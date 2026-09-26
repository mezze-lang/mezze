package com.mezze.language.codebase

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Opening a cache written by a different schema version must work.
 *
 * Any mismatch is a wipe-and-rebuild — caches are downstream of source, so there
 * is no migration ladder to maintain. That policy only holds if the wipe really
 * clears everything: `dropAllTables` used to work from a hand-written list of
 * table names, and the cost of forgetting an entry is not a leftover table but a
 * cache that CANNOT BE OPENED, because the survivor collides with its own
 * `CREATE TABLE` on rebuild. The list had already accumulated legacy names for
 * exactly this reason.
 *
 * Adding the signature tables was the third chance to forget. It now enumerates
 * `sqlite_master` instead, and this is the test that would have caught the
 * omission either way.
 */
class SchemaUpgradeTest {

    /** Force [db] to look like a cache from another schema version. */
    private fun stampVersion(db: File, version: Int) {
        java.sql.DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { conn ->
            conn.createStatement().use { s ->
                s.executeUpdate("UPDATE metadata SET value = '$version' WHERE key = 'schema_version'")
            }
        }
    }

    private fun tableNames(db: File): Set<String> =
        java.sql.DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { conn ->
            conn.createStatement().use { s ->
                s.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
                ).use { rs -> buildSet { while (rs.next()) add(rs.getString("name")) } }
            }
        }

    @Test
    fun `a cache from an older schema version reopens`(@TempDir tempDir: Path) {
        val root = tempDir.toFile()
        File(root, "Mezze.toml").writeText("[package]\nname = \"t\"\n")

        val dbFile: File
        SQLiteMezzeStore.forProject(root).use { store ->
            store.putModule("app", "app.mz", "sh")
            dbFile = SQLiteMezzeStore.resolveSrcFile(root)
        }
        assertTrue(dbFile.exists())

        stampVersion(dbFile, SQLiteMezzeStore.SCHEMA_VERSION - 1)

        // The reopen is the assertion: a surviving table would make initSchema
        // throw here rather than return a store.
        SQLiteMezzeStore.forProject(root).use { store ->
            assertEquals(
                SQLiteMezzeStore.SCHEMA_VERSION.toString(),
                store.getMetadata("schema_version"),
                "reopening must rebuild at the current version",
            )
            assertTrue(store.getDeclsForModule("app").isEmpty(),
                "a rebuild starts empty — the cache is downstream of source")
        }
    }

    @Test
    fun `a cache from a NEWER schema version also reopens`(@TempDir tempDir: Path) {
        // Downgrades happen — a developer switching branches runs the older
        // toolchain against a cache the newer one wrote.
        val root = tempDir.toFile()
        File(root, "Mezze.toml").writeText("[package]\nname = \"t\"\n")
        SQLiteMezzeStore.forProject(root).use { it.putModule("app", "app.mz", "sh") }

        stampVersion(SQLiteMezzeStore.resolveSrcFile(root), SQLiteMezzeStore.SCHEMA_VERSION + 99)

        SQLiteMezzeStore.forProject(root).use { store ->
            assertEquals(SQLiteMezzeStore.SCHEMA_VERSION.toString(), store.getMetadata("schema_version"))
        }
    }

    @Test
    fun `the wipe clears EVERY table, including ones added later`(@TempDir tempDir: Path) {
        // The specific failure the hand-written list invited. A stray table left
        // by an old version must not survive into the rebuilt schema, whether or
        // not anyone remembered to name it.
        val root = tempDir.toFile()
        File(root, "Mezze.toml").writeText("[package]\nname = \"t\"\n")
        SQLiteMezzeStore.forProject(root).use { it.putModule("app", "app.mz", "sh") }

        val dbFile = SQLiteMezzeStore.resolveSrcFile(root)
        java.sql.DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
            conn.createStatement().use { s ->
                s.executeUpdate("CREATE TABLE some_table_a_future_version_added (x INTEGER)")
            }
        }
        stampVersion(dbFile, SQLiteMezzeStore.SCHEMA_VERSION - 1)

        SQLiteMezzeStore.forProject(root).use { }

        assertTrue(
            "some_table_a_future_version_added" !in tableNames(dbFile),
            "the wipe must clear tables it was never told about; otherwise the next " +
                "schema change cannot create a table of that name",
        )
    }

    /**
     * The ATTACHED databases are version-checked too.
     *
     * `ensureStdlibFile` used to be `if (exists()) return`, despite its own doc
     * promising "if missing or version-mismatched". So a `stdlib.sqlite` written by
     * an older schema survived every upgrade — and silently: the attach path creates
     * a missing table EMPTY, so a newly added index returned nothing for the entire
     * stdlib with no error to notice. The project's own store was checked and
     * rebuilt; the two databases a user cannot rebuild themselves were not.
     */
    @Test
    fun `a stale attached stdlib is replaced, not accepted`(@TempDir tempDir: Path) {
        val root = tempDir.toFile()
        File(root, "Mezze.toml").writeText("[package]\nname = \"t\"\n")

        SQLiteMezzeStore.openProject(root).use { }
        val stdlib = File(root, ".mez/store/stdlib.sqlite")
        assertTrue(stdlib.exists(), "openProject must materialise a stdlib database")

        stampVersion(stdlib, SQLiteMezzeStore.SCHEMA_VERSION - 1)
        assertEquals(
            (SQLiteMezzeStore.SCHEMA_VERSION - 1).toString(),
            readVersion(stdlib),
            "the stamp must have taken, or the rest of this test proves nothing",
        )

        SQLiteMezzeStore.openProject(root).use { }

        assertEquals(
            SQLiteMezzeStore.SCHEMA_VERSION.toString(),
            readVersion(stdlib),
            "reopening must replace a stdlib database from an older schema",
        )
    }

    @Test
    fun `an unreadable attached stdlib is replaced rather than crashing`(@TempDir tempDir: Path) {
        val root = tempDir.toFile()
        File(root, "Mezze.toml").writeText("[package]\nname = \"t\"\n")
        SQLiteMezzeStore.openProject(root).use { }

        val stdlib = File(root, ".mez/store/stdlib.sqlite")
        stdlib.writeText("this is not a database")

        SQLiteMezzeStore.openProject(root).use { }
        assertEquals(SQLiteMezzeStore.SCHEMA_VERSION.toString(), readVersion(stdlib),
            "a corrupt file is not a usable store of any version")
    }

    private fun readVersion(db: File): String? =
        java.sql.DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { conn ->
            conn.createStatement().use { s ->
                s.executeQuery("SELECT value FROM metadata WHERE key = 'schema_version'")
                    .use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    /**
     * Every cross-database query must RESOLVE against a freshly attached database.
     *
     * Store queries union `main`, `stdlib` and `deps`, so a table the attach template
     * forgot fails with `no such table: deps.usages` — at runtime, and only for the
     * cross-database queries, which is the hardest place to notice. Four tables had
     * drifted out of the template that way, and the first query to union `usages`
     * discovered it.
     *
     * Behavioural rather than a schema diff: the file-backed databases get their
     * schema from their own creation path, so only the in-memory attach exercises the
     * template — which is exactly the configuration every test uses.
     */
    @Test
    fun `every cross-database query resolves against attached databases`() {
        SQLiteMezzeStore.inMemory().use { store ->
            val hash = com.mezze.language.typedast.Hash("a".repeat(64))
            // Each of these unions all three databases. Throwing is the failure; an
            // empty result is a perfectly good answer.
            store.getUsages(hash)
            store.getEdgesFrom(hash)
            store.getEdgesTo(hash)
            store.getAllEdges()
            store.transitiveEdgesFrom(hash)
            store.transitiveEdgesTo(hash)
            store.findUnreferencedDecls()
            store.getDeclsForModule("nope")
            store.searchDeclsByName("x")
            store.getDeclsByKind(com.mezze.language.store.DeclKind.LET)
            store.findDeclByHash(hash)
            store.findDeclsByMetadata("k")
            store.findDeclsByEffect("Log")
            store.findDeclsByConstraint("Ord")
            store.findDeclsByText("abc")
            store.findDeclsByText("x")           // the short-query LIKE path
            store.getSignature(hash)
            store.getResidualEffectsFor(listOf(hash))
            store.getImplTargets(hash)
            store.findAbilityImplsByName("Ord")
            store.findEffectPerformersByName("Log")
            store.getAbilityImpls(hash)
            store.getAbilityImplForType(hash, hash)
            store.getEffectPerformers(hash)
            store.getImportsForModule("nope")
            store.getImportersOf("nope", "x")
            store.getModuleExports("nope")
            store.searchExportsByName("x")
            store.getNamesForHash(hash)
            store.getApp("nope")
        }
    }

    @Test
    fun `the signature tables exist after a rebuild`(@TempDir tempDir: Path) {
        val root = tempDir.toFile()
        File(root, "Mezze.toml").writeText("[package]\nname = \"t\"\n")
        SQLiteMezzeStore.forProject(root).use { it.putModule("app", "app.mz", "sh") }
        stampVersion(SQLiteMezzeStore.resolveSrcFile(root), 1)
        SQLiteMezzeStore.forProject(root).use { }

        val tables = tableNames(SQLiteMezzeStore.resolveSrcFile(root))
        for (t in listOf("type_nodes", "type_node_effects", "type_node_constraints")) {
            assertTrue(t in tables, "$t must exist after a rebuild; have $tables")
        }
    }
}
