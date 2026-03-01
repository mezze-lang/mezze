package com.mezze.interpreter.v2.nodes.builtin.collections;

import com.mezze.interpreter.v2.values.*;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.util.Arrays;
import java.util.Comparator;

/**
 * Native ArrayList operations - ULTRA PERFORMANT.
 *
 * ArrayList in V2 is represented as Object[] (Java array).
 * All operations are designed for JIT compilation:
 * - Primitive operations are inline-able
 * - Complex operations use @TruffleBoundary
 * - No boxing for primitive arrays where possible
 */
public final class ArrayListNatives {

    private ArrayListNatives() {}

    // ==================== Basic Operations ====================

    public static Object[] empty() {
        return new Object[0];
    }

    public static long length(Object[] arr) {
        return arr.length;
    }

    public static boolean isEmpty(Object[] arr) {
        return arr.length == 0;
    }

    public static Object get(Object[] arr, long index) {
        int idx = (int) index;
        if (idx < 0 || idx >= arr.length) {
            return MezzeVariant.none();
        }
        return MezzeVariant.some(arr[idx]);
    }

    public static Object[] set(Object[] arr, long index, Object value) {
        int idx = (int) index;
        if (idx < 0 || idx >= arr.length) {
            return arr; // Return unchanged if out of bounds
        }
        Object[] result = arr.clone();
        result[idx] = value;
        return result;
    }

    public static Object[] add(Object[] arr, Object element) {
        Object[] result = Arrays.copyOf(arr, arr.length + 1);
        result[arr.length] = element;
        return result;
    }

    @TruffleBoundary
    public static Object[] remove(Object[] arr, long index) {
        int idx = (int) index;
        if (idx < 0 || idx >= arr.length) {
            return arr;
        }
        Object[] result = new Object[arr.length - 1];
        System.arraycopy(arr, 0, result, 0, idx);
        System.arraycopy(arr, idx + 1, result, idx, arr.length - idx - 1);
        return result;
    }

    @TruffleBoundary
    public static Object[] concat(Object[] arr1, Object[] arr2) {
        Object[] result = new Object[arr1.length + arr2.length];
        System.arraycopy(arr1, 0, result, 0, arr1.length);
        System.arraycopy(arr2, 0, result, arr1.length, arr2.length);
        return result;
    }

    // ==================== Access Operations ====================

    public static MezzeVariant head(Object[] arr) {
        if (arr.length == 0) {
            return MezzeVariant.none();
        }
        return MezzeVariant.some(arr[0]);
    }

    public static MezzeVariant last(Object[] arr) {
        if (arr.length == 0) {
            return MezzeVariant.none();
        }
        return MezzeVariant.some(arr[arr.length - 1]);
    }

    public static Object[] tail(Object[] arr) {
        if (arr.length <= 1) {
            return new Object[0];
        }
        return Arrays.copyOfRange(arr, 1, arr.length);
    }

    public static Object[] init(Object[] arr) {
        if (arr.length <= 1) {
            return new Object[0];
        }
        return Arrays.copyOf(arr, arr.length - 1);
    }

    @TruffleBoundary
    public static Object[] slice(Object[] arr, long start, long end) {
        int s = Math.max(0, (int) start);
        int e = Math.min(arr.length, (int) end);
        if (s >= e) {
            return new Object[0];
        }
        return Arrays.copyOfRange(arr, s, e);
    }

    // ==================== Search Operations ====================

    @TruffleBoundary
    public static boolean contains(Object[] arr, Object element) {
        for (Object obj : arr) {
            if (deepEquals(obj, element)) {
                return true;
            }
        }
        return false;
    }

    @TruffleBoundary
    public static MezzeVariant indexOf(Object[] arr, Object element) {
        for (int i = 0; i < arr.length; i++) {
            if (deepEquals(arr[i], element)) {
                return MezzeVariant.some((long) i);
            }
        }
        return MezzeVariant.none();
    }

    @TruffleBoundary
    public static MezzeVariant findIndex(Object[] arr, MezzeFunction predicate,
                                                                                    com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        for (int i = 0; i < arr.length; i++) {
            Object result = callNode.call(predicate.getCallTarget(),
                prepareArgs(predicate, arr[i]));
            if (result instanceof Boolean && (Boolean) result) {
                return MezzeVariant.some((long) i);
            }
        }
        return MezzeVariant.none();
    }

    @TruffleBoundary
    public static MezzeVariant find(Object[] arr, MezzeFunction predicate,
                                                                          com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        for (Object element : arr) {
            Object result = callNode.call(predicate.getCallTarget(),
                prepareArgs(predicate, element));
            if (result instanceof Boolean && (Boolean) result) {
                return MezzeVariant.some(element);
            }
        }
        return MezzeVariant.none();
    }

    // ==================== Transform Operations ====================

    @TruffleBoundary
    public static Object[] reverse(Object[] arr) {
        Object[] result = new Object[arr.length];
        for (int i = 0; i < arr.length; i++) {
            result[arr.length - 1 - i] = arr[i];
        }
        return result;
    }

    @TruffleBoundary
    public static Object[] map(Object[] arr, MezzeFunction mapper,
                                                                com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        Object[] result = new Object[arr.length];
        for (int i = 0; i < arr.length; i++) {
            result[i] = callNode.call(mapper.getCallTarget(),
                prepareArgs(mapper, arr[i]));
        }
        return result;
    }

    @TruffleBoundary
    public static Object[] filter(Object[] arr, MezzeFunction predicate,
                                                                      com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        Object[] temp = new Object[arr.length];
        int count = 0;
        for (Object element : arr) {
            Object result = callNode.call(predicate.getCallTarget(),
                prepareArgs(predicate, element));
            if (result instanceof Boolean && (Boolean) result) {
                temp[count++] = element;
            }
        }
        return Arrays.copyOf(temp, count);
    }

    @TruffleBoundary
    public static Object fold(Object[] arr, Object init, MezzeFunction folder,
                                                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        Object acc = init;
        for (Object element : arr) {
            acc = callNode.call(folder.getCallTarget(),
                prepareArgsTwo(folder, acc, element));
        }
        return acc;
    }

    @TruffleBoundary
    public static MezzeVariant reduce(Object[] arr, MezzeFunction reducer,
                                                                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (arr.length == 0) {
            return MezzeVariant.none();
        }
        Object acc = arr[0];
        for (int i = 1; i < arr.length; i++) {
            acc = callNode.call(reducer.getCallTarget(),
                prepareArgsTwo(reducer, acc, arr[i]));
        }
        return MezzeVariant.some(acc);
    }

    // ==================== Predicate Operations ====================

    @TruffleBoundary
    public static boolean any(Object[] arr, MezzeFunction predicate,
                                                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        for (Object element : arr) {
            Object result = callNode.call(predicate.getCallTarget(),
                prepareArgs(predicate, element));
            if (result instanceof Boolean && (Boolean) result) {
                return true;
            }
        }
        return false;
    }

    @TruffleBoundary
    public static boolean all(Object[] arr, MezzeFunction predicate,
                                                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        for (Object element : arr) {
            Object result = callNode.call(predicate.getCallTarget(),
                prepareArgs(predicate, element));
            if (!(result instanceof Boolean) || !(Boolean) result) {
                return false;
            }
        }
        return true;
    }

    // ==================== Zip/Unzip Operations ====================

    @TruffleBoundary
    public static Object[] zip(Object[] arr1, Object[] arr2) {
        int len = Math.min(arr1.length, arr2.length);
        Object[] result = new Object[len];
        for (int i = 0; i < len; i++) {
            result[i] = MezzeRecord.fromFields(
                new String[]{"first", "second"},
                new Object[]{arr1[i], arr2[i]}
            );
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeRecord unzip(Object[] arr) {
        Object[] firsts = new Object[arr.length];
        Object[] seconds = new Object[arr.length];
        for (int i = 0; i < arr.length; i++) {
            MezzeRecord pair = (MezzeRecord) arr[i];
            int firstIdx = pair.getFieldIndex("first");
            int secondIdx = pair.getFieldIndex("second");
            firsts[i] = pair.getField(firstIdx);
            seconds[i] = pair.getField(secondIdx);
        }
        return MezzeRecord.fromFields(
            new String[]{"first", "second"},
            new Object[]{firsts, seconds}
        );
    }

    // ==================== Partition Operations ====================

    @TruffleBoundary
    public static MezzeRecord partition(Object[] arr, MezzeFunction predicate,
                                                                                  com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        Object[] matching = new Object[arr.length];
        Object[] nonMatching = new Object[arr.length];
        int matchCount = 0;
        int nonMatchCount = 0;

        for (Object element : arr) {
            Object result = callNode.call(predicate.getCallTarget(),
                prepareArgs(predicate, element));
            if (result instanceof Boolean && (Boolean) result) {
                matching[matchCount++] = element;
            } else {
                nonMatching[nonMatchCount++] = element;
            }
        }

        return MezzeRecord.fromFields(
            new String[]{"matching", "non_matching"},
            new Object[]{
                Arrays.copyOf(matching, matchCount),
                Arrays.copyOf(nonMatching, nonMatchCount)
            }
        );
    }

    // ==================== Sort Operations ====================

    @TruffleBoundary
    @SuppressWarnings("unchecked")
    public static Object[] sort(Object[] arr) {
        Object[] result = arr.clone();
        Arrays.sort(result, (a, b) -> {
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
        return result;
    }

    @TruffleBoundary
    public static Object[] sortBy(Object[] arr, MezzeFunction keyFn,
                                                                      com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        // Extract keys first
        Object[][] pairs = new Object[arr.length][2];
        for (int i = 0; i < arr.length; i++) {
            pairs[i][0] = arr[i];
            pairs[i][1] = callNode.call(keyFn.getCallTarget(),
                prepareArgs(keyFn, arr[i]));
        }

        // Sort by keys
        Arrays.sort(pairs, (a, b) -> {
            Object keyA = a[1];
            Object keyB = b[1];
            if (keyA instanceof Long && keyB instanceof Long) {
                return Long.compare((Long) keyA, (Long) keyB);
            }
            if (keyA instanceof Double && keyB instanceof Double) {
                return Double.compare((Double) keyA, (Double) keyB);
            }
            if (keyA instanceof String && keyB instanceof String) {
                return ((String) keyA).compareTo((String) keyB);
            }
            return 0;
        });

        // Extract sorted values
        Object[] result = new Object[arr.length];
        for (int i = 0; i < arr.length; i++) {
            result[i] = pairs[i][0];
        }
        return result;
    }

    // ==================== Unique/Flatten Operations ====================

    @TruffleBoundary
    public static Object[] unique(Object[] arr) {
        java.util.LinkedHashSet<Object> seen = new java.util.LinkedHashSet<>();
        for (Object element : arr) {
            seen.add(element);
        }
        return seen.toArray();
    }

    @TruffleBoundary
    public static Object[] flatten(Object[] arr) {
        java.util.ArrayList<Object> result = new java.util.ArrayList<>();
        for (Object element : arr) {
            if (element instanceof Object[]) {
                for (Object inner : (Object[]) element) {
                    result.add(inner);
                }
            } else {
                result.add(element);
            }
        }
        return result.toArray();
    }

    // ==================== Equality ====================

    @TruffleBoundary
    public static boolean equals(Object[] arr1, Object[] arr2) {
        if (arr1.length != arr2.length) {
            return false;
        }
        for (int i = 0; i < arr1.length; i++) {
            if (!deepEquals(arr1[i], arr2[i])) {
                return false;
            }
        }
        return true;
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

    @TruffleBoundary
    private static boolean deepEquals(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a instanceof Long && b instanceof Long) return a.equals(b);
        if (a instanceof Double && b instanceof Double) return a.equals(b);
        if (a instanceof String && b instanceof String) return a.equals(b);
        if (a instanceof Boolean && b instanceof Boolean) return a.equals(b);
        if (a instanceof MezzeRecord && b instanceof MezzeRecord) {
            return ((MezzeRecord) a).equals(b);
        }
        if (a instanceof MezzeVariant && b instanceof MezzeVariant) {
            return ((MezzeVariant) a).equals(b);
        }
        if (a instanceof Object[] && b instanceof Object[]) {
            return equals((Object[]) a, (Object[]) b);
        }
        return false;
    }
}
