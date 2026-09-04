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
     */
    private val ENABLED: Boolean = System.getenv("MEZZE_CHAIN_CANONICALIZE") != "0"

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
            val newBody = rewrite(fn.body, classifier, allFunctions)
            if (newBody === fn.body) {
                out[hash] = fn
            } else {
                out[hash] = fn.copy(body = newBody)
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
    private fun rewrite(node: NodeIR, classifier: ChainHofClassifier, allFunctions: Map<Hash, FunctionIR>): NodeIR {
        // Recurse first — child nodes may contain iter chains too.
        val descended = recurseChildren(node) { child -> rewrite(child, classifier, allFunctions) }
        // Try to canonicalize this node itself (only CallDirectIRs can
        // become FusedChainIRs).
        val result = tryCanonicalize(descended, classifier, allFunctions)
        return result ?: descended
    }

    /**
     * Attempt to rewrite [node] into an [FusedChainIR]. Returns null if
     * the shape isn't recognized — caller keeps the original node.
     */
    private fun tryCanonicalize(node: NodeIR, classifier: ChainHofClassifier, allFunctions: Map<Hash, FunctionIR>): FusedChainIR? {
        // Bare __native_seq_sum / __native_iter_sum — desugared body of
        // seq::sum / iter::sum. Rewrite to FusedChainIR with a Sum sink so
        // the BDSL emit can drive BuiltinSumLongSeq / BuiltinSumRangeLong
        // through the canonical path.
        if (node is CallNativeIR
            && (node.name == "__native_seq_sum" || node.name == "__native_iter_sum")
            && !node.isScalarABI) {
            val mr = node.arg as? MakeRecordIR ?: return null
            if (mr.fields.size != 1) return null
            val fld = mr.fields[0]
            if (fld.name != "xs" && fld.name != "it") return null
            val (stages, source) = peelSource(fld.value, classifier)
            val candidate = FusedChainIR(source, stages, SinkIR.Sum, node.src)
            return if (isEffectSafe(candidate, allFunctions)) candidate else null
        }
        // __native_seq_for_each / __native_iter_for_each
        if (node is CallNativeIR
            && (node.name == "__native_seq_for_each" || node.name == "__native_iter_for_each")
            && !node.isScalarABI) {
            val mr = node.arg as? MakeRecordIR ?: return null
            var src: NodeIR? = null; var f: NodeIR? = null
            for (fld in mr.fields) when (fld.name) {
                "xs", "it" -> src = fld.value
                "f"  -> f  = fld.value
            }
            val srcInner = src ?: return null
            val fLambda = f as? MakeLambdaIR ?: return null
            // Captures OK — emit-side handles them via capture slot remap.
            val (stages, source) = peelSource(srcInner, classifier)
            if (stages.isEmpty() && source is SourceIR.Opaque) return null
            val candidate = FusedChainIR(source, stages, SinkIR.ForEach(fLambda), node.src)
            return if (isEffectSafe(candidate, allFunctions)) candidate else null
        }
        // __native_iter_fold / __native_seq_fold — fold { it/xs, init, f }
        if (node is CallNativeIR
            && (node.name == "__native_iter_fold" || node.name == "__native_seq_fold")
            && !node.isScalarABI) {
            val mr = node.arg as? MakeRecordIR ?: return null
            var src: NodeIR? = null; var init: NodeIR? = null; var f: NodeIR? = null
            for (fld in mr.fields) when (fld.name) {
                "it", "xs" -> src = fld.value
                "init" -> init = fld.value
                "f" -> f = fld.value
            }
            val srcInner = src ?: return null
            val initExpr = init ?: return null
            val fLambda = f as? MakeLambdaIR ?: return null
            val (stages, source) = peelSource(srcInner, classifier)
            if (stages.isEmpty() && source is SourceIR.Opaque) return null
            val candidate = FusedChainIR(source, stages, SinkIR.Fold(initExpr, fLambda), node.src)
            return if (isEffectSafe(candidate, allFunctions)) candidate else null
        }
        // __native_{iter,seq}_{count,any,all,find,reduce,to_seq,to_list}
        if (node is CallNativeIR && !node.isScalarABI) {
            val result = tryCanonicalizeNativeSink(node, classifier, allFunctions)
            if (result != null) return result
        }
        // Peek through CallDirect to mono wrappers whose body is a native
        // sink call (e.g., mono iter::count with a baked-in predicate lambda).
        // The TrivialWrapperInliner can't inline these because `countParamReads`
        // rejects MakeLambdaIR in the wrapper body. But we can still fuse them
        // by manually substituting the call args into the native body and routing
        // through the native-sink path. This covers mono specializations of
        // count/any/all/find/reduce/to_seq/to_list that the classifier
        // misclassifies as FILTER/MAP due to empty names.
        if (node is CallDirectIR) {
            val peeked = peekThroughMonoWrapper(node, allFunctions)
            if (peeked != null) {
                val result = tryCanonicalizeNativeSink(peeked, classifier, allFunctions)
                if (result != null) return result
            }
        }
        if (node !is CallDirectIR) return null
        val callee = classifier.lookup(node.targetHash) ?: return null
        // Extract sink + source expr from the CallDirect args.
        val sinkAndSrc = extractSinkAndSource(node, callee) ?: return null
        val (sink, sourceExpr) = sinkAndSrc
        // Peel the source expression: strip iter::map/filter/from_seq
        // layers, then classify the terminal source (RangeSeq/RawSeq/Opaque).
        val (stages, source) = peelSource(sourceExpr, classifier)
        // If we couldn't reach anything better than Opaque AND we had no
        // stages, this is a bare fold on an unknown value — no gain from
        // canonicalizing. Bail so downstream can dispatch normally.
        if (stages.isEmpty() && source is SourceIR.Opaque) return null
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
        is SinkIR.Fold, SinkIR.Sum, is SinkIR.ForEach -> true
        SinkIR.Count, is SinkIR.Any, is SinkIR.All -> true
        is SinkIR.Find, is SinkIR.Reduce -> true
        SinkIR.ToSeq, SinkIR.ToList -> true
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
    ): CallNativeIR? {
        if (!call.isScalarABI) return null
        val target = allFunctions[call.targetHash] ?: return null
        // Only handle simple wrappers: no captures, no locals.
        if (target.captureSlots.isNotEmpty() || target.localSlots.isNotEmpty()) return null
        // Body must be a single record-ABI native call.
        val nativeCall = target.body as? CallNativeIR ?: return null
        if (nativeCall.isScalarABI) return null
        val mr = nativeCall.arg as? MakeRecordIR ?: return null
        // Substitute ReadLocalIR(param slot) → corresponding call arg.
        // Frame layout with 0 captures: param i lives at slot i.
        val paramCount = target.paramSlots.size
        if (call.args.size < paramCount) return null
        val newFields = mr.fields.map { fld ->
            val v = fld.value
            if (v is ReadLocalIR && v.slot < paramCount) {
                FieldIR(fld.name, call.args[v.slot])
            } else {
                fld
            }
        }
        return nativeCall.copy(arg = MakeRecordIR(newFields, mr.src))
    }

    /** Handle __native_iter_{count,any,all,find,reduce,to_seq,to_list}.
     *  These sinks are classified but not yet emittable — the method builds
     *  the FusedChainIR, then gates on [isEmittableSink] before returning. */
    private fun tryCanonicalizeNativeSink(
        node: CallNativeIR,
        classifier: ChainHofClassifier,
        allFunctions: Map<Hash, FunctionIR>,
    ): FusedChainIR? {
        val mr = node.arg as? MakeRecordIR ?: return null
        val candidate: FusedChainIR? = when (node.name) {
            "__native_iter_count", "__native_seq_count" -> {
                val srcField = if (node.name.startsWith("__native_iter")) "it" else "xs"
                var src: NodeIR? = null; var p: NodeIR? = null
                for (fld in mr.fields) when (fld.name) {
                    srcField -> src = fld.value; "p" -> p = fld.value
                }
                val srcInner = src ?: return null
                val pLambda = p as? MakeLambdaIR ?: return null
                // Captures OK — emit-side handles them via capture slot remap.
                val (stages, source) = peelSource(srcInner, classifier)
                // count(p) = filter(p) + count
                val allStages = stages + StageIR.Filter(pLambda)
                val candidate = FusedChainIR(source, allStages, SinkIR.Count, node.src)
                if (isEffectSafe(candidate, allFunctions)) candidate else null
            }
            "__native_iter_any", "__native_seq_any" ->
                buildPredicateFused(mr, node.name, classifier, allFunctions, node.src) { SinkIR.Any(it) }
            "__native_iter_all", "__native_seq_all" ->
                buildPredicateFused(mr, node.name, classifier, allFunctions, node.src) { SinkIR.All(it) }
            "__native_iter_find", "__native_seq_find" ->
                buildPredicateFused(mr, node.name, classifier, allFunctions, node.src) { SinkIR.Find(it) }
            "__native_iter_reduce", "__native_seq_reduce" -> {
                val srcField = if (node.name.startsWith("__native_iter")) "it" else "xs"
                var src: NodeIR? = null; var f: NodeIR? = null
                for (fld in mr.fields) when (fld.name) {
                    srcField -> src = fld.value; "f" -> f = fld.value
                }
                val srcInner = src ?: return null
                val fLambda = f as? MakeLambdaIR ?: return null
                // Captures OK — emit-side handles them via capture slot remap.
                val (stages, source) = peelSource(srcInner, classifier)
                if (stages.isEmpty() && source is SourceIR.Opaque) return null
                val candidate = FusedChainIR(source, stages, SinkIR.Reduce(fLambda), node.src)
                if (isEffectSafe(candidate, allFunctions)) candidate else null
            }
            "__native_iter_to_seq", "__native_iter_to_list" -> {
                var src: NodeIR? = null
                for (fld in mr.fields) { if (fld.name == "it") src = fld.value }
                val srcInner = src ?: return null
                val sink = if (node.name == "__native_iter_to_seq") SinkIR.ToSeq else SinkIR.ToList
                val (stages, source) = peelSource(srcInner, classifier)
                if (stages.isEmpty() && source is SourceIR.Opaque) return null
                val candidate = FusedChainIR(source, stages, sink, node.src)
                if (isEffectSafe(candidate, allFunctions)) candidate else null
            }
            else -> null
        }
        // Gate: only return if the emitter can handle the sink.
        if (candidate != null && !isEmittableSink(candidate.sink)) return null
        return candidate
    }

    /** Build a FusedChainIR with a predicate sink (any/all/find) from a native call record. */
    private fun buildPredicateFused(
        mr: MakeRecordIR,
        nativeName: String,
        classifier: ChainHofClassifier,
        allFunctions: Map<Hash, FunctionIR>,
        src: SourceInfo,
        wrap: (MakeLambdaIR) -> SinkIR,
    ): FusedChainIR? {
        val srcField = if (nativeName.startsWith("__native_iter")) "it" else "xs"
        var srcNode: NodeIR? = null; var p: NodeIR? = null
        for (fld in mr.fields) when (fld.name) {
            srcField -> srcNode = fld.value; "p" -> p = fld.value
        }
        val srcInner = srcNode ?: return null
        val pLambda = p as? MakeLambdaIR ?: return null
        // Captures OK — emit-side handles them via capture slot remap.
        val (stages, source) = peelSource(srcInner, classifier)
        if (stages.isEmpty() && source is SourceIR.Opaque) return null
        val candidate = FusedChainIR(source, stages, wrap(pLambda), src)
        return if (isEffectSafe(candidate, allFunctions)) candidate else null
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
        ChainHofClassifier.HofKind.FOR_EACH -> extractForEach(call)
        ChainHofClassifier.HofKind.COUNT -> extractCount(call)
        ChainHofClassifier.HofKind.REDUCE -> extractReduce(call)
        ChainHofClassifier.HofKind.ANY -> extractPredicateSink(call) { SinkIR.Any(it) }
        ChainHofClassifier.HofKind.ALL -> extractPredicateSink(call) { SinkIR.All(it) }
        ChainHofClassifier.HofKind.FIND -> extractPredicateSink(call) { SinkIR.Find(it) }
        ChainHofClassifier.HofKind.TO_SEQ -> extractUnarySink(call, SinkIR.ToSeq)
        ChainHofClassifier.HofKind.TO_LIST -> extractUnarySink(call, SinkIR.ToList)
        // MAP/FILTER/TAKE/DROP/FROM_SEQ aren't sinks — they're peel targets.
        // If we land here we're processing the wrong callsite kind; refuse
        // silently so the peel step handles it recursively.
        ChainHofClassifier.HofKind.MAP,
        ChainHofClassifier.HofKind.FILTER,
        ChainHofClassifier.HofKind.TAKE,
        ChainHofClassifier.HofKind.DROP,
        ChainHofClassifier.HofKind.FROM_SEQ -> null
    }

    /** iter::fold / seq::fold — expects (src, init, f) + optional trailing dict. */
    private fun extractFold(call: CallDirectIR): Pair<SinkIR, NodeIR>? {
        val (src, init, fRaw) = extractThreeArgs(call, srcNames = SRC_NAMES) ?: return null
        val fLambda = fRaw as? MakeLambdaIR ?: return null
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
        val fLambda = fRaw as? MakeLambdaIR ?: return null
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
        val fLambda = fRaw as? MakeLambdaIR ?: return null
        // Captures OK — emit-side handles them via capture slot remap.
        return SinkIR.Reduce(fLambda) to src
    }

    /** any/all/find — (src, p) + optional trailing dict. Predicate sinks. */
    private fun extractPredicateSink(
        call: CallDirectIR,
        wrap: (MakeLambdaIR) -> SinkIR,
    ): Pair<SinkIR, NodeIR>? {
        val (src, pRaw) = extractTwoArgs(call, srcNames = SRC_NAMES, secondName = "p") ?: return null
        val pLambda = pRaw as? MakeLambdaIR ?: return null
        // Captures OK — emit-side handles them via capture slot remap.
        return wrap(pLambda) to src
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

    /** Field names that mean "the collection source position" across
     *  seq::/iter:: variants (plain module-level `xs`/`src`, ability-
     *  method wrapper `it`). */
    private val SRC_NAMES = setOf("xs", "src", "it")

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
     * Peel iter::map / iter::filter / iter::from_seq layers off
     * [sourceExpr] and classify the resulting terminal expression as
     * a [SourceIR]. Stages are returned in *outer-to-inner* semantic
     * order — the first stage in the list is the one closest to the
     * sink, which is the outermost `iter::map` or `iter::filter` in
     * the source.
     *
     * MVP scope: handles CallDirect to iter::map/filter/from_seq only.
     * Inlined MakeRecord descriptor shapes (post-inliner) are the
     * next step — TODO.
     */
    private fun peelSource(
        sourceExpr: NodeIR,
        classifier: ChainHofClassifier,
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
            val fromSeqPeeled = tryPeelIterFromSeq(current, classifier)
            if (fromSeqPeeled != null) {
                // from_seq terminates the iter chain — the peeled inner is
                // now a Seq expression. Classify it and return.
                return stages to classifyTerminalSource(fromSeqPeeled)
            }
            // Nothing peeled — treat current as the terminal source.
            return stages to classifyTerminalSource(current)
        }
        // Guard exhausted — chain too deep. Bail with what we have.
        return stages to classifyTerminalSource(current)
    }

    /** Recognize `__native_seq_range` / `__native_iter_range` for a fusable
     *  counted loop, or wrap anything else as RawSeq (seq indexed loop)
     *  or Opaque (unknown). */
    private fun classifyTerminalSource(expr: NodeIR): SourceIR {
        if (expr is CallNativeIR
            && (expr.name == "__native_seq_range" || expr.name == "__native_iter_range")) {
            // Both scalar-ABI (args=[start, end]) and record-ABI (arg
            // = MakeRecord{start, end}) shapes exist.
            if (expr.isScalarABI && expr.args.size == 2) {
                return SourceIR.RangeSeq(expr.args[0], expr.args[1])
            }
            val rec = expr.arg as? MakeRecordIR
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
        // Any other native call producing a Seq — treat as raw materialized
        // seq. The BDSL emit uses SeqSize/SeqGet indexed loop for RawSeq.
        // If the runtime value isn't actually a Seq, we'd crash — so only
        // tag as RawSeq when we're confident. __native_iter_* calls produce
        // MezzeIter objects (not Seq), so those must be Opaque — SeqSize
        // would throw on them at runtime.
        if (expr is CallNativeIR && !expr.name.startsWith("__native_iter_")) {
            return SourceIR.RawSeq(expr)
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
        // Native seq::map or iter::map — record-ABI only.
        // seq uses field "xs", iter uses field "it".
        if (node is CallNativeIR
            && (node.name == "__native_seq_map" || node.name == "__native_iter_map")
            && !node.isScalarABI) {
            val mr = node.arg as? MakeRecordIR ?: return null
            var src: NodeIR? = null; var f: NodeIR? = null
            for (fld in mr.fields) when (fld.name) {
                "xs", "it" -> src = fld.value
                "f"  -> f  = fld.value
            }
            val srcInner = src ?: return null
            val fLambda = f as? MakeLambdaIR ?: return null
            // Captures are OK: emit-side classifier + emit remap captured
            // reads to the enclosing frame slots (captures[i].slot).
            return fLambda to srcInner
        }
        if (node !is CallDirectIR) return null
        val callee = classifier.lookup(node.targetHash) ?: return null
        if (callee.kind != ChainHofClassifier.HofKind.MAP) return null
        val extracted = extractTwoArgs(node, srcNames = SRC_NAMES, secondName = "f") ?: return null
        val (srcInner, fRaw) = extracted
        val fLambda = fRaw as? MakeLambdaIR ?: return null
        // Captures OK; see comment above.
        return fLambda to srcInner
    }

    private fun tryPeelIterFilter(
        node: NodeIR,
        classifier: ChainHofClassifier,
    ): Pair<MakeLambdaIR, NodeIR>? {
        // Native seq::filter or iter::filter — record-ABI only.
        if (node is CallNativeIR
            && (node.name == "__native_seq_filter" || node.name == "__native_iter_filter")
            && !node.isScalarABI) {
            val mr = node.arg as? MakeRecordIR ?: return null
            var src: NodeIR? = null; var p: NodeIR? = null
            for (fld in mr.fields) when (fld.name) {
                "xs", "it" -> src = fld.value
                "p"  -> p  = fld.value
            }
            val srcInner = src ?: return null
            val pLambda = p as? MakeLambdaIR ?: return null
            // Captures OK; see comment on tryPeelIterMap.
            return pLambda to srcInner
        }
        if (node !is CallDirectIR) return null
        val callee = classifier.lookup(node.targetHash) ?: return null
        if (callee.kind != ChainHofClassifier.HofKind.FILTER) return null
        val extracted = extractTwoArgs(node, srcNames = SRC_NAMES, secondName = "p") ?: return null
        val (srcInner, pRaw) = extracted
        val pLambda = pRaw as? MakeLambdaIR ?: return null
        // Captures OK; see comment above.
        return pLambda to srcInner
    }

    /** iter::take { it = <src>, n = <count> } — returns (nExpr, srcExpr). */
    private fun tryPeelIterTake(
        node: NodeIR,
        classifier: ChainHofClassifier,
    ): Pair<NodeIR, NodeIR>? {
        // Native __native_iter_take / __native_seq_take — record-ABI only.
        if (node is CallNativeIR
            && (node.name == "__native_iter_take" || node.name == "__native_seq_take")
            && !node.isScalarABI) {
            val mr = node.arg as? MakeRecordIR ?: return null
            var src: NodeIR? = null; var n: NodeIR? = null
            for (fld in mr.fields) when (fld.name) {
                "xs", "it" -> src = fld.value
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

    /** iter::drop { it = <src>, n = <count> } — returns (nExpr, srcExpr). */
    private fun tryPeelIterDrop(
        node: NodeIR,
        classifier: ChainHofClassifier,
    ): Pair<NodeIR, NodeIR>? {
        // Native __native_iter_drop / __native_seq_drop — record-ABI only.
        if (node is CallNativeIR
            && (node.name == "__native_iter_drop" || node.name == "__native_seq_drop")
            && !node.isScalarABI) {
            val mr = node.arg as? MakeRecordIR ?: return null
            var src: NodeIR? = null; var n: NodeIR? = null
            for (fld in mr.fields) when (fld.name) {
                "xs", "it" -> src = fld.value
                "n"  -> n  = fld.value
            }
            val srcInner = src ?: return null
            val nExpr = n ?: return null
            return nExpr to srcInner
        }
        if (node !is CallDirectIR) return null
        val callee = classifier.lookup(node.targetHash) ?: return null
        if (callee.kind != ChainHofClassifier.HofKind.DROP) return null
        val extracted = extractTwoArgs(node, srcNames = SRC_NAMES, secondName = "n") ?: return null
        return extracted.second to extracted.first
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
                    is SourceIR.RawSeq -> { val nXs = f(s.xs); if (nXs !== s.xs) SourceIR.RawSeq(nXs) else s }
                    is SourceIR.Opaque -> { val nE = f(s.expr); if (nE !== s.expr) SourceIR.Opaque(nE) else s }
                }
                var stagesChanged = false
                val newStages = node.stages.map { stage ->
                    when (stage) {
                        is StageIR.Map, is StageIR.Filter -> stage
                        is StageIR.Take -> { val nn = f(stage.n); if (nn !== stage.n) { stagesChanged = true; StageIR.Take(nn) } else stage }
                        is StageIR.Drop -> { val nn = f(stage.n); if (nn !== stage.n) { stagesChanged = true; StageIR.Drop(nn) } else stage }
                    }
                }
                val newSink: SinkIR = when (val sk = node.sink) {
                    is SinkIR.Fold -> { val nInit = f(sk.init); if (nInit !== sk.init) SinkIR.Fold(nInit, sk.f) else sk }
                    is SinkIR.ForEach -> sk
                    is SinkIR.Reduce -> sk
                    is SinkIR.Any -> sk
                    is SinkIR.All -> sk
                    is SinkIR.Find -> sk
                    SinkIR.Sum, SinkIR.Count, SinkIR.ToSeq, SinkIR.ToList -> sk
                }
                if (newSource !== node.source || newSink !== node.sink || stagesChanged)
                    node.copy(source = newSource, stages = if (stagesChanged) newStages else node.stages, sink = newSink) else node
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
        FOLD, SUM, FOR_EACH, MAP, FILTER, TAKE, DROP, FROM_SEQ,
        COUNT, REDUCE, ANY, ALL, FIND, TO_SEQ, TO_LIST
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
            val isSrc = p0 == "xs" || p0 == "src" || p0 == "it"
            if (!isSrc) return null
            // ── Ordering matters! ──
            // Mono specializations have empty names, so disambiguation
            // relies entirely on param layout. More specific patterns
            // (those constraining params[1]) MUST come before less
            // specific ones (SUM/FROM_SEQ which accept any params[1]).
            // #446 fix: SUM was matching mono map/filter/for_each before
            // they could reach their own branches.
            return when {
                // fold(src/xs/it, init, f [, dict]) — most specific (3-4 params)
                params.size in 3..4
                    && (name == "fold" || name.isEmpty())
                    && params[1] == "init" && params[2] == "f" -> HofKind.FOLD
                // map(src/xs/it, f [, dict]) — constrains params[1]
                params.size in 2..3
                    && (name == "map" || name.isEmpty())
                    && params[1] == "f" -> HofKind.MAP
                // for_each(src/xs/it, f [, dict]) — constrains params[1];
                // for mono, structurally identical to map — safe: if a
                // mono for_each is classified as MAP, extractSinkAndSource
                // rejects it (MAP isn't a sink) and the chain stays unfused.
                params.size in 2..3
                    && (name == "for_each" || name.isEmpty())
                    && params[1] == "f" -> HofKind.FOR_EACH
                // filter(src/xs/it, p [, dict]) — constrains params[1]
                params.size in 2..3
                    && (name == "filter" || name.isEmpty())
                    && params[1] == "p" -> HofKind.FILTER
                // take(src/xs/it, n [, dict]) — constrains params[1]
                params.size in 2..3
                    && (name == "take" || name.isEmpty())
                    && params[1] == "n" -> HofKind.TAKE
                // drop(src/xs/it, n [, dict]) — constrains params[1];
                // for mono, structurally identical to take — safe: both
                // are stages, but misclassification changes semantics.
                // Named fns are fine; mono take/drop are rare at the
                // outer-call level (users don't chain `take` into `fold`
                // via a mono specialization in practice).
                params.size in 2..3
                    && (name == "drop" || name.isEmpty())
                    && params[1] == "n" -> HofKind.DROP
                // ── Sink HOFs (named only — mono collides with stages) ──
                // count(it, p) — same shape as filter, distinguish by name
                params.size in 2..3 && name == "count"
                    && params[1] == "p" -> HofKind.COUNT
                // any(it, p)
                params.size in 2..3 && name == "any"
                    && params[1] == "p" -> HofKind.ANY
                // all(it, p)
                params.size in 2..3 && name == "all"
                    && params[1] == "p" -> HofKind.ALL
                // find(it, p)
                params.size in 2..3 && name == "find"
                    && params[1] == "p" -> HofKind.FIND
                // reduce(it, f) — like fold but no init
                params.size in 2..3 && name == "reduce"
                    && params[1] == "f" -> HofKind.REDUCE
                // to_seq(it) — materialize
                params.size == 1 && name == "to_seq" -> HofKind.TO_SEQ
                // to_list(it) — materialize
                params.size == 1 && name == "to_list" -> HofKind.TO_LIST
                // sum(src/xs/it [, dict]) — LAST among 1-2 param patterns;
                // no params[1] constraint so it would shadow MAP/FILTER
                // etc. if placed earlier.
                params.size in 1..2
                    && (name == "sum" || name.isEmpty()) -> HofKind.SUM
                // from_seq(xs) — exactly 1 param, no ambiguity
                params.size == 1
                    && (name == "from_seq" || name.isEmpty()) -> HofKind.FROM_SEQ
                else -> null
            }
        }
    }
}
