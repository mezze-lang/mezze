package com.mezze.datastructures.sorted;

import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for MezzeSortedMap.
 * Tests binary search operations and range queries.
 */
public class MezzeSortedMapTest {

    // ===== CONSTRUCTION =====

    @Test
    void testEmpty() {
        MezzeSortedMap<String, Integer> map = MezzeSortedMap.empty();
        assertEquals(0, map.size());
        assertTrue(map.isEmpty());
    }

    @Test
    void testOf() {
        MezzeSortedMap<String, Integer> map = MezzeSortedMap.of("b", 2)
                .put("a", 1)
                .put("c", 3);
        assertEquals(3, map.size());
        assertEquals("a", map.minKey());
        assertEquals("c", map.maxKey());
    }

    @Test
    void testOfPreservesSortOrder() {
        MezzeSortedMap<Integer, String> map = MezzeSortedMap.of(3, "three")
                .put(1, "one")
                .put(4, "four")
                .put(2, "two");

        List<Integer> keys = new ArrayList<>();
        for (var entry : map) {
            keys.add(entry.key());
        }
        assertEquals(List.of(1, 2, 3, 4), keys);
    }

    @Test
    void testWithComparator() {
        MezzeSortedMap<String, Integer> map = MezzeSortedMap.<String, Integer>empty(Comparator.reverseOrder())
                .put("a", 1)
                .put("b", 2)
                .put("c", 3);

        assertEquals("c", map.minKey()); // Reverse order
        assertEquals("a", map.maxKey());
    }

    // ===== GET =====

    @Test
    void testGetExisting() {
        MezzeSortedMap<String, Integer> map = MezzeSortedMap.of("a", 1).put("b", 2);
        assertEquals(1, map.get("a"));
        assertEquals(2, map.get("b"));
    }

    @Test
    void testGetMissing() {
        MezzeSortedMap<String, Integer> map = MezzeSortedMap.of("a", 1);
        assertNull(map.get("z"));
    }

    @Test
    void testGetOrElse() {
        MezzeSortedMap<String, Integer> map = MezzeSortedMap.of("a", 1);
        assertEquals(1, map.getOrElse("a", 99));
        assertEquals(99, map.getOrElse("z", 99));
    }

    // ===== PUT =====

    @Test
    void testPut() {
        MezzeSortedMap<String, Integer> map = MezzeSortedMap.of("a", 1).put("c", 3);
        MezzeSortedMap<String, Integer> result = map.put("b", 2);

        assertEquals(3, result.size());
        assertEquals(2, result.get("b"));
    }

    @Test
    void testPutOverwrite() {
        MezzeSortedMap<String, Integer> map = MezzeSortedMap.of("a", 1);
        MezzeSortedMap<String, Integer> result = map.put("a", 100);

        assertEquals(1, result.size());
        assertEquals(100, result.get("a"));
    }

    @Test
    void testPutMaintainsOrder() {
        MezzeSortedMap<Integer, String> map = MezzeSortedMap.of(1, "a").put(5, "e");
        map = map.put(3, "c").put(2, "b").put(4, "d");

        List<Integer> keys = new ArrayList<>();
        for (var entry : map) {
            keys.add(entry.key());
        }
        assertEquals(List.of(1, 2, 3, 4, 5), keys);
    }

    // ===== REMOVE =====

    @Test
    void testRemove() {
        MezzeSortedMap<String, Integer> map = MezzeSortedMap.of("a", 1).put("b", 2).put("c", 3);
        MezzeSortedMap<String, Integer> result = map.remove("b");

        assertEquals(2, result.size());
        assertFalse(result.containsKey("b"));
    }

    @Test
    void testRemoveMissing() {
        MezzeSortedMap<String, Integer> map = MezzeSortedMap.of("a", 1);
        MezzeSortedMap<String, Integer> result = map.remove("z");

        assertSame(map, result);
    }

    // ===== NAVIGATION =====

    @Test
    void testMinMaxKey() {
        MezzeSortedMap<Integer, String> map = MezzeSortedMap.of(5, "e")
                .put(3, "c")
                .put(8, "h")
                .put(1, "a");

        assertEquals(Integer.valueOf(1), map.minKey());
        assertEquals(Integer.valueOf(8), map.maxKey());
    }

    @Test
    void testMinMaxValue() {
        MezzeSortedMap<Integer, String> map = MezzeSortedMap.of(5, "e")
                .put(3, "c")
                .put(8, "h")
                .put(1, "a");

        assertEquals("a", map.minValue()); // Value at min key
        assertEquals("h", map.maxValue()); // Value at max key
    }

    @Test
    void testFloorEntry() {
        MezzeSortedMap<Integer, String> map = MezzeSortedMap.of(10, "a")
                .put(20, "b")
                .put(30, "c")
                .put(40, "d");

        assertEquals(30, map.floorEntry(30).key());
        assertEquals(30, map.floorEntry(35).key());
        assertEquals(40, map.floorEntry(100).key());
        assertNull(map.floorEntry(5));
    }

    @Test
    void testCeilingEntry() {
        MezzeSortedMap<Integer, String> map = MezzeSortedMap.of(10, "a")
                .put(20, "b")
                .put(30, "c")
                .put(40, "d");

        assertEquals(30, map.ceilingEntry(30).key());
        assertEquals(40, map.ceilingEntry(35).key());
        assertEquals(10, map.ceilingEntry(5).key());
        assertNull(map.ceilingEntry(100));
    }

    // ===== RANGE =====

    @Test
    void testRange() {
        MezzeSortedMap<Integer, String> map = MezzeSortedMap.of(10, "a")
                .put(20, "b")
                .put(30, "c")
                .put(40, "d")
                .put(50, "e");
        MezzeSortedMap<Integer, String> range = map.range(15, 45);

        assertEquals(3, range.size());
        assertTrue(range.containsKey(20));
        assertTrue(range.containsKey(30));
        assertTrue(range.containsKey(40));
    }

    @Test
    void testRangeFrom() {
        MezzeSortedMap<Integer, String> map = MezzeSortedMap.of(10, "a")
                .put(20, "b")
                .put(30, "c")
                .put(40, "d");
        MezzeSortedMap<Integer, String> tail = map.rangeFrom(25);

        assertEquals(2, tail.size());
        assertTrue(tail.containsKey(30));
        assertTrue(tail.containsKey(40));
    }

    @Test
    void testRangeTo() {
        MezzeSortedMap<Integer, String> map = MezzeSortedMap.of(10, "a")
                .put(20, "b")
                .put(30, "c")
                .put(40, "d");
        MezzeSortedMap<Integer, String> head = map.rangeTo(25);

        assertEquals(2, head.size());
        assertTrue(head.containsKey(10));
        assertTrue(head.containsKey(20));
    }

    // ===== ITERATION =====

    @Test
    void testKeys() {
        MezzeSortedMap<String, Integer> map = MezzeSortedMap.of("c", 3)
                .put("a", 1)
                .put("b", 2);
        Object[] keys = map.keys();
        assertEquals(3, keys.length);
        assertEquals("a", keys[0]);
        assertEquals("b", keys[1]);
        assertEquals("c", keys[2]);
    }

    @Test
    void testValues() {
        MezzeSortedMap<Integer, String> map = MezzeSortedMap.of(3, "c")
                .put(1, "a")
                .put(2, "b");
        Object[] values = map.values();
        assertArrayEquals(new String[]{"a", "b", "c"}, values); // In key order
    }

    // ===== EQUALITY =====

    @Test
    void testEquality() {
        MezzeSortedMap<String, Integer> map1 = MezzeSortedMap.of("a", 1).put("b", 2);
        MezzeSortedMap<String, Integer> map2 = MezzeSortedMap.of("b", 2).put("a", 1);
        MezzeSortedMap<String, Integer> map3 = MezzeSortedMap.of("a", 1).put("b", 3);

        assertEquals(map1, map2);
        assertNotEquals(map1, map3);
    }

    @Test
    void testHashCode() {
        MezzeSortedMap<String, Integer> map1 = MezzeSortedMap.of("a", 1).put("b", 2);
        MezzeSortedMap<String, Integer> map2 = MezzeSortedMap.of("b", 2).put("a", 1);

        assertEquals(map1.hashCode(), map2.hashCode());
    }

    // ===== EDGE CASES =====

    @Test
    void testEmptyMapOperations() {
        MezzeSortedMap<Integer, String> empty = MezzeSortedMap.empty();

        assertTrue(empty.isEmpty());
        assertNull(empty.floorEntry(5));
        assertNull(empty.ceilingEntry(5));
        assertTrue(empty.range(0, 100).isEmpty());
    }

    @Test
    void testSingleEntry() {
        MezzeSortedMap<Integer, String> map = MezzeSortedMap.of(42, "answer");

        assertEquals(Integer.valueOf(42), map.minKey());
        assertEquals(Integer.valueOf(42), map.maxKey());
        assertEquals(42, map.floorEntry(42).key());
        assertEquals(42, map.ceilingEntry(42).key());
    }

    @Test
    void testLargeSortedMap() {
        MezzeSortedMap<Integer, Integer> map = MezzeSortedMap.empty();
        for (int i = 999; i >= 0; i--) {
            map = map.put(i, i * 2);
        }

        assertEquals(1000, map.size());
        assertEquals(Integer.valueOf(0), map.minKey());
        assertEquals(Integer.valueOf(999), map.maxKey());

        // Verify sorted order
        int prev = -1;
        for (var entry : map) {
            assertTrue(entry.key() > prev);
            assertEquals(entry.key() * 2, entry.value());
            prev = entry.key();
        }
    }

    // ===== ADDITIONAL COVERAGE =====

    @Test
    void testToString() {
        MezzeSortedMap<String, Integer> map = MezzeSortedMap.of("a", 1).put("b", 2);
        String str = map.toString();
        assertTrue(str.contains("a"));
        assertTrue(str.contains("b"));
    }

    @Test
    void testEqualsNull() {
        MezzeSortedMap<String, Integer> map = MezzeSortedMap.of("a", 1);
        assertNotEquals(map, null);
    }

    @Test
    void testEqualsOtherType() {
        MezzeSortedMap<String, Integer> map = MezzeSortedMap.of("a", 1);
        assertNotEquals(map, "not a map");
    }

    @Test
    void testEqualsDifferentSizes() {
        MezzeSortedMap<String, Integer> map1 = MezzeSortedMap.of("a", 1).put("b", 2);
        MezzeSortedMap<String, Integer> map2 = MezzeSortedMap.of("a", 1);
        assertNotEquals(map1, map2);
    }

    @Test
    void testContainsKey() {
        MezzeSortedMap<String, Integer> map = MezzeSortedMap.of("a", 1).put("c", 3);
        assertTrue(map.containsKey("a"));
        assertTrue(map.containsKey("c"));
        assertFalse(map.containsKey("b"));
    }

    @Test
    void testMinMaxKeyValue() {
        MezzeSortedMap<Integer, String> map = MezzeSortedMap.of(5, "e")
                .put(3, "c")
                .put(8, "h")
                .put(1, "a");

        assertEquals(Integer.valueOf(1), map.minKey());
        assertEquals("a", map.minValue());
        assertEquals(Integer.valueOf(8), map.maxKey());
        assertEquals("h", map.maxValue());
    }

    @Test
    void testIsEmpty() {
        MezzeSortedMap<String, Integer> empty = MezzeSortedMap.empty();
        assertTrue(empty.isEmpty());

        MezzeSortedMap<String, Integer> nonEmpty = MezzeSortedMap.of("a", 1);
        assertFalse(nonEmpty.isEmpty());
    }
}
