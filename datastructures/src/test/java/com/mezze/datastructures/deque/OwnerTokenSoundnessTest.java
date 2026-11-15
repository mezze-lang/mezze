package com.mezze.datastructures.deque;

import com.mezze.datastructures.deque.MezzeDeque;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The owner-token structures, probed for the defect BUG 38 was: a derivation
 * observable in the value it was derived FROM.
 *
 * <p>The Map and Set had it and were rewritten as persistent tries.
 * {@code MezzeDeque} kept the token, and these five cases are why it may:
 *
 * <p><b>THE RULE, and it decides the question for any of these structures:
 * whether the structure's reads are bounded AWAY from the slot being
 * written.</b> A deque's reads are bounded by an explicit {@code [head, tail)}
 * window. {@code pushBack} writes at {@code tail} and {@code pushFront} at
 * {@code head-1} — both OUTSIDE the base's window, which the base cannot
 * address. Pops write nothing at all: they narrow the window, share the buffer
 * and revoke ownership. So a derivation is invisible, and the tests below hold.
 *
 * <p>The same rule made the hash map's in-place insert unsafe while a sorted
 * array's append was safe.
 *
 * <p>MEZZEBITSET FAILED IT AND WAS DELETED (BUG 39, 2026-11-15). A bitset has no
 * window: {@code contains(i)} reads {@code words[i >> 6]} for ANY {@code i}, so
 * there is nowhere unobservable to write, and {@code add}'s in-place
 * {@code words[w] |= mask} was visible in the base — which also disagreed with
 * its own per-instance {@code cardinality}. It was reachable from no Mezze
 * program (zero natives, zero stdlib mentions), the cheap fix of always
 * allocating OOM'd the benchmarks, and the real fix was a persistent word trie,
 * so it was removed rather than rebuilt for nothing. Recoverable from git if a
 * fast {@code Set Int} representation is ever wanted; the rule above is the part
 * worth keeping.
 *
 * <p>Reasoning of exactly this kind was wrong twice on the day this was written,
 * which is why it sits above tests rather than instead of them.
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
}
