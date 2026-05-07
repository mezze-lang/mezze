package com.mezze.language.parser

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Regression tests for #299: when a user writes an invalid operator like `++`
 * (string concat in many other languages), the parser used to bury the
 * targeted error under a generic message from a competing alternative
 * ("Do block is empty", "Expected 'perform' but got 'do'", etc.) because
 * `oneOf`'s deepest-failure heuristic preferred a shallower miss at a later
 * token over a real failure that happened after consuming a valid LHS.
 *
 * The fix marks the `++`/`--` failure with `isFatal = true` so `oneOf`
 * stops trying further alternatives and propagates the targeted error
 * straight up.
 */
class InvalidOperatorErrorTest {

    private val parser = MezzeParser()

    @Test
    fun `++ inside a do block surfaces the targeted '++' error`() {
        // Repro from #299: `++` inside a do-block let body. The old
        // behavior surfaced "Do block is empty" or "Expected 'perform'
        // but got 'do'" — both unrelated to the actual issue.
        val source = """
            let main = {} -> do
                let s = "hello" ++ " world"
                s
        """.trimIndent()

        val ex = assertThrows(ParseException::class.java) {
            parser.parseParsedModule(source)
        }
        val msg = ex.message ?: ""
        assertTrue(
            msg.contains("'++' is not a valid operator"),
            "expected the targeted '++' error, got: $msg"
        )
        assertTrue(
            msg.contains("interpolation"),
            "expected the suggestion to use string interpolation, got: $msg"
        )
    }

    @Test
    fun `++ at top level surfaces the targeted error`() {
        val source = """let s = "hello" ++ " world""""
        val ex = assertThrows(ParseException::class.java) {
            parser.parseParsedModule(source)
        }
        assertTrue(
            (ex.message ?: "").contains("'++' is not a valid operator"),
            "expected the targeted '++' error, got: ${ex.message}"
        )
    }

    @Test
    fun `-- surfaces the targeted error with a different suggestion`() {
        val source = """let x = 1 -- 2"""
        val ex = assertThrows(ParseException::class.java) {
            parser.parseParsedModule(source)
        }
        val msg = ex.message ?: ""
        assertTrue(
            msg.contains("'--' is not a valid operator"),
            "expected the targeted '--' error, got: $msg"
        )
        assertTrue(
            msg.contains("Use '-' with two operands"),
            "expected the suggestion to use '-' with two operands, got: $msg"
        )
    }

    @Test
    fun `valid '+ -' (add negative) still parses`() {
        // The fix must NOT break valid `+ -x` or `- -x` patterns.
        val source = """let x = 1 + -2"""
        assertDoesNotThrow { parser.parseParsedModule(source) }
    }
}
