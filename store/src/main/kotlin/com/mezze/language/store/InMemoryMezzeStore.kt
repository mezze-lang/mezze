package com.mezze.language.store

import com.mezze.language.typedast.Hash

/**
 * HashMap-based MezzeStore implementation.
 *
 * Used for:
 * - Unit tests (fast, no disk)
 * - Script mode (mezze app.mz — in-memory, no artifacts)
 * - Future WASM target (no SQLite in browser)
 *
 * All data lives in memory and is lost when the store is closed.
 * Thread-safe for single-writer / multiple-reader (not concurrent writers).
 */
class InMemoryMezzeStore : MezzeStore {

    private val blobs = HashMap<String, BlobRow>()               // hash.hex → BlobRow
    private val names = HashMap<String, Hash>()                  // "module\0name" → hash
    private val modules = HashMap<String, ModuleRow>()           // path → ModuleRow
    private val edges = ArrayList<EdgeRow>()
    private val abilityImpls = ArrayList<AbilityImplRow>()
    private val effectPerformers = ArrayList<EffectPerformerRow>()
    private val usages = ArrayList<UsageRow>()
    private val metadata = HashMap<String, String>()
    private val apps = HashMap<String, AppRow>()
    private val appFunctionHashes = HashMap<String, List<Hash>>()
    private val appPerformerRecords = HashMap<String, List<Array<String>>>()
    private val imports = ArrayList<ImportRow>()
    private val decls = ArrayList<DeclRow>()
    private val parsedModules = HashMap<String, ByteArray>()     // "path\0hash" → blob
    private val typedModules = HashMap<String, ByteArray>()      // "path\0hash" → blob
    private val monoSpecializations = HashMap<String, MonoSpecializationRow>()  // specialized.hex → row

    private fun nameKey(module: String, name: String) = "$module\u0000$name"
    private fun moduleKey(path: String, hash: String) = "$path\u0000$hash"

    // ── BLOBS ──

    override fun putBlob(hash: Hash, kind: BlobKind, visibility: Visibility, typed: ByteArray?, ir: ByteArray?) {
        blobs[hash.hex] = BlobRow(hash, kind, visibility, typed, ir)
    }

    override fun getBlob(hash: Hash): BlobRow? = blobs[hash.hex]
    override fun blobExists(hash: Hash): Boolean = hash.hex in blobs
    override fun blobsExist(hashes: Collection<Hash>): Set<Hash> = hashes.filterTo(HashSet()) { it.hex in blobs }
    override fun getIR(hash: Hash): ByteArray? = blobs[hash.hex]?.ir

    // ── NAMES ──

    override fun registerName(module: String, name: String, hash: Hash) {
        names[nameKey(module, name)] = hash
    }

    override fun resolveName(module: String, name: String): Hash? = names[nameKey(module, name)]

    override fun getNamesForHash(hash: Hash): List<NameRow> =
        names.entries.filter { it.value == hash }.map { (key, h) ->
            val parts = key.split('\u0000', limit = 2)
            NameRow(parts[0], parts[1], h)
        }

    override fun getModuleExports(module: String): List<NameRow> =
        names.entries.filter { it.key.startsWith("$module\u0000") }.map { (key, hash) ->
            val parts = key.split('\u0000', limit = 2)
            NameRow(parts[0], parts[1], hash)
        }

    override fun searchExportsByName(query: String): List<NameRow> =
        names.entries.filter { entry ->
            val name = entry.key.substringAfter('\u0000')
            name.startsWith(query, ignoreCase = true)
        }.map { (key, hash) ->
            val parts = key.split('\u0000', limit = 2)
            NameRow(parts[0], parts[1], hash)
        }

    // ── MODULES ──

    override fun putModule(path: String, filePath: String?, sourceHash: String) {
        modules[path] = ModuleRow(path, filePath, sourceHash)
    }

    override fun getModule(path: String): ModuleRow? = modules[path]

    override fun isFileStale(filePath: String, currentSourceHash: String): Boolean {
        val mod = modules.values.find { it.filePath == filePath } ?: return true
        return mod.sourceHash != currentSourceHash
    }

    override fun getAllModules(): List<ModuleRow> = modules.values.toList()

    // ── EDGES ──

    override fun addEdge(fromHash: Hash, toHash: Hash, kind: EdgeKind) {
        if (edges.none { it.fromHash == fromHash && it.toHash == toHash && it.kind == kind }) {
            edges.add(EdgeRow(fromHash, toHash, kind))
        }
    }

    override fun getEdgesFrom(hash: Hash): List<EdgeRow> = edges.filter { it.fromHash == hash }
    override fun getEdgesTo(hash: Hash): List<EdgeRow> = edges.filter { it.toHash == hash }

    override fun getTransitiveClosure(entryHash: Hash): Set<Hash> {
        val visited = mutableSetOf<Hash>()
        val queue = ArrayDeque<Hash>()
        queue.add(entryHash)
        while (queue.isNotEmpty()) {
            val h = queue.removeFirst()
            if (!visited.add(h)) continue
            for (edge in getEdgesFrom(h)) {
                queue.add(edge.toHash)
            }
        }
        return visited
    }

    // ── ABILITY / EFFECT INDEX ──

    override fun putAbilityImpl(hash: Hash, abilityHash: Hash, targetType: String, module: String?) {
        abilityImpls.removeAll { it.hash == hash }
        abilityImpls.add(AbilityImplRow(hash, abilityHash, targetType, module))
    }

    override fun getAbilityImpls(abilityHash: Hash): List<AbilityImplRow> =
        abilityImpls.filter { it.abilityHash == abilityHash }

    override fun getAbilityImplForType(abilityHash: Hash, targetType: String): Hash? =
        abilityImpls.find { it.abilityHash == abilityHash && it.targetType == targetType }?.hash

    override fun putEffectPerformer(hash: Hash, effectHash: Hash, performerName: String, module: String?) {
        effectPerformers.removeAll { it.hash == hash }
        effectPerformers.add(EffectPerformerRow(hash, effectHash, performerName, module))
    }

    override fun getEffectPerformers(effectHash: Hash): List<EffectPerformerRow> =
        effectPerformers.filter { it.effectHash == effectHash }

    // ── USAGES ──

    override fun putUsage(targetHash: Hash, filePath: String, startLine: Int, startCol: Int, endLine: Int, endCol: Int) {
        usages.add(UsageRow(targetHash, filePath, startLine, startCol, endLine, endCol))
    }

    override fun getUsages(targetHash: Hash): List<UsageRow> =
        usages.filter { it.targetHash == targetHash }

    override fun clearUsagesForFile(filePath: String) {
        usages.removeAll { it.filePath == filePath }
    }

    // ── METADATA ──

    override fun getMetadata(key: String): String? = metadata[key]
    override fun setMetadata(key: String, value: String) { metadata[key] = value }

    // ── APPS ──

    override fun putApp(name: String, entryName: String, entryModule: String, sourceFile: String?) {
        apps[name] = AppRow(name, entryName, entryModule, sourceFile)
    }

    override fun getApp(name: String): AppRow? = apps[name]

    override fun setAppFunctionHashes(name: String, hashes: List<Hash>) {
        appFunctionHashes[name] = hashes
    }

    override fun getAppFunctionHashes(name: String): List<Hash> =
        appFunctionHashes[name] ?: emptyList()

    override fun setAppPerformerRecords(name: String, records: List<Array<String>>) {
        appPerformerRecords[name] = records
    }

    override fun getAppPerformerRecords(name: String): List<Array<String>> =
        appPerformerRecords[name] ?: emptyList()

    // ── IMPORTS ──

    override fun putImport(
        modulePath: String, localName: String, targetModule: String, targetName: String,
        targetHash: Hash?, startLine: Int, startCol: Int, endLine: Int, endCol: Int
    ) {
        imports.removeAll { it.modulePath == modulePath && it.localName == localName }
        imports.add(ImportRow(modulePath, localName, targetModule, targetName, targetHash, startLine, startCol, endLine, endCol))
    }

    override fun clearImportsForModule(modulePath: String) {
        imports.removeAll { it.modulePath == modulePath }
    }

    override fun getImportsForModule(modulePath: String): List<ImportRow> =
        imports.filter { it.modulePath == modulePath }

    override fun getImportersOf(targetModule: String, targetName: String): List<ImportRow> =
        imports.filter { it.targetModule == targetModule && it.targetName == targetName }

    // ── DECLS ──

    override fun putDecl(
        modulePath: String, declName: String, declKind: DeclKind, sourceHash: String,
        hash: Hash, startLine: Int, startCol: Int, endLine: Int, endCol: Int,
        doc: String?, metadata: String?
    ) {
        decls.removeAll { it.modulePath == modulePath && it.declKind == declKind && it.declName == declName }
        decls.add(DeclRow(modulePath, declName, declKind, sourceHash, hash, startLine, startCol, endLine, endCol, doc, metadata))
    }

    override fun clearDeclsForModule(modulePath: String) {
        decls.removeAll { it.modulePath == modulePath }
    }

    override fun getDeclsForModule(modulePath: String): List<DeclRow> =
        decls.filter { it.modulePath == modulePath }

    override fun searchDeclsByName(query: String): List<DeclRow> =
        decls.filter { it.declName.startsWith(query, ignoreCase = true) }

    override fun getDeclsByKind(kind: DeclKind): List<DeclRow> =
        decls.filter { it.declKind == kind }

    override fun findDeclByHash(hash: Hash): DeclRow? =
        decls.find { it.hash == hash }

    // ── PARSED MODULES ──

    override fun putParsedModule(modulePath: String, sourceHash: String, parsedBlob: ByteArray) {
        parsedModules[moduleKey(modulePath, sourceHash)] = parsedBlob
    }

    override fun getParsedModule(modulePath: String, sourceHash: String): ByteArray? =
        parsedModules[moduleKey(modulePath, sourceHash)]

    override fun clearParsedModulesForPath(modulePath: String) {
        parsedModules.keys.removeAll { it.startsWith("$modulePath\u0000") }
    }

    // ── TYPED MODULES ──

    override fun putTypedModule(modulePath: String, sourceHash: String, typedBlob: ByteArray) {
        typedModules[moduleKey(modulePath, sourceHash)] = typedBlob
    }

    override fun getTypedModule(modulePath: String, sourceHash: String): ByteArray? =
        typedModules[moduleKey(modulePath, sourceHash)]

    override fun clearTypedModulesForPath(modulePath: String) {
        typedModules.keys.removeAll { it.startsWith("$modulePath\u0000") }
    }

    // ── MONO SPECIALIZATIONS ──

    override fun putMonoSpecialization(specializedHash: Hash, genericHash: Hash, typeArgsCbor: ByteArray) {
        monoSpecializations[specializedHash.hex] = MonoSpecializationRow(specializedHash, genericHash, typeArgsCbor)
    }

    override fun getMonoSpecialization(specializedHash: Hash): MonoSpecializationRow? =
        monoSpecializations[specializedHash.hex]

    override fun getMonoSpecializationsForGeneric(genericHash: Hash): List<MonoSpecializationRow> =
        monoSpecializations.values.filter { it.genericHash == genericHash }

    // ── LIFECYCLE ──

    override fun <T> transaction(block: () -> T): T = block()
    override fun close() {}
}
