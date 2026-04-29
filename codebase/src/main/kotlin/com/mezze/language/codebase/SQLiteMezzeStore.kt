package com.mezze.language.codebase

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
 * - Project mode: `.mz/cache.sqlite` (read-write, full schema)
 * - Dependencies: `.mz/deps.sqlite` (read-only)
 * - JAR embedded: `blobs.sqlite` (read-only, IR-only)
 * - Tests: `:memory:`
 *
 * Thread safety: WAL mode supports concurrent reads + single writer.
 */
class SQLiteMezzeStore private constructor(
    private val conn: Connection,
    private val readOnly: Boolean = false
) : MezzeStore {

    /**
     * Run a block with the underlying JDBC connection. Internal seam used
     * by `StdlibSeed.copyFrom` to issue ATTACH DATABASE / multi-table
     * INSERT-SELECT batches that don't fit the typed API. Public callers
     * should always go through the typed methods.
     */
    internal fun <T> executeRaw(block: (Connection) -> T): T = block(conn)

    companion object {
        /**
         * Current schema version. Each bump must be accompanied by a
         * `applyVN(stmt)` function below.
         *
         * Bump history:
         *  - v5: apps + app_functions + app_performers tables.
         *  - v6: typed_modules table — caches v2 TypedModule per (path, sourceHash).
         *  - v7: unified cache + incremental compile.
         *        - foreign_keys=ON, declared everywhere, ON DELETE CASCADE
         *        - CHECK constraints on enum columns
         *        - NEW tables: parsed_modules, decls, imports
         *        - RENAME: references_ → usages
         *        - ADD column: modules.category, blobs.created_at
         *        - decls PK includes decl_kind; decls.hash matches blobs.hash
         *        - app_functions UNIQUE(app_name, ord)
         *
         * v7 is a clean break — no migration code from v6. Existing caches
         * get wiped + rebuilt on first open. See
         * docs/architecture/16-unified-cache-and-incremental-compile.md.
         */
        const val SCHEMA_VERSION = 7
        const val PROJECT_DIR = ".mz"
        const val CACHE_FILE = "cache.sqlite"
        const val DEPS_FILE = "deps.sqlite"
        const val CACHE_DIR_ENV = "MEZZE_CACHE_DIR"

        /**
         * Resolve where this project's cache.sqlite lives.
         *
         * Priority:
         *   1. `MEZZE_CACHE_DIR` env var → `<MEZZE_CACHE_DIR>/cache.sqlite`
         *      (CI runners, shared mounts, Docker volumes can override.)
         *   2. Default → `<projectRoot>/.mz/cache.sqlite`
         *
         * Single source of truth — every caller goes through `forProject` /
         * `hasCache`, so they all pick this up automatically.
         */
        fun resolveCacheFile(projectRoot: File): File {
            val override = System.getenv(CACHE_DIR_ENV)?.takeIf { it.isNotBlank() }
            return if (override != null) {
                File(override, CACHE_FILE)
            } else {
                File(File(projectRoot, PROJECT_DIR), CACHE_FILE)
            }
        }

        /** Open or create project cache (location resolved via [resolveCacheFile]). */
        fun forProject(projectRoot: File): SQLiteMezzeStore {
            val dbFile = resolveCacheFile(projectRoot)
            dbFile.parentFile?.mkdirs()
            val conn = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")
            return SQLiteMezzeStore(conn, readOnly = false).also {
                it.configurePragmas()
                it.initSchema()
            }
        }

        /** Open read-write store at a specific file path. */
        fun forFile(path: File): SQLiteMezzeStore {
            path.parentFile?.mkdirs()
            val conn = DriverManager.getConnection("jdbc:sqlite:${path.absolutePath}")
            return SQLiteMezzeStore(conn, readOnly = false).also {
                it.configurePragmas()
                it.initSchema()
            }
        }

        /** Open read-only store (for deps.sqlite, JAR blobs.sqlite). */
        fun readOnly(path: File): SQLiteMezzeStore {
            val conn = DriverManager.getConnection("jdbc:sqlite:file:${path.absolutePath}?mode=ro")
            return SQLiteMezzeStore(conn, readOnly = true)
        }

        /** Create in-memory store (for tests). */
        fun inMemory(): SQLiteMezzeStore {
            val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
            return SQLiteMezzeStore(conn, readOnly = false).also {
                it.configurePragmas()
                it.initSchema()
            }
        }

        /** Check if project has an existing cache (location resolved via [resolveCacheFile]). */
        fun hasCache(projectRoot: File): Boolean =
            resolveCacheFile(projectRoot).exists()

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
        // The metadata table holds schema_version; we have to ensure it exists
        // before we can read the current version. (CREATE TABLE IF NOT EXISTS
        // is idempotent; safe to run on every open.)
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
            currentVersion == 0 -> {
                // Fresh DB — create v7 schema in one shot.
                applyV7Schema()
            }
            currentVersion < 7 -> {
                // v1..v6 → v7: clean break. Drop everything and rebuild.
                // No migration code; data is downstream of source files,
                // so re-typecheck on next mez dev rebuilds it.
                dropV6Schema()
                applyV7Schema()
            }
            currentVersion > SCHEMA_VERSION -> {
                error("cache.sqlite is at schema v$currentVersion but this " +
                      "build only supports up to v$SCHEMA_VERSION. Delete the " +
                      "cache to continue.")
            }
            // currentVersion == SCHEMA_VERSION: nothing to do.
        }

        writeSchemaVersion(SCHEMA_VERSION)
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
     * Drop every v6 table. Called when migrating an existing v6 (or earlier)
     * cache to v7. v7 is a clean break — no data migration. The cache is
     * downstream of source files; mez dev rebuilds it on next run.
     *
     * Order matters: drop FK referrers before referents. But since v6 had
     * `foreign_keys=OFF`, no enforcement happened anyway and order doesn't
     * matter on the way out.
     */
    private fun dropV6Schema() {
        conn.createStatement().use { s ->
            // Temporarily disable FKs while we drop in arbitrary order.
            s.executeUpdate("PRAGMA foreign_keys=OFF")
            for (table in listOf(
                "app_performers", "app_functions", "apps",
                "references_",  // renamed to `usages` in v7
                "effect_performers", "ability_impls",
                "edges", "names",
                "typed_modules", "modules", "blobs"
                // metadata kept; we'll write the new schema_version into it
            )) {
                s.executeUpdate("DROP TABLE IF EXISTS $table")
            }
            s.executeUpdate("PRAGMA foreign_keys=ON")
        }
    }

    /**
     * Create the full v7 schema from scratch. See the design doc for the
     * shape and rationale of every table:
     *   docs/architecture/16-unified-cache-and-incremental-compile.md
     *
     * Highlights:
     *  - foreign_keys=ON; every hash-pointing column has REFERENCES declared
     *  - CHECK constraints on every enum-like column
     *  - ON DELETE CASCADE on natural-cleanup paths
     *  - parsed_modules + decls + imports are NEW (vs v6)
     *  - usages was `references_` in v6
     *  - modules.category, blobs.created_at are NEW
     */
    private fun applyV7Schema() {
        conn.createStatement().use { s ->
            // ----------------------------------------------------------------
            // FILES & MODULES
            // ----------------------------------------------------------------
            s.executeUpdate("""
                -- modules: every Mezze source file the project knows about.
                -- Keyed by namespaced module path (not raw filename).
                CREATE TABLE modules (
                    path        TEXT PRIMARY KEY,
                    file_path   TEXT,
                    source_hash TEXT NOT NULL,
                    category    TEXT NOT NULL CHECK (category IN ('user','stdlib','library'))
                ) WITHOUT ROWID
            """.trimIndent())
            s.executeUpdate("CREATE INDEX idx_modules_category ON modules(category)")

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
                -- target_hash is NOT a FK; the imported decl may live in
                -- another file/module not yet scattered.
                CREATE TABLE imports (
                    module_path   TEXT NOT NULL REFERENCES modules(path) ON DELETE CASCADE,
                    local_name    TEXT NOT NULL,
                    target_module TEXT NOT NULL,
                    target_name   TEXT NOT NULL,
                    target_hash   TEXT NOT NULL,
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

    override fun putBlob(hash: Hash, kind: BlobKind, visibility: Visibility, typed: ByteArray?, ir: ByteArray?) {
        putBlobStmt.apply {
            setString(1, hash.hex)
            setString(2, kind.name)
            setString(3, visibility.name)
            setBytes(4, typed)
            setBytes(5, ir)
            executeUpdate()
        }
    }

    private val getBlobStmt: PreparedStatement by lazy {
        conn.prepareStatement("SELECT hash, kind, visibility, typed, ir FROM blobs WHERE hash = ?")
    }

    override fun getBlob(hash: Hash): BlobRow? {
        getBlobStmt.setString(1, hash.hex)
        return getBlobStmt.executeQuery().use { rs ->
            if (rs.next()) rs.toBlobRow() else null
        }
    }

    private val blobExistsStmt: PreparedStatement by lazy {
        conn.prepareStatement("SELECT 1 FROM blobs WHERE hash = ? LIMIT 1")
    }

    override fun blobExists(hash: Hash): Boolean {
        blobExistsStmt.setString(1, hash.hex)
        return blobExistsStmt.executeQuery().use { it.next() }
    }

    override fun blobsExist(hashes: Collection<Hash>): Set<Hash> {
        if (hashes.isEmpty()) return emptySet()
        val present = HashSet<Hash>(hashes.size)
        // SQLite default SQLITE_MAX_VARIABLE_NUMBER is 999 (older builds) / 32766 (newer).
        // Stay safe with a chunk size of 500.
        for (chunk in hashes.chunked(500)) {
            val placeholders = chunk.joinToString(",") { "?" }
            val sql = "SELECT hash FROM blobs WHERE hash IN ($placeholders)"
            conn.prepareStatement(sql).use { stmt ->
                chunk.forEachIndexed { i, h -> stmt.setString(i + 1, h.hex) }
                stmt.executeQuery().use { rs ->
                    while (rs.next()) present.add(Hash(rs.getString(1)))
                }
            }
        }
        return present
    }

    private val getIRStmt: PreparedStatement by lazy {
        conn.prepareStatement("SELECT ir FROM blobs WHERE hash = ?")
    }

    override fun getIR(hash: Hash): ByteArray? {
        getIRStmt.setString(1, hash.hex)
        return getIRStmt.executeQuery().use { rs ->
            if (rs.next()) rs.getBytes("ir") else null
        }
    }

    /** Get all blob hashes in the store. */
    fun getAllBlobHashes(): List<Hash> {
        val result = mutableListOf<Hash>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT hash FROM blobs").use { rs ->
                while (rs.next()) {
                    result.add(Hash(rs.getString("hash")))
                }
            }
        }
        return result
    }

    // ====================================================================
    // NAMES
    // ====================================================================

    private val registerNameStmt: PreparedStatement by lazy {
        conn.prepareStatement("INSERT OR REPLACE INTO names (module, name, hash) VALUES (?, ?, ?)")
    }

    override fun registerName(module: String, name: String, hash: Hash) {
        registerNameStmt.apply {
            setString(1, module)
            setString(2, name)
            setString(3, hash.hex)
            executeUpdate()
        }
    }

    private val resolveNameStmt: PreparedStatement by lazy {
        conn.prepareStatement("SELECT hash FROM names WHERE module = ? AND name = ?")
    }

    override fun resolveName(module: String, name: String): Hash? {
        resolveNameStmt.setString(1, module)
        resolveNameStmt.setString(2, name)
        return resolveNameStmt.executeQuery().use { rs ->
            if (rs.next()) Hash(rs.getString(1)) else null
        }
    }

    private val getNamesForHashStmt: PreparedStatement by lazy {
        conn.prepareStatement("SELECT module, name, hash FROM names WHERE hash = ?")
    }

    override fun getNamesForHash(hash: Hash): List<NameRow> {
        getNamesForHashStmt.setString(1, hash.hex)
        return getNamesForHashStmt.executeQuery().use { rs -> rs.collectNameRows() }
    }

    private val getModuleExportsStmt: PreparedStatement by lazy {
        conn.prepareStatement("""
            SELECT n.module, n.name, n.hash FROM names n
            JOIN blobs b ON n.hash = b.hash
            WHERE n.module = ? AND b.visibility = 'PUBLIC'
        """.trimIndent())
    }

    override fun getModuleExports(module: String): List<NameRow> {
        getModuleExportsStmt.setString(1, module)
        return getModuleExportsStmt.executeQuery().use { rs -> rs.collectNameRows() }
    }

    private val searchExportsStmt: PreparedStatement by lazy {
        conn.prepareStatement("""
            SELECT n.module, n.name, n.hash FROM names n
            JOIN blobs b ON n.hash = b.hash
            WHERE n.name LIKE ? AND b.visibility = 'PUBLIC'
            LIMIT 50
        """.trimIndent())
    }

    override fun searchExportsByName(query: String): List<NameRow> {
        searchExportsStmt.setString(1, "$query%")
        return searchExportsStmt.executeQuery().use { rs -> rs.collectNameRows() }
    }

    // ====================================================================
    // MODULES
    // ====================================================================

    private val putModuleStmt: PreparedStatement by lazy {
        conn.prepareStatement("INSERT OR REPLACE INTO modules (path, file_path, source_hash, category) VALUES (?, ?, ?, ?)")
    }

    override fun putModule(path: String, filePath: String?, sourceHash: String, category: ModuleCategory) {
        putModuleStmt.apply {
            setString(1, path)
            setString(2, filePath)
            setString(3, sourceHash)
            setString(4, category.dbValue)
            executeUpdate()
        }
    }

    private val getModuleStmt: PreparedStatement by lazy {
        conn.prepareStatement("SELECT path, file_path, source_hash, category FROM modules WHERE path = ?")
    }

    override fun getModule(path: String): ModuleRow? {
        getModuleStmt.setString(1, path)
        return getModuleStmt.executeQuery().use { rs ->
            if (rs.next()) ModuleRow(
                path = rs.getString("path"),
                filePath = rs.getString("file_path"),
                sourceHash = rs.getString("source_hash"),
                category = ModuleCategory.fromDb(rs.getString("category"))
            ) else null
        }
    }

    private val isFileStaleStmt: PreparedStatement by lazy {
        conn.prepareStatement("SELECT 1 FROM modules WHERE file_path = ? AND source_hash = ? LIMIT 1")
    }

    override fun isFileStale(filePath: String, currentSourceHash: String): Boolean {
        isFileStaleStmt.setString(1, filePath)
        isFileStaleStmt.setString(2, currentSourceHash)
        return !isFileStaleStmt.executeQuery().use { it.next() }
    }

    override fun getAllModules(): List<ModuleRow> {
        return conn.createStatement().use { s ->
            s.executeQuery("SELECT path, file_path, source_hash, category FROM modules").use { rs ->
                val list = mutableListOf<ModuleRow>()
                while (rs.next()) {
                    list.add(ModuleRow(
                        rs.getString("path"),
                        rs.getString("file_path"),
                        rs.getString("source_hash"),
                        ModuleCategory.fromDb(rs.getString("category"))
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

    override fun addEdge(fromHash: Hash, toHash: Hash, kind: EdgeKind) {
        addEdgeStmt.apply {
            setString(1, fromHash.hex)
            setString(2, toHash.hex)
            setString(3, kind.name)
            executeUpdate()
        }
    }

    private val getEdgesFromStmt: PreparedStatement by lazy {
        conn.prepareStatement("SELECT from_hash, to_hash, kind FROM edges WHERE from_hash = ?")
    }

    override fun getEdgesFrom(hash: Hash): List<EdgeRow> {
        getEdgesFromStmt.setString(1, hash.hex)
        return getEdgesFromStmt.executeQuery().use { rs -> rs.collectEdgeRows() }
    }

    private val getEdgesToStmt: PreparedStatement by lazy {
        conn.prepareStatement("SELECT from_hash, to_hash, kind FROM edges WHERE to_hash = ?")
    }

    override fun getEdgesTo(hash: Hash): List<EdgeRow> {
        getEdgesToStmt.setString(1, hash.hex)
        return getEdgesToStmt.executeQuery().use { rs -> rs.collectEdgeRows() }
    }

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
        conn.prepareStatement("SELECT hash, ability_hash, target_type, module FROM ability_impls WHERE ability_hash = ?")
    }

    override fun getAbilityImpls(abilityHash: Hash): List<AbilityImplRow> {
        getAbilityImplsStmt.setString(1, abilityHash.hex)
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
        conn.prepareStatement("SELECT hash FROM ability_impls WHERE ability_hash = ? AND target_type = ? LIMIT 1")
    }

    override fun getAbilityImplForType(abilityHash: Hash, targetType: String): Hash? {
        getAbilityImplForTypeStmt.setString(1, abilityHash.hex)
        getAbilityImplForTypeStmt.setString(2, targetType)
        return getAbilityImplForTypeStmt.executeQuery().use { rs ->
            if (rs.next()) Hash(rs.getString("hash")) else null
        }
    }

    private val putEffectPerformerStmt: PreparedStatement by lazy {
        conn.prepareStatement("INSERT OR REPLACE INTO effect_performers (hash, effect_hash, performer_name, module) VALUES (?, ?, ?, ?)")
    }

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
        conn.prepareStatement("SELECT hash, effect_hash, performer_name, module FROM effect_performers WHERE effect_hash = ?")
    }

    override fun getEffectPerformers(effectHash: Hash): List<EffectPerformerRow> {
        getEffectPerformersStmt.setString(1, effectHash.hex)
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

    override fun getMetadata(key: String): String? {
        getMetadataStmt.setString(1, key)
        return getMetadataStmt.executeQuery().use { rs ->
            if (rs.next()) rs.getString(1) else null
        }
    }

    private val setMetadataStmt: PreparedStatement by lazy {
        conn.prepareStatement("INSERT OR REPLACE INTO metadata (key, value) VALUES (?, ?)")
    }

    override fun setMetadata(key: String, value: String) {
        setMetadataStmt.apply {
            setString(1, key)
            setString(2, value)
            executeUpdate()
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

    override fun setAppFunctionHashes(name: String, hashes: List<Hash>) {
        conn.prepareStatement("DELETE FROM app_functions WHERE app_name = ?").use { stmt ->
            stmt.setString(1, name)
            stmt.executeUpdate()
        }
        if (hashes.isEmpty()) return
        conn.prepareStatement("INSERT INTO app_functions (app_name, hash, ord) VALUES (?, ?, ?)").use { stmt ->
            hashes.forEachIndexed { i, h ->
                stmt.setString(1, name)
                stmt.setString(2, h.hex)
                stmt.setInt(3, i)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
    }

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
            // Schema says NOT NULL but the column isn't FK'd; use empty string
            // when the target hasn't been scattered yet.
            setString(5, targetHash?.hex ?: "")
            setInt(6, startLine)
            setInt(7, startCol)
            setInt(8, endLine)
            setInt(9, endCol)
            executeUpdate()
        }
    }

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
        conn.prepareStatement("""
            SELECT module_path, local_name, target_module, target_name, target_hash,
                   start_line, start_col, end_line, end_col
            FROM imports
            WHERE module_path = ?
        """.trimIndent())
    }

    override fun getImportsForModule(modulePath: String): List<ImportRow> {
        getImportsForModuleStmt.setString(1, modulePath)
        return getImportsForModuleStmt.executeQuery().use { rs ->
            buildList { while (rs.next()) add(rs.toImportRow()) }
        }
    }

    private val getImportersOfStmt: PreparedStatement by lazy {
        conn.prepareStatement("""
            SELECT module_path, local_name, target_module, target_name, target_hash,
                   start_line, start_col, end_line, end_col
            FROM imports
            WHERE target_module = ? AND target_name = ?
        """.trimIndent())
    }

    override fun getImportersOf(targetModule: String, targetName: String): List<ImportRow> {
        getImportersOfStmt.setString(1, targetModule)
        getImportersOfStmt.setString(2, targetName)
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
                 start_line, start_col, end_line, end_col)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent())
    }

    override fun putDecl(
        modulePath: String,
        declName: String,
        declKind: DeclKind,
        sourceHash: String,
        hash: Hash,
        startLine: Int,
        startCol: Int,
        endLine: Int,
        endCol: Int
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
            executeUpdate()
        }
    }

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
        endCol = getInt("end_col")
    )

    private val getDeclsForModuleStmt: PreparedStatement by lazy {
        conn.prepareStatement("""
            SELECT module_path, decl_name, decl_kind, source_hash, hash,
                   start_line, start_col, end_line, end_col
            FROM decls
            WHERE module_path = ?
        """.trimIndent())
    }

    override fun getDeclsForModule(modulePath: String): List<DeclRow> {
        getDeclsForModuleStmt.setString(1, modulePath)
        return getDeclsForModuleStmt.executeQuery().use { rs ->
            buildList { while (rs.next()) add(rs.toDeclRow()) }
        }
    }

    private val searchDeclsByNameStmt: PreparedStatement by lazy {
        conn.prepareStatement("""
            SELECT module_path, decl_name, decl_kind, source_hash, hash,
                   start_line, start_col, end_line, end_col
            FROM decls
            WHERE decl_name LIKE ? || '%'
            ORDER BY decl_name
            LIMIT 200
        """.trimIndent())
    }

    override fun searchDeclsByName(query: String): List<DeclRow> {
        searchDeclsByNameStmt.setString(1, query)
        return searchDeclsByNameStmt.executeQuery().use { rs ->
            buildList { while (rs.next()) add(rs.toDeclRow()) }
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

    override fun putParsedModule(modulePath: String, sourceHash: String, parsedBlob: ByteArray) {
        putParsedModuleStmt.apply {
            setString(1, modulePath)
            setString(2, sourceHash)
            setBytes(3, parsedBlob)
            setLong(4, System.currentTimeMillis())
            executeUpdate()
        }
    }

    private val getParsedModuleStmt: PreparedStatement by lazy {
        conn.prepareStatement(
            "SELECT parsed_blob FROM parsed_modules WHERE module_path = ? AND source_hash = ?"
        )
    }

    override fun getParsedModule(modulePath: String, sourceHash: String): ByteArray? {
        getParsedModuleStmt.setString(1, modulePath)
        getParsedModuleStmt.setString(2, sourceHash)
        return getParsedModuleStmt.executeQuery().use { rs ->
            if (rs.next()) rs.getBytes(1) else null
        }
    }

    override fun clearParsedModulesForPath(modulePath: String) {
        conn.prepareStatement("DELETE FROM parsed_modules WHERE module_path = ?").use { stmt ->
            stmt.setString(1, modulePath)
            stmt.executeUpdate()
        }
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

    override fun putTypedModule(modulePath: String, sourceHash: String, typedBlob: ByteArray) {
        putTypedModuleStmt.apply {
            setString(1, modulePath)
            setString(2, sourceHash)
            setBytes(3, typedBlob)
            setLong(4, System.currentTimeMillis())
            executeUpdate()
        }
    }

    private val getTypedModuleStmt: PreparedStatement by lazy {
        conn.prepareStatement(
            "SELECT typed_blob FROM typed_modules WHERE module_path = ? AND source_hash = ?"
        )
    }

    override fun getTypedModule(modulePath: String, sourceHash: String): ByteArray? {
        getTypedModuleStmt.setString(1, modulePath)
        getTypedModuleStmt.setString(2, sourceHash)
        return getTypedModuleStmt.executeQuery().use { rs ->
            if (rs.next()) rs.getBytes(1) else null
        }
    }

    override fun clearTypedModulesForPath(modulePath: String) {
        conn.prepareStatement("DELETE FROM typed_modules WHERE module_path = ?").use { stmt ->
            stmt.setString(1, modulePath)
            stmt.executeUpdate()
        }
    }

    // ====================================================================
    // LIFECYCLE
    // ====================================================================

    override fun <T> transaction(block: () -> T): T {
        val wasAutoCommit = conn.autoCommit
        conn.autoCommit = false
        return try {
            val result = block()
            conn.commit()
            result
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = wasAutoCommit
        }
    }

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
