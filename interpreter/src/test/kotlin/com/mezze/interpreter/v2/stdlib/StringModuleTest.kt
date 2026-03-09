package com.mezze.interpreter.v2.stdlib

import com.mezze.interpreter.v2.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for the String module in the standard library.
 * Migrated from V1 to V2 with unboxed values.
 */
class StringModuleTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `test string concat`() {
        val source = """
            use std::string
            string::concat { l = "hello", r = " world" }
        """.trimIndent()

        assertEquals("hello world", eval(source))
    }

    @Test
    fun `test string length`() {
        val source = """
            use std::string
            string::length { s = "hello" }
        """.trimIndent()

        assertEquals(5L, eval(source))
    }

    @Test
    fun `test string length - empty`() {
        val source = """
            use std::string
            string::length { s = "" }
        """.trimIndent()

        assertEquals(0L, eval(source))
    }

    @Test
    fun `test string is_empty - true`() {
        val source = """
            use std::string
            string::is_empty { s = "" }
        """.trimIndent()

        assertEquals(true, eval(source))
    }

    @Test
    fun `test string is_empty - false`() {
        val source = """
            use std::string
            string::is_empty { s = "hello" }
        """.trimIndent()

        assertEquals(false, eval(source))
    }

    @Test
    fun `test string to_upper`() {
        val source = """
            use std::string
            string::to_upper { s = "hello" }
        """.trimIndent()

        assertEquals("HELLO", eval(source))
    }

    @Test
    fun `test string to_lower`() {
        val source = """
            use std::string
            string::to_lower { s = "HELLO" }
        """.trimIndent()

        assertEquals("hello", eval(source))
    }

    @Test
    fun `test string trim`() {
        val source = """
            use std::string
            string::trim { s = "  hello  " }
        """.trimIndent()

        assertEquals("hello", eval(source))
    }

    @Test
    fun `test string reverse`() {
        val source = """
            use std::string
            string::reverse { s = "hello" }
        """.trimIndent()

        assertEquals("olleh", eval(source))
    }

    @Test
    fun `test string starts_with - true`() {
        val source = """
            use std::string
            string::starts_with { s = "hello world", prefix = "hello" }
        """.trimIndent()

        assertEquals(true, eval(source))
    }

    @Test
    fun `test string starts_with - false`() {
        val source = """
            use std::string
            string::starts_with { s = "hello world", prefix = "world" }
        """.trimIndent()

        assertEquals(false, eval(source))
    }

    @Test
    fun `test string ends_with - true`() {
        val source = """
            use std::string
            string::ends_with { s = "hello world", suffix = "world" }
        """.trimIndent()

        assertEquals(true, eval(source))
    }

    @Test
    fun `test string ends_with - false`() {
        val source = """
            use std::string
            string::ends_with { s = "hello world", suffix = "hello" }
        """.trimIndent()

        assertEquals(false, eval(source))
    }

    @Test
    fun `test string contains - true`() {
        val source = """
            use std::string
            string::contains { s = "hello world", substring = "lo wo" }
        """.trimIndent()

        assertEquals(true, eval(source))
    }

    @Test
    fun `test string contains - false`() {
        val source = """
            use std::string
            string::contains { s = "hello world", substring = "xyz" }
        """.trimIndent()

        assertEquals(false, eval(source))
    }

    @Test
    fun `test string repeat`() {
        val source = """
            use std::string
            string::repeat { s = "ab", n = 3 }
        """.trimIndent()

        assertEquals("ababab", eval(source))
    }

    @Test
    fun `test string repeat - zero times`() {
        val source = """
            use std::string
            string::repeat { s = "ab", n = 0 }
        """.trimIndent()

        assertEquals("", eval(source))
    }

    @Test
    fun `test string to_string - identity`() {
        val source = """
            use std::string
            string::to_string { s = "hello" }
        """.trimIndent()

        assertEquals("hello", eval(source))
    }

    @Test
    fun `test string split`() {
        val source = """
            use std::string
            use std::collections::seq
            let parts = string::split { s = "a,b,c", delimiter = "," }
            seq::length { xs = parts }
        """.trimIndent()

        assertEquals(3L, eval(source))
    }

    @Test
    fun `test string split - no delimiter found`() {
        val source = """
            use std::string
            use std::collections::seq
            let parts = string::split { s = "hello", delimiter = "," }
            seq::length { xs = parts }
        """.trimIndent()

        assertEquals(1L, eval(source))
    }

    @Test
    fun `test string split - empty string`() {
        val source = """
            use std::string
            use std::collections::seq
            let parts = string::split { s = "", delimiter = "," }
            seq::length { xs = parts }
        """.trimIndent()

        assertEquals(1L, eval(source))
    }

    @Test
    fun `test string join`() {
        val source = """
            use std::string
            let parts = ["a", "b", "c"]
            string::join { strings = parts, separator = "," }
        """.trimIndent()

        assertEquals("a,b,c", eval(source))
    }

    @Test
    fun `test string join - empty array`() {
        val source = """
            use std::string
            let parts: [String] = []
            string::join { strings = parts, separator = "," }
        """.trimIndent()

        assertEquals("", eval(source))
    }

    @Test
    fun `test string join - single element`() {
        val source = """
            use std::string
            let parts = ["hello"]
            string::join { strings = parts, separator = "," }
        """.trimIndent()

        assertEquals("hello", eval(source))
    }

    @Test
    fun `test string split and join roundtrip`() {
        val source = """
            use std::string
            let original = "one,two,three"
            let parts = string::split { s = original, delimiter = "," }
            string::join { strings = parts, separator = "," }
        """.trimIndent()

        assertEquals("one,two,three", eval(source))
    }

    @Test
    fun `test string substring`() {
        val source = """
            use std::string
            let s = "hello world"
            string::substring { s = s, start = 0, end = 5 }
        """.trimIndent()

        assertEquals("hello", eval(source))
    }

    @Test
    fun `test string substring - middle`() {
        val source = """
            use std::string
            let s = "hello world"
            string::substring { s = s, start = 6, end = 11 }
        """.trimIndent()

        assertEquals("world", eval(source))
    }

    @Test
    fun `test string replace`() {
        val source = """
            use std::string
            let s = "hello world world"
            string::replace { s = s, old = "world", new = "there" }
        """.trimIndent()

        assertEquals("hello there world", eval(source))
    }

    @Test
    fun `test string replace_all`() {
        val source = """
            use std::string
            let s = "hello world world"
            string::replace_all { s = s, old = "world", new = "there" }
        """.trimIndent()

        assertEquals("hello there there", eval(source))
    }

    @Test
    fun `test string char_at - found`() {
        val source = """
            use std::string
            use std::char
            let s = "hello"
            match string::char_at { s = s, i = 1 } is
                Some { val } -> char::to_int { c = val }
                None -> -1
        """.trimIndent()

        assertEquals('e'.code.toLong(), eval(source))
    }

    @Test
    fun `test string char_at - out of bounds`() {
        val source = """
            use std::string
            let s = "hello"
            match string::char_at { s = s, i = 10 } is
                Some { val } -> 1
                None -> 0
        """.trimIndent()

        assertEquals(0L, eval(source))
    }

    @Test
    fun `test string index_of - found`() {
        val source = """
            use std::string
            let s = "hello world"
            match string::index_of { s = s, sub = "world" } is
                Some { val } -> val
                None -> -1
        """.trimIndent()

        assertEquals(6L, eval(source))
    }

    @Test
    fun `test string index_of - not found`() {
        val source = """
            use std::string
            let s = "hello world"
            match string::index_of { s = s, sub = "xyz" } is
                Some { val } -> val
                None -> -1
        """.trimIndent()

        assertEquals(-1L, eval(source))
    }

    @Test
    fun `test string last_index_of - found`() {
        val source = """
            use std::string
            let s = "hello world world"
            match string::last_index_of { s = s, sub = "world" } is
                Some { val } -> val
                None -> -1
        """.trimIndent()

        assertEquals(12L, eval(source))
    }

    @Test
    fun `test string last_index_of - not found`() {
        val source = """
            use std::string
            let s = "hello world"
            match string::last_index_of { s = s, sub = "xyz" } is
                Some { val } -> val
                None -> -1
        """.trimIndent()

        assertEquals(-1L, eval(source))
    }

    @Test
    fun `test string trim_start`() {
        val source = """
            use std::string
            let s = "   hello   "
            string::trim_start { s = s }
        """.trimIndent()

        assertEquals("hello   ", eval(source))
    }

    @Test
    fun `test string trim_end`() {
        val source = """
            use std::string
            let s = "   hello   "
            string::trim_end { s = s }
        """.trimIndent()

        assertEquals("   hello", eval(source))
    }

    @Test
    fun `test string lines`() {
        val source = """
            use std::string
            use std::collections::seq
            let s = "line1\nline2\nline3"
            let lines = string::lines { s = s }
            seq::length { xs = lines }
        """.trimIndent()

        assertEquals(3L, eval(source))
    }

    @Test
    fun `test string words`() {
        val source = """
            use std::string
            use std::collections::seq
            let s = "hello world foo"
            let words = string::words { s = s }
            seq::length { xs = words }
        """.trimIndent()

        assertEquals(3L, eval(source))
    }

    @Test
    fun `test string chars`() {
        val source = """
            use std::string
            use std::collections::seq
            let s = "hello"
            let chars = string::chars { s = s }
            seq::length { xs = chars }
        """.trimIndent()

        assertEquals(5L, eval(source))
    }

    @Test
    fun `test string pad_left`() {
        val source = """
            use std::string
            use std::char
            let s = "hi"
            let opt = char::from_int { code = 48 }
            match opt is
                Some { val } -> string::pad_left { s = s, len = 5, c = val }
                None -> ""
        """.trimIndent()

        assertEquals("000hi", eval(source))
    }

    @Test
    fun `test string pad_left - already long enough`() {
        val source = """
            use std::string
            use std::char
            let s = "hello"
            let opt = char::from_int { code = 48 }
            match opt is
                Some { val } -> string::pad_left { s = s, len = 3, c = val }
                None -> ""
        """.trimIndent()

        assertEquals("hello", eval(source))
    }

    @Test
    fun `test string pad_right`() {
        val source = """
            use std::string
            use std::char
            let s = "hi"
            let opt = char::from_int { code = 46 }
            match opt is
                Some { val } -> string::pad_right { s = s, len = 5, c = val }
                None -> ""
        """.trimIndent()

        assertEquals("hi...", eval(source))
    }

    @Test
    fun `test string pad_right - already long enough`() {
        val source = """
            use std::string
            use std::char
            let s = "hello"
            let opt = char::from_int { code = 46 }
            match opt is
                Some { val } -> string::pad_right { s = s, len = 3, c = val }
                None -> ""
        """.trimIndent()

        assertEquals("hello", eval(source))
    }
}
