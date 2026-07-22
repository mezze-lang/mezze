package com.mezze.language.parser

import com.mezze.language.parsedast.ParsedDo
import com.mezze.language.parsedast.ParsedLambda
import com.mezze.language.parsedast.ParsedLetDecl
import com.mezze.language.parsedast.ParsedStatement
import com.mezze.language.parsedast.ParsedVariablePattern
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows

/**
 * Do-block binding statements. BOTH forms REQUIRE a leading `let`:
 *   `let pat = expr`   — plain value binding
 *   `let pat <- expr`  — do-bind (unwrap + short-circuit)
 *
 * A bare `pat = expr` or `pat <- expr` (no `let`) is a parse error with a
 * targeted message. Requiring `let` disambiguates a binding from a bare
 * expression at the first token, keeping the grammar LL(1)-friendly and
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

    // ---- positive: both forms parse to the right node ----

    @Test fun `let equals is a plain Let statement`() {
        val stmts = doStatements("let f = {} -> do\n  let x = compute {}\n  x")
        assertTrue(stmts.single() is ParsedStatement.Let)
    }

    @Test fun `let left-arrow is a Bind statement`() {
        val stmts = doStatements("let f = {} -> do\n  let x <- fetch {}\n  x")
        val bind = stmts.single() as ParsedStatement.Bind
        assertEquals("x", (bind.pattern as ParsedVariablePattern).name)
    }

    @Test fun `let mixes equals and left-arrow in one block`() {
        val stmts = doStatements(
            "let f = {} -> do\n  let a = seed {}\n  let b <- fetch {x = a}\n  b"
        )
        assertTrue(stmts[0] is ParsedStatement.Let)
        assertTrue(stmts[1] is ParsedStatement.Bind)
    }

    @Test fun `let bind with wildcard`() {
        val stmts = doStatements("let f = {} -> do\n  let _ <- effectful {}\n  0")
        assertTrue(stmts.single() is ParsedStatement.Bind)
    }

    @Test fun `let bind with record destructure`() {
        val stmts = doStatements("let f = {} -> do\n  let {a, b} <- get_both {}\n  a")
        assertTrue(stmts.single() is ParsedStatement.Bind)
    }

    // ---- negative: missing `let`, with a helpful message ----

    @Test fun `bare equals binding without let is rejected with a clear message`() {
        val ex = assertThrows<ParseException> {
            parser.parseParsedModule("let f = {} -> do\n  x = compute {}\n  x")
        }
        assertTrue(ex.message?.contains("must start with `let`") == true,
            "expected a missing-let hint, got: ${ex.message}")
    }

    @Test fun `bare left-arrow bind without let is rejected with a clear message`() {
        val ex = assertThrows<ParseException> {
            parser.parseParsedModule("let f = {} -> do\n  x <- fetch {}\n  x")
        }
        assertTrue(ex.message?.contains("do-bind must start with `let`") == true,
            "expected a missing-let hint for the bind, got: ${ex.message}")
    }

    // ---- a refutable (constructor) pattern in a bind is still rejected ----

    @Test fun `let bind with a variant constructor pattern is rejected`() {
        // `<-` unwraps its receiver's success value; matching that as a variant
        // constructor is a category error.
        assertThrows<ParseException> {
            parser.parseParsedModule("let f = {} -> do\n  let Some {val = v} <- fetch {}\n  v")
        }
    }

    // ---- a type annotation on a bind is rejected ----

    @Test fun `let bind cannot carry a type annotation`() {
        assertThrows<ParseException> {
            parser.parseParsedModule("let f = {} -> do\n  let x: Int <- fetch {}\n  x")
        }
    }
}
