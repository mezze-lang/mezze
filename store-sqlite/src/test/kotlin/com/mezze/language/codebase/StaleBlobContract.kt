package com.mezze.language.codebase

import com.mezze.language.store.InMemoryMezzeStore
import com.mezze.language.store.MezzeStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Re-indexing a module forgets what the module no longer declares.
 *
 * Blobs are addressed by content, so a blob is never WRONG — but rows keyed
 * on one are answers. An `impl` deleted from the source kept its
 * `ability_impls` row, because that row goes only when its blob goes and no
 * blob ever went: the store went on reporting the impl to every "what
 * implements this" question, and grew by every version of every declaration
 * ever written.
 */
abstract class StaleBlobContract {

    abstract fun open(): MezzeStore

    private lateinit var store: MezzeStore

    @BeforeEach fun setUp() { store = open() }
    @AfterEach fun tearDown() { store.close() }

    private val head = """
        ability Label for 'a where { label: { it: 'a } -> Str }
        type P = { x: Int }
    """.trimIndent()

    private fun hashOf(module: String, name: String) =
        store.getDeclsForModule(module).single { it.declName == name }.hash

    @Test
    fun `a deleted impl is no longer reported`() {
        indexSource(store, "$head\nimpl Label for P where { label = { it } -> \"p\" }\nlet kept = {} -> 1")
        val impl = store.getAllAbilityImpls().single { it.module == "app" }.hash

        indexSource(store, "$head\nlet kept = {} -> 1")

        assertTrue(store.getAllAbilityImpls().none { it.hash == impl }, "the deleted impl is still listed")
        assertNull(store.getBlob(impl), "the deleted impl's blob is still stored")
    }

    @Test
    fun `a deleted function's blob goes, the kept one stays`() {
        indexSource(store, "$head\nlet gone = {} -> 41\nlet kept = {} -> 1")
        val gone = hashOf("app", "gone")
        val kept = hashOf("app", "kept")

        indexSource(store, "$head\nlet kept = {} -> 1")

        assertNull(store.getBlob(gone), "a declaration deleted from the source is still stored")
        assertNull(store.resolveName("app", "gone"), "a declaration deleted from the source still resolves")
        assertNotNull(store.getBlob(kept))
        assertEquals(kept, store.resolveName("app", "kept"))
    }

    @Test
    fun `a blob another module still declares is kept`() {
        val same = "let same = {} -> 41"
        indexSource(store, "$same\nlet a_only = {} -> 1", module = "a")
        indexSource(store, "$same\nlet b_only = {} -> 2", module = "b")
        val shared = hashOf("a", "same")
        assertEquals(shared, hashOf("b", "same"), "precondition: identical declarations share a blob")

        indexSource(store, "let a_only = {} -> 1", module = "a")

        assertNotNull(store.getBlob(shared), "module b still declares it")
    }
}

class SQLiteStaleBlobTest : StaleBlobContract() {
    override fun open(): MezzeStore = SQLiteMezzeStore.inMemory()
}

class InMemoryStaleBlobTest : StaleBlobContract() {
    override fun open(): MezzeStore = InMemoryMezzeStore()
}
