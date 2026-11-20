package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for runtime effect validation behavior.
 * Migrated from V1 to V2 with unboxed values.
 *
 * An effect performed with no handler must never reach the host as a value
 * (a suspended computation is runtime plumbing). Such a program is refused by
 * the type checker (V0501 for `main`); if one runs anyway, it fails loudly with
 * an internal error naming the operation — never a `MezzeSuspend` result.
 */
class RuntimeEffectValidationTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    private fun assertUnhandledFailsLoudly(source: String, op: String) {
        val outcome = runCatching { eval(source) }
        val message = outcome.exceptionOrNull()?.message
            ?: fail<Nothing>("expected a loud failure, got the value: ${outcome.getOrNull()}")
        assertFalse(message.contains("<suspended"), "a suspension leaked into the message: $message")
        assertTrue(message.contains("Effect operation `$op` ran with no handler in scope"), "unexpected failure: $message")
    }

    @Test
    fun testUnhandledEffectFailsLoudly() {
        val source = """
            effect Log where {
              print : {msg: Str} -> {} <Log>
            }

            impl Log for Console where {
              print : {msg: Str} -> {} = {msg} -> {}
            }

            let effectfulFunc : {x: Int} -> Int <Log> = {x} -> do
              Log.print {msg = "test"}
              x + 1

            # Call function with unhandled effects - should return suspended
            effectfulFunc {x = 5}
        """.trimIndent()

        assertUnhandledFailsLoudly(source, "print")
    }

    @Test
    fun testHandledEffectAllowsExecution() {
        val source = """
            effect Log where {
              print : {msg: Str} -> {} <Log>
            }

            impl Log for Console where {
              print : {msg: Str} -> {} = {msg} -> {}
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
    fun testMultipleUnhandledEffectsFailLoudly() {
        val source = """
            effect Log where {
              print : {msg: Str} -> {} <Log>
            }

            effect Random where {
              nextInt : {max: Int} -> Int <Random>
            }

            impl Log for Console where {
              print : {msg: Str} -> {} = {msg} -> {}
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

        assertUnhandledFailsLoudly(source, "print")
    }

    @Test
    fun testPartiallyHandledEffectsFailLoudlyOnTheUnhandledOne() {
        val source = """
            effect Log where {
              print : {msg: Str} -> {} <Log>
            }

            effect Random where {
              nextInt : {max: Int} -> Int <Random>
            }

            impl Log for Console where {
              print : {msg: Str} -> {} = {msg} -> {}
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

        assertUnhandledFailsLoudly(source, "nextInt")
    }

    @Test
    fun testFullyHandledEffectsAllowsExecution() {
        val source = """
            effect Log where {
              print : {msg: Str} -> {} <Log>
            }

            effect Random where {
              nextInt : {max: Int} -> Int <Random>
            }

            impl Log for Console where {
              print : {msg: Str} -> {} = {msg} -> {}
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
    fun testNestedEffectfulFunctionFailsLoudly() {
        val source = """
            effect Log where {
              print : {msg: Str} -> {} <Log>
            }

            impl Log for Console where {
              print : {msg: Str} -> {} = {msg} -> {}
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

        assertUnhandledFailsLoudly(source, "print")
    }

    @Test
    fun testPartialApplicationOfEffectfulFunctionFailsLoudly() {
        val source = """
            effect Log where {
              print : {msg: Str} -> {} <Log>
            }

            impl Log for Console where {
              print : {msg: Str} -> {} = {msg} -> {}
            }

            let effectfulFunc = {x, y} -> do
              Log.print {msg = "computing"}
              x + y

            # Partial application should still preserve effect information
            let partialFunc = effectfulFunc {x = 5}
            partialFunc {y = 3}
        """.trimIndent()

        assertUnhandledFailsLoudly(source, "print")
    }
}
