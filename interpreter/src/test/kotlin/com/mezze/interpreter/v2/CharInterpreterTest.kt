package com.mezze.interpreter.v2

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for Char type and operations.
 */
class CharInterpreterTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // ========================================
    // Construction Tests
    // ========================================

    @Test
    fun `from_int creates character from valid code point`() {
        val result = eval("""
            use std::char
            match char::from_int { code = 65 } is
                Some { val } -> char::to_int { c = val }
                None -> -1
        """)
        assertEquals(65L, result) // 'A' = 65
    }

    @Test
    fun `from_int returns None for invalid code point`() {
        val result = eval("""
            use std::char
            match char::from_int { code = -1 } is
                Some { val } -> 1
                None -> 0
        """)
        assertEquals(0L, result)
    }

    @Test
    fun `from_string gets first character`() {
        val result = eval("""
            use std::char
            match char::from_string { s = "Hello" } is
                Some { val } -> char::to_int { c = val }
                None -> -1
        """)
        assertEquals(72L, result) // 'H' = 72
    }

    @Test
    fun `from_string returns None for empty string`() {
        val result = eval("""
            use std::char
            match char::from_string { s = "" } is
                Some { val } -> 1
                None -> 0
        """)
        assertEquals(0L, result)
    }

    // ========================================
    // Conversion Tests
    // ========================================

    @Test
    fun `to_int returns code point`() {
        val result = eval("""
            use std::char
            match char::from_int { code = 97 } is
                Some { val } -> char::to_int { c = val }
                None -> -1
        """)
        assertEquals(97L, result) // 'a' = 97
    }

    @Test
    fun `to_str converts to single-char string`() {
        val result = eval("""
            use std::char
            use std::string
            match char::from_int { code = 65 } is
                Some { val } -> string::length { s = char::to_str { c = val } }
                None -> -1
        """)
        assertEquals(1L, result)
    }

    // ========================================
    // Case Conversion Tests
    // ========================================

    @Test
    fun `to_upper converts lowercase to uppercase`() {
        val result = eval("""
            use std::char
            match char::from_int { code = 97 } is
                Some { val } -> char::to_int { c = char::to_upper { c = val } }
                None -> -1
        """)
        assertEquals(65L, result) // 'a'(97) -> 'A'(65)
    }

    @Test
    fun `to_lower converts uppercase to lowercase`() {
        val result = eval("""
            use std::char
            match char::from_int { code = 65 } is
                Some { val } -> char::to_int { c = char::to_lower { c = val } }
                None -> -1
        """)
        assertEquals(97L, result) // 'A'(65) -> 'a'(97)
    }

    // ========================================
    // Classification Tests
    // ========================================

    @Test
    fun `is_digit returns true for digits`() {
        val result = eval("""
            use std::char
            match char::from_int { code = 53 } is
                Some { val } -> char::is_digit { c = val }
                None -> False
        """)
        assertEquals(true, result) // '5' = 53
    }

    @Test
    fun `is_digit returns false for letters`() {
        val result = eval("""
            use std::char
            match char::from_int { code = 65 } is
                Some { val } -> char::is_digit { c = val }
                None -> True
        """)
        assertEquals(false, result)
    }

    @Test
    fun `is_letter returns true for letters`() {
        val result = eval("""
            use std::char
            match char::from_int { code = 97 } is
                Some { val } -> char::is_letter { c = val }
                None -> False
        """)
        assertEquals(true, result)
    }

    @Test
    fun `is_alpha_numeric returns true for letters and digits`() {
        val result = eval("""
            use std::char
            let check_a = match char::from_int { code = 97 } is
                Some { val } -> char::is_alpha_numeric { c = val }
                None -> False
            let check_5 = match char::from_int { code = 53 } is
                Some { val } -> char::is_alpha_numeric { c = val }
                None -> False
            check_a && check_5
        """)
        assertEquals(true, result)
    }

    @Test
    fun `is_whitespace returns true for space`() {
        val result = eval("""
            use std::char
            match char::from_int { code = 32 } is
                Some { val } -> char::is_whitespace { c = val }
                None -> False
        """)
        assertEquals(true, result) // ' ' = 32
    }

    @Test
    fun `is_upper returns true for uppercase`() {
        val result = eval("""
            use std::char
            match char::from_int { code = 65 } is
                Some { val } -> char::is_upper { c = val }
                None -> False
        """)
        assertEquals(true, result)
    }

    @Test
    fun `is_lower returns true for lowercase`() {
        val result = eval("""
            use std::char
            match char::from_int { code = 97 } is
                Some { val } -> char::is_lower { c = val }
                None -> False
        """)
        assertEquals(true, result)
    }

    // ========================================
    // Comparison Tests
    // ========================================

    @Test
    fun `eq returns true for same character`() {
        val result = eval("""
            use std::char
            match char::from_int { code = 65 } is
                Some { val } -> char::eq { a = val, b = val }
                None -> False
        """)
        assertEquals(true, result)
    }

    @Test
    fun `eq returns false for different characters`() {
        val result = eval("""
            use std::char
            let a_opt = char::from_int { code = 65 }
            let b_opt = char::from_int { code = 66 }
            match a_opt is
                Some { val = a_char } -> match b_opt is
                    Some { val = b_char } -> char::eq { a = a_char, b = b_char }
                    None -> True
                None -> True
        """)
        assertEquals(false, result)
    }

    @Test
    fun `compare returns correct ordering`() {
        val result = eval("""
            use std::char
            let a_opt = char::from_int { code = 65 }
            let b_opt = char::from_int { code = 66 }
            match a_opt is
                Some { val = a_char } -> match b_opt is
                    Some { val = b_char } ->
                        match char::compare { a = a_char, b = b_char } is
                            LT -> 1
                            EQ -> 0
                            GT -> -1
                    None -> -2
                None -> -2
        """)
        assertEquals(1L, result) // 'A' < 'B' => LT
    }

    // ========================================
    // Digit Operations Tests
    // ========================================

    @Test
    fun `digit_to_int converts decimal digit`() {
        val result = eval("""
            use std::char
            match char::from_int { code = 55 } is
                Some { val } -> match char::digit_to_int { c = val, radix = 10 } is
                    Some { val } -> val
                    None -> -1
                None -> -2
        """)
        assertEquals(7L, result) // '7' = 55, digit value = 7
    }

    @Test
    fun `digit_to_int converts hex digit`() {
        val result = eval("""
            use std::char
            match char::from_int { code = 65 } is
                Some { val } -> match char::digit_to_int { c = val, radix = 16 } is
                    Some { val } -> val
                    None -> -1
                None -> -2
        """)
        assertEquals(10L, result) // 'A' in hex = 10
    }

    @Test
    fun `digit_to_int returns None for invalid digit`() {
        val result = eval("""
            use std::char
            match char::from_int { code = 71 } is
                Some { val } -> match char::digit_to_int { c = val, radix = 16 } is
                    Some { val } -> 1
                    None -> 0
                None -> -1
        """)
        assertEquals(0L, result) // 'G' is not a valid hex digit
    }
}
