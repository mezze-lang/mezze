package com.mezze.datastructures.set;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;

/**
 * Open-addressing hash set specialized for primitive {@code long} elements.
 *
 * Sister of {@link com.mezze.datastructures.map.LongHashMapStorage} — same
 * design, just one array instead of two. Holds elements in a {@code long[]}
 * with primitive {@code ==} compares, eliminating the {@code Long} boxing +
 * {@code Objects.equals} virtual dispatch that costs ~8x relative to
 * {@code java.util.HashSet.contains} on Long-element sets.
 *
 * <p>Sentinels (same as {@link com.mezze.datastructures.map.LongHashMapStorage}):
 * <ul>
 *   <li>{@link #EMPTY_SLOT}: {@link Long#MIN_VALUE} — slot is empty.
 *   <li>{@link #DELETED_SLOT}: {@link Long#MIN_VALUE}+1 — slot was deleted.
 * </ul>
 */
public final class LongHashSetStorage implements SetStorage {

    private static final int MIN_CAPACITY = 16;
    private static final float LOAD_FACTOR = 0.75f;
    public static final long EMPTY_SLOT = Long.MIN_VALUE;
    public static final long DELETED_SLOT = Long.MIN_VALUE + 1L;

    @CompilationFinal(dimensions = 1)
    private final long[] table;

    @CompilationFinal
    private final int size;

    @CompilationFinal
    private final int capacity;

    private final Object[] ownerToken;

    @CompilationFinal
    private boolean ownershipRevoked;

    public LongHashSetStorage() {
        this.capacity = MIN_CAPACITY;
        this.table = newTable(MIN_CAPACITY);
        this.size = 0;
        this.ownerToken = new Object[]{this};
    }

    private LongHashSetStorage(long[] table, int size, int capacity, Object[] ownerToken) {
        this.table = table;
        this.size = size;
        this.capacity = capacity;
        this.ownerToken = ownerToken;
    }

    private static long[] newTable(int capacity) {
        long[] a = new long[capacity];
        java.util.Arrays.fill(a, EMPTY_SLOT);
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

    /** Whether {@code v} can be stored without colliding with the sentinels. */
    public static boolean isStorable(long v) {
        return v != EMPTY_SLOT && v != DELETED_SLOT;
    }

    /** Murmur3 finalizer for long. */
    private static int hash(long key) {
        long x = key;
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

    public boolean containsLong(long elem) {
        if (!isStorable(elem)) return false;
        revokeOwnership();
        int idx = hash(elem) & (capacity - 1);
        for (int i = 0; i < capacity; i++) {
            long t = table[idx];
            if (t == EMPTY_SLOT) return false;
            if (t == elem) return true;
            // DELETED → keep probing
            idx = (idx + 1) & (capacity - 1);
        }
        return false;
    }

    @Override
    public boolean contains(Object elem) {
        return elem instanceof Long l && containsLong(l);
    }

    @Override
    public SetStorage add(Object elem) {
        if (elem instanceof Long l && isStorable(l)) {
            return addLong(l);
        }
        return transitionToGeneric().add(elem);
    }

    public SetStorage addLong(long elem) {
        if (!isStorable(elem)) {
            return transitionToGeneric().add(Long.valueOf(elem));
        }
        if (containsLongInternal(elem)) return this;

        if (size >= capacity * LOAD_FACTOR) {
            return grow().addLong(elem);
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

        int idx = hash(elem) & (capacity - 1);
        while (workTable[idx] != EMPTY_SLOT && workTable[idx] != DELETED_SLOT) {
            idx = (idx + 1) & (capacity - 1);
        }
        workTable[idx] = elem;
        var result = new LongHashSetStorage(workTable, size + 1, capacity, token);
        token[0] = result;
        return result;
    }

    /** Internal contains that doesn't revoke ownership (used by addLong). */
    private boolean containsLongInternal(long elem) {
        int idx = hash(elem) & (capacity - 1);
        for (int i = 0; i < capacity; i++) {
            long t = table[idx];
            if (t == EMPTY_SLOT) return false;
            if (t == elem) return true;
            idx = (idx + 1) & (capacity - 1);
        }
        return false;
    }

    @Override
    public SetStorage remove(Object elem) {
        if (elem instanceof Long l) return removeLong(l);
        return this;
    }

    public SetStorage removeLong(long elem) {
        if (!isStorable(elem)) return this;
        revokeOwnership();
        int idx = hash(elem) & (capacity - 1);
        for (int i = 0; i < capacity; i++) {
            long t = table[idx];
            if (t == EMPTY_SLOT) return this;
            if (t == elem) {
                int newSize = size - 1;
                if (newSize == 0) return EmptySetStorage.INSTANCE;
                long[] newTable = table.clone();
                newTable[idx] = DELETED_SLOT;
                if (newSize < capacity / 4 && capacity > MIN_CAPACITY) {
                    return shrink(newTable, newSize);
                }
                return new LongHashSetStorage(newTable, newSize, capacity, null);
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
            if (t != EMPTY_SLOT && t != DELETED_SLOT) {
                out[j++] = Long.valueOf(t);
            }
        }
        return out;
    }

    @Override
    public byte getStrategyTag() {
        return TAG_HASH;  // shared with HashSetStorage; both are open-addressed.
    }

    private LongHashSetStorage grow() {
        int newCapacity = capacity * 2;
        long[] newTable = newTable(newCapacity);
        int newSize = 0;
        for (int i = 0; i < capacity; i++) {
            long t = table[i];
            if (t == EMPTY_SLOT || t == DELETED_SLOT) continue;
            int idx = hash(t) & (newCapacity - 1);
            while (newTable[idx] != EMPTY_SLOT) idx = (idx + 1) & (newCapacity - 1);
            newTable[idx] = t;
            newSize++;
        }
        Object[] token = new Object[]{null};
        var result = new LongHashSetStorage(newTable, newSize, newCapacity, token);
        token[0] = result;
        return result;
    }

    private LongHashSetStorage shrink(long[] oldTable, int newSize) {
        int newCapacity = Math.max(MIN_CAPACITY, capacity / 2);
        long[] newTable = newTable(newCapacity);
        for (int i = 0; i < oldTable.length; i++) {
            long t = oldTable[i];
            if (t == EMPTY_SLOT || t == DELETED_SLOT) continue;
            int idx = hash(t) & (newCapacity - 1);
            while (newTable[idx] != EMPTY_SLOT) idx = (idx + 1) & (newCapacity - 1);
            newTable[idx] = t;
        }
        return new LongHashSetStorage(newTable, newSize, newCapacity, null);
    }

    /** Promote to {@link HashSetStorage} for non-Long elements or sentinel collisions. */
    private HashSetStorage transitionToGeneric() {
        HashSetStorage out = new HashSetStorage();
        for (int i = 0; i < capacity; i++) {
            long t = table[i];
            if (t == EMPTY_SLOT || t == DELETED_SLOT) continue;
            out = (HashSetStorage) out.add(Long.valueOf(t));
        }
        return out;
    }
}
