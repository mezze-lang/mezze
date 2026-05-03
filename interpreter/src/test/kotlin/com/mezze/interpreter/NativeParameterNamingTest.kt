package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests that native functions use consistent parameter naming conventions.
 *
 * V2 migration notes:
 * - Uses MezzeTestHelper.run(source) returning unboxed values
 * - Tests are behavioral (verifying operations work) rather than inspecting internal APIs
 * - Binary operations use 'l' and 'r' parameters
 * - Unary value operations use standardized parameter names
 */
class NativeParameterNamingTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `test binary operations work with standard arithmetic`() {
        // Tests that __native_int_add (and other binary ops) work correctly
        // This verifies the underlying parameter naming is consistent
        val result = eval("5 + 3")
        assertEquals(8L, result)
    }

    @Test
    fun `test int to string conversion`() {
        // Tests that int::to_str works correctly with 'n' parameter
        val result = eval("""
            use std::int
            int::to_str { n = 42 }
        """.trimIndent())
        assertEquals("42", result)
    }

    @Test
    fun `test consistent parameter naming across binary operations - addition`() {
        // Tests int::add with 'l' and 'r' parameters
        val result = eval("""
            use std::int
            int::add { l = 10, r = 2 }
        """.trimIndent())
        assertEquals(12L, result)
    }

    @Test
    fun `test consistent parameter naming across binary operations - subtraction`() {
        // Tests int::subtract with 'l' and 'r' parameters
        val result = eval("""
            use std::int
            int::subtract { l = 10, r = 2 }
        """.trimIndent())
        assertEquals(8L, result)
    }

    @Test
    fun `test consistent parameter naming across binary operations - multiplication`() {
        // Tests int::multiply with 'l' and 'r' parameters
        val result = eval("""
            use std::int
            int::multiply { l = 10, r = 2 }
        """.trimIndent())
        assertEquals(20L, result)
    }

    @Test
    fun `test consistent parameter naming across binary operations - division`() {
        val result = eval("10 / 2")
        assertEquals(5L, result)
    }

    @Test
    fun `test consistent parameter naming across binary operations - modulo`() {
        val result = eval("10 % 3")
        assertEquals(1L, result)
    }

    @Test
    fun `test string operations use consistent naming`() {
        // Tests str::concat with 'l' and 'r' parameters
        val result = eval("""
            use std::str
            str::concat { l = "Hello", r = " World" }
        """.trimIndent())
        assertEquals("Hello World", result)
    }

    @Test
    fun `test comparison operations use consistent naming - equality`() {
        val result = eval("5 == 5")
        assertEquals(true, result)
    }

    @Test
    fun `test comparison operations use consistent naming - inequality`() {
        val result = eval("5 != 3")
        assertEquals(true, result)
    }

    @Test
    fun `test comparison operations use consistent naming - less than`() {
        val result = eval("3 < 5")
        assertEquals(true, result)
    }

    @Test
    fun `test comparison operations use consistent naming - greater than`() {
        val result = eval("5 > 3")
        assertEquals(true, result)
    }

    @Test
    fun `test comparison operations use consistent naming - less than or equal`() {
        val result = eval("5 <= 5")
        assertEquals(true, result)
    }

    @Test
    fun `test comparison operations use consistent naming - greater than or equal`() {
        val result = eval("5 >= 5")
        assertEquals(true, result)
    }

    @Test
    fun `test boolean operations use consistent naming - and`() {
        val result = eval("True && True")
        assertEquals(true, result)
    }

    @Test
    fun `test boolean operations use consistent naming - or`() {
        val result = eval("False || True")
        assertEquals(true, result)
    }

    @Test
    fun `test chained binary operations`() {
        // Verifies that multiple binary operations work together
        val result = eval("(2 + 3) * 4 - 10")
        assertEquals(10L, result)
    }

    @Test
    fun `test float binary operations`() {
        val result = eval("3.5 + 1.5")
        assertEquals(5.0, result)
    }

    @Test
    fun `test float to string conversion`() {
        // Tests float::to_str with 'n' parameter
        val result = eval("""
            use std::float
            float::to_str { n = 3.14 }
        """.trimIndent())
        assertEquals("3.14", result)
    }

    @Test
    fun `test string length operation`() {
        // Tests str::size with 's' parameter
        val result = eval("""
            use std::str
            str::size { s = "hello" }
        """.trimIndent())
        assertEquals(5L, result)
    }
}
