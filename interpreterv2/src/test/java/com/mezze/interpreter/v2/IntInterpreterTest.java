package com.mezze.interpreter.v2;

import com.mezze.interpreter.v2.values.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the std::int module functions.
 * Tests cover arithmetic, utility functions, and conversions.
 *
 * Ported from V1: interpreter/src/test/kotlin/.../IntInterpreterTest.kt
 */
public class IntInterpreterTest {

    @BeforeEach
    void setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache();
    }

    // ========================================
    // Basic Arithmetic
    // ========================================

    @Test
    void testAddSumsTwoIntegers() {
        Object result = MezzeTestHelper.run("""
            use std::int
            int::add { l = 3, r = 4 }
            """);
        assertEquals(7L, result);
    }

    @Test
    void testSubtractSubtractsTwoIntegers() {
        Object result = MezzeTestHelper.run("""
            use std::int
            int::subtract { l = 10, r = 3 }
            """);
        assertEquals(7L, result);
    }

    @Test
    void testMultiplyMultipliesTwoIntegers() {
        Object result = MezzeTestHelper.run("""
            use std::int
            int::multiply { l = 3, r = 4 }
            """);
        assertEquals(12L, result);
    }

    @Test
    void testDivideDividesTwoIntegers() {
        Object result = MezzeTestHelper.run("""
            use std::int
            int::divide { l = 10, r = 3 }
            """);
        assertEquals(3L, result);
    }

    @Test
    void testModuloReturnsRemainder() {
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
    void testAbsReturnsAbsoluteValueOfPositive() {
        Object result = MezzeTestHelper.run("""
            use std::int
            int::abs { n = 5 }
            """);
        assertEquals(5L, result);
    }

    @Test
    void testAbsReturnsAbsoluteValueOfNegative() {
        Object result = MezzeTestHelper.run("""
            use std::int
            int::abs { n = -5 }
            """);
        assertEquals(5L, result);
    }

    @Test
    void testMaxReturnsLargerValue() {
        Object result = MezzeTestHelper.run("""
            use std::int
            int::max { l = 3, r = 7 }
            """);
        assertEquals(7L, result);
    }

    @Test
    void testMinReturnsSmallerValue() {
        Object result = MezzeTestHelper.run("""
            use std::int
            int::min { l = 3, r = 7 }
            """);
        assertEquals(3L, result);
    }

    @Test
    void testSignReturns1ForPositive() {
        Object result = MezzeTestHelper.run("""
            use std::int
            int::sign { n = 42 }
            """);
        assertEquals(1L, result);
    }

    @Test
    void testSignReturnsMinus1ForNegative() {
        Object result = MezzeTestHelper.run("""
            use std::int
            int::sign { n = -42 }
            """);
        assertEquals(-1L, result);
    }

    @Test
    void testSignReturns0ForZero() {
        Object result = MezzeTestHelper.run("""
            use std::int
            int::sign { n = 0 }
            """);
        assertEquals(0L, result);
    }

    @Test
    void testIsEvenReturnsTrueForEvenNumber() {
        Object result = MezzeTestHelper.run("""
            use std::int
            int::is_even { n = 4 }
            """);
        assertEquals(true, result);
    }

    @Test
    void testIsEvenReturnsFalseForOddNumber() {
        Object result = MezzeTestHelper.run("""
            use std::int
            int::is_even { n = 5 }
            """);
        assertEquals(false, result);
    }

    @Test
    void testIsOddReturnsTrueForOddNumber() {
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
    void testPowComputesPower() {
        Object result = MezzeTestHelper.run("""
            use std::int
            int::pow { base = 2, exp = 10 }
            """);
        assertEquals(1024L, result);
    }

    @Test
    void testPowWithZeroExponentReturns1() {
        Object result = MezzeTestHelper.run("""
            use std::int
            int::pow { base = 5, exp = 0 }
            """);
        assertEquals(1L, result);
    }

    @Test
    void testGcdComputesGreatestCommonDivisor() {
        Object result = MezzeTestHelper.run("""
            use std::int
            int::gcd { l = 48, r = 18 }
            """);
        assertEquals(6L, result);
    }

    @Test
    void testLcmComputesLeastCommonMultiple() {
        Object result = MezzeTestHelper.run("""
            use std::int
            int::lcm { l = 4, r = 6 }
            """);
        assertEquals(12L, result);
    }

    @Test
    void testClampConstrainsValueToRange() {
        Object result = MezzeTestHelper.run("""
            use std::int
            int::clamp { n = 15, min = 0, max = 10 }
            """);
        assertEquals(10L, result);
    }

    @Test
    void testClampReturnsValueWhenInRange() {
        Object result = MezzeTestHelper.run("""
            use std::int
            int::clamp { n = 5, min = 0, max = 10 }
            """);
        assertEquals(5L, result);
    }

    @Test
    void testClampConstrainsToMinimum() {
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
    void testToStringConvertsToString() {
        Object result = MezzeTestHelper.run("""
            use std::int
            int::to_string { n = 42 }
            """);
        assertEquals("42", result);
    }

    @Test
    void testFromStringParsesValidInteger() {
        Object result = MezzeTestHelper.run("""
            use std::int
            match int::from_string { s = "42" } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(42L, result);
    }

    @Test
    void testFromStringReturnsNoneForInvalid() {
        Object result = MezzeTestHelper.run("""
            use std::int
            match int::from_string { s = "abc" } is
                Some { val } -> val
                None -> -999
            """);
        assertEquals(-999L, result);
    }

    @Test
    void testToNatConvertsPositiveToNat() {
        Object result = MezzeTestHelper.run("""
            use std::int
            match int::to_nat { n = 5 } is
                Some { val } -> 1
                None -> 0
            """);
        assertEquals(1L, result);
    }

    @Test
    void testToNatReturnsNoneForNegative() {
        Object result = MezzeTestHelper.run("""
            use std::int
            match int::to_nat { n = -5 } is
                Some { val } -> 1
                None -> 0
            """);
        assertEquals(0L, result);
    }

    @Test
    void testFromNatConvertsNatToInt() {
        Object result = MezzeTestHelper.run("""
            use std::int
            int::from_nat { n = 42n }
            """);
        assertEquals(42L, result);
    }
}
