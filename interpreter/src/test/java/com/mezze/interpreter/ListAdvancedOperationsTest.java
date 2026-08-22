package com.mezze.interpreter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for advanced List operations in the Mezze language.
 *
 * Tests cover:
 * - Equality operations (equals)
 * - Search operations (index_of, last_index_of)
 * - Slicing operations (slice, chunk, windows, sliding, chunks_exact)
 * - Sorting operations (sort_by_key)
 * - Rotation operations (rotate_left, rotate_right)
 * - Cross-collection conversions (to_seq, from_seq, to_set, from_set, to_deque, from_deque)
 * - Statistical operations (average, variance, stddev, median)
 * - Comparison operations (compare, is_sorted, is_sorted_by, is_sorted_descending)
 * - Binary search operations (binary_search, lower_bound, upper_bound, equal_range)
 */
class ListAdvancedOperationsTest {
    // ========================================
    // Equality Operations
    // ========================================

    @Test
    void equalsReturnsTrueForEqualLists() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            list::equals { xs = list::from_seq { xs = [1, 2, 3] }, ys = list::from_seq { xs = [1, 2, 3] } }
            """);
        assertEquals(true, result);
    }

    @Test
    void equalsReturnsFalseForDifferentLists() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            list::equals { xs = list::from_seq { xs = [1, 2, 3] }, ys = list::from_seq { xs = [1, 2, 4] } }
            """);
        assertEquals(false, result);
    }

    @Test
    void equalsReturnsFalseForDifferentLengths() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            list::equals { xs = list::from_seq { xs = [1, 2] }, ys = list::from_seq { xs = [1, 2, 3] } }
            """);
        assertEquals(false, result);
    }

    @Test
    void equalsReturnsTrueForEmptyLists() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            list::equals { xs = list::empty {}, ys = list::empty {} }
            """);
        assertEquals(true, result);
    }

    // ========================================
    // Index Of Operations
    // ========================================

    @Test
    void indexOfReturnsFirstOccurrence() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [1, 2, 3, 2, 5] }
            match list::index_of { xs = xs, x = 2 } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(1L, result);
    }

    @Test
    void indexOfReturnsNoneForMissingElement() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [1, 2, 3] }
            match list::index_of { xs = xs, x = 99 } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(-1L, result);
    }

    @Test
    void indexOfWorksOnEmptyList() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            match list::index_of { xs = list::empty {}, x = 1 } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(-1L, result);
    }

    // ========================================
    // Last Index Of Operations
    // ========================================

    @Test
    void lastIndexOfReturnsLastOccurrence() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [1, 2, 3, 2, 5] }
            match list::last_index_of { xs = xs, x = 2 } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(3L, result);
    }

    @Test
    void lastIndexOfReturnsNoneForMissingElement() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [1, 2, 3] }
            match list::last_index_of { xs = xs, x = 99 } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(-1L, result);
    }

    @Test
    void lastIndexOfForSingleOccurrence() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [1, 2, 3, 4, 5] }
            match list::last_index_of { xs = xs, x = 3 } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(2L, result);
    }

    // ========================================
    // Slice Operations
    // ========================================

    @Test
    void sliceExtractsSublist() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [1, 2, 3, 4, 5] }
            let sliced = list::slice { xs = xs, start = 1, end = 4 }
            list::size { xs = sliced }
            """);
        assertEquals(3L, result);
    }

    @Test
    void sliceReturnsCorrectElements() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [10, 20, 30, 40, 50] }
            let sliced = list::slice { xs = xs, start = 1, end = 4 }
            match list::head { xs = sliced } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(20L, result);
    }

    @Test
    void sliceWithOutOfBoundsReturnsEmptyOrPartial() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [1, 2, 3] }
            let sliced = list::slice { xs = xs, start = 5, end = 10 }
            list::is_empty { xs = sliced }
            """);
        assertEquals(true, result);
    }

    // ========================================
    // Chunk Operations
    // ========================================

    @Test
    void chunkSplitsIntoChunks() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [1, 2, 3, 4, 5] }
            let chunks = list::chunk { xs = xs, size = 2 }
            list::size { xs = chunks }
            """);
        assertEquals(3L, result);  // [[1,2], [3,4], [5]]
    }

    @Test
    void chunkFirstChunkHasCorrectSize() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [1, 2, 3, 4, 5] }
            let chunks = list::chunk { xs = xs, size = 2 }
            match list::head { xs = chunks } is
                Some { val } -> list::size { xs = val }
                None -> -1
            """);
        assertEquals(2L, result);
    }

    @Test
    void chunkLastChunkMayBeSmaller() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [1, 2, 3, 4, 5] }
            let chunks = list::chunk { xs = xs, size = 2 }
            match list::last { xs = chunks } is
                Some { val } -> list::size { xs = val }
                None -> -1
            """);
        assertEquals(1L, result);  // [5]
    }

    // ========================================
    // Windows Operations
    // ========================================

    @Test
    void windowsCreatesSlidingWindows() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [1, 2, 3, 4] }
            let windows = list::windows { xs = xs, size = 2 }
            list::size { xs = windows }
            """);
        assertEquals(3L, result);  // [[1,2], [2,3], [3,4]]
    }

    @Test
    void windowsFirstWindowHasCorrectElements() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [10, 20, 30, 40] }
            let windows = list::windows { xs = xs, size = 2 }
            match list::head { xs = windows } is
                Some { val } ->
                    match list::head { xs = val } is
                        Some { val } -> val
                        None -> -1
                None -> -2
            """);
        assertEquals(10L, result);
    }

    @Test
    void windowsLastWindowHasCorrectElements() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [10, 20, 30, 40] }
            let windows = list::windows { xs = xs, size = 2 }
            match list::last { xs = windows } is
                Some { val } ->
                    match list::head { xs = val } is
                        Some { val } -> val
                        None -> -1
                None -> -2
            """);
        assertEquals(30L, result);  // [30, 40]
    }

    // ========================================
    // Sliding Operations (with step)
    // ========================================

    @Test
    void slidingWithStepCreatesWindows() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [1, 2, 3, 4, 5, 6] }
            let windows = list::sliding { xs = xs, size = 2, step = 2 }
            list::size { xs = windows }
            """);
        assertEquals(3L, result);  // [[1,2], [3,4], [5,6]]
    }

    @Test
    void slidingWithLargerStepCreatesFewerWindows() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [1, 2, 3, 4, 5, 6] }
            let windows = list::sliding { xs = xs, size = 2, step = 3 }
            list::size { xs = windows }
            """);
        assertEquals(2L, result);  // [[1,2], [4,5]]
    }

    @Test
    void slidingReturnsCorrectFirstWindow() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [10, 20, 30, 40, 50, 60] }
            let windows = list::sliding { xs = xs, size = 2, step = 2 }
            match list::head { xs = windows } is
                Some { val } ->
                    match list::last { xs = val } is
                        Some { val } -> val
                        None -> -1
                None -> -2
            """);
        assertEquals(20L, result);  // [10, 20] -> last is 20
    }

    // ========================================
    // Chunks Exact Operations
    // ========================================

    @Test
    void chunksExactDropsIncompleteChunk() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [1, 2, 3, 4, 5] }
            let chunks = list::chunks_exact { xs = xs, size = 2 }
            list::size { xs = chunks }
            """);
        assertEquals(2L, result);  // [[1,2], [3,4]] - drops [5]
    }

    @Test
    void chunksExactAllChunksSameSize() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [1, 2, 3, 4, 5, 6] }
            let chunks = list::chunks_exact { xs = xs, size = 3 }
            let allSizeThree = list::all { xs = chunks, p = { x } -> list::size { xs = x } == 3 }
            allSizeThree
            """);
        assertEquals(true, result);
    }

    @Test
    void chunksExactOnExactMultiple() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [1, 2, 3, 4, 5, 6] }
            let chunks = list::chunks_exact { xs = xs, size = 2 }
            list::size { xs = chunks }
            """);
        assertEquals(3L, result);  // [[1,2], [3,4], [5,6]]
    }

    // ========================================
    // Sort By Key Operations
    // ========================================

    @Test
    void sortByKeySortsByExtractedKey() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [{name = "z", age = 30}, {name = "a", age = 20}, {name = "m", age = 25}] }
            let sorted = list::sort_by_key { xs = xs, f = { x } -> x.age }
            match list::head { xs = sorted } is
                Some { val } -> val.age
                None -> -1
            """);
        assertEquals(20L, result);
    }

    @Test
    void sortByKeyPreservesAllElements() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [{v = 3}, {v = 1}, {v = 2}] }
            let sorted = list::sort_by_key { xs = xs, f = { x } -> x.v }
            list::size { xs = sorted }
            """);
        assertEquals(3L, result);
    }

    @Test
    void sortByKeyOnNumbersExtractingNegative() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [1, 2, 3, 4, 5] }
            let sorted = list::sort_by_key { xs = xs, f = { x } -> 0 - x }
            match list::head { xs = sorted } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(5L, result);  // Sorted in descending order
    }

    // ========================================
    // Rotate Left Operations
    // ========================================

    @Test
    void rotateLeftByTwoPositions() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [1, 2, 3, 4, 5] }
            let rotated = list::rotate_left { xs = xs, n = 2 }
            match list::head { xs = rotated } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(3L, result);  // [3, 4, 5, 1, 2]
    }

    @Test
    void rotateLeftPreservesLength() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [1, 2, 3, 4, 5] }
            let rotated = list::rotate_left { xs = xs, n = 2 }
            list::size { xs = rotated }
            """);
        assertEquals(5L, result);
    }

    @Test
    void rotateLeftByZeroNoChange() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [1, 2, 3] }
            let rotated = list::rotate_left { xs = xs, n = 0 }
            match list::head { xs = rotated } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(1L, result);
    }

    // ========================================
    // Rotate Right Operations
    // ========================================

    @Test
    void rotateRightByTwoPositions() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [1, 2, 3, 4, 5] }
            let rotated = list::rotate_right { xs = xs, n = 2 }
            match list::head { xs = rotated } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(4L, result);  // [4, 5, 1, 2, 3]
    }

    @Test
    void rotateRightPreservesLength() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [1, 2, 3, 4, 5] }
            let rotated = list::rotate_right { xs = xs, n = 2 }
            list::size { xs = rotated }
            """);
        assertEquals(5L, result);
    }

    @Test
    void rotateRightThenLeftIsIdentity() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [1, 2, 3, 4, 5] }
            let rotated = list::rotate_left { xs = list::rotate_right { xs = xs, n = 2 }, n = 2 }
            list::equals { xs = xs, ys = rotated }
            """);
        assertEquals(true, result);
    }

    // ========================================
    // To/From Seq Conversions
    // ========================================

    @Test
    void toSeqConvertsListToArray() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            use std::collections::seq
            let xs = list::from_seq { xs = [1, 2, 3] }
            let arr = list::to_seq { xs = xs }
            seq::size { xs = arr }
            """);
        assertEquals(3L, result);
    }

    @Test
    void fromSeqConvertsArrayToList() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let arr = [1, 2, 3, 4, 5]
            let xs = list::from_seq { xs = arr }
            list::size { xs = xs }
            """);
        assertEquals(5L, result);
    }

    @Test
    void toSeqFromSeqRoundTrip() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let original = list::from_seq { xs = [10, 20, 30] }
            let roundTrip = list::from_seq { xs = list::to_seq { xs = original } }
            list::equals { xs = original, ys = roundTrip }
            """);
        assertEquals(true, result);
    }

    // ========================================
    // To/From Set Conversions
    // ========================================

    @Test
    void toSetConvertsListToSet() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            use std::collections::set
            let xs = list::from_seq { xs = [1, 2, 2, 3, 3, 3] }
            let s = list::to_set { xs = xs }
            set::size { s = s }
            """);
        assertEquals(3L, result);  // Duplicates removed
    }

    @Test
    void fromSetConvertsSetToList() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            use std::collections::set
            let s = set::from_seq { xs = [3, 1, 2] }
            let xs = list::from_set { xs = s }
            list::size { xs = xs }
            """);
        assertEquals(3L, result);
    }

    @Test
    void toSetRemovesDuplicates() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            use std::collections::set
            let xs = list::from_seq { xs = [1, 1, 1, 1, 1] }
            let s = list::to_set { xs = xs }
            set::size { s = s }
            """);
        assertEquals(1L, result);
    }

    // ========================================
    // To/From Deque Conversions
    // ========================================

    @Test
    void toDequeConvertsListToDeque() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            use std::collections::deque
            let xs = list::from_seq { xs = [1, 2, 3] }
            let d = list::to_deque { xs = xs }
            deque::size { d = d }
            """);
        assertEquals(3L, result);
    }

    @Test
    void fromDequeConvertsDequeToList() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            use std::collections::deque
            let d = deque::from_seq { xs = [1, 2, 3, 4] }
            let xs = list::from_deque { xs = d }
            list::size { xs = xs }
            """);
        assertEquals(4L, result);
    }

    @Test
    void toDequeFromDequeRoundTrip() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let original = list::from_seq { xs = [10, 20, 30] }
            let roundTrip = list::from_deque { xs = list::to_deque { xs = original } }
            list::equals { xs = original, ys = roundTrip }
            """);
        assertEquals(true, result);
    }

    // ========================================
    // Average Operation
    // ========================================

    @Test
    void averageCalculatesCorrectly() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [2.0, 4.0, 6.0, 8.0, 10.0] }
            match list::average { xs = xs } is
                Some { val } -> val
                None -> -1.0
            """);
        assertEquals(6.0, result);
    }

    @Test
    void averageReturnsNoneForEmptyList() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs: (List Float) = list::empty {}
            match list::average { xs = xs } is
                Some { val } -> 1.0
                None -> 0.0
            """);
        assertEquals(0.0, result);
    }

    @Test
    void averageOfSingleElement() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [42.0] }
            match list::average { xs = xs } is
                Some { val } -> val
                None -> -1.0
            """);
        assertEquals(42.0, result);
    }

    // ========================================
    // Variance Operation
    // ========================================

    @Test
    void varianceCalculatesCorrectly() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0] }
            match list::variance { xs = xs } is
                Some { val } -> val
                None -> -1.0
            """);
        assertEquals(4.0, result);
    }

    @Test
    void varianceReturnsNoneForEmptyList() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs: (List Float) = list::empty {}
            match list::variance { xs = xs } is
                Some { val } -> 1.0
                None -> 0.0
            """);
        assertEquals(0.0, result);
    }

    @Test
    void varianceOfIdenticalElements() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [5.0, 5.0, 5.0, 5.0] }
            match list::variance { xs = xs } is
                Some { val } -> val
                None -> -1.0
            """);
        assertEquals(0.0, result);
    }

    // ========================================
    // Standard Deviation Operation
    // ========================================

    @Test
    void stddevCalculatesCorrectly() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0] }
            match list::stddev { xs = xs } is
                Some { val } -> val
                None -> -1.0
            """);
        assertEquals(2.0, result);  // sqrt(4.0) = 2.0
    }

    @Test
    void stddevReturnsNoneForEmptyList() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs: (List Float) = list::empty {}
            match list::stddev { xs = xs } is
                Some { val } -> 1.0
                None -> 0.0
            """);
        assertEquals(0.0, result);
    }

    @Test
    void stddevOfIdenticalElements() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [7.0, 7.0, 7.0, 7.0, 7.0] }
            match list::stddev { xs = xs } is
                Some { val } -> val
                None -> -1.0
            """);
        assertEquals(0.0, result);
    }

    // ========================================
    // Median Operation
    // ========================================

    @Test
    void medianOfOddLengthList() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [1.0, 3.0, 5.0, 7.0, 9.0] }
            match list::median { xs = xs } is
                Some { val } -> val
                None -> -1.0
            """);
        assertEquals(5.0, result);
    }

    @Test
    void medianOfEvenLengthList() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [1.0, 2.0, 3.0, 4.0] }
            match list::median { xs = xs } is
                Some { val } -> val
                None -> -1.0
            """);
        assertEquals(2.5, result);  // (2 + 3) / 2
    }

    @Test
    void medianReturnsNoneForEmptyList() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs: (List Float) = list::empty {}
            match list::median { xs = xs } is
                Some { val } -> 1.0
                None -> 0.0
            """);
        assertEquals(0.0, result);
    }

    // ========================================
    // Compare Operation
    // ========================================

    @Test
    void compareEqualListsReturnsZero() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [1, 2, 3] }
            let ys = list::from_seq { xs = [1, 2, 3] }
            list::compare { xs = xs, ys = ys }
            """);
        assertEquals(0L, result);
    }

    @Test
    void compareSmallerListReturnsNegative() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [1, 2, 3] }
            let ys = list::from_seq { xs = [1, 2, 4] }
            list::compare { xs = xs, ys = ys } < 0
            """);
        assertEquals(true, result);
    }

    @Test
    void compareLargerListReturnsPositive() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [1, 2, 4] }
            let ys = list::from_seq { xs = [1, 2, 3] }
            list::compare { xs = xs, ys = ys } > 0
            """);
        assertEquals(true, result);
    }

    // ========================================
    // Is Sorted Operation
    // ========================================

    @Test
    void isSortedReturnsTrueForSortedList() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [1, 2, 3, 4, 5] }
            list::is_sorted { xs = xs }
            """);
        assertEquals(true, result);
    }

    @Test
    void isSortedReturnsFalseForUnsortedList() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [1, 3, 2, 4, 5] }
            list::is_sorted { xs = xs }
            """);
        assertEquals(false, result);
    }

    @Test
    void isSortedReturnsTrueForEmptyList() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            list::is_sorted { xs = list::empty {} }
            """);
        assertEquals(true, result);
    }

    // ========================================
    // Is Sorted By Operation
    // ========================================

    @Test
    void isSortedByWithCustomComparator() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [5, 4, 3, 2, 1] }
            list::is_sorted_by { xs = xs, cmp = { a, b } -> b - a }
            """);
        assertEquals(true, result);  // Sorted in descending order
    }

    @Test
    void isSortedByReturnsFalseForWrongOrder() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [1, 2, 3, 4, 5] }
            list::is_sorted_by { xs = xs, cmp = { a, b } -> b - a }
            """);
        assertEquals(false, result);  // Not sorted in descending order
    }

    @Test
    void isSortedBySingleElement() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [42] }
            list::is_sorted_by { xs = xs, cmp = { a, b } -> a - b }
            """);
        assertEquals(true, result);
    }

    // ========================================
    // Is Sorted Descending Operation
    // ========================================

    @Test
    void isSortedDescendingReturnsTrueForDescendingList() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [5, 4, 3, 2, 1] }
            list::is_sorted_descending { xs = xs }
            """);
        assertEquals(true, result);
    }

    @Test
    void isSortedDescendingReturnsFalseForAscendingList() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [1, 2, 3, 4, 5] }
            list::is_sorted_descending { xs = xs }
            """);
        assertEquals(false, result);
    }

    @Test
    void isSortedDescendingReturnsTrueForEmptyList() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            list::is_sorted_descending { xs = list::empty {} }
            """);
        assertEquals(true, result);
    }

    // ========================================
    // Binary Search Operation
    // ========================================

    @Test
    void binarySearchFindsElement() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10] }
            match list::binary_search { xs = xs, target = 5 } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(4L, result);
    }

    @Test
    void binarySearchReturnsNoneForMissingElement() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [1, 2, 3, 4, 5] }
            match list::binary_search { xs = xs, target = 99 } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(-1L, result);
    }

    @Test
    void binarySearchFindsFirstElement() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [1, 2, 3, 4, 5] }
            match list::binary_search { xs = xs, target = 1 } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(0L, result);
    }

    // ========================================
    // Lower Bound Operation
    // ========================================

    @Test
    void lowerBoundFindsFirstGreaterOrEqual() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [1, 2, 4, 4, 4, 5, 6] }
            list::lower_bound { xs = xs, target = 4 }
            """);
        assertEquals(2L, result);  // First 4 is at index 2
    }

    @Test
    void lowerBoundForMissingElement() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [1, 2, 4, 5, 6] }
            list::lower_bound { xs = xs, target = 3 }
            """);
        assertEquals(2L, result);  // 3 would go at index 2
    }

    @Test
    void lowerBoundAtEnd() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [1, 2, 3] }
            list::lower_bound { xs = xs, target = 10 }
            """);
        assertEquals(3L, result);  // Past the end
    }

    // ========================================
    // Upper Bound Operation
    // ========================================

    @Test
    void upperBoundFindsFirstGreater() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [1, 2, 4, 4, 4, 5, 6] }
            list::upper_bound { xs = xs, target = 4 }
            """);
        assertEquals(5L, result);  // First element > 4 is at index 5
    }

    @Test
    void upperBoundForMissingElement() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [1, 2, 4, 5, 6] }
            list::upper_bound { xs = xs, target = 3 }
            """);
        assertEquals(2L, result);  // First element > 3 is 4 at index 2
    }

    @Test
    void upperBoundAtEnd() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [1, 2, 3] }
            list::upper_bound { xs = xs, target = 3 }
            """);
        assertEquals(3L, result);  // Past the end
    }

    // ========================================
    // Equal Range Operation
    // ========================================

    @Test
    void equalRangeFindsRangeOfDuplicates() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [1, 2, 4, 4, 4, 5, 6] }
            let range = list::equal_range { xs = xs, target = 4 }
            range.end - range.start
            """);
        assertEquals(3L, result);  // Three 4s
    }

    @Test
    void equalRangeForSingleOccurrence() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [1, 2, 3, 4, 5] }
            let range = list::equal_range { xs = xs, target = 3 }
            range.end - range.start
            """);
        assertEquals(1L, result);  // One 3
    }

    @Test
    void equalRangeForMissingElement() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [1, 2, 4, 5] }
            let range = list::equal_range { xs = xs, target = 3 }
            range.end - range.start
            """);
        assertEquals(0L, result);  // No 3s
    }

    @Test
    void equalRangeLowerBoundIsCorrect() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [1, 2, 4, 4, 4, 5, 6] }
            let range = list::equal_range { xs = xs, target = 4 }
            range.start
            """);
        assertEquals(2L, result);
    }

    @Test
    void equalRangeUpperBoundIsCorrect() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [1, 2, 4, 4, 4, 5, 6] }
            let range = list::equal_range { xs = xs, target = 4 }
            range.end
            """);
        assertEquals(5L, result);
    }

    // ========================================
    // Edge Cases and Integration Tests
    // ========================================

    @Test
    void chainingMultipleOperations() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [5, 3, 1, 4, 2] }
            let sorted = list::sort { xs = xs }
            let sliced = list::slice { xs = sorted, start = 1, end = 4 }
            let rotated = list::rotate_left { xs = sliced, n = 1 }
            match list::head { xs = rotated } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(3L, result);  // sort: [1,2,3,4,5], slice: [2,3,4], rotate: [3,4,2]
    }

    @Test
    void statisticsOnLargeList() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            use std::float
            let ints = list::range { start = 1, end = 101 }
            let xs = list::map { xs = ints, f = { x } -> float::from_int { n = x } }
            match list::average { xs = xs } is
                Some { val } -> val
                None -> -1.0
            """);
        assertEquals(50.5, result);  // Average of 1..100
    }

    @Test
    void binarySearchAfterSort() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [5, 2, 8, 1, 9, 3] }
            let sorted = list::sort { xs = xs }
            match list::binary_search { xs = sorted, target = 5 } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(3L, result);  // sorted: [1,2,3,5,8,9], 5 is at index 3
    }

    @Test
    void windowsWithSumCalculation() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [1, 2, 3, 4, 5] }
            let windows = list::windows { xs = xs, size = 3 }
            let sums = list::map { xs = windows, f = { x } -> list::sum { xs = x } }
            match list::head { xs = sums } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(6L, result);  // First window [1,2,3] sums to 6
    }

    @Test
    void convertToSetAndBack() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::from_seq { xs = [3, 1, 2, 1, 3, 2] }
            let s = list::to_set { xs = xs }
            let back = list::from_set { xs = s }
            list::size { xs = back }
            """);
        assertEquals(3L, result);  // Duplicates removed
    }
}
