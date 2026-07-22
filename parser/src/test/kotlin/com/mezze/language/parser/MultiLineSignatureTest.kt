package com.mezze.language.parser

import com.mezze.language.parsedast.ParsedLetDecl
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * #78 — a `pub let` signature may wrap across lines. The decl keyword + name
 * stay on the anchor line (P0126), but once the anchor is parsed the whitespace
 * between `:`, the type, `->`, the effect row, `=`, and the body is not
 * significant. Nested record types make single-line signatures unreadable.
 */
class MultiLineSignatureTest {
    private val parser = MezzeParser()

    @Test fun `multi-line pub let signature parses`() {
        val src = """
            pub let promise_from
              : { setup: { complete: { val: 'a } -> {} } -> {} }
              -> Promise 'a
              = { setup } -> setup
        """.trimIndent()
        val m = parser.parseParsedModule(src)
        val decl = m.decls.filterIsInstance<ParsedLetDecl>().single()
        assertNotNull(decl.typeAnnotation)
    }

    @Test fun `single-line signature still parses`() {
        val src = "pub let f : { x: Int } -> Int = { x } -> x"
        val m = parser.parseParsedModule(src)
        assertTrue(m.decls.filterIsInstance<ParsedLetDecl>().isNotEmpty())
    }

    @Test fun `non-pub multi-line signature parses`() {
        val src = """
            let g
              : { x: Int }
              -> Int
              = { x } -> x
        """.trimIndent()
        val m = parser.parseParsedModule(src)
        assertTrue(m.decls.filterIsInstance<ParsedLetDecl>().isNotEmpty())
    }
}
