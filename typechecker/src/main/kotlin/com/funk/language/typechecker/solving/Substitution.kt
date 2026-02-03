package com.funk.language.typechecker.solving

import com.funk.language.ast.*

/**
 * Type substitution - foundation for constraint solving.
 *
 * A substitution is an immutable mapping from type variables to types.
 * Applying a substitution replaces type variables with their assigned types.
 *
 * Key operations:
 * - apply: Apply substitution to a type
 * - compose: Combine two substitutions
 * - occurs check: Prevent infinite types
 *
 * Design:
 * - Immutable data structure (val, not var)
 * - Pure functions (no side effects)
 * - Exhaustive pattern matching on FunkType
 */
data class Substitution(
    val mappings: Map<TypeVar, FunkType> = emptyMap()
) {
    /**
     * Apply this substitution to a type.
     *
     * Recursively replaces type variables with their mappings.
     * Example:
     *   subst = {α -> Int, β -> String}
     *   apply(List α) = List Int
     *   apply({x: α, y: β}) = {x: Int, y: String}
     */
    fun apply(type: FunkType): FunkType {
        return when (type) {
            is FunkType.VarType -> {
                // Look up the type variable in mappings
                val substitutedType = mappings[type.variable]
                if (substitutedType != null) {
                    // Apply substitution recursively to handle chains
                    // Example: {α -> β, β -> Int}, apply(α) = Int
                    apply(substitutedType)
                } else {
                    // Type variable not in substitution, keep as-is
                    type
                }
            }

            is FunkType.FunctionType -> FunkType.FunctionType(
                paramType = apply(type.paramType),
                returnType = apply(type.returnType),
                effects = type.effects?.let { applyToEffectRow(it) },
                abilityConstraints = type.abilityConstraints.map { constraint ->
                    FunkType.FunctionAbilityConstraint(
                        abilityName = constraint.abilityName,
                        typeArgs = constraint.typeArgs.map { apply(it) }
                    )
                }
            )

            is FunkType.RecordType -> FunkType.RecordType(
                fields = type.fields.mapValues { (_, fieldType) -> apply(fieldType) },
                rowVar = type.rowVar // Row variables handled separately in row unification
            )

            is FunkType.VariantType -> FunkType.VariantType(
                constructors = type.constructors.mapValues { (_, dataType) ->
                    dataType?.let { apply(it) }
                },
                rowVar = type.rowVar // Row variables handled separately
            )

            is FunkType.TypeApplication -> FunkType.TypeApplication(
                constructor = type.constructor,
                args = type.args.map { apply(it) }
            )

            is FunkType.ArrayType -> FunkType.ArrayType(
                elementType = apply(type.elementType)
            )

            is FunkType.ListType -> FunkType.ListType(
                elementType = apply(type.elementType)
            )

            is FunkType.SetType -> FunkType.SetType(
                elementType = apply(type.elementType)
            )

            is FunkType.MapType -> FunkType.MapType(
                keyType = apply(type.keyType),
                valueType = apply(type.valueType)
            )

            is FunkType.SuspendedType -> FunkType.SuspendedType(
                valueType = apply(type.valueType),
                effects = applyToEffectRow(type.effects)
            )

            is FunkType.EffectRow -> applyToEffectRow(type)

            // Concrete types remain unchanged
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
     * Apply substitution to an effect row.
     *
     * Effect rows contain effect names (not types), so we only need to
     * handle the row variable if present.
     */
    private fun applyToEffectRow(effectRow: FunkType.EffectRow): FunkType.EffectRow {
        // Effect names are not types, so they don't change
        // Row variables are handled separately in row unification
        return effectRow
    }

    /**
     * Compose this substitution with another.
     *
     * (s1 compose s2) means: first apply s2, then apply s1
     *
     * Example:
     *   s1 = {α -> Int}
     *   s2 = {β -> α}
     *   s1.compose(s2) = {α -> Int, β -> Int}
     */
    fun compose(other: Substitution): Substitution {
        // Apply s1 to all mappings in s2
        val composedMappings = other.mappings.mapValues { (_, type) ->
            apply(type)
        }

        // Combine with s1's mappings (s1 takes precedence)
        return Substitution(mappings + composedMappings)
    }

    /**
     * Check if this substitution is empty.
     */
    val isEmpty: Boolean
        get() = mappings.isEmpty()

    /**
     * Get the domain (set of type variables being substituted).
     */
    val domain: Set<TypeVar>
        get() = mappings.keys

    /**
     * Get the range (set of types being substituted to).
     */
    val range: Set<FunkType>
        get() = mappings.values.toSet()

    companion object {
        /**
         * Empty substitution (identity).
         */
        val empty = Substitution()

        /**
         * Create a substitution from a single mapping.
         */
        fun single(typeVar: TypeVar, type: FunkType): Substitution {
            return Substitution(mapOf(typeVar to type))
        }

        /**
         * Create a substitution from multiple mappings.
         */
        fun fromMappings(vararg pairs: Pair<TypeVar, FunkType>): Substitution {
            return Substitution(pairs.toMap())
        }
    }

    override fun toString(): String {
        if (mappings.isEmpty()) return "{}"
        val entries = mappings.entries.take(5).joinToString(", ") { (typeVar, type) ->
            "$typeVar -> ${type.javaClass.simpleName}"
        }
        val suffix = if (mappings.size > 5) ", ..." else ""
        return "{$entries$suffix}"
    }
}

/**
 * Occurs check - prevent infinite types.
 *
 * Checks if a type variable occurs anywhere inside a type.
 * This prevents creating infinite types like: α = List α
 *
 * Example:
 *   occursCheck(α, List α) = true  (α occurs in List α, would create infinite type)
 *   occursCheck(α, List Int) = false (α doesn't occur, substitution is safe)
 */
fun occursCheck(typeVar: TypeVar, type: FunkType): Boolean {
    return when (type) {
        is FunkType.VarType -> typeVar == type.variable

        is FunkType.FunctionType ->
            occursCheck(typeVar, type.paramType) ||
            occursCheck(typeVar, type.returnType) ||
            type.abilityConstraints.any { constraint ->
                constraint.typeArgs.any { occursCheck(typeVar, it) }
            }

        is FunkType.RecordType ->
            type.fields.values.any { occursCheck(typeVar, it) }

        is FunkType.VariantType ->
            type.constructors.values.any { dataType ->
                dataType != null && occursCheck(typeVar, dataType)
            }

        is FunkType.TypeApplication ->
            type.args.any { occursCheck(typeVar, it) }

        is FunkType.ArrayType -> occursCheck(typeVar, type.elementType)
        is FunkType.ListType -> occursCheck(typeVar, type.elementType)
        is FunkType.SetType -> occursCheck(typeVar, type.elementType)

        is FunkType.MapType ->
            occursCheck(typeVar, type.keyType) ||
            occursCheck(typeVar, type.valueType)

        is FunkType.SuspendedType -> occursCheck(typeVar, type.valueType)

        is FunkType.EffectRow -> false // Effect rows don't contain type variables

        // Concrete types can't contain type variables
        is FunkType.IntType,
        is FunkType.FloatType,
        is FunkType.StringType,
        is FunkType.BooleanType,
        is FunkType.NatType,
        is FunkType.FracType,
        is FunkType.OrderingType -> false
    }
}

/**
 * Collect all free type variables in a type.
 *
 * Free variables are type variables that aren't bound by a quantifier.
 * Used for generalization in let-polymorphism.
 */
fun collectFreeTypeVars(type: FunkType): Set<TypeVar> {
    return when (type) {
        is FunkType.VarType -> setOf(type.variable)

        is FunkType.FunctionType -> {
            val paramVars = collectFreeTypeVars(type.paramType)
            val returnVars = collectFreeTypeVars(type.returnType)
            val constraintVars = type.abilityConstraints.flatMap { constraint ->
                constraint.typeArgs.flatMap { collectFreeTypeVars(it) }
            }.toSet()
            paramVars + returnVars + constraintVars
        }

        is FunkType.RecordType ->
            type.fields.values.flatMap { collectFreeTypeVars(it) }.toSet()

        is FunkType.VariantType ->
            type.constructors.values.flatMap { dataType ->
                dataType?.let { collectFreeTypeVars(it) } ?: emptySet()
            }.toSet()

        is FunkType.TypeApplication ->
            type.args.flatMap { collectFreeTypeVars(it) }.toSet()

        is FunkType.ArrayType -> collectFreeTypeVars(type.elementType)
        is FunkType.ListType -> collectFreeTypeVars(type.elementType)
        is FunkType.SetType -> collectFreeTypeVars(type.elementType)

        is FunkType.MapType ->
            collectFreeTypeVars(type.keyType) + collectFreeTypeVars(type.valueType)

        is FunkType.SuspendedType -> collectFreeTypeVars(type.valueType)

        is FunkType.EffectRow -> emptySet()

        // Concrete types have no type variables
        is FunkType.IntType,
        is FunkType.FloatType,
        is FunkType.StringType,
        is FunkType.BooleanType,
        is FunkType.NatType,
        is FunkType.FracType,
        is FunkType.OrderingType -> emptySet()
    }
}
