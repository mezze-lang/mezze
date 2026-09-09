package com.mezze.interpreter.values;

import com.mezze.datastructures.map.MezzeMap;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.util.HashMap;
import java.util.Map;

/**
 * Mutable map wrapper for safe local mutation.
 *
 * This is the runtime representation of MutMap - a mutable hash map.
 */
public final class MutableMap {
    private final Map<Object, Object> data;

    private MutableMap(Map<Object, Object> data) {
        this.data = data;
    }

    @TruffleBoundary
    public static MutableMap fromImmutable(MezzeMap<?, ?> map) {
        Map<Object, Object> data = new HashMap<>();
        for (MezzeMap.Entry<?, ?> entry : map) {
            data.put(entry.key(), entry.value());
        }
        return new MutableMap(data);
    }

    @TruffleBoundary
    public static MutableMap withCapacity(int capacity) {
        return new MutableMap(new HashMap<>(capacity));
    }

    @TruffleBoundary
    public MezzeMap toImmutable() {
        MezzeMap result = MezzeMap.empty();
        for (Map.Entry<Object, Object> entry : data.entrySet()) {
            result = result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    @TruffleBoundary
    public int size() {
        return data.size();
    }

    @TruffleBoundary
    public Object get(Object key) {
        return data.get(key);
    }

    @TruffleBoundary
    public void put(Object key, Object value) {
        data.put(key, value);
    }

    @TruffleBoundary
    public void remove(Object key) {
        data.remove(key);
    }

    @TruffleBoundary
    public boolean containsKey(Object key) {
        return data.containsKey(key);
    }

    @TruffleBoundary
    public void clear() {
        data.clear();
    }

    /**
     * Get all keys as an array.
     */
    @TruffleBoundary
    public Object[] keys() {
        return data.keySet().toArray();
    }

    /**
     * Get all values as an array.
     */
    @TruffleBoundary
    public Object[] values() {
        return data.values().toArray();
    }

    /**
     * Get all entries as an array of {k, v} records.
     */
    @TruffleBoundary
    public MezzeRecord[] entries() {
        MezzeRecord[] result = new MezzeRecord[data.size()];
        int i = 0;
        for (Map.Entry<Object, Object> entry : data.entrySet()) {
            result[i++] = MezzeRecord.fromFields(
                new String[]{"k", "v"},
                new Object[]{entry.getKey(), entry.getValue()}
            );
        }
        return result;
    }

    /**
     * Update value at key using a function. Returns true if key existed.
     */
    @TruffleBoundary
    public boolean update(Object key, java.util.function.Function<Object, Object> updater) {
        if (data.containsKey(key)) {
            data.put(key, updater.apply(data.get(key)));
            return true;
        }
        return false;
    }

    /**
     * Get value or insert default. Returns the value.
     */
    @TruffleBoundary
    public Object getOrInsert(Object key, Object defaultValue) {
        if (!data.containsKey(key)) {
            data.put(key, defaultValue);
        }
        return data.get(key);
    }
}
