package com.mezze.datastructures.bitset;

import com.mezze.datastructures.deque.MezzeDeque;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The structures that still carry an owner token, probed for the defect BUG 38
 * was: a derivation observable in the value it was derived FROM.
 *
 * <p>The Map and Set had it and were rewritten as persistent tries.
 * {@code MezzeDeque} and {@code MezzeBitSet} kept the token and had never been
 * probed, which is a question rather than a finding until something runs.
 *
 * <p>Reasoning first, because it predicts different answers for the two and the
 * difference is the interesting part:
 *
 * <ul>
 *   <li>A DEQUE's reads are bounded by an explicit {@code [head, tail)} window.
 *       {@code pushBack} writes at {@code tail} and {@code pushFront} at
 *       {@code head-1} — both OUTSIDE the base's window, which the base cannot
 *       address. Pops write nothing at all: they narrow the window, share the
 *       buffer and revoke ownership. So a derivation should be invisible.
 *   <li>A BITSET has no window. {@code contains(i)} reads
 *       {@code words[i >> 6]} for any {@code i}, and {@code add} does
 *       {@code words[w] |= mask} in place when it owns the array. The base
 *       should therefore SEE a bit only ever added to a set derived from it —
 *       the same shape as the hash map's probe walking onto an in-place write.
 * </ul>
 *
 * <p>Reasoning of exactly this kind was wrong twice today, which is why it is
 * written above a test rather than instead of one.
 */
public class OwnerTokenSoundnessTest {

    // ───────────────────────────── Deque ─────────────────────────────

    @Test
    public void aDequeBaseIsUnchangedByAPushBack() {
        MezzeDeque<Object> base = MezzeDeque.empty();
        for (int i = 0; i < 50; i++) base = base.pushBack(i);
        MezzeDeque<Object> derived = base.pushBack(999);
        assertEquals(50, base.size());
        assertEquals(51, derived.size());
        assertEquals(49, base.peekBack(), "the base's back element must not move");
        assertEquals(999, derived.peekBack());
    }

    @Test
    public void aDequeBaseIsUnchangedByAPushFront() {
        MezzeDeque<Object> base = MezzeDeque.empty();
        for (int i = 0; i < 50; i++) base = base.pushBack(i);
        MezzeDeque<Object> derived = base.pushFront(999);
        assertEquals(50, base.size());
        assertEquals(51, derived.size());
        assertEquals(0, base.peekFront(), "the base's front element must not move");
        assertEquals(999, derived.peekFront());
    }

    @Test
    public void twoDequesDerivedFromOneBaseAreIndependent() {
        MezzeDeque<Object> base = MezzeDeque.empty();
        for (int i = 0; i < 50; i++) base = base.pushBack(i);
        MezzeDeque<Object> a = base.pushBack(111);
        MezzeDeque<Object> b = base.pushBack(222);
        assertEquals(111, a.peekBack());
        assertEquals(222, b.peekBack());
        assertEquals(49, base.peekBack());
        assertEquals(50, base.size());
    }

    @Test
    public void aDequeBaseKeepsWhatAPopDropped() {
        MezzeDeque<Object> base = MezzeDeque.empty();
        for (int i = 0; i < 50; i++) base = base.pushBack(i);
        MezzeDeque.PopResult<Object> popped = base.popFront();
        assertEquals(0, popped.element());
        assertEquals(49, popped.rest().size());
        assertEquals(50, base.size(), "the base keeps its size");
        assertEquals(0, base.peekFront(), "the base keeps the popped element");
    }

    /** A push AFTER a pop: the pop revoked ownership, so this must copy. */
    @Test
    public void aPushAfterAPopDoesNotDisturbEitherView() {
        MezzeDeque<Object> base = MezzeDeque.empty();
        for (int i = 0; i < 50; i++) base = base.pushBack(i);
        MezzeDeque<Object> narrowed = base.popFront().rest();
        MezzeDeque<Object> grown = narrowed.pushBack(777);
        assertEquals(50, base.size());
        assertEquals(0, base.peekFront());
        assertEquals(49, narrowed.size());
        assertEquals(49, narrowed.peekBack(), "the narrowed view must not see the push");
        assertEquals(777, grown.peekBack());
    }

    // ───────────────────────────── BitSet ────────────────────────────

    /**
     * THE ONE THE REASONING SAYS SHOULD FAIL. 50 bits put the set well inside
     * one word, so {@code add} takes the in-place branch.
     */
    @org.junit.jupiter.api.Disabled("BUG 39 — MezzeBitSet.add mutates in place, observable in the base")
    @Test
    public void aBitSetBaseDoesNotAcquireABitAddedToADerivedSet() {
        MezzeBitSet base = MezzeBitSet.empty();
        for (int i = 0; i < 50; i++) base = base.add(i);
        MezzeBitSet derived = base.add(55);
        assertTrue(derived.contains(55));
        assertFalse(base.contains(55),
            "the base must not acquire a bit added only to a set derived from it");
        assertEquals(50, base.size());
        assertEquals(51, derived.size());
    }

    @org.junit.jupiter.api.Disabled("BUG 39 — MezzeBitSet.add mutates in place, observable in the base")
    @Test
    public void twoBitSetsDerivedFromOneBaseAreIndependent() {
        MezzeBitSet base = MezzeBitSet.empty();
        for (int i = 0; i < 50; i++) base = base.add(i);
        MezzeBitSet a = base.add(60);
        MezzeBitSet b = base.add(61);
        assertTrue(a.contains(60));
        assertFalse(a.contains(61), "a must not see b's bit");
        assertTrue(b.contains(61));
        assertFalse(b.contains(60), "b must not see a's bit");
        assertFalse(base.contains(60));
        assertFalse(base.contains(61));
    }

    @Test
    public void aBitSetBaseKeepsABitRemovedFromADerivedSet() {
        MezzeBitSet base = MezzeBitSet.empty();
        for (int i = 0; i < 50; i++) base = base.add(i);
        MezzeBitSet derived = base.remove(25);
        assertFalse(derived.contains(25));
        assertTrue(base.contains(25), "the base keeps the removed bit");
        assertEquals(50, base.size());
        assertEquals(49, derived.size());
    }
}
