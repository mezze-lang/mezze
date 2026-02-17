package com.mezze.language.typechecker.context

import com.mezze.language.ast.*

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
                    typeVar = TypeVar(constraint.typeParam)
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
            if (newType is MezzeType.VarType) {
                // Map both with and without the ' prefix
                val baseName = oldVar.name.removePrefix("'")
                nameToFreshVar[baseName] = newType.variable
                nameToFreshVar["'$baseName"] = newType.variable
                nameToFreshVar[oldVar.name] = newType.variable
            }
        }

        // Instantiate constraints with fresh variables
        // Use the name lookup to get the correct fresh TypeVar
        val freshConstraintsWithVars = constraints.mapNotNull { constraint ->
            val baseName = constraint.typeParam.removePrefix("'")
            val freshTypeVar = nameToFreshVar[baseName] ?: nameToFreshVar[constraint.typeParam]
            if (freshTypeVar != null) {
                FreshAbilityConstraint(
                    abilityName = constraint.abilityName,
                    typeVar = freshTypeVar
                )
            } else {
                // Not quantified - create a TypeVar from the name for now
                FreshAbilityConstraint(
                    abilityName = constraint.abilityName,
                    typeVar = TypeVar(constraint.typeParam)
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
        val typeVar: TypeVar
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
 * Apply substitution to a type.
 * Helper function for instantiation.
 */
private fun applySubstitutionToType(
    type: MezzeType,
    substitution: Map<TypeVar, MezzeType>
): MezzeType {
    return when (type) {
        is MezzeType.VarType -> {
            substitution[type.variable] ?: type
        }
        is MezzeType.FunctionType -> {
            MezzeType.FunctionType(
                paramType = applySubstitutionToType(type.paramType, substitution),
                returnType = applySubstitutionToType(type.returnType, substitution),
                effects = type.effects,
                abilityConstraints = type.abilityConstraints
            )
        }
        is MezzeType.RecordType -> {
            MezzeType.RecordType(
                fields = type.fields.mapValues { (_, fieldType) ->
                    applySubstitutionToType(fieldType, substitution)
                },
                rowVar = type.rowVar  // RowVar doesn't get substituted
            )
        }
        is MezzeType.VariantType -> {
            MezzeType.VariantType(
                constructors = type.constructors.mapValues { (_, payloadType) ->
                    payloadType?.let { applySubstitutionToType(it, substitution) }
                },
                rowVar = type.rowVar  // RowVar doesn't get substituted
            )
        }
        is MezzeType.TypeApplication -> {
            MezzeType.TypeApplication(
                constructor = type.constructor,
                args = type.args.map { applySubstitutionToType(it, substitution) }
            )
        }
        is MezzeType.ArrayType -> {
            MezzeType.ArrayType(
                elementType = applySubstitutionToType(type.elementType, substitution)
            )
        }
        is MezzeType.ListType -> {
            MezzeType.ListType(
                elementType = applySubstitutionToType(type.elementType, substitution)
            )
        }
        is MezzeType.SetType -> {
            MezzeType.SetType(
                elementType = applySubstitutionToType(type.elementType, substitution)
            )
        }
        is MezzeType.SortedMapType -> {
            MezzeType.SortedMapType(
                keyType = applySubstitutionToType(type.keyType, substitution),
                valueType = applySubstitutionToType(type.valueType, substitution)
            )
        }
        is MezzeType.SortedSetType -> {
            MezzeType.SortedSetType(
                elementType = applySubstitutionToType(type.elementType, substitution)
            )
        }
        is MezzeType.MapType -> {
            MezzeType.MapType(
                keyType = applySubstitutionToType(type.keyType, substitution),
                valueType = applySubstitutionToType(type.valueType, substitution)
            )
        }
        is MezzeType.SuspendedType -> {
            MezzeType.SuspendedType(
                valueType = applySubstitutionToType(type.valueType, substitution),
                effects = type.effects
            )
        }
        is MezzeType.EffectRow -> {
            // Effect rows don't contain substitutable type variables
            type
        }
        // Concrete types don't change
        is MezzeType.IntType,
        is MezzeType.FloatType,
        is MezzeType.StringType,
        is MezzeType.BooleanType,
        is MezzeType.NatType,
        is MezzeType.FracType,
        is MezzeType.OrderingType,
        is MezzeType.BytesType,
        is MezzeType.ByteBufferType,
        is MezzeType.CharType -> type
    }
}

/**
 * Collect free type variables in a type.
 * Helper for generalization.
 * Made public for use in ConstraintGenerator for let-polymorphism.
 */
fun collectFreeTypeVars(type: MezzeType): Set<TypeVar> {
    return when (type) {
        is MezzeType.VarType -> setOf(type.variable)
        is MezzeType.FunctionType -> {
            collectFreeTypeVars(type.paramType) +
            collectFreeTypeVars(type.returnType)
        }
        is MezzeType.RecordType -> {
            // Row variables are not type variables, so we don't include them
            type.fields.values.flatMap { collectFreeTypeVars(it) }.toSet()
        }
        is MezzeType.VariantType -> {
            // Row variables are not type variables, so we don't include them
            type.constructors.values
                .filterNotNull()
                .flatMap { collectFreeTypeVars(it) }
                .toSet()
        }
        is MezzeType.TypeApplication -> {
            type.args.flatMap { collectFreeTypeVars(it) }.toSet()
        }
        is MezzeType.ArrayType -> {
            collectFreeTypeVars(type.elementType)
        }
        is MezzeType.ListType -> {
            collectFreeTypeVars(type.elementType)
        }
        is MezzeType.SetType -> {
            collectFreeTypeVars(type.elementType)
        }
        is MezzeType.SortedMapType -> {
            collectFreeTypeVars(type.keyType) + collectFreeTypeVars(type.valueType)
        }
        is MezzeType.SortedSetType -> {
            collectFreeTypeVars(type.elementType)
        }
        is MezzeType.MapType -> {
            collectFreeTypeVars(type.keyType) + collectFreeTypeVars(type.valueType)
        }
        is MezzeType.SuspendedType -> {
            collectFreeTypeVars(type.valueType)
        }
        is MezzeType.EffectRow -> {
            // Effect rows don't contain type variables
            emptySet()
        }
        // Concrete types have no free variables
        is MezzeType.IntType,
        is MezzeType.FloatType,
        is MezzeType.StringType,
        is MezzeType.BooleanType,
        is MezzeType.NatType,
        is MezzeType.FracType,
        is MezzeType.OrderingType,
        is MezzeType.BytesType,
        is MezzeType.ByteBufferType,
        is MezzeType.CharType -> emptySet()
    }
}
