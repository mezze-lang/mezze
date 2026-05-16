package com.mezze.language.typechecker

import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.truffleir.NodeIR
import com.mezze.language.typedast.Hash

/**
 * Compute the transitive closure of FunctionIR hashes reachable from a
 * set of seed FunctionIRs.
 *
 * This is a pure IR walker — no Truffle types, no execution, no
 * runtime state. It lives in `:typechecker` (next to `PrecompiledStdlib`
 * whose dependency graph we walk) so `:cli` can call it without pulling
 * in `:interpreter`.
 *
 * Single source of truth used by `mez build`'s reachable-closure
 * computation: user code + every stdlib function transitively
 * referenced. The output is what goes into the user-app jar's
 * `embedded-app.cbor`.
 *
 * Originally lived in [com.mezze.interpreter.compiler.PipelineRunner];
 * moved here in #216 to break `:cli`'s transitive `:interpreter`
 * dependency. The old `PipelineRunner.collectReferencedHashes`
 * trampoline forwards here for any in-tree callers that hadn't been
 * migrated.
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

        val depGraph = PrecompiledStdlib.stdlibDependencyGraph
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
            is com.mezze.language.truffleir.CallNativeIR -> collectHashesFromNode(node.arg, queue)
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
}
