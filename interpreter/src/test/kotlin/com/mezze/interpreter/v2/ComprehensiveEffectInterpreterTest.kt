package com.mezze.interpreter.v2

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import com.mezze.interpreter.v2.values.MezzeRecord
import com.mezze.interpreter.v2.values.MezzeUnit

/**
 * Comprehensive tests for effect interpreter.
 * Migrated from V1 to V2 with unboxed values.
 */
class ComprehensiveEffectInterpreterTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun testBasicEffectExecution() {
        val source = """
            effect Log where {
              print : {msg: Str} -> {} <Log>
            }

            impl Log for Console where {
              print : {msg: Str} -> {} = {msg} -> {}
            }

            perform Console in Log.print {msg = "Hello from effect test!"}
        """.trimIndent()

        val result = eval(source)
        // Should execute successfully and return unit
        assertTrue(MezzeTestHelper.isUnit(result) || (result is MezzeRecord && (result as MezzeRecord).getFieldCount() == 0))
    }

    @Test
    fun testRandomEffectExecution() {
        val source = """
            effect Random where {
              nextInt : {min: Int, max: Int} -> Int <Random>
            }

            impl Random for MathRandom where {
              nextInt : {min: Int, max: Int} -> Int = {min, max} -> min + max / 2
            }

            perform MathRandom in Random.nextInt {min = 1, max = 100}
        """.trimIndent()

        val result = eval(source)
        // Should return an integer between 1 and 100
        assertTrue(result is Long)
        val intValue = result as Long
        assertTrue(intValue >= 1 && intValue <= 100)
    }

    @Test
    fun testMultipleEffectsExecution() {
        val source = """
            use std::int

            effect Log where {
              print : {msg: Str} -> {} <Log>
            }

            effect Random where {
              nextInt : {min: Int, max: Int} -> Int <Random>
            }

            impl Log for Console where {
              print : {msg: Str} -> {} = {msg} -> {}
            }

            impl Random for MathRandom where {
              nextInt : {min: Int, max: Int} -> Int = {min, max} -> min + max / 2
            }

            # Direct effect calls with multiple handlers
            perform MathRandom, Console in do
              Log.print {msg = "Generating random number"}
              let num = Random.nextInt {min = 1, max = 10}
              Log.print {msg = "Generated: $(int::to_str {n = num})"}
              num
        """.trimIndent()

        val result = eval(source)
        // Should return an integer
        assertTrue(result is Long)
        val intValue = result as Long
        assertTrue(intValue >= 1 && intValue <= 10)
    }

    @Test
    fun testDoBlockWithEffectsExecution() {
        val source = """
            use std::int

            effect Log where {
              info : {msg: Str} -> {} <Log>
            }

            impl Log for Console where {
              info : {msg: Str} -> {} = {msg} -> {}
            }

            # Computation with effects in do block
            let computation = {} ->
              do
                Log.info {msg = "Starting computation"}
                let x = 5
                let y = 10
                Log.info {msg = "Computing $(int::to_str {n = x}) + $(int::to_str {n = y})"}
                let result = x + y
                Log.info {msg = "Result: $(int::to_str {n = result})"}
                result

            # Execute computation
            perform Console in computation {}
        """.trimIndent()

        val result = eval(source)
        // Should return 15
        assertEquals(15L, result)
    }

    @Test
    fun testUseExpressionExecution() {
        val source = """
            effect Log where {
              print : {msg: Str} -> {} <Log>
            }

            impl Log for Console where {
              print : {msg: Str} -> {} = {msg} -> {}
            }

            perform Console in Log.print {msg = "Hello from use expression!"}
        """.trimIndent()

        val result = eval(source)
        // Should execute successfully and return unit
        assertTrue(MezzeTestHelper.isUnit(result) || (result is MezzeRecord && (result as MezzeRecord).getFieldCount() == 0))
    }

    @Test
    fun testEffectDeclarationRegistration() {
        val source = """
            effect Log where {
              print : {msg: Str} -> {} <Log>,
              debug : {msg: Str, level: Int} -> {} <Log>
            }

            # Just declare the effect, don't use it
            let dummy = 42
            dummy
        """.trimIndent()

        val result = eval(source)
        // Should return 42
        assertEquals(42L, result)
    }

    @Test
    fun testImplDeclarationRegistration() {
        val source = """
            effect Log where {
              print : {msg: Str} -> {} <Log>
            }

            impl Log for Console where {
              print : {msg: Str} -> {} = {msg} -> {}
            }

            # Just declare the impl, don't use it
            let dummy = "test"
            dummy
        """.trimIndent()

        val result = eval(source)
        // Should return "test"
        assertEquals("test", result)
    }

    @Test
    fun testMultipleImplOperationsExecution() {
        val source = """
            effect Log where {
              info : {msg: Str} -> {} <Log>,
              error : {msg: Str} -> {} <Log>,
              debug : {msg: Str} -> {} <Log>
            }

            impl Log for Console where {
              info : {msg: Str} -> {} = {msg} -> {},
              error : {msg: Str} -> {} = {msg} -> {},
              debug : {msg: Str} -> {} = {msg} -> {}
            }

            # Test function with multiple log operations
            let testLogs = {} ->
              do
                Log.info {msg = "This is info"}
                Log.error {msg = "This is error"}
                Log.debug {msg = "This is debug"}
                "All logs completed"

            # Execute with handler
            perform Console in testLogs {}
        """.trimIndent()

        val result = eval(source)
        // Should return the string
        assertEquals("All logs completed", result)
    }

    @Test
    fun testNestedEffectCallsExecution() {
        val source = """
            effect Log where {
              print : {msg: Str} -> {} <Log>
            }

            impl Log for Console where {
              print : {msg: Str} -> {} = {msg} -> {}
            }

            # Simple nested effect calls
            perform Console in do
              Log.print {msg = "Outer function start"}
              Log.print {msg = "Inner function start"}
              let value = 42
              Log.print {msg = "Inner function end"}
              Log.print {msg = "Outer function end"}
              value
        """.trimIndent()

        val result = eval(source)
        // Should return 42
        assertEquals(42L, result)
    }

    @Test
    fun testEffectCallWithComplexArguments() {
        val source = """
            effect Math where {
              add : {x: Int, y: Int} -> Int <Math>,
              multiply : {x: Int, y: Int} -> Int <Math>
            }

            impl Math for Calculator where {
              add : {x: Int, y: Int} -> Int = {x, y} -> x + y,
              multiply : {x: Int, y: Int} -> Int = {x, y} -> x * y
            }

            # Complex computation with multiple effect calls
            let computation = {} ->
              do
                let a = 3
                let b = 4
                let sum = Math.add {x = a, y = b}
                let product = Math.multiply {x = sum, y = 2}
                product

            # Execute computation
            perform Calculator in computation {}
        """.trimIndent()

        val result = eval(source)
        // Should return (3 + 4) * 2 = 14
        assertEquals(14L, result)
    }

    @Test
    fun testEffectCallErrorHandling() {
        // Test that type errors are reported correctly
        val source = """
            let x: Int = "not an int"
        """.trimIndent()

        // In V2, TypeCheckError is wrapped in PolyglotException
        val exception = assertThrows(org.graalvm.polyglot.PolyglotException::class.java) {
            eval(source)
        }
        // Verify the underlying cause mentions type mismatch
        assertTrue(exception.message?.contains("TypeCheckError") == true ||
                   exception.message?.contains("type") == true)
    }

    @Test
    fun testNativeFunctionIntegration() {
        // Test native operations through arithmetic operators
        val source = """
            let x = 10 + 20
            let y = x * 2
            y
        """.trimIndent()

        val result = eval(source)
        // Should execute successfully and return 60
        assertEquals(60L, result)
    }

    @Test
    fun testNativeRandomFunctionIntegration() {
        // Test native integer operations through built-in abilities
        // Also tests that a record literal can be returned as the last expression
        val source = """
let a = 5
let b = 3
let sum = a + b
let diff = a - b
let prod = a * b
let quot = a / b
{sum = sum, diff = diff, prod = prod, quot = quot}
        """.trimIndent()

        val result = eval(source)
        // Should return a record with computed values
        assertTrue(result is MezzeRecord)
        val record = result as MezzeRecord
        assertEquals(8L, record.getField("sum"))
        assertEquals(2L, record.getField("diff"))
        assertEquals(15L, record.getField("prod"))
        assertEquals(1L, record.getField("quot"))
    }

    @Test
    fun testHandlerMappingCorrectness() {
        val source = """
            effect Log where {
              print : {msg: Str} -> {} <Log>
            }

            effect Debug where {
              log : {msg: Str} -> {} <Debug>
            }

            impl Log for Console where {
              print : {msg: Str} -> {} = {msg} -> {}
            }

            impl Debug for DebugConsole where {
              log : {msg: Str} -> {} = {msg} -> {}
            }

            # Direct multiple effect calls
            perform DebugConsole, Console in do
              Log.print {msg = "This is from Log effect"}
              Debug.log {msg = "This is from Debug effect"}
              "Mapping test complete"
        """.trimIndent()

        val result = eval(source)
        // Should execute successfully
        assertEquals("Mapping test complete", result)
    }

    @Test
    fun testParameterizedImplExecution() {
        // Note: This test will work once parameterized handler instantiation is implemented
        val source = """
            effect Logging where {
              log : {msg: Str} -> {} <Logging>
            }

            impl Logging for FileLogger {path: Str} where {
              log : {msg: Str} -> {} = {msg} -> {}
            }

            # Test parameterized handler with path parameter
            perform FileLogger {path = "app.log"} in Logging.log {msg = "Parameterized test"}
        """.trimIndent()

        val result = eval(source)
        // Should execute successfully even with empty parameters
        assertTrue(MezzeTestHelper.isUnit(result) || (result is MezzeRecord && (result as MezzeRecord).getFieldCount() == 0))
    }
}
