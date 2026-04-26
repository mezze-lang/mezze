package com.mezze.interpreter.nodes.builtin.collections;

import com.mezze.datastructures.map.MezzeMap;
import com.mezze.datastructures.list.MezzeList;
import com.mezze.datastructures.seq.MezzeSeq;
import com.mezze.datastructures.sorted.MezzeSortedMap;
import com.mezze.interpreter.values.*;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

/**
 * Native Map (HashMap) operations - ULTRA PERFORMANT.
 *
 * Now uses com.mezze.interpreter.values.MezzeMap which is backed by
 * the optimized com.mezze.datastructures.map.MezzeMap with tiered storage.
 *
 * All operations are immutable (return new maps).
 */
public final class MapNatives {

    private MapNatives() {}

    // ==================== Cached Field Name Arrays ====================
    // Avoids repeated allocation of common field name arrays

    private static final String[] FIELD_X = {"x"};
    private static final String[] FIELD_VAL = {"val"};
    private static final String[] FIELD_K_V = {"k", "v"};
    private static final String[] FIELD_KEY_VALUE = {"key", "value"};
    private static final String[] FIELD_ACC_X = {"acc", "x"};
    private static final String[] FIELD_ACC_K_V = {"acc", "k", "v"};
    private static final String[] FIELD_ACC_ENTRY = {"acc", "entry"};
    private static final String[] FIELD_L_R = {"l", "r"};
    private static final String[] FIELD_PASS_FAIL = {"pass", "fail"};
    private static final String[] FIELD_FST_SND = {"fst", "snd"};

    // ==================== Factory Operations ====================

    public static MezzeMap empty() {
        return MezzeMap.empty();
    }

    @TruffleBoundary
    public static MezzeMap fromKeys(Object[] keys, MezzeFunction valueFn,
                                    com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeMap map = MezzeMap.empty();
        for (Object key : keys) {
            // from_keys callback: { val: 'k } -> 'v
            Object value = callNode.call(valueFn.getCallTarget(),
                prepareArgsVal(valueFn, key));
            map = map.put(key, value);
        }
        return map;
    }

    // ==================== Basic Operations ====================

    public static long size(MezzeMap map) {
        return map.size();
    }

    public static boolean isEmpty(MezzeMap map) {
        return map.isEmpty();
    }

    @TruffleBoundary
    public static boolean containsKey(MezzeMap map, Object key) {
        return map.containsKey(key);
    }

    @TruffleBoundary
    public static MezzeVariant get(MezzeMap map, Object key) {
        Object value = map.get(key);
        if (value == null && !map.containsKey(key)) {
            return MezzeVariant.none();
        }
        return MezzeVariant.some(value);
    }

    @TruffleBoundary
    public static MezzeMap insert(MezzeMap map, Object key, Object value) {
        return map.put(key, value);
    }

    @TruffleBoundary
    public static MezzeMap remove(MezzeMap map, Object key) {
        return map.remove(key);
    }

    @TruffleBoundary
    public static MezzeMap removeAll(MezzeMap map, Object[] keys) {
        MezzeMap result = map;
        for (Object key : keys) {
            result = result.remove(key);
        }
        return result;
    }

    /**
     * Get all keys as a List (not Array).
     * Returns MezzeList to match the type signature: (Map 'k 'v) -> (List 'k)
     */
    @TruffleBoundary
    public static MezzeList keys(MezzeMap map) {
        Object[] keyArray = map.keys();
        return MezzeList.of(keyArray);
    }

    /**
     * Get all values as a List (not Array).
     * Returns MezzeList to match the type signature: (Map 'k 'v) -> (List 'v)
     */
    @TruffleBoundary
    public static MezzeList values(MezzeMap map) {
        Object[] valueArray = map.values();
        return MezzeList.of(valueArray);
    }

    /**
     * Get all entries as a List (not Array).
     */
    @TruffleBoundary
    public static MezzeList entries(MezzeMap map) {
        com.mezze.datastructures.map.MezzeMap<Object, Object> delegate = map;
        Object[] result = new Object[delegate.size()];
        int i = 0;
        for (MezzeMap.Entry entry : delegate) {
            result[i++] = MezzeRecord.fromFields(
                FIELD_KEY_VALUE,
                new Object[]{entry.key(), entry.value()}
            );
        }
        return MezzeList.of(result);
    }

    // ==================== Transform Operations ====================

    @TruffleBoundary
    public static MezzeMap filter(MezzeMap map, MezzeFunction predicate,
                                  com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeMap result = MezzeMap.empty();
        for (MezzeMap.Entry entry : (Iterable<MezzeMap.Entry>) (Object) map) {
            // filter callback: { k: 'k, v: 'v } -> Bool
            MezzeRecord kvRecord = MezzeRecord.fromFields(
                FIELD_K_V,
                new Object[]{entry.key(), entry.value()}
            );
            Object res = callNode.call(predicate.getCallTarget(),
                prepareArgsKV(predicate, kvRecord));
            if (res instanceof Boolean && (Boolean) res) {
                result = result.put(entry.key(), entry.value());
            }
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeMap mapValues(MezzeMap map, MezzeFunction mapper,
                                     com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeMap result = MezzeMap.empty();
        for (MezzeMap.Entry entry : (Iterable<MezzeMap.Entry>) (Object) map) {
            // map_values callback: { val: 'v } -> 'w
            Object newValue = callNode.call(mapper.getCallTarget(),
                prepareArgsVal(mapper, entry.value()));
            result = result.put(entry.key(), newValue);
        }
        return result;
    }

    @TruffleBoundary
    public static Object fold(MezzeMap map, Object init, MezzeFunction folder,
                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        Object acc = init;
        for (MezzeMap.Entry entry : (Iterable<MezzeMap.Entry>) (Object) map) {
            // fold callback: { acc: 'acc, k: 'k, v: 'v } -> 'acc
            acc = callNode.call(folder.getCallTarget(),
                prepareArgsFold(folder, acc, entry.key(), entry.value()));
        }
        return acc;
    }

    // ==================== Merge Operations ====================

    @TruffleBoundary
    public static MezzeMap merge(MezzeMap map1, MezzeMap map2) {
        return map1.putAll(map2);
    }

    @TruffleBoundary
    public static MezzeMap mergeWith(MezzeMap map1, MezzeMap map2, MezzeFunction merger,
                                     com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeMap result = map1;
        for (MezzeMap.Entry entry : (Iterable<MezzeMap.Entry>) (Object) map2) {
            Object key = entry.key();
            Object newValue = entry.value();
            if (result.containsKey(key)) {
                Object oldValue = result.get(key);
                // merge_with callback: { l: 'v, r: 'v } -> 'v
                newValue = callNode.call(merger.getCallTarget(),
                    prepareArgsLR(merger, oldValue, newValue));
            }
            result = result.put(key, newValue);
        }
        return result;
    }

    // ==================== Equality ====================

    @TruffleBoundary
    public static boolean equals(MezzeMap map1, MezzeMap map2) {
        return map1.equals(map2);
    }

    // ==================== Helper Methods ====================

    private static Object[] prepareArgs(MezzeFunction fn, Object arg) {
        Object[] closureValues = fn.getClosureValues();
        Object[] args = new Object[closureValues.length + 1];
        System.arraycopy(closureValues, 0, args, 0, closureValues.length);
        args[closureValues.length] = MezzeRecord.fromFields(
            FIELD_X, new Object[]{arg}
        );
        return args;
    }

    private static Object[] prepareArgsTwo(MezzeFunction fn, Object arg1, Object arg2) {
        Object[] closureValues = fn.getClosureValues();
        Object[] args = new Object[closureValues.length + 1];
        System.arraycopy(closureValues, 0, args, 0, closureValues.length);
        args[closureValues.length] = MezzeRecord.fromFields(
            FIELD_ACC_X, new Object[]{arg1, arg2}
        );
        return args;
    }

    // Helper for map_values: { val: 'v } -> 'w
    private static Object[] prepareArgsVal(MezzeFunction fn, Object val) {
        Object[] closureValues = fn.getClosureValues();
        Object[] args = new Object[closureValues.length + 1];
        System.arraycopy(closureValues, 0, args, 0, closureValues.length);
        args[closureValues.length] = MezzeRecord.fromFields(
            FIELD_VAL, new Object[]{val}
        );
        return args;
    }

    // Helper for filter: { k: 'k, v: 'v } -> Bool
    private static Object[] prepareArgsKV(MezzeFunction fn, MezzeRecord kvRecord) {
        Object[] closureValues = fn.getClosureValues();
        Object[] args = new Object[closureValues.length + 1];
        System.arraycopy(closureValues, 0, args, 0, closureValues.length);
        args[closureValues.length] = kvRecord;
        return args;
    }

    // Helper for fold: { acc: 'acc, k: 'k, v: 'v } -> 'acc
    private static Object[] prepareArgsFold(MezzeFunction fn, Object acc, Object k, Object v) {
        Object[] closureValues = fn.getClosureValues();
        Object[] args = new Object[closureValues.length + 1];
        System.arraycopy(closureValues, 0, args, 0, closureValues.length);
        args[closureValues.length] = MezzeRecord.fromFields(
            FIELD_ACC_K_V, new Object[]{acc, k, v}
        );
        return args;
    }

    // Helper for merge_with: { l: 'v, r: 'v } -> 'v
    private static Object[] prepareArgsLR(MezzeFunction fn, Object l, Object r) {
        Object[] closureValues = fn.getClosureValues();
        Object[] args = new Object[closureValues.length + 1];
        System.arraycopy(closureValues, 0, args, 0, closureValues.length);
        args[closureValues.length] = MezzeRecord.fromFields(
            FIELD_L_R, new Object[]{l, r}
        );
        return args;
    }

    // ==================== Predicate Operations ====================

    @TruffleBoundary
    public static boolean any(MezzeMap map, MezzeFunction predicate,
                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        for (MezzeMap.Entry entry : (Iterable<MezzeMap.Entry>) (Object) map) {
            MezzeRecord kvRecord = MezzeRecord.fromFields(
                FIELD_K_V,
                new Object[]{entry.key(), entry.value()}
            );
            Object result = callNode.call(predicate.getCallTarget(), prepareArgsKV(predicate, kvRecord));
            if (result instanceof Boolean && (Boolean) result) {
                return true;
            }
        }
        return false;
    }

    @TruffleBoundary
    public static boolean all(MezzeMap map, MezzeFunction predicate,
                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        for (MezzeMap.Entry entry : (Iterable<MezzeMap.Entry>) (Object) map) {
            MezzeRecord kvRecord = MezzeRecord.fromFields(
                FIELD_K_V,
                new Object[]{entry.key(), entry.value()}
            );
            Object result = callNode.call(predicate.getCallTarget(), prepareArgsKV(predicate, kvRecord));
            if (!(result instanceof Boolean) || !(Boolean) result) {
                return false;
            }
        }
        return true;
    }

    @TruffleBoundary
    public static boolean none(MezzeMap map, MezzeFunction predicate,
                               com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        return !any(map, predicate, callNode);
    }

    @TruffleBoundary
    public static MezzeVariant find(MezzeMap map, MezzeFunction predicate,
                                    com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        for (MezzeMap.Entry entry : (Iterable<MezzeMap.Entry>) (Object) map) {
            MezzeRecord kvRecord = MezzeRecord.fromFields(
                FIELD_K_V,
                new Object[]{entry.key(), entry.value()}
            );
            Object result = callNode.call(predicate.getCallTarget(), prepareArgsKV(predicate, kvRecord));
            if (result instanceof Boolean && (Boolean) result) {
                return MezzeVariant.some(kvRecord);
            }
        }
        return MezzeVariant.none();
    }

    @TruffleBoundary
    public static long count(MezzeMap map, MezzeFunction predicate,
                             com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        long cnt = 0;
        for (MezzeMap.Entry entry : (Iterable<MezzeMap.Entry>) (Object) map) {
            MezzeRecord kvRecord = MezzeRecord.fromFields(
                FIELD_K_V,
                new Object[]{entry.key(), entry.value()}
            );
            Object result = callNode.call(predicate.getCallTarget(), prepareArgsKV(predicate, kvRecord));
            if (result instanceof Boolean && (Boolean) result) {
                cnt++;
            }
        }
        return cnt;
    }

    // ==================== Contains Value ====================

    @TruffleBoundary
    public static boolean containsValue(MezzeMap map, Object value) {
        for (Object v : map.values()) {
            if (java.util.Objects.equals(v, value)) {
                return true;
            }
        }
        return false;
    }

    // ==================== Update ====================

    @TruffleBoundary
    public static MezzeMap update(MezzeMap map, Object key, MezzeFunction fn,
                                  com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeVariant existing = get(map, key);
        Object newValue = callNode.call(fn.getCallTarget(), prepareArgsVal(fn, existing));
        return map.put(key, newValue);
    }

    // ==================== Get Or Default ====================

    @TruffleBoundary
    public static Object getOrDefault(MezzeMap map, Object key, Object defaultValue) {
        MezzeVariant result = get(map, key);
        if (result.isNone()) {
            return defaultValue;
        }
        return result.getPayloadValue();
    }

    // ==================== To Seq ====================

    @TruffleBoundary
    public static MezzeSeq toSeq(MezzeMap map) {
        com.mezze.datastructures.map.MezzeMap<Object, Object> delegate = map;
        Object[] result = new Object[delegate.size()];
        int i = 0;
        for (MezzeMap.Entry entry : delegate) {
            result[i++] = MezzeRecord.fromFields(
                FIELD_KEY_VALUE,
                new Object[]{entry.key(), entry.value()}
            );
        }
        return MezzeSeq.fromArray(result);
    }

    @TruffleBoundary
    public static MezzeSeq keysSeq(MezzeMap map) {
        return MezzeSeq.fromArray(map.keys());
    }

    @TruffleBoundary
    public static MezzeSeq valuesSeq(MezzeMap map) {
        return MezzeSeq.fromArray(map.values());
    }

    // ==================== Singleton ====================

    @TruffleBoundary
    public static MezzeMap singleton(Object key, Object value) {
        return MezzeMap.of(key, value);
    }

    // ==================== Additional Operations ====================

    @TruffleBoundary
    public static MezzeRecord partition(MezzeMap map, MezzeFunction predicate,
                                        com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeMap passMap = MezzeMap.empty();
        MezzeMap failMap = MezzeMap.empty();
        for (MezzeMap.Entry entry : (Iterable<MezzeMap.Entry>) (Object) map) {
            Object result = callNode.call(predicate.getCallTarget(),
                new Object[]{MezzeRecord.fromFields(
                    FIELD_K_V,
                    new Object[]{entry.key(), entry.value()})});
            if (result instanceof Boolean && (Boolean) result) {
                passMap = passMap.put(entry.key(), entry.value());
            } else {
                failMap = failMap.put(entry.key(), entry.value());
            }
        }
        return MezzeRecord.fromFields(
            FIELD_PASS_FAIL,
            new Object[]{passMap, failMap}
        );
    }

    // ==================== Task #21: Min/Max Operations ====================

    @TruffleBoundary
    public static MezzeVariant minKey(MezzeMap map) {
        if (map.isEmpty()) return MezzeVariant.none();
        Object minKey = null;
        for (Object key : map.keys()) {
            if (minKey == null || compare(key, minKey) < 0) {
                minKey = key;
            }
        }
        return MezzeVariant.some(minKey);
    }

    @TruffleBoundary
    public static MezzeVariant maxKey(MezzeMap map) {
        if (map.isEmpty()) return MezzeVariant.none();
        Object maxKey = null;
        for (Object key : map.keys()) {
            if (maxKey == null || compare(key, maxKey) > 0) {
                maxKey = key;
            }
        }
        return MezzeVariant.some(maxKey);
    }

    @TruffleBoundary
    public static MezzeVariant minValue(MezzeMap map) {
        if (map.isEmpty()) return MezzeVariant.none();
        Object minVal = null;
        for (Object val : map.values()) {
            if (minVal == null || compare(val, minVal) < 0) {
                minVal = val;
            }
        }
        return MezzeVariant.some(minVal);
    }

    @TruffleBoundary
    public static MezzeVariant maxValue(MezzeMap map) {
        if (map.isEmpty()) return MezzeVariant.none();
        Object maxVal = null;
        for (Object val : map.values()) {
            if (maxVal == null || compare(val, maxVal) > 0) {
                maxVal = val;
            }
        }
        return MezzeVariant.some(maxVal);
    }

    @TruffleBoundary
    public static MezzeMap invert(MezzeMap map) {
        MezzeMap result = MezzeMap.empty();
        for (MezzeMap.Entry entry : (Iterable<MezzeMap.Entry>) (Object) map) {
            result = result.put(entry.value(), entry.key());
        }
        return result;
    }

    // ==================== Task #22: Key/Value Operations ====================

    @TruffleBoundary
    public static MezzeMap selectKeys(MezzeMap map, Object[] keys) {
        MezzeMap result = MezzeMap.empty();
        for (Object key : keys) {
            if (map.containsKey(key)) {
                result = result.put(key, map.get(key));
            }
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeMap rejectKeys(MezzeMap map, Object[] keys) {
        MezzeMap result = map;
        for (Object key : keys) {
            result = result.remove(key);
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeVariant reduce(MezzeMap map, MezzeFunction f,
                                      com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (map.isEmpty()) {
            return MezzeVariant.none();
        }
        java.util.Iterator<MezzeMap.Entry> iter = map.iterator();
        MezzeMap.Entry first = iter.next();
        MezzeRecord acc = MezzeRecord.fromFields(
            FIELD_K_V,
            new Object[]{first.key(), first.value()}
        );
        while (iter.hasNext()) {
            MezzeMap.Entry entry = iter.next();
            MezzeRecord current = MezzeRecord.fromFields(
                FIELD_K_V,
                new Object[]{entry.key(), entry.value()}
            );
            Object result = callNode.call(f.getCallTarget(),
                new Object[]{MezzeRecord.fromFields(
                    FIELD_ACC_ENTRY,
                    new Object[]{acc, current}
                )});
            acc = (MezzeRecord) result;
        }
        return MezzeVariant.some(acc);
    }

    @TruffleBoundary
    public static MezzeMap groupByValue(MezzeMap map) {
        java.util.LinkedHashMap<Object, java.util.ArrayList<Object>> groups = new java.util.LinkedHashMap<>();
        for (MezzeMap.Entry entry : (Iterable<MezzeMap.Entry>) (Object) map) {
            groups.computeIfAbsent(entry.value(), k -> new java.util.ArrayList<>())
                  .add(entry.key());
        }
        MezzeMap result = MezzeMap.empty();
        for (java.util.Map.Entry<Object, java.util.ArrayList<Object>> entry : groups.entrySet()) {
            result = result.put(entry.getKey(), MezzeList.of(entry.getValue().toArray()));
        }
        return result;
    }

    // ==================== Task #29: Key Operations ====================

    @TruffleBoundary
    public static MezzeMap mapKeys(MezzeMap map, MezzeFunction f,
                                   com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeMap result = MezzeMap.empty();
        for (MezzeMap.Entry entry : (Iterable<MezzeMap.Entry>) (Object) map) {
            Object newKey = callNode.call(f.getCallTarget(), prepareArgsVal(f, entry.key()));
            result = result.put(newKey, entry.value());
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeMap filterKeys(MezzeMap map, MezzeFunction predicate,
                                      com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeMap result = MezzeMap.empty();
        for (MezzeMap.Entry entry : (Iterable<MezzeMap.Entry>) (Object) map) {
            Object res = callNode.call(predicate.getCallTarget(), prepareArgsVal(predicate, entry.key()));
            if (res instanceof Boolean && (Boolean) res) {
                result = result.put(entry.key(), entry.value());
            }
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeMap filterValues(MezzeMap map, MezzeFunction predicate,
                                        com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeMap result = MezzeMap.empty();
        for (MezzeMap.Entry entry : (Iterable<MezzeMap.Entry>) (Object) map) {
            Object res = callNode.call(predicate.getCallTarget(), prepareArgsVal(predicate, entry.value()));
            if (res instanceof Boolean && (Boolean) res) {
                result = result.put(entry.key(), entry.value());
            }
        }
        return result;
    }

    // ==================== Comparison Helper ====================

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
        return 0;
    }

    // ==================== Task #37: Multi-Map Operations ====================

    /**
     * Zip two maps together. For keys present in both, creates pairs of values.
     * Returns Map of {k, {first: v1, second: v2}} for keys present in both maps.
     */
    @TruffleBoundary
    public static MezzeMap zipMaps(MezzeMap m1, MezzeMap m2) {
        MezzeMap result = MezzeMap.empty();
        for (MezzeMap.Entry entry : (Iterable<MezzeMap.Entry>) (Object) m1) {
            Object key = entry.key();
            if (m2.containsKey(key)) {
                result = result.put(key, MezzeRecord.fromFields(
                    FIELD_FST_SND,
                    new Object[]{entry.value(), m2.get(key)}
                ));
            }
        }
        return result;
    }

    /**
     * Compute the difference of two maps: keys in m1 but not in m2.
     * Returns entries from m1 where key is not present in m2.
     */
    @TruffleBoundary
    public static MezzeMap diffMaps(MezzeMap m1, MezzeMap m2) {
        MezzeMap result = MezzeMap.empty();
        for (MezzeMap.Entry entry : (Iterable<MezzeMap.Entry>) (Object) m1) {
            if (!m2.containsKey(entry.key())) {
                result = result.put(entry.key(), entry.value());
            }
        }
        return result;
    }

    /**
     * Compute the intersection of two maps: keys present in both.
     * Takes values from m1 for shared keys.
     */
    @TruffleBoundary
    public static MezzeMap intersectMaps(MezzeMap m1, MezzeMap m2) {
        MezzeMap result = MezzeMap.empty();
        for (MezzeMap.Entry entry : (Iterable<MezzeMap.Entry>) (Object) m1) {
            if (m2.containsKey(entry.key())) {
                result = result.put(entry.key(), entry.value());
            }
        }
        return result;
    }

    /**
     * Symmetric difference: keys in either map but not both.
     * Entries from m1 for keys only in m1, entries from m2 for keys only in m2.
     */
    @TruffleBoundary
    public static MezzeMap symmetricDiffMaps(MezzeMap m1, MezzeMap m2) {
        MezzeMap result = MezzeMap.empty();
        for (MezzeMap.Entry entry : (Iterable<MezzeMap.Entry>) (Object) m1) {
            if (!m2.containsKey(entry.key())) {
                result = result.put(entry.key(), entry.value());
            }
        }
        for (MezzeMap.Entry entry : (Iterable<MezzeMap.Entry>) (Object) m2) {
            if (!m1.containsKey(entry.key())) {
                result = result.put(entry.key(), entry.value());
            }
        }
        return result;
    }

    // ==================== List/SortedMap Conversions ====================

    /**
     * Convert Map to List of {k, v} records.
     */
    @TruffleBoundary
    public static MezzeList toList(MezzeMap map) {
        Object[] entries = new Object[map.size()];
        int i = 0;
        for (MezzeMap.Entry entry : (Iterable<MezzeMap.Entry>) (Object) map) {
            entries[i++] = MezzeRecord.fromFields(
                FIELD_K_V,
                new Object[]{entry.key(), entry.value()}
            );
        }
        return MezzeList.of(entries);
    }

    /**
     * Convert List of {k, v} records to Map.
     */
    @TruffleBoundary
    public static MezzeMap fromList(MezzeList list) {
        MezzeMap result = MezzeMap.empty();
        for (int i = 0; i < list.size(); i++) {
            Object elem = list.get(i);
            if (elem instanceof MezzeRecord rec) {
                Object k = rec.getField("k");
                Object v = rec.getField("v");
                result = result.put(k, v);
            }
        }
        return result;
    }

    /**
     * FlatMap over map entries.
     * Applies function to each {k, v} entry, expecting a Map result, and merges all results.
     */
    @TruffleBoundary
    public static MezzeMap flatMap(MezzeMap map, MezzeFunction f,
                                   com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeMap result = MezzeMap.empty();
        for (MezzeMap.Entry entry : (Iterable<MezzeMap.Entry>) (Object) map) {
            MezzeRecord kvRecord = MezzeRecord.fromFields(
                FIELD_K_V,
                new Object[]{entry.key(), entry.value()}
            );
            Object mapped = callNode.call(f.getCallTarget(), prepareArgsKV(f, kvRecord));
            if (mapped instanceof MezzeMap innerMap) {
                result = result.putAll(innerMap);
            }
        }
        return result;
    }

    /**
     * Convert Map to SortedMap.
     */
    @TruffleBoundary
    public static MezzeSortedMap toSortedMap(MezzeMap map) {
        return SortedMapNatives.fromMap(map);
    }
}
