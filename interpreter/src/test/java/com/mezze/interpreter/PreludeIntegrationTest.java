package com.mezze.interpreter;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that prelude auto-import makes ability impls available
 * without importing every stdlib module explicitly.
 *
 * The prelude auto-imports ability impls (Eq, Ord, numeric, etc.) and
 * inherent impls from ALL stdlib modules. This means:
 * - Ability operators (==, +, <, etc.) work on all types
 * - Inherent method impls are in scope (resolved during substitution application)
 * - Cross-module ability impls (e.g., Eq for Seq) are available everywhere
 */
public class PreludeIntegrationTest {
    // ========================================
    // Arithmetic and equality work without imports
    // ========================================

    @Test
    void arithmeticWorks() {
        Object result = MezzeTestHelper.run("1 + 2 * 3");
        assertEquals(7L, result);
    }

    @Test
    void equalityWorks() {
        Object result = MezzeTestHelper.run("[1, 2, 3] == [1, 2, 3]");
        assertEquals(true, result);
    }

    @Test
    void booleanLogicWorks() {
        Object result = MezzeTestHelper.run("True && False");
        assertEquals(false, result);
    }

    @Test
    void stringEqualityWorks() {
        Object result = MezzeTestHelper.run("\"hello\" == \"hello\"");
        assertEquals(true, result);
    }

    @Test
    void comparisonWorks() {
        Object result = MezzeTestHelper.run("3 > 2");
        assertEquals(true, result);
    }

    // ========================================
    // Inherent methods work with explicit use
    // (prelude brings impls, user brings bindings)
    // ========================================

    @Test
    void seqMethodsWithUse() {
        Object result = MezzeTestHelper.run("""
            use std::collections::seq
            [1, 2, 3].map {f = {x} -> x * 2}.size {}
        """);
        assertEquals(3L, result);
    }

    @Test
    void optionMethodsWithUse() {
        Object result = MezzeTestHelper.run("""
            use std::option
            Some {val = 42}.map {f = {val} -> val + 1}.get_or_else {def = 0}
        """);
        assertEquals(43L, result);
    }

    @Test
    void resultMethodsWithUse() {
        Object result = MezzeTestHelper.run("""
            use std::result
            Ok {val = 10}.map {f = {val} -> val * 2}.get_or_else {def = 0}
        """);
        assertEquals(20L, result);
    }

    @Test
    void strMethodsWithUse() {
        Object result = MezzeTestHelper.run("""
            use std::str
            "hello".to_upper {}
        """);
        assertEquals("HELLO", result);
    }

    @Test
    void seqJoinWithUse() {
        Object result = MezzeTestHelper.run("""
            use std::collections::seq
            ["a", "b", "c"].join {sep = ", "}
        """);
        assertEquals("a, b, c", result);
    }
}
