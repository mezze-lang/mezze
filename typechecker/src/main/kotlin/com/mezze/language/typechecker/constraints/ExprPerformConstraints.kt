package com.mezze.language.typechecker.constraints

import com.mezze.language.ast.*
import com.mezze.language.parsedast.*
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.typechecker.ErrorType
import com.mezze.language.typechecker.debug.DebugLogger
import com.mezze.language.typechecker.debug.DebugLogger.Category
import com.mezze.language.parsedast.diagnostics.ErrorCode

/**
 * Perform-block and effect-call constraint generators.
 *
 * Extension functions on [ExpressionConstraintGenerator].
 */

// ========================================================================
// EFFECTS
// ========================================================================

internal fun ExpressionConstraintGenerator.generatePerform(expr: ParsedPerform, context: ConstraintContext): ConstraintResult {
    // perform Handler in expr
    // The handler handles a specific effect, removing it from the expression's effects

    // CRITICAL: Pass the perform context to child expressions
    // This allows effect operations to substitute associated types from the performer
    // Matches OldTypeChecker.kt lines 7353-7354
    val contextWithPerform = context.copy(performContext = expr)
    val exprResult = generate(expr.body, contextWithPerform)

    // Map each handler name to the effect it handles
    // VALIDATION: Check that all handlers are defined performers
    // Resolve performer→effect via hash-based lookup (primary), with context fallback.
    val handlerEffects = mutableListOf<Pair<String, String>>()
    for (handler in expr.handlers) {
        val handlerName = handler.performer.localName
        val effectName = context.performerToEffect[handlerName]
        if (effectName != null) {
            handlerEffects.add(handlerName to effectName)
        } else {
            // Handler not resolved via hash or name — check if it exists in context
            // Performers can be imported from modules where they might not be tracked
            val handlerLookup = context.typingContext.lookup(handlerName)
            val performerInfos = context.typingContext.getPerformerTypeInfosByName(handlerName)

            if (handlerLookup is TypingContext.LookupResult.Found || performerInfos.isNotEmpty()) {
                // Performer exists in context (imported), allow it but we can't track effect
                // Skip adding to handlerEffects - imported performers are permissive
            } else {
                // Not resolvable and not in context - truly unknown
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

    // VALIDATION 1: Check for duplicate handlers for the same effect
    val effectCounts = handlerEffects.groupBy { it.second }
    for ((effect, handlers) in effectCounts) {
        if (handlers.size > 1) {
            val handlerNames = handlers.map { it.first }.joinToString(", ")
            throw TypeCheckError(
                message = "Multiple handlers for the same effect '$effect': $handlerNames. Each effect can only be handled by one performer.",
                loc = expr.loc,
                errorType = ErrorType.CONSTRAINT_VIOLATION,
                code = com.mezze.language.parsedast.diagnostics.ErrorCode.V0507_HANDLER_DUPLICATE,
                hints = listOf("keep only one handler for `$effect` in this `perform` block"),
            )
        }
    }

    // VALIDATION 2: Check that handlers are meaningful
    // Skip validation for nested performs in certain cases:
    // - Inner performs may have handlers that PRODUCE effects (e.g., FileLogger adds FileSystem)
    //   which need to be handled by outer performs. We can't track handler-added effects statically.
    // However, we CAN validate when effects are embedded in the result TYPE (e.g., SuspendedType)
    val isNestedPerform = expr.body is ParsedPerform

    // Extract effects from the expression's result TYPE (for SuspendedType, FunctionType with effects)
    val extractedEffects = extractEffectsFromType(exprResult.type)
    val allEffects = exprResult.effects + extractedEffects.effects
    val hasPolymorphicEffects = extractedEffects.isPolymorphic

    // Check if the inner expression has the performed marker but no effects
    // This means all effects were already handled, so adding more handlers is an error
    val hasPerformedMarker = exprResult.performed || hasPerformedMarkerInType(exprResult.type)
    val isPureWithPerformed = allEffects.isEmpty() && hasPerformedMarker

    // For nested performs:
    // - If we have effects in the type, validate against those
    // - If result is pure with <*>, validate (no effects to handle)
    // - Otherwise skip validation (handler may add effects we can't see yet)
    // NOTE: When a handler adds effects, those effects should appear in the result type.
    //       If the result is truly pure with <*>, the handler didn't add any effects.
    val canValidateNestedPerform = isNestedPerform && (extractedEffects.effects.isNotEmpty() || isPureWithPerformed)

    // CRITICAL: We validate based on effects, not types
    // If effects are empty, the expression is pure - we can validate (unless nested perform with no <*>)
    // If effects are non-empty, we know what effects the expression uses - we can validate
    // ALSO skip validation if effects are polymorphic (<...>) - they could match any handler
    //
    // ALSO CRITICAL: Skip validation if there are undefined variables!
    // If the expression contains undefined variables, we can't properly validate effects yet
    // because we don't know if those variables are effectful functions or not.
    // The undefined variables will be caught later in TypeChecker.checkProgram() at line 242.
    val hasUndefinedVariables = exprResult.undefinedVariables.isNotEmpty()
    val shouldValidate = (!isNestedPerform || canValidateNestedPerform) && !hasPolymorphicEffects && !hasUndefinedVariables
    if (shouldValidate) {
        // If the expression has no unhandled effects matching the handlers
        // provided, providing those handlers is meaningless. This happens
        // either because the body is genuinely pure, or because the effect
        // was already handled by an inner `perform` (Mezze allows only one
        // handler per effect in scope at a time — nest using records or
        // separate effects if you need multiple carriers).
        if (handlerEffects.isNotEmpty() && allEffects.isEmpty()) {
            val pairs = handlerEffects.joinToString(", ") { (handler, effect) ->
                "`$handler` (handles `$effect`)"
            }
            val effectList = handlerEffects.map { it.second }.distinct().joinToString(", ") { "`$it`" }
            throw TypeCheckError(
                message = "This `perform` provides $pairs, but the body has no unhandled $effectList effect. " +
                    "Either the body is pure, or that effect was already handled by an inner `perform`.",
                loc = expr.loc,
                errorType = ErrorType.CONSTRAINT_VIOLATION,
                code = com.mezze.language.parsedast.diagnostics.ErrorCode.V0508_HANDLER_ON_PURE_EXPR,
                hints = listOf(
                    "drop this `perform` wrapper if the body really has no effect to handle",
                    "if you intended to override an inner handler, restructure so only one `perform` handles the effect — Mezze only allows one handler per effect in scope at a time",
                ),
            )
        }

        if (allEffects.isNotEmpty() && handlerEffects.isNotEmpty()) {
            // Check if at least one handler handles an effect the expression actually uses
            // This is the "entry point" handler - the rightmost handler that touches the expression first
            val handlersHandleUsedEffects = handlerEffects.any { (_, effectName) ->
                effectName in allEffects
            }
            if (!handlersHandleUsedEffects) {
                // None of the handlers handle effects that the expression uses
                val handlersList = handlerEffects.map { (name, effect) -> "$name (handles $effect)" }.joinToString(", ")
                throw TypeCheckError(
                    message = "None of the provided handlers handle effects used by the expression. " +
                        "Expression uses: ${allEffects.joinToString(", ")}. " +
                        "Handlers provided: $handlersList",
                    loc = expr.loc,
                    errorType = ErrorType.CONSTRAINT_VIOLATION,
                    code = com.mezze.language.parsedast.diagnostics.ErrorCode.V0509_HANDLER_EFFECT_MISMATCH,
                    hints = listOf(
                        "add a handler for one of: ${allEffects.joinToString(", ")}",
                        "or remove handlers that don't match",
                    ),
                )
            }

            // VALIDATION 3: Check that EACH handler handles an effect present in the expression
            // ONLY when there's a single handler. With multiple handlers, handlers can add effects
            // that other handlers handle (rightmost/innermost handler applies first and may add effects).
            // Example: perform HttpClient, DiskFS, CompositeLogger in logMsg
            //   - CompositeLogger handles Log, adds FileSystem+Network
            //   - DiskFS handles FileSystem
            //   - HttpClient handles Network
            if (handlerEffects.size == 1) {
                val (handlerName, effectName) = handlerEffects[0]
                if (effectName !in allEffects) {
                    throw TypeCheckError(
                        message = "Handler '$handlerName' handles effect '$effectName', but the expression doesn't use this effect. " +
                            "Expression uses: ${allEffects.joinToString(", ")}. " +
                            "Remove the unnecessary handler.",
                        loc = expr.loc,
                        errorType = ErrorType.CONSTRAINT_VIOLATION,
                        code = com.mezze.language.parsedast.diagnostics.ErrorCode.V0510_HANDLER_UNNECESSARY,
                        hints = listOf("remove the handler — no matching effect is performed"),
                    )
                }
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

    // =================================================================
    // SUSPENSION TYPE INFERENCE
    // =================================================================
    // For handlers that use continuations with suspend_with, use the statically
    // analyzed suspension type from PerformerTypeInfo to determine the perform result.
    //
    // Example: ToResult { cont: Continuation 'a 'b 'c }
    //   The impl body has: throw = {error} -> cont.suspend_with {value = Err {error}}
    //   The suspension type is analyzed as: Result 'val 'error
    //   At perform site, 'error is bound to the concrete error type (e.g., String)
    //   So the perform result type is: Result 'val String
    //
    // This implements cross-module type inference for continuation-based effect handlers.

    val continuationConstraints = mutableListOf<Constraint>()
    var performResultType: MezzeType = exprResult.type

    for (handler in expr.handlers) {
        // Look up the performer's type info
        val performerInfos = context.typingContext.getPerformerTypeInfosByName(handler.performer.localName)
        val performerInfo = performerInfos.firstOrNull() ?: continue

        // Type handler parameters if present
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

        // Use the pre-analyzed suspension type from the performer
        // This type was extracted from the impl body's suspend_with calls
        val suspensionType = performerInfo.suspensionType
        if (suspensionType != null) {
            // Substitute associated types in the suspension type
            // The suspension type may reference associated types like 'error
            // which need to be resolved to their concrete bindings
            //
            // CRITICAL: Get concrete associated type bindings from effectInstances!
            // The effectInstances track concrete bindings like 'error = String
            // from expressions that use the effect, not the generic bindings from the performer.
            val effectName = performerInfo.effectName
            // Use structured path comparison for effect name matching
            val effectNamePath = ModulePath.parse(effectName)
            val effectInstance = exprResult.effectInstances.find { instance ->
                val instancePath = ModulePath.parse(instance.name)
                instancePath == effectNamePath || instancePath.endsWith(effectNamePath)
            }

            // Build substitution map from effectInstance's concrete bindings
            // CRITICAL: The performer's associated types maps effect associated type names to
            // TypeVars (e.g., 'error -> VarType('e)). The effectInstance maps associated type
            // names to concrete types (e.g., 'error -> String).
            // We need to compose these: get the TypeVar for each associated type name from
            // the performer, then substitute that TypeVar with the concrete type.
            val performerAssocTypes = performerInfo.associatedTypes as? MezzeType.RecordType
            val concreteBindings: Map<String, MezzeType> = if (effectInstance != null && effectInstance.associatedTypes.isNotEmpty() && performerAssocTypes != null) {
                // For each associated type name in the effectInstance (e.g., "error" -> String),
                // look up the performer's binding for that name (e.g., "error" -> VarType('e))
                // and create a mapping from the TypeVar name ('e) to the concrete type (String)
                val result = mutableMapOf<String, MezzeType>()
                for ((assocTypeName, concreteType) in effectInstance.associatedTypes) {
                    val performerBinding = performerAssocTypes.fields[assocTypeName]
                    if (performerBinding is MezzeType.VarType) {
                        // Map the TypeVar name (e.g., "e") to the concrete type
                        val typeVarName = performerBinding.variable.name.removePrefix("'")
                        result[typeVarName] = concreteType
                    } else {
                        // Fallback: map the associated type name directly
                        result[assocTypeName] = concreteType
                    }
                }
                result
            } else if (performerAssocTypes != null) {
                // Fallback to performer's associated types (generic case)
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

            // SUSPENSION TYPE VALIDATION:
            // The body of a perform expression must return the same type that
            // the handler's suspend_with produces. For example:
            // - perform ToResult in <body> requires body to return Result type
            // - perform ToOption in <body> requires body to return Option type
            //
            // This ensures type safety: both the success path (body returns Ok/Some)
            // and the error path (handler calls suspend_with with Err/None) produce
            // compatible types.
            continuationConstraints.add(
                Constraint.Equality(
                    left = performResultType,
                    right = resolvedSuspensionType,
                    context = "perform body must return the same type that handler '${handler.performer.localName}' produces (e.g., wrap with Ok/Some)",
                    loc = expr.loc
                )
            )

            // Update performResultType to use the resolved suspension type
            performResultType = resolvedSuspensionType
        }
    }

    // Merge continuation constraints with expression constraints
    val allConstraints = exprResult.constraints + continuationConstraints

    // Remove handled effects from the expression's type (for types that already have effects embedded)
    // If continuation inference found a specific type, use that; otherwise use the expression type
    val baseType = if (continuationConstraints.isNotEmpty()) {
        performResultType
    } else {
        exprResult.type
    }
    val resultType = removeEffectsFromType(baseType, handledEffects)

    // CRITICAL: Also remove handled effects from ConstraintResult.effects
    // This is where effects are tracked during constraint generation
    //
    // AND: Add effects that the handlers introduce! When a handler handles one effect,
    // it may add other effects. For example, FileLogger handles Log but adds FileSystem.
    // These added effects need to propagate so outer performs can handle them.
    // BUT: If another handler in the same perform handles those added effects, they're discharged.
    val addedEffects = handlerEffects.flatMap { (handlerName, _) ->
        context.performerAddedEffects[handlerName] ?: emptySet()
    }.toSet()

    // Remove both original effects and added effects that are handled by ANY handler in this perform
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

    // Filter out handled effect instances (keep only unhandled ones)
    val remainingEffectInstances = exprResult.effectInstances.filter { instance ->
        instance.name !in handledEffects
    }.toSet()

    return ConstraintResult(
        type = resultType,
        constraints = allConstraints,  // Include continuation constraints!
        effects = remainingEffects,  // Return remaining unhandled effects
        effectInstances = remainingEffectInstances,  // Return remaining unhandled effect instances
        performed = true,  // Mark that effects were performed
        abilityConstraints = exprResult.abilityConstraints,  // Propagate ability constraints from body
        associatedTypeConstraints = exprResult.associatedTypeConstraints,  // Propagate associated type constraints
        undefinedVariables = exprResult.undefinedVariables,
        usedVariables = exprResult.usedVariables,
        referencedBindings = exprResult.referencedBindings,
        methodCallResolutions = exprResult.methodCallResolutions,
        // Record this perform's solved result type so TypedAstBuilder can
        // emit `TypedPerform.type` as the discharged result (e.g.
        // `Result _ _`) rather than the raw body type (which would be the
        // pre-discharge value wrapped in SuspendedType, breaking method
        // dispatch on the perform expression as a receiver).
        callReturnTypes = exprResult.callReturnTypes + (expr.loc to resultType),
    )
}

/**
 * Result of extracting effects from a type.
 * Contains both concrete effect names and whether effects are polymorphic.
 */
internal data class ExtractedEffects(
    val effects: Set<String>,
    val isPolymorphic: Boolean  // true if there's a row variable (<...>)
)

/**
 * Extract effects from a type.
 *
 * Returns effects from FunctionType.effects or SuspendedType.effects.
 * Used to detect if a function reference has effects that need handling.
 * Also returns whether the effect row is open (polymorphic with <...>).
 */
internal fun ExpressionConstraintGenerator.extractEffectsFromType(type: MezzeType): ExtractedEffects {
    return when (type) {
        is MezzeType.FunctionType -> {
            val effectRow = type.effects
            if (effectRow != null) {
                ExtractedEffects(effectRow.effects, effectRow.rowVar != null)
            } else {
                ExtractedEffects(emptySet(), false)
            }
        }
        is MezzeType.SuspendedType -> {
            ExtractedEffects(type.effects.effects, type.effects.rowVar != null)
        }
        // Types that don't carry effects - exhaustive matching
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
        is MezzeType.OrderingType,
        is MezzeType.BytesType,
        is MezzeType.ByteBufferType,
        is MezzeType.NewtypeType -> ExtractedEffects(emptySet(), false)
    }
}

/**
 * Check if a type has the performed marker (<*>).
 */
internal fun ExpressionConstraintGenerator.hasPerformedMarkerInType(type: MezzeType): Boolean {
    return when (type) {
        is MezzeType.FunctionType -> type.effects?.performed == true
        is MezzeType.SuspendedType -> type.effects.performed
        // Types that don't have performed marker - exhaustive matching
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
        is MezzeType.OrderingType,
        is MezzeType.BytesType,
        is MezzeType.ByteBufferType,
        is MezzeType.NewtypeType -> false
    }
}

/**
 * Wrap a call's value type in `SuspendedType` if the callee's effect row
 * is non-empty / performed. Returns `(callType, declaredEffects,
 * declaredEffectInstances)`.
 *
 * Shared by ability method calls, inherent method calls, and regular
 * function calls — same logic in three places previously.
 */
internal fun ExpressionConstraintGenerator.wrapCallResultWithEffects(
    valueType: MezzeType,
    effectRow: MezzeType.EffectRow?,
): Triple<MezzeType, Set<String>, Set<MezzeType.EffectInstance>> {
    if (effectRow == null) return Triple(valueType, emptySet(), emptySet())
    val hasUnhandled = effectRow.effects.isNotEmpty() ||
        effectRow.effectInstances.isNotEmpty() ||
        effectRow.rowVar != null
    val callType: MezzeType = if (hasUnhandled || effectRow.performed) {
        MezzeType.SuspendedType(valueType = valueType, effects = effectRow)
    } else {
        valueType
    }
    return Triple(callType, effectRow.effects, effectRow.effectInstances)
}

/**
 * Remove handled effects from a type and mark it as performed (`<*>`).
 *
 * For FunctionType: clears the handled effects from the effect row and
 * sets `performed = true`.
 * For SuspendedType: same on the underlying effect row.
 *
 * The performed marker fires for all `perform` blocks — both user-
 * written and synthesized by the do-bind desugar. The do-bind sugar
 * is intentionally semantically identical to the explicit form.
 */
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
        is MezzeType.SuspendedType -> {
            val remainingEffects = type.effects.effects - effectsToRemove
            val remainingInstances = type.effects.effectInstances.filter { it.name !in effectsToRemove }.toSet()
            if (remainingEffects.isEmpty() && remainingInstances.isEmpty()) {
                type.valueType
            } else {
                MezzeType.SuspendedType(
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
    // ParsedEffectCall represents effect operation calls like Effect.operation(args)
    // The effectName and operationName are already parsed out

    val effectName = expr.operation.qualifier?.segments?.joinToString("::") ?: ""
    val operationName = expr.operation.localName

    // Look up effect type from context using dedicated map
    val effectType = context.typingContext.getEffectOperationType(effectName)
    if (effectType == null) {
        // Effect not found - return error with fresh type var
        return ConstraintResult(
            type = MezzeType.VarType(TypeVar.fresh()),
            constraints = emptyList(),
            effects = setOf(effectName),
            undefinedVariables = listOf(ConstraintResult.UndefinedVariable(effectName, expr.loc))
        )
    }

    // Effect should be stored as RecordType with operations as fields
    if (effectType !is MezzeType.RecordType) {
        throw IllegalStateException("Effect '$effectName' is not properly registered as RecordType")
    }

    // Look up operation in effect
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

    // Handle associated type substitution if we're in a perform context
    var instantiatedOperationType = operationType
    val performContext = context.performContext
    DebugLogger.debug(Category.Effects) { "generateEffectCall: effectName=$effectName, operationName=$operationName, performContext=${performContext != null}, isFunctionType=${instantiatedOperationType is MezzeType.FunctionType}" }
    if (performContext != null && instantiatedOperationType is MezzeType.FunctionType) {
        DebugLogger.debug(Category.Effects) { "generateEffectCall: handlers=${performContext.handlers.map { it.performer.localName }}, performerToEffect=${context.performerToEffect}" }
        // Find which performer is handling this effect
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
            // Get the performer's associated type bindings using dedicated map
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

                    // CRITICAL: After substituting associated types, we need to instantiate
                    // with fresh vars BUT preserve the TypeVars that came from the performer's
                    // bindings. These are the TypeVars in performerType.fields values.
                    // This ensures constraint solving connects the effect call's argument types
                    // to the performer's type variables (which flow to Result's type params).
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

    // Instantiate with fresh vars if we didn't already (no performer substitution happened)
    if (performContext == null || performContext.handlers.none { context.performerToEffect[it.performer.localName] == effectName }) {
        instantiatedOperationType = instantiateTypeWithFreshVars(instantiatedOperationType)
    }

    // Generate constraints for arguments
    val argResult = generate(expr.arguments, context)

    // If operation is a function, add constraint that argument type matches parameter type
    val (resultType, additionalConstraints) = if (instantiatedOperationType is MezzeType.FunctionType) {
        val paramConstraint = Constraint.Equality(
            left = argResult.type,
            right = instantiatedOperationType.paramType,
            context = "Effect operation argument type",
            loc = expr.loc
        )
        instantiatedOperationType.returnType to listOf(paramConstraint)
    } else {
        // Operation is not a function (unusual but possible for thunks)
        instantiatedOperationType to emptyList()
    }

    // Create EffectInstance with associated types bound from the argument
    // For Exception.throw { error = FakeError {...} }, we need to bind 'error to FakeError
    val effectDecl = context.effectDeclarations[effectName]
    DebugLogger.debug(Category.Effects) { "generateEffectCall: creating effectInstance, effectDecl=$effectDecl, assocTypes=${effectDecl?.associatedTypes}, opType=$instantiatedOperationType" }
    val effectInstance = if (effectDecl != null && effectDecl.associatedTypes.isNotEmpty() && instantiatedOperationType is MezzeType.FunctionType) {
        // Extract associated type bindings from the argument
        // The parameter type tells us which fields map to which associated types
        val paramType = instantiatedOperationType.paramType
        val associatedTypeBindings = mutableMapOf<String, MezzeType>()

        DebugLogger.debug(Category.Effects) { "generateEffectCall: paramType=$paramType, argResult.type=${argResult.type}" }
        if (paramType is MezzeType.RecordType && argResult.type is MezzeType.RecordType) {
            val argRecordType = argResult.type as MezzeType.RecordType
            for (assocTypeName in effectDecl.associatedTypes) {
                val normalizedName = assocTypeName.removePrefix("'")
                // The associated type is bound to the field with the same name in the argument
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

// ========================================================================
// HELPER FUNCTIONS
