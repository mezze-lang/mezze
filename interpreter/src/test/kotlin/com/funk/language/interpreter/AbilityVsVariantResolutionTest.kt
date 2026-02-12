package com.funk.language.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import com.funk.language.parser.FunkParser
import com.funk.language.typechecker.TypeChecker
import java.nio.file.Paths

/**
 * Comprehensive tests for distinguishing between abilities, effects, variants, and handlers.
 *
 * CORE ISSUE:
 * The parser treats ALL uppercase identifiers as variant constructors because they are
 * syntactically identical at parse time:
 *   - Variant constructor: Some { val = 42 }
 *   - Ability reference: Inspect
 *   - Effect reference: Log
 *   - Handler/Performer: Console
 *
 * The parser creates VariantConstruction AST nodes for all of them, and the typechecker
 * sets the `resolution` field to distinguish them. The interpreter then uses this field.
 *
 * These tests verify that each type of construct works correctly at runtime.
 */
class AbilityVsVariantResolutionTest {
    private lateinit var parser: FunkParser
    private lateinit var typeChecker: TypeChecker
    private lateinit var interpreter: FunkInterpreter

    @BeforeEach
    fun setup() {
        // Set project root for stdlib module resolution
        System.setProperty("funk.project.root", "/Users/shriyans/Projects/funk")
        com.funk.language.typechecker.ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = FunkParser()
        typeChecker = TypeChecker()
        interpreter = FunkInterpreter()
    }

    private fun executeProgram(source: String): FunkValue {
        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val projectRoot = Paths.get(System.getProperty("funk.project.root", "."))
        return FunkTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, projectRoot)
    }

    // ============================================================================
    // BUILTIN ABILITIES
    // ============================================================================

    @Test
    fun `test builtin ability Inspect works without import`() {
        val source = """
            let test_val = 42
            let main = {} -> do
                let str = Inspect.to_str {val = test_val}
                {}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertDoesNotThrow {
            interpreter.execute(typedProgram)
        }
    }

    @Test
    fun `test builtin ability Inspect on custom types`() {
        val source = """
            type Tree 'a = | Leaf, Node { value: 'a, left: Tree 'a, right: Tree 'a } |

            let tree = Node {
                value = 10,
                left = Leaf,
                right = Leaf
            }

            let main = {} -> do
                let str = Inspect.to_str {val = tree}
                {}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertDoesNotThrow {
            interpreter.execute(typedProgram)
        }
    }

    @Test
    fun `test builtin ability on nested structures`() {
        val source = """
            type Maybe 'a = | Nothing, Just { val: 'a } |

            let nested = Just { val = Just { val = 42 } }

            let main = {} -> do
                let str = Inspect.to_str {val = nested}
                {}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertDoesNotThrow {
            interpreter.execute(typedProgram)
        }
    }

    @Test
    fun `test builtin ability on records`() {
        val source = """
            let person = { name = "Alice", age = 30 }

            let main = {} -> do
                let str = Inspect.to_str {val = person}
                {}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertDoesNotThrow {
            interpreter.execute(typedProgram)
        }
    }

    // ============================================================================
    // VARIANT CONSTRUCTORS
    // ============================================================================

    @Test
    fun `test variant constructor vs ability - no confusion`() {
        val source = """
            # Define a variant that happens to have an uppercase name
            type Result 'a 'e = | Ok { val: 'a }, Err { error: 'e } |

            let success = Ok { val = 42 }
            let failure = Err { error = "oops" }

            let main = {} -> do
                # Variant construction should work
                let str1 = Inspect.to_str {val = success}
                let str2 = Inspect.to_str {val = failure}
                # Ability member access should work
                let str3 = Inspect.to_str {val = 100}
                {}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertDoesNotThrow {
            interpreter.execute(typedProgram)
        }
    }

    @Test
    fun `test unit variant constructors`() {
        val source = """
            type Option 'a = | None, Some { val: 'a } |

            let nothing = None
            let something = Some { val = 42 }

            let main = {} -> do
                let str1 = Inspect.to_str {val = nothing}
                let str2 = Inspect.to_str {val = something}
                {}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertDoesNotThrow {
            interpreter.execute(typedProgram)
        }
    }

    // ============================================================================
    // ABILITIES VS EFFECTS
    // ============================================================================

    @Test
    fun `test ability member access works without handler`() {
        val source = """
            # NO perform, NO handler - ability should still work
            let main = {} -> do
                let str = Inspect.to_str {val = 42}
                {}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertDoesNotThrow {
            interpreter.execute(typedProgram)
        }
    }

    @Test
    fun `test effect member access works with handler from stdlib`() {
        val source = """
            use std::effects::log::{Log, Console}

            let main = {} -> perform Console in do
                Log.println {msg = "Hello from test"}
                Log.info {msg = "Info message"}
                Log.warn {msg = "Warning message"}
                {}
        """.trimIndent()

        assertDoesNotThrow {
            executeProgram(source)
        }
    }

    @Test
    fun `test ability and effect together with stdlib`() {
        val source = """
            use std::effects::log::{Log, Console}

            type Tree 'a = | Leaf, Node { value: 'a, left: Tree 'a, right: Tree 'a } |

            let tree = Node {
                value = 10,
                left = Leaf,
                right = Leaf
            }

            let main = {} -> perform Console in do
                Log.println {msg = "Tree: $(Inspect.to_str {val = tree})"}
                {}
        """.trimIndent()

        assertDoesNotThrow {
            executeProgram(source)
        }
    }

    // ============================================================================
    // COMPLEX SCENARIOS
    // ============================================================================

    @Test
    fun `test nested member access`() {
        val source = """
            type Tree 'a = | Leaf, Node { value: 'a, left: Tree 'a, right: Tree 'a } |

            let small_tree = Node {
                value = 5,
                left = Node { value = 3, left = Leaf, right = Leaf },
                right = Node { value = 7, left = Leaf, right = Leaf }
            }

            let main = {} -> do
                let str = Inspect.to_str {val = small_tree}
                {}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertDoesNotThrow {
            interpreter.execute(typedProgram)
        }
    }

    @Test
    fun `test ability in function argument`() {
        val source = """
            let show = {val} -> Inspect.to_str {val = val}

            let main = {} -> do
                let result = show {val = [1, 2, 3]}
                {}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertDoesNotThrow {
            interpreter.execute(typedProgram)
        }
    }

    @Test
    fun `test ability in lambda with map`() {
        val source = """
            let numbers = [1, 2, 3, 4, 5]
            let to_strings = {xs} -> match xs is
                [] -> []
                [x, ...rest] -> [...to_strings {xs = rest}, Inspect.to_str {val = x}]

            let main = {} -> do
                let strings = to_strings {xs = numbers}
                {}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertDoesNotThrow {
            interpreter.execute(typedProgram)
        }
    }

    @Test
    fun `test ability with recursive tree structure`() {
        val source = """
            type MyTree 'a = | MyLeaf, MyNode { value: 'a, left: MyTree 'a, right: MyTree 'a } |

            let tree = MyNode {
                value = 1,
                left = MyNode {
                    value = 2,
                    left = MyLeaf,
                    right = MyLeaf
                },
                right = MyNode {
                    value = 3,
                    left = MyLeaf,
                    right = MyLeaf
                }
            }

            let main = {} -> do
                let str = Inspect.to_str {val = tree}
                {}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertDoesNotThrow {
            interpreter.execute(typedProgram)
        }
    }

    @Test
    fun `test ability with simple variant`() {
        val source = """
            type Color = | Red, Green, Blue |

            let color = Red

            let main = {} -> do
                let str = Inspect.to_str {val = color}
                {}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertDoesNotThrow {
            interpreter.execute(typedProgram)
        }
    }

    @Test
    fun `test ability with recursive linked list`() {
        val source = """
            type MyList 'a = | MyNil, MyCons { head: 'a, tail: MyList 'a } |

            let list = MyCons {
                head = 1,
                tail = MyCons {
                    head = 2,
                    tail = MyCons {
                        head = 3,
                        tail = MyNil
                    }
                }
            }

            let main = {} -> do
                let str = Inspect.to_str {val = list}
                {}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertDoesNotThrow {
            interpreter.execute(typedProgram)
        }
    }

    // ============================================================================
    // EDGE CASES
    // ============================================================================

    @Test
    fun `test ability on arrays`() {
        val source = """
            let arr = [1, 2, 3, 4, 5]

            let main = {} -> do
                let str = Inspect.to_str {val = arr}
                {}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertDoesNotThrow {
            interpreter.execute(typedProgram)
        }
    }

    @Test
    fun `test ability on strings`() {
        val source = """
            let str = "Hello, World!"

            let main = {} -> do
                let result = Inspect.to_str {val = str}
                {}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertDoesNotThrow {
            interpreter.execute(typedProgram)
        }
    }

    @Test
    fun `test ability on booleans`() {
        val source = """
            let main = {} -> do
                let str1 = Inspect.to_str {val = True}
                let str2 = Inspect.to_str {val = False}
                {}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertDoesNotThrow {
            interpreter.execute(typedProgram)
        }
    }

    @Test
    fun `test ability on unit type`() {
        val source = """
            let unit = {}

            let main = {} -> do
                let str = Inspect.to_str {val = unit}
                {}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertDoesNotThrow {
            interpreter.execute(typedProgram)
        }
    }

    // ============================================================================
    // PERFORMANCE TESTS
    // ============================================================================

    @Test
    fun `test ability on deeply nested structures`() {
        val source = """
            type Tree = | Leaf, Branch { left: Tree, right: Tree } |

            # Create a deep tree: depth 5 = 31 nodes
            let deep = Branch {
                left = Branch {
                    left = Branch {
                        left = Branch {
                            left = Leaf,
                            right = Leaf
                        },
                        right = Branch {
                            left = Leaf,
                            right = Leaf
                        }
                    },
                    right = Branch {
                        left = Branch {
                            left = Leaf,
                            right = Leaf
                        },
                        right = Branch {
                            left = Leaf,
                            right = Leaf
                        }
                    }
                },
                right = Branch {
                    left = Branch {
                        left = Branch {
                            left = Leaf,
                            right = Leaf
                        },
                        right = Branch {
                            left = Leaf,
                            right = Leaf
                        }
                    },
                    right = Branch {
                        left = Branch {
                            left = Leaf,
                            right = Leaf
                        },
                        right = Branch {
                            left = Leaf,
                            right = Leaf
                        }
                    }
                }
            }

            let main = {} -> do
                let str = Inspect.to_str {val = deep}
                {}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertDoesNotThrow {
            interpreter.execute(typedProgram)
        }
    }
}
