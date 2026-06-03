package com.mezze.language.typechecker.solving

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.RowVar
import com.mezze.language.ast.TypeVar

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
 * - Exhaustive pattern matching on MezzeType
 */
data class Substitution(
    val mappings: Map<TypeVar, MezzeType> = emptyMap()
) {
    /**
     * Apply this substitution to a type.
     *
     * Recursively replaces type variables with their mappings.
     * Example:
     *   subst = {α -> Int, β -> String}
     *   apply(List α) = List Int
     *   apply({x: α, y: β}) = {x: Int, y: String}
     *
     * Optimization: Short-circuits for empty substitutions and concrete types.
     */
    fun apply(type: MezzeType): MezzeType {
        // Short-circuit 1: Empty substitution - nothing to apply
        if (mappings.isEmpty()) {
            return type
        }

        // Short-circuit 2: Concrete primitive types have no type variables
        if (isConcreteType(type)) {
            return type
        }

        return applyWithVisited(type, emptySet())
    }

    /**
     * Check if a type is a concrete primitive type (no type variables possible).
     */
    private fun isConcreteType(type: MezzeType): Boolean = when (type) {
        is MezzeType.IntType,
        is MezzeType.FloatType,
        is MezzeType.StringType,
        is MezzeType.BoolType,
        is MezzeType.NatType,
        is MezzeType.FracType,
        is MezzeType.OrderingType,
        is MezzeType.BytesType,
        is MezzeType.ByteBufferType,
        is MezzeType.CharType -> true
        else -> false
    }

    /**
     * Check if a type needs substitution applied.
     * Returns true if the type contains any type variable that is in our mapping.
     * This is a quick check to avoid unnecessary recursion.
     */
    private fun needsSubstitution(type: MezzeType): Boolean {
        return when (type) {
            is MezzeType.VarType -> type.variable in mappings

            is MezzeType.FunctionType ->
                needsSubstitution(type.paramType) ||
                needsSubstitution(type.returnType) ||
                type.abilityConstraints.any { c -> c.typeArgs.any { needsSubstitution(it) } }

            is MezzeType.RecordType ->
                type.fields.values.any { needsSubstitution(it) }

            is MezzeType.VariantType ->
                type.constructors.values.any { it != null && needsSubstitution(it) } ||
                type.typeArgs.any { needsSubstitution(it) }

            is MezzeType.TypeApplication ->
                type.args.any { needsSubstitution(it) }

            is MezzeType.SeqType -> needsSubstitution(type.elementType)
            is MezzeType.ListType -> needsSubstitution(type.elementType)
            is MezzeType.SetType -> needsSubstitution(type.elementType)
            is MezzeType.SortedSetType -> needsSubstitution(type.elementType)
            is MezzeType.SortedMapType -> needsSubstitution(type.keyType) || needsSubstitution(type.valueType)
            is MezzeType.MapType -> needsSubstitution(type.keyType) || needsSubstitution(type.valueType)
            is MezzeType.SuspendedType -> needsSubstitution(type.valueType) || needsSubstitutionInEffectRow(type.effects)

            is MezzeType.NewtypeType -> needsSubstitution(type.wrappedType)

            // Effect rows may need substitution if they have effectInstances with TypeVars
            is MezzeType.EffectRow -> needsSubstitutionInEffectRow(type)

            // Concrete types don't need substitution
            is MezzeType.IntType,
            is MezzeType.FloatType,
            is MezzeType.StringType,
            is MezzeType.BoolType,
            is MezzeType.NatType,
            is MezzeType.FracType,
            is MezzeType.OrderingType,
            is MezzeType.BytesType,
            is MezzeType.ByteBufferType,
            is MezzeType.CharType -> false
        }
    }

    /**
     * Check if an effect row needs substitution applied.
     * Returns true if any effectInstance has an associated type containing a mapped TypeVar.
     */
    private fun needsSubstitutionInEffectRow(effectRow: MezzeType.EffectRow): Boolean {
        return effectRow.effectInstances.any { instance ->
            instance.associatedTypes.values.any { needsSubstitution(it) }
        }
    }

    /**
     * Internal apply function with cycle detection.
     * Tracks visited type variables to prevent infinite recursion from cyclic substitutions.
     */
    private fun applyWithVisited(type: MezzeType, visitedVars: Set<TypeVar>): MezzeType {
        // Short-circuit: if this type doesn't need substitution, return as-is
        // Only do this check at the top level (empty visitedVars) to avoid repeated checks
        if (visitedVars.isEmpty() && !needsSubstitution(type)) {
            return type
        }

        return when (type) {
            is MezzeType.VarType -> {
                // Cycle detection: if we've seen this variable before, stop recursion
                if (type.variable in visitedVars) {
                    return type // Return as-is to break cycle
                }

                // Look up the type variable in mappings
                val substitutedType = mappings[type.variable]
                if (substitutedType != null) {
                    // Apply substitution recursively to handle chains
                    // Example: {α -> β, β -> Int}, apply(α) = Int
                    // Track this variable as visited to detect cycles
                    applyWithVisited(substitutedType, visitedVars + type.variable)
                } else {
                    // Type variable not in substitution, keep as-is
                    type
                }
            }

            is MezzeType.FunctionType -> MezzeType.FunctionType(
                paramType = applyWithVisited(type.paramType, visitedVars),
                returnType = applyWithVisited(type.returnType, visitedVars),
                effects = type.effects?.let { applyToEffectRowWithVisited(it, visitedVars) },
                abilityConstraints = type.abilityConstraints.map { constraint ->
                    MezzeType.FunctionAbilityConstraint(
                        abilityName = constraint.abilityName,
                        typeArgs = constraint.typeArgs.map { applyWithVisited(it, visitedVars) },
                        abilityHash = constraint.abilityHash
                    )
                }
            )

            is MezzeType.RecordType -> MezzeType.RecordType(
                fields = type.fields.mapValues { (_, fieldType) -> applyWithVisited(fieldType, visitedVars) },
                rowVar = type.rowVar, // Row variables handled separately in row unification
                typeName = type.typeName,
                typeHash = type.typeHash
            )

            is MezzeType.VariantType -> {
                val appliedConstructors = type.constructors.mapValues { (_, dataType) ->
                    dataType?.let { applyWithVisited(it, visitedVars) }
                }
                MezzeType.VariantType(
                    constructors = appliedConstructors,
                    rowVar = type.rowVar, // Row variables handled separately
                    typeName = type.typeName, // Preserve type name for validation
                    typeArgs = type.typeArgs.map { applyWithVisited(it, visitedVars) }, // #194: substitute into nominal-arg vector
                    typeHash = type.typeHash, // Preserve content hash for rename-safe identity
                    nominalWall = type.nominalWall // #148: preserve nominal-wall flag
                )
            }

            is MezzeType.TypeApplication -> MezzeType.TypeApplication(
                constructor = type.constructor,
                args = type.args.map { applyWithVisited(it, visitedVars) },
                typeHash = type.typeHash
            )

            is MezzeType.SeqType -> MezzeType.SeqType(
                elementType = applyWithVisited(type.elementType, visitedVars)
            )

            is MezzeType.ListType -> MezzeType.ListType(
                elementType = applyWithVisited(type.elementType, visitedVars)
            )

            is MezzeType.SetType -> MezzeType.SetType(
                elementType = applyWithVisited(type.elementType, visitedVars)
            )

            is MezzeType.SortedMapType -> MezzeType.SortedMapType(
                keyType = applyWithVisited(type.keyType, visitedVars),
                valueType = applyWithVisited(type.valueType, visitedVars)
            )

            is MezzeType.SortedSetType -> MezzeType.SortedSetType(
                elementType = applyWithVisited(type.elementType, visitedVars)
            )

            is MezzeType.MapType -> MezzeType.MapType(
                keyType = applyWithVisited(type.keyType, visitedVars),
                valueType = applyWithVisited(type.valueType, visitedVars)
            )

            is MezzeType.SuspendedType -> MezzeType.SuspendedType(
                valueType = applyWithVisited(type.valueType, visitedVars),
                effects = applyToEffectRowWithVisited(type.effects, visitedVars)
            )

            is MezzeType.NewtypeType -> MezzeType.NewtypeType(
                type.name,
                type.constructor,
                applyWithVisited(type.wrappedType, visitedVars)
            )

            is MezzeType.EffectRow -> applyToEffectRowWithVisited(type, visitedVars)

            // Concrete types remain unchanged
            is MezzeType.IntType,
            is MezzeType.FloatType,
            is MezzeType.StringType,
            is MezzeType.BoolType,
            is MezzeType.NatType,
            is MezzeType.FracType,
            is MezzeType.OrderingType,
            is MezzeType.BytesType,
            is MezzeType.ByteBufferType,
            is MezzeType.CharType -> type
        }
    }

    /**
     * Apply substitution to an effect row.
     *
     * Effect rows contain:
     * - Effect names (Strings) - don't change
     * - EffectInstances with associated types (may contain TypeVars) - need substitution
     * - Row variable - handled separately in row unification
     */
    private fun applyToEffectRow(effectRow: MezzeType.EffectRow): MezzeType.EffectRow {
        return applyToEffectRowWithVisited(effectRow, emptySet())
    }

    /**
     * Apply substitution to an effect row (with cycle detection).
     *
     * Applies substitution to the associated types in effectInstances.
     * This ensures that type variables like 'error get resolved to concrete types like String.
     *
     * OPTIMIZATION: Only creates new objects if substitution actually changes something.
     */
    private fun applyToEffectRowWithVisited(effectRow: MezzeType.EffectRow, visitedVars: Set<TypeVar>): MezzeType.EffectRow {
        // Fast path: no effectInstances, nothing to substitute
        if (effectRow.effectInstances.isEmpty()) {
            return effectRow
        }

        // Check if any substitution would actually change the effectInstances
        var anyChanged = false
        val substitutedInstances = effectRow.effectInstances.map { instance ->
            if (instance.associatedTypes.isEmpty()) {
                instance
            } else {
                // Check if any associated type needs substitution
                val needsSub = instance.associatedTypes.values.any { needsSubstitution(it) }
                if (!needsSub) {
                    instance
                } else {
                    anyChanged = true
                    val substitutedAssocTypes = instance.associatedTypes.mapValues { (_, type) ->
                        applyWithVisited(type, visitedVars)
                    }
                    MezzeType.EffectInstance(instance.name, substitutedAssocTypes)
                }
            }
        }

        // OPTIMIZATION: Return original if nothing changed
        return if (anyChanged) effectRow.copy(effectInstances = substitutedInstances.toSet()) else effectRow
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
     *
     * IMPORTANT: s1's mappings take precedence for duplicate keys.
     * This allows updating a type variable's mapping when we learn more about it
     * (e.g., adding fields to a record type).
     */
    fun compose(other: Substitution): Substitution {
        // Simple merge: new mappings (this) take precedence over old mappings (other)
        // We DON'T apply this to other's values because that would bake in intermediate
        // results. Instead, we keep type variable references and let final `apply` resolve them.
        //
        // Example: if other = {v1 -> Variant(Pair -> v2)} and this = {v2 -> {fst}}
        // We want to keep v2 as a reference so later {v2 -> {fst, snd}} can update it.
        // If we applied here, Variant would have {fst} baked in.
        return Substitution(other.mappings + mappings)
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
    val range: Set<MezzeType>
        get() = mappings.values.toSet()

    companion object {
        /**
         * Empty substitution (identity).
         */
        val empty = Substitution()

        /**
         * Create a substitution from a single mapping.
         */
        fun single(typeVar: TypeVar, type: MezzeType): Substitution {
            return Substitution(mapOf(typeVar to type))
        }

        /**
         * Create a substitution from multiple mappings.
         */
        fun fromMappings(vararg pairs: Pair<TypeVar, MezzeType>): Substitution {
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
 * Close generated row variables in a type.
 *
 * After type inference is complete, auto-generated row variables (from RowVar.fresh())
 * should be closed (set to null) to produce closed record/variant types.
 * Only user-specified row variables (from RowVar.userOpen(), e.g. {x: Int, ...})
 * and row variables in the mustBeOpen set remain open.
 *
 * @param mustBeOpenRowVars Row variables that must stay open (from wildcard patterns etc.)
 *
 * Closes generated row variables that should not remain open.
 */
fun closeGeneratedRowVars(type: MezzeType, mustBeOpenRowVars: Set<RowVar> = emptySet()): MezzeType = when (type) {
    is MezzeType.RecordType -> {
        val existingRowVar = type.rowVar
        val closedRowVar = if (existingRowVar != null
            && !existingRowVar.isUserSpecified()
            && existingRowVar !in mustBeOpenRowVars) {
            null  // Close auto-generated row variable
        } else {
            existingRowVar  // Keep user-specified or must-be-open row variable
        }
        MezzeType.RecordType(
            fields = type.fields.mapValues { (_, fieldType) -> closeGeneratedRowVars(fieldType, mustBeOpenRowVars) },
            rowVar = closedRowVar,
            typeName = type.typeName,
            typeHash = type.typeHash
        )
    }
    is MezzeType.VariantType -> {
        val existingRowVar = type.rowVar
        val shouldStayOpen = existingRowVar != null && existingRowVar in mustBeOpenRowVars
        val closedRowVar = if (existingRowVar != null
            && !existingRowVar.isUserSpecified()
            && !shouldStayOpen) {
            null  // Close auto-generated row variable
        } else {
            existingRowVar  // Keep user-specified or must-be-open row variable
        }
        MezzeType.VariantType(
            constructors = type.constructors.mapValues { (_, payloadType) ->
                payloadType?.let { closeGeneratedRowVars(it, mustBeOpenRowVars) }
            },
            rowVar = closedRowVar,
            typeName = type.typeName,
            typeArgs = type.typeArgs.map { closeGeneratedRowVars(it, mustBeOpenRowVars) },
            typeHash = type.typeHash,
            nominalWall = type.nominalWall // #148: preserve nominal-wall flag
        )
    }
    is MezzeType.FunctionType -> MezzeType.FunctionType(
        paramType = closeGeneratedRowVars(type.paramType, mustBeOpenRowVars),
        returnType = closeGeneratedRowVars(type.returnType, mustBeOpenRowVars),
        effects = type.effects,
        abilityConstraints = type.abilityConstraints
    )
    is MezzeType.SeqType -> MezzeType.SeqType(closeGeneratedRowVars(type.elementType, mustBeOpenRowVars))
    is MezzeType.ListType -> MezzeType.ListType(closeGeneratedRowVars(type.elementType, mustBeOpenRowVars))
    is MezzeType.SetType -> MezzeType.SetType(closeGeneratedRowVars(type.elementType, mustBeOpenRowVars))
    is MezzeType.SortedMapType -> MezzeType.SortedMapType(
        keyType = closeGeneratedRowVars(type.keyType, mustBeOpenRowVars),
        valueType = closeGeneratedRowVars(type.valueType, mustBeOpenRowVars)
    )
    is MezzeType.SortedSetType -> MezzeType.SortedSetType(closeGeneratedRowVars(type.elementType, mustBeOpenRowVars))
    is MezzeType.MapType -> MezzeType.MapType(
        keyType = closeGeneratedRowVars(type.keyType, mustBeOpenRowVars),
        valueType = closeGeneratedRowVars(type.valueType, mustBeOpenRowVars)
    )
    is MezzeType.TypeApplication -> MezzeType.TypeApplication(
        constructor = type.constructor,
        args = type.args.map { closeGeneratedRowVars(it, mustBeOpenRowVars) },
        typeHash = type.typeHash
    )
    is MezzeType.SuspendedType -> MezzeType.SuspendedType(
        valueType = closeGeneratedRowVars(type.valueType, mustBeOpenRowVars),
        effects = type.effects
    )
    is MezzeType.NewtypeType -> MezzeType.NewtypeType(
        type.name,
        type.constructor,
        closeGeneratedRowVars(type.wrappedType, mustBeOpenRowVars)
    )
    // Types without row variables
    else -> type
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
fun occursCheck(typeVar: TypeVar, type: MezzeType): Boolean {
    return when (type) {
        is MezzeType.VarType -> typeVar == type.variable

        is MezzeType.FunctionType ->
            occursCheck(typeVar, type.paramType) ||
            occursCheck(typeVar, type.returnType) ||
            type.abilityConstraints.any { constraint ->
                constraint.typeArgs.any { occursCheck(typeVar, it) }
            }

        is MezzeType.RecordType ->
            type.fields.values.any { occursCheck(typeVar, it) }

        is MezzeType.VariantType ->
            type.constructors.values.any { dataType ->
                dataType != null && occursCheck(typeVar, dataType)
            } || type.typeArgs.any { occursCheck(typeVar, it) }

        is MezzeType.TypeApplication ->
            type.args.any { occursCheck(typeVar, it) }

        is MezzeType.SeqType -> occursCheck(typeVar, type.elementType)
        is MezzeType.ListType -> occursCheck(typeVar, type.elementType)
        is MezzeType.SetType -> occursCheck(typeVar, type.elementType)
        is MezzeType.SortedSetType -> occursCheck(typeVar, type.elementType)

        is MezzeType.SortedMapType ->
            occursCheck(typeVar, type.keyType) ||
            occursCheck(typeVar, type.valueType)

        is MezzeType.MapType ->
            occursCheck(typeVar, type.keyType) ||
            occursCheck(typeVar, type.valueType)

        is MezzeType.SuspendedType -> occursCheck(typeVar, type.valueType)

        is MezzeType.NewtypeType -> occursCheck(typeVar, type.wrappedType)

        is MezzeType.EffectRow -> false // Effect rows don't contain type variables

        // Concrete types can't contain type variables
        is MezzeType.IntType,
        is MezzeType.FloatType,
        is MezzeType.StringType,
        is MezzeType.BoolType,
        is MezzeType.NatType,
        is MezzeType.FracType,
        is MezzeType.OrderingType,
        is MezzeType.BytesType,
        is MezzeType.ByteBufferType,
        is MezzeType.CharType -> false
    }
}

/**
 * Collect all free type variables in a type.
 *
 * Free variables are type variables that aren't bound by a quantifier.
 * Used for generalization in let-polymorphism.
 */
fun collectFreeTypeVars(type: MezzeType): Set<TypeVar> {
    return when (type) {
        is MezzeType.VarType -> setOf(type.variable)

        is MezzeType.FunctionType -> {
            val paramVars = collectFreeTypeVars(type.paramType)
            val returnVars = collectFreeTypeVars(type.returnType)
            val constraintVars = type.abilityConstraints.flatMap { constraint ->
                constraint.typeArgs.flatMap { collectFreeTypeVars(it) }
            }.toSet()
            paramVars + returnVars + constraintVars
        }

        is MezzeType.RecordType ->
            type.fields.values.flatMap { collectFreeTypeVars(it) }.toSet()

        is MezzeType.VariantType ->
            (type.constructors.values.flatMap { dataType ->
                dataType?.let { collectFreeTypeVars(it) } ?: emptySet()
            } + type.typeArgs.flatMap { collectFreeTypeVars(it) }).toSet()

        is MezzeType.TypeApplication ->
            type.args.flatMap { collectFreeTypeVars(it) }.toSet()

        is MezzeType.SeqType -> collectFreeTypeVars(type.elementType)
        is MezzeType.ListType -> collectFreeTypeVars(type.elementType)
        is MezzeType.SetType -> collectFreeTypeVars(type.elementType)
        is MezzeType.SortedSetType -> collectFreeTypeVars(type.elementType)

        is MezzeType.SortedMapType ->
            collectFreeTypeVars(type.keyType) + collectFreeTypeVars(type.valueType)

        is MezzeType.MapType ->
            collectFreeTypeVars(type.keyType) + collectFreeTypeVars(type.valueType)

        is MezzeType.SuspendedType -> collectFreeTypeVars(type.valueType)

        is MezzeType.NewtypeType -> collectFreeTypeVars(type.wrappedType)

        is MezzeType.EffectRow -> emptySet()

        // Concrete types have no type variables
        is MezzeType.IntType,
        is MezzeType.FloatType,
        is MezzeType.StringType,
        is MezzeType.BoolType,
        is MezzeType.NatType,
        is MezzeType.FracType,
        is MezzeType.OrderingType,
        is MezzeType.BytesType,
        is MezzeType.ByteBufferType,
        is MezzeType.CharType -> emptySet()
    }
}
