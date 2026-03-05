package com.mezze.interpreter.v2.values;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import io.vavr.collection.Queue;
import io.vavr.Tuple2;

import java.util.Iterator;
import java.util.Objects;

/**
 * Mezze Deque backed by Vavr Queue for amortized O(1) operations on both ends.
 *
 * Vavr Queue provides:
 * - Amortized O(1) enqueue (pushBack)
 * - Amortized O(1) dequeue (popFront)
 * - Amortized O(1) prepend (pushFront)
 * - Structural sharing for memory efficiency
 * - Immutable semantics with persistent updates
 *
 * Implemented as a functional queue (Banker's queue style) with two internal lists:
 * front for O(1) head access, back for O(1) tail insertion, with lazy rotation.
 *
 * All "mutating" operations return new MezzeDeque instances with shared structure.
 */
@ExportLibrary(InteropLibrary.class)
public final class MezzeDeque implements TruffleObject, Iterable<Object> {

    // Singleton empty deque
    private static final MezzeDeque EMPTY = new MezzeDeque(Queue.empty());

    // Vavr Queue backing store
    private final Queue<Object> elements;

    private MezzeDeque(Queue<Object> elements) {
        this.elements = elements;
    }

    // ===== FACTORY METHODS =====

    public static MezzeDeque empty() {
        return EMPTY;
    }

    public static MezzeDeque of(Object element) {
        return new MezzeDeque(Queue.of(element));
    }

    public static MezzeDeque of(Object e1, Object e2) {
        return new MezzeDeque(Queue.of(e1, e2));
    }

    public static MezzeDeque of(Object e1, Object e2, Object e3) {
        return new MezzeDeque(Queue.of(e1, e2, e3));
    }

    @TruffleBoundary
    public static MezzeDeque of(Object... elements) {
        if (elements.length == 0) return EMPTY;
        return new MezzeDeque(Queue.of(elements));
    }

    @TruffleBoundary
    public static MezzeDeque fromIterable(Iterable<?> elements) {
        return new MezzeDeque(Queue.ofAll(elements));
    }

    // ===== BASIC OPERATIONS =====

    public int size() {
        return elements.size();
    }

    public boolean isEmpty() {
        return elements.isEmpty();
    }

    public Queue<Object> getElements() {
        return elements;
    }

    // ===== FRONT OPERATIONS =====

    /**
     * Get the front element without removing it. Amortized O(1)
     * @throws java.util.NoSuchElementException if deque is empty
     */
    @TruffleBoundary
    public Object peekFront() {
        return elements.head();
    }

    /**
     * Get the front element, or null if empty. Amortized O(1)
     */
    @TruffleBoundary
    public Object peekFrontOrNull() {
        return elements.isEmpty() ? null : elements.head();
    }

    /**
     * Add element to front. Amortized O(1)
     */
    @TruffleBoundary
    public MezzeDeque pushFront(Object element) {
        return new MezzeDeque(elements.prepend(element));
    }

    /**
     * Remove and return front element. Amortized O(1)
     * Returns a tuple of (element, newDeque), or null if empty.
     */
    @TruffleBoundary
    public Object[] popFront() {
        if (elements.isEmpty()) return null;
        Tuple2<Object, Queue<Object>> result = elements.dequeue();
        Queue<Object> newQueue = result._2;
        return new Object[]{result._1, newQueue.isEmpty() ? EMPTY : new MezzeDeque(newQueue)};
    }

    // ===== BACK OPERATIONS =====

    /**
     * Get the back element without removing it. O(n) - but usually fast in practice
     * @throws java.util.NoSuchElementException if deque is empty
     */
    @TruffleBoundary
    public Object peekBack() {
        return elements.last();
    }

    /**
     * Get the back element, or null if empty.
     */
    @TruffleBoundary
    public Object peekBackOrNull() {
        return elements.isEmpty() ? null : elements.last();
    }

    /**
     * Add element to back. Amortized O(1)
     */
    @TruffleBoundary
    public MezzeDeque pushBack(Object element) {
        return new MezzeDeque(elements.append(element));
    }

    /**
     * Remove and return back element.
     * Returns a tuple of (newDeque, element), or null if empty.
     * Note: O(n) operation - use sparingly for true deque semantics.
     */
    @TruffleBoundary
    public Object[] popBack() {
        if (elements.isEmpty()) return null;
        Queue<Object> init = elements.init();
        Object last = elements.last();
        return new Object[]{init.isEmpty() ? EMPTY : new MezzeDeque(init), last};
    }

    // ===== BULK OPERATIONS =====

    /**
     * Concatenate two deques. O(n)
     */
    @TruffleBoundary
    public MezzeDeque concat(MezzeDeque other) {
        return new MezzeDeque(elements.appendAll(other.elements));
    }

    /**
     * Reverse the deque. O(n)
     */
    @TruffleBoundary
    public MezzeDeque reverse() {
        return new MezzeDeque(elements.reverse());
    }

    /**
     * Take first n elements. O(n)
     */
    @TruffleBoundary
    public MezzeDeque take(int n) {
        Queue<Object> taken = elements.take(n);
        return taken.isEmpty() ? EMPTY : new MezzeDeque(taken);
    }

    /**
     * Drop first n elements. O(n)
     */
    @TruffleBoundary
    public MezzeDeque drop(int n) {
        Queue<Object> dropped = elements.drop(n);
        return dropped.isEmpty() ? EMPTY : new MezzeDeque(dropped);
    }

    // ===== CONVERSION =====

    @TruffleBoundary
    public java.util.List<Object> toJavaList() {
        return elements.asJava();
    }

    @TruffleBoundary
    public Object[] toJavaArray() {
        return elements.toJavaArray(Object[]::new);
    }

    @Override
    @TruffleBoundary
    public Iterator<Object> iterator() {
        return elements.iterator();
    }

    // ===== INTEROP LIBRARY =====

    @ExportMessage
    boolean hasArrayElements() {
        return true;
    }

    @ExportMessage
    long getArraySize() {
        return elements.size();
    }

    @ExportMessage
    @TruffleBoundary
    boolean isArrayElementReadable(long index) {
        return index >= 0 && index < elements.size();
    }

    @ExportMessage
    @TruffleBoundary
    Object readArrayElement(long index) {
        return elements.get((int) index);
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
        return new DequeIterator(elements.iterator());
    }

    // ===== OBJECT METHODS =====

    @Override
    @TruffleBoundary
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof MezzeDeque other)) return false;
        return Objects.equals(elements, other.elements);
    }

    @Override
    @TruffleBoundary
    public int hashCode() {
        return elements.hashCode();
    }

    @Override
    @TruffleBoundary
    public String toString() {
        StringBuilder sb = new StringBuilder("Deque(");
        boolean first = true;
        for (Object elem : elements) {
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
