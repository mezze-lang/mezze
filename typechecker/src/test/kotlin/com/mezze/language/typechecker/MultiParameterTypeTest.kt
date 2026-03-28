package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows

/**
 * Tests for multi-parameter type declarations
 */
class MultiParameterTypeTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    @Test
    fun `test two parameter type declaration parses correctly`() {
        val source = """
            type Either 'a 'b = | Left { val: 'a }, Right { val: 'b } |
        """.trimIndent()

        // Check parsed AST directly
        val program = parser.parse(source)
        val typeDecl = program.items.filterIsInstance<com.mezze.language.ast.TypeDeclaration>().first()
        assertEquals("Either", typeDecl.name)
        assertEquals(2, typeDecl.typeParams.size)
        assertEquals("'a", typeDecl.typeParams[0])
        assertEquals("'b", typeDecl.typeParams[1])

        // Also verify type checking succeeds
        val result = typeChecker.typeCheck(program)
        assertNotNull(result)
    }

    @Test
    fun `test Result type with two parameters`() {
        val source = """
            type Result 'ok 'err = | Ok 'ok, Err 'err |
        """.trimIndent()

        val program = parser.parse(source)
        val typeDecl = program.items.filterIsInstance<com.mezze.language.ast.TypeDeclaration>().first()
        assertEquals("Result", typeDecl.name)
        assertEquals(2, typeDecl.typeParams.size)
        assertEquals("'ok", typeDecl.typeParams[0])
        assertEquals("'err", typeDecl.typeParams[1])

        val result = typeChecker.typeCheck(program)
        assertNotNull(result)
    }

    @Test
    fun `test three parameter type declaration`() {
        val source = """
            type Triple 'a 'b 'c = | MkTriple { first: 'a, second: 'b, third: 'c } |
        """.trimIndent()

        val program = parser.parse(source)
        val typeDecl = program.items.filterIsInstance<com.mezze.language.ast.TypeDeclaration>().first()
        assertEquals("Triple", typeDecl.name)
        assertEquals(3, typeDecl.typeParams.size)

        val result = typeChecker.typeCheck(program)
        assertNotNull(result)
    }

    @Test
    fun `test Either type with constructors`() {
        val source = """
            type Either 'a 'b = | Left { val: 'a }, Right { val: 'b } |

            let left_val = Left { val = 42 }
            let right_val = Right { val = "hello" }
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)

        // Check that the let bindings typecheck via TypedModule
        val leftType = result.getVariableType("left_val")
        assertNotNull(leftType)

        val rightType = result.getVariableType("right_val")
        assertNotNull(rightType)
    }

    @Test
    fun `test multi-parameter type with record constructors`() {
        val source = """
            type Pair 'a 'b = | MkPair { first: 'a, second: 'b } |

            let pair = MkPair { first = 1, second = "one" }
        """.trimIndent()

        val program = parser.parse(source)
        val typeDecl = program.items.filterIsInstance<com.mezze.language.ast.TypeDeclaration>().first()
        assertEquals("Pair", typeDecl.name)
        assertEquals(2, typeDecl.typeParams.size)

        val result = typeChecker.typeCheck(program)
        val pairType = result.getVariableType("pair")
        assertNotNull(pairType)
    }

    @Test
    fun `test multi-parameter type with mixed constructor payloads`() {
        val source = """
            type Maybe2 'a 'b = | Nothing, JustFirst { first: 'a }, JustSecond { second: 'b }, Both { first: 'a, second: 'b } |
        """.trimIndent()

        val program = parser.parse(source)
        val typeDecl = program.items.filterIsInstance<com.mezze.language.ast.TypeDeclaration>().first()
        assertEquals("Maybe2", typeDecl.name)
        assertEquals(2, typeDecl.typeParams.size)

        val definition = typeDecl.definition as com.mezze.language.ast.VariantDefinition
        assertEquals(4, definition.constructors.size)
    }

    @Test
    fun `test multi-parameter type preserves type variables correctly`() {
        val source = """
            type Either 'a 'b = | Left { val: 'a }, Right { val: 'b } |
        """.trimIndent()

        val program = parser.parse(source)
        val typeDecl = program.items.filterIsInstance<com.mezze.language.ast.TypeDeclaration>().first()
        val definition = typeDecl.definition as com.mezze.language.ast.VariantDefinition

        // Check Left constructor
        val leftConstructor = definition.constructors.find { it.name == "Left" }!!
        val leftType = leftConstructor.dataType as com.mezze.language.ast.RecordTypeAnnotation
        val leftField = leftType.entries.find { it.name == "val" }!!
        val leftFieldType = leftField.type as com.mezze.language.ast.IdentifierTypeAnnotation
        assertEquals("'a", leftFieldType.name)

        // Check Right constructor
        val rightConstructor = definition.constructors.find { it.name == "Right" }!!
        val rightType = rightConstructor.dataType as com.mezze.language.ast.RecordTypeAnnotation
        val rightField = rightType.entries.find { it.name == "val" }!!
        val rightFieldType = rightField.type as com.mezze.language.ast.IdentifierTypeAnnotation
        assertEquals("'b", rightFieldType.name)
    }

    @Test
    fun `test multi-parameter type without constructor payloads`() {
        val source = """
            type Status 'a 'b = | Pending, InProgress, Complete |
        """.trimIndent()

        val program = parser.parse(source)
        val typeDecl = program.items.filterIsInstance<com.mezze.language.ast.TypeDeclaration>().first()
        assertEquals("Status", typeDecl.name)
        assertEquals(2, typeDecl.typeParams.size)

        val definition = typeDecl.definition as com.mezze.language.ast.VariantDefinition
        assertEquals(3, definition.constructors.size)

        // All constructors should have null dataType (unit constructors)
        definition.constructors.forEach { constructor ->
            assertNull(constructor.dataType)
        }
    }

    @Test
    fun `test Either with wrong syntax should fail`() {
        val source = """
            type Either 'a 'b = | Left { val: 'a }, Right { val: 'b } |

            let wrong = Left 42
        """.trimIndent()

        // The parser should now reject "Left 42" syntax
        val exception = assertThrows<Exception> {
            parser.parse(source)
        }

        // Verify the error message mentions that variant constructors must use {} syntax
        assertTrue(exception.message?.contains("Variant constructors must use {} syntax") == true,
                   "Error message should mention variant constructor syntax requirement, but got: ${exception.message}")
    }
}
