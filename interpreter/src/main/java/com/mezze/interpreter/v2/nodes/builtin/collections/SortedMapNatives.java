package com.mezze.interpreter.v2.nodes.builtin.collections;

import com.mezze.interpreter.v2.values.*;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

/**
 * Native SortedMap operations - ULTRA PERFORMANT.
 *
 * SortedMap in V2 is represented as MezzeSortedMap from values package.
 * Uses sorted array backing for O(log n) lookups and O(1) min/max.
 * Uses persistent semantics (returns new maps on mutation).
 */
public final class SortedMapNatives {

    private SortedMapNatives() {}

    // ==================== Factory Operations ====================

    public static MezzeSortedMap empty() {
        return MezzeSortedMap.empty();
    }

    @TruffleBoundary
    public static MezzeSortedMap singleton(Object key, Object value) {
        return MezzeSortedMap.of(key, value);
    }

    // ==================== Basic Operations ====================

    @TruffleBoundary
    public static MezzeSortedMap insert(MezzeSortedMap map, Object key, Object value) {
        return map.put(key, value);
    }

    @TruffleBoundary
    public static MezzeVariant get(MezzeSortedMap map, Object key) {
        Object value = map.get(key);
        if (value == null && !map.containsKey(key)) {
            return MezzeVariant.none();
        }
        return MezzeVariant.some(value);
    }

    @TruffleBoundary
    public static MezzeSortedMap remove(MezzeSortedMap map, Object key) {
        return map.remove(key);
    }

    @TruffleBoundary
    public static boolean containsKey(MezzeSortedMap map, Object key) {
        return map.containsKey(key);
    }

    public static long size(MezzeSortedMap map) {
        return map.size();
    }

    public static boolean isEmpty(MezzeSortedMap map) {
        return map.isEmpty();
    }

    @TruffleBoundary
    public static MezzeList keys(MezzeSortedMap map) {
        Object[] keysArray = toArray(map.keys());
        return MezzeList.fromArray(keysArray);
    }

    @TruffleBoundary
    public static MezzeList values(MezzeSortedMap map) {
        Object[] valuesArray = toArray(map.values());
        return MezzeList.fromArray(valuesArray);
    }

    @TruffleBoundary
    public static MezzeList entries(MezzeSortedMap map) {
        java.util.Map<Object, Object> javaMap = map.toJavaMap();
        Object[] result = new Object[javaMap.size()];
        int i = 0;
        for (java.util.Map.Entry<Object, Object> entry : javaMap.entrySet()) {
            result[i++] = MezzeRecord.fromFields(
                new String[]{"key", "value"},
                new Object[]{entry.getKey(), entry.getValue()}
            );
        }
        return MezzeList.fromArray(result);
    }

    // ==================== Additional Operations ====================

    @TruffleBoundary
    public static boolean equals(MezzeSortedMap a, MezzeSortedMap b) {
        return a.equals(b);
    }

    @TruffleBoundary
    public static MezzeVariant minEntry(MezzeSortedMap map) {
        if (map.isEmpty()) {
            return MezzeVariant.none();
        }
        Object[] entry = map.minEntry();
        return MezzeVariant.some(MezzeRecord.fromFields(
            new String[]{"key", "value"},
            new Object[]{entry[0], entry[1]}
        ));
    }

    @TruffleBoundary
    public static MezzeVariant maxEntry(MezzeSortedMap map) {
        if (map.isEmpty()) {
            return MezzeVariant.none();
        }
        Object[] entry = map.maxEntry();
        return MezzeVariant.some(MezzeRecord.fromFields(
            new String[]{"key", "value"},
            new Object[]{entry[0], entry[1]}
        ));
    }

    @TruffleBoundary
    public static MezzeMap toMap(MezzeSortedMap sortedMap) {
        MezzeMap result = MezzeMap.empty();
        for (java.util.Map.Entry<Object, Object> entry : sortedMap.toJavaMap().entrySet()) {
            result = result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeSortedMap fromMap(MezzeMap map) {
        MezzeSortedMap result = MezzeSortedMap.empty();
        for (com.mezze.datastructures.map.MezzeMap.Entry entry : map.getDelegate()) {
            result = result.put(entry.key(), entry.value());
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeVariant minKey(MezzeSortedMap map) {
        if (map.isEmpty()) {
            return MezzeVariant.none();
        }
        return MezzeVariant.some(map.minKey());
    }

    @TruffleBoundary
    public static MezzeVariant maxKey(MezzeSortedMap map) {
        if (map.isEmpty()) {
            return MezzeVariant.none();
        }
        return MezzeVariant.some(map.maxKey());
    }

    // ==================== Higher-Order Functions ====================

    @TruffleBoundary
    public static MezzeSortedMap filter(MezzeSortedMap map, MezzeFunction predicate,
                                        com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeSortedMap result = MezzeSortedMap.empty();
        for (java.util.Map.Entry<Object, Object> entry : map.toJavaMap().entrySet()) {
            Object res = callNode.call(predicate.getCallTarget(), prepareArgsKV(predicate, entry.getKey(), entry.getValue()));
            if (res instanceof Boolean && (Boolean) res) {
                result = result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    @TruffleBoundary
    public static Object fold(MezzeSortedMap map, Object init, MezzeFunction f,
                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        Object acc = init;
        for (java.util.Map.Entry<Object, Object> entry : map.toJavaMap().entrySet()) {
            acc = callNode.call(f.getCallTarget(), prepareArgsFold(f, acc, entry.getKey(), entry.getValue()));
        }
        return acc;
    }

    @TruffleBoundary
    public static MezzeSortedMap mapValues(MezzeSortedMap map, MezzeFunction f,
                                           com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeSortedMap result = MezzeSortedMap.empty();
        for (java.util.Map.Entry<Object, Object> entry : map.toJavaMap().entrySet()) {
            Object mapped = callNode.call(f.getCallTarget(), prepareArgsVal(f, entry.getValue()));
            result = result.put(entry.getKey(), mapped);
        }
        return result;
    }

    @TruffleBoundary
    public static boolean any(MezzeSortedMap map, MezzeFunction predicate,
                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        for (java.util.Map.Entry<Object, Object> entry : map.toJavaMap().entrySet()) {
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
        for (java.util.Map.Entry<Object, Object> entry : map.toJavaMap().entrySet()) {
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
        for (java.util.Map.Entry<Object, Object> entry : map.toJavaMap().entrySet()) {
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
        for (java.util.Map.Entry<Object, Object> entry : map.toJavaMap().entrySet()) {
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
        for (java.util.Map.Entry<Object, Object> entry : map.toJavaMap().entrySet()) {
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
        for (Object v : map.values()) {
            if (java.util.Objects.equals(v, value)) {
                return true;
            }
        }
        return false;
    }

    @TruffleBoundary
    public static Object getOrDefault(MezzeSortedMap map, Object key, Object defaultValue) {
        Object value = map.get(key);
        if (value == null && !map.containsKey(key)) {
            return defaultValue;
        }
        return value;
    }

    @TruffleBoundary
    public static MezzeSortedMap update(MezzeSortedMap map, Object key, MezzeFunction f,
                                        com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeVariant current = get(map, key);
        Object newValue = callNode.call(f.getCallTarget(), prepareArgsVal(f, current));
        return map.put(key, newValue);
    }

    @TruffleBoundary
    public static MezzeSortedMap merge(MezzeSortedMap m1, MezzeSortedMap m2) {
        return m1.merge(m2);
    }

    @TruffleBoundary
    public static MezzeSortedMap mergeWith(MezzeSortedMap m1, MezzeSortedMap m2, MezzeFunction f,
                                           com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeSortedMap result = m1;
        for (java.util.Map.Entry<Object, Object> entry : m2.toJavaMap().entrySet()) {
            Object key = entry.getKey();
            Object v2 = entry.getValue();
            if (result.containsKey(key)) {
                Object v1 = result.get(key);
                Object merged = callNode.call(f.getCallTarget(), prepareArgsLR(f, v1, v2));
                result = result.put(key, merged);
            } else {
                result = result.put(key, v2);
            }
        }
        return result;
    }

    // ==================== Conversion ====================

    @TruffleBoundary
    public static MezzeSeq toSeq(MezzeSortedMap map) {
        java.util.Map<Object, Object> javaMap = map.toJavaMap();
        Object[] entries = new Object[javaMap.size()];
        int i = 0;
        for (java.util.Map.Entry<Object, Object> entry : javaMap.entrySet()) {
            entries[i++] = MezzeRecord.fromFields(
                new String[]{"k", "v"},
                new Object[]{entry.getKey(), entry.getValue()}
            );
        }
        return MezzeSeq.fromArray(entries);
    }

    @TruffleBoundary
    public static MezzeSeq keysSeq(MezzeSortedMap map) {
        return MezzeSeq.fromArray(toArray(map.keys()));
    }

    @TruffleBoundary
    public static MezzeSeq valuesSeq(MezzeSortedMap map) {
        return MezzeSeq.fromArray(toArray(map.values()));
    }

    // ==================== Task #15: Additional Operations ====================

    @TruffleBoundary
    public static MezzeRecord partition(MezzeSortedMap map, MezzeFunction predicate,
                                        com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeSortedMap passMap = MezzeSortedMap.empty();
        MezzeSortedMap failMap = MezzeSortedMap.empty();
        for (java.util.Map.Entry<Object, Object> entry : map.toJavaMap().entrySet()) {
            Object result = callNode.call(predicate.getCallTarget(), prepareArgsKV(predicate, entry.getKey(), entry.getValue()));
            if (result instanceof Boolean && (Boolean) result) {
                passMap = passMap.put(entry.getKey(), entry.getValue());
            } else {
                failMap = failMap.put(entry.getKey(), entry.getValue());
            }
        }
        return MezzeRecord.fromFields(
            new String[]{"pass", "fail"},
            new Object[]{passMap, failMap}
        );
    }

    @TruffleBoundary
    public static MezzeSortedMap range(MezzeSortedMap map, Object loKey, Object hiKey) {
        // Use the value class's range method which returns [lo, hi)
        // We need [lo, hi] inclusive, so we filter manually
        MezzeSortedMap result = MezzeSortedMap.empty();
        for (java.util.Map.Entry<Object, Object> entry : map.toJavaMap().entrySet()) {
            Object key = entry.getKey();
            if (compare(key, loKey) >= 0 && compare(key, hiKey) <= 0) {
                result = result.put(key, entry.getValue());
            }
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeVariant floorKey(MezzeSortedMap map, Object key) {
        // Find largest key <= given key
        Object result = null;
        for (Object k : map.keys()) {
            if (compare(k, key) <= 0) {
                if (result == null || compare(k, result) > 0) {
                    result = k;
                }
            }
        }
        if (result == null) {
            return MezzeVariant.none();
        }
        return MezzeVariant.some(result);
    }

    @TruffleBoundary
    public static MezzeVariant ceilingKey(MezzeSortedMap map, Object key) {
        // Find smallest key >= given key
        Object result = null;
        for (Object k : map.keys()) {
            if (compare(k, key) >= 0) {
                if (result == null || compare(k, result) < 0) {
                    result = k;
                }
            }
        }
        if (result == null) {
            return MezzeVariant.none();
        }
        return MezzeVariant.some(result);
    }

    @TruffleBoundary
    public static MezzeVariant lowerKey(MezzeSortedMap map, Object key) {
        // Find largest key < given key
        Object result = null;
        for (Object k : map.keys()) {
            if (compare(k, key) < 0) {
                if (result == null || compare(k, result) > 0) {
                    result = k;
                }
            }
        }
        if (result == null) {
            return MezzeVariant.none();
        }
        return MezzeVariant.some(result);
    }

    @TruffleBoundary
    public static MezzeVariant higherKey(MezzeSortedMap map, Object key) {
        // Find smallest key > given key
        Object result = null;
        for (Object k : map.keys()) {
            if (compare(k, key) > 0) {
                if (result == null || compare(k, result) < 0) {
                    result = k;
                }
            }
        }
        if (result == null) {
            return MezzeVariant.none();
        }
        return MezzeVariant.some(result);
    }

    @TruffleBoundary
    public static MezzeVariant floorEntry(MezzeSortedMap map, Object key) {
        MezzeVariant floorKeyOpt = floorKey(map, key);
        if (floorKeyOpt.isNone()) {
            return MezzeVariant.none();
        }
        Object floorK = floorKeyOpt.getPayloadValue();
        Object value = map.get(floorK);
        return MezzeVariant.some(MezzeRecord.fromFields(
            new String[]{"k", "v"},
            new Object[]{floorK, value}
        ));
    }

    @TruffleBoundary
    public static MezzeVariant ceilingEntry(MezzeSortedMap map, Object key) {
        MezzeVariant ceilingKeyOpt = ceilingKey(map, key);
        if (ceilingKeyOpt.isNone()) {
            return MezzeVariant.none();
        }
        Object ceilingK = ceilingKeyOpt.getPayloadValue();
        Object value = map.get(ceilingK);
        return MezzeVariant.some(MezzeRecord.fromFields(
            new String[]{"k", "v"},
            new Object[]{ceilingK, value}
        ));
    }

    // ==================== Task #16: Advanced Operations ====================

    @TruffleBoundary
    public static MezzeSortedMap headMap(MezzeSortedMap map, Object toKey) {
        MezzeSortedMap result = MezzeSortedMap.empty();
        for (java.util.Map.Entry<Object, Object> entry : map.toJavaMap().entrySet()) {
            if (compare(entry.getKey(), toKey) < 0) {
                result = result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeSortedMap tailMap(MezzeSortedMap map, Object fromKey) {
        MezzeSortedMap result = MezzeSortedMap.empty();
        for (java.util.Map.Entry<Object, Object> entry : map.toJavaMap().entrySet()) {
            if (compare(entry.getKey(), fromKey) >= 0) {
                result = result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeSortedMap subMap(MezzeSortedMap map, Object loKey, Object hiKey) {
        MezzeSortedMap result = MezzeSortedMap.empty();
        for (java.util.Map.Entry<Object, Object> entry : map.toJavaMap().entrySet()) {
            Object key = entry.getKey();
            // subMap is [lo, hi) - lo inclusive, hi exclusive
            if (compare(key, loKey) >= 0 && compare(key, hiKey) < 0) {
                result = result.put(key, entry.getValue());
            }
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeSortedMap take(MezzeSortedMap map, long n) {
        MezzeSortedMap result = MezzeSortedMap.empty();
        int count = 0;
        for (java.util.Map.Entry<Object, Object> entry : map.toJavaMap().entrySet()) {
            if (count >= n) break;
            result = result.put(entry.getKey(), entry.getValue());
            count++;
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeSortedMap drop(MezzeSortedMap map, long n) {
        MezzeSortedMap result = MezzeSortedMap.empty();
        int count = 0;
        for (java.util.Map.Entry<Object, Object> entry : map.toJavaMap().entrySet()) {
            if (count >= n) {
                result = result.put(entry.getKey(), entry.getValue());
            }
            count++;
        }
        return result;
    }

    // ==================== Additional Higher-Order Functions ====================

    @TruffleBoundary
    public static MezzeSortedMap mapKeys(MezzeSortedMap map, MezzeFunction f,
                                         com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeSortedMap result = MezzeSortedMap.empty();
        for (java.util.Map.Entry<Object, Object> entry : map.toJavaMap().entrySet()) {
            Object newKey = callNode.call(f.getCallTarget(), prepareArgsVal(f, entry.getKey()));
            result = result.put(newKey, entry.getValue());
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeSortedMap filterKeys(MezzeSortedMap map, MezzeFunction predicate,
                                            com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeSortedMap result = MezzeSortedMap.empty();
        for (java.util.Map.Entry<Object, Object> entry : map.toJavaMap().entrySet()) {
            Object res = callNode.call(predicate.getCallTarget(), prepareArgsVal(predicate, entry.getKey()));
            if (res instanceof Boolean && (Boolean) res) {
                result = result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeSortedMap filterValues(MezzeSortedMap map, MezzeFunction predicate,
                                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeSortedMap result = MezzeSortedMap.empty();
        for (java.util.Map.Entry<Object, Object> entry : map.toJavaMap().entrySet()) {
            Object res = callNode.call(predicate.getCallTarget(), prepareArgsVal(predicate, entry.getValue()));
            if (res instanceof Boolean && (Boolean) res) {
                result = result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeSortedMap invert(MezzeSortedMap map) {
        MezzeSortedMap result = MezzeSortedMap.empty();
        for (java.util.Map.Entry<Object, Object> entry : map.toJavaMap().entrySet()) {
            result = result.put(entry.getValue(), entry.getKey());
        }
        return result;
    }

    // ==================== List Conversion ====================

    @TruffleBoundary
    public static MezzeList toList(MezzeSortedMap map) {
        java.util.Map<Object, Object> javaMap = map.toJavaMap();
        Object[] entries = new Object[javaMap.size()];
        int i = 0;
        for (java.util.Map.Entry<Object, Object> entry : javaMap.entrySet()) {
            entries[i++] = MezzeRecord.fromFields(
                new String[]{"key", "value"},
                new Object[]{entry.getKey(), entry.getValue()}
            );
        }
        return MezzeList.fromArray(entries);
    }

    @TruffleBoundary
    public static MezzeSortedMap fromList(MezzeList list) {
        MezzeSortedMap result = MezzeSortedMap.empty();
        for (Object item : list) {
            if (item instanceof MezzeRecord) {
                MezzeRecord record = (MezzeRecord) item;
                Object key = record.getField("key");
                Object value = record.getField("value");
                result = result.put(key, value);
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

    @TruffleBoundary
    private static Object[] toArray(Iterable<Object> iterable) {
        java.util.List<Object> list = new java.util.ArrayList<>();
        for (Object item : iterable) {
            list.add(item);
        }
        return list.toArray();
    }
}
