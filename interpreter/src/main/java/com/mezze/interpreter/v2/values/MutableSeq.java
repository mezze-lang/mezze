package com.mezze.interpreter.v2.values;

import com.mezze.datastructures.seq.MezzeSeq;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.IndirectCallNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Mutable sequence wrapper for safe local mutation.
 *
 * This is the runtime representation of MutSeq - a mutable array
 * that can be sorted, reversed, filled, etc. in place.
 */
public final class MutableSeq {
    private Object[] data;
    private int size;

    private MutableSeq(Object[] data, int size) {
        this.data = data;
        this.size = size;
    }

    @TruffleBoundary
    public static MutableSeq fromImmutable(MezzeSeq seq) {
        int len = (int) seq.length();
        Object[] data = new Object[len];
        for (int i = 0; i < len; i++) {
            data[i] = seq.get(i);
        }
        return new MutableSeq(data, len);
    }

    @TruffleBoundary
    public static MutableSeq withCapacity(int capacity) {
        return new MutableSeq(new Object[capacity], 0);
    }

    @TruffleBoundary
    public MezzeSeq toImmutable() {
        Object[] copy = Arrays.copyOf(data, size);
        return MezzeSeq.fromArray(copy);
    }

    public int size() {
        return size;
    }

    public Object get(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
        return data[index];
    }

    public void set(int index, Object value) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
        data[index] = value;
    }

    @TruffleBoundary
    public void sort() {
        Arrays.sort(data, 0, size, (a, b) -> {
            if (a instanceof Long la && b instanceof Long lb) {
                return Long.compare(la, lb);
            }
            if (a instanceof Double da && b instanceof Double db) {
                return Double.compare(da, db);
            }
            if (a instanceof String sa && b instanceof String sb) {
                return sa.compareTo(sb);
            }
            throw new RuntimeException("Cannot compare: " + a.getClass() + " with " + b.getClass());
        });
    }

    @TruffleBoundary
    public void sortBy(MezzeFunction cmp, IndirectCallNode callNode) {
        Object[] closureValues = cmp.getClosureValues();
        int closureLen = closureValues.length;
        Object[] callArgs = new Object[closureLen + 1];
        System.arraycopy(closureValues, 0, callArgs, 0, closureLen);

        Arrays.sort(data, 0, size, (a, b) -> {
            MezzeRecord argRecord = MezzeRecord.fromFields(
                new String[]{"a", "b"},
                new Object[]{a, b}
            );
            callArgs[closureLen] = argRecord;
            Object result = callNode.call(cmp.getCallTarget(), callArgs);
            if (result instanceof Long l) {
                return l.intValue();
            }
            throw new RuntimeException("Comparator must return Int, got: " + result.getClass());
        });
    }

    @TruffleBoundary
    public void reverse() {
        int left = 0;
        int right = size - 1;
        while (left < right) {
            Object temp = data[left];
            data[left] = data[right];
            data[right] = temp;
            left++;
            right--;
        }
    }

    @TruffleBoundary
    public void fill(Object value) {
        Arrays.fill(data, 0, size, value);
    }

    @TruffleBoundary
    public void fillRange(int start, int end, Object value) {
        if (start < 0) start = 0;
        if (end > size) end = size;
        Arrays.fill(data, start, end, value);
    }

    public void swap(int i, int j) {
        if (i < 0 || i >= size || j < 0 || j >= size) {
            throw new IndexOutOfBoundsException("Swap indices out of bounds");
        }
        Object temp = data[i];
        data[i] = data[j];
        data[j] = temp;
    }

    /**
     * Push an element to the end of the sequence.
     * Grows the array if necessary.
     */
    @TruffleBoundary
    public void push(Object value) {
        ensureCapacity(size + 1);
        data[size++] = value;
    }

    /**
     * Pop and return the last element.
     * Returns null if empty.
     */
    public Object pop() {
        if (size == 0) {
            return null;
        }
        Object value = data[--size];
        data[size] = null; // Help GC
        return value;
    }

    /**
     * Get element at index, returning null if out of bounds.
     * This is the safe version for Option return.
     */
    public Object getSafe(int index) {
        if (index < 0 || index >= size) {
            return null;
        }
        return data[index];
    }

    /**
     * Set element at index, returning true if successful.
     */
    public boolean setSafe(int index, Object value) {
        if (index < 0 || index >= size) {
            return false;
        }
        data[index] = value;
        return true;
    }

    /**
     * Clear all elements.
     */
    public void clear() {
        Arrays.fill(data, 0, size, null);
        size = 0;
    }

    /**
     * Insert element at index, shifting elements right.
     */
    @TruffleBoundary
    public boolean insert(int index, Object value) {
        if (index < 0 || index > size) {
            return false;
        }
        ensureCapacity(size + 1);
        System.arraycopy(data, index, data, index + 1, size - index);
        data[index] = value;
        size++;
        return true;
    }

    /**
     * Remove element at index, shifting elements left.
     * Returns the removed element or null if out of bounds.
     */
    @TruffleBoundary
    public Object remove(int index) {
        if (index < 0 || index >= size) {
            return null;
        }
        Object value = data[index];
        System.arraycopy(data, index + 1, data, index, size - index - 1);
        data[--size] = null;
        return value;
    }

    /**
     * Ensure the array has at least the given capacity.
     */
    private void ensureCapacity(int minCapacity) {
        if (minCapacity > data.length) {
            int newCapacity = Math.max(data.length * 2, minCapacity);
            data = Arrays.copyOf(data, newCapacity);
        }
    }
}
