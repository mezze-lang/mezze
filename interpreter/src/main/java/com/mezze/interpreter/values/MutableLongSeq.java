package com.mezze.interpreter.values;

import com.mezze.datastructures.seq.MezzeSeq;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.IndirectCallNode;

import java.util.Arrays;

/**
 * Long-specialized mutable sequence for Int elements.
 * Uses {@code long[]} storage — zero boxing on swap/set, cache-friendly reads.
 *
 * <p>Created automatically by {@link MutableSeq#fromImmutable} when the source
 * MezzeSeq has long storage.  All BDSL typed ops (MutSwap, MutGetRaw, MutSet,
 * MutSize) have specializations for this class, keeping the partition inner
 * loop fully unboxed.
 */
public final class MutableLongSeq extends MutableSeq {
    private long[] data;
    private int size;

    private MutableLongSeq(long[] data, int size) {
        super(); // parent storage unused
        this.data = data;
        this.size = size;
    }

    @TruffleBoundary
    static MutableLongSeq fromLongSeq(MezzeSeq<?> seq) {
        long[] data = seq.toLongArray(); // already clones
        return new MutableLongSeq(data, data.length);
    }

    // ── Size ──────────────────────────────────────────────────────────

    @Override
    public int size() {
        return size;
    }

    // ── Element access ───────────────────────────────────────────────

    /** Unboxed read — no allocation. */
    public long getLong(int index) {
        return data[index];
    }

    @Override
    public Object get(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
        return data[index]; // autobox
    }

    /**
     * Safe get for Option return — returns boxed Long or null.
     * The fused MutGetRaw + IsNotNull pattern relies on null ↔ out-of-bounds.
     */
    @Override
    public Object getSafe(int index) {
        if (index < 0 || index >= size) {
            return null;
        }
        return data[index]; // autobox long → Long
    }

    // ── Mutation ─────────────────────────────────────────────────────

    /** Unboxed set — no allocation. */
    public void setLong(int index, long value) {
        data[index] = value;
    }

    @Override
    public void set(int index, Object value) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
        data[index] = (long) value;
    }

    @Override
    public boolean setSafe(int index, Object value) {
        if (index < 0 || index >= size) {
            return false;
        }
        data[index] = (long) value;
        return true;
    }

    /** Long-typed setSafe — stays fully unboxed. */
    public boolean setSafeLong(int index, long value) {
        if (index < 0 || index >= size) {
            return false;
        }
        data[index] = value;
        return true;
    }

    /** Pure long swap — three primitive moves, no Object manipulation. */
    @Override
    public void swap(int i, int j) {
        if (i < 0 || i >= size || j < 0 || j >= size) {
            throw new IndexOutOfBoundsException("Swap indices out of bounds");
        }
        long tmp = data[i];
        data[i] = data[j];
        data[j] = tmp;
    }

    // ── Bulk ops ─────────────────────────────────────────────────────

    /** Primitive dual-pivot quicksort — no comparator overhead. */
    @Override
    @TruffleBoundary
    public void sort() {
        Arrays.sort(data, 0, size);
    }

    @Override
    @TruffleBoundary
    public void sortBy(MezzeFunction cmp, IndirectCallNode callNode) {
        // Must box longs for the comparator call — this is behind @TruffleBoundary
        Object[] closureValues = cmp.getClosureValues();
        int closureLen = closureValues.length;
        Object[] callArgs = new Object[closureLen + 1];
        System.arraycopy(closureValues, 0, callArgs, 0, closureLen);

        // Convert to Long[] for sort, then copy back
        Long[] boxed = new Long[size];
        for (int i = 0; i < size; i++) boxed[i] = data[i];

        Arrays.sort(boxed, 0, size, (a, b) -> {
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

        for (int i = 0; i < size; i++) data[i] = boxed[i];
    }

    @Override
    @TruffleBoundary
    public void reverse() {
        int left = 0;
        int right = size - 1;
        while (left < right) {
            long tmp = data[left];
            data[left] = data[right];
            data[right] = tmp;
            left++;
            right--;
        }
    }

    @Override
    @TruffleBoundary
    public void fill(Object value) {
        Arrays.fill(data, 0, size, (long) value);
    }

    @Override
    @TruffleBoundary
    public void fillRange(int start, int end, Object value) {
        if (start < 0) start = 0;
        if (end > size) end = size;
        Arrays.fill(data, start, end, (long) value);
    }

    // ── Push / Pop ───────────────────────────────────────────────────

    @Override
    @TruffleBoundary
    public void push(Object value) {
        ensureLongCapacity(size + 1);
        data[size++] = (long) value;
    }

    @Override
    public Object pop() {
        if (size == 0) {
            return null;
        }
        return data[--size]; // autobox
    }

    // ── Insert / Remove ──────────────────────────────────────────────

    @Override
    @TruffleBoundary
    public boolean insert(int index, Object value) {
        if (index < 0 || index > size) {
            return false;
        }
        ensureLongCapacity(size + 1);
        System.arraycopy(data, index, data, index + 1, size - index);
        data[index] = (long) value;
        size++;
        return true;
    }

    @Override
    @TruffleBoundary
    public Object remove(int index) {
        if (index < 0 || index >= size) {
            return null;
        }
        long value = data[index];
        System.arraycopy(data, index + 1, data, index, size - index - 1);
        size--;
        return value; // autobox
    }

    // ── Clear ────────────────────────────────────────────────────────

    @Override
    public void clear() {
        size = 0;
    }

    // ── Conversion ───────────────────────────────────────────────────

    @Override
    @TruffleBoundary
    public MezzeSeq toImmutable() {
        return MezzeSeq.fromLongs(Arrays.copyOf(data, size));
    }

    // ── Internal ─────────────────────────────────────────────────────

    private void ensureLongCapacity(int minCapacity) {
        if (minCapacity > data.length) {
            int newCapacity = Math.max(data.length * 2, minCapacity);
            data = Arrays.copyOf(data, newCapacity);
        }
    }
}
