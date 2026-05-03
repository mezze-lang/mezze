package com.mezze.interpreter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for advanced Deque, SortedSet, and SortedMap operations.
 *
 * Tests cover:
 *
 * Deque operations:
 * - min_by, max_by - min/max by key function
 * - get, slice - indexed access
 * - index_of, find_index - search operations
 * - take_while, drop_while - conditional take/drop
 * - split_at, span - split operations
 * - scan - running fold
 * - dedup, dedup_by - remove consecutive duplicates
 * - intersperse - insert separator
 * - zip, zip_with_index - pairing operations
 * - rotate_left, rotate_right - rotation
 * - to_set, to_sorted_set, to_map, to_sorted_map - conversions
 * - from_set, from_sorted_set, from_map, from_sorted_map - conversions
 *
 * SortedSet operations:
 * - partition - split by predicate
 * - min_by, max_by - min/max by key
 * - take, drop - first/skip n elements
 * - range - elements in range
 * - symmetric_difference - XOR of sets
 * - head_set, tail_set - range views
 * - filter_map - filter and transform
 * - collect_seq, collect_list - collect Some values
 *
 * SortedMap operations:
 * - partition - split by predicate
 * - range - entries in key range
 * - floor_key, ceiling_key - nearest keys
 * - head_map, tail_map - views
 * - sub_map - range view
 * - take, drop - first/skip n entries
 */
class DequeAndSortedCollectionsTest {
    // ========================================
    // DEQUE: min_by, max_by
    // ========================================

    @Test
    void dequeMinByReturnsElementWithMinimumKey() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::from_seq { xs = [{ name = "alice", age = 30 }, { name = "bob", age = 25 }, { name = "carol", age = 35 }] }
            match deque::min_by { d = d, f = { val } -> val.age } is
                Some { val } -> val.name
                None -> "none"
            """);
        assertEquals("bob", result);
    }

    @Test
    void dequeMinByReturnsNoneForEmptyDeque() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d: Deque Int = deque::empty {}
            match deque::min_by { d = d, f = { val } -> val } is
                Some { val } -> 1
                None -> 0
            """);
        assertEquals(0L, result);
    }

    @Test
    void dequeMaxByReturnsElementWithMaximumKey() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::from_seq { xs = [{ name = "alice", score = 85 }, { name = "bob", score = 95 }, { name = "carol", score = 90 }] }
            match deque::max_by { d = d, f = { val } -> val.score } is
                Some { val } -> val.name
                None -> "none"
            """);
        assertEquals("bob", result);
    }

    @Test
    void dequeMaxByReturnsNoneForEmptyDeque() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d: Deque Int = deque::empty {}
            match deque::max_by { d = d, f = { val } -> val } is
                Some { val } -> 1
                None -> 0
            """);
        assertEquals(0L, result);
    }

    @Test
    void dequeMinByWorksWithNegativeKeys() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::from_seq { xs = [5, -3, 10, -8, 2] }
            match deque::min_by { d = d, f = { val } -> val } is
                Some { val } -> val
                None -> 0
            """);
        assertEquals(-8L, result);
    }

    // ========================================
    // DEQUE: get, slice
    // ========================================

    @Test
    void dequeGetReturnsElementAtIndex() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::from_seq { xs = [10, 20, 30, 40, 50] }
            match deque::get { d = d, i = 2 } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(30L, result);
    }

    @Test
    void dequeGetReturnsNoneForOutOfBounds() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::from_seq { xs = [10, 20, 30] }
            match deque::get { d = d, i = 10 } is
                Some { val } -> 1
                None -> 0
            """);
        assertEquals(0L, result);
    }

    @Test
    void dequeGetReturnsNoneForNegativeIndex() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::from_seq { xs = [10, 20, 30] }
            match deque::get { d = d, i = -1 } is
                Some { val } -> 1
                None -> 0
            """);
        assertEquals(0L, result);
    }

    @Test
    void dequeSliceExtractsSubrange() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::from_seq { xs = [1, 2, 3, 4, 5, 6, 7] }
            let sliced = deque::slice { d = d, start = 2, end = 5 }
            deque::size { d = sliced }
            """);
        assertEquals(3L, result);
    }

    @Test
    void dequeSliceReturnsCorrectElements() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::from_seq { xs = [10, 20, 30, 40, 50] }
            let sliced = deque::slice { d = d, start = 1, end = 4 }
            match deque::peek_front { d = sliced } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(20L, result);
    }

    @Test
    void dequeSliceHandlesEmptyRange() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::from_seq { xs = [1, 2, 3, 4, 5] }
            let sliced = deque::slice { d = d, start = 3, end = 3 }
            deque::is_empty { d = sliced }
            """);
        assertEquals(true, result);
    }

    // ========================================
    // DEQUE: index_of, find_index
    // ========================================

    @Test
    void dequeIndexOfFindsElement() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::from_seq { xs = [10, 20, 30, 40, 50] }
            match deque::index_of { d = d, x = 30 } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(2L, result);
    }

    @Test
    void dequeIndexOfReturnsNoneForMissingElement() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::from_seq { xs = [10, 20, 30] }
            match deque::index_of { d = d, x = 99 } is
                Some { val } -> 1
                None -> 0
            """);
        assertEquals(0L, result);
    }

    @Test
    void dequeIndexOfFindsFirstOccurrence() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::from_seq { xs = [1, 2, 3, 2, 1] }
            match deque::index_of { d = d, x = 2 } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(1L, result);
    }

    @Test
    void dequeFindIndexFindsFirstMatch() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::from_seq { xs = [1, 2, 3, 4, 5, 6] }
            match deque::find_index { d = d, p = { val } -> val > 3 } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(3L, result);
    }

    @Test
    void dequeFindIndexReturnsNoneWhenNoMatch() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::from_seq { xs = [1, 2, 3] }
            match deque::find_index { d = d, p = { val } -> val > 10 } is
                Some { val } -> 1
                None -> 0
            """);
        assertEquals(0L, result);
    }

    @Test
    void dequeFindIndexWithComplexPredicate() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::from_seq { xs = [2, 4, 6, 7, 8, 10] }
            match deque::find_index { d = d, p = { val } -> val % 2 != 0 } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(3L, result);
    }

    // ========================================
    // DEQUE: take_while, drop_while
    // ========================================

    @Test
    void dequeTakeWhileTakesWhilePredicateHolds() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::from_seq { xs = [1, 2, 3, 4, 5, 1, 2] }
            let taken = deque::take_while { d = d, p = { val } -> val < 4 }
            deque::size { d = taken }
            """);
        assertEquals(3L, result);
    }

    @Test
    void dequeTakeWhileReturnsCorrectElements() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::from_seq { xs = [2, 4, 6, 7, 8] }
            let taken = deque::take_while { d = d, p = { val } -> val % 2 == 0 }
            match deque::peek_back { d = taken } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(6L, result);
    }

    @Test
    void dequeTakeWhileReturnsEmptyWhenFirstFails() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::from_seq { xs = [5, 1, 2, 3] }
            let taken = deque::take_while { d = d, p = { val } -> val < 3 }
            deque::is_empty { d = taken }
            """);
        assertEquals(true, result);
    }

    @Test
    void dequeDropWhileDropsWhilePredicateHolds() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::from_seq { xs = [1, 2, 3, 4, 5, 1, 2] }
            let dropped = deque::drop_while { d = d, p = { val } -> val < 4 }
            deque::size { d = dropped }
            """);
        assertEquals(4L, result);
    }

    @Test
    void dequeDropWhileReturnsCorrectElements() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::from_seq { xs = [2, 4, 6, 7, 8] }
            let dropped = deque::drop_while { d = d, p = { val } -> val % 2 == 0 }
            match deque::peek_front { d = dropped } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(7L, result);
    }

    @Test
    void dequeDropWhileReturnsAllWhenFirstFails() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::from_seq { xs = [5, 1, 2, 3] }
            let dropped = deque::drop_while { d = d, p = { val } -> val < 3 }
            deque::size { d = dropped }
            """);
        assertEquals(4L, result);
    }

    // ========================================
    // DEQUE: split_at, span
    // ========================================

    @Test
    void dequeSplitAtSplitsAtIndex() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::from_seq { xs = [1, 2, 3, 4, 5] }
            let result = deque::split_at { d = d, i = 2 }
            deque::size { d = result.l }
            """);
        assertEquals(2L, result);
    }

    @Test
    void dequeSplitAtRightPartHasRemainder() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::from_seq { xs = [1, 2, 3, 4, 5] }
            let result = deque::split_at { d = d, i = 2 }
            deque::size { d = result.r }
            """);
        assertEquals(3L, result);
    }

    @Test
    void dequeSplitAtFirstElementOfRight() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::from_seq { xs = [10, 20, 30, 40, 50] }
            let result = deque::split_at { d = d, i = 3 }
            match deque::peek_front { d = result.r } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(40L, result);
    }

    @Test
    void dequeSpanSplitsAtPredicateFailure() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::from_seq { xs = [1, 2, 3, 4, 5, 1, 2] }
            let result = deque::span { d = d, p = { val } -> val < 4 }
            deque::size { d = result.l }
            """);
        assertEquals(3L, result);
    }

    @Test
    void dequeSpanRightStartsWithFailingElement() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::from_seq { xs = [1, 2, 3, 4, 5] }
            let result = deque::span { d = d, p = { val } -> val < 4 }
            match deque::peek_front { d = result.r } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(4L, result);
    }

    @Test
    void dequeSpanAllInLeftWhenAllPass() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::from_seq { xs = [1, 2, 3] }
            let result = deque::span { d = d, p = { val } -> val < 10 }
            deque::size { d = result.l } + deque::size { d = result.r }
            """);
        assertEquals(3L, result);
    }

    // ========================================
    // DEQUE: scan
    // ========================================

    @Test
    void dequeScanProducesRunningTotals() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::from_seq { xs = [1, 2, 3] }
            let scanned = deque::scan { d = d, init = 0, f = { acc, x } -> acc + x }
            deque::size { d = scanned }
            """);
        assertEquals(4L, result);  // [0, 1, 3, 6]
    }

    @Test
    void dequeScanFirstElementIsInit() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::from_seq { xs = [1, 2, 3] }
            let scanned = deque::scan { d = d, init = 100, f = { acc, x } -> acc + x }
            match deque::peek_front { d = scanned } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(100L, result);
    }

    @Test
    void dequeScanLastElementIsFinalAccumulator() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::from_seq { xs = [1, 2, 3, 4] }
            let scanned = deque::scan { d = d, init = 0, f = { acc, x } -> acc + x }
            match deque::peek_back { d = scanned } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(10L, result);  // 1+2+3+4 = 10
    }

    @Test
    void dequeScanWithEmptyDeque() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d: Deque Int = deque::empty {}
            let scanned = deque::scan { d = d, init = 42, f = { acc, x } -> acc + x }
            deque::size { d = scanned }
            """);
        assertEquals(1L, result);  // Just [42]
    }

    // ========================================
    // DEQUE: dedup, dedup_by
    // ========================================

    @Test
    void dedupRemovesConsecutiveDuplicates() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::from_seq { xs = [1, 1, 2, 2, 2, 3, 1, 1] }
            let deduped = deque::dedup { d = d }
            deque::size { d = deduped }
            """);
        assertEquals(4L, result);  // [1, 2, 3, 1]
    }

    @Test
    void dedupPreservesNonConsecutiveDuplicates() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::from_seq { xs = [1, 2, 1, 2, 1] }
            let deduped = deque::dedup { d = d }
            deque::size { d = deduped }
            """);
        assertEquals(5L, result);  // All different consecutively
    }

    @Test
    void dedupPreservesOrder() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::from_seq { xs = [5, 5, 3, 3, 7, 7] }
            let deduped = deque::dedup { d = d }
            match deque::peek_front { d = deduped } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(5L, result);
    }

    @Test
    void dedupByRemovesConsecutiveByKey() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::from_seq { xs = [1, 3, 5, 2, 4, 6] }
            let deduped = deque::dedup_by { d = d, f = { val } -> val % 2 }
            deque::size { d = deduped }
            """);
        assertEquals(2L, result);  // [1, 2] - consecutive odd then consecutive even, keep first of each run
    }

    @Test
    void dedupByWithRecords() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::from_seq { xs = [{ cat = "a", val = 1 }, { cat = "a", val = 2 }, { cat = "b", val = 3 }] }
            let deduped = deque::dedup_by { d = d, f = { val } -> val.cat }
            deque::size { d = deduped }
            """);
        assertEquals(2L, result);  // First "a" and first "b"
    }

    // ========================================
    // DEQUE: intersperse
    // ========================================

    @Test
    void intersperseAddsSeparators() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::from_seq { xs = [1, 2, 3] }
            let interspersed = deque::intersperse { d = d, sep = 0 }
            deque::size { d = interspersed }
            """);
        assertEquals(5L, result);  // [1, 0, 2, 0, 3]
    }

    @Test
    void intersperseSeparatorInCorrectPositions() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::from_seq { xs = [10, 20, 30] }
            let interspersed = deque::intersperse { d = d, sep = 99 }
            match deque::get { d = interspersed, i = 1 } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(99L, result);
    }

    @Test
    void intersperseWithSingleElement() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::from_seq { xs = [42] }
            let interspersed = deque::intersperse { d = d, sep = 0 }
            deque::size { d = interspersed }
            """);
        assertEquals(1L, result);  // No separator needed
    }

    @Test
    void intersperseWithEmptyDeque() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d: Deque Int = deque::empty {}
            let interspersed = deque::intersperse { d = d, sep = 0 }
            deque::is_empty { d = interspersed }
            """);
        assertEquals(true, result);
    }

    // ========================================
    // DEQUE: zip, zip_with_index
    // ========================================

    @Test
    void zipPairsElements() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d1 = deque::from_seq { xs = [1, 2, 3] }
            let d2 = deque::from_seq { xs = ["a", "b", "c"] }
            let zipped = deque::zip { d1 = d1, d2 = d2 }
            deque::size { d = zipped }
            """);
        assertEquals(3L, result);
    }

    @Test
    void zipTruncatesToShorter() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d1 = deque::from_seq { xs = [1, 2, 3, 4, 5] }
            let d2 = deque::from_seq { xs = ["a", "b"] }
            let zipped = deque::zip { d1 = d1, d2 = d2 }
            deque::size { d = zipped }
            """);
        assertEquals(2L, result);
    }

    @Test
    void zipAccessPairedElements() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d1 = deque::from_seq { xs = [10, 20, 30] }
            let d2 = deque::from_seq { xs = [1, 2, 3] }
            let zipped = deque::zip { d1 = d1, d2 = d2 }
            match deque::peek_front { d = zipped } is
                Some { val } -> val.fst + val.snd
                None -> -1
            """);
        assertEquals(11L, result);  // 10 + 1
    }

    @Test
    void zipWithIndexPairsWithIndices() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::from_seq { xs = ["a", "b", "c"] }
            let indexed = deque::zip_with_index { d = d }
            deque::size { d = indexed }
            """);
        assertEquals(3L, result);
    }

    @Test
    void zipWithIndexCorrectIndices() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::from_seq { xs = ["x", "y", "z"] }
            let indexed = deque::zip_with_index { d = d }
            match deque::get { d = indexed, i = 2 } is
                Some { val } -> val.idx
                None -> -1
            """);
        assertEquals(2L, result);
    }

    @Test
    void zipWithIndexAccessValue() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::from_seq { xs = [100, 200, 300] }
            let indexed = deque::zip_with_index { d = d }
            match deque::peek_front { d = indexed } is
                Some { val } -> val.val + val.idx
                None -> -1
            """);
        assertEquals(100L, result);  // 100 + 0
    }

    // ========================================
    // DEQUE: rotate_left, rotate_right
    // ========================================

    @Test
    void rotateLeftByOne() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::from_seq { xs = [1, 2, 3, 4, 5] }
            let rotated = deque::rotate_left { d = d, n = 1 }
            match deque::peek_front { d = rotated } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(2L, result);
    }

    @Test
    void rotateLeftWrapsAround() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::from_seq { xs = [1, 2, 3, 4, 5] }
            let rotated = deque::rotate_left { d = d, n = 2 }
            match deque::peek_back { d = rotated } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(2L, result);  // [3, 4, 5, 1, 2] -> back is 2
    }

    @Test
    void rotateLeftPreservesLength() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::from_seq { xs = [1, 2, 3, 4, 5] }
            let rotated = deque::rotate_left { d = d, n = 3 }
            deque::size { d = rotated }
            """);
        assertEquals(5L, result);
    }

    @Test
    void rotateRightByOne() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::from_seq { xs = [1, 2, 3, 4, 5] }
            let rotated = deque::rotate_right { d = d, n = 1 }
            match deque::peek_front { d = rotated } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(5L, result);  // [5, 1, 2, 3, 4]
    }

    @Test
    void rotateRightWrapsAround() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::from_seq { xs = [1, 2, 3, 4, 5] }
            let rotated = deque::rotate_right { d = d, n = 2 }
            match deque::peek_back { d = rotated } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(3L, result);  // [4, 5, 1, 2, 3] -> back is 3
    }

    @Test
    void rotateRightThenLeftIsIdentity() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::from_seq { xs = [1, 2, 3, 4, 5] }
            let rotated = deque::rotate_right { d = deque::rotate_left { d = d, n = 3 }, n = 3 }
            match deque::peek_front { d = rotated } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(1L, result);
    }

    // ========================================
    // DEQUE: to_set, to_sorted_set
    // ========================================

    @Test
    void toSetConvertsToSet() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            use std::collections::set
            let d = deque::from_seq { xs = [1, 2, 3, 2, 1] }
            let s = deque::to_set { d = d }
            set::size { s = s }
            """);
        assertEquals(3L, result);  // Duplicates removed
    }

    @Test
    void toSortedSetConvertsToSortedSet() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            use std::collections::sorted_set
            let d = deque::from_seq { xs = [5, 3, 1, 4, 2] }
            let s = deque::to_sorted_set { d = d }
            sorted_set::size { s = s }
            """);
        assertEquals(5L, result);
    }

    @Test
    void toSortedSetRemovesDuplicates() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            use std::collections::sorted_set
            let d = deque::from_seq { xs = [1, 1, 2, 2, 3, 3] }
            let s = deque::to_sorted_set { d = d }
            sorted_set::size { s = s }
            """);
        assertEquals(3L, result);
    }

    // ========================================
    // DEQUE: to_map, to_sorted_map
    // ========================================

    @Test
    void toMapConvertsKeyValuePairs() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            use std::collections::map
            let d = deque::from_seq { xs = [{ key = "a", value = 1 }, { key = "b", value = 2 }] }
            let m = deque::to_map { d = d }
            map::size { m = m }
            """);
        assertEquals(2L, result);
    }

    @Test
    void toSortedMapConvertsKeyValuePairs() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            use std::collections::sorted_map
            let d = deque::from_seq { xs = [{ key = 3, value = "c" }, { key = 1, value = "a" }, { key = 2, value = "b" }] }
            let m = deque::to_sorted_map { d = d }
            sorted_map::size { m = m }
            """);
        assertEquals(3L, result);
    }

    @Test
    void toSortedMapLastValueWins() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            use std::collections::sorted_map
            let d = deque::from_seq { xs = [{ key = 1, value = "first" }, { key = 1, value = "second" }] }
            let m = deque::to_sorted_map { d = d }
            match sorted_map::get { m = m, k = 1 } is
                Some { val } -> val
                None -> "none"
            """);
        assertEquals("second", result);
    }

    // ========================================
    // DEQUE: from_set, from_sorted_set
    // ========================================

    @Test
    void fromSetConvertsSetToDeque() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            use std::collections::set
            let s = set::empty {}
            let s2 = set::insert { s = s, x = 1 }
            let s3 = set::insert { s = s2, x = 2 }
            let s4 = set::insert { s = s3, x = 3 }
            let d = deque::from_set { s = s4 }
            deque::size { d = d }
            """);
        assertEquals(3L, result);
    }

    @Test
    void fromSortedSetPreservesOrder() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            use std::collections::sorted_set
            let s = sorted_set::empty {}
            let s2 = sorted_set::add { s = s, x = 5 }
            let s3 = sorted_set::add { s = s2, x = 1 }
            let s4 = sorted_set::add { s = s3, x = 3 }
            let d = deque::from_sorted_set { s = s4 }
            match deque::peek_front { d = d } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(1L, result);  // Sorted order: 1, 3, 5
    }

    @Test
    void fromSortedSetToDequeLength() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            use std::collections::sorted_set
            let s = sorted_set::singleton { x = 10 }
            let s2 = sorted_set::add { s = s, x = 20 }
            let s3 = sorted_set::add { s = s2, x = 30 }
            let d = deque::from_sorted_set { s = s3 }
            deque::size { d = d }
            """);
        assertEquals(3L, result);
    }

    // ========================================
    // DEQUE: from_map, from_sorted_map
    // ========================================

    @Test
    void fromMapConvertsToDequeOfRecords() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            use std::collections::map
            let m = map::empty {}
            let m2 = map::insert { m = m, k = "a", v = 1 }
            let m3 = map::insert { m = m2, k = "b", v = 2 }
            let d = deque::from_map { m = m3 }
            deque::size { d = d }
            """);
        assertEquals(2L, result);
    }

    @Test
    void fromSortedMapConvertsInKeyOrder() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            use std::collections::sorted_map
            let m = sorted_map::empty {}
            let m2 = sorted_map::insert { m = m, k = 3, v = "c" }
            let m3 = sorted_map::insert { m = m2, k = 1, v = "a" }
            let m4 = sorted_map::insert { m = m3, k = 2, v = "b" }
            let d = deque::from_sorted_map { m = m4 }
            match deque::peek_front { d = d } is
                Some { val } -> val.key
                None -> -1
            """);
        assertEquals(1L, result);  // First key is 1 (sorted)
    }

    @Test
    void fromSortedMapPreservesValues() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            use std::collections::sorted_map
            let m = sorted_map::singleton { k = 1, v = "hello" }
            let d = deque::from_sorted_map { m = m }
            match deque::peek_front { d = d } is
                Some { val } -> val.value
                None -> "none"
            """);
        assertEquals("hello", result);
    }

    // ========================================
    // SORTED_SET: partition
    // ========================================

    @Test
    void sortedSetPartitionSplitsByPredicate() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_set
            let s = sorted_set::empty {}
            let s2 = sorted_set::add { s = s, x = 1 }
            let s3 = sorted_set::add { s = s2, x = 2 }
            let s4 = sorted_set::add { s = s3, x = 3 }
            let s5 = sorted_set::add { s = s4, x = 4 }
            let s6 = sorted_set::add { s = s5, x = 5 }
            let result = sorted_set::partition { s = s6, p = { val } -> val > 3 }
            sorted_set::size { s = result.pass }
            """);
        assertEquals(2L, result);  // 4, 5
    }

    @Test
    void sortedSetPartitionRestContainsNonMatching() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_set
            let s = sorted_set::empty {}
            let s2 = sorted_set::add { s = s, x = 1 }
            let s3 = sorted_set::add { s = s2, x = 2 }
            let s4 = sorted_set::add { s = s3, x = 3 }
            let s5 = sorted_set::add { s = s4, x = 4 }
            let result = sorted_set::partition { s = s5, p = { val } -> val % 2 == 0 }
            sorted_set::size { s = result.fail }
            """);
        assertEquals(2L, result);  // 1, 3
    }

    @Test
    void sortedSetPartitionPreservesAllElements() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_set
            let s = sorted_set::empty {}
            let s2 = sorted_set::add { s = s, x = 10 }
            let s3 = sorted_set::add { s = s2, x = 20 }
            let s4 = sorted_set::add { s = s3, x = 30 }
            let result = sorted_set::partition { s = s4, p = { val } -> val > 15 }
            sorted_set::size { s = result.pass } + sorted_set::size { s = result.fail }
            """);
        assertEquals(3L, result);
    }

    // ========================================
    // SORTED_SET: min_by, max_by
    // ========================================

    @Test
    void sortedSetMinByReturnsElementWithMinKey() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_set
            let s = sorted_set::empty {}
            let s2 = sorted_set::add { s = s, x = 3 }
            let s3 = sorted_set::add { s = s2, x = 1 }
            let s4 = sorted_set::add { s = s3, x = 2 }
            match sorted_set::min_by { s = s4, f = { val } -> val * val } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(1L, result);  // 1*1 = 1 is smallest
    }

    @Test
    void sortedSetMinByWithTransformation() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_set
            let s = sorted_set::empty {}
            let s2 = sorted_set::add { s = s, x = 5 }
            let s3 = sorted_set::add { s = s2, x = -3 }
            let s4 = sorted_set::add { s = s3, x = 2 }
            match sorted_set::min_by { s = s4, f = { val } -> if val < 0 then 0 - val else val } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(2L, result);  // abs values: 5, 3, 2 -> min is 2
    }

    @Test
    void sortedSetMaxByReturnsElementWithMaxKey() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_set
            let s = sorted_set::empty {}
            let s2 = sorted_set::add { s = s, x = 2 }
            let s3 = sorted_set::add { s = s2, x = 5 }
            let s4 = sorted_set::add { s = s3, x = 3 }
            match sorted_set::max_by { s = s4, f = { val } -> val } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(5L, result);
    }

    @Test
    void sortedSetMaxByReturnsNoneForEmpty() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_set
            let s: SortedSet Int = sorted_set::empty {}
            match sorted_set::max_by { s = s, f = { val } -> val } is
                Some { val } -> 1
                None -> 0
            """);
        assertEquals(0L, result);
    }

    // ========================================
    // SORTED_SET: take, drop
    // ========================================

    @Test
    void sortedSetTakeReturnsFirstNElements() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_set
            let s = sorted_set::empty {}
            let s2 = sorted_set::add { s = s, x = 5 }
            let s3 = sorted_set::add { s = s2, x = 3 }
            let s4 = sorted_set::add { s = s3, x = 1 }
            let s5 = sorted_set::add { s = s4, x = 4 }
            let s6 = sorted_set::add { s = s5, x = 2 }
            let taken = sorted_set::take { s = s6, n = 3 }
            sorted_set::size { s = taken }
            """);
        assertEquals(3L, result);
    }

    @Test
    void sortedSetTakeReturnsSmallestElements() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_set
            let s = sorted_set::empty {}
            let s2 = sorted_set::add { s = s, x = 10 }
            let s3 = sorted_set::add { s = s2, x = 30 }
            let s4 = sorted_set::add { s = s3, x = 20 }
            let taken = sorted_set::take { s = s4, n = 2 }
            match sorted_set::max { s = taken } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(20L, result);  // [10, 20] -> max is 20
    }

    @Test
    void sortedSetDropSkipsFirstNElements() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_set
            let s = sorted_set::empty {}
            let s2 = sorted_set::add { s = s, x = 1 }
            let s3 = sorted_set::add { s = s2, x = 2 }
            let s4 = sorted_set::add { s = s3, x = 3 }
            let s5 = sorted_set::add { s = s4, x = 4 }
            let s6 = sorted_set::add { s = s5, x = 5 }
            let dropped = sorted_set::drop { s = s6, n = 2 }
            sorted_set::size { s = dropped }
            """);
        assertEquals(3L, result);  // [3, 4, 5]
    }

    @Test
    void sortedSetDropReturnsLargestElements() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_set
            let s = sorted_set::empty {}
            let s2 = sorted_set::add { s = s, x = 10 }
            let s3 = sorted_set::add { s = s2, x = 20 }
            let s4 = sorted_set::add { s = s3, x = 30 }
            let dropped = sorted_set::drop { s = s4, n = 1 }
            match sorted_set::min { s = dropped } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(20L, result);  // [20, 30] -> min is 20
    }

    // ========================================
    // SORTED_SET: range
    // ========================================

    @Test
    void sortedSetRangeReturnsElementsInRange() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_set
            let s = sorted_set::empty {}
            let s2 = sorted_set::add { s = s, x = 1 }
            let s3 = sorted_set::add { s = s2, x = 2 }
            let s4 = sorted_set::add { s = s3, x = 3 }
            let s5 = sorted_set::add { s = s4, x = 4 }
            let s6 = sorted_set::add { s = s5, x = 5 }
            let ranged = sorted_set::range { s = s6, lo = 2, hi = 4 }
            sorted_set::size { s = ranged }
            """);
        assertEquals(3L, result);  // [2, 3, 4]
    }

    @Test
    void sortedSetRangeIsInclusive() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_set
            let s = sorted_set::empty {}
            let s2 = sorted_set::add { s = s, x = 10 }
            let s3 = sorted_set::add { s = s2, x = 20 }
            let s4 = sorted_set::add { s = s3, x = 30 }
            let ranged = sorted_set::range { s = s4, lo = 10, hi = 30 }
            sorted_set::size { s = ranged }
            """);
        assertEquals(3L, result);  // All elements
    }

    @Test
    void sortedSetRangeReturnsEmptyWhenNoMatch() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_set
            let s = sorted_set::empty {}
            let s2 = sorted_set::add { s = s, x = 1 }
            let s3 = sorted_set::add { s = s2, x = 10 }
            let ranged = sorted_set::range { s = s3, lo = 5, hi = 7 }
            sorted_set::is_empty { s = ranged }
            """);
        assertEquals(true, result);
    }

    // ========================================
    // SORTED_SET: symmetric_difference
    // ========================================

    @Test
    void sortedSetSymmetricDifferenceReturnsXOR() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_set
            let s1 = sorted_set::empty {}
            let s1b = sorted_set::add { s = s1, x = 1 }
            let s1c = sorted_set::add { s = s1b, x = 2 }
            let s1d = sorted_set::add { s = s1c, x = 3 }
            let s2 = sorted_set::empty {}
            let s2b = sorted_set::add { s = s2, x = 2 }
            let s2c = sorted_set::add { s = s2b, x = 3 }
            let s2d = sorted_set::add { s = s2c, x = 4 }
            let symDiff = sorted_set::symmetric_difference { s1 = s1d, s2 = s2d }
            sorted_set::size { s = symDiff }
            """);
        assertEquals(2L, result);  // {1, 4} - elements in only one set
    }

    @Test
    void sortedSetSymmetricDifferenceIsCommutative() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_set
            let s1 = sorted_set::singleton { x = 1 }
            let s1b = sorted_set::add { s = s1, x = 2 }
            let s2 = sorted_set::singleton { x = 2 }
            let s2b = sorted_set::add { s = s2, x = 3 }
            let diff1 = sorted_set::symmetric_difference { s1 = s1b, s2 = s2b }
            let diff2 = sorted_set::symmetric_difference { s1 = s2b, s2 = s1b }
            sorted_set::equals { s1 = diff1, s2 = diff2 }
            """);
        assertEquals(true, result);
    }

    @Test
    void sortedSetSymmetricDifferenceWithDisjointSets() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_set
            let s1 = sorted_set::singleton { x = 1 }
            let s1b = sorted_set::add { s = s1, x = 2 }
            let s2 = sorted_set::singleton { x = 3 }
            let s2b = sorted_set::add { s = s2, x = 4 }
            let symDiff = sorted_set::symmetric_difference { s1 = s1b, s2 = s2b }
            sorted_set::size { s = symDiff }
            """);
        assertEquals(4L, result);  // All elements (union of disjoint sets)
    }

    // ========================================
    // SORTED_SET: head_set, tail_set
    // ========================================

    @Test
    void sortedSetHeadSetReturnsElementsLessThan() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_set
            let s = sorted_set::empty {}
            let s2 = sorted_set::add { s = s, x = 1 }
            let s3 = sorted_set::add { s = s2, x = 2 }
            let s4 = sorted_set::add { s = s3, x = 3 }
            let s5 = sorted_set::add { s = s4, x = 4 }
            let s6 = sorted_set::add { s = s5, x = 5 }
            let head = sorted_set::head_set { s = s6, to = 3 }
            sorted_set::size { s = head }
            """);
        assertEquals(2L, result);  // [1, 2] (strictly less than 3)
    }

    @Test
    void sortedSetHeadSetExcludesBoundary() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_set
            let s = sorted_set::singleton { x = 10 }
            let s2 = sorted_set::add { s = s, x = 20 }
            let s3 = sorted_set::add { s = s2, x = 30 }
            let head = sorted_set::head_set { s = s3, to = 20 }
            sorted_set::contains { s = head, x = 20 }
            """);
        assertEquals(false, result);
    }

    @Test
    void sortedSetTailSetReturnsElementsGreaterOrEqual() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_set
            let s = sorted_set::empty {}
            let s2 = sorted_set::add { s = s, x = 1 }
            let s3 = sorted_set::add { s = s2, x = 2 }
            let s4 = sorted_set::add { s = s3, x = 3 }
            let s5 = sorted_set::add { s = s4, x = 4 }
            let s6 = sorted_set::add { s = s5, x = 5 }
            let tail = sorted_set::tail_set { s = s6, from = 3 }
            sorted_set::size { s = tail }
            """);
        assertEquals(3L, result);  // [3, 4, 5] (>= 3)
    }

    @Test
    void sortedSetTailSetIncludesBoundary() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_set
            let s = sorted_set::singleton { x = 10 }
            let s2 = sorted_set::add { s = s, x = 20 }
            let s3 = sorted_set::add { s = s2, x = 30 }
            let tail = sorted_set::tail_set { s = s3, from = 20 }
            sorted_set::contains { s = tail, x = 20 }
            """);
        assertEquals(true, result);
    }

    // ========================================
    // SORTED_SET: filter_map
    // ========================================

    @Test
    void sortedSetFilterMapFiltersAndTransforms() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_set
            let s = sorted_set::empty {}
            let s2 = sorted_set::add { s = s, x = 1 }
            let s3 = sorted_set::add { s = s2, x = 2 }
            let s4 = sorted_set::add { s = s3, x = 3 }
            let s5 = sorted_set::add { s = s4, x = 4 }
            let filtered = sorted_set::filter_map { set = s5, f = { val } ->
                if val % 2 == 0 then Some { val = val * 10 } else None
            }
            sorted_set::size { s = filtered }
            """);
        assertEquals(2L, result);  // [20, 40]
    }

    @Test
    void sortedSetFilterMapTransformsValues() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_set
            let s = sorted_set::singleton { x = 5 }
            let s2 = sorted_set::add { s = s, x = 10 }
            let filtered = sorted_set::filter_map { set = s2, f = { val } ->
                if val > 3 then Some { val = val * 2 } else None
            }
            match sorted_set::min { s = filtered } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(10L, result);  // 5*2 = 10
    }

    @Test
    void sortedSetFilterMapReturnsEmptyWhenAllNone() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_set
            let s = sorted_set::singleton { x = 1 }
            let s2 = sorted_set::add { s = s, x = 2 }
            let filtered = sorted_set::filter_map { set = s2, f = { val } -> None }
            sorted_set::is_empty { s = filtered }
            """);
        assertEquals(true, result);
    }

    // ========================================
    // SORTED_SET: collect_seq, collect_list
    // ========================================

    @Test
    void sortedSetCollectSeqCollectsSomeValues() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_set
            use std::collections::seq
            let s = sorted_set::empty {}
            let s2 = sorted_set::add { s = s, x = 1 }
            let s3 = sorted_set::add { s = s2, x = 2 }
            let s4 = sorted_set::add { s = s3, x = 3 }
            let s5 = sorted_set::add { s = s4, x = 4 }
            let collected = sorted_set::collect_seq { set = s5, f = { val } ->
                if val > 2 then Some { val = val * 10 } else None
            }
            seq::size { xs = collected }
            """);
        assertEquals(2L, result);  // [30, 40]
    }

    @Test
    void sortedSetCollectSeqPreservesOrder() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_set
            use std::collections::seq
            let s = sorted_set::singleton { x = 10 }
            let s2 = sorted_set::add { s = s, x = 5 }
            let s3 = sorted_set::add { s = s2, x = 15 }
            let collected = sorted_set::collect_seq { set = s3, f = { val } ->
                Some { val = val }
            }
            match seq::first { xs = collected } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(5L, result);  // First in sorted order
    }

    @Test
    void sortedSetCollectListCollectsSomeValues() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_set
            use std::collections::list
            let s = sorted_set::singleton { x = 1 }
            let s2 = sorted_set::add { s = s, x = 2 }
            let s3 = sorted_set::add { s = s2, x = 3 }
            let collected = sorted_set::collect_list { set = s3, f = { val } ->
                if val % 2 == 1 then Some { val = val } else None
            }
            list::size { xs = collected }
            """);
        assertEquals(2L, result);  // [1, 3]
    }

    // ========================================
    // SORTED_MAP: partition
    // ========================================

    @Test
    void sortedMapPartitionSplitsByPredicate() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_map
            let m = sorted_map::empty {}
            let m2 = sorted_map::insert { m = m, k = 1, v = "a" }
            let m3 = sorted_map::insert { m = m2, k = 2, v = "b" }
            let m4 = sorted_map::insert { m = m3, k = 3, v = "c" }
            let m5 = sorted_map::insert { m = m4, k = 4, v = "d" }
            let result = sorted_map::partition { m = m5, p = { k, v } -> k > 2 }
            sorted_map::size { m = result.pass }
            """);
        assertEquals(2L, result);  // keys 3, 4
    }

    @Test
    void sortedMapPartitionFailContainsNonMatching() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_map
            let m = sorted_map::empty {}
            let m2 = sorted_map::insert { m = m, k = 1, v = 10 }
            let m3 = sorted_map::insert { m = m2, k = 2, v = 20 }
            let m4 = sorted_map::insert { m = m3, k = 3, v = 30 }
            let result = sorted_map::partition { m = m4, p = { k, v } -> v > 15 }
            sorted_map::size { m = result.fail }
            """);
        assertEquals(1L, result);  // key 1 with value 10
    }

    @Test
    void sortedMapPartitionPreservesAllEntries() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_map
            let m = sorted_map::singleton { k = "a", v = 1 }
            let m2 = sorted_map::insert { m = m, k = "b", v = 2 }
            let m3 = sorted_map::insert { m = m2, k = "c", v = 3 }
            let result = sorted_map::partition { m = m3, p = { k, v } -> v % 2 == 0 }
            sorted_map::size { m = result.pass } + sorted_map::size { m = result.fail }
            """);
        assertEquals(3L, result);
    }

    // ========================================
    // SORTED_MAP: range
    // ========================================

    @Test
    void sortedMapRangeReturnsEntriesInKeyRange() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_map
            let m = sorted_map::empty {}
            let m2 = sorted_map::insert { m = m, k = 1, v = "a" }
            let m3 = sorted_map::insert { m = m2, k = 2, v = "b" }
            let m4 = sorted_map::insert { m = m3, k = 3, v = "c" }
            let m5 = sorted_map::insert { m = m4, k = 4, v = "d" }
            let m6 = sorted_map::insert { m = m5, k = 5, v = "e" }
            let ranged = sorted_map::range { m = m6, lo = 2, hi = 4 }
            sorted_map::size { m = ranged }
            """);
        assertEquals(3L, result);  // keys 2, 3, 4
    }

    @Test
    void sortedMapRangeIncludesBoundaries() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_map
            let m = sorted_map::singleton { k = 10, v = "x" }
            let m2 = sorted_map::insert { m = m, k = 20, v = "y" }
            let m3 = sorted_map::insert { m = m2, k = 30, v = "z" }
            let ranged = sorted_map::range { m = m3, lo = 10, hi = 30 }
            sorted_map::size { m = ranged }
            """);
        assertEquals(3L, result);
    }

    @Test
    void sortedMapRangeReturnsEmptyWhenNoMatch() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_map
            let m = sorted_map::singleton { k = 1, v = "a" }
            let m2 = sorted_map::insert { m = m, k = 10, v = "b" }
            let ranged = sorted_map::range { m = m2, lo = 5, hi = 7 }
            sorted_map::is_empty { m = ranged }
            """);
        assertEquals(true, result);
    }

    // ========================================
    // SORTED_MAP: floor_key, ceiling_key
    // ========================================

    @Test
    void sortedMapFloorKeyReturnsLargestKeyLessOrEqual() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_map
            let m = sorted_map::empty {}
            let m2 = sorted_map::insert { m = m, k = 10, v = "a" }
            let m3 = sorted_map::insert { m = m2, k = 20, v = "b" }
            let m4 = sorted_map::insert { m = m3, k = 30, v = "c" }
            match sorted_map::floor_key { m = m4, k = 25 } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(20L, result);
    }

    @Test
    void sortedMapFloorKeyReturnsExactMatch() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_map
            let m = sorted_map::singleton { k = 10, v = "a" }
            let m2 = sorted_map::insert { m = m, k = 20, v = "b" }
            match sorted_map::floor_key { m = m2, k = 20 } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(20L, result);
    }

    @Test
    void sortedMapFloorKeyReturnsNoneWhenNoSmaller() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_map
            let m = sorted_map::singleton { k = 10, v = "a" }
            let m2 = sorted_map::insert { m = m, k = 20, v = "b" }
            match sorted_map::floor_key { m = m2, k = 5 } is
                Some { val } -> 1
                None -> 0
            """);
        assertEquals(0L, result);
    }

    @Test
    void sortedMapCeilingKeyReturnsSmallestKeyGreaterOrEqual() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_map
            let m = sorted_map::empty {}
            let m2 = sorted_map::insert { m = m, k = 10, v = "a" }
            let m3 = sorted_map::insert { m = m2, k = 20, v = "b" }
            let m4 = sorted_map::insert { m = m3, k = 30, v = "c" }
            match sorted_map::ceiling_key { m = m4, k = 15 } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(20L, result);
    }

    @Test
    void sortedMapCeilingKeyReturnsExactMatch() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_map
            let m = sorted_map::singleton { k = 10, v = "a" }
            let m2 = sorted_map::insert { m = m, k = 20, v = "b" }
            match sorted_map::ceiling_key { m = m2, k = 10 } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(10L, result);
    }

    @Test
    void sortedMapCeilingKeyReturnsNoneWhenNoLarger() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_map
            let m = sorted_map::singleton { k = 10, v = "a" }
            let m2 = sorted_map::insert { m = m, k = 20, v = "b" }
            match sorted_map::ceiling_key { m = m2, k = 25 } is
                Some { val } -> 1
                None -> 0
            """);
        assertEquals(0L, result);
    }

    // ========================================
    // SORTED_MAP: head_map, tail_map
    // ========================================

    @Test
    void sortedMapHeadMapReturnsEntriesWithKeysLessThan() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_map
            let m = sorted_map::empty {}
            let m2 = sorted_map::insert { m = m, k = 1, v = "a" }
            let m3 = sorted_map::insert { m = m2, k = 2, v = "b" }
            let m4 = sorted_map::insert { m = m3, k = 3, v = "c" }
            let m5 = sorted_map::insert { m = m4, k = 4, v = "d" }
            let head = sorted_map::head_map { m = m5, to = 3 }
            sorted_map::size { m = head }
            """);
        assertEquals(2L, result);  // keys 1, 2
    }

    @Test
    void sortedMapHeadMapExcludesBoundary() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_map
            let m = sorted_map::singleton { k = 10, v = "x" }
            let m2 = sorted_map::insert { m = m, k = 20, v = "y" }
            let m3 = sorted_map::insert { m = m2, k = 30, v = "z" }
            let head = sorted_map::head_map { m = m3, to = 20 }
            sorted_map::contains_key { m = head, k = 20 }
            """);
        assertEquals(false, result);
    }

    @Test
    void sortedMapTailMapReturnsEntriesWithKeysGreaterOrEqual() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_map
            let m = sorted_map::empty {}
            let m2 = sorted_map::insert { m = m, k = 1, v = "a" }
            let m3 = sorted_map::insert { m = m2, k = 2, v = "b" }
            let m4 = sorted_map::insert { m = m3, k = 3, v = "c" }
            let m5 = sorted_map::insert { m = m4, k = 4, v = "d" }
            let tail = sorted_map::tail_map { m = m5, from = 3 }
            sorted_map::size { m = tail }
            """);
        assertEquals(2L, result);  // keys 3, 4
    }

    @Test
    void sortedMapTailMapIncludesBoundary() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_map
            let m = sorted_map::singleton { k = 10, v = "x" }
            let m2 = sorted_map::insert { m = m, k = 20, v = "y" }
            let m3 = sorted_map::insert { m = m2, k = 30, v = "z" }
            let tail = sorted_map::tail_map { m = m3, from = 20 }
            sorted_map::contains_key { m = tail, k = 20 }
            """);
        assertEquals(true, result);
    }

    // ========================================
    // SORTED_MAP: sub_map
    // ========================================

    @Test
    void sortedMapSubMapReturnsEntriesInRange() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_map
            let m = sorted_map::empty {}
            let m2 = sorted_map::insert { m = m, k = 1, v = "a" }
            let m3 = sorted_map::insert { m = m2, k = 2, v = "b" }
            let m4 = sorted_map::insert { m = m3, k = 3, v = "c" }
            let m5 = sorted_map::insert { m = m4, k = 4, v = "d" }
            let m6 = sorted_map::insert { m = m5, k = 5, v = "e" }
            let sub = sorted_map::sub_map { m = m6, lo = 2, hi = 4 }
            sorted_map::size { m = sub }
            """);
        assertEquals(2L, result);  // keys 2, 3 (lo inclusive, hi exclusive)
    }

    @Test
    void sortedMapSubMapIsLoInclusiveHiExclusive() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_map
            let m = sorted_map::singleton { k = 10, v = "a" }
            let m2 = sorted_map::insert { m = m, k = 20, v = "b" }
            let m3 = sorted_map::insert { m = m2, k = 30, v = "c" }
            let sub = sorted_map::sub_map { m = m3, lo = 10, hi = 30 }
            sorted_map::contains_key { m = sub, k = 30 }
            """);
        assertEquals(false, result);  // hi is exclusive
    }

    @Test
    void sortedMapSubMapIncludesLo() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_map
            let m = sorted_map::singleton { k = 10, v = "a" }
            let m2 = sorted_map::insert { m = m, k = 20, v = "b" }
            let m3 = sorted_map::insert { m = m2, k = 30, v = "c" }
            let sub = sorted_map::sub_map { m = m3, lo = 10, hi = 30 }
            sorted_map::contains_key { m = sub, k = 10 }
            """);
        assertEquals(true, result);  // lo is inclusive
    }

    // ========================================
    // SORTED_MAP: take, drop
    // ========================================

    @Test
    void sortedMapTakeReturnsFirstNEntries() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_map
            let m = sorted_map::empty {}
            let m2 = sorted_map::insert { m = m, k = 5, v = "e" }
            let m3 = sorted_map::insert { m = m2, k = 3, v = "c" }
            let m4 = sorted_map::insert { m = m3, k = 1, v = "a" }
            let m5 = sorted_map::insert { m = m4, k = 4, v = "d" }
            let m6 = sorted_map::insert { m = m5, k = 2, v = "b" }
            let taken = sorted_map::take { m = m6, n = 3 }
            sorted_map::size { m = taken }
            """);
        assertEquals(3L, result);
    }

    @Test
    void sortedMapTakeReturnsSmallestKeys() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_map
            let m = sorted_map::singleton { k = 10, v = "a" }
            let m2 = sorted_map::insert { m = m, k = 30, v = "c" }
            let m3 = sorted_map::insert { m = m2, k = 20, v = "b" }
            let taken = sorted_map::take { m = m3, n = 2 }
            match sorted_map::max_key { m = taken } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(20L, result);  // [10, 20] -> max is 20
    }

    @Test
    void sortedMapDropSkipsFirstNEntries() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_map
            let m = sorted_map::empty {}
            let m2 = sorted_map::insert { m = m, k = 1, v = "a" }
            let m3 = sorted_map::insert { m = m2, k = 2, v = "b" }
            let m4 = sorted_map::insert { m = m3, k = 3, v = "c" }
            let m5 = sorted_map::insert { m = m4, k = 4, v = "d" }
            let m6 = sorted_map::insert { m = m5, k = 5, v = "e" }
            let dropped = sorted_map::drop { m = m6, n = 2 }
            sorted_map::size { m = dropped }
            """);
        assertEquals(3L, result);  // [3, 4, 5]
    }

    @Test
    void sortedMapDropReturnsLargestKeys() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_map
            let m = sorted_map::singleton { k = 10, v = "a" }
            let m2 = sorted_map::insert { m = m, k = 20, v = "b" }
            let m3 = sorted_map::insert { m = m2, k = 30, v = "c" }
            let dropped = sorted_map::drop { m = m3, n = 1 }
            match sorted_map::min_key { m = dropped } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(20L, result);  // [20, 30] -> min is 20
    }

    @Test
    void sortedMapDropMoreThanSizeReturnsEmpty() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_map
            let m = sorted_map::singleton { k = 1, v = "a" }
            let m2 = sorted_map::insert { m = m, k = 2, v = "b" }
            let dropped = sorted_map::drop { m = m2, n = 10 }
            sorted_map::is_empty { m = dropped }
            """);
        assertEquals(true, result);
    }
}
