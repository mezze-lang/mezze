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
 * Mezze List backed by custom datastructures module (functional linked list) for O(1) head/tail/prepend.
 *
 * The datastructures MezzeList provides:
 * - O(1) prepend (cons)
 * - O(1) head
 * - O(1) tail
 * - O(n) append, get by index
 * - Structural sharing for memory efficiency
 * - Immutable semantics with persistent updates
 * - Specialized storage for primitives (long, double)
 *
 * This is the classic functional linked list, ideal for recursive algorithms
 * and pattern matching. For random access, use MezzeSeq instead.
 */
@ExportLibrary(InteropLibrary.class)
public final class MezzeList implements TruffleObject, Iterable<Object> {

    private static final MezzeList EMPTY = new MezzeList(com.mezze.datastructures.list.MezzeList.empty());

    private final com.mezze.datastructures.list.MezzeList<Object> delegate;

    private MezzeList(com.mezze.datastructures.list.MezzeList<Object> delegate) {
        this.delegate = delegate;
    }

    // ===== FACTORY METHODS =====

    public static MezzeList empty() {
        return EMPTY;
    }

    public static MezzeList of(Object element) {
        return new MezzeList(com.mezze.datastructures.list.MezzeList.of(element));
    }

    public static MezzeList of(Object e1, Object e2) {
        return new MezzeList(com.mezze.datastructures.list.MezzeList.of(e1, e2));
    }

    public static MezzeList of(Object e1, Object e2, Object e3) {
        return new MezzeList(com.mezze.datastructures.list.MezzeList.of(e1, e2, e3));
    }

    @TruffleBoundary
    public static MezzeList of(Object... elements) {
        if (elements.length == 0) return EMPTY;
        return new MezzeList(com.mezze.datastructures.list.MezzeList.of(elements));
    }

    @TruffleBoundary
    public static MezzeList fromIterable(Iterable<?> elements) {
        com.mezze.datastructures.list.MezzeList<Object> result = com.mezze.datastructures.list.MezzeList.empty();
        // Build in reverse order then reverse
        for (Object elem : elements) {
            result = result.prepend(elem);
        }
        return new MezzeList(result.reverse());
    }

    @TruffleBoundary
    public static MezzeList fromArray(Object[] elements) {
        if (elements.length == 0) return EMPTY;
        return new MezzeList(com.mezze.datastructures.list.MezzeList.of(elements));
    }

    // ===== BASIC OPERATIONS =====

    public int size() {
        return delegate.size();
    }

    public boolean isEmpty() {
        return delegate.isEmpty();
    }

    /**
     * Get the underlying datastructures list for internal use.
     */
    public com.mezze.datastructures.list.MezzeList<Object> getDelegate() {
        return delegate;
    }

    // ===== HEAD/TAIL OPERATIONS (O(1)) =====

    @TruffleBoundary
    public Object head() {
        return delegate.head();
    }

    @TruffleBoundary
    public Object headOrNull() {
        return delegate.isEmpty() ? null : delegate.head();
    }

    @TruffleBoundary
    public MezzeList tail() {
        com.mezze.datastructures.list.MezzeList<Object> t = delegate.tail();
        return t.isEmpty() ? EMPTY : new MezzeList(t);
    }

    @TruffleBoundary
    public MezzeList tailOrEmpty() {
        if (delegate.isEmpty()) return EMPTY;
        com.mezze.datastructures.list.MezzeList<Object> t = delegate.tail();
        return t.isEmpty() ? EMPTY : new MezzeList(t);
    }

    @TruffleBoundary
    public Object last() {
        // Walk to the last element
        com.mezze.datastructures.list.MezzeList<Object> current = delegate;
        Object last = null;
        while (!current.isEmpty()) {
            last = current.head();
            current = current.tail();
        }
        return last;
    }

    @TruffleBoundary
    public MezzeList init() {
        // Return all but the last element
        if (delegate.isEmpty() || delegate.tail().isEmpty()) {
            return EMPTY;
        }
        // Build new list without last element
        com.mezze.datastructures.list.MezzeList<Object> result = com.mezze.datastructures.list.MezzeList.empty();
        com.mezze.datastructures.list.MezzeList<Object> current = delegate;
        while (!current.tail().isEmpty()) {
            result = result.prepend(current.head());
            current = current.tail();
        }
        return new MezzeList(result.reverse());
    }

    // ===== INDEX ACCESS (O(n)) =====

    @TruffleBoundary
    public Object get(int index) {
        return delegate.get(index);
    }

    public boolean isValidIndex(int index) {
        return index >= 0 && index < delegate.size();
    }

    // ===== PERSISTENT UPDATE OPERATIONS =====

    @TruffleBoundary
    public MezzeList prepend(Object element) {
        return new MezzeList(delegate.prepend(element));
    }

    @TruffleBoundary
    public MezzeList append(Object element) {
        return new MezzeList(delegate.append(element));
    }

    @TruffleBoundary
    public MezzeList update(int index, Object element) {
        // Build new list with element at index replaced
        if (index < 0 || index >= delegate.size()) {
            throw new IndexOutOfBoundsException("Index: " + index);
        }
        com.mezze.datastructures.list.MezzeList<Object> result = com.mezze.datastructures.list.MezzeList.empty();
        com.mezze.datastructures.list.MezzeList<Object> current = delegate;
        int i = 0;
        while (!current.isEmpty()) {
            if (i == index) {
                result = result.prepend(element);
            } else {
                result = result.prepend(current.head());
            }
            current = current.tail();
            i++;
        }
        return new MezzeList(result.reverse());
    }

    @TruffleBoundary
    public MezzeList removeAt(int index) {
        // Build new list without element at index
        if (index < 0 || index >= delegate.size()) {
            throw new IndexOutOfBoundsException("Index: " + index);
        }
        com.mezze.datastructures.list.MezzeList<Object> result = com.mezze.datastructures.list.MezzeList.empty();
        com.mezze.datastructures.list.MezzeList<Object> current = delegate;
        int i = 0;
        while (!current.isEmpty()) {
            if (i != index) {
                result = result.prepend(current.head());
            }
            current = current.tail();
            i++;
        }
        return new MezzeList(result.reverse());
    }

    @TruffleBoundary
    public MezzeList concat(MezzeList other) {
        return new MezzeList(delegate.concat(other.delegate));
    }

    @TruffleBoundary
    public MezzeList reverse() {
        return new MezzeList(delegate.reverse());
    }

    @TruffleBoundary
    public MezzeList take(int n) {
        com.mezze.datastructures.list.MezzeList<Object> taken = delegate.take(n);
        return taken.isEmpty() ? EMPTY : new MezzeList(taken);
    }

    @TruffleBoundary
    public MezzeList drop(int n) {
        com.mezze.datastructures.list.MezzeList<Object> dropped = delegate.drop(n);
        return dropped.isEmpty() ? EMPTY : new MezzeList(dropped);
    }

    @TruffleBoundary
    public MezzeList slice(int start, int end) {
        // take(end).drop(start)
        com.mezze.datastructures.list.MezzeList<Object> sliced = delegate.take(end).drop(start);
        return sliced.isEmpty() ? EMPTY : new MezzeList(sliced);
    }

    // ===== SEARCH OPERATIONS =====

    @TruffleBoundary
    public boolean contains(Object element) {
        return delegate.contains(element);
    }

    @TruffleBoundary
    public int indexOf(Object element) {
        int i = 0;
        com.mezze.datastructures.list.MezzeList<Object> current = delegate;
        while (!current.isEmpty()) {
            if (Objects.equals(current.head(), element)) {
                return i;
            }
            current = current.tail();
            i++;
        }
        return -1;
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
    public Object[] toArray() {
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
        return new ListIterator(delegate.iterator());
    }

    // ===== OBJECT METHODS =====

    @Override
    @TruffleBoundary
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof MezzeList other)) return false;
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
        StringBuilder sb = new StringBuilder("List(");
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
    static final class ListIterator implements TruffleObject {
        private final Iterator<Object> iterator;

        ListIterator(Iterator<Object> iterator) {
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
