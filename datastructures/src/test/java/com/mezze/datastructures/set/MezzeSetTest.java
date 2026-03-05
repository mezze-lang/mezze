package com.mezze.datastructures.set;

import org.junit.jupiter.api.*;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for MezzeSet.
 * Tests all storage strategies and transitions.
 */
public class MezzeSetTest {

    // ===== CONSTRUCTION =====

    @Test
    void testEmpty() {
        MezzeSet<String> set = MezzeSet.empty();
        assertEquals(0, set.size());
        assertTrue(set.isEmpty());
    }

    @Test
    void testOf() {
        MezzeSet<String> set = MezzeSet.of("a");
        assertEquals(1, set.size());
        assertTrue(set.contains("a"));
    }

    @Test
    void testOfMultiple() {
        MezzeSet<String> set = MezzeSet.of("a", "b", "c");
        assertEquals(3, set.size());
        assertTrue(set.contains("a"));
        assertTrue(set.contains("b"));
        assertTrue(set.contains("c"));
    }

    @Test
    void testOfDuplicates() {
        MezzeSet<String> set = MezzeSet.of("a", "b", "a", "c", "b");
        assertEquals(3, set.size()); // Duplicates removed
    }

    @Test
    void testOfLongsUsesSpecialized() {
        MezzeSet<Long> set = MezzeSet.ofLongs(1L, 2L, 3L);
        assertEquals(3, set.size());
        assertTrue(set.contains(1L));
        assertTrue(set.contains(3L));
    }

    // ===== STORAGE TRANSITIONS =====

    @Test
    void testTransitionToSmallFlat() {
        MezzeSet<String> set = MezzeSet.<String>empty()
                .add("a")
                .add("b")
                .add("c");

        assertEquals(3, set.size());
    }

    @Test
    void testTransitionToHash() {
        MezzeSet<String> set = MezzeSet.empty();
        for (int i = 0; i < 25; i++) {
            set = set.add("item" + i);
        }

        assertEquals(25, set.size());
        for (int i = 0; i < 25; i++) {
            assertTrue(set.contains("item" + i));
        }
    }

    // ===== ADD =====

    @Test
    void testAddCreatesNewSet() {
        MezzeSet<String> set1 = MezzeSet.of("a");
        MezzeSet<String> set2 = set1.add("b");

        assertEquals(1, set1.size()); // Original unchanged
        assertEquals(2, set2.size()); // New has both
    }

    @Test
    void testAddExisting() {
        MezzeSet<String> set1 = MezzeSet.of("a", "b");
        MezzeSet<String> set2 = set1.add("a");

        assertEquals(set1, set2); // Semantically equal
        assertEquals(2, set2.size());
    }

    // ===== REMOVE =====

    @Test
    void testRemove() {
        MezzeSet<String> set1 = MezzeSet.of("a", "b", "c");
        MezzeSet<String> set2 = set1.remove("b");

        assertEquals(3, set1.size());
        assertEquals(2, set2.size());
        assertFalse(set2.contains("b"));
    }

    @Test
    void testRemoveMissing() {
        MezzeSet<String> set = MezzeSet.of("a", "b");
        MezzeSet<String> result = set.remove("z");

        assertEquals(set, result); // Semantically equal
        assertEquals(2, result.size());
    }

    @Test
    void testRemoveToEmpty() {
        MezzeSet<String> set = MezzeSet.of("a");
        MezzeSet<String> result = set.remove("a");

        assertTrue(result.isEmpty());
    }

    // ===== SET OPERATIONS =====

    @Test
    void testUnion() {
        MezzeSet<String> set1 = MezzeSet.of("a", "b", "c");
        MezzeSet<String> set2 = MezzeSet.of("b", "c", "d");
        MezzeSet<String> result = set1.union(set2);

        assertEquals(4, result.size());
        assertTrue(result.contains("a"));
        assertTrue(result.contains("b"));
        assertTrue(result.contains("c"));
        assertTrue(result.contains("d"));
    }

    @Test
    void testUnionWithEmpty() {
        MezzeSet<String> set = MezzeSet.of("a", "b");
        MezzeSet<String> empty = MezzeSet.empty();

        assertSame(set, set.union(empty));
        assertSame(set, empty.union(set));
    }

    @Test
    void testIntersect() {
        MezzeSet<String> set1 = MezzeSet.of("a", "b", "c");
        MezzeSet<String> set2 = MezzeSet.of("b", "c", "d");
        MezzeSet<String> result = set1.intersect(set2);

        assertEquals(2, result.size());
        assertTrue(result.contains("b"));
        assertTrue(result.contains("c"));
    }

    @Test
    void testIntersectDisjoint() {
        MezzeSet<String> set1 = MezzeSet.of("a", "b");
        MezzeSet<String> set2 = MezzeSet.of("c", "d");
        MezzeSet<String> result = set1.intersect(set2);

        assertTrue(result.isEmpty());
    }

    @Test
    void testDifference() {
        MezzeSet<String> set1 = MezzeSet.of("a", "b", "c");
        MezzeSet<String> set2 = MezzeSet.of("b", "c", "d");
        MezzeSet<String> result = set1.difference(set2);

        assertEquals(1, result.size());
        assertTrue(result.contains("a"));
    }

    @Test
    void testSymmetricDifference() {
        MezzeSet<String> set1 = MezzeSet.of("a", "b", "c");
        MezzeSet<String> set2 = MezzeSet.of("b", "c", "d");
        MezzeSet<String> result = set1.symmetricDifference(set2);

        assertEquals(2, result.size());
        assertTrue(result.contains("a"));
        assertTrue(result.contains("d"));
    }

    // ===== LONG SET OPERATIONS =====

    @Test
    void testLongUnion() {
        MezzeSet<Long> set1 = MezzeSet.ofLongs(1L, 2L, 3L);
        MezzeSet<Long> set2 = MezzeSet.ofLongs(2L, 3L, 4L);
        MezzeSet<Long> result = set1.union(set2);

        assertEquals(4, result.size());
    }

    @Test
    void testLongIntersect() {
        MezzeSet<Long> set1 = MezzeSet.ofLongs(1L, 2L, 3L);
        MezzeSet<Long> set2 = MezzeSet.ofLongs(2L, 3L, 4L);
        MezzeSet<Long> result = set1.intersect(set2);

        assertEquals(2, result.size());
        assertTrue(result.contains(2L));
        assertTrue(result.contains(3L));
    }

    @Test
    void testLongDifference() {
        MezzeSet<Long> set1 = MezzeSet.ofLongs(1L, 2L, 3L);
        MezzeSet<Long> set2 = MezzeSet.ofLongs(2L, 3L, 4L);
        MezzeSet<Long> result = set1.difference(set2);

        assertEquals(1, result.size());
        assertTrue(result.contains(1L));
    }

    // ===== SUBSET =====

    @Test
    void testIsSubsetOf() {
        MezzeSet<String> set1 = MezzeSet.of("a", "b");
        MezzeSet<String> set2 = MezzeSet.of("a", "b", "c");

        assertTrue(set1.isSubsetOf(set2));
        assertFalse(set2.isSubsetOf(set1));
    }

    @Test
    void testIsSubsetOfEqual() {
        MezzeSet<String> set1 = MezzeSet.of("a", "b");
        MezzeSet<String> set2 = MezzeSet.of("a", "b");

        assertTrue(set1.isSubsetOf(set2));
    }

    @Test
    void testIsDisjoint() {
        MezzeSet<String> set1 = MezzeSet.of("a", "b");
        MezzeSet<String> set2 = MezzeSet.of("c", "d");
        MezzeSet<String> set3 = MezzeSet.of("b", "c");

        assertTrue(set1.isDisjoint(set2));
        assertFalse(set1.isDisjoint(set3));
    }

    // ===== TRANSFORMS =====

    @Test
    void testMap() {
        MezzeSet<Integer> set = MezzeSet.of(1, 2, 3);
        MezzeSet<Integer> result = set.map(x -> x * 10);

        assertEquals(3, result.size());
        assertTrue(result.contains(10));
        assertTrue(result.contains(20));
        assertTrue(result.contains(30));
    }

    @Test
    void testMapCollapse() {
        // Map that produces duplicates
        MezzeSet<Integer> set = MezzeSet.of(1, 2, 3, 4);
        MezzeSet<Integer> result = set.map(x -> x / 2);

        assertEquals(3, result.size()); // 1/2=0, 2/2=1, 3/2=1, 4/2=2 -> {0,1,2}
    }

    @Test
    void testFilter() {
        MezzeSet<Integer> set = MezzeSet.of(1, 2, 3, 4, 5);
        MezzeSet<Integer> result = set.filter(x -> x % 2 == 0);

        assertEquals(2, result.size());
        assertTrue(result.contains(2));
        assertTrue(result.contains(4));
    }

    @Test
    void testFold() {
        MezzeSet<Integer> set = MezzeSet.of(1, 2, 3, 4);
        int sum = set.fold(0, Integer::sum);
        assertEquals(10, sum);
    }

    // ===== ITERATION =====

    @Test
    void testIteration() {
        MezzeSet<String> set = MezzeSet.of("a", "b", "c");
        Set<String> collected = new HashSet<>();
        for (String s : set) {
            collected.add(s);
        }
        assertEquals(Set.of("a", "b", "c"), collected);
    }

    @Test
    void testToArray() {
        MezzeSet<String> set = MezzeSet.of("a", "b", "c");
        Object[] arr = set.toArray();

        assertEquals(3, arr.length);
        Set<Object> arrSet = new HashSet<>(java.util.Arrays.asList(arr));
        assertEquals(Set.of("a", "b", "c"), arrSet);
    }

    // ===== EQUALITY =====

    @Test
    void testEquality() {
        MezzeSet<String> set1 = MezzeSet.of("a", "b", "c");
        MezzeSet<String> set2 = MezzeSet.of("c", "b", "a"); // Different order
        MezzeSet<String> set3 = MezzeSet.of("a", "b");

        assertEquals(set1, set2);
        assertNotEquals(set1, set3);
    }

    @Test
    void testHashCode() {
        MezzeSet<String> set1 = MezzeSet.of("a", "b", "c");
        MezzeSet<String> set2 = MezzeSet.of("c", "b", "a");

        assertEquals(set1.hashCode(), set2.hashCode());
    }

    // ===== EDGE CASES =====

    @Test
    void testEmptySetOperations() {
        MezzeSet<String> empty = MezzeSet.empty();

        assertTrue(empty.isEmpty());
        assertTrue(empty.filter(x -> true).isEmpty());
        assertTrue(empty.remove("x").isEmpty());
    }

    @Test
    void testLargeSet() {
        MezzeSet<Integer> set = MezzeSet.empty();
        for (int i = 0; i < 1000; i++) {
            set = set.add(i);
        }

        assertEquals(1000, set.size());
        for (int i = 0; i < 1000; i++) {
            assertTrue(set.contains(i));
        }
    }

    @Test
    void testHashCollisions() {
        class CollisionElem {
            final int value;
            CollisionElem(int value) { this.value = value; }
            @Override public int hashCode() { return 1; }
            @Override public boolean equals(Object o) {
                return o instanceof CollisionElem && ((CollisionElem) o).value == value;
            }
        }

        MezzeSet<CollisionElem> set = MezzeSet.empty();
        for (int i = 0; i < 50; i++) {
            set = set.add(new CollisionElem(i));
        }

        assertEquals(50, set.size());
        for (int i = 0; i < 50; i++) {
            assertTrue(set.contains(new CollisionElem(i)));
        }
    }

    // ===== ADDITIONAL COVERAGE =====

    @Test
    void testFlatMap() {
        MezzeSet<Integer> set = MezzeSet.of(1, 2, 3);
        MezzeSet<Integer> result = set.flatMap(x -> MezzeSet.of(x, x * 10));
        assertEquals(6, result.size());
        assertTrue(result.contains(1));
        assertTrue(result.contains(10));
        assertTrue(result.contains(2));
        assertTrue(result.contains(20));
    }

    @Test
    void testForEach() {
        MezzeSet<Integer> set = MezzeSet.of(1, 2, 3);
        Set<Integer> collected = new HashSet<>();
        set.forEach(collected::add);
        assertEquals(Set.of(1, 2, 3), collected);
    }

    @Test
    void testFind() {
        MezzeSet<Integer> set = MezzeSet.of(1, 2, 3, 4, 5);
        Integer found = set.find(x -> x > 3);
        assertNotNull(found);
        assertTrue(found > 3);
    }

    @Test
    void testFindNotFound() {
        MezzeSet<Integer> set = MezzeSet.of(1, 2, 3);
        Integer found = set.find(x -> x > 10);
        assertNull(found);
    }

    @Test
    void testExists() {
        MezzeSet<Integer> set = MezzeSet.of(1, 2, 3, 4, 5);
        assertTrue(set.exists(x -> x == 3));
        assertFalse(set.exists(x -> x == 10));
    }

    @Test
    void testForall() {
        MezzeSet<Integer> set = MezzeSet.of(2, 4, 6, 8);
        assertTrue(set.forall(x -> x % 2 == 0));
        assertFalse(set.forall(x -> x < 5));
    }

    @Test
    void testToString() {
        MezzeSet<Integer> set = MezzeSet.of(1, 2, 3);
        String str = set.toString();
        assertTrue(str.contains("1"));
        assertTrue(str.contains("2"));
        assertTrue(str.contains("3"));
    }

    @Test
    void testEqualsWithNull() {
        MezzeSet<Integer> set = MezzeSet.of(1, 2, 3);
        assertNotEquals(set, null);
    }

    @Test
    void testEqualsWithOtherType() {
        MezzeSet<Integer> set = MezzeSet.of(1, 2, 3);
        assertNotEquals(set, "not a set");
    }

    @Test
    void testEqualsDifferentSizes() {
        MezzeSet<Integer> set1 = MezzeSet.of(1, 2, 3);
        MezzeSet<Integer> set2 = MezzeSet.of(1, 2);
        assertNotEquals(set1, set2);
    }

    @Test
    void testAddAllSet() {
        MezzeSet<Integer> set = MezzeSet.of(1, 2, 3);
        MezzeSet<Integer> other = MezzeSet.of(4, 5, 6);
        MezzeSet<Integer> result = set.addAll(other);
        assertEquals(6, result.size());
        assertTrue(result.contains(4));
        assertTrue(result.contains(6));
    }

    @Test
    void testNonEmpty() {
        MezzeSet<Integer> set = MezzeSet.of(1, 2, 3);
        assertTrue(set.nonEmpty());
        assertFalse(MezzeSet.empty().nonEmpty());
    }

    @Test
    void testLongSumMinMax() {
        MezzeSet<Long> set = MezzeSet.ofLongs(1L, 2L, 3L, 4L, 5L);
        assertEquals(15L, set.sumLong());
        assertEquals(1L, set.minLong());
        assertEquals(5L, set.maxLong());
    }

    @Test
    void testToLongArray() {
        MezzeSet<Long> set = MezzeSet.ofLongs(1L, 2L, 3L);
        long[] arr = set.toLongArray();
        assertEquals(3, arr.length);
    }

    @Test
    void testGetStorage() {
        MezzeSet<Integer> set = MezzeSet.of(1, 2, 3);
        assertNotNull(set.getStorage());
    }

    @Test
    void testIsSupersetOf() {
        MezzeSet<Integer> set1 = MezzeSet.of(1, 2, 3, 4, 5);
        MezzeSet<Integer> set2 = MezzeSet.of(2, 3, 4);
        assertTrue(set1.isSupersetOf(set2));
        assertFalse(set2.isSupersetOf(set1));
    }
}
