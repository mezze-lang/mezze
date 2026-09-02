package com.mezze.language.typechecker.constraints

import com.mezze.language.ast.*
import com.mezze.language.parsedast.*
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.typechecker.ErrorType
import com.mezze.language.typechecker.debug.DebugLogger
import com.mezze.language.typechecker.debug.DebugLogger.Category
import com.mezze.language.parsedast.diagnostics.ErrorCode

internal fun ExpressionConstraintGenerator.generatePerform(expr: ParsedPerform, context: ConstraintContext): ConstraintResult {
    val contextWithPerform = context.copy(performContext = expr)
    val exprResult = generate(expr.body, contextWithPerform)

    val handlerEffects = mutableListOf<Pair<String, String>>()
    for (handler in expr.handlers) {
        val handlerName = handler.performer.localName
        val effectName = context.performerToEffect[handlerName]
        if (effectName != null) {
            handlerEffects.add(handlerName to effectName)
        } else {
            val handlerLookup = context.typingContext.lookup(handlerName)
            val performerInfos = context.typingContext.getPerformerTypeInfosByName(handlerName)

            if (handlerLookup is TypingContext.LookupResult.Found || performerInfos.isNotEmpty()) {
            } else {
                throw TypeCheckError(
                    message = "Unknown performer '${handlerName}'. Performer has not been defined with 'impl Effect for ${handlerName}'.",
                    loc = expr.loc,
                    errorType = ErrorType.UNDEFINED_VARIABLE,
                    code = ErrorCode.V0702_UNDEFINED_VARIABLE,
                    hints = listOf(
                        "declare a performer: `impl SomeEffect for $handlerName { ... }`",
                        "or import it from the module that defines it",
                    ),
                )
            }
        }
    }
    val handledEffects = handlerEffects.map { it.second }.toSet()

    val effectCounts = handlerEffects.groupBy { it.second }
    for ((effect, handlers) in effectCounts) {
        if (handlers.size > 1) {
            val handlerNames = handlers.map { it.first }.joinToString(", ")
            throw TypeCheckError(
                message = "Multiple handlers for the same effect '$effect': $handlerNames. Each effect can only be handled by one performer.",
                loc = expr.loc,
                errorType = ErrorType.CONSTRAINT_VIOLATION,
                code = ErrorCode.V0507_HANDLER_DUPLICATE,
                hints = listOf("keep only one handler for `$effect` in this `perform` block"),
            )
        }
    }

    val isNestedPerform = expr.body is ParsedPerform
    val extractedEffects = extractEffectsFromType(exprResult.type)
    val allEffects = exprResult.effects + extractedEffects.effects
    val hasPolymorphicEffects = extractedEffects.isPolymorphic
    val hasPerformedMarker = exprResult.performed || hasPerformedMarkerInType(exprResult.type)
    val isPureWithPerformed = allEffects.isEmpty() && hasPerformedMarker
    val canValidateNestedPerform = isNestedPerform && (extractedEffects.effects.isNotEmpty() || isPureWithPerformed)

    val hasUndefinedVariables = exprResult.undefinedVariables.isNotEmpty()
    val bodyHasNativeCall = bodyContainsNativeCall(expr.body)
    val shouldValidate = (!isNestedPerform || canValidateNestedPerform) && !hasPolymorphicEffects && !hasUndefinedVariables && !bodyHasNativeCall

    // V0510: installing a handler for an effect the body doesn't perform
    // is a bug. Spawn is now effect-transparent (`<...>` on both sides), so
    // effects from spawned children DO surface in the parent's row —
    // meaning we can honestly check "is this handler's effect actually in
    // the body's row?" without false positives around spawn.
    //
    // Skip when:
    //   - body's effect row is polymorphic (a row var could pick anything)
    //   - body has native call (opaque)
    //   - body has undefined vars (constraint gen incomplete)
    //   - a nested perform whose inner body isn't yet analyzed
    // V0510: installing a handler for an effect the body doesn't perform
    // is a bug. Fire only when we can be CONFIDENT the handler is dead:
    // the body's inferred row is empty (no effects at all) AND no
    // performerAddedEffects tell us the handler chain would transitively
    // require it. This rejects `perform Console in 1 + 2` cleanly while
    // staying permissive around code that calls other functions or
    // handlers whose transitive effect chain we can't fully see.
    if (shouldValidate && !isNestedPerform && allEffects.isEmpty()) {
        val transitivelyNeeded = expr.handlers
            .flatMap { context.performerAddedEffects[it.performer.localName] ?: emptySet() }
            .toSet()
        val effectivelyPerformed = allEffects + transitivelyNeeded

        for ((handlerName, effectName) in handlerEffects) {
            if (effectName !in effectivelyPerformed) {
                throw TypeCheckError(
                    message = "Handler '$handlerName' handles '$effectName', but the `perform` " +
                        "body performs no effect — so the handler can never fire.",
                    loc = expr.loc,
                    errorType = ErrorType.CONSTRAINT_VIOLATION,
                    code = ErrorCode.V0510_HANDLER_UNNECESSARY,
                    hints = listOf(
                        "`perform` only handles effects performed INSIDE its body — wrap the call " +
                            "that performs '$effectName' in it: `perform $handlerName in <that call>`.",
                        "if you bound the call to a variable first (`let x = <call>`), its effect " +
                            "already rose to the enclosing function; move the call into the `perform` " +
                            "body, or wrap the enclosing block: `perform $handlerName in do …`.",
                        "to defer handling, make it a thunk and force it in the perform: " +
                            "`let x = {} -> <call>` then `perform $handlerName in x {}`.",
                        "if you meant to handle a different effect, change the handler.",
                    ),
                )
            }
        }
    }

    DebugLogger.debugSection(Category.ExpressionConstraints, "generatePerform") {
        listOf(
            "handlers: ${expr.handlers.map { it.performer.localName }}",
            "performerToEffect map: ${context.performerToEffect}",
            "performerAddedEffects map: ${context.performerAddedEffects}",
            "handledEffects: $handledEffects",
            "exprResult.type: ${exprResult.type}",
            "exprResult.effects: ${exprResult.effects}"
        )
    }

    val continuationConstraints = mutableListOf<Constraint>()
    var performResultType: MezzeType = exprResult.type

    for (handler in expr.handlers) {
        val performerInfos = context.typingContext.getPerformerTypeInfosByName(handler.performer.localName)
        val performerInfo = performerInfos.firstOrNull() ?: continue

        val handlerParams = handler.params
        if (handlerParams != null) {
            val performerFields = performerInfo.performerFields
            if (performerFields is MezzeType.RecordType) {
                val actualParamsResult = generate(handlerParams, context)
                val actualParamsType = actualParamsResult.type

                continuationConstraints.add(
                    Constraint.Equality(
                        left = actualParamsType,
                        right = performerFields,
                        context = "Handler parameters must match performer field types",
                        loc = handlerParams.loc
                    )
                )
            }
        }

        val suspensionType = performerInfo.suspensionType
        if (suspensionType != null) {
            val effectName = performerInfo.effectName
            val effectNamePath = ModulePath.parse(effectName)
            val effectInstance = exprResult.effectInstances.find { instance ->
                val instancePath = ModulePath.parse(instance.name)
                instancePath == effectNamePath || instancePath.endsWith(effectNamePath)
            }

            val performerAssocTypes = performerInfo.associatedTypes as? MezzeType.RecordType
            val concreteBindings: Map<String, MezzeType> = if (effectInstance != null && effectInstance.associatedTypes.isNotEmpty() && performerAssocTypes != null) {
                val result = mutableMapOf<String, MezzeType>()
                for ((assocTypeName, concreteType) in effectInstance.associatedTypes) {
                    val performerBinding = performerAssocTypes.fields[assocTypeName]
                    if (performerBinding is MezzeType.VarType) {
                        result[performerBinding.variable.name.removePrefix("'")] = concreteType
                    } else {
                        result[assocTypeName] = concreteType
                    }
                }
                result
            } else if (performerAssocTypes != null) {
                performerAssocTypes.fields
            } else {
                emptyMap()
            }

            val resolvedSuspensionType = if (concreteBindings.isNotEmpty()) {
                substituteAssociatedTypesInType(suspensionType, concreteBindings)
            } else {
                suspensionType
            }

            DebugLogger.debugSection(Category.ExpressionConstraints, "suspension type inference") {
                listOf(
                    "handler: ${handler.performer.localName}",
                    "effectName: $effectName",
                    "effectInstance: $effectInstance",
                    "suspensionType: $suspensionType",
                    "concreteBindings: $concreteBindings",
                    "resolvedSuspensionType: $resolvedSuspensionType",
                    "performResultType: $performResultType"
                )
            }

            continuationConstraints.add(
                Constraint.Equality(
                    left = performResultType,
                    right = resolvedSuspensionType,
                    context = "perform body must return the same type that handler '${handler.performer.localName}' produces (e.g., wrap with Ok/Some)",
                    loc = expr.loc
                )
            )

            performResultType = resolvedSuspensionType

            // A `perform` that discharges an effect into a Result/Option channel
            // (e.g. ExceptionToResult routes a thrown `'error` into `Err {error}`)
            // fixes the associated type of EVERY value in the body carrying that
            // effect — not just the tail. But it must only PIN the error channel,
            // never coerce a mid-block `let x = op {}` whose value is a plain `Int`.
            // So emit a best-effort DefaultAssociatedType per such binding: it fires
            // ONLY post-solve, and ONLY when the binding solved to the SAME
            // constructor as the handler's suspension shape (freshened so its own
            // vars are throwaways). A bare `Int` binding, or an already-concrete
            // error slot, is left untouched. This gives a mid-block
            // `let bad = safe_div{...}.ok{}` the `Result Int Str` its returned
            // sibling `good` already gets, closing the "associated type inferred but
            // not USED at the binding" leak.
            if (concreteBindings.isNotEmpty()) {
                for (bindingEffect in exprResult.doBindingEffects) {
                    if (effectName in bindingEffect.effectNames) {
                        continuationConstraints.add(
                            Constraint.DefaultAssociatedType(
                                bindingType = bindingEffect.bindingType,
                                shape = instantiateTypeWithFreshVars(resolvedSuspensionType),
                                context = "value performing '$effectName' takes the associated " +
                                    "type that handler '${handler.performer.localName}' fixes",
                                loc = expr.loc
                            )
                        )
                    }
                }
            }
        }
    }

    val rawConstraints = exprResult.constraints + continuationConstraints
    // Option B / #376: stamp handledEffects onto every body Equality so
    // strictEffectRows can distinguish "in-flight unification inside a
    // perform body that discharges this effect at its boundary" from
    // "real leak with no discharger in scope". Accumulate onto any
    // effects already stamped by an enclosing perform (nested case).
    val allConstraints = if (handledEffects.isEmpty()) {
        rawConstraints
    } else {
        rawConstraints.map { c ->
            if (c is Constraint.Equality) {
                c.copy(activeDischargedEffects = c.activeDischargedEffects + handledEffects)
            } else {
                c
            }
        }
    }

    val baseType = if (continuationConstraints.isNotEmpty()) {
        performResultType
    } else {
        exprResult.type
    }
    val resultType = removeEffectsFromType(baseType, handledEffects)

    val addedEffects = handlerEffects.flatMap { (handlerName, _) ->
        context.performerAddedEffects[handlerName] ?: emptySet()
    }.toSet()
    val remainingEffects = (exprResult.effects - handledEffects) + (addedEffects - handledEffects)

    DebugLogger.debugSection(Category.ExpressionConstraints, "generatePerform result") {
        listOf(
            "resultType after removal: $resultType",
            "baseType: $baseType",
            "continuationConstraints count: ${continuationConstraints.size}",
            "addedEffects: $addedEffects",
            "remainingEffects: $remainingEffects"
        )
    }

    val remainingEffectInstances = exprResult.effectInstances.filter { it.name !in handledEffects }.toSet()
    // Effect-model migration (S2): the runtime's "fully discharges body's row"
    // signal is about the RESULT VALUE's residual effects, not the body's performed
    // effects. removeEffectsFromType (which produced `resultType`) returns a bare
    // value type when nothing remains on the value and a SuspendType otherwise, so
    // `resultType !is SuspendType` is EXACTLY the old check on the perform's result
    // type (and SuspendType-ness is substitution-invariant — a SuspendType built here
    // always carries non-empty named effects). Threaded to emit so the compiler no
    // longer inspects the value-carried effect row. (A `.effects`-based residual is
    // the wrong question — it over-reports side-effects that never rode the value.)
    val fullyDischargedHere = resultType !is MezzeType.SuspendType

    return ConstraintResult(
        type = resultType,
        constraints = allConstraints,
        effects = remainingEffects,
        effectInstances = remainingEffectInstances,
        // #256: a handler for specific NAMED effects does not discharge a
        // polymorphic tail `<r>` (it could be instantiated with effects the
        // handler doesn't cover), so carry the row var through unchanged. Sound
        // over-approximation: may keep an open tail that's actually closed, never
        // the reverse.
        effectRowVars = exprResult.effectRowVars,
        performed = true,
        abilityConstraints = exprResult.abilityConstraints,
        associatedTypeConstraints = exprResult.associatedTypeConstraints,
        undefinedVariables = exprResult.undefinedVariables,
        usedVariables = exprResult.usedVariables,
        referencedBindings = exprResult.referencedBindings,
        methodCallResolutions = exprResult.methodCallResolutions,
        callReturnTypes = exprResult.callReturnTypes + (expr.loc to resultType),
        // Carry the body's nested-perform entries and add this perform's. Mirrors
        // callReturnTypes: generatePerform encapsulates body generation, so body
        // side-tables must be threaded through its return or they are lost.
        performFullyDischarged = exprResult.performFullyDischarged + (expr.loc to fullyDischargedHere),
    )
}

internal data class ExtractedEffects(
    val effects: Set<String>,
    val isPolymorphic: Boolean  // true if there's a row variable (<...>)
)

internal fun ExpressionConstraintGenerator.extractEffectsFromType(type: MezzeType): ExtractedEffects {
    return when (type) {
        is MezzeType.FunctionType -> {
            val effectRow = type.effects
            if (effectRow != null) {
                // #404: an effect carried purely as an INSTANCE (e.g. `Exception
                // having 'error = Str` when the simple-name slot has been normalized
                // away by `withEffectInstance`) lives in `effectInstances`, not
                // `effects`. Reading only `effects` would launder such an effect out
                // of the perform-body check. `allEffectNames` unions simple + instance
                // names so an instanced effect is never invisible.
                ExtractedEffects(effectRow.allEffectNames, effectRow.rowVar != null)
            } else {
                ExtractedEffects(emptySet(), false)
            }
        }
        is MezzeType.SuspendType -> {
            ExtractedEffects(type.effects.allEffectNames, type.effects.rowVar != null)
        }
        is MezzeType.VarType,
        is MezzeType.RecordType,
        is MezzeType.VariantType,
        is MezzeType.TypeApplication,
        is MezzeType.SeqType,
        is MezzeType.ListType,
        is MezzeType.SetType,
        is MezzeType.SortedSetType,
        is MezzeType.MapType,
        is MezzeType.SortedMapType,
        is MezzeType.EffectRow,
        is MezzeType.IntType,
        is MezzeType.FloatType,
        is MezzeType.StringType,
        is MezzeType.BoolType,
        is MezzeType.NatType,
        is MezzeType.FracType,
        is MezzeType.CharType,
        is MezzeType.BytesType,
        is MezzeType.NewtypeType -> ExtractedEffects(emptySet(), false)
    }
}

internal fun ExpressionConstraintGenerator.hasPerformedMarkerInType(type: MezzeType): Boolean {
    return when (type) {
        is MezzeType.FunctionType -> type.effects?.performed == true
        is MezzeType.SuspendType -> type.effects.performed
        is MezzeType.VarType,
        is MezzeType.RecordType,
        is MezzeType.VariantType,
        is MezzeType.TypeApplication,
        is MezzeType.SeqType,
        is MezzeType.ListType,
        is MezzeType.SetType,
        is MezzeType.SortedSetType,
        is MezzeType.MapType,
        is MezzeType.SortedMapType,
        is MezzeType.EffectRow,
        is MezzeType.IntType,
        is MezzeType.FloatType,
        is MezzeType.StringType,
        is MezzeType.BoolType,
        is MezzeType.NatType,
        is MezzeType.FracType,
        is MezzeType.CharType,
        is MezzeType.BytesType,
        is MezzeType.NewtypeType -> false
    }
}

internal fun ExpressionConstraintGenerator.wrapCallResultWithEffects(
    valueType: MezzeType,
    effectRow: MezzeType.EffectRow?,
): Triple<MezzeType, Set<String>, Set<MezzeType.EffectInstance>> {
    if (effectRow == null) return Triple(valueType, emptySet(), emptySet())
    // Effect-model migration (S2): return the BARE value type; effects ride on the
    // enclosing arrow via the caller's collector. No SuspendType wrap. (Duplicate of
    // the ExpressionConstraints copy.)
    return Triple(valueType, effectRow.effects, effectRow.effectInstances)
}

internal fun ExpressionConstraintGenerator.removeEffectsFromType(
    type: MezzeType,
    effectsToRemove: Set<String>,
): MezzeType {
    return when (type) {
        is MezzeType.FunctionType -> {
            val effects = type.effects
            if (effects == null) {
                MezzeType.FunctionType(
                    paramType = type.paramType,
                    returnType = type.returnType,
                    effects = MezzeType.EffectRow.simple(emptySet(), null, performed = true),
                    abilityConstraints = type.abilityConstraints
                )
            } else {
                val remainingEffects = effects.effects - effectsToRemove
                MezzeType.FunctionType(
                    paramType = type.paramType,
                    returnType = type.returnType,
                    effects = MezzeType.EffectRow.simple(remainingEffects, effects.rowVar, performed = true),
                    abilityConstraints = type.abilityConstraints
                )
            }
        }
        is MezzeType.SuspendType -> {
            val remainingEffects = type.effects.effects - effectsToRemove
            val remainingInstances = type.effects.effectInstances.filter { it.name !in effectsToRemove }.toSet()
            if (remainingEffects.isEmpty() && remainingInstances.isEmpty()) {
                type.valueType
            } else {
                MezzeType.SuspendType(
                    valueType = type.valueType,
                    effects = MezzeType.EffectRow(
                        effects = remainingEffects,
                        effectInstances = remainingInstances,
                        rowVar = type.effects.rowVar,
                        performed = true
                    )
                )
            }
        }
        else -> type
    }
}

internal fun ExpressionConstraintGenerator.generateEffectCall(expr: ParsedEffectCall, context: ConstraintContext): ConstraintResult {
    val effectName = expr.operation.qualifier?.segments?.joinToString("::") ?: ""
    val operationName = expr.operation.localName

    val effectType = context.typingContext.getEffectOperationType(effectName)
    if (effectType == null) {
        return ConstraintResult(
            type = MezzeType.VarType(TypeVar.fresh()),
            constraints = emptyList(),
            effects = setOf(effectName),
            undefinedVariables = listOf(ConstraintResult.UndefinedVariable(effectName, expr.loc))
        )
    }

    if (effectType !is MezzeType.RecordType) {
        throw IllegalStateException("Effect '$effectName' is not properly registered as RecordType")
    }

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
    DebugLogger.debug(Category.Effects) { "generateEffectCall: effectName=$effectName, operationName=$operationName, performContext=${performContext != null}, isFunctionType=${instantiatedOperationType is MezzeType.FunctionType}" }
    if (performContext != null && instantiatedOperationType is MezzeType.FunctionType) {
        DebugLogger.debug(Category.Effects) { "generateEffectCall: handlers=${performContext.handlers.map { it.performer.localName }}, performerToEffect=${context.performerToEffect}" }
        var performerName: String? = null
        for (handler in performContext.handlers) {
            val handlerEffectName = context.performerToEffect[handler.performer.localName]
            DebugLogger.debug(Category.Effects) { "generateEffectCall: handler=${handler.performer.localName}, handlerEffectName=$handlerEffectName" }
            if (handlerEffectName == effectName) {
                performerName = handler.performer.localName
                break
            }
        }

        if (performerName != null) {
            val performerInfos = context.typingContext.getPerformerTypeInfosByName(performerName)
            DebugLogger.debug(Category.Effects) { "generateEffectCall: performerName=$performerName, performerInfos.size=${performerInfos.size}, effectName=$effectName" }
            if (performerInfos.isNotEmpty()) {
                val performerInfo = performerInfos.first()
                val performerType = performerInfo.associatedTypes
                DebugLogger.debug(Category.Effects) { "generateEffectCall: performerType=$performerType" }
                if (performerType is MezzeType.RecordType) {
                    DebugLogger.debug(Category.Effects) { "generateEffectCall: fields=${performerType.fields}" }
                    instantiatedOperationType = substituteAssociatedTypesInType(
                        instantiatedOperationType,
                        performerType.fields
                    )
                    DebugLogger.debug(Category.Effects) { "generateEffectCall: after substitution=$instantiatedOperationType" }

                    val performerTypeVarNames = performerType.fields.values
                        .filterIsInstance<MezzeType.VarType>()
                        .map { it.variable.name }
                    instantiatedOperationType = instantiateTypeWithFreshVarsPreservingPerformerBindings(
                        instantiatedOperationType,
                        performerTypeVarNames
                    )
                }
            }
        }
    }

    if (performContext == null || performContext.handlers.none { context.performerToEffect[it.performer.localName] == effectName }) {
        instantiatedOperationType = instantiateTypeWithFreshVars(instantiatedOperationType)
    }

    val argResult = generate(expr.arguments, context)

    val (resultType, additionalConstraints) = if (instantiatedOperationType is MezzeType.FunctionType) {
        val paramConstraint = Constraint.Equality(
            left = argResult.type,
            right = instantiatedOperationType.paramType,
            context = "Effect operation argument type",
            loc = expr.loc
        )
        instantiatedOperationType.returnType to listOf(paramConstraint)
    } else {
        instantiatedOperationType to emptyList()
    }

    val effectDecl = context.effectDeclarations[effectName]
    DebugLogger.debug(Category.Effects) { "generateEffectCall: creating effectInstance, effectDecl=$effectDecl, assocTypes=${effectDecl?.associatedTypes}, opType=$instantiatedOperationType" }
    val effectInstance = if (effectDecl != null && effectDecl.associatedTypes.isNotEmpty() && instantiatedOperationType is MezzeType.FunctionType) {
        val paramType = instantiatedOperationType.paramType
        val associatedTypeBindings = mutableMapOf<String, MezzeType>()

        DebugLogger.debug(Category.Effects) { "generateEffectCall: paramType=$paramType, argResult.type=${argResult.type}" }
        if (paramType is MezzeType.RecordType && argResult.type is MezzeType.RecordType) {
            val argRecordType = argResult.type as MezzeType.RecordType
            for (assocTypeName in effectDecl.associatedTypes) {
                val normalizedName = assocTypeName.removePrefix("'")
                val fieldType = argRecordType.fields[normalizedName]
                DebugLogger.debug(Category.Effects) { "generateEffectCall: assocTypeName=$assocTypeName, normalizedName=$normalizedName, fieldType=$fieldType" }
                if (fieldType != null) {
                    associatedTypeBindings[normalizedName] = fieldType
                }
            }
        }

        if (associatedTypeBindings.isNotEmpty()) {
            DebugLogger.debug(Category.Effects) { "generateEffectCall: creating EffectInstance($effectName, $associatedTypeBindings)" }
            MezzeType.EffectInstance(effectName, associatedTypeBindings)
        } else {
            DebugLogger.debug(Category.Effects) { "generateEffectCall: no associatedTypeBindings, effectInstance=null" }
            null
        }
    } else {
        DebugLogger.debug(Category.Effects) { "generateEffectCall: skipping effectInstance creation" }
        null
    }

    val collector = ConstraintCollector()
    collector.merge(argResult)
    collector.constraints.addAll(additionalConstraints)
    collector.effects.add(effectName)
    if (effectInstance != null) collector.effectInstances.add(effectInstance)

    return collector.toResult(resultType)
}

