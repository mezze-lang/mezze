package com.mezze.language.parser

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/**
 * A `let` name must start lowercase (P0134).
 *
 * An uppercase name is a constructor everywhere else in the language. `let
 * MAX_VALUE = 100` used to be accepted, and then `n > MAX_VALUE` failed with
 * "expected Int, got | MAX_VALUE |" — the binding unreachable, and the error at
 * the use site rather than the definition that caused it.
 */
class LetNameCaseTest {
    private val parser = MezzeParser()

    private fun refusal(source: String): String {
        try {
            parser.parseParsedModule(source, "test.mz")
        } catch (e: Exception) {
            return e.message ?: ""
        }
        fail<Unit>("expected `$source` to be refused")
        return ""
    }

    @Test
    fun `an uppercase top-level let is refused at its definition`() {
        val msg = refusal("let MAX_VALUE_CONST = 100")
        assertTrue("constructor" in msg, "should say why: $msg")
        assertTrue("let max_value_const" in msg, "should suggest the lowercase name: $msg")
    }

    @Test
    fun `an uppercase do-block let is refused too`() {
        val msg = refusal("let f = {} -> do\n  let Total = 1\n  Total")
        assertTrue("let total" in msg, "should suggest the lowercase name: $msg")
    }

    @Test
    fun `lowercase lets and underscored names still parse`() {
        assertNotNull(parser.parseParsedModule("let max_value = 100\nlet _unused = 1", "test.mz"))
    }
}
