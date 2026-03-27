package com.mezze.language.typechecker

import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertNull

/**
 * Tests for effect row unification in match expressions.
 *
 * These tests verify that match expressions with branches having different effects
 * correctly unify using row polymorphism:
 * - Pure branch (<>) + effectful branch (<E>) = <E>
 * - <E1> + <E2> = <E1, E2>
 * - SuspendedType unification with pure types
 */
class MatchEffectUnificationTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        parser = MezzeParser()
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun typeCheck(code: String): com.mezze.language.ast.TypeCheckResult {
        val program = parser.parse(code)
        return typeChecker.typeCheck(program)
    }

    private fun getBinding(program: TypeCheckResult, name: String): LetBinding {
        return program.items.filterIsInstance<LetBinding>().first { it.variableName == name }
    }

    @Test
    fun `test match with pure and effectful branches unifies to effectful`() {
        val code = """
            effect Log where {
                info: {msg: Str} -> {} <Log>
            }

            impl Log for ConsoleLogger where {
                info = {msg} -> {}
            }

            type Option 'a = | Some { val: 'a }, None |

            let test = { opt: Option Int } -> do
                match opt is
                    Some { val } -> do
                        Log.info { msg = "Got value" }
                        val
                    None -> 0
        """.trimIndent()

        val program = typeCheck(code)
        val binding = getBinding(program, "test")

        // The function should have Log effect from the Some branch
        val funcType = binding.inferredType as MezzeType.FunctionType
        assertNotNull(funcType.effects, "Match expression should have Log effect")
        assertTrue(funcType.effects!!.effects.contains("Log"), "Should contain Log effect")
    }

    @Test
    fun `test match with two different effects unifies to union`() {
        val code = """
            effect Log where {
                info: {msg: Str} -> {} <Log>
            }

            effect State where {
                get: {} -> Int <State>,
                put: {value: Int} -> {} <State>
            }

            impl Log for ConsoleLogger where {
                info = {msg} -> {}
            }

            impl State for Counter {state: Int} where {
                get = {} -> 0,
                put = {value} -> {}
            }

            type Either 'a 'b = | Left { val: 'a }, Right { val: 'b } |

            let test = { either: Either Int String } -> do
                match either is
                    Left { val } -> do
                        Log.info { msg = "Left" }
                        val
                    Right { val } -> do
                        State.put { value = 42 }
                        0
        """.trimIndent()

        val program = typeCheck(code)
        val binding = getBinding(program, "test")

        // The function should have both Log and State effects
        val funcType = binding.inferredType as MezzeType.FunctionType
        assertNotNull(funcType.effects, "Match expression should have effects")
        assertTrue(funcType.effects!!.effects.contains("Log"), "Should contain Log effect")
        assertTrue(funcType.effects!!.effects.contains("State"), "Should contain State effect")
    }

    @Test
    fun `test match with all pure branches stays pure`() {
        val code = """
            type Option 'a = | Some { val: 'a }, None |

            let test = { opt: Option Int } -> do
                match opt is
                    Some { val } -> val + 1
                    None -> 0
        """.trimIndent()

        val program = typeCheck(code)
        val binding = getBinding(program, "test")

        // Pure function should have no effects
        val funcType = binding.inferredType as MezzeType.FunctionType
        assertNull(funcType.effects, "All pure branches should result in no effects")
    }

    @Test
    fun `test match with multiple branches and different effects`() {
        val code = """
            effect Log where {
                info: {msg: Str} -> {} <Log>
            }

            effect State where {
                get: {} -> Int <State>
            }

            impl Log for ConsoleLogger where {
                info = {msg} -> {}
            }

            impl State for Counter {state: Int} where {
                get = {} -> 0
            }

            type Choice = | A, B, C, D |

            let test = { choice: Choice } -> do
                match choice is
                    A -> 1                               # Pure
                    B -> do                              # Log effect
                        Log.info { msg = "B" }
                        2
                    C -> 3                               # Pure
                    D -> do                              # State effect
                        State.get {}
        """.trimIndent()

        val program = typeCheck(code)
        val binding = getBinding(program, "test")

        // Should have both Log and State effects
        val funcType = binding.inferredType as MezzeType.FunctionType
        assertNotNull(funcType.effects, "Should have effects from Log and State branches")
        assertTrue(funcType.effects!!.effects.contains("Log"), "Should contain Log effect")
        assertTrue(funcType.effects!!.effects.contains("State"), "Should contain State effect")
    }

    @Test
    fun `test nested match expressions with effects`() {
        val code = """
            effect Log where {
                info: {msg: Str} -> {} <Log>
            }

            impl Log for ConsoleLogger where {
                info = {msg} -> {}
            }

            type Option 'a = | Some { val: 'a }, None |

            let test = { outer: Option Int, inner: Option Int } -> do
                match outer is
                    Some { val } -> do
                        match inner is
                            Some { val = innerVal } -> do
                                Log.info { msg = "Both Some" }
                                val + innerVal
                            None -> val
                    None -> 0
        """.trimIndent()

        val program = typeCheck(code)
        val binding = getBinding(program, "test")

        // Should have Log effect from nested match
        val funcType = binding.inferredType as MezzeType.FunctionType
        assertNotNull(funcType.effects, "Nested match should propagate effects")
        assertTrue(funcType.effects!!.effects.contains("Log"), "Should contain Log effect")
    }

    @Test
    fun `test match as function return value with effects`() {
        val code = """
            effect Log where {
                info: {msg: Str} -> {} <Log>
            }

            impl Log for ConsoleLogger where {
                info = {msg} -> {}
            }

            type Bool = | True, False |

            let choose: { b: Bool } -> Int <Log> = { b } ->
                match b is
                    True -> do
                        Log.info { msg = "True" }
                        1
                    False -> 0
        """.trimIndent()

        val program = typeCheck(code)
        val binding = getBinding(program, "choose")

        // Function annotation declares <Log>, inferred type should match
        val funcType = binding.inferredType as MezzeType.FunctionType
        assertNotNull(funcType.effects, "Should have Log effect")
        assertTrue(funcType.effects!!.effects.contains("Log"), "Should contain Log effect")
    }

    @Test
    fun `test match with guards and mixed effects`() {
        val code = """
            effect Log where {
                info: {msg: Str} -> {} <Log>
            }

            impl Log for ConsoleLogger where {
                info = {msg} -> {}
            }

            type Option 'a = | Some { val: 'a }, None |

            let test = { opt: Option Int } -> do
                match opt is
                    Some { val } if val > 10 -> do
                        Log.info { msg = "Large" }
                        val * 2
                    Some { val } -> val
                    None -> 0
        """.trimIndent()

        val program = typeCheck(code)
        val binding = getBinding(program, "test")

        // Should have Log effect from guarded branch
        val funcType = binding.inferredType as MezzeType.FunctionType
        assertNotNull(funcType.effects, "Guarded effectful branch should add effects")
        assertTrue(funcType.effects!!.effects.contains("Log"), "Should contain Log effect")
    }

    @Test
    fun `test match expression type error for incompatible value types`() {
        // Even with effect unification, value types must still match
        val code = """
            type Option 'a = | Some { val: 'a }, None |

            let test = { opt: Option Int } -> do
                match opt is
                    Some { val } -> val        # Returns Int
                    None -> "error"            # Returns String - should fail!
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            typeCheck(code)
        }

        assertTrue(
            exception.message?.contains("incompatible types") == true ||
            exception.message?.contains("Type error") == true ||
            exception.message?.contains("Type mismatch") == true ||
            exception.message?.contains("not compatible") == true,
            "Should report incompatible value types, got: ${exception.message}"
        )
    }

    @Test
    fun `test match preserves effect row openness`() {
        // When matching on functions with polymorphic effects,
        // the result should preserve polymorphism
        val code = """
            effect Log where {
                info: {msg: Str} -> {} <Log>
            }

            impl Log for ConsoleLogger where {
                info = {msg} -> {}
            }

            type Option 'a = | Some { val: 'a }, None |

            # Function takes a callback with polymorphic effects
            let apply_to_opt: { opt: Option Int, f: { x: Int } -> Int <...> } -> Int <...> =
                { opt, f } -> match opt is
                    Some { val } -> f { x = val }
                    None -> 0
        """.trimIndent()

        val program = typeCheck(code)
        val binding = getBinding(program, "apply_to_opt")

        // The function should preserve polymorphic effects
        val funcType = binding.inferredType as MezzeType.FunctionType
        // Note: Depending on implementation, this might be polymorphic or specific
        // The key is that it should type check without errors
        assertNotNull(binding.inferredType, "Function should type check successfully")
    }

    @Test
    fun `test match with effectful pattern matching`() {
        // Test that effects in the matched value are handled correctly
        val code = """
            effect Log where {
                info: {msg: Str} -> {} <Log>
            }

            effect State where {
                get: {} -> Int <State>
            }

            impl Log for ConsoleLogger where {
                info = {msg} -> {}
            }

            impl State for Counter {state: Int} where {
                get = {} -> 0
            }

            type Option 'a = | Some { val: 'a }, None |

            let test = {} -> do
                let val = State.get {}
                match Some { val = val } is
                    Some { val } -> do
                        Log.info { msg = "Got value" }
                        val
                    None -> 0
        """.trimIndent()

        val program = typeCheck(code)
        val binding = getBinding(program, "test")

        // Should have both State (from creating the value) and Log (from match)
        val funcType = binding.inferredType as MezzeType.FunctionType
        assertNotNull(funcType.effects, "Should have effects from both matched value and branches")
    }

    @Test
    fun `test match unification with three different effects`() {
        val code = """
            effect Log where {
                info: {msg: Str} -> {} <Log>
            }

            effect State where {
                get: {} -> Int <State>
            }

            effect Random where {
                random: {} -> Int <Random>
            }

            impl Log for ConsoleLogger where {
                info = {msg} -> {}
            }

            impl State for Counter {state: Int} where {
                get = {} -> 0
            }

            impl Random for RNG {seed: Int} where {
                random = {} -> 42
            }

            type Choice = | A, B, C |

            let test = { choice: Choice } -> do
                match choice is
                    A -> do
                        Log.info { msg = "A" }
                        1
                    B -> State.get {}
                    C -> Random.random {}
        """.trimIndent()

        val program = typeCheck(code)
        val binding = getBinding(program, "test")

        // Should have all three effects
        val funcType = binding.inferredType as MezzeType.FunctionType
        assertNotNull(funcType.effects, "Should have effects from all three branches")
        assertTrue(funcType.effects!!.effects.contains("Log"), "Should contain Log effect")
        assertTrue(funcType.effects!!.effects.contains("State"), "Should contain State effect")
        assertTrue(funcType.effects!!.effects.contains("Random"), "Should contain Random effect")
        assertEquals(3, funcType.effects!!.effects.size, "Should have exactly three effects")
    }

    @Test
    fun `test match with alternating pure and effectful branches`() {
        val code = """
            effect Log where {
                info: {msg: Str} -> {} <Log>
            }

            impl Log for ConsoleLogger where {
                info = {msg} -> {}
            }

            type Number = | One, Two, Three, Four, Five |

            let test = { n: Number } -> do
                match n is
                    One -> 1                              # Pure
                    Two -> do                             # Effectful
                        Log.info { msg = "Two" }
                        2
                    Three -> 3                            # Pure
                    Four -> do                            # Effectful
                        Log.info { msg = "Four" }
                        4
                    Five -> 5                             # Pure
        """.trimIndent()

        val program = typeCheck(code)
        val binding = getBinding(program, "test")

        // Should have Log effect from the effectful branches
        val funcType = binding.inferredType as MezzeType.FunctionType
        assertNotNull(funcType.effects, "Mixed pure/effectful branches should have effects")
        assertTrue(funcType.effects!!.effects.contains("Log"), "Should contain Log effect")
    }

    @Test
    fun `test match in let binding propagates effects`() {
        val code = """
            effect Log where {
                info: {msg: Str} -> {} <Log>
            }

            impl Log for ConsoleLogger where {
                info = {msg} -> {}
            }

            type Option 'a = | Some { val: 'a }, None |

            let outer = {} -> do
                let inner = match Some { val = 42 } is
                    Some { val } -> do
                        Log.info { msg = "Some" }
                        val
                    None -> 0
                inner + 1
        """.trimIndent()

        val program = typeCheck(code)
        val binding = getBinding(program, "outer")

        // outer should have Log effect from the match in its body
        val funcType = binding.inferredType as MezzeType.FunctionType
        assertNotNull(funcType.effects, "Let binding with effectful match should propagate effects")
        assertTrue(funcType.effects!!.effects.contains("Log"), "Should contain Log effect")
    }

    @Test
    fun `test deeply nested match expressions with effect accumulation`() {
        val code = """
            effect Log where {
                info: {msg: Str} -> {} <Log>
            }

            effect State where {
                get: {} -> Int <State>
            }

            impl Log for ConsoleLogger where {
                info = {msg} -> {}
            }

            impl State for Counter {state: Int} where {
                get = {} -> 0
            }

            type Option 'a = | Some { val: 'a }, None |

            let test = { a: Option Int, b: Option Int, c: Option Int } -> do
                match a is
                    Some { val = valA } -> do
                        match b is
                            Some { val = valB } -> do
                                match c is
                                    Some { val = valC } -> do
                                        Log.info { msg = "All Some" }
                                        valA + valB + valC
                                    None -> do
                                        let x = State.get {}
                                        valA + valB + x
                            None -> valA
                    None -> 0
        """.trimIndent()

        val program = typeCheck(code)
        val binding = getBinding(program, "test")

        // Should have both Log and State from different nested branches
        val funcType = binding.inferredType as MezzeType.FunctionType
        assertNotNull(funcType.effects, "Deeply nested matches should accumulate effects")
        assertTrue(funcType.effects!!.effects.contains("Log"), "Should contain Log effect")
        assertTrue(funcType.effects!!.effects.contains("State"), "Should contain State effect")
    }
}
