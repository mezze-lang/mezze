package com.mezze.datastructures.map;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;

/**
 * Open-addressing hash map specialized for primitive {@code double} keys.
 *
 * Mirrors {@link LongHashMapStorage} but stores keys as the raw {@code long}
 * bit pattern of each {@code double} (via {@link Double#doubleToRawLongBits}).
 * This makes every probe a primitive {@code ==} on a {@code long[]} — no
 * boxing, no virtual {@code Objects.equals} dispatch.
 *
 * <p>Storing the raw bits also gives the right semantics for IEEE-754
 * edge cases:
 * <ul>
 *   <li>{@code +0.0} and {@code -0.0} have different bits → treated as
 *       distinct keys (matches {@code java.util.HashMap} for Double keys).
 *   <li>All NaN bit-patterns canonicalize to a single bit pattern via
 *       {@link Double#doubleToLongBits} on insertion, so any two NaN keys
 *       compare equal.
 * </ul>
 *
 * <p>Sentinels reuse two NaN bit patterns that {@code doubleToLongBits}
 * never produces (it normalizes all NaNs to {@code 0x7ff8000000000000L}),
 * so they cannot collide with any user-storable key.
 */
public final class DoubleHashMapStorage implements MapStorage {

    private static final int MIN_CAPACITY = 16;
    private static final float LOAD_FACTOR = 0.75f;

    /** Sentinel for an empty slot — a NaN bit pattern that
     *  {@link Double#doubleToLongBits} never returns. */
    private static final long EMPTY_BITS = 0x7ff8000000000001L;
    /** Sentinel for a deleted slot — another non-canonical NaN bit pattern. */
    private static final long DELETED_BITS = 0x7ff8000000000002L;

    @CompilationFinal(dimensions = 1)
    private final long[] keys;

    @CompilationFinal(dimensions = 1)
    private final Object[] values;

    @CompilationFinal
    private final int size;

    @CompilationFinal
    private final int capacity;

    private final Object[] ownerToken;

    @CompilationFinal
    private boolean ownershipRevoked;

    public DoubleHashMapStorage() {
        this.capacity = MIN_CAPACITY;
        this.keys = newKeysArray(MIN_CAPACITY);
        this.values = new Object[MIN_CAPACITY];
        this.size = 0;
        this.ownerToken = new Object[]{this};
    }

    private DoubleHashMapStorage(long[] keys, Object[] values, int size, int capacity, Object[] ownerToken) {
        this.keys = keys;
        this.values = values;
        this.size = size;
        this.capacity = capacity;
        this.ownerToken = ownerToken;
    }

    private static long[] newKeysArray(int capacity) {
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

    /** Every double can be stored — {@link Double#doubleToLongBits} never
     *  produces our sentinel bit patterns, so there's no collision case. */
    public static boolean isStorable(@SuppressWarnings("unused") double k) {
        return true;
    }

    /** Murmur3 finalizer over the raw bits of the double. */
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

    public Object getDouble(double key) {
        long bits = Double.doubleToLongBits(key);
        revokeOwnership();
        int idx = hash(bits) & (capacity - 1);
        for (int i = 0; i < capacity; i++) {
            long k = keys[idx];
            if (k == EMPTY_BITS) return null;
            if (k == bits) return values[idx];
            idx = (idx + 1) & (capacity - 1);
        }
        return null;
    }

    @Override
    public Object get(Object key) {
        if (key instanceof Double d) return getDouble(d);
        return null;
    }

    public boolean containsKeyDouble(double key) {
        return getDouble(key) != null;
    }

    @Override
    public boolean containsKey(Object key) {
        return key instanceof Double d && getDouble(d) != null;
    }

    @Override
    public MapStorage put(Object key, Object value) {
        if (key instanceof Double d) return putDouble(d, value);
        return transitionToGeneric().put(key, value);
    }

    public MapStorage putDouble(double key, Object value) {
        if (size >= capacity * LOAD_FACTOR) {
            return grow().putDouble(key, value);
        }

        long bits = Double.doubleToLongBits(key);

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

        int idx = hash(bits) & (capacity - 1);
        int insertIdx = -1;
        for (int i = 0; i < capacity; i++) {
            long k = workKeys[idx];
            if (k == EMPTY_BITS) {
                if (insertIdx < 0) insertIdx = idx;
                workKeys[insertIdx] = bits;
                workValues[insertIdx] = value;
                var result = new DoubleHashMapStorage(workKeys, workValues, size + 1, capacity, token);
                token[0] = result;
                return result;
            }
            if (k == DELETED_BITS) {
                if (insertIdx < 0) insertIdx = idx;
                idx = (idx + 1) & (capacity - 1);
                continue;
            }
            if (k == bits) {
                workValues[idx] = value;
                var result = new DoubleHashMapStorage(workKeys, workValues, size, capacity, token);
                token[0] = result;
                return result;
            }
            idx = (idx + 1) & (capacity - 1);
        }
        if (insertIdx >= 0) {
            workKeys[insertIdx] = bits;
            workValues[insertIdx] = value;
            var result = new DoubleHashMapStorage(workKeys, workValues, size + 1, capacity, token);
            token[0] = result;
            return result;
        }
        throw new IllegalStateException("DoubleHashMap table full");
    }

    @Override
    public MapStorage remove(Object key) {
        if (key instanceof Double d) return removeDouble(d);
        return this;
    }

    public MapStorage removeDouble(double key) {
        revokeOwnership();
        long bits = Double.doubleToLongBits(key);
        int idx = hash(bits) & (capacity - 1);
        for (int i = 0; i < capacity; i++) {
            long k = keys[idx];
            if (k == EMPTY_BITS) return this;
            if (k == bits) {
                int newSize = size - 1;
                if (newSize == 0) return EmptyMapStorage.INSTANCE;
                long[] newKeys = keys.clone();
                Object[] newValues = values.clone();
                newKeys[idx] = DELETED_BITS;
                newValues[idx] = null;
                if (newSize < capacity / 4 && capacity > MIN_CAPACITY) {
                    return shrink(newKeys, newValues, newSize);
                }
                return new DoubleHashMapStorage(newKeys, newValues, newSize, capacity, null);
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
            if (k != EMPTY_BITS && k != DELETED_BITS) {
                out[j++] = Double.longBitsToDouble(k);
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
            if (k != EMPTY_BITS && k != DELETED_BITS) {
                out[j++] = values[i];
            }
        }
        return out;
    }

    @Override
    public byte getStrategyTag() {
        return TAG_HASH;
    }

    public void forEachDouble(DoubleValueConsumer consumer) {
        for (int i = 0; i < capacity; i++) {
            long k = keys[i];
            if (k != EMPTY_BITS && k != DELETED_BITS) {
                consumer.accept(Double.longBitsToDouble(k), values[i]);
            }
        }
    }

    @FunctionalInterface
    public interface DoubleValueConsumer {
        void accept(double key, Object value);
    }

    private DoubleHashMapStorage grow() {
        int newCapacity = capacity * 2;
        long[] newKeys = newKeysArray(newCapacity);
        Object[] newValues = new Object[newCapacity];
        int newSize = 0;
        for (int i = 0; i < capacity; i++) {
            long k = keys[i];
            if (k == EMPTY_BITS || k == DELETED_BITS) continue;
            int idx = hash(k) & (newCapacity - 1);
            while (newKeys[idx] != EMPTY_BITS) idx = (idx + 1) & (newCapacity - 1);
            newKeys[idx] = k;
            newValues[idx] = values[i];
            newSize++;
        }
        Object[] token = new Object[]{null};
        var result = new DoubleHashMapStorage(newKeys, newValues, newSize, newCapacity, token);
        token[0] = result;
        return result;
    }

    private DoubleHashMapStorage shrink(long[] oldKeys, Object[] oldValues, int newSize) {
        int newCapacity = Math.max(MIN_CAPACITY, capacity / 2);
        long[] newKeys = newKeysArray(newCapacity);
        Object[] newValues = new Object[newCapacity];
        for (int i = 0; i < oldKeys.length; i++) {
            long k = oldKeys[i];
            if (k == EMPTY_BITS || k == DELETED_BITS) continue;
            int idx = hash(k) & (newCapacity - 1);
            while (newKeys[idx] != EMPTY_BITS) idx = (idx + 1) & (newCapacity - 1);
            newKeys[idx] = k;
            newValues[idx] = oldValues[i];
        }
        return new DoubleHashMapStorage(newKeys, newValues, newSize, newCapacity, null);
    }

    private HashMapStorage transitionToGeneric() {
        HashMapStorage out = new HashMapStorage();
        for (int i = 0; i < capacity; i++) {
            long k = keys[i];
            if (k == EMPTY_BITS || k == DELETED_BITS) continue;
            out = (HashMapStorage) out.put(Double.longBitsToDouble(k), values[i]);
        }
        return out;
    }
}
