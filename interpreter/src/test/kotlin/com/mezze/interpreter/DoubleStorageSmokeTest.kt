package com.mezze.interpreter

import com.mezze.datastructures.map.MezzeMap
import com.mezze.datastructures.map.DoubleHashMapStorage
import com.mezze.datastructures.map.StringHashMapStorage
import com.mezze.datastructures.set.MezzeSet
import com.mezze.datastructures.set.DoubleHashSetStorage
import com.mezze.datastructures.set.StringHashSetStorage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals

class DoubleStorageSmokeTest {
    @Test
    fun `Map promotes to DoubleHashMapStorage on all-Double keys`() {
        @Suppress("UNCHECKED_CAST")
        var m = MezzeMap.empty<Any?, Any?>() as MezzeMap<Any?, Any?>
        for (i in 0 until 100) m = m.putObject(i.toDouble(), (i * 2).toDouble())
        assertTrue(m.getStorage() is DoubleHashMapStorage,
            "expected DoubleHashMapStorage, got ${m.getStorage().javaClass.simpleName}")
        assertEquals(50.0 * 2, m.get(50.0))  // sanity: 50 -> 100.0
    }

    @Test
    fun `Set promotes to DoubleHashSetStorage on all-Double elements`() {
        @Suppress("UNCHECKED_CAST")
        var s = MezzeSet.empty<Any?>() as MezzeSet<Any?>
        for (i in 0 until 100) s = s.addObject(i.toDouble())
        assertTrue(s.getStorage() is DoubleHashSetStorage,
            "expected DoubleHashSetStorage, got ${s.getStorage().javaClass.simpleName}")
        for (i in 0 until 100) assertTrue(s.contains(i.toDouble()))
    }

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

    @Test
    fun `Map promotes to StringHashMapStorage on all-String keys`() {
        @Suppress("UNCHECKED_CAST")
        var m = MezzeMap.empty<Any?, Any?>() as MezzeMap<Any?, Any?>
        for (i in 0 until 100) m = m.putObject("k$i", i.toLong())
        assertTrue(m.getStorage() is StringHashMapStorage,
            "expected StringHashMapStorage, got ${m.getStorage().javaClass.simpleName}")
        assertEquals(50L, m.get("k50"))
        assertEquals(null, m.get("missing"))
    }

    @Test
    fun `Set promotes to StringHashSetStorage on all-String elements`() {
        @Suppress("UNCHECKED_CAST")
        var s = MezzeSet.empty<Any?>() as MezzeSet<Any?>
        for (i in 0 until 100) s = s.addObject("e$i")
        assertTrue(s.getStorage() is StringHashSetStorage,
            "expected StringHashSetStorage, got ${s.getStorage().javaClass.simpleName}")
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
}
