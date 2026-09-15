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
     * `ImplValidator` already runs a full solve of every impl operation to check
     * its body against the declared return type, then throws the result away;
     * emit rebuilds those bodies from nothing, which is where 566 of the 983
     * gaps the elaboration-invariant gate measures come from. With this on, those
     * solves are merged into the module's side tables and substitution.
     *
     * Defaults ON. Set `MEZZE_ELAB=0` to fall back to discarding them.
     */
    val carryImplOpSolves: Boolean = System.getenv("MEZZE_ELAB") != "0"

}
