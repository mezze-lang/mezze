package com.mezze.datastructures.map;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SmallFlatMapStorage} compares a long key AS A LONG, and must answer
 * exactly what {@code Objects.equals} answered.
 *
 * <p>WHY THE FAST PATH EXISTS. `keys` is an `Object[]`, so
 * `Objects.equals(keys[i], searchKey)` is a VIRTUAL `equals` on a receiver
 * whose type varies across the process — megamorphic, once per entry, up to
 * eight times per probe. MEASURED on `bench/cases/mapsurface`, n=1e7, probe
 * cost against the key count, and the curve had an inversion in it:
 *
 * <pre>
 *   keys  storage           contains_key   get
 *      2  SmallFlat            4 131 ps   5 239
 *      4  SmallFlat            5 714      6 718
 *      8  SmallFlat (full)     9 876     10 701
 *      9  long trie            6 005      6 992
 *     16  long trie            5 986      6 914
 * </pre>
 *
 * ~820 ps PER ENTRY, so an 8-key map probed 1.65x SLOWER than the 9-key map it
 * promotes to. Testing the search key's type once and then comparing primitives:
 *
 * <pre>
 *   keys          contains_key        get
 *      2   4 103 -> 3 095  1.33x   1.12x
 *      4   5 804 -> 3 779  1.54x   1.44x
 *      8   9 765 -> 4 783  2.04x   1.81x
 *      9 (trie, control)   1.05x   0.99x
 *     16 (trie, control)   1.00x   1.01x
 * </pre>
 *
 * The gain scales with the entry count, which is what removing a PER-ENTRY cost
 * predicts, and the trie rows this cannot touch are flat. The inversion is gone:
 * 8 keys now probes at 4 783 against the trie's ~5 900.
 *
 * <p>WHAT THIS TEST IS FOR. The fast path must not answer differently from the
 * general one, and the interesting cases are the ones where numeric equality
 * and {@code equals} disagree — a `Double` key and a `Long` key of the same
 * value are DIFFERENT keys, and the fast path must keep them so. A differential
 * against {@code HashMap} is the load-bearing case here for the same reason
 * {@link LongTrieMapStorageTest} uses one: agreement with something already
 * trusted, not hand-picked cases alone.
 */
public class SmallFlatLongKeyScanTest {

    private static MapStorage of(Object... kv) {
        MapStorage m = EmptyMapStorage.INSTANCE;
        for (int i = 0; i < kv.length; i += 2) m = m.put(kv[i], kv[i + 1]);
        return m;
    }

    @Test
    public void aLongKeyIsFoundAtEverySize() {
        for (int n = 1; n <= SmallFlatMapStorage.MAX_SIZE; n++) {
            MapStorage m = EmptyMapStorage.INSTANCE;
            for (long k = 0; k < n; k++) m = m.put(k, k * 10);
            for (long k = 0; k < n; k++) {
                assertTrue(m.containsKey(k), "size " + n + " key " + k);
                assertEquals(k * 10, m.get(k), "size " + n + " key " + k);
            }
            assertFalse(m.containsKey((long) n), "absent key at size " + n);
            assertNull(m.get((long) n), "absent key at size " + n);
        }
    }

    /**
     * A `Double` and a `Long` of the same value are DIFFERENT keys, and the
     * fast path engages only for a `Long` search key against `Long` entries —
     * so neither may find the other. This is the case a numeric comparison gets
     * wrong if it is written as `((Number) k).longValue() == s`.
     */
    @Test
    public void aDoubleKeyIsNotALongKey() {
        MapStorage m = of(1L, "long", 2.0d, "double");
        assertEquals("long", m.get(1L));
        assertEquals("double", m.get(2.0d));
        assertNull(m.get(1.0d), "1.0 must not find the entry stored under 1L");
        assertNull(m.get(2L), "2L must not find the entry stored under 2.0");
        assertFalse(m.containsKey(1.0d));
        assertFalse(m.containsKey(2L));
    }

    /** A long search key over a map whose entries are not all Longs. */
    @Test
    public void aLongKeyScansPastEntriesOfOtherTypes() {
        MapStorage m = of("a", 1L, 7L, 2L, 3.5d, 3L, Boolean.TRUE, 4L);
        assertEquals(2L, m.get(7L));
        assertTrue(m.containsKey(7L));
        assertNull(m.get(9L));
        assertFalse(m.containsKey(9L));
        // and the other key types still answer
        assertEquals(1L, m.get("a"));
        assertEquals(3L, m.get(3.5d));
        assertEquals(4L, m.get(Boolean.TRUE));
    }

    /** The values a boxed-cache shortcut would get wrong. */
    @Test
    public void theEdgesOfTheLongRange() {
        MapStorage m = of(
                Long.MIN_VALUE, "min",
                Long.MAX_VALUE, "max",
                0L, "zero",
                -1L, "minusOne",
                1000L, "big");
        assertEquals("min", m.get(Long.MIN_VALUE));
        assertEquals("max", m.get(Long.MAX_VALUE));
        assertEquals("zero", m.get(0L));
        assertEquals("minusOne", m.get(-1L));
        assertEquals("big", m.get(1000L));
        assertNull(m.get(Long.MIN_VALUE + 1));
        assertNull(m.get(Long.MAX_VALUE - 1));
        // 1000 is outside the Long.valueOf cache; a fresh box must still match.
        assertTrue(m.containsKey(Long.valueOf("1000")));
    }

    /** A replace must find the existing entry through the fast path. */
    @Test
    public void replacingAnExistingLongKeyDoesNotGrowTheMap() {
        MapStorage m = of(1L, "a", 2L, "b", 3L, "c");
        assertEquals(3, m.size());
        MapStorage m2 = m.put(2L, "B");
        assertEquals(3, m2.size());
        assertEquals("B", m2.get(2L));
        assertEquals("b", m.get(2L), "the original is unchanged");
    }

    /**
     * THE LOAD-BEARING CASE: against a map that is already trusted, over keys
     * whose types are deliberately mixed so numeric equality and `equals`
     * disagree on some of them.
     */
    @Test
    public void randomMixedKeysMatchHashMap() {
        Random rnd = new Random(20261111L);
        for (int trial = 0; trial < 200; trial++) {
            Map<Object, Object> expected = new HashMap<>();
            MapStorage actual = EmptyMapStorage.INSTANCE;
            int n = 1 + rnd.nextInt(SmallFlatMapStorage.MAX_SIZE);
            for (int i = 0; i < n; i++) {
                Object k = randomKey(rnd);
                Object v = i;
                expected.put(k, v);
                actual = actual.put(k, v);
            }
            assertEquals(expected.size(), actual.size(), "trial " + trial);
            for (int probe = 0; probe < 20; probe++) {
                Object k = randomKey(rnd);
                assertEquals(expected.containsKey(k), actual.containsKey(k),
                        "trial " + trial + " containsKey " + k + " (" + k.getClass() + ")");
                assertEquals(expected.get(k), actual.get(k),
                        "trial " + trial + " get " + k + " (" + k.getClass() + ")");
            }
            for (Object k : expected.keySet()) {
                assertTrue(actual.containsKey(k), "stored key missing: " + k);
                assertEquals(expected.get(k), actual.get(k), "stored key wrong: " + k);
            }
        }
    }

    /** Small numeric range on purpose, so Long 3 and Double 3.0 both occur. */
    private static Object randomKey(Random rnd) {
        int kind = rnd.nextInt(4);
        long v = rnd.nextInt(6);
        return switch (kind) {
            case 0 -> v;
            case 1 -> (double) v;
            case 2 -> "k" + v;
            default -> v % 2 == 0;
        };
    }
}
