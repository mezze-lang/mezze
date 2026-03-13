package com.mezze.datastructures.list;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.util.Arrays;

/**
 * Mutable builder for MezzeList.
 *
 * Since MezzeList is a linked list (not suitable for random-access mutation),
 * this class provides an array-backed mutable view that can be converted back
 * to a MezzeList when mutation is complete.
 *
 * Usage pattern (in Mezze):
 *   let list = [1, 2, 3, 4, 5]
 *   let result = LocalMut.run {} -> {
 *       let mut_arr = List.to_mut { list }
 *       # mutate mut_arr...
 *       List.to_immut { mut_arr }
 *   }
 */
public final class MutableListBuilder<A> {

    private Object[] data;
    private int size;

    private MutableListBuilder(Object[] data, int size) {
        this.data = data;
        this.size = size;
    }

    /**
     * Creates a mutable builder from a MezzeList.
     * Copies all elements into an array.
     */
    @TruffleBoundary
    public static <A> MutableListBuilder<A> fromList(MezzeList<A> list) {
        int size = list.size();
        Object[] data = new Object[Math.max(size, 8)];
        int i = 0;
        for (A elem : list) {
            data[i++] = elem;
        }
        return new MutableListBuilder<>(data, size);
    }

    /**
     * Creates an empty mutable builder with given capacity.
     */
    public static <A> MutableListBuilder<A> withCapacity(int capacity) {
        return new MutableListBuilder<>(new Object[Math.max(capacity, 8)], 0);
    }

    /**
     * Returns the number of elements.
     */
    public int size() {
        return size;
    }

    /**
     * Returns true if empty.
     */
    public boolean isEmpty() {
        return size == 0;
    }

    /**
     * Gets element at index.
     */
    @SuppressWarnings("unchecked")
    public A get(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
        return (A) data[index];
    }

    // ===== MUTABLE OPERATIONS =====

    /**
     * Sets element at index.
     */
    public void mutSet(int index, A value) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
        data[index] = value;
    }

    /**
     * Swaps elements at indices i and j.
     */
    public void mutSwap(int i, int j) {
        if (i < 0 || i >= size || j < 0 || j >= size) {
            throw new IndexOutOfBoundsException("Indices: " + i + ", " + j + ", Size: " + size);
        }
        Object tmp = data[i];
        data[i] = data[j];
        data[j] = tmp;
    }

    /**
     * Appends element to end.
     */
    @TruffleBoundary
    public void mutPush(A value) {
        ensureCapacity(size + 1);
        data[size++] = value;
    }

    /**
     * Removes and returns last element.
     */
    @SuppressWarnings("unchecked")
    public A mutPop() {
        if (size == 0) {
            throw new java.util.NoSuchElementException("Empty builder");
        }
        A result = (A) data[--size];
        data[size] = null; // Help GC
        return result;
    }

    /**
     * Reverses elements in place.
     */
    public void mutReverse() {
        int left = 0;
        int right = size - 1;
        while (left < right) {
            Object tmp = data[left];
            data[left] = data[right];
            data[right] = tmp;
            left++;
            right--;
        }
    }

    /**
     * Sorts elements in place using natural ordering.
     */
    @TruffleBoundary
    @SuppressWarnings("unchecked")
    public void mutSort() {
        Arrays.sort(data, 0, size, (a, b) -> ((Comparable<Object>) a).compareTo(b));
    }

    /**
     * Sorts elements in place using comparator.
     */
    @TruffleBoundary
    public void mutSortWith(java.util.Comparator<Object> comparator) {
        Arrays.sort(data, 0, size, comparator);
    }

    /**
     * Clears all elements.
     */
    @TruffleBoundary
    public void mutClear() {
        Arrays.fill(data, 0, size, null);
        size = 0;
    }

    /**
     * Fills range with value.
     */
    public void mutFillRange(int start, int end, A value) {
        if (start < 0 || end > size || start > end) {
            throw new IndexOutOfBoundsException("Range: " + start + " to " + end + ", Size: " + size);
        }
        Arrays.fill(data, start, end, value);
    }

    // ===== CONVERSION =====

    /**
     * Converts back to an immutable MezzeList.
     * Builds the list from back to front for O(n) construction.
     */
    @TruffleBoundary
    @SuppressWarnings("unchecked")
    public MezzeList<A> toList() {
        MezzeList<A> result = MezzeList.empty();
        for (int i = size - 1; i >= 0; i--) {
            result = MezzeList.cons((A) data[i], result);
        }
        return result;
    }

    /**
     * Returns a copy of the internal array (sized to actual elements).
     */
    public Object[] toArray() {
        return Arrays.copyOf(data, size);
    }

    // ===== INTERNAL =====

    private void ensureCapacity(int minCapacity) {
        if (minCapacity > data.length) {
            int newCapacity = Math.max(data.length * 2, minCapacity);
            data = Arrays.copyOf(data, newCapacity);
        }
    }

    @Override
    @TruffleBoundary
    public String toString() {
        StringBuilder sb = new StringBuilder("MutableListBuilder(");
        for (int i = 0; i < size; i++) {
            if (i > 0) sb.append(", ");
            sb.append(data[i]);
        }
        sb.append(")");
        return sb.toString();
    }
}
