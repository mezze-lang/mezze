package com.mezze.language.typechecker.compiler

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
