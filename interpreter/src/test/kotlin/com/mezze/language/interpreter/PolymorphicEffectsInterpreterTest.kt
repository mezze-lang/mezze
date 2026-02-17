package com.mezze.language.interpreter

import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for runtime behavior of polymorphic effects in the interpreter
 */
class PolymorphicEffectsInterpreterTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker
    private lateinit var interpreter: MezzeInterpreter

    @BeforeEach
    fun setup() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()  // Clear module cache to prevent state pollution between tests
        parser = MezzeParser()
        typeChecker = TypeChecker()
        interpreter = MezzeInterpreter()
    }

    private fun execute(code: String): MezzeValue? {
        val program = parser.parse(code)
        val typeCheckResult = typeChecker.typeCheck(program)
        val (result, _) = MezzeTestHelper.executeWithContext(typeCheckResult.program, typeCheckResult.monomorphicInstances)
        return result
    }

    private fun executeWithContext(code: String): Map<String, MezzeValue> {
        val program = parser.parse(code)
        val typeCheckResult = typeChecker.typeCheck(program)
        val (_, context) = MezzeTestHelper.executeWithContext(typeCheckResult.program, typeCheckResult.monomorphicInstances)
        return context
    }

    @Test
    fun `test higher-order function with pure callback executes correctly`() {
        val code = """
            let twice: { f: {} -> Int <...> } -> Int <...> =
                { f } -> do
                    let x = f {}
                    let y = f {}
                    x + y

            let result = twice { f = {} -> 42 }
        """.trimIndent()

        val context = executeWithContext(code)
        assertEquals(84L, (context["result"] as MezzeInt).value) // 42 + 42 = 84
    }

    @Test
    fun `test higher-order function with effectful callback executes correctly`() {
        val code = """
            effect Log where {
                info: {msg: String} -> {} <Log>
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

            let getResult = {} -> perform ConsoleLogger in twice { f = counter }
        """.trimIndent()

        val program = parser.parse(code)
        val typeCheckResult = typeChecker.typeCheck(program)
        val result = MezzeTestHelper.executeAndCallFunction(typeCheckResult.program, "getResult", MezzeRecord(emptyMap()), typeCheckResult.monomorphicInstances)
        assertEquals(42L, (result as MezzeInt).value) // 21 + 21 = 42
    }

    @Test
    fun `test compose with polymorphic effects`() {
        val code = """
            let compose: { f: { x: Int } -> Int <...>, g: { x: Int } -> Int <...> } -> { x: Int } -> Int <...> =
                { f, g } -> { x } -> f { x = g { x = x } }

            let inc = { x: Int } -> x + 1
            let double = { x: Int } -> x * 2

            let composed = compose { f = inc, g = double }
            let result = composed { x = 5 }
        """.trimIndent()

        val context = executeWithContext(code)
        assertEquals(11L, (context["result"] as MezzeInt).value) // double(5) = 10, inc(10) = 11
    }

    @Test
    fun `test polymorphic effects with different callbacks`() {
        val code = """
            let apply_both: { f: {} -> Int <...>, g: {} -> Int <...> } -> Int <...> =
                { f, g } -> do
                    let x = f {}
                    let y = g {}
                    x + y

            let result = apply_both { f = {} -> 10, g = {} -> 20 }
        """.trimIndent()

        val context = executeWithContext(code)
        assertEquals(30L, (context["result"] as MezzeInt).value) // 10 + 20 = 30
    }

    @Test
    fun `test polymorphic effects in nested structures`() {
        val code = """
            let config: { handler: { x: Int } -> Int <...> } =
                { handler = { x } -> x * 2 }

            let apply_handler: { cfg: { handler: { x: Int } -> Int <...> }, value: Int } -> Int <...> =
                { cfg, value } -> cfg.handler { x = value }

            let result = apply_handler { cfg = config, value = 21 }
        """.trimIndent()

        val context = executeWithContext(code)
        assertEquals(42L, (context["result"] as MezzeInt).value) // 21 * 2 = 42
    }

    @Test
    fun `test polymorphic effects preserve purity`() {
        // Pure functions passed to polymorphic effect parameters should remain pure
        val code = """
            let map_twice: { f: { x: Int } -> Int <...> } -> { x: Int } -> Int <...> =
                { f } -> { x } -> f { x = f { x = x } }

            let add_one = { x: Int } -> x + 1

            let mapped = map_twice { f = add_one }
            let result = mapped { x = 10 }
        """.trimIndent()

        val context = executeWithContext(code)
        assertEquals(12L, (context["result"] as MezzeInt).value) // add_one(add_one(10)) = 12
    }

    @Test
    fun `test polymorphic effects with record return`() {
        val code = """
            let both: { f: {} -> Int <...>, g: {} -> String <...> } -> { a: Int, b: String } <...> =
                { f, g } -> { a = f {}, b = g {} }

            let result = both { f = {} -> 42, g = {} -> "hello" }
        """.trimIndent()

        val context = executeWithContext(code)
        val result = context["result"] as MezzeRecord
        assertEquals(42L, (result.fields["a"] as MezzeInt).value)
        assertEquals("hello", (result.fields["b"] as MezzeString).value)
    }

    @Test
    fun `test polymorphic effects with conditional logic`() {
        val code = """
            let conditional_apply: { pred: Bool, f: {} -> Int <...>, g: {} -> Int <...> } -> Int <...> =
                { pred, f, g } -> if pred then f {} else g {}

            let result = conditional_apply { pred = True, f = {} -> 100, g = {} -> 200 }
        """.trimIndent()

        val context = executeWithContext(code)
        assertEquals(100L, (context["result"] as MezzeInt).value)
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
            let result = get_value { opt = some_val }
        """.trimIndent()

        val context = executeWithContext(code)
        assertEquals(42L, (context["result"] as MezzeInt).value)
    }
}
