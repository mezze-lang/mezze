package com.mezze.datastructures.set;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.ExplodeLoop;

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
    @ExplodeLoop(kind = ExplodeLoop.LoopExplosionKind.FULL_UNROLL_UNTIL_RETURN)
    // NO LONG FAST PATH HERE, and not for a performance reason: this storage
    // is UNREACHABLE for long elements. `SingleElemSetStorage.add` sends two
    // Longs to `LongSetStorage`, which keeps a primitive `long[]` and binary
    // searches it, so a long-element set never arrives here at all. A fast path
    // was added and measured before that was checked; it was dead code.
    public boolean contains(Object elem) {
        for (int i = 0; i < elements.length; i++) {
            if (Objects.equals(elements[i], elem)) {
                return true;
            }
        }
        return false;
    }

    @Override
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

        // Transition to LongTrieSetStorage if every element is a Long
        // (no sentinel collision), HashSetStorage otherwise.
        return transitionToBestHash(elem);
    }

    private SetStorage transitionToBestHash(Object newElem) {
        if (allLongElements(newElem)) {
            SetStorage out = new LongTrieSetStorage();
            for (Object e : elements) {
                out = ((LongTrieSetStorage) out).addLong((Long) e);
            }
            return out.add(newElem);
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
        // String elements likewise: the specialised table read a cached
        // String.hashCode and called String.equals directly, and the trie does
        // both — Objects.equals devirtualizes on a String receiver — so there
        // was nothing left for it to specialise.
        return transitionToHash().add(newElem);
    }

    /**
     * Being a Long is the whole condition — see the note on the Map's copy: a
     * trie reserves no sentinel values, so the predicate that used to express
     * that restriction is gone rather than left returning true.
     */
    private boolean allLongElements(Object newElem) {
        if (!(newElem instanceof Long)) return false;
        for (Object e : elements) {
            if (!(e instanceof Long)) return false;
        }
        return true;
    }

    @Override
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

    private SetStorage transitionToHash() {
        SetStorage hash = new TrieSetStorage();
        for (Object elem : elements) {
            hash = hash.add(elem);
        }
        return hash;
    }

    @Override
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
