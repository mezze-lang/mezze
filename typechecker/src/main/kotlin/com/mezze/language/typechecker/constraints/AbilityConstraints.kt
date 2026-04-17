package com.mezze.language.typechecker.constraints

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar

/**
 * Ability constraint generator - generates ability requirements.
 *
 * Abilities (type classes) appear in:
 * - Function constraints: {x: 'a} -> 'a where 'a: NumAdd
 * - Method calls: NumAdd.add {x = a, y = b} requires NumAdd on type of a/b
 * - Operators: a + b requires NumAdd
 * - Pattern matching with Eq: requires Eq on matched type
 *
 * For each ability usage, we:
 * 1. Determine which type variable needs the ability
 * 2. Generate AbilityRequired constraint
 * 3. Track ability for later validation/resolution
 *
 * Design:
 * - Pure functions (no mutation!)
 * - Generates AbilityRequired constraints
 * - Works with constraint solver to satisfy constraints
 */
class AbilityConstraintGenerator {

    /**
     * Generate ability constraints from function type.
     *
     * When a function has ability constraints like:
     *   {x: 'a} -> 'a where 'a: NumAdd
     *
     * We need to ensure those constraints are satisfied.
     */
    fun generateFromFunctionType(
        functionType: MezzeType.FunctionType,
        typeSubstitution: Map<TypeVar, MezzeType> = emptyMap()
    ): List<Constraint.AbilityRequired> {
        val constraints = mutableListOf<Constraint.AbilityRequired>()

        for (abilityConstraint in functionType.abilityConstraints) {
            // FunctionAbilityConstraint has typeArgs (List<MezzeType>)
            // We need to generate constraints for each type argument that's a type variable
            for (typeArg in abilityConstraint.typeArgs) {
                val actualType = when (typeArg) {
                    is MezzeType.VarType -> typeSubstitution[typeArg.variable] ?: typeArg
                    else -> typeArg
                }

                // If it's still a type variable, add constraint
                if (actualType is MezzeType.VarType) {
                    constraints.add(
                        Constraint.AbilityRequired(
                            typeVar = actualType.variable,
                            abilityName = abilityConstraint.abilityName,
                            typeArgs = abilityConstraint.typeArgs,
                            context = "Function ability constraint"
                        )
                    )
                }
                // If it's a concrete type, validation will check if ability is satisfied
            }
        }

        return constraints
    }

    /**
     * Generate ability constraint for a specific type and ability.
     *
     * This is a helper for generating explicit ability requirements.
     */
    fun generateConstraint(
        type: MezzeType,
        abilityName: String,
        typeArgs: List<MezzeType> = emptyList(),
        context: String? = null
    ): Constraint.AbilityRequired? {
        // Only generate constraint if type is a type variable
        return when (type) {
            is MezzeType.VarType -> Constraint.AbilityRequired(
                typeVar = type.variable,
                abilityName = abilityName,
                typeArgs = typeArgs,
                context = context
            )
            // For concrete types, validation will check ability satisfaction
            else -> null
        }
    }

    /**
     * Common built-in abilities that are frequently used.
     */
    object BuiltInAbilities {
        const val NUM_ADD = "NumAdd"
        const val NUM_SUB = "NumSub"
        const val NUM_MUL = "NumMul"
        const val NUM_DIV = "NumDiv"
        const val NUM_MOD = "NumMod"
        const val NUM_NEG = "NumNeg"

        const val EQ = "Eq"
        const val ORD = "Ord"
        const val INSPECT = "Inspect"

        const val ITERABLE = "Iterable"
        const val COLLECTION = "Collection"

        /**
         * Check if ability is a built-in that all types have.
         * Example: Inspect is implicitly available for all types.
         */
        fun isUniversal(abilityName: String): Boolean {
            return when (abilityName) {
                INSPECT -> true  // All types can be inspected
                else -> false
            }
        }

        /**
         * Check if ability is derivable automatically.
         * Example: Eq can be derived for records/variants.
         */
        fun isDerivable(abilityName: String): Boolean {
            return when (abilityName) {
                EQ -> true      // Can derive equality for many types
                ORD -> true     // Can derive ordering if Eq is satisfied
                INSPECT -> true // Can derive inspection
                else -> false
            }
        }
    }
}
