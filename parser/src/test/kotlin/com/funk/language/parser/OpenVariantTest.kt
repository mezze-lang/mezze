package com.funk.language.parser

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class OpenVariantTest {

    private val parser = FunkParserImpl()

    @Test
    fun `parses closed variant type`() {
        val code = "type Color = | Red, Green, Blue |"
        val ast = parser.parse(code)

        assertEquals(1, ast.items.size)
        val typeDecl = ast.items[0] as TypeDeclaration
        assertEquals("Color", typeDecl.name)

        val variantType = typeDecl.definition as VariantType
        assertEquals(3, variantType.constructors.size)
        assertEquals(false, variantType.isOpen)
        assertEquals("Red", variantType.constructors[0].name)
        assertEquals("Green", variantType.constructors[1].name)
        assertEquals("Blue", variantType.constructors[2].name)

        println("✅ Closed variant type parsing works")
    }

    @Test
    fun `parses open variant type`() {
        val code = "type ExtensibleColor = | Red, Green, Blue, ... |"
        val ast = parser.parse(code)

        assertEquals(1, ast.items.size)
        val typeDecl = ast.items[0] as TypeDeclaration
        assertEquals("ExtensibleColor", typeDecl.name)

        val variantType = typeDecl.definition as VariantType
        assertEquals(3, variantType.constructors.size)
        assertEquals(true, variantType.isOpen)
        assertEquals("Red", variantType.constructors[0].name)
        assertEquals("Green", variantType.constructors[1].name)
        assertEquals("Blue", variantType.constructors[2].name)

        println("✅ Open variant type parsing works")
    }

    @Test
    fun `parses open variant with record constructors`() {
        val code = "type ExtensibleResult = | Ok { value: Int }, Error { message: String }, ... |"
        val ast = parser.parse(code)

        assertEquals(1, ast.items.size)
        val typeDecl = ast.items[0] as TypeDeclaration
        val variantType = typeDecl.definition as VariantType

        assertEquals(2, variantType.constructors.size)
        assertEquals(true, variantType.isOpen)

        val okConstructor = variantType.constructors[0]
        assertEquals("Ok", okConstructor.name)
        assertNotNull(okConstructor.recordType)
        assertEquals(1, okConstructor.recordType!!.entries.size)
        assertEquals("value", okConstructor.recordType!!.entries[0].name)

        val errorConstructor = variantType.constructors[1]
        assertEquals("Error", errorConstructor.name)
        assertNotNull(errorConstructor.recordType)
        assertEquals(1, errorConstructor.recordType!!.entries.size)
        assertEquals("message", errorConstructor.recordType!!.entries[0].name)

        println("✅ Open variant with record constructors parsing works")
    }

    @Test
    fun `parses anonymous open variant in function parameter`() {
        val code = "let process = { color: | Red, Green, Blue, ... | } -> color"
        val ast = parser.parse(code)

        assertEquals(1, ast.items.size)
        val letBinding = ast.items[0] as LetBinding
        val lambdaExpr = letBinding.expression as LambdaExpression

        val typedParams = lambdaExpr.parameters as TypedParameters
        assertEquals(1, typedParams.recordType.entries.size)
        val paramType = typedParams.recordType.entries[0].type as VariantType
        assertEquals(true, paramType.isOpen)
        assertEquals(3, paramType.constructors.size)
        assertEquals("Red", paramType.constructors[0].name)
        assertEquals("Green", paramType.constructors[1].name)
        assertEquals("Blue", paramType.constructors[2].name)

        println("✅ Anonymous open variant in function parameter parsing works")
    }

    @Test
    fun `parses anonymous closed variant in function parameter`() {
        val code = "let process = { result: | Ok { value: Int }, Error { message: String } | } -> result"
        val ast = parser.parse(code)

        assertEquals(1, ast.items.size)
        val letBinding = ast.items[0] as LetBinding
        val lambdaExpr = letBinding.expression as LambdaExpression

        val typedParams = lambdaExpr.parameters as TypedParameters
        assertEquals(1, typedParams.recordType.entries.size)
        val paramType = typedParams.recordType.entries[0].type as VariantType
        assertEquals(false, paramType.isOpen)
        assertEquals(2, paramType.constructors.size)
        assertEquals("Ok", paramType.constructors[0].name)
        assertEquals("Error", paramType.constructors[1].name)

        println("✅ Anonymous closed variant in function parameter parsing works")
    }

    @Test
    fun `compares open and closed variants`() {
        val code = """
            type ClosedColor = | Red, Green, Blue |
            type OpenColor = | Red, Green, Blue, ... |
        """.trimIndent()
        val ast = parser.parse(code)

        assertEquals(2, ast.items.size)
        
        val closedType = (ast.items[0] as TypeDeclaration).definition as VariantType
        val openType = (ast.items[1] as TypeDeclaration).definition as VariantType
        
        assertEquals(false, closedType.isOpen)
        assertEquals(true, openType.isOpen)
        
        // Both should have same constructors
        assertEquals(closedType.constructors.size, openType.constructors.size)
        assertEquals(closedType.constructors[0].name, openType.constructors[0].name)

        println("✅ Open vs closed variant comparison works")
    }
}

