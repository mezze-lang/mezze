package com.mezze.language.interpreter.stdlib

import com.mezze.language.interpreter.MezzeBool
import com.mezze.language.interpreter.MezzeChar
import com.mezze.language.interpreter.MezzeInt
import com.mezze.language.interpreter.MezzeString
import com.mezze.language.interpreter.MezzeTestHelper
import com.mezze.language.interpreter.MezzeValue
import com.mezze.language.interpreter.MezzeList
import com.mezze.language.interpreter.TestUtils
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import java.nio.file.Paths

class StringModuleTest {

    private val parser = MezzeParser()
    private val typeChecker = TypeChecker()

    @BeforeEach
    fun setup() {
        TestUtils.setupProjectRoot()
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun parseTypeCheckAndRun(source: String): Map<String, MezzeValue> {
        val program = parser.parse(source)
        val projectRoot = Paths.get(System.getProperty("mezze.project.root", "."))
        val typeCheckResult = typeChecker.typeCheck(program, projectRoot)
        MezzeTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, projectRoot)
        return MezzeTestHelper.getLastExecutionContext()
    }

    @Test
    fun `test string concat`() {
        val source = """
            use std::string
            let result = string::concat { l = "hello", r = " world" }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals("hello world", (variables["result"] as MezzeString).value)
    }

    @Test
    fun `test string length`() {
        val source = """
            use std::string
            let result = string::length { s = "hello" }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(5L, (variables["result"] as MezzeInt).value)
    }

    @Test
    fun `test string length - empty`() {
        val source = """
            use std::string
            let result = string::length { s = "" }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(0L, (variables["result"] as MezzeInt).value)
    }

    @Test
    fun `test string is_empty - true`() {
        val source = """
            use std::string
            let result = string::is_empty { s = "" }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(true, (variables["result"] as MezzeBool).value)
    }

    @Test
    fun `test string is_empty - false`() {
        val source = """
            use std::string
            let result = string::is_empty { s = "hello" }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(false, (variables["result"] as MezzeBool).value)
    }

    @Test
    fun `test string to_upper`() {
        val source = """
            use std::string
            let result = string::to_upper { s = "hello" }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals("HELLO", (variables["result"] as MezzeString).value)
    }

    @Test
    fun `test string to_lower`() {
        val source = """
            use std::string
            let result = string::to_lower { s = "HELLO" }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals("hello", (variables["result"] as MezzeString).value)
    }

    @Test
    fun `test string trim`() {
        val source = """
            use std::string
            let result = string::trim { s = "  hello  " }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals("hello", (variables["result"] as MezzeString).value)
    }

    @Test
    fun `test string reverse`() {
        val source = """
            use std::string
            let result = string::reverse { s = "hello" }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals("olleh", (variables["result"] as MezzeString).value)
    }

    @Test
    fun `test string starts_with - true`() {
        val source = """
            use std::string
            let result = string::starts_with { s = "hello world", prefix = "hello" }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(true, (variables["result"] as MezzeBool).value)
    }

    @Test
    fun `test string starts_with - false`() {
        val source = """
            use std::string
            let result = string::starts_with { s = "hello world", prefix = "world" }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(false, (variables["result"] as MezzeBool).value)
    }

    @Test
    fun `test string ends_with - true`() {
        val source = """
            use std::string
            let result = string::ends_with { s = "hello world", suffix = "world" }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(true, (variables["result"] as MezzeBool).value)
    }

    @Test
    fun `test string ends_with - false`() {
        val source = """
            use std::string
            let result = string::ends_with { s = "hello world", suffix = "hello" }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(false, (variables["result"] as MezzeBool).value)
    }

    @Test
    fun `test string contains - true`() {
        val source = """
            use std::string
            let result = string::contains { s = "hello world", substring = "lo wo" }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(true, (variables["result"] as MezzeBool).value)
    }

    @Test
    fun `test string contains - false`() {
        val source = """
            use std::string
            let result = string::contains { s = "hello world", substring = "xyz" }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(false, (variables["result"] as MezzeBool).value)
    }

    @Test
    fun `test string repeat`() {
        val source = """
            use std::string
            let result = string::repeat { s = "ab", n = 3 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals("ababab", (variables["result"] as MezzeString).value)
    }

    @Test
    fun `test string repeat - zero times`() {
        val source = """
            use std::string
            let result = string::repeat { s = "ab", n = 0 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals("", (variables["result"] as MezzeString).value)
    }

    // === Additional tests for missing functions ===

    @Test
    fun `test string to_string - identity`() {
        val source = """
            use std::string
            let result = string::to_string { s = "hello" }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals("hello", (variables["result"] as MezzeString).value)
    }

    @Test
    fun `test string split`() {
        val source = """
            use std::string
            use std::collections::array
            let parts = string::split { s = "a,b,c", delimiter = "," }
            let len = array::length { xs = parts }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(3L, (variables["len"] as MezzeInt).value)
    }

    @Test
    fun `test string split - no delimiter found`() {
        val source = """
            use std::string
            use std::collections::array
            let parts = string::split { s = "hello", delimiter = "," }
            let len = array::length { xs = parts }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(1L, (variables["len"] as MezzeInt).value)
    }

    @Test
    fun `test string split - empty string`() {
        val source = """
            use std::string
            use std::collections::array
            let parts = string::split { s = "", delimiter = "," }
            let len = array::length { xs = parts }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(1L, (variables["len"] as MezzeInt).value)
    }

    @Test
    fun `test string join`() {
        val source = """
            use std::string
            let parts = ["a", "b", "c"]
            let result = string::join { strings = parts, separator = "," }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals("a,b,c", (variables["result"] as MezzeString).value)
    }

    @Test
    fun `test string join - empty array`() {
        val source = """
            use std::string
            let parts: [String] = []
            let result = string::join { strings = parts, separator = "," }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals("", (variables["result"] as MezzeString).value)
    }

    @Test
    fun `test string join - single element`() {
        val source = """
            use std::string
            let parts = ["hello"]
            let result = string::join { strings = parts, separator = "," }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals("hello", (variables["result"] as MezzeString).value)
    }

    @Test
    fun `test string split and join roundtrip`() {
        val source = """
            use std::string
            let original = "one,two,three"
            let parts = string::split { s = original, delimiter = "," }
            let rejoined = string::join { strings = parts, separator = "," }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals("one,two,three", (variables["rejoined"] as MezzeString).value)
    }

    // === substring ===

    @Test
    fun `test string substring`() {
        val source = """
            use std::string
            let s = "hello world"
            let result = string::substring { s = s, start = 0, end = 5 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals("hello", (variables["result"] as MezzeString).value)
    }

    @Test
    fun `test string substring - middle`() {
        val source = """
            use std::string
            let s = "hello world"
            let result = string::substring { s = s, start = 6, end = 11 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals("world", (variables["result"] as MezzeString).value)
    }

    // === replace ===

    @Test
    fun `test string replace`() {
        val source = """
            use std::string
            let s = "hello world world"
            let result = string::replace { s = s, old = "world", new = "there" }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals("hello there world", (variables["result"] as MezzeString).value)
    }

    @Test
    fun `test string replace_all`() {
        val source = """
            use std::string
            let s = "hello world world"
            let result = string::replace_all { s = s, old = "world", new = "there" }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals("hello there there", (variables["result"] as MezzeString).value)
    }

    // === char_at ===

    @Test
    fun `test string char_at - found`() {
        val source = """
            use std::string
            use std::char
            let s = "hello"
            let result = match string::char_at { s = s, i = 1 } is
                Some { val } -> char::to_int { c = val }
                None -> -1
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(MezzeInt('e'.code.toLong()), variables["result"])
    }

    @Test
    fun `test string char_at - out of bounds`() {
        val source = """
            use std::string
            let s = "hello"
            let is_none = match string::char_at { s = s, i = 10 } is
                Some { val } -> False
                None -> True
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(MezzeBool(true), variables["is_none"])
    }

    // === index_of ===

    @Test
    fun `test string index_of - found`() {
        val source = """
            use std::string
            let s = "hello world"
            let result = match string::index_of { s = s, sub = "world" } is
                Some { val } -> val
                None -> -1
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(MezzeInt(6), variables["result"])
    }

    @Test
    fun `test string index_of - not found`() {
        val source = """
            use std::string
            let s = "hello world"
            let result = match string::index_of { s = s, sub = "xyz" } is
                Some { val } -> val
                None -> -1
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(MezzeInt(-1), variables["result"])
    }

    // === last_index_of ===

    @Test
    fun `test string last_index_of - found`() {
        val source = """
            use std::string
            let s = "hello world world"
            let result = match string::last_index_of { s = s, sub = "world" } is
                Some { val } -> val
                None -> -1
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(MezzeInt(12), variables["result"])
    }

    @Test
    fun `test string last_index_of - not found`() {
        val source = """
            use std::string
            let s = "hello world"
            let result = match string::last_index_of { s = s, sub = "xyz" } is
                Some { val } -> val
                None -> -1
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(MezzeInt(-1), variables["result"])
    }

    // === trim_start / trim_end ===

    @Test
    fun `test string trim_start`() {
        val source = """
            use std::string
            let s = "   hello   "
            let result = string::trim_start { s = s }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals("hello   ", (variables["result"] as MezzeString).value)
    }

    @Test
    fun `test string trim_end`() {
        val source = """
            use std::string
            let s = "   hello   "
            let result = string::trim_end { s = s }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals("   hello", (variables["result"] as MezzeString).value)
    }

    // === lines ===

    @Test
    fun `test string lines`() {
        val source = """
            use std::string
            let s = "line1\nline2\nline3"
            let lines = string::lines { s = s }
            let result = __native_arraylist_length { xs = lines }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(MezzeInt(3), variables["result"])
    }

    // === words ===

    @Test
    fun `test string words`() {
        val source = """
            use std::string
            let s = "hello world foo"
            let words = string::words { s = s }
            let result = __native_arraylist_length { xs = words }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(MezzeInt(3), variables["result"])
    }

    // === chars ===

    @Test
    fun `test string chars`() {
        val source = """
            use std::string
            let s = "hello"
            let chars = string::chars { s = s }
            let result = __native_arraylist_length { xs = chars }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(MezzeInt(5), variables["result"])
    }

    // === pad_left / pad_right ===

    @Test
    fun `test string pad_left`() {
        val source = """
            use std::string
            use std::char
            let s = "hi"
            let opt = char::from_int { code = 48 }
            let result = match opt is
                Some { val } -> string::pad_left { s = s, len = 5, c = val }
                None -> ""
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals("000hi", (variables["result"] as MezzeString).value)
    }

    @Test
    fun `test string pad_left - already long enough`() {
        val source = """
            use std::string
            use std::char
            let s = "hello"
            let opt = char::from_int { code = 48 }
            let result = match opt is
                Some { val } -> string::pad_left { s = s, len = 3, c = val }
                None -> ""
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals("hello", (variables["result"] as MezzeString).value)
    }

    @Test
    fun `test string pad_right`() {
        val source = """
            use std::string
            use std::char
            let s = "hi"
            let opt = char::from_int { code = 46 }
            let result = match opt is
                Some { val } -> string::pad_right { s = s, len = 5, c = val }
                None -> ""
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals("hi...", (variables["result"] as MezzeString).value)
    }

    @Test
    fun `test string pad_right - already long enough`() {
        val source = """
            use std::string
            use std::char
            let s = "hello"
            let opt = char::from_int { code = 46 }
            let result = match opt is
                Some { val } -> string::pad_right { s = s, len = 3, c = val }
                None -> ""
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals("hello", (variables["result"] as MezzeString).value)
    }
}
