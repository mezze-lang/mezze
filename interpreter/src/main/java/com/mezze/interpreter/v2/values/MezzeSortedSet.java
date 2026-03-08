package com.mezze.interpreter.v2.values;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import io.vavr.collection.TreeSet;

import java.util.Comparator;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;

/**
 * Mezze SortedSet backed by Vavr TreeSet (Red-Black tree) for O(log n) operations.
 *
 * Vavr TreeSet provides:
 * - O(log n) add, remove, contains
 * - O(log n) min, max
 * - Elements maintained in sorted order
 * - Structural sharing for memory efficiency
 * - Immutable semantics with persistent updates
 *
 * All "mutating" operations return new MezzeSortedSet instances with shared structure.
 */
@ExportLibrary(InteropLibrary.class)
public final class MezzeSortedSet implements TruffleObject, Iterable<Object> {

    // Default comparator that handles all Mezze value types
    private static final Comparator<Object> DEFAULT_COMPARATOR = MezzeSortedSet::compareValues;

    // Singleton empty sorted set
    private static final MezzeSortedSet EMPTY = new MezzeSortedSet(TreeSet.empty(DEFAULT_COMPARATOR));

    // Vavr TreeSet backing store
    private final TreeSet<Object> elements;

    private MezzeSortedSet(TreeSet<Object> elements) {
        this.elements = elements;
    }

    // ===== VALUE COMPARISON =====

    @TruffleBoundary
    private static int compareValues(Object a, Object b) {
        // Handle nulls
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;

        // Same type comparisons
        if (a instanceof Long && b instanceof Long) {
            return Long.compare((Long) a, (Long) b);
        }
        if (a instanceof Double && b instanceof Double) {
            return Double.compare((Double) a, (Double) b);
        }
        if (a instanceof String && b instanceof String) {
            return ((String) a).compareTo((String) b);
        }
        if (a instanceof Boolean && b instanceof Boolean) {
            return Boolean.compare((Boolean) a, (Boolean) b);
        }

        // Cross-type: compare by type name first, then by string representation
        int typeCompare = a.getClass().getName().compareTo(b.getClass().getName());
        if (typeCompare != 0) return typeCompare;

        // Same type but not comparable - use string representation
        return a.toString().compareTo(b.toString());
    }

    // ===== FACTORY METHODS =====

    public static MezzeSortedSet empty() {
        return EMPTY;
    }

    public static MezzeSortedSet of(Object element) {
        return new MezzeSortedSet(TreeSet.of(DEFAULT_COMPARATOR, element));
    }

    public static MezzeSortedSet of(Object e1, Object e2) {
        return new MezzeSortedSet(TreeSet.of(DEFAULT_COMPARATOR, e1, e2));
    }

    public static MezzeSortedSet of(Object e1, Object e2, Object e3) {
        return new MezzeSortedSet(TreeSet.of(DEFAULT_COMPARATOR, e1, e2, e3));
    }

    @TruffleBoundary
    public static MezzeSortedSet of(Object... elements) {
        if (elements.length == 0) return EMPTY;
        TreeSet<Object> set = TreeSet.empty(DEFAULT_COMPARATOR);
        for (Object e : elements) {
            set = set.add(e);
        }
        return new MezzeSortedSet(set);
    }

    @TruffleBoundary
    public static MezzeSortedSet fromIterable(Iterable<?> elements) {
        TreeSet<Object> set = TreeSet.empty(DEFAULT_COMPARATOR);
        for (Object e : elements) {
            set = set.add(e);
        }
        return new MezzeSortedSet(set);
    }

    @TruffleBoundary
    public static MezzeSortedSet fromJavaSet(Set<?> set) {
        TreeSet<Object> result = TreeSet.empty(DEFAULT_COMPARATOR);
        for (Object e : set) {
            result = result.add(e);
        }
        return new MezzeSortedSet(result);
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

    public TreeSet<Object> getElements() {
        return elements;
    }

    // ===== PERSISTENT UPDATE OPERATIONS =====

    @TruffleBoundary
    public MezzeSortedSet add(Object element) {
        return new MezzeSortedSet(elements.add(element));
    }

    @TruffleBoundary
    public MezzeSortedSet remove(Object element) {
        return new MezzeSortedSet(elements.remove(element));
    }

    // ===== SET OPERATIONS =====

    @TruffleBoundary
    public MezzeSortedSet union(MezzeSortedSet other) {
        return new MezzeSortedSet(elements.union(other.elements));
    }

    @TruffleBoundary
    public MezzeSortedSet intersect(MezzeSortedSet other) {
        return new MezzeSortedSet(elements.intersect(other.elements));
    }

    @TruffleBoundary
    public MezzeSortedSet diff(MezzeSortedSet other) {
        return new MezzeSortedSet(elements.diff(other.elements));
    }

    @TruffleBoundary
    public boolean isSubsetOf(MezzeSortedSet other) {
        for (Object e : elements) {
            if (!other.elements.contains(e)) return false;
        }
        return true;
    }

    // ===== SORTED SET SPECIFIC OPERATIONS =====

    /**
     * Get the minimum element. O(log n)
     */
    @TruffleBoundary
    public Object min() {
        return elements.isEmpty() ? null : elements.head();
    }

    /**
     * Get the maximum element. O(log n)
     */
    @TruffleBoundary
    public Object max() {
        return elements.isEmpty() ? null : elements.last();
    }

    /**
     * Get all elements in the range [from, to). O(log n + m)
     */
    @TruffleBoundary
    public MezzeSortedSet range(Object from, Object to) {
        TreeSet<Object> ranged = elements.filter(e ->
            compareValues(e, from) >= 0 && compareValues(e, to) < 0
        );
        return ranged.isEmpty() ? EMPTY : new MezzeSortedSet(ranged);
    }

    /**
     * Get all elements greater than the given element. O(log n + m)
     */
    @TruffleBoundary
    public MezzeSortedSet above(Object element) {
        TreeSet<Object> filtered = elements.filter(e -> compareValues(e, element) > 0);
        return filtered.isEmpty() ? EMPTY : new MezzeSortedSet(filtered);
    }

    /**
     * Get all elements less than the given element. O(log n + m)
     */
    @TruffleBoundary
    public MezzeSortedSet below(Object element) {
        TreeSet<Object> filtered = elements.filter(e -> compareValues(e, element) < 0);
        return filtered.isEmpty() ? EMPTY : new MezzeSortedSet(filtered);
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
        return new SortedSetIterator(elements.iterator());
    }

    // ===== OBJECT METHODS =====

    @Override
    @TruffleBoundary
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof MezzeSortedSet other)) return false;
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
        StringBuilder sb = new StringBuilder("SortedSet#{");
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
    static final class SortedSetIterator implements TruffleObject {
        private final Iterator<Object> iterator;

        SortedSetIterator(Iterator<Object> iterator) {
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
