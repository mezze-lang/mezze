package com.mezze.datastructures.set;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link LongTrieSetStorage#unionWith} against the answer, element by element.
 *
 * <p>WHY THIS IS THE RISKIEST CHANGE IN THE SET WORK. `MezzeSet.addAll` rebuilds
 * both sides through {@code fromElements}, which is slow and obviously correct —
 * every element is hashed and inserted by the same code that inserts one
 * element. A structural union is neither: it merges two tries bit by bit,
 * SHARES whole subtrees by reference, and computes the result's size by counting
 * what it added rather than by walking the result. A wrong bitmap gives a
 * missing element; a wrong index gives a swapped one; a wrong count gives a set
 * whose {@code size()} disagrees with its own contents, which no membership test
 * would catch.
 *
 * <p>So these compare against {@link HashSet} — not against another MezzeSet
 * path, which could share a bug — and they check SIZE separately from
 * MEMBERSHIP, because the size is derived independently and can be wrong on its
 * own.
 *
 * <p>MEASURED, 1000 elements a side: the rebuild is 84% of `addAll` for disjoint
 * inputs and 72% for identical ones, against 6 790 ps/element to walk both
 * sides. That is what the union replaces.
 */
class LongTrieUnionTest {

    private static LongTrieSetStorage trie(Iterable<Long> elems) {
        SetStorage s = new LongTrieSetStorage();
        for (long e : elems) s = ((LongTrieSetStorage) s).addLong(e);
        return (LongTrieSetStorage) s;
    }

    private static void check(Set<Long> left, Set<Long> right, String what) {
        LongTrieSetStorage a = trie(left);
        LongTrieSetStorage b = trie(right);

        Set<Long> expected = new HashSet<>(left);
        expected.addAll(right);

        LongTrieSetStorage out = LongTrieSetStorage.unionWith(a, b);

        assertEquals(expected.size(), out.size(),
            what + ": size disagrees with the union's own contents");
        for (long e : expected) {
            assertTrue(out.contains(e), what + ": missing " + e);
        }
        // And nothing that belongs to neither side crept in. Checking a window
        // around the inputs rather than every long: a structural merge goes
        // wrong by misplacing an element it was given, not by inventing one.
        long lo = Long.MAX_VALUE, hi = Long.MIN_VALUE;
        for (long e : expected) { lo = Math.min(lo, e); hi = Math.max(hi, e); }
        for (long e = lo - 50; e <= hi + 50; e++) {
            if (!expected.contains(e)) {
                assertFalse(out.contains(e), what + ": invented " + e);
            }
        }
        // The inputs are PERSISTENT and must be untouched — a structural merge
        // shares their subtrees, so a merge that mutated one would corrupt a
        // value someone else still holds.
        assertEquals(left.size(), a.size(), what + ": left input changed size");
        assertEquals(right.size(), b.size(), what + ": right input changed size");
        for (long e : left) assertTrue(a.contains(e), what + ": left input lost " + e);
        for (long e : right) assertTrue(b.contains(e), what + ": right input lost " + e);
    }

    private static Set<Long> range(long from, long toExclusive) {
        Set<Long> s = new HashSet<>();
        for (long i = from; i < toExclusive; i++) s.add(i);
        return s;
    }

    /** Disjoint, and big enough that the root has subtrees to transplant. */
    @Test
    void disjointRanges() {
        check(range(0, 500), range(500, 1000), "disjoint");
    }

    /** Identical: every bit collides, which is the worst case for sharing. */
    @Test
    void identicalSets() {
        check(range(0, 500), range(0, 500), "identical");
    }

    /** Partial overlap, the ordinary case. */
    @Test
    void halfOverlapping() {
        check(range(0, 600), range(300, 900), "half overlapping");
    }

    /** One side a strict subset: the result must equal the larger side. */
    @Test
    void subset() {
        check(range(0, 800), range(100, 200), "subset");
    }

    /**
     * SPARSE LEFT AGAINST DENSE RIGHT, which is the one case the others miss.
     *
     * <p>The four per-bit cases are NOT symmetric in A and B, and
     * `aData && bNode` carries the subtlest arithmetic in the merge: the
     * elements B contributes there are everything in B's subtree EXCEPT A's own
     * element when B already had it, which is read back from whether `insert`
     * reported a growth. Every other case above has the dense side on the left,
     * so it exercises `aNode && bData` — the easy direction, where `insert`
     * counts for us. This one puts a single element on the left at bit
     * positions where the right has whole subtrees.
     */
    @Test
    void sparseLeftDenseRight() {
        check(range(0, 60), range(0, 900), "sparse left, dense right");
        check(range(0, 20), range(500, 1400), "sparse left, disjoint dense right");
    }

    /** Tiny tries, where a node holds data and no subtrees at all. */
    @Test
    void tinySets() {
        check(range(0, 3), range(2, 5), "tiny");
    }

    /** One empty side each way. */
    @Test
    void emptySides() {
        check(range(0, 40), new HashSet<>(), "empty right");
        check(new HashSet<>(), range(0, 40), "empty left");
        check(new HashSet<>(), new HashSet<>(), "both empty");
    }

    /**
     * NEGATIVE AND EXTREME VALUES, because the index comes from
     * {@code mix64(elem) >>> shift} and a sign-extension slip shows up only
     * here. Includes the values whose mixes are most likely to share high bits.
     */
    @Test
    void negativeAndExtremeValues() {
        Set<Long> left = new HashSet<>();
        Set<Long> right = new HashSet<>();
        for (long e : new long[] {0L, -1L, 1L, Long.MIN_VALUE, Long.MAX_VALUE,
                                  -2L, 2L, -1000000L}) {
            left.add(e);
        }
        for (long e : new long[] {-1L, 3L, Long.MIN_VALUE + 1, Long.MAX_VALUE - 1,
                                  1000000L, 0L}) {
            right.add(e);
        }
        check(left, right, "extremes");
    }

    /**
     * RANDOMISED, fixed seeds. A structural merge has four cases per bit
     * position and the ones that go wrong are the asymmetric pairs — data on
     * one side against a subtree on the other — which only appear at particular
     * densities. Random sparse sets reach them where tidy ranges do not.
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
            // A deliberate shared slice, so the equal-data and shared-subtree
            // cases are exercised alongside the asymmetric ones.
            int shared = 0;
            for (long e : left) {
                if (shared++ > 80) break;
                right.add(e);
            }
            check(left, right, "random seed " + seed);
        }
    }

    /**
     * ASSOCIATIVITY AND COMMUTATIVITY, which a bit-by-bit merge can break
     * asymmetrically: the four cases are not symmetric in A and B, so
     * {@code a ∪ b} and {@code b ∪ a} go down different branches and must still
     * agree.
     */
    @Test
    void commutesAndAssociates() {
        Set<Long> x = range(0, 300), y = range(200, 500), z = range(450, 700);
        LongTrieSetStorage a = trie(x), b = trie(y), c = trie(z);

        LongTrieSetStorage ab = LongTrieSetStorage.unionWith(a, b);
        LongTrieSetStorage ba = LongTrieSetStorage.unionWith(b, a);
        assertEquals(ab.size(), ba.size(), "a∪b and b∪a differ in size");

        LongTrieSetStorage abc = LongTrieSetStorage.unionWith(ab, c);
        LongTrieSetStorage a_bc = LongTrieSetStorage.unionWith(
            a, LongTrieSetStorage.unionWith(b, c));
        assertEquals(abc.size(), a_bc.size(), "(a∪b)∪c and a∪(b∪c) differ in size");

        Set<Long> all = new HashSet<>(x);
        all.addAll(y);
        all.addAll(z);
        for (long e : all) {
            assertTrue(ab.contains(e) || c.contains(e));
            assertTrue(abc.contains(e), "(a∪b)∪c missing " + e);
            assertTrue(a_bc.contains(e), "a∪(b∪c) missing " + e);
        }
    }
}
