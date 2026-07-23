package com.mezze.language.typechecker.solving

import com.mezze.language.ast.MezzeType
import com.mezze.language.typechecker.constraints.Constraint

/**
 * A pre-solve cleanup pass over the constraint pool. Runs before the worklist
 * solver so the solver has fewer constraints to iterate over.
 *
 * Only *provably meaning-preserving* rewrites live here — the simplified pool
 * must have exactly the same set of solutions as the original, or inference
 * silently breaks. The two rewrites below are safe by construction:
 *
 *   1. Drop reflexive equalities `Equality(t, t)` — they constrain nothing (a
 *      no-op unification), so removing them changes no solution.
 *   2. Drop exact structural duplicates — keeping the FIRST occurrence and
 *      dropping later identical ones. The kept constraint carries the same
 *      requirement, so the solution set is unchanged. We keep the first (not an
 *      arbitrary one) so the surviving constraint's `loc`/`errorContext` — used
 *      for diagnostics — is the earliest, most intuitive source position.
 *
 * Measured on the stdlib + synthetics: ~2-3% of constraints are reflexive and,
 * at the global pool, ~43% are exact duplicates (7% per-binding). Eager
 * substitution of `Equality(var, type)` (~31%) is deliberately NOT done here —
 * it moves error locations and is not obviously meaning-preserving; left to the
 * solver's worklist.
 */
object ConstraintSimplifier {

    /** Returns a simplified constraint list. Preserves relative order of the
     *  surviving constraints (the solver already re-sorts by priority, but stable
     *  order keeps diagnostics deterministic). */
    fun simplify(constraints: List<Constraint>): List<Constraint> {
        if (constraints.isEmpty()) return constraints

        val out = ArrayList<Constraint>(constraints.size)
        // Dedup by a *canonical* constraint — the same constraint with its
        // diagnostic-only fields (loc/context) reset to defaults. This uses the
        // data-class structural equals/hashCode (which recurses through MezzeType
        // structurally), NOT toString(): several MezzeType.toString()s are
        // deliberately lossy/truncated (`RecordType` -> "N fields",
        // `TypeApplication` -> "ctor, N args"), so a string key would wrongly
        // merge distinct types and silently break inference.
        val seen = HashSet<Constraint>()
        for (c in constraints) {
            if (isReflexive(c)) continue
            if (seen.add(canonical(c))) out.add(c)
        }
        return out
    }

    /** `Equality(t, t)` — both sides identical, so it imposes no constraint. */
    private fun isReflexive(c: Constraint): Boolean =
        c is Constraint.Equality && c.left == c.right

    /**
     * The constraint with diagnostic-only fields (`loc`, `context`) normalized to
     * defaults, so two constraints that impose the same requirement but differ
     * only in source position compare equal. Every meaning-bearing field —
     * types, names, and flags that change how the solver treats the constraint
     * (`strictFieldCheck`, `isPatternContext`, `isMatchCaseContext`) — is left
     * intact, so we never merge two constraints that would solve differently.
     */
    private fun canonical(c: Constraint): Constraint = when (c) {
        is Constraint.Equality -> c.copy(loc = null, context = null, errorContext = null)
        is Constraint.HasField -> c.copy(loc = null, context = null)
        is Constraint.HasConstructor -> c.copy(loc = null, context = null)
        is Constraint.AbilityRequired -> c.copy(loc = null, context = null)
        is Constraint.Subsumes -> c.copy(loc = null, context = null)
        is Constraint.MustBeOpen -> c.copy(loc = null, context = null)
        is Constraint.RecordPatternExhaustive -> c.copy(loc = null, context = null)
        is Constraint.AssociatedTypeResolution -> c.copy(loc = null, context = null)
        // #160: resultType is a fresh tyvar per call site, so two distinct calls
        // never canonicalize equal (safe to normalize only the diagnostic fields).
        is Constraint.InherentMethodResolve -> c.copy(loc = null, context = null)
    }
}
