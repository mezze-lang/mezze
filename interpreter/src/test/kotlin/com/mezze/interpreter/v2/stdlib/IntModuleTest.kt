package com.mezze.interpreter.v2.stdlib

import com.mezze.interpreter.v2.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for the Int module in the standard library.
 * Migrated from V1 to V2 with unboxed values.
 */
class IntModuleTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `test int add function`() {
        val source = """
            use std::int
            int::add { l = 5, r = 3 }
        """.trimIndent()

        assertEquals(8L, eval(source))
    }

    @Test
    fun `test int subtract function`() {
        val source = """
            use std::int
            int::subtract { l = 10, r = 4 }
        """.trimIndent()

        assertEquals(6L, eval(source))
    }

    @Test
    fun `test int multiply function`() {
        val source = """
            use std::int
            int::multiply { l = 6, r = 7 }
        """.trimIndent()

        assertEquals(42L, eval(source))
    }

    @Test
    fun `test int divide function`() {
        val source = """
            use std::int
            int::divide { l = 20, r = 4 }
        """.trimIndent()

        assertEquals(5L, eval(source))
    }

    @Test
    fun `test int modulo function`() {
        val source = """
            use std::int
            int::modulo { l = 17, r = 5 }
        """.trimIndent()

        assertEquals(2L, eval(source))
    }

    @Test
    fun `test int to_str function`() {
        val source = """
            use std::int
            int::to_str { n = 42 }
        """.trimIndent()

        assertEquals("42", eval(source))
    }

    @Test
    fun `test int abs function - negative`() {
        val source = """
            use std::int
            int::abs { n = -5 }
        """.trimIndent()

        assertEquals(5L, eval(source))
    }

    @Test
    fun `test int abs function - positive`() {
        val source = """
            use std::int
            int::abs { n = 5 }
        """.trimIndent()

        assertEquals(5L, eval(source))
    }

    @Test
    fun `test int max function`() {
        val source = """
            use std::int
            int::max { l = 5, r = 8 }
        """.trimIndent()

        assertEquals(8L, eval(source))
    }

    @Test
    fun `test int min function`() {
        val source = """
            use std::int
            int::min { l = 5, r = 8 }
        """.trimIndent()

        assertEquals(5L, eval(source))
    }

    @Test
    fun `test int is_even function - true`() {
        val source = """
            use std::int
            int::is_even { n = 4 }
        """.trimIndent()

        assertEquals(true, eval(source))
    }

    @Test
    fun `test int is_even function - false`() {
        val source = """
            use std::int
            int::is_even { n = 5 }
        """.trimIndent()

        assertEquals(false, eval(source))
    }

    @Test
    fun `test int is_odd function - false`() {
        val source = """
            use std::int
            int::is_odd { n = 4 }
        """.trimIndent()

        assertEquals(false, eval(source))
    }

    @Test
    fun `test int is_odd function - true`() {
        val source = """
            use std::int
            int::is_odd { n = 5 }
        """.trimIndent()

        assertEquals(true, eval(source))
    }

    @Test
    fun `test int gcd function`() {
        val source = """
            use std::int
            int::gcd { l = 48, r = 18 }
        """.trimIndent()

        assertEquals(6L, eval(source))
    }

    @Test
    fun `test int lcm function`() {
        val source = """
            use std::int
            int::lcm { l = 12, r = 18 }
        """.trimIndent()

        assertEquals(36L, eval(source))
    }

    @Test
    fun `test int from_string - valid integer`() {
        val source = """
            use std::int
            match int::from_string { s = "42" } is
                Some { val } -> val
                None -> -999
        """.trimIndent()

        assertEquals(42L, eval(source))
    }

    @Test
    fun `test int from_string - negative integer`() {
        val source = """
            use std::int
            match int::from_string { s = "-123" } is
                Some { val } -> val
                None -> -999
        """.trimIndent()

        assertEquals(-123L, eval(source))
    }

    @Test
    fun `test int from_string - invalid string`() {
        val source = """
            use std::int
            match int::from_string { s = "not a number" } is
                Some { val } -> val
                None -> -999
        """.trimIndent()

        assertEquals(-999L, eval(source))
    }

    @Test
    fun `test int from_nat`() {
        val source = """
            use std::int
            int::from_nat { n = 42n }
        """.trimIndent()

        assertEquals(42L, eval(source))
    }

    @Test
    fun `test int sign - positive`() {
        val source = """
            use std::int
            int::sign { n = 42 }
        """.trimIndent()

        assertEquals(1L, eval(source))
    }

    @Test
    fun `test int sign - negative`() {
        val source = """
            use std::int
            int::sign { n = -42 }
        """.trimIndent()

        assertEquals(-1L, eval(source))
    }

    @Test
    fun `test int sign - zero`() {
        val source = """
            use std::int
            int::sign { n = 0 }
        """.trimIndent()

        assertEquals(0L, eval(source))
    }

    @Test
    fun `test int pow - basic`() {
        val source = """
            use std::int
            int::pow { base = 2, exp = 10 }
        """.trimIndent()

        assertEquals(1024L, eval(source))
    }

    @Test
    fun `test int pow - zero exponent`() {
        val source = """
            use std::int
            int::pow { base = 5, exp = 0 }
        """.trimIndent()

        assertEquals(1L, eval(source))
    }

    @Test
    fun `test int pow - exponent of one`() {
        val source = """
            use std::int
            int::pow { base = 7, exp = 1 }
        """.trimIndent()

        assertEquals(7L, eval(source))
    }

    @Test
    fun `test int clamp - within range`() {
        val source = """
            use std::int
            int::clamp { n = 5, min = 0, max = 10 }
        """.trimIndent()

        assertEquals(5L, eval(source))
    }

    @Test
    fun `test int clamp - below min`() {
        val source = """
            use std::int
            int::clamp { n = -5, min = 0, max = 10 }
        """.trimIndent()

        assertEquals(0L, eval(source))
    }

    @Test
    fun `test int clamp - above max`() {
        val source = """
            use std::int
            int::clamp { n = 15, min = 0, max = 10 }
        """.trimIndent()

        assertEquals(10L, eval(source))
    }

    @Test
    fun `test int clamp - negative range`() {
        val source = """
            use std::int
            int::clamp { n = 0, min = -10, max = -5 }
        """.trimIndent()

        assertEquals(-5L, eval(source))
    }

    @Test
    fun `test int to_nat - positive`() {
        val source = """
            use std::int
            match int::to_nat { n = 42 } is
                Some { val } -> 1
                None -> 0
        """.trimIndent()

        assertEquals(1L, eval(source))
    }

    @Test
    fun `test int to_nat - negative returns None`() {
        val source = """
            use std::int
            match int::to_nat { n = -5 } is
                Some { val } -> 1
                None -> 0
        """.trimIndent()

        assertEquals(0L, eval(source))
    }

    @Test
    fun `test int to_nat - zero`() {
        val source = """
            use std::int
            match int::to_nat { n = 0 } is
                Some { val } -> 1
                None -> 0
        """.trimIndent()

        assertEquals(1L, eval(source))
    }
}
