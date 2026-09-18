package com.mezze.datastructures.map;

import com.mezze.datastructures.set.ElementHashing;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

/**
 * Open-addressing map storage that buckets by the KEY'S OWN hashing.
 *
 * The map counterpart of {@code EvidenceSetStorage}, and there for the same reason:
 * every other tier buckets by the key's runtime representation, which is exactly the
 * derived structural equality and exactly wrong for a key type whose author wrote
 * its own. `newtype P = Int` comparing mod 10 has `P.(1) == P.(11)`, and a newtype
 * is erased, so the structural tiers see the payloads `1` and `11` and keep two
 * entries where the key type says there is one.
 *
 * Keys and values are held in parallel arrays with the key's hash beside it, so a
 * probe compares ints and only re-enters Mezze when they match. Never promoted away
 * from: re-tiering because the payloads happen to be longs would restore the
 * structural bucketing this replaces.
 */
public final class EvidenceMapStorage implements MapStorage {

    private static final int MIN_CAPACITY = 8;
    private static final float LOAD_FACTOR = 0.6f;
    /** Slot is free. Distinct from a stored null key, which this tier permits. */
    private static final Object EMPTY = new Object();

    @CompilationFinal(dimensions = 1)
    private final Object[] keys;
    @CompilationFinal(dimensions = 1)
    private final Object[] values;
    @CompilationFinal(dimensions = 1)
    private final int[] hashes;
    @CompilationFinal
    private final int size;
    @CompilationFinal
    private final ElementHashing hashing;

    private EvidenceMapStorage(Object[] keys, Object[] values, int[] hashes, int size, ElementHashing hashing) {
        this.keys = keys;
        this.values = values;
        this.hashes = hashes;
        this.size = size;
        this.hashing = hashing;
    }

    /** An empty map that will bucket by [hashing]. */
    public static EvidenceMapStorage empty(ElementHashing hashing) {
        Object[] k = new Object[MIN_CAPACITY];
        java.util.Arrays.fill(k, EMPTY);
        return new EvidenceMapStorage(k, new Object[MIN_CAPACITY], new int[MIN_CAPACITY], 0, hashing);
    }

    /** The key instance this storage buckets by; never null. */
    public ElementHashing hashing() {
        return hashing;
    }

    /** Rebuild [entries] under [hashing] in one pass — `put` is persistent and
     *  copies, so folding through it would be quadratic. */
    @TruffleBoundary
    public static EvidenceMapStorage fromEntries(Object[] ks, Object[] vs, ElementHashing hashing) {
        EvidenceMapStorage result = empty(hashing);
        for (int i = 0; i < ks.length; i++) {
            result = result.putInPlaceUnsafe(ks[i], vs[i]);
        }
        return result;
    }

    @Override
    public int size() {
        return size;
    }

    /** Slot holding [key], or the first free slot if absent. */
    private int probe(Object key, int hash) {
        int mask = keys.length - 1;
        int i = hash & mask;
        while (keys[i] != EMPTY) {
            if (hashes[i] == hash && hashing.eq(keys[i], key)) {
                return i;
            }
            i = (i + 1) & mask;
        }
        return i;
    }

    @Override
    @TruffleBoundary
    public Object get(Object key) {
        int i = probe(key, hashing.hashOf(key));
        return keys[i] == EMPTY ? null : values[i];
    }

    @Override
    @TruffleBoundary
    public boolean containsKey(Object key) {
        return keys[probe(key, hashing.hashOf(key))] != EMPTY;
    }

    @Override
    @TruffleBoundary
    public MapStorage put(Object key, Object value) {
        int hash = hashing.hashOf(key);
        int i = probe(key, hash);
        if (keys[i] != EMPTY) {
            // Replacing a value keeps the key that is already there — it is the
            // equal one by the key type's own instance, which is the whole point.
            Object[] vs = values.clone();
            vs[i] = value;
            return new EvidenceMapStorage(keys, vs, hashes, size, hashing);
        }
        return copy(size + 1).putInPlaceUnsafe(key, value);
    }

    @Override
    @TruffleBoundary
    public MapStorage remove(Object key) {
        int hash = hashing.hashOf(key);
        if (keys[probe(key, hash)] == EMPTY) {
            return this;
        }
        // Rebuild rather than tombstone: removal is rare on this tier and a clean
        // table keeps `probe` free of deleted-slot handling.
        Object[] ks = new Object[size - 1];
        Object[] vs = new Object[size - 1];
        int n = 0;
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] != EMPTY && !(hashes[i] == hash && hashing.eq(keys[i], key))) {
                ks[n] = keys[i];
                vs[n] = values[i];
                n++;
            }
        }
        return fromEntries(ks, vs, hashing);
    }

    @Override
    public Object[] keys() {
        Object[] out = new Object[size];
        int n = 0;
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] != EMPTY) out[n++] = keys[i];
        }
        return out;
    }

    @Override
    public Object[] values() {
        Object[] out = new Object[size];
        int n = 0;
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] != EMPTY) out[n++] = values[i];
        }
        return out;
    }

    @Override
    public byte getStrategyTag() {
        return TAG_HASH;
    }

    /** A copy with room for [needed] entries. Callers own the result. */
    private EvidenceMapStorage copy(int needed) {
        int capacity = keys.length;
        while (needed > capacity * LOAD_FACTOR) {
            capacity <<= 1;
        }
        Object[] k = new Object[capacity];
        java.util.Arrays.fill(k, EMPTY);
        EvidenceMapStorage acc =
            new EvidenceMapStorage(k, new Object[capacity], new int[capacity], 0, hashing);
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] != EMPTY) {
                acc = acc.putKnownAbsent(keys[i], values[i], hashes[i]);
            }
        }
        return acc;
    }

    /** Insert into a table this call owns, growing if the load factor demands it. */
    private EvidenceMapStorage putInPlaceUnsafe(Object key, Object value) {
        int hash = hashing.hashOf(key);
        int i = probe(key, hash);
        if (keys[i] != EMPTY) {
            values[i] = value;
            return this;
        }
        EvidenceMapStorage target = (size + 1 > keys.length * LOAD_FACTOR) ? copy(size + 1) : this;
        return target.putKnownAbsent(key, value, hash);
    }

    /** Write an entry known to be absent; returns a storage sharing the tables. */
    private EvidenceMapStorage putKnownAbsent(Object key, Object value, int hash) {
        int mask = keys.length - 1;
        int i = hash & mask;
        while (keys[i] != EMPTY) {
            i = (i + 1) & mask;
        }
        keys[i] = key;
        values[i] = value;
        hashes[i] = hash;
        return new EvidenceMapStorage(keys, values, hashes, size + 1, hashing);
    }
}
