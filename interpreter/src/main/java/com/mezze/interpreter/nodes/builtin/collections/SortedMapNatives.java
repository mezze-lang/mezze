package com.mezze.interpreter.nodes.builtin.collections;

import com.mezze.datastructures.map.MezzeMap;
import com.mezze.datastructures.list.MezzeList;
import com.mezze.datastructures.seq.MezzeSeq;
import com.mezze.datastructures.sorted.MezzeSortedMap;
import com.mezze.interpreter.values.*;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

/**
 * Native SortedMap operations - ULTRA PERFORMANT.
 *
 * SortedMap in V2 is represented as MezzeSortedMap from datastructures module.
 * Uses sorted array backing for O(log n) lookups and O(1) min/max.
 * Uses persistent semantics (returns new maps on mutation).
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public final class SortedMapNatives {

    private SortedMapNatives() {}

    // ==================== Factory Operations ====================

    public static MezzeSortedMap empty() {
        return MezzeSortedMap.empty();
    }

    @TruffleBoundary
    public static MezzeSortedMap singleton(Object key, Object value) {
        return MezzeSortedMap.<Comparable, Object>empty().putObject(key, value);
    }

    // ==================== Basic Operations ====================

    @TruffleBoundary
    public static MezzeSortedMap insert(MezzeSortedMap map, Object key, Object value) {
        return map.putObject(key, value);
    }

    @TruffleBoundary
    public static MezzeVariant get(MezzeSortedMap map, Object key) {
        Object value = map.getObject(key);
        if (value == null && !map.containsKeyObject(key)) {
            return MezzeVariant.none();
        }
        return MezzeVariant.some(value);
    }

    @TruffleBoundary
    public static MezzeSortedMap remove(MezzeSortedMap map, Object key) {
        return map.removeObject(key);
    }

    @TruffleBoundary
    public static boolean containsKey(MezzeSortedMap map, Object key) {
        return map.containsKeyObject(key);
    }

    public static long size(MezzeSortedMap map) {
        return map.size();
    }

    public static boolean isEmpty(MezzeSortedMap map) {
        return map.isEmpty();
    }

    @TruffleBoundary
    public static MezzeList keys(MezzeSortedMap map) {
        return MezzeList.of(map.keys());
    }

    @TruffleBoundary
    public static MezzeList values(MezzeSortedMap map) {
        return MezzeList.of(map.values());
    }

    @TruffleBoundary
    public static MezzeList entries(MezzeSortedMap map) {
        Object[] keys = map.keys();
        Object[] values = map.values();
        Object[] result = new Object[map.size()];
        for (int i = 0; i < keys.length; i++) {
            result[i] = MezzeRecord.fromFields(
                new String[]{"k", "v"},
                new Object[]{keys[i], values[i]}
            );
        }
        return MezzeList.of(result);
    }

    // ==================== Additional Operations ====================

    @TruffleBoundary
    public static boolean equals(MezzeSortedMap a, MezzeSortedMap b) {
        return a.equals(b);
    }

    /**
     * Lexicographic comparison of two sorted maps (by key order, then value order).
     * Returns -1, 0, or 1.
     */
    @TruffleBoundary
    public static long compareMaps(MezzeSortedMap map1, MezzeSortedMap map2) {
        Object[] keys1 = map1.getKeysInternal();
        Object[] keys2 = map2.getKeysInternal();
        Object[] vals1 = map1.getValuesInternal();
        Object[] vals2 = map2.getValuesInternal();
        int minLen = Math.min(keys1.length, keys2.length);
        for (int i = 0; i < minLen; i++) {
            int cmp = compare(keys1[i], keys2[i]);
            if (cmp != 0) {
                return cmp < 0 ? -1L : 1L;
            }
            cmp = compare(vals1[i], vals2[i]);
            if (cmp != 0) {
                return cmp < 0 ? -1L : 1L;
            }
        }
        return Integer.compare(keys1.length, keys2.length);
    }

    @TruffleBoundary
    public static MezzeVariant minEntry(MezzeSortedMap map) {
        if (map.isEmpty()) {
            return MezzeVariant.none();
        }
        return MezzeVariant.some(MezzeRecord.fromFields(
            new String[]{"k", "v"},
            new Object[]{map.minKey(), map.minValue()}
        ));
    }

    @TruffleBoundary
    public static MezzeVariant maxEntry(MezzeSortedMap map) {
        if (map.isEmpty()) {
            return MezzeVariant.none();
        }
        return MezzeVariant.some(MezzeRecord.fromFields(
            new String[]{"k", "v"},
            new Object[]{map.maxKey(), map.maxValue()}
        ));
    }

    @TruffleBoundary
    public static MezzeMap toMap(MezzeSortedMap sortedMap) {
        MezzeMap result = MezzeMap.empty();
        for (Object entryObj : sortedMap.rawEntries()) {
            MezzeSortedMap.RawEntry entry = (MezzeSortedMap.RawEntry) entryObj;
            result = result.putObject(entry.key(), entry.value());
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeSortedMap fromMap(MezzeMap map) {
        MezzeSortedMap result = MezzeSortedMap.empty();
        for (MezzeMap.Entry entry : (Iterable<MezzeMap.Entry>) (Object) map) {
            result = result.putObject(entry.key(), entry.value());
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
        var ctx = ScalarCall.prepare2(predicate, "k", "v");
        MezzeSortedMap result = MezzeSortedMap.empty();
        for (MezzeSortedMap.RawEntry entry : (Iterable<MezzeSortedMap.RawEntry>) map.rawEntries()) {
            Object res = ctx.call2(callNode, entry.key(), entry.value());
            if (res instanceof Boolean && (Boolean) res) {
                result = result.putObject(entry.key(), entry.value());
            }
        }
        return result;
    }

    @TruffleBoundary
    public static Object fold(MezzeSortedMap map, Object init, MezzeFunction f,
                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare3(f, "acc", "k", "v");
        Object acc = init;
        for (MezzeSortedMap.RawEntry entry : (Iterable<MezzeSortedMap.RawEntry>) map.rawEntries()) {
            acc = ctx.call3(callNode, acc, entry.key(), entry.value());
        }
        return acc;
    }

    @TruffleBoundary
    public static MezzeSortedMap mapValues(MezzeSortedMap map, MezzeFunction f,
                                           com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare1(f, "val");
        MezzeSortedMap result = MezzeSortedMap.empty();
        for (MezzeSortedMap.RawEntry entry : (Iterable<MezzeSortedMap.RawEntry>) map.rawEntries()) {
            Object mapped = ctx.call1(callNode, entry.value());
            result = result.putObject(entry.key(), mapped);
        }
        return result;
    }

    @TruffleBoundary
    public static boolean any(MezzeSortedMap map, MezzeFunction predicate,
                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare2(predicate, "k", "v");
        for (MezzeSortedMap.RawEntry entry : (Iterable<MezzeSortedMap.RawEntry>) map.rawEntries()) {
            Object result = ctx.call2(callNode, entry.key(), entry.value());
            if (result instanceof Boolean && (Boolean) result) {
                return true;
            }
        }
        return false;
    }

    @TruffleBoundary
    public static boolean all(MezzeSortedMap map, MezzeFunction predicate,
                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare2(predicate, "k", "v");
        for (MezzeSortedMap.RawEntry entry : (Iterable<MezzeSortedMap.RawEntry>) map.rawEntries()) {
            Object result = ctx.call2(callNode, entry.key(), entry.value());
            if (!(result instanceof Boolean) || !(Boolean) result) {
                return false;
            }
        }
        return true;
    }

    @TruffleBoundary
    public static boolean none(MezzeSortedMap map, MezzeFunction predicate,
                               com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare2(predicate, "k", "v");
        for (MezzeSortedMap.RawEntry entry : (Iterable<MezzeSortedMap.RawEntry>) map.rawEntries()) {
            Object result = ctx.call2(callNode, entry.key(), entry.value());
            if (result instanceof Boolean && (Boolean) result) {
                return false;
            }
        }
        return true;
    }

    @TruffleBoundary
    public static MezzeVariant find(MezzeSortedMap map, MezzeFunction predicate,
                                    com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare2(predicate, "k", "v");
        for (MezzeSortedMap.RawEntry entry : (Iterable<MezzeSortedMap.RawEntry>) map.rawEntries()) {
            Object result = ctx.call2(callNode, entry.key(), entry.value());
            if (result instanceof Boolean && (Boolean) result) {
                return MezzeVariant.some(MezzeRecord.fromFields(
                    new String[]{"k", "v"},
                    new Object[]{entry.key(), entry.value()}
                ));
            }
        }
        return MezzeVariant.none();
    }

    @TruffleBoundary
    public static long count(MezzeSortedMap map, MezzeFunction predicate,
                             com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare2(predicate, "k", "v");
        long cnt = 0;
        for (MezzeSortedMap.RawEntry entry : (Iterable<MezzeSortedMap.RawEntry>) map.rawEntries()) {
            Object result = ctx.call2(callNode, entry.key(), entry.value());
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
        Object value = map.getObject(key);
        if (value == null && !map.containsKeyObject(key)) {
            return defaultValue;
        }
        return value;
    }

    @TruffleBoundary
    public static MezzeSortedMap update(MezzeSortedMap map, Object key, MezzeFunction f,
                                        com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare1(f, "val");
        MezzeVariant current = get(map, key);
        Object newValue = ctx.call1(callNode, current);
        return map.putObject(key, newValue);
    }

    @TruffleBoundary
    public static MezzeSortedMap merge(MezzeSortedMap m1, MezzeSortedMap m2) {
        MezzeSortedMap result = m1;
        for (Object entryObj : m2.rawEntries()) {
            MezzeSortedMap.RawEntry entry = (MezzeSortedMap.RawEntry) entryObj;
            result = result.putObject(entry.key(), entry.value());
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeSortedMap mergeWith(MezzeSortedMap m1, MezzeSortedMap m2, MezzeFunction f,
                                           com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare2(f, "l", "r");
        MezzeSortedMap result = m1;
        for (Object entryObj : m2.rawEntries()) {
            MezzeSortedMap.RawEntry entry = (MezzeSortedMap.RawEntry) entryObj;
            Object key = entry.key();
            Object v2 = entry.value();
            if (result.containsKeyObject(key)) {
                Object v1 = result.getObject(key);
                Object merged = ctx.call2(callNode, v1, v2);
                result = result.putObject(key, merged);
            } else {
                result = result.putObject(key, v2);
            }
        }
        return result;
    }

    // ==================== Conversion ====================

    @TruffleBoundary
    public static MezzeSeq toSeq(MezzeSortedMap map) {
        Object[] entries = new Object[map.size()];
        int i = 0;
        for (MezzeSortedMap.RawEntry entry : (Iterable<MezzeSortedMap.RawEntry>) map.rawEntries()) {
            entries[i++] = MezzeRecord.fromFields(
                new String[]{"k", "v"},
                new Object[]{entry.key(), entry.value()}
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
        var ctx = ScalarCall.prepare2(predicate, "k", "v");
        MezzeSortedMap passMap = MezzeSortedMap.empty();
        MezzeSortedMap failMap = MezzeSortedMap.empty();
        for (MezzeSortedMap.RawEntry entry : (Iterable<MezzeSortedMap.RawEntry>) map.rawEntries()) {
            Object result = ctx.call2(callNode, entry.key(), entry.value());
            if (result instanceof Boolean && (Boolean) result) {
                passMap = passMap.putObject(entry.key(), entry.value());
            } else {
                failMap = failMap.putObject(entry.key(), entry.value());
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
        for (MezzeSortedMap.RawEntry entry : (Iterable<MezzeSortedMap.RawEntry>) map.rawEntries()) {
            Object key = entry.key();
            if (compare(key, loKey) >= 0 && compare(key, hiKey) <= 0) {
                result = result.putObject(key, entry.value());
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
        Object value = map.getObject(floorK);
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
        Object value = map.getObject(ceilingK);
        return MezzeVariant.some(MezzeRecord.fromFields(
            new String[]{"k", "v"},
            new Object[]{ceilingK, value}
        ));
    }

    // ==================== Task #16: Advanced Operations ====================

    @TruffleBoundary
    public static MezzeSortedMap headMap(MezzeSortedMap map, Object toKey) {
        MezzeSortedMap result = MezzeSortedMap.empty();
        for (MezzeSortedMap.RawEntry entry : (Iterable<MezzeSortedMap.RawEntry>) map.rawEntries()) {
            if (compare(entry.key(), toKey) < 0) {
                result = result.putObject(entry.key(), entry.value());
            }
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeSortedMap tailMap(MezzeSortedMap map, Object fromKey) {
        MezzeSortedMap result = MezzeSortedMap.empty();
        for (MezzeSortedMap.RawEntry entry : (Iterable<MezzeSortedMap.RawEntry>) map.rawEntries()) {
            if (compare(entry.key(), fromKey) >= 0) {
                result = result.putObject(entry.key(), entry.value());
            }
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeSortedMap subMap(MezzeSortedMap map, Object loKey, Object hiKey) {
        MezzeSortedMap result = MezzeSortedMap.empty();
        for (MezzeSortedMap.RawEntry entry : (Iterable<MezzeSortedMap.RawEntry>) map.rawEntries()) {
            Object key = entry.key();
            // subMap is [lo, hi) - lo inclusive, hi exclusive
            if (compare(key, loKey) >= 0 && compare(key, hiKey) < 0) {
                result = result.putObject(key, entry.value());
            }
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeSortedMap take(MezzeSortedMap map, long n) {
        MezzeSortedMap result = MezzeSortedMap.empty();
        int count = 0;
        for (MezzeSortedMap.RawEntry entry : (Iterable<MezzeSortedMap.RawEntry>) map.rawEntries()) {
            if (count >= n) break;
            result = result.putObject(entry.key(), entry.value());
            count++;
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeSortedMap drop(MezzeSortedMap map, long n) {
        MezzeSortedMap result = MezzeSortedMap.empty();
        int count = 0;
        for (MezzeSortedMap.RawEntry entry : (Iterable<MezzeSortedMap.RawEntry>) map.rawEntries()) {
            if (count >= n) {
                result = result.putObject(entry.key(), entry.value());
            }
            count++;
        }
        return result;
    }

    // ==================== Additional Higher-Order Functions ====================

    @TruffleBoundary
    public static MezzeSortedMap mapKeys(MezzeSortedMap map, MezzeFunction f,
                                         com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare1(f, "val");
        MezzeSortedMap result = MezzeSortedMap.empty();
        for (MezzeSortedMap.RawEntry entry : (Iterable<MezzeSortedMap.RawEntry>) map.rawEntries()) {
            Object newKey = ctx.call1(callNode, entry.key());
            result = result.putObject(newKey, entry.value());
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeSortedMap filterKeys(MezzeSortedMap map, MezzeFunction predicate,
                                            com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare1(predicate, "val");
        MezzeSortedMap result = MezzeSortedMap.empty();
        for (MezzeSortedMap.RawEntry entry : (Iterable<MezzeSortedMap.RawEntry>) map.rawEntries()) {
            Object res = ctx.call1(callNode, entry.key());
            if (res instanceof Boolean && (Boolean) res) {
                result = result.putObject(entry.key(), entry.value());
            }
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeSortedMap filterValues(MezzeSortedMap map, MezzeFunction predicate,
                                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare1(predicate, "val");
        MezzeSortedMap result = MezzeSortedMap.empty();
        for (MezzeSortedMap.RawEntry entry : (Iterable<MezzeSortedMap.RawEntry>) map.rawEntries()) {
            Object res = ctx.call1(callNode, entry.value());
            if (res instanceof Boolean && (Boolean) res) {
                result = result.putObject(entry.key(), entry.value());
            }
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeSortedMap invert(MezzeSortedMap map) {
        MezzeSortedMap result = MezzeSortedMap.empty();
        for (MezzeSortedMap.RawEntry entry : (Iterable<MezzeSortedMap.RawEntry>) map.rawEntries()) {
            result = result.putObject(entry.value(), entry.key());
        }
        return result;
    }

    // ==================== List Conversion ====================

    @TruffleBoundary
    public static MezzeList toList(MezzeSortedMap map) {
        Object[] entries = new Object[map.size()];
        int i = 0;
        for (MezzeSortedMap.RawEntry entry : (Iterable<MezzeSortedMap.RawEntry>) map.rawEntries()) {
            entries[i++] = MezzeRecord.fromFields(
                new String[]{"k", "v"},
                new Object[]{entry.key(), entry.value()}
            );
        }
        return MezzeList.of(entries);
    }

    @TruffleBoundary
    public static MezzeSortedMap fromList(MezzeList list) {
        MezzeSortedMap result = MezzeSortedMap.empty();
        for (Object item : list) {
            if (item instanceof MezzeRecord) {
                MezzeRecord record = (MezzeRecord) item;
                Object key = record.getField("key");
                Object value = record.getField("value");
                result = result.putObject(key, value);
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

}
