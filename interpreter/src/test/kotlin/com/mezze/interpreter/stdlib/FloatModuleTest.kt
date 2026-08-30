package com.mezze.interpreter.stdlib

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

class FloatModuleTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = com.mezze.interpreter.MezzeTestHelper.run(source)

    @Test
    fun `test float to_str function`() {
        val source = """
            use std::float
            float::to_str { n = 3.14 }
        """.trimIndent()

        val result = eval(source)
        assertEquals("3.14", result)
    }

    @Test
    fun `test float from_int function`() {
        val source = """
            use std::float
            float::from_int { n = 42 }
        """.trimIndent()

        val result = eval(source)
        assertEquals(42.0, result as Double, 0.0001)
    }

    @Test
    fun `test float from_nat function`() {
        val source = """
            use std::float
            float::from_nat { n = 42n }
        """.trimIndent()

        val result = eval(source)
        assertEquals(42.0, result as Double, 0.0001)
    }

    @Test
    fun `test float abs function - positive`() {
        val source = """
            use std::float
            float::abs { n = 3.14 }
        """.trimIndent()

        val result = eval(source)
        assertEquals(3.14, result as Double, 0.0001)
    }

    @Test
    fun `test float abs function - negative`() {
        val source = """
            use std::float
            float::abs { n = -3.14 }
        """.trimIndent()

        val result = eval(source)
        assertEquals(3.14, result as Double, 0.0001)
    }

    @Test
    fun `test float abs function - zero`() {
        val source = """
            use std::float
            float::abs { n = 0.0 }
        """.trimIndent()

        val result = eval(source)
        assertEquals(0.0, result as Double, 0.0001)
    }

    @Test
    fun `test float min function`() {
        val source = """
            use std::float
            float::min { l = 3.14, r = 2.71 }
        """.trimIndent()

        val result = eval(source)
        assertEquals(2.71, result as Double, 0.0001)
    }

    @Test
    fun `test float min function - negative numbers`() {
        val source = """
            use std::float
            float::min { l = -3.14, r = -2.71 }
        """.trimIndent()

        val result = eval(source)
        assertEquals(-3.14, result as Double, 0.0001)
    }

    @Test
    fun `test float max function`() {
        val source = """
            use std::float
            float::max { l = 3.14, r = 2.71 }
        """.trimIndent()

        val result = eval(source)
        assertEquals(3.14, result as Double, 0.0001)
    }

    @Test
    fun `test float max function - negative numbers`() {
        val source = """
            use std::float
            float::max { l = -3.14, r = -2.71 }
        """.trimIndent()

        val result = eval(source)
        assertEquals(-2.71, result as Double, 0.0001)
    }

    @Test
    fun `test float min and max with equal values`() {
        val source = """
            use std::float
            float::min { l = 5.0, r = 5.0 }
        """.trimIndent()

        val result = eval(source)
        assertEquals(5.0, result as Double, 0.0001)
    }

    @Test
    fun `test float max with equal values`() {
        val source = """
            use std::float
            float::max { l = 5.0, r = 5.0 }
        """.trimIndent()

        val result = eval(source)
        assertEquals(5.0, result as Double, 0.0001)
    }

    // === Additional tests for missing functions ===

    @Test
    fun `test float from_str - valid float`() {
        val source = """
            use std::float
            match float::from_str { s = "3.14" } is
                Some { val } -> val
                None -> -999.0
        """.trimIndent()

        val result = eval(source)
        assertEquals(3.14, result as Double, 0.0001)
    }

    @Test
    fun `test float from_str - integer string`() {
        val source = """
            use std::float
            match float::from_str { s = "42" } is
                Some { val } -> val
                None -> -999.0
        """.trimIndent()

        val result = eval(source)
        assertEquals(42.0, result as Double, 0.0001)
    }

    @Test
    fun `test float from_str - negative float`() {
        val source = """
            use std::float
            match float::from_str { s = "-2.718" } is
                Some { val } -> val
                None -> -999.0
        """.trimIndent()

        val result = eval(source)
        assertEquals(-2.718, result as Double, 0.0001)
    }

    @Test
    fun `test float from_str - invalid string`() {
        val source = """
            use std::float
            match float::from_str { s = "not a number" } is
                Some { val } -> val
                None -> -999.0
        """.trimIndent()

        val result = eval(source)
        assertEquals(-999.0, result as Double, 0.0001)
    }

    // === Float Classification Tests ===

    @Test
    fun `test float is_nan - with NaN`() {
        val source = """
            use std::float
            float::is_nan { n = 0.0 / 0.0 }
        """.trimIndent()

        val result = eval(source)
        assertEquals(true, result)
    }

    @Test
    fun `test float is_nan - with normal value`() {
        val source = """
            use std::float
            float::is_nan { n = 3.14 }
        """.trimIndent()

        val result = eval(source)
        assertEquals(false, result)
    }

    @Test
    fun `test float is_infinite - with positive infinity`() {
        val source = """
            use std::float
            float::is_infinite { n = 1.0 / 0.0 }
        """.trimIndent()

        val result = eval(source)
        assertEquals(true, result)
    }

    @Test
    fun `test float is_infinite - with negative infinity`() {
        val source = """
            use std::float
            float::is_infinite { n = -1.0 / 0.0 }
        """.trimIndent()

        val result = eval(source)
        assertEquals(true, result)
    }

    @Test
    fun `test float is_infinite - with normal value`() {
        val source = """
            use std::float
            float::is_infinite { n = 3.14 }
        """.trimIndent()

        val result = eval(source)
        assertEquals(false, result)
    }

    @Test
    fun `test float is_finite - with normal value`() {
        val source = """
            use std::float
            float::is_finite { n = 3.14 }
        """.trimIndent()

        val result = eval(source)
        assertEquals(true, result)
    }

    @Test
    fun `test float is_finite - with infinity`() {
        val source = """
            use std::float
            float::is_finite { n = 1.0 / 0.0 }
        """.trimIndent()

        val result = eval(source)
        assertEquals(false, result)
    }

    @Test
    fun `test float is_finite - with NaN`() {
        val source = """
            use std::float
            float::is_finite { n = 0.0 / 0.0 }
        """.trimIndent()

        val result = eval(source)
        assertEquals(false, result)
    }

    // === Float Utilities Tests ===

    @Test
    fun `test float sign - positive`() {
        val source = """
            use std::float
            float::sign { n = 5.5 }
        """.trimIndent()

        val result = eval(source)
        assertEquals(1.0, result as Double, 0.0001)
    }

    @Test
    fun `test float sign - negative`() {
        val source = """
            use std::float
            float::sign { n = -5.5 }
        """.trimIndent()

        val result = eval(source)
        assertEquals(-1.0, result as Double, 0.0001)
    }

    @Test
    fun `test float sign - zero`() {
        val source = """
            use std::float
            float::sign { n = 0.0 }
        """.trimIndent()

        val result = eval(source)
        assertEquals(0.0, result as Double, 0.0001)
    }

    @Test
    fun `test float clamp - within range`() {
        val source = """
            use std::float
            float::clamp { n = 5.0, min = 0.0, max = 10.0 }
        """.trimIndent()

        val result = eval(source)
        assertEquals(5.0, result as Double, 0.0001)
    }

    @Test
    fun `test float clamp - below min`() {
        val source = """
            use std::float
            float::clamp { n = -5.0, min = 0.0, max = 10.0 }
        """.trimIndent()

        val result = eval(source)
        assertEquals(0.0, result as Double, 0.0001)
    }

    @Test
    fun `test float clamp - above max`() {
        val source = """
            use std::float
            float::clamp { n = 15.5, min = 0.0, max = 10.0 }
        """.trimIndent()

        val result = eval(source)
        assertEquals(10.0, result as Double, 0.0001)
    }

    @Test
    fun `test float to_int - positive truncate`() {
        val source = """
            use std::float
            match float::to_int { n = 3.7 } is
                Some { val } -> val
                None -> -999
        """.trimIndent()

        val result = eval(source)
        assertEquals(3L, result)
    }

    @Test
    fun `test float to_int - negative truncate`() {
        val source = """
            use std::float
            match float::to_int { n = -3.7 } is
                Some { val } -> val
                None -> -999
        """.trimIndent()

        val result = eval(source)
        assertEquals(-3L, result)
    }

    @Test
    fun `test float to_int - with infinity returns None`() {
        val source = """
            use std::float
            match float::to_int { n = 1.0 / 0.0 } is
                Some { val } -> 1
                None -> 0
        """.trimIndent()

        val result = eval(source)
        assertEquals(0L, result)
    }

    @Test
    fun `test float to_int - with NaN returns None`() {
        val source = """
            use std::float
            match float::to_int { n = 0.0 / 0.0 } is
                Some { val } -> 1
                None -> 0
        """.trimIndent()

        val result = eval(source)
        assertEquals(0L, result)
    }
}
