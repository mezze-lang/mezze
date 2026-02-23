package com.mezze.language.codebase

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.hashing.MezzeHash
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * SQLite-backed codebase implementation.
 *
 * This is the primary implementation used for:
 * - Project mode (.mez/codebase.db)
 * - Build output (embedded in JAR)
 * - LSP server
 *
 * ## Thread Safety
 * SQLite with WAL mode supports concurrent reads with a single writer.
 * Use transactions for batch operations.
 *
 * ## Performance Notes
 * - Uses prepared statements for all queries
 * - Batch inserts use transactions
 * - WAL mode for concurrent access
 * - Indexes on frequently queried columns
 */
class SQLiteCodebase private constructor(
    private val connection: Connection,
    private val readOnly: Boolean = false
) : Codebase {

    companion object {
        const val SCHEMA_VERSION = 1
        const val PROJECT_DIR = ".mez"
        const val CODEBASE_FILE = "codebase.db"

        /**
         * Open or create a project-based codebase at .mez/codebase.db
         *
         * @param projectRoot The project root directory (containing Mezze.toml)
         * @param readOnly Whether to open in read-only mode
         * @return The codebase instance
         */
        fun forProject(projectRoot: File, readOnly: Boolean = false): SQLiteCodebase {
            val mezDir = File(projectRoot, PROJECT_DIR)
            if (!readOnly && !mezDir.exists()) {
                mezDir.mkdirs()
            }
            val dbFile = File(mezDir, CODEBASE_FILE)
            return open(dbFile, readOnly)
        }

        /**
         * Check if a project has an existing codebase.
         */
        fun hasCodebase(projectRoot: File): Boolean {
            return File(projectRoot, "$PROJECT_DIR/$CODEBASE_FILE").exists()
        }

        /**
         * Open or create a file-based codebase.
         */
        fun open(path: File, readOnly: Boolean = false): SQLiteCodebase {
            val url = if (readOnly) {
                "jdbc:sqlite:file:${path.absolutePath}?mode=ro"
            } else {
                "jdbc:sqlite:${path.absolutePath}"
            }
            val connection = DriverManager.getConnection(url)
            val codebase = SQLiteCodebase(connection, readOnly)
            if (!readOnly) {
                codebase.initializeSchema()
                codebase.configurePragmas()
            }
            return codebase
        }

        /**
         * Create an in-memory codebase.
         */
        fun inMemory(): SQLiteCodebase {
            val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
            val codebase = SQLiteCodebase(connection, false)
            codebase.initializeSchema()
            codebase.configurePragmas()
            return codebase
        }

        /**
         * Load a codebase from bytes into memory (for JAR runtime).
         */
        fun fromBytes(bytes: ByteArray): SQLiteCodebase {
            // Create in-memory database
            val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
            val codebase = SQLiteCodebase(connection, true)

            // Write bytes to temp file, then restore
            val tempFile = File.createTempFile("codebase", ".db")
            try {
                tempFile.writeBytes(bytes)
                // Use SQLite backup API via JDBC
                connection.createStatement().use { stmt ->
                    stmt.executeUpdate("ATTACH DATABASE '${tempFile.absolutePath}' AS source")
                    // Copy all tables from source to main
                    val tables = mutableListOf<String>()
                    stmt.executeQuery("SELECT name FROM source.sqlite_master WHERE type='table'").use { rs ->
                        while (rs.next()) {
                            tables.add(rs.getString(1))
                        }
                    }
                    // First create schema in main
                    codebase.initializeSchema()
                    // Then copy data
                    for (table in tables) {
                        if (table != "sqlite_sequence") {
                            stmt.executeUpdate("INSERT OR REPLACE INTO main.$table SELECT * FROM source.$table")
                        }
                    }
                    stmt.executeUpdate("DETACH DATABASE source")
                }
            } finally {
                tempFile.delete()
            }

            return codebase
        }

        /**
         * Export codebase to bytes (for embedding in JAR).
         */
        fun toBytes(codebase: SQLiteCodebase): ByteArray {
            val tempFile = File.createTempFile("codebase_export", ".db")
            try {
                // Use backup API to copy to temp file
                codebase.connection.createStatement().use { stmt ->
                    stmt.executeUpdate("ATTACH DATABASE '${tempFile.absolutePath}' AS export_db")

                    // Get list of tables
                    val tables = mutableListOf<String>()
                    stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table'").use { rs ->
                        while (rs.next()) {
                            val tableName = rs.getString(1)
                            if (tableName != "sqlite_sequence") {
                                tables.add(tableName)
                            }
                        }
                    }

                    // Create tables and copy data
                    for (table in tables) {
                        // Get table schema
                        stmt.executeQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name='$table'").use { rs ->
                            if (rs.next()) {
                                val createSql = rs.getString(1)
                                if (createSql != null) {
                                    val exportCreateSql = createSql.replaceFirst("CREATE TABLE", "CREATE TABLE IF NOT EXISTS export_db.")
                                    stmt.executeUpdate(exportCreateSql)
                                }
                            }
                        }
                        // Copy data
                        stmt.executeUpdate("INSERT INTO export_db.$table SELECT * FROM main.$table")
                    }

                    stmt.executeUpdate("DETACH DATABASE export_db")
                }
                return tempFile.readBytes()
            } finally {
                tempFile.delete()
            }
        }
    }

    private fun configurePragmas() {
        connection.createStatement().use { stmt ->
            // WAL mode for concurrent access
            stmt.executeUpdate("PRAGMA journal_mode=WAL")
            // Faster syncs (still safe with WAL)
            stmt.executeUpdate("PRAGMA synchronous=NORMAL")
            // Store temp tables in memory
            stmt.executeUpdate("PRAGMA temp_store=MEMORY")
            // Increase cache size (negative = KB)
            stmt.executeUpdate("PRAGMA cache_size=-64000")  // 64MB
            // Disable foreign keys - this is a cache, not a source of truth
            // FK constraints cause issues when updating definitions (hash changes)
            stmt.executeUpdate("PRAGMA foreign_keys=OFF")
            // Memory-mapped I/O
            stmt.executeUpdate("PRAGMA mmap_size=268435456")  // 256MB
        }
    }

    private fun initializeSchema() {
        connection.createStatement().use { stmt ->
            // Metadata table
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS metadata (
                    key TEXT PRIMARY KEY,
                    value TEXT
                )
            """.trimIndent())

            // Check schema version
            val currentVersion = getMetadata("schema_version")?.toIntOrNull() ?: 0
            if (currentVersion < SCHEMA_VERSION) {
                createCoreTables(stmt)
                setMetadata("schema_version", SCHEMA_VERSION.toString())
            }
        }
    }

    private fun createCoreTables(stmt: java.sql.Statement) {
        // Definitions table
        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS definitions (
                hash TEXT PRIMARY KEY,
                kind TEXT NOT NULL,
                name TEXT NOT NULL,
                module_path TEXT,
                visibility TEXT DEFAULT 'private',
                ast_blob BLOB NOT NULL,
                source_hash TEXT,
                created_at INTEGER DEFAULT (strftime('%s', 'now'))
            )
        """.trimIndent())
        stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_definitions_kind ON definitions(kind)")
        stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_definitions_module ON definitions(module_path)")
        stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_definitions_name ON definitions(name)")

        // Types table
        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS types (
                hash TEXT PRIMARY KEY,
                type_blob BLOB NOT NULL,
                type_text TEXT NOT NULL,
                FOREIGN KEY (hash) REFERENCES definitions(hash)
            )
        """.trimIndent())

        // Names table
        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS names (
                module_path TEXT,
                name TEXT NOT NULL,
                hash TEXT NOT NULL,
                is_primary INTEGER DEFAULT 1,
                PRIMARY KEY (module_path, name),
                FOREIGN KEY (hash) REFERENCES definitions(hash)
            )
        """.trimIndent())
        stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_names_hash ON names(hash)")

        // Dependencies table
        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS dependencies (
                from_hash TEXT NOT NULL,
                to_hash TEXT NOT NULL,
                dep_kind TEXT NOT NULL,
                PRIMARY KEY (from_hash, to_hash),
                FOREIGN KEY (from_hash) REFERENCES definitions(hash),
                FOREIGN KEY (to_hash) REFERENCES definitions(hash)
            )
        """.trimIndent())
        stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_dependencies_to ON dependencies(to_hash)")

        // Modules table
        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS modules (
                path TEXT PRIMARY KEY,
                file_path TEXT,
                source_hash TEXT,
                is_stdlib INTEGER DEFAULT 0,
                is_external_dep INTEGER DEFAULT 0,
                package_name TEXT,
                package_version TEXT,
                visibility TEXT DEFAULT 'public',
                doc_comment TEXT
            )
        """.trimIndent())

        // Exports table
        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS exports (
                module_path TEXT NOT NULL,
                export_name TEXT NOT NULL,
                hash TEXT NOT NULL,
                original_name TEXT,
                kind TEXT NOT NULL,
                PRIMARY KEY (module_path, export_name),
                FOREIGN KEY (module_path) REFERENCES modules(path),
                FOREIGN KEY (hash) REFERENCES definitions(hash)
            )
        """.trimIndent())
        stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_exports_hash ON exports(hash)")
    }

    // ========================================================================
    // DEFINITIONS
    // ========================================================================

    private val putDefinitionStmt: PreparedStatement by lazy {
        connection.prepareStatement("""
            INSERT OR REPLACE INTO definitions (hash, kind, name, module_path, visibility, ast_blob, source_hash)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """.trimIndent())
    }

    override fun putDefinition(definition: StoredDefinition) {
        putDefinitionStmt.apply {
            setString(1, definition.hash.toFullHexString())
            setString(2, definition.kind.name)
            setString(3, definition.name)
            setString(4, definition.modulePath)
            setString(5, definition.visibility.name)
            setBytes(6, definition.astBlob)
            setString(7, definition.sourceHash)
            executeUpdate()
        }
    }

    private val getDefinitionStmt: PreparedStatement by lazy {
        connection.prepareStatement("""
            SELECT hash, kind, name, module_path, visibility, ast_blob, source_hash
            FROM definitions WHERE hash = ?
        """.trimIndent())
    }

    override fun getDefinition(hash: MezzeHash): StoredDefinition? {
        getDefinitionStmt.setString(1, hash.toFullHexString())
        return getDefinitionStmt.executeQuery().use { rs ->
            if (rs.next()) rs.toStoredDefinition() else null
        }
    }

    override fun hasDefinition(hash: MezzeHash): Boolean {
        connection.prepareStatement("SELECT 1 FROM definitions WHERE hash = ?").use { stmt ->
            stmt.setString(1, hash.toFullHexString())
            return stmt.executeQuery().next()
        }
    }

    override fun getAllDefinitionHashes(): Set<MezzeHash> {
        val hashes = mutableSetOf<MezzeHash>()
        connection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT hash FROM definitions").use { rs ->
                while (rs.next()) {
                    hashes.add(MezzeHash.fromHex(rs.getString(1)))
                }
            }
        }
        return hashes
    }

    override fun getDefinitionsByKind(kind: DefinitionKind): List<StoredDefinition> {
        val definitions = mutableListOf<StoredDefinition>()
        connection.prepareStatement("""
            SELECT hash, kind, name, module_path, visibility, ast_blob, source_hash
            FROM definitions WHERE kind = ?
        """.trimIndent()).use { stmt ->
            stmt.setString(1, kind.name)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    definitions.add(rs.toStoredDefinition())
                }
            }
        }
        return definitions
    }

    override fun getDefinitionsByModule(modulePath: String): List<StoredDefinition> {
        val definitions = mutableListOf<StoredDefinition>()
        connection.prepareStatement("""
            SELECT hash, kind, name, module_path, visibility, ast_blob, source_hash
            FROM definitions WHERE module_path = ?
        """.trimIndent()).use { stmt ->
            stmt.setString(1, modulePath)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    definitions.add(rs.toStoredDefinition())
                }
            }
        }
        return definitions
    }

    // ========================================================================
    // NAMES
    // ========================================================================

    override fun putName(modulePath: String?, name: String, hash: MezzeHash, isPrimary: Boolean) {
        connection.prepareStatement("""
            INSERT OR REPLACE INTO names (module_path, name, hash, is_primary)
            VALUES (?, ?, ?, ?)
        """.trimIndent()).use { stmt ->
            stmt.setString(1, modulePath ?: "")
            stmt.setString(2, name)
            stmt.setString(3, hash.toFullHexString())
            stmt.setInt(4, if (isPrimary) 1 else 0)
            stmt.executeUpdate()
        }
    }

    override fun getHashByName(modulePath: String?, name: String): MezzeHash? {
        connection.prepareStatement("SELECT hash FROM names WHERE module_path = ? AND name = ?").use { stmt ->
            stmt.setString(1, modulePath ?: "")
            stmt.setString(2, name)
            return stmt.executeQuery().use { rs ->
                if (rs.next()) MezzeHash.fromHex(rs.getString(1)) else null
            }
        }
    }

    override fun getNamesByHash(hash: MezzeHash): List<QualifiedName> {
        val names = mutableListOf<QualifiedName>()
        connection.prepareStatement("SELECT module_path, name, is_primary FROM names WHERE hash = ?").use { stmt ->
            stmt.setString(1, hash.toFullHexString())
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    val modulePath = rs.getString(1).takeIf { it.isNotEmpty() }
                    names.add(QualifiedName(modulePath, rs.getString(2), rs.getInt(3) == 1))
                }
            }
        }
        return names
    }

    override fun removeName(modulePath: String?, name: String) {
        connection.prepareStatement("DELETE FROM names WHERE module_path = ? AND name = ?").use { stmt ->
            stmt.setString(1, modulePath ?: "")
            stmt.setString(2, name)
            stmt.executeUpdate()
        }
    }

    // ========================================================================
    // TYPES
    // ========================================================================

    override fun putType(hash: MezzeHash, type: MezzeType, typeText: String) {
        connection.prepareStatement("""
            INSERT OR REPLACE INTO types (hash, type_blob, type_text)
            VALUES (?, ?, ?)
        """.trimIndent()).use { stmt ->
            stmt.setString(1, hash.toFullHexString())
            stmt.setBytes(2, CodebaseSerialization.serializeType(type))
            stmt.setString(3, typeText)
            stmt.executeUpdate()
        }
    }

    override fun getType(hash: MezzeHash): StoredType? {
        connection.prepareStatement("SELECT hash, type_blob, type_text FROM types WHERE hash = ?").use { stmt ->
            stmt.setString(1, hash.toFullHexString())
            return stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    StoredType(
                        hash = MezzeHash.fromHex(rs.getString(1)),
                        typeBlob = rs.getBytes(2),
                        typeText = rs.getString(3)
                    )
                } else null
            }
        }
    }

    // ========================================================================
    // DEPENDENCIES
    // ========================================================================

    override fun addDependency(fromHash: MezzeHash, toHash: MezzeHash, kind: DependencyKind) {
        connection.prepareStatement("""
            INSERT OR IGNORE INTO dependencies (from_hash, to_hash, dep_kind)
            VALUES (?, ?, ?)
        """.trimIndent()).use { stmt ->
            stmt.setString(1, fromHash.toFullHexString())
            stmt.setString(2, toHash.toFullHexString())
            stmt.setString(3, kind.name)
            stmt.executeUpdate()
        }
    }

    override fun getDependencies(hash: MezzeHash): List<Dependency> {
        val deps = mutableListOf<Dependency>()
        connection.prepareStatement("SELECT from_hash, to_hash, dep_kind FROM dependencies WHERE from_hash = ?").use { stmt ->
            stmt.setString(1, hash.toFullHexString())
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    deps.add(Dependency(
                        fromHash = MezzeHash.fromHex(rs.getString(1)),
                        toHash = MezzeHash.fromHex(rs.getString(2)),
                        kind = DependencyKind.valueOf(rs.getString(3))
                    ))
                }
            }
        }
        return deps
    }

    override fun getDependents(hash: MezzeHash): List<Dependency> {
        val deps = mutableListOf<Dependency>()
        connection.prepareStatement("SELECT from_hash, to_hash, dep_kind FROM dependencies WHERE to_hash = ?").use { stmt ->
            stmt.setString(1, hash.toFullHexString())
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    deps.add(Dependency(
                        fromHash = MezzeHash.fromHex(rs.getString(1)),
                        toHash = MezzeHash.fromHex(rs.getString(2)),
                        kind = DependencyKind.valueOf(rs.getString(3))
                    ))
                }
            }
        }
        return deps
    }

    override fun clearDependencies(fromHash: MezzeHash) {
        connection.prepareStatement("DELETE FROM dependencies WHERE from_hash = ?").use { stmt ->
            stmt.setString(1, fromHash.toFullHexString())
            stmt.executeUpdate()
        }
    }

    // ========================================================================
    // MODULES
    // ========================================================================

    override fun putModule(module: StoredModule) {
        connection.prepareStatement("""
            INSERT OR REPLACE INTO modules
            (path, file_path, source_hash, is_stdlib, is_external_dep, package_name, package_version, visibility, doc_comment)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()).use { stmt ->
            stmt.setString(1, module.path)
            stmt.setString(2, module.filePath)
            stmt.setString(3, module.sourceHash)
            stmt.setInt(4, if (module.isStdlib) 1 else 0)
            stmt.setInt(5, if (module.isExternalDep) 1 else 0)
            stmt.setString(6, module.packageName)
            stmt.setString(7, module.packageVersion)
            stmt.setString(8, module.visibility.name)
            stmt.setString(9, module.docComment)
            stmt.executeUpdate()
        }
    }

    override fun getModule(path: String): StoredModule? {
        connection.prepareStatement("SELECT * FROM modules WHERE path = ?").use { stmt ->
            stmt.setString(1, path)
            return stmt.executeQuery().use { rs ->
                if (rs.next()) rs.toStoredModule() else null
            }
        }
    }

    override fun getAllModules(): List<StoredModule> {
        val modules = mutableListOf<StoredModule>()
        connection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT * FROM modules").use { rs ->
                while (rs.next()) {
                    modules.add(rs.toStoredModule())
                }
            }
        }
        return modules
    }

    override fun hasModule(path: String): Boolean {
        connection.prepareStatement("SELECT 1 FROM modules WHERE path = ?").use { stmt ->
            stmt.setString(1, path)
            return stmt.executeQuery().next()
        }
    }

    // ========================================================================
    // EXPORTS
    // ========================================================================

    override fun putExport(modulePath: String, exportName: String, hash: MezzeHash, kind: DefinitionKind) {
        connection.prepareStatement("""
            INSERT OR REPLACE INTO exports (module_path, export_name, hash, kind)
            VALUES (?, ?, ?, ?)
        """.trimIndent()).use { stmt ->
            stmt.setString(1, modulePath)
            stmt.setString(2, exportName)
            stmt.setString(3, hash.toFullHexString())
            stmt.setString(4, kind.name)
            stmt.executeUpdate()
        }
    }

    override fun getExports(modulePath: String): List<StoredExport> {
        val exports = mutableListOf<StoredExport>()
        connection.prepareStatement("SELECT * FROM exports WHERE module_path = ?").use { stmt ->
            stmt.setString(1, modulePath)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    exports.add(rs.toStoredExport())
                }
            }
        }
        return exports
    }

    override fun getExport(modulePath: String, exportName: String): StoredExport? {
        connection.prepareStatement("SELECT * FROM exports WHERE module_path = ? AND export_name = ?").use { stmt ->
            stmt.setString(1, modulePath)
            stmt.setString(2, exportName)
            return stmt.executeQuery().use { rs ->
                if (rs.next()) rs.toStoredExport() else null
            }
        }
    }

    // ========================================================================
    // BULK OPERATIONS
    // ========================================================================

    override fun beginTransaction() {
        connection.autoCommit = false
    }

    override fun commit() {
        connection.commit()
        connection.autoCommit = true
    }

    override fun rollback() {
        connection.rollback()
        connection.autoCommit = true
    }

    override fun clear() {
        connection.createStatement().use { stmt ->
            stmt.executeUpdate("DELETE FROM exports")
            stmt.executeUpdate("DELETE FROM dependencies")
            stmt.executeUpdate("DELETE FROM names")
            stmt.executeUpdate("DELETE FROM types")
            stmt.executeUpdate("DELETE FROM definitions")
            stmt.executeUpdate("DELETE FROM modules")
        }
    }

    // ========================================================================
    // METADATA
    // ========================================================================

    override fun getSchemaVersion(): Int {
        return getMetadata("schema_version")?.toIntOrNull() ?: 0
    }

    override fun getMetadata(key: String): String? {
        connection.prepareStatement("SELECT value FROM metadata WHERE key = ?").use { stmt ->
            stmt.setString(1, key)
            return stmt.executeQuery().use { rs ->
                if (rs.next()) rs.getString(1) else null
            }
        }
    }

    override fun setMetadata(key: String, value: String) {
        connection.prepareStatement("INSERT OR REPLACE INTO metadata (key, value) VALUES (?, ?)").use { stmt ->
            stmt.setString(1, key)
            stmt.setString(2, value)
            stmt.executeUpdate()
        }
    }

    // ========================================================================
    // CLOSE
    // ========================================================================

    override fun close() {
        connection.close()
    }

    // ========================================================================
    // HELPERS
    // ========================================================================

    private fun ResultSet.toStoredDefinition(): StoredDefinition {
        return StoredDefinition(
            hash = MezzeHash.fromHex(getString("hash")),
            kind = DefinitionKind.valueOf(getString("kind")),
            name = getString("name"),
            modulePath = getString("module_path"),
            visibility = Visibility.valueOf(getString("visibility")),
            astBlob = getBytes("ast_blob"),
            sourceHash = getString("source_hash")
        )
    }

    private fun ResultSet.toStoredModule(): StoredModule {
        return StoredModule(
            path = getString("path"),
            filePath = getString("file_path"),
            sourceHash = getString("source_hash"),
            isStdlib = getInt("is_stdlib") == 1,
            isExternalDep = getInt("is_external_dep") == 1,
            packageName = getString("package_name"),
            packageVersion = getString("package_version"),
            visibility = Visibility.valueOf(getString("visibility")),
            docComment = getString("doc_comment")
        )
    }

    private fun ResultSet.toStoredExport(): StoredExport {
        return StoredExport(
            modulePath = getString("module_path"),
            exportName = getString("export_name"),
            hash = MezzeHash.fromHex(getString("hash")),
            originalName = getString("original_name"),
            kind = DefinitionKind.valueOf(getString("kind"))
        )
    }
}
