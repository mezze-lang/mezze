package com.mezze.datastructures.map;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.ExplodeLoop;

import java.util.Arrays;
import java.util.Objects;

/**
 * Flat array storage for small maps (≤8 entries).
 * Uses linear search which is faster than hashing for small sizes due to cache locality.
 */
public final class SmallFlatMapStorage implements MapStorage {

    public static final int MAX_SIZE = 8;

    @CompilationFinal(dimensions = 1)
    private final Object[] keys;

    @CompilationFinal(dimensions = 1)
    private final Object[] values;

    public SmallFlatMapStorage(Object[] keys, Object[] values) {
        this.keys = keys;
        this.values = values;
    }

    @Override
    public int size() {
        return keys.length;
    }

    @Override
    @ExplodeLoop(kind = ExplodeLoop.LoopExplosionKind.FULL_UNROLL_UNTIL_RETURN)
    public Object get(Object searchKey) {
        for (int i = 0; i < keys.length; i++) {
            if (Objects.equals(keys[i], searchKey)) {
                return values[i];
            }
        }
        return null;
    }

    @Override
    @ExplodeLoop(kind = ExplodeLoop.LoopExplosionKind.FULL_UNROLL_UNTIL_RETURN)
    public boolean containsKey(Object searchKey) {
        for (int i = 0; i < keys.length; i++) {
            if (Objects.equals(keys[i], searchKey)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public MapStorage put(Object newKey, Object newValue) {
        // Check if key exists
        for (int i = 0; i < keys.length; i++) {
            if (Objects.equals(keys[i], newKey)) {
                // Update existing
                Object[] newValues = values.clone();
                newValues[i] = newValue;
                return new SmallFlatMapStorage(keys, newValues);
            }
        }

        // Add new entry
        if (keys.length < MAX_SIZE) {
            Object[] newKeys = Arrays.copyOf(keys, keys.length + 1);
            Object[] newValues = Arrays.copyOf(values, values.length + 1);
            newKeys[keys.length] = newKey;
            newValues[values.length] = newValue;
            return new SmallFlatMapStorage(newKeys, newValues);
        }

        // Transition to LongTrieMapStorage if every key is a Long,
        // HashMapStorage otherwise. The Long-specialized storage skips boxing
        // and Objects.equals on every probe.
        return transitionToBestHash(newKey, newValue);
    }

    private MapStorage transitionToBestHash(Object newKey, Object newValue) {
        if (allLongKeysStorable(newKey)) {
            MapStorage out = new LongTrieMapStorage();
            for (int i = 0; i < keys.length; i++) {
                out = ((LongTrieMapStorage) out).putLong((Long) keys[i], values[i]);
            }
            return out.put(newKey, newValue);
        }
        // DOUBLE KEYS GO TO THE GENERAL TRIE TOO. The specialised table stored
        // them as raw bits in a long[], which the trie gives up — a Double is
        // boxed here. That is deliberate: the table carried BUG 38 and the only
        // fix is structural sharing, so the choice was a Double-specialised
        // trie or this. Semantics are identical either way, because
        // Double.equals and doubleToLongBits agree on the two cases that could
        // differ (both fold every NaN together, both keep -0.0 apart from 0.0),
        // and Float-keyed maps are rare enough that one more class was not
        // worth carrying for them.
        // String keys go to the general trie as well. The String-specialised
        // TABLE existed to read a cached String.hashCode and call
        // String.equals directly; the trie reads the same cached hashCode and
        // Objects.equals devirtualizes to String.equals on a String receiver,
        // so there is nothing left for a separate storage to specialise — and
        // the table carried BUG 38.
        return transitionToHash().put(newKey, newValue);
    }

    /**
     * Every Long key qualifies now. The open-addressed table this used to
     * promote to reserved two values as empty/deleted sentinels, so a map whose
     * keys included {@code Long.MIN_VALUE} had to go to boxed storage instead;
     * a trie stores no sentinels, so {@code isStorable} is unconditionally true
     * and that promotion is gone.
     */
    private boolean allLongKeysStorable(Object newKey) {
        if (!(newKey instanceof Long nk) || !LongTrieMapStorage.isStorable(nk)) return false;
        for (Object k : keys) {
            if (!(k instanceof Long lk) || !LongTrieMapStorage.isStorable(lk)) return false;
        }
        return true;
    }

    private boolean allDoubleKeys(Object newKey) {
        if (!(newKey instanceof Double)) return false;
        for (Object k : keys) {
            if (!(k instanceof Double)) return false;
        }
        return true;
    }

    private boolean allStringKeys(Object newKey) {
        if (!(newKey instanceof String)) return false;
        for (Object k : keys) {
            if (!(k instanceof String)) return false;
        }
        return true;
    }

    @Override
    public MapStorage remove(Object removeKey) {
        int idx = -1;
        for (int i = 0; i < keys.length; i++) {
            if (Objects.equals(keys[i], removeKey)) {
                idx = i;
                break;
            }
        }

        if (idx < 0) {
            return this;
        }

        if (keys.length == 1) {
            return EmptyMapStorage.INSTANCE;
        }

        if (keys.length == 2) {
            int otherIdx = idx == 0 ? 1 : 0;
            return new SingleEntryMapStorage(keys[otherIdx], values[otherIdx]);
        }

        Object[] newKeys = new Object[keys.length - 1];
        Object[] newValues = new Object[values.length - 1];
        System.arraycopy(keys, 0, newKeys, 0, idx);
        System.arraycopy(keys, idx + 1, newKeys, idx, keys.length - idx - 1);
        System.arraycopy(values, 0, newValues, 0, idx);
        System.arraycopy(values, idx + 1, newValues, idx, values.length - idx - 1);

        return new SmallFlatMapStorage(newKeys, newValues);
    }

    @Override
    public Object[] keys() {
        return keys.clone();
    }

    @Override
    public Object[] values() {
        return values.clone();
    }

    @Override
    public byte getStrategyTag() {
        return TAG_SMALL_FLAT;
    }

    /**
     * Returns the key at index (for iteration).
     */
    public Object getKeyAt(int index) {
        return keys[index];
    }

    /**
     * Returns the value at index (for iteration).
     */
    public Object getValueAt(int index) {
        return values[index];
    }

    private MapStorage transitionToHash() {
        MapStorage hash = new TrieMapStorage();
        for (int i = 0; i < keys.length; i++) {
            hash = hash.put(keys[i], values[i]);
        }
        return hash;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("SmallFlatMapStorage{");
        for (int i = 0; i < keys.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(keys[i]).append("=").append(values[i]);
        }
        sb.append("}");
        return sb.toString();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof SmallFlatMapStorage other)) return false;
        if (keys.length != other.keys.length) return false;

        for (int i = 0; i < keys.length; i++) {
            Object v = other.get(keys[i]);
            if (!Objects.equals(values[i], v)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = 0;
        for (int i = 0; i < keys.length; i++) {
            result += Objects.hashCode(keys[i]) ^ Objects.hashCode(values[i]);
        }
        return result;
    }
}
