package com.mezze.interpreter.v2.stdlib

import com.mezze.interpreter.v2.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for std::result module.
 * Migrated from V1 to V2 with unboxed values.
 */
class ResultModuleTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // === map ===

    @Test
    fun `test result map - Ok`() {
        val source = """
            use std::result
            let res: Result Int String = Ok { val = 5 }
            let mapped = result::map { result = res, f = { val } -> val * 2 }
            match mapped is
                Ok { val } -> val
                Err { error } -> -1
        """.trimIndent()

        assertEquals(10L, eval(source))
    }

    @Test
    fun `test result map - Err`() {
        val source = """
            use std::result
            let res: Result Int String = Err { error = "oops" }
            let mapped = result::map { result = res, f = { val } -> val * 2 }
            match mapped is
                Ok { val } -> "ok"
                Err { error } -> error
        """.trimIndent()

        assertEquals("oops", eval(source))
    }

    // === map_err ===

    @Test
    fun `test result map_err - Ok`() {
        val source = """
            use std::result
            let res: Result Int String = Ok { val = 5 }
            let mapped = result::map_err { result = res, f = { error } -> "wrapped: " }
            match mapped is
                Ok { val } -> val
                Err { error } -> -1
        """.trimIndent()

        assertEquals(5L, eval(source))
    }

    @Test
    fun `test result map_err - Err`() {
        val source = """
            use std::result
            let res: Result Int String = Err { error = "oops" }
            let mapped = result::map_err { result = res, f = { error } -> "wrapped" }
            match mapped is
                Ok { val } -> "ok"
                Err { error } -> error
        """.trimIndent()

        assertEquals("wrapped", eval(source))
    }

    // === get_or_else ===

    @Test
    fun `test result get_or_else - Ok`() {
        val source = """
            use std::result
            let res: Result Int String = Ok { val = 42 }
            result::get_or_else { result = res, def = 0 }
        """.trimIndent()

        assertEquals(42L, eval(source))
    }

    @Test
    fun `test result get_or_else - Err`() {
        val source = """
            use std::result
            let res: Result Int String = Err { error = "oops" }
            result::get_or_else { result = res, def = 99 }
        """.trimIndent()

        assertEquals(99L, eval(source))
    }

    // === is_ok / is_err ===

    @Test
    fun `test result is_ok - Ok`() {
        val source = """
            use std::result
            let res: Result Int String = Ok { val = 1 }
            result::is_ok { result = res }
        """.trimIndent()

        assertEquals(true, eval(source))
    }

    @Test
    fun `test result is_ok - Err`() {
        val source = """
            use std::result
            let res: Result Int String = Err { error = "oops" }
            result::is_ok { result = res }
        """.trimIndent()

        assertEquals(false, eval(source))
    }

    @Test
    fun `test result is_err - Ok`() {
        val source = """
            use std::result
            let res: Result Int String = Ok { val = 1 }
            result::is_err { result = res }
        """.trimIndent()

        assertEquals(false, eval(source))
    }

    @Test
    fun `test result is_err - Err`() {
        val source = """
            use std::result
            let res: Result Int String = Err { error = "oops" }
            result::is_err { result = res }
        """.trimIndent()

        assertEquals(true, eval(source))
    }

    // === flat_map ===

    @Test
    fun `test result flat_map - Ok to Ok`() {
        val source = """
            use std::result
            let res: Result Int String = Ok { val = 5 }
            let chained = result::flat_map { result = res, f = { val } -> Ok { val = val * 2 } }
            match chained is
                Ok { val } -> val
                Err { error } -> -1
        """.trimIndent()

        assertEquals(10L, eval(source))
    }

    @Test
    fun `test result flat_map - Ok to Err`() {
        val source = """
            use std::result
            let res: Result Int String = Ok { val = 5 }
            let chained: Result Int String = result::flat_map { result = res, f = { val } -> Err { error = "failed" } }
            match chained is
                Ok { val } -> "ok"
                Err { error } -> error
        """.trimIndent()

        assertEquals("failed", eval(source))
    }

    @Test
    fun `test result flat_map - Err`() {
        val source = """
            use std::result
            let res: Result Int String = Err { error = "original" }
            let chained = result::flat_map { result = res, f = { val } -> Ok { val = val * 2 } }
            match chained is
                Ok { val } -> "ok"
                Err { error } -> error
        """.trimIndent()

        assertEquals("original", eval(source))
    }

    // === or_else ===

    @Test
    fun `test result or_else - Ok`() {
        val source = """
            use std::result
            let res: Result Int String = Ok { val = 5 }
            let recovered = result::or_else { result = res, f = { error } -> Ok { val = 0 } }
            match recovered is
                Ok { val } -> val
                Err { error } -> -1
        """.trimIndent()

        assertEquals(5L, eval(source))
    }

    @Test
    fun `test result or_else - Err recovered`() {
        val source = """
            use std::result
            let res: Result Int String = Err { error = "oops" }
            let recovered = result::or_else { result = res, f = { error } -> Ok { val = 99 } }
            match recovered is
                Ok { val } -> val
                Err { error } -> -1
        """.trimIndent()

        assertEquals(99L, eval(source))
    }

    // === to_option ===

    @Test
    fun `test result to_option - Ok`() {
        val source = """
            use std::result
            let res: Result Int String = Ok { val = 42 }
            let opt = result::to_option { result = res }
            match opt is
                Some { val } -> val
                None -> -1
        """.trimIndent()

        assertEquals(42L, eval(source))
    }

    @Test
    fun `test result to_option - Err`() {
        val source = """
            use std::result
            let res: Result Int String = Err { error = "oops" }
            let opt = result::to_option { result = res }
            match opt is
                Some { val } -> val
                None -> -1
        """.trimIndent()

        assertEquals(-1L, eval(source))
    }

    // === from_option ===

    @Test
    fun `test result from_option - Some`() {
        val source = """
            use std::result
            let opt = Some { val = 42 }
            let res = result::from_option { opt = opt, error = "was none" }
            match res is
                Ok { val } -> val
                Err { error } -> -1
        """.trimIndent()

        assertEquals(42L, eval(source))
    }

    @Test
    fun `test result from_option - None`() {
        val source = """
            use std::result
            let opt: Option Int = None
            let res = result::from_option { opt = opt, error = "was none" }
            match res is
                Ok { val } -> "ok"
                Err { error } -> error
        """.trimIndent()

        assertEquals("was none", eval(source))
    }

    // === map2 ===

    @Test
    fun `test result map2 - both Ok`() {
        val source = """
            use std::result
            let a: Result Int String = Ok { val = 3 }
            let b: Result Int String = Ok { val = 4 }
            let combined = result::map2 { result_a = a, result_b = b, f = { a, b } -> a + b }
            match combined is
                Ok { val } -> val
                Err { error } -> -1
        """.trimIndent()

        assertEquals(7L, eval(source))
    }

    @Test
    fun `test result map2 - first Err`() {
        val source = """
            use std::result
            let a: Result Int String = Err { error = "first failed" }
            let b: Result Int String = Ok { val = 4 }
            let combined = result::map2 { result_a = a, result_b = b, f = { a, b } -> a + b }
            match combined is
                Ok { val } -> "ok"
                Err { error } -> error
        """.trimIndent()

        assertEquals("first failed", eval(source))
    }

    @Test
    fun `test result map2 - second Err`() {
        val source = """
            use std::result
            let a: Result Int String = Ok { val = 3 }
            let b: Result Int String = Err { error = "second failed" }
            let combined = result::map2 { result_a = a, result_b = b, f = { a, b } -> a + b }
            match combined is
                Ok { val } -> "ok"
                Err { error } -> error
        """.trimIndent()

        assertEquals("second failed", eval(source))
    }

    // === if_ok ===

    @Test
    fun `test result if_ok - Ok executes action`() {
        val source = """
            use std::result
            let res: Result Int String = Ok { val = 5 }
            result::if_ok { result = res, action = { val } -> {} }
            1
        """.trimIndent()

        assertEquals(1L, eval(source))
    }

    @Test
    fun `test result if_ok - Err does not execute`() {
        val source = """
            use std::result
            let res: Result Int String = Err { error = "oops" }
            result::if_ok { result = res, action = { val } -> {} }
            1
        """.trimIndent()

        assertEquals(1L, eval(source))
    }

    // === if_err ===

    @Test
    fun `test result if_err - Err executes action`() {
        val source = """
            use std::result
            let res: Result Int String = Err { error = "oops" }
            result::if_err { result = res, action = { error } -> {} }
            1
        """.trimIndent()

        assertEquals(1L, eval(source))
    }

    @Test
    fun `test result if_err - Ok does not execute`() {
        val source = """
            use std::result
            let res: Result Int String = Ok { val = 5 }
            result::if_err { result = res, action = { error } -> {} }
            1
        """.trimIndent()

        assertEquals(1L, eval(source))
    }

    // === fold ===

    @Test
    fun `test result fold - Ok`() {
        val source = """
            use std::result
            let res: Result Int String = Ok { val = 5 }
            result::fold {
                result = res,
                on_err = { error } -> "error",
                on_ok = { val } -> "success"
            }
        """.trimIndent()

        assertEquals("success", eval(source))
    }

    @Test
    fun `test result fold - Err`() {
        val source = """
            use std::result
            let res: Result Int String = Err { error = "oops" }
            result::fold {
                result = res,
                on_err = { error } -> "error",
                on_ok = { val } -> "success"
            }
        """.trimIndent()

        assertEquals("error", eval(source))
    }

    // === flatten ===

    @Test
    fun `test result flatten - Ok Ok`() {
        val source = """
            use std::result
            let inner: Result Int String = Ok { val = 42 }
            let nested: Result (Result Int String) String = Ok { val = inner }
            let result = result::flatten { result = nested }
            match result is
                Ok { val } -> val
                Err { error } -> -1
        """.trimIndent()

        assertEquals(42L, eval(source))
    }

    @Test
    fun `test result flatten - Ok Err`() {
        val source = """
            use std::result
            let inner: Result Int String = Err { error = "inner error" }
            let nested: Result (Result Int String) String = Ok { val = inner }
            let result = result::flatten { result = nested }
            match result is
                Ok { val } -> "ok"
                Err { error } -> error
        """.trimIndent()

        assertEquals("inner error", eval(source))
    }

    @Test
    fun `test result flatten - Err`() {
        val source = """
            use std::result
            let nested: Result (Result Int String) String = Err { error = "outer error" }
            let result = result::flatten { result = nested }
            match result is
                Ok { val } -> "ok"
                Err { error } -> error
        """.trimIndent()

        assertEquals("outer error", eval(source))
    }

    // === to_list ===

    @Test
    fun `test result to_list - Ok becomes singleton list`() {
        val source = """
            use std::result
            use std::collections::array
            let res: Result Int String = Ok { val = 42 }
            let xs = result::to_list { result = res }
            array::length { xs = xs }
        """.trimIndent()

        assertEquals(1L, eval(source))
    }

    @Test
    fun `test result to_list - Err becomes empty list`() {
        val source = """
            use std::result
            use std::collections::array
            let res: Result Int String = Err { error = "oops" }
            let xs = result::to_list { result = res }
            array::length { xs = xs }
        """.trimIndent()

        assertEquals(0L, eval(source))
    }

    // === zip ===

    @Test
    fun `test result zip - both Ok`() {
        val source = """
            use std::result
            let a: Result Int String = Ok { val = 1 }
            let b: Result String String = Ok { val = "x" }
            let result = result::zip { a = a, b = b }
            match result is
                Ok { val } -> val.fst
                Err { error } -> -1
        """.trimIndent()

        assertEquals(1L, eval(source))
    }

    @Test
    fun `test result zip - first Err`() {
        val source = """
            use std::result
            let a: Result Int String = Err { error = "first failed" }
            let b: Result String String = Ok { val = "x" }
            let result = result::zip { a = a, b = b }
            match result is
                Ok { val } -> "ok"
                Err { error } -> error
        """.trimIndent()

        assertEquals("first failed", eval(source))
    }

    @Test
    fun `test result zip - second Err`() {
        val source = """
            use std::result
            let a: Result Int String = Ok { val = 1 }
            let b: Result String String = Err { error = "second failed" }
            let result = result::zip { a = a, b = b }
            match result is
                Ok { val } -> "ok"
                Err { error } -> error
        """.trimIndent()

        assertEquals("second failed", eval(source))
    }
}
