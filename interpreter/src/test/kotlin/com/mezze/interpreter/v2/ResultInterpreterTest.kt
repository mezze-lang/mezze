package com.mezze.interpreter.v2

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for the std::result module functions.
 * Tests cover Result manipulation, transformation, and conversion functions.
 */
class ResultInterpreterTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // ========================================
    // Basic Predicates
    // ========================================

    @Test
    fun `is_ok returns true for Ok`() {
        val code = """
            use std::result
            let res: (Result Int String) = Ok { val = 42 }
            result::is_ok { result = res }
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `is_ok returns false for Err`() {
        val code = """
            use std::result
            let res: (Result Int String) = Err { error = "failed" }
            result::is_ok { result = res }
        """
        val result = eval(code)
        assertEquals(false, result)
    }

    @Test
    fun `is_err returns true for Err`() {
        val code = """
            use std::result
            let res: (Result Int String) = Err { error = "failed" }
            result::is_err { result = res }
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `is_err returns false for Ok`() {
        val code = """
            use std::result
            let res: (Result Int String) = Ok { val = 42 }
            result::is_err { result = res }
        """
        val result = eval(code)
        assertEquals(false, result)
    }

    // ========================================
    // Extractors
    // ========================================

    @Test
    fun `get_or_else returns value for Ok`() {
        val code = """
            use std::result
            let res: (Result Int String) = Ok { val = 42 }
            result::get_or_else { result = res, def = 0 }
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `get_or_else returns default for Err`() {
        val code = """
            use std::result
            let res: (Result Int String) = Err { error = "failed" }
            result::get_or_else { result = res, def = 99 }
        """
        val result = eval(code)
        assertEquals(99L, result)
    }

    // ========================================
    // Transformation Functions
    // ========================================

    @Test
    fun `map transforms Ok value`() {
        val code = """
            use std::result
            let res: (Result Int String) = Ok { val = 21 }
            let doubled = result::map { result = res, f = { val } -> val * 2 }
            result::get_or_else { result = doubled, def = 0 }
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `map preserves Err`() {
        val code = """
            use std::result
            let res: (Result Int String) = Err { error = "failed" }
            let doubled = result::map { result = res, f = { val } -> val * 2 }
            result::is_err { result = doubled }
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `map_err transforms error`() {
        val code = """
            use std::result
            use std::string
            let res: (Result Int String) = Err { error = "error" }
            let mapped = result::map_err { result = res, f = { error } -> string::to_upper { s = error } }
            match mapped is
                Ok { val } -> "ok"
                Err { error } -> error
        """
        val result = eval(code)
        assertEquals("ERROR", result)
    }

    @Test
    fun `flat_map chains results`() {
        val code = """
            use std::result
            let res: (Result Int String) = Ok { val = 5 }
            let chained = result::flat_map { result = res, f = { val } ->
                if val > 0 then Ok { val = val * 2 } else Err { error = "negative" }
            }
            result::get_or_else { result = chained, def = 0 }
        """
        val result = eval(code)
        assertEquals(10L, result)
    }

    @Test
    fun `or_else recovers from error`() {
        val code = """
            use std::result
            let res: (Result Int String) = Err { error = "first error" }
            let recovered = result::or_else { result = res, f = { error } -> Ok { val = 99 } }
            result::get_or_else { result = recovered, def = 0 }
        """
        val result = eval(code)
        assertEquals(99L, result)
    }

    // ========================================
    // Flatten
    // ========================================

    @Test
    fun `flatten unwraps nested Ok`() {
        val code = """
            use std::result
            let inner: (Result Int String) = Ok { val = 42 }
            let outer: (Result (Result Int String) String) = Ok { val = inner }
            let flat = result::flatten { result = outer }
            result::get_or_else { result = flat, def = 0 }
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `flatten propagates inner Err`() {
        val code = """
            use std::result
            let inner: (Result Int String) = Err { error = "inner" }
            let outer: (Result (Result Int String) String) = Ok { val = inner }
            let flat = result::flatten { result = outer }
            result::is_err { result = flat }
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `flatten propagates outer Err`() {
        val code = """
            use std::result
            let outer: (Result (Result Int String) String) = Err { error = "outer" }
            let flat = result::flatten { result = outer }
            result::is_err { result = flat }
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    // ========================================
    // Conversion Functions
    // ========================================

    @Test
    fun `to_option converts Ok to Some`() {
        val code = """
            use std::result
            use std::option
            let res: (Result Int String) = Ok { val = 42 }
            let opt = result::to_option { result = res }
            option::is_some { opt = opt }
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `to_option converts Err to None`() {
        val code = """
            use std::result
            use std::option
            let res: (Result Int String) = Err { error = "failed" }
            let opt = result::to_option { result = res }
            option::is_none { opt = opt }
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `from_option converts Some to Ok`() {
        val code = """
            use std::result
            let opt: (Option Int) = Some { val = 42 }
            let res = result::from_option { opt = opt, error = "missing" }
            result::is_ok { result = res }
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `from_option converts None to Err`() {
        val code = """
            use std::result
            let opt: (Option Int) = None
            let res = result::from_option { opt = opt, error = "missing" }
            result::is_err { result = res }
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `to_list converts Ok to singleton`() {
        val code = """
            use std::result
            use std::collections::array
            let res: (Result Int String) = Ok { val = 42 }
            let arr = result::to_list { result = res }
            array::length { xs = arr }
        """
        val result = eval(code)
        assertEquals(1L, result)
    }

    @Test
    fun `to_list converts Err to empty`() {
        val code = """
            use std::result
            use std::collections::array
            let res: (Result Int String) = Err { error = "failed" }
            let arr = result::to_list { result = res }
            array::length { xs = arr }
        """
        val result = eval(code)
        assertEquals(0L, result)
    }

    // ========================================
    // Zip
    // ========================================

    @Test
    fun `zip combines two Oks`() {
        val code = """
            use std::result
            let a: (Result Int String) = Ok { val = 1 }
            let b: (Result Int String) = Ok { val = 2 }
            let zipped = result::zip { a = a, b = b }
            result::is_ok { result = zipped }
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `zip returns Err when first is Err`() {
        val code = """
            use std::result
            let a: (Result Int String) = Err { error = "first" }
            let b: (Result Int String) = Ok { val = 2 }
            let zipped = result::zip { a = a, b = b }
            result::is_err { result = zipped }
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    // ========================================
    // Fold
    // ========================================

    @Test
    fun `fold executes on_ok for Ok`() {
        val code = """
            use std::result
            let res: (Result Int String) = Ok { val = 42 }
            result::fold {
                result = res,
                on_err = { error } -> 0,
                on_ok = { val } -> val * 2
            }
        """
        val result = eval(code)
        assertEquals(84L, result)
    }

    @Test
    fun `fold executes on_err for Err`() {
        val code = """
            use std::result
            use std::string
            let res: (Result Int String) = Err { error = "failed" }
            result::fold {
                result = res,
                on_err = { error } -> string::length { s = error },
                on_ok = { val } -> val
            }
        """
        val result = eval(code)
        assertEquals(6L, result)  // "failed".length = 6
    }

    // ========================================
    // map2
    // ========================================

    @Test
    fun `map2 combines two Results with function`() {
        val code = """
            use std::result
            let a: (Result Int String) = Ok { val = 10 }
            let b: (Result Int String) = Ok { val = 5 }
            let combined = result::map2 { result_a = a, result_b = b, f = { a, b } -> a + b }
            result::get_or_else { result = combined, def = 0 }
        """
        val result = eval(code)
        assertEquals(15L, result)
    }

    @Test
    fun `map2 returns Err if either is Err`() {
        val code = """
            use std::result
            let a: (Result Int String) = Ok { val = 10 }
            let b: (Result Int String) = Err { error = "failed" }
            let combined = result::map2 { result_a = a, result_b = b, f = { a, b } -> a + b }
            result::is_err { result = combined }
        """
        val result = eval(code)
        assertEquals(true, result)
    }
}
