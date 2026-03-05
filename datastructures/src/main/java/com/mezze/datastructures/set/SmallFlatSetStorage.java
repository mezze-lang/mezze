package com.mezze.datastructures.set;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.util.Arrays;
import java.util.Objects;

/**
 * Flat array storage for small sets (≤16 elements).
 * Linear search is faster than hashing for small sizes due to cache locality.
 */
public final class SmallFlatSetStorage implements SetStorage {

    public static final int MAX_SIZE = 16;

    @CompilationFinal(dimensions = 1)
    private final Object[] elements;

    public SmallFlatSetStorage(Object[] elements) {
        this.elements = elements;
    }

    @Override
    public int size() {
        return elements.length;
    }

    @Override
    public boolean contains(Object elem) {
        for (int i = 0; i < elements.length; i++) {
            if (Objects.equals(elements[i], elem)) {
                return true;
            }
        }
        return false;
    }

    @Override
    @TruffleBoundary
    public SetStorage add(Object elem) {
        // Check if already present
        for (int i = 0; i < elements.length; i++) {
            if (Objects.equals(elements[i], elem)) {
                return this;
            }
        }

        if (elements.length < MAX_SIZE) {
            Object[] newElements = Arrays.copyOf(elements, elements.length + 1);
            newElements[elements.length] = elem;
            return new SmallFlatSetStorage(newElements);
        }

        // Transition to HashSetStorage
        return transitionToHash().add(elem);
    }

    @Override
    @TruffleBoundary
    public SetStorage remove(Object elem) {
        int idx = -1;
        for (int i = 0; i < elements.length; i++) {
            if (Objects.equals(elements[i], elem)) {
                idx = i;
                break;
            }
        }

        if (idx < 0) {
            return this;
        }

        if (elements.length == 1) {
            return EmptySetStorage.INSTANCE;
        }

        if (elements.length == 2) {
            int otherIdx = idx == 0 ? 1 : 0;
            return new SingleElemSetStorage(elements[otherIdx]);
        }

        Object[] newElements = new Object[elements.length - 1];
        System.arraycopy(elements, 0, newElements, 0, idx);
        System.arraycopy(elements, idx + 1, newElements, idx, elements.length - idx - 1);

        return new SmallFlatSetStorage(newElements);
    }

    @Override
    public Object[] toArray() {
        return elements.clone();
    }

    @Override
    public byte getStrategyTag() {
        return TAG_SMALL_FLAT;
    }

    /**
     * Returns element at index (for iteration).
     */
    public Object getAt(int index) {
        return elements[index];
    }

    @TruffleBoundary
    private HashSetStorage transitionToHash() {
        HashSetStorage hash = new HashSetStorage();
        for (Object elem : elements) {
            hash = (HashSetStorage) hash.add(elem);
        }
        return hash;
    }

    @Override
    @TruffleBoundary
    public String toString() {
        return "SmallFlatSetStorage" + Arrays.toString(elements);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof SmallFlatSetStorage other)) return false;
        if (elements.length != other.elements.length) return false;

        for (Object elem : elements) {
            if (!other.contains(elem)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = 0;
        for (Object elem : elements) {
            result += Objects.hashCode(elem);
        }
        return result;
    }
}
