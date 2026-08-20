package com.mezze.interpreter;

import com.mezze.interpreter.values.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ability resolution in V2 interpreter.
 *
 * Run with: ./gradlew :interpreterv2:test --tests "*AbilityIntegrationTest*"
 */
public class AbilityIntegrationTest {

    // ========================
    // Numeric Abilities
    // ========================

    @Test
    void testNumAddWithInt() {
        Object result = MezzeTestHelper.run("""
            10 + 20 + 30
            """);
        assertEquals(60L, result);
    }

    @Test
    void testNumAddWithFloat() {
        Object result = MezzeTestHelper.run("""
            1.5 + 2.5 + 3.0
            """);
        assertEquals(7.0, result);
    }

    @Test
    void testNumSubWithInt() {
        Object result = MezzeTestHelper.run("""
            100 - 30 - 20
            """);
        assertEquals(50L, result);
    }

    @Test
    void testNumMulWithInt() {
        Object result = MezzeTestHelper.run("""
            5 * 6 * 7
            """);
        assertEquals(210L, result);
    }

    @Test
    void testNumDivWithInt() {
        Object result = MezzeTestHelper.run("""
            100 / 5 / 2
            """);
        assertEquals(10L, result);
    }

    @Test
    void testNumModWithInt() {
        Object result = MezzeTestHelper.run("""
            17 % 5
            """);
        assertEquals(2L, result);
    }

    @Test
    void testNumNegWithInt() {
        Object result = MezzeTestHelper.run("""
            let x = 42
            x.neg {}
            """);
        assertEquals(-42L, result);
    }

    // ========================
    // Comparison Abilities
    // ========================

    @Test
    void testEqWithInt() {
        Object result = MezzeTestHelper.run("""
            5 == 5
            """);
        assertEquals(true, result);
    }

    @Test
    void testNeqWithInt() {
        Object result = MezzeTestHelper.run("""
            5 != 6
            """);
        assertEquals(true, result);
    }

    @Test
    void testOrdWithInt() {
        Object result1 = MezzeTestHelper.run("3 < 5");
        Object result2 = MezzeTestHelper.run("5 > 3");
        Object result3 = MezzeTestHelper.run("5 <= 5");
        Object result4 = MezzeTestHelper.run("5 >= 5");

        assertEquals(true, result1);
        assertEquals(true, result2);
        assertEquals(true, result3);
        assertEquals(true, result4);
    }

    // ========================
    // Logical Abilities
    // ========================

    @Test
    void testLogicalAnd() {
        Object result = MezzeTestHelper.run("""
            True && True
            """);
        assertEquals(true, result);
    }

    @Test
    void testLogicalOr() {
        Object result = MezzeTestHelper.run("""
            False || True
            """);
        assertEquals(true, result);
    }

    @Test
    void testLogicalNot() {
        Object result = MezzeTestHelper.run("""
            False.not {}
            """);
        assertEquals(true, result);
    }

    // ========================
    // Complex Expressions
    // ========================

    @Test
    void testComplexArithmetic() {
        Object result = MezzeTestHelper.run("""
            let a = 10
            let b = 5
            let c = 2
            (a + b) * c - a / b
            """);
        assertEquals(28L, result);
    }

    @Test
    void testNestedFunctions() {
        Object result = MezzeTestHelper.run("""
            let double = {x} -> x * 2
            let add = {a, b} -> a + b

            add { a = double { x = 5 }, b = double { x = 10 } }
            """);
        assertEquals(30L, result);
    }

    // TODO: Recursive functions need self-reference in closure
    // @Test
    // void testRecursiveFunction() {
    //     Object result = MezzeTestHelper.run("""
    //         let factorial = {n} ->
    //             if n <= 1 then 1
    //             else n * factorial { n = n - 1 }
    //
    //         factorial { n = 5 }
    //         """);
    //     assertEquals(120L, result);
    // }

    @Test
    void testHigherOrderFunctions() {
        Object result = MezzeTestHelper.run("""
            let apply_twice = {f, x} -> f { x = f { x = x } }
            let double = {x} -> x * 2

            apply_twice { f = double, x = 3 }
            """);
        assertEquals(12L, result);
    }

    // ========================
    // User-Defined Ability Tests
    // ========================

    @Test
    void testUserDefinedNumAddForMoney() {
        Object result = MezzeTestHelper.run("""
            use std::abilities::numeric::{NumAdd}

            type Money = { cents: Int }

            impl NumAdd Money for Money having 'out = Money where {
              add = { it, r } -> { cents = it.cents + r.cents }
            }

            let a: Money = { cents = 500 }
            let b: Money = { cents = 300 }
            let result = a + b
            result.cents
            """);
        assertEquals(800L, result);
    }

    @Test
    void testUserDefinedNumSubForMoney() {
        Object result = MezzeTestHelper.run("""
            use std::abilities::numeric::{NumSub}

            type Money = { cents: Int }

            impl NumSub for Money where {
              sub = { it, r } -> { cents = it.cents - r.cents }
            }

            let a: Money = { cents = 500 }
            let b: Money = { cents = 300 }
            let result = a - b
            result.cents
            """);
        // 500 - 300 = 200 (correct subtraction)
        assertEquals(200L, result);
    }

}
