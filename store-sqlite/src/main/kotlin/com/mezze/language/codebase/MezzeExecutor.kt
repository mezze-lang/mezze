package com.mezze.language.codebase

import com.mezze.language.store.*

import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.typedast.Hash

/**
 * Hash-based lazy execution engine.
 *
 * Loads FunctionIR from MezzeStore(s), compiles to Truffle CallTargets on demand.
 * This is the bridge between the storage layer and the Truffle runtime.
 *
 * The executor does NOT replace MonomorphicCallNode's hot-path resolution.
 * Instead, it orchestrates the initial compilation:
 * 1. Resolve entry hash → load FunctionIR from store
 * 2. Collect transitively referenced hashes
 * 3. Feed all needed FunctionIRs into the existing TruffleCompiler/PipelineRunner
 * 4. The Truffle runtime then handles execution and on-demand compilation
 *
 * Later, getCachedStdlibCallTarget in MezzeLanguage can be refactored to
 * query the store instead of PrecompiledStdlib — but that's a separate change.
 */
class MezzeExecutor(
    private val stores: List<MezzeStore>
) {
    /**
     * Resolve a FunctionIR by hash, searching stores in order.
     * Returns null if not found in any store.
     */
    fun loadIR(hash: Hash): FunctionIR? {
        for (store in stores) {
            val irBytes = store.getIR(hash) ?: continue
            return StoreSerialization.deserializeFunctionIR(irBytes)
        }
        return null
    }

    /**
     * Load all FunctionIRs needed to execute from an entry point.
     *
     * Walks the IR tree to find all transitively referenced hashes,
     * loads each from the stores, and returns the complete set.
     *
     * @param entryHash The entry point function hash
     * @return All FunctionIRs needed for execution (entry + dependencies)
     */
    fun loadTransitiveFunctions(entryHash: Hash): List<FunctionIR> {
        val loaded = mutableMapOf<String, FunctionIR>()
        val queue = ArrayDeque<String>()
        queue.add(entryHash.hex)

        while (queue.isNotEmpty()) {
            val hex = queue.removeFirst()
            if (hex in loaded) continue

            val ir = loadIRByHex(hex)
            if (ir != null) {
                loaded[hex] = ir
                collectReferencedHashes(ir.body, queue)
            }
            // If not found, it might be a native function or
            // already registered in the Language cache — that's fine,
            // MonomorphicCallNode will handle it at runtime.
        }

        return loaded.values.toList()
    }

    /**
     * Resolve entry point hash from a module name.
     * Looks for "main" in the given module across all stores.
     */
    fun resolveEntry(modulePath: String): Hash? {
        for (store in stores) {
            store.resolveName(modulePath, "main")?.let { return it }
        }
        // Also try __entry__ (top-level expressions wrapped by EntryFunctionBuilder)
        for (store in stores) {
            store.resolveName(modulePath, "__entry__")?.let { return it }
        }
        return null
    }

    /**
     * Check if a hash exists in any store.
     */
    fun exists(hash: Hash): Boolean {
        return stores.any { it.blobExists(hash) }
    }

    /**
     * Walk [bodies] and collect direct-call/lambda target hashes that
     * the predicate rejects.
     *
     * Only validates IR node kinds whose runtime resolution crashes
     * loudly if the hash is missing:
     *   CallDirectIR.targetHash       → CallDirectScalarNode
     *   PartialApplyDirectIR.targetHash
     *   MakeLambdaIR.functionHash
     *   CallDirectIR variants in BlockIR statements
     *
     * NOT validated (resolved via other runtime paths that don't crash
     * on missing-hash, or that always-true-by-construction):
     *   effectHash, handlerHash — bound at perform/handle time
     *   DictMethodCallIR — ability dispatch via dictionary slot
     *   ReadGlobalByHashIR / ReadGlobalFnByHashIR — may be runtime-only
     *   native function hashes — resolved by name via NativeDispatch
     *
     * Returns the set of unresolvable direct-call targets, which signals
     * the cache is stale (typically: built by an older mez whose helper
     * hashes have since shifted). Recompile from source.
     */
    fun collectUnresolvedHashes(
        bodies: List<FunctionIR>,
        resolves: (Hash) -> Boolean
    ): Set<Hash> {
        val ownHashes = bodies.mapTo(mutableSetOf()) { it.hash.hex }
        val unresolved = mutableSetOf<Hash>()
        val visited = mutableSetOf<String>()
        for (fn in bodies) {
            collectDirectCallTargets(fn.body, ownHashes, visited, resolves, unresolved)
        }
        return unresolved
    }

    /** Walk only the call/lambda-target ref kinds that crash on missing hash. */
    private fun collectDirectCallTargets(
        node: com.mezze.language.truffleir.NodeIR,
        ownHashes: Set<String>,
        visited: MutableSet<String>,
        resolves: (Hash) -> Boolean,
        out: MutableSet<Hash>
    ) {
        fun check(hash: Hash) {
            val hex = hash.hex
            if (!visited.add(hex)) return
            if (hex in ownHashes) return
            if (!resolves(hash)) out.add(hash)
        }
        when (node) {
            is com.mezze.language.truffleir.CallDirectIR -> {
                check(node.targetHash)
                node.args.forEach { collectDirectCallTargets(it, ownHashes, visited, resolves, out) }
            }
            is com.mezze.language.truffleir.PartialApplyDirectIR -> {
                check(node.targetHash)
                node.partialFields.forEach { collectDirectCallTargets(it.value, ownHashes, visited, resolves, out) }
            }
            is com.mezze.language.truffleir.MakeLambdaIR -> {
                check(node.functionHash)
            }
            // Compound nodes: just recurse into their bodies, don't validate their own hashes.
            is com.mezze.language.truffleir.HandleIR -> {
                collectDirectCallTargets(node.body, ownHashes, visited, resolves, out)
                for (h in node.handlers) h.params?.let { collectDirectCallTargets(it, ownHashes, visited, resolves, out) }
            }
            is com.mezze.language.truffleir.PerformOpIR -> collectDirectCallTargets(node.arg, ownHashes, visited, resolves, out)
            is com.mezze.language.truffleir.BlockIR -> {
                for (stmt in node.stmts) when (stmt) {
                    is com.mezze.language.truffleir.StmtIR.Let -> collectDirectCallTargets(stmt.value, ownHashes, visited, resolves, out)
                    is com.mezze.language.truffleir.StmtIR.Expr -> collectDirectCallTargets(stmt.node, ownHashes, visited, resolves, out)
                }
                collectDirectCallTargets(node.result, ownHashes, visited, resolves, out)
            }
            is com.mezze.language.truffleir.CallLocalIR -> collectDirectCallTargets(node.arg, ownHashes, visited, resolves, out)
            is com.mezze.language.truffleir.CallNativeIR -> {
                collectDirectCallTargets(node.arg, ownHashes, visited, resolves, out)
                node.args.forEach { collectDirectCallTargets(it, ownHashes, visited, resolves, out) }
            }
            is com.mezze.language.truffleir.CallIndirectIR -> {
                collectDirectCallTargets(node.function, ownHashes, visited, resolves, out)
                collectDirectCallTargets(node.arg, ownHashes, visited, resolves, out)
            }
            is com.mezze.language.truffleir.CallIndirectScalarIR -> {
                collectDirectCallTargets(node.function, ownHashes, visited, resolves, out)
                node.args.forEach { collectDirectCallTargets(it, ownHashes, visited, resolves, out) }
            }
            is com.mezze.language.truffleir.MakeRecordIR -> node.fields.forEach { collectDirectCallTargets(it.value, ownHashes, visited, resolves, out) }
            is com.mezze.language.truffleir.ReadFieldIR -> collectDirectCallTargets(node.record, ownHashes, visited, resolves, out)
            is com.mezze.language.truffleir.RecordUpdateIR -> {
                collectDirectCallTargets(node.record, ownHashes, visited, resolves, out)
                node.updates.forEach { collectDirectCallTargets(it.value, ownHashes, visited, resolves, out) }
            }
            is com.mezze.language.truffleir.MakeVariantIR -> node.payload?.let { collectDirectCallTargets(it, ownHashes, visited, resolves, out) }
            is com.mezze.language.truffleir.MatchIR -> {
                collectDirectCallTargets(node.scrutinee, ownHashes, visited, resolves, out)
                for (c in node.cases) {
                    collectDirectCallTargets(c.body, ownHashes, visited, resolves, out)
                    c.guard?.let { collectDirectCallTargets(it, ownHashes, visited, resolves, out) }
                    for (ilb in c.ifLetBindings) collectDirectCallTargets(ilb.expr, ownHashes, visited, resolves, out)
                }
            }
            is com.mezze.language.truffleir.IfIR -> {
                collectDirectCallTargets(node.cond, ownHashes, visited, resolves, out)
                collectDirectCallTargets(node.thenBr, ownHashes, visited, resolves, out)
                collectDirectCallTargets(node.elseBr, ownHashes, visited, resolves, out)
            }
            is com.mezze.language.truffleir.WriteLocalIR -> collectDirectCallTargets(node.value, ownHashes, visited, resolves, out)
            is com.mezze.language.truffleir.InlineBinaryOpIR -> {
                collectDirectCallTargets(node.left, ownHashes, visited, resolves, out)
                collectDirectCallTargets(node.right, ownHashes, visited, resolves, out)
            }
            is com.mezze.language.truffleir.SpecializedCallIR -> node.args.forEach { collectDirectCallTargets(it, ownHashes, visited, resolves, out) }
            is com.mezze.language.truffleir.InterpolatedStringIR -> {
                for (part in node.parts) if (part is com.mezze.language.truffleir.StringPartIR.Expr) collectDirectCallTargets(part.node, ownHashes, visited, resolves, out)
            }
            is com.mezze.language.truffleir.MakeSeqIR -> {
                for (elem in node.elements) when (elem) {
                    is com.mezze.language.truffleir.SeqElementIR.Regular -> collectDirectCallTargets(elem.node, ownHashes, visited, resolves, out)
                    is com.mezze.language.truffleir.SeqElementIR.Spread -> collectDirectCallTargets(elem.node, ownHashes, visited, resolves, out)
                }
            }
            is com.mezze.language.truffleir.SeqGetIR -> {
                collectDirectCallTargets(node.seq, ownHashes, visited, resolves, out)
                collectDirectCallTargets(node.index, ownHashes, visited, resolves, out)
            }
            is com.mezze.language.truffleir.DictMethodCallIR -> collectDirectCallTargets(node.arg, ownHashes, visited, resolves, out)
            // Leaves / effect-hash / read-global — not validated, no recursion.
            is com.mezze.language.truffleir.ReadGlobalByHashIR -> {}
            is com.mezze.language.truffleir.ReadGlobalFnByHashIR -> {}
            is com.mezze.language.truffleir.EffectBindingIR -> {}
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

    private fun loadIRByHex(hex: String): FunctionIR? {
        val hash = try { Hash(hex) } catch (_: Exception) { return null }
        return loadIR(hash)
    }

    /**
     * Walk a NodeIR tree and collect all hash references (CallDirect, MakeLambda, etc.).
     * This is the same traversal as FunctionIRReachability.collectHashesFromNode but operates
     * on the FunctionIR level for store-based loading.
     */
    private fun collectReferencedHashes(node: com.mezze.language.truffleir.NodeIR, queue: ArrayDeque<String>) {
        when (node) {
            is com.mezze.language.truffleir.CallDirectIR -> {
                queue.add(node.targetHash.hex)
                node.args.forEach { collectReferencedHashes(it, queue) }
            }
            is com.mezze.language.truffleir.PartialApplyDirectIR -> {
                queue.add(node.targetHash.hex)
                node.partialFields.forEach { collectReferencedHashes(it.value, queue) }
            }
            is com.mezze.language.truffleir.MakeLambdaIR -> {
                queue.add(node.functionHash.hex)
                for (cap in node.captures) collectReferencedHashes(cap, queue)
            }
            is com.mezze.language.truffleir.HandleIR -> {
                collectReferencedHashes(node.body, queue)
                for (h in node.handlers) {
                    queue.add(h.handlerHash.hex)
                    queue.add(h.effectHash.hex)
                    h.params?.let { collectReferencedHashes(it, queue) }
                }
            }
            is com.mezze.language.truffleir.PerformOpIR -> {
                queue.add(node.effectHash.hex)
                collectReferencedHashes(node.arg, queue)
            }
            is com.mezze.language.truffleir.BlockIR -> {
                for (stmt in node.stmts) {
                    when (stmt) {
                        is com.mezze.language.truffleir.StmtIR.Let -> collectReferencedHashes(stmt.value, queue)
                        is com.mezze.language.truffleir.StmtIR.Expr -> collectReferencedHashes(stmt.node, queue)
                    }
                }
                collectReferencedHashes(node.result, queue)
            }
            is com.mezze.language.truffleir.CallLocalIR -> collectReferencedHashes(node.arg, queue)
            is com.mezze.language.truffleir.CallNativeIR -> {
                collectReferencedHashes(node.arg, queue)
                node.args.forEach { collectReferencedHashes(it, queue) }
            }
            is com.mezze.language.truffleir.CallIndirectIR -> {
                collectReferencedHashes(node.function, queue)
                collectReferencedHashes(node.arg, queue)
            }
            is com.mezze.language.truffleir.CallIndirectScalarIR -> {
                collectReferencedHashes(node.function, queue)
                node.args.forEach { collectReferencedHashes(it, queue) }
            }
            is com.mezze.language.truffleir.MakeRecordIR -> node.fields.forEach { collectReferencedHashes(it.value, queue) }
            is com.mezze.language.truffleir.ReadFieldIR -> collectReferencedHashes(node.record, queue)
            is com.mezze.language.truffleir.RecordUpdateIR -> {
                collectReferencedHashes(node.record, queue)
                node.updates.forEach { collectReferencedHashes(it.value, queue) }
            }
            is com.mezze.language.truffleir.MakeVariantIR -> node.payload?.let { collectReferencedHashes(it, queue) }
            is com.mezze.language.truffleir.MatchIR -> {
                collectReferencedHashes(node.scrutinee, queue)
                for (c in node.cases) {
                    collectReferencedHashes(c.body, queue)
                    c.guard?.let { collectReferencedHashes(it, queue) }
                    for (ilb in c.ifLetBindings) collectReferencedHashes(ilb.expr, queue)
                }
            }
            is com.mezze.language.truffleir.IfIR -> {
                collectReferencedHashes(node.cond, queue)
                collectReferencedHashes(node.thenBr, queue)
                collectReferencedHashes(node.elseBr, queue)
            }
            is com.mezze.language.truffleir.WriteLocalIR -> collectReferencedHashes(node.value, queue)
            is com.mezze.language.truffleir.InlineBinaryOpIR -> {
                collectReferencedHashes(node.left, queue)
                collectReferencedHashes(node.right, queue)
            }
            is com.mezze.language.truffleir.SpecializedCallIR -> {
                node.args.forEach { collectReferencedHashes(it, queue) }
            }
            is com.mezze.language.truffleir.InterpolatedStringIR -> {
                for (part in node.parts) {
                    if (part is com.mezze.language.truffleir.StringPartIR.Expr) collectReferencedHashes(part.node, queue)
                }
            }
            is com.mezze.language.truffleir.MakeSeqIR -> {
                for (elem in node.elements) {
                    when (elem) {
                        is com.mezze.language.truffleir.SeqElementIR.Regular -> collectReferencedHashes(elem.node, queue)
                        is com.mezze.language.truffleir.SeqElementIR.Spread -> collectReferencedHashes(elem.node, queue)
                    }
                }
            }
            is com.mezze.language.truffleir.SeqGetIR -> {
                collectReferencedHashes(node.seq, queue)
                collectReferencedHashes(node.index, queue)
            }
            is com.mezze.language.truffleir.DictMethodCallIR -> collectReferencedHashes(node.arg, queue)
            is com.mezze.language.truffleir.ReadGlobalByHashIR -> queue.add(node.hash.hex)
            is com.mezze.language.truffleir.ReadGlobalFnByHashIR -> queue.add(node.hash.hex)
            is com.mezze.language.truffleir.EffectBindingIR -> {}
            // Leaf nodes
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

    /**
     * Collect all hash references from a CaptureIR.
     * CaptureIR itself is just a slot reference (no child nodes to walk),
     * but MakeLambdaIR.captures is List<CaptureIR> which we handle in the MakeLambdaIR case.
     */
    private fun collectReferencedHashes(capture: com.mezze.language.truffleir.CaptureIR, queue: ArrayDeque<String>) {
        // CaptureIR is a leaf — just slot + kind, no hash references
    }
}
