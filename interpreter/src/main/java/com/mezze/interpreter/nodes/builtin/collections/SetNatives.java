package com.mezze.interpreter.nodes.builtin.collections;

import com.mezze.datastructures.list.MezzeList;
import com.mezze.datastructures.seq.MezzeSeq;
import com.mezze.datastructures.set.MezzeSet;
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
        MezzeSet result = MezzeSet.empty();
        for (int i = 0; i < list.size(); i++) {
            result = result.add(list.get(i));
        }
        return result;
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

    public static MezzeSet filter(MezzeSet set, MezzeFunction predicate,
                                  com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare1(predicate, "val");
        MezzeSet result = MezzeSet.empty();
        for (Object element : set) {
            Object res = ctx.call1(callNode, element);
            if (res instanceof Boolean && (Boolean) res) {
                result = result.add(element);
            }
        }
        return result;
    }

    public static MezzeSet map(MezzeSet set, MezzeFunction mapper,
                               com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare1(mapper, "val");
        MezzeSet result = MezzeSet.empty();
        for (Object element : set) {
            Object mapped = ctx.call1(callNode, element);
            result = result.add(mapped);
        }
        return result;
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

    // ==================== Helper for comparison ====================

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

    @TruffleBoundary
    public static MezzeSet symmetricDifference(MezzeSet s1, MezzeSet s2) {
        MezzeSet result = MezzeSet.empty();
        for (Object elem : s1) {
            if (!s2.contains(elem)) {
                result = result.add(elem);
            }
        }
        for (Object elem : s2) {
            if (!s1.contains(elem)) {
                result = result.add(elem);
            }
        }
        return result;
    }

    // ==================== Task #30: Collect Operations ====================

    /**
     * Apply a function that returns Option to each element and collect only the Some values.
     * Similar to flatMap but specifically for Option-returning functions.
     */
    public static MezzeSet filterMap(MezzeSet set, MezzeFunction f,
                                     com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare1(f, "val");
        MezzeSet result = MezzeSet.empty();
        for (Object elem : set) {
            Object mapped = ctx.call1(callNode, elem);
            if (mapped instanceof MezzeVariant v && v.isSome()) {
                result = result.add(v.getPayloadFieldByName("val"));
            }
        }
        return result;
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
     * Convert Set to SortedSet.
     */
    @TruffleBoundary
    public static MezzeSortedSet toSortedSet(MezzeSet set) {
        return SortedSetNatives.fromSet(set);
    }

    /**
     * Convert SortedSet to Set.
     */
    @TruffleBoundary
    public static MezzeSet fromSortedSet(MezzeSortedSet sortedSet) {
        return fromArray(SortedSetNatives.toArray(sortedSet));
    }
}
