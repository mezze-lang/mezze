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
    public static MezzeNonEmptyList fromList(MezzeList list) {
        if (list.isEmpty()) {
            throw new RuntimeException("Cannot create NonEmptyList from empty list");
        }
        Object[] elements = new Object[list.size()];
        for (int i = 0; i < list.size(); i++) {
            elements[i] = list.get(i);
        }
        return new MezzeNonEmptyList(elements);
    }

    @TruffleBoundary
    public static MezzeNonEmptyList fromArray(Object[] arr) {
        if (arr.length == 0) {
            throw new RuntimeException("Cannot create NonEmptyList from empty array");
        }
        return new MezzeNonEmptyList(arr.clone());
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

    public static Object get(MezzeNonEmptyList list, long index) {
        int idx = (int) index;
        if (idx < 0 || idx >= list.size()) {
            throw new RuntimeException("Index out of bounds: " + idx + " for NonEmptyList of size " + list.size());
        }
        return list.get(idx);
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

    @TruffleBoundary
    public static MezzeNonEmptyList map(MezzeNonEmptyList list, MezzeFunction mapper,
                                                                                  com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        Object[] result = new Object[list.size()];
        for (int i = 0; i < list.size(); i++) {
            result[i] = callNode.call(mapper.getCallTarget(),
                prepareArgs(mapper, list.get(i)));
        }
        return new MezzeNonEmptyList(result);
    }

    @TruffleBoundary
    public static MezzeNonEmptyList flatMap(MezzeNonEmptyList list, MezzeFunction mapper,
                                                                                          com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        java.util.ArrayList<Object> result = new java.util.ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Object mapped = callNode.call(mapper.getCallTarget(),
                prepareArgs(mapper, list.get(i)));
            if (mapped instanceof MezzeNonEmptyList nel) {
                for (int j = 0; j < nel.size(); j++) {
                    result.add(nel.get(j));
                }
            }
        }
        if (result.isEmpty()) {
            // This shouldn't happen with flatMap on NEL, but handle gracefully
            throw new RuntimeException("flatMap on NonEmptyList produced empty result");
        }
        return new MezzeNonEmptyList(result.toArray());
    }

    @TruffleBoundary
    public static Object foldLeft(MezzeNonEmptyList list, Object init, MezzeFunction folder,
                                                                      com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        Object acc = init;
        for (int i = 0; i < list.size(); i++) {
            acc = callNode.call(folder.getCallTarget(),
                prepareArgsTwo(folder, acc, list.get(i)));
        }
        return acc;
    }

    @TruffleBoundary
    public static Object foldRight(MezzeNonEmptyList list, Object init, MezzeFunction folder,
                                                                        com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        Object acc = init;
        for (int i = list.size() - 1; i >= 0; i--) {
            acc = callNode.call(folder.getCallTarget(),
                prepareArgsTwo(folder, list.get(i), acc));
        }
        return acc;
    }

    @TruffleBoundary
    public static Object reduce(MezzeNonEmptyList list, MezzeFunction reducer,
                                                                  com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        Object acc = list.head();
        for (int i = 1; i < list.size(); i++) {
            acc = callNode.call(reducer.getCallTarget(),
                prepareArgsTwo(reducer, acc, list.get(i)));
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

    @TruffleBoundary
    public static boolean any(MezzeNonEmptyList list, MezzeFunction predicate,
                                                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        for (int i = 0; i < list.size(); i++) {
            Object result = callNode.call(predicate.getCallTarget(),
                prepareArgs(predicate, list.get(i)));
            if (result instanceof Boolean && (Boolean) result) {
                return true;
            }
        }
        return false;
    }

    @TruffleBoundary
    public static boolean all(MezzeNonEmptyList list, MezzeFunction predicate,
                                                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        for (int i = 0; i < list.size(); i++) {
            Object result = callNode.call(predicate.getCallTarget(),
                prepareArgs(predicate, list.get(i)));
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
    @TruffleBoundary
    public static MezzeNonEmptyList sortBy(MezzeNonEmptyList list, MezzeFunction cmpFn,
                                                                                        com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        Object[] arr = list.getElements().clone();
        java.util.Arrays.sort(arr, (a, b) -> {
            // Call the comparator function with {l, r}
            Object result = callNode.call(cmpFn.getCallTarget(),
                prepareArgsLR(cmpFn, a, b));
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

    private static Object[] prepareArgs(MezzeFunction fn, Object arg) {
        Object[] closureValues = fn.getClosureValues();
        Object[] args = new Object[closureValues.length + 1];
        System.arraycopy(closureValues, 0, args, 0, closureValues.length);
        args[closureValues.length] = MezzeRecord.fromFields(
            new String[]{"x"}, new Object[]{arg}
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

    private static Object[] prepareArgsLR(MezzeFunction fn, Object left, Object right) {
        Object[] closureValues = fn.getClosureValues();
        Object[] args = new Object[closureValues.length + 1];
        System.arraycopy(closureValues, 0, args, 0, closureValues.length);
        args[closureValues.length] = MezzeRecord.fromFields(
            new String[]{"l", "r"}, new Object[]{left, right}
        );
        return args;
    }

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
