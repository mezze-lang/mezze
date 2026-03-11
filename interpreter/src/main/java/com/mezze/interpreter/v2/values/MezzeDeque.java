package com.mezze.interpreter.v2.values;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import java.util.Iterator;
import java.util.Objects;

/**
 * Mezze Deque backed by custom datastructures module for O(1) operations on both ends.
 *
 * The datastructures MezzeDeque provides:
 * - O(1) pushFront, pushBack
 * - O(1) peekFront, peekBack
 * - O(1) popFront, popBack
 * - Ring buffer with copy-on-write optimization
 * - Structural sharing for memory efficiency
 * - Immutable semantics with persistent updates
 *
 * All "mutating" operations return new MezzeDeque instances with shared structure.
 */
@ExportLibrary(InteropLibrary.class)
public final class MezzeDeque implements TruffleObject, Iterable<Object> {

    // Datastructures MezzeDeque backing store
    private final com.mezze.datastructures.deque.MezzeDeque<Object> delegate;

    private MezzeDeque(com.mezze.datastructures.deque.MezzeDeque<Object> delegate) {
        this.delegate = delegate;
    }

    // ===== FACTORY METHODS =====

    /**
     * Returns a new empty deque.
     * Note: We cannot use a singleton because the underlying datastructures deque
     * uses copy-on-write with buffer mutation when owned=true.
     */
    public static MezzeDeque empty() {
        return new MezzeDeque(com.mezze.datastructures.deque.MezzeDeque.empty());
    }

    public static MezzeDeque of(Object element) {
        return new MezzeDeque(com.mezze.datastructures.deque.MezzeDeque.of(element));
    }

    public static MezzeDeque of(Object e1, Object e2) {
        return new MezzeDeque(com.mezze.datastructures.deque.MezzeDeque.of(e1, e2));
    }

    public static MezzeDeque of(Object e1, Object e2, Object e3) {
        return new MezzeDeque(com.mezze.datastructures.deque.MezzeDeque.of(e1, e2, e3));
    }

    @TruffleBoundary
    public static MezzeDeque of(Object... elements) {
        if (elements.length == 0) return empty();
        return new MezzeDeque(com.mezze.datastructures.deque.MezzeDeque.of(elements));
    }

    @TruffleBoundary
    public static MezzeDeque fromIterable(Iterable<?> elements) {
        com.mezze.datastructures.deque.MezzeDeque<Object> result = com.mezze.datastructures.deque.MezzeDeque.empty();
        for (Object elem : elements) {
            result = result.pushBack(elem);
        }
        return new MezzeDeque(result);
    }

    // ===== BASIC OPERATIONS =====

    public int size() {
        return delegate.size();
    }

    public boolean isEmpty() {
        return delegate.isEmpty();
    }

    /**
     * Get the underlying datastructures deque for internal use.
     */
    public com.mezze.datastructures.deque.MezzeDeque<Object> getDelegate() {
        return delegate;
    }

    // ===== FRONT OPERATIONS =====

    /**
     * Get the front element without removing it.
     * @throws java.util.NoSuchElementException if deque is empty
     */
    @TruffleBoundary
    public Object peekFront() {
        return delegate.peekFront();
    }

    /**
     * Get the front element, or null if empty.
     */
    @TruffleBoundary
    public Object peekFrontOrNull() {
        return delegate.isEmpty() ? null : delegate.peekFront();
    }

    /**
     * Add element to front.
     */
    @TruffleBoundary
    public MezzeDeque pushFront(Object element) {
        return new MezzeDeque(delegate.pushFront(element));
    }

    /**
     * Remove and return front element.
     * Returns a tuple of (element, newDeque), or null if empty.
     */
    @TruffleBoundary
    public Object[] popFront() {
        if (delegate.isEmpty()) return null;
        com.mezze.datastructures.deque.MezzeDeque.PopResult<Object> result = delegate.popFront();
        MezzeDeque newDeque = result.rest().isEmpty() ? empty() : new MezzeDeque(result.rest());
        return new Object[]{result.element(), newDeque};
    }

    // ===== BACK OPERATIONS =====

    /**
     * Get the back element without removing it.
     * @throws java.util.NoSuchElementException if deque is empty
     */
    @TruffleBoundary
    public Object peekBack() {
        return delegate.peekBack();
    }

    /**
     * Get the back element, or null if empty.
     */
    @TruffleBoundary
    public Object peekBackOrNull() {
        return delegate.isEmpty() ? null : delegate.peekBack();
    }

    /**
     * Add element to back.
     */
    @TruffleBoundary
    public MezzeDeque pushBack(Object element) {
        return new MezzeDeque(delegate.pushBack(element));
    }

    /**
     * Remove and return back element.
     * Returns a tuple of (newDeque, element), or null if empty.
     */
    @TruffleBoundary
    public Object[] popBack() {
        if (delegate.isEmpty()) return null;
        com.mezze.datastructures.deque.MezzeDeque.PopResult<Object> result = delegate.popBack();
        MezzeDeque newDeque = result.rest().isEmpty() ? empty() : new MezzeDeque(result.rest());
        return new Object[]{newDeque, result.element()};
    }

    // ===== BULK OPERATIONS =====

    /**
     * Concatenate two deques.
     */
    @TruffleBoundary
    public MezzeDeque concat(MezzeDeque other) {
        return new MezzeDeque(delegate.concat(other.delegate));
    }

    /**
     * Reverse the deque.
     */
    @TruffleBoundary
    public MezzeDeque reverse() {
        return new MezzeDeque(delegate.reverse());
    }

    /**
     * Take first n elements.
     */
    @TruffleBoundary
    public MezzeDeque take(int n) {
        // Build a new deque with first n elements
        if (n <= 0 || delegate.isEmpty()) return empty();
        com.mezze.datastructures.deque.MezzeDeque<Object> result = com.mezze.datastructures.deque.MezzeDeque.empty();
        int count = 0;
        for (Object elem : delegate) {
            if (count >= n) break;
            result = result.pushBack(elem);
            count++;
        }
        return new MezzeDeque(result);
    }

    /**
     * Drop first n elements.
     */
    @TruffleBoundary
    public MezzeDeque drop(int n) {
        if (n <= 0) return this;
        if (n >= delegate.size()) return empty();
        com.mezze.datastructures.deque.MezzeDeque<Object> result = delegate;
        for (int i = 0; i < n && !result.isEmpty(); i++) {
            result = result.popFront().rest();
        }
        return result.isEmpty() ? empty() : new MezzeDeque(result);
    }

    // ===== CONVERSION =====

    @TruffleBoundary
    public java.util.List<Object> toJavaList() {
        java.util.ArrayList<Object> result = new java.util.ArrayList<>(delegate.size());
        for (Object elem : delegate) {
            result.add(elem);
        }
        return result;
    }

    @TruffleBoundary
    public Object[] toJavaArray() {
        return delegate.toArray();
    }

    @Override
    @TruffleBoundary
    public Iterator<Object> iterator() {
        return delegate.iterator();
    }

    // ===== INTEROP LIBRARY =====

    @ExportMessage
    boolean hasArrayElements() {
        return true;
    }

    @ExportMessage
    long getArraySize() {
        return delegate.size();
    }

    @ExportMessage
    @TruffleBoundary
    boolean isArrayElementReadable(long index) {
        return index >= 0 && index < delegate.size();
    }

    @ExportMessage
    @TruffleBoundary
    Object readArrayElement(long index) {
        return delegate.get((int) index);
    }

    @ExportMessage
    boolean isArrayElementModifiable(long index) {
        return false;
    }

    @ExportMessage
    boolean isArrayElementInsertable(long index) {
        return false;
    }

    @ExportMessage
    void writeArrayElement(long index, Object value) throws UnsupportedMessageException {
        throw UnsupportedMessageException.create();
    }

    @ExportMessage
    boolean hasIterator() {
        return true;
    }

    @ExportMessage
    @TruffleBoundary
    Object getIterator() {
        return new DequeIterator(delegate.iterator());
    }

    // ===== OBJECT METHODS =====

    @Override
    @TruffleBoundary
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof MezzeDeque other)) return false;
        return Objects.equals(delegate, other.delegate);
    }

    @Override
    @TruffleBoundary
    public int hashCode() {
        return delegate.hashCode();
    }

    @Override
    @TruffleBoundary
    public String toString() {
        StringBuilder sb = new StringBuilder("Deque(");
        boolean first = true;
        for (Object elem : delegate) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(elem);
        }
        sb.append(")");
        return sb.toString();
    }

    // ===== ITERATOR HELPER =====

    @ExportLibrary(InteropLibrary.class)
    static final class DequeIterator implements TruffleObject {
        private final Iterator<Object> iterator;

        DequeIterator(Iterator<Object> iterator) {
            this.iterator = iterator;
        }

        @ExportMessage
        boolean isIterator() {
            return true;
        }

        @ExportMessage
        @TruffleBoundary
        boolean hasIteratorNextElement() {
            return iterator.hasNext();
        }

        @ExportMessage
        @TruffleBoundary
        Object getIteratorNextElement() throws UnsupportedMessageException {
            if (!iterator.hasNext()) {
                throw UnsupportedMessageException.create();
            }
            return iterator.next();
        }
    }
}
