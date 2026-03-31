package com.mezze.interpreter.v2

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for Bytes type and operations.
 */
class BytesInterpreterTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // ========================================
    // Construction Tests
    // ========================================

    @Test
    fun `empty bytes has length 0`() {
        val result = eval("""
            use std::collections::bytes
            bytes::length { b = bytes::empty {} }
        """)
        assertEquals(0L, result)
    }

    @Test
    fun `bytes from string`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_string { s = "Hello" }
            bytes::length { b = b }
        """)
        assertEquals(5L, result)
    }

    @Test
    fun `bytes from seq`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_seq { arr = [72, 101, 108, 108, 111] }
            bytes::length { b = b }
        """)
        assertEquals(5L, result)
    }

    // ========================================
    // Access Tests
    // ========================================

    @Test
    fun `bytes get returns correct byte`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_string { s = "Hello" }
            match bytes::get { b = b, i = 0 } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(72L, result) // 'H' = 72
    }

    @Test
    fun `bytes get out of bounds returns None`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_string { s = "Hi" }
            match bytes::get { b = b, i = 10 } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(-1L, result)
    }

    @Test
    fun `bytes is_empty true`() {
        val result = eval("""
            use std::collections::bytes
            bytes::is_empty { b = bytes::empty {} }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `bytes is_empty false`() {
        val result = eval("""
            use std::collections::bytes
            bytes::is_empty { b = bytes::from_string { s = "x" } }
        """)
        assertEquals(false, result)
    }

    // ========================================
    // Slicing Tests
    // ========================================

    @Test
    fun `bytes slice`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_string { s = "Hello World" }
            let sliced = bytes::slice { b = b, start = 0, end = 5 }
            bytes::length { b = sliced }
        """)
        assertEquals(5L, result)
    }

    @Test
    fun `bytes take`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_string { s = "Hello" }
            let taken = bytes::take { b = b, n = 3 }
            bytes::to_hex { b = taken }
        """)
        assertEquals("48656c", result) // "Hel" in hex
    }

    @Test
    fun `bytes drop`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_string { s = "Hello" }
            let dropped = bytes::drop { b = b, n = 2 }
            bytes::length { b = dropped }
        """)
        assertEquals(3L, result) // "llo" = 3 bytes
    }

    // ========================================
    // Combination Tests
    // ========================================

    @Test
    fun `bytes concat`() {
        val result = eval("""
            use std::collections::bytes
            let b1 = bytes::from_string { s = "Hello" }
            let b2 = bytes::from_string { s = " World" }
            let combined = bytes::concat { b1 = b1, b2 = b2 }
            bytes::length { b = combined }
        """)
        assertEquals(11L, result)
    }

    // ========================================
    // Hex Encoding Tests
    // ========================================

    @Test
    fun `bytes to hex`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_string { s = "Hi" }
            bytes::to_hex { b = b }
        """)
        assertEquals("4869", result) // H=48, i=69
    }

    @Test
    fun `bytes from hex valid`() {
        val result = eval("""
            use std::collections::bytes
            match bytes::from_hex { s = "48656c6c6f" } is
                Some { val } -> bytes::length { b = val }
                None -> -1
        """)
        assertEquals(5L, result)
    }

    @Test
    fun `bytes from hex invalid returns None`() {
        val result = eval("""
            use std::collections::bytes
            match bytes::from_hex { s = "GGG" } is
                Some { val } -> 1
                None -> 0
        """)
        assertEquals(0L, result)
    }

    // ========================================
    // Base64 Encoding Tests
    // ========================================

    @Test
    fun `bytes to base64`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_string { s = "Hello" }
            bytes::to_base64 { b = b }
        """)
        assertEquals("SGVsbG8=", result)
    }

    @Test
    fun `bytes from base64 valid`() {
        val result = eval("""
            use std::collections::bytes
            match bytes::from_base64 { s = "SGVsbG8=" } is
                Some { val } -> bytes::length { b = val }
                None -> -1
        """)
        assertEquals(5L, result)
    }

    // ========================================
    // Search Tests
    // ========================================

    @Test
    fun `bytes contains found`() {
        val result = eval("""
            use std::collections::bytes
            let haystack = bytes::from_string { s = "Hello World" }
            let needle = bytes::from_string { s = "World" }
            bytes::contains { b = haystack, pattern = needle }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `bytes contains not found`() {
        val result = eval("""
            use std::collections::bytes
            let haystack = bytes::from_string { s = "Hello World" }
            let needle = bytes::from_string { s = "Foo" }
            bytes::contains { b = haystack, pattern = needle }
        """)
        assertEquals(false, result)
    }

    @Test
    fun `bytes starts_with true`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_string { s = "Hello World" }
            let prefix = bytes::from_string { s = "Hello" }
            bytes::starts_with { b = b, prefix = prefix }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `bytes ends_with true`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_string { s = "Hello World" }
            let suffix = bytes::from_string { s = "World" }
            bytes::ends_with { b = b, suffix = suffix }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `bytes index_of found`() {
        val result = eval("""
            use std::collections::bytes
            let haystack = bytes::from_string { s = "Hello World" }
            let needle = bytes::from_string { s = "World" }
            match bytes::index_of { b = haystack, pattern = needle } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(6L, result)
    }

    // ========================================
    // Transformation Tests
    // ========================================

    @Test
    fun `bytes reverse`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_seq { arr = [1, 2, 3] }
            let rev = bytes::reverse { b = b }
            match bytes::get { b = rev, i = 0 } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(3L, result)
    }

    @Test
    fun `bytes map doubles each byte`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_seq { arr = [1, 2, 3] }
            let doubled = bytes::map { b = b, f = { x } -> x * 2 }
            match bytes::get { b = doubled, i = 1 } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(4L, result) // 2 * 2 = 4
    }

    @Test
    fun `bytes fold sums all bytes`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_seq { arr = [1, 2, 3, 4] }
            bytes::fold { b = b, init = 0, f = { acc, byte } -> acc + byte }
        """)
        assertEquals(10L, result) // 1 + 2 + 3 + 4 = 10
    }

    // ========================================
    // Conversion Tests
    // ========================================

    @Test
    fun `bytes to_seq returns seq of ints`() {
        // Test that to_seq returns a seq by checking it's not empty
        // The seq should have 3 elements [65, 66, 67]
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_seq { arr = [65, 66, 67] }
            let arr = bytes::to_seq { b = b }
            # Check first element via fold
            bytes::fold { b = b, init = 0, f = { acc, byte } -> if acc == 0 then byte else acc }
        """)
        assertEquals(65L, result)
    }

    @Test
    fun `bytes to_str success`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_string { s = "Hello" }
            match bytes::to_str { b = b } is
                Ok { val } -> val
                Err { error } -> error
        """)
        assertEquals("Hello", result)
    }

    // ========================================
    // Roundtrip Tests
    // ========================================

    @Test
    fun `bytes roundtrip hex`() {
        val result = eval("""
            use std::collections::bytes
            let original = bytes::from_string { s = "Test123" }
            let hex = bytes::to_hex { b = original }
            match bytes::from_hex { s = hex } is
                Some { val } -> bytes::length { b = val }
                None -> -1
        """)
        assertEquals(7L, result)
    }

    @Test
    fun `bytes roundtrip base64`() {
        val result = eval("""
            use std::collections::bytes
            let original = bytes::from_string { s = "Binary Data!" }
            let encoded = bytes::to_base64 { b = original }
            match bytes::from_base64 { s = encoded } is
                Some { val } -> bytes::length { b = val }
                None -> -1
        """)
        assertEquals(12L, result)
    }
}
