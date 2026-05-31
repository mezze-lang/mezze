package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * #171 — Improved error messages for unknown identifiers in expression
 * position.
 *
 * Covers:
 *   - typo of a name in current scope -> "Did you mean: X"
 *   - typo of a qualified name -> "Did you mean: mod::X" (existing)
 *   - unqualified bare name that's a method elsewhere -> "available as a
 *     method on Seq" (existing)
 *   - unqualified bare name that's a free function in an unimported
 *     stdlib module -> "exported from std::xxx — try use std::xxx::{X}"
 *     (new in #171)
 */
class UndefinedIdentifierSuggestionsTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    private fun errFor(source: String): String =
        assertThrows<Exception> { eval(source) }.message ?: ""

    @Test
    fun `typo of in-scope name suggests close match`() {
        val msg = errFor("""
            use std::effects::log::{println}
            printlin { msg = "hi" }
        """.trimIndent())
        assertTrue("Did you mean: println" in msg) { msg }
    }

    @Test
    fun `typo of locally bound name suggests it`() {
        val msg = errFor("""
            let counter = 0
            countar
        """.trimIndent())
        assertTrue("Did you mean: counter" in msg) { msg }
    }

    @Test
    fun `qualified typo suggests close qualified names`() {
        val msg = errFor("""
            use std::collections::seq
            seq::flrst { xs = [1, 2, 3] }
        """.trimIndent())
        assertTrue("Did you mean: seq::first" in msg) { msg }
    }

    @Test
    fun `unimported free function points to import path`() {
        // First load the log module in a separate eval so its bindings
        // end up in ContentHashRegistry — the suggestion mechanism
        // reads from there. (In real LSP/CLI workflows the registry is
        // populated by earlier compilations; per-test eval starts cold.)
        try {
            eval("use std::effects::log::{println}\nprintln { msg = \"warmup\" }")
        } catch (_: Exception) { /* warmup may fail to execute, that's fine */ }

        // Now the typo of bare `println` (no import) should mention the source module.
        val msg = errFor("""println { msg = "hi" }""")
        assertTrue("exported from std::effects::log" in msg) { msg }
        assertTrue("use std::effects::log::{println}" in msg) { msg }
    }

    @Test
    fun `unimported inherent method gets method-syntax hint`() {
        // `first` is on Seq (inherent impl).
        val msg = errFor("first { xs = [1, 2, 3] }")
        assertTrue("available as a method on Seq" in msg) { msg }
    }
}
