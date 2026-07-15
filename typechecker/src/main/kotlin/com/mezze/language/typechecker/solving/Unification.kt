package com.mezze.language.typechecker.solving

import com.mezze.language.ast.*
import com.mezze.language.ast.solving.Substitution
import com.mezze.language.ast.solving.occursCheck
import com.mezze.language.typechecker.WellKnownHashConstants
import com.mezze.language.typechecker.abilities.BuiltInAbilities
import com.mezze.language.typechecker.context.DeclarationRegistry
import com.mezze.language.typechecker.debug.DebugLogger
import com.mezze.language.typechecker.debug.DebugLogger.Category
import com.mezze.language.typechecker.util.StringDistance
import com.mezze.language.parsedast.*
import com.mezze.language.typedast.*
import com.mezze.language.typechecker.ModuleResolver
import com.mezze.language.typechecker.context.ContextBuilder

private val unificationCache = ThreadLocal.withInitial {
    HashMap<Pair<MezzeType, MezzeType>, UnificationResult>()
}

fun clearUnificationCache() {
    unificationCache.get().clear()
}

sealed class UnificationResult {
    data class Success(val substitution: Substitution) : UnificationResult()
    data class Failure(
        val message: String,
        val type1: MezzeType? = null,
        val type2: MezzeType? = null
    ) : UnificationResult()

    inline fun getOrElse(onFailure: (Failure) -> Nothing): Substitution = when (this) {
        is Success -> substitution
        is Failure -> onFailure(this)
    }
}

fun unify(type1: MezzeType, type2: MezzeType, registry: DeclarationRegistry = DeclarationRegistry.EMPTY): UnificationResult {
    if (type1 == type2) return UnificationResult.Success(Substitution.empty)

    val cacheKey = Pair(type1, type2)
    unificationCache.get()[cacheKey]?.let { return it }

    val result = unifyImpl(type1, type2, registry)
    unificationCache.get()[cacheKey] = result
    return result
}

private fun unifyImpl(type1: MezzeType, type2: MezzeType, registry: DeclarationRegistry): UnificationResult {
    if (type1 is MezzeType.BoolType && type2 is MezzeType.VariantType) {
        if (BuiltInAbilities.isBoolVariant(type2)) {
            return UnificationResult.Success(Substitution.empty)
        }
    }
    if (type1 is MezzeType.VariantType && type2 is MezzeType.BoolType) {
        if (BuiltInAbilities.isBoolVariant(type1)) {
            return UnificationResult.Success(Substitution.empty)
        }
    }
    when {
        type1 is MezzeType.VariantType && type2 is MezzeType.TypeApplication -> {
            val expanded = expandTypeApplicationToVariant(type2, registry)
            return if (expanded != null) {
                unify(type1, expanded, registry)
            } else {
                UnificationResult.Failure(
                    message = "Type mismatch: ${prettyPrintType(type1)} is not compatible with ${prettyPrintType(type2)}",
                    type1 = type1,
                    type2 = type2
                )
            }
        }
        type1 is MezzeType.TypeApplication && type2 is MezzeType.VariantType -> {
            val expanded = expandTypeApplicationToVariant(type1, registry)
            return if (expanded != null) {
                unify(expanded, type2, registry)
            } else {
                UnificationResult.Failure(
                    message = "Type mismatch: ${prettyPrintType(type1)} is not compatible with ${prettyPrintType(type2)}",
                    type1 = type1,
                    type2 = type2
                )
            }
        }
        type1 is MezzeType.RecordType && type2 is MezzeType.TypeApplication -> {
            when (val expansion = expandTypeApplication(type2, registry)) {
                is TypeExpansionResult.Record -> {
                    return unify(type1, expansion.recordType, registry)
                }
                is TypeExpansionResult.Newtype -> {
                    if (expansion.payloadType != null && expansion.variantType.rowVar == null) {
                        return unify(type1, expansion.payloadType, registry)
                    }
                }
                is TypeExpansionResult.Variant, is TypeExpansionResult.NotFound -> {
                }
            }
            return UnificationResult.Failure(
                message = "Type mismatch: ${prettyPrintType(type1)} is not compatible with ${prettyPrintType(type2)}",
                type1 = type1,
                type2 = type2
            )
        }
        type1 is MezzeType.TypeApplication && type2 is MezzeType.RecordType -> {
            when (val expansion = expandTypeApplication(type1, registry)) {
                is TypeExpansionResult.Record -> {
                    return unify(expansion.recordType, type2, registry)
                }
                is TypeExpansionResult.Newtype -> {
                    if (expansion.payloadType != null && expansion.variantType.rowVar == null) {
                        return unify(expansion.payloadType, type2, registry)
                    }
                }
                is TypeExpansionResult.Variant, is TypeExpansionResult.NotFound -> {
                }
            }
            return UnificationResult.Failure(
                message = "Type mismatch: ${prettyPrintType(type1)} is not compatible with ${prettyPrintType(type2)}",
                type1 = type1,
                type2 = type2
            )
        }
        type1 is MezzeType.RecordType && type2 is MezzeType.VariantType -> {
            if (type2.constructors.size == 1 && type2.rowVar == null &&
                !type2.nominalWall && !isVariantNameInScope(type2, registry)) {
                val (_, payloadType) = type2.constructors.entries.first()
                if (payloadType != null) {
                    return unify(type1, payloadType, registry)
                }
            }
            return UnificationResult.Failure(
                message = "Type mismatch: expected ${prettyPrintType(type2)}, but got ${prettyPrintType(type1)}",
                type1 = type1,
                type2 = type2
            )
        }
        type1 is MezzeType.VariantType && type2 is MezzeType.RecordType -> {
            if (type1.constructors.size == 1 && type1.rowVar == null &&
                !type1.nominalWall && !isVariantNameInScope(type1, registry)) {
                val (_, payloadType) = type1.constructors.entries.first()
                if (payloadType != null) {
                    return unify(payloadType, type2, registry)
                }
            }
            return UnificationResult.Failure(
                message = "Type mismatch: expected ${prettyPrintType(type2)}, but got ${prettyPrintType(type1)}",
                type1 = type1,
                type2 = type2
            )
        }
    }

    return when {
        type1 is MezzeType.VarType -> unifyVar(type1.variable, type2)
        type2 is MezzeType.VarType -> unifyVar(type2.variable, type1)

        type1 is MezzeType.SuspendedType && type2 !is MezzeType.SuspendedType -> {
            val hasConcreteEffects = type1.effects.effects.isNotEmpty() || type1.effects.effectInstances.isNotEmpty()
            if (hasConcreteEffects) {
                return UnificationResult.Failure(
                    message = "Cannot unify effectful type ${prettyPrintType(type1)} with pure type ${prettyPrintType(type2)}. " +
                            "Effects <${type1.effects.allEffectNames.joinToString(", ")}> must be handled first.",
                    type1 = type1,
                    type2 = type2
                )
            }
            unify(type1.valueType, type2, registry)
        }
        type1 !is MezzeType.SuspendedType && type2 is MezzeType.SuspendedType -> {
            val hasConcreteEffects = type2.effects.effects.isNotEmpty() || type2.effects.effectInstances.isNotEmpty()
            if (hasConcreteEffects) {
                return UnificationResult.Failure(
                    message = "Cannot unify pure type ${prettyPrintType(type1)} with effectful type ${prettyPrintType(type2)}. " +
                            "Effects <${type2.effects.allEffectNames.joinToString(", ")}> must be handled first.",
                    type1 = type1,
                    type2 = type2
                )
            }
            unify(type1, type2.valueType, registry)
        }

        type1 is MezzeType.FunctionType && type2 is MezzeType.FunctionType ->
            unifyFunctions(type1, type2, registry)

        type1 is MezzeType.RecordType && type2 is MezzeType.RecordType ->
            unifyRecords(type1, type2, registry)

        type1 is MezzeType.VariantType && type2 is MezzeType.VariantType ->
            unifyVariants(type1, type2, registry)

        type1 is MezzeType.TypeApplication && type2 is MezzeType.TypeApplication ->
            unifyTypeApplications(type1, type2, registry)

        type1 is MezzeType.SeqType && type2 is MezzeType.SeqType ->
            unify(type1.elementType, type2.elementType, registry)

        type1 is MezzeType.SeqType && type2 is MezzeType.TypeApplication && isBuiltinCollectionApp(type2, BuiltinTypes.SEQ) && type2.args.size == 1 ->
            unify(type1.elementType, type2.args[0], registry)
        type1 is MezzeType.TypeApplication && isBuiltinCollectionApp(type1, BuiltinTypes.SEQ) && type1.args.size == 1 && type2 is MezzeType.SeqType ->
            unify(type1.args[0], type2.elementType, registry)

        type1 is MezzeType.ListType && type2 is MezzeType.ListType ->
            unify(type1.elementType, type2.elementType, registry)

        type1 is MezzeType.ListType && type2 is MezzeType.TypeApplication && isBuiltinCollectionApp(type2, BuiltinTypes.LIST) && type2.args.size == 1 ->
            unify(type1.elementType, type2.args[0], registry)
        type1 is MezzeType.TypeApplication && isBuiltinCollectionApp(type1, BuiltinTypes.LIST) && type1.args.size == 1 && type2 is MezzeType.ListType ->
            unify(type1.args[0], type2.elementType, registry)

        type1 is MezzeType.SetType && type2 is MezzeType.SetType ->
            unify(type1.elementType, type2.elementType, registry)

        type1 is MezzeType.SetType && type2 is MezzeType.TypeApplication && isBuiltinCollectionApp(type2, BuiltinTypes.SET) && type2.args.size == 1 ->
            unify(type1.elementType, type2.args[0], registry)
        type1 is MezzeType.TypeApplication && isBuiltinCollectionApp(type1, BuiltinTypes.SET) && type1.args.size == 1 && type2 is MezzeType.SetType ->
            unify(type1.args[0], type2.elementType, registry)

        type1 is MezzeType.SortedMapType && type2 is MezzeType.SortedMapType ->
            unifySortedMaps(type1, type2, registry)

        type1 is MezzeType.SortedMapType && type2 is MezzeType.TypeApplication && isBuiltinCollectionApp(type2, BuiltinTypes.SORTED_MAP) && type2.args.size == 2 ->
            unifyKeyValue(type1.keyType, type1.valueType, type2.args[0], type2.args[1], registry)
        type1 is MezzeType.TypeApplication && isBuiltinCollectionApp(type1, BuiltinTypes.SORTED_MAP) && type1.args.size == 2 && type2 is MezzeType.SortedMapType ->
            unifyKeyValue(type1.args[0], type1.args[1], type2.keyType, type2.valueType, registry)

        type1 is MezzeType.SortedSetType && type2 is MezzeType.SortedSetType ->
            unify(type1.elementType, type2.elementType, registry)

        type1 is MezzeType.SortedSetType && type2 is MezzeType.TypeApplication && isBuiltinCollectionApp(type2, BuiltinTypes.SORTED_SET) && type2.args.size == 1 ->
            unify(type1.elementType, type2.args[0], registry)
        type1 is MezzeType.TypeApplication && isBuiltinCollectionApp(type1, BuiltinTypes.SORTED_SET) && type1.args.size == 1 && type2 is MezzeType.SortedSetType ->
            unify(type1.args[0], type2.elementType, registry)

        type1 is MezzeType.MapType && type2 is MezzeType.MapType ->
            unifyMaps(type1, type2, registry)

        type1 is MezzeType.MapType && type2 is MezzeType.TypeApplication && isBuiltinCollectionApp(type2, BuiltinTypes.MAP) && type2.args.size == 2 ->
            unifyKeyValue(type1.keyType, type1.valueType, type2.args[0], type2.args[1], registry)
        type1 is MezzeType.TypeApplication && isBuiltinCollectionApp(type1, BuiltinTypes.MAP) && type1.args.size == 2 && type2 is MezzeType.MapType ->
            unifyKeyValue(type1.args[0], type1.args[1], type2.keyType, type2.valueType, registry)

        type1 is MezzeType.SuspendedType && type2 is MezzeType.SuspendedType ->
            unifySuspended(type1, type2, registry)

        type1 is MezzeType.EffectRow && type2 is MezzeType.EffectRow ->
            unifyEffectRows(type1, type2)

        type1 is MezzeType.IntType && type2 is MezzeType.IntType -> UnificationResult.Success(Substitution.empty)
        type1 is MezzeType.FloatType && type2 is MezzeType.FloatType -> UnificationResult.Success(Substitution.empty)
        type1 is MezzeType.StringType && type2 is MezzeType.StringType -> UnificationResult.Success(Substitution.empty)
        type1 is MezzeType.BoolType && type2 is MezzeType.BoolType -> UnificationResult.Success(Substitution.empty)
        type1 is MezzeType.NatType && type2 is MezzeType.NatType -> UnificationResult.Success(Substitution.empty)
        type1 is MezzeType.FracType && type2 is MezzeType.FracType -> UnificationResult.Success(Substitution.empty)
        type1 is MezzeType.OrderingType && type2 is MezzeType.OrderingType -> UnificationResult.Success(Substitution.empty)

        type1 is MezzeType.NewtypeType && type2 is MezzeType.NewtypeType -> {
            if (type1.name == type2.name) {
                unify(type1.wrappedType, type2.wrappedType, registry)
            } else {
                UnificationResult.Failure(
                    message = "Type mismatch: ${type1.name} is not the same type as ${type2.name}",
                    type1 = type1,
                    type2 = type2
                )
            }
        }
        type1 is MezzeType.OrderingType && type2 is MezzeType.VariantType ->
            unifyOrderingWithVariant(type2, type1, type2)
        type1 is MezzeType.VariantType && type2 is MezzeType.OrderingType ->
            unifyOrderingWithVariant(type1, type1, type2)

        type1 is MezzeType.BytesType && type2 is MezzeType.BytesType -> UnificationResult.Success(Substitution.empty)
        type1 is MezzeType.ByteBufferType && type2 is MezzeType.ByteBufferType -> UnificationResult.Success(Substitution.empty)
        type1 is MezzeType.CharType && type2 is MezzeType.CharType -> UnificationResult.Success(Substitution.empty)

        else -> {
            UnificationResult.Failure(
                message = "Type mismatch: expected ${prettyPrintType(type2)}, but got ${prettyPrintType(type1)}",
                type1 = type1,
                type2 = type2
            )
        }
    }
}

private fun unifyVar(typeVar: TypeVar, type: MezzeType): UnificationResult {
    if (type is MezzeType.VarType && type.variable == typeVar) {
        return UnificationResult.Success(Substitution.empty)
    }
    if (type is MezzeType.SuspendedType) {
        val valueType = type.valueType
        if (valueType is MezzeType.VarType && valueType.variable == typeVar) {
            return UnificationResult.Success(Substitution.empty)
        }
    }
    if (occursCheck(typeVar, type)) {
        return UnificationResult.Failure(
            message = "Infinite type: type variable '${typeVar}' occurs inside '${prettyPrintType(type)}'. " +
                    "This would create an infinite type.",
            type1 = MezzeType.VarType(typeVar),
            type2 = type
        )
    }
    return UnificationResult.Success(Substitution.single(typeVar, type))
}

private fun unifyFunctions(fn1: MezzeType.FunctionType, fn2: MezzeType.FunctionType, registry: DeclarationRegistry): UnificationResult {
    val paramSubst = unify(fn1.paramType, fn2.paramType, registry).getOrElse { return it }
    val return1 = paramSubst.apply(fn1.returnType)
    val return2 = paramSubst.apply(fn2.returnType)
    val returnSubst = unify(return1, return2, registry).getOrElse { return it }
    val effects1 = fn1.effects
    val effects2 = fn2.effects

    val isPure1 = effects1 == null || (effects1.effects.isEmpty() && effects1.effectInstances.isEmpty() && effects1.rowVar == null)
    val isPure2 = effects2 == null || (effects2.effects.isEmpty() && effects2.effectInstances.isEmpty() && effects2.rowVar == null)
    val isPolymorphic1 = effects1 != null && effects1.effects.isEmpty() && effects1.effectInstances.isEmpty() && effects1.rowVar != null
    val isPolymorphic2 = effects2 != null && effects2.effects.isEmpty() && effects2.effectInstances.isEmpty() && effects2.rowVar != null
    val hasConcrete1 = effects1 != null && (effects1.effects.isNotEmpty() || effects1.effectInstances.isNotEmpty())
    val hasConcrete2 = effects2 != null && (effects2.effects.isNotEmpty() || effects2.effectInstances.isNotEmpty())

    val effectSubst = when {
        isPure1 && isPure2 -> Substitution.empty

        isPure1 && isPolymorphic2 -> Substitution.empty
        isPolymorphic1 && isPure2 -> Substitution.empty

        isPolymorphic1 && isPolymorphic2 -> Substitution.empty

        isPolymorphic1 && hasConcrete2 -> Substitution.empty
        hasConcrete1 && isPolymorphic2 -> Substitution.empty

        isPure1 && hasConcrete2 -> Substitution.empty
        hasConcrete1 && isPure2 -> {
            val allEffects = effects1!!.effects + effects1.effectInstances.map { it.name }
            return UnificationResult.Failure(
                message = "Cannot use effectful function <${allEffects.joinToString()}> where pure function is expected",
                type1 = fn1,
                type2 = fn2
            )
        }

        hasConcrete1 && hasConcrete2 -> {
            unifyEffectRows(effects1!!, effects2!!).getOrElse { return it }
        }

        else -> Substitution.empty
    }

    val finalSubst = returnSubst.compose(paramSubst).compose(effectSubst)
    return UnificationResult.Success(finalSubst)
}

private fun unifyRecords(rec1: MezzeType.RecordType, rec2: MezzeType.RecordType, registry: DeclarationRegistry): UnificationResult {
    val fields1 = rec1.fields.keys
    val fields2 = rec2.fields.keys

    if (rec1.rowVar != null || rec2.rowVar != null) {
        val allFields = (fields1 + fields2).toSet()

        var substitution = Substitution.empty
        for (fieldName in allFields) {
            val fieldType1 = rec1.fields[fieldName]
            val fieldType2 = rec2.fields[fieldName]

            when {
                fieldType1 != null && fieldType2 != null -> {
                    val appliedType1 = substitution.apply(fieldType1)
                    val appliedType2 = substitution.apply(fieldType2)

                    val fieldSubst = when (val fieldResult = unify(appliedType1, appliedType2, registry)) {
                        is UnificationResult.Success -> fieldResult.substitution
                        is UnificationResult.Failure -> return UnificationResult.Failure(
                            message = formatFieldMismatch(fieldName, appliedType2, appliedType1, fieldResult.message),
                            type1 = rec1,
                            type2 = rec2
                        )
                    }
                    substitution = fieldSubst.compose(substitution)
                }
                else -> {}
            }
        }

        return UnificationResult.Success(substitution)
    }

    if (fields1 != fields2) {
        val missing = fields2 - fields1   // expected has, got doesn't
        val extra   = fields1 - fields2   // got has, expected doesn't
        val parts = mutableListOf<String>()
        if (missing.isNotEmpty()) {
            parts.add("missing ${missing.joinToString(", ") { "'$it'" }}")
        }
        if (extra.isNotEmpty()) {
            parts.add("unexpected ${extra.joinToString(", ") { "'$it'" }}")
        }
        return UnificationResult.Failure(
            message = "record fields don't match: ${parts.joinToString("; ")}",
            type1 = rec1,
            type2 = rec2
        )
    }

    val sharedFields = fields1

    var substitution = Substitution.empty
    for (fieldName in sharedFields) {
        val fieldType1 = substitution.apply(rec1.fields[fieldName]!!)
        val fieldType2 = substitution.apply(rec2.fields[fieldName]!!)

        val fieldSubst = when (val fieldResult = unify(fieldType1, fieldType2, registry)) {
            is UnificationResult.Success -> fieldResult.substitution
            is UnificationResult.Failure -> return UnificationResult.Failure(
                message = formatFieldMismatch(fieldName, fieldType2, fieldType1, fieldResult.message),
                type1 = rec1,
                type2 = rec2
            )
        }
        substitution = fieldSubst.compose(substitution)
    }

    return UnificationResult.Success(substitution)
}

// #148: nominal wall if nominalWall=true or typeName is in caller's in-scope set.
private fun isVariantNameInScope(
    v: MezzeType.VariantType,
    registry: DeclarationRegistry
): Boolean {
    if (v.nominalWall) return true
    val name = v.typeName ?: return false
    val override = inScopeAliasNamesOverride.get()
    return if (override != null) override.contains(name)
           else registry.typeNames.contains(name)
}

private val inScopeAliasNamesOverride: ThreadLocal<Set<String>?> = ThreadLocal.withInitial { null }

fun <T> withInScopeAliasNames(names: Set<String>, block: () -> T): T {
    val prev = inScopeAliasNamesOverride.get()
    inScopeAliasNamesOverride.set(names)
    try {
        return block()
    } finally {
        inScopeAliasNamesOverride.set(prev)
    }
}

fun inScopeAliasNamesForSolverOrNull(): Set<String>? = inScopeAliasNamesOverride.get()

internal fun unifyVariants(var1: MezzeType.VariantType, var2: MezzeType.VariantType, registry: DeclarationRegistry): UnificationResult {
    val constructors1 = var1.constructors.keys
    val constructors2 = var2.constructors.keys

    if (var1.rowVar != null && var2.rowVar != null) {
        val allConstructors = (constructors1 + constructors2).toSet()

        var substitution = Substitution.empty
        for (constructorName in allConstructors) {
            val dataType1 = var1.constructors[constructorName]
            val dataType2 = var2.constructors[constructorName]

            if (dataType1 != null && dataType2 != null) {
                val appliedType1 = substitution.apply(dataType1)
                val appliedType2 = substitution.apply(dataType2)

                val constructorSubst = when (val constructorResult = unify(appliedType1, appliedType2, registry)) {
                    is UnificationResult.Success -> constructorResult.substitution
                    is UnificationResult.Failure -> return UnificationResult.Failure(
                        message = "Constructor '$constructorName' type mismatch: ${constructorResult.message}",
                        type1 = var1,
                        type2 = var2
                    )
                }
                substitution = constructorSubst.compose(substitution)
            }
        }

        return UnificationResult.Success(substitution)
    }

    if (var1.rowVar != null || var2.rowVar != null) {
        val (openVar, closedVar) = if (var1.rowVar != null) Pair(var1, var2) else Pair(var2, var1)
        val closedCtors = closedVar.constructors.keys
        val openCtors = openVar.constructors.keys

        // inline/inferred placeholder via #148's nominalWall flag), check that ALL
        // constructors in the open variant exist in the closed variant. This is what
        // prevents `Yellow` from unifying with `Color = | Red, Green, Blue |` when
        // Color is declared/imported here.
        if (isVariantNameInScope(closedVar, registry)) {
            val invalidConstructors = openCtors - closedCtors
            if (invalidConstructors.isNotEmpty()) {
                val available = closedCtors.sorted().joinToString(", ")
                val invalid = invalidConstructors.sorted().joinToString(", ")
                val typeDesc = when (closedVar.typeName) {
                    "<inline>" -> "inline variant type '| $available |'"
                    "<inferred>" -> "inferred variant type '| $available |'"
                    else -> "type '${closedVar.typeName}'"
                }
                return UnificationResult.Failure(
                    message = "Constructor(s) '$invalid' not found in $typeDesc. " +
                              "Available constructors: $available",
                    type1 = var1,
                    type2 = var2
                )
            }
        }

        var substitution = Substitution.empty
        for (constructorName in closedCtors) {
            val dataType1 = var1.constructors[constructorName]
            val dataType2 = var2.constructors[constructorName]

            if (dataType1 != null && dataType2 != null) {
                val appliedType1 = substitution.apply(dataType1)
                val appliedType2 = substitution.apply(dataType2)

                val constructorSubst = when (val constructorResult = unify(appliedType1, appliedType2, registry)) {
                    is UnificationResult.Success -> constructorResult.substitution
                    is UnificationResult.Failure -> return UnificationResult.Failure(
                        message = "Constructor '$constructorName' type mismatch: ${constructorResult.message}",
                        type1 = var1,
                        type2 = var2
                    )
                }
                substitution = constructorSubst.compose(substitution)
            }
        }

        return UnificationResult.Success(substitution)
    }

    // — but only if at least one of those names is in scope at the use site (#148). An alias
    // acts as a nominal wall only when its name is reachable by the caller (declared locally
    // or imported by name). When a value crosses a module boundary with an alias-typed shape
    // and the caller didn't import the alias, the name has no nominal force here.
    val bothNamed = (var1.typeHash != null && var2.typeHash != null) ||
                    (var1.typeName != null && var2.typeName != null)
    val namesMatch = if (var1.typeHash != null && var2.typeHash != null) {
        var1.typeHash == var2.typeHash
    } else {
        var1.typeName == var2.typeName
    }
    if (bothNamed && !namesMatch) {
        val var1NameInScope = isVariantNameInScope(var1, registry)
        val var2NameInScope = isVariantNameInScope(var2, registry)
        if (var1NameInScope || var2NameInScope) {
            // #149: when both walls happen to constrain to the SAME arm set,
            // unify successfully (their nominal identities are interchangeable
            // for value-typing purposes). Concretely this covers the pattern
            val sameArms = var1.constructors.keys == var2.constructors.keys
            if (!sameArms) {
                return UnificationResult.Failure(
                    message = "Cannot unify different variant types '${var1.typeName}' and '${var2.typeName}'",
                    type1 = var1,
                    type2 = var2
                )
            }
        }
    }

    // scope (#148). Otherwise the "declared" side acts purely as a structural arm set.
    val isDeclared1 = var1.typeHash != null || var1.typeName != null
    val isDeclared2 = var2.typeHash != null || var2.typeName != null
    val (declaredVar, anonymousVar) = when {
        isDeclared1 && !isDeclared2 -> Pair(var1, var2)
        isDeclared2 && !isDeclared1 -> Pair(var2, var1)
        else -> Pair(null, null)
    }

    if (declaredVar != null && anonymousVar != null) {
        val invalidConstructors = anonymousVar.constructors.keys - declaredVar.constructors.keys
        if (invalidConstructors.isNotEmpty() && isVariantNameInScope(declaredVar, registry)) {
            val available = declaredVar.constructors.keys.sorted().joinToString(", ")
            val invalid = invalidConstructors.sorted().joinToString(", ")
            val typeDesc = when (declaredVar.typeName) {
                "<inline>" -> "inline variant type '| $available |'"
                "<inferred>" -> "inferred variant type '| $available |'"
                else -> "type '${declaredVar.typeName}'"
            }
            return UnificationResult.Failure(
                message = "Constructor(s) '$invalid' not found in $typeDesc. " +
                          "Available constructors: $available",
                type1 = var1,
                type2 = var2
            )
        }
    }

    val allConstructors = (constructors1 + constructors2).toSet()

    var substitution = Substitution.empty
    for (constructorName in allConstructors) {
        val dataType1 = var1.constructors[constructorName]
        val dataType2 = var2.constructors[constructorName]

        if (constructorName in constructors1 && constructorName in constructors2) {
            if ((dataType1 == null) != (dataType2 == null)) {
                return UnificationResult.Failure(
                    message = "Constructor '$constructorName' data mismatch",
                    type1 = var1,
                    type2 = var2
                )
            }

            if (dataType1 != null && dataType2 != null) {
                val appliedType1 = substitution.apply(dataType1)
                val appliedType2 = substitution.apply(dataType2)

                val constructorSubst = when (val constructorResult = unify(appliedType1, appliedType2, registry)) {
                    is UnificationResult.Success -> constructorResult.substitution
                    is UnificationResult.Failure -> return UnificationResult.Failure(
                        message = "Constructor '$constructorName' type mismatch: ${constructorResult.message}",
                        type1 = var1,
                        type2 = var2
                    )
                }
                substitution = constructorSubst.compose(substitution)
            }
        }
    }

    return UnificationResult.Success(substitution)
}

private fun unifyTypeApplications(app1: MezzeType.TypeApplication, app2: MezzeType.TypeApplication, registry: DeclarationRegistry): UnificationResult {
    val constructorsMatch = app1.constructor == app2.constructor ||
        (app1.typeHash != null && app2.typeHash != null && app1.typeHash == app2.typeHash)

    if (!constructorsMatch) {
        val expanded1 = expandTypeApplicationToVariant(app1, registry)
        val expanded2 = expandTypeApplicationToVariant(app2, registry)

        if (expanded1 != null && expanded2 != null) {
            if (expanded1.rowVar != null || expanded2.rowVar != null) {
                return unifyVariants(expanded1, expanded2, registry)
            }

            // STRUCTURAL ALIAS HANDLING (#148): two distinct closed aliases. An
            // alias name acts as a nominal barrier ONLY when it's in scope in
            // the caller's module — declared locally or imported by name. When
            // NEITHER side is in scope, both names are reachable-but-unqualified
            // labels and the typechecker should treat the values structurally,
            // unifying their arm sets.
            val override = inScopeAliasNamesOverride.get()
            val app1InScope = if (override != null) override.contains(app1.constructor)
                              else registry.typeNames.contains(app1.constructor)
            val app2InScope = if (override != null) override.contains(app2.constructor)
                              else registry.typeNames.contains(app2.constructor)
            if (!app1InScope && !app2InScope) {
                return unifyVariants(expanded1, expanded2, registry)
            }
        }

        return UnificationResult.Failure(
            message = "Type constructor mismatch: ${app1.constructor} vs ${app2.constructor}",
            type1 = app1,
            type2 = app2
        )
    }

    if (app1.args.size != app2.args.size) {
        return UnificationResult.Failure(
            message = "Type argument count mismatch: ${app1.args.size} vs ${app2.args.size}",
            type1 = app1,
            type2 = app2
        )
    }

    var substitution = Substitution.empty
    for (i in app1.args.indices) {
        val arg1 = substitution.apply(app1.args[i])
        val arg2 = substitution.apply(app2.args[i])

        val argSubst = when (val argResult = unify(arg1, arg2, registry)) {
            is UnificationResult.Success -> argResult.substitution
            is UnificationResult.Failure -> return UnificationResult.Failure(
                message = "Type argument $i mismatch: ${argResult.message}",
                type1 = app1,
                type2 = app2
            )
        }
        substitution = argSubst.compose(substitution)
    }

    return UnificationResult.Success(substitution)
}

private fun unifyMaps(map1: MezzeType.MapType, map2: MezzeType.MapType, registry: DeclarationRegistry): UnificationResult =
    unifyKeyValue(map1.keyType, map1.valueType, map2.keyType, map2.valueType, registry)

private fun unifySortedMaps(map1: MezzeType.SortedMapType, map2: MezzeType.SortedMapType, registry: DeclarationRegistry): UnificationResult =
    unifyKeyValue(map1.keyType, map1.valueType, map2.keyType, map2.valueType, registry)

private fun unifySuspended(susp1: MezzeType.SuspendedType, susp2: MezzeType.SuspendedType, registry: DeclarationRegistry): UnificationResult {
    val valueSubst = unify(susp1.valueType, susp2.valueType, registry).getOrElse { return it }
    val effectSubst = unifyEffectRows(susp1.effects, susp2.effects).getOrElse { return it }
    return UnificationResult.Success(valueSubst.compose(effectSubst))
}

private fun unifyEffectRows(eff1: MezzeType.EffectRow, eff2: MezzeType.EffectRow): UnificationResult {
    val allEffects1 = eff1.allEffectNames
    val allEffects2 = eff2.allEffectNames
    val combinedEffects = allEffects1 + allEffects2

    val combinedHashes = eff1.effectHashes + eff2.effectHashes
    fun resolveEffectHash(name: String): Hash? = combinedHashes[name] ?: WellKnownHashConstants.resolveHash(name)

    val mutEffect = combinedEffects.firstOrNull { name ->
        WellKnownHashConstants.isMut(resolveEffectHash(name))
    }
    if (mutEffect != null && combinedEffects.size > 1) {
        val otherEffects = combinedEffects.filter { name ->
            !WellKnownHashConstants.isMut(resolveEffectHash(name))
        }.joinToString(", ")
        return UnificationResult.Failure(
            message = "$mutEffect effect is hermetic: cannot coexist with other effects ($otherEffects). " +
                "$mutEffect must be the only effect in scope to prevent data races. " +
                "Either handle the other effects before entering the mutation scope, " +
                "or restructure the code to separate mutation from other effectful operations.",
            type1 = eff1,
            type2 = eff2
        )
    }

    val instances1 = eff1.effectInstances.associateBy { it.name }
    val instances2 = eff2.effectInstances.associateBy { it.name }

    val commonEffects = instances1.keys.intersect(instances2.keys)

    var composedSubst = Substitution.empty
    for (effectName in commonEffects) {
        val inst1 = instances1[effectName]!!
        val inst2 = instances2[effectName]!!

        val allKeys = inst1.associatedTypes.keys + inst2.associatedTypes.keys
        for (key in allKeys) {
            val type1 = inst1.associatedTypes[key]
            val type2 = inst2.associatedTypes[key]

            if (type1 != null && type2 != null) {
                val result = unify(composedSubst.apply(type1), composedSubst.apply(type2))
                when (result) {
                    is UnificationResult.Success -> {
                        composedSubst = composedSubst.compose(result.substitution)
                    }
                    is UnificationResult.Failure -> {
                        return UnificationResult.Failure(
                            message = "Effect '$effectName' has incompatible associated type '$key': " +
                                "expected ${prettyPrintType(type2)} but got ${prettyPrintType(type1)}",
                            type1 = eff1,
                            type2 = eff2
                        )
                    }
                }
            }
        }
    }

    return UnificationResult.Success(composedSubst)
}

/**
 * Build a focused error message when a record field's type fails to unify
 * against an expected shape. Avoids the doubled "Field 'x' type mismatch:
 * Type mismatch:" phrasing — strips the inner generic prefix and, when the
 * expected field type is a closed record (the shape produced by issue #216's
 * method-call inference), foregrounds the shape so the user sees what they
 * needed to pass.
 */
private val ORDERING_CONSTRUCTORS = setOf("LT", "EQ", "GT")

private fun unifyKeyValue(
    key1: MezzeType, val1: MezzeType,
    key2: MezzeType, val2: MezzeType,
    registry: DeclarationRegistry
): UnificationResult {
    val keyResult = unify(key1, key2, registry)
    return when (keyResult) {
        is UnificationResult.Success -> {
            val valueResult = unify(
                keyResult.substitution.apply(val1),
                keyResult.substitution.apply(val2),
                registry
            )
            when (valueResult) {
                is UnificationResult.Success -> UnificationResult.Success(keyResult.substitution.compose(valueResult.substitution))
                is UnificationResult.Failure -> valueResult
            }
        }
        is UnificationResult.Failure -> keyResult
    }
}

private fun unifyOrderingWithVariant(variant: MezzeType.VariantType, type1: MezzeType, type2: MezzeType): UnificationResult {
    val exactMatch = variant.constructors.keys == ORDERING_CONSTRUCTORS &&
                   variant.constructors.all { (_, payload) -> payload == null } &&
                   variant.rowVar == null
    val subsetWithOpenRow = variant.constructors.keys.all { it in ORDERING_CONSTRUCTORS } &&
                   variant.constructors.all { (_, payload) -> payload == null } &&
                   variant.rowVar != null
    return if (exactMatch || subsetWithOpenRow) {
        UnificationResult.Success(Substitution.empty)
    } else {
        UnificationResult.Failure(
            message = "Type mismatch: expected ${prettyPrintType(type2)}, but got ${prettyPrintType(type1)}",
            type1 = type1,
            type2 = type2
        )
    }
}

private fun formatFieldMismatch(
    fieldName: String,
    expectedFieldType: MezzeType,
    actualFieldType: MezzeType,
    innerMessage: String
): String {
    val expectedStr = prettyPrintType(expectedFieldType)
    val actualStr = prettyPrintType(actualFieldType)
    val baseMessage = "Field '$fieldName' type mismatch: expected $expectedStr, got $actualStr"
    val cleanedInner = innerMessage.removePrefix("Type mismatch: ").trim()
    val isShallow = cleanedInner.contains(expectedStr) && cleanedInner.contains(actualStr)
    return if (isShallow) baseMessage else "$baseMessage ($cleanedInner)"
}

private fun substituteTypeParamsByName(type: MezzeType, subst: Map<String, MezzeType>): MezzeType {
    return when (type) {
        is MezzeType.VarType -> {
            val name = type.variable.name
            subst[name] ?: subst["'$name"] ?: subst[name.removePrefix("'")] ?: type
        }
        is MezzeType.RecordType -> MezzeType.RecordType(
            fields = type.fields.mapValues { (_, fieldType) -> substituteTypeParamsByName(fieldType, subst) },
            rowVar = type.rowVar
        )
        is MezzeType.FunctionType -> MezzeType.FunctionType(
            paramType = substituteTypeParamsByName(type.paramType, subst),
            returnType = substituteTypeParamsByName(type.returnType, subst),
            effects = type.effects,
            abilityConstraints = type.abilityConstraints
        )
        is MezzeType.TypeApplication -> MezzeType.TypeApplication(
            constructor = type.constructor,
            args = type.args.map { substituteTypeParamsByName(it, subst) },
            typeHash = type.typeHash
        )
        is MezzeType.SeqType -> MezzeType.SeqType(substituteTypeParamsByName(type.elementType, subst))
        is MezzeType.ListType -> MezzeType.ListType(substituteTypeParamsByName(type.elementType, subst))
        is MezzeType.SetType -> MezzeType.SetType(substituteTypeParamsByName(type.elementType, subst))
        is MezzeType.SortedMapType -> MezzeType.SortedMapType(
            keyType = substituteTypeParamsByName(type.keyType, subst),
            valueType = substituteTypeParamsByName(type.valueType, subst)
        )
        is MezzeType.SortedSetType -> MezzeType.SortedSetType(substituteTypeParamsByName(type.elementType, subst))
        is MezzeType.MapType -> MezzeType.MapType(
            keyType = substituteTypeParamsByName(type.keyType, subst),
            valueType = substituteTypeParamsByName(type.valueType, subst)
        )
        is MezzeType.VariantType -> MezzeType.VariantType(
            constructors = type.constructors.mapValues { (_, dataType) ->
                dataType?.let { substituteTypeParamsByName(it, subst) }
            },
            rowVar = type.rowVar
        )
        is MezzeType.SuspendedType -> MezzeType.SuspendedType(
            valueType = substituteTypeParamsByName(type.valueType, subst),
            effects = type.effects
        )
        is MezzeType.NewtypeType -> MezzeType.NewtypeType(
            type.name,
            type.constructor,
            substituteTypeParamsByName(type.wrappedType, subst)
        )
        is MezzeType.IntType, is MezzeType.FloatType, is MezzeType.StringType,
        is MezzeType.BoolType, is MezzeType.NatType, is MezzeType.FracType,
        is MezzeType.OrderingType, is MezzeType.BytesType, is MezzeType.ByteBufferType,
        is MezzeType.CharType, is MezzeType.EffectRow -> type
    }
}

internal fun expandTypeApplicationToRecord(typeApp: MezzeType.TypeApplication, registry: DeclarationRegistry): MezzeType.RecordType? {
    val typeDecl = registry.lookupType(typeApp.constructor, typeApp.typeHash) ?: return null

    val recordDef = typeDecl.definition as? ParsedRecordDef ?: return null

    val typeParamSubst = typeDecl.typeParams.zip(typeApp.args).associate { (paramName, argType) ->
        paramName to argType
    }

    val contextBuilder = ContextBuilder()

    val fields = recordDef.fields.associate { field ->
        val convertedType = contextBuilder.convertParsedTypeAnnotation(field.type)
        val substitutedType = substituteTypeParamsByName(convertedType, typeParamSubst)
        field.name to substitutedType
    }

    return MezzeType.RecordType(
        fields = fields,
        rowVar = if (recordDef.isOpen) RowVar.fresh() else null
    )
}

internal fun expandTypeApplicationToVariant(typeApp: MezzeType.TypeApplication, registry: DeclarationRegistry): MezzeType.VariantType? {
    val typeDecl = registry.lookupType(typeApp.constructor, typeApp.typeHash)
        ?: ModuleResolver.lookupTypeDeclaration(typeApp.constructor)
        ?: return null

    val variantDef = when (val def = typeDecl.definition) {
        is ParsedVariantDef -> def
        is ParsedNewtypeDef -> {
            val payloadType = if (def.wrappedType is ParsedRecordType) {
                def.wrappedType
            } else {
                val synLoc = Loc("<expand>", 0, 0, 0, 0)
                ParsedRecordType(
                    entries = listOf(ParsedRecordTypeEntry("val", def.wrappedType, synLoc)),
                    loc = synLoc
                )
            }
            val synLoc = Loc("<expand>", 0, 0, 0, 0)
            ParsedVariantDef(listOf(ParsedVariantCtorDef(def.constructor, payloadType, loc = synLoc)), loc = synLoc)
        }
        else -> return null
    }

    DebugLogger.debugSection(Category.Unification, "expandTypeApplication") {
        listOf(
            "Expanding ${typeApp.constructor}",
            "Variant has ${variantDef.constructors.size} constructors: ${variantDef.constructors.map { it.name }}"
        )
    }

    val typeParamSubst = typeDecl.typeParams.zip(typeApp.args).associate { (paramName, argType) ->
        paramName to argType
    }

    val contextBuilder = ContextBuilder()

    val constructors = variantDef.constructors.associate { constructor ->
        val dataType = constructor.dataType?.let { annotation ->
            val convertedType = contextBuilder.convertParsedTypeAnnotation(annotation)
            substituteTypeParamsByName(convertedType, typeParamSubst)
        }
        constructor.name to dataType
    }

    DebugLogger.debugSection(Category.Unification, "expandTypeApplicationToVariant") {
        listOf(
            "Expanding ${typeApp.constructor}",
            "VariantDef has ${variantDef.constructors.size} constructors: ${variantDef.constructors.map { it.name }}",
            "Built ${constructors.size} constructors: ${constructors.keys}"
        )
    }

    return MezzeType.VariantType(
        constructors = constructors,
        rowVar = if (variantDef.isOpen) RowVar.fresh() else null,
        typeName = typeDecl.name,
        typeArgs = typeApp.args,   // #194: carry the application's args so the printer can render `Tree Int`
        typeHash = typeDecl.contentHash
    )
}

sealed class TypeExpansionResult {
    data class Record(val recordType: MezzeType.RecordType) : TypeExpansionResult()

    data class Variant(val variantType: MezzeType.VariantType) : TypeExpansionResult()

    data class Newtype(
        val variantType: MezzeType.VariantType,
        val constructorName: String,
        val payloadType: MezzeType?
    ) : TypeExpansionResult()

    object NotFound : TypeExpansionResult()
}

internal fun expandTypeApplication(
    typeApp: MezzeType.TypeApplication,
    registry: DeclarationRegistry
): TypeExpansionResult {
    val typeDecl = registry.lookupType(typeApp.constructor, typeApp.typeHash) ?: return TypeExpansionResult.NotFound

    return when (val definition = typeDecl.definition) {
        is ParsedRecordDef -> {
            val expanded = expandTypeApplicationToRecord(typeApp, registry)
            if (expanded != null) {
                TypeExpansionResult.Record(expanded)
            } else {
                TypeExpansionResult.NotFound
            }
        }
        is ParsedVariantDef -> {
            val expanded = expandTypeApplicationToVariant(typeApp, registry)
            if (expanded != null) {
                TypeExpansionResult.Variant(expanded)
            } else {
                TypeExpansionResult.NotFound
            }
        }
        is ParsedNewtypeDef -> {
            val expanded = expandTypeApplicationToVariant(typeApp, registry)
            if (expanded != null && expanded.constructors.size == 1) {
                val (constructorName, payloadType) = expanded.constructors.entries.first()
                TypeExpansionResult.Newtype(expanded, constructorName, payloadType)
            } else {
                TypeExpansionResult.NotFound
            }
        }
    }
}

private fun isBuiltinCollectionApp(app: MezzeType.TypeApplication, expectedConstructor: String): Boolean =
    app.constructor == expectedConstructor
