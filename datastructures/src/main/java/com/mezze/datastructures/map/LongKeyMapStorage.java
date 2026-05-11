package com.mezze.datastructures.map;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.util.Arrays;

/**
 * Unboxed long[] keys for maps with Int/Nat keys.
 * Eliminates boxing overhead for key lookups.
 *
 * Keys are stored in sorted order for O(log n) binary search lookups.
 */
public final class LongKeyMapStorage implements MapStorage {

    public static final int MAX_SIZE = 32;

    @CompilationFinal(dimensions = 1)
    private final long[] keys;  // Always sorted

    @CompilationFinal(dimensions = 1)
    private final Object[] values;  // Parallel to keys

    /**
     * Creates storage from key-value pairs. Sorts if needed.
     */
    public LongKeyMapStorage(long[] keys, Object[] values) {
        this(keys, values, false);
    }

    /**
     * Creates storage with sort hint.
     * @param alreadySorted if true, skips sorting
     */
    public LongKeyMapStorage(long[] keys, Object[] values, boolean alreadySorted) {
        if (alreadySorted || keys.length <= 1) {
            this.keys = keys;
            this.values = values;
        } else {
            // Sort keys and values together
            Integer[] indices = new Integer[keys.length];
            for (int i = 0; i < indices.length; i++) {
                indices[i] = i;
            }
            java.util.Arrays.sort(indices, (a, b) -> Long.compare(keys[a], keys[b]));

            this.keys = new long[keys.length];
            this.values = new Object[values.length];
            for (int i = 0; i < indices.length; i++) {
                this.keys[i] = keys[indices[i]];
                this.values[i] = values[indices[i]];
            }
        }
    }

    /**
     * Creates storage from pre-sorted key-value pairs (internal use).
     */
    private static LongKeyMapStorage fromSorted(long[] sortedKeys, Object[] sortedValues) {
        return new LongKeyMapStorage(sortedKeys, sortedValues, true);
    }

    @Override
    public int size() {
        return keys.length;
    }

    @Override
    public Object get(Object key) {
        if (key instanceof Long l) {
            return getLong(l);
        }
        return null;
    }

    /**
     * Unboxed key lookup using binary search. O(log n).
     */
    public Object getLong(long key) {
        int idx = binarySearch(key);
        return idx >= 0 ? values[idx] : null;
    }

    /**
     * Binary search for key.
     * @return index if found, or -(insertionPoint + 1) if not found
     */
    private int binarySearch(long key) {
        int low = 0;
        int high = keys.length - 1;

        while (low <= high) {
            int mid = (low + high) >>> 1;
            long midVal = keys[mid];

            if (midVal < key) {
                low = mid + 1;
            } else if (midVal > key) {
                high = mid - 1;
            } else {
                return mid;
            }
        }
        return -(low + 1);
    }

    @Override
    public boolean containsKey(Object key) {
        if (key instanceof Long l) {
            return containsKeyLong(l);
        }
        return false;
    }

    /**
     * Unboxed contains check using binary search. O(log n).
     */
    public boolean containsKeyLong(long key) {
        return binarySearch(key) >= 0;
    }

    @Override
    public MapStorage put(Object key, Object value) {
        if (key instanceof Long l) {
            return putLong(l, value);
        }
        // Key type changed - transition to generic storage
        return transitionToGeneric().put(key, value);
    }

    /**
     * Unboxed put using binary search. Maintains sorted order.
     */
    public MapStorage putLong(long key, Object value) {
        int idx = binarySearch(key);

        if (idx >= 0) {
            // Update existing
            Object[] newValues = values.clone();
            newValues[idx] = value;
            return fromSorted(keys, newValues);
        }

        // Add new entry at correct position
        if (keys.length < MAX_SIZE) {
            int insertPos = -(idx + 1);
            long[] newKeys = new long[keys.length + 1];
            Object[] newValues = new Object[values.length + 1];

            System.arraycopy(keys, 0, newKeys, 0, insertPos);
            newKeys[insertPos] = key;
            System.arraycopy(keys, insertPos, newKeys, insertPos + 1, keys.length - insertPos);

            System.arraycopy(values, 0, newValues, 0, insertPos);
            newValues[insertPos] = value;
            System.arraycopy(values, insertPos, newValues, insertPos + 1, values.length - insertPos);

            return fromSorted(newKeys, newValues);
        }

        // Transition to HashMapStorage
        return transitionToHash().put(key, value);
    }

    @Override
    public MapStorage remove(Object key) {
        if (key instanceof Long l) {
            return removeLong(l);
        }
        return this;
    }

    /**
     * Unboxed remove using binary search.
     */
    public MapStorage removeLong(long key) {
        int idx = binarySearch(key);

        if (idx < 0) {
            return this;  // Not found
        }

        if (keys.length == 1) {
            return EmptyMapStorage.INSTANCE;
        }

        long[] newKeys = new long[keys.length - 1];
        Object[] newValues = new Object[values.length - 1];
        System.arraycopy(keys, 0, newKeys, 0, idx);
        System.arraycopy(keys, idx + 1, newKeys, idx, keys.length - idx - 1);
        System.arraycopy(values, 0, newValues, 0, idx);
        System.arraycopy(values, idx + 1, newValues, idx, values.length - idx - 1);

        return fromSorted(newKeys, newValues);  // Already sorted
    }

    @Override
    public Object[] keys() {
        Object[] result = new Object[keys.length];
        for (int i = 0; i < keys.length; i++) {
            result[i] = keys[i];
        }
        return result;
    }

    /**
     * Returns unboxed keys array.
     */
    public long[] keysLong() {
        return keys.clone();
    }

    @Override
    public Object[] values() {
        return values.clone();
    }

    @Override
    public byte getStrategyTag() {
        return TAG_LONG_KEY;
    }

    /**
     * Iterates over all entries without boxing keys.
     * Uses direct array iteration for maximum efficiency.
     */
    @Override
    public void forEach(java.util.function.BiConsumer<Object, Object> consumer) {
        for (int i = 0; i < keys.length; i++) {
            consumer.accept(keys[i], values[i]);
        }
    }

    /**
     * Iterates over all entries with unboxed long keys.
     */
    public void forEachLong(LongObjConsumer consumer) {
        for (int i = 0; i < keys.length; i++) {
            consumer.accept(keys[i], values[i]);
        }
    }

    @FunctionalInterface
    public interface LongObjConsumer {
        void accept(long key, Object value);
    }

    /**
     * Returns key at index (unboxed).
     */
    public long getKeyAtLong(int index) {
        return keys[index];
    }

    /**
     * Returns value at index.
     */
    public Object getValueAt(int index) {
        return values[index];
    }

    private SmallFlatMapStorage transitionToGeneric() {
        Object[] boxedKeys = new Object[keys.length];
        for (int i = 0; i < keys.length; i++) {
            boxedKeys[i] = keys[i];
        }
        return new SmallFlatMapStorage(boxedKeys, values.clone());
    }

    /** Promote to {@link LongHashMapStorage} (primitive-keyed, no boxing) when
     *  every key is sentinel-safe; fall back to {@link HashMapStorage} otherwise. */
    private MapStorage transitionToHash() {
        for (long k : keys) {
            if (!LongHashMapStorage.isStorable(k)) {
                HashMapStorage hash = new HashMapStorage();
                for (int i = 0; i < keys.length; i++) {
                    hash = (HashMapStorage) hash.put(keys[i], values[i]);
                }
                return hash;
            }
        }
        LongHashMapStorage out = new LongHashMapStorage();
        for (int i = 0; i < keys.length; i++) {
            out = (LongHashMapStorage) out.putLong(keys[i], values[i]);
        }
        return out;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("LongKeyMapStorage{");
        for (int i = 0; i < keys.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(keys[i]).append("=").append(values[i]);
        }
        sb.append("}");
        return sb.toString();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof LongKeyMapStorage other)) return false;
        return Arrays.equals(keys, other.keys) && Arrays.equals(values, other.values);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(keys) ^ Arrays.hashCode(values);
    }
}
