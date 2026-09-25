package com.mezze.language.codebase

import com.mezze.language.store.*
import com.mezze.language.typedast.Hash
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Storing and querying the signature index.
 *
 * The columns exist so that a search does not have to deserialize a whole
 * typed-module blob per candidate — which is not a search. These tests cover
 * the storage and query half; SignatureNormalizerTest covers what a canonical
 * rendering IS.
 */
class SignatureIndexTest {

    private lateinit var store: SQLiteMezzeStore

    @BeforeEach
    fun setUp() {
        store = SQLiteMezzeStore.inMemory()
        // Once — putModule is INSERT OR REPLACE and decls.module_path cascades,
        // so calling it per decl deletes everything written before it.
        store.putModule("app", "app.mz", "srchash")
    }

    @AfterEach fun tearDown() { store.close() }

    private var counter = 0

    private fun decl(
        name: String,
        signature: String? = null,
        effects: String? = null,
        constraints: String? = null,
    ) {
        val hash = Hash("%064x".format(++counter) + "ab")
        store.putBlob(hash, BlobKind.FUNCTION, Visibility.PUBLIC, null, null)
        store.putDecl(
            modulePath = "app", declName = name, declKind = DeclKind.LET,
            sourceHash = "srchash", hash = hash,
            startLine = 1, startCol = 1, endLine = 1, endCol = 10,
            doc = null, metadata = null,
            signature = signature, effects = effects, constraints = constraints,
        )
    }

    private fun names(rows: List<DeclRow>) = rows.map { it.declName }.sorted()

    // ---- round-trip --------------------------------------------------------

    @Test
    fun `signature, effects and constraints survive a round trip`() {
        decl("shout", "{ msg: Str } -> {} <Log>", """["Log"]""", null)

        val row = store.getDeclsForModule("app").single()
        assertEquals("{ msg: Str } -> {} <Log>", row.signature)
        assertEquals("""["Log"]""", row.effects)
        assertNull(row.constraints)
    }

    @Test
    fun `a decl indexed without a type has null signature columns`() {
        // Type/ability/effect declarations have no signature to index. Null
        // rather than empty string, so "no signature" is distinguishable from
        // "the empty signature".
        decl("plain")

        val row = store.getDeclsForModule("app").single()
        assertNull(row.signature)
        assertNull(row.effects)
    }

    // ---- effect search -----------------------------------------------------

    @Test
    fun `finds declarations by effect`() {
        decl("shout", "{ msg: Str } -> {} <Log>", """["Log"]""")
        decl("read", "{ p: Str } -> Str <FileSystem>", """["FileSystem"]""")
        decl("pure_fn", "{ x: Int } -> Int", null)

        assertEquals(listOf("shout"), names(store.findDeclsByEffect("Log")))
        assertEquals(listOf("read"), names(store.findDeclsByEffect("FileSystem")))
    }

    @Test
    fun `a declaration with several effects is found by each`() {
        decl("both", "{ p: Str } -> Str <FileSystem, Log>", """["FileSystem", "Log"]""")

        assertEquals(listOf("both"), names(store.findDeclsByEffect("Log")))
        assertEquals(listOf("both"), names(store.findDeclsByEffect("FileSystem")))
    }

    /**
     * The reason effects are stored as JSON rather than a delimited string:
     * `LIKE '%Log%'` would match `Logger` too, and a search that quietly
     * over-matches is worse than one that finds nothing, because the caller
     * cannot tell.
     */
    @Test
    fun `effect matching is exact, not substring`() {
        decl("logger_user", "{ x: Int } -> Int <Logger>", """["Logger"]""")

        assertTrue(store.findDeclsByEffect("Log").isEmpty(),
            "`Log` must not match the effect `Logger`")
        assertEquals(listOf("logger_user"), names(store.findDeclsByEffect("Logger")))
    }

    @Test
    fun `pure declarations are never returned by an effect search`() {
        decl("pure_fn", "{ x: Int } -> Int", null)
        assertTrue(store.findDeclsByEffect("Log").isEmpty())
    }

    @Test
    fun `an effect nothing performs finds nothing`() {
        decl("shout", "{ msg: Str } -> {} <Log>", """["Log"]""")
        assertTrue(store.findDeclsByEffect("Nonexistent").isEmpty())
    }
}
