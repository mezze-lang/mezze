package com.mezze.interpreter.v2

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for the std::option module functions.
 * Tests cover Option manipulation, transformation, and conversion functions.
 */
class OptionInterpreterTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // ========================================
    // Basic Predicates
    // ========================================

    @Test
    fun `is_some returns true for Some`() {
        val code = """
            use std::option
            let opt: (Option Int) = Some { val = 42 }
            option::is_some { opt = opt }
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `is_some returns false for None`() {
        val code = """
            use std::option
            let opt: (Option Int) = None
            option::is_some { opt = opt }
        """
        val result = eval(code)
        assertEquals(false, result)
    }

    @Test
    fun `is_none returns true for None`() {
        val code = """
            use std::option
            let opt: (Option Int) = None
            option::is_none { opt = opt }
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `is_none returns false for Some`() {
        val code = """
            use std::option
            let opt: (Option Int) = Some { val = 42 }
            option::is_none { opt = opt }
        """
        val result = eval(code)
        assertEquals(false, result)
    }

    // ========================================
    // Extractors
    // ========================================

    @Test
    fun `get_or_else returns value for Some`() {
        val code = """
            use std::option
            let opt: (Option Int) = Some { val = 42 }
            option::get_or_else { opt = opt, def = 0 }
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `get_or_else returns default for None`() {
        val code = """
            use std::option
            let opt: (Option Int) = None
            option::get_or_else { opt = opt, def = 99 }
        """
        val result = eval(code)
        assertEquals(99L, result)
    }

    // ========================================
    // Transformation Functions
    // ========================================

    @Test
    fun `map transforms Some value`() {
        val code = """
            use std::option
            let opt: (Option Int) = Some { val = 21 }
            let doubled = option::map { opt = opt, f = { val } -> val * 2 }
            option::get_or_else { opt = doubled, def = 0 }
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `map returns None for None`() {
        val code = """
            use std::option
            let opt: (Option Int) = None
            let doubled = option::map { opt = opt, f = { val } -> val * 2 }
            option::is_none { opt = doubled }
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `flat_map chains options`() {
        val code = """
            use std::option
            let opt: (Option Int) = Some { val = 5 }
            let result = option::flat_map { opt = opt, f = { val } ->
                if val > 0 then Some { val = val * 2 } else None
            }
            option::get_or_else { opt = result, def = 0 }
        """
        val result = eval(code)
        assertEquals(10L, result)
    }

    @Test
    fun `filter keeps matching Some`() {
        val code = """
            use std::option
            let opt: (Option Int) = Some { val = 10 }
            let filtered = option::filter { opt = opt, p = { val } -> val > 5 }
            option::is_some { opt = filtered }
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `filter removes non-matching Some`() {
        val code = """
            use std::option
            let opt: (Option Int) = Some { val = 3 }
            let filtered = option::filter { opt = opt, p = { val } -> val > 5 }
            option::is_none { opt = filtered }
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    // ========================================
    // or_else
    // ========================================

    @Test
    fun `or_else returns first Some`() {
        val code = """
            use std::option
            let opt1: (Option Int) = Some { val = 1 }
            let opt2: (Option Int) = Some { val = 2 }
            let result = option::or_else { opt1 = opt1, opt2 = opt2 }
            option::get_or_else { opt = result, def = 0 }
        """
        val result = eval(code)
        assertEquals(1L, result)
    }

    @Test
    fun `or_else returns second when first is None`() {
        val code = """
            use std::option
            let opt1: (Option Int) = None
            let opt2: (Option Int) = Some { val = 2 }
            let result = option::or_else { opt1 = opt1, opt2 = opt2 }
            option::get_or_else { opt = result, def = 0 }
        """
        val result = eval(code)
        assertEquals(2L, result)
    }

    // ========================================
    // Flatten
    // ========================================

    @Test
    fun `flatten unwraps nested Some`() {
        val code = """
            use std::option
            let inner: (Option Int) = Some { val = 42 }
            let outer: (Option (Option Int)) = Some { val = inner }
            let result = option::flatten { opt = outer }
            option::get_or_else { opt = result, def = 0 }
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `flatten returns None for nested None`() {
        val code = """
            use std::option
            let inner: (Option Int) = None
            let outer: (Option (Option Int)) = Some { val = inner }
            let result = option::flatten { opt = outer }
            option::is_none { opt = result }
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    // ========================================
    // Conversion Functions
    // ========================================

    @Test
    fun `to_result converts Some to Ok`() {
        val code = """
            use std::option
            use std::result
            let opt: (Option Int) = Some { val = 42 }
            let res = option::to_result { opt = opt, error = "missing" }
            result::is_ok { result = res }
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `to_result converts None to Err`() {
        val code = """
            use std::option
            use std::result
            let opt: (Option Int) = None
            let res = option::to_result { opt = opt, error = "missing" }
            result::is_err { result = res }
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `to_list converts Some to singleton`() {
        val code = """
            use std::option
            use std::collections::seq
            let opt: (Option Int) = Some { val = 42 }
            let arr = option::to_list { opt = opt }
            seq::length { xs = arr }
        """
        val result = eval(code)
        assertEquals(1L, result)
    }

    @Test
    fun `to_list converts None to empty`() {
        val code = """
            use std::option
            use std::collections::seq
            let opt: (Option Int) = None
            let arr = option::to_list { opt = opt }
            seq::length { xs = arr }
        """
        val result = eval(code)
        assertEquals(0L, result)
    }

    // ========================================
    // Zip Operations
    // ========================================

    @Test
    fun `zip combines two Somes`() {
        val code = """
            use std::option
            let a: (Option Int) = Some { val = 1 }
            let b: (Option Str) = Some { val = "x" }
            let result = option::zip { a = a, b = b }
            option::is_some { opt = result }
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `zip returns None when first is None`() {
        val code = """
            use std::option
            let a: (Option Int) = None
            let b: (Option Str) = Some { val = "x" }
            let result = option::zip { a = a, b = b }
            option::is_none { opt = result }
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `zip_with combines values with function`() {
        val code = """
            use std::option
            let a: (Option Int) = Some { val = 3 }
            let b: (Option Int) = Some { val = 4 }
            let result = option::zip_with { a = a, b = b, f = { x, y } -> x + y }
            option::get_or_else { opt = result, def = 0 }
        """
        val result = eval(code)
        assertEquals(7L, result)
    }

    // ========================================
    // Fold
    // ========================================

    @Test
    fun `fold executes on_some for Some`() {
        val code = """
            use std::option
            let opt: (Option Int) = Some { val = 42 }
            option::fold {
                opt = opt,
                on_none = {} -> 0,
                on_some = { val } -> val * 2
            }
        """
        val result = eval(code)
        assertEquals(84L, result)
    }

    @Test
    fun `fold executes on_none for None`() {
        val code = """
            use std::option
            let opt: (Option Int) = None
            option::fold {
                opt = opt,
                on_none = {} -> 99,
                on_some = { val } -> val * 2
            }
        """
        val result = eval(code)
        assertEquals(99L, result)
    }

    // ========================================
    // map2
    // ========================================

    @Test
    fun `map2 combines two Options with function`() {
        val code = """
            use std::option
            let a: (Option Int) = Some { val = 10 }
            let b: (Option Int) = Some { val = 5 }
            let result = option::map2 { opt_a = a, opt_b = b, f = { a, b } -> a + b }
            option::get_or_else { opt = result, def = 0 }
        """
        val result = eval(code)
        assertEquals(15L, result)
    }

    @Test
    fun `map2 returns None if either is None`() {
        val code = """
            use std::option
            let a: (Option Int) = Some { val = 10 }
            let b: (Option Int) = None
            let result = option::map2 { opt_a = a, opt_b = b, f = { a, b } -> a + b }
            option::is_none { opt = result }
        """
        val result = eval(code)
        assertEquals(true, result)
    }
}
