package com.mezze.datastructures.set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A Map or Set built by the BULK path — {@code fromMap} /
 * {@code fromCollection}, which the transforms ({@code map}, {@code filter},
 * {@code flatMap}, {@code mapValues}) call — must obey the same rule as one
 * built by repeated puts: a derivation is invisible in the base.
 *
 * <p>This exists because the SmallFlat promotion path was rerouted to the
 * persistent tries while the bulk path still constructs the open-addressed
 * {@code HashSetStorage} directly. If that storage's in-place add is still
 * reachable this way, BUG 38 is still reachable this way, and the fix is not as
 * complete as the routing change makes it look. This test is how that question
 * gets an answer instead of an assumption.
 */
public class BulkBuildSoundnessTest {

    /**
     * Built through {@code map}, which is one of the transforms that calls
     * {@code HashSetStorage.fromCollection} — the bulk path, as opposed to the
     * repeated-add path the SmallFlat promotion takes.
     */
    private static MezzeSet<Object> built(int n) {
        MezzeSet<Object> seed = MezzeSet.empty();
        for (int i = 0; i < n; i++) seed = seed.add("x" + i);
        return seed.map(e -> "e" + ((String) e).substring(1));
    }

    @Test
    public void aBulkBuiltSetAnswersCorrectly() {
        MezzeSet<Object> s = built(100);
        assertEquals(100, s.size());
        for (int i = 0; i < 100; i++) assertTrue(s.contains("e" + i), "e" + i);
        assertFalse(s.contains("missing"));
    }

    @Test
    public void aBulkBuiltSetIsUnchangedByADerivation() {
        MezzeSet<Object> base = built(100);
        MezzeSet<Object> derived = base.add("fresh");
        assertFalse(base.contains("fresh"),
            "a set built from a collection must not acquire a derived element");
        assertTrue(derived.contains("fresh"));
        assertEquals(100, base.size());
        assertEquals(101, derived.size());
    }

    /** The Map has the identical hole through {@code fromMap}. */
    @Test
    public void aBulkBuiltMapIsUnchangedByADerivation() {
        com.mezze.datastructures.map.MezzeMap<Object, Object> seed =
            com.mezze.datastructures.map.MezzeMap.empty();
        for (int i = 0; i < 100; i++) seed = seed.put("x" + i, (long) i);
        com.mezze.datastructures.map.MezzeMap<Object, Object> base =
            seed.mapValues(v -> (Long) v * 10);
        assertEquals(100, base.size());
        com.mezze.datastructures.map.MezzeMap<Object, Object> replaced = base.put("x50", 999L);
        com.mezze.datastructures.map.MezzeMap<Object, Object> grown = base.put("fresh", 1L);
        assertEquals(500L, base.get("x50"), "the base must keep its own value");
        assertEquals(999L, replaced.get("x50"));
        assertNull(base.get("fresh"), "the base must not acquire the derived key");
        assertEquals(1L, grown.get("fresh"));
    }

    @Test
    public void twoDerivationsFromOneBulkBuiltBaseAreIndependent() {
        MezzeSet<Object> base = built(100);
        MezzeSet<Object> a = base.add("a");
        MezzeSet<Object> b = base.add("b");
        assertTrue(a.contains("a"));
        assertFalse(a.contains("b"));
        assertTrue(b.contains("b"));
        assertFalse(b.contains("a"));
        assertFalse(base.contains("a"));
        assertFalse(base.contains("b"));
    }
}
