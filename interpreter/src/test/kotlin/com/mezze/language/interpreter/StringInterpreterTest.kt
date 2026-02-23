package com.mezze.language.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for the std::string module functions.
 * Tests cover string manipulation, searching, splitting, padding, and conversion functions.
 */
class StringInterpreterTest {

    @BeforeEach
    fun setUp() {
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
    // Basic String Operations
    // ========================================

    @Test
    fun `concat joins two strings`() {
        val code = """
            use std::string
            string::concat { l = "hello", r = " world" }
        """
        val result = eval(code)
        assertEquals(MezzeString("hello world"), result)
    }

    @Test
    fun `length returns string length`() {
        val code = """
            use std::string
            string::length { s = "hello" }
        """
        val result = eval(code)
        assertEquals(MezzeInt(5), result)
    }

    @Test
    fun `is_empty returns true for empty string`() {
        val code = """
            use std::string
            string::is_empty { s = "" }
        """
        val result = eval(code)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `is_empty returns false for non-empty string`() {
        val code = """
            use std::string
            string::is_empty { s = "x" }
        """
        val result = eval(code)
        assertEquals(MezzeBool(false), result)
    }

    // ========================================
    // Case Conversion
    // ========================================

    @Test
    fun `to_upper converts to uppercase`() {
        val code = """
            use std::string
            string::to_upper { s = "hello" }
        """
        val result = eval(code)
        assertEquals(MezzeString("HELLO"), result)
    }

    @Test
    fun `to_lower converts to lowercase`() {
        val code = """
            use std::string
            string::to_lower { s = "HELLO" }
        """
        val result = eval(code)
        assertEquals(MezzeString("hello"), result)
    }

    // ========================================
    // Trim Operations
    // ========================================

    @Test
    fun `trim removes whitespace from both ends`() {
        val code = """
            use std::string
            string::trim { s = "  hello  " }
        """
        val result = eval(code)
        assertEquals(MezzeString("hello"), result)
    }

    @Test
    fun `trim_start removes leading whitespace`() {
        val code = """
            use std::string
            string::trim_start { s = "  hello  " }
        """
        val result = eval(code)
        assertEquals(MezzeString("hello  "), result)
    }

    @Test
    fun `trim_end removes trailing whitespace`() {
        val code = """
            use std::string
            string::trim_end { s = "  hello  " }
        """
        val result = eval(code)
        assertEquals(MezzeString("  hello"), result)
    }

    // ========================================
    // Search and Contains
    // ========================================

    @Test
    fun `starts_with returns true when prefix matches`() {
        val code = """
            use std::string
            string::starts_with { s = "hello world", prefix = "hello" }
        """
        val result = eval(code)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `starts_with returns false when prefix does not match`() {
        val code = """
            use std::string
            string::starts_with { s = "hello world", prefix = "world" }
        """
        val result = eval(code)
        assertEquals(MezzeBool(false), result)
    }

    @Test
    fun `ends_with returns true when suffix matches`() {
        val code = """
            use std::string
            string::ends_with { s = "hello world", suffix = "world" }
        """
        val result = eval(code)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `contains returns true when substring exists`() {
        val code = """
            use std::string
            string::contains { s = "hello world", substring = "lo wo" }
        """
        val result = eval(code)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `contains returns false when substring does not exist`() {
        val code = """
            use std::string
            string::contains { s = "hello world", substring = "xyz" }
        """
        val result = eval(code)
        assertEquals(MezzeBool(false), result)
    }

    // ========================================
    // Index Operations
    // ========================================

    @Test
    fun `index_of finds first occurrence`() {
        val code = """
            use std::string
            match string::index_of { s = "hello", sub = "ll" } is
                Some { val } -> val
                None -> -1
        """
        val result = eval(code)
        assertEquals(MezzeInt(2), result)
    }

    @Test
    fun `index_of returns None when not found`() {
        val code = """
            use std::string
            match string::index_of { s = "hello", sub = "xyz" } is
                Some { val } -> val
                None -> -1
        """
        val result = eval(code)
        assertEquals(MezzeInt(-1), result)
    }

    @Test
    fun `last_index_of finds last occurrence`() {
        val code = """
            use std::string
            match string::last_index_of { s = "hello hello", sub = "ll" } is
                Some { val } -> val
                None -> -1
        """
        val result = eval(code)
        assertEquals(MezzeInt(8), result)
    }

    // ========================================
    // Substring Operations
    // ========================================

    @Test
    fun `substring extracts middle portion`() {
        val code = """
            use std::string
            string::substring { s = "hello world", start = 0, end = 5 }
        """
        val result = eval(code)
        assertEquals(MezzeString("hello"), result)
    }

    @Test
    fun `substring with out of bounds returns empty`() {
        val code = """
            use std::string
            string::substring { s = "hi", start = 10, end = 20 }
        """
        val result = eval(code)
        assertEquals(MezzeString(""), result)
    }

    // ========================================
    // Replace Operations
    // ========================================

    @Test
    fun `replace replaces first occurrence`() {
        val code = """
            use std::string
            string::replace { s = "hello world world", old = "world", new = "there" }
        """
        val result = eval(code)
        assertEquals(MezzeString("hello there world"), result)
    }

    @Test
    fun `replace_all replaces all occurrences`() {
        val code = """
            use std::string
            string::replace_all { s = "hello world world", old = "world", new = "there" }
        """
        val result = eval(code)
        assertEquals(MezzeString("hello there there"), result)
    }

    // ========================================
    // Split and Join
    // ========================================

    @Test
    fun `split divides string by delimiter`() {
        val code = """
            use std::string
            use std::collections::array
            let parts = string::split { s = "a,b,c", delimiter = "," }
            array::length { xs = parts }
        """
        val result = eval(code)
        assertEquals(MezzeInt(3), result)
    }

    @Test
    fun `join combines strings with separator`() {
        val code = """
            use std::string
            string::join { strings = ["a", "b", "c"], separator = "-" }
        """
        val result = eval(code)
        assertEquals(MezzeString("a-b-c"), result)
    }

    @Test
    fun `lines splits by newlines`() {
        val code = """
            use std::string
            use std::collections::array
            let parts = string::lines { s = "a\nb\nc" }
            array::length { xs = parts }
        """
        val result = eval(code)
        assertEquals(MezzeInt(3), result)
    }

    @Test
    fun `words splits by whitespace`() {
        val code = """
            use std::string
            use std::collections::array
            let parts = string::words { s = "hello   world  test" }
            array::length { xs = parts }
        """
        val result = eval(code)
        assertEquals(MezzeInt(3), result)
    }

    // ========================================
    // Character Operations
    // ========================================

    @Test
    fun `char_at returns character at index`() {
        val code = """
            use std::string
            use std::char
            match string::char_at { s = "hello", i = 1 } is
                Some { val } -> char::to_int { c = val }
                None -> -1
        """
        val result = eval(code)
        assertEquals(MezzeInt(101), result)  // 'e' = 101
    }

    @Test
    fun `char_at returns None for out of bounds`() {
        val code = """
            use std::string
            match string::char_at { s = "hi", i = 10 } is
                Some { val } -> 1
                None -> 0
        """
        val result = eval(code)
        assertEquals(MezzeInt(0), result)
    }

    @Test
    fun `chars converts to character array`() {
        val code = """
            use std::string
            use std::collections::array
            let cs = string::chars { s = "abc" }
            array::length { xs = cs }
        """
        val result = eval(code)
        assertEquals(MezzeInt(3), result)
    }

    // ========================================
    // Padding Operations
    // ========================================

    @Test
    fun `pad_left pads string to target length`() {
        val code = """
            use std::string
            use std::char
            match char::from_int { code = 48 } is
                Some { val } -> string::pad_left { s = "42", len = 5, c = val }
                None -> "error"
        """
        val result = eval(code)
        assertEquals(MezzeString("00042"), result)  // '0' = 48
    }

    @Test
    fun `pad_right pads string to target length`() {
        val code = """
            use std::string
            use std::char
            match char::from_int { code = 46 } is
                Some { val } -> string::pad_right { s = "hi", len = 5, c = val }
                None -> "error"
        """
        val result = eval(code)
        assertEquals(MezzeString("hi..."), result)  // '.' = 46
    }

    @Test
    fun `pad does not truncate longer strings`() {
        val code = """
            use std::string
            use std::char
            match char::from_int { code = 48 } is
                Some { val } -> string::pad_left { s = "hello world", len = 5, c = val }
                None -> "error"
        """
        val result = eval(code)
        assertEquals(MezzeString("hello world"), result)
    }

    // ========================================
    // Other Operations
    // ========================================

    @Test
    fun `reverse reverses the string`() {
        val code = """
            use std::string
            string::reverse { s = "hello" }
        """
        val result = eval(code)
        assertEquals(MezzeString("olleh"), result)
    }

    @Test
    fun `repeat creates repeated string`() {
        val code = """
            use std::string
            string::repeat { s = "ab", n = 3 }
        """
        val result = eval(code)
        assertEquals(MezzeString("ababab"), result)
    }

    @Test
    fun `repeat with zero returns empty`() {
        val code = """
            use std::string
            string::repeat { s = "ab", n = 0 }
        """
        val result = eval(code)
        assertEquals(MezzeString(""), result)
    }
}
