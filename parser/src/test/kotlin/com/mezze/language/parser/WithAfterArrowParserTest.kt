package com.mezze.language.parser

import com.mezze.language.parsedast.diagnostics.ErrorCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Pins the rejection of post-arrow `with` clauses:
 *   let f : { p } -> Ret with C 'a = ...    # rejected (P0120)
 *   let f : { p } with C 'a -> Ret = ...    # accepted
 *
 * The constraint clause must precede the arrow so the return slot stays
 * focused on the value type and its effect row (`<...>`). See #35 and
 * the design discussion in `docs/design/`.
 */
class WithAfterArrowParserTest {

    private val parser = MezzeParser()

    private fun parseExpectingFailure(source: String): Throwable {
        return assertThrows(Throwable::class.java) {
            parser.parseParsedModule(source, "with_after_test.mz")
        }
    }

    @Test
    fun `with after arrow is rejected with P0120`() {
        val source = """
            let f : { x: 'a, y: 'a } -> 'a with NumAdd 'a = { x, y } -> x + y
        """.trimIndent()

        val ex = parseExpectingFailure(source)
        val msg = ex.message ?: ""
        assertTrue(
            msg.contains("'with' clause must come BEFORE the arrow") || msg.contains("P0120"),
            "Expected P0120 with-after-arrow error, got: $msg",
        )
    }

    @Test
    fun `error suggests the user's actual rewritten form`() {
        val source = """
            let f : { x: 'a, y: 'a } -> 'a with NumAdd 'a = { x, y } -> x + y
        """.trimIndent()

        val ex = parseExpectingFailure(source) as ParseException
        // Fix-it should rewrite to the user's actual signature, not a generic
        // example. Inspect the structured hints field directly — Exception.message
        // renders without hints (raw `formatParseError`), but `ex.hints` carries
        // them and the user-facing CLI formatter renders them in the output.
        val hintBody = ex.hints.joinToString("\n")
        assertTrue(
            hintBody.contains("with NumAdd 'a"),
            "Hint should reference user's actual constraint 'with NumAdd 'a', got hints: ${ex.hints}",
        )
        assertTrue(
            hintBody.contains("rewrite as"),
            "Hint should propose a concrete rewrite, got hints: ${ex.hints}",
        )
        // Sanity: the rewrite should put `with` BEFORE the arrow.
        val rewriteLine = ex.hints.firstOrNull { it.contains("->") } ?: ""
        val withIdx = rewriteLine.indexOf(" with ")
        val arrowIdx = rewriteLine.indexOf(" -> ")
        assertTrue(
            withIdx in 0 until arrowIdx,
            "Rewrite should have 'with' BEFORE '->', got: '$rewriteLine'",
        )
    }

    @Test
    fun `with after effect row is also rejected`() {
        val source = """
            let f : { val: 'a } -> Str <Log> with Show 'a = { val } -> "x"
        """.trimIndent()

        val ex = parseExpectingFailure(source)
        val msg = ex.message ?: ""
        assertTrue(
            msg.contains("'with' clause must come BEFORE the arrow") || msg.contains("P0120"),
            "Expected P0120 error for `with` after effect row, got: $msg",
        )
    }

    @Test
    fun `with before arrow still parses`() {
        val source = """
            let f : { x: 'a, y: 'a } with NumAdd 'a -> 'a = { x, y } -> x + y
        """.trimIndent()
        // Should not throw.
        val module = parser.parseParsedModule(source, "with_before.mz")
        assertNotNull(module)
        assertEquals(1, module.decls.size)
    }

    @Test
    fun `with on inner function-type parameter is unaffected`() {
        // Pre-arrow `with` inside a function-type field. This is the
        // higher-order case that confused the migration heuristic in #35.
        // The OUTER signature uses pre-arrow form; the inner one too. No
        // post-arrow `with`, so no error.
        val source = """
            let applyAndShow: {f: {x: 'a} -> 'a, val: 'a} with Show 'a -> Str = {f, val} -> "x"
        """.trimIndent()
        val module = parser.parseParsedModule(source, "ho.mz")
        assertNotNull(module)
    }
}
