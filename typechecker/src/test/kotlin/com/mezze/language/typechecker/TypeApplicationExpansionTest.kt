package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertContains

/**
 * Comprehensive tests for TypeApplication expansion and pattern matching with parametric types
 *
 * This test suite verifies the fixes for:
 * 1. TypeApplication expansion in ConstraintSolver (expandTypeApplication)
 * 2. Pattern matching type variable bug in inferVariantTypeFromPatterns
 *
 * Key scenarios covered:
 * - Parametric variant types (Tree 'a, Option 'a, Result 'a 'e)
 * - Type alias expansion with parameter substitution
 * - Pattern matching on parametric constructors
 * - Complex higher-order functions with parametric types
 * - Nested parametric types
 */
class TypeApplicationExpansionTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun parseAndTypeCheck(code: String): TypeCheckResult {
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        return typeChecker.typeCheck(parsedModule, Paths.get("."))
    }

    private fun assertTypeCheckFails(code: String, expectedMessagePart: String? = null): TypeCheckError {
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val error = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
        if (expectedMessagePart != null) {
            assertContains(error.message ?: "", expectedMessagePart, ignoreCase = true)
        }
        return error
    }

    // ==========================================================================
    // SECTION 1: Basic TypeApplication Expansion
    // ==========================================================================

    @Test
    fun `TypeApplication expansion - simple Option type`() {
        val code = """

            let unwrap_or = {opt, default} ->
                match opt is
                  Some { val } -> val
                  None -> default

            let example: Option Int = Some { val = 42 }
            let result = unwrap_or { opt = example, default = 0 }
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `TypeApplication expansion - Tree type with Int`() {
        val code = """
            type Tree 'a = | Leaf, Node { value: 'a, left: Tree 'a, right: Tree 'a } |

            let sum = {tree} -> match tree is
              Leaf -> 0
              Node {value, left, right} -> value + sum {tree = left} + sum {tree = right}

            let example: Tree Int = Node { value = 10, left = Leaf, right = Leaf }
            let result = sum {tree = example}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `TypeApplication expansion - Result type with two parameters`() {
        val code = """

            let is_ok = {result} ->
                match result is
                  Ok { val } -> True
                  Err { error } -> False

            let example: Result Int Str = Ok { val = 42 }
            let result = is_ok { result = example }
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    // ==========================================================================
    // SECTION 2: Pattern Matching with Parametric Types
    // ==========================================================================

    @Test
    fun `Pattern matching - Tree fold with correct field types`() {
        // This test verifies that pattern matching on Node {value, left, right}
        // correctly assigns distinct types to each field, not the same type variable
        val code = """
            type Tree 'a = | Leaf, Node { value: 'a, left: Tree 'a, right: Tree 'a } |

            let fold = {tree, acc, f} -> match tree is
              Leaf -> acc
              Node {value, left, right} -> fold {tree = right, acc = f {acc = fold {tree = left, acc, f}, val = value}, f}

            let sum = {tree} -> fold {tree, acc = 0, f = {acc, val} -> acc + val}

            let example = Node { value = 10, left = Leaf, right = Leaf }
            let result = sum {tree = example}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `Pattern matching - Tree with multiple operations`() {
        // This test verifies complex scenarios with multiple functions using pattern matching
        val code = """
            type Tree 'a = | Leaf, Node { value: 'a, left: Tree 'a, right: Tree 'a } |

            let fold = {tree, acc, f} -> match tree is
              Leaf -> acc
              Node {value, left, right} -> fold {tree = right, acc = f {acc = fold {tree = left, acc, f}, val = value}, f}

            let sum = {tree} -> fold {tree, acc = 0, f = {acc, val} -> acc + val}
            let size = {tree} -> fold {tree, acc = 0, f = {acc, val} -> acc + 1}
            let max_value = {tree} -> fold {tree, acc = 0, f = {acc, val} ->
                match acc > val is
                  True -> acc
                  False -> val
            }

            let example = Node {
              value = 10,
              left = Node { value = 5, left = Leaf, right = Leaf },
              right = Node { value = 15, left = Leaf, right = Leaf }
            }

            let r1 = sum {tree = example}
            let r2 = size {tree = example}
            let r3 = max_value {tree = example}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `Pattern matching - nested parametric types`() {
        val code = """
            type Tree 'a = | Leaf, Node { value: 'a, left: Tree 'a, right: Tree 'a } |

            let find_value = {tree, target} -> match tree is
              Leaf -> None
              Node {value, left, right} -> match value == target is
                True -> Some { val = value }
                False -> match find_value {tree = left, target} is
                  Some { val } -> Some { val }
                  None -> find_value {tree = right, target}

            let example = Node { value = 10, left = Leaf, right = Leaf }
            let result = find_value {tree = example, target = 10}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    // ==========================================================================
    // SECTION 3: Higher-Order Functions with Parametric Types
    // ==========================================================================

    @Test
    fun `Higher-order - tree map function`() {
        val code = """
            type Tree 'a = | Leaf, Node { value: 'a, left: Tree 'a, right: Tree 'a } |

            let tree_map = {tree, f} -> match tree is
              Leaf -> Leaf
              Node {value, left, right} -> Node {
                value = f {x = value},
                left = tree_map {tree = left, f},
                right = tree_map {tree = right, f}
              }

            let double = {x} -> x * 2
            let example = Node { value = 5, left = Leaf, right = Leaf }
            let result = tree_map {tree = example, f = double}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `Higher-order - tree filter with predicate`() {
        val code = """
            type Tree 'a = | Leaf, Node { value: 'a, left: Tree 'a, right: Tree 'a } |

            let tree_filter = {tree, pred} -> match tree is
              Leaf -> None
              Node {value, left, right} -> match pred {x = value} is
                True -> Some { val = value }
                False -> match tree_filter {tree = left, pred} is
                  Some { val } -> Some { val }
                  None -> tree_filter {tree = right, pred}

            let is_positive = {x} -> x > 0
            let example = Node { value = 10, left = Leaf, right = Leaf }
            let result = tree_filter {tree = example, pred = is_positive}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `Higher-order - fold with lambda`() {
        val code = """
            type Tree 'a = | Leaf, Node { value: 'a, left: Tree 'a, right: Tree 'a } |

            let fold = {tree, acc, f} -> match tree is
              Leaf -> acc
              Node {value, left, right} -> fold {tree = right, acc = f {acc = fold {tree = left, acc, f}, val = value}, f}

            let example = Node { value = 5, left = Leaf, right = Leaf }
            let result = fold {tree = example, acc = 0, f = {acc, val} -> acc + val * 2}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    // ==========================================================================
    // SECTION 4: Complex Constraint Solving
    // ==========================================================================

    @Test
    fun `Complex constraints - tree fold with conditional`() {
        val code = """
            type Tree 'a = | Leaf, Node { value: 'a, left: Tree 'a, right: Tree 'a } |

            let fold = {tree, acc, f} -> match tree is
              Leaf -> acc
              Node {value, left, right} -> fold {tree = right, acc = f {acc = fold {tree = left, acc, f}, val = value}, f}

            let conditional_sum = {tree, threshold} ->
                fold {tree, acc = 0, f = {acc, val} ->
                    match val > threshold is
                      True -> acc + val
                      False -> acc
                }

            let example = Node {
              value = 15,
              left = Node { value = 5, left = Leaf, right = Leaf },
              right = Node { value = 20, left = Leaf, right = Leaf }
            }
            let result = conditional_sum {tree = example, threshold = 10}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `Complex constraints - multiple type parameters in use`() {
        val code = """
            type Pair 'a 'b = | Pair { first: 'a, second: 'b } |

            let swap_if_ok = {result} -> match result is
              Ok { val = Pair { first, second } } -> Ok { val = Pair { first = second, second = first } }
              Err { error } -> Err { error }

            let example = Ok { val = Pair { first = 42, second = "hello" } }
            let result = swap_if_ok { result = example }
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `Complex constraints - recursive type with abilities`() {
        val code = """
            type Tree 'a = | Leaf, Node { value: 'a, left: Tree 'a, right: Tree 'a } |

            let fold = {tree, acc, f} -> match tree is
              Leaf -> acc
              Node {value, left, right} -> fold {tree = right, acc = f {acc = fold {tree = left, acc, f}, val = value}, f}

            let sum = {tree} -> fold {tree, acc = 0, f = {acc, val} -> acc + val}
            let product = {tree} -> fold {tree, acc = 1, f = {acc, val} -> acc * val}

            let example = Node { value = 5, left = Leaf, right = Leaf }
            let r1 = sum {tree = example}
            let r2 = product {tree = example}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    // ==========================================================================
    // SECTION 5: Edge Cases and Regression Tests
    // ==========================================================================

    @Test
    fun `Edge case - deeply nested parametric types`() {
        val code = """
            type Tree 'a = | Leaf, Node { value: 'a, left: Tree 'a, right: Tree 'a } |

            let get_value = {tree} -> match tree is
              Leaf -> None
              Node {value, left, right} -> Some { val = value }

            let example: Tree (Option Int) = Node {
              value = Some { val = 42 },
              left = Leaf,
              right = Leaf
            }
            let result = get_value {tree = example}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `Edge case - parametric type with unit constructor`() {
        val code = """

            let is_none = {opt} -> match opt is
              None -> True
              Some { val } -> False

            let example: Option Int = None
            let result = is_none {opt = example}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `Regression - Tree Int should not require NumAdd ability`() {
        // This is the original bug that was fixed:
        // Tree Int was incorrectly being asked to support NumAdd
        // because pattern matching assigned the same type variable to all fields
        val code = """
            type Tree 'a = | Leaf, Node { value: 'a, left: Tree 'a, right: Tree 'a } |

            let fold = {tree, acc, f} -> match tree is
              Leaf -> acc
              Node {value, left, right} -> fold {tree = right, acc = f {acc = fold {tree = left, acc, f}, val = value}, f}

            let sum = {tree} -> fold {tree, acc = 0, f = {acc, val} -> acc + val}

            let example = Node { value = 10, left = Leaf, right = Leaf }
            let result = sum {tree = example}
        """.trimIndent()

        // This should succeed without errors
        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `Regression - pattern matching field types are distinct`() {
        val code = """
            type Tree 'a = | Leaf, Node { value: 'a, left: Tree 'a, right: Tree 'a } |

            let test = {tree} -> match tree is
              Node {value, left, right} -> value + 0
              Leaf -> 0

            let example = Node { value = 5, left = Leaf, right = Leaf }
            let result = test {tree = example}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    // ==========================================================================
    // SECTION 6: Error Cases
    // ==========================================================================

    @Test
    fun `Error - mismatched type parameter in pattern`() {
        val code = """
            type Tree 'a = | Leaf, Node { value: 'a, left: Tree 'a, right: Tree 'a } |

            let bad = {tree: Tree Int} -> match tree is
              Leaf -> "empty"
              Node {value, left, right} -> value
        """.trimIndent()

        // value is Int, but returning it where String is expected (from Leaf branch)
        assertTypeCheckFails(code)
    }

    @Test
    fun `Error - type mismatch in parametric constructor`() {
        // Under the class-parameter NumAdd ability, `val + "hello"` on an
        // unconstrained `val: 'a` does not fail at the DEFINITION — `bad`
        // generalizes to a polymorphic fn carrying `NumAdd Str 'a` (the obligation
        // defers to the call site, the correct more-general behavior). The
        // invariant "you can't add a Str to the payload" is enforced when `bad` is
        // instantiated with a concrete payload: `Some { val = 5 }` needs
        // `NumAdd Str Int`, which has no impl. Call it so the mismatch surfaces.
        val code = """

            let bad = {opt} -> match opt is
              Some { val } -> val + "hello"
              None -> 0
            bad { opt = Some { val = 5 } }
        """.trimIndent()

        assertTypeCheckFails(code)
    }

    // ==========================================================================
    // SECTION 7: Multi-Parameter Type Aliases
    // ==========================================================================

    @Test
    fun `Multi-param - Either type with left and right`() {
        val code = """
            type Either 'left 'right = | Left { val: 'left }, Right { val: 'right } |

            let is_left = {either} -> match either is
              Left { val } -> True
              Right { val } -> False

            let example: Either Int Str = Left { val = 42 }
            let result = is_left {either = example}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `Multi-param - map Either values`() {
        val code = """
            type Either 'left 'right = | Left { val: 'left }, Right { val: 'right } |

            let map_left = {either, f} -> match either is
              Left { val } -> Left { val = f {x = val} }
              Right { val } -> Right { val }

            let double = {x} -> x * 2
            let example: Either Int Str = Left { val = 21 }
            let result = map_left {either = example, f = double}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `Multi-param - swap Either sides`() {
        val code = """
            type Either 'left 'right = | Left { val: 'left }, Right { val: 'right } |

            let swap = {either} -> match either is
              Left { val } -> Right { val }
              Right { val } -> Left { val }

            let example: Either Int Str = Left { val = 42 }
            let result = swap {either = example}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }
}
