package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import kotlin.math.abs

/**
 * Tests for std/math/random module.
 * Note: This module contains pure mathematical functions for probability distributions,
 * not actual random number generation.
 * Migrated from V1 to V2 with unboxed values.
 */
class MathRandomModuleTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // === Box-Muller Transform ===

    @Test
    fun `test box_muller with standard normal`() {
        val result = eval("""
            use std::effects::random
            random::box_muller { u1 = 0.5, u2 = 0.5, mean = 0.0, std_dev = 1.0 }
        """)
        val value = result as Double
        // With u1=0.5, u2=0.5, we can calculate the expected result
        // z0 = sqrt(-2*ln(0.5)) * cos(2*pi*0.5) = sqrt(1.386) * cos(pi) = 1.177 * -1 = -1.177
        assertTrue(abs(value - (-1.1774100225154747)) < 0.0001)
    }

    @Test
    fun `test box_muller with custom mean and std_dev`() {
        val result = eval("""
            use std::effects::random
            random::box_muller { u1 = 0.5, u2 = 0.5, mean = 10.0, std_dev = 2.0 }
        """)
        val value = result as Double
        // mean + std_dev * z0 = 10.0 + 2.0 * (-1.1774...) = 7.645
        val expectedZ0 = -1.1774100225154747
        val expected = 10.0 + 2.0 * expectedZ0
        assertTrue(abs(value - expected) < 0.0001)
    }

    // === Exponential Distribution ===

    @Test
    fun `test exponential_inverse`() {
        val result = eval("""
            use std::effects::random
            random::exponential_inverse { u = 0.5, rate = 1.0 }
        """)
        val value = result as Double
        // -1/rate * ln(u) = -1 * ln(0.5) = -1 * (-0.693) = 0.693
        assertTrue(abs(value - 0.6931471805599453) < 0.0001)
    }

    @Test
    fun `test exponential_inverse with different rate`() {
        val result = eval("""
            use std::effects::random
            random::exponential_inverse { u = 0.5, rate = 2.0 }
        """)
        val value = result as Double
        // -1/2 * ln(0.5) = 0.347
        assertTrue(abs(value - 0.34657359027997264) < 0.0001)
    }

    // === Uniform Transform ===

    @Test
    fun `test uniform_transform - midpoint`() {
        val result = eval("""
            use std::effects::random
            random::uniform_transform { u = 0.5, min = 0.0, max = 10.0 }
        """)
        val value = result as Double
        // 0 + 0.5 * (10 - 0) = 5
        assertTrue(abs(value - 5.0) < 0.0001)
    }

    @Test
    fun `test uniform_transform - at min`() {
        val result = eval("""
            use std::effects::random
            random::uniform_transform { u = 0.0, min = 5.0, max = 15.0 }
        """)
        val value = result as Double
        assertTrue(abs(value - 5.0) < 0.0001)
    }

    @Test
    fun `test uniform_transform - at max`() {
        val result = eval("""
            use std::effects::random
            random::uniform_transform { u = 1.0, min = 5.0, max = 15.0 }
        """)
        val value = result as Double
        assertTrue(abs(value - 15.0) < 0.0001)
    }

    // === Normal PDF ===

    @Test
    fun `test normal_pdf at mean`() {
        val result = eval("""
            use std::effects::random
            random::normal_pdf { x = 0.0, mean = 0.0, std_dev = 1.0 }
        """)
        val value = result as Double
        // 1 / sqrt(2 * pi * 1) * exp(0) = 0.3989
        assertTrue(abs(value - 0.3989422804014327) < 0.0001)
    }

    @Test
    fun `test normal_pdf - one std_dev away`() {
        val result = eval("""
            use std::effects::random
            random::normal_pdf { x = 1.0, mean = 0.0, std_dev = 1.0 }
        """)
        val value = result as Double
        // Should be less than the pdf at mean
        assertTrue(value < 0.4)
        assertTrue(value > 0.2)
    }

    @Test
    fun `test normal_pdf with custom mean and std_dev`() {
        val result = eval("""
            use std::effects::random
            random::normal_pdf { x = 10.0, mean = 10.0, std_dev = 2.0 }
        """)
        val value = result as Double
        // At the mean, pdf = 1 / sqrt(2 * pi * variance)
        val expected = 1.0 / kotlin.math.sqrt(2.0 * kotlin.math.PI * 4.0)
        assertTrue(abs(value - expected) < 0.0001)
    }

    // === Normal CDF Approximation ===

    @Test
    fun `test normal_cdf_approx at mean`() {
        val result = eval("""
            use std::effects::random
            random::normal_cdf_approx { x = 0.0, mean = 0.0, std_dev = 1.0 }
        """)
        val value = result as Double
        // At mean, CDF should be approximately 0.5
        assertTrue(abs(value - 0.5) < 0.01)
    }

    @Test
    fun `test normal_cdf_approx - far positive`() {
        val result = eval("""
            use std::effects::random
            random::normal_cdf_approx { x = 3.0, mean = 0.0, std_dev = 1.0 }
        """)
        val value = result as Double
        // Should be close to 1.0 for 3 std devs above mean
        assertTrue(value > 0.9)
    }

    @Test
    fun `test normal_cdf_approx - far negative`() {
        val result = eval("""
            use std::effects::random
            random::normal_cdf_approx { x = -3.0, mean = 0.0, std_dev = 1.0 }
        """)
        val value = result as Double
        // Should be close to 0.0 for 3 std devs below mean
        assertTrue(value < 0.1)
    }

    // === Exponential PDF ===

    @Test
    fun `test exponential_pdf at zero`() {
        val result = eval("""
            use std::effects::random
            random::exponential_pdf { x = 0.0, rate = 1.0 }
        """)
        val value = result as Double
        // rate * exp(0) = 1
        assertTrue(abs(value - 1.0) < 0.0001)
    }

    @Test
    fun `test exponential_pdf - negative x`() {
        val result = eval("""
            use std::effects::random
            random::exponential_pdf { x = -1.0, rate = 1.0 }
        """)
        val value = result as Double
        // Should be 0 for negative x
        assertTrue(abs(value - 0.0) < 0.0001)
    }

    @Test
    fun `test exponential_pdf - positive x`() {
        val result = eval("""
            use std::effects::random
            random::exponential_pdf { x = 1.0, rate = 1.0 }
        """)
        val value = result as Double
        // 1 * exp(-1) = 0.368
        assertTrue(abs(value - 0.36787944117144233) < 0.0001)
    }

    @Test
    fun `test exponential_pdf with different rate`() {
        val result = eval("""
            use std::effects::random
            random::exponential_pdf { x = 1.0, rate = 2.0 }
        """)
        val value = result as Double
        // 2 * exp(-2) = 0.271
        assertTrue(abs(value - 0.2706705664732254) < 0.0001)
    }

    // === Exponential CDF ===

    @Test
    fun `test exponential_cdf at zero`() {
        val result = eval("""
            use std::effects::random
            random::exponential_cdf { x = 0.0, rate = 1.0 }
        """)
        val value = result as Double
        // 1 - exp(0) = 0
        assertTrue(abs(value - 0.0) < 0.0001)
    }

    @Test
    fun `test exponential_cdf - negative x`() {
        val result = eval("""
            use std::effects::random
            random::exponential_cdf { x = -1.0, rate = 1.0 }
        """)
        val value = result as Double
        // Should be 0 for negative x
        assertTrue(abs(value - 0.0) < 0.0001)
    }

    @Test
    fun `test exponential_cdf - positive x`() {
        val result = eval("""
            use std::effects::random
            random::exponential_cdf { x = 1.0, rate = 1.0 }
        """)
        val value = result as Double
        // 1 - exp(-1) = 0.632
        assertTrue(abs(value - 0.6321205588285577) < 0.0001)
    }

    @Test
    fun `test exponential_cdf with different rate`() {
        val result = eval("""
            use std::effects::random
            random::exponential_cdf { x = 1.0, rate = 2.0 }
        """)
        val value = result as Double
        // 1 - exp(-2) = 0.865
        assertTrue(abs(value - 0.8646647167633873) < 0.0001)
    }
}
