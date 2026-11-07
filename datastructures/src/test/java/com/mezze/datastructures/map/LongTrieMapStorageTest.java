package com.mezze.datastructures.map;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link LongTrieMapStorage} — the persistent trie that replaces the
 * open-addressed table whose in-place writes were BUG 38.
 *
 * <p>The property that matters, and the reason this class exists, is that a
 * derivation is invisible in the storage it was derived from. That is asserted
 * directly here and again through the language in
 * {@code PersistentReplaceSoundnessTest}; the difference is that these run
 * against the structure with no interpreter in the way, so a failure localises.
 *
 * <p>The load-bearing test is {@link #randomOperationsMatchHashMap()}: a new
 * data structure earns trust from a differential against one that is already
 * trusted, not from hand-picked cases. The hand-picked cases are here too,
 * because when the differential fails it says "at operation 4 071" and a named
 * case says which shape broke.
 */
public class LongTrieMapStorageTest {

    private static MapStorage of(long... keys) {
        MapStorage m = new LongTrieMapStorage();
        for (long k : keys) m = m.put(k, k * 10);
        return m;
    }

    @Test
    public void emptyFindsNothing() {
        MapStorage m = new LongTrieMapStorage();
        assertEquals(0, m.size());
        assertTrue(m.isEmpty());
        assertNull(m.get(1L));
        assertFalse(m.containsKey(1L));
    }

    @Test
    public void singleEntryRoundTrips() {
        MapStorage m = of(7L);
        assertEquals(1, m.size());
        assertEquals(70L, m.get(7L));
        assertNull(m.get(8L));
    }

    /**
     * ACROSS THE DEPTHS. 5 bits a level means 32 slots, so these sizes put the
     * trie at one, two and three levels; a bug in the data/node split or in the
     * bitmap indexing shows up as soon as a node has to spill.
     */
    @Test
    public void everyKeyIsFoundAtEverySize() {
        for (int n : new int[]{1, 2, 8, 31, 32, 33, 64, 1000, 5000}) {
            MapStorage m = new LongTrieMapStorage();
            for (long i = 0; i < n; i++) m = m.put(i, i * 10);
            assertEquals(n, m.size(), "size at n=" + n);
            for (long i = 0; i < n; i++) {
                assertEquals(i * 10, m.get(i), "key " + i + " at n=" + n);
            }
            assertNull(m.get((long) n), "absent key at n=" + n);
        }
    }

    @Test
    public void replacingAKeyKeepsTheSize() {
        MapStorage m = of(1L, 2L, 3L);
        MapStorage r = m.put(2L, 999L);
        assertEquals(3, r.size());
        assertEquals(999L, r.get(2L));
    }

    /** THE BUG 38 PROPERTY, on a replace. */
    @Test
    public void aReplaceIsInvisibleInTheBase() {
        MapStorage base = new LongTrieMapStorage();
        for (long i = 0; i < 64; i++) base = base.put(i, i * 10);
        MapStorage derived = base.put(30L, 777L);
        assertEquals(300L, base.get(30L), "the base must not see the derived value");
        assertEquals(777L, derived.get(30L));
        assertEquals(64, base.size());
        assertEquals(64, derived.size());
    }

    /** THE BUG 38 PROPERTY, on a new key — the half the first filing got wrong. */
    @Test
    public void anInsertIsInvisibleInTheBase() {
        MapStorage base = new LongTrieMapStorage();
        for (long i = 0; i < 64; i++) base = base.put(i, i * 10);
        MapStorage derived = base.put(999L, 1L);
        assertFalse(base.containsKey(999L), "the base must not acquire the derived key");
        assertTrue(derived.containsKey(999L));
        assertEquals(64, base.size());
        assertEquals(65, derived.size());
    }

    /**
     * A LONG CHAIN with the base held throughout — the shape that made the
     * diff-chain attempt retain 2.5 GB. Every version must report its own
     * contents, and the base must still report its own.
     */
    @Test
    public void everyVersionOfALongChainKeepsItsOwnContents() {
        MapStorage base = new LongTrieMapStorage();
        for (long i = 0; i < 200; i++) base = base.put(i, i);
        MapStorage[] versions = new MapStorage[200];
        MapStorage m = base;
        for (int i = 0; i < 200; i++) {
            m = m.put(0L, (long) (i + 1000));
            versions[i] = m;
        }
        assertEquals(0L, base.get(0L), "the base after 200 derivations");
        for (int i = 0; i < 200; i++) {
            assertEquals((long) (i + 1000), versions[i].get(0L), "version " + i);
            assertEquals(200, versions[i].size());
        }
    }

    @Test
    public void twoDerivationsFromOneBaseAreIndependent() {
        MapStorage base = of(1L, 2L, 3L);
        MapStorage a = base.put(2L, 111L);
        MapStorage b = base.put(2L, 222L);
        assertEquals(20L, base.get(2L));
        assertEquals(111L, a.get(2L));
        assertEquals(222L, b.get(2L));
    }

    @Test
    public void removeTakesOnlyTheKeyAsked() {
        MapStorage m = new LongTrieMapStorage();
        for (long i = 0; i < 100; i++) m = m.put(i, i * 10);
        MapStorage r = m.remove(50L);
        assertEquals(99, r.size());
        assertNull(r.get(50L));
        assertEquals(400L, r.get(40L));
        assertEquals(500L, m.get(50L), "the base must keep the removed key");
        assertEquals(100, m.size());
    }

    @Test
    public void removingEverythingEndsEmpty() {
        MapStorage m = new LongTrieMapStorage();
        for (long i = 0; i < 64; i++) m = m.put(i, i);
        for (long i = 0; i < 64; i++) m = m.remove(i);
        assertEquals(0, m.size());
        assertTrue(m.isEmpty());
    }

    @Test
    public void removingAnAbsentKeyChangesNothing() {
        MapStorage m = of(1L, 2L);
        assertSame(m, m.remove(99L));
    }

    /**
     * THE OLD SENTINEL KEYS. The table this replaces reserved
     * `Long.MIN_VALUE` and `MIN_VALUE+1` as empty/deleted markers and had to
     * promote the whole map to boxed storage when a program used one as a key.
     * A trie stores no markers, so they are ordinary keys — asserted here
     * BEHAVIOURALLY, by storing and reading them back, so the property survives
     * even though the predicate that used to express the restriction is gone.
     */
    @Test
    public void theOldSentinelValuesAreOrdinaryKeys() {
        MapStorage m = new LongTrieMapStorage();
        m = m.put(Long.MIN_VALUE, 1L);
        m = m.put(Long.MIN_VALUE + 1, 2L);
        m = m.put(Long.MAX_VALUE, 3L);
        m = m.put(0L, 4L);
        assertEquals(4, m.size());
        assertEquals(1L, m.get(Long.MIN_VALUE));
        assertEquals(2L, m.get(Long.MIN_VALUE + 1));
        assertEquals(3L, m.get(Long.MAX_VALUE));
        assertEquals(4L, m.get(0L));
    }

    @Test
    public void negativeAndWidelySpacedKeys() {
        long[] keys = {-1L, -1000L, 1L << 40, -(1L << 40), 0L, 7L, Long.MIN_VALUE / 3};
        MapStorage m = new LongTrieMapStorage();
        for (long k : keys) m = m.put(k, k);
        assertEquals(keys.length, m.size());
        for (long k : keys) assertEquals(k, m.get(k), "key " + k);
    }

    @Test
    public void keysAndValuesEnumerateEverything() {
        MapStorage m = new LongTrieMapStorage();
        Set<Long> expected = new HashSet<>();
        for (long i = 0; i < 300; i++) {
            m = m.put(i, i * 10);
            expected.add(i);
        }
        Object[] ks = m.keys();
        Object[] vs = m.values();
        assertEquals(300, ks.length);
        assertEquals(300, vs.length);
        Set<Long> seen = new HashSet<>();
        for (Object k : ks) seen.add((Long) k);
        assertEquals(expected, seen);
        // keys() and values() must agree positionally.
        for (int i = 0; i < ks.length; i++) {
            assertEquals(((Long) ks[i]) * 10, vs[i], "pair at " + i);
        }
    }

    @Test
    public void forEachVisitsEveryEntryOnce() {
        MapStorage m = new LongTrieMapStorage();
        for (long i = 0; i < 200; i++) m = m.put(i, i * 10);
        Map<Object, Object> seen = new HashMap<>();
        m.forEach(seen::put);
        assertEquals(200, seen.size());
        for (long i = 0; i < 200; i++) assertEquals(i * 10, seen.get(i));
    }

    @Test
    public void forEachLongDoesNotBox() {
        LongTrieMapStorage m = new LongTrieMapStorage();
        MapStorage s = m;
        for (long i = 0; i < 50; i++) s = s.put(i, i * 10);
        long[] total = new long[2];
        ((LongTrieMapStorage) s).forEachLong((k, v) -> {
            total[0] += k;
            total[1] += (Long) v;
        });
        assertEquals(49 * 50 / 2, total[0]);
        assertEquals(10 * (49 * 50 / 2), total[1]);
    }

    @Test
    public void aNonLongKeyPromotesToGenericStorage() {
        MapStorage m = of(1L, 2L);
        MapStorage promoted = m.put("hello", 1L);
        assertEquals(3, promoted.size());
        assertEquals(1L, promoted.get("hello"));
        assertEquals(10L, promoted.get(1L));
        assertEquals(2, m.size(), "the Long-keyed base is untouched by the promotion");
    }

    /**
     * THE LOAD-BEARING TEST. Random inserts, replaces and removes against a
     * `java.util.HashMap` kept in step, re-checking the whole contents
     * periodically, and holding on to an old version to assert it never drifts.
     *
     * <p>A fixed seed so a failure is reproducible; several seeds so one lucky
     * sequence cannot pass for correctness.
     */
    @Test
    public void randomOperationsMatchHashMap() {
        for (long seed : new long[]{1L, 42L, 20261107L, -7L}) {
            Random rnd = new Random(seed);
            Map<Long, Long> ref = new HashMap<>();
            MapStorage got = new LongTrieMapStorage();

            MapStorage snapshot = null;
            Map<Long, Long> snapshotRef = null;

            for (int op = 0; op < 8000; op++) {
                long key = rnd.nextInt(600) - 200;   // dense, with negatives
                int which = rnd.nextInt(10);
                if (which < 7) {
                    long value = rnd.nextLong();
                    ref.put(key, value);
                    got = got.put(key, value);
                } else {
                    ref.remove(key);
                    got = got.remove(key);
                }
                assertEquals(ref.size(), got.size(),
                    "size, seed " + seed + " op " + op);

                if (op == 4000) {
                    snapshot = got;
                    snapshotRef = new HashMap<>(ref);
                }
                if (op % 500 == 0) {
                    for (Map.Entry<Long, Long> e : ref.entrySet()) {
                        assertEquals(e.getValue(), got.get(e.getKey()),
                            "key " + e.getKey() + ", seed " + seed + " op " + op);
                    }
                    assertEquals(ref.size(), got.keys().length,
                        "keys() length, seed " + seed + " op " + op);
                }
            }

            // The old version must still be exactly what it was.
            assertEquals(snapshotRef.size(), snapshot.size(), "snapshot size, seed " + seed);
            for (Map.Entry<Long, Long> e : snapshotRef.entrySet()) {
                assertEquals(e.getValue(), snapshot.get(e.getKey()),
                    "snapshot key " + e.getKey() + ", seed " + seed);
            }
            for (Map.Entry<Long, Long> e : ref.entrySet()) {
                assertEquals(e.getValue(), got.get(e.getKey()),
                    "final key " + e.getKey() + ", seed " + seed);
            }
        }
    }
}
