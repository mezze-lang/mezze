package com.mezze.interpreter.v2;

import com.mezze.interpreter.v2.runtime.MezzeLanguage;
import com.mezze.interpreter.v2.values.MezzeRecord;
import com.mezze.language.typechecker.ModuleResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for unary operators (negation).
 * Uses V2 unboxed values directly.
 */
class UnaryOperatorIntegrationTest {

    @BeforeEach
    void setUp() {
        ModuleResolver.clearCache();
    }

    private Map<String, Object> runAndGetContext(String source) {
        MezzeTestHelper.run(source);
        return MezzeLanguage.getLastExecutionContext();
    }

    @Test
    void testNegativeIntegerLiterals() {
        var ctx = runAndGetContext("""
            let neg5 = -5
            let neg0 = -0
            let neg100 = -100
            """);
        assertEquals(-5L, ctx.get("neg5"));
        assertEquals(0L, ctx.get("neg0"));
        assertEquals(-100L, ctx.get("neg100"));
    }

    @Test
    void testNegativeFloatLiterals() {
        var ctx = runAndGetContext("""
            let negPi = -3.14
            let negZero = -0.0
            let negSmall = -0.001
            """);
        assertEquals(-3.14, (Double) ctx.get("negPi"), 0.001);
        assertEquals(0.0, (Double) ctx.get("negZero"), 0.001);
        assertEquals(-0.001, (Double) ctx.get("negSmall"), 0.001);
    }

    @Test
    void testNegativeExpressions() {
        var ctx = runAndGetContext("""
            let negSum = -(5 + 3)
            let negProduct = -(4 * 2)
            let negDifference = -(10 - 3)
            """);
        assertEquals(-8L, ctx.get("negSum"));
        assertEquals(-8L, ctx.get("negProduct"));
        assertEquals(-7L, ctx.get("negDifference"));
    }

    @Test
    void testNegativeInArithmeticOperations() {
        var ctx = runAndGetContext("""
            let a = -10
            let b = -5
            let sum = a + b
            let difference = a - b
            let product = a * b
            let quotient = a / b
            """);
        assertEquals(-10L, ctx.get("a"));
        assertEquals(-5L, ctx.get("b"));
        assertEquals(-15L, ctx.get("sum"));       // -10 + (-5)
        assertEquals(-5L, ctx.get("difference")); // -10 - (-5)
        assertEquals(50L, ctx.get("product"));    // -10 * (-5)
        assertEquals(2L, ctx.get("quotient"));    // -10 / (-5)
    }

    @Test
    void testNegativeWithVariables() {
        var ctx = runAndGetContext("""
            let x = 7
            let negX = -x
            let y = -3
            let negY = -y
            """);
        assertEquals(7L, ctx.get("x"));
        assertEquals(-7L, ctx.get("negX"));
        assertEquals(-3L, ctx.get("y"));
        assertEquals(3L, ctx.get("negY"));
    }

    @Test
    void testNegativeInFunctionParameters() {
        var ctx = runAndGetContext("""
            let square = {x} -> x * x
            let result1 = square {x = -4}
            let result2 = square {x = -7}
            """);
        assertEquals(16L, ctx.get("result1")); // (-4)^2
        assertEquals(49L, ctx.get("result2")); // (-7)^2
    }

    @Test
    void testNegativeInConditionals() {
        var ctx = runAndGetContext("""
            let x = -5
            let isNegative = if x < 0 then True else False
            let absValue = if x < 0 then -x else x
            """);
        assertEquals(-5L, ctx.get("x"));
        assertEquals(true, ctx.get("isNegative"));
        assertEquals(5L, ctx.get("absValue"));
    }

    @Test
    void testNegativeWithRecords() {
        var ctx = runAndGetContext("""
            let point = {x = -3, y = -4}
            let negPoint = {x = -point.x, y = -point.y}
            """);
        var point = (MezzeRecord) ctx.get("point");
        assertEquals(-3L, point.getField("x"));
        assertEquals(-4L, point.getField("y"));

        var negPoint = (MezzeRecord) ctx.get("negPoint");
        assertEquals(3L, negPoint.getField("x"));
        assertEquals(4L, negPoint.getField("y"));
    }

    @Test
    void testNegativeWithPipeOperatorDesugared() {
        var ctx = runAndGetContext("""
            let negate = {x} -> -x
            let result1 = negate {x = 5}
            let result2 = negate {x = -3}
            """);
        assertEquals(-5L, ctx.get("result1"));
        assertEquals(3L, ctx.get("result2"));
    }

    @Test
    void testNegativeWithPipeOperator() {
        var ctx = runAndGetContext("""
            let negate = {x} -> -x
            let result1 = 5 |> negate {x = @}
            let result2 = -3 |> negate {x = @}
            """);
        assertEquals(-5L, ctx.get("result1"));
        assertEquals(3L, ctx.get("result2"));
    }

    @Test
    void testComplexNegativeExpressions() {
        var ctx = runAndGetContext("""
            let complex1 = -(3 + 4) * -2
            let complex2 = -((5 * 2) - 3)
            let complex3 = -(10 / 2) + -(3 * 2)
            """);
        assertEquals(14L, ctx.get("complex1"));   // -(3+4) * -2 = -7 * -2 = 14
        assertEquals(-7L, ctx.get("complex2"));   // -((5*2) - 3) = -(10-3) = -7
        assertEquals(-11L, ctx.get("complex3"));  // -(10/2) + -(3*2) = -5 + -6 = -11
    }

    @Test
    void testNegativeWithFloatArithmetic() {
        var ctx = runAndGetContext("""
            let a = -2.5
            let b = -1.5
            let sum = a + b
            let product = a * b
            """);
        assertEquals(-2.5, (Double) ctx.get("a"), 0.001);
        assertEquals(-1.5, (Double) ctx.get("b"), 0.001);
        assertEquals(-4.0, (Double) ctx.get("sum"), 0.001);
        assertEquals(3.75, (Double) ctx.get("product"), 0.001);
    }

    @Test
    void testNegativeZeroEdgeCases() {
        var ctx = runAndGetContext("""
            let negZeroInt = -0
            let negZeroFloat = -0.0
            """);
        assertEquals(0L, ctx.get("negZeroInt"));
        assertEquals(0.0, (Double) ctx.get("negZeroFloat"), 0.001);
    }
}
