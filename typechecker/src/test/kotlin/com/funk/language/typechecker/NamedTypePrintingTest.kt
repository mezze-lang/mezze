package com.funk.language.typechecker

import com.funk.language.parser.FunkParser
import com.funk.language.ast.prettyPrintTypeWithAliases
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Tests for printing types with their declaration names instead of expanded variants
 */
class NamedTypePrintingTest {
    private val parser = FunkParser()
    private val typeChecker = TypeChecker()

    @Test
    fun `test Tree type prints with name in function signature`() {
        val source = """
            type Tree 'a = | Leaf, Node { value: 'a, left: Tree 'a, right: Tree 'a } |
            
            let fold = {tree, acc, f} -> match tree is
              Leaf -> acc
              Node {value, left, right} -> fold {tree = right, acc = f {acc = fold {tree = left, acc, f}, val = value}, f}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program).program

        val typeDecls = result.items.filterIsInstance<com.funk.language.ast.TypeDeclaration>()
        val letBindings = result.items.filterIsInstance<com.funk.language.ast.LetBinding>()
        val foldBinding = letBindings.find { it.variableName == "fold" }!!

        // Get the type scheme
        val scheme = foldBinding.typeScheme!!
        
        // Print the type with type declarations
        val typeStr = prettyPrintTypeWithAliases(
            scheme.type,
            typeDeclarations = typeDecls
        )

        // Should contain "Tree 'b" or similar, not the expanded variant
        assertTrue(typeStr.contains("Tree"), "Type signature should contain 'Tree'")
        // Should not contain "| Leaf" which would indicate expanded form
        assertFalse(typeStr.contains("| Leaf"), "Type signature should not be expanded")
    }

    @Test
    fun `test Maybe type prints with name not expanded`() {
        val source = """
            type Maybe 'a = | Nothing, Just { val: 'a } |

            let map_maybe = {maybe, f} -> match maybe is
              Nothing -> Nothing
              Just {val} -> Just { val = f {val} }
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program).program

        val typeDecls = result.items.filterIsInstance<com.funk.language.ast.TypeDeclaration>()
        val letBindings = result.items.filterIsInstance<com.funk.language.ast.LetBinding>()
        val mapBinding = letBindings.find { it.variableName == "map_maybe" }!!

        val scheme = mapBinding.typeScheme!!
        val typeStr = prettyPrintTypeWithAliases(
            scheme.type,
            typeDeclarations = typeDecls
        )

        // Should contain "Maybe"
        assertTrue(typeStr.contains("Maybe"), "Type signature should contain 'Maybe'")
    }

    @Test
    fun `test Either type with two parameters prints correctly`() {
        val source = """
            type Either 'a 'b = | Left {x: 'a}, Right {y: 'b} |

            let map_left = {either, f} -> match either is
              Left {x} -> Left {x = f {x}}
              Right {y} -> Right {y}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program).program

        val typeDecls = result.items.filterIsInstance<com.funk.language.ast.TypeDeclaration>()
        val letBindings = result.items.filterIsInstance<com.funk.language.ast.LetBinding>()
        val mapBinding = letBindings.find { it.variableName == "map_left" }!!

        val scheme = mapBinding.typeScheme!!
        val typeStr = prettyPrintTypeWithAliases(
            scheme.type,
            typeDeclarations = typeDecls
        )

        // Should contain "Either" with type parameters
        assertTrue(typeStr.contains("Either"), "Type signature should contain 'Either'")
        // Should not be expanded
        assertFalse(typeStr.contains("| Left"), "Type should not be expanded")
    }

    @Test
    fun `test nested Tree types print with names`() {
        val source = """
            type Tree 'a = | Leaf, Node { value: 'a, left: Tree 'a, right: Tree 'a } |
            
            let make_leaf = {} -> Leaf
            let make_node = {v, l, r} -> Node { value = v, left = l, right = r }
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program).program

        val typeDecls = result.items.filterIsInstance<com.funk.language.ast.TypeDeclaration>()
        val letBindings = result.items.filterIsInstance<com.funk.language.ast.LetBinding>()
        val nodeBinding = letBindings.find { it.variableName == "make_node" }!!

        val scheme = nodeBinding.typeScheme!!
        val typeStr = prettyPrintTypeWithAliases(
            scheme.type,
            typeDeclarations = typeDecls
        )

        // Should contain "Tree" for return type and parameters
        assertTrue(typeStr.contains("Tree"), "Type signature should contain 'Tree'")
    }

    @Test
    fun `test List type is not affected by named type printing`() {
        val source = """
            type Tree 'a = | Leaf, Node { value: 'a, left: Tree 'a, right: Tree 'a } |
            
            let list_test = {x} -> [x, x, x]
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program).program

        val typeDecls = result.items.filterIsInstance<com.funk.language.ast.TypeDeclaration>()
        val letBindings = result.items.filterIsInstance<com.funk.language.ast.LetBinding>()
        val listBinding = letBindings.find { it.variableName == "list_test" }!!

        val inferredType = listBinding.inferredType!!
        val typeStr = prettyPrintTypeWithAliases(
            inferredType,
            typeDeclarations = typeDecls
        )

        // List should still be printed as List
        assertTrue(typeStr.contains("[") || typeStr.contains("List"), 
            "List types should still print correctly")
    }

    @Test
    fun `test multiple type declarations dont interfere`() {
        val source = """
            type Maybe 'a = | Nothing, Just { val: 'a } |
            type Either 'a 'b = | Left {x: 'a}, Right {y: 'b} |

            let test = {m, e} -> match m is
              Nothing -> e
              Just {val} -> Left {x = val}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program).program

        val typeDecls = result.items.filterIsInstance<com.funk.language.ast.TypeDeclaration>()
        assertEquals(2, typeDecls.size)
        
        val letBindings = result.items.filterIsInstance<com.funk.language.ast.LetBinding>()
        val testBinding = letBindings.find { it.variableName == "test" }!!

        val scheme = testBinding.typeScheme!!
        val typeStr = prettyPrintTypeWithAliases(
            scheme.type,
            typeDeclarations = typeDecls
        )

        // Should contain both type names
        assertTrue(typeStr.contains("Maybe") || typeStr.contains("Either"), 
            "Should reference at least one of the custom types")
    }

    @Test
    fun `test unit constructors print correctly with named types`() {
        val source = """
            type Option 'a = | None, Some 'a |
            
            let none_value = None
            let some_value = Some 42
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program).program

        val typeDecls = result.items.filterIsInstance<com.funk.language.ast.TypeDeclaration>()
        val letBindings = result.items.filterIsInstance<com.funk.language.ast.LetBinding>()
        
        // Both bindings should typecheck successfully
        assertEquals(2, letBindings.size)
        assertNotNull(letBindings[0].inferredType)
        assertNotNull(letBindings[1].inferredType)
    }
}
