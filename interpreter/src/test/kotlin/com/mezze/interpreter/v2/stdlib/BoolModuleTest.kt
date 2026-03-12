package com.mezze.interpreter.v2.stdlib

import com.mezze.interpreter.v2.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for the Bool module in the standard library.
 * Migrated from V1 to V2 with unboxed values.
 */
class BoolModuleTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `test bool and function - true and true`() {
        val source = """
            use std::bool
            bool::and { l = True, r = True }
        """.trimIndent()

        assertEquals(true, eval(source))
    }

    @Test
    fun `test bool and function - true and false`() {
        val source = """
            use std::bool
            bool::and { l = True, r = False }
        """.trimIndent()

        assertEquals(false, eval(source))
    }

    @Test
    fun `test bool and function - false and false`() {
        val source = """
            use std::bool
            bool::and { l = False, r = False }
        """.trimIndent()

        assertEquals(false, eval(source))
    }

    @Test
    fun `test bool or function - true or false`() {
        val source = """
            use std::bool
            bool::or { l = True, r = False }
        """.trimIndent()

        assertEquals(true, eval(source))
    }

    @Test
    fun `test bool or function - false or false`() {
        val source = """
            use std::bool
            bool::or { l = False, r = False }
        """.trimIndent()

        assertEquals(false, eval(source))
    }

    @Test
    fun `test bool not function - not true`() {
        val source = """
            use std::bool
            bool::not { b = True }
        """.trimIndent()

        assertEquals(false, eval(source))
    }

    @Test
    fun `test bool not function - not false`() {
        val source = """
            use std::bool
            bool::not { b = False }
        """.trimIndent()

        assertEquals(true, eval(source))
    }

    @Test
    fun `test bool xor function - true xor true`() {
        val source = """
            use std::bool
            bool::xor { l = True, r = True }
        """.trimIndent()

        assertEquals(false, eval(source))
    }

    @Test
    fun `test bool xor function - true xor false`() {
        val source = """
            use std::bool
            bool::xor { l = True, r = False }
        """.trimIndent()

        assertEquals(true, eval(source))
    }

    @Test
    fun `test bool xor function - false xor false`() {
        val source = """
            use std::bool
            bool::xor { l = False, r = False }
        """.trimIndent()

        assertEquals(false, eval(source))
    }

    @Test
    fun `test bool implies function - true implies true`() {
        val source = """
            use std::bool
            bool::implies { l = True, r = True }
        """.trimIndent()

        assertEquals(true, eval(source))
    }

    @Test
    fun `test bool implies function - true implies false`() {
        val source = """
            use std::bool
            bool::implies { l = True, r = False }
        """.trimIndent()

        assertEquals(false, eval(source))
    }

    @Test
    fun `test bool implies function - false implies anything is true`() {
        val source1 = """
            use std::bool
            bool::implies { l = False, r = True }
        """.trimIndent()

        val source2 = """
            use std::bool
            bool::implies { l = False, r = False }
        """.trimIndent()

        assertEquals(true, eval(source1))
        assertEquals(true, eval(source2))
    }

    @Test
    fun `test bool iff function - true iff true`() {
        val source = """
            use std::bool
            bool::iff { l = True, r = True }
        """.trimIndent()

        assertEquals(true, eval(source))
    }

    @Test
    fun `test bool iff function - true iff false`() {
        val source = """
            use std::bool
            bool::iff { l = True, r = False }
        """.trimIndent()

        assertEquals(false, eval(source))
    }

    @Test
    fun `test bool iff function - false iff false`() {
        val source = """
            use std::bool
            bool::iff { l = False, r = False }
        """.trimIndent()

        assertEquals(true, eval(source))
    }

    @Test
    fun `test bool to_str function`() {
        val source1 = """
            use std::bool
            bool::to_str { b = True }
        """.trimIndent()

        val source2 = """
            use std::bool
            bool::to_str { b = False }
        """.trimIndent()

        assertEquals("true", eval(source1))
        assertEquals("false", eval(source2))
    }
}
