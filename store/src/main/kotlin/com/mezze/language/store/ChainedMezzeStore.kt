package com.mezze.language.store

import com.mezze.language.typedast.Hash

/**
 * Read-through chain of [MezzeStore]s. Tries each store in order on every
 * read; returns the first hit. Writes always go to the first (primary)
 * store — the rest are read-only.
 *
 * Designed as the seam for hash-based remote fetching:
 *
 * ```
 * val store = ChainedMezzeStore(
 *     primary = SQLiteMezzeStore.readOnly(localBlobs),
 *     fallbacks = listOf(RemoteHttpMezzeStore(registry)),
 * )
 * ```
 *
 * Standalone JAR cold-start: just `[local]`. Future remote mode:
 * `[local, remote]` — a hash miss in `local` transparently fetches
 * from `remote` and caches into `local` on the next write cycle. The
 * typechecker / runtime calling `store.getIR(hash)` doesn't care which
 * tier answered.
 *
 * Per docs/architecture-v2/15-codebase-refactor.md.
 */
class ChainedMezzeStore(
    val primary: MezzeStore,
    val fallbacks: List<MezzeStore> = emptyList(),
) : MezzeStore {

    private val all: List<MezzeStore> = buildList {
        add(primary)
        addAll(fallbacks)
    }

    // ── Blobs ─────────────────────────────────────────────────────────────

    override fun putBlob(hash: Hash, kind: BlobKind, visibility: Visibility, typed: ByteArray?, ir: ByteArray?) {
        primary.putBlob(hash, kind, visibility, typed, ir)
    }

    override fun getBlob(hash: Hash): BlobRow? = firstNonNull { it.getBlob(hash) }
    override fun getIR(hash: Hash): ByteArray? = firstNonNull { it.getIR(hash) }
    override fun blobExists(hash: Hash): Boolean = all.any { it.blobExists(hash) }
    override fun blobsExist(hashes: Collection<Hash>): Set<Hash> {
        val acc = mutableSetOf<Hash>()
        for (s in all) acc.addAll(s.blobsExist(hashes - acc))
        return acc
    }

    override fun getAllBlobHashes(): List<Hash> {
        val seen = LinkedHashSet<Hash>()
        for (s in all) seen.addAll(s.getAllBlobHashes())
        return seen.toList()
    }

    // ── Names ─────────────────────────────────────────────────────────────

    override fun registerName(module: String, name: String, hash: Hash) {
        primary.registerName(module, name, hash)
    }

    override fun resolveName(module: String, name: String): Hash? =
        firstNonNull { it.resolveName(module, name) }

    override fun getNamesForHash(hash: Hash): List<NameRow> =
        all.flatMap { it.getNamesForHash(hash) }.distinct()

    override fun getModuleExports(module: String): List<NameRow> =
        all.flatMap { it.getModuleExports(module) }.distinct()

    override fun searchExportsByName(query: String): List<NameRow> =
        all.flatMap { it.searchExportsByName(query) }.distinct()

    // ── Modules ───────────────────────────────────────────────────────────

    override fun putModule(path: String, filePath: String?, sourceHash: String) {
        primary.putModule(path, filePath, sourceHash)
    }

    override fun getModule(path: String): ModuleRow? = firstNonNull { it.getModule(path) }

    override fun isFileStale(filePath: String, currentSourceHash: String): Boolean =
        primary.isFileStale(filePath, currentSourceHash)

    override fun getAllModules(): List<ModuleRow> =
        all.flatMap { it.getAllModules() }.distinctBy { it.path }

    // ── Edges ─────────────────────────────────────────────────────────────

    override fun addEdge(fromHash: Hash, toHash: Hash, kind: EdgeKind) {
        primary.addEdge(fromHash, toHash, kind)
    }

    override fun getEdgesFrom(hash: Hash): List<EdgeRow> =
        all.flatMap { it.getEdgesFrom(hash) }.distinct()

    override fun getEdgesTo(hash: Hash): List<EdgeRow> =
        all.flatMap { it.getEdgesTo(hash) }.distinct()

    override fun getTransitiveClosure(entryHash: Hash): Set<Hash> {
        // Walk edges across all tiers — a remote tier may know edges that
        // local doesn't yet.
        val visited = mutableSetOf<Hash>()
        val queue = ArrayDeque<Hash>()
        queue.add(entryHash)
        while (queue.isNotEmpty()) {
            val h = queue.removeFirst()
            if (!visited.add(h)) continue
            for (s in all) {
                for (e in s.getEdgesFrom(h)) {
                    if (e.toHash !in visited) queue.add(e.toHash)
                }
            }
        }
        return visited
    }

    // ── Ability impls / Performers ────────────────────────────────────────

    override fun putAbilityImpl(hash: Hash, abilityHash: Hash, targetType: String, module: String?) {
        primary.putAbilityImpl(hash, abilityHash, targetType, module)
    }

    override fun getAbilityImpls(abilityHash: Hash): List<AbilityImplRow> =
        all.flatMap { it.getAbilityImpls(abilityHash) }.distinct()

    override fun getAbilityImplForType(abilityHash: Hash, targetType: String): Hash? =
        firstNonNull { it.getAbilityImplForType(abilityHash, targetType) }

    override fun putEffectPerformer(hash: Hash, effectHash: Hash, performerName: String, module: String?) {
        primary.putEffectPerformer(hash, effectHash, performerName, module)
    }

    override fun getEffectPerformers(effectHash: Hash): List<EffectPerformerRow> =
        all.flatMap { it.getEffectPerformers(effectHash) }.distinct()

    // ── Usages (LSP) ──────────────────────────────────────────────────────

    override fun putUsage(targetHash: Hash, filePath: String, startLine: Int, startCol: Int, endLine: Int, endCol: Int) {
        primary.putUsage(targetHash, filePath, startLine, startCol, endLine, endCol)
    }

    override fun getUsages(targetHash: Hash): List<UsageRow> =
        all.flatMap { it.getUsages(targetHash) }.distinct()

    override fun clearUsagesForFile(filePath: String) {
        primary.clearUsagesForFile(filePath)
    }

    // ── Metadata ──────────────────────────────────────────────────────────

    override fun getMetadata(key: String): String? = firstNonNull { it.getMetadata(key) }

    override fun setMetadata(key: String, value: String) {
        primary.setMetadata(key, value)
    }

    override fun getMetadataByPrefix(prefix: String): Map<String, String> {
        val merged = mutableMapOf<String, String>()
        // Reverse so primary wins on key collision.
        for (s in all.reversed()) merged.putAll(s.getMetadataByPrefix(prefix))
        return merged
    }

    // ── Apps (write to primary only) ──────────────────────────────────────

    override fun putApp(name: String, entryHash: Hash, entryName: String?, entryModule: String, sourceFile: String?) {
        primary.putApp(name, entryHash, entryName, entryModule, sourceFile)
    }
    override fun getApp(name: String): AppRow? = firstNonNull { it.getApp(name) }
    override fun setAppFunctionHashes(name: String, hashes: List<Hash>) {
        primary.setAppFunctionHashes(name, hashes)
    }
    override fun getAppFunctionHashes(name: String): List<Hash> {
        for (s in all) {
            val r = s.getAppFunctionHashes(name)
            if (r.isNotEmpty()) return r
        }
        return emptyList()
    }
    override fun setAppPerformerRecords(name: String, records: List<Array<String>>) {
        primary.setAppPerformerRecords(name, records)
    }
    override fun getAppPerformerRecords(name: String): List<Array<String>> {
        for (s in all) {
            val r = s.getAppPerformerRecords(name)
            if (r.isNotEmpty()) return r
        }
        return emptyList()
    }

    // ── Imports / Decls / Parsed+Typed modules / Mono specs ───────────────
    //
    // For now these write to the primary and read from the chain. They're
    // tooling-level indexes (LSP, debugger), not execution hot paths — so
    // simple semantics are fine.

    override fun putImport(
        modulePath: String, localName: String, targetModule: String, targetName: String,
        targetHash: Hash?, startLine: Int, startCol: Int, endLine: Int, endCol: Int
    ) = primary.putImport(modulePath, localName, targetModule, targetName, targetHash, startLine, startCol, endLine, endCol)
    override fun clearImportsForModule(modulePath: String) = primary.clearImportsForModule(modulePath)
    override fun getImportsForModule(modulePath: String): List<ImportRow> =
        all.flatMap { it.getImportsForModule(modulePath) }.distinct()
    override fun getImportersOf(targetModule: String, targetName: String): List<ImportRow> =
        all.flatMap { it.getImportersOf(targetModule, targetName) }.distinct()

    override fun putDecl(
        modulePath: String, declName: String, declKind: DeclKind, sourceHash: String,
        hash: Hash, startLine: Int, startCol: Int, endLine: Int, endCol: Int, doc: String?, metadata: String?
    ) = primary.putDecl(modulePath, declName, declKind, sourceHash, hash, startLine, startCol, endLine, endCol,
        doc, metadata)
    override fun clearDeclsForModule(modulePath: String) = primary.clearDeclsForModule(modulePath)
    override fun getDeclsForModule(modulePath: String): List<DeclRow> =
        all.flatMap { it.getDeclsForModule(modulePath) }.distinct()
    override fun searchDeclsByName(query: String): List<DeclRow> =
        all.flatMap { it.searchDeclsByName(query) }.distinct()
    override fun getDeclsByKind(kind: DeclKind): List<DeclRow> =
        all.flatMap { it.getDeclsByKind(kind) }.distinct()
    override fun findDeclByHash(hash: Hash): DeclRow? = firstNonNull { it.findDeclByHash(hash) }
    override fun findDeclsByMetadata(key: String, value: String?): List<DeclRow> =
        all.flatMap { it.findDeclsByMetadata(key, value) }.distinct()
    override fun findDeclsByEffect(effectName: String): List<DeclRow> =
        all.flatMap { it.findDeclsByEffect(effectName) }.distinct()
    override fun findDeclsByText(text: String): List<DeclRow> =
        all.flatMap { it.findDeclsByText(text) }.distinct()
    override fun findDeclsByConstraint(abilityName: String): List<DeclRow> =
        all.flatMap { it.findDeclsByConstraint(abilityName) }.distinct()
    override fun findDeclsMentioningType(typeHash: Hash): List<DeclRow> =
        all.flatMap { it.findDeclsMentioningType(typeHash) }.distinct()

    // Writes go to the primary; reads span the chain, so a signature indexed in
    // the stdlib tier is visible to a query made from a project store.
    override fun putSignature(rows: SignatureRows) = primary.putSignature(rows)
    override fun getSignature(declHash: Hash): SignatureRows =
        all.asSequence().map { it.getSignature(declHash) }.firstOrNull { !it.isEmpty }
            ?: SignatureRows.EMPTY

    override fun putParsedModule(modulePath: String, sourceHash: String, parsedBlob: ByteArray) =
        primary.putParsedModule(modulePath, sourceHash, parsedBlob)
    override fun getParsedModule(modulePath: String, sourceHash: String): ByteArray? =
        firstNonNull { it.getParsedModule(modulePath, sourceHash) }
    override fun clearParsedModulesForPath(modulePath: String) =
        primary.clearParsedModulesForPath(modulePath)

    override fun putTypedModule(modulePath: String, sourceHash: String, typedBlob: ByteArray) =
        primary.putTypedModule(modulePath, sourceHash, typedBlob)
    override fun getTypedModule(modulePath: String, sourceHash: String): ByteArray? =
        firstNonNull { it.getTypedModule(modulePath, sourceHash) }
    override fun clearTypedModulesForPath(modulePath: String) =
        primary.clearTypedModulesForPath(modulePath)

    override fun putMonoSpecialization(specializedHash: Hash, genericHash: Hash, typeArgsCbor: ByteArray) =
        primary.putMonoSpecialization(specializedHash, genericHash, typeArgsCbor)
    override fun getMonoSpecialization(specializedHash: Hash): MonoSpecializationRow? =
        firstNonNull { it.getMonoSpecialization(specializedHash) }
    override fun getMonoSpecializationsForGeneric(genericHash: Hash): List<MonoSpecializationRow> =
        all.flatMap { it.getMonoSpecializationsForGeneric(genericHash) }.distinct()

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun <T> transaction(block: () -> T): T = primary.transaction(block)

    /** Wipe primary only — fallbacks are typically read-only mirrors. */
    override fun wipeTypecheckCaches() = primary.wipeTypecheckCaches()

    override fun close() {
        var firstError: Exception? = null
        for (s in all) {
            try { s.close() } catch (e: Exception) { if (firstError == null) firstError = e }
        }
        firstError?.let { throw it }
    }

    private inline fun <T : Any> firstNonNull(get: (MezzeStore) -> T?): T? {
        for (s in all) {
            val v = get(s)
            if (v != null) return v
        }
        return null
    }
}
