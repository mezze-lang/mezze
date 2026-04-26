package com.mezze.interpreter.stdlib

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

class CharModuleTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = com.mezze.interpreter.MezzeTestHelper.run(source)

    // === Construction ===

    @Test
    fun `test char from_int - valid ASCII`() {
        val result = eval("""
            use std::char
            match char::from_int { code = 65 } is
                Some { val } -> char::to_int { c = val }
                None -> -1
        """)
        assertEquals(65L, result) // 'A' = 65
    }

    @Test
    fun `test char from_int - invalid code point`() {
        val result = eval("""
            use std::char
            match char::from_int { code = -1 } is
                Some { val } -> 1
                None -> 0
        """)
        assertEquals(0L, result) // None case
    }

    @Test
    fun `test char from_string - valid`() {
        val result = eval("""
            use std::char
            match char::from_string { s = "Hello" } is
                Some { val } -> char::to_int { c = val }
                None -> -1
        """)
        assertEquals(72L, result) // 'H' = 72
    }

    @Test
    fun `test char from_string - empty string`() {
        val result = eval("""
            use std::char
            match char::from_string { s = "" } is
                Some { val } -> 1
                None -> 0
        """)
        assertEquals(0L, result) // None case
    }

    // === Conversion ===

    @Test
    fun `test char to_int`() {
        val result = eval("""
            use std::char
            let c = char::from_int { code = 65 }
            match c is
                Some { val } -> char::to_int { c = val }
                None -> -1
        """)
        assertEquals(65L, result)
    }

    @Test
    fun `test char to_str`() {
        val result = eval("""
            use std::char
            let c = char::from_int { code = 65 }
            match c is
                Some { val } -> char::to_str { c = val }
                None -> "error"
        """)
        assertEquals("A", result)
    }

    // === Case Conversion ===

    @Test
    fun `test char to_upper`() {
        val result = eval("""
            use std::char
            let c = char::from_int { code = 97 }
            match c is
                Some { val } -> char::to_str { c = char::to_upper { c = val } }
                None -> "error"
        """)
        assertEquals("A", result)
    }

    @Test
    fun `test char to_lower`() {
        val result = eval("""
            use std::char
            let c = char::from_int { code = 65 }
            match c is
                Some { val } -> char::to_str { c = char::to_lower { c = val } }
                None -> "error"
        """)
        assertEquals("a", result)
    }

    // === Character Classification ===

    @Test
    fun `test char is_digit - digit`() {
        val result = eval("""
            use std::char
            let c = char::from_int { code = 53 }
            match c is
                Some { val } -> char::is_digit { c = val }
                None -> False
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test char is_digit - letter`() {
        val result = eval("""
            use std::char
            let c = char::from_int { code = 65 }
            match c is
                Some { val } -> char::is_digit { c = val }
                None -> True
        """)
        assertEquals(false, result)
    }

    @Test
    fun `test char is_letter`() {
        val result = eval("""
            use std::char
            let c = char::from_int { code = 65 }
            match c is
                Some { val } -> char::is_letter { c = val }
                None -> False
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test char is_alpha_numeric - letter`() {
        val result = eval("""
            use std::char
            let c = char::from_int { code = 65 }
            match c is
                Some { val } -> char::is_alpha_numeric { c = val }
                None -> False
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test char is_alpha_numeric - digit`() {
        val result = eval("""
            use std::char
            let c = char::from_int { code = 53 }
            match c is
                Some { val } -> char::is_alpha_numeric { c = val }
                None -> False
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test char is_alpha_numeric - space`() {
        val result = eval("""
            use std::char
            let c = char::from_int { code = 32 }
            match c is
                Some { val } -> char::is_alpha_numeric { c = val }
                None -> True
        """)
        assertEquals(false, result)
    }

    @Test
    fun `test char is_whitespace`() {
        val result = eval("""
            use std::char
            let c = char::from_int { code = 32 }
            match c is
                Some { val } -> char::is_whitespace { c = val }
                None -> False
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test char is_upper`() {
        val result1 = eval("""
            use std::char
            let c1 = char::from_int { code = 65 }
            match c1 is
                Some { val } -> char::is_upper { c = val }
                None -> False
        """)
        val result2 = eval("""
            use std::char
            let c2 = char::from_int { code = 97 }
            match c2 is
                Some { val } -> char::is_upper { c = val }
                None -> True
        """)
        assertEquals(true, result1)
        assertEquals(false, result2)
    }

    @Test
    fun `test char is_lower`() {
        val result1 = eval("""
            use std::char
            let c1 = char::from_int { code = 97 }
            match c1 is
                Some { val } -> char::is_lower { c = val }
                None -> False
        """)
        val result2 = eval("""
            use std::char
            let c2 = char::from_int { code = 65 }
            match c2 is
                Some { val } -> char::is_lower { c = val }
                None -> True
        """)
        assertEquals(true, result1)
        assertEquals(false, result2)
    }

    // === Comparison ===

    @Test
    fun `test char eq - equal`() {
        val result = eval("""
            use std::char
            let c1 = char::from_int { code = 65 }
            let c2 = char::from_int { code = 65 }
            match c1 is
                Some { val = a } -> match c2 is
                    Some { val = b } -> char::eq { a = a, b = b }
                    None -> False
                None -> False
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test char eq - not equal`() {
        val result = eval("""
            use std::char
            let c1 = char::from_int { code = 65 }
            let c2 = char::from_int { code = 66 }
            match c1 is
                Some { val = a } -> match c2 is
                    Some { val = b } -> char::eq { a = a, b = b }
                    None -> True
                None -> True
        """)
        assertEquals(false, result)
    }

    // === Compare ===

    @Test
    fun `test char compare - less than`() {
        val result = eval("""
            use std::char
            let c1 = char::from_int { code = 65 }
            let c2 = char::from_int { code = 66 }
            match c1 is
                Some { val = a } -> match c2 is
                    Some { val = b } ->
                        match char::compare { a = a, b = b } is
                            LT -> 1
                            EQ -> 0
                            GT -> -1
                    None -> -2
                None -> -2
        """)
        assertEquals(1L, result) // LT
    }

    @Test
    fun `test char compare - equal`() {
        val result = eval("""
            use std::char
            let c1 = char::from_int { code = 65 }
            let c2 = char::from_int { code = 65 }
            match c1 is
                Some { val = a } -> match c2 is
                    Some { val = b } ->
                        match char::compare { a = a, b = b } is
                            LT -> 1
                            EQ -> 0
                            GT -> -1
                    None -> -2
                None -> -2
        """)
        assertEquals(0L, result) // EQ
    }

    @Test
    fun `test char compare - greater than`() {
        val result = eval("""
            use std::char
            let c1 = char::from_int { code = 66 }
            let c2 = char::from_int { code = 65 }
            match c1 is
                Some { val = a } -> match c2 is
                    Some { val = b } ->
                        match char::compare { a = a, b = b } is
                            LT -> 1
                            EQ -> 0
                            GT -> -1
                    None -> -2
                None -> -2
        """)
        assertEquals(-1L, result) // GT
    }

    // === Digit Operations ===

    @Test
    fun `test char digit_to_int - decimal digit`() {
        val result = eval("""
            use std::char
            let c = char::from_int { code = 53 }
            match c is
                Some { val } -> match char::digit_to_int { c = val, radix = 10 } is
                    Some { val } -> val
                    None -> -1
                None -> -2
        """)
        assertEquals(5L, result) // '5' = 53, digit value = 5
    }

    @Test
    fun `test char digit_to_int - hex digit`() {
        val result = eval("""
            use std::char
            let c = char::from_int { code = 65 }
            match c is
                Some { val } -> match char::digit_to_int { c = val, radix = 16 } is
                    Some { val } -> val
                    None -> -1
                None -> -2
        """)
        assertEquals(10L, result) // 'A' in hex = 10
    }

    @Test
    fun `test char digit_to_int - invalid digit`() {
        val result = eval("""
            use std::char
            let c = char::from_int { code = 71 }
            match c is
                Some { val } -> match char::digit_to_int { c = val, radix = 16 } is
                    Some { val } -> 1
                    None -> 0
                None -> -1
        """)
        assertEquals(0L, result) // 'G' is not a valid hex digit -> None
    }
}
