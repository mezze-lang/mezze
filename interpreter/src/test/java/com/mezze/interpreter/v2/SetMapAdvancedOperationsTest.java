package com.mezze.interpreter.v2;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for advanced Set and Map operations in the Mezze language.
 *
 * Set advanced operations tested:
 * - flat_map: map and merge result sets
 * - group_by: group elements by key
 * - symmetric_difference: XOR of sets
 * - filter_map: filter and map in one step
 * - collect_seq: collect Some values to Seq
 * - collect_list: collect Some values to List
 *
 * Map advanced operations tested:
 * - min_key, max_key: find min/max keys
 * - min_value, max_value: find min/max values
 * - invert: swap keys and values
 * - select_keys: keep only specified keys
 * - reject_keys: remove specified keys
 * - reduce: reduce entries
 * - group_by_value: group keys by value
 * - map_keys: transform keys
 * - filter_keys: filter by key predicate
 * - filter_values: filter by value predicate
 * - zip_maps: zip two maps by common keys
 * - diff_maps: keys in m1 not in m2
 * - intersect_maps: common keys
 * - symmetric_diff_maps: XOR of maps
 */
class SetMapAdvancedOperationsTest {
    // ========================================
    // Set: symmetric_difference
    // ========================================

    @Test
    void symmetricDifferenceFindsUniqueElements() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            let s1 = set::from_seq { xs = [1, 2, 3] }
            let s2 = set::from_seq { xs = [2, 3, 4] }
            let diff = set::symmetric_difference { s1 = s1, s2 = s2 }
            set::size { s = diff }
            """);
        assertEquals(2L, result);  // {1, 4}
    }

    @Test
    void symmetricDifferenceContainsCorrectElements() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            let s1 = set::from_seq { xs = [1, 2, 3] }
            let s2 = set::from_seq { xs = [2, 3, 4] }
            let diff = set::symmetric_difference { s1 = s1, s2 = s2 }
            set::contains { s = diff, x = 1 } && set::contains { s = diff, x = 4 }
            """);
        assertEquals(true, result);
    }

    @Test
    void symmetricDifferenceExcludesCommonElements() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            let s1 = set::from_seq { xs = [1, 2, 3] }
            let s2 = set::from_seq { xs = [2, 3, 4] }
            let diff = set::symmetric_difference { s1 = s1, s2 = s2 }
            set::contains { s = diff, x = 2 } || set::contains { s = diff, x = 3 }
            """);
        assertEquals(false, result);
    }

    @Test
    void symmetricDifferenceWithDisjointSetsReturnsUnion() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            let s1 = set::from_seq { xs = [1, 2] }
            let s2 = set::from_seq { xs = [3, 4] }
            let diff = set::symmetric_difference { s1 = s1, s2 = s2 }
            set::size { s = diff }
            """);
        assertEquals(4L, result);
    }

    @Test
    void symmetricDifferenceWithIdenticalSetsIsEmpty() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            let s1 = set::from_seq { xs = [1, 2, 3] }
            let s2 = set::from_seq { xs = [1, 2, 3] }
            let diff = set::symmetric_difference { s1 = s1, s2 = s2 }
            set::is_empty { s = diff }
            """);
        assertEquals(true, result);
    }

    // ========================================
    // Set: flat_map
    // ========================================

    @Test
    void flatMapMapsAndMergesSets() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            let s = set::from_seq { xs = [1, 2] }
            let result = set::flat_map { s = s, f = { val } -> set::from_seq { xs = [val, val * 10] } }
            set::size { s = result }
            """);
        assertEquals(4L, result);  // {1, 10, 2, 20}
    }

    @Test
    void flatMapContainsCorrectElements() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            let s = set::from_seq { xs = [1, 2] }
            let result = set::flat_map { s = s, f = { val } -> set::from_seq { xs = [val, val * 10] } }
            # Check containment
            let has1 = set::contains { s = result, x = 1 }
            let has10 = set::contains { s = result, x = 10 }
            let has2 = set::contains { s = result, x = 2 }
            let has20 = set::contains { s = result, x = 20 }
            has1 && has10 && has2 && has20
            """);
        assertEquals(true, result);
    }

    @Test
    void flatMapHandlesDuplicatesFromMergedSets() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            let s = set::from_seq { xs = [1, 2, 3] }
            let result = set::flat_map { s = s, f = { val } -> set::from_seq { xs = [val % 2] } }
            set::size { s = result }
            """);
        assertEquals(2L, result);  // {0, 1}
    }

    // ========================================
    // Set: group_by
    // ========================================

    @Test
    void groupByCreatesCorrectNumberOfGroups() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            use std::collections::list
            let s = set::from_seq { xs = [1, 2, 3, 4, 5, 6] }
            let groups = set::group_by { s = s, f = { val } -> val % 2 }
            list::length { xs = groups }
            """);
        assertEquals(2L, result);  // odd and even groups
    }

    @Test
    void groupByGroupsElementsCorrectly() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            use std::collections::list
            let s = set::from_seq { xs = [1, 2, 3, 4, 5, 6] }
            let groups = set::group_by { s = s, f = { val } -> val % 3 }
            list::length { xs = groups }
            """);
        assertEquals(3L, result);  // groups for 0, 1, 2
    }

    @Test
    void groupByPreservesAllElements() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            use std::collections::list
            let s = set::from_seq { xs = [1, 2, 3, 4] }
            let groups = set::group_by { s = s, f = { val } -> val % 2 }
            let totalSize = list::fold_left { xs = groups, init = 0, f = { acc, x } -> acc + set::size { s = x.values } }
            totalSize
            """);
        assertEquals(4L, result);
    }

    // ========================================
    // Set: filter_map
    // ========================================

    @Test
    void filterMapFiltersAndTransforms() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            let s = set::from_seq { xs = [1, 2, 3, 4, 5, 6] }
            let result = set::filter_map { set = s, f = { val } ->
                if val % 2 == 0 then Some { val = val * 10 } else None
            }
            set::size { s = result }
            """);
        assertEquals(3L, result);  // {20, 40, 60}
    }

    @Test
    void filterMapContainsTransformedValues() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            let s = set::from_seq { xs = [1, 2, 3, 4] }
            let result = set::filter_map { set = s, f = { val } ->
                if val % 2 == 0 then Some { val = val * 10 } else None
            }
            set::contains { s = result, x = 20 } && set::contains { s = result, x = 40 }
            """);
        assertEquals(true, result);
    }

    @Test
    void filterMapReturnsEmptyWhenAllFilteredOut() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            let s = set::from_seq { xs = [1, 3, 5, 7] }
            let result = set::filter_map { set = s, f = { val } ->
                if val % 2 == 0 then Some { val = val } else None
            }
            set::is_empty { s = result }
            """);
        assertEquals(true, result);
    }

    // ========================================
    // Set: collect_seq
    // ========================================

    @Test
    void collectSeqCollectsSomeValues() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            use std::collections::seq
            let s = set::from_seq { xs = [1, 2, 3, 4] }
            let result = set::collect_seq { set = s, f = { val } ->
                if val > 2 then Some { val = val * 10 } else None
            }
            seq::length { xs = result }
            """);
        assertEquals(2L, result);  // [30, 40]
    }

    @Test
    void collectSeqReturnsCorrectValues() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            use std::collections::seq
            let s = set::from_seq { xs = [1, 2, 3] }
            let result = set::collect_seq { set = s, f = { val } ->
                if val == 2 then Some { val = 100 } else None
            }
            match seq::first { xs = result } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(100L, result);
    }

    @Test
    void collectSeqReturnsEmptySeqWhenAllNone() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            use std::collections::seq
            let s = set::from_seq { xs = [1, 2, 3] }
            let result = set::collect_seq { set = s, f = { val } -> None }
            seq::is_empty { xs = result }
            """);
        assertEquals(true, result);
    }

    // ========================================
    // Set: collect_list
    // ========================================

    @Test
    void collectListCollectsSomeValues() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            use std::collections::list
            let s = set::from_seq { xs = [1, 2, 3, 4, 5] }
            let result = set::collect_list { set = s, f = { val } ->
                if val % 2 == 1 then Some { val = val } else None
            }
            list::length { xs = result }
            """);
        assertEquals(3L, result);  // odd numbers: 1, 3, 5
    }

    @Test
    void collectListReturnsEmptyWhenAllNone() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            use std::collections::list
            let s = set::from_seq { xs = [1, 2, 3] }
            let result = set::collect_list { set = s, f = { val } -> None }
            list::is_empty { xs = result }
            """);
        assertEquals(true, result);
    }

    @Test
    void collectListTransformsValues() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            use std::collections::list
            let s = set::from_seq { xs = [10] }
            let result = set::collect_list { set = s, f = { val } -> Some { val = val * 5 } }
            match list::head { xs = result } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(50L, result);
    }

    // ========================================
    // Map: min_key
    // ========================================

    @Test
    void minKeyFindsMinimumKey() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = 5, v = "five" }
            let m2 = map::insert { m = m1, k = 2, v = "two" }
            let m3 = map::insert { m = m2, k = 8, v = "eight" }
            match map::min_key { m = m3 } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(2L, result);
    }

    @Test
    void minKeyReturnsNoneForEmptyMap() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            match map::min_key { m = map::empty {} } is
                Some { val } -> 1
                None -> 0
            """);
        assertEquals(0L, result);
    }

    @Test
    void minKeyWithSingleElement() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m = map::insert { m = map::empty {}, k = 42, v = "answer" }
            match map::min_key { m = m } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(42L, result);
    }

    // ========================================
    // Map: max_key
    // ========================================

    @Test
    void maxKeyFindsMaximumKey() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = 5, v = "five" }
            let m2 = map::insert { m = m1, k = 2, v = "two" }
            let m3 = map::insert { m = m2, k = 8, v = "eight" }
            match map::max_key { m = m3 } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(8L, result);
    }

    @Test
    void maxKeyReturnsNoneForEmptyMap() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            match map::max_key { m = map::empty {} } is
                Some { val } -> 1
                None -> 0
            """);
        assertEquals(0L, result);
    }

    @Test
    void maxKeyWithSingleElement() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m = map::insert { m = map::empty {}, k = 42, v = "answer" }
            match map::max_key { m = m } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(42L, result);
    }

    // ========================================
    // Map: min_value
    // ========================================

    @Test
    void minValueFindsMinimumValue() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "a", v = 50 }
            let m2 = map::insert { m = m1, k = "b", v = 20 }
            let m3 = map::insert { m = m2, k = "c", v = 80 }
            match map::min_value { m = m3 } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(20L, result);
    }

    @Test
    void minValueReturnsNoneForEmptyMap() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            match map::min_value { m = map::empty {} } is
                Some { val } -> 1
                None -> 0
            """);
        assertEquals(0L, result);
    }

    @Test
    void minValueWithSingleElement() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m = map::insert { m = map::empty {}, k = "key", v = 100 }
            match map::min_value { m = m } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(100L, result);
    }

    // ========================================
    // Map: max_value
    // ========================================

    @Test
    void maxValueFindsMaximumValue() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "a", v = 50 }
            let m2 = map::insert { m = m1, k = "b", v = 20 }
            let m3 = map::insert { m = m2, k = "c", v = 80 }
            match map::max_value { m = m3 } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(80L, result);
    }

    @Test
    void maxValueReturnsNoneForEmptyMap() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            match map::max_value { m = map::empty {} } is
                Some { val } -> 1
                None -> 0
            """);
        assertEquals(0L, result);
    }

    @Test
    void maxValueWithNegativeValues() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "a", v = -50 }
            let m2 = map::insert { m = m1, k = "b", v = -20 }
            let m3 = map::insert { m = m2, k = "c", v = -80 }
            match map::max_value { m = m3 } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(-20L, result);
    }

    // ========================================
    // Map: invert
    // ========================================

    @Test
    void invertSwapsKeysAndValues() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "a", v = 1 }
            let m2 = map::insert { m = m1, k = "b", v = 2 }
            let inverted = map::invert { m = m2 }
            match map::get { m = inverted, k = 1 } is
                Some { val } -> val
                None -> "not_found"
            """);
        assertEquals("a", result);
    }

    @Test
    void invertPreservesSizeWhenValuesUnique() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "a", v = 1 }
            let m2 = map::insert { m = m1, k = "b", v = 2 }
            let m3 = map::insert { m = m2, k = "c", v = 3 }
            let inverted = map::invert { m = m3 }
            map::size { m = inverted }
            """);
        assertEquals(3L, result);
    }

    @Test
    void invertHandlesDuplicateValues() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "a", v = 1 }
            let m2 = map::insert { m = m1, k = "b", v = 1 }
            let m3 = map::insert { m = m2, k = "c", v = 2 }
            let inverted = map::invert { m = m3 }
            map::size { m = inverted }
            """);
        assertEquals(2L, result);  // duplicate values collapse to one key
    }

    // ========================================
    // Map: select_keys
    // ========================================

    @Test
    void selectKeysKeepsOnlySpecifiedKeys() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "a", v = 1 }
            let m2 = map::insert { m = m1, k = "b", v = 2 }
            let m3 = map::insert { m = m2, k = "c", v = 3 }
            let m4 = map::insert { m = m3, k = "d", v = 4 }
            let selected = map::select_keys { m = m4, ks = ["a", "c"] }
            map::size { m = selected }
            """);
        assertEquals(2L, result);
    }

    @Test
    void selectKeysContainsCorrectKeys() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "a", v = 1 }
            let m2 = map::insert { m = m1, k = "b", v = 2 }
            let m3 = map::insert { m = m2, k = "c", v = 3 }
            let selected = map::select_keys { m = m3, ks = ["a", "c"] }
            map::contains_key { m = selected, k = "a" } && map::contains_key { m = selected, k = "c" }
            """);
        assertEquals(true, result);
    }

    @Test
    void selectKeysIgnoresMissingKeys() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m = map::insert { m = map::empty {}, k = "a", v = 1 }
            let selected = map::select_keys { m = m, ks = ["a", "missing", "also_missing"] }
            map::size { m = selected }
            """);
        assertEquals(1L, result);
    }

    // ========================================
    // Map: reject_keys
    // ========================================

    @Test
    void rejectKeysRemovesSpecifiedKeys() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "a", v = 1 }
            let m2 = map::insert { m = m1, k = "b", v = 2 }
            let m3 = map::insert { m = m2, k = "c", v = 3 }
            let m4 = map::insert { m = m3, k = "d", v = 4 }
            let result = map::reject_keys { m = m4, ks = ["b", "d"] }
            map::size { m = result }
            """);
        assertEquals(2L, result);
    }

    @Test
    void rejectKeysKeepsCorrectKeys() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "a", v = 1 }
            let m2 = map::insert { m = m1, k = "b", v = 2 }
            let m3 = map::insert { m = m2, k = "c", v = 3 }
            let result = map::reject_keys { m = m3, ks = ["b"] }
            map::contains_key { m = result, k = "a" } && map::contains_key { m = result, k = "c" }
            """);
        assertEquals(true, result);
    }

    @Test
    void rejectKeysIgnoresMissingKeys() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "a", v = 1 }
            let m2 = map::insert { m = m1, k = "b", v = 2 }
            let result = map::reject_keys { m = m2, ks = ["missing", "also_missing"] }
            map::size { m = result }
            """);
        assertEquals(2L, result);
    }

    // ========================================
    // Map: reduce
    // ========================================

    @Test
    void reduceReducesEntriesToSingleValue() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = 1, v = 10 }
            let m2 = map::insert { m = m1, k = 2, v = 20 }
            let m3 = map::insert { m = m2, k = 3, v = 30 }
            match map::reduce { m = m3, f = { acc, entry } -> { k = acc.k + entry.k, v = acc.v + entry.v } } is
                Some { val } -> val.v
                None -> -1
            """);
        assertEquals(60L, result);  // 10 + 20 + 30
    }

    @Test
    void reduceReturnsNoneForEmptyMap() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            match map::reduce { m = map::empty {}, f = { acc, entry } -> acc } is
                Some { val } -> 1
                None -> 0
            """);
        assertEquals(0L, result);
    }

    @Test
    void reduceWithSingleElement() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m = map::insert { m = map::empty {}, k = 5, v = 50 }
            match map::reduce { m = m, f = { acc, entry } -> { k = acc.k + entry.k, v = acc.v + entry.v } } is
                Some { val } -> val.v
                None -> -1
            """);
        assertEquals(50L, result);
    }

    // ========================================
    // Map: group_by_value
    // ========================================

    @Test
    void groupByValueGroupsKeysByValue() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            use std::collections::list
            let m1 = map::insert { m = map::empty {}, k = "a", v = 1 }
            let m2 = map::insert { m = m1, k = "b", v = 2 }
            let m3 = map::insert { m = m2, k = "c", v = 1 }
            let m4 = map::insert { m = m3, k = "d", v = 2 }
            let grouped = map::group_by_value { m = m4 }
            map::size { m = grouped }
            """);
        assertEquals(2L, result);  // two unique values: 1 and 2
    }

    @Test
    void groupByValueCreatesCorrectGroups() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            use std::collections::list
            let m1 = map::insert { m = map::empty {}, k = "a", v = 1 }
            let m2 = map::insert { m = m1, k = "b", v = 1 }
            let m3 = map::insert { m = m2, k = "c", v = 2 }
            let grouped = map::group_by_value { m = m3 }
            match map::get { m = grouped, k = 1 } is
                Some { val } -> list::length { xs = val }
                None -> -1
            """);
        assertEquals(2L, result);  // "a" and "b" have value 1
    }

    @Test
    void groupByValueWithUniqueValues() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            use std::collections::list
            let m1 = map::insert { m = map::empty {}, k = "a", v = 1 }
            let m2 = map::insert { m = m1, k = "b", v = 2 }
            let m3 = map::insert { m = m2, k = "c", v = 3 }
            let grouped = map::group_by_value { m = m3 }
            map::size { m = grouped }
            """);
        assertEquals(3L, result);  // each value is unique
    }

    // ========================================
    // Map: map_keys
    // ========================================

    @Test
    void mapKeysTransformsKeys() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = 1, v = "one" }
            let m2 = map::insert { m = m1, k = 2, v = "two" }
            let transformed = map::map_keys { m = m2, f = { val } -> val * 10 }
            map::contains_key { m = transformed, k = 10 } && map::contains_key { m = transformed, k = 20 }
            """);
        assertEquals(true, result);
    }

    @Test
    void mapKeysPreservesValues() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = 1, v = 100 }
            let m2 = map::insert { m = m1, k = 2, v = 200 }
            let transformed = map::map_keys { m = m2, f = { val } -> val * 10 }
            match map::get { m = transformed, k = 10 } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(100L, result);
    }

    @Test
    void mapKeysHandlesDuplicateNewKeys() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = 1, v = 10 }
            let m2 = map::insert { m = m1, k = 2, v = 20 }
            let m3 = map::insert { m = m2, k = 3, v = 30 }
            let transformed = map::map_keys { m = m3, f = { val } -> val % 2 }
            map::size { m = transformed }
            """);
        assertEquals(2L, result);  // keys 1,3 -> 1, key 2 -> 0
    }

    // ========================================
    // Map: filter_keys
    // ========================================

    @Test
    void filterKeysFiltersMapByKeyPredicate() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = 1, v = "one" }
            let m2 = map::insert { m = m1, k = 2, v = "two" }
            let m3 = map::insert { m = m2, k = 3, v = "three" }
            let m4 = map::insert { m = m3, k = 4, v = "four" }
            let filtered = map::filter_keys { m = m4, p = { val } -> val % 2 == 0 }
            map::size { m = filtered }
            """);
        assertEquals(2L, result);  // keys 2 and 4
    }

    @Test
    void filterKeysKeepsCorrectEntries() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = 1, v = 10 }
            let m2 = map::insert { m = m1, k = 5, v = 50 }
            let m3 = map::insert { m = m2, k = 10, v = 100 }
            let filtered = map::filter_keys { m = m3, p = { val } -> val > 3 }
            map::contains_key { m = filtered, k = 5 } && map::contains_key { m = filtered, k = 10 }
            """);
        assertEquals(true, result);
    }

    @Test
    void filterKeysReturnsEmptyWhenNoneMatch() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = 1, v = 10 }
            let m2 = map::insert { m = m1, k = 2, v = 20 }
            let filtered = map::filter_keys { m = m2, p = { val } -> val > 100 }
            map::is_empty { m = filtered }
            """);
        assertEquals(true, result);
    }

    // ========================================
    // Map: filter_values
    // ========================================

    @Test
    void filterValuesFiltersMapByValuePredicate() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "a", v = 10 }
            let m2 = map::insert { m = m1, k = "b", v = 25 }
            let m3 = map::insert { m = m2, k = "c", v = 30 }
            let m4 = map::insert { m = m3, k = "d", v = 5 }
            let filtered = map::filter_values { m = m4, p = { val } -> val >= 20 }
            map::size { m = filtered }
            """);
        assertEquals(2L, result);  // values 25 and 30
    }

    @Test
    void filterValuesKeepsCorrectEntries() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "a", v = 100 }
            let m2 = map::insert { m = m1, k = "b", v = 50 }
            let m3 = map::insert { m = m2, k = "c", v = 150 }
            let filtered = map::filter_values { m = m3, p = { val } -> val > 75 }
            map::contains_key { m = filtered, k = "a" } && map::contains_key { m = filtered, k = "c" }
            """);
        assertEquals(true, result);
    }

    @Test
    void filterValuesReturnsEmptyWhenNoneMatch() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "a", v = 10 }
            let m2 = map::insert { m = m1, k = "b", v = 20 }
            let filtered = map::filter_values { m = m2, p = { val } -> val > 100 }
            map::is_empty { m = filtered }
            """);
        assertEquals(true, result);
    }

    // ========================================
    // Map: zip_maps
    // ========================================

    @Test
    void zipMapsZipsByCommonKeys() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1_a = map::insert { m = map::empty {}, k = "a", v = 1 }
            let m1_b = map::insert { m = m1_a, k = "b", v = 2 }
            let m1 = map::insert { m = m1_b, k = "c", v = 3 }
            let m2_a = map::insert { m = map::empty {}, k = "a", v = 10 }
            let m2_b = map::insert { m = m2_a, k = "b", v = 20 }
            let m2 = map::insert { m = m2_b, k = "d", v = 40 }
            let zipped = map::zip_maps { m1 = m1, m2 = m2 }
            map::size { m = zipped }
            """);
        assertEquals(2L, result);  // common keys: "a" and "b"
    }

    @Test
    void zipMapsCreatesCorrectPairs() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "a", v = 1 }
            let m2 = map::insert { m = map::empty {}, k = "a", v = 10 }
            let zipped = map::zip_maps { m1 = m1, m2 = m2 }
            match map::get { m = zipped, k = "a" } is
                Some { val } -> val.fst + val.snd
                None -> -1
            """);
        assertEquals(11L, result);  // 1 + 10
    }

    @Test
    void zipMapsReturnsEmptyWhenNoCommonKeys() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "a", v = 1 }
            let m2 = map::insert { m = map::empty {}, k = "b", v = 2 }
            let zipped = map::zip_maps { m1 = m1, m2 = m2 }
            map::is_empty { m = zipped }
            """);
        assertEquals(true, result);
    }

    // ========================================
    // Map: diff_maps
    // ========================================

    @Test
    void diffMapsFindsKeysInFirstNotInSecond() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1_a = map::insert { m = map::empty {}, k = "a", v = 1 }
            let m1_b = map::insert { m = m1_a, k = "b", v = 2 }
            let m1 = map::insert { m = m1_b, k = "c", v = 3 }
            let m2_b = map::insert { m = map::empty {}, k = "b", v = 20 }
            let m2 = map::insert { m = m2_b, k = "d", v = 40 }
            let diff = map::diff_maps { m1 = m1, m2 = m2 }
            map::size { m = diff }
            """);
        assertEquals(2L, result);  // "a" and "c" are only in m1
    }

    @Test
    void diffMapsContainsCorrectKeys() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1_a = map::insert { m = map::empty {}, k = "a", v = 1 }
            let m1 = map::insert { m = m1_a, k = "b", v = 2 }
            let m2 = map::insert { m = map::empty {}, k = "b", v = 20 }
            let diff = map::diff_maps { m1 = m1, m2 = m2 }
            map::contains_key { m = diff, k = "a" }
            """);
        assertEquals(true, result);
    }

    @Test
    void diffMapsReturnsEmptyWhenAllKeysCommon() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "a", v = 1 }
            let m2 = map::insert { m = map::empty {}, k = "a", v = 2 }
            let diff = map::diff_maps { m1 = m1, m2 = m2 }
            map::is_empty { m = diff }
            """);
        assertEquals(true, result);
    }

    // ========================================
    // Map: intersect_maps
    // ========================================

    @Test
    void intersectMapsFindsCommonKeys() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1_a = map::insert { m = map::empty {}, k = "a", v = 1 }
            let m1_b = map::insert { m = m1_a, k = "b", v = 2 }
            let m1 = map::insert { m = m1_b, k = "c", v = 3 }
            let m2_b = map::insert { m = map::empty {}, k = "b", v = 20 }
            let m2_c = map::insert { m = m2_b, k = "c", v = 30 }
            let m2 = map::insert { m = m2_c, k = "d", v = 40 }
            let intersection = map::intersect_maps { m1 = m1, m2 = m2 }
            map::size { m = intersection }
            """);
        assertEquals(2L, result);  // "b" and "c" are common
    }

    @Test
    void intersectMapsUsesValuesFromFirstMap() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "a", v = 100 }
            let m2 = map::insert { m = map::empty {}, k = "a", v = 999 }
            let intersection = map::intersect_maps { m1 = m1, m2 = m2 }
            match map::get { m = intersection, k = "a" } is
                Some { val } -> val
                None -> -1
            """);
        assertEquals(100L, result);  // value from m1
    }

    @Test
    void intersectMapsReturnsEmptyWhenNoCommonKeys() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "a", v = 1 }
            let m2 = map::insert { m = map::empty {}, k = "b", v = 2 }
            let intersection = map::intersect_maps { m1 = m1, m2 = m2 }
            map::is_empty { m = intersection }
            """);
        assertEquals(true, result);
    }

    // ========================================
    // Map: symmetric_diff_maps
    // ========================================

    @Test
    void symmetricDiffMapsFindsUniqueKeys() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1_a = map::insert { m = map::empty {}, k = "a", v = 1 }
            let m1 = map::insert { m = m1_a, k = "b", v = 2 }
            let m2_b = map::insert { m = map::empty {}, k = "b", v = 20 }
            let m2 = map::insert { m = m2_b, k = "c", v = 30 }
            let symDiff = map::symmetric_diff_maps { m1 = m1, m2 = m2 }
            map::size { m = symDiff }
            """);
        assertEquals(2L, result);  // "a" and "c" are unique
    }

    @Test
    void symmetricDiffMapsContainsCorrectKeys() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1_a = map::insert { m = map::empty {}, k = "a", v = 1 }
            let m1 = map::insert { m = m1_a, k = "b", v = 2 }
            let m2_b = map::insert { m = map::empty {}, k = "b", v = 20 }
            let m2 = map::insert { m = m2_b, k = "c", v = 30 }
            let symDiff = map::symmetric_diff_maps { m1 = m1, m2 = m2 }
            map::contains_key { m = symDiff, k = "a" } && map::contains_key { m = symDiff, k = "c" }
            """);
        assertEquals(true, result);
    }

    @Test
    void symmetricDiffMapsExcludesCommonKeys() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1_a = map::insert { m = map::empty {}, k = "a", v = 1 }
            let m1 = map::insert { m = m1_a, k = "b", v = 2 }
            let m2_b = map::insert { m = map::empty {}, k = "b", v = 20 }
            let m2 = map::insert { m = m2_b, k = "c", v = 30 }
            let symDiff = map::symmetric_diff_maps { m1 = m1, m2 = m2 }
            map::contains_key { m = symDiff, k = "b" }
            """);
        assertEquals(false, result);
    }

    @Test
    void symmetricDiffMapsWithIdenticalMapsIsEmpty() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "a", v = 1 }
            let m2 = map::insert { m = map::empty {}, k = "a", v = 1 }
            let symDiff = map::symmetric_diff_maps { m1 = m1, m2 = m2 }
            map::is_empty { m = symDiff }
            """);
        assertEquals(true, result);
    }

    @Test
    void symmetricDiffMapsWithDisjointMapsIsUnion() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "a", v = 1 }
            let m2 = map::insert { m = map::empty {}, k = "b", v = 2 }
            let symDiff = map::symmetric_diff_maps { m1 = m1, m2 = m2 }
            map::size { m = symDiff }
            """);
        assertEquals(2L, result);
    }

    // ========================================
    // Edge Cases and Combinations
    // ========================================

    @Test
    void setSymmetricDifferenceWithEmptySet() {
        Object result = MezzeTestHelper.run("""
            use std::collections::set
            let s1 = set::from_seq { xs = [1, 2, 3] }
            let s2 = set::empty {}
            let diff = set::symmetric_difference { s1 = s1, s2 = s2 }
            set::size { s = diff }
            """);
        assertEquals(3L, result);
    }

    @Test
    void mapOperationsChained() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            use std::collections::list
            let m1 = map::insert { m = map::empty {}, k = 1, v = 10 }
            let m2 = map::insert { m = m1, k = 2, v = 20 }
            let m3 = map::insert { m = m2, k = 3, v = 30 }
            let m4 = map::insert { m = m3, k = 4, v = 40 }
            let filtered = map::filter_keys { m = m4, p = { val } -> val % 2 == 0 }
            let transformed = map::map_keys { m = filtered, f = { val } -> val * 100 }
            map::size { m = transformed }
            """);
        assertEquals(2L, result);
    }

    @Test
    void filterMapAndFilterValuesEquivalence() {
        Object result = MezzeTestHelper.run("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "a", v = 5 }
            let m2 = map::insert { m = m1, k = "b", v = 15 }
            let m3 = map::insert { m = m2, k = "c", v = 25 }
            let filtered = map::filter_values { m = m3, p = { val } -> val > 10 }
            map::fold { m = filtered, init = 0, f = { acc, k, v } -> acc + v }
            """);
        assertEquals(40L, result);  // 15 + 25
    }
}
