package com.mezze.language.typechecker.compiler

import com.mezze.language.truffleir.SourceInfo
import com.mezze.language.truffleir.NodeIR
import com.mezze.language.truffleir.UnitLitIR
import com.mezze.language.truffleir.CallIndirectIR
import com.mezze.language.typedast.TypedPerform
import com.mezze.language.typedast.TypedVar
import com.mezze.language.typedast.TypedFieldAccess
import com.mezze.language.typedast.TypedExpr
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
