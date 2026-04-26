package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for the std::str module functions.
 * Tests cover string manipulation, searching, splitting, padding, and conversion functions.
 */
class StringInterpreterTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // ========================================
    // Basic String Operations
    // ========================================

    @Test
    fun `concat joins two strings`() {
        val result = eval("""
            use std::str
            str::concat { l = "hello", r = " world" }
        """)
        assertEquals("hello world", result)
    }

    @Test
    fun `length returns string length`() {
        val result = eval("""
            use std::str
            str::length { s = "hello" }
        """)
        assertEquals(5L, result)
    }

    @Test
    fun `is_empty returns true for empty string`() {
        val result = eval("""
            use std::str
            str::is_empty { s = "" }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `is_empty returns false for non-empty string`() {
        val result = eval("""
            use std::str
            str::is_empty { s = "x" }
        """)
        assertEquals(false, result)
    }

    // ========================================
    // Case Conversion
    // ========================================

    @Test
    fun `to_upper converts to uppercase`() {
        val result = eval("""
            use std::str
            str::to_upper { s = "hello" }
        """)
        assertEquals("HELLO", result)
    }

    @Test
    fun `to_lower converts to lowercase`() {
        val result = eval("""
            use std::str
            str::to_lower { s = "HELLO" }
        """)
        assertEquals("hello", result)
    }

    // ========================================
    // Trim Operations
    // ========================================

    @Test
    fun `trim removes whitespace from both ends`() {
        val result = eval("""
            use std::str
            str::trim { s = "  hello  " }
        """)
        assertEquals("hello", result)
    }

    @Test
    fun `trim_start removes leading whitespace`() {
        val result = eval("""
            use std::str
            str::trim_start { s = "  hello  " }
        """)
        assertEquals("hello  ", result)
    }

    @Test
    fun `trim_end removes trailing whitespace`() {
        val result = eval("""
            use std::str
            str::trim_end { s = "  hello  " }
        """)
        assertEquals("  hello", result)
    }

    // ========================================
    // Search and Contains
    // ========================================

    @Test
    fun `starts_with returns true when prefix matches`() {
        val result = eval("""
            use std::str
            str::starts_with { s = "hello world", prefix = "hello" }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `starts_with returns false when prefix does not match`() {
        val result = eval("""
            use std::str
            str::starts_with { s = "hello world", prefix = "world" }
        """)
        assertEquals(false, result)
    }

    @Test
    fun `ends_with returns true when suffix matches`() {
        val result = eval("""
            use std::str
            str::ends_with { s = "hello world", suffix = "world" }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `contains returns true when substring exists`() {
        val result = eval("""
            use std::str
            str::contains { s = "hello world", substring = "lo wo" }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `contains returns false when substring does not exist`() {
        val result = eval("""
            use std::str
            str::contains { s = "hello world", substring = "xyz" }
        """)
        assertEquals(false, result)
    }

    // ========================================
    // Index Operations
    // ========================================

    @Test
    fun `index_of finds first occurrence`() {
        val result = eval("""
            use std::str
            match str::index_of { s = "hello", sub = "ll" } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(2L, result)
    }

    @Test
    fun `index_of returns None when not found`() {
        val result = eval("""
            use std::str
            match str::index_of { s = "hello", sub = "xyz" } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(-1L, result)
    }

    @Test
    fun `last_index_of finds last occurrence`() {
        val result = eval("""
            use std::str
            match str::last_index_of { s = "hello hello", sub = "ll" } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(8L, result)
    }

    // ========================================
    // Substring Operations
    // ========================================

    @Test
    fun `substring extracts middle portion`() {
        val result = eval("""
            use std::str
            str::substring { s = "hello world", start = 0, end = 5 }
        """)
        assertEquals("hello", result)
    }

    @Test
    fun `substring with out of bounds returns empty`() {
        val result = eval("""
            use std::str
            str::substring { s = "hi", start = 10, end = 20 }
        """)
        assertEquals("", result)
    }

    // ========================================
    // Replace Operations
    // ========================================

    @Test
    fun `replace replaces first occurrence`() {
        val result = eval("""
            use std::str
            str::replace { s = "hello world world", old = "world", new = "there" }
        """)
        assertEquals("hello there world", result)
    }

    @Test
    fun `replace_all replaces all occurrences`() {
        val result = eval("""
            use std::str
            str::replace_all { s = "hello world world", old = "world", new = "there" }
        """)
        assertEquals("hello there there", result)
    }

    // ========================================
    // Split and Join
    // ========================================

    @Test
    fun `split divides string by delimiter`() {
        val result = eval("""
            use std::str
            use std::collections::seq
            let parts = str::split { s = "a,b,c", delimiter = "," }
            seq::length { xs = parts }
        """)
        assertEquals(3L, result)
    }

    @Test
    fun `join combines strings with separator`() {
        val result = eval("""
            use std::str
            str::join { strings = ["a", "b", "c"], separator = "-" }
        """)
        assertEquals("a-b-c", result)
    }

    @Test
    fun `lines splits by newlines`() {
        val result = eval("""
            use std::str
            use std::collections::seq
            let parts = str::lines { s = "a\nb\nc" }
            seq::length { xs = parts }
        """)
        assertEquals(3L, result)
    }

    @Test
    fun `words splits by whitespace`() {
        val result = eval("""
            use std::str
            use std::collections::seq
            let parts = str::words { s = "hello   world  test" }
            seq::length { xs = parts }
        """)
        assertEquals(3L, result)
    }

    // ========================================
    // Character Operations
    // ========================================

    @Test
    fun `char_at returns character at index`() {
        val result = eval("""
            use std::str
            use std::char
            match str::char_at { s = "hello", i = 1 } is
                Some { val } -> char::to_int { c = val }
                None -> -1
        """)
        assertEquals(101L, result)  // 'e' = 101
    }

    @Test
    fun `char_at returns None for out of bounds`() {
        val result = eval("""
            use std::str
            match str::char_at { s = "hi", i = 10 } is
                Some { val } -> 1
                None -> 0
        """)
        assertEquals(0L, result)
    }

    @Test
    fun `chars converts to character array`() {
        val result = eval("""
            use std::str
            use std::collections::seq
            let cs = str::chars { s = "abc" }
            seq::length { xs = cs }
        """)
        assertEquals(3L, result)
    }

    // ========================================
    // Padding Operations
    // ========================================

    @Test
    fun `pad_left pads string to target length`() {
        val result = eval("""
            use std::str
            use std::char
            match char::from_int { code = 48 } is
                Some { val } -> str::pad_left { s = "42", len = 5, c = val }
                None -> "error"
        """)
        assertEquals("00042", result)  // '0' = 48
    }

    @Test
    fun `pad_right pads string to target length`() {
        val result = eval("""
            use std::str
            use std::char
            match char::from_int { code = 46 } is
                Some { val } -> str::pad_right { s = "hi", len = 5, c = val }
                None -> "error"
        """)
        assertEquals("hi...", result)  // '.' = 46
    }

    @Test
    fun `pad does not truncate longer strings`() {
        val result = eval("""
            use std::str
            use std::char
            match char::from_int { code = 48 } is
                Some { val } -> str::pad_left { s = "hello world", len = 5, c = val }
                None -> "error"
        """)
        assertEquals("hello world", result)
    }

    // ========================================
    // Other Operations
    // ========================================

    @Test
    fun `reverse reverses the string`() {
        val result = eval("""
            use std::str
            str::reverse { s = "hello" }
        """)
        assertEquals("olleh", result)
    }

    @Test
    fun `repeat creates repeated string`() {
        val result = eval("""
            use std::str
            str::repeat { s = "ab", n = 3 }
        """)
        assertEquals("ababab", result)
    }

    @Test
    fun `repeat with zero returns empty`() {
        val result = eval("""
            use std::str
            str::repeat { s = "ab", n = 0 }
        """)
        assertEquals("", result)
    }
}
