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

    /**
     * True when [hash]'s blob is in THIS store's own writable tier — not one it
     * only reads through (an attached stdlib or dependency layer, a chained
     * fallback). [blobExists] answers across every tier; this answers who owns it.
     * Facts about a blob, such as its edges, are recorded where the blob lives.
     */
    fun ownsBlob(hash: Hash): Boolean

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
     *
     * Abstract on purpose. It used to default to `emptyList()`, and the in-memory
     * store never overrode it, so a bulk load over that store silently loaded
     * nothing instead of failing.
     */
    fun getAllBlobHashes(): List<Hash>

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

    /**
     * Which part of the codebase the module [path] belongs to — this project, a
     * dependency, or the standard library — or null when no module by that path
     * is stored. Decided by WHERE the store keeps it, not by its name.
     */
    fun getModuleTier(path: String): StoreTier?

    /** Record the module's own documentation (its leading `##` block), or clear it. */
    fun putModuleDoc(path: String, doc: String?)

    /** The module's own documentation, or null when it has none. */
    fun getModuleDoc(path: String): String?

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

    /**
     * Every edge of the given [kinds], across every attached database.
     *
     * For whole-graph consumers — the runtime dependency graph, a visualiser —
     * which need the graph once rather than a hash at a time.
     */
    fun getAllEdges(kinds: Set<EdgeKind> = EdgeKind.entries.toSet()): List<EdgeRow>

    /**
     * Everything reachable FROM [hash] by [kinds] edges, transitively, with the
     * shortest distance to each.
     *
     * "What does this depend on" — one hop was already answerable; the question
     * people ask is the closure. Cycles terminate: the traversal dedups, so mutual
     * recursion converges instead of running forever, and [hash] itself is excluded
     * even when a cycle makes it reachable from itself.
     *
     * [maxDepth] bounds the search when a neighbourhood is wanted rather than the
     * whole closure; null means the whole closure, which is finite.
     */
    fun transitiveEdgesFrom(
        hash: Hash,
        kinds: Set<EdgeKind> = setOf(EdgeKind.CALLS, EdgeKind.REFERENCES),
        maxDepth: Int? = null,
    ): List<ReachableRow>

    /**
     * Everything that reaches [hash], transitively — the BLAST RADIUS of changing
     * it.
     *
     * The question to ask before an edit, and the one a single hop cannot answer:
     * the caller of the caller breaks just as surely as the direct caller.
     */
    fun transitiveEdgesTo(
        hash: Hash,
        kinds: Set<EdgeKind> = setOf(EdgeKind.CALLS, EdgeKind.REFERENCES),
        maxDepth: Int? = null,
    ): List<ReachableRow>

    /**
     * Declarations that NOTHING references and that nothing outside could reach:
     * private, and with no inbound edge.
     *
     * Deliberately conservative. It does not claim to find all dead code — a public
     * declaration no one imports is invisible here, and so is a private cluster that
     * only references itself. It claims the opposite: everything it returns really
     * is unreferenced, because a false accusation of deadness is the failure that
     * makes such a report useless.
     */
    fun findUnreferencedDecls(modulePath: String? = null): List<DeclRow>

    /** Compute transitive closure: all hashes reachable from entryHash via edges. */
    fun getTransitiveClosure(entryHash: Hash): Set<Hash>

    // ====================================================================
    // ABILITY / EFFECT INDEX
    // ====================================================================

    /** Register an ability implementation. */
    /**
     * Record an impl and its target types.
     *
     * [targetTypeHashes] are the structural hashes of the targets, in written
     * order. Each one should also have its type tree indexed (see
     * [putSignature]/[getTypeRows]) so the target can be rendered and unified
     * against, rather than only counted.
     */
    fun putAbilityImpl(hash: Hash, abilityHash: Hash, targetTypeHashes: List<Hash>, module: String?)

    /** Find all impls of an ability. */
    fun getAbilityImpls(abilityHash: Hash): List<AbilityImplRow>

    /**
     * The target types of one impl, in written order, as the structural hashes
     * their type trees are stored under.
     *
     * A junction, because an impl can have SEVERAL targets: 80 of the stdlib's 310
     * impls do (a class-param ability like `NumAdd 'r for 'l`). They used to be one
     * comma-joined string in `ability_impls.target_type`, which meant every reader
     * had to split it and could not join to anything.
     */
    fun getImplTargets(implHash: Hash): List<Hash>

    /**
     * Impls of the ability DECLARED as [abilityName], across every attached
     * database.
     *
     * Resolves the name to its declaration first, so this answers "impls of the
     * `Ord` declared here" rather than "impls of anything spelled Ord" — two
     * modules may each declare one.
     */
    fun findAbilityImplsByName(abilityName: String): List<AbilityImplRow>

    /** Find the impl of a specific ability for a specific type. */
    /**
     * The impl of [abilityHash] whose RECEIVER is [targetTypeHash], if any.
     *
     * The receiver is the type after `for` — the LAST target, since class parameters
     * are written before it (`impl FromJson 'a for Json`).
     */
    fun getAbilityImplForType(abilityHash: Hash, targetTypeHash: Hash): Hash?

    /**
     * Every impl, of any ability, whose RECEIVER is [targetTypeHash]: the impls
     * WRITTEN for exactly this type.
     *
     * The reverse of [getImplTargets]. It answers "what has been implemented for
     * `Point`" and finds neither generic impls that merely apply (`impl Eq for
     * ['a]` at `[Point]`) nor derived ones. Deciding those takes the type
     * checker's matching, not a lookup.
     */
    fun findImplsForType(targetTypeHash: Hash): List<AbilityImplRow>

    /** Every ability impl stored, of every ability, across every tier. */
    fun getAllAbilityImpls(): List<AbilityImplRow>

    /** Register an effect performer. */
    fun putEffectPerformer(hash: Hash, effectHash: Hash, performerName: String, module: String?)

    /** Find all performers for an effect. */
    fun getEffectPerformers(effectHash: Hash): List<EffectPerformerRow>

    /**
     * Performers of the effect DECLARED as [effectName], across every attached
     * database.
     *
     * The name-entered form of [getEffectPerformers], which needs a hash the caller
     * does not have. Resolved through the declaration, so two modules each declaring
     * `Log` stay separate — the same reason ability impls resolve that way.
     */
    fun findEffectPerformersByName(effectName: String): List<EffectPerformerRow>

    // ====================================================================
    // USAGES — LSP cross-file find-references index
    // ====================================================================

    /**
     * Record a source location that references a hash.
     *
     * [fromHash] is the declaration the reference is WRITTEN IN, and [isCall]
     * says whether it is the function position of a call. Together they are the
     * source-level call graph. The `edges` table cannot be that: it is built from
     * lowered code, where a small helper inlined into its callers has vanished,
     * so "who calls `shout`" came back empty for a `shout` written twice in
     * `twice`.
     */
    fun putUsage(
        targetHash: Hash, filePath: String, startLine: Int, startCol: Int, endLine: Int, endCol: Int,
        fromHash: Hash, isCall: Boolean,
    )

    /** Find all source locations referencing a hash. */
    fun getUsages(targetHash: Hash): List<UsageRow>

    /** Every reference written inside the declaration [fromHash]: what its source mentions. */
    fun getUsagesFrom(fromHash: Hash): List<UsageRow>

    /** Clear all usages recorded from a file. Called before re-indexing a changed file. */
    fun clearUsagesForFile(filePath: String)

    /**
     * Every expression node written in [modulePath]'s declarations, by its
     * `std::syntax` kind and what it spells — replacing what the module had. What
     * makes "every `==`", "every pipe", "every use of `x` as written" one lookup.
     */
    fun putOccurrences(modulePath: String, rows: List<OccurrenceRow>)

    /** Nodes of [kind]; with [symbol], only those spelling it. */
    fun findOccurrences(kind: String, symbol: String?): List<OccurrenceRow>

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
        metadata: String? = null,
    )
    fun clearDeclsForModule(modulePath: String)

    /** Forget every name [modulePath] declares; re-indexing registers the current ones. */
    fun clearNamesForModule(modulePath: String)

    /**
     * The blobs [owner] produces now — a module's declarations, or the functions
     * an entry point lowers to.
     *
     * A blob [owner] produced before and no longer does, and that no other owner
     * produces, is deleted with every row keyed on it: its impl and performer
     * registrations, edges, signature and definition, usages from it. Content
     * addressing makes a blob never WRONG, but those rows are answers — without
     * this a deleted `impl` stayed in every "what implements this" — and the
     * store grew by every version of every declaration ever written.
     *
     * Only blobs some owner once claimed are ever deleted: a blob written by
     * anything that records no ownership is never touched.
     */
    fun replaceBlobOwnership(owner: String, hashes: Set<Hash>)
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

    /**
     * Declarations whose residual effect row contains [effectName].
     *
     * The dimension a caller actually asks about — "what can throw?", "what is
     * safe to run with no handlers?" — and one that was previously unreachable:
     * `getEffectPerformers` answers "who PERFORMS this effect" and needs a
     * hash, not a name.
     *
     * An equality join against the effect rows of the decl's RESIDUAL arrow —
     * what a caller is left holding once every argument is applied. Exact by
     * construction: the previous form tested membership in a JSON array to avoid
     * `LIKE '%Log%'` also matching `Logger`, and a join needs no such care.
     */
    fun findDeclsByEffect(effectName: String): List<DeclRow>

    /**
     * Declarations whose residual arrow requires the ability [abilityName]
     * (`with Ord 'a`).
     *
     * The other half of "what does calling this oblige me to provide", and the
     * question an agent or doc browser asks when looking for what it can satisfy.
     */
    fun findDeclsByConstraint(abilityName: String): List<DeclRow>

    /**
     * Declarations whose residual arrow performs the effect DECLARED at
     * [effectHash].
     *
     * The identity form of [findDeclsByEffect]. That one takes the name a person
     * typed, which is right for text search; this one takes the declaration, so
     * two effects that share a name (`Db` in two packages) are never merged. Rows
     * whose effect has no recorded hash cannot match.
     */
    fun findDeclsPerforming(effectHash: Hash): List<DeclRow>

    /**
     * Declarations whose residual arrow requires the ability DECLARED at
     * [abilityHash] (`with Ord 'a`). The identity form of [findDeclsByConstraint].
     */
    fun findDeclsRequiring(abilityHash: Hash): List<DeclRow>

    /**
     * The RESIDUAL effect names declared by each of [hashes] — what a caller is left
     * holding once every argument is applied.
     *
     * Batched deliberately. The caller is walking a call graph, so it has tens or
     * hundreds of hashes at once, and asking per hash would turn one question into
     * hundreds of round trips. A hash with no indexed signature is absent from the
     * result rather than present-and-empty: "no signature" and "no effects" are
     * different facts.
     */
    fun getResidualEffectsFor(hashes: Collection<Hash>): Map<Hash, Set<String>>

    /**
     * Declarations whose NAME or doc comment contains [text], as a substring.
     *
     * Spans every declaration kind, not just `let`: an ability, an effect or a type
     * is a thing you look for by name too.
     *
     * Substring, not prefix. `searchDeclsByName` answers "starts with", which only
     * helps when you remember how a name BEGINS — and half-remembering the middle
     * (`sea` for `binary_search_insert_point`) is the more common case. Backed by a
     * trigram index, so it is not a table scan.
     */
    fun findDeclsByText(text: String): List<DeclRow>

    /**
     * Declarations whose signature mentions the type whose declaration hashes to
     * [typeHash], anywhere — parameter, return, nested inside a container.
     *
     * Keyed on the HASH, not the name, so it answers "who uses THIS `Color`"
     * rather than "who uses something spelled Color". Two same-named types from
     * different modules are different types, and this query treats them so.
     */
    fun findDeclsMentioningType(typeHash: Hash): List<DeclRow>

    // ====================================================================
    // SIGNATURES — the decl's type, as a queryable tree
    // ====================================================================

    /**
     * Store [rows] as the signature of the declaration they name.
     *
     * Replaces any signature already recorded for that declaration, so
     * re-indexing a changed decl cannot leave half of the old tree behind.
     */
    fun putSignature(rows: SignatureRows)

    /**
     * The type rows owned by [ownerHash] — a declaration's signature, or a
     * standalone type indexed under its own structural hash.
     *
     * Same table either way: a type tree is a type tree, and an ability impl's
     * target needs exactly what a signature needs (rendering, and unification
     * against a query).
     */
    fun getTypeRows(ownerHash: Hash): SignatureRows = getSignature(ownerHash)

    /**
     * The signature rows for [declHash], or [SignatureRows.EMPTY] if the
     * declaration has no indexed signature — a type, ability or effect
     * declaration has no signature to index, which is different from having an
     * empty one.
     *
     * Use [SignatureIndex.rebuild] to turn the rows back into a `MezzeType`.
     */
    fun getSignature(declHash: Hash): SignatureRows

    /**
     * Store what a type, ability or effect declaration DEFINES: fields,
     * constructors, operations (see [DefinitionIndex]). Replaces any rows already
     * stored for the same declaration — a definition is one value.
     */
    fun putDefinition(rows: SignatureRows)

    /**
     * The definition rows of the declaration [declHash], or [SignatureRows.EMPTY]
     * when none is stored (a `let`, an impl, or a declaration indexed without its
     * source). Rebuild them with [DefinitionIndex.rebuild].
     */
    fun getDefinition(declHash: Hash): SignatureRows

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

/** Where a module comes from. */
enum class StoreTier { PROJECT, DEPENDENCY, STDLIB }

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

/** A hash reached by a transitive traversal, with the SHORTEST distance to it. */
data class ReachableRow(val hash: Hash, val depth: Int)
/**
 * One `impl A for T` row. Its target types live in the `ability_impl_targets`
 * junction — see [MezzeStore.getImplTargets] — not on this row: an impl may have
 * several, and a single column could only hold them joined into a string.
 */
data class AbilityImplRow(val hash: Hash, val abilityHash: Hash, val module: String?)
data class EffectPerformerRow(val hash: Hash, val effectHash: Hash, val performerName: String, val module: String?)
/**
 * One written expression node: the declaration it is in ([ownerHash]), its
 * `std::syntax` kind (`BinaryExpr`), what it spells (`==`), and where.
 */
data class OccurrenceRow(
    val ownerHash: Hash,
    val kind: String,
    val symbol: String?,
    val filePath: String?,
    val startLine: Int,
    val startCol: Int,
    val endLine: Int,
    val endCol: Int,
)

data class UsageRow(
    val targetHash: Hash,
    val filePath: String,
    val startLine: Int,
    val startCol: Int,
    val endLine: Int,
    val endCol: Int,
    /** The declaration this reference is written in. */
    val fromHash: Hash,
    /** True when the reference is the function being called (`f { … }`), not a value mention. */
    val isCall: Boolean,
)

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
    val metadata: String? = null,
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
