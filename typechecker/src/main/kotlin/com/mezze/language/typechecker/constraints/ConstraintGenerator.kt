package com.mezze.language.typechecker.constraints

import com.mezze.language.ast.*
import com.mezze.language.parsedast.*
import com.mezze.language.typechecker.abilities.BuiltInAbilities
import com.mezze.language.typechecker.context.ContextBuilder
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.context.TypeScheme
import com.mezze.language.typechecker.debug.DebugLogger
import com.mezze.language.typechecker.debug.DebugLogger.Category
import com.mezze.language.typechecker.scope.ScopedEffectConstants
import com.mezze.language.typechecker.scope.detectScopedEffectMembers
import com.mezze.language.typechecker.scope.extractScopeTypeFromAnnotation
import com.mezze.language.typechecker.scope.validateScopedEffectSignatures
import com.mezze.language.typechecker.WellKnownHashes
import com.mezze.language.ast.Keywords

/** Convert Loc to SourceLocation for error reporting */
private fun Loc.toSL(): SourceLocation? = SourceLocation(startLine, startCol, file)

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
    val perBindingSubstitution: com.mezze.language.typechecker.solving.Substitution = com.mezze.language.typechecker.solving.Substitution.empty  // Accumulated substitution from per-binding solving (for bidirectional inference)
) {
    /**
     * Get all referenced bindings for each binding name.
     * Used for building the dependency graph.
     */
    fun getReferencedBindingsPerBinding(): Map<String, Set<com.mezze.language.typechecker.dependencies.DefinitionRef.ByName>> {
        return constraintResults.mapValues { (_, result) -> result.referencedBindings }
    }
}

class ConstraintGenerator(
    val expressionGenerator: ExpressionConstraintGenerator,
    private val patternGenerator: PatternConstraintGenerator,
    private val abilityGenerator: AbilityConstraintGenerator,
    private val effectGenerator: EffectConstraintGenerator,
    private val typeDeclarations: Map<String, TypeDeclaration> = emptyMap(),
    private val performerToEffect: Map<String, String> = emptyMap(),
    initialPerformerAddedEffects: Map<String, Set<String>> = emptyMap(),
    private val abilityNames: Set<String> = emptySet(),
    private val effectNames: Set<String> = emptySet(),
    private val abilityDeclarations: Map<String, AbilityDeclaration> = emptyMap(),
    private val effectDeclarations: Map<String, EffectDeclaration> = emptyMap()
) {
    // Mutable map for tracking effects that performers add
    // This is populated initially from explicit effect annotations, and updated
    // during constraint generation when we discover effects from impl bodies
    private val performerAddedEffects: MutableMap<String, Set<String>> = initialPerformerAddedEffects.toMutableMap()
    // Cache for type variables within a single type annotation
    // Ensures all occurrences of 'a in an annotation refer to the same TypeVar instance
    // Matches old typechecker behavior
    private val typeVarCache = mutableMapOf<String, TypeVar>()
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

        // Track accumulated substitution from per-binding solving (for bidirectional inference)
        var perBindingSubstitution = com.mezze.language.typechecker.solving.Substitution.empty

        // Import constraint solver for incremental solving
        val solver = com.mezze.language.typechecker.solving.ConstraintSolver(context, typeDeclarations, abilityDeclarations)

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
                is ParsedLetDecl -> {
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
                    val constraintContext = ConstraintContext(currentContext, performerToEffect = performerToEffect, performerAddedEffects = performerAddedEffects, abilityNames = abilityNames, effectNames = effectNames, effectDeclarations = effectDeclarations, abilityDeclarations = abilityDeclarations, typeDeclarations = typeDeclarations)
                    val patternResult = patternGenerator.generate(
                        item.pattern,
                        result.type,
                        constraintContext,
                        item.value.loc.toSL()
                    )

                    // Check for duplicate bindings BEFORE extending context
                    // Skip bindings that were added as placeholders (they're being replaced, not duplicated)
                    val duplicateBindings = patternResult.bindings.keys
                        .filter { name -> currentContext.hasLocal(name) && !placeholderNames.contains(name) }
                        .map { name ->
                            ConstraintResult.DuplicateBinding(
                                name = name,
                                sourceLocation = null  // TODO: Track source locations for patterns
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
                            val bindingHash: String? = null  // ParsedLetDecl has no contentHash
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
                                    val isUserDefinedAlias = typeDeclarations.containsKey(preConvertedAnnotationType.constructor)

                                    if (isUserDefinedAlias) {
                                        // User-defined type alias - preserve it, apply substitution to type arguments only
                                        MezzeType.TypeApplication(
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
                                    validatePolymorphismPreserved(
                                        preConvertedAnnotationType,
                                        substitution,
                                        varName,
                                        item.value.loc.toSL(),
                                        abilityConstrainedVars
                                    )
                                }

                                // 2. EFFECTS VALIDATION
                                // Check that annotation doesn't overpromise effects.
                                // ONLY validate when there are NO function calls - because effects propagate
                                // through function calls and we can't easily track that here.
                                if (annotation is ParsedFunctionType &&
                                    annotation.effects != null &&
                                    !containsFunctionCalls(item.value)) {
                                    validateEffectsNotOverpromised(
                                        preConvertedAnnotationType,
                                        inferredTypeWithAbilities,
                                        varName,
                                        item.value.loc.toSL()
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
                                    validateAbilityOnCorrectTypeVar(
                                        preConvertedAnnotationType,
                                        resolvedAbilityConstraints,
                                        varName,
                                        substitution,
                                        item.value.loc.toSL()
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
                                // Example: let encode_value: {x: 'a} -> String with Encoder 'a = ...
                                // The user explicitly wants this to be polymorphic over 'a with Encoder 'a constraint.
                                val hasExplicitAbilityConstraints = when (val annotation = item.typeAnnotation) {
                                    is ParsedFunctionType -> annotation.abilityConstraints.isNotEmpty()
                                    else -> false
                                }

                                val hasUserDefinedAbilityConstraints = if (hasExplicitAbilityConstraints) {
                                    // User explicitly declared ability constraints - allow generalization
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

                            // CRITICAL FIX: Update results map with types that have ability constraints and effects
                            // SubstitutionApplier reads from this map, so we need the types with constraints here
                            // This ensures ability constraints make it to the final LetBinding.inferredType
                            for ((bindingName, solvedType) in solvedBindingsWithEffects) {
                                if (results.containsKey(bindingName)) {
                                    val existingResult = results[bindingName]!!
                                    results[bindingName] = existingResult.copy(type = solvedType)
                                }
                            }
                        }
                    }
                }
                is ParsedTypeDecl,
                is ParsedAbilityDecl,
                is ParsedEffectDecl -> {
                    // These are handled by ContextBuilder in Phase 1
                    // Nothing to do here
                }
                is ParsedPerformerDecl -> {
                    // CRITICAL: Validate effect implementation bodies
                    // ContextBuilder registers the performer in Phase 1
                    // Here in Phase 2, we type-check the implementation bodies
                    validateEffectImplementation(item, currentContext)
                }
                is ParsedImplAbilityDecl -> {
                    // CRITICAL: Validate ability implementation bodies
                    // ContextBuilder registers the implementation in Phase 1
                    // Here in Phase 2, we type-check the implementation bodies
                    validateAbilityImplementation(item, currentContext)

                    // For inherent impls, generate constraints for each operation body
                    if (item.isInherent) {
                        val targetTypeName = item.targetTypes.firstOrNull()?.let { ann ->
                            when (ann) {
                                is ParsedNamedType -> ann.name
                                is ParsedTypeApp -> ann.constructor
                                else -> null
                            }
                        }

                        for (op in item.operations) {
                            // Each operation gets a FRESH target type to prevent type variable leakage
                            val targetType = if (item.targetTypes.isNotEmpty()) {
                                convertTypeAnnotation(item.targetTypes.first())
                            } else {
                                MezzeType.VarType(TypeVar.fresh())
                            }

                            if (op.implementation !is ParsedLambda) continue
                            val lambda = op.implementation as ParsedLambda

                            val methodInfo = if (targetTypeName != null) {
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
                                    else -> paramNames.associateWith { MezzeType.VarType(TypeVar.fresh()) }
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
                                effectDeclarations = effectDeclarations,
                                abilityDeclarations = abilityDeclarations,
                                typeDeclarations = typeDeclarations
                            )

                            val bodyResult = expressionGenerator.generate(lambda.body, constraintContext)

                            val constraints = bodyResult.constraints.toMutableList()

                            val registeredMethodType = methodInfo?.methodType
                            if (registeredMethodType is MezzeType.FunctionType) {
                                constraints.add(Constraint.Equality(
                                    left = registeredMethodType.returnType,
                                    right = bodyResult.type,
                                    context = "Inherent impl return type for ${op.name}"
                                ))
                                val paramRecordType = MezzeType.RecordType(fields = paramTypes, rowVar = null)
                                constraints.add(Constraint.Equality(
                                    left = registeredMethodType.paramType,
                                    right = paramRecordType,
                                    context = "Inherent impl param type for ${op.name}"
                                ))
                            }

                            // ParsedImplAbilityDecl has no contentHash — use loc-based key
                            val opKey = "_inherent_${item.loc}_${op.name}"
                            results[opKey] = ConstraintResult(
                                type = bodyResult.type,
                                constraints = constraints,
                                effects = bodyResult.effects,
                                effectInstances = bodyResult.effectInstances,
                                abilityConstraints = bodyResult.abilityConstraints,
                                associatedTypeConstraints = bodyResult.associatedTypeConstraints,
                                undefinedVariables = emptyList(),
                                usedVariables = bodyResult.usedVariables
                            )
                        }
                    }
                }
                is ParsedTopLevelExpr -> {
                    // Top-level expressions (for REPL)
                    val constraintContext = ConstraintContext(currentContext, performerToEffect = performerToEffect, performerAddedEffects = performerAddedEffects, abilityNames = abilityNames, effectNames = effectNames, effectDeclarations = effectDeclarations, abilityDeclarations = abilityDeclarations, typeDeclarations = typeDeclarations)
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
            perBindingSubstitution = perBindingSubstitution
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

        val constraintContext = ConstraintContext(
            context,
            expectedType = expectedType,
            performerToEffect = performerToEffect,
            performerAddedEffects = performerAddedEffects,
            abilityNames = abilityNames,
            effectNames = effectNames,
            effectDeclarations = effectDeclarations,
            abilityDeclarations = abilityDeclarations,
            recordLiteralBindings = recordLiteralBindings,
            typeDeclarations = typeDeclarations,
            currentBindingName = bindingName,
            enclosingFunctionName = enclosingFunctionName
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
            binding.value.loc.toSL()
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
            validateAnnotationAgainstExpression(binding, annotatedType, typeAnnotationValue, binding.value, exprResult.type)

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
                                abilityHash = abilityDeclarations[constraint.abilityName]?.contentHash,
                                typeArgs = constraint.typeArgs,
                                context = "Type annotation on ${getBindingName(binding)}"
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
                                abilityHash = abilityDeclarations[constraint.abilityName]?.contentHash,
                                typeArgs = allTypeArgs,
                                context = "Type annotation on ${getBindingName(binding)}"
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
                errorContext = ErrorContext.TypeAnnotation(bindingName = bindingName)
            )

            Pair(constraintsWithAnnotation, exprResult.abilityConstraints + annotationAbilityConstraints)
        } else {
            Pair(allConstraints, exprResult.abilityConstraints)
        }

        return exprResult.copy(
            constraints = finalConstraints,
            abilityConstraints = abilityConstraints
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
        sourceLocation: SourceLocation? = null
    ): PatternConstraintResult {
        return patternGenerator.generate(pattern, scrutineeType, context, sourceLocation)
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

                val effectiveHash = constraint.abilityHash ?: WellKnownHashes.hashForName(constraint.abilityName)
                if (matchingTypeVar != null && !WellKnownHashes.isInspect(effectiveHash)) {
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
     * Helper: Get binding name from LetBinding for results map key.
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
                            MezzeType.TypeApplication(annotation.name, emptyList(), typeDeclarations[annotation.name]?.contentHash)
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
                        abilityHash = abilityDeclarations[constraint.abilityName]?.contentHash
                    )
                }

                MezzeType.FunctionType(
                    paramType = convertTypeAnnotationInternal(annotation.paramType),
                    returnType = convertTypeAnnotationInternal(annotation.returnType),
                    effects = annotation.effects?.let { effectRow ->
                        // Validate hermetic effects (e.g., Mutation cannot coexist with other effects)
                        // Use allEffectNames to include both simple effects and effect annotations
                        ContextBuilder.validateHermeticEffects(effectRow.allEffectNames)

                        // If the effect row is polymorphic (<...> or <Effect, ...>), create a fresh row variable
                        val rowVar = if (effectRow.isPolymorphic) RowVar.fresh() else null

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
                    typeName = if (!annotation.isOpen) "<inline>" else null
                )
            }
            is ParsedTypeApp -> {
                MezzeType.TypeApplication(
                    constructor = annotation.constructor,
                    args = annotation.args.map { convertTypeAnnotationInternal(it) },
                    typeHash = typeDeclarations[annotation.constructor]?.contentHash
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
                MezzeType.TypeApplication(
                    constructor = qualifiedName,
                    args = annotation.args.map { convertTypeAnnotationInternal(it) },
                    typeHash = typeDeclarations[qualifiedName]?.contentHash
                )
            }
        }
    }

    /**
     * Validate ability implementation bodies.
     *
     * Matches OldTypeChecker.kt lines 13063-13285:
     * - Look up ability declaration
     * - For each operation, type-check the lambda body
     * - Compare inferred body type with expected return type
     * - Throw TypeCheckError if types don't match
     *
     * This ensures soundness: implementation bodies must actually produce the declared return type.
     */
    private fun validateAbilityImplementation(impl: ParsedImplAbilityDecl, context: TypingContext) {
        // Find the ability declaration
        // Support both simple name (EncodeFormat) and qualified name (codec::EncodeFormat)
        // Inherent impls don't implement an ability — skip ability-based validation
        if (impl.isInherent) return

        val abilityRef = (impl.target as ParsedImplTarget.Named).ability
        val abilityName = abilityRef.localName
        val abilityQualifier = abilityRef.qualifier
        val qualifiedAbilityName = abilityQualifier?.let { "${it.segments.joinToString("::")}::$abilityName" } ?: abilityName
        val abilityDeclaration = abilityDeclarations[abilityName]
            ?: abilityDeclarations[qualifiedAbilityName]
            ?: throw com.mezze.language.typechecker.TypeCheckError(
                "Ability '$qualifiedAbilityName' is not defined or not imported. " +
                "Did you forget to import it with 'use'?"
            )

        // Build allTypeArgs: concrete abilityTypeArgs (excluding type vars) + targetTypes
        val allTypeArgs: List<ParsedTypeAnnotation> = run {
            val concreteArgs = impl.abilityTypeArgs.filter {
                it !is ParsedNamedType || !it.name.startsWith("'")
            }
            concreteArgs + impl.targetTypes
        }

        // Build substitution map: 'a -> Int, etc. (type parameters from target types)
        val typeParamSubstitution = abilityDeclaration.typeParams.zip(allTypeArgs).associate { (param, targetType) ->
            param to convertTypeAnnotation(targetType)
        }

        // Validate each operation's implementation body
        for (implOp in impl.operations) {
            val declOp = abilityDeclaration.operations.find { it.name == implOp.name }
                ?: continue  // Extra operations caught elsewhere

            // Substitute type parameters in declaration to get expected types
            val expectedParamType = substituteTypeParams(convertTypeAnnotation(declOp.paramType), typeParamSubstitution)
            val expectedReturnType = substituteTypeParams(convertTypeAnnotation(declOp.returnType), typeParamSubstitution)

            // Type-check the implementation body (must be a lambda)
            if (implOp.implementation is ParsedLambda) {
                val lambda = implOp.implementation as ParsedLambda

                // Extract parameter names from lambda
                val paramNames = when (val params = lambda.params) {
                    is ParsedLambdaParams.Untyped -> params.names
                    is ParsedLambdaParams.Typed -> params.fields.map { it.name }
                }

                // Build parameter type map for scoped context
                val paramTypes = if (expectedParamType is MezzeType.RecordType) {
                    paramNames.associateWith { name ->
                        expectedParamType.fields[name] ?: MezzeType.VarType(TypeVar.fresh())
                    }
                } else {
                    // Single parameter case - wrap in record
                    paramNames.associateWith { expectedParamType }
                }

                // Type-check the lambda body with parameters in scope
                // Create a new context with parameters bound
                var bodyContext = context
                for ((name, type) in paramTypes) {
                    bodyContext = bodyContext.extendMono(name, type)
                }

                // `it` is now an explicit param in ability impl bodies ({it, r} -> ...).
                // No implicit injection needed — the lambda param handles it.

                val constraintContext = ConstraintContext(
                    bodyContext,
                    expectedType = null,  // Synthesize type, don't check against expected
                    performerToEffect = performerToEffect,
                    performerAddedEffects = performerAddedEffects,
                    abilityNames = abilityNames,
                    effectNames = effectNames,
                    effectDeclarations = effectDeclarations,
                    abilityDeclarations = abilityDeclarations,
                    typeDeclarations = typeDeclarations
                )

                // Generate constraints for lambda body
                val bodyResult = expressionGenerator.generate(lambda.body, constraintContext)

                // Check for undefined variables in the body
                // Allow __native_* identifiers — they're resolved at runtime by NativeDispatch
                val realUndefined = bodyResult.undefinedVariables.filter { !it.name.startsWith("__native_") }
                if (realUndefined.isNotEmpty()) {
                    val undefinedVar = realUndefined.first()
                    throw com.mezze.language.typechecker.TypeCheckError(
                        "Type error in ability '${abilityName}' operation '${implOp.name}' implementation: " +
                        "Undefined variable '${undefinedVar.name}'"
                    )
                }

                // Solve constraints to get actual body type
                val solver = com.mezze.language.typechecker.solving.ConstraintSolver(bodyContext, typeDeclarations, abilityDeclarations)
                val solveResult = solver.solve(bodyResult.constraints)

                when (solveResult) {
                    is com.mezze.language.typechecker.solving.ConstraintSolver.SolveResult.Failure -> {
                        // Skip "Cannot access field" errors — these occur when operator desugaring
                        // produces ability method calls (e.g., `x + y` → `x.add {r = y}`, `x - y` →
                        // `x.sub {r = y}`) but ability impls aren't in scope during early validation.
                        // The full pipeline (Phases 2-5) catches real type errors with complete context.
                        val allFieldAccessErrors = solveResult.errors.all {
                            it.message.contains("Cannot access field") && it.message.contains("non-record type")
                        }
                        if (!allFieldAccessErrors) {
                            throw com.mezze.language.typechecker.TypeCheckError(
                                "Type error in ability '${abilityName}' operation '${implOp.name}' implementation: ${solveResult.errors.firstOrNull()?.message ?: "Unknown error"}"
                            )
                        }
                    }
                    is com.mezze.language.typechecker.solving.ConstraintSolver.SolveResult.Success -> {
                        val substitution = solveResult.substitution
                        val actualBodyType = substitution.apply(bodyResult.type)

                        // Check that actual body type matches expected return type
                        val unificationSolver = com.mezze.language.typechecker.solving.ConstraintSolver(bodyContext, typeDeclarations, abilityDeclarations)
                        val unificationResult = unificationSolver.solve(
                            listOf(Constraint.Equality(actualBodyType, expectedReturnType, "Ability body validation"))
                        )

                        if (unificationResult is com.mezze.language.typechecker.solving.ConstraintSolver.SolveResult.Failure) {
                            val actualTypeStr = com.mezze.language.ast.prettyPrintType(actualBodyType)
                            val expectedTypeStr = com.mezze.language.ast.prettyPrintType(expectedReturnType)
                            throw com.mezze.language.typechecker.TypeCheckError(
                                "Method '${implOp.name}' in impl ${abilityName} returns wrong type: " +
                                "expected $expectedTypeStr, but body returns $actualTypeStr."
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Validate effect implementation bodies.
     *
     * Similar to validateAbilityImplementation but for effects (ImplDeclaration).
     * For each operation:
     * - Get the expected return type from the effect declaration
     * - Substitute associated types with the performer's bindings
     * - Type-check the lambda body
     * - Compare inferred body type with expected return type
     * - Throw TypeCheckError if types don't match
     */
    private fun validateEffectImplementation(impl: ParsedPerformerDecl, context: TypingContext) {
        // Find the effect declaration by looking up with the exact name used in the impl.
        // The effectDeclarations map contains:
        // - Local effects keyed by their name (e.g., "MyEffect")
        // - Imported effects keyed by their import name (e.g., "log::Log" for module imports,
        //   or "Log" for selective imports like `use std::effects::log::{ Log }`)
        // We don't search by EffectDeclaration.name because that would bypass import scoping.
        val effectRef = impl.effect
        // Replicate toBindingKey().toKeyString() from v1 QualifiedRef
        val effectQualifier = effectRef.qualifier
        val effectLookupKey = if (effectQualifier != null) {
            "${effectQualifier.segments.last()}::${effectRef.localName}"
        } else {
            effectRef.localName
        }
        val effectDeclaration = effectDeclarations[effectLookupKey]
            ?: effectDeclarations[effectRef.localName]  // Fallback to local name for selective imports
            ?: throw com.mezze.language.typechecker.TypeCheckError(
                "Effect '${effectLookupKey}' is not defined or not imported. " +
                "Did you forget to import it with 'use'?"
            )

        // Build substitution map: 'state -> Int, etc. (associated type bindings)
        val associatedTypeSubstitution = impl.associatedTypeBindings.mapKeys { (key, _) ->
            // Keys might have or not have the ' prefix, normalize to have it
            if (key.startsWith("'")) key else "'$key"
        }.mapValues { (_, typeAnnotation) ->
            convertTypeAnnotation(typeAnnotation)
        }

        // Also add version without ' prefix for matching
        val fullSubstitution = associatedTypeSubstitution + associatedTypeSubstitution.mapKeys { (k, _) ->
            k.removePrefix("'")
        }

        // Add performer parameters to context for all operations
        // e.g., impl State for ConfigState {initial: Int} makes 'initial' available in operation bodies
        var baseContext = context
        for (param in impl.params) {
            val paramType = convertTypeAnnotation(param.type)
            baseContext = baseContext.extendMono(param.name, paramType)
        }

        // SCOPED EFFECTS: Detect initialize_scope and finalize_scope inline
        // (replacing detectScopedEffectMembers which takes v1 ImplDeclaration)
        var initializeScope: ParsedPerformerOp? = null
        var finalizeScope: ParsedPerformerOp? = null
        for (operation in impl.operations) {
            when (operation.name) {
                ScopedEffectConstants.INITIALIZE_SCOPE -> initializeScope = operation
                ScopedEffectConstants.FINALIZE_SCOPE -> finalizeScope = operation
            }
        }
        val isScopedEffect = initializeScope != null
        val hasFinalizer = finalizeScope != null

        // SCOPED EFFECTS: Validate scoped effect signatures inline
        // (replacing validateScopedEffectSignatures which takes ScopedEffectInfo with v1 types)
        if (isScopedEffect && !hasFinalizer) {
            throw com.mezze.language.typechecker.TypeCheckError(
                "initialize_scope requires finalize_scope for resource safety. " +
                "Add 'finalize_scope = {} -> {}' to clean up resources when the perform block exits.",
                initializeScope!!.loc.toSL()
            )
        }
        if (hasFinalizer && !isScopedEffect) {
            throw com.mezze.language.typechecker.TypeCheckError(
                "finalize_scope requires initialize_scope. " +
                "Add initialize_scope to create the scope that finalize_scope cleans up.",
                finalizeScope!!.loc.toSL()
            )
        }

        // SCOPED EFFECTS: Validate return type annotations on initialize_scope / finalize_scope
        if (isScopedEffect) {
            // initialize_scope must return a record type
            initializeScope!!.returnType?.let { retType ->
                if (retType !is ParsedRecordType) {
                    throw com.mezze.language.typechecker.TypeCheckError(
                        "initialize_scope must return a record type like { field1 = value1, ... }. " +
                        "The returned record becomes the 'scope' variable available in operations.",
                        initializeScope.loc.toSL()
                    )
                }
            }
            // finalize_scope must return unit ({})
            finalizeScope?.returnType?.let { retType ->
                if (retType !is ParsedRecordType || (retType as ParsedRecordType).entries.isNotEmpty()) {
                    throw com.mezze.language.typechecker.TypeCheckError(
                        "finalize_scope must return {} (unit). " +
                        "It's for cleanup side effects only, not for returning values.",
                        finalizeScope.loc.toSL()
                    )
                }
            }
        }

        // SCOPED EFFECTS: Validate initialize_scope body and INFER scope type
        // We need to:
        // 1. Generate constraints for initialize_scope body (without 'scope' available)
        // 2. Infer the scope type from the body's return type
        // 3. Use that scope type for operations and finalize_scope
        var inferredScopeType: MezzeType? = null

        initializeScope?.let { initOp ->
            if (initOp.implementation is ParsedLambda) {
                val lambda = initOp.implementation as ParsedLambda

                // Build context WITHOUT scope for initialize_scope
                val initContext = baseContext

                val constraintContext = ConstraintContext(
                    initContext,
                    expectedType = null,
                    performerToEffect = performerToEffect,
                    performerAddedEffects = performerAddedEffects,
                    abilityNames = abilityNames,
                    effectNames = effectNames,
                    effectDeclarations = effectDeclarations,
                    abilityDeclarations = abilityDeclarations,
                    typeDeclarations = typeDeclarations
                )

                // Generate constraints for lambda body
                val bodyResult = expressionGenerator.generate(lambda.body, constraintContext)

                // Check if 'scope' is used - this is an error
                val scopeUndefined = bodyResult.undefinedVariables.find { it.name == "scope" }
                if (scopeUndefined != null) {
                    throw com.mezze.language.typechecker.TypeCheckError(
                        "The 'scope' variable is not available in initialize_scope. " +
                        "The scope is being created here and will only be available in operations and finalize_scope.",
                        scopeUndefined.sourceLocation
                    )
                }

                // INFER scope type from the body's return type
                // This is the key change - we get the type from what initialize_scope actually returns
                // Accept ANY type (not just RecordType) — native calls may return opaque types
                inferredScopeType = bodyResult.type

                // Track effects from initialize_scope - they propagate to the perform site
                // Effects run at the START of perform, before the body
                val initEffects = bodyResult.effects - effectRef.localName
                if (initEffects.isNotEmpty()) {
                    val existingEffects = performerAddedEffects[impl.performerName] ?: emptySet()
                    performerAddedEffects[impl.performerName] = existingEffects + initEffects
                }
            }
        }

        // Use inferred scope type, or try annotation as fallback
        val scopeType: MezzeType? = inferredScopeType ?: if (isScopedEffect) {
            // Fallback: try to get scope type from initialize_scope return type annotation
            initializeScope?.let { initOp ->
                val returnType = initOp.returnType
                if (returnType is ParsedRecordType) {
                    convertTypeAnnotation(returnType)
                } else {
                    null
                }
            }
        } else {
            null
        }

        // Build context with scope for operations and finalize_scope
        val contextWithScope = if (scopeType != null) {
            baseContext.extendMono("scope", scopeType)
        } else {
            baseContext
        }

        // SCOPED EFFECTS: Validate finalize_scope body and track its effects
        // finalize_scope runs in a finally block at the END of perform
        finalizeScope?.let { finalOp ->
            if (finalOp.implementation is ParsedLambda) {
                val lambda = finalOp.implementation as ParsedLambda

                // finalize_scope has access to 'scope' variable
                val finalizeContext = contextWithScope

                // SCOPED EFFECTS: Add 'scope' to recordLiteralBindings for field access validation
                // This enables "field not found" errors with suggestions for scope.fieldName
                val scopeRecordBindings = if (scopeType != null) setOf("scope") else emptySet()

                val constraintContext = ConstraintContext(
                    finalizeContext,
                    expectedType = null,
                    performerToEffect = performerToEffect,
                    performerAddedEffects = performerAddedEffects,
                    abilityNames = abilityNames,
                    effectNames = effectNames,
                    effectDeclarations = effectDeclarations,
                    abilityDeclarations = abilityDeclarations,
                    typeDeclarations = typeDeclarations,
                    recordLiteralBindings = scopeRecordBindings
                )

                // Generate constraints for lambda body
                val bodyResult = expressionGenerator.generate(lambda.body, constraintContext)

                // Track effects from finalize_scope - they propagate to the perform site
                // Effects run at the END of perform, in a finally block (even if body fails)
                val finalizeEffects = bodyResult.effects - effectRef.localName
                if (finalizeEffects.isNotEmpty()) {
                    val existingEffects = performerAddedEffects[impl.performerName] ?: emptySet()
                    performerAddedEffects[impl.performerName] = existingEffects + finalizeEffects
                }
            }
        }

        // Validate each operation's implementation body
        for (implOp in impl.operations) {
            // Skip scoped effect special members - they're validated separately
            if (implOp.name == ScopedEffectConstants.INITIALIZE_SCOPE || implOp.name == ScopedEffectConstants.FINALIZE_SCOPE) {
                continue
            }

            val declOp = effectDeclaration.operations.find { it.name == implOp.name }
                ?: continue  // Extra operations caught elsewhere

            // Convert param and return types from the declaration
            val declParamType = convertTypeAnnotation(declOp.paramType)
            val declReturnType = convertTypeAnnotation(declOp.returnType)

            // Substitute associated types to get expected types
            // Example: {} -> 'state <State> with 'state = Int becomes {} -> Int <State>
            val expectedParamType = substituteTypeParams(declParamType, fullSubstitution)
            val expectedReturnType = substituteTypeParams(declReturnType, fullSubstitution)

            // CRITICAL: Validate impl operation type annotations match effect declaration
            // If the impl provides EXPLICIT type annotations, they must match the expected types
            // Both parameter names AND types must match exactly (after associated type substitution)
            // For return types, the base type must match but effect annotations can differ
            // (impl can remove effects or transform them)
            //
            // NOTE: When an operation has no explicit type annotation (e.g., `ask = {} -> "hello"`),
            // the parser sets placeholder types (empty records). We detect these placeholders and skip
            // validation, letting the body type inference handle type checking.
            val implParamTypeAnnotation = implOp.paramType
            val isPlaceholderParamType = implParamTypeAnnotation is ParsedRecordType &&
                implParamTypeAnnotation.entries.isEmpty() && !implParamTypeAnnotation.isOpen

            if (implParamTypeAnnotation != null && !isPlaceholderParamType) {
                val implParamType = convertTypeAnnotation(implParamTypeAnnotation)
                // Parameter names AND types must match exactly
                // Use full unification which checks both field names and types
                val unificationSolver = com.mezze.language.typechecker.solving.ConstraintSolver(baseContext, typeDeclarations, abilityDeclarations)
                val unificationResult = unificationSolver.solve(
                    listOf(Constraint.Equality(implParamType, expectedParamType, "Effect param type validation"))
                )
                if (unificationResult is com.mezze.language.typechecker.solving.ConstraintSolver.SolveResult.Failure) {
                    val implTypeStr = com.mezze.language.ast.prettyPrintType(implParamType)
                    val expectedTypeStr = com.mezze.language.ast.prettyPrintType(expectedParamType)
                    throw com.mezze.language.typechecker.TypeCheckError(
                        "Effect impl '${impl.performerName}' operation '${implOp.name}' has wrong parameter type: " +
                        "expected $expectedTypeStr (from effect '${effectRef.localName}'), " +
                        "but impl declares $implTypeStr. " +
                        "Parameter names and types must match exactly.",
                        implOp.implementation.loc.toSL()
                    )
                }
            }

            val implReturnTypeAnnotation = implOp.returnType
            val isPlaceholderReturnType = implReturnTypeAnnotation is ParsedRecordType &&
                implReturnTypeAnnotation.entries.isEmpty() && !implReturnTypeAnnotation.isOpen

            if (implReturnTypeAnnotation != null && !isPlaceholderReturnType) {
                val implReturnType = convertTypeAnnotation(implReturnTypeAnnotation)
                // For return types, the base type must match but we don't check effects here
                // (impl can remove effects or transform them - this is handled elsewhere)
                val unificationSolver = com.mezze.language.typechecker.solving.ConstraintSolver(baseContext, typeDeclarations, abilityDeclarations)
                val unificationResult = unificationSolver.solve(
                    listOf(Constraint.Equality(implReturnType, expectedReturnType, "Effect return type validation"))
                )
                if (unificationResult is com.mezze.language.typechecker.solving.ConstraintSolver.SolveResult.Failure) {
                    val implTypeStr = com.mezze.language.ast.prettyPrintType(implReturnType)
                    val expectedTypeStr = com.mezze.language.ast.prettyPrintType(expectedReturnType)
                    throw com.mezze.language.typechecker.TypeCheckError(
                        "Effect impl '${impl.performerName}' operation '${implOp.name}' has wrong return type: " +
                        "expected $expectedTypeStr (from effect '${effectRef.localName}'), " +
                        "but impl declares $implTypeStr.",
                        implOp.implementation.loc.toSL()
                    )
                }
            }

            // CRITICAL: Effect impl operation CANNOT have the same effect in its effect annotation
            // The impl is supposed to HANDLE the effect, not require it - that would be circular
            // e.g., impl Log for Console where { info: { msg: String } -> {} <Log> = ... } is INVALID
            // but  impl Log for Console where { info: { msg: String } -> {} <IO> = ... } is OK (transformed)
            // and  impl Log for Console where { info: { msg: String } -> {} = ... } is OK (handled/removed)
            val implEffects = implOp.effects
            if (implEffects != null && effectRef.localName in implEffects.effects) {
                throw com.mezze.language.typechecker.TypeCheckError(
                    "Effect impl '${impl.performerName}' operation '${implOp.name}' cannot require " +
                    "the same effect '${effectRef.localName}' it is implementing. " +
                    "The impl should handle the effect, not propagate it. " +
                    "Either remove '${effectRef.localName}' from the effect annotation or transform it to a different effect.",
                    implOp.implementation.loc.toSL()
                )
            }

            // Type-check the implementation body (must be a lambda)
            if (implOp.implementation is ParsedLambda) {
                val lambda = implOp.implementation as ParsedLambda

                // Extract parameter names from lambda
                val paramNames = when (val params = lambda.params) {
                    is ParsedLambdaParams.Untyped -> params.names
                    is ParsedLambdaParams.Typed -> params.fields.map { it.name }
                }

                // Build parameter type map for scoped context
                val paramTypes = if (expectedParamType is MezzeType.RecordType) {
                    paramNames.associateWith { name ->
                        expectedParamType.fields[name] ?: MezzeType.VarType(TypeVar.fresh())
                    }
                } else {
                    // Single parameter case - wrap in record
                    paramNames.associateWith { expectedParamType }
                }

                // Type-check the lambda body with parameters in scope (including performer params)
                // SCOPED EFFECTS: Use contextWithScope for operations and finalize_scope,
                // but NOT for initialize_scope (scope is being created, not yet available)
                val operationBaseContext = if (implOp.name == ScopedEffectConstants.INITIALIZE_SCOPE) {
                    baseContext  // No scope access in initialize_scope
                } else {
                    contextWithScope  // Operations and finalize_scope have scope access
                }
                var bodyContext = operationBaseContext
                for ((name, type) in paramTypes) {
                    bodyContext = bodyContext.extendMono(name, type)
                }

                // SCOPED EFFECTS: Add 'scope' to recordLiteralBindings for field access validation
                // This enables "field not found" errors with suggestions for scope.fieldName
                // Only add if scope is available (scopeType is set and this is not initialize_scope)
                val scopeRecordBindings = if (scopeType != null && implOp.name != ScopedEffectConstants.INITIALIZE_SCOPE) {
                    setOf("scope")
                } else {
                    emptySet()
                }

                val constraintContext = ConstraintContext(
                    bodyContext,
                    expectedType = null,
                    performerToEffect = performerToEffect,
                    performerAddedEffects = performerAddedEffects,
                    abilityNames = abilityNames,
                    effectNames = effectNames,
                    effectDeclarations = effectDeclarations,
                    abilityDeclarations = abilityDeclarations,
                    typeDeclarations = typeDeclarations,
                    recordLiteralBindings = scopeRecordBindings
                )

                // Generate constraints for lambda body
                val bodyResult = expressionGenerator.generate(lambda.body, constraintContext)

                // CRITICAL: Track effects from the implementation body
                // When an impl body uses effects (like FileSystem.write_file), those effects
                // need to be propagated so that perform expressions know what additional
                // effects are introduced by this performer.
                // Exclude the effect being implemented (impl handles it, doesn't add it)
                val bodyEffects = bodyResult.effects - effectRef.localName
                if (bodyEffects.isNotEmpty()) {
                    val existingEffects = performerAddedEffects[impl.performerName] ?: emptySet()
                    performerAddedEffects[impl.performerName] = existingEffects + bodyEffects
                }

                // If there are undefined variables, check for 'scope' errors first
                // The body may reference external performers or functions not yet in context
                // Full validation will happen during the normal type-checking phase
                if (bodyResult.undefinedVariables.isNotEmpty()) {
                    // SCOPED EFFECTS: Check for 'scope' being undefined - this is a special error case
                    // 'scope' is only available in operations and finalize_scope when initialize_scope exists
                    val scopeUndefined = bodyResult.undefinedVariables.find { it.name == "scope" }
                    if (scopeUndefined != null) {
                        // Determine the error message based on context
                        val errorMessage = if (implOp.name == ScopedEffectConstants.INITIALIZE_SCOPE) {
                            "The 'scope' variable is not available in initialize_scope. " +
                            "The scope is being created here and will only be available in operations and finalize_scope."
                        } else if (!isScopedEffect) {
                            "The 'scope' variable is not available because this impl does not define initialize_scope. " +
                            "Add 'initialize_scope = {} -> { ... }' to create a scope."
                        } else {
                            // This shouldn't happen if our code is correct, but handle it anyway
                            "Undefined variable 'scope' in operation '${implOp.name}'."
                        }
                        throw com.mezze.language.typechecker.TypeCheckError(
                            errorMessage,
                            scopeUndefined.sourceLocation
                        )
                    }
                    // Skip validation for other undefined variables - let the normal type-checking catch errors later
                    continue
                }

                // Solve constraints to get actual body type
                val solver = com.mezze.language.typechecker.solving.ConstraintSolver(bodyContext, typeDeclarations, abilityDeclarations)
                val solveResult = solver.solve(bodyResult.constraints)

                when (solveResult) {
                    is com.mezze.language.typechecker.solving.ConstraintSolver.SolveResult.Failure -> {
                        val firstError = solveResult.errors.firstOrNull()
                        if (firstError != null) {
                            val constraint = firstError.constraint

                            // FIRST: Check if this is a "calling non-function as function" error
                            // This happens when one side is FunctionType and the other is not
                            // This is always a clear error and should be reported immediately
                            if (constraint is Constraint.Equality) {
                                val left = constraint.left
                                val right = constraint.right
                                val isCallingNonFunction =
                                    (left is MezzeType.FunctionType && !isFunctionLike(right)) ||
                                    (right is MezzeType.FunctionType && !isFunctionLike(left))

                                if (isCallingNonFunction) {
                                    // This is a clear error: trying to call a non-function as a function
                                    val nonFunctionType = if (left is MezzeType.FunctionType) right else left
                                    throw com.mezze.language.typechecker.TypeCheckError(
                                        "Cannot call non-function type as function in impl '${impl.performerName}' " +
                                        "operation '${implOp.name}': type '${com.mezze.language.ast.prettyPrintType(nonFunctionType)}' " +
                                        "is not a function",
                                        firstError.constraint.sourceLocation ?: implOp.implementation.loc.toSL()
                                    )
                                }
                            }

                            // SECOND: Check for other clear type mismatches
                            // Only report mismatches between concrete scalar types (String vs Int, etc.)
                            // Skip if message involves: effects, type variables ('a, 'b, etc.), generics, collections
                            // These may be handled by native functions with different semantics
                            val msg = firstError.message
                            // Type variables are 'letter patterns, not quoted field names like 'fieldName'
                            val hasTypeVar = Regex("'[a-z]\\b").containsMatchIn(msg)
                            val hasEffects = msg.contains("effectful") || msg.contains("<") && msg.contains(">")
                            val hasCollections = msg.contains("List") || msg.contains("Seq") ||
                                msg.contains("[") || msg.contains("Set") || msg.contains("Map")
                            val isClearTypeMismatch = msg.contains("Type mismatch") &&
                                !hasTypeVar && !hasEffects && !hasCollections

                            if (isClearTypeMismatch) {
                                throw com.mezze.language.typechecker.TypeCheckError(
                                    "Type error in impl '${impl.performerName}' operation '${implOp.name}': ${firstError.message}",
                                    firstError.constraint.sourceLocation ?: implOp.implementation.loc.toSL()
                                )
                            }
                        }
                        continue
                    }
                    is com.mezze.language.typechecker.solving.ConstraintSolver.SolveResult.Success -> {
                        val substitution = solveResult.substitution
                        val actualBodyType = substitution.apply(bodyResult.type)

                        // Check that actual body type matches expected return type using unification
                        // This is more permissive than string comparison - it allows [] to unify with List String
                        val unificationSolver = com.mezze.language.typechecker.solving.ConstraintSolver(bodyContext, typeDeclarations, abilityDeclarations)
                        val unificationResult = unificationSolver.solve(
                            listOf(Constraint.Equality(actualBodyType, expectedReturnType, "Effect body validation"))
                        )

                        when (unificationResult) {
                            is com.mezze.language.typechecker.solving.ConstraintSolver.SolveResult.Failure -> {
                                val actualTypeStr = com.mezze.language.ast.prettyPrintType(actualBodyType)
                                val expectedTypeStr = com.mezze.language.ast.prettyPrintType(expectedReturnType)
                                throw com.mezze.language.typechecker.TypeCheckError(
                                    "Operation '${implOp.name}' in impl ${effectRef.localName} returns wrong type: " +
                                    "expected $expectedTypeStr, but body returns $actualTypeStr."
                                )
                            }
                            is com.mezze.language.typechecker.solving.ConstraintSolver.SolveResult.Success -> {
                                // Unification succeeded - types are compatible
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Substitute type parameters in a type.
     *
     * Helper for ability implementation validation.
     * Matches OldTypeChecker.kt's substituteTypeParameters.
     */
    private fun substituteTypeParams(type: MezzeType, substitution: Map<String, MezzeType>): MezzeType {
        return when (type) {
            is MezzeType.VarType -> {
                // Check if this type variable should be substituted
                substitution[type.variable.name] ?: type
            }
            is MezzeType.FunctionType -> MezzeType.FunctionType(
                paramType = substituteTypeParams(type.paramType, substitution),
                returnType = substituteTypeParams(type.returnType, substitution),
                effects = type.effects,
                abilityConstraints = type.abilityConstraints
            )
            is MezzeType.RecordType -> MezzeType.RecordType(
                fields = type.fields.mapValues { (_, fieldType) ->
                    substituteTypeParams(fieldType, substitution)
                },
                rowVar = type.rowVar
            )
            is MezzeType.VariantType -> MezzeType.VariantType(
                constructors = type.constructors.mapValues { (_, payloadType) ->
                    payloadType?.let { substituteTypeParams(it, substitution) }
                },
                rowVar = type.rowVar,
                typeName = type.typeName
            )
            is MezzeType.TypeApplication -> MezzeType.TypeApplication(
                constructor = type.constructor,
                args = type.args.map { substituteTypeParams(it, substitution) },
                typeHash = type.typeHash
            )
            is MezzeType.ListType -> MezzeType.ListType(
                elementType = substituteTypeParams(type.elementType, substitution)
            )
            is MezzeType.SeqType -> MezzeType.SeqType(
                elementType = substituteTypeParams(type.elementType, substitution)
            )
            is MezzeType.SetType -> MezzeType.SetType(
                elementType = substituteTypeParams(type.elementType, substitution)
            )
            is MezzeType.SortedMapType -> MezzeType.SortedMapType(
                keyType = substituteTypeParams(type.keyType, substitution),
                valueType = substituteTypeParams(type.valueType, substitution)
            )
            is MezzeType.SortedSetType -> MezzeType.SortedSetType(
                elementType = substituteTypeParams(type.elementType, substitution)
            )
            is MezzeType.MapType -> MezzeType.MapType(
                keyType = substituteTypeParams(type.keyType, substitution),
                valueType = substituteTypeParams(type.valueType, substitution)
            )
            is MezzeType.SuspendedType -> MezzeType.SuspendedType(
                valueType = substituteTypeParams(type.valueType, substitution),
                effects = type.effects
            )
            // Primitive types don't need substitution
            else -> type
        }
    }

    /**
     * Validate type annotation against expression.
     *
     * Performs upfront validation that catches errors the constraint solver would silently accept:
     * 1. Record with extra fields when annotation is closed record
     * 2. Lambda parameter count mismatch with function annotation
     * 3. Polymorphic function returning function (partial application check)
     */
    private fun validateAnnotationAgainstExpression(
        binding: ParsedLetDecl,
        annotatedType: MezzeType,
        annotation: ParsedTypeAnnotation,
        expression: ParsedExpr,
        inferredType: MezzeType
    ) {
        // 1. RECORD EXTRA FIELD VALIDATION
        // Check if annotation is a closed record type and expression is a record literal with extra fields
        if (annotatedType is MezzeType.RecordType && expression is ParsedRecordLit) {
            // Only validate if annotation is closed (no rowVar)
            if (annotatedType.rowVar == null) {
                val annotatedFields = annotatedType.fields.keys
                val literalFields = mutableSetOf<String>()

                // Extract field names from record literal
                for (entry in expression.entries) {
                    when (entry) {
                        is ParsedRecordEntry.Field -> literalFields.add(entry.name)
                        is ParsedRecordEntry.Spread -> {
                            // Spread entries make validation complex - skip for now
                            // The constraint solver will handle these cases
                            return
                        }
                    }
                }

                // Check for extra fields
                val extraFields = literalFields - annotatedFields
                if (extraFields.isNotEmpty()) {
                    throw com.mezze.language.typechecker.TypeCheckError(
                        "Type annotation mismatch: record has extra field(s) ${extraFields.sorted().joinToString(", ")} " +
                        "not present in closed record type annotation { ${annotatedFields.sorted().joinToString(", ") { "$it: ..." }} }",
                        expression.loc.toSL()
                    )
                }

                // Check for missing fields
                val missingFields = annotatedFields - literalFields
                if (missingFields.isNotEmpty()) {
                    throw com.mezze.language.typechecker.TypeCheckError(
                        "Type annotation mismatch: record is missing field(s) ${missingFields.sorted().joinToString(", ")} " +
                        "required by type annotation { ${annotatedFields.sorted().joinToString(", ") { "$it: ..." }} }",
                        expression.loc.toSL()
                    )
                }
            }
        }

        // 2. FUNCTION ARITY VALIDATION
        // Check if annotation is a function type and expression is a lambda with mismatched parameters
        if (annotatedType is MezzeType.FunctionType && expression is ParsedLambda) {
            val annotatedParamType = annotatedType.paramType

            // Get parameter count from annotation
            val expectedParamCount = when (annotatedParamType) {
                is MezzeType.RecordType -> annotatedParamType.fields.size
                else -> 1 // Single parameter (not a record)
            }

            // Get parameter count from lambda
            val actualParamCount = when (val params = expression.params) {
                is ParsedLambdaParams.Untyped -> params.names.size
                is ParsedLambdaParams.Typed -> params.fields.size
            }

            // Check if counts match
            if (expectedParamCount != actualParamCount) {
                throw com.mezze.language.typechecker.TypeCheckError(
                    "Type annotation mismatch: function expects $expectedParamCount parameter(s) " +
                    "but lambda has $actualParamCount parameter(s)",
                    expression.loc.toSL()
                )
            }
        }
    }

    /**
     * Validate that type variables in annotation are not forced to FULLY concrete types.
     *
     * This catches cases like:
     *   let bad: { x: 'a, y: 'a } -> 'a = {x, y} -> if true then x else 0
     *
     * Where the annotation says 'a is polymorphic, but the implementation forces it to be Int.
     *
     * IMPORTANT: Only flags when the substituted type has NO type variables at all.
     * If the substituted type still contains type variables (even nested), that's OK.
     *
     * @param annotationType The original annotation type (before substitution)
     * @param substitution The substitution from constraint solving
     * @param varName The variable name (for error messages)
     */
    private fun validatePolymorphismPreserved(
        annotationType: MezzeType,
        substitution: com.mezze.language.typechecker.solving.Substitution,
        varName: String,
        sourceLocation: SourceLocation? = null,
        abilityConstrainedTypeVars: Set<TypeVar> = emptySet()
    ) {
        // Collect all type variables from the annotation
        val annotationTypeVars = collectTypeVars(annotationType)

        // Check each type variable - if substitution maps it to a FULLY CONCRETE type, that's an error
        for (typeVar in annotationTypeVars) {
            // Skip type variables that have ability constraints on them.
            // The solver's bidirectional inference may map these to concrete types
            // from available impls, but that doesn't mean the function isn't polymorphic.
            // The constraint will be validated at the call site instead.
            if (abilityConstrainedTypeVars.any {
                it.name.removePrefix("'") == typeVar.name.removePrefix("'") || it.id == typeVar.id
            }) {
                continue
            }

            val substituted = substitution.apply(MezzeType.VarType(typeVar))
            if (substituted !is MezzeType.VarType) {
                // Check if the substituted type is FULLY concrete (no type variables)
                val substitutedTypeVars = collectTypeVars(substituted)
                if (substitutedTypeVars.isEmpty()) {
                    throw com.mezze.language.typechecker.TypeCheckError(
                        "Type annotation mismatch for '$varName': Annotation declares type variable '${typeVar.name}' " +
                        "but implementation forces it to concrete type '${com.mezze.language.ast.prettyPrintType(substituted)}'. " +
                        "The implementation must work for all types, not just specific ones.",
                        sourceLocation
                    )
                }
            }
        }
    }

    /**
     * Collect all type variables from a type.
     */
    private fun collectTypeVars(type: MezzeType): Set<TypeVar> {
        val result = mutableSetOf<TypeVar>()
        collectTypeVarsRecursive(type, result)
        return result
    }

    private fun collectTypeVarsRecursive(type: MezzeType, result: MutableSet<TypeVar>) {
        when (type) {
            is MezzeType.VarType -> result.add(type.variable)
            is MezzeType.FunctionType -> {
                collectTypeVarsRecursive(type.paramType, result)
                collectTypeVarsRecursive(type.returnType, result)
            }
            is MezzeType.RecordType -> {
                type.fields.values.forEach { collectTypeVarsRecursive(it, result) }
            }
            is MezzeType.VariantType -> {
                type.constructors.values.filterNotNull().forEach { collectTypeVarsRecursive(it, result) }
            }
            is MezzeType.TypeApplication -> {
                type.args.forEach { collectTypeVarsRecursive(it, result) }
            }
            is MezzeType.SeqType -> collectTypeVarsRecursive(type.elementType, result)
            is MezzeType.ListType -> collectTypeVarsRecursive(type.elementType, result)
            is MezzeType.SetType -> collectTypeVarsRecursive(type.elementType, result)
            is MezzeType.SortedMapType -> {
                collectTypeVarsRecursive(type.keyType, result)
                collectTypeVarsRecursive(type.valueType, result)
            }
            is MezzeType.SortedSetType -> collectTypeVarsRecursive(type.elementType, result)
            is MezzeType.MapType -> {
                collectTypeVarsRecursive(type.keyType, result)
                collectTypeVarsRecursive(type.valueType, result)
            }
            is MezzeType.NewtypeType -> collectTypeVarsRecursive(type.wrappedType, result)
            // Concrete types have no type variables
            is MezzeType.IntType,
            is MezzeType.FloatType,
            is MezzeType.StringType,
            is MezzeType.BoolType,
            is MezzeType.NatType,
            is MezzeType.FracType,
            is MezzeType.OrderingType,
            is MezzeType.BytesType,
            is MezzeType.ByteBufferType,
            is MezzeType.CharType -> { /* No type variables */ }
            // Effect and suspended types - check inner types
            is MezzeType.EffectRow -> { /* Effect names, no type variables to collect */ }
            is MezzeType.SuspendedType -> collectTypeVarsRecursive(type.valueType, result)
        }
    }

    /**
     * Check if a type contains any concrete (non-polymorphic) types.
     *
     * Concrete types include: Int, String, Bool, Float, Unit, etc.
     * Polymorphic types are type variables like 'a, 'b.
     *
     * A type like { x: 'a } -> 'a is NOT concrete (all type variables).
     * A type like { x: Int } -> Int IS concrete.
     * A type like { x: 'a } -> Int IS concrete (contains Int).
     */
    private fun containsConcreteTypes(type: MezzeType): Boolean {
        return when (type) {
            is MezzeType.VarType -> false
            is MezzeType.FunctionType ->
                containsConcreteTypes(type.paramType) || containsConcreteTypes(type.returnType)
            is MezzeType.RecordType ->
                type.fields.values.any { containsConcreteTypes(it) }
            is MezzeType.VariantType -> {
                // A variant type IS concrete even if constructors have no payloads
                // For example, |Red, Blue, Green| is a concrete type (not polymorphic)
                type.constructors.isNotEmpty() || type.constructors.values.any { it != null && containsConcreteTypes(it) }
            }
            is MezzeType.TypeApplication ->
                type.args.any { containsConcreteTypes(it) }
            is MezzeType.SeqType ->
                containsConcreteTypes(type.elementType)
            is MezzeType.ListType ->
                containsConcreteTypes(type.elementType)
            is MezzeType.SetType ->
                containsConcreteTypes(type.elementType)
            is MezzeType.SortedMapType ->
                containsConcreteTypes(type.keyType) || containsConcreteTypes(type.valueType)
            is MezzeType.SortedSetType ->
                containsConcreteTypes(type.elementType)
            is MezzeType.MapType ->
                containsConcreteTypes(type.keyType) || containsConcreteTypes(type.valueType)
            // Concrete types: IntType, StringType, BoolType, FloatType, UnitType
            is MezzeType.IntType,
            is MezzeType.StringType,
            is MezzeType.BoolType,
            is MezzeType.FloatType -> true
            // Other types are considered concrete by default
            else -> true
        }
    }

    /**
     * Validate effects match between annotation and inferred type.
     *
     * Rules:
     * - If annotation declares effects, implementation must use exactly those effects
     * - If annotation declares no effects (pure), implementation must be pure
     */
    private fun validateEffectsMatch(
        annotationType: MezzeType,
        inferredType: MezzeType,
        varName: String,
        expression: ParsedExpr
    ) {
        if (annotationType is MezzeType.FunctionType && inferredType is MezzeType.FunctionType) {
            val annotatedEffects = annotationType.effects?.effects ?: emptySet()
            val inferredEffects = inferredType.effects?.effects ?: emptySet()

            // Check if implementation uses effects not declared in annotation
            val extraEffects = inferredEffects - annotatedEffects
            if (extraEffects.isNotEmpty()) {
                throw com.mezze.language.typechecker.TypeCheckError(
                    "Type annotation mismatch for '$varName': Implementation uses effects ${extraEffects.joinToString(", ")} " +
                    "which are not declared in the annotation.",
                    expression.loc.toSL()
                )
            }

            // Check if annotation declares effects not used in implementation
            val missingEffects = annotatedEffects - inferredEffects
            if (missingEffects.isNotEmpty()) {
                throw com.mezze.language.typechecker.TypeCheckError(
                    "Type annotation mismatch for '$varName': Annotation declares effects ${missingEffects.joinToString(", ")} " +
                    "which are not used in the implementation.",
                    expression.loc.toSL()
                )
            }
        }
    }

    /**
     * Validate that annotation doesn't overpromise effects.
     *
     * Rules:
     * - If annotation declares effects that implementation doesn't use, that's an error
     * - If implementation uses effects not in annotation, that's allowed (more restrictive API)
     *
     * NOTE: This should ONLY be called when there are NO function calls in the implementation,
     * because effects propagate through function calls and we can't track that here.
     */
    private fun validateEffectsNotOverpromised(
        annotationType: MezzeType,
        inferredType: MezzeType,
        varName: String,
        sourceLocation: SourceLocation? = null
    ) {
        if (annotationType is MezzeType.FunctionType && inferredType is MezzeType.FunctionType) {
            val annotatedEffects = annotationType.effects?.effects ?: emptySet()
            val inferredEffects = inferredType.effects?.effects ?: emptySet()

            // Check if annotation declares effects not used in implementation (overpromising)
            val overpromisedEffects = annotatedEffects - inferredEffects
            if (overpromisedEffects.isNotEmpty()) {
                throw com.mezze.language.typechecker.TypeCheckError(
                    "Type annotation mismatch for '$varName': Annotation declares effects ${overpromisedEffects.joinToString(", ")} " +
                    "which are not used in the implementation.",
                    sourceLocation
                )
            }
        }
    }

    /**
     * Validate that abilities are applied to the correct type variables.
     *
     * This catches cases like:
     * - Annotation declares `NumAdd 'b` but implementation uses `NumAdd 'a`
     *
     * The substitution is used to check if type variables are unified (in either direction).
     */
    private fun validateAbilityOnCorrectTypeVar(
        annotationType: MezzeType,
        inferredAbilityConstraints: List<Constraint.AbilityRequired>,
        varName: String,
        substitution: com.mezze.language.typechecker.solving.Substitution,
        sourceLocation: SourceLocation? = null
    ) {
        if (annotationType !is MezzeType.FunctionType) return

        val annotatedAbilities = annotationType.abilityConstraints
        val annotationTypeVars = collectTypeVars(annotationType)

        // Filter out constraints from annotation itself
        val actualUsageConstraints = inferredAbilityConstraints.filter { constraint ->
            constraint.context == null || !constraint.context.startsWith("Type annotation on")
        }

        // Helper function to check if two type variables are unified through substitution
        fun areUnified(tv1: TypeVar, tv2: TypeVar): Boolean {
            if (tv1.id == tv2.id) return true
            // Check if substitution maps tv1 to tv2 or vice versa
            val resolved1 = substitution.apply(MezzeType.VarType(tv1))
            val resolved2 = substitution.apply(MezzeType.VarType(tv2))
            // They're unified if they resolve to the same thing
            if (resolved1 == resolved2 ||
                (resolved1 is MezzeType.VarType && resolved1.variable.id == tv2.id) ||
                (resolved2 is MezzeType.VarType && resolved2.variable.id == tv1.id)) return true
            // Fallback: if both resolve to unresolved VarTypes with the same user-written name,
            // treat as unified (handles annotation type vars that aren't in the inferred substitution)
            if (resolved1 is MezzeType.VarType && resolved2 is MezzeType.VarType &&
                resolved1.variable.name == resolved2.variable.name) return true
            return false
        }

        for (usage in actualUsageConstraints) {
            val usageHash = usage.abilityHash ?: WellKnownHashes.hashForName(usage.abilityName)
            if (WellKnownHashes.isInspect(usageHash)) continue  // Skip implicit Inspect

            // Find ALL annotated abilities with the same name (there might be multiple, e.g., Show 'x and Show 'y)
            val matchingAnnotatedList = annotatedAbilities.filter { it.abilityName == usage.abilityName }

            if (matchingAnnotatedList.isNotEmpty()) {
                // Check if ANY of the matching annotated abilities have a type arg that matches
                // the usage's type variable. This handles cases like:
                //   annotation: Show 'x, Show 'y
                //   usage: Show on 'y (should match Show 'y, not fail because it doesn't match Show 'x)
                // Also handles multi-parameter abilities like:
                //   annotation: Encode Json 'a (typeArgs=[Json, 'a])
                //   usage: typeVar=format_fresh (resolves to Json), typeArgs=[data_fresh]
                val resolvedUsageTypeVar = substitution.apply(MezzeType.VarType(usage.typeVar))
                val hasMatchingTypeVar = matchingAnnotatedList.any { matchingAnnotated ->
                    matchingAnnotated.typeArgs.any { annotatedTypeArg ->
                        when {
                            // Check VarType: unified through substitution
                            annotatedTypeArg is MezzeType.VarType ->
                                areUnified(usage.typeVar, annotatedTypeArg.variable)
                            // Check concrete type: usage's typeVar resolves to same concrete type
                            else -> resolvedUsageTypeVar == annotatedTypeArg
                        }
                    }
                }

                if (!hasMatchingTypeVar) {
                    // The ability is declared but not on a matching type variable
                    val declaredVars = matchingAnnotatedList.flatMap { it.typeArgs }
                        .filterIsInstance<MezzeType.VarType>()
                        .map { it.variable.name }
                        .distinct()
                    throw com.mezze.language.typechecker.TypeCheckError(
                        "Type annotation mismatch for '$varName': Ability '${usage.abilityName}' is applied to wrong type variable. " +
                        "Annotation declares constraint on ${declaredVars.joinToString()} but implementation uses it on '${usage.typeVar.name}'.",
                        sourceLocation
                    )
                }
            } else {
                // Implementation uses ability not in annotation - error if on annotation's type var
                // Check if usage.typeVar is unified with any annotation type var
                val isOnAnnotationTypeVar = annotationTypeVars.any { annotationVar ->
                    areUnified(usage.typeVar, annotationVar)
                }
                if (isOnAnnotationTypeVar) {
                    throw com.mezze.language.typechecker.TypeCheckError(
                        "Type annotation mismatch for '$varName': Implementation requires '${usage.abilityName}' on type variable '${usage.typeVar.name}', " +
                        "but the annotation does not declare this constraint.",
                        sourceLocation
                    )
                }
            }
        }
    }

    /**
     * Validate ability constraints match between annotation and inferred type.
     *
     * Rules:
     * - If annotation declares abilities (on type variables), implementation should use them
     * - We only check for simple functions without function calls to avoid false positives
     *
     * Note: We don't check if implementation uses abilities not in annotation, because
     * concrete abilities (like `Ord Int`) are always available and don't need to be declared.
     */
    private fun validateAbilitiesMatch(
        annotationType: MezzeType,
        inferredType: MezzeType,
        inferredAbilityConstraints: List<Constraint.AbilityRequired>,
        varName: String,
        expression: ParsedExpr
    ) {
        if (annotationType is MezzeType.FunctionType && inferredType is MezzeType.FunctionType) {
            val annotatedAbilities = annotationType.abilityConstraints


            // Filter out constraints that come from the annotation itself
            // These have context like "Type annotation on varName"
            val actualUsageConstraints = inferredAbilityConstraints.filter { constraint ->
                constraint.context == null || !constraint.context.startsWith("Type annotation on")
            }


            // Check if implementation uses abilities on DIFFERENT type variables than annotation declares
            // Example: annotation declares NumAdd 'b but implementation uses NumAdd 'a
            for (usage in actualUsageConstraints) {
                // Skip Inspect - it's implicit
                val usageEffHash = usage.abilityHash ?: WellKnownHashes.hashForName(usage.abilityName)
                if (WellKnownHashes.isInspect(usageEffHash)) continue

                // Find matching annotation constraint by ability name
                val matchingAnnotated = annotatedAbilities.find { it.abilityName == usage.abilityName }
                if (matchingAnnotated != null) {
                    // Check that the type variables match
                    // The annotation's type arg should reference the same type variable as the usage
                    for ((i, annotatedTypeArg) in matchingAnnotated.typeArgs.withIndex()) {
                        if (annotatedTypeArg is MezzeType.VarType) {
                            // The usage's typeVar should match the annotation's type variable
                            if (usage.typeVar.name != annotatedTypeArg.variable.name) {
                                throw com.mezze.language.typechecker.TypeCheckError(
                                    "Type annotation mismatch for '$varName': Ability '${usage.abilityName}' is applied to wrong type variable. " +
                                    "Annotation declares '${annotatedTypeArg.variable.name}' but implementation uses '${usage.typeVar.name}'.",
                                    expression.loc.toSL()
                                )
                            }
                        }
                    }
                }
            }

            // Check if annotation declares abilities not used in implementation
            // Only check for simple functions without function calls
            if (!containsFunctionCalls(expression)) {
                for (annotated in annotatedAbilities) {
                    // Skip Inspect - it's implicit
                    if (WellKnownHashes.isInspect(annotated.abilityHash)) continue

                    // Skip abilities on concrete types - they don't need to be used
                    val hasConcreteTypes = annotated.typeArgs.any { it !is MezzeType.VarType }
                    if (hasConcreteTypes) continue

                    val matchingInferred = actualUsageConstraints.find { it.abilityName == annotated.abilityName }
                    if (matchingInferred == null) {
                        throw com.mezze.language.typechecker.TypeCheckError(
                            "Type annotation mismatch for '$varName': Annotation declares ability '${annotated.abilityName}' " +
                            "which is not used in the implementation.",
                            expression.loc.toSL()
                        )
                    }
                }
            }
        }
    }

    /**
     * Check if expression contains function calls.
     * Used to determine if we should validate unused abilities.
     */
    private fun containsFunctionCalls(expr: ParsedExpr): Boolean {
        return when (expr) {
            is ParsedFunctionCall -> true
            is ParsedLambda -> containsFunctionCalls(expr.body)
            is ParsedIf -> {
                containsFunctionCalls(expr.condition) ||
                containsFunctionCalls(expr.thenBranch) ||
                containsFunctionCalls(expr.elseBranch)
            }
            is ParsedMatch -> {
                containsFunctionCalls(expr.scrutinee) ||
                expr.cases.any { containsFunctionCalls(it.body) }
            }
            is ParsedSeqLit -> expr.elements.any { element ->
                when (element) {
                    is ParsedSeqElement.Regular -> containsFunctionCalls(element.expr)
                    is ParsedSeqElement.Spread -> containsFunctionCalls(element.expr)
                }
            }
            is ParsedRecordLit -> expr.entries.any { entry ->
                when (entry) {
                    is ParsedRecordEntry.Field -> entry.value?.let { containsFunctionCalls(it) } ?: false
                    is ParsedRecordEntry.Spread -> containsFunctionCalls(entry.expr)
                }
            }
            is ParsedMemberAccess -> containsFunctionCalls(expr.receiver)
            // String interpolation can contain function calls in its parts
            is ParsedStringLit -> {
                expr.parts.any { part ->
                    part is ParsedStringPart.Interpolation && containsFunctionCalls(part.expr)
                }
            }
            // For ParsedDo and other complex expressions, assume they may contain function calls
            is ParsedDo -> true
            else -> false
        }
    }

    companion object {
        /**
         * Create default constraint generator with all sub-generators.
         */
        fun create(
            typeDeclarations: Map<String, TypeDeclaration> = emptyMap(),
            performerToEffect: Map<String, String> = emptyMap(),
            performerAddedEffects: Map<String, Set<String>> = emptyMap(),
            abilityNames: Set<String> = emptySet(),
            effectNames: Set<String> = emptySet(),
            abilityDeclarations: Map<String, AbilityDeclaration> = emptyMap(),
            effectDeclarations: Map<String, EffectDeclaration> = emptyMap()
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
                typeDeclarations,
                performerToEffect,
                performerAddedEffects,
                abilityNames,
                effectNames,
                abilityDeclarations,
                effectDeclarations
            )
        }
    }

    private fun debugTypeCG(type: MezzeType?, depth: Int = 0): String {
        if (type == null) return "null"
        if (depth > 3) return "..."
        return when (type) {
            is MezzeType.FunctionType -> "FunctionType(param=${debugTypeCG(type.paramType, depth + 1)}, return=${debugTypeCG(type.returnType, depth + 1)})"
            is MezzeType.RecordType -> "RecordType(fields={${type.fields.map { (k, v) -> "$k: ${debugTypeCG(v, depth + 1)}" }.joinToString(", ")}}, rowVar=${type.rowVar})"
            is MezzeType.VariantType -> "VariantType(constructors=${type.constructors.keys}, rowVar=${type.rowVar})"
            is MezzeType.VarType -> "VarType(${type.variable})"
            else -> type::class.simpleName ?: type.toString()
        }
    }

    /**
     * Check if a type could be function-like (FunctionType or unresolved type variable).
     * Used to detect "calling non-function as function" errors.
     */
    private fun isFunctionLike(type: MezzeType): Boolean {
        return when (type) {
            is MezzeType.FunctionType -> true
            is MezzeType.VarType -> true  // Type variable could resolve to function
            else -> false
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
    val bindingLocations: Map<String, SourceLocation> = emptyMap()
)
