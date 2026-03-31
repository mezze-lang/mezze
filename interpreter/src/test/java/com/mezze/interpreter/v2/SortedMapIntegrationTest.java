package com.mezze.interpreter.v2;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SortedMap type and operations.
 * Uses V2 unboxed values directly.
 */
class SortedMapIntegrationTest {
    // ========================================
    // Construction Tests
    // ========================================

    @Test
    void emptySortedMapHasSize0() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_map
            sorted_map::size { m = sorted_map::empty {} }
            """);
        assertEquals(0L, result);
    }

    @Test
    void emptySortedMapIsEmpty() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_map
            sorted_map::is_empty { m = sorted_map::empty {} }
            """);
        assertEquals(true, result);
    }

    // ========================================
    // Insert and Get Tests
    // ========================================

    @Test
    void insertAndGetSingleElement() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_map
            let m = sorted_map::empty {}
            let m2 = sorted_map::insert { m = m, k = "key", v = 42 }
            match sorted_map::get { m = m2, k = "key" } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(42L, result);
    }

    @Test
    void getMissingKeyReturnsNone() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_map
            let m = sorted_map::empty {}
            let m2 = sorted_map::insert { m = m, k = "key", v = 42 }
            match sorted_map::get { m = m2, k = "other" } is
                Some { val } -> 1
                None -> 0
            """);
        assertEquals(0L, result);
    }

    @Test
    void insertMultipleElements() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_map
            let m = sorted_map::empty {}
            let m2 = sorted_map::insert { m = m, k = 1, v = "one" }
            let m3 = sorted_map::insert { m = m2, k = 2, v = "two" }
            let m4 = sorted_map::insert { m = m3, k = 3, v = "three" }
            sorted_map::size { m = m4 }
            """);
        assertEquals(3L, result);
    }

    @Test
    void insertUpdatesExistingKey() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_map
            let m = sorted_map::empty {}
            let m2 = sorted_map::insert { m = m, k = "key", v = 1 }
            let m3 = sorted_map::insert { m = m2, k = "key", v = 2 }
            match sorted_map::get { m = m3, k = "key" } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(2L, result);
    }

    // ========================================
    // Remove Tests
    // ========================================

    @Test
    void removeExistingKey() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_map
            let m = sorted_map::empty {}
            let m2 = sorted_map::insert { m = m, k = "key", v = 42 }
            let m3 = sorted_map::remove { m = m2, k = "key" }
            sorted_map::is_empty { m = m3 }
            """);
        assertEquals(true, result);
    }

    @Test
    void removeMissingKeyIsNoOp() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_map
            let m = sorted_map::empty {}
            let m2 = sorted_map::insert { m = m, k = "key", v = 42 }
            let m3 = sorted_map::remove { m = m2, k = "other" }
            sorted_map::size { m = m3 }
            """);
        assertEquals(1L, result);
    }

    // ========================================
    // Contains Key Tests
    // ========================================

    @Test
    void containsKeyFindsExistingKey() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_map
            let m = sorted_map::empty {}
            let m2 = sorted_map::insert { m = m, k = "hello", v = 1 }
            sorted_map::contains_key { m = m2, k = "hello" }
            """);
        assertEquals(true, result);
    }

    @Test
    void containsKeyReturnsFalseForMissingKey() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_map
            let m = sorted_map::empty {}
            let m2 = sorted_map::insert { m = m, k = "hello", v = 1 }
            sorted_map::contains_key { m = m2, k = "world" }
            """);
        assertEquals(false, result);
    }

    // ========================================
    // Min/Max Tests
    // ========================================

    @Test
    void minKeyReturnsSmallestKey() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_map
            let m = sorted_map::empty {}
            let m2 = sorted_map::insert { m = m, k = 3, v = "c" }
            let m3 = sorted_map::insert { m = m2, k = 1, v = "a" }
            let m4 = sorted_map::insert { m = m3, k = 2, v = "b" }
            match sorted_map::min_key { m = m4 } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(1L, result);
    }

    @Test
    void maxKeyReturnsLargestKey() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_map
            let m = sorted_map::empty {}
            let m2 = sorted_map::insert { m = m, k = 1, v = "a" }
            let m3 = sorted_map::insert { m = m2, k = 3, v = "c" }
            let m4 = sorted_map::insert { m = m3, k = 2, v = "b" }
            match sorted_map::max_key { m = m4 } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(3L, result);
    }

    @Test
    void minKeyOnEmptyMapReturnsNone() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_map
            match sorted_map::min_key { m = sorted_map::empty {} } is
                Some { val } -> 1
                None -> 0
            """);
        assertEquals(0L, result);
    }

    @Test
    void maxKeyOnEmptyMapReturnsNone() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_map
            match sorted_map::max_key { m = sorted_map::empty {} } is
                Some { val } -> 1
                None -> 0
            """);
        assertEquals(0L, result);
    }

    // ========================================
    // Keys/Values/Entries Tests
    // ========================================

    @Test
    void keysReturnsAllKeysInSortedOrder() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_map
            use std::collections::list
            let m = sorted_map::empty {}
            let m2 = sorted_map::insert { m = m, k = 3, v = "c" }
            let m3 = sorted_map::insert { m = m2, k = 1, v = "a" }
            let m4 = sorted_map::insert { m = m3, k = 2, v = "b" }
            let keys = sorted_map::keys { m = m4 }
            list::length { xs = keys }
            """);
        assertEquals(3L, result);
    }

    @Test
    void valuesReturnsAllValues() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_map
            use std::collections::list
            let m = sorted_map::empty {}
            let m2 = sorted_map::insert { m = m, k = 1, v = 10 }
            let m3 = sorted_map::insert { m = m2, k = 2, v = 20 }
            let values = sorted_map::values { m = m3 }
            list::length { xs = values }
            """);
        assertEquals(2L, result);
    }

    @Test
    void entriesReturnsAllKeyValuePairs() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_map
            use std::collections::list
            let m = sorted_map::empty {}
            let m2 = sorted_map::insert { m = m, k = 1, v = "one" }
            let m3 = sorted_map::insert { m = m2, k = 2, v = "two" }
            let entries = sorted_map::entries { m = m3 }
            list::length { xs = entries }
            """);
        assertEquals(2L, result);
    }

    // ========================================
    // Conversion Tests
    // ========================================

    @Test
    void convertFromRegularMapPreservesEntries() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_map
            use std::collections::map
            let m = map::empty {}
            let m2 = map::insert { m = m, k = 1, v = 10 }
            let m3 = map::insert { m = m2, k = 2, v = 20 }
            let sorted = sorted_map::from_map { m = m3 }
            sorted_map::size { m = sorted }
            """);
        assertEquals(2L, result);
    }

    @Test
    void convertToRegularMapPreservesEntries() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_map
            use std::collections::map
            let m = sorted_map::empty {}
            let m2 = sorted_map::insert { m = m, k = 1, v = 10 }
            let m3 = sorted_map::insert { m = m2, k = 2, v = 20 }
            let regular = sorted_map::to_map { m = m3 }
            map::size { m = regular }
            """);
        assertEquals(2L, result);
    }

    // ========================================
    // Immutability Tests
    // ========================================

    @Test
    void insertDoesNotMutateOriginal() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_map
            let m1 = sorted_map::empty {}
            let m2 = sorted_map::insert { m = m1, k = "key", v = 42 }
            sorted_map::is_empty { m = m1 }
            """);
        assertEquals(true, result);
    }

    @Test
    void removeDoesNotMutateOriginal() {
        Object result = MezzeTestHelper.run("""
            use std::collections::sorted_map
            let m1 = sorted_map::empty {}
            let m2 = sorted_map::insert { m = m1, k = "key", v = 42 }
            let m3 = sorted_map::remove { m = m2, k = "key" }
            sorted_map::size { m = m2 }
            """);
        assertEquals(1L, result);
    }
}
