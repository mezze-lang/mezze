package com.mezze.language.interpreter.stdlib

import com.mezze.language.interpreter.*
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import kotlin.math.abs

/**
 * Tests for std/math/trig module.
 */
class MathTrigModuleTest {

    private val parser = MezzeParser()
    private val typeChecker = TypeChecker()

    @BeforeEach
    fun setup() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): MezzeValue {
        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val (result, _) = MezzeTestHelper.executeWithContext(typeCheckResult.program, typeCheckResult.monomorphicInstances)
        return result
    }

    // === Constants ===

    @Test
    fun `test trig pi constant`() {
        val result = eval("""
            use std::math::trig
            trig::pi
        """)
        val pi = (result as MezzeFloat).value
        assertTrue(abs(pi - 3.141592653589793) < 0.0001)
    }

    @Test
    fun `test trig e constant`() {
        val result = eval("""
            use std::math::trig
            trig::e
        """)
        val e = (result as MezzeFloat).value
        assertTrue(abs(e - 2.718281828459045) < 0.0001)
    }

    // === Basic Trigonometric Functions ===

    @Test
    fun `test trig sin - zero`() {
        val result = eval("""
            use std::math::trig
            trig::sin { n = 0.0 }
        """)
        val value = (result as MezzeFloat).value
        assertTrue(abs(value - 0.0) < 0.0001)
    }

    @Test
    fun `test trig sin - pi over 2`() {
        val result = eval("""
            use std::math::trig
            trig::sin { n = trig::pi / 2.0 }
        """)
        val value = (result as MezzeFloat).value
        assertTrue(abs(value - 1.0) < 0.0001)
    }

    @Test
    fun `test trig cos - zero`() {
        val result = eval("""
            use std::math::trig
            trig::cos { n = 0.0 }
        """)
        val value = (result as MezzeFloat).value
        assertTrue(abs(value - 1.0) < 0.0001)
    }

    @Test
    fun `test trig cos - pi`() {
        val result = eval("""
            use std::math::trig
            trig::cos { n = trig::pi }
        """)
        val value = (result as MezzeFloat).value
        assertTrue(abs(value - (-1.0)) < 0.0001)
    }

    @Test
    fun `test trig tan - zero`() {
        val result = eval("""
            use std::math::trig
            trig::tan { n = 0.0 }
        """)
        val value = (result as MezzeFloat).value
        assertTrue(abs(value - 0.0) < 0.0001)
    }

    @Test
    fun `test trig tan - pi over 4`() {
        val result = eval("""
            use std::math::trig
            trig::tan { n = trig::pi / 4.0 }
        """)
        val value = (result as MezzeFloat).value
        assertTrue(abs(value - 1.0) < 0.0001)
    }

    // === Inverse Trigonometric Functions ===

    @Test
    fun `test trig asin - zero`() {
        val result = eval("""
            use std::math::trig
            trig::asin { n = 0.0 }
        """)
        val value = (result as MezzeFloat).value
        assertTrue(abs(value - 0.0) < 0.0001)
    }

    @Test
    fun `test trig asin - one`() {
        val result = eval("""
            use std::math::trig
            trig::asin { n = 1.0 }
        """)
        val value = (result as MezzeFloat).value
        assertTrue(abs(value - (kotlin.math.PI / 2.0)) < 0.0001)
    }

    @Test
    fun `test trig acos - one`() {
        val result = eval("""
            use std::math::trig
            trig::acos { n = 1.0 }
        """)
        val value = (result as MezzeFloat).value
        assertTrue(abs(value - 0.0) < 0.0001)
    }

    @Test
    fun `test trig acos - zero`() {
        val result = eval("""
            use std::math::trig
            trig::acos { n = 0.0 }
        """)
        val value = (result as MezzeFloat).value
        assertTrue(abs(value - (kotlin.math.PI / 2.0)) < 0.0001)
    }

    @Test
    fun `test trig atan - zero`() {
        val result = eval("""
            use std::math::trig
            trig::atan { n = 0.0 }
        """)
        val value = (result as MezzeFloat).value
        assertTrue(abs(value - 0.0) < 0.0001)
    }

    @Test
    fun `test trig atan - one`() {
        val result = eval("""
            use std::math::trig
            trig::atan { n = 1.0 }
        """)
        val value = (result as MezzeFloat).value
        assertTrue(abs(value - (kotlin.math.PI / 4.0)) < 0.0001)
    }

    @Test
    fun `test trig atan2 - first quadrant`() {
        val result = eval("""
            use std::math::trig
            trig::atan2 { y = 1.0, x = 1.0 }
        """)
        val value = (result as MezzeFloat).value
        assertTrue(abs(value - (kotlin.math.PI / 4.0)) < 0.0001)
    }

    @Test
    fun `test trig atan2 - y axis`() {
        val result = eval("""
            use std::math::trig
            trig::atan2 { y = 1.0, x = 0.0 }
        """)
        val value = (result as MezzeFloat).value
        assertTrue(abs(value - (kotlin.math.PI / 2.0)) < 0.0001)
    }

    // === Conversion Functions ===

    @Test
    fun `test trig degrees_to_radians - 180 degrees`() {
        val result = eval("""
            use std::math::trig
            trig::degrees_to_radians { degrees = 180.0 }
        """)
        val value = (result as MezzeFloat).value
        assertTrue(abs(value - kotlin.math.PI) < 0.0001)
    }

    @Test
    fun `test trig degrees_to_radians - 90 degrees`() {
        val result = eval("""
            use std::math::trig
            trig::degrees_to_radians { degrees = 90.0 }
        """)
        val value = (result as MezzeFloat).value
        assertTrue(abs(value - (kotlin.math.PI / 2.0)) < 0.0001)
    }

    @Test
    fun `test trig radians_to_degrees - pi`() {
        val result = eval("""
            use std::math::trig
            trig::radians_to_degrees { radians = trig::pi }
        """)
        val value = (result as MezzeFloat).value
        assertTrue(abs(value - 180.0) < 0.0001)
    }

    @Test
    fun `test trig radians_to_degrees - pi over 2`() {
        val result = eval("""
            use std::math::trig
            trig::radians_to_degrees { radians = trig::pi / 2.0 }
        """)
        val value = (result as MezzeFloat).value
        assertTrue(abs(value - 90.0) < 0.0001)
    }

    // === Hyperbolic Functions ===

    @Test
    fun `test trig sinh - zero`() {
        val result = eval("""
            use std::math::trig
            trig::sinh { n = 0.0 }
        """)
        val value = (result as MezzeFloat).value
        assertTrue(abs(value - 0.0) < 0.0001)
    }

    @Test
    fun `test trig sinh - one`() {
        val result = eval("""
            use std::math::trig
            trig::sinh { n = 1.0 }
        """)
        val value = (result as MezzeFloat).value
        // sinh(1) ≈ 1.1752
        assertTrue(abs(value - 1.1752011936438014) < 0.0001)
    }

    @Test
    fun `test trig cosh - zero`() {
        val result = eval("""
            use std::math::trig
            trig::cosh { n = 0.0 }
        """)
        val value = (result as MezzeFloat).value
        assertTrue(abs(value - 1.0) < 0.0001)
    }

    @Test
    fun `test trig cosh - one`() {
        val result = eval("""
            use std::math::trig
            trig::cosh { n = 1.0 }
        """)
        val value = (result as MezzeFloat).value
        // cosh(1) ≈ 1.5431
        assertTrue(abs(value - 1.5430806348152437) < 0.0001)
    }

    @Test
    fun `test trig tanh - zero`() {
        val result = eval("""
            use std::math::trig
            trig::tanh { n = 0.0 }
        """)
        val value = (result as MezzeFloat).value
        assertTrue(abs(value - 0.0) < 0.0001)
    }

    @Test
    fun `test trig tanh - one`() {
        val result = eval("""
            use std::math::trig
            trig::tanh { n = 1.0 }
        """)
        val value = (result as MezzeFloat).value
        // tanh(1) ≈ 0.7616
        assertTrue(abs(value - 0.7615941559557649) < 0.0001)
    }

    // === Integration Tests ===

    @Test
    fun `test pythagorean identity`() {
        val result = eval("""
            use std::math::trig
            use std::math::core
            let angle = 0.5
            let s = trig::sin { n = angle }
            let c = trig::cos { n = angle }
            s * s + c * c
        """)
        val value = (result as MezzeFloat).value
        // sin^2 + cos^2 should equal 1
        assertTrue(abs(value - 1.0) < 0.0001)
    }

    @Test
    fun `test round trip degrees to radians and back`() {
        val result = eval("""
            use std::math::trig
            let deg = 45.0
            let rad = trig::degrees_to_radians { degrees = deg }
            trig::radians_to_degrees { radians = rad }
        """)
        val value = (result as MezzeFloat).value
        assertTrue(abs(value - 45.0) < 0.0001)
    }
}
