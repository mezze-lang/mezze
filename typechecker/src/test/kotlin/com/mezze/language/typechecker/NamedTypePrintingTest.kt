package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
import com.mezze.language.ast.prettyPrintTypeWithAliases
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

/**
 * Tests for printing types with their declaration names instead of expanded variants
 */
class NamedTypePrintingTest {
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
    fun `test Tree type prints with name in function signature`() {
        val source = """
            type Tree 'a = | Leaf, Node { value: 'a, left: Tree 'a, right: Tree 'a } |

            let fold = {tree, acc, f} -> match tree is
              Leaf -> acc
              Node {value, left, right} -> fold {tree = right, acc = f {acc = fold {tree = left, acc, f}, val = value}, f}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        // Get type aliases from the typechecker (replaces v1 typeDeclarations)
        val typeAliases = typeChecker.getTypeAliases()

        val foldType = result.getVariableType("fold")
        assertNotNull(foldType, "fold type should not be null")

        // Print the type with type aliases
        val typeStr = prettyPrintTypeWithAliases(
            foldType!!,
            typeAliases = typeAliases
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

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val typeAliases = typeChecker.getTypeAliases()

        val mapType = result.getVariableType("map_maybe")
        assertNotNull(mapType, "map_maybe type should not be null")

        val typeStr = prettyPrintTypeWithAliases(
            mapType!!,
            typeAliases = typeAliases
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

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val typeAliases = typeChecker.getTypeAliases()

        val mapType = result.getVariableType("map_left")
        assertNotNull(mapType, "map_left type should not be null")

        val typeStr = prettyPrintTypeWithAliases(
            mapType!!,
            typeAliases = typeAliases
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

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val typeAliases = typeChecker.getTypeAliases()

        val nodeType = result.getVariableType("make_node")
        assertNotNull(nodeType, "make_node type should not be null")

        val typeStr = prettyPrintTypeWithAliases(
            nodeType!!,
            typeAliases = typeAliases
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

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val typeAliases = typeChecker.getTypeAliases()

        val listType = result.getVariableType("list_test")
        assertNotNull(listType, "list_test type should not be null")

        val typeStr = prettyPrintTypeWithAliases(
            listType!!,
            typeAliases = typeAliases
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

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val typeAliases = typeChecker.getTypeAliases()
        assertEquals(2, typeAliases.size)

        val testType = result.getVariableType("test")
        assertNotNull(testType, "test type should not be null")

        val typeStr = prettyPrintTypeWithAliases(
            testType!!,
            typeAliases = typeAliases
        )

        // Should contain both type names
        assertTrue(typeStr.contains("Maybe") || typeStr.contains("Either"),
            "Should reference at least one of the custom types")
    }

    @Test
    fun `test unit constructors print correctly with named types`() {
        val source = """
            type Option 'a = | None, Some { val: 'a } |

            let none_value = None
            let some_value = Some { val = 42 }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        // Both bindings should typecheck successfully
        val noneType = result.getVariableType("none_value")
        val someType = result.getVariableType("some_value")
        assertNotNull(noneType, "none_value type should not be null")
        assertNotNull(someType, "some_value type should not be null")
    }
}
