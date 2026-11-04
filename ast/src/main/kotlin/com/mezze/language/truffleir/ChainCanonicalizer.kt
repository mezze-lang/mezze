package com.mezze.language.truffleir

import com.mezze.language.typedast.Hash

/**
 * #446 — canonicalize iter chains into [FusedChainIR].
 *
 * Runs post-`TrivialWrapperInliner`, pre-`DeadLetEliminator`. Rewrites
 * every fusable iter chain into a single canonical node so the BDSL
 * fusion peel matches ONE shape instead of ~16 permutations of
 * (call-form × wrapper-form × descriptor-inline-form × sink-kind).
 *
 * Refusal is silent: if a call doesn't match a recognized HOF shape,
 * the original node is returned reference-equal. The BDSL fallback
 * (ability-dispatch, generic seq-fold) then runs unchanged.
 *
 * Env gate: `MEZZE_CHAIN_CANONICALIZE=0` disables the pass entirely.
 * Default is off during phased landing; flipped on in Phase 5 once the
 * BDSL emit for FusedChainIR is in place.
 *
 * See docs/iter-canonicalizer-design.md for the full design.
 */
object ChainCanonicalizer {

    /**
     * Kill switch. Default ON since Phase 5. Escape hatch:
     * MEZZE_CHAIN_CANONICALIZE=0 disables the pass and forces callers to
     * rely on the throwing intercept in BytecodeCompiler.emitNode — no
     * FusedChainIR is produced, so the intercept is unreachable.
     *
     * THE ONLY ONE IN THIS FILE, deliberately. A kill switch exists to prove a
     * pass's effect by building the same program twice from one compiler, and
     * once that pass has a test which FAILS without it, the test does that job
     * permanently and in CI while the switch is exercised only when someone
     * remembers. What is left is a second code path nothing covers in its `off`
     * state — and `MEZZE_NATIVE_SIGS=0` shows how that rots: the stdlib snapshot
     * is precompiled with signatures on, so clearing it at user-build time does
     * not disable what it claims to.
     *
     * So the rule here is one switch per SUBSYSTEM, not per pass: this one turns
     * off everything in this file, which is what triage of a suspected
     * mis-fusion actually needs. `tryDistributeOverIf` and
     * `resolveSlotStageCall` had their own and no longer do; each is pinned by
     * tests verified to fail without it (ChainIfDistributionLoweringTest,
     * ChainSlotStageLoweringTest). Re-measuring a landed pass is a build of its
     * parent commit, not a flag.
     */
    private val ENABLED: Boolean = System.getenv("MEZZE_CHAIN_CANONICALIZE") != "0"

    /**
     * Coerce [node] to a [MakeLambdaIR] for chain-analysis purposes.
     * - Already a MakeLambdaIR → return directly.
     * - ReadGlobalFnByHashIR (named function reference, e.g. `double`) →
     *   synthesize a zero-capture MakeLambdaIR pointing at the same hash.
     *   The emitter's classifyUnaryLiteralLambda / classifyFolderBody will
     *   look through it to the function's body for shape classification,
     *   so no actual closure is created at runtime.
     */
    private fun asMakeLambda(node: NodeIR?): MakeLambdaIR? = when (node) {
        is MakeLambdaIR -> node
        is ReadGlobalFnByHashIR -> MakeLambdaIR(node.hash, emptyList(), node.src)
        else -> null
    }

    /**
     * Gives a relocated lambda's captures somewhere to point in the caller.
     *
     * A `CaptureIR` names a frame SLOT, not a value, so moving a lambda into a
     * caller only works if every captured value lives in a slot THERE. When the
     * caller passed a literal — `go { k = 10, lim = 40 }` — it lives in no slot
     * at all, and refusing the move used to mean refusing the fusion with it:
     * a chain that SPANS the call (stages in the callee, sink in the caller)
     * then had an opaque source and ran element-at-a-time.
     *
     * So the value is bound to a fresh local and the capture points at that —
     * the let-binding form of inlining. Slots are appended after the
     * function's existing frame, which is laid out `[captures…, params…,
     * locals…]`, and the bindings are emitted immediately around the relocated
     * expression rather than hoisted to the function's entry, so an argument is
     * still evaluated exactly where and when it was.
     *
     * [mark] and [rollback] undo the allocations of a canonicalization attempt
     * that then fails, so a declined attempt leaves no unused slot behind.
     */
    private class FrameExtender(fn: FunctionIR) {
        private val baseSlot = fn.captureSlots.size + fn.paramSlots.size + fn.localSlots.size
        private val names = mutableListOf<String>()
        private val kinds = mutableListOf<SlotKind>()
        private val lets = mutableListOf<StmtIR.Let>()

        val addedNames: List<String> get() = names
        val addedKinds: List<SlotKind> get() = kinds
        fun isEmpty(): Boolean = names.isEmpty()

        fun mark(): Int = names.size

        fun rollback(mark: Int) {
            while (names.size > mark) {
                names.removeAt(names.size - 1)
                kinds.removeAt(kinds.size - 1)
                lets.removeAt(lets.size - 1)
            }
        }

        /** The bindings created since [mark], to wrap that attempt's result. */
        fun letsSince(mark: Int): List<StmtIR> = lets.subList(mark, lets.size).toList()

        fun materialize(value: NodeIR, kind: SlotKind, src: SourceInfo): Int {
            val slot = baseSlot + names.size
            val name = "__fuse_cap$slot"
            names.add(name)
            kinds.add(kind)
            lets.add(StmtIR.Let(slot, kind, name, Hash.ZERO, value, src))
            return slot
        }
    }

    /**
     * True when [node] may be bound to a caller local by [FrameExtender].
     *
     * It must be safe to evaluate TWICE. A [FusedChainIR] keeps the original
     * call as its `fallback`, which the emitter uses when it cannot classify
     * the chain — and that call still contains this expression as an argument.
     * So a pure, cheap value is evaluated once in the binding and harmlessly
     * again on the fallback path, while a call or an allocation would run
     * twice. Refusing those is why this is a whitelist: a literal bound to a
     * local is the case that actually arises, since a value the caller already
     * holds in a slot needs no binding at all.
     */
    private fun isPureToMaterialize(node: NodeIR): Boolean = when (node) {
        is LongLitIR, is DoubleLitIR, is BoolLitIR, is StringLitIR,
        is UnitLitIR, is CharLitIR, is FracLitIR,
        is ReadGlobalByHashIR, is ReadGlobalFnByHashIR -> true
        is InlineBinaryOpIR -> isPureToMaterialize(node.left) && isPureToMaterialize(node.right)
        is MakeLambdaIR -> node.captures.isEmpty()
        else -> false
    }

    /**
     * Rewrite each function's body. Bodies that don't contain any
     * canonicalizable iter chain are returned unchanged (reference-equal),
     * preserving hash stability for the FunctionIR content-addressing.
     *
     * @param functions the current function-hash → FunctionIR map (post
     *                  inline, pre dead-let). Callers merge this into the
     *                  same slot in their pipeline.
     * @param stdlibSnapshot when compiling user code, this exposes stdlib
     *                      FunctionIRs so we can resolve callee hashes
     *                      (iter::fold etc.) that aren't in [functions].
     */
    fun canonicalize(
        functions: Map<Hash, FunctionIR>,
        stdlibSnapshot: Map<Hash, FunctionIR> = emptyMap(),
    ): Map<Hash, FunctionIR> {
        if (!ENABLED) return functions

        // Build the HOF classifier once, from both maps.
        val classifier = ChainHofClassifier.build(functions, stdlibSnapshot)
        if (classifier.isEmpty()) return functions

        // Merge both maps for function-body lookups (effect gate, etc.).
        val allFunctions = if (stdlibSnapshot.isEmpty()) functions
                           else HashMap<Hash, FunctionIR>(functions.size + stdlibSnapshot.size).also {
                               it.putAll(stdlibSnapshot); it.putAll(functions)
                           }

        val out = HashMap<Hash, FunctionIR>(functions.size)
        var anyChanged = false
        for ((hash, fn) in functions) {
            // One extender per function: a relocated capture may need a slot
            // in THIS function's frame, and the frame grows only here.
            val ext = FrameExtender(fn)
            val newBody = rewrite(fn.body, classifier, allFunctions, ext)
            if (newBody === fn.body && ext.isEmpty()) {
                out[hash] = fn
            } else {
                out[hash] = fn.copy(
                    body = newBody,
                    localSlots = if (ext.isEmpty()) fn.localSlots else fn.localSlots + ext.addedKinds,
                    localNames = if (ext.isEmpty()) fn.localNames else fn.localNames + ext.addedNames,
                )
                anyChanged = true
            }
        }
        return if (anyChanged) out else functions
    }

    /**
     * Bottom-up rewrite: recurse into children first, then try to
     * canonicalize this node. Ensures nested iter chains (e.g. a chain
     * whose source is itself a chain) get inner-first canonicalization —
     * but the peel operates on the OUTER call's args, so the inner form
     * matters only if it produces a canonical shape we can then peel
     * through. Today we only recognize the flat 4-layer chain; nested
     * iter-of-iter is out of scope for MVP.
     */
    private fun rewrite(
        node: NodeIR,
        classifier: ChainHofClassifier,
        allFunctions: Map<Hash, FunctionIR>,
        ext: FrameExtender,
        lambdas: Map<Int, MakeLambdaIR> = emptyMap(),
    ): NodeIR {
        // A STAGE WRITTEN AS A LOCAL HELPER. `peelSource` walks expressions and
        // cannot follow a call through a frame slot, so a stage parameterised by
        // a value from the enclosing scope —
        //
        //     let scaled = { xs } -> iter::map { it = xs, f = { x } -> x * factor }
        //     iter::fold { it = iter::filter { it = scaled { xs = … }, … }, … }
        //
        // left the source un-typable and the pipeline ran element-at-a-time.
        // `MEZZE_TRACE_FUSION` names it exactly: `native=__native_iter_fold
        // source=CallIndirectScalarIR:through-a-slot`. `bench/cases/dotprobe` k3
        // measured 49 280 ps/elem against the fused 152.
        //
        // Everything needed is at the call site: the binding names the target
        // AND the capture mapping in one node, and the callee frame is
        // `[captures…, params…, locals…]`, so the body can be substituted in
        // with its slots rewritten to mean the same thing here. Resolving it
        // BEFORE the walk below means the chain machinery sees an ordinary
        // expression and needs no new cases.
        val scope = if (node is BlockIR) lambdas + lambdaLets(node) else lambdas
        val opened = if (scope.isEmpty()) node
                     else resolveSlotStageCall(node, scope, allFunctions) ?: node
        // Recurse first — child nodes may contain iter chains too.
        val descended = recurseChildren(opened) { child ->
            rewrite(child, classifier, allFunctions, ext, scope)
        }
        // Try to canonicalize this node itself (only CallDirectIRs can
        // become FusedChainIRs).
        val mark = ext.mark()
        val result = tryCanonicalize(descended, classifier, allFunctions, ext)
        if (result == null) {
            // The attempt may have allocated slots for captures it then could
            // not use. Give them back rather than growing the frame for a
            // fusion that did not happen.
            ext.rollback(mark)
            // A CHAIN WHOSE SOURCE IS A CONDITIONAL distributes over the
            // branches. `peelSource` has no case for an `IfIR`, so a helper
            // that chooses between two pipelines left the source Opaque and
            // the caller's whole chain ran element-at-a-time —
            // `bench/cases/dotprobe` k4, 43 416 ps/elem against the fused
            // 152, i.e. 286x. Distributing is sound because the branches are
            // MUTUALLY EXCLUSIVE: putting the operation in both duplicates
            // the IR and not the work, since exactly one branch runs. Each
            // branch is then an ordinary chain, so the result goes back
            // through this walk to be fused.
            val distributed = tryDistributeOverIf(descended, allFunctions)
            if (distributed != null) return rewrite(distributed, classifier, allFunctions, ext, scope)
            return descended
        }
        // Preserve the original node as fallback. FusedChainIR replaces
        // the original IR — if the BDSL emitter can't classify the fused
        // chain (complex fold body, un-classifiable predicate, etc.), it
        // falls back to emitting this node instead of crashing.
        val fused = result.copy(fallback = descended)
        // Bind anything this attempt had to materialise, right here: the
        // captures point at these slots, so the bindings must run before the
        // chain and must not be lifted out of a branch that may not be taken.
        val lets = ext.letsSince(mark)
        return if (lets.isEmpty()) fused else BlockIR(lets, fused, fused.src)
    }

    /** Slots this block binds, exactly once, to a lambda. A slot bound twice is
     *  not a known target and is left alone. */
    private fun lambdaLets(block: BlockIR): Map<Int, MakeLambdaIR> {
        val out = HashMap<Int, MakeLambdaIR>()
        val twice = HashSet<Int>()
        for (st in block.stmts) {
            if (st !is StmtIR.Let) continue
            if (!twice.add(st.slot)) { out.remove(st.slot); continue }
            (st.value as? MakeLambdaIR)?.let { out[st.slot] = it }
        }
        return out
    }

    /**
     * A call through a slot holding a known lambda, with the callee's body
     * substituted in. Null when the shape does not match or the body cannot be
     * expressed here.
     *
     * Only a callee whose body is a chain STAGE is inlined, so this cannot
     * inline arbitrary helpers — the set of things it relocates is the set the
     * fuser can consume, and moving one can only turn a refusal into a fusion.
     */
    private fun resolveSlotStageCall(
        node: NodeIR,
        lambdas: Map<Int, MakeLambdaIR>,
        fns: Map<Hash, FunctionIR>,
    ): NodeIR? {
        val slot: Int
        val args: List<NodeIR>
        val names: List<String>
        when (node) {
            is CallIndirectScalarIR -> {
                if (node.isTailCall) return null
                slot = (node.function as? ReadLocalIR)?.slot ?: return null
                args = node.args; names = node.argFieldNames
            }
            is CallIndirectIR -> {
                if (node.isTailCall) return null
                slot = (node.function as? ReadLocalIR)?.slot ?: return null
                val rec = node.arg as? MakeRecordIR ?: return null
                args = rec.fields.map { it.value }; names = rec.fields.map { it.name }
            }
            else -> return null
        }
        val lambda = lambdas[slot] ?: return null
        val target = fns[lambda.functionHash] ?: return null
        if (target.localSlots.isNotEmpty()) return null
        if (target.captureSlots.size != lambda.captures.size) return null
        val captureCount = target.captureSlots.size
        val paramCount = target.paramSlots.size
        if (args.size != paramCount || names.size != paramCount) return null
        if (!isChainExpr(target.body) && !isStageCallFunction(target.body, fns)) return null

        val byName = names.zip(args).toMap()
        val argForParam = HashMap<Int, NodeIR>(paramCount)
        for (i in 0 until paramCount) {
            val pname = target.paramNames.getOrNull(i) ?: return null
            argForParam[captureCount + i] = byName[pname] ?: return null
        }
        return remapCalleeBody(target.body, captureCount, paramCount, argForParam, lambda.captures)
    }

    /** A direct call to a stdlib chain op — the shape a stage takes when the
     *  enclosing function was not monomorphised. */
    private fun isStageCallFunction(body: NodeIR, fns: Map<Hash, FunctionIR>): Boolean {
        if (body !is CallDirectIR) return false
        var hash = body.targetHash
        for (hop in 0..2) {
            val b = fns[hash]?.body ?: return false
            if (isChainExpr(b)) return true
            hash = (b as? CallDirectIR)?.targetHash ?: return false
        }
        return false
    }

    /**
     * Rewrite one expression from a callee's body so it means the same thing
     * here. Null when it cannot be expressed.
     *
     *  - `ReadCaptureIR(i)` becomes a read of the caller slot capture `i` was
     *    taken from.
     *  - a PARAM slot read becomes the argument passed for it.
     *  - a nested `MakeLambdaIR`'s `CaptureIR(slot=j)` is repointed: a callee
     *    capture to the caller slot behind it, a callee param to the slot the
     *    argument reads. An argument that is not a slot read has nowhere for a
     *    capture to point, and refuses.
     *  - a callee LOCAL refuses: there is no value for it here.
     */
    private fun remapCalleeBody(
        node: NodeIR,
        captureCount: Int,
        paramCount: Int,
        argForParam: Map<Int, NodeIR>,
        captures: List<CaptureIR>,
    ): NodeIR? = when (node) {
        is ReadCaptureIR -> captures.getOrNull(node.index)
            ?.let { ReadLocalIR(it.slot, it.kind, node.src, null) }
        is ReadLocalIR -> argForParam[node.slot]
            ?: if (node.slot < captureCount) {
                captures.getOrNull(node.slot)?.let { ReadLocalIR(it.slot, it.kind, node.src, null) }
            } else null
        is MakeLambdaIR -> {
            val moved = ArrayList<CaptureIR>(node.captures.size)
            var ok = true
            for (cap in node.captures) {
                val j = cap.slot
                if (j < captureCount) {
                    val outer = captures.getOrNull(j)
                    if (outer == null) { ok = false; break }
                    moved.add(CaptureIR(outer.slot, cap.kind))
                } else if (j < captureCount + paramCount) {
                    val arg = argForParam[j] as? ReadLocalIR
                    if (arg == null) { ok = false; break }
                    moved.add(CaptureIR(arg.slot, cap.kind))
                } else { ok = false; break }
            }
            if (ok) node.copy(captures = moved) else null
        }
        else -> {
            var failed = false
            val out = node.transformChildren { child ->
                if (failed) child else {
                    val r = remapCalleeBody(child, captureCount, paramCount, argForParam, captures)
                    if (r == null) { failed = true; child } else r
                }
            }
            if (failed) null else out
        }
    }

    /**
     * `op { src = if c then A else B, …rest }`
     *   ->  `if c then op { src = A, …rest } else op { src = B, …rest }`
     *
     * Also accepts a source that is a CALL to a capture-free, local-free
     * function whose body is that `if`, substituting the call's arguments into
     * the condition and both branches — which is the shape a helper like
     *
     *     let stages = { xs, on } ->
     *       if on then xs.map{…}.filter{…} else xs
     *
     * produces. That helper is not inlined, because `xs` is read in both
     * branches and the inliner will not duplicate a non-cheap argument; and
     * the cross-function peek refuses it because its body is not a single call.
     * So it reached `peelSource` as an opaque call and nothing fused.
     *
     * WHAT MAY BE DUPLICATED. Every argument is substituted into the condition
     * and into BOTH branches, so each must be something that costs nothing to
     * evaluate more than once: a literal, a slot read, or a lazy chain stage
     * such as `iter::range` / `iter::map`, which builds a descriptor and runs
     * nothing. Anything else refuses — a source that performs, allocates
     * eagerly or calls into unknown code must not be duplicated even across
     * exclusive branches, because the condition may read it too.
     *
     * Returns null unless at least one branch is a chain expression: without
     * that there is no fusion to win and this would only grow the IR.
     */
    private fun tryDistributeOverIf(
        node: NodeIR,
        allFunctions: Map<Hash, FunctionIR>,
    ): NodeIR? {
        if (node !is CallNativeIR || node.isScalarABI) return null
        val record = node.arg as? MakeRecordIR ?: return null
        val srcField = NativeHofNames.srcFieldFor(node.name)
        val srcIdx = record.fields.indexOfFirst { it.name == srcField }
        if (srcIdx < 0) return null

        val branching = asBranchingSource(record.fields[srcIdx].value, allFunctions) ?: return null
        if (!isChainExpr(branching.thenBr) && !isChainExpr(branching.elseBr)) return null

        fun withSource(branch: NodeIR): NodeIR {
            val fields = record.fields.toMutableList()
            fields[srcIdx] = fields[srcIdx].copy(value = branch)
            return node.copy(arg = MakeRecordIR(fields, record.src))
        }
        return IfIR(branching.cond, withSource(branching.thenBr), withSource(branching.elseBr), node.src)
    }

    /** An `if` over two sources, written directly or reached through a call. */
    private class BranchingSource(val cond: NodeIR, val thenBr: NodeIR, val elseBr: NodeIR)

    private fun asBranchingSource(
        src: NodeIR,
        allFunctions: Map<Hash, FunctionIR>,
    ): BranchingSource? {
        if (src is IfIR) return BranchingSource(src.cond, src.thenBr, src.elseBr)
        if (src !is CallDirectIR) return null
        val target = allFunctions[src.targetHash] ?: return null
        if (target.captureSlots.isNotEmpty()) return null
        val body = target.body as? IfIR ?: return null
        val paramCount = target.paramSlots.size
        if (readsNonParamSlot(body, paramCount)) return null
        val args = argSlotValues(src, target, paramCount) ?: return null
        // Each argument lands in the condition and in both branches.
        if (args.values.any { !isSafeToDuplicateAcrossBranches(it) }) return null
        return BranchingSource(
            substituteSlots(body.cond, args),
            substituteSlots(body.thenBr, args),
            substituteSlots(body.elseBr, args),
        )
    }

    /** Costs nothing to evaluate more than once. A lazy chain stage qualifies:
     *  it builds a descriptor, and the elements are walked at the sink. */
    private fun isSafeToDuplicateAcrossBranches(node: NodeIR): Boolean = when (node) {
        is LongLitIR, is DoubleLitIR, is BoolLitIR, is StringLitIR,
        is UnitLitIR, is CharLitIR, is FracLitIR -> true
        is ReadLocalIR, is ReadCaptureIR, is ReadArgIR -> true
        is ReadFieldIR -> isSafeToDuplicateAcrossBranches(node.record)
        is InlineBinaryOpIR ->
            isSafeToDuplicateAcrossBranches(node.left) && isSafeToDuplicateAcrossBranches(node.right)
        else -> isChainExpr(node)
    }

    /** A lazy source or stage the fuser itself consumes. */
    private fun isChainExpr(node: NodeIR): Boolean {
        if (node !is CallNativeIR) return false
        return CHAIN_SHAPES.any { op -> NativeHofNames.isAnyElementCollection(node.name, op) }
    }

    private val CHAIN_SHAPES = setOf(
        NativeHofNames.OP_MAP, NativeHofNames.OP_FILTER, NativeHofNames.OP_TAKE,
        NativeHofNames.OP_DROP, NativeHofNames.OP_TAKE_WHILE, NativeHofNames.OP_DROP_WHILE,
        NativeHofNames.OP_ENUMERATE, NativeHofNames.OP_RANGE,
        NativeHofNames.OP_FROM_SEQ, NativeHofNames.OP_FROM_LIST,
    )

    /** Replace `ReadLocalIR(slot)` for every slot the map names. */
    private fun substituteSlots(node: NodeIR, args: Map<Int, NodeIR>): NodeIR {
        if (node is ReadLocalIR) args[node.slot]?.let { return it }
        return node.transformChildren { substituteSlots(it, args) }
    }

    /**
     * Attempt to rewrite [node] into an [FusedChainIR]. Returns null if
     * the shape isn't recognized — caller keeps the original node.
     */
    private fun tryCanonicalize(
        node: NodeIR,
        classifier: ChainHofClassifier,
        allFunctions: Map<Hash, FunctionIR>,
        ext: FrameExtender,
    ): FusedChainIR? {
        // Bare __native_seq_sum / __native_iter_sum — desugared body of
        // seq::sum / iter::sum. Rewrite to FusedChainIR with a Sum sink so
        // the BDSL emit can drive BuiltinSumLongSeq / BuiltinSumRangeLong
        // through the canonical path.
        if (node is CallNativeIR
            && (node.name == "__native_seq_sum" || node.name == "__native_iter_sum"
                || node.name == "__native_list_sum"
                || node.name == "__native_set_sum" || node.name == "__native_sorted_set_sum"
                || node.name == "__native_deque_sum")
            && !node.isScalarABI) {
            val mr = node.arg as? MakeRecordIR ?: return null
            if (mr.fields.size != 1) return null
            val fld = mr.fields[0]
            if (fld.name !in NativeHofNames.SRC_FIELDS) return null
            val (stages, source) = peelSource(fld.value, classifier, allFunctions, ext)
            // seq_sum: we know the source is a Seq — promote Opaque → RawSeq.
            // list_sum: source is a List — promote Opaque → RawList.
            //   BUT only when there are stages to fuse — bare list::sum is
            //   faster as a native call (direct cons-cell walk) than via
            //   ListToLongSeq + FLP (O(n) array copy overhead).
            // set/sorted_set/deque: promote Opaque → to_seq → RawSeq.
            //   Same as list: only fuse when there are stages to benefit from.
            // iter_sum: source could be any collection — must reject Opaque.
            val effectiveSource = when {
                source is SourceIR.Opaque && node.name == "__native_seq_sum" -> SourceIR.RawSeq(source.expr)
                source is SourceIR.Opaque && node.name == "__native_list_sum" ->
                    if (stages.isNotEmpty()) SourceIR.RawList(source.expr) else return null
                source is SourceIR.Opaque && (NativeHofNames.isSetNative(node.name)
                        || NativeHofNames.isSortedSetNative(node.name)
                        || NativeHofNames.isDequeNative(node.name)) ->
                    if (stages.isNotEmpty()) promoteOpaqueSource(source, node.name, true) ?: return null else return null
                source is SourceIR.Opaque -> return null
                else -> source
            }
            val candidate = FusedChainIR(effectiveSource, stages, SinkIR.Sum, node.src)
            return if (isEffectSafe(candidate, allFunctions)) candidate else null
        }
        // __native_{seq,iter,list,set,sorted_set,deque}_for_each
        if (node is CallNativeIR
            && NativeHofNames.isAnyElementCollection(node.name, NativeHofNames.OP_FOR_EACH)
            && !node.isScalarABI) {
            val mr = node.arg as? MakeRecordIR ?: return null
            val srcField = NativeHofNames.srcFieldFor(node.name)
            var src: NodeIR? = null; var f: NodeIR? = null
            for (fld in mr.fields) when (fld.name) {
                srcField -> src = fld.value
                "f"  -> f  = fld.value
            }
            val srcInner = src ?: return null
            val fLambda = asMakeLambda(f) ?: return null
            // Captures OK — emit-side handles them via capture slot remap.
            val (stages, source) = peelSource(srcInner, classifier, allFunctions, ext)
            val effectiveSource = promoteOpaqueSource(source, node.name, stages.isNotEmpty()) ?: return null
            val candidate = FusedChainIR(effectiveSource, stages, SinkIR.ForEach(fLambda), node.src)
            return if (isEffectSafe(candidate, allFunctions)) candidate else null
        }
        // __native_{iter,seq,list,set,sorted_set,deque}_fold{,_left}
        if (node is CallNativeIR
            && (node.name == "__native_iter_fold" || node.name == "__native_seq_fold"
                || node.name == "__native_list_fold_left"
                || node.name == "__native_set_fold" || node.name == "__native_sorted_set_fold"
                || node.name == "__native_deque_fold")
            && !node.isScalarABI) {
            val mr = node.arg as? MakeRecordIR ?: return null
            val srcField = NativeHofNames.srcFieldFor(node.name)
            var src: NodeIR? = null; var init: NodeIR? = null; var f: NodeIR? = null
            for (fld in mr.fields) when (fld.name) {
                srcField -> src = fld.value
                "init" -> init = fld.value
                "f" -> f = fld.value
            }
            val srcInner = src ?: return null
            val initExpr = init ?: return null
            val fLambda = asMakeLambda(f) ?: return null
            if (!isFoldLambdaEmittable(fLambda, allFunctions)) return null
            val (stages, source) = peelSource(srcInner, classifier, allFunctions, ext)
            val effectiveSource = promoteOpaqueSource(source, node.name, stages.isNotEmpty()) ?: return null
            val candidate = FusedChainIR(effectiveSource, stages, SinkIR.Fold(initExpr, fLambda), node.src)
            return if (isEffectSafe(candidate, allFunctions)) candidate else null
        }
        // __native_{iter,seq}_{count,any,all,find,reduce,to_seq,to_list}
        if (node is CallNativeIR && !node.isScalarABI) {
            val result = tryCanonicalizeNativeSink(node, classifier, allFunctions, ext)
            if (result != null) return result
        }
        // Peek through CallDirect to mono wrappers whose body is a native
        // call (e.g., mono iter::count / seq::fold with a baked-in lambda).
        // The TrivialWrapperInliner can't inline these because `countParamReads`
        // rejects MakeLambdaIR in the wrapper body. But we can still fuse them
        // by manually substituting the call args into the native body and routing
        // through the native-sink path AND the fold/sum/for_each handlers.
        if (node is CallDirectIR) {
            val peeked = peekThroughMonoWrapper(node, allFunctions, ext)
            if (peeked != null) {
                // Try native-sink handlers first (count, any, all, find, etc.)
                val sinkResult = tryCanonicalizeNativeSink(peeked, classifier, allFunctions, ext)
                if (sinkResult != null) return sinkResult
                // Try fold/sum/for_each handlers (needed for mono seq::fold etc.)
                val foldResult = tryCanonicalizeNativeFoldSumForEach(peeked, classifier, allFunctions, ext)
                if (foldResult != null) return foldResult
            }
            // Compose through callee whose body is already a FusedChainIR.
            // This happens when the stdlib canonicalizer ran on a trivial
            // wrapper (e.g. seq::sum → FusedChainIR(RawSeq(ReadLocalIR(0)),
            // [], Sum)). TrivialWrapperInliner can't inline these because
            // countParamReads doesn't handle FusedChainIR. We substitute
            // the call args into the FusedChainIR source and re-peel, which
            // lets filter+sum / map+sum chains fuse end-to-end.
            val composed = composeThroughFusedCallee(node, classifier, allFunctions, ext)
            if (composed != null) return composed
        }
        if (node !is CallDirectIR) return null
        val callee = classifier.lookup(node.targetHash) ?: return null
        // Extract sink + source expr from the CallDirect args.
        val sinkAndSrc = extractSinkAndSource(node, callee) ?: return null
        val (sink, sourceExpr) = sinkAndSrc
        // Peel the source expression: strip iter::map/filter/from_seq
        // layers, then classify the terminal source (RangeSeq/RawSeq/Opaque).
        val (stages, source) = peelSource(sourceExpr, classifier, allFunctions, ext)
        // The classifier path handles ALL collection types (iter, seq, list,
        // etc.), so an Opaque source here could be any type — we can't assume
        // Seq for BuiltinSumLongSeq. Reject all Opaque sources; the explicit
        // native-call handlers (__native_iter_sum, __native_seq_sum) handle
        // Opaque+Sum safely because they know the collection type.
        if (source is SourceIR.Opaque) return null
        // Gate: only produce FusedChainIR for sinks the BDSL emitter supports.
        // New sink types (Count, Any, All, Find, Reduce, ToSeq, ToList) are
        // classified but not yet emittable — producing them would crash at
        // BytecodeCompiler line 1611. Remove this gate as emit support lands.
        if (!isEmittableSink(sink)) return null
        val candidate = FusedChainIR(source, stages, sink, node.src)
        return if (isEffectSafe(candidate, allFunctions)) candidate else null
    }

    /** True for sinks the BDSL emitter can handle today. */
    private fun isEmittableSink(sink: SinkIR): Boolean = when (sink) {
        is SinkIR.Fold, SinkIR.Sum, SinkIR.SumFloat -> true
        SinkIR.Product, SinkIR.ProductFloat -> true
        is SinkIR.ForEach -> true
        SinkIR.Count, is SinkIR.Any, is SinkIR.All -> true
        is SinkIR.Find, is SinkIR.FindIndex, is SinkIR.Reduce -> true
        SinkIR.ToSeq, SinkIR.ToList -> true
        is SinkIR.None -> true
        SinkIR.First, SinkIR.Last -> true
        SinkIR.Min, SinkIR.Max -> true
        is SinkIR.Scan -> true
        is SinkIR.MinBy, is SinkIR.MaxBy, is SinkIR.Partition -> true
    }

    /**
     * Check whether a fold/reduce lambda can be emitted by the BDSL
     * `classifyBinaryFolder` → `classifyFolderBody` path.
     *
     * FusedChainIR REPLACES the original CallDirect with no fallback, so
     * creating one whose sink the emitter can't classify crashes
     * compilation. This mirrors the emitter's classification so we only
     * create fusable Fold/Reduce chains.
     *
     * Accepted fold-body shapes (must have 2 params, 0 locals):
     * 1. InlineBinaryOpIR(ReadLocalIR, ReadLocalIR)
     * 2. CallDirectIR scalar-ABI, 2 ReadLocalIR args (known arith)
     * 3. CallIndirectIR(ReadFieldIR(ReadLocalIR, _), MakeRecordIR{2 fields})
     *
     * Captures are allowed. The emitter fuses a captured folder only when the
     * capture is the element transform's right-hand side (`acc + x * mul`),
     * which it lifts to the enclosing frame; it refuses any other captured
     * shape, and FusedChainIR's fallback then runs the unfused folder. Refusing
     * captures outright HERE instead cost a real fold 19% of its throughput,
     * because a chain that never canonicalizes can never be fused.
     */
    private fun isFoldLambdaEmittable(
        fLambda: MakeLambdaIR,
        allFunctions: Map<Hash, FunctionIR>,
    ): Boolean {
        val fn = allFunctions[fLambda.functionHash] ?: return false
        if (fn.captureSlots.size != fLambda.captures.size) return false
        if (fn.paramSlots.size != 2 || fn.localSlots.isNotEmpty()) return false
        return isFoldBodyEmittable(fn.body)
    }

    private fun isFoldBodyEmittable(body: NodeIR): Boolean = when (body) {
        // Pattern 1: inline binary op with ReadLocal operands — the common
        // case for sum/product fold bodies after the compiler inlines arith.
        is InlineBinaryOpIR -> {
            val simple = body.left is ReadLocalIR && body.right is ReadLocalIR
            if (simple) true
            else {
                // Pattern 1b: compound fold — acc <OP> f(x) where f(x) is
                // an inline binary expression over the element param.
                // E.g. { acc, x } -> acc + x * x
                // The emitter decomposes this into map(f) + fold(OP).
                (body.left is ReadLocalIR && isUnaryExpr(body.right))
                        || (body.right is ReadLocalIR && isUnaryExpr(body.left))
                        // Pattern 1c: ANY pure arithmetic tree over the two
                        // params and literals. The two patterns above cover
                        // `acc OP x` and one element transform, which is two of
                        // the three nodes in `acc + x * 2 - 1` — so that fold
                        // was refused, and a refused folder declines the WHOLE
                        // chain. Measured at 14025 ps/elem against 93 for the
                        // shapes that fuse: a 159x cliff on any fold that is not
                        // a bare `acc + x`. The emitter emits such a body as
                        // written (`BytecodeCompiler.emitFoldTree`); the two
                        // predicates agree on the grammar, and if the emitter
                        // ever disagrees, `FusedChainIR.fallback` runs the real
                        // folder.
                        || isPureArithTree(body, 0)
            }
        }
        // Pattern 2: CallDirectIR to a known arith op (scalar-ABI, 2 ReadLocal
        // args). The BDSL emitter additionally checks KnownArithOps.lookup —
        // but that's in the interpreter module (Java). To avoid false positives
        // (e.g. list::cons is scalar-ABI 2-arg but NOT a known arith op), we
        // conservatively reject CallDirectIR here. InlineBinaryOpIR covers the
        // normal arith path; the only miss is a mono spec the compiler didn't
        // reduce to InlineBinaryOpIR, which is rare and benign (unfused path
        // handles it).
        //
        // Pattern 3: dict-method dispatch — CallIndirect(ReadField(ReadLocal, _),
        // MakeRecord{2 fields}). This is the desugaring of `acc + x` when the
        // monomorphizer didn't specialize the arith op inline. The emitter's
        // classifyFolderBody checks ABILITY_OP_NAMES to filter non-arith ops;
        // if it rejects the body, FusedChainIR.fallback handles it safely.
        // Accepting this shape here enables fold fusion for the common case
        // where `+` desugars through ability dispatch (e.g. seq::fold).
        is CallIndirectIR -> body.function is ReadFieldIR
                && (body.function as ReadFieldIR).record is ReadLocalIR
                && body.arg is MakeRecordIR
                && (body.arg as MakeRecordIR).fields.size == 2
        // Pattern 4: a native over the two params — what a mono-specialised
        // folder reduces to once its call to the Int `add` impl is inlined
        // (`{acc, x} -> __native_int_add { l = acc, r = x }`). Whether the native
        // IS an arithmetic op is known only to the interpreter's registry, so the
        // emitter decides, exactly as for Pattern 3; rejected, the fallback runs.
        is CallNativeIR -> nativeOperands(body)?.all { it is ReadLocalIR } == true
        else -> false
    }

    /** The two operands of a binary native call, in either ABI; null otherwise. */
    private fun nativeOperands(call: CallNativeIR): List<NodeIR>? {
        val operands = if (call.isScalarABI) call.args
            else (call.arg as? MakeRecordIR)?.fields?.map { it.value } ?: return null
        return operands.takeIf { it.size == 2 }
    }

    /** Check if [node] is a unary expression over a single ReadLocal param —
     *  i.e. `x <OP> x`, `x <OP> lit`, or `lit <OP> x`. Used to detect
     *  compound fold bodies like `acc + x * x`. */
    /** How deep a fold body may be before it stops being worth emitting inline.
     *  Mirrors `BytecodeCompiler.FOLD_TREE_MAX_DEPTH`. */
    private const val FOLD_TREE_MAX_DEPTH = 8

    /**
     * True when [node] is a pure arithmetic expression over parameter reads and
     * integer literals — nothing that calls, allocates or performs.
     *
     * Such a body can be emitted inline exactly as written, because every
     * operator becomes the same overflow-checked operation the unfused folder
     * would run. Comparisons are excluded: an accumulator update has to produce
     * a number, not a Bool. A CAPTURED operand is excluded too, since a capture
     * addresses the lambda's own capture array and needs lifting to the
     * enclosing frame — the one-level patterns above already handle that case
     * and keep it.
     */
    private fun isPureArithTree(node: NodeIR, depth: Int): Boolean {
        if (depth > FOLD_TREE_MAX_DEPTH) return false
        return when (node) {
            is ReadLocalIR -> true
            is LongLitIR -> true
            is InlineBinaryOpIR -> when (node.op) {
                InlineOp.ADD, InlineOp.SUB, InlineOp.MUL, InlineOp.DIV, InlineOp.MOD ->
                    isPureArithTree(node.left, depth + 1) && isPureArithTree(node.right, depth + 1)
                else -> false
            }
            else -> false
        }
    }

    private fun isUnaryExpr(node: NodeIR): Boolean = when (node) {
        is InlineBinaryOpIR -> {
            val l = node.left; val r = node.right
            // ReadCaptureIR on the right is `x * mul` with `mul` captured; the
            // emitter lifts it to the enclosing frame.
            (l is ReadLocalIR && (r is ReadLocalIR || r is LongLitIR || r is ReadCaptureIR))
                    || (l is LongLitIR && r is ReadLocalIR)
        }
        else -> false
    }

    /**
     * Peek through a CallDirect to a monomorphized wrapper whose body is a
     * single record-ABI native call. Returns a substituted [CallNativeIR]
     * with the CallDirect's positional args replacing [ReadLocalIR] param
     * references in the native body, or null if the shape doesn't match.
     *
     * This handles the case where [TrivialWrapperInliner] couldn't inline
     * the mono wrapper because the body contains [MakeLambdaIR] baked in
     * by the monomorphizer, which `countParamReads` rejects. Without this,
     * the [ChainHofClassifier] misclassifies the CallDirect as FILTER/MAP
     * due to the empty name and matching param structure (e.g. mono
     * `iter::count` with params `[it, p]` collides with FILTER), and the
     * chain can't fuse.
     *
     * The substituted CallNativeIR is then routed through
     * [tryCanonicalizeNativeSink] which recognizes the native name and
     * builds the correct [FusedChainIR].
     */
    private fun peekThroughMonoWrapper(
        call: CallDirectIR,
        allFunctions: Map<Hash, FunctionIR>,
        ext: FrameExtender?,
    ): CallNativeIR? {
        var current = call
        var hops = 0
        while (hops < MAX_PEEK_HOPS) {
            hops++
            val target = allFunctions[current.targetHash] ?: return null
            // Only handle simple wrappers: no captures, no locals, so a slot
            // index in the body is a parameter index.
            //
            // THE TEST IS ON WHAT THE BODY READS, not on what the frame
            // DECLARES. It used to refuse on `localSlots.isNotEmpty()`, and that
            // rejected a helper whose locals had already been substituted away:
            // [ChainLetInliner] empties the `let` but leaves the slot declared
            // on purpose — renumbering every later slot to reclaim one costs a
            // rewrite of every read for nothing — so a helper written with one
            // named intermediate still carried a declaration, still hit this
            // refusal, and still took its caller's whole pipeline down with it.
            // What substitution actually needs is that every slot the body reads
            // IS a parameter, which an unused declaration does not affect.
            if (target.captureSlots.isNotEmpty()) return null
            val paramCount = target.paramSlots.size
            if (readsNonParamSlot(target.body, paramCount)) return null
            val slotValues = argSlotValues(current, target, paramCount) ?: return null
            when (val body = target.body) {
                is CallNativeIR -> {
                    if (body.isScalarABI) return null
                    val mr = body.arg as? MakeRecordIR ?: return null
                    val newFields = substituteCalleeArgs(mr.fields, slotValues, paramCount, ext)
                        ?: return null
                    return body.copy(arg = MakeRecordIR(newFields, mr.src))
                }
                // ANOTHER FORWARDING HOP. A user function that returns a lazy
                // stage — `let stage = { k } -> iter::map { it = xs, f = { x } ->
                // x * k }` — forwards to the stdlib function, whose body is the
                // native. That is two hops, and stopping at one left the source
                // Opaque: a pipeline whose stages are in the callee and whose
                // sink is in the caller fused NOTHING and ran element-at-a-time,
                // while the same program with a non-capturing stage fused.
                is CallDirectIR -> {
                    val forwarded = substituteForwardedCall(body, slotValues, paramCount, ext)
                        ?: return null
                    current = forwarded
                }
                else -> return null
            }
        }
        return null
    }

    /**
     * True if [node] reads a frame slot that is not a parameter.
     *
     * With no captures the layout is `[params…, locals…]`, so a read at or past
     * [paramCount] is a local — and a local has no value in the caller to
     * substitute. A slot merely DECLARED and never read is harmless, which is
     * the whole point of asking the body rather than the frame.
     */
    private fun readsNonParamSlot(node: NodeIR, paramCount: Int): Boolean {
        if (node is ReadLocalIR && node.slot >= paramCount) return true
        var found = false
        node.transformChildren { child ->
            if (!found && readsNonParamSlot(child, paramCount)) found = true
            child
        }
        return found
    }

    /** One hop covers a stdlib wrapper, two a user function forwarding to one.
     *  Past that a "wrapper" is doing something a substitution cannot model. */
    private const val MAX_PEEK_HOPS = 4

    /**
     * Map [target]'s parameter slots to the values [call] passes, for both
     * calling conventions: scalar-ABI positionally, record-ABI by field name.
     * Frame layout with no captures puts parameter `i` at slot `i`.
     */
    private fun argSlotValues(
        call: CallDirectIR,
        target: FunctionIR,
        paramCount: Int,
    ): Map<Int, NodeIR>? {
        if (call.isScalarABI) {
            if (call.args.size < paramCount) return null
            return (0 until paramCount).associateWith { call.args[it] }
        }
        val callRecord = call.args.singleOrNull() as? MakeRecordIR ?: return null
        val fieldMap = callRecord.fields.associate { it.name to it.value }
        val resolved = HashMap<Int, NodeIR>(paramCount)
        for (slot in 0 until paramCount) {
            val paramName = target.paramNames.getOrNull(slot) ?: return null
            resolved[slot] = fieldMap[paramName] ?: return null
        }
        return resolved
    }

    /**
     * Rewrite one expression taken from a callee's body so it means the same
     * thing in the caller. Returns null when it cannot be expressed there.
     *
     * Only applied to CALLEE-side expressions. A value that came from the
     * caller is carried through [argSlotValues] as-is and never passed here, so
     * a lambda whose captures were already re-expressed is not remapped twice.
     */
    private fun substituteCalleeArg(
        v: NodeIR,
        slotValues: Map<Int, NodeIR>,
        paramCount: Int,
        ext: FrameExtender?,
    ): NodeIR? {
        var failed = false
        fun walk(n: NodeIR): NodeIR {
            if (failed) return n
            return when {
                // A parameter read becomes the value the caller passed.
                n is ReadLocalIR && n.slot < paramCount -> {
                    val mapped = slotValues[n.slot]
                    if (mapped == null) { failed = true; n } else mapped
                }
                // A LAMBDA CARRIES SLOT INDICES, NOT VALUES. `CaptureIR` names a
                // slot in the frame the lambda was BUILT in, so copying one into
                // the caller leaves it addressing the callee's frame: with a
                // smaller caller frame that is an out-of-bounds read at emit,
                // and with a merely different one it is a silent wrong value.
                // [remapLambdaCaptures] re-expresses each capture against the
                // caller, binding a value the caller holds in no slot to a fresh
                // local, and refuses what it cannot express.
                n is MakeLambdaIR -> {
                    val remapped = remapLambdaCaptures(n, slotValues, paramCount, ext)
                    if (remapped == null) { failed = true; n } else remapped
                }
                // The callee has no captures (checked before any hop), so this
                // should not arise; refuse rather than relocate a slot read
                // whose meaning is unknown.
                n is ReadCaptureIR -> { failed = true; n }
                // DESCEND, rather than refuse. A stage call nested inside the
                // forwarded one — `filter { it = map { … }, p = … }` — carries
                // parameter reads and lambdas at depth, and bailing on those
                // refused exactly the pipelines worth fusing. [recurseChildren]
                // is an exhaustive `when` over the sealed node hierarchy, so a
                // new node kind cannot silently escape this walk.
                else -> recurseChildren(n) { walk(it) }
            }
        }
        val out = walk(v)
        return if (failed) null else out
    }

    /** [substituteCalleeArg] across a record-ABI argument's fields. */
    private fun substituteCalleeArgs(
        fields: List<FieldIR>,
        slotValues: Map<Int, NodeIR>,
        paramCount: Int,
        ext: FrameExtender?,
    ): List<FieldIR>? = fields.map { fld ->
        FieldIR(fld.name, substituteCalleeArg(fld.value, slotValues, paramCount, ext) ?: return null)
    }

    /** Substitute the caller's values into a forwarded call, so the next hop
     *  sees a call whose arguments are already caller-side expressions. */
    private fun substituteForwardedCall(
        call: CallDirectIR,
        slotValues: Map<Int, NodeIR>,
        paramCount: Int,
        ext: FrameExtender?,
    ): CallDirectIR? {
        if (call.isScalarABI) {
            val newArgs = call.args.map { a ->
                substituteCalleeArg(a, slotValues, paramCount, ext) ?: return null
            }
            return call.copy(args = newArgs)
        }
        val mr = call.args.singleOrNull() as? MakeRecordIR ?: return null
        val newFields = substituteCalleeArgs(mr.fields, slotValues, paramCount, ext) ?: return null
        return call.copy(args = listOf(MakeRecordIR(newFields, mr.src)))
    }

    /**
     * Compose through a CallDirectIR whose callee body is already a
     * [FusedChainIR]. This handles the phase-ordering gap where the
     * stdlib canonicalizer rewrote a trivial wrapper (e.g. seq::sum)
     * into FusedChainIR during precompile — making it invisible to
     * both TrivialWrapperInliner (countParamReads doesn't handle
     * FusedChainIR) and peekThroughMonoWrapper (expects CallNativeIR).
     *
     * We substitute the call args into the FusedChainIR's source
     * expression, prepend any existing stages, then re-peel the source
     * to discover filter/map stages from the caller's arg expression.
     *
     * Example: seq::sum body = FusedChainIR(RawSeq(ReadLocal(0)), [], Sum)
     *   Call: seq::sum(filter_result)
     *   → substitute ReadLocal(0) → filter_result
     *   → FusedChainIR(RawSeq(filter_result), [], Sum)
     *   → re-peel filter_result discovers Filter stage
     *   → FusedChainIR(RawSeq(range_expr), [Filter(p)], Sum) ← FUSED!
     */
    private fun composeThroughFusedCallee(
        call: CallDirectIR,
        classifier: ChainHofClassifier,
        allFunctions: Map<Hash, FunctionIR>,
        ext: FrameExtender,
    ): FusedChainIR? {
        val target = allFunctions[call.targetHash] ?: return null
        if (target.captureSlots.isNotEmpty() || target.localSlots.isNotEmpty()) return null
        val fused = target.body as? FusedChainIR ?: return null
        val paramCount = target.paramSlots.size

        // Build slot → value map from the call's args.
        val slotValues: Map<Int, NodeIR>
        if (call.isScalarABI) {
            if (call.args.size < paramCount) return null
            slotValues = (0 until paramCount).associateWith { call.args[it] }
        } else {
            val callRecord = call.args.singleOrNull() as? MakeRecordIR ?: return null
            val fieldMap = callRecord.fields.associate { it.name to it.value }
            val resolved = HashMap<Int, NodeIR>(paramCount)
            for (slot in 0 until paramCount) {
                val paramName = target.paramNames.getOrNull(slot) ?: return null
                resolved[slot] = fieldMap[paramName] ?: return null
            }
            slotValues = resolved
        }

        // Substitute ReadLocalIR slots in the source expression.
        val newSource = substituteSourceSlots(fused.source, slotValues, paramCount)

        // Substitute any MakeLambdaIR captures in stages that reference
        // param slots — but for trivial wrappers the stages typically
        // reference the same slots. For safety, also substitute in sink.
        // Returns null when a lambda capture can't be remapped to a caller slot.
        val newSink = substituteSinkSlots(fused.sink, slotValues, paramCount, ext) ?: return null

        // Remap captures in the callee's stages (filter/map/takeWhile/dropWhile
        // lambdas may capture dict params from the callee's frame).
        val remappedStages = remapStageCaptures(fused.stages, slotValues, paramCount, ext) ?: return null

        // Re-peel the substituted source to discover upstream stages
        // (e.g. filter, map) that now appear in the caller's arg expression.
        val sourceExpr = when (newSource) {
            is SourceIR.RawSeq -> newSource.xs
            is SourceIR.RawList -> newSource.xs
            is SourceIR.RangeSeq -> null  // already terminal
            is SourceIR.RangeStepSeq -> null  // already terminal
            is SourceIR.Opaque -> newSource.expr
        }

        if (sourceExpr != null) {
            val (peeledStages, peeledSource) = peelSource(sourceExpr, classifier, allFunctions, ext)
            // The callee's stages sit ABOVE the caller's argument expression —
            // the callee consumes what the argument produces — so they are
            // applied later and, outer-to-inner, come first.
            val combinedStages = remappedStages + peeledStages
            // If we peeled to a known source, use it. Otherwise preserve
            // the substituted source type (RawSeq stays RawSeq, etc.).
            val finalSource = if (peeledSource is SourceIR.Opaque && newSource is SourceIR.RawSeq) {
                SourceIR.RawSeq(peeledSource.expr)
            } else if (peeledSource is SourceIR.Opaque && newSource is SourceIR.RawList) {
                SourceIR.RawList(peeledSource.expr)
            } else {
                peeledSource
            }
            val candidate = FusedChainIR(finalSource, combinedStages, newSink, call.src)
            return if (isEffectSafe(candidate, allFunctions)) candidate else null
        }

        // RangeSeq or no re-peel needed: just substitute and return.
        val candidate = FusedChainIR(newSource, remappedStages, newSink, call.src)
        return if (isEffectSafe(candidate, allFunctions)) candidate else null
    }

    /** Substitute ReadLocalIR(slot) references in a SourceIR. */
    private fun substituteSourceSlots(
        source: SourceIR,
        slotValues: Map<Int, NodeIR>,
        paramCount: Int,
    ): SourceIR = when (source) {
        is SourceIR.RawSeq -> SourceIR.RawSeq(substituteSlots(source.xs, slotValues, paramCount))
        is SourceIR.RawList -> SourceIR.RawList(substituteSlots(source.xs, slotValues, paramCount))
        is SourceIR.RangeSeq -> SourceIR.RangeSeq(
            substituteSlots(source.start, slotValues, paramCount),
            substituteSlots(source.end, slotValues, paramCount),
        )
        is SourceIR.RangeStepSeq -> SourceIR.RangeStepSeq(
            substituteSlots(source.start, slotValues, paramCount),
            substituteSlots(source.end, slotValues, paramCount),
            substituteSlots(source.step, slotValues, paramCount),
        )
        is SourceIR.Opaque -> SourceIR.Opaque(substituteSlots(source.expr, slotValues, paramCount))
    }

    /** Substitute ReadLocalIR(slot) references in a SinkIR.
     *  Returns null if a lambda capture can't be remapped (the caller
     *  must bail out of the composition). */
    private fun substituteSinkSlots(
        sink: SinkIR,
        slotValues: Map<Int, NodeIR>,
        paramCount: Int,
        ext: FrameExtender,
    ): SinkIR? = when (sink) {
        is SinkIR.Fold -> {
            val rf = remapLambdaCaptures(sink.f, slotValues, paramCount, ext) ?: return null
            SinkIR.Fold(substituteSlots(sink.init, slotValues, paramCount), rf)
        }
        is SinkIR.Scan -> {
            val rf = remapLambdaCaptures(sink.f, slotValues, paramCount, ext) ?: return null
            SinkIR.Scan(substituteSlots(sink.init, slotValues, paramCount), rf)
        }
        is SinkIR.Reduce -> {
            val rf = remapLambdaCaptures(sink.f, slotValues, paramCount, ext) ?: return null
            SinkIR.Reduce(rf)
        }
        is SinkIR.ForEach -> {
            val rf = remapLambdaCaptures(sink.f, slotValues, paramCount, ext) ?: return null
            SinkIR.ForEach(rf)
        }
        is SinkIR.Any -> {
            val rp = remapLambdaCaptures(sink.p, slotValues, paramCount, ext) ?: return null
            SinkIR.Any(rp)
        }
        is SinkIR.All -> {
            val rp = remapLambdaCaptures(sink.p, slotValues, paramCount, ext) ?: return null
            SinkIR.All(rp)
        }
        is SinkIR.Find -> {
            val rp = remapLambdaCaptures(sink.p, slotValues, paramCount, ext) ?: return null
            SinkIR.Find(rp)
        }
        is SinkIR.FindIndex -> {
            val rp = remapLambdaCaptures(sink.p, slotValues, paramCount, ext) ?: return null
            SinkIR.FindIndex(rp)
        }
        is SinkIR.None -> {
            val rp = remapLambdaCaptures(sink.p, slotValues, paramCount, ext) ?: return null
            SinkIR.None(rp)
        }
        is SinkIR.MinBy -> {
            val rf = remapLambdaCaptures(sink.f, slotValues, paramCount, ext) ?: return null
            SinkIR.MinBy(rf)
        }
        is SinkIR.MaxBy -> {
            val rf = remapLambdaCaptures(sink.f, slotValues, paramCount, ext) ?: return null
            SinkIR.MaxBy(rf)
        }
        is SinkIR.Partition -> {
            val rp = remapLambdaCaptures(sink.p, slotValues, paramCount, ext) ?: return null
            SinkIR.Partition(rp)
        }
        // Sinks with no expressions that reference param slots.
        SinkIR.Sum, SinkIR.SumFloat, SinkIR.Product, SinkIR.ProductFloat -> sink
        SinkIR.Count, SinkIR.ToSeq, SinkIR.ToList -> sink
        SinkIR.First, SinkIR.Last, SinkIR.Min, SinkIR.Max -> sink
    }

    /** Remap a MakeLambdaIR's captures from the callee's frame to the caller's.
     *  Each CaptureIR(slot=S) where S < paramCount is mapped via slotValues[S].
     *  If slotValues[S] is ReadLocalIR, the capture slot is updated.
     *  If slotValues[S] is not a ReadLocalIR (e.g. a literal or MakeSeqIR),
     *  the capture can't be expressed as a slot reference → returns null.
     *  Lambdas with no captures that need remapping are returned as-is. */
    private fun remapLambdaCaptures(
        lambda: MakeLambdaIR,
        slotValues: Map<Int, NodeIR>,
        paramCount: Int,
        ext: FrameExtender?,
    ): MakeLambdaIR? {
        if (lambda.captures.isEmpty()) return lambda
        val newCaptures = lambda.captures.map { cap ->
            if (cap.slot < paramCount) {
                val mapped = slotValues[cap.slot] ?: return null
                if (mapped is ReadLocalIR) {
                    CaptureIR(slot = mapped.slot, kind = cap.kind)
                } else if (ext != null && isPureToMaterialize(mapped)) {
                    // The caller holds this value in no slot of its own — it
                    // passed a literal — so give it one. Without this the move
                    // is refused, and with it the whole chain is refused: a
                    // pipeline whose stages are in the callee and whose sink is
                    // in the caller loses fusion entirely, not just one call.
                    CaptureIR(slot = ext.materialize(mapped, cap.kind, lambda.src), kind = cap.kind)
                } else {
                    // Not safe to bind: see [isPureToMaterialize]. Refusing
                    // costs a fusion, never an answer.
                    return null
                }
            } else {
                // Slot beyond param range — callee local/capture. But
                // composeThroughFusedCallee already rejected callees with
                // locals/captures, so this shouldn't happen.
                cap
            }
        }
        return if (newCaptures == lambda.captures) lambda
               else lambda.copy(captures = newCaptures)
    }

    /** Remap captures in a list of stages. Returns null if any lambda
     *  capture can't be remapped. Returns the original list when nothing changed. */
    private fun remapStageCaptures(
        stages: List<StageIR>,
        slotValues: Map<Int, NodeIR>,
        paramCount: Int,
        ext: FrameExtender,
    ): List<StageIR>? {
        var changed = false
        val result = stages.map { stage ->
            when (stage) {
                is StageIR.Map -> {
                    val rf = remapLambdaCaptures(stage.f, slotValues, paramCount, ext) ?: return null
                    if (rf !== stage.f) changed = true
                    StageIR.Map(rf)
                }
                is StageIR.Filter -> {
                    val rp = remapLambdaCaptures(stage.p, slotValues, paramCount, ext) ?: return null
                    if (rp !== stage.p) changed = true
                    StageIR.Filter(rp)
                }
                is StageIR.TakeWhile -> {
                    val rp = remapLambdaCaptures(stage.p, slotValues, paramCount, ext) ?: return null
                    if (rp !== stage.p) changed = true
                    StageIR.TakeWhile(rp)
                }
                is StageIR.DropWhile -> {
                    val rp = remapLambdaCaptures(stage.p, slotValues, paramCount, ext) ?: return null
                    if (rp !== stage.p) changed = true
                    StageIR.DropWhile(rp)
                }
                is StageIR.Take -> {
                    val nn = substituteSlots(stage.n, slotValues, paramCount)
                    if (nn !== stage.n) changed = true
                    StageIR.Take(nn)
                }
                is StageIR.Drop -> {
                    val nn = substituteSlots(stage.n, slotValues, paramCount)
                    if (nn !== stage.n) changed = true
                    StageIR.Drop(nn)
                }
                // No lambda, no expression — nothing addresses a frame.
                StageIR.Enumerate -> stage
            }
        }
        return if (changed) result else stages
    }

    /** Recursively substitute ReadLocalIR(slot) → slotValues[slot]. */
    private fun substituteSlots(node: NodeIR, slotValues: Map<Int, NodeIR>, paramCount: Int): NodeIR {
        if (node is ReadLocalIR && node.slot < paramCount) {
            return slotValues[node.slot] ?: node
        }
        return recurseChildren(node) { child ->
            substituteSlots(child, slotValues, paramCount)
        }
    }

    /**
     * Handle __native_{iter,seq}_{sum,fold,for_each} for peeked-through mono
     * wrappers. These are the handlers that live above the generic native-sink
     * check in [tryCanonicalize] — sum, for_each, and fold — extracted so
     * the peek-through block can call them directly.
     */
    private fun tryCanonicalizeNativeFoldSumForEach(
        node: CallNativeIR,
        classifier: ChainHofClassifier,
        allFunctions: Map<Hash, FunctionIR>,
        ext: FrameExtender?,
    ): FusedChainIR? {
        if (node.isScalarABI) return null
        // Sum (any element-based collection)
        if (NativeHofNames.isAnyElementCollection(node.name, NativeHofNames.OP_SUM)) {
            val mr = node.arg as? MakeRecordIR ?: return null
            if (mr.fields.size != 1) return null
            val fld = mr.fields[0]
            if (fld.name !in NativeHofNames.SRC_FIELDS) return null
            val (stages, source) = peelSource(fld.value, classifier, allFunctions, ext)
            val effectiveSource = when {
                source is SourceIR.Opaque && NativeHofNames.isSeqNative(node.name) -> SourceIR.RawSeq(source.expr)
                source is SourceIR.Opaque && NativeHofNames.isListNative(node.name) ->
                    if (stages.isNotEmpty()) SourceIR.RawList(source.expr) else return null
                source is SourceIR.Opaque ->
                    if (stages.isNotEmpty()) promoteOpaqueSource(source, node.name, true) ?: return null else return null
                else -> source
            }
            val candidate = FusedChainIR(effectiveSource, stages, SinkIR.Sum, node.src)
            return if (isEffectSafe(candidate, allFunctions)) candidate else null
        }
        // For-each (any element-based collection)
        if (NativeHofNames.isAnyElementCollection(node.name, NativeHofNames.OP_FOR_EACH)) {
            val mr = node.arg as? MakeRecordIR ?: return null
            val srcField = NativeHofNames.srcFieldFor(node.name)
            var src: NodeIR? = null; var f: NodeIR? = null
            for (fld in mr.fields) when (fld.name) {
                srcField -> src = fld.value
                "f" -> f = fld.value
            }
            val srcInner = src ?: return null
            val fLambda = asMakeLambda(f) ?: return null
            val (stages, source) = peelSource(srcInner, classifier, allFunctions, ext)
            val effectiveSource = promoteOpaqueSource(source, node.name, stages.isNotEmpty()) ?: return null
            val candidate = FusedChainIR(effectiveSource, stages, SinkIR.ForEach(fLambda), node.src)
            return if (isEffectSafe(candidate, allFunctions)) candidate else null
        }
        // Fold (any element-based collection — fold / fold_left)
        if (node.name == "__native_iter_fold" || node.name == "__native_seq_fold"
            || node.name == "__native_list_fold_left"
            || node.name == "__native_set_fold" || node.name == "__native_sorted_set_fold"
            || node.name == "__native_deque_fold") {
            val mr = node.arg as? MakeRecordIR ?: return null
            val srcField = NativeHofNames.srcFieldFor(node.name)
            var src: NodeIR? = null; var init: NodeIR? = null; var f: NodeIR? = null
            for (fld in mr.fields) when (fld.name) {
                srcField -> src = fld.value
                "init" -> init = fld.value
                "f" -> f = fld.value
            }
            val srcInner = src ?: return null
            val initExpr = init ?: return null
            val fLambda = asMakeLambda(f) ?: return null
            if (!isFoldLambdaEmittable(fLambda, allFunctions)) return null
            val (stages, source) = peelSource(srcInner, classifier, allFunctions, ext)
            val effectiveSource = promoteOpaqueSource(source, node.name, stages.isNotEmpty()) ?: return null
            val candidate = FusedChainIR(effectiveSource, stages, SinkIR.Fold(initExpr, fLambda), node.src)
            return if (isEffectSafe(candidate, allFunctions)) candidate else null
        }
        return null
    }

    /** Handle __native_iter_{count,any,all,find,reduce,to_seq,to_list}.
     *  These sinks are classified but not yet emittable — the method builds
     *  the FusedChainIR, then gates on [isEmittableSink] before returning. */
    private fun tryCanonicalizeNativeSink(
        node: CallNativeIR,
        classifier: ChainHofClassifier,
        allFunctions: Map<Hash, FunctionIR>,
        ext: FrameExtender?,
    ): FusedChainIR? {
        val mr = node.arg as? MakeRecordIR ?: return null
        val candidate: FusedChainIR? = when {
            // ── Size over a CHAIN: count what reaches the sink ──
            //
            // `xs.map(f).size()` measured ~9 500 ps/elem against ~490 for
            // `xs.map(f).fold(+)` at N=1e6 — twenty times, for asking how many
            // instead of what the sum is. `size` was not a sink, so the chain
            // could not fuse and the map MATERIALISED a whole new collection:
            // a box per element read, a box per element written into an
            // `Object[]`, then `SeqStorageFactory.fromArray` scanning all of it
            // with three `instanceof` checks each before unboxing into a typed
            // array. Two allocations and two extra traversals per element.
            //
            // For a chain, `size` is exactly what the Count sink already does —
            // increment per element that survives the stages — so there is no
            // new sink to emit, only a name to recognise.
            //
            // AND IT REFUSES WITHOUT A STAGE, which is the whole correctness of
            // it: `size` of a materialised collection is O(1), and routing that
            // through a counting sink would make it O(n). A chain with no stages
            // is exactly that case, so it is left as the native it was.
            NativeHofNames.isAnyElementCollection(node.name, NativeHofNames.OP_SIZE) &&
                true -> {
                val srcField = NativeHofNames.srcFieldFor(node.name)
                var src: NodeIR? = null
                for (fld in mr.fields) if (fld.name == srcField) src = fld.value
                val srcInner = src ?: return null
                val (stages, source) = peelSource(srcInner, classifier, allFunctions, ext)
                if (stages.isEmpty()) return null
                val effectiveSource = promoteOpaqueSource(source, node.name, stages.isNotEmpty()) ?: return null
                val candidate0 = FusedChainIR(effectiveSource, stages, SinkIR.Count, node.src)
                if (isEffectSafe(candidate0, allFunctions)) candidate0 else null
            }
            // ── Count (predicate → filter + count) ──
            NativeHofNames.isAnyElementCollection(node.name, NativeHofNames.OP_COUNT) -> {
                val srcField = NativeHofNames.srcFieldFor(node.name)
                var src: NodeIR? = null; var p: NodeIR? = null
                for (fld in mr.fields) when (fld.name) {
                    srcField -> src = fld.value; NativeHofNames.P -> p = fld.value
                }
                val srcInner = src ?: return null
                val pLambda = asMakeLambda(p) ?: return null
                val (stages, source) = peelSource(srcInner, classifier, allFunctions, ext)
                val effectiveSource = promoteOpaqueSource(source, node.name, stages.isNotEmpty()) ?: return null
                // The sink's own predicate is applied LAST, after every peeled
                // stage, so under the outer-to-inner convention it goes FIRST.
                // Appending it made it the innermost stage and it filtered the
                // SOURCE: `range{0,5}.map{*10}.count{<40}` answered 5, not 4.
                val allStages = listOf<StageIR>(StageIR.Filter(pLambda)) + stages
                val candidate = FusedChainIR(effectiveSource, allStages, SinkIR.Count, node.src)
                if (isEffectSafe(candidate, allFunctions)) candidate else null
            }
            // ── Any / All / Find / None / Partition (predicate sinks) ──
            NativeHofNames.isAnyElementCollection(node.name, NativeHofNames.OP_ANY) ->
                buildPredicateFusedGeneric(mr, node.name, classifier, allFunctions, node.src, ext) { SinkIR.Any(it) }
            NativeHofNames.isAnyElementCollection(node.name, NativeHofNames.OP_ALL) ->
                buildPredicateFusedGeneric(mr, node.name, classifier, allFunctions, node.src, ext) { SinkIR.All(it) }
            NativeHofNames.isAnyElementCollection(node.name, NativeHofNames.OP_FIND) ->
                buildPredicateFusedGeneric(mr, node.name, classifier, allFunctions, node.src, ext) { SinkIR.Find(it) }
            NativeHofNames.isAnyElementCollection(node.name, NativeHofNames.OP_NONE) ->
                buildPredicateFusedGeneric(mr, node.name, classifier, allFunctions, node.src, ext) { SinkIR.None(it) }
            // Partition returns {pass: T, fail: T} where T matches the source collection.
            // Only fuse for seq/iter where T=Seq (fused loop emits Seq builders).
            // For list/set/deque etc. the result type would change — skip fusion.
            NativeHofNames.isSeqOrIter(node.name, NativeHofNames.OP_PARTITION) ->
                buildPredicateFusedGeneric(mr, node.name, classifier, allFunctions, node.src, ext) { SinkIR.Partition(it) }
            // ── Reduce (function sink) ──
            NativeHofNames.isAnyElementCollection(node.name, NativeHofNames.OP_REDUCE) -> {
                val srcField = NativeHofNames.srcFieldFor(node.name)
                var src: NodeIR? = null; var f: NodeIR? = null
                for (fld in mr.fields) when (fld.name) {
                    srcField -> src = fld.value; NativeHofNames.F -> f = fld.value
                }
                val srcInner = src ?: return null
                val fLambda = asMakeLambda(f) ?: return null
                val (stages, source) = peelSource(srcInner, classifier, allFunctions, ext)
                val effectiveSource = promoteOpaqueSource(source, node.name, stages.isNotEmpty()) ?: return null
                val candidate = FusedChainIR(effectiveSource, stages, SinkIR.Reduce(fLambda), node.src)
                if (isEffectSafe(candidate, allFunctions)) candidate else null
            }
            // ── ToSeq / ToList ──
            NativeHofNames.isAnyElementCollection(node.name, NativeHofNames.OP_TO_SEQ)
                    || NativeHofNames.isAnyElementCollection(node.name, NativeHofNames.OP_TO_LIST) -> {
                val srcField = NativeHofNames.srcFieldFor(node.name)
                var src: NodeIR? = null
                for (fld in mr.fields) { if (fld.name == srcField) src = fld.value }
                val srcInner = src ?: return null
                val sink = if (node.name.endsWith("_to_seq")) SinkIR.ToSeq else SinkIR.ToList
                val (stages, source) = peelSource(srcInner, classifier, allFunctions, ext)
                val effectiveSource = promoteOpaqueSource(source, node.name, stages.isNotEmpty()) ?: return null
                val candidate = FusedChainIR(effectiveSource, stages, sink, node.src)
                if (isEffectSafe(candidate, allFunctions)) candidate else null
            }
            // ── Unary sinks: First / Last / Min / Max ──
            NativeHofNames.isAnyElementCollection(node.name, NativeHofNames.OP_FIRST) ->
                buildUnarySinkFused(mr, node.name, classifier, allFunctions, node.src, ext, SinkIR.First)
            NativeHofNames.isAnyElementCollection(node.name, NativeHofNames.OP_LAST) ->
                buildUnarySinkFused(mr, node.name, classifier, allFunctions, node.src, ext, SinkIR.Last)
            // Min/Max are NOT fused for a container that owns its ordering.
            //
            // The fused sink compares elements with DirectLessThan, which only
            // specializes long/double/String/Frac and throws on anything else.
            // A sorted container, though, was built with the ELEMENT'S OWN `cmp`
            // closure and keeps it inside the data structure, so fusing rewrites
            // `__native_sorted_set_min { s }` into `to_seq { s } |> Min` and
            // silently swaps that comparator for a primitive `<` — a crash for a
            // record or variant element, and a WRONG ANSWER for a newtype whose
            // Ord disagrees with the wrapped type's. It is also slower: the
            // native reads `elements[0]` in O(1), the fused form rescans in O(n).
            //
            // `__native_sorted_set_{min,max}` are in fact the only bare min/max
            // element natives that exist — every other collection's `min` is a
            // Mezze `reduce` over the element's Ord (see seq.mz), which never
            // reaches here — so this guard costs no fusion that was ever
            // profitable. See known-bugs.md BUG 7; BUG 8 was the same family.
            NativeHofNames.isAnyElementCollection(node.name, NativeHofNames.OP_MIN) &&
                !ownsItsOrdering(node.name) ->
                buildUnarySinkFused(mr, node.name, classifier, allFunctions, node.src, ext, SinkIR.Min)
            NativeHofNames.isAnyElementCollection(node.name, NativeHofNames.OP_MAX) &&
                !ownsItsOrdering(node.name) ->
                buildUnarySinkFused(mr, node.name, classifier, allFunctions, node.src, ext, SinkIR.Max)
            // ── Function sinks: MinBy / MaxBy ──
            NativeHofNames.isAnyElementCollection(node.name, NativeHofNames.OP_MIN_BY) ->
                buildFunctionSinkFused(mr, node.name, classifier, allFunctions, node.src, ext) { SinkIR.MinBy(it) }
            NativeHofNames.isAnyElementCollection(node.name, NativeHofNames.OP_MAX_BY) ->
                buildFunctionSinkFused(mr, node.name, classifier, allFunctions, node.src, ext) { SinkIR.MaxBy(it) }
            // ── SumFloat / Product / ProductFloat (unary sinks) ──
            NativeHofNames.isAnyElementCollection(node.name, NativeHofNames.OP_SUM_FLOAT) ->
                buildUnarySinkFused(mr, node.name, classifier, allFunctions, node.src, ext, SinkIR.SumFloat)
            NativeHofNames.isAnyElementCollection(node.name, NativeHofNames.OP_PRODUCT) ->
                buildUnarySinkFused(mr, node.name, classifier, allFunctions, node.src, ext, SinkIR.Product)
            NativeHofNames.isAnyElementCollection(node.name, NativeHofNames.OP_PRODUCT_FLOAT) ->
                buildUnarySinkFused(mr, node.name, classifier, allFunctions, node.src, ext, SinkIR.ProductFloat)
            // ── FindIndex (predicate sink) ──
            NativeHofNames.isAnyElementCollection(node.name, NativeHofNames.OP_FIND_INDEX) ->
                buildPredicateFusedGeneric(mr, node.name, classifier, allFunctions, node.src, ext) { SinkIR.FindIndex(it) }
            // ── Scan ── (returns a collection of the source type — only fuse for seq/iter)
            NativeHofNames.isSeqOrIter(node.name, NativeHofNames.OP_SCAN) -> {
                val srcField = NativeHofNames.srcFieldFor(node.name)
                var src: NodeIR? = null; var init: NodeIR? = null; var f: NodeIR? = null
                for (fld in mr.fields) when (fld.name) {
                    srcField -> src = fld.value
                    NativeHofNames.INIT -> init = fld.value
                    NativeHofNames.F -> f = fld.value
                }
                val srcInner = src ?: return null
                val initExpr = init ?: return null
                val fLambda = asMakeLambda(f) ?: return null
                val (stages, source) = peelSource(srcInner, classifier, allFunctions, ext)
                val effectiveSource = promoteOpaqueSource(source, node.name, stages.isNotEmpty()) ?: return null
                val candidate = FusedChainIR(effectiveSource, stages, SinkIR.Scan(initExpr, fLambda), node.src)
                if (isEffectSafe(candidate, allFunctions)) candidate else null
            }
            else -> null
        }
        // Gate: only return if the emitter can handle the sink.
        if (candidate != null && !isEmittableSink(candidate.sink)) return null
        return candidate
    }

    /** Build a FusedChainIR with a predicate sink for ANY element-based collection.
     *  Uses [promoteOpaqueSource] to handle the collection-specific source promotion. */
    private fun buildPredicateFusedGeneric(
        mr: MakeRecordIR,
        nativeName: String,
        classifier: ChainHofClassifier,
        allFunctions: Map<Hash, FunctionIR>,
        src: SourceInfo,
        ext: FrameExtender?,
        wrap: (MakeLambdaIR) -> SinkIR,
    ): FusedChainIR? {
        val srcField = NativeHofNames.srcFieldFor(nativeName)
        var srcNode: NodeIR? = null; var p: NodeIR? = null
        for (fld in mr.fields) when (fld.name) {
            srcField -> srcNode = fld.value; NativeHofNames.P -> p = fld.value
        }
        val srcInner = srcNode ?: return null
        val pLambda = asMakeLambda(p) ?: return null
        val (stages, source) = peelSource(srcInner, classifier, allFunctions, ext)
        val effectiveSource = promoteOpaqueSource(source, nativeName, stages.isNotEmpty()) ?: return null
        val candidate = FusedChainIR(effectiveSource, stages, wrap(pLambda), src)
        return if (isEffectSafe(candidate, allFunctions)) candidate else null
    }

    /** Build a FusedChainIR with a predicate sink (any/all/find/none/partition) from a native call record.
     *  Legacy: kept for the mono-wrapper peek-through path which passes the native name directly. */
    private fun buildPredicateFused(
        mr: MakeRecordIR,
        nativeName: String,
        classifier: ChainHofClassifier,
        allFunctions: Map<Hash, FunctionIR>,
        src: SourceInfo,
        ext: FrameExtender?,
        wrap: (MakeLambdaIR) -> SinkIR,
    ): FusedChainIR? {
        val srcField = NativeHofNames.srcFieldFor(nativeName)
        var srcNode: NodeIR? = null; var p: NodeIR? = null
        for (fld in mr.fields) when (fld.name) {
            srcField -> srcNode = fld.value; NativeHofNames.P -> p = fld.value
        }
        val srcInner = srcNode ?: return null
        val pLambda = asMakeLambda(p) ?: return null
        val (stages, source) = peelSource(srcInner, classifier, allFunctions, ext)
        val effectiveSource = promoteOpaqueSource(source, nativeName, stages.isNotEmpty()) ?: return null
        val candidate = FusedChainIR(effectiveSource, stages, wrap(pLambda), src)
        return if (isEffectSafe(candidate, allFunctions)) candidate else null
    }

    /** Build a FusedChainIR with a function sink (min_by/max_by) from a native call record. */
    private fun buildFunctionSinkFused(
        mr: MakeRecordIR,
        nativeName: String,
        classifier: ChainHofClassifier,
        allFunctions: Map<Hash, FunctionIR>,
        src: SourceInfo,
        ext: FrameExtender?,
        wrap: (MakeLambdaIR) -> SinkIR,
    ): FusedChainIR? {
        val srcField = NativeHofNames.srcFieldFor(nativeName)
        var srcNode: NodeIR? = null; var f: NodeIR? = null
        for (fld in mr.fields) when (fld.name) {
            srcField -> srcNode = fld.value; NativeHofNames.F -> f = fld.value
        }
        val srcInner = srcNode ?: return null
        val fLambda = asMakeLambda(f) ?: return null
        val (stages, source) = peelSource(srcInner, classifier, allFunctions, ext)
        val effectiveSource = promoteOpaqueSource(source, nativeName, stages.isNotEmpty()) ?: return null
        val candidate = FusedChainIR(effectiveSource, stages, wrap(fLambda), src)
        return if (isEffectSafe(candidate, allFunctions)) candidate else null
    }

    /** Build a FusedChainIR with a unary sink (first/last/min/max) from a native call record. */
    private fun buildUnarySinkFused(
        mr: MakeRecordIR,
        nativeName: String,
        classifier: ChainHofClassifier,
        allFunctions: Map<Hash, FunctionIR>,
        src: SourceInfo,
        ext: FrameExtender?,
        sink: SinkIR,
    ): FusedChainIR? {
        val srcField = NativeHofNames.srcFieldFor(nativeName)
        var srcNode: NodeIR? = null
        for (fld in mr.fields) { if (fld.name == srcField) srcNode = fld.value }
        val srcInner = srcNode ?: return null
        val (stages, source) = peelSource(srcInner, classifier, allFunctions, ext)
        val effectiveSource = promoteOpaqueSource(source, nativeName, stages.isNotEmpty()) ?: return null
        val candidate = FusedChainIR(effectiveSource, stages, sink, src)
        return if (isEffectSafe(candidate, allFunctions)) candidate else null
    }

    /** True when the container keeps an ordering INSIDE the data structure — it
     *  was handed the element's own comparator at construction and reuses it for
     *  every later query. Any rewrite that reaches such a container's elements
     *  through `to_seq` loses that comparator, so ordering-dependent sinks must
     *  not fuse over one. */
    private fun ownsItsOrdering(nativeName: String): Boolean =
        NativeHofNames.isSortedSetNative(nativeName)

    /**
     * Promote an Opaque source based on the native call's collection type.
     * Seq natives → RawSeq, list natives → RawList, set/sorted_set/deque →
     * synthesize a to_seq call → RawSeq, iter → reject (unknown concrete type).
     *
     * THE to_seq ROUTE IS A COPY, AND A COPY MUST BE PAID FOR. `RawSeq` and
     * `RawList` walk the structure where it lies; set, sorted_set and deque have
     * no such walk, so they were promoted by MATERIALISING the whole collection
     * into a Seq first — O(n) allocation on every chain. With stages to fuse,
     * that copy buys a tight loop. With NO stages it buys nothing, and it showed:
     *
     *   bench/cases/fuseall, fold over 1e6 elements, A/B on one CLI with
     *   MEZZE_CHAIN_CANONICALIZE=0 — "off" is the direct walk:
     *
     *     seq     14 763 ->     697 ps/elem   21x   FASTER fused
     *     list    18 252 ->   1 915           9.5x  FASTER fused
     *     deque   10 955 ->  71 508           6.5x  SLOWER fused
     *     set     26 776 -> 188 633           7.0x  SLOWER fused
     *
     * So fusion as shipped made a bare set or deque fold SEVEN TIMES slower than
     * not fusing it. Adding `nel` the same way measured 22x worse and was
     * reverted, which is what sent me to check the collections already on this
     * path.
     *
     * The guard is the one this file already applies to `size`, for the reason
     * stated there: "size on a materialised collection is O(1), and routing it
     * through a counting sink would make it O(n) — a regression dressed as an
     * optimisation". Two of this function's twelve call sites tested
     * `stages.isNotEmpty()` before calling; now the rule lives here, so none can
     * forget it.
     *
     * The STAGED case is left promoting, because that is what the measurement
     * covers — a bare fold. Whether copy-then-fuse beats a direct walk with two
     * callbacks is a separate question, and the answer for all four collections
     * is really a source that walks their own storage, as `RawList` does for
     * `list`. That needs a BDSL loop per storage, which is the follow-up.
     */
    private fun promoteOpaqueSource(
        source: SourceIR,
        nativeName: String,
        hasStages: Boolean,
    ): SourceIR? = when {
        source !is SourceIR.Opaque -> source
        NativeHofNames.isSeqNative(nativeName) -> SourceIR.RawSeq(source.expr)
        NativeHofNames.isListNative(nativeName) -> SourceIR.RawList(source.expr)
        !hasStages -> null  // a copy with nothing to fuse is pure loss
        NativeHofNames.isSetNative(nativeName) ->
            wrapAsSeqSource(source.expr, "__native_set_to_seq", NativeHofNames.S)
        NativeHofNames.isSortedSetNative(nativeName) ->
            wrapAsSeqSource(source.expr, "__native_sorted_set_to_seq", NativeHofNames.S)
        NativeHofNames.isDequeNative(nativeName) ->
            wrapAsSeqSource(source.expr, "__native_deque_to_seq", NativeHofNames.D)
        else -> {
            // THE REFUSAL THAT MATTERS, and this file's own header says it is
            // silent. An Opaque source is not itself a problem — every
            // collection whose concrete type is known gets promoted above. It
            // is reaching HERE, with a source the walk could not type, that
            // makes a pipeline run element-at-a-time with nothing anywhere
            // saying so. Every gap found in this effort arrived looking
            // identical: a chain that simply did not fuse.
            //
            // `MEZZE_TRACE_FUSION=1` names the native and the node, which is
            // what tells those cases apart. The same device as
            // MEZZE_TRACE_SELFTAIL and MEZZE_TRACE_WRAPPER, which between them
            // turned two of these from an afternoon into one run.
            if (System.getenv("MEZZE_TRACE_FUSION") != null) {
                val e = source.expr
                System.err.println(
                    "[fusion] refused: native=" + nativeName +
                        " source=" + e::class.simpleName +
                        (if (e is CallNativeIR) ":" + e.name else "") +
                        (if (e is ReadLocalIR) ":slot=" + e.slot else "") +
                        (if (e is CallIndirectScalarIR || e is CallIndirectIR) ":through-a-slot" else "")
                )
            }
            null  // iter, or a collection whose concrete type is unknown
        }
    }

    /** Wrap a collection expression in a synthetic `to_seq` native call,
     *  producing a RawSeq source. Used for Set/SortedSet/Deque whose
     *  elements are the same as Seq but need a to_seq conversion step. */
    private fun wrapAsSeqSource(expr: NodeIR, toSeqNative: String, srcField: String): SourceIR.RawSeq {
        val call = CallNativeIR(
            name = toSeqNative,
            arg = MakeRecordIR(listOf(FieldIR(srcField, expr)), expr.src),
            src = expr.src,
        )
        return SourceIR.RawSeq(call)
    }

    // ── Phase 3/5: effect-row safety gate ─────────────────────────────
    //
    // Phase 3 (original): reject any chain whose lambdas contain unhandled
    // PerformOpIR nodes. Conservative — prevents fusion of effectful chains.
    //
    // Phase 5 (current): allow effectful chains. The PerformD cached
    // specialization (handler-chain-stable Assumption + DirectCallNode)
    // makes effectful performs inside fused loops fast: handler resolved
    // once and cached for the loop's duration. Performs dispatch through
    // the existing handler chain unchanged during the loop.
    //
    // Still rejects: nothing — all FusedChainIR candidates pass. The
    // correctness argument: fused loops call lambda bodies as separate
    // functions (emitCallOneArg). PerformD inside those bodies dispatches
    // via the per-thread handler chain, which is stable during the loop
    // (no PushHandlerChain/PopHandlerChain in the fused loop driver).
    // HandleIR inside a lambda is balanced (push+pop within one iteration).
    // AbortPerformException propagates out identically to the unfused case.

    @Suppress("UNUSED_PARAMETER")
    private fun isEffectSafe(chain: FusedChainIR, allFunctions: Map<Hash, FunctionIR>): Boolean {
        return true
    }

    /**
     * Extract (sink, source-expr) from a call to a HOF whose classification
     * is [callee]. Handles both scalar-ABI (positional args, possibly with
     * trailing ability-dict arg) and record-ABI (single MakeRecord arg).
     * Returns null on any shape mismatch (partial application, wrong
     * arity, missing fields, etc.).
     */
    private fun extractSinkAndSource(
        call: CallDirectIR,
        callee: ChainHofClassifier.Callee,
    ): Pair<SinkIR, NodeIR>? = when (callee.kind) {
        ChainHofClassifier.HofKind.FOLD -> extractFold(call)
        ChainHofClassifier.HofKind.SUM -> extractSum(call)
        ChainHofClassifier.HofKind.SUM_FLOAT -> extractUnarySink(call, SinkIR.SumFloat)
        ChainHofClassifier.HofKind.PRODUCT -> extractUnarySink(call, SinkIR.Product)
        ChainHofClassifier.HofKind.PRODUCT_FLOAT -> extractUnarySink(call, SinkIR.ProductFloat)
        ChainHofClassifier.HofKind.FOR_EACH -> extractForEach(call)
        ChainHofClassifier.HofKind.COUNT -> extractCount(call)
        ChainHofClassifier.HofKind.REDUCE -> extractReduce(call)
        ChainHofClassifier.HofKind.ANY -> extractPredicateSink(call) { SinkIR.Any(it) }
        ChainHofClassifier.HofKind.ALL -> extractPredicateSink(call) { SinkIR.All(it) }
        ChainHofClassifier.HofKind.FIND -> extractPredicateSink(call) { SinkIR.Find(it) }
        ChainHofClassifier.HofKind.TO_SEQ -> extractUnarySink(call, SinkIR.ToSeq)
        ChainHofClassifier.HofKind.TO_LIST -> extractUnarySink(call, SinkIR.ToList)
        ChainHofClassifier.HofKind.NONE -> extractPredicateSink(call) { SinkIR.None(it) }
        ChainHofClassifier.HofKind.FIRST -> extractUnarySink(call, SinkIR.First)
        ChainHofClassifier.HofKind.LAST -> extractUnarySink(call, SinkIR.Last)
        ChainHofClassifier.HofKind.MIN -> extractUnarySink(call, SinkIR.Min)
        ChainHofClassifier.HofKind.MAX -> extractUnarySink(call, SinkIR.Max)
        ChainHofClassifier.HofKind.MIN_BY -> extractFunctionSink(call) { SinkIR.MinBy(it) }
        ChainHofClassifier.HofKind.MAX_BY -> extractFunctionSink(call) { SinkIR.MaxBy(it) }
        ChainHofClassifier.HofKind.PARTITION -> extractPredicateSink(call) { SinkIR.Partition(it) }
        ChainHofClassifier.HofKind.SCAN -> extractScan(call)
        ChainHofClassifier.HofKind.FIND_INDEX -> extractPredicateSink(call) { SinkIR.FindIndex(it) }
        // MAP/FILTER/TAKE/DROP/FROM_SEQ/TAKE_WHILE/DROP_WHILE aren't sinks —
        // they're peel targets. If we land here we're processing the wrong
        // callsite kind; refuse silently so the peel step handles it.
        ChainHofClassifier.HofKind.MAP,
        ChainHofClassifier.HofKind.FILTER,
        ChainHofClassifier.HofKind.TAKE,
        ChainHofClassifier.HofKind.DROP,
        ChainHofClassifier.HofKind.TAKE_WHILE,
        ChainHofClassifier.HofKind.DROP_WHILE,
        // A STAGE, never a sink: enumerate belongs to the peel, not here.
        ChainHofClassifier.HofKind.ENUMERATE,
        ChainHofClassifier.HofKind.FROM_SEQ -> null
    }

    /** iter::fold / seq::fold — expects (src, init, f) + optional trailing dict. */
    private fun extractFold(call: CallDirectIR): Pair<SinkIR, NodeIR>? {
        val (src, init, fRaw) = extractThreeArgs(call, srcNames = SRC_NAMES) ?: return null
        val fLambda = asMakeLambda(fRaw) ?: return null
        // Captures OK — emit-side handles them via capture slot remap.
        return SinkIR.Fold(init, fLambda) to src
    }

    /**
     * iter::sum { src = X } — equivalent to Fold(0, +). Since Phase 5c added
     * SinkIR.Sum, we can canonicalize the sum callsite directly without
     * synthesizing a folder lambda: return SinkIR.Sum + the raw src expr.
     * BDSL emit routes the resulting FusedChainIR to BuiltinSumRangeLong /
     * BuiltinSumLongSeq exactly like the seq::sum path.
     *
     * Expects arity 1 (record { src }) or 2 (scalar-ABI [src, dict]).
     */
    private fun extractSum(call: CallDirectIR): Pair<SinkIR, NodeIR>? {
        val args = call.args
        val srcExpr: NodeIR = if (call.isScalarABI) {
            // Scalar-ABI: [src] or [src, dict]
            if (args.size !in 1..2) return null
            args[0]
        } else {
            // Record-ABI: single MakeRecord{src}
            if (args.size != 1) return null
            val rec = args[0] as? MakeRecordIR ?: return null
            var src: NodeIR? = null
            for (fld in rec.fields) {
                if (fld.name in SRC_NAMES) src = fld.value
            }
            src ?: return null
        }
        return SinkIR.Sum to srcExpr
    }

    /** iter::for_each — (src, f) + optional trailing dict. */
    private fun extractForEach(call: CallDirectIR): Pair<SinkIR, NodeIR>? {
        val (src, fRaw) = extractTwoArgs(call, srcNames = SRC_NAMES, secondName = "f") ?: return null
        val fLambda = asMakeLambda(fRaw) ?: return null
        // Captures OK — emit-side handles them via capture slot remap.
        return SinkIR.ForEach(fLambda) to src
    }

    /** iter::count — (src, p) + optional trailing dict.
     *  count(xs, p) = filter(xs, p).count — we DON'T fold the predicate
     *  into a Count-with-predicate sink; instead we return src to the
     *  canonicalizer which will peel the source expression. The predicate
     *  is lost here because extractSinkAndSource only returns (sink, srcExpr);
     *  the caller peels stages from srcExpr. For count-with-predicate to work
     *  properly, the caller would need to inject a Filter stage. For now,
     *  handle the simple case: count is only useful as a sink when the
     *  predicate filtering is already a peeled stage in the chain. */
    private fun extractCount(call: CallDirectIR): Pair<SinkIR, NodeIR>? {
        val args = call.args
        // Try to extract just the source (ignore predicate — it should
        // already be a filter stage peeled by the caller).
        val srcExpr: NodeIR = if (call.isScalarABI) {
            if (args.isEmpty()) return null
            args[0]
        } else {
            if (args.size != 1) return null
            val rec = args[0] as? MakeRecordIR ?: return null
            var src: NodeIR? = null
            for (fld in rec.fields) {
                if (fld.name in SRC_NAMES) src = fld.value
            }
            src ?: return null
        }
        return SinkIR.Count to srcExpr
    }

    /** iter::reduce — (src, f) + optional trailing dict. */
    private fun extractReduce(call: CallDirectIR): Pair<SinkIR, NodeIR>? {
        val (src, fRaw) = extractTwoArgs(call, srcNames = SRC_NAMES, secondName = "f") ?: return null
        val fLambda = asMakeLambda(fRaw) ?: return null
        // Captures OK — emit-side handles them via capture slot remap.
        return SinkIR.Reduce(fLambda) to src
    }

    /** any/all/find/none/partition — (src, p) + optional trailing dict. Predicate sinks. */
    private fun extractPredicateSink(
        call: CallDirectIR,
        wrap: (MakeLambdaIR) -> SinkIR,
    ): Pair<SinkIR, NodeIR>? {
        val (src, pRaw) = extractTwoArgs(call, srcNames = SRC_NAMES, secondName = NativeHofNames.P) ?: return null
        val pLambda = asMakeLambda(pRaw) ?: return null
        return wrap(pLambda) to src
    }

    /** min_by/max_by — (src, f) + optional trailing dict. Function sinks. */
    private fun extractFunctionSink(
        call: CallDirectIR,
        wrap: (MakeLambdaIR) -> SinkIR,
    ): Pair<SinkIR, NodeIR>? {
        val (src, fRaw) = extractTwoArgs(call, srcNames = SRC_NAMES, secondName = NativeHofNames.F) ?: return null
        val fLambda = asMakeLambda(fRaw) ?: return null
        return wrap(fLambda) to src
    }

    /** scan(src, init, f) — fold-like sink producing seq. */
    private fun extractScan(call: CallDirectIR): Pair<SinkIR, NodeIR>? {
        val (src, init, fRaw) = extractThreeArgs(call, srcNames = SRC_NAMES) ?: return null
        val fLambda = asMakeLambda(fRaw) ?: return null
        return SinkIR.Scan(init, fLambda) to src
    }

    /** to_seq/to_list — unary (src) sinks. */
    private fun extractUnarySink(call: CallDirectIR, sink: SinkIR): Pair<SinkIR, NodeIR>? {
        val args = call.args
        val srcExpr: NodeIR = if (call.isScalarABI) {
            if (args.isEmpty()) return null
            args[0]
        } else {
            if (args.size != 1) return null
            val rec = args[0] as? MakeRecordIR ?: return null
            var src: NodeIR? = null
            for (fld in rec.fields) {
                if (fld.name in SRC_NAMES) src = fld.value
            }
            src ?: return null
        }
        return sink to srcExpr
    }

    /** Field names that mean "the collection source position". */
    private val SRC_NAMES = NativeHofNames.SRC_FIELDS

    /**
     * Extract 3 positional or named args (source, init, f) from a call.
     * Accepts trailing extra positional args (ability dict slots).
     */
    private fun extractThreeArgs(
        call: CallDirectIR,
        srcNames: Set<String>,
    ): Triple<NodeIR, NodeIR, NodeIR>? {
        val args = call.args
        if (call.isScalarABI) {
            // Scalar ABI: [src, init, f] or [src, init, f, dict].
            if (args.size != 3 && args.size != 4) return null
            return Triple(args[0], args[1], args[2])
        }
        // Record ABI: single MakeRecord with fields [src|xs|it, init, f].
        if (args.size != 1) return null
        val rec = args[0] as? MakeRecordIR ?: return null
        var src: NodeIR? = null; var init: NodeIR? = null; var f: NodeIR? = null
        for (fld in rec.fields) {
            when (fld.name) {
                in srcNames -> src = fld.value
                "init" -> init = fld.value
                "f" -> f = fld.value
            }
        }
        return if (src != null && init != null && f != null) Triple(src, init, f) else null
    }

    private fun extractTwoArgs(
        call: CallDirectIR,
        srcNames: Set<String>,
        secondName: String,
    ): Pair<NodeIR, NodeIR>? {
        val args = call.args
        if (call.isScalarABI) {
            if (args.size != 2 && args.size != 3) return null
            return args[0] to args[1]
        }
        if (args.size != 1) return null
        val rec = args[0] as? MakeRecordIR ?: return null
        var src: NodeIR? = null; var second: NodeIR? = null
        for (fld in rec.fields) {
            when (fld.name) {
                in srcNames -> src = fld.value
                secondName -> second = fld.value
            }
        }
        return if (src != null && second != null) src to second else null
    }

    /**
     * Fold a `drop` that is applied directly to a counted range into the
     * range's start, so the stage disappears.
     *
     * `range{start, end}` yields `start + i`, so skipping the first k elements
     * is exactly `range{start + k, end}` — and when k exceeds the length the
     * element count `end - start` goes negative, which the loops already treat
     * as empty, so an over-drop needs no clamp.
     *
     * WHY IT IS WORTH A PEEPHOLE: `FusedLongPipeline` has no drop stage at all,
     * so one drop sent the whole pipeline to the general scalar loop — measured
     * 658 ps/elem for `drop+map` against 88 for the same map alone, a 7x cliff
     * bought by a stage that does nothing but move the start. Folding it keeps
     * the vectorised path.
     *
     * ONLY THE FIRST-APPLIED STAGE QUALIFIES. Stages are outer-to-inner, so the
     * innermost is the LAST element: a drop with a map or filter below it
     * applies to transformed or filtered elements, where the range's own
     * positions no longer say which elements to skip.
     *
     * Step ranges are left alone. `drop` on one is `start + k*step`, which is
     * expressible, but its element count is computed differently and that is
     * not verified here — a stepped range keeps the general loop.
     */
    private fun foldLeadingDropIntoRange(
        stages: List<StageIR>,
        source: SourceIR,
    ): Pair<List<StageIR>, SourceIR> {
        var stagesOut = stages
        var src = source
        while (stagesOut.isNotEmpty()) {
            val innermost = stagesOut.last()
            if (innermost !is StageIR.Drop) break
            val range = src as? SourceIR.RangeSeq ?: break
            src = SourceIR.RangeSeq(
                InlineBinaryOpIR(InlineOp.ADD, range.start, innermost.n, range.start.src),
                range.end,
            )
            stagesOut = stagesOut.dropLast(1)
        }
        return stagesOut to src
    }

    /**
     * Peel iter::map / iter::filter / iter::from_seq layers off
     * [sourceExpr] and classify the resulting terminal expression as
     * a [SourceIR].
     *
     * STAGE ORDER IS OUTER-TO-INNER — `stages[0]` is the stage closest to the
     * SINK, i.e. the one applied LAST. The peel walks the nesting inward and
     * `xs.map(f).filter(p)` nests as `filter(map(xs))`, so the predicate is met
     * first and lands at index 0. `BytecodeCompiler.emitCanonicalIterChain`
     * reverses the list once it has classified the stages, and from there on
     * everything downstream — the fused loop's emission order, the
     * `filterFirst` flag, the take-after-filter refusal — reads application
     * order.
     *
     * ANYTHING THAT BUILDS A STAGE LIST BY HAND MUST MATCH THIS. A stage that
     * runs LAST goes at the FRONT. Appending one instead makes it run FIRST,
     * which is how `range{0,5}.map{*10}.count{<40}` counted RAW elements and
     * answered 5 where the answer is 4 — the predicate the user wrote for
     * mapped values was tested against the source. A wrong number, no
     * diagnostic.
     *
     * MVP scope: handles CallDirect to iter::map/filter/from_seq only.
     * Inlined MakeRecord descriptor shapes (post-inliner) are the
     * next step — TODO.
     */
    private fun peelSource(
        sourceExpr: NodeIR,
        classifier: ChainHofClassifier,
        allFunctions: Map<Hash, FunctionIR> = emptyMap(),
        ext: FrameExtender? = null,
    ): Pair<List<StageIR>, SourceIR> {
        val stages = mutableListOf<StageIR>()
        var current = sourceExpr
        var guard = 0
        while (guard < 16) {
            guard++
            // Try each peel in priority order. If any fires, update
            // `current` to the peeled inner expression and re-loop.
            val mapPeeled = tryPeelIterMap(current, classifier)
            if (mapPeeled != null) {
                stages.add(StageIR.Map(mapPeeled.first))
                current = mapPeeled.second
                continue
            }
            val filterPeeled = tryPeelIterFilter(current, classifier)
            if (filterPeeled != null) {
                stages.add(StageIR.Filter(filterPeeled.first))
                current = filterPeeled.second
                continue
            }
            val enumPeeled = tryPeelIterEnumerate(current, classifier)
            if (enumPeeled != null) {
                stages.add(StageIR.Enumerate)
                current = enumPeeled
                continue
            }
            val takePeeled = tryPeelIterTake(current, classifier)
            if (takePeeled != null) {
                stages.add(StageIR.Take(takePeeled.first))
                current = takePeeled.second
                continue
            }
            val dropPeeled = tryPeelIterDrop(current, classifier)
            if (dropPeeled != null) {
                stages.add(StageIR.Drop(dropPeeled.first))
                current = dropPeeled.second
                continue
            }
            val takeWhilePeeled = tryPeelTakeWhile(current, classifier)
            if (takeWhilePeeled != null) {
                stages.add(StageIR.TakeWhile(takeWhilePeeled.first))
                current = takeWhilePeeled.second
                continue
            }
            val dropWhilePeeled = tryPeelDropWhile(current, classifier)
            if (dropWhilePeeled != null) {
                stages.add(StageIR.DropWhile(dropWhilePeeled.first))
                current = dropWhilePeeled.second
                continue
            }
            val fromSeqPeeled = tryPeelIterFromSeq(current, classifier)
            if (fromSeqPeeled != null) {
                // from_seq terminates the iter chain — the peeled inner is
                // now a Seq expression. Classify it and return.
                return foldLeadingDropIntoRange(stages, classifyTerminalSource(fromSeqPeeled, allFunctions))
            }
            // Nothing peeled. If this is a call to a forwarding wrapper,
            // look through it and try again — the stages may be on the other
            // side of the call. Without this retry a pipeline split across a
            // function boundary classified its source as Opaque and fused
            // nothing. Bounded by the same `guard` as every other peel.
            if (current is CallDirectIR) {
                val peeked = peekThroughMonoWrapper(current, allFunctions, ext)
                if (peeked != null) {
                    current = peeked
                    continue
                }
            }
            // Nothing peeled — treat current as the terminal source.
            return foldLeadingDropIntoRange(stages, classifyTerminalSource(current, allFunctions))
        }
        // Guard exhausted — chain too deep. Bail with what we have.
        return foldLeadingDropIntoRange(stages, classifyTerminalSource(current, allFunctions))
    }

    /** Recognize `__native_seq_range` / `__native_iter_range` for a fusable
     *  counted loop, or wrap anything else as RawSeq (seq indexed loop)
     *  or Opaque (unknown). Also peeks through CallDirect mono wrappers
     *  that the TrivialWrapperInliner didn't inline (same pattern as
     *  peekThroughMonoWrapper: baked MakeLambdaIR prevents inlining). */
    private fun classifyTerminalSource(
        expr: NodeIR,
        allFunctions: Map<Hash, FunctionIR> = emptyMap(),
    ): SourceIR {
        val effective = if (expr is CallDirectIR && allFunctions.isNotEmpty()) {
            peekThroughMonoWrapper(expr, allFunctions, /*ext=*/ null) ?: expr
        } else expr
        if (effective is CallNativeIR
            && (effective.name == "__native_seq_range" || effective.name == "__native_iter_range")) {
            // Both scalar-ABI (args=[start, end]) and record-ABI (arg
            // = MakeRecord{start, end}) shapes exist.
            if (effective.isScalarABI && effective.args.size == 2) {
                return SourceIR.RangeSeq(effective.args[0], effective.args[1])
            }
            val rec = effective.arg as? MakeRecordIR
            if (rec != null) {
                var start: NodeIR? = null; var end: NodeIR? = null
                for (fld in rec.fields) {
                    when (fld.name) {
                        "start" -> start = fld.value
                        "end" -> end = fld.value
                    }
                }
                if (start != null && end != null) return SourceIR.RangeSeq(start, end)
            }
        }
        if (effective is CallNativeIR
            && (effective.name == "__native_seq_range_step" || effective.name == "__native_iter_range_step")) {
            if (effective.isScalarABI && effective.args.size == 3) {
                return SourceIR.RangeStepSeq(effective.args[0], effective.args[1], effective.args[2])
            }
            val rec = effective.arg as? MakeRecordIR
            if (rec != null) {
                var start: NodeIR? = null; var end: NodeIR? = null; var step: NodeIR? = null
                for (fld in rec.fields) {
                    when (fld.name) {
                        "start" -> start = fld.value
                        "end" -> end = fld.value
                        "step" -> step = fld.value
                    }
                }
                if (start != null && end != null && step != null) {
                    return SourceIR.RangeStepSeq(start, end, step)
                }
            }
        }
        // Only __native_seq_* calls (except range, handled above) produce
        // MezzeSeq values. __native_list_*, __native_deque_*, etc. produce
        // non-Seq types — BuiltinSumLongSeq/SeqSize would crash on them.
        // Tag only seq-producing natives as RawSeq; everything else is Opaque.
        if (effective is CallNativeIR && effective.name.startsWith("__native_seq_")) {
            return SourceIR.RawSeq(effective)
        }
        return SourceIR.Opaque(expr)
    }

    /**
     * iter::map has params `[src, f]`. Recognize both call forms:
     * - CallDirectIR(iter::map) with args [srcExpr, fExpr] (scalar-ABI)
     *   or single MakeRecord{src, f} (record-ABI)
     * - CallDirectIR may also have a trailing ability-dict arg (3 args
     *   scalar-ABI, 2 fields record-ABI + extra…) — accept.
     */
    private fun tryPeelIterMap(
        node: NodeIR,
        classifier: ChainHofClassifier,
    ): Pair<MakeLambdaIR, NodeIR>? {
        // Native map stage for any element-based collection — record-ABI only.
        if (node is CallNativeIR
            && NativeHofNames.isAnyElementCollection(node.name, NativeHofNames.OP_MAP)
            && !node.isScalarABI) {
            val mr = node.arg as? MakeRecordIR ?: return null
            val srcField = NativeHofNames.srcFieldFor(node.name)
            var src: NodeIR? = null; var f: NodeIR? = null
            for (fld in mr.fields) when (fld.name) {
                srcField -> src = fld.value
                "f"  -> f  = fld.value
            }
            val srcInner = src ?: return null
            val fLambda = asMakeLambda(f) ?: return null
            // Captures are OK: emit-side classifier + emit remap captured
            // reads to the enclosing frame slots (captures[i].slot).
            return fLambda to srcInner
        }
        if (node !is CallDirectIR) return null
        val callee = classifier.lookup(node.targetHash) ?: return null
        if (callee.kind != ChainHofClassifier.HofKind.MAP) return null
        val extracted = extractTwoArgs(node, srcNames = SRC_NAMES, secondName = "f") ?: return null
        val (srcInner, fRaw) = extracted
        val fLambda = asMakeLambda(fRaw) ?: return null
        // Captures OK; see comment above.
        return fLambda to srcInner
    }

    private fun tryPeelIterFilter(
        node: NodeIR,
        classifier: ChainHofClassifier,
    ): Pair<MakeLambdaIR, NodeIR>? {
        // Native filter stage for any element-based collection — record-ABI only.
        if (node is CallNativeIR
            && NativeHofNames.isAnyElementCollection(node.name, NativeHofNames.OP_FILTER)
            && !node.isScalarABI) {
            val mr = node.arg as? MakeRecordIR ?: return null
            val srcField = NativeHofNames.srcFieldFor(node.name)
            var src: NodeIR? = null; var p: NodeIR? = null
            for (fld in mr.fields) when (fld.name) {
                srcField -> src = fld.value
                "p"  -> p  = fld.value
            }
            val srcInner = src ?: return null
            val pLambda = asMakeLambda(p) ?: return null
            // Captures OK; see comment on tryPeelIterMap.
            return pLambda to srcInner
        }
        if (node !is CallDirectIR) return null
        val callee = classifier.lookup(node.targetHash) ?: return null
        if (callee.kind != ChainHofClassifier.HofKind.FILTER) return null
        val extracted = extractTwoArgs(node, srcNames = SRC_NAMES, secondName = "p") ?: return null
        val (srcInner, pRaw) = extracted
        val pLambda = asMakeLambda(pRaw) ?: return null
        // Captures OK; see comment above.
        return pLambda to srcInner
    }

    /** take { src = <src>, n = <count> } — returns (nExpr, srcExpr). */
    /**
     * Peel an `enumerate` layer, which has a source and no callback.
     *
     * Returns the inner source. The stage itself carries nothing: the index is
     * the loop's counter, and the `{i, val}` record it nominally produces is
     * never built — the emitter resolves a downstream `x.val` to the element and
     * `x.i` to that counter. A chain containing `.enumerate{}` previously could
     * not be peeled at all, so it fused nothing and allocated a record per
     * element: 124247 ps/elem against 93 for the shapes that fuse.
     */
    private fun tryPeelIterEnumerate(
        node: NodeIR,
        classifier: ChainHofClassifier,
    ): NodeIR? {
        if (node is CallNativeIR
            && NativeHofNames.isAnyElementCollection(node.name, NativeHofNames.OP_ENUMERATE)
            && !node.isScalarABI) {
            val mr = node.arg as? MakeRecordIR ?: return null
            if (mr.fields.size != 1) return null
            val fld = mr.fields[0]
            if (fld.name !in NativeHofNames.SRC_FIELDS) return null
            return fld.value
        }
        if (node !is CallDirectIR) return null
        val callee = classifier.lookup(node.targetHash) ?: return null
        if (callee.kind != ChainHofClassifier.HofKind.ENUMERATE) return null
        if (node.isScalarABI) {
            if (node.args.size != 1) return null
            return node.args[0]
        }
        val rec = node.args.singleOrNull() as? MakeRecordIR ?: return null
        val srcFld = rec.fields.singleOrNull { it.name in SRC_NAMES } ?: return null
        return srcFld.value
    }

    private fun tryPeelIterTake(
        node: NodeIR,
        classifier: ChainHofClassifier,
    ): Pair<NodeIR, NodeIR>? {
        // Native take for any element-based collection — record-ABI only.
        if (node is CallNativeIR
            && NativeHofNames.isAnyElementCollection(node.name, NativeHofNames.OP_TAKE)
            && !node.isScalarABI) {
            val mr = node.arg as? MakeRecordIR ?: return null
            val srcField = NativeHofNames.srcFieldFor(node.name)
            var src: NodeIR? = null; var n: NodeIR? = null
            for (fld in mr.fields) when (fld.name) {
                srcField -> src = fld.value
                "n"  -> n  = fld.value
            }
            val srcInner = src ?: return null
            val nExpr = n ?: return null
            return nExpr to srcInner
        }
        if (node !is CallDirectIR) return null
        val callee = classifier.lookup(node.targetHash) ?: return null
        if (callee.kind != ChainHofClassifier.HofKind.TAKE) return null
        val extracted = extractTwoArgs(node, srcNames = SRC_NAMES, secondName = "n") ?: return null
        return extracted.second to extracted.first
    }

    /** drop { src = <src>, n = <count> } — returns (nExpr, srcExpr). */
    private fun tryPeelIterDrop(
        node: NodeIR,
        classifier: ChainHofClassifier,
    ): Pair<NodeIR, NodeIR>? {
        // Native drop for any element-based collection — record-ABI only.
        if (node is CallNativeIR
            && NativeHofNames.isAnyElementCollection(node.name, NativeHofNames.OP_DROP)
            && !node.isScalarABI) {
            val mr = node.arg as? MakeRecordIR ?: return null
            val srcField = NativeHofNames.srcFieldFor(node.name)
            var src: NodeIR? = null; var n: NodeIR? = null
            for (fld in mr.fields) when (fld.name) {
                srcField -> src = fld.value
                "n"  -> n  = fld.value
            }
            val srcInner = src ?: return null
            val nExpr = n ?: return null
            return nExpr to srcInner
        }
        if (node !is CallDirectIR) return null
        val callee = classifier.lookup(node.targetHash) ?: return null
        if (callee.kind != ChainHofClassifier.HofKind.DROP) return null
        val extracted = extractTwoArgs(node, srcNames = SRC_NAMES, secondName = NativeHofNames.N) ?: return null
        return extracted.second to extracted.first
    }

    /** take_while { src = <src>, p = <pred> } — returns (predLambda, srcExpr). */
    private fun tryPeelTakeWhile(
        node: NodeIR,
        classifier: ChainHofClassifier,
    ): Pair<MakeLambdaIR, NodeIR>? {
        if (node is CallNativeIR
            && NativeHofNames.isAnyElementCollection(node.name, NativeHofNames.OP_TAKE_WHILE)
            && !node.isScalarABI) {
            val mr = node.arg as? MakeRecordIR ?: return null
            var src: NodeIR? = null; var p: NodeIR? = null
            for (fld in mr.fields) when (fld.name) {
                in SRC_NAMES -> src = fld.value
                NativeHofNames.P -> p = fld.value
            }
            val srcInner = src ?: return null
            val pLambda = asMakeLambda(p) ?: return null
            return pLambda to srcInner
        }
        if (node !is CallDirectIR) return null
        val callee = classifier.lookup(node.targetHash) ?: return null
        if (callee.kind != ChainHofClassifier.HofKind.TAKE_WHILE) return null
        val extracted = extractTwoArgs(node, srcNames = SRC_NAMES, secondName = NativeHofNames.P) ?: return null
        val pLambda = asMakeLambda(extracted.second) ?: return null
        return pLambda to extracted.first
    }

    /** drop_while { src = <src>, p = <pred> } — returns (predLambda, srcExpr). */
    private fun tryPeelDropWhile(
        node: NodeIR,
        classifier: ChainHofClassifier,
    ): Pair<MakeLambdaIR, NodeIR>? {
        if (node is CallNativeIR
            && NativeHofNames.isAnyElementCollection(node.name, NativeHofNames.OP_DROP_WHILE)
            && !node.isScalarABI) {
            val mr = node.arg as? MakeRecordIR ?: return null
            var src: NodeIR? = null; var p: NodeIR? = null
            for (fld in mr.fields) when (fld.name) {
                in SRC_NAMES -> src = fld.value
                NativeHofNames.P -> p = fld.value
            }
            val srcInner = src ?: return null
            val pLambda = asMakeLambda(p) ?: return null
            return pLambda to srcInner
        }
        if (node !is CallDirectIR) return null
        val callee = classifier.lookup(node.targetHash) ?: return null
        if (callee.kind != ChainHofClassifier.HofKind.DROP_WHILE) return null
        val extracted = extractTwoArgs(node, srcNames = SRC_NAMES, secondName = NativeHofNames.P) ?: return null
        val pLambda = asMakeLambda(extracted.second) ?: return null
        return pLambda to extracted.first
    }

    /**
     * iter::from_seq has params `[xs]`. Recognize:
     * - CallDirectIR(iter::from_seq) with arg [xsExpr] (scalar) or
     *   MakeRecord{xs} (record)
     * - Also the inlined descriptor form: MakeRecordIR{xs, i=0} —
     *   TrivialWrapperInliner may unfold from_seq at the call site.
     */
    private fun tryPeelIterFromSeq(
        node: NodeIR,
        classifier: ChainHofClassifier,
    ): NodeIR? {
        // Native iter::from_seq { xs = ... }
        if (node is CallNativeIR && node.name == "__native_iter_from_seq" && !node.isScalarABI) {
            val mr = node.arg as? MakeRecordIR ?: return null
            for (fld in mr.fields) {
                if (fld.name == "xs") return fld.value
            }
            return null
        }
        if (node is CallDirectIR) {
            val callee = classifier.lookup(node.targetHash)
            if (callee != null && callee.kind == ChainHofClassifier.HofKind.FROM_SEQ) {
                if (node.isScalarABI && node.args.size == 1) return node.args[0]
                val rec = node.args.firstOrNull() as? MakeRecordIR
                if (rec != null) {
                    for (fld in rec.fields) {
                        if (fld.name == "xs") return fld.value
                    }
                }
            }
        }
        // Inlined SeqIter descriptor: MakeRecord{xs, i=0}.
        if (node is MakeRecordIR && node.fields.size == 2) {
            var xsField: NodeIR? = null
            var iIsZero = false
            var sawI = false
            for (fld in node.fields) {
                when (fld.name) {
                    "xs" -> xsField = fld.value
                    "i" -> {
                        sawI = true
                        if (fld.value is LongLitIR && (fld.value as LongLitIR).value == 0L) iIsZero = true
                    }
                }
            }
            if (xsField != null && sawI && iIsZero) return xsField
        }
        return null
    }

    /**
     * Generic structural recursion — same shape as
     * [TrivialWrapperInliner.recurseChildren]. Small duplication rather
     * than exposing a public util until we have more consumers.
     */
    private fun recurseChildren(node: NodeIR, f: (NodeIR) -> NodeIR): NodeIR =
        when (node) {
            is LongLitIR, is DoubleLitIR, is BoolLitIR, is StringLitIR,
            is UnitLitIR, is CharLitIR, is FracLitIR,
            is ReadLocalIR, is ReadCaptureIR, is ReadArgIR,
            is ReadGlobalByHashIR, is ReadGlobalFnByHashIR,
            is EffectBindingIR, is MakeLambdaIR -> node

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
            is WriteLocalIR -> { val nv = f(node.value); if (nv !== node.value) node.copy(value = nv) else node }
            is CallDirectIR -> {
                val newArgs = mapIfChanged(node.args, f)
                if (newArgs !== node.args) node.copy(args = newArgs) else node
            }
            is PartialApplyDirectIR -> {
                val newFields = mapFieldsIfChanged(node.partialFields, f)
                if (newFields !== node.partialFields) node.copy(partialFields = newFields) else node
            }
            is CallLocalIR -> { val na = f(node.arg); if (na !== node.arg) node.copy(arg = na) else node }
            is CallNativeIR -> {
                val na = f(node.arg)
                var changed = na !== node.arg
                val newArgs = if (node.args.isNotEmpty()) node.args.map { c -> val nc = f(c); if (nc !== c) changed = true; nc } else node.args
                if (changed) node.copy(arg = na, args = newArgs) else node
            }
            is CallIndirectIR -> {
                val nf = f(node.function); val na = f(node.arg)
                if (nf !== node.function || na !== node.arg) node.copy(function = nf, arg = na) else node
            }
            is InspectIR -> { val nv = f(node.value); if (nv !== node.value) node.copy(value = nv) else node }
            is CallIndirectScalarIR -> {
                val nf = f(node.function); val newArgs = mapIfChanged(node.args, f)
                if (nf !== node.function || newArgs !== node.args) node.copy(function = nf, args = newArgs) else node
            }
            is DictMethodCallIR -> { val na = f(node.arg); if (na !== node.arg) node.copy(arg = na) else node }
            is InlineBinaryOpIR -> {
                val nl = f(node.left); val nr = f(node.right)
                if (nl !== node.left || nr !== node.right) node.copy(left = nl, right = nr) else node
            }
            is SpecializedCallIR -> { val newArgs = mapIfChanged(node.args, f); if (newArgs !== node.args) node.copy(args = newArgs) else node }
            is MakeRecordIR -> { val newFields = mapFieldsIfChanged(node.fields, f); if (newFields !== node.fields) node.copy(fields = newFields) else node }
            is ReadFieldIR -> { val nr = f(node.record); if (nr !== node.record) node.copy(record = nr) else node }
            is RecordUpdateIR -> {
                val nr = f(node.record); val newUpdates = mapFieldsIfChanged(node.updates, f)
                if (nr !== node.record || newUpdates !== node.updates) node.copy(record = nr, updates = newUpdates) else node
            }
            is MakeVariantIR -> { val np = node.payload?.let(f); if (np !== node.payload) node.copy(payload = np) else node }
            is MatchIR -> {
                val ns = f(node.scrutinee)
                var anyChanged = ns !== node.scrutinee
                val newCases = node.cases.map { c ->
                    val nb = f(c.body); val ng = c.guard?.let(f)
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
                if (nc !== node.cond || nt !== node.thenBr || ne !== node.elseBr) node.copy(cond = nc, thenBr = nt, elseBr = ne) else node
            }
            is BlockIR -> {
                var stmtsChanged = false
                val newStmts = node.stmts.map { s ->
                    when (s) {
                        is StmtIR.Let -> { val nv = f(s.value); if (nv !== s.value) { stmtsChanged = true; s.copy(value = nv) } else s }
                        is StmtIR.Expr -> { val nn = f(s.node); if (nn !== s.node) { stmtsChanged = true; s.copy(node = nn) } else s }
                    }
                }
                val newResult = f(node.result)
                if (stmtsChanged || newResult !== node.result) node.copy(stmts = newStmts, result = newResult) else node
            }
            is HandleIR -> { val nb = f(node.body); if (nb !== node.body) node.copy(body = nb) else node }
            is PerformOpIR -> { val na = f(node.arg); if (na !== node.arg) node.copy(arg = na) else node }
            is MakeSeqIR -> {
                var anyChanged = false
                val newEls = node.elements.map { el ->
                    when (el) {
                        is SeqElementIR.Regular -> { val nn = f(el.node); if (nn !== el.node) { anyChanged = true; SeqElementIR.Regular(nn) } else el }
                        is SeqElementIR.Spread -> { val nn = f(el.node); if (nn !== el.node) { anyChanged = true; SeqElementIR.Spread(nn) } else el }
                    }
                }
                if (anyChanged) node.copy(elements = newEls) else node
            }
            is SeqGetIR -> {
                val ns = f(node.seq); val ni = f(node.index)
                if (ns !== node.seq || ni !== node.index) node.copy(seq = ns, index = ni) else node
            }
            is FusedChainIR -> {
                // Inner canonicalization already done bottom-up; descend
                // into source expression, sink init, and take/drop count exprs.
                val newSource: SourceIR = when (val s = node.source) {
                    is SourceIR.RangeSeq -> {
                        val nStart = f(s.start); val nEnd = f(s.end)
                        if (nStart !== s.start || nEnd !== s.end) SourceIR.RangeSeq(nStart, nEnd) else s
                    }
                    is SourceIR.RangeStepSeq -> {
                        val nStart = f(s.start); val nEnd = f(s.end); val nStep = f(s.step)
                        if (nStart !== s.start || nEnd !== s.end || nStep !== s.step) SourceIR.RangeStepSeq(nStart, nEnd, nStep) else s
                    }
                    is SourceIR.RawSeq -> { val nXs = f(s.xs); if (nXs !== s.xs) SourceIR.RawSeq(nXs) else s }
                    is SourceIR.RawList -> { val nXs = f(s.xs); if (nXs !== s.xs) SourceIR.RawList(nXs) else s }
                    is SourceIR.Opaque -> { val nE = f(s.expr); if (nE !== s.expr) SourceIR.Opaque(nE) else s }
                }
                var stagesChanged = false
                val newStages: List<StageIR> = node.stages.map { stage ->
                    when (stage) {
                        StageIR.Enumerate -> stage
                        is StageIR.Map, is StageIR.Filter,
                        is StageIR.TakeWhile, is StageIR.DropWhile -> stage
                        is StageIR.Take -> { val nn = f(stage.n); if (nn !== stage.n) { stagesChanged = true; StageIR.Take(nn) } else stage }
                        is StageIR.Drop -> { val nn = f(stage.n); if (nn !== stage.n) { stagesChanged = true; StageIR.Drop(nn) } else stage }
                    }
                }
                val newSink: SinkIR = when (val sk = node.sink) {
                    is SinkIR.Fold -> { val nInit = f(sk.init); if (nInit !== sk.init) SinkIR.Fold(nInit, sk.f) else sk }
                    is SinkIR.Scan -> { val nInit = f(sk.init); if (nInit !== sk.init) SinkIR.Scan(nInit, sk.f) else sk }
                    is SinkIR.ForEach, is SinkIR.Reduce, is SinkIR.Any, is SinkIR.All, is SinkIR.Find -> sk
                    is SinkIR.FindIndex, is SinkIR.None, is SinkIR.MinBy, is SinkIR.MaxBy, is SinkIR.Partition -> sk
                    SinkIR.Sum, SinkIR.SumFloat, SinkIR.Product, SinkIR.ProductFloat -> sk
                    SinkIR.Count, SinkIR.ToSeq, SinkIR.ToList -> sk
                    SinkIR.First, SinkIR.Last, SinkIR.Min, SinkIR.Max -> sk
                }
                val newFallback = node.fallback?.let(f)
                if (newSource !== node.source || newSink !== node.sink || stagesChanged || newFallback !== node.fallback)
                    node.copy(source = newSource, stages = if (stagesChanged) newStages else node.stages, sink = newSink, fallback = newFallback) else node
            }
        }

    private fun mapIfChanged(xs: List<NodeIR>, f: (NodeIR) -> NodeIR): List<NodeIR> {
        var changed = false
        val out = xs.map { x -> val n = f(x); if (n !== x) changed = true; n }
        return if (changed) out else xs
    }

    private fun mapFieldsIfChanged(xs: List<FieldIR>, f: (NodeIR) -> NodeIR): List<FieldIR> {
        var changed = false
        val out = xs.map { fld -> val nv = f(fld.value); if (nv !== fld.value) { changed = true; fld.copy(value = nv) } else fld }
        return if (changed) out else xs
    }
}


/**
 * Fingerprint-based classifier for stdlib iter/seq HOFs. Built once per
 * canonicalizer run. Keyed on FunctionIR.targetHash; the fingerprint
 * check (module basename + paramNames + fn name) is applied at build
 * time so the run-loop is a cheap map lookup.
 */
internal class ChainHofClassifier private constructor(
    private val table: Map<Hash, Callee>,
) {

    internal enum class HofKind {
        FOLD, SUM, SUM_FLOAT, PRODUCT, PRODUCT_FLOAT, FOR_EACH, MAP, FILTER, TAKE, DROP, FROM_SEQ,
        COUNT, REDUCE, ANY, ALL, FIND, FIND_INDEX, TO_SEQ, TO_LIST,
        TAKE_WHILE, DROP_WHILE, NONE, FIRST, LAST, MIN, MAX,
        MIN_BY, MAX_BY, PARTITION, SCAN,
        ENUMERATE
    }
    internal data class Callee(val kind: HofKind)

    fun isEmpty(): Boolean = table.isEmpty()

    fun lookup(hash: Hash?): Callee? = if (hash == null) null else table[hash]

    companion object {
        /**
         * Build the classifier from both maps. Classifies ANY function
         * whose (name, paramNames) structurally matches a known HOF
         * shape — generic stdlib originals AND monomorphized
         * specializations (which have empty names but identical param
         * layouts). The structural fingerprint is restrictive enough
         * that accidental matches on user code are harmless: downstream
         * extractors + source peelers reject anything that isn't a real
         * fusable chain.
         */
        fun build(
            functions: Map<Hash, FunctionIR>,
            stdlibSnapshot: Map<Hash, FunctionIR>,
        ): ChainHofClassifier {
            val out = HashMap<Hash, Callee>()
            for ((hash, fn) in stdlibSnapshot) {
                val kind = classifyStructural(fn) ?: continue
                out[hash] = Callee(kind)
            }
            for ((hash, fn) in functions) {
                if (hash in out) continue
                val kind = classifyStructural(fn) ?: continue
                out[hash] = Callee(kind)
            }
            return ChainHofClassifier(out)
        }

        /**
         * Purely structural classifier — matches by (name, paramNames).
         * No source-file matching: stdlib membership is enforced by the
         * caller ([build]), so only known-stdlib functions reach this.
         *
         * Both the pre-mono generic decl and post-mono spec (empty name)
         * are accepted, mirroring KnownHofs's mono-aware fingerprint.
         */
        private fun classifyStructural(fn: FunctionIR): HofKind? {
            val name = fn.name
            val params = fn.paramNames
            val p0 = params.getOrNull(0) ?: return null
            if (p0 !in NativeHofNames.SRC_FIELDS) return null
            val p1 = params.getOrNull(1)
            val p2 = params.getOrNull(2)
            // ── Ordering matters! ──
            // Mono specializations have empty names, so disambiguation
            // relies entirely on param layout. More specific patterns
            // (those constraining params[1]) MUST come before less
            // specific ones (SUM/FROM_SEQ which accept any params[1]).
            // #446 fix: SUM was matching mono map/filter/for_each before
            // they could reach their own branches.
            return when {
                // ── 3-param HOFs (most specific) ──
                // fold/fold_left(src, init, f [, dict])
                params.size in 3..4
                    && (name == NativeHofNames.OP_FOLD || name == NativeHofNames.OP_FOLD_LEFT || name.isEmpty())
                    && p1 == NativeHofNames.INIT && p2 == NativeHofNames.F -> HofKind.FOLD
                // scan(src, init, f [, dict]) — fold-like, produces seq
                params.size in 3..4 && name == NativeHofNames.OP_SCAN
                    && p1 == NativeHofNames.INIT && p2 == NativeHofNames.F -> HofKind.SCAN

                // ── 2-param HOFs with function callback ──
                // All (src, f) HOFs share the same param layout, so mono
                // specializations (empty name) are ambiguous — body-inspect
                // to disambiguate (same pattern as predicate HOFs below).
                // Named functions match exactly; flat_map is explicitly
                // excluded (its callback returns Iter, not an element).
                params.size in 2..3 && p1 == NativeHofNames.F -> when (name) {
                    NativeHofNames.OP_MAP      -> HofKind.MAP
                    NativeHofNames.OP_FOR_EACH -> HofKind.FOR_EACH
                    NativeHofNames.OP_REDUCE   -> HofKind.REDUCE
                    NativeHofNames.OP_MIN_BY   -> HofKind.MIN_BY
                    NativeHofNames.OP_MAX_BY   -> HofKind.MAX_BY
                    ""   -> classifyFCallbackByBody(fn)
                    else -> null
                }

                // ── 2-param HOFs with predicate callback ──
                // All (src, p) HOFs share the same param layout, so mono
                // specializations (empty name) are ambiguous.  When the name
                // IS present we match it exactly; when empty we fall through
                // to body-inspection (classifyPredicateByBody).
                params.size in 2..3 && p1 == NativeHofNames.P -> when (name) {
                    NativeHofNames.OP_FILTER     -> HofKind.FILTER
                    NativeHofNames.OP_COUNT      -> HofKind.COUNT
                    NativeHofNames.OP_ANY        -> HofKind.ANY
                    NativeHofNames.OP_ALL        -> HofKind.ALL
                    NativeHofNames.OP_FIND       -> HofKind.FIND
                    NativeHofNames.OP_NONE       -> HofKind.NONE
                    NativeHofNames.OP_PARTITION  -> HofKind.PARTITION
                    NativeHofNames.OP_TAKE_WHILE -> HofKind.TAKE_WHILE
                    NativeHofNames.OP_DROP_WHILE -> HofKind.DROP_WHILE
                    ""   -> classifyPredicateByBody(fn)
                    else -> null
                }

                // ── 2-param HOFs with count ──
                // take(src, n [, dict]) — named only; mono specs with (src, n)
                // are ambiguous with drop (same fingerprint) so not classified.
                params.size in 2..3
                    && name == NativeHofNames.OP_TAKE
                    && p1 == NativeHofNames.N -> HofKind.TAKE
                // drop(src, n [, dict]) — named only (same reason as take)
                params.size in 2..3
                    && name == NativeHofNames.OP_DROP
                    && p1 == NativeHofNames.N -> HofKind.DROP

                // ── 2-param HOFs with predicate: find_index ──
                params.size in 2..3 && name == NativeHofNames.OP_FIND_INDEX
                    && p1 == NativeHofNames.P -> HofKind.FIND_INDEX

                // ── 1-param unary sinks / stages ──
                params.size == 1 && name == NativeHofNames.OP_TO_SEQ -> HofKind.TO_SEQ
                params.size == 1 && name == NativeHofNames.OP_TO_LIST -> HofKind.TO_LIST
                params.size == 1 && name == NativeHofNames.OP_FIRST -> HofKind.FIRST
                params.size == 1 && name == NativeHofNames.OP_LAST -> HofKind.LAST
                params.size == 1 && name == NativeHofNames.OP_MIN -> HofKind.MIN
                params.size == 1 && name == NativeHofNames.OP_MAX -> HofKind.MAX
                params.size == 1 && name == NativeHofNames.OP_SUM_FLOAT -> HofKind.SUM_FLOAT
                params.size == 1 && name == NativeHofNames.OP_PRODUCT -> HofKind.PRODUCT
                params.size == 1 && name == NativeHofNames.OP_PRODUCT_FLOAT -> HofKind.PRODUCT_FLOAT

                // ── Catch-all: least specific patterns LAST ──
                // sum(src [, dict]) and from_seq(xs). A NAMED function says what it
                // is; a mono specialisation has no name, and its param layout alone
                // says nothing — `(it, r)` is just as much `Eq.eq` on a seq, which
                // was classified SUM here and, once its source peeled to a real seq,
                // fused `[1,2,3].reverse {} == [3,2,1]` into a sum: 6, not True. So
                // an unnamed one is classified by the native its body calls, like
                // the predicate and callback kinds above.
                params.size in 1..2 && name == NativeHofNames.OP_SUM -> HofKind.SUM
                params.size in 1..2 && name.isEmpty()
                    && classifyByBody(fn, mapOf(NativeHofNames.OP_SUM to HofKind.SUM), null) == HofKind.SUM -> HofKind.SUM
                // enumerate(src) — one param and no callback, so it needs its own
                // arm before the catch-alls below.
                params.size == 1 && name == NativeHofNames.OP_ENUMERATE -> HofKind.ENUMERATE
                params.size == 1 && name.isEmpty()
                    && classifyByBody(fn, mapOf(NativeHofNames.OP_ENUMERATE to HofKind.ENUMERATE), null) == HofKind.ENUMERATE -> HofKind.ENUMERATE
                params.size == 1 && name == NativeHofNames.OP_FROM_SEQ -> HofKind.FROM_SEQ
                params.size == 1 && name.isEmpty()
                    && classifyByBody(fn, mapOf(NativeHofNames.OP_FROM_SEQ to HofKind.FROM_SEQ), null) == HofKind.FROM_SEQ -> HofKind.FROM_SEQ
                else -> null
            }
        }

        /**
         * Disambiguate empty-name (mono-specialized) `(src, p)` HOFs by
         * inspecting the function body. Mono specs are thin wrappers that
         * delegate to a single `CallNativeIR`; the native name encodes the
         * operation (`__native_seq_filter`, `__native_seq_drop_while`, …).
         *
         * Falls back to FILTER for unrecognized bodies so that existing
         * mono-spec filter chains keep fusing.
         */
        private fun classifyPredicateByBody(fn: FunctionIR): HofKind? =
            classifyByBody(fn, PREDICATE_OPS, HofKind.FILTER)

        /** Disambiguate mono-spec (src, f) HOFs by inspecting the native
         *  call in the function body. flat_map, for_each, reduce, min_by,
         *  max_by all share the same (src, f) fingerprint as map. Returns
         *  null for unrecognized bodies (flat_map, unknown) — they must
         *  NOT be fused as MAP. */
        private fun classifyFCallbackByBody(fn: FunctionIR): HofKind? =
            classifyByBody(fn, F_CALLBACK_OPS, null)

        private val PREDICATE_OPS = mapOf(
            NativeHofNames.OP_FILTER     to HofKind.FILTER,
            NativeHofNames.OP_DROP_WHILE to HofKind.DROP_WHILE,
            NativeHofNames.OP_TAKE_WHILE to HofKind.TAKE_WHILE,
            NativeHofNames.OP_COUNT      to HofKind.COUNT,
            NativeHofNames.OP_ANY        to HofKind.ANY,
            NativeHofNames.OP_ALL        to HofKind.ALL,
            NativeHofNames.OP_FIND       to HofKind.FIND,
            NativeHofNames.OP_NONE       to HofKind.NONE,
            NativeHofNames.OP_PARTITION  to HofKind.PARTITION,
            NativeHofNames.OP_FIND_INDEX to HofKind.FIND_INDEX,
        )

        private val F_CALLBACK_OPS = mapOf(
            NativeHofNames.OP_MAP      to HofKind.MAP,
            NativeHofNames.OP_FOR_EACH to HofKind.FOR_EACH,
            NativeHofNames.OP_REDUCE   to HofKind.REDUCE,
            NativeHofNames.OP_MIN_BY   to HofKind.MIN_BY,
            NativeHofNames.OP_MAX_BY   to HofKind.MAX_BY,
            // flat_map intentionally absent — its callback returns Iter,
            // not an element, so fusing it as MAP would be unsound.
        )

        /** Shared body-inspection logic: extract the native-call operation
         *  name from the function body and look it up in [ops]. Returns
         *  [fallback] when the body isn't a native call or the op isn't
         *  in [ops]. */
        private fun classifyByBody(
            fn: FunctionIR,
            ops: Map<String, HofKind>,
            fallback: HofKind?,
        ): HofKind? {
            val body = fn.body
            // When the body IS a native call, inspect its name.
            // When it isn't (e.g., list::take_while calling take_while_go),
            // return null — we can't distinguish the HOF variants from a
            // CallDirect body.
            val nativeName = (body as? CallNativeIR)?.name ?: return null
            val op = extractNativeOp(nativeName) ?: return fallback
            return ops[op] ?: fallback
        }

        /** Extract the operation suffix from a native name like
         *  `__native_seq_drop_while` → `"drop_while"`. */
        private fun extractNativeOp(nativeName: String): String? {
            for (coll in NativeHofNames.ELEMENT_COLLECTION_PREFIXES) {
                val prefix = "${com.mezze.language.ast.MagicParams.NATIVE_PREFIX}${coll}_"
                if (nativeName.startsWith(prefix)) return nativeName.substring(prefix.length)
            }
            return null
        }
    }
}
