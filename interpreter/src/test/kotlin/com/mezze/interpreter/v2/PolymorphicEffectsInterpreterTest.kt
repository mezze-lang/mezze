package com.mezze.interpreter.v2

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import com.mezze.interpreter.v2.values.MezzeRecord

/**
 * Tests for runtime behavior of polymorphic effects in the interpreter.
 * Migrated from V1 to V2 with unboxed values.
 */
class PolymorphicEffectsInterpreterTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `test higher-order function with pure callback executes correctly`() {
        val code = """
            let twice: { f: {} -> Int <...> } -> Int <...> =
                { f } -> do
                    let x = f {}
                    let y = f {}
                    x + y

            let result = twice { f = {} -> 42 }
            result
        """.trimIndent()

        val result = eval(code)
        assertEquals(84L, result) // 42 + 42 = 84
    }

    @Test
    fun `test higher-order function with effectful callback executes correctly`() {
        val code = """
            effect Log where {
                info: {msg: Str} -> {} <Log>
            }

            impl Log for ConsoleLogger {} where {
                info = {msg} -> {}
            }

            let twice: { f: {} -> Int <...> } -> Int <...> =
                { f } -> do
                    let x = f {}
                    let y = f {}
                    x + y

            let counter = { } -> do
                Log.info { msg = "Called" }
                21

            perform ConsoleLogger in twice { f = counter }
        """.trimIndent()

        val result = eval(code)
        assertEquals(42L, result) // 21 + 21 = 42
    }

    @Test
    fun `test compose with polymorphic effects`() {
        val code = """
            let compose: { f: { x: Int } -> Int <...>, g: { x: Int } -> Int <...> } -> { x: Int } -> Int <...> =
                { f, g } -> { x } -> f { x = g { x = x } }

            let inc = { x: Int } -> x + 1
            let double = { x: Int } -> x * 2

            let composed = compose { f = inc, g = double }
            composed { x = 5 }
        """.trimIndent()

        val result = eval(code)
        assertEquals(11L, result) // double(5) = 10, inc(10) = 11
    }

    @Test
    fun `test polymorphic effects with different callbacks`() {
        val code = """
            let apply_both: { f: {} -> Int <...>, g: {} -> Int <...> } -> Int <...> =
                { f, g } -> do
                    let x = f {}
                    let y = g {}
                    x + y

            apply_both { f = {} -> 10, g = {} -> 20 }
        """.trimIndent()

        val result = eval(code)
        assertEquals(30L, result) // 10 + 20 = 30
    }

    @Test
    fun `test polymorphic effects in nested structures`() {
        val code = """
            let config: { handler: { x: Int } -> Int <...> } =
                { handler = { x } -> x * 2 }

            let apply_handler: { cfg: { handler: { x: Int } -> Int <...> }, value: Int } -> Int <...> =
                { cfg, value } -> cfg.handler { x = value }

            apply_handler { cfg = config, value = 21 }
        """.trimIndent()

        val result = eval(code)
        assertEquals(42L, result) // 21 * 2 = 42
    }

    @Test
    fun `test polymorphic effects preserve purity`() {
        // Pure functions passed to polymorphic effect parameters should remain pure
        val code = """
            let map_twice: { f: { x: Int } -> Int <...> } -> { x: Int } -> Int <...> =
                { f } -> { x } -> f { x = f { x = x } }

            let add_one = { x: Int } -> x + 1

            let mapped = map_twice { f = add_one }
            mapped { x = 10 }
        """.trimIndent()

        val result = eval(code)
        assertEquals(12L, result) // add_one(add_one(10)) = 12
    }

    @Test
    fun `test polymorphic effects with record return`() {
        val code = """
            let both: { f: {} -> Int <...>, g: {} -> Str <...> } -> { a: Int, b: Str } <...> =
                { f, g } -> { a = f {}, b = g {} }

            both { f = {} -> 42, g = {} -> "hello" }
        """.trimIndent()

        val result = eval(code) as MezzeRecord
        assertEquals(42L, result.getField("a"))
        assertEquals("hello", result.getField("b"))
    }

    @Test
    fun `test polymorphic effects with conditional logic`() {
        val code = """
            let conditional_apply: { pred: Bool, f: {} -> Int <...>, g: {} -> Int <...> } -> Int <...> =
                { pred, f, g } -> if pred then f {} else g {}

            conditional_apply { pred = True, f = {} -> 100, g = {} -> 200 }
        """.trimIndent()

        val result = eval(code)
        assertEquals(100L, result)
    }

    @Test
    fun `test polymorphic effects with match expression`() {
        // Test that polymorphic effects work with match expressions
        val code = """
            let get_value: { opt: (Option Int) } -> Int =
                { opt } -> match opt is
                    Some { val } -> val
                    None -> 0

            let some_val = Some { val = 42 }
            get_value { opt = some_val }
        """.trimIndent()

        val result = eval(code)
        assertEquals(42L, result)
    }
}
