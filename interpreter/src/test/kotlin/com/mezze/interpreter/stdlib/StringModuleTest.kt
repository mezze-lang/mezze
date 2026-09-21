package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
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
            use std::str
            str::concat { l = "hello", r = " world" }
        """.trimIndent()

        assertEquals("hello world", eval(source))
    }

    @Test
    fun `test string length`() {
        val source = """
            use std::str
            str::size { s = "hello" }
        """.trimIndent()

        assertEquals(5L, eval(source))
    }

    @Test
    fun `test string length - empty`() {
        val source = """
            use std::str
            str::size { s = "" }
        """.trimIndent()

        assertEquals(0L, eval(source))
    }

    @Test
    fun `test string is_empty - true`() {
        val source = """
            use std::str
            str::is_empty { s = "" }
        """.trimIndent()

        assertEquals(true, eval(source))
    }

    @Test
    fun `test string is_empty - false`() {
        val source = """
            use std::str
            str::is_empty { s = "hello" }
        """.trimIndent()

        assertEquals(false, eval(source))
    }

    @Test
    fun `test string to_upper`() {
        val source = """
            use std::str
            str::to_upper { s = "hello" }
        """.trimIndent()

        assertEquals("HELLO", eval(source))
    }

    @Test
    fun `test string to_lower`() {
        val source = """
            use std::str
            str::to_lower { s = "HELLO" }
        """.trimIndent()

        assertEquals("hello", eval(source))
    }

    @Test
    fun `test string trim`() {
        val source = """
            use std::str
            str::trim { s = "  hello  " }
        """.trimIndent()

        assertEquals("hello", eval(source))
    }

    @Test
    fun `test string reverse`() {
        val source = """
            use std::str
            str::reverse { s = "hello" }
        """.trimIndent()

        assertEquals("olleh", eval(source))
    }

    @Test
    fun `test string starts_with - true`() {
        val source = """
            use std::str
            str::starts_with { s = "hello world", prefix = "hello" }
        """.trimIndent()

        assertEquals(true, eval(source))
    }

    @Test
    fun `test string starts_with - false`() {
        val source = """
            use std::str
            str::starts_with { s = "hello world", prefix = "world" }
        """.trimIndent()

        assertEquals(false, eval(source))
    }

    @Test
    fun `test string ends_with - true`() {
        val source = """
            use std::str
            str::ends_with { s = "hello world", suffix = "world" }
        """.trimIndent()

        assertEquals(true, eval(source))
    }

    @Test
    fun `test string ends_with - false`() {
        val source = """
            use std::str
            str::ends_with { s = "hello world", suffix = "hello" }
        """.trimIndent()

        assertEquals(false, eval(source))
    }

    @Test
    fun `test string contains - true`() {
        val source = """
            use std::str
            str::contains { s = "hello world", substring = "lo wo" }
        """.trimIndent()

        assertEquals(true, eval(source))
    }

    @Test
    fun `test string contains - false`() {
        val source = """
            use std::str
            str::contains { s = "hello world", substring = "xyz" }
        """.trimIndent()

        assertEquals(false, eval(source))
    }

    @Test
    fun `test string repeat`() {
        val source = """
            use std::str
            str::repeat { s = "ab", n = 3 }
        """.trimIndent()

        assertEquals("ababab", eval(source))
    }

    @Test
    fun `test string repeat - zero times`() {
        val source = """
            use std::str
            str::repeat { s = "ab", n = 0 }
        """.trimIndent()

        assertEquals("", eval(source))
    }

    @Test
    fun `test string to_str - identity`() {
        val source = """
            use std::str
            str::to_str { s = "hello" }
        """.trimIndent()

        assertEquals("hello", eval(source))
    }

    @Test
    fun `test string split`() {
        val source = """
            use std::str
            use std::collections::seq
            let parts = str::split { s = "a,b,c", delimiter = "," }
            seq::size { xs = parts }
        """.trimIndent()

        assertEquals(3L, eval(source))
    }

    @Test
    fun `test string split - no delimiter found`() {
        val source = """
            use std::str
            use std::collections::seq
            let parts = str::split { s = "hello", delimiter = "," }
            seq::size { xs = parts }
        """.trimIndent()

        assertEquals(1L, eval(source))
    }

    @Test
    fun `test string split - empty string`() {
        val source = """
            use std::str
            use std::collections::seq
            let parts = str::split { s = "", delimiter = "," }
            seq::size { xs = parts }
        """.trimIndent()

        assertEquals(1L, eval(source))
    }

    @Test
    fun `test string join`() {
        val source = """
            use std::str
            let parts = ["a", "b", "c"]
            str::join { strings = parts, separator = "," }
        """.trimIndent()

        assertEquals("a,b,c", eval(source))
    }

    @Test
    fun `test string join - empty array`() {
        val source = """
            use std::str
            let parts: [Str] = []
            str::join { strings = parts, separator = "," }
        """.trimIndent()

        assertEquals("", eval(source))
    }

    @Test
    fun `test string join - single element`() {
        val source = """
            use std::str
            let parts = ["hello"]
            str::join { strings = parts, separator = "," }
        """.trimIndent()

        assertEquals("hello", eval(source))
    }

    @Test
    fun `test string split and join roundtrip`() {
        val source = """
            use std::str
            let original = "one,two,three"
            let parts = str::split { s = original, delimiter = "," }
            str::join { strings = parts, separator = "," }
        """.trimIndent()

        assertEquals("one,two,three", eval(source))
    }

    @Test
    fun `test string substring`() {
        val source = """
            use std::str
            let s = "hello world"
            str::substring { s = s, start = 0, end = 5 }
        """.trimIndent()

        assertEquals("hello", eval(source))
    }

    @Test
    fun `test string substring - middle`() {
        val source = """
            use std::str
            let s = "hello world"
            str::substring { s = s, start = 6, end = 11 }
        """.trimIndent()

        assertEquals("world", eval(source))
    }

    @Test
    fun `test string replace`() {
        val source = """
            use std::str
            let s = "hello world world"
            str::replace { s = s, old = "world", new = "there" }
        """.trimIndent()

        assertEquals("hello there world", eval(source))
    }

    @Test
    fun `test string replace_all`() {
        val source = """
            use std::str
            let s = "hello world world"
            str::replace_all { s = s, old = "world", new = "there" }
        """.trimIndent()

        assertEquals("hello there there", eval(source))
    }

    /**
     * `replace` and `replace_all` are the same operation differing only in how
     * many occurrences they take, so `new` must mean the same thing to both:
     * the text to put there.
     *
     * `replace` used to reach `String.replaceFirst`, whose SECOND argument is a
     * regex replacement template — `$` and `\` are metacharacters in it. Quoting
     * only the search side left that template live, so replacing with an
     * ordinary price threw IndexOutOfBoundsException out of the regex engine,
     * a trailing `$` or `\` threw IllegalArgumentException, and `$0` silently
     * substituted the matched text instead of itself. `replace_all` was always
     * literal, so the two disagreed.
     */
    /** `str::<op> { s = "cost: X", old = "X", new = <newLiteral> }` */
    private fun replacingX(op: String, newLiteral: String): Any =
        eval(
            """
            use std::str
            str::$op { s = "cost: X", old = "X", new = "$newLiteral" }
            """.trimIndent(),
        )

    @Test
    fun `replace puts the replacement in literally, like replace_all`() {
        val d = "${'$'}"

        // Threw IndexOutOfBoundsException: No group 5
        assertEquals("cost: ${d}5", replacingX("replace", "${d}5"))
        // Threw IllegalArgumentException: Illegal group reference
        assertEquals("cost: c$d", replacingX("replace", "c$d"))
        // Silently substituted the MATCHED text: gave "cost: X"
        assertEquals("cost: ${d}0", replacingX("replace", "${d}0"))
        // Threw IllegalArgumentException: character to be escaped is missing
        assertEquals("cost: \\", replacingX("replace", "\\\\"))

        // …and the sibling has always been literal, so the two must agree.
        assertEquals(replacingX("replace_all", "${d}5"), replacingX("replace", "${d}5"))
        assertEquals(replacingX("replace_all", "${d}0"), replacingX("replace", "${d}0"))
        assertEquals(replacingX("replace_all", "\\\\"), replacingX("replace", "\\\\"))
    }

    /** The search side stays literal too — `old` is text, not a pattern. */
    @Test
    fun `replace matches old literally, not as a regex`() {
        val source = """
            use std::str
            str::replace { s = "a.b.c", old = ".", new = "-" }
        """.trimIndent()

        assertEquals("a-b.c", eval(source), "`.` must match a literal dot, not any char")
    }

    @Test
    fun `test string char_at - found`() {
        val source = """
            use std::str
            use std::char
            let s = "hello"
            match str::char_at { s = s, i = 1 } is
                Some { val } -> char::to_int { c = val }
                None -> -1
        """.trimIndent()

        assertEquals('e'.code.toLong(), eval(source))
    }

    @Test
    fun `test string char_at - out of bounds`() {
        val source = """
            use std::str
            let s = "hello"
            match str::char_at { s = s, i = 10 } is
                Some { val } -> 1
                None -> 0
        """.trimIndent()

        assertEquals(0L, eval(source))
    }

    @Test
    fun `test string index_of - found`() {
        val source = """
            use std::str
            let s = "hello world"
            match str::index_of { s = s, sub = "world" } is
                Some { val } -> val
                None -> -1
        """.trimIndent()

        assertEquals(6L, eval(source))
    }

    @Test
    fun `test string index_of - not found`() {
        val source = """
            use std::str
            let s = "hello world"
            match str::index_of { s = s, sub = "xyz" } is
                Some { val } -> val
                None -> -1
        """.trimIndent()

        assertEquals(-1L, eval(source))
    }

    @Test
    fun `test string last_index_of - found`() {
        val source = """
            use std::str
            let s = "hello world world"
            match str::last_index_of { s = s, sub = "world" } is
                Some { val } -> val
                None -> -1
        """.trimIndent()

        assertEquals(12L, eval(source))
    }

    @Test
    fun `test string last_index_of - not found`() {
        val source = """
            use std::str
            let s = "hello world"
            match str::last_index_of { s = s, sub = "xyz" } is
                Some { val } -> val
                None -> -1
        """.trimIndent()

        assertEquals(-1L, eval(source))
    }

    @Test
    fun `test string trim_start`() {
        val source = """
            use std::str
            let s = "   hello   "
            str::trim_start { s = s }
        """.trimIndent()

        assertEquals("hello   ", eval(source))
    }

    @Test
    fun `test string trim_end`() {
        val source = """
            use std::str
            let s = "   hello   "
            str::trim_end { s = s }
        """.trimIndent()

        assertEquals("   hello", eval(source))
    }

    @Test
    fun `test string lines`() {
        val source = """
            use std::str
            use std::collections::seq
            let s = "line1\nline2\nline3"
            let lines = str::lines { s = s }
            seq::size { xs = lines }
        """.trimIndent()

        assertEquals(3L, eval(source))
    }

    @Test
    fun `test string words`() {
        val source = """
            use std::str
            use std::collections::seq
            let s = "hello world foo"
            let words = str::words { s = s }
            seq::size { xs = words }
        """.trimIndent()

        assertEquals(3L, eval(source))
    }

    @Test
    fun `test string chars`() {
        val source = """
            use std::str
            use std::collections::seq
            let s = "hello"
            let chars = str::chars { s = s }
            seq::size { xs = chars }
        """.trimIndent()

        assertEquals(5L, eval(source))
    }

    @Test
    fun `test string pad_left`() {
        val source = """
            use std::str
            use std::char
            let s = "hi"
            let opt = char::from_int { code = 48 }
            match opt is
                Some { val } -> str::pad_left { s = s, len = 5, c = val }
                None -> ""
        """.trimIndent()

        assertEquals("000hi", eval(source))
    }

    @Test
    fun `test string pad_left - already long enough`() {
        val source = """
            use std::str
            use std::char
            let s = "hello"
            let opt = char::from_int { code = 48 }
            match opt is
                Some { val } -> str::pad_left { s = s, len = 3, c = val }
                None -> ""
        """.trimIndent()

        assertEquals("hello", eval(source))
    }

    @Test
    fun `test string pad_right`() {
        val source = """
            use std::str
            use std::char
            let s = "hi"
            let opt = char::from_int { code = 46 }
            match opt is
                Some { val } -> str::pad_right { s = s, len = 5, c = val }
                None -> ""
        """.trimIndent()

        assertEquals("hi...", eval(source))
    }

    @Test
    fun `test string pad_right - already long enough`() {
        val source = """
            use std::str
            use std::char
            let s = "hello"
            let opt = char::from_int { code = 46 }
            match opt is
                Some { val } -> str::pad_right { s = s, len = 3, c = val }
                None -> ""
        """.trimIndent()

        assertEquals("hello", eval(source))
    }
}
