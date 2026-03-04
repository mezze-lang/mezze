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
        return MezzeList.fromObjects(deque.toArray());
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
}
