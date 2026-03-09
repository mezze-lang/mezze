package com.mezze.interpreter.v2.values;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

/**
 * Mezze SortedSet backed by a sorted array for O(log n) operations.
 *
 * This implementation:
 * - O(log n) contains (binary search)
 * - O(n) add, remove (due to array copy)
 * - O(1) min, max
 * - Elements maintained in sorted order
 * - Immutable semantics with persistent updates
 *
 * All "mutating" operations return new MezzeSortedSet instances.
 */
@ExportLibrary(InteropLibrary.class)
public final class MezzeSortedSet implements TruffleObject, Iterable<Object> {

    // Default comparator that handles all Mezze value types
    private static final Comparator<Object> DEFAULT_COMPARATOR = MezzeSortedSet::compareValues;

    // Singleton empty sorted set
    private static final MezzeSortedSet EMPTY = new MezzeSortedSet(new Object[0]);

    // Sorted array backing store
    private final Object[] elements;

    private MezzeSortedSet(Object[] elements) {
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

    /**
     * Binary search for element.
     * Returns index if found, or -(insertionPoint + 1) if not found.
     */
    @TruffleBoundary
    private int binarySearch(Object elem) {
        int low = 0;
        int high = elements.length - 1;

        while (low <= high) {
            int mid = (low + high) >>> 1;
            int cmp = compareValues(elements[mid], elem);

            if (cmp < 0) {
                low = mid + 1;
            } else if (cmp > 0) {
                high = mid - 1;
            } else {
                return mid;
            }
        }

        return -(low + 1);
    }

    // ===== FACTORY METHODS =====

    public static MezzeSortedSet empty() {
        return EMPTY;
    }

    @TruffleBoundary
    public static MezzeSortedSet of(Object element) {
        return new MezzeSortedSet(new Object[]{element});
    }

    @TruffleBoundary
    public static MezzeSortedSet of(Object e1, Object e2) {
        return empty().add(e1).add(e2);
    }

    @TruffleBoundary
    public static MezzeSortedSet of(Object e1, Object e2, Object e3) {
        return empty().add(e1).add(e2).add(e3);
    }

    @TruffleBoundary
    public static MezzeSortedSet of(Object... elems) {
        if (elems.length == 0) return EMPTY;
        MezzeSortedSet result = EMPTY;
        for (Object e : elems) {
            result = result.add(e);
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeSortedSet fromIterable(Iterable<?> elems) {
        MezzeSortedSet result = EMPTY;
        for (Object e : elems) {
            result = result.add(e);
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeSortedSet fromJavaSet(Set<?> set) {
        MezzeSortedSet result = EMPTY;
        for (Object e : set) {
            result = result.add(e);
        }
        return result;
    }

    // ===== BASIC OPERATIONS =====

    public int size() {
        return elements.length;
    }

    public boolean isEmpty() {
        return elements.length == 0;
    }

    @TruffleBoundary
    public boolean contains(Object element) {
        return binarySearch(element) >= 0;
    }

    // ===== PERSISTENT UPDATE OPERATIONS =====

    @TruffleBoundary
    public MezzeSortedSet add(Object element) {
        int idx = binarySearch(element);
        if (idx >= 0) {
            return this;  // Already present
        }

        int insertPos = -(idx + 1);
        Object[] newElements = new Object[elements.length + 1];
        System.arraycopy(elements, 0, newElements, 0, insertPos);
        newElements[insertPos] = element;
        System.arraycopy(elements, insertPos, newElements, insertPos + 1, elements.length - insertPos);

        return new MezzeSortedSet(newElements);
    }

    @TruffleBoundary
    public MezzeSortedSet remove(Object element) {
        int idx = binarySearch(element);
        if (idx < 0) {
            return this;  // Not present
        }

        if (elements.length == 1) {
            return EMPTY;
        }

        Object[] newElements = new Object[elements.length - 1];
        System.arraycopy(elements, 0, newElements, 0, idx);
        System.arraycopy(elements, idx + 1, newElements, idx, elements.length - idx - 1);

        return new MezzeSortedSet(newElements);
    }

    // ===== SET OPERATIONS =====

    @TruffleBoundary
    public MezzeSortedSet union(MezzeSortedSet other) {
        if (isEmpty()) return other;
        if (other.isEmpty()) return this;

        // Merge sorted arrays
        Object[] result = new Object[elements.length + other.elements.length];
        int i = 0, j = 0, k = 0;

        while (i < elements.length && j < other.elements.length) {
            int cmp = compareValues(elements[i], other.elements[j]);
            if (cmp < 0) {
                result[k++] = elements[i++];
            } else if (cmp > 0) {
                result[k++] = other.elements[j++];
            } else {
                result[k++] = elements[i++];
                j++;  // Skip duplicate
            }
        }

        while (i < elements.length) {
            result[k++] = elements[i++];
        }
        while (j < other.elements.length) {
            result[k++] = other.elements[j++];
        }

        if (k == 0) return EMPTY;
        return new MezzeSortedSet(Arrays.copyOf(result, k));
    }

    @TruffleBoundary
    public MezzeSortedSet intersect(MezzeSortedSet other) {
        if (isEmpty() || other.isEmpty()) return EMPTY;

        Object[] result = new Object[Math.min(elements.length, other.elements.length)];
        int i = 0, j = 0, k = 0;

        while (i < elements.length && j < other.elements.length) {
            int cmp = compareValues(elements[i], other.elements[j]);
            if (cmp < 0) {
                i++;
            } else if (cmp > 0) {
                j++;
            } else {
                result[k++] = elements[i++];
                j++;
            }
        }

        if (k == 0) return EMPTY;
        return new MezzeSortedSet(Arrays.copyOf(result, k));
    }

    @TruffleBoundary
    public MezzeSortedSet diff(MezzeSortedSet other) {
        if (isEmpty()) return this;
        if (other.isEmpty()) return this;

        Object[] result = new Object[elements.length];
        int i = 0, j = 0, k = 0;

        while (i < elements.length && j < other.elements.length) {
            int cmp = compareValues(elements[i], other.elements[j]);
            if (cmp < 0) {
                result[k++] = elements[i++];
            } else if (cmp > 0) {
                j++;
            } else {
                i++;
                j++;
            }
        }

        while (i < elements.length) {
            result[k++] = elements[i++];
        }

        if (k == 0) return EMPTY;
        return new MezzeSortedSet(Arrays.copyOf(result, k));
    }

    @TruffleBoundary
    public boolean isSubsetOf(MezzeSortedSet other) {
        for (Object elem : elements) {
            if (!other.contains(elem)) return false;
        }
        return true;
    }

    // ===== SORTED SET SPECIFIC OPERATIONS =====

    /**
     * Get the minimum element. O(1)
     */
    @TruffleBoundary
    public Object min() {
        return isEmpty() ? null : elements[0];
    }

    /**
     * Get the maximum element. O(1)
     */
    @TruffleBoundary
    public Object max() {
        return isEmpty() ? null : elements[elements.length - 1];
    }

    /**
     * Get all elements in the range [from, to).
     */
    @TruffleBoundary
    public MezzeSortedSet range(Object from, Object to) {
        int fromIdx = binarySearch(from);
        if (fromIdx < 0) fromIdx = -(fromIdx + 1);

        int toIdx = binarySearch(to);
        if (toIdx < 0) toIdx = -(toIdx + 1);

        if (fromIdx >= toIdx) return EMPTY;
        return new MezzeSortedSet(Arrays.copyOfRange(elements, fromIdx, toIdx));
    }

    /**
     * Get all elements greater than the given element.
     */
    @TruffleBoundary
    public MezzeSortedSet above(Object element) {
        int idx = binarySearch(element);
        if (idx >= 0) {
            idx++;  // Skip the element itself
        } else {
            idx = -(idx + 1);
        }

        if (idx >= elements.length) return EMPTY;
        return new MezzeSortedSet(Arrays.copyOfRange(elements, idx, elements.length));
    }

    /**
     * Get all elements less than the given element.
     */
    @TruffleBoundary
    public MezzeSortedSet below(Object element) {
        int idx = binarySearch(element);
        if (idx < 0) {
            idx = -(idx + 1);
        }

        if (idx <= 0) return EMPTY;
        return new MezzeSortedSet(Arrays.copyOfRange(elements, 0, idx));
    }

    // ===== CONVERSION =====

    @TruffleBoundary
    public Set<Object> toJavaSet() {
        java.util.LinkedHashSet<Object> result = new java.util.LinkedHashSet<>(elements.length);
        for (Object elem : elements) {
            result.add(elem);
        }
        return result;
    }

    @Override
    @TruffleBoundary
    public Iterator<Object> iterator() {
        return new Iterator<>() {
            private int index = 0;

            @Override
            public boolean hasNext() {
                return index < elements.length;
            }

            @Override
            public Object next() {
                if (!hasNext()) throw new NoSuchElementException();
                return elements[index++];
            }
        };
    }

    // ===== INTEROP LIBRARY =====

    @ExportMessage
    boolean hasIterator() {
        return true;
    }

    @ExportMessage
    @TruffleBoundary
    Object getIterator() {
        return new SortedSetIterator(iterator());
    }

    // ===== OBJECT METHODS =====

    @Override
    @TruffleBoundary
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof MezzeSortedSet other)) return false;
        return Arrays.equals(elements, other.elements);
    }

    @Override
    @TruffleBoundary
    public int hashCode() {
        return Arrays.hashCode(elements);
    }

    @Override
    @TruffleBoundary
    public String toString() {
        StringBuilder sb = new StringBuilder("SortedSet#{");
        for (int i = 0; i < elements.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(elements[i]);
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
