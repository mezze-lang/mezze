package com.mezze.datastructures.set;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link LongTrieSetStorage#symmetricDifferenceWith} against the answer.
 *
 * <p>HARDER THAN THE UNION, AND THE TESTS REFLECT WHERE. A union only grows:
 * every position contributes something and the result is at least as big as
 * either input. A symmetric difference CANCELS, which introduces two failure
 * modes the union cannot have:
 *
 * <ul>
 *   <li><b>Empty subtrees.</b> A position can produce nothing — two equal
 *       elements annihilate, and a recursion can come back empty. Recording a
 *       bit for an empty child leaves a node claiming a subtree that holds
 *       nothing, which no membership test would notice but which makes `size`
 *       and the structure disagree. {@code identicalSetsCancelEntirely} and
 *       {@code nearlyIdenticalLeavesOne} are the cases that force it.</li>
 *   <li><b>The toggle.</b> Where one side has a single element and the other a
 *       whole subtree, the element is REMOVED if the subtree had it and ADDED if
 *       not. Getting that backwards gives a result that is wrong by exactly two
 *       elements and still plausible. The random cases and
 *       {@code sparseAgainstDense} hold it down.</li>
 * </ul>
 *
 * <p>Compared against {@link HashSet}, not another MezzeSet path. Size is
 * checked separately from membership because it is counted while the result is
 * built, and a size that disagrees with the contents is the signature of a
 * mis-pruned node.
 */
class LongTrieSymDiffTest {

    private static LongTrieSetStorage trie(Iterable<Long> elems) {
        SetStorage s = new LongTrieSetStorage();
        for (long e : elems) s = ((LongTrieSetStorage) s).addLong(e);
        return (LongTrieSetStorage) s;
    }

    private static Set<Long> range(long from, long toExclusive) {
        Set<Long> s = new HashSet<>();
        for (long i = from; i < toExclusive; i++) s.add(i);
        return s;
    }

    private static void check(Set<Long> left, Set<Long> right, String what) {
        LongTrieSetStorage a = trie(left);
        LongTrieSetStorage b = trie(right);

        Set<Long> expected = new HashSet<>(left);
        for (long e : right) {
            if (!expected.remove(e)) expected.add(e);
        }

        LongTrieSetStorage out = LongTrieSetStorage.symmetricDifferenceWith(a, b);

        assertEquals(expected.size(), out.size(),
            what + ": size disagrees with the contents — the signature of a"
                + " node recorded for an empty subtree");
        for (long e : expected) {
            assertTrue(out.contains(e), what + ": missing " + e);
        }
        // Everything SHARED must be gone. A symmetric difference that forgot to
        // cancel would still contain all of these and pass every test above if
        // the size happened to agree.
        for (long e : left) {
            if (right.contains(e)) {
                assertFalse(out.contains(e),
                    what + ": " + e + " is in both sides and must have cancelled");
            }
        }
        // The inputs are persistent and their subtrees are shared.
        assertEquals(left.size(), a.size(), what + ": left input changed");
        assertEquals(right.size(), b.size(), what + ": right input changed");
        for (long e : left) assertTrue(a.contains(e), what + ": left lost " + e);
        for (long e : right) assertTrue(b.contains(e), what + ": right lost " + e);
    }

    /** Disjoint: the answer is the union, and nothing cancels. */
    @Test
    void disjointIsTheUnion() {
        check(range(0, 500), range(500, 1000), "disjoint");
    }

    /**
     * IDENTICAL SETS CANCEL TO NOTHING. Every element annihilates, every
     * recursion comes back empty, and the result must be empty rather than a
     * trie of empty nodes claiming a thousand elements.
     */
    @Test
    void identicalSetsCancelEntirely() {
        LongTrieSetStorage a = trie(range(0, 500));
        LongTrieSetStorage out = LongTrieSetStorage.symmetricDifferenceWith(a, a);
        assertEquals(0, out.size(), "identical sets must cancel to empty");
        for (long e = 0; e < 500; e++) {
            assertFalse(out.contains(e), "cancelled element " + e + " survived");
        }
        // And via two distinct but equal tries, which does not take the
        // `a == b` short-circuit and so exercises the whole walk.
        check(range(0, 500), range(0, 500), "equal but distinct tries");
    }

    /**
     * ALL BUT ONE CANCELS — the case that leaves a single element behind after
     * most of the trie has pruned itself away. A mis-pruned node shows up here
     * as a size that is not 1.
     */
    @Test
    void nearlyIdenticalLeavesOne() {
        Set<Long> left = range(0, 600);
        Set<Long> right = range(0, 600);
        right.remove(317L);
        check(left, right, "all but one cancels");

        Set<Long> plusOne = range(0, 600);
        plusOne.add(99999L);
        check(left, plusOne, "one extra on the right");
    }

    @Test
    void halfOverlapping() {
        check(range(0, 600), range(300, 900), "half overlapping");
    }

    /** One side a subset: the answer is exactly the rest of the larger side. */
    @Test
    void subset() {
        check(range(0, 800), range(100, 200), "right is a subset");
    }

    /**
     * SPARSE AGAINST DENSE, which is where the TOGGLE lives: one side has a
     * single element at a bit position where the other has a whole subtree.
     * Checked both with overlap (the element must cancel out of the subtree)
     * and without (it must join it).
     */
    @Test
    void sparseAgainstDense() {
        check(range(0, 60), range(0, 900), "sparse left, overlapping");
        check(range(0, 20), range(500, 1400), "sparse left, disjoint");
        check(range(0, 900), range(0, 60), "dense left, overlapping");
    }

    @Test
    void tinySets() {
        check(range(0, 3), range(2, 5), "tiny");
        check(range(0, 1), range(0, 1), "one element, cancels");
    }

    @Test
    void emptySides() {
        check(range(0, 40), new HashSet<>(), "empty right");
        check(new HashSet<>(), range(0, 40), "empty left");
        check(new HashSet<>(), new HashSet<>(), "both empty");
    }

    @Test
    void negativeAndExtremeValues() {
        Set<Long> left = new HashSet<>();
        Set<Long> right = new HashSet<>();
        for (long e : new long[] {0L, -1L, 1L, Long.MIN_VALUE, Long.MAX_VALUE, -2L}) {
            left.add(e);
        }
        for (long e : new long[] {-1L, 3L, Long.MIN_VALUE, 1000000L}) {
            right.add(e);
        }
        check(left, right, "extremes");
    }

    /**
     * RANDOMISED with a deliberate shared slice, so cancellation, toggling and
     * subtree sharing all occur at densities tidy ranges do not reach.
     */
    @Test
    void randomSparseSets() {
        for (long seed : new long[] {1L, 42L, 1337L, 161803L, 2718281828L}) {
            Random r = new Random(seed);
            Set<Long> left = new HashSet<>();
            Set<Long> right = new HashSet<>();
            for (int i = 0; i < 400; i++) {
                left.add(r.nextLong() % 100000);
                right.add(r.nextLong() % 100000);
            }
            int shared = 0;
            for (long e : left) {
                if (shared++ > 120) break;
                right.add(e);               // these must all cancel
            }
            check(left, right, "random seed " + seed);
        }
    }

    /**
     * SYMMETRY, which this operation genuinely has and the map merge does not:
     * `a ^ b` and `b ^ a` must agree exactly, although the four per-bit cases
     * are not symmetric in A and B and go down different branches.
     */
    @Test
    void commutes() {
        Set<Long> x = range(0, 400), y = range(250, 700);
        LongTrieSetStorage a = trie(x), b = trie(y);
        LongTrieSetStorage ab = LongTrieSetStorage.symmetricDifferenceWith(a, b);
        LongTrieSetStorage ba = LongTrieSetStorage.symmetricDifferenceWith(b, a);
        assertEquals(ab.size(), ba.size(), "a^b and b^a differ in size");
        for (long e = 0; e < 700; e++) {
            assertEquals(ab.contains(e), ba.contains(e), "a^b and b^a differ at " + e);
        }
    }
}
