package com.mezze.language.truffleir

/**
 * CPS (continuation-passing style) lowering of effect-carrying IR.
 *
 * Phase 1 of Path C (docs/perf/bdsl-effects-path-c-2026-07.md). Transforms
 * every function that transitively performs an effect op into an explicit
 * (arg, k) -> ... form where k is the continuation. The Loom-based
 * suspend/resume mechanism becomes unnecessary once every effect boundary
 * is a plain lambda invocation.
 *
 * ## Current state (Phase 1.4)
 *
 * The transform is still identity — nothing about the runtime has changed
 * yet, so any rewrite would break execution. What Phase 1.4 adds is the
 * *walker infrastructure* the real transform needs:
 *
 *  - [countPerformSites]  — how many [PerformOpIR] nodes appear in a
 *    function's body, transitively?
 *  - [collectPerformedEffects] — which distinct effect hashes does the
 *    function perform?
 *  - [hasHandle] — is a [HandleIR] present anywhere in the body?
 *
 * Phase 1.5 will thread continuation parameters based on this analysis;
 * Phase 1.6 will rewrite [EffectBindingIR] reads to consume them.
 *
 * A trace is emitted when the `mezze.cps.trace` system property is on —
 * useful for confirming the walker sees what we expect during Phase 1.5
 * development. Off by default; adds zero cost otherwise.
 */
object CpsLowering {

    private val TRACE: Boolean = System.getProperty("mezze.cps.trace")?.let {
        it != "0" && !it.equals("false", ignoreCase = true)
    } ?: false

    /**
     * Rewrite [fn] into CPS form. Currently identity; Phase 1.5+ starts
     * threading continuations. The traversal is still run today so any
     * walker bug surfaces immediately (e.g. missing an IR case).
     */
    fun lower(fn: FunctionIR): FunctionIR {
        if (TRACE) {
            val performs = countPerformSites(fn.body)
            val effects = collectPerformedEffects(fn.body)
            val handles = hasHandle(fn.body)
            if (performs > 0 || handles) {
                System.err.println(
                    "[CPS] ${fn.qualifiedName}: performs=$performs, " +
                    "effects=${effects.size}, has_handle=$handles"
                )
            }
        }
        return fn
    }

    /**
     * Count the [PerformOpIR] nodes reachable from [node]. Nested
     * [HandleIR] bodies contribute to the count because the outer
     * function still needs a continuation shape even when the perform
     * is caught by a nested handler — the analysis is downstream of any
     * "handled locally" pruning.
     */
    fun countPerformSites(node: NodeIR): Int {
        var count = 0
        walk(node) { if (it is PerformOpIR) count++ }
        return count
    }

    /**
     * Collect the set of effect hashes reached by [PerformOpIR] nodes
     * inside [node]. Used by Phase 1.5 to decide whether a function
     * needs a continuation slot at all.
     */
    fun collectPerformedEffects(node: NodeIR): Set<com.mezze.language.typedast.Hash> {
        val effects = mutableSetOf<com.mezze.language.typedast.Hash>()
        walk(node) { if (it is PerformOpIR) effects.add(it.effectHash) }
        return effects
    }

    /** True when [node] contains at least one [HandleIR] site. */
    fun hasHandle(node: NodeIR): Boolean {
        var found = false
        walk(node) { if (it is HandleIR) found = true }
        return found
    }

    /**
     * Preorder walker over every [NodeIR] reachable from [root]. Visits
     * lambdas' bodies via [MakeLambdaIR] captures too? No — a
     * [MakeLambdaIR] only names the target function hash; the target's
     * body is a separate [FunctionIR] and gets its own [lower] call.
     * Captures are just value expressions, walked normally.
     */
    private fun walk(root: NodeIR, visit: (NodeIR) -> Unit) {
        val stack = ArrayDeque<NodeIR>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            visit(n)
            children(n).forEach { stack.add(it) }
        }
    }

    /** Direct children of [n]. Missing an IR case here silently under-
     *  counts, so keep this exhaustive as new IR nodes land. */
    private fun children(n: NodeIR): List<NodeIR> = when (n) {
        is LongLitIR, is DoubleLitIR, is BoolLitIR, is StringLitIR,
        is UnitLitIR, is CharLitIR, is FracLitIR,
        is ReadLocalIR, is ReadCaptureIR, is ReadArgIR,
        is ReadGlobalByHashIR, is ReadGlobalFnByHashIR,
        is EffectBindingIR -> emptyList()

        is InterpolatedStringIR -> n.parts.mapNotNull {
            (it as? StringPartIR.Expr)?.node
        }
        is WriteLocalIR -> listOf(n.value)
        is CallDirectIR -> n.args
        is PartialApplyDirectIR -> n.partialFields.map { it.value }
        is CallLocalIR -> listOf(n.arg)
        is CallNativeIR -> if (n.args.isNotEmpty()) n.args else listOf(n.arg)
        is CallIndirectIR -> listOf(n.function, n.arg)
        is CallIndirectScalarIR -> listOf(n.function) + n.args
        is MakeRecordIR -> n.fields.map { it.value }
        is ReadFieldIR -> listOf(n.record)
        is RecordUpdateIR -> listOf(n.record) + n.updates.map { it.value }
        is MakeVariantIR -> n.payload?.let { listOf(it) } ?: emptyList()
        is MakeSeqIR -> n.elements.map {
            when (it) {
                is SeqElementIR.Regular -> it.node
                is SeqElementIR.Spread -> it.node
            }
        }
        is SeqGetIR -> listOf(n.seq, n.index)
        is IfIR -> listOf(n.cond, n.thenBr, n.elseBr)
        is BlockIR -> n.stmts.map {
            when (it) {
                is StmtIR.Let -> it.value
                is StmtIR.Expr -> it.node
            }
        } + n.result
        is MakeLambdaIR -> emptyList()
        is HandleIR -> listOf(n.body) + n.handlers.mapNotNull { it.params }
        is PerformOpIR -> listOf(n.arg)
        is MatchIR -> listOf(n.scrutinee) + n.cases.flatMap {
            listOfNotNull(it.guard, it.body)
        }
        is DictMethodCallIR -> listOf(n.arg)
        is InlineBinaryOpIR -> listOf(n.left, n.right)
        else -> emptyList()
    }
}
