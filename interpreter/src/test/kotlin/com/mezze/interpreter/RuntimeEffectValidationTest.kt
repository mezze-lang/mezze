package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import com.mezze.interpreter.values.MezzeSuspend

/**
 * Tests for runtime effect validation behavior.
 * Migrated from V1 to V2 with unboxed values.
 *
 * In V2, unhandled effects result in MezzeSuspend values that can be
 * checked for which effects are not yet handled.
 */
class RuntimeEffectValidationTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun testUnhandledEffectReturnsSuspended() {
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

        val result = eval(source)

        // Result should be a suspended computation
        assertTrue(result is MezzeSuspend, "Result should be MezzeSuspend but was ${result::class.simpleName}")
        val suspended = result as MezzeSuspend

        // Should have exactly one unhandled effect (identified by content hash)
        assertEquals(1, suspended.unhandledEffects.size,
            "Should have exactly one unhandled effect but was ${suspended.unhandledEffects}")
        // Verify it's a valid content hash (128 hex characters for SHA3-512)
        val effectHash = suspended.unhandledEffects.first()
        assertTrue(effectHash.hex.length == 128 && effectHash.hex.all { it in '0'..'9' || it in 'a'..'f' },
            "Unhandled effect should be a content hash but was $effectHash")
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
    fun testMultipleUnhandledEffectsReturnsSuspended() {
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

        val result = eval(source)

        // Result should be a suspended computation
        assertTrue(result is MezzeSuspend, "Result should be MezzeSuspend but was ${result::class.simpleName}")
        val suspended = result as MezzeSuspend

        // Should have at least one unhandled effect (identified by content hash)
        assertTrue(suspended.unhandledEffects.isNotEmpty(),
            "Should have at least one unhandled effect but was empty")
        // Verify all are valid content hashes
        suspended.unhandledEffects.forEach { hash ->
            assertTrue(hash.hex.length == 128 && hash.hex.all { it in '0'..'9' || it in 'a'..'f' },
                "Unhandled effect should be a content hash but was $hash")
        }
    }

    @Test
    fun testPartiallyHandledEffectsReturnsSuspended() {
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

        val result = eval(source)

        // Result should be a suspended computation with Random unhandled
        assertTrue(result is MezzeSuspend, "Result should be MezzeSuspend but was ${result::class.simpleName}")
        val suspended = result as MezzeSuspend

        // Should have exactly one unhandled effect (Random's content hash - Log was handled)
        assertEquals(1, suspended.unhandledEffects.size,
            "Should have exactly one unhandled effect (Random) but was ${suspended.unhandledEffects}")
        // Verify it's a valid content hash
        val effectHash = suspended.unhandledEffects.first()
        assertTrue(effectHash.hex.length == 128 && effectHash.hex.all { it in '0'..'9' || it in 'a'..'f' },
            "Unhandled effect should be a content hash but was $effectHash")
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
    fun testNestedEffectfulFunctionValidation() {
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

        val result = eval(source)

        // Result should be a suspended computation
        assertTrue(result is MezzeSuspend, "Result should be MezzeSuspend but was ${result::class.simpleName}")
        val suspended = result as MezzeSuspend

        // Should have exactly one unhandled effect (identified by content hash)
        assertEquals(1, suspended.unhandledEffects.size,
            "Should have exactly one unhandled effect but was ${suspended.unhandledEffects}")
        val effectHash = suspended.unhandledEffects.first()
        assertTrue(effectHash.hex.length == 128 && effectHash.hex.all { it in '0'..'9' || it in 'a'..'f' },
            "Unhandled effect should be a content hash but was $effectHash")
    }

    @Test
    fun testEffectValidationInPartialApplication() {
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

        val result = eval(source)

        // Result should be a suspended computation
        assertTrue(result is MezzeSuspend, "Result should be MezzeSuspend but was ${result::class.simpleName}")
        val suspended = result as MezzeSuspend

        // Should have exactly one unhandled effect (identified by content hash)
        assertEquals(1, suspended.unhandledEffects.size,
            "Should have exactly one unhandled effect but was ${suspended.unhandledEffects}")
        val effectHash = suspended.unhandledEffects.first()
        assertTrue(effectHash.hex.length == 128 && effectHash.hex.all { it in '0'..'9' || it in 'a'..'f' },
            "Unhandled effect should be a content hash but was $effectHash")
    }
}
