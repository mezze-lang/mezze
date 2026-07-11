package com.mezze.language.typechecker.context

import com.mezze.language.ast.AbilityConstraint
import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.RowVar
import com.mezze.language.ast.TypeVar
import com.mezze.language.ast.solving.Substitution
import com.mezze.language.ast.solving.collectFreeTypeVars

/**
 * Type scheme for polymorphic types.
 *
 * Represents a type with universally quantified type variables.
 * Example: forall 'a 'b. ('a, 'b) -> 'a
 *
 * Replaces ad-hoc polymorphism handling with proper type schemes.
 */
data class TypeScheme(
    val quantifiedVars: List<TypeVar>,
    val constraints: List<AbilityConstraint>,
    val type: MezzeType
) {
    /**
     * Check if this is a monomorphic type (no quantified vars)
     */
    val isMonomorphic: Boolean
        get() = quantifiedVars.isEmpty()

    /**
     * Instantiate this type scheme with fresh type variables.
     * Returns a substitution map and the instantiated type.
     */
    fun instantiate(): InstantiationResult {
        if (isMonomorphic) {
            // Convert AbilityConstraint to FreshAbilityConstraint for monomorphic case
            val monoConstraints = constraints.map { constraint ->
                FreshAbilityConstraint(
                    abilityName = constraint.abilityName,
                    typeVar = TypeVar.freshWithName(constraint.typeParam),
                    typeArgs = constraint.typeArgs  // Preserve type args
                )
            }
            return InstantiationResult(
                type = type,
                substitution = emptyMap(),
                freshConstraints = monoConstraints
            )
        }

        // Create fresh type variables for each quantified variable
        val substitution = quantifiedVars.associateWith {
            MezzeType.VarType(TypeVar.fresh())
        }

        // Apply substitution to type
        val instantiatedType = applySubstitutionToType(type, substitution)

        // Create a lookup from type param NAME to the fresh TypeVar
        // This handles both "'a" and "a" formats
        val nameToFreshVar = mutableMapOf<String, TypeVar>()
        for ((oldVar, newType) in substitution) {
            // Map both with and without the ' prefix
            val baseName = oldVar.name.removePrefix("'")
            nameToFreshVar[baseName] = newType.variable
            nameToFreshVar["'$baseName"] = newType.variable
            nameToFreshVar[oldVar.name] = newType.variable
        }

        // Instantiate constraints with fresh variables
        // Use the name lookup to get the correct fresh TypeVar
        val freshConstraintsWithVars = constraints.mapNotNull { constraint ->
            val baseName = constraint.typeParam.removePrefix("'")
            val freshTypeVar = nameToFreshVar[baseName] ?: nameToFreshVar[constraint.typeParam]
            // Apply substitution to type args (they may contain type variables)
            val instantiatedTypeArgs = constraint.typeArgs.map { applySubstitutionToType(it, substitution) }
            if (freshTypeVar != null) {
                FreshAbilityConstraint(
                    abilityName = constraint.abilityName,
                    typeVar = freshTypeVar,
                    typeArgs = instantiatedTypeArgs
                )
            } else {
                // Not quantified - create a TypeVar from the name for now
                FreshAbilityConstraint(
                    abilityName = constraint.abilityName,
                    typeVar = TypeVar.freshWithName(constraint.typeParam),
                    typeArgs = instantiatedTypeArgs
                )
            }
        }

        return InstantiationResult(
            type = instantiatedType,
            substitution = substitution,
            freshConstraints = freshConstraintsWithVars
        )
    }

    /**
     * Result of instantiating a type scheme
     */
    data class InstantiationResult(
        val type: MezzeType,
        val substitution: Map<TypeVar, MezzeType>,
        val freshConstraints: List<FreshAbilityConstraint>
    )

    /**
     * Ability constraint with the actual fresh TypeVar (not just a name string).
     * This is used during instantiation to preserve TypeVar identity.
     */
    data class FreshAbilityConstraint(
        val abilityName: String,
        val typeVar: TypeVar,
        val typeArgs: List<MezzeType> = emptyList()  // Additional type arguments for multi-parameter abilities (e.g., Json in Encode Json 'a)
    )

    companion object {
        /**
         * Create a monomorphic type scheme (no quantifiers)
         */
        fun mono(type: MezzeType): TypeScheme {
            return TypeScheme(
                quantifiedVars = emptyList(),
                constraints = emptyList(),
                type = type
            )
        }

        /**
         * Generalize a type into a type scheme.
         * Quantifies free type variables that don't appear in the context.
         */
        fun generalize(
            type: MezzeType,
            contextVars: Set<TypeVar>,
            constraints: List<AbilityConstraint>
        ): TypeScheme {
            val typeVars = collectFreeTypeVars(type)
            val quantifiable = typeVars - contextVars

            return TypeScheme(
                quantifiedVars = quantifiable.toList(),
                constraints = constraints.filter { constraint ->
                    // Only include constraints on quantified variables
                    quantifiable.any { it.name == constraint.typeParam }
                },
                type = type
            )
        }
    }
}

/**
 * Apply substitution to a type for TypeScheme instantiation.
 *
 * Delegates to [Substitution.apply] for the main type-walking logic, then
 * clears abilityConstraints on every FunctionType in the result. The
 * constraints are already captured in [TypeScheme.constraints] and will be
 * instantiated as freshConstraints; keeping them on the FunctionType would
 * double-count them when the function is called.
 */
private fun applySubstitutionToType(
    type: MezzeType,
    substitution: Map<TypeVar, MezzeType>
): MezzeType {
    val applied = Substitution(substitution).apply(type)
    return clearAbilityConstraints(applied)
}

/**
 * Recursively clear abilityConstraints from FunctionTypes.
 * Used during TypeScheme instantiation to avoid double-counting.
 */
private fun clearAbilityConstraints(type: MezzeType): MezzeType = when (type) {
    is MezzeType.FunctionType -> MezzeType.FunctionType(
        paramType = clearAbilityConstraints(type.paramType),
        returnType = clearAbilityConstraints(type.returnType),
        effects = type.effects,
        abilityConstraints = emptyList()
    )
    is MezzeType.RecordType -> if (type.fields.values.any { it is MezzeType.FunctionType }) {
        MezzeType.RecordType(
            fields = type.fields.mapValues { (_, ft) -> clearAbilityConstraints(ft) },
            rowVar = type.rowVar, typeName = type.typeName, typeHash = type.typeHash
        )
    } else type
    is MezzeType.VariantType -> if (type.constructors.values.any { it is MezzeType.FunctionType }) {
        MezzeType.VariantType(
            constructors = type.constructors.mapValues { (_, dt) -> dt?.let { clearAbilityConstraints(it) } },
            rowVar = type.rowVar, typeName = type.typeName,
            typeArgs = type.typeArgs, typeHash = type.typeHash, nominalWall = type.nominalWall
        )
    } else type
    else -> type
}

