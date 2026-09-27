package com.mezze.wire

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The value tree and the evaluation messages, as a CONTRACT.
 *
 * Two things are being pinned here. First that every node survives a CBOR round
 * trip — [WireValue] is a sealed hierarchy, which serialises through a
 * discriminator rather than a flat record, and that is the part most likely to
 * work in one direction only. Second that [toText] reproduces exactly what the
 * language already prints, because the whole point of the tree is to be the
 * primitive that text is derived from; if the derivation differs from today's
 * output, every existing golden assertion in the repo disagrees with it.
 */
class WireValueTest {

    private fun roundTrip(v: WireValue): WireValue =
        WireFrame.of(MessageType.EVAL_RESULT, EvalResult(requestId = 1, value = v))
            .decode<EvalResult>().value!!

    // ---- CBOR round trip ---------------------------------------------------

    @Test
    fun `every node kind survives a CBOR round trip`() {
        // A sealed hierarchy over CBOR is the risky part of this contract: it
        // encodes with a type discriminator, and a case that only encodes is
        // worse than one that does neither.
        val nodes: List<WireValue> = listOf(
            WireValue.Scalar(WireValue.Scalar.Kind.INT, "42"),
            WireValue.Scalar(WireValue.Scalar.Kind.FRAC, "3//4"),
            WireValue.Newtype("Money", WireValue.Scalar(WireValue.Scalar.Kind.INT, "5")),
            WireValue.Record(listOf(WireValue.Field("x", WireValue.Scalar("int", "1")))),
            WireValue.Record(),
            WireValue.Variant("None"),
            WireValue.Variant("Some", WireValue.Scalar("int", "1")),
            WireValue.Sequence(WireValue.Sequence.Kind.SEQ, listOf(WireValue.Scalar("int", "1"))),
            WireValue.Sequence(WireValue.Sequence.Kind.SET, emptyList(), omitted = 7),
            WireValue.Mapping(
                WireValue.Mapping.Kind.MAP,
                listOf(WireValue.Entry(WireValue.Scalar("int", "1"), WireValue.Scalar("str", "\"a\""))),
            ),
            WireValue.Fn("double"),
            WireValue.Fn(),
            WireValue.Elided("f:inner/i:3", "record"),
        )
        for (node in nodes) {
            assertEquals(node, roundTrip(node), "did not survive CBOR: $node")
        }
    }

    @Test
    fun `a deeply nested tree survives intact`() {
        val deep = WireValue.Record(
            listOf(
                WireValue.Field(
                    "outer",
                    WireValue.Sequence(
                        WireValue.Sequence.Kind.LIST,
                        listOf(
                            WireValue.Variant(
                                "Some",
                                WireValue.Newtype("Id", WireValue.Scalar("nat", "7n")),
                            )
                        ),
                    ),
                )
            )
        )
        assertEquals(deep, roundTrip(deep))
    }

    // ---- toText reproduces today's rendering -------------------------------

    @Test
    fun `scalars render as their baked text`() {
        // The leaf text is the compiler's, not the client's: `to_dbg` quotes a
        // string where `to_str` does not, a Nat takes an `n` suffix. A client
        // re-deriving that would drift from what the language prints.
        assertEquals("42", WireValue.Scalar("int", "42").toText())
        assertEquals("7n", WireValue.Scalar("nat", "7n").toText())
        assertEquals("\"hi\"", WireValue.Scalar("str", "\"hi\"").toText())
        assertEquals("3//4", WireValue.Scalar("frac", "3//4").toText())
    }

    @Test
    fun `records render in field order with braces`() {
        assertEquals("{}", WireValue.Record().toText())
        assertEquals(
            "{ x = 1, y = \"a\" }",
            WireValue.Record(
                listOf(
                    WireValue.Field("x", WireValue.Scalar("int", "1")),
                    WireValue.Field("y", WireValue.Scalar("str", "\"a\"")),
                )
            ).toText(),
        )
    }

    @Test
    fun `each collection keeps its own delimiters`() {
        val one = listOf<WireValue>(WireValue.Scalar("int", "1"), WireValue.Scalar("int", "2"))
        fun seq(kind: String) = WireValue.Sequence(kind, one).toText()

        assertEquals("List(1, 2)", seq(WireValue.Sequence.Kind.LIST))
        assertEquals("[1, 2]", seq(WireValue.Sequence.Kind.SEQ))
        assertEquals("Set{1, 2}", seq(WireValue.Sequence.Kind.SET))
        assertEquals("SortedSet{1, 2}", seq(WireValue.Sequence.Kind.SORTED_SET))
        assertEquals("Deque[1, 2]", seq(WireValue.Sequence.Kind.DEQUE))
        assertEquals("NonEmptyList(1, 2)", seq(WireValue.Sequence.Kind.NON_EMPTY_LIST))
    }

    @Test
    fun `an empty collection renders with its delimiters and nothing between`() {
        fun empty(kind: String) = WireValue.Sequence(kind).toText()
        assertEquals("List()", empty(WireValue.Sequence.Kind.LIST))
        assertEquals("[]", empty(WireValue.Sequence.Kind.SEQ))
        assertEquals("Set{}", empty(WireValue.Sequence.Kind.SET))
        assertEquals("Deque[]", empty(WireValue.Sequence.Kind.DEQUE))
        assertEquals("Map{}", WireValue.Mapping(WireValue.Mapping.Kind.MAP).toText())
        assertEquals("SortedMap{}", WireValue.Mapping(WireValue.Mapping.Kind.SORTED_MAP).toText())
    }

    @Test
    fun `a map pads inside its braces where a set does not`() {
        // Reproducing an existing inconsistency on purpose: `Set{1, 2}` has no
        // inner padding and `Map{ 1 = 2 }` does. Both are what Mezze prints
        // today, and tidying it belongs in its own change rather than arriving
        // as a side effect of adding a tree type.
        assertEquals("Set{1, 2}", WireValue.Sequence(
            WireValue.Sequence.Kind.SET,
            listOf(WireValue.Scalar("int", "1"), WireValue.Scalar("int", "2")),
        ).toText())

        assertEquals("Map{ 1 = 2 }", WireValue.Mapping(
            WireValue.Mapping.Kind.MAP,
            listOf(WireValue.Entry(WireValue.Scalar("int", "1"), WireValue.Scalar("int", "2"))),
        ).toText())
    }

    @Test
    fun `variants render bare or with a payload`() {
        assertEquals("None", WireValue.Variant("None").toText())
        assertEquals("Some 1", WireValue.Variant("Some", WireValue.Scalar("int", "1")).toText())
    }

    @Test
    fun `a newtype names itself around its payload`() {
        assertEquals(
            "Money.(5)",
            WireValue.Newtype("Money", WireValue.Scalar("int", "5")).toText(),
        )
    }

    @Test
    fun `functions render named or anonymous`() {
        assertEquals("<function:double>", WireValue.Fn("double").toText())
        assertEquals("<function>", WireValue.Fn().toText())
    }

    @Test
    fun `an omitted count is shown rather than silently dropped`() {
        // A truncated collection that renders as if it were short is the
        // "looks like it worked" failure — the reader has no way to tell three
        // elements from three hundred.
        assertEquals(
            "List(1, …2 more)",
            WireValue.Sequence(
                WireValue.Sequence.Kind.LIST,
                listOf(WireValue.Scalar("int", "1")),
                omitted = 2,
            ).toText(),
        )
        assertEquals(
            "Map{ 1 = 2, …5 more }",
            WireValue.Mapping(
                WireValue.Mapping.Kind.MAP,
                listOf(WireValue.Entry(WireValue.Scalar("int", "1"), WireValue.Scalar("int", "2"))),
                omitted = 5,
            ).toText(),
        )
    }

    @Test
    fun `an elided branch renders as an ellipsis`() {
        assertEquals("…", WireValue.Elided("f:x", "record").toText())
        assertEquals(
            "{ x = … }",
            WireValue.Record(listOf(WireValue.Field("x", WireValue.Elided("f:x", "record")))).toText(),
        )
    }

    @Test
    fun `an unknown collection kind shows the kind instead of guessing a delimiter`() {
        // A peer newer than this code. Printing `[...]` for something that is not
        // a Seq would claim a shape that was never sent.
        val text = WireValue.Sequence("bag", listOf(WireValue.Scalar("int", "1"))).toText()
        assertTrue("bag" in text, "the unknown kind should be visible: $text")
    }

    // ---- paths -------------------------------------------------------------

    @Test
    fun `a path names a node by the steps from the root`() {
        val root = WireValue.Path.ROOT
        assertEquals("", root)
        assertEquals("f:inner", WireValue.Path.field(root, "inner"))
        assertEquals("f:inner/i:3", WireValue.Path.index(WireValue.Path.field(root, "inner"), 3))
        assertEquals("f:a/p", WireValue.Path.payload(WireValue.Path.field(root, "a")))
        assertEquals("n", WireValue.Path.inner(root))
        assertEquals("k:0", WireValue.Path.key(root, 0))
        assertEquals("v:0", WireValue.Path.value(root, 0))
    }

    @Test
    fun `a field name that would make the path ambiguous is rejected`() {
        // Sound rather than lucky: a Mezze field name is an identifier, so it
        // cannot contain '/' or ':'. Enforced instead of assumed, because an
        // ambiguous path resolves to the WRONG node rather than to none.
        assertThrows<IllegalArgumentException> { WireValue.Path.field("", "a/b") }
        assertThrows<IllegalArgumentException> { WireValue.Path.field("", "a:b") }
    }

    // ---- the messages ------------------------------------------------------

    @Test
    fun `the evaluation tags occupy their reserved range and collide with nothing`() {
        // Once published a tag byte can never mean anything else, so the
        // allocation itself is part of the contract.
        assertEquals(0x70.toByte(), MessageType.EVAL.tag)
        assertEquals(0x71.toByte(), MessageType.EVAL_RESULT.tag)
        assertEquals(0x72.toByte(), MessageType.EXPAND.tag)
        assertEquals(0x73.toByte(), MessageType.EXPAND_RESULT.tag)
        assertEquals(0x74.toByte(), MessageType.CANCEL.tag)

        val tags = MessageType.entries.map { it.tag }
        assertEquals(tags.size, tags.toSet().size, "two message types share a tag byte")
    }

    @Test
    fun `the multi-client range is still unallocated`() {
        // 0x20–0x2F is held for multi-client attach. Spending it on something
        // else would be irreversible.
        val taken = (0x20..0x2F).mapNotNull { MessageType.fromTag(it.toByte()) }
        assertTrue(taken.isEmpty(), "0x20–0x2F is reserved but now holds $taken")
    }

    @Test
    fun `Eval round-trips and defaults its limits`() {
        val frame = WireFrame.of(MessageType.EVAL, Eval(
            requestId = 9,
            fnsCborPath = "/tmp/fns.cbor",
            metaCborPath = "/tmp/meta.cbor",
        ))
        val decoded = frame.decode<Eval>()
        assertEquals(9, decoded.requestId)
        assertEquals("/tmp/fns.cbor", decoded.fnsCborPath)
        // Bounds are not optional — an unbounded walk of a live value is how a
        // request to print one value serialises megabytes, or never returns.
        assertEquals(WireProtocol.DEFAULT_EVAL_MAX_DEPTH, decoded.maxDepth)
        assertEquals(WireProtocol.DEFAULT_EVAL_MAX_ITEMS, decoded.maxItems)
        assertEquals(0L, decoded.timeoutMs)
    }

    @Test
    fun `an EvalResult carries a value or an error, not both`() {
        val ok = WireFrame.of(MessageType.EVAL_RESULT, EvalResult(
            requestId = 1, value = WireValue.Scalar("int", "1"), elapsedMicros = 250,
        )).decode<EvalResult>()
        assertNotNull(ok.value)
        assertNull(ok.error)
        assertEquals(250L, ok.elapsedMicros)

        val bad = WireFrame.of(MessageType.EVAL_RESULT, EvalResult(
            requestId = 2,
            error = EvalError(EvalError.Kind.FAILED, ExitDetail(message = "boom")),
        )).decode<EvalResult>()
        assertNull(bad.value)
        assertEquals(EvalError.Kind.FAILED, bad.error?.kind)
        assertEquals("boom", bad.error?.detail?.message)
    }

    @Test
    fun `an eval failure carries the same stack shape a program failure does`() {
        // Reused rather than re-declared: a client that already renders a Mezze
        // stack for a crashed program renders one for a failed expression with
        // the same code.
        val err = EvalError(
            kind = EvalError.Kind.FAILED,
            detail = ExitDetail(
                message = "division by zero",
                stack = listOf(StackFrame(function = "divide", file = "a.mz", line = 3, col = 5)),
                sourceFile = "a.mz",
                sourceLine = 3,
            ),
        )
        val decoded = WireFrame.of(MessageType.EVAL_RESULT, EvalResult(requestId = 1, error = err))
            .decode<EvalResult>().error!!
        assertEquals("divide", decoded.detail.stack.single().function)
        assertEquals(3, decoded.detail.sourceLine)
    }

    @Test
    fun `Expand names both the request it came from and the path`() {
        // Without `from_request` a path is meaningless: the same path addresses a
        // different node in every result.
        val e = WireFrame.of(MessageType.EXPAND, Expand(
            requestId = 11, fromRequest = 9, path = "f:inner/i:3",
        )).decode<Expand>()
        assertEquals(11, e.requestId)
        assertEquals(9, e.fromRequest)
        assertEquals("f:inner/i:3", e.path)
    }

    @Test
    fun `ExpandResult echoes the path it answers`() {
        val r = WireFrame.of(MessageType.EXPAND_RESULT, ExpandResult(
            requestId = 11, path = "f:inner", value = WireValue.Record(),
        )).decode<ExpandResult>()
        assertEquals("f:inner", r.path)
        assertEquals(WireValue.Record(), r.value)
    }

    @Test
    fun `Cancel names a request, not a run`() {
        // A run id identifies a program, of which the runtime models one at a
        // time; eval requests are many and concurrent, so INTERRUPT cannot
        // express "drop this one and keep the others".
        val c = WireFrame.of(MessageType.CANCEL, Cancel(requestId = 4)).decode<Cancel>()
        assertEquals(4, c.requestId)
    }

    @Test
    fun `eval is a negotiated capability`() {
        // Gated rather than assumed, so a runtime built without it says so at
        // handshake instead of failing on the first EVAL.
        assertEquals("eval", WireProtocol.Capability.EVAL)
    }

    @Test
    fun `adding these messages was a minor version bump`() {
        // Every new message is optional and capability-gated, so a peer that
        // never heard of them is unaffected — which is what makes it minor.
        assertEquals(1, WireProtocol.PROTOCOL_VERSION_MAJOR)
        assertTrue(
            WireProtocol.PROTOCOL_VERSION_MINOR >= 1,
            "the eval messages arrived in 1.1; minor is ${WireProtocol.PROTOCOL_VERSION_MINOR}",
        )
    }
}
