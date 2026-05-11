package com.mezze.datastructures.set;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;

/**
 * Open-addressing hash set specialized for primitive {@code double} elements.
 *
 * Sister of {@link com.mezze.datastructures.map.DoubleHashMapStorage}: stores
 * elements as their raw {@code long} bit pattern via
 * {@link Double#doubleToLongBits}, comparing with primitive {@code ==}.
 *
 * <p>{@code Double.doubleToLongBits} canonicalizes all NaN bit patterns to a
 * single value, so any two NaN elements compare equal. {@code +0.0} and
 * {@code -0.0} have distinct bit patterns and are treated as distinct
 * elements (matching {@code java.util.HashSet} semantics for Double).
 *
 * <p>Sentinels are non-canonical NaN bit patterns that
 * {@link Double#doubleToLongBits} never produces, so user values cannot
 * collide with them.
 */
public final class DoubleHashSetStorage implements SetStorage {

    private static final int MIN_CAPACITY = 16;
    private static final float LOAD_FACTOR = 0.75f;
    private static final long EMPTY_BITS = 0x7ff8000000000001L;
    private static final long DELETED_BITS = 0x7ff8000000000002L;

    @CompilationFinal(dimensions = 1)
    private final long[] table;

    @CompilationFinal
    private final int size;

    @CompilationFinal
    private final int capacity;

    private final Object[] ownerToken;

    @CompilationFinal
    private boolean ownershipRevoked;

    public DoubleHashSetStorage() {
        this.capacity = MIN_CAPACITY;
        this.table = newTable(MIN_CAPACITY);
        this.size = 0;
        this.ownerToken = new Object[]{this};
    }

    private DoubleHashSetStorage(long[] table, int size, int capacity, Object[] ownerToken) {
        this.table = table;
        this.size = size;
        this.capacity = capacity;
        this.ownerToken = ownerToken;
    }

    private static long[] newTable(int capacity) {
        long[] a = new long[capacity];
        java.util.Arrays.fill(a, EMPTY_BITS);
        return a;
    }

    private boolean isOwner() {
        return !ownershipRevoked && ownerToken != null && ownerToken[0] == this;
    }

    private void revokeOwnership() {
        if (ownershipRevoked) return;
        if (ownerToken != null && ownerToken[0] == this) {
            ownerToken[0] = null;
        }
        ownershipRevoked = true;
    }

    public static boolean isStorable(@SuppressWarnings("unused") double v) {
        return true;
    }

    private static int hash(long bits) {
        long x = bits;
        x ^= x >>> 33;
        x *= 0xff51afd7ed558ccdL;
        x ^= x >>> 33;
        x *= 0xc4ceb9fe1a85ec53L;
        x ^= x >>> 33;
        return (int) (x ^ (x >>> 32));
    }

    @Override
    public int size() {
        return size;
    }

    public boolean containsDouble(double elem) {
        revokeOwnership();
        long bits = Double.doubleToLongBits(elem);
        int idx = hash(bits) & (capacity - 1);
        for (int i = 0; i < capacity; i++) {
            long t = table[idx];
            if (t == EMPTY_BITS) return false;
            if (t == bits) return true;
            idx = (idx + 1) & (capacity - 1);
        }
        return false;
    }

    @Override
    public boolean contains(Object elem) {
        return elem instanceof Double d && containsDouble(d);
    }

    @Override
    public SetStorage add(Object elem) {
        if (elem instanceof Double d) return addDouble(d);
        return transitionToGeneric().add(elem);
    }

    public SetStorage addDouble(double elem) {
        long bits = Double.doubleToLongBits(elem);
        if (containsBitsInternal(bits)) return this;

        if (size >= capacity * LOAD_FACTOR) {
            return grow().addDouble(elem);
        }

        long[] workTable;
        Object[] token;
        if (isOwner()) {
            workTable = table;
            token = ownerToken;
        } else {
            workTable = table.clone();
            token = new Object[1];
        }

        int idx = hash(bits) & (capacity - 1);
        while (workTable[idx] != EMPTY_BITS && workTable[idx] != DELETED_BITS) {
            idx = (idx + 1) & (capacity - 1);
        }
        workTable[idx] = bits;
        var result = new DoubleHashSetStorage(workTable, size + 1, capacity, token);
        token[0] = result;
        return result;
    }

    private boolean containsBitsInternal(long bits) {
        int idx = hash(bits) & (capacity - 1);
        for (int i = 0; i < capacity; i++) {
            long t = table[idx];
            if (t == EMPTY_BITS) return false;
            if (t == bits) return true;
            idx = (idx + 1) & (capacity - 1);
        }
        return false;
    }

    @Override
    public SetStorage remove(Object elem) {
        if (elem instanceof Double d) return removeDouble(d);
        return this;
    }

    public SetStorage removeDouble(double elem) {
        revokeOwnership();
        long bits = Double.doubleToLongBits(elem);
        int idx = hash(bits) & (capacity - 1);
        for (int i = 0; i < capacity; i++) {
            long t = table[idx];
            if (t == EMPTY_BITS) return this;
            if (t == bits) {
                int newSize = size - 1;
                if (newSize == 0) return EmptySetStorage.INSTANCE;
                long[] newTable = table.clone();
                newTable[idx] = DELETED_BITS;
                if (newSize < capacity / 4 && capacity > MIN_CAPACITY) {
                    return shrink(newTable, newSize);
                }
                return new DoubleHashSetStorage(newTable, newSize, capacity, null);
            }
            idx = (idx + 1) & (capacity - 1);
        }
        return this;
    }

    @Override
    public Object[] toArray() {
        revokeOwnership();
        Object[] out = new Object[size];
        int j = 0;
        for (int i = 0; i < capacity; i++) {
            long t = table[i];
            if (t != EMPTY_BITS && t != DELETED_BITS) {
                out[j++] = Double.longBitsToDouble(t);
            }
        }
        return out;
    }

    @Override
    public byte getStrategyTag() {
        return TAG_HASH;
    }

    private DoubleHashSetStorage grow() {
        int newCapacity = capacity * 2;
        long[] newTable = newTable(newCapacity);
        int newSize = 0;
        for (int i = 0; i < capacity; i++) {
            long t = table[i];
            if (t == EMPTY_BITS || t == DELETED_BITS) continue;
            int idx = hash(t) & (newCapacity - 1);
            while (newTable[idx] != EMPTY_BITS) idx = (idx + 1) & (newCapacity - 1);
            newTable[idx] = t;
            newSize++;
        }
        Object[] token = new Object[]{null};
        var result = new DoubleHashSetStorage(newTable, newSize, newCapacity, token);
        token[0] = result;
        return result;
    }

    private DoubleHashSetStorage shrink(long[] oldTable, int newSize) {
        int newCapacity = Math.max(MIN_CAPACITY, capacity / 2);
        long[] newTable = newTable(newCapacity);
        for (int i = 0; i < oldTable.length; i++) {
            long t = oldTable[i];
            if (t == EMPTY_BITS || t == DELETED_BITS) continue;
            int idx = hash(t) & (newCapacity - 1);
            while (newTable[idx] != EMPTY_BITS) idx = (idx + 1) & (newCapacity - 1);
            newTable[idx] = t;
        }
        return new DoubleHashSetStorage(newTable, newSize, newCapacity, null);
    }

    private HashSetStorage transitionToGeneric() {
        HashSetStorage out = new HashSetStorage();
        for (int i = 0; i < capacity; i++) {
            long t = table[i];
            if (t == EMPTY_BITS || t == DELETED_BITS) continue;
            out = (HashSetStorage) out.add(Double.longBitsToDouble(t));
        }
        return out;
    }
}
