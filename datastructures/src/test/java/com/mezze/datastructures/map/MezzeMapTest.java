package com.mezze.datastructures.map;

import org.junit.jupiter.api.*;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for MezzeMap.
 * Tests all storage strategies and transitions.
 */
public class MezzeMapTest {

    // ===== CONSTRUCTION =====

    @Test
    void testEmpty() {
        MezzeMap<String, Integer> map = MezzeMap.empty();
        assertEquals(0, map.size());
        assertTrue(map.isEmpty());
    }

    @Test
    void testOf() {
        MezzeMap<String, Integer> map = MezzeMap.of("a", 1);
        assertEquals(1, map.size());
        assertEquals(Integer.valueOf(1), map.get("a"));
    }

    @Test
    void testOfMultiple() {
        MezzeMap<String, Integer> map = MezzeMap.of("a", 1)
                .put("b", 2)
                .put("c", 3);
        assertEquals(3, map.size());
        assertEquals(Integer.valueOf(1), map.get("a"));
        assertEquals(Integer.valueOf(2), map.get("b"));
        assertEquals(Integer.valueOf(3), map.get("c"));
    }

    // ===== STORAGE TRANSITIONS =====

    @Test
    void testTransitionToSmallFlat() {
        // Start empty, add a few entries
        MezzeMap<String, Integer> map = MezzeMap.<String, Integer>empty()
                .put("a", 1)
                .put("b", 2)
                .put("c", 3);

        assertEquals(3, map.size());
        assertEquals(Integer.valueOf(1), map.get("a"));
        assertEquals(Integer.valueOf(3), map.get("c"));
    }

    @Test
    void testTransitionToHash() {
        // Add more than 8 entries to trigger hash storage
        MezzeMap<String, Integer> map = MezzeMap.empty();
        for (int i = 0; i < 20; i++) {
            map = map.put("key" + i, i);
        }

        assertEquals(20, map.size());
        for (int i = 0; i < 20; i++) {
            assertEquals(Integer.valueOf(i), map.get("key" + i));
        }
    }

    @Test
    void testLongKeyStorage() {
        // Use Long keys to trigger specialized storage
        MezzeMap<Long, String> map = MezzeMap.<Long, String>empty()
                .put(1L, "one")
                .put(2L, "two")
                .put(3L, "three");

        assertEquals(3, map.size());
        assertEquals("one", map.get(1L));
        assertEquals("three", map.get(3L));
    }

    // ===== GET =====

    @Test
    void testGetExisting() {
        MezzeMap<String, Integer> map = MezzeMap.of("a", 1).put("b", 2);
        assertEquals(Integer.valueOf(1), map.get("a"));
    }

    @Test
    void testGetMissing() {
        MezzeMap<String, Integer> map = MezzeMap.of("a", 1);
        assertNull(map.get("z"));
    }

    @Test
    void testGetOrElse() {
        MezzeMap<String, Integer> map = MezzeMap.of("a", 1);
        assertEquals(Integer.valueOf(1), map.getOrElse("a", 99));
        assertEquals(Integer.valueOf(99), map.getOrElse("z", 99));
    }

    @Test
    void testContainsKey() {
        MezzeMap<String, Integer> map = MezzeMap.of("a", 1).put("b", 2);
        assertTrue(map.containsKey("a"));
        assertFalse(map.containsKey("z"));
    }

    // ===== PUT =====

    @Test
    void testPutCreatesNewMap() {
        MezzeMap<String, Integer> map1 = MezzeMap.of("a", 1);
        MezzeMap<String, Integer> map2 = map1.put("b", 2);

        assertEquals(1, map1.size()); // Original unchanged
        assertEquals(2, map2.size()); // New has both
    }

    @Test
    void testPutOverwrite() {
        MezzeMap<String, Integer> map1 = MezzeMap.of("a", 1);
        MezzeMap<String, Integer> map2 = map1.put("a", 42);

        assertEquals(Integer.valueOf(1), map1.get("a"));
        assertEquals(Integer.valueOf(42), map2.get("a"));
        assertEquals(1, map2.size());
    }

    // ===== REMOVE =====

    @Test
    void testRemove() {
        MezzeMap<String, Integer> map1 = MezzeMap.of("a", 1).put("b", 2).put("c", 3);
        MezzeMap<String, Integer> map2 = map1.remove("b");

        assertEquals(3, map1.size());
        assertEquals(2, map2.size());
        assertFalse(map2.containsKey("b"));
    }

    @Test
    void testRemoveMissing() {
        MezzeMap<String, Integer> map = MezzeMap.of("a", 1);
        MezzeMap<String, Integer> result = map.remove("z");
        assertEquals(map, result); // Semantically equal
        assertEquals(1, result.size());
    }

    @Test
    void testRemoveToEmpty() {
        MezzeMap<String, Integer> map = MezzeMap.of("a", 1);
        MezzeMap<String, Integer> result = map.remove("a");

        assertTrue(result.isEmpty());
    }

    // ===== MERGE =====

    @Test
    void testPutAll() {
        MezzeMap<String, Integer> map1 = MezzeMap.of("a", 1).put("b", 2);
        MezzeMap<String, Integer> map2 = MezzeMap.of("b", 20).put("c", 3);
        MezzeMap<String, Integer> result = map1.putAll(map2);

        assertEquals(3, result.size());
        assertEquals(Integer.valueOf(1), result.get("a"));
        assertEquals(Integer.valueOf(20), result.get("b")); // Overwritten
        assertEquals(Integer.valueOf(3), result.get("c"));
    }

    @Test
    void testPutAllWithEmpty() {
        MezzeMap<String, Integer> map = MezzeMap.of("a", 1);
        MezzeMap<String, Integer> empty = MezzeMap.empty();

        assertSame(map, map.putAll(empty));
        assertSame(map, empty.putAll(map));
    }

    // ===== TRANSFORMS =====

    @Test
    void testMapValues() {
        MezzeMap<String, Integer> map = MezzeMap.of("a", 1).put("b", 2).put("c", 3);
        MezzeMap<String, Integer> result = map.mapValues(v -> v * 10);

        assertEquals(Integer.valueOf(10), result.get("a"));
        assertEquals(Integer.valueOf(20), result.get("b"));
        assertEquals(Integer.valueOf(30), result.get("c"));
    }

    @Test
    void testFilter() {
        MezzeMap<String, Integer> map = MezzeMap.of("a", 1).put("b", 2).put("c", 3).put("d", 4);
        MezzeMap<String, Integer> result = map.filter((k, v) -> v % 2 == 0);

        assertEquals(2, result.size());
        assertFalse(result.containsKey("a"));
        assertTrue(result.containsKey("b"));
    }

    @Test
    void testFold() {
        MezzeMap<String, Integer> map = MezzeMap.of("a", 1).put("b", 2).put("c", 3);
        int sum = map.fold(0, (acc, k, v) -> acc + v);
        assertEquals(6, sum);
    }

    // ===== KEYS/VALUES =====

    @Test
    void testKeys() {
        MezzeMap<String, Integer> map = MezzeMap.of("a", 1).put("b", 2).put("c", 3);
        Object[] keys = map.keys();
        assertEquals(3, keys.length);
        java.util.Set<Object> keySet = new java.util.HashSet<>(java.util.Arrays.asList(keys));
        assertEquals(java.util.Set.of("a", "b", "c"), keySet);
    }

    @Test
    void testValues() {
        MezzeMap<String, Integer> map = MezzeMap.of("a", 1).put("b", 2).put("c", 3);
        Object[] values = map.values();
        assertEquals(3, values.length);
        java.util.Set<Object> valueSet = new java.util.HashSet<>(java.util.Arrays.asList(values));
        assertEquals(java.util.Set.of(1, 2, 3), valueSet);
    }

    // ===== ITERATION =====

    @Test
    void testIterator() {
        MezzeMap<String, Integer> map = MezzeMap.of("a", 1).put("b", 2);
        Map<String, Integer> collected = new HashMap<>();
        for (var entry : map) {
            collected.put(entry.key(), entry.value());
        }
        assertEquals(Map.of("a", 1, "b", 2), collected);
    }

    // ===== EQUALITY =====

    @Test
    void testEquality() {
        MezzeMap<String, Integer> map1 = MezzeMap.of("a", 1).put("b", 2);
        MezzeMap<String, Integer> map2 = MezzeMap.of("a", 1).put("b", 2);
        MezzeMap<String, Integer> map3 = MezzeMap.of("a", 1).put("b", 3);

        assertEquals(map1, map2);
        assertNotEquals(map1, map3);
    }

    @Test
    void testHashCode() {
        MezzeMap<String, Integer> map1 = MezzeMap.of("a", 1).put("b", 2);
        MezzeMap<String, Integer> map2 = MezzeMap.of("a", 1).put("b", 2);

        assertEquals(map1.hashCode(), map2.hashCode());
    }

    // ===== EDGE CASES =====

    @Test
    void testNullKey() {
        MezzeMap<String, Integer> map = MezzeMap.<String, Integer>empty().put(null, 1);
        assertEquals(Integer.valueOf(1), map.get(null));
    }

    @Test
    void testNullValue() {
        MezzeMap<String, Integer> map = MezzeMap.<String, Integer>empty().put("a", null);
        assertNull(map.get("a"));
    }

    @Test
    void testLargeMap() {
        MezzeMap<Integer, Integer> map = MezzeMap.empty();
        for (int i = 0; i < 1000; i++) {
            map = map.put(i, i * 2);
        }

        assertEquals(1000, map.size());
        for (int i = 0; i < 1000; i++) {
            assertEquals(Integer.valueOf(i * 2), map.get(i));
        }
    }

    @Test
    void testHashCollisions() {
        // Test with objects that have same hashCode
        class CollisionKey {
            final int value;
            CollisionKey(int value) { this.value = value; }
            @Override public int hashCode() { return 1; } // All same hash
            @Override public boolean equals(Object o) {
                return o instanceof CollisionKey && ((CollisionKey) o).value == value;
            }
        }

        MezzeMap<CollisionKey, Integer> map = MezzeMap.empty();
        for (int i = 0; i < 50; i++) {
            map = map.put(new CollisionKey(i), i);
        }

        assertEquals(50, map.size());
        for (int i = 0; i < 50; i++) {
            assertEquals(Integer.valueOf(i), map.get(new CollisionKey(i)));
        }
    }

    // ===== ADDITIONAL COVERAGE =====

    @Test
    void testUpdate() {
        MezzeMap<String, Integer> map = MezzeMap.of("a", 1).put("b", 2);
        MezzeMap<String, Integer> updated = map.update("b", v -> v * 10);
        assertEquals(Integer.valueOf(20), updated.get("b"));
        assertEquals(Integer.valueOf(2), map.get("b")); // Original unchanged
    }

    @Test
    void testFilterKeys() {
        MezzeMap<String, Integer> map = MezzeMap.of("a", 1).put("b", 2).put("c", 3);
        MezzeMap<String, Integer> filtered = map.filterKeys(k -> k.compareTo("b") >= 0);
        assertEquals(2, filtered.size());
        assertFalse(filtered.containsKey("a"));
        assertTrue(filtered.containsKey("b"));
        assertTrue(filtered.containsKey("c"));
    }

    @Test
    void testFilterValues() {
        MezzeMap<String, Integer> map = MezzeMap.of("a", 1).put("b", 2).put("c", 3);
        MezzeMap<String, Integer> filtered = map.filterValues(v -> v > 1);
        assertEquals(2, filtered.size());
        assertFalse(filtered.containsKey("a"));
        assertTrue(filtered.containsKey("b"));
        assertTrue(filtered.containsKey("c"));
    }

    @Test
    void testForEach() {
        MezzeMap<String, Integer> map = MezzeMap.of("a", 1).put("b", 2);
        Map<String, Integer> collected = new HashMap<>();
        map.foreach((k, v) -> collected.put(k, v));
        assertEquals(Map.of("a", 1, "b", 2), collected);
    }

    @Test
    void testToString() {
        MezzeMap<String, Integer> map = MezzeMap.of("a", 1);
        String str = map.toString();
        assertTrue(str.contains("a"));
        assertTrue(str.contains("1"));
    }

    @Test
    void testEqualsWithNull() {
        MezzeMap<String, Integer> map = MezzeMap.of("a", 1);
        assertNotEquals(map, null);
    }

    @Test
    void testEqualsWithOtherType() {
        MezzeMap<String, Integer> map = MezzeMap.of("a", 1);
        assertNotEquals(map, "not a map");
    }

    @Test
    void testEqualsDifferentSizes() {
        MezzeMap<String, Integer> map1 = MezzeMap.of("a", 1).put("b", 2);
        MezzeMap<String, Integer> map2 = MezzeMap.of("a", 1);
        assertNotEquals(map1, map2);
    }

    @Test
    void testMerge() {
        MezzeMap<String, Integer> map1 = MezzeMap.of("a", 1).put("b", 2);
        MezzeMap<String, Integer> map2 = MezzeMap.of("b", 20).put("c", 3);
        MezzeMap<String, Integer> merged = map1.merge(map2, Integer::sum);

        assertEquals(Integer.valueOf(1), merged.get("a"));
        assertEquals(Integer.valueOf(22), merged.get("b")); // 2 + 20
        assertEquals(Integer.valueOf(3), merged.get("c"));
    }

    @Test
    void testMapKeys() {
        MezzeMap<String, Integer> map = MezzeMap.of("a", 1).put("b", 2);
        MezzeMap<String, Integer> mapped = map.mapKeys(k -> k.toUpperCase());
        assertEquals(Integer.valueOf(1), mapped.get("A"));
        assertEquals(Integer.valueOf(2), mapped.get("B"));
    }

    @Test
    void testMap() {
        MezzeMap<String, Integer> map = MezzeMap.of("a", 1).put("b", 2);
        MezzeMap<String, String> mapped = map.map((k, v) -> new MezzeMap.Entry<>(k.toUpperCase(), "v" + v));
        assertEquals("v1", mapped.get("A"));
        assertEquals("v2", mapped.get("B"));
    }

    @Test
    void testEntries() {
        MezzeMap<String, Integer> map = MezzeMap.of("a", 1).put("b", 2);
        MezzeMap.Entry<String, Integer>[] entries = map.entries();
        assertEquals(2, entries.length);
    }

    @Test
    void testNonEmpty() {
        MezzeMap<String, Integer> map = MezzeMap.of("a", 1);
        assertTrue(map.nonEmpty());
        assertFalse(MezzeMap.empty().nonEmpty());
    }

    @Test
    void testGetStorage() {
        MezzeMap<String, Integer> map = MezzeMap.of("a", 1);
        assertNotNull(map.getStorage());
    }

    @Test
    void testIsLongKeyStorage() {
        MezzeMap<Long, String> map = MezzeMap.<Long, String>empty().put(1L, "one");
        // Storage type depends on implementation
        map.isLongKeyStorage(); // Just ensure it doesn't throw
    }

    @Test
    void testKeySet() {
        MezzeMap<String, Integer> map = MezzeMap.of("a", 1).put("b", 2).put("c", 3);
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (var entry : map) {
            keys.add(entry.key());
        }
        assertEquals(java.util.Set.of("a", "b", "c"), keys);
    }
}
