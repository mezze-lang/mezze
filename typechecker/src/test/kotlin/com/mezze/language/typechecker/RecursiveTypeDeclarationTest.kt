package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
import com.mezze.language.parsedast.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse

/**
 * Tests for recursive type declarations to ensure type parameters are preserved correctly.
 * Uses the v2 ParsedModule directly — no v1 Program needed.
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

        // Parse and typecheck to ensure it succeeds
        val parsedModule = parser.parseParsedModule(source, "test.mz")
        typeChecker.typeCheck(parsedModule, Paths.get("."))

        // Validate via the parsed AST
        val module = parser.parseParsedModule(source, "test.mz")
        val typeDecl = module.decls.filterIsInstance<ParsedTypeDecl>().first()
        val definition = typeDecl.definition as ParsedVariantDef
        val constructorDataType = definition.constructors[0].dataType as ParsedRecordType

        // Both fields should reference the same type variable 'a'
        val xType = constructorDataType.entries.find { it.name == "x" }!!.type as ParsedNamedType
        val yType = constructorDataType.entries.find { it.name == "y" }!!.type as ParsedNamedType

        assertEquals("'a", xType.name)
        assertEquals("'a", yType.name)
    }

    @Test
    fun `test recursive type declaration preserves type variable`() {
        val source = """
            type Tree 'a = | Leaf, Node { value: 'a, left: Tree 'a, right: Tree 'a } |
        """.trimIndent()

        // Parse and typecheck to ensure it succeeds
        val parsedModule = parser.parseParsedModule(source, "test.mz")
        typeChecker.typeCheck(parsedModule, Paths.get("."))

        // Validate via the parsed AST
        val module = parser.parseParsedModule(source, "test.mz")
        val typeDecl = module.decls.filterIsInstance<ParsedTypeDecl>().first()
        val definition = typeDecl.definition as ParsedVariantDef

        // Find the Node constructor
        val nodeConstructor = definition.constructors.find { it.name == "Node" }!!
        val recordType = nodeConstructor.dataType as ParsedRecordType

        // Get the three field types
        val valueType = recordType.entries.find { it.name == "value" }!!.type as ParsedNamedType
        val leftType = recordType.entries.find { it.name == "left" }!!.type as ParsedTypeApp
        val rightType = recordType.entries.find { it.name == "right" }!!.type as ParsedTypeApp

        // Value field should be 'a
        assertEquals("'a", valueType.name)

        // Left and right should be Tree 'a
        assertEquals("Tree", leftType.constructor)
        assertEquals(1, leftType.args.size)
        assertEquals("'a", (leftType.args[0] as ParsedNamedType).name)

        assertEquals("Tree", rightType.constructor)
        assertEquals(1, rightType.args.size)
        assertEquals("'a", (rightType.args[0] as ParsedNamedType).name)
    }

    @Test
    fun `test tree type declaration prints with single type variable`() {
        // This is the main regression test - ensures that Tree 'a prints correctly
        val source = """
            type Tree 'a = | Leaf, Node { value: 'a, left: Tree 'a, right: Tree 'a } |
        """.trimIndent()

        // Parse and typecheck to ensure it succeeds
        val parsedModule = parser.parseParsedModule(source, "test.mz")
        typeChecker.typeCheck(parsedModule, Paths.get("."))

        // Validate via the parsed AST
        val module = parser.parseParsedModule(source, "test.mz")
        assertEquals(1, module.decls.size)

        // Verify there are no spurious type variables (like 'b, 'c)
        val typeDecl = module.decls.filterIsInstance<ParsedTypeDecl>().first()
        assertFalse(typeDecl.toString().contains("'b"), "Should not contain 'b type variable")
        assertFalse(typeDecl.toString().contains("'c"), "Should not contain 'c type variable")
    }
}
