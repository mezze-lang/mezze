package com.mezze.interpreter.v2.nodes.builtin.collections;

import com.mezze.datastructures.deque.MezzeDeque;
import com.mezze.datastructures.map.MezzeMap;
import com.mezze.datastructures.list.MezzeList;
import com.mezze.datastructures.seq.MezzeSeq;
import com.mezze.datastructures.set.MezzeSet;
import com.mezze.datastructures.sorted.MezzeSortedMap;
import com.mezze.datastructures.sorted.MezzeSortedSet;
import com.mezze.interpreter.v2.values.*;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

/**
 * Native Deque operations - ULTRA PERFORMANT.
 *
 * Deque in V2 uses MezzeDeque from values package with persistent semantics.
 * Backed by datastructures module ring buffer with O(1) operations on both ends.
 * All operations are immutable (return new deques).
 */
public final class DequeNatives {

    private DequeNatives() {}

    // ==================== Factory Operations ====================

    public static MezzeDeque<Object> empty() {
        return MezzeDeque.empty();
    }

    @TruffleBoundary
    public static MezzeDeque<Object> singleton(Object element) {
        return MezzeDeque.of(element);
    }

    @TruffleBoundary
    public static MezzeDeque<Object> fromList(MezzeList list) {
        MezzeDeque<Object> result = MezzeDeque.empty();
        for (Object elem : list) {
            result = result.pushBack(elem);
        }
        return result;
    }

    // ==================== Basic Operations ====================

    public static long length(MezzeDeque<Object> deque) {
        return deque.size();
    }

    public static boolean isEmpty(MezzeDeque<Object> deque) {
        return deque.isEmpty();
    }

    @TruffleBoundary
    public static MezzeDeque<Object> pushFront(MezzeDeque<Object> deque, Object element) {
        return deque.pushFront(element);
    }

    @TruffleBoundary
    public static MezzeDeque<Object> pushBack(MezzeDeque<Object> deque, Object element) {
        return deque.pushBack(element);
    }

    /**
     * Pop from front - returns Option { head: T, tail: Deque[T] }.
     * Returns None if empty, Some { val = { head, tail } } otherwise.
     */
    @TruffleBoundary
    public static MezzeVariant popFront(MezzeDeque<Object> deque) {
        if (deque.isEmpty()) {
            return MezzeVariant.none();
        }
        MezzeDeque.PopResult<Object> result = deque.popFront();
        MezzeRecord record = MezzeRecord.fromFields(
            new String[]{"head", "tail"}, new Object[]{result.element(), result.rest()}
        );
        return MezzeVariant.some(record);
    }

    /**
     * Pop from back - returns Option { init: Deque[T], last: T }.
     * Returns None if empty, Some { val = { init, last } } otherwise.
     */
    @TruffleBoundary
    public static MezzeVariant popBack(MezzeDeque<Object> deque) {
        if (deque.isEmpty()) {
            return MezzeVariant.none();
        }
        MezzeDeque.PopResult<Object> result = deque.popBack();
        MezzeRecord record = MezzeRecord.fromFields(
            new String[]{"init", "last"}, new Object[]{result.rest(), result.element()}
        );
        return MezzeVariant.some(record);
    }

    @TruffleBoundary
    public static MezzeVariant peekFront(MezzeDeque<Object> deque) {
        if (deque.isEmpty()) {
            return MezzeVariant.none();
        }
        return MezzeVariant.some(deque.peekFront());
    }

    @TruffleBoundary
    public static MezzeVariant peekBack(MezzeDeque<Object> deque) {
        if (deque.isEmpty()) {
            return MezzeVariant.none();
        }
        return MezzeVariant.some(deque.peekBack());
    }

    // ==================== Transform Operations ====================

    @TruffleBoundary
    public static MezzeDeque reverse(MezzeDeque deque) {
        return deque.reverse();
    }

    @TruffleBoundary
    public static MezzeDeque map(MezzeDeque deque, MezzeFunction mapper,
                                 com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeDeque result = MezzeDeque.empty();
        for (Object element : deque) {
            Object mapped = callNode.call(mapper.getCallTarget(),
                prepareArgs(mapper, element));
            result = result.pushBack(mapped);
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeDeque filter(MezzeDeque deque, MezzeFunction predicate,
                                    com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeDeque result = MezzeDeque.empty();
        for (Object element : deque) {
            Object res = callNode.call(predicate.getCallTarget(),
                prepareArgs(predicate, element));
            if (res instanceof Boolean && (Boolean) res) {
                result = result.pushBack(element);
            }
        }
        return result;
    }

    @TruffleBoundary
    public static Object fold(MezzeDeque deque, Object init, MezzeFunction folder,
                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        Object acc = init;
        for (Object element : deque) {
            acc = callNode.call(folder.getCallTarget(),
                prepareArgsTwo(folder, acc, element));
        }
        return acc;
    }

    // ==================== Concat ====================

    @TruffleBoundary
    public static MezzeDeque concat(MezzeDeque deque1, MezzeDeque deque2) {
        return deque1.concat(deque2);
    }

    // ==================== Equality ====================

    @TruffleBoundary
    public static boolean equals(MezzeDeque deque1, MezzeDeque deque2) {
        return deque1.equals(deque2);
    }

    // ==================== Conversion ====================

    @TruffleBoundary
    public static MezzeList toList(MezzeDeque deque) {
        return MezzeList.of(deque.toArray());
    }

    @TruffleBoundary
    public static Object[] toArray(MezzeDeque deque) {
        return deque.toArray();
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
        return false;
    }

    // ==================== Predicate Operations ====================

    @TruffleBoundary
    public static boolean any(MezzeDeque deque, MezzeFunction predicate,
                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        for (Object element : deque) {
            Object result = callNode.call(predicate.getCallTarget(), prepareArgs(predicate, element));
            if (result instanceof Boolean && (Boolean) result) {
                return true;
            }
        }
        return false;
    }

    @TruffleBoundary
    public static boolean all(MezzeDeque deque, MezzeFunction predicate,
                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        for (Object element : deque) {
            Object result = callNode.call(predicate.getCallTarget(), prepareArgs(predicate, element));
            if (!(result instanceof Boolean) || !(Boolean) result) {
                return false;
            }
        }
        return true;
    }

    @TruffleBoundary
    public static boolean none(MezzeDeque deque, MezzeFunction predicate,
                               com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        return !any(deque, predicate, callNode);
    }

    @TruffleBoundary
    public static MezzeVariant find(MezzeDeque deque, MezzeFunction predicate,
                                    com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        for (Object element : deque) {
            Object result = callNode.call(predicate.getCallTarget(), prepareArgs(predicate, element));
            if (result instanceof Boolean && (Boolean) result) {
                return MezzeVariant.some(element);
            }
        }
        return MezzeVariant.none();
    }

    @TruffleBoundary
    public static long count(MezzeDeque deque, MezzeFunction predicate,
                             com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        long cnt = 0;
        for (Object element : deque) {
            Object result = callNode.call(predicate.getCallTarget(), prepareArgs(predicate, element));
            if (result instanceof Boolean && (Boolean) result) {
                cnt++;
            }
        }
        return cnt;
    }

    // ==================== Min/Max/Sum/Product ====================

    @TruffleBoundary
    public static MezzeVariant min(MezzeDeque deque) {
        if (deque.isEmpty()) return MezzeVariant.none();
        Object minVal = null;
        for (Object elem : deque) {
            if (minVal == null || compare(elem, minVal) < 0) {
                minVal = elem;
            }
        }
        return MezzeVariant.some(minVal);
    }

    @TruffleBoundary
    public static MezzeVariant max(MezzeDeque deque) {
        if (deque.isEmpty()) return MezzeVariant.none();
        Object maxVal = null;
        for (Object elem : deque) {
            if (maxVal == null || compare(elem, maxVal) > 0) {
                maxVal = elem;
            }
        }
        return MezzeVariant.some(maxVal);
    }

    @TruffleBoundary
    public static long sum(MezzeDeque deque) {
        long total = 0;
        for (Object elem : deque) {
            if (elem instanceof Long l) total += l;
            else if (elem instanceof Integer n) total += n;
        }
        return total;
    }

    @TruffleBoundary
    public static long product(MezzeDeque deque) {
        if (deque.isEmpty()) return 1L;
        long result = 1;
        for (Object elem : deque) {
            if (elem instanceof Long l) result *= l;
            else if (elem instanceof Integer n) result *= n;
        }
        return result;
    }

    // ==================== Contains ====================

    @TruffleBoundary
    public static boolean contains(MezzeDeque deque, Object element) {
        for (Object elem : deque) {
            if (deepEquals(elem, element)) {
                return true;
            }
        }
        return false;
    }

    // ==================== To Seq ====================

    @TruffleBoundary
    public static MezzeSeq toSeq(MezzeDeque deque) {
        return MezzeSeq.fromArray(deque.toArray());
    }

    // ==================== From Seq ====================

    @TruffleBoundary
    public static MezzeDeque fromSeq(MezzeSeq seq) {
        MezzeDeque result = MezzeDeque.empty();
        for (int i = 0; i < seq.size(); i++) {
            result = result.pushBack(seq.get(i));
        }
        return result;
    }

    // ==================== Additional Operations ====================

    @TruffleBoundary
    public static MezzeVariant reduce(MezzeDeque deque, MezzeFunction f,
                                      com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (deque.isEmpty()) {
            return MezzeVariant.none();
        }
        java.util.Iterator<Object> iter = deque.iterator();
        Object acc = iter.next();
        while (iter.hasNext()) {
            acc = callNode.call(f.getCallTarget(),
                new Object[]{MezzeRecord.fromFields(new String[]{"acc", "x"}, new Object[]{acc, iter.next()})});
        }
        return MezzeVariant.some(acc);
    }

    @TruffleBoundary
    public static MezzeDeque flatMap(MezzeDeque deque, MezzeFunction f,
                                     com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeDeque result = MezzeDeque.empty();
        for (Object elem : deque) {
            Object mapped = callNode.call(f.getCallTarget(),
                new Object[]{MezzeRecord.fromFields(new String[]{"val"}, new Object[]{elem})});
            if (mapped instanceof MezzeDeque mappedDeque) {
                for (Object item : mappedDeque) {
                    result = result.pushBack(item);
                }
            }
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeRecord partition(MezzeDeque deque, MezzeFunction predicate,
                                        com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeDeque passDeque = MezzeDeque.empty();
        MezzeDeque failDeque = MezzeDeque.empty();
        for (Object elem : deque) {
            Object result = callNode.call(predicate.getCallTarget(),
                new Object[]{MezzeRecord.fromFields(new String[]{"val"}, new Object[]{elem})});
            if (result instanceof Boolean && (Boolean) result) {
                passDeque = passDeque.pushBack(elem);
            } else {
                failDeque = failDeque.pushBack(elem);
            }
        }
        return MezzeRecord.fromFields(
            new String[]{"pass", "fail"},
            new Object[]{passDeque, failDeque}
        );
    }

    @TruffleBoundary
    public static MezzeDeque take(MezzeDeque deque, long n) {
        return deque.take((int) n);
    }

    @TruffleBoundary
    public static MezzeDeque drop(MezzeDeque deque, long n) {
        return deque.drop((int) n);
    }

    @TruffleBoundary
    public static MezzeDeque unique(MezzeDeque deque) {
        java.util.LinkedHashSet<Object> seen = new java.util.LinkedHashSet<>();
        for (Object elem : deque) {
            seen.add(elem);
        }
        MezzeDeque result = MezzeDeque.empty();
        for (Object elem : seen) {
            result = result.pushBack(elem);
        }
        return result;
    }

    @TruffleBoundary
    @SuppressWarnings("unchecked")
    public static MezzeDeque sort(MezzeDeque deque) {
        if (deque.size() <= 1) return deque;

        Object[] arr = deque.toArray();

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

        MezzeDeque result = MezzeDeque.empty();
        for (Object elem : arr) {
            result = result.pushBack(elem);
        }
        return result;
    }

    @TruffleBoundary
    public static double sumFloat(MezzeDeque deque) {
        double result = 0.0;
        for (Object elem : deque) {
            if (elem instanceof Double) {
                result += (Double) elem;
            } else if (elem instanceof Long) {
                result += (Long) elem;
            }
        }
        return result;
    }

    @TruffleBoundary
    public static double productFloat(MezzeDeque deque) {
        double result = 1.0;
        for (Object elem : deque) {
            if (elem instanceof Double) {
                result *= (Double) elem;
            } else if (elem instanceof Long) {
                result *= (Long) elem;
            }
        }
        return result;
    }

    // ==================== Min/Max By ====================

    @TruffleBoundary
    public static MezzeVariant minBy(MezzeDeque deque, MezzeFunction f,
                                     com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (deque.isEmpty()) return MezzeVariant.none();
        Object minElem = null;
        Object minKey = null;
        for (Object elem : deque) {
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
    public static MezzeVariant maxBy(MezzeDeque deque, MezzeFunction f,
                                     com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (deque.isEmpty()) return MezzeVariant.none();
        Object maxElem = null;
        Object maxKey = null;
        for (Object elem : deque) {
            Object key = callNode.call(f.getCallTarget(),
                new Object[]{MezzeRecord.fromFields(new String[]{"val"}, new Object[]{elem})});
            if (maxKey == null || compare(key, maxKey) > 0) {
                maxKey = key;
                maxElem = elem;
            }
        }
        return MezzeVariant.some(maxElem);
    }

    // ==================== Indexed Access ====================

    @TruffleBoundary
    public static MezzeVariant get(MezzeDeque deque, long index) {
        if (index < 0 || index >= deque.size()) {
            return MezzeVariant.none();
        }
        int i = 0;
        for (Object elem : deque) {
            if (i == index) {
                return MezzeVariant.some(elem);
            }
            i++;
        }
        return MezzeVariant.none();
    }

    @TruffleBoundary
    public static MezzeDeque slice(MezzeDeque deque, long start, long end) {
        MezzeDeque result = MezzeDeque.empty();
        int i = 0;
        for (Object elem : deque) {
            if (i >= start && i < end) {
                result = result.pushBack(elem);
            }
            if (i >= end) break;
            i++;
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeVariant indexOf(MezzeDeque deque, Object element) {
        int i = 0;
        for (Object elem : deque) {
            if (deepEquals(elem, element)) {
                return MezzeVariant.some((long) i);
            }
            i++;
        }
        return MezzeVariant.none();
    }

    @TruffleBoundary
    public static MezzeVariant findIndex(MezzeDeque deque, MezzeFunction predicate,
                                         com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        int i = 0;
        for (Object elem : deque) {
            Object result = callNode.call(predicate.getCallTarget(),
                new Object[]{MezzeRecord.fromFields(new String[]{"val"}, new Object[]{elem})});
            if (result instanceof Boolean && (Boolean) result) {
                return MezzeVariant.some((long) i);
            }
            i++;
        }
        return MezzeVariant.none();
    }

    // ==================== Slicing Operations ====================

    @TruffleBoundary
    public static MezzeDeque takeWhile(MezzeDeque deque, MezzeFunction predicate,
                                       com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeDeque result = MezzeDeque.empty();
        for (Object elem : deque) {
            Object res = callNode.call(predicate.getCallTarget(),
                new Object[]{MezzeRecord.fromFields(new String[]{"val"}, new Object[]{elem})});
            if (res instanceof Boolean && (Boolean) res) {
                result = result.pushBack(elem);
            } else {
                break;
            }
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeDeque dropWhile(MezzeDeque deque, MezzeFunction predicate,
                                       com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeDeque result = MezzeDeque.empty();
        boolean dropping = true;
        for (Object elem : deque) {
            if (dropping) {
                Object res = callNode.call(predicate.getCallTarget(),
                    new Object[]{MezzeRecord.fromFields(new String[]{"val"}, new Object[]{elem})});
                if (!(res instanceof Boolean) || !(Boolean) res) {
                    dropping = false;
                    result = result.pushBack(elem);
                }
            } else {
                result = result.pushBack(elem);
            }
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeRecord splitAt(MezzeDeque deque, long index) {
        MezzeDeque left = MezzeDeque.empty();
        MezzeDeque right = MezzeDeque.empty();
        int i = 0;
        for (Object elem : deque) {
            if (i < index) {
                left = left.pushBack(elem);
            } else {
                right = right.pushBack(elem);
            }
            i++;
        }
        return MezzeRecord.fromFields(
            new String[]{"left", "right"},
            new Object[]{left, right}
        );
    }

    @TruffleBoundary
    public static MezzeRecord span(MezzeDeque deque, MezzeFunction predicate,
                                   com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeDeque left = MezzeDeque.empty();
        MezzeDeque right = MezzeDeque.empty();
        boolean inLeft = true;
        for (Object elem : deque) {
            if (inLeft) {
                Object res = callNode.call(predicate.getCallTarget(),
                    new Object[]{MezzeRecord.fromFields(new String[]{"val"}, new Object[]{elem})});
                if (res instanceof Boolean && (Boolean) res) {
                    left = left.pushBack(elem);
                } else {
                    inLeft = false;
                    right = right.pushBack(elem);
                }
            } else {
                right = right.pushBack(elem);
            }
        }
        return MezzeRecord.fromFields(
            new String[]{"left", "right"},
            new Object[]{left, right}
        );
    }

    // ==================== Transformation Operations ====================

    @TruffleBoundary
    public static MezzeDeque scan(MezzeDeque deque, Object init, MezzeFunction f,
                                  com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeDeque result = MezzeDeque.empty();
        Object acc = init;
        result = result.pushBack(acc);
        for (Object elem : deque) {
            acc = callNode.call(f.getCallTarget(),
                new Object[]{MezzeRecord.fromFields(new String[]{"acc", "x"}, new Object[]{acc, elem})});
            result = result.pushBack(acc);
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeDeque dedup(MezzeDeque deque) {
        MezzeDeque result = MezzeDeque.empty();
        Object prev = null;
        boolean first = true;
        for (Object elem : deque) {
            if (first || !deepEquals(elem, prev)) {
                result = result.pushBack(elem);
                prev = elem;
                first = false;
            }
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeDeque dedupBy(MezzeDeque deque, MezzeFunction f,
                                     com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeDeque result = MezzeDeque.empty();
        Object prevKey = null;
        boolean first = true;
        for (Object elem : deque) {
            Object key = callNode.call(f.getCallTarget(),
                new Object[]{MezzeRecord.fromFields(new String[]{"val"}, new Object[]{elem})});
            if (first || !deepEquals(key, prevKey)) {
                result = result.pushBack(elem);
                prevKey = key;
                first = false;
            }
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeDeque intersperse(MezzeDeque deque, Object sep) {
        MezzeDeque result = MezzeDeque.empty();
        boolean first = true;
        for (Object elem : deque) {
            if (!first) {
                result = result.pushBack(sep);
            }
            result = result.pushBack(elem);
            first = false;
        }
        return result;
    }

    // ==================== Combining Operations ====================

    @TruffleBoundary
    public static MezzeDeque zip(MezzeDeque d1, MezzeDeque d2) {
        MezzeDeque result = MezzeDeque.empty();
        java.util.Iterator<Object> it1 = d1.iterator();
        java.util.Iterator<Object> it2 = d2.iterator();
        while (it1.hasNext() && it2.hasNext()) {
            result = result.pushBack(MezzeRecord.fromFields(
                new String[]{"first", "second"},
                new Object[]{it1.next(), it2.next()}
            ));
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeDeque zipWithIndex(MezzeDeque deque) {
        MezzeDeque result = MezzeDeque.empty();
        long idx = 0;
        for (Object elem : deque) {
            result = result.pushBack(MezzeRecord.fromFields(
                new String[]{"val", "idx"},
                new Object[]{elem, idx}
            ));
            idx++;
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeDeque rotateLeft(MezzeDeque deque, long n) {
        if (deque.isEmpty()) return deque;
        int size = deque.size();
        int shift = (int) (n % size);
        if (shift < 0) shift += size;
        if (shift == 0) return deque;

        Object[] arr = deque.toArray();
        MezzeDeque result = MezzeDeque.empty();
        for (int i = 0; i < size; i++) {
            result = result.pushBack(arr[(i + shift) % size]);
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeDeque rotateRight(MezzeDeque deque, long n) {
        return rotateLeft(deque, -n);
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

    // ==================== Cross-Collection Conversions ====================

    @TruffleBoundary
    public static MezzeSet toSet(MezzeDeque deque) {
        MezzeSet result = MezzeSet.empty();
        for (Object elem : deque) {
            result = result.addObject(elem);
        }
        return result;
    }

    @TruffleBoundary
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static MezzeSortedSet toSortedSet(MezzeDeque deque) {
        MezzeSortedSet result = MezzeSortedSet.empty();
        for (Object elem : deque) {
            result = result.addObject(elem);
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeMap toMap(MezzeDeque deque) {
        // Expects deque of {key, value} records
        MezzeMap result = MezzeMap.empty();
        for (Object elem : deque) {
            if (elem instanceof MezzeRecord rec) {
                int keyIdx = rec.getFieldIndex("key");
                int valIdx = rec.getFieldIndex("value");
                if (keyIdx < 0) keyIdx = rec.getFieldIndex("k");
                if (valIdx < 0) valIdx = rec.getFieldIndex("v");
                if (keyIdx >= 0 && valIdx >= 0) {
                    result = result.put(rec.getField(keyIdx), rec.getField(valIdx));
                }
            }
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeSortedMap toSortedMap(MezzeDeque deque) {
        // Expects deque of {key, value} records
        MezzeSortedMap result = MezzeSortedMap.empty();
        for (Object elem : deque) {
            if (elem instanceof MezzeRecord rec) {
                int keyIdx = rec.getFieldIndex("key");
                int valIdx = rec.getFieldIndex("value");
                if (keyIdx < 0) keyIdx = rec.getFieldIndex("k");
                if (valIdx < 0) valIdx = rec.getFieldIndex("v");
                if (keyIdx >= 0 && valIdx >= 0) {
                    result = result.putObject(rec.getField(keyIdx), rec.getField(valIdx));
                }
            }
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeDeque fromSet(MezzeSet set) {
        MezzeDeque result = MezzeDeque.empty();
        for (Object elem : set) {
            result = result.pushBack(elem);
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeDeque fromSortedSet(MezzeSortedSet set) {
        MezzeDeque result = MezzeDeque.empty();
        for (Object elem : set) {
            result = result.pushBack(elem);
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeDeque fromMap(MezzeMap map) {
        // Returns deque of {key, value} records
        MezzeDeque result = MezzeDeque.empty();
        for (MezzeMap.Entry entry : (Iterable<MezzeMap.Entry>) (Object) map) {
            MezzeRecord rec = MezzeRecord.fromFields(
                new String[]{"key", "value"},
                new Object[]{entry.key(), entry.value()}
            );
            result = result.pushBack(rec);
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeDeque fromSortedMap(MezzeSortedMap map) {
        // Returns deque of {key, value} records (in sorted key order)
        MezzeDeque result = MezzeDeque.empty();
        for (Object entryObj : map.rawEntries()) {
            MezzeSortedMap.RawEntry entry = (MezzeSortedMap.RawEntry) entryObj;
            MezzeRecord rec = MezzeRecord.fromFields(
                new String[]{"key", "value"},
                new Object[]{entry.key(), entry.value()}
            );
            result = result.pushBack(rec);
        }
        return result;
    }
}
