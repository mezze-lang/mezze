package com.mezze.language.codebase

import com.mezze.language.store.BlobConflict
import com.mezze.language.store.InMemoryMezzeStore
import com.mezze.language.store.MezzeStore
import com.mezze.language.store.StoreSerialization
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * A hash names one declaration, and the store holds it to that.
 *
 * Writing a declaration again — from another file, further down, in another
 * run — is the ordinary case and changes only where it was written. Writing a
 * DIFFERENT declaration under a hash that is taken used to succeed silently,
 * last writer winning; what a reader then got under that hash depended on who
 * had written most recently.
 */
abstract class BlobConflictContract {

    abstract fun open(): MezzeStore

    private lateinit var store: MezzeStore

    @BeforeEach fun setUp() { store = open() }
    @AfterEach fun tearDown() { store.close() }

    private val source = """
        let twice = { x: Int } -> x * 2
        let thrice = { x: Int } -> x * 3
    """.trimIndent()

    private fun hashOf(s: MezzeStore, name: String) = s.resolveName("app", name)!!

    @Test
    fun `the same declaration written again is accepted, and says where it now is`() {
        indexSource(store, source, parsedAs = "/home/one/app.mz")
        val twice = hashOf(store, "twice")

        val moved = InMemoryMezzeStore().also { indexSource(it, "\n\n\n$source", parsedAs = "/srv/elsewhere/app.mz") }
        val again = moved.getBlob(hashOf(moved, "twice"))!!
        assertEquals(twice, again.hash, "moving a declaration changed its identity")
        store.putBlob(twice, again.kind, again.visibility, again.typed, again.ir)

        val read = StoreSerialization.deserializeTypedLetDecl(store.getBlob(twice)!!.typed!!)
        assertEquals("/srv/elsewhere/app.mz", read.loc.file)
        assertEquals(4, read.loc.startLine)
    }

    @Test
    fun `a different typed form under a taken hash is refused`() {
        indexSource(store, source)
        val twice = store.getBlob(hashOf(store, "twice"))!!
        val thrice = store.getBlob(hashOf(store, "thrice"))!!

        val refused = assertThrows(BlobConflict::class.java) {
            store.putBlob(twice.hash, twice.kind, twice.visibility, thrice.typed, null)
        }
        assertEquals(twice.hash, refused.hash)
        assertEquals("typed", refused.part)
        assertTrue(
            StoreSerialization.sameContent(twice.typed!!, store.getBlob(twice.hash)!!.typed!!),
            "the refused write replaced what was stored",
        )
    }

    @Test
    fun `a different lowered form under a taken hash is refused`() {
        indexSource(store, source)
        val twice = store.getBlob(hashOf(store, "twice"))!!
        val thrice = store.getBlob(hashOf(store, "thrice"))!!
        assertTrue(twice.ir != null && thrice.ir != null, "nothing was lowered")

        val refused = assertThrows(BlobConflict::class.java) {
            store.putBlob(twice.hash, twice.kind, twice.visibility, twice.typed, thrice.ir)
        }
        assertEquals("lowered", refused.part)
        assertTrue(StoreSerialization.sameContent(twice.ir!!, store.getIR(twice.hash)!!), "the refused write replaced what was stored")
    }

    /**
     * The check at work on what a build writes. `{ x } -> x` over `Int` and over
     * `Str` lower to different functions; while a lambda's hash did not cover its
     * types they shared one, and the second module's was skipped as "already
     * stored". With every skipped declaration verified (`mezze.store.verify`, set
     * for every test JVM) that is a [BlobConflict] — so indexing both is the test.
     */
    @Test
    fun `two modules whose lambdas read alike but lower differently both index`() {
        val use = "use std::collections::seq\n\n"
        indexSource(store, use + "let f = { xs: [Int] } -> seq::map { xs = xs, f = { x } -> x }", module = "ints")
        indexSource(store, use + "let f = { xs: [Str] } -> seq::map { xs = xs, f = { x } -> x }", module = "strs")
        assertTrue(hashOf2("ints") != hashOf2("strs"))
    }

    private fun hashOf2(module: String) = store.resolveName(module, "f")!!

    @Test
    fun `a form given as null makes no claim`() {
        indexSource(store, source)
        val twice = store.getBlob(hashOf(store, "twice"))!!
        // The lowered form is merged onto the typed one this way: same typed bytes, the IR arriving later.
        store.putBlob(twice.hash, twice.kind, twice.visibility, twice.typed, null)
        store.putBlob(twice.hash, twice.kind, twice.visibility, twice.typed, twice.ir)
        assertTrue(StoreSerialization.sameContent(twice.ir!!, store.getIR(twice.hash)!!))
    }
}

class SQLiteBlobConflictTest : BlobConflictContract() {
    override fun open(): MezzeStore = SQLiteMezzeStore.inMemory()
}

class InMemoryBlobConflictTest : BlobConflictContract() {
    override fun open(): MezzeStore = InMemoryMezzeStore()
}
