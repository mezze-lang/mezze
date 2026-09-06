package com.mezze.language.parser

import com.mezze.language.parsedast.ParsedPipe
import com.mezze.language.parsedast.ParsedExpr
import com.mezze.language.parsedast.ParsedLetDecl
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * A multi-line pipe whose continuation stages carry INLINE trailing comments.
 *
 * The bug: the pipe-continuation peek advanced only past NEWLINE tokens to find
 * the next `|>`. An inline comment (`|> f { x = @ }   # note`) put a COMMENT
 * token before that NEWLINE, so the peek stopped, the chain terminated at the
 * first commented stage, and the remaining `|>` stages were orphaned — the do
 * block then rejected its last `let` as the missing result. One commented stage
 * slipped through; two or more triggered it. The peek now skips COMMENT too, and
 * the skipped comments are kept on `ParsedPipe.trailingComments`.
 */
class PipeStageCommentParseTest {

    private val parser = MezzeParser()

    @Test
    fun `two inline stage comments no longer break the do block`() {
        val source = """
            use std::str
            let main = {} -> do
              let n = "a"
                |> str::to_upper { s = @ }   # c1
                |> str::to_lower { s = @ }   # c2
              n
        """.trimIndent()
        assertDoesNotThrow { parser.parseParsedModule(source) }
    }

    @Test
    fun `three inline stage comments parse`() {
        val source = """
            use std::str
            let main = {} -> do
              let n = "a"
                |> str::to_upper { s = @ }   # 1
                |> str::to_lower { s = @ }   # 2
                |> str::trim { s = @ }       # 3
              n
        """.trimIndent()
        assertDoesNotThrow { parser.parseParsedModule(source) }
    }

    @Test
    fun `an own-line comment between stages parses`() {
        val source = """
            use std::str
            let main = {} -> do
              let n = "a"
                |> str::to_upper { s = @ }
                # between
                |> str::to_lower { s = @ }
              n
        """.trimIndent()
        assertDoesNotThrow { parser.parseParsedModule(source) }
    }

    @Test
    fun `a pipe chain as the do-block result with stage comments parses`() {
        val source = """
            use std::str
            let main = {} -> do
              let z = 1
              "a"
                |> str::to_upper { s = @ }   # one
                |> str::to_lower { s = @ }   # two
        """.trimIndent()
        assertDoesNotThrow { parser.parseParsedModule(source) }
    }

    @Test
    fun `a stage comment is captured on the pipe AST`() {
        val source = """
            use std::str
            let n = "a"
              |> str::to_upper { s = @ }   # captured
              |> str::to_lower { s = @ }
        """.trimIndent()
        val module = parser.parseParsedModule(source)
        // Walk the parsed tree for any ParsedPipe carrying our comment.
        val texts = mutableListOf<String>()
        fun walk(e: ParsedExpr) {
            if (e is ParsedPipe) {
                e.trailingComments.forEach { texts.add(it.text) }
                walk(e.left); walk(e.right)
            }
        }
        // The single top-level decl's value is the pipe chain.
        module.decls.filterIsInstance<ParsedLetDecl>().forEach { walk(it.value) }
        assertTrue(texts.any { it.contains("# captured") },
            "the inline stage comment was not captured on ParsedPipe.trailingComments; found $texts")
    }
}
