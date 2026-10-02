package com.mezze.language.cli.commands

import com.mezze.language.ast.MezzeType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The expression `mez sample` writes.
 *
 * Tested here rather than end to end because its SHAPE is the design, and the shape
 * is what several measurements settled. Each test below names the measurement it
 * stands on, so "simplifying" any of this fails something that explains why.
 *
 * The one idea underneath all of it: the command already knows every argument's TYPE
 * — it read the function's parameter off the typed declaration — so it generates FROM
 * that type rather than emitting a bare `Arbitrary.arbitrary {}` and hoping the call
 * site pins it. That is what makes a container or an `Option` argument work at all.
 */
class SampleExpressionTest {

    private val command = SampleCommand()

    private fun ready(name: String, fields: Map<String, MezzeType>) =
        SampleTarget.Result.Ready(name, fields)

    /** Every field an `Int`, for the tests that are only about structure. */
    private fun expr(name: String, fields: List<String>, seed: Int = 7, count: Int = 1): String =
        command.buildExpression(ready(name, fields.associateWith { MezzeType.IntType }), seed, count)

    private fun seqOf(element: MezzeType) = MezzeType.TypeApplication("Seq", listOf(element))

    @Test
    fun `each field gets its own draw`() {
        val out = expr("total", listOf("quantity", "unit_price"))
        assertEquals(
            2,
            Regex("Arbitrary\\.arbitrary \\{\\}").findAll(out).count(),
            "expected one draw per field:\n$out",
        )
    }

    /**
     * EVERY field binding carries its type, and that annotation is load-bearing: it
     * is what pins the draws. Dropping it is what made a container argument fail
     * before — a bare field draw at `[T]` has nothing to dispatch on.
     */
    @Test
    fun `every field binding carries its type`() {
        val out = expr("total", listOf("quantity", "unit_price"))
        assertTrue("quantity: Int = " in out, "field not annotated:\n$out")
        assertTrue("unit_price: Int = " in out, "field not annotated:\n$out")
    }

    /**
     * A SEQUENCE field becomes `list_of`, the case that cannot work any other way: a
     * receiver-less op at `[T]` has nothing to pin it, while `list_of` is a FUNCTION
     * whose `'a` the annotation solves — putting the inner draw at the concrete
     * element type, where dispatch works.
     *
     * Verified live: `tagged`, an ANONYMOUS record with a `[Str]` field, samples under
     * this shape and failed under every earlier one.
     */
    @Test
    fun `a sequence field is generated with list_of`() {
        val out = command.buildExpression(
            ready("tagged", mapOf("tags" to seqOf(MezzeType.StringType), "id" to MezzeType.IntType)),
            seed = 7,
        )
        assertTrue("list_of { g = {} -> Arbitrary.arbitrary {} }" in out, "no list_of:\n$out")
        assertTrue("tags: [Str]" in out, "field not annotated with its type:\n$out")
    }

    /** A sequence OF a sequence descends, so each level pins its own element. */
    @Test
    fun `a nested sequence descends`() {
        val out = command.buildExpression(
            ready("f", mapOf("xss" to seqOf(seqOf(MezzeType.IntType)))),
            seed = 7,
        )
        assertEquals(2, Regex("list_of").findAll(out).count(), "expected two levels:\n$out")
    }

    /**
     * `Option` is generated inline because no instance exists to dispatch to (S-4,
     * #161). 1 in 10 is ScalaCheck's `Gen.option` — `frequency(1 -> None, 9 -> some)`
     * — rather than a guess.
     */
    @Test
    fun `an Option field is generated inline, weighted 1 in 10`() {
        val out = command.buildExpression(
            ready("f", mapOf("o" to MezzeType.TypeApplication("Option", listOf(MezzeType.StringType)))),
            seed = 7,
        )
        assertTrue("choose { bound = 10 } == 0 then None" in out, "wrong Option shape:\n$out")
        assertTrue("Some { val = Arbitrary.arbitrary {} }" in out, "no Some payload draw:\n$out")
    }

    /** `Result`, same reasoning — weighted toward `Ok`, `Err` often enough to matter. */
    @Test
    fun `a Result field is generated inline with both arms`() {
        val out = command.buildExpression(
            ready("f", mapOf(
                "r" to MezzeType.TypeApplication("Result", listOf(MezzeType.IntType, MezzeType.StringType)),
            )),
            seed = 7,
        )
        assertTrue("Err { error =" in out, "no Err arm:\n$out")
        assertTrue("Ok { val =" in out, "no Ok arm:\n$out")
    }

    @Test
    fun `the seed reaches the expression`() {
        assertTrue("seed = 1234" in expr("total", listOf("q"), seed = 1234))
    }

    /**
     * A ZERO-argument function gets no `with_seed`. There is nothing to draw, so the
     * wrapper would declare a `<Sample>` the body never performs and be rejected for
     * exactly that — measured, not guessed.
     */
    @Test
    fun `a zero-argument function is not wrapped in with_seed`() {
        val out = expr("answer", emptyList())
        assertTrue("with_seed" !in out, "a no-arg function was wrapped:\n$out")
        assertTrue("args = {}" in out, "expected an empty args record:\n$out")
        assertTrue("answer {}" in out, "expected a plain call:\n$out")
    }

    /** Every sample returns `{ rows = [{ args, result }] }`; the printer reads by name. */
    @Test
    fun `the result shape is rows of args and result`() {
        for (fields in listOf(emptyList(), listOf("a"), listOf("a", "b"))) {
            val out = expr("f", fields)
            assertTrue("rows = [" in out, "no rows field for $fields:\n$out")
            assertTrue("args = " in out, "no args field for $fields:\n$out")
            assertTrue("result = " in out, "no result field for $fields:\n$out")
        }
    }

    /**
     * Every row draws inside ONE `with_seed`, so the seed reproduces the whole batch.
     * A seeded scope per row would reset the stream and print the same values N times
     * — a table that looks like it works and shows one sample repeated.
     */
    @Test
    fun `all rows draw inside a single seeded scope`() {
        val out = expr("f", listOf("a"), count = 5)
        assertEquals(1, Regex("with_seed").findAll(out).count(), "expected ONE seeded scope:\n$out")
    }

    @Test
    fun `count produces that many rows, each with its own draws`() {
        val out = expr("f", listOf("a", "b"), count = 4)
        assertEquals(
            8,
            Regex("Arbitrary\\.arbitrary \\{\\}").findAll(out).count(),
            "wrong draw count for 4 rows x 2 fields:\n$out",
        )
        for (i in 0 until 4) assertTrue("${ARGS}_$i" in out, "row $i missing:\n$out")
    }

    /**
     * Rows must not share a binding — the easy mistake when unrolling, and one that
     * would print identical values on every line while the draws really happened.
     * Indistinguishable from a working table at a glance, hence asserted.
     */
    @Test
    fun `each row reads its OWN bindings`() {
        val out = expr("f", listOf("a"), count = 3)
        for (i in 0 until 3) {
            assertTrue("a = ${ARGS}_$i.a" in out, "row $i does not read its own args:\n$out")
        }
    }

    /**
     * The call forwards fields from the bound record and never re-draws. Re-drawing
     * would print arguments that are not the ones the function saw — silently wrong,
     * and the worst outcome available to this command.
     */
    @Test
    fun `the call forwards from the binding instead of re-drawing`() {
        val out = expr("total", listOf("quantity"))
        val callSite = out.substringAfter("result = ")
        assertTrue("Arbitrary.arbitrary" !in callSite, "the call re-draws:\n$out")
        assertTrue("quantity = ${ARGS}_0.quantity" in callSite, "not forwarded:\n$out")
    }

    private companion object {
        /** Mirrors `SampleCommand.ARGS`, which is private to it. */
        const val ARGS = "__mez_sample_args__"
    }
}
