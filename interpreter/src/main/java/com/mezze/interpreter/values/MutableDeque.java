package com.mezze.interpreter.values;

import com.mezze.datastructures.deque.MezzeDeque;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Mutable deque wrapper for safe local mutation.
 *
 * This is the runtime representation of MutDeque - a double-ended queue
 * that supports efficient push/pop from both ends.
 */
public final class MutableDeque {
    private final Deque<Object> data;

    private MutableDeque(Deque<Object> data) {
        this.data = data;
    }

    @TruffleBoundary
    public static MutableDeque fromImmutable(MezzeDeque<?> deque) {
        Deque<Object> data = new ArrayDeque<>();
        for (Object elem : deque) {
            data.addLast(elem);
        }
        return new MutableDeque(data);
    }

    @TruffleBoundary
    public static MutableDeque withCapacity(int capacity) {
        return new MutableDeque(new ArrayDeque<>(capacity));
    }

    @TruffleBoundary
    public static MutableDeque empty() {
        return new MutableDeque(new ArrayDeque<>());
    }

    @TruffleBoundary
    public MezzeDeque toImmutable() {
        MezzeDeque result = MezzeDeque.empty();
        for (Object elem : data) {
            result = result.pushBack(elem);
        }
        return result;
    }

    @TruffleBoundary
    public int size() {
        return data.size();
    }

    @TruffleBoundary
    public boolean isEmpty() {
        return data.isEmpty();
    }

    /**
     * Push element to front.
     */
    @TruffleBoundary
    public void pushFront(Object elem) {
        data.addFirst(elem);
    }

    /**
     * Push element to back.
     */
    @TruffleBoundary
    public void pushBack(Object elem) {
        data.addLast(elem);
    }

    /**
     * Pop element from front. Returns null if empty.
     */
    @TruffleBoundary
    public Object popFront() {
        return data.pollFirst();
    }

    /**
     * Pop element from back. Returns null if empty.
     */
    @TruffleBoundary
    public Object popBack() {
        return data.pollLast();
    }

    /**
     * Peek at front element without removing. Returns null if empty.
     */
    @TruffleBoundary
    public Object peekFront() {
        return data.peekFirst();
    }

    /**
     * Peek at back element without removing. Returns null if empty.
     */
    @TruffleBoundary
    public Object peekBack() {
        return data.peekLast();
    }

    /**
     * Clear all elements.
     */
    @TruffleBoundary
    public void clear() {
        data.clear();
    }

    /**
     * Get all elements as an array (front to back order).
     */
    @TruffleBoundary
    public Object[] toArray() {
        return data.toArray();
    }

    /**
     * Reverse the deque in place.
     */
    @TruffleBoundary
    public void reverse() {
        Object[] arr = data.toArray();
        data.clear();
        for (int i = arr.length - 1; i >= 0; i--) {
            data.addLast(arr[i]);
        }
    }
}
