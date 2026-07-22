package com.mezze.language.formatter

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * #78 — the formatter auto-wraps an over-budget `pub let` function-type
 * signature at the top-level `:`, `->`, and `=`, and keeps short signatures
 * on one line. Wrapped output must re-parse.
 */
class MultiLineSignatureFormatTest {
    private val parser = MezzeParser()

    private fun fmt(src: String): String =
        PrettyPrinter().printModule(parser.parseParsedModule(src.trimIndent()))

    @Test fun `long signature wraps at colon arrow equals`() {
        val src = "pub let promise_from : { setup: { complete: { val: 'a } -> {}, fail: { err: AsyncError } -> {} } -> {} } -> Promise 'a = { setup } -> setup"
        val out = fmt(src)
        // wrapped: the `:` / `->` / `=` each start their own indented line
        assertTrue(out.contains("\n  : "), "expected wrapped `:` line:\n$out")
        assertTrue(out.contains("\n  -> "), "expected wrapped `->` line:\n$out")
        assertTrue(out.contains("\n  = "), "expected wrapped `=` line:\n$out")
        // round-trips: formatted output re-parses
        assertDoesNotThrow { parser.parseParsedModule(out) }
    }

    @Test fun `short signature stays on one line`() {
        val out = fmt("pub let f : { x: Int } -> Int = { x } -> x")
        assertFalse(out.contains("\n  : "), "short sig should not wrap:\n$out")
        assertTrue(out.trimEnd().count { it == '\n' } == 0, "expected single line:\n$out")
    }

    @Test fun `wrapped output is idempotent`() {
        val src = "pub let promise_from : { setup: { complete: { val: 'a } -> {}, fail: { err: AsyncError } -> {} } -> {} } -> Promise 'a = { setup } -> setup"
        val once = fmt(src)
        val twice = PrettyPrinter().printModule(parser.parseParsedModule(once))
        assertEquals(once, twice, "formatting should be idempotent")
    }
}
