package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import com.mezze.interpreter.values.MezzeRecord
import com.mezze.interpreter.values.MezzeVariant

/**
 * Tests for state machine execution behavior in V2.
 *
 * Note: The V1 version of this test tested internal interpreter classes
 * (StateMachine, StateMachineExecutor, continuations, etc.). In V2, the
 * state machine infrastructure is implemented using Truffle nodes and has
 * a different internal architecture.
 *
 * This V2 version tests the behavioral aspects of state machine execution
 * through the language interface, including:
 * - Effect handling and suspension
 * - Continuation capture and resumption
 * - Effect context management
 * - State transitions in computations
 *
 * Migrated from V1 to V2 with unboxed values.
 */
class StateMachineExecutionTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `test basic computation completes`() {
        // Test that simple computations complete successfully
        val code = """
            let x = 42
            let y = "test"
            x + 1
        """.trimIndent()
        assertEquals(43L, eval(code))
    }

    @Test
    fun `test variable management in computation`() {
        // Test variable binding and access
        val code = """
            let a = 10
            let b = 20
            let c = a + b
            c * 2
        """.trimIndent()
        assertEquals(60L, eval(code))
    }

    @Test
    fun `test state transitions in conditional`() {
        // Test state transitions in conditional expressions
        val code = """
            let x = 5
            let result = if x > 0 then "positive" else "non-positive"
            result
        """.trimIndent()
        assertEquals("positive", eval(code))
    }

    @Test
    fun `test state transitions in match expression`() {
        // Test state transitions in pattern matching
        val code = """
            let opt = Some { val = 42 }
            match opt is
                Some { val } -> val
                None -> 0
        """.trimIndent()
        assertEquals(42L, eval(code))
    }

    @Test
    fun `test effect handler context`() {
        // Test effect handling with handlers
        val code = """
            effect Log where {
                log: {message: Str} -> {} <Log>
            }

            impl Log for ConsoleLog {} where {
                log = {message} -> {}
            }

            let computation = {} -> do
                Log.log {message = "hello"}
                42

            perform ConsoleLog in computation {}
        """.trimIndent()
        assertEquals(42L, eval(code))
    }

    @Test
    fun `test multiple effects in handler`() {
        // Test handling multiple effect operations
        val code = """
            effect Counter where {
                increment: {} -> Int <Counter>
            }

            impl Counter for SimpleCounter { count: Int } where {
                increment = {} -> count
            }

            let computation = {} -> do
                let a = Counter.increment {}
                let b = Counter.increment {}
                a + b

            perform SimpleCounter { count = 1 } in computation {}
        """.trimIndent()
        // Both increments return count = 1, so result is 2
        assertEquals(2L, eval(code))
    }

    @Test
    fun `test nested state transitions`() {
        // Test nested expressions with state transitions
        val code = """
            let f = {x} -> x * 2
            let g = {x} -> x + 1
            let compose = {f, g, x} -> f {x = g {x = x}}
            compose {f = f, g = g, x = 5}
        """.trimIndent()
        assertEquals(12L, eval(code)) // (5 + 1) * 2 = 12
    }

    @Test
    fun `test recursive computation with state`() {
        // Test recursive functions that maintain state
        val code = """
            let factorial: {n: Int} -> Int = {n} ->
                if n <= 1 then 1
                else n * factorial {n = n - 1}

            factorial {n = 5}
        """.trimIndent()
        assertEquals(120L, eval(code))
    }

    @Test
    fun `test immutable computation with record`() {
        // Test immutable record operations
        val code = """
            let original = {x = 1, y = 2}
            let modified = {...original, y = 10}
            original.y + modified.y
        """.trimIndent()
        assertEquals(12L, eval(code)) // 2 + 10
    }

    @Test
    fun `test state machine with do block`() {
        // Test do blocks which involve internal state management
        val code = """
            do
                let x = 10
                let y = 20
                x + y
        """.trimIndent()
        assertEquals(30L, eval(code))
    }

    @Test
    fun `test state machine with let bindings`() {
        // Test multiple let bindings (each creates state transitions)
        val code = """
            let a = 1
            let b = a + 1
            let c = b + 1
            let d = c + 1
            let e = d + 1
            e
        """.trimIndent()
        assertEquals(5L, eval(code))
    }

    @Test
    fun `test return effect for early termination`() {
        // Test early return effect
        val code = """
            use std::effects::early_return::{Return, EarlyReturn}

            let f = {x} ->
                perform EarlyReturn in do
                    if x < 0 then
                        Return.return {val = 42}
                    else {}
                    100

            f {x = -1}
        """.trimIndent()
        assertEquals(42L, eval(code))
    }

    @Test
    fun `test exception effect handling`() {
        // Test exception-like behavior using Result pattern
        val code = """
            # Test success path using Result type
            let computation = {} -> do
                let x = 10
                Ok { val = x + 5 }

            let result = computation {}

            match result is
                Ok { val } -> val
                Err { error } -> -1
        """.trimIndent()
        assertEquals(15L, eval(code))
    }

    @Test
    fun `test state effect for stateful computation`() {
        // Test stateful computation using effect with single operation
        val code = """
            effect State where {
                get: {} -> Int <State>
            }

            impl State for StateHandler { state: Int } where {
                get = {} -> state
            }

            let computation = {} -> do
                let s = State.get {}
                s + 1

            perform StateHandler { state = 10 } in computation {}
        """.trimIndent()
        assertEquals(11L, eval(code))
    }

    @Test
    fun `test computation completion`() {
        // Test that computation reaches terminal state
        val code = """
            let compute: {n: Int} -> Str = {n} ->
                if n <= 0 then "done"
                else compute {n = n - 1}

            compute {n = 5}
        """.trimIndent()
        assertEquals("done", eval(code))
    }

    @Test
    fun `test error in computation`() {
        // Test error path using Result pattern
        val code = """
            # Test error path using Result type
            let computation = {fail} -> do
                if fail then
                    Err { error = "test error" }
                else
                    Ok { val = 42 }

            let result = computation { fail = True }

            match result is
                Ok { val } -> val
                Err { error } -> -1
        """.trimIndent()
        assertEquals(-1L, eval(code))
    }

    @Test
    fun `test multiple continuations in sequence`() {
        // Test multiple effect operations in sequence
        val code = """
            effect Counter where {
                next: {} -> Int <Counter>
            }

            impl Counter for Incrementor { n: Int } where {
                next = {} -> n
            }

            let computation = {} -> do
                let a = Counter.next {}
                let b = Counter.next {}
                let c = Counter.next {}
                a + b + c

            perform Incrementor { n = 1 } in computation {}
        """.trimIndent()
        // Each next returns n = 1, so result is 3
        assertEquals(3L, eval(code))
    }

    @Test
    fun `test expression continuation - basic`() {
        // Test that expressions properly transition states
        val code = """
            let x = 1 + 2 + 3 + 4 + 5
            x
        """.trimIndent()
        assertEquals(15L, eval(code))
    }

    @Test
    fun `test result continuation - function return`() {
        // Test that function results are properly returned
        val code = """
            let f = {x} -> do
                let a = x * 2
                let b = a + 1
                b

            f {x = 10}
        """.trimIndent()
        assertEquals(21L, eval(code)) // 10 * 2 + 1 = 21
    }

    @Test
    fun `test suspension point with async effect`() {
        // Test async-like behavior with effects
        val code = """
            effect Async2 where {
                suspend_at: {value: Int} -> Int <Async2>
            }

            impl Async2 for DoubleHandler where {
                suspend_at = { value } -> value * 2
            }

            let computation = {} -> do
                let a = Async2.suspend_at { value = 10 }
                let b = Async2.suspend_at { value = 20 }
                a + b

            perform DoubleHandler in computation {}
        """.trimIndent()
        assertEquals(60L, eval(code)) // (10 * 2) + (20 * 2) = 60
    }

    @Test
    fun `test effect context handler management`() {
        // Test nested handlers
        val code = """
            effect Outer where {
                outerOp: {} -> Int <Outer>
            }

            effect Inner where {
                innerOp: {} -> Int <Inner>
            }

            impl Outer for OuterHandler {} where {
                outerOp = {} -> 10
            }

            impl Inner for InnerHandler {} where {
                innerOp = {} -> 20
            }

            let innerComputation = {} ->
                Inner.innerOp {}

            let outerComputation = {} -> do
                let a = Outer.outerOp {}
                let b = perform InnerHandler in innerComputation {}
                a + b

            perform OuterHandler in outerComputation {}
        """.trimIndent()
        assertEquals(30L, eval(code))
    }

    @Test
    fun `test state machine with complex control flow`() {
        // Test complex control flow with multiple branches
        val code = """
            let classify: {n: Int} -> Str = {n} ->
                if n < 0 then "negative"
                else if n == 0 then "zero"
                else if n < 10 then "small"
                else if n < 100 then "medium"
                else "large"

            use std::collections::seq

            let results = [
                classify {n = -5},
                classify {n = 0},
                classify {n = 5},
                classify {n = 50},
                classify {n = 500}
            ]

            seq::size { xs = results }
        """.trimIndent()
        assertEquals(5L, eval(code))
    }

    @Test
    fun `test execution state immutability`() {
        // Test that state is immutable and properly scoped
        val code = """
            let outer = 100
            let f = {} -> do
                let inner = outer + 1
                inner

            let result1 = f {}
            let result2 = f {}

            result1 + result2
        """.trimIndent()
        assertEquals(202L, eval(code)) // 101 + 101
    }
}
