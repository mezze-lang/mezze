package com.mezze.language.codebase

import com.mezze.language.typechecker.TypeCheckResult
import com.mezze.language.typedast.*
import com.mezze.language.truffleir.FunctionIR
import java.io.File
import java.security.MessageDigest

/**
 * Scatters compilation pipeline output into a MezzeStore.
 *
 * Takes a TypeCheckResult (produced by TypeChecker.typeCheck()) and writes
 * individual definitions as blobs, registers names, records edges, etc.
 *
 * This is the bridge between the compilation pipeline and the storage layer.
 * Called by `mez dev`, `mez check`, and `mez build` after each file is compiled.
 */
/**
 * Result of a populate() call — what actually changed in the store.
 *
 * Drives:
 * - Watch mode CallTarget invalidation (only rebuild changed hashes)
 * - Surgical cache invalidation in ModuleResolver / PipelineRunner
 * - Logging / telemetry
 */
data class PopulateDelta(
    /** Hashes newly written this run. Subset of all hashes in result. */
    val newHashes: Set<Hash>,
    /** Hashes already present in the store, skipped (no IO). */
    val unchangedHashes: Set<Hash>,
)

object StorePopulator {

    /**
     * Scatter a TypeCheckResult into the store, skipping definitions whose
     * content hash already exists. Returns the delta.
     *
     * @param store The MezzeStore to write to
     * @param result The TypeCheckResult from TypeChecker.typeCheck()
     * @param modulePath The module path (e.g., "myapp::utils")
     * @param filePath The source file path (e.g., "src/utils.mz")
     * @param sourceHash The SHA-256 hash of the source file contents
     */
    fun populate(
        store: MezzeStore,
        result: TypeCheckResult,
        modulePath: String,
        filePath: String?,
        sourceHash: String
    ): PopulateDelta {
        // Collect all hashes we might write so we can do one bulk existence check.
        val candidateHashes = mutableSetOf<Hash>()
        result.typedModule?.let { module ->
            module.decls.filterIsInstance<TypedLetDecl>().mapTo(candidateHashes) { it.hash }
            module.decls.filterIsInstance<TypedTypeDecl>().mapTo(candidateHashes) { it.hash }
            module.abilityDecls.mapTo(candidateHashes) { it.hash }
            module.abilityImpls.mapTo(candidateHashes) { it.hash }
            module.effectDecls.mapTo(candidateHashes) { it.hash }
            module.performerDecls.mapTo(candidateHashes) { it.hash }
        }
        result.loweredFunctions.mapTo(candidateHashes) { it.hash }

        val existing = store.blobsExist(candidateHashes)
        val newHashes = candidateHashes - existing

        store.transaction {
            // Register the module — always update (file may have new sourceHash)
            store.putModule(modulePath, filePath, sourceHash)

            val typedModule = result.typedModule
            if (typedModule != null) {
                scatterTypedModule(store, typedModule, modulePath, existing)

                // Cache the whole TypedModule blob keyed by (path, sourceHash).
                // Lets future JVM starts skip re-typecheck if source matches.
                // Idempotent: same (path, hash) → same bytes → INSERT OR REPLACE no-op.
                val typedBlob = CodebaseSerialization.serializeTypedModule(typedModule)
                store.putTypedModule(modulePath, sourceHash, typedBlob)
            }

            // Cache typed bytes for every transitively-loaded project module
            // (skip stdlib — its modulePath starts with "std::" and lives
            // inside the install dir, never a project file).
            //
            // The LoadedModule.sourceHash from ImportResolver is a MezzeHash
            // (#-prefixed SHA3-512). Hydration uses SHA-256 of file bytes for
            // compatibility with DevRunner's keying. Re-compute SHA-256 here
            // so both tools share the same `(modulePath, sourceHash)` keys.
            for ((loadedPath, loadedTyped) in result.loadedModules) {
                if (loadedPath == modulePath) continue
                if (loadedPath.startsWith(com.mezze.language.parsedast.STDLIB_NAMESPACE_PREFIX)) {
                    continue  // stdlib handled by PrecompiledStdlib
                }
                val src = result.loadedModuleSources[loadedPath] ?: continue
                val file = File(src.filePath).takeIf { it.exists() } ?: continue
                val sha256 = sha256Hex(file.readText())
                store.putModule(loadedPath, src.filePath, sha256)
                val blob = CodebaseSerialization.serializeTypedModule(loadedTyped)
                store.putTypedModule(loadedPath, sha256, blob)
            }

            val functions = result.loweredFunctions
            if (functions.isNotEmpty()) {
                scatterFunctionIRs(store, functions, modulePath, existing)
            }
        }

        return PopulateDelta(newHashes = newHashes, unchangedHashes = existing)
    }

    /**
     * Scatter a TypedModule's declarations into individual blobs.
     * Skips putBlob for hashes already in the store; always re-registers names
     * and edges (cheap; cover module-level rebinding).
     */
    private fun scatterTypedModule(
        store: MezzeStore,
        module: TypedModule,
        modulePath: String,
        existing: Set<Hash>
    ) {
        for (decl in module.decls.filterIsInstance<TypedLetDecl>()) {
            if (decl.hash !in existing) {
                val visibility = if (decl.isPublic) Visibility.PUBLIC else Visibility.PRIVATE
                store.putBlob(decl.hash, BlobKind.FUNCTION, visibility,
                    CodebaseSerialization.serializeTypedLetDecl(decl), null)
            }
            store.registerName(modulePath, decl.name, decl.hash)
        }

        for (decl in module.decls.filterIsInstance<TypedTypeDecl>()) {
            if (decl.hash !in existing) {
                val visibility = if (decl.isPublic) Visibility.PUBLIC else Visibility.PRIVATE
                store.putBlob(decl.hash, BlobKind.TYPE, visibility,
                    CodebaseSerialization.serializeTypedTypeDecl(decl), null)
            }
            store.registerName(modulePath, decl.name, decl.hash)
        }

        for (decl in module.abilityDecls) {
            if (decl.hash !in existing) {
                store.putBlob(decl.hash, BlobKind.ABILITY, Visibility.PUBLIC,
                    CodebaseSerialization.serializeTypedAbilityDecl(decl), null)
            }
            store.registerName(modulePath, decl.name, decl.hash)
        }

        for (impl in module.abilityImpls) {
            if (impl.hash !in existing) {
                store.putBlob(impl.hash, BlobKind.ABILITY_IMPL, Visibility.PUBLIC,
                    CodebaseSerialization.serializeTypedAbilityImpl(impl), null)
                store.addEdge(impl.hash, impl.abilityHash, EdgeKind.IMPLEMENTS)
            }
            val targetType = impl.targetTypes.firstOrNull()?.toString() ?: "unknown"
            store.putAbilityImpl(impl.hash, impl.abilityHash, targetType, modulePath)
        }

        for (decl in module.effectDecls) {
            if (decl.hash !in existing) {
                store.putBlob(decl.hash, BlobKind.EFFECT, Visibility.PUBLIC,
                    CodebaseSerialization.serializeTypedEffectDecl(decl), null)
            }
            store.registerName(modulePath, decl.name, decl.hash)
        }

        for (decl in module.performerDecls) {
            if (decl.hash !in existing) {
                store.putBlob(decl.hash, BlobKind.PERFORMER, Visibility.PUBLIC,
                    CodebaseSerialization.serializeTypedPerformerDecl(decl), null)
                store.addEdge(decl.hash, decl.effectHash, EdgeKind.IMPLEMENTS)
            }
            store.registerName(modulePath, decl.name, decl.hash)
            store.putEffectPerformer(decl.hash, decl.effectHash, decl.name, modulePath)
        }
    }

    /**
     * Store lowered FunctionIRs and record dependency edges.
     *
     * - If hash existed in store BEFORE this populate run AND its blob already
     *   has IR: skip entirely (content-addressed: same hash → same bytes).
     * - Otherwise: read current blob row (might have been written by
     *   scatterTypedModule earlier in this same transaction with typed-only)
     *   and merge in IR.
     */
    private fun scatterFunctionIRs(
        store: MezzeStore,
        functions: List<FunctionIR>,
        modulePath: String,
        preExistingWithIR: Set<Hash>
    ) {
        for (fn in functions) {
            // Read current blob — covers both cases:
            //   (a) blob written by scatterTypedModule moments ago (typed set, ir=null)
            //   (b) blob existed before this run (skip if ir already set)
            val row = store.getBlob(fn.hash)
            if (row?.ir != null) continue  // already complete

            val irBytes = CodebaseSerialization.serializeFunctionIR(fn)
            store.putBlob(
                fn.hash,
                row?.kind ?: BlobKind.FUNCTION,
                row?.visibility ?: Visibility.PRIVATE,
                row?.typed,
                irBytes
            )
            recordEdges(store, fn)
        }
    }

    /**
     * Walk a FunctionIR's body and record dependency edges in the store.
     * These edges power:
     * - `mez build` transitive closure (which blobs go into the JAR)
     * - LSP find-references (reverse edge lookup)
     * - Incremental invalidation (what to recompile when X changes)
     */
    private fun recordEdges(store: MezzeStore, fn: FunctionIR) {
        val fromHash = fn.hash
        collectEdgesFromNode(fn.body) { toHex, kind ->
            try {
                store.addEdge(fromHash, Hash(toHex), kind)
            } catch (_: Exception) {
                // Skip invalid hashes (e.g., native function names that aren't hex)
            }
        }
    }

    /**
     * Walk a NodeIR tree and report all hash references as edges.
     */
    private fun collectEdgesFromNode(
        node: com.mezze.language.truffleir.NodeIR,
        report: (toHex: String, kind: EdgeKind) -> Unit
    ) {
        when (node) {
            is com.mezze.language.truffleir.CallDirectIR -> {
                report(node.targetHash.hex, EdgeKind.CALLS)
                collectEdgesFromNode(node.arg, report)
            }
            is com.mezze.language.truffleir.MakeLambdaIR -> {
                report(node.functionHash.hex, EdgeKind.CALLS)
            }
            is com.mezze.language.truffleir.HandleIR -> {
                collectEdgesFromNode(node.body, report)
                for (h in node.handlers) {
                    report(h.handlerHash.hex, EdgeKind.REFERENCES)
                    report(h.effectHash.hex, EdgeKind.REFERENCES)
                    h.params?.let { collectEdgesFromNode(it, report) }
                }
            }
            is com.mezze.language.truffleir.PerformOpIR -> {
                report(node.effectHash.hex, EdgeKind.REFERENCES)
                collectEdgesFromNode(node.arg, report)
            }
            is com.mezze.language.truffleir.BlockIR -> {
                for (stmt in node.stmts) {
                    when (stmt) {
                        is com.mezze.language.truffleir.StmtIR.Let -> collectEdgesFromNode(stmt.value, report)
                        is com.mezze.language.truffleir.StmtIR.Expr -> collectEdgesFromNode(stmt.node, report)
                    }
                }
                collectEdgesFromNode(node.result, report)
            }
            is com.mezze.language.truffleir.CallLocalIR -> collectEdgesFromNode(node.arg, report)
            is com.mezze.language.truffleir.CallNativeIR -> collectEdgesFromNode(node.arg, report)
            is com.mezze.language.truffleir.CallIndirectIR -> {
                collectEdgesFromNode(node.function, report)
                collectEdgesFromNode(node.arg, report)
            }
            is com.mezze.language.truffleir.MakeRecordIR -> node.fields.forEach { collectEdgesFromNode(it.value, report) }
            is com.mezze.language.truffleir.ReadFieldIR -> collectEdgesFromNode(node.record, report)
            is com.mezze.language.truffleir.RecordUpdateIR -> {
                collectEdgesFromNode(node.record, report)
                node.updates.forEach { collectEdgesFromNode(it.value, report) }
            }
            is com.mezze.language.truffleir.MakeVariantIR -> node.payload?.let { collectEdgesFromNode(it, report) }
            is com.mezze.language.truffleir.MatchIR -> {
                collectEdgesFromNode(node.scrutinee, report)
                for (c in node.cases) {
                    collectEdgesFromNode(c.body, report)
                    c.guard?.let { collectEdgesFromNode(it, report) }
                    for (ilb in c.ifLetBindings) collectEdgesFromNode(ilb.expr, report)
                }
            }
            is com.mezze.language.truffleir.IfIR -> {
                collectEdgesFromNode(node.cond, report)
                collectEdgesFromNode(node.thenBr, report)
                collectEdgesFromNode(node.elseBr, report)
            }
            is com.mezze.language.truffleir.WriteLocalIR -> collectEdgesFromNode(node.value, report)
            is com.mezze.language.truffleir.InlineBinaryOpIR -> {
                collectEdgesFromNode(node.left, report)
                collectEdgesFromNode(node.right, report)
            }
            is com.mezze.language.truffleir.InterpolatedStringIR -> {
                for (part in node.parts) {
                    if (part is com.mezze.language.truffleir.StringPartIR.Expr) collectEdgesFromNode(part.node, report)
                }
            }
            is com.mezze.language.truffleir.MakeSeqIR -> {
                for (elem in node.elements) {
                    when (elem) {
                        is com.mezze.language.truffleir.SeqElementIR.Regular -> collectEdgesFromNode(elem.node, report)
                        is com.mezze.language.truffleir.SeqElementIR.Spread -> collectEdgesFromNode(elem.node, report)
                    }
                }
            }
            is com.mezze.language.truffleir.SeqGetIR -> {
                collectEdgesFromNode(node.seq, report)
                collectEdgesFromNode(node.index, report)
            }
            is com.mezze.language.truffleir.DictMethodCallIR -> collectEdgesFromNode(node.arg, report)
            is com.mezze.language.truffleir.ReadContextFnIR -> report(node.name, EdgeKind.REFERENCES)
            is com.mezze.language.truffleir.ReadContextIR -> report(node.name, EdgeKind.REFERENCES)
            // Leaf nodes — no edges
            is com.mezze.language.truffleir.ReadLocalIR -> {}
            is com.mezze.language.truffleir.ReadCaptureIR -> {}
            is com.mezze.language.truffleir.ReadArgIR -> {}
            is com.mezze.language.truffleir.LongLitIR -> {}
            is com.mezze.language.truffleir.DoubleLitIR -> {}
            is com.mezze.language.truffleir.BoolLitIR -> {}
            is com.mezze.language.truffleir.StringLitIR -> {}
            is com.mezze.language.truffleir.UnitLitIR -> {}
            is com.mezze.language.truffleir.CharLitIR -> {}
            is com.mezze.language.truffleir.FracLitIR -> {}
        }
    }

    /** SHA-256 of source bytes; matches DevRunner / LSP keying convention
     *  for the typed_modules table. */
    private fun sha256Hex(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(content.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
