package com.mezze.datastructures.map;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;

/**
 * Singleton storage for empty maps.
 */
public final class EmptyMapStorage implements MapStorage {

    @CompilationFinal
    public static final EmptyMapStorage INSTANCE = new EmptyMapStorage();

    private static final Object[] EMPTY_ARRAY = new Object[0];

    private EmptyMapStorage() {}

    @Override
    public int size() {
        return 0;
    }

    @Override
    public boolean isEmpty() {
        return true;
    }

    @Override
    public Object get(Object key) {
        return null;
    }

    @Override
    public boolean containsKey(Object key) {
        return false;
    }

    @Override
    public MapStorage put(Object key, Object value) {
        return new SingleEntryMapStorage(key, value);
    }

    @Override
    public MapStorage remove(Object key) {
        return this;
    }

    @Override
    public Object[] keys() {
        return EMPTY_ARRAY;
    }

    @Override
    public Object[] values() {
        return EMPTY_ARRAY;
    }

    @Override
    public byte getStrategyTag() {
        return TAG_EMPTY;
    }

    @Override
    public String toString() {
        return "EmptyMapStorage{}";
    }
}
