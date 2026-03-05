package com.mezze.datastructures.sorted;

import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for MezzeSortedSet.
 * Tests binary search operations and range queries.
 */
public class MezzeSortedSetTest {

    // ===== CONSTRUCTION =====

    @Test
    void testEmpty() {
        MezzeSortedSet<String> set = MezzeSortedSet.empty();
        assertEquals(0, set.size());
        assertTrue(set.isEmpty());
    }

    @Test
    void testOf() {
        MezzeSortedSet<Integer> set = MezzeSortedSet.of(3, 1, 4, 1, 5, 9, 2, 6);
        assertEquals(7, set.size()); // Duplicates removed
        assertEquals(Integer.valueOf(1), set.min());
        assertEquals(Integer.valueOf(9), set.max());
    }

    @Test
    void testOfPreservesSortOrder() {
        MezzeSortedSet<Integer> set = MezzeSortedSet.of(5, 3, 8, 1, 9, 2);

        List<Integer> elements = new ArrayList<>();
        for (Integer i : set) {
            elements.add(i);
        }
        assertEquals(List.of(1, 2, 3, 5, 8, 9), elements);
    }

    @Test
    void testOfLongs() {
        MezzeSortedSet<Long> set = MezzeSortedSet.ofLongs(5L, 3L, 8L, 1L);
        assertEquals(4, set.size());
        assertEquals(Long.valueOf(1L), set.min());
        assertEquals(Long.valueOf(8L), set.max());
    }

    @Test
    void testWithComparator() {
        MezzeSortedSet<String> set = MezzeSortedSet.<String>empty(Comparator.reverseOrder())
                .add("apple")
                .add("banana")
                .add("cherry");
        assertEquals("cherry", set.min()); // Reverse order
        assertEquals("apple", set.max());
    }

    // ===== ADD =====

    @Test
    void testAdd() {
        MezzeSortedSet<Integer> set = MezzeSortedSet.of(1, 3, 5);
        MezzeSortedSet<Integer> result = set.add(2);

        assertEquals(4, result.size());
        assertEquals(List.of(1, 2, 3, 5), toList(result));
    }

    @Test
    void testAddExisting() {
        MezzeSortedSet<Integer> set = MezzeSortedSet.of(1, 2, 3);
        MezzeSortedSet<Integer> result = set.add(2);

        assertSame(set, result); // No change
    }

    @Test
    void testAddMaintainsOrder() {
        MezzeSortedSet<Integer> set = MezzeSortedSet.of(1, 5, 9);
        set = set.add(3).add(7).add(2);

        assertEquals(List.of(1, 2, 3, 5, 7, 9), toList(set));
    }

    // ===== REMOVE =====

    @Test
    void testRemove() {
        MezzeSortedSet<Integer> set = MezzeSortedSet.of(1, 2, 3, 4, 5);
        MezzeSortedSet<Integer> result = set.remove(3);

        assertEquals(4, result.size());
        assertFalse(result.contains(3));
    }

    @Test
    void testRemoveMissing() {
        MezzeSortedSet<Integer> set = MezzeSortedSet.of(1, 2, 3);
        MezzeSortedSet<Integer> result = set.remove(5);

        assertSame(set, result);
    }

    // ===== NAVIGATION =====

    @Test
    void testMinMax() {
        MezzeSortedSet<Integer> set = MezzeSortedSet.of(5, 3, 8, 1, 9);

        assertEquals(Integer.valueOf(1), set.min());
        assertEquals(Integer.valueOf(9), set.max());
    }

    @Test
    void testMinMaxOnEmpty() {
        MezzeSortedSet<Integer> set = MezzeSortedSet.empty();

        // Empty set throws on min/max
        assertThrows(java.util.NoSuchElementException.class, set::min);
        assertThrows(java.util.NoSuchElementException.class, set::max);
    }

    @Test
    void testFloor() {
        MezzeSortedSet<Integer> set = MezzeSortedSet.of(10, 20, 30, 40, 50);

        assertEquals(Integer.valueOf(30), set.floor(30)); // Exact match
        assertEquals(Integer.valueOf(30), set.floor(35)); // Between
        assertEquals(Integer.valueOf(50), set.floor(100)); // Above all
        assertNull(set.floor(5)); // Below all
    }

    @Test
    void testCeiling() {
        MezzeSortedSet<Integer> set = MezzeSortedSet.of(10, 20, 30, 40, 50);

        assertEquals(Integer.valueOf(30), set.ceiling(30)); // Exact match
        assertEquals(Integer.valueOf(40), set.ceiling(35)); // Between
        assertEquals(Integer.valueOf(10), set.ceiling(5)); // Below all
        assertNull(set.ceiling(100)); // Above all
    }

    @Test
    void testLower() {
        MezzeSortedSet<Integer> set = MezzeSortedSet.of(10, 20, 30, 40, 50);

        assertEquals(Integer.valueOf(20), set.lower(30)); // Strictly less
        assertEquals(Integer.valueOf(30), set.lower(35));
        assertNull(set.lower(10)); // First element
    }

    @Test
    void testHigher() {
        MezzeSortedSet<Integer> set = MezzeSortedSet.of(10, 20, 30, 40, 50);

        assertEquals(Integer.valueOf(40), set.higher(30)); // Strictly greater
        assertEquals(Integer.valueOf(40), set.higher(35));
        assertNull(set.higher(50)); // Last element
    }

    // ===== RANGE =====

    @Test
    void testRange() {
        MezzeSortedSet<Integer> set = MezzeSortedSet.of(10, 20, 30, 40, 50, 60, 70);
        MezzeSortedSet<Integer> range = set.range(25, 55);

        assertEquals(List.of(30, 40, 50), toList(range));
    }

    @Test
    void testRangeInclusive() {
        MezzeSortedSet<Integer> set = MezzeSortedSet.of(10, 20, 30, 40, 50);
        MezzeSortedSet<Integer> range = set.range(20, 40);

        // Range is [from, to) - from inclusive, to exclusive
        assertEquals(List.of(20, 30, 40), toList(range));
    }

    @Test
    void testRangeFrom() {
        MezzeSortedSet<Integer> set = MezzeSortedSet.of(10, 20, 30, 40, 50);
        MezzeSortedSet<Integer> tail = set.rangeFrom(25);

        assertEquals(List.of(30, 40, 50), toList(tail));
    }

    @Test
    void testRangeTo() {
        MezzeSortedSet<Integer> set = MezzeSortedSet.of(10, 20, 30, 40, 50);
        MezzeSortedSet<Integer> head = set.rangeTo(35);

        assertEquals(List.of(10, 20, 30), toList(head));
    }

    // ===== SET OPERATIONS =====

    @Test
    void testUnion() {
        MezzeSortedSet<Integer> set1 = MezzeSortedSet.of(1, 3, 5);
        MezzeSortedSet<Integer> set2 = MezzeSortedSet.of(2, 3, 4);
        MezzeSortedSet<Integer> result = set1.union(set2);

        assertEquals(List.of(1, 2, 3, 4, 5), toList(result));
    }

    @Test
    void testIntersect() {
        MezzeSortedSet<Integer> set1 = MezzeSortedSet.of(1, 2, 3, 4, 5);
        MezzeSortedSet<Integer> set2 = MezzeSortedSet.of(3, 4, 5, 6, 7);
        MezzeSortedSet<Integer> result = set1.intersect(set2);

        assertEquals(List.of(3, 4, 5), toList(result));
    }

    @Test
    void testDifference() {
        MezzeSortedSet<Integer> set1 = MezzeSortedSet.of(1, 2, 3, 4, 5);
        MezzeSortedSet<Integer> set2 = MezzeSortedSet.of(3, 4, 5, 6, 7);
        MezzeSortedSet<Integer> result = set1.difference(set2);

        assertEquals(List.of(1, 2), toList(result));
    }

    // ===== EQUALITY =====

    @Test
    void testEquality() {
        MezzeSortedSet<Integer> set1 = MezzeSortedSet.of(1, 2, 3);
        MezzeSortedSet<Integer> set2 = MezzeSortedSet.of(3, 1, 2); // Different order input
        MezzeSortedSet<Integer> set3 = MezzeSortedSet.of(1, 2, 4);

        assertEquals(set1, set2);
        assertNotEquals(set1, set3);
    }

    @Test
    void testHashCode() {
        MezzeSortedSet<Integer> set1 = MezzeSortedSet.of(1, 2, 3);
        MezzeSortedSet<Integer> set2 = MezzeSortedSet.of(3, 1, 2);

        assertEquals(set1.hashCode(), set2.hashCode());
    }

    // ===== EDGE CASES =====

    @Test
    void testEmptySetOperations() {
        MezzeSortedSet<Integer> empty = MezzeSortedSet.empty();

        assertTrue(empty.isEmpty());
        assertNull(empty.floor(5));
        assertNull(empty.ceiling(5));
        assertTrue(empty.range(0, 100).isEmpty());
    }

    @Test
    void testSingleElement() {
        MezzeSortedSet<Integer> set = MezzeSortedSet.of(42);

        assertEquals(Integer.valueOf(42), set.min());
        assertEquals(Integer.valueOf(42), set.max());
        assertEquals(Integer.valueOf(42), set.floor(42));
        assertEquals(Integer.valueOf(42), set.ceiling(42));
    }

    @Test
    void testLargeSortedSet() {
        MezzeSortedSet<Integer> set = MezzeSortedSet.empty();
        for (int i = 999; i >= 0; i--) { // Add in reverse order
            set = set.add(i);
        }

        assertEquals(1000, set.size());
        assertEquals(Integer.valueOf(0), set.min());
        assertEquals(Integer.valueOf(999), set.max());

        // Verify sorted order
        int prev = -1;
        for (Integer i : set) {
            assertTrue(i > prev);
            prev = i;
        }
    }

    // ===== ADDITIONAL COVERAGE =====

    @Test
    void testToString() {
        MezzeSortedSet<Integer> set = MezzeSortedSet.of(3, 1, 2);
        String str = set.toString();
        assertTrue(str.contains("1"));
        assertTrue(str.contains("2"));
        assertTrue(str.contains("3"));
    }

    @Test
    void testEqualsNull() {
        MezzeSortedSet<Integer> set = MezzeSortedSet.of(1, 2, 3);
        assertNotEquals(set, null);
    }

    @Test
    void testEqualsOtherType() {
        MezzeSortedSet<Integer> set = MezzeSortedSet.of(1, 2, 3);
        assertNotEquals(set, "not a set");
    }

    @Test
    void testEqualsDifferentSizes() {
        MezzeSortedSet<Integer> set1 = MezzeSortedSet.of(1, 2, 3);
        MezzeSortedSet<Integer> set2 = MezzeSortedSet.of(1, 2);
        assertNotEquals(set1, set2);
    }

    @Test
    void testContains() {
        MezzeSortedSet<Integer> set = MezzeSortedSet.of(1, 3, 5, 7, 9);
        assertTrue(set.contains(5));
        assertFalse(set.contains(4));
    }

    @Test
    void testToArray() {
        MezzeSortedSet<Integer> set = MezzeSortedSet.of(3, 1, 2);
        Object[] arr = set.toArray();
        assertEquals(3, arr.length);
        assertEquals(1, arr[0]); // Sorted order
        assertEquals(2, arr[1]);
        assertEquals(3, arr[2]);
    }

    // ===== HELPER =====

    private static <T extends Comparable<T>> List<T> toList(MezzeSortedSet<T> set) {
        List<T> result = new ArrayList<>();
        for (T elem : set) {
            result.add(elem);
        }
        return result;
    }
}
