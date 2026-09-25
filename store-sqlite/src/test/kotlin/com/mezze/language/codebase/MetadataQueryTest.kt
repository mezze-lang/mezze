package com.mezze.language.codebase

import com.mezze.language.store.*
import com.mezze.language.typedast.Hash
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Querying declarations BY their `#{ }` metadata.
 *
 * Against real SQLite on purpose: the whole question is how the driver's JSON
 * operators behave, and an in-memory fake would answer a question nobody asked.
 * `json_extract` loses type — on `{"deprecated": true}` it returns 1, not
 * `true` — so the implementation matches on `->` (JSON form) OR `->>` (SQL
 * text). These tests pin that, because getting it wrong silently returns
 * nothing rather than failing.
 *
 * The metadata strings here are exactly what StorePopulator.serializeMetadata
 * emits, so the fixtures cannot drift from the writer.
 */
class MetadataQueryTest {

    private lateinit var store: SQLiteMezzeStore

    @BeforeEach
    fun setUp() {
        store = SQLiteMezzeStore.inMemory()
        // ONCE, not per decl. putModule is INSERT OR REPLACE and decls.module_path
        // is ON DELETE CASCADE, so calling it again deletes every decl already
        // written for that module — which silently left only the last one.
        store.putModule("app", "app.mz", "srchash")
    }

    @AfterEach fun tearDown() { store.close() }

    private var counter = 0

    /** A decl carrying [metadata], with the blob row its FK needs. */
    private fun decl(name: String, metadata: String?) {
        val hash = Hash("%064x".format(++counter) + "ab")
        store.putBlob(hash, BlobKind.FUNCTION, Visibility.PUBLIC, null, null)
        store.putDecl(
            modulePath = "app", declName = name, declKind = DeclKind.LET,
            sourceHash = "srchash", hash = hash,
            startLine = 1, startCol = 1, endLine = 1, endCol = 10,
            doc = null, metadata = metadata,
        )
    }

    private fun names(rows: List<DeclRow>) = rows.map { it.declName }.sorted()

    // ---- presence ----------------------------------------------------------

    @Test
    fun `finds declarations carrying a key`() {
        decl("old_api", """{"deprecated": true, "since": "0.5.0"}""")
        decl("newer", """{"since": "0.9.0"}""")
        decl("plain", null)

        assertEquals(listOf("old_api"), names(store.findDeclsByMetadata("deprecated")))
        assertEquals(listOf("newer", "old_api"), names(store.findDeclsByMetadata("since")))
    }

    @Test
    fun `a key nothing declares finds nothing`() {
        decl("a", """{"since": "0.5.0"}""")
        assertTrue(store.findDeclsByMetadata("nonexistent").isEmpty())
    }

    @Test
    fun `declarations with no metadata are never matched`() {
        decl("plain", null)
        assertTrue(store.findDeclsByMetadata("deprecated").isEmpty())
    }

    // ---- value matching ----------------------------------------------------
    //
    // The two cases that break if only one arrow operator is used.

    @Test
    fun `matches a boolean value written as in the source`() {
        decl("gone", """{"deprecated": true}""")
        decl("staying", """{"deprecated": false}""")

        // json_extract would return 1 here, so a naive impl matching on it
        // would need the caller to pass "1". `true` is the only spelling a
        // person would try.
        assertEquals(listOf("gone"), names(store.findDeclsByMetadata("deprecated", "true")))
        assertEquals(listOf("staying"), names(store.findDeclsByMetadata("deprecated", "false")))
    }

    @Test
    fun `matches a string value unquoted`() {
        decl("a", """{"since": "0.5.0"}""")
        decl("b", """{"since": "0.9.0"}""")

        // `->` would yield "0.5.0" WITH quotes; `->>` yields it without. The
        // caller should not have to know which.
        assertEquals(listOf("a"), names(store.findDeclsByMetadata("since", "0.5.0")))
    }

    @Test
    fun `a value that matches nothing returns empty`() {
        decl("a", """{"since": "0.5.0"}""")
        assertTrue(store.findDeclsByMetadata("since", "1.0.0").isEmpty())
    }

    @Test
    fun `key presence and value match are different questions`() {
        decl("yes", """{"deprecated": true}""")
        decl("no", """{"deprecated": false}""")

        // Both CARRY the key; only one has the value.
        assertEquals(listOf("no", "yes"), names(store.findDeclsByMetadata("deprecated")))
        assertEquals(listOf("yes"), names(store.findDeclsByMetadata("deprecated", "true")))
    }

    // ---- robustness --------------------------------------------------------

    @Test
    fun `one malformed metadata row does not break the query`() {
        decl("good", """{"deprecated": true}""")
        decl("broken", """{not valid json""")

        // json_valid guards the extract. Without it SQLite errors and the
        // deprecated declaration nobody could see would be a data problem
        // masquerading as "nothing is deprecated".
        assertEquals(listOf("good"), names(store.findDeclsByMetadata("deprecated")))
    }

    @Test
    fun `a nested container value does not confuse key matching`() {
        decl("tagged", """{"tags": ["math", "pure"], "deprecated": true}""")

        assertEquals(listOf("tagged"), names(store.findDeclsByMetadata("tags")))
        assertEquals(listOf("tagged"), names(store.findDeclsByMetadata("deprecated", "true")))
    }
}
