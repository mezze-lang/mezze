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
    override fun getMetadataByPrefix(prefix: String): Map<String, String> =
        metadata.filterKeys { it.startsWith(prefix) }

    // ── APPS ──

    override fun putApp(name: String, entryHash: Hash, entryName: String?, entryModule: String, sourceFile: String?) {
        apps[name] = AppRow(name, entryHash, entryName, entryModule, sourceFile)
    }

    override fun getApp(name: String): AppRow? = apps[name]

    override fun setAppFunctionHashes(name: String, hashes: List<Hash>) {
        // Dedupe to match the SQLite-backed store's set semantics (#225).
        // First-occurrence wins for ordering.
        val seen = HashSet<Hash>(hashes.size)
        val deduped = ArrayList<Hash>(hashes.size)
        for (h in hashes) if (seen.add(h)) deduped.add(h)
        appFunctionHashes[name] = deduped
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
        decls.add(DeclRow(modulePath, declName, declKind, sourceHash, hash, startLine, startCol, endLine, endCol,
            doc, metadata))
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

    /**
     * The in-memory twin of the SQLite JSON query, and it has to agree with it
     * on both questions: does this key exist, and does its value match.
     *
     * Metadata is written by StorePopulator.serializeMetadata as flat JSON —
     * `{"deprecated": true, "since": "0.5.0"}` — so a top-level key can be
     * found without a JSON parser, and the value is compared against BOTH its
     * JSON form (`true`, `"0.5.0"`) and its unquoted form (`0.5.0`), matching
     * what SQLite's `->` and `->>` return respectively.
     */
    /** Effect membership, matching the SQL's json_each test. */
    // ── SIGNATURES ──
    //
    // The equivalent of the SQL store's three tables: the rows as stored, keyed
    // by decl hash. The effect search was previously a hand-written JSON-array
    // split here, which is what storing a signature as text forces on every
    // reader; with rows there is nothing to parse.
    private val signatures = mutableMapOf<Hash, SignatureRows>()

    override fun putSignature(rows: SignatureRows) {
        val declHash = rows.nodes.firstOrNull()?.declHash ?: return
        signatures[declHash] = rows
    }

    override fun getSignature(declHash: Hash): SignatureRows =
        signatures[declHash] ?: SignatureRows.EMPTY

    /** The node ids of [declHash]'s residual arrow — see [TypeNodeRow.isResidual]. */
    private fun residualNodeIds(declHash: Hash): Set<Int> =
        signatures[declHash]?.nodes?.filter { it.isResidual }?.map { it.nodeId }?.toSet().orEmpty()

    override fun findDeclsByEffect(effectName: String): List<DeclRow> =
        decls.filter { row ->
            val residual = residualNodeIds(row.hash)
            signatures[row.hash]?.effects?.any {
                it.nodeId in residual && it.effectName == effectName
            } == true
        }

    override fun findDeclsByConstraint(abilityName: String): List<DeclRow> =
        decls.filter { row ->
            val residual = residualNodeIds(row.hash)
            signatures[row.hash]?.constraints?.any {
                it.nodeId in residual && it.abilityName == abilityName
            } == true
        }

    override fun findDeclsMentioningType(typeHash: Hash): List<DeclRow> =
        decls.filter { row ->
            signatures[row.hash]?.nodes?.any { it.typeHash == typeHash } == true
        }

    override fun findDeclsByMetadata(key: String, value: String?): List<DeclRow> =
        decls.filter { row ->
            val raw = row.metadata ?: return@filter false
            val found = topLevelJsonValue(raw, key) ?: return@filter false
            value == null || found == value || found.removeSurrounding("\"") == value
        }

    /** The raw JSON text of a top-level key's value, or null if absent. */
    private fun topLevelJsonValue(json: String, key: String): String? {
        val marker = "\"$key\":"
        val at = json.indexOf(marker).takeIf { it >= 0 } ?: return null
        var i = at + marker.length
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length) return null
        // Scan one JSON value: a string honours escapes, a container balances,
        // anything else runs to the next delimiter at depth zero.
        val start = i
        if (json[i] == '"') {
            i++
            while (i < json.length) {
                if (json[i] == '\\') { i += 2; continue }
                if (json[i] == '"') { i++; break }
                i++
            }
            return json.substring(start, i)
        }
        var depth = 0
        while (i < json.length) {
            val c = json[i]
            if (c == '{' || c == '[') depth++
            else if (c == '}' || c == ']') { if (depth == 0) break; depth-- }
            else if (c == ',' && depth == 0) break
            i++
        }
        return json.substring(start, i).trim()
    }

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

    /**
     * Wipe every typecheck-derived collection. Preserves `modules`,
     * `parsedModules`, `metadata` (mirrors the SQLite policy).
     */
    override fun wipeTypecheckCaches() {
        blobs.clear()
        names.clear()
        edges.clear()
        abilityImpls.clear()
        effectPerformers.clear()
        usages.clear()
        apps.clear()
        appFunctionHashes.clear()
        appPerformerRecords.clear()
        imports.clear()
        decls.clear()
        typedModules.clear()
        monoSpecializations.clear()
        // Preserved: modules, parsedModules, metadata.
    }

    override fun close() {}
}
