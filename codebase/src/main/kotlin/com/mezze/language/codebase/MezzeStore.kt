package com.mezze.language.codebase

import com.mezze.language.typedast.Hash

/**
 * Content-addressed storage for compiled Mezze code.
 *
 * All tools — compiler, executor, LSP, CLI — read and write through this interface.
 * Backed by SQLite (file-based for projects, :memory: for tests).
 *
 * Design:
 * - Blobs are dumb storage keyed by content hash. Everything else is an index.
 * - Names are metadata, not identity. A hash can have zero, one, or many names.
 * - Append-only for blobs: INSERT OR REPLACE, never delete (orphans are harmless).
 * - References table is the only thing that gets cleared per-file (for LSP re-index).
 */
interface MezzeStore : AutoCloseable {

    // ====================================================================
    // BLOBS — content-addressed storage (the only table with actual data)
    // ====================================================================

    /** Store a definition blob. INSERT OR REPLACE — idempotent. */
    fun putBlob(hash: Hash, kind: BlobKind, visibility: Visibility, typed: ByteArray?, ir: ByteArray?)

    /** Retrieve a blob by hash. Returns null if not found. */
    fun getBlob(hash: Hash): BlobRow?

    /** Check existence without loading data. */
    fun blobExists(hash: Hash): Boolean

    /** Bulk existence check — returns the subset of hashes already present. */
    fun blobsExist(hashes: Collection<Hash>): Set<Hash>

    /** Get IR bytes only (avoids deserializing typed). For executor hot path. */
    fun getIR(hash: Hash): ByteArray?

    // ====================================================================
    // NAMES — human-readable labels pointing to hashes
    // ====================================================================

    /** Register a name → hash mapping. INSERT OR REPLACE. */
    fun registerName(module: String, name: String, hash: Hash)

    /** Resolve a name to its hash. Returns null if not found. */
    fun resolveName(module: String, name: String): Hash?

    /** Reverse lookup: all names for a given hash. For LSP rename, stack traces. */
    fun getNamesForHash(hash: Hash): List<NameRow>

    /** Get all public exports from a module. For import resolution. */
    fun getModuleExports(module: String): List<NameRow>

    /** Search exports by name prefix across all modules. For LSP auto-import. */
    fun searchExportsByName(query: String): List<NameRow>

    // ====================================================================
    // MODULES — source file tracking for incremental compilation
    // ====================================================================

    /**
     * Register a module (source file). Category tells consumers whether
     * this row is user code, stdlib, or library — replaces path-prefix
     * matching as the canonical "where did this come from" check.
     */
    fun putModule(path: String, filePath: String?, sourceHash: String, category: ModuleCategory)

    /** Get module info. */
    fun getModule(path: String): ModuleRow?

    /** Check if a file needs recompilation. True if source_hash differs or module not found. */
    fun isFileStale(filePath: String, currentSourceHash: String): Boolean

    /** Get all registered modules. */
    fun getAllModules(): List<ModuleRow>

    // ====================================================================
    // EDGES — dependency graph between hashes
    // ====================================================================

    /** Record that fromHash depends on toHash. INSERT OR IGNORE. */
    fun addEdge(fromHash: Hash, toHash: Hash, kind: EdgeKind)

    /** Get all hashes that `hash` depends on (outgoing edges). */
    fun getEdgesFrom(hash: Hash): List<EdgeRow>

    /** Get all hashes that depend on `hash` (incoming edges). For find-references, invalidation. */
    fun getEdgesTo(hash: Hash): List<EdgeRow>

    /**
     * Compute transitive closure: all hashes reachable from entryHash via edges.
     * For `mez build` — determines which blobs go into the JAR.
     */
    fun getTransitiveClosure(entryHash: Hash): Set<Hash>

    // ====================================================================
    // ABILITY / EFFECT INDEX — lookup tables for typechecker + runtime
    // ====================================================================

    /** Register an ability implementation. */
    fun putAbilityImpl(hash: Hash, abilityHash: Hash, targetType: String, module: String?)

    /** Find all impls of an ability. */
    fun getAbilityImpls(abilityHash: Hash): List<AbilityImplRow>

    /** Find the impl of a specific ability for a specific type. */
    fun getAbilityImplForType(abilityHash: Hash, targetType: String): Hash?

    /** Register an effect performer. */
    fun putEffectPerformer(hash: Hash, effectHash: Hash, performerName: String, module: String?)

    /** Find all performers for an effect. */
    fun getEffectPerformers(effectHash: Hash): List<EffectPerformerRow>

    // ====================================================================
    // USAGES — LSP cross-file find-references index
    // ====================================================================
    // Renamed from v6's "references" (table was references_ to dodge the SQL
    // keyword). Functionally identical: where in the source code is this hash
    // referenced from. Cleared per-file when a file is re-typechecked.

    /** Record a source location that references a hash. */
    fun putUsage(targetHash: Hash, filePath: String, startLine: Int, startCol: Int, endLine: Int, endCol: Int)

    /** Find all source locations referencing a hash. For LSP find-references. */
    fun getUsages(targetHash: Hash): List<UsageRow>

    /** Clear all usages recorded from a file. Called before re-indexing a changed file. */
    fun clearUsagesForFile(filePath: String)

    // ====================================================================
    // METADATA — key-value pairs for schema version, project info, etc.
    // ====================================================================

    fun getMetadata(key: String): String?
    fun setMetadata(key: String, value: String)

    // ====================================================================
    // APPS — per-app entry metadata + function/performer manifests
    // ====================================================================

    /** Upsert an app's basic info. */
    fun putApp(name: String, entryName: String, entryModule: String, sourceFile: String?)

    /** Look up an app by name. */
    fun getApp(name: String): AppRow?

    /** Replace this app's function manifest. The order of the list is preserved. */
    fun setAppFunctionHashes(name: String, hashes: List<Hash>)

    /** Get the ordered function hashes for an app, or empty list if none. */
    fun getAppFunctionHashes(name: String): List<Hash>

    /** Replace this app's performer records (record_idx → ordered hashes). */
    fun setAppPerformerRecords(name: String, records: List<Array<String>>)

    /** Get the performer records for an app. */
    fun getAppPerformerRecords(name: String): List<Array<String>>

    // ====================================================================
    // TYPED MODULES — cached v2 TypedModule per (modulePath, sourceHash)
    //
    // Lets tools skip whole-module re-typecheck on JVM start when the source
    // file's bytes match what was typed before. Keyed by (path, sourceHash)
    // so multiple in-flight versions of the same module can coexist (e.g.
    // LSP saw an in-progress edit, dev saw the saved file).
    // ====================================================================

    /** Cache a typed module. Idempotent: same (path, sourceHash) → same blob. */
    fun putTypedModule(modulePath: String, sourceHash: String, typedBlob: ByteArray)

    /** Look up a cached typed module by (path, sourceHash). Null if not present. */
    fun getTypedModule(modulePath: String, sourceHash: String): ByteArray?

    /**
     * Drop all cached typed-module entries for [modulePath]. Called when a
     * module is deleted from the project so old (path, sourceHash) entries
     * don't outlive their source.
     */
    fun clearTypedModulesForPath(modulePath: String)

    // ====================================================================
    // LIFECYCLE
    // ====================================================================

    /** Execute a block inside a transaction. Auto-commits on success, auto-rollbacks on exception. */
    fun <T> transaction(block: () -> T): T

    /** Close the store and release resources. */
    override fun close()
}

// ========================================================================
// DATA TYPES
// ========================================================================

enum class BlobKind {
    FUNCTION,
    TYPE,
    ABILITY,
    ABILITY_IMPL,
    EFFECT,
    PERFORMER
}

enum class Visibility {
    PUBLIC,
    PRIVATE
}

enum class EdgeKind {
    CALLS,        // function A calls function B
    REFERENCES,   // definition A references definition B (type, variable)
    IMPLEMENTS    // impl X implements ability/effect Y
}

data class BlobRow(
    val hash: Hash,
    val kind: BlobKind,
    val visibility: Visibility,
    val typed: ByteArray?,
    val ir: ByteArray?
) {
    override fun equals(other: Any?): Boolean = other is BlobRow && hash == other.hash
    override fun hashCode(): Int = hash.hashCode()
}

data class AppRow(
    val name: String,
    val entryName: String,
    val entryModule: String,
    val sourceFile: String?
)

data class NameRow(
    val module: String,
    val name: String,
    val hash: Hash
)

data class ModuleRow(
    val path: String,
    val filePath: String?,
    val sourceHash: String,
    val category: ModuleCategory
)

/**
 * Where a module came from. Stored as the modules.category column.
 *
 * - USER: written by the project author. Lives in the project directory.
 * - STDLIB: shipped with the Mezze CLI. Source extracted to a temp dir.
 * - LIBRARY: pulled in via `mez sync` (when #108 ships). Lives in
 *   mezze_packages/<libname>/.
 *
 * Cheap to filter on (`WHERE category='user'`); replaces the older
 * path-prefix check (`WHERE path NOT LIKE 'std::%'`).
 */
enum class ModuleCategory(val dbValue: String) {
    USER("user"),
    STDLIB("stdlib"),
    LIBRARY("library");

    companion object {
        fun fromDb(s: String): ModuleCategory = when (s) {
            "user" -> USER
            "stdlib" -> STDLIB
            "library" -> LIBRARY
            else -> error("unknown ModuleCategory in db: $s")
        }
    }
}

data class EdgeRow(
    val fromHash: Hash,
    val toHash: Hash,
    val kind: EdgeKind
)

data class AbilityImplRow(
    val hash: Hash,
    val abilityHash: Hash,
    val targetType: String,
    val module: String?
)

data class EffectPerformerRow(
    val hash: Hash,
    val effectHash: Hash,
    val performerName: String,
    val module: String?
)

data class UsageRow(
    val targetHash: Hash,
    val filePath: String,
    val startLine: Int,
    val startCol: Int,
    val endLine: Int,
    val endCol: Int
)
