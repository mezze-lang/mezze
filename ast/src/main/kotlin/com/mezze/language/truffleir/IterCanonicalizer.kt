package com.mezze.language.truffleir

import com.mezze.language.typedast.Hash

/**
 * #446 — canonicalize iter chains into [IterChainIR].
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
 * Env gate: `MEZZE_ITER_CANONICALIZE=0` disables the pass entirely.
 * Default is off during phased landing; flipped on in Phase 5 once the
 * BDSL emit for IterChainIR is in place.
 *
 * See docs/iter-canonicalizer-design.md for the full design.
 */
object IterCanonicalizer {

    /**
     * Kill switch. Default ON since Phase 5. Escape hatch:
     * MEZZE_ITER_CANONICALIZE=0 disables the pass and forces callers to
     * rely on the throwing intercept in BytecodeCompiler.emitNode — no
     * IterChainIR is produced, so the intercept is unreachable.
     */
    private val ENABLED: Boolean = System.getenv("MEZZE_ITER_CANONICALIZE") != "0"

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
        val classifier = IterHofClassifier.build(functions, stdlibSnapshot)
        if (classifier.isEmpty()) return functions

        val out = HashMap<Hash, FunctionIR>(functions.size)
        var anyChanged = false
        for ((hash, fn) in functions) {
            val newBody = rewrite(fn.body, classifier)
            if (newBody === fn.body) {
                out[hash] = fn
            } else {
                out[hash] = fn.copy(body = newBody)
                anyChanged = true
            }
        }
        if (System.getenv("MEZZE_ITER_CANON_DEBUG") != null) {
            System.err.println("[iter-canon] rewrote bodies in " +
                out.count { it.value.body !== functions[it.key]?.body } +
                "/${functions.size} functions")
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
    private fun rewrite(node: NodeIR, classifier: IterHofClassifier): NodeIR {
        // Recurse first — child nodes may contain iter chains too.
        val descended = recurseChildren(node) { child -> rewrite(child, classifier) }
        // Try to canonicalize this node itself (only CallDirectIRs can
        // become IterChainIRs).
        return tryCanonicalize(descended, classifier) ?: descended
    }

    /**
     * Attempt to rewrite [node] into an [IterChainIR]. Returns null if
     * the shape isn't recognized — caller keeps the original node.
     */
    private fun tryCanonicalize(node: NodeIR, classifier: IterHofClassifier): IterChainIR? {
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
        return IterChainIR(source, stages, sink, node.src)
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
        callee: IterHofClassifier.Callee,
    ): Pair<SinkIR, NodeIR>? = when (callee.kind) {
        IterHofClassifier.HofKind.FOLD -> extractFold(call)
        IterHofClassifier.HofKind.SUM -> extractSum(call)
        IterHofClassifier.HofKind.FOR_EACH -> extractForEach(call)
        // MAP/FILTER/FROM_SEQ aren't sinks — they're peel targets. If we
        // land here we're processing the wrong callsite kind; refuse
        // silently so the peel step handles it recursively.
        IterHofClassifier.HofKind.MAP,
        IterHofClassifier.HofKind.FILTER,
        IterHofClassifier.HofKind.FROM_SEQ -> null
    }

    /** iter::fold / seq::fold — expects (src, init, f) + optional trailing dict. */
    private fun extractFold(call: CallDirectIR): Pair<SinkIR, NodeIR>? {
        val (src, init, fRaw) = extractThreeArgs(call, srcNames = SRC_NAMES) ?: return null
        val fLambda = fRaw as? MakeLambdaIR ?: return null
        // Folder must be pure — reject captures. (Stage lambdas with
        // captures need slot-remap at fusion emit time; MVP refuses.)
        if (fLambda.captures.isNotEmpty()) return null
        return SinkIR.Fold(init, fLambda) to src
    }

    /**
     * iter::sum — desugars to Fold(LongLit(0), synthetic {acc,x}->acc+x).
     * We don't synthesize a lambda for that at this point; instead we
     * refuse if the sum body isn't in a form the peel can handle directly.
     * For MVP: skip sum. Add in a follow-up commit once BDSL emits sums
     * natively from IterChainIR without a synthetic MakeLambda.
     */
    private fun extractSum(call: CallDirectIR): Pair<SinkIR, NodeIR>? {
        // MVP: not handled — refuse. Case 1 fusion enabled in a later phase.
        return null
    }

    /** iter::for_each — (src, f) + optional trailing dict. */
    private fun extractForEach(call: CallDirectIR): Pair<SinkIR, NodeIR>? {
        val (src, fRaw) = extractTwoArgs(call, srcNames = SRC_NAMES, secondName = "f") ?: return null
        val fLambda = fRaw as? MakeLambdaIR ?: return null
        if (fLambda.captures.isNotEmpty()) return null
        return SinkIR.ForEach(fLambda) to src
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
        classifier: IterHofClassifier,
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

    /** Recognize `__native_seq_range { start, end }` for a fusable
     *  counted loop, or wrap anything else as RawSeq (seq indexed loop)
     *  or Opaque (unknown). */
    private fun classifyTerminalSource(expr: NodeIR): SourceIR {
        if (expr is CallNativeIR && expr.name == "__native_seq_range") {
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
        // Any other native call producing a Seq, ReadLocal of a Seq
        // param, etc. — treat as raw materialized seq. The BDSL emit
        // uses SeqSize/SeqGet indexed loop for RawSeq. If the runtime
        // value isn't actually a Seq, we'd crash — so canonicalizer must
        // only tag as RawSeq when we're confident.
        //
        // MVP: only tag CallNativeIR results as RawSeq (they're typed
        // seq producers). ReadLocalIR/CallDirectIR of unknown shape →
        // Opaque, forcing peel to bail so ability-dispatch runs.
        if (expr is CallNativeIR) {
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
        classifier: IterHofClassifier,
    ): Pair<MakeLambdaIR, NodeIR>? {
        if (node !is CallDirectIR) return null
        val callee = classifier.lookup(node.targetHash) ?: return null
        if (callee.kind != IterHofClassifier.HofKind.MAP) return null
        val extracted = extractTwoArgs(node, srcNames = SRC_NAMES, secondName = "f") ?: return null
        val (srcInner, fRaw) = extracted
        val fLambda = fRaw as? MakeLambdaIR ?: return null
        if (fLambda.captures.isNotEmpty()) return null
        return fLambda to srcInner
    }

    private fun tryPeelIterFilter(
        node: NodeIR,
        classifier: IterHofClassifier,
    ): Pair<MakeLambdaIR, NodeIR>? {
        if (node !is CallDirectIR) return null
        val callee = classifier.lookup(node.targetHash) ?: return null
        if (callee.kind != IterHofClassifier.HofKind.FILTER) return null
        val extracted = extractTwoArgs(node, srcNames = SRC_NAMES, secondName = "p") ?: return null
        val (srcInner, pRaw) = extracted
        val pLambda = pRaw as? MakeLambdaIR ?: return null
        if (pLambda.captures.isNotEmpty()) return null
        return pLambda to srcInner
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
        classifier: IterHofClassifier,
    ): NodeIR? {
        if (node is CallDirectIR) {
            val callee = classifier.lookup(node.targetHash)
            if (callee != null && callee.kind == IterHofClassifier.HofKind.FROM_SEQ) {
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
            is IterChainIR -> {
                // Inner canonicalization already done bottom-up; descend
                // into source expression and sink init.
                val newSource: SourceIR = when (val s = node.source) {
                    is SourceIR.RangeSeq -> {
                        val nStart = f(s.start); val nEnd = f(s.end)
                        if (nStart !== s.start || nEnd !== s.end) SourceIR.RangeSeq(nStart, nEnd) else s
                    }
                    is SourceIR.RawSeq -> { val nXs = f(s.xs); if (nXs !== s.xs) SourceIR.RawSeq(nXs) else s }
                    is SourceIR.Opaque -> { val nE = f(s.expr); if (nE !== s.expr) SourceIR.Opaque(nE) else s }
                }
                val newSink: SinkIR = when (val sk = node.sink) {
                    is SinkIR.Fold -> { val nInit = f(sk.init); if (nInit !== sk.init) SinkIR.Fold(nInit, sk.f) else sk }
                    is SinkIR.ForEach -> sk
                }
                if (newSource !== node.source || newSink !== node.sink) node.copy(source = newSource, sink = newSink) else node
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
internal class IterHofClassifier private constructor(
    private val table: Map<Hash, Callee>,
) {

    internal enum class HofKind { FOLD, SUM, FOR_EACH, MAP, FILTER, FROM_SEQ }
    internal data class Callee(val kind: HofKind)

    fun isEmpty(): Boolean = table.isEmpty()

    fun lookup(hash: Hash?): Callee? = if (hash == null) null else table[hash]

    companion object {
        fun build(
            functions: Map<Hash, FunctionIR>,
            stdlibSnapshot: Map<Hash, FunctionIR>,
        ): IterHofClassifier {
            val out = HashMap<Hash, Callee>()
            fun scan(m: Map<Hash, FunctionIR>) {
                for ((hash, fn) in m) {
                    val kind = classify(fn) ?: continue
                    out[hash] = Callee(kind)
                }
            }
            scan(functions)
            scan(stdlibSnapshot)
            return IterHofClassifier(out)
        }

        /**
         * Structural classifier — matches by (basename, name, paramNames).
         * Both the pre-mono generic decl and post-mono spec (empty name)
         * are accepted, mirroring KnownHofs's mono-aware fingerprint.
         *
         * Only stdlib basenames match; user-code shadowing is safe.
         */
        private fun classify(fn: FunctionIR): HofKind? {
            val src = fn.sourceFile
            val base = basename(src)
            val name = fn.name
            val params = fn.paramNames
            // iter.mz functions
            if (base == "stdlib-std-collections-iter.mz") {
                return when {
                    params.size in 3..4
                        && (name == "fold" || name.isEmpty())
                        && (params[0] == "src" || params[0] == "it")
                        && params[1] == "init" && params[2] == "f" -> HofKind.FOLD
                    params.size in 1..2
                        && (name == "sum" || name.isEmpty())
                        && (params[0] == "src" || params[0] == "it") -> HofKind.SUM
                    params.size in 2..3
                        && (name == "for_each" || name.isEmpty())
                        && (params[0] == "src" || params[0] == "it")
                        && params[1] == "f" -> HofKind.FOR_EACH
                    params.size in 2..3
                        && (name == "map" || name.isEmpty())
                        && (params[0] == "src" || params[0] == "it")
                        && params[1] == "f" -> HofKind.MAP
                    params.size in 2..3
                        && (name == "filter" || name.isEmpty())
                        && (params[0] == "src" || params[0] == "it")
                        && params[1] == "p" -> HofKind.FILTER
                    params.size == 1
                        && (name == "from_seq" || name.isEmpty())
                        && params[0] == "xs" -> HofKind.FROM_SEQ
                    else -> null
                }
            }
            // seq.mz functions (partial — canonicalizer covers seq
            // side too so peel becomes uniform).
            if (base == "stdlib-std-collections-seq.mz") {
                return when {
                    params.size == 3
                        && (name == "fold" || name.isEmpty())
                        && (params[0] == "xs" || params[0] == "it")
                        && params[1] == "init" && params[2] == "f" -> HofKind.FOLD
                    params.size == 2
                        && (name == "map" || name.isEmpty())
                        && (params[0] == "xs" || params[0] == "it")
                        && params[1] == "f" -> HofKind.MAP
                    params.size == 2
                        && (name == "filter" || name.isEmpty())
                        && (params[0] == "xs" || params[0] == "it")
                        && params[1] == "p" -> HofKind.FILTER
                    else -> null
                }
            }
            return null
        }

        private fun basename(path: String?): String {
            if (path == null) return ""
            val slash = path.lastIndexOf('/')
            return if (slash >= 0) path.substring(slash + 1) else path
        }
    }
}
