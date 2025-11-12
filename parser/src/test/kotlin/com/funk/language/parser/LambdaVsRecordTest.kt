package com.funk.language.parser

import com.funk.language.ast.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Test the critical disambiguation between lambda expressions and record literals.
 */
class LambdaVsRecordTest {

    private val parser = FunkParser()

    @Test
    fun `test empty lambda`() {
        val source = "{ } -> 42"
        val program = parser.parse(source)

        assertEquals(1, program.items.size)
        val item = program.items[0] as TopLevelExpression
        val expr = item.expression

        assertTrue(expr is LambdaExpression, "Should be lambda expression")
        val lambda = expr as LambdaExpression
        assertTrue(lambda.parameters is UntypedParameters)
        assertTrue(lambda.body is IntLiteral)
    }

    @Test
    fun `test untyped lambda with single parameter`() {
        val source = "{x} -> x"
        val program = parser.parse(source)

        val expr = (program.items[0] as TopLevelExpression).expression
        assertTrue(expr is LambdaExpression)
        val lambda = expr as LambdaExpression
        val params = lambda.parameters as UntypedParameters
        assertEquals(listOf("x"), params.names)
    }

    @Test
    fun `test untyped lambda with multiple parameters`() {
        val source = "{x, y} -> x"
        val program = parser.parse(source)

        val expr = (program.items[0] as TopLevelExpression).expression
        assertTrue(expr is LambdaExpression)
        val lambda = expr as LambdaExpression
        val params = lambda.parameters as UntypedParameters
        assertEquals(listOf("x", "y"), params.names)
    }

    @Test
    fun `test record literal with punning`() {
        val source = "{x}"
        val program = parser.parse(source)

        val expr = (program.items[0] as TopLevelExpression).expression
        assertTrue(expr is RecordLiteral, "Should be record literal, not lambda")
    }

    @Test
    fun `test record literal with assignment`() {
        val source = "{x = 1}"
        val program = parser.parse(source)

        val expr = (program.items[0] as TopLevelExpression).expression
        assertTrue(expr is RecordLiteral)
    }

    @Test
    fun `test typed lambda`() {
        val source = "{x: Int} -> x"
        val program = parser.parse(source)

        val expr = (program.items[0] as TopLevelExpression).expression
        assertTrue(expr is LambdaExpression)
        val lambda = expr as LambdaExpression
        assertTrue(lambda.parameters is TypedParameters)
    }

    @Test
    fun `test lambda with record literal in body`() {
        // This is the critical test case that ANTLR couldn't handle!
        val source = "{x} -> {x = x}"
        val program = parser.parse(source)

        val expr = (program.items[0] as TopLevelExpression).expression
        assertTrue(expr is LambdaExpression, "Should parse as lambda despite record in body")
        val lambda = expr as LambdaExpression
        assertTrue(lambda.body is RecordLiteral)
    }
}
