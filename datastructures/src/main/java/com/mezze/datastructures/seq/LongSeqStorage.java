package com.mezze.datastructures.seq;

import com.mezze.datastructures.util.VectorOps;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.ExplodeLoop;

import java.util.Arrays;

/**
 * Unboxed long[] storage for Int/Nat sequences.
 * Provides zero-boxing access and SIMD-optimized operations.
 *
 * Optimizations:
 * - SIMD via Vector API for sum/product/min/max/indexOf
 * - @ExplodeLoop for small fixed-size loops
 * - @CompilationFinal for JIT constant folding
 */
public final class LongSeqStorage implements SeqStorage {

    @CompilationFinal(dimensions = 1)
    private final long[] data;

    @CompilationFinal
    private final int length;

    public LongSeqStorage(long[] data) {
        this.data = data;
        this.length = data.length;
    }

    @Override
    public int size() {
        return length;
    }

    @Override
    public Object get(int index) {
        return getLong(index);
    }

    /**
     * Unboxed access - no boxing overhead.
     */
    public long getLong(int index) {
        return data[index];
    }

    @Override
    public SeqStorage update(int index, Object value) {
        if (value instanceof Long l) {
            return updateLong(index, l);
        }
        // Type changed - transition to ObjectStorage
        return transitionToObject().update(index, value);
    }

    /**
     * Unboxed update - returns new storage with updated value.
     */
    public LongSeqStorage updateLong(int index, long value) {
        long[] newData = Arrays.copyOf(data, length);
        newData[index] = value;
        return new LongSeqStorage(newData);
    }

    @Override
    public SeqStorage append(Object value) {
        if (value instanceof Long l) {
            return appendLong(l);
        }
        return transitionToObject().append(value);
    }

    /**
     * Unboxed append.
     */
    public LongSeqStorage appendLong(long value) {
        long[] newData = Arrays.copyOf(data, length + 1);
        newData[length] = value;
        return new LongSeqStorage(newData);
    }

    @Override
    public SeqStorage prepend(Object value) {
        if (value instanceof Long l) {
            return prependLong(l);
        }
        return transitionToObject().prepend(value);
    }

    /**
     * Unboxed prepend.
     */
    public LongSeqStorage prependLong(long value) {
        long[] newData = new long[length + 1];
        newData[0] = value;
        System.arraycopy(data, 0, newData, 1, length);
        return new LongSeqStorage(newData);
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
        return new LongSeqStorage(Arrays.copyOfRange(data, start, end));
    }

    @Override
    public SeqStorage concat(SeqStorage other) {
        if (other.isEmpty()) {
            return this;
        }
        if (other instanceof LongSeqStorage longOther) {
            return concatLong(longOther);
        }
        return transitionToObject().concat(other);
    }

    /**
     * Concat two long storages - stays unboxed.
     */
    public LongSeqStorage concatLong(LongSeqStorage other) {
        long[] newData = new long[length + other.length];
        System.arraycopy(data, 0, newData, 0, length);
        System.arraycopy(other.data, 0, newData, length, other.length);
        return new LongSeqStorage(newData);
    }

    @Override
    public byte getStrategyTag() {
        return TAG_LONG;
    }

    /**
     * Returns the underlying array for SIMD operations.
     * The returned array should not be modified.
     */
    public long[] getArray() {
        return data;
    }

    // ===== SIMD-optimized numeric operations =====

    /**
     * Sum all elements using SIMD.
     */
    @TruffleBoundary
    public long sum() {
        return VectorOps.sumLong(data);
    }

    /**
     * Sum for small arrays with loop unrolling.
     */
    @ExplodeLoop
    public long sumSmall(int knownSize) {
        long result = 0;
        for (int i = 0; i < knownSize; i++) {
            result += data[i];
        }
        return result;
    }

    /**
     * Product of all elements using SIMD.
     */
    @TruffleBoundary
    public long product() {
        return VectorOps.productLong(data);
    }

    /**
     * Minimum element using SIMD.
     */
    @TruffleBoundary
    public long min() {
        if (length == 0) {
            throw new IllegalStateException("Empty sequence has no minimum");
        }
        return VectorOps.minLong(data);
    }

    /**
     * Maximum element using SIMD.
     */
    @TruffleBoundary
    public long max() {
        if (length == 0) {
            throw new IllegalStateException("Empty sequence has no maximum");
        }
        return VectorOps.maxLong(data);
    }

    /**
     * Find index of element using SIMD, or -1 if not found.
     */
    @TruffleBoundary
    public int indexOf(long value) {
        return VectorOps.indexOfLong(data, value);
    }

    /**
     * indexOf for small arrays with loop unrolling.
     */
    @ExplodeLoop
    public int indexOfSmall(long value, int knownSize) {
        for (int i = 0; i < knownSize; i++) {
            if (data[i] == value) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Check if contains element.
     */
    public boolean contains(long value) {
        return indexOf(value) >= 0;
    }

    /**
     * Element-wise addition with another array using SIMD.
     */
    @TruffleBoundary
    public LongSeqStorage add(LongSeqStorage other) {
        return new LongSeqStorage(VectorOps.addLong(data, other.data));
    }

    /**
     * Scale all elements by a factor using SIMD.
     */
    @TruffleBoundary
    public LongSeqStorage scale(long factor) {
        return new LongSeqStorage(VectorOps.scaleLong(data, factor));
    }

    /**
     * Reverse the sequence.
     */
    @ExplodeLoop(kind = ExplodeLoop.LoopExplosionKind.FULL_UNROLL_UNTIL_RETURN)
    public LongSeqStorage reverse() {
        long[] newData = new long[length];
        for (int i = 0; i < length; i++) {
            newData[i] = data[length - 1 - i];
        }
        return new LongSeqStorage(newData);
    }

    /**
     * Sort in ascending order.
     */
    @TruffleBoundary
    public LongSeqStorage sort() {
        long[] newData = Arrays.copyOf(data, length);
        Arrays.sort(newData);
        return new LongSeqStorage(newData);
    }

    /**
     * Transition to ObjectStorage when type changes.
     */
    @TruffleBoundary
    private ObjectSeqStorage transitionToObject() {
        Object[] objects = new Object[length];
        for (int i = 0; i < length; i++) {
            objects[i] = data[i];
        }
        return new ObjectSeqStorage(objects);
    }

    @Override
    @TruffleBoundary
    public String toString() {
        return "LongSeqStorage" + Arrays.toString(data);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof LongSeqStorage other)) return false;
        return Arrays.equals(data, other.data);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(data);
    }

    // ===== MUTABLE OPERATIONS (for LocalMut effect) =====

    /**
     * Creates a mutable copy of this storage.
     * Used by to_mut to get a fresh copy that can be mutated.
     */
    public LongSeqStorage copyToMutable() {
        return new LongSeqStorage(Arrays.copyOf(data, length));
    }

    /**
     * In-place set - mutates the underlying array.
     * Only safe within LocalMut context.
     */
    public void mutSet(int index, long value) {
        data[index] = value;
    }

    /**
     * In-place swap - mutates the underlying array.
     * Only safe within LocalMut context.
     */
    public void mutSwap(int i, int j) {
        long tmp = data[i];
        data[i] = data[j];
        data[j] = tmp;
    }

    /**
     * In-place increment - mutates the underlying array.
     * Only safe within LocalMut context.
     */
    public void mutIncr(int index, long delta) {
        data[index] += delta;
    }

    /**
     * In-place sort - mutates the underlying array.
     * Only safe within LocalMut context.
     */
    @TruffleBoundary
    public void mutSort() {
        Arrays.sort(data);
    }

    /**
     * In-place reverse - mutates the underlying array.
     * Only safe within LocalMut context.
     */
    public void mutReverse() {
        int left = 0;
        int right = length - 1;
        while (left < right) {
            long tmp = data[left];
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
    public void mutFill(long value) {
        Arrays.fill(data, value);
    }

    /**
     * In-place fill range - mutates the underlying array.
     * Only safe within LocalMut context.
     */
    public void mutFillRange(int start, int end, long value) {
        Arrays.fill(data, start, end, value);
    }
}
