package com.mezze.interpreter.nodes.builtin.effects

import com.mezze.datastructures.map.MezzeMap
import com.mezze.datastructures.sorted.MezzeSortedMap
import com.mezze.interpreter.values.MezzeFunction
import com.mezze.interpreter.values.MezzeRecord
import com.mezze.interpreter.values.MezzeVariant
import com.mezze.language.truffleir.PrintType
import com.mezze.wire.WireValue

/**
 * Walk `(value, PrintType)` into a [WireValue] tree.
 *
 * This is the same lockstep walk [InspectNatives.render] performs, emitting a
 * tree instead of concatenating a String. Two walks rather than one because
 * `render` is `to_str`/`to_dbg` — it runs inside user programs, in loops, on the
 * hot path — and making it allocate a tree per call only to fold it back to text
 * would charge every caller who wanted nothing but the text.
 *
 * What keeps them from drifting is not shared structure but two things:
 *  - every LEAF is formatted by the same [InspectNatives] helper, so `Nat`,
 *    `Frac`, `Char`, `Bytes` and function rendering have exactly one
 *    implementation; and
 *  - `InspectTreeAgreementTest` asserts `build(…).toText() == render(…)` across
 *    every `PrintType` case, so structural drift fails a test.
 *
 * ## Bounds
 *
 * [maxDepth] and [maxItems] are required, not advisory: the walk reads a live
 * runtime value, and a deep or wide one would otherwise serialise without limit
 * to answer "what is x". Anything cut becomes a [WireValue.Elided] naming its own
 * path, which a caller can then ask for specifically.
 *
 * Only COMPOSITE nodes are ever elided. A scalar at the depth limit still
 * renders — eliding it would discard the very thing being looked for to save
 * nothing.
 */
object InspectTree {

    /**
     * Build the tree for [value] as described by [printType].
     *
     * [dbgStyle] selects `to_dbg` (quoted strings and chars) over `to_str`,
     * exactly as in [InspectNatives.render], and reaches the leaves only.
     */
    fun build(
        value: Any?,
        printType: PrintType,
        dbgStyle: Boolean,
        maxDepth: Int,
        maxItems: Int,
    ): WireValue = Walk(dbgStyle, maxDepth, maxItems)
        .node(value, printType, emptyMap(), depth = 0, path = WireValue.Path.ROOT)

    /** One build's settings, so the recursion carries only what varies. */
    private class Walk(
        private val dbgStyle: Boolean,
        private val maxDepth: Int,
        private val maxItems: Int,
    ) {

        fun node(
            value: Any?,
            printType: PrintType,
            defs: Map<String, PrintType>,
            depth: Int,
            path: String,
        ): WireValue {
            // PrintType.Recursive / Ref describe a cyclic TYPE and are resolved
            // here, never surfaced. A WireValue.Elided is about a large VALUE —
            // a different thing that happens to look similar. Mirrors render.
            if (printType is PrintType.Recursive) {
                return node(value, printType.root, printType.defs, depth, path)
            }
            if (printType is PrintType.Ref) {
                val target = defs[printType.name]
                    ?: throw IllegalStateException(
                        "PrintType.Ref('${printType.name}') with no definition in scope — " +
                            "the builder emitted a back-reference without its table."
                    )
                return node(value, target, defs, depth, path)
            }

            // Leaves are never elided, so they are settled before the depth check.
            leafOrNull(value, printType)?.let { return it }

            if (depth >= maxDepth) return WireValue.Elided(path, hintFor(printType))

            return when (printType) {
                // Newtype is erased at runtime — `value` IS the underlying value.
                is PrintType.Newtype -> WireValue.Newtype(
                    name = printType.name,
                    inner = node(value, printType.inner, defs, depth + 1, WireValue.Path.inner(path)),
                )

                is PrintType.Record -> {
                    val record = value as? MezzeRecord ?: mismatch("record", value)
                    WireValue.Record(
                        printType.fields.map { f ->
                            WireValue.Field(
                                name = f.name,
                                value = node(
                                    record.getField(record.getFieldIndex(f.name)),
                                    f.type, defs, depth + 1, WireValue.Path.field(path, f.name),
                                ),
                            )
                        }
                    )
                }

                is PrintType.Variant -> variant(value, printType, defs, depth, path)

                is PrintType.MzList ->
                    sequence(WireValue.Sequence.Kind.LIST, value, printType.inner, defs, depth, path)
                is PrintType.MzSeq ->
                    sequence(WireValue.Sequence.Kind.SEQ, value, printType.inner, defs, depth, path)
                is PrintType.MzSet ->
                    sequence(WireValue.Sequence.Kind.SET, value, printType.inner, defs, depth, path)
                is PrintType.MzSortedSet ->
                    sequence(WireValue.Sequence.Kind.SORTED_SET, value, printType.inner, defs, depth, path)
                is PrintType.MzDeque ->
                    sequence(WireValue.Sequence.Kind.DEQUE, value, printType.inner, defs, depth, path)
                is PrintType.MzNonEmptyList ->
                    sequence(WireValue.Sequence.Kind.NON_EMPTY_LIST, value, printType.inner, defs, depth, path)

                is PrintType.MzMap ->
                    mapping(WireValue.Mapping.Kind.MAP, value, printType.key, printType.value, defs, depth, path)
                is PrintType.MzSortedMap ->
                    mapping(WireValue.Mapping.Kind.SORTED_MAP, value, printType.key, printType.value, defs, depth, path)

                // Handled by leafOrNull / the Ref cases above. Enumerated rather
                // than `else`-d so a new PrintType case is a compile error here,
                // not a silent fall-through.
                is PrintType.Scalar, PrintType.Frac, PrintType.Function, PrintType.Dynamic,
                is PrintType.Ref, is PrintType.Recursive,
                -> throw IllegalStateException(
                    "InspectTree: $printType reached the composite branch — leafOrNull " +
                        "and the Ref cases should have claimed it."
                )
            }
        }

        /** Every PrintType that renders without children, or null if it has some. */
        private fun leafOrNull(value: Any?, printType: PrintType): WireValue? = when (printType) {
            is PrintType.Scalar -> WireValue.Scalar(
                kind = scalarKindName(printType.kind),
                text = InspectNatives.renderScalar(value, printType.kind, dbgStyle),
            )

            PrintType.Frac -> WireValue.Scalar(
                kind = WireValue.Scalar.Kind.FRAC,
                text = InspectNatives.renderFrac(value),
            )

            PrintType.Function -> WireValue.Fn(
                name = (value as? MezzeFunction ?: mismatch("function", value)).name
            )

            // No static type at this position (an unconstrained type parameter).
            // The runtime value alone renders every runtime-distinguishable type
            // correctly; `to_str` on a bare Str wants it unquoted, and
            // valueToString always quotes.
            PrintType.Dynamic -> WireValue.Scalar(
                kind = WireValue.Scalar.Kind.DYNAMIC,
                text = if (!dbgStyle && value is String) value else InspectNatives.valueToString(value),
            )

            else -> null
        }

        private fun variant(
            value: Any?,
            printType: PrintType.Variant,
            defs: Map<String, PrintType>,
            depth: Int,
            path: String,
        ): WireValue {
            val v = value as? MezzeVariant ?: mismatch("variant", value)
            val ctor = v.constructorName
            val arm = printType.arms.firstOrNull { it.constructor == ctor }
                // Constructor absent from the compile-time shape: the value came
                // through a polymorphic catch-all arm or an open variant type.
                // Render dynamically rather than failing, as render does.
                ?: return WireValue.Scalar(
                    kind = WireValue.Scalar.Kind.DYNAMIC,
                    text = InspectNatives.valueToString(value),
                )

            val payloadType = arm.payload
            if (payloadType == null || !v.hasPayload()) return WireValue.Variant(ctor)
            return WireValue.Variant(
                constructor = ctor,
                payload = node(v.payloadValue, payloadType, defs, depth + 1, WireValue.Path.payload(path)),
            )
        }

        private fun sequence(
            kind: String,
            value: Any?,
            inner: PrintType,
            defs: Map<String, PrintType>,
            depth: Int,
            path: String,
        ): WireValue {
            val items = ArrayList<WireValue>()
            var seen = 0
            for (element in InspectNatives.iterableOf(value)) {
                // Indexed by position in the collection, which coincides with the
                // position in `items` because truncation only ever drops a
                // SUFFIX. Written as `seen` because position-in-the-collection is
                // what a path means; the two would diverge the moment this
                // sampled or filtered rather than truncating.
                if (seen < maxItems) {
                    items.add(node(element, inner, defs, depth + 1, WireValue.Path.index(path, seen)))
                }
                seen++
            }
            return WireValue.Sequence(kind, items, omitted = seen - items.size)
        }

        private fun mapping(
            kind: String,
            value: Any?,
            keyType: PrintType,
            valueType: PrintType,
            defs: Map<String, PrintType>,
            depth: Int,
            path: String,
        ): WireValue {
            val entries = ArrayList<WireValue.Entry>()
            var seen = 0

            fun add(k: Any?, v: Any?) {
                if (seen < maxItems) {
                    entries.add(
                        WireValue.Entry(
                            key = node(k, keyType, defs, depth + 1, WireValue.Path.key(path, seen)),
                            value = node(v, valueType, defs, depth + 1, WireValue.Path.value(path, seen)),
                        )
                    )
                }
                seen++
            }

            when (value) {
                is MezzeMap<*, *> -> for (e in value) add(e.key, e.value)
                is MezzeSortedMap<*, *> -> for (e in value.rawEntries()) add(e.key(), e.value())
                else -> mismatch("map", value)
            }
            return WireValue.Mapping(kind, entries, omitted = seen - entries.size)
        }
    }

    private fun mismatch(expected: String, value: Any?): Nothing = throw IllegalStateException(
        "Inspect: expected a $expected runtime value, got " + (value?.javaClass?.name ?: "null")
    )

    private fun scalarKindName(kind: PrintType.ScalarKind): String = when (kind) {
        PrintType.ScalarKind.Int -> WireValue.Scalar.Kind.INT
        PrintType.ScalarKind.Nat -> WireValue.Scalar.Kind.NAT
        PrintType.ScalarKind.Float -> WireValue.Scalar.Kind.FLOAT
        PrintType.ScalarKind.Str -> WireValue.Scalar.Kind.STR
        PrintType.ScalarKind.Bool -> WireValue.Scalar.Kind.BOOL
        PrintType.ScalarKind.Char -> WireValue.Scalar.Kind.CHAR
        PrintType.ScalarKind.Bytes -> WireValue.Scalar.Kind.BYTES
    }

    /** What an elided node reports was cut, so a UI can label it. */
    private fun hintFor(printType: PrintType): String = when (printType) {
        is PrintType.Record -> "record"
        is PrintType.Variant -> "variant"
        is PrintType.Newtype -> "newtype"
        is PrintType.MzMap, is PrintType.MzSortedMap -> "map"
        else -> "seq"
    }
}
