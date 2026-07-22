package com.mezze.language.truffleir

/**
 * CPS (continuation-passing style) lowering of effect-carrying IR.
 *
 * Phase 1 of Path C (docs/perf/bdsl-effects-path-c-2026-07.md). Transforms
 * every function that transitively performs an effect op into an explicit
 * (arg, k) -> ... form where k is the continuation. The Loom-based
 * suspend/resume mechanism becomes unnecessary once every effect boundary
 * is a plain lambda invocation.
 *
 * Current state: identity transform. The wiring is in place so that
 * gated invocations (via [com.mezze.interpreter.runtime.MezzeLanguage.CPS_ENABLED])
 * exercise the pass without changing behaviour. Future phases (1.4-1.6)
 * install the real transform for PerformOpIR, HandleIR and
 * EffectBindingIR.
 */
object CpsLowering {

    /** Rewrite [fn] into CPS form. Currently an identity transform. */
    fun lower(fn: FunctionIR): FunctionIR = fn
}
