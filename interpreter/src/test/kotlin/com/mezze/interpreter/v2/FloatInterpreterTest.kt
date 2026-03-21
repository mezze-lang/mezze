package com.mezze.interpreter.v2

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for the std::float module functions.
 * Tests cover float operations, classification, and conversions.
 */
class FloatInterpreterTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // ========================================
    // Basic Operations
    // ========================================

    @Test
    fun `abs returns absolute value of positive`() {
        val code = """
            use std::float
            float::abs { n = 5.5 }
        """
        val result = eval(code)
        assertEquals(5.5, result)
    }

    @Test
    fun `abs returns absolute value of negative`() {
        val code = """
            use std::float
            float::abs { n = -5.5 }
        """
        val result = eval(code)
        assertEquals(5.5, result)
    }

    @Test
    fun `min returns smaller value`() {
        val code = """
            use std::float
            float::min { l = 3.5, r = 7.2 }
        """
        val result = eval(code)
        assertEquals(3.5, result)
    }

    @Test
    fun `max returns larger value`() {
        val code = """
            use std::float
            float::max { l = 3.5, r = 7.2 }
        """
        val result = eval(code)
        assertEquals(7.2, result)
    }

    // ========================================
    // Float Classification
    // ========================================

    @Test
    fun `is_finite returns true for regular float`() {
        val code = """
            use std::float
            float::is_finite { n = 3.14 }
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `is_finite returns false for infinity`() {
        val code = """
            use std::float
            float::is_finite { n = 1.0 / 0.0 }
        """
        val result = eval(code)
        assertEquals(false, result)
    }

    @Test
    fun `is_infinite returns true for infinity`() {
        val code = """
            use std::float
            float::is_infinite { n = 1.0 / 0.0 }
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `is_infinite returns false for regular float`() {
        val code = """
            use std::float
            float::is_infinite { n = 3.14 }
        """
        val result = eval(code)
        assertEquals(false, result)
    }

    @Test
    fun `is_nan returns true for NaN`() {
        val code = """
            use std::float
            float::is_nan { n = 0.0 / 0.0 }
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `is_nan returns false for regular float`() {
        val code = """
            use std::float
            float::is_nan { n = 3.14 }
        """
        val result = eval(code)
        assertEquals(false, result)
    }

    // ========================================
    // Sign Function
    // ========================================

    @Test
    fun `sign returns 1 for positive`() {
        val code = """
            use std::float
            float::sign { n = 42.5 }
        """
        val result = eval(code)
        assertEquals(1.0, result)
    }

    @Test
    fun `sign returns -1 for negative`() {
        val code = """
            use std::float
            float::sign { n = -42.5 }
        """
        val result = eval(code)
        assertEquals(-1.0, result)
    }

    @Test
    fun `sign returns 0 for zero`() {
        val code = """
            use std::float
            float::sign { n = 0.0 }
        """
        val result = eval(code)
        assertEquals(0.0, result)
    }

    // ========================================
    // Clamp
    // ========================================

    @Test
    fun `clamp constrains value to maximum`() {
        val code = """
            use std::float
            float::clamp { n = 15.5, min = 0.0, max = 10.0 }
        """
        val result = eval(code)
        assertEquals(10.0, result)
    }

    @Test
    fun `clamp returns value when in range`() {
        val code = """
            use std::float
            float::clamp { n = 5.5, min = 0.0, max = 10.0 }
        """
        val result = eval(code)
        assertEquals(5.5, result)
    }

    @Test
    fun `clamp constrains to minimum`() {
        val code = """
            use std::float
            float::clamp { n = -5.5, min = 0.0, max = 10.0 }
        """
        val result = eval(code)
        assertEquals(0.0, result)
    }

    // ========================================
    // Conversion Functions
    // ========================================

    @Test
    fun `to_str converts float to string`() {
        val code = """
            use std::float
            use std::str
            let s = float::to_str { n = 3.14 }
            str::starts_with { s = s, prefix = "3.14" }
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `from_int converts integer to float`() {
        val code = """
            use std::float
            float::from_int { n = 42 }
        """
        val result = eval(code)
        assertEquals(42.0, result)
    }

    @Test
    fun `from_nat converts natural to float`() {
        val code = """
            use std::float
            float::from_nat { n = 42n }
        """
        val result = eval(code)
        assertEquals(42.0, result)
    }

    @Test
    fun `from_string parses valid float`() {
        val code = """
            use std::float
            match float::from_string { s = "3.14" } is
                Some { val } -> 1
                None -> 0
        """
        val result = eval(code)
        assertEquals(1L, result)
    }

    @Test
    fun `from_string returns None for invalid`() {
        val code = """
            use std::float
            match float::from_string { s = "abc" } is
                Some { val } -> 1
                None -> 0
        """
        val result = eval(code)
        assertEquals(0L, result)
    }

    @Test
    fun `to_int truncates float to integer`() {
        val code = """
            use std::float
            match float::to_int { n = 3.7 } is
                Some { val } -> val
                None -> -999
        """
        val result = eval(code)
        assertEquals(3L, result)
    }

    @Test
    fun `to_int returns None for infinity`() {
        val code = """
            use std::float
            match float::to_int { n = 1.0 / 0.0 } is
                Some { val } -> 1
                None -> 0
        """
        val result = eval(code)
        assertEquals(0L, result)
    }

    @Test
    fun `to_int returns None for NaN`() {
        val code = """
            use std::float
            match float::to_int { n = 0.0 / 0.0 } is
                Some { val } -> 1
                None -> 0
        """
        val result = eval(code)
        assertEquals(0L, result)
    }
}
