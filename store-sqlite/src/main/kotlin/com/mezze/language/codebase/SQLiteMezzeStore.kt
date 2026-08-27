package com.mezze.language.codebase

import com.mezze.language.store.*

import com.mezze.language.typedast.Hash
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * SQLite-backed implementation of [MezzeStore].
 *
 * Used for:
 * - Project mode: `.mez/store/src.sqlite` (read-write, full schema)
 * - Dependencies: `.mez/store/deps.sqlite` (read-only)
 * - JAR embedded: `blobs.sqlite` (read-only, IR-only)
 * - Tests: `:memory:`
 *
 * Thread safety: every public override (and `executeRaw`) is `@Synchronized`
 * on the instance. A single JDBC connection (and its cached PreparedStatements)
 * is not thread-safe by spec, and concurrent LSP requests would otherwise hit
 * "ResultSet already requested" from sqlite-jdbc when two threads share the
 * same statement object. WAL mode helps for inter-process concurrency, not
 * within a single connection.
 */
class SQLiteMezzeStore private constructor(
    private val conn: Connection,
    private val readOnly: Boolean = false
) : MezzeStore {

    // ====================================================================
    // L1 IN-MEMORY CACHES — sit in front of every SQLite read.
    // Populated on first access; writes go to both L1 and SQLite.
    // ====================================================================
    private val l1Blobs = HashMap<String, BlobRow>(256)            // hash.hex → BlobRow
    private val l1IR = HashMap<String, ByteArray?>(256)            // hash.hex → IR bytes
    private val l1Names = HashMap<String, Hash?>(512)              // "mod\0name" → Hash
    private val l1Modules = HashMap<String, ModuleRow?>(64)        // path → ModuleRow
    private val l1ParsedModules = HashMap<String, ByteArray?>(64)  // "path\0hash" → blob
    private val l1TypedModules = HashMap<String, ByteArray?>(64)   // "path\0hash" → blob

    private fun nameKey(module: String, name: String) = "$module\u0000$name"
    private fun cacheKey(path: String, hash: String) = "$path\u0000$hash"

    /**
     * Run a block with the underlying JDBC connection. Internal seam used
     * by `StdlibSeed.copyFrom` to issue ATTACH DATABASE / multi-table
     * INSERT-SELECT batches that don't fit the typed API. Public callers
     * should always go through the typed methods.
     */
    @Synchronized
    internal fun <T> executeRaw(block: (Connection) -> T): T = block(conn)

    /**
     * Build a UNION ALL across all three attached databases for a single
     * select template. The placeholder `<DB>` is substituted with each of
     * `main`, `stdlib`, `deps` in turn.
     *
     * Example:
     *   unionAll("SELECT * FROM <DB>.modules WHERE path = ?")
     * yields:
     *   SELECT * FROM main.modules WHERE path = ?
     *   UNION ALL
     *   SELECT * FROM stdlib.modules WHERE path = ?
     *   UNION ALL
     *   SELECT * FROM deps.modules WHERE path = ?
     *
     * Caller is responsible for binding `paramCount * 3` parameters.
     */
    private fun unionAll(template: String): String =
        listOf("main", DB_STDLIB, DB_DEPS).joinToString(" UNION ALL ") {
            template.replace("<DB>", it)
        }

    /**
     * Escape LIKE-special characters (`%`, `_`, `\`) in user input. The
     * matching SQL must use `LIKE ... ESCAPE '\\'`. Without this, a user
     * query containing `_` in an identifier matches any char in that
     * position, returning extra rows.
     */
    private fun escapeLikePattern(raw: String): String =
        raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    companion object {
        /**
         * Schema version. We've reset to baseline alongside the three-file
         * split — pre-1.0, the v0..v7 migration ladder doesn't carry users
         * forward; old caches get detected as version-mismatched, dropped,
         * and regenerated from source. See
         * `docs/architecture/17-three-file-cache.md`.
         */
        // v5: hash-driven TypeIdentity (Stage D) — MezzeTypeHasher now encodes a
        // nominal type's identity via a discriminant-tagged appendIdentity(), which
        // shifts the content-hash bytes for every nominal type. Old caches are
        // dropped and regenerated.
        //
        // #312 (5→6): TypeApplication no longer hashes its `constructor` string for
        // Declared/Builtin identities — the name is already in appendIdentity, and
        // constructor is enforced to mirror it (Type.kt init{}). Only Anonymous
        // still hashes constructor (its sole name signal). Content-hash bytes shift
        // for every named TypeApplication; old caches dropped and regenerated.
        const val SCHEMA_VERSION = 7

        // Paths and env override are owned by [com.mezze.language.store.ProjectLayout].
        // SQLiteMezzeStore exposes only thin pass-throughs so the rest of
        // the SQLite code can stay self-contained, and so any tooling
        // that grabbed the constants off this object historically keeps
        // working. New callers should depend on ProjectLayout directly.
        const val SRC_FILE = com.mezze.language.store.ProjectLayout.SRC_FILE
        const val STDLIB_FILE = com.mezze.language.store.ProjectLayout.STDLIB_FILE
        const val DEPS_FILE = com.mezze.language.store.ProjectLayout.DEPS_FILE

        /** SQLite ATTACH aliases. `main` is src; stdlib + deps attach over it. */
        const val DB_STDLIB = "stdlib"
        const val DB_DEPS = "deps"

        fun resolveSrcFile(projectRoot: File): File =
            com.mezze.language.store.ProjectLayout.srcFile(projectRoot)

        fun resolveStdlibFile(projectRoot: File): File =
            com.mezze.language.store.ProjectLayout.stdlibFile(projectRoot)

        fun resolveDepsFile(projectRoot: File): File =
            com.mezze.language.store.ProjectLayout.depsFile(projectRoot)

        /**
         * Wipe all three sqlite files (and their `-wal`/`-shm` siblings)
         * for a project. Caller must have closed any open store first.
         *
         * Used by `mezze.clearCache` in the LSP and as the user-facing
         * escape hatch when a cache file is corrupted. Next `openProject`
         * recreates everything from scratch (and re-seeds stdlib).
         */
        fun wipeProjectFiles(projectRoot: File) {
            val dir = com.mezze.language.store.ProjectLayout.storeDir(projectRoot)
            if (!dir.isDirectory) return
            for (base in listOf(SRC_FILE, STDLIB_FILE, DEPS_FILE)) {
                for (suffix in listOf("", "-wal", "-shm", "-journal")) {
                    File(dir, base + suffix).delete()
                }
            }
        }

        /**
         * Open or create project cache. Note: this does NOT attach
         * stdlib / deps. For the full three-file flow use [openProject].
         * `forProject` is only used by tooling that needs raw access to
         * a single sqlite (StdlibPrecompiler writes to one isolated
         * file, the bundled JAR target stores its data in another).
         */
        fun forProject(projectRoot: File): SQLiteMezzeStore {
            val dbFile = resolveSrcFile(projectRoot)
            dbFile.parentFile?.mkdirs()
            val conn = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")
            return SQLiteMezzeStore(conn, readOnly = false).also {
                it.configurePragmas()
                it.initSchema()
                it.attachInMemoryAux()
            }
        }

        /**
         * Single canonical entry point for opening a project store.
         *
         * Three-file design (see docs/architecture/17-three-file-cache.md):
         *  1. Ensure the `.mez/store/` directory exists.
         *  2. Materialise stdlib.sqlite by copying the bundled JAR resource
         *     if missing or version-mismatched. Read-only after.
         *  3. Materialise an empty deps.sqlite if missing (`mez sync` is
         *     the only writer; until then it's a placeholder so ATTACH
         *     and UNION ALL never fail).
         *  4. Open src.sqlite as the primary connection, apply baseline
         *     schema if missing.
         *  5. ATTACH stdlib.sqlite AS stdlib, deps.sqlite AS deps.
         *  6. Install the parser-cache hook so ModuleResolver consults
         *     parsed_modules before invoking the parser (slice A).
         */
        fun openProject(projectRoot: File): SQLiteMezzeStore {
            val srcFile = resolveSrcFile(projectRoot)
            val stdlibFile = resolveStdlibFile(projectRoot)
            val depsFile = resolveDepsFile(projectRoot)

            srcFile.parentFile?.mkdirs()
            ensureStdlibFile(stdlibFile)
            ensureDepsFile(depsFile)

            val conn = DriverManager.getConnection("jdbc:sqlite:${srcFile.absolutePath}")
            val store = SQLiteMezzeStore(conn, readOnly = false)
            store.configurePragmas()
            store.initSchema()
            store.attachAuxDb(DB_STDLIB, stdlibFile)
            store.attachAuxDb(DB_DEPS, depsFile)
            // Both attached files were created in advance by ensureStdlib /
            // ensureDeps, but defensive schema apply ensures they have the
            // expected tables — important when a stale or partially-written
            // file is on disk.
            store.applyBaselineSchemaTo(DB_STDLIB)
            store.applyBaselineSchemaTo(DB_DEPS)

            com.mezze.language.parser.ParsedModuleCacheHolder.set(
                StoreParsedModuleCache(store)
            )
            return store
        }

        /**
         * Copy the bundled stdlib seed into the project's `.mez/store/`
         * directory if the file is missing. The bundle is shipped as a
         * JAR classpath resource; we extract it once per project.
         *
         * Replaces today's INSERT-SELECT seed copy. The bundled file IS
         * the project's stdlib.sqlite from second one — same schema,
         * same blobs, same metadata.
         */
        private fun ensureStdlibFile(stdlibFile: File) {
            if (stdlibFile.exists()) return
            stdlibFile.parentFile?.mkdirs()
            val stream = SQLiteMezzeStore::class.java.classLoader
                .getResourceAsStream(STDLIB_FILE)
            if (stream == null) {
                // No bundled seed — create an empty stdlib.sqlite with
                // the schema applied so ATTACH + UNION ALL still work.
                // Happens in dev builds before the stdlib precompile has
                // run; PrecompiledStdlib's runtime path covers the gap.
                createEmptyDb(stdlibFile)
                return
            }
            stream.use { it.copyTo(stdlibFile.outputStream()) }
        }

        /**
         * Ensure `deps.sqlite` exists so ATTACH and UNION ALL queries
         * work even when the project has no external dependencies.
         * `mez sync` is the only writer; until then this stays as an
         * empty schema'd file.
         */
        private fun ensureDepsFile(depsFile: File) {
            if (depsFile.exists()) return
            depsFile.parentFile?.mkdirs()
            createEmptyDb(depsFile)
        }

        /** Materialise an empty SQLite file with our baseline schema. */
        private fun createEmptyDb(file: File) {
            val conn = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
            try {
                val store = SQLiteMezzeStore(conn, readOnly = false)
                store.configurePragmas()
                store.initSchema()
            } finally {
                conn.close()
            }
        }

        /**
         * Open read-write store at a specific file path. Used by tooling
         * (StdlibPrecompiler) that writes a self-contained sqlite. We
         * attach in-memory empty stdlib + deps so that the UNION ALL
         * read paths still work — they just hit empty aux tables.
         */
        fun forFile(path: File): SQLiteMezzeStore {
            path.parentFile?.mkdirs()
            val conn = DriverManager.getConnection("jdbc:sqlite:${path.absolutePath}")
            return SQLiteMezzeStore(conn, readOnly = false).also {
                it.configurePragmas()
                it.initSchema()
                it.attachInMemoryAux()
            }
        }

        /**
         * Open read-only store (for deps.sqlite, JAR blobs.sqlite, bundled
         * stdlib.sqlite).
         *
         * Attaches in-memory empty stdlib + deps aux dbs because every read
         * path UNION-ALLs across `main`, `stdlib`, `deps`. Without the aux
         * attaches, queries against a single-file store fail with
         * `no such table: stdlib.blobs` / `no such table: deps.blobs`.
         * The aux dbs stay empty — the data lives in `main` — but the
         * schema needs to exist so the UNION ALL parses and runs.
         */
        fun readOnly(path: File): SQLiteMezzeStore {
            val conn = DriverManager.getConnection("jdbc:sqlite:file:${path.absolutePath}?mode=ro")
            return SQLiteMezzeStore(conn, readOnly = true).also {
                it.attachInMemoryAux()
            }
        }

        /**
         * Create in-memory store (for tests). Always attaches in-memory
         * stdlib + deps databases too, so UNION ALL queries against the
         * three scopes don't fail with "no such table." Tests that need
         * stdlib data can write into `stdlib.tablename` directly via
         * the typed API (writes still default to `main`).
         */
        fun inMemory(): SQLiteMezzeStore {
            val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
            return SQLiteMezzeStore(conn, readOnly = false).also {
                it.configurePragmas()
                it.initSchema()
                it.attachInMemoryAux()
            }
        }

        /** Check if project has an existing src store (location resolved via [resolveSrcFile]). */
        fun hasCache(projectRoot: File): Boolean =
            resolveSrcFile(projectRoot).exists()

        /** Find project root by walking up from a path looking for Mezze.toml. */
        fun findProjectRoot(startPath: File): File? {
            var current = if (startPath.isFile) startPath.parentFile else startPath
            while (current != null) {
                if (File(current, "Mezze.toml").exists()) return current
                current = current.parentFile
            }
            return null
        }
    }

    // ====================================================================
    // SCHEMA
    // ====================================================================

    private fun configurePragmas() {
        conn.createStatement().use { s ->
            s.executeUpdate("PRAGMA journal_mode=WAL")
            s.executeUpdate("PRAGMA synchronous=NORMAL")
            s.executeUpdate("PRAGMA temp_store=MEMORY")
            s.executeUpdate("PRAGMA cache_size=-64000")   // 64MB
            s.executeUpdate("PRAGMA mmap_size=268435456")  // 256MB
            // FKs ON (v7+): every hash-pointing column has REFERENCES
            // declared. Orphan rows can't sneak in; ON DELETE CASCADE
            // does natural cleanup when modules / apps are dropped.
            s.executeUpdate("PRAGMA foreign_keys=ON")
            // Wait up to 5s for a contended write lock instead of failing
            // immediately with SQLITE_BUSY. Required when LSP and `mez dev`
            // write to the same cache.sqlite concurrently.
            s.executeUpdate("PRAGMA busy_timeout=5000")
        }
    }

    private fun initSchema() {
        // The metadata table holds schema_version; we have to ensure it
        // exists before we can read the current version. (CREATE TABLE
        // IF NOT EXISTS is idempotent; safe to run on every open.)
        conn.createStatement().use { s ->
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS metadata (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                ) WITHOUT ROWID
            """.trimIndent())
        }

        val currentVersion = readSchemaVersion()
        when {
            currentVersion == 0 -> applyBaselineSchema()
            currentVersion != SCHEMA_VERSION -> {
                // Any mismatch — older OR newer — is a wipe-and-rebuild.
                // Caches are downstream of source; the next mez dev
                // re-typechecks. No migration ladder.
                dropAllTables()
                applyBaselineSchema()
            }
            // currentVersion == SCHEMA_VERSION: schema is current.
        }
        writeSchemaVersion(SCHEMA_VERSION)
    }

    /**
     * ATTACH an auxiliary SQLite database (stdlib or deps) over the open
     * cache connection. Once attached, `<alias>.tablename` lets us read
     * across files via UNION ALL queries.
     */
    private fun attachAuxDb(alias: String, file: File) {
        val safePath = file.absolutePath.replace("'", "''")
        conn.createStatement().use { s ->
            s.executeUpdate("ATTACH DATABASE '$safePath' AS $alias")
        }
    }

    /**
     * Attach in-memory empty stdlib + deps databases. Used by tests
     * (`inMemory()`) and any caller that opens a project store
     * without the full three-file setup. Each gets its own schema
     * applied so UNION ALL across `main`, `stdlib`, `deps` always
     * resolves cleanly even when the auxiliary DBs are empty.
     */
    private fun attachInMemoryAux() {
        for (alias in listOf(DB_STDLIB, DB_DEPS)) {
            conn.createStatement().use { s ->
                s.executeUpdate("ATTACH DATABASE ':memory:' AS $alias")
            }
            applyBaselineSchemaTo(alias)
        }
    }

    /**
     * Apply baseline schema to an attached database (other than the
     * primary `main`). Mirrors [applyBaselineSchema] but writes every
     * table with the `<alias>.` prefix.
     */
    private fun applyBaselineSchemaTo(alias: String) {
        conn.createStatement().use { s ->
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS $alias.metadata (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                ) WITHOUT ROWID
            """.trimIndent())
            // Modules. Cache validity is purely sha256-based; if the file's
            // current sha256 matches source_hash, the cache is valid.
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS $alias.modules (
                    path TEXT PRIMARY KEY,
                    file_path TEXT,
                    source_hash TEXT NOT NULL
                ) WITHOUT ROWID
            """.trimIndent())
            // Blobs
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS $alias.blobs (
                    hash TEXT PRIMARY KEY,
                    kind TEXT NOT NULL,
                    visibility TEXT NOT NULL,
                    typed BLOB,
                    ir BLOB,
                    created_at INTEGER NOT NULL DEFAULT 0
                ) WITHOUT ROWID
            """.trimIndent())
            // Names — no FKs across attached DBs.
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS $alias.names (
                    module TEXT NOT NULL,
                    name TEXT NOT NULL,
                    hash TEXT NOT NULL,
                    PRIMARY KEY (module, name)
                ) WITHOUT ROWID
            """.trimIndent())
            // Decls
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS $alias.decls (
                    module_path TEXT NOT NULL,
                    decl_name TEXT NOT NULL,
                    decl_kind TEXT NOT NULL,
                    source_hash TEXT NOT NULL,
                    hash TEXT NOT NULL,
                    start_line INTEGER NOT NULL,
                    start_col INTEGER NOT NULL,
                    end_line INTEGER NOT NULL,
                    end_col INTEGER NOT NULL,
                    doc TEXT,
                    metadata TEXT,
                    PRIMARY KEY (module_path, decl_kind, decl_name)
                ) WITHOUT ROWID
            """.trimIndent())
            // Imports
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS $alias.imports (
                    module_path TEXT NOT NULL,
                    local_name TEXT NOT NULL,
                    target_module TEXT NOT NULL,
                    target_name TEXT NOT NULL,
                    target_hash TEXT,
                    start_line INTEGER NOT NULL,
                    start_col INTEGER NOT NULL,
                    end_line INTEGER NOT NULL,
                    end_col INTEGER NOT NULL,
                    PRIMARY KEY (module_path, local_name)
                ) WITHOUT ROWID
            """.trimIndent())
            // typed_modules + parsed_modules
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS $alias.typed_modules (
                    module_path TEXT NOT NULL,
                    source_hash TEXT NOT NULL,
                    typed_blob BLOB NOT NULL,
                    typed_at INTEGER NOT NULL,
                    PRIMARY KEY (module_path, source_hash)
                ) WITHOUT ROWID
            """.trimIndent())
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS $alias.parsed_modules (
                    module_path TEXT NOT NULL,
                    source_hash TEXT NOT NULL,
                    parsed_blob BLOB NOT NULL,
                    parsed_at INTEGER NOT NULL,
                    PRIMARY KEY (module_path, source_hash)
                ) WITHOUT ROWID
            """.trimIndent())
            // edges + ability_impls + effect_performers
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS $alias.edges (
                    from_hash TEXT NOT NULL,
                    to_hash TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    PRIMARY KEY (from_hash, to_hash, kind)
                ) WITHOUT ROWID
            """.trimIndent())
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS $alias.ability_impls (
                    hash TEXT PRIMARY KEY,
                    ability_hash TEXT NOT NULL,
                    target_type TEXT NOT NULL,
                    module TEXT
                ) WITHOUT ROWID
            """.trimIndent())
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS $alias.effect_performers (
                    hash TEXT PRIMARY KEY,
                    effect_hash TEXT NOT NULL,
                    performer_name TEXT NOT NULL,
                    module TEXT
                ) WITHOUT ROWID
            """.trimIndent())
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS $alias.mono_specializations (
                    specialized_hash TEXT PRIMARY KEY,
                    generic_hash     TEXT NOT NULL,
                    type_args_cbor   BLOB NOT NULL
                ) WITHOUT ROWID
            """.trimIndent())
        }
    }

    private fun readSchemaVersion(): Int = try {
        conn.createStatement().use { s ->
            s.executeQuery("SELECT value FROM metadata WHERE key = 'schema_version'").use { rs ->
                if (rs.next()) rs.getString(1).toIntOrNull() ?: 0 else 0
            }
        }
    } catch (_: Exception) { 0 }

    private fun writeSchemaVersion(version: Int) {
        conn.createStatement().use { s ->
            s.executeUpdate("INSERT OR REPLACE INTO metadata (key, value) VALUES ('schema_version', '$version')")
        }
    }

    /**
     * Drop every non-metadata table. Called when the schema_version
     * doesn't match the current baseline. Caches are downstream of
     * source files; mez dev rebuilds them on next run.
     *
     * FKs disabled during the drop because the v7 schema declared
     * REFERENCES; turning them off lets us drop in arbitrary order.
     * The catch-all list below covers every table we've ever created
     * — older legacy names included so v6/v7 caches both go away.
     */
    private fun dropAllTables() {
        conn.createStatement().use { s ->
            s.executeUpdate("PRAGMA foreign_keys=OFF")
            for (table in listOf(
                "app_performers", "app_functions", "apps",
                "mono_specializations",
                "usages", "references_",
                "imports", "decls", "edges", "names",
                "effect_performers", "ability_impls",
                "parsed_modules", "typed_modules",
                "modules", "blobs"
                // metadata kept — schema_version write follows.
            )) {
                s.executeUpdate("DROP TABLE IF EXISTS $table")
            }
            s.executeUpdate("PRAGMA foreign_keys=ON")
        }
    }

    /**
     * Create the full schema from scratch. Same DDL applied to all three
     * files (cache, stdlib, deps); each owns its own scope, so there's
     * no `category` column — the file you're querying IS the scope.
     *
     * Highlights:
     *  - foreign_keys=ON; every hash-pointing column has REFERENCES declared
     *  - CHECK constraints on every enum-like column
     *  - ON DELETE CASCADE on natural-cleanup paths
     */
    private fun applyBaselineSchema() {
        conn.createStatement().use { s ->
            // ----------------------------------------------------------------
            // FILES & MODULES
            // ----------------------------------------------------------------
            s.executeUpdate("""
                -- modules: every Mezze source file this DB knows about.
                -- Keyed by namespaced module path (not raw filename).
                -- Cache validity: sha256(current file bytes) == source_hash.
                CREATE TABLE modules (
                    path        TEXT PRIMARY KEY,
                    file_path   TEXT,
                    source_hash TEXT NOT NULL
                ) WITHOUT ROWID
            """.trimIndent())

            // ----------------------------------------------------------------
            // BLOBS — content-addressed storage for typed AST and lowered IR
            // ----------------------------------------------------------------
            s.executeUpdate("""
                -- blobs: two cells share one row — `typed` holds the per-decl
                -- TypedDecl, `ir` holds the lowered FunctionIR. The hash is
                -- the same identity across both forms.
                CREATE TABLE blobs (
                    hash       TEXT PRIMARY KEY,
                    kind       TEXT NOT NULL CHECK (kind IN ('FUNCTION','TYPE','ABILITY','ABILITY_IMPL','EFFECT','PERFORMER')),
                    visibility TEXT NOT NULL CHECK (visibility IN ('PUBLIC','PRIVATE')),
                    typed      BLOB,
                    ir         BLOB,
                    created_at INTEGER NOT NULL DEFAULT 0
                ) WITHOUT ROWID
            """.trimIndent())

            // ----------------------------------------------------------------
            // AST CACHES — parsed_modules, typed_modules
            // ----------------------------------------------------------------
            s.executeUpdate("""
                -- parsed_modules: cached ParsedAST per file. The CBOR blob
                -- includes ALL comments — leading, trailing, orphan, both
                -- regular # and doc ##. LSP and mez fmt skip parse on cold
                -- cache.
                CREATE TABLE parsed_modules (
                    module_path TEXT NOT NULL REFERENCES modules(path) ON DELETE CASCADE,
                    source_hash TEXT NOT NULL,
                    parsed_blob BLOB NOT NULL,
                    parsed_at   INTEGER NOT NULL,
                    PRIMARY KEY (module_path, source_hash)
                ) WITHOUT ROWID
            """.trimIndent())
            s.executeUpdate("CREATE INDEX idx_parsed_modules_path ON parsed_modules(module_path)")

            s.executeUpdate("""
                -- typed_modules: cached TypedAST per file. Skips whole-module
                -- typecheck on cold start when source hasn't changed.
                CREATE TABLE typed_modules (
                    module_path TEXT NOT NULL REFERENCES modules(path) ON DELETE CASCADE,
                    source_hash TEXT NOT NULL,
                    typed_blob  BLOB NOT NULL,
                    typed_at    INTEGER NOT NULL,
                    PRIMARY KEY (module_path, source_hash)
                ) WITHOUT ROWID
            """.trimIndent())
            s.executeUpdate("CREATE INDEX idx_typed_modules_path ON typed_modules(module_path)")

            // ----------------------------------------------------------------
            // DECLS — per-declaration index; the keystone for incremental
            // typecheck and the symbol index for LSP.
            // ----------------------------------------------------------------
            s.executeUpdate("""
                -- decls: per-decl source ranges + content hash. source_hash is
                -- the SHA-256 of THIS decl's source-text range (distinct from
                -- the file-level source_hash). `hash` matches blobs.hash —
                -- joins read naturally as `decls JOIN blobs USING (hash)`.
                -- hash not FK'd; the decl row may be registered before the
                -- blob is fully written (typed first, then IR).
                CREATE TABLE decls (
                    module_path TEXT NOT NULL REFERENCES modules(path) ON DELETE CASCADE,
                    decl_name   TEXT NOT NULL,
                    decl_kind   TEXT NOT NULL CHECK (decl_kind IN ('let','type','ability','effect','performer','impl')),
                    source_hash TEXT NOT NULL,
                    hash        TEXT NOT NULL,
                    start_line  INTEGER NOT NULL,
                    start_col   INTEGER NOT NULL,
                    end_line    INTEGER NOT NULL,
                    end_col     INTEGER NOT NULL,
                    doc         TEXT,
                    metadata    TEXT,
                    PRIMARY KEY (module_path, decl_kind, decl_name)
                ) WITHOUT ROWID
            """.trimIndent())
            s.executeUpdate("CREATE INDEX idx_decls_source_hash ON decls(source_hash)")
            s.executeUpdate("CREATE INDEX idx_decls_hash ON decls(hash)")
            s.executeUpdate("CREATE INDEX idx_decls_kind_name ON decls(decl_kind, decl_name)")

            // ----------------------------------------------------------------
            // LOOKUPS & CROSS-REFS
            // ----------------------------------------------------------------
            s.executeUpdate("""
                -- names: module-qualified name -> blob hash.
                -- hash not FK'd: a name registration may precede the blob
                -- write within the same transaction (StorePopulator orders
                -- this way for typed-only-then-IR), and FK enforcement runs
                -- per-statement.
                CREATE TABLE names (
                    module TEXT NOT NULL REFERENCES modules(path) ON DELETE CASCADE,
                    name   TEXT NOT NULL,
                    hash   TEXT NOT NULL,
                    PRIMARY KEY (module, name)
                ) WITHOUT ROWID
            """.trimIndent())
            s.executeUpdate("CREATE INDEX idx_names_hash ON names(hash)")

            s.executeUpdate("""
                -- edges: dependency graph between decls. Used for invalidation
                -- propagation and `mez build` reachability.
                -- edges: dependency graph between decls. Used for invalidation
                -- propagation and `mez build` reachability.
                --
                -- FK on from_hash but NOT on to_hash. Reason: edges may reference
                -- a target blob that hasn't been written yet (e.g., user code calls
                -- a stdlib function before stdlib is fully scattered, or two files
                -- compile in parallel). Dangling to_hash values are tolerated and
                -- "snap" into validity when the target eventually appears.
                CREATE TABLE edges (
                    from_hash TEXT NOT NULL REFERENCES blobs(hash) ON DELETE CASCADE,
                    to_hash   TEXT NOT NULL,
                    kind      TEXT NOT NULL CHECK (kind IN ('CALLS','REFERENCES','IMPLEMENTS')),
                    PRIMARY KEY (from_hash, to_hash, kind)
                ) WITHOUT ROWID
            """.trimIndent())
            s.executeUpdate("CREATE INDEX idx_edges_to ON edges(to_hash)")

            s.executeUpdate("""
                -- imports: every `use` declaration across the project.
                -- target_hash is NOT a FK (imported decl may live in another
                -- file not yet scattered) and is NULL when unresolved.
                CREATE TABLE imports (
                    module_path   TEXT NOT NULL REFERENCES modules(path) ON DELETE CASCADE,
                    local_name    TEXT NOT NULL,
                    target_module TEXT NOT NULL,
                    target_name   TEXT NOT NULL,
                    target_hash   TEXT,
                    start_line    INTEGER NOT NULL,
                    start_col     INTEGER NOT NULL,
                    end_line      INTEGER NOT NULL,
                    end_col       INTEGER NOT NULL,
                    PRIMARY KEY (module_path, local_name)
                ) WITHOUT ROWID
            """.trimIndent())
            s.executeUpdate("CREATE INDEX idx_imports_target ON imports(target_module, target_name)")

            s.executeUpdate("""
                -- usages: every place a hash is referenced from another decl.
                -- Powers LSP textDocument/references in O(1). target_hash is
                -- NOT a FK because the target may be cross-module / not yet
                -- in the cache. clearUsagesForFile handles cleanup.
                CREATE TABLE usages (
                    target_hash TEXT NOT NULL,
                    file_path   TEXT NOT NULL,
                    start_line  INTEGER NOT NULL,
                    start_col   INTEGER NOT NULL,
                    end_line    INTEGER NOT NULL,
                    end_col     INTEGER NOT NULL
                )
            """.trimIndent())
            s.executeUpdate("CREATE INDEX idx_usages_target ON usages(target_hash)")
            s.executeUpdate("CREATE INDEX idx_usages_file ON usages(file_path)")

            // ----------------------------------------------------------------
            // DISPATCH REGISTRIES — ability impls, effect performers
            // ----------------------------------------------------------------
            s.executeUpdate("""
                -- ability_impls: ability impl registry for runtime dispatch.
                -- ability_hash is NOT a FK because the ability decl may live in
                -- another file/module not yet scattered. Same pattern as edges.
                CREATE TABLE ability_impls (
                    hash         TEXT PRIMARY KEY REFERENCES blobs(hash) ON DELETE CASCADE,
                    ability_hash TEXT NOT NULL,
                    target_type  TEXT NOT NULL,
                    module       TEXT
                ) WITHOUT ROWID
            """.trimIndent())
            s.executeUpdate("CREATE INDEX idx_ability_impls_ability ON ability_impls(ability_hash)")
            s.executeUpdate("CREATE INDEX idx_ability_impls_type ON ability_impls(target_type)")

            s.executeUpdate("""
                -- effect_performers: performer registry per effect.
                -- effect_hash is NOT a FK; same reasoning as edges.
                CREATE TABLE effect_performers (
                    hash           TEXT PRIMARY KEY REFERENCES blobs(hash) ON DELETE CASCADE,
                    effect_hash    TEXT NOT NULL,
                    performer_name TEXT NOT NULL,
                    module         TEXT
                ) WITHOUT ROWID
            """.trimIndent())
            s.executeUpdate("CREATE INDEX idx_effect_performers_effect ON effect_performers(effect_hash)")

            // ----------------------------------------------------------------
            // MONO SPECIALIZATIONS — provenance for monomorphizer outputs.
            // Read by tools (debugger frame labels, LSP go-to-def) to map
            // a specialized blob back to the generic decl it specializes.
            // Never on the execution hot path. specialized_hash FK'd so
            // dropping a blob naturally cleans up its provenance row.
            // ----------------------------------------------------------------
            s.executeUpdate("""
                CREATE TABLE mono_specializations (
                    specialized_hash TEXT PRIMARY KEY REFERENCES blobs(hash) ON DELETE CASCADE,
                    generic_hash     TEXT NOT NULL,
                    type_args_cbor   BLOB NOT NULL
                ) WITHOUT ROWID
            """.trimIndent())
            s.executeUpdate("CREATE INDEX idx_mono_specializations_generic ON mono_specializations(generic_hash)")

            // ----------------------------------------------------------------
            // APP MANIFESTS — entry points for `mez build`
            // ----------------------------------------------------------------
            s.executeUpdate("""
                -- apps: declared entry points from Mezze.toml.
                -- entry_module not FK'd; the module may be registered later.
                CREATE TABLE apps (
                    name         TEXT PRIMARY KEY,
                    entry_name   TEXT NOT NULL,
                    entry_module TEXT NOT NULL,
                    source_file  TEXT
                ) WITHOUT ROWID
            """.trimIndent())

            s.executeUpdate("""
                -- app_functions: ordered list of reachable function hashes
                -- per app. UNIQUE(app_name, ord) ensures no two functions
                -- share the same position in the manifest.
                -- hash not FK'd; populated by `mez build` from a transitive
                -- closure that may include hashes not yet in blobs.
                CREATE TABLE app_functions (
                    app_name TEXT NOT NULL REFERENCES apps(name) ON DELETE CASCADE,
                    hash     TEXT NOT NULL,
                    ord      INTEGER NOT NULL,
                    PRIMARY KEY (app_name, hash),
                    UNIQUE (app_name, ord)
                ) WITHOUT ROWID
            """.trimIndent())
            s.executeUpdate("CREATE INDEX idx_app_functions_app_ord ON app_functions(app_name, ord)")

            s.executeUpdate("""
                -- app_performers: performer records per app for dispatch.
                -- hash not FK'd; same reasoning as app_functions.
                CREATE TABLE app_performers (
                    app_name   TEXT NOT NULL REFERENCES apps(name) ON DELETE CASCADE,
                    record_idx INTEGER NOT NULL,
                    position   INTEGER NOT NULL,
                    hash       TEXT NOT NULL,
                    PRIMARY KEY (app_name, record_idx, position)
                ) WITHOUT ROWID
            """.trimIndent())
        }
    }


    // ====================================================================
    // BLOBS
    // ====================================================================

    private val putBlobStmt: PreparedStatement by lazy {
        conn.prepareStatement("""
            INSERT OR REPLACE INTO blobs (hash, kind, visibility, typed, ir)
            VALUES (?, ?, ?, ?, ?)
        """.trimIndent())
    }

    @Synchronized
    override fun putBlob(hash: Hash, kind: BlobKind, visibility: Visibility, typed: ByteArray?, ir: ByteArray?) {
        putBlobStmt.apply {
            setString(1, hash.hex)
            setString(2, kind.name)
            setString(3, visibility.name)
            setBytes(4, typed)
            setBytes(5, ir)
            executeUpdate()
        }
        l1Blobs[hash.hex] = BlobRow(hash, kind, visibility, typed, ir)
        if (ir != null) l1IR[hash.hex] = ir
    }

    private val getBlobStmt: PreparedStatement by lazy {
        conn.prepareStatement(
            unionAll("SELECT hash, kind, visibility, typed, ir FROM <DB>.blobs WHERE hash = ?") + " LIMIT 1"
        )
    }

    @Synchronized
    override fun getBlob(hash: Hash): BlobRow? {
        l1Blobs[hash.hex]?.let { return it }
        for (i in 0..2) getBlobStmt.setString(i + 1, hash.hex)
        return getBlobStmt.executeQuery().use { rs ->
            if (rs.next()) rs.toBlobRow().also { l1Blobs[hash.hex] = it } else null
        }
    }

    private val blobExistsStmt: PreparedStatement by lazy {
        conn.prepareStatement(
            unionAll("SELECT 1 FROM <DB>.blobs WHERE hash = ?") + " LIMIT 1"
        )
    }

    @Synchronized
    override fun blobExists(hash: Hash): Boolean {
        for (i in 0..2) blobExistsStmt.setString(i + 1, hash.hex)
        return blobExistsStmt.executeQuery().use { it.next() }
    }

    @Synchronized
    override fun blobsExist(hashes: Collection<Hash>): Set<Hash> {
        if (hashes.isEmpty()) return emptySet()
        val present = HashSet<Hash>(hashes.size)
        // Stay safe with a chunk size of 500. Three UNIONed clauses each
        // bind `chunk.size` placeholders, so total params per query is
        // `3 * chunk.size` — well under the SQLite limit.
        for (chunk in hashes.chunked(500)) {
            val placeholders = chunk.joinToString(",") { "?" }
            val sql = unionAll("SELECT hash FROM <DB>.blobs WHERE hash IN ($placeholders)")
            conn.prepareStatement(sql).use { stmt ->
                for (db in 0..2) {
                    chunk.forEachIndexed { i, h ->
                        stmt.setString(db * chunk.size + i + 1, h.hex)
                    }
                }
                stmt.executeQuery().use { rs ->
                    while (rs.next()) present.add(Hash(rs.getString(1)))
                }
            }
        }
        return present
    }

    private val getIRStmt: PreparedStatement by lazy {
        // Filter `ir IS NOT NULL` PER-TIER (inside each branch of UNION ALL)
        // rather than wrapping the union. Otherwise `LIMIT 1` picks `main`'s
        // null-ir row over a later tier's real bytes, and we miss the IR.
        // Scope-disjoint invariant means at most one tier has a real row;
        // this just defends against an IR-less placeholder shadowing it.
        conn.prepareStatement(
            unionAll("SELECT ir FROM <DB>.blobs WHERE hash = ? AND ir IS NOT NULL") + " LIMIT 1"
        )
    }

    @Synchronized
    override fun getIR(hash: Hash): ByteArray? {
        if (hash.hex in l1IR) return l1IR[hash.hex]
        for (i in 0..2) getIRStmt.setString(i + 1, hash.hex)
        return getIRStmt.executeQuery().use { rs ->
            val ir = if (rs.next()) rs.getBytes("ir") else null
            l1IR[hash.hex] = ir
            ir
        }
    }

    /**
     * Get every blob hash visible to this store — main + stdlib + deps. Used
     * by bulk-load paths (e.g. `PrecompiledStdlib`) that need to materialise
     * the entire IR set up front.
     *
     * Was `main`-only despite the name; now UNION-ALLs across all three
     * tiers so a project-mode store (`openProject`) sees its stdlib + deps
     * blobs too. De-duplicates: a hash present in multiple tiers (impossible
     * under the scope-disjoint invariant, but defensible) appears once.
     */
    override fun getAllBlobHashes(): List<Hash> {
        val result = LinkedHashSet<Hash>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery(unionAll("SELECT hash FROM <DB>.blobs")).use { rs ->
                while (rs.next()) {
                    result.add(Hash(rs.getString("hash")))
                }
            }
        }
        return result.toList()
    }

    // ====================================================================
    // NAMES
    // ====================================================================

    private val registerNameStmt: PreparedStatement by lazy {
        conn.prepareStatement("INSERT OR REPLACE INTO names (module, name, hash) VALUES (?, ?, ?)")
    }

    @Synchronized
    override fun registerName(module: String, name: String, hash: Hash) {
        registerNameStmt.apply {
            setString(1, module)
            setString(2, name)
            setString(3, hash.hex)
            executeUpdate()
        }
        l1Names[nameKey(module, name)] = hash
    }

    private val resolveNameStmt: PreparedStatement by lazy {
        conn.prepareStatement(
            unionAll("SELECT hash FROM <DB>.names WHERE module = ? AND name = ?") + " LIMIT 1"
        )
    }

    @Synchronized
    override fun resolveName(module: String, name: String): Hash? {
        val key = nameKey(module, name)
        if (key in l1Names) return l1Names[key]
        for (i in 0..2) {
            resolveNameStmt.setString(i * 2 + 1, module)
            resolveNameStmt.setString(i * 2 + 2, name)
        }
        return resolveNameStmt.executeQuery().use { rs ->
            val hash = if (rs.next()) Hash(rs.getString(1)) else null
            l1Names[key] = hash
            hash
        }
    }

    private val getNamesForHashStmt: PreparedStatement by lazy {
        conn.prepareStatement(unionAll("SELECT module, name, hash FROM <DB>.names WHERE hash = ?"))
    }

    @Synchronized
    override fun getNamesForHash(hash: Hash): List<NameRow> {
        for (i in 0..2) getNamesForHashStmt.setString(i + 1, hash.hex)
        return getNamesForHashStmt.executeQuery().use { rs -> rs.collectNameRows() }
    }

    private val getModuleExportsStmt: PreparedStatement by lazy {
        // JOIN blobs needs to look in the same DB as names, since blobs
        // and names of stdlib live in stdlib.sqlite (not main.blobs).
        conn.prepareStatement(unionAll("""
            SELECT n.module, n.name, n.hash FROM <DB>.names n
            JOIN <DB>.blobs b ON n.hash = b.hash
            WHERE n.module = ? AND b.visibility = 'PUBLIC'
        """.trimIndent()))
    }

    @Synchronized
    override fun getModuleExports(module: String): List<NameRow> {
        for (i in 0..2) getModuleExportsStmt.setString(i + 1, module)
        return getModuleExportsStmt.executeQuery().use { rs -> rs.collectNameRows() }
    }

    private val searchExportsStmt: PreparedStatement by lazy {
        conn.prepareStatement(unionAll("""
            SELECT n.module, n.name, n.hash FROM <DB>.names n
            JOIN <DB>.blobs b ON n.hash = b.hash
            WHERE n.name LIKE ? ESCAPE '\' AND b.visibility = 'PUBLIC'
        """.trimIndent()) + " LIMIT 50")
    }

    @Synchronized
    override fun searchExportsByName(query: String): List<NameRow> {
        val pattern = "${escapeLikePattern(query)}%"
        for (i in 0..2) searchExportsStmt.setString(i + 1, pattern)
        return searchExportsStmt.executeQuery().use { rs -> rs.collectNameRows() }
    }

    // ====================================================================
    // MODULES
    // ====================================================================

    private val putModuleStmt: PreparedStatement by lazy {
        conn.prepareStatement("INSERT OR REPLACE INTO modules (path, file_path, source_hash) VALUES (?, ?, ?)")
    }

    @Synchronized
    override fun putModule(path: String, filePath: String?, sourceHash: String) {
        putModuleStmt.apply {
            setString(1, path)
            setString(2, filePath)
            setString(3, sourceHash)
            executeUpdate()
        }
        l1Modules[path] = ModuleRow(path, filePath, sourceHash)
    }

    private val getModuleStmt: PreparedStatement by lazy {
        // UNION across all three attached DBs. Module paths don't
        // overlap (std::* in stdlib, dep prefixes in deps, else cache),
        // so the result is at most one row.
        conn.prepareStatement("""
            SELECT path, file_path, source_hash FROM main.modules WHERE path = ?
            UNION ALL
            SELECT path, file_path, source_hash FROM stdlib.modules WHERE path = ?
            UNION ALL
            SELECT path, file_path, source_hash FROM deps.modules WHERE path = ?
            LIMIT 1
        """.trimIndent())
    }

    @Synchronized
    override fun getModule(path: String): ModuleRow? {
        if (path in l1Modules) return l1Modules[path]
        getModuleStmt.setString(1, path)
        getModuleStmt.setString(2, path)
        getModuleStmt.setString(3, path)
        return getModuleStmt.executeQuery().use { rs ->
            val row = if (rs.next()) ModuleRow(
                path = rs.getString("path"),
                filePath = rs.getString("file_path"),
                sourceHash = rs.getString("source_hash"),
            ) else null
            l1Modules[path] = row
            row
        }
    }

    private val isFileStaleStmt: PreparedStatement by lazy {
        conn.prepareStatement("SELECT 1 FROM modules WHERE file_path = ? AND source_hash = ? LIMIT 1")
    }

    @Synchronized
    override fun isFileStale(filePath: String, currentSourceHash: String): Boolean {
        isFileStaleStmt.setString(1, filePath)
        isFileStaleStmt.setString(2, currentSourceHash)
        return !isFileStaleStmt.executeQuery().use { it.next() }
    }

    /**
     * Walk every module across cache + stdlib + deps. UNION ALL works
     * because module paths don't overlap across DBs (std::* lives in
     * stdlib, dep prefixes in deps, everything else in cache).
     */
    @Synchronized
    override fun getAllModules(): List<ModuleRow> {
        val sql = """
            SELECT path, file_path, source_hash FROM main.modules
            UNION ALL
            SELECT path, file_path, source_hash FROM stdlib.modules
            UNION ALL
            SELECT path, file_path, source_hash FROM deps.modules
        """.trimIndent()
        return conn.createStatement().use { s ->
            s.executeQuery(sql).use { rs ->
                val list = mutableListOf<ModuleRow>()
                while (rs.next()) {
                    list.add(ModuleRow(
                        path = rs.getString("path"),
                        filePath = rs.getString("file_path"),
                        sourceHash = rs.getString("source_hash"),
                    ))
                }
                list
            }
        }
    }


    // ====================================================================
    // EDGES
    // ====================================================================

    private val addEdgeStmt: PreparedStatement by lazy {
        conn.prepareStatement("INSERT OR IGNORE INTO edges (from_hash, to_hash, kind) VALUES (?, ?, ?)")
    }

    @Synchronized
    override fun addEdge(fromHash: Hash, toHash: Hash, kind: EdgeKind) {
        addEdgeStmt.apply {
            setString(1, fromHash.hex)
            setString(2, toHash.hex)
            setString(3, kind.name)
            executeUpdate()
        }
    }

    private val getEdgesFromStmt: PreparedStatement by lazy {
        conn.prepareStatement(unionAll("SELECT from_hash, to_hash, kind FROM <DB>.edges WHERE from_hash = ?"))
    }

    @Synchronized
    override fun getEdgesFrom(hash: Hash): List<EdgeRow> {
        for (i in 0..2) getEdgesFromStmt.setString(i + 1, hash.hex)
        return getEdgesFromStmt.executeQuery().use { rs -> rs.collectEdgeRows() }
    }

    private val getEdgesToStmt: PreparedStatement by lazy {
        conn.prepareStatement(unionAll("SELECT from_hash, to_hash, kind FROM <DB>.edges WHERE to_hash = ?"))
    }

    @Synchronized
    override fun getEdgesTo(hash: Hash): List<EdgeRow> {
        for (i in 0..2) getEdgesToStmt.setString(i + 1, hash.hex)
        return getEdgesToStmt.executeQuery().use { rs -> rs.collectEdgeRows() }
    }

    @Synchronized
    override fun getTransitiveClosure(entryHash: Hash): Set<Hash> {
        val visited = mutableSetOf<Hash>()
        val queue = ArrayDeque<Hash>()
        queue.add(entryHash)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current)) continue
            for (edge in getEdgesFrom(current)) {
                if (edge.toHash !in visited) {
                    queue.add(edge.toHash)
                }
            }
        }
        return visited
    }

    // ====================================================================
    // ABILITY / EFFECT INDEX
    // ====================================================================

    private val putAbilityImplStmt: PreparedStatement by lazy {
        conn.prepareStatement("INSERT OR REPLACE INTO ability_impls (hash, ability_hash, target_type, module) VALUES (?, ?, ?, ?)")
    }

    @Synchronized
    override fun putAbilityImpl(hash: Hash, abilityHash: Hash, targetType: String, module: String?) {
        putAbilityImplStmt.apply {
            setString(1, hash.hex)
            setString(2, abilityHash.hex)
            setString(3, targetType)
            setString(4, module)
            executeUpdate()
        }
    }

    private val getAbilityImplsStmt: PreparedStatement by lazy {
        conn.prepareStatement(unionAll(
            "SELECT hash, ability_hash, target_type, module FROM <DB>.ability_impls WHERE ability_hash = ?"
        ))
    }

    @Synchronized
    override fun getAbilityImpls(abilityHash: Hash): List<AbilityImplRow> {
        for (i in 0..2) getAbilityImplsStmt.setString(i + 1, abilityHash.hex)
        return getAbilityImplsStmt.executeQuery().use { rs ->
            val list = mutableListOf<AbilityImplRow>()
            while (rs.next()) {
                list.add(AbilityImplRow(
                    hash = Hash(rs.getString("hash")),
                    abilityHash = Hash(rs.getString("ability_hash")),
                    targetType = rs.getString("target_type"),
                    module = rs.getString("module")
                ))
            }
            list
        }
    }

    private val getAbilityImplForTypeStmt: PreparedStatement by lazy {
        conn.prepareStatement(
            unionAll("SELECT hash FROM <DB>.ability_impls WHERE ability_hash = ? AND target_type = ?") + " LIMIT 1"
        )
    }

    @Synchronized
    override fun getAbilityImplForType(abilityHash: Hash, targetType: String): Hash? {
        for (i in 0..2) {
            getAbilityImplForTypeStmt.setString(i * 2 + 1, abilityHash.hex)
            getAbilityImplForTypeStmt.setString(i * 2 + 2, targetType)
        }
        return getAbilityImplForTypeStmt.executeQuery().use { rs ->
            if (rs.next()) Hash(rs.getString("hash")) else null
        }
    }

    private val putEffectPerformerStmt: PreparedStatement by lazy {
        conn.prepareStatement("INSERT OR REPLACE INTO effect_performers (hash, effect_hash, performer_name, module) VALUES (?, ?, ?, ?)")
    }

    @Synchronized
    override fun putEffectPerformer(hash: Hash, effectHash: Hash, performerName: String, module: String?) {
        putEffectPerformerStmt.apply {
            setString(1, hash.hex)
            setString(2, effectHash.hex)
            setString(3, performerName)
            setString(4, module)
            executeUpdate()
        }
    }

    private val getEffectPerformersStmt: PreparedStatement by lazy {
        conn.prepareStatement(unionAll(
            "SELECT hash, effect_hash, performer_name, module FROM <DB>.effect_performers WHERE effect_hash = ?"
        ))
    }

    @Synchronized
    override fun getEffectPerformers(effectHash: Hash): List<EffectPerformerRow> {
        for (i in 0..2) getEffectPerformersStmt.setString(i + 1, effectHash.hex)
        return getEffectPerformersStmt.executeQuery().use { rs ->
            val list = mutableListOf<EffectPerformerRow>()
            while (rs.next()) {
                list.add(EffectPerformerRow(
                    hash = Hash(rs.getString("hash")),
                    effectHash = Hash(rs.getString("effect_hash")),
                    performerName = rs.getString("performer_name"),
                    module = rs.getString("module")
                ))
            }
            list
        }
    }

    // ====================================================================
    // USAGES (LSP cross-file find-references)
    // ====================================================================

    private val putUsageStmt: PreparedStatement by lazy {
        conn.prepareStatement("""
            INSERT INTO usages (target_hash, file_path, start_line, start_col, end_line, end_col)
            VALUES (?, ?, ?, ?, ?, ?)
        """.trimIndent())
    }

    @Synchronized
    override fun putUsage(targetHash: Hash, filePath: String, startLine: Int, startCol: Int, endLine: Int, endCol: Int) {
        putUsageStmt.apply {
            setString(1, targetHash.hex)
            setString(2, filePath)
            setInt(3, startLine)
            setInt(4, startCol)
            setInt(5, endLine)
            setInt(6, endCol)
            executeUpdate()
        }
    }

    private val getUsagesStmt: PreparedStatement by lazy {
        conn.prepareStatement("SELECT target_hash, file_path, start_line, start_col, end_line, end_col FROM usages WHERE target_hash = ?")
    }

    @Synchronized
    override fun getUsages(targetHash: Hash): List<UsageRow> {
        getUsagesStmt.setString(1, targetHash.hex)
        return getUsagesStmt.executeQuery().use { rs ->
            val list = mutableListOf<UsageRow>()
            while (rs.next()) {
                list.add(UsageRow(
                    targetHash = Hash(rs.getString("target_hash")),
                    filePath = rs.getString("file_path"),
                    startLine = rs.getInt("start_line"),
                    startCol = rs.getInt("start_col"),
                    endLine = rs.getInt("end_line"),
                    endCol = rs.getInt("end_col")
                ))
            }
            list
        }
    }

    private val clearUsagesStmt: PreparedStatement by lazy {
        conn.prepareStatement("DELETE FROM usages WHERE file_path = ?")
    }

    @Synchronized
    override fun clearUsagesForFile(filePath: String) {
        clearUsagesStmt.setString(1, filePath)
        clearUsagesStmt.executeUpdate()
    }

    // ====================================================================
    // METADATA
    // ====================================================================

    private val getMetadataStmt: PreparedStatement by lazy {
        conn.prepareStatement("SELECT value FROM metadata WHERE key = ?")
    }

    @Synchronized
    override fun getMetadata(key: String): String? {
        getMetadataStmt.setString(1, key)
        return getMetadataStmt.executeQuery().use { rs ->
            if (rs.next()) rs.getString(1) else null
        }
    }

    private val setMetadataStmt: PreparedStatement by lazy {
        conn.prepareStatement("INSERT OR REPLACE INTO metadata (key, value) VALUES (?, ?)")
    }

    @Synchronized
    override fun setMetadata(key: String, value: String) {
        setMetadataStmt.apply {
            setString(1, key)
            setString(2, value)
            executeUpdate()
        }
    }

    private val getMetadataByPrefixStmt: PreparedStatement by lazy {
        conn.prepareStatement("SELECT key, value FROM metadata WHERE key LIKE ? ESCAPE '\\'")
    }

    @Synchronized
    override fun getMetadataByPrefix(prefix: String): Map<String, String> {
        getMetadataByPrefixStmt.setString(1, "${escapeLikePattern(prefix)}%")
        return getMetadataByPrefixStmt.executeQuery().use { rs ->
            buildMap {
                while (rs.next()) put(rs.getString(1), rs.getString(2))
            }
        }
    }

    // ====================================================================
    // APPS
    // ====================================================================

    private val putAppStmt: PreparedStatement by lazy {
        conn.prepareStatement("""
            INSERT OR REPLACE INTO apps (name, entry_name, entry_module, source_file)
            VALUES (?, ?, ?, ?)
        """.trimIndent())
    }

    @Synchronized
    override fun putApp(name: String, entryName: String, entryModule: String, sourceFile: String?) {
        putAppStmt.apply {
            setString(1, name)
            setString(2, entryName)
            setString(3, entryModule)
            if (sourceFile != null) setString(4, sourceFile) else setNull(4, java.sql.Types.VARCHAR)
            executeUpdate()
        }
    }

    private val getAppStmt: PreparedStatement by lazy {
        conn.prepareStatement("SELECT name, entry_name, entry_module, source_file FROM apps WHERE name = ?")
    }

    @Synchronized
    override fun getApp(name: String): AppRow? {
        getAppStmt.setString(1, name)
        return getAppStmt.executeQuery().use { rs ->
            if (rs.next()) AppRow(
                name = rs.getString(1),
                entryName = rs.getString(2),
                entryModule = rs.getString(3),
                sourceFile = rs.getString(4)
            ) else null
        }
    }

    @Synchronized
    override fun setAppFunctionHashes(name: String, hashes: List<Hash>) {
        // Defense-in-depth dedupe. The interface contract says hashes is
        // semantically a set; the upstream pipeline (TypedASTCompiler)
        // already dedupes its output. We dedupe here too so a future
        // caller can never trigger the schema's UNIQUE constraint —
        // SQLite errors must never reach a mezze user (#225).
        //
        // First-occurrence wins for ordinal stability; downstream
        // consumers that read with ORDER BY ord see hashes in the same
        // sequence the caller provided minus repeats.
        val seen = HashSet<Hash>(hashes.size)
        val deduped = ArrayList<Hash>(hashes.size)
        for (h in hashes) if (seen.add(h)) deduped.add(h)

        try {
            conn.prepareStatement("DELETE FROM app_functions WHERE app_name = ?").use { stmt ->
                stmt.setString(1, name)
                stmt.executeUpdate()
            }
            if (deduped.isEmpty()) return
            conn.prepareStatement("INSERT INTO app_functions (app_name, hash, ord) VALUES (?, ?, ?)").use { stmt ->
                deduped.forEachIndexed { i, h ->
                    stmt.setString(1, name)
                    stmt.setString(2, h.hex)
                    stmt.setInt(3, i)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
        } catch (e: java.sql.SQLException) {
            // Should be unreachable after the dedupe above. If we ever
            // see this, it's a mezze internal bug — surface as such, not
            // as a raw SQLite jargon string to the user.
            throw IllegalStateException(
                "internal: mezze store failed to register app function hashes for '$name' " +
                "(count=${deduped.size}, distinct=${seen.size}): ${e.message}", e
            )
        }
    }

    @Synchronized
    override fun getAppFunctionHashes(name: String): List<Hash> {
        conn.prepareStatement("SELECT hash FROM app_functions WHERE app_name = ? ORDER BY ord").use { stmt ->
            stmt.setString(1, name)
            stmt.executeQuery().use { rs ->
                val out = mutableListOf<Hash>()
                while (rs.next()) out.add(Hash(rs.getString(1)))
                return out
            }
        }
    }

    @Synchronized
    override fun setAppPerformerRecords(name: String, records: List<Array<String>>) {
        conn.prepareStatement("DELETE FROM app_performers WHERE app_name = ?").use { stmt ->
            stmt.setString(1, name)
            stmt.executeUpdate()
        }
        if (records.isEmpty()) return
        conn.prepareStatement("INSERT INTO app_performers (app_name, record_idx, position, hash) VALUES (?, ?, ?, ?)").use { stmt ->
            records.forEachIndexed { recordIdx, record ->
                record.forEachIndexed { pos, hash ->
                    stmt.setString(1, name)
                    stmt.setInt(2, recordIdx)
                    stmt.setInt(3, pos)
                    stmt.setString(4, hash)
                    stmt.addBatch()
                }
            }
            stmt.executeBatch()
        }
    }

    @Synchronized
    override fun getAppPerformerRecords(name: String): List<Array<String>> {
        conn.prepareStatement(
            "SELECT record_idx, position, hash FROM app_performers WHERE app_name = ? ORDER BY record_idx, position"
        ).use { stmt ->
            stmt.setString(1, name)
            stmt.executeQuery().use { rs ->
                val byRecord = mutableMapOf<Int, MutableList<String>>()
                while (rs.next()) {
                    val recordIdx = rs.getInt(1)
                    val hash = rs.getString(3)
                    byRecord.getOrPut(recordIdx) { mutableListOf() }.add(hash)
                }
                return byRecord.toSortedMap().values.map { it.toTypedArray() }
            }
        }
    }

    // ====================================================================
    // IMPORTS
    // ====================================================================

    private val putImportStmt: PreparedStatement by lazy {
        conn.prepareStatement("""
            INSERT OR REPLACE INTO imports
                (module_path, local_name, target_module, target_name, target_hash,
                 start_line, start_col, end_line, end_col)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent())
    }

    @Synchronized
    override fun putImport(
        modulePath: String,
        localName: String,
        targetModule: String,
        targetName: String,
        targetHash: Hash?,
        startLine: Int,
        startCol: Int,
        endLine: Int,
        endCol: Int
    ) {
        putImportStmt.apply {
            setString(1, modulePath)
            setString(2, localName)
            setString(3, targetModule)
            setString(4, targetName)
            // target_hash is nullable; pass NULL (not "") when unresolved so
            // direct SQL queries see a real NULL.
            if (targetHash != null) setString(5, targetHash.hex)
            else setNull(5, java.sql.Types.VARCHAR)
            setInt(6, startLine)
            setInt(7, startCol)
            setInt(8, endLine)
            setInt(9, endCol)
            executeUpdate()
        }
    }

    @Synchronized
    override fun clearImportsForModule(modulePath: String) {
        conn.prepareStatement("DELETE FROM imports WHERE module_path = ?").use { stmt ->
            stmt.setString(1, modulePath)
            stmt.executeUpdate()
        }
    }

    private fun ResultSet.toImportRow(): ImportRow {
        val rawHash = getString("target_hash")
        return ImportRow(
            modulePath = getString("module_path"),
            localName = getString("local_name"),
            targetModule = getString("target_module"),
            targetName = getString("target_name"),
            targetHash = if (rawHash.isNullOrEmpty()) null else Hash(rawHash),
            startLine = getInt("start_line"),
            startCol = getInt("start_col"),
            endLine = getInt("end_line"),
            endCol = getInt("end_col")
        )
    }

    private val getImportsForModuleStmt: PreparedStatement by lazy {
        conn.prepareStatement(unionAll("""
            SELECT module_path, local_name, target_module, target_name, target_hash,
                   start_line, start_col, end_line, end_col
            FROM <DB>.imports
            WHERE module_path = ?
        """.trimIndent()))
    }

    @Synchronized
    override fun getImportsForModule(modulePath: String): List<ImportRow> {
        for (i in 0..2) getImportsForModuleStmt.setString(i + 1, modulePath)
        return getImportsForModuleStmt.executeQuery().use { rs ->
            buildList { while (rs.next()) add(rs.toImportRow()) }
        }
    }

    private val getImportersOfStmt: PreparedStatement by lazy {
        conn.prepareStatement(unionAll("""
            SELECT module_path, local_name, target_module, target_name, target_hash,
                   start_line, start_col, end_line, end_col
            FROM <DB>.imports
            WHERE target_module = ? AND target_name = ?
        """.trimIndent()))
    }

    @Synchronized
    override fun getImportersOf(targetModule: String, targetName: String): List<ImportRow> {
        for (i in 0..2) {
            getImportersOfStmt.setString(i * 2 + 1, targetModule)
            getImportersOfStmt.setString(i * 2 + 2, targetName)
        }
        return getImportersOfStmt.executeQuery().use { rs ->
            buildList { while (rs.next()) add(rs.toImportRow()) }
        }
    }

    // ====================================================================
    // DECLS
    // ====================================================================

    private val putDeclStmt: PreparedStatement by lazy {
        conn.prepareStatement("""
            INSERT OR REPLACE INTO decls
                (module_path, decl_name, decl_kind, source_hash, hash,
                 start_line, start_col, end_line, end_col, doc, metadata)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent())
    }

    @Synchronized
    override fun putDecl(
        modulePath: String,
        declName: String,
        declKind: DeclKind,
        sourceHash: String,
        hash: Hash,
        startLine: Int,
        startCol: Int,
        endLine: Int,
        endCol: Int,
        doc: String?,
        metadata: String?
    ) {
        putDeclStmt.apply {
            setString(1, modulePath)
            setString(2, declName)
            setString(3, declKind.dbValue)
            setString(4, sourceHash)
            setString(5, hash.hex)
            setInt(6, startLine)
            setInt(7, startCol)
            setInt(8, endLine)
            setInt(9, endCol)
            setString(10, doc)
            setString(11, metadata)
            executeUpdate()
        }
    }

    @Synchronized
    override fun clearDeclsForModule(modulePath: String) {
        conn.prepareStatement("DELETE FROM decls WHERE module_path = ?").use { stmt ->
            stmt.setString(1, modulePath)
            stmt.executeUpdate()
        }
    }

    private fun ResultSet.toDeclRow(): DeclRow = DeclRow(
        modulePath = getString("module_path"),
        declName = getString("decl_name"),
        declKind = DeclKind.fromDb(getString("decl_kind")),
        sourceHash = getString("source_hash"),
        hash = Hash(getString("hash")),
        startLine = getInt("start_line"),
        startCol = getInt("start_col"),
        endLine = getInt("end_line"),
        endCol = getInt("end_col"),
        doc = getString("doc"),
        metadata = getString("metadata")
    )

    private val getDeclsForModuleStmt: PreparedStatement by lazy {
        conn.prepareStatement(unionAll("""
            SELECT module_path, decl_name, decl_kind, source_hash, hash,
                   start_line, start_col, end_line, end_col, doc, metadata
            FROM <DB>.decls
            WHERE module_path = ?
        """.trimIndent()))
    }

    @Synchronized
    override fun getDeclsForModule(modulePath: String): List<DeclRow> {
        for (i in 0..2) getDeclsForModuleStmt.setString(i + 1, modulePath)
        return getDeclsForModuleStmt.executeQuery().use { rs ->
            buildList { while (rs.next()) add(rs.toDeclRow()) }
        }
    }

    private val searchDeclsByNameStmt: PreparedStatement by lazy {
        conn.prepareStatement(unionAll("""
            SELECT module_path, decl_name, decl_kind, source_hash, hash,
                   start_line, start_col, end_line, end_col, doc, metadata
            FROM <DB>.decls
            WHERE decl_name LIKE ? ESCAPE '\'
        """.trimIndent()) + " ORDER BY decl_name LIMIT 200")
    }

    @Synchronized
    override fun searchDeclsByName(query: String): List<DeclRow> {
        val pattern = "${escapeLikePattern(query)}%"
        for (i in 0..2) searchDeclsByNameStmt.setString(i + 1, pattern)
        return searchDeclsByNameStmt.executeQuery().use { rs ->
            buildList { while (rs.next()) add(rs.toDeclRow()) }
        }
    }

    private val getDeclsByKindStmt: PreparedStatement by lazy {
        conn.prepareStatement(unionAll("""
            SELECT module_path, decl_name, decl_kind, source_hash, hash,
                   start_line, start_col, end_line, end_col, doc, metadata
            FROM <DB>.decls
            WHERE decl_kind = ?
        """.trimIndent()))
    }

    @Synchronized
    override fun getDeclsByKind(kind: DeclKind): List<DeclRow> {
        for (i in 0..2) getDeclsByKindStmt.setString(i + 1, kind.dbValue)
        return getDeclsByKindStmt.executeQuery().use { rs ->
            buildList { while (rs.next()) add(rs.toDeclRow()) }
        }
    }

    private val findDeclByHashStmt: PreparedStatement by lazy {
        // Hits idx_decls_hash. UNION ALL across all three so hashes
        // resolve regardless of which file owns the decl.
        conn.prepareStatement(unionAll("""
            SELECT module_path, decl_name, decl_kind, source_hash, hash,
                   start_line, start_col, end_line, end_col, doc, metadata
            FROM <DB>.decls
            WHERE hash = ?
        """.trimIndent()) + " LIMIT 1")
    }

    @Synchronized
    override fun findDeclByHash(hash: Hash): DeclRow? {
        for (i in 0..2) findDeclByHashStmt.setString(i + 1, hash.hex)
        return findDeclByHashStmt.executeQuery().use { rs ->
            if (rs.next()) rs.toDeclRow() else null
        }
    }

    // ====================================================================
    // PARSED MODULES
    // ====================================================================

    private val putParsedModuleStmt: PreparedStatement by lazy {
        conn.prepareStatement("""
            INSERT OR REPLACE INTO parsed_modules (module_path, source_hash, parsed_blob, parsed_at)
            VALUES (?, ?, ?, ?)
        """.trimIndent())
    }

    @Synchronized
    override fun putParsedModule(modulePath: String, sourceHash: String, parsedBlob: ByteArray) {
        putParsedModuleStmt.apply {
            setString(1, modulePath)
            setString(2, sourceHash)
            setBytes(3, parsedBlob)
            setLong(4, System.currentTimeMillis())
            executeUpdate()
        }
        l1ParsedModules[cacheKey(modulePath, sourceHash)] = parsedBlob
    }

    private val getParsedModuleStmt: PreparedStatement by lazy {
        conn.prepareStatement(
            unionAll("SELECT parsed_blob FROM <DB>.parsed_modules WHERE module_path = ? AND source_hash = ?") + " LIMIT 1"
        )
    }

    @Synchronized
    override fun getParsedModule(modulePath: String, sourceHash: String): ByteArray? {
        val key = cacheKey(modulePath, sourceHash)
        if (key in l1ParsedModules) return l1ParsedModules[key]
        for (i in 0..2) {
            getParsedModuleStmt.setString(i * 2 + 1, modulePath)
            getParsedModuleStmt.setString(i * 2 + 2, sourceHash)
        }
        return getParsedModuleStmt.executeQuery().use { rs ->
            val blob = if (rs.next()) rs.getBytes(1) else null
            l1ParsedModules[key] = blob
            blob
        }
    }

    @Synchronized
    override fun clearParsedModulesForPath(modulePath: String) {
        conn.prepareStatement("DELETE FROM parsed_modules WHERE module_path = ?").use { stmt ->
            stmt.setString(1, modulePath)
            stmt.executeUpdate()
        }
        l1ParsedModules.keys.removeAll { it.startsWith("$modulePath\u0000") }
    }

    // ====================================================================
    // TYPED MODULES
    // ====================================================================

    private val putTypedModuleStmt: PreparedStatement by lazy {
        conn.prepareStatement("""
            INSERT OR REPLACE INTO typed_modules (module_path, source_hash, typed_blob, typed_at)
            VALUES (?, ?, ?, ?)
        """.trimIndent())
    }

    @Synchronized
    override fun putTypedModule(modulePath: String, sourceHash: String, typedBlob: ByteArray) {
        putTypedModuleStmt.apply {
            setString(1, modulePath)
            setString(2, sourceHash)
            setBytes(3, typedBlob)
            setLong(4, System.currentTimeMillis())
            executeUpdate()
        }
        l1TypedModules[cacheKey(modulePath, sourceHash)] = typedBlob
    }

    private val getTypedModuleStmt: PreparedStatement by lazy {
        // UNION across cache + stdlib + deps. Module paths are scope-
        // unique so at most one hit; LIMIT 1 short-circuits the scan.
        conn.prepareStatement("""
            SELECT typed_blob FROM main.typed_modules   WHERE module_path = ? AND source_hash = ?
            UNION ALL
            SELECT typed_blob FROM stdlib.typed_modules WHERE module_path = ? AND source_hash = ?
            UNION ALL
            SELECT typed_blob FROM deps.typed_modules   WHERE module_path = ? AND source_hash = ?
            LIMIT 1
        """.trimIndent())
    }

    @Synchronized
    override fun getTypedModule(modulePath: String, sourceHash: String): ByteArray? {
        val key = cacheKey(modulePath, sourceHash)
        if (key in l1TypedModules) return l1TypedModules[key]
        for (i in 0..2) {
            getTypedModuleStmt.setString(i * 2 + 1, modulePath)
            getTypedModuleStmt.setString(i * 2 + 2, sourceHash)
        }
        return getTypedModuleStmt.executeQuery().use { rs ->
            val blob = if (rs.next()) rs.getBytes(1) else null
            l1TypedModules[key] = blob
            blob
        }
    }

    @Synchronized
    override fun clearTypedModulesForPath(modulePath: String) {
        conn.prepareStatement("DELETE FROM typed_modules WHERE module_path = ?").use { stmt ->
            stmt.setString(1, modulePath)
            stmt.executeUpdate()
        }
        l1TypedModules.keys.removeAll { it.startsWith("$modulePath\u0000") }
    }

    // ====================================================================
    // MONO SPECIALIZATIONS
    // ====================================================================

    @Synchronized
    override fun putMonoSpecialization(specializedHash: Hash, genericHash: Hash, typeArgsCbor: ByteArray) {
        conn.prepareStatement("""
            INSERT OR REPLACE INTO mono_specializations (specialized_hash, generic_hash, type_args_cbor)
            VALUES (?, ?, ?)
        """.trimIndent()).use { ps ->
            ps.setString(1, specializedHash.hex)
            ps.setString(2, genericHash.hex)
            ps.setBytes(3, typeArgsCbor)
            ps.executeUpdate()
        }
    }

    @Synchronized
    override fun getMonoSpecialization(specializedHash: Hash): MonoSpecializationRow? {
        conn.prepareStatement(
            "SELECT generic_hash, type_args_cbor FROM mono_specializations WHERE specialized_hash = ?"
        ).use { ps ->
            ps.setString(1, specializedHash.hex)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                val genericHash = Hash(rs.getString(1))
                val cbor = rs.getBytes(2)
                return MonoSpecializationRow(specializedHash, genericHash, cbor)
            }
        }
    }

    @Synchronized
    override fun getMonoSpecializationsForGeneric(genericHash: Hash): List<MonoSpecializationRow> {
        val out = ArrayList<MonoSpecializationRow>()
        conn.prepareStatement(
            "SELECT specialized_hash, type_args_cbor FROM mono_specializations WHERE generic_hash = ?"
        ).use { ps ->
            ps.setString(1, genericHash.hex)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    out.add(MonoSpecializationRow(
                        specializedHash = Hash(rs.getString(1)),
                        genericHash = genericHash,
                        typeArgsCbor = rs.getBytes(2)
                    ))
                }
            }
        }
        return out
    }

    // ====================================================================
    // LIFECYCLE
    // ====================================================================

    @Synchronized
    override fun <T> transaction(block: () -> T): T {
        val wasAutoCommit = conn.autoCommit
        conn.autoCommit = false
        // Snapshot the L1 maps so we can restore them on rollback. Without
        // this, a failed transaction leaves L1 holding writes that SQLite
        // never committed — subsequent reads see ghost data. Cheap because
        // L1 only holds hot entries (~hundreds, not all of cache).
        val savedBlobs = HashMap(l1Blobs)
        val savedIR = HashMap(l1IR)
        val savedNames = HashMap(l1Names)
        val savedModules = HashMap(l1Modules)
        val savedParsedModules = HashMap(l1ParsedModules)
        val savedTypedModules = HashMap(l1TypedModules)
        return try {
            val result = block()
            conn.commit()
            result
        } catch (e: Exception) {
            conn.rollback()
            // Restore L1 to its pre-transaction state.
            l1Blobs.clear(); l1Blobs.putAll(savedBlobs)
            l1IR.clear(); l1IR.putAll(savedIR)
            l1Names.clear(); l1Names.putAll(savedNames)
            l1Modules.clear(); l1Modules.putAll(savedModules)
            l1ParsedModules.clear(); l1ParsedModules.putAll(savedParsedModules)
            l1TypedModules.clear(); l1TypedModules.putAll(savedTypedModules)
            throw e
        } finally {
            conn.autoCommit = wasAutoCommit
        }
    }

    /**
     * Truncate every typecheck-derived table in `main`. Preserves `modules`,
     * `parsed_modules`, `metadata`, plus SQLite internals (`schema_version`,
     * `sqlite_sequence`). Discovered dynamically from `sqlite_master`, so a
     * future table added to the schema is wiped automatically.
     *
     * Wraps itself in a transaction so the L1 caches get restored on any
     * failure mid-wipe. Also invalidates L1 entries that the wipe orphans.
     */
    @Synchronized
    override fun wipeTypecheckCaches() {
        val preserved = setOf("modules", "parsed_modules", "metadata", "schema_version", "sqlite_sequence")
        transaction {
            val tables = mutableListOf<String>()
            conn.createStatement().use { s ->
                s.executeQuery("SELECT name FROM main.sqlite_master WHERE type='table'").use { rs ->
                    while (rs.next()) tables.add(rs.getString(1))
                }
            }
            conn.createStatement().use { s ->
                for (t in tables) {
                    if (t in preserved) continue
                    s.executeUpdate("DELETE FROM main.\"$t\"")
                }
            }
        }
        // L1 entries for blobs/IR/names/typed_modules are now stale; clear.
        // parsed_modules + modules survive both in SQLite and in L1.
        l1Blobs.clear()
        l1IR.clear()
        l1Names.clear()
        l1TypedModules.clear()
    }

    @Synchronized
    override fun close() {
        conn.close()
    }

    // ====================================================================
    // HELPERS
    // ====================================================================

    private fun ResultSet.toBlobRow(): BlobRow = BlobRow(
        hash = Hash(getString("hash")),
        kind = BlobKind.valueOf(getString("kind")),
        visibility = Visibility.valueOf(getString("visibility")),
        typed = getBytes("typed"),
        ir = getBytes("ir")
    )

    private fun ResultSet.collectNameRows(): List<NameRow> {
        val list = mutableListOf<NameRow>()
        while (next()) {
            list.add(NameRow(getString("module"), getString("name"), Hash(getString("hash"))))
        }
        return list
    }

    private fun ResultSet.collectEdgeRows(): List<EdgeRow> {
        val list = mutableListOf<EdgeRow>()
        while (next()) {
            list.add(EdgeRow(Hash(getString("from_hash")), Hash(getString("to_hash")), EdgeKind.valueOf(getString("kind"))))
        }
        return list
    }
}
