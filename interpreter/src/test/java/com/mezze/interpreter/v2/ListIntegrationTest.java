package com.mezze.interpreter.v2;

import com.mezze.interpreter.v2.values.MezzeVariant;
import com.mezze.language.typechecker.ModuleResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the List (immutable linked list) data structure.
 * Uses V2 unboxed values directly.
 */
class ListIntegrationTest {

    @BeforeEach
    void setUp() {
        ModuleResolver.clearCache();
    }

    // ========================================
    // Variant Pattern Matching Tests
    // ========================================

    @Test
    void patternMatchConsOnNonEmptyList() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::cons { head = 42, tail = list::empty {} }
            match xs is
                Cons { head, tail } -> head
                Nil -> 0
            """);
        assertEquals(42L, result);
    }

    @Test
    void patternMatchNilOnEmptyList() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::empty {}
            match xs is
                Cons { head, tail } -> head
                Nil -> 99
            """);
        assertEquals(99L, result);
    }

    @Test
    void recursivePatternMatchingOnList() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list

            let sum = { xs } ->
                match xs is
                    Nil -> 0
                    Cons { head, tail } -> head + sum { xs = tail }

            let xs = list::range { start = 1, end = 4 }
            sum { xs = xs }
            """);
        assertEquals(6L, result);  // 1 + 2 + 3
    }

    @Test
    void nestedConsNilPatternMatching() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list

            let count = { xs } ->
                match xs is
                    Nil -> 0
                    Cons { head, tail } ->
                        match tail is
                            Nil -> 1
                            Cons { head = _, tail = rest } -> 2 + count { xs = rest }

            let xs = list::range { start = 1, end = 5 }
            count { xs = xs }
            """);
        assertEquals(4L, result);
    }

    // ========================================
    // Construction Tests
    // ========================================

    @Test
    void emptyListIsEmpty() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            list::is_empty { xs = list::empty {} }
            """);
        assertEquals(true, result);
    }

    @Test
    void consCreatesNonEmptyList() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::cons { head = 1, tail = list::empty {} }
            list::is_empty { xs = xs }
            """);
        assertEquals(false, result);
    }

    @Test
    void singletonCreatesSingleElementList() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            list::length { xs = list::singleton { x = 42 } }
            """);
        assertEquals(1L, result);
    }

    @Test
    void rangeCreatesListOfIntegers() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            list::length { xs = list::range { start = 0, end = 5 } }
            """);
        assertEquals(5L, result); // 0, 1, 2, 3, 4
    }

    // ========================================
    // Deconstruction Tests
    // ========================================

    @Test
    void headReturnsFirstElement() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::range { start = 1, end = 4 }
            match list::head { xs = xs } is
                Some { val } -> val
                None -> 0
            """);
        assertEquals(1L, result);
    }

    @Test
    void headOnEmptyReturnsNone() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            match list::head { xs = list::empty {} } is
                Some { val } -> 1
                None -> 0
            """);
        assertEquals(0L, result);
    }

    @Test
    void tailReturnsRemainingList() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::range { start = 1, end = 4 }
            match list::tail { xs = xs } is
                Some { val } -> list::length { xs = val }
                None -> 0
            """);
        assertEquals(2L, result); // tail of [1,2,3] is [2,3]
    }

    @Test
    void unconsReturnsHeadAndTailTogether() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::range { start = 1, end = 4 }
            match list::uncons { xs = xs } is
                Some { val } -> val.head + list::length { xs = val.tail }
                None -> 0
            """);
        assertEquals(3L, result); // head (1) + tail length (2)
    }

    @Test
    void unconsOnEmptyReturnsNone() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            match list::uncons { xs = list::empty {} } is
                Some { val } -> 1
                None -> 0
            """);
        assertEquals(0L, result);
    }

    // ========================================
    // Predicates Tests
    // ========================================

    @Test
    void lengthReturnsCorrectCount() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::range { start = 0, end = 10 }
            list::length { xs = xs }
            """);
        assertEquals(10L, result);
    }

    @Test
    void containsFindsExistingElement() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::range { start = 1, end = 10 }
            list::contains { xs = xs, x = 5 }
            """);
        assertEquals(true, result);
    }

    @Test
    void containsReturnsFalseForMissingElement() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::range { start = 1, end = 5 }
            list::contains { xs = xs, x = 10 }
            """);
        assertEquals(false, result);
    }

    // ========================================
    // Transformation Tests
    // ========================================

    @Test
    void mapTransformsAllElements() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::range { start = 1, end = 4 }
            let doubled = list::map { xs = xs, f = { x } -> x * 2 }
            match list::head { xs = doubled } is
                Some { val } -> val
                None -> 0
            """);
        assertEquals(2L, result); // first elem 1 * 2 = 2
    }

    @Test
    void filterKeepsMatchingElements() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::range { start = 1, end = 10 }
            let evens = list::filter { xs = xs, p = { x } -> x % 2 == 0 }
            list::length { xs = evens }
            """);
        assertEquals(4L, result); // 2, 4, 6, 8
    }

    @Test
    void foldLeftAccumulatesLeftToRight() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::range { start = 1, end = 4 }
            list::fold_left { xs = xs, init = 0, f = { acc, x } -> acc + x }
            """);
        assertEquals(6L, result); // 1 + 2 + 3 = 6
    }

    @Test
    void foldRightAccumulatesRightToLeft() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::range { start = 1, end = 4 }
            list::fold_right { xs = xs, init = 0, f = { acc, x } -> acc * 10 + x }
            """);
        assertEquals(321L, result);
    }

    // ========================================
    // Combination Tests
    // ========================================

    @Test
    void appendJoinsTwoLists() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::range { start = 1, end = 3 }
            let ys = list::range { start = 3, end = 5 }
            list::length { xs = list::append { xs = xs, ys = ys } }
            """);
        assertEquals(4L, result); // [1,2] + [3,4]
    }

    @Test
    void reverseReversesTheList() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::range { start = 1, end = 4 }
            let reversed = list::reverse { xs = xs }
            match list::head { xs = reversed } is
                Some { val } -> val
                None -> 0
            """);
        assertEquals(3L, result); // 3 is now at front
    }

    @Test
    void zipPairsElementsFromTwoLists() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::range { start = 1, end = 4 }
            let ys = list::range { start = 10, end = 13 }
            let zipped = list::zip { xs = xs, ys = ys }
            match list::head { xs = zipped } is
                Some { val } -> val.fst + val.snd
                None -> 0
            """);
        assertEquals(11L, result); // 1 + 10
    }

    // ========================================
    // Conversion Tests
    // ========================================

    @Test
    void toSeqConvertsListToSeq() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            use std::collections::seq
            let xs = list::range { start = 1, end = 4 }
            let arr = list::to_seq { xs = xs }
            seq::length { xs = arr }
            """);
        assertEquals(3L, result);
    }

    @Test
    void fromSeqConvertsSeqToList() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let arr = [1, 2, 3]
            let xs = list::from_seq { xs = arr }
            list::length { xs = xs }
            """);
        assertEquals(3L, result);
    }

    @Test
    void ofSeqCreatesListFromSeqElements() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::of_seq { elements = [10, 20, 30] }
            list::length { xs = xs }
            """);
        assertEquals(3L, result);
    }

    // ========================================
    // Utility Tests
    // ========================================

    @Test
    void repeatCreatesListWithRepeatedElement() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::repeat { x = 42, n = 3 }
            list::length { xs = xs }
            """);
        assertEquals(3L, result);
    }

    @Test
    void anyReturnsTrueIfAnyElementMatches() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::range { start = 1, end = 10 }
            list::any { xs = xs, p = { x } -> x > 5 }
            """);
        assertEquals(true, result);
    }

    @Test
    void allReturnsTrueIfAllElementsMatch() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::range { start = 2, end = 8 }
            let evens = list::filter { xs = xs, p = { x } -> x % 2 == 0 }
            list::all { xs = evens, p = { x } -> x % 2 == 0 }
            """);
        assertEquals(true, result);
    }

    @Test
    void sortOrdersElements() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::reverse { xs = list::range { start = 1, end = 4 } }
            let sorted = list::sort { xs = xs }
            match list::head { xs = sorted } is
                Some { val } -> val
                None -> 0
            """);
        assertEquals(1L, result); // Smallest first
    }

    // ========================================
    // Find Operations
    // ========================================

    @Test
    void findReturnsFirstMatchingElement() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::range { start = 1, end = 10 }
            match list::find { xs = xs, p = { x } -> x > 5 } is
                Some { val } -> val
                None -> 0
            """);
        assertEquals(6L, result);
    }

    @Test
    void findReturnsNoneWhenNoMatch() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::range { start = 1, end = 5 }
            match list::find { xs = xs, p = { x } -> x > 100 } is
                Some { val } -> 1
                None -> 0
            """);
        assertEquals(0L, result);
    }

    @Test
    void findIndexReturnsIndexOfFirstMatch() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::range { start = 1, end = 10 }
            match list::find_index { xs = xs, p = { x } -> x > 5 } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(5L, result);  // index 5 is value 6
    }

    @Test
    void findIndexReturnsNoneWhenNoMatch() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::range { start = 1, end = 5 }
            match list::find_index { xs = xs, p = { x } -> x > 100 } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(-1L, result);
    }

    // ========================================
    // Partition
    // ========================================

    @Test
    void partitionSplitsByPredicateMatching() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::range { start = 1, end = 6 }
            let result = list::partition { xs = xs, p = { x } -> x > 3 }
            list::length { xs = result.pass }
            """);
        assertEquals(2L, result);  // 4, 5
    }

    @Test
    void partitionSplitsByPredicateNonMatching() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::range { start = 1, end = 6 }
            let result = list::partition { xs = xs, p = { x } -> x > 3 }
            list::length { xs = result.fail }
            """);
        assertEquals(3L, result);  // 1, 2, 3
    }

    // ========================================
    // Last and Init
    // ========================================

    @Test
    void lastReturnsLastElement() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::range { start = 1, end = 5 }
            match list::last { xs = xs } is
                Some { val } -> val
                None -> 0
            """);
        assertEquals(4L, result);
    }

    @Test
    void lastReturnsNoneForEmptyList() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            match list::last { xs = list::empty {} } is
                Some { val } -> 1
                None -> 0
            """);
        assertEquals(0L, result);
    }

    @Test
    void initReturnsAllButLastElement() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::range { start = 1, end = 5 }
            let inits = list::init { xs = xs }
            list::length { xs = inits }
            """);
        assertEquals(3L, result);  // [1, 2, 3]
    }

    @Test
    void initOfSingletonIsEmpty() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let xs = list::singleton { x = 42 }
            let inits = list::init { xs = xs }
            list::is_empty { xs = inits }
            """);
        assertEquals(true, result);
    }
}
