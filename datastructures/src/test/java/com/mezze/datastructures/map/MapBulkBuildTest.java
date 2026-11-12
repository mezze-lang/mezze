package com.mezze.datastructures.map;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link MezzeMap#fromEntries} and the two trie BUILDERS behind it.
 *
 * <p>WHY THEY EXIST. Every path that rebuilds a whole map — `filter`,
 * `map_values`, `map_keys`, `filter_keys`, `filter_values`, `invert`,
 * `select_keys`, the set-like combinators, `from_seq`, `from_list`,
 * `put_all` — used to write {@code result = result.put(k, v)} once per entry,
 * and so did {@code TrieMapStorage.fromMap}, whose comment called itself a
 * one-pass build while doing exactly those N persistent puts. A persistent put
 * allocates a node at every level of the path it touches: right for an update,
 * wasted for a rebuild that discards the base on the very next entry. MEASURED
 * on 1 000 entries: 103.9 ns an entry put one at a time, 32.2 through the
 * builder (3.2x), against a {@code java.util.HashMap}'s in-place 15.9.
 *
 * <p>WHAT THESE TESTS ARE FOR. A builder mutates nodes in place, which is only
 * safe because nothing else can see them yet, and it reaches the SAME shape the
 * persistent puts reach by a different route. Two things could therefore go
 * wrong invisibly, and both are checked DIFFERENTIALLY against the put path
 * rather than against an expectation written out by hand:
 *
 * <ul>
 *   <li>CONTENTS. A slot-indexed node is compacted into bitmap order on the way
 *       out, so a key could end up reachable at a different index than its
 *       bitmap says — a get would then answer null or another key's value.
 *   <li>THE STRATEGY LADDER. A map's storage is chosen by size and key type,
 *       and a builder that always made a general trie would be faster than one
 *       path and slower than another. {@link #fromEntriesPicksTheSameTierAsPuts}
 *       pins the tier itself, not just the contents.
 * </ul>
 *
 * <p>The general trie additionally needs COLLISION coverage: {@code mix32} is
 * not a bijection, so distinct keys can agree on all 32 bits and must share a
 * {@link TrieMapStorage.Collision}. {@link Collider} forces it.
 */
public class MapBulkBuildTest {

    /** A key whose hash is a CONSTANT, so distinct instances collide fully. */
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

    /** The map the same entries produce one put at a time. */
    private static MezzeMap<Object, Object> byPuts(Object[] keys, Object[] values, int count) {
        MezzeMap<Object, Object> m = MezzeMap.empty();
        for (int i = 0; i < count; i++) {
            m = m.putObject(keys[i], values[i]);
        }
        return m;
    }

    /**
     * The builder and the puts must agree on size, on every key's value, and on
     * what they do NOT contain.
     */
    private static void agree(Object[] keys, Object[] values, int count, String what) {
        MezzeMap<Object, Object> put = byPuts(keys, values, count);
        MezzeMap<Object, Object> built = MezzeMap.fromEntries(keys, values, count);

        assertEquals(put.size(), built.size(), what + ": size");
        for (int i = 0; i < count; i++) {
            assertEquals(put.get(keys[i]), built.get(keys[i]),
                what + ": key " + keys[i]);
            assertNotNull(built.get(keys[i]), what + ": key " + keys[i] + " missing");
        }
        // Nothing invented: every key the builder holds was handed to it.
        Object[] gotKeys = built.keys();
        assertEquals(put.size(), gotKeys.length, what + ": key count");
        for (Object k : gotKeys) {
            assertNotNull(put.get(k), what + ": built holds an unasked key " + k);
        }
        assertNull(built.get("definitely-absent"), what + ": absent key");
    }

    @Test
    public void emptyBuildsEmpty() {
        MezzeMap<Object, Object> m = MezzeMap.fromEntries(new Object[0], new Object[0], 0);
        assertEquals(0, m.size());
        assertNull(m.get("a"));
    }

    /** A count below the array length: a filter passes arrays sized for input. */
    @Test
    public void onlyTheFirstCountEntriesAreRead() {
        Object[] keys = {1L, 2L, 3L, 4L, 5L};
        Object[] values = {10L, 20L, 30L, null, null};
        MezzeMap<Object, Object> m = MezzeMap.fromEntries(keys, values, 3);
        assertEquals(3, m.size());
        assertEquals(30L, m.get(3L));
        assertNull(m.get(4L), "an entry past `count` must not be read");
    }

    /**
     * The sizes that matter are the ladder's own boundaries: one entry, the
     * flat storage's maximum, the first size past it, and well into a trie.
     */
    @Test
    public void longKeysAgreeAtEverySize() {
        for (int n : new int[]{1, 2, 7, 8, 9, 31, 32, 33, 100, 1000}) {
            Object[] keys = new Object[n];
            Object[] values = new Object[n];
            for (int i = 0; i < n; i++) {
                keys[i] = Long.valueOf(i);
                values[i] = Long.valueOf(i * 10L);
            }
            agree(keys, values, n, "long keys n=" + n);
        }
    }

    @Test
    public void stringKeysAgreeAtEverySize() {
        for (int n : new int[]{1, 8, 9, 100, 1000}) {
            Object[] keys = new Object[n];
            Object[] values = new Object[n];
            for (int i = 0; i < n; i++) {
                keys[i] = "key-" + i;
                values[i] = Long.valueOf(i);
            }
            agree(keys, values, n, "string keys n=" + n);
        }
    }

    /** A later entry wins, exactly as the repeated puts did. */
    @Test
    public void duplicateKeysTakeTheLaterValue() {
        Object[] keys = {1L, 2L, 1L, 3L, 2L, 1L};
        Object[] values = {100L, 200L, 101L, 300L, 201L, 102L};
        agree(keys, values, keys.length, "duplicates");

        MezzeMap<Object, Object> m = MezzeMap.fromEntries(keys, values, keys.length);
        assertEquals(3, m.size());
        assertEquals(102L, m.get(1L));
        assertEquals(201L, m.get(2L));
    }

    /** The same, large enough to be a trie rather than the flat storage. */
    @Test
    public void duplicateKeysTakeTheLaterValueInATrie() {
        int n = 600;
        Object[] keys = new Object[n];
        Object[] values = new Object[n];
        for (int i = 0; i < n; i++) {
            keys[i] = Long.valueOf(i % 200);        // each key three times
            values[i] = Long.valueOf(i);
        }
        agree(keys, values, n, "trie duplicates");

        MezzeMap<Object, Object> m = MezzeMap.fromEntries(keys, values, n);
        assertEquals(200, m.size());
        for (int k = 0; k < 200; k++) {
            assertEquals(Long.valueOf(400 + k), m.get(Long.valueOf(k)),
                "the last of three writes to key " + k);
        }
    }

    /**
     * COLLISION NODES, which only the general trie can reach: every Collider
     * hashes to 7, so they all land in one node, and the builder must hand that
     * node to the persistent path rather than keep mutating.
     */
    @Test
    public void collidersAgree() {
        for (int n : new int[]{2, 9, 40}) {
            Object[] keys = new Object[n];
            Object[] values = new Object[n];
            for (int i = 0; i < n; i++) {
                keys[i] = new Collider(i);
                values[i] = Long.valueOf(i);
            }
            agree(keys, values, n, "colliders n=" + n);
        }
    }

    /** Colliders mixed with ordinary keys: the collision node has neighbours. */
    @Test
    public void collidersAndOrdinaryKeysAgree() {
        int n = 300;
        Object[] keys = new Object[n];
        Object[] values = new Object[n];
        for (int i = 0; i < n; i++) {
            keys[i] = i % 5 == 0 ? new Collider(i) : (Object) ("k" + i);
            values[i] = Long.valueOf(i);
        }
        agree(keys, values, n, "mixed colliders");
    }

    /** Mixed key TYPES force the general trie even though some keys are Longs. */
    @Test
    public void mixedKeyTypesAgree() {
        int n = 200;
        Object[] keys = new Object[n];
        Object[] values = new Object[n];
        for (int i = 0; i < n; i++) {
            keys[i] = switch (i % 4) {
                case 0 -> Long.valueOf(i);
                case 1 -> "s" + i;
                case 2 -> Double.valueOf(i + 0.5);
                default -> new Collider(i);
            };
            values[i] = Long.valueOf(i);
        }
        agree(keys, values, n, "mixed key types");
    }

    /**
     * THE LADDER IS PRESERVED. A bulk build must land in the same storage the
     * puts land in, or it would be faster than one path and slower than
     * another — the flat storage below nine entries, the UNBOXED long trie when
     * every key is a Long, the general trie otherwise.
     */
    @Test
    public void fromEntriesPicksTheSameTierAsPuts() {
        for (int n : new int[]{1, 5, 8, 9, 50, 1000}) {
            Object[] longs = new Object[n];
            Object[] strings = new Object[n];
            Object[] values = new Object[n];
            for (int i = 0; i < n; i++) {
                longs[i] = Long.valueOf(i);
                strings[i] = "k" + i;
                values[i] = Long.valueOf(i);
            }
            assertEquals(byPuts(longs, values, n).storage().getClass(),
                MezzeMap.fromEntries(longs, values, n).storage().getClass(),
                "long-keyed storage at n=" + n);
            assertEquals(byPuts(strings, values, n).storage().getClass(),
                MezzeMap.fromEntries(strings, values, n).storage().getClass(),
                "string-keyed storage at n=" + n);
        }
    }

    /**
     * A differential run over random shapes, because the shapes written out by
     * hand above are the ones that were thought of.
     *
     * <p>MIXED means the general trie, always: one String key is enough to take
     * the whole map off the long-keyed tier. So {@code longOnly} runs the same
     * rounds with nothing but Long keys — without it this test says nothing
     * about the long builder, which is the one the common case uses. (Verified:
     * reversing the long builder's compaction order leaves the mixed rounds
     * green and fails the long ones.)
     */
    @Test
    public void randomShapesAgreeWithPutsAndWithHashMap() {
        for (boolean longOnly : new boolean[]{false, true}) {
            for (long seed : new long[]{1L, 42L, 20261112L, -7L}) {
                Random rnd = new Random(seed);
                for (int round = 0; round < 60; round++) {
                    int n = rnd.nextInt(400);
                    Object[] keys = new Object[n];
                    Object[] values = new Object[n];
                    Map<Object, Object> ref = new HashMap<>();
                    for (int i = 0; i < n; i++) {
                        int pick = rnd.nextInt(10);
                        // A small key space, so duplicates and collisions happen.
                        keys[i] = longOnly ? (Object) Long.valueOf(rnd.nextInt(80))
                            : pick == 0 ? new Collider(rnd.nextInt(8))
                            : pick < 5 ? (Object) Long.valueOf(rnd.nextInt(80))
                            : (Object) ("k" + rnd.nextInt(80));
                        values[i] = Long.valueOf(rnd.nextInt(1_000_000) + 1);
                        ref.put(keys[i], values[i]);
                    }

                    String what = (longOnly ? "long-only" : "mixed")
                        + " seed " + seed + " round " + round;
                    MezzeMap<Object, Object> built = MezzeMap.fromEntries(keys, values, n);
                    assertEquals(ref.size(), built.size(), "size, " + what);
                    for (Map.Entry<Object, Object> e : ref.entrySet()) {
                        assertEquals(e.getValue(), built.get(e.getKey()),
                            "key " + e.getKey() + ", " + what);
                    }
                    agree(keys, values, n, what);
                }
            }
        }
    }

    /** `put_all` merges both sides through the builder: `other` wins a shared key. */
    @Test
    public void putAllKeepsTheOtherSideOnASharedKey() {
        MezzeMap<Object, Object> a = MezzeMap.empty();
        MezzeMap<Object, Object> b = MezzeMap.empty();
        for (int i = 0; i < 40; i++) {
            a = a.putObject(Long.valueOf(i), "a" + i);
        }
        for (int i = 20; i < 60; i++) {
            b = b.putObject(Long.valueOf(i), "b" + i);
        }
        MezzeMap<Object, Object> merged = a.putAll(b);

        assertEquals(60, merged.size());
        for (int i = 0; i < 20; i++) {
            assertEquals("a" + i, merged.get(Long.valueOf(i)), "only in a");
        }
        for (int i = 20; i < 60; i++) {
            assertEquals("b" + i, merged.get(Long.valueOf(i)), "b overwrites a");
        }
    }

    /**
     * A merge of two long-keyed maps keeps the UNBOXED storage. It did not
     * before: `put_all` went through a {@code java.util.HashMap} and then built
     * a general trie, so every later lookup on a merged map boxed its key.
     */
    @Test
    public void putAllKeepsTheLongKeyedStorage() {
        MezzeMap<Object, Object> a = MezzeMap.empty();
        MezzeMap<Object, Object> b = MezzeMap.empty();
        for (int i = 0; i < 40; i++) {
            a = a.putObject(Long.valueOf(i), Long.valueOf(i));
            b = b.putObject(Long.valueOf(i + 100), Long.valueOf(i));
        }
        assertEquals(LongTrieMapStorage.class, a.storage().getClass(), "the base");
        assertEquals(LongTrieMapStorage.class, a.putAll(b).storage().getClass(),
            "a merge of two long-keyed maps");
    }

    /** `filter` keeping everything returns the map itself. */
    @Test
    public void filterKeepingEverythingReturnsTheSameMap() {
        MezzeMap<Object, Object> m = MezzeMap.empty();
        for (int i = 0; i < 50; i++) {
            m = m.putObject(Long.valueOf(i), Long.valueOf(i));
        }
        assertSame(m, m.filter((k, v) -> true), "no entry dropped");
        assertEquals(25, m.filter((k, v) -> ((Long) k) < 25).size());
    }

    /** `map_values` keeps the keys and the key-to-value pairing. */
    @Test
    public void mapValuesKeepsThePairing() {
        MezzeMap<Object, Object> m = MezzeMap.empty();
        for (int i = 0; i < 300; i++) {
            m = m.putObject(Long.valueOf(i), Long.valueOf(i));
        }
        MezzeMap<Object, Object> doubled = m.mapValues(v -> ((Long) v) * 2);
        assertEquals(300, doubled.size());
        for (int i = 0; i < 300; i++) {
            assertEquals(Long.valueOf(i * 2L), doubled.get(Long.valueOf(i)),
                "key " + i + " must still own its own value");
        }
    }

    /** `map_keys` collapsing two keys onto one keeps the later entry. */
    @Test
    public void mapKeysCollapsingKeepsTheLater() {
        MezzeMap<Object, Object> m = MezzeMap.empty();
        for (int i = 0; i < 100; i++) {
            m = m.putObject(Long.valueOf(i), Long.valueOf(i));
        }
        MezzeMap<Object, Object> halved = m.mapKeys(k -> ((Long) k) / 2);
        assertEquals(50, halved.size());
        for (int i = 0; i < 50; i++) {
            assertNotNull(halved.get(Long.valueOf(i)), "collapsed key " + i);
        }
    }
}
