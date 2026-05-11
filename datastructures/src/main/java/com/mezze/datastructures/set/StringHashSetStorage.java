package com.mezze.datastructures.set;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;

/**
 * Open-addressing hash set specialized for {@code String} elements.
 *
 * Sister of {@link com.mezze.datastructures.map.StringHashMapStorage}: holds
 * elements in a {@code String[]} so probes call {@link String#equals(Object)}
 * directly, devirtualized — no {@code Objects.equals} dispatch.
 *
 * <p>Sentinels: {@code null} for empty slots, a private String reference
 * for tombstones.
 */
public final class StringHashSetStorage implements SetStorage {

    private static final int MIN_CAPACITY = 16;
    /** 0.5 — see {@link com.mezze.datastructures.map.StringHashMapStorage}. */
    private static final float LOAD_FACTOR = 0.5f;

    /** Reference-identity tombstone — distinct from any user-supplied String. */
    private static final String DELETED = new String("<DELETED>");

    @CompilationFinal(dimensions = 1)
    private final String[] table;

    @CompilationFinal
    private final int size;

    @CompilationFinal
    private final int capacity;

    private final Object[] ownerToken;

    @CompilationFinal
    private boolean ownershipRevoked;

    public StringHashSetStorage() {
        this.capacity = MIN_CAPACITY;
        this.table = new String[MIN_CAPACITY];
        this.size = 0;
        this.ownerToken = new Object[]{this};
    }

    private StringHashSetStorage(String[] table, int size, int capacity, Object[] ownerToken) {
        this.table = table;
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

    public static boolean isStorable(String s) {
        return s != null;
    }

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

    public boolean containsString(String elem) {
        if (elem == null) return false;
        revokeOwnership();
        int idx = hash(elem) & (capacity - 1);
        for (int i = 0; i < capacity; i++) {
            String t = table[idx];
            if (t == null) return false;
            if (t != DELETED && (t == elem || t.equals(elem))) return true;
            idx = (idx + 1) & (capacity - 1);
        }
        return false;
    }

    @Override
    public boolean contains(Object elem) {
        return elem instanceof String s && containsString(s);
    }

    @Override
    public SetStorage add(Object elem) {
        if (elem instanceof String s) return addString(s);
        return transitionToGeneric().add(elem);
    }

    public SetStorage addString(String elem) {
        if (elem == null) {
            return transitionToGeneric().add(null);
        }
        if (containsStringInternal(elem)) return this;

        if (size >= capacity * LOAD_FACTOR) {
            return grow().addString(elem);
        }

        String[] workTable;
        Object[] token;
        if (isOwner()) {
            workTable = table;
            token = ownerToken;
        } else {
            workTable = table.clone();
            token = new Object[1];
        }

        int idx = hash(elem) & (capacity - 1);
        while (workTable[idx] != null && workTable[idx] != DELETED) {
            idx = (idx + 1) & (capacity - 1);
        }
        workTable[idx] = elem;
        var result = new StringHashSetStorage(workTable, size + 1, capacity, token);
        token[0] = result;
        return result;
    }

    private boolean containsStringInternal(String elem) {
        int idx = hash(elem) & (capacity - 1);
        for (int i = 0; i < capacity; i++) {
            String t = table[idx];
            if (t == null) return false;
            if (t != DELETED && (t == elem || t.equals(elem))) return true;
            idx = (idx + 1) & (capacity - 1);
        }
        return false;
    }

    @Override
    public SetStorage remove(Object elem) {
        if (elem instanceof String s) return removeString(s);
        return this;
    }

    public SetStorage removeString(String elem) {
        if (elem == null) return this;
        revokeOwnership();
        int idx = hash(elem) & (capacity - 1);
        for (int i = 0; i < capacity; i++) {
            String t = table[idx];
            if (t == null) return this;
            if (t != DELETED && (t == elem || t.equals(elem))) {
                int newSize = size - 1;
                if (newSize == 0) return EmptySetStorage.INSTANCE;
                String[] newTable = table.clone();
                newTable[idx] = DELETED;
                if (newSize < capacity / 4 && capacity > MIN_CAPACITY) {
                    return shrink(newTable, newSize);
                }
                return new StringHashSetStorage(newTable, newSize, capacity, null);
            }
            idx = (idx + 1) & (capacity - 1);
        }
        return this;
    }

    @Override
    public Object[] toArray() {
        revokeOwnership();
        Object[] out = new Object[size];
        int j = 0;
        for (int i = 0; i < capacity; i++) {
            String t = table[i];
            if (t != null && t != DELETED) {
                out[j++] = t;
            }
        }
        return out;
    }

    @Override
    public byte getStrategyTag() {
        return TAG_HASH;
    }

    private StringHashSetStorage grow() {
        int newCapacity = capacity * 2;
        String[] newTable = new String[newCapacity];
        int newSize = 0;
        for (int i = 0; i < capacity; i++) {
            String t = table[i];
            if (t == null || t == DELETED) continue;
            int idx = hash(t) & (newCapacity - 1);
            while (newTable[idx] != null) idx = (idx + 1) & (newCapacity - 1);
            newTable[idx] = t;
            newSize++;
        }
        Object[] token = new Object[]{null};
        var result = new StringHashSetStorage(newTable, newSize, newCapacity, token);
        token[0] = result;
        return result;
    }

    private StringHashSetStorage shrink(String[] oldTable, int newSize) {
        int newCapacity = Math.max(MIN_CAPACITY, capacity / 2);
        String[] newTable = new String[newCapacity];
        for (int i = 0; i < oldTable.length; i++) {
            String t = oldTable[i];
            if (t == null || t == DELETED) continue;
            int idx = hash(t) & (newCapacity - 1);
            while (newTable[idx] != null) idx = (idx + 1) & (newCapacity - 1);
            newTable[idx] = t;
        }
        return new StringHashSetStorage(newTable, newSize, newCapacity, null);
    }

    private HashSetStorage transitionToGeneric() {
        HashSetStorage out = new HashSetStorage();
        for (int i = 0; i < capacity; i++) {
            String t = table[i];
            if (t == null || t == DELETED) continue;
            out = (HashSetStorage) out.add(t);
        }
        return out;
    }
}
