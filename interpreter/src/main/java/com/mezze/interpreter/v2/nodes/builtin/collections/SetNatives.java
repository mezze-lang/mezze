package com.mezze.interpreter.v2.nodes.builtin.collections;

import com.mezze.interpreter.v2.values.*;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.util.HashSet;
import java.util.LinkedHashSet;

/**
 * Native Set operations - ULTRA PERFORMANT.
 *
 * Set in V2 uses LinkedHashSet for predictable iteration order.
 * All operations are immutable (return new sets).
 */
public final class SetNatives {

    private SetNatives() {}

    /**
     * Wrapper class for Set to enable persistent semantics
     */
    public static final class MezzeSet {
        private final LinkedHashSet<Object> set;

        public MezzeSet() {
            this.set = new LinkedHashSet<>();
        }

        private MezzeSet(LinkedHashSet<Object> set) {
            this.set = set;
        }

        public LinkedHashSet<Object> getSet() {
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
        public MezzeSet insert(Object element) {
            LinkedHashSet<Object> newSet = new LinkedHashSet<>(set);
            newSet.add(element);
            return new MezzeSet(newSet);
        }

        @TruffleBoundary
        public MezzeSet remove(Object element) {
            LinkedHashSet<Object> newSet = new LinkedHashSet<>(set);
            newSet.remove(element);
            return new MezzeSet(newSet);
        }

        @TruffleBoundary
        public Object[] toArray() {
            return set.toArray();
        }
    }

    // ==================== Factory Operations ====================

    public static MezzeSet empty() {
        return new MezzeSet();
    }

    @TruffleBoundary
    public static MezzeSet fromArray(Object[] arr) {
        LinkedHashSet<Object> set = new LinkedHashSet<>();
        for (Object element : arr) {
            set.add(element);
        }
        return new MezzeSet(set);
    }

    @TruffleBoundary
    public static MezzeSet fromList(MezzeList list) {
        LinkedHashSet<Object> set = new LinkedHashSet<>();
        for (int i = 0; i < list.size(); i++) {
            set.add(list.get(i));
        }
        return new MezzeSet(set);
    }

    // ==================== Basic Operations ====================

    public static long size(MezzeSet set) {
        return set.size();
    }

    public static boolean isEmpty(MezzeSet set) {
        return set.isEmpty();
    }

    public static boolean contains(MezzeSet set, Object element) {
        return set.contains(element);
    }

    public static MezzeSet insert(MezzeSet set, Object element) {
        return set.insert(element);
    }

    public static MezzeSet remove(MezzeSet set, Object element) {
        return set.remove(element);
    }

    @TruffleBoundary
    public static MezzeSet insertAll(MezzeSet set, Object[] elements) {
        LinkedHashSet<Object> newSet = new LinkedHashSet<>(set.getSet());
        for (Object element : elements) {
            newSet.add(element);
        }
        return new MezzeSet(newSet);
    }

    @TruffleBoundary
    public static MezzeSet removeAll(MezzeSet set, Object[] elements) {
        LinkedHashSet<Object> newSet = new LinkedHashSet<>(set.getSet());
        for (Object element : elements) {
            newSet.remove(element);
        }
        return new MezzeSet(newSet);
    }

    // ==================== Set Operations ====================

    @TruffleBoundary
    public static MezzeSet union(MezzeSet set1, MezzeSet set2) {
        LinkedHashSet<Object> result = new LinkedHashSet<>(set1.getSet());
        result.addAll(set2.getSet());
        return new MezzeSet(result);
    }

    @TruffleBoundary
    public static MezzeSet intersection(MezzeSet set1, MezzeSet set2) {
        LinkedHashSet<Object> result = new LinkedHashSet<>(set1.getSet());
        result.retainAll(set2.getSet());
        return new MezzeSet(result);
    }

    @TruffleBoundary
    public static MezzeSet difference(MezzeSet set1, MezzeSet set2) {
        LinkedHashSet<Object> result = new LinkedHashSet<>(set1.getSet());
        result.removeAll(set2.getSet());
        return new MezzeSet(result);
    }

    @TruffleBoundary
    public static boolean isSubset(MezzeSet set1, MezzeSet set2) {
        return set2.getSet().containsAll(set1.getSet());
    }

    @TruffleBoundary
    public static boolean equals(MezzeSet set1, MezzeSet set2) {
        return set1.getSet().equals(set2.getSet());
    }

    // ==================== Transform Operations ====================

    @TruffleBoundary
    public static MezzeSet filter(MezzeSet set, MezzeFunction predicate,
                                                                      com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        LinkedHashSet<Object> result = new LinkedHashSet<>();
        for (Object element : set.getSet()) {
            Object res = callNode.call(predicate.getCallTarget(),
                prepareArgs(predicate, element));
            if (res instanceof Boolean && (Boolean) res) {
                result.add(element);
            }
        }
        return new MezzeSet(result);
    }

    @TruffleBoundary
    public static MezzeSet map(MezzeSet set, MezzeFunction mapper,
                                                                com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        LinkedHashSet<Object> result = new LinkedHashSet<>();
        for (Object element : set.getSet()) {
            Object mapped = callNode.call(mapper.getCallTarget(),
                prepareArgs(mapper, element));
            result.add(mapped);
        }
        return new MezzeSet(result);
    }

    @TruffleBoundary
    public static Object fold(MezzeSet set, Object init, MezzeFunction folder,
                                                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        Object acc = init;
        for (Object element : set.getSet()) {
            acc = callNode.call(folder.getCallTarget(),
                prepareArgsTwo(folder, acc, element));
        }
        return acc;
    }

    // ==================== Conversion ====================

    public static Object[] toArray(MezzeSet set) {
        return set.toArray();
    }

    @TruffleBoundary
    public static MezzeList toList(MezzeSet set) {
        return MezzeList.fromArray(set.toArray());
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
        for (Object element : set.getSet()) {
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
        for (Object element : set.getSet()) {
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
        for (Object element : set.getSet()) {
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
        for (Object element : set.getSet()) {
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
        for (Object elem : set.getSet()) {
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
        for (Object elem : set.getSet()) {
            if (maxVal == null || compare(elem, maxVal) > 0) {
                maxVal = elem;
            }
        }
        return MezzeVariant.some(maxVal);
    }

    @TruffleBoundary
    public static long sum(MezzeSet set) {
        long total = 0;
        for (Object elem : set.getSet()) {
            if (elem instanceof Long l) total += l;
            else if (elem instanceof Integer n) total += n;
        }
        return total;
    }

    @TruffleBoundary
    public static long product(MezzeSet set) {
        if (set.isEmpty()) return 1L;
        long result = 1;
        for (Object elem : set.getSet()) {
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
        return empty().insert(element);
    }

    // ==================== Additional Operations ====================

    @TruffleBoundary
    public static MezzeVariant reduce(MezzeSet set, MezzeFunction f,
                                      com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (set.isEmpty()) {
            return MezzeVariant.none();
        }
        java.util.Iterator<Object> iter = set.getSet().iterator();
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
        for (Object elem : set.getSet()) {
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
        for (Object elem : set.getSet()) {
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
        java.util.LinkedHashSet<Object> trueSet = new java.util.LinkedHashSet<>();
        java.util.LinkedHashSet<Object> falseSet = new java.util.LinkedHashSet<>();
        for (Object elem : set.getSet()) {
            Object result = callNode.call(predicate.getCallTarget(),
                new Object[]{MezzeRecord.fromFields(new String[]{"val"}, new Object[]{elem})});
            if (result instanceof Boolean && (Boolean) result) {
                trueSet.add(elem);
            } else {
                falseSet.add(elem);
            }
        }
        return MezzeRecord.fromFields(
            new String[]{"pass", "fail"},
            new Object[]{new MezzeSet(trueSet), new MezzeSet(falseSet)}
        );
    }

    @TruffleBoundary
    public static double sumFloat(MezzeSet set) {
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
    public static double productFloat(MezzeSet set) {
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
        LinkedHashSet<Object> result = new LinkedHashSet<>();
        for (Object elem : set.getSet()) {
            Object mapped = callNode.call(f.getCallTarget(), prepareArgs(f, elem));
            if (mapped instanceof MezzeSet innerSet) {
                result.addAll(innerSet.getSet());
            }
        }
        return new MezzeSet(result);
    }

    @TruffleBoundary
    public static MezzeList groupBy(MezzeSet set, MezzeFunction keyFn,
                                    com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        java.util.LinkedHashMap<Object, java.util.ArrayList<Object>> groups = new java.util.LinkedHashMap<>();
        for (Object elem : set.getSet()) {
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
        return MezzeList.fromArray(result);
    }

    @TruffleBoundary
    public static MezzeSet symmetricDifference(MezzeSet s1, MezzeSet s2) {
        LinkedHashSet<Object> result = new LinkedHashSet<>();
        for (Object elem : s1.getSet()) {
            if (!s2.contains(elem)) {
                result.add(elem);
            }
        }
        for (Object elem : s2.getSet()) {
            if (!s1.contains(elem)) {
                result.add(elem);
            }
        }
        return new MezzeSet(result);
    }

    // ==================== Task #30: Collect Operations ====================

    /**
     * Apply a function that returns Option to each element and collect only the Some values.
     * Similar to flatMap but specifically for Option-returning functions.
     */
    @TruffleBoundary
    public static MezzeSet filterMap(MezzeSet set, MezzeFunction f,
                                     com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        LinkedHashSet<Object> result = new LinkedHashSet<>();
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
        return new MezzeSet(result);
    }

    /**
     * Filter elements and transform them in one pass, returning a Seq.
     * The function returns Option[B] - None filters out, Some(b) includes b.
     */
    @TruffleBoundary
    public static Object[] collectArray(MezzeSet set, MezzeFunction f,
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
    public static MezzeList collectList(MezzeSet set, MezzeFunction f,
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

    // ==================== SortedSet Conversions ====================

    /**
     * Convert Set to SortedSet.
     */
    @TruffleBoundary
    public static SortedSetNatives.MezzeSortedSet toSortedSet(MezzeSet set) {
        return SortedSetNatives.fromSet(set);
    }

    /**
     * Convert SortedSet to Set.
     */
    @TruffleBoundary
    public static MezzeSet fromSortedSet(SortedSetNatives.MezzeSortedSet sortedSet) {
        return fromArray(sortedSet.toArray());
    }
}
