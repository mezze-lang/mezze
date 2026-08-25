package com.mezze.language.typechecker.constraints

import com.mezze.language.ast.*
import com.mezze.language.parsedast.*
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.typechecker.ErrorType
import com.mezze.language.parsedast.diagnostics.ErrorCode
import com.mezze.language.typechecker.util.WellKnownTypeApp

internal fun ExpressionConstraintGenerator.tryGenerateEffectOperationCall(
    expr: ParsedMemberAccess,
    effectName: String,
    effectBinding: TypingContext.Binding,
    context: ConstraintContext
): ConstraintResult? {
    if (effectName !in context.effectNames) {
        return null
    }

    val effectType = when (effectBinding) {
        is TypingContext.Binding.Monomorphic -> effectBinding.type
        is TypingContext.Binding.Polymorphic -> {
            effectBinding.scheme.instantiate().type
        }
    }

    if (effectType !is MezzeType.RecordType) {
        throw IllegalStateException("Effect '$effectName' is not properly registered")
    }

    val operationName = expr.member
    val operationType = effectType.fields[operationName]

    if (operationType == null) {
        val availableOps = effectType.fields.keys
        val suggestion = findClosestMatch(operationName, availableOps)
        val didYouMean = if (suggestion != null) " Did you mean '$suggestion'?" else ""
        throw TypeCheckError(
            message = "Operation '$operationName' not found in effect '$effectName'.$didYouMean Available operations: ${availableOps.joinToString(", ")}",
            loc = expr.loc,
            errorType = ErrorType.MISSING_FIELD,
            code = ErrorCode.T0206_METHOD_NOT_FOUND,
        )
    }

    var instantiatedOperationType = operationType

    val performContext = context.performContext
    if (performContext != null && instantiatedOperationType is MezzeType.FunctionType) {
        var performerName: String? = null

        for (handler in performContext.handlers) {
            val handlerEffectName = context.performerToEffect[handler.performer.localName]
            if (handlerEffectName == effectName) {
                performerName = handler.performer.localName
                break
            }
        }

        if (performerName != null) {
            val performerInfos = context.typingContext.getPerformerTypeInfosByName(performerName)
            if (performerInfos.isNotEmpty()) {
                val performerInfo = performerInfos.first()
                val performerType = performerInfo.associatedTypes

                if (performerType is MezzeType.RecordType) {
                    instantiatedOperationType = substituteAssociatedTypesInType(
                        instantiatedOperationType,
                        performerType.fields
                    )

                    val performerTypeVarNames = performerType.fields.values
                        .filterIsInstance<MezzeType.VarType>()
                        .map { it.variable.name }
                    instantiatedOperationType = instantiateTypeWithFreshVarsPreservingPerformerBindings(
                        instantiatedOperationType,
                        performerTypeVarNames
                    )
                } else {
                    instantiatedOperationType = instantiateTypeWithFreshVars(instantiatedOperationType)
                }
            } else {
                instantiatedOperationType = instantiateTypeWithFreshVars(instantiatedOperationType)
            }
        } else {
            instantiatedOperationType = instantiateTypeWithFreshVars(instantiatedOperationType)
        }
    } else {
        instantiatedOperationType = instantiateTypeWithFreshVars(instantiatedOperationType)
    }

    val effectDecl = context.effectDeclarations[effectName]
    val hasAssociatedTypes = effectDecl != null && effectDecl.associatedTypes.isNotEmpty()

    val effectInstances = if (hasAssociatedTypes) {
        val effectInstance = createEffectInstanceFromInstantiated(
            effectName,
            operationType,
            instantiatedOperationType,
            context
        )
        setOf(effectInstance)
    } else {
        emptySet()
    }

    // #312: when the operation's own outer effect row mentions the parent effect
    // explicitly (e.g. `query: ... -> Str <Database, Log>` inside `effect Database`),
    // we trust the row as exhaustive and surface ALL of its effects. When the row
    // names a different effect entirely (e.g. `succeed: ... <Result>` inside
    // `effect Outcome`), we treat that as a free-form "tag" annotation and fall
    // back to just the parent effect — preserving long-standing behavior of
    // EffectAssociatedTypesInterpreterTest. (Empty op row → parent only.)
    val opOwnEffects = effectDecl?.operations
        ?.firstOrNull { it.name == operationName }
        ?.effects?.toSet().orEmpty()
    val operationEffects = if (effectName in opOwnEffects) opOwnEffects else setOf(effectName)
    return ConstraintResult(
        type = instantiatedOperationType,
        constraints = emptyList(),
        effects = operationEffects,
        effectInstances = effectInstances,
        undefinedVariables = emptyList()
    )
}

internal fun ExpressionConstraintGenerator.createEffectInstanceFromInstantiated(
    effectName: String,
    originalType: MezzeType,
    instantiatedType: MezzeType,
    context: ConstraintContext
): MezzeType.EffectInstance {
    val effectDecl = context.effectDeclarations[effectName]
        ?: return MezzeType.EffectInstance(effectName, emptyMap())

    if (effectDecl.associatedTypes.isEmpty()) {
        return MezzeType.EffectInstance(effectName, emptyMap())
    }

    val typeVarMapping = buildTypeVarMapping(originalType, instantiatedType)

    val associatedTypeBindings = HashMap<String, MezzeType>(effectDecl.associatedTypes.size)

    if (originalType is MezzeType.FunctionType) {
        for (assocTypeName in effectDecl.associatedTypes) {
            val normalizedName = assocTypeName.removePrefix("'")

            val originalVar = typeVarMapping.keys.find { tv ->
                tv.name == normalizedName || tv.name == "'$normalizedName"
            }

            if (originalVar != null) {
                val instantiatedVar = typeVarMapping[originalVar]
                if (instantiatedVar != null) {
                    associatedTypeBindings[normalizedName] = MezzeType.VarType(instantiatedVar)
                } else {
                    associatedTypeBindings[normalizedName] = MezzeType.VarType(originalVar)
                }
            }
        }
    }

    return MezzeType.EffectInstance(effectName, associatedTypeBindings)
}

internal fun ExpressionConstraintGenerator.buildTypeVarMapping(original: MezzeType, instantiated: MezzeType): Map<TypeVar, TypeVar> {
    val cacheKey = System.identityHashCode(original) to System.identityHashCode(instantiated)

    typeVarMappingCache[cacheKey]?.let { return it }

    val mapping = mutableMapOf<TypeVar, TypeVar>()
    buildTypeVarMappingHelper(original, instantiated, mapping)

    if (typeVarMappingCache.size >= maxCacheSize) {
        val keysToRemove = typeVarMappingCache.keys.take(maxCacheSize / 2)
        keysToRemove.forEach { typeVarMappingCache.remove(it) }
    }
    typeVarMappingCache[cacheKey] = mapping

    return mapping
}

internal fun ExpressionConstraintGenerator.buildTypeVarMappingHelper(original: MezzeType, instantiated: MezzeType, mapping: MutableMap<TypeVar, TypeVar>) {
    when {
        original is MezzeType.VarType && instantiated is MezzeType.VarType -> {
            mapping[original.variable] = instantiated.variable
        }
        original is MezzeType.FunctionType && instantiated is MezzeType.FunctionType -> {
            buildTypeVarMappingHelper(original.paramType, instantiated.paramType, mapping)
            buildTypeVarMappingHelper(original.returnType, instantiated.returnType, mapping)
        }
        original is MezzeType.RecordType && instantiated is MezzeType.RecordType -> {
            for ((fieldName, fieldType) in original.fields) {
                val instFieldType = instantiated.fields[fieldName]
                if (instFieldType != null) {
                    buildTypeVarMappingHelper(fieldType, instFieldType, mapping)
                }
            }
        }
        original is MezzeType.VariantType && instantiated is MezzeType.VariantType -> {
            for ((ctorName, ctorType) in original.constructors) {
                val instCtorType = instantiated.constructors[ctorName]
                if (ctorType != null && instCtorType != null) {
                    buildTypeVarMappingHelper(ctorType, instCtorType, mapping)
                }
            }
        }
        original is MezzeType.TypeApplication && instantiated is MezzeType.TypeApplication -> {
            original.args.zip(instantiated.args).forEach { (orig, inst) ->
                buildTypeVarMappingHelper(orig, inst, mapping)
            }
        }
        original is MezzeType.ListType && instantiated is MezzeType.ListType -> {
            buildTypeVarMappingHelper(original.elementType, instantiated.elementType, mapping)
        }
        original is MezzeType.SeqType && instantiated is MezzeType.SeqType -> {
            buildTypeVarMappingHelper(original.elementType, instantiated.elementType, mapping)
        }
    }
}
internal fun ExpressionConstraintGenerator.tryGeneratePerformerMethodCall(
    expr: ParsedMemberAccess,
    performerName: String,
    effectName: String,
    effectBinding: TypingContext.Binding,
    context: ConstraintContext
): ConstraintResult? {
    val effectType = when (effectBinding) {
        is TypingContext.Binding.Monomorphic -> effectBinding.type
        is TypingContext.Binding.Polymorphic -> {
            effectBinding.scheme.instantiate().type
        }
    }

    if (effectType !is MezzeType.RecordType) {
        return null
    }

    val operationName = expr.member
    val operationType = effectType.fields[operationName] ?: return null

    var instantiatedOperationType = operationType
    val performerInfos = context.typingContext.getPerformerTypeInfosByName(performerName)
    if (performerInfos.isNotEmpty()) {
        val performerInfo = performerInfos.first()
        val performerType = performerInfo.associatedTypes

        if (performerType is MezzeType.RecordType) {
            instantiatedOperationType = substituteAssociatedTypesInType(
                instantiatedOperationType,
                performerType.fields
            )
        }
    }

    instantiatedOperationType = instantiateTypeWithFreshVars(instantiatedOperationType)

    return ConstraintResult(
        type = instantiatedOperationType,
        constraints = emptyList(),
        effects = setOf(effectName),
        undefinedVariables = emptyList()
    )
}

internal fun ExpressionConstraintGenerator.substituteAssociatedTypesInType(
    type: MezzeType,
    associatedTypeBindings: Map<String, MezzeType>
): MezzeType {
    return when (type) {
        is MezzeType.VarType -> {
            val varName = type.variable.name
            val keyWithoutQuote = varName.removePrefix("'")
            associatedTypeBindings[varName] ?: associatedTypeBindings[keyWithoutQuote] ?: type
        }
        is MezzeType.TypeApplication -> {
            if (type.args.isEmpty() && type.constructor.startsWith("'")) {
                associatedTypeBindings[type.constructor] ?: type
            } else {
                WellKnownTypeApp.of(
                    constructor = type.constructor,
                    args = type.args.map { substituteAssociatedTypesInType(it, associatedTypeBindings) },
                    typeHash = type.typeHash
                )
            }
        }
        is MezzeType.FunctionType -> MezzeType.FunctionType(
            paramType = substituteAssociatedTypesInType(type.paramType, associatedTypeBindings),
            returnType = substituteAssociatedTypesInType(type.returnType, associatedTypeBindings),
            effects = type.effects,
            abilityConstraints = type.abilityConstraints
        )
        is MezzeType.RecordType -> MezzeType.RecordType(
            fields = type.fields.mapValues { (_, fieldType) ->
                substituteAssociatedTypesInType(fieldType, associatedTypeBindings)
            },
            rowVar = type.rowVar,
            typeName = type.typeName,
            typeHash = type.typeHash
        )
        is MezzeType.VariantType -> MezzeType.VariantType(
            constructors = type.constructors.mapValues { (_, dataType) ->
                dataType?.let { substituteAssociatedTypesInType(it, associatedTypeBindings) }
            },
            rowVar = type.rowVar,
            typeName = type.typeName,
            typeArgs = type.typeArgs.map { substituteAssociatedTypesInType(it, associatedTypeBindings) },
            typeHash = type.typeHash,
            ascriptionWall = type.ascriptionWall
        )
        is MezzeType.SeqType -> MezzeType.SeqType(
            elementType = substituteAssociatedTypesInType(type.elementType, associatedTypeBindings)
        )
        is MezzeType.ListType -> MezzeType.ListType(
            elementType = substituteAssociatedTypesInType(type.elementType, associatedTypeBindings)
        )
        is MezzeType.SetType -> MezzeType.SetType(
            elementType = substituteAssociatedTypesInType(type.elementType, associatedTypeBindings)
        )
        is MezzeType.SortedMapType -> MezzeType.SortedMapType(
            keyType = substituteAssociatedTypesInType(type.keyType, associatedTypeBindings),
            valueType = substituteAssociatedTypesInType(type.valueType, associatedTypeBindings)
        )
        is MezzeType.SortedSetType -> MezzeType.SortedSetType(
            elementType = substituteAssociatedTypesInType(type.elementType, associatedTypeBindings)
        )
        is MezzeType.MapType -> MezzeType.MapType(
            keyType = substituteAssociatedTypesInType(type.keyType, associatedTypeBindings),
            valueType = substituteAssociatedTypesInType(type.valueType, associatedTypeBindings)
        )
        is MezzeType.SuspendType -> MezzeType.SuspendType(
            valueType = substituteAssociatedTypesInType(type.valueType, associatedTypeBindings),
            effects = type.effects
        )
        is MezzeType.NewtypeType -> type.copy(
            wrappedType = substituteAssociatedTypesInType(type.wrappedType, associatedTypeBindings)
        )
        is MezzeType.VarType, is MezzeType.IntType, is MezzeType.FloatType, is MezzeType.StringType,
        is MezzeType.BoolType, is MezzeType.NatType, is MezzeType.FracType,
        is MezzeType.BytesType, is MezzeType.ByteBufferType,
        is MezzeType.CharType, is MezzeType.EffectRow -> type
    }
}

