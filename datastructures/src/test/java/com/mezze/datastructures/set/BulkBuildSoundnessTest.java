package com.mezze.datastructures.set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A Map or Set built by the BULK path — {@code MezzeMap.fromEntries} /
 * {@code MezzeSet.fromElements}, which the transforms ({@code map},
 * {@code filter}, {@code flatMap}, {@code mapValues}) call — must obey the same
 * rule as one built by repeated puts: a derivation is invisible in the base.
 *
 * <p>IT EXISTED FIRST because the SmallFlat promotion was rerouted to the
 * persistent tries while the bulk path still built the open-addressed
 * {@code HashSetStorage} directly, which would have left BUG 38 reachable that
 * way. It MATTERS MORE NOW. The bulk path is a BUILDER that mutates its nodes
 * in place — safe only while nothing else can see them — so the question this
 * test asks is exactly the question an in-place builder raises: once
 * {@code build()} has handed the structure over, is it immutable like any
 * other? A builder that leaked a node, or froze one by aliasing its slot
 * arrays instead of copying them, would answer no here.
 *
 * <p>The long-element and long-key cases are separate, because the unboxed
 * tries have their own builder and one String would take the whole structure
 * off that tier.
 */
public class BulkBuildSoundnessTest {

    /**
     * Built through {@code map}, one of the transforms that goes through
     * {@code MezzeSet.fromElements} — the bulk path, as opposed to the
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

    /** The same, through the UNBOXED long-element builder. */
    @Test
    public void aBulkBuiltLongSetIsUnchangedByADerivation() {
        MezzeSet<Object> seed = MezzeSet.empty();
        for (int i = 0; i < 100; i++) seed = seed.add(Long.valueOf(i));
        MezzeSet<Object> base = seed.map(e -> Long.valueOf(((Long) e) * 10));
        assertEquals(LongTrieSetStorage.class, base.storage().getClass(),
            "the unboxed tier, or this tests the general builder twice");
        assertEquals(100, base.size());

        MezzeSet<Object> derived = base.add(Long.valueOf(99999));
        assertFalse(base.contains(Long.valueOf(99999)),
            "a set built by the long builder must not acquire a derived element");
        assertTrue(derived.contains(Long.valueOf(99999)));
        assertEquals(100, base.size());
        assertEquals(101, derived.size());
    }

    /** The map's unboxed builder, for the same reason. */
    @Test
    public void aBulkBuiltLongKeyedMapIsUnchangedByADerivation() {
        com.mezze.datastructures.map.MezzeMap<Object, Object> seed =
            com.mezze.datastructures.map.MezzeMap.empty();
        for (int i = 0; i < 100; i++) seed = seed.put(Long.valueOf(i), (long) i);
        com.mezze.datastructures.map.MezzeMap<Object, Object> base =
            seed.mapValues(v -> (Long) v * 10);
        assertEquals(com.mezze.datastructures.map.LongTrieMapStorage.class,
            base.storage().getClass(),
            "the unboxed tier, or this tests the general builder twice");

        com.mezze.datastructures.map.MezzeMap<Object, Object> grown =
            base.put(Long.valueOf(99999), 1L);
        assertNull(base.get(Long.valueOf(99999)),
            "the base must not acquire the derived key");
        assertEquals(1L, grown.get(Long.valueOf(99999)));
        assertEquals(100, base.size());
        assertEquals(101, grown.size());
    }
}
