package com.mezze.language.parser

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows

/**
 * #P0126: a declaration keyword must be followed by the name it introduces on
 * the SAME line. `let\nname` / `type\nName` / `pub\nlet` are typos (a stray
 * keyword silently absorbing the next line) and are now rejected. The rest of
 * Mezze's newline-insensitivity (wrapping expressions, `=` on the next line) is
 * unaffected.
 */
class DeclNameOnNewLineTest {
    private val parser = MezzeParser()

    @Test
    fun `let with the name on the next line is rejected`() {
        val ex = assertThrows<ParseException> {
            parser.parseParsedModule("let\nhelp = { a, b } -> a + b")
        }
        assertTrue(ex.message?.contains("same line") == true,
            "expected a same-line error, got: ${ex.message}")
    }

    @Test
    fun `type with the name on the next line is rejected`() {
        assertThrows<ParseException> { parser.parseParsedModule("type\nColor = | Red, Green |") }
    }

    @Test
    fun `pub on its own line is rejected`() {
        assertThrows<ParseException> { parser.parseParsedModule("pub\nlet x = 1") }
    }

    @Test
    fun `a normal one-line let still parses`() {
        val m = parser.parseParsedModule("let help = { a, b } -> a + b")
        assertEquals(1, m.decls.size)
    }

    @Test
    fun `wrapping is still allowed elsewhere — name on the let line, value on the next`() {
        // Only the keyword↔name gap is forbidden; `=` then a newline is fine.
        val m = parser.parseParsedModule("let x =\n  1")
        assertEquals(1, m.decls.size)
    }
}
