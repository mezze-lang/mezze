package com.mezze.interpreter.v2;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the std::collections::seq module functions.
 * Uses V2 unboxed values directly.
 */
class SeqIntegrationTest {
    // ========================================
    // Basic Operations
    // ========================================

    @Test
    void lengthReturnsArrayLength() {
        Object result = MezzeTestHelper.run("""
            use std::collections::seq
            seq::length { xs = [1, 2, 3, 4, 5] }
            """);
        assertEquals(5L, result);
    }

    @Test
    void isEmptyReturnsTrueForEmptyArray() {
        Object result = MezzeTestHelper.run("""
            use std::collections::seq
            let xs: [Int] = []
            seq::is_empty { xs = xs }
            """);
        assertEquals(true, result);
    }

    @Test
    void isEmptyReturnsFalseForNonEmptyArray() {
        Object result = MezzeTestHelper.run("""
            use std::collections::seq
            seq::is_empty { xs = [1] }
            """);
        assertEquals(false, result);
    }

    @Test
    void getReturnsElementAtIndex() {
        Object result = MezzeTestHelper.run("""
            use std::collections::seq
            match seq::get { xs = [10, 20, 30], i = 1 } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(20L, result);
    }

    @Test
    void getReturnsNoneForOutOfBounds() {
        Object result = MezzeTestHelper.run("""
            use std::collections::seq
            match seq::get { xs = [10, 20, 30], i = 10 } is
                Some { val } -> 1
                None -> 0
            """);
        assertEquals(0L, result);
    }

    // ========================================
    // Higher-Order Functions
    // ========================================

    @Test
    void mapTransformsAllElements() {
        Object result = MezzeTestHelper.run("""
            use std::collections::seq
            let xs = seq::map { xs = [1, 2, 3], f = { x } -> x * 2 }
            match seq::get { xs = xs, i = 0 } is
                Some { val } -> val
                None -> 0
            """);
        assertEquals(2L, result);
    }

    @Test
    void filterKeepsMatchingElements() {
        Object result = MezzeTestHelper.run("""
            use std::collections::seq
            let xs = seq::filter { xs = [1, 2, 3, 4, 5], p = { x } -> x > 2 }
            seq::length { xs = xs }
            """);
        assertEquals(3L, result);
    }

    @Test
    void foldAccumulatesValues() {
        Object result = MezzeTestHelper.run("""
            use std::collections::seq
            seq::fold { xs = [1, 2, 3, 4], init = 0, f = { acc, x } -> acc + x }
            """);
        assertEquals(10L, result);
    }

    // ========================================
    // Predicate Functions
    // ========================================

    @Test
    void anyReturnsTrueWhenPredicateMatches() {
        Object result = MezzeTestHelper.run("""
            use std::collections::seq
            seq::any { xs = [1, 2, 3, 4, 5], p = { x } -> x > 3 }
            """);
        assertEquals(true, result);
    }

    @Test
    void anyReturnsFalseWhenNoElementMatches() {
        Object result = MezzeTestHelper.run("""
            use std::collections::seq
            seq::any { xs = [1, 2, 3], p = { x } -> x > 10 }
            """);
        assertEquals(false, result);
    }

    @Test
    void allReturnsTrueWhenAllElementsMatch() {
        Object result = MezzeTestHelper.run("""
            use std::collections::seq
            seq::all { xs = [2, 4, 6, 8], p = { x } -> x % 2 == 0 }
            """);
        assertEquals(true, result);
    }

    @Test
    void allReturnsFalseWhenAnyElementFails() {
        Object result = MezzeTestHelper.run("""
            use std::collections::seq
            seq::all { xs = [2, 4, 5, 8], p = { x } -> x % 2 == 0 }
            """);
        assertEquals(false, result);
    }

    @Test
    void containsFindsElement() {
        Object result = MezzeTestHelper.run("""
            use std::collections::seq
            seq::contains { xs = [1, 2, 3, 4, 5], x = 3 }
            """);
        assertEquals(true, result);
    }

    @Test
    void containsReturnsFalseForMissingElement() {
        Object result = MezzeTestHelper.run("""
            use std::collections::seq
            seq::contains { xs = [1, 2, 3, 4, 5], x = 10 }
            """);
        assertEquals(false, result);
    }

    // ========================================
    // Find Operations
    // ========================================

    @Test
    void findReturnsFirstMatchingElement() {
        Object result = MezzeTestHelper.run("""
            use std::collections::seq
            match seq::find { xs = [1, 2, 3, 4, 5], p = { x } -> x > 2 } is
                Some { val } -> val
                None -> 0
            """);
        assertEquals(3L, result);
    }

    @Test
    void findReturnsNoneWhenNotFound() {
        Object result = MezzeTestHelper.run("""
            use std::collections::seq
            match seq::find { xs = [1, 2, 3], p = { x } -> x > 10 } is
                Some { val } -> 1
                None -> 0
            """);
        assertEquals(0L, result);
    }

    @Test
    void findIndexReturnsIndexOfFirstMatch() {
        Object result = MezzeTestHelper.run("""
            use std::collections::seq
            match seq::find_index { xs = [1, 2, 3, 4, 5], p = { x } -> x > 2 } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(2L, result);
    }

    @Test
    void findIndexReturnsNoneWhenNotFound() {
        Object result = MezzeTestHelper.run("""
            use std::collections::seq
            match seq::find_index { xs = [1, 2, 3], p = { x } -> x > 10 } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(-1L, result);
    }

    @Test
    void indexOfReturnsIndexOfElement() {
        Object result = MezzeTestHelper.run("""
            use std::collections::seq
            match seq::index_of { xs = [10, 20, 30], x = 20 } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(1L, result);
    }

    // ========================================
    // Partition
    // ========================================

    @Test
    void partitionSplitsByPredicate() {
        Object result = MezzeTestHelper.run("""
            use std::collections::seq
            let result = seq::partition { xs = [1, 2, 3, 4, 5], p = { x } -> x > 2 }
            seq::length { xs = result.pass }
            """);
        assertEquals(3L, result);  // 3, 4, 5
    }

    @Test
    void partitionNonMatchingElements() {
        Object result = MezzeTestHelper.run("""
            use std::collections::seq
            let result = seq::partition { xs = [1, 2, 3, 4, 5], p = { x } -> x > 2 }
            seq::length { xs = result.fail }
            """);
        assertEquals(2L, result);  // 1, 2
    }

    // ========================================
    // Flatten
    // ========================================

    @Test
    void flattenMergesNestedArrays() {
        Object result = MezzeTestHelper.run("""
            use std::collections::seq
            let nested = [[1, 2], [3, 4], [5]]
            let flat = seq::flatten { xss = nested }
            seq::length { xs = flat }
            """);
        assertEquals(5L, result);
    }

    @Test
    void flattenPreservesOrder() {
        Object result = MezzeTestHelper.run("""
            use std::collections::seq
            let nested = [[1, 2], [3, 4]]
            let flat = seq::flatten { xss = nested }
            match seq::get { xs = flat, i = 2 } is
                Some { val } -> val
                None -> 0
            """);
        assertEquals(3L, result);
    }

    // ========================================
    // Accessors
    // ========================================

    @Test
    void headReturnsFirstElement() {
        Object result = MezzeTestHelper.run("""
            use std::collections::seq
            match seq::head { xs = [10, 20, 30] } is
                Some { val } -> val
                None -> 0
            """);
        assertEquals(10L, result);
    }

    @Test
    void lastReturnsLastElement() {
        Object result = MezzeTestHelper.run("""
            use std::collections::seq
            match seq::last { xs = [10, 20, 30] } is
                Some { val } -> val
                None -> 0
            """);
        assertEquals(30L, result);
    }

    @Test
    void lastReturnsNoneForEmptyArray() {
        Object result = MezzeTestHelper.run("""
            use std::collections::seq
            let xs: [Int] = []
            match seq::last { xs = xs } is
                Some { val } -> 1
                None -> 0
            """);
        assertEquals(0L, result);
    }

    @Test
    void initReturnsAllButLast() {
        Object result = MezzeTestHelper.run("""
            use std::collections::seq
            let xs = seq::init { xs = [1, 2, 3, 4] }
            seq::length { xs = xs }
            """);
        assertEquals(3L, result);
    }

    @Test
    void tailReturnsAllButFirst() {
        Object result = MezzeTestHelper.run("""
            use std::collections::seq
            let xs = seq::tail { xs = [1, 2, 3, 4] }
            seq::length { xs = xs }
            """);
        assertEquals(3L, result);
    }

    // ========================================
    // Slicing
    // ========================================

    @Test
    void sliceExtractsSubarray() {
        Object result = MezzeTestHelper.run("""
            use std::collections::seq
            let xs = seq::slice { xs = [1, 2, 3, 4, 5], start = 1, end = 4 }
            seq::length { xs = xs }
            """);
        assertEquals(3L, result);
    }

    @Test
    void takeReturnsFirstNElements() {
        Object result = MezzeTestHelper.run("""
            use std::collections::seq
            let xs = seq::take { xs = [1, 2, 3, 4, 5], n = 3 }
            seq::length { xs = xs }
            """);
        assertEquals(3L, result);
    }

    @Test
    void dropSkipsFirstNElements() {
        Object result = MezzeTestHelper.run("""
            use std::collections::seq
            let xs = seq::drop { xs = [1, 2, 3, 4, 5], n = 2 }
            seq::length { xs = xs }
            """);
        assertEquals(3L, result);
    }

    // ========================================
    // Zip Operations
    // ========================================

    @Test
    void zipPairsElementsFromTwoArrays() {
        Object result = MezzeTestHelper.run("""
            use std::collections::seq
            let zipped = seq::zip { xs = [1, 2, 3], ys = ["a", "b", "c"] }
            seq::length { xs = zipped }
            """);
        assertEquals(3L, result);
    }

    @Test
    void zipTruncatesToShorterLength() {
        Object result = MezzeTestHelper.run("""
            use std::collections::seq
            let zipped = seq::zip { xs = [1, 2], ys = ["a", "b", "c", "d"] }
            seq::length { xs = zipped }
            """);
        assertEquals(2L, result);
    }

    @Test
    void unzipSplitsPairsIntoTwoArrays() {
        Object result = MezzeTestHelper.run("""
            use std::collections::seq
            let pairs = [{ fst = 1, snd = "a" }, { fst = 2, snd = "b" }]
            let result = seq::unzip { xs = pairs }
            seq::length { xs = result.fsts }
            """);
        assertEquals(2L, result);
    }

    // ========================================
    // Unique
    // ========================================

    @Test
    void uniqueRemovesDuplicates() {
        Object result = MezzeTestHelper.run("""
            use std::collections::seq
            let xs = seq::unique { xs = [1, 2, 1, 3, 2, 4, 1] }
            seq::length { xs = xs }
            """);
        assertEquals(4L, result);
    }

    @Test
    void uniquePreservesFirstOccurrence() {
        Object result = MezzeTestHelper.run("""
            use std::collections::seq
            let xs = seq::unique { xs = [3, 1, 2, 1, 3] }
            match seq::get { xs = xs, i = 0 } is
                Some { val } -> val
                None -> 0
            """);
        assertEquals(3L, result);
    }

    // ========================================
    // Transformations
    // ========================================

    @Test
    void reverseReversesArray() {
        Object result = MezzeTestHelper.run("""
            use std::collections::seq
            let xs = seq::reverse { xs = [1, 2, 3] }
            match seq::get { xs = xs, i = 0 } is
                Some { val } -> val
                None -> 0
            """);
        assertEquals(3L, result);
    }

    @Test
    void sortOrdersElements() {
        Object result = MezzeTestHelper.run("""
            use std::collections::seq
            let xs = seq::sort { xs = [3, 1, 4, 1, 5, 9, 2, 6] }
            match seq::get { xs = xs, i = 0 } is
                Some { val } -> val
                None -> 0
            """);
        assertEquals(1L, result);
    }

    @Test
    void concatJoinsTwoArrays() {
        Object result = MezzeTestHelper.run("""
            use std::collections::seq
            let xs = seq::concat { xs = [1, 2], ys = [3, 4] }
            seq::length { xs = xs }
            """);
        assertEquals(4L, result);
    }

    @Test
    void addAppendsElement() {
        Object result = MezzeTestHelper.run("""
            use std::collections::seq
            let xs = seq::add { xs = [1, 2], x = 3 }
            seq::length { xs = xs }
            """);
        assertEquals(3L, result);
    }

    @Test
    void setUpdatesElementAtIndex() {
        Object result = MezzeTestHelper.run("""
            use std::collections::seq
            match seq::set { xs = [1, 2, 3], i = 1, x = 99 } is
                Some { val } -> match seq::get { xs = val, i = 1 } is
                    Some { val } -> val
                    None -> -1
                None -> -2
            """);
        assertEquals(99L, result);
    }

    @Test
    void removeDeletesElementAtIndex() {
        Object result = MezzeTestHelper.run("""
            use std::collections::seq
            match seq::remove { xs = [1, 2, 3], i = 1 } is
                Some { val } -> seq::length { xs = val }
                None -> -1
            """);
        assertEquals(2L, result);
    }
}
