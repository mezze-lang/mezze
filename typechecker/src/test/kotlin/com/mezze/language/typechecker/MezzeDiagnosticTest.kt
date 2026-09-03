package com.mezze.language.typechecker

import com.mezze.language.parsedast.Loc
import com.mezze.language.parsedast.diagnostics.DiagnosticNote
import com.mezze.language.parsedast.diagnostics.ErrorCode
import com.mezze.language.parsedast.diagnostics.LevenshteinSuggest
import com.mezze.language.parsedast.diagnostics.MezzeDiagnostic
import com.mezze.language.parsedast.diagnostics.display
import com.mezze.language.langservice.printing.ErrorFormatter
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Phase A foundation tests: ErrorCode taxonomy, LevenshteinSuggest, and
 * the MezzeDiagnostic → Rust-style output rendering.
 */
class MezzeDiagnosticTest {

    @Test
    fun `ErrorCode display shows category prefix`() {
        assertEquals("T0201", ErrorCode.T0201_TYPE_MISMATCH.display())
        assertEquals("P0001", ErrorCode.P0001_UNTERMINATED_STRING.display())
        assertEquals("V0501", ErrorCode.V0501_EFFECT_NOT_HANDLED.display())
        assertEquals("E0000", ErrorCode.UNCATEGORIZED.display())
    }

    @Test
    fun `Levenshtein closest finds single-char typo`() {
        val candidates = listOf("println", "print", "string", "show")
        assertEquals("println", LevenshteinSuggest.closest("priintln", candidates))
        assertEquals("print", LevenshteinSuggest.closest("prnt", candidates))
    }

    @Test
    fun `Levenshtein returns null for distant matches`() {
        val candidates = listOf("println", "print")
        assertNull(LevenshteinSuggest.closest("xyz", candidates))
    }

    @Test
    fun `Levenshtein returns null for exact matches`() {
        val candidates = listOf("println")
        assertNull(LevenshteinSuggest.closest("println", candidates))
    }

    @Test
    fun `Levenshtein closestN orders by distance`() {
        val candidates = listOf("println", "print", "perform", "publish")
        val top = LevenshteinSuggest.closestN("prin", candidates, n = 3)
        assertTrue("print" in top, "expected 'print' in top matches, got $top")
    }

    @Test
    fun `formatDiagnostic produces Rust-style block with code and message`() {
        val source = """
            let main = {} -> perform Console in do
              println { msg = "hi" }
        """.trimIndent()
        val loc = Loc(file = "app.mz", startLine = 2, startCol = 3, endLine = 2, endCol = 10)
        val diag = MezzeDiagnostic(
            code = ErrorCode.T0201_TYPE_MISMATCH,
            message = "expected `Int`, got `Str`",
            loc = loc,
        )
        val out = ErrorFormatter.formatDiagnostic(diag, source)
        assertTrue(out.startsWith("error[T0201]: expected `Int`, got `Str`"),
            "header wrong, got:\n$out")
        assertTrue("--> app.mz:2:3" in out, "loc line wrong, got:\n$out")
    }

    @Test
    fun `formatDiagnostic includes hints when present`() {
        val source = "let x = 1\n"
        val loc = Loc(file = "app.mz", startLine = 1, startCol = 9, endLine = 1, endCol = 9)
        val diag = MezzeDiagnostic(
            code = ErrorCode.V0702_UNDEFINED_VARIABLE,
            message = "cannot find `prntln` in scope",
            loc = loc,
            hints = listOf("did you mean `println`?", "add `use std::effects::log::{ println }`"),
        )
        val out = ErrorFormatter.formatDiagnostic(diag, source)
        assertTrue("= hint: did you mean `println`?" in out, "hint missing, got:\n$out")
        assertTrue("= hint: add `use std::effects::log" in out, "second hint missing, got:\n$out")
    }

    @Test
    fun `formatDiagnostic includes notes when present`() {
        val source = "let x = 1\nlet x = 2\n"
        val firstLoc = Loc("app.mz", 1, 5, 1, 5)
        val secondLoc = Loc("app.mz", 2, 5, 2, 5)
        val diag = MezzeDiagnostic(
            code = ErrorCode.V0701_DUPLICATE_DECLARATION,
            message = "duplicate declaration of `x`",
            loc = secondLoc,
            notes = listOf(DiagnosticNote(message = "previous declaration was here", loc = firstLoc)),
        )
        val out = ErrorFormatter.formatDiagnostic(diag, source)
        assertTrue("= note: previous declaration was here" in out, "note missing, got:\n$out")
    }
}
