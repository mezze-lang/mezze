package com.funk.language.parser

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class LambdaSyntaxTest {

    private val parser = FunkParserImpl()

    @Test
    fun `parses simple lambda function`() {
        val code = "let add = { x: Int, y: Int } -> x + y"
        val ast = parser.parse(code)
        
        assertEquals(1, ast.items.size)
        val letBinding = ast.items[0] as LetBinding
        assertEquals("add", letBinding.variableName)
        
        val lambdaExpr = letBinding.expression as LambdaExpression
        val typedParams = lambdaExpr.parameters as TypedParameters
        assertEquals(2, typedParams.recordType.entries.size)
        assertEquals("x", typedParams.recordType.entries[0].name)
        assertEquals("Int", (typedParams.recordType.entries[0].type as SimpleType).name)
        assertEquals("y", typedParams.recordType.entries[1].name)
        assertEquals("Int", (typedParams.recordType.entries[1].type as SimpleType).name)
        
        val body = lambdaExpr.body as BinaryOperation
        assertEquals("+", body.operator)
        assertEquals("x", (body.left as Identifier).name)
        assertEquals("y", (body.right as Identifier).name)
        
        println("✅ Simple lambda function parsing works")
    }

    @Test
    fun `parses lambda with single parameter`() {
        val code = "let double = { x: Int } -> x * 2"
        val ast = parser.parse(code)
        
        assertEquals(1, ast.items.size)
        val letBinding = ast.items[0] as LetBinding
        val lambdaExpr = letBinding.expression as LambdaExpression
        
        val typedParams = lambdaExpr.parameters as TypedParameters
        assertEquals(1, typedParams.recordType.entries.size)
        assertEquals("x", typedParams.recordType.entries[0].name)
        
        val body = lambdaExpr.body as BinaryOperation
        assertEquals("*", body.operator)
        assertEquals("x", (body.left as Identifier).name)
        assertEquals(2, (body.right as Literal<*>).value)
        
        println("✅ Single parameter lambda parsing works")
    }

    @Test
    fun `parses lambda with string concatenation`() {
        val code = "let greet = { name: String } -> \"Hello, \" ++ name ++ \"!\""
        val ast = parser.parse(code)
        
        assertEquals(1, ast.items.size)
        val letBinding = ast.items[0] as LetBinding
        val lambdaExpr = letBinding.expression as LambdaExpression
        
        val typedParams = lambdaExpr.parameters as TypedParameters
        assertEquals(1, typedParams.recordType.entries.size)
        assertEquals("name", typedParams.recordType.entries[0].name)
        assertEquals("String", (typedParams.recordType.entries[0].type as SimpleType).name)
        
        // The body should be a binary operation for string concatenation
        val body = lambdaExpr.body as BinaryOperation
        assertEquals("++", body.operator)
        
        println("✅ Lambda with string concatenation parsing works")
    }

    @Test
    fun `parses function call with lambda-defined function`() {
        val code = """
            let add = { x: Int, y: Int } -> x + y
            let result = add { x = 5, y = 10 }
        """.trimIndent()
        
        val ast = parser.parse(code)
        assertEquals(2, ast.items.size)
        
        // First: lambda definition
        val lambdaDef = ast.items[0] as LetBinding
        assertEquals("add", lambdaDef.variableName)
        assert(lambdaDef.expression is LambdaExpression)
        
        // Second: function call
        val functionCall = ast.items[1] as LetBinding
        assertEquals("result", functionCall.variableName)
        
        val callExpr = functionCall.expression as FunctionCall
        assertEquals("add", (callExpr.function as Identifier).name)
        val argsRecord = callExpr.arguments as RecordLiteral
        assertEquals(2, argsRecord.entries.size)
        assertEquals("x", argsRecord.entries[0].name)
        assertEquals(5, (argsRecord.entries[0].value as Literal<*>).value)
        
        println("✅ Function call with lambda-defined function parsing works")
    }

    @Test
    fun `parses partial application with lambdas`() {
        val code = """
            let add = { x: Int, y: Int } -> x + y
            let add5 = add { x = 5 }
            let result = add5 { y = 10 }
        """.trimIndent()
        
        val ast = parser.parse(code)
        assertEquals(3, ast.items.size)
        
        // First: lambda definition
        val lambdaDef = ast.items[0] as LetBinding
        assert(lambdaDef.expression is LambdaExpression)
        
        // Second: partial application
        val partialApp = ast.items[1] as LetBinding
        assertEquals("add5", partialApp.variableName)
        
        val partialCall = partialApp.expression as FunctionCall
        assertEquals("add", (partialCall.function as Identifier).name)
        val partialArgs = partialCall.arguments as RecordLiteral
        assertEquals(1, partialArgs.entries.size) // Only x provided
        
        // Third: complete application
        val completion = ast.items[2] as LetBinding
        assertEquals("result", completion.variableName)
        
        val completionCall = completion.expression as FunctionCall
        assertEquals("add5", (completionCall.function as Identifier).name)
        val completionArgs = completionCall.arguments as RecordLiteral
        assertEquals(1, completionArgs.entries.size) // Only y provided
        
        println("✅ Partial application with lambdas parsing works")
    }

    @Test
    fun `parses lambda with simple type annotation`() {
        val code = "let add : AddFunction = { x: Int, y: Int } -> x + y"
        val ast = parser.parse(code)
        
        assertEquals(1, ast.items.size)
        val letBinding = ast.items[0] as LetBinding
        assertEquals("add", letBinding.variableName)
        
        // Should have type annotation
        assertNotNull(letBinding.typeAnnotation)
        assertEquals("AddFunction", (letBinding.typeAnnotation as SimpleType).name)
        
        // Should have lambda expression
        assert(letBinding.expression is LambdaExpression)
        
        println("✅ Lambda with simple type annotation parsing works")
    }

    @Test
    fun `documents new lambda syntax advantages`() {
        println("\n🎉 NEW LAMBDA SYNTAX BENEFITS:")
        println("✅ Cleaner separation: let name = lambda")
        println("✅ More conventional arrow syntax: { params } -> body")
        println("✅ Better type annotation support")
        println("✅ Consistent with functional programming conventions")
        println("✅ Easier to distinguish functions from variables")
        println("✅ Partial application works seamlessly")
        
        println("\n📝 SYNTAX COMPARISON:")
        println("OLD: let add { x: Int, y: Int } = x + y")
        println("NEW: let add = { x: Int, y: Int } -> x + y")
        
        println("\n🔄 FUNCTION CALLS (unchanged):")
        println("add { x = 5, y = 10 }")
        
        // This test always passes - it's for documentation
        assertTrue(true)
    }
}
