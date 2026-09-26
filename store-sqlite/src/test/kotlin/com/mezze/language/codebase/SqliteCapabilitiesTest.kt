package com.mezze.language.codebase

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.DriverManager

/**
 * What the BUNDLED SQLite can actually do.
 *
 * Text search over names and doc comments needs FTS5, and fuzzy substring matching
 * needs FTS5's trigram tokenizer. Both are compile-time options in SQLite, so
 * whether they exist is a property of the driver we ship — not something to assume
 * from the version number and then discover in the field.
 *
 * These tests exist so the assumption is checked by CI rather than by a user whose
 * search silently returns nothing. If a driver upgrade ever drops one of these,
 * this fails here instead of in a feature built on top of it.
 */
class SqliteCapabilitiesTest {

    private fun <T> withDb(block: (java.sql.Connection) -> T): T =
        DriverManager.getConnection("jdbc:sqlite::memory:").use(block)

    @Test
    fun `the bundled SQLite has FTS5`() {
        withDb { conn ->
            conn.createStatement().use { s ->
                s.executeUpdate("CREATE VIRTUAL TABLE probe USING fts5(body)")
                s.executeUpdate("INSERT INTO probe(body) VALUES ('sorted sequence of elements')")
                val hits = s.executeQuery("SELECT COUNT(*) FROM probe WHERE probe MATCH 'sequence'")
                    .use { rs -> rs.next(); rs.getInt(1) }
                assertEquals(1, hits, "FTS5 MATCH must find a whole word")
            }
        }
    }

    @Test
    fun `FTS5 has the trigram tokenizer, which is what makes substrings findable`() {
        // The default tokenizer matches whole words, so `MATCH 'quen'` finds nothing
        // in "sequence". Trigram indexing is what makes `binary_sea` find
        // `binary_search_insert_point` — the pg_trgm equivalent, and the reason a
        // name search can be forgiving rather than exact.
        withDb { conn ->
            conn.createStatement().use { s ->
                s.executeUpdate("CREATE VIRTUAL TABLE probe USING fts5(name, tokenize='trigram')")
                s.executeUpdate("INSERT INTO probe(name) VALUES ('binary_search_insert_point')")
                val hits = s.executeQuery("SELECT COUNT(*) FROM probe WHERE probe MATCH 'sea'")
                    .use { rs -> rs.next(); rs.getInt(1) }
                assertEquals(1, hits, "a trigram index must match an infix, not only a prefix")
            }
        }
    }

    @Test
    fun `the default tokenizer does NOT match infixes`() {
        // The boundary that justifies the trigram table: this is what you get
        // without it, and it is why `LIKE '%...%'` was the only other option.
        withDb { conn ->
            conn.createStatement().use { s ->
                s.executeUpdate("CREATE VIRTUAL TABLE probe USING fts5(name)")
                s.executeUpdate("INSERT INTO probe(name) VALUES ('binary_search_insert_point')")
                val hits = s.executeQuery("SELECT COUNT(*) FROM probe WHERE probe MATCH 'sea'")
                    .use { rs -> rs.next(); rs.getInt(1) }
                assertEquals(0, hits)
            }
        }
    }

    @Test
    fun `FTS5 ranks results, so a name search can order by relevance`() {
        withDb { conn ->
            conn.createStatement().use { s ->
                s.executeUpdate("CREATE VIRTUAL TABLE probe USING fts5(body)")
                s.executeUpdate("INSERT INTO probe(body) VALUES ('sort a sequence')")
                s.executeUpdate("INSERT INTO probe(body) VALUES ('sort sort sort')")
                val first = s.executeQuery(
                    "SELECT body FROM probe WHERE probe MATCH 'sort' ORDER BY rank"
                ).use { rs -> rs.next(); rs.getString(1) }
                assertTrue(first.isNotEmpty(), "bm25 ranking must be available via `rank`")
            }
        }
    }

    // ---- what is NOT available, and the escape hatch that is ----------------
    //
    // These record the SEARCH-extension landscape of the bundled driver, measured
    // rather than assumed. They assert ABSENCE on purpose: if one starts failing, a
    // driver upgrade has opened an option that is currently closed, and that is
    // worth noticing rather than discovering by accident.

    @Test
    fun `spellfix1 is NOT bundled, so did-you-mean cannot be done in SQL`() {
        // SQLite's own fuzzy-matching extension (spellfix1, and its `editdist3`
        // function) lives in ext/misc and is not compiled into this build. The
        // consequence is not "no fuzzy matching" — it is that fuzzy matching happens
        // in Kotlin (`LevenshteinSuggest`, which the compiler's diagnostics already
        // use) instead of in a query.
        withDb { conn ->
            for (sql in listOf(
                "CREATE VIRTUAL TABLE probe USING spellfix1",
                "SELECT editdist3('kitten', 'sitting')",
            )) {
                val failed = try {
                    conn.createStatement().use { it.execute(sql) }; false
                } catch (_: Exception) { true }
                assertTrue(failed, "spellfix1 appears to be available now: $sql")
            }
        }
    }

    @Test
    fun `REGEXP is not provided by SQLite itself`() {
        // SQLite defines the REGEXP operator's SYNTAX but ships no implementation —
        // it is a hook for the host program. So a regex search over names needs a
        // registered function, not an extension.
        withDb { conn ->
            val failed = try {
                conn.createStatement().use { it.executeQuery("SELECT 'abc' REGEXP 'b'") }; false
            } catch (_: Exception) { true }
            assertTrue(failed, "REGEXP is implemented now — a regex name search became possible")
        }
    }

    @Test
    fun `loadable extensions are compiled in but NOT authorized`() {
        // ENABLE_LOAD_EXTENSION is in the compile options, yet `load_extension()`
        // returns "not authorized": the JDBC driver blocks it at the authorizer
        // unless explicitly enabled. So a third-party search extension
        // (sqlite-vec for vector search, say) is not simply a dependency — it needs
        // enabling AND a platform-specific binary shipped beside a native image.
        withDb { conn ->
            val failed = try {
                conn.createStatement().use { it.executeQuery("SELECT load_extension('probe')") }; false
            } catch (_: Exception) { true }
            assertTrue(failed, "extension loading is permitted now")
        }
    }

    @Test
    fun `a custom function CAN be registered, which is the way round every gap above`() {
        // The escape hatch, verified: anything SQLite lacks (edit distance, regexp,
        // a domain-specific ranker) can be a Kotlin function registered on the
        // connection. No .so, no per-platform artifact.
        withDb { conn ->
            org.sqlite.Function.create(conn, "mezze_capability_probe", object : org.sqlite.Function() {
                override fun xFunc() { result("ok") }
            })
            val v = conn.createStatement().use { s ->
                s.executeQuery("SELECT mezze_capability_probe()").use { rs -> rs.next(); rs.getString(1) }
            }
            assertEquals("ok", v)
        }
    }

    @Test
    fun `json1 is available, which the metadata queries already rely on`() {
        // P-2's metadata search is built on this. Pinned alongside the others so the
        // set of SQLite features this store depends on is stated in one place.
        withDb { conn ->
            conn.createStatement().use { s ->
                val v = s.executeQuery("SELECT json_extract('{\"a\":1}', '$.a')")
                    .use { rs -> rs.next(); rs.getInt(1) }
                assertEquals(1, v)
            }
        }
    }
}
