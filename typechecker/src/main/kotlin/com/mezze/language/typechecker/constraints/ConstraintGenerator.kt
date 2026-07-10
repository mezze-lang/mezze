package com.mezze.language.typechecker.constraints

import com.mezze.language.ast.*
import com.mezze.language.parsedast.*
import com.mezze.language.typechecker.abilities.BuiltInAbilities
import com.mezze.language.typechecker.context.ContextBuilder
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.context.TypeScheme
import com.mezze.language.typechecker.debug.DebugLogger
import com.mezze.language.typechecker.debug.DebugLogger.Category
import com.mezze.language.typechecker.WellKnownHashes
import com.mezze.language.ast.Keywords
import com.mezze.language.parsedast.diagnostics.ErrorCode


/**
 * Constraint generator - orchestrator for constraint-based type inference.
 *
 * This is the main entry point for constraint generation.
 * Delegates to specialized generators:
 * - ExpressionConstraints: for expressions
 * - PatternConstraints: for patterns
 * - AbilityConstraints: for ability requirements
 * - EffectConstraints: for effect tracking
 *
 * Design:
 * - Pure functions (no mutation!)
 * - Returns ConstraintResult with type + constraints
 * - Solving happens separately (not here!)
 */
/**
 * Result of generating constraints for a program.
 * Contains both the per-binding results and additional metadata from per-binding solving.
 */
data class ProgramConstraintResult(
    val constraintResults: Map<String, ConstraintResult>,
    val perBindingMustBeOpenRowVars: Set<RowVar>,  // Row variables that must stay open from per-binding solving
    val perBindingSolveErrors: List<com.mezze.language.typechecker.solving.ConstraintSolver.ConstraintError> = emptyList(),  // Errors from per-binding solving
    val perBindingSubstitution: com.mezze.language.typechecker.solving.Substitution = com.mezze.language.typechecker.solving.Substitution.empty,  // Accumulated substitution from per-binding solving (for bidirectional inference)
    val collectedTypeCheckErrors: List<com.mezze.language.typechecker.TypeCheckError> = emptyList()  // TypeCheckErrors caught during recovery
) {
}

class ConstraintGenerator(
    val expressionGenerator: ExpressionConstraintGenerator,
    private val patternGenerator: PatternConstraintGenerator,
    private val abilityGenerator: AbilityConstraintGenerator,
    private val effectGenerator: EffectConstraintGenerator,
    private val registry: com.mezze.language.typechecker.context.DeclarationRegistry = com.mezze.language.typechecker.context.DeclarationRegistry.EMPTY,
    private val performerToEffect: Map<String, String> = emptyMap(),
    initialPerformerAddedEffects: Map<String, Set<String>> = emptyMap(),
    private val abilityNames: Set<String> = emptySet(),
    private val effectNames: Set<String> = emptySet(),
) {
    // Mutable map for tracking effects that performers add
    // This is populated initially from explicit effect annotations, and updated
    // during constraint generation when we discover effects from impl bodies
    private val performerAddedEffects: MutableMap<String, Set<String>> = initialPerformerAddedEffects.toMutableMap()
    // Cache for type variables within a single type annotation
    // Ensures all occurrences of 'a in an annotation refer to the same TypeVar instance
    // Matches old typechecker behavior
    private val typeVarCache = mutableMapOf<String, TypeVar>()

    // Shared row variable across all `<...>` in a single annotation. Lazily allocated.
    // See ContextBuilder.sharedAnnotationRowVar for full rationale.
    private var sharedAnnotationRowVar: RowVar? = null

    private val implValidator = ImplValidator(
        expressionGenerator, registry, performerToEffect, performerAddedEffects,
        abilityNames, effectNames, ::convertTypeAnnotation
    )
    private val annotationValidator = AnnotationValidator(registry)

    /**
     * Generate constraints for a program.
     *
     * This is the top-level entry point.
     * Returns map of binding names to their constraint results.
     *
     * Uses ALGORITHM W (incremental solving per-binding) to support let-polymorphism:
     * 1. FIRST PASS: Add placeholders for all function bindings (mutual recursion)
     * 2. SECOND PASS: For each binding:
     *    - Generate constraints
     *    - Solve constraints IMMEDIATELY
     *    - Generalize if has free variables
     *    - Add to context (polymorphic or monomorphic)
     *    - Continue to next binding
     *
     * This matches the old typechecker's approach (OldTypeChecker.kt lines 1165-1446)
     */
    fun generateProgram(
        module: ParsedModule,
        context: TypingContext
    ): ProgramConstraintResult {
        DebugLogger.debugSection(Category.ConstraintGenerator, "Abilities and Effects") {
            listOf(
                "abilityNames = $abilityNames",
                "effectNames = $effectNames"
            )
        }

        val results = mutableMapOf<String, ConstraintResult>()
        var currentContext = context

        // CRITICAL: Track record literal bindings for field access validation
        // Matches OldTypeChecker.kt:1428-1429
        val recordLiteralBindings = mutableSetOf<String>()

        // Track errors from per-binding solving
        val perBindingSolveErrors = mutableListOf<com.mezze.language.typechecker.solving.ConstraintSolver.ConstraintError>()
        // Track TypeCheckErrors caught during recovery (preserves metadata)
        val collectedTypeCheckErrors = mutableListOf<com.mezze.language.typechecker.TypeCheckError>()

        // Track accumulated substitution from per-binding solving (for bidirectional inference)
        var perBindingSubstitution = com.mezze.language.typechecker.solving.Substitution.empty

        // Import constraint solver for incremental solving
        val solver = com.mezze.language.typechecker.solving.ConstraintSolver(context, registry)

        // FIRST PASS: Add placeholders for function bindings to support mutual recursion
        // This allows functions to reference themselves and each other
        // CRITICAL: Store the converted annotations to reuse the SAME TypeVar instances in second pass!
        val letBindings = module.decls.filterIsInstance<ParsedLetDecl>()
        val placeholderNames = mutableSetOf<String>()
        val annotationTypes = mutableMapOf<ParsedLetDecl, MezzeType>()  // Store converted annotations
        for (binding in letBindings) {
            // Convert and store annotation types for ALL bindings with annotations
            // This ensures type annotations with variant/record types are preserved correctly
            binding.typeAnnotation?.let { annotation ->
                // Validate ability constraints in the annotation: every `with X 'a`
                // must reference a real ability that's in scope. Without this,
                // typos like `with FooBaz 'a` (or constraints whose ability isn't
                // imported / not in prelude) get silently dropped — the annotation
                // looks like it worked but adds zero typing power.
                if (annotation is ParsedFunctionType) {
                    for (constraint in annotation.abilityConstraints) {
                        val name = constraint.abilityName
                        val isKnown = registry.abilityDeclarations.containsKey(name) ||
                            BuiltInAbilities.getAllAbilityNames().contains(name)
                        if (!isKnown) {
                            throw com.mezze.language.typechecker.TypeCheckError(
                                "Ability '$name' referenced in 'with' constraint is not defined or not imported. " +
                                "Did you forget to import it with 'use'?",
                                constraint.loc,
                                code = ErrorCode.V0401_ABILITY_NOT_DEFINED,
                            )
                        }
                    }
                }
                val convertedType = convertTypeAnnotation(annotation)
                annotationTypes[binding] = convertedType  // Store for reuse in second pass
            }

            // Only add placeholders for simple variable bindings with lambda expressions
            if (binding.isSimpleBinding && binding.value is ParsedLambda) {
                val placeholderType = annotationTypes[binding] ?: MezzeType.VarType(TypeVar.fresh())

                currentContext = currentContext.extendMono(binding.variableName, placeholderType)
                placeholderNames.add(binding.variableName)
            }
        }

        // SECOND PASS: Process each program item with placeholders in context
        for (item in module.decls) {
            when (item) {
                is ParsedLetDecl -> try {
                    // Reuse the converted annotation type from first pass to ensure same TypeVar instances
                    val preConvertedAnnotationType = annotationTypes[item]
                    val result = generateLetBinding(item, currentContext, recordLiteralBindings, preConvertedAnnotationType)

                    // CRITICAL: Track record literal bindings (matches OldTypeChecker.kt:1428-1429)
                    if (item.pattern is ParsedVariablePattern && item.value is ParsedRecordLit) {
                        recordLiteralBindings.add((item.pattern as ParsedVariablePattern).name)
                    }

                    // Extend context with pattern bindings for subsequent items
                    // For simple variable bindings: extends with one binding
                    // For destructuring patterns: extends with multiple bindings
                    val constraintContext = ConstraintContext(currentContext, performerToEffect = performerToEffect, performerAddedEffects = performerAddedEffects, abilityNames = abilityNames, effectNames = effectNames, effectDeclarations = registry.effectDeclarations, abilityDeclarations = registry.abilityDeclarations, typeDeclarations = registry.typeDeclarations)
                    val patternResult = patternGenerator.generate(
                        item.pattern,
                        result.type,
                        constraintContext,
                        item.value.loc
                    )

                    // Check for duplicate bindings BEFORE extending context
                    // Skip bindings that were added as placeholders (they're being replaced, not duplicated)
                    val duplicateBindings = patternResult.bindings.keys
                        .filter { name -> currentContext.hasLocal(name) && !placeholderNames.contains(name) }
                        .map { name ->
                            ConstraintResult.DuplicateBinding(
                                name = name,
                                loc = patternResult.bindingLocations[name]
                            )
                        }

                    // Add duplicate bindings to the result
                    val resultWithDuplicates = result.copy(
                        duplicateBindings = duplicateBindings
                    )


                    results[getBindingName(item)] = resultWithDuplicates

                    // ALGORITHM W: Solve constraints for THIS binding immediately
                    // CRITICAL: Include ability constraints so they get validated with the proper substitution!
                    // Without this, ability constraints like NumAdd 'a are only validated in global solving,
                    // but by then the type variable chain has changed and 'a doesn't resolve to the concrete type.
                    // CRITICAL FIX: Also include patternResult.constraints to solve HasField constraints
                    // from destructuring patterns! Without these, bindings from {x = y} patterns stay as
                    // unconstrained type variables instead of being unified with actual field types.
                    val allBindingConstraints = result.constraints + result.abilityConstraints + result.associatedTypeConstraints + patternResult.constraints
                    val solveResult = solver.solve(allBindingConstraints)
                    when (solveResult) {
                        is com.mezze.language.typechecker.solving.ConstraintSolver.SolveResult.Failure -> {
                            // Solving failed - collect the errors for propagation
                            perBindingSolveErrors.addAll(solveResult.errors)

                            // STILL add binding to context with its unsolved type
                            // so subsequent bindings can reference it (even if the type is wrong)
                            // This prevents cascading "undefined variable" errors
                            results[getBindingName(item)] = resultWithDuplicates

                            // Add binding to context with unsolved type
                            // Pass contentHash for simple bindings (single variable pattern)
                            val bindingHash: com.mezze.language.typedast.Hash? = null  // ParsedLetDecl has no contentHash
                            currentContext = currentContext.extendAll(
                                patternResult.bindings.map { (name, type) ->
                                    BindingKey.unqualified(name) to TypingContext.Binding.Monomorphic(type, contentHash = bindingHash)
                                }.toMap()
                            )
                            continue
                        }
                        is com.mezze.language.typechecker.solving.ConstraintSolver.SolveResult.Success -> {
                            val substitution = solveResult.substitution
                            // NOTE: We do NOT accumulate substitution from regular bindings.
                            // Only TopLevelExpression substitutions are accumulated for bidirectional type inference.
                            // Accumulating from all bindings causes conflicts with type aliases (e.g., Ordering).

                            // CRITICAL: If there's a type annotation, use the ANNOTATED type (with substitution applied)
                            // as the authoritative type. This ensures closed variant/record types from annotations
                            // override open inferred types.
                            // IMPORTANT: Use annotation even if it has type variables (polymorphic) - generalization
                            // happens AFTER solving constraints. This matches OldTypeChecker.kt lines 1226-1411.
                            // IMPORTANT: Preserve TypeApplication from annotations (don't expand user-defined type aliases)
                            val authoritativeType = if (item.pattern is ParsedVariablePattern && preConvertedAnnotationType != null) {
                                // CRITICAL: If annotation was a TypeApplication (type alias), check if it's user-defined
                                // Only preserve USER-DEFINED type aliases, not built-in types like Seq, List, etc.
                                // This ensures "let x: Person = ..." has type "Person", not "{ name: String, ... }"
                                if (preConvertedAnnotationType is MezzeType.TypeApplication) {
                                    // Check if this is a user-defined type alias (exists in typeDeclarations)
                                    val isUserDefinedAlias = registry.typeDeclarations.containsKey(preConvertedAnnotationType.constructor)

                                    if (isUserDefinedAlias) {
                                        // User-defined type alias - preserve it, apply substitution to type arguments only
                                        com.mezze.language.typechecker.util.WellKnownTypeApp.of(
                                            constructor = preConvertedAnnotationType.constructor,
                                            args = preConvertedAnnotationType.args.map { substitution.apply(it) },
                                            typeHash = preConvertedAnnotationType.typeHash
                                        )
                                    } else {
                                        // Built-in type (Seq, List, etc.) - apply substitution normally
                                        // This allows Seq Int to be treated as SeqType(IntType)
                                        substitution.apply(preConvertedAnnotationType)
                                    }
                                } else {
                                    // For other types, apply substitution normally
                                    substitution.apply(preConvertedAnnotationType)
                                }
                            } else {
                                substitution.apply(result.type)
                            }

                            // VALIDATION: Check annotation matches inferred type
                            // Only validate specific aspects when the annotation explicitly declares them
                            // throw Error("[DEBUG PRE-VALIDATION] REACHED - item.pattern is VariablePattern=${item.pattern is VariablePattern}")
                            if (item.pattern is ParsedVariablePattern && preConvertedAnnotationType != null) {
                                val annotation = item.typeAnnotation
                                val varName = (item.pattern as ParsedVariablePattern).name

                                // Build inferred type with abilities for validation
                                val inferredTypeRaw = substitution.apply(result.type)
                                val inferredTypeWithAbilities = addAbilityConstraintsToFunctionType(
                                    inferredTypeRaw,
                                    result.abilityConstraints,
                                    substitution
                                )

                                // 1. POLYMORPHISM VALIDATION
                                // Check that type variables in annotation are not forced to FULLY concrete types.
                                // Only flag when substitution maps type var to something with NO type variables.
                                if (annotation is ParsedFunctionType) {
                                    // Collect type vars that have ability constraints - these may be
                                    // mapped to concrete types by bidirectional inference and should be skipped
                                    val abilityConstrainedVars = result.abilityConstraints.map { it.typeVar }.toSet()
                                    annotationValidator.validatePolymorphismPreserved(
                                        preConvertedAnnotationType,
                                        substitution,
                                        varName,
                                        item.value.loc,
                                        abilityConstrainedVars
                                    )
                                }

                                // 2. EFFECTS VALIDATION
                                // Annotation must not declare effects the inferred type
                                // doesn't carry. With #312's call-site closure-effect
                                // harvest (in generateFunctionCall — when the callee's
                                // effect row has a row var, effects from closure-typed
                                // argument fields get unioned into propagatedEffects),
                                // the inferred type now picks up effects passed via
                                // HOFs like `Async.spawn { f = effectful_thing }`.
                                if (annotation is ParsedFunctionType &&
                                    annotation.effects != null) {
                                    annotationValidator.validateEffectsNotOverpromised(
                                        preConvertedAnnotationType,
                                        inferredTypeWithAbilities,
                                        varName,
                                        item.value.loc
                                    )
                                }

                                // 3. ABILITY VALIDATION
                                // Check that abilities are applied to correct type variables.
                                // This catches cases like: annotation declares NumAdd 'b but impl uses NumAdd 'a
                                if (annotation is ParsedFunctionType && annotation.abilityConstraints.isNotEmpty()) {
                                    // Apply substitution to ability constraints to map fresh type vars back
                                    // The substitution may map in either direction (annotation->inferred or inferred->annotation)
                                    // so we try to resolve in both directions
                                    val resolvedAbilityConstraints = result.abilityConstraints.map { constraint ->
                                        val resolvedTypeVar = substitution.apply(MezzeType.VarType(constraint.typeVar))
                                        if (resolvedTypeVar is MezzeType.VarType) {
                                            constraint.copy(typeVar = resolvedTypeVar.variable)
                                        } else {
                                            constraint
                                        }
                                    }
                                    annotationValidator.validateAbilityOnCorrectTypeVar(
                                        preConvertedAnnotationType,
                                        resolvedAbilityConstraints,
                                        varName,
                                        substitution,
                                        item.value.loc
                                    )
                                }
                            }

                            // Apply substitution to get final solved types for pattern bindings
                            val solvedBindings = if (item.pattern is ParsedVariablePattern && authoritativeType != substitution.apply(result.type)) {
                                // For simple variable bindings where annotation differs from inferred type (e.g., closed vs open variants),
                                // use the authoritative type (the annotation wins, whether monomorphic or polymorphic)
                                mapOf(item.variableName to authoritativeType)
                            } else {
                                // For complex patterns or when types match, apply substitution to pattern bindings
                                patternResult.bindings.mapValues { (_, type) ->
                                    substitution.apply(type)
                                }
                            }

                            // CRITICAL: Add ability constraints to FunctionType before generalization
                            // This matches old typechecker's collectAbilityConstraintsForFunctionType (line 2606)
                            val solvedBindingsWithAbilities = solvedBindings.mapValues { (name, solvedType) ->
                                addAbilityConstraintsToFunctionType(solvedType, result.abilityConstraints, substitution)
                            }

                            // CRITICAL: Wrap types in SuspendedType if the binding has effects OR performed marker
                            // This allows subsequent lookups to see the effects
                            // Example: let program = Log.info {...} should have type {} <Log>, not just {}
                            // IMPORTANT: Preserve effectInstances (with associated types like 'error = String)
                            // so that perform expressions can properly resolve suspension types
                            // ALSO IMPORTANT: If performed=true (from perform expressions), wrap in SuspendedType
                            // with performed marker so top-level validation can reject it
                            val hasEffectsOrPerformed = result.effects.isNotEmpty() || result.effectInstances.isNotEmpty() || result.performed
                            val solvedBindingsWithEffects = if (hasEffectsOrPerformed) {
                                solvedBindingsWithAbilities.mapValues { (_, solvedType) ->
                                    when (solvedType) {
                                        is MezzeType.SuspendedType -> {
                                            // Already suspended - but make sure performed flag is set if needed
                                            if (result.performed && !solvedType.effects.performed) {
                                                MezzeType.SuspendedType(
                                                    valueType = solvedType.valueType,
                                                    effects = MezzeType.EffectRow(
                                                        effects = solvedType.effects.effects,
                                                        effectInstances = solvedType.effects.effectInstances,
                                                        rowVar = solvedType.effects.rowVar,
                                                        performed = true
                                                    )
                                                )
                                            } else {
                                                solvedType
                                            }
                                        }
                                        is MezzeType.FunctionType -> solvedType    // Functions have effects in signature
                                        else -> MezzeType.SuspendedType(
                                            valueType = solvedType,
                                            effects = MezzeType.EffectRow(
                                                effects = result.effects,
                                                effectInstances = result.effectInstances,
                                                rowVar = null,
                                                performed = result.performed
                                            )
                                        )
                                    }
                                }
                            } else {
                                solvedBindingsWithAbilities
                            }

                            // GENERALIZATION: Check if bindings should be polymorphic
                            // Convert pattern bindings to context bindings with generalization
                            // Note: pattern bindings are local (unqualified) names
                            val contextBindings = solvedBindingsWithEffects.map { (name, solvedType) ->
                                val bindingKey = BindingKey.unqualified(name)
                                bindingKey to run {
                                // Collect free type variables in the solved type
                                val freeVars = com.mezze.language.typechecker.context.collectFreeTypeVars(solvedType)

                                // Get free type variables from current context
                                // CRITICAL: Exclude the binding being generalized from context vars calculation
                                // This prevents the placeholder's type vars from blocking generalization
                                val contextVars = getContextFreeVars(currentContext, excludeBindings = setOf(bindingKey))

                                // Quantify variables that don't appear in context
                                val quantifiableVars = freeVars - contextVars

                                // CRITICAL FIX: Don't generalize if there are USER-DEFINED ability constraints
                                // on the quantifiable variables. This prevents premature generalization that
                                // breaks type inference when constraints are satisfied later.
                                //
                                // Example: let x = Parse.decode ... should NOT be generalized to forall 'a. Parse 'a => 'a
                                // because when x is used in "10 + x", we need the SAME type variable to unify with Int.
                                //
                                // BUT: Built-in abilities like NumAdd, Eq, Ord SHOULD be generalized!
                                // Example: let addTwo = { x } -> x + x SHOULD be forall 'a. NumAdd 'a => { x: 'a } -> 'a
                                // because NumAdd has built-in implementations for all numeric types.
                                //
                                // We only prevent generalization for user-defined abilities that need explicit implementations.
                                // EXCEPTION: If the user explicitly declared ability constraints in their type annotation,
                                // we MUST allow generalization. The user is explicitly telling us "this is polymorphic with these constraints".
                                // Example: let encode_value: {x: 'a} with Encoder 'a -> String = ...
                                // The user explicitly wants this to be polymorphic over 'a with Encoder 'a constraint.
                                val hasExplicitAbilityConstraints = when (val annotation = item.typeAnnotation) {
                                    is ParsedFunctionType -> annotation.abilityConstraints.isNotEmpty()
                                    else -> false
                                }

                                // Lambda bindings (functions) should ALWAYS allow generalization
                                // with ability constraints. Only VALUE bindings (let x = Parse.decode ...)
                                // should block generalization, because their type variable represents
                                // a single concrete type to be inferred from usage context.
                                val isLambdaBinding = item.value is com.mezze.language.parsedast.ParsedLambda

                                val hasUserDefinedAbilityConstraints = if (hasExplicitAbilityConstraints || isLambdaBinding) {
                                    // User explicitly declared ability constraints OR binding is a function
                                    // — allow generalization (function is polymorphic over its constraint)
                                    false
                                } else {
                                    result.abilityConstraints.any { constraint ->
                                    // Check if this is a built-in ability with built-in implementations
                                    val isBuiltInAbility = BuiltInAbilities.isBuiltInAbility(constraint.abilityName)

                                    if (isBuiltInAbility) {
                                        // Built-in abilities like NumAdd, Eq, etc. should allow generalization
                                        false
                                    } else {
                                        // User-defined ability - check if the type variable OR any typeArgs are quantifiable
                                        // Apply substitution to handle 'a -> 'b chains
                                        val resolvedType = substitution.apply(MezzeType.VarType(constraint.typeVar))
                                        val resolvedTypeVar = if (resolvedType is MezzeType.VarType) {
                                            resolvedType.variable
                                        } else {
                                            // If resolved to concrete type, constraint is satisfied
                                            null
                                        }
                                        val mainVarIsQuantifiable = resolvedTypeVar != null && quantifiableVars.contains(resolvedTypeVar)

                                        // CRITICAL FIX: Also check typeArgs for multi-parameter abilities like Decode
                                        // If ANY type argument contains a quantifiable variable, don't generalize.
                                        // This ensures bidirectional type inference works for abilities like Decode
                                        // where the target type ('a in Result 'a Error) needs to stay monomorphic
                                        // so later usage (val.name) can constrain it.
                                        val typeArgsHaveQuantifiableVars = constraint.typeArgs.any { typeArg ->
                                            val freeVars = com.mezze.language.typechecker.context.collectFreeTypeVars(typeArg)
                                            freeVars.any { freeVar ->
                                                val resolved = substitution.apply(MezzeType.VarType(freeVar))
                                                if (resolved is MezzeType.VarType) {
                                                    quantifiableVars.contains(resolved.variable)
                                                } else {
                                                    false
                                                }
                                            }
                                        }

                                        mainVarIsQuantifiable || typeArgsHaveQuantifiableVars
                                    }
                                    }
                                }

                                if (quantifiableVars.isNotEmpty() && !hasUserDefinedAbilityConstraints) {
                                    // Polymorphic binding - generalize into TypeScheme
                                    // Extract ability constraints for quantified variables

                                    // CRITICAL: If the user explicitly declared ability constraints in the type annotation,
                                    // use THOSE directly instead of inferring from expression constraints.
                                    // The annotation constraints have the complete type args (e.g., [Json, 'a] for Encode Json 'a)
                                    // while expression constraints from method calls only have partial info.
                                    //
                                    // Use the already-converted FunctionType's abilityConstraints to get the correct TypeVars
                                    // (not re-convert from annotation, which would create different TypeVars)
                                    val abilityConstraints = if (hasExplicitAbilityConstraints && authoritativeType is MezzeType.FunctionType) {
                                        val funcType = authoritativeType
                                        funcType.abilityConstraints.mapNotNull { constraint ->
                                            // Find the type variable in the args - that's the constrained parameter
                                            val typeVarArg = constraint.typeArgs.filterIsInstance<MezzeType.VarType>().firstOrNull()

                                            if (typeVarArg != null) {
                                                // Keep ALL type args in their original order (including the constrained var)
                                                // For multi-parameter abilities like "Encode Json 'a", typeArgs = [Json, 'a]
                                                // For single-parameter abilities like "Show 'a", typeArgs = ['a]
                                                // The solver's solveAbilityRequired will use typeArgs directly when
                                                // typeVarInArgs is true, preserving the correct order
                                                AbilityConstraint(
                                                    abilityName = constraint.abilityName,
                                                    typeParam = typeVarArg.variable.name,
                                                    typeArgs = constraint.typeArgs
                                                )
                                            } else {
                                                null
                                            }
                                        }
                                    } else {
                                        // Infer from expression constraints
                                        // CRITICAL: Compare by ID since TypeVar instances may differ even for same logical variable
                                        val quantifiableVarIds = quantifiableVars.map { it.id }.toSet()
                                        result.abilityConstraints.mapNotNull { constraint ->
                                            // Check if constraint's typeVar matches any quantifiable var by ID
                                            val resolvedConstraintVar = substitution.apply(MezzeType.VarType(constraint.typeVar))
                                            val constraintVarId = if (resolvedConstraintVar is MezzeType.VarType) {
                                                resolvedConstraintVar.variable.id
                                            } else {
                                                constraint.typeVar.id
                                            }
                                            if (quantifiableVarIds.contains(constraintVarId)) {
                                                // Find the actual quantifiable var to get its name
                                                val quantVar = quantifiableVars.find { it.id == constraintVarId }
                                                // Apply substitution to typeArgs in case they contain type variables
                                                val resolvedTypeArgs = constraint.typeArgs.map { substitution.apply(it) }
                                                AbilityConstraint(
                                                    abilityName = constraint.abilityName,
                                                    typeParam = quantVar?.name ?: constraint.typeVar.name,
                                                    typeArgs = resolvedTypeArgs
                                                )
                                            } else null
                                        }
                                    }

                                    val scheme = TypeScheme(
                                        quantifiedVars = quantifiableVars.toList(),
                                        constraints = abilityConstraints,
                                        type = solvedType
                                    )
                                    // ParsedLetDecl has no contentHash
                                    TypingContext.Binding.Polymorphic(scheme, contentHash = null)
                                } else {
                                    // Monomorphic binding - either no free variables or has unsatisfied ability constraints
                                    // ParsedLetDecl has no contentHash
                                    TypingContext.Binding.Monomorphic(solvedType, contentHash = null)
                                }
                            }}.toMap()

                            // Update context with final inferred types (replacing placeholders)
                            // Polymorphic bindings will be instantiated at use sites
                            currentContext = currentContext.extendAll(contextBindings)

                            // CRITICAL FIX: Update results map with types that have ability constraints and effects.
                            // TypedAstBuilder and TypeValidator read from this map.
                            for ((bindingName, solvedType) in solvedBindingsWithEffects) {
                                if (results.containsKey(bindingName)) {
                                    val existingResult = results[bindingName]!!
                                    results[bindingName] = existingResult.copy(type = solvedType)
                                }
                            }
                        }
                    }
                } catch (e: com.mezze.language.typechecker.TypeCheckError) {
                    // Error recovery: collect the original error (preserves metadata), continue
                    collectedTypeCheckErrors.add(if (e.loc == null) com.mezze.language.typechecker.TypeCheckError(e.message ?: "Type error", item.loc, e.errorType, e.metadata, code = e.code, hints = e.hints, fixHints = e.fixHints) else e)
                    if (item.isSimpleBinding) {
                        currentContext = currentContext.extendMono(item.variableName, MezzeType.VarType(TypeVar.fresh()))
                    }
                }
                is ParsedTypeDecl,
                is ParsedAbilityDecl,
                is ParsedEffectDecl -> {
                    // These are handled by ContextBuilder in Phase 1
                    // Nothing to do here
                }
                is ParsedPerformerDecl -> try {
                    // CRITICAL: Validate effect implementation bodies
                    // ContextBuilder registers the performer in Phase 1
                    // Here in Phase 2, we type-check the implementation bodies
                    implValidator.validateEffectImplementation(item, currentContext)
                } catch (e: com.mezze.language.typechecker.TypeCheckError) {
                    collectedTypeCheckErrors.add(if (e.loc == null) com.mezze.language.typechecker.TypeCheckError(e.message ?: "Type error", item.loc, e.errorType, e.metadata, code = e.code, hints = e.hints, fixHints = e.fixHints) else e)
                }
                is ParsedImplAbilityDecl -> try {
                    // CRITICAL: Validate ability implementation bodies
                    // ContextBuilder registers the implementation in Phase 1
                    // Here in Phase 2, we type-check the implementation bodies
                    implValidator.validateAbilityImplementation(item, currentContext)

                    // For inherent impls, generate constraints for each operation body
                    if (item.isInherent) {
                        val targetTypeName = item.targetTypes.firstOrNull()?.let { ann ->
                            when (ann) {
                                is ParsedNamedType -> ann.name
                                is ParsedTypeApp -> ann.constructor
                                else -> null
                            }
                        }
                        val targetTypeHash = targetTypeName?.let { registry.typeDeclarations[it]?.contentHash }

                        for (op in item.operations) {
                            // Each operation gets a FRESH target type to prevent type variable leakage
                            val targetType = if (item.targetTypes.isNotEmpty()) {
                                convertTypeAnnotation(item.targetTypes.first())
                            } else {
                                MezzeType.VarType(TypeVar.fresh())
                            }

                            if (op.implementation !is ParsedLambda) continue
                            val lambda = op.implementation as ParsedLambda

                            // Hash-first inherent method lookup, fall back to name
                            val methodInfo = if (targetTypeHash != null) {
                                currentContext.findAnonymousMethodByHash(targetTypeHash, op.name)
                                    ?: targetTypeName?.let { currentContext.findAnonymousMethod(it, op.name) }
                            } else if (targetTypeName != null) {
                                currentContext.findAnonymousMethod(targetTypeName, op.name)
                            } else null

                            val paramNames = when (val params = lambda.params) {
                                is ParsedLambdaParams.Untyped -> params.names
                                is ParsedLambdaParams.Typed -> params.fields.map { it.name }
                            }

                            val paramTypes = if (op.paramType != null) {
                                val expectedParam = convertTypeAnnotation(op.paramType!!)
                                if (expectedParam is MezzeType.RecordType) {
                                    paramNames.associateWith { name ->
                                        expectedParam.fields[name] ?: MezzeType.VarType(TypeVar.fresh())
                                    }
                                } else {
                                    paramNames.associateWith { expectedParam }
                                }
                            } else {
                                when (val params = lambda.params) {
                                    is ParsedLambdaParams.Typed -> params.fields.associate { entry ->
                                        entry.name to convertTypeAnnotation(entry.type)
                                    }
                                    else -> paramNames.associateWith { name ->
                                        // The receiver param `it` must be bound to the impl's
                                        // target type so `it.<field>` / `it.<method>` dispatch
                                        // sees the nominal type — not a free TyVar that the
                                        // body's HasField constraints would resolve to a row-
                                        // open structural record. Without this, the home-module
                                        // typecheck of `impl Resp where { is_ok = { it } -> ... }`
                                        // registers `is_ok : { it: 'h } -> Bool` and importers
                                        // can't tell which impl is "concrete" vs "generic".
                                        if (name == Keywords.IT) targetType
                                        else MezzeType.VarType(TypeVar.fresh())
                                    }
                                }
                            }

                            var bodyContext = currentContext
                            // `it` is now explicit in inherent impl bodies — no implicit injection
                            for ((name, type) in paramTypes) {
                                bodyContext = bodyContext.extendMono(name, type)
                            }

                            val constraintContext = ConstraintContext(
                                bodyContext,
                                expectedType = null,
                                performerToEffect = performerToEffect,
                                performerAddedEffects = performerAddedEffects,
                                abilityNames = abilityNames,
                                effectNames = effectNames,
                                effectDeclarations = registry.effectDeclarations,
                                abilityDeclarations = registry.abilityDeclarations,
                                typeDeclarations = registry.typeDeclarations
                            )

                            val bodyResult = expressionGenerator.generate(lambda.body, constraintContext)

                            val constraints = bodyResult.constraints.toMutableList()

                            val registeredMethodType = methodInfo?.methodType
                            if (registeredMethodType is MezzeType.FunctionType) {
                                constraints.add(Constraint.Equality(
                                    left = registeredMethodType.returnType,
                                    right = bodyResult.type,
                                    context = "Inherent impl return type for ${op.name}",
                                    loc = op.loc,
                                ))
                                val paramRecordType = MezzeType.RecordType(fields = paramTypes, rowVar = null)
                                constraints.add(Constraint.Equality(
                                    left = registeredMethodType.paramType,
                                    right = paramRecordType,
                                    context = "Inherent impl param type for ${op.name}",
                                    loc = op.loc,
                                ))
                            }

                            // ParsedImplAbilityDecl has no contentHash — use loc-based key
                            val opKey = "_inherent_${item.loc}_${op.name}"
                            // Stash the full FunctionType so TypedAstBuilder
                            // can recover post-solve PARAM types too — issue
                            // #230. The body type alone (used by #213 for
                            // returns) doesn't include the impl's lambda
                            // param TypeVars, which the solver populated
                            // when the body's usage sites constrained them.
                            //
                            // #180: also include the body's inferred effect
                            // row. Without this, an impl method whose body
                            // performs an effect (e.g.
                            //   send = { it } -> Http.request { ... }
                            // ) is registered as a pure FunctionType. The
                            // call site `r.send {}` then looks effect-free
                            // and any surrounding perform-block complains
                            // "Expression is pure". The body's effects are
                            // already computed at this point (bodyResult.
                            // effects / effectInstances) — thread them in.
                            val implParamRecord = MezzeType.RecordType(fields = paramTypes, rowVar = null)
                            val bodyEffectRow: MezzeType.EffectRow? =
                                if (bodyResult.effects.isEmpty() && bodyResult.effectInstances.isEmpty()) {
                                    null
                                } else {
                                    MezzeType.EffectRow.simple(
                                        effects = bodyResult.effects,
                                        rowVar = null,
                                        performed = false
                                    ).copy(effectInstances = bodyResult.effectInstances)
                                }
                            val implFunctionType = MezzeType.FunctionType(
                                paramType = implParamRecord,
                                returnType = bodyResult.type,
                                effects = bodyEffectRow
                            )
                            // #180: refresh the registered InherentMethodInfo
                            // with the body-derived FunctionType. The original
                            // entry registered by ContextBuilder.addAnonymousImpl
                            // was built before the body was constraint-generated,
                            // so its methodType lacks the body's inferred effect
                            // row. Subsequent call sites read methodInfo.methodType
                            // → wrapCallResultWithEffects, so the effects must be
                            // present here for `r.send {}` to look effectful.
                            if (methodInfo != null && bodyEffectRow != null) {
                                val refreshedInfo = methodInfo.copy(methodType = implFunctionType)
                                currentContext = currentContext.extendWithAnonymousMethod(refreshedInfo)
                            }
                            results[opKey] = ConstraintResult(
                                type = implFunctionType,
                                constraints = constraints,
                                effects = bodyResult.effects,
                                effectInstances = bodyResult.effectInstances,
                                abilityConstraints = bodyResult.abilityConstraints,
                                associatedTypeConstraints = bodyResult.associatedTypeConstraints,
                                undefinedVariables = emptyList(),
                                usedVariables = bodyResult.usedVariables,
                                // #182: propagate the body's per-loc inferred
                                // types so TypedAstBuilder can recover post-
                                // solve types for variant constructions and
                                // calls inside the impl method body. Without
                                // this, sibling no-payload variants in the
                                // body (e.g. `{ method = Get, body = None }`)
                                // collapse onto the shared default
                                // `TypeVar("unknown", 0)` and lose their
                                // resolved types.
                                callReturnTypes = bodyResult.callReturnTypes,
                                methodCallResolutions = bodyResult.methodCallResolutions
                            )
                        }
                    }
                } catch (e: com.mezze.language.typechecker.TypeCheckError) {
                    collectedTypeCheckErrors.add(if (e.loc == null) com.mezze.language.typechecker.TypeCheckError(e.message ?: "Type error", item.loc, e.errorType, e.metadata, code = e.code, hints = e.hints, fixHints = e.fixHints) else e)
                }
                is ParsedTopLevelExpr -> {
                    // Top-level expressions (for REPL)
                    val constraintContext = ConstraintContext(currentContext, performerToEffect = performerToEffect, performerAddedEffects = performerAddedEffects, abilityNames = abilityNames, effectNames = effectNames, effectDeclarations = registry.effectDeclarations, abilityDeclarations = registry.abilityDeclarations, typeDeclarations = registry.typeDeclarations)
                    val result = expressionGenerator.generate(
                        item.expression,
                        constraintContext
                    )
                    results["_toplevel"] = result

                    // BIDIRECTIONAL TYPE INFERENCE: Solve HasField and HasConstructor constraints
                    // for ParsedTopLevelExpr to enable type inference from usage sites.
                    // For example, `val.name` creates a HasField constraint that binds the type variable.
                    //
                    // We solve these constraints here (per-binding) rather than globally because:
                    // 1. The global solver filters out HasField/HasConstructor to avoid interference
                    // 2. We need the substitution to contain the bindings for bidirectional inference
                    //
                    // IMPORTANT: We ignore solving errors here because some constraints may involve
                    // types that can't be expanded (e.g., type aliases like CodecConfig). These will
                    // be handled during validation or produce more meaningful errors elsewhere.
                    val structuralConstraints = result.constraints.filter { constraint ->
                        constraint is Constraint.HasField || constraint is Constraint.HasConstructor
                    }
                    if (structuralConstraints.isNotEmpty()) {
                        // Solve constraints but don't fail on errors - just accumulate substitutions
                        val solveResult = solver.solve(structuralConstraints)
                        // Accumulate the substitution from successful solving
                        if (solveResult is com.mezze.language.typechecker.solving.ConstraintSolver.SolveResult.Success) {
                            perBindingSubstitution = solveResult.substitution.compose(perBindingSubstitution)
                        }
                        // We don't add errors to perBindingSolveErrors - the global solver will catch real issues
                    }
                }
                is ParsedUseDecl,
                is ParsedPubUseDecl -> {
                    // Module imports - handled elsewhere
                }
            }
        }

        // Return results with accumulated mustBeOpenRowVars and errors from per-binding solving
        return ProgramConstraintResult(
            constraintResults = results,
            perBindingMustBeOpenRowVars = solver.getMustBeOpenRowVars(),
            perBindingSolveErrors = perBindingSolveErrors,
            perBindingSubstitution = perBindingSubstitution,
            collectedTypeCheckErrors = collectedTypeCheckErrors
        )
    }

    /**
     * Generate constraints for a let binding.
     *
     * Handles both simple variable bindings and pattern destructuring:
     * - let x = expr          (simple variable)
     * - let {x, y} = expr     (record destructuring)
     * - let Some {val = x} = expr  (constructor pattern)
     *
     * @param preConvertedAnnotationType Pre-converted type annotation from first pass (for placeholder consistency)
     */
    private fun generateLetBinding(
        binding: ParsedLetDecl,
        context: TypingContext,
        recordLiteralBindings: Set<String>,
        preConvertedAnnotationType: MezzeType? = null
    ): ConstraintResult {
        DebugLogger.debug(Category.ConstraintGenerator) {
            val exprInfo = if (binding.value is ParsedIdentifier) {
                " Identifier='${(binding.value as ParsedIdentifier).localName}'"
            } else ""
            "generateLetBinding: binding=${binding.variableName} expr=${binding.value.javaClass.simpleName}$exprInfo"
        }

        // BIDIRECTIONAL TYPING: If there's a type annotation, use it as the expected type
        // This is CRITICAL for polymorphic functions to work correctly
        // Matches old typechecker's approach (OldTypeChecker.kt line 4055-4169)
        // CRITICAL: Use pre-converted type if available to ensure same TypeVar instances as placeholders!
        val expectedType = preConvertedAnnotationType ?: binding.typeAnnotation?.let { convertTypeAnnotation(it) }

        // TCO: Set enclosing function name if this binding is a function (lambda expression)
        // This allows tail call detection for self-recursive functions
        val bindingName = getBindingName(binding)
        val enclosingFunctionName = if (binding.value is ParsedLambda) bindingName else null

        // Collect ability names declared in the binding's `with X 'a` annotation.
        // Method-call resolution uses these to disambiguate when multiple
        // abilities declare the same method (#304) — the user's explicit
        // declaration wins over a coincidentally-named prelude ability.
        val annotationAbilityNames = (binding.typeAnnotation as? ParsedFunctionType)
            ?.abilityConstraints
            ?.map { it.abilityName }
            ?.toSet()
            .orEmpty()

        val constraintContext = ConstraintContext(
            context,
            expectedType = expectedType,
            performerToEffect = performerToEffect,
            performerAddedEffects = performerAddedEffects,
            abilityNames = abilityNames,
            effectNames = effectNames,
            effectDeclarations = registry.effectDeclarations,
            abilityDeclarations = registry.abilityDeclarations,
            recordLiteralBindings = recordLiteralBindings,
            typeDeclarations = registry.typeDeclarations,
            currentBindingName = bindingName,
            enclosingFunctionName = enclosingFunctionName,
            annotationAbilityNames = annotationAbilityNames
        )

        // Generate constraints for the expression with expected type
        val exprResult = expressionGenerator.generate(
            binding.value,
            constraintContext
        )

        // CRITICAL: Unwrap SuspendedType for let bindings.
        // Data types (Result, Option, etc.) are orthogonal to effects.
        // When we write `let res = effectfulFunc {}`, we want `res` to be the
        // unwrapped value type, not Suspended<T>. The effects are tracked
        // separately and will be handled by perform.
        val bindingType = when (val t = exprResult.type) {
            is MezzeType.SuspendedType -> t.valueType
            else -> t
        }

        // Generate pattern constraints
        // The pattern must match the unwrapped expression type
        val patternResult = patternGenerator.generate(
            binding.pattern,
            bindingType,
            constraintContext,
            binding.value.loc
        )

        // Combine expression constraints with pattern constraints
        val allConstraints = exprResult.constraints + patternResult.constraints

        // If there's a type annotation, add equality constraint and ability constraints
        val typeAnnotationValue = binding.typeAnnotation
        val (finalConstraints, abilityConstraints) = if (typeAnnotationValue != null && expectedType != null) {
            // CRITICAL: Reuse expectedType instead of converting again!
            // If we convert twice, we get different TypeVar instances, breaking polymorphism
            val annotatedType = expectedType

            // VALIDATION: Check annotation against expression BEFORE adding equality constraint
            // This catches errors that the constraint solver would silently accept
            annotationValidator.validateAnnotationAgainstExpression(binding, annotatedType, typeAnnotationValue, binding.value, exprResult.type)

            // Extract ability constraints from annotation if it's a function type.
            // CRITICAL: Use the TypeVars from the pre-converted annotation type (preConvertedAnnotationType)
            // rather than reconverting from the ParsedFunctionType. Reconverting creates NEW TypeVar instances
            // (different IDs) that are never entered into the substitution, causing false positives in
            // validateAbilityOnCorrectTypeVar.
            val annotationAbilityConstraints = if (typeAnnotationValue is ParsedFunctionType) {
                val preConvertedFnType = annotatedType as? MezzeType.FunctionType
                if (preConvertedFnType != null) {
                    // Use abilityConstraints directly from the pre-converted FunctionType.
                    // These TypeVars are the same instances used in the annotation equality constraint,
                    // so they will be properly resolved through the substitution.
                    preConvertedFnType.abilityConstraints.mapNotNull { constraint ->
                        val typeVarArg = constraint.typeArgs.filterIsInstance<MezzeType.VarType>().firstOrNull()
                        if (typeVarArg != null) {
                            Constraint.AbilityRequired(
                                typeVar = typeVarArg.variable,
                                abilityName = constraint.abilityName,
                                abilityHash = registry.abilityDeclarations[constraint.abilityName]?.contentHash,
                                typeArgs = constraint.typeArgs,
                                context = "Type annotation on ${getBindingName(binding)}",
                                loc = binding.loc,
                            )
                        } else {
                            null
                        }
                    }
                } else {
                    // Fallback: reconvert from parsed annotation (legacy path)
                    typeAnnotationValue.abilityConstraints.mapNotNull { constraint ->
                        val allTypeArgs = constraint.typeArgs.map { convertTypeAnnotation(it) }
                        val typeVarArg = allTypeArgs.filterIsInstance<MezzeType.VarType>().firstOrNull()
                        if (typeVarArg != null) {
                            Constraint.AbilityRequired(
                                typeVar = typeVarArg.variable,
                                abilityName = constraint.abilityName,
                                abilityHash = registry.abilityDeclarations[constraint.abilityName]?.contentHash,
                                typeArgs = allTypeArgs,
                                context = "Type annotation on ${getBindingName(binding)}",
                                loc = binding.loc
                            )
                        } else {
                            null
                        }
                    }
                }
            } else {
                emptyList()
            }

            val bindingName = getBindingName(binding)
            val constraintsWithAnnotation = allConstraints + Constraint.Equality(
                left = exprResult.type,
                right = annotatedType,
                context = "Type annotation on $bindingName",
                loc = binding.value.loc,
                errorContext = ErrorContext.TypeAnnotation(bindingName = bindingName)
            )

            Pair(constraintsWithAnnotation, exprResult.abilityConstraints + annotationAbilityConstraints)
        } else {
            Pair(allConstraints, exprResult.abilityConstraints)
        }

        // expectedTypeAt: record what the user-typed body must produce, so the
        // LSP ranker can prioritize completion candidates whose return/declared
        // type unifies with this expected type. For `let f: A -> B = lambda`,
        // the body slot is the lambda's body; the expected type there is `B`.
        // For non-function annotations or non-lambda bodies, the expected type
        // is the annotated type itself.
        val expectedHints: Map<com.mezze.language.parsedast.Loc, MezzeType> =
            if (typeAnnotationValue != null && expectedType != null) {
                val fnAnnotation = expectedType as? MezzeType.FunctionType
                val lambdaBody = (binding.value as? com.mezze.language.parsedast.ParsedLambda)?.body
                if (fnAnnotation != null && lambdaBody != null) {
                    // Map the lambda BODY's loc to the function's return type.
                    // Cursor in the body lands here when LSP looks up enclosing loc.
                    mapOf(lambdaBody.loc to fnAnnotation.returnType)
                } else {
                    mapOf(binding.value.loc to expectedType)
                }
            } else emptyMap()

        return exprResult.copy(
            constraints = finalConstraints,
            abilityConstraints = abilityConstraints,
            expectedTypeAt = exprResult.expectedTypeAt + expectedHints,
        )
    }

    /**
     * Generate constraints for an expression.
     * Delegates to ExpressionConstraintGenerator.
     */
    fun generateExpression(
        expr: ParsedExpr,
        context: ConstraintContext
    ): ConstraintResult {
        return expressionGenerator.generate(expr, context)
    }

    /**
     * Generate constraints for a pattern.
     * Delegates to PatternConstraintGenerator.
     */
    fun generatePattern(
        pattern: ParsedPattern,
        scrutineeType: MezzeType,
        context: ConstraintContext,
        sourceLoc: Loc? = null
    ): PatternConstraintResult {
        return patternGenerator.generate(pattern, scrutineeType, context, sourceLoc)
    }

    /**
     * Helper: Get all free type variables from context bindings.
     * Used for let-polymorphism to determine which variables to quantify.
     *
     * @param excludeBindings BindingKeys of bindings to exclude (e.g., the binding being generalized)
     *                        This is CRITICAL for recursive functions - when generalizing `singleton`,
     *                        we should NOT include `singleton`'s placeholder type in the context vars.
     */
    private fun getContextFreeVars(context: TypingContext, excludeBindings: Set<BindingKey> = emptySet()): Set<TypeVar> {
        val freeVars = mutableSetOf<TypeVar>()
        for ((key, binding) in context.getAllBindings()) {
            // Skip bindings that should be excluded (like the binding being generalized)
            if (key in excludeBindings) continue

            when (binding) {
                is TypingContext.Binding.Monomorphic -> {
                    freeVars.addAll(com.mezze.language.typechecker.context.collectFreeTypeVars(binding.type))
                }
                is TypingContext.Binding.Polymorphic -> {
                    // Don't include quantified variables - they're bound by the scheme
                    // Only include free variables in the type that aren't quantified
                    val typeFreeVars = com.mezze.language.typechecker.context.collectFreeTypeVars(binding.scheme.type)
                    val unquantified = typeFreeVars - binding.scheme.quantifiedVars.toSet()
                    freeVars.addAll(unquantified)
                }
            }
        }
        return freeVars
    }

    /**
     * Helper: Add ability constraints to FunctionType before generalization.
     *
     * This matches old typechecker's collectAbilityConstraintsForFunctionType (OldTypeChecker.kt line 2606-2670).
     *
     * Process:
     * 1. If type is not FunctionType, return as-is
     * 2. Collect all type variables from function type (params + return)
     * 3. Find ability constraints that apply to these type variables
     * 4. Add constraints to FunctionType.abilityConstraints
     *
     * CRITICAL: Apply substitution to ability constraints so they refer to the SOLVED type variables
     */
    internal fun addAbilityConstraintsToFunctionType(
        type: MezzeType,
        abilityConstraints: List<Constraint.AbilityRequired>,
        substitution: com.mezze.language.typechecker.solving.Substitution
    ): MezzeType {
        DebugLogger.debugSection(Category.ConstraintGenerator, "addAbilityConstraintsToFunctionType") {
            val lines = mutableListOf("Type: $type")
            if (type is MezzeType.FunctionType) {
                lines.add("Param type: ${type.paramType}")
                if (type.paramType is MezzeType.RecordType) {
                    val record = type.paramType as MezzeType.RecordType
                    lines.add("  Record fields:")
                    record.fields.forEach { (name, fieldType) ->
                        lines.add("    * $name: $fieldType")
                    }
                }
                lines.add("Return type: ${type.returnType}")
            }
            lines.add("Ability constraints received: ${abilityConstraints.size} constraints")
            abilityConstraints.forEach { lines.add("  * $it") }
            lines
        }

        // Only add ability constraints to function types
        if (type !is MezzeType.FunctionType) {
            return type
        }

        // Collect all type variables from the function type
        val typeVars = mutableSetOf<TypeVar>()
        fun collectTypeVars(t: MezzeType) {
            when (t) {
                is MezzeType.VarType -> typeVars.add(t.variable)
                is MezzeType.RecordType -> t.fields.values.forEach { collectTypeVars(it) }
                is MezzeType.FunctionType -> {
                    collectTypeVars(t.paramType)
                    collectTypeVars(t.returnType)
                }
                is MezzeType.TypeApplication -> t.args.forEach { collectTypeVars(it) }
                is MezzeType.VariantType -> t.constructors.values.forEach { it?.let { collectTypeVars(it) } }
                is MezzeType.ListType -> collectTypeVars(t.elementType)
                is MezzeType.SeqType -> collectTypeVars(t.elementType)
                is MezzeType.SetType -> collectTypeVars(t.elementType)
                is MezzeType.SortedMapType -> {
                    collectTypeVars(t.keyType)
                    collectTypeVars(t.valueType)
                }
                is MezzeType.SortedSetType -> collectTypeVars(t.elementType)
                is MezzeType.MapType -> {
                    collectTypeVars(t.keyType)
                    collectTypeVars(t.valueType)
                }
                is MezzeType.SuspendedType -> collectTypeVars(t.valueType)
                else -> {} // Primitive types have no type variables
            }
        }

        collectTypeVars(type.paramType)
        collectTypeVars(type.returnType)

        DebugLogger.debugSection(Category.ConstraintGenerator, "Collected type vars") {
            typeVars.map { "* ${it.name} (id=${it.id})" }
        }

        // Find ability constraints that apply to type variables in the function
        // CRITICAL: Match by type variable NAME (after substitution), not by TypeVar instance
        // This handles cases where substitution renamed type variables
        val typeVarNames = typeVars.map { it.name }.toSet()

        val functionAbilityConstraints = abilityConstraints.mapNotNull { constraint ->
            // Apply substitution to get the solved type variable
            val solvedTypeVar = substitution.apply(MezzeType.VarType(constraint.typeVar))

            if (solvedTypeVar is MezzeType.VarType) {
                // Check if this type variable appears in our function type
                val matchingTypeVar = typeVars.find { it.name == solvedTypeVar.variable.name }

                val constraintResolvedHash = constraint.abilityHash ?: WellKnownHashes.resolveHash(constraint.abilityName)
                if (matchingTypeVar != null && !WellKnownHashes.isInspect(constraintResolvedHash)) {
                    // CRITICAL FIX: Filter out Inspect - it's universally available
                    // and shouldn't appear in type signatures (matches OldTypeChecker line 2651-2652)
                    MezzeType.FunctionAbilityConstraint(
                        abilityName = constraint.abilityName,
                        typeArgs = listOf(MezzeType.VarType(matchingTypeVar)),
                        abilityHash = constraint.abilityHash
                    )
                } else null
            } else null
        }

        // Return function type with added ability constraints
        // Merge with existing constraints (from type annotations)
        val allConstraints = (type.abilityConstraints + functionAbilityConstraints).distinctBy {
            // Avoid duplicates - same ability + same type var name
            it.abilityName to it.typeArgs.map { arg ->
                when (arg) {
                    is MezzeType.VarType -> arg.variable.name
                    else -> arg.toString()
                }
            }
        }

        DebugLogger.debugSection(Category.ConstraintGenerator, "addAbilityConstraintsToFunctionType result") {
            val lines = mutableListOf("All constraints: ${allConstraints.size} constraints")
            allConstraints.forEach { lines.add("  * $it") }
            lines.add("Function ability constraints added: ${functionAbilityConstraints.size}")
            functionAbilityConstraints.forEach { lines.add("  * $it") }
            lines
        }

        return MezzeType.FunctionType(
            paramType = type.paramType,
            returnType = type.returnType,
            effects = type.effects,
            abilityConstraints = allConstraints
        )
    }

    /**
     * Helper: Get binding name from ParsedLetDecl for results map key.
     *
     * For simple patterns (let x = ...), returns the variable name.
     * For complex patterns (let {x, y} = ...), returns first binding or placeholder.
     */
    private fun getBindingName(binding: ParsedLetDecl): String {
        // Extract all bindings from the pattern
        val bindings = binding.pattern.extractBindings()

        // Return first binding name, or a placeholder if no bindings
        return bindings.firstOrNull() ?: when (binding.pattern) {
            is ParsedWildcardPattern -> "_wildcard"
            is ParsedLiteralPattern -> "_literal"
            else -> "_pattern"
        }
    }

    /**
     * Helper: Convert TypeAnnotation to MezzeType
     * (Reuse logic from ContextBuilder)
     *
     * Public entry point - clears cache before conversion to ensure
     * type variables in separate annotations don't alias
     * Matches old typechecker behavior EXACTLY
     */
    private fun convertTypeAnnotation(annotation: ParsedTypeAnnotation): MezzeType {
        typeVarCache.clear()
        sharedAnnotationRowVar = null
        return convertTypeAnnotationInternal(annotation)
    }

    /**
     * Internal recursive type annotation converter.
     * Uses cached type variables to ensure consistency within a single annotation.
     */
    private fun convertTypeAnnotationInternal(annotation: ParsedTypeAnnotation): MezzeType {
        return when (annotation) {
            is ParsedNamedType -> {
                // Check primitive types first (includes Bool -> MezzeType.BoolType)
                PrimitiveTypes.fromName(annotation.name)?.let { return it }

                when (annotation.name) {
                    "Unit" -> MezzeType.RecordType(emptyMap())  // Unit is an empty record type
                    else -> {
                        // Type variable or type constructor
                        if (annotation.name.first().isLowerCase() || annotation.name.startsWith("'")) {
                            // CRITICAL: Cache type variables by name to ensure all occurrences of 'a
                            // in a single annotation refer to the same TypeVar instance
                            // Matches old typechecker behavior EXACTLY
                            //
                            // IMPORTANT: Use TypeVar.freshWithName() to generate unique IDs for annotation type vars.
                            // This preserves the original name from the annotation while ensuring the ID
                            // doesn't collide with inference type variables which also use fresh().
                            // Without this, annotation 'a (id=0) and pattern-fresh 'a (id=0) would be
                            // structurally equal, causing incorrect unifications.
                            val typeVar = typeVarCache.getOrPut(annotation.name) {
                                TypeVar.freshWithName(annotation.name)
                            }
                            MezzeType.VarType(typeVar)
                        } else {
                            com.mezze.language.typechecker.util.WellKnownTypeApp.of(annotation.name, emptyList(), registry.typeDeclarations[annotation.name]?.contentHash)
                        }
                    }
                }
            }
            is ParsedFunctionType -> {
                // Convert ability constraints from annotation to MezzeType.FunctionAbilityConstraint
                val abilityConstraints = annotation.abilityConstraints.map { constraint ->
                    MezzeType.FunctionAbilityConstraint(
                        abilityName = constraint.abilityName,
                        typeArgs = constraint.typeArgs.map { convertTypeAnnotationInternal(it) },
                        associatedTypeBindings = constraint.associatedTypeBindings.mapValues { (_, typeAnnotation) ->
                            convertTypeAnnotationInternal(typeAnnotation)
                        },
                        abilityHash = registry.abilityDeclarations[constraint.abilityName]?.contentHash
                    )
                }

                MezzeType.FunctionType(
                    paramType = convertTypeAnnotationInternal(annotation.paramType),
                    returnType = convertTypeAnnotationInternal(annotation.returnType),
                    effects = annotation.effects?.let { effectRow ->
                        // Validate hermetic effects (e.g., Mutation cannot coexist with other effects)
                        // Use allEffectNames to include both simple effects and effect annotations
                        ContextBuilder.validateHermeticEffects(effectRow.allEffectNames)

                        // Every named effect in the annotation must resolve.
                        // Without this, an annotation like `Str <FileSystem>`
                        // when FileSystem isn't imported silently strips the
                        // effect, then downstream the body's real effects
                        // surface as a confusing "effect Log doesn't match
                        // FileSystem" error pointing at unrelated code.
                        val unknownEffects = (effectRow.effects + effectRow.effectAnnotations.map { it.name })
                            .filter { it !in registry.effectDeclarations }
                        if (unknownEffects.isNotEmpty()) {
                            val name = unknownEffects.first()

                            // First: did-you-mean within already-in-scope effects.
                            val known = registry.effectDeclarations.keys
                            val suggestion = com.mezze.language.parsedast.diagnostics
                                .LevenshteinSuggest.closest(name, known)
                            val didYouMean = suggestion?.let { ". Did you mean `$it`?" } ?: ""

                            // Second: repo-wide search via the store — an effect
                            // with this exact name might exist somewhere the user
                            // hasn't imported from. If so, point them at the
                            // `use` line they need.
                            // Repo-wide store search for effects with this name.
                            val candidateModules: List<String> = try {
                                val store = com.mezze.language.typechecker.PrecompiledStdlib.getStore()
                                store?.getDeclsByKind(com.mezze.language.store.DeclKind.EFFECT)
                                    ?.filter { it.declName == name }
                                    ?.map { it.modulePath }
                                    ?.distinct()
                                    ?: emptyList()
                            } catch (_: Exception) { emptyList() }  // best-effort

                            // Text hints — kept for `mez check` CLI output.
                            val hints = if (candidateModules.isNotEmpty()) {
                                candidateModules.take(3).map { "import it: `use ${it}::{$name}`" }
                            } else listOf(
                                "declare `effect $name { ... }` in this module, or",
                                "import it: `use some_module::$name`",
                            )

                            // Structured fix-hints — consumed by LSP code actions.
                            // Each non-string handle: a Rename suggestion (if a
                            // close in-scope effect exists) and one Import hint
                            // (carrying every module the effect lives in).
                            val fixHints = buildList {
                                if (suggestion != null) {
                                    add(com.mezze.language.parsedast.diagnostics
                                        .FixHint.Rename(effectRow.loc, listOf(suggestion)))
                                }
                                if (candidateModules.isNotEmpty()) {
                                    add(com.mezze.language.parsedast.diagnostics
                                        .FixHint.Import(effectRow.loc, name, candidateModules))
                                }
                            }

                            throw com.mezze.language.typechecker.TypeCheckError(
                                "Effect `$name` is not defined or not imported$didYouMean",
                                effectRow.loc,
                                code = com.mezze.language.parsedast.diagnostics.ErrorCode.V0505_EFFECT_NOT_DEFINED,
                                hints = hints,
                                fixHints = fixHints,
                            )
                        }

                        // If the effect row is polymorphic (<...> or <Effect, ...>), reuse
                        // the per-annotation shared row var so multiple `<...>` in one
                        // signature carry the same effects (input → output propagation).
                        val rowVar = if (effectRow.isPolymorphic) {
                            sharedAnnotationRowVar
                                ?: RowVar.fresh().also { sharedAnnotationRowVar = it }
                        } else null

                        // Convert effect annotations with associated types to EffectInstance objects
                        val effectInstances = effectRow.effectAnnotations.map { effectAnnotation ->
                            MezzeType.EffectInstance(
                                name = effectAnnotation.name,
                                associatedTypes = effectAnnotation.associatedTypes.mapValues { (_, typeAnnotation) ->
                                    convertTypeAnnotationInternal(typeAnnotation)
                                }
                            )
                        }.toSet()

                        MezzeType.EffectRow(
                            effects = effectRow.effects.toSet(),
                            effectInstances = effectInstances,
                            rowVar = rowVar,
                            performed = effectRow.isPerformed
                        )
                    },
                    abilityConstraints = abilityConstraints
                )
            }
            is ParsedRecordType -> {
                MezzeType.RecordType(
                    fields = annotation.entries.associate { entry ->
                        entry.name to convertTypeAnnotationInternal(entry.type)
                    },
                    rowVar = if (annotation.isOpen) RowVar.userOpen() else null
                )
            }
            is ParsedVariantType -> {
                MezzeType.VariantType(
                    constructors = annotation.constructors.associate { ctor ->
                        ctor.name to ctor.dataType?.let { convertTypeAnnotationInternal(it) }
                    },
                    rowVar = if (annotation.isOpen) RowVar.userOpen() else null,
                    // Use synthetic typeName for closed inline annotations to enable validation.
                    // This makes `let x: | Red, Blue | = Green` fail because Green is not in the annotation.
                    typeName = if (!annotation.isOpen) "<inline>" else null,
                    nominalWall = !annotation.isOpen // #148: closed inline annotations are always walls
                )
            }
            is ParsedTypeApp -> {
                com.mezze.language.typechecker.util.WellKnownTypeApp.of(
                    constructor = annotation.constructor,
                    args = annotation.args.map { convertTypeAnnotationInternal(it) },
                    typeHash = registry.typeDeclarations[annotation.constructor]?.contentHash
                )
            }
            is ParsedSeqType -> {
                MezzeType.SeqType(
                    elementType = convertTypeAnnotationInternal(annotation.elementType)
                )
            }
            is ParsedEffectRow -> {
                MezzeType.EffectRow(annotation.effects.toSet())
            }
            is ParsedQualifiedType -> {
                val qualifiedName = annotation.modulePath.joinToString("::") + "::" + annotation.typeName
                com.mezze.language.typechecker.util.WellKnownTypeApp.of(
                    constructor = qualifiedName,
                    args = annotation.args.map { convertTypeAnnotationInternal(it) },
                    typeHash = registry.typeDeclarations[qualifiedName]?.contentHash
                )
            }
        }
    }

    companion object {
        /**
         * Create default constraint generator with all sub-generators.
         */
        fun create(
            registry: com.mezze.language.typechecker.context.DeclarationRegistry = com.mezze.language.typechecker.context.DeclarationRegistry.EMPTY,
            performerToEffect: Map<String, String> = emptyMap(),
            performerAddedEffects: Map<String, Set<String>> = emptyMap(),
            abilityNames: Set<String> = emptySet(),
            effectNames: Set<String> = emptySet(),
        ): ConstraintGenerator {
            val expressionGen = ExpressionConstraintGenerator()
            val patternGen = PatternConstraintGenerator()
            val abilityGen = AbilityConstraintGenerator()
            val effectGen = EffectConstraintGenerator()

            return ConstraintGenerator(
                expressionGen,
                patternGen,
                abilityGen,
                effectGen,
                registry,
                performerToEffect,
                performerAddedEffects,
                abilityNames,
                effectNames,
            )
        }
    }
}

// ExpressionConstraintGenerator is defined in ExpressionConstraints.kt
// PatternConstraintGenerator is defined in PatternConstraints.kt
// AbilityConstraintGenerator is defined in AbilityConstraints.kt
// EffectConstraintGenerator is defined in EffectConstraints.kt

/**
 * Result of pattern constraint generation.
 *
 * Contains:
 * - Bindings introduced by the pattern (name -> type)
 * - bindingLocations: source locations for each binding (for unused variable warnings)
 * - Constraints on the scrutinee type
 */
data class PatternConstraintResult(
    val bindings: Map<String, MezzeType>,
    val constraints: List<Constraint>,
    val warnings: List<String> = emptyList(),
    val bindingLocations: Map<String, Loc> = emptyMap()
)
