package com.mezze.interpreter

import com.mezze.datastructures.deque.MezzeDeque
import com.mezze.datastructures.list.MezzeList
import com.mezze.datastructures.map.MezzeMap
import com.mezze.datastructures.seq.MezzeSeq
import com.mezze.datastructures.set.MezzeSet
import com.mezze.datastructures.sorted.MezzeSortedMap
import com.mezze.datastructures.sorted.MezzeSortedSet
import com.mezze.interpreter.nodes.builtin.collections.NonEmptyListNatives
import com.mezze.interpreter.nodes.builtin.effects.InspectNatives
import com.mezze.interpreter.nodes.builtin.effects.InspectTree
import com.mezze.interpreter.nodes.builtin.io.BytesNatives
import com.mezze.interpreter.values.MezzeRecord
import com.mezze.interpreter.values.MezzeVariant
import com.mezze.language.truffleir.PrintType
import com.mezze.wire.WireValue
import com.mezze.wire.toText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * THE INVARIANT: the value tree and the string the language prints are the same
 * information.
 *
 * `InspectNatives.render` and [InspectTree] walk `(value, PrintType)` twice —
 * deliberately, because `render` is `to_str`/`to_dbg` and runs inside user
 * programs on the hot path, so making it allocate a tree to then fold back to
 * text would charge every caller who only ever wanted text. The cost of two
 * walks is that they can drift. This is what stops that: for every `PrintType`
 * case, `build(…).toText()` must equal `render(…)` exactly.
 *
 * Drift here would not look like a crash. It would look like a `Nat` printed
 * `7n` by a program and `7` in a REPL beside it — the kind of disagreement
 * nobody notices until they are debugging something else entirely.
 */
class InspectTreeAgreementTest {

    /** A case: a runtime value, the static shape it is rendered against. */
    private data class Case(
        val label: String,
        val value: Any?,
        val printType: PrintType,
        /** Which `PrintType` case this exercises, for the coverage check. */
        val covers: String,
    )

    private val intT = PrintType.Scalar(PrintType.ScalarKind.Int)
    private val strT = PrintType.Scalar(PrintType.ScalarKind.Str)

    private fun record(vararg pairs: Pair<String, Any?>): MezzeRecord =
        MezzeRecord.fromFields(pairs.map { it.first }.toTypedArray(), pairs.map { it.second }.toTypedArray())

    /** `type Tree = | Node { l: Tree, r: Tree }, Leaf { n: Int } |` — a cyclic TYPE. */
    private val treeRegistry = MezzeVariant.TagRegistry(arrayOf("Node", "Leaf"))
    private val treeShape: PrintType = PrintType.Recursive(
        defs = mapOf(
            "Tree" to PrintType.Variant(
                listOf(
                    PrintType.Arm(
                        "Node",
                        PrintType.Record(
                            listOf(
                                PrintType.Field("l", PrintType.Ref("Tree")),
                                PrintType.Field("r", PrintType.Ref("Tree")),
                            )
                        ),
                    ),
                    PrintType.Arm("Leaf", intT),
                )
            )
        ),
        root = PrintType.Ref("Tree"),
    )

    private fun leaf(n: Long) = MezzeVariant.singlePayload(treeRegistry, 1, n)

    private val optionRegistry = MezzeVariant.TagRegistry(arrayOf("None", "Some"))
    private val optionShape = PrintType.Variant(
        listOf(PrintType.Arm("None", null), PrintType.Arm("Some", intT))
    )

    private val cases: List<Case> = listOf(
        // Scalars — one per ScalarKind, because the kinds are exactly the
        // distinctions the runtime value alone cannot make.
        Case("int", 42L, intT, "Scalar"),
        Case("nat", 7L, PrintType.Scalar(PrintType.ScalarKind.Nat), "Scalar"),
        Case("float", 3.5, PrintType.Scalar(PrintType.ScalarKind.Float), "Scalar"),
        Case("str", "hi", strT, "Scalar"),
        Case("str-empty", "", strT, "Scalar"),
        Case("bool-true", true, PrintType.Scalar(PrintType.ScalarKind.Bool), "Scalar"),
        Case("bool-false", false, PrintType.Scalar(PrintType.ScalarKind.Bool), "Scalar"),
        Case("char-boxed", 'x', PrintType.Scalar(PrintType.ScalarKind.Char), "Scalar"),
        // Char has two runtime reps — a boxed Character from a literal, and a
        // one-char String from char::from_int. Both must agree.
        Case("char-as-string", "y", PrintType.Scalar(PrintType.ScalarKind.Char), "Scalar"),
        Case(
            "bytes",
            BytesNatives.MezzeBytes(byteArrayOf(0x01, 0x0f, 0x7f.toByte())),
            PrintType.Scalar(PrintType.ScalarKind.Bytes),
            "Scalar",
        ),

        Case("frac", record("numerator" to 3L, "denominator" to 4L), PrintType.Frac, "Frac"),

        // Newtype is erased at runtime: the value IS the payload.
        Case("newtype", 5L, PrintType.Newtype("Money", intT), "Newtype"),
        Case(
            "newtype-nested",
            5L,
            PrintType.Newtype("Money", PrintType.Newtype("Cents", intT)),
            "Newtype",
        ),

        Case("record-empty", MezzeRecord.empty(), PrintType.Record(emptyList()), "Record"),
        Case(
            "record",
            record("x" to 1L, "y" to "a"),
            PrintType.Record(listOf(PrintType.Field("x", intT), PrintType.Field("y", strT))),
            "Record",
        ),
        Case(
            "record-nested",
            record("inner" to record("x" to 1L), "n" to 2L),
            PrintType.Record(
                listOf(
                    PrintType.Field("inner", PrintType.Record(listOf(PrintType.Field("x", intT)))),
                    PrintType.Field("n", intT),
                )
            ),
            "Record",
        ),

        Case("variant-no-payload", MezzeVariant.noPayload(optionRegistry, 0), optionShape, "Variant"),
        Case("variant-payload", MezzeVariant.singlePayload(optionRegistry, 1, 1L), optionShape, "Variant"),
        // A constructor absent from the compile-time shape: the value arrived
        // through a polymorphic catch-all arm or an open variant. render falls
        // back to dynamic rendering rather than failing, and so must the tree.
        Case(
            "variant-unknown-constructor",
            MezzeVariant.singlePayload(MezzeVariant.TagRegistry(arrayOf("Weird")), 0, 1L),
            PrintType.Variant(listOf(PrintType.Arm("Other", null))),
            "Variant",
        ),

        Case("list", MezzeList.of(1L, 2L), PrintType.MzList(intT), "MzList"),
        Case("list-empty", MezzeList.empty<Any>(), PrintType.MzList(intT), "MzList"),
        Case("seq", MezzeSeq.of(1L, 2L, 3L), PrintType.MzSeq(intT), "MzSeq"),
        Case("seq-empty", MezzeSeq.empty<Any>(), PrintType.MzSeq(intT), "MzSeq"),
        Case("seq-of-str", MezzeSeq.of("a", "b"), PrintType.MzSeq(strT), "MzSeq"),
        Case("set", MezzeSet.of(1L, 2L), PrintType.MzSet(intT), "MzSet"),
        Case("set-empty", MezzeSet.empty<Any>(), PrintType.MzSet(intT), "MzSet"),
        Case("sorted-set", MezzeSortedSet.of(2L, 1L), PrintType.MzSortedSet(intT), "MzSortedSet"),
        Case("deque", MezzeDeque.of(1L, 2L), PrintType.MzDeque(intT), "MzDeque"),
        Case("deque-empty", MezzeDeque.empty<Any>(), PrintType.MzDeque(intT), "MzDeque"),
        Case(
            "non-empty-list",
            NonEmptyListNatives.fromHeadTail(1L, MezzeList.of(2L)),
            PrintType.MzNonEmptyList(intT),
            "MzNonEmptyList",
        ),

        Case("map", MezzeMap.of<Any, Any>(1L, "a"), PrintType.MzMap(intT, strT), "MzMap"),
        Case("map-empty", MezzeMap.empty<Any, Any>(), PrintType.MzMap(intT, strT), "MzMap"),
        Case(
            "sorted-map",
            MezzeSortedMap.of<Any, Any>(1L, "a"),
            PrintType.MzSortedMap(intT, strT),
            "MzSortedMap",
        ),
        Case("sorted-map-empty", MezzeSortedMap.empty<Any, Any>(), PrintType.MzSortedMap(intT, strT), "MzSortedMap"),

        // Dynamic: no static type at this position. Rendered from the runtime
        // value alone, which is correct for everything runtime-distinguishable.
        Case("dynamic-int", 1L, PrintType.Dynamic, "Dynamic"),
        Case("dynamic-str", "hi", PrintType.Dynamic, "Dynamic"),
        Case("dynamic-record", record("x" to 1L), PrintType.Dynamic, "Dynamic"),

        // A cyclic TYPE resolved through the Recursive defs table. This is the
        // case a value tree must NOT confuse with an elided branch.
        Case(
            "recursive",
            MezzeVariant.singlePayload(treeRegistry, 0, record("l" to leaf(1), "r" to leaf(2))),
            treeShape,
            "Recursive",
        ),
        Case("recursive-at-leaf", leaf(9), treeShape, "Ref"),
    )

    /** Unbounded, so the only difference between the two walks can be structural. */
    private fun tree(c: Case, dbgStyle: Boolean): WireValue =
        InspectTree.build(c.value, c.printType, dbgStyle, maxDepth = 64, maxItems = 1000)

    @Test
    fun `the tree renders exactly what to_dbg renders`() {
        for (c in cases) {
            val expected = InspectNatives.render(c.value, c.printType, true)
            assertEquals(expected, tree(c, dbgStyle = true).toText(), "case '${c.label}' (to_dbg)")
        }
    }

    @Test
    fun `the tree renders exactly what to_str renders`() {
        // Not redundant with the to_dbg case: dbgStyle changes the LEAVES (a Str
        // loses its quotes), so a builder that ignored the flag would pass one
        // of these two and fail the other.
        for (c in cases) {
            val expected = InspectNatives.render(c.value, c.printType, false)
            assertEquals(expected, tree(c, dbgStyle = false).toText(), "case '${c.label}' (to_str)")
        }
    }

    @Test
    fun `to_dbg and to_str actually differ somewhere`() {
        // Guards the two tests above from being the same assertion twice: if
        // dbgStyle stopped reaching the leaves, both would still pass.
        val differing = cases.count { c ->
            tree(c, dbgStyle = true).toText() != tree(c, dbgStyle = false).toText()
        }
        assertTrue(differing > 0, "no case distinguishes to_dbg from to_str, so neither test proves anything")
    }

    @Test
    fun `every PrintType case is covered`() {
        // The compile-time guard is `InspectTree.node`'s `when`, which has no
        // `else` over the sealed hierarchy — a new PrintType case fails to
        // compile. This is the second half: a case that compiles but was never
        // RUN against render is untested, and the whole value of this file is
        // the comparison.
        val all = setOf(
            "Scalar", "Frac", "Newtype", "Record", "Variant",
            "MzList", "MzSeq", "MzSet", "MzSortedSet", "MzDeque", "MzNonEmptyList",
            "MzMap", "MzSortedMap", "Function", "Dynamic", "Ref", "Recursive",
        )
        val covered = cases.map { it.covers }.toSet() + "Function"  // see the Function test below
        assertEquals(emptySet<String>(), all - covered, "PrintType cases with no agreement case")
        assertEquals(emptySet<String>(), covered - all, "coverage labels naming no PrintType case")
    }

    @Test
    fun `a function value renders the same through both walks`() {
        // Separate because a MezzeFunction needs a CallTarget, which the
        // table-driven cases construct nothing else for. Only the name is read.
        val fn = com.mezze.interpreter.values.MezzeFunction(null, "double", arrayOfNulls<Any>(0))
        assertEquals(
            InspectNatives.render(fn, PrintType.Function, true),
            InspectTree.build(fn, PrintType.Function, true, 64, 1000).toText(),
        )
        assertEquals(WireValue.Fn("double"), InspectTree.build(fn, PrintType.Function, true, 64, 1000))

        val anon = com.mezze.interpreter.values.MezzeFunction(null, null, arrayOfNulls<Any>(0))
        assertEquals(WireValue.Fn(null), InspectTree.build(anon, PrintType.Function, true, 64, 1000))
    }

    // ---- the tree is more than a string ------------------------------------

    @Test
    fun `the tree keeps structure a string has already thrown away`() {
        // The reason for the tree at all: `{ x = 1, y = "a" }` is one string, but
        // a browser needs the field names and a navigable child per field.
        val c = cases.first { it.label == "record" }
        val built = tree(c, dbgStyle = true)
        val record = built as WireValue.Record
        assertEquals(listOf("x", "y"), record.fields.map { it.name })
        assertEquals(WireValue.Scalar("int", "1"), record.fields[0].value)
        assertEquals(WireValue.Scalar("str", "\"a\""), record.fields[1].value)
    }

    @Test
    fun `a recursive type resolves rather than eliding`() {
        // PrintType.Ref is about a cyclic TYPE and is resolved during the walk;
        // WireValue.Elided is about a large VALUE. Confusing the two would show a
        // navigable structure as an ellipsis.
        val c = cases.first { it.label == "recursive" }
        val built = tree(c, dbgStyle = true) as WireValue.Variant
        assertEquals("Node", built.constructor)
        val payload = built.payload as WireValue.Record
        assertEquals(listOf("l", "r"), payload.fields.map { it.name })
        assertTrue(
            payload.fields.all { it.value is WireValue.Variant },
            "a resolved Ref should yield the variant it names, got ${payload.fields.map { it.value }}",
        )
    }

    // ---- bounds ------------------------------------------------------------

    @Test
    fun `the depth limit elides composites and names their path`() {
        val value = record("inner" to record("deeper" to record("x" to 1L)))
        val shape = PrintType.Record(
            listOf(
                PrintType.Field(
                    "inner",
                    PrintType.Record(
                        listOf(
                            PrintType.Field(
                                "deeper",
                                PrintType.Record(listOf(PrintType.Field("x", intT))),
                            )
                        )
                    ),
                )
            )
        )

        val built = InspectTree.build(value, shape, true, maxDepth = 2, maxItems = 100)
        val inner = (built as WireValue.Record).fields.single().value as WireValue.Record
        val elided = inner.fields.single().value as WireValue.Elided

        // The path is the node's own address, so a client echoes it back rather
        // than having to track where it was.
        assertEquals("f:inner/f:deeper", elided.path)
        assertEquals("record", elided.hint)
        assertEquals("{ inner = { deeper = … } }", built.toText())
    }

    @Test
    fun `a scalar at the depth limit still renders`() {
        // Eliding a leaf would discard the thing being looked for to save
        // nothing: only composites are worth cutting.
        val built = InspectTree.build(
            record("x" to 1L),
            PrintType.Record(listOf(PrintType.Field("x", intT))),
            true, maxDepth = 1, maxItems = 100,
        )
        assertEquals("{ x = 1 }", built.toText())
    }

    @Test
    fun `the item limit reports how many it dropped`() {
        val value = MezzeSeq.of(1L, 2L, 3L, 4L, 5L)
        val built = InspectTree.build(value, PrintType.MzSeq(intT), true, maxDepth = 64, maxItems = 2)
            as WireValue.Sequence

        assertEquals(2, built.items.size)
        assertEquals(3, built.omitted)
        assertEquals("[1, 2, …3 more]", built.toText())
    }

    @Test
    fun `element paths are contiguous positions counted from zero`() {
        // What an expand depends on: the path of the nth element says `i:n`. Note
        // this does NOT distinguish counting the collection from counting the
        // emitted items — truncation drops a suffix, so the two coincide. The
        // claim being pinned is the numbering itself, not which counter produced
        // it.
        val value = MezzeSeq.of(
            record("x" to 1L), record("x" to 2L), record("x" to 3L), record("x" to 4L),
        )
        val built = InspectTree.build(
            value,
            PrintType.MzSeq(PrintType.Record(listOf(PrintType.Field("x", intT)))),
            true, maxDepth = 1, maxItems = 3,
        ) as WireValue.Sequence

        assertEquals(
            listOf("i:0", "i:1", "i:2"),
            built.items.map { (it as WireValue.Elided).path },
        )
        assertEquals(1, built.omitted)
    }

    @Test
    fun `map entries elide by key and value position`() {
        val value = MezzeMap.of<Any, Any>(1L, record("x" to 1L))
        val built = InspectTree.build(
            value,
            PrintType.MzMap(intT, PrintType.Record(listOf(PrintType.Field("x", intT)))),
            true, maxDepth = 1, maxItems = 10,
        ) as WireValue.Mapping

        val entry = built.entries.single()
        assertEquals(WireValue.Scalar("int", "1"), entry.key)
        assertEquals("v:0", (entry.value as WireValue.Elided).path)
    }

    @Test
    fun `an unbounded build produces no elided nodes at all`() {
        // The agreement tests above depend on this: they compare against a
        // renderer that never elides, so any Elided node would make them
        // compare an ellipsis to real output.
        for (c in cases) {
            assertTrue(
                WireValue.ELLIPSIS !in tree(c, dbgStyle = true).toText(),
                "case '${c.label}' elided at depth 64 — the agreement tests are not comparing what they claim",
            )
        }
    }
}
