package com.mezze.interpreter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exhaustive tests for the std::collections::set module functions.
 *
 * Set is a hash set data structure backed by an immutable hash map.
 *
 * Tests cover:
 * - Basic operations (empty, insert, remove, contains, size, is_empty)
 * - Set operations (union, intersection, difference)
 * - Set queries (is_subset, is_superset, is_disjoint)
 * - Conversion functions (to_seq, from_seq, to_list, from_list)
 * - Higher-order functions (filter, map, fold)
 * - Utility functions (singleton, insert_all, remove_all)
 * - Equality (equals)
 */
class SetIntegrationTest {
    // ========================================
    // Basic Operations
    // ========================================

    @Test
    void emptyCreatesEmptySet() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            set::is_empty { s = set::empty {} }
            """);
        assertEquals(true, result);
    }

    @Test
    void insertAddsElementToSet() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            let s = set::insert { s = set::empty {}, x = 42 }
            set::contains { s = s, x = 42 }
            """);
        assertEquals(true, result);
    }

    @Test
    void insertIncreasesSizeByOne() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            let s = set::insert { s = set::empty {}, x = 42 }
            set::size { s = s }
            """);
        assertEquals(1L, result);
    }

    @Test
    void insertDuplicateDoesNotChangeSize() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            let s1 = set::insert { s = set::empty {}, x = 42 }
            let s2 = set::insert { s = s1, x = 42 }
            set::size { s = s2 }
            """);
        assertEquals(1L, result);
    }

    @Test
    void removeDeletesElementFromSet() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            let s1 = set::insert { s = set::empty {}, x = 42 }
            let s2 = set::remove { s = s1, x = 42 }
            set::contains { s = s2, x = 42 }
            """);
        assertEquals(false, result);
    }

    @Test
    void removeDecreasesSizeByOne() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            let s1 = set::insert { s = set::empty {}, x = 42 }
            let s2 = set::remove { s = s1, x = 42 }
            set::size { s = s2 }
            """);
        assertEquals(0L, result);
    }

    @Test
    void removeNonExistentElementDoesNotChangeSize() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            let s1 = set::insert { s = set::empty {}, x = 42 }
            let s2 = set::remove { s = s1, x = 99 }
            set::size { s = s2 }
            """);
        assertEquals(1L, result);
    }

    @Test
    void containsReturnsFalseForMissingElement() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            let s = set::insert { s = set::empty {}, x = 42 }
            set::contains { s = s, x = 99 }
            """);
        assertEquals(false, result);
    }

    @Test
    void sizeReturnsCorrectCount() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            let s1 = set::insert { s = set::empty {}, x = 1 }
            let s2 = set::insert { s = s1, x = 2 }
            let s3 = set::insert { s = s2, x = 3 }
            set::size { s = s3 }
            """);
        assertEquals(3L, result);
    }

    @Test
    void isEmptyReturnsFalseForNonEmptySet() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            let s = set::insert { s = set::empty {}, x = 1 }
            set::is_empty { s = s }
            """);
        assertEquals(false, result);
    }

    // ========================================
    // Set Operations
    // ========================================

    @Test
    void unionCombinesTwoSets() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            let s1 = set::from_seq { xs = [1, 2, 3] }
            let s2 = set::from_seq { xs = [3, 4, 5] }
            let u = set::union { s1 = s1, s2 = s2 }
            set::size { s = u }
            """);
        assertEquals(5L, result);
    }

    @Test
    void unionContainsElementsFromBothSets() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            let s1 = set::from_seq { xs = [1, 2] }
            let s2 = set::from_seq { xs = [3, 4] }
            let u = set::union { s1 = s1, s2 = s2 }
            set::contains { s = u, x = 1 } && set::contains { s = u, x = 4 }
            """);
        assertEquals(true, result);
    }

    @Test
    void intersectionKeepsOnlyCommonElements() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            let s1 = set::from_seq { xs = [1, 2, 3] }
            let s2 = set::from_seq { xs = [2, 3, 4] }
            let i = set::intersection { s1 = s1, s2 = s2 }
            set::size { s = i }
            """);
        assertEquals(2L, result);
    }

    @Test
    void intersectionContainsOnlyCommonElements() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            let s1 = set::from_seq { xs = [1, 2, 3] }
            let s2 = set::from_seq { xs = [2, 3, 4] }
            let i = set::intersection { s1 = s1, s2 = s2 }
            set::contains { s = i, x = 2 } && set::contains { s = i, x = 3 }
            """);
        assertEquals(true, result);
    }

    @Test
    void intersectionExcludesNonCommonElements() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            let s1 = set::from_seq { xs = [1, 2, 3] }
            let s2 = set::from_seq { xs = [2, 3, 4] }
            let i = set::intersection { s1 = s1, s2 = s2 }
            set::contains { s = i, x = 1 } || set::contains { s = i, x = 4 }
            """);
        assertEquals(false, result);
    }

    @Test
    void differenceRemovesElementsInSecondSet() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            let s1 = set::from_seq { xs = [1, 2, 3, 4] }
            let s2 = set::from_seq { xs = [2, 4] }
            let d = set::difference { s1 = s1, s2 = s2 }
            set::size { s = d }
            """);
        assertEquals(2L, result);
    }

    @Test
    void differenceKeepsOnlyElementsNotInSecondSet() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            let s1 = set::from_seq { xs = [1, 2, 3, 4] }
            let s2 = set::from_seq { xs = [2, 4] }
            let d = set::difference { s1 = s1, s2 = s2 }
            set::contains { s = d, x = 1 } && set::contains { s = d, x = 3 }
            """);
        assertEquals(true, result);
    }

    // ========================================
    // Set Queries
    // ========================================

    @Test
    void isSubsetReturnsTrueForSubset() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            let s1 = set::from_seq { xs = [1, 2] }
            let s2 = set::from_seq { xs = [1, 2, 3, 4] }
            set::is_subset { s1 = s1, s2 = s2 }
            """);
        assertEquals(true, result);
    }

    @Test
    void isSubsetReturnsFalseForNonSubset() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            let s1 = set::from_seq { xs = [1, 2, 5] }
            let s2 = set::from_seq { xs = [1, 2, 3, 4] }
            set::is_subset { s1 = s1, s2 = s2 }
            """);
        assertEquals(false, result);
    }

    @Test
    void isSupersetReturnsTrueForSuperset() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            let s1 = set::from_seq { xs = [1, 2, 3, 4] }
            let s2 = set::from_seq { xs = [1, 2] }
            set::is_superset { s1 = s1, s2 = s2 }
            """);
        assertEquals(true, result);
    }

    @Test
    void isDisjointReturnsTrueForDisjointSets() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            let s1 = set::from_seq { xs = [1, 2] }
            let s2 = set::from_seq { xs = [3, 4] }
            set::is_disjoint { s1 = s1, s2 = s2 }
            """);
        assertEquals(true, result);
    }

    @Test
    void isDisjointReturnsFalseForOverlappingSets() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            let s1 = set::from_seq { xs = [1, 2, 3] }
            let s2 = set::from_seq { xs = [3, 4, 5] }
            set::is_disjoint { s1 = s1, s2 = s2 }
            """);
        assertEquals(false, result);
    }

    // ========================================
    // Conversion Functions
    // ========================================

    @Test
    void toSeqConvertsSetToSeq() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            use std::collections::seq
            let s = set::from_seq { xs = [1, 2, 3] }
            let arr = set::to_seq { s = s }
            seq::length { xs = arr }
            """);
        assertEquals(3L, result);
    }

    @Test
    void fromSeqConvertsSeqToSet() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            let s = set::from_seq { xs = [1, 2, 3, 2, 1] }
            set::size { s = s }
            """);
        assertEquals(3L, result);
    }

    @Test
    void toListConvertsSetToList() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            use std::collections::list
            let s = set::from_seq { xs = [1, 2, 3] }
            let xs = set::to_list { s = s }
            list::length { xs = xs }
            """);
        assertEquals(3L, result);
    }

    @Test
    void fromListConvertsListToSet() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            use std::collections::list
            let xs = list::range { start = 1, end = 4 }
            let s = set::from_list { xs = xs }
            set::size { s = s }
            """);
        assertEquals(3L, result);
    }

    // ========================================
    // Higher-Order Functions
    // ========================================

    @Test
    void filterKeepsMatchingElements() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            let s = set::from_seq { xs = [1, 2, 3, 4, 5, 6] }
            let evens = set::filter { s = s, p = { val } -> val % 2 == 0 }
            set::size { s = evens }
            """);
        assertEquals(3L, result);
    }

    @Test
    void filterKeepsCorrectElements() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            let s = set::from_seq { xs = [1, 2, 3, 4, 5, 6] }
            let evens = set::filter { s = s, p = { val } -> val % 2 == 0 }
            set::contains { s = evens, x = 2 } && set::contains { s = evens, x = 4 }
            """);
        assertEquals(true, result);
    }

    @Test
    void mapTransformsAllElements() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            let s = set::from_seq { xs = [1, 2, 3] }
            let doubled = set::map { s = s, f = { val } -> val * 2 }
            set::contains { s = doubled, x = 2 } && set::contains { s = doubled, x = 4 } && set::contains { s = doubled, x = 6 }
            """);
        assertEquals(true, result);
    }

    @Test
    void mapPreservesSetSemantics() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            let s = set::from_seq { xs = [1, 2, 3, 4] }
            let evens = set::map { s = s, f = { val } -> val % 2 }
            set::size { s = evens }
            """);
        assertEquals(2L, result);
    }

    @Test
    void foldAccumulatesValues() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            let s = set::from_seq { xs = [1, 2, 3, 4] }
            set::fold { s = s, init = 0, f = { acc, x } -> acc + x }
            """);
        assertEquals(10L, result);
    }

    // ========================================
    // Utility Functions
    // ========================================

    @Test
    void singletonCreatesSetWithOneElement() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            let s = set::singleton { x = 42 }
            set::size { s = s }
            """);
        assertEquals(1L, result);
    }

    @Test
    void singletonContainsTheElement() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            let s = set::singleton { x = 42 }
            set::contains { s = s, x = 42 }
            """);
        assertEquals(true, result);
    }

    @Test
    void insertAllAddsMultipleElements() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            let s1 = set::singleton { x = 1 }
            let s2 = set::insert_all { s = s1, xs = [2, 3, 4] }
            set::size { s = s2 }
            """);
        assertEquals(4L, result);
    }

    @Test
    void removeAllRemovesMultipleElements() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            let s1 = set::from_seq { xs = [1, 2, 3, 4, 5] }
            let s2 = set::remove_all { s = s1, xs = [2, 4] }
            set::size { s = s2 }
            """);
        assertEquals(3L, result);
    }

    // ========================================
    // Equality
    // ========================================

    @Test
    void equalsReturnsTrueForEqualSets() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            let s1 = set::from_seq { xs = [1, 2, 3] }
            let s2 = set::from_seq { xs = [3, 2, 1] }
            set::equals { s1 = s1, s2 = s2 }
            """);
        assertEquals(true, result);
    }

    @Test
    void equalsReturnsFalseForDifferentSets() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            let s1 = set::from_seq { xs = [1, 2, 3] }
            let s2 = set::from_seq { xs = [1, 2, 4] }
            set::equals { s1 = s1, s2 = s2 }
            """);
        assertEquals(false, result);
    }

    @Test
    void equalsReturnsFalseForDifferentSizes() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            let s1 = set::from_seq { xs = [1, 2, 3] }
            let s2 = set::from_seq { xs = [1, 2] }
            set::equals { s1 = s1, s2 = s2 }
            """);
        assertEquals(false, result);
    }

    // ========================================
    // Edge Cases
    // ========================================

    @Test
    void operationsOnEmptySet() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            let s = set::empty {}
            set::contains { s = s, x = 1 }
            """);
        assertEquals(false, result);
    }

    @Test
    void unionWithEmptySet() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            let s1 = set::from_seq { xs = [1, 2, 3] }
            let s2 = set::empty {}
            let u = set::union { s1 = s1, s2 = s2 }
            set::size { s = u }
            """);
        assertEquals(3L, result);
    }

    @Test
    void intersectionWithEmptySet() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            let s1 = set::from_seq { xs = [1, 2, 3] }
            let s2 = set::empty {}
            let i = set::intersection { s1 = s1, s2 = s2 }
            set::is_empty { s = i }
            """);
        assertEquals(true, result);
    }
}
