package com.mezze.language.parser

import com.mezze.language.parsedast.*
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
        val module = parser.parseParsedModule(source)

        assertEquals(1, module.decls.size)
        val item = module.decls[0] as ParsedTopLevelExpr
        val expr = item.expression

        assertTrue(expr is ParsedLambda, "Should be lambda expression")
        val lambda = expr as ParsedLambda
        assertTrue(lambda.params is ParsedLambdaParams.Untyped)
        assertTrue(lambda.body is ParsedIntLit)
    }

    @Test
    fun `test untyped lambda with single parameter`() {
        val source = "{x} -> x"
        val module = parser.parseParsedModule(source)

        val expr = (module.decls[0] as ParsedTopLevelExpr).expression
        assertTrue(expr is ParsedLambda)
        val lambda = expr as ParsedLambda
        val params = lambda.params as ParsedLambdaParams.Untyped
        assertEquals(listOf("x"), params.names)
    }

    @Test
    fun `test untyped lambda with multiple parameters`() {
        val source = "{x, y} -> x"
        val module = parser.parseParsedModule(source)

        val expr = (module.decls[0] as ParsedTopLevelExpr).expression
        assertTrue(expr is ParsedLambda)
        val lambda = expr as ParsedLambda
        val params = lambda.params as ParsedLambdaParams.Untyped
        assertEquals(listOf("x", "y"), params.names)
    }

    @Test
    fun `test record literal with punning`() {
        val source = "{x}"
        val module = parser.parseParsedModule(source)

        val expr = (module.decls[0] as ParsedTopLevelExpr).expression
        assertTrue(expr is ParsedRecordLit, "Should be record literal, not lambda")
    }

    @Test
    fun `test record literal with assignment`() {
        val source = "{x = 1}"
        val module = parser.parseParsedModule(source)

        val expr = (module.decls[0] as ParsedTopLevelExpr).expression
        assertTrue(expr is ParsedRecordLit)
    }

    @Test
    fun `test typed lambda`() {
        val source = "{x: Int} -> x"
        val module = parser.parseParsedModule(source)

        val expr = (module.decls[0] as ParsedTopLevelExpr).expression
        assertTrue(expr is ParsedLambda)
        val lambda = expr as ParsedLambda
        assertTrue(lambda.params is ParsedLambdaParams.Typed)
    }

    @Test
    fun `test lambda with record literal in body`() {
        // This is the critical test case that ANTLR couldn't handle!
        val source = "{x} -> {x = x}"
        val module = parser.parseParsedModule(source)

        val expr = (module.decls[0] as ParsedTopLevelExpr).expression
        assertTrue(expr is ParsedLambda, "Should parse as lambda despite record in body")
        val lambda = expr as ParsedLambda
        assertTrue(lambda.body is ParsedRecordLit)
    }

    @Test
    fun `test multi-parameter lambda with three parameters`() {
        val source = "{x, y, z} -> x"
        val module = parser.parseParsedModule(source)

        val expr = (module.decls[0] as ParsedTopLevelExpr).expression
        assertTrue(expr is ParsedLambda)
        val lambda = expr as ParsedLambda
        val params = lambda.params as ParsedLambdaParams.Untyped
        assertEquals(listOf("x", "y", "z"), params.names)
    }

    @Test
    fun `test multi-parameter lambda in let binding`() {
        val source = "let concat = {xs, ys} -> xs"
        val module = parser.parseParsedModule(source)

        assertEquals(1, module.decls.size)
        val item = module.decls[0] as ParsedLetDecl
        val expr = item.value
        assertTrue(expr is ParsedLambda, "Should be lambda expression")
        val lambda = expr as ParsedLambda
        val params = lambda.params as ParsedLambdaParams.Untyped
        assertEquals(listOf("xs", "ys"), params.names)
    }

    @Test
    fun `test multi-parameter lambda with function call body`() {
        val source = "{xs, ys} -> concat { xs = xs, ys = ys }"
        val module = parser.parseParsedModule(source)

        val expr = (module.decls[0] as ParsedTopLevelExpr).expression
        assertTrue(expr is ParsedLambda)
        val lambda = expr as ParsedLambda
        val params = lambda.params as ParsedLambdaParams.Untyped
        assertEquals(listOf("xs", "ys"), params.names)
        assertTrue(lambda.body is ParsedFunctionCall, "Body should be a function call")
    }

    @Test
    fun `test multi-parameter lambda with record literal body`() {
        val source = "{x, y} -> {a = x, b = y}"
        val module = parser.parseParsedModule(source)

        val expr = (module.decls[0] as ParsedTopLevelExpr).expression
        assertTrue(expr is ParsedLambda)
        val lambda = expr as ParsedLambda
        val params = lambda.params as ParsedLambdaParams.Untyped
        assertEquals(listOf("x", "y"), params.names)
        assertTrue(lambda.body is ParsedRecordLit, "Body should be a record literal")
    }

    @Test
    fun `test multi-parameter typed lambda`() {
        val source = "{x: Int, y: Int} -> x"
        val module = parser.parseParsedModule(source)

        val expr = (module.decls[0] as ParsedTopLevelExpr).expression
        assertTrue(expr is ParsedLambda)
        val lambda = expr as ParsedLambda
        assertTrue(lambda.params is ParsedLambdaParams.Typed)
        val params = lambda.params as ParsedLambdaParams.Typed
        assertEquals(2, params.fields.size)
        assertEquals("x", params.fields[0].name)
        assertEquals("y", params.fields[1].name)
    }

    @Test
    fun `test nested multi-parameter lambda`() {
        val source = "{x, y} -> {z} -> x"
        val module = parser.parseParsedModule(source)

        val expr = (module.decls[0] as ParsedTopLevelExpr).expression
        assertTrue(expr is ParsedLambda)
        val outerLambda = expr as ParsedLambda
        val outerParams = outerLambda.params as ParsedLambdaParams.Untyped
        assertEquals(listOf("x", "y"), outerParams.names)

        assertTrue(outerLambda.body is ParsedLambda, "Body should be another lambda")
        val innerLambda = outerLambda.body as ParsedLambda
        val innerParams = innerLambda.params as ParsedLambdaParams.Untyped
        assertEquals(listOf("z"), innerParams.names)
    }
}
