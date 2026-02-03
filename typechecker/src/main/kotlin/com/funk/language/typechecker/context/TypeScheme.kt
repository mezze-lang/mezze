package com.funk.language.typechecker.context

import com.funk.language.ast.*

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
    val type: FunkType
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
            return InstantiationResult(
                type = type,
                substitution = emptyMap(),
                freshConstraints = constraints
            )
        }

        // Create fresh type variables for each quantified variable
        val substitution = quantifiedVars.associateWith {
            FunkType.VarType(TypeVar.fresh())
        }

        // Apply substitution to type
        val instantiatedType = applySubstitutionToType(type, substitution)

        // Instantiate constraints with fresh variables
        val freshConstraints = constraints.map { constraint ->
            val freshTypeParam = substitution[TypeVar(constraint.typeParam)]
            when (freshTypeParam) {
                is FunkType.VarType -> AbilityConstraint(
                    constraint.abilityName,
                    freshTypeParam.variable.name
                )
                null -> constraint // Not quantified, keep as-is
                else -> constraint // Shouldn't happen, but keep constraint
            }
        }

        return InstantiationResult(
            type = instantiatedType,
            substitution = substitution,
            freshConstraints = freshConstraints
        )
    }

    /**
     * Result of instantiating a type scheme
     */
    data class InstantiationResult(
        val type: FunkType,
        val substitution: Map<TypeVar, FunkType>,
        val freshConstraints: List<AbilityConstraint>
    )

    companion object {
        /**
         * Create a monomorphic type scheme (no quantifiers)
         */
        fun mono(type: FunkType): TypeScheme {
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
            type: FunkType,
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
 * Apply substitution to a type.
 * Helper function for instantiation.
 */
private fun applySubstitutionToType(
    type: FunkType,
    substitution: Map<TypeVar, FunkType>
): FunkType {
    return when (type) {
        is FunkType.VarType -> {
            substitution[type.variable] ?: type
        }
        is FunkType.FunctionType -> {
            FunkType.FunctionType(
                paramType = applySubstitutionToType(type.paramType, substitution),
                returnType = applySubstitutionToType(type.returnType, substitution),
                effects = type.effects,
                abilityConstraints = type.abilityConstraints
            )
        }
        is FunkType.RecordType -> {
            FunkType.RecordType(
                fields = type.fields.mapValues { (_, fieldType) ->
                    applySubstitutionToType(fieldType, substitution)
                },
                rowVar = type.rowVar  // RowVar doesn't get substituted
            )
        }
        is FunkType.VariantType -> {
            FunkType.VariantType(
                constructors = type.constructors.mapValues { (_, payloadType) ->
                    payloadType?.let { applySubstitutionToType(it, substitution) }
                },
                rowVar = type.rowVar  // RowVar doesn't get substituted
            )
        }
        is FunkType.TypeApplication -> {
            FunkType.TypeApplication(
                constructor = type.constructor,
                args = type.args.map { applySubstitutionToType(it, substitution) }
            )
        }
        is FunkType.ArrayType -> {
            FunkType.ArrayType(
                elementType = applySubstitutionToType(type.elementType, substitution)
            )
        }
        is FunkType.ListType -> {
            FunkType.ListType(
                elementType = applySubstitutionToType(type.elementType, substitution)
            )
        }
        is FunkType.SetType -> {
            FunkType.SetType(
                elementType = applySubstitutionToType(type.elementType, substitution)
            )
        }
        is FunkType.MapType -> {
            FunkType.MapType(
                keyType = applySubstitutionToType(type.keyType, substitution),
                valueType = applySubstitutionToType(type.valueType, substitution)
            )
        }
        is FunkType.SuspendedType -> {
            FunkType.SuspendedType(
                valueType = applySubstitutionToType(type.valueType, substitution),
                effects = type.effects
            )
        }
        is FunkType.EffectRow -> {
            // Effect rows don't contain substitutable type variables
            type
        }
        // Concrete types don't change
        is FunkType.IntType,
        is FunkType.FloatType,
        is FunkType.StringType,
        is FunkType.BooleanType,
        is FunkType.NatType,
        is FunkType.FracType,
        is FunkType.OrderingType -> type
    }
}

/**
 * Collect free type variables in a type.
 * Helper for generalization.
 * Made public for use in ConstraintGenerator for let-polymorphism.
 */
fun collectFreeTypeVars(type: FunkType): Set<TypeVar> {
    return when (type) {
        is FunkType.VarType -> setOf(type.variable)
        is FunkType.FunctionType -> {
            collectFreeTypeVars(type.paramType) +
            collectFreeTypeVars(type.returnType)
        }
        is FunkType.RecordType -> {
            // Row variables are not type variables, so we don't include them
            type.fields.values.flatMap { collectFreeTypeVars(it) }.toSet()
        }
        is FunkType.VariantType -> {
            // Row variables are not type variables, so we don't include them
            type.constructors.values
                .filterNotNull()
                .flatMap { collectFreeTypeVars(it) }
                .toSet()
        }
        is FunkType.TypeApplication -> {
            type.args.flatMap { collectFreeTypeVars(it) }.toSet()
        }
        is FunkType.ArrayType -> {
            collectFreeTypeVars(type.elementType)
        }
        is FunkType.ListType -> {
            collectFreeTypeVars(type.elementType)
        }
        is FunkType.SetType -> {
            collectFreeTypeVars(type.elementType)
        }
        is FunkType.MapType -> {
            collectFreeTypeVars(type.keyType) + collectFreeTypeVars(type.valueType)
        }
        is FunkType.SuspendedType -> {
            collectFreeTypeVars(type.valueType)
        }
        is FunkType.EffectRow -> {
            // Effect rows don't contain type variables
            emptySet()
        }
        // Concrete types have no free variables
        is FunkType.IntType,
        is FunkType.FloatType,
        is FunkType.StringType,
        is FunkType.BooleanType,
        is FunkType.NatType,
        is FunkType.FracType,
        is FunkType.OrderingType -> emptySet()
    }
}
