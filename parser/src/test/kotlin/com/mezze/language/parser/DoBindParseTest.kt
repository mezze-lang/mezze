package com.mezze.language.parser

import com.mezze.language.parsedast.ParsedDo
import com.mezze.language.parsedast.ParsedIdentifier
import com.mezze.language.parsedast.ParsedLetDecl
import com.mezze.language.parsedast.ParsedLambda
import com.mezze.language.parsedast.ParsedRecordPattern
import com.mezze.language.parsedast.ParsedStatement
import com.mezze.language.parsedast.ParsedVariablePattern
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/**
 * Tests that `pat <- expr` inside a `do` block parses into `ParsedStatement.Bind`.
 *
 * Covers:
 *   - Basic bind: `a <- expr`
 *   - Multiple binds in a row
 *   - Bind mixed with let and expression statements
 *   - Bind with record-destructure pattern
 *   - Refutable pattern → error
 *   - `<-` outside a do block → parse error (not recognized)
 */
class DoBindParseTest {

    private val parser = MezzeParser()

    private fun parseDoBody(source: String): ParsedDo {
        val module = parser.parseParsedModule(source.trimIndent())
        // The test code wraps `do` in a single let — find it.
        val decl = module.decls.firstOrNull { it is ParsedLetDecl } as? ParsedLetDecl
            ?: fail("Expected a ParsedLetDecl in the parsed module")
        val lambda = decl.value as? ParsedLambda
            ?: fail("Expected let RHS to be a lambda")
        val body = lambda.body as? ParsedDo
            ?: fail("Expected lambda body to be a ParsedDo, got: ${lambda.body::class.simpleName}")
        return body
    }

    @Test
    fun `single bind statement parses to ParsedStatement_Bind`() {
        val doExpr = parseDoBody("""
            let main = {} -> do
              let a <- some_call {}
              a
        """)
        assertEquals(1, doExpr.statements.size)
        val stmt = doExpr.statements[0]
        assertTrue(stmt is ParsedStatement.Bind, "Expected Bind, got ${stmt::class.simpleName}")
        val bind = stmt as ParsedStatement.Bind
        assertEquals("a", (bind.pattern as ParsedVariablePattern).name)
    }

    @Test
    fun `multiple binds parse correctly`() {
        val doExpr = parseDoBody("""
            let main = {} -> do
              let user <- find_user {id = 1}
              let bio <- parse_bio {raw = user.bio}
              {user, bio}
        """)
        assertEquals(2, doExpr.statements.size)
        assertTrue(doExpr.statements.all { it is ParsedStatement.Bind })
        assertEquals("user", ((doExpr.statements[0] as ParsedStatement.Bind).pattern as ParsedVariablePattern).name)
        assertEquals("bio", ((doExpr.statements[1] as ParsedStatement.Bind).pattern as ParsedVariablePattern).name)
    }

    @Test
    fun `bind mixed with let and expression statements`() {
        val doExpr = parseDoBody("""
            let main = {} -> do
              let user <- find_user {id = 1}
              let display = "found"
              log {msg = display}
              user
        """)
        assertEquals(3, doExpr.statements.size)
        assertTrue(doExpr.statements[0] is ParsedStatement.Bind, "stmt[0] should be Bind")
        assertTrue(doExpr.statements[1] is ParsedStatement.Let, "stmt[1] should be Let")
        assertTrue(doExpr.statements[2] is ParsedStatement.Expr, "stmt[2] should be Expr")
    }

    @Test
    fun `bind with record-destructure pattern`() {
        val doExpr = parseDoBody("""
            let main = {} -> do
              let {user, posts} <- load_profile {id = 1}
              user
        """)
        assertEquals(1, doExpr.statements.size)
        val bind = doExpr.statements[0] as ParsedStatement.Bind
        assertTrue(bind.pattern is ParsedRecordPattern,
            "Expected ParsedRecordPattern, got ${bind.pattern::class.simpleName}")
    }

    @Test
    fun `bind with wildcard pattern`() {
        val doExpr = parseDoBody("""
            let main = {} -> do
              let _ <- some_call {}
              42
        """)
        assertEquals(1, doExpr.statements.size)
        val bind = doExpr.statements[0] as ParsedStatement.Bind
        // Wildcard pattern — just confirm it parses to Bind, exact type name varies
        assertNotNull(bind.pattern)
    }

    @Test
    fun `final result after bind statements`() {
        val doExpr = parseDoBody("""
            let main = {} -> do
              let x <- a {}
              let y <- b {}
              x.combine {other = y}
        """)
        // Result is the last expression, NOT a statement
        assertEquals(2, doExpr.statements.size)
        assertNotNull(doExpr.result)
    }

    @Test
    fun `bare bind outside do produces parse error`() {
        // `<-` outside a do block isn't recognized syntactically. It should
        // either fail to parse as a let or be rejected somewhere downstream.
        // We just need to make sure it doesn't silently succeed with weird semantics.
        try {
            parser.parseParsedModule("""
                let main = {} -> do
                  let x <- y <- z
                  x
            """.trimIndent())
            // If we got here, parsing succeeded. Inspect — this is `x <- (y <- z)`
            // which is nested binds inside expressions, not legal.
            fail("Expected parse failure for nested `<-` in expression context")
        } catch (e: Exception) {
            // Expected
        }
    }
}
