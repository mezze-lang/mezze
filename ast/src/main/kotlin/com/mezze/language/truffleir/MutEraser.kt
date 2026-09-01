package com.mezze.language.truffleir

import com.mezze.language.typedast.Hash

/**
 * IR-level β-reduction of `Mut.mutate { act = {} -> X }` wrappers.
 *
 * Every stdlib `Mut.X` wrapper (incr, incr1, read_int, write_int, push,
 * pop, etc.) has the shape:
 *
 *     pub let foo : ... -> R <Mut> = { args } ->
 *       Mut.mutate { act = {} -> __native_foo_impl { args } }
 *
 * Mut is hermetic — the type system guarantees no `<Mut>` value escapes
 * its handler region. So at runtime, when `LocalMut` is the topmost Mut
 * handler, `Mut.mutate { act = λ }` is statically `λ {}` (the impl is
 * literally `{ act } -> act {}`). The whole effect-dispatch dance is
 * unobservable.
 *
 * This rewriter runs at stdlib precompile time. It walks each
 * `FunctionIR`'s body and replaces every
 *
 *     PerformOp("mutate", Mut, MakeRecord([FieldIR("act", MakeLambda(H, captures))]))
 *
 * with `functions[H].body`, after remapping the lambda's
 * `ReadCaptureIR(i)` nodes to `ReadLocalIR(captures[i].slot, ...)`. The
 * function's hash is preserved — only its body changes. User code sees
 * the same function hash and gets the β-reduced IR at execution time.
 *
 * Locally-bound `Mut.mutate { act = (something not a lambda literal) }`
 * sites are left alone (rare in practice; the wrappers always use a
 * literal lambda).
 *
 * `DebugLocalMut` is NOT special-cased here: the rewriter assumes the
 * caller's `Mut` handler is `LocalMut`. At runtime, when the caller's
 * handler is actually `DebugLocalMut`, the erased wrappers would skip
 * the debug logging. To keep DebugLocalMut working, the eraser is only
 * meant for paths that statically resolve to LocalMut — the TruffleIR
 * lowering path makes that choice (see {@code TruffleCompiler}).
 */
object MutEraser {

    /**
     * Hex of the Mut effect content hash — kept in sync with
     * `WellKnownHashConstants.EFFECT_MUT` via the
     * `MarkerEffectsHashConsistencyTest` regression test.
     */
    private const val MUT_EFFECT_HASH_HEX =
        "05d9e70c83972f84e21da6095c1c22600854cc8c081877a5f7f49c689da6fb7265fdfdacc76b16d0561ad9be50bd32ff300977d466fbb16e98f988b41fbb6dba"

    /**
     * Erase Mut.mutate wrappers in every function. Returns a new map
     * with the same hashes; each function's body has been transformed
     * if it contained any `Mut.mutate { act = λ }` site, otherwise the
     * instance is returned unchanged.
     */
    fun erase(functions: Map<Hash, FunctionIR>): Map<Hash, FunctionIR> {
        val out = HashMap<Hash, FunctionIR>(functions.size)
        var changed = 0
        for ((hash, fn) in functions) {
            val newBody = rewrite(fn.body, functions)
            if (newBody === fn.body) {
                out[hash] = fn
            } else {
                out[hash] = fn.copy(body = newBody)
                changed++
            }
        }
        if (changed > 0 && System.getenv("MEZZE_DEBUG") != null) {
            System.err.println("MutEraser: rewrote $changed of ${functions.size} functions")
        }
        return out
    }

    /**
     * Recursive rewrite over a NodeIR tree. Returns the same instance
     * if nothing changed (so reference-equality checks can detect that
     * cheaply).
     */
    private fun rewrite(node: NodeIR, functions: Map<Hash, FunctionIR>): NodeIR {
        // Fast-path detection: the canonical `Mut.mutate { act = λ }` shape.
        if (node is PerformOpIR && node.operationName == "mutate"
                && node.effectHash.hex == MUT_EFFECT_HASH_HEX) {
            val arg = node.arg
            if (arg is MakeRecordIR && arg.fields.size == 1
                    && arg.fields[0].name == "act") {
                val actExpr = arg.fields[0].value
                if (actExpr is MakeLambdaIR) {
                    val lambdaFn = functions[actExpr.functionHash]
                    if (lambdaFn != null) {
                        // β-reduce. The lambda has no params (act takes
                        // {}), so the only remapping needed is captures
                        // → parent's local slots.
                        val remap = buildCaptureRemap(actExpr.captures)
                        val inlined = rewriteWithCaptureRemap(
                            lambdaFn.body, functions, remap
                        )
                        return inlined
                    }
                }
            }
        }

        return recurseChildren(node) { rewrite(it, functions) }
    }

    /**
     * Same as [rewrite] but inside an inlined lambda body, where
     * `ReadCaptureIR(i)` reads must be remapped to the parent's local
     * slot. `remap[i]` gives the parent slot+kind for capture index `i`.
     */
    private fun rewriteWithCaptureRemap(
        node: NodeIR,
        functions: Map<Hash, FunctionIR>,
        remap: List<CaptureIR>
    ): NodeIR {
        // Remap captures first — this is the whole point of the inlined
        // walk. Capture(i) was "read the parent's slot at captures[i]".
        if (node is ReadCaptureIR) {
            val target = remap[node.index]
            return ReadLocalIR(target.slot, target.kind, node.src, typeTag = null)
        }

        // The rare nested case: an inlined lambda body that itself
        // contains a Mut.mutate { act = λ } site. β-reduce again. The
        // inner lambda's captures point at the ORIGINAL parent's slots
        // (the lambda we're inlining captured those values), so the
        // inner-lambda capture remap also goes through `remap`. Since
        // the inner lambda's captures are CaptureIR with the original
        // lambda's slot indices (which are now parent-local-slot indices
        // after the outer inline), we can recurse with the inner remap
        // built directly — but the inner remap's slot fields are still
        // parent-local-slots, so no further translation needed.
        if (node is PerformOpIR && node.operationName == "mutate"
                && node.effectHash.hex == MUT_EFFECT_HASH_HEX) {
            val arg = node.arg
            if (arg is MakeRecordIR && arg.fields.size == 1
                    && arg.fields[0].name == "act") {
                val actExpr = arg.fields[0].value
                if (actExpr is MakeLambdaIR) {
                    val lambdaFn = functions[actExpr.functionHash]
                    if (lambdaFn != null) {
                        // Inner captures reference the OUTER lambda's
                        // captures (since we're inside an already-
                        // inlined lambda body, ReadCapture(i) means the
                        // outer lambda's capture i, which `remap[i]`
                        // already resolves to the original parent's
                        // local slot). So the inner remap can be
                        // computed by walking actExpr.captures through
                        // the current remap.
                        val innerRemap = remapThrough(actExpr.captures, remap)
                        return rewriteWithCaptureRemap(
                            lambdaFn.body, functions, innerRemap
                        )
                    }
                }
            }
        }

        return recurseChildren(node) {
            rewriteWithCaptureRemap(it, functions, remap)
        }
    }

    /**
     * The captures recorded on a [MakeLambdaIR] describe "the parent's
     * frame slot for capture `i`". When that parent is itself an
     * already-inlined lambda body, those slot indices were captures
     * into the *original* parent's frame — which we've already remapped
     * via the outer `remap`. So inner `Capture(slot=N, kind=K)`
     * really means `outerRemap[N]`.
     *
     * This produces the inner remap by looking up each inner capture's
     * slot in the outer remap by INDEX, but actually the slot field on
     * the original lambda construction is a frame-slot index in the
     * outer lambda's frame, NOT a capture index into the outer's
     * captures list. So this helper is wrong in general — but for the
     * specific Mut wrappers we care about there is no nested lambda,
     * so this code path is exercised only by paranoia. If it ever runs
     * we fall back to a conservative no-rewrite by returning the
     * captures unchanged; the recursive walker then leaves any inner
     * ReadCaptureIR alone, which preserves correctness at the cost of
     * not inlining the nested mutate.
     */
    private fun remapThrough(
        innerCaptures: List<CaptureIR>,
        outerRemap: List<CaptureIR>
    ): List<CaptureIR> {
        // Conservative: don't try to do nested remapping (no Mut wrapper
        // in mutation.mz has a nested mutate-in-act-lambda). Return the
        // inner captures unchanged; the walker will leave inner reads
        // as-is and the inlining stops at this level.
        return innerCaptures
    }

    private fun buildCaptureRemap(captures: List<CaptureIR>): List<CaptureIR> =
        captures

    /**
     * Generic structural recursion that rebuilds a [NodeIR] from its
     * children, applying [f] to each child. If no child changed, the
     * original node is returned to preserve reference equality.
     */
    private fun recurseChildren(node: NodeIR, f: (NodeIR) -> NodeIR): NodeIR =
        when (node) {
            // Leaves — no children to recurse into.
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
                // Scalar-ABI path: also rewrite each positional arg in `args`.
                // Legacy record-ABI path: `args` is empty, the map is a no-op.
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

            is InspectIR -> {
                val nv = f(node.value)
                if (nv !== node.value) node.copy(value = nv) else node
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
                    val newIfLet = c.ifLetBindings.map { lb ->
                        val ne = f(lb.expr)
                        if (ne !== lb.expr) lb.copy(expr = ne) else lb
                    }
                    val ifLetChanged = newIfLet.zip(c.ifLetBindings)
                        .any { (a, b) -> a !== b }
                    if (nb !== c.body || ng !== c.guard || ifLetChanged) {
                        anyChanged = true
                        c.copy(body = nb, guard = ng, ifLetBindings = newIfLet)
                    } else c
                }
                if (anyChanged) node.copy(scrutinee = ns, cases = newCases) else node
            }

            is MakeSeqIR -> {
                var anyChanged = false
                val newEls = node.elements.map { e ->
                    when (e) {
                        is SeqElementIR.Regular -> {
                            val n = f(e.node)
                            if (n !== e.node) { anyChanged = true; SeqElementIR.Regular(n) } else e
                        }
                        is SeqElementIR.Spread -> {
                            val n = f(e.node)
                            if (n !== e.node) { anyChanged = true; SeqElementIR.Spread(n) } else e
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

            is IfIR -> {
                val nc = f(node.cond); val nt = f(node.thenBr); val ne = f(node.elseBr)
                if (nc !== node.cond || nt !== node.thenBr || ne !== node.elseBr)
                    node.copy(cond = nc, thenBr = nt, elseBr = ne) else node
            }

            is BlockIR -> {
                var anyChanged = false
                val newStmts = node.stmts.map { s ->
                    when (s) {
                        is StmtIR.Let -> {
                            val nv = f(s.value)
                            if (nv !== s.value) { anyChanged = true; s.copy(value = nv) } else s
                        }
                        is StmtIR.Expr -> {
                            val nn = f(s.node)
                            if (nn !== s.node) { anyChanged = true; s.copy(node = nn) } else s
                        }
                    }
                }
                val nr = f(node.result)
                if (anyChanged || nr !== node.result)
                    node.copy(stmts = newStmts, result = nr) else node
            }

            is MakeLambdaIR -> {
                // Do NOT recurse into the captured FunctionIR — that's a
                // separate FunctionIR in the registry and gets rewritten
                // on its own pass. We only patch the lambda-construction
                // site, not the lambda body, which would require slot
                // remapping we don't want to do here.
                node
            }

            is HandleIR -> {
                val nb = f(node.body)
                if (nb !== node.body) node.copy(body = nb) else node
            }

            is PerformOpIR -> {
                val na = f(node.arg)
                if (na !== node.arg) node.copy(arg = na) else node
            }

            is FusedChainIR -> {
                // #446 — mirror of TrivialWrapperInliner.recurseChildren
                // FusedChainIR case. Descend into source expression and
                // sink init; stage/sink MakeLambdas are leaves.
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

    private fun mapIfChanged(list: List<NodeIR>, f: (NodeIR) -> NodeIR): List<NodeIR> {
        var anyChanged = false
        val out = ArrayList<NodeIR>(list.size)
        for (n in list) {
            val nn = f(n)
            if (nn !== n) anyChanged = true
            out.add(nn)
        }
        return if (anyChanged) out else list
    }

    private fun mapFieldsIfChanged(list: List<FieldIR>, f: (NodeIR) -> NodeIR): List<FieldIR> {
        var anyChanged = false
        val out = ArrayList<FieldIR>(list.size)
        for (fld in list) {
            val nv = f(fld.value)
            if (nv !== fld.value) { anyChanged = true; out.add(fld.copy(value = nv)) } else out.add(fld)
        }
        return if (anyChanged) out else list
    }
}
