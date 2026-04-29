package com.mezze.language.codebase

import java.io.File
import java.sql.DriverManager

/**
 * Copies stdlib rows from a seed sqlite file into the project store.
 *
 * The seed is produced at build time by `StdlibPrecompiler` (gradle
 * `compileStdlib` task). It's a normal v7 sqlite with `category='stdlib'`
 * rows for stdlib modules, names, blobs, etc. Plus the metadata-soup keys
 * (prelude_import_context, stdlib_decl_hashes, performer_records, deps:*)
 * that `PrecompiledStdlib` uses to skip parse+typecheck on JVM start.
 *
 * Slice C unifies stdlib into the project cache so LSP, find-references,
 * decls, imports queries see stdlib like any other module. Before this,
 * `~/.mezze/cache/stdlib.sqlite` lived as a separate runtime file the
 * project never saw.
 *
 * Idempotent: if the project store already has any `category='stdlib'`
 * module rows, returns false without touching anything. Concurrent
 * first-open from LSP and `mez dev` is safe — the SQLite WAL serialises
 * the writes; whichever transaction wins, the other sees the rows on
 * its next check.
 */
object StdlibSeed {

    /**
     * Try to seed stdlib into [target] from the bundled seed.
     *
     * @return true if seeding ran (either copied successfully or detected
     *   no seed available), false if rows were already present.
     */
    fun seedIfNeeded(target: MezzeStore): Boolean {
        // ATTACH DATABASE only works on SQLiteMezzeStore. Other backends
        // (in-memory test stores, future S3-backed, etc.) opt out cleanly.
        val sqliteTarget = target as? SQLiteMezzeStore ?: return false
        if (hasStdlibRows(target)) return false

        val seedPath = locateSeed() ?: run {
            if (System.getenv("MEZZE_DEBUG") != null) {
                System.err.println("[StdlibSeed] no seed found; project cache will lack stdlib rows")
            }
            return false
        }

        copyFrom(seedPath, sqliteTarget)
        return true
    }

    /** Test seam — true when [target] already holds stdlib data. */
    fun hasStdlibRows(target: MezzeStore): Boolean {
        return target.getAllModules().any { it.category == ModuleCategory.STDLIB }
    }

    /**
     * Resolve the seed file. Order:
     *   1. ~/.mezze/cache/stdlib.sqlite (legacy install path; removed by
     *      slice C's install.sh changes but kept as a fallback during
     *      the transition).
     *   2. Classpath resource `stdlib.sqlite` extracted to a temp file.
     */
    private fun locateSeed(): File? {
        val installed = File(System.getProperty("user.home"), ".mezze/cache/stdlib.sqlite")
        if (installed.exists()) return installed

        val stream = StdlibSeed::class.java.classLoader.getResourceAsStream("stdlib.sqlite")
            ?: return null
        val tmp = File.createTempFile("mezze-stdlib-seed", ".sqlite")
        tmp.deleteOnExit()
        tmp.writeBytes(stream.readAllBytes())
        return tmp
    }

    /**
     * Copy every stdlib-relevant table from [seed] into [target] via
     * a single ATTACH + INSERT-SELECT batch. Wrapped in a transaction
     * so a partial failure rolls everything back.
     *
     * Tables copied:
     *   - modules (only category='stdlib' rows)
     *   - blobs (FK from names; copy all)
     *   - names (only those whose module belongs to a stdlib row)
     *   - typed_modules / parsed_modules (same filter)
     *   - decls (same filter)
     *   - edges (same filter on from_hash side)
     *   - metadata (the prelude/deps soup PrecompiledStdlib still needs)
     */
    private fun copyFrom(seed: File, target: SQLiteMezzeStore) {
        // Use the target store's connection so writes land inside the same
        // SQLite database. ATTACH creates a temporary alias for the seed.
        target.executeRaw { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate("ATTACH DATABASE '${seed.absolutePath.replace("'", "''")}' AS seed")
            }
            try {
                conn.autoCommit = false
                conn.createStatement().use { st ->
                    // Order matters because of FKs:
                    // 1. modules first (parent of names/decls/etc.)
                    st.executeUpdate(
                        "INSERT OR REPLACE INTO modules (path, file_path, source_hash, category) " +
                        "SELECT path, file_path, source_hash, category FROM seed.modules " +
                        "WHERE category = 'stdlib'"
                    )
                    // 2. blobs (target of edges.from_hash)
                    st.executeUpdate(
                        "INSERT OR REPLACE INTO blobs (hash, kind, visibility, typed, ir) " +
                        "SELECT hash, kind, visibility, typed, ir FROM seed.blobs"
                    )
                    // 3. names — copy only those whose module is a stdlib row.
                    st.executeUpdate(
                        "INSERT OR REPLACE INTO names (module, name, hash) " +
                        "SELECT n.module, n.name, n.hash FROM seed.names n " +
                        "JOIN seed.modules m ON m.path = n.module " +
                        "WHERE m.category = 'stdlib'"
                    )
                    // 4. typed_modules + parsed_modules (cached AST blobs)
                    for (table in listOf("typed_modules", "parsed_modules")) {
                        val blobCol = if (table == "typed_modules") "typed_blob" else "parsed_blob"
                        val stampCol = if (table == "typed_modules") "typed_at" else "parsed_at"
                        st.executeUpdate(
                            "INSERT OR REPLACE INTO $table (module_path, source_hash, $blobCol, $stampCol) " +
                            "SELECT t.module_path, t.source_hash, t.$blobCol, t.$stampCol " +
                            "FROM seed.$table t " +
                            "JOIN seed.modules m ON m.path = t.module_path " +
                            "WHERE m.category = 'stdlib'"
                        )
                    }
                    // 5. decls
                    st.executeUpdate(
                        "INSERT OR REPLACE INTO decls (module_path, decl_name, decl_kind, source_hash, hash, " +
                        "start_line, start_col, end_line, end_col) " +
                        "SELECT d.module_path, d.decl_name, d.decl_kind, d.source_hash, d.hash, " +
                        "d.start_line, d.start_col, d.end_line, d.end_col " +
                        "FROM seed.decls d " +
                        "JOIN seed.modules m ON m.path = d.module_path " +
                        "WHERE m.category = 'stdlib'"
                    )
                    // 6. edges — only edges originating from a hash in seed.blobs
                    //    (which we just copied wholesale).
                    st.executeUpdate(
                        "INSERT OR IGNORE INTO edges (from_hash, to_hash, kind) " +
                        "SELECT from_hash, to_hash, kind FROM seed.edges"
                    )
                    // 7. metadata — PrecompiledStdlib still reads these keys.
                    //    Skip schema_version (target's is already correct).
                    st.executeUpdate(
                        "INSERT OR REPLACE INTO metadata (key, value) " +
                        "SELECT key, value FROM seed.metadata WHERE key != 'schema_version'"
                    )
                }
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
                conn.createStatement().use { st ->
                    try { st.executeUpdate("DETACH DATABASE seed") } catch (_: Exception) {}
                }
            }
        }
    }
}
