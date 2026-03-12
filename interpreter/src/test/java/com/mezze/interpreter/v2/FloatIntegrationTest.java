package com.mezze.interpreter.v2;

import com.mezze.language.typechecker.ModuleResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the std::float module functions.
 * Uses V2 unboxed values directly.
 */
class FloatIntegrationTest {

    @BeforeEach
    void setUp() {
        ModuleResolver.clearCache();
    }

    // ========================================
    // Basic Operations
    // ========================================

    @Test
    void absReturnsAbsoluteValueOfPositive() {
        Object result = MezzeTestHelper.run("""
            use std::float
            float::abs { n = 5.5 }
            """);
        assertEquals(5.5, (Double) result, 0.001);
    }

    @Test
    void absReturnsAbsoluteValueOfNegative() {
        Object result = MezzeTestHelper.run("""
            use std::float
            float::abs { n = -5.5 }
            """);
        assertEquals(5.5, (Double) result, 0.001);
    }

    @Test
    void minReturnsSmallerValue() {
        Object result = MezzeTestHelper.run("""
            use std::float
            float::min { l = 3.5, r = 7.2 }
            """);
        assertEquals(3.5, (Double) result, 0.001);
    }

    @Test
    void maxReturnsLargerValue() {
        Object result = MezzeTestHelper.run("""
            use std::float
            float::max { l = 3.5, r = 7.2 }
            """);
        assertEquals(7.2, (Double) result, 0.001);
    }

    // ========================================
    // Float Classification
    // ========================================

    @Test
    void isFiniteReturnsTrueForRegularFloat() {
        Object result = MezzeTestHelper.run("""
            use std::float
            float::is_finite { n = 3.14 }
            """);
        assertEquals(true, result);
    }

    @Test
    void isFiniteReturnsFalseForInfinity() {
        Object result = MezzeTestHelper.run("""
            use std::float
            float::is_finite { n = 1.0 / 0.0 }
            """);
        assertEquals(false, result);
    }

    @Test
    void isInfiniteReturnsTrueForInfinity() {
        Object result = MezzeTestHelper.run("""
            use std::float
            float::is_infinite { n = 1.0 / 0.0 }
            """);
        assertEquals(true, result);
    }

    @Test
    void isInfiniteReturnsFalseForRegularFloat() {
        Object result = MezzeTestHelper.run("""
            use std::float
            float::is_infinite { n = 3.14 }
            """);
        assertEquals(false, result);
    }

    @Test
    void isNanReturnsTrueForNaN() {
        Object result = MezzeTestHelper.run("""
            use std::float
            float::is_nan { n = 0.0 / 0.0 }
            """);
        assertEquals(true, result);
    }

    @Test
    void isNanReturnsFalseForRegularFloat() {
        Object result = MezzeTestHelper.run("""
            use std::float
            float::is_nan { n = 3.14 }
            """);
        assertEquals(false, result);
    }

    // ========================================
    // Sign Function
    // ========================================

    @Test
    void signReturns1ForPositive() {
        Object result = MezzeTestHelper.run("""
            use std::float
            float::sign { n = 42.5 }
            """);
        assertEquals(1.0, (Double) result, 0.001);
    }

    @Test
    void signReturnsMinus1ForNegative() {
        Object result = MezzeTestHelper.run("""
            use std::float
            float::sign { n = -42.5 }
            """);
        assertEquals(-1.0, (Double) result, 0.001);
    }

    @Test
    void signReturns0ForZero() {
        Object result = MezzeTestHelper.run("""
            use std::float
            float::sign { n = 0.0 }
            """);
        assertEquals(0.0, (Double) result, 0.001);
    }

    // ========================================
    // Clamp
    // ========================================

    @Test
    void clampConstrainsValueToMaximum() {
        Object result = MezzeTestHelper.run("""
            use std::float
            float::clamp { n = 15.5, min = 0.0, max = 10.0 }
            """);
        assertEquals(10.0, (Double) result, 0.001);
    }

    @Test
    void clampReturnsValueWhenInRange() {
        Object result = MezzeTestHelper.run("""
            use std::float
            float::clamp { n = 5.5, min = 0.0, max = 10.0 }
            """);
        assertEquals(5.5, (Double) result, 0.001);
    }

    @Test
    void clampConstrainsToMinimum() {
        Object result = MezzeTestHelper.run("""
            use std::float
            float::clamp { n = -5.5, min = 0.0, max = 10.0 }
            """);
        assertEquals(0.0, (Double) result, 0.001);
    }

    // ========================================
    // Conversion Functions
    // ========================================

    @Test
    void toStringConvertsFloatToString() {
        Object result = MezzeTestHelper.run("""
            use std::float
            use std::string
            let s = float::to_str { n = 3.14 }
            string::starts_with { s = s, prefix = "3.14" }
            """);
        assertEquals(true, result);
    }

    @Test
    void fromIntConvertsIntegerToFloat() {
        Object result = MezzeTestHelper.run("""
            use std::float
            float::from_int { n = 42 }
            """);
        assertEquals(42.0, (Double) result, 0.001);
    }

    @Test
    void fromNatConvertsNaturalToFloat() {
        Object result = MezzeTestHelper.run("""
            use std::float
            float::from_nat { n = 42n }
            """);
        assertEquals(42.0, (Double) result, 0.001);
    }

    @Test
    void fromStringParsesValidFloat() {
        Object result = MezzeTestHelper.run("""
            use std::float
            match float::from_string { s = "3.14" } is
                Some { val } -> 1
                None -> 0
            """);
        assertEquals(1L, result);
    }

    @Test
    void fromStringReturnsNoneForInvalid() {
        Object result = MezzeTestHelper.run("""
            use std::float
            match float::from_string { s = "abc" } is
                Some { val } -> 1
                None -> 0
            """);
        assertEquals(0L, result);
    }

    @Test
    void toIntTruncatesFloatToInteger() {
        Object result = MezzeTestHelper.run("""
            use std::float
            match float::to_int { n = 3.7 } is
                Some { val } -> val
                None -> -999
            """);
        assertEquals(3L, result);
    }

    @Test
    void toIntReturnsNoneForInfinity() {
        Object result = MezzeTestHelper.run("""
            use std::float
            match float::to_int { n = 1.0 / 0.0 } is
                Some { val } -> 1
                None -> 0
            """);
        assertEquals(0L, result);
    }

    @Test
    void toIntReturnsNoneForNaN() {
        Object result = MezzeTestHelper.run("""
            use std::float
            match float::to_int { n = 0.0 / 0.0 } is
                Some { val } -> 1
                None -> 0
            """);
        assertEquals(0L, result);
    }
}
