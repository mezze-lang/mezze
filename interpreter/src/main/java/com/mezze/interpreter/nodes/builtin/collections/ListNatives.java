package com.mezze.interpreter.nodes.builtin.collections;

import com.mezze.datastructures.deque.MezzeDeque;
import com.mezze.datastructures.list.MezzeList;
import com.mezze.datastructures.list.MezzeLongCons;
import com.mezze.datastructures.map.MezzeMap;
import com.mezze.datastructures.seq.MezzeSeq;
import com.mezze.datastructures.set.MezzeSet;
import com.mezze.datastructures.sorted.MezzeSortedMap;
import com.mezze.datastructures.sorted.MezzeSortedSet;
import com.mezze.interpreter.values.*;
import com.oracle.truffle.api.CallTarget;
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

        Object[] src1 = list1.toArray();
        Object[] src2 = list2.toArray();
        Object[] elements = new Object[src1.length + src2.length];
        System.arraycopy(src1, 0, elements, 0, src1.length);
        System.arraycopy(src2, 0, elements, src1.length, src2.length);
        return MezzeList.of(elements);
    }

    /**
     * Flatten a list of lists into a single list.
     * This is the stdlib `concat` function: List (List 'a) -> List 'a
     */
    @TruffleBoundary
    public static MezzeList concat(MezzeList listOfLists) {
        Object[] outerSrc = listOfLists.toArray();
        int totalSize = 0;
        for (int i = 0; i < outerSrc.length; i++) {
            totalSize += ((MezzeList) outerSrc[i]).size();
        }

        if (totalSize == 0) return MezzeList.empty();

        Object[] elements = new Object[totalSize];
        int offset = 0;
        for (int i = 0; i < outerSrc.length; i++) {
            Object[] innerSrc = ((MezzeList) outerSrc[i]).toArray();
            System.arraycopy(innerSrc, 0, elements, offset, innerSrc.length);
            offset += innerSrc.length;
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
        // toArray() is O(n), then reverse-copy is O(n) — total O(n).
        Object[] src = list.toArray();
        Object[] elements = new Object[src.length];
        for (int i = 0; i < src.length; i++) {
            elements[src.length - 1 - i] = src[i];
        }
        return MezzeList.of(elements);
    }

    public static MezzeList map(MezzeList list, MezzeFunction mapper,

                                 com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (list.isEmpty()) return list;
        Object[] src = list.toArray();
        Object[] result = new Object[src.length];
        Object[] closureValues = mapper.getClosureValues();
        int closureLen = closureValues.length;
        // Scalar-ABI bypass: pass raw element without MezzeRecord wrapping
        CallTarget innerTarget = ScalarBypass.tryResolve(mapper);
        if (innerTarget != null) {
            Object[] args = new Object[closureLen + 1];
            System.arraycopy(closureValues, 0, args, 0, closureLen);
            for (int i = 0; i < src.length; i++) {
                args[closureLen] = src[i];
                result[i] = callNode.call(innerTarget, args);
            }
            return MezzeList.of(result);
        }
        // Record path for non-BDSL callees
        CallTarget target = mapper.getCallTarget();
        Object[] args = new Object[closureLen + 1];
        System.arraycopy(closureValues, 0, args, 0, closureLen);
        for (int i = 0; i < src.length; i++) {
            args[closureLen] = MezzeRecord.wrapX(src[i]);
            result[i] = callNode.call(target, args);
        }
        return MezzeList.of(result);
    }

    public static MezzeList filter(MezzeList list, MezzeFunction predicate,

                                    com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (list.isEmpty()) return list;
        Object[] src = list.toArray();
        Object[] temp = new Object[src.length];
        int count = 0;
        Object[] closureValues = predicate.getClosureValues();
        int closureLen = closureValues.length;
        // Scalar-ABI bypass
        CallTarget innerTarget = ScalarBypass.tryResolve(predicate);
        if (innerTarget != null) {
            Object[] args = new Object[closureLen + 1];
            System.arraycopy(closureValues, 0, args, 0, closureLen);
            for (int i = 0; i < src.length; i++) {
                Object element = src[i];
                args[closureLen] = element;
                Object result = callNode.call(innerTarget, args);
                if (result instanceof Boolean && (Boolean) result) {
                    temp[count++] = element;
                }
            }
        } else {
            // Record path for non-BDSL callees
            CallTarget target = predicate.getCallTarget();
            Object[] args = new Object[closureLen + 1];
            System.arraycopy(closureValues, 0, args, 0, closureLen);
            for (int i = 0; i < src.length; i++) {
                Object element = src[i];
                args[closureLen] = MezzeRecord.wrapX(element);
                Object result = callNode.call(target, args);
                if (result instanceof Boolean && (Boolean) result) {
                    temp[count++] = element;
                }
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


    public static MezzeVariant find(MezzeList list, MezzeFunction predicate,

                                     com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        Object[] closureValues = predicate.getClosureValues();
        int closureLen = closureValues.length;
        CallTarget innerTarget = ScalarBypass.tryResolve(predicate);
        CallTarget callTarget = innerTarget != null ? innerTarget : predicate.getCallTarget();
        Object[] args = new Object[closureLen + 1];
        System.arraycopy(closureValues, 0, args, 0, closureLen);
        MezzeList current = list;
        while (!current.isEmpty()) {
            Object element = current.head();
            args[closureLen] = innerTarget != null ? element : MezzeRecord.wrapX(element);
            Object result = callNode.call(callTarget, args);
            if (result instanceof Boolean && (Boolean) result) {
                return MezzeVariant.some(element);
            }
            current = current.tail();
        }
        return MezzeVariant.none();
    }

    public static MezzeVariant findIndex(MezzeList list, MezzeFunction predicate,

                                          com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        Object[] closureValues = predicate.getClosureValues();
        int closureLen = closureValues.length;
        CallTarget innerTarget = ScalarBypass.tryResolve(predicate);
        CallTarget callTarget = innerTarget != null ? innerTarget : predicate.getCallTarget();
        Object[] args = new Object[closureLen + 1];
        System.arraycopy(closureValues, 0, args, 0, closureLen);
        MezzeList current = list;
        long idx = 0;
        while (!current.isEmpty()) {
            args[closureLen] = innerTarget != null ? current.head() : MezzeRecord.wrapX(current.head());
            Object result = callNode.call(callTarget, args);
            if (result instanceof Boolean && (Boolean) result) {
                return MezzeVariant.some(idx);
            }
            current = current.tail();
            idx++;
        }
        return MezzeVariant.none();
    }

    // ==================== Predicate Operations ====================

    public static boolean any(MezzeList list, MezzeFunction predicate,

                               com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        Object[] closureValues = predicate.getClosureValues();
        int closureLen = closureValues.length;
        CallTarget innerTarget = ScalarBypass.tryResolve(predicate);
        CallTarget callTarget = innerTarget != null ? innerTarget : predicate.getCallTarget();
        Object[] args = new Object[closureLen + 1];
        System.arraycopy(closureValues, 0, args, 0, closureLen);
        MezzeList current = list;
        while (!current.isEmpty()) {
            args[closureLen] = innerTarget != null ? current.head() : MezzeRecord.wrapX(current.head());
            Object result = callNode.call(callTarget, args);
            if (result instanceof Boolean && (Boolean) result) {
                return true;
            }
            current = current.tail();
        }
        return false;
    }

    public static boolean all(MezzeList list, MezzeFunction predicate,

                               com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        Object[] closureValues = predicate.getClosureValues();
        int closureLen = closureValues.length;
        CallTarget innerTarget = ScalarBypass.tryResolve(predicate);
        CallTarget callTarget = innerTarget != null ? innerTarget : predicate.getCallTarget();
        Object[] args = new Object[closureLen + 1];
        System.arraycopy(closureValues, 0, args, 0, closureLen);
        MezzeList current = list;
        while (!current.isEmpty()) {
            args[closureLen] = innerTarget != null ? current.head() : MezzeRecord.wrapX(current.head());
            Object result = callNode.call(callTarget, args);
            if (!(result instanceof Boolean) || !(Boolean) result) {
                return false;
            }
            current = current.tail();
        }
        return true;
    }

    // ==================== Partition ====================

    public static MezzeRecord partition(MezzeList list, MezzeFunction predicate,

                                         com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare1(predicate, "x");
        Object[] src = list.toArray();
        Object[] matching = new Object[src.length];
        Object[] nonMatching = new Object[src.length];
        int matchCount = 0;
        int nonMatchCount = 0;

        for (int i = 0; i < src.length; i++) {
            Object element = src[i];
            Object result = ctx.call1(callNode, element);
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

        Object[] src1 = list1.toArray();
        Object[] src2 = list2.toArray();
        Object[] result = new Object[len];
        for (int i = 0; i < len; i++) {
            result[i] = MezzeRecord.fromFields(
                new String[]{"fst", "snd"},
                new Object[]{src1[i], src2[i]}
            );
        }
        return MezzeList.of(result);
    }

    @TruffleBoundary
    public static MezzeRecord unzip(MezzeList list) {
        Object[] src = list.toArray();
        Object[] firsts = new Object[src.length];
        Object[] seconds = new Object[src.length];
        for (int i = 0; i < src.length; i++) {
            MezzeRecord pair = (MezzeRecord) src[i];
            int firstIdx = pair.getFieldIndex("fst");
            if (firstIdx < 0) firstIdx = pair.getFieldIndex("first");
            int secondIdx = pair.getFieldIndex("snd");
            if (secondIdx < 0) secondIdx = pair.getFieldIndex("second");
            firsts[i] = pair.getField(firstIdx);
            seconds[i] = pair.getField(secondIdx);
        }
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

        Object[] src = list.toArray();
        Object[] result = new Object[src.length * 2 - 1];
        for (int i = 0; i < src.length; i++) {
            result[i * 2] = src[i];
            if (i < src.length - 1) {
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

        Object[] arr = list.toArray();

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

    public static MezzeList sortBy(MezzeList list, MezzeFunction cmpFn,

                                    com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (list.size() <= 1) return list;
        var ctx = ScalarCall.prepare2(cmpFn, "a", "b");
        Object[] arr = list.toArray();
        java.util.Arrays.sort(arr, (elemA, elemB) -> {
            Object cmpResult = ctx.call2(callNode, elemA, elemB);
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
        return list.toArray();
    }

    @TruffleBoundary
    public static MezzeList fromArray(Object[] arr) {
        return MezzeList.of(arr);
    }

    // ==================== Helper Methods ====================


    /**
     * #403: shared 2-arg fold loop for foldLeft/foldRight/reduce/scan.
     *
     * Uses toArray() for O(n) random-access instead of list.get(i) which
     * was O(n²) for cons-list. toArray() is a single O(n) pass, then the
     * fold loop is O(n) — total O(n) instead of O(n²).
     *
     * Scalar-ABI bypass: when the folder is a bypassable BDSL function,
     * passes (closures..., acc, x) directly — no MezzeRecord allocation
     * per iteration. Falls back to record-wrapping for AST/effect callees.
     */
    private static Object foldTwoArg(MezzeList list, Object init, MezzeFunction folder,
                                     com.oracle.truffle.api.nodes.IndirectCallNode callNode,
                                     boolean reverse, int startIndex, Object[] scanOut) {
        Object[] closureValues = folder.getClosureValues();
        int closureLen = closureValues.length;
        CallTarget target = folder.getCallTarget();
        Object[] elements = list.toArray();
        int size = elements.length;
        int from = reverse ? size - 1 - startIndex : startIndex;
        int step = reverse ? -1 : 1;
        int end = reverse ? -1 : size;
        Object acc = init;
        // Scalar-ABI bypass: pass raw (closures..., acc, x) without record.
        // Must respect callee's parameter order: the wrapAccX field order
        // is [acc, x] but the callback might declare {x, acc} (swapped).
        CallTarget innerTarget = ScalarBypass.tryResolve(folder);
        if (innerTarget != null) {
            String[] params = folder.getParameterNames();
            // Determine which positional slot gets acc vs element.
            // Default: acc at closureLen, element at closureLen+1
            // Swapped: element at closureLen, acc at closureLen+1
            boolean accFirst = params.length < 2 || "acc".equals(params[0]);
            int accSlot = accFirst ? closureLen : closureLen + 1;
            int elemSlot = accFirst ? closureLen + 1 : closureLen;
            Object[] args = new Object[closureLen + 2];
            System.arraycopy(closureValues, 0, args, 0, closureLen);
            for (int i = from; i != end; i += step) {
                args[accSlot] = acc;
                args[elemSlot] = elements[i];
                acc = callNode.call(innerTarget, args);
                if (scanOut != null) scanOut[i + 1] = acc;
            }
            return acc;
        }
        // Record path: wrap in MezzeRecord for non-BDSL callees
        Object[] args = new Object[closureLen + 1];
        System.arraycopy(closureValues, 0, args, 0, closureLen);
        for (int i = from; i != end; i += step) {
            args[closureLen] = MezzeRecord.wrapAccX(acc, elements[i]);
            acc = callNode.call(target, args);
            if (scanOut != null) scanOut[i + 1] = acc;
        }
        return acc;
    }




    public static MezzeVariant lastIndexOf(MezzeList list, Object element, MezzeFunction eqFn,
                                           com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var eq = ScalarCall.prepare2(eqFn, "a", "b");
        Object[] src = list.toArray();
        for (int i = src.length - 1; i >= 0; i--) {
            if (eq.callEq(callNode, src[i], element)) return MezzeVariant.some((long) i);
        }
        return MezzeVariant.none();
    }

    public static MezzeList dedup(MezzeList list, MezzeFunction eqFn, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (list.isEmpty()) return list;
        var eq = ScalarCall.prepare2(eqFn, "a", "b");
        Object[] src = list.toArray();
        java.util.ArrayList<Object> result = new java.util.ArrayList<>();
        Object last = src[0];
        result.add(last);
        for (int i = 1; i < src.length; i++) {
            Object curr = src[i];
            if (!eq.callEq(callNode, curr, last)) {
                result.add(curr);
                last = curr;
            }
        }
        return MezzeList.of(result.toArray());
    }

    public static MezzeList dedupBy(MezzeList list, MezzeFunction keyFn, MezzeFunction eqFn,
                                    com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (list.isEmpty()) return list;
        var key = ScalarCall.prepare1(keyFn, "x");
        var eq = ScalarCall.prepare2(eqFn, "a", "b");
        Object[] src = list.toArray();
        java.util.ArrayList<Object> result = new java.util.ArrayList<>();
        Object lastElem = src[0];
        Object lastKey = key.call1(callNode, lastElem);
        result.add(lastElem);
        for (int i = 1; i < src.length; i++) {
            Object elem = src[i];
            Object k = key.call1(callNode, elem);
            if (!eq.callEq(callNode, k, lastKey)) {
                result.add(elem);
                lastElem = elem;
                lastKey = k;
            }
        }
        return MezzeList.of(result.toArray());
    }

    public static MezzeList groupConsecutive(MezzeList list, MezzeFunction eqFn, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (list.isEmpty()) return MezzeList.empty();
        var eq = ScalarCall.prepare2(eqFn, "a", "b");
        Object[] src = list.toArray();
        java.util.ArrayList<Object> groups = new java.util.ArrayList<>();
        java.util.ArrayList<Object> currentGroup = new java.util.ArrayList<>();
        Object currentKey = src[0];
        currentGroup.add(currentKey);
        for (int i = 1; i < src.length; i++) {
            Object elem = src[i];
            if (eq.callEq(callNode, elem, currentKey)) {
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

    public static MezzeList groupConsecutiveBy(MezzeList list, MezzeFunction keyFn, MezzeFunction eqFn,
                                               com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (list.isEmpty()) return MezzeList.empty();
        var key = ScalarCall.prepare1(keyFn, "x");
        var eq = ScalarCall.prepare2(eqFn, "a", "b");
        Object[] src = list.toArray();
        java.util.ArrayList<Object> groups = new java.util.ArrayList<>();
        java.util.ArrayList<Object> currentGroup = new java.util.ArrayList<>();
        Object firstElem = src[0];
        Object currentKey = key.call1(callNode, firstElem);
        currentGroup.add(firstElem);
        for (int i = 1; i < src.length; i++) {
            Object elem = src[i];
            Object k = key.call1(callNode, elem);
            if (eq.callEq(callNode, k, currentKey)) {
                currentGroup.add(elem);
            } else {
                groups.add(MezzeList.of(currentGroup.toArray()));
                currentGroup = new java.util.ArrayList<>();
                currentGroup.add(elem);
                currentKey = k;
            }
        }
        groups.add(MezzeList.of(currentGroup.toArray()));
        return MezzeList.of(groups.toArray());
    }


    public static MezzeVariant minBy(MezzeList list, MezzeFunction keyFn, MezzeFunction cmpFn,
                                     com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (list.isEmpty()) return MezzeVariant.none();
        var ctx = ScalarCall.prepare1(keyFn, "x");
        var cmp = ScalarCall.prepare2(cmpFn, "a", "b");
        Object[] src = list.toArray();
        Object minElem = src[0];
        Object minKey = ctx.call1(callNode, minElem);
        for (int i = 1; i < src.length; i++) {
            Object elem = src[i];
            Object key = ctx.call1(callNode, elem);
            if (cmp.callCmp(callNode, key, minKey) < 0) {
                minElem = elem;
                minKey = key;
            }
        }
        return MezzeVariant.some(minElem);
    }

    public static MezzeVariant maxBy(MezzeList list, MezzeFunction keyFn, MezzeFunction cmpFn,
                                     com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (list.isEmpty()) return MezzeVariant.none();
        var ctx = ScalarCall.prepare1(keyFn, "x");
        var cmp = ScalarCall.prepare2(cmpFn, "a", "b");
        Object[] src = list.toArray();
        Object maxElem = src[0];
        Object maxKey = ctx.call1(callNode, maxElem);
        for (int i = 1; i < src.length; i++) {
            Object elem = src[i];
            Object key = ctx.call1(callNode, elem);
            if (cmp.callCmp(callNode, key, maxKey) > 0) {
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
        // Walk head/tail — O(n). The previous list.get(i) was O(n²).
        MezzeList current = list;
        while (!current.isEmpty()) {
            if (current instanceof MezzeLongCons lc) {
                total += lc.headLong();
            } else {
                Object elem = current.head();
                if (elem instanceof Long l) total += l;
                else if (elem instanceof Integer n) total += n;
            }
            current = current.tail();
        }
        return total;
    }

    @TruffleBoundary
    public static double sumFloat(MezzeList list) {
        double total = 0.0;
        MezzeList current = list;
        while (!current.isEmpty()) {
            Object elem = current.head();
            if (elem instanceof Double d) total += d;
            else if (elem instanceof Float f) total += f;
            else if (elem instanceof Long l) total += l;
            else if (elem instanceof Integer n) total += n;
            current = current.tail();
        }
        return total;
    }

    @TruffleBoundary
    public static long product(MezzeList list) {
        if (list.isEmpty()) return 1L;
        Object[] src = list.toArray();
        long result = 1;
        for (int i = 0; i < src.length; i++) {
            Object elem = src[i];
            if (elem instanceof Long l) result *= l;
            else if (elem instanceof Integer n) result *= n;
        }
        return result;
    }

    @TruffleBoundary
    public static double productFloat(MezzeList list) {
        if (list.isEmpty()) return 1.0;
        Object[] src = list.toArray();
        double result = 1.0;
        for (int i = 0; i < src.length; i++) {
            Object elem = src[i];
            if (elem instanceof Double d) result *= d;
            else if (elem instanceof Float f) result *= f;
            else if (elem instanceof Long l) result *= l;
            else if (elem instanceof Integer n) result *= n;
        }
        return result;
    }

    // ==================== Group By ====================

    public static MezzeList groupBy(MezzeList list, MezzeFunction keyFn,
                                    com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare1(keyFn, "x");
        Object[] src = list.toArray();
        java.util.LinkedHashMap<Object, java.util.ArrayList<Object>> groups = new java.util.LinkedHashMap<>();
        for (int i = 0; i < src.length; i++) {
            Object elem = src[i];
            Object key = ctx.call1(callNode, elem);
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
        Object[] src = list.toArray();
        Object[] result = new Object[src.length];
        for (int i = 0; i < src.length; i++) {
            result[i] = MezzeRecord.fromFields(
                new String[]{"value", "index"},
                new Object[]{src[i], (long) i}
            );
        }
        return MezzeList.of(result);
    }

    public static MezzeRecord forEach(MezzeList list, MezzeFunction fn,
                                      com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare1(fn, "x");
        Object[] src = list.toArray();
        for (int i = 0; i < src.length; i++) {
            ctx.call1(callNode, src[i]);
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

    public static MezzeRecord span(MezzeList list, MezzeFunction predicate,
                                   com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare1(predicate, "x");
        Object[] src = list.toArray();
        int splitIdx = 0;
        for (int i = 0; i < src.length; i++) {
            Object result = ctx.call1(callNode, src[i]);
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

    public static MezzeList fillWith(long count, MezzeFunction fn,
                                     com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare1(fn, "i");
        Object[] arr = new Object[(int) count];
        for (int i = 0; i < count; i++) {
            arr[i] = ctx.call1(callNode, (long) i);
        }
        return MezzeList.of(arr);
    }



    // ==================== FlatMap/TakeWhile/DropWhile ====================

    public static MezzeList flatMap(MezzeList list, MezzeFunction mapper,
                                    com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare1(mapper, "x");
        Object[] src = list.toArray();
        java.util.ArrayList<Object> results = new java.util.ArrayList<>();
        for (int i = 0; i < src.length; i++) {
            Object mapped = ctx.call1(callNode, src[i]);
            if (mapped instanceof MezzeList innerList) {
                Object[] innerSrc = innerList.toArray();
                for (int j = 0; j < innerSrc.length; j++) {
                    results.add(innerSrc[j]);
                }
            }
        }
        return MezzeList.of(results.toArray());
    }

    public static MezzeList takeWhile(MezzeList list, MezzeFunction predicate,
                                      com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare1(predicate, "x");
        Object[] src = list.toArray();
        java.util.ArrayList<Object> results = new java.util.ArrayList<>();
        for (int i = 0; i < src.length; i++) {
            Object element = src[i];
            Object result = ctx.call1(callNode, element);
            if (result instanceof Boolean && (Boolean) result) {
                results.add(element);
            } else {
                break;
            }
        }
        return MezzeList.of(results.toArray());
    }

    public static MezzeList dropWhile(MezzeList list, MezzeFunction predicate,
                                      com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare1(predicate, "x");
        Object[] src = list.toArray();
        int dropCount = 0;
        for (int i = 0; i < src.length; i++) {
            Object result = ctx.call1(callNode, src[i]);
            if (result instanceof Boolean && (Boolean) result) {
                dropCount++;
            } else {
                break;
            }
        }
        return list.slice(dropCount, list.size());
    }

    // ==================== None/Count ====================

    public static boolean none(MezzeList list, MezzeFunction predicate,
                               com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        return !any(list, predicate, callNode);
    }

    public static long count(MezzeList list, MezzeFunction predicate,
                             com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare1(predicate, "x");
        Object[] src = list.toArray();
        long cnt = 0;
        for (int i = 0; i < src.length; i++) {
            Object result = ctx.call1(callNode, src[i]);
            if (result instanceof Boolean && (Boolean) result) {
                cnt++;
            }
        }
        return cnt;
    }

    // ==================== Unique/Distinct/Flatten ====================

    @TruffleBoundary
    public static MezzeList unique(MezzeList list) {
        Object[] src = list.toArray();
        java.util.LinkedHashSet<Object> seen = new java.util.LinkedHashSet<>();
        for (int i = 0; i < src.length; i++) {
            seen.add(src[i]);
        }
        return MezzeList.of(seen.toArray());
    }

    @TruffleBoundary
    public static MezzeList distinct(MezzeList list) {
        return unique(list);
    }

    @TruffleBoundary
    public static MezzeList flatten(MezzeList list) {
        Object[] src = list.toArray();
        java.util.ArrayList<Object> results = new java.util.ArrayList<>();
        for (int i = 0; i < src.length; i++) {
            Object elem = src[i];
            if (elem instanceof MezzeList innerList) {
                Object[] innerSrc = innerList.toArray();
                for (int j = 0; j < innerSrc.length; j++) {
                    results.add(innerSrc[j]);
                }
            }
        }
        return MezzeList.of(results.toArray());
    }


    // ==================== Task #17: Additional Operations ====================

    @TruffleBoundary
    // Elementwise via the caller-supplied eq closure (the element's Eq impl), so
    // `List T == List T` agrees with `==` on the elements. Structural deepEquals
    // bottomed out in MezzeRecord.equals and ignored a custom Eq.
    public static boolean equals(MezzeList list1, MezzeList list2, MezzeFunction eqFn,
                                 com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (list1.size() != list2.size()) return false;
        var ctx = ScalarCall.prepare2(eqFn, "a", "b");
        Object[] src1 = list1.toArray();
        Object[] src2 = list2.toArray();
        for (int i = 0; i < src1.length; i++) {
            Object result = ctx.call2(callNode, src1[i], src2[i]);
            if (!(result instanceof Boolean bool) || !bool) {
                return false;
            }
        }
        return true;
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

    public static MezzeList sortByKey(MezzeList list, MezzeFunction keyFn, MezzeFunction cmpFn,
                                      com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (list.size() <= 1) return list;
        var ctx = ScalarCall.prepare1(keyFn, "x");
        var cmp = ScalarCall.prepare2(cmpFn, "a", "b");
        Object[] src = list.toArray();

        // Build array of {element, key} pairs
        Object[][] pairs = new Object[src.length][2];
        for (int i = 0; i < src.length; i++) {
            Object elem = src[i];
            Object key = ctx.call1(callNode, elem);
            pairs[i][0] = elem;
            pairs[i][1] = key;
        }

        // Sort by key
        java.util.Arrays.sort(pairs, (a, b) -> cmp.callCmp(callNode, a[1], b[1]));

        // Extract sorted elements
        Object[] result = new Object[src.length];
        for (int i = 0; i < src.length; i++) {
            result[i] = pairs[i][0];
        }
        return MezzeList.of(result);
    }

    @TruffleBoundary
    public static MezzeList rotateLeft(MezzeList list, long n) {
        if (list.isEmpty()) return list;
        Object[] src = list.toArray();
        int size = src.length;
        int rotation = (int) (((n % size) + size) % size);
        if (rotation == 0) return list;
        Object[] result = new Object[size];
        for (int i = 0; i < size; i++) {
            result[i] = src[(i + rotation) % size];
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
        Object[] src = list.toArray();
        MezzeMap result = MapNatives.empty();
        for (int i = 0; i < src.length; i++) {
            Object elem = src[i];
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
        Object[] src = list.toArray();
        MezzeSortedMap result = SortedMapNatives.empty();
        for (int i = 0; i < src.length; i++) {
            Object elem = src[i];
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
        Object[] src = list.toArray();
        double sum = 0.0;
        int count = 0;
        for (int i = 0; i < src.length; i++) {
            Object elem = src[i];
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
        Object[] src = list.toArray();
        // Calculate mean
        double sum = 0.0;
        int count = 0;
        for (int i = 0; i < src.length; i++) {
            Object elem = src[i];
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
        for (int i = 0; i < src.length; i++) {
            Object elem = src[i];
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
        Object[] src = list.toArray();
        // Convert to doubles and sort
        double[] values = new double[src.length];
        for (int i = 0; i < src.length; i++) {
            Object elem = src[i];
            if (elem instanceof Long l) {
                values[i] = l;
            } else if (elem instanceof Double d) {
                values[i] = d;
            }
        }
        java.util.Arrays.sort(values);

        int mid = src.length / 2;
        if (src.length % 2 == 0) {
            return MezzeVariant.some((values[mid - 1] + values[mid]) / 2.0);
        } else {
            return MezzeVariant.some(values[mid]);
        }
    }


    public static long lexicographicCompare(MezzeList list1, MezzeList list2, MezzeFunction cmp,
                                            com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare2(cmp, "a", "b");
        Object[] src1 = list1.toArray();
        Object[] src2 = list2.toArray();
        int minLen = Math.min(src1.length, src2.length);
        for (int i = 0; i < minLen; i++) {
            Object result = ctx.call2(callNode, src1[i], src2[i]);
            if (result instanceof Long l && l != 0) {
                return l < 0 ? -1L : 1L;
            }
        }
        return Integer.compare(src1.length, src2.length);
    }


    public static boolean isSortedBy(MezzeList list, MezzeFunction cmp,
                                     com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (list.size() <= 1) return true;
        var ctx = ScalarCall.prepare2(cmp, "a", "b");
        Object[] src = list.toArray();
        for (int i = 1; i < src.length; i++) {
            Object result = ctx.call2(callNode, src[i - 1], src[i]);
            if (result instanceof Long l && l > 0) {
                return false;
            }
        }
        return true;
    }


    // Helper for element comparison

    // ==================== Task #33: Binary Search Operations ====================

    /**
     * Binary search for an element. Returns index if found, or None if not found.
     * List MUST be sorted.
     */
    @TruffleBoundary
    public static MezzeVariant binarySearch(MezzeList list, Object target, MezzeFunction cmpFn,
                                            com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var cmpCall = ScalarCall.prepare2(cmpFn, "a", "b");
        Object[] src = list.toArray();
        int lo = 0;
        int hi = src.length - 1;
        while (lo <= hi) {
            int mid = lo + (hi - lo) / 2;
            int cmp = cmpCall.callCmp(callNode, src[mid], target);
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
    public static long binarySearchInsertPoint(MezzeList list, Object target, MezzeFunction cmpFn,
                                               com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var cmpCall = ScalarCall.prepare2(cmpFn, "a", "b");
        Object[] src = list.toArray();
        int lo = 0;
        int hi = src.length;
        while (lo < hi) {
            int mid = lo + (hi - lo) / 2;
            int cmp = cmpCall.callCmp(callNode, src[mid], target);
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
    public static long lowerBound(MezzeList list, Object target, MezzeFunction cmpFn, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        return binarySearchInsertPoint(list, target, cmpFn, callNode);
    }

    /**
     * Find the upper bound: first position where element > target.
     * Returns the index of the first element strictly greater than target.
     * List MUST be sorted.
     */
    @TruffleBoundary
    public static long upperBound(MezzeList list, Object target, MezzeFunction cmpFn, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var cmpCall = ScalarCall.prepare2(cmpFn, "a", "b");
        Object[] src = list.toArray();
        int lo = 0;
        int hi = src.length;
        while (lo < hi) {
            int mid = lo + (hi - lo) / 2;
            int cmp = cmpCall.callCmp(callNode, src[mid], target);
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
     * Returns {start: Int, end: Int} representing [start, end) half-open interval.
     * All elements in this range are equal to target.
     * List MUST be sorted.
     */
    @TruffleBoundary
    public static MezzeRecord equalRange(MezzeList list, Object target, MezzeFunction cmpFn,
                                         com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        long start = lowerBound(list, target, cmpFn, callNode);
        long end = upperBound(list, target, cmpFn, callNode);
        return MezzeRecord.fromFields(
            new String[]{"start", "end"},
            new Object[]{start, end}
        );
    }

    /**
     * Binary search with a custom comparator function.
     * List MUST be sorted according to the comparator.
     */
    public static MezzeVariant binarySearchBy(MezzeList list, Object target, MezzeFunction cmp,
                                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare2(cmp, "a", "b");
        Object[] src = list.toArray();
        int lo = 0;
        int hi = src.length - 1;
        while (lo <= hi) {
            int mid = lo + (hi - lo) / 2;
            Object cmpResult = ctx.call2(callNode, src[mid], target);
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
