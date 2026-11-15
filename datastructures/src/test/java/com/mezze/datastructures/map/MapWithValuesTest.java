package com.mezze.datastructures.map;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A value-only transform does not touch the keys — asserted by COUNTING.
 *
 * <p>{@link MezzeMap#mapValues} and {@code MapNatives.mapValues} both walked the
 * entries into two arrays and called {@link MezzeMap#fromEntries}, which pushes
 * every key back through a trie builder and hashes it again. The keys are
 * unchanged and already in the structure's own order, so all of that was
 * redundant — and {@code mapValues}'s own doc already claimed "the keys are
 * unchanged, so only the values are rebuilt", which its code did not do.
 *
 * <p>MEASURED on the real structure with 1000 string keys, before building it:
 * 29 302 ps/element for the rebuild against 10 925 for the structure copy,
 * 2.68x — and that is a LOWER bound, because the copy was measured through
 * reflection while the rebuild ran compiled.
 *
 * <p>WHY THESE TESTS COUNT hashCode INSTEAD OF COMPARING MAPS. The transform
 * computes the same answer either way — that is the point of it — so a test that
 * only checks the values passes with the optimisation absent. What changed is
 * that no key is hashed, and a key that counts its own {@code hashCode} calls
 * observes exactly that: zero afterwards, one per entry (at least) before.
 * Verified the usual way — with {@code mapValues} put back on
 * {@code fromEntries}, every {@code noRehash} case below fails.
 *
 * <p>The values are asserted too, in the same tests, because a structure copy
 * that shared the keys and dropped a value would satisfy the count.
 *
 * <p>NOT EVERY CASE HERE DISCRIMINATES, and which ones do was established by
 * reverting the optimisation and reading the result rather than by assuming.
 * TWO fail without it: {@code noRehashForATrie} and
 * {@code theFlatStorageTouchesNoKey}.
 *
 * <p>The rest are invariant locks, and each has a reason it cannot discriminate.
 * {@code theSingleEntryStorageTouchesNoKey} cannot, because a rebuild of a
 * one-entry map puts into the EMPTY storage, which has no existing key to hash
 * or compare — zero either way. The Long-keyed cases and
 * {@code everyValueLandsUnderItsOwnKey} cannot, because their keys are boxed
 * Longs with no counter in them; they are here to catch a structure copy that
 * loses or misplaces a value, which is the way this change could actually
 * break. Keeping them is still worth it — they would catch a storage that
 * STARTED hashing — but the file should not imply they prove today's win.
 */
class MapWithValuesTest {

    /** A key that reports how often it was hashed or compared. */
    static final class CountingKey {
        static int hashes = 0;
        static int equalsCalls = 0;
        final String name;

        CountingKey(String name) {
            this.name = name;
        }

        static void reset() {
            hashes = 0;
            equalsCalls = 0;
        }

        @Override
        public int hashCode() {
            hashes++;
            return name.hashCode();
        }

        @Override
        public boolean equals(Object o) {
            equalsCalls++;
            return o instanceof CountingKey k && name.equals(k.name);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static MezzeMap<Object, Object> build(int n) {
        Object[] keys = new Object[n];
        Object[] values = new Object[n];
        for (int i = 0; i < n; i++) {
            keys[i] = new CountingKey("key-" + i);
            values[i] = (long) i;
        }
        return MezzeMap.fromEntries(keys, values, n);
    }

    /**
     * Doubling every value hashes NO key, at a size that reaches the trie.
     *
     * <p>1000 is past {@code SmallFlatMapStorage.MAX_SIZE} and past
     * {@code LongKeyMapStorage.MAX_SIZE}, and the keys are not Longs, so this is
     * {@link TrieMapStorage} — the storage the measurement was taken on.
     */
    @Test
    void noRehashForATrie() {
        MezzeMap<Object, Object> m = build(1000);
        CountingKey.reset();
        MezzeMap<Object, Object> out = m.mapValues(v -> ((Long) v) * 2);
        assertEquals(0, CountingKey.hashes,
            "mapValues hashed keys; it should share the structure");
        assertEquals(0, CountingKey.equalsCalls,
            "mapValues compared keys; it should share the structure");
        assertEquals(1000, out.size());
    }

    /**
     * The flat storage TOUCHES NO KEY either — and here the count that matters
     * is the COMPARISONS, not the hashes.
     *
     * <p>Worth writing down, because the first version of this test asserted
     * `hashes == 0` and passed with the optimisation reverted: the flat
     * storage's `put` finds its slot by `equals`, not by hashing, so a rebuild
     * never hashed anything at this size and the assertion was vacuous. The
     * rebuild does compare, once per entry at least, so that is the count which
     * distinguishes the two.
     */
    @Test
    void theFlatStorageTouchesNoKey() {
        MezzeMap<Object, Object> m = build(3);
        CountingKey.reset();
        MezzeMap<Object, Object> out = m.mapValues(v -> ((Long) v) * 2);
        assertEquals(0, CountingKey.hashes, "the flat storage rehashed");
        assertEquals(0, CountingKey.equalsCalls, "the flat storage compared keys");
        assertEquals(3, out.size());
    }

    /** And a single entry, whose own storage also finds its slot by `equals`. */
    @Test
    void theSingleEntryStorageTouchesNoKey() {
        MezzeMap<Object, Object> m = build(1);
        Object k0 = keyNamed(m, "key-0");
        CountingKey.reset();
        MezzeMap<Object, Object> out = m.mapValues(v -> ((Long) v) + 7);
        assertEquals(0, CountingKey.hashes, "the single-entry storage rehashed");
        assertEquals(0, CountingKey.equalsCalls, "the single-entry storage compared");
        assertEquals(7L, out.get(k0));
    }

    /**
     * THE UNBOXED LONG-KEY STORAGE, which {@link MezzeMap#fromEntries} never
     * produces — it sends small maps to the flat storage and long-keyed ones to
     * the long trie — so it is reachable only through
     * {@link MezzeMap#ofLongKeys} and would otherwise have had no test at all.
     *
     * <p>Its keys are kept SORTED, and sharing the array means the sort is
     * skipped along with the hashing.
     */
    @Test
    void theUnboxedLongKeyStorageKeepsItsKeys() {
        MezzeMap<Long, Object> m = MezzeMap.ofLongKeys(
            new long[] {5L, 1L, 3L}, new Object[] {50L, 10L, 30L});
        MezzeMap<Long, Object> out = m.mapValues(v -> ((Long) v) + 1);
        assertEquals(3, out.size());
        assertEquals(51L, out.get(5L));
        assertEquals(11L, out.get(1L));
        assertEquals(31L, out.get(3L));
    }

    private static Object keyNamed(MezzeMap<Object, Object> m, String name) {
        for (Object k : m.keys()) {
            if (name.equals(k.toString())) return k;
        }
        throw new AssertionError("no key " + name);
    }

    /**
     * EVERY VALUE IS THE MAPPED ONE, and under its own key.
     *
     * <p>A structure copy that shared the keys but mis-assigned the values would
     * pass every count above. The contract `withValues` relies on is an ORDER —
     * that the array it is given lines up with {@code values()} — and this is
     * what checks the walk and the rebuild agree about it.
     */
    @Test
    void everyValueLandsUnderItsOwnKey() {
        int n = 1000;
        MezzeMap<Object, Object> m = build(n);
        MezzeMap<Object, Object> out = m.mapValues(v -> ((Long) v) * 10 + 1);

        Map<String, Long> expected = new HashMap<>();
        for (Object k : m.keys()) {
            expected.put(k.toString(), ((Long) m.get(k)) * 10 + 1);
        }
        assertEquals(n, out.size());
        for (Object k : out.keys()) {
            assertEquals(expected.get(k.toString()), out.get(k),
                "wrong value under " + k);
        }
    }

    /** A LONG-keyed map takes a different storage, and must not re-sort either. */
    @Test
    void longKeysKeepTheirOrderAndValues() {
        int n = 1000;
        Object[] keys = new Object[n];
        Object[] values = new Object[n];
        for (int i = 0; i < n; i++) {
            keys[i] = (long) i;
            values[i] = (long) i;
        }
        MezzeMap<Object, Object> m = MezzeMap.fromEntries(keys, values, n);
        MezzeMap<Object, Object> out = m.mapValues(v -> ((Long) v) + 100);
        assertEquals(n, out.size());
        for (int i = 0; i < n; i++) {
            assertEquals((long) i + 100, out.get((long) i), "wrong value at " + i);
        }
    }

    /** The small long-keyed storage, which keeps its keys SORTED. */
    @Test
    void aFewLongKeysKeepTheirValues() {
        MezzeMap<Object, Object> m = MezzeMap.fromEntries(
            new Object[] {5L, 1L, 3L}, new Object[] {50L, 10L, 30L}, 3);
        MezzeMap<Object, Object> out = m.mapValues(v -> ((Long) v) + 1);
        assertEquals(51L, out.get(5L));
        assertEquals(11L, out.get(1L));
        assertEquals(31L, out.get(3L));
        assertEquals(3, out.size());
    }

    /** An empty map maps to an empty map rather than to a null structure. */
    @Test
    void emptyStaysEmpty() {
        MezzeMap<Object, Object> m = MezzeMap.empty();
        MezzeMap<Object, Object> out = m.mapValues(v -> v);
        assertEquals(0, out.size());
        assertTrue(out.isEmpty());
    }

    /**
     * COLLIDING KEYS, which live in a {@code Collision} node rather than a
     * {@code Bitmap} — the one branch of the trie copy that has no children and
     * so is reached only by keys that agree on all 32 hash bits.
     */
    @Test
    void collidingKeysKeepTheirValues() {
        List<Object> keys = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            keys.add(new SameHash("c" + i));
            values.add((long) i);
        }
        MezzeMap<Object, Object> m = MezzeMap.fromEntries(
            keys.toArray(), values.toArray(), keys.size());
        MezzeMap<Object, Object> out = m.mapValues(v -> ((Long) v) * 3);
        assertEquals(keys.size(), out.size());
        for (int i = 0; i < keys.size(); i++) {
            assertEquals((long) i * 3, out.get(keys.get(i)), "wrong value at " + i);
        }
    }

    /** Distinct, equal-hashing keys: every one lands in one Collision node. */
    static final class SameHash {
        final String name;

        SameHash(String name) {
            this.name = name;
        }

        @Override
        public int hashCode() {
            return 7;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof SameHash s && name.equals(s.name);
        }
    }
}
