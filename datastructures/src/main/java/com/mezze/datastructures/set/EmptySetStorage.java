package com.mezze.datastructures.set;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;

/**
 * Singleton storage for empty sets.
 */
public final class EmptySetStorage implements SetStorage {

    @CompilationFinal
    public static final EmptySetStorage INSTANCE = new EmptySetStorage();

    private static final Object[] EMPTY_ARRAY = new Object[0];

    private EmptySetStorage() {}

    @Override
    public int size() {
        return 0;
    }

    @Override
    public boolean isEmpty() {
        return true;
    }

    @Override
    public boolean contains(Object elem) {
        return false;
    }

    @Override
    public SetStorage add(Object elem) {
        return new SingleElemSetStorage(elem);
    }

    @Override
    public SetStorage remove(Object elem) {
        return this;
    }

    @Override
    public Object[] toArray() {
        return EMPTY_ARRAY;
    }

    @Override
    public byte getStrategyTag() {
        return TAG_EMPTY;
    }

    @Override
    public String toString() {
        return "EmptySetStorage{}";
    }
}
