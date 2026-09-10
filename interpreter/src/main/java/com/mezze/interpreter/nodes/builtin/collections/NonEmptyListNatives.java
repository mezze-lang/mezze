package com.mezze.interpreter.nodes.builtin.collections;

import com.mezze.datastructures.list.MezzeList;

import com.mezze.interpreter.values.*;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

/**
 * Native NonEmptyList operations - ULTRA PERFORMANT.
 *
 * NonEmptyList in V2 is represented as Object[] with guaranteed length >= 1.
 * All operations are designed for JIT compilation.
 */
public final class NonEmptyListNatives {

    private NonEmptyListNatives() {}

    /**
     * Wrapper class for NonEmptyList - guarantees non-empty
     */
    public static final class MezzeNonEmptyList {
        private final Object[] elements;

        private MezzeNonEmptyList(Object[] elements) {
            if (elements.length == 0) {
                throw new IllegalArgumentException("NonEmptyList cannot be empty");
            }
            this.elements = elements;
        }

        public Object[] getElements() {
            return elements;
        }

        public int size() {
            return elements.length;
        }

        public Object head() {
            return elements[0];
        }

        public Object last() {
            return elements[elements.length - 1];
        }

        public MezzeList tail() {
            if (elements.length == 1) {
                return MezzeList.empty();
            }
            Object[] tail = new Object[elements.length - 1];
            System.arraycopy(elements, 1, tail, 0, elements.length - 1);
            return MezzeList.of(tail);
        }

        public Object get(int index) {
            return elements[index];
        }
    }

    // ==================== Factory Operations ====================

    @TruffleBoundary
    public static MezzeNonEmptyList singleton(Object element) {
        return new MezzeNonEmptyList(new Object[]{element});
    }

    @TruffleBoundary
    public static MezzeNonEmptyList fromHeadTail(Object head, MezzeList tail) {
        Object[] elements = new Object[tail.size() + 1];
        elements[0] = head;
        for (int i = 0; i < tail.size(); i++) {
            elements[i + 1] = tail.get(i);
        }
        return new MezzeNonEmptyList(elements);
    }

    @TruffleBoundary
    public static MezzeVariant fromList(MezzeList list) {
        if (list.isEmpty()) {
            // No-fatal: empty source is a normal, recoverable case.
            // `nel::from_list` returns Option (NonEmptyList 'a).
            return MezzeVariant.none();
        }
        Object[] elements = new Object[list.size()];
        for (int i = 0; i < list.size(); i++) {
            elements[i] = list.get(i);
        }
        return MezzeVariant.some(new MezzeNonEmptyList(elements));
    }

    @TruffleBoundary
    public static MezzeVariant fromArray(Object[] arr) {
        if (arr.length == 0) {
            // No-fatal: empty seq is a normal case; `nel::from_seq`
            // returns Option (NonEmptyList 'a).
            return MezzeVariant.none();
        }
        return MezzeVariant.some(new MezzeNonEmptyList(arr.clone()));
    }

    // ==================== Basic Operations ====================

    public static long length(MezzeNonEmptyList list) {
        return list.size();
    }

    public static Object head(MezzeNonEmptyList list) {
        return list.head();
    }

    public static MezzeList tail(MezzeNonEmptyList list) {
        return list.tail();
    }

    public static Object last(MezzeNonEmptyList list) {
        return list.last();
    }

    public static MezzeVariant get(MezzeNonEmptyList list, long index) {
        int idx = (int) index;
        if (idx < 0 || idx >= list.size()) {
            // No-fatal: out-of-range index is a normal case; `nel::get`
            // returns Option 'a.
            return MezzeVariant.none();
        }
        return MezzeVariant.some(list.get(idx));
    }

    // ==================== Construct Operations ====================

    @TruffleBoundary
    public static MezzeNonEmptyList cons(Object element, MezzeNonEmptyList list) {
        Object[] result = new Object[list.size() + 1];
        result[0] = element;
        System.arraycopy(list.getElements(), 0, result, 1, list.size());
        return new MezzeNonEmptyList(result);
    }

    @TruffleBoundary
    public static MezzeNonEmptyList append(MezzeNonEmptyList list1, MezzeNonEmptyList list2) {
        Object[] result = new Object[list1.size() + list2.size()];
        System.arraycopy(list1.getElements(), 0, result, 0, list1.size());
        System.arraycopy(list2.getElements(), 0, result, list1.size(), list2.size());
        return new MezzeNonEmptyList(result);
    }

    @TruffleBoundary
    public static MezzeNonEmptyList appendList(MezzeNonEmptyList list, MezzeList other) {
        Object[] result = new Object[list.size() + other.size()];
        System.arraycopy(list.getElements(), 0, result, 0, list.size());
        for (int i = 0; i < other.size(); i++) {
            result[list.size() + i] = other.get(i);
        }
        return new MezzeNonEmptyList(result);
    }

    /**
     * Prepend a list to an element to form a NonEmptyList.
     * {xs: List 'a, y: 'a} -> NonEmptyList 'a
     * Result: [xs[0], xs[1], ..., xs[n-1], y]
     */
    @TruffleBoundary
    public static MezzeNonEmptyList prependList(MezzeList list, Object element) {
        Object[] result = new Object[list.size() + 1];
        for (int i = 0; i < list.size(); i++) {
            result[i] = list.get(i);
        }
        result[list.size()] = element;
        return new MezzeNonEmptyList(result);
    }

    // ==================== Transform Operations ====================

    @TruffleBoundary
    public static MezzeNonEmptyList reverse(MezzeNonEmptyList list) {
        Object[] result = new Object[list.size()];
        for (int i = 0; i < list.size(); i++) {
            result[list.size() - 1 - i] = list.get(i);
        }
        return new MezzeNonEmptyList(result);
    }

    public static MezzeNonEmptyList map(MezzeNonEmptyList list, MezzeFunction mapper,
                                                                                  com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare1(mapper, "x");
        Object[] result = new Object[list.size()];
        for (int i = 0; i < list.size(); i++) {
            result[i] = ctx.call1(callNode, list.get(i));
        }
        return new MezzeNonEmptyList(result);
    }

    public static MezzeNonEmptyList flatMap(MezzeNonEmptyList list, MezzeFunction mapper,
                                                                                          com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare1(mapper, "x");
        java.util.ArrayList<Object> result = new java.util.ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Object mapped = ctx.call1(callNode, list.get(i));
            if (mapped instanceof MezzeNonEmptyList nel) {
                for (int j = 0; j < nel.size(); j++) {
                    result.add(nel.get(j));
                }
            }
        }
        if (result.isEmpty()) {
            throw new IllegalStateException("[internal] flatMap on NonEmptyList produced an empty result — invariant violated");
        }
        return new MezzeNonEmptyList(result.toArray());
    }

    public static Object foldLeft(MezzeNonEmptyList list, Object init, MezzeFunction folder,
                                                                      com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare2(folder, "acc", "x");
        Object acc = init;
        for (int i = 0; i < list.size(); i++) {
            acc = ctx.call2(callNode, acc, list.get(i));
        }
        return acc;
    }

    public static Object foldRight(MezzeNonEmptyList list, Object init, MezzeFunction folder,
                                                                        com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare2(folder, "x", "acc");
        Object acc = init;
        for (int i = list.size() - 1; i >= 0; i--) {
            acc = ctx.call2(callNode, list.get(i), acc);
        }
        return acc;
    }

    public static Object reduce(MezzeNonEmptyList list, MezzeFunction reducer,
                                                                  com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare2(reducer, "acc", "x");
        Object acc = list.head();
        for (int i = 1; i < list.size(); i++) {
            acc = ctx.call2(callNode, acc, list.get(i));
        }
        return acc;
    }

    // ==================== Search Operations ====================

    @TruffleBoundary
    public static boolean contains(MezzeNonEmptyList list, Object element) {
        for (int i = 0; i < list.size(); i++) {
            if (deepEquals(list.get(i), element)) {
                return true;
            }
        }
        return false;
    }

    public static boolean any(MezzeNonEmptyList list, MezzeFunction predicate,
                                                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare1(predicate, "x");
        for (int i = 0; i < list.size(); i++) {
            Object result = ctx.call1(callNode, list.get(i));
            if (result instanceof Boolean && (Boolean) result) {
                return true;
            }
        }
        return false;
    }

    public static boolean all(MezzeNonEmptyList list, MezzeFunction predicate,
                                                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare1(predicate, "x");
        for (int i = 0; i < list.size(); i++) {
            Object result = ctx.call1(callNode, list.get(i));
            if (!(result instanceof Boolean) || !(Boolean) result) {
                return false;
            }
        }
        return true;
    }

    // ==================== Intersperse ====================

    @TruffleBoundary
    public static MezzeNonEmptyList intersperse(MezzeNonEmptyList list, Object separator) {
        if (list.size() == 1) return list;

        Object[] result = new Object[list.size() * 2 - 1];
        for (int i = 0; i < list.size(); i++) {
            result[i * 2] = list.get(i);
            if (i < list.size() - 1) {
                result[i * 2 + 1] = separator;
            }
        }
        return new MezzeNonEmptyList(result);
    }

    // ==================== Zip ====================

    @TruffleBoundary
    public static MezzeNonEmptyList zip(MezzeNonEmptyList list1, MezzeNonEmptyList list2) {
        int len = Math.min(list1.size(), list2.size());
        Object[] result = new Object[len];
        for (int i = 0; i < len; i++) {
            result[i] = MezzeRecord.fromFields(
                new String[]{"fst", "snd"},
                new Object[]{list1.get(i), list2.get(i)}
            );
        }
        return new MezzeNonEmptyList(result);
    }

    // ==================== Sort Operations ====================

    @TruffleBoundary
    @SuppressWarnings("unchecked")
    public static MezzeNonEmptyList sort(MezzeNonEmptyList list) {
        Object[] arr = list.getElements().clone();
        java.util.Arrays.sort(arr, (a, b) -> {
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
        });
        return new MezzeNonEmptyList(arr);
    }

    /**
     * Sort by comparator function: {l: 'a, r: 'a} -> Int
     * Returns negative if l < r, 0 if equal, positive if l > r
     */
    public static MezzeNonEmptyList sortBy(MezzeNonEmptyList list, MezzeFunction cmpFn,
                                                                                        com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare2(cmpFn, "l", "r");
        Object[] arr = list.getElements().clone();
        java.util.Arrays.sort(arr, (a, b) -> {
            Object result = ctx.call2(callNode, a, b);
            if (result instanceof Long) {
                return ((Long) result).intValue();
            }
            return 0;
        });
        return new MezzeNonEmptyList(arr);
    }

    // ==================== Conversion ====================

    @TruffleBoundary
    public static MezzeList toList(MezzeNonEmptyList list) {
        return MezzeList.of(list.getElements());
    }

    public static Object[] toArray(MezzeNonEmptyList list) {
        return list.getElements().clone();
    }

    // ==================== Equality & Comparison ====================

    @TruffleBoundary
    public static boolean equals(MezzeNonEmptyList list1, MezzeNonEmptyList list2) {
        if (list1.size() != list2.size()) return false;
        for (int i = 0; i < list1.size(); i++) {
            if (!deepEquals(list1.get(i), list2.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Lexicographic comparison returning -1, 0, or 1.
     */
    @TruffleBoundary
    public static long compare(MezzeNonEmptyList list1, MezzeNonEmptyList list2) {
        int minLen = Math.min(list1.size(), list2.size());
        for (int i = 0; i < minLen; i++) {
            int cmp = compareElements(list1.get(i), list2.get(i));
            if (cmp != 0) {
                return cmp < 0 ? -1L : 1L;
            }
        }
        return Integer.compare(list1.size(), list2.size());
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

    // ==================== Helper Methods ====================

    @TruffleBoundary
    private static boolean deepEquals(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a instanceof Long && b instanceof Long) return a.equals(b);
        if (a instanceof Double && b instanceof Double) return a.equals(b);
        if (a instanceof String && b instanceof String) return a.equals(b);
        if (a instanceof Boolean && b instanceof Boolean) return a.equals(b);
        return false;
    }
}
