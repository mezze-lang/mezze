package com.mezze.language.parser

import com.mezze.language.parsedast.ParsedLetDecl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * A module documents itself with the `##` block at the top of its file — the one
 * that documents no declaration: followed by a `use`, or set apart by a blank line.
 */
class ModuleDocTest {

    private fun parse(src: String) = MezzeParser().parseParsedModule(src.trimIndent(), "m.mz")

    @Test
    fun `a leading block before the uses documents the module`() {
        val m = parse("""
            ## Text utilities.
            ## Second line.
            use std::str
            let x = 1
        """)
        assertEquals("Text utilities.\nSecond line.", m.doc)
    }

    @Test
    fun `a leading block set apart by a blank line documents the module`() {
        val m = parse("""
            ## Text utilities.

            let x = 1
        """)
        assertEquals("Text utilities.", m.doc)
        assertNull((m.decls.single() as ParsedLetDecl).docComment)
    }

    @Test
    fun `a block right above the first declaration documents that declaration, not the module`() {
        val m = parse("""
            ## The answer.
            let x = 42
        """)
        assertNull(m.doc)
        assertEquals("The answer.", (m.decls.single() as ParsedLetDecl).docComment)
    }

    @Test
    fun `a plain comment above it does not hide it`() {
        val m = parse("""
            # Not a doc.

            ## Text utilities.
            use std::str
        """)
        assertEquals("Text utilities.", m.doc)
    }

    @Test
    fun `the recovering parser agrees`() {
        val r = MezzeParser().parseWithRecovery("## Text utilities.\nuse std::str\n", file = "m.mz")
        assertEquals("Text utilities.", r.module.doc)
    }
}
