package com.mezze.datastructures.seq;

import com.mezze.datastructures.util.VectorOps;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.ExplodeLoop;

import java.util.Arrays;

/**
 * Unboxed double[] storage for Float/Frac sequences.
 * Provides zero-boxing access and SIMD-optimized operations.
 *
 * Optimizations:
 * - SIMD via Vector API for sum/product/min/max/dotProduct
 * - @ExplodeLoop for small fixed-size loops
 * - @CompilationFinal for JIT constant folding
 */
public final class DoubleSeqStorage implements SeqStorage {

    @CompilationFinal(dimensions = 1)
    private final double[] data;

    @CompilationFinal
    private final int length;

    private final int offset;

    private final Object[] ownerToken;

    public DoubleSeqStorage(double[] data) {
        this.data = data;
        this.length = data.length;
        this.offset = 0;
        this.ownerToken = null;
    }

    private DoubleSeqStorage(double[] data, int length, Object[] ownerToken) {
        this.data = data;
        this.length = length;
        this.offset = 0;
        this.ownerToken = ownerToken;
    }

    /** Zero-copy slice view: shares the parent's backing array. */
    private DoubleSeqStorage(double[] data, int offset, int length) {
        this.data = data;
        this.offset = offset;
        this.length = length;
        this.ownerToken = null;
    }

    private boolean isOwner() {
        return offset == 0 && ownerToken != null && ownerToken[0] == this;
    }

    private double[] trimmedData() {
        if (offset == 0 && length == data.length) return data;
        return Arrays.copyOfRange(data, offset, offset + length);
    }

    @Override
    public int size() {
        return length;
    }

    @Override
    public Object get(int index) {
        return getDouble(index);
    }

    /**
     * Unboxed access - no boxing overhead.
     */
    public double getDouble(int index) {
        return data[offset + index];
    }

    @Override
    public SeqStorage update(int index, Object value) {
        if (value instanceof Double d) {
            return updateDouble(index, d);
        }
        return transitionToObject().update(index, value);
    }

    /**
     * Unboxed update.
     */
    public DoubleSeqStorage updateDouble(int index, double value) {
        double[] newData = new double[length];
        System.arraycopy(data, offset, newData, 0, length);
        newData[index] = value;
        return new DoubleSeqStorage(newData);
    }

    @Override
    public SeqStorage append(Object value) {
        if (value instanceof Double d) {
            return appendDouble(d);
        }
        return transitionToObject().append(value);
    }

    /**
     * Unboxed append.
     */
    public DoubleSeqStorage appendDouble(double value) {
        if (isOwner() && length < data.length) {
            data[length] = value;
            var result = new DoubleSeqStorage(data, length + 1, ownerToken);
            ownerToken[0] = result;
            return result;
        }
        int newCapacity = Math.max(length + 1, length + (length >> 1) + 4);
        double[] newData = new double[newCapacity];
        System.arraycopy(data, offset, newData, 0, length);
        newData[length] = value;
        Object[] token = new Object[1];
        var result = new DoubleSeqStorage(newData, length + 1, token);
        token[0] = result;
        return result;
    }

    @Override
    public SeqStorage prepend(Object value) {
        if (value instanceof Double d) {
            return prependDouble(d);
        }
        return transitionToObject().prepend(value);
    }

    /**
     * Unboxed prepend.
     */
    public DoubleSeqStorage prependDouble(double value) {
        double[] newData = new double[length + 1];
        newData[0] = value;
        System.arraycopy(data, offset, newData, 1, length);
        return new DoubleSeqStorage(newData);
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
        return new DoubleSeqStorage(data, offset + start, end - start);
    }

    @Override
    public SeqStorage concat(SeqStorage other) {
        if (other.isEmpty()) {
            return this;
        }
        if (other instanceof DoubleSeqStorage doubleOther) {
            return concatDouble(doubleOther);
        }
        return transitionToObject().concat(other);
    }

    /**
     * Concat two double storages — stays unboxed.
     * COW-owner fast path avoids fresh allocation when spare capacity suffices.
     */
    public DoubleSeqStorage concatDouble(DoubleSeqStorage other) {
        int otherLen = other.length;

        // COW-owner fast path: copy right into spare capacity
        if (isOwner() && length + otherLen <= data.length) {
            System.arraycopy(other.data, other.offset, data, length, otherLen);
            var result = new DoubleSeqStorage(data, length + otherLen, ownerToken);
            ownerToken[0] = result;
            return result;
        }

        // Allocate with spare capacity for future appends/concats (1.5x + 4)
        int total = length + otherLen;
        int newCapacity = Math.max(total, total + (total >> 1) + 4);
        double[] newData = new double[newCapacity];
        System.arraycopy(data, offset, newData, 0, length);
        System.arraycopy(other.data, other.offset, newData, length, otherLen);
        Object[] token = new Object[1];
        var result = new DoubleSeqStorage(newData, total, token);
        token[0] = result;
        return result;
    }

    @Override
    public byte getStrategyTag() {
        return TAG_DOUBLE;
    }

    /**
     * Returns the underlying array for SIMD operations.
     */
    public double[] getArray() {
        return trimmedData();
    }

    // ===== SIMD-optimized numeric operations =====

    /**
     * Sum all elements using SIMD.
     */
    public double sum() {
        return VectorOps.sumDouble(trimmedData());
    }

    /**
     * Sum for small arrays with loop unrolling.
     */
    @ExplodeLoop
    public double sumSmall(int knownSize) {
        double result = 0.0;
        for (int i = 0; i < knownSize; i++) {
            result += data[offset + i];
        }
        return result;
    }

    /**
     * Product of all elements using SIMD.
     */
    public double product() {
        return VectorOps.productDouble(trimmedData());
    }

    /**
     * Minimum element using SIMD.
     */
    public double min() {
        if (length == 0) {
            throw new IllegalStateException("Empty sequence has no minimum");
        }
        return VectorOps.minDouble(trimmedData());
    }

    /**
     * Maximum element using SIMD.
     */
    public double max() {
        if (length == 0) {
            throw new IllegalStateException("Empty sequence has no maximum");
        }
        return VectorOps.maxDouble(trimmedData());
    }

    /**
     * Average of all elements using SIMD sum.
     */
    public double average() {
        if (length == 0) {
            throw new IllegalStateException("Empty sequence has no average");
        }
        return VectorOps.sumDouble(trimmedData()) / length;
    }

    /**
     * Dot product with another array using SIMD.
     */
    public double dotProduct(DoubleSeqStorage other) {
        return VectorOps.dotProduct(trimmedData(), other.trimmedData());
    }

    /**
     * Element-wise addition with another array using SIMD.
     */
    public DoubleSeqStorage add(DoubleSeqStorage other) {
        return new DoubleSeqStorage(VectorOps.addDouble(trimmedData(), other.trimmedData()));
    }

    /**
     * Scale all elements by a factor using SIMD.
     */
    public DoubleSeqStorage scale(double factor) {
        return new DoubleSeqStorage(VectorOps.scaleDouble(trimmedData(), factor));
    }

    /**
     * Fused multiply-add: this * other + addend
     */
    public DoubleSeqStorage fma(DoubleSeqStorage other, DoubleSeqStorage addend) {
        return new DoubleSeqStorage(VectorOps.fmaDouble(trimmedData(), other.trimmedData(), addend.trimmedData()));
    }

    /**
     * Find index of element, or -1 if not found.
     * Uses exact equality for doubles.
     */
    public int indexOf(double value) {
        for (int i = 0; i < length; i++) {
            if (data[offset + i] == value) {
                return i;
            }
        }
        return -1;
    }

    /**
     * indexOf for small arrays with loop unrolling.
     */
    @ExplodeLoop
    public int indexOfSmall(double value, int knownSize) {
        for (int i = 0; i < knownSize; i++) {
            if (data[offset + i] == value) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Check if contains element.
     */
    public boolean contains(double value) {
        return indexOf(value) >= 0;
    }

    /**
     * Reverse the sequence.
     */
    @ExplodeLoop(kind = ExplodeLoop.LoopExplosionKind.FULL_UNROLL_UNTIL_RETURN)
    public DoubleSeqStorage reverse() {
        double[] newData = new double[length];
        for (int i = 0; i < length; i++) {
            newData[i] = data[offset + length - 1 - i];
        }
        return new DoubleSeqStorage(newData);
    }

    /**
     * Sort in ascending order.
     */
    public DoubleSeqStorage sort() {
        double[] newData = new double[length];
        System.arraycopy(data, offset, newData, 0, length);
        Arrays.sort(newData);
        return new DoubleSeqStorage(newData);
    }

    /**
     * Transition to ObjectStorage when type changes.
     */
    private ObjectSeqStorage transitionToObject() {
        Object[] objects = new Object[length];
        for (int i = 0; i < length; i++) {
            objects[i] = data[offset + i];
        }
        return new ObjectSeqStorage(objects);
    }

    @Override
    public String toString() {
        return "DoubleSeqStorage" + Arrays.toString(trimmedData());
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof DoubleSeqStorage other)) return false;
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
    public DoubleSeqStorage copyToMutable() {
        return new DoubleSeqStorage(Arrays.copyOfRange(data, offset, offset + length));
    }

    /**
     * In-place set - mutates the underlying array.
     * Only safe within LocalMut context.
     */
    public void mutSet(int index, double value) {
        data[offset + index] = value;
    }

    /**
     * In-place swap - mutates the underlying array.
     * Only safe within LocalMut context.
     */
    public void mutSwap(int i, int j) {
        double tmp = data[offset + i];
        data[offset + i] = data[offset + j];
        data[offset + j] = tmp;
    }

    /**
     * In-place increment - mutates the underlying array.
     * Only safe within LocalMut context.
     */
    public void mutIncr(int index, double delta) {
        data[offset + index] += delta;
    }

    /**
     * In-place sort - mutates the underlying array.
     * Only safe within LocalMut context.
     */
    public void mutSort() {
        Arrays.sort(data, offset, offset + length);
    }

    /**
     * In-place reverse - mutates the underlying array.
     * Only safe within LocalMut context.
     */
    public void mutReverse() {
        int left = offset;
        int right = offset + length - 1;
        while (left < right) {
            double tmp = data[left];
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
    public void mutFill(double value) {
        Arrays.fill(data, offset, offset + length, value);
    }

    /**
     * In-place fill range - mutates the underlying array.
     * Only safe within LocalMut context.
     */
    public void mutFillRange(int start, int end, double value) {
        Arrays.fill(data, offset + start, offset + end, value);
    }
}
