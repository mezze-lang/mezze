package com.mezze.datastructures.bitset;

import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for MezzeBitSet.
 * Tests packed long[] storage and bitwise operations.
 */
public class MezzeBitSetTest {

    // ===== CONSTRUCTION =====

    @Test
    void testEmpty() {
        MezzeBitSet set = MezzeBitSet.empty();
        assertEquals(0, set.size());
        assertTrue(set.isEmpty());
    }

    @Test
    void testOfSingle() {
        MezzeBitSet set = MezzeBitSet.of(42);
        assertEquals(1, set.size());
        assertTrue(set.contains(42));
    }

    @Test
    void testOfMultiple() {
        MezzeBitSet set = MezzeBitSet.of(1, 5, 10, 100);
        assertEquals(4, set.size());
        assertTrue(set.contains(1));
        assertTrue(set.contains(5));
        assertTrue(set.contains(10));
        assertTrue(set.contains(100));
    }

    @Test
    void testOfDuplicates() {
        MezzeBitSet set = MezzeBitSet.of(1, 2, 1, 3, 2);
        assertEquals(3, set.size());
    }

    @Test
    void testRange() {
        MezzeBitSet set = MezzeBitSet.range(10, 20);
        assertEquals(10, set.size());
        for (int i = 10; i < 20; i++) {
            assertTrue(set.contains(i));
        }
        assertFalse(set.contains(9));
        assertFalse(set.contains(20));
    }

    @Test
    void testNegativeIndexThrows() {
        assertThrows(IllegalArgumentException.class, () -> MezzeBitSet.of(-1));
    }

    // ===== CONTAINS =====

    @Test
    void testContains() {
        MezzeBitSet set = MezzeBitSet.of(0, 63, 64, 127);
        assertTrue(set.contains(0));
        assertTrue(set.contains(63));
        assertTrue(set.contains(64));
        assertTrue(set.contains(127));
        assertFalse(set.contains(1));
        assertFalse(set.contains(62));
        assertFalse(set.contains(128));
    }

    @Test
    void testContainsNegative() {
        MezzeBitSet set = MezzeBitSet.of(0, 1, 2);
        assertFalse(set.contains(-1)); // Should not throw
    }

    @Test
    void testContainsOutOfRange() {
        MezzeBitSet set = MezzeBitSet.of(0, 1, 2);
        assertFalse(set.contains(1000)); // Beyond storage
    }

    // ===== MIN/MAX =====

    @Test
    void testMin() {
        MezzeBitSet set = MezzeBitSet.of(100, 50, 75, 25);
        assertEquals(25, set.min());
    }

    @Test
    void testMax() {
        MezzeBitSet set = MezzeBitSet.of(100, 50, 75, 25);
        assertEquals(100, set.max());
    }

    @Test
    void testMinMaxOnEmpty() {
        MezzeBitSet set = MezzeBitSet.empty();
        assertEquals(-1, set.min());
        assertEquals(-1, set.max());
    }

    @Test
    void testMinMaxAcrossWords() {
        // Test across 64-bit boundaries
        MezzeBitSet set = MezzeBitSet.of(0, 65, 130);
        assertEquals(0, set.min());
        assertEquals(130, set.max());
    }

    // ===== ADD =====

    @Test
    void testAdd() {
        MezzeBitSet set = MezzeBitSet.of(1, 2, 3);
        MezzeBitSet result = set.add(4);

        assertEquals(3, set.size()); // Original unchanged
        assertEquals(4, result.size());
        assertTrue(result.contains(4));
    }

    @Test
    void testAddExisting() {
        MezzeBitSet set = MezzeBitSet.of(1, 2, 3);
        MezzeBitSet result = set.add(2);

        assertSame(set, result); // No change
    }

    @Test
    void testAddGrows() {
        MezzeBitSet set = MezzeBitSet.of(1);
        MezzeBitSet result = set.add(1000);

        assertEquals(2, result.size());
        assertTrue(result.contains(1));
        assertTrue(result.contains(1000));
    }

    // ===== REMOVE =====

    @Test
    void testRemove() {
        MezzeBitSet set = MezzeBitSet.of(1, 2, 3, 4);
        MezzeBitSet result = set.remove(2);

        assertEquals(4, set.size());
        assertEquals(3, result.size());
        assertFalse(result.contains(2));
    }

    @Test
    void testRemoveMissing() {
        MezzeBitSet set = MezzeBitSet.of(1, 2, 3);
        MezzeBitSet result = set.remove(5);

        assertSame(set, result);
    }

    @Test
    void testRemoveNegative() {
        MezzeBitSet set = MezzeBitSet.of(1, 2, 3);
        MezzeBitSet result = set.remove(-1);

        assertSame(set, result);
    }

    // ===== SET OPERATIONS =====

    @Test
    void testUnion() {
        MezzeBitSet set1 = MezzeBitSet.of(1, 2, 3);
        MezzeBitSet set2 = MezzeBitSet.of(2, 3, 4);
        MezzeBitSet result = set1.union(set2);

        assertEquals(4, result.size());
        assertTrue(result.contains(1));
        assertTrue(result.contains(2));
        assertTrue(result.contains(3));
        assertTrue(result.contains(4));
    }

    @Test
    void testUnionWithEmpty() {
        MezzeBitSet set = MezzeBitSet.of(1, 2, 3);
        MezzeBitSet empty = MezzeBitSet.empty();

        assertSame(set, set.union(empty));
        assertSame(set, empty.union(set));
    }

    @Test
    void testIntersect() {
        MezzeBitSet set1 = MezzeBitSet.of(1, 2, 3, 4);
        MezzeBitSet set2 = MezzeBitSet.of(3, 4, 5, 6);
        MezzeBitSet result = set1.intersect(set2);

        assertEquals(2, result.size());
        assertTrue(result.contains(3));
        assertTrue(result.contains(4));
    }

    @Test
    void testIntersectDisjoint() {
        MezzeBitSet set1 = MezzeBitSet.of(1, 2);
        MezzeBitSet set2 = MezzeBitSet.of(3, 4);
        MezzeBitSet result = set1.intersect(set2);

        assertTrue(result.isEmpty());
    }

    @Test
    void testDifference() {
        MezzeBitSet set1 = MezzeBitSet.of(1, 2, 3, 4);
        MezzeBitSet set2 = MezzeBitSet.of(3, 4, 5, 6);
        MezzeBitSet result = set1.difference(set2);

        assertEquals(2, result.size());
        assertTrue(result.contains(1));
        assertTrue(result.contains(2));
    }

    @Test
    void testSymmetricDifference() {
        MezzeBitSet set1 = MezzeBitSet.of(1, 2, 3);
        MezzeBitSet set2 = MezzeBitSet.of(2, 3, 4);
        MezzeBitSet result = set1.symmetricDifference(set2);

        assertEquals(2, result.size());
        assertTrue(result.contains(1));
        assertTrue(result.contains(4));
    }

    @Test
    void testComplement() {
        MezzeBitSet set = MezzeBitSet.of(1, 3, 5, 7);
        MezzeBitSet result = set.complement(10);

        assertEquals(6, result.size()); // 0,2,4,6,8,9
        assertTrue(result.contains(0));
        assertTrue(result.contains(2));
        assertTrue(result.contains(9));
        assertFalse(result.contains(1));
        assertFalse(result.contains(3));
    }

    // ===== SUBSET =====

    @Test
    void testIsSubsetOf() {
        MezzeBitSet set1 = MezzeBitSet.of(1, 2);
        MezzeBitSet set2 = MezzeBitSet.of(1, 2, 3, 4);

        assertTrue(set1.isSubsetOf(set2));
        assertFalse(set2.isSubsetOf(set1));
    }

    @Test
    void testIsSubsetOfEqual() {
        MezzeBitSet set1 = MezzeBitSet.of(1, 2, 3);
        MezzeBitSet set2 = MezzeBitSet.of(1, 2, 3);

        assertTrue(set1.isSubsetOf(set2));
    }

    @Test
    void testIsDisjoint() {
        MezzeBitSet set1 = MezzeBitSet.of(1, 2);
        MezzeBitSet set2 = MezzeBitSet.of(3, 4);
        MezzeBitSet set3 = MezzeBitSet.of(2, 3);

        assertTrue(set1.isDisjoint(set2));
        assertFalse(set1.isDisjoint(set3));
    }

    // ===== ITERATION =====

    @Test
    void testIteration() {
        MezzeBitSet set = MezzeBitSet.of(5, 2, 8, 1);
        List<Integer> collected = new ArrayList<>();
        for (Integer i : set) {
            collected.add(i);
        }
        assertEquals(List.of(1, 2, 5, 8), collected); // In ascending order
    }

    @Test
    void testToArray() {
        MezzeBitSet set = MezzeBitSet.of(5, 2, 8, 1);
        int[] arr = set.toArray();
        assertArrayEquals(new int[]{1, 2, 5, 8}, arr);
    }

    // ===== EQUALITY =====

    @Test
    void testEquality() {
        MezzeBitSet set1 = MezzeBitSet.of(1, 2, 3);
        MezzeBitSet set2 = MezzeBitSet.of(3, 1, 2);
        MezzeBitSet set3 = MezzeBitSet.of(1, 2, 4);

        assertEquals(set1, set2);
        assertNotEquals(set1, set3);
    }

    @Test
    void testHashCode() {
        MezzeBitSet set1 = MezzeBitSet.of(1, 2, 3);
        MezzeBitSet set2 = MezzeBitSet.of(3, 1, 2);

        assertEquals(set1.hashCode(), set2.hashCode());
    }

    // ===== EDGE CASES =====

    @Test
    void testLargeBitSet() {
        // Test with indices across multiple words
        MezzeBitSet set = MezzeBitSet.empty();
        for (int i = 0; i < 1000; i += 7) {
            set = set.add(i);
        }

        int count = 0;
        for (int i = 0; i < 1000; i += 7) {
            assertTrue(set.contains(i));
            count++;
        }
        assertEquals(count, set.size());
    }

    @Test
    void testWordBoundaries() {
        // Test indices at word boundaries (0, 63, 64, 127, 128, etc.)
        MezzeBitSet set = MezzeBitSet.of(0, 63, 64, 127, 128, 191, 192);

        assertEquals(7, set.size());
        assertTrue(set.contains(0));
        assertTrue(set.contains(63));
        assertTrue(set.contains(64));
        assertTrue(set.contains(127));
        assertTrue(set.contains(128));
        assertTrue(set.contains(191));
        assertTrue(set.contains(192));
    }

    @Test
    void testOperationsAcrossWords() {
        MezzeBitSet set1 = MezzeBitSet.range(50, 80);  // Crosses word boundary at 64
        MezzeBitSet set2 = MezzeBitSet.range(60, 90);

        MezzeBitSet union = set1.union(set2);
        assertEquals(40, union.size()); // 50-89

        MezzeBitSet intersect = set1.intersect(set2);
        assertEquals(20, intersect.size()); // 60-79

        MezzeBitSet diff = set1.difference(set2);
        assertEquals(10, diff.size()); // 50-59
    }

    @Test
    void testEmptyOperations() {
        MezzeBitSet empty = MezzeBitSet.empty();

        assertTrue(empty.isEmpty());
        assertEquals(-1, empty.min());
        assertEquals(-1, empty.max());
        assertTrue(empty.isSubsetOf(MezzeBitSet.of(1, 2, 3)));
        assertTrue(empty.isDisjoint(MezzeBitSet.of(1, 2, 3)));
    }
}
