package com.mezze.language.typechecker.constraints

import com.mezze.language.ast.*
import com.mezze.language.parsedast.*
import com.mezze.language.typechecker.abilities.BuiltInAbilities
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.context.ContextBuilder
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.typechecker.ErrorType
import com.mezze.language.ast.solving.collectFreeTypeVars
import com.mezze.language.parsedast.diagnostics.ErrorCode

/**
 * Variant construction, lambda, and function-call constraint generators,
 * plus effect-instance merging helpers.
 *
 * Extension functions on [ExpressionConstraintGenerator].
 */

// ========================================================================
// VARIANTS
// ========================================================================

internal fun ExpressionConstraintGenerator.generateVariantConstruction(expr: ParsedVariantConstruct, context: ConstraintContext): ConstraintResult {
    // Constructor {payload}

    // Check if this constructor is an imported newtype (opaque — can't construct)
    val constructorBinding = context.typingContext.lookup(BindingKey.unqualified(expr.constructor))
    if (constructorBinding is TypingContext.LookupResult.NotFound) {
        // Constructor not in scope — could be an imported newtype (constructor not exported)
        // Check type declarations for a newtype with this constructor name
        val newtypeDecl = context.typeDeclarations.values.find {
            it.definition is ParsedNewtypeDef && (it.definition as ParsedNewtypeDef).constructor == expr.constructor
        }
        if (newtypeDecl != null) {
            throw TypeCheckError(
                message = "Cannot construct newtype '${expr.constructor}' — its constructor is private to the defining module. " +
                    "Use a public constructor function from the module instead.",
                loc = expr.loc,
                errorType = ErrorType.MISSING_FIELD,
                code = ErrorCode.M0803_NOT_EXPORTED,
                hints = listOf(
                    "newtype constructors are not exported; look for a `pub` factory function in the defining module"
                ),
            )
        }
    }

    // CRITICAL: Check if this is an ability or effect being used as a value (without payload)
    // Abilities and effects should NOT be treated as variant constructors
    val isBuiltInAbility = BuiltInAbilities.isBuiltInAbility(expr.constructor)
    val isUserDefinedAbility = expr.constructor in context.abilityNames
    val isEffect = expr.constructor in context.effectNames

    if ((isBuiltInAbility || isUserDefinedAbility || isEffect) && expr.value == null) {
        // This is an ability or effect being used as a value - error!
        // Mark as undefined variable so TypeChecker can provide a better error message
        val freshType = MezzeType.VarType(TypeVar.fresh())
        return ConstraintResult(
            type = freshType,
            constraints = emptyList(),
            undefinedVariables = listOf(
                ConstraintResult.UndefinedVariable(
                    name = expr.constructor,
                    loc = expr.loc
                )
            )
        )
    }

    val resultType = MezzeType.VarType(TypeVar.fresh())

    val valueExpr = expr.value
    if (valueExpr == null) {
        // No payload: Just constructor name
        val constraints = listOf(
            Constraint.HasConstructor(
                variantType = resultType,
                constructorName = expr.constructor,
                payloadType = null,
                context = "Variant construction ${expr.constructor}",
                loc = expr.loc,
            )
        )

        return ConstraintResult(
            type = resultType,
            constraints = constraints,
            // #182: stash the loc → inferred-type entry so TypedAstBuilder
            // can recover the post-solve variant type. Without this it
            // falls back to its `contextType` default (a *shared*
            // TypeVar("unknown", 0)), and two no-payload variants in the
            // same record literal (e.g. `{ method = Get, body = None }`)
            // end up with the same TyVar instance — the solver pins one
            // but the other carries the unresolved alias and breaks
            // downstream unifications.
            callReturnTypes = mapOf(expr.loc to resultType)
        )
    } else {
        // With payload
        val payloadResult = generate(valueExpr, context)

        // VALIDATION: Check variant constructor payload fields against type declaration
        // This validates: Red { x = 5, y = 10 } against type Color = | Red { x: Int } |
        if (valueExpr is ParsedRecordLit && context.typeDeclarations.isNotEmpty()) {
            // Find type declarations that have this constructor
            for ((typeName, typeDecl) in context.typeDeclarations) {
                if (typeDecl.definition is ParsedVariantDef) {
                    val variantDef = typeDecl.definition as ParsedVariantDef
                    val constructor = variantDef.constructors.find { it.name == expr.constructor }

                    if (constructor != null && constructor.dataType is ParsedRecordType) {
                        val expectedRecordType = constructor.dataType as ParsedRecordType
                        val isOpen = expectedRecordType.isOpen

                        // Get fields from literal
                        val literalFields = valueExpr.entries.filterIsInstance<ParsedRecordEntry.Field>().map { it.name }.toSet()
                        val expectedFields = expectedRecordType.entries.map { it.name }.toSet()

                        // Check for closed record (no ...)
                        if (!isOpen) {
                            // Check for extra fields
                            val extraFields = literalFields - expectedFields
                            if (extraFields.isNotEmpty()) {
                                throw TypeCheckError(
                                    message = "Variant constructor ${expr.constructor} expects exact fields ${expectedFields.sorted().joinToString(", ")} " +
                                            "but got extra field(s): ${extraFields.sorted().joinToString(", ")}",
                                    loc = expr.loc,
                                    errorType = ErrorType.TYPE_MISMATCH,
                                    code = ErrorCode.T0204_VARIANT_CONSTRUCTOR_ARITY,
                                    hints = listOf("remove: ${extraFields.sorted().joinToString(", ")}"),
                                )
                            }

                            // Check for missing fields
                            val missingFields = expectedFields - literalFields
                            if (missingFields.isNotEmpty()) {
                                throw TypeCheckError(
                                    message = "Variant constructor ${expr.constructor} expects fields ${expectedFields.sorted().joinToString(", ")} " +
                                            "but missing field(s): ${missingFields.sorted().joinToString(", ")}",
                                    loc = expr.loc,
                                    errorType = ErrorType.MISSING_FIELD,
                                    code = ErrorCode.T0204_VARIANT_CONSTRUCTOR_ARITY,
                                    hints = listOf("add: ${missingFields.sorted().joinToString(", ")}"),
                                )
                            }
                        }
                    }
                }
            }
        }

        val constraints = mutableListOf<Constraint>()
        constraints.addAll(payloadResult.constraints)
        constraints.add(
            Constraint.HasConstructor(
                variantType = resultType,
                constructorName = expr.constructor,
                payloadType = payloadResult.type,
                context = "Variant construction ${expr.constructor}",
                loc = expr.loc,
            )
        )

        return ConstraintResult(
            type = resultType,
            constraints = constraints,
            effects = payloadResult.effects,  // Propagate effects from payload
            effectInstances = payloadResult.effectInstances,  // Propagate effect instances
            undefinedVariables = payloadResult.undefinedVariables,
            usedVariables = payloadResult.usedVariables,
            abilityConstraints = payloadResult.abilityConstraints,
            referencedBindings = payloadResult.referencedBindings,
            methodCallResolutions = payloadResult.methodCallResolutions,
            // #182: stash the loc → inferred-type entry so TypedAstBuilder
            // can recover the post-solve variant type. See no-payload
            // branch above for the full rationale.
            callReturnTypes = payloadResult.callReturnTypes + (expr.loc to resultType)
        )
    }
}

// ========================================================================
// FUNCTIONS
// ========================================================================

internal fun ExpressionConstraintGenerator.generateLambda(expr: ParsedLambda, context: ConstraintContext): ConstraintResult {
    // {params} -> body

    // BIDIRECTIONAL TYPING: Use expected type from context if available
    // This matches the old typechecker's approach (OldTypeChecker.kt line 4090-4114)
    // Key insight: Use the SAME TypeVar instances from the expected type annotation
    // instead of creating fresh ones and unifying later
    val expectedFunctionType = context.expectedType as? MezzeType.FunctionType
    val expectedParamType = expectedFunctionType?.paramType as? MezzeType.RecordType

    // Extract parameter names and create bindings
    val paramNames: List<String>
    val paramFields: Map<String, MezzeType>
    val isOpenPattern: Boolean

    when (val params = expr.params) {
        is ParsedLambdaParams.Untyped -> {
            paramNames = params.names
            isOpenPattern = params.isOpen  // Check if ... was used in parameter list

            // If we have an expected parameter type, use it directly
            // This ensures we use the SAME TypeVar instances from the annotation
            paramFields = if (expectedParamType != null) {
                // Use expected parameter types from the function type annotation
                // This is CRITICAL for polymorphic functions to work correctly
                paramNames.associateWith { name ->
                    expectedParamType.fields[name] ?: MezzeType.VarType(TypeVar.fresh())
                }
            } else {
                // No expected type: create fresh type variables for each parameter
                paramNames.associateWith { MezzeType.VarType(TypeVar.fresh()) }
            }
        }
        is ParsedLambdaParams.Typed -> {
            // Typed parameters: use the annotated types
            // Check if the pattern is open (has ...)
            isOpenPattern = false  // ParsedLambdaParams.Typed has no isOpen; closed by default
            paramNames = params.fields.map { it.name }
            // CRITICAL: Convert annotated types using convertParsedTypeAnnotation instead of fresh vars!
            // This ensures parameter types like `opt: Option Int` actually use `Option Int`
            // instead of a fresh type variable.
            paramFields = params.fields.associate { entry ->
                entry.name to convertParsedTypeAnnotation(entry.type)
            }
        }
    }

    // Create parameter record type
    // CRITICAL: Preserve rowVar from expected type OR from open pattern
    val rowVar = when {
        expectedParamType?.rowVar != null -> expectedParamType.rowVar  // From type annotation
        isOpenPattern -> RowVar.fresh()  // From pattern syntax {x, ...}
        else -> null  // Closed record
    }
    val paramType = MezzeType.RecordType(
        fields = paramFields,
        rowVar = rowVar
    )

    // Extend context with parameter bindings
    val extendedContext = paramFields.entries.fold(context) { ctx, (name, type) ->
        ctx.extend(name, type)
    }

    // Generate constraints for body with extended context
    // Pass expected return type if available (bidirectional typing)
    // TCO: Lambda body is in tail position (note: enclosingFunctionName is set by ConstraintGenerator)
    val bodyContext = if (expectedFunctionType != null) {
        extendedContext.withExpectedType(expectedFunctionType.returnType).inTailPosition()
    } else {
        extendedContext.inTailPosition()
    }
    val bodyResult = generate(expr.body, bodyContext)

    // Create function type with effects from body
    // CRITICAL: If the body has effects, effect instances, or performed effects, add them to the FunctionType
    // Include effectInstances in the EffectRow for effect-polymorphic signatures
    //
    // IMPORTANT: Effect instances from different branches (if-then-else, match) may have the same
    // effect name but different associated types. For example:
    //   - Branch 1: Exception with 'error = MathError
    //   - Branch 2: Exception with 'error = FakeError
    // These need to be MERGED by unifying their associated types, producing:
    //   - Exception with 'error = | MathError, FakeError |
    val (mergedEffectInstances, effectMergeConstraints) = mergeEffectInstances(bodyResult.effectInstances, expr.body.loc)

    val effectRow = if (bodyResult.effects.isNotEmpty() || mergedEffectInstances.isNotEmpty() || bodyResult.performed) {
        MezzeType.EffectRow(
            effects = bodyResult.effects,
            effectInstances = mergedEffectInstances,
            rowVar = null,
            performed = bodyResult.performed
        )
    } else {
        null
    }

    // CRITICAL: Generate constraints for effect associated types from annotation
    // When the annotation specifies effect associated types (e.g., <State having 'state = Int>),
    // we need to add equality constraints to unify the annotated types with the inferred ones.
    val effectAssociatedTypeConstraints = mutableListOf<Constraint>()
    val expectedEffects = expectedFunctionType?.effects
    if (expectedEffects != null && expectedEffects.effectInstances.isNotEmpty()) {
        // For each effect instance in the annotation with associated types,
        // find the matching effect instance from the body and constrain their associated types
        val bodyInstances = bodyResult.effectInstances.associateBy { it.name }
        for (annotationInstance in expectedEffects.effectInstances) {
            val bodyInstance = bodyInstances[annotationInstance.name]
            if (bodyInstance != null) {
                // Unify each associated type from annotation with the corresponding one from body
                // NOTE: Annotation keys have apostrophe prefix ('state), body keys don't (state)
                for ((assocName, annotationType) in annotationInstance.associatedTypes) {
                    // Try both with and without apostrophe prefix
                    val normalizedName = assocName.removePrefix("'")
                    val bodyType = bodyInstance.associatedTypes[normalizedName]
                        ?: bodyInstance.associatedTypes[assocName]
                    if (bodyType != null) {
                        effectAssociatedTypeConstraints.add(
                            Constraint.Equality(
                                left = annotationType,
                                right = bodyType,
                                context = "Effect associated type '$assocName' in ${annotationInstance.name}",
                                loc = expr.loc,
                            )
                        )
                    }
                }
            }
        }
    }

    // CRITICAL: Unwrap SuspendedType from body result - effects are already in effectRow
    // This prevents double-wrapping: FunctionType already has effects in its `effects` field,
    // so the return type should be the plain value type, not SuspendedType(value, effects)
    val returnType = when (val bodyType = bodyResult.type) {
        is MezzeType.SuspendedType -> bodyType.valueType
        else -> bodyType
    }

    // CRITICAL: Include ability constraints from multiple sources:
    // 1. From the lambda's own abilityConstraints (parsed from `type with Ability = lambda`)
    // 2. From the expected type annotation (if present)
    // 3. Inferred from body — harvest bodyResult.abilityConstraints so mono
    //    (#218) can resolve them at the call site. Without this, an unannotated
    //    lambda whose body uses an ability method (e.g. `w == h` desugaring
    //    to `w.eq {r = h}`) loses the `<Eq 'v>` constraint at the arrow,
    //    leaving MonoDriver.resolveAbilityConstraints with nothing to walk,
    //    so the specialized blob's body keeps the unresolved `'v` and the
    //    runtime crashes at __native_int_eq with l=null.
    // The lambda's own constraints take precedence since they're explicitly specified
    val abilityConstraints = when {
        expr.abilityConstraints.isNotEmpty() -> {
            // Convert from ParsedFnAbilityConstraint to MezzeType.FunctionAbilityConstraint
            expr.abilityConstraints.map { constraint ->
                MezzeType.FunctionAbilityConstraint(
                    abilityName = constraint.abilityName,
                    typeArgs = constraint.typeArgs.map { typeAnn ->
                        convertParsedTypeAnnotation(typeAnn)
                    },
                    abilityHash = context.abilityDeclarations[constraint.abilityName]?.contentHash
                        ?: com.mezze.language.typechecker.WellKnownHashConstants.resolveAbilityHash(constraint.abilityName)
                )
            }
        }
        expectedFunctionType?.abilityConstraints?.isNotEmpty() == true -> expectedFunctionType.abilityConstraints
        else -> bodyResult.abilityConstraints
            .distinctBy { it.abilityName to it.typeVar.id }
            .map { ab ->
                MezzeType.FunctionAbilityConstraint(
                    abilityName = ab.abilityName,
                    typeArgs = listOf<MezzeType>(MezzeType.VarType(ab.typeVar)) + ab.typeArgs,
                    abilityHash = ab.abilityHash
                )
            }
    }

    // CRITICAL: Generate constraints for ability associated types from annotation
    // When the annotation specifies ability associated types (e.g., with Container 'c having 'item = Int),
    // we need to add equality constraints to unify the annotated types with the inferred ones.
    val abilityAssociatedTypeConstraints = mutableListOf<Constraint>()
    val expectedAbilityConstraints = expectedFunctionType?.abilityConstraints ?: emptyList()
    for (annotationConstraint in expectedAbilityConstraints) {
        if (annotationConstraint.associatedTypeBindings.isNotEmpty()) {
            // Find matching AssociatedTypeResolution constraints from the body
            val matchingBodyConstraints = bodyResult.associatedTypeConstraints.filter {
                it.abilityName == annotationConstraint.abilityName
            }

            for (bodyConstraint in matchingBodyConstraints) {
                // For each associated type binding in the annotation,
                // find the corresponding type variable from the body constraint
                for ((assocName, annotationType) in annotationConstraint.associatedTypeBindings) {
                    val normalizedName = assocName.removePrefix("'")
                    val bodyTypeVar = bodyConstraint.associatedTypeVars[normalizedName]
                        ?: bodyConstraint.associatedTypeVars[assocName]

                    if (bodyTypeVar != null) {
                        abilityAssociatedTypeConstraints.add(
                            Constraint.Equality(
                                left = annotationType,
                                right = MezzeType.VarType(bodyTypeVar),
                                context = "Ability associated type '$assocName' in ${annotationConstraint.abilityName}",
                                loc = expr.loc,
                            )
                        )
                    }
                }
            }
        }
    }

    val functionType = MezzeType.FunctionType(
        paramType = paramType,
        returnType = returnType,
        effects = effectRow,
        abilityConstraints = abilityConstraints
    )

    // CRITICAL: Propagate ability constraints from body
    // These will be added to the FunctionType during generalization
    // TCO: Propagate tail calls from body
    // WARNINGS: Propagate pattern warnings and unused variables from body
    return ConstraintResult(
        type = functionType,
        constraints = bodyResult.constraints + effectAssociatedTypeConstraints + abilityAssociatedTypeConstraints + effectMergeConstraints,
        abilityConstraints = bodyResult.abilityConstraints,
        undefinedVariables = bodyResult.undefinedVariables,
        tailCalls = bodyResult.tailCalls,
        usedVariables = bodyResult.usedVariables,
        unusedVariables = bodyResult.unusedVariables,
        patternWarnings = bodyResult.patternWarnings,
        referencedBindings = bodyResult.referencedBindings,
        methodCallResolutions = bodyResult.methodCallResolutions,
        callReturnTypes = bodyResult.callReturnTypes,
        matchCaseBindings = bodyResult.matchCaseBindings,
    )
}

/**
 * Merge effect instances that have the same effect name.
 *
 * When an if-then-else or match expression has branches that throw different error types
 * for the same effect (e.g., Exception), we need to:
 * 1. Group effect instances by name
 * 2. For instances with the same name, directly merge their associated types (especially variants)
 * 3. Return a single merged instance per effect name
 *
 * Example:
 *   Branch 1: Exception with 'error = | MathError {...} |
 *   Branch 2: Exception with 'error = | FakeError {...} |
 *
 * After merging:
 *   Exception with 'error = | MathError {...}, FakeError {...} |
 *
 * For variant types, we directly merge constructors from all branches.
 * For non-variant types, we generate equality constraints.
 */
internal fun ExpressionConstraintGenerator.mergeEffectInstances(
    instances: Set<MezzeType.EffectInstance>,
    loc: Loc? = null,
): Pair<Set<MezzeType.EffectInstance>, List<Constraint>> {
    if (instances.isEmpty()) {
        return Pair(emptySet(), emptyList())
    }

    // Group instances by effect name
    val groupedByName = instances.groupBy { it.name }

    val mergedInstances = mutableSetOf<MezzeType.EffectInstance>()
    val constraints = mutableListOf<Constraint>()

    for ((effectName, instancesForEffect) in groupedByName) {
        if (instancesForEffect.size == 1) {
            // Only one instance for this effect - no merging needed
            mergedInstances.add(instancesForEffect.first())
        } else {
            // Multiple instances with same effect name - need to merge their associated types
            // Collect all unique associated type keys
            val allKeys = instancesForEffect.flatMap { it.associatedTypes.keys }.toSet()

            val mergedAssocTypes = mutableMapOf<String, MezzeType>()
            for (key in allKeys) {
                // Collect all types for this key from all instances
                val typesForKey = instancesForEffect.mapNotNull { it.associatedTypes[key] }

                if (typesForKey.isEmpty()) {
                    // No types for this key (shouldn't happen but handle gracefully)
                    continue
                } else if (typesForKey.size == 1) {
                    // Only one instance has this key
                    mergedAssocTypes[key] = typesForKey.first()
                } else {
                    // Multiple instances have this key - try to merge them directly
                    val mergedType = mergeAssociatedTypes(typesForKey, constraints, loc)
                    mergedAssocTypes[key] = mergedType
                }
            }

            mergedInstances.add(MezzeType.EffectInstance(effectName, mergedAssocTypes))
        }
    }

    return Pair(mergedInstances, constraints)
}

/**
 * Merge multiple types into a single type.
 *
 * For variant types, this directly merges constructors from all types.
 * For other types, this creates a fresh type variable and generates equality constraints.
 */
internal fun ExpressionConstraintGenerator.mergeAssociatedTypes(
    types: List<MezzeType>,
    constraints: MutableList<Constraint>,
    loc: Loc? = null,
): MezzeType {
    // Check if all types are variant types - if so, we can merge them directly
    val variantTypes = types.filterIsInstance<MezzeType.VariantType>()

    if (variantTypes.size == types.size && variantTypes.isNotEmpty()) {
        // All types are variants - merge their constructors directly
        val mergedConstructors = mutableMapOf<String, MezzeType?>()

        for (variant in variantTypes) {
            for ((constructorName, payloadType) in variant.constructors) {
                val existingPayload = mergedConstructors[constructorName]
                if (existingPayload == null && constructorName !in mergedConstructors) {
                    // New constructor - add it
                    mergedConstructors[constructorName] = payloadType
                } else if (payloadType != null && existingPayload != null) {
                    // Constructor exists with payload in both - need to unify payloads
                    // Generate a constraint for the payload types
                    val mergedPayloadVar = MezzeType.VarType(TypeVar.fresh())
                    constraints.add(
                        Constraint.Equality(
                            left = existingPayload,
                            right = mergedPayloadVar,
                            context = "Merging variant constructor '$constructorName' payload",
                            loc = loc,
                        )
                    )
                    constraints.add(
                        Constraint.Equality(
                            left = payloadType,
                            right = mergedPayloadVar,
                            context = "Merging variant constructor '$constructorName' payload",
                            loc = loc,
                        )
                    )
                    mergedConstructors[constructorName] = mergedPayloadVar
                }
                // If constructor exists but payload types differ (one null, one not), keep the non-null one
                else if (payloadType != null && existingPayload == null) {
                    mergedConstructors[constructorName] = payloadType
                }
                // If existing has payload and new doesn't, keep existing
            }
        }

        // Create a closed variant with all merged constructors
        return MezzeType.VariantType(
            constructors = mergedConstructors,
            rowVar = null  // Closed variant
        )
    }

    // Not all variants - fall back to type variable with constraints
    // CRITICAL: Set isMatchCaseContext = true so that the constraint solver
    // will merge the variants instead of just unifying them. Without this,
    // when $tv1 = | MathError | and $tv2 = | FakeError | are unified through
    // the merged type var, the solver won't merge the constructors.
    val mergedTypeVar = MezzeType.VarType(TypeVar.fresh())
    for (typeForKey in types) {
        constraints.add(
            Constraint.Equality(
                left = typeForKey,
                right = mergedTypeVar,
                context = "Merging effect associated types from branches",
                isMatchCaseContext = true,  // Enable variant merging
                loc = loc,
            )
        )
    }
    return mergedTypeVar
}

internal fun ExpressionConstraintGenerator.generateFunctionCall(expr: ParsedFunctionCall, context: ConstraintContext): ConstraintResult {
    // function(arguments)
    // Supports:
    // 1. Full application: all parameters provided
    // 2. Partial application: subset of parameters provided (currying)
    // 3. Effectful functions: wrap result in SuspendedType

    // VALIDATION: Reject spread-only records in function calls (Bug #10 Extension)
    // Valid: func { x = 1, y = 2 } - explicit fields
    // Valid: func { ...k, x = 1 } - spread with explicit fields
    // Invalid: func { ...k } - spread-only
    if (expr.arguments is ParsedRecordLit) {
        val recordLiteral = expr.arguments as ParsedRecordLit
        val hasExplicitFields = recordLiteral.entries.any { it is ParsedRecordEntry.Field }
        val hasSpreadEntries = recordLiteral.entries.any { it is ParsedRecordEntry.Spread }

        // Reject if ONLY spread entries (no explicit fields)
        if (hasSpreadEntries && !hasExplicitFields) {
            // Get the function name for error message
            val functionName = when (expr.function) {
                is ParsedIdentifier -> (expr.function as ParsedIdentifier).localName
                else -> "function"
            }

            // Get the spread variable name for error message
            val spreadEntry = recordLiteral.entries.filterIsInstance<ParsedRecordEntry.Spread>().first()
            val spreadVarName = when (spreadEntry.expr) {
                is ParsedIdentifier -> (spreadEntry.expr as ParsedIdentifier).localName
                else -> "record"
            }

            throw TypeCheckError(
                message = "cannot use spread-only records in function call to '$functionName'. " +
                        "Spread variable: '$spreadVarName'. " +
                        "Use explicit named arguments instead, e.g., { x = $spreadVarName.x, y = $spreadVarName.y }",
                loc = expr.loc,
                errorType = ErrorType.TYPE_MISMATCH,
                metadata = mapOf(
                    "functionName" to functionName,
                    "spreadVariable" to spreadVarName
                ),
                code = com.mezze.language.parsedast.diagnostics.ErrorCode.V0708_SPREAD_ONLY_RECORD_FORBIDDEN,
                hints = listOf("write each field explicitly: `{ x = $spreadVarName.x, y = $spreadVarName.y }`"),
            )
        }
    }

    // METHOD CALL RESOLUTION: Detect `value.method { args }` pattern
    // When the function is ParsedMemberAccess(receiver, methodName) and receiver is NOT an ability/effect,
    // check if methodName matches an ability operation. If so, transform into an ability method call
    // where the receiver is injected as the first argument.
    if (expr.function is ParsedMemberAccess) {
        val memberAccess = expr.function as ParsedMemberAccess
        val receiverName = when (memberAccess.receiver) {
            is ParsedIdentifier -> (memberAccess.receiver as ParsedIdentifier).localName
            is ParsedVariantConstruct -> (memberAccess.receiver as ParsedVariantConstruct).constructor
            else -> null
        }
        val isAbilityOrEffect = receiverName != null && receiverName.first().isUpperCase() &&
            (context.typingContext.getAbilityOperationType(receiverName) != null ||
             context.typingContext.getEffectOperationType(receiverName) != null ||
             context.performerToEffect.containsKey(receiverName) ||
             BuiltInAbilities.isBuiltInAbility(receiverName))

        // QUALIFIED ABILITY CALL: `Ability.method { it = receiver, ... }`
        // When the function-call's function is `Ability.method` (where Ability is a
        // user-declared or built-in ability) and the args record contains `it = ...`,
        // dispatch as an ability method call with `it` as the receiver. This is the
        // form the existing error message at generateMemberAccess advertises as
        // supported but which previously fell through to the standalone
        // `Ability.method` evaluator and got rejected there.
        if (isAbilityOrEffect && receiverName != null) {
            val isUserAbility = context.typingContext.getAbilityOperationType(receiverName) != null
            val isBuiltInAb = BuiltInAbilities.isBuiltInAbility(receiverName)
            if ((isUserAbility || isBuiltInAb) && expr.arguments is ParsedRecordLit) {
                val argRecord = expr.arguments as ParsedRecordLit
                val itEntry = argRecord.entries
                    .filterIsInstance<ParsedRecordEntry.Field>()
                    .find { it.name == Keywords.IT }
                // Build the receiver expression from the it entry. If `value` is
                // null (record-punning `{it}`), there's an in-scope `it` we can
                // reference by name.
                val itReceiver: ParsedExpr? = itEntry?.let {
                    it.value ?: ParsedIdentifier(
                        qualifier = null,
                        localName = Keywords.IT,
                        loc = it.loc
                    )
                }
                if (itReceiver != null) {
                    // Synthesize an equivalent method-call expression:
                    //   <it-value>.method { remaining-args }
                    // and dispatch through the existing method-call path.
                    val remainingFields = argRecord.entries.filter { e ->
                        e !is ParsedRecordEntry.Field || e.name != Keywords.IT
                    }
                    val syntheticArgs = ParsedRecordLit(remainingFields, argRecord.loc)
                    val syntheticReceiverAccess = ParsedMemberAccess(
                        receiver = itReceiver,
                        member = memberAccess.member,
                        loc = memberAccess.loc
                    )
                    val syntheticCall = ParsedFunctionCall(
                        function = syntheticReceiverAccess,
                        arguments = syntheticArgs,
                        loc = expr.loc
                    )
                    // Force the dispatcher to pick THIS ability (not a coincidentally-
                    // matching one). Reuse the annotationAbilityNames channel — its
                    // semantics are "prefer these abilities when multiple match" — by
                    // narrowing it to {receiverName}. Without this, `B.foo {it=x}`
                    // routes to `A` whenever both have a `foo` method.
                    val qualifiedContext = context.copy(
                        annotationAbilityNames = setOf(receiverName)
                    )
                    val methodResult = tryGenerateMethodCall(
                        syntheticCall,
                        syntheticReceiverAccess,
                        qualifiedContext
                    )
                    if (methodResult != null) {
                        return methodResult
                    }
                }
            }
        }

        // If receiver is a ParsedVariantConstruct (uppercase) that's NOT a known ability/effect,
        // check if it's a known value (variant constructor like True, Some, etc.).
        // If it's not a known value either, it's likely a misspelled ability —
        // skip method resolution and let the normal error path handle it.
        val isUnknownUppercaseName = !isAbilityOrEffect &&
            memberAccess.receiver is ParsedVariantConstruct &&
            context.typingContext.lookup(receiverName ?: "") is TypingContext.LookupResult.NotFound

        // Only try method resolution if receiver is NOT an ability/effect/performer
        // and not an unknown uppercase name
        if (!isAbilityOrEffect && !isUnknownUppercaseName) {
            val methodCallResult = tryGenerateMethodCall(expr, memberAccess, context)
            if (methodCallResult != null) {
                return methodCallResult
            }
        }
    }

    // TCO: The function expression and arguments are NOT in tail position
    // (only the entire call could be a tail call)
    val functionResult = generate(expr.function, context.notInTailPosition())
    // CRITICAL: Clear expected type when generating function arguments!
    // The argument's expected type should come from the function's parameter type,
    // not from the outer context. Otherwise, expected types from outer scope
    // (like Continuation) would incorrectly propagate to argument record literals.
    val argumentResult = generate(expr.arguments, context.clearExpectedType().notInTailPosition())

    // TCO: Detect self-recursive tail calls
    // A call is a self-recursive tail call if:
    // 1. We're in tail position
    // 2. The function being called is an UNQUALIFIED identifier matching the enclosing function name
    // Note: Must check for unqualified (no qualifier) to avoid false positives like
    // `let length = {xs} -> seq::length {xs}` where seq::length != length
    val isSelfTailCall = context.inTailPosition &&
        context.enclosingFunctionName != null &&
        expr.function is ParsedIdentifier &&
        (expr.function as ParsedIdentifier).qualifier == null &&
        (expr.function as ParsedIdentifier).localName == context.enclosingFunctionName

    // Track tail call location if detected
    val tailCallLocation = if (isSelfTailCall) expr.loc else null

    // PARTIAL APPLICATION DETECTION
    val functionType = functionResult.type
    val argumentType = argumentResult.type

    // PARTIAL APPLICATION: Check if this is partial application
    if (functionType is MezzeType.FunctionType && argumentType is MezzeType.RecordType) {
        val funcParamType = functionType.paramType
        if (funcParamType is MezzeType.RecordType) {
            val expectedFields = funcParamType.fields.keys
            val providedFields = argumentType.fields.keys
            val missingFields = expectedFields - providedFields
            val extraFields = providedFields - expectedFields

            // VALIDATION: Reject extra fields in closed records
            if (extraFields.isNotEmpty() && funcParamType.rowVar == null) {
                // Get function name for error message
                val functionName = when (expr.function) {
                    is ParsedIdentifier -> (expr.function as ParsedIdentifier).localName
                    else -> "function"
                }
                throw TypeCheckError(
                    message = "Extra field(s) '${extraFields.joinToString(", ")}' in call to '$functionName'. " +
                            "Function expects fields: ${expectedFields.sorted().joinToString(", ")}",
                    loc = expr.loc,
                    errorType = ErrorType.TYPE_MISMATCH,
                    code = ErrorCode.T0203_RECORD_EXTRA_FIELDS,
                )
            }

            // Check for partial application (some fields missing)
            if (missingFields.isNotEmpty()) {
                // PARTIAL APPLICATION: Return function accepting remaining parameters
                val collector = ConstraintCollector()
                collector.merge(functionResult)
                collector.merge(argumentResult)

                // Add constraints for provided fields (must match expected types)
                for (fieldName in providedFields) {
                    val expectedType = funcParamType.fields[fieldName]
                    val providedType = argumentType.fields[fieldName]
                    if (expectedType != null && providedType != null) {
                        collector.constraints.add(
                            Constraint.Equality(
                                left = providedType,
                                right = expectedType,
                                context = "Partial application field $fieldName",
                                loc = expr.loc
                            )
                        )
                    }
                }

                // Create remaining parameter type (only missing fields)
                val remainingFields = funcParamType.fields.filterKeys { it in missingFields }
                val remainingParamType = MezzeType.RecordType(
                    fields = remainingFields,
                    rowVar = funcParamType.rowVar  // Preserve open/closed nature
                )

                // Result is a new function: remainingParams -> returnType
                // Preserve effects from original function
                // Split ability constraints: keep polymorphic ones on the result function type,
                // emit concrete ones as AbilityRequired so the solver verifies them.
                val remainingTypeVars = collectFreeTypeVars(remainingParamType) + collectFreeTypeVars(functionType.returnType)
                val filteredAbilityConstraints = mutableListOf<MezzeType.FunctionAbilityConstraint>()
                val concreteAbilityChecks = mutableListOf<Constraint.AbilityRequired>()

                for (constraint in functionType.abilityConstraints) {
                    val hasPolymorphicArg = constraint.typeArgs.any { typeArg ->
                        typeArg is MezzeType.VarType && typeArg.variable in remainingTypeVars
                    }
                    if (hasPolymorphicArg) {
                        filteredAbilityConstraints.add(constraint)
                    } else {
                        // All type args are concrete — emit an AbilityRequired constraint
                        // so the solver verifies the ability is satisfied
                        for (typeArg in constraint.typeArgs) {
                            if (typeArg is MezzeType.VarType) {
                                concreteAbilityChecks.add(
                                    Constraint.AbilityRequired(
                                        typeVar = typeArg.variable,
                                        abilityName = constraint.abilityName,
                                        abilityHash = constraint.abilityHash,
                                        typeArgs = constraint.typeArgs,
                                        context = "Partial application: ability constraint on fixed type variable",
                                        loc = expr.loc
                                    )
                                )
                            }
                        }
                    }
                }

                val partialResult = MezzeType.FunctionType(
                    paramType = remainingParamType,
                    returnType = functionType.returnType,
                    effects = functionType.effects,
                    abilityConstraints = filteredAbilityConstraints
                )

                collector.abilityConstraints.addAll(concreteAbilityChecks)
                collector.callReturnTypes[expr.loc] = partialResult
                return collector.toResult(partialResult)
            }
        }
    }

    // FULL APPLICATION (or function type not yet known)
    // Check if the function type has effects (concrete or polymorphic)
    if (functionType is MezzeType.FunctionType) {
        val effectRow = functionType.effects
        // Handle effects if: has concrete effects OR effect instances OR has a row variable (polymorphic <...>) OR is performed
        if (effectRow != null && (effectRow.effects.isNotEmpty() || effectRow.effectInstances.isNotEmpty() || effectRow.rowVar != null || effectRow.performed)) {
            val collector = ConstraintCollector()
            collector.merge(functionResult)
            collector.merge(argumentResult)

            // Argument must match parameter type
            // Extract function name for better error messages
            val functionName = when (expr.function) {
                is ParsedIdentifier -> (expr.function as ParsedIdentifier).localName
                else -> null
            }
            collector.constraints.add(
                Constraint.Equality(
                    left = argumentResult.type,
                    right = functionType.paramType,
                    context = "Function application argument",
                    loc = expr.loc,
                    errorContext = ErrorContext.FunctionArgument(
                        functionName = functionName,
                        argumentName = extractArgumentName(expr.arguments),
                        parameterIndex = 0
                    )
                )
            )

            // CRITICAL FIX: Propagate effects from the function type to ConstraintResult.effects
            // This allows generatePerform to track which effects are used by the expression
            //
            // #312: when the function's effect row has a row var (e.g. spawn declared
            // `<Async, ...>`), harvest effects from any closure-typed argument fields.
            // Those effects flow through the row var to the surrounding scope. We do this
            // as a *side-channel* read here (no Substitution involvement) because mixing
            // row var bindings into the substitution machinery interacts badly with type
            // var unification and broke 12 interp tests in the previous attempt.
            val rowVarEffects: Set<String> = if (effectRow.rowVar != null) {
                collectClosureArgEffects(argumentResult.type)
            } else emptySet()
            // CRITICAL FIX: Also propagate effectInstances from the function type!
            // This is needed so that when a function calls another function with associated effect types
            // (like Exception with 'error), those effect instances are properly propagated and merged.
            collector.effects.addAll(effectRow.effects + rowVarEffects)
            collector.effectInstances.addAll(effectRow.effectInstances)
            // Propagate performed flag from function type
            collector.performed = collector.performed || effectRow.performed

            // TCO: Include tail call if detected (note: effectful tail calls may need special handling)
            if (tailCallLocation != null) collector.tailCalls.add(tailCallLocation)

            // CRITICAL: If the function type has ability constraints (like `with Show 'b`),
            // generate AbilityRequired constraints for them. These constraints will be unified
            // with the actual argument types through the equality constraint above.
            if (functionType.abilityConstraints.isNotEmpty()) {
                val methodLevelConstraints = functionType.abilityConstraints.mapNotNull { constraint ->
                    // Extract the type variable from typeArgs (first VarType is the constrained type)
                    val constrainedType = constraint.typeArgs.firstOrNull()
                    if (constrainedType is MezzeType.VarType) {
                        Constraint.AbilityRequired(
                            typeVar = constrainedType.variable,
                            abilityName = constraint.abilityName,
                            abilityHash = context.abilityDeclarations[constraint.abilityName]?.contentHash,
                            typeArgs = constraint.typeArgs.drop(1),  // Additional type args after the constrained type
                            context = "Method-level constraint: ${constraint.abilityName} on ${constrainedType.variable.name}",
                            loc = expr.loc
                        )
                    } else null
                }
                collector.abilityConstraints.addAll(methodLevelConstraints)
            }

            // Wrap the call's value type in SuspendedType when the callee
            // has effects (unhandled or performed). Important so top-level
            // validation can detect `<*>`.
            val (resultType, _, _) = wrapCallResultWithEffects(functionType.returnType, effectRow)

            collector.callReturnTypes[expr.loc] = resultType
            return collector.toResult(resultType)
        }
    }

    // Normal function call (no effects in the function type itself)
    // BUT: the function may have tracked effects in ConstraintResult.effects
    // For example, effect operations like Log.println track effects this way.
    //
    // Prefer the function's concrete return type when it has no free
    // TypeVars. Using a fresh VarType here means downstream consumers
    // (e.g. let-binding typing context, used by inherent-method dispatch)
    // see an unresolved VarType until the solver runs. Pulling a
    // monomorphic returnType directly gives them the concrete type
    // immediately, which is required for inherent-impl dispatch on
    // effect-op results (e.g. `Greet.greet {}.to_upper {}`).
    //
    // For polymorphic returns (e.g. `'a` from `throw : … -> 'a`), keep
    // the fresh VarType — variant-merging tests rely on each call site
    // producing its own labelable result so that distinct error types
    // from different branches accumulate instead of collapsing.
    val returnType: MezzeType = run {
        val ft = functionResult.type
        if (ft is MezzeType.FunctionType) {
            val freeVars = com.mezze.language.ast.solving.collectFreeTypeVars(ft.returnType)
            if (freeVars.isEmpty()) ft.returnType
            else MezzeType.VarType(TypeVar.fresh())
        } else MezzeType.VarType(TypeVar.fresh())
    }

    val collector = ConstraintCollector()
    collector.merge(functionResult)
    collector.merge(argumentResult)

    // function must be a function type: argumentType -> returnType
    // Note: left = actual (what we got), right = expected (what we need)
    // The function's actual type must match a function that takes our argument type
    // Extract function name for better error messages
    val functionName = when (expr.function) {
        is ParsedIdentifier -> (expr.function as ParsedIdentifier).localName
        else -> null
    }
    collector.constraints.add(
        Constraint.Equality(
            left = MezzeType.FunctionType(
                paramType = argumentResult.type,
                returnType = returnType
            ),
            right = functionResult.type,
            context = "Function application",
            loc = expr.loc,
            errorContext = ErrorContext.FunctionArgument(
                functionName = functionName,
                argumentName = extractArgumentName(expr.arguments),
                parameterIndex = 0
            )
        )
    )

    // Collect effects from the function being called (e.g., effect operations)
    // Also collect effects from the arguments.
    //
    // #312: when calling an effect operation (functionResult.effects is
    // non-empty — set by tryGenerateEffectOperationCall), the operation's
    // declared signature may have a polymorphic effect row (`<Effect, ...>`)
    // for inputs like `f: {} -> 'a <...>`. Pulling effects out of any
    // closure-typed argument fields here surfaces them at the call site
    // — `Async.spawn { f = effectful }` ends up performing `<Async, …f's effects>`.
    // Pure side-channel: argument types are inspected, no Substitution touched.
    val rowVarEffects = if (functionResult.effects.isNotEmpty())
        collectClosureArgEffects(argumentResult.type)
    else emptySet()
    // Extract effects directly from the function's type. Required for
    // pub-let helpers that wrap effect ops: a binding like
    //   `pub let process_args : {} -> [Str] <Environment> = ...`
    // is stored as a FunctionType with effects=<Environment>, but the
    // identifier-lookup path returns only the type, not its effect row
    // (and tryGenerateEffectOperationCall only fires on `Effect.op`
    // syntax). Without this, calling the helper looks "pure" at the
    // call site even though its signature declares effects.
    // Extract effects declared on the function's type itself. Required
    // for pub-let helpers that wrap effect ops — e.g.
    //   `pub let process_args : {} -> [Str] <Environment> = ...`
    // — where the helper's effect row otherwise wouldn't reach the
    // call site (identifier lookup returns the type but not its
    // effect row; tryGenerateEffectOperationCall only fires on
    // direct `Effect.op` syntax). The known-broken case is in
    // EnvironmentEffectModuleTest::pub-let helper imported standalone.
    if (functionResult.type is MezzeType.FunctionType) {
        val fnType = functionResult.type as MezzeType.FunctionType
        collector.effects.addAll(fnType.effects?.effects ?: emptySet())
        collector.performed = collector.performed || (fnType.effects?.performed ?: false)
    }
    collector.effects.addAll(rowVarEffects)

    // CRITICAL: If the function type has ability constraints (like `with Show 'b`),
    // generate AbilityRequired constraints for them.
    if (functionResult.type is MezzeType.FunctionType) {
        val funcType = functionResult.type as MezzeType.FunctionType
        if (funcType.abilityConstraints.isNotEmpty()) {
            val methodLevelConstraints = funcType.abilityConstraints.mapNotNull { constraint ->
                // Extract the type variable from typeArgs (first VarType is the constrained type)
                val constrainedType = constraint.typeArgs.firstOrNull()
                if (constrainedType is MezzeType.VarType) {
                    Constraint.AbilityRequired(
                        typeVar = constrainedType.variable,
                        abilityName = constraint.abilityName,
                        abilityHash = context.abilityDeclarations[constraint.abilityName]?.contentHash,
                        typeArgs = constraint.typeArgs.drop(1),  // Additional type args after the constrained type
                        context = "Method-level constraint: ${constraint.abilityName} on ${constrainedType.variable.name}",
                        loc = expr.loc
                    )
                } else null
            }
            collector.abilityConstraints.addAll(methodLevelConstraints)
        }
    }

    // TCO: Include tail call if detected
    if (tailCallLocation != null) collector.tailCalls.add(tailCallLocation)

    collector.callReturnTypes[expr.loc] = returnType
    return collector.toResult(returnType)
}

// ========================================================================
// CONTROL FLOW
// ========================================================================

// NOTE: IfExpression is unified with MatchExpression at the type-checking level.
// The generateIf() function has been removed - IfExpression is now transformed to
// MatchExpression via toMatchExpression() and handled by generateMatch().
// This simplifies the typechecker while preserving:
// - IfExpression in AST for tooling (error messages, LSP, formatting)
// - IfNode in interpreter for optimal runtime performance (simple branch profile)

