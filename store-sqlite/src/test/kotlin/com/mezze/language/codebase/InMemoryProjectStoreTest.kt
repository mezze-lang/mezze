package com.mezze.language.codebase

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * A project store worked on in memory reaches its file on close — the rows a
 * command wrote, and anything else it changed — and nothing is written when
 * nothing changed.
 */
class InMemoryProjectStoreTest {

    @TempDir
    lateinit var root: File

    private val iface = ByteArray(64) { it.toByte() }
    private val body = ByteArray(512) { (it * 3).toByte() }

    @Test
    fun `what is kept in memory is in the file after close`() {
        SQLiteMezzeStore.openProjectInMemory(root).use { it.putCheckedModule("app::a", "k1", iface, body) }
        SQLiteMezzeStore.openProject(root).use { assertArrayEquals(iface, it.getCheckedInterface("app::a", "k1")) }
    }

    @Test
    fun `a later run writes back only its rows, and they land`() {
        SQLiteMezzeStore.openProjectInMemory(root).use { it.putCheckedModule("app::a", "k1", iface, body) }
        SQLiteMezzeStore.openProjectInMemory(root).use {
            assertArrayEquals(iface, it.getCheckedInterface("app::a", "k1"), "read in from the file")
            it.putCheckedModule("app::b", "k2", body, iface)
            it.setMetadata("check-replay:x", "recorded")
            it.putExpansion(com.mezze.language.typedast.Hash("ab".repeat(64)), "in", byteArrayOf(1, 2))
        }
        SQLiteMezzeStore.openProject(root).use {
            assertArrayEquals(iface, it.getCheckedInterface("app::a", "k1"), "the earlier row survives")
            assertArrayEquals(body, it.getCheckedInterface("app::b", "k2"))
            assertEquals("recorded", it.getMetadata("check-replay:x"))
            assertArrayEquals(byteArrayOf(1, 2), it.getExpansion(com.mezze.language.typedast.Hash("ab".repeat(64)), "in"))
        }
    }

    @Test
    fun `a change to any other table writes the whole store back`() {
        SQLiteMezzeStore.openProjectInMemory(root).use { it.putCheckedModule("app::a", "k1", iface, body) }
        SQLiteMezzeStore.openProjectInMemory(root).use {
            it.putModule("app::m", "src/m.mz", "h")
            it.putCheckedModule("app::b", "k2", body, iface)
        }
        SQLiteMezzeStore.openProject(root).use {
            assertNotNull(it.getModule("app::m"), "an untracked table's write is not lost")
            assertArrayEquals(body, it.getCheckedInterface("app::b", "k2"))
        }
    }
}
