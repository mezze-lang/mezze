package com.mezze.datastructures.set;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MezzeSet#containsDirect} must answer exactly what
 * {@link MezzeSet#contains} answers, on every storage it can meet.
 *
 * <p>WHY IT EXISTS. A CPU profile of the one-element probe put 118 of ~130
 * steady-state samples in two frames — `SetNatives.contains` /
 * `MapNatives.containsKey`, which carry a `@TruffleBoundary`, and
 * `InlineMapContainsKey` — with `MezzeSet` and the storage inlined INTO the
 * boundary-compiled unit. Removing the boundary while naming the storage so the
 * receiver is concrete measured, on `bench/cases/allocsurface`:
 *
 * <pre>
 *   run                      set has   controls
 *     n=2e7, 8 reps           1.34x    0.92-1.01x
 *     n=1e7, 14 reps          1.33x    0.99-1.09x
 *     n=1e7, 14, map reverted 1.22x    0.90-1.13x
 * </pre>
 *
 * Always the largest mover, across three runs and two sizes, against a noise
 * floor of ~1.1x on this bench — `bytes::get` is bimodal and moves 1.09-1.19x
 * untouched, so the honest claim is ~1.2-1.3x and not a sharper figure.
 *
 * <p>THE MAP HALF OF THE SAME CHANGE WAS NOT DEMONSTRATED (1.29x then 1.13x for
 * `map has`, null for `map::get`) and is NOT shipped; the numbers are on
 * `MezzeMap.get`. And the WIDE version — naming six storage implementations
 * rather than one — measured 0.02x, i.e. 40-48x WORSE, because each branch is a
 * whole hash-map probe and PE inlined six of them into the caller. The chain
 * pays only where a branch is a CONSTANT number of field reads.
 *
 * <p>This test exists because the fast path must not answer DIFFERENTLY, and
 * the storage a set lands on is not obvious from its contents: one element is
 * `SingleElemSetStorage`, two to 32 longs a primitive `long[]` with binary
 * search, more a trie, and anything non-long a flat scan or the general trie.
 */
public class SetDirectProbeTest {

    private static MezzeSet<Object> of(Object... xs) {
        MezzeSet<Object> s = MezzeSet.empty();
        for (Object x : xs) s = s.add(x);
        return s;
    }

    /** The two answers must agree, whatever the storage. */
    private static void agree(MezzeSet<Object> s, Object probe) {
        assertEquals(s.contains(probe), s.containsDirect(probe),
                "contains/containsDirect disagree on " + probe
                        + " for " + s.getStorage().getClass().getSimpleName());
    }

    @Test
    public void theEmptyAndSingleElementCases() {
        MezzeSet<Object> empty = MezzeSet.empty();
        assertFalse(empty.containsDirect(1L));
        agree(empty, 1L);

        MezzeSet<Object> one = of(7L);
        assertTrue(one.containsDirect(7L));
        assertFalse(one.containsDirect(8L));
        agree(one, 7L);
        agree(one, 8L);
        agree(one, "7");
    }

    /** Two to 32 longs is the primitive `long[]`; above it, the trie. */
    /**
     * The typed probes the compiled inline lookup uses — no virtual
     * {@code equals}/{@code hashCode}, so native-image's analysis cannot wander
     * into other overrides — agree with {@code contains} on the two direct
     * storages, and answer false for a key of the other type.
     */
    @Test
    public void theTypedProbesAgreeOnDirectStorages() {
        MezzeSet<Object> empty = MezzeSet.empty();
        assertFalse(empty.containsDirectLong(1L));
        assertFalse(empty.containsDirectString("a"));

        MezzeSet<Object> one = of(7L);
        assertTrue(one.containsDirectLong(7L));
        assertFalse(one.containsDirectLong(8L));
        assertFalse(one.containsDirectString("7"));

        MezzeSet<Object> word = of("b");
        assertTrue(word.containsDirectString("b"));
        assertFalse(word.containsDirectString("z"));
        assertFalse(word.containsDirectLong(1L));
    }

    @Test
    public void acrossTheLongStoragePromotions() {
        for (int n : new int[]{2, 8, 32, 33, 100}) {
            MezzeSet<Object> s = MezzeSet.empty();
            for (long i = 0; i < n; i++) s = s.add(i);
            for (long i = 0; i < n; i++) {
                assertTrue(s.containsDirect(i), "size " + n + " elem " + i);
                agree(s, i);
            }
            assertFalse(s.containsDirect((long) n), "absent at size " + n);
            agree(s, (long) n);
        }
    }

    /** Non-long elements take the flat scan or the general trie. */
    @Test
    public void theNonLongStorages() {
        MezzeSet<Object> strs = of("a", "b", "c");
        assertTrue(strs.containsDirect("b"));
        assertFalse(strs.containsDirect("z"));
        agree(strs, "b");
        agree(strs, "z");
        agree(strs, 1L);

        MezzeSet<Object> mixed = of(1L, "a", 2.5d, Boolean.TRUE);
        for (Object x : new Object[]{1L, "a", 2.5d, Boolean.TRUE, 2L, "b", 1.0d, Boolean.FALSE}) {
            agree(mixed, x);
        }

        MezzeSet<Object> many = MezzeSet.empty();
        for (int i = 0; i < 50; i++) many = many.add("s" + i);
        assertTrue(many.containsDirect("s49"));
        assertFalse(many.containsDirect("s50"));
        agree(many, "s49");
        agree(many, "s50");
    }

    /** A Double and a Long of the same value are different elements. */
    @Test
    public void aDoubleIsNotALong() {
        MezzeSet<Object> s = of(1L, 2.0d);
        assertTrue(s.containsDirect(1L));
        assertTrue(s.containsDirect(2.0d));
        assertFalse(s.containsDirect(1.0d));
        assertFalse(s.containsDirect(2L));
        agree(s, 1.0d);
        agree(s, 2L);
    }

    /**
     * ONLY the storages whose probe is a constant number of field reads may run
     * inside compiled code. Asserted because widening this is what measured
     * 0.02x, and nothing else would catch someone adding a storage to it.
     */
    @Test
    public void onlyTheConstantTimeStoragesTakeTheDirectProbe() {
        assertTrue(MezzeSet.empty().hasDirectProbe(), "empty");
        assertTrue(of(1L).hasDirectProbe(), "single element");

        MezzeSet<Object> longs = MezzeSet.empty();
        for (long i = 0; i < 8; i++) longs = longs.add(i);
        assertFalse(longs.hasDirectProbe(), "a long[] binary search is not constant time");

        MezzeSet<Object> trie = MezzeSet.empty();
        for (long i = 0; i < 100; i++) trie = trie.add(i);
        assertFalse(trie.hasDirectProbe(), "a trie walk is not constant time");

        assertFalse(of("a", "b", "c").hasDirectProbe(), "a flat scan is not constant time");
    }

    /** Against a set that is already trusted, over mixed element types. */
    @Test
    public void randomMixedElementsMatchHashSet() {
        Random rnd = new Random(20261111L);
        for (int trial = 0; trial < 150; trial++) {
            Set<Object> expected = new HashSet<>();
            MezzeSet<Object> actual = MezzeSet.empty();
            int n = 1 + rnd.nextInt(40);
            for (int i = 0; i < n; i++) {
                Object x = randomElem(rnd);
                expected.add(x);
                actual = actual.add(x);
            }
            assertEquals(expected.size(), actual.size(), "trial " + trial);
            for (int probe = 0; probe < 20; probe++) {
                Object x = randomElem(rnd);
                assertEquals(expected.contains(x), actual.containsDirect(x),
                        "trial " + trial + " probe " + x + " (" + x.getClass() + ")");
                agree(actual, x);
            }
            for (Object x : expected) {
                assertTrue(actual.containsDirect(x), "stored element missing: " + x);
            }
        }
    }

    /** A small numeric range on purpose, so Long 3 and Double 3.0 both occur. */
    private static Object randomElem(Random rnd) {
        int kind = rnd.nextInt(4);
        long v = rnd.nextInt(8);
        return switch (kind) {
            case 0 -> v;
            case 1 -> (double) v;
            case 2 -> "e" + v;
            default -> v % 2 == 0;
        };
    }
}
