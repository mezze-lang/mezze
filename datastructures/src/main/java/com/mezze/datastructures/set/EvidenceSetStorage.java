package com.mezze.datastructures.set;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

/**
 * Open-addressing set storage that buckets by the ELEMENT'S OWN hashing.
 *
 * <p>Every other tier buckets by the runtime representation — {@code Objects.hashCode}
 * on a Long, a String, a record. That is exactly right when the element's equality is
 * the derived structural one, which is the common case and stays on the fast tiers.
 * It is wrong when the element wrote its own: a newtype whose `Eq` compares mod 10
 * has `P.(1) == P.(11)`, and both are the payload `1` and `11` at runtime, so the
 * structural tiers put them in different buckets and keep both.
 *
 * <p>This tier is the specialised one, chosen only for an element type that HAS a
 * hand-written instance. It stores the element's hash alongside the element so a
 * probe compares ints first and only calls back into Mezze when they match — the
 * closure call is a Truffle boundary, so not making it is worth an int array.
 *
 * <p>Deliberately NOT part of the tier-promotion ladder: a set with evidence keeps it
 * for life. Promoting to {@link LongSetStorage} because the payloads happen to be
 * longs would silently restore the structural bucketing this exists to replace.
 */
public final class EvidenceSetStorage implements SetStorage {

    private static final int MIN_CAPACITY = 8;
    private static final float LOAD_FACTOR = 0.6f;
    /** Slot is free. Distinct from a stored null element, which this tier permits. */
    private static final Object EMPTY = new Object();

    @CompilationFinal(dimensions = 1)
    private final Object[] table;
    /** The element hash per slot, so probing does not re-enter Mezze. */
    @CompilationFinal(dimensions = 1)
    private final int[] hashes;
    @CompilationFinal
    private final int size;
    @CompilationFinal
    private final ElementHashing hashing;

    private EvidenceSetStorage(Object[] table, int[] hashes, int size, ElementHashing hashing) {
        this.table = table;
        this.hashes = hashes;
        this.size = size;
        this.hashing = hashing;
    }

    /** An empty set that will bucket by [hashing]. */
    public static EvidenceSetStorage empty(ElementHashing hashing) {
        Object[] t = new Object[MIN_CAPACITY];
        java.util.Arrays.fill(t, EMPTY);
        return new EvidenceSetStorage(t, new int[MIN_CAPACITY], 0, hashing);
    }

    /** The element instance this storage buckets by; never null. */
    public ElementHashing hashing() {
        return hashing;
    }

    /**
     * Build in one pass. `add` is persistent and copies, so folding a sequence
     * through it would be quadratic; bulk construction goes through here.
     */
    @TruffleBoundary
    public static EvidenceSetStorage fromElements(Object[] elements, ElementHashing hashing) {
        EvidenceSetStorage result = empty(hashing);
        for (Object e : elements) {
            result = result.addInPlaceUnsafe(e);
        }
        return result;
    }

    @Override
    public int size() {
        return size;
    }

    /** Slot holding [elem], or the first free slot if absent. */
    private int probe(Object elem, int hash) {
        int mask = table.length - 1;
        int i = hash & mask;
        while (table[i] != EMPTY) {
            if (hashes[i] == hash && hashing.eq(table[i], elem)) {
                return i;
            }
            i = (i + 1) & mask;
        }
        return i;
    }

    @Override
    @TruffleBoundary
    public boolean contains(Object elem) {
        int hash = hashing.hashOf(elem);
        return table[probe(elem, hash)] != EMPTY;
    }

    @Override
    @TruffleBoundary
    public SetStorage add(Object elem) {
        int hash = hashing.hashOf(elem);
        if (table[probe(elem, hash)] != EMPTY) {
            return this;
        }
        return copy(size + 1).addInPlaceUnsafe(elem);
    }

    @Override
    @TruffleBoundary
    public SetStorage remove(Object elem) {
        int hash = hashing.hashOf(elem);
        if (table[probe(elem, hash)] == EMPTY) {
            return this;
        }
        // Rebuild rather than tombstone: removal is rare on this tier and a clean
        // table keeps `probe` free of deleted-slot handling.
        Object[] kept = new Object[size - 1];
        int n = 0;
        for (int i = 0; i < table.length; i++) {
            if (table[i] != EMPTY && !(hashes[i] == hash && hashing.eq(table[i], elem))) {
                kept[n++] = table[i];
            }
        }
        return fromElements(kept, hashing);
    }

    @Override
    public Object[] toArray() {
        Object[] out = new Object[size];
        int n = 0;
        for (Object o : table) {
            if (o != EMPTY) {
                out[n++] = o;
            }
        }
        return out;
    }

    @Override
    public byte getStrategyTag() {
        return TAG_HASH;
    }

    /** A copy with room for [needed] elements. Callers own the result. */
    private EvidenceSetStorage copy(int needed) {
        int capacity = table.length;
        while (needed > capacity * LOAD_FACTOR) {
            capacity <<= 1;
        }
        Object[] t = new Object[capacity];
        java.util.Arrays.fill(t, EMPTY);
        EvidenceSetStorage fresh = new EvidenceSetStorage(t, new int[capacity], 0, hashing);
        EvidenceSetStorage acc = fresh;
        for (int i = 0; i < table.length; i++) {
            if (table[i] != EMPTY) {
                acc = acc.putKnownAbsent(table[i], hashes[i]);
            }
        }
        return acc;
    }

    /** Insert into a table this call owns, growing if the load factor demands it. */
    private EvidenceSetStorage addInPlaceUnsafe(Object elem) {
        int hash = hashing.hashOf(elem);
        if (table[probe(elem, hash)] != EMPTY) {
            return this;
        }
        EvidenceSetStorage target = (size + 1 > table.length * LOAD_FACTOR) ? copy(size + 1) : this;
        return target.putKnownAbsent(elem, hash);
    }

    /** Write an element known to be absent; returns a storage sharing the table. */
    private EvidenceSetStorage putKnownAbsent(Object elem, int hash) {
        int mask = table.length - 1;
        int i = hash & mask;
        while (table[i] != EMPTY) {
            i = (i + 1) & mask;
        }
        table[i] = elem;
        hashes[i] = hash;
        return new EvidenceSetStorage(table, hashes, size + 1, hashing);
    }
}
