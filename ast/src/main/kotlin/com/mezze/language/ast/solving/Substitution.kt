package com.mezze.language.ast.solving

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.RowVar
import com.mezze.language.ast.TypeVar

data class Substitution(
    val mappings: Map<TypeVar, MezzeType> = emptyMap()
) {
    fun apply(type: MezzeType): MezzeType {
        if (mappings.isEmpty()) {
            return type
        }
        if (isConcreteType(type)) {
            return type
        }

        return applyWithVisited(type, emptySet())
    }

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

            is MezzeType.EffectRow -> needsSubstitutionInEffectRow(type)

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

    private fun needsSubstitutionInEffectRow(effectRow: MezzeType.EffectRow): Boolean {
        return effectRow.effectInstances.any { instance ->
            instance.associatedTypes.values.any { needsSubstitution(it) }
        }
    }

    // Tracks visited type variables to prevent infinite recursion from cyclic substitutions.
    private fun applyWithVisited(type: MezzeType, visitedVars: Set<TypeVar>): MezzeType {
        if (visitedVars.isEmpty() && !needsSubstitution(type)) {
            return type
        }

        return when (type) {
            is MezzeType.VarType -> {
                if (type.variable in visitedVars) return type
                val substitutedType = mappings[type.variable]
                if (substitutedType != null) {
                    applyWithVisited(substitutedType, visitedVars + type.variable)
                } else {
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
                rowVar = type.rowVar,
                typeName = type.typeName,
                typeHash = type.typeHash
            )

            is MezzeType.VariantType -> {
                val appliedConstructors = type.constructors.mapValues { (_, dataType) ->
                    dataType?.let { applyWithVisited(it, visitedVars) }
                }
                MezzeType.VariantType(
                    constructors = appliedConstructors,
                    rowVar = type.rowVar,
                    typeName = type.typeName,
                    typeArgs = type.typeArgs.map { applyWithVisited(it, visitedVars) },
                    typeHash = type.typeHash,
                    nominalWall = type.nominalWall
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

    private fun applyToEffectRowWithVisited(effectRow: MezzeType.EffectRow, visitedVars: Set<TypeVar>): MezzeType.EffectRow {
        if (effectRow.effectInstances.isEmpty()) {
            return effectRow
        }

        var anyChanged = false
        val substitutedInstances = effectRow.effectInstances.map { instance ->
            if (instance.associatedTypes.isEmpty()) {
                instance
            } else {
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

        return if (anyChanged) effectRow.copy(effectInstances = substitutedInstances.toSet()) else effectRow
    }

    // s1's mappings take precedence — we DON'T apply this to other's values because
    // that would bake in intermediate results (e.g. partial variant fields).
    fun compose(other: Substitution): Substitution {
        return Substitution(other.mappings + mappings)
    }

    val isEmpty: Boolean
        get() = mappings.isEmpty()

    companion object {
        val empty = Substitution()

        fun single(typeVar: TypeVar, type: MezzeType) = Substitution(mapOf(typeVar to type))
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

// Auto-generated row vars (RowVar.fresh()) are closed to null; user-specified (RowVar.userOpen())
// and mustBeOpenRowVars remain open.
fun closeGeneratedRowVars(type: MezzeType, mustBeOpenRowVars: Set<RowVar> = emptySet()): MezzeType = when (type) {
    is MezzeType.RecordType -> {
        val existingRowVar = type.rowVar
        val closedRowVar = if (existingRowVar != null
            && !existingRowVar.isUserSpecified()
            && existingRowVar !in mustBeOpenRowVars) {
            null
        } else {
            existingRowVar
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
            null
        } else {
            existingRowVar
        }
        MezzeType.VariantType(
            constructors = type.constructors.mapValues { (_, payloadType) ->
                payloadType?.let { closeGeneratedRowVars(it, mustBeOpenRowVars) }
            },
            rowVar = closedRowVar,
            typeName = type.typeName,
            typeArgs = type.typeArgs.map { closeGeneratedRowVars(it, mustBeOpenRowVars) },
            typeHash = type.typeHash,
            nominalWall = type.nominalWall
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
    else -> type
}

fun occursCheck(typeVar: TypeVar, type: MezzeType): Boolean {
    return when (type) {
        is MezzeType.VarType -> typeVar == type.variable

        is MezzeType.FunctionType ->
            occursCheck(typeVar, type.paramType) ||
            occursCheck(typeVar, type.returnType) ||
            type.abilityConstraints.any { constraint ->
                constraint.typeArgs.any { occursCheck(typeVar, it) } ||
                constraint.associatedTypeBindings.values.any { occursCheck(typeVar, it) }
            } ||
            (type.effects != null && occursCheckEffectRow(typeVar, type.effects!!))

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

        is MezzeType.SuspendedType ->
            occursCheck(typeVar, type.valueType)

        is MezzeType.NewtypeType -> occursCheck(typeVar, type.wrappedType)

        is MezzeType.EffectRow -> occursCheckEffectRow(typeVar, type)

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

private fun occursCheckEffectRow(typeVar: TypeVar, effectRow: MezzeType.EffectRow): Boolean {
    return effectRow.effectInstances.any { instance ->
        instance.associatedTypes.values.any { occursCheck(typeVar, it) }
    }
}

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
