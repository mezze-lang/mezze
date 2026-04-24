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

    companion object {
        const val SCHEMA_VERSION = 4
        const val PROJECT_DIR = ".mz"
        const val CACHE_FILE = "cache.sqlite"
        const val DEPS_FILE = "deps.sqlite"

        /** Open or create project cache at `.mz/cache.sqlite`. */
        fun forProject(projectRoot: File): SQLiteMezzeStore {
            val mezDir = File(projectRoot, PROJECT_DIR)
            if (!mezDir.exists()) mezDir.mkdirs()
            val dbFile = File(mezDir, CACHE_FILE)
            val conn = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")
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

        /** Check if project has an existing cache. */
        fun hasCache(projectRoot: File): Boolean =
            File(projectRoot, "$PROJECT_DIR/$CACHE_FILE").exists()

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
            s.executeUpdate("PRAGMA foreign_keys=OFF")
        }
    }

    private fun initSchema() {
        val currentVersion = try {
            conn.createStatement().use { s ->
                s.executeQuery("SELECT value FROM metadata WHERE key = 'schema_version'").use { rs ->
                    if (rs.next()) rs.getString(1).toIntOrNull() ?: 0 else 0
                }
            }
        } catch (_: Exception) { 0 }

        if (currentVersion < SCHEMA_VERSION) {
            createTables()
            conn.createStatement().use { s ->
                s.executeUpdate("INSERT OR REPLACE INTO metadata (key, value) VALUES ('schema_version', '$SCHEMA_VERSION')")
            }
        }
    }

    private fun createTables() {
        conn.createStatement().use { s ->
            // Tier 6: Metadata (created first — schema version lives here)
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS metadata (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                ) WITHOUT ROWID
            """.trimIndent())

            // Tier 1: Content-addressed storage
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS blobs (
                    hash TEXT PRIMARY KEY,
                    kind TEXT NOT NULL,
                    visibility TEXT NOT NULL DEFAULT 'PRIVATE',
                    typed BLOB,
                    ir BLOB
                ) WITHOUT ROWID
            """.trimIndent())

            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS names (
                    module TEXT NOT NULL,
                    name TEXT NOT NULL,
                    hash TEXT NOT NULL,
                    PRIMARY KEY (module, name)
                ) WITHOUT ROWID
            """.trimIndent())
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_names_hash ON names(hash)")

            // Tier 2: Source tracking
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS modules (
                    path TEXT PRIMARY KEY,
                    file_path TEXT,
                    source_hash TEXT NOT NULL
                ) WITHOUT ROWID
            """.trimIndent())

            // Tier 3: Dependency graph
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS edges (
                    from_hash TEXT NOT NULL,
                    to_hash TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    PRIMARY KEY (from_hash, to_hash, kind)
                ) WITHOUT ROWID
            """.trimIndent())
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_edges_to ON edges(to_hash)")

            // Tier 4: Ability/Effect index
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS ability_impls (
                    hash TEXT PRIMARY KEY,
                    ability_hash TEXT NOT NULL,
                    target_type TEXT NOT NULL,
                    module TEXT
                ) WITHOUT ROWID
            """.trimIndent())
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_ability_impls_ability ON ability_impls(ability_hash)")
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_ability_impls_type ON ability_impls(target_type)")

            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS effect_performers (
                    hash TEXT PRIMARY KEY,
                    effect_hash TEXT NOT NULL,
                    performer_name TEXT NOT NULL,
                    module TEXT
                ) WITHOUT ROWID
            """.trimIndent())
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_effect_performers_effect ON effect_performers(effect_hash)")

            // Tier 5: LSP source locations
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS references_ (
                    target_hash TEXT NOT NULL,
                    file_path TEXT NOT NULL,
                    start_line INTEGER NOT NULL,
                    start_col INTEGER NOT NULL,
                    end_line INTEGER NOT NULL,
                    end_col INTEGER NOT NULL
                )
            """.trimIndent())
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_references_target ON references_(target_hash)")
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_references_file ON references_(file_path)")
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

    private val getIRStmt: PreparedStatement by lazy {
        conn.prepareStatement("SELECT ir FROM blobs WHERE hash = ?")
    }

    override fun getIR(hash: Hash): ByteArray? {
        getIRStmt.setString(1, hash.hex)
        return getIRStmt.executeQuery().use { rs ->
            if (rs.next()) rs.getBytes("ir") else null
        }
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
        conn.prepareStatement("INSERT OR REPLACE INTO modules (path, file_path, source_hash) VALUES (?, ?, ?)")
    }

    override fun putModule(path: String, filePath: String?, sourceHash: String) {
        putModuleStmt.apply {
            setString(1, path)
            setString(2, filePath)
            setString(3, sourceHash)
            executeUpdate()
        }
    }

    private val getModuleStmt: PreparedStatement by lazy {
        conn.prepareStatement("SELECT path, file_path, source_hash FROM modules WHERE path = ?")
    }

    override fun getModule(path: String): ModuleRow? {
        getModuleStmt.setString(1, path)
        return getModuleStmt.executeQuery().use { rs ->
            if (rs.next()) ModuleRow(
                path = rs.getString("path"),
                filePath = rs.getString("file_path"),
                sourceHash = rs.getString("source_hash")
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
            s.executeQuery("SELECT path, file_path, source_hash FROM modules").use { rs ->
                val list = mutableListOf<ModuleRow>()
                while (rs.next()) {
                    list.add(ModuleRow(rs.getString("path"), rs.getString("file_path"), rs.getString("source_hash")))
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
    // REFERENCES (LSP)
    // ====================================================================

    private val putReferenceStmt: PreparedStatement by lazy {
        conn.prepareStatement("""
            INSERT INTO references_ (target_hash, file_path, start_line, start_col, end_line, end_col)
            VALUES (?, ?, ?, ?, ?, ?)
        """.trimIndent())
    }

    override fun putReference(targetHash: Hash, filePath: String, startLine: Int, startCol: Int, endLine: Int, endCol: Int) {
        putReferenceStmt.apply {
            setString(1, targetHash.hex)
            setString(2, filePath)
            setInt(3, startLine)
            setInt(4, startCol)
            setInt(5, endLine)
            setInt(6, endCol)
            executeUpdate()
        }
    }

    private val getReferencesStmt: PreparedStatement by lazy {
        conn.prepareStatement("SELECT target_hash, file_path, start_line, start_col, end_line, end_col FROM references_ WHERE target_hash = ?")
    }

    override fun getReferences(targetHash: Hash): List<ReferenceRow> {
        getReferencesStmt.setString(1, targetHash.hex)
        return getReferencesStmt.executeQuery().use { rs ->
            val list = mutableListOf<ReferenceRow>()
            while (rs.next()) {
                list.add(ReferenceRow(
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

    private val clearReferencesStmt: PreparedStatement by lazy {
        conn.prepareStatement("DELETE FROM references_ WHERE file_path = ?")
    }

    override fun clearReferencesForFile(filePath: String) {
        clearReferencesStmt.setString(1, filePath)
        clearReferencesStmt.executeUpdate()
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
