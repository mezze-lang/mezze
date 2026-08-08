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
    public static boolean equals(MezzeSet set1, MezzeSet set2) {
        return set1.equals(set2);
    }

    // ==================== Transform Operations ====================

    @TruffleBoundary
    public static MezzeSet filter(MezzeSet set, MezzeFunction predicate,
                                  com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeSet result = MezzeSet.empty();
        for (Object element : set) {
            Object res = callNode.call(predicate.getCallTarget(),
                prepareArgs(predicate, element));
            if (res instanceof Boolean && (Boolean) res) {
                result = result.add(element);
            }
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeSet map(MezzeSet set, MezzeFunction mapper,
                               com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeSet result = MezzeSet.empty();
        for (Object element : set) {
            Object mapped = callNode.call(mapper.getCallTarget(),
                prepareArgs(mapper, element));
            result = result.add(mapped);
        }
        return result;
    }

    /**
     * #403: drop {@code @TruffleBoundary} so Truffle PE can trace across
     * the loop and IndirectCallNode PIC can inline the folder body.
     * See {@link SeqNatives}'s foldTwoArg for why we do not skip the
     * {@code MezzeRecord{acc, x}} wrap at runtime.
     */
    public static Object fold(MezzeSet set, Object init, MezzeFunction folder,
                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        Object[] closureValues = folder.getClosureValues();
        int closureLen = closureValues.length;
        com.oracle.truffle.api.CallTarget target = folder.getCallTarget();
        Object acc = init;
        for (Object element : set) {
            Object[] args = new Object[closureLen + 1];
            System.arraycopy(closureValues, 0, args, 0, closureLen);
            args[closureLen] = MezzeRecord.wrapAccX(acc, element);
            acc = callNode.call(target, args);
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

    // ==================== Helper Methods ====================

    private static Object[] prepareArgs(MezzeFunction fn, Object arg) {
        Object[] closureValues = fn.getClosureValues();
        Object[] args = new Object[closureValues.length + 1];
        System.arraycopy(closureValues, 0, args, 0, closureValues.length);
        args[closureValues.length] = MezzeRecord.fromFields(
            new String[]{"val"}, new Object[]{arg}
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

    // ==================== Predicate Operations ====================

    @TruffleBoundary
    public static boolean any(MezzeSet set, MezzeFunction predicate,
                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        for (Object element : set) {
            Object result = callNode.call(predicate.getCallTarget(), prepareArgs(predicate, element));
            if (result instanceof Boolean && (Boolean) result) {
                return true;
            }
        }
        return false;
    }

    @TruffleBoundary
    public static boolean all(MezzeSet set, MezzeFunction predicate,
                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        for (Object element : set) {
            Object result = callNode.call(predicate.getCallTarget(), prepareArgs(predicate, element));
            if (!(result instanceof Boolean) || !(Boolean) result) {
                return false;
            }
        }
        return true;
    }

    @TruffleBoundary
    public static boolean none(MezzeSet set, MezzeFunction predicate,
                               com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        return !any(set, predicate, callNode);
    }

    @TruffleBoundary
    public static MezzeVariant find(MezzeSet set, MezzeFunction predicate,
                                    com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        for (Object element : set) {
            Object result = callNode.call(predicate.getCallTarget(), prepareArgs(predicate, element));
            if (result instanceof Boolean && (Boolean) result) {
                return MezzeVariant.some(element);
            }
        }
        return MezzeVariant.none();
    }

    @TruffleBoundary
    public static long count(MezzeSet set, MezzeFunction predicate,
                             com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        long cnt = 0;
        for (Object element : set) {
            Object result = callNode.call(predicate.getCallTarget(), prepareArgs(predicate, element));
            if (result instanceof Boolean && (Boolean) result) {
                cnt++;
            }
        }
        return cnt;
    }

    // ==================== Min/Max/Sum/Product ====================

    @TruffleBoundary
    public static MezzeVariant min(MezzeSet set) {
        if (set.isEmpty()) return MezzeVariant.none();
        Object minVal = null;
        for (Object elem : set) {
            if (minVal == null || compare(elem, minVal) < 0) {
                minVal = elem;
            }
        }
        return MezzeVariant.some(minVal);
    }

    @TruffleBoundary
    public static MezzeVariant max(MezzeSet set) {
        if (set.isEmpty()) return MezzeVariant.none();
        Object maxVal = null;
        for (Object elem : set) {
            if (maxVal == null || compare(elem, maxVal) > 0) {
                maxVal = elem;
            }
        }
        return MezzeVariant.some(maxVal);
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

    @TruffleBoundary
    public static MezzeVariant reduce(MezzeSet set, MezzeFunction f,
                                      com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (set.isEmpty()) {
            return MezzeVariant.none();
        }
        java.util.Iterator<Object> iter = set.iterator();
        Object acc = iter.next();
        while (iter.hasNext()) {
            acc = callNode.call(f.getCallTarget(),
                new Object[]{MezzeRecord.fromFields(new String[]{"acc", "x"}, new Object[]{acc, iter.next()})});
        }
        return MezzeVariant.some(acc);
    }

    @TruffleBoundary
    public static MezzeVariant minBy(MezzeSet set, MezzeFunction f,
                                     com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (set.isEmpty()) {
            return MezzeVariant.none();
        }
        Object minElem = null;
        Object minKey = null;
        for (Object elem : set) {
            Object key = callNode.call(f.getCallTarget(),
                new Object[]{MezzeRecord.fromFields(new String[]{"val"}, new Object[]{elem})});
            if (minKey == null || compare(key, minKey) < 0) {
                minKey = key;
                minElem = elem;
            }
        }
        return MezzeVariant.some(minElem);
    }

    @TruffleBoundary
    public static MezzeVariant maxBy(MezzeSet set, MezzeFunction f,
                                     com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (set.isEmpty()) {
            return MezzeVariant.none();
        }
        Object maxElem = null;
        Object maxKey = null;
        for (Object elem : set) {
            Object key = callNode.call(f.getCallTarget(),
                new Object[]{MezzeRecord.fromFields(new String[]{"val"}, new Object[]{elem})});
            if (maxKey == null || compare(key, maxKey) > 0) {
                maxKey = key;
                maxElem = elem;
            }
        }
        return MezzeVariant.some(maxElem);
    }

    @TruffleBoundary
    public static MezzeRecord partition(MezzeSet set, MezzeFunction predicate,
                                        com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeSet trueSet = MezzeSet.empty();
        MezzeSet falseSet = MezzeSet.empty();
        for (Object elem : set) {
            Object result = callNode.call(predicate.getCallTarget(),
                new Object[]{MezzeRecord.fromFields(new String[]{"val"}, new Object[]{elem})});
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

    @TruffleBoundary
    public static MezzeSet flatMap(MezzeSet set, MezzeFunction f,
                                   com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeSet result = MezzeSet.empty();
        for (Object elem : set) {
            Object mapped = callNode.call(f.getCallTarget(), prepareArgs(f, elem));
            if (mapped instanceof MezzeSet innerSet) {
                result = result.union(innerSet);
            }
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeList groupBy(MezzeSet set, MezzeFunction keyFn,
                                    com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        java.util.LinkedHashMap<Object, java.util.ArrayList<Object>> groups = new java.util.LinkedHashMap<>();
        for (Object elem : set) {
            Object key = callNode.call(keyFn.getCallTarget(), prepareArgs(keyFn, elem));
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
    @TruffleBoundary
    public static MezzeSet filterMap(MezzeSet set, MezzeFunction f,
                                     com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeSet result = MezzeSet.empty();
        for (Object elem : set) {
            Object mapped = callNode.call(f.getCallTarget(), prepareArgs(f, elem));
            if (mapped instanceof MezzeVariant v && v.isSome()) {
                // Some wraps value in { val = value }, so extract it
                Object payload = v.getPayloadValue();
                if (payload instanceof MezzeRecord rec) {
                    result = result.add(rec.getField("val"));
                }
            }
        }
        return result;
    }

    /**
     * Filter elements and transform them in one pass, returning a Seq.
     * The function returns Option[B] - None filters out, Some(b) includes b.
     */
    @TruffleBoundary
    public static Object[] collectArray(MezzeSet set, MezzeFunction f,
                                        com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        java.util.ArrayList<Object> result = new java.util.ArrayList<>();
        for (Object elem : set) {
            Object mapped = callNode.call(f.getCallTarget(), prepareArgs(f, elem));
            if (mapped instanceof MezzeVariant v && v.isSome()) {
                Object payload = v.getPayloadValue();
                if (payload instanceof MezzeRecord rec) {
                    result.add(rec.getField("val"));
                }
            }
        }
        return result.toArray();
    }

    /**
     * Like filterMap but returns a List for ordered result.
     */
    @TruffleBoundary
    public static MezzeList collectList(MezzeSet set, MezzeFunction f,
                                        com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        java.util.ArrayList<Object> result = new java.util.ArrayList<>();
        for (Object elem : set) {
            Object mapped = callNode.call(f.getCallTarget(), prepareArgs(f, elem));
            if (mapped instanceof MezzeVariant v && v.isSome()) {
                Object payload = v.getPayloadValue();
                if (payload instanceof MezzeRecord rec) {
                    result.add(rec.getField("val"));
                }
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
