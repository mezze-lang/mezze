package com.mezze.datastructures.sorted;

import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The ordered structures hand arrays to other structures rather than copying
 * them, in two places, and this is the probe for the defect that creates.
 *
 * <p>A REPLACE changes no key, so {@code putObject} shares the keys array with
 * the map it derives from — the whole point, since a chain of replaces ran 264
 * young collections where the {@code size} row over the same process shape ran
 * 3. {@code withComparator} was already sharing BOTH arrays, and its result
 * escapes: {@code removeObject} of an absent key and {@code addObject} of a
 * present element both {@code return this}, so the sharing structure itself
 * reaches the caller.
 *
 * <p>The danger is not the share. It is that the structure sharing the array
 * might still hold the right to SHIFT it in place: {@code putObject} has a
 * middle-insert path that does {@code arraycopy(keys, p, keys, p+1, …)} inside
 * the window the other map reads. The append path is harmless by
 * ADDRESSABILITY — it writes at index {@code length}, which no map sharing the
 * array can address — and that distinction is the one BUG 38 turned on.
 *
 * <p>PREMISE, and it is the whole difficulty of testing this: the in-place
 * paths need a structure that OWNS its arrays AND has spare capacity in them,
 * and ANY read revokes ownership. So a test that touches the base before the
 * write it is trying to catch proves nothing while looking exactly like one
 * that does — the mistake a one-entry hash map test made earlier. Nothing here
 * calls {@code get} on a base; {@code size}, {@code keys} and
 * {@code getKeysInternal} are used instead because they do not revoke.
 */
public class SharedArraySoundnessTest {

    private static final Object[] EVENS_TO_20 =
            {0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20};

    /**
     * 11 keys, built by ascending puts and never read. Growth lands capacity at
     * 19 for length 11, so the base owns its arrays with room to shift inside
     * them — the only state the in-place paths can fire from.
     */
    private static MezzeSortedMap<Integer, String> ownedMapWithSpare() {
        MezzeSortedMap<Integer, String> m = MezzeSortedMap.empty();
        for (int i = 0; i <= 20; i += 2) m = m.put(i, "v" + i);
        return m;
    }

    private static MezzeSortedSet<Integer> ownedSetWithSpare() {
        MezzeSortedSet<Integer> s = MezzeSortedSet.empty();
        for (int i = 0; i <= 20; i += 2) s = s.add(i);
        return s;
    }

    // ─────────────────── the share itself, which is the win ───────────────────

    @Test
    public void aReplaceHandsOverTheKeysArrayRatherThanCopyingIt() {
        MezzeSortedMap<Integer, String> base = ownedMapWithSpare();
        Object[] baseKeys = base.getKeysInternal();

        MezzeSortedMap<Integer, String> replaced = base.put(4, "CHANGED");

        assertSame(baseKeys, replaced.getKeysInternal(),
                "a replace changes no key, so it must share the array, not copy it");
        assertNotSame(base.getValuesInternal(), replaced.getValuesInternal(),
                "the values array is the one thing a replace does change");
    }

    @Test
    public void aReplaceIsVisibleInTheNewMapAndNotInTheBase() {
        MezzeSortedMap<Integer, String> base = ownedMapWithSpare();
        MezzeSortedMap<Integer, String> replaced = base.put(4, "CHANGED");

        assertEquals("CHANGED", replaced.get(4));
        assertEquals("v4", base.get(4));
        assertEquals(11, base.size());
        assertEquals(11, replaced.size());
    }

    // ──────────────── what sharing would cost if it were not frozen ───────────

    @Test
    public void aMiddleInsertCannotShiftKeysSharedWithAReplacedMap() {
        MezzeSortedMap<Integer, String> base = ownedMapWithSpare();
        assertTrue(base.getKeysInternal().length > base.size(),
                "premise: spare capacity, or the in-place path is unreachable "
                        + "and this test cannot fail for the right reason");

        MezzeSortedMap<Integer, String> replaced = base.put(4, "CHANGED");
        base.put(5, "v5");   // insertPos 3 of 11 — the middle-shift path

        assertArrayEquals(EVENS_TO_20, replaced.keys(),
                "the shared keys were shifted underneath the replaced map");
        assertEquals(11, replaced.size());
        assertEquals("CHANGED", replaced.get(4));
    }

    @Test
    public void theBaseItselfSurvivesAReplaceFollowedByAnInsert() {
        MezzeSortedMap<Integer, String> base = ownedMapWithSpare();
        base.put(4, "CHANGED");
        MezzeSortedMap<Integer, String> inserted = base.put(5, "v5");

        assertArrayEquals(EVENS_TO_20, base.keys());
        assertEquals(11, base.size());
        assertEquals("v4", base.get(4));

        assertEquals(12, inserted.size());
        assertEquals("v5", inserted.get(5));
        assertEquals("v4", inserted.get(4));
    }

    @Test
    public void chainedReplacesDoNotAliasOneAnother() {
        // All four maps share ONE keys array, so a write into it shows up in
        // every one of them at once.
        MezzeSortedMap<Integer, String> m0 = ownedMapWithSpare();
        MezzeSortedMap<Integer, String> m1 = m0.put(4, "one");
        MezzeSortedMap<Integer, String> m2 = m1.put(4, "two");
        MezzeSortedMap<Integer, String> m3 = m2.put(8, "three");

        for (MezzeSortedMap<Integer, String> m : List.of(m0, m1, m2, m3)) {
            assertArrayEquals(EVENS_TO_20, m.keys());
            assertEquals(11, m.size());
        }
        assertEquals("v4", m0.get(4));
        assertEquals("one", m1.get(4));
        assertEquals("two", m2.get(4));
        assertEquals("two", m3.get(4));
        assertEquals("v8", m2.get(8));
        assertEquals("three", m3.get(8));
    }

    // ──────────────────────── withComparator's share ──────────────────────────

    @Test
    public void aMiddleInsertCannotShiftArraysHandedOverByWithComparator() {
        MezzeSortedMap<Integer, String> base = ownedMapWithSpare();
        assertTrue(base.getKeysInternal().length > base.size(), "premise: spare capacity");

        // An EQUIVALENT ordering: this method is a field swap for installing a
        // Mezze-derived comparator on a naturally-ordered map, so a genuinely
        // different order would invalidate the array rather than test anything.
        MezzeSortedMap<Integer, String> viaCmp = base.withComparator(Comparator.naturalOrder());
        assertSame(base.getKeysInternal(), viaCmp.getKeysInternal(), "premise: keys handed over");
        assertSame(base.getValuesInternal(), viaCmp.getValuesInternal(), "premise: values handed over");

        base.put(5, "v5");   // the middle-shift path, on both arrays

        assertArrayEquals(EVENS_TO_20, viaCmp.keys());
        assertArrayEquals(
                new Object[]{"v0", "v2", "v4", "v6", "v8", "v10", "v12", "v14", "v16", "v18", "v20"},
                viaCmp.values());
        assertEquals(11, viaCmp.size());
    }

    @Test
    public void aMiddleInsertCannotShiftTheArrayASetHandedOver() {
        // The set has no internal-array accessor to assert the premise with —
        // its growth is the map's, and the sentinel run is what proves this
        // test can fail.
        MezzeSortedSet<Integer> base = ownedSetWithSpare();

        MezzeSortedSet<Integer> viaCmp = base.withComparator(Comparator.naturalOrder());
        base.add(5);   // insertPos 3 of 11 — the middle-shift path

        assertArrayEquals(EVENS_TO_20, viaCmp.toArray());
        assertEquals(11, viaCmp.size());
    }

    @Test
    public void aSetsAddOfAPresentElementReturnsSomethingTheBaseCannotDisturb() {
        // `addObject` returns `this` for an element already there, so the set
        // that escapes IS the one sharing the base's array.
        MezzeSortedSet<Integer> base = ownedSetWithSpare();

        MezzeSortedSet<Integer> escaped = base.withComparator(Comparator.naturalOrder()).add(4);
        base.add(5);

        assertArrayEquals(EVENS_TO_20, escaped.toArray());
        assertEquals(11, escaped.size());
    }

    // ──────── the in-place middle shift, with no sharing in the picture ───────

    @Test
    public void aMapIsNotChangedByInsertingIntoIt() {
        // NO replace, NO withComparator, nothing shared: one owning map and one
        // insert that lands in the middle of it. If this fails, the in-place
        // middle-shift path is writing inside the base's OWN read window, and
        // the share is not what broke it.
        MezzeSortedMap<Integer, String> base = ownedMapWithSpare();
        assertTrue(base.getKeysInternal().length > base.size(), "premise: spare capacity");

        MezzeSortedMap<Integer, String> inserted = base.put(5, "v5");

        assertArrayEquals(EVENS_TO_20, base.keys(), "the base saw its own insert");
        assertEquals(11, base.size());
        assertEquals(12, inserted.size());
    }

    @Test
    public void aSetIsNotChangedByAddingToIt() {
        MezzeSortedSet<Integer> base = ownedSetWithSpare();
        MezzeSortedSet<Integer> added = base.add(5);

        assertArrayEquals(EVENS_TO_20, base.toArray(), "the base saw its own add");
        assertEquals(11, base.size());
        assertEquals(12, added.size());
    }
}
