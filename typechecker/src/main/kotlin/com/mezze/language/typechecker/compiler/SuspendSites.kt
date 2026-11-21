package com.mezze.language.typechecker.compiler

import com.mezze.language.typedast.Hash
import com.mezze.language.truffleir.forEachChildIR
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.truffleir.MakeLambdaIR
import com.mezze.language.truffleir.CallNativeIR
import com.mezze.language.truffleir.DictMethodCallIR
import com.mezze.language.truffleir.CallLocalIR
import com.mezze.language.truffleir.CallIndirectScalarIR
import com.mezze.language.truffleir.CallDirectIR
import com.mezze.language.truffleir.PerformOpIR
import com.mezze.language.truffleir.SourceInfo
import com.mezze.language.truffleir.NodeIR
import com.mezze.language.truffleir.UnitLitIR
import com.mezze.language.truffleir.CallIndirectIR
import com.mezze.language.typedast.TypedPerform
import com.mezze.language.typedast.TypedVar
import com.mezze.language.typedast.TypedFieldAccess
import com.mezze.language.typedast.TypedExpr
import com.mezze.language.typedast.TypedCall
import com.mezze.language.ast.ScopedEffectConstants
import com.mezze.language.ast.MezzeType

/**
 * C1 of continuations-and-swappable-async: which call sites MAY SUSPEND.
 *
 * A call may suspend iff its callee's solved effect row names an effect or has
 * an open tail — those are the effects a handler OUTSIDE the call will answer,
 * and a handler that parks suspends every frame between it and the call. A
 * `<*>`-only row handles its own effects inside and cannot suspend outward.
 *
 * Decided here, from the TYPE, and carried on the call node (`maySuspend`) so
 * the lowering — which runs in another process for `mez dev` / `exec` and sees
 * only serialized IR — can put the propagation check exactly where it can
 * matter. The check itself costs 38 ps, so the flag buys correctness and
 * clarity, not speed; a FALSE verdict where the callee can suspend would drop a
 * suspension, so a wrapper whose target row is not in hand is marked `true`.
 */
internal fun TypedASTCompiler.rowMaySuspend(calleeType: MezzeType?): Boolean {
    val row = (substitution.apply(calleeType ?: return false) as? MezzeType.FunctionType)?.effects ?: return false
    return row.allEffectNames.isNotEmpty() || row.rowVar != null
}

/**
 * The verdict for a call's CALLEE expression. The row rule above, plus the one
 * call that suspends by DEFINITION: `cont.park`. `cont` is a runtime-bound
 * keyword (`EffectBindingKind.CONT`), so this is a keyword rule, not a name match
 * on user code — the same footing as `scope` or `resume`.
 */
internal fun TypedASTCompiler.calleeMaySuspend(fn: TypedExpr): Boolean =
    rowMaySuspend(fn.type) ||
        (fn is TypedFieldAccess && fn.field == ScopedEffectConstants.CONT_PARK &&
            (fn.record as? TypedVar)?.name == ScopedEffectConstants.CONT)

/**
 * The body of a `perform`. A cont-capable perform's body is a `{} -> …` thunk
 * (its own root, so the boundary is the frame that catches a suspension), and
 * calling it is the ONE call that must not propagate a suspension outward
 * (`maySuspend = false`): the boundary driver right there consumes it.
 */
internal fun TypedASTCompiler.compilePerformBody(expr: TypedPerform, src: SourceInfo): NodeIR =
    if (expr.bodyIsThunk) CallIndirectIR(compileExpr(expr.body), UnitLitIR(src), false, src, maySuspend = false)
    else compileExpr(expr.body)

/**
 * C4: a native call whose argument record carries an EFFECTFUL function value may
 * suspend inside the native (the callback parks); the site gets the propagation
 * wrapper. In a stdlib wrapper the callback is a parameter with an open row, so the
 * wrapper's one native call is flagged once, for every caller. A pure callback
 * (`effects == null`) cannot park and costs nothing.
 */
internal fun nativeMaySuspend(arg: TypedExpr): Boolean {
    val rec = arg.type as? MezzeType.RecordType ?: return false
    return rec.fields.values.any { it is MezzeType.FunctionType && it.effects != null }
}

/**
 * C4: does this function BODY possibly suspend? A perform op; a call carrying a
 * `maySuspend` verdict (C1); a native call with an effectful callback; or a nested
 * lambda whose own body may (looked up by hash — lambdas are lowered before the
 * function that creates them). Stamped on `FunctionIR.maySuspend`, read by the
 * natives to pick a resumable path only for callbacks that need one.
 */
internal fun bodyMaySuspend(body: NodeIR, lookup: (Hash) -> FunctionIR?): Boolean {
    var found = false
    val seen = HashSet<Hash>()
    fun walk(n: NodeIR, depth: Int) {
        if (found) return
        when (n) {
            is PerformOpIR -> { found = true; return }
            is CallDirectIR -> if (n.maySuspend) { found = true; return }
            is CallIndirectIR -> if (n.maySuspend) { found = true; return }
            is CallIndirectScalarIR -> if (n.maySuspend) { found = true; return }
            is CallLocalIR -> if (n.maySuspend) { found = true; return }
            is DictMethodCallIR -> if (n.maySuspend) { found = true; return }
            is CallNativeIR -> if (n.maySuspend) { found = true; return }
            is MakeLambdaIR -> if (depth < 6 && seen.add(n.functionHash)) {
                val inner = lookup(n.functionHash)
                if (inner == null || inner.maySuspend) { found = true; return }
            }
            else -> {}
        }
        n.forEachChildIR { walk(it, depth + 1) }
    }
    walk(body, 0)
    return found
}

/** `"abort"`/`"resume"` when [fn] is `cont.abort`/`cont.resume` — the typed twin of
 *  `ScopedEffectConstants.contHandle`. `cont` is reserved, so a TypedVar named
 *  `cont` is always the runtime handle. */
internal fun contHandleOf(fn: TypedExpr): String? {
    if (fn !is TypedFieldAccess) return null
    if ((fn.record as? TypedVar)?.name != ScopedEffectConstants.CONT) return null
    return when (fn.field) {
        ScopedEffectConstants.CONT_ABORT, ScopedEffectConstants.CONT_RESUME -> fn.field
        else -> null
    }
}

/** `cont.abort { val }` / `cont.resume { val }`: the handle itself, not a field read
 *  off the `cont` record — the same EffectBindingIR call the bare names compiled to,
 *  which the bytecode folds to a constant under a stable handler chain, so a
 *  `throw`/`return` handler pays nothing for the `cont.` spelling. */
internal fun TypedASTCompiler.compileContHandleCall(
    expr: TypedCall, calleeMaySuspend: Boolean, argIR: NodeIR, src: SourceInfo,
): NodeIR? {
    val handle = contHandleOf(expr.function) ?: return null
    return emitCallIndirect(calleeMaySuspend, com.mezze.language.truffleir.EffectBindingIR(handle, src), argIR, expr.isTailCall, src)
}

/** `scope` and `cont` — the names the runtime binds in a performer op body. NOT
 *  `abort`/`resume`: those are ordinary identifiers now, reached as `cont.abort`. */
internal fun isRuntimeBoundName(name: String): Boolean {
    val kind = com.mezze.language.ast.EffectBindingKind.fromName(name)
    return kind == com.mezze.language.ast.EffectBindingKind.SCOPE || kind == com.mezze.language.ast.EffectBindingKind.CONT
}
