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

    private final int offset;

    private final Object[] ownerToken;

    public ObjectSeqStorage(Object[] data) {
        this.data = data;
        this.length = data.length;
        this.offset = 0;
        this.ownerToken = null;
    }

    private ObjectSeqStorage(Object[] data, int length, Object[] ownerToken) {
        this.data = data;
        this.length = length;
        this.offset = 0;
        this.ownerToken = ownerToken;
    }

    /** Zero-copy slice view. */
    private ObjectSeqStorage(Object[] data, int offset, int length) {
        this.data = data;
        this.offset = offset;
        this.length = length;
        this.ownerToken = null;
    }

    private boolean isOwner() {
        return offset == 0 && ownerToken != null && ownerToken[0] == this;
    }

    private Object[] trimmedData() {
        if (offset == 0 && length == data.length) return data;
        return Arrays.copyOfRange(data, offset, offset + length);
    }

    @Override
    public int size() {
        return length;
    }

    @Override
    public Object get(int index) {
        return data[offset + index];
    }

    @Override
    public SeqStorage update(int index, Object value) {
        Object[] newData = new Object[length];
        System.arraycopy(data, offset, newData, 0, length);
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
        System.arraycopy(data, offset, newData, 0, length);
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
        System.arraycopy(data, offset, newData, 1, length);
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
        // Zero-copy view over the same backing array.
        return new ObjectSeqStorage(data, offset + start, end - start);
    }

    @Override
    public SeqStorage concat(SeqStorage other) {
        if (other.isEmpty()) {
            return this;
        }

        Object[] otherData;
        int otherLen;
        if (other instanceof ObjectSeqStorage objOther) {
            otherData = objOther.data;
            otherLen = objOther.length;
        } else if (other instanceof LongSeqStorage longOther) {
            otherData = boxLongArray(longOther.getArray());
            otherLen = otherData.length;
        } else if (other instanceof DoubleSeqStorage doubleOther) {
            otherData = boxDoubleArray(doubleOther.getArray());
            otherLen = otherData.length;
        } else if (other instanceof BooleanSeqStorage boolOther) {
            otherData = boxBooleanStorage(boolOther);
            otherLen = otherData.length;
        } else {
            throw new IllegalStateException("Unknown storage type: " + other.getClass());
        }

        // For ObjectSeqStorage other, we need the offset-adjusted start
        int otherOffset = (other instanceof ObjectSeqStorage obj) ? obj.offset : 0;

        // COW-owner fast path: copy right into spare capacity
        if (isOwner() && length + otherLen <= data.length) {
            System.arraycopy(otherData, otherOffset, data, length, otherLen);
            var result = new ObjectSeqStorage(data, length + otherLen, ownerToken);
            ownerToken[0] = result;
            return result;
        }

        // Allocate with spare capacity for future appends/concats (1.5x + 4)
        int total = length + otherLen;
        int newCapacity = Math.max(total, total + (total >> 1) + 4);
        Object[] newData = new Object[newCapacity];
        System.arraycopy(data, offset, newData, 0, length);
        System.arraycopy(otherData, otherOffset, newData, length, otherLen);
        Object[] token = new Object[1];
        var result = new ObjectSeqStorage(newData, total, token);
        token[0] = result;
        return result;
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
            if (Objects.equals(data[offset + i], value)) {
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
            if (Objects.equals(data[offset + i], value)) {
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
            newData[i] = data[offset + length - 1 - i];
        }
        return new ObjectSeqStorage(newData);
    }

    /**
     * Sort using natural ordering (elements must be Comparable).
     */
    @SuppressWarnings("unchecked")
    public ObjectSeqStorage sort() {
        Object[] newData = new Object[length];
        System.arraycopy(data, offset, newData, 0, length);
        Arrays.sort(newData, (a, b) -> ((Comparable<Object>) a).compareTo(b));
        return new ObjectSeqStorage(newData);
    }

    /**
     * Sort using custom comparator.
     */
    @TruffleBoundary
    public ObjectSeqStorage sortWith(Comparator<Object> comparator) {
        Object[] newData = new Object[length];
        System.arraycopy(data, offset, newData, 0, length);
        Arrays.sort(newData, comparator);
        return new ObjectSeqStorage(newData);
    }

    /**
     * Remove duplicates (keeping first occurrence).
     */
    public ObjectSeqStorage distinct() {
        java.util.LinkedHashSet<Object> seen = new java.util.LinkedHashSet<>();
        for (int i = 0; i < length; i++) {
            seen.add(data[offset + i]);
        }
        return new ObjectSeqStorage(seen.toArray());
    }

    // ===== Helper methods for boxing =====

    private static Object[] boxLongArray(long[] longs) {
        Object[] result = new Object[longs.length];
        for (int i = 0; i < longs.length; i++) {
            result[i] = longs[i];
        }
        return result;
    }

    private static Object[] boxDoubleArray(double[] doubles) {
        Object[] result = new Object[doubles.length];
        for (int i = 0; i < doubles.length; i++) {
            result[i] = doubles[i];
        }
        return result;
    }

    private static Object[] boxBooleanStorage(BooleanSeqStorage bools) {
        Object[] result = new Object[bools.size()];
        for (int i = 0; i < bools.size(); i++) {
            result[i] = bools.getBoolean(i);
        }
        return result;
    }

    @Override
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
        return new ObjectSeqStorage(Arrays.copyOfRange(data, offset, offset + length));
    }

    /**
     * In-place set - mutates the underlying array.
     * Only safe within LocalMut context.
     */
    public void mutSet(int index, Object value) {
        data[offset + index] = value;
    }

    /**
     * In-place swap - mutates the underlying array.
     * Only safe within LocalMut context.
     */
    public void mutSwap(int i, int j) {
        Object tmp = data[offset + i];
        data[offset + i] = data[offset + j];
        data[offset + j] = tmp;
    }

    /**
     * In-place reverse - mutates the underlying array.
     * Only safe within LocalMut context.
     */
    public void mutReverse() {
        int left = offset;
        int right = offset + length - 1;
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
        Arrays.fill(data, offset, offset + length, value);
    }

    /**
     * In-place fill range - mutates the underlying array.
     * Only safe within LocalMut context.
     */
    public void mutFillRange(int start, int end, Object value) {
        Arrays.fill(data, offset + start, offset + end, value);
    }

    /**
     * In-place sort using comparator - mutates the underlying array.
     * Only safe within LocalMut context.
     */
    @TruffleBoundary
    public void mutSortWith(Comparator<Object> comparator) {
        Arrays.sort(data, offset, offset + length, comparator);
    }
}
