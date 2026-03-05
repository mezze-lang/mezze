package com.mezze.datastructures.seq;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;

/**
 * General Object[] storage for heterogeneous sequences.
 * Used when elements are not all the same primitive type.
 */
public final class ObjectSeqStorage implements SeqStorage {

    @CompilationFinal(dimensions = 1)
    private final Object[] data;

    public ObjectSeqStorage(Object[] data) {
        this.data = data;
    }

    @Override
    public int size() {
        return data.length;
    }

    @Override
    public Object get(int index) {
        return data[index];
    }

    @Override
    public SeqStorage update(int index, Object value) {
        Object[] newData = data.clone();
        newData[index] = value;
        return new ObjectSeqStorage(newData);
    }

    @Override
    public SeqStorage append(Object value) {
        Object[] newData = Arrays.copyOf(data, data.length + 1);
        newData[data.length] = value;
        return new ObjectSeqStorage(newData);
    }

    @Override
    public SeqStorage prepend(Object value) {
        Object[] newData = new Object[data.length + 1];
        newData[0] = value;
        System.arraycopy(data, 0, newData, 1, data.length);
        return new ObjectSeqStorage(newData);
    }

    @Override
    public SeqStorage slice(int start, int end) {
        if (start < 0 || end > data.length || start > end) {
            throw new IndexOutOfBoundsException("Invalid slice range: " + start + " to " + end);
        }
        if (start == end) {
            return EmptySeqStorage.INSTANCE;
        }
        if (start == 0 && end == data.length) {
            return this;
        }
        return new ObjectSeqStorage(Arrays.copyOfRange(data, start, end));
    }

    @Override
    public SeqStorage concat(SeqStorage other) {
        if (other.isEmpty()) {
            return this;
        }

        Object[] otherData;
        if (other instanceof ObjectSeqStorage objOther) {
            otherData = objOther.data;
        } else if (other instanceof LongSeqStorage longOther) {
            otherData = boxLongArray(longOther.getArray());
        } else if (other instanceof DoubleSeqStorage doubleOther) {
            otherData = boxDoubleArray(doubleOther.getArray());
        } else if (other instanceof BooleanSeqStorage boolOther) {
            otherData = boxBooleanStorage(boolOther);
        } else {
            throw new IllegalStateException("Unknown storage type: " + other.getClass());
        }

        Object[] newData = new Object[data.length + otherData.length];
        System.arraycopy(data, 0, newData, 0, data.length);
        System.arraycopy(otherData, 0, newData, data.length, otherData.length);
        return new ObjectSeqStorage(newData);
    }

    @Override
    public byte getStrategyTag() {
        return TAG_OBJECT;
    }

    /**
     * Returns the underlying array.
     */
    public Object[] getArray() {
        return data;
    }

    // ===== Search operations =====

    /**
     * Find index of element using equals(), or -1 if not found.
     */
    public int indexOf(Object value) {
        for (int i = 0; i < data.length; i++) {
            if (Objects.equals(data[i], value)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Find last index of element, or -1 if not found.
     */
    public int lastIndexOf(Object value) {
        for (int i = data.length - 1; i >= 0; i--) {
            if (Objects.equals(data[i], value)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Check if contains element.
     */
    public boolean contains(Object value) {
        return indexOf(value) >= 0;
    }

    /**
     * Reverse the sequence.
     */
    public ObjectSeqStorage reverse() {
        Object[] newData = new Object[data.length];
        for (int i = 0; i < data.length; i++) {
            newData[i] = data[data.length - 1 - i];
        }
        return new ObjectSeqStorage(newData);
    }

    /**
     * Sort using natural ordering (elements must be Comparable).
     */
    @TruffleBoundary
    @SuppressWarnings("unchecked")
    public ObjectSeqStorage sort() {
        Object[] newData = data.clone();
        Arrays.sort(newData, (a, b) -> ((Comparable<Object>) a).compareTo(b));
        return new ObjectSeqStorage(newData);
    }

    /**
     * Sort using custom comparator.
     */
    @TruffleBoundary
    public ObjectSeqStorage sortWith(Comparator<Object> comparator) {
        Object[] newData = data.clone();
        Arrays.sort(newData, comparator);
        return new ObjectSeqStorage(newData);
    }

    /**
     * Remove duplicates (keeping first occurrence).
     */
    @TruffleBoundary
    public ObjectSeqStorage distinct() {
        java.util.LinkedHashSet<Object> seen = new java.util.LinkedHashSet<>();
        for (Object elem : data) {
            seen.add(elem);
        }
        return new ObjectSeqStorage(seen.toArray());
    }

    // ===== Helper methods for boxing =====

    @TruffleBoundary
    private static Object[] boxLongArray(long[] longs) {
        Object[] result = new Object[longs.length];
        for (int i = 0; i < longs.length; i++) {
            result[i] = longs[i];
        }
        return result;
    }

    @TruffleBoundary
    private static Object[] boxDoubleArray(double[] doubles) {
        Object[] result = new Object[doubles.length];
        for (int i = 0; i < doubles.length; i++) {
            result[i] = doubles[i];
        }
        return result;
    }

    @TruffleBoundary
    private static Object[] boxBooleanStorage(BooleanSeqStorage bools) {
        Object[] result = new Object[bools.size()];
        for (int i = 0; i < bools.size(); i++) {
            result[i] = bools.getBoolean(i);
        }
        return result;
    }

    @Override
    @TruffleBoundary
    public String toString() {
        return "ObjectSeqStorage" + Arrays.toString(data);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof ObjectSeqStorage other)) return false;
        return Arrays.equals(data, other.data);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(data);
    }
}
