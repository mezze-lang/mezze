package com.funk.language.ast

/**
 * Type utilities for normalization, substitution, and constraint handling.
 *
 * This file contains internal utilities for manipulating FunkType instances:
 * - Type variable normalization (renaming to 'a, 'b, 'c, etc.)
 * - Type variable substitution
 * - Type variable collection
 * - Constraint merging
 *
 * Part of the refactored AST module.
 */

// ============================================================================
// IDENTITY-BASED TYPE VARIABLE MAP
// ============================================================================

/**
 * A Map implementation that uses object identity (System.identityHashCode) for TypeVar keys.
 * This is necessary because TypeVar is a data class and uses structural equality by default,
 * but we sometimes need to distinguish type variables by identity (e.g., during normalization).
 */
private class IdentityBasedTypeVarMap(
    private val identityToValue: Map<Int, TypeVar>
) : Map<TypeVar, TypeVar> {

    override val entries: Set<Map.Entry<TypeVar, TypeVar>>
        get() = emptySet() // Not used by substituteTypeVariables

    override val keys: Set<TypeVar>
        get() = emptySet() // Not used by substituteTypeVariables

    override val size: Int
        get() = identityToValue.size

    override val values: Collection<TypeVar>
        get() = identityToValue.values

    override fun isEmpty(): Boolean = identityToValue.isEmpty()

    override fun get(key: TypeVar): TypeVar? =
        identityToValue[System.identityHashCode(key)]

    override fun containsKey(key: TypeVar): Boolean =
        identityToValue.containsKey(System.identityHashCode(key))

    override fun containsValue(value: TypeVar): Boolean =
        identityToValue.containsValue(value)
}

// ============================================================================
// TYPE VARIABLE COLLECTION
// ============================================================================

/**
 * Core function: Collect all type variables from a type in order of appearance (left-to-right, depth-first).
 * This is the single source of truth for type variable traversal - all other collection functions
 * should use this one.
 *
 * @param type The type to collect variables from
 * @param sortFields If true, sorts record fields and variant constructors alphabetically for consistent ordering.
 *                   Use true for normalization/printing, false for simple collection.
 * @return List of type variables in order of appearance (may contain duplicates)
 */
fun collectTypeVarsInOrder(type: FunkType, sortFields: Boolean = true): List<TypeVar> {
    val result = mutableListOf<TypeVar>()
    collectTypeVarsRecursive(type, result, sortFields)
    return result
}

/**
 * Collect type variables into a mutable set (for generalization and other uses that need unique variables).
 * This is a convenience wrapper around collectTypeVarsInOrder.
 */
internal fun collectTypeVariables(type: FunkType, collector: MutableSet<TypeVar>) {
    collector.addAll(collectTypeVarsInOrder(type, sortFields = false))
}

/**
 * Internal recursive helper for type variable collection.
 */
private fun collectTypeVarsRecursive(type: FunkType, collector: MutableList<TypeVar>, sortFields: Boolean) {
    when (type) {
        is FunkType.VarType -> collector.add(type.variable)
        is FunkType.FunctionType -> {
            collectTypeVarsRecursive(type.paramType, collector, sortFields)
            collectTypeVarsRecursive(type.returnType, collector, sortFields)
            // Also collect type variables from ability constraints
            type.abilityConstraints.forEach { constraint ->
                constraint.typeArgs.forEach { arg ->
                    collectTypeVarsRecursive(arg, collector, sortFields)
                }
            }
        }
        is FunkType.RecordType -> {
            val fieldEntries = if (sortFields) {
                type.fields.entries.sortedBy { it.key }
            } else {
                type.fields.entries
            }
            fieldEntries.forEach { (_, fieldType) ->
                collectTypeVarsRecursive(fieldType, collector, sortFields)
            }
        }
        is FunkType.VariantType -> {
            val constructorEntries = if (sortFields) {
                type.constructors.entries.sortedBy { it.key }
            } else {
                type.constructors.entries
            }
            constructorEntries.forEach { (_, constructorType) ->
                constructorType?.let { collectTypeVarsRecursive(it, collector, sortFields) }
            }
        }
        is FunkType.TypeApplication -> {
            type.args.forEach { arg ->
                collectTypeVarsRecursive(arg, collector, sortFields)
            }
        }
        is FunkType.ListType -> collectTypeVarsRecursive(type.elementType, collector, sortFields)
        is FunkType.MapType -> {
            collectTypeVarsRecursive(type.keyType, collector, sortFields)
            collectTypeVarsRecursive(type.valueType, collector, sortFields)
        }
        is FunkType.SetType -> collectTypeVarsRecursive(type.elementType, collector, sortFields)
        is FunkType.ArrayType -> collectTypeVarsRecursive(type.elementType, collector, sortFields)
        is FunkType.SuspendedType -> collectTypeVarsRecursive(type.valueType, collector, sortFields)
        // Base types don't contain type variables
        is FunkType.IntType, is FunkType.FloatType, is FunkType.StringType,
        is FunkType.BooleanType, is FunkType.NatType, is FunkType.FracType,
        is FunkType.OrderingType, is FunkType.BytesType, is FunkType.ByteBufferType, is FunkType.EffectRow -> { }
    }
}

// ============================================================================
// TYPE NORMALIZATION
// ============================================================================

/**
 * Normalize type variables with constraint awareness to preserve unification relationships
 */
fun normalizeTypeVariablesWithConstraints(type: FunkType, constraints: List<Pair<FunkType, FunkType>>): FunkType {
    val typeVarsInOrder = collectTypeVarsInOrder(type)

    // Create equivalence classes based on constraints
    val equivalenceClasses = mutableListOf<MutableSet<TypeVar>>()
    val processed = mutableSetOf<TypeVar>()

    for (typeVar in typeVarsInOrder) {
        if (typeVar in processed) continue

        val equivalenceClass = mutableSetOf<TypeVar>()
        equivalenceClass.add(typeVar)
        processed.add(typeVar)

        // Find all type variables that should be unified with this one
        for ((type1, type2) in constraints) {
            val vars1 = collectTypeVarsInOrder(type1, sortFields = false).toSet()
            val vars2 = collectTypeVarsInOrder(type2, sortFields = false).toSet()

            if (typeVar in vars1) {
                vars2.forEach { otherVar ->
                    if (otherVar !in processed) {
                        equivalenceClass.add(otherVar)
                        processed.add(otherVar)
                    }
                }
            } else if (typeVar in vars2) {
                vars1.forEach { otherVar ->
                    if (otherVar !in processed) {
                        equivalenceClass.add(otherVar)
                        processed.add(otherVar)
                    }
                }
            }
        }

        equivalenceClasses.add(equivalenceClass)
    }

    // Create mapping from old names to new normalized names
    val mapping = mutableMapOf<TypeVar, TypeVar>()
    equivalenceClasses.forEachIndexed { index, equivalenceClass ->
        val normalizedName = "'${('a' + index)}"
        for (typeVar in equivalenceClass) {
            mapping[typeVar] = TypeVar(normalizedName, typeVar.level)
        }
    }

    return substituteTypeVariables(type, mapping)
}

/**
 * Normalize type variables in a type to start from 'a, 'b, 'c, etc.
 * This provides clean, readable output by renaming type variables in order of appearance.
 */
fun normalizeTypeVariables(type: FunkType): FunkType {
    val typeVarsInOrder = collectTypeVarsInOrder(type)

    // CRITICAL FIX: Use identity-based distinct to preserve independence of type variables
    // TypeVar is a data class, so .distinct() uses structural equality (name + level)
    // This would incorrectly merge independent type variables with the same name!
    // Instead, we track by object identity using a map
    val seenIdentities = mutableMapOf<Int, TypeVar>()  // identity -> first occurrence
    val uniqueTypeVars = mutableListOf<TypeVar>()

    for (typeVar in typeVarsInOrder) {
        val identity = System.identityHashCode(typeVar)
        if (!seenIdentities.containsKey(identity)) {
            seenIdentities[identity] = typeVar
            uniqueTypeVars.add(typeVar)
        }
    }

    // Create mapping from old type variables to new normalized names
    // Use identity-based lookup since we need to distinguish type variables by identity
    val identityToNewVar = mutableMapOf<Int, TypeVar>()

    uniqueTypeVars.forEachIndexed { index, typeVar ->
        val normalizedName = "'${('a' + index)}"
        val newTypeVar = TypeVar(normalizedName, typeVar.level)
        identityToNewVar[System.identityHashCode(typeVar)] = newTypeVar
    }

    // Create a wrapper that looks up by identity
    val mapping = IdentityBasedTypeVarMap(identityToNewVar)

    return substituteTypeVariables(type, mapping)
}


/**
 * Substitute type variables in a type according to the mapping
 */
fun substituteTypeVariables(type: FunkType, mapping: Map<TypeVar, TypeVar>): FunkType {
    return when (type) {
        is FunkType.VarType -> {
            val newVar = mapping[type.variable] ?: type.variable
            FunkType.VarType(newVar)
        }
        is FunkType.FunctionType -> {
            val newParamType = substituteTypeVariables(type.paramType, mapping)
            val newReturnType = substituteTypeVariables(type.returnType, mapping)
            // CRITICAL FIX: Also substitute type variables in ability constraints
            // This ensures constraint type variables are normalized along with param/return types
            val newAbilityConstraints = type.abilityConstraints.map { constraint ->
                FunkType.FunctionAbilityConstraint(
                    constraint.abilityName,
                    constraint.typeArgs.map { arg -> substituteTypeVariables(arg, mapping) }
                )
            }
            FunkType.FunctionType(newParamType, newReturnType, type.effects, newAbilityConstraints)
        }
        is FunkType.RecordType -> {
            val newFields = type.fields.mapValues { (_, fieldType) ->
                substituteTypeVariables(fieldType, mapping)
            }
            FunkType.RecordType(newFields, type.rowVar) // RowVar substitution if needed
        }
        is FunkType.VariantType -> {
            val newConstructors = type.constructors.mapValues { (_, caseType) ->
                caseType?.let { substituteTypeVariables(it, mapping) }
            }
            // CRITICAL FIX: Preserve row variable during type variable normalization
            FunkType.VariantType(newConstructors, type.rowVar)
        }
        is FunkType.TypeApplication -> {
            val newArgs = type.args.map { substituteTypeVariables(it, mapping) }
            FunkType.TypeApplication(type.constructor, newArgs)
        }
        is FunkType.ListType -> {
            val newElementType = substituteTypeVariables(type.elementType, mapping)
            FunkType.ListType(newElementType)
        }
        is FunkType.MapType -> {
            val newKeyType = substituteTypeVariables(type.keyType, mapping)
            val newValueType = substituteTypeVariables(type.valueType, mapping)
            FunkType.MapType(newKeyType, newValueType)
        }
        is FunkType.SetType -> {
            val newElementType = substituteTypeVariables(type.elementType, mapping)
            FunkType.SetType(newElementType)
        }
        is FunkType.ArrayType -> {
            val newElementType = substituteTypeVariables(type.elementType, mapping)
            FunkType.ArrayType(newElementType)
        }
        is FunkType.SuspendedType -> {
            val newValueType = substituteTypeVariables(type.valueType, mapping)
            FunkType.SuspendedType(newValueType, type.effects)
        }
        // Base types and effect rows remain unchanged
        is FunkType.IntType, is FunkType.FloatType, is FunkType.StringType,
        is FunkType.BooleanType, is FunkType.NatType, is FunkType.FracType,
        is FunkType.OrderingType, is FunkType.BytesType, is FunkType.ByteBufferType, is FunkType.EffectRow -> type
    }
}

/**
 * Merge two lists of ability constraints, removing duplicates
 */
fun mergeConstraints(constraints1: List<AbilityConstraint>, constraints2: List<AbilityConstraint>): List<AbilityConstraint> {
    val merged = (constraints1 + constraints2).distinctBy { "${it.abilityName}:${it.typeParam}" }
    return merged
}
