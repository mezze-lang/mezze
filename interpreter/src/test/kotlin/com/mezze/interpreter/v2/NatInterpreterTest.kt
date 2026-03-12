package com.mezze.interpreter.v2

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for the std::nat module functions.
 * Tests cover natural number operations and conversions.
 */
class NatInterpreterTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // ========================================
    // Basic Operations
    // ========================================

    @Test
    fun `min returns smaller value`() {
        val code = """
            use std::nat
            nat::min { l = 3n, r = 7n }
        """
        val result = eval(code)
        assertEquals(3L, result)
    }

    @Test
    fun `max returns larger value`() {
        val code = """
            use std::nat
            nat::max { l = 3n, r = 7n }
        """
        val result = eval(code)
        assertEquals(7L, result)
    }

    @Test
    fun `is_zero returns true for zero`() {
        val code = """
            use std::nat
            nat::is_zero { n = 0n }
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `is_zero returns false for non-zero`() {
        val code = """
            use std::nat
            nat::is_zero { n = 5n }
        """
        val result = eval(code)
        assertEquals(false, result)
    }

    @Test
    fun `is_even returns true for even number`() {
        val code = """
            use std::nat
            nat::is_even { n = 4n }
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `is_even returns false for odd number`() {
        val code = """
            use std::nat
            nat::is_even { n = 5n }
        """
        val result = eval(code)
        assertEquals(false, result)
    }

    @Test
    fun `is_odd returns true for odd number`() {
        val code = """
            use std::nat
            nat::is_odd { n = 5n }
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `is_odd returns false for even number`() {
        val code = """
            use std::nat
            nat::is_odd { n = 4n }
        """
        val result = eval(code)
        assertEquals(false, result)
    }

    // ========================================
    // Subtraction to Int
    // ========================================

    @Test
    fun `subtract_to_int with positive result`() {
        val code = """
            use std::nat
            nat::subtract_to_int { l = 10n, r = 3n }
        """
        val result = eval(code)
        assertEquals(7L, result)
    }

    @Test
    fun `subtract_to_int with negative result`() {
        val code = """
            use std::nat
            nat::subtract_to_int { l = 3n, r = 10n }
        """
        val result = eval(code)
        assertEquals(-7L, result)
    }

    // ========================================
    // Clamp
    // ========================================

    @Test
    fun `clamp constrains value to range`() {
        val code = """
            use std::nat
            nat::clamp { n = 15n, min = 0n, max = 10n }
        """
        val result = eval(code)
        assertEquals(10L, result)
    }

    @Test
    fun `clamp returns value when in range`() {
        val code = """
            use std::nat
            nat::clamp { n = 5n, min = 0n, max = 10n }
        """
        val result = eval(code)
        assertEquals(5L, result)
    }

    @Test
    fun `clamp constrains to minimum`() {
        val code = """
            use std::nat
            nat::clamp { n = 0n, min = 5n, max = 10n }
        """
        val result = eval(code)
        assertEquals(5L, result)
    }

    // ========================================
    // Conversion Functions
    // ========================================

    @Test
    fun `to_int converts Nat to Int`() {
        val code = """
            use std::nat
            nat::to_int { n = 42n }
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `to_str converts Nat to String`() {
        val code = """
            use std::nat
            nat::to_str { n = 42n }
        """
        val result = eval(code)
        assertEquals("42", result)
    }

    @Test
    fun `from_int converts positive Int to Nat`() {
        val code = """
            use std::nat
            match nat::from_int { n = 42 } is
                Some { val } -> 1
                None -> 0
        """
        val result = eval(code)
        assertEquals(1L, result)
    }

    @Test
    fun `from_int returns None for negative Int`() {
        val code = """
            use std::nat
            match nat::from_int { n = -5 } is
                Some { val } -> 1
                None -> 0
        """
        val result = eval(code)
        assertEquals(0L, result)
    }

    @Test
    fun `from_string parses valid natural number`() {
        val code = """
            use std::nat
            match nat::from_string { s = "42" } is
                Some { val } -> 1
                None -> 0
        """
        val result = eval(code)
        assertEquals(1L, result)
    }

    @Test
    fun `from_string returns None for invalid string`() {
        val code = """
            use std::nat
            match nat::from_string { s = "abc" } is
                Some { val } -> 1
                None -> 0
        """
        val result = eval(code)
        assertEquals(0L, result)
    }

    @Test
    fun `from_string returns None for negative number string`() {
        val code = """
            use std::nat
            match nat::from_string { s = "-5" } is
                Some { val } -> 1
                None -> 0
        """
        val result = eval(code)
        assertEquals(0L, result)
    }
}
