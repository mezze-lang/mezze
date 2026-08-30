package com.mezze.interpreter.stdlib

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

/**
 * Tests for std/collections/bytes module.
 */
class BytesModuleTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = com.mezze.interpreter.MezzeTestHelper.run(source)

    // ===== Construction =====

    @Test
    fun `test bytes empty`() {
        val result = eval("""
            use std::collections::bytes
            bytes::is_empty { b = bytes::empty {} }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test bytes from_str`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_str { s = "Hello" }
            bytes::size { b = b }
        """)
        assertEquals(5L, result)
    }

    @Test
    fun `test bytes from_seq`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_seq { arr = [72, 105] }
            bytes::size { b = b }
        """)
        assertEquals(2L, result)
    }

    @Test
    fun `test bytes from_hex`() {
        val result = eval("""
            use std::collections::bytes
            match bytes::from_hex { s = "48656c6c6f" } is
                Some { val } -> bytes::size { b = val }
                None -> 0
        """)
        assertEquals(5L, result)
    }

    @Test
    fun `test bytes from_hex invalid`() {
        val result = eval("""
            use std::collections::bytes
            match bytes::from_hex { s = "ZZZZ" } is
                Some { val } -> False
                None -> True
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test bytes from_base64`() {
        val result = eval("""
            use std::collections::bytes
            match bytes::from_base64 { s = "SGVsbG8=" } is
                Some { val } -> bytes::size { b = val }
                None -> 0
        """)
        assertEquals(5L, result)
    }

    @Test
    fun `test bytes from_base64 invalid`() {
        val result = eval("""
            use std::collections::bytes
            match bytes::from_base64 { s = "!!invalid!!" } is
                Some { val } -> False
                None -> True
        """)
        assertEquals(true, result)
    }

    // ===== Basic Operations =====

    @Test
    fun `test bytes length`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_str { s = "Hello World" }
            bytes::size { b = b }
        """)
        assertEquals(11L, result)
    }

    @Test
    fun `test bytes is_empty - true`() {
        val result = eval("""
            use std::collections::bytes
            bytes::is_empty { b = bytes::empty {} }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test bytes is_empty - false`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_str { s = "x" }
            bytes::is_empty { b = b }
        """)
        assertEquals(false, result)
    }

    @Test
    fun `test bytes get`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_str { s = "Hello" }
            match bytes::get { b = b, i = 0 } is
                Some { val } -> val
                None -> 0
        """)
        assertEquals(72L, result) // 'H' = 72
    }

    @Test
    fun `test bytes get out of bounds`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_str { s = "Hi" }
            match bytes::get { b = b, i = 10 } is
                Some { val } -> False
                None -> True
        """)
        assertEquals(true, result)
    }

    // ===== Slicing =====

    @Test
    fun `test bytes slice`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_str { s = "Hello World" }
            let s = bytes::slice { b = b, start = 0, end = 5 }
            bytes::size { b = s }
        """)
        assertEquals(5L, result)
    }

    @Test
    fun `test bytes take`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_str { s = "Hello World" }
            let t = bytes::take { b = b, n = 5 }
            bytes::size { b = t }
        """)
        assertEquals(5L, result)
    }

    @Test
    fun `test bytes drop`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_str { s = "Hello World" }
            let d = bytes::drop { b = b, n = 6 }
            bytes::size { b = d }
        """)
        assertEquals(5L, result)
    }

    // ===== Combining =====

    @Test
    fun `test bytes concat`() {
        val result = eval("""
            use std::collections::bytes
            let b1 = bytes::from_str { s = "Hello" }
            let b2 = bytes::from_str { s = "World" }
            let b3 = bytes::concat { b1 = b1, b2 = b2 }
            bytes::size { b = b3 }
        """)
        assertEquals(10L, result)
    }

    @Test
    fun `test bytes concat_all`() {
        val result = eval("""
            use std::collections::bytes
            let b1 = bytes::from_str { s = "A" }
            let b2 = bytes::from_str { s = "B" }
            let b3 = bytes::from_str { s = "C" }
            let bs = [b1, b2, b3]
            let combined = bytes::concat_all { bs = bs }
            bytes::size { b = combined }
        """)
        assertEquals(3L, result)
    }

    @Test
    fun `test bytes repeat`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_str { s = "Hi" }
            let r = bytes::repeat { b = b, n = 3 }
            bytes::size { b = r }
        """)
        assertEquals(6L, result)
    }

    // ===== Searching =====

    @Test
    fun `test bytes index_of - found`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_str { s = "Hello World" }
            let pattern = bytes::from_str { s = "World" }
            match bytes::index_of { b = b, pattern = pattern } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(6L, result)
    }

    @Test
    fun `test bytes index_of - not found`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_str { s = "Hello" }
            let pattern = bytes::from_str { s = "xyz" }
            match bytes::index_of { b = b, pattern = pattern } is
                Some { val } -> False
                None -> True
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test bytes contains - true`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_str { s = "Hello World" }
            let pattern = bytes::from_str { s = "World" }
            bytes::contains { b = b, pattern = pattern }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test bytes contains - false`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_str { s = "Hello" }
            let pattern = bytes::from_str { s = "xyz" }
            bytes::contains { b = b, pattern = pattern }
        """)
        assertEquals(false, result)
    }

    @Test
    fun `test bytes starts_with - true`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_str { s = "Hello World" }
            let prefix = bytes::from_str { s = "Hello" }
            bytes::starts_with { b = b, prefix = prefix }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test bytes starts_with - false`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_str { s = "Hello World" }
            let prefix = bytes::from_str { s = "World" }
            bytes::starts_with { b = b, prefix = prefix }
        """)
        assertEquals(false, result)
    }

    @Test
    fun `test bytes ends_with - true`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_str { s = "Hello World" }
            let suffix = bytes::from_str { s = "World" }
            bytes::ends_with { b = b, suffix = suffix }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test bytes ends_with - false`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_str { s = "Hello World" }
            let suffix = bytes::from_str { s = "Hello" }
            bytes::ends_with { b = b, suffix = suffix }
        """)
        assertEquals(false, result)
    }

    // ===== Transformations =====

    @Test
    fun `test bytes reverse`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_seq { arr = [1, 2, 3] }
            let r = bytes::reverse { b = b }
            match bytes::get { b = r, i = 0 } is
                Some { val } -> val
                None -> 0
        """)
        assertEquals(3L, result)
    }

    @Test
    fun `test bytes map`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_seq { arr = [1, 2, 3] }
            let m = bytes::map { b = b, f = {x} -> x + 1 }
            match bytes::get { b = m, i = 0 } is
                Some { val } -> val
                None -> 0
        """)
        assertEquals(2L, result)
    }

    @Test
    fun `test bytes fold`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_seq { arr = [1, 2, 3] }
            bytes::fold { b = b, init = 0, f = {acc, byte} -> acc + byte }
        """)
        assertEquals(6L, result)
    }

    // ===== Conversions =====

    @Test
    fun `test bytes to_str`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_str { s = "Hello" }
            match bytes::to_str { b = b } is
                Ok { val } -> val
                Err { error } -> "failed"
        """)
        assertEquals("Hello", result)
    }

    @Test
    fun `test bytes to_seq`() {
        val result = eval("""
            use std::collections::bytes
            use std::collections::seq
            let b = bytes::from_str { s = "Hi" }
            let arr = bytes::to_seq { b = b }
            seq::size { xs = arr }
        """)
        assertEquals(2L, result)
    }

    @Test
    fun `test bytes to_hex`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_str { s = "Hi" }
            bytes::to_hex { b = b }
        """)
        assertEquals("4869", result)
    }

    @Test
    fun `test bytes to_base64`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_str { s = "Hello" }
            bytes::to_base64 { b = b }
        """)
        assertEquals("SGVsbG8=", result)
    }

    @Test
    fun `test bytes roundtrip hex`() {
        val result = eval("""
            use std::collections::bytes
            let b1 = bytes::from_str { s = "Test" }
            let hex = bytes::to_hex { b = b1 }
            match bytes::from_hex { s = hex } is
                Some { val } ->
                    match bytes::to_str { b = val } is
                        Ok { val } -> val
                        Err { error } -> "failed"
                None -> "failed"
        """)
        assertEquals("Test", result)
    }

    @Test
    fun `test bytes roundtrip base64`() {
        val result = eval("""
            use std::collections::bytes
            let b1 = bytes::from_str { s = "Test" }
            let b64 = bytes::to_base64 { b = b1 }
            match bytes::from_base64 { s = b64 } is
                Some { val } ->
                    match bytes::to_str { b = val } is
                        Ok { val } -> val
                        Err { error } -> "failed"
                None -> "failed"
        """)
        assertEquals("Test", result)
    }
}
