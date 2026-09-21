package com.mezze.language.ast

// TypeVar is a data class (structural equality) but normalization needs identity-based lookup.
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
 * Is this name a type *variable* (as opposed to a concrete/nominal type)?
 * Mezze's convention: a name that starts with a `'` (e.g. `'a`) or with a
 * lowercase letter (e.g. `a`) is a type variable; anything else (uppercase,
 * `Int`, `Option`) is a concrete type. Empty-safe.
 */
fun String.isTypeVarName(): Boolean =
    startsWith("'") || (isNotEmpty() && this[0].isLowerCase())

fun collectTypeVarsInOrder(type: MezzeType, sortFields: Boolean = true): List<TypeVar> {
    val result = mutableListOf<TypeVar>()
    collectTypeVarsRecursive(type, result, sortFields)
    return result
}

fun collectTypeVariables(type: MezzeType, collector: MutableSet<TypeVar>) {
    collector.addAll(collectTypeVarsInOrder(type, sortFields = false))
}

private fun collectTypeVarsRecursive(type: MezzeType, collector: MutableList<TypeVar>, sortFields: Boolean) {
    when (type) {
        is MezzeType.VarType -> collector.add(type.variable)
        is MezzeType.FunctionType -> {
            collectTypeVarsRecursive(type.paramType, collector, sortFields)
            collectTypeVarsRecursive(type.returnType, collector, sortFields)
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
        is MezzeType.NewtypeType -> collectTypeVarsRecursive(type.wrappedType, collector, sortFields)
        is MezzeType.IntType, is MezzeType.FloatType, is MezzeType.StringType,
        is MezzeType.BoolType, is MezzeType.FracType,
        is MezzeType.BytesType,
        is MezzeType.CharType, is MezzeType.EffectRow -> { }
    }
}

fun normalizeTypeVariables(type: MezzeType): MezzeType {
    val typeVarsInOrder = collectTypeVarsInOrder(type)

    // identity-based distinct: .distinct() would merge independent TypeVars with the same name
    val seenIdentities = mutableMapOf<Int, TypeVar>()
    val uniqueTypeVars = mutableListOf<TypeVar>()

    for (typeVar in typeVarsInOrder) {
        val identity = System.identityHashCode(typeVar)
        if (!seenIdentities.containsKey(identity)) {
            seenIdentities[identity] = typeVar
            uniqueTypeVars.add(typeVar)
        }
    }

    val identityToNewVar = mutableMapOf<Int, TypeVar>()

    uniqueTypeVars.forEachIndexed { index, typeVar ->
        val normalizedName = "'${('a' + index)}"
        val newTypeVar = TypeVar.freshWithName(normalizedName, typeVar.level)
        identityToNewVar[System.identityHashCode(typeVar)] = newTypeVar
    }

    val mapping = IdentityBasedTypeVarMap(identityToNewVar)

    return substituteTypeVariables(type, mapping)
}


fun substituteTypeVariables(type: MezzeType, mapping: Map<TypeVar, TypeVar>): MezzeType {
    if (type is MezzeType.VarType) {
        val newVar = mapping[type.variable] ?: type.variable
        return MezzeType.VarType(newVar)
    }
    return type.transformChildren { substituteTypeVariables(it, mapping) }
}

