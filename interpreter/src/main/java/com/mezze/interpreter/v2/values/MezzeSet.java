package com.mezze.interpreter.v2.values;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import java.util.Iterator;
import java.util.Objects;
import java.util.Set;

/**
 * Mezze Set backed by custom datastructures module for O(1) amortized persistent operations.
 *
 * The datastructures MezzeSet provides:
 * - O(1) amortized add, remove, contains (tiered storage)
 * - Structural sharing for memory efficiency
 * - Immutable semantics with persistent updates
 * - Specialized storage for primitives (long)
 *
 * All "mutating" operations return new MezzeSet instances with shared structure.
 */
@ExportLibrary(InteropLibrary.class)
public final class MezzeSet implements TruffleObject, Iterable<Object> {

    // Singleton empty set
    private static final MezzeSet EMPTY = new MezzeSet(com.mezze.datastructures.set.MezzeSet.empty());

    // Datastructures MezzeSet backing store
    private final com.mezze.datastructures.set.MezzeSet<Object> delegate;

    private MezzeSet(com.mezze.datastructures.set.MezzeSet<Object> delegate) {
        this.delegate = delegate;
    }

    // ===== FACTORY METHODS =====

    public static MezzeSet empty() {
        return EMPTY;
    }

    public static MezzeSet of(Object element) {
        return new MezzeSet(com.mezze.datastructures.set.MezzeSet.of(element));
    }

    public static MezzeSet of(Object e1, Object e2) {
        return new MezzeSet(com.mezze.datastructures.set.MezzeSet.of(e1, e2));
    }

    public static MezzeSet of(Object e1, Object e2, Object e3) {
        return new MezzeSet(com.mezze.datastructures.set.MezzeSet.of(e1, e2, e3));
    }

    @TruffleBoundary
    public static MezzeSet of(Object... elements) {
        if (elements.length == 0) return EMPTY;
        return new MezzeSet(com.mezze.datastructures.set.MezzeSet.of(elements));
    }

    @TruffleBoundary
    public static MezzeSet fromIterable(Iterable<?> elements) {
        com.mezze.datastructures.set.MezzeSet<Object> result = com.mezze.datastructures.set.MezzeSet.empty();
        for (Object elem : elements) {
            result = result.add(elem);
        }
        return new MezzeSet(result);
    }

    @TruffleBoundary
    public static MezzeSet fromJavaSet(Set<?> set) {
        com.mezze.datastructures.set.MezzeSet<Object> result = com.mezze.datastructures.set.MezzeSet.empty();
        for (Object elem : set) {
            result = result.add(elem);
        }
        return new MezzeSet(result);
    }

    // ===== BASIC OPERATIONS =====

    public int size() {
        return delegate.size();
    }

    public boolean isEmpty() {
        return delegate.isEmpty();
    }

    @TruffleBoundary
    public boolean contains(Object element) {
        return delegate.contains(element);
    }

    /**
     * Get the underlying datastructures set for internal use.
     */
    public com.mezze.datastructures.set.MezzeSet<Object> getDelegate() {
        return delegate;
    }

    // ===== PERSISTENT UPDATE OPERATIONS =====

    @TruffleBoundary
    public MezzeSet add(Object element) {
        return new MezzeSet(delegate.add(element));
    }

    @TruffleBoundary
    public MezzeSet remove(Object element) {
        return new MezzeSet(delegate.remove(element));
    }

    // ===== SET OPERATIONS =====

    @TruffleBoundary
    public MezzeSet union(MezzeSet other) {
        return new MezzeSet(delegate.union(other.delegate));
    }

    @TruffleBoundary
    public MezzeSet intersect(MezzeSet other) {
        return new MezzeSet(delegate.intersect(other.delegate));
    }

    @TruffleBoundary
    public MezzeSet diff(MezzeSet other) {
        return new MezzeSet(delegate.difference(other.delegate));
    }

    @TruffleBoundary
    public boolean isSubsetOf(MezzeSet other) {
        return delegate.isSubsetOf(other.delegate);
    }

    // ===== CONVERSION =====

    @TruffleBoundary
    public Set<Object> toJavaSet() {
        java.util.HashSet<Object> result = new java.util.HashSet<>(delegate.size());
        for (Object elem : delegate) {
            result.add(elem);
        }
        return result;
    }

    @Override
    @TruffleBoundary
    public Iterator<Object> iterator() {
        return delegate.iterator();
    }

    // ===== INTEROP LIBRARY =====

    @ExportMessage
    boolean hasIterator() {
        return true;
    }

    @ExportMessage
    @TruffleBoundary
    Object getIterator() {
        return new SetIterator(delegate.iterator());
    }

    // ===== OBJECT METHODS =====

    @Override
    @TruffleBoundary
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof MezzeSet other)) return false;
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
        StringBuilder sb = new StringBuilder("#{");
        boolean first = true;
        for (Object elem : delegate) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(elem);
        }
        sb.append("}");
        return sb.toString();
    }

    // ===== ITERATOR HELPER =====

    @ExportLibrary(InteropLibrary.class)
    static final class SetIterator implements TruffleObject {
        private final Iterator<Object> iterator;

        SetIterator(Iterator<Object> iterator) {
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
