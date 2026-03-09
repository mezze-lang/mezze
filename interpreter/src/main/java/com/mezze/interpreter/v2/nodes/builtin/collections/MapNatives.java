package com.mezze.interpreter.v2.nodes.builtin.collections;

import com.mezze.interpreter.v2.values.*;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Native Map (HashMap) operations - ULTRA PERFORMANT.
 *
 * Map in V2 uses LinkedHashMap for predictable iteration order.
 * All operations are immutable (return new maps).
 */
public final class MapNatives {

    private MapNatives() {}

    /**
     * Wrapper class for Map
     */
    public static final class MezzeMap {
        private final LinkedHashMap<Object, Object> map;

        public MezzeMap() {
            this.map = new LinkedHashMap<>();
        }

        private MezzeMap(LinkedHashMap<Object, Object> map) {
            this.map = map;
        }

        public LinkedHashMap<Object, Object> getMap() {
            return map;
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
        public MezzeMap insert(Object key, Object value) {
            LinkedHashMap<Object, Object> newMap = new LinkedHashMap<>(map);
            newMap.put(key, value);
            return new MezzeMap(newMap);
        }

        @TruffleBoundary
        public MezzeMap remove(Object key) {
            LinkedHashMap<Object, Object> newMap = new LinkedHashMap<>(map);
            newMap.remove(key);
            return new MezzeMap(newMap);
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

    public static MezzeMap empty() {
        return new MezzeMap();
    }

    @TruffleBoundary
    public static MezzeMap fromKeys(Object[] keys, MezzeFunction valueFn,
                                                                          com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        LinkedHashMap<Object, Object> map = new LinkedHashMap<>();
        for (Object key : keys) {
            // from_keys callback: { val: 'k } -> 'v
            Object value = callNode.call(valueFn.getCallTarget(),
                prepareArgsVal(valueFn, key));
            map.put(key, value);
        }
        return new MezzeMap(map);
    }

    // ==================== Basic Operations ====================

    public static long size(MezzeMap map) {
        return map.size();
    }

    public static boolean isEmpty(MezzeMap map) {
        return map.isEmpty();
    }

    public static boolean containsKey(MezzeMap map, Object key) {
        return map.containsKey(key);
    }

    public static MezzeVariant get(MezzeMap map, Object key) {
        return map.get(key);
    }

    public static MezzeMap insert(MezzeMap map, Object key, Object value) {
        return map.insert(key, value);
    }

    public static MezzeMap remove(MezzeMap map, Object key) {
        return map.remove(key);
    }

    @TruffleBoundary
    public static MezzeMap removeAll(MezzeMap map, Object[] keys) {
        LinkedHashMap<Object, Object> newMap = new LinkedHashMap<>(map.getMap());
        for (Object key : keys) {
            newMap.remove(key);
        }
        return new MezzeMap(newMap);
    }

    /**
     * Get all keys as a List (not Array).
     * Returns MezzeList to match the type signature: (Map 'k 'v) -> (List 'k)
     */
    public static MezzeList keys(MezzeMap map) {
        return MezzeList.fromArray(map.keys());
    }

    /**
     * Get all values as a List (not Array).
     * Returns MezzeList to match the type signature: (Map 'k 'v) -> (List 'v)
     */
    public static MezzeList values(MezzeMap map) {
        return MezzeList.fromArray(map.values());
    }

    /**
     * Get all entries as a List (not Array).
     */
    public static MezzeList entries(MezzeMap map) {
        return MezzeList.fromArray(map.entries());
    }

    // ==================== Transform Operations ====================

    @TruffleBoundary
    public static MezzeMap filter(MezzeMap map, MezzeFunction predicate,
                                                                      com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        LinkedHashMap<Object, Object> result = new LinkedHashMap<>();
        for (Map.Entry<Object, Object> entry : map.getMap().entrySet()) {
            // filter callback: { k: 'k, v: 'v } -> Bool
            MezzeRecord kvRecord = MezzeRecord.fromFields(
                new String[]{"k", "v"},
                new Object[]{entry.getKey(), entry.getValue()}
            );
            Object res = callNode.call(predicate.getCallTarget(),
                prepareArgsKV(predicate, kvRecord));
            if (res instanceof Boolean && (Boolean) res) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return new MezzeMap(result);
    }

    @TruffleBoundary
    public static MezzeMap mapValues(MezzeMap map, MezzeFunction mapper,
                                                                            com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        LinkedHashMap<Object, Object> result = new LinkedHashMap<>();
        for (Map.Entry<Object, Object> entry : map.getMap().entrySet()) {
            // map_values callback: { val: 'v } -> 'w
            Object newValue = callNode.call(mapper.getCallTarget(),
                prepareArgsVal(mapper, entry.getValue()));
            result.put(entry.getKey(), newValue);
        }
        return new MezzeMap(result);
    }

    @TruffleBoundary
    public static Object fold(MezzeMap map, Object init, MezzeFunction folder,
                                                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        Object acc = init;
        for (Map.Entry<Object, Object> entry : map.getMap().entrySet()) {
            // fold callback: { acc: 'acc, k: 'k, v: 'v } -> 'acc
            acc = callNode.call(folder.getCallTarget(),
                prepareArgsFold(folder, acc, entry.getKey(), entry.getValue()));
        }
        return acc;
    }

    // ==================== Merge Operations ====================

    @TruffleBoundary
    public static MezzeMap merge(MezzeMap map1, MezzeMap map2) {
        LinkedHashMap<Object, Object> result = new LinkedHashMap<>(map1.getMap());
        result.putAll(map2.getMap());
        return new MezzeMap(result);
    }

    @TruffleBoundary
    public static MezzeMap mergeWith(MezzeMap map1, MezzeMap map2, MezzeFunction merger,
                                                                            com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        LinkedHashMap<Object, Object> result = new LinkedHashMap<>(map1.getMap());
        for (Map.Entry<Object, Object> entry : map2.getMap().entrySet()) {
            Object key = entry.getKey();
            Object newValue = entry.getValue();
            if (result.containsKey(key)) {
                Object oldValue = result.get(key);
                // merge_with callback: { l: 'v, r: 'v } -> 'v
                newValue = callNode.call(merger.getCallTarget(),
                    prepareArgsLR(merger, oldValue, newValue));
            }
            result.put(key, newValue);
        }
        return new MezzeMap(result);
    }

    // ==================== Equality ====================

    @TruffleBoundary
    public static boolean equals(MezzeMap map1, MezzeMap map2) {
        return map1.getMap().equals(map2.getMap());
    }

    // ==================== Helper Methods ====================

    private static Object[] prepareArgs(MezzeFunction fn, Object arg) {
        Object[] closureValues = fn.getClosureValues();
        Object[] args = new Object[closureValues.length + 1];
        System.arraycopy(closureValues, 0, args, 0, closureValues.length);
        args[closureValues.length] = MezzeRecord.fromFields(
            new String[]{"x"}, new Object[]{arg}
        );
        return args;
    }

    private static Object[] prepareArgsTwo(MezzeFunction fn, Object arg1, Object arg2) {
        Object[] closureValues = fn.getClosureValues();
        Object[] args = new Object[closureValues.length + 1];
        System.arraycopy(closureValues, 0, args, 0, closureValues.length);
        args[closureValues.length] = MezzeRecord.fromFields(
            new String[]{"acc", "x"}, new Object[]{arg1, arg2}
        );
        return args;
    }

    // Helper for map_values: { val: 'v } -> 'w
    private static Object[] prepareArgsVal(MezzeFunction fn, Object val) {
        Object[] closureValues = fn.getClosureValues();
        Object[] args = new Object[closureValues.length + 1];
        System.arraycopy(closureValues, 0, args, 0, closureValues.length);
        args[closureValues.length] = MezzeRecord.fromFields(
            new String[]{"val"}, new Object[]{val}
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
            new String[]{"acc", "k", "v"}, new Object[]{acc, k, v}
        );
        return args;
    }

    // Helper for merge_with: { l: 'v, r: 'v } -> 'v
    private static Object[] prepareArgsLR(MezzeFunction fn, Object l, Object r) {
        Object[] closureValues = fn.getClosureValues();
        Object[] args = new Object[closureValues.length + 1];
        System.arraycopy(closureValues, 0, args, 0, closureValues.length);
        args[closureValues.length] = MezzeRecord.fromFields(
            new String[]{"l", "r"}, new Object[]{l, r}
        );
        return args;
    }

    // ==================== Predicate Operations ====================

    @TruffleBoundary
    public static boolean any(MezzeMap map, MezzeFunction predicate,
                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        for (Map.Entry<Object, Object> entry : map.getMap().entrySet()) {
            MezzeRecord kvRecord = MezzeRecord.fromFields(
                new String[]{"k", "v"},
                new Object[]{entry.getKey(), entry.getValue()}
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
        for (Map.Entry<Object, Object> entry : map.getMap().entrySet()) {
            MezzeRecord kvRecord = MezzeRecord.fromFields(
                new String[]{"k", "v"},
                new Object[]{entry.getKey(), entry.getValue()}
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
        for (Map.Entry<Object, Object> entry : map.getMap().entrySet()) {
            MezzeRecord kvRecord = MezzeRecord.fromFields(
                new String[]{"k", "v"},
                new Object[]{entry.getKey(), entry.getValue()}
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
        for (Map.Entry<Object, Object> entry : map.getMap().entrySet()) {
            MezzeRecord kvRecord = MezzeRecord.fromFields(
                new String[]{"k", "v"},
                new Object[]{entry.getKey(), entry.getValue()}
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
        return map.getMap().containsValue(value);
    }

    // ==================== Update ====================

    @TruffleBoundary
    public static MezzeMap update(MezzeMap map, Object key, MezzeFunction fn,
                                  com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeVariant existing = map.get(key);
        Object newValue = callNode.call(fn.getCallTarget(), prepareArgsVal(fn, existing));
        return map.insert(key, newValue);
    }

    // ==================== Get Or Default ====================

    @TruffleBoundary
    public static Object getOrDefault(MezzeMap map, Object key, Object defaultValue) {
        MezzeVariant result = map.get(key);
        if (result.isNone()) {
            return defaultValue;
        }
        return result.getPayloadValue();
    }

    // ==================== To Seq ====================

    @TruffleBoundary
    public static MezzeSeq toSeq(MezzeMap map) {
        return MezzeSeq.fromArray(map.entries());
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
        return empty().insert(key, value);
    }

    // ==================== Additional Operations ====================

    @TruffleBoundary
    public static MezzeRecord partition(MezzeMap map, MezzeFunction predicate,
                                        com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        java.util.LinkedHashMap<Object, Object> passMap = new java.util.LinkedHashMap<>();
        java.util.LinkedHashMap<Object, Object> failMap = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<Object, Object> entry : map.getMap().entrySet()) {
            Object result = callNode.call(predicate.getCallTarget(),
                new Object[]{MezzeRecord.fromFields(
                    new String[]{"k", "v"},
                    new Object[]{entry.getKey(), entry.getValue()})});
            if (result instanceof Boolean && (Boolean) result) {
                passMap.put(entry.getKey(), entry.getValue());
            } else {
                failMap.put(entry.getKey(), entry.getValue());
            }
        }
        return MezzeRecord.fromFields(
            new String[]{"pass", "fail"},
            new Object[]{new MezzeMap(passMap), new MezzeMap(failMap)}
        );
    }

    // ==================== Task #21: Min/Max Operations ====================

    @TruffleBoundary
    public static MezzeVariant minKey(MezzeMap map) {
        if (map.isEmpty()) return MezzeVariant.none();
        Object minKey = null;
        for (Object key : map.getMap().keySet()) {
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
        for (Object key : map.getMap().keySet()) {
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
        for (Object val : map.getMap().values()) {
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
        for (Object val : map.getMap().values()) {
            if (maxVal == null || compare(val, maxVal) > 0) {
                maxVal = val;
            }
        }
        return MezzeVariant.some(maxVal);
    }

    @TruffleBoundary
    public static MezzeMap invert(MezzeMap map) {
        LinkedHashMap<Object, Object> result = new LinkedHashMap<>();
        for (Map.Entry<Object, Object> entry : map.getMap().entrySet()) {
            result.put(entry.getValue(), entry.getKey());
        }
        return new MezzeMap(result);
    }

    // ==================== Task #22: Key/Value Operations ====================

    @TruffleBoundary
    public static MezzeMap selectKeys(MezzeMap map, Object[] keys) {
        LinkedHashMap<Object, Object> result = new LinkedHashMap<>();
        for (Object key : keys) {
            if (map.containsKey(key)) {
                result.put(key, map.getMap().get(key));
            }
        }
        return new MezzeMap(result);
    }

    @TruffleBoundary
    public static MezzeMap rejectKeys(MezzeMap map, Object[] keys) {
        LinkedHashMap<Object, Object> result = new LinkedHashMap<>(map.getMap());
        for (Object key : keys) {
            result.remove(key);
        }
        return new MezzeMap(result);
    }

    @TruffleBoundary
    public static MezzeVariant reduce(MezzeMap map, MezzeFunction f,
                                      com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (map.isEmpty()) {
            return MezzeVariant.none();
        }
        java.util.Iterator<Map.Entry<Object, Object>> iter = map.getMap().entrySet().iterator();
        Map.Entry<Object, Object> first = iter.next();
        MezzeRecord acc = MezzeRecord.fromFields(
            new String[]{"k", "v"},
            new Object[]{first.getKey(), first.getValue()}
        );
        while (iter.hasNext()) {
            Map.Entry<Object, Object> entry = iter.next();
            MezzeRecord current = MezzeRecord.fromFields(
                new String[]{"k", "v"},
                new Object[]{entry.getKey(), entry.getValue()}
            );
            Object result = callNode.call(f.getCallTarget(),
                new Object[]{MezzeRecord.fromFields(
                    new String[]{"acc", "entry"},
                    new Object[]{acc, current}
                )});
            acc = (MezzeRecord) result;
        }
        return MezzeVariant.some(acc);
    }

    @TruffleBoundary
    public static MezzeMap groupByValue(MezzeMap map) {
        java.util.LinkedHashMap<Object, java.util.ArrayList<Object>> groups = new java.util.LinkedHashMap<>();
        for (Map.Entry<Object, Object> entry : map.getMap().entrySet()) {
            groups.computeIfAbsent(entry.getValue(), k -> new java.util.ArrayList<>())
                  .add(entry.getKey());
        }
        LinkedHashMap<Object, Object> result = new LinkedHashMap<>();
        for (Map.Entry<Object, java.util.ArrayList<Object>> entry : groups.entrySet()) {
            result.put(entry.getKey(), MezzeList.fromArray(entry.getValue().toArray()));
        }
        return new MezzeMap(result);
    }

    // ==================== Task #29: Key Operations ====================

    @TruffleBoundary
    public static MezzeMap mapKeys(MezzeMap map, MezzeFunction f,
                                   com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        LinkedHashMap<Object, Object> result = new LinkedHashMap<>();
        for (Map.Entry<Object, Object> entry : map.getMap().entrySet()) {
            Object newKey = callNode.call(f.getCallTarget(), prepareArgsVal(f, entry.getKey()));
            result.put(newKey, entry.getValue());
        }
        return new MezzeMap(result);
    }

    @TruffleBoundary
    public static MezzeMap filterKeys(MezzeMap map, MezzeFunction predicate,
                                      com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        LinkedHashMap<Object, Object> result = new LinkedHashMap<>();
        for (Map.Entry<Object, Object> entry : map.getMap().entrySet()) {
            Object res = callNode.call(predicate.getCallTarget(), prepareArgsVal(predicate, entry.getKey()));
            if (res instanceof Boolean && (Boolean) res) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return new MezzeMap(result);
    }

    @TruffleBoundary
    public static MezzeMap filterValues(MezzeMap map, MezzeFunction predicate,
                                        com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        LinkedHashMap<Object, Object> result = new LinkedHashMap<>();
        for (Map.Entry<Object, Object> entry : map.getMap().entrySet()) {
            Object res = callNode.call(predicate.getCallTarget(), prepareArgsVal(predicate, entry.getValue()));
            if (res instanceof Boolean && (Boolean) res) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return new MezzeMap(result);
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
        LinkedHashMap<Object, Object> result = new LinkedHashMap<>();
        for (Map.Entry<Object, Object> entry : m1.getMap().entrySet()) {
            Object key = entry.getKey();
            if (m2.getMap().containsKey(key)) {
                result.put(key, MezzeRecord.fromFields(
                    new String[]{"first", "second"},
                    new Object[]{entry.getValue(), m2.getMap().get(key)}
                ));
            }
        }
        return new MezzeMap(result);
    }

    /**
     * Compute the difference of two maps: keys in m1 but not in m2.
     * Returns entries from m1 where key is not present in m2.
     */
    @TruffleBoundary
    public static MezzeMap diffMaps(MezzeMap m1, MezzeMap m2) {
        LinkedHashMap<Object, Object> result = new LinkedHashMap<>();
        for (Map.Entry<Object, Object> entry : m1.getMap().entrySet()) {
            if (!m2.getMap().containsKey(entry.getKey())) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return new MezzeMap(result);
    }

    /**
     * Compute the intersection of two maps: keys present in both.
     * Takes values from m1 for shared keys.
     */
    @TruffleBoundary
    public static MezzeMap intersectMaps(MezzeMap m1, MezzeMap m2) {
        LinkedHashMap<Object, Object> result = new LinkedHashMap<>();
        for (Map.Entry<Object, Object> entry : m1.getMap().entrySet()) {
            if (m2.getMap().containsKey(entry.getKey())) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return new MezzeMap(result);
    }

    /**
     * Symmetric difference: keys in either map but not both.
     * Entries from m1 for keys only in m1, entries from m2 for keys only in m2.
     */
    @TruffleBoundary
    public static MezzeMap symmetricDiffMaps(MezzeMap m1, MezzeMap m2) {
        LinkedHashMap<Object, Object> result = new LinkedHashMap<>();
        for (Map.Entry<Object, Object> entry : m1.getMap().entrySet()) {
            if (!m2.getMap().containsKey(entry.getKey())) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        for (Map.Entry<Object, Object> entry : m2.getMap().entrySet()) {
            if (!m1.getMap().containsKey(entry.getKey())) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return new MezzeMap(result);
    }

    // ==================== List/SortedMap Conversions ====================

    /**
     * Convert Map to List of {k, v} records.
     */
    @TruffleBoundary
    public static MezzeList toList(MezzeMap map) {
        Object[] entries = new Object[map.size()];
        int i = 0;
        for (Map.Entry<Object, Object> entry : map.getMap().entrySet()) {
            entries[i++] = MezzeRecord.fromFields(
                new String[]{"k", "v"},
                new Object[]{entry.getKey(), entry.getValue()}
            );
        }
        return MezzeList.fromArray(entries);
    }

    /**
     * Convert List of {k, v} records to Map.
     */
    @TruffleBoundary
    public static MezzeMap fromList(MezzeList list) {
        LinkedHashMap<Object, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < list.size(); i++) {
            Object elem = list.get(i);
            if (elem instanceof MezzeRecord rec) {
                Object k = rec.getField("k");
                Object v = rec.getField("v");
                result.put(k, v);
            }
        }
        return new MezzeMap(result);
    }

    /**
     * FlatMap over map entries.
     * Applies function to each {k, v} entry, expecting a Map result, and merges all results.
     */
    @TruffleBoundary
    public static MezzeMap flatMap(MezzeMap map, MezzeFunction f,
                                   com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        LinkedHashMap<Object, Object> result = new LinkedHashMap<>();
        for (Map.Entry<Object, Object> entry : map.getMap().entrySet()) {
            MezzeRecord kvRecord = MezzeRecord.fromFields(
                new String[]{"k", "v"},
                new Object[]{entry.getKey(), entry.getValue()}
            );
            Object mapped = callNode.call(f.getCallTarget(), prepareArgsKV(f, kvRecord));
            if (mapped instanceof MezzeMap innerMap) {
                result.putAll(innerMap.getMap());
            }
        }
        return new MezzeMap(result);
    }

    /**
     * Convert Map to SortedMap.
     */
    @TruffleBoundary
    public static SortedMapNatives.MezzeSortedMap toSortedMap(MezzeMap map) {
        return SortedMapNatives.fromMap(map);
    }
}
