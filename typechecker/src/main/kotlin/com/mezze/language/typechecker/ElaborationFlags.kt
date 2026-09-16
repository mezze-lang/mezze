package com.mezze.language.typechecker

/**
 * Feature gates for the elaboration work (tasks A–F).
 *
 * Each phase of that work changes what the compiler KNOWS, which is exactly the
 * kind of change that can move dispatch in ways no single test predicts. Gating
 * lets a phase land on main, be measured against the elaboration-invariant
 * baseline, and be turned off in one place if something downstream disagrees —
 * rather than being carried on a branch until the whole plan is finished.
 *
 * Read once into a `val`: these are consulted on paths hot enough that a
 * per-call system-property lookup would be a global hash lookup in the middle of
 * type checking.
 */
object ElaborationFlags {

    /**
     * A.1 — keep the impl-operation body solves instead of discarding them.
     *
     * `ImplValidator` runs a full solve of every impl operation to check its body
     * against the declared return type. Without this flag that result is thrown
     * away and emit rebuilds those bodies from nothing; with it, the solves are
     * merged into the module's side tables and substitution.
     *
     * MEASURED 2026-09-16, re-checked because the figure here had gone stale:
     * running both gates with `MEZZE_ELAB=0` produces **22 new elaboration gaps**
     * and turns 3 baseline entries stale, so carrying the solves is load-bearing
     * and `ElaborationInvariantGateTest` is what proves it. (The previous note
     * claimed "566 of the 983 gaps"; that was true when A.1 landed, and later
     * fixes — BUG 11's newtype impl bodies among them — closed most of that
     * distance from the other side. The baseline is now 882.)
     *
     * Defaults ON. Set `MEZZE_ELAB=0` to fall back to discarding them.
     *
     * Note what this flag does NOT do: the solve still happens TWICE, once here
     * and once in the module solve. Carrying the result is strictly better than
     * discarding it, but "ImplValidator consumes elaboration rather than
     * producing it" needs A.1's remainder, which is blocked on A.3.
     */
    val carryImplOpSolves: Boolean = System.getenv("MEZZE_ELAB") != "0"

}
