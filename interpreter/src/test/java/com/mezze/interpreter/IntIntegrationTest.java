package com.mezze.interpreter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the std::int module functions.
 * Uses V2 unboxed values directly.
 */
class IntIntegrationTest {
    // ========================================
    // Basic Arithmetic
    // ========================================

    @Test
    void addSumsTwoIntegers() {
        Object result = MezzeTestHelper.run("""
            use std::int
            int::add { l = 3, r = 4 }
            """);
        assertEquals(7L, result);
    }

    @Test
    void subtractSubtractsTwoIntegers() {
        Object result = MezzeTestHelper.run("""
            use std::int
            int::subtract { l = 10, r = 3 }
            """);
        assertEquals(7L, result);
    }

    @Test
    void multiplyMultipliesTwoIntegers() {
        Object result = MezzeTestHelper.run("""
            use std::int
            int::multiply { l = 3, r = 4 }
            """);
        assertEquals(12L, result);
    }

    @Test
    void divideDividesTwoIntegers() {
        Object result = MezzeTestHelper.run("""
            use std::int
            int::divide { l = 10, r = 3 }
            """);
        assertEquals(3L, result);
    }

    @Test
    void moduloReturnsRemainder() {
        Object result = MezzeTestHelper.run("""
            use std::int
            int::modulo { l = 10, r = 3 }
            """);
        assertEquals(1L, result);
    }

    // ========================================
    // Utility Functions
    // ========================================

    @Test
    void absReturnsAbsoluteValueOfPositive() {
        Object result = MezzeTestHelper.run("""
            use std::int
            int::abs { n = 5 }
            """);
        assertEquals(5L, result);
    }

    @Test
    void absReturnsAbsoluteValueOfNegative() {
        Object result = MezzeTestHelper.run("""
            use std::int
            int::abs { n = -5 }
            """);
        assertEquals(5L, result);
    }

    @Test
    void maxReturnsLargerValue() {
        Object result = MezzeTestHelper.run("""
            use std::int
            int::max { l = 3, r = 7 }
            """);
        assertEquals(7L, result);
    }

    @Test
    void minReturnsSmallerValue() {
        Object result = MezzeTestHelper.run("""
            use std::int
            int::min { l = 3, r = 7 }
            """);
        assertEquals(3L, result);
    }

    @Test
    void signReturns1ForPositive() {
        Object result = MezzeTestHelper.run("""
            use std::int
            int::sign { n = 42 }
            """);
        assertEquals(1L, result);
    }

    @Test
    void signReturnsMinus1ForNegative() {
        Object result = MezzeTestHelper.run("""
            use std::int
            int::sign { n = -42 }
            """);
        assertEquals(-1L, result);
    }

    @Test
    void signReturns0ForZero() {
        Object result = MezzeTestHelper.run("""
            use std::int
            int::sign { n = 0 }
            """);
        assertEquals(0L, result);
    }

    @Test
    void isEvenReturnsTrueForEvenNumber() {
        Object result = MezzeTestHelper.run("""
            use std::int
            int::is_even { n = 4 }
            """);
        assertEquals(true, result);
    }

    @Test
    void isEvenReturnsFalseForOddNumber() {
        Object result = MezzeTestHelper.run("""
            use std::int
            int::is_even { n = 5 }
            """);
        assertEquals(false, result);
    }

    @Test
    void isOddReturnsTrueForOddNumber() {
        Object result = MezzeTestHelper.run("""
            use std::int
            int::is_odd { n = 5 }
            """);
        assertEquals(true, result);
    }

    // ========================================
    // Math Functions
    // ========================================

    @Test
    void powComputesPower() {
        Object result = MezzeTestHelper.run("""
            use std::int
            int::pow { base = 2, exp = 10 }
            """);
        assertEquals(1024L, result);
    }

    @Test
    void powWithZeroExponentReturns1() {
        Object result = MezzeTestHelper.run("""
            use std::int
            int::pow { base = 5, exp = 0 }
            """);
        assertEquals(1L, result);
    }

    @Test
    void gcdComputesGreatestCommonDivisor() {
        Object result = MezzeTestHelper.run("""
            use std::int
            int::gcd { l = 48, r = 18 }
            """);
        assertEquals(6L, result);
    }

    @Test
    void lcmComputesLeastCommonMultiple() {
        Object result = MezzeTestHelper.run("""
            use std::int
            int::lcm { l = 4, r = 6 }
            """);
        assertEquals(12L, result);
    }

    @Test
    void clampConstrainsValueToRange() {
        Object result = MezzeTestHelper.run("""
            use std::int
            int::clamp { n = 15, min = 0, max = 10 }
            """);
        assertEquals(10L, result);
    }

    @Test
    void clampReturnsValueWhenInRange() {
        Object result = MezzeTestHelper.run("""
            use std::int
            int::clamp { n = 5, min = 0, max = 10 }
            """);
        assertEquals(5L, result);
    }

    @Test
    void clampConstrainsToMinimum() {
        Object result = MezzeTestHelper.run("""
            use std::int
            int::clamp { n = -5, min = 0, max = 10 }
            """);
        assertEquals(0L, result);
    }

    // ========================================
    // Conversion Functions
    // ========================================

    @Test
    void toStringConvertsToString() {
        Object result = MezzeTestHelper.run("""
            use std::int
            int::to_str { n = 42 }
            """);
        assertEquals("42", result);
    }

    @Test
    void fromStringParsesValidInteger() {
        Object result = MezzeTestHelper.run("""
            use std::int
            match int::from_string { s = "42" } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(42L, result);
    }

    @Test
    void fromStringReturnsNoneForInvalid() {
        Object result = MezzeTestHelper.run("""
            use std::int
            match int::from_string { s = "abc" } is
                Some { val } -> val
                None -> -999
            """);
        assertEquals(-999L, result);
    }

    @Test
    void toNatConvertsPositiveToNat() {
        Object result = MezzeTestHelper.run("""
            use std::int
            match int::to_nat { n = 5 } is
                Some { val } -> 1
                None -> 0
            """);
        assertEquals(1L, result);
    }

    @Test
    void toNatReturnsNoneForNegative() {
        Object result = MezzeTestHelper.run("""
            use std::int
            match int::to_nat { n = -5 } is
                Some { val } -> 1
                None -> 0
            """);
        assertEquals(0L, result);
    }

    @Test
    void fromNatConvertsNatToInt() {
        Object result = MezzeTestHelper.run("""
            use std::int
            int::from_nat { n = 42n }
            """);
        assertEquals(42L, result);
    }
}
