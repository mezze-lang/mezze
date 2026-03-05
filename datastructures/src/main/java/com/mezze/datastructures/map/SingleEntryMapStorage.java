package com.mezze.datastructures.map;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;

import java.util.Objects;

/**
 * Storage for single-entry maps.
 * Optimal for maps that stay small.
 */
public final class SingleEntryMapStorage implements MapStorage {

    @CompilationFinal
    private final Object key;

    @CompilationFinal
    private final Object value;

    public SingleEntryMapStorage(Object key, Object value) {
        this.key = key;
        this.value = value;
    }

    @Override
    public int size() {
        return 1;
    }

    @Override
    public Object get(Object searchKey) {
        return Objects.equals(key, searchKey) ? value : null;
    }

    @Override
    public boolean containsKey(Object searchKey) {
        return Objects.equals(key, searchKey);
    }

    @Override
    public MapStorage put(Object newKey, Object newValue) {
        if (Objects.equals(key, newKey)) {
            // Update existing
            return new SingleEntryMapStorage(newKey, newValue);
        }
        // Transition to SmallFlatMapStorage
        return new SmallFlatMapStorage(
                new Object[]{key, newKey},
                new Object[]{value, newValue}
        );
    }

    @Override
    public MapStorage remove(Object removeKey) {
        if (Objects.equals(key, removeKey)) {
            return EmptyMapStorage.INSTANCE;
        }
        return this;
    }

    @Override
    public Object[] keys() {
        return new Object[]{key};
    }

    @Override
    public Object[] values() {
        return new Object[]{value};
    }

    @Override
    public byte getStrategyTag() {
        return TAG_SINGLE;
    }

    public Object getKey() {
        return key;
    }

    public Object getValue() {
        return value;
    }

    @Override
    public String toString() {
        return "SingleEntryMapStorage{" + key + "=" + value + "}";
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof SingleEntryMapStorage other)) return false;
        return Objects.equals(key, other.key) && Objects.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, value);
    }
}
