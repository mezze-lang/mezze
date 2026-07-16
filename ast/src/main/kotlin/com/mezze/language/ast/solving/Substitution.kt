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

        return applyWithVisited(type, mutableSetOf(), HashMap())
    }

    // Resolve a variable chain (var -> var -> ... -> terminal) with path
    // compression, union-find style. `chainCache` memoizes the terminal a
    // variable resolves to *within a single apply() traversal*, so a type that
    // mentions the same var many times (e.g. (a, a, a) -> a) walks each chain
    // once instead of once per occurrence. Only pure variable hops are cached;
    // once the chain reaches a non-var (or an unmapped/cyclic var) we hand off
    // to applyWithVisited so compound terminals still recurse under the cycle
    // guard. Returns the resolved MezzeType for `startVar`, or null if it maps
    // to a non-variable that must be recursed by the caller.
    private fun resolveVarChain(
        startVar: TypeVar,
        visitedVars: MutableSet<TypeVar>,
        chainCache: MutableMap<TypeVar, MezzeType>
    ): MezzeType? {
        chainCache[startVar]?.let { return it }

        var current = startVar
        val hops = ArrayList<TypeVar>()
        val hopSet = HashSet<TypeVar>()
        while (true) {
            if (current in visitedVars || current in hopSet) {
                // Cyclic — either against the outer traversal's visitedVars, or
                // a var-only cycle within this chain (a -> b -> a). Leave as the
                // raw var. Do NOT cache: visitedVars membership is transient
                // (added/removed around each recursion), so a hop's cyclic
                // resolution here could be stale once the var leaves visitedVars.
                return MezzeType.VarType(current)
            }
            val mapped = mappings[current]
            if (mapped == null) {
                // Unmapped terminal variable — resolves to itself, independent
                // of visitedVars, so it (and every hop that led here) is safe to
                // cache with path compression for the rest of this traversal.
                val result = MezzeType.VarType(current)
                chainCache[current] = result
                for (v in hops) chainCache[v] = result
                return result
            }
            if (mapped is MezzeType.VarType) {
                hops.add(current)
                hopSet.add(current)
                current = mapped.variable
                continue
            }
            // Non-variable terminal: caller must recurse it under the cycle
            // guard, so we can't cache a finished value here.
            return null
        }
    }

    private fun isConcreteType(type: MezzeType): Boolean = when (type) {
        is MezzeType.IntType, is MezzeType.FloatType, is MezzeType.StringType,
        is MezzeType.BoolType, is MezzeType.NatType, is MezzeType.FracType,
        is MezzeType.OrderingType, is MezzeType.BytesType, is MezzeType.ByteBufferType,
        is MezzeType.CharType -> true
        is MezzeType.VarType, is MezzeType.FunctionType, is MezzeType.RecordType,
        is MezzeType.VariantType, is MezzeType.TypeApplication, is MezzeType.ListType,
        is MezzeType.MapType, is MezzeType.SetType, is MezzeType.SortedMapType,
        is MezzeType.SortedSetType, is MezzeType.SeqType, is MezzeType.SuspendedType,
        is MezzeType.NewtypeType, is MezzeType.EffectRow -> false
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

    private fun applyWithVisited(
        type: MezzeType,
        visitedVars: MutableSet<TypeVar>,
        chainCache: MutableMap<TypeVar, MezzeType>
    ): MezzeType {
        if (visitedVars.isEmpty() && !needsSubstitution(type)) {
            return type
        }

        return when (type) {
            is MezzeType.VarType -> {
                if (type.variable in visitedVars) return type
                // Fast path: chase variable-only chains with path compression.
                // Returns non-null when the chain terminates at a variable
                // (mapped-to-nothing or cyclic); null means the chain ends at a
                // compound type we must recurse into under the cycle guard.
                resolveVarChain(type.variable, visitedVars, chainCache)?.let { return it }
                val substitutedType = mappings[type.variable]
                if (substitutedType != null) {
                    visitedVars.add(type.variable)
                    val result = applyWithVisited(substitutedType, visitedVars, chainCache)
                    visitedVars.remove(type.variable)
                    result
                } else {
                    type
                }
            }

            is MezzeType.FunctionType -> MezzeType.FunctionType(
                paramType = applyWithVisited(type.paramType, visitedVars, chainCache),
                returnType = applyWithVisited(type.returnType, visitedVars, chainCache),
                effects = type.effects?.let { applyToEffectRowWithVisited(it, visitedVars, chainCache) },
                abilityConstraints = type.abilityConstraints.map { constraint ->
                    MezzeType.FunctionAbilityConstraint(
                        abilityName = constraint.abilityName,
                        typeArgs = constraint.typeArgs.map { applyWithVisited(it, visitedVars, chainCache) },
                        abilityHash = constraint.abilityHash
                    )
                }
            )

            is MezzeType.RecordType -> MezzeType.RecordType(
                fields = type.fields.mapValues { (_, fieldType) -> applyWithVisited(fieldType, visitedVars, chainCache) },
                rowVar = type.rowVar,
                typeName = type.typeName,
                typeHash = type.typeHash
            )

            is MezzeType.VariantType -> {
                val appliedConstructors = type.constructors.mapValues { (_, dataType) ->
                    dataType?.let { applyWithVisited(it, visitedVars, chainCache) }
                }
                MezzeType.VariantType(
                    constructors = appliedConstructors,
                    rowVar = type.rowVar,
                    typeName = type.typeName,
                    typeArgs = type.typeArgs.map { applyWithVisited(it, visitedVars, chainCache) },
                    typeHash = type.typeHash,
                    nominalWall = type.nominalWall
                )
            }

            is MezzeType.TypeApplication -> MezzeType.TypeApplication(
                constructor = type.constructor,
                args = type.args.map { applyWithVisited(it, visitedVars, chainCache) },
                typeHash = type.typeHash
            )

            is MezzeType.SeqType -> MezzeType.SeqType(
                elementType = applyWithVisited(type.elementType, visitedVars, chainCache)
            )

            is MezzeType.ListType -> MezzeType.ListType(
                elementType = applyWithVisited(type.elementType, visitedVars, chainCache)
            )

            is MezzeType.SetType -> MezzeType.SetType(
                elementType = applyWithVisited(type.elementType, visitedVars, chainCache)
            )

            is MezzeType.SortedMapType -> MezzeType.SortedMapType(
                keyType = applyWithVisited(type.keyType, visitedVars, chainCache),
                valueType = applyWithVisited(type.valueType, visitedVars, chainCache)
            )

            is MezzeType.SortedSetType -> MezzeType.SortedSetType(
                elementType = applyWithVisited(type.elementType, visitedVars, chainCache)
            )

            is MezzeType.MapType -> MezzeType.MapType(
                keyType = applyWithVisited(type.keyType, visitedVars, chainCache),
                valueType = applyWithVisited(type.valueType, visitedVars, chainCache)
            )

            is MezzeType.SuspendedType -> MezzeType.SuspendedType(
                valueType = applyWithVisited(type.valueType, visitedVars, chainCache),
                effects = applyToEffectRowWithVisited(type.effects, visitedVars, chainCache)
            )

            is MezzeType.NewtypeType -> MezzeType.NewtypeType(
                type.name,
                type.constructor,
                applyWithVisited(type.wrappedType, visitedVars, chainCache)
            )

            is MezzeType.EffectRow -> applyToEffectRowWithVisited(type, visitedVars, chainCache)

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

    private fun applyToEffectRowWithVisited(
        effectRow: MezzeType.EffectRow,
        visitedVars: MutableSet<TypeVar>,
        chainCache: MutableMap<TypeVar, MezzeType>
    ): MezzeType.EffectRow {
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
                        applyWithVisited(type, visitedVars, chainCache)
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
    is MezzeType.VarType, is MezzeType.IntType, is MezzeType.FloatType,
    is MezzeType.StringType, is MezzeType.BoolType, is MezzeType.NatType,
    is MezzeType.FracType, is MezzeType.OrderingType, is MezzeType.BytesType,
    is MezzeType.ByteBufferType, is MezzeType.CharType, is MezzeType.EffectRow -> type
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
