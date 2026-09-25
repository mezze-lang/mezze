package com.mezze.interpreter.comptime

import com.mezze.datastructures.seq.MezzeSeq
import com.mezze.interpreter.values.MezzeRecord
import com.mezze.interpreter.values.MezzeVariant
import com.mezze.language.truffleir.*

/**
 * Turns a value computed at compile time into IR that rebuilds it.
 *
 * The sibling of `InspectNatives.render`, and deliberately so: both walk
 * (value, [PrintType]) in lockstep, for the same reason. The runtime value
 * alone cannot say whether a `Long` is an `Int` or a `Nat`, whether a
 * two-field record is a `Frac`, or which newtype erased to the value in hand —
 * so the static shape decides and the value supplies the contents.
 *
 *     render(value, PrintType) -> String     // for a human
 *     reify (value, PrintType) -> NodeIR     // for the compiled program
 *
 * ## Rebuild, not "literal"
 *
 * The IR's literal vocabulary stops at records, variants and seqs. Everything
 * else is reconstructed by a call — a `List` is `__native_list_from_seq` over a
 * reified seq. So the question a shape has to answer is not "can it be written
 * down" but "can it be rebuilt by pure IR", which is a larger and better-defined
 * set.
 *
 * ## Where `render` is total and this is not
 *
 * `render` can print anything, `Function` included. This cannot: a closure is a
 * code pointer plus captured values, with no IR that reconstructs it. Reaching
 * such a case means [com.mezze.language.typechecker.WellKnownHashConstants]'s
 * `Embeddable` obligation let something through, so it is reported as a
 * compiler-internal failure carrying the path to the offending sub-value —
 * `result.items[3].callback` — rather than as a user error.
 */
object ComptimeReifier {

    /** Raised when a value has no IR that rebuilds it. Carries the path to the
     *  exact sub-value, because in a nested structure the top-level type is not
     *  where the problem is. */
    class NotEmbeddable(val path: String, message: String) : RuntimeException(message)

    fun reify(value: Any?, shape: PrintType, src: SourceInfo): NodeIR =
        reify(value, shape, src, emptyMap(), "result")

    private fun reify(
        value: Any?,
        shape: PrintType,
        src: SourceInfo,
        defs: Map<String, PrintType>,
        path: String,
    ): NodeIR = when (shape) {

        // Recursive shapes carry their own definition table; a Ref resolves
        // against it. Same contract as the printer — a cyclic TYPE still has a
        // finite SHAPE, so this terminates on the value, not on the type.
        is PrintType.Recursive -> reify(value, shape.root, src, shape.defs, path)
        is PrintType.Ref -> {
            val target = defs[shape.name]
                ?: throw IllegalStateException(
                    "PrintType.Ref('${shape.name}') with no definition in scope — " +
                        "the builder emitted a back-reference without its table."
                )
            reify(value, target, src, defs, path)
        }

        is PrintType.Scalar -> reifyScalar(value, shape.kind, src, path)

        // Frac is an ordinary two-field record at runtime; only the shape knows
        // it is a fraction.
        is PrintType.Frac -> {
            val r = value as MezzeRecord
            FracLitIR(
                (r.getField(r.getFieldIndex("numerator")) as Number).toLong(),
                (r.getField(r.getFieldIndex("denominator")) as Number).toLong(),
                src,
            )
        }

        // Erased at runtime: the value already IS the underlying one, so there
        // is nothing to unwrap and nothing to re-wrap.
        is PrintType.Newtype -> reify(value, shape.inner, src, defs, path)

        is PrintType.Record -> MakeRecordIR(
            shape.fields.map { f ->
                val r = value as MezzeRecord
                FieldIR(f.name, reify(r.getField(r.getFieldIndex(f.name)), f.type, src, defs, "$path.${f.name}"))
            },
            src,
        )

        is PrintType.Variant -> {
            val v = value as MezzeVariant
            val name = v.constructorName
            val arm = shape.arms.firstOrNull { it.constructor == name }
                ?: throw NotEmbeddable(
                    path,
                    "comptime produced constructor `$name` at `$path`, which the " +
                        "result's static shape does not list.",
                )
            MakeVariantIR(
                v.tag,
                name,
                arm.payload?.let { reify(v.payloadValue, it, src, defs, "$path.$name") },
                src,
            )
        }

        is PrintType.MzSeq -> reifySeq(value, shape.inner, src, defs, path)

        // No literal form — rebuilt by the same `from_seq` native the stdlib
        // calls, over a reified seq.
        is PrintType.MzList -> fromSeq("__native_list_from_seq", value, shape.inner, src, defs, path)
        is PrintType.MzSet -> fromSeq("__native_set_from_seq", value, shape.inner, src, defs, path)
        is PrintType.MzDeque -> fromSeq("__native_deque_from_seq", value, shape.inner, src, defs, path)
        is PrintType.MzNonEmptyList ->
            fromSeq("__native_nel_from_seq", value, shape.inner, src, defs, path)

        // Maps have no literal form and no element type — they are rebuilt from a
        // seq of `{ k, v }` records, which is exactly what the native consumes.
        is PrintType.MzMap -> fromPairs("__native_map_from_seq", value, shape.key, shape.value, src, defs, path)

        // Sorted containers. Their natives take a comparator, which is a FUNCTION
        // and therefore the one thing reification cannot build — so the natives
        // were given an ordering-free path instead: an absent `cmp` means natural
        // ordering, which `ElementComparator.of(null)` already supported and was
        // simply not expressible. A reified sorted container relies on the
        // element's natural order, which is what `Ord` gave it in the first place.
        is PrintType.MzSortedSet ->
            fromSeq("__native_sorted_set_from_seq", value, shape.inner, src, defs, path)

        // Built from a reified Map — `from_map` is the shape the native takes.
        is PrintType.MzSortedMap -> CallNativeIR(
            name = "__native_sorted_map_from_map",
            arg = MakeRecordIR(
                listOf(FieldIR("m", fromPairs(
                    "__native_map_from_seq", value, shape.key, shape.value, src, defs, path,
                ))),
                src,
            ),
            src = src,
        )

        is PrintType.Function -> throw NotEmbeddable(
            path,
            "comptime produced a function at `$path`. A function value is a code " +
                "pointer plus its captured values, and there is no way to write that " +
                "into the compiled program.",
        )

        // Every remaining shape is one `Embeddable` admits but reification does
        // not yet build. Named explicitly rather than swallowed, so the gap is
        // visible and closing it is mechanical.
        else -> throw NotEmbeddable(
            path,
            "comptime cannot yet embed a value of shape ${shape::class.simpleName} " +
                "at `$path`. Return a record, variant, seq, list, set or deque instead.",
        )
    }

    private fun reifyScalar(
        value: Any?,
        kind: PrintType.ScalarKind,
        src: SourceInfo,
        path: String,
    ): NodeIR = when (kind) {
        // Nat is a Long at runtime exactly like Int — the shape is the only
        // thing that tells them apart, which is this walker's whole reason to
        // exist.
        PrintType.ScalarKind.Int, PrintType.ScalarKind.Nat ->
            LongLitIR((value as Number).toLong(), src)
        PrintType.ScalarKind.Float -> DoubleLitIR((value as Number).toDouble(), src)
        PrintType.ScalarKind.Str -> StringLitIR(value as String, src)
        PrintType.ScalarKind.Bool -> BoolLitIR(value as Boolean, src)
        PrintType.ScalarKind.Char -> CharLitIR(
            when (value) {
                is Char -> value
                is String -> value[0]
                else -> throw NotEmbeddable(path, "comptime produced a non-char for a Char at `$path`")
            },
            src,
        )
        // No byte-literal IR node, so rebuilt from a seq of ints through the same
        // native `bytes::from_seq` calls.
        PrintType.ScalarKind.Bytes -> CallNativeIR(
            name = "__native_bytes_from_seq",
            arg = MakeRecordIR(
                listOf(FieldIR("arr", MakeSeqIR(
                    byteValuesOf(value, path).map {
                        SeqElementIR.Regular(LongLitIR(it, src))
                    },
                    src,
                ))),
                src,
            ),
            src = src,
        )
    }

    private fun reifySeq(
        value: Any?,
        inner: PrintType,
        src: SourceInfo,
        defs: Map<String, PrintType>,
        path: String,
    ): MakeSeqIR = MakeSeqIR(
        elementsOf(value).mapIndexed { i, el ->
            SeqElementIR.Regular(reify(el, inner, src, defs, "$path[$i]"))
        },
        src,
    )

    /** Rebuild a seq-backed container by handing a reified seq to the same
     *  native the stdlib's own `from_seq` calls. */
    private fun fromSeq(
        native: String,
        value: Any?,
        inner: PrintType,
        src: SourceInfo,
        defs: Map<String, PrintType>,
        path: String,
    ): NodeIR = CallNativeIR(
        name = native,
        arg = MakeRecordIR(listOf(FieldIR("xs", reifySeq(value, inner, src, defs, path))), src),
        src = src,
    )

    /** Rebuild a map from a seq of `{ k, v }` records — the encoding
     *  `MapNatives.fromSeq` reads. */
    private fun fromPairs(
        native: String,
        value: Any?,
        keyShape: PrintType,
        valueShape: PrintType,
        src: SourceInfo,
        defs: Map<String, PrintType>,
        path: String,
    ): NodeIR {
        val entries = entriesOf(value).mapIndexed { i, (k, v) ->
            SeqElementIR.Regular(
                MakeRecordIR(
                    listOf(
                        FieldIR("k", reify(k, keyShape, src, defs, "$path[$i].k")),
                        FieldIR("v", reify(v, valueShape, src, defs, "$path[$i].v")),
                    ),
                    src,
                )
            )
        }
        return CallNativeIR(
            name = native,
            arg = MakeRecordIR(listOf(FieldIR("xs", MakeSeqIR(entries, src))), src),
            src = src,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun entriesOf(value: Any?): List<Pair<Any?, Any?>> {
        if (value !is Iterable<*>) {
            throw NotEmbeddable("?", "comptime produced a non-map where a map was expected: $value")
        }
        // Both map representations iterate as entries; read key/value
        // reflectively so this does not have to name either concrete Entry type
        // (they differ, and neither is a Kotlin Pair).
        return value.map { e ->
            requireNotNull(e)
            val k = e.javaClass.getMethod("key").invoke(e)
            val v = e.javaClass.getMethod("value").invoke(e)
            k to v
        }
    }

    /** What a value IS, for a message about what it should have been. The class
     *  name is the part that tells you where to look. */
    private fun describe(value: Any?): String =
        if (value == null) "nothing" else "a ${value.javaClass.simpleName}"

    /**
     * The bytes behind a `Bytes` value.
     *
     * Three unrelated classes in this repo are called `MezzeBytes`, and the one
     * the `bytes::*` natives actually build is the nested
     * [com.mezze.interpreter.nodes.builtin.io.BytesNatives.MezzeBytes] — not the
     * `datastructures` one the name suggests. The others are matched too, since
     * reification only needs the bytes and getting the wrong one back is a
     * failed build, not a wrong answer.
     */
    private fun byteValuesOf(value: Any?, path: String): List<Long> = when (value) {
        is com.mezze.interpreter.nodes.builtin.io.BytesNatives.MezzeBytes ->
            value.data.map { it.toLong() and 0xFF }
        is com.mezze.datastructures.bytes.MezzeBytes ->
            (0 until value.size()).map { value.get(it).toLong() and 0xFF }
        is com.mezze.interpreter.values.MezzeBytes ->
            value.toByteArray().map { it.toLong() and 0xFF }
        is ByteArray -> value.map { it.toLong() and 0xFF }
        else -> throw NotEmbeddable(
            path,
            "comptime produced ${describe(value)} where Bytes was expected at `$path`",
        )
    }

    private fun elementsOf(value: Any?): List<Any?> = when (value) {
        is MezzeSeq<*> -> (0 until value.size()).map { value.get(it) }
        is Iterable<*> -> value.toList()
        else -> throw NotEmbeddable("?", "comptime produced a non-iterable where a sequence was expected: $value")
    }
}
