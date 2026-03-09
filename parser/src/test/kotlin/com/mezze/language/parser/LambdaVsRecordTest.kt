package com.mezze.language.parser

import com.mezze.language.ast.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Test the critical disambiguation between lambda expressions and record literals.
 */
class LambdaVsRecordTest {

    private val parser = MezzeParser()

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

    @Test
    fun `test multi-parameter lambda with three parameters`() {
        val source = "{x, y, z} -> x"
        val program = parser.parse(source)

        val expr = (program.items[0] as TopLevelExpression).expression
        assertTrue(expr is LambdaExpression)
        val lambda = expr as LambdaExpression
        val params = lambda.parameters as UntypedParameters
        assertEquals(listOf("x", "y", "z"), params.names)
    }

    @Test
    fun `test multi-parameter lambda in let binding`() {
        val source = "let concat = {xs, ys} -> xs"
        val program = parser.parse(source)

        assertEquals(1, program.items.size)
        val item = program.items[0] as LetBinding
        val expr = item.expression
        assertTrue(expr is LambdaExpression, "Should be lambda expression")
        val lambda = expr as LambdaExpression
        val params = lambda.parameters as UntypedParameters
        assertEquals(listOf("xs", "ys"), params.names)
    }

    @Test
    fun `test multi-parameter lambda with function call body`() {
        val source = "{xs, ys} -> concat { xs = xs, ys = ys }"
        val program = parser.parse(source)

        val expr = (program.items[0] as TopLevelExpression).expression
        assertTrue(expr is LambdaExpression)
        val lambda = expr as LambdaExpression
        val params = lambda.parameters as UntypedParameters
        assertEquals(listOf("xs", "ys"), params.names)
        assertTrue(lambda.body is FunctionCall, "Body should be a function call")
    }

    @Test
    fun `test multi-parameter lambda with record literal body`() {
        val source = "{x, y} -> {a = x, b = y}"
        val program = parser.parse(source)

        val expr = (program.items[0] as TopLevelExpression).expression
        assertTrue(expr is LambdaExpression)
        val lambda = expr as LambdaExpression
        val params = lambda.parameters as UntypedParameters
        assertEquals(listOf("x", "y"), params.names)
        assertTrue(lambda.body is RecordLiteral, "Body should be a record literal")
    }

    @Test
    fun `test multi-parameter typed lambda`() {
        val source = "{x: Int, y: Int} -> x"
        val program = parser.parse(source)

        val expr = (program.items[0] as TopLevelExpression).expression
        assertTrue(expr is LambdaExpression)
        val lambda = expr as LambdaExpression
        assertTrue(lambda.parameters is TypedParameters)
        val params = lambda.parameters as TypedParameters
        assertEquals(2, params.recordType.entries.size)
        assertEquals("x", params.recordType.entries[0].name)
        assertEquals("y", params.recordType.entries[1].name)
    }

    @Test
    fun `test nested multi-parameter lambda`() {
        val source = "{x, y} -> {z} -> x"
        val program = parser.parse(source)

        val expr = (program.items[0] as TopLevelExpression).expression
        assertTrue(expr is LambdaExpression)
        val outerLambda = expr as LambdaExpression
        val outerParams = outerLambda.parameters as UntypedParameters
        assertEquals(listOf("x", "y"), outerParams.names)

        assertTrue(outerLambda.body is LambdaExpression, "Body should be another lambda")
        val innerLambda = outerLambda.body as LambdaExpression
        val innerParams = innerLambda.parameters as UntypedParameters
        assertEquals(listOf("z"), innerParams.names)
    }
}
