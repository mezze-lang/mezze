package com.mezze.interpreter.v2;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the std::str module functions.
 * Uses V2 unboxed values directly.
 */
class StringIntegrationTest {
    // ========================================
    // Basic String Operations
    // ========================================

    @Test
    void concatJoinsTwoStrings() {
        Object result = MezzeTestHelper.run("""
            use std::str
            str::concat { l = "hello", r = " world" }
            """);
        assertEquals("hello world", result);
    }

    @Test
    void lengthReturnsStringLength() {
        Object result = MezzeTestHelper.run("""
            use std::str
            str::length { s = "hello" }
            """);
        assertEquals(5L, result);
    }

    @Test
    void isEmptyReturnsTrueForEmptyString() {
        Object result = MezzeTestHelper.run("""
            use std::str
            str::is_empty { s = "" }
            """);
        assertEquals(true, result);
    }

    @Test
    void isEmptyReturnsFalseForNonEmptyString() {
        Object result = MezzeTestHelper.run("""
            use std::str
            str::is_empty { s = "x" }
            """);
        assertEquals(false, result);
    }

    // ========================================
    // Case Conversion
    // ========================================

    @Test
    void toUpperConvertsToUppercase() {
        Object result = MezzeTestHelper.run("""
            use std::str
            str::to_upper { s = "hello" }
            """);
        assertEquals("HELLO", result);
    }

    @Test
    void toLowerConvertsToLowercase() {
        Object result = MezzeTestHelper.run("""
            use std::str
            str::to_lower { s = "HELLO" }
            """);
        assertEquals("hello", result);
    }

    // ========================================
    // Trim Operations
    // ========================================

    @Test
    void trimRemovesWhitespaceFromBothEnds() {
        Object result = MezzeTestHelper.run("""
            use std::str
            str::trim { s = "  hello  " }
            """);
        assertEquals("hello", result);
    }

    @Test
    void trimStartRemovesLeadingWhitespace() {
        Object result = MezzeTestHelper.run("""
            use std::str
            str::trim_start { s = "  hello  " }
            """);
        assertEquals("hello  ", result);
    }

    @Test
    void trimEndRemovesTrailingWhitespace() {
        Object result = MezzeTestHelper.run("""
            use std::str
            str::trim_end { s = "  hello  " }
            """);
        assertEquals("  hello", result);
    }

    // ========================================
    // Search and Contains
    // ========================================

    @Test
    void startsWithReturnsTrueWhenPrefixMatches() {
        Object result = MezzeTestHelper.run("""
            use std::str
            str::starts_with { s = "hello world", prefix = "hello" }
            """);
        assertEquals(true, result);
    }

    @Test
    void startsWithReturnsFalseWhenPrefixDoesNotMatch() {
        Object result = MezzeTestHelper.run("""
            use std::str
            str::starts_with { s = "hello world", prefix = "world" }
            """);
        assertEquals(false, result);
    }

    @Test
    void endsWithReturnsTrueWhenSuffixMatches() {
        Object result = MezzeTestHelper.run("""
            use std::str
            str::ends_with { s = "hello world", suffix = "world" }
            """);
        assertEquals(true, result);
    }

    @Test
    void containsReturnsTrueWhenSubstringExists() {
        Object result = MezzeTestHelper.run("""
            use std::str
            str::contains { s = "hello world", substring = "lo wo" }
            """);
        assertEquals(true, result);
    }

    @Test
    void containsReturnsFalseWhenSubstringDoesNotExist() {
        Object result = MezzeTestHelper.run("""
            use std::str
            str::contains { s = "hello world", substring = "xyz" }
            """);
        assertEquals(false, result);
    }

    // ========================================
    // Index Operations
    // ========================================

    @Test
    void indexOfFindsFirstOccurrence() {
        Object result = MezzeTestHelper.run("""
            use std::str
            match str::index_of { s = "hello", sub = "ll" } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(2L, result);
    }

    @Test
    void indexOfReturnsNoneWhenNotFound() {
        Object result = MezzeTestHelper.run("""
            use std::str
            match str::index_of { s = "hello", sub = "xyz" } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(-1L, result);
    }

    @Test
    void lastIndexOfFindsLastOccurrence() {
        Object result = MezzeTestHelper.run("""
            use std::str
            match str::last_index_of { s = "hello hello", sub = "ll" } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(8L, result);
    }

    // ========================================
    // Substring Operations
    // ========================================

    @Test
    void substringExtractsMiddlePortion() {
        Object result = MezzeTestHelper.run("""
            use std::str
            str::substring { s = "hello world", start = 0, end = 5 }
            """);
        assertEquals("hello", result);
    }

    @Test
    void substringWithOutOfBoundsReturnsEmpty() {
        Object result = MezzeTestHelper.run("""
            use std::str
            str::substring { s = "hi", start = 10, end = 20 }
            """);
        assertEquals("", result);
    }

    // ========================================
    // Replace Operations
    // ========================================

    @Test
    void replaceReplacesFirstOccurrence() {
        Object result = MezzeTestHelper.run("""
            use std::str
            str::replace { s = "hello world world", old = "world", new = "there" }
            """);
        assertEquals("hello there world", result);
    }

    @Test
    void replaceAllReplacesAllOccurrences() {
        Object result = MezzeTestHelper.run("""
            use std::str
            str::replace_all { s = "hello world world", old = "world", new = "there" }
            """);
        assertEquals("hello there there", result);
    }

    // ========================================
    // Split and Join
    // ========================================

    @Test
    void splitDividesStringByDelimiter() {
        Object result = MezzeTestHelper.run("""
            use std::str
            use std::collections::seq
            let parts = str::split { s = "a,b,c", delimiter = "," }
            seq::length { xs = parts }
            """);
        assertEquals(3L, result);
    }

    @Test
    void joinCombinesStringsWithSeparator() {
        Object result = MezzeTestHelper.run("""
            use std::str
            str::join { strings = ["a", "b", "c"], separator = "-" }
            """);
        assertEquals("a-b-c", result);
    }

    @Test
    void linesSplitsByNewlines() {
        Object result = MezzeTestHelper.run("""
            use std::str
            use std::collections::seq
            let parts = str::lines { s = "a\\nb\\nc" }
            seq::length { xs = parts }
            """);
        assertEquals(3L, result);
    }

    @Test
    void wordsSplitsByWhitespace() {
        Object result = MezzeTestHelper.run("""
            use std::str
            use std::collections::seq
            let parts = str::words { s = "hello   world  test" }
            seq::length { xs = parts }
            """);
        assertEquals(3L, result);
    }

    // ========================================
    // Character Operations
    // ========================================

    @Test
    void charAtReturnsCharacterAtIndex() {
        Object result = MezzeTestHelper.run("""
            use std::str
            use std::char
            match str::char_at { s = "hello", i = 1 } is
                Some { val } -> char::to_int { c = val }
                None -> -1
            """);
        assertEquals(101L, result);  // 'e' = 101
    }

    @Test
    void charAtReturnsNoneForOutOfBounds() {
        Object result = MezzeTestHelper.run("""
            use std::str
            match str::char_at { s = "hi", i = 10 } is
                Some { val } -> 1
                None -> 0
            """);
        assertEquals(0L, result);
    }

    @Test
    void charsConvertsToCharacterArray() {
        Object result = MezzeTestHelper.run("""
            use std::str
            use std::collections::seq
            let cs = str::chars { s = "abc" }
            seq::length { xs = cs }
            """);
        assertEquals(3L, result);
    }

    // ========================================
    // Padding Operations
    // ========================================

    @Test
    void padLeftPadsStringToTargetLength() {
        Object result = MezzeTestHelper.run("""
            use std::str
            use std::char
            match char::from_int { code = 48 } is
                Some { val } -> str::pad_left { s = "42", len = 5, c = val }
                None -> "error"
            """);
        assertEquals("00042", result);  // '0' = 48
    }

    @Test
    void padRightPadsStringToTargetLength() {
        Object result = MezzeTestHelper.run("""
            use std::str
            use std::char
            match char::from_int { code = 46 } is
                Some { val } -> str::pad_right { s = "hi", len = 5, c = val }
                None -> "error"
            """);
        assertEquals("hi...", result);  // '.' = 46
    }

    @Test
    void padDoesNotTruncateLongerStrings() {
        Object result = MezzeTestHelper.run("""
            use std::str
            use std::char
            match char::from_int { code = 48 } is
                Some { val } -> str::pad_left { s = "hello world", len = 5, c = val }
                None -> "error"
            """);
        assertEquals("hello world", result);
    }

    // ========================================
    // Other Operations
    // ========================================

    @Test
    void reverseReversesTheString() {
        Object result = MezzeTestHelper.run("""
            use std::str
            str::reverse { s = "hello" }
            """);
        assertEquals("olleh", result);
    }

    @Test
    void repeatCreatesRepeatedString() {
        Object result = MezzeTestHelper.run("""
            use std::str
            str::repeat { s = "ab", n = 3 }
            """);
        assertEquals("ababab", result);
    }

    @Test
    void repeatWithZeroReturnsEmpty() {
        Object result = MezzeTestHelper.run("""
            use std::str
            str::repeat { s = "ab", n = 0 }
            """);
        assertEquals("", result);
    }
}
