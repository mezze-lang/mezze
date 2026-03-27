package com.mezze.language.typechecker

import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for shorthand field syntax in record literals: {x} as shorthand for {x = x}
 *
 * THE KEY REQUIREMENT: Shorthand fields must work with PURE TYPE INFERENCE without annotations.
 * When we write `let f = {tree} -> fold {tree, ...}`, the type of the `tree` parameter
 * should be inferred from `fold`'s parameter type, just as if we had written `{tree = tree}`.
 */
class ShorthandFieldInferenceTest {

    private fun parseAndTypeCheck(code: String): TypeCheckResult {
        val parser = MezzeParser()
        val ast = parser.parse(code)
        val typeChecker = TypeChecker()
        return typeChecker.typeCheck(ast)
    }

    private fun getVariableType(result: TypeCheckResult, varName: String): MezzeType? {
        return result.getVariableType(varName)
    }

    private fun assertTypeChecks(code: String, variableName: String, expectedType: String) {
        val result = parseAndTypeCheck(code)
        val actualType = getVariableType(result, variableName)
        assertNotNull(actualType, "Variable '$variableName' not found")
        assertEquals(expectedType, prettyPrintType(actualType))
    }

    private fun assertTypeContains(code: String, variableName: String, expectedSubstring: String) {
        val result = parseAndTypeCheck(code)
        val actualType = getVariableType(result, variableName)
        assertNotNull(actualType, "Variable '$variableName' not found")
        val typeStr = prettyPrintType(actualType)
        assertTrue(typeStr.contains(expectedSubstring), "Expected type to contain '$expectedSubstring' but got: $typeStr")
    }

    // ===== Basic Shorthand Tests (Pure Inference) =====

    @Test
    fun `test shorthand field with integer function`() {
        assertTypeChecks(
            """
            let f = {x} -> x + 1
            let result = {n} -> f {x = n}
            result
            """.trimIndent(),
            "result",
            "{ n: Int } -> Int"
        )
    }

    @Test
    fun `test shorthand with polymorphic identity function`() {
        assertTypeChecks(
            """
            let id = {x} -> x
            let useId = {value} -> id {x = value}
            useId
            """.trimIndent(),
            "useId",
            "{ value: 'a } -> 'a"
        )
    }

    @Test
    fun `test shorthand with multiple parameters`() {
        assertTypeChecks(
            """
            let add = {x, y} -> x + y
            let caller = {a, b} -> add {x = a, y = b}
            caller
            """.trimIndent(),
            "caller",
            "{ a: 'a, b: 'a } with NumAdd 'a -> 'a"
        )
    }

    // ===== Tree Fold - The Main Use Case =====

    @Test
    fun `test shorthand with Tree fold - sum function`() {
        // THE KEY TEST: {tree} shorthand should infer Tree variant type from fold WITHOUT annotations
        // Before the fix, this would infer { tree: 'a } -> Int (wrong!)
        // After the fix, it infers { tree: | Leaf, Node {...} | } -> Int (correct!)
        assertTypeContains(
            """
            type Tree 'a = | Leaf, Node { value: 'a, left: Tree 'a, right: Tree 'a } |

            let fold = {tree, acc, f} -> match tree is
              Leaf -> acc
              Node {value, left, right} -> do
                let lacc = fold {tree = left, acc, f}
                let vacc = f {acc = lacc, val = value}
                fold {tree = right, acc = vacc, f}

            let sum = {tree} -> fold {tree, acc = 0, f = {acc, val} -> acc + val}
            sum
            """.trimIndent(),
            "sum",
            "Leaf"  // Leaf is one of the Tree constructors
        )
    }

    @Test
    fun `test shorthand with Tree fold - size function`() {
        // THE KEY TEST: {tree} shorthand should infer Tree variant type from fold WITHOUT annotations
        assertTypeContains(
            """
            type Tree 'a = | Leaf, Node { value: 'a, left: Tree 'a, right: Tree 'a } |

            let fold = {tree, acc, f} -> match tree is
              Leaf -> acc
              Node {value, left, right} -> do
                let lacc = fold {tree = left, acc, f}
                let vacc = f {acc = lacc, val = value}
                fold {tree = right, acc = vacc, f}

            let size = {tree} -> fold {tree, acc = 0, f = {acc, val} -> acc + 1}
            size
            """.trimIndent(),
            "size",
            "Node"  // Node is the other Tree constructor
        )
    }

    // ===== Mixed Shorthand and Explicit Fields =====

    @Test
    fun `test mixed shorthand and explicit fields in same record`() {
        assertTypeContains(
            """
            type Tree 'a = | Leaf, Node { value: 'a, left: Tree 'a, right: Tree 'a } |

            let fold = {tree, acc, f} -> match tree is
              Leaf -> acc
              Node {value, left, right} -> fold {tree = right, acc = f {acc = fold {tree = left, acc, f}, val = value}, f}

            let sum = {tree} -> fold {
              tree,
              acc = 0,
              f = {acc, val} -> acc + val
            }
            sum
            """.trimIndent(),
            "sum",
            "Leaf"  // Should infer Tree variant type with Leaf constructor
        )
    }

    // ===== Partial Application =====

    @Test
    fun `test shorthand in partial application`() {
        assertTypeContains(
            """
            let add = {x, y} -> x + y
            let partialAdd = {n} -> add {x = n}
            partialAdd
            """.trimIndent(),
            "partialAdd",
            "{ n: 'a }"  // Check the key part of the type (ability constraints may vary)
        )
    }

    // ===== All Fields as Shorthand =====

    @Test
    fun `test all fields as shorthand`() {
        assertTypeChecks(
            """
            let triple = {x, y, z} -> x + y + z
            let allShorthand = {x, y, z} -> triple {x, y, z}
            allShorthand
            """.trimIndent(),
            "allShorthand",
            "{ x: 'a, y: 'a, z: 'a } with NumAdd 'a -> 'a"
        )
    }

    // ===== Tree Map Test =====

    @Test
    fun `test tree_map type inference`() {
        val code = """
            type Tree 'a = | Leaf, Node { value: 'a, left: Tree 'a, right: Tree 'a } |

            let tree_map = {tree, f} -> match tree is
              Leaf -> Leaf
              Node {value, left, right} -> Node {
                value = f {val = value},
                left = tree_map {tree = left, f},
                right = tree_map {tree = right, f}
              }
            tree_map
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        val actualType = getVariableType(result, "tree_map")
        assertNotNull(actualType, "Variable 'tree_map' not found")
        val typeStr = prettyPrintType(actualType)

        // Print the actual type for debugging
        println("tree_map type: $typeStr")

        // The type should be: { tree: Tree 'a, f: { val: 'a } -> 'b } -> Tree 'b
        // NOT: { tree: Tree 'a, f: { val: 'a } -> 'b } -> Tree 'd
        assertTrue(
            typeStr.contains("Tree 'b") && !typeStr.contains("Tree 'd") && !typeStr.contains("Tree 'e"),
            "Expected result type to be Tree 'b but got: $typeStr"
        )
    }
}
