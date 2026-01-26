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
// TYPE NORMALIZATION
// ============================================================================

/**
 * Normalize type variables with constraint awareness to preserve unification relationships
 */
fun normalizeTypeVariablesWithConstraints(type: FunkType, constraints: List<Pair<FunkType, FunkType>>): FunkType {
    val typeVarsInOrder = mutableListOf<TypeVar>()
    collectTypeVariablesInOrder(type, typeVarsInOrder)

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
            val vars1 = extractTypeVariables(type1)
            val vars2 = extractTypeVariables(type2)

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
 * Extract type variables from a type
 */
private fun extractTypeVariables(type: FunkType): Set<TypeVar> {
    val result = mutableSetOf<TypeVar>()
    when (type) {
        is FunkType.VarType -> result.add(type.variable)
        is FunkType.FunctionType -> {
            result.addAll(extractTypeVariables(type.paramType))
            result.addAll(extractTypeVariables(type.returnType))
        }
        is FunkType.RecordType -> {
            type.fields.values.forEach { fieldType ->
                fieldType?.let { result.addAll(extractTypeVariables(it)) }
            }
        }
        is FunkType.VariantType -> {
            type.constructors.values.forEach { constructorType ->
                constructorType?.let { result.addAll(extractTypeVariables(it)) }
            }
        }
        is FunkType.TypeApplication -> {
            type.args.forEach { arg ->
                result.addAll(extractTypeVariables(arg))
            }
        }
        is FunkType.ListType -> {
            result.addAll(extractTypeVariables(type.elementType))
        }
        is FunkType.MapType -> {
            result.addAll(extractTypeVariables(type.keyType))
            result.addAll(extractTypeVariables(type.valueType))
        }
        is FunkType.SetType -> {
            result.addAll(extractTypeVariables(type.elementType))
        }
        is FunkType.ArrayType -> {
            result.addAll(extractTypeVariables(type.elementType))
        }
        else -> { /* Base types don't contain type variables */ }
    }
    return result
}

/**
 * Normalize type variables in a type to start from 'a, 'b, 'c, etc.
 * This provides clean, readable output by renaming type variables in order of appearance.
 */
fun normalizeTypeVariables(type: FunkType): FunkType {
    val typeVarsInOrder = mutableListOf<TypeVar>()
    collectTypeVariablesInOrder(type, typeVarsInOrder)

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
    // Use a custom map that compares by identity, not structural equality
    val mapping = object : MutableMap<TypeVar, TypeVar> by mutableMapOf() {
        private val identityMap = mutableMapOf<Int, TypeVar>()

        override fun get(key: TypeVar): TypeVar? {
            return identityMap[System.identityHashCode(key)]
        }

        override fun put(key: TypeVar, value: TypeVar): TypeVar? {
            val identity = System.identityHashCode(key)
            return identityMap.put(identity, value)
        }

        override fun containsKey(key: TypeVar): Boolean {
            return identityMap.containsKey(System.identityHashCode(key))
        }
    }

    uniqueTypeVars.forEachIndexed { index, typeVar ->
        val normalizedName = "'${('a' + index)}"
        val newTypeVar = TypeVar(normalizedName, typeVar.level)
        mapping[typeVar] = newTypeVar
    }

    return substituteTypeVariables(type, mapping)
}

/**
 * Collect all type variables in a type in order of appearance (left-to-right, depth-first)
 */
private fun collectTypeVariablesInOrder(type: FunkType, collector: MutableList<TypeVar>) {
    when (type) {
        is FunkType.VarType -> collector.add(type.variable)
        is FunkType.FunctionType -> {
            // Collect parameters first (left side), then return type (right side)
            collectTypeVariablesInOrder(type.paramType, collector)
            collectTypeVariablesInOrder(type.returnType, collector)
            // CRITICAL FIX: Also collect type variables from ability constraints
            // This ensures constraint type variables get the same normalized names as param/return types
            type.abilityConstraints.forEach { constraint ->
                constraint.typeArgs.forEach { arg ->
                    collectTypeVariablesInOrder(arg, collector)
                }
            }
            type.effects?.let { /* Effect collection if needed */ }
        }
        is FunkType.RecordType -> {
            // Collect field types in alphabetical order for consistency
            type.fields.keys.sorted().forEach { fieldName ->
                type.fields[fieldName]?.let { collectTypeVariablesInOrder(it, collector) }
            }
            type.rowVar?.let { /* RowVar collection if needed */ }
        }
        is FunkType.VariantType -> {
            // Collect constructor types in alphabetical order for consistency
            type.constructors.keys.sorted().forEach { constructorName ->
                type.constructors[constructorName]?.let { collectTypeVariablesInOrder(it, collector) }
            }
        }
        is FunkType.TypeApplication -> {
            type.args.forEach { arg ->
                collectTypeVariablesInOrder(arg, collector)
            }
        }
        is FunkType.ListType -> {
            collectTypeVariablesInOrder(type.elementType, collector)
        }
        is FunkType.MapType -> {
            collectTypeVariablesInOrder(type.keyType, collector)
            collectTypeVariablesInOrder(type.valueType, collector)
        }
        is FunkType.SetType -> {
            collectTypeVariablesInOrder(type.elementType, collector)
        }
        is FunkType.ArrayType -> {
            collectTypeVariablesInOrder(type.elementType, collector)
        }
        is FunkType.SuspendedType -> {
            collectTypeVariablesInOrder(type.valueType, collector)
        }
        // Base types don't contain type variables
        is FunkType.IntType, is FunkType.FloatType, is FunkType.StringType,
        is FunkType.BooleanType, is FunkType.NatType, is FunkType.FracType,
        is FunkType.OrderingType -> {
            // No type variables to collect
        }
        is FunkType.EffectRow -> {
            // Effect rows might contain type variables in the future
        }
    }
}

/**
 * Collect all type variables in a type (old version for compatibility)
 */
internal fun collectTypeVariables(type: FunkType, collector: MutableSet<TypeVar>) {
    when (type) {
        is FunkType.VarType -> collector.add(type.variable)
        is FunkType.FunctionType -> {
            collectTypeVariables(type.paramType, collector)
            collectTypeVariables(type.returnType, collector)
            type.effects?.let { /* Effect collection if needed */ }
        }
        is FunkType.RecordType -> {
            type.fields.values.forEach { collectTypeVariables(it, collector) }
            type.rowVar?.let { /* RowVar collection if needed */ }
        }
        is FunkType.VariantType -> {
            type.constructors.values.forEach { caseType ->
                caseType?.let { collectTypeVariables(it, collector) }
            }
        }
        is FunkType.TypeApplication -> {
            type.args.forEach { arg ->
                collectTypeVariables(arg, collector)
            }
        }
        is FunkType.ListType -> {
            collectTypeVariables(type.elementType, collector)
        }
        is FunkType.MapType -> {
            collectTypeVariables(type.keyType, collector)
            collectTypeVariables(type.valueType, collector)
        }
        is FunkType.SetType -> {
            collectTypeVariables(type.elementType, collector)
        }
        is FunkType.ArrayType -> {
            collectTypeVariables(type.elementType, collector)
        }
        is FunkType.SuspendedType -> {
            collectTypeVariables(type.valueType, collector)
        }
        // Base types don't contain type variables
        is FunkType.IntType, is FunkType.FloatType, is FunkType.StringType,
        is FunkType.BooleanType, is FunkType.NatType, is FunkType.FracType,
        is FunkType.OrderingType -> {
            // No type variables to collect
        }
        is FunkType.EffectRow -> {
            // Effect rows might contain type variables in the future
        }
    }
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
        // Base types and effect rows remain unchanged
        else -> type
    }
}

/**
 * Merge two lists of ability constraints, removing duplicates
 */
fun mergeConstraints(constraints1: List<AbilityConstraint>, constraints2: List<AbilityConstraint>): List<AbilityConstraint> {
    val merged = (constraints1 + constraints2).distinctBy { "${it.abilityName}:${it.typeParam}" }
    return merged
}
