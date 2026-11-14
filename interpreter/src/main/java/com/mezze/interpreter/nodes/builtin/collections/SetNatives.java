package com.mezze.interpreter.nodes.builtin.collections;

import com.mezze.datastructures.list.MezzeList;
import com.mezze.datastructures.seq.MezzeSeq;
import com.mezze.datastructures.set.ElementHashing;
import com.mezze.datastructures.set.EvidenceSetStorage;
import com.mezze.datastructures.set.MezzeSet;
import com.mezze.datastructures.set.SetStorage;
import com.mezze.datastructures.sorted.MezzeSortedSet;
import com.mezze.interpreter.values.*;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

/**
 * Native Set operations - ULTRA PERFORMANT.
 *
 * Now uses com.mezze.interpreter.values.MezzeSet which is backed by
 * the optimized com.mezze.datastructures.set.MezzeSet with tiered storage.
 *
 * All operations are immutable (return new sets).
 */
public final class SetNatives {

    private SetNatives() {}

    // ============ Operations carrying the element's own Eq/Hash ============
    //
    // A hash container buckets by the element's runtime representation, which is
    // exactly right when the element's `Eq` is the compiler's derived structural
    // one — and wrong when the element wrote its own. `newtype P = Int` comparing
    // mod 10 has `P.(1) == P.(11)`, and a newtype is erased, so the structural
    // tiers see the payloads `1` and `11`, bucket them apart, and keep both.
    //
    // These entry points take that element's `hash` and `eq` closures and install
    // them ON THE SET, so every later insert, lookup and removal asks the element's
    // instance. Installed at the first operation that HAS an element: an empty set
    // has nothing to mis-bucket, so there is nothing to convert until then.
    //
    // The structural entry points above are untouched and stay the default — a set
    // whose elements derive their `Eq` keeps the specialised Long/String tiers and
    // their iteration order. Which form a call site uses is the COMPILER's decision,
    // taken once from the element type.

    /** [set] re-bucketed under [hashing], or itself when it already is. */
    private static MezzeSet withHashing(MezzeSet set, ElementHashing hashing) {
        if (hashing == null) return set;
        SetStorage storage = set.storage();
        if (storage instanceof EvidenceSetStorage e && e.hashing() == hashing) return set;
        return MezzeSet.fromStorage(EvidenceSetStorage.fromElements(set.toArray(), hashing));
    }

    @TruffleBoundary
    public static MezzeSet insertWithEvidence(MezzeSet set, Object element, MezzeFunction hashFn, MezzeFunction eqFn) {
        return withHashing(set, ClosureHashing.of(hashFn, eqFn)).add(element);
    }

    @TruffleBoundary
    public static boolean containsWithEvidence(MezzeSet set, Object element, MezzeFunction hashFn, MezzeFunction eqFn) {
        return withHashing(set, ClosureHashing.of(hashFn, eqFn)).contains(element);
    }

    @TruffleBoundary
    public static MezzeSet removeWithEvidence(MezzeSet set, Object element, MezzeFunction hashFn, MezzeFunction eqFn) {
        return withHashing(set, ClosureHashing.of(hashFn, eqFn)).remove(element);
    }

    @TruffleBoundary
    public static MezzeSet fromArrayWithEvidence(Object[] arr, MezzeFunction hashFn, MezzeFunction eqFn) {
        ElementHashing hashing = ClosureHashing.of(hashFn, eqFn);
        if (hashing == null) return MezzeSet.of(arr);
        return MezzeSet.fromStorage(EvidenceSetStorage.fromElements(arr, hashing));
    }

    // ==================== Factory Operations ====================

    public static MezzeSet empty() {
        return MezzeSet.empty();
    }

    @TruffleBoundary
    public static MezzeSet fromArray(Object[] arr) {
        return MezzeSet.of(arr);
    }

    @TruffleBoundary
    public static MezzeSet fromList(MezzeList list) {
        Object[] out = new Object[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i);
        }
        return MezzeSet.fromElements(out, out.length);
    }

    // ==================== Basic Operations ====================

    public static long size(MezzeSet set) {
        return set.size();
    }

    public static boolean isEmpty(MezzeSet set) {
        return set.isEmpty();
    }

    @TruffleBoundary
    public static boolean contains(MezzeSet set, Object element) {
        return set.contains(element);
    }

    @TruffleBoundary
    public static MezzeSet insert(MezzeSet set, Object element) {
        return set.add(element);
    }

    @TruffleBoundary
    public static MezzeSet remove(MezzeSet set, Object element) {
        return set.remove(element);
    }

    @TruffleBoundary
    public static MezzeSet insertAll(MezzeSet set, Object[] elements) {
        MezzeSet result = set;
        for (Object element : elements) {
            result = result.add(element);
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeSet removeAll(MezzeSet set, Object[] elements) {
        MezzeSet result = set;
        for (Object element : elements) {
            result = result.remove(element);
        }
        return result;
    }

    // ==================== Set Operations ====================

    @TruffleBoundary
    public static MezzeSet union(MezzeSet set1, MezzeSet set2) {
        return set1.union(set2);
    }

    @TruffleBoundary
    public static MezzeSet intersection(MezzeSet set1, MezzeSet set2) {
        return set1.intersect(set2);
    }

    @TruffleBoundary
    public static MezzeSet difference(MezzeSet set1, MezzeSet set2) {
        return set1.difference(set2);
    }

    @TruffleBoundary
    public static boolean isSubset(MezzeSet set1, MezzeSet set2) {
        return set1.isSubsetOf(set2);
    }

    @TruffleBoundary
    public static boolean isSuperset(MezzeSet set1, MezzeSet set2) {
        return set2.isSubsetOf(set1);
    }

    @TruffleBoundary
    public static boolean isDisjoint(MezzeSet set1, MezzeSet set2) {
        // Short-circuit: iterate the smaller set, probe the larger.
        MezzeSet smaller = set1.size() <= set2.size() ? set1 : set2;
        MezzeSet larger  = smaller == set1 ? set2 : set1;
        for (Object elem : smaller) {
            if (larger.contains(elem)) return false;
        }
        return true;
    }

    @TruffleBoundary
    public static boolean equals(MezzeSet set1, MezzeSet set2) {
        return set1.equals(set2);
    }

    // ==================== Transform Operations ====================

    /**
     * EVERY OPERATION THAT REBUILDS A SET COLLECTS AND BUILDS ONCE.
     *
     * <p>`result = result.add(e)` per element is a persistent add per element,
     * and a persistent add allocates a node at every level of the path it
     * touches — right for an update, wasted for a rebuild that discards the
     * base on the very next element. {@link MezzeSet#fromElements} takes the
     * elements already in hand and builds the trie in place instead; the map's
     * identical change measured 3.2x on the build.
     *
     * <p>`filter` keeping EVERYTHING returns the set itself, because an
     * immutable set that would be rebuilt element for element is already the
     * answer.
     */
    public static MezzeSet filter(MezzeSet set, MezzeFunction predicate,
                                  com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare1(predicate, "val");
        int size = set.size();
        Object[] out = new Object[size];
        int[] n = {0};
        set.walkElems(e -> {
            Object res = ctx.call1(callNode, e);
            if (res instanceof Boolean && (Boolean) res) {
                out[n[0]++] = e;
            }
        });
        if (n[0] == size) {
            return set;
        }
        return MezzeSet.fromElements(out, n[0]);
    }

    /**
     * `map` that collapses by the TARGET's equality.
     *
     * <p>The plain {@link #map} rebuilds through the structural `fromElements`,
     * so two results a hand-written `Eq` calls equal stayed apart: mapping the
     * distinct Ints 10 and 11 to a newtype equal BY TENS gave a set of two
     * where the author's `Eq` says one. Its sibling `sorted_set::map` already
     * carries `with Ord 'b` for the same reason — the result's elements are
     * `'b`, so the SOURCE's instance says nothing about them.
     *
     * <p>Reached only when `'b`'s `hash` and `eq` are hand-written; the plain
     * form stays the default, so a set of Int keeps its specialised storage.
     */
    @TruffleBoundary
    public static MezzeSet mapWithEvidence(MezzeSet set, MezzeFunction mapper,
                                           MezzeFunction hashFn, MezzeFunction eqFn,
                                           com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare1(mapper, "val");
        int size = set.size();
        Object[] out = new Object[size];
        int[] n = {0};
        set.walkElems(e -> out[n[0]++] = ctx.call1(callNode, e));
        ElementHashing hashing = ClosureHashing.of(hashFn, eqFn);
        if (hashing == null) return MezzeSet.fromElements(out, size);
        return MezzeSet.fromStorage(EvidenceSetStorage.fromElements(out, hashing));
    }

    public static MezzeSet map(MezzeSet set, MezzeFunction mapper,
                               com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare1(mapper, "val");
        int size = set.size();
        Object[] out = new Object[size];
        int[] n = {0};
        set.walkElems(e -> out[n[0]++] = ctx.call1(callNode, e));
        // Two elements may map to one: they collapse, as the adds did.
        return MezzeSet.fromElements(out, size);
    }

    public static Object fold(MezzeSet set, Object init, MezzeFunction folder,
                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare2(folder, "acc", "x");
        Object acc = init;
        for (Object element : set) {
            acc = ctx.call2(callNode, acc, element);
        }
        return acc;
    }

    // ==================== Conversion ====================

    @TruffleBoundary
    public static Object[] toArray(MezzeSet set) {
        return set.toArray();
    }

    @TruffleBoundary
    public static MezzeList toList(MezzeSet set) {
        return MezzeList.of(set.toArray());
    }

    // ==================== Predicate Operations ====================

    public static boolean any(MezzeSet set, MezzeFunction predicate,
                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare1(predicate, "val");
        for (Object element : set) {
            Object result = ctx.call1(callNode, element);
            if (result instanceof Boolean && (Boolean) result) {
                return true;
            }
        }
        return false;
    }

    public static boolean all(MezzeSet set, MezzeFunction predicate,
                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare1(predicate, "val");
        for (Object element : set) {
            Object result = ctx.call1(callNode, element);
            if (!(result instanceof Boolean) || !(Boolean) result) {
                return false;
            }
        }
        return true;
    }

    public static boolean none(MezzeSet set, MezzeFunction predicate,
                               com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        return !any(set, predicate, callNode);
    }

    public static MezzeVariant find(MezzeSet set, MezzeFunction predicate,
                                    com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare1(predicate, "val");
        for (Object element : set) {
            Object result = ctx.call1(callNode, element);
            if (result instanceof Boolean && (Boolean) result) {
                return MezzeVariant.some(element);
            }
        }
        return MezzeVariant.none();
    }

    public static long count(MezzeSet set, MezzeFunction predicate,
                             com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare1(predicate, "val");
        long cnt = 0;
        for (Object element : set) {
            Object result = ctx.call1(callNode, element);
            if (result instanceof Boolean && (Boolean) result) {
                cnt++;
            }
        }
        return cnt;
    }

    // ==================== ForEach ====================

    public static Object forEach(MezzeSet set, MezzeFunction f,
                                 com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare1(f, "val");
        for (Object element : set) {
            ctx.call1(callNode, element);
        }
        return MezzeRecord.empty();
    }



    @TruffleBoundary
    public static long sum(MezzeSet set) {
        // A set of Ints already stores them in a `long[]`; the iterator and
        // `toArray()` both hand back `Object`, so the generic walk below boxes
        // every element only to unbox it again one line later. MEASURED: that
        // boxing IS the set fold's remaining cost once the guest callback is
        // gone (7 447 ps/elem on `fuseall` kernel 7).
        if (set.storage() instanceof com.mezze.datastructures.set.LongTrieSetStorage ls) {
            return ls.sumLongs();
        }
        long total = 0;
        for (Object elem : set) {
            if (elem instanceof Long l) total += l;
            else if (elem instanceof Integer n) total += n;
        }
        return total;
    }

    @TruffleBoundary
    public static long product(MezzeSet set) {
        if (set.isEmpty()) return 1L;
        long result = 1;
        for (Object elem : set) {
            if (elem instanceof Long l) result *= l;
            else if (elem instanceof Integer n) result *= n;
        }
        return result;
    }

    // ==================== To Seq ====================

    @TruffleBoundary
    public static MezzeSeq toSeq(MezzeSet set) {
        return MezzeSeq.fromArray(set.toArray());
    }

    // ==================== Singleton ====================

    @TruffleBoundary
    public static MezzeSet singleton(Object element) {
        return MezzeSet.of(element);
    }

    // ==================== Additional Operations ====================

    public static MezzeVariant reduce(MezzeSet set, MezzeFunction f,
                                      com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (set.isEmpty()) {
            return MezzeVariant.none();
        }
        var ctx = ScalarCall.prepare2(f, "acc", "x");
        java.util.Iterator<Object> iter = set.iterator();
        Object acc = iter.next();
        while (iter.hasNext()) {
            acc = ctx.call2(callNode, acc, iter.next());
        }
        return MezzeVariant.some(acc);
    }

    public static MezzeVariant minBy(MezzeSet set, MezzeFunction f, MezzeFunction cmpFn,
                                     com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (set.isEmpty()) {
            return MezzeVariant.none();
        }
        var ctx = ScalarCall.prepare1(f, "val");
        var cmp = ScalarCall.prepare2(cmpFn, "a", "b");
        Object minElem = null;
        Object minKey = null;
        for (Object elem : set) {
            Object key = ctx.call1(callNode, elem);
            if (minKey == null || cmp.callCmp(callNode, key, minKey) < 0) {
                minKey = key;
                minElem = elem;
            }
        }
        return MezzeVariant.some(minElem);
    }

    public static MezzeVariant maxBy(MezzeSet set, MezzeFunction f, MezzeFunction cmpFn,
                                     com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (set.isEmpty()) {
            return MezzeVariant.none();
        }
        var ctx = ScalarCall.prepare1(f, "val");
        var cmp = ScalarCall.prepare2(cmpFn, "a", "b");
        Object maxElem = null;
        Object maxKey = null;
        for (Object elem : set) {
            Object key = ctx.call1(callNode, elem);
            if (maxKey == null || cmp.callCmp(callNode, key, maxKey) > 0) {
                maxKey = key;
                maxElem = elem;
            }
        }
        return MezzeVariant.some(maxElem);
    }

    public static MezzeRecord partition(MezzeSet set, MezzeFunction predicate,
                                        com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare1(predicate, "val");
        MezzeSet trueSet = MezzeSet.empty();
        MezzeSet falseSet = MezzeSet.empty();
        for (Object elem : set) {
            Object result = ctx.call1(callNode, elem);
            if (result instanceof Boolean && (Boolean) result) {
                trueSet = trueSet.add(elem);
            } else {
                falseSet = falseSet.add(elem);
            }
        }
        return MezzeRecord.fromFields(
            new String[]{"pass", "fail"},
            new Object[]{trueSet, falseSet}
        );
    }

    @TruffleBoundary
    public static double sumFloat(MezzeSet set) {
        double result = 0.0;
        for (Object elem : set) {
            if (elem instanceof Double) {
                result += (Double) elem;
            } else if (elem instanceof Long) {
                result += (Long) elem;
            }
        }
        return result;
    }

    @TruffleBoundary
    public static double productFloat(MezzeSet set) {
        double result = 1.0;
        for (Object elem : set) {
            if (elem instanceof Double) {
                result *= (Double) elem;
            } else if (elem instanceof Long) {
                result *= (Long) elem;
            }
        }
        return result;
    }


    // ==================== Task #20: Additional Operations ====================

    public static MezzeSet flatMap(MezzeSet set, MezzeFunction f,
                                   com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare1(f, "val");
        MezzeSet result = MezzeSet.empty();
        for (Object elem : set) {
            Object mapped = ctx.call1(callNode, elem);
            if (mapped instanceof MezzeSet innerSet) {
                result = result.union(innerSet);
            }
        }
        return result;
    }

    public static MezzeList groupBy(MezzeSet set, MezzeFunction keyFn,
                                    com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare1(keyFn, "val");
        java.util.LinkedHashMap<Object, java.util.ArrayList<Object>> groups = new java.util.LinkedHashMap<>();
        for (Object elem : set) {
            Object key = ctx.call1(callNode, elem);
            groups.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(elem);
        }
        Object[] result = new Object[groups.size()];
        int idx = 0;
        for (var entry : groups.entrySet()) {
            result[idx++] = MezzeRecord.fromFields(
                new String[]{"key", "values"},
                new Object[]{entry.getKey(), fromArray(entry.getValue().toArray())}
            );
        }
        return MezzeList.of(result);
    }

    /**
     * Elements in exactly one of the two sets.
     *
     * <p>THIS USED TO HAVE ITS OWN WALK AND REBUILD — both sides walked while
     * probing the other, survivors collected, then `fromElements` rehashing
     * every one — and that is why `set::symmetric_difference` did NOT move when
     * `set::union` became 3.6x faster: it never called
     * {@code MezzeSet.symmetricDifference} at all, so that method was dead code
     * for this path. The control not moving when the composition said it must
     * is what exposed it.
     *
     * <p>It delegates now, and the trie pair is answered structurally in one
     * pass by `LongTrieSetStorage.symmetricDifferenceWith`.
     */
    @TruffleBoundary
    public static MezzeSet symmetricDifference(MezzeSet s1, MezzeSet s2) {
        return s1.symmetricDifference(s2);
    }

    // ==================== Task #30: Collect Operations ====================

    /**
     * Apply a function that returns Option to each element and collect only the Some values.
     * Similar to flatMap but specifically for Option-returning functions.
     */
    public static MezzeSet filterMap(MezzeSet set, MezzeFunction f,
                                     com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare1(f, "val");
        Object[] out = new Object[set.size()];
        int[] n = {0};
        set.walkElems(e -> {
            Object mapped = ctx.call1(callNode, e);
            if (mapped instanceof MezzeVariant v && v.isSome()) {
                out[n[0]++] = v.getPayloadFieldByName("val");
            }
        });
        return MezzeSet.fromElements(out, n[0]);
    }

    /**
     * Filter elements and transform them in one pass, returning a Seq.
     * The function returns Option[B] - None filters out, Some(b) includes b.
     */
    public static Object[] collectArray(MezzeSet set, MezzeFunction f,
                                        com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare1(f, "val");
        java.util.ArrayList<Object> result = new java.util.ArrayList<>();
        for (Object elem : set) {
            Object mapped = ctx.call1(callNode, elem);
            if (mapped instanceof MezzeVariant v && v.isSome()) {
                result.add(v.getPayloadFieldByName("val"));
            }
        }
        return result.toArray();
    }

    /**
     * Like filterMap but returns a List for ordered result.
     */
    public static MezzeList collectList(MezzeSet set, MezzeFunction f,
                                        com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare1(f, "val");
        java.util.ArrayList<Object> result = new java.util.ArrayList<>();
        for (Object elem : set) {
            Object mapped = ctx.call1(callNode, elem);
            if (mapped instanceof MezzeVariant v && v.isSome()) {
                result.add(v.getPayloadFieldByName("val"));
            }
        }
        return MezzeList.of(result.toArray());
    }

    // ==================== SortedSet Conversions ====================

    /**
     * Convert Set to SortedSet, ordered by the element's `Ord`.
     *
     * <p>`set::to_sorted_set` used to say "elements must be comparable" in its
     * DOC COMMENT and carry no constraint, so nothing supplied the order and
     * the result was naturally ordered whatever that `Ord` said. See
     * known-bugs.md BUG 45.
     */
    @TruffleBoundary
    public static MezzeSortedSet toSortedSet(MezzeSet set, MezzeFunction cmpFn) {
        return SortedSetNatives.fromSet(set, cmpFn);
    }

    /**
     * Convert SortedSet to Set.
     */
    @TruffleBoundary
    public static MezzeSet fromSortedSet(MezzeSortedSet sortedSet) {
        return fromArray(SortedSetNatives.toArray(sortedSet));
    }
}
