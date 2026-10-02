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
    public static MezzeSortedMap singleton(Object key, Object value, MezzeFunction cmpFn) {
        return MezzeSortedMap.<Comparable, Object>empty(ElementComparator.of(cmpFn)).putObject(key, value);
    }

    // ==================== Basic Operations ====================

    @TruffleBoundary
    public static MezzeSortedMap insert(MezzeSortedMap map, Object key, Object value, MezzeFunction cmpFn) {
        map = map.withComparator(ElementComparator.of(cmpFn));
        return map.putObject(key, value);
    }

    @TruffleBoundary
    public static MezzeVariant get(MezzeSortedMap map, Object key, MezzeFunction cmpFn) {
        map = map.withComparator(ElementComparator.of(cmpFn));
        Object value = map.getObject(key);
        if (value == null && !map.containsKeyObject(key)) {
            return MezzeVariant.none();
        }
        return MezzeVariant.some(value);
    }

    @TruffleBoundary
    public static MezzeSortedMap remove(MezzeSortedMap map, Object key, MezzeFunction cmpFn) {
        map = map.withComparator(ElementComparator.of(cmpFn));
        return map.removeObject(key);
    }

    @TruffleBoundary
    public static boolean containsKey(MezzeSortedMap map, Object key, MezzeFunction cmpFn) {
        map = map.withComparator(ElementComparator.of(cmpFn));
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

    /**
     * Equality over both halves of an entry: the KEYS by the map's own ordered
     * lookup, and the VALUES by the VALUE'S OWN `eq`, threaded in.
     *
     * `a.equals(b)` compared the runtime representation of the values, which is
     * right only when their equality is the derived structural one — so a value
     * type with a hand-written `Eq` was ignored and two equal maps answered false,
     * silently, while `<` on the same two answered correctly. A key present in one
     * and absent from the other ends it: `getObject` returns null only for an
     * absent key, a Mezze value never being null.
     */
    @TruffleBoundary
    public static boolean equals(MezzeSortedMap a, MezzeSortedMap b, MezzeFunction eqFn,
                                 com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (a.size() != b.size()) return false;
        var ctx = ScalarCall.prepare2(eqFn, "a", "b");
        for (MezzeSortedMap.Entry entry : (Iterable<MezzeSortedMap.Entry>) (Object) a) {
            Object other = b.getObject(entry.key());
            if (other == null) return false;
            Object result = ctx.call2(callNode, entry.value(), other);
            if (!(result instanceof Boolean bool) || !bool) {
                return false;
            }
        }
        return true;
    }

    /**
     * Lexicographic comparison of two sorted maps: KEYS by the map's own ordering,
     * which it carries from construction, and VALUES by the VALUE'S OWN `cmp`,
     * threaded in.
     *
     * The values used to be compared with `order` — the KEY's comparator. That is
     * wrong twice: the values are not ordered by the key's `Ord`, and the value's
     * own `Ord` was never consulted, so a value type with a hand-written ordering
     * was silently ignored. The `impl Ord for SortedMap 'k 'v` above it has
     * declared `with Ord 'k, Ord 'v` the whole time; only `'k` was ever used.
     * Same shape as BUG 21, and as the `equals` directly above this.
     */
    @TruffleBoundary
    public static long compareMaps(MezzeSortedMap map1, MezzeSortedMap map2,
                                   MezzeFunction cmpFn,
                                   com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var order = map1.comparator();
        Object[] keys1 = map1.getKeysInternal();
        Object[] keys2 = map2.getKeysInternal();
        Object[] vals1 = map1.getValuesInternal();
        Object[] vals2 = map2.getValuesInternal();
        // map.size(), NOT keys.length. `getKeysInternal` hands back the BACKING
        // ARRAY, whose capacity runs ahead of the entry count — a one-entry map
        // returns an array of four. Walking `.length` compared three pairs of
        // nulls past the end, and the length compare below asked which CAPACITY
        // was larger, so two maps of different sizes could answer equal.
        int size1 = map1.size();
        int size2 = map2.size();
        int minLen = Math.min(size1, size2);
        for (int i = 0; i < minLen; i++) {
            int cmp = order.compare(keys1[i], keys2[i]);
            if (cmp != 0) {
                return cmp < 0 ? -1L : 1L;
            }
            long v = callCmp(cmpFn, vals1[i], vals2[i], callNode);
            if (v != 0L) {
                return v < 0 ? -1L : 1L;
            }
        }
        return Integer.compare(size1, size2);
    }

    /**
     * Call a `{ a, b } -> Int` comparator through the RECORD path, closures first.
     *
     * Deliberately not `ScalarCall.prepare2`, whose scalar bypass passes arguments
     * positionally to an inner CallTarget: a comparator body that dispatches TWICE
     * (`if a < b … else if b < a …`, which is every `Ord` comparator the stdlib
     * writes) then received a captured dictionary where a value belonged, and the
     * Int `<` native was handed a function. `SeqNatives.cmpInt` has always used
     * this shape and handles the same comparator correctly.
     */
    @TruffleBoundary
    private static long callCmp(MezzeFunction cmpFn, Object a, Object b,
                                com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        Object[] closures = cmpFn.getClosureValues();
        Object[] args = new Object[closures.length + 1];
        System.arraycopy(closures, 0, args, 0, closures.length);
        args[closures.length] = MezzeRecord.wrapAB(a, b);
        Object r = callNode.call(cmpFn.getCallTarget(), args);
        if (!(r instanceof Long l)) {
            throw new RuntimeException("sorted-map value comparator returned "
                + (r == null ? "null" : r.getClass().getName() + " (" + r + ")")
                + " rather than an Int; closures=" + closures.length);
        }
        return l;
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
    public static MezzeSortedMap fromMap(MezzeMap map, MezzeFunction cmpFn) {
        MezzeSortedMap result = MezzeSortedMap.<Comparable, Object>empty(ElementComparator.of(cmpFn));
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

    public static MezzeSortedMap filter(MezzeSortedMap map, MezzeFunction predicate,
                                        com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var order = map.comparator();
        var ctx = ScalarCall.prepare2(predicate, "k", "v");
        MezzeSortedMap result = MezzeSortedMap.empty(order);
        for (MezzeSortedMap.RawEntry entry : (Iterable<MezzeSortedMap.RawEntry>) map.rawEntries()) {
            Object res = ctx.call2(callNode, entry.key(), entry.value());
            if (res instanceof Boolean && (Boolean) res) {
                result = result.putObject(entry.key(), entry.value());
            }
        }
        return result;
    }

    public static Object fold(MezzeSortedMap map, Object init, MezzeFunction f,
                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare3(f, "acc", "k", "v");
        Object acc = init;
        for (MezzeSortedMap.RawEntry entry : (Iterable<MezzeSortedMap.RawEntry>) map.rawEntries()) {
            acc = ctx.call3(callNode, acc, entry.key(), entry.value());
        }
        return acc;
    }

    public static MezzeSortedMap mapValues(MezzeSortedMap map, MezzeFunction f,
                                           com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var order = map.comparator();
        var ctx = ScalarCall.prepare1(f, "val");
        MezzeSortedMap result = MezzeSortedMap.empty(order);
        for (MezzeSortedMap.RawEntry entry : (Iterable<MezzeSortedMap.RawEntry>) map.rawEntries()) {
            Object mapped = ctx.call1(callNode, entry.value());
            result = result.putObject(entry.key(), mapped);
        }
        return result;
    }

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


    @TruffleBoundary
    public static Object getOrDefault(MezzeSortedMap map, Object key, Object defaultValue) {
        Object value = map.getObject(key);
        if (value == null && !map.containsKeyObject(key)) {
            return defaultValue;
        }
        return value;
    }

    public static MezzeSortedMap update(MezzeSortedMap map, Object key, MezzeFunction f,
                                        com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare1(f, "val");
        MezzeVariant current = get(map, key, null);
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
        var order = map.comparator();
        // Use the value class's range method which returns [lo, hi)
        // We need [lo, hi] inclusive, so we filter manually
        MezzeSortedMap result = MezzeSortedMap.empty(order);
        for (MezzeSortedMap.RawEntry entry : (Iterable<MezzeSortedMap.RawEntry>) map.rawEntries()) {
            Object key = entry.key();
            if (order.compare(key, loKey) >= 0 && order.compare(key, hiKey) <= 0) {
                result = result.putObject(key, entry.value());
            }
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeVariant floorKey(MezzeSortedMap map, Object key) {
        var order = map.comparator();
        // Find largest key <= given key
        Object result = null;
        for (Object k : map.keys()) {
            if (order.compare(k, key) <= 0) {
                if (result == null || order.compare(k, result) > 0) {
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
        var order = map.comparator();
        // Find smallest key >= given key
        Object result = null;
        for (Object k : map.keys()) {
            if (order.compare(k, key) >= 0) {
                if (result == null || order.compare(k, result) < 0) {
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
        var order = map.comparator();
        MezzeSortedMap result = MezzeSortedMap.empty(order);
        for (MezzeSortedMap.RawEntry entry : (Iterable<MezzeSortedMap.RawEntry>) map.rawEntries()) {
            if (order.compare(entry.key(), toKey) < 0) {
                result = result.putObject(entry.key(), entry.value());
            }
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeSortedMap tailMap(MezzeSortedMap map, Object fromKey) {
        var order = map.comparator();
        MezzeSortedMap result = MezzeSortedMap.empty(order);
        for (MezzeSortedMap.RawEntry entry : (Iterable<MezzeSortedMap.RawEntry>) map.rawEntries()) {
            if (order.compare(entry.key(), fromKey) >= 0) {
                result = result.putObject(entry.key(), entry.value());
            }
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeSortedMap subMap(MezzeSortedMap map, Object loKey, Object hiKey) {
        var order = map.comparator();
        MezzeSortedMap result = MezzeSortedMap.empty(order);
        for (MezzeSortedMap.RawEntry entry : (Iterable<MezzeSortedMap.RawEntry>) map.rawEntries()) {
            Object key = entry.key();
            // subMap is [lo, hi) - lo inclusive, hi exclusive
            if (order.compare(key, loKey) >= 0 && order.compare(key, hiKey) < 0) {
                result = result.putObject(key, entry.value());
            }
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeSortedMap take(MezzeSortedMap map, long n) {
        var order = map.comparator();
        MezzeSortedMap result = MezzeSortedMap.empty(order);
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
        var order = map.comparator();
        MezzeSortedMap result = MezzeSortedMap.empty(order);
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

    public static MezzeSortedMap mapKeys(MezzeSortedMap map, MezzeFunction f,
                                         com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        // mapKeys / invert produce keys of a DIFFERENT type, so the source map's
        // comparator does not apply; these fall back to natural ordering and are
        // therefore still primitive-only (tracked with the sorted-container gap).
        java.util.Comparator<Object> order = null;
        var ctx = ScalarCall.prepare1(f, "val");
        MezzeSortedMap result = MezzeSortedMap.empty(order);
        for (MezzeSortedMap.RawEntry entry : (Iterable<MezzeSortedMap.RawEntry>) map.rawEntries()) {
            Object newKey = ctx.call1(callNode, entry.key());
            result = result.putObject(newKey, entry.value());
        }
        return result;
    }

    public static MezzeSortedMap filterKeys(MezzeSortedMap map, MezzeFunction predicate,
                                            com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var order = map.comparator();
        var ctx = ScalarCall.prepare1(predicate, "val");
        MezzeSortedMap result = MezzeSortedMap.empty(order);
        for (MezzeSortedMap.RawEntry entry : (Iterable<MezzeSortedMap.RawEntry>) map.rawEntries()) {
            Object res = ctx.call1(callNode, entry.key());
            if (res instanceof Boolean && (Boolean) res) {
                result = result.putObject(entry.key(), entry.value());
            }
        }
        return result;
    }

    public static MezzeSortedMap filterValues(MezzeSortedMap map, MezzeFunction predicate,
                                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var order = map.comparator();
        var ctx = ScalarCall.prepare1(predicate, "val");
        MezzeSortedMap result = MezzeSortedMap.empty(order);
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
        // mapKeys / invert produce keys of a DIFFERENT type, so the source map's
        // comparator does not apply; these fall back to natural ordering and are
        // therefore still primitive-only (tracked with the sorted-container gap).
        java.util.Comparator<Object> order = null;
        MezzeSortedMap result = MezzeSortedMap.empty(order);
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
    public static MezzeSortedMap fromList(MezzeList list, MezzeFunction cmpFn) {
        MezzeSortedMap result = MezzeSortedMap.<Comparable, Object>empty(ElementComparator.of(cmpFn));
        for (Object item : list) {
            if (item instanceof MezzeRecord) {
                MezzeRecord record = (MezzeRecord) item;
                // Entries are `{ k, v }` — the stdlib signature (`from_list: { xs:
                // List { k, v } }`) and what `entries()` / the codec walker produce.
                // Reading `key`/`value` here returned null keys (cmp saw null < null).
                Object key = record.getField(MapNatives.ENTRY_KEY);
                Object value = record.getField(MapNatives.ENTRY_VALUE);
                result = result.putObject(key, value);
            }
        }
        return result;
    }

    // The private `compare(Object, Object)` that used to live here is DELETED. It
    // ordered anything non-primitive by `System.identityHashCode`, so records
    // sorted by JVM identity — wrong, and unstable across runs. Nothing called it:
    // key ordering comes from the map's own comparator, captured at construction,
    // and value ordering from the `cmp` threaded into `compareMaps`.
}
