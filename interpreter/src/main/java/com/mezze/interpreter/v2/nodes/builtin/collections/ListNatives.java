package com.mezze.interpreter.v2.nodes.builtin.collections;

import com.mezze.interpreter.v2.values.*;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

/**
 * Native List operations - ULTRA PERFORMANT.
 *
 * List in V2 uses persistent linked list semantics (MezzeList).
 * All operations are designed for JIT compilation.
 */
public final class ListNatives {

    private ListNatives() {}

    // ==================== Factory Operations ====================

    public static MezzeList empty() {
        return MezzeList.empty();
    }

    @TruffleBoundary
    public static MezzeList range(long start, long end) {
        if (start >= end) {
            return MezzeList.empty();
        }
        int size = (int) (end - start);
        Object[] elements = new Object[size];
        for (int i = 0; i < size; i++) {
            elements[i] = start + i;
        }
        return MezzeList.fromArray(elements);
    }

    @TruffleBoundary
    public static MezzeList repeat(Object element, long count) {
        if (count <= 0) {
            return MezzeList.empty();
        }
        Object[] elements = new Object[(int) count];
        for (int i = 0; i < count; i++) {
            elements[i] = element;
        }
        return MezzeList.fromArray(elements);
    }

    // ==================== Basic Operations ====================

    public static long length(MezzeList list) {
        return list.size();
    }

    public static boolean isEmpty(MezzeList list) {
        return list.isEmpty();
    }

    public static MezzeVariant head(MezzeList list) {
        if (list.isEmpty()) {
            return MezzeVariant.none();
        }
        return MezzeVariant.some(list.head());
    }

    public static MezzeList tail(MezzeList list) {
        if (list.isEmpty()) {
            return MezzeList.empty();
        }
        return list.tail();
    }

    public static MezzeVariant last(MezzeList list) {
        if (list.isEmpty()) {
            return MezzeVariant.none();
        }
        return MezzeVariant.some(list.get(list.size() - 1));
    }

    public static MezzeList init(MezzeList list) {
        if (list.size() <= 1) {
            return MezzeList.empty();
        }
        return list.init();
    }

    public static MezzeList cons(Object element, MezzeList list) {
        return list.prepend(element);
    }

    public static MezzeList append(MezzeList list, Object element) {
        return list.append(element);
    }

    @TruffleBoundary
    public static MezzeRecord uncons(MezzeList list) {
        if (list.isEmpty()) {
            return MezzeRecord.fromFields(
                new String[]{"head", "tail"},
                new Object[]{MezzeVariant.none(), MezzeList.empty()}
            );
        }
        return MezzeRecord.fromFields(
            new String[]{"head", "tail"},
            new Object[]{MezzeVariant.some(list.head()), list.tail()}
        );
    }

    public static MezzeVariant get(MezzeList list, long index) {
        if (index < 0 || index >= list.size()) {
            return MezzeVariant.none();
        }
        return MezzeVariant.some(list.get((int) index));
    }

    // ==================== Concatenation ====================

    @TruffleBoundary
    public static MezzeList concat(MezzeList list1, MezzeList list2) {
        if (list1.isEmpty()) return list2;
        if (list2.isEmpty()) return list1;

        Object[] elements = new Object[list1.size() + list2.size()];
        for (int i = 0; i < list1.size(); i++) {
            elements[i] = list1.get(i);
        }
        for (int i = 0; i < list2.size(); i++) {
            elements[list1.size() + i] = list2.get(i);
        }
        return MezzeList.fromArray(elements);
    }

    // ==================== Sublists ====================

    public static MezzeList take(MezzeList list, long n) {
        int count = (int) Math.max(0, Math.min(n, list.size()));
        return list.slice(0, count);
    }

    public static MezzeList drop(MezzeList list, long n) {
        int skip = (int) Math.max(0, Math.min(n, list.size()));
        return list.slice(skip, list.size());
    }

    // ==================== Transform Operations ====================

    @TruffleBoundary
    public static MezzeList reverse(MezzeList list) {
        if (list.size() <= 1) return list;
        Object[] elements = new Object[list.size()];
        for (int i = 0; i < list.size(); i++) {
            elements[list.size() - 1 - i] = list.get(i);
        }
        return MezzeList.fromArray(elements);
    }

    @TruffleBoundary
    public static MezzeList map(MezzeList list, MezzeFunction mapper,
                                 
                                 com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (list.isEmpty()) return list;
        Object[] result = new Object[list.size()];
        for (int i = 0; i < list.size(); i++) {
            result[i] = callNode.call(mapper.getCallTarget(),
                prepareArgs(mapper, list.get(i)));
        }
        return MezzeList.fromArray(result);
    }

    @TruffleBoundary
    public static MezzeList filter(MezzeList list, MezzeFunction predicate,
                                    
                                    com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (list.isEmpty()) return list;
        Object[] temp = new Object[list.size()];
        int count = 0;
        for (int i = 0; i < list.size(); i++) {
            Object element = list.get(i);
            Object result = callNode.call(predicate.getCallTarget(),
                prepareArgs(predicate, element));
            if (result instanceof Boolean && (Boolean) result) {
                temp[count++] = element;
            }
        }
        if (count == 0) return MezzeList.empty();
        Object[] filtered = new Object[count];
        System.arraycopy(temp, 0, filtered, 0, count);
        return MezzeList.fromArray(filtered);
    }

    @TruffleBoundary
    public static Object foldLeft(MezzeList list, Object init, MezzeFunction folder,
                                   
                                   com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        Object acc = init;
        for (int i = 0; i < list.size(); i++) {
            acc = callNode.call(folder.getCallTarget(),
                prepareArgsTwo(folder, acc, list.get(i)));
        }
        return acc;
    }

    @TruffleBoundary
    public static Object foldRight(MezzeList list, Object init, MezzeFunction folder,
                                    
                                    com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        Object acc = init;
        for (int i = list.size() - 1; i >= 0; i--) {
            acc = callNode.call(folder.getCallTarget(),
                prepareArgsTwo(folder, list.get(i), acc));
        }
        return acc;
    }

    @TruffleBoundary
    public static MezzeVariant reduce(MezzeList list, MezzeFunction reducer,
                                       
                                       com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (list.isEmpty()) {
            return MezzeVariant.none();
        }
        Object acc = list.head();
        for (int i = 1; i < list.size(); i++) {
            acc = callNode.call(reducer.getCallTarget(),
                prepareArgsTwo(reducer, acc, list.get(i)));
        }
        return MezzeVariant.some(acc);
    }

    // ==================== Search Operations ====================

    @TruffleBoundary
    public static boolean contains(MezzeList list, Object element) {
        for (int i = 0; i < list.size(); i++) {
            if (deepEquals(list.get(i), element)) {
                return true;
            }
        }
        return false;
    }

    @TruffleBoundary
    public static MezzeVariant find(MezzeList list, MezzeFunction predicate,
                                     
                                     com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        for (int i = 0; i < list.size(); i++) {
            Object element = list.get(i);
            Object result = callNode.call(predicate.getCallTarget(),
                prepareArgs(predicate, element));
            if (result instanceof Boolean && (Boolean) result) {
                return MezzeVariant.some(element);
            }
        }
        return MezzeVariant.none();
    }

    @TruffleBoundary
    public static MezzeVariant findIndex(MezzeList list, MezzeFunction predicate,
                                          
                                          com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        for (int i = 0; i < list.size(); i++) {
            Object result = callNode.call(predicate.getCallTarget(),
                prepareArgs(predicate, list.get(i)));
            if (result instanceof Boolean && (Boolean) result) {
                return MezzeVariant.some((long) i);
            }
        }
        return MezzeVariant.none();
    }

    // ==================== Predicate Operations ====================

    @TruffleBoundary
    public static boolean any(MezzeList list, MezzeFunction predicate,
                               
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
    public static boolean all(MezzeList list, MezzeFunction predicate,
                               
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

    // ==================== Partition ====================

    @TruffleBoundary
    public static MezzeRecord partition(MezzeList list, MezzeFunction predicate,
                                         
                                         com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        Object[] matching = new Object[list.size()];
        Object[] nonMatching = new Object[list.size()];
        int matchCount = 0;
        int nonMatchCount = 0;

        for (int i = 0; i < list.size(); i++) {
            Object element = list.get(i);
            Object result = callNode.call(predicate.getCallTarget(),
                prepareArgs(predicate, element));
            if (result instanceof Boolean && (Boolean) result) {
                matching[matchCount++] = element;
            } else {
                nonMatching[nonMatchCount++] = element;
            }
        }

        Object[] matchTrimmed = new Object[matchCount];
        System.arraycopy(matching, 0, matchTrimmed, 0, matchCount);
        Object[] nonMatchTrimmed = new Object[nonMatchCount];
        System.arraycopy(nonMatching, 0, nonMatchTrimmed, 0, nonMatchCount);

        return MezzeRecord.fromFields(
            new String[]{"matching", "not_matching"},
            new Object[]{
                MezzeList.fromArray(matchTrimmed),
                MezzeList.fromArray(nonMatchTrimmed)
            }
        );
    }

    // ==================== Zip/Unzip ====================

    @TruffleBoundary
    public static MezzeList zip(MezzeList list1, MezzeList list2) {
        int len = Math.min(list1.size(), list2.size());
        if (len == 0) return MezzeList.empty();

        Object[] result = new Object[len];
        for (int i = 0; i < len; i++) {
            result[i] = MezzeRecord.fromFields(
                new String[]{"first", "second"},
                new Object[]{list1.get(i), list2.get(i)}
            );
        }
        return MezzeList.fromArray(result);
    }

    @TruffleBoundary
    public static MezzeRecord unzip(MezzeList list) {
        Object[] firsts = new Object[list.size()];
        Object[] seconds = new Object[list.size()];
        for (int i = 0; i < list.size(); i++) {
            MezzeRecord pair = (MezzeRecord) list.get(i);
            // Try both naming conventions: fst/snd and first/second
            int firstIdx = pair.getFieldIndex("fst");
            if (firstIdx < 0) firstIdx = pair.getFieldIndex("first");
            int secondIdx = pair.getFieldIndex("snd");
            if (secondIdx < 0) secondIdx = pair.getFieldIndex("second");
            firsts[i] = pair.getField(firstIdx);
            seconds[i] = pair.getField(secondIdx);
        }
        // Return {fsts, snds} (plural) per stdlib convention
        return MezzeRecord.fromFields(
            new String[]{"fsts", "snds"},
            new Object[]{
                MezzeList.fromArray(firsts),
                MezzeList.fromArray(seconds)
            }
        );
    }

    // ==================== Intersperse ====================

    @TruffleBoundary
    public static MezzeList intersperse(MezzeList list, Object separator) {
        if (list.size() <= 1) return list;

        Object[] result = new Object[list.size() * 2 - 1];
        for (int i = 0; i < list.size(); i++) {
            result[i * 2] = list.get(i);
            if (i < list.size() - 1) {
                result[i * 2 + 1] = separator;
            }
        }
        return MezzeList.fromArray(result);
    }

    // ==================== Sort Operations ====================

    @TruffleBoundary
    @SuppressWarnings("unchecked")
    public static MezzeList sort(MezzeList list) {
        if (list.size() <= 1) return list;

        Object[] arr = new Object[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i);
        }

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

        return MezzeList.fromArray(arr);
    }

    @TruffleBoundary
    public static MezzeList sortBy(MezzeList list, MezzeFunction cmpFn,

                                    com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (list.size() <= 1) return list;

        // Copy to array for sorting
        Object[] arr = new Object[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i);
        }

        // Sort using comparator function that takes {a, b} record
        java.util.Arrays.sort(arr, (elemA, elemB) -> {
            Object cmpResult = callNode.call(cmpFn.getCallTarget(),
                prepareArgsComparator(cmpFn, elemA, elemB));
            if (cmpResult instanceof Long) {
                return ((Long) cmpResult).intValue();
            }
            return 0;
        });

        return MezzeList.fromArray(arr);
    }

    // ==================== Conversion ====================

    @TruffleBoundary
    public static Object[] toArray(MezzeList list) {
        Object[] result = new Object[list.size()];
        for (int i = 0; i < list.size(); i++) {
            result[i] = list.get(i);
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeList fromArray(Object[] arr) {
        return MezzeList.fromArray(arr);
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

    private static Object[] prepareArgsComparator(MezzeFunction fn, Object arg1, Object arg2) {
        Object[] closureValues = fn.getClosureValues();
        Object[] args = new Object[closureValues.length + 1];
        System.arraycopy(closureValues, 0, args, 0, closureValues.length);
        args[closureValues.length] = MezzeRecord.fromFields(
            new String[]{"a", "b"}, new Object[]{arg1, arg2}
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
        return false;
    }
}
