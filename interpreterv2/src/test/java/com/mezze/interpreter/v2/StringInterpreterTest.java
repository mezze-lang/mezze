package com.mezze.interpreter.v2;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the std::string module functions.
 * Tests cover string manipulation, searching, splitting, padding, and conversion functions.
 *
 * Ported from V1: interpreter/src/test/kotlin/.../StringInterpreterTest.kt
 */
public class StringInterpreterTest {

    @BeforeEach
    void setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache();
    }

    // ========================================
    // Basic String Operations
    // ========================================

    @Test
    void testConcatJoinsTwoStrings() {
        Object result = MezzeTestHelper.run("""
            use std::string
            string::concat { l = "hello", r = " world" }
            """);
        assertEquals("hello world", result);
    }

    @Test
    void testLengthReturnsStringLength() {
        Object result = MezzeTestHelper.run("""
            use std::string
            string::length { s = "hello" }
            """);
        assertEquals(5L, result);
    }

    @Test
    void testIsEmptyReturnsTrueForEmptyString() {
        Object result = MezzeTestHelper.run("""
            use std::string
            string::is_empty { s = "" }
            """);
        assertEquals(true, result);
    }

    @Test
    void testIsEmptyReturnsFalseForNonEmptyString() {
        Object result = MezzeTestHelper.run("""
            use std::string
            string::is_empty { s = "x" }
            """);
        assertEquals(false, result);
    }

    // ========================================
    // Case Conversion
    // ========================================

    @Test
    void testToUpperConvertsToUppercase() {
        Object result = MezzeTestHelper.run("""
            use std::string
            string::to_upper { s = "hello" }
            """);
        assertEquals("HELLO", result);
    }

    @Test
    void testToLowerConvertsToLowercase() {
        Object result = MezzeTestHelper.run("""
            use std::string
            string::to_lower { s = "HELLO" }
            """);
        assertEquals("hello", result);
    }

    // ========================================
    // Trim Operations
    // ========================================

    @Test
    void testTrimRemovesWhitespaceFromBothEnds() {
        Object result = MezzeTestHelper.run("""
            use std::string
            string::trim { s = "  hello  " }
            """);
        assertEquals("hello", result);
    }

    @Test
    void testTrimStartRemovesWhitespaceFromStart() {
        Object result = MezzeTestHelper.run("""
            use std::string
            string::trim_start { s = "  hello" }
            """);
        assertEquals("hello", result);
    }

    @Test
    void testTrimEndRemovesWhitespaceFromEnd() {
        Object result = MezzeTestHelper.run("""
            use std::string
            string::trim_end { s = "hello  " }
            """);
        assertEquals("hello", result);
    }

    // ========================================
    // Substring Operations
    // ========================================

    @Test
    void testSubstringExtractsMiddlePortion() {
        Object result = MezzeTestHelper.run("""
            use std::string
            string::substring { s = "hello world", start = 0, end = 5 }
            """);
        assertEquals("hello", result);
    }

    @Test
    void testCharAtReturnsCharacterAtIndex() {
        Object result = MezzeTestHelper.run("""
            use std::string
            string::char_at { s = "hello", i = 1 }
            """);
        assertEquals("e", result);
    }

    // ========================================
    // Search Operations
    // ========================================

    @Test
    void testContainsReturnsTrueWhenSubstringFound() {
        Object result = MezzeTestHelper.run("""
            use std::string
            string::contains { s = "hello world", substring = "world" }
            """);
        assertEquals(true, result);
    }

    @Test
    void testContainsReturnsFalseWhenSubstringNotFound() {
        Object result = MezzeTestHelper.run("""
            use std::string
            string::contains { s = "hello world", substring = "foo" }
            """);
        assertEquals(false, result);
    }

    @Test
    void testStartsWithReturnsTrueForMatchingPrefix() {
        Object result = MezzeTestHelper.run("""
            use std::string
            string::starts_with { s = "hello world", prefix = "hello" }
            """);
        assertEquals(true, result);
    }

    @Test
    void testEndsWithReturnsTrueForMatchingSuffix() {
        Object result = MezzeTestHelper.run("""
            use std::string
            string::ends_with { s = "hello world", suffix = "world" }
            """);
        assertEquals(true, result);
    }

    // ========================================
    // Replace Operations
    // ========================================

    @Test
    void testReplaceReplacesFirstOccurrence() {
        Object result = MezzeTestHelper.run("""
            use std::string
            string::replace { s = "hello hello", old = "hello", new = "hi" }
            """);
        assertEquals("hi hello", result);
    }

    @Test
    void testReplaceAllReplacesAllOccurrences() {
        Object result = MezzeTestHelper.run("""
            use std::string
            string::replace_all { s = "hello hello", old = "hello", new = "hi" }
            """);
        assertEquals("hi hi", result);
    }

    // ========================================
    // Repeat Operation
    // ========================================

    @Test
    void testRepeatRepeatsStringOnce() {
        // Note: string::repeat uses recursion which works with smaller N
        Object result = MezzeTestHelper.run("""
            use std::string
            string::repeat { s = "ab", n = 1 }
            """);
        assertEquals("ab", result);
    }

    @Test
    void testRepeatReturnsEmptyForZero() {
        Object result = MezzeTestHelper.run("""
            use std::string
            string::repeat { s = "ab", n = 0 }
            """);
        assertEquals("", result);
    }

    // ========================================
    // Reverse Operation
    // ========================================

    @Test
    void testReverseReversesString() {
        Object result = MezzeTestHelper.run("""
            use std::string
            string::reverse { s = "hello" }
            """);
        assertEquals("olleh", result);
    }
}
