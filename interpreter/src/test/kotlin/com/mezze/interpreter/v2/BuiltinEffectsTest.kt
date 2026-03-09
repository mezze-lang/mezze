package com.mezze.interpreter.v2

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertDoesNotThrow
import com.mezze.interpreter.v2.values.MezzeRecord

/**
 * Tests for builtin effects in V2 interpreter.
 * Migrated from V1 to V2 with unboxed values.
 */
class BuiltinEffectsTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    /** Helper to check if result is unit (either MezzeUnit singleton or empty MezzeRecord) */
    private fun isUnitResult(result: Any): Boolean {
        return MezzeTestHelper.isUnit(result) ||
               (result is MezzeRecord && result.fieldCount == 0)
    }

    @Test
    fun testBuiltinLogEffect() {
        val source = """
            use std::effects::log::{Log, Console, println}

            # Test the effect using stdlib Log
            perform Console in do
                println {msg = "Hello World!"}
                42
        """.trimIndent()

        // This should execute and print to console
        val result = eval(source)
        // Result should be 42 since we return that after logging
        assertEquals(42L, result)
    }

    @Test
    fun testBuiltinRandomEffect() {
        val source = """
            use std::effects::random::{Random, SeededRandom}

            # Test the effect using stdlib Random with SeededRandom for determinism
            perform SeededRandom { seed = 42 } in do
                let result = Random.random_int {min = 1, max = 10}
                result
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is Long)
        val randomValue = result as Long
        assertTrue(randomValue >= 1 && randomValue <= 10, "Random value $randomValue should be between 1 and 10")
    }

    @Test
    fun testParameterExtractionImprovement() {
        val source = """
            # Test that parameter names are correctly extracted
            effect TestEffect where {
              multiParam : {first: String, second: Int, third: Float} -> String <TestEffect>
            }

            impl TestEffect for TestHandler where {
              multiParam : {first: String, second: Int, third: Float} -> String =
                {first, second, third} -> first
            }

            # This should work with proper parameter extraction
            let result = perform TestHandler in TestEffect.multiParam {first = "Hello", second = 42, third = 3.14}

            result
        """.trimIndent()

        // This test verifies that our parameter extraction improvement works
        // The impl should create a function with parameters ["first", "second", "third"]
        // instead of the old simplified ["args"]
        val result = eval(source)
        assertTrue(result is String)
        assertEquals("Hello", result)
    }

    @Test
    fun testNativeFunctionIntegration() {
        // Test that basic arithmetic native operations work
        // These are handled through builtin abilities (NumAdd, NumSub, etc.)
        val source = """
            # Test native operations through arithmetic operators
            let x = 10 + 20
            let y = x * 2
            y
        """.trimIndent()

        // This should execute without throwing an exception
        assertDoesNotThrow {
            val result = eval(source)
            // Native operations should execute successfully
            assertTrue(result is Long)
            assertEquals(60L, result)
        }
    }
}
