package com.mezze.datastructures.map;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;

/**
 * Open-addressing hash map specialized for {@code String} keys.
 *
 * Sister of {@link LongHashMapStorage}. Keys live in a {@code String[]} so
 * the hot lookup path can call {@link String#equals(Object)} directly
 * (devirtualized — no {@code Objects.equals} dispatch through the {@code Object[]}
 * static type) and read the cached {@code String.hashCode()}.
 *
 * <p>String-keyed maps are extremely common in Mezze code (config records,
 * symbol tables, JSON-like objects), so this is one of the higher-impact
 * specializations.
 *
 * <p>Sentinels:
 * <ul>
 *   <li>{@code null} — empty slot (a {@link String} reference is never null
 *       once we've reached this storage; keys with a null value go through
 *       the generic path).
 *   <li>{@link #DELETED} — a sentinel String instance that no user could
 *       produce (held by reference identity).
 * </ul>
 */
public final class StringHashMapStorage implements MapStorage {

    private static final int MIN_CAPACITY = 16;
    /** 0.5 instead of the usual 0.75 — open-addressing punishes clustered
     *  String.hashCode (common for prefix-sharing keys like "key-0".."key-N").
     *  Halving load factor halves the average probe distance for the cost
     *  of ~2x memory in the keys/values arrays. */
    private static final float LOAD_FACTOR = 0.5f;

    /** Reference-identity tombstone — distinct from any user-supplied String. */
    static final String DELETED = new String("<DELETED>");

    @CompilationFinal(dimensions = 1)
    private final String[] keys;

    @CompilationFinal(dimensions = 1)
    private final Object[] values;

    @CompilationFinal
    private final int size;

    @CompilationFinal
    private final int capacity;

    private final Object[] ownerToken;

    @CompilationFinal
    private boolean ownershipRevoked;

    public StringHashMapStorage() {
        this.capacity = MIN_CAPACITY;
        this.keys = new String[MIN_CAPACITY];
        this.values = new Object[MIN_CAPACITY];
        this.size = 0;
        this.ownerToken = new Object[]{this};
    }

    private StringHashMapStorage(String[] keys, Object[] values, int size, int capacity, Object[] ownerToken) {
        this.keys = keys;
        this.values = values;
        this.size = size;
        this.capacity = capacity;
        this.ownerToken = ownerToken;
    }

    private boolean isOwner() {
        return !ownershipRevoked && ownerToken != null && ownerToken[0] == this;
    }

    private void revokeOwnership() {
        if (ownershipRevoked) return;
        if (ownerToken != null && ownerToken[0] == this) {
            ownerToken[0] = null;
        }
        ownershipRevoked = true;
    }

    /** All non-null Strings are storable; the tombstone is a private instance
     *  that user code cannot construct via reference identity. */
    public static boolean isStorable(String s) {
        return s != null;
    }

    /** Murmur3 finalizer over String.hashCode. String.hashCode's distribution
     *  for keys with shared prefixes is poor, and open addressing punishes
     *  clustered hashes much more than chaining does — so the extra mixing
     *  is a measurable win here despite the added math. */
    private static int hash(String s) {
        int h = s.hashCode();
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        return h;
    }

    @Override
    public int size() {
        return size;
    }

    public Object getString(String key) {
        if (key == null) return null;
        revokeOwnership();
        int idx = hash(key) & (capacity - 1);
        for (int i = 0; i < capacity; i++) {
            String k = keys[idx];
            if (k == null) return null;
            if (k != DELETED && (k == key || k.equals(key))) return values[idx];
            idx = (idx + 1) & (capacity - 1);
        }
        return null;
    }

    @Override
    public Object get(Object key) {
        if (key instanceof String s) return getString(s);
        return null;
    }

    public boolean containsKeyString(String key) {
        return getString(key) != null;
    }

    @Override
    public boolean containsKey(Object key) {
        return key instanceof String s && getString(s) != null;
    }

    @Override
    public MapStorage put(Object key, Object value) {
        if (key instanceof String s) return putString(s, value);
        return transitionToGeneric().put(key, value);
    }

    public MapStorage putString(String key, Object value) {
        if (key == null) {
            return transitionToGeneric().put(null, value);
        }
        if (size >= capacity * LOAD_FACTOR) {
            return grow().putString(key, value);
        }

        String[] workKeys;
        Object[] workValues;
        Object[] token;
        if (isOwner()) {
            workKeys = keys;
            workValues = values;
            token = ownerToken;
        } else {
            workKeys = keys.clone();
            workValues = values.clone();
            token = new Object[1];
        }

        int idx = hash(key) & (capacity - 1);
        int insertIdx = -1;
        for (int i = 0; i < capacity; i++) {
            String k = workKeys[idx];
            if (k == null) {
                if (insertIdx < 0) insertIdx = idx;
                workKeys[insertIdx] = key;
                workValues[insertIdx] = value;
                var result = new StringHashMapStorage(workKeys, workValues, size + 1, capacity, token);
                token[0] = result;
                return result;
            }
            if (k == DELETED) {
                if (insertIdx < 0) insertIdx = idx;
                idx = (idx + 1) & (capacity - 1);
                continue;
            }
            if (k == key || k.equals(key)) {
                workValues[idx] = value;
                var result = new StringHashMapStorage(workKeys, workValues, size, capacity, token);
                token[0] = result;
                return result;
            }
            idx = (idx + 1) & (capacity - 1);
        }
        if (insertIdx >= 0) {
            workKeys[insertIdx] = key;
            workValues[insertIdx] = value;
            var result = new StringHashMapStorage(workKeys, workValues, size + 1, capacity, token);
            token[0] = result;
            return result;
        }
        throw new IllegalStateException("StringHashMap table full");
    }

    @Override
    public MapStorage remove(Object key) {
        if (key instanceof String s) return removeString(s);
        return this;
    }

    public MapStorage removeString(String key) {
        if (key == null) return this;
        revokeOwnership();
        int idx = hash(key) & (capacity - 1);
        for (int i = 0; i < capacity; i++) {
            String k = keys[idx];
            if (k == null) return this;
            if (k != DELETED && (k == key || k.equals(key))) {
                int newSize = size - 1;
                if (newSize == 0) return EmptyMapStorage.INSTANCE;
                String[] newKeys = keys.clone();
                Object[] newValues = values.clone();
                newKeys[idx] = DELETED;
                newValues[idx] = null;
                if (newSize < capacity / 4 && capacity > MIN_CAPACITY) {
                    return shrink(newKeys, newValues, newSize);
                }
                return new StringHashMapStorage(newKeys, newValues, newSize, capacity, null);
            }
            idx = (idx + 1) & (capacity - 1);
        }
        return this;
    }

    @Override
    public Object[] keys() {
        revokeOwnership();
        Object[] out = new Object[size];
        int j = 0;
        for (int i = 0; i < capacity; i++) {
            String k = keys[i];
            if (k != null && k != DELETED) {
                out[j++] = k;
            }
        }
        return out;
    }

    @Override
    public Object[] values() {
        revokeOwnership();
        Object[] out = new Object[size];
        int j = 0;
        for (int i = 0; i < capacity; i++) {
            String k = keys[i];
            if (k != null && k != DELETED) {
                out[j++] = values[i];
            }
        }
        return out;
    }

    @Override
    public byte getStrategyTag() {
        return TAG_HASH;
    }

    public void forEachString(StringValueConsumer consumer) {
        for (int i = 0; i < capacity; i++) {
            String k = keys[i];
            if (k != null && k != DELETED) {
                consumer.accept(k, values[i]);
            }
        }
    }

    @FunctionalInterface
    public interface StringValueConsumer {
        void accept(String key, Object value);
    }

    private StringHashMapStorage grow() {
        int newCapacity = capacity * 2;
        String[] newKeys = new String[newCapacity];
        Object[] newValues = new Object[newCapacity];
        int newSize = 0;
        for (int i = 0; i < capacity; i++) {
            String k = keys[i];
            if (k == null || k == DELETED) continue;
            int idx = hash(k) & (newCapacity - 1);
            while (newKeys[idx] != null) idx = (idx + 1) & (newCapacity - 1);
            newKeys[idx] = k;
            newValues[idx] = values[i];
            newSize++;
        }
        Object[] token = new Object[]{null};
        var result = new StringHashMapStorage(newKeys, newValues, newSize, newCapacity, token);
        token[0] = result;
        return result;
    }

    private StringHashMapStorage shrink(String[] oldKeys, Object[] oldValues, int newSize) {
        int newCapacity = Math.max(MIN_CAPACITY, capacity / 2);
        String[] newKeys = new String[newCapacity];
        Object[] newValues = new Object[newCapacity];
        for (int i = 0; i < oldKeys.length; i++) {
            String k = oldKeys[i];
            if (k == null || k == DELETED) continue;
            int idx = hash(k) & (newCapacity - 1);
            while (newKeys[idx] != null) idx = (idx + 1) & (newCapacity - 1);
            newKeys[idx] = k;
            newValues[idx] = oldValues[i];
        }
        return new StringHashMapStorage(newKeys, newValues, newSize, newCapacity, null);
    }

    private HashMapStorage transitionToGeneric() {
        HashMapStorage out = new HashMapStorage();
        for (int i = 0; i < capacity; i++) {
            String k = keys[i];
            if (k == null || k == DELETED) continue;
            out = (HashMapStorage) out.put(k, values[i]);
        }
        return out;
    }
}
