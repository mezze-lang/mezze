package com.mezze.language.codebase

import com.mezze.language.store.InMemoryMezzeStore
import com.mezze.language.store.MezzeStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * A module as last checked is kept in two parts, read apart, under the key of
 * what it was checked from; a newer one replaces it, and a new compiler wipes it.
 */
abstract class CheckedModulesContract {

    abstract fun open(): MezzeStore

    private lateinit var store: MezzeStore

    @BeforeEach fun setUp() { store = open() }
    @AfterEach fun tearDown() { store.close() }

    private val iface = ByteArray(300) { (it % 7).toByte() }
    private val body = ByteArray(5000) { (it % 13).toByte() }

    @Test
    fun `interface and body are read back apart, under their key`() {
        store.putCheckedModule("app::lib", "k1", iface, body)
        assertArrayEquals(iface, store.getCheckedInterface("app::lib", "k1"))
        assertArrayEquals(body, store.getCheckedBody("app::lib", "k1"))
        assertNull(store.getCheckedInterface("app::lib", "k2"), "another key is another module state")
        assertNull(store.getCheckedBody("app::other", "k1"))
    }

    @Test
    fun `keeping a newer one replaces the older`() {
        store.putCheckedModule("app::lib", "k1", iface, body)
        store.putCheckedModule("app::lib", "k2", body, iface)
        assertNull(store.getCheckedInterface("app::lib", "k1"))
        assertArrayEquals(body, store.getCheckedInterface("app::lib", "k2"))
    }

    @Test
    fun `a new compiler wipes them`() {
        store.putCheckedModule("app::lib", "k1", iface, body)
        store.wipeTypecheckCaches()
        assertNull(store.getCheckedInterface("app::lib", "k1"))
    }
}

class SQLiteCheckedModulesTest : CheckedModulesContract() {
    override fun open(): MezzeStore = SQLiteMezzeStore.inMemory()
}

class InMemoryCheckedModulesTest : CheckedModulesContract() {
    override fun open(): MezzeStore = InMemoryMezzeStore()
}
