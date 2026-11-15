package com.mezze.datastructures.map;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link LongTrieMapStorage#mergeWith} against the answer, entry by entry.
 *
 * <p>THE VALUE IS THE POINT HERE, not just the key set. `putAll` means OTHER'S
 * VALUES WIN — {@code fromEntries} builds with `put` and the later entry
 * overwrites, which the sibling `mapKeys` records as "the later wins, as the
 * puts did". A structural merge is asymmetric for that reason, and every case
 * has to place the right winner. A merge that got the key set perfectly right
 * and the winner backwards would pass any test that only asked which keys are
 * present, so these compare VALUES for every key.
 *
 * <p>The asymmetric case is the one to distrust: A holds a single entry where B
 * holds a whole subtree. `insert` overwrites, so using it there would make A the
 * winner on a shared key — backwards — and the merge checks containment first
 * instead. {@code overlappingKeysTakeTheRightValue} and the random cases are
 * what hold that down.
 *
 * <p>Compared against {@link HashMap}, not another MezzeMap path, which could
 * share a bug. Size is checked separately from contents because it is
 * accumulated as the merge walks rather than derived from the result.
 */
class LongTrieMergeTest {

    private static LongTrieMapStorage trie(Map<Long, Object> entries) {
        MapStorage s = new LongTrieMapStorage();
        for (var e : entries.entrySet()) {
            s = ((LongTrieMapStorage) s).putLong(e.getKey(), e.getValue());
        }
        return (LongTrieMapStorage) s;
    }

    /** Values are tagged with the side they came from, so a wrong winner shows. */
    private static Map<Long, Object> side(String tag, long from, long toExclusive) {
        Map<Long, Object> m = new HashMap<>();
        for (long i = from; i < toExclusive; i++) m.put(i, tag + ":" + i);
        return m;
    }

    private static void check(Map<Long, Object> left, Map<Long, Object> right, String what) {
        LongTrieMapStorage a = trie(left);
        LongTrieMapStorage b = trie(right);

        // The answer: left, then right overwriting — which is what putAll means.
        Map<Long, Object> expected = new HashMap<>(left);
        expected.putAll(right);

        LongTrieMapStorage out = LongTrieMapStorage.mergeWith(a, b);

        assertEquals(expected.size(), out.size(),
            what + ": size disagrees with the merge's own contents");
        for (var e : expected.entrySet()) {
            assertEquals(e.getValue(), out.get(e.getKey()),
                what + ": wrong value under key " + e.getKey()
                    + " (a wrong WINNER looks exactly like this)");
        }
        // The inputs are persistent and their subtrees are shared, so a merge
        // that mutated one would corrupt a value someone else still holds.
        assertEquals(left.size(), a.size(), what + ": left input changed size");
        assertEquals(right.size(), b.size(), what + ": right input changed size");
        for (var e : left.entrySet()) {
            assertEquals(e.getValue(), a.get(e.getKey()), what + ": left input altered");
        }
        for (var e : right.entrySet()) {
            assertEquals(e.getValue(), b.get(e.getKey()), what + ": right input altered");
        }
    }

    @Test
    void disjointRanges() {
        check(side("L", 0, 500), side("R", 500, 1000), "disjoint");
    }

    /**
     * EVERY KEY SHARED: the result must be entirely R's values. This is the case
     * that fails loudly if the winner is backwards.
     */
    @Test
    void overlappingKeysTakeTheRightValue() {
        check(side("L", 0, 500), side("R", 0, 500), "fully overlapping");
    }

    @Test
    void halfOverlapping() {
        check(side("L", 0, 600), side("R", 300, 900), "half overlapping");
    }

    /** Right a strict subset of left: only those keys change value. */
    @Test
    void rightIsSubset() {
        check(side("L", 0, 800), side("R", 100, 200), "right subset");
    }

    /**
     * SPARSE LEFT AGAINST DENSE RIGHT — the asymmetric `aData && bNode` case,
     * where A has one entry at a bit position and B has a subtree. That is the
     * branch where `insert` would pick the wrong winner, so it is checked with
     * overlap (B must win) and without (A's entry must survive).
     */
    @Test
    void sparseLeftDenseRight() {
        check(side("L", 0, 60), side("R", 0, 900), "sparse left, overlapping");
        check(side("L", 0, 20), side("R", 500, 1400), "sparse left, disjoint");
    }

    @Test
    void tinyMaps() {
        check(side("L", 0, 3), side("R", 2, 5), "tiny");
    }

    @Test
    void emptySides() {
        check(side("L", 0, 40), new HashMap<>(), "empty right");
        check(new HashMap<>(), side("R", 0, 40), "empty left");
        check(new HashMap<>(), new HashMap<>(), "both empty");
    }

    /** Negative and extreme keys, where a sign-extension slip would show. */
    @Test
    void negativeAndExtremeKeys() {
        Map<Long, Object> left = new HashMap<>();
        Map<Long, Object> right = new HashMap<>();
        for (long k : new long[] {0L, -1L, 1L, Long.MIN_VALUE, Long.MAX_VALUE, -2L,
                                  -1000000L}) {
            left.put(k, "L:" + k);
        }
        for (long k : new long[] {-1L, 3L, Long.MIN_VALUE + 1, Long.MAX_VALUE - 1,
                                  1000000L, 0L}) {
            right.put(k, "R:" + k);
        }
        check(left, right, "extremes");
    }

    /**
     * RANDOMISED, fixed seeds, with a deliberate shared slice so the
     * equal-key, shared-subtree and asymmetric cases all appear at densities
     * tidy ranges do not reach.
     */
    @Test
    void randomSparseMaps() {
        for (long seed : new long[] {1L, 42L, 1337L, 161803L, 2718281828L}) {
            Random r = new Random(seed);
            Map<Long, Object> left = new HashMap<>();
            Map<Long, Object> right = new HashMap<>();
            for (int i = 0; i < 400; i++) {
                long kl = r.nextLong() % 100000;
                long kr = r.nextLong() % 100000;
                left.put(kl, "L:" + kl);
                right.put(kr, "R:" + kr);
            }
            int shared = 0;
            for (long k : left.keySet()) {
                if (shared++ > 80) break;
                right.put(k, "R:" + k);   // R must win every one of these
            }
            check(left, right, "random seed " + seed);
        }
    }

    /**
     * NOT COMMUTATIVE, and that is the assertion. A set union is symmetric; a
     * map merge is not, because the winner differs. `a merge b` and `b merge a`
     * must agree on the KEY SET and disagree on exactly the shared keys' values
     * — a merge that came out symmetric would mean the winner logic had been
     * dropped.
     */
    @Test
    void mergeIsNotCommutativeOnValues() {
        Map<Long, Object> l = side("L", 0, 300), r = side("R", 200, 500);
        LongTrieMapStorage a = trie(l), b = trie(r);

        LongTrieMapStorage ab = LongTrieMapStorage.mergeWith(a, b);
        LongTrieMapStorage ba = LongTrieMapStorage.mergeWith(b, a);

        assertEquals(ab.size(), ba.size(), "the key sets should match");
        for (long k = 0; k < 500; k++) {
            assertNotNull(ab.get(k), "a merge b lost " + k);
            assertNotNull(ba.get(k), "b merge a lost " + k);
        }
        // The shared band is where the winner shows.
        for (long k = 200; k < 300; k++) {
            assertEquals("R:" + k, ab.get(k), "a merge b: right should win at " + k);
            assertEquals("L:" + k, ba.get(k), "b merge a: left should win at " + k);
        }
    }
}
