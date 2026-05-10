package com.mezze.datastructures.map;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;

/**
 * Open-addressing hash map specialized for primitive {@code long} keys.
 *
 * Mirrors {@link HashMapStorage}'s structure but holds keys in a {@code long[]}
 * instead of {@code Object[]}, and uses primitive {@code ==} comparisons —
 * eliminating the {@code Long} boxing + {@code Objects.equals} virtual dispatch
 * that costs ~7x relative to {@code java.util.HashMap.get} on Long-keyed maps.
 *
 * <p>Bench (1M lookups in a 10K-entry Map<Int,Int>):
 * <pre>
 *   java.util.HashMap.get      3.3 ms
 *   HashMapStorage (Object[])  23.8 ms
 *   LongHashMapStorage         (target ~6-12 ms — closes about half the gap)
 * </pre>
 *
 * <p>Sentinels:
 * <ul>
 *   <li>{@link #EMPTY_KEY}: {@link Long#MIN_VALUE} — slot is empty.
 *   <li>{@link #DELETED_KEY}: {@link Long#MIN_VALUE}+1 — slot was deleted (tombstone).
 * </ul>
 *
 * <p>Mezze's {@code Int} type maps to Java {@code long}, so the sentinels
 * collide with two extreme values out of 2^64. We accept this trade-off:
 * the build-pass that promotes from {@link SmallFlatMapStorage} into a
 * primitive-keyed storage detects the collision and stays on
 * {@link HashMapStorage} when one of the sentinel values appears as a key.
 */
public final class LongHashMapStorage implements MapStorage {

    private static final int MIN_CAPACITY = 16;
    private static final float LOAD_FACTOR = 0.75f;
    /** Sentinel for an empty slot — {@link Long#MIN_VALUE}. */
    public static final long EMPTY_KEY = Long.MIN_VALUE;
    /** Sentinel for a deleted slot — {@link Long#MIN_VALUE}+1. */
    public static final long DELETED_KEY = Long.MIN_VALUE + 1L;

    @CompilationFinal(dimensions = 1)
    private final long[] keys;

    @CompilationFinal(dimensions = 1)
    private final Object[] values;

    @CompilationFinal
    private final int size;

    @CompilationFinal
    private final int capacity;

    /** COW ownership token — same pattern as HashMapStorage. */
    private final Object[] ownerToken;

    @CompilationFinal
    private boolean ownershipRevoked;

    public LongHashMapStorage() {
        this.capacity = MIN_CAPACITY;
        this.keys = newKeysArray(MIN_CAPACITY);
        this.values = new Object[MIN_CAPACITY];
        this.size = 0;
        this.ownerToken = new Object[]{this};
    }

    private LongHashMapStorage(long[] keys, Object[] values, int size, int capacity, Object[] ownerToken) {
        this.keys = keys;
        this.values = values;
        this.size = size;
        this.capacity = capacity;
        this.ownerToken = ownerToken;
    }

    /** Allocates a fresh keys array initialized to {@link #EMPTY_KEY}. */
    private static long[] newKeysArray(int capacity) {
        long[] a = new long[capacity];
        java.util.Arrays.fill(a, EMPTY_KEY);
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

    /** Whether {@code k} can be stored as a primitive long key here.
     *  Excludes the two sentinel values that the storage uses internally. */
    public static boolean isStorable(long k) {
        return k != EMPTY_KEY && k != DELETED_KEY;
    }

    /** Murmur3 finalizer adapted for long. Strong avalanche; primitive math. */
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

    /** Primitive-keyed lookup — no boxing, no virtual dispatch. */
    public Object getLong(long key) {
        if (!isStorable(key)) return null;
        revokeOwnership();
        int idx = hash(key) & (capacity - 1);
        for (int i = 0; i < capacity; i++) {
            long k = keys[idx];
            if (k == EMPTY_KEY) return null;
            if (k == key) return values[idx];
            // k == DELETED_KEY → keep probing
            idx = (idx + 1) & (capacity - 1);
        }
        return null;
    }

    @Override
    public Object get(Object key) {
        if (key instanceof Long l) return getLong(l);
        return null;
    }

    public boolean containsKeyLong(long key) {
        return getLong(key) != null;
    }

    @Override
    public boolean containsKey(Object key) {
        return key instanceof Long l && getLong(l) != null;
    }

    @Override
    public MapStorage put(Object key, Object value) {
        if (key instanceof Long l && isStorable(l)) {
            return putLong(l, value);
        }
        // Non-Long key (or sentinel collision) — transition to generic storage.
        return transitionToGeneric().put(key, value);
    }

    public MapStorage putLong(long key, Object value) {
        if (!isStorable(key)) {
            return transitionToGeneric().put(Long.valueOf(key), value);
        }
        if (size >= capacity * LOAD_FACTOR) {
            return grow().putLong(key, value);
        }

        long[] workKeys;
        Object[] workValues;
        Object[] token;
        if (isOwner()) {
            workKeys = keys;
            workValues = values;
            token = ownerToken;
        } else {
            workKeys = keys.clone();
            workValues = values.clone();
            token = new Object[1];
        }

        int idx = hash(key) & (capacity - 1);
        int insertIdx = -1;
        for (int i = 0; i < capacity; i++) {
            long k = workKeys[idx];
            if (k == EMPTY_KEY) {
                if (insertIdx < 0) insertIdx = idx;
                workKeys[insertIdx] = key;
                workValues[insertIdx] = value;
                var result = new LongHashMapStorage(workKeys, workValues, size + 1, capacity, token);
                token[0] = result;
                return result;
            }
            if (k == DELETED_KEY) {
                if (insertIdx < 0) insertIdx = idx;
                idx = (idx + 1) & (capacity - 1);
                continue;
            }
            if (k == key) {
                workValues[idx] = value;
                var result = new LongHashMapStorage(workKeys, workValues, size, capacity, token);
                token[0] = result;
                return result;
            }
            idx = (idx + 1) & (capacity - 1);
        }
        // Should not happen with load factor enforced.
        if (insertIdx >= 0) {
            workKeys[insertIdx] = key;
            workValues[insertIdx] = value;
            var result = new LongHashMapStorage(workKeys, workValues, size + 1, capacity, token);
            token[0] = result;
            return result;
        }
        throw new IllegalStateException("LongHashMap table full");
    }

    @Override
    public MapStorage remove(Object key) {
        if (key instanceof Long l) return removeLong(l);
        return this;
    }

    public MapStorage removeLong(long key) {
        if (!isStorable(key)) return this;
        revokeOwnership();
        int idx = hash(key) & (capacity - 1);
        for (int i = 0; i < capacity; i++) {
            long k = keys[idx];
            if (k == EMPTY_KEY) return this;
            if (k == key) {
                int newSize = size - 1;
                if (newSize == 0) return EmptyMapStorage.INSTANCE;
                long[] newKeys = keys.clone();
                Object[] newValues = values.clone();
                newKeys[idx] = DELETED_KEY;
                newValues[idx] = null;
                if (newSize < capacity / 4 && capacity > MIN_CAPACITY) {
                    return shrink(newKeys, newValues, newSize);
                }
                return new LongHashMapStorage(newKeys, newValues, newSize, capacity, null);
            }
            idx = (idx + 1) & (capacity - 1);
        }
        return this;
    }

    @Override
    public Object[] keys() {
        revokeOwnership();
        Object[] out = new Object[size];
        int j = 0;
        for (int i = 0; i < capacity; i++) {
            long k = keys[i];
            if (k != EMPTY_KEY && k != DELETED_KEY) {
                out[j++] = Long.valueOf(k);
            }
        }
        return out;
    }

    @Override
    public Object[] values() {
        revokeOwnership();
        Object[] out = new Object[size];
        int j = 0;
        for (int i = 0; i < capacity; i++) {
            long k = keys[i];
            if (k != EMPTY_KEY && k != DELETED_KEY) {
                out[j++] = values[i];
            }
        }
        return out;
    }

    @Override
    public byte getStrategyTag() {
        return TAG_HASH;  // shared with HashMapStorage; both are open-addressed hash storages
    }

    /** Primitive-keyed iteration — no boxing for keys. */
    public void forEachLong(LongValueConsumer consumer) {
        for (int i = 0; i < capacity; i++) {
            long k = keys[i];
            if (k != EMPTY_KEY && k != DELETED_KEY) {
                consumer.accept(k, values[i]);
            }
        }
    }

    @FunctionalInterface
    public interface LongValueConsumer {
        void accept(long key, Object value);
    }

    private LongHashMapStorage grow() {
        int newCapacity = capacity * 2;
        long[] newKeys = newKeysArray(newCapacity);
        Object[] newValues = new Object[newCapacity];
        int newSize = 0;
        for (int i = 0; i < capacity; i++) {
            long k = keys[i];
            if (k == EMPTY_KEY || k == DELETED_KEY) continue;
            int idx = hash(k) & (newCapacity - 1);
            while (newKeys[idx] != EMPTY_KEY) idx = (idx + 1) & (newCapacity - 1);
            newKeys[idx] = k;
            newValues[idx] = values[i];
            newSize++;
        }
        Object[] token = new Object[]{null};
        var result = new LongHashMapStorage(newKeys, newValues, newSize, newCapacity, token);
        token[0] = result;
        return result;
    }

    private LongHashMapStorage shrink(long[] oldKeys, Object[] oldValues, int newSize) {
        int newCapacity = Math.max(MIN_CAPACITY, capacity / 2);
        long[] newKeys = newKeysArray(newCapacity);
        Object[] newValues = new Object[newCapacity];
        for (int i = 0; i < oldKeys.length; i++) {
            long k = oldKeys[i];
            if (k == EMPTY_KEY || k == DELETED_KEY) continue;
            int idx = hash(k) & (newCapacity - 1);
            while (newKeys[idx] != EMPTY_KEY) idx = (idx + 1) & (newCapacity - 1);
            newKeys[idx] = k;
            newValues[idx] = oldValues[i];
        }
        return new LongHashMapStorage(newKeys, newValues, newSize, newCapacity, null);
    }

    /** Promote to {@link HashMapStorage} when a non-Long key arrives or when
     *  a sentinel-valued Long key tries to enter. Boxes everything. */
    private HashMapStorage transitionToGeneric() {
        HashMapStorage out = new HashMapStorage();
        for (int i = 0; i < capacity; i++) {
            long k = keys[i];
            if (k == EMPTY_KEY || k == DELETED_KEY) continue;
            out = (HashMapStorage) out.put(Long.valueOf(k), values[i]);
        }
        return out;
    }
}
