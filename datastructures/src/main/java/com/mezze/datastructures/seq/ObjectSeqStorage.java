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

    @CompilationFinal
    private final int length;

    private final Object[] ownerToken;

    public ObjectSeqStorage(Object[] data) {
        this.data = data;
        this.length = data.length;
        this.ownerToken = null;
    }

    private ObjectSeqStorage(Object[] data, int length, Object[] ownerToken) {
        this.data = data;
        this.length = length;
        this.ownerToken = ownerToken;
    }

    private boolean isOwner() {
        return ownerToken != null && ownerToken[0] == this;
    }

    private Object[] trimmedData() {
        if (length == data.length) return data;
        return Arrays.copyOf(data, length);
    }

    @Override
    public int size() {
        return length;
    }

    @Override
    public Object get(int index) {
        return data[index];
    }

    @Override
    public SeqStorage update(int index, Object value) {
        Object[] newData = Arrays.copyOf(data, length);
        newData[index] = value;
        return new ObjectSeqStorage(newData);
    }

    @Override
    public SeqStorage append(Object value) {
        if (isOwner() && length < data.length) {
            data[length] = value;
            var result = new ObjectSeqStorage(data, length + 1, ownerToken);
            ownerToken[0] = result;
            return result;
        }
        int newCapacity = Math.max(length + 1, length + (length >> 1) + 4);
        Object[] newData = new Object[newCapacity];
        System.arraycopy(data, 0, newData, 0, length);
        newData[length] = value;
        Object[] token = new Object[1];
        var result = new ObjectSeqStorage(newData, length + 1, token);
        token[0] = result;
        return result;
    }

    @Override
    public SeqStorage prepend(Object value) {
        Object[] newData = new Object[length + 1];
        newData[0] = value;
        System.arraycopy(data, 0, newData, 1, length);
        return new ObjectSeqStorage(newData);
    }

    @Override
    public SeqStorage slice(int start, int end) {
        if (start < 0 || end > length || start > end) {
            throw new IndexOutOfBoundsException("Invalid slice range: " + start + " to " + end);
        }
        if (start == end) {
            return EmptySeqStorage.INSTANCE;
        }
        if (start == 0 && end == length) {
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

        Object[] newData = new Object[length + otherData.length];
        System.arraycopy(data, 0, newData, 0, length);
        System.arraycopy(otherData, 0, newData, length, otherData.length);
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
        return trimmedData();
    }

    // ===== Search operations =====

    /**
     * Find index of element using equals(), or -1 if not found.
     */
    public int indexOf(Object value) {
        for (int i = 0; i < length; i++) {
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
        for (int i = length - 1; i >= 0; i--) {
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
        Object[] newData = new Object[length];
        for (int i = 0; i < length; i++) {
            newData[i] = data[length - 1 - i];
        }
        return new ObjectSeqStorage(newData);
    }

    /**
     * Sort using natural ordering (elements must be Comparable).
     */
    @TruffleBoundary
    @SuppressWarnings("unchecked")
    public ObjectSeqStorage sort() {
        Object[] newData = Arrays.copyOf(data, length);
        Arrays.sort(newData, (a, b) -> ((Comparable<Object>) a).compareTo(b));
        return new ObjectSeqStorage(newData);
    }

    /**
     * Sort using custom comparator.
     */
    @TruffleBoundary
    public ObjectSeqStorage sortWith(Comparator<Object> comparator) {
        Object[] newData = Arrays.copyOf(data, length);
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
        return "ObjectSeqStorage" + Arrays.toString(trimmedData());
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof ObjectSeqStorage other)) return false;
        return Arrays.equals(trimmedData(), other.trimmedData());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(trimmedData());
    }

    // ===== MUTABLE OPERATIONS (for LocalMut effect) =====

    /**
     * Creates a mutable copy of this storage.
     * Used by to_mut to get a fresh copy that can be mutated.
     */
    public ObjectSeqStorage copyToMutable() {
        return new ObjectSeqStorage(Arrays.copyOf(data, length));
    }

    /**
     * In-place set - mutates the underlying array.
     * Only safe within LocalMut context.
     */
    public void mutSet(int index, Object value) {
        data[index] = value;
    }

    /**
     * In-place swap - mutates the underlying array.
     * Only safe within LocalMut context.
     */
    public void mutSwap(int i, int j) {
        Object tmp = data[i];
        data[i] = data[j];
        data[j] = tmp;
    }

    /**
     * In-place reverse - mutates the underlying array.
     * Only safe within LocalMut context.
     */
    public void mutReverse() {
        int left = 0;
        int right = length - 1;
        while (left < right) {
            Object tmp = data[left];
            data[left] = data[right];
            data[right] = tmp;
            left++;
            right--;
        }
    }

    /**
     * In-place fill - mutates the underlying array.
     * Only safe within LocalMut context.
     */
    public void mutFill(Object value) {
        Arrays.fill(data, 0, length, value);
    }

    /**
     * In-place fill range - mutates the underlying array.
     * Only safe within LocalMut context.
     */
    public void mutFillRange(int start, int end, Object value) {
        Arrays.fill(data, start, end, value);
    }

    /**
     * In-place sort using comparator - mutates the underlying array.
     * Only safe within LocalMut context.
     */
    @TruffleBoundary
    public void mutSortWith(Comparator<Object> comparator) {
        Arrays.sort(data, 0, length, comparator);
    }
}
