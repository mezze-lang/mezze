package com.mezze.language.store

import com.mezze.language.typedast.Hash

/**
 * Content-addressed storage for compiled Mezze code.
 *
 * All tools — compiler, executor, LSP, CLI — read and write through this interface.
 * Implementations: SQLiteMezzeStore (file-based), InMemoryMezzeStore (tests/scripts).
 *
 * Design:
 * - Blobs are dumb storage keyed by content hash. Everything else is an index.
 * - Names are metadata, not identity. A hash can have zero, one, or many names.
 * - Append-only for blobs: INSERT OR REPLACE, never delete (orphans are harmless).
 * - Usages table is the only thing that gets cleared per-file (for LSP re-index).
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

    /**
     * Enumerate every blob hash visible to this store, across all tiers
     * (main + any chained / attached stores). Used by bulk-load paths
     * (e.g. PrecompiledStdlib startup) that need to materialise the entire
     * IR set up front instead of chasing decl-by-decl. Default implementation
     * returns empty — implementations that can iterate efficiently (SQL,
     * etc.) should override. SQL impl UNION-ALLs across main + stdlib + deps;
     * Chained walks every tier.
     */
    fun getAllBlobHashes(): List<Hash> = emptyList()

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

    /** Register a module (source file). Cache validity is determined by
     *  comparing sha256 of the file's current bytes against [sourceHash]. */
    fun putModule(path: String, filePath: String?, sourceHash: String)

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

    /** Get all hashes that depend on `hash` (incoming edges). */
    fun getEdgesTo(hash: Hash): List<EdgeRow>

    /** Compute transitive closure: all hashes reachable from entryHash via edges. */
    fun getTransitiveClosure(entryHash: Hash): Set<Hash>

    // ====================================================================
    // ABILITY / EFFECT INDEX
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

    /** Record a source location that references a hash. */
    fun putUsage(targetHash: Hash, filePath: String, startLine: Int, startCol: Int, endLine: Int, endCol: Int)

    /** Find all source locations referencing a hash. */
    fun getUsages(targetHash: Hash): List<UsageRow>

    /** Clear all usages recorded from a file. Called before re-indexing a changed file. */
    fun clearUsagesForFile(filePath: String)

    // ====================================================================
    // METADATA
    // ====================================================================

    fun getMetadata(key: String): String?
    fun setMetadata(key: String, value: String)

    /** Return all metadata entries whose key starts with [prefix]. Empty map if none. */
    fun getMetadataByPrefix(prefix: String): Map<String, String>

    // ====================================================================
    // APPS — per-app entry metadata + function/performer manifests
    // ====================================================================

    /**
     * Record an app's entry-point metadata.
     *
     * [entryHash] is the entry function's content hash — the SOLE identity of
     * the entry point (== the entry `TypedLetDecl.hash` == the entry
     * `FunctionIR.hash`). Selection at runtime keys on this hash, never on a
     * name. [entryName] is retained as optional display metadata only and must
     * NOT be read for selection anywhere. [entryModule]/[sourceFile] are
     * addressing/provenance.
     */
    fun putApp(name: String, entryHash: Hash, entryName: String?, entryModule: String, sourceFile: String?)
    fun getApp(name: String): AppRow?

    /**
     * Register the function hashes that compose this app.
     *
     * Semantics: this is a SET of hashes — each hash appears at most
     * once. Order in the passed list is preserved for ordering-sensitive
     * downstream consumers (e.g. closure walks); duplicates are silently
     * deduped at first occurrence. The underlying storage enforces
     * uniqueness; passing duplicates is not an error but a redundancy.
     *
     * Callers that produce hash lists from a content-addressed lowering
     * pipeline already dedupe at the source (see TypedASTCompiler).
     * The store enforces it as a defense-in-depth invariant.
     */
    fun setAppFunctionHashes(name: String, hashes: List<Hash>)
    fun getAppFunctionHashes(name: String): List<Hash>
    fun setAppPerformerRecords(name: String, records: List<Array<String>>)
    fun getAppPerformerRecords(name: String): List<Array<String>>

    // ====================================================================
    // IMPORTS
    // ====================================================================

    fun putImport(
        modulePath: String,
        localName: String,
        targetModule: String,
        targetName: String,
        targetHash: Hash?,
        startLine: Int,
        startCol: Int,
        endLine: Int,
        endCol: Int
    )
    fun clearImportsForModule(modulePath: String)
    fun getImportsForModule(modulePath: String): List<ImportRow>
    fun getImportersOf(targetModule: String, targetName: String): List<ImportRow>

    // ====================================================================
    // DECLS — per-decl index
    // ====================================================================

    fun putDecl(
        modulePath: String,
        declName: String,
        declKind: DeclKind,
        sourceHash: String,
        hash: Hash,
        startLine: Int,
        startCol: Int,
        endLine: Int,
        endCol: Int,
        doc: String? = null,
        metadata: String? = null
    )
    fun clearDeclsForModule(modulePath: String)
    fun getDeclsForModule(modulePath: String): List<DeclRow>
    fun searchDeclsByName(query: String): List<DeclRow>
    fun getDeclsByKind(kind: DeclKind): List<DeclRow>
    fun findDeclByHash(hash: Hash): DeclRow?

    /**
     * Declarations whose `#{ }` metadata carries [key], optionally restricted to
     * a particular [value].
     *
     * The `metadata` column has been written on every decl and handed back on
     * every decl query since it existed — but only ever *alongside* a result,
     * never as the thing selected ON. So "what metadata does `sort` have?" was
     * answerable and "what in this codebase is deprecated?" was not, with the
     * data sitting in the same table either way. This closes that gap.
     *
     * [key] is a top-level field name. [value] compares against the stored JSON
     * rendering — `deprecated` + `"true"`, or `since` + `"0.5.0"`. Passing null
     * matches any declaration carrying the key at all, which is what a bare flag
     * (`#{ deprecated }`) needs.
     *
     * Spans every attached database like the other decl queries: a search for
     * deprecated things that silently skipped the stdlib and dependencies would
     * be worse than no search.
     */
    fun findDeclsByMetadata(key: String, value: String? = null): List<DeclRow>

    // ====================================================================
    // PARSED MODULES — cached ParsedModule per (modulePath, sourceHash)
    // ====================================================================

    fun putParsedModule(modulePath: String, sourceHash: String, parsedBlob: ByteArray)
    fun getParsedModule(modulePath: String, sourceHash: String): ByteArray?
    fun clearParsedModulesForPath(modulePath: String)

    // ====================================================================
    // TYPED MODULES — cached TypedModule per (modulePath, sourceHash)
    // ====================================================================

    fun putTypedModule(modulePath: String, sourceHash: String, typedBlob: ByteArray)
    fun getTypedModule(modulePath: String, sourceHash: String): ByteArray?
    fun clearTypedModulesForPath(modulePath: String)

    // ====================================================================
    // MONO SPECIALIZATIONS — provenance for monomorphizer outputs.
    // Read by tools (debugger, LSP, "go to definition") to map a
    // specialization back to its generic. Never on the execution hot path.
    // ====================================================================

    /** Register a specialization. INSERT OR REPLACE. */
    fun putMonoSpecialization(specializedHash: Hash, genericHash: Hash, typeArgsCbor: ByteArray)

    /** Look up a specialization's provenance by its specialized hash. */
    fun getMonoSpecialization(specializedHash: Hash): MonoSpecializationRow?

    /** All specializations of a given generic. */
    fun getMonoSpecializationsForGeneric(genericHash: Hash): List<MonoSpecializationRow>

    // ====================================================================
    // LIFECYCLE
    // ====================================================================

    fun <T> transaction(block: () -> T): T

    /**
     * Wipe every table derived from typechecker output. Preserves the three
     * upstream-of-typecheck tables: `modules` (user-source observations),
     * `parsed_modules` (parser output), and `metadata` (where version stamps
     * live). Called by `StoreHydration.invalidateOnVersionShift` when the
     * `mez_version` stamp changes.
     *
     * After wipe, callers should re-stamp via `setMetadata("mez_version", ...)`.
     * The wipe itself doesn't touch metadata.
     */
    fun wipeTypecheckCaches()

    override fun close()
}

// ========================================================================
// DATA TYPES
// ========================================================================

enum class BlobKind {
    FUNCTION, TYPE, ABILITY, ABILITY_IMPL, EFFECT, PERFORMER
}

enum class Visibility {
    PUBLIC, PRIVATE
}

enum class EdgeKind {
    CALLS, REFERENCES, IMPLEMENTS
}

enum class DeclKind(val dbValue: String) {
    LET("let"),
    TYPE("type"),
    ABILITY("ability"),
    EFFECT("effect"),
    PERFORMER("performer"),
    IMPL("impl");

    companion object {
        fun fromDb(s: String): DeclKind = entries.firstOrNull { it.dbValue == s }
            ?: error("unknown DeclKind in db: $s")
    }
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
    /** Entry-point identity: content hash of the entry function. */
    val entryHash: Hash,
    /** Display-only entry name; NEVER a selection key. Nullable for forward-compat. */
    val entryName: String?,
    val entryModule: String,
    val sourceFile: String?
)

data class NameRow(val module: String, val name: String, val hash: Hash)
data class ModuleRow(
    val path: String,
    val filePath: String?,
    val sourceHash: String,
)
data class EdgeRow(val fromHash: Hash, val toHash: Hash, val kind: EdgeKind)
data class AbilityImplRow(val hash: Hash, val abilityHash: Hash, val targetType: String, val module: String?)
data class EffectPerformerRow(val hash: Hash, val effectHash: Hash, val performerName: String, val module: String?)
data class UsageRow(val targetHash: Hash, val filePath: String, val startLine: Int, val startCol: Int, val endLine: Int, val endCol: Int)

data class DeclRow(
    val modulePath: String,
    val declName: String,
    val declKind: DeclKind,
    val sourceHash: String,
    val hash: Hash,
    val startLine: Int,
    val startCol: Int,
    val endLine: Int,
    val endCol: Int,
    val doc: String? = null,
    val metadata: String? = null
)

data class ImportRow(
    val modulePath: String,
    val localName: String,
    val targetModule: String,
    val targetName: String,
    val targetHash: Hash?,
    val startLine: Int,
    val startCol: Int,
    val endLine: Int,
    val endCol: Int
)

data class MonoSpecializationRow(
    val specializedHash: Hash,
    val genericHash: Hash,
    val typeArgsCbor: ByteArray
) {
    override fun equals(other: Any?): Boolean =
        other is MonoSpecializationRow && specializedHash == other.specializedHash
    override fun hashCode(): Int = specializedHash.hashCode()
}
