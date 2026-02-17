package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse

/**
 * Tests for recursive type declarations to ensure type parameters are preserved correctly
 */
class RecursiveTypeDeclarationTest {
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
    fun `test simple parameterized type preserves type variable`() {
        val source = """
            type Simple 'a = | A { x: 'a, y: 'a } |
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program).program

        // Extract the type declaration
        val typeDecl = result.items.filterIsInstance<com.mezze.language.ast.TypeDeclaration>().first()
        val definition = typeDecl.definition as com.mezze.language.ast.VariantDefinition
        val constructorDataType = definition.constructors[0].dataType as com.mezze.language.ast.RecordTypeAnnotation

        // Both fields should reference the same type variable 'a'
        val xType = constructorDataType.entries.find { it.name == "x" }!!.type as com.mezze.language.ast.IdentifierTypeAnnotation
        val yType = constructorDataType.entries.find { it.name == "y" }!!.type as com.mezze.language.ast.IdentifierTypeAnnotation

        assertEquals("'a", xType.name)
        assertEquals("'a", yType.name)
    }

    @Test
    fun `test recursive type declaration preserves type variable`() {
        val source = """
            type Tree 'a = | Leaf, Node { value: 'a, left: Tree 'a, right: Tree 'a } |
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program).program

        // Extract the type declaration
        val typeDecl = result.items.filterIsInstance<com.mezze.language.ast.TypeDeclaration>().first()
        val definition = typeDecl.definition as com.mezze.language.ast.VariantDefinition

        // Find the Node constructor
        val nodeConstructor = definition.constructors.find { it.name == "Node" }!!
        val recordType = nodeConstructor.dataType as com.mezze.language.ast.RecordTypeAnnotation

        // Get the three field types
        val valueType = recordType.entries.find { it.name == "value" }!!.type as com.mezze.language.ast.IdentifierTypeAnnotation
        val leftType = recordType.entries.find { it.name == "left" }!!.type as com.mezze.language.ast.TypeApplicationAnnotation
        val rightType = recordType.entries.find { it.name == "right" }!!.type as com.mezze.language.ast.TypeApplicationAnnotation

        // Value field should be 'a
        assertEquals("'a", valueType.name)

        // Left and right should be Tree 'a
        assertEquals("Tree", leftType.constructor)
        assertEquals(1, leftType.args.size)
        assertEquals("'a", (leftType.args[0] as com.mezze.language.ast.IdentifierTypeAnnotation).name)

        assertEquals("Tree", rightType.constructor)
        assertEquals(1, rightType.args.size)
        assertEquals("'a", (rightType.args[0] as com.mezze.language.ast.IdentifierTypeAnnotation).name)
    }

    @Test
    fun `test tree type declaration prints with single type variable`() {
        // This is the main regression test - ensures that Tree 'a prints as
        // Tree = | Leaf, Node { left: Tree 'a, right: Tree 'a, value: 'a } |
        // and NOT as Tree = | Leaf, Node { left: 'a, right: 'b, value: 'c } |
        val source = """
            type Tree 'a = | Leaf, Node { value: 'a, left: Tree 'a, right: Tree 'a } |
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program).program

        // This test just verifies typechecking succeeds
        // The actual printing is verified manually
        assertEquals(1, result.items.size)

        // Verify there are no spurious type variables (like 'b, 'c)
        val typeDecl = result.items.filterIsInstance<com.mezze.language.ast.TypeDeclaration>().first()
        assertFalse(typeDecl.toString().contains("'b"), "Should not contain 'b type variable")
        assertFalse(typeDecl.toString().contains("'c"), "Should not contain 'c type variable")
    }
}
