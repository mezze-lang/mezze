package com.mezze.language.typechecker.constraints

import com.mezze.language.ast.*
import com.mezze.language.parsedast.*
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.typechecker.ErrorType
import com.mezze.language.parsedast.diagnostics.ErrorCode

/**
 * Effect operation and performer method-call constraint generators,
 * plus associated-type substitution and TypeVar mapping helpers.
 *
 * Extension functions on [ExpressionConstraintGenerator].
 */

internal fun ExpressionConstraintGenerator.tryGenerateEffectOperationCall(
    expr: ParsedMemberAccess,
    effectName: String,
    effectBinding: TypingContext.Binding,
    context: ConstraintContext
): ConstraintResult? {
    // Check if this is actually an effect (not an ability)
    if (effectName !in context.effectNames) {
        return null  // Not an effect, caller will try ability method
    }

    // Get effect type from binding
    val effectType = when (effectBinding) {
        is TypingContext.Binding.Monomorphic -> effectBinding.type
        is TypingContext.Binding.Polymorphic -> {
            // Instantiate polymorphic effect type with fresh type variables
            effectBinding.scheme.instantiate().type
        }
    }

    // Effect should be stored as RecordType with operations as fields
    if (effectType !is MezzeType.RecordType) {
        // Shouldn't happen if ContextBuilder is working correctly
        throw IllegalStateException("Effect '$effectName' is not properly registered")
    }

    // Look up operation in effect
    val operationName = expr.member
    val operationType = effectType.fields[operationName]

    if (operationType == null) {
        // Operation not found - suggest similar operation names
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

    // For effect operations, we DON'T want to instantiate with fresh vars yet
    // because we need to preserve associated type variable names for substitution
    // We'll instantiate AFTER substitution if needed
    var instantiatedOperationType = operationType

    // CRITICAL: If we're in a perform context and this effect has associated types,
    // substitute the associated type variables with the concrete types from the performer
    // This matches OldTypeChecker.kt lines 4810-4854
    val performContext = context.performContext
    if (performContext != null && instantiatedOperationType is MezzeType.FunctionType) {
        // Find which performer is handling this effect
        // The performer handlers are in performContext.handlers
        var performerName: String? = null

        // Try each handler in the perform context
        for (handler in performContext.handlers) {
            val handlerEffectName = context.performerToEffect[handler.performer.localName]
            if (handlerEffectName == effectName) {
                performerName = handler.performer.localName
                break
            }
        }

        if (performerName != null) {
            // Get the performer's associated type bindings using dedicated map
            val performerInfos = context.typingContext.getPerformerTypeInfosByName(performerName)
            if (performerInfos.isNotEmpty()) {
                val performerInfo = performerInfos.first()
                val performerType = performerInfo.associatedTypes

                if (performerType is MezzeType.RecordType) {
                    // Substitute associated types in the operation type
                    // The performer's type has fields like: {env -> String}
                    instantiatedOperationType = substituteAssociatedTypesInType(
                        instantiatedOperationType,
                        performerType.fields
                    )

                    // CRITICAL: Preserve performer binding TypeVars during instantiation
                    // These TypeVars connect to the suspension type, so constraints
                    // from operation calls (e.g., 'error = String) must flow to the
                    // suspension type (e.g., Result 'val 'error -> Result 'val String)
                    val performerTypeVarNames = performerType.fields.values
                        .filterIsInstance<MezzeType.VarType>()
                        .map { it.variable.name }
                    instantiatedOperationType = instantiateTypeWithFreshVarsPreservingPerformerBindings(
                        instantiatedOperationType,
                        performerTypeVarNames
                    )
                } else {
                    // No performer bindings to preserve
                    instantiatedOperationType = instantiateTypeWithFreshVars(instantiatedOperationType)
                }
            } else {
                // No performer info, use regular instantiation
                instantiatedOperationType = instantiateTypeWithFreshVars(instantiatedOperationType)
            }
        } else {
            // No performer found, use regular instantiation
            instantiatedOperationType = instantiateTypeWithFreshVars(instantiatedOperationType)
        }
    } else {
        // Not in perform context, use regular instantiation
        instantiatedOperationType = instantiateTypeWithFreshVars(instantiatedOperationType)
    }

    // OPTIMIZATION: Only create effectInstance when the effect has associated types
    // This avoids expensive structural traversal for simple effects without associated types
    val effectDecl = context.effectDeclarations[effectName]
    val hasAssociatedTypes = effectDecl != null && effectDecl.associatedTypes.isNotEmpty()

    val effectInstances = if (hasAssociatedTypes) {
        // Create an EffectInstance with associated types from the INSTANTIATED operation type
        // This tracks the TypeVars that will be constrained by the call arguments
        // For example: Exception.throw {error = "boom"} creates Exception having 'error = String
        val effectInstance = createEffectInstanceFromInstantiated(
            effectName,
            operationType,
            instantiatedOperationType,
            context
        )
        setOf(effectInstance)
    } else {
        // No associated types - skip the expensive mapping
        emptySet()
    }

    // Return the plain function type with the effects tracked in ConstraintResult.
    //
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
        effectInstances = effectInstances,  // Track effect with associated types (only if present)
        undefinedVariables = emptyList()
    )
}

/**
 * Create an EffectInstance from an instantiated operation type.
 *
 * This function maps from the original operation type (with named TypeVars like 'error)
 * to the instantiated type (with fresh TypeVars like $tv123). It finds the corresponding
 * fresh TypeVars for each associated type by structural matching.
 *
 * Example: For Exception.throw
 *   - Original type: {error: 'error} -> 'a
 *   - Instantiated type: {error: $tv1} -> $tv2
 *   - Effect declaration has associatedTypes = ["error"]
 *   - We match 'error to $tv1 by finding it in the same structural position
 *   - Creates EffectInstance("Exception", {"error" -> VarType($tv1)})
 */
internal fun ExpressionConstraintGenerator.createEffectInstanceFromInstantiated(
    effectName: String,
    originalType: MezzeType,
    instantiatedType: MezzeType,
    context: ConstraintContext
): MezzeType.EffectInstance {
    // Get the effect declaration to find associated type names
    // Note: Caller already checked that effectDecl exists and has associated types
    val effectDecl = context.effectDeclarations[effectName]
        ?: return MezzeType.EffectInstance(effectName, emptyMap())

    if (effectDecl.associatedTypes.isEmpty()) {
        return MezzeType.EffectInstance(effectName, emptyMap())
    }

    // OPTIMIZATION: Build TypeVar mapping only once using cache
    val typeVarMapping = buildTypeVarMapping(originalType, instantiatedType)

    // For each associated type, find the original TypeVar and map it to the instantiated one
    // OPTIMIZATION: Use sized map to avoid rehashing
    val associatedTypeBindings = HashMap<String, MezzeType>(effectDecl.associatedTypes.size)

    if (originalType is MezzeType.FunctionType) {
        // OPTIMIZATION: Instead of collecting all TypeVars into a Set first,
        // search directly for each associated type name
        for (assocTypeName in effectDecl.associatedTypes) {
            val normalizedName = assocTypeName.removePrefix("'")

            // Find the TypeVar by name directly in the mapping keys
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

/**
 * Build a mapping from TypeVars in the original type to TypeVars in the instantiated type.
 * This works by structural matching - we walk both types in parallel and collect the mappings.
 * Uses caching to avoid repeated traversal for the same type pairs.
 */
internal fun ExpressionConstraintGenerator.buildTypeVarMapping(original: MezzeType, instantiated: MezzeType): Map<TypeVar, TypeVar> {
    // Use identity hash codes for cache key (fast, works for same object references)
    val cacheKey = System.identityHashCode(original) to System.identityHashCode(instantiated)

    // Check cache first
    typeVarMappingCache[cacheKey]?.let { return it }

    // Cache miss - compute the mapping
    val mapping = mutableMapOf<TypeVar, TypeVar>()
    buildTypeVarMappingHelper(original, instantiated, mapping)

    // Store in cache with size management
    if (typeVarMappingCache.size >= maxCacheSize) {
        // Clear half the cache when full (simple LRU approximation)
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
        // Add more cases as needed...
    }
}
/**
 * Try to generate a performer method call (e.g., BasicLogger.info).
 *
 * Performers are concrete implementations of effects. When accessing a method
 * on a performer, we look up the corresponding effect's operation type.
 * The call still produces the effect (it's not handled by the performer itself).
 *
 * @return ConstraintResult if this is a valid performer method call, null otherwise
 */
internal fun ExpressionConstraintGenerator.tryGeneratePerformerMethodCall(
    expr: ParsedMemberAccess,
    performerName: String,
    effectName: String,
    effectBinding: TypingContext.Binding,
    context: ConstraintContext
): ConstraintResult? {
    // Get effect type from binding
    val effectType = when (effectBinding) {
        is TypingContext.Binding.Monomorphic -> effectBinding.type
        is TypingContext.Binding.Polymorphic -> {
            effectBinding.scheme.instantiate().type
        }
    }

    // Effect should be stored as RecordType with operations as fields
    if (effectType !is MezzeType.RecordType) {
        return null
    }

    // Look up operation in effect
    val operationName = expr.member
    val operationType = effectType.fields[operationName] ?: return null

    // Get the performer's associated type bindings (if any) using dedicated map
    var instantiatedOperationType = operationType
    val performerInfos = context.typingContext.getPerformerTypeInfosByName(performerName)
    if (performerInfos.isNotEmpty()) {
        val performerInfo = performerInfos.first()
        val performerType = performerInfo.associatedTypes

        if (performerType is MezzeType.RecordType) {
            // Substitute associated types in the operation type
            instantiatedOperationType = substituteAssociatedTypesInType(
                instantiatedOperationType,
                performerType.fields
            )
        }
    }

    // Instantiate with fresh vars
    instantiatedOperationType = instantiateTypeWithFreshVars(instantiatedOperationType)

    // Return the function type with the effect tracked
    // Performer method calls still produce effects - they need to be handled by perform
    return ConstraintResult(
        type = instantiatedOperationType,
        constraints = emptyList(),
        effects = setOf(effectName),
        undefinedVariables = emptyList()
    )
}

/**
 * Substitute associated type variables in a type with concrete types.
 * Used for effect operations with associated types.
 *
 * Example: If operation type is `{value: 'state} -> 'state <State>`
 *          and bindings is `{'state -> Int}`
 *          Result is `{value: Int} -> Int <State>`
 */
internal fun ExpressionConstraintGenerator.substituteAssociatedTypesInType(
    type: MezzeType,
    associatedTypeBindings: Map<String, MezzeType>
): MezzeType {
    return when (type) {
        is MezzeType.VarType -> {
            // CRITICAL: Associated type variables are represented as VarType with name like ''env'
            // The performer bindings map has keys like "env" (without ')
            // So we need to check both with and without the ' prefix
            val varName = type.variable.name
            // Try looking up with name as-is, then without ' prefix
            val keyWithoutQuote = varName.removePrefix("'")
            associatedTypeBindings[varName] ?: associatedTypeBindings[keyWithoutQuote] ?: type
        }
        is MezzeType.TypeApplication -> {
            // Check if this is an associated type reference (e.g., 'state)
            if (type.args.isEmpty() && type.constructor.startsWith("'")) {
                // This is an associated type variable - substitute it
                associatedTypeBindings[type.constructor] ?: type
            } else {
                // Regular type application - recurse into args
                com.mezze.language.typechecker.util.WellKnownTypeApp.of(
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
            nominalWall = type.nominalWall
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
        is MezzeType.SuspendedType -> MezzeType.SuspendedType(
            valueType = substituteAssociatedTypesInType(type.valueType, associatedTypeBindings),
            effects = type.effects
        )
        is MezzeType.NewtypeType -> MezzeType.NewtypeType(
            type.name,
            type.constructor,
            substituteAssociatedTypesInType(type.wrappedType, associatedTypeBindings)
        )
        // Primitive types and VarType unchanged
        is MezzeType.VarType, is MezzeType.IntType, is MezzeType.FloatType, is MezzeType.StringType,
        is MezzeType.BoolType, is MezzeType.NatType, is MezzeType.FracType,
        is MezzeType.OrderingType, is MezzeType.BytesType, is MezzeType.ByteBufferType,
        is MezzeType.CharType, is MezzeType.EffectRow -> type
    }
}

/**
 * Instantiate a type with fresh type variables.
 * Replaces all type variables with fresh ones.
 * CRITICAL: Preserves variable names for associated types (names starting with ')
 */
