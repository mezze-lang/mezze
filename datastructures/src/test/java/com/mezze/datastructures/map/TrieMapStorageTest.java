package com.mezze.datastructures.map;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link TrieMapStorage} — the general-key persistent trie replacing the
 * open-addressed {@code HashMapStorage} and {@code StringHashMapStorage}.
 *
 * <p>The tests the Long version has are here too, because the properties are
 * the same. What this class needs ON TOP is collision coverage: the Long trie
 * hashes through a bijection and provably cannot collide, while this one hashes
 * {@code hashCode()} into 32 bits and genuinely can. {@link Collider} forces
 * that by returning a constant hash, so every one of its instances lands in the
 * same {@link TrieMapStorage.Collision} node — the code path no amount of
 * random testing with sensible keys would reach.
 */
public class TrieMapStorageTest {

    /**
     * A key whose hash is a CONSTANT, so distinct instances collide fully. The
     * only way to exercise collision nodes deliberately.
     */
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

    @Test
    public void emptyFindsNothing() {
        MapStorage m = new TrieMapStorage();
        assertEquals(0, m.size());
        assertNull(m.get("a"));
        assertFalse(m.containsKey("a"));
    }

    @Test
    public void stringKeysAcrossTheDepths() {
        for (int n : new int[]{1, 2, 8, 31, 32, 33, 64, 1000, 5000}) {
            MapStorage m = new TrieMapStorage();
            for (int i = 0; i < n; i++) m = m.put("key-" + i, i);
            assertEquals(n, m.size(), "size at n=" + n);
            for (int i = 0; i < n; i++) {
                assertEquals(i, m.get("key-" + i), "key-" + i + " at n=" + n);
            }
            assertNull(m.get("key-" + n), "absent at n=" + n);
        }
    }

    @Test
    public void replacingAKeyKeepsTheSize() {
        MapStorage m = new TrieMapStorage().put("a", 1).put("b", 2);
        MapStorage r = m.put("a", 99);
        assertEquals(2, r.size());
        assertEquals(99, r.get("a"));
        assertEquals(1, m.get("a"), "the base keeps its own value");
    }

    /** THE BUG 38 PROPERTY for general keys, both directions. */
    @Test
    public void derivationsAreInvisibleInTheBase() {
        MapStorage base = new TrieMapStorage();
        for (int i = 0; i < 64; i++) base = base.put("k" + i, i);

        MapStorage replaced = base.put("k30", 777);
        assertEquals(30, base.get("k30"), "replace must not reach the base");
        assertEquals(777, replaced.get("k30"));

        MapStorage inserted = base.put("fresh", 1);
        assertFalse(base.containsKey("fresh"), "insert must not reach the base");
        assertTrue(inserted.containsKey("fresh"));
        assertEquals(64, base.size());
        assertEquals(65, inserted.size());
    }

    @Test
    public void nullKeyIsAllowedLikeTheStorageItReplaces() {
        MapStorage m = new TrieMapStorage().put(null, 1).put("a", 2);
        assertEquals(2, m.size());
        assertEquals(1, m.get(null));
        assertEquals(2, m.get("a"));
        MapStorage r = m.remove(null);
        assertEquals(1, r.size());
        assertNull(r.get(null));
    }

    // ───────────────────────── collision nodes ──────────────────────

    @Test
    public void fullyCollidingKeysAllCoexist() {
        MapStorage m = new TrieMapStorage();
        for (int i = 0; i < 20; i++) m = m.put(new Collider(i), i);
        assertEquals(20, m.size());
        for (int i = 0; i < 20; i++) {
            assertEquals(i, m.get(new Collider(i)), "collider " + i);
        }
        assertNull(m.get(new Collider(999)));
    }

    @Test
    public void aCollidingKeyCanBeReplacedAndRemoved() {
        MapStorage m = new TrieMapStorage();
        for (int i = 0; i < 10; i++) m = m.put(new Collider(i), i);

        MapStorage replaced = m.put(new Collider(4), 400);
        assertEquals(10, replaced.size());
        assertEquals(400, replaced.get(new Collider(4)));
        assertEquals(4, m.get(new Collider(4)), "the base keeps its own value");

        MapStorage removed = m.remove(new Collider(4));
        assertEquals(9, removed.size());
        assertNull(removed.get(new Collider(4)));
        assertEquals(5, removed.get(new Collider(5)));
        assertEquals(4, m.get(new Collider(4)), "the base keeps the removed key");
    }

    /**
     * COLLIDERS MIXED WITH ORDINARY KEYS. A collision node has to sit happily
     * inside a bitmap level, and an ordinary key whose hash differs must not be
     * swallowed by it — that is the {@code splitCollision} path.
     */
    @Test
    public void collidersAndOrdinaryKeysCoexist() {
        MapStorage m = new TrieMapStorage();
        for (int i = 0; i < 12; i++) m = m.put(new Collider(i), i);
        for (int i = 0; i < 200; i++) m = m.put("s" + i, 1000 + i);
        assertEquals(212, m.size());
        for (int i = 0; i < 12; i++) assertEquals(i, m.get(new Collider(i)), "collider " + i);
        for (int i = 0; i < 200; i++) assertEquals(1000 + i, m.get("s" + i), "s" + i);
    }

    /** The other insertion order, which takes the split path in reverse. */
    @Test
    public void ordinaryKeysThenColliders() {
        MapStorage m = new TrieMapStorage();
        for (int i = 0; i < 200; i++) m = m.put("s" + i, 1000 + i);
        for (int i = 0; i < 12; i++) m = m.put(new Collider(i), i);
        assertEquals(212, m.size());
        for (int i = 0; i < 12; i++) assertEquals(i, m.get(new Collider(i)), "collider " + i);
        for (int i = 0; i < 200; i++) assertEquals(1000 + i, m.get("s" + i), "s" + i);
    }

    @Test
    public void removingEveryColliderEndsEmpty() {
        MapStorage m = new TrieMapStorage();
        for (int i = 0; i < 15; i++) m = m.put(new Collider(i), i);
        for (int i = 0; i < 15; i++) m = m.remove(new Collider(i));
        assertEquals(0, m.size());
        assertTrue(m.isEmpty());
    }

    // ───────────────────────────── bulk ─────────────────────────────

    @Test
    public void removeTakesOnlyTheKeyAsked() {
        MapStorage m = new TrieMapStorage();
        for (int i = 0; i < 100; i++) m = m.put("k" + i, i);
        MapStorage r = m.remove("k50");
        assertEquals(99, r.size());
        assertNull(r.get("k50"));
        assertEquals(40, r.get("k40"));
        assertEquals(50, m.get("k50"), "the base keeps the removed key");
    }

    @Test
    public void keysAndValuesEnumerateEverythingAndAgreePositionally() {
        MapStorage m = new TrieMapStorage();
        Set<Object> expected = new HashSet<>();
        for (int i = 0; i < 300; i++) {
            m = m.put("k" + i, i);
            expected.add("k" + i);
        }
        for (int i = 0; i < 10; i++) {
            m = m.put(new Collider(i), 10_000 + i);
            expected.add(new Collider(i));
        }
        Object[] ks = m.keys();
        Object[] vs = m.values();
        assertEquals(310, ks.length);
        assertEquals(310, vs.length);
        assertEquals(expected, new HashSet<>(java.util.Arrays.asList(ks)));
        for (int i = 0; i < ks.length; i++) {
            assertEquals(m.get(ks[i]), vs[i], "pair at " + i);
        }
    }

    @Test
    public void forEachVisitsEveryEntryOnce() {
        MapStorage m = new TrieMapStorage();
        for (int i = 0; i < 200; i++) m = m.put("k" + i, i);
        Map<Object, Object> seen = new HashMap<>();
        m.forEach(seen::put);
        assertEquals(200, seen.size());
        for (int i = 0; i < 200; i++) assertEquals(i, seen.get("k" + i));
    }

    /**
     * THE LOAD-BEARING TEST, with colliders in the key mix so the differential
     * exercises collision nodes too — roughly one key in six is a Collider, so
     * a run both fills and drains them while ordinary keys come and go around
     * them.
     */
    @Test
    public void randomOperationsMatchHashMap() {
        for (long seed : new long[]{1L, 42L, 20261107L, -7L}) {
            Random rnd = new Random(seed);
            Map<Object, Object> ref = new HashMap<>();
            MapStorage got = new TrieMapStorage();

            MapStorage snapshot = null;
            Map<Object, Object> snapshotRef = null;

            for (int op = 0; op < 8000; op++) {
                int n = rnd.nextInt(300);
                Object key = rnd.nextInt(6) == 0 ? new Collider(n % 25) : ("k" + n);
                if (rnd.nextInt(10) < 7) {
                    Object value = rnd.nextInt(1_000_000) + 1;   // never null
                    ref.put(key, value);
                    got = got.put(key, value);
                } else {
                    ref.remove(key);
                    got = got.remove(key);
                }
                assertEquals(ref.size(), got.size(), "size, seed " + seed + " op " + op);

                if (op == 4000) {
                    snapshot = got;
                    snapshotRef = new HashMap<>(ref);
                }
                if (op % 500 == 0) {
                    for (Map.Entry<Object, Object> e : ref.entrySet()) {
                        assertEquals(e.getValue(), got.get(e.getKey()),
                            "key " + e.getKey() + ", seed " + seed + " op " + op);
                    }
                    assertEquals(ref.size(), got.keys().length,
                        "keys() length, seed " + seed + " op " + op);
                }
            }

            assertEquals(snapshotRef.size(), snapshot.size(), "snapshot size, seed " + seed);
            for (Map.Entry<Object, Object> e : snapshotRef.entrySet()) {
                assertEquals(e.getValue(), snapshot.get(e.getKey()),
                    "snapshot key " + e.getKey() + ", seed " + seed);
            }
            for (Map.Entry<Object, Object> e : ref.entrySet()) {
                assertEquals(e.getValue(), got.get(e.getKey()),
                    "final key " + e.getKey() + ", seed " + seed);
            }
        }
    }
}
