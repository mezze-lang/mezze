package com.mezze.interpreter.v2.stdlib

import com.mezze.interpreter.v2.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for std::nat module.
 * Migrated from V1 to V2 with unboxed values.
 */
class NatModuleTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `test nat subtract_to_int - positive result`() {
        val source = """
            use std::nat
            nat::subtract_to_int { l = 10n, r = 3n }
        """.trimIndent()

        assertEquals(7L, eval(source))
    }

    @Test
    fun `test nat subtract_to_int - negative result`() {
        val source = """
            use std::nat
            nat::subtract_to_int { l = 3n, r = 10n }
        """.trimIndent()

        assertEquals(-7L, eval(source))
    }

    @Test
    fun `test nat subtract_to_int - zero result`() {
        val source = """
            use std::nat
            nat::subtract_to_int { l = 5n, r = 5n }
        """.trimIndent()

        assertEquals(0L, eval(source))
    }

    @Test
    fun `test nat min function`() {
        val source = """
            use std::nat
            nat::min { l = 5n, r = 3n }
        """.trimIndent()

        assertEquals(3L, eval(source))
    }

    @Test
    fun `test nat min function - equal values`() {
        val source = """
            use std::nat
            nat::min { l = 5n, r = 5n }
        """.trimIndent()

        assertEquals(5L, eval(source))
    }

    @Test
    fun `test nat max function`() {
        val source = """
            use std::nat
            nat::max { l = 5n, r = 3n }
        """.trimIndent()

        assertEquals(5L, eval(source))
    }

    @Test
    fun `test nat max function - equal values`() {
        val source = """
            use std::nat
            nat::max { l = 5n, r = 5n }
        """.trimIndent()

        assertEquals(5L, eval(source))
    }

    @Test
    fun `test nat is_zero - true`() {
        val source = """
            use std::nat
            nat::is_zero { n = 0n }
        """.trimIndent()

        assertEquals(true, eval(source))
    }

    @Test
    fun `test nat is_zero - false`() {
        val source = """
            use std::nat
            nat::is_zero { n = 5n }
        """.trimIndent()

        assertEquals(false, eval(source))
    }

    @Test
    fun `test nat is_even - true`() {
        val source = """
            use std::nat
            nat::is_even { n = 4n }
        """.trimIndent()

        assertEquals(true, eval(source))
    }

    @Test
    fun `test nat is_even - false`() {
        val source = """
            use std::nat
            nat::is_even { n = 5n }
        """.trimIndent()

        assertEquals(false, eval(source))
    }

    @Test
    fun `test nat is_even - zero`() {
        val source = """
            use std::nat
            nat::is_even { n = 0n }
        """.trimIndent()

        assertEquals(true, eval(source))
    }

    @Test
    fun `test nat is_odd - false`() {
        val source = """
            use std::nat
            nat::is_odd { n = 4n }
        """.trimIndent()

        assertEquals(false, eval(source))
    }

    @Test
    fun `test nat is_odd - true`() {
        val source = """
            use std::nat
            nat::is_odd { n = 5n }
        """.trimIndent()

        assertEquals(true, eval(source))
    }

    @Test
    fun `test nat is_odd - zero`() {
        val source = """
            use std::nat
            nat::is_odd { n = 0n }
        """.trimIndent()

        assertEquals(false, eval(source))
    }

    @Test
    fun `test nat from_int - valid positive`() {
        val source = """
            use std::nat
            match nat::from_int { n = 42 } is
                Some { val } -> val
                None -> -1
        """.trimIndent()

        assertEquals(42L, eval(source))
    }

    @Test
    fun `test nat from_int - negative returns None`() {
        val source = """
            use std::nat
            match nat::from_int { n = -5 } is
                Some { val } -> 1
                None -> 0
        """.trimIndent()

        assertEquals(0L, eval(source))
    }

    @Test
    fun `test nat from_int - zero`() {
        val source = """
            use std::nat
            match nat::from_int { n = 0 } is
                Some { val } -> val
                None -> -1
        """.trimIndent()

        assertEquals(0L, eval(source))
    }

    @Test
    fun `test nat from_float - valid positive`() {
        val source = """
            use std::nat
            match nat::from_float { n = 42.7 } is
                Some { val } -> val
                None -> -1
        """.trimIndent()

        assertEquals(42L, eval(source))
    }

    @Test
    fun `test nat from_float - negative returns None`() {
        val source = """
            use std::nat
            match nat::from_float { n = -5.5 } is
                Some { val } -> 1
                None -> 0
        """.trimIndent()

        assertEquals(0L, eval(source))
    }

    @Test
    fun `test nat from_float - zero`() {
        val source = """
            use std::nat
            match nat::from_float { n = 0.0 } is
                Some { val } -> val
                None -> -1
        """.trimIndent()

        assertEquals(0L, eval(source))
    }

    @Test
    fun `test nat from_string - valid`() {
        val source = """
            use std::nat
            match nat::from_string { s = "42" } is
                Some { val } -> val
                None -> -1
        """.trimIndent()

        assertEquals(42L, eval(source))
    }

    @Test
    fun `test nat from_string - invalid returns None`() {
        val source = """
            use std::nat
            match nat::from_string { s = "not a number" } is
                Some { val } -> 1
                None -> 0
        """.trimIndent()

        assertEquals(0L, eval(source))
    }

    @Test
    fun `test nat from_string - negative returns None`() {
        val source = """
            use std::nat
            match nat::from_string { s = "-5" } is
                Some { val } -> 1
                None -> 0
        """.trimIndent()

        assertEquals(0L, eval(source))
    }

    @Test
    fun `test nat to_int - zero`() {
        val source = """
            use std::nat
            nat::to_int { n = 0n }
        """.trimIndent()

        assertEquals(0L, eval(source))
    }

    @Test
    fun `test nat to_int - positive`() {
        val source = """
            use std::nat
            nat::to_int { n = 42n }
        """.trimIndent()

        assertEquals(42L, eval(source))
    }

    @Test
    fun `test nat to_int - large`() {
        val source = """
            use std::nat
            nat::to_int { n = 1000000n }
        """.trimIndent()

        assertEquals(1000000L, eval(source))
    }

    @Test
    fun `test nat to_string - zero`() {
        val source = """
            use std::nat
            nat::to_string { n = 0n }
        """.trimIndent()

        assertEquals("0", eval(source))
    }

    @Test
    fun `test nat to_string - positive`() {
        val source = """
            use std::nat
            nat::to_string { n = 42n }
        """.trimIndent()

        assertEquals("42", eval(source))
    }

    @Test
    fun `test nat to_string - large`() {
        val source = """
            use std::nat
            nat::to_string { n = 12345n }
        """.trimIndent()

        assertEquals("12345", eval(source))
    }

    @Test
    fun `test nat clamp - value in range`() {
        val source = """
            use std::nat
            nat::clamp { n = 5n, min = 0n, max = 10n }
        """.trimIndent()

        assertEquals(5L, eval(source))
    }

    @Test
    fun `test nat clamp - value below min`() {
        val source = """
            use std::nat
            nat::clamp { n = 0n, min = 5n, max = 10n }
        """.trimIndent()

        assertEquals(5L, eval(source))
    }

    @Test
    fun `test nat clamp - value above max`() {
        val source = """
            use std::nat
            nat::clamp { n = 15n, min = 0n, max = 10n }
        """.trimIndent()

        assertEquals(10L, eval(source))
    }

    @Test
    fun `test nat clamp - value equals min`() {
        val source = """
            use std::nat
            nat::clamp { n = 5n, min = 5n, max = 10n }
        """.trimIndent()

        assertEquals(5L, eval(source))
    }

    @Test
    fun `test nat clamp - value equals max`() {
        val source = """
            use std::nat
            nat::clamp { n = 10n, min = 5n, max = 10n }
        """.trimIndent()

        assertEquals(10L, eval(source))
    }
}
