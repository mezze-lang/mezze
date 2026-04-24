package com.mezze.language.ast

/**
 * Type utilities for normalization, substitution, and type variable collection.
 */

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
fun collectTypeVarsInOrder(type: MezzeType, sortFields: Boolean = true): List<TypeVar> {
    val result = mutableListOf<TypeVar>()
    collectTypeVarsRecursive(type, result, sortFields)
    return result
}

/**
 * Collect type variables into a mutable set (for generalization and other uses that need unique variables).
 * This is a convenience wrapper around collectTypeVarsInOrder.
 */
internal fun collectTypeVariables(type: MezzeType, collector: MutableSet<TypeVar>) {
    collector.addAll(collectTypeVarsInOrder(type, sortFields = false))
}

/**
 * Internal recursive helper for type variable collection.
 */
private fun collectTypeVarsRecursive(type: MezzeType, collector: MutableList<TypeVar>, sortFields: Boolean) {
    when (type) {
        is MezzeType.VarType -> collector.add(type.variable)
        is MezzeType.FunctionType -> {
            collectTypeVarsRecursive(type.paramType, collector, sortFields)
            collectTypeVarsRecursive(type.returnType, collector, sortFields)
            // Also collect type variables from ability constraints
            type.abilityConstraints.forEach { constraint ->
                constraint.typeArgs.forEach { arg ->
                    collectTypeVarsRecursive(arg, collector, sortFields)
                }
            }
        }
        is MezzeType.RecordType -> {
            val fieldEntries = if (sortFields) {
                type.fields.entries.sortedBy { it.key }
            } else {
                type.fields.entries
            }
            fieldEntries.forEach { (_, fieldType) ->
                collectTypeVarsRecursive(fieldType, collector, sortFields)
            }
        }
        is MezzeType.VariantType -> {
            val constructorEntries = if (sortFields) {
                type.constructors.entries.sortedBy { it.key }
            } else {
                type.constructors.entries
            }
            constructorEntries.forEach { (_, constructorType) ->
                constructorType?.let { collectTypeVarsRecursive(it, collector, sortFields) }
            }
        }
        is MezzeType.TypeApplication -> {
            type.args.forEach { arg ->
                collectTypeVarsRecursive(arg, collector, sortFields)
            }
        }
        is MezzeType.ListType -> collectTypeVarsRecursive(type.elementType, collector, sortFields)
        is MezzeType.MapType -> {
            collectTypeVarsRecursive(type.keyType, collector, sortFields)
            collectTypeVarsRecursive(type.valueType, collector, sortFields)
        }
        is MezzeType.SetType -> collectTypeVarsRecursive(type.elementType, collector, sortFields)
        is MezzeType.SortedMapType -> {
            collectTypeVarsRecursive(type.keyType, collector, sortFields)
            collectTypeVarsRecursive(type.valueType, collector, sortFields)
        }
        is MezzeType.SortedSetType -> collectTypeVarsRecursive(type.elementType, collector, sortFields)
        is MezzeType.SeqType -> collectTypeVarsRecursive(type.elementType, collector, sortFields)
        is MezzeType.SuspendedType -> collectTypeVarsRecursive(type.valueType, collector, sortFields)
        is MezzeType.NewtypeType -> collectTypeVarsRecursive(type.wrappedType, collector, sortFields)
        // Base types don't contain type variables
        is MezzeType.IntType, is MezzeType.FloatType, is MezzeType.StringType,
        is MezzeType.BoolType, is MezzeType.NatType, is MezzeType.FracType,
        is MezzeType.OrderingType, is MezzeType.BytesType, is MezzeType.ByteBufferType,
        is MezzeType.CharType, is MezzeType.EffectRow -> { }
    }
}

/**
 * Normalize type variables in a type to start from 'a, 'b, 'c, etc.
 * This provides clean, readable output by renaming type variables in order of appearance.
 */
fun normalizeTypeVariables(type: MezzeType): MezzeType {
    val typeVarsInOrder = collectTypeVarsInOrder(type)

    // Use identity-based distinct because TypeVar is a data class -- .distinct()
    // would merge independent type variables with the same name. Track by identity.
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
fun substituteTypeVariables(type: MezzeType, mapping: Map<TypeVar, TypeVar>): MezzeType {
    return when (type) {
        is MezzeType.VarType -> {
            val newVar = mapping[type.variable] ?: type.variable
            MezzeType.VarType(newVar)
        }
        is MezzeType.FunctionType -> {
            val newParamType = substituteTypeVariables(type.paramType, mapping)
            val newReturnType = substituteTypeVariables(type.returnType, mapping)
            // Also substitute type variables in ability constraints
            val newAbilityConstraints = type.abilityConstraints.map { constraint ->
                MezzeType.FunctionAbilityConstraint(
                    constraint.abilityName,
                    constraint.typeArgs.map { arg -> substituteTypeVariables(arg, mapping) },
                    abilityHash = constraint.abilityHash
                )
            }
            MezzeType.FunctionType(newParamType, newReturnType, type.effects, newAbilityConstraints)
        }
        is MezzeType.RecordType -> {
            val newFields = type.fields.mapValues { (_, fieldType) ->
                substituteTypeVariables(fieldType, mapping)
            }
            MezzeType.RecordType(newFields, type.rowVar) // RowVar substitution if needed
        }
        is MezzeType.VariantType -> {
            val newConstructors = type.constructors.mapValues { (_, caseType) ->
                caseType?.let { substituteTypeVariables(it, mapping) }
            }
            // Preserve row variable during type variable normalization
            MezzeType.VariantType(newConstructors, type.rowVar)
        }
        is MezzeType.TypeApplication -> {
            val newArgs = type.args.map { substituteTypeVariables(it, mapping) }
            MezzeType.TypeApplication(type.constructor, newArgs, type.typeHash)
        }
        is MezzeType.ListType -> {
            val newElementType = substituteTypeVariables(type.elementType, mapping)
            MezzeType.ListType(newElementType)
        }
        is MezzeType.MapType -> {
            val newKeyType = substituteTypeVariables(type.keyType, mapping)
            val newValueType = substituteTypeVariables(type.valueType, mapping)
            MezzeType.MapType(newKeyType, newValueType)
        }
        is MezzeType.SetType -> {
            val newElementType = substituteTypeVariables(type.elementType, mapping)
            MezzeType.SetType(newElementType)
        }
        is MezzeType.SortedMapType -> {
            val newKeyType = substituteTypeVariables(type.keyType, mapping)
            val newValueType = substituteTypeVariables(type.valueType, mapping)
            MezzeType.SortedMapType(newKeyType, newValueType)
        }
        is MezzeType.SortedSetType -> {
            val newElementType = substituteTypeVariables(type.elementType, mapping)
            MezzeType.SortedSetType(newElementType)
        }
        is MezzeType.SeqType -> {
            val newElementType = substituteTypeVariables(type.elementType, mapping)
            MezzeType.SeqType(newElementType)
        }
        is MezzeType.SuspendedType -> {
            val newValueType = substituteTypeVariables(type.valueType, mapping)
            MezzeType.SuspendedType(newValueType, type.effects)
        }
        is MezzeType.NewtypeType -> {
            val newWrappedType = substituteTypeVariables(type.wrappedType, mapping)
            MezzeType.NewtypeType(type.name, type.constructor, newWrappedType)
        }
        // Base types and effect rows remain unchanged
        is MezzeType.IntType, is MezzeType.FloatType, is MezzeType.StringType,
        is MezzeType.BoolType, is MezzeType.NatType, is MezzeType.FracType,
        is MezzeType.OrderingType, is MezzeType.BytesType, is MezzeType.ByteBufferType,
        is MezzeType.CharType, is MezzeType.EffectRow -> type
    }
}

