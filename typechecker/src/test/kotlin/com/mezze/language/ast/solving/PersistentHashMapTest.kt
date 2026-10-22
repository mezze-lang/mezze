package com.mezze.language.ast.solving

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class PersistentHashMapTest {
    /** Keys with a tiny hash space, so collisions and deep tries both happen. */
    private data class Key(val id: Int, val bucket: Int) {
        override fun hashCode() = bucket
    }

    @Test
    fun `agrees with a HashMap over random insertions, and old versions are unchanged`() {
        val random = Random(42)
        var p = PersistentHashMap.empty<Key, Int>()
        val reference = HashMap<Key, Int>()
        val snapshots = mutableListOf<Pair<PersistentHashMap<Key, Int>, Map<Key, Int>>>()
        repeat(20_000) { i ->
            val key = Key(random.nextInt(3000), random.nextInt(if (i % 2 == 0) 4 else Int.MAX_VALUE))
            p = p.plus(key, i)
            reference[key] = i
            if (i % 997 == 0) snapshots += p to HashMap(reference)
        }
        assertEquals(reference.size, p.size)
        assertEquals(reference, p)
        assertEquals(reference.keys, p.keys)
        for ((k, v) in reference) assertEquals(v, p[k])
        assertTrue(Key(-1, 1) !in p)
        for ((snap, ref) in snapshots) assertEquals(ref, snap, "an earlier version saw a later change")
    }

    @Test
    fun `plusAll lets the argument win, and of a persistent map is itself`() {
        val base = PersistentHashMap.of(mapOf("a" to 1, "b" to 2))
        assertEquals(mapOf("a" to 1, "b" to 3, "c" to 4), base.plusAll(mapOf("b" to 3, "c" to 4)))
        assertTrue(PersistentHashMap.of(base) === base)
    }
}
