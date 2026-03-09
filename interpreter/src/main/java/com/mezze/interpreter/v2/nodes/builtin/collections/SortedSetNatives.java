package com.mezze.interpreter.v2.nodes.builtin.collections;

import com.mezze.interpreter.v2.values.*;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.util.TreeSet;

/**
 * Native SortedSet operations - ULTRA PERFORMANT.
 *
 * SortedSet in V2 uses TreeSet for sorted iteration order.
 * All operations are immutable (return new sets).
 */
public final class SortedSetNatives {

    private SortedSetNatives() {}

    /**
     * Wrapper class for SortedSet
     */
    public static final class MezzeSortedSet {
        private final TreeSet<Object> set;

        public MezzeSortedSet() {
            this.set = new TreeSet<>(SortedSetNatives::compare);
        }

        private MezzeSortedSet(TreeSet<Object> set) {
            this.set = set;
        }

        public TreeSet<Object> getSet() {
            return set;
        }

        public int size() {
            return set.size();
        }

        public boolean isEmpty() {
            return set.isEmpty();
        }

        @TruffleBoundary
        public boolean contains(Object element) {
            return set.contains(element);
        }

        @TruffleBoundary
        public MezzeSortedSet add(Object element) {
            TreeSet<Object> newSet = new TreeSet<>(set);
            newSet.add(element);
            return new MezzeSortedSet(newSet);
        }

        @TruffleBoundary
        public MezzeSortedSet remove(Object element) {
            TreeSet<Object> newSet = new TreeSet<>(set);
            newSet.remove(element);
            return new MezzeSortedSet(newSet);
        }

        @TruffleBoundary
        public Object[] toArray() {
            return set.toArray();
        }
    }

    // ==================== Factory Operations ====================

    public static MezzeSortedSet empty() {
        return new MezzeSortedSet();
    }

    @TruffleBoundary
    public static MezzeSortedSet singleton(Object element) {
        MezzeSortedSet set = new MezzeSortedSet();
        return set.add(element);
    }

    @TruffleBoundary
    public static MezzeSortedSet fromList(MezzeList list) {
        TreeSet<Object> set = new TreeSet<>(SortedSetNatives::compare);
        for (int i = 0; i < list.size(); i++) {
            set.add(list.get(i));
        }
        return new MezzeSortedSet(set);
    }

    @TruffleBoundary
    public static MezzeSortedSet fromSet(SetNatives.MezzeSet set) {
        TreeSet<Object> treeSet = new TreeSet<>(SortedSetNatives::compare);
        treeSet.addAll(java.util.Arrays.asList(set.toArray()));
        return new MezzeSortedSet(treeSet);
    }

    // ==================== Basic Operations ====================

    public static long size(MezzeSortedSet set) {
        return set.size();
    }

    public static boolean isEmpty(MezzeSortedSet set) {
        return set.isEmpty();
    }

    public static boolean contains(MezzeSortedSet set, Object element) {
        return set.contains(element);
    }

    public static MezzeSortedSet add(MezzeSortedSet set, Object element) {
        return set.add(element);
    }

    public static MezzeSortedSet remove(MezzeSortedSet set, Object element) {
        return set.remove(element);
    }

    // ==================== Min/Max Operations ====================

    @TruffleBoundary
    public static MezzeVariant min(MezzeSortedSet set) {
        if (set.isEmpty()) {
            return MezzeVariant.none();
        }
        return MezzeVariant.some(set.getSet().first());
    }

    @TruffleBoundary
    public static MezzeVariant max(MezzeSortedSet set) {
        if (set.isEmpty()) {
            return MezzeVariant.none();
        }
        return MezzeVariant.some(set.getSet().last());
    }

    // ==================== Set Operations ====================

    @TruffleBoundary
    public static MezzeSortedSet union(MezzeSortedSet set1, MezzeSortedSet set2) {
        TreeSet<Object> result = new TreeSet<>(set1.getSet());
        result.addAll(set2.getSet());
        return new MezzeSortedSet(result);
    }

    @TruffleBoundary
    public static MezzeSortedSet intersect(MezzeSortedSet set1, MezzeSortedSet set2) {
        TreeSet<Object> result = new TreeSet<>(set1.getSet());
        result.retainAll(set2.getSet());
        return new MezzeSortedSet(result);
    }

    @TruffleBoundary
    public static MezzeSortedSet difference(MezzeSortedSet set1, MezzeSortedSet set2) {
        TreeSet<Object> result = new TreeSet<>(set1.getSet());
        result.removeAll(set2.getSet());
        return new MezzeSortedSet(result);
    }

    @TruffleBoundary
    public static boolean equals(MezzeSortedSet set1, MezzeSortedSet set2) {
        return set1.getSet().equals(set2.getSet());
    }

    // ==================== Conversion ====================

    @TruffleBoundary
    public static Object[] toArray(MezzeSortedSet set) {
        return set.toArray();
    }

    @TruffleBoundary
    public static MezzeSortedSet fromArray(Object[] arr) {
        TreeSet<Object> treeSet = new TreeSet<>(SortedSetNatives::compare);
        for (Object element : arr) {
            treeSet.add(element);
        }
        return new MezzeSortedSet(treeSet);
    }

    @TruffleBoundary
    public static MezzeList toList(MezzeSortedSet set) {
        return MezzeList.fromArray(set.toArray());
    }

    @TruffleBoundary
    public static SetNatives.MezzeSet toSet(MezzeSortedSet sortedSet) {
        return SetNatives.fromArray(sortedSet.toArray());
    }

    // ==================== Higher-Order Functions ====================

    @TruffleBoundary
    public static MezzeSortedSet filter(MezzeSortedSet set, MezzeFunction predicate,
                                        com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        TreeSet<Object> result = new TreeSet<>(SortedSetNatives::compare);
        for (Object element : set.getSet()) {
            Object res = callNode.call(predicate.getCallTarget(), prepareArgs(predicate, element));
            if (res instanceof Boolean && (Boolean) res) {
                result.add(element);
            }
        }
        return new MezzeSortedSet(result);
    }

    @TruffleBoundary
    public static Object fold(MezzeSortedSet set, Object init, MezzeFunction f,
                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        Object acc = init;
        for (Object element : set.getSet()) {
            acc = callNode.call(f.getCallTarget(), prepareArgsAccX(f, acc, element));
        }
        return acc;
    }

    @TruffleBoundary
    public static MezzeSortedSet map(MezzeSortedSet set, MezzeFunction f,
                                     com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        TreeSet<Object> result = new TreeSet<>(SortedSetNatives::compare);
        for (Object element : set.getSet()) {
            Object mapped = callNode.call(f.getCallTarget(), prepareArgs(f, element));
            result.add(mapped);
        }
        return new MezzeSortedSet(result);
    }

    @TruffleBoundary
    public static boolean any(MezzeSortedSet set, MezzeFunction predicate,
                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        for (Object element : set.getSet()) {
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
        for (Object element : set.getSet()) {
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
        for (Object element : set.getSet()) {
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
        for (Object element : set.getSet()) {
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
        for (Object element : set.getSet()) {
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
        java.util.Iterator<Object> iter = set.getSet().iterator();
        Object acc = iter.next();
        while (iter.hasNext()) {
            acc = callNode.call(f.getCallTarget(), prepareArgsAccX(f, acc, iter.next()));
        }
        return MezzeVariant.some(acc);
    }

    @TruffleBoundary
    public static MezzeRecord forEach(MezzeSortedSet set, MezzeFunction fn,
                                      com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        for (Object element : set.getSet()) {
            callNode.call(fn.getCallTarget(), prepareArgs(fn, element));
        }
        return MezzeRecord.empty();  // Returns unit
    }

    // ==================== Aggregation ====================

    @TruffleBoundary
    public static long sum(MezzeSortedSet set) {
        long result = 0;
        for (Object elem : set.getSet()) {
            if (elem instanceof Long) {
                result += (Long) elem;
            }
        }
        return result;
    }

    @TruffleBoundary
    public static double sumFloat(MezzeSortedSet set) {
        double result = 0.0;
        for (Object elem : set.getSet()) {
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
        for (Object elem : set.getSet()) {
            if (elem instanceof Long) {
                result *= (Long) elem;
            }
        }
        return result;
    }

    @TruffleBoundary
    public static double productFloat(MezzeSortedSet set) {
        double result = 1.0;
        for (Object elem : set.getSet()) {
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
        return MezzeSeq.fromArray(set.toArray());
    }

    // ==================== Additional Operations (Task #13) ====================

    @TruffleBoundary
    public static MezzeRecord partition(MezzeSortedSet set, MezzeFunction predicate,
                                        com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        TreeSet<Object> passSet = new TreeSet<>(SortedSetNatives::compare);
        TreeSet<Object> failSet = new TreeSet<>(SortedSetNatives::compare);
        for (Object elem : set.getSet()) {
            Object result = callNode.call(predicate.getCallTarget(), prepareArgs(predicate, elem));
            if (result instanceof Boolean && (Boolean) result) {
                passSet.add(elem);
            } else {
                failSet.add(elem);
            }
        }
        return MezzeRecord.fromFields(
            new String[]{"pass", "fail"},
            new Object[]{new MezzeSortedSet(passSet), new MezzeSortedSet(failSet)}
        );
    }

    @TruffleBoundary
    public static MezzeVariant minBy(MezzeSortedSet set, MezzeFunction f,
                                     com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (set.isEmpty()) return MezzeVariant.none();
        Object minElem = null;
        Object minKey = null;
        for (Object elem : set.getSet()) {
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
        for (Object elem : set.getSet()) {
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
        TreeSet<Object> result = new TreeSet<>(SortedSetNatives::compare);
        int count = 0;
        for (Object elem : set.getSet()) {
            if (count >= n) break;
            result.add(elem);
            count++;
        }
        return new MezzeSortedSet(result);
    }

    @TruffleBoundary
    public static MezzeSortedSet drop(MezzeSortedSet set, long n) {
        TreeSet<Object> result = new TreeSet<>(SortedSetNatives::compare);
        int count = 0;
        for (Object elem : set.getSet()) {
            if (count >= n) {
                result.add(elem);
            }
            count++;
        }
        return new MezzeSortedSet(result);
    }

    @TruffleBoundary
    public static MezzeSortedSet range(MezzeSortedSet set, Object lo, Object hi) {
        TreeSet<Object> result = new TreeSet<>(SortedSetNatives::compare);
        for (Object elem : set.getSet()) {
            if (compare(elem, lo) >= 0 && compare(elem, hi) <= 0) {
                result.add(elem);
            }
        }
        return new MezzeSortedSet(result);
    }

    // ==================== Advanced Operations (Task #14) ====================

    @TruffleBoundary
    public static MezzeSortedSet symmetricDifference(MezzeSortedSet s1, MezzeSortedSet s2) {
        TreeSet<Object> result = new TreeSet<>(SortedSetNatives::compare);
        for (Object elem : s1.getSet()) {
            if (!s2.contains(elem)) result.add(elem);
        }
        for (Object elem : s2.getSet()) {
            if (!s1.contains(elem)) result.add(elem);
        }
        return new MezzeSortedSet(result);
    }

    @TruffleBoundary
    public static MezzeSortedSet headSet(MezzeSortedSet set, Object toElement) {
        TreeSet<Object> result = new TreeSet<>(SortedSetNatives::compare);
        for (Object elem : set.getSet()) {
            if (compare(elem, toElement) < 0) {
                result.add(elem);
            }
        }
        return new MezzeSortedSet(result);
    }

    @TruffleBoundary
    public static MezzeSortedSet tailSet(MezzeSortedSet set, Object fromElement) {
        TreeSet<Object> result = new TreeSet<>(SortedSetNatives::compare);
        for (Object elem : set.getSet()) {
            if (compare(elem, fromElement) >= 0) {
                result.add(elem);
            }
        }
        return new MezzeSortedSet(result);
    }

    @TruffleBoundary
    public static MezzeSortedSet subSet(MezzeSortedSet set, Object lo, Object hi) {
        TreeSet<Object> result = new TreeSet<>(SortedSetNatives::compare);
        for (Object elem : set.getSet()) {
            if (compare(elem, lo) >= 0 && compare(elem, hi) < 0) {
                result.add(elem);
            }
        }
        return new MezzeSortedSet(result);
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
        TreeSet<Object> result = new TreeSet<>(SortedSetNatives::compare);
        for (Object elem : set.getSet()) {
            Object mapped = callNode.call(f.getCallTarget(), prepareArgs(f, elem));
            if (mapped instanceof MezzeVariant v && v.isSome()) {
                // Some wraps value in { val = value }, so extract it
                Object payload = v.getPayloadValue();
                if (payload instanceof MezzeRecord rec) {
                    result.add(rec.getField("val"));
                }
            }
        }
        return new MezzeSortedSet(result);
    }

    /**
     * Filter elements and transform them in one pass, returning a Seq.
     * The function returns Option[B] - None filters out, Some(b) includes b.
     */
    @TruffleBoundary
    public static Object[] collectArray(MezzeSortedSet set, MezzeFunction f,
                                        com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        java.util.ArrayList<Object> result = new java.util.ArrayList<>();
        for (Object elem : set.getSet()) {
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
        for (Object elem : set.getSet()) {
            Object mapped = callNode.call(f.getCallTarget(), prepareArgs(f, elem));
            if (mapped instanceof MezzeVariant v && v.isSome()) {
                Object payload = v.getPayloadValue();
                if (payload instanceof MezzeRecord rec) {
                    result.add(rec.getField("val"));
                }
            }
        }
        return MezzeList.fromArray(result.toArray());
    }

    // ==================== Helper Methods ====================

    private static Object[] prepareArgs(MezzeFunction f, Object value) {
        return new Object[]{MezzeRecord.fromFields(new String[]{"val"}, new Object[]{value})};
    }

    private static Object[] prepareArgsAccX(MezzeFunction f, Object acc, Object x) {
        return new Object[]{MezzeRecord.fromFields(new String[]{"acc", "x"}, new Object[]{acc, x})};
    }
}
