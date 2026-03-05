package com.mezze.datastructures.set;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.util.Arrays;

/**
 * Unboxed long[] storage for Int/Nat sets.
 * Eliminates boxing overhead for numeric sets.
 *
 * Elements are stored in sorted order for O(log n) binary search lookups.
 */
public final class LongSetStorage implements SetStorage {

    public static final int MAX_SIZE = 32;

    @CompilationFinal(dimensions = 1)
    private final long[] elements;  // Always sorted

    @CompilationFinal
    private final boolean sorted;  // True if elements are definitely sorted

    /**
     * Creates storage from elements. Sorts if needed.
     */
    public LongSetStorage(long[] elements) {
        this(elements, false);
    }

    /**
     * Creates storage from elements with sort hint.
     * @param alreadySorted if true, skips sorting
     */
    public LongSetStorage(long[] elements, boolean alreadySorted) {
        if (alreadySorted || elements.length <= 1) {
            this.elements = elements;
            this.sorted = true;
        } else {
            // Sort on construction for binary search
            this.elements = elements.clone();
            Arrays.sort(this.elements);
            this.sorted = true;
        }
    }

    /**
     * Creates storage from pre-sorted elements (internal use).
     */
    private static LongSetStorage fromSorted(long[] sortedElements) {
        return new LongSetStorage(sortedElements, true);
    }

    @Override
    public int size() {
        return elements.length;
    }

    @Override
    public boolean contains(Object elem) {
        if (elem instanceof Long l) {
            return containsLong(l);
        }
        return false;
    }

    /**
     * Unboxed contains check using binary search. O(log n).
     */
    public boolean containsLong(long elem) {
        return binarySearch(elem) >= 0;
    }

    /**
     * Binary search for element.
     * @return index if found, or -(insertionPoint + 1) if not found
     */
    private int binarySearch(long elem) {
        int low = 0;
        int high = elements.length - 1;

        while (low <= high) {
            int mid = (low + high) >>> 1;
            long midVal = elements[mid];

            if (midVal < elem) {
                low = mid + 1;
            } else if (midVal > elem) {
                high = mid - 1;
            } else {
                return mid;
            }
        }
        return -(low + 1);
    }

    @Override
    @TruffleBoundary
    public SetStorage add(Object elem) {
        if (elem instanceof Long l) {
            return addLong(l);
        }
        // Type changed - transition to generic
        return transitionToGeneric().add(elem);
    }

    /**
     * Unboxed add. Maintains sorted order for binary search.
     */
    @TruffleBoundary
    public SetStorage addLong(long elem) {
        int idx = binarySearch(elem);
        if (idx >= 0) {
            return this;  // Already present
        }

        if (elements.length < MAX_SIZE) {
            // Insert at correct position to maintain sorted order
            int insertPos = -(idx + 1);
            long[] newElements = new long[elements.length + 1];
            System.arraycopy(elements, 0, newElements, 0, insertPos);
            newElements[insertPos] = elem;
            System.arraycopy(elements, insertPos, newElements, insertPos + 1, elements.length - insertPos);
            return fromSorted(newElements);
        }

        // Transition to HashSetStorage
        return transitionToHash().add(elem);
    }

    @Override
    @TruffleBoundary
    public SetStorage remove(Object elem) {
        if (elem instanceof Long l) {
            return removeLong(l);
        }
        return this;
    }

    /**
     * Unboxed remove using binary search.
     */
    @TruffleBoundary
    public SetStorage removeLong(long elem) {
        int idx = binarySearch(elem);

        if (idx < 0) {
            return this;  // Not found
        }

        if (elements.length == 1) {
            return EmptySetStorage.INSTANCE;
        }

        if (elements.length == 2) {
            long other = elements[idx == 0 ? 1 : 0];
            return new SingleElemSetStorage(other);
        }

        long[] newElements = new long[elements.length - 1];
        System.arraycopy(elements, 0, newElements, 0, idx);
        System.arraycopy(elements, idx + 1, newElements, idx, elements.length - idx - 1);

        return fromSorted(newElements);  // Already sorted
    }

    @Override
    public Object[] toArray() {
        Object[] result = new Object[elements.length];
        for (int i = 0; i < elements.length; i++) {
            result[i] = elements[i];
        }
        return result;
    }

    /**
     * Returns unboxed elements array.
     */
    public long[] toLongArray() {
        return elements.clone();
    }

    @Override
    public byte getStrategyTag() {
        return TAG_LONG;
    }

    /**
     * Iterates over all elements without boxing.
     * Uses direct array iteration for maximum efficiency.
     */
    @Override
    public void forEach(java.util.function.Consumer<Object> consumer) {
        for (int i = 0; i < elements.length; i++) {
            consumer.accept(elements[i]);
        }
    }

    /**
     * Iterates over all elements with unboxed long values.
     */
    public void forEachLong(java.util.function.LongConsumer consumer) {
        for (int i = 0; i < elements.length; i++) {
            consumer.accept(elements[i]);
        }
    }

    /**
     * Returns element at index (unboxed).
     */
    public long getAtLong(int index) {
        return elements[index];
    }

    // ===== SET OPERATIONS (Optimized with two-pointer merge on sorted arrays) =====

    /**
     * Union with another LongSetStorage.
     * Uses two-pointer merge on sorted arrays: O(n + m) instead of O(n * m).
     */
    @TruffleBoundary
    public LongSetStorage unionLong(LongSetStorage other) {
        if (elements.length == 0) return other;
        if (other.elements.length == 0) return this;

        long[] result = new long[elements.length + other.elements.length];
        int i = 0, j = 0, k = 0;

        // Two-pointer merge on sorted arrays
        while (i < elements.length && j < other.elements.length) {
            long a = elements[i];
            long b = other.elements[j];
            if (a < b) {
                result[k++] = a;
                i++;
            } else if (a > b) {
                result[k++] = b;
                j++;
            } else {
                // Equal - add once, skip both
                result[k++] = a;
                i++;
                j++;
            }
        }

        // Copy remaining from this
        while (i < elements.length) {
            result[k++] = elements[i++];
        }
        // Copy remaining from other
        while (j < other.elements.length) {
            result[k++] = other.elements[j++];
        }

        return fromSorted(Arrays.copyOf(result, k));
    }

    /**
     * Intersection with another LongSetStorage.
     * Uses two-pointer scan on sorted arrays: O(n + m) instead of O(n * m).
     */
    @TruffleBoundary
    public LongSetStorage intersectLong(LongSetStorage other) {
        if (elements.length == 0 || other.elements.length == 0) {
            return fromSorted(new long[0]);
        }

        long[] result = new long[Math.min(elements.length, other.elements.length)];
        int i = 0, j = 0, k = 0;

        // Two-pointer intersection on sorted arrays
        while (i < elements.length && j < other.elements.length) {
            long a = elements[i];
            long b = other.elements[j];
            if (a < b) {
                i++;
            } else if (a > b) {
                j++;
            } else {
                // Equal - add to result
                result[k++] = a;
                i++;
                j++;
            }
        }

        if (k == 0) {
            return fromSorted(new long[0]);
        }

        return fromSorted(Arrays.copyOf(result, k));
    }

    /**
     * Difference (this - other).
     * Uses two-pointer scan on sorted arrays: O(n + m) instead of O(n * m).
     */
    @TruffleBoundary
    public LongSetStorage differenceLong(LongSetStorage other) {
        if (elements.length == 0) {
            return this;
        }
        if (other.elements.length == 0) {
            return this;
        }

        long[] result = new long[elements.length];
        int i = 0, j = 0, k = 0;

        // Two-pointer difference on sorted arrays
        while (i < elements.length && j < other.elements.length) {
            long a = elements[i];
            long b = other.elements[j];
            if (a < b) {
                // a not in other, add to result
                result[k++] = a;
                i++;
            } else if (a > b) {
                j++;
            } else {
                // Equal - skip both
                i++;
                j++;
            }
        }

        // Copy remaining from this (all not in other)
        while (i < elements.length) {
            result[k++] = elements[i++];
        }

        if (k == 0) {
            return fromSorted(new long[0]);
        }

        return fromSorted(Arrays.copyOf(result, k));
    }

    /**
     * Sum of all elements.
     */
    public long sum() {
        long result = 0;
        for (int i = 0; i < elements.length; i++) {
            result += elements[i];
        }
        return result;
    }

    /**
     * Minimum element.
     */
    public long min() {
        if (elements.length == 0) {
            throw new IllegalStateException("Empty set has no minimum");
        }
        long result = elements[0];
        for (int i = 1; i < elements.length; i++) {
            if (elements[i] < result) {
                result = elements[i];
            }
        }
        return result;
    }

    /**
     * Maximum element.
     */
    public long max() {
        if (elements.length == 0) {
            throw new IllegalStateException("Empty set has no maximum");
        }
        long result = elements[0];
        for (int i = 1; i < elements.length; i++) {
            if (elements[i] > result) {
                result = elements[i];
            }
        }
        return result;
    }

    @TruffleBoundary
    private SmallFlatSetStorage transitionToGeneric() {
        Object[] boxed = new Object[elements.length];
        for (int i = 0; i < elements.length; i++) {
            boxed[i] = elements[i];
        }
        return new SmallFlatSetStorage(boxed);
    }

    @TruffleBoundary
    private HashSetStorage transitionToHash() {
        HashSetStorage hash = new HashSetStorage();
        for (long elem : elements) {
            hash = (HashSetStorage) hash.add(elem);
        }
        return hash;
    }

    @Override
    @TruffleBoundary
    public String toString() {
        return "LongSetStorage" + Arrays.toString(elements);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof LongSetStorage other)) return false;
        if (elements.length != other.elements.length) return false;

        for (long elem : elements) {
            if (!other.containsLong(elem)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = 0;
        for (long elem : elements) {
            result += Long.hashCode(elem);
        }
        return result;
    }
}
