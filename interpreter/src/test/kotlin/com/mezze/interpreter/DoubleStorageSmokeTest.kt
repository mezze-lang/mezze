package com.mezze.interpreter

import com.mezze.datastructures.map.MezzeMap
import com.mezze.datastructures.map.TrieMapStorage
import com.mezze.datastructures.set.MezzeSet
import com.mezze.datastructures.set.TrieSetStorage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals

/**
 * Which storage a Map or Set promotes to once it outgrows `SmallFlat`, and that
 * the promoted storage answers correctly.
 *
 * WHAT CHANGED, AND WHY THE EXPECTED CLASSES MOVED. Double and String keys used
 * to promote to `DoubleHashMapStorage` and `StringHashMapStorage` — tables that
 * stored keys unboxed in a `long[]`/`String[]` and probed by open addressing.
 * Both mutated their arrays while holding an ownership token and handed the
 * token to the derived storage, so a derivation was observable in the map it
 * came from: BUG 38. The only fix is structural sharing, so they are gone and
 * both key kinds now reach `TrieMapStorage`, the general persistent trie.
 *
 * The cost is named rather than hidden: a Double key is BOXED in the trie where
 * the table held raw bits. Semantics are unaffected, because `Double.equals`
 * and `doubleToLongBits` agree on the only two cases that could differ — both
 * fold every NaN together and both keep `-0.0` apart from `0.0` — and the NaN
 * test below pins exactly that. A Float-keyed map is rare enough that a
 * Double-specialised trie was not worth carrying for it; `long` keys, which are
 * not rare, do have one (`LongTrieMapStorage`).
 *
 * So these tests still do their original job — "promotion happens and the
 * promoted storage is correct" — against the storages that exist now.
 */
class DoubleStorageSmokeTest {
    @Test
    fun `Map promotes to the trie on all-Double keys`() {
        @Suppress("UNCHECKED_CAST")
        var m = MezzeMap.empty<Any?, Any?>() as MezzeMap<Any?, Any?>
        for (i in 0 until 100) m = m.putObject(i.toDouble(), (i * 2).toDouble())
        assertTrue(m.getStorage() is TrieMapStorage,
            "expected TrieMapStorage, got ${m.getStorage().javaClass.simpleName}")
        assertEquals(50.0 * 2, m.get(50.0))  // sanity: 50 -> 100.0
    }

    @Test
    fun `Set promotes to the trie on all-Double elements`() {
        @Suppress("UNCHECKED_CAST")
        var s = MezzeSet.empty<Any?>() as MezzeSet<Any?>
        for (i in 0 until 100) s = s.addObject(i.toDouble())
        assertTrue(s.getStorage() is TrieSetStorage,
            "expected TrieSetStorage, got ${s.getStorage().javaClass.simpleName}")
        for (i in 0 until 100) assertTrue(s.contains(i.toDouble()))
    }

    /**
     * NaN canonicalisation, which is the property the move from raw bits to
     * `Double.equals` had to preserve: every NaN bit pattern is one key.
     */
    @Test
    fun `Double NaN canonicalizes`() {
        @Suppress("UNCHECKED_CAST")
        var m = MezzeMap.empty<Any?, Any?>() as MezzeMap<Any?, Any?>
        for (i in 0 until 20) m = m.putObject(i.toDouble(), i.toLong())
        m = m.putObject(Double.NaN, 999L)
        // a different NaN bit-pattern should still find the same entry
        val otherNan = java.lang.Double.longBitsToDouble(0x7ff8000000000123L)
        assertEquals(999L, m.get(otherNan))
    }

    /** The other half of that property: `-0.0` and `0.0` stay distinct keys. */
    @Test
    fun `negative zero stays a separate key from zero`() {
        @Suppress("UNCHECKED_CAST")
        var m = MezzeMap.empty<Any?, Any?>() as MezzeMap<Any?, Any?>
        for (i in 1 until 40) m = m.putObject(i.toDouble(), i.toLong())
        m = m.putObject(0.0, 100L)
        m = m.putObject(-0.0, 200L)
        assertEquals(100L, m.get(0.0))
        assertEquals(200L, m.get(-0.0))
    }

    @Test
    fun `Map promotes to the trie on all-String keys`() {
        @Suppress("UNCHECKED_CAST")
        var m = MezzeMap.empty<Any?, Any?>() as MezzeMap<Any?, Any?>
        for (i in 0 until 100) m = m.putObject("k$i", i.toLong())
        assertTrue(m.getStorage() is TrieMapStorage,
            "expected TrieMapStorage, got ${m.getStorage().javaClass.simpleName}")
        assertEquals(50L, m.get("k50"))
        assertEquals(null, m.get("missing"))
    }

    @Test
    fun `Set promotes to the trie on all-String elements`() {
        @Suppress("UNCHECKED_CAST")
        var s = MezzeSet.empty<Any?>() as MezzeSet<Any?>
        for (i in 0 until 100) s = s.addObject("e$i")
        assertTrue(s.getStorage() is TrieSetStorage,
            "expected TrieSetStorage, got ${s.getStorage().javaClass.simpleName}")
        for (i in 0 until 100) assertTrue(s.contains("e$i"))
        assertTrue(!s.contains("missing"))
    }

    @Test
    fun `Mixed key types fall back to generic storage`() {
        @Suppress("UNCHECKED_CAST")
        var m = MezzeMap.empty<Any?, Any?>() as MezzeMap<Any?, Any?>
        for (i in 0 until 8) m = m.putObject(i.toDouble(), i.toLong())  // fills SmallFlat
        m = m.putObject("a-string-key", 42L)  // forces fallback to generic
        assertEquals(42L, m.get("a-string-key"))
        assertEquals(0L, m.get(0.0))
    }

    /**
     * THE BUG 38 PROPERTY at the storage boundary these tests already sit on:
     * a promoted Map must not change when something is derived from it. The
     * same property is asserted through the language in
     * `PersistentReplaceSoundnessTest` and against the structures directly in
     * `TrieMapStorageTest`; it is here because this is the test that knows
     * which storage got chosen.
     */
    @Test
    fun `a promoted map is unchanged by a derivation`() {
        @Suppress("UNCHECKED_CAST")
        var base = MezzeMap.empty<Any?, Any?>() as MezzeMap<Any?, Any?>
        for (i in 0 until 100) base = base.putObject("k$i", i.toLong())
        assertTrue(base.getStorage() is TrieMapStorage)
        val derived = base.putObject("k50", 999L)
        val grown = base.putObject("fresh", 1L)
        assertEquals(50L, base.get("k50"), "the base keeps its own value")
        assertEquals(999L, derived.get("k50"))
        assertEquals(null, base.get("fresh"), "the base does not acquire the derived key")
        assertEquals(1L, grown.get("fresh"))
        assertEquals(100, base.size())
    }
}
