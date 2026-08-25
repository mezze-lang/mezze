package com.mezze.language.truffleir

import com.mezze.language.typedast.Hash

/**
 * Drop `let x = <pure> ...` bindings where `x` is never read in the
 * enclosing block. Sibling of [TrivialWrapperInliner] / [MutEraser];
 * runs after inlining so it can catch reads that only became dead
 * *because* of substitution.
 *
 * ## Status: OPT-IN (MEZZE_DEAD_LET_ELIMINATION=1)
 *
 * First integration broke ~94 tests, cluster around HTTP handler
 * tests and scoped closures — the conservative purity check
 * (see [isSideEffectFree]) misclassified some patterns. Concrete
 * failure: a `let rules: [MockRule] = [...]` binding used as a
 * handler config, but the record RHS somehow lost data by the time
 * the handler read it. Likely candidates for further investigation:
 *
 *   1. `readsSlot` might miss occurrences inside a node type it doesn't
 *      descend into, causing a genuinely-live binding to look dead.
 *   2. The order in which we count reads vs. drop lets across
 *      `StmtIR.Let` sequences might have off-by-one bugs when a later
 *      let depends on an earlier let we just dropped.
 *   3. Some effectful-looking allocations (records used as handler
 *      config, effect performer records) might participate in identity
 *      comparisons or other behaviours we shouldn't erase.
 *
 * Until #1–#3 are settled, keep the pass gated so mainline stays
 * green. Enable with `MEZZE_DEAD_LET_ELIMINATION=1` to iterate.
 *
 * Two-phase per BlockIR:
 *
 *   1. Descend into every child of every stmt/result first (bottom-up)
 *      so nested blocks are cleaned before we count reads at this level.
 *   2. Count `ReadLocalIR(slot)` occurrences across every remaining
 *      later stmt value and the result. If a `StmtIR.Let(slot, …, value)`
 *      has zero reads AND `isSideEffectFree(value)` returns true, drop it.
 *
 * Why only pure values? A `let _ = f {}` where `f` calls into a
 * `HandleIR` chain, performs an effect, mutates a shared record, or
 * updates an Atom, is observable at runtime even when the binding
 * itself is unused. Dropping it would change program behaviour. Only
 * expressions the runtime can safely never execute qualify.
 *
 * The pass never reorders lets, never renumbers slots, and never
 * touches captures — it only removes stmts. Downstream frame layout
 * logic still sees `localSlots` unchanged; a dropped slot just never
 * gets written or read.
 */
object DeadLetEliminator {

    /**
     * Rewrite every function body, dropping dead pure lets. Reference-
     * equal for unchanged bodies. Same contract as [TrivialWrapperInliner.inline]:
     * shape of the function map is preserved; only bodies change.
     */
    fun eliminate(functions: Map<Hash, FunctionIR>): Map<Hash, FunctionIR> {
        val out = HashMap<Hash, FunctionIR>(functions.size)
        var changed = 0
        var lets = 0
        for ((hash, fn) in functions) {
            val stats = ElimStats()
            val newBody = rewrite(fn.body, stats)
            if (newBody === fn.body) {
                out[hash] = fn
            } else {
                out[hash] = fn.copy(body = newBody)
                changed++
                lets += stats.letsDropped
            }
        }
        if (changed > 0 && System.getenv("MEZZE_DEBUG") != null) {
            System.err.println(
                "DeadLetEliminator: dropped $lets dead let(s) " +
                "across $changed/${functions.size} functions"
            )
        }
        return out
    }

    private class ElimStats {
        var letsDropped: Int = 0
    }

    /**
     * Structurally rewrite [node], applying the dead-let rule at every
     * `BlockIR` encountered. Bottom-up: inner blocks are cleaned first,
     * so an outer block sees only the surviving inner stmts.
     */
    private fun rewrite(node: NodeIR, stats: ElimStats): NodeIR {
        return when (node) {
            is BlockIR -> rewriteBlock(node, stats)
            else -> recurseChildren(node) { rewrite(it, stats) }
        }
    }

    private fun rewriteBlock(block: BlockIR, stats: ElimStats): NodeIR {
        // Bottom-up: recurse into every stmt value and the result first.
        var anyChildChanged = false
        val recursedStmts = block.stmts.map { s ->
            when (s) {
                is StmtIR.Let -> {
                    val nv = rewrite(s.value, stats)
                    if (nv !== s.value) { anyChildChanged = true; s.copy(value = nv) } else s
                }
                is StmtIR.Expr -> {
                    val nn = rewrite(s.node, stats)
                    if (nn !== s.node) { anyChildChanged = true; s.copy(node = nn) } else s
                }
            }
        }
        val recursedResult = rewrite(block.result, stats)
        val resultChanged = recursedResult !== block.result

        // Count local-slot reads across all stmt values AND the result.
        // We fill on the fly per candidate rather than one hashmap for
        // the whole block — most blocks have <5 lets and this stays
        // linear.
        val kept = ArrayList<StmtIR>(recursedStmts.size)
        var droppedHere = 0
        for ((idx, stmt) in recursedStmts.withIndex()) {
            if (stmt is StmtIR.Let && isSideEffectFree(stmt.value)) {
                var used = false
                // Later stmts.
                for (j in idx + 1 until recursedStmts.size) {
                    val other = recursedStmts[j]
                    val otherNode = when (other) {
                        is StmtIR.Let -> other.value
                        is StmtIR.Expr -> other.node
                    }
                    if (readsSlot(otherNode, stmt.slot)) { used = true; break }
                }
                if (!used && readsSlot(recursedResult, stmt.slot)) used = true
                if (!used) {
                    droppedHere++
                    continue  // drop this let
                }
            }
            kept.add(stmt)
        }
        stats.letsDropped += droppedHere

        if (droppedHere == 0 && !anyChildChanged && !resultChanged) return block
        // If every stmt got dropped and result is unchanged, collapse the
        // block entirely to just its result. Removes one BlockIR layer.
        if (kept.isEmpty()) return recursedResult
        return block.copy(stmts = kept, result = recursedResult)
    }

    /**
     * Predicate: does re-not-evaluating [node] preserve program
     * behaviour? True iff the node builds a value with no observable
     * side effect at execution — no calls, no effect ops, no writes,
     * no allocation with identity semantics (we accept records/variants
     * as pure since Mezze's `==` is structural).
     */
    private fun isSideEffectFree(node: NodeIR): Boolean {
        return when (node) {
            is LongLitIR, is DoubleLitIR, is BoolLitIR, is StringLitIR,
            is UnitLitIR, is CharLitIR, is FracLitIR,
            is ReadLocalIR, is ReadCaptureIR, is ReadArgIR,
            is ReadGlobalByHashIR, is ReadGlobalFnByHashIR,
            is EffectBindingIR -> true

            is MakeRecordIR -> node.fields.all { isSideEffectFree(it.value) }
            is MakeVariantIR -> node.payload?.let { isSideEffectFree(it) } ?: true
            is MakeSeqIR -> node.elements.all { e ->
                when (e) {
                    is SeqElementIR.Regular -> isSideEffectFree(e.node)
                    is SeqElementIR.Spread -> isSideEffectFree(e.node)
                }
            }
            is RecordUpdateIR ->
                isSideEffectFree(node.record) &&
                node.updates.all { isSideEffectFree(it.value) }
            is ReadFieldIR -> isSideEffectFree(node.record)
            is SeqGetIR -> isSideEffectFree(node.seq) && isSideEffectFree(node.index)
            is InlineBinaryOpIR ->
                isSideEffectFree(node.left) && isSideEffectFree(node.right)
            is InterpolatedStringIR -> node.parts.all { p ->
                when (p) {
                    is StringPartIR.Lit -> true
                    is StringPartIR.Expr -> isSideEffectFree(p.node)
                }
            }
            is IfIR ->
                isSideEffectFree(node.cond) &&
                isSideEffectFree(node.thenBr) &&
                isSideEffectFree(node.elseBr)
            is BlockIR ->
                node.stmts.all { s ->
                    when (s) {
                        // A pure inner let has no observable effect (we would
                        // drop it too in a recursive pass anyway).
                        is StmtIR.Let -> isSideEffectFree(s.value)
                        is StmtIR.Expr -> isSideEffectFree(s.node)
                    }
                } && isSideEffectFree(node.result)

            // Everything else may have side effects, potentially raise,
            // touch shared mutable state, or perform an effect.
            // Conservative bail:
            //   - CallDirect / CallNative / CallLocal / CallIndirect(Scalar)
            //   - PartialApplyDirect (allocates a closure; observable via identity)
            //   - PerformOp / HandleIR
            //   - MakeLambda (identity-comparable in Mezze via `==` on fns? conservatively no-touch)
            //   - SpecializedCall / DictMethodCall
            //   - Match (branches to arbitrary code)
            //   - WriteLocal (mutates)
            else -> false
        }
    }

    /**
     * Cheap DFS: does [node] contain any `ReadLocalIR(slot)` reference?
     * Used to decide if a pure let is truly unreferenced by anything
     * later in its block or its result.
     */
    private fun readsSlot(node: NodeIR, slot: Int): Boolean {
        if (node is ReadLocalIR && node.slot == slot) return true
        var found = false
        recurseChildren(node) { child ->
            if (!found && readsSlot(child, slot)) found = true
            child
        }
        return found
    }

    /**
     * Structural rebuild helper — same shape as
     * [TrivialWrapperInliner.recurseChildren]. Kept private here to
     * avoid making the traversal a public utility until we have more
     * than two consumers.
     */
    private fun recurseChildren(node: NodeIR, f: (NodeIR) -> NodeIR): NodeIR =
        when (node) {
            is LongLitIR, is DoubleLitIR, is BoolLitIR, is StringLitIR,
            is UnitLitIR, is CharLitIR, is FracLitIR,
            is ReadLocalIR, is ReadCaptureIR, is ReadArgIR,
            is ReadGlobalByHashIR, is ReadGlobalFnByHashIR,
            is EffectBindingIR -> node

            is InterpolatedStringIR -> {
                var anyChanged = false
                val newParts = node.parts.map { p ->
                    when (p) {
                        is StringPartIR.Lit -> p
                        is StringPartIR.Expr -> {
                            val nn = f(p.node)
                            if (nn !== p.node) { anyChanged = true; StringPartIR.Expr(nn) } else p
                        }
                    }
                }
                if (anyChanged) node.copy(parts = newParts) else node
            }

            is WriteLocalIR -> {
                val nv = f(node.value)
                if (nv !== node.value) node.copy(value = nv) else node
            }

            is CallDirectIR -> {
                val newArgs = mapIfChanged(node.args, f)
                if (newArgs !== node.args) node.copy(args = newArgs) else node
            }

            is PartialApplyDirectIR -> {
                val newFields = mapFieldsIfChanged(node.partialFields, f)
                if (newFields !== node.partialFields) node.copy(partialFields = newFields) else node
            }

            is CallLocalIR -> {
                val na = f(node.arg)
                if (na !== node.arg) node.copy(arg = na) else node
            }

            is CallNativeIR -> {
                val na = f(node.arg)
                var changed = na !== node.arg
                val newArgs = if (node.args.isNotEmpty()) {
                    node.args.map { child ->
                        val c = f(child)
                        if (c !== child) changed = true
                        c
                    }
                } else node.args
                if (changed) node.copy(arg = na, args = newArgs) else node
            }

            is CallIndirectIR -> {
                val nf = f(node.function); val na = f(node.arg)
                if (nf !== node.function || na !== node.arg)
                    node.copy(function = nf, arg = na) else node
            }

            is CallIndirectScalarIR -> {
                val nf = f(node.function)
                val newArgs = mapIfChanged(node.args, f)
                if (nf !== node.function || newArgs !== node.args)
                    node.copy(function = nf, args = newArgs) else node
            }

            is DictMethodCallIR -> {
                val na = f(node.arg)
                if (na !== node.arg) node.copy(arg = na) else node
            }

            is InlineBinaryOpIR -> {
                val nl = f(node.left); val nr = f(node.right)
                if (nl !== node.left || nr !== node.right)
                    node.copy(left = nl, right = nr) else node
            }

            is SpecializedCallIR -> {
                val newArgs = mapIfChanged(node.args, f)
                if (newArgs !== node.args) node.copy(args = newArgs) else node
            }

            is MakeRecordIR -> {
                val newFields = mapFieldsIfChanged(node.fields, f)
                if (newFields !== node.fields) node.copy(fields = newFields) else node
            }

            is ReadFieldIR -> {
                val nr = f(node.record)
                if (nr !== node.record) node.copy(record = nr) else node
            }

            is RecordUpdateIR -> {
                val nr = f(node.record)
                val newUpdates = mapFieldsIfChanged(node.updates, f)
                if (nr !== node.record || newUpdates !== node.updates)
                    node.copy(record = nr, updates = newUpdates) else node
            }

            is MakeVariantIR -> {
                val np = node.payload?.let(f)
                if (np !== node.payload) node.copy(payload = np) else node
            }

            is MatchIR -> {
                val ns = f(node.scrutinee)
                var anyChanged = ns !== node.scrutinee
                val newCases = node.cases.map { c ->
                    val nb = f(c.body)
                    val ng = c.guard?.let(f)
                    var ilbChanged = false
                    val newIlbs = c.ifLetBindings.map { ilb ->
                        val ne = f(ilb.expr)
                        if (ne !== ilb.expr) { ilbChanged = true; ilb.copy(expr = ne) } else ilb
                    }
                    if (nb !== c.body || ng !== c.guard || ilbChanged) {
                        anyChanged = true
                        c.copy(body = nb, guard = ng, ifLetBindings = newIlbs)
                    } else c
                }
                if (anyChanged) node.copy(scrutinee = ns, cases = newCases) else node
            }

            is IfIR -> {
                val nc = f(node.cond); val nt = f(node.thenBr); val ne = f(node.elseBr)
                if (nc !== node.cond || nt !== node.thenBr || ne !== node.elseBr)
                    node.copy(cond = nc, thenBr = nt, elseBr = ne) else node
            }

            is BlockIR -> {
                // Note: the outer rewriteBlock handles this; falling in
                // via recurseChildren means we're inside an expression
                // like `Match.body = BlockIR`. Structural pass only —
                // no dead-let elimination here (that requires the
                // dedicated two-phase walker in rewriteBlock).
                var stmtsChanged = false
                val newStmts = node.stmts.map { s ->
                    when (s) {
                        is StmtIR.Let -> {
                            val nv = f(s.value)
                            if (nv !== s.value) { stmtsChanged = true; s.copy(value = nv) } else s
                        }
                        is StmtIR.Expr -> {
                            val nn = f(s.node)
                            if (nn !== s.node) { stmtsChanged = true; s.copy(node = nn) } else s
                        }
                    }
                }
                val newResult = f(node.result)
                if (stmtsChanged || newResult !== node.result)
                    node.copy(stmts = newStmts, result = newResult) else node
            }

            is MakeLambdaIR -> node

            is HandleIR -> {
                val nb = f(node.body)
                if (nb !== node.body) node.copy(body = nb) else node
            }

            is PerformOpIR -> {
                val na = f(node.arg)
                if (na !== node.arg) node.copy(arg = na) else node
            }

            is MakeSeqIR -> {
                var anyChanged = false
                val newEls = node.elements.map { el ->
                    when (el) {
                        is SeqElementIR.Regular -> {
                            val nn = f(el.node)
                            if (nn !== el.node) { anyChanged = true; SeqElementIR.Regular(nn) } else el
                        }
                        is SeqElementIR.Spread -> {
                            val nn = f(el.node)
                            if (nn !== el.node) { anyChanged = true; SeqElementIR.Spread(nn) } else el
                        }
                    }
                }
                if (anyChanged) node.copy(elements = newEls) else node
            }

            is SeqGetIR -> {
                val ns = f(node.seq); val ni = f(node.index)
                if (ns !== node.seq || ni !== node.index)
                    node.copy(seq = ns, index = ni) else node
            }

            is FusedChainIR -> {
                // #446 — mirror of TrivialWrapperInliner.recurseChildren case.
                val newSource: SourceIR = when (val s = node.source) {
                    is SourceIR.RangeSeq -> {
                        val nStart = f(s.start); val nEnd = f(s.end)
                        if (nStart !== s.start || nEnd !== s.end)
                            SourceIR.RangeSeq(nStart, nEnd) else s
                    }
                    is SourceIR.RawSeq -> {
                        val nXs = f(s.xs)
                        if (nXs !== s.xs) SourceIR.RawSeq(nXs) else s
                    }
                    is SourceIR.Opaque -> {
                        val nE = f(s.expr)
                        if (nE !== s.expr) SourceIR.Opaque(nE) else s
                    }
                }
                val newSink: SinkIR = when (val sk = node.sink) {
                    SinkIR.Sum -> sk
                    is SinkIR.Fold -> {
                        val nInit = f(sk.init)
                        if (nInit !== sk.init) SinkIR.Fold(nInit, sk.f) else sk
                    }
                    is SinkIR.ForEach -> sk
                }
                if (newSource !== node.source || newSink !== node.sink)
                    node.copy(source = newSource, sink = newSink) else node
            }
        }

    private fun mapIfChanged(xs: List<NodeIR>, f: (NodeIR) -> NodeIR): List<NodeIR> {
        var changed = false
        val out = xs.map { x ->
            val n = f(x)
            if (n !== x) changed = true
            n
        }
        return if (changed) out else xs
    }

    private fun mapFieldsIfChanged(xs: List<FieldIR>, f: (NodeIR) -> NodeIR): List<FieldIR> {
        var changed = false
        val out = xs.map { fld ->
            val nv = f(fld.value)
            if (nv !== fld.value) { changed = true; fld.copy(value = nv) } else fld
        }
        return if (changed) out else xs
    }
}
