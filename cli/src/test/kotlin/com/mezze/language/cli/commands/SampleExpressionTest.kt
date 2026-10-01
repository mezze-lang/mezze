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

    private fun expr(name: String, fields: List<String>, seed: Int = 7): String =
        command.buildExpression(SampleTarget.Result.Ready(name, fields), seed)

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
        assertTrue("quantity = ${ARGS}.quantity" in out, "field not forwarded from the binding:\n$out")
        assertTrue("unit_price = ${ARGS}.unit_price" in out, "field not forwarded from the binding:\n$out")
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

    /** Every sample returns `{ args, result }`; the printer reads both by name. */
    @Test
    fun `the result shape is args and result`() {
        for (fields in listOf(emptyList(), listOf("a"), listOf("a", "b"))) {
            val out = expr("f", fields)
            assertTrue("args = " in out, "no args field for $fields:\n$out")
            assertTrue("result = " in out, "no result field for $fields:\n$out")
        }
    }

    private companion object {
        /** Mirrors `SampleCommand.ARGS`, which is private to it. */
        const val ARGS = "__mez_sample_args__"
    }
}
