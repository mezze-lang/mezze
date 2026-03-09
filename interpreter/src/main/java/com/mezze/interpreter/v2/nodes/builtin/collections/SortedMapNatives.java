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

    // ==================== Higher-Order Functions ====================

    @TruffleBoundary
    public static MezzeSortedMap filter(MezzeSortedMap map, MezzeFunction predicate,
                                        com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        TreeMap<Object, Object> result = new TreeMap<>(SortedMapNatives::compare);
        for (Map.Entry<Object, Object> entry : map.getMap().entrySet()) {
            Object res = callNode.call(predicate.getCallTarget(), prepareArgsKV(predicate, entry.getKey(), entry.getValue()));
            if (res instanceof Boolean && (Boolean) res) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return new MezzeSortedMap(result);
    }

    @TruffleBoundary
    public static Object fold(MezzeSortedMap map, Object init, MezzeFunction f,
                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        Object acc = init;
        for (Map.Entry<Object, Object> entry : map.getMap().entrySet()) {
            acc = callNode.call(f.getCallTarget(), prepareArgsFold(f, acc, entry.getKey(), entry.getValue()));
        }
        return acc;
    }

    @TruffleBoundary
    public static MezzeSortedMap mapValues(MezzeSortedMap map, MezzeFunction f,
                                           com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        TreeMap<Object, Object> result = new TreeMap<>(SortedMapNatives::compare);
        for (Map.Entry<Object, Object> entry : map.getMap().entrySet()) {
            Object mapped = callNode.call(f.getCallTarget(), prepareArgsVal(f, entry.getValue()));
            result.put(entry.getKey(), mapped);
        }
        return new MezzeSortedMap(result);
    }

    @TruffleBoundary
    public static boolean any(MezzeSortedMap map, MezzeFunction predicate,
                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        for (Map.Entry<Object, Object> entry : map.getMap().entrySet()) {
            Object result = callNode.call(predicate.getCallTarget(), prepareArgsKV(predicate, entry.getKey(), entry.getValue()));
            if (result instanceof Boolean && (Boolean) result) {
                return true;
            }
        }
        return false;
    }

    @TruffleBoundary
    public static boolean all(MezzeSortedMap map, MezzeFunction predicate,
                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        for (Map.Entry<Object, Object> entry : map.getMap().entrySet()) {
            Object result = callNode.call(predicate.getCallTarget(), prepareArgsKV(predicate, entry.getKey(), entry.getValue()));
            if (!(result instanceof Boolean) || !(Boolean) result) {
                return false;
            }
        }
        return true;
    }

    @TruffleBoundary
    public static boolean none(MezzeSortedMap map, MezzeFunction predicate,
                               com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        for (Map.Entry<Object, Object> entry : map.getMap().entrySet()) {
            Object result = callNode.call(predicate.getCallTarget(), prepareArgsKV(predicate, entry.getKey(), entry.getValue()));
            if (result instanceof Boolean && (Boolean) result) {
                return false;
            }
        }
        return true;
    }

    @TruffleBoundary
    public static MezzeVariant find(MezzeSortedMap map, MezzeFunction predicate,
                                    com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        for (Map.Entry<Object, Object> entry : map.getMap().entrySet()) {
            Object result = callNode.call(predicate.getCallTarget(), prepareArgsKV(predicate, entry.getKey(), entry.getValue()));
            if (result instanceof Boolean && (Boolean) result) {
                return MezzeVariant.some(MezzeRecord.fromFields(
                    new String[]{"k", "v"},
                    new Object[]{entry.getKey(), entry.getValue()}
                ));
            }
        }
        return MezzeVariant.none();
    }

    @TruffleBoundary
    public static long count(MezzeSortedMap map, MezzeFunction predicate,
                             com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        long cnt = 0;
        for (Map.Entry<Object, Object> entry : map.getMap().entrySet()) {
            Object result = callNode.call(predicate.getCallTarget(), prepareArgsKV(predicate, entry.getKey(), entry.getValue()));
            if (result instanceof Boolean && (Boolean) result) {
                cnt++;
            }
        }
        return cnt;
    }

    // ==================== Additional Operations ====================

    @TruffleBoundary
    public static boolean containsValue(MezzeSortedMap map, Object value) {
        return map.getMap().containsValue(value);
    }

    @TruffleBoundary
    public static Object getOrDefault(MezzeSortedMap map, Object key, Object defaultValue) {
        MezzeVariant result = map.get(key);
        if (result.isNone()) {
            return defaultValue;
        }
        return result.getPayloadValue();
    }

    @TruffleBoundary
    public static MezzeSortedMap update(MezzeSortedMap map, Object key, MezzeFunction f,
                                        com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeVariant current = map.get(key);
        Object newValue = callNode.call(f.getCallTarget(), prepareArgsVal(f, current));
        return map.insert(key, newValue);
    }

    @TruffleBoundary
    public static MezzeSortedMap merge(MezzeSortedMap m1, MezzeSortedMap m2) {
        TreeMap<Object, Object> result = new TreeMap<>(m1.getMap());
        result.putAll(m2.getMap());
        return new MezzeSortedMap(result);
    }

    @TruffleBoundary
    public static MezzeSortedMap mergeWith(MezzeSortedMap m1, MezzeSortedMap m2, MezzeFunction f,
                                           com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        TreeMap<Object, Object> result = new TreeMap<>(m1.getMap());
        for (Map.Entry<Object, Object> entry : m2.getMap().entrySet()) {
            Object key = entry.getKey();
            Object v2 = entry.getValue();
            if (result.containsKey(key)) {
                Object v1 = result.get(key);
                Object merged = callNode.call(f.getCallTarget(), prepareArgsLR(f, v1, v2));
                result.put(key, merged);
            } else {
                result.put(key, v2);
            }
        }
        return new MezzeSortedMap(result);
    }

    // ==================== Conversion ====================

    @TruffleBoundary
    public static MezzeSeq toSeq(MezzeSortedMap map) {
        Object[] entries = new Object[map.size()];
        int i = 0;
        for (Map.Entry<Object, Object> entry : map.getMap().entrySet()) {
            entries[i++] = MezzeRecord.fromFields(
                new String[]{"k", "v"},
                new Object[]{entry.getKey(), entry.getValue()}
            );
        }
        return MezzeSeq.fromArray(entries);
    }

    @TruffleBoundary
    public static MezzeSeq keysSeq(MezzeSortedMap map) {
        return MezzeSeq.fromArray(map.keys());
    }

    @TruffleBoundary
    public static MezzeSeq valuesSeq(MezzeSortedMap map) {
        return MezzeSeq.fromArray(map.values());
    }

    // ==================== Task #15: Additional Operations ====================

    @TruffleBoundary
    public static MezzeRecord partition(MezzeSortedMap map, MezzeFunction predicate,
                                        com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        TreeMap<Object, Object> passMap = new TreeMap<>(SortedMapNatives::compare);
        TreeMap<Object, Object> failMap = new TreeMap<>(SortedMapNatives::compare);
        for (Map.Entry<Object, Object> entry : map.getMap().entrySet()) {
            Object result = callNode.call(predicate.getCallTarget(), prepareArgsKV(predicate, entry.getKey(), entry.getValue()));
            if (result instanceof Boolean && (Boolean) result) {
                passMap.put(entry.getKey(), entry.getValue());
            } else {
                failMap.put(entry.getKey(), entry.getValue());
            }
        }
        return MezzeRecord.fromFields(
            new String[]{"pass", "fail"},
            new Object[]{new MezzeSortedMap(passMap), new MezzeSortedMap(failMap)}
        );
    }

    @TruffleBoundary
    public static MezzeSortedMap range(MezzeSortedMap map, Object loKey, Object hiKey) {
        TreeMap<Object, Object> result = new TreeMap<>(SortedMapNatives::compare);
        for (Map.Entry<Object, Object> entry : map.getMap().entrySet()) {
            Object key = entry.getKey();
            if (compare(key, loKey) >= 0 && compare(key, hiKey) <= 0) {
                result.put(key, entry.getValue());
            }
        }
        return new MezzeSortedMap(result);
    }

    @TruffleBoundary
    public static MezzeVariant floorKey(MezzeSortedMap map, Object key) {
        Object result = map.getMap().floorKey(key);
        if (result == null) {
            return MezzeVariant.none();
        }
        return MezzeVariant.some(result);
    }

    @TruffleBoundary
    public static MezzeVariant ceilingKey(MezzeSortedMap map, Object key) {
        Object result = map.getMap().ceilingKey(key);
        if (result == null) {
            return MezzeVariant.none();
        }
        return MezzeVariant.some(result);
    }

    @TruffleBoundary
    public static MezzeVariant lowerKey(MezzeSortedMap map, Object key) {
        Object result = map.getMap().lowerKey(key);
        if (result == null) {
            return MezzeVariant.none();
        }
        return MezzeVariant.some(result);
    }

    @TruffleBoundary
    public static MezzeVariant higherKey(MezzeSortedMap map, Object key) {
        Object result = map.getMap().higherKey(key);
        if (result == null) {
            return MezzeVariant.none();
        }
        return MezzeVariant.some(result);
    }

    @TruffleBoundary
    public static MezzeVariant floorEntry(MezzeSortedMap map, Object key) {
        Map.Entry<Object, Object> entry = map.getMap().floorEntry(key);
        if (entry == null) {
            return MezzeVariant.none();
        }
        return MezzeVariant.some(MezzeRecord.fromFields(
            new String[]{"k", "v"},
            new Object[]{entry.getKey(), entry.getValue()}
        ));
    }

    @TruffleBoundary
    public static MezzeVariant ceilingEntry(MezzeSortedMap map, Object key) {
        Map.Entry<Object, Object> entry = map.getMap().ceilingEntry(key);
        if (entry == null) {
            return MezzeVariant.none();
        }
        return MezzeVariant.some(MezzeRecord.fromFields(
            new String[]{"k", "v"},
            new Object[]{entry.getKey(), entry.getValue()}
        ));
    }

    // ==================== Task #16: Advanced Operations ====================

    @TruffleBoundary
    public static MezzeSortedMap headMap(MezzeSortedMap map, Object toKey) {
        TreeMap<Object, Object> result = new TreeMap<>(SortedMapNatives::compare);
        for (Map.Entry<Object, Object> entry : map.getMap().entrySet()) {
            if (compare(entry.getKey(), toKey) < 0) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return new MezzeSortedMap(result);
    }

    @TruffleBoundary
    public static MezzeSortedMap tailMap(MezzeSortedMap map, Object fromKey) {
        TreeMap<Object, Object> result = new TreeMap<>(SortedMapNatives::compare);
        for (Map.Entry<Object, Object> entry : map.getMap().entrySet()) {
            if (compare(entry.getKey(), fromKey) >= 0) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return new MezzeSortedMap(result);
    }

    @TruffleBoundary
    public static MezzeSortedMap subMap(MezzeSortedMap map, Object loKey, Object hiKey) {
        TreeMap<Object, Object> result = new TreeMap<>(SortedMapNatives::compare);
        for (Map.Entry<Object, Object> entry : map.getMap().entrySet()) {
            Object key = entry.getKey();
            // subMap is [lo, hi) - lo inclusive, hi exclusive
            if (compare(key, loKey) >= 0 && compare(key, hiKey) < 0) {
                result.put(key, entry.getValue());
            }
        }
        return new MezzeSortedMap(result);
    }

    @TruffleBoundary
    public static MezzeSortedMap take(MezzeSortedMap map, long n) {
        TreeMap<Object, Object> result = new TreeMap<>(SortedMapNatives::compare);
        int count = 0;
        for (Map.Entry<Object, Object> entry : map.getMap().entrySet()) {
            if (count >= n) break;
            result.put(entry.getKey(), entry.getValue());
            count++;
        }
        return new MezzeSortedMap(result);
    }

    @TruffleBoundary
    public static MezzeSortedMap drop(MezzeSortedMap map, long n) {
        TreeMap<Object, Object> result = new TreeMap<>(SortedMapNatives::compare);
        int count = 0;
        for (Map.Entry<Object, Object> entry : map.getMap().entrySet()) {
            if (count >= n) {
                result.put(entry.getKey(), entry.getValue());
            }
            count++;
        }
        return new MezzeSortedMap(result);
    }

    // ==================== Additional Higher-Order Functions ====================

    @TruffleBoundary
    public static MezzeSortedMap mapKeys(MezzeSortedMap map, MezzeFunction f,
                                         com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        TreeMap<Object, Object> result = new TreeMap<>(SortedMapNatives::compare);
        for (Map.Entry<Object, Object> entry : map.getMap().entrySet()) {
            Object newKey = callNode.call(f.getCallTarget(), prepareArgsVal(f, entry.getKey()));
            result.put(newKey, entry.getValue());
        }
        return new MezzeSortedMap(result);
    }

    @TruffleBoundary
    public static MezzeSortedMap filterKeys(MezzeSortedMap map, MezzeFunction predicate,
                                            com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        TreeMap<Object, Object> result = new TreeMap<>(SortedMapNatives::compare);
        for (Map.Entry<Object, Object> entry : map.getMap().entrySet()) {
            Object res = callNode.call(predicate.getCallTarget(), prepareArgsVal(predicate, entry.getKey()));
            if (res instanceof Boolean && (Boolean) res) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return new MezzeSortedMap(result);
    }

    @TruffleBoundary
    public static MezzeSortedMap filterValues(MezzeSortedMap map, MezzeFunction predicate,
                                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        TreeMap<Object, Object> result = new TreeMap<>(SortedMapNatives::compare);
        for (Map.Entry<Object, Object> entry : map.getMap().entrySet()) {
            Object res = callNode.call(predicate.getCallTarget(), prepareArgsVal(predicate, entry.getValue()));
            if (res instanceof Boolean && (Boolean) res) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return new MezzeSortedMap(result);
    }

    @TruffleBoundary
    public static MezzeSortedMap invert(MezzeSortedMap map) {
        TreeMap<Object, Object> result = new TreeMap<>(SortedMapNatives::compare);
        for (Map.Entry<Object, Object> entry : map.getMap().entrySet()) {
            result.put(entry.getValue(), entry.getKey());
        }
        return new MezzeSortedMap(result);
    }

    // ==================== List Conversion ====================

    @TruffleBoundary
    public static MezzeList toList(MezzeSortedMap map) {
        Object[] entries = new Object[map.size()];
        int i = 0;
        for (Map.Entry<Object, Object> entry : map.getMap().entrySet()) {
            entries[i++] = MezzeRecord.fromFields(
                new String[]{"key", "value"},
                new Object[]{entry.getKey(), entry.getValue()}
            );
        }
        return MezzeList.fromArray(entries);
    }

    @TruffleBoundary
    public static MezzeSortedMap fromList(MezzeList list) {
        MezzeSortedMap result = new MezzeSortedMap();
        for (Object item : list) {
            if (item instanceof MezzeRecord) {
                MezzeRecord record = (MezzeRecord) item;
                Object key = record.getField("key");
                Object value = record.getField("value");
                result = result.insert(key, value);
            }
        }
        return result;
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

    // ==================== Helper Methods ====================

    private static Object[] prepareArgsKV(MezzeFunction f, Object key, Object value) {
        return new Object[]{MezzeRecord.fromFields(new String[]{"k", "v"}, new Object[]{key, value})};
    }

    private static Object[] prepareArgsVal(MezzeFunction f, Object value) {
        return new Object[]{MezzeRecord.fromFields(new String[]{"val"}, new Object[]{value})};
    }

    private static Object[] prepareArgsFold(MezzeFunction f, Object acc, Object key, Object value) {
        return new Object[]{MezzeRecord.fromFields(new String[]{"acc", "k", "v"}, new Object[]{acc, key, value})};
    }

    private static Object[] prepareArgsLR(MezzeFunction f, Object left, Object right) {
        return new Object[]{MezzeRecord.fromFields(new String[]{"l", "r"}, new Object[]{left, right})};
    }
}
