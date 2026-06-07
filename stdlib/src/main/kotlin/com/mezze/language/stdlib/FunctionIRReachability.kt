package com.mezze.language.stdlib

import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.truffleir.NodeIR
import com.mezze.language.typedast.Hash

/**
 * Compute the transitive closure of FunctionIR hashes reachable from a
 * set of seed FunctionIRs.
 *
 * Pure IR walker — no Truffle types, no execution, no compile state.
 * Lives in `:stdlib` (next to [StdlibRuntimeData] whose dependency
 * graph we walk) so the interpreter and `mez build` can call it
 * without pulling in `:typechecker` or `:interpreter`.
 *
 * Single source of truth used by `mez build`'s reachable-closure
 * computation: user code + every stdlib function transitively
 * referenced. The output is what goes into the user-app jar's
 * `embedded-app.cbor`.
 *
 * History: originally lived in
 * [com.mezze.interpreter.compiler.PipelineRunner]; moved to
 * `:typechecker` in #216 to break `:cli`'s transitive `:interpreter`
 * dependency; moved to `:stdlib` in #222 to drop the runtime jar's
 * `:typechecker` dependency.
 */
object FunctionIRReachability {

    /**
     * Walk [userFunctions] plus the stdlib dependency graph to find
     * every FunctionIR hash that may be invoked at runtime.
     *
     * Returns the closure as a set — order is not meaningful.
     */
    @JvmStatic
    fun collectReferencedHashes(userFunctions: List<FunctionIR>): Set<Hash> {
        val timing = System.getenv("MEZZE_TIMING") != null
        val tStart = System.nanoTime()
        fun mark(label: String) {
            if (timing) System.err.println("[${(System.nanoTime() - tStart) / 1_000_000}ms]   collectReferencedHashes: $label")
        }
        val referenced = mutableSetOf<Hash>()
        val queue = ArrayDeque<Hash>()

        for (fn in userFunctions) {
            referenced.add(fn.hash)
            collectHashesFromNode(fn.body, queue)
        }
        mark("user fn IR walked (queue=${queue.size})")

        val depGraph = StdlibRuntimeData.dependencyGraph
        mark("stdlibDependencyGraph loaded (size=${depGraph.size})")

        while (queue.isNotEmpty()) {
            val hash = queue.removeFirst()
            if (hash in referenced) continue
            referenced.add(hash)
            depGraph[hash]?.let { queue.addAll(it) }
        }
        mark("transitive closure done (${referenced.size} hashes)")

        return referenced
    }

    /**
     * Walk a single [NodeIR] tree, adding every hash referenced by any
     * sub-node to [queue]. Used both internally by
     * [collectReferencedHashes] and by external callers (e.g. v2
     * pipeline glue) that need the same traversal.
     */
    @JvmStatic
    fun collectHashesFromNode(node: NodeIR, queue: ArrayDeque<Hash>) {
        when (node) {
            is com.mezze.language.truffleir.CallDirectIR -> {
                queue.add(node.targetHash)
                node.args.forEach { collectHashesFromNode(it, queue) }
            }
            is com.mezze.language.truffleir.PartialApplyDirectIR -> {
                queue.add(node.targetHash)
                node.partialFields.forEach { collectHashesFromNode(it.value, queue) }
            }
            is com.mezze.language.truffleir.MakeLambdaIR -> queue.add(node.functionHash)
            is com.mezze.language.truffleir.HandleIR -> {
                collectHashesFromNode(node.body, queue)
                for (h in node.handlers) {
                    queue.add(h.handlerHash)
                    queue.add(h.effectHash)
                }
            }
            is com.mezze.language.truffleir.PerformOpIR -> {
                queue.add(node.effectHash)
                collectHashesFromNode(node.arg, queue)
            }
            is com.mezze.language.truffleir.BlockIR -> {
                for (stmt in node.stmts) {
                    when (stmt) {
                        is com.mezze.language.truffleir.StmtIR.Let -> collectHashesFromNode(stmt.value, queue)
                        is com.mezze.language.truffleir.StmtIR.Expr -> collectHashesFromNode(stmt.node, queue)
                    }
                }
                collectHashesFromNode(node.result, queue)
            }
            is com.mezze.language.truffleir.CallLocalIR -> collectHashesFromNode(node.arg, queue)
            is com.mezze.language.truffleir.CallNativeIR -> {
                collectHashesFromNode(node.arg, queue)
                node.args.forEach { collectHashesFromNode(it, queue) }
            }
            is com.mezze.language.truffleir.CallIndirectIR -> {
                collectHashesFromNode(node.function, queue)
                collectHashesFromNode(node.arg, queue)
            }
            is com.mezze.language.truffleir.CallIndirectScalarIR -> {
                collectHashesFromNode(node.function, queue)
                node.args.forEach { collectHashesFromNode(it, queue) }
            }
            is com.mezze.language.truffleir.MakeRecordIR -> node.fields.forEach { collectHashesFromNode(it.value, queue) }
            is com.mezze.language.truffleir.ReadFieldIR -> collectHashesFromNode(node.record, queue)
            is com.mezze.language.truffleir.RecordUpdateIR -> {
                collectHashesFromNode(node.record, queue)
                node.updates.forEach { collectHashesFromNode(it.value, queue) }
            }
            is com.mezze.language.truffleir.MakeVariantIR -> node.payload?.let { collectHashesFromNode(it, queue) }
            is com.mezze.language.truffleir.MatchIR -> {
                collectHashesFromNode(node.scrutinee, queue)
                for (c in node.cases) {
                    collectHashesFromNode(c.body, queue)
                    c.guard?.let { collectHashesFromNode(it, queue) }
                    for (ilb in c.ifLetBindings) {
                        collectHashesFromNode(ilb.expr, queue)
                    }
                }
            }
            is com.mezze.language.truffleir.IfIR -> {
                collectHashesFromNode(node.cond, queue)
                collectHashesFromNode(node.thenBr, queue)
                collectHashesFromNode(node.elseBr, queue)
            }
            is com.mezze.language.truffleir.WriteLocalIR -> collectHashesFromNode(node.value, queue)
            is com.mezze.language.truffleir.InlineBinaryOpIR -> {
                collectHashesFromNode(node.left, queue)
                collectHashesFromNode(node.right, queue)
            }
            is com.mezze.language.truffleir.SpecializedCallIR -> {
                node.args.forEach { collectHashesFromNode(it, queue) }
            }
            is com.mezze.language.truffleir.InterpolatedStringIR -> {
                for (part in node.parts) {
                    if (part is com.mezze.language.truffleir.StringPartIR.Expr) collectHashesFromNode(part.node, queue)
                }
            }
            is com.mezze.language.truffleir.MakeSeqIR -> {
                for (elem in node.elements) {
                    when (elem) {
                        is com.mezze.language.truffleir.SeqElementIR.Regular -> collectHashesFromNode(elem.node, queue)
                        is com.mezze.language.truffleir.SeqElementIR.Spread -> collectHashesFromNode(elem.node, queue)
                    }
                }
            }
            is com.mezze.language.truffleir.SeqGetIR -> {
                collectHashesFromNode(node.seq, queue)
                collectHashesFromNode(node.index, queue)
            }
            is com.mezze.language.truffleir.DictMethodCallIR -> {
                collectHashesFromNode(node.arg, queue)
            }
            // Leaf nodes — no children to walk
            is com.mezze.language.truffleir.ReadLocalIR -> {}
            is com.mezze.language.truffleir.ReadCaptureIR -> {}
            is com.mezze.language.truffleir.ReadArgIR -> {}
            is com.mezze.language.truffleir.ReadGlobalByHashIR -> queue.add(node.hash)
            is com.mezze.language.truffleir.ReadGlobalFnByHashIR -> queue.add(node.hash)
            is com.mezze.language.truffleir.EffectBindingIR -> {} // runtime-supplied effect binding, not a function ref
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
     * A CallDirectIR or kin that targets a Hash unknown to both the user
     * function set and the precompiled stdlib graph. If this list is
     * non-empty, lowering produced a call site that the runtime cannot
     * resolve — it would crash with `MonomorphicCallNode$resolveFunction`'s
     * "Function not found for content hash: …" RuntimeException.
     *
     * Reporting these at the end of lowering converts that bare runtime
     * error into a structured compiler diagnostic the user can act on.
     */
    data class OrphanHashRef(
        val callerHash: Hash,
        val callerName: String,
        val targetHash: Hash,
        val src: com.mezze.language.truffleir.SourceInfo,
    )

    /**
     * Find every CallDirectIR / ReadGlobalByHash / etc. reference whose
     * target is neither a user-compiled function nor a stdlib function.
     *
     * This is the runtime-resolvability check: if a hash isn't in
     * `userFunctions + PrecompiledStdlib`, the call would explode at
     * runtime. Catching it at lowering is strictly better.
     */
    @JvmStatic
    fun findOrphanHashRefs(userFunctions: List<FunctionIR>): List<OrphanHashRef> {
        val known = mutableSetOf<Hash>()
        userFunctions.forEach { known.add(it.hash) }
        try {
            known.addAll(StdlibRuntimeData.preloweredFunctions.keys)
        } catch (_: Throwable) { /* stdlib may be uninitialized in unusual contexts */ }

        val orphans = mutableListOf<OrphanHashRef>()
        for (fn in userFunctions) {
            scanForOrphans(fn.body, fn.hash, fn.name, known, orphans)
        }
        return orphans
    }

    private fun scanForOrphans(
        node: NodeIR,
        callerHash: Hash,
        callerName: String,
        known: Set<Hash>,
        out: MutableList<OrphanHashRef>,
    ) {
        when (node) {
            is com.mezze.language.truffleir.CallDirectIR -> {
                if (node.targetHash !in known) {
                    out.add(OrphanHashRef(callerHash, callerName, node.targetHash, node.src))
                }
                node.args.forEach { scanForOrphans(it, callerHash, callerName, known, out) }
            }
            is com.mezze.language.truffleir.PartialApplyDirectIR -> {
                if (node.targetHash !in known) {
                    out.add(OrphanHashRef(callerHash, callerName, node.targetHash, node.src))
                }
                node.partialFields.forEach { scanForOrphans(it.value, callerHash, callerName, known, out) }
            }
            is com.mezze.language.truffleir.MakeLambdaIR -> {
                if (node.functionHash !in known) {
                    out.add(OrphanHashRef(callerHash, callerName, node.functionHash, node.src))
                }
            }
            is com.mezze.language.truffleir.ReadGlobalByHashIR -> {
                if (node.hash !in known) {
                    out.add(OrphanHashRef(callerHash, callerName, node.hash, node.src))
                }
            }
            is com.mezze.language.truffleir.ReadGlobalFnByHashIR -> {
                if (node.hash !in known) {
                    out.add(OrphanHashRef(callerHash, callerName, node.hash, node.src))
                }
            }
            is com.mezze.language.truffleir.HandleIR -> {
                scanForOrphans(node.body, callerHash, callerName, known, out)
                for (h in node.handlers) {
                    if (h.handlerHash !in known) {
                        out.add(OrphanHashRef(callerHash, callerName, h.handlerHash, node.src))
                    }
                    // h.effectHash is an effect identity, not a function hash; skip.
                }
            }
            is com.mezze.language.truffleir.BlockIR -> {
                for (stmt in node.stmts) {
                    when (stmt) {
                        is com.mezze.language.truffleir.StmtIR.Let -> scanForOrphans(stmt.value, callerHash, callerName, known, out)
                        is com.mezze.language.truffleir.StmtIR.Expr -> scanForOrphans(stmt.node, callerHash, callerName, known, out)
                    }
                }
                scanForOrphans(node.result, callerHash, callerName, known, out)
            }
            is com.mezze.language.truffleir.PerformOpIR -> scanForOrphans(node.arg, callerHash, callerName, known, out)
            is com.mezze.language.truffleir.CallLocalIR -> scanForOrphans(node.arg, callerHash, callerName, known, out)
            is com.mezze.language.truffleir.CallNativeIR -> {
                scanForOrphans(node.arg, callerHash, callerName, known, out)
                node.args.forEach { scanForOrphans(it, callerHash, callerName, known, out) }
            }
            is com.mezze.language.truffleir.CallIndirectIR -> {
                scanForOrphans(node.function, callerHash, callerName, known, out)
                scanForOrphans(node.arg, callerHash, callerName, known, out)
            }
            is com.mezze.language.truffleir.CallIndirectScalarIR -> {
                scanForOrphans(node.function, callerHash, callerName, known, out)
                node.args.forEach { scanForOrphans(it, callerHash, callerName, known, out) }
            }
            is com.mezze.language.truffleir.MakeRecordIR -> node.fields.forEach { scanForOrphans(it.value, callerHash, callerName, known, out) }
            is com.mezze.language.truffleir.ReadFieldIR -> scanForOrphans(node.record, callerHash, callerName, known, out)
            is com.mezze.language.truffleir.RecordUpdateIR -> {
                scanForOrphans(node.record, callerHash, callerName, known, out)
                node.updates.forEach { scanForOrphans(it.value, callerHash, callerName, known, out) }
            }
            is com.mezze.language.truffleir.MakeVariantIR -> node.payload?.let { scanForOrphans(it, callerHash, callerName, known, out) }
            is com.mezze.language.truffleir.MatchIR -> {
                scanForOrphans(node.scrutinee, callerHash, callerName, known, out)
                for (c in node.cases) {
                    scanForOrphans(c.body, callerHash, callerName, known, out)
                    c.guard?.let { scanForOrphans(it, callerHash, callerName, known, out) }
                    for (ilb in c.ifLetBindings) scanForOrphans(ilb.expr, callerHash, callerName, known, out)
                }
            }
            is com.mezze.language.truffleir.IfIR -> {
                scanForOrphans(node.cond, callerHash, callerName, known, out)
                scanForOrphans(node.thenBr, callerHash, callerName, known, out)
                scanForOrphans(node.elseBr, callerHash, callerName, known, out)
            }
            is com.mezze.language.truffleir.WriteLocalIR -> scanForOrphans(node.value, callerHash, callerName, known, out)
            is com.mezze.language.truffleir.InlineBinaryOpIR -> {
                scanForOrphans(node.left, callerHash, callerName, known, out)
                scanForOrphans(node.right, callerHash, callerName, known, out)
            }
            is com.mezze.language.truffleir.SpecializedCallIR -> node.args.forEach { scanForOrphans(it, callerHash, callerName, known, out) }
            is com.mezze.language.truffleir.InterpolatedStringIR -> {
                for (p in node.parts) if (p is com.mezze.language.truffleir.StringPartIR.Expr) scanForOrphans(p.node, callerHash, callerName, known, out)
            }
            is com.mezze.language.truffleir.MakeSeqIR -> {
                for (e in node.elements) when (e) {
                    is com.mezze.language.truffleir.SeqElementIR.Regular -> scanForOrphans(e.node, callerHash, callerName, known, out)
                    is com.mezze.language.truffleir.SeqElementIR.Spread -> scanForOrphans(e.node, callerHash, callerName, known, out)
                }
            }
            is com.mezze.language.truffleir.SeqGetIR -> {
                scanForOrphans(node.seq, callerHash, callerName, known, out)
                scanForOrphans(node.index, callerHash, callerName, known, out)
            }
            is com.mezze.language.truffleir.DictMethodCallIR -> scanForOrphans(node.arg, callerHash, callerName, known, out)
            else -> {} // leaves carry no hash refs
        }
    }
}
