package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * `to_dbg` of a string is the string as source would write it (DBG-1).
 *
 * It quoted without escaping, so `open "sesame"` printed as `"open "sesame""` —
 * ambiguous — and a newline broke the rendering across lines. Escaped exactly as
 * the lexer reads escapes back, a rendering pasted into source is the same string.
 */
class DbgStringEscapeTest {

    private fun dbg(literal: String): String = MezzeTestHelper.run("let s = $literal\ns.to_dbg {}").toString()

    @Test
    fun `quotes are escaped`() {
        assertEquals("\"open \\\"sesame\\\"\"", dbg("\"open \\\"sesame\\\"\""))
    }

    @Test
    fun `newline, tab, return and backslash are escaped`() {
        assertEquals("\"a\\nb\\tc\\rd\\\\e\"", dbg("\"a\\nb\\tc\\rd\\\\e\""))
    }

    @Test
    fun `a dollar before a paren is escaped, so it does not read back as interpolation`() {
        assertEquals("\"cost \\$(x) and \$5\"", dbg("\"cost \\$(x) and \$5\""))
    }

    @Test
    fun `the rendering read back as source is the same string`() {
        val literal = "\"q\\\" b\\\\ n\\n t\\t \\$(x)\""
        val rendered = dbg(literal)
        assertEquals(true, MezzeTestHelper.run("let s = $literal\n$rendered == s"))
    }

    @Test
    fun `the value itself is untouched`() {
        assertEquals("open \"sesame\"", MezzeTestHelper.run("let s = \"open \\\"sesame\\\"\"\ns").toString())
    }
}
