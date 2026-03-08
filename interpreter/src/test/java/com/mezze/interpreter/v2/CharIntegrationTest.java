package com.mezze.interpreter.v2;

import com.mezze.language.typechecker.ModuleResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Char type and operations.
 * Uses V2 unboxed values directly.
 */
class CharIntegrationTest {

    @BeforeEach
    void setUp() {
        ModuleResolver.clearCache();
    }

    // ========================================
    // Construction Tests
    // ========================================

    @Test
    void fromIntCreatesCharacterFromValidCodePoint() {
        Object result = MezzeTestHelper.run("""
            use std::char
            match char::from_int { code = 65 } is
                Some { val } -> char::to_int { c = val }
                None -> -1
            """);
        assertEquals(65L, result); // 'A' = 65
    }

    @Test
    void fromIntReturnsNoneForInvalidCodePoint() {
        Object result = MezzeTestHelper.run("""
            use std::char
            match char::from_int { code = -1 } is
                Some { val } -> 1
                None -> 0
            """);
        assertEquals(0L, result);
    }

    @Test
    void fromStringGetsFirstCharacter() {
        Object result = MezzeTestHelper.run("""
            use std::char
            match char::from_string { s = "Hello" } is
                Some { val } -> char::to_int { c = val }
                None -> -1
            """);
        assertEquals(72L, result); // 'H' = 72
    }

    @Test
    void fromStringReturnsNoneForEmptyString() {
        Object result = MezzeTestHelper.run("""
            use std::char
            match char::from_string { s = "" } is
                Some { val } -> 1
                None -> 0
            """);
        assertEquals(0L, result);
    }

    // ========================================
    // Conversion Tests
    // ========================================

    @Test
    void toIntReturnsCodePoint() {
        Object result = MezzeTestHelper.run("""
            use std::char
            match char::from_int { code = 97 } is
                Some { val } -> char::to_int { c = val }
                None -> -1
            """);
        assertEquals(97L, result); // 'a' = 97
    }

    @Test
    void toStringConvertsToSingleCharString() {
        Object result = MezzeTestHelper.run("""
            use std::char
            use std::string
            match char::from_int { code = 65 } is
                Some { val } -> string::length { s = char::to_string { c = val } }
                None -> -1
            """);
        assertEquals(1L, result);
    }

    // ========================================
    // Case Conversion Tests
    // ========================================

    @Test
    void toUpperConvertsLowercaseToUppercase() {
        Object result = MezzeTestHelper.run("""
            use std::char
            match char::from_int { code = 97 } is
                Some { val } -> char::to_int { c = char::to_upper { c = val } }
                None -> -1
            """);
        assertEquals(65L, result); // 'a'(97) -> 'A'(65)
    }

    @Test
    void toLowerConvertsUppercaseToLowercase() {
        Object result = MezzeTestHelper.run("""
            use std::char
            match char::from_int { code = 65 } is
                Some { val } -> char::to_int { c = char::to_lower { c = val } }
                None -> -1
            """);
        assertEquals(97L, result); // 'A'(65) -> 'a'(97)
    }

    // ========================================
    // Classification Tests
    // ========================================

    @Test
    void isDigitReturnsTrueForDigits() {
        Object result = MezzeTestHelper.run("""
            use std::char
            match char::from_int { code = 53 } is
                Some { val } -> char::is_digit { c = val }
                None -> False
            """);
        assertEquals(true, result); // '5' = 53
    }

    @Test
    void isDigitReturnsFalseForLetters() {
        Object result = MezzeTestHelper.run("""
            use std::char
            match char::from_int { code = 65 } is
                Some { val } -> char::is_digit { c = val }
                None -> True
            """);
        assertEquals(false, result);
    }

    @Test
    void isLetterReturnsTrueForLetters() {
        Object result = MezzeTestHelper.run("""
            use std::char
            match char::from_int { code = 97 } is
                Some { val } -> char::is_letter { c = val }
                None -> False
            """);
        assertEquals(true, result);
    }

    @Test
    void isAlphaNumericReturnsTrueForLettersAndDigits() {
        Object result = MezzeTestHelper.run("""
            use std::char
            let check_a = match char::from_int { code = 97 } is
                Some { val } -> char::is_alpha_numeric { c = val }
                None -> False
            let check_5 = match char::from_int { code = 53 } is
                Some { val } -> char::is_alpha_numeric { c = val }
                None -> False
            check_a && check_5
            """);
        assertEquals(true, result);
    }

    @Test
    void isWhitespaceReturnsTrueForSpace() {
        Object result = MezzeTestHelper.run("""
            use std::char
            match char::from_int { code = 32 } is
                Some { val } -> char::is_whitespace { c = val }
                None -> False
            """);
        assertEquals(true, result); // ' ' = 32
    }

    @Test
    void isUpperReturnsTrueForUppercase() {
        Object result = MezzeTestHelper.run("""
            use std::char
            match char::from_int { code = 65 } is
                Some { val } -> char::is_upper { c = val }
                None -> False
            """);
        assertEquals(true, result);
    }

    @Test
    void isLowerReturnsTrueForLowercase() {
        Object result = MezzeTestHelper.run("""
            use std::char
            match char::from_int { code = 97 } is
                Some { val } -> char::is_lower { c = val }
                None -> False
            """);
        assertEquals(true, result);
    }

    // ========================================
    // Comparison Tests
    // ========================================

    @Test
    void eqReturnsTrueForSameCharacter() {
        Object result = MezzeTestHelper.run("""
            use std::char
            match char::from_int { code = 65 } is
                Some { val } -> char::eq { a = val, b = val }
                None -> False
            """);
        assertEquals(true, result);
    }

    @Test
    void eqReturnsFalseForDifferentCharacters() {
        Object result = MezzeTestHelper.run("""
            use std::char
            let a_opt = char::from_int { code = 65 }
            let b_opt = char::from_int { code = 66 }
            match a_opt is
                Some { val = a_char } -> match b_opt is
                    Some { val = b_char } -> char::eq { a = a_char, b = b_char }
                    None -> True
                None -> True
            """);
        assertEquals(false, result);
    }

    @Test
    void compareReturnsCorrectOrdering() {
        Object result = MezzeTestHelper.run("""
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
            """);
        assertEquals(1L, result); // 'A' < 'B' => LT
    }

    // ========================================
    // Digit Operations Tests
    // ========================================

    @Test
    void digitToIntConvertsDecimalDigit() {
        Object result = MezzeTestHelper.run("""
            use std::char
            match char::from_int { code = 55 } is
                Some { val } -> match char::digit_to_int { c = val, radix = 10 } is
                    Some { val } -> val
                    None -> -1
                None -> -2
            """);
        assertEquals(7L, result); // '7' = 55, digit value = 7
    }

    @Test
    void digitToIntConvertsHexDigit() {
        Object result = MezzeTestHelper.run("""
            use std::char
            match char::from_int { code = 65 } is
                Some { val } -> match char::digit_to_int { c = val, radix = 16 } is
                    Some { val } -> val
                    None -> -1
                None -> -2
            """);
        assertEquals(10L, result); // 'A' in hex = 10
    }

    @Test
    void digitToIntReturnsNoneForInvalidDigit() {
        Object result = MezzeTestHelper.run("""
            use std::char
            match char::from_int { code = 71 } is
                Some { val } -> match char::digit_to_int { c = val, radix = 16 } is
                    Some { val } -> 1
                    None -> 0
                None -> -1
            """);
        assertEquals(0L, result); // 'G' is not a valid hex digit
    }
}
