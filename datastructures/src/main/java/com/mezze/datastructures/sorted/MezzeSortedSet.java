package com.mezze.datastructures.sorted;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.TruffleObject;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Immutable sorted set backed by a sorted array.
 * Uses binary search for O(log n) lookups.
 *
 * @param <A> Element type (must be Comparable)
 */
public final class MezzeSortedSet<A extends Comparable<A>> implements TruffleObject, Iterable<A> {

    @CompilationFinal(dimensions = 1)
    private final Object[] elements;

    @CompilationFinal
    private final Comparator<Object> comparator;

    @SuppressWarnings("unchecked")
    private MezzeSortedSet(Object[] elements, Comparator<?> comparator) {
        this.elements = elements;
        this.comparator = comparator != null
                ? (Comparator<Object>) comparator
                : (a, b) -> ((Comparable<Object>) a).compareTo(b);
    }

    // ===== CONSTRUCTION =====

    /**
     * Returns an empty sorted set.
     */
    public static <A extends Comparable<A>> MezzeSortedSet<A> empty() {
        return new MezzeSortedSet<>(new Object[0], null);
    }

    /**
     * Returns an empty sorted set with custom comparator.
     */
    public static <A extends Comparable<A>> MezzeSortedSet<A> empty(Comparator<? super A> comparator) {
        return new MezzeSortedSet<>(new Object[0], comparator);
    }

    /**
     * Creates a sorted set with a single element.
     */
    public static <A extends Comparable<A>> MezzeSortedSet<A> of(A element) {
        return new MezzeSortedSet<>(new Object[]{element}, null);
    }

    /**
     * Creates a sorted set from varargs.
     */
    @SafeVarargs
    @TruffleBoundary
    public static <A extends Comparable<A>> MezzeSortedSet<A> of(A... elements) {
        if (elements.length == 0) return empty();
        if (elements.length == 1) return of(elements[0]);

        // Sort and deduplicate
        Object[] sorted = elements.clone();
        Arrays.sort(sorted);
        return fromSortedArrayWithDuplicates(sorted, null);
    }

    /**
     * Creates a sorted set of longs.
     */
    @TruffleBoundary
    public static MezzeSortedSet<Long> ofLongs(long... elements) {
        if (elements.length == 0) return empty();

        long[] sorted = elements.clone();
        Arrays.sort(sorted);

        // Deduplicate
        int uniqueCount = 1;
        for (int i = 1; i < sorted.length; i++) {
            if (sorted[i] != sorted[uniqueCount - 1]) {
                sorted[uniqueCount++] = sorted[i];
            }
        }

        Object[] boxed = new Object[uniqueCount];
        for (int i = 0; i < uniqueCount; i++) {
            boxed[i] = sorted[i];
        }

        return new MezzeSortedSet<>(boxed, null);
    }

    @TruffleBoundary
    private static <A extends Comparable<A>> MezzeSortedSet<A> fromSortedArrayWithDuplicates(
            Object[] sorted, Comparator<? super A> comparator) {
        // Count unique elements
        int uniqueCount = 1;
        for (int i = 1; i < sorted.length; i++) {
            if (!Objects.equals(sorted[i], sorted[i - 1])) {
                uniqueCount++;
            }
        }

        if (uniqueCount == sorted.length) {
            return new MezzeSortedSet<>(sorted, comparator);
        }

        // Create deduplicated array
        Object[] unique = new Object[uniqueCount];
        unique[0] = sorted[0];
        int j = 1;
        for (int i = 1; i < sorted.length; i++) {
            if (!Objects.equals(sorted[i], sorted[i - 1])) {
                unique[j++] = sorted[i];
            }
        }

        return new MezzeSortedSet<>(unique, comparator);
    }

    // ===== ACCESS =====

    /**
     * Returns the number of elements.
     */
    public int size() {
        return elements.length;
    }

    /**
     * Returns true if empty.
     */
    public boolean isEmpty() {
        return elements.length == 0;
    }

    /**
     * Returns true if the element is present.
     */
    public boolean contains(A elem) {
        return binarySearch(elem) >= 0;
    }

    /**
     * Binary search for element.
     * Returns index if found, or -(insertionPoint + 1) if not found.
     */
    @TruffleBoundary
    private int binarySearch(A elem) {
        int low = 0;
        int high = elements.length - 1;

        while (low <= high) {
            int mid = (low + high) >>> 1;
            @SuppressWarnings("unchecked")
            int cmp = comparator.compare(elements[mid], elem);

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

    /**
     * Returns the minimum element, or throws if empty.
     */
    @SuppressWarnings("unchecked")
    public A min() {
        if (isEmpty()) {
            throw new NoSuchElementException("Empty set");
        }
        return (A) elements[0];
    }

    /**
     * Returns the maximum element, or throws if empty.
     */
    @SuppressWarnings("unchecked")
    public A max() {
        if (isEmpty()) {
            throw new NoSuchElementException("Empty set");
        }
        return (A) elements[elements.length - 1];
    }

    /**
     * Returns the element at index.
     */
    @SuppressWarnings("unchecked")
    public A get(int index) {
        return (A) elements[index];
    }

    // ===== UPDATE =====

    /**
     * Returns a new sorted set with the element added.
     */
    @TruffleBoundary
    public MezzeSortedSet<A> add(A elem) {
        int idx = binarySearch(elem);
        if (idx >= 0) {
            return this;  // Already present
        }

        int insertPos = -(idx + 1);
        Object[] newElements = new Object[elements.length + 1];
        System.arraycopy(elements, 0, newElements, 0, insertPos);
        newElements[insertPos] = elem;
        System.arraycopy(elements, insertPos, newElements, insertPos + 1, elements.length - insertPos);

        return new MezzeSortedSet<>(newElements, comparator);
    }

    /**
     * Returns a new sorted set with the element removed.
     */
    @TruffleBoundary
    public MezzeSortedSet<A> remove(A elem) {
        int idx = binarySearch(elem);
        if (idx < 0) {
            return this;  // Not present
        }

        if (elements.length == 1) {
            return empty();
        }

        Object[] newElements = new Object[elements.length - 1];
        System.arraycopy(elements, 0, newElements, 0, idx);
        System.arraycopy(elements, idx + 1, newElements, idx, elements.length - idx - 1);

        return new MezzeSortedSet<>(newElements, comparator);
    }

    // ===== RANGE OPERATIONS =====

    /**
     * Returns elements in range [from, to].
     */
    @TruffleBoundary
    public MezzeSortedSet<A> range(A from, A to) {
        int fromIdx = binarySearch(from);
        if (fromIdx < 0) fromIdx = -(fromIdx + 1);

        int toIdx = binarySearch(to);
        if (toIdx < 0) {
            toIdx = -(toIdx + 1);
        } else {
            toIdx++;  // Include the 'to' element
        }

        if (fromIdx >= toIdx) {
            return empty();
        }

        return new MezzeSortedSet<>(Arrays.copyOfRange(elements, fromIdx, toIdx), comparator);
    }

    /**
     * Returns elements >= from.
     */
    @TruffleBoundary
    public MezzeSortedSet<A> rangeFrom(A from) {
        int idx = binarySearch(from);
        if (idx < 0) idx = -(idx + 1);

        if (idx >= elements.length) {
            return empty();
        }

        return new MezzeSortedSet<>(Arrays.copyOfRange(elements, idx, elements.length), comparator);
    }

    /**
     * Returns elements <= to.
     */
    @TruffleBoundary
    public MezzeSortedSet<A> rangeTo(A to) {
        int idx = binarySearch(to);
        if (idx < 0) {
            idx = -(idx + 1);
        } else {
            idx++;  // Include the 'to' element
        }

        if (idx <= 0) {
            return empty();
        }

        return new MezzeSortedSet<>(Arrays.copyOfRange(elements, 0, idx), comparator);
    }

    // ===== NAVIGATION =====

    /**
     * Returns the greatest element <= given, or null if none.
     */
    @TruffleBoundary
    @SuppressWarnings("unchecked")
    public A floor(A elem) {
        int idx = binarySearch(elem);
        if (idx >= 0) {
            return (A) elements[idx];
        }
        int insertPos = -(idx + 1);
        if (insertPos == 0) {
            return null;
        }
        return (A) elements[insertPos - 1];
    }

    /**
     * Returns the smallest element >= given, or null if none.
     */
    @TruffleBoundary
    @SuppressWarnings("unchecked")
    public A ceiling(A elem) {
        int idx = binarySearch(elem);
        if (idx >= 0) {
            return (A) elements[idx];
        }
        int insertPos = -(idx + 1);
        if (insertPos >= elements.length) {
            return null;
        }
        return (A) elements[insertPos];
    }

    /**
     * Returns the greatest element < given, or null if none.
     */
    @TruffleBoundary
    @SuppressWarnings("unchecked")
    public A lower(A elem) {
        int idx = binarySearch(elem);
        if (idx >= 0) {
            idx--;
        } else {
            idx = -(idx + 1) - 1;
        }
        if (idx < 0) {
            return null;
        }
        return (A) elements[idx];
    }

    /**
     * Returns the smallest element > given, or null if none.
     */
    @TruffleBoundary
    @SuppressWarnings("unchecked")
    public A higher(A elem) {
        int idx = binarySearch(elem);
        if (idx >= 0) {
            idx++;
        } else {
            idx = -(idx + 1);
        }
        if (idx >= elements.length) {
            return null;
        }
        return (A) elements[idx];
    }

    // ===== SET OPERATIONS =====

    /**
     * Compares two elements using the comparator.
     * For Long elements, uses unboxed comparison for speed.
     */
    @SuppressWarnings("unchecked")
    private int compareElements(Object a, Object b) {
        // Fast path for Long (common case)
        if (a instanceof Long la && b instanceof Long lb) {
            return Long.compare(la, lb);
        }
        // Fast path for Integer
        if (a instanceof Integer ia && b instanceof Integer ib) {
            return Integer.compare(ia, ib);
        }
        // Fast path for String
        if (a instanceof String sa && b instanceof String sb) {
            return sa.compareTo(sb);
        }
        // General case
        return comparator.compare(a, b);
    }

    /**
     * Union with another sorted set.
     * Optimized: uses specialized comparison for common types.
     */
    @TruffleBoundary
    public MezzeSortedSet<A> union(MezzeSortedSet<A> other) {
        if (isEmpty()) return other;
        if (other.isEmpty()) return this;

        // Merge sorted arrays
        Object[] result = new Object[elements.length + other.elements.length];
        int i = 0, j = 0, k = 0;

        while (i < elements.length && j < other.elements.length) {
            int cmp = compareElements(elements[i], other.elements[j]);
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

        return new MezzeSortedSet<>(Arrays.copyOf(result, k), comparator);
    }

    /**
     * Intersection with another sorted set.
     * Optimized: uses specialized comparison for common types.
     */
    @TruffleBoundary
    public MezzeSortedSet<A> intersect(MezzeSortedSet<A> other) {
        if (isEmpty() || other.isEmpty()) return empty();

        Object[] result = new Object[Math.min(elements.length, other.elements.length)];
        int i = 0, j = 0, k = 0;

        while (i < elements.length && j < other.elements.length) {
            int cmp = compareElements(elements[i], other.elements[j]);
            if (cmp < 0) {
                i++;
            } else if (cmp > 0) {
                j++;
            } else {
                result[k++] = elements[i++];
                j++;
            }
        }

        if (k == 0) return empty();
        return new MezzeSortedSet<>(Arrays.copyOf(result, k), comparator);
    }

    /**
     * Difference (this - other).
     * Optimized: uses specialized comparison for common types.
     */
    @TruffleBoundary
    public MezzeSortedSet<A> difference(MezzeSortedSet<A> other) {
        if (isEmpty()) return this;
        if (other.isEmpty()) return this;

        Object[] result = new Object[elements.length];
        int i = 0, j = 0, k = 0;

        while (i < elements.length && j < other.elements.length) {
            int cmp = compareElements(elements[i], other.elements[j]);
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

        if (k == 0) return empty();
        return new MezzeSortedSet<>(Arrays.copyOf(result, k), comparator);
    }

    // ===== ITERATION =====

    @Override
    public Iterator<A> iterator() {
        return new Iterator<>() {
            private int index = 0;

            @Override
            public boolean hasNext() {
                return index < elements.length;
            }

            @Override
            @SuppressWarnings("unchecked")
            public A next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return (A) elements[index++];
            }
        };
    }

    /**
     * Returns all elements as array.
     */
    @SuppressWarnings("unchecked")
    public A[] toArray() {
        return (A[]) elements.clone();
    }

    // ===== OBJECT METHODS =====

    @Override
    @TruffleBoundary
    public String toString() {
        StringBuilder sb = new StringBuilder("SortedSet(");
        for (int i = 0; i < elements.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(elements[i]);
        }
        sb.append(")");
        return sb.toString();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof MezzeSortedSet<?> other)) return false;
        return Arrays.equals(elements, other.elements);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(elements);
    }
}
