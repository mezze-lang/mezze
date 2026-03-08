package com.mezze.interpreter.v2

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import com.mezze.interpreter.v2.values.MezzeSuspended

/**
 * Tests for runtime effect validation behavior.
 * Migrated from V1 to V2 with unboxed values.
 *
 * In V2, unhandled effects result in MezzeSuspended values that can be
 * checked for which effects are not yet handled.
 */
class RuntimeEffectValidationTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun testUnhandledEffectReturnsSuspended() {
        val source = """
            effect Log where {
              print : {msg: String} -> {} <Log>
            }

            impl Log for Console where {
              print : {msg: String} -> {} = {msg} -> {}
            }

            let effectfulFunc : {x: Int} -> Int <Log> = {x} -> do
              Log.print {msg = "test"}
              x + 1

            # Call function with unhandled effects - should return suspended
            effectfulFunc {x = 5}
        """.trimIndent()

        val result = eval(source)

        // Result should be a suspended computation
        assertTrue(result is MezzeSuspended, "Result should be MezzeSuspended but was ${result::class.simpleName}")
        val suspended = result as MezzeSuspended

        // Should have Log as unhandled effect
        assertTrue(suspended.unhandledEffects.contains("Log"),
            "Unhandled effects should contain Log but was ${suspended.unhandledEffects}")
    }

    @Test
    fun testHandledEffectAllowsExecution() {
        val source = """
            effect Log where {
              print : {msg: String} -> {} <Log>
            }

            impl Log for Console where {
              print : {msg: String} -> {} = {msg} -> {}
            }

            let effectfulFunc = {x} -> do
              Log.print {msg = "test"}
              x + 1

            # Handle the effect properly
            perform Console in effectfulFunc {x = 5}
        """.trimIndent()

        // This should not throw an exception - for now just check it runs
        assertDoesNotThrow { eval(source) }
    }

    @Test
    fun testMultipleUnhandledEffectsReturnsSuspended() {
        val source = """
            effect Log where {
              print : {msg: String} -> {} <Log>
            }

            effect Random where {
              nextInt : {max: Int} -> Int <Random>
            }

            impl Log for Console where {
              print : {msg: String} -> {} = {msg} -> {}
            }

            impl Random for SystemRandom where {
              nextInt : {max: Int} -> Int = {max} -> max
            }

            let multiEffectFunc = {x} -> do
              Log.print {msg = "start"}
              let value = Random.nextInt {max = x}
              Log.print {msg = "end"}
              value

            # Call function with multiple unhandled effects - should return suspended
            multiEffectFunc {x = 10}
        """.trimIndent()

        val result = eval(source)

        // Result should be a suspended computation
        assertTrue(result is MezzeSuspended, "Result should be MezzeSuspended but was ${result::class.simpleName}")
        val suspended = result as MezzeSuspended

        // Should have at least one unhandled effect
        assertTrue(suspended.unhandledEffects.contains("Log") || suspended.unhandledEffects.contains("Random"),
            "Unhandled effects should contain Log or Random but was ${suspended.unhandledEffects}")
    }

    @Test
    fun testPartiallyHandledEffectsReturnsSuspended() {
        val source = """
            effect Log where {
              print : {msg: String} -> {} <Log>
            }

            effect Random where {
              nextInt : {max: Int} -> Int <Random>
            }

            impl Log for Console where {
              print : {msg: String} -> {} = {msg} -> {}
            }

            impl Random for SystemRandom where {
              nextInt : {max: Int} -> Int = {max} -> max
            }

            let multiEffectFunc : {x: Int} -> Int <Log, Random> = {x} -> do
              Log.print {msg = "start"}
              let value = Random.nextInt {max = x}
              value

            # Handle only one effect - should return suspended with remaining effect
            perform Console in multiEffectFunc {x = 10}
        """.trimIndent()

        val result = eval(source)

        // Result should be a suspended computation with Random unhandled
        assertTrue(result is MezzeSuspended, "Result should be MezzeSuspended but was ${result::class.simpleName}")
        val suspended = result as MezzeSuspended

        // Should have Random as the remaining unhandled effect
        assertTrue(suspended.unhandledEffects.contains("Random"),
            "Unhandled effects should contain Random but was ${suspended.unhandledEffects}")
        assertFalse(suspended.unhandledEffects.contains("Log"),
            "Log should be handled, unhandled effects: ${suspended.unhandledEffects}")
    }

    @Test
    fun testFullyHandledEffectsAllowsExecution() {
        val source = """
            effect Log where {
              print : {msg: String} -> {} <Log>
            }

            effect Random where {
              nextInt : {max: Int} -> Int <Random>
            }

            impl Log for Console where {
              print : {msg: String} -> {} = {msg} -> {}
            }

            impl Random for SystemRandom where {
              nextInt : {max: Int} -> Int = {max} -> max
            }

            let multiEffectFunc = {x} -> do
              Log.print {msg = "start"}
              let value = Random.nextInt {max = x}
              value

            # Handle all effects
            perform SystemRandom, Console in multiEffectFunc {x = 10}
        """.trimIndent()

        // This should not throw an exception - for now just check it runs
        assertDoesNotThrow { eval(source) }
    }

    @Test
    fun testPureFunctionExecutesWithoutHandlers() {
        val source = """
            let pureFunc = {x, y} -> x + y
            pureFunc {x = 3, y = 4}
        """.trimIndent()

        // Pure functions should execute without issues
        val result = eval(source)
        assertEquals(7L, result)
    }

    @Test
    fun testNestedEffectfulFunctionValidation() {
        val source = """
            effect Log where {
              print : {msg: String} -> {} <Log>
            }

            impl Log for Console where {
              print : {msg: String} -> {} = {msg} -> {}
            }

            let innerFunc : {x: Int} -> Int <Log> = {x} -> do
              Log.print {msg = "inner"}
              x * 2

            let outerFunc : {y: Int} -> Int <Log> = {y} -> do
              let result = innerFunc {x = y}
              result + 1

            # Both functions have unhandled effects - should return suspended
            outerFunc {y = 5}
        """.trimIndent()

        val result = eval(source)

        // Result should be a suspended computation
        assertTrue(result is MezzeSuspended, "Result should be MezzeSuspended but was ${result::class.simpleName}")
        val suspended = result as MezzeSuspended

        // Should have Log as unhandled effect
        assertTrue(suspended.unhandledEffects.contains("Log"),
            "Unhandled effects should contain Log but was ${suspended.unhandledEffects}")
    }

    @Test
    fun testEffectValidationInPartialApplication() {
        val source = """
            effect Log where {
              print : {msg: String} -> {} <Log>
            }

            impl Log for Console where {
              print : {msg: String} -> {} = {msg} -> {}
            }

            let effectfulFunc = {x, y} -> do
              Log.print {msg = "computing"}
              x + y

            # Partial application should still preserve effect information
            let partialFunc = effectfulFunc {x = 5}
            partialFunc {y = 3}
        """.trimIndent()

        val result = eval(source)

        // Result should be a suspended computation
        assertTrue(result is MezzeSuspended, "Result should be MezzeSuspended but was ${result::class.simpleName}")
        val suspended = result as MezzeSuspended

        // Should have Log as unhandled effect
        assertTrue(suspended.unhandledEffects.contains("Log"),
            "Unhandled effects should contain Log but was ${suspended.unhandledEffects}")
    }
}
