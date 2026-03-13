package com.mezze.interpreter.v2.values;

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

    public int size() {
        return data.size();
    }

    public boolean contains(Object elem) {
        return data.contains(elem);
    }

    public void add(Object elem) {
        data.add(elem);
    }

    public void remove(Object elem) {
        data.remove(elem);
    }

    public void clear() {
        data.clear();
    }
}
