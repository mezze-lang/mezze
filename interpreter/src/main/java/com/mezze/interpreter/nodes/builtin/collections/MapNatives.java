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
    private static final String[] FIELD_FST_SND =
        {com.mezze.language.truffleir.NativeHofNames.PAIR_FST, com.mezze.language.truffleir.NativeHofNames.PAIR_SND};

    // ==================== Factory Operations ====================

    public static MezzeMap empty() {
        return MezzeMap.empty();
    }

    public static MezzeMap fromKeys(Object[] keys, MezzeFunction valueFn,
                                    com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare1(valueFn, "val");
        Object[] outV = new Object[keys.length];
        for (int i = 0; i < keys.length; i++) {
            outV[i] = ctx.call1(callNode, keys[i]);
        }
        return MezzeMap.fromEntries(keys, outV, keys.length);
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
        Object[] result = new Object[map.size()];
        int[] i = {0};
        map.walkEntries((k, v) -> {
            result[i[0]++] = MezzeRecord.fromFields(FIELD_K_V, new Object[]{k, v});
        });
        return MezzeList.of(result);
    }

    // ==================== Transform Operations ====================

    /**
     * EVERY OPERATION THAT REBUILDS A MAP COLLECTS AND BUILDS ONCE.
     *
     * <p>`result = result.put(k, v)` per entry is a persistent put per entry,
     * and a persistent put allocates a node at every level of the path it
     * touches — right for an update, wasted for a rebuild that discards the
     * base on the very next entry. {@link MezzeMap#fromEntries} takes the
     * entries already in hand and builds the trie in place instead, which
     * MEASURED 3.2x faster than the same keys put one at a time (3.4x for
     * String keys), against an in-place `java.util.HashMap` ceiling of 6.5x.
     *
     * <p>A key already present still takes the later value, so the contents
     * are what the repeated puts gave.
     *
     * <p>`filter` keeping EVERYTHING returns the map itself, because an
     * immutable map that would be rebuilt entry for entry is already the
     * answer. A bench row that keeps every entry therefore measures that
     * shortcut rather than the build — `mapsurface` row 22 keeps half.
     */
    public static MezzeMap filter(MezzeMap map, MezzeFunction predicate,
                                  com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare2(predicate, "k", "v");
        int size = map.size();
        Object[] outK = new Object[size];
        Object[] outV = new Object[size];
        int[] n = {0};
        map.walkEntries((k, v) -> {
            Object res = ctx.call2(callNode, k, v);
            if (res instanceof Boolean && (Boolean) res) {
                outK[n[0]] = k;
                outV[n[0]] = v;
                n[0]++;
            }
        });
        if (n[0] == size) {
            return map;
        }
        return MezzeMap.fromEntries(outK, outV, n[0]);
    }

    public static MezzeMap mapValues(MezzeMap map, MezzeFunction mapper,
                                     com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        // THE KEYS DO NOT CHANGE, so they are not touched: no key is copied
        // into an array, no key is hashed, and the trie's shape is reused.
        // This used to walk the entries into two arrays and call
        // `fromEntries`, which pushes every key back through a builder —
        // 29 302 ps/element against 10 925 for the structure copy, measured on
        // 1000 string keys, and that ratio is a lower bound because the copy
        // was measured through reflection.
        //
        // The guest calls stay HERE rather than moving inside a
        // `MezzeMap.mapValues(fn)`, which is `@TruffleBoundary`: a boundary
        // around a HOF's loop means the loop never compiles and the callback is
        // never inlined into it, which is the point `BytesNatives.fold`'s
        // comment makes. So the mapping runs in this frame and only the
        // structure copy crosses.
        //
        // `withValues` wants the values in `values()` order, which is what
        // mapping over `values()` itself produces.
        var ctx = ScalarCall.prepare1(mapper, "val");
        Object[] vs = map.values();
        Object[] outV = new Object[vs.length];
        for (int i = 0; i < vs.length; i++) {
            outV[i] = ctx.call1(callNode, vs[i]);
        }
        return map.withValues(outV);
    }

    /**
     * Sum the integer VALUES, without a guest callback and without an Entry.
     *
     * A map fold is the most expensive walk in the collection ledger, and it
     * pays THREE costs where the others pay one. `MezzeMap`'s iterator does
     * `new Entry<>(keys[index], values[index])` per step, so there is an
     * allocation per element on top of the three-argument guest callback — and
     * the keys are read and boxed into it even when the folder ignores them, as
     * a value sum does.
     *
     * None of that is necessary: the map stores keys and values in PARALLEL
     * ARRAYS, so a value sum is a counted loop over one of them.
     *
     * Named `sum_values` and not `sum` because a map has two things to sum and
     * the ambiguity would be the whole bug. Reads Long/Integer and ignores
     * anything else, matching the sibling sums; the stdlib signature is
     * `{ m: Map 'k Int } -> Int`, and `FoldToSumRewriter` additionally requires
     * the folder's value parameter to be a LONG slot before redirecting here.
     *
     * One `@TruffleBoundary` for the whole loop, not one per element.
     */
    @TruffleBoundary
    public static long sumValues(MezzeMap map) {
        // `MapStorage.forEach` is the storage's own walk and its contract is
        // "without creating intermediate arrays" — so no `values()` copy and no
        // Entry. The accumulator is boxed in a one-element array because a
        // BiConsumer cannot assign a local; that is ONE allocation per fold,
        // against the Entry-per-element this replaces.
        long[] total = { 0L };
        map.storage().forEach((k, v) -> {
            if (v instanceof Long l) total[0] += l;
            else if (v instanceof Integer n) total[0] += n;
        });
        return total[0];
    }

    public static Object fold(MezzeMap map, Object init, MezzeFunction folder,
                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare3(folder, "acc", "k", "v");
        Object[] acc = {init};
        map.walkEntries((k, v) -> {
            acc[0] = ctx.call3(callNode, acc[0], k, v);
        });
        return acc[0];
    }

    // ==================== Merge Operations ====================

    @TruffleBoundary
    public static MezzeMap merge(MezzeMap map1, MezzeMap map2) {
        return map1.putAll(map2);
    }

    public static MezzeMap mergeWith(MezzeMap map1, MezzeMap map2, MezzeFunction merger,
                                     com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare2(merger, "l", "r");
        // Still a put per entry of map2, on purpose: this starts from an
        // EXISTING map1 and adds map2's entries, so a bulk rebuild would walk
        // the whole base to save however few puts map2 asks for.
        MezzeMap[] result = {map1};
        map2.walkEntries((key, value) -> {
            Object newValue = value;
            if (result[0].containsKey(key)) {
                newValue = ctx.call2(callNode, result[0].get(key), value);
            }
            result[0] = result[0].put(key, newValue);
        });
        return result[0];
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
        // The first unequal pair is the answer, so this stops there.
        return map1.walkEntriesUntil((k, v) -> {
            Object other = map2.get(k);
            if (other == null) return false;
            Object result = ctx.call2(callNode, v, other);
            return result instanceof Boolean bool && bool;
        });
    }

    // ==================== Predicate Operations ====================

    /**
     * ONE WALK, NO INPUT ARRAYS, and the second correction to this walk.
     *
     * <p>FIRST it built an `Entry` per element — `MezzeMap`'s iterator does
     * `new Entry<>(keys[index], values[index])` for every step — so a walk over
     * a 1 000-key map allocated a thousand two-field objects before any
     * predicate ran. That became `keys()` and `values()` indexed in step, which
     * is where the reasoning stopped too early: it removed the Entry but kept
     * the trie being WALKED TWICE to fill two n-element arrays.
     *
     * <p>MEASURED on a 1 000-entry long-keyed map: the two arrays cost 7.16 ns
     * an entry, the Entry iterator 7.25, and a single `forEach` walk 1.97. So
     * NEITHER earlier shape was the walk — both were the arrays, and the Entry
     * was never the main cost it was taken for. 3.54x at n=4 000, 2.56x for
     * String keys.
     *
     * <p>`any`, `all`, `none` and `find` answer before the end, so they take
     * `walkEntriesUntil`, whose predicate returning false stops the walk where
     * the array loop used to `return` out of it.
     */
    public static boolean any(MezzeMap map, MezzeFunction predicate,
                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare2(predicate, "k", "v");
        // false from the predicate STOPS the walk, so a hit in the first node
        // does not pay for the rest of the trie.
        return !map.walkEntriesUntil((k, v) -> {
            Object result = ctx.call2(callNode, k, v);
            return !(result instanceof Boolean && (Boolean) result);
        });
    }

    public static boolean all(MezzeMap map, MezzeFunction predicate,
                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare2(predicate, "k", "v");
        return map.walkEntriesUntil((k, v) -> {
            Object result = ctx.call2(callNode, k, v);
            return result instanceof Boolean && (Boolean) result;
        });
    }

    public static boolean none(MezzeMap map, MezzeFunction predicate,
                               com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        return !any(map, predicate, callNode);
    }

    public static MezzeVariant find(MezzeMap map, MezzeFunction predicate,
                                    com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare2(predicate, "k", "v");
        Object[] hit = new Object[2];
        boolean ranToEnd = map.walkEntriesUntil((k, v) -> {
            Object result = ctx.call2(callNode, k, v);
            if (result instanceof Boolean && (Boolean) result) {
                hit[0] = k;
                hit[1] = v;
                return false;
            }
            return true;
        });
        if (ranToEnd) {
            return MezzeVariant.none();
        }
        return MezzeVariant.some(MezzeRecord.fromFields(FIELD_K_V, hit));
    }

    public static long count(MezzeMap map, MezzeFunction predicate,
                             com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare2(predicate, "k", "v");
        long[] cnt = {0L};
        map.walkEntries((k, v) -> {
            Object result = ctx.call2(callNode, k, v);
            if (result instanceof Boolean && (Boolean) result) {
                cnt[0]++;
            }
        });
        return cnt[0];
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
        Object[] outK = new Object[entries.size()];
        Object[] outV = new Object[entries.size()];
        int n = 0;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i) instanceof MezzeRecord rec) {
                outK[n] = rec.getField(ENTRY_KEY);
                outV[n] = rec.getField(ENTRY_VALUE);
                n++;
            }
        }
        return MezzeMap.fromEntries(outK, outV, n);
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
        Object[] result = new Object[map.size()];
        int[] i = {0};
        map.walkEntries((k, v) -> {
            result[i[0]++] = MezzeRecord.fromFields(FIELD_K_V, new Object[]{k, v});
        });
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
        // BOTH corrections at once: this walked the Entry iterator AND built
        // its two results with a persistent put per entry, which the bulk-build
        // sweep missed because it returns a record rather than a map.
        int size = map.size();
        Object[] passK = new Object[size];
        Object[] passV = new Object[size];
        Object[] failK = new Object[size];
        Object[] failV = new Object[size];
        int[] np = {0};
        int[] nf = {0};
        map.walkEntries((k, v) -> {
            Object result = ctx.call2(callNode, k, v);
            if (result instanceof Boolean && (Boolean) result) {
                passK[np[0]] = k;
                passV[np[0]] = v;
                np[0]++;
            } else {
                failK[nf[0]] = k;
                failV[nf[0]] = v;
                nf[0]++;
            }
        });
        return MezzeRecord.fromFields(
            FIELD_PASS_FAIL,
            new Object[]{
                MezzeMap.fromEntries(passK, passV, np[0]),
                MezzeMap.fromEntries(failK, failV, nf[0]),
            }
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
        // Values become keys, so two entries may collide: the later wins, as
        // the puts did.
        return MezzeMap.fromEntries(map.values(), map.keys(), map.size());
    }

    // ==================== Task #22: Key/Value Operations ====================

    @TruffleBoundary
    public static MezzeMap selectKeys(MezzeMap map, Object[] keys) {
        Object[] outK = new Object[keys.length];
        Object[] outV = new Object[keys.length];
        int n = 0;
        for (Object key : keys) {
            if (map.containsKey(key)) {
                outK[n] = key;
                outV[n] = map.get(key);
                n++;
            }
        }
        return MezzeMap.fromEntries(outK, outV, n);
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
        map.walkEntries((k, v) -> {
            groups.computeIfAbsent(v, g -> new java.util.ArrayList<>()).add(k);
        });
        Object[] outK = new Object[groups.size()];
        Object[] outV = new Object[groups.size()];
        int n = 0;
        for (java.util.Map.Entry<Object, java.util.ArrayList<Object>> entry : groups.entrySet()) {
            outK[n] = entry.getKey();
            outV[n] = MezzeList.of(entry.getValue().toArray());
            n++;
        }
        return MezzeMap.fromEntries(outK, outV, n);
    }

    // ==================== Task #29: Key Operations ====================

    public static MezzeMap mapKeys(MezzeMap map, MezzeFunction f,
                                   com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare1(f, "val");
        int size = map.size();
        Object[] outK = new Object[size];
        Object[] outV = new Object[size];
        int[] n = {0};
        map.walkEntries((k, v) -> {
            outK[n[0]] = ctx.call1(callNode, k);
            outV[n[0]] = v;
            n[0]++;
        });
        // Two keys may map to one: the later entry wins, as the puts did.
        return MezzeMap.fromEntries(outK, outV, size);
    }

    public static MezzeMap filterKeys(MezzeMap map, MezzeFunction predicate,
                                      com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare1(predicate, "val");
        int size = map.size();
        Object[] outK = new Object[size];
        Object[] outV = new Object[size];
        int[] n = {0};
        map.walkEntries((k, v) -> {
            Object res = ctx.call1(callNode, k);
            if (res instanceof Boolean && (Boolean) res) {
                outK[n[0]] = k;
                outV[n[0]] = v;
                n[0]++;
            }
        });
        if (n[0] == size) {
            return map;
        }
        return MezzeMap.fromEntries(outK, outV, n[0]);
    }

    public static MezzeMap filterValues(MezzeMap map, MezzeFunction predicate,
                                        com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare1(predicate, "val");
        int size = map.size();
        Object[] outK = new Object[size];
        Object[] outV = new Object[size];
        int[] n = {0};
        map.walkEntries((k, v) -> {
            Object res = ctx.call1(callNode, v);
            if (res instanceof Boolean && (Boolean) res) {
                outK[n[0]] = k;
                outV[n[0]] = v;
                n[0]++;
            }
        });
        if (n[0] == size) {
            return map;
        }
        return MezzeMap.fromEntries(outK, outV, n[0]);
    }


    // ==================== Task #37: Multi-Map Operations ====================

    /**
     * Zip two maps together. For keys present in both, creates pairs of values.
     * Returns Map of {k, {first: v1, second: v2}} for keys present in both maps.
     */
    @TruffleBoundary
    public static MezzeMap zipMaps(MezzeMap m1, MezzeMap m2) {
        Object[] ks = m1.keys();
        Object[] vs = m1.values();
        Object[] outK = new Object[ks.length];
        Object[] outV = new Object[ks.length];
        int n = 0;
        for (int i = 0; i < ks.length; i++) {
            if (m2.containsKey(ks[i])) {
                outK[n] = ks[i];
                outV[n] = MezzeRecord.fromFields(
                    FIELD_FST_SND,
                    new Object[]{vs[i], m2.get(ks[i])}
                );
                n++;
            }
        }
        return MezzeMap.fromEntries(outK, outV, n);
    }

    /**
     * Compute the difference of two maps: keys in m1 but not in m2.
     * Returns entries from m1 where key is not present in m2.
     */
    @TruffleBoundary
    public static MezzeMap diffMaps(MezzeMap m1, MezzeMap m2) {
        Object[] ks = m1.keys();
        Object[] vs = m1.values();
        Object[] outK = new Object[ks.length];
        Object[] outV = new Object[ks.length];
        int n = 0;
        for (int i = 0; i < ks.length; i++) {
            if (!m2.containsKey(ks[i])) {
                outK[n] = ks[i];
                outV[n] = vs[i];
                n++;
            }
        }
        if (n == ks.length) {
            return m1;
        }
        return MezzeMap.fromEntries(outK, outV, n);
    }

    /**
     * Compute the intersection of two maps: keys present in both.
     * Takes values from m1 for shared keys.
     */
    @TruffleBoundary
    public static MezzeMap intersectMaps(MezzeMap m1, MezzeMap m2) {
        Object[] ks = m1.keys();
        Object[] vs = m1.values();
        Object[] outK = new Object[ks.length];
        Object[] outV = new Object[ks.length];
        int n = 0;
        for (int i = 0; i < ks.length; i++) {
            if (m2.containsKey(ks[i])) {
                outK[n] = ks[i];
                outV[n] = vs[i];
                n++;
            }
        }
        if (n == ks.length) {
            return m1;
        }
        return MezzeMap.fromEntries(outK, outV, n);
    }

    /**
     * Symmetric difference: keys in either map but not both.
     * Entries from m1 for keys only in m1, entries from m2 for keys only in m2.
     */
    @TruffleBoundary
    public static MezzeMap symmetricDiffMaps(MezzeMap m1, MezzeMap m2) {
        Object[] k1 = m1.keys();
        Object[] v1 = m1.values();
        Object[] k2 = m2.keys();
        Object[] v2 = m2.values();
        Object[] outK = new Object[k1.length + k2.length];
        Object[] outV = new Object[k1.length + k2.length];
        int n = 0;
        for (int i = 0; i < k1.length; i++) {
            if (!m2.containsKey(k1[i])) {
                outK[n] = k1[i];
                outV[n] = v1[i];
                n++;
            }
        }
        for (int i = 0; i < k2.length; i++) {
            if (!m1.containsKey(k2[i])) {
                outK[n] = k2[i];
                outV[n] = v2[i];
                n++;
            }
        }
        return MezzeMap.fromEntries(outK, outV, n);
    }

    // ==================== List/SortedMap Conversions ====================

    /**
     * Convert Map to List of {k, v} records.
     */
    @TruffleBoundary
    public static MezzeList toList(MezzeMap map) {
        Object[] entries = new Object[map.size()];
        int[] i = {0};
        map.walkEntries((k, v) -> {
            entries[i[0]++] = MezzeRecord.fromFields(FIELD_K_V, new Object[]{k, v});
        });
        return MezzeList.of(entries);
    }

    /**
     * Convert List of {k, v} records to Map.
     */
    @TruffleBoundary
    public static MezzeMap fromList(MezzeList list) {
        Object[] outK = new Object[list.size()];
        Object[] outV = new Object[list.size()];
        int n = 0;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) instanceof MezzeRecord rec) {
                outK[n] = rec.getField(ENTRY_KEY);
                outV[n] = rec.getField(ENTRY_VALUE);
                n++;
            }
        }
        return MezzeMap.fromEntries(outK, outV, n);
    }

    /**
     * FlatMap over map entries.
     * Applies function to each {k, v} entry, expecting a Map result, and merges all results.
     */
    public static MezzeMap flatMap(MezzeMap map, MezzeFunction f,
                                   com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare2(f, "k", "v");
        MezzeMap[] result = {MezzeMap.empty()};
        map.walkEntries((k, v) -> {
            Object mapped = ctx.call2(callNode, k, v);
            if (mapped instanceof MezzeMap innerMap) {
                result[0] = result[0].putAll(innerMap);
            }
        });
        return result[0];
    }

    /**
     * Convert Map to SortedMap.
     */
    @TruffleBoundary
    public static MezzeSortedMap toSortedMap(MezzeMap map, MezzeFunction cmpFn) {
        return SortedMapNatives.fromMap(map, cmpFn);
    }
}
