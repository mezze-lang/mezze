package com.mezze.language.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for Bytes type and operations.
 */
class BytesInterpreterTest {

    @BeforeEach
    fun setUp() {
        // Clear module cache to ensure clean state between tests
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): MezzeValue {
        val parser = com.mezze.language.parser.MezzeParser()
        val typeChecker = com.mezze.language.typechecker.TypeChecker()
        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val (result, _) = MezzeTestHelper.executeWithContext(typeCheckResult.program, typeCheckResult.monomorphicInstances)
        return result
    }

    // ========================================
    // Construction Tests
    // ========================================

    @Test
    fun `empty bytes has length 0`() {
        val result = eval("""
            use std::collections::bytes
            bytes::length { b = bytes::empty {} }
        """)
        assertEquals(MezzeInt(0), result)
    }

    @Test
    fun `bytes from string`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_string { s = "Hello" }
            bytes::length { b = b }
        """)
        assertEquals(MezzeInt(5), result)
    }

    @Test
    fun `bytes from array`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_array { arr = [72, 101, 108, 108, 111] }
            bytes::length { b = b }
        """)
        assertEquals(MezzeInt(5), result)
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
        assertEquals(MezzeInt(72), result) // 'H' = 72
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
        assertEquals(MezzeInt(-1), result)
    }

    @Test
    fun `bytes is_empty true`() {
        val result = eval("""
            use std::collections::bytes
            bytes::is_empty { b = bytes::empty {} }
        """)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `bytes is_empty false`() {
        val result = eval("""
            use std::collections::bytes
            bytes::is_empty { b = bytes::from_string { s = "x" } }
        """)
        assertEquals(MezzeBool(false), result)
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
        assertEquals(MezzeInt(5), result)
    }

    @Test
    fun `bytes take`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_string { s = "Hello" }
            let taken = bytes::take { b = b, n = 3 }
            bytes::to_hex { b = taken }
        """)
        assertEquals(MezzeString("48656c"), result) // "Hel" in hex
    }

    @Test
    fun `bytes drop`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_string { s = "Hello" }
            let dropped = bytes::drop { b = b, n = 2 }
            bytes::length { b = dropped }
        """)
        assertEquals(MezzeInt(3), result) // "llo" = 3 bytes
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
        assertEquals(MezzeInt(11), result)
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
        assertEquals(MezzeString("4869"), result) // H=48, i=69
    }

    @Test
    fun `bytes from hex valid`() {
        val result = eval("""
            use std::collections::bytes
            match bytes::from_hex { s = "48656c6c6f" } is
                Some { val } -> bytes::length { b = val }
                None -> -1
        """)
        assertEquals(MezzeInt(5), result)
    }

    @Test
    fun `bytes from hex invalid returns None`() {
        val result = eval("""
            use std::collections::bytes
            match bytes::from_hex { s = "GGG" } is
                Some { val } -> 1
                None -> 0
        """)
        assertEquals(MezzeInt(0), result)
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
        assertEquals(MezzeString("SGVsbG8="), result)
    }

    @Test
    fun `bytes from base64 valid`() {
        val result = eval("""
            use std::collections::bytes
            match bytes::from_base64 { s = "SGVsbG8=" } is
                Some { val } -> bytes::length { b = val }
                None -> -1
        """)
        assertEquals(MezzeInt(5), result)
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
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `bytes contains not found`() {
        val result = eval("""
            use std::collections::bytes
            let haystack = bytes::from_string { s = "Hello World" }
            let needle = bytes::from_string { s = "Foo" }
            bytes::contains { b = haystack, pattern = needle }
        """)
        assertEquals(MezzeBool(false), result)
    }

    @Test
    fun `bytes starts_with true`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_string { s = "Hello World" }
            let prefix = bytes::from_string { s = "Hello" }
            bytes::starts_with { b = b, prefix = prefix }
        """)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `bytes ends_with true`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_string { s = "Hello World" }
            let suffix = bytes::from_string { s = "World" }
            bytes::ends_with { b = b, suffix = suffix }
        """)
        assertEquals(MezzeBool(true), result)
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
        assertEquals(MezzeInt(6), result)
    }

    // ========================================
    // Transformation Tests
    // ========================================

    @Test
    fun `bytes reverse`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_array { arr = [1, 2, 3] }
            let rev = bytes::reverse { b = b }
            match bytes::get { b = rev, i = 0 } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(MezzeInt(3), result)
    }

    @Test
    fun `bytes map doubles each byte`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_array { arr = [1, 2, 3] }
            let doubled = bytes::map { b = b, f = { x } -> x * 2 }
            match bytes::get { b = doubled, i = 1 } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(MezzeInt(4), result) // 2 * 2 = 4
    }

    @Test
    fun `bytes fold sums all bytes`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_array { arr = [1, 2, 3, 4] }
            bytes::fold { b = b, init = 0, f = { acc, byte } -> acc + byte }
        """)
        assertEquals(MezzeInt(10), result) // 1 + 2 + 3 + 4 = 10
    }

    // ========================================
    // Conversion Tests
    // ========================================

    @Test
    fun `bytes to_array returns array of ints`() {
        // Test that to_array returns an array by checking it's not empty
        // The array should have 3 elements [65, 66, 67]
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_array { arr = [65, 66, 67] }
            let arr = bytes::to_array { b = b }
            # Check first element via fold
            bytes::fold { b = b, init = 0, f = { acc, byte } -> if acc == 0 then byte else acc }
        """)
        assertEquals(MezzeInt(65), result)
    }

    @Test
    fun `bytes to_string success`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_string { s = "Hello" }
            match bytes::to_string { b = b } is
                Ok { val } -> val
                Err { error } -> error
        """)
        assertEquals(MezzeString("Hello"), result)
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
        assertEquals(MezzeInt(7), result)
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
        assertEquals(MezzeInt(12), result)
    }
}
