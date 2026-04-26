package com.mezze.interpreter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the std::nat module functions.
 * Uses V2 unboxed values directly.
 */
class NatIntegrationTest {
    // ========================================
    // Basic Operations
    // ========================================

    @Test
    void minReturnsSmallerValue() {
        Object result = MezzeTestHelper.run("""
            use std::nat
            nat::min { l = 3n, r = 7n }
            """);
        assertEquals(3L, result);
    }

    @Test
    void maxReturnsLargerValue() {
        Object result = MezzeTestHelper.run("""
            use std::nat
            nat::max { l = 3n, r = 7n }
            """);
        assertEquals(7L, result);
    }

    @Test
    void isZeroReturnsTrueForZero() {
        Object result = MezzeTestHelper.run("""
            use std::nat
            nat::is_zero { n = 0n }
            """);
        assertEquals(true, result);
    }

    @Test
    void isZeroReturnsFalseForNonZero() {
        Object result = MezzeTestHelper.run("""
            use std::nat
            nat::is_zero { n = 5n }
            """);
        assertEquals(false, result);
    }

    @Test
    void isEvenReturnsTrueForEvenNumber() {
        Object result = MezzeTestHelper.run("""
            use std::nat
            nat::is_even { n = 4n }
            """);
        assertEquals(true, result);
    }

    @Test
    void isEvenReturnsFalseForOddNumber() {
        Object result = MezzeTestHelper.run("""
            use std::nat
            nat::is_even { n = 5n }
            """);
        assertEquals(false, result);
    }

    @Test
    void isOddReturnsTrueForOddNumber() {
        Object result = MezzeTestHelper.run("""
            use std::nat
            nat::is_odd { n = 5n }
            """);
        assertEquals(true, result);
    }

    @Test
    void isOddReturnsFalseForEvenNumber() {
        Object result = MezzeTestHelper.run("""
            use std::nat
            nat::is_odd { n = 4n }
            """);
        assertEquals(false, result);
    }

    // ========================================
    // Subtraction to Int
    // ========================================

    @Test
    void subtractToIntWithPositiveResult() {
        Object result = MezzeTestHelper.run("""
            use std::nat
            nat::subtract_to_int { l = 10n, r = 3n }
            """);
        assertEquals(7L, result);
    }

    @Test
    void subtractToIntWithNegativeResult() {
        Object result = MezzeTestHelper.run("""
            use std::nat
            nat::subtract_to_int { l = 3n, r = 10n }
            """);
        assertEquals(-7L, result);
    }

    // ========================================
    // Clamp
    // ========================================

    @Test
    void clampConstrainsValueToRange() {
        Object result = MezzeTestHelper.run("""
            use std::nat
            nat::clamp { n = 15n, min = 0n, max = 10n }
            """);
        assertEquals(10L, result);
    }

    @Test
    void clampReturnsValueWhenInRange() {
        Object result = MezzeTestHelper.run("""
            use std::nat
            nat::clamp { n = 5n, min = 0n, max = 10n }
            """);
        assertEquals(5L, result);
    }

    @Test
    void clampConstrainsToMinimum() {
        Object result = MezzeTestHelper.run("""
            use std::nat
            nat::clamp { n = 0n, min = 5n, max = 10n }
            """);
        assertEquals(5L, result);
    }

    // ========================================
    // Conversion Functions
    // ========================================

    @Test
    void toIntConvertsNatToInt() {
        Object result = MezzeTestHelper.run("""
            use std::nat
            nat::to_int { n = 42n }
            """);
        assertEquals(42L, result);
    }

    @Test
    void toStringConvertsNatToString() {
        Object result = MezzeTestHelper.run("""
            use std::nat
            nat::to_str { n = 42n }
            """);
        assertEquals("42", result);
    }

    @Test
    void fromIntConvertsPositiveIntToNat() {
        Object result = MezzeTestHelper.run("""
            use std::nat
            match nat::from_int { n = 42 } is
                Some { val } -> 1
                None -> 0
            """);
        assertEquals(1L, result);
    }

    @Test
    void fromIntReturnsNoneForNegativeInt() {
        Object result = MezzeTestHelper.run("""
            use std::nat
            match nat::from_int { n = -5 } is
                Some { val } -> 1
                None -> 0
            """);
        assertEquals(0L, result);
    }

    @Test
    void fromStringParsesValidNaturalNumber() {
        Object result = MezzeTestHelper.run("""
            use std::nat
            match nat::from_string { s = "42" } is
                Some { val } -> 1
                None -> 0
            """);
        assertEquals(1L, result);
    }

    @Test
    void fromStringReturnsNoneForInvalidString() {
        Object result = MezzeTestHelper.run("""
            use std::nat
            match nat::from_string { s = "abc" } is
                Some { val } -> 1
                None -> 0
            """);
        assertEquals(0L, result);
    }

    @Test
    void fromStringReturnsNoneForNegativeNumberString() {
        Object result = MezzeTestHelper.run("""
            use std::nat
            match nat::from_string { s = "-5" } is
                Some { val } -> 1
                None -> 0
            """);
        assertEquals(0L, result);
    }
}
