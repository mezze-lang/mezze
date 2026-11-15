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
        /**
         * Functions that must SURVIVE inlining even though they look trivial.
         *
         * `std::comptime::comptime` is `{ eval } -> eval {}` — a textbook
         * trivial wrapper — but a later pass has to still find the call in order
         * to replace it with the value it computes. Inlined away, the call
         * becomes an ordinary `thunk {}` and compile-time evaluation silently
         * stops happening: every program still produces the right answer, so
         * nothing fails, it just quietly costs run time.
         *
         * Passed in rather than recognised here because the hash is generated
         * into `:typechecker`, which `:ast` cannot see.
         */
        doNotInline: Set<Hash> = emptySet(),
    ): Map<Hash, FunctionIR> {
        // Pre-compute the trivial-wrapper table once: hash → wrapper IR.
        // Then walk each body, substituting eligible calls.
        val wrappers = HashMap<Hash, FunctionIR>()
        for ((hash, fn) in functions) {
            if (hash !in doNotInline && isTrivialWrapper(fn)) wrappers[hash] = fn
        }
        // External wrappers (e.g. stdlib wrappers visible from user code)
        // are inlined into the body rewrites but their own definitions
        // are NOT carried into the output map.
        for ((hash, fn) in externalWrappers) {
            if (hash !in wrappers && hash !in doNotInline && isTrivialWrapper(fn)) wrappers[hash] = fn
        }

        // NO WRAPPERS STILL MEANS WORK TO DO. Collapsing a record-form native
        // into an inline op is NORMALISATION, not a side effect of inlining, so
        // it cannot be gated on having something to inline.
        //
        // MEASURED CAVEAT, 2026-11-03: this cannot fire under `mez build`
        // today, because the NativeSignatures registry is EMPTY there — the
        // interpreter module is `compileOnly` in the CLI, so nothing registers
        // `__native_int_add`. Probed directly:
        //     [inliner] inline() entered, 3913 functions; registrySize=0 int_add=NULL
        // So every `signatureOf(name) ?: return node` bails and no native
        // collapses at compile time. Interpreter tests LOAD the registry and
        // therefore cannot see this. The collapse stays because it is correct
        // and becomes live the moment the registry is populated in the compile
        // path; until then the RUNTIME side (BytecodeCompiler.fltlExtractOp)
        // has to recognise the record form, which is where the fix landed.
        //
        // MEASURED: `tailonly`'s `sum_tr` has no trivial wrapper to inline —
        // the stdlib `add` body was already inlined at precompile time — so
        // this early return skipped every body and `acc + n` kept its
        // MakeRecordIR operands. That cost 48x (12 vs 0.25 ns/iter): the record
        // allocates ~100 bytes per iteration AND makes FusedLongTailLoop refuse
        // the function, because the fused tier needs positional operands.
        if (wrappers.isEmpty()) return collapseOnly(functions)

        // #432 — iterate to fixpoint. A single pass only handles direct
        // A→B wrapping. Chains like `sum_to → fold_method_impl → fold_module`
        // need one iteration per link in the chain. Bound at MAX_INLINE_PASSES
        // to keep pathological deep wrappers from causing quadratic blowup;
        // in practice chains are 2-3 deep and settle in 2-3 iterations.
        val stats = InlineStats()
        var current: Map<Hash, FunctionIR> = functions
        var passes = 0
        var changedThisPass = -1
        while (changedThisPass != 0 && passes < MAX_INLINE_PASSES) {
            passes++
            changedThisPass = 0
            val out = HashMap<Hash, FunctionIR>(current.size)
            for ((hash, fn) in current) {
                // Every body is rewritten, a wrapper's included — only a call
                // to the function ITSELF is left alone. Skipping wrappers'
                // bodies wholesale left a wrapper-to-wrapper chain unflattened
                // wherever the outer wrapper is used as a VALUE rather than
                // called: a mono-specialised folder `{acc, x} -> add(acc, x)`
                // passed to a fold kept its call to the Int `add` impl, so the
                // fold's body never reduced to an inline op and never fused.
                val newBody = rewrite(fn.body, wrappers, stats, hash)
                if (newBody === fn.body) {
                    out[hash] = fn
                } else {
                    out[hash] = fn.copy(body = newBody)
                    stats.fnsChanged++
                    changedThisPass++
                }
            }
            current = out
        }
        if (stats.fnsChanged > 0 && System.getenv("MEZZE_DEBUG") != null) {
            System.err.println(
                "TrivialWrapperInliner: ${stats.sitesInlined} call sites inlined " +
                "(${stats.sitesSkippedHeavyArg} skipped: heavy multi-read arg) " +
                "across ${stats.fnsChanged}/${functions.size} functions " +
                "using ${wrappers.size} known wrappers " +
                "in $passes pass(es)"
            )
        }
        return current
    }

    /** Bound on fixpoint iteration count. Chains are typically 2-3 deep
     *  (e.g. `sum_to → fold_method → fold_module → fold_go`); MAX=8 is
     *  ample and prevents runaway if the wrapper set has cycles despite
     *  the self-call guard. */
    private const val MAX_INLINE_PASSES: Int = 8

    /** Per-run counters — mutable, threaded through [rewrite] to make the
     *  MEZZE_DEBUG line report actual work done rather than just "how many
     *  functions had at least one rewrite". */
    private class InlineStats {
        var fnsChanged: Int = 0
        var sitesInlined: Int = 0
        var sitesSkippedHeavyArg: Int = 0
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
    private fun isTrivialWrapper(fn: FunctionIR): Boolean = wrapperReason(fn) == null

    /**
     * Why [fn] is NOT a trivial wrapper, or null when it is.
     *
     * Split out so the decision is observable: a function that fails here is
     * never inlined, and nothing anywhere says so. That matters because a
     * chain of un-inlined trivial forwarders is invisible to
     * `ChainCanonicalizer` — it looks for the underlying natives and finds
     * impl-op calls instead, so it silently declines and the whole pipeline
     * runs unfused. `MEZZE_TRACE_WRAPPER=1` prints these.
     */
    private fun wrapperReason(fn: FunctionIR): String? {
        val why: String? = run {
            if (fn.captureSlots.isNotEmpty()) return@run "has-captures"
            if (fn.localSlots.isNotEmpty()) return@run "has-locals"
            val paramStart = fn.captureSlots.size
            val paramEnd = paramStart + fn.paramSlots.size
            val body = fn.body
            if (body is ReadCaptureIR || body is ReadArgIR) return@run "body-is-bare-read"
            val counts = IntArray(paramEnd - paramStart)
            if (!countParamReads(body, paramStart, paramEnd, counts)) return@run "param-read-count-failed"
            if (body is CallDirectIR && !body.isScalarABI) return@run "body-call-not-scalar-abi"
            if (containsSelfCall(body, fn.hash)) return@run "self-call"
            val sz = sizeOf(body)
            if (sz > INLINE_SIZE_BUDGET) return@run "too-big($sz>$INLINE_SIZE_BUDGET)"
            null
        }
        if (why != null && System.getenv("MEZZE_TRACE_WRAPPER") != null) {
            System.err.println("[wrapper] declined ${fn.name} hash=${fn.hash.hex.take(8)} " +
                "reason=$why bodyKind=${fn.body::class.simpleName}")
        }
        return why
    }

    /** Max IR-node count for a body to be considered a "trivial" wrapper. */
    private const val INLINE_SIZE_BUDGET: Int = 32

    /**
     * Count IR nodes in [node] up to a cap. Returns [INLINE_SIZE_BUDGET]+1
     * as soon as the cap is exceeded so callers can short-circuit.
     * A tight walker — no allocations, uses the existing
     * [recurseChildren] structural traversal.
     */
    private fun sizeOf(node: NodeIR): Int {
        var count = 0
        fun walk(n: NodeIR) {
            if (count > INLINE_SIZE_BUDGET) return
            count++
            recurseChildren(n) { child ->
                if (count <= INLINE_SIZE_BUDGET) walk(child)
                child
            }
        }
        walk(node)
        return count
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
     * True when [node] can be skipped without changing what the program does:
     * evaluating it has no effect. A param the wrapper never reads drops its
     * argument, so only such an argument may be dropped.
     */
    private fun isPureToDrop(node: NodeIR): Boolean = when (node) {
        is MakeLambdaIR -> true
        is MakeRecordIR -> node.fields.all { isPureToDrop(it.value) }
        else -> isCheapToDuplicate(node)
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
            // Phase C: pure data-shape nodes. All are safe because they
            // build new values (records, variants, seqs, updates) or
            // do pure lookups (field/seq reads, string interp) with no
            // slot writes, no calls, no effects.
            is MakeVariantIR -> {
                val payload = node.payload
                payload == null || countParamReads(payload, paramStart, paramEnd, counts)
            }
            is ReadFieldIR -> countParamReads(node.record, paramStart, paramEnd, counts)
            is RecordUpdateIR -> {
                if (!countParamReads(node.record, paramStart, paramEnd, counts)) return false
                for (u in node.updates) {
                    if (!countParamReads(u.value, paramStart, paramEnd, counts)) return false
                }
                true
            }
            is MakeSeqIR -> {
                for (el in node.elements) {
                    val child = when (el) {
                        is SeqElementIR.Regular -> el.node
                        is SeqElementIR.Spread -> el.node
                    }
                    if (!countParamReads(child, paramStart, paramEnd, counts)) return false
                }
                true
            }
            is SeqGetIR -> {
                countParamReads(node.seq, paramStart, paramEnd, counts) &&
                countParamReads(node.index, paramStart, paramEnd, counts)
            }
            is InterpolatedStringIR -> {
                for (p in node.parts) {
                    when (p) {
                        is StringPartIR.Lit -> {}
                        is StringPartIR.Expr ->
                            if (!countParamReads(p.node, paramStart, paramEnd, counts)) return false
                    }
                }
                true
            }
            // A CLOSED lambda reads nothing from the frame it is built in, so
            // it contributes no param reads and relocating it is a no-op: its
            // `functionHash` is content-addressed and its capture list is
            // empty. One WITH captures is refused, because `CaptureIR` holds a
            // frame SLOT INDEX, not an expression — moved into a caller those
            // indices address unrelated values, and an argument that is itself
            // a lambda literal has no slot to point at. That is not a tweak
            // away; it needs the captured values materialised into caller
            // locals.
            //
            // This is the commonest shape the whitelist met — 263 occurrences
            // in one build against 90 for the next — and refusing it kept the
            // mono-specialised `Iter` impl forwarders from being inlined. Those
            // forwarders are where a dot-chained pipeline lives: mono bakes the
            // user's stage lambda into the forwarder as a closed constant and
            // leaves the callback parameter unused, so `{ it, f } -> map { it,
            // <baked f> }` was within one substitution of the free-function
            // shape that fuses, and never took it.
            is MakeLambdaIR -> node.captures.isEmpty()
            // Everything else (Match, Perform, Handle, indirect
            // calls, `WriteLocal`, `PartialApply`, `DictMethodCall`,
            // `SpecializedCall`, `CallLocal`, `CallIndirect*`) still bails.
            else -> {
                // Name the shape the whitelist does not cover. A `false` here
                // disqualifies the whole function from inlining, silently, and
                // an un-inlined trivial forwarder is invisible to
                // ChainCanonicalizer — which is how a dot-chained pipeline ends
                // up running completely unfused.
                if (System.getenv("MEZZE_TRACE_WRAPPER") != null) {
                    System.err.println("[wrapper]   countParamReads gave up on " +
                        node::class.simpleName)
                }
                false
            }
        }
    }

    /**
     * Rewrite [node] by inlining any trivial-wrapper calls found
     * inside it. Walks the whole tree (not just the top level) so calls
     * nested inside another non-trivial body still get the speedup.
     */
    /**
     * Normalise every body with no inlining: collapse record-form native calls
     * into inline ops. Used when there is no trivial wrapper to inline, so the
     * normalisation still happens — see the comment at the `wrappers.isEmpty()`
     * return.
     */
    private fun collapseOnly(functions: Map<Hash, FunctionIR>): Map<Hash, FunctionIR> {
        var changed = false
        val out = HashMap<Hash, FunctionIR>(functions.size)
        for ((hash, fn) in functions) {
            val nb = collapseWalk(fn.body)
            if (nb === fn.body) {
                out[hash] = fn
            } else {
                out[hash] = fn.copy(body = nb)
                changed = true
            }
        }
        return if (changed) out else functions
    }

    private fun collapseWalk(node: NodeIR): NodeIR =
        maybeCollapseInlineOp(recurseChildren(node) { collapseWalk(it) })

    private fun rewrite(node: NodeIR, wrappers: Map<Hash, FunctionIR>, stats: InlineStats, self: Hash): NodeIR {
        // Top-level case: this node is itself a wrappable call.
        if (node is CallDirectIR && node.isScalarABI) {
            val wrapper = if (node.targetHash == self) null else wrappers[node.targetHash]
            if (wrapper != null && wrapper.paramSlots.size == node.args.size) {
                // Recursively rewrite the args first (the caller's
                // arguments may themselves contain wrappable calls), so
                // we don't have to re-walk after substitution.
                // mapIfChanged, not `map`: a plain map always returns a NEW list, so
                // every `node.copy(args = …)` below allocated even when no argument
                // had changed. The caller tests `newBody === fn.body`, so an
                // unchanged body looked changed, `changedThisPass` never reached
                // zero, and the fixpoint burned all MAX_INLINE_PASSES passes on
                // every run — MEASURED: 0 call sites inlined across the stdlib and
                // still 8 passes, reporting 744 functions "changed".
                val rewrittenArgs = mapIfChanged(node.args) { rewrite(it, wrappers, stats, self) }
                // Closure-capture gate: a `MakeLambdaIR` arg that captures values
                // (`__dict_<m>` closures like a `cmp = {a,b}-> a<b`) reads those from
                // the CALLER's frame slots. Substituting it into the wrapper body
                // relocates it into the callee's frame, where those slot indices point
                // at unrelated values → the dict is lost and the comparator falls to a
                // primitive op (`Cannot compare MezzeRecord`). Never inline such a site.
                if (rewrittenArgs.any { it is MakeLambdaIR && it.captures.isNotEmpty() }) {
                    stats.sitesSkippedHeavyArg++
                    return if (rewrittenArgs !== node.args) node.copy(args = rewrittenArgs) else node
                }
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
                        // A param the wrapper never reads drops its argument —
                        // only allowed when evaluating it could do nothing.
                        if (counts[i] == 0 && !isPureToDrop(rewrittenArgs[i])) {
                            ok = false; break
                        }
                    }
                    if (ok) {
                        val substituted = untail(
                            substituteParams(wrapper.body, rewrittenArgs, wrapper.captureSlots.size),
                            siteIsTail = node.isTailCall,
                        )
                        stats.sitesInlined++
                        // Collapse CallNativeIR → InlineBinaryOpIR when
                        // the native has an inlineOp equivalent.
                        val collapsed = maybeCollapseInlineOp(substituted)
                        // Recurse in case the wrapper body referenced
                        // another wrapper (chain of trivial forwarders).
                        return rewrite(collapsed, wrappers, stats, self)
                    } else {
                        stats.sitesSkippedHeavyArg++
                    }
                }
                // Fall through: leave the call alone, but keep the args
                // rewritten (nested inlining still applies).
                // This guard was always HERE and always true, because the plain
                // `map` above handed it a fresh list every time. With
                // mapIfChanged it finally means what it says.
                if (rewrittenArgs !== node.args) {
                    return node.copy(args = rewrittenArgs)
                }
                return node
            }
        }
        // Collapse on the way OUT of every node, not only after a wrapper
        // substitution. MEASURED: `acc + n` in an annotation-free tail loop
        // arrives as
        //     CallNativeIR(__native_int_add,
        //         arg=MakeRecordIR([l=ReadLocal(kind=LONG), r=ReadLocal(kind=LONG)]),
        //         args=[], inlineOp=null, src=fileIndex=50)
        // i.e. already inlined from the stdlib snapshot (int.mz's `add` body),
        // in RECORD form, by a path that never reached the collapse. Everything
        // needed was present — field names match the signature and both
        // operands are LONG — so the record survived lowering purely because
        // nothing asked. That cost 48x: 12 ns/iter against 0.25 for the same
        // loop with the operands positional, because the record both allocates
        // per iteration AND makes FusedLongTailLoop refuse the function.
        return maybeCollapseInlineOp(
            recurseChildren(node) { rewrite(it, wrappers, stats, self) }
        )
    }

    /**
     * A wrapper body with the tail-call marks in its TAIL POSITIONS dropped,
     * because the call site it is being substituted into is not itself a tail
     * call.
     *
     * A tail call returns from its enclosing frame. That is correct inside the
     * wrapper — the body's tail IS the wrapper's return — and wrong the moment
     * the body moves into a caller where the site is, say, a `let` statement. The
     * caller then returns that statement's value and never evaluates its own
     * result. A WRONG ANSWER, silently:
     *
     *     let sum_to = {n, acc} -> if n <= 0 then acc else sum_to {n = n-1, acc = acc+n}
     *     let s = sum_to {n = 5, acc = 0}
     *     3 + 4                                  -- returned 15, not 7
     *
     * When the site IS a tail call the marks stay, and must: the body's tail is
     * then genuinely the caller's tail, which is what makes inlining a tail call
     * sound in the first place.
     *
     * Only positions the body RETURNS FROM are walked. A `MakeLambdaIR` is not
     * descended into — a lambda's body tails belong to the lambda and stay
     * correct wherever the lambda is moved.
     *
     * Same rule as `ConstraintResult.operandOf` states on the constraint side:
     * tail position is syntactic, so MOVING an expression can take it away.
     */
    private fun untail(node: NodeIR, siteIsTail: Boolean): NodeIR {
        if (siteIsTail) return node
        return when (node) {
            is CallDirectIR -> if (node.isTailCall) node.copy(isTailCall = false) else node
            is CallIndirectIR -> if (node.isTailCall) node.copy(isTailCall = false) else node
            is CallLocalIR -> if (node.isTailCall) node.copy(isTailCall = false) else node
            is PartialApplyDirectIR -> if (node.isTailCall) node.copy(isTailCall = false) else node
            is IfIR -> node.copy(thenBr = untail(node.thenBr, false), elseBr = untail(node.elseBr, false))
            is MatchIR -> node.copy(cases = node.cases.map { it.copy(body = untail(it.body, false)) })
            is BlockIR -> node.copy(result = untail(node.result, false))
            else -> node
        }
    }

    /** Collapse a CallNativeIR to InlineBinaryOpIR when the native
     *  declares an inlineOp. Mirrors TypedASTCompiler.maybeCollapseInlineOp
     *  but runs in the ast module (no typechecker dependency). */
    private fun maybeCollapseInlineOp(node: NodeIR): NodeIR {
        if (node !is CallNativeIR) return node
        val sig = NativeSignatures.signatureOf(node.name) ?: return node
        val op = sig.inlineOp ?: return node
        val positional: List<NodeIR>? = when {
            node.isScalarABI && node.args.size == sig.params.size -> node.args
            node.arg is MakeRecordIR -> {
                val record = node.arg as MakeRecordIR
                val fields = record.fields.filterIsInstance<FieldIR>()
                if (fields.size != sig.params.size) null
                else sig.params.map { p -> fields.find { it.name == p.name }?.value ?: return node }
            }
            else -> null
        }
        if (positional == null || positional.size != 2) return node
        // EVALUATION ORDER: InlineBinaryOpIR evaluates left then right. The
        // record form evaluates its fields in the order they are written, which
        // is not necessarily the signature's parameter order. Collapsing a
        // record whose fields are written out of signature order would silently
        // reorder two operand expressions, so decline it — an operand can be a
        // call. Arithmetic written `{ l = .., r = .. }` (every stdlib numeric
        // body) is in order and collapses.
        if (node.arg is MakeRecordIR) {
            val written = (node.arg as MakeRecordIR).fields
                .filterIsInstance<FieldIR>().map { it.name }
            val declared = sig?.params?.map { it.name } ?: return node
            if (written != declared) return node
        }
        return InlineBinaryOpIR(op, positional[0], positional[1], node.src)
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

            is FusedChainIR -> {
                // #446 — descend into source expression, sink init, and
                // take/drop count exprs. MakeLambdaIR is a leaf (its body
                // lives in a separate FunctionIR the outer pass walks).
                val newSource: SourceIR = when (val s = node.source) {
                    is SourceIR.RangeSeq -> {
                        val nStart = f(s.start); val nEnd = f(s.end)
                        if (nStart !== s.start || nEnd !== s.end)
                            SourceIR.RangeSeq(nStart, nEnd) else s
                    }
                    is SourceIR.RangeStepSeq -> {
                        val nStart = f(s.start); val nEnd = f(s.end); val nStep = f(s.step)
                        if (nStart !== s.start || nEnd !== s.end || nStep !== s.step)
                            SourceIR.RangeStepSeq(nStart, nEnd, nStep) else s
                    }
                    is SourceIR.RawSeq -> {
                        val nXs = f(s.xs)
                        if (nXs !== s.xs) SourceIR.RawSeq(nXs) else s
                    }
                    is SourceIR.RawList -> {
                        val nXs = f(s.xs)
                        if (nXs !== s.xs) SourceIR.RawList(nXs) else s
                    }
                    is SourceIR.RawBytes -> {
                        val nXs = f(s.xs)
                        if (nXs !== s.xs) SourceIR.RawBytes(nXs) else s
                    }
                    is SourceIR.Opaque -> {
                        val nE = f(s.expr)
                        if (nE !== s.expr) SourceIR.Opaque(nE) else s
                    }
                }
                var stagesChanged = false
                val newStages = node.stages.map { stage ->
                    when (stage) {
                        is StageIR.Map, is StageIR.Filter, is StageIR.TakeWhile, is StageIR.DropWhile -> stage
                        StageIR.Enumerate -> stage
                        is StageIR.Take -> { val nn = f(stage.n); if (nn !== stage.n) { stagesChanged = true; StageIR.Take(nn) } else stage }
                        is StageIR.Drop -> { val nn = f(stage.n); if (nn !== stage.n) { stagesChanged = true; StageIR.Drop(nn) } else stage }
                        // The second source holds NodeIRs that this pass
                        // rewrites like any other; rebuilt only when it changed.
                        is StageIR.Zip -> {
                            val ns = mapSourceIR(stage.second, f)
                            if (ns != stage.second) { stagesChanged = true; StageIR.Zip(ns) } else stage
                        }
                        is StageIR.FlatMap -> {
                            val ns = mapSourceIR(stage.inner, f)
                            if (ns != stage.inner) { stagesChanged = true; StageIR.FlatMap(stage.f, ns) } else stage
                        }
                    }
                }
                val newSink: SinkIR = when (val sk = node.sink) {
                    is SinkIR.Fold -> {
                        val nInit = f(sk.init)
                        if (nInit !== sk.init) SinkIR.Fold(nInit, sk.f) else sk
                    }
                    is SinkIR.Scan -> {
                        val nInit = f(sk.init)
                        if (nInit !== sk.init) SinkIR.Scan(nInit, sk.f) else sk
                    }
                    is SinkIR.ForEach, is SinkIR.Reduce, is SinkIR.Any, is SinkIR.All, is SinkIR.Find,
                    is SinkIR.FindIndex, is SinkIR.None, is SinkIR.MinBy, is SinkIR.MaxBy, is SinkIR.Partition -> sk
                    SinkIR.Sum, SinkIR.SumFloat, SinkIR.Product, SinkIR.ProductFloat,
                    SinkIR.Count, SinkIR.ToSeq, SinkIR.ToList,
                    SinkIR.First, SinkIR.Last, SinkIR.Min, SinkIR.Max -> sk
                }
                val newFallback = node.fallback?.let(f)
                if (newSource !== node.source || newSink !== node.sink || stagesChanged || newFallback !== node.fallback)
                    node.copy(source = newSource, stages = if (stagesChanged) newStages else node.stages, sink = newSink, fallback = newFallback) else node
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
