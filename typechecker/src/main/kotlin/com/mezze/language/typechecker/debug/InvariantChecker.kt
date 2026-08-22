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
}
