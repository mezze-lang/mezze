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

    /**
     * A LONG KEY IS COMPARED AS A LONG, not through {@code Objects.equals}.
     *
     * <p>MEASURED on `bench/cases/mapsurface`, n=1e7, probe cost against the
     * key count — and the curve had an inversion in it:
     *
     * <pre>
     *   keys  storage           contains_key   get
     *      2  SmallFlat            4 131 ps   5 239
     *      4  SmallFlat            5 714      6 718
     *      7  SmallFlat            8 710      9 929
     *      8  SmallFlat (full)     9 876     10 701
     *      9  long trie            6 005      6 992
     *     16  long trie            5 986      6 914
     * </pre>
     *
     * The flat scan was ~820 ps PER ENTRY and the trie is flat at ~6 000, so an
     * 8-key map probed 1.65x slower than the 9-key map it promotes to — the
     * crossover sat at four or five keys, not at {@link #MAX_SIZE}.
     *
     * <p>820 ps is far too much for comparing two numbers. {@code keys} is an
     * {@code Object[]}, so {@code Objects.equals(keys[i], searchKey)} is a
     * VIRTUAL {@code equals} on a receiver whose type varies across the
     * process: megamorphic, once per entry, eight times per probe. Testing the
     * search key once and then comparing primitives removes the call entirely —
     * the same move that took `MezzeSeq.size` 3.29x and `seq::get` 1.21x.
     *
     * <p>The general path is kept verbatim underneath for every other key type,
     * so this narrows nothing: a Str-keyed or Double-keyed small map behaves
     * exactly as before.
     */
    @Override
    @ExplodeLoop(kind = ExplodeLoop.LoopExplosionKind.FULL_UNROLL_UNTIL_RETURN)
    public Object get(Object searchKey) {
        if (searchKey instanceof Long sk) {
            long s = sk;
            for (int i = 0; i < keys.length; i++) {
                if (keys[i] instanceof Long k && k.longValue() == s) {
                    return values[i];
                }
            }
            return null;
        }
        for (int i = 0; i < keys.length; i++) {
            if (Objects.equals(keys[i], searchKey)) {
                return values[i];
            }
        }
        return null;
    }

    /** The Bool peer of {@link #get}; see its comment for the measurement. */
    @Override
    @ExplodeLoop(kind = ExplodeLoop.LoopExplosionKind.FULL_UNROLL_UNTIL_RETURN)
    public boolean containsKey(Object searchKey) {
        if (searchKey instanceof Long sk) {
            long s = sk;
            for (int i = 0; i < keys.length; i++) {
                if (keys[i] instanceof Long k && k.longValue() == s) {
                    return true;
                }
            }
            return false;
        }
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

        // Every key a Long -> the long-keyed trie, which stores keys unboxed
        // and compares them with ==; anything else -> the general trie.
        return transitionToBestHash(newKey, newValue);
    }

    private MapStorage transitionToBestHash(Object newKey, Object newValue) {
        if (allLongKeys(newKey)) {
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
     * Being a Long is the whole condition. The table this used to promote to
     * reserved {@code Long.MIN_VALUE} and {@code MIN_VALUE+1} as empty/deleted
     * sentinels, so a map using one as a key had to go to boxed storage
     * instead; a trie stores no sentinels, and the extra predicate that used to
     * express that is gone rather than left returning true.
     */
    private boolean allLongKeys(Object newKey) {
        if (!(newKey instanceof Long)) return false;
        for (Object k : keys) {
            if (!(k instanceof Long)) return false;
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
