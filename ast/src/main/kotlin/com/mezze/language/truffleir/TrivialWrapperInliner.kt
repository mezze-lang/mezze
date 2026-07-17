package com.mezze.language.truffleir

import com.mezze.language.typedast.Hash

/**
 * IR-level β-reduction of "trivial" stdlib wrapper functions.
 *
 * Almost every stdlib primitive wrapper is a one-line forwarder:
 *
 *     pub let pow : { base: Float, exp: Float } -> Float =
 *         { base, exp } -> __native_float_pow { base = base, exp = exp }
 *
 * At the IR level this compiles to a [FunctionIR] whose body is a single
 * [CallNativeIR] (scalar-ABI) or a single [CallDirectIR] pointing at the
 * underlying primitive. The wrapper does literally nothing — but every
 * call site still pays a cross-CallTarget call into the wrapper's
 * [com.mezze.interpreter.nodes.function.MezzeFunctionRootNode], plus a
 * fresh `VirtualFrame` allocation, plus param-slot binding, all to bounce
 * straight back to the native.
 *
 * This pass inlines the wrapper's body directly at every call site,
 * substituting each `ReadLocalIR(paramSlot)` with the corresponding
 * positional argument expression. The result is that `core::pow {base=x,
 * exp=y}` lowers to `__native_float_pow {base=x, exp=y}` with no
 * intermediate function call — same speedup as if the JIT had inlined
 * the wrapper, but deterministic and applies from the very first call.
 *
 * Sibling of [MutEraser], which does the same thing for `Mut.mutate`
 * dispatch lambdas. Runs at stdlib precompile time so the inlined IR
 * is what lands in the snapshot.
 *
 * ## Eligibility
 *
 * A function is treated as a trivial wrapper when ALL of the following hold:
 *
 *  - **No captures** — it's a top-level let, not a closure. Eliminates the
 *    need to remap `ReadCaptureIR` reads.
 *  - **No locals** — the body doesn't `let`-bind anything. We don't need to
 *    grow the caller's frame.
 *  - **No params with side-effecting reads** — purely typed slot reads.
 *  - **Body shape is a single allowed call**:
 *      * `CallNativeIR(isScalarABI=true)` — the post-scalar-ABI shape.
 *      * `CallDirectIR(isScalarABI=true)` — forwards to another trivial
 *        wrapper. Resolved transitively at substitution time.
 *      * `CallNativeIR` with legacy record-ABI shape AND arg shape
 *        `MakeRecordIR(fields = [name_i = ReadLocalIR(captures.size + i) for i])`
 *        — still inlineable, we just rewrite the field exprs.
 *  - **Each param read count is known**, but multi-read is allowed at
 *    the call site iff the corresponding arg is cheap-to-duplicate
 *    (literals, slot reads, global reads). This unlocks stdlib helpers
 *    like `min { l, r }` and `sign { n }` when called with cheap args.
 *
 * Functions that take captures, have locals, perform effects, branch
 * with `Match`, use lambdas, or invoke a param through an indirect
 * call still stay as separate CallTargets.
 */
object TrivialWrapperInliner {

    /**
     * Inline all trivial-wrapper calls in every function. Returns a new
     * map: function bodies that contained an inlineable call get
     * replaced; others are returned unchanged (reference-equal).
     *
     * The function-hash → IR map is not changed in shape (no functions
     * are added or removed); only bodies are rewritten. This means
     * everything keyed by Hash (Truffle CallTarget cache, codec, the
     * stdlib snapshot, etc.) continues to work without touching
     * downstream consumers.
     *
     * Important: the wrappers themselves are NOT deleted. They still
     * exist as separate CallTargets. They just never get called when
     * the call site is statically known (e.g. `core::pow {...}`),
     * because the call has been replaced by the wrapper's body
     * in-place. Indirect calls (a wrapper passed as a higher-order
     * argument) still hit the CallTarget.
     */
    fun inline(
        functions: Map<Hash, FunctionIR>,
        externalWrappers: Map<Hash, FunctionIR> = emptyMap(),
    ): Map<Hash, FunctionIR> {
        // Pre-compute the trivial-wrapper table once: hash → wrapper IR.
        // Then walk each body, substituting eligible calls.
        val wrappers = HashMap<Hash, FunctionIR>()
        for ((hash, fn) in functions) {
            if (isTrivialWrapper(fn)) wrappers[hash] = fn
        }
        // External wrappers (e.g. stdlib wrappers visible from user code)
        // are inlined into the body rewrites but their own definitions
        // are NOT carried into the output map.
        for ((hash, fn) in externalWrappers) {
            if (hash !in wrappers && isTrivialWrapper(fn)) wrappers[hash] = fn
        }

        if (wrappers.isEmpty()) return functions

        val out = HashMap<Hash, FunctionIR>(functions.size)
        var changed = 0
        for ((hash, fn) in functions) {
            // Don't recursively inline a wrapper's body into itself —
            // the wrapper IS the trivial body. Other functions are fair
            // game (they may or may not call into wrappers).
            val newBody = if (wrappers.containsKey(hash) && hash in functions) {
                fn.body
            } else {
                rewrite(fn.body, wrappers)
            }
            if (newBody === fn.body) {
                out[hash] = fn
            } else {
                out[hash] = fn.copy(body = newBody)
                changed++
            }
        }
        if (changed > 0 && System.getenv("MEZZE_DEBUG") != null) {
            System.err.println(
                "TrivialWrapperInliner: rewrote $changed of ${functions.size} functions " +
                "using ${wrappers.size} known wrappers"
            )
        }
        return out
    }

    /**
     * True when the function's body is a one-shot forwarder to either a
     * native or another wrapper, and each param is read at most once.
     *
     * The frame layout `[captures..., params..., locals...]` is implicit
     * in the FunctionIR shape: param slot `i` lives at frame index
     * `captureSlots.size + i`, which is what `ReadLocalIR` indices into
     * inside the body.
     */
    private fun isTrivialWrapper(fn: FunctionIR): Boolean {
        if (fn.captureSlots.isNotEmpty()) return false
        if (fn.localSlots.isNotEmpty()) return false
        if (fn.paramSlots.isEmpty()) {
            // Zero-param wrappers (e.g. `seq::empty {} -> __native_seq_empty {}`)
            // are still worth inlining; the savings is just one frame
            // alloc per call.
            // Fall through.
        }

        val paramStart = fn.captureSlots.size  // == 0 since captures is empty
        val paramEnd = paramStart + fn.paramSlots.size

        // Body must be a pure expression tree whose leaves reference each
        // param a known number of times and read no other slots. Accepted
        // top-level shapes are single calls or expression-form control
        // flow (`If`, `Block` without lets) that ultimately forwards to
        // calls. The eligibility check now records per-param read counts
        // instead of hard-rejecting on >1 reads; the substitution site
        // (see [rewrite]) decides whether the caller's arg is cheap
        // enough to duplicate.
        val body = fn.body
        val shapeOk = when (body) {
            is CallNativeIR -> true
            is CallDirectIR -> body.isScalarABI
            is IfIR -> true
            is BlockIR -> body.stmts.all { it is StmtIR.Expr }
            else -> false
        }
        if (!shapeOk) return false
        // Traverse the body to make sure no capture/local slots are
        // read (only params, in-range). We discard the counts here;
        // the same walk runs again at the call site to compute them.
        val counts = IntArray(paramEnd - paramStart)
        if (!countParamReads(body, paramStart, paramEnd, counts)) return false
        // Recursion guard: a wrapper whose body calls itself would
        // unfold forever at compile time. Once Phase B relaxed the
        // "≤1 read" rule, `If`-shaped self-recursive functions like
        // `gcd { a, b } -> if b == 0 then a else gcd { a = b, b = a % b }`
        // pass the earlier gates — reject them here.
        return !containsSelfCall(body, fn.hash)
    }

    /**
     * True when [node] contains a direct call to [selfHash] anywhere
     * in its tree. Used by [isTrivialWrapper] to reject self-recursive
     * candidate wrappers.
     *
     * Only checks direct `CallDirectIR` targets — indirect calls
     * cannot go through a specific hash statically, and the mutual-
     * recursion case (A calls B calls A) is out of scope for a single-
     * pass inliner.
     */
    private fun containsSelfCall(node: NodeIR, selfHash: Hash): Boolean {
        if (node is CallDirectIR && node.targetHash == selfHash) return true
        var found = false
        recurseChildren(node) { child ->
            if (!found && containsSelfCall(child, selfHash)) found = true
            child
        }
        return found
    }

    /**
     * Compute the per-param read counts for a body known to pass
     * [isTrivialWrapper]. Returns null iff the body has a shape that
     * would have failed eligibility (defensive — callers already gate).
     */
    private fun readCountsFor(
        body: NodeIR,
        paramStart: Int,
        paramEnd: Int,
    ): IntArray? {
        val counts = IntArray(paramEnd - paramStart)
        return if (countParamReads(body, paramStart, paramEnd, counts)) counts else null
    }

    /**
     * True when [node] is safe to duplicate — i.e. re-evaluating it on
     * every read produces the same value and causes no side effect. Only
     * these leaves are safe:
     *   - Literals (long/double/bool/string/unit/char/frac).
     *   - Slot reads (local, capture) — Mezze frame slots aren't racy
     *     within a single fiber, so two reads observe the same value.
     *   - Global by-hash reads — content-addressed, immutable.
     *   - EffectBindingIR — resolved by the handler chain; reading it
     *     twice is idempotent (returns the same handler snapshot).
     *
     * NOT safe: any call (`CallDirectIR`/`CallNativeIR`/…), any effect
     * op, any allocation (`MakeRecordIR`, `MakeSeqIR`, …), any binary op
     * whose operands aren't themselves cheap. Bail conservatively.
     */
    private fun isCheapToDuplicate(node: NodeIR): Boolean {
        return when (node) {
            is LongLitIR, is DoubleLitIR, is BoolLitIR, is StringLitIR,
            is UnitLitIR, is CharLitIR, is FracLitIR,
            is ReadLocalIR, is ReadCaptureIR,
            is ReadGlobalByHashIR, is ReadGlobalFnByHashIR,
            is EffectBindingIR -> true
            else -> false
        }
    }

    /**
     * Recursively count `ReadLocalIR(slot=paramStart..<paramEnd)`
     * occurrences in [node]. Returns false on any node shape that
     * indicates non-trivial control flow (match, perform, lambda,
     * indirect call) — those make inlining unsafe at this level.
     *
     * Returning false bails the whole eligibility check. Returning true
     * with counts intact means the body is a flat substitution problem.
     */
    private fun countParamReads(
        node: NodeIR,
        paramStart: Int,
        paramEnd: Int,
        counts: IntArray,
    ): Boolean {
        return when (node) {
            is ReadLocalIR -> {
                if (node.slot in paramStart until paramEnd) {
                    counts[node.slot - paramStart]++
                    true
                } else {
                    // Param-out-of-range read — would mean the wrapper
                    // accesses a slot that isn't a param, contradicting
                    // the "no captures, no locals" check.
                    false
                }
            }
            is ReadCaptureIR, is ReadArgIR -> false  // shouldn't appear; bail
            is LongLitIR, is DoubleLitIR, is BoolLitIR, is StringLitIR,
            is UnitLitIR, is CharLitIR, is FracLitIR,
            is ReadGlobalByHashIR, is ReadGlobalFnByHashIR,
            is EffectBindingIR -> true
            is CallNativeIR -> {
                if (!countParamReads(node.arg, paramStart, paramEnd, counts)) return false
                for (a in node.args) {
                    if (!countParamReads(a, paramStart, paramEnd, counts)) return false
                }
                true
            }
            is CallDirectIR -> {
                for (a in node.args) {
                    if (!countParamReads(a, paramStart, paramEnd, counts)) return false
                }
                true
            }
            is MakeRecordIR -> {
                for (f in node.fields) {
                    if (!countParamReads(f.value, paramStart, paramEnd, counts)) return false
                }
                true
            }
            is InlineBinaryOpIR -> {
                countParamReads(node.left, paramStart, paramEnd, counts) &&
                countParamReads(node.right, paramStart, paramEnd, counts)
            }
            // If: descend into all three arms. A param used in both
            // then+else counts twice here even though only one arm
            // runs — that's fine now that the call site checks
            // arg-cheapness before duplicating (per-path counting
            // would be more precise but adds complexity for little gain).
            is IfIR -> {
                countParamReads(node.cond, paramStart, paramEnd, counts) &&
                countParamReads(node.thenBr, paramStart, paramEnd, counts) &&
                countParamReads(node.elseBr, paramStart, paramEnd, counts)
            }
            // Block: only pure `Expr` statements are inlineable; a `Let`
            // in the body would introduce a new local slot whose index
            // belongs to the wrapper's frame layout and would collide on
            // substitution. Descend into each expr and the final result.
            is BlockIR -> {
                for (s in node.stmts) {
                    if (s !is StmtIR.Expr) return false
                    if (!countParamReads(s.node, paramStart, paramEnd, counts)) return false
                }
                countParamReads(node.result, paramStart, paramEnd, counts)
            }
            // Everything else (Lambda, Match, Perform, Handle, …) makes
            // the body non-trivial. Conservative bail.
            else -> false
        }
    }

    /**
     * Rewrite [node] by inlining any trivial-wrapper calls found
     * inside it. Walks the whole tree (not just the top level) so calls
     * nested inside another non-trivial body still get the speedup.
     */
    private fun rewrite(node: NodeIR, wrappers: Map<Hash, FunctionIR>): NodeIR {
        // Top-level case: this node is itself a wrappable call.
        if (node is CallDirectIR && node.isScalarABI) {
            val wrapper = wrappers[node.targetHash]
            if (wrapper != null && wrapper.paramSlots.size == node.args.size) {
                // Recursively rewrite the args first (the caller's
                // arguments may themselves contain wrappable calls), so
                // we don't have to re-walk after substitution.
                val rewrittenArgs = node.args.map { rewrite(it, wrappers) }
                // Multi-read gate: any param the wrapper reads more than
                // once must have a cheap-to-duplicate arg here, because
                // substitution will inline that arg AST at every read
                // site. If any multi-read param sees a heavy arg, bail
                // on inlining THIS call site — leave the CallDirectIR
                // in place so the callee's CallTarget still handles it.
                // Other call sites of the same wrapper may still inline.
                val paramStart = wrapper.captureSlots.size
                val paramEnd = paramStart + wrapper.paramSlots.size
                val counts = readCountsFor(wrapper.body, paramStart, paramEnd)
                if (counts != null) {
                    var ok = true
                    for (i in counts.indices) {
                        if (counts[i] > 1 && !isCheapToDuplicate(rewrittenArgs[i])) {
                            ok = false; break
                        }
                    }
                    if (ok) {
                        val substituted = substituteParams(
                            wrapper.body, rewrittenArgs, wrapper.captureSlots.size)
                        // Recurse in case the wrapper body referenced
                        // another wrapper (chain of trivial forwarders).
                        return rewrite(substituted, wrappers)
                    }
                }
                // Fall through: leave the call alone, but keep the args
                // rewritten (nested inlining still applies).
                if (rewrittenArgs !== node.args) {
                    return node.copy(args = rewrittenArgs)
                }
                return node
            }
        }
        return recurseChildren(node) { rewrite(it, wrappers) }
    }

    /**
     * Substitute each `ReadLocalIR(slot=paramStart+i)` in [body] with
     * `args[i]`. For multi-read params, [rewrite] has already checked
     * that `args[i]` is cheap-to-duplicate (see [isCheapToDuplicate]),
     * so sharing the arg expression AST at multiple read sites is safe
     * — every reference re-evaluates to the same value with no side
     * effects.
     */
    private fun substituteParams(
        body: NodeIR,
        args: List<NodeIR>,
        paramStart: Int,
    ): NodeIR {
        if (args.isEmpty()) return body
        val paramEnd = paramStart + args.size
        return substituteParamsRec(body, args, paramStart, paramEnd)
    }

    private fun substituteParamsRec(
        node: NodeIR,
        args: List<NodeIR>,
        paramStart: Int,
        paramEnd: Int,
    ): NodeIR {
        return when (node) {
            is ReadLocalIR -> {
                if (node.slot in paramStart until paramEnd) {
                    args[node.slot - paramStart]
                } else {
                    node
                }
            }
            else -> recurseChildren(node) { substituteParamsRec(it, args, paramStart, paramEnd) }
        }
    }

    /**
     * Generic structural recursion that rebuilds a [NodeIR] from its
     * children, applying [f] to each child. If no child changed, the
     * original node is returned to preserve reference equality. Mirrors
     * [MutEraser.recurseChildren] verbatim — both passes share the same
     * traversal shape.
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

            is MakeLambdaIR -> node  // hash + captures are values; the
                                     // lambda's body lives in another
                                     // FunctionIR that the outer pass
                                     // walked.

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
