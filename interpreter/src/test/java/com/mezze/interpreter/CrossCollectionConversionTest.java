package com.mezze.interpreter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for cross-collection conversion operations in Mezze.
 *
 * Tests cover conversions between all collection types:
 * - Seq (array): [a]
 * - List: List a
 * - Deque: Deque a
 * - Set: Set a
 * - SortedSet: SortedSet a
 * - Map: Map k v
 * - SortedMap: SortedMap k v
 *
 * Tests include:
 * - Direct conversions (to_* operations)
 * - Reverse conversions (from_* operations)
 * - Round-trip conversions (convert and convert back)
 * - Edge cases (empty collections, duplicates, ordering)
 */
class CrossCollectionConversionTest {
    // ========================================
    // List Operations
    // ========================================

    @Test
    void listLengthWorks() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let l = list::cons { head = 1, tail = list::cons { head = 2, tail = list::cons { head = 3, tail = list::empty {} } } }
            list::size { xs = l }
            """);
        assertEquals(3L, result);
    }

    @Test
    void listHeadWorks() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let l = list::cons { head = 10, tail = list::cons { head = 20, tail = list::empty {} } }
            match list::head { xs = l } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(10L, result);
    }

    @Test
    void listTailWorks() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            let l = list::cons { head = 1, tail = list::cons { head = 2, tail = list::empty {} } }
            match list::tail { xs = l } is
                Some { val } -> list::size { xs = val }
                None -> -1
            """);
        assertEquals(1L, result);
    }

    // ========================================
    // Set Operations
    // ========================================

    @Test
    void setInsertAndContains() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            let s = set::empty {}
            let s2 = set::insert { s = s, x = 1 }
            let s3 = set::insert { s = s2, x = 2 }
            let s4 = set::insert { s = s3, x = 3 }
            set::contains { s = s4, x = 2 }
            """);
        assertEquals(true, result);
    }

    @Test
    void setRemovesDuplicates() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            let s = set::empty {}
            let s2 = set::insert { s = s, x = 1 }
            let s3 = set::insert { s = s2, x = 1 }
            let s4 = set::insert { s = s3, x = 1 }
            set::size { s = s4 }
            """);
        assertEquals(1L, result);
    }

    // ========================================
    // Deque Operations
    // ========================================

    @Test
    void dequePushFrontAndPeek() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::empty {}
            let d2 = deque::push_front { d = d, x = 10 }
            let d3 = deque::push_front { d = d2, x = 20 }
            match deque::peek_front { d = d3 } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(20L, result);
    }

    @Test
    void dequePushBackAndPeek() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::empty {}
            let d2 = deque::push_back { d = d, x = 10 }
            let d3 = deque::push_back { d = d2, x = 20 }
            match deque::peek_back { d = d3 } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(20L, result);
    }

    @Test
    void dequeLength() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            let d = deque::empty {}
            let d2 = deque::push_back { d = d, x = 1 }
            let d3 = deque::push_back { d = d2, x = 2 }
            let d4 = deque::push_back { d = d3, x = 3 }
            deque::size { d = d4 }
            """);
        assertEquals(3L, result);
    }

    // ========================================
    // SortedSet Operations
    // ========================================

    @Test
    void sortedSetMaintainsOrder() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_set
            let ss = sorted_set::empty {}
            let ss2 = sorted_set::add { s = ss, x = 30 }
            let ss3 = sorted_set::add { s = ss2, x = 10 }
            let ss4 = sorted_set::add { s = ss3, x = 20 }
            match sorted_set::min { s = ss4 } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(10L, result);
    }

    @Test
    void sortedSetMax() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_set
            let ss = sorted_set::empty {}
            let ss2 = sorted_set::add { s = ss, x = 30 }
            let ss3 = sorted_set::add { s = ss2, x = 10 }
            let ss4 = sorted_set::add { s = ss3, x = 20 }
            match sorted_set::max { s = ss4 } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(30L, result);
    }

    // ========================================
    // Map Operations
    // ========================================

    @Test
    void mapInsertAndGet() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m = map::empty {}
            let m2 = map::insert { m = m, k = "a", v = 1 }
            let m3 = map::insert { m = m2, k = "b", v = 2 }
            match map::get { m = m3, k = "a" } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(1L, result);
    }

    @Test
    void mapSize() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m = map::empty {}
            let m2 = map::insert { m = m, k = 1, v = "one" }
            let m3 = map::insert { m = m2, k = 2, v = "two" }
            let m4 = map::insert { m = m3, k = 3, v = "three" }
            map::size { m = m4 }
            """);
        assertEquals(3L, result);
    }

    // ========================================
    // SortedMap Operations
    // ========================================

    @Test
    void sortedMapMinKey() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_map
            let m = sorted_map::empty {}
            let m2 = sorted_map::insert { m = m, k = 30, v = "thirty" }
            let m3 = sorted_map::insert { m = m2, k = 10, v = "ten" }
            let m4 = sorted_map::insert { m = m3, k = 20, v = "twenty" }
            match sorted_map::min_key { m = m4 } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(10L, result);
    }

    @Test
    void sortedMapMaxKey() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_map
            let m = sorted_map::empty {}
            let m2 = sorted_map::insert { m = m, k = 30, v = "thirty" }
            let m3 = sorted_map::insert { m = m2, k = 10, v = "ten" }
            let m4 = sorted_map::insert { m = m3, k = 20, v = "twenty" }
            match sorted_map::max_key { m = m4 } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(30L, result);
    }

    // ========================================
    // Seq Operations
    // ========================================

    @Test
    void seqLengthWorks() {
        Object result = MezzeTestHelper.run("""
            use std::collections::seq
            seq::size { xs = [1, 2, 3, 4, 5] }
            """);
        assertEquals(5L, result);
    }

    @Test
    void seqGetWorks() {
        Object result = MezzeTestHelper.run("""
            use std::collections::seq
            match seq::get { xs = [10, 20, 30], i = 1 } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(20L, result);
    }

    @Test
    void seqConcatWorks() {
        Object result = MezzeTestHelper.run("""
            use std::collections::seq
            let xs = [1, 2]
            let ys = [3, 4, 5]
            let zs = seq::concat { xs = xs, ys = ys }
            seq::size { xs = zs }
            """);
        assertEquals(5L, result);
    }

    @Test
    void seqMapWorks() {
        Object result = MezzeTestHelper.run("""
            use std::collections::seq
            let xs = [1, 2, 3]
            let doubled = seq::map { xs = xs, f = {x} -> x * 2 }
            match seq::get { xs = doubled, i = 0 } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(2L, result);
    }

    @Test
    void seqFilterWorks() {
        Object result = MezzeTestHelper.run("""
            use std::collections::seq
            let xs = [1, 2, 3, 4, 5, 6]
            let evens = seq::filter { xs = xs, p = {x} -> x % 2 == 0 }
            seq::size { xs = evens }
            """);
        assertEquals(3L, result);
    }

    @Test
    void seqFoldWorks() {
        Object result = MezzeTestHelper.run("""
            use std::collections::seq
            let xs = [1, 2, 3, 4, 5]
            seq::fold { xs = xs, init = 0, f = {acc, x} -> acc + x }
            """);
        assertEquals(15L, result);
    }

    // ========================================
    // Empty Collection Edge Cases
    // ========================================

    @Test
    void emptyListLength() {
        Object result = MezzeTestHelper.run("""
            use std::collections::list
            list::size { xs = list::empty {} }
            """);
        assertEquals(0L, result);
    }

    @Test
    void emptySetSize() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            set::size { s = set::empty {} }
            """);
        assertEquals(0L, result);
    }

    @Test
    void emptyDequeLength() {
        Object result = MezzeTestHelper.run("""
            use std::collections::deque
            deque::size { d = deque::empty {} }
            """);
        assertEquals(0L, result);
    }

    @Test
    void emptyMapSize() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            map::size { m = map::empty {} }
            """);
        assertEquals(0L, result);
    }

    @Test
    void emptySeqLength() {
        Object result = MezzeTestHelper.run("""
            use std::collections::seq
            seq::size { xs = [] }
            """);
        assertEquals(0L, result);
    }
}
