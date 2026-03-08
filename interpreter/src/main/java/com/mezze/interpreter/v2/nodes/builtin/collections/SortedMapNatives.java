package com.mezze.interpreter.v2.nodes.builtin.collections;

import com.mezze.interpreter.v2.values.*;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.util.TreeMap;
import java.util.Map;

/**
 * Native SortedMap operations - ULTRA PERFORMANT.
 *
 * SortedMap in V2 is represented as a wrapper around Java's TreeMap.
 * Uses persistent semantics (returns new maps on mutation).
 */
public final class SortedMapNatives {

    private SortedMapNatives() {}

    /**
     * Wrapper class for TreeMap to enable Truffle interop
     */
    public static final class MezzeSortedMap {
        private final TreeMap<Object, Object> map;

        public MezzeSortedMap() {
            this.map = new TreeMap<>(SortedMapNatives::compare);
        }

        private MezzeSortedMap(TreeMap<Object, Object> map) {
            this.map = map;
        }

        public TreeMap<Object, Object> getMap() {
            return map;
        }

        @TruffleBoundary
        public MezzeSortedMap insert(Object key, Object value) {
            TreeMap<Object, Object> newMap = new TreeMap<>(map);
            newMap.put(key, value);
            return new MezzeSortedMap(newMap);
        }

        @TruffleBoundary
        public MezzeSortedMap remove(Object key) {
            TreeMap<Object, Object> newMap = new TreeMap<>(map);
            newMap.remove(key);
            return new MezzeSortedMap(newMap);
        }

        public int size() {
            return map.size();
        }

        public boolean isEmpty() {
            return map.isEmpty();
        }

        @TruffleBoundary
        public boolean containsKey(Object key) {
            return map.containsKey(key);
        }

        @TruffleBoundary
        public MezzeVariant get(Object key) {
            Object value = map.get(key);
            if (value == null && !map.containsKey(key)) {
                return MezzeVariant.none();
            }
            return MezzeVariant.some(value);
        }

        @TruffleBoundary
        public Object[] keys() {
            return map.keySet().toArray();
        }

        @TruffleBoundary
        public Object[] values() {
            return map.values().toArray();
        }

        @TruffleBoundary
        public Object[] entries() {
            Object[] result = new Object[map.size()];
            int i = 0;
            for (Map.Entry<Object, Object> entry : map.entrySet()) {
                result[i++] = MezzeRecord.fromFields(
                    new String[]{"key", "value"},
                    new Object[]{entry.getKey(), entry.getValue()}
                );
            }
            return result;
        }
    }

    // ==================== Factory Operations ====================

    public static MezzeSortedMap empty() {
        return new MezzeSortedMap();
    }

    @TruffleBoundary
    public static MezzeSortedMap singleton(Object key, Object value) {
        MezzeSortedMap map = new MezzeSortedMap();
        return map.insert(key, value);
    }

    // ==================== Basic Operations ====================

    public static MezzeSortedMap insert(MezzeSortedMap map, Object key, Object value) {
        return map.insert(key, value);
    }

    public static MezzeVariant get(MezzeSortedMap map, Object key) {
        return map.get(key);
    }

    public static MezzeSortedMap remove(MezzeSortedMap map, Object key) {
        return map.remove(key);
    }

    public static boolean containsKey(MezzeSortedMap map, Object key) {
        return map.containsKey(key);
    }

    public static long size(MezzeSortedMap map) {
        return map.size();
    }

    public static boolean isEmpty(MezzeSortedMap map) {
        return map.isEmpty();
    }

    public static MezzeList keys(MezzeSortedMap map) {
        return MezzeList.fromArray(map.keys());
    }

    public static MezzeList values(MezzeSortedMap map) {
        return MezzeList.fromArray(map.values());
    }

    public static MezzeList entries(MezzeSortedMap map) {
        return MezzeList.fromArray(map.entries());
    }

    // ==================== Additional Operations ====================

    @TruffleBoundary
    public static boolean equals(MezzeSortedMap a, MezzeSortedMap b) {
        if (a == b) return true;
        if (a.size() != b.size()) return false;
        return a.getMap().equals(b.getMap());
    }

    @TruffleBoundary
    public static MezzeVariant minEntry(MezzeSortedMap map) {
        if (map.isEmpty()) {
            return MezzeVariant.none();
        }
        Map.Entry<Object, Object> entry = map.getMap().firstEntry();
        return MezzeVariant.some(MezzeRecord.fromFields(
            new String[]{"key", "value"},
            new Object[]{entry.getKey(), entry.getValue()}
        ));
    }

    @TruffleBoundary
    public static MezzeVariant maxEntry(MezzeSortedMap map) {
        if (map.isEmpty()) {
            return MezzeVariant.none();
        }
        Map.Entry<Object, Object> entry = map.getMap().lastEntry();
        return MezzeVariant.some(MezzeRecord.fromFields(
            new String[]{"key", "value"},
            new Object[]{entry.getKey(), entry.getValue()}
        ));
    }

    @TruffleBoundary
    public static MapNatives.MezzeMap toMap(MezzeSortedMap sortedMap) {
        MapNatives.MezzeMap result = MapNatives.empty();
        for (Map.Entry<Object, Object> entry : sortedMap.getMap().entrySet()) {
            result = MapNatives.insert(result, entry.getKey(), entry.getValue());
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeSortedMap fromMap(MapNatives.MezzeMap map) {
        MezzeSortedMap result = new MezzeSortedMap();
        for (Map.Entry<Object, Object> entry : map.getMap().entrySet()) {
            result = result.insert(entry.getKey(), entry.getValue());
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeVariant minKey(MezzeSortedMap map) {
        if (map.isEmpty()) {
            return MezzeVariant.none();
        }
        return MezzeVariant.some(map.getMap().firstKey());
    }

    @TruffleBoundary
    public static MezzeVariant maxKey(MezzeSortedMap map) {
        if (map.isEmpty()) {
            return MezzeVariant.none();
        }
        return MezzeVariant.some(map.getMap().lastKey());
    }

    // ==================== Comparison Helper ====================

    @TruffleBoundary
    @SuppressWarnings("unchecked")
    private static int compare(Object a, Object b) {
        if (a instanceof Long && b instanceof Long) {
            return Long.compare((Long) a, (Long) b);
        }
        if (a instanceof Double && b instanceof Double) {
            return Double.compare((Double) a, (Double) b);
        }
        if (a instanceof String && b instanceof String) {
            return ((String) a).compareTo((String) b);
        }
        if (a instanceof Boolean && b instanceof Boolean) {
            return Boolean.compare((Boolean) a, (Boolean) b);
        }
        // Fallback: compare hash codes
        return Integer.compare(System.identityHashCode(a), System.identityHashCode(b));
    }
}
