package com.mezze.interpreter.v2.stdlib

import com.mezze.interpreter.v2.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for std::option module.
 * Migrated from V1 to V2 with unboxed values.
 */
class OptionModuleTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // === map ===

    @Test
    fun `test option map - Some`() {
        val source = """
            use std::option
            let opt = Some { val = 5 }
            let result = option::map { opt = opt, f = { val } -> val * 2 }
            match result is
                Some { val } -> val
                None -> -1
        """.trimIndent()

        assertEquals(10L, eval(source))
    }

    @Test
    fun `test option map - None`() {
        val source = """
            use std::option
            let opt: Option Int = None
            let result = option::map { opt = opt, f = { val } -> val * 2 }
            match result is
                Some { val } -> val
                None -> -1
        """.trimIndent()

        assertEquals(-1L, eval(source))
    }

    // === get_or_else ===

    @Test
    fun `test option get_or_else - Some`() {
        val source = """
            use std::option
            let opt = Some { val = 42 }
            option::get_or_else { opt = opt, def = 0 }
        """.trimIndent()

        assertEquals(42L, eval(source))
    }

    @Test
    fun `test option get_or_else - None`() {
        val source = """
            use std::option
            let opt: Option Int = None
            option::get_or_else { opt = opt, def = 99 }
        """.trimIndent()

        assertEquals(99L, eval(source))
    }

    // === is_some / is_none ===

    @Test
    fun `test option is_some - Some`() {
        val source = """
            use std::option
            let opt = Some { val = 1 }
            option::is_some { opt = opt }
        """.trimIndent()

        assertEquals(true, eval(source))
    }

    @Test
    fun `test option is_some - None`() {
        val source = """
            use std::option
            let opt: Option Int = None
            option::is_some { opt = opt }
        """.trimIndent()

        assertEquals(false, eval(source))
    }

    @Test
    fun `test option is_none - Some`() {
        val source = """
            use std::option
            let opt = Some { val = 1 }
            option::is_none { opt = opt }
        """.trimIndent()

        assertEquals(false, eval(source))
    }

    @Test
    fun `test option is_none - None`() {
        val source = """
            use std::option
            let opt: Option Int = None
            option::is_none { opt = opt }
        """.trimIndent()

        assertEquals(true, eval(source))
    }

    // === filter ===

    @Test
    fun `test option filter - Some passes predicate`() {
        val source = """
            use std::option
            let opt = Some { val = 10 }
            let result = option::filter { opt = opt, p = { val } -> val > 5 }
            match result is
                Some { val } -> 1
                None -> 0
        """.trimIndent()

        assertEquals(1L, eval(source))
    }

    @Test
    fun `test option filter - Some fails predicate`() {
        val source = """
            use std::option
            let opt = Some { val = 3 }
            let result = option::filter { opt = opt, p = { val } -> val > 5 }
            match result is
                Some { val } -> 1
                None -> 0
        """.trimIndent()

        assertEquals(0L, eval(source))
    }

    @Test
    fun `test option filter - None`() {
        val source = """
            use std::option
            let opt: Option Int = None
            let result = option::filter { opt = opt, p = { val } -> val > 5 }
            match result is
                Some { val } -> 1
                None -> 0
        """.trimIndent()

        assertEquals(0L, eval(source))
    }

    // === flat_map ===

    @Test
    fun `test option flat_map - Some to Some`() {
        val source = """
            use std::option
            let opt = Some { val = 5 }
            let result = option::flat_map { opt = opt, f = { val } -> Some { val = val * 2 } }
            match result is
                Some { val } -> val
                None -> -1
        """.trimIndent()

        assertEquals(10L, eval(source))
    }

    @Test
    fun `test option flat_map - Some to None`() {
        val source = """
            use std::option
            let opt = Some { val = 5 }
            let result: Option Int = option::flat_map { opt = opt, f = { val } -> None }
            match result is
                Some { val } -> val
                None -> -1
        """.trimIndent()

        assertEquals(-1L, eval(source))
    }

    @Test
    fun `test option flat_map - None`() {
        val source = """
            use std::option
            let opt: Option Int = None
            let result = option::flat_map { opt = opt, f = { val } -> Some { val = val * 2 } }
            match result is
                Some { val } -> val
                None -> -1
        """.trimIndent()

        assertEquals(-1L, eval(source))
    }

    // === or_else ===

    @Test
    fun `test option or_else - first is Some`() {
        val source = """
            use std::option
            let opt1 = Some { val = 1 }
            let opt2 = Some { val = 2 }
            let result = option::or_else { opt1 = opt1, opt2 = opt2 }
            match result is
                Some { val } -> val
                None -> -1
        """.trimIndent()

        assertEquals(1L, eval(source))
    }

    @Test
    fun `test option or_else - first is None`() {
        val source = """
            use std::option
            let opt1: Option Int = None
            let opt2 = Some { val = 2 }
            let result = option::or_else { opt1 = opt1, opt2 = opt2 }
            match result is
                Some { val } -> val
                None -> -1
        """.trimIndent()

        assertEquals(2L, eval(source))
    }

    @Test
    fun `test option or_else - both None`() {
        val source = """
            use std::option
            let opt1: Option Int = None
            let opt2: Option Int = None
            let result = option::or_else { opt1 = opt1, opt2 = opt2 }
            match result is
                Some { val } -> val
                None -> -1
        """.trimIndent()

        assertEquals(-1L, eval(source))
    }

    // === map2 ===

    @Test
    fun `test option map2 - both Some`() {
        val source = """
            use std::option
            let opt_a = Some { val = 3 }
            let opt_b = Some { val = 4 }
            let result = option::map2 { opt_a = opt_a, opt_b = opt_b, f = { a, b } -> a + b }
            match result is
                Some { val } -> val
                None -> -1
        """.trimIndent()

        assertEquals(7L, eval(source))
    }

    @Test
    fun `test option map2 - first None`() {
        val source = """
            use std::option
            let opt_a: Option Int = None
            let opt_b = Some { val = 4 }
            let result = option::map2 { opt_a = opt_a, opt_b = opt_b, f = { a, b } -> a + b }
            match result is
                Some { val } -> val
                None -> -1
        """.trimIndent()

        assertEquals(-1L, eval(source))
    }

    @Test
    fun `test option map2 - second None`() {
        val source = """
            use std::option
            let opt_a = Some { val = 3 }
            let opt_b: Option Int = None
            let result = option::map2 { opt_a = opt_a, opt_b = opt_b, f = { a, b } -> a + b }
            match result is
                Some { val } -> val
                None -> -1
        """.trimIndent()

        assertEquals(-1L, eval(source))
    }

    // === if_some ===

    @Test
    fun `test option if_some - Some executes action`() {
        val source = """
            use std::option
            let opt = Some { val = 5 }
            option::if_some { opt = opt, action = { val } -> {} }
            1
        """.trimIndent()

        assertEquals(1L, eval(source))
    }

    @Test
    fun `test option if_some - None does not execute`() {
        val source = """
            use std::option
            let opt: Option Int = None
            option::if_some { opt = opt, action = { val } -> {} }
            1
        """.trimIndent()

        assertEquals(1L, eval(source))
    }

    // === if_none ===

    @Test
    fun `test option if_none - None executes action`() {
        val source = """
            use std::option
            let opt: Option Int = None
            option::if_none { opt = opt, action = {} -> {} }
            1
        """.trimIndent()

        assertEquals(1L, eval(source))
    }

    @Test
    fun `test option if_none - Some does not execute`() {
        val source = """
            use std::option
            let opt = Some { val = 5 }
            option::if_none { opt = opt, action = {} -> {} }
            1
        """.trimIndent()

        assertEquals(1L, eval(source))
    }

    // === fold ===

    @Test
    fun `test option fold - Some`() {
        val source = """
            use std::option
            let opt = Some { val = 5 }
            option::fold {
                opt = opt,
                on_none = {} -> "empty",
                on_some = { val } -> "value"
            }
        """.trimIndent()

        assertEquals("value", eval(source))
    }

    @Test
    fun `test option fold - None`() {
        val source = """
            use std::option
            let opt: Option Int = None
            option::fold {
                opt = opt,
                on_none = {} -> "empty",
                on_some = { val } -> "value"
            }
        """.trimIndent()

        assertEquals("empty", eval(source))
    }

    // === flatten ===

    @Test
    fun `test option flatten - Some Some`() {
        val source = """
            use std::option
            let nested = Some { val = Some { val = 42 } }
            let result = option::flatten { opt = nested }
            match result is
                Some { val } -> val
                None -> -1
        """.trimIndent()

        assertEquals(42L, eval(source))
    }

    @Test
    fun `test option flatten - Some None`() {
        val source = """
            use std::option
            let inner: Option Int = None
            let nested = Some { val = inner }
            let result = option::flatten { opt = nested }
            match result is
                Some { val } -> val
                None -> -1
        """.trimIndent()

        assertEquals(-1L, eval(source))
    }

    @Test
    fun `test option flatten - None`() {
        val source = """
            use std::option
            let nested: Option (Option Int) = None
            let result = option::flatten { opt = nested }
            match result is
                Some { val } -> val
                None -> -1
        """.trimIndent()

        assertEquals(-1L, eval(source))
    }

    // === to_result ===

    @Test
    fun `test option to_result - Some becomes Ok`() {
        val source = """
            use std::option
            let opt = Some { val = 42 }
            let result = option::to_result { opt = opt, error = "not found" }
            match result is
                Ok { val } -> val
                Err { error } -> -1
        """.trimIndent()

        assertEquals(42L, eval(source))
    }

    @Test
    fun `test option to_result - None becomes Err`() {
        val source = """
            use std::option
            let opt: Option Int = None
            let result = option::to_result { opt = opt, error = "not found" }
            match result is
                Ok { val } -> "ok"
                Err { error } -> error
        """.trimIndent()

        assertEquals("not found", eval(source))
    }

    // === to_list ===

    @Test
    fun `test option to_list - Some becomes singleton list`() {
        val source = """
            use std::option
            use std::collections::seq
            let opt = Some { val = 42 }
            let xs = option::to_list { opt = opt }
            seq::length { xs = xs }
        """.trimIndent()

        assertEquals(1L, eval(source))
    }

    @Test
    fun `test option to_list - None becomes empty list`() {
        val source = """
            use std::option
            use std::collections::seq
            let opt: Option Int = None
            let xs = option::to_list { opt = opt }
            seq::length { xs = xs }
        """.trimIndent()

        assertEquals(0L, eval(source))
    }

    // === zip ===

    @Test
    fun `test option zip - both Some`() {
        val source = """
            use std::option
            let a = Some { val = 1 }
            let b = Some { val = "x" }
            let result = option::zip { a = a, b = b }
            match result is
                Some { val } -> val.fst
                None -> -1
        """.trimIndent()

        assertEquals(1L, eval(source))
    }

    @Test
    fun `test option zip - first None`() {
        val source = """
            use std::option
            let a: Option Int = None
            let b = Some { val = "x" }
            let result = option::zip { a = a, b = b }
            match result is
                Some { val } -> 1
                None -> 0
        """.trimIndent()

        assertEquals(0L, eval(source))
    }

    @Test
    fun `test option zip - second None`() {
        val source = """
            use std::option
            let a = Some { val = 1 }
            let b: Option Str = None
            let result = option::zip { a = a, b = b }
            match result is
                Some { val } -> 1
                None -> 0
        """.trimIndent()

        assertEquals(0L, eval(source))
    }

    // === zip_with ===

    @Test
    fun `test option zip_with - both Some`() {
        val source = """
            use std::option
            let a = Some { val = 3 }
            let b = Some { val = 4 }
            let result = option::zip_with { a = a, b = b, f = { x, y } -> x + y }
            match result is
                Some { val } -> val
                None -> -1
        """.trimIndent()

        assertEquals(7L, eval(source))
    }

    @Test
    fun `test option zip_with - first None`() {
        val source = """
            use std::option
            let a: Option Int = None
            let b = Some { val = 4 }
            let result = option::zip_with { a = a, b = b, f = { x, y } -> x + y }
            match result is
                Some { val } -> val
                None -> -1
        """.trimIndent()

        assertEquals(-1L, eval(source))
    }

    @Test
    fun `test option zip_with - second None`() {
        val source = """
            use std::option
            let a = Some { val = 3 }
            let b: Option Int = None
            let result = option::zip_with { a = a, b = b, f = { x, y } -> x + y }
            match result is
                Some { val } -> val
                None -> -1
        """.trimIndent()

        assertEquals(-1L, eval(source))
    }
}
