package com.mezze.interpreter

import com.mezze.datastructures.map.MezzeMap
import com.mezze.datastructures.seq.MezzeSeq
import com.mezze.datastructures.set.MezzeSet
import com.mezze.interpreter.nodes.builtin.effects.InspectTree
import com.mezze.interpreter.values.MezzeRecord
import com.mezze.interpreter.values.MezzeVariant
import com.mezze.language.truffleir.PrintType
import com.mezze.wire.WireValue
import com.mezze.wire.toText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Expanding an elided branch — the laziness half of the value tree.
 *
 * A bounded first reply is only useful if the client can then ask about a
 * particular branch, and the path it echoes back must land on exactly the node
 * that was elided. These tests are mostly about the ways it could land
 * somewhere else: a path from a different value, a step that does not fit the
 * shape it meets, an index past the end. Each of those must REFUSE, because an
 * expand that quietly answers about the wrong node is worse than one that
 * fails — the caller displays the answer either way.
 */
class InspectTreeExpandTest {

    private val intT = PrintType.Scalar(PrintType.ScalarKind.Int)
    private val strT = PrintType.Scalar(PrintType.ScalarKind.Str)

    private fun record(vararg pairs: Pair<String, Any?>): MezzeRecord =
        MezzeRecord.fromFields(pairs.map { it.first }.toTypedArray(), pairs.map { it.second }.toTypedArray())

    private fun expand(value: Any?, t: PrintType, path: String, depth: Int = 8, items: Int = 100) =
        InspectTree.expand(value, t, path, dbgStyle = true, maxDepth = depth, maxItems = items)

    // ---- the round trip: what was elided is what expands ---------------------

    @Test
    fun `the path an Elided node reports expands to the branch that was cut`() {
        // The invariant the whole mechanism rests on. Built with a tight depth so
        // a branch really is cut, then the reported path is fed straight back.
        val value = record("inner" to record("deeper" to record("x" to 1L, "y" to "a")))
        val shape = PrintType.Record(listOf(PrintType.Field("inner", PrintType.Record(listOf(
            PrintType.Field("deeper", PrintType.Record(listOf(
                PrintType.Field("x", intT), PrintType.Field("y", strT),
            ))),
        )))))

        val bounded = InspectTree.build(value, shape, true, maxDepth = 2, maxItems = 100)
        val elided = ((bounded as WireValue.Record).fields.single().value as WireValue.Record)
            .fields.single().value as WireValue.Elided

        val expanded = expand(value, shape, elided.path)
        assertEquals("{ x = 1, y = \"a\" }", expanded.toText())
    }

    @Test
    fun `an expanded branch is itself bounded and can elide again`() {
        // Expansion is not "give me everything from here" — a deep branch stays
        // bounded, so a client walks down rather than pulling the whole value.
        val value = record("a" to record("b" to record("c" to record("d" to 1L))))
        val shape = PrintType.Record(listOf(PrintType.Field("a", PrintType.Record(listOf(
            PrintType.Field("b", PrintType.Record(listOf(
                PrintType.Field("c", PrintType.Record(listOf(PrintType.Field("d", intT)))),
            ))),
        )))))

        val expanded = expand(value, shape, "f:a", depth = 1)
        assertEquals("{ b = … }", expanded.toText())
    }

    // ---- every step kind ----------------------------------------------------

    @Test
    fun `a record field step descends by name`() {
        val value = record("x" to 1L, "y" to "a")
        val shape = PrintType.Record(listOf(PrintType.Field("x", intT), PrintType.Field("y", strT)))
        assertEquals("1", expand(value, shape, "f:x").toText())
        assertEquals("\"a\"", expand(value, shape, "f:y").toText())
    }

    @Test
    fun `an index step descends into an ordered collection`() {
        val value = MezzeSeq.of(10L, 20L, 30L)
        assertEquals("20", expand(value, PrintType.MzSeq(intT), "i:1").toText())
    }

    @Test
    fun `an index step works on a collection with no random access`() {
        // A set has no index, so the walk counts — and must count in the SAME
        // order the build did, or `i:n` names a different element than the one
        // that was elided.
        val value = MezzeSet.of(1L, 2L, 3L)
        val built = InspectTree.build(value, PrintType.MzSet(intT), true, 8, 100) as WireValue.Sequence
        for (i in built.items.indices) {
            assertEquals(
                built.items[i].toText(),
                expand(value, PrintType.MzSet(intT), "i:$i").toText(),
                "element $i disagrees between build and expand",
            )
        }
    }

    @Test
    fun `a payload step descends into a variant`() {
        val registry = MezzeVariant.TagRegistry(arrayOf("None", "Some"))
        val shape = PrintType.Variant(listOf(
            PrintType.Arm("None", null),
            PrintType.Arm("Some", PrintType.Record(listOf(PrintType.Field("x", intT)))),
        ))
        val value = MezzeVariant.singlePayload(registry, 1, record("x" to 7L))
        assertEquals("{ x = 7 }", expand(value, shape, "p").toText())
    }

    @Test
    fun `a newtype step changes the shape and leaves the value alone`() {
        // Newtype is erased at runtime: the value below the wrapper IS the value.
        val shape = PrintType.Newtype("Money", intT)
        assertEquals("5", expand(5L, shape, "n").toText())
        assertEquals("Money.(5)", expand(5L, shape, "").toText())
    }

    @Test
    fun `key and value steps descend into a map entry`() {
        val value = MezzeMap.of<Any, Any>(1L, "a")
        val shape = PrintType.MzMap(intT, strT)
        assertEquals("1", expand(value, shape, "k:0").toText())
        assertEquals("\"a\"", expand(value, shape, "v:0").toText())
    }

    @Test
    fun `a multi-step path descends through mixed shapes`() {
        val registry = MezzeVariant.TagRegistry(arrayOf("Some"))
        val shape = PrintType.Record(listOf(PrintType.Field(
            "items",
            PrintType.MzSeq(PrintType.Variant(listOf(PrintType.Arm("Some", intT)))),
        )))
        val value = record("items" to MezzeSeq.of(
            MezzeVariant.singlePayload(registry, 0, 1L),
            MezzeVariant.singlePayload(registry, 0, 2L),
        ))
        assertEquals("2", expand(value, shape, "f:items/i:1/p").toText())
    }

    @Test
    fun `the empty path is the whole value`() {
        val value = record("x" to 1L)
        val shape = PrintType.Record(listOf(PrintType.Field("x", intT)))
        assertEquals("{ x = 1 }", expand(value, shape, WireValue.Path.ROOT).toText())
    }

    @Test
    fun `a recursive type resolves through the path`() {
        // A Ref is type-level indirection, not a node — a path never names one,
        // so the walk must see through it at every step.
        val registry = MezzeVariant.TagRegistry(arrayOf("Node", "Leaf"))
        val shape = PrintType.Recursive(
            defs = mapOf("Tree" to PrintType.Variant(listOf(
                PrintType.Arm("Node", PrintType.Record(listOf(
                    PrintType.Field("l", PrintType.Ref("Tree")),
                    PrintType.Field("r", PrintType.Ref("Tree")),
                ))),
                PrintType.Arm("Leaf", intT),
            ))),
            root = PrintType.Ref("Tree"),
        )
        val leaf = { n: Long -> MezzeVariant.singlePayload(registry, 1, n) }
        val value = MezzeVariant.singlePayload(registry, 0, record("l" to leaf(1), "r" to leaf(2)))

        assertEquals("Leaf 2", expand(value, shape, "p/f:r").toText())
        // And a Ref below the expansion point still resolves, which only works if
        // the defs table travelled with it.
        assertEquals("1", expand(value, shape, "p/f:l/p").toText())
    }

    // ---- refusals ----------------------------------------------------------

    @Test
    fun `a step that does not fit the shape is refused`() {
        val shape = PrintType.Record(listOf(PrintType.Field("x", intT)))
        val value = record("x" to 1L)

        // Asking for a payload where there is a record.
        val e = assertThrows<InspectTree.NoSuchPathException> { expand(value, shape, "p") }
        assertTrue("record" in e.message!!, "the error should say what it found: ${e.message}")
    }

    @Test
    fun `an unknown field is refused rather than resolving to another`() {
        val shape = PrintType.Record(listOf(PrintType.Field("x", intT)))
        val e = assertThrows<InspectTree.NoSuchPathException> { expand(record("x" to 1L), shape, "f:nope") }
        assertTrue("nope" in e.message!!, e.message!!)
    }

    @Test
    fun `an index past the end is refused rather than clamped`() {
        // Clamping would answer about the last element as though it were the one
        // asked for.
        assertThrows<InspectTree.NoSuchPathException> {
            expand(MezzeSeq.of(1L, 2L), PrintType.MzSeq(intT), "i:9")
        }
        assertThrows<InspectTree.NoSuchPathException> {
            expand(MezzeSeq.of(1L, 2L), PrintType.MzSeq(intT), "i:-1")
        }
    }

    @Test
    fun `an entry past the end of a map is refused`() {
        assertThrows<InspectTree.NoSuchPathException> {
            expand(MezzeMap.of<Any, Any>(1L, "a"), PrintType.MzMap(intT, strT), "v:4")
        }
    }

    @Test
    fun `a payload step on a payload-less constructor is refused`() {
        val registry = MezzeVariant.TagRegistry(arrayOf("None", "Some"))
        val shape = PrintType.Variant(listOf(
            PrintType.Arm("None", null), PrintType.Arm("Some", intT),
        ))
        assertThrows<InspectTree.NoSuchPathException> {
            expand(MezzeVariant.noPayload(registry, 0), shape, "p")
        }
    }

    @Test
    fun `a malformed step is refused`() {
        val shape = PrintType.Record(listOf(PrintType.Field("x", intT)))
        assertThrows<InspectTree.NoSuchPathException> { expand(record("x" to 1L), shape, "zzz") }
        assertThrows<InspectTree.NoSuchPathException> { expand(record("x" to 1L), shape, "i:notanumber") }
    }

    @Test
    fun `a path from a different value is refused, not answered`() {
        // The case that makes this matter: a client holding a path from an earlier
        // result sends it against a new one. Answering anything would be a wrong
        // answer presented as a right one.
        val shape = PrintType.Record(listOf(PrintType.Field("x", intT)))
        assertThrows<InspectTree.NoSuchPathException> {
            expand(record("x" to 1L), shape, "f:items/i:1/p")
        }
    }

    @Test
    fun `the refusal names the path it could not resolve`() {
        val shape = PrintType.Record(listOf(PrintType.Field("x", intT)))
        val e = assertThrows<InspectTree.NoSuchPathException> { expand(record("x" to 1L), shape, "f:a/f:b") }
        assertEquals("f:a/f:b", e.path)
    }
}
