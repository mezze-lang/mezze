package com.mezze.datastructures.set;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link MezzeSet#fromElements} and the two trie BUILDERS behind it.
 *
 * <p>WHY THEY EXIST. Every path that rebuilds a whole set — `filter`, `map`,
 * `filter_map`, `symmetric_difference`, `from_list`, `of` — wrote
 * {@code result = result.add(e)} once per element, and so did
 * {@code TrieSetStorage.fromCollection}, which both {@code fromMutableSet}
 * helpers called while their own comments claimed a one-pass build. A
 * persistent add allocates a node at every level of the path it touches: right
 * for an update whose base survives, wasted for a rebuild that discards the
 * base on the next element. The map's identical change measured 3.2x.
 *
 * <p>WHAT THESE TESTS ARE FOR. A builder mutates nodes in place — safe only
 * because nothing else can see them yet — and reaches the same shape the adds
 * reach by a different route. Both things that could go wrong invisibly are
 * checked DIFFERENTIALLY against the add path rather than against expectations
 * written by hand: the CONTENTS (a compaction that mis-orders a node leaves an
 * element unreachable) and the STRATEGY LADDER (a builder that always made a
 * general trie would be faster than one path and slower than another).
 *
 * <p>The set's ladder has one more tier than the map's — {@link LongSetStorage}
 * to 32 for all-Long elements, {@link SmallFlatSetStorage} to 16 otherwise —
 * so {@link #fromElementsPicksTheSameTierAsAdds} pins it at every boundary
 * rather than trusting the arithmetic.
 */
public class SetBulkBuildTest {

    /** An element whose hash is a CONSTANT, so distinct instances collide. */
    static final class Collider {
        final int id;

        Collider(int id) {
            this.id = id;
        }

        @Override
        public int hashCode() {
            return 7;  // every instance, deliberately
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Collider c && c.id == id;
        }

        @Override
        public String toString() {
            return "Collider(" + id + ")";
        }
    }

    /** The set the same elements produce one add at a time. */
    private static MezzeSet<Object> byAdds(Object[] elems, int count) {
        MezzeSet<Object> s = MezzeSet.empty();
        for (int i = 0; i < count; i++) {
            s = s.add(elems[i]);
        }
        return s;
    }

    /** Builder and adds must agree on size, membership and non-membership. */
    private static void agree(Object[] elems, int count, String what) {
        MezzeSet<Object> added = byAdds(elems, count);
        MezzeSet<Object> built = MezzeSet.fromElements(elems, count);

        assertEquals(added.size(), built.size(), what + ": size");
        for (int i = 0; i < count; i++) {
            assertTrue(built.contains(elems[i]), what + ": missing " + elems[i]);
        }
        Object[] got = built.toArray();
        assertEquals(added.size(), got.length, what + ": element count");
        for (Object e : got) {
            assertTrue(added.contains(e), what + ": built holds an unasked element " + e);
        }
        assertFalse(built.contains("definitely-absent"), what + ": absent element");
    }

    @Test
    public void emptyBuildsEmpty() {
        MezzeSet<Object> s = MezzeSet.fromElements(new Object[0], 0);
        assertEquals(0, s.size());
        assertFalse(s.contains("a"));
    }

    /** A count below the array length: a filter passes an input-sized array. */
    @Test
    public void onlyTheFirstCountElementsAreRead() {
        Object[] elems = {1L, 2L, 3L, 4L, 5L};
        MezzeSet<Object> s = MezzeSet.fromElements(elems, 3);
        assertEquals(3, s.size());
        assertTrue(s.contains(3L));
        assertFalse(s.contains(4L), "an element past `count` must not be read");
    }

    /** The ladder's own boundaries: 16 flat, 32 long-flat, and well past both. */
    @Test
    public void longElementsAgreeAtEverySize() {
        for (int n : new int[]{1, 2, 15, 16, 17, 31, 32, 33, 100, 1000}) {
            Object[] elems = new Object[n];
            for (int i = 0; i < n; i++) {
                elems[i] = Long.valueOf(i);
            }
            agree(elems, n, "long elements n=" + n);
        }
    }

    @Test
    public void stringElementsAgreeAtEverySize() {
        for (int n : new int[]{1, 16, 17, 32, 33, 100, 1000}) {
            Object[] elems = new Object[n];
            for (int i = 0; i < n; i++) {
                elems[i] = "e-" + i;
            }
            agree(elems, n, "string elements n=" + n);
        }
    }

    /** Repeats collapse, exactly as the adds collapsed them. */
    @Test
    public void repeatsCollapse() {
        Object[] elems = {1L, 2L, 1L, 3L, 2L, 1L};
        agree(elems, elems.length, "repeats");
        assertEquals(3, MezzeSet.fromElements(elems, elems.length).size());
    }

    /** The same, large enough to be a trie rather than a flat tier. */
    @Test
    public void repeatsCollapseInATrie() {
        int n = 600;
        Object[] elems = new Object[n];
        for (int i = 0; i < n; i++) {
            elems[i] = Long.valueOf(i % 200);        // each element three times
        }
        agree(elems, n, "trie repeats");
        assertEquals(200, MezzeSet.fromElements(elems, n).size());
    }

    /**
     * EQUAL BUT NOT IDENTICAL, which is the general builder's own test. The
     * hand-written cases above happen to reuse one instance per value, and the
     * all-Long cases go to the long builder where elements are compared as
     * primitives — so nothing here but this (and the random rounds) notices a
     * builder that compares with {@code ==} instead of {@code equals}.
     * Verified: making that substitution fails this and the random test and
     * nothing else.
     */
    @Test
    public void equalButDistinctInstancesCollapse() {
        int n = 300;
        Object[] elems = new Object[n];
        for (int i = 0; i < n; i++) {
            // A fresh String per slot, so every repeat is a different object
            // that is nonetheless equal.
            elems[i] = new String("e-" + (i % 100));
        }
        agree(elems, n, "equal but distinct");
        assertEquals(100, MezzeSet.fromElements(elems, n).size(),
            "three equal instances of each of a hundred values");
    }

    /**
     * COLLISION NODES, which only the general trie can reach: every Collider
     * hashes to 7, so they all land in one node and the builder must hand it to
     * the persistent path rather than keep mutating.
     */
    @Test
    public void collidersAgree() {
        for (int n : new int[]{2, 17, 40}) {
            Object[] elems = new Object[n];
            for (int i = 0; i < n; i++) {
                elems[i] = new Collider(i);
            }
            agree(elems, n, "colliders n=" + n);
        }
    }

    /** Colliders mixed with ordinary elements: the collision node has neighbours. */
    @Test
    public void collidersAndOrdinaryElementsAgree() {
        int n = 300;
        Object[] elems = new Object[n];
        for (int i = 0; i < n; i++) {
            elems[i] = i % 5 == 0 ? new Collider(i) : (Object) ("e" + i);
        }
        agree(elems, n, "mixed colliders");
    }

    /** Mixed TYPES force the general trie even though some elements are Longs. */
    @Test
    public void mixedElementTypesAgree() {
        int n = 200;
        Object[] elems = new Object[n];
        for (int i = 0; i < n; i++) {
            elems[i] = switch (i % 4) {
                case 0 -> Long.valueOf(i);
                case 1 -> "s" + i;
                case 2 -> Double.valueOf(i + 0.5);
                default -> new Collider(i);
            };
        }
        agree(elems, n, "mixed element types");
    }

    /**
     * THE LADDER IS PRESERVED. A bulk build must land in the same storage the
     * adds land in — the flat tiers at their own sizes, the UNBOXED long trie
     * when every element is a Long, the general trie otherwise.
     */
    @Test
    public void fromElementsPicksTheSameTierAsAdds() {
        for (int n : new int[]{1, 5, 16, 17, 32, 33, 50, 1000}) {
            Object[] longs = new Object[n];
            Object[] strings = new Object[n];
            for (int i = 0; i < n; i++) {
                longs[i] = Long.valueOf(i);
                strings[i] = "e" + i;
            }
            assertEquals(byAdds(longs, n).storage().getClass(),
                MezzeSet.<Object>fromElements(longs, n).storage().getClass(),
                "long-element storage at n=" + n);
            assertEquals(byAdds(strings, n).storage().getClass(),
                MezzeSet.<Object>fromElements(strings, n).storage().getClass(),
                "string-element storage at n=" + n);
        }
    }

    /**
     * A differential run over random shapes. LONG-ONLY is separate because one
     * String element takes the whole set off the long tier, so a mixed run says
     * nothing about the long builder.
     */
    @Test
    public void randomShapesAgreeWithAddsAndWithHashSet() {
        for (boolean longOnly : new boolean[]{false, true}) {
            for (long seed : new long[]{1L, 42L, 20261112L, -7L}) {
                Random rnd = new Random(seed);
                for (int round = 0; round < 60; round++) {
                    int n = rnd.nextInt(400);
                    Object[] elems = new Object[n];
                    Set<Object> ref = new HashSet<>();
                    for (int i = 0; i < n; i++) {
                        int pick = rnd.nextInt(10);
                        elems[i] = longOnly ? (Object) Long.valueOf(rnd.nextInt(80))
                            : pick == 0 ? new Collider(rnd.nextInt(8))
                            : pick < 5 ? (Object) Long.valueOf(rnd.nextInt(80))
                            : (Object) ("e" + rnd.nextInt(80));
                        ref.add(elems[i]);
                    }

                    String what = (longOnly ? "long-only" : "mixed")
                        + " seed " + seed + " round " + round;
                    MezzeSet<Object> built = MezzeSet.fromElements(elems, n);
                    assertEquals(ref.size(), built.size(), "size, " + what);
                    for (Object e : ref) {
                        assertTrue(built.contains(e), "element " + e + ", " + what);
                    }
                    agree(elems, n, what);
                }
            }
        }
    }

    /** `filter` keeping everything returns the set itself. */
    @Test
    public void filterKeepingEverythingReturnsTheSameSet() {
        MezzeSet<Object> s = MezzeSet.empty();
        for (int i = 0; i < 50; i++) {
            s = s.add(Long.valueOf(i));
        }
        assertSame(s, s.filter(e -> true), "no element dropped");
        assertEquals(25, s.filter(e -> ((Long) e) < 25).size());
    }
}
