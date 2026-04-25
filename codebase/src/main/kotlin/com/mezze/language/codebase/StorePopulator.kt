package com.mezze.language.codebase

import com.mezze.language.typechecker.TypeCheckResult
import com.mezze.language.typedast.*
import com.mezze.language.truffleir.FunctionIR

/**
 * Scatters compilation pipeline output into a MezzeStore.
 *
 * Takes a TypeCheckResult (produced by TypeChecker.typeCheck()) and writes
 * individual definitions as blobs, registers names, records edges, etc.
 *
 * This is the bridge between the compilation pipeline and the storage layer.
 * Called by `mez dev`, `mez check`, and `mez build` after each file is compiled.
 */
object StorePopulator {

    /**
     * Scatter a TypeCheckResult into the store.
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
    ) {
        store.transaction {
            // Register the module
            store.putModule(modulePath, filePath, sourceHash)

            // Scatter TypedModule declarations
            val typedModule = result.typedModule
            if (typedModule != null) {
                scatterTypedModule(store, typedModule, modulePath)
            }

            // Store lowered FunctionIRs
            val functions = result.loweredFunctions
            if (functions.isNotEmpty()) {
                scatterFunctionIRs(store, functions, modulePath)
            }
        }
    }

    /**
     * Scatter a TypedModule's declarations into individual blobs.
     */
    private fun scatterTypedModule(store: MezzeStore, module: TypedModule, modulePath: String) {
        // Let bindings → function blobs
        for (decl in module.decls.filterIsInstance<TypedLetDecl>()) {
            val visibility = if (decl.isPublic) Visibility.PUBLIC else Visibility.PRIVATE
            val typedBytes = CodebaseSerialization.serializeTypedLetDecl(decl)

            store.putBlob(decl.hash, BlobKind.FUNCTION, visibility, typedBytes, null)
            store.registerName(modulePath, decl.name, decl.hash)
        }

        // Type declarations → type blobs
        for (decl in module.decls.filterIsInstance<TypedTypeDecl>()) {
            val visibility = if (decl.isPublic) Visibility.PUBLIC else Visibility.PRIVATE
            val typedBytes = CodebaseSerialization.serializeTypedTypeDecl(decl)

            store.putBlob(decl.hash, BlobKind.TYPE, visibility, typedBytes, null)
            store.registerName(modulePath, decl.name, decl.hash)
        }

        // Ability declarations
        for (decl in module.abilityDecls) {
            val typedBytes = CodebaseSerialization.serializeTypedAbilityDecl(decl)
            store.putBlob(decl.hash, BlobKind.ABILITY, Visibility.PUBLIC, typedBytes, null)
            store.registerName(modulePath, decl.name, decl.hash)
        }

        // Ability implementations
        for (impl in module.abilityImpls) {
            val typedBytes = CodebaseSerialization.serializeTypedAbilityImpl(impl)
            store.putBlob(impl.hash, BlobKind.ABILITY_IMPL, Visibility.PUBLIC, typedBytes, null)

            // Index for lookup: "find impl of ability X for type Y"
            val targetType = impl.targetTypes.firstOrNull()?.toString() ?: "unknown"
            store.putAbilityImpl(impl.hash, impl.abilityHash, targetType, modulePath)

            // Edge: impl implements ability
            store.addEdge(impl.hash, impl.abilityHash, EdgeKind.IMPLEMENTS)
        }

        // Effect declarations
        for (decl in module.effectDecls) {
            val typedBytes = CodebaseSerialization.serializeTypedEffectDecl(decl)
            store.putBlob(decl.hash, BlobKind.EFFECT, Visibility.PUBLIC, typedBytes, null)
            store.registerName(modulePath, decl.name, decl.hash)
        }

        // Performer declarations
        for (decl in module.performerDecls) {
            val typedBytes = CodebaseSerialization.serializeTypedPerformerDecl(decl)
            store.putBlob(decl.hash, BlobKind.PERFORMER, Visibility.PUBLIC, typedBytes, null)
            store.registerName(modulePath, decl.name, decl.hash)

            // Index for lookup: "find all performers for effect X"
            store.putEffectPerformer(decl.hash, decl.effectHash, decl.name, modulePath)

            // Edge: performer implements effect
            store.addEdge(decl.hash, decl.effectHash, EdgeKind.IMPLEMENTS)
        }
    }

    /**
     * Store lowered FunctionIRs and record dependency edges.
     */
    private fun scatterFunctionIRs(store: MezzeStore, functions: List<FunctionIR>, modulePath: String) {
        for (fn in functions) {
            val irBytes = CodebaseSerialization.serializeFunctionIR(fn)

            // Update the blob's IR column (typed may already be set from scatterTypedModule)
            val existing = store.getBlob(fn.hash)
            if (existing != null) {
                // Blob exists from typed scatter — add IR
                store.putBlob(fn.hash, existing.kind, existing.visibility, existing.typed, irBytes)
            } else {
                // New blob (lambda body, monomorphic instance, etc.)
                store.putBlob(fn.hash, BlobKind.FUNCTION, Visibility.PRIVATE, null, irBytes)
            }

            // Record dependency edges from this function's IR body
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
}
