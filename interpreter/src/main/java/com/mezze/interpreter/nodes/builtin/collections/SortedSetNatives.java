package com.mezze.interpreter.nodes.builtin.collections;

import com.mezze.datastructures.list.MezzeList;
import com.mezze.datastructures.seq.MezzeSeq;
import com.mezze.datastructures.set.MezzeSet;
import com.mezze.datastructures.sorted.MezzeSortedSet;
import com.mezze.interpreter.values.*;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

/**
 * Native SortedSet operations - ULTRA PERFORMANT.
 *
 * Now uses com.mezze.datastructures.sorted.MezzeSortedSet which provides:
 * - O(log n) contains (binary search)
 * - O(n) add, remove (due to array copy)
 * - O(1) min, max
 *
 * All operations are immutable (return new sets).
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public final class SortedSetNatives {

    private SortedSetNatives() {}

    // ==================== Factory Operations ====================

    public static MezzeSortedSet empty() {
        return MezzeSortedSet.empty();
    }

    @TruffleBoundary
    public static MezzeSortedSet singleton(Object element) {
        return MezzeSortedSet.<Comparable>empty().addObject(element);
    }

    @TruffleBoundary
    public static MezzeSortedSet fromList(MezzeList list) {
        MezzeSortedSet result = MezzeSortedSet.empty();
        for (int i = 0; i < list.size(); i++) {
            result = result.addObject(list.get(i));
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeSortedSet fromSet(MezzeSet set) {
        MezzeSortedSet result = MezzeSortedSet.empty();
        for (Object elem : set) {
            result = result.addObject(elem);
        }
        return result;
    }

    // ==================== Basic Operations ====================

    public static long size(MezzeSortedSet set) {
        return set.size();
    }

    public static boolean isEmpty(MezzeSortedSet set) {
        return set.isEmpty();
    }

    @TruffleBoundary
    public static boolean contains(MezzeSortedSet set, Object element) {
        return set.containsObject(element);
    }

    @TruffleBoundary
    public static MezzeSortedSet add(MezzeSortedSet set, Object element) {
        return set.addObject(element);
    }

    @TruffleBoundary
    public static MezzeSortedSet remove(MezzeSortedSet set, Object element) {
        return set.removeObject(element);
    }

    // ==================== Min/Max Operations ====================

    @TruffleBoundary
    public static MezzeVariant min(MezzeSortedSet set) {
        if (set.isEmpty()) {
            return MezzeVariant.none();
        }
        return MezzeVariant.some(set.min());
    }

    @TruffleBoundary
    public static MezzeVariant max(MezzeSortedSet set) {
        if (set.isEmpty()) {
            return MezzeVariant.none();
        }
        return MezzeVariant.some(set.max());
    }

    // ==================== Set Operations ====================

    @TruffleBoundary
    public static MezzeSortedSet union(MezzeSortedSet set1, MezzeSortedSet set2) {
        return set1.union(set2);
    }

    @TruffleBoundary
    public static MezzeSortedSet intersect(MezzeSortedSet set1, MezzeSortedSet set2) {
        return set1.intersect(set2);
    }

    @TruffleBoundary
    @SuppressWarnings("unchecked")
    public static MezzeSortedSet difference(MezzeSortedSet set1, MezzeSortedSet set2) {
        return set1.difference(set2);
    }

    @TruffleBoundary
    public static boolean equals(MezzeSortedSet set1, MezzeSortedSet set2) {
        return set1.equals(set2);
    }

    /**
     * Lexicographic comparison of two sorted sets (by their sorted element order).
     * Returns -1, 0, or 1.
     */
    @TruffleBoundary
    public static long compare(MezzeSortedSet set1, MezzeSortedSet set2) {
        int minLen = Math.min(set1.size(), set2.size());
        for (int i = 0; i < minLen; i++) {
            int cmp = compareElements(set1.get(i), set2.get(i));
            if (cmp != 0) {
                return cmp < 0 ? -1L : 1L;
            }
        }
        return Integer.compare(set1.size(), set2.size());
    }

    private static int compareElements(Object a, Object b) {
        if (a instanceof Long && b instanceof Long) {
            return Long.compare((Long) a, (Long) b);
        }
        if (a instanceof Double && b instanceof Double) {
            return Double.compare((Double) a, (Double) b);
        }
        if (a instanceof String && b instanceof String) {
            return ((String) a).compareTo((String) b);
        }
        if (a instanceof Long && b instanceof Double) {
            return Double.compare(((Long) a).doubleValue(), (Double) b);
        }
        if (a instanceof Double && b instanceof Long) {
            return Double.compare((Double) a, ((Long) b).doubleValue());
        }
        return 0;
    }

    // ==================== Conversion ====================

    @TruffleBoundary
    public static Object[] toArray(MezzeSortedSet set) {
        java.util.ArrayList<Object> result = new java.util.ArrayList<>(set.size());
        for (Object elem : set) {
            result.add(elem);
        }
        return result.toArray();
    }

    @TruffleBoundary
    public static MezzeSortedSet fromArray(Object[] arr) {
        MezzeSortedSet result = MezzeSortedSet.empty();
        for (Object elem : arr) {
            result = result.addObject(elem);
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeList toList(MezzeSortedSet set) {
        return MezzeList.of(toArray(set));
    }

    @TruffleBoundary
    public static MezzeSet toSet(MezzeSortedSet sortedSet) {
        return SetNatives.fromArray(toArray(sortedSet));
    }

    // ==================== Higher-Order Functions ====================

    @TruffleBoundary
    public static MezzeSortedSet filter(MezzeSortedSet set, MezzeFunction predicate,
                                        com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeSortedSet result = MezzeSortedSet.empty();
        for (Object element : set) {
            Object res = callNode.call(predicate.getCallTarget(), prepareArgs(predicate, element));
            if (res instanceof Boolean && (Boolean) res) {
                result = result.addObject(element);
            }
        }
        return result;
    }

    @TruffleBoundary
    public static Object fold(MezzeSortedSet set, Object init, MezzeFunction f,
                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        Object acc = init;
        for (Object element : set) {
            acc = callNode.call(f.getCallTarget(), prepareArgsAccX(f, acc, element));
        }
        return acc;
    }

    @TruffleBoundary
    public static MezzeSortedSet map(MezzeSortedSet set, MezzeFunction f,
                                     com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeSortedSet result = MezzeSortedSet.empty();
        for (Object element : set) {
            Object mapped = callNode.call(f.getCallTarget(), prepareArgs(f, element));
            result = result.addObject(mapped);
        }
        return result;
    }

    @TruffleBoundary
    public static boolean any(MezzeSortedSet set, MezzeFunction predicate,
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
    public static boolean all(MezzeSortedSet set, MezzeFunction predicate,
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
    public static boolean none(MezzeSortedSet set, MezzeFunction predicate,
                               com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        for (Object element : set) {
            Object result = callNode.call(predicate.getCallTarget(), prepareArgs(predicate, element));
            if (result instanceof Boolean && (Boolean) result) {
                return false;
            }
        }
        return true;
    }

    @TruffleBoundary
    public static MezzeVariant find(MezzeSortedSet set, MezzeFunction predicate,
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
    public static long count(MezzeSortedSet set, MezzeFunction predicate,
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

    @TruffleBoundary
    public static MezzeVariant reduce(MezzeSortedSet set, MezzeFunction f,
                                      com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (set.isEmpty()) {
            return MezzeVariant.none();
        }
        java.util.Iterator<Object> iter = set.iterator();
        Object acc = iter.next();
        while (iter.hasNext()) {
            acc = callNode.call(f.getCallTarget(), prepareArgsAccX(f, acc, iter.next()));
        }
        return MezzeVariant.some(acc);
    }

    @TruffleBoundary
    public static MezzeRecord forEach(MezzeSortedSet set, MezzeFunction fn,
                                      com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        for (Object element : set) {
            callNode.call(fn.getCallTarget(), prepareArgs(fn, element));
        }
        return MezzeRecord.empty();  // Returns unit
    }

    // ==================== Aggregation ====================

    @TruffleBoundary
    public static long sum(MezzeSortedSet set) {
        long result = 0;
        for (Object elem : set) {
            if (elem instanceof Long) {
                result += (Long) elem;
            }
        }
        return result;
    }

    @TruffleBoundary
    public static double sumFloat(MezzeSortedSet set) {
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
    public static long product(MezzeSortedSet set) {
        long result = 1;
        for (Object elem : set) {
            if (elem instanceof Long) {
                result *= (Long) elem;
            }
        }
        return result;
    }

    @TruffleBoundary
    public static double productFloat(MezzeSortedSet set) {
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

    // ==================== Conversion ====================

    @TruffleBoundary
    public static MezzeSeq toSeq(MezzeSortedSet set) {
        return MezzeSeq.fromArray(toArray(set));
    }

    // ==================== Additional Operations (Task #13) ====================

    @TruffleBoundary
    public static MezzeRecord partition(MezzeSortedSet set, MezzeFunction predicate,
                                        com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeSortedSet passSet = MezzeSortedSet.empty();
        MezzeSortedSet failSet = MezzeSortedSet.empty();
        for (Object elem : set) {
            Object result = callNode.call(predicate.getCallTarget(), prepareArgs(predicate, elem));
            if (result instanceof Boolean && (Boolean) result) {
                passSet = passSet.addObject(elem);
            } else {
                failSet = failSet.addObject(elem);
            }
        }
        return MezzeRecord.fromFields(
            new String[]{"pass", "fail"},
            new Object[]{passSet, failSet}
        );
    }

    @TruffleBoundary
    public static MezzeVariant minBy(MezzeSortedSet set, MezzeFunction f,
                                     com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (set.isEmpty()) return MezzeVariant.none();
        Object minElem = null;
        Object minKey = null;
        for (Object elem : set) {
            Object key = callNode.call(f.getCallTarget(), prepareArgs(f, elem));
            if (minKey == null || compare(key, minKey) < 0) {
                minKey = key;
                minElem = elem;
            }
        }
        return MezzeVariant.some(minElem);
    }

    @TruffleBoundary
    public static MezzeVariant maxBy(MezzeSortedSet set, MezzeFunction f,
                                     com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (set.isEmpty()) return MezzeVariant.none();
        Object maxElem = null;
        Object maxKey = null;
        for (Object elem : set) {
            Object key = callNode.call(f.getCallTarget(), prepareArgs(f, elem));
            if (maxKey == null || compare(key, maxKey) > 0) {
                maxKey = key;
                maxElem = elem;
            }
        }
        return MezzeVariant.some(maxElem);
    }

    @TruffleBoundary
    public static MezzeSortedSet take(MezzeSortedSet set, long n) {
        MezzeSortedSet result = MezzeSortedSet.empty();
        int count = 0;
        for (Object elem : set) {
            if (count >= n) break;
            result = result.addObject(elem);
            count++;
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeSortedSet drop(MezzeSortedSet set, long n) {
        MezzeSortedSet result = MezzeSortedSet.empty();
        int count = 0;
        for (Object elem : set) {
            if (count >= n) {
                result = result.addObject(elem);
            }
            count++;
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeSortedSet range(MezzeSortedSet set, Object lo, Object hi) {
        // Range is inclusive on both ends: [lo, hi]
        MezzeSortedSet result = MezzeSortedSet.empty();
        for (Object elem : set) {
            if (compare(elem, lo) >= 0 && compare(elem, hi) <= 0) {
                result = result.addObject(elem);
            }
        }
        return result;
    }

    // ==================== Advanced Operations (Task #14) ====================

    @TruffleBoundary
    public static MezzeSortedSet symmetricDifference(MezzeSortedSet s1, MezzeSortedSet s2) {
        MezzeSortedSet result = MezzeSortedSet.empty();
        for (Object elem : s1) {
            if (!s2.containsObject(elem)) result = result.addObject(elem);
        }
        for (Object elem : s2) {
            if (!s1.containsObject(elem)) result = result.addObject(elem);
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeSortedSet headSet(MezzeSortedSet set, Object toElement) {
        // headSet is strictly less than toElement (Java standard behavior)
        MezzeSortedSet result = MezzeSortedSet.empty();
        for (Object elem : set) {
            if (compare(elem, toElement) < 0) {
                result = result.addObject(elem);
            }
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeSortedSet tailSet(MezzeSortedSet set, Object fromElement) {
        // above returns elements greater than, but tailSet should include fromElement
        MezzeSortedSet result = MezzeSortedSet.empty();
        for (Object elem : set) {
            if (compare(elem, fromElement) >= 0) {
                result = result.addObject(elem);
            }
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeSortedSet subSet(MezzeSortedSet set, Object lo, Object hi) {
        MezzeSortedSet result = MezzeSortedSet.empty();
        for (Object elem : set) {
            if (compare(elem, lo) >= 0 && compare(elem, hi) < 0) {
                result = result.addObject(elem);
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

    // ==================== Task #30: Collect Operations ====================

    /**
     * Apply a function that returns Option to each element and collect only the Some values.
     * Similar to flatMap but specifically for Option-returning functions.
     */
    @TruffleBoundary
    public static MezzeSortedSet filterMap(MezzeSortedSet set, MezzeFunction f,
                                           com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeSortedSet result = MezzeSortedSet.empty();
        for (Object elem : set) {
            Object mapped = callNode.call(f.getCallTarget(), prepareArgs(f, elem));
            if (mapped instanceof MezzeVariant v && v.isSome()) {
                // Some wraps value in { val = value }, so extract it
                Object payload = v.getPayloadValue();
                if (payload instanceof MezzeRecord rec) {
                    result = result.addObject(rec.getField("val"));
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
    public static Object[] collectArray(MezzeSortedSet set, MezzeFunction f,
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
    public static MezzeList collectList(MezzeSortedSet set, MezzeFunction f,
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

    // ==================== Helper Methods ====================

    private static Object[] prepareArgs(MezzeFunction f, Object value) {
        return new Object[]{MezzeRecord.fromFields(new String[]{"val"}, new Object[]{value})};
    }

    private static Object[] prepareArgsAccX(MezzeFunction f, Object acc, Object x) {
        return new Object[]{MezzeRecord.fromFields(new String[]{"acc", "x"}, new Object[]{acc, x})};
    }
}
