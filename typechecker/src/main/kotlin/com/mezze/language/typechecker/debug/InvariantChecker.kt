package com.mezze.language.typechecker.debug

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.solving.Substitution
import com.mezze.language.ast.solving.collectFreeTypeVars
import com.mezze.language.ast.solving.occursCheck
import com.mezze.language.typechecker.constraints.ConstraintResult

object InvariantChecker {

    private val enabled: Boolean
        get() = System.getProperty("mezze.invariants", "false") == "true"

    class InvariantViolation(message: String) : RuntimeException(message)

    fun checkSubstitution(substitution: Substitution, phase: String) {
        if (!enabled) return

        for ((typeVar, type) in substitution.mappings) {
            if (type is MezzeType.VarType && type.variable == typeVar) {
                throw InvariantViolation(
                    "[$phase] Substitution self-mapping: $typeVar → $type"
                )
            }

            if (occursCheck(typeVar, type)) {
                throw InvariantViolation(
                    "[$phase] Occurs-check violation: $typeVar appears in ${type.javaClass.simpleName}"
                )
            }
        }
    }

    fun checkSubstitutionIdempotence(substitution: Substitution, phase: String) {
        if (!enabled) return

        for ((_, type) in substitution.mappings) {
            val applied = substitution.apply(type)
            if (applied != type) {
                throw InvariantViolation(
                    "[$phase] Non-idempotent substitution: applying to RHS changed " +
                    "${type.javaClass.simpleName} → ${applied.javaClass.simpleName}"
                )
            }
        }
    }

    fun checkConstraintResultsConsistent(
        constraintResults: Map<String, ConstraintResult>,
        phase: String,
    ) {
        if (!enabled) return

        for ((key, result) in constraintResults) {
            val freeVars = collectFreeTypeVars(result.type)
            for (tv in freeVars) {
                if (tv.name.startsWith(com.mezze.language.ast.MagicParams.INTERNAL_PREFIX)) {
                    throw InvariantViolation(
                        "[$phase] Internal type variable leaked into constraint result for '$key': $tv"
                    )
                }
            }
        }
    }

    /**
     * S0.2 probe (warn-only, measurement phase). Reports emitted constraint-result
     * types that still contain a bare VarType, so we can distinguish LEGITIMATE
     * polymorphism (`let id = {x} -> x` : `'a -> 'a`, a fine generalized type) from
     * a genuinely UNRESOLVED receiver that will mis-dispatch downstream (the
     * "defaults to Bytes" class). This does NOT throw — enabling it as a hard
     * invariant requires first characterising which VarTypes are expected. Gated
     * behind `mezze.invariants.probe=true` so it never affects the normal gate.
     */
    fun probeUnresolvedVars(
        constraintResults: Map<String, ConstraintResult>,
        phase: String,
    ) {
        if (System.getProperty("mezze.invariants.probe", "false") != "true") return
        for ((key, result) in constraintResults) {
            val vars = collectFreeTypeVars(result.type)
            if (vars.isNotEmpty()) {
                System.err.println(
                    "[invariant-probe $phase] '$key' emitted type has ${vars.size} free var(s): " +
                    vars.joinToString(", ") { it.name } + "  ::  ${result.type}"
                )
            }
        }
    }
}
