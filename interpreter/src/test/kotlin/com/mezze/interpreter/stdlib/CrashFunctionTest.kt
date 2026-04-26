package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.assertDoesNotThrow

/**
 * Tests for the crash built-in function.
 * Migrated from V1 to V2 with unboxed values.
 *
 * crash is a built-in function (no import needed) that terminates the program
 * with an error message, similar to Rust's panic!.
 */
class CrashFunctionTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `crash function type checks correctly`() {
        val source = """
            let fail = {} -> crash { msg = "Something went wrong" }
            0
        """.trimIndent()

        // Should type check and run without throwing an exception
        // (we add a final expression to avoid actually calling crash)
        assertDoesNotThrow {
            eval(source)
        }
    }

    @Test
    fun `crash function throws exception when called`() {
        val source = """
            let main = {} -> crash { msg = "Test crash message" }
            main {}
        """.trimIndent()

        // The crash function should throw an exception
        val exception = assertThrows<Exception> {
            eval(source)
        }

        assertTrue(
            exception.message?.contains("crash") == true ||
            exception.message?.contains("Test crash message") == true,
            "Exception should mention crash or the message: ${exception.message}"
        )
    }

    @Test
    fun `crash returns polymorphic type - can be used anywhere`() {
        // crash returns 'a which means it can be used in any context
        val source = """
            let get_int = {} ->
                if True then 42 else crash { msg = "unreachable" }

            let get_string = {} ->
                if True then "hello" else crash { msg = "unreachable" }

            0
        """.trimIndent()

        // Should type check and run without errors - crash works with any return type
        // (we add a final expression to avoid actually calling crash)
        assertDoesNotThrow {
            eval(source)
        }
    }

    @Test
    fun `crash is available without import`() {
        // crash should be a built-in, no import needed
        val source = """
            let test = {} -> crash { msg = "no import needed" }
            0
        """.trimIndent()

        // Should type check and run - crash is built-in
        // (we add a final expression to avoid actually calling crash)
        assertDoesNotThrow {
            eval(source)
        }
    }
}
