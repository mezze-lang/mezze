package com.mezze.interpreter.values;

import com.mezze.datastructures.set.MezzeSet;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.util.HashSet;
import java.util.Set;

/**
 * Mutable set wrapper for safe local mutation.
 *
 * This is the runtime representation of MutSet - a mutable hash set.
 */
public final class MutableSet {
    private final Set<Object> data;

    private MutableSet(Set<Object> data) {
        this.data = data;
    }

    @TruffleBoundary
    public static MutableSet fromImmutable(MezzeSet<?> set) {
        Set<Object> data = new HashSet<>();
        for (Object elem : set) {
            data.add(elem);
        }
        return new MutableSet(data);
    }

    @TruffleBoundary
    public static MutableSet withCapacity(int capacity) {
        return new MutableSet(new HashSet<>(capacity));
    }

    @TruffleBoundary
    public MezzeSet toImmutable() {
        MezzeSet result = MezzeSet.empty();
        for (Object elem : data) {
            result = result.add(elem);
        }
        return result;
    }

    @TruffleBoundary
    public int size() {
        return data.size();
    }

    @TruffleBoundary
    public boolean contains(Object elem) {
        return data.contains(elem);
    }

    @TruffleBoundary
    public void add(Object elem) {
        data.add(elem);
    }

    @TruffleBoundary
    public void remove(Object elem) {
        data.remove(elem);
    }

    @TruffleBoundary
    public void clear() {
        data.clear();
    }

    /**
     * Get all elements as an array.
     */
    @TruffleBoundary
    public Object[] elements() {
        return data.toArray();
    }

    /**
     * Add all elements from another set.
     */
    @TruffleBoundary
    public void addAll(MutableSet other) {
        data.addAll(other.data);
    }

    /**
     * Remove all elements from another set.
     */
    @TruffleBoundary
    public void removeAll(MutableSet other) {
        data.removeAll(other.data);
    }

    /**
     * Keep only elements that are also in another set (intersection).
     */
    @TruffleBoundary
    public void retainAll(MutableSet other) {
        data.retainAll(other.data);
    }

    /**
     * Toggle element - add if not present, remove if present.
     * Returns true if element is now in the set.
     */
    @TruffleBoundary
    public boolean toggle(Object elem) {
        if (data.contains(elem)) {
            data.remove(elem);
            return false;
        } else {
            data.add(elem);
            return true;
        }
    }
}
