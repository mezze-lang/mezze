package com.mezze.language.parser

import com.mezze.language.parsedast.ParsedDo
import com.mezze.language.parsedast.ParsedLambda
import com.mezze.language.parsedast.ParsedLetDecl
import com.mezze.language.parsedast.ParsedStatement
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows

/**
 * Do-block binding statements. A binding REQUIRES a leading `let`:
 * `let pat [: T] = expr`. A bare `pat = expr` (no `let`) is a parse error
 * with a targeted message. Requiring `let` disambiguates a binding from a
 * bare expression at the first token, keeping the grammar LL(1)-friendly and
 * tooling-parseable.
 */
class LetBindStatementTest {
    private val parser = MezzeParser()

    private fun doStatements(src: String): List<ParsedStatement> {
        val m = parser.parseParsedModule(src.trimIndent())
        val decl = m.decls.filterIsInstance<ParsedLetDecl>().first()
        val body = (decl.value as ParsedLambda).body as ParsedDo
        return body.statements
    }

    @Test fun `let equals is a plain Let statement`() {
        val stmts = doStatements("let f = {} -> do\n  let x = compute {}\n  x")
        assertTrue(stmts.single() is ParsedStatement.Let)
    }

    @Test fun `bare equals binding without let is rejected with a clear message`() {
        val ex = assertThrows<ParseException> {
            parser.parseParsedModule("let f = {} -> do\n  x = compute {}\n  x")
        }
        assertTrue(ex.message?.contains("must start with `let`") == true,
            "expected a missing-let hint, got: ${ex.message}")
    }
}
