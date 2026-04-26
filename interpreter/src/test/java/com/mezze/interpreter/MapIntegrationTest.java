package com.mezze.interpreter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exhaustive tests for the std::collections::map module functions.
 *
 * Map is a hash map data structure backed by an immutable hash map.
 *
 * Tests cover:
 * - Basic operations (empty, insert, get, remove, contains_key, size, is_empty)
 * - Map queries (keys, values)
 * - Higher-order functions (map_values, filter, fold, from_keys)
 * - Map operations (merge, merge_with)
 * - Lookup with default (get_or_default)
 * - Update operations (update, remove_all)
 * - Equality (equals)
 */
class MapIntegrationTest {
    // ========================================
    // Basic Operations
    // ========================================

    @Test
    void emptyCreatesEmptyMap() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            map::is_empty { m = map::empty {} }
            """);
        assertEquals(true, result);
    }

    @Test
    void insertAddsKeyValuePair() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m = map::insert { m = map::empty {}, k = "key", v = 42 }
            match map::get { m = m, k = "key" } is
                Some { val } -> val
                None -> 0
            """);
        assertEquals(42L, result);
    }

    @Test
    void insertIncreasesSizeByOne() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m = map::insert { m = map::empty {}, k = "key", v = 42 }
            map::size { m = m }
            """);
        assertEquals(1L, result);
    }

    @Test
    void insertDuplicateKeyReplacesValue() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "key", v = 42 }
            let m2 = map::insert { m = m1, k = "key", v = 99 }
            match map::get { m = m2, k = "key" } is
                Some { val } -> val
                None -> 0
            """);
        assertEquals(99L, result);
    }

    @Test
    void insertDuplicateKeyDoesNotChangeSize() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "key", v = 42 }
            let m2 = map::insert { m = m1, k = "key", v = 99 }
            map::size { m = m2 }
            """);
        assertEquals(1L, result);
    }

    @Test
    void getReturnsNoneForMissingKey() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m = map::insert { m = map::empty {}, k = "key", v = 42 }
            match map::get { m = m, k = "missing" } is
                Some { val } -> 1
                None -> 0
            """);
        assertEquals(0L, result);
    }

    @Test
    void removeDeletesKeyValuePair() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "key", v = 42 }
            let m2 = map::remove { m = m1, k = "key" }
            map::contains_key { m = m2, k = "key" }
            """);
        assertEquals(false, result);
    }

    @Test
    void removeDecreasesSizeByOne() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "key", v = 42 }
            let m2 = map::remove { m = m1, k = "key" }
            map::size { m = m2 }
            """);
        assertEquals(0L, result);
    }

    @Test
    void removeNonExistentKeyDoesNotChangeSize() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "key", v = 42 }
            let m2 = map::remove { m = m1, k = "missing" }
            map::size { m = m2 }
            """);
        assertEquals(1L, result);
    }

    @Test
    void containsKeyReturnsTrueForExistingKey() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m = map::insert { m = map::empty {}, k = "key", v = 42 }
            map::contains_key { m = m, k = "key" }
            """);
        assertEquals(true, result);
    }

    @Test
    void containsKeyReturnsFalseForMissingKey() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m = map::insert { m = map::empty {}, k = "key", v = 42 }
            map::contains_key { m = m, k = "missing" }
            """);
        assertEquals(false, result);
    }

    @Test
    void sizeReturnsCorrectCount() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "a", v = 1 }
            let m2 = map::insert { m = m1, k = "b", v = 2 }
            let m3 = map::insert { m = m2, k = "c", v = 3 }
            map::size { m = m3 }
            """);
        assertEquals(3L, result);
    }

    @Test
    void isEmptyReturnsFalseForNonEmptyMap() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m = map::insert { m = map::empty {}, k = "key", v = 42 }
            map::is_empty { m = m }
            """);
        assertEquals(false, result);
    }

    // ========================================
    // Map Queries
    // ========================================

    @Test
    void keysReturnsAllKeys() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            use std::collections::list
            let m1 = map::insert { m = map::empty {}, k = "a", v = 1 }
            let m2 = map::insert { m = m1, k = "b", v = 2 }
            let ks = map::keys { m = m2 }
            list::length { xs = ks }
            """);
        assertEquals(2L, result);
    }

    @Test
    void valuesReturnsAllValues() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            use std::collections::list
            let m1 = map::insert { m = map::empty {}, k = "a", v = 1 }
            let m2 = map::insert { m = m1, k = "b", v = 2 }
            let vs = map::values { m = m2 }
            list::length { xs = vs }
            """);
        assertEquals(2L, result);
    }

    // ========================================
    // Higher-Order Functions
    // ========================================

    @Test
    void mapValuesTransformsAllValues() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "a", v = 1 }
            let m2 = map::insert { m = m1, k = "b", v = 2 }
            let doubled = map::map_values { m = m2, f = { val } -> val * 2 }
            match map::get { m = doubled, k = "a" } is
                Some { val } -> val
                None -> 0
            """);
        assertEquals(2L, result);
    }

    @Test
    void mapValuesPreservesSize() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "a", v = 1 }
            let m2 = map::insert { m = m1, k = "b", v = 2 }
            let doubled = map::map_values { m = m2, f = { val } -> val * 2 }
            map::size { m = doubled }
            """);
        assertEquals(2L, result);
    }

    @Test
    void filterKeepsMatchingEntries() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "a", v = 1 }
            let m2 = map::insert { m = m1, k = "b", v = 2 }
            let m3 = map::insert { m = m2, k = "c", v = 3 }
            let filtered = map::filter { m = m3, p = { k, v } -> v > 1 }
            map::size { m = filtered }
            """);
        assertEquals(2L, result);
    }

    @Test
    void filterKeepsCorrectEntries() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "a", v = 1 }
            let m2 = map::insert { m = m1, k = "b", v = 2 }
            let m3 = map::insert { m = m2, k = "c", v = 3 }
            let filtered = map::filter { m = m3, p = { k, v } -> v > 1 }
            map::contains_key { m = filtered, k = "b" } && map::contains_key { m = filtered, k = "c" }
            """);
        assertEquals(true, result);
    }

    @Test
    void foldAccumulatesValues() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "a", v = 1 }
            let m2 = map::insert { m = m1, k = "b", v = 2 }
            let m3 = map::insert { m = m2, k = "c", v = 3 }
            map::fold { m = m3, init = 0, f = { acc, k, v } -> acc + v }
            """);
        assertEquals(6L, result);
    }

    @Test
    void fromKeysCreatesMapFromKeyList() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            use std::collections::list
            let ks = list::range { start = 1, end = 4 }
            let m = map::from_keys { ks = ks, f = { val } -> val * 10 }
            match map::get { m = m, k = 2 } is
                Some { val } -> val
                None -> 0
            """);
        assertEquals(20L, result);
    }

    // ========================================
    // Map Operations
    // ========================================

    @Test
    void mergeCombinesTwoMaps() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "a", v = 1 }
            let m2 = map::insert { m = map::empty {}, k = "b", v = 2 }
            let merged = map::merge { m1 = m1, m2 = m2 }
            map::size { m = merged }
            """);
        assertEquals(2L, result);
    }

    @Test
    void mergeOverwritesWithSecondMap() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "a", v = 1 }
            let m2 = map::insert { m = map::empty {}, k = "a", v = 99 }
            let merged = map::merge { m1 = m1, m2 = m2 }
            match map::get { m = merged, k = "a" } is
                Some { val } -> val
                None -> 0
            """);
        assertEquals(99L, result);
    }

    @Test
    void mergeWithUsesCustomFunction() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "a", v = 10 }
            let m2 = map::insert { m = map::empty {}, k = "a", v = 5 }
            let merged = map::merge_with { m1 = m1, m2 = m2, f = { l, r } -> l + r }
            match map::get { m = merged, k = "a" } is
                Some { val } -> val
                None -> 0
            """);
        assertEquals(15L, result);
    }

    // ========================================
    // Lookup with Default
    // ========================================

    @Test
    void getOrDefaultReturnsValueWhenKeyExists() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m = map::insert { m = map::empty {}, k = "key", v = 42 }
            map::get_or_default { m = m, k = "key", def = 0 }
            """);
        assertEquals(42L, result);
    }

    @Test
    void getOrDefaultReturnsDefaultWhenKeyMissing() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m = map::insert { m = map::empty {}, k = "key", v = 42 }
            map::get_or_default { m = m, k = "missing", def = 99 }
            """);
        assertEquals(99L, result);
    }

    // ========================================
    // Update Operations
    // ========================================

    @Test
    void updateInsertsNewValue() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m = map::empty {}
            let m2 = map::update { m = m, k = "key", f = { x } ->
                match x is
                    Some { val } -> Some { val = val + 1 }
                    None -> Some { val = 100 }
            }
            match map::get { m = m2, k = "key" } is
                Some { val } -> val
                None -> 0
            """);
        assertEquals(100L, result);
    }

    @Test
    void updateModifiesExistingValue() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "key", v = 42 }
            let m2 = map::update { m = m1, k = "key", f = { x } ->
                match x is
                    Some { val } -> Some { val = val + 1 }
                    None -> Some { val = 100 }
            }
            match map::get { m = m2, k = "key" } is
                Some { val } -> val
                None -> 0
            """);
        assertEquals(43L, result);
    }

    @Test
    void updateCanRemoveKey() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "key", v = 42 }
            let m2 = map::update { m = m1, k = "key", f = { x } -> None }
            map::contains_key { m = m2, k = "key" }
            """);
        assertEquals(false, result);
    }

    @Test
    void removeAllRemovesMultipleKeys() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            use std::collections::list
            let m1 = map::insert { m = map::empty {}, k = "a", v = 1 }
            let m2 = map::insert { m = m1, k = "b", v = 2 }
            let m3 = map::insert { m = m2, k = "c", v = 3 }
            let toRemove = list::of_seq { elements = ["a", "c"] }
            let m4 = map::remove_all { m = m3, ks = toRemove }
            map::size { m = m4 }
            """);
        assertEquals(1L, result);
    }

    // ========================================
    // Equality
    // ========================================

    @Test
    void equalsReturnsTrueForEqualMaps() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "a", v = 1 }
            let m2 = map::insert { m = map::empty {}, k = "a", v = 1 }
            map::equals { m1 = m1, m2 = m2 }
            """);
        assertEquals(true, result);
    }

    @Test
    void equalsReturnsFalseForDifferentValues() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "a", v = 1 }
            let m2 = map::insert { m = map::empty {}, k = "a", v = 2 }
            map::equals { m1 = m1, m2 = m2 }
            """);
        assertEquals(false, result);
    }

    @Test
    void equalsReturnsFalseForDifferentKeys() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "a", v = 1 }
            let m2 = map::insert { m = map::empty {}, k = "b", v = 1 }
            map::equals { m1 = m1, m2 = m2 }
            """);
        assertEquals(false, result);
    }

    @Test
    void equalsReturnsFalseForDifferentSizes() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "a", v = 1 }
            let m2_1 = map::insert { m = map::empty {}, k = "a", v = 1 }
            let m2 = map::insert { m = m2_1, k = "b", v = 2 }
            map::equals { m1 = m1, m2 = m2 }
            """);
        assertEquals(false, result);
    }

    // ========================================
    // Edge Cases
    // ========================================

    @Test
    void operationsOnEmptyMap() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m = map::empty {}
            match map::get { m = m, k = "key" } is
                Some { val } -> 1
                None -> 0
            """);
        assertEquals(0L, result);
    }

    @Test
    void integerKeys() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = 1, v = "one" }
            let m2 = map::insert { m = m1, k = 2, v = "two" }
            map::contains_key { m = m2, k = 1 }
            """);
        assertEquals(true, result);
    }

    @Test
    void multipleInsertOperations() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = 1, v = 10 }
            let m2 = map::insert { m = m1, k = 2, v = 20 }
            let m3 = map::insert { m = m2, k = 3, v = 30 }
            let m4 = map::insert { m = m3, k = 4, v = 40 }
            let m5 = map::insert { m = m4, k = 5, v = 50 }
            map::fold { m = m5, init = 0, f = { acc, k, v } -> acc + v }
            """);
        assertEquals(150L, result);
    }
}
