package com.mezze.interpreter.v2.nodes.builtin.collections;

import com.mezze.interpreter.v2.values.*;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.util.ArrayDeque;

/**
 * Native Deque operations - ULTRA PERFORMANT.
 *
 * Deque in V2 uses ArrayDeque with persistent semantics (copy on write).
 * All operations are immutable (return new deques).
 */
public final class DequeNatives {

    private DequeNatives() {}

    /**
     * Wrapper class for Deque
     */
    public static final class MezzeDeque {
        private final ArrayDeque<Object> deque;

        public MezzeDeque() {
            this.deque = new ArrayDeque<>();
        }

        private MezzeDeque(ArrayDeque<Object> deque) {
            this.deque = deque;
        }

        public ArrayDeque<Object> getDeque() {
            return deque;
        }

        public int size() {
            return deque.size();
        }

        public boolean isEmpty() {
            return deque.isEmpty();
        }

        @TruffleBoundary
        public MezzeDeque pushFront(Object element) {
            ArrayDeque<Object> newDeque = new ArrayDeque<>(deque);
            newDeque.addFirst(element);
            return new MezzeDeque(newDeque);
        }

        @TruffleBoundary
        public MezzeDeque pushBack(Object element) {
            ArrayDeque<Object> newDeque = new ArrayDeque<>(deque);
            newDeque.addLast(element);
            return new MezzeDeque(newDeque);
        }

        @TruffleBoundary
        public MezzeDeque popFront() {
            if (deque.isEmpty()) return this;
            ArrayDeque<Object> newDeque = new ArrayDeque<>(deque);
            newDeque.removeFirst();
            return new MezzeDeque(newDeque);
        }

        @TruffleBoundary
        public MezzeDeque popBack() {
            if (deque.isEmpty()) return this;
            ArrayDeque<Object> newDeque = new ArrayDeque<>(deque);
            newDeque.removeLast();
            return new MezzeDeque(newDeque);
        }

        @TruffleBoundary
        public MezzeVariant peekFront() {
            if (deque.isEmpty()) {
                return MezzeVariant.none();
            }
            return MezzeVariant.some(deque.getFirst());
        }

        @TruffleBoundary
        public MezzeVariant peekBack() {
            if (deque.isEmpty()) {
                return MezzeVariant.none();
            }
            return MezzeVariant.some(deque.getLast());
        }

        @TruffleBoundary
        public Object[] toArray() {
            return deque.toArray();
        }
    }

    // ==================== Factory Operations ====================

    public static MezzeDeque empty() {
        return new MezzeDeque();
    }

    @TruffleBoundary
    public static MezzeDeque singleton(Object element) {
        MezzeDeque deque = new MezzeDeque();
        return deque.pushBack(element);
    }

    @TruffleBoundary
    public static MezzeDeque fromList(MezzeList list) {
        ArrayDeque<Object> deque = new ArrayDeque<>();
        for (int i = 0; i < list.size(); i++) {
            deque.addLast(list.get(i));
        }
        return new MezzeDeque(deque);
    }

    // ==================== Basic Operations ====================

    public static long length(MezzeDeque deque) {
        return deque.size();
    }

    public static boolean isEmpty(MezzeDeque deque) {
        return deque.isEmpty();
    }

    public static MezzeDeque pushFront(MezzeDeque deque, Object element) {
        return deque.pushFront(element);
    }

    public static MezzeDeque pushBack(MezzeDeque deque, Object element) {
        return deque.pushBack(element);
    }

    /**
     * Pop from front - returns Option { head: T, tail: Deque[T] }.
     * Returns None if empty, Some { val = { head, tail } } otherwise.
     */
    @TruffleBoundary
    public static MezzeVariant popFront(MezzeDeque deque) {
        if (deque.isEmpty()) {
            return MezzeVariant.none();
        }
        Object head = deque.getDeque().getFirst();
        MezzeDeque tail = deque.popFront();
        MezzeRecord result = MezzeRecord.fromFields(
            new String[]{"head", "tail"}, new Object[]{head, tail}
        );
        return MezzeVariant.some(result);
    }

    /**
     * Pop from back - returns Option { init: Deque[T], last: T }.
     * Returns None if empty, Some { val = { init, last } } otherwise.
     */
    @TruffleBoundary
    public static MezzeVariant popBack(MezzeDeque deque) {
        if (deque.isEmpty()) {
            return MezzeVariant.none();
        }
        Object last = deque.getDeque().getLast();
        MezzeDeque init = deque.popBack();
        MezzeRecord result = MezzeRecord.fromFields(
            new String[]{"init", "last"}, new Object[]{init, last}
        );
        return MezzeVariant.some(result);
    }

    public static MezzeVariant peekFront(MezzeDeque deque) {
        return deque.peekFront();
    }

    public static MezzeVariant peekBack(MezzeDeque deque) {
        return deque.peekBack();
    }

    // ==================== Transform Operations ====================

    @TruffleBoundary
    public static MezzeDeque reverse(MezzeDeque deque) {
        Object[] arr = deque.toArray();
        ArrayDeque<Object> result = new ArrayDeque<>();
        for (int i = arr.length - 1; i >= 0; i--) {
            result.addLast(arr[i]);
        }
        return new MezzeDeque(result);
    }

    @TruffleBoundary
    public static MezzeDeque map(MezzeDeque deque, MezzeFunction mapper,
                                                                    com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        ArrayDeque<Object> result = new ArrayDeque<>();
        for (Object element : deque.getDeque()) {
            Object mapped = callNode.call(mapper.getCallTarget(),
                prepareArgs(mapper, element));
            result.addLast(mapped);
        }
        return new MezzeDeque(result);
    }

    @TruffleBoundary
    public static MezzeDeque filter(MezzeDeque deque, MezzeFunction predicate,
                                                                          com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        ArrayDeque<Object> result = new ArrayDeque<>();
        for (Object element : deque.getDeque()) {
            Object res = callNode.call(predicate.getCallTarget(),
                prepareArgs(predicate, element));
            if (res instanceof Boolean && (Boolean) res) {
                result.addLast(element);
            }
        }
        return new MezzeDeque(result);
    }

    @TruffleBoundary
    public static Object fold(MezzeDeque deque, Object init, MezzeFunction folder,
                                                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        Object acc = init;
        for (Object element : deque.getDeque()) {
            acc = callNode.call(folder.getCallTarget(),
                prepareArgsTwo(folder, acc, element));
        }
        return acc;
    }

    // ==================== Concat ====================

    @TruffleBoundary
    public static MezzeDeque concat(MezzeDeque deque1, MezzeDeque deque2) {
        ArrayDeque<Object> result = new ArrayDeque<>(deque1.getDeque());
        result.addAll(deque2.getDeque());
        return new MezzeDeque(result);
    }

    // ==================== Equality ====================

    @TruffleBoundary
    public static boolean equals(MezzeDeque deque1, MezzeDeque deque2) {
        Object[] arr1 = deque1.toArray();
        Object[] arr2 = deque2.toArray();
        if (arr1.length != arr2.length) return false;
        for (int i = 0; i < arr1.length; i++) {
            if (!deepEquals(arr1[i], arr2[i])) return false;
        }
        return true;
    }

    // ==================== Conversion ====================

    @TruffleBoundary
    public static MezzeList toList(MezzeDeque deque) {
        return MezzeList.fromArray(deque.toArray());
    }

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
        for (Object element : deque.getDeque()) {
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
        for (Object element : deque.getDeque()) {
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
        for (Object element : deque.getDeque()) {
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
        for (Object element : deque.getDeque()) {
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
        for (Object elem : deque.getDeque()) {
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
        for (Object elem : deque.getDeque()) {
            if (maxVal == null || compare(elem, maxVal) > 0) {
                maxVal = elem;
            }
        }
        return MezzeVariant.some(maxVal);
    }

    @TruffleBoundary
    public static long sum(MezzeDeque deque) {
        long total = 0;
        for (Object elem : deque.getDeque()) {
            if (elem instanceof Long l) total += l;
            else if (elem instanceof Integer n) total += n;
        }
        return total;
    }

    @TruffleBoundary
    public static long product(MezzeDeque deque) {
        if (deque.isEmpty()) return 1L;
        long result = 1;
        for (Object elem : deque.getDeque()) {
            if (elem instanceof Long l) result *= l;
            else if (elem instanceof Integer n) result *= n;
        }
        return result;
    }

    // ==================== Contains ====================

    @TruffleBoundary
    public static boolean contains(MezzeDeque deque, Object element) {
        return deque.getDeque().contains(element);
    }

    // ==================== To Seq ====================

    @TruffleBoundary
    public static MezzeSeq toSeq(MezzeDeque deque) {
        return MezzeSeq.fromArray(deque.toArray());
    }

    // ==================== From Seq ====================

    @TruffleBoundary
    public static MezzeDeque fromSeq(MezzeSeq seq) {
        ArrayDeque<Object> result = new ArrayDeque<>();
        for (int i = 0; i < seq.size(); i++) {
            result.addLast(seq.get(i));
        }
        return new MezzeDeque(result);
    }

    // ==================== Additional Operations ====================

    @TruffleBoundary
    public static MezzeVariant reduce(MezzeDeque deque, MezzeFunction f,
                                      com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (deque.isEmpty()) {
            return MezzeVariant.none();
        }
        java.util.Iterator<Object> iter = deque.getDeque().iterator();
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
        ArrayDeque<Object> result = new ArrayDeque<>();
        for (Object elem : deque.getDeque()) {
            Object mapped = callNode.call(f.getCallTarget(),
                new Object[]{MezzeRecord.fromFields(new String[]{"val"}, new Object[]{elem})});
            if (mapped instanceof MezzeDeque) {
                result.addAll(((MezzeDeque) mapped).getDeque());
            }
        }
        return new MezzeDeque(result);
    }

    @TruffleBoundary
    public static MezzeRecord partition(MezzeDeque deque, MezzeFunction predicate,
                                        com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        ArrayDeque<Object> passDeque = new ArrayDeque<>();
        ArrayDeque<Object> failDeque = new ArrayDeque<>();
        for (Object elem : deque.getDeque()) {
            Object result = callNode.call(predicate.getCallTarget(),
                new Object[]{MezzeRecord.fromFields(new String[]{"val"}, new Object[]{elem})});
            if (result instanceof Boolean && (Boolean) result) {
                passDeque.addLast(elem);
            } else {
                failDeque.addLast(elem);
            }
        }
        return MezzeRecord.fromFields(
            new String[]{"pass", "fail"},
            new Object[]{new MezzeDeque(passDeque), new MezzeDeque(failDeque)}
        );
    }

    @TruffleBoundary
    public static MezzeDeque take(MezzeDeque deque, long n) {
        ArrayDeque<Object> result = new ArrayDeque<>();
        int count = 0;
        for (Object elem : deque.getDeque()) {
            if (count >= n) break;
            result.addLast(elem);
            count++;
        }
        return new MezzeDeque(result);
    }

    @TruffleBoundary
    public static MezzeDeque drop(MezzeDeque deque, long n) {
        ArrayDeque<Object> result = new ArrayDeque<>();
        int count = 0;
        for (Object elem : deque.getDeque()) {
            if (count >= n) {
                result.addLast(elem);
            }
            count++;
        }
        return new MezzeDeque(result);
    }

    @TruffleBoundary
    public static MezzeDeque unique(MezzeDeque deque) {
        java.util.LinkedHashSet<Object> seen = new java.util.LinkedHashSet<>();
        for (Object elem : deque.getDeque()) {
            seen.add(elem);
        }
        return new MezzeDeque(new ArrayDeque<>(seen));
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

        ArrayDeque<Object> result = new ArrayDeque<>();
        for (Object elem : arr) {
            result.addLast(elem);
        }
        return new MezzeDeque(result);
    }

    @TruffleBoundary
    public static double sumFloat(MezzeDeque deque) {
        double result = 0.0;
        for (Object elem : deque.getDeque()) {
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
        for (Object elem : deque.getDeque()) {
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
        for (Object elem : deque.getDeque()) {
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
        for (Object elem : deque.getDeque()) {
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
        for (Object elem : deque.getDeque()) {
            if (i == index) {
                return MezzeVariant.some(elem);
            }
            i++;
        }
        return MezzeVariant.none();
    }

    @TruffleBoundary
    public static MezzeDeque slice(MezzeDeque deque, long start, long end) {
        ArrayDeque<Object> result = new ArrayDeque<>();
        int i = 0;
        for (Object elem : deque.getDeque()) {
            if (i >= start && i < end) {
                result.addLast(elem);
            }
            if (i >= end) break;
            i++;
        }
        return new MezzeDeque(result);
    }

    @TruffleBoundary
    public static MezzeVariant indexOf(MezzeDeque deque, Object element) {
        int i = 0;
        for (Object elem : deque.getDeque()) {
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
        for (Object elem : deque.getDeque()) {
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
        ArrayDeque<Object> result = new ArrayDeque<>();
        for (Object elem : deque.getDeque()) {
            Object res = callNode.call(predicate.getCallTarget(),
                new Object[]{MezzeRecord.fromFields(new String[]{"val"}, new Object[]{elem})});
            if (res instanceof Boolean && (Boolean) res) {
                result.addLast(elem);
            } else {
                break;
            }
        }
        return new MezzeDeque(result);
    }

    @TruffleBoundary
    public static MezzeDeque dropWhile(MezzeDeque deque, MezzeFunction predicate,
                                       com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        ArrayDeque<Object> result = new ArrayDeque<>();
        boolean dropping = true;
        for (Object elem : deque.getDeque()) {
            if (dropping) {
                Object res = callNode.call(predicate.getCallTarget(),
                    new Object[]{MezzeRecord.fromFields(new String[]{"val"}, new Object[]{elem})});
                if (!(res instanceof Boolean) || !(Boolean) res) {
                    dropping = false;
                    result.addLast(elem);
                }
            } else {
                result.addLast(elem);
            }
        }
        return new MezzeDeque(result);
    }

    @TruffleBoundary
    public static MezzeRecord splitAt(MezzeDeque deque, long index) {
        ArrayDeque<Object> left = new ArrayDeque<>();
        ArrayDeque<Object> right = new ArrayDeque<>();
        int i = 0;
        for (Object elem : deque.getDeque()) {
            if (i < index) {
                left.addLast(elem);
            } else {
                right.addLast(elem);
            }
            i++;
        }
        return MezzeRecord.fromFields(
            new String[]{"left", "right"},
            new Object[]{new MezzeDeque(left), new MezzeDeque(right)}
        );
    }

    @TruffleBoundary
    public static MezzeRecord span(MezzeDeque deque, MezzeFunction predicate,
                                   com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        ArrayDeque<Object> left = new ArrayDeque<>();
        ArrayDeque<Object> right = new ArrayDeque<>();
        boolean inLeft = true;
        for (Object elem : deque.getDeque()) {
            if (inLeft) {
                Object res = callNode.call(predicate.getCallTarget(),
                    new Object[]{MezzeRecord.fromFields(new String[]{"val"}, new Object[]{elem})});
                if (res instanceof Boolean && (Boolean) res) {
                    left.addLast(elem);
                } else {
                    inLeft = false;
                    right.addLast(elem);
                }
            } else {
                right.addLast(elem);
            }
        }
        return MezzeRecord.fromFields(
            new String[]{"left", "right"},
            new Object[]{new MezzeDeque(left), new MezzeDeque(right)}
        );
    }

    // ==================== Transformation Operations ====================

    @TruffleBoundary
    public static MezzeDeque scan(MezzeDeque deque, Object init, MezzeFunction f,
                                  com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        ArrayDeque<Object> result = new ArrayDeque<>();
        Object acc = init;
        result.addLast(acc);
        for (Object elem : deque.getDeque()) {
            acc = callNode.call(f.getCallTarget(),
                new Object[]{MezzeRecord.fromFields(new String[]{"acc", "x"}, new Object[]{acc, elem})});
            result.addLast(acc);
        }
        return new MezzeDeque(result);
    }

    @TruffleBoundary
    public static MezzeDeque dedup(MezzeDeque deque) {
        ArrayDeque<Object> result = new ArrayDeque<>();
        Object prev = null;
        boolean first = true;
        for (Object elem : deque.getDeque()) {
            if (first || !deepEquals(elem, prev)) {
                result.addLast(elem);
                prev = elem;
                first = false;
            }
        }
        return new MezzeDeque(result);
    }

    @TruffleBoundary
    public static MezzeDeque dedupBy(MezzeDeque deque, MezzeFunction f,
                                     com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        ArrayDeque<Object> result = new ArrayDeque<>();
        Object prevKey = null;
        boolean first = true;
        for (Object elem : deque.getDeque()) {
            Object key = callNode.call(f.getCallTarget(),
                new Object[]{MezzeRecord.fromFields(new String[]{"val"}, new Object[]{elem})});
            if (first || !deepEquals(key, prevKey)) {
                result.addLast(elem);
                prevKey = key;
                first = false;
            }
        }
        return new MezzeDeque(result);
    }

    @TruffleBoundary
    public static MezzeDeque intersperse(MezzeDeque deque, Object sep) {
        ArrayDeque<Object> result = new ArrayDeque<>();
        boolean first = true;
        for (Object elem : deque.getDeque()) {
            if (!first) {
                result.addLast(sep);
            }
            result.addLast(elem);
            first = false;
        }
        return new MezzeDeque(result);
    }

    // ==================== Combining Operations ====================

    @TruffleBoundary
    public static MezzeDeque zip(MezzeDeque d1, MezzeDeque d2) {
        ArrayDeque<Object> result = new ArrayDeque<>();
        java.util.Iterator<Object> it1 = d1.getDeque().iterator();
        java.util.Iterator<Object> it2 = d2.getDeque().iterator();
        while (it1.hasNext() && it2.hasNext()) {
            result.addLast(MezzeRecord.fromFields(
                new String[]{"first", "second"},
                new Object[]{it1.next(), it2.next()}
            ));
        }
        return new MezzeDeque(result);
    }

    @TruffleBoundary
    public static MezzeDeque zipWithIndex(MezzeDeque deque) {
        ArrayDeque<Object> result = new ArrayDeque<>();
        long idx = 0;
        for (Object elem : deque.getDeque()) {
            result.addLast(MezzeRecord.fromFields(
                new String[]{"val", "idx"},
                new Object[]{elem, idx}
            ));
            idx++;
        }
        return new MezzeDeque(result);
    }

    @TruffleBoundary
    public static MezzeDeque rotateLeft(MezzeDeque deque, long n) {
        if (deque.isEmpty()) return deque;
        int size = deque.size();
        int shift = (int) (n % size);
        if (shift < 0) shift += size;
        if (shift == 0) return deque;

        Object[] arr = deque.toArray();
        ArrayDeque<Object> result = new ArrayDeque<>();
        for (int i = 0; i < size; i++) {
            result.addLast(arr[(i + shift) % size]);
        }
        return new MezzeDeque(result);
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

    // ==================== Task #28: Cross-Collection Conversions ====================

    @TruffleBoundary
    public static SetNatives.MezzeSet toSet(MezzeDeque deque) {
        return SetNatives.fromArray(deque.toArray());
    }

    @TruffleBoundary
    public static SortedSetNatives.MezzeSortedSet toSortedSet(MezzeDeque deque) {
        SortedSetNatives.MezzeSortedSet result = SortedSetNatives.empty();
        for (Object elem : deque.getDeque()) {
            result = SortedSetNatives.add(result, elem);
        }
        return result;
    }

    @TruffleBoundary
    public static MapNatives.MezzeMap toMap(MezzeDeque deque) {
        // Expects deque of {key, value} records
        MapNatives.MezzeMap result = MapNatives.empty();
        for (Object elem : deque.getDeque()) {
            if (elem instanceof MezzeRecord rec) {
                int keyIdx = rec.getFieldIndex("key");
                int valIdx = rec.getFieldIndex("value");
                if (keyIdx < 0) keyIdx = rec.getFieldIndex("k");
                if (valIdx < 0) valIdx = rec.getFieldIndex("v");
                if (keyIdx >= 0 && valIdx >= 0) {
                    result = MapNatives.insert(result, rec.getField(keyIdx), rec.getField(valIdx));
                }
            }
        }
        return result;
    }

    @TruffleBoundary
    public static SortedMapNatives.MezzeSortedMap toSortedMap(MezzeDeque deque) {
        // Expects deque of {key, value} records
        SortedMapNatives.MezzeSortedMap result = SortedMapNatives.empty();
        for (Object elem : deque.getDeque()) {
            if (elem instanceof MezzeRecord rec) {
                int keyIdx = rec.getFieldIndex("key");
                int valIdx = rec.getFieldIndex("value");
                if (keyIdx < 0) keyIdx = rec.getFieldIndex("k");
                if (valIdx < 0) valIdx = rec.getFieldIndex("v");
                if (keyIdx >= 0 && valIdx >= 0) {
                    result = SortedMapNatives.insert(result, rec.getField(keyIdx), rec.getField(valIdx));
                }
            }
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeDeque fromSet(SetNatives.MezzeSet set) {
        MezzeDeque result = empty();
        for (Object elem : set.getSet()) {
            result = pushBack(result, elem);
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeDeque fromSortedSet(SortedSetNatives.MezzeSortedSet set) {
        MezzeDeque result = empty();
        for (Object elem : set.getSet()) {
            result = pushBack(result, elem);
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeDeque fromMap(MapNatives.MezzeMap map) {
        // Returns deque of {key, value} records
        MezzeDeque result = empty();
        for (Object entry : map.entries()) {
            result = pushBack(result, entry);
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeDeque fromSortedMap(SortedMapNatives.MezzeSortedMap map) {
        // Returns deque of {key, value} records (in sorted key order)
        MezzeDeque result = empty();
        for (java.util.Map.Entry<Object, Object> entry : map.getMap().entrySet()) {
            MezzeRecord rec = MezzeRecord.fromFields(
                new String[]{"key", "value"},
                new Object[]{entry.getKey(), entry.getValue()}
            );
            result = pushBack(result, rec);
        }
        return result;
    }
}
