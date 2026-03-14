package com.mezze.interpreter.v2

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

/**
 * Tests for qualified effect name imports in the interpreter.
 *
 * These tests verify that:
 * 1. Qualified effect names (module::Effect) work in impl declarations at runtime
 * 2. Effect handlers are correctly matched regardless of import style
 * 3. Effect operations produce correct results with qualified imports
 */
class QualifiedEffectImportInterpreterTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // ========================================================================
    // BASIC QUALIFIED EFFECT IMPL TESTS
    // ========================================================================

    @Test
    fun `qualified effect impl with module import works`() {
        val source = """
            use std::effects::log

            impl log::Log for TestLogger {} where {
                log = {level, msg} -> {}
            }

            perform TestLogger {} in do
                log::info { msg = "test" }
                42
        """.trimIndent()

        val result = eval(source)
        assertEquals(42L, result)
    }

    @Test
    fun `selective import effect impl works`() {
        val source = """
            use std::effects::log::{ Log, info }

            impl Log for TestLogger {} where {
                log = {level, msg} -> {}
            }

            perform TestLogger {} in do
                info { msg = "test" }
                42
        """.trimIndent()

        val result = eval(source)
        assertEquals(42L, result)
    }

    // ========================================================================
    // LOCAL EFFECT WITH QUALIFIED IMPL
    // ========================================================================

    @Test
    fun `local effect with qualified-style impl works`() {
        // Define a local State-like effect to test associated types
        val source = """
            effect Counter where {
                get_count: {} -> Int <Counter>
            }

            impl Counter for SimpleCounter {} where {
                get_count = {} -> 42
            }

            perform SimpleCounter {} in Counter.get_count {}
        """.trimIndent()

        val result = eval(source)
        assertEquals(42L, result)
    }

    // ========================================================================
    // MULTIPLE EFFECTS WITH MIXED IMPORT STYLES
    // ========================================================================

    @Test
    fun `multiple local effects with different handlers work`() {
        val source = """
            effect Logger where {
                log_msg: {msg: Str} -> {} <Logger>
            }

            effect Counter where {
                get_value: {} -> Int <Counter>
            }

            impl Logger for TestLogger {} where {
                log_msg = {msg} -> {}
            }

            impl Counter for TestCounter {} where {
                get_value = {} -> 100
            }

            perform TestLogger {}, TestCounter {} in do
                Logger.log_msg { msg = "Starting" }
                Counter.get_value {}
        """.trimIndent()

        val result = eval(source)
        assertEquals(100L, result)
    }

    // ========================================================================
    // NESTED PERFORM WITH QUALIFIED EFFECTS
    // ========================================================================

    @Test
    fun `nested perform with qualified effect works`() {
        val source = """
            use std::effects::log

            impl log::Log for OuterLogger {} where {
                log = {level, msg} -> {}
            }

            impl log::Log for InnerLogger {} where {
                log = {level, msg} -> {}
            }

            perform OuterLogger {} in do
                log::info { msg = "outer" }
                perform InnerLogger {} in log::info { msg = "inner" }
        """.trimIndent()

        val result = eval(source)
        assertTrue(MezzeTestHelper.isUnit(result), "Expected unit, got: $result (${result?.javaClass})")
    }

    // ========================================================================
    // LOCAL EFFECT WITH QUALIFIED STDLIB EFFECT
    // ========================================================================

    @Test
    fun `local effect combined with qualified stdlib effect works`() {
        val source = """
            use std::effects::log

            effect Counter where {
                increment: {} -> Int <Counter>
            }

            impl Counter for SimpleCounter {} where {
                increment = {} -> 1
            }

            impl log::Log for TestLogger {} where {
                log = {level, msg} -> {}
            }

            perform SimpleCounter {}, TestLogger {} in do
                log::info { msg = "incrementing" }
                Counter.increment {}
        """.trimIndent()

        val result = eval(source)
        assertEquals(1L, result)
    }

    // ========================================================================
    // EFFECT RETURN VALUES
    // ========================================================================

    @Test
    fun `local effect returning value works`() {
        val source = """
            effect ValueGetter where {
                get_value: {} -> Str <ValueGetter>
            }

            impl ValueGetter for StringHolder {} where {
                get_value = {} -> "hello world"
            }

            perform StringHolder {} in ValueGetter.get_value {}
        """.trimIndent()

        val result = eval(source)
        assertEquals("hello world", result)
    }

    // ========================================================================
    // MULTIPLE QUALIFIED EFFECT IMPLS
    // ========================================================================

    @Test
    fun `multiple qualified effect impls for same effect work`() {
        val source = """
            use std::effects::log

            impl log::Log for Logger1 {} where {
                log = {level, msg} -> {}
            }

            impl log::Log for Logger2 {} where {
                log = {level, msg} -> {}
            }

            # Use Logger1 then Logger2
            do
                perform Logger1 {} in log::info { msg = "from logger1" }
                perform Logger2 {} in log::info { msg = "from logger2" }
        """.trimIndent()

        val result = eval(source)
        assertTrue(MezzeTestHelper.isUnit(result), "Expected unit, got: $result (${result?.javaClass})")
    }
}
