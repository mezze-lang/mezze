package com.mezze.datastructures.set;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;

import java.util.Objects;

/**
 * Storage for single-element sets.
 */
public final class SingleElemSetStorage implements SetStorage {

    @CompilationFinal
    private final Object element;

    public SingleElemSetStorage(Object element) {
        this.element = element;
    }

    @Override
    public int size() {
        return 1;
    }

    @Override
    public boolean contains(Object elem) {
        return Objects.equals(element, elem);
    }

    @Override
    public SetStorage add(Object elem) {
        if (Objects.equals(element, elem)) {
            return this;
        }

        // Check if both are longs for specialized storage
        if (element instanceof Long l1 && elem instanceof Long l2) {
            return new LongSetStorage(new long[]{l1, l2});
        }

        return new SmallFlatSetStorage(new Object[]{element, elem});
    }

    @Override
    public SetStorage remove(Object elem) {
        if (Objects.equals(element, elem)) {
            return EmptySetStorage.INSTANCE;
        }
        return this;
    }

    @Override
    public Object[] toArray() {
        return new Object[]{element};
    }

    @Override
    public byte getStrategyTag() {
        return TAG_SINGLE;
    }

    public Object getElement() {
        return element;
    }

    @Override
    public String toString() {
        return "SingleElemSetStorage{" + element + "}";
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof SingleElemSetStorage other)) return false;
        return Objects.equals(element, other.element);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(element);
    }
}
