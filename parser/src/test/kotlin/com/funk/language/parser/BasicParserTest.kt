package com.funk.language.parser

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class BasicParserTest {

    private val parser = FunkParserImpl()

    @Test
    fun `parses simple let binding`() {
        val code = "let x = 42"
        val ast = parser.parse(code)
        
        assertNotNull(ast)
        assertEquals(1, ast.items.size)
        
        val letBinding = ast.items[0] as LetBinding
        assertEquals("x", letBinding.variableName)
        assertEquals(null, letBinding.typeAnnotation)
        // Simple let binding, not a lambda
        
        val literal = letBinding.expression as Literal<*>
        assertEquals(42, literal.value)
    }

    @Test
    fun `parses lambda function definition`() {
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
    }

    @Test
    fun `parses record literal`() {
        val code = "let person = { name = \"Alice\", age = 30 }"
        val ast = parser.parse(code)
        
        assertEquals(1, ast.items.size)
        val letBinding = ast.items[0] as LetBinding
        
        val recordLiteral = letBinding.expression as RecordLiteral
        assertEquals(2, recordLiteral.entries.size)
        assertEquals("name", recordLiteral.entries[0].name)
        assertEquals("Alice", (recordLiteral.entries[0].value as Literal<*>).value)
    }

    @Test
    fun `parses if expression`() {
        val code = "if true then 1 else 2"
        val ast = parser.parse(code)
        
        assertEquals(1, ast.items.size)
        val stmt = ast.items[0] as ExpressionStatement
        val ifExpr = stmt.expression as IfExpression
        
        assertEquals(true, (ifExpr.condition as Literal<*>).value)
        assertEquals(1, (ifExpr.thenBranch as Literal<*>).value)
        assertEquals(2, (ifExpr.elseBranch as Literal<*>).value)
    }

    @Test
    fun `parses type declaration`() {
        val code = "type Person = { name: String, age: Int }"
        val ast = parser.parse(code)
        
        assertEquals(1, ast.items.size)
        val typeDecl = ast.items[0] as TypeDeclaration
        assertEquals("Person", typeDecl.name)
        
        val recordType = typeDecl.definition as RecordType
        assertEquals(2, recordType.entries.size)
        assertEquals("name", recordType.entries[0].name)
        assertEquals("String", (recordType.entries[0].type as SimpleType).name)
    }

    @Test
    fun `parses variant type`() {
        val code = "type Color = | Red, Green, Blue |"
        val ast = parser.parse(code)
        
        assertEquals(1, ast.items.size)
        val typeDecl = ast.items[0] as TypeDeclaration
        assertEquals("Color", typeDecl.name)
        
        val variantType = typeDecl.definition as VariantType
        assertEquals(3, variantType.constructors.size)
        assertEquals("Red", variantType.constructors[0].name)
        assertEquals("Green", variantType.constructors[1].name)
        assertEquals("Blue", variantType.constructors[2].name)
    }

    @Test
    fun `parses match expression`() {
        val code = """
            match color {
                Red -> "red"
                Green -> "green"
            }
        """.trimIndent()
        
        val ast = parser.parse(code)
        assertEquals(1, ast.items.size)
        
        val stmt = ast.items[0] as ExpressionStatement
        val matchExpr = stmt.expression as MatchExpression
        
        assertEquals(2, matchExpr.cases.size)
        assertEquals("Red", (matchExpr.cases[0].pattern as VariablePattern).name)
        assertEquals("red", (matchExpr.cases[0].expression as Literal<*>).value)
    }

    @Test
    fun `parses multiple statements`() {
        val code = """
            let x = 10
            let y = 20
            type Point = { x: Int, y: Int }
        """.trimIndent()
        
        val ast = parser.parse(code)
        assertEquals(3, ast.items.size)
        
        assertTrue(ast.items[0] is LetBinding)
        assertTrue(ast.items[1] is LetBinding)
        assertTrue(ast.items[2] is TypeDeclaration)
    }
}
