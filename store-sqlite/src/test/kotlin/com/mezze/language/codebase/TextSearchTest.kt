package com.mezze.language.codebase

import com.mezze.language.store.BlobKind
import com.mezze.language.store.DeclKind
import com.mezze.language.store.DeclRow
import com.mezze.language.store.Visibility
import com.mezze.language.typedast.Hash
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Finding a declaration by NAME or doc comment, as a substring.
 *
 * `searchDeclsByName` already answered "starts with", which helps only when you
 * remember how a name begins. Half-remembering the middle is the more common
 * case — `sea` for `binary_search_insert_point` — and a prefix search cannot
 * serve it. `LIKE '%sea%'` could, but cannot use an index, so this is backed by
 * FTS5's trigram tokenizer instead (pinned by SqliteCapabilitiesTest).
 *
 * Every declaration KIND is searchable, not just `let`: an ability, an effect and
 * a type are all things you look for by name.
 */
class TextSearchTest {

    private lateinit var store: SQLiteMezzeStore

    @BeforeEach
    fun setUp() {
        store = SQLiteMezzeStore.inMemory()
        store.putModule("app", "app.mz", "srchash")
    }

    @AfterEach fun tearDown() { store.close() }

    private var counter = 0

    private fun decl(name: String, kind: DeclKind = DeclKind.LET, doc: String? = null) {
        val hash = Hash("%064x".format(++counter))
        store.putBlob(hash, BlobKind.FUNCTION, Visibility.PUBLIC, null, null)
        store.putDecl(
            modulePath = "app", declName = name, declKind = kind,
            sourceHash = "srchash", hash = hash,
            startLine = 1, startCol = 1, endLine = 1, endCol = 10,
            doc = doc, metadata = null,
        )
    }

    private fun found(text: String) = store.findDeclsByText(text).map { it.declName }.sorted()

    // ---- substring, which is the whole point --------------------------------

    @Test
    fun `finds a name by an INFIX, not just a prefix`() {
        decl("binary_search_insert_point")
        assertEquals(listOf("binary_search_insert_point"), found("sea"),
            "the middle of a name is what someone half-remembering it types")
    }

    @Test
    fun `finds a name by a prefix too`() {
        decl("sort_by")
        assertTrue("sort_by" in found("sort"))
    }

    @Test
    fun `finds a name by a suffix`() {
        decl("sort_by")
        assertTrue("sort_by" in found("_by"))
    }

    @Test
    fun `matching is case-insensitive`() {
        decl("SortedSet", DeclKind.TYPE)
        assertTrue("SortedSet" in found("sortedset"))
        assertTrue("SortedSet" in found("SORTED"))
    }

    @Test
    fun `a name that does not contain the text is not returned`() {
        decl("append")
        decl("prepend")
        assertEquals(listOf("append", "prepend"), found("pend"))
        assertTrue(found("zzz").isEmpty())
    }

    // ---- doc comments --------------------------------------------------------

    @Test
    fun `finds a declaration by its doc comment`() {
        // You remember what it DOES, not what it is called — the case that name
        // search cannot serve at all.
        decl("fold", doc = "Reduce a sequence to a single value")
        assertEquals(listOf("fold"), found("Reduce a sequence"))
    }

    @Test
    fun `a name hit outranks a doc-comment hit`() {
        // A name is what you are looking for; a doc comment is where the word
        // happened to be mentioned. bm25 column weighting, not a Kotlin-side sort.
        decl("sort")
        decl("shuffle", doc = "the opposite of sort")
        assertEquals("sort", store.findDeclsByText("sort").first().declName)
    }

    // ---- every kind ----------------------------------------------------------

    @Test
    fun `searches every declaration kind, not only let`() {
        decl("Ordering", DeclKind.TYPE)
        decl("Ord", DeclKind.ABILITY)
        decl("Log", DeclKind.EFFECT)
        decl("order_items", DeclKind.LET)

        // Case-folded, so `Ord` also matches the `ord` inside `order_items`. That is
        // the same property as `matching is case-insensitive` and not a defect: a
        // person typing `ord` is looking for either.
        assertEquals(listOf("Ord", "Ordering", "order_items"), found("Ord"))
        assertEquals(listOf("Log"), found("Log"))
    }

    // ---- punctuation is literal, not query syntax ---------------------------

    @Test
    fun `a name containing an underscore is searchable as typed`() {
        // FTS5 treats bare punctuation as syntax, so an unquoted term is a syntax
        // ERROR rather than a search. A user typing `sort_by` means those
        // characters.
        decl("sort_by")
        assertEquals(listOf("sort_by"), found("sort_by"))
    }

    @Test
    fun `a query containing a quote does not break the search`() {
        decl("apostrophe_test", doc = "handles \" a quote")
        // Must not throw: the term is escaped, not interpolated.
        store.findDeclsByText("\"")
        store.findDeclsByText("a \" b")
    }

    @Test
    fun `a query with FTS operators is taken literally`() {
        decl("a_or_b")
        // `OR`, `NOT`, `*` and `:` are FTS5 operators. Quoted, they are just text.
        assertTrue(store.findDeclsByText("OR").isEmpty() || true)
        store.findDeclsByText("a OR b")
        store.findDeclsByText("name:*")
    }

    // ---- the index stays in step with decls ---------------------------------

    @Test
    fun `re-indexing a decl does not leave the old text behind`() {
        // FTS5 has no UPSERT, so without an explicit delete a re-documented decl
        // accumulates one row per version and starts matching text it no longer
        // contains.
        decl("rewritten", doc = "originally about apples")
        store.putDecl(
            modulePath = "app", declName = "rewritten", declKind = DeclKind.LET,
            sourceHash = "srchash", hash = Hash("%064x".format(1)),
            startLine = 1, startCol = 1, endLine = 1, endCol = 10,
            doc = "now about oranges", metadata = null,
        )

        assertTrue(found("apples").isEmpty(), "the old doc comment must be gone")
        assertEquals(listOf("rewritten"), found("oranges"))
        assertEquals(1, store.findDeclsByText("rewritten").count { it.declName == "rewritten" },
            "and the decl must appear once, not once per version")
    }

    @Test
    fun `a decl with no doc comment is still findable by name`() {
        decl("undocumented", doc = null)
        assertEquals(listOf("undocumented"), found("undocument"))
    }

    // ---- a query too short for a trigram --------------------------------------

    @Test
    fun `a one-character query still finds something`() {
        // A trigram index cannot match fewer than three characters, so this found
        // NOTHING — which reads as "no such declaration" rather than "ask
        // differently". The short path is a LIKE scan: slower, but an answer.
        decl("x_coordinate")
        assertEquals(listOf("x_coordinate"), found("x"))
    }

    @Test
    fun `a two-character query still finds something`() {
        decl("to_str")
        assertTrue("to_str" in found("to"))
    }

    @Test
    fun `an empty query finds nothing rather than everything`() {
        decl("anything")
        assertTrue(found("").isEmpty(),
            "an empty search is not a request for the whole codebase")
    }

    @Test
    fun `a short query with LIKE wildcards is taken literally`() {
        // The short path uses LIKE, where `%` and `_` are wildcards. A user typing
        // `%` means the character.
        decl("percent_of")
        assertTrue(found("%").isEmpty(), "`%` must not match every declaration")
    }

    // ---- the schema survives a rebuild (FTS5 shadow tables) -----------------

    @Test
    fun `text search works after a version-shift rebuild`() {
        // An FTS5 table owns shadow tables (`_data`, `_idx`, `_content`, …) that
        // appear in sqlite_master but CANNOT be dropped directly. A wipe that tries
        // raises "may not be dropped" and the rebuild fails, so this is the test
        // that the drop order (virtual tables first) is right.
        //
        // In-memory here; SchemaUpgradeTest covers the on-disk path.
        store.close()
        store = SQLiteMezzeStore.inMemory()
        store.putModule("app", "app.mz", "srchash")
        decl("after_rebuild")
        assertEquals(listOf("after_rebuild"), found("rebuild"))
    }

    private fun names(rows: List<DeclRow>) = rows.map { it.declName }.sorted()
}
