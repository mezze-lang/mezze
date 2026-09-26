package com.mezze.language.comptime

import com.mezze.language.truffleir.*
import com.mezze.language.typedast.Hash

/**
 * Finding and replacing `comptime` in lowered IR.
 *
 * Two consumers need this and they must agree, so there is deliberately one
 * traversal: the type checker's rule 1 (`comptime` used as a value rather than
 * applied) and the reduction pass that replaces a comptime call with the value
 * it computes. Two hand-maintained walkers over the same node set is a failure
 * mode this codebase already has several instances of — including one that
 * corrupted a wire format — and this is not going to be another.
 *
 * It lives in `:ast` and takes the comptime hash as a parameter rather than
 * reading it from a constant, because `:interpreter` — which runs the reduction
 * — does not depend on `:typechecker`, where the generated hash lives.
 *
 * ## Applied vs used as a value
 *
 * Applied, in either shape lowering produces:
 *
 *     CallDirectIR(targetHash = comptime, args)
 *     CallIndirectIR(function = ReadGlobal*ByHashIR(comptime), arg)
 *
 * Used as a value — anything else carrying the hash:
 *
 *     ReadGlobalByHashIR(comptime)        // let f = comptime
 *     ReadGlobalFnByHashIR(comptime)      // { go = comptime }
 *     PartialApplyDirectIR(comptime, …)   // comptime {}
 *
 * The distinction is positional, so [findSites] skips exactly the callee child
 * of a call and reports every other occurrence.
 */
class ComptimeIr(private val comptimeHash: Hash? = null) {

    /** A `comptime` occurrence: applied ([call]) or used as a value ([valueUse]). */
    data class Site(val call: NodeIR?, val valueUse: NodeIR?) {
        val isValueUse: Boolean get() = valueUse != null
    }

    fun findSites(body: NodeIR?): List<Site> =
        mutableListOf<Site>().also { walk(body, it) }

    /**
     * The thunk a comptime call was handed — the `eval` field of its argument
     * record, which lowering leaves as a `MakeLambdaIR` naming the thunk's own
     * compiled function. Null when the call does not have that shape, which the
     * type checker has already rejected by the time this runs.
     */
    fun thunkHashOf(call: NodeIR?): Hash? {
        val arg = when (call) {
            is PerformOpIR -> call.arg
            is CallIndirectIR -> call.arg
            is CallDirectIR -> call.args.firstOrNull()
            is CallIndirectScalarIR -> call.args.firstOrNull()
            else -> null
        } ?: return null

        // Scalar ABI: `comptime` has exactly one parameter, so a fully-applied
        // call passes the thunk POSITIONALLY and no record is built at all.
        (arg as? MakeLambdaIR)?.let { return it.functionHash }

        // Record ABI: the thunk is the `eval` field.
        val record = arg as? MakeRecordIR ?: return null
        val eval = record.fields.firstOrNull { it.name == "eval" }?.value
        return (eval as? MakeLambdaIR)?.functionHash
    }

    /**
     * Rebuild [body] with each applied comptime replaced by whatever [reduce]
     * returns for it. Returning null leaves a site alone, so a pass that can
     * only handle some sites degrades to leaving the rest as ordinary calls —
     * which still evaluate correctly at run time, just not for free.
     */
    fun rewrite(body: NodeIR, reduce: (call: NodeIR) -> NodeIR?): NodeIR {
        if (isStampedSite(body)) reduce(body)?.let { return it }
        return mapChildren(body) { rewrite(it, reduce) }
    }

    /** A call is a comptime site iff it carries the shape the type checker
     *  stamped on it. Self-identifying, so a runtime that never saw the type
     *  checker can still find every site. */
    fun isStampedSite(node: NodeIR): Boolean = when (node) {
        // Recognised by being a comptime perform, NOT by carrying a shape — a
        // shapeless site still has to be evaluated. See PerformOpIR.isComptime.
        is PerformOpIR -> node.isComptime && thunkHashOf(node) != null
        is CallDirectIR -> node.comptimeShape != null && thunkHashOf(node) != null
        else -> false
    }

    /** The shape a stamped site carries, or null if it is not one. */
    fun shapeOf(node: NodeIR): PrintType? = when (node) {
        is PerformOpIR -> node.comptimeShape
        is CallDirectIR -> node.comptimeShape
        else -> null
    }

    /** The spans of every stamped comptime site still present in [body].
     *  Used after reduction to prove none survived. */
    fun remainingSites(body: NodeIR): List<SourceInfo?> {
        val out = mutableListOf<SourceInfo?>()
        fun walk(node: NodeIR) {
            if (isStampedSite(node)) out.add(node.src)
            mapChildren(node) { child -> walk(child); child }
        }
        walk(body)
        return out
    }

    /** Whether [body] contains any stamped comptime site. */
    fun hasSite(body: NodeIR): Boolean {
        if (isStampedSite(body)) return true
        var found = false
        mapChildren(body) { child -> if (!found && hasSite(child)) found = true; child }
        return found
    }

    /**
     * Whether [node] is a call whose callee is `comptime` BY HASH.
     *
     * Reliable for finding value uses, but NOT for finding call sites to reduce:
     * the monomorphizer redirects a specialized call to the specialized hash, so
     * a real comptime call in lowered IR often does not carry this hash at all.
     * The reduction pass therefore identifies sites by the spans the type checker
     * recorded, which survive specialization.
     */
    fun isAppliedComptimeByHash(node: NodeIR): Boolean = isAppliedComptime(node)

    private fun isAppliedComptime(node: NodeIR): Boolean = when (node) {
        is CallDirectIR -> comptimeHash != null && node.targetHash == comptimeHash
        is CallIndirectIR -> isComptimeRead(node.function)
        is CallIndirectScalarIR -> isComptimeRead(node.function)
        else -> false
    }

    private fun isComptimeRead(node: NodeIR?): Boolean = when (node) {
        is ReadGlobalByHashIR -> comptimeHash != null && node.hash == comptimeHash
        is ReadGlobalFnByHashIR -> comptimeHash != null && node.hash == comptimeHash
        else -> false
    }

    // ── traversal ─────────────────────────────────────────────────────────

    private fun walk(node: NodeIR?, out: MutableList<Site>) {
        when (node) {
            null -> return
            is ReadGlobalByHashIR ->
                if (comptimeHash != null && node.hash == comptimeHash) out += Site(null, node)
            is ReadGlobalFnByHashIR ->
                if (comptimeHash != null && node.hash == comptimeHash) out += Site(null, node)
            is PartialApplyDirectIR -> {
                // `comptime {}` — a missing `eval` reads as a partial application
                // handing back a function. There is no call site to replace, so it
                // is a value use; unreported it reaches the runtime and dies in a
                // dispatch node instead of being explained.
                if (comptimeHash != null && node.targetHash == comptimeHash) out += Site(null, node)
                node.partialFields.forEach { walk(it.value, out) }
            }
            is CallDirectIR -> {
                if (comptimeHash != null && node.targetHash == comptimeHash) out += Site(node, null)
                node.args.forEach { walk(it, out) }
            }
            is CallIndirectIR -> {
                // Applied: the callee read is legitimate and is NOT visited.
                if (isComptimeRead(node.function)) out += Site(node, null)
                else walk(node.function, out)
                walk(node.arg, out)
            }
            is CallIndirectScalarIR -> {
                if (isComptimeRead(node.function)) out += Site(node, null)
                else walk(node.function, out)
                node.args.forEach { walk(it, out) }
            }
            else -> forEachChild(node) { walk(it, out) }
        }
    }

    private inline fun forEachChild(node: NodeIR, visit: (NodeIR?) -> Unit) {
        when (node) {
            is BlockIR -> {
                node.stmts.forEach {
                    when (it) {
                        is StmtIR.Let -> visit(it.value)
                        is StmtIR.Expr -> visit(it.node)
                    }
                }
                visit(node.result)
            }
            is IfIR -> { visit(node.cond); visit(node.thenBr); visit(node.elseBr) }
            is WriteLocalIR -> visit(node.value)
            is InlineBinaryOpIR -> { visit(node.left); visit(node.right) }
            is CallLocalIR -> visit(node.arg)
            is CallNativeIR -> { node.args.forEach(visit); visit(node.arg) }
            is DictMethodCallIR -> visit(node.arg)
            is MakeRecordIR -> node.fields.forEach { visit(it.value) }
            is RecordUpdateIR -> { visit(node.record); node.updates.forEach { visit(it.value) } }
            is ReadFieldIR -> visit(node.record)
            is MakeVariantIR -> visit(node.payload)
            is MakeSeqIR -> node.elements.forEach {
                when (it) {
                    is SeqElementIR.Regular -> visit(it.node)
                    is SeqElementIR.Spread -> visit(it.node)
                }
            }
            is SeqGetIR -> { visit(node.seq); visit(node.index) }
            is MatchIR -> {
                visit(node.scrutinee)
                node.cases.forEach { c ->
                    visit(c.guard); visit(c.body)
                    c.ifLetBindings.forEach { visit(it.expr) }
                }
            }
            is PerformOpIR -> visit(node.arg)
            is InspectIR -> visit(node.value)
            is InterpolatedStringIR -> node.parts.forEach {
                if (it is StringPartIR.Expr) visit(it.node)
            }
            is SpecializedCallIR -> node.args.forEach(visit)
            // Leaves, plus shapes whose children cannot hold a global read:
            // literals, ReadLocal/Arg/Capture, EffectBinding, MakeLambda (captures
            // are slot references, not expressions), Handle/HandlerBind, and
            // FusedChain — whose lambdas are separate FunctionIRs, visited in
            // their own right.
            else -> Unit
        }
    }

    /** Structural copy of [node] with each child replaced by [f] of it. */
    fun mapChildren(node: NodeIR, f: (NodeIR) -> NodeIR): NodeIR = when (node) {
        is BlockIR -> node.copy(
            stmts = node.stmts.map {
                when (it) {
                    is StmtIR.Let -> it.copy(value = f(it.value))
                    is StmtIR.Expr -> it.copy(node = f(it.node))
                }
            },
            result = f(node.result),
        )
        is IfIR -> node.copy(cond = f(node.cond), thenBr = f(node.thenBr), elseBr = f(node.elseBr))
        is WriteLocalIR -> node.copy(value = f(node.value))
        is InlineBinaryOpIR -> node.copy(left = f(node.left), right = f(node.right))
        is CallDirectIR -> node.copy(args = node.args.map(f))
        is CallIndirectIR -> node.copy(function = f(node.function), arg = f(node.arg))
        is CallIndirectScalarIR -> node.copy(function = f(node.function), args = node.args.map(f))
        is CallLocalIR -> node.copy(arg = f(node.arg))
        is CallNativeIR -> node.copy(args = node.args.map(f), arg = f(node.arg))
        is DictMethodCallIR -> node.copy(arg = f(node.arg))
        is PartialApplyDirectIR -> node.copy(
            partialFields = node.partialFields.map { it.copy(value = f(it.value)) },
        )
        is MakeRecordIR -> node.copy(fields = node.fields.map { it.copy(value = f(it.value)) })
        is RecordUpdateIR -> node.copy(
            record = f(node.record),
            updates = node.updates.map { it.copy(value = f(it.value)) },
        )
        is ReadFieldIR -> node.copy(record = f(node.record))
        is MakeVariantIR -> node.copy(payload = node.payload?.let(f))
        is MakeSeqIR -> node.copy(
            elements = node.elements.map {
                when (it) {
                    is SeqElementIR.Regular -> it.copy(node = f(it.node))
                    is SeqElementIR.Spread -> it.copy(node = f(it.node))
                }
            },
        )
        is SeqGetIR -> node.copy(seq = f(node.seq), index = f(node.index))
        is MatchIR -> node.copy(
            scrutinee = f(node.scrutinee),
            cases = node.cases.map { c ->
                c.copy(
                    guard = c.guard?.let(f),
                    body = f(c.body),
                    ifLetBindings = c.ifLetBindings.map { it.copy(expr = f(it.expr)) },
                )
            },
        )
        is PerformOpIR -> node.copy(arg = f(node.arg))
        // A comptime block written directly under its own handler —
        // `perform ComptimeEval in Comptime.comptime { … }` — lives inside a
        // HandleIR. Without this arm the traversal stops at the handler and the
        // site is invisible: it type-checks, gets stamped, and then silently
        // never folds, falling back to run time. The handler's own parameter
        // expressions are walked too; a comptime can appear in one.
        is HandleIR -> node.copy(
            body = f(node.body),
            handlers = node.handlers.map { h -> h.copy(params = h.params?.let(f)) },
        )
        is InspectIR -> node.copy(value = f(node.value))
        is InterpolatedStringIR -> node.copy(
            parts = node.parts.map {
                if (it is StringPartIR.Expr) StringPartIR.Expr(f(it.node)) else it
            },
        )
        is SpecializedCallIR -> node.copy(args = node.args.map(f))

        // A canonicalized iter/seq chain. `ChainCanonicalizer` rewrites a seq
        // pipeline into this AFTER lowering, so a comptime feeding a chain ends
        // up buried in `source` — and with no arm here the traversal walked
        // straight past it. The site was stamped, invisible, and silently never
        // folded; a test literally named "a seq result folds" passed anyway,
        // because the run-time fallback computes the same answer.
        is FusedChainIR -> node.copy(
            source = when (val sr = node.source) {
                is SourceIR.RangeSeq -> sr.copy(start = f(sr.start), end = f(sr.end))
                is SourceIR.RangeStepSeq ->
                    sr.copy(start = f(sr.start), end = f(sr.end), step = f(sr.step))
                is SourceIR.RawSeq -> sr.copy(xs = f(sr.xs))
                is SourceIR.RawList -> sr.copy(xs = f(sr.xs))
                is SourceIR.Opaque -> sr.copy(expr = f(sr.expr))
                else -> sr
            },
            stages = node.stages.map { st ->
                when (st) {
                    is StageIR.Take -> st.copy(n = f(st.n))
                    is StageIR.Drop -> st.copy(n = f(st.n))
                    else -> st
                }
            },
            sink = when (val sk = node.sink) {
                is SinkIR.Fold -> sk.copy(init = f(sk.init))
                else -> sk
            },
            fallback = node.fallback?.let(f),
        )

        // Leaves: nothing of ours can be nested inside them. Listed rather than
        // swept up by an `else`, so that a new NodeIR variant is a COMPILE ERROR
        // here instead of another silently-skipped site. Two have already been
        // found the hard way — `HandleIR` and `FusedChainIR` — and both looked
        // exactly like this feature working.
        is BoolLitIR, is CharLitIR, is DoubleLitIR, is FracLitIR, is LongLitIR,
        is StringLitIR, is UnitLitIR,
        is ReadArgIR, is ReadCaptureIR, is ReadLocalIR,
        is EffectBindingIR, is MakeLambdaIR,
        is ReadGlobalByHashIR, is ReadGlobalFnByHashIR -> node
    }
}
