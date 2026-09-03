package com.mezze.language.parser

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Source positions after a string literal that spans lines.
 *
 * A newline inside a string was counted twice: once by the loop in lexString
 * and again by the advance() it called. Everything after such a string was
 * therefore reported N lines too far down, N being the number of newlines the
 * string spanned. That moved compiler error locations, LSP positions, and the
 * type-on-hover lookup in the browser editor, all silently, and only for files
 * containing a multi-line string.
 */
class MultiLineStringLocTest {

    private val parser = MezzeParser()


    /** Start line of the LAST top-level decl. Every fixture ends with
     *  `let after = 42`, so this is the line the double-count shifted. */
    private fun lastDeclLine(source: String): Int =
        parser.parseParsedModule(source).decls.last().loc.startLine

    @Test
    fun `a decl after a two-line string keeps its own line number`() {
        val source = """
let a = "one
two"
let after = 42
""".trimStart()
        // Lines: 1 = let a, 2 = two", 3 = let after
        assertEquals(3, lastDeclLine(source))
    }

    @Test
    fun `the offset scales with the newlines in the string`() {
        val source = """
let a = "one
two
three
four"
let after = 42
""".trimStart()
        // Lines 1..4 are the string, so the next decl is line 5.
        assertEquals(5, lastDeclLine(source))
    }

    @Test
    fun `a single-line string does not shift anything`() {
        val source = """
let a = "one line"
let after = 42
""".trimStart()
        assertEquals(2, lastDeclLine(source))
    }

    @Test
    fun `interpolation inside a multi-line string still counts once`() {
        val source = """
let name = "Mezze"
let a = "${'$'}(name) spans
two lines"
let after = 42
""".trimStart()
        assertEquals(4, lastDeclLine(source))
    }

    @Test
    fun `two multi-line strings accumulate correctly`() {
        val source = """
let a = "one
two"
let b = "three
four"
let after = 42
""".trimStart()
        assertEquals(5, lastDeclLine(source))
    }
}
