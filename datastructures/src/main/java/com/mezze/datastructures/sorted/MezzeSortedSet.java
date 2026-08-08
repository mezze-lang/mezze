package com.mezze.datastructures.sorted;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

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
@ExportLibrary(InteropLibrary.class)
public final class MezzeSortedSet<A extends Comparable<A>> implements TruffleObject, Iterable<A> {

    @CompilationFinal(dimensions = 1)
    private final Object[] elements;

    @CompilationFinal
    private final int length;

    @CompilationFinal
    private final Comparator<Object> comparator;

    private final Object[] ownerToken;

    @SuppressWarnings("unchecked")
    private MezzeSortedSet(Object[] elements, Comparator<?> comparator) {
        this.elements = elements;
        this.length = elements.length;
        this.comparator = comparator != null
                ? (Comparator<Object>) comparator
                : (a, b) -> ((Comparable<Object>) a).compareTo(b);
        this.ownerToken = null;
    }

    @SuppressWarnings("unchecked")
    private MezzeSortedSet(Object[] elements, int length, Comparator<?> comparator, Object[] ownerToken) {
        this.elements = elements;
        this.length = length;
        this.comparator = comparator != null
                ? (Comparator<Object>) comparator
                : (a, b) -> ((Comparable<Object>) a).compareTo(b);
        this.ownerToken = ownerToken;
    }

    /** Latched flag — see HashMapStorage.ownershipRevoked for rationale. */
    @CompilationFinal
    private boolean ownershipRevoked;

    private boolean isOwner() {
        return !ownershipRevoked && ownerToken != null && ownerToken[0] == this;
    }

    private void revokeOwnership() {
        if (ownershipRevoked) return;
        if (ownerToken != null && ownerToken[0] == this) {
            ownerToken[0] = null;
        }
        ownershipRevoked = true;
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
        return length;
    }

    /**
     * Returns true if empty.
     */
    public boolean isEmpty() {
        return length == 0;
    }

    /**
     * Returns true if the element is present.
     */
    public boolean contains(A elem) {
        revokeOwnership();
        return binarySearch(elem) >= 0;
    }

    /**
     * Binary search for element.
     * Returns index if found, or -(insertionPoint + 1) if not found.
     */
    private int binarySearch(A elem) {
        return binarySearchObject(elem);
    }

    /**
     * Binary search for element (raw type version).
     */
    private int binarySearchObject(Object elem) {
        int low = 0;
        int high = length - 1;

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

    // ===== RAW TYPE METHODS (for interpreter use) =====

    /**
     * Returns true if the element is present (raw type version).
     */
    public boolean containsObject(Object elem) {
        revokeOwnership();
        return binarySearchObject(elem) >= 0;
    }

    /**
     * Returns a new sorted set with the element added (raw type version).
     */
    @SuppressWarnings("unchecked")
    public MezzeSortedSet<A> addObject(Object elem) {
        int idx = binarySearchObject(elem);
        if (idx >= 0) {
            return this;  // Already present
        }

        int insertPos = -(idx + 1);

        if (isOwner() && length < elements.length && insertPos == length) {
            // O(1) fast path: appending at end with spare capacity
            elements[length] = elem;
            Object[] token = ownerToken;
            @SuppressWarnings("unchecked")
            MezzeSortedSet<A> result = new MezzeSortedSet<>(elements, length + 1, comparator, token);
            token[0] = result;
            return result;
        }

        if (isOwner() && length < elements.length) {
            // Spare capacity, inserting in middle — shift in-place
            System.arraycopy(elements, insertPos, elements, insertPos + 1, length - insertPos);
            elements[insertPos] = elem;
            Object[] token = ownerToken;
            @SuppressWarnings("unchecked")
            MezzeSortedSet<A> result = new MezzeSortedSet<>(elements, length + 1, comparator, token);
            token[0] = result;
            return result;
        }

        // Allocate with spare capacity
        int newCapacity = Math.max(length + 1, length + (length >> 1) + 4);
        Object[] newElements = new Object[newCapacity];
        System.arraycopy(elements, 0, newElements, 0, insertPos);
        newElements[insertPos] = elem;
        System.arraycopy(elements, insertPos, newElements, insertPos + 1, length - insertPos);

        Object[] token = new Object[1];
        @SuppressWarnings("unchecked")
        MezzeSortedSet<A> result = new MezzeSortedSet<>(newElements, length + 1, comparator, token);
        token[0] = result;
        return result;
    }

    /**
     * Returns a new sorted set with the element removed (raw type version).
     */
    @SuppressWarnings("unchecked")
    public MezzeSortedSet<A> removeObject(Object elem) {
        int idx = binarySearchObject(elem);
        if (idx < 0) {
            return this;  // Not present
        }

        if (length == 1) {
            return empty();
        }

        Object[] newElements = new Object[length - 1];
        System.arraycopy(elements, 0, newElements, 0, idx);
        System.arraycopy(elements, idx + 1, newElements, idx, length - idx - 1);

        return new MezzeSortedSet<>(newElements, comparator);
    }

    /**
     * Creates a sorted set from raw Object iterable (for interpreter use).
     */
    @SuppressWarnings("unchecked")
    public static <A extends Comparable<A>> MezzeSortedSet<A> fromIterable(Iterable<?> elements) {
        MezzeSortedSet<A> result = empty();
        for (Object elem : elements) {
            result = result.addObject(elem);
        }
        return result;
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
        return (A) elements[length - 1];
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
    public MezzeSortedSet<A> add(A elem) {
        return addObject(elem);
    }

    /**
     * Returns a new sorted set with the element removed.
     */
    public MezzeSortedSet<A> remove(A elem) {
        int idx = binarySearch(elem);
        if (idx < 0) {
            return this;  // Not present
        }

        if (length == 1) {
            return empty();
        }

        Object[] newElements = new Object[length - 1];
        System.arraycopy(elements, 0, newElements, 0, idx);
        System.arraycopy(elements, idx + 1, newElements, idx, length - idx - 1);

        return new MezzeSortedSet<>(newElements, comparator);
    }

    // ===== RANGE OPERATIONS =====

    /**
     * Returns elements in range [from, to].
     */
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
    public MezzeSortedSet<A> rangeFrom(A from) {
        int idx = binarySearch(from);
        if (idx < 0) idx = -(idx + 1);

        if (idx >= length) {
            return empty();
        }

        return new MezzeSortedSet<>(Arrays.copyOfRange(elements, idx, length), comparator);
    }

    /**
     * Returns elements <= to.
     */
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
    @SuppressWarnings("unchecked")
    public A ceiling(A elem) {
        int idx = binarySearch(elem);
        if (idx >= 0) {
            return (A) elements[idx];
        }
        int insertPos = -(idx + 1);
        if (insertPos >= length) {
            return null;
        }
        return (A) elements[insertPos];
    }

    /**
     * Returns the greatest element < given, or null if none.
     */
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
    @SuppressWarnings("unchecked")
    public A higher(A elem) {
        int idx = binarySearch(elem);
        if (idx >= 0) {
            idx++;
        } else {
            idx = -(idx + 1);
        }
        if (idx >= length) {
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
    public MezzeSortedSet<A> union(MezzeSortedSet<A> other) {
        if (isEmpty()) return other;
        if (other.isEmpty()) return this;

        // Merge sorted arrays
        Object[] result = new Object[length + other.length];
        int i = 0, j = 0, k = 0;

        while (i < length && j < other.length) {
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

        while (i < length) {
            result[k++] = elements[i++];
        }
        while (j < other.length) {
            result[k++] = other.elements[j++];
        }

        return new MezzeSortedSet<>(Arrays.copyOf(result, k), comparator);
    }

    /**
     * Intersection with another sorted set.
     * Optimized: uses specialized comparison for common types.
     */
    public MezzeSortedSet<A> intersect(MezzeSortedSet<A> other) {
        if (isEmpty() || other.isEmpty()) return empty();

        Object[] result = new Object[Math.min(length, other.length)];
        int i = 0, j = 0, k = 0;

        while (i < length && j < other.length) {
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
    public MezzeSortedSet<A> difference(MezzeSortedSet<A> other) {
        if (isEmpty()) return this;
        if (other.isEmpty()) return this;

        Object[] result = new Object[length];
        int i = 0, j = 0, k = 0;

        while (i < length && j < other.length) {
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

        while (i < length) {
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
                return index < length;
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
        return (A[]) Arrays.copyOf(elements, length);
    }

    // ===== MUTABLE OPERATIONS (for LocalMut effect) =====

    /**
     * Creates a mutable copy of this sorted set.
     * Used by to_mut to get a fresh copy that can be mutated.
     */
    public MezzeSortedSet<A> copyToMutable() {
        return new MezzeSortedSet<>(elements.clone(), comparator);
    }

    /**
     * Returns the internal elements array (for building mutable sorted sets).
     * Only for internal use within LocalMut context.
     */
    public Object[] getElementsInternal() {
        return elements;
    }

    /**
     * In-place set at index - replaces element at given position.
     * Note: Does NOT maintain sort order - caller is responsible for that.
     * Only safe within LocalMut context.
     */
    public void mutSet(int index, A value) {
        if (index < 0 || index >= length) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + length);
        }
        elements[index] = value;
    }

    /**
     * In-place swap elements at indices i and j.
     * Only safe within LocalMut context.
     */
    public void mutSwap(int i, int j) {
        if (i < 0 || i >= length || j < 0 || j >= length) {
            throw new IndexOutOfBoundsException("Indices: " + i + ", " + j + ", Size: " + length);
        }
        Object tmp = elements[i];
        elements[i] = elements[j];
        elements[j] = tmp;
    }

    // ===== OBJECT METHODS =====

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("SortedSet(");
        for (int i = 0; i < length; i++) {
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
        if (this.length != other.length) return false;
        for (int i = 0; i < length; i++) {
            if (!java.util.Objects.equals(elements[i], other.elements[i])) return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int h = 1;
        for (int i = 0; i < length; i++) {
            h = 31 * h + java.util.Objects.hashCode(elements[i]);
        }
        return h;
    }

    // ===== INTEROP LIBRARY =====

    @ExportMessage
   @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    boolean hasArrayElements() {
        return true;
    }

    @ExportMessage
   @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    long getArraySize() {
        return length;
    }

    @ExportMessage
   @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    boolean isArrayElementReadable(long index) {
        return index >= 0 && index < length;
    }

    @ExportMessage
   @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    Object readArrayElement(long index) throws InvalidArrayIndexException {
        if (index < 0 || index >= length) {
            throw InvalidArrayIndexException.create(index);
        }
        return elements[(int) index];
    }

    @ExportMessage
   @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    boolean isArrayElementModifiable(@SuppressWarnings("unused") long index) {
        return false;
    }

    @ExportMessage
   @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    boolean isArrayElementInsertable(@SuppressWarnings("unused") long index) {
        return false;
    }

    @ExportMessage
   @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    void writeArrayElement(@SuppressWarnings("unused") long index, @SuppressWarnings("unused") Object value) throws UnsupportedMessageException {
        throw UnsupportedMessageException.create();
    }

    @ExportMessage
   @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    boolean hasIterator() {
        return true;
    }

    @ExportMessage
   @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    Object getIterator() {
        return new SortedSetIterator(iterator());
    }

    @ExportLibrary(InteropLibrary.class)
    static final class SortedSetIterator implements TruffleObject {
        private final Iterator<?> iterator;

        SortedSetIterator(Iterator<?> iterator) {
            this.iterator = iterator;
        }

        @ExportMessage
       @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        boolean isIterator() {
            return true;
        }

        @ExportMessage
       @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        boolean hasIteratorNextElement() {
            return iterator.hasNext();
        }

        @ExportMessage
       @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        Object getIteratorNextElement() throws UnsupportedMessageException {
            if (!iterator.hasNext()) {
                throw UnsupportedMessageException.create();
            }
            return iterator.next();
        }
    }
}
