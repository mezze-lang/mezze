package com.mezze.language.cli.commands

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The expression `mez sample` writes.
 *
 * Tested here rather than end to end because its SHAPE is the design decision, and
 * the shape was chosen against measurements that an end-to-end test would not
 * record: generating per FIELD, with no annotation on the record, because
 * `let args: { n: Int } = Arbitrary.arbitrary {}` is rejected (an anonymous record
 * annotation does not pin a receiver-less ability operation) while the per-field
 * form is pinned by the function's own parameter. If someone "simplifies" this to
 * one annotated binding, these tests say why not.
 */
class SampleExpressionTest {

    private val command = SampleCommand()

    private fun expr(name: String, fields: List<String>, seed: Int = 7, count: Int = 1): String =
        command.buildExpression(SampleTarget.Result.Ready(name, fields), seed, count)

    @Test
    fun `each field gets its own arbitrary call`() {
        val out = expr("total", listOf("quantity", "unit_price"))
        assertEquals(
            2,
            Regex("Arbitrary\\.arbitrary \\{\\}").findAll(out).count(),
            "expected one draw per field, got:\n$out",
        )
    }

    /**
     * The record carries NO type annotation, and that is the measured choice rather
     * than an omission. An annotation would not pin the draws for an anonymous
     * parameter type, so adding one would break exactly the functions whose
     * parameter is written inline — which is most of them.
     */
    @Test
    fun `the generated record is NOT annotated`() {
        val out = expr("total", listOf("quantity"))
        assertFalse(
            Regex("let \\S+\\s*:").containsMatchIn(out),
            "the args binding carries a type annotation; see the class doc:\n$out",
        )
    }

    /**
     * Bound once and forwarded field by field. Re-drawing inside the call would
     * produce a sample whose printed arguments were not the ones the function saw —
     * silently wrong, and the single worst outcome available to this command.
     */
    @Test
    fun `the args are bound once and forwarded, not drawn twice`() {
        val out = expr("total", listOf("quantity", "unit_price"))
        assertTrue("quantity = ${ARGS}_0.quantity" in out, "field not forwarded from the binding:\n$out")
        assertTrue("unit_price = ${ARGS}_0.unit_price" in out, "field not forwarded from the binding:\n$out")
        // The call site must reference the binding, never generate again.
        val callSite = out.substringAfter("result = ")
        assertFalse(
            "Arbitrary.arbitrary" in callSite,
            "the call re-draws instead of using the bound args:\n$out",
        )
    }

    @Test
    fun `the seed reaches the expression`() {
        assertTrue("seed = 1234" in expr("total", listOf("q"), seed = 1234))
    }

    /**
     * A ZERO-argument function gets no `with_seed` wrapper. There is nothing to
     * draw, so the wrapper would declare a `<Sample>` the body never performs and be
     * rejected for precisely that — measured, not guessed.
     */
    @Test
    fun `a zero-argument function is not wrapped in with_seed`() {
        val out = expr("answer", emptyList())
        assertFalse("with_seed" in out, "a no-arg function was wrapped:\n$out")
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
     * Every row draws inside ONE `with_seed`, so the seed reproduces the whole
     * batch. A seeded scope per row would reset the stream and print the same values
     * N times — a table that looks like it works and shows one sample repeated.
     */
    @Test
    fun `all rows draw inside a single seeded scope`() {
        val out = expr("f", listOf("a"), count = 5)
        assertEquals(1, Regex("with_seed").findAll(out).count(), "expected ONE seeded scope:\n$out")
    }

    /** One args binding and one draw per field, per row. */
    @Test
    fun `count produces that many rows, each with its own draws`() {
        val out = expr("f", listOf("a", "b"), count = 4)
        assertEquals(4, Regex("\\{ a = Arbitrary").findAll(out).count(), "wrong row count:\n$out")
        assertEquals(8, Regex("Arbitrary\\.arbitrary \\{\\}").findAll(out).count(), "wrong draw count:\n$out")
        for (i in 0 until 4) {
            assertTrue("${ARGS}_$i" in out, "row $i has no args binding:\n$out")
        }
    }

    /**
     * Rows must not share an args binding — the easy mistake when unrolling, and one
     * that would print the same values on every line while the draws really happened.
     * Indistinguishable from a working table at a glance, which is why it is asserted
     * rather than left to review.
     */
    @Test
    fun `each row reads its OWN args binding`() {
        val out = expr("f", listOf("a"), count = 3)
        for (i in 0 until 3) {
            assertTrue("a = ${ARGS}_$i.a" in out, "row $i does not read its own args:\n$out")
        }
    }

    /**
     * A NAMED parameter type is drawn WHOLE under an annotation, not per field — and
     * that is a capability difference, not a style one. The annotated form dispatches
     * to the type's derived instance, which handles a `[Int]` field; the per-field
     * form cannot, because a bare field draw at a container type has nothing to pin
     * it. Verified against the installed binary: `cart_total`, whose parameter is a
     * named record with a `[Int]` field, samples under this shape and fails under the
     * other.
     */
    @Test
    fun `a named parameter type is drawn whole under its own annotation`() {
        val out = command.buildExpression(
            SampleTarget.Result.Ready("cart_total", listOf("items", "note"), paramTypeName = "Cart"),
            seed = 7,
        )
        assertTrue("${ARGS}_0: Cart = Arbitrary.arbitrary {}" in out, "not annotated whole:\n$out")
        assertEquals(
            1,
            Regex("Arbitrary\\.arbitrary \\{\\}").findAll(out).count(),
            "expected ONE draw for the whole record, not one per field:\n$out",
        )
        // Fields are still forwarded individually — a spread-only record is rejected
        // in a call position, so the call has to name them.
        assertTrue("items = ${ARGS}_0.items" in out, "field not forwarded:\n$out")
        assertTrue("note = ${ARGS}_0.note" in out, "field not forwarded:\n$out")
    }

    /** Anonymous stays per-field: an anonymous annotation does not pin. */
    @Test
    fun `an anonymous parameter type is still drawn per field`() {
        val out = command.buildExpression(
            SampleTarget.Result.Ready("f", listOf("a", "b"), paramTypeName = null),
            seed = 7,
        )
        assertFalse(Regex("let \\S+\\s*:").containsMatchIn(out), "annotated anyway:\n$out")
        assertEquals(
            2,
            Regex("Arbitrary\\.arbitrary \\{\\}").findAll(out).count(),
            "expected one draw per field:\n$out",
        )
    }

    private companion object {
        /** Mirrors `SampleCommand.ARGS`, which is private to it. */
        const val ARGS = "__mez_sample_args__"
    }
}
