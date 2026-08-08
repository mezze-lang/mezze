package com.mezze.interpreter.nodes.builtin.collections;

import com.mezze.datastructures.deque.MezzeDeque;
import com.mezze.datastructures.list.MezzeList;
import com.mezze.datastructures.map.MezzeMap;
import com.mezze.datastructures.seq.MezzeSeq;
import com.mezze.datastructures.set.MezzeSet;
import com.mezze.datastructures.sorted.MezzeSortedMap;
import com.mezze.datastructures.sorted.MezzeSortedSet;
import com.mezze.interpreter.values.*;
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
        return MezzeList.of(elements);
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
        return MezzeList.of(elements);
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

    public static MezzeVariant tail(MezzeList list) {
        if (list.isEmpty()) {
            return MezzeVariant.none();
        }
        return MezzeVariant.some(list.tail());
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
    public static MezzeVariant uncons(MezzeList list) {
        if (list.isEmpty()) {
            return MezzeVariant.none();
        }
        return MezzeVariant.some(MezzeRecord.fromFields(
            new String[]{"head", "tail"},
            new Object[]{list.head(), list.tail()}
        ));
    }

    public static MezzeVariant get(MezzeList list, long index) {
        if (index < 0 || index >= list.size()) {
            return MezzeVariant.none();
        }
        return MezzeVariant.some(list.get((int) index));
    }

    // ==================== Concatenation ====================

    /**
     * Concatenate two lists.
     */
    @TruffleBoundary
    public static MezzeList concat2(MezzeList list1, MezzeList list2) {
        if (list1.isEmpty()) return list2;
        if (list2.isEmpty()) return list1;

        Object[] elements = new Object[list1.size() + list2.size()];
        for (int i = 0; i < list1.size(); i++) {
            elements[i] = list1.get(i);
        }
        for (int i = 0; i < list2.size(); i++) {
            elements[list1.size() + i] = list2.get(i);
        }
        return MezzeList.of(elements);
    }

    /**
     * Flatten a list of lists into a single list.
     * This is the stdlib `concat` function: List (List 'a) -> List 'a
     */
    @TruffleBoundary
    public static MezzeList concat(MezzeList listOfLists) {
        int totalSize = 0;
        for (int i = 0; i < listOfLists.size(); i++) {
            MezzeList inner = (MezzeList) listOfLists.get(i);
            totalSize += inner.size();
        }

        if (totalSize == 0) return MezzeList.empty();

        Object[] elements = new Object[totalSize];
        int offset = 0;
        for (int i = 0; i < listOfLists.size(); i++) {
            MezzeList inner = (MezzeList) listOfLists.get(i);
            for (int j = 0; j < inner.size(); j++) {
                elements[offset++] = inner.get(j);
            }
        }
        return MezzeList.of(elements);
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
        return MezzeList.of(elements);
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
        return MezzeList.of(result);
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
        return MezzeList.of(filtered);
    }

    public static Object foldLeft(MezzeList list, Object init, MezzeFunction folder,
                                   com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        return foldTwoArg(list, init, folder, callNode,
                          /*reverse=*/false, /*startIndex=*/0, /*scanOut=*/null);
    }

    public static Object foldRight(MezzeList list, Object init, MezzeFunction folder,
                                    com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        return foldTwoArg(list, init, folder, callNode,
                          /*reverse=*/true, /*startIndex=*/0, /*scanOut=*/null);
    }

    public static MezzeVariant reduce(MezzeList list, MezzeFunction reducer,
                                       com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (list.isEmpty()) {
            return MezzeVariant.none();
        }
        Object acc = foldTwoArg(list, list.head(), reducer, callNode,
                                /*reverse=*/false, /*startIndex=*/1, /*scanOut=*/null);
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
            new String[]{"pass", "fail"},
            new Object[]{
                MezzeList.of(matchTrimmed),
                MezzeList.of(nonMatchTrimmed)
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
                new String[]{"fst", "snd"},
                new Object[]{list1.get(i), list2.get(i)}
            );
        }
        return MezzeList.of(result);
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
                MezzeList.of(firsts),
                MezzeList.of(seconds)
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
        return MezzeList.of(result);
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

        return MezzeList.of(arr);
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

        return MezzeList.of(arr);
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
        return MezzeList.of(arr);
    }

    // ==================== Helper Methods ====================

    private static Object[] prepareArgs(MezzeFunction fn, Object arg) {
        Object[] closureValues = fn.getClosureValues();
        Object[] args = new Object[closureValues.length + 1];
        System.arraycopy(closureValues, 0, args, 0, closureValues.length);
        // Use fast wrapper - no @TruffleBoundary
        args[closureValues.length] = MezzeRecord.wrapX(arg);
        return args;
    }

    /**
     * #403: shared 2-arg fold loop for foldLeft/foldRight/reduce/scan.
     * See {@link SeqNatives}'s foldTwoArg for design notes. We drop
     * {@code @TruffleBoundary} so PE can trace and inline the folder;
     * we do NOT skip the {@code MezzeRecord{acc, x}} wrap because the
     * folder's param naming is user-chosen and disambiguating positional
     * slots at runtime would require name-based dispatch.
     */
    private static Object foldTwoArg(MezzeList list, Object init, MezzeFunction folder,
                                     com.oracle.truffle.api.nodes.IndirectCallNode callNode,
                                     boolean reverse, int startIndex, Object[] scanOut) {
        Object[] closureValues = folder.getClosureValues();
        int closureLen = closureValues.length;
        com.oracle.truffle.api.CallTarget target = folder.getCallTarget();
        int size = list.size();
        int from = reverse ? size - 1 - startIndex : startIndex;
        int step = reverse ? -1 : 1;
        int end = reverse ? -1 : size;
        Object acc = init;
        for (int i = from; i != end; i += step) {
            Object[] args = new Object[closureLen + 1];
            System.arraycopy(closureValues, 0, args, 0, closureLen);
            args[closureLen] = MezzeRecord.wrapAccX(acc, list.get(i));
            acc = callNode.call(target, args);
            if (scanOut != null) scanOut[i + 1] = acc;
        }
        return acc;
    }

    private static Object[] prepareArgsTwo(MezzeFunction fn, Object arg1, Object arg2) {
        Object[] closureValues = fn.getClosureValues();
        Object[] args = new Object[closureValues.length + 1];
        System.arraycopy(closureValues, 0, args, 0, closureValues.length);
        // Use fast wrapper - no @TruffleBoundary
        args[closureValues.length] = MezzeRecord.wrapAccX(arg1, arg2);
        return args;
    }

    private static Object[] prepareArgsComparator(MezzeFunction fn, Object arg1, Object arg2) {
        Object[] closureValues = fn.getClosureValues();
        Object[] args = new Object[closureValues.length + 1];
        System.arraycopy(closureValues, 0, args, 0, closureValues.length);
        // Use fast wrapper - no @TruffleBoundary
        args[closureValues.length] = MezzeRecord.wrapAB(arg1, arg2);
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

    // ==================== Min/Max ====================

    @TruffleBoundary
    public static MezzeVariant min(MezzeList list) {
        if (list.isEmpty()) return MezzeVariant.none();
        Object minVal = list.get(0);
        for (int i = 1; i < list.size(); i++) {
            Object curr = list.get(i);
            if (compare(curr, minVal) < 0) {
                minVal = curr;
            }
        }
        return MezzeVariant.some(minVal);
    }

    @TruffleBoundary
    public static MezzeVariant max(MezzeList list) {
        if (list.isEmpty()) return MezzeVariant.none();
        Object maxVal = list.get(0);
        for (int i = 1; i < list.size(); i++) {
            Object curr = list.get(i);
            if (compare(curr, maxVal) > 0) {
                maxVal = curr;
            }
        }
        return MezzeVariant.some(maxVal);
    }

    @TruffleBoundary
    public static MezzeVariant minBy(MezzeList list, MezzeFunction keyFn,
                                     com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (list.isEmpty()) return MezzeVariant.none();
        Object minElem = list.get(0);
        Object minKey = callNode.call(keyFn.getCallTarget(), prepareArgs(keyFn, minElem));
        for (int i = 1; i < list.size(); i++) {
            Object elem = list.get(i);
            Object key = callNode.call(keyFn.getCallTarget(), prepareArgs(keyFn, elem));
            if (compare(key, minKey) < 0) {
                minElem = elem;
                minKey = key;
            }
        }
        return MezzeVariant.some(minElem);
    }

    @TruffleBoundary
    public static MezzeVariant maxBy(MezzeList list, MezzeFunction keyFn,
                                     com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (list.isEmpty()) return MezzeVariant.none();
        Object maxElem = list.get(0);
        Object maxKey = callNode.call(keyFn.getCallTarget(), prepareArgs(keyFn, maxElem));
        for (int i = 1; i < list.size(); i++) {
            Object elem = list.get(i);
            Object key = callNode.call(keyFn.getCallTarget(), prepareArgs(keyFn, elem));
            if (compare(key, maxKey) > 0) {
                maxElem = elem;
                maxKey = key;
            }
        }
        return MezzeVariant.some(maxElem);
    }

    // ==================== Sum/Product ====================

    @TruffleBoundary
    public static long sum(MezzeList list) {
        long total = 0;
        for (int i = 0; i < list.size(); i++) {
            Object elem = list.get(i);
            if (elem instanceof Long l) total += l;
            else if (elem instanceof Integer n) total += n;
        }
        return total;
    }

    @TruffleBoundary
    public static double sumFloat(MezzeList list) {
        double total = 0.0;
        for (int i = 0; i < list.size(); i++) {
            Object elem = list.get(i);
            if (elem instanceof Double d) total += d;
            else if (elem instanceof Float f) total += f;
            else if (elem instanceof Long l) total += l;
            else if (elem instanceof Integer n) total += n;
        }
        return total;
    }

    @TruffleBoundary
    public static long product(MezzeList list) {
        if (list.isEmpty()) return 1L;
        long result = 1;
        for (int i = 0; i < list.size(); i++) {
            Object elem = list.get(i);
            if (elem instanceof Long l) result *= l;
            else if (elem instanceof Integer n) result *= n;
        }
        return result;
    }

    @TruffleBoundary
    public static double productFloat(MezzeList list) {
        if (list.isEmpty()) return 1.0;
        double result = 1.0;
        for (int i = 0; i < list.size(); i++) {
            Object elem = list.get(i);
            if (elem instanceof Double d) result *= d;
            else if (elem instanceof Float f) result *= f;
            else if (elem instanceof Long l) result *= l;
            else if (elem instanceof Integer n) result *= n;
        }
        return result;
    }

    // ==================== Group By ====================

    @TruffleBoundary
    public static MezzeList groupBy(MezzeList list, MezzeFunction keyFn,
                                    com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        java.util.LinkedHashMap<Object, java.util.ArrayList<Object>> groups = new java.util.LinkedHashMap<>();
        for (int i = 0; i < list.size(); i++) {
            Object elem = list.get(i);
            Object key = callNode.call(keyFn.getCallTarget(), prepareArgs(keyFn, elem));
            groups.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(elem);
        }
        Object[] result = new Object[groups.size()];
        int idx = 0;
        for (var entry : groups.entrySet()) {
            result[idx++] = MezzeRecord.fromFields(
                new String[]{"key", "values"},
                new Object[]{entry.getKey(), MezzeList.of(entry.getValue().toArray())}
            );
        }
        return MezzeList.of(result);
    }

    // ==================== Enumerate/ForEach/Scan ====================

    @TruffleBoundary
    public static MezzeList enumerate(MezzeList list) {
        return zipWithIndex(list);
    }

    @TruffleBoundary
    public static MezzeList zipWithIndex(MezzeList list) {
        Object[] result = new Object[list.size()];
        for (int i = 0; i < list.size(); i++) {
            result[i] = MezzeRecord.fromFields(
                new String[]{"value", "index"},
                new Object[]{list.get(i), (long) i}
            );
        }
        return MezzeList.of(result);
    }

    @TruffleBoundary
    public static MezzeRecord forEach(MezzeList list, MezzeFunction fn,
                                      com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        for (int i = 0; i < list.size(); i++) {
            callNode.call(fn.getCallTarget(), prepareArgs(fn, list.get(i)));
        }
        return MezzeRecord.empty();
    }

    public static MezzeList scan(MezzeList list, Object init, MezzeFunction fn,
                                 com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        Object[] result = new Object[list.size() + 1];
        result[0] = init;
        foldTwoArg(list, init, fn, callNode, /*reverse=*/false, /*startIndex=*/0, result);
        return MezzeList.of(result);
    }

    // ==================== Split Operations ====================

    @TruffleBoundary
    public static MezzeRecord splitAt(MezzeList list, long index) {
        int idx = Math.max(0, Math.min(list.size(), (int) index));
        return MezzeRecord.fromFields(
            new String[]{"l", "r"},
            new Object[]{list.slice(0, idx), list.slice(idx, list.size())}
        );
    }

    @TruffleBoundary
    public static MezzeRecord span(MezzeList list, MezzeFunction predicate,
                                   com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        int splitIdx = 0;
        for (int i = 0; i < list.size(); i++) {
            Object result = callNode.call(predicate.getCallTarget(), prepareArgs(predicate, list.get(i)));
            if (result instanceof Boolean && (Boolean) result) {
                splitIdx++;
            } else {
                break;
            }
        }
        return MezzeRecord.fromFields(
            new String[]{"l", "r"},
            new Object[]{list.slice(0, splitIdx), list.slice(splitIdx, list.size())}
        );
    }

    // ==================== Dedup ====================

    @TruffleBoundary
    public static MezzeList dedup(MezzeList list) {
        if (list.isEmpty()) return list;
        java.util.ArrayList<Object> result = new java.util.ArrayList<>();
        Object last = list.get(0);
        result.add(last);
        for (int i = 1; i < list.size(); i++) {
            Object curr = list.get(i);
            if (!java.util.Objects.equals(curr, last)) {
                result.add(curr);
                last = curr;
            }
        }
        return MezzeList.of(result.toArray());
    }

    @TruffleBoundary
    public static MezzeList dedupBy(MezzeList list, MezzeFunction keyFn,
                                    com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (list.isEmpty()) return list;
        java.util.ArrayList<Object> result = new java.util.ArrayList<>();
        Object lastElem = list.get(0);
        Object lastKey = callNode.call(keyFn.getCallTarget(), prepareArgs(keyFn, lastElem));
        result.add(lastElem);
        for (int i = 1; i < list.size(); i++) {
            Object elem = list.get(i);
            Object key = callNode.call(keyFn.getCallTarget(), prepareArgs(keyFn, elem));
            if (!java.util.Objects.equals(key, lastKey)) {
                result.add(elem);
                lastElem = elem;
                lastKey = key;
            }
        }
        return MezzeList.of(result.toArray());
    }

    // ==================== Singleton/Nth/Fill ====================

    public static MezzeList singleton(Object element) {
        return MezzeList.of(new Object[]{element});
    }

    public static MezzeVariant nth(MezzeList list, long index) {
        return get(list, index);
    }

    @TruffleBoundary
    public static MezzeList fill(long count, Object value) {
        Object[] arr = new Object[(int) count];
        java.util.Arrays.fill(arr, value);
        return MezzeList.of(arr);
    }

    @TruffleBoundary
    public static MezzeList fillWith(long count, MezzeFunction fn,
                                     com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        Object[] arr = new Object[(int) count];
        for (int i = 0; i < count; i++) {
            arr[i] = callNode.call(fn.getCallTarget(), prepareArgsIndex(fn, (long) i));
        }
        return MezzeList.of(arr);
    }

    // ==================== Group Consecutive ====================

    @TruffleBoundary
    public static MezzeList groupConsecutive(MezzeList list) {
        if (list.isEmpty()) return MezzeList.empty();
        java.util.ArrayList<Object> groups = new java.util.ArrayList<>();
        java.util.ArrayList<Object> currentGroup = new java.util.ArrayList<>();
        Object currentKey = list.get(0);
        currentGroup.add(currentKey);

        for (int i = 1; i < list.size(); i++) {
            Object elem = list.get(i);
            if (java.util.Objects.equals(elem, currentKey)) {
                currentGroup.add(elem);
            } else {
                groups.add(MezzeList.of(currentGroup.toArray()));
                currentGroup = new java.util.ArrayList<>();
                currentGroup.add(elem);
                currentKey = elem;
            }
        }
        groups.add(MezzeList.of(currentGroup.toArray()));
        return MezzeList.of(groups.toArray());
    }

    @TruffleBoundary
    public static MezzeList groupConsecutiveBy(MezzeList list, MezzeFunction keyFn,
                                               com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (list.isEmpty()) return MezzeList.empty();
        java.util.ArrayList<Object> groups = new java.util.ArrayList<>();
        java.util.ArrayList<Object> currentGroup = new java.util.ArrayList<>();
        Object firstElem = list.get(0);
        Object currentKey = callNode.call(keyFn.getCallTarget(), prepareArgs(keyFn, firstElem));
        currentGroup.add(firstElem);

        for (int i = 1; i < list.size(); i++) {
            Object elem = list.get(i);
            Object key = callNode.call(keyFn.getCallTarget(), prepareArgs(keyFn, elem));
            if (java.util.Objects.equals(key, currentKey)) {
                currentGroup.add(elem);
            } else {
                groups.add(MezzeList.of(currentGroup.toArray()));
                currentGroup = new java.util.ArrayList<>();
                currentGroup.add(elem);
                currentKey = key;
            }
        }
        groups.add(MezzeList.of(currentGroup.toArray()));
        return MezzeList.of(groups.toArray());
    }

    // ==================== FlatMap/TakeWhile/DropWhile ====================

    @TruffleBoundary
    public static MezzeList flatMap(MezzeList list, MezzeFunction mapper,
                                    com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        java.util.ArrayList<Object> results = new java.util.ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Object mapped = callNode.call(mapper.getCallTarget(), prepareArgs(mapper, list.get(i)));
            if (mapped instanceof MezzeList innerList) {
                for (int j = 0; j < innerList.size(); j++) {
                    results.add(innerList.get(j));
                }
            }
        }
        return MezzeList.of(results.toArray());
    }

    @TruffleBoundary
    public static MezzeList takeWhile(MezzeList list, MezzeFunction predicate,
                                      com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        java.util.ArrayList<Object> results = new java.util.ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Object element = list.get(i);
            Object result = callNode.call(predicate.getCallTarget(), prepareArgs(predicate, element));
            if (result instanceof Boolean && (Boolean) result) {
                results.add(element);
            } else {
                break;
            }
        }
        return MezzeList.of(results.toArray());
    }

    @TruffleBoundary
    public static MezzeList dropWhile(MezzeList list, MezzeFunction predicate,
                                      com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        int dropCount = 0;
        for (int i = 0; i < list.size(); i++) {
            Object result = callNode.call(predicate.getCallTarget(), prepareArgs(predicate, list.get(i)));
            if (result instanceof Boolean && (Boolean) result) {
                dropCount++;
            } else {
                break;
            }
        }
        return list.slice(dropCount, list.size());
    }

    // ==================== None/Count ====================

    @TruffleBoundary
    public static boolean none(MezzeList list, MezzeFunction predicate,
                               com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        return !any(list, predicate, callNode);
    }

    @TruffleBoundary
    public static long count(MezzeList list, MezzeFunction predicate,
                             com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        long cnt = 0;
        for (int i = 0; i < list.size(); i++) {
            Object result = callNode.call(predicate.getCallTarget(), prepareArgs(predicate, list.get(i)));
            if (result instanceof Boolean && (Boolean) result) {
                cnt++;
            }
        }
        return cnt;
    }

    // ==================== Unique/Distinct/Flatten ====================

    @TruffleBoundary
    public static MezzeList unique(MezzeList list) {
        java.util.LinkedHashSet<Object> seen = new java.util.LinkedHashSet<>();
        for (int i = 0; i < list.size(); i++) {
            seen.add(list.get(i));
        }
        return MezzeList.of(seen.toArray());
    }

    @TruffleBoundary
    public static MezzeList distinct(MezzeList list) {
        return unique(list);
    }

    @TruffleBoundary
    public static MezzeList flatten(MezzeList list) {
        java.util.ArrayList<Object> results = new java.util.ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Object elem = list.get(i);
            if (elem instanceof MezzeList innerList) {
                for (int j = 0; j < innerList.size(); j++) {
                    results.add(innerList.get(j));
                }
            }
        }
        return MezzeList.of(results.toArray());
    }

    // ==================== Helper for fillWith ====================

    private static Object[] prepareArgsIndex(MezzeFunction fn, long index) {
        Object[] closureValues = fn.getClosureValues();
        Object[] args = new Object[closureValues.length + 1];
        System.arraycopy(closureValues, 0, args, 0, closureValues.length);
        args[closureValues.length] = MezzeRecord.fromFields(
            new String[]{"i"}, new Object[]{index}
        );
        return args;
    }

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
        if (a instanceof Long && b instanceof Double) {
            return Double.compare(((Long) a).doubleValue(), (Double) b);
        }
        if (a instanceof Double && b instanceof Long) {
            return Double.compare((Double) a, ((Long) b).doubleValue());
        }
        return 0;
    }

    // ==================== Task #17: Additional Operations ====================

    @TruffleBoundary
    public static boolean equals(MezzeList list1, MezzeList list2) {
        if (list1.size() != list2.size()) return false;
        for (int i = 0; i < list1.size(); i++) {
            if (!deepEquals(list1.get(i), list2.get(i))) {
                return false;
            }
        }
        return true;
    }

    @TruffleBoundary
    public static MezzeVariant indexOf(MezzeList list, Object element) {
        for (int i = 0; i < list.size(); i++) {
            if (deepEquals(list.get(i), element)) {
                return MezzeVariant.some((long) i);
            }
        }
        return MezzeVariant.none();
    }

    @TruffleBoundary
    public static MezzeVariant lastIndexOf(MezzeList list, Object element) {
        for (int i = list.size() - 1; i >= 0; i--) {
            if (deepEquals(list.get(i), element)) {
                return MezzeVariant.some((long) i);
            }
        }
        return MezzeVariant.none();
    }

    @TruffleBoundary
    public static MezzeList slice(MezzeList list, long start, long end) {
        int s = Math.max(0, Math.min(list.size(), (int) start));
        int e = Math.max(s, Math.min(list.size(), (int) end));
        return list.slice(s, e);
    }

    // ==================== Task #18: Windowing Operations ====================

    @TruffleBoundary
    public static MezzeList chunk(MezzeList list, long size) {
        if (size <= 0 || list.isEmpty()) return MezzeList.empty();
        int chunkSize = (int) size;
        java.util.ArrayList<Object> chunks = new java.util.ArrayList<>();
        for (int i = 0; i < list.size(); i += chunkSize) {
            int end = Math.min(i + chunkSize, list.size());
            chunks.add(list.slice(i, end));
        }
        return MezzeList.of(chunks.toArray());
    }

    @TruffleBoundary
    public static MezzeList windows(MezzeList list, long size) {
        if (size <= 0 || list.size() < size) return MezzeList.empty();
        int windowSize = (int) size;
        java.util.ArrayList<Object> result = new java.util.ArrayList<>();
        for (int i = 0; i <= list.size() - windowSize; i++) {
            result.add(list.slice(i, i + windowSize));
        }
        return MezzeList.of(result.toArray());
    }

    @TruffleBoundary
    public static MezzeList sliding(MezzeList list, long size, long step) {
        if (size <= 0 || step <= 0 || list.isEmpty()) return MezzeList.empty();
        int windowSize = (int) size;
        int stepSize = (int) step;
        java.util.ArrayList<Object> result = new java.util.ArrayList<>();
        for (int i = 0; i + windowSize <= list.size(); i += stepSize) {
            result.add(list.slice(i, i + windowSize));
        }
        return MezzeList.of(result.toArray());
    }

    @TruffleBoundary
    public static MezzeList chunksExact(MezzeList list, long size) {
        if (size <= 0 || list.isEmpty()) return MezzeList.empty();
        int chunkSize = (int) size;
        int numChunks = list.size() / chunkSize;
        java.util.ArrayList<Object> chunks = new java.util.ArrayList<>();
        for (int i = 0; i < numChunks; i++) {
            chunks.add(list.slice(i * chunkSize, (i + 1) * chunkSize));
        }
        return MezzeList.of(chunks.toArray());
    }

    // ==================== Task #19: Transformation Operations ====================

    @TruffleBoundary
    public static MezzeList sortByKey(MezzeList list, MezzeFunction keyFn,
                                      com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (list.size() <= 1) return list;

        // Build array of {element, key} pairs
        Object[][] pairs = new Object[list.size()][2];
        for (int i = 0; i < list.size(); i++) {
            Object elem = list.get(i);
            Object key = callNode.call(keyFn.getCallTarget(), prepareArgs(keyFn, elem));
            pairs[i][0] = elem;
            pairs[i][1] = key;
        }

        // Sort by key
        java.util.Arrays.sort(pairs, (a, b) -> compare(a[1], b[1]));

        // Extract sorted elements
        Object[] result = new Object[list.size()];
        for (int i = 0; i < list.size(); i++) {
            result[i] = pairs[i][0];
        }
        return MezzeList.of(result);
    }

    @TruffleBoundary
    public static MezzeList rotateLeft(MezzeList list, long n) {
        if (list.isEmpty()) return list;
        int size = list.size();
        int rotation = (int) (((n % size) + size) % size);
        if (rotation == 0) return list;
        Object[] result = new Object[size];
        for (int i = 0; i < size; i++) {
            result[i] = list.get((i + rotation) % size);
        }
        return MezzeList.of(result);
    }

    @TruffleBoundary
    public static MezzeList rotateRight(MezzeList list, long n) {
        if (list.isEmpty()) return list;
        int size = list.size();
        int rotation = (int) (((n % size) + size) % size);
        if (rotation == 0) return list;
        return rotateLeft(list, size - rotation);
    }

    // ==================== Task #27: Cross-Collection Conversions ====================

    @TruffleBoundary
    public static MezzeSeq toSeq(MezzeList list) {
        return MezzeSeq.fromArray(toArray(list));
    }

    @TruffleBoundary
    public static MezzeSet toSet(MezzeList list) {
        return SetNatives.fromList(list);
    }

    @TruffleBoundary
    public static MezzeDeque toDeque(MezzeList list) {
        return DequeNatives.fromList(list);
    }

    @TruffleBoundary
    public static MezzeSortedSet toSortedSet(MezzeList list) {
        return SortedSetNatives.fromList(list);
    }

    @TruffleBoundary
    public static MezzeMap toMap(MezzeList list) {
        // Expects list of {k, v} records.
        MezzeMap result = MapNatives.empty();
        for (int i = 0; i < list.size(); i++) {
            Object elem = list.get(i);
            if (elem instanceof MezzeRecord rec) {
                int keyIdx = rec.getFieldIndex("k");
                int valIdx = rec.getFieldIndex("v");
                if (keyIdx >= 0 && valIdx >= 0) {
                    result = MapNatives.insert(result, rec.getField(keyIdx), rec.getField(valIdx));
                }
            }
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeSortedMap toSortedMap(MezzeList list) {
        // Expects list of {k, v} records.
        MezzeSortedMap result = SortedMapNatives.empty();
        for (int i = 0; i < list.size(); i++) {
            Object elem = list.get(i);
            if (elem instanceof MezzeRecord rec) {
                int keyIdx = rec.getFieldIndex("k");
                int valIdx = rec.getFieldIndex("v");
                if (keyIdx >= 0 && valIdx >= 0) {
                    result = SortedMapNatives.insert(result, rec.getField(keyIdx), rec.getField(valIdx));
                }
            }
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeList fromSeq(MezzeSeq seq) {
        return MezzeList.of(seq.toArray());
    }

    @TruffleBoundary
    public static MezzeList fromSet(MezzeSet set) {
        return SetNatives.toList(set);
    }

    @TruffleBoundary
    public static MezzeList fromDeque(MezzeDeque deque) {
        return DequeNatives.toList(deque);
    }

    @TruffleBoundary
    public static MezzeList fromSortedSet(MezzeSortedSet set) {
        return SortedSetNatives.toList(set);
    }

    @TruffleBoundary
    public static MezzeList fromMap(MezzeMap map) {
        // Returns list of {key, value} records
        return MapNatives.entries(map);
    }

    @TruffleBoundary
    public static MezzeList fromSortedMap(MezzeSortedMap map) {
        // Returns list of {key, value} records (in sorted key order)
        return SortedMapNatives.entries(map);
    }

    // ==================== Task #32: Numeric Operations ====================

    @TruffleBoundary
    public static MezzeVariant average(MezzeList list) {
        if (list.isEmpty()) {
            return MezzeVariant.none();
        }
        double sum = 0.0;
        int count = 0;
        for (int i = 0; i < list.size(); i++) {
            Object elem = list.get(i);
            if (elem instanceof Long l) {
                sum += l;
                count++;
            } else if (elem instanceof Double d) {
                sum += d;
                count++;
            }
        }
        if (count == 0) return MezzeVariant.none();
        return MezzeVariant.some(sum / count);
    }

    @TruffleBoundary
    public static MezzeVariant variance(MezzeList list) {
        if (list.isEmpty()) {
            return MezzeVariant.none();
        }
        // Calculate mean
        double sum = 0.0;
        int count = 0;
        for (int i = 0; i < list.size(); i++) {
            Object elem = list.get(i);
            if (elem instanceof Long l) {
                sum += l;
                count++;
            } else if (elem instanceof Double d) {
                sum += d;
                count++;
            }
        }
        if (count == 0) return MezzeVariant.none();
        double mean = sum / count;

        // Calculate variance (population variance)
        double variance = 0.0;
        for (int i = 0; i < list.size(); i++) {
            Object elem = list.get(i);
            double val = 0.0;
            if (elem instanceof Long l) {
                val = l;
            } else if (elem instanceof Double d) {
                val = d;
            }
            double diff = val - mean;
            variance += diff * diff;
        }
        return MezzeVariant.some(variance / count);
    }

    @TruffleBoundary
    public static MezzeVariant stddev(MezzeList list) {
        MezzeVariant varianceResult = variance(list);
        if (varianceResult.isNone()) {
            return varianceResult;
        }
        double var = (Double) varianceResult.getSomeValue();
        return MezzeVariant.some(Math.sqrt(var));
    }

    @TruffleBoundary
    public static MezzeVariant median(MezzeList list) {
        if (list.isEmpty()) {
            return MezzeVariant.none();
        }
        // Convert to doubles and sort
        double[] values = new double[list.size()];
        for (int i = 0; i < list.size(); i++) {
            Object elem = list.get(i);
            if (elem instanceof Long l) {
                values[i] = l;
            } else if (elem instanceof Double d) {
                values[i] = d;
            }
        }
        java.util.Arrays.sort(values);

        int mid = list.size() / 2;
        if (list.size() % 2 == 0) {
            return MezzeVariant.some((values[mid - 1] + values[mid]) / 2.0);
        } else {
            return MezzeVariant.some(values[mid]);
        }
    }

    // ==================== Task #36: Comparison Operations ====================

    @TruffleBoundary
    public static long compare(MezzeList list1, MezzeList list2) {
        // Lexicographic comparison: -1, 0, or 1
        int minLen = Math.min(list1.size(), list2.size());
        for (int i = 0; i < minLen; i++) {
            int cmp = compareElements(list1.get(i), list2.get(i));
            if (cmp != 0) {
                return cmp < 0 ? -1L : 1L;
            }
        }
        return Integer.compare(list1.size(), list2.size());
    }

    @TruffleBoundary
    public static long lexicographicCompare(MezzeList list1, MezzeList list2, MezzeFunction cmp,
                                            com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        int minLen = Math.min(list1.size(), list2.size());
        for (int i = 0; i < minLen; i++) {
            Object result = callNode.call(cmp.getCallTarget(),
                new Object[]{MezzeRecord.fromFields(new String[]{"a", "b"}, new Object[]{list1.get(i), list2.get(i)})});
            if (result instanceof Long l && l != 0) {
                return l < 0 ? -1L : 1L;
            }
        }
        return Integer.compare(list1.size(), list2.size());
    }

    @TruffleBoundary
    public static boolean isSorted(MezzeList list) {
        if (list.size() <= 1) return true;
        for (int i = 1; i < list.size(); i++) {
            if (compareElements(list.get(i - 1), list.get(i)) > 0) {
                return false;
            }
        }
        return true;
    }

    @TruffleBoundary
    public static boolean isSortedBy(MezzeList list, MezzeFunction cmp,
                                     com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (list.size() <= 1) return true;
        for (int i = 1; i < list.size(); i++) {
            Object result = callNode.call(cmp.getCallTarget(),
                new Object[]{MezzeRecord.fromFields(new String[]{"a", "b"}, new Object[]{list.get(i - 1), list.get(i)})});
            if (result instanceof Long l && l > 0) {
                return false;
            }
        }
        return true;
    }

    @TruffleBoundary
    public static boolean isSortedDescending(MezzeList list) {
        if (list.size() <= 1) return true;
        for (int i = 1; i < list.size(); i++) {
            if (compareElements(list.get(i - 1), list.get(i)) < 0) {
                return false;
            }
        }
        return true;
    }

    // Helper for element comparison
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
        if (a instanceof Boolean && b instanceof Boolean) {
            return Boolean.compare((Boolean) a, (Boolean) b);
        }
        return Integer.compare(System.identityHashCode(a), System.identityHashCode(b));
    }

    // ==================== Task #33: Binary Search Operations ====================

    /**
     * Binary search for an element. Returns index if found, or None if not found.
     * List MUST be sorted.
     */
    @TruffleBoundary
    public static MezzeVariant binarySearch(MezzeList list, Object target) {
        int lo = 0;
        int hi = list.size() - 1;
        while (lo <= hi) {
            int mid = lo + (hi - lo) / 2;
            int cmp = compareElements(list.get(mid), target);
            if (cmp == 0) {
                return MezzeVariant.some((long) mid);
            } else if (cmp < 0) {
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return MezzeVariant.none();
    }

    /**
     * Find the insertion point for target to maintain sorted order.
     * Returns the index where target should be inserted.
     * If there are existing elements equal to target, returns the leftmost position.
     * List MUST be sorted.
     */
    @TruffleBoundary
    public static long binarySearchInsertPoint(MezzeList list, Object target) {
        int lo = 0;
        int hi = list.size();
        while (lo < hi) {
            int mid = lo + (hi - lo) / 2;
            int cmp = compareElements(list.get(mid), target);
            if (cmp < 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /**
     * Find the lower bound: first position where element >= target.
     * List MUST be sorted.
     */
    @TruffleBoundary
    public static long lowerBound(MezzeList list, Object target) {
        return binarySearchInsertPoint(list, target);
    }

    /**
     * Find the upper bound: first position where element > target.
     * Returns the index of the first element strictly greater than target.
     * List MUST be sorted.
     */
    @TruffleBoundary
    public static long upperBound(MezzeList list, Object target) {
        int lo = 0;
        int hi = list.size();
        while (lo < hi) {
            int mid = lo + (hi - lo) / 2;
            int cmp = compareElements(list.get(mid), target);
            if (cmp <= 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /**
     * Find equal range: both lower and upper bound at once.
     * Returns {lower: Int, upper: Int} representing [lower, upper) half-open interval.
     * All elements in this range are equal to target.
     * List MUST be sorted.
     */
    @TruffleBoundary
    public static MezzeRecord equalRange(MezzeList list, Object target) {
        long lower = lowerBound(list, target);
        long upper = upperBound(list, target);
        return MezzeRecord.fromFields(
            new String[]{"lower", "upper"},
            new Object[]{lower, upper}
        );
    }

    /**
     * Binary search with a custom comparator function.
     * List MUST be sorted according to the comparator.
     */
    @TruffleBoundary
    public static MezzeVariant binarySearchBy(MezzeList list, Object target, MezzeFunction cmp,
                                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        int lo = 0;
        int hi = list.size() - 1;
        while (lo <= hi) {
            int mid = lo + (hi - lo) / 2;
            Object cmpResult = callNode.call(cmp.getCallTarget(),
                new Object[]{MezzeRecord.fromFields(new String[]{"a", "b"}, new Object[]{list.get(mid), target})});
            int comparison = 0;
            if (cmpResult instanceof Long l) {
                comparison = l.intValue();
            } else if (cmpResult instanceof Integer i) {
                comparison = i;
            }
            if (comparison == 0) {
                return MezzeVariant.some((long) mid);
            } else if (comparison < 0) {
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return MezzeVariant.none();
    }
}
