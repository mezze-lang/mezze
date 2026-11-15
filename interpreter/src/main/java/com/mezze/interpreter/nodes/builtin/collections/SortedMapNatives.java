package com.mezze.interpreter.nodes.builtin.collections;

import com.mezze.datastructures.map.MezzeMap;
import com.mezze.datastructures.list.MezzeList;
import com.mezze.datastructures.seq.MezzeSeq;
import com.mezze.datastructures.map.EvidenceMapStorage;
import com.mezze.datastructures.set.ElementHashing;
import com.mezze.datastructures.sorted.MezzeSortedMap;
import com.mezze.interpreter.values.*;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

/**
 * Native SortedMap operations - ULTRA PERFORMANT.
 *
 * SortedMap is represented as MezzeSortedMap from the datastructures module.
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
    /**
     * The key at a position in sort order — the probe the guest-side binary
     * search in `std::collections::sorted_map` drives. See
     * {@link MezzeSortedMap#keyAt}: the search moved out of host code so that
     * the comparison is a guest call Graal can inline instead of a callback it
     * cannot.
     */
    public static Object keyAt(MezzeSortedMap map, Object index) {
        return map.keyAt((int) (long) (Long) index);
    }

    /** The value at a position in sort order. See {@link #keyAt}. */
    public static Object valueAt(MezzeSortedMap map, Object index) {
        return map.valueAt((int) (long) (Long) index);
    }

    /**
     * Replace the value at a position the GUEST search already found, and
     * insert at a position it already computed. Neither takes a comparator,
     * because neither compares: the index is the whole input.
     *
     * <p>`insert` used to search here, which meant the comparison ran in host
     * code and called back into guest code once per comparison — ~30 ns each,
     * ~300 ns a lookup, against ~15 ns for the entire guest-side search. That
     * is the same cost `get` and `contains_key` stopped paying when their
     * search moved out; these two are what let `insert` stop paying it too.
     *
     * <p>The guest encodes the search result the way a Java binary search
     * does: a non-negative index means found, and {@code -(insertPos + 1)}
     * means absent with the position that keeps the array sorted. The guest
     * decides which of these two to call.
     */
    @TruffleBoundary
    public static MezzeSortedMap setAt(MezzeSortedMap map, Object index, Object value) {
        return map.setAt((int) (long) (Long) index, value);
    }

    /** See {@link #setAt}. */
    @TruffleBoundary
    public static MezzeSortedMap insertAt(MezzeSortedMap map, Object index, Object key, Object value) {
        return map.insertAt((int) (long) (Long) index, key, value);
    }

    /** See {@link #setAt}. The guest calls this only when the key is present,
     *  so an absent key never reaches a native at all. */
    @TruffleBoundary
    public static MezzeSortedMap removeAt(MezzeSortedMap map, Object index) {
        return map.removeAt((int) (long) (Long) index);
    }

    public static MezzeVariant get(MezzeSortedMap map, Object key, MezzeFunction cmpFn) {
        map = map.withComparator(ElementComparator.of(cmpFn));
        Object value = map.getObject(key);
        if (value == null && !map.containsKeyObject(key)) {
            return MezzeVariant.none();
        }
        return MezzeVariant.some(value);
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

    /**
     * `to_map` under the KEY's own hash and equality.
     *
     * <p>This leaves a comparator-ordered container for a HASHED one, so the
     * sorted map's `Ord` stops being the right question and the key's
     * `Eq`/`Hash` becomes it. The plain {@link #toMap} below builds through a
     * bare {@code MezzeMap.empty()}, which buckets structurally — a sorted map
     * holding two keys an author's finer `Ord` separates must collapse to one
     * when that author's `Eq` calls them equal, and it did not.
     * `fromMap` one method down already took its comparator.
     */
    @TruffleBoundary
    public static MezzeMap toMapWithEvidence(MezzeSortedMap sortedMap, MezzeFunction hashFn,
                                             MezzeFunction eqFn) {
        ElementHashing hashing = ClosureHashing.of(hashFn, eqFn);
        if (hashing == null) return toMap(sortedMap);
        MezzeMap result = MezzeMap.fromStorage(
            EvidenceMapStorage.fromEntries(new Object[0], new Object[0], hashing));
        for (Object entryObj : sortedMap.rawEntries()) {
            MezzeSortedMap.RawEntry entry = (MezzeSortedMap.RawEntry) entryObj;
            result = result.put(entry.key(), entry.value());
        }
        return result;
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

    /**
     * Sum the values, with no guest callback in the loop.
     *
     * <p>WHY THIS EXISTS. `FoldToSumRewriter` turns a summing fold into the
     * collection's own `sum`, and a SortedMap had none for it to aim at — the
     * same gap `nel` had before `nel::sum` was added. `bench/cases/collfold`
     * at n=2e5: `sorted_map::fold` costs 13 202 ps/elem where
     * `sorted_set::fold` costs 446, and the two are the same sorted array
     * walked the same way. The difference is entirely the rewrite: summing the
     * values in Java with the callback skipped measures **633 ps/elem**, so
     * 95% of that 13 202 is the per-element guest call and 5% is the walk.
     *
     * <p>Only the VALUES. A key sum is a different question and
     * `FoldToSumRewriter` refuses to rewrite a folder that reads the key.
     */
    public static Object sumValues(MezzeSortedMap map) {
        long sum = 0;
        int n = map.size();
        for (int i = 0; i < n; i++) {
            Object v = map.valueAt(i);
            if (v instanceof Long l) sum += l;
            else if (v instanceof Number num) sum += num.longValue();
            else throw new IllegalStateException(
                    "sorted_map::sum_values over a non-numeric value: "
                    + (v == null ? "null" : v.getClass().getSimpleName()));
        }
        return sum;
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
        // BOTH HALVES INHERIT THE SOURCE'S ORDER. The key type is unchanged, so
        // rebuilding through a bare `empty()` dropped the ordering the map was
        // partitioned from — the failure `SortedSetNatives.slice`'s doc names
        // for `take`/`drop`. See known-bugs.md BUG 45.
        // `comparator()`, the EFFECTIVE one, is right here where it was wrong
        // for the set: `MezzeSortedMap` has no instanceof fast paths, so there
        // is no natural-order flag to preserve and nothing to forfeit.
        var order = map.comparator();
        MezzeSortedMap passMap = MezzeSortedMap.empty(order);
        MezzeSortedMap failMap = MezzeSortedMap.empty(order);
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

    /**
     * A contiguous run of the sort order, which is every slice
     * {@code std::collections::sorted_map} offers: {@code take}, {@code drop},
     * {@code head_map}, {@code tail_map}, {@code range} and {@code sub_map} are
     * each a pair of indices the GUEST computes from its own search, handed
     * here.
     *
     * <p>{@code take} and {@code drop} used to rebuild their result one
     * {@code putObject} at a time — O(n^2), with a comparator search per entry.
     * That made them the last place a slice could reach {@code (Comparable) a}
     * for a key the host cannot order, so a sorted map keyed by a record threw
     * from `take` however it was reached.
     */
    @TruffleBoundary
    public static MezzeSortedMap slice(MezzeSortedMap map, Object from, Object to) {
        return map.slice((int) (long) (Long) from, (int) (long) (Long) to);
    }

    // ==================== Additional Higher-Order Functions ====================

    public static MezzeSortedMap mapKeys(MezzeSortedMap map, MezzeFunction f,
                                         MezzeFunction cmpFn,
                                         com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        // ORDERED BY THE TARGET'S `Ord`, supplied by the caller. These produce
        // keys of a DIFFERENT type, so the source map's comparator genuinely
        // does not apply — but `null` meant natural ordering, which silently
        // overrode a hand-written `Ord`: `map_keys` into a descending newtype
        // gave `1, 3, 5` and a `get` that found one key of three. The comment
        // this replaces called it "primitive-only (tracked with the
        // sorted-container gap)"; the gap is this. `cmpFn` is null when the
        // compiler proved the new key directly comparable.
        java.util.Comparator<Object> order = ElementComparator.of(cmpFn);
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
    /** See {@link #mapKeys}: the new keys are the old VALUES, so the order is
     *  theirs and comes from the caller. */
    public static MezzeSortedMap invert(MezzeSortedMap map, MezzeFunction cmpFn) {
        java.util.Comparator<Object> order = ElementComparator.of(cmpFn);
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
