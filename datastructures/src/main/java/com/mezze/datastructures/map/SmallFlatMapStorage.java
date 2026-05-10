package com.mezze.datastructures.map;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.ExplodeLoop;

import java.util.Arrays;
import java.util.Objects;

/**
 * Flat array storage for small maps (≤8 entries).
 * Uses linear search which is faster than hashing for small sizes due to cache locality.
 */
public final class SmallFlatMapStorage implements MapStorage {

    public static final int MAX_SIZE = 8;

    @CompilationFinal(dimensions = 1)
    private final Object[] keys;

    @CompilationFinal(dimensions = 1)
    private final Object[] values;

    public SmallFlatMapStorage(Object[] keys, Object[] values) {
        this.keys = keys;
        this.values = values;
    }

    @Override
    public int size() {
        return keys.length;
    }

    @Override
    @ExplodeLoop(kind = ExplodeLoop.LoopExplosionKind.FULL_UNROLL_UNTIL_RETURN)
    public Object get(Object searchKey) {
        for (int i = 0; i < keys.length; i++) {
            if (Objects.equals(keys[i], searchKey)) {
                return values[i];
            }
        }
        return null;
    }

    @Override
    @ExplodeLoop(kind = ExplodeLoop.LoopExplosionKind.FULL_UNROLL_UNTIL_RETURN)
    public boolean containsKey(Object searchKey) {
        for (int i = 0; i < keys.length; i++) {
            if (Objects.equals(keys[i], searchKey)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public MapStorage put(Object newKey, Object newValue) {
        // Check if key exists
        for (int i = 0; i < keys.length; i++) {
            if (Objects.equals(keys[i], newKey)) {
                // Update existing
                Object[] newValues = values.clone();
                newValues[i] = newValue;
                return new SmallFlatMapStorage(keys, newValues);
            }
        }

        // Add new entry
        if (keys.length < MAX_SIZE) {
            Object[] newKeys = Arrays.copyOf(keys, keys.length + 1);
            Object[] newValues = Arrays.copyOf(values, values.length + 1);
            newKeys[keys.length] = newKey;
            newValues[values.length] = newValue;
            return new SmallFlatMapStorage(newKeys, newValues);
        }

        // Transition to HashMapStorage
        return transitionToHash().put(newKey, newValue);
    }

    @Override
    public MapStorage remove(Object removeKey) {
        int idx = -1;
        for (int i = 0; i < keys.length; i++) {
            if (Objects.equals(keys[i], removeKey)) {
                idx = i;
                break;
            }
        }

        if (idx < 0) {
            return this;
        }

        if (keys.length == 1) {
            return EmptyMapStorage.INSTANCE;
        }

        if (keys.length == 2) {
            int otherIdx = idx == 0 ? 1 : 0;
            return new SingleEntryMapStorage(keys[otherIdx], values[otherIdx]);
        }

        Object[] newKeys = new Object[keys.length - 1];
        Object[] newValues = new Object[values.length - 1];
        System.arraycopy(keys, 0, newKeys, 0, idx);
        System.arraycopy(keys, idx + 1, newKeys, idx, keys.length - idx - 1);
        System.arraycopy(values, 0, newValues, 0, idx);
        System.arraycopy(values, idx + 1, newValues, idx, values.length - idx - 1);

        return new SmallFlatMapStorage(newKeys, newValues);
    }

    @Override
    public Object[] keys() {
        return keys.clone();
    }

    @Override
    public Object[] values() {
        return values.clone();
    }

    @Override
    public byte getStrategyTag() {
        return TAG_SMALL_FLAT;
    }

    /**
     * Returns the key at index (for iteration).
     */
    public Object getKeyAt(int index) {
        return keys[index];
    }

    /**
     * Returns the value at index (for iteration).
     */
    public Object getValueAt(int index) {
        return values[index];
    }

    @TruffleBoundary
    private HashMapStorage transitionToHash() {
        HashMapStorage hash = new HashMapStorage();
        for (int i = 0; i < keys.length; i++) {
            hash = (HashMapStorage) hash.put(keys[i], values[i]);
        }
        return hash;
    }

    @Override
    @TruffleBoundary
    public String toString() {
        StringBuilder sb = new StringBuilder("SmallFlatMapStorage{");
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
        if (!(obj instanceof SmallFlatMapStorage other)) return false;
        if (keys.length != other.keys.length) return false;

        for (int i = 0; i < keys.length; i++) {
            Object v = other.get(keys[i]);
            if (!Objects.equals(values[i], v)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = 0;
        for (int i = 0; i < keys.length; i++) {
            result += Objects.hashCode(keys[i]) ^ Objects.hashCode(values[i]);
        }
        return result;
    }
}
