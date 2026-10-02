package com.mezze.interpreter.nodes.builtin.collections;

import com.mezze.datastructures.map.EvidenceMapStorage;
import com.mezze.datastructures.map.MapStorage;
import com.mezze.datastructures.map.MezzeMap;
import com.mezze.datastructures.set.ElementHashing;
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

    // ==================== Map-Entry Field Names ====================
    // The canonical `{ k, v }` entry-record shape every map-like native (and the
    // codec walker) produces and consumes. One spelling, referenced — never a
    // scattered "k"/"v" literal (a stray "key"/"value" silently read null).

    /** Entry-record key field: `{ k, v }`. */
    public static final String ENTRY_KEY = "k";
    /** Entry-record value field: `{ k, v }`. */
    public static final String ENTRY_VALUE = "v";

    // ==================== Cached Field Name Arrays ====================
    // Used by non-HOF result record construction (entries, toSeq, toList, etc.)

    private static final String[] FIELD_K_V = {ENTRY_KEY, ENTRY_VALUE};
    private static final String[] FIELD_PASS_FAIL = {"pass", "fail"};
    private static final String[] FIELD_FST_SND = {"fst", "snd"};

    // ==================== Factory Operations ====================

    public static MezzeMap empty() {
        return MezzeMap.empty();
    }

    public static MezzeMap fromKeys(Object[] keys, MezzeFunction valueFn,
                                    com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare1(valueFn, "val");
        MezzeMap map = MezzeMap.empty();
        for (Object key : keys) {
            Object value = ctx.call1(callNode, key);
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

    // ============ Operations carrying the KEY's own Eq/Hash ============
    //
    // A map buckets by the key's runtime representation, which IS the derived
    // structural equality — right for every key whose `Eq` the compiler wrote, and
    // wrong for one the author wrote. `newtype P = Int` comparing mod 10 has
    // `P.(1) == P.(11)`, and a newtype is erased, so the structural tiers keep two
    // entries where the key type says there is one.
    //
    // Installed at the first operation that HAS a key: an empty map has nothing to
    // mis-bucket. The structural entry points stay the default, so a `Map Str V`
    // keeps its specialised storage. Which form a call site uses is the compiler's
    // decision, taken once from the key type.

    /** [map] re-bucketed under [hashing], or itself when it already is. */
    private static MezzeMap withHashing(MezzeMap map, ElementHashing hashing) {
        if (hashing == null) return map;
        MapStorage storage = map.storage();
        if (storage instanceof EvidenceMapStorage e && e.hashing() == hashing) return map;
        return MezzeMap.fromStorage(
            EvidenceMapStorage.fromEntries(map.storage().keys(), map.storage().values(), hashing));
    }

    @TruffleBoundary
    public static MezzeMap insertWithEvidence(MezzeMap map, Object key, Object value,
                                             MezzeFunction hashFn, MezzeFunction eqFn) {
        return withHashing(map, ClosureHashing.of(hashFn, eqFn)).put(key, value);
    }

    @TruffleBoundary
    public static MezzeVariant getWithEvidence(MezzeMap map, Object key,
                                              MezzeFunction hashFn, MezzeFunction eqFn) {
        return get(withHashing(map, ClosureHashing.of(hashFn, eqFn)), key);
    }

    @TruffleBoundary
    public static boolean containsKeyWithEvidence(MezzeMap map, Object key,
                                                  MezzeFunction hashFn, MezzeFunction eqFn) {
        return withHashing(map, ClosureHashing.of(hashFn, eqFn)).containsKey(key);
    }

    @TruffleBoundary
    public static MezzeMap removeWithEvidence(MezzeMap map, Object key,
                                              MezzeFunction hashFn, MezzeFunction eqFn) {
        return withHashing(map, ClosureHashing.of(hashFn, eqFn)).remove(key);
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
                FIELD_K_V,
                new Object[]{entry.key(), entry.value()}
            );
        }
        return MezzeList.of(result);
    }

    // ==================== Transform Operations ====================

    public static MezzeMap filter(MezzeMap map, MezzeFunction predicate,
                                  com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare2(predicate, "k", "v");
        MezzeMap result = MezzeMap.empty();
        for (MezzeMap.Entry entry : (Iterable<MezzeMap.Entry>) (Object) map) {
            Object res = ctx.call2(callNode, entry.key(), entry.value());
            if (res instanceof Boolean && (Boolean) res) {
                result = result.put(entry.key(), entry.value());
            }
        }
        return result;
    }

    public static MezzeMap mapValues(MezzeMap map, MezzeFunction mapper,
                                     com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare1(mapper, "val");
        MezzeMap result = MezzeMap.empty();
        for (MezzeMap.Entry entry : (Iterable<MezzeMap.Entry>) (Object) map) {
            Object newValue = ctx.call1(callNode, entry.value());
            result = result.put(entry.key(), newValue);
        }
        return result;
    }

    public static Object fold(MezzeMap map, Object init, MezzeFunction folder,
                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare3(folder, "acc", "k", "v");
        Object acc = init;
        for (MezzeMap.Entry entry : (Iterable<MezzeMap.Entry>) (Object) map) {
            acc = ctx.call3(callNode, acc, entry.key(), entry.value());
        }
        return acc;
    }

    // ==================== Merge Operations ====================

    @TruffleBoundary
    public static MezzeMap merge(MezzeMap map1, MezzeMap map2) {
        return map1.putAll(map2);
    }

    public static MezzeMap mergeWith(MezzeMap map1, MezzeMap map2, MezzeFunction merger,
                                     com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare2(merger, "l", "r");
        MezzeMap result = map1;
        for (MezzeMap.Entry entry : (Iterable<MezzeMap.Entry>) (Object) map2) {
            Object key = entry.key();
            Object newValue = entry.value();
            if (result.containsKey(key)) {
                Object oldValue = result.get(key);
                newValue = ctx.call2(callNode, oldValue, newValue);
            }
            result = result.put(key, newValue);
        }
        return result;
    }

    // ==================== Equality ====================

    /**
     * Equality over both halves of an entry: the KEYS by the map's own lookup —
     * which already buckets by the key type's instance — and the VALUES by the
     * VALUE'S OWN `eq`, threaded in.
     *
     * `map1.equals(map2)` compared the runtime representation of the values, which
     * is right only when their equality is the derived structural one. A newtype
     * whose `Eq` compares mod 10 has `P.(1) == P.(11)`, and a newtype erases to its
     * payload, so two maps holding those values answered false where the value type
     * says true — silently, with no crash.
     *
     * A key present in one map and absent from the other ends it: `get` returns
     * null only for an absent key, because a stored null is not reachable through
     * this type (a Mezze value is never null).
     */
    @TruffleBoundary
    public static boolean equals(MezzeMap map1, MezzeMap map2, MezzeFunction eqFn,
                                 com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (map1.size() != map2.size()) return false;
        var ctx = ScalarCall.prepare2(eqFn, "a", "b");
        for (MezzeMap.Entry entry : (Iterable<MezzeMap.Entry>) (Object) map1) {
            Object other = map2.get(entry.key());
            if (other == null) return false;
            Object result = ctx.call2(callNode, entry.value(), other);
            if (!(result instanceof Boolean bool) || !bool) {
                return false;
            }
        }
        return true;
    }

    // ==================== Predicate Operations ====================

    public static boolean any(MezzeMap map, MezzeFunction predicate,
                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare2(predicate, "k", "v");
        for (MezzeMap.Entry entry : (Iterable<MezzeMap.Entry>) (Object) map) {
            Object result = ctx.call2(callNode, entry.key(), entry.value());
            if (result instanceof Boolean && (Boolean) result) {
                return true;
            }
        }
        return false;
    }

    public static boolean all(MezzeMap map, MezzeFunction predicate,
                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare2(predicate, "k", "v");
        for (MezzeMap.Entry entry : (Iterable<MezzeMap.Entry>) (Object) map) {
            Object result = ctx.call2(callNode, entry.key(), entry.value());
            if (!(result instanceof Boolean) || !(Boolean) result) {
                return false;
            }
        }
        return true;
    }

    public static boolean none(MezzeMap map, MezzeFunction predicate,
                               com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        return !any(map, predicate, callNode);
    }

    public static MezzeVariant find(MezzeMap map, MezzeFunction predicate,
                                    com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare2(predicate, "k", "v");
        for (MezzeMap.Entry entry : (Iterable<MezzeMap.Entry>) (Object) map) {
            Object result = ctx.call2(callNode, entry.key(), entry.value());
            if (result instanceof Boolean && (Boolean) result) {
                return MezzeVariant.some(MezzeRecord.fromFields(
                    FIELD_K_V,
                    new Object[]{entry.key(), entry.value()}
                ));
            }
        }
        return MezzeVariant.none();
    }

    public static long count(MezzeMap map, MezzeFunction predicate,
                             com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare2(predicate, "k", "v");
        long cnt = 0;
        for (MezzeMap.Entry entry : (Iterable<MezzeMap.Entry>) (Object) map) {
            Object result = ctx.call2(callNode, entry.key(), entry.value());
            if (result instanceof Boolean && (Boolean) result) {
                cnt++;
            }
        }
        return cnt;
    }

    // ==================== Contains Value ====================

    @TruffleBoundary
    public static boolean containsValue(MezzeMap map, Object value, MezzeFunction eqFn, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var eq = ScalarCall.prepare2(eqFn, "a", "b");
        for (Object v : map.values()) {
            if (eq.callEq(callNode, v, value)) {
                return true;
            }
        }
        return false;
    }

    // ==================== Update ====================

    public static MezzeMap update(MezzeMap map, Object key, MezzeFunction fn,
                                  com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare1(fn, "val");
        MezzeVariant existing = get(map, key);
        Object newValue = ctx.call1(callNode, existing);
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

    /**
     * The inverse of {@link #toSeq}: rebuild a map from a seq of `{k, v}` records.
     *
     * Exists because the codec walker had no way back. `decode_map` hands the
     * walker its entries and the walker decoded each side, but nothing turned the
     * entry seq into a `Map` — so decoding into a `Map` returned a value whose
     * runtime shape was not its declared type, for EVERY format. JSON surfaced
     * that as a decode `Err`; the polyglot codec got one rung further and failed
     * with "Expected MezzeMap for field 'm'".
     *
     * Structural bucketing is the right default, exactly as it is for
     * {@code __native_map_insert}: when the key's `Eq` is the compiler's derived
     * one, the map's own structural comparison IS that instance. A key type that
     * wrote its own `Eq` needs the evidence-carrying form instead, which is why
     * this name is in TypedASTCompiler's `evidenceNativeForm` table alongside
     * insert/get/remove.
     */
    @TruffleBoundary
    public static MezzeMap fromSeq(MezzeSeq entries) {
        MezzeMap result = MezzeMap.empty();
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i) instanceof MezzeRecord rec) {
                result = result.put(rec.getField("k"), rec.getField("v"));
            }
        }
        return result;
    }

    /**
     * {@link #fromSeq} using the key type's OWN hash/eq rather than the map's
     * structural bucketing — the form the compiler substitutes when the key's
     * instance was hand-written. Mirrors {@code insertWithEvidence}.
     */
    @TruffleBoundary
    public static MezzeMap fromSeqWithEvidence(
        MezzeSeq entries,
        MezzeFunction hash,
        MezzeFunction eq
    ) {
        // Install the key's instance ONCE and fill, rather than re-wrapping per
        // entry: the hashing belongs to the map, not to the individual put.
        MezzeMap result = withHashing(MezzeMap.empty(), ClosureHashing.of(hash, eq));
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i) instanceof MezzeRecord rec) {
                result = result.put(rec.getField("k"), rec.getField("v"));
            }
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeSeq toSeq(MezzeMap map) {
        com.mezze.datastructures.map.MezzeMap<Object, Object> delegate = map;
        Object[] result = new Object[delegate.size()];
        int i = 0;
        for (MezzeMap.Entry entry : delegate) {
            result[i++] = MezzeRecord.fromFields(
                FIELD_K_V,
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

    public static MezzeRecord partition(MezzeMap map, MezzeFunction predicate,
                                        com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare2(predicate, "k", "v");
        MezzeMap passMap = MezzeMap.empty();
        MezzeMap failMap = MezzeMap.empty();
        for (MezzeMap.Entry entry : (Iterable<MezzeMap.Entry>) (Object) map) {
            Object result = ctx.call2(callNode, entry.key(), entry.value());
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
    public static MezzeVariant minKey(MezzeMap map, MezzeFunction cmpFn, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (map.isEmpty()) return MezzeVariant.none();
        var cmp = ScalarCall.prepare2(cmpFn, "a", "b");
        Object minKey = null;
        for (Object key : map.keys()) {
            if (minKey == null || cmp.callCmp(callNode, key, minKey) < 0) {
                minKey = key;
            }
        }
        return MezzeVariant.some(minKey);
    }

    @TruffleBoundary
    public static MezzeVariant maxKey(MezzeMap map, MezzeFunction cmpFn, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (map.isEmpty()) return MezzeVariant.none();
        var cmp = ScalarCall.prepare2(cmpFn, "a", "b");
        Object maxKey = null;
        for (Object key : map.keys()) {
            if (maxKey == null || cmp.callCmp(callNode, key, maxKey) > 0) {
                maxKey = key;
            }
        }
        return MezzeVariant.some(maxKey);
    }

    @TruffleBoundary
    public static MezzeVariant minValue(MezzeMap map, MezzeFunction cmpFn, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (map.isEmpty()) return MezzeVariant.none();
        var cmp = ScalarCall.prepare2(cmpFn, "a", "b");
        Object minVal = null;
        for (Object val : map.values()) {
            if (minVal == null || cmp.callCmp(callNode, val, minVal) < 0) {
                minVal = val;
            }
        }
        return MezzeVariant.some(minVal);
    }

    @TruffleBoundary
    public static MezzeVariant maxValue(MezzeMap map, MezzeFunction cmpFn, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (map.isEmpty()) return MezzeVariant.none();
        var cmp = ScalarCall.prepare2(cmpFn, "a", "b");
        Object maxVal = null;
        for (Object val : map.values()) {
            if (maxVal == null || cmp.callCmp(callNode, val, maxVal) > 0) {
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

    public static MezzeVariant reduce(MezzeMap map, MezzeFunction f,
                                      com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (map.isEmpty()) {
            return MezzeVariant.none();
        }
        var ctx = ScalarCall.prepare2(f, "acc", "entry");
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
            Object result = ctx.call2(callNode, acc, current);
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

    public static MezzeMap mapKeys(MezzeMap map, MezzeFunction f,
                                   com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare1(f, "val");
        MezzeMap result = MezzeMap.empty();
        for (MezzeMap.Entry entry : (Iterable<MezzeMap.Entry>) (Object) map) {
            Object newKey = ctx.call1(callNode, entry.key());
            result = result.put(newKey, entry.value());
        }
        return result;
    }

    public static MezzeMap filterKeys(MezzeMap map, MezzeFunction predicate,
                                      com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare1(predicate, "val");
        MezzeMap result = MezzeMap.empty();
        for (MezzeMap.Entry entry : (Iterable<MezzeMap.Entry>) (Object) map) {
            Object res = ctx.call1(callNode, entry.key());
            if (res instanceof Boolean && (Boolean) res) {
                result = result.put(entry.key(), entry.value());
            }
        }
        return result;
    }

    public static MezzeMap filterValues(MezzeMap map, MezzeFunction predicate,
                                        com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare1(predicate, "val");
        MezzeMap result = MezzeMap.empty();
        for (MezzeMap.Entry entry : (Iterable<MezzeMap.Entry>) (Object) map) {
            Object res = ctx.call1(callNode, entry.value());
            if (res instanceof Boolean && (Boolean) res) {
                result = result.put(entry.key(), entry.value());
            }
        }
        return result;
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
    public static MezzeMap flatMap(MezzeMap map, MezzeFunction f,
                                   com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare2(f, "k", "v");
        MezzeMap result = MezzeMap.empty();
        for (MezzeMap.Entry entry : (Iterable<MezzeMap.Entry>) (Object) map) {
            Object mapped = ctx.call2(callNode, entry.key(), entry.value());
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
    public static MezzeSortedMap toSortedMap(MezzeMap map, MezzeFunction cmpFn) {
        return SortedMapNatives.fromMap(map, cmpFn);
    }
}
