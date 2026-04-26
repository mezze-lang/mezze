package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import kotlin.math.abs

/**
 * Tests for std/math/core module.
 * Migrated from V1 to V2 with unboxed values.
 */
class MathCoreModuleTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // === Constants ===

    @Test
    fun `test math core pi constant`() {
        val result = eval("""
            use std::math::core
            core::pi
        """)
        val pi = result as Double
        assertTrue(abs(pi - 3.141592653589793) < 0.0001)
    }

    @Test
    fun `test math core e constant`() {
        val result = eval("""
            use std::math::core
            core::e
        """)
        val e = result as Double
        assertTrue(abs(e - 2.718281828459045) < 0.0001)
    }

    // === Power Functions ===

    @Test
    fun `test math core pow`() {
        val result = eval("""
            use std::math::core
            core::pow { base = 2.0, exp = 3.0 }
        """)
        val value = result as Double
        assertTrue(abs(value - 8.0) < 0.0001)
    }

    @Test
    fun `test math core pow_int`() {
        val result = eval("""
            use std::math::core
            core::pow_int { base = 2, exp = 10 }
        """)
        assertEquals(1024L, result)
    }

    @Test
    fun `test math core pow_nat`() {
        val result = eval("""
            use std::math::core
            core::pow_nat { base = 3n, exp = 4n }
        """)
        assertEquals(81L, result)
    }

    // === Square Root ===

    @Test
    fun `test math core sqrt`() {
        val result = eval("""
            use std::math::core
            core::sqrt { n = 16.0 }
        """)
        val value = result as Double
        assertTrue(abs(value - 4.0) < 0.0001)
    }

    @Test
    fun `test math core sqrt - non-perfect square`() {
        val result = eval("""
            use std::math::core
            core::sqrt { n = 2.0 }
        """)
        val value = result as Double
        assertTrue(abs(value - 1.41421356) < 0.0001)
    }

    // === Logarithm Functions ===

    @Test
    fun `test math core ln`() {
        val result = eval("""
            use std::math::core
            core::ln { n = 2.718281828459045 }
        """)
        val value = result as Double
        assertTrue(abs(value - 1.0) < 0.0001)
    }

    @Test
    fun `test math core log10`() {
        val result = eval("""
            use std::math::core
            core::log10 { n = 100.0 }
        """)
        val value = result as Double
        assertTrue(abs(value - 2.0) < 0.0001)
    }

    // === Exponential ===

    @Test
    fun `test math core exp`() {
        val result = eval("""
            use std::math::core
            core::exp { n = 1.0 }
        """)
        val value = result as Double
        assertTrue(abs(value - 2.718281828459045) < 0.0001)
    }

    @Test
    fun `test math core exp - zero`() {
        val result = eval("""
            use std::math::core
            core::exp { n = 0.0 }
        """)
        val value = result as Double
        assertTrue(abs(value - 1.0) < 0.0001)
    }

    // === Rounding Functions ===

    @Test
    fun `test math core floor`() {
        val result = eval("""
            use std::math::core
            core::floor { n = 3.7 }
        """)
        val value = result as Double
        assertTrue(abs(value - 3.0) < 0.0001)
    }

    @Test
    fun `test math core floor - negative`() {
        val result = eval("""
            use std::math::core
            core::floor { n = -3.2 }
        """)
        val value = result as Double
        assertTrue(abs(value - (-4.0)) < 0.0001)
    }

    @Test
    fun `test math core ceil`() {
        val result = eval("""
            use std::math::core
            core::ceil { n = 3.2 }
        """)
        val value = result as Double
        assertTrue(abs(value - 4.0) < 0.0001)
    }

    @Test
    fun `test math core ceil - negative`() {
        val result = eval("""
            use std::math::core
            core::ceil { n = -3.7 }
        """)
        val value = result as Double
        assertTrue(abs(value - (-3.0)) < 0.0001)
    }

    @Test
    fun `test math core round`() {
        val result = eval("""
            use std::math::core
            core::round { n = 3.5 }
        """)
        val value = result as Double
        assertTrue(abs(value - 4.0) < 0.0001)
    }

    @Test
    fun `test math core round - down`() {
        val result = eval("""
            use std::math::core
            core::round { n = 3.4 }
        """)
        val value = result as Double
        assertTrue(abs(value - 3.0) < 0.0001)
    }

    // === Special Value Checks ===

    @Test
    fun `test math core is_nan - true`() {
        val result = eval("""
            use std::math::core
            core::is_nan { n = core::sqrt { n = -1.0 } }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test math core is_nan - false`() {
        val result = eval("""
            use std::math::core
            core::is_nan { n = 1.0 }
        """)
        assertEquals(false, result)
    }

    @Test
    fun `test math core is_infinite - positive infinity`() {
        val result = eval("""
            use std::math::core
            core::is_infinite { n = 1.0 / 0.0 }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test math core is_infinite - false`() {
        val result = eval("""
            use std::math::core
            core::is_infinite { n = 100.0 }
        """)
        assertEquals(false, result)
    }

    @Test
    fun `test math core is_finite - true`() {
        val result = eval("""
            use std::math::core
            core::is_finite { n = 100.0 }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test math core is_finite - false for infinity`() {
        val result = eval("""
            use std::math::core
            core::is_finite { n = 1.0 / 0.0 }
        """)
        assertEquals(false, result)
    }

    // === Sign Functions ===

    @Test
    fun `test math core sign - positive`() {
        val result = eval("""
            use std::math::core
            core::sign { n = 5.0 }
        """)
        val value = result as Double
        assertTrue(abs(value - 1.0) < 0.0001)
    }

    @Test
    fun `test math core sign - negative`() {
        val result = eval("""
            use std::math::core
            core::sign { n = -5.0 }
        """)
        val value = result as Double
        assertTrue(abs(value - (-1.0)) < 0.0001)
    }

    @Test
    fun `test math core sign - zero`() {
        val result = eval("""
            use std::math::core
            core::sign { n = 0.0 }
        """)
        val value = result as Double
        assertTrue(abs(value - 0.0) < 0.0001)
    }

    @Test
    fun `test math core sign_int - positive`() {
        val result = eval("""
            use std::math::core
            core::sign_int { n = 42 }
        """)
        assertEquals(1L, result)
    }

    @Test
    fun `test math core sign_int - negative`() {
        val result = eval("""
            use std::math::core
            core::sign_int { n = -42 }
        """)
        assertEquals(-1L, result)
    }

    @Test
    fun `test math core sign_int - zero`() {
        val result = eval("""
            use std::math::core
            core::sign_int { n = 0 }
        """)
        assertEquals(0L, result)
    }

    // === Min/Max Functions ===

    @Test
    fun `test math core min - first smaller`() {
        val result = eval("""
            use std::math::core
            core::min { l = 5, r = 10 }
        """)
        assertEquals(5L, result)
    }

    @Test
    fun `test math core min - second smaller`() {
        val result = eval("""
            use std::math::core
            core::min { l = 10, r = 5 }
        """)
        assertEquals(5L, result)
    }

    @Test
    fun `test math core min - equal`() {
        val result = eval("""
            use std::math::core
            core::min { l = 7, r = 7 }
        """)
        assertEquals(7L, result)
    }

    @Test
    fun `test math core max - first larger`() {
        val result = eval("""
            use std::math::core
            core::max { l = 10, r = 5 }
        """)
        assertEquals(10L, result)
    }

    @Test
    fun `test math core max - second larger`() {
        val result = eval("""
            use std::math::core
            core::max { l = 5, r = 10 }
        """)
        assertEquals(10L, result)
    }

    @Test
    fun `test math core max - equal`() {
        val result = eval("""
            use std::math::core
            core::max { l = 7, r = 7 }
        """)
        assertEquals(7L, result)
    }

    @Test
    fun `test math core min with floats`() {
        val result = eval("""
            use std::math::core
            core::min { l = 3.14, r = 2.71 }
        """)
        val value = result as Double
        assertTrue(abs(value - 2.71) < 0.0001)
    }

    @Test
    fun `test math core max with floats`() {
        val result = eval("""
            use std::math::core
            core::max { l = 3.14, r = 2.71 }
        """)
        val value = result as Double
        assertTrue(abs(value - 3.14) < 0.0001)
    }
}
