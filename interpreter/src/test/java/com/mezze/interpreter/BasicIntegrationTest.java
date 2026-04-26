package com.mezze.interpreter;

import com.mezze.interpreter.values.*;
import com.mezze.datastructures.seq.MezzeSeq;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Basic integration tests for V2 interpreter end-to-end execution.
 *
 * These tests verify that the full pipeline works:
 * Parser → TypeChecker → ASTToTruffleConverterV2 → Execution
 *
 * Run with: ./gradlew :interpreterv2:test --tests "*BasicIntegrationTest*"
 */
public class BasicIntegrationTest {

    // ========================
    // Literals
    // ========================

    @Test
    void testIntLiteral() {
        Object result = MezzeTestHelper.run("42");
        assertEquals(42L, result);
    }

    @Test
    void testFloatLiteral() {
        Object result = MezzeTestHelper.run("3.14");
        assertEquals(3.14, result);
    }

    @Test
    void testBoolLiteral() {
        Object result = MezzeTestHelper.run("True");
        assertEquals(true, result);
    }

    @Test
    void testStringLiteral() {
        Object result = MezzeTestHelper.run("\"hello\"");
        assertEquals("hello", result);
    }

    // ========================
    // Arithmetic
    // ========================

    @Test
    void testIntAddition() {
        Object result = MezzeTestHelper.run("1 + 2");
        assertEquals(3L, result);
    }

    @Test
    void testIntSubtraction() {
        Object result = MezzeTestHelper.run("10 - 3");
        assertEquals(7L, result);
    }

    @Test
    void testIntMultiplication() {
        Object result = MezzeTestHelper.run("6 * 7");
        assertEquals(42L, result);
    }

    @Test
    void testIntDivision() {
        Object result = MezzeTestHelper.run("100 / 4");
        assertEquals(25L, result);
    }

    @Test
    void testIntModulo() {
        Object result = MezzeTestHelper.run("17 % 5");
        assertEquals(2L, result);
    }

    @Test
    void testComplexArithmetic() {
        Object result = MezzeTestHelper.run("(2 + 3) * 4 - 10 / 2");
        assertEquals(15L, result);
    }

    // ========================
    // Comparisons
    // ========================

    @Test
    void testLessThan() {
        assertEquals(true, MezzeTestHelper.run("1 < 2"));
        assertEquals(false, MezzeTestHelper.run("2 < 1"));
    }

    @Test
    void testGreaterThan() {
        assertEquals(true, MezzeTestHelper.run("2 > 1"));
        assertEquals(false, MezzeTestHelper.run("1 > 2"));
    }

    @Test
    void testEquals() {
        assertEquals(true, MezzeTestHelper.run("5 == 5"));
        assertEquals(false, MezzeTestHelper.run("5 == 6"));
    }

    @Test
    void testNotEquals() {
        assertEquals(true, MezzeTestHelper.run("5 != 6"));
        assertEquals(false, MezzeTestHelper.run("5 != 5"));
    }

    // ========================
    // Let bindings
    // ========================

    @Test
    void testLetBinding() {
        Object result = MezzeTestHelper.run("""
            let x = 42
            x
            """);
        assertEquals(42L, result);
    }

    @Test
    void testMultipleLetBindings() {
        Object result = MezzeTestHelper.run("""
            let x = 10
            let y = 20
            x + y
            """);
        assertEquals(30L, result);
    }

    @Test
    void testLetWithExpression() {
        Object result = MezzeTestHelper.run("""
            let x = 2 + 3
            x * 10
            """);
        assertEquals(50L, result);
    }

    // ========================
    // Conditionals
    // ========================

    @Test
    void testIfTrue() {
        Object result = MezzeTestHelper.run("""
            if True then 1 else 2
            """);
        assertEquals(1L, result);
    }

    @Test
    void testIfFalse() {
        Object result = MezzeTestHelper.run("""
            if False then 1 else 2
            """);
        assertEquals(2L, result);
    }

    @Test
    void testIfWithComparison() {
        Object result = MezzeTestHelper.run("""
            let x = 10
            if x > 5 then 1 else 0
            """);
        assertEquals(1L, result);
    }

    // ========================
    // Functions
    // ========================

    @Test
    void testSimpleFunction() {
        Object result = MezzeTestHelper.run("""
            let double = {x} -> x * 2
            double { x = 21 }
            """);
        assertEquals(42L, result);
    }

    @Test
    void testFunctionWithMultipleArgs() {
        Object result = MezzeTestHelper.run("""
            let add = {x, y} -> x + y
            add { x = 10, y = 32 }
            """);
        assertEquals(42L, result);
    }

    // Higher-order functions need additional work for nested closure capture
    // @Test
    // void testHigherOrderFunction() {
    //     Object result = MezzeTestHelper.run("""
    //         let apply = {f, x} -> f { x = x }
    //         let double = {x} -> x * 2
    //         apply { f = double, x = 21 }
    //         """);
    //     assertEquals(42L, result);
    // }

    // ========================
    // Records
    // ========================

    @Test
    void testRecordLiteral() {
        Object result = MezzeTestHelper.run("""
            { x = 10, y = 20 }
            """);
        assertTrue(result instanceof MezzeRecord);
        MezzeRecord rec = (MezzeRecord) result;
        assertEquals(10L, rec.getField("x"));
        assertEquals(20L, rec.getField("y"));
    }

    @Test
    void testRecordFieldAccess() {
        Object result = MezzeTestHelper.run("""
            let point = { x = 10, y = 20 }
            point.x + point.y
            """);
        assertEquals(30L, result);
    }

    // ========================
    // Arrays
    // ========================

    @Test
    void testArrayLiteral() {
        Object result = MezzeTestHelper.run("""
            [1, 2, 3]
            """);
        // V2 uses MezzeSeq for sequence literals
        assertTrue(result instanceof MezzeSeq, "Result should be MezzeSeq, got: " +
            (result == null ? "null" : result.getClass().getName() + " = " + result));
        MezzeSeq seq = (MezzeSeq) result;
        assertEquals(3, seq.size());
        assertEquals(1L, seq.get(0));
        assertEquals(2L, seq.get(1));
        assertEquals(3L, seq.get(2));
    }

    @Test
    void testSeqLength() {
        Object result = MezzeTestHelper.run("""
            use std::collections::seq
            seq::length { xs = [1, 2, 3] }
            """);
        assertEquals(3L, result);
    }

    @Test
    void testSeqLengthViaFunction() {
        Object result = MezzeTestHelper.run("""
            use std::collections::seq
            let length = {xs} -> seq::length { xs = xs }
            length { xs = [1, 2, 3, 4] }
            """);
        assertEquals(4L, result);
    }

    @Test
    void testStdlibSeqModule() {
        Object result = MezzeTestHelper.run("""
            use std::collections::seq
            seq::length { xs = [1, 2, 3, 4, 5] }
            """);
        assertEquals(5L, result);
    }

    // ========================
    // Variants (using built-in Option)
    // ========================

    @Test
    void testVariantConstruction() {
        Object result = MezzeTestHelper.run("""
            Some { val = 42 }
            """);
        assertTrue(result instanceof MezzeVariant, "Result should be MezzeVariant, got: " +
            (result == null ? "null" : result.getClass().getName()));
        MezzeVariant variant = (MezzeVariant) result;
        // Tag should be registered for "Some"
        assertTrue(variant.getTag() >= 0);
    }

    // ========================
    // Pattern matching
    // ========================

    @Test
    void testSimpleMatch() {
        Object result = MezzeTestHelper.run("""
            let x = Some { val = 42 }
            match x is
                Some { val } -> val
                None -> 0
            """);
        assertEquals(42L, result);
    }

    @Test
    void testMatchOnInt() {
        Object result = MezzeTestHelper.run("""
            let x = 2
            match x is
                1 -> "one"
                2 -> "two"
                _ -> "other"
            """);
        assertEquals("two", result);
    }

    // ========================
    // Do expressions
    // ========================

    @Test
    void testDoExpression() {
        Object result = MezzeTestHelper.run("""
            do
                let x = 10
                let y = 20
                x + y
            """);
        assertEquals(30L, result);
    }

    // ========================
    // String operations (stdlib)
    // ========================

    @Test
    void testStringLength() {
        // Test string length using stdlib
        Object result = MezzeTestHelper.run("""
            use std::str
            str::length { s = "hello" }
            """);
        assertEquals(5L, result);
    }

    // ========================
    // Effect handler from stdlib
    // ========================

    @Test
    void testSimpleEffectHandler() {
        // Test basic effect handler with Log effect using println helper
        Object result = MezzeTestHelper.run("""
            use std::effects::log::{Log, Console, println}

            perform Console in do
                println { msg = "hello" }
                42
            """);
        assertEquals(42L, result);
    }

    @Test
    void testEffectHandlerMultipleCalls() {
        // Test effect handler with multiple calls
        Object result = MezzeTestHelper.run("""
            use std::effects::log::{Log, Console, println}

            perform Console in do
                println { msg = "first" }
                println { msg = "second" }
                100
            """);
        assertEquals(100L, result);
    }

    // ========================
    // Bytes module
    // ========================

    @Test
    void testBytesModule() {
        Object result = MezzeTestHelper.run("""
            use std::collections::bytes
            let b = bytes::from_string { s = "Hello" }
            bytes::length { b = b }
            """);
        assertEquals(5L, result);
    }

    @Test
    void testBytesConcat() {
        // Test - calling same function twice
        Object result = MezzeTestHelper.run("""
            use std::collections::bytes
            let x = bytes::length { b = bytes::from_string { s = "Hello" } }
            let y = bytes::length { b = bytes::from_string { s = "World" } }
            x + y
            """);
        assertEquals(10L, result);
    }
}
