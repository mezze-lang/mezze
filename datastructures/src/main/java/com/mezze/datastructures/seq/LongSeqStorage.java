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
    private long[] data;  // NOT final: lazy-range materializes here on demand

    @CompilationFinal
    private final int length;  // logical size (may be < data.length when spare capacity exists)

    @CompilationFinal
    private final int offset;  // start index into data[] for zero-copy slice views

    /** COW ownership token for O(1) amortized append chains. */
    private final Object[] ownerToken;

    // Lazy-range fields (env-gated MEZZE_LAZY_RANGE=1). When lazy != null, data is null
    // and elements are computed as lazyStart + i*lazyStep. First array-access materializes.
    private final long lazyStart;
    private final long lazyStep;
    private final boolean isLazy;

    public LongSeqStorage(long[] data) {
        this.data = data;
        this.length = data.length;
        this.offset = 0;
        this.ownerToken = null;
        this.lazyStart = 0;
        this.lazyStep = 0;
        this.isLazy = false;
    }

    private LongSeqStorage(long[] data, int length, Object[] ownerToken) {
        this.data = data;
        this.length = length;
        this.offset = 0;
        this.ownerToken = ownerToken;
        this.lazyStart = 0;
        this.lazyStep = 0;
        this.isLazy = false;
    }

    /**
     * Lazy-range constructor. Elements computed as start + i*step on demand.
     * size = length. Materializes to data[] only on first array-touching op.
     */
    public static LongSeqStorage lazyRange(long start, long end, long step) {
        long span;
        if (step > 0) span = (end - start + step - 1) / step;
        else span = (start - end + (-step) - 1) / (-step);
        int len = (int) span;
        return new LongSeqStorage(start, step, len);
    }

    private LongSeqStorage(long start, long step, int length) {
        this.data = null;
        this.length = length;
        this.offset = 0;
        this.ownerToken = null;
        this.lazyStart = start;
        this.lazyStep = step;
        this.isLazy = true;
    }

    /** Zero-copy slice view: shares the parent's backing array. */
    private LongSeqStorage(long[] data, int offset, int length) {
        this.data = data;
        this.offset = offset;
        this.length = length;
        this.ownerToken = null;
        this.lazyStart = 0;
        this.lazyStep = 0;
        this.isLazy = false;
    }

    /** Materializes lazy range into data[] on demand. Idempotent. */
    @TruffleBoundary
    private void materialize() {
        if (data != null) return;
        long[] arr = new long[length];
        long v = lazyStart;
        for (int i = 0; i < length; i++) {
            arr[i] = v;
            v += lazyStep;
        }
        this.data = arr;
    }

    public boolean isLazyRange() { return isLazy && data == null; }

    private boolean isOwner() {
        return offset == 0 && ownerToken != null && data != null && ownerToken[0] == this;
    }

    /** Returns a trimmed array (length == data.length) for external use. */
    private long[] trimmedData() {
        if (data == null) materialize();
        if (offset == 0 && length == data.length) return data;
        return Arrays.copyOfRange(data, offset, offset + length);
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
        if (data == null) {
            // Lazy path: compute without materializing.
            return lazyStart + (long) index * lazyStep;
        }
        return data[offset + index];
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
        if (data == null) materialize();
        long[] newData = new long[length];
        System.arraycopy(data, offset, newData, 0, length);
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
     * Unboxed append with COW + spare capacity.
     * O(1) amortized in chains like s = s.append(1).append(2).append(3).
     */
    public LongSeqStorage appendLong(long value) {
        if (data == null) materialize();
        if (isOwner() && length < data.length) {
            // O(1): write into spare capacity, transfer ownership
            data[length] = value;
            var result = new LongSeqStorage(data, length + 1, ownerToken);
            ownerToken[0] = result;
            return result;
        }
        // Allocate with spare capacity for future appends (1.5x + 4)
        int newCapacity = Math.max(length + 1, length + (length >> 1) + 4);
        long[] newData = new long[newCapacity];
        System.arraycopy(data, offset, newData, 0, length);
        newData[length] = value;
        Object[] token = new Object[1];
        var result = new LongSeqStorage(newData, length + 1, token);
        token[0] = result;
        return result;
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
        if (data == null) materialize();
        long[] newData = new long[length + 1];
        newData[0] = value;
        System.arraycopy(data, offset, newData, 1, length);
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
        if (data == null) {
            // Slice of a lazy range is another lazy range.
            long newStart = lazyStart + (long) start * lazyStep;
            return new LongSeqStorage(newStart, lazyStep, end - start);
        }
        // Zero-copy view over the same backing array.
        return new LongSeqStorage(data, offset + start, end - start);
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
     * Concat two long storages — stays unboxed.
     * COW-owner fast path: if this side owns the backing array with enough
     * spare capacity, copy the right side into spare and transfer ownership.
     * Avoids the O(N+M) fresh-array allocation in chains of concats.
     */
    public LongSeqStorage concatLong(LongSeqStorage other) {
        if (data == null) materialize();
        if (other.data == null) other.materialize();
        int otherLen = other.length;

        // COW-owner fast path: copy right into spare capacity
        if (isOwner() && length + otherLen <= data.length) {
            System.arraycopy(other.data, other.offset, data, length, otherLen);
            var result = new LongSeqStorage(data, length + otherLen, ownerToken);
            ownerToken[0] = result;
            return result;
        }

        // Allocate with spare capacity for future appends/concats (1.5x + 4)
        int total = length + otherLen;
        int newCapacity = Math.max(total, total + (total >> 1) + 4);
        long[] newData = new long[newCapacity];
        System.arraycopy(data, offset, newData, 0, length);
        System.arraycopy(other.data, other.offset, newData, length, otherLen);
        Object[] token = new Object[1];
        var result = new LongSeqStorage(newData, total, token);
        token[0] = result;
        return result;
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
        return trimmedData();
    }

    // ===== SIMD-optimized numeric operations =====

    /**
     * Sum all elements using SIMD.
     */
    public long sum() {
        return VectorOps.sumLong(trimmedData());
    }

    /**
     * Sum for small arrays with loop unrolling.
     */
    @ExplodeLoop
    public long sumSmall(int knownSize) {
        if (data == null) materialize();
        long result = 0;
        for (int i = 0; i < knownSize; i++) {
            result += data[offset + i];
        }
        return result;
    }

    /**
     * Product of all elements using SIMD.
     */
    public long product() {
        return VectorOps.productLong(trimmedData());
    }

    /**
     * Minimum element using SIMD.
     */
    public long min() {
        if (length == 0) {
            throw new IllegalStateException("Empty sequence has no minimum");
        }
        return VectorOps.minLong(trimmedData());
    }

    /**
     * Maximum element using SIMD.
     */
    public long max() {
        if (length == 0) {
            throw new IllegalStateException("Empty sequence has no maximum");
        }
        return VectorOps.maxLong(trimmedData());
    }

    /**
     * Find index of element using SIMD, or -1 if not found.
     */
    public int indexOf(long value) {
        return VectorOps.indexOfLong(trimmedData(), value);
    }

    /**
     * indexOf for small arrays with loop unrolling.
     */
    @ExplodeLoop
    public int indexOfSmall(long value, int knownSize) {
        if (data == null) materialize();
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
    public boolean contains(long value) {
        return indexOf(value) >= 0;
    }

    /**
     * Element-wise addition with another array using SIMD.
     */
    public LongSeqStorage add(LongSeqStorage other) {
        return new LongSeqStorage(VectorOps.addLong(trimmedData(), other.trimmedData()));
    }

    /**
     * Scale all elements by a factor using SIMD.
     */
    public LongSeqStorage scale(long factor) {
        return new LongSeqStorage(VectorOps.scaleLong(trimmedData(), factor));
    }

    /**
     * Reverse the sequence.
     */
    @ExplodeLoop(kind = ExplodeLoop.LoopExplosionKind.FULL_UNROLL_UNTIL_RETURN)
    public LongSeqStorage reverse() {
        if (data == null) materialize();
        long[] newData = new long[length];
        for (int i = 0; i < length; i++) {
            newData[i] = data[offset + length - 1 - i];
        }
        return new LongSeqStorage(newData);
    }

    /**
     * Sort in ascending order.
     */
    public LongSeqStorage sort() {
        if (data == null) materialize();
        long[] newData = new long[length];
        System.arraycopy(data, offset, newData, 0, length);
        Arrays.sort(newData);
        return new LongSeqStorage(newData);
    }

    /**
     * Transition to ObjectStorage when type changes.
     */
    private ObjectSeqStorage transitionToObject() {
        Object[] objects = new Object[length];
        if (data == null) {
            long v = lazyStart;
            for (int i = 0; i < length; i++) {
                objects[i] = v;
                v += lazyStep;
            }
        } else {
            for (int i = 0; i < length; i++) {
                objects[i] = data[offset + i];
            }
        }
        return new ObjectSeqStorage(objects);
    }

    @Override
    public String toString() {
        return "LongSeqStorage" + Arrays.toString(trimmedData());
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof LongSeqStorage other)) return false;
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
    public LongSeqStorage copyToMutable() {
        if (data == null) materialize();
        return new LongSeqStorage(Arrays.copyOfRange(data, offset, offset + length));
    }

    /**
     * In-place set - mutates the underlying array.
     * Only safe within LocalMut context.
     */
    public void mutSet(int index, long value) {
        if (data == null) materialize();
        data[offset + index] = value;
    }

    /**
     * In-place swap - mutates the underlying array.
     * Only safe within LocalMut context.
     */
    public void mutSwap(int i, int j) {
        if (data == null) materialize();
        long tmp = data[offset + i];
        data[offset + i] = data[offset + j];
        data[offset + j] = tmp;
    }

    /**
     * In-place increment - mutates the underlying array.
     * Only safe within LocalMut context.
     */
    public void mutIncr(int index, long delta) {
        if (data == null) materialize();
        data[offset + index] += delta;
    }

    /**
     * In-place sort - mutates the underlying array.
     * Only safe within LocalMut context.
     */
    public void mutSort() {
        if (data == null) materialize();
        Arrays.sort(data, offset, offset + length);
    }

    /**
     * In-place reverse - mutates the underlying array.
     * Only safe within LocalMut context.
     */
    public void mutReverse() {
        if (data == null) materialize();
        int left = offset;
        int right = offset + length - 1;
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
        if (data == null) materialize();
        Arrays.fill(data, offset, offset + length, value);
    }

    /**
     * In-place fill range - mutates the underlying array.
     * Only safe within LocalMut context.
     */
    public void mutFillRange(int start, int end, long value) {
        if (data == null) materialize();
        Arrays.fill(data, offset + start, offset + end, value);
    }
}
