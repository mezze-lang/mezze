package com.mezze.interpreter.v2;

import com.mezze.interpreter.v2.values.MezzeRecord;
import com.mezze.language.typechecker.ModuleResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Frac (fraction) type and operations.
 * Uses V2 unboxed values directly.
 *
 * In V2, fractions are represented as MezzeRecord with "numerator" and "denominator" fields.
 */
class FracIntegrationTest {

    @BeforeEach
    void setUp() {
        ModuleResolver.clearCache();
    }

    // Helper to extract fraction components
    private static long getNumerator(Object result) {
        assertTrue(result instanceof MezzeRecord, "Expected MezzeRecord for fraction, got: " + result.getClass());
        return (Long) ((MezzeRecord) result).getField("numerator");
    }

    private static long getDenominator(Object result) {
        assertTrue(result instanceof MezzeRecord, "Expected MezzeRecord for fraction, got: " + result.getClass());
        return (Long) ((MezzeRecord) result).getField("denominator");
    }

    private static void assertFraction(long expectedNum, long expectedDenom, Object result) {
        assertTrue(result instanceof MezzeRecord, "Expected MezzeRecord for fraction");
        assertEquals(expectedNum, getNumerator(result), "numerator mismatch");
        assertEquals(expectedDenom, getDenominator(result), "denominator mismatch");
    }

    // ========================================
    // Basic Fraction Literal Tests
    // ========================================

    @Test
    void simpleFractionLiteral() {
        Object result = MezzeTestHelper.run("""
            let x = 1//2
            x
            """);
        assertFraction(1L, 2L, result);
    }

    @Test
    void largeFractionLiteral() {
        Object result = MezzeTestHelper.run("""
            let x = 123//456
            x
            """);
        assertFraction(123L, 456L, result);
    }

    @Test
    void fractionLiteralNotAutoReduced() {
        Object result = MezzeTestHelper.run("""
            let x = 6//8
            x
            """);
        // Note: Fractions are not auto-reduced in interpreter
        assertFraction(6L, 8L, result);
    }

    @Test
    void negativeFractionLiteral() {
        Object result = MezzeTestHelper.run("""
            let x = -3//4
            x
            """);
        assertFraction(-3L, 4L, result);
    }

    @Test
    void wholeNumberAsFraction() {
        Object result = MezzeTestHelper.run("""
            let x = 10//2
            x
            """);
        assertFraction(10L, 2L, result);
    }

    // ========================================
    // Fraction Addition Tests
    // ========================================

    @Test
    void addFractionsWithSameDenominator() {
        Object result = MezzeTestHelper.run("""
            let x = 1//4
            let y = 2//4
            x + y
            """);
        // 1/4 + 2/4 = 3/4
        assertFraction(3L, 4L, result);
    }

    @Test
    void addFractionsWithDifferentDenominators() {
        Object result = MezzeTestHelper.run("""
            let x = 1//2
            let y = 1//3
            x + y
            """);
        // 1/2 + 1/3 = 3/6 + 2/6 = 5/6
        assertFraction(5L, 6L, result);
    }

    @Test
    void addNegativeFraction() {
        Object result = MezzeTestHelper.run("""
            let x = 3//4
            let y = -1//4
            x + y
            """);
        // 3/4 + (-1/4) = 2/4 = 1/2
        assertFraction(1L, 2L, result);
    }

    @Test
    void addMultipleFractions() {
        Object result = MezzeTestHelper.run("""
            1//4 + 1//4 + 1//4 + 1//4
            """);
        // 1/4 + 1/4 + 1/4 + 1/4 = 4/4 = 1/1
        assertFraction(1L, 1L, result);
    }

    // ========================================
    // Fraction Subtraction Tests
    // ========================================

    @Test
    void subtractFractions() {
        Object result = MezzeTestHelper.run("""
            let x = 3//4
            let y = 1//4
            x - y
            """);
        // 3/4 - 1/4 = 2/4 = 1/2
        assertFraction(1L, 2L, result);
    }

    @Test
    void subtractFractionsWithDifferentDenominators() {
        Object result = MezzeTestHelper.run("""
            let x = 2//3
            let y = 1//2
            x - y
            """);
        // 2/3 - 1/2 = 4/6 - 3/6 = 1/6
        assertFraction(1L, 6L, result);
    }

    @Test
    void subtractResultingInNegative() {
        Object result = MezzeTestHelper.run("""
            let x = 1//4
            let y = 3//4
            x - y
            """);
        // 1/4 - 3/4 = -2/4 = -1/2
        assertFraction(-1L, 2L, result);
    }

    // ========================================
    // Fraction Multiplication Tests
    // ========================================

    @Test
    void multiplyFractions() {
        Object result = MezzeTestHelper.run("""
            let x = 2//3
            let y = 3//4
            x * y
            """);
        // 2/3 * 3/4 = 6/12 = 1/2
        assertFraction(1L, 2L, result);
    }

    @Test
    void multiplyNegativeFraction() {
        Object result = MezzeTestHelper.run("""
            let x = 1//2
            let y = -3//4
            x * y
            """);
        // 1/2 * (-3/4) = -3/8
        assertFraction(-3L, 8L, result);
    }

    @Test
    void multiplyMultipleFractions() {
        Object result = MezzeTestHelper.run("""
            1//2 * 2//3 * 3//4
            """);
        // 1/2 * 2/3 * 3/4 = 6/24 = 1/4
        assertFraction(1L, 4L, result);
    }

    // ========================================
    // Fraction Division Tests
    // ========================================

    @Test
    void divideFractions() {
        Object result = MezzeTestHelper.run("""
            let x = 1//2
            let y = 1//4
            x / y
            """);
        // 1/2 / 1/4 = 1/2 * 4/1 = 4/2 = 2/1
        assertFraction(2L, 1L, result);
    }

    @Test
    void divideFractionByLarger() {
        Object result = MezzeTestHelper.run("""
            let x = 1//4
            let y = 1//2
            x / y
            """);
        // 1/4 / 1/2 = 1/4 * 2/1 = 2/4 = 1/2
        assertFraction(1L, 2L, result);
    }

    @Test
    void divideByNegativeFraction() {
        Object result = MezzeTestHelper.run("""
            let x = 3//4
            let y = -1//2
            x / y
            """);
        assertTrue(result instanceof MezzeRecord);
        // 3/4 / (-1/2) = 3/4 * (-2/1) = -6/4 = -3/2
        long num = getNumerator(result);
        long denom = getDenominator(result);
        assertEquals(3L, Math.abs(num));
        assertEquals(2L, Math.abs(denom));
        // Check the value is negative
        assertTrue((num < 0) != (denom < 0), "Result should be negative");
    }

    // ========================================
    // Combined Arithmetic Tests
    // ========================================

    @Test
    void combinedArithmetic() {
        Object result = MezzeTestHelper.run("""
            let a = 1//2
            let b = 1//4
            let c = 2//3
            (a + b) * c - a / b
            """);
        // (1/2 + 1/4) * 2/3 - 1/2 / 1/4
        // = 3/4 * 2/3 - 2/1
        // = 1/2 - 2/1
        // = 1/2 - 4/2
        // = -3/2
        assertFraction(-3L, 2L, result);
    }

    @Test
    void complexNestedFractionOperations() {
        Object result = MezzeTestHelper.run("""
            let x = 1//2
            let y = 1//3
            let z = 1//6
            (x + y) - (x * y) + (y / z)
            """);
        // (1/2 + 1/3) - (1/2 * 1/3) + (1/3 / 1/6)
        // = 5/6 - 1/6 + 2/1
        // = 4/6 + 2/1
        // = 2/3 + 6/3
        // = 8/3
        assertFraction(8L, 3L, result);
    }

    // ========================================
    // Fraction Equality Tests
    // ========================================

    @Test
    void fractionEqualityTrue() {
        Object result = MezzeTestHelper.run("""
            let x = 1//2
            let y = 2//4
            x == y
            """);
        assertEquals(true, result);
    }

    @Test
    void fractionEqualityFalse() {
        Object result = MezzeTestHelper.run("""
            let x = 1//2
            let y = 1//3
            x == y
            """);
        assertEquals(false, result);
    }

    @Test
    void negativeFractionEquality() {
        Object result = MezzeTestHelper.run("""
            let x = -1//2
            let y = -2//4
            x == y
            """);
        assertEquals(true, result);
    }

    // ========================================
    // Edge Cases and Special Scenarios
    // ========================================

    @Test
    void zeroFraction() {
        Object result = MezzeTestHelper.run("""
            let x = 0//5
            x
            """);
        assertTrue(result instanceof MezzeRecord);
        assertEquals(0L, getNumerator(result));
        assertTrue(getDenominator(result) > 0);
    }

    @Test
    void addZeroFraction() {
        Object result = MezzeTestHelper.run("""
            let x = 1//2
            let zero = 0//1
            x + zero
            """);
        assertFraction(1L, 2L, result);
    }

    @Test
    void multiplyByZero() {
        Object result = MezzeTestHelper.run("""
            let x = 3//4
            let zero = 0//1
            x * zero
            """);
        assertTrue(result instanceof MezzeRecord);
        assertEquals(0L, getNumerator(result));
        assertEquals(1L, getDenominator(result));
    }

    @Test
    void conditionalWithFractions() {
        Object result = MezzeTestHelper.run("""
            let x = 1//2
            let y = 1//3
            let sum = x + y
            if sum == 5//6 then 1 else 0
            """);
        assertEquals(1L, result);
    }

    @Test
    void fractionInRecord() {
        Object result = MezzeTestHelper.run("""
            let point = { x = 1//2, y = 3//4 }
            point.x + point.y
            """);
        // 1/2 + 3/4 = 2/4 + 3/4 = 5/4
        assertFraction(5L, 4L, result);
    }

    @Test
    void fractionOperatorsNoImports() {
        Object result = MezzeTestHelper.run("""
            let x = 1//2 + 1//4
            let y = x * 2//1
            let z = y - 1//4
            z / 1//2
            """);
        // x = 1/2 + 1/4 = 3/4
        // y = 3/4 * 2/1 = 6/4 = 3/2
        // z = 3/2 - 1/4 = 6/4 - 1/4 = 5/4
        // result = 5/4 / 1/2 = 5/4 * 2/1 = 10/4 = 5/2
        assertFraction(5L, 2L, result);
    }
}
