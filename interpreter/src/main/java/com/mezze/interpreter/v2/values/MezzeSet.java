package com.mezze.interpreter.v2.values;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import io.vavr.collection.HashSet;

import java.util.Iterator;
import java.util.Objects;
import java.util.Set;

/**
 * Mezze Set backed by Vavr HashSet (HAMT) for O(1) amortized persistent operations.
 *
 * Vavr HashSet provides:
 * - O(1) amortized add, remove, contains (Hash Array Mapped Trie)
 * - Structural sharing for memory efficiency
 * - Immutable semantics with persistent updates
 *
 * All "mutating" operations return new MezzeSet instances with shared structure.
 */
@ExportLibrary(InteropLibrary.class)
public final class MezzeSet implements TruffleObject, Iterable<Object> {

    // Singleton empty set
    private static final MezzeSet EMPTY = new MezzeSet(HashSet.empty());

    // Vavr HashSet backing store
    private final HashSet<Object> elements;

    private MezzeSet(HashSet<Object> elements) {
        this.elements = elements;
    }

    // ===== FACTORY METHODS =====

    public static MezzeSet empty() {
        return EMPTY;
    }

    public static MezzeSet of(Object element) {
        return new MezzeSet(HashSet.of(element));
    }

    public static MezzeSet of(Object e1, Object e2) {
        return new MezzeSet(HashSet.of(e1, e2));
    }

    public static MezzeSet of(Object e1, Object e2, Object e3) {
        return new MezzeSet(HashSet.of(e1, e2, e3));
    }

    @TruffleBoundary
    public static MezzeSet of(Object... elements) {
        if (elements.length == 0) return EMPTY;
        return new MezzeSet(HashSet.of(elements));
    }

    @TruffleBoundary
    public static MezzeSet fromIterable(Iterable<?> elements) {
        return new MezzeSet(HashSet.ofAll(elements));
    }

    @TruffleBoundary
    public static MezzeSet fromJavaSet(Set<?> set) {
        return new MezzeSet(HashSet.ofAll(set));
    }

    // ===== BASIC OPERATIONS =====

    public int size() {
        return elements.size();
    }

    public boolean isEmpty() {
        return elements.isEmpty();
    }

    @TruffleBoundary
    public boolean contains(Object element) {
        return elements.contains(element);
    }

    public HashSet<Object> getElements() {
        return elements;
    }

    // ===== PERSISTENT UPDATE OPERATIONS =====

    @TruffleBoundary
    public MezzeSet add(Object element) {
        return new MezzeSet(elements.add(element));
    }

    @TruffleBoundary
    public MezzeSet remove(Object element) {
        return new MezzeSet(elements.remove(element));
    }

    // ===== SET OPERATIONS =====

    @TruffleBoundary
    public MezzeSet union(MezzeSet other) {
        return new MezzeSet(elements.union(other.elements));
    }

    @TruffleBoundary
    public MezzeSet intersect(MezzeSet other) {
        return new MezzeSet(elements.intersect(other.elements));
    }

    @TruffleBoundary
    public MezzeSet diff(MezzeSet other) {
        return new MezzeSet(elements.diff(other.elements));
    }

    @TruffleBoundary
    public boolean isSubsetOf(MezzeSet other) {
        return other.elements.containsAll(elements);
    }

    // ===== CONVERSION =====

    @TruffleBoundary
    public Set<Object> toJavaSet() {
        return elements.toJavaSet();
    }

    @Override
    @TruffleBoundary
    public Iterator<Object> iterator() {
        return elements.iterator();
    }

    // ===== INTEROP LIBRARY =====

    @ExportMessage
    boolean hasIterator() {
        return true;
    }

    @ExportMessage
    @TruffleBoundary
    Object getIterator() {
        return new SetIterator(elements.iterator());
    }

    // ===== OBJECT METHODS =====

    @Override
    @TruffleBoundary
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof MezzeSet other)) return false;
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
        StringBuilder sb = new StringBuilder("#{");
        boolean first = true;
        for (Object elem : elements) {
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
