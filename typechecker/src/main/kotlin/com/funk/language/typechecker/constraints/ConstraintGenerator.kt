package com.funk.language.typechecker.constraints

import com.funk.language.ast.*
import com.funk.language.typechecker.abilities.BuiltInAbilities
import com.funk.language.typechecker.context.TypingContext
import com.funk.language.typechecker.context.TypeScheme

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
    val perBindingSolveErrors: List<com.funk.language.typechecker.solving.ConstraintSolver.ConstraintError> = emptyList()  // Errors from per-binding solving
)

class ConstraintGenerator(
    private val expressionGenerator: ExpressionConstraintGenerator,
    private val patternGenerator: PatternConstraintGenerator,
    private val abilityGenerator: AbilityConstraintGenerator,
    private val effectGenerator: EffectConstraintGenerator,
    private val typeDeclarations: Map<String, TypeDeclaration> = emptyMap(),
    private val performerToEffect: Map<String, String> = emptyMap(),
    private val abilityNames: Set<String> = emptySet(),
    private val effectNames: Set<String> = emptySet(),
    private val abilityDeclarations: Map<String, AbilityDeclaration> = emptyMap(),
    private val effectDeclarations: Map<String, EffectDeclaration> = emptyMap()
) {
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
        program: Program,
        context: TypingContext
    ): ProgramConstraintResult {
        // DEBUG: Print ability and effect names
        if (System.getProperty("funk.debug.abilities") == "true") {
            println("[DEBUG ConstraintGenerator] abilityNames = $abilityNames")
            println("[DEBUG ConstraintGenerator] effectNames = $effectNames")
        }

        val results = mutableMapOf<String, ConstraintResult>()
        var currentContext = context

        // CRITICAL: Track record literal bindings for field access validation
        // Matches OldTypeChecker.kt:1428-1429
        val recordLiteralBindings = mutableSetOf<String>()

        // Track errors from per-binding solving
        val perBindingSolveErrors = mutableListOf<com.funk.language.typechecker.solving.ConstraintSolver.ConstraintError>()

        // Import constraint solver for incremental solving
        val solver = com.funk.language.typechecker.solving.ConstraintSolver(context, typeDeclarations, abilityDeclarations)

        // FIRST PASS: Add placeholders for function bindings to support mutual recursion
        // This allows functions to reference themselves and each other
        // CRITICAL: Store the converted annotations to reuse the SAME TypeVar instances in second pass!
        val letBindings = program.items.filterIsInstance<LetBinding>()
        val placeholderNames = mutableSetOf<String>()
        val annotationTypes = mutableMapOf<LetBinding, FunkType>()  // Store converted annotations
        for (binding in letBindings) {
            // Convert and store annotation types for ALL bindings with annotations
            // This ensures type annotations with variant/record types are preserved correctly
            binding.typeAnnotation?.let { annotation ->
                val convertedType = convertTypeAnnotation(annotation)
                annotationTypes[binding] = convertedType  // Store for reuse in second pass
            }

            // Only add placeholders for simple variable bindings with lambda expressions
            if (binding.isSimpleBinding && binding.expression is LambdaExpression) {
                val placeholderType = annotationTypes[binding] ?: FunkType.VarType(TypeVar.fresh())

                currentContext = currentContext.extendMono(binding.variableName, placeholderType)
                placeholderNames.add(binding.variableName)
            }
        }

        // SECOND PASS: Process each program item with placeholders in context
        for (item in program.items) {
            when (item) {
                is LetBinding -> {
                    // Reuse the converted annotation type from first pass to ensure same TypeVar instances
                    val preConvertedAnnotationType = annotationTypes[item]
                    val result = generateLetBinding(item, currentContext, recordLiteralBindings, preConvertedAnnotationType)

                    // CRITICAL: Track record literal bindings (matches OldTypeChecker.kt:1428-1429)
                    if (item.pattern is VariablePattern && item.expression is RecordLiteral) {
                        recordLiteralBindings.add((item.pattern as VariablePattern).name)
                    }

                    // Extend context with pattern bindings for subsequent items
                    // For simple variable bindings: extends with one binding
                    // For destructuring patterns: extends with multiple bindings
                    val constraintContext = ConstraintContext(currentContext, performerToEffect = performerToEffect, abilityNames = abilityNames, effectNames = effectNames, abilityDeclarations = abilityDeclarations, typeDeclarations = typeDeclarations)
                    val patternResult = patternGenerator.generate(
                        item.pattern,
                        result.type,
                        constraintContext
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
                    val solveResult = solver.solve(result.constraints)
                    when (solveResult) {
                        is com.funk.language.typechecker.solving.ConstraintSolver.SolveResult.Failure -> {
                            // Solving failed - collect the errors for propagation
                            perBindingSolveErrors.addAll(solveResult.errors)

                            // STILL add binding to context with its unsolved type
                            // so subsequent bindings can reference it (even if the type is wrong)
                            // This prevents cascading "undefined variable" errors
                            results[getBindingName(item)] = resultWithDuplicates

                            // Add binding to context with unsolved type
                            currentContext = currentContext.extendAll(
                                patternResult.bindings.mapValues { (_, type) ->
                                    TypingContext.Binding.Monomorphic(type)
                                }
                            )
                            continue
                        }
                        is com.funk.language.typechecker.solving.ConstraintSolver.SolveResult.Success -> {
                            val substitution = solveResult.substitution

                            // CRITICAL: If there's a type annotation, use the ANNOTATED type (with substitution applied)
                            // as the authoritative type. This ensures closed variant/record types from annotations
                            // override open inferred types.
                            // IMPORTANT: Use annotation even if it has type variables (polymorphic) - generalization
                            // happens AFTER solving constraints. This matches OldTypeChecker.kt lines 1226-1411.
                            // IMPORTANT: Preserve TypeApplication from annotations (don't expand user-defined type aliases)
                            val authoritativeType = if (item.pattern is VariablePattern && preConvertedAnnotationType != null) {
                                // CRITICAL: If annotation was a TypeApplication (type alias), check if it's user-defined
                                // Only preserve USER-DEFINED type aliases, not built-in types like Array, List, etc.
                                // This ensures "let x: Person = ..." has type "Person", not "{ name: String, ... }"
                                if (preConvertedAnnotationType is FunkType.TypeApplication) {
                                    // Check if this is a user-defined type alias (exists in typeDeclarations)
                                    val isUserDefinedAlias = typeDeclarations.containsKey(preConvertedAnnotationType.constructor)

                                    if (isUserDefinedAlias) {
                                        // User-defined type alias - preserve it, apply substitution to type arguments only
                                        FunkType.TypeApplication(
                                            constructor = preConvertedAnnotationType.constructor,
                                            args = preConvertedAnnotationType.args.map { substitution.apply(it) }
                                        )
                                    } else {
                                        // Built-in type (Array, List, etc.) - apply substitution normally
                                        // This allows Array Int to be treated as ArrayType(IntType)
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
                            if (item.pattern is VariablePattern && preConvertedAnnotationType != null) {
                                val annotation = item.typeAnnotation
                                val varName = (item.pattern as VariablePattern).name

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
                                if (annotation is FunctionTypeAnnotation) {
                                    validatePolymorphismPreserved(
                                        preConvertedAnnotationType,
                                        substitution,
                                        varName
                                    )
                                }

                                // 2. EFFECTS VALIDATION
                                // Check that annotation doesn't overpromise effects.
                                // ONLY validate when there are NO function calls - because effects propagate
                                // through function calls and we can't easily track that here.
                                if (annotation is FunctionTypeAnnotation &&
                                    annotation.effects != null &&
                                    !containsFunctionCalls(item.expression)) {
                                    validateEffectsNotOverpromised(
                                        preConvertedAnnotationType,
                                        inferredTypeWithAbilities,
                                        varName
                                    )
                                }

                                // 3. ABILITY VALIDATION
                                // Check that abilities are applied to correct type variables.
                                // This catches cases like: annotation declares NumAdd 'b but impl uses NumAdd 'a
                                if (annotation is FunctionTypeAnnotation && annotation.abilityConstraints.isNotEmpty()) {
                                    // Apply substitution to ability constraints to map fresh type vars back
                                    // The substitution may map in either direction (annotation->inferred or inferred->annotation)
                                    // so we try to resolve in both directions
                                    val resolvedAbilityConstraints = result.abilityConstraints.map { constraint ->
                                        val resolvedTypeVar = substitution.apply(FunkType.VarType(constraint.typeVar))
                                        if (resolvedTypeVar is FunkType.VarType) {
                                            constraint.copy(typeVar = resolvedTypeVar.variable)
                                        } else {
                                            constraint
                                        }
                                    }
                                    validateAbilityOnCorrectTypeVar(
                                        preConvertedAnnotationType,
                                        resolvedAbilityConstraints,
                                        varName,
                                        substitution
                                    )
                                }
                            }

                            // Apply substitution to get final solved types for pattern bindings
                            val solvedBindings = if (item.pattern is VariablePattern && authoritativeType != substitution.apply(result.type)) {
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

                            // GENERALIZATION: Check if bindings should be polymorphic
                            // Convert pattern bindings to context bindings with generalization
                            val contextBindings = solvedBindingsWithAbilities.mapValues { (name, solvedType) ->
                                // Collect free type variables in the solved type
                                val freeVars = com.funk.language.typechecker.context.collectFreeTypeVars(solvedType)

                                // Get free type variables from current context
                                // CRITICAL: Exclude the binding being generalized from context vars calculation
                                // This prevents the placeholder's type vars from blocking generalization
                                val contextVars = getContextFreeVars(currentContext, excludeBindings = setOf(name))

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
                                val hasUserDefinedAbilityConstraints = result.abilityConstraints.any { constraint ->
                                    when (constraint) {
                                        is Constraint.AbilityRequired -> {
                                            // Check if this is a built-in ability with built-in implementations
                                            val isBuiltInAbility = BuiltInAbilities.isBuiltInAbility(constraint.abilityName)

                                            if (isBuiltInAbility) {
                                                // Built-in abilities like NumAdd, Eq, etc. should allow generalization
                                                false
                                            } else {
                                                // User-defined ability - check if the type variable is quantifiable
                                                // Apply substitution to handle 'a -> 'b chains
                                                val resolvedType = substitution.apply(FunkType.VarType(constraint.typeVar))
                                                val resolvedTypeVar = if (resolvedType is FunkType.VarType) {
                                                    resolvedType.variable
                                                } else {
                                                    // If resolved to concrete type, constraint is satisfied
                                                    null
                                                }
                                                resolvedTypeVar != null && quantifiableVars.contains(resolvedTypeVar)
                                            }
                                        }
                                        else -> false
                                    }
                                }

                                if (quantifiableVars.isNotEmpty() && !hasUserDefinedAbilityConstraints) {
                                    // Polymorphic binding - generalize into TypeScheme
                                    // Extract ability constraints for quantified variables
                                    // CRITICAL: Compare by ID since TypeVar instances may differ even for same logical variable
                                    val quantifiableVarIds = quantifiableVars.map { it.id }.toSet()
                                    val abilityConstraints = result.abilityConstraints.mapNotNull { constraint ->
                                        when (constraint) {
                                            is Constraint.AbilityRequired -> {
                                                // Check if constraint's typeVar matches any quantifiable var by ID
                                                val resolvedConstraintVar = substitution.apply(FunkType.VarType(constraint.typeVar))
                                                val constraintVarId = if (resolvedConstraintVar is FunkType.VarType) {
                                                    resolvedConstraintVar.variable.id
                                                } else {
                                                    constraint.typeVar.id
                                                }
                                                if (quantifiableVarIds.contains(constraintVarId)) {
                                                    // Find the actual quantifiable var to get its name
                                                    val quantVar = quantifiableVars.find { it.id == constraintVarId }
                                                    AbilityConstraint(
                                                        abilityName = constraint.abilityName,
                                                        typeParam = quantVar?.name ?: constraint.typeVar.name
                                                    )
                                                } else null
                                            }
                                            else -> null
                                        }
                                    }

                                    val scheme = TypeScheme(
                                        quantifiedVars = quantifiableVars.toList(),
                                        constraints = abilityConstraints,
                                        type = solvedType
                                    )
                                    TypingContext.Binding.Polymorphic(scheme)
                                } else {
                                    // Monomorphic binding - either no free variables or has unsatisfied ability constraints
                                    TypingContext.Binding.Monomorphic(solvedType)
                                }
                            }

                            // Update context with final inferred types (replacing placeholders)
                            // Polymorphic bindings will be instantiated at use sites
                            currentContext = currentContext.extendAll(contextBindings)

                            // CRITICAL FIX: Update results map with types that have ability constraints
                            // SubstitutionApplier reads from this map, so we need the types with constraints here
                            // This ensures ability constraints make it to the final LetBinding.inferredType
                            for ((bindingName, solvedType) in solvedBindingsWithAbilities) {
                                if (results.containsKey(bindingName)) {
                                    val existingResult = results[bindingName]!!
                                    results[bindingName] = existingResult.copy(type = solvedType)
                                }
                            }
                        }
                    }
                }
                is TypeDeclaration,
                is AbilityDeclaration,
                is EffectDeclaration -> {
                    // These are handled by ContextBuilder in Phase 1
                    // Nothing to do here
                }
                is ImplDeclaration -> {
                    // CRITICAL: Validate effect implementation bodies
                    // ContextBuilder registers the performer in Phase 1
                    // Here in Phase 2, we type-check the implementation bodies
                    validateEffectImplementation(item, currentContext)
                }
                is ImplAbilityDeclaration -> {
                    // CRITICAL: Validate ability implementation bodies
                    // ContextBuilder registers the implementation in Phase 1
                    // Here in Phase 2, we type-check the implementation bodies
                    validateAbilityImplementation(item, currentContext)
                }
                is TopLevelExpression -> {
                    // Top-level expressions (for REPL)
                    val constraintContext = ConstraintContext(currentContext, performerToEffect = performerToEffect, abilityNames = abilityNames, effectNames = effectNames, abilityDeclarations = abilityDeclarations, typeDeclarations = typeDeclarations)
                    results["_toplevel"] = expressionGenerator.generate(
                        item.expression,
                        constraintContext
                    )
                }
                is UseDeclaration,
                is PubUseDeclaration -> {
                    // Module imports - handled elsewhere
                }
            }
        }

        // Return results with accumulated mustBeOpenRowVars and errors from per-binding solving
        return ProgramConstraintResult(
            constraintResults = results,
            perBindingMustBeOpenRowVars = solver.getMustBeOpenRowVars(),
            perBindingSolveErrors = perBindingSolveErrors
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
        binding: LetBinding,
        context: TypingContext,
        recordLiteralBindings: Set<String>,
        preConvertedAnnotationType: FunkType? = null
    ): ConstraintResult {
        // DEBUG
        if (System.getProperty("funk.debug.abilities") == "true") {
            println("[DEBUG generateLetBinding] binding=${binding.variableName} expr=${binding.expression.javaClass.simpleName}")
            if (binding.expression is Identifier) {
                println("[DEBUG generateLetBinding] Identifier='${(binding.expression as Identifier).name}'")
            }
        }

        // BIDIRECTIONAL TYPING: If there's a type annotation, use it as the expected type
        // This is CRITICAL for polymorphic functions to work correctly
        // Matches old typechecker's approach (OldTypeChecker.kt line 4055-4169)
        // CRITICAL: Use pre-converted type if available to ensure same TypeVar instances as placeholders!
        val expectedType = preConvertedAnnotationType ?: binding.typeAnnotation?.let { convertTypeAnnotation(it) }
        val constraintContext = ConstraintContext(
            context,
            expectedType = expectedType,
            performerToEffect = performerToEffect,
            abilityNames = abilityNames,
            effectNames = effectNames,
            abilityDeclarations = abilityDeclarations,
            recordLiteralBindings = recordLiteralBindings,
            typeDeclarations = typeDeclarations,
            currentBindingName = getBindingName(binding)
        )

        // Generate constraints for the expression with expected type
        val exprResult = expressionGenerator.generate(
            binding.expression,
            constraintContext
        )

        // Generate pattern constraints
        // The pattern must match the expression's type
        val patternResult = patternGenerator.generate(
            binding.pattern,
            exprResult.type,
            constraintContext
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
            validateAnnotationAgainstExpression(binding, annotatedType, typeAnnotationValue, binding.expression, exprResult.type)

            // Extract ability constraints from annotation if it's a function type
            val annotationAbilityConstraints = if (typeAnnotationValue is FunctionTypeAnnotation) {
                typeAnnotationValue.abilityConstraints.flatMap { constraint ->
                    // Create Constraint.AbilityRequired for each type argument
                    constraint.typeArgs.map { typeArg ->
                        val typeArgType = convertTypeAnnotation(typeArg)
                        val typeVar = when (typeArgType) {
                            is FunkType.VarType -> typeArgType.variable
                            else -> TypeVar.fresh() // Shouldn't happen, but handle it
                        }
                        Constraint.AbilityRequired(
                            typeVar = typeVar,
                            abilityName = constraint.abilityName,
                            typeArgs = emptyList(),
                            context = "Type annotation on ${getBindingName(binding)}"
                        )
                    }
                }
            } else {
                emptyList()
            }

            val constraintsWithAnnotation = allConstraints + Constraint.Equality(
                left = exprResult.type,
                right = annotatedType,
                context = "Type annotation on ${getBindingName(binding)}"
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
        expr: Expression,
        context: ConstraintContext
    ): ConstraintResult {
        return expressionGenerator.generate(expr, context)
    }

    /**
     * Generate constraints for a pattern.
     * Delegates to PatternConstraintGenerator.
     */
    fun generatePattern(
        pattern: Pattern,
        scrutineeType: FunkType,
        context: ConstraintContext
    ): PatternConstraintResult {
        return patternGenerator.generate(pattern, scrutineeType, context)
    }

    /**
     * Helper: Get all free type variables from context bindings.
     * Used for let-polymorphism to determine which variables to quantify.
     *
     * @param excludeBindings Names of bindings to exclude (e.g., the binding being generalized)
     *                        This is CRITICAL for recursive functions - when generalizing `singleton`,
     *                        we should NOT include `singleton`'s placeholder type in the context vars.
     */
    private fun getContextFreeVars(context: TypingContext, excludeBindings: Set<String> = emptySet()): Set<TypeVar> {
        val freeVars = mutableSetOf<TypeVar>()
        for ((name, binding) in context.getAllBindings()) {
            // Skip bindings that should be excluded (like the binding being generalized)
            if (name in excludeBindings) continue

            when (binding) {
                is TypingContext.Binding.Monomorphic -> {
                    freeVars.addAll(com.funk.language.typechecker.context.collectFreeTypeVars(binding.type))
                }
                is TypingContext.Binding.Polymorphic -> {
                    // Don't include quantified variables - they're bound by the scheme
                    // Only include free variables in the type that aren't quantified
                    val typeFreeVars = com.funk.language.typechecker.context.collectFreeTypeVars(binding.scheme.type)
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
        type: FunkType,
        abilityConstraints: List<Constraint.AbilityRequired>,
        substitution: com.funk.language.typechecker.solving.Substitution
    ): FunkType {
        // DEBUG: Print what we're receiving
        if (System.getProperty("funk.debug.constraints") == "true") {
            println("[DEBUG] addAbilityConstraintsToFunctionType called:")
            println("  - Type: $type")
            if (type is FunkType.FunctionType) {
                println("  - Param type: ${type.paramType}")
                if (type.paramType is FunkType.RecordType) {
                    val record = type.paramType as FunkType.RecordType
                    println("    - Record fields:")
                    record.fields.forEach { (name, fieldType) ->
                        println("      * $name: $fieldType")
                    }
                }
                println("  - Return type: ${type.returnType}")
            }
            println("  - Ability constraints received: ${abilityConstraints.size} constraints")
            abilityConstraints.forEach { println("    * $it") }
        }

        // Only add ability constraints to function types
        if (type !is FunkType.FunctionType) {
            return type
        }

        // Collect all type variables from the function type
        val typeVars = mutableSetOf<TypeVar>()
        fun collectTypeVars(t: FunkType) {
            when (t) {
                is FunkType.VarType -> typeVars.add(t.variable)
                is FunkType.RecordType -> t.fields.values.forEach { collectTypeVars(it) }
                is FunkType.FunctionType -> {
                    collectTypeVars(t.paramType)
                    collectTypeVars(t.returnType)
                }
                is FunkType.TypeApplication -> t.args.forEach { collectTypeVars(it) }
                is FunkType.VariantType -> t.constructors.values.forEach { it?.let { collectTypeVars(it) } }
                is FunkType.ListType -> collectTypeVars(t.elementType)
                is FunkType.ArrayType -> collectTypeVars(t.elementType)
                is FunkType.SetType -> collectTypeVars(t.elementType)
                is FunkType.MapType -> {
                    collectTypeVars(t.keyType)
                    collectTypeVars(t.valueType)
                }
                is FunkType.SuspendedType -> collectTypeVars(t.valueType)
                else -> {} // Primitive types have no type variables
            }
        }

        collectTypeVars(type.paramType)
        collectTypeVars(type.returnType)

        // DEBUG: Print collected type vars
        if (System.getProperty("funk.debug.constraints") == "true") {
            println("[DEBUG] Collected type vars from function type:")
            typeVars.forEach { println("    * ${it.name} (id=${it.id})") }
        }

        // Find ability constraints that apply to type variables in the function
        // CRITICAL: Match by type variable NAME (after substitution), not by TypeVar instance
        // This handles cases where substitution renamed type variables
        val typeVarNames = typeVars.map { it.name }.toSet()

        val functionAbilityConstraints = abilityConstraints.mapNotNull { constraint ->
            // Apply substitution to get the solved type variable
            val solvedTypeVar = substitution.apply(FunkType.VarType(constraint.typeVar))

            if (solvedTypeVar is FunkType.VarType) {
                // Check if this type variable appears in our function type
                val matchingTypeVar = typeVars.find { it.name == solvedTypeVar.variable.name }

                if (matchingTypeVar != null && constraint.abilityName != "Inspect") {
                    // CRITICAL FIX: Filter out Inspect - it's universally available
                    // and shouldn't appear in type signatures (matches OldTypeChecker line 2651-2652)
                    FunkType.FunctionAbilityConstraint(
                        abilityName = constraint.abilityName,
                        typeArgs = listOf(FunkType.VarType(matchingTypeVar))
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
                    is FunkType.VarType -> arg.variable.name
                    else -> arg.toString()
                }
            }
        }

        // DEBUG: Print result
        if (System.getProperty("funk.debug.constraints") == "true") {
            println("[DEBUG] addAbilityConstraintsToFunctionType result:")
            println("  - All constraints: ${allConstraints.size} constraints")
            allConstraints.forEach { println("    * $it") }
            println("  - Function ability constraints added: ${functionAbilityConstraints.size}")
            functionAbilityConstraints.forEach { println("    * $it") }
        }

        return FunkType.FunctionType(
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
    private fun getBindingName(binding: LetBinding): String {
        // Extract all bindings from the pattern
        val bindings = binding.pattern.extractBindings()

        // Return first binding name, or a placeholder if no bindings
        return bindings.firstOrNull() ?: when (binding.pattern) {
            is WildcardPattern -> "_wildcard"
            is LiteralPattern -> "_literal"
            else -> "_pattern"
        }
    }

    /**
     * Helper: Convert TypeAnnotation to FunkType
     * (Reuse logic from ContextBuilder)
     *
     * Public entry point - clears cache before conversion to ensure
     * type variables in separate annotations don't alias
     * Matches old typechecker behavior EXACTLY
     */
    private fun convertTypeAnnotation(annotation: TypeAnnotation): FunkType {
        typeVarCache.clear()
        return convertTypeAnnotationInternal(annotation)
    }

    /**
     * Internal recursive type annotation converter.
     * Uses cached type variables to ensure consistency within a single annotation.
     */
    private fun convertTypeAnnotationInternal(annotation: TypeAnnotation): FunkType {
        return when (annotation) {
            is IdentifierTypeAnnotation -> {
                when (annotation.name) {
                    "Int" -> FunkType.IntType
                    "Float" -> FunkType.FloatType
                    "String" -> FunkType.StringType
                    "Bool" -> FunkType.VariantType(
                        constructors = mapOf("True" to null, "False" to null),
                        rowVar = null
                    )
                    "Nat" -> FunkType.NatType
                    "Frac" -> FunkType.FracType
                    "Ordering" -> FunkType.OrderingType
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
                            FunkType.VarType(typeVar)
                        } else {
                            FunkType.TypeApplication(annotation.name, emptyList())
                        }
                    }
                }
            }
            is FunctionTypeAnnotation -> {
                // Convert ability constraints from annotation to FunkType.FunctionAbilityConstraint
                val abilityConstraints = annotation.abilityConstraints.map { constraint ->
                    FunkType.FunctionAbilityConstraint(
                        abilityName = constraint.abilityName,
                        typeArgs = constraint.typeArgs.map { convertTypeAnnotationInternal(it) }
                    )
                }

                FunkType.FunctionType(
                    paramType = convertTypeAnnotationInternal(annotation.paramType),
                    returnType = convertTypeAnnotationInternal(annotation.returnType),
                    effects = annotation.effects?.let { effectRow ->
                        FunkType.EffectRow(effectRow.effects.toSet())
                    },
                    abilityConstraints = abilityConstraints
                )
            }
            is RecordTypeAnnotation -> {
                FunkType.RecordType(
                    fields = annotation.entries.associate { entry ->
                        entry.name to convertTypeAnnotationInternal(entry.type)
                    },
                    rowVar = annotation.restField?.let { RowVar(it) }
                )
            }
            is VariantTypeAnnotation -> {
                FunkType.VariantType(
                    constructors = annotation.constructors.associate { ctor ->
                        ctor.name to ctor.dataType?.let { convertTypeAnnotationInternal(it) }
                    }
                )
            }
            is TypeApplicationAnnotation -> {
                FunkType.TypeApplication(
                    constructor = annotation.constructor,
                    args = annotation.args.map { convertTypeAnnotationInternal(it) }
                )
            }
            is ArrayTypeAnnotation -> {
                FunkType.ArrayType(
                    elementType = convertTypeAnnotationInternal(annotation.elementType)
                )
            }
            is EffectRowAnnotation -> {
                FunkType.EffectRow(annotation.effects.toSet())
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
    private fun validateAbilityImplementation(impl: ImplAbilityDeclaration, context: TypingContext) {
        // Find the ability declaration
        val abilityDeclaration = abilityDeclarations[impl.abilityName]
            ?: throw com.funk.language.typechecker.TypeCheckError(
                "Ability '${impl.abilityName}' not found - cannot validate implementation"
            )

        // Build substitution map: 'a -> Int, etc. (type parameters from target types)
        val typeParamSubstitution = abilityDeclaration.typeParams.zip(impl.targetTypes).associate { (param, targetType) ->
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
            if (implOp.implementation is LambdaExpression) {
                val lambda = implOp.implementation as LambdaExpression

                // Extract parameter names from lambda
                val paramNames = when (val params = lambda.parameters) {
                    is UntypedParameters -> params.names
                    is TypedParameters -> params.recordType.entries.map { it.name }
                }

                // Build parameter type map for scoped context
                val paramTypes = if (expectedParamType is FunkType.RecordType) {
                    paramNames.associateWith { name ->
                        expectedParamType.fields[name] ?: FunkType.VarType(TypeVar.fresh())
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

                val constraintContext = ConstraintContext(
                    bodyContext,
                    expectedType = null,  // Synthesize type, don't check against expected
                    performerToEffect = performerToEffect,
                    abilityNames = abilityNames,
                    effectNames = effectNames,
                    abilityDeclarations = abilityDeclarations,
                    typeDeclarations = typeDeclarations
                )

                // Generate constraints for lambda body
                val bodyResult = expressionGenerator.generate(lambda.body, constraintContext)

                // Check for undefined variables in the body
                if (bodyResult.undefinedVariables.isNotEmpty()) {
                    val undefinedVar = bodyResult.undefinedVariables.first()
                    throw com.funk.language.typechecker.TypeCheckError(
                        "Type error in ability '${impl.abilityName}' operation '${implOp.name}' implementation: " +
                        "Undefined variable '${undefinedVar.name}'"
                    )
                }

                // Solve constraints to get actual body type
                val solver = com.funk.language.typechecker.solving.ConstraintSolver(bodyContext, typeDeclarations, abilityDeclarations)
                val solveResult = solver.solve(bodyResult.constraints)

                when (solveResult) {
                    is com.funk.language.typechecker.solving.ConstraintSolver.SolveResult.Failure -> {
                        throw com.funk.language.typechecker.TypeCheckError(
                            "Type error in ability '${impl.abilityName}' operation '${implOp.name}' implementation: ${solveResult.errors.firstOrNull()?.message ?: "Unknown error"}"
                        )
                    }
                    is com.funk.language.typechecker.solving.ConstraintSolver.SolveResult.Success -> {
                        val substitution = solveResult.substitution
                        val actualBodyType = substitution.apply(bodyResult.type)

                        // Check that actual body type matches expected return type
                        // Use pretty printing for comparison (catches Int vs String mismatches)
                        val actualTypeStr = com.funk.language.ast.prettyPrintType(actualBodyType)
                        val expectedTypeStr = com.funk.language.ast.prettyPrintType(expectedReturnType)

                        if (actualTypeStr != expectedTypeStr) {
                            // Types differ - check if this is due to polymorphism
                            val hasTypeVariables = actualTypeStr.contains("'") || expectedTypeStr.contains("'")

                            if (hasTypeVariables) {
                                // Try unification for polymorphic types
                                val unificationSolver = com.funk.language.typechecker.solving.ConstraintSolver(bodyContext, typeDeclarations, abilityDeclarations)
                                val unificationResult = unificationSolver.solve(
                                    listOf(Constraint.Equality(actualBodyType, expectedReturnType, "Ability body validation"))
                                )

                                when (unificationResult) {
                                    is com.funk.language.typechecker.solving.ConstraintSolver.SolveResult.Failure -> {
                                        throw com.funk.language.typechecker.TypeCheckError(
                                            "Implementation body type mismatch in ability '${impl.abilityName}' operation '${implOp.name}': " +
                                            "body has type $actualTypeStr but operation signature requires $expectedTypeStr"
                                        )
                                    }
                                    is com.funk.language.typechecker.solving.ConstraintSolver.SolveResult.Success -> {
                                        // Unification succeeded - types are compatible
                                    }
                                }
                            } else {
                                // Concrete types that don't match - strict error
                                throw com.funk.language.typechecker.TypeCheckError(
                                    "Implementation body type mismatch in ability '${impl.abilityName}' operation '${implOp.name}': " +
                                    "body has type $actualTypeStr but operation signature requires $expectedTypeStr"
                                )
                            }
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
    private fun validateEffectImplementation(impl: ImplDeclaration, context: TypingContext) {
        // Find the effect declaration
        val effectDeclaration = effectDeclarations[impl.effectName]
            ?: return  // Effect not found - will be caught elsewhere

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
        for (param in impl.performerParams) {
            val paramType = convertTypeAnnotation(param.type)
            baseContext = baseContext.extendMono(param.name, paramType)
        }

        // Validate each operation's implementation body
        for (implOp in impl.operations) {
            val declOp = effectDeclaration.operations.find { it.name == implOp.name }
                ?: continue  // Extra operations caught elsewhere

            // Convert param and return types from the declaration
            val declParamType = convertTypeAnnotation(declOp.paramType)
            val declReturnType = convertTypeAnnotation(declOp.returnType)

            // Substitute associated types to get expected types
            // Example: {} -> 'state <State> with 'state = Int becomes {} -> Int <State>
            val expectedParamType = substituteTypeParams(declParamType, fullSubstitution)
            val expectedReturnType = substituteTypeParams(declReturnType, fullSubstitution)

            // Type-check the implementation body (must be a lambda)
            if (implOp.implementation is LambdaExpression) {
                val lambda = implOp.implementation as LambdaExpression

                // Extract parameter names from lambda
                val paramNames = when (val params = lambda.parameters) {
                    is UntypedParameters -> params.names
                    is TypedParameters -> params.recordType.entries.map { it.name }
                }

                // Build parameter type map for scoped context
                val paramTypes = if (expectedParamType is FunkType.RecordType) {
                    paramNames.associateWith { name ->
                        expectedParamType.fields[name] ?: FunkType.VarType(TypeVar.fresh())
                    }
                } else {
                    // Single parameter case - wrap in record
                    paramNames.associateWith { expectedParamType }
                }

                // Type-check the lambda body with parameters in scope (including performer params)
                var bodyContext = baseContext
                for ((name, type) in paramTypes) {
                    bodyContext = bodyContext.extendMono(name, type)
                }

                val constraintContext = ConstraintContext(
                    bodyContext,
                    expectedType = null,
                    performerToEffect = performerToEffect,
                    abilityNames = abilityNames,
                    effectNames = effectNames,
                    abilityDeclarations = abilityDeclarations,
                    typeDeclarations = typeDeclarations
                )

                // Generate constraints for lambda body
                val bodyResult = expressionGenerator.generate(lambda.body, constraintContext)

                // If there are undefined variables, skip detailed validation
                // The body may reference external performers or functions not yet in context
                // Full validation will happen during the normal type-checking phase
                if (bodyResult.undefinedVariables.isNotEmpty()) {
                    // Skip validation - let the normal type-checking catch errors later
                    continue
                }

                // Solve constraints to get actual body type
                val solver = com.funk.language.typechecker.solving.ConstraintSolver(bodyContext, typeDeclarations, abilityDeclarations)
                val solveResult = solver.solve(bodyResult.constraints)

                when (solveResult) {
                    is com.funk.language.typechecker.solving.ConstraintSolver.SolveResult.Failure -> {
                        // Solver failures might be due to external references
                        // Skip validation - let the normal type-checking catch errors later
                        continue
                    }
                    is com.funk.language.typechecker.solving.ConstraintSolver.SolveResult.Success -> {
                        val substitution = solveResult.substitution
                        val actualBodyType = substitution.apply(bodyResult.type)

                        // Check that actual body type matches expected return type using unification
                        // This is more permissive than string comparison - it allows [] to unify with List String
                        val unificationSolver = com.funk.language.typechecker.solving.ConstraintSolver(bodyContext, typeDeclarations, abilityDeclarations)
                        val unificationResult = unificationSolver.solve(
                            listOf(Constraint.Equality(actualBodyType, expectedReturnType, "Effect body validation"))
                        )

                        when (unificationResult) {
                            is com.funk.language.typechecker.solving.ConstraintSolver.SolveResult.Failure -> {
                                val actualTypeStr = com.funk.language.ast.prettyPrintType(actualBodyType)
                                val expectedTypeStr = com.funk.language.ast.prettyPrintType(expectedReturnType)
                                throw com.funk.language.typechecker.TypeCheckError(
                                    "Implementation body type mismatch in effect '${impl.effectName}' operation '${implOp.name}': " +
                                    "body has type $actualTypeStr but operation signature requires $expectedTypeStr"
                                )
                            }
                            is com.funk.language.typechecker.solving.ConstraintSolver.SolveResult.Success -> {
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
    private fun substituteTypeParams(type: FunkType, substitution: Map<String, FunkType>): FunkType {
        return when (type) {
            is FunkType.VarType -> {
                // Check if this type variable should be substituted
                substitution[type.variable.name] ?: type
            }
            is FunkType.FunctionType -> FunkType.FunctionType(
                paramType = substituteTypeParams(type.paramType, substitution),
                returnType = substituteTypeParams(type.returnType, substitution),
                effects = type.effects,
                abilityConstraints = type.abilityConstraints
            )
            is FunkType.RecordType -> FunkType.RecordType(
                fields = type.fields.mapValues { (_, fieldType) ->
                    substituteTypeParams(fieldType, substitution)
                },
                rowVar = type.rowVar
            )
            is FunkType.VariantType -> FunkType.VariantType(
                constructors = type.constructors.mapValues { (_, payloadType) ->
                    payloadType?.let { substituteTypeParams(it, substitution) }
                },
                rowVar = type.rowVar
            )
            is FunkType.TypeApplication -> FunkType.TypeApplication(
                constructor = type.constructor,
                args = type.args.map { substituteTypeParams(it, substitution) }
            )
            is FunkType.ListType -> FunkType.ListType(
                elementType = substituteTypeParams(type.elementType, substitution)
            )
            is FunkType.ArrayType -> FunkType.ArrayType(
                elementType = substituteTypeParams(type.elementType, substitution)
            )
            is FunkType.SetType -> FunkType.SetType(
                elementType = substituteTypeParams(type.elementType, substitution)
            )
            is FunkType.MapType -> FunkType.MapType(
                keyType = substituteTypeParams(type.keyType, substitution),
                valueType = substituteTypeParams(type.valueType, substitution)
            )
            is FunkType.SuspendedType -> FunkType.SuspendedType(
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
        binding: LetBinding,
        annotatedType: FunkType,
        annotation: TypeAnnotation,
        expression: Expression,
        inferredType: FunkType
    ) {
        // 1. RECORD EXTRA FIELD VALIDATION
        // Check if annotation is a closed record type and expression is a record literal with extra fields
        if (annotatedType is FunkType.RecordType && expression is RecordLiteral) {
            // Only validate if annotation is closed (no rowVar)
            if (annotatedType.rowVar == null) {
                val annotatedFields = annotatedType.fields.keys
                val literalFields = mutableSetOf<String>()

                // Extract field names from record literal
                for (entry in expression.entries) {
                    when (entry) {
                        is RecordFieldEntry -> literalFields.add(entry.name)
                        is RecordSpreadEntry -> {
                            // Spread entries make validation complex - skip for now
                            // The constraint solver will handle these cases
                            return
                        }
                    }
                }

                // Check for extra fields
                val extraFields = literalFields - annotatedFields
                if (extraFields.isNotEmpty()) {
                    throw com.funk.language.typechecker.TypeCheckError(
                        "Type annotation mismatch: record has extra field(s) ${extraFields.sorted().joinToString(", ")} " +
                        "not present in closed record type annotation { ${annotatedFields.sorted().joinToString(", ") { "$it: ..." }} }"
                    )
                }

                // Check for missing fields
                val missingFields = annotatedFields - literalFields
                if (missingFields.isNotEmpty()) {
                    throw com.funk.language.typechecker.TypeCheckError(
                        "Type annotation mismatch: record is missing field(s) ${missingFields.sorted().joinToString(", ")} " +
                        "required by type annotation { ${annotatedFields.sorted().joinToString(", ") { "$it: ..." }} }"
                    )
                }
            }
        }

        // 2. FUNCTION ARITY VALIDATION
        // Check if annotation is a function type and expression is a lambda with mismatched parameters
        if (annotatedType is FunkType.FunctionType && expression is LambdaExpression) {
            val annotatedParamType = annotatedType.paramType

            // Get parameter count from annotation
            val expectedParamCount = when (annotatedParamType) {
                is FunkType.RecordType -> annotatedParamType.fields.size
                else -> 1 // Single parameter (not a record)
            }

            // Get parameter count from lambda
            val actualParamCount = when (val params = expression.parameters) {
                is UntypedParameters -> params.names.size
                is TypedParameters -> params.recordType.entries.size
            }

            // Check if counts match
            if (expectedParamCount != actualParamCount) {
                throw com.funk.language.typechecker.TypeCheckError(
                    "Type annotation mismatch: function expects $expectedParamCount parameter(s) " +
                    "but lambda has $actualParamCount parameter(s)"
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
        annotationType: FunkType,
        substitution: com.funk.language.typechecker.solving.Substitution,
        varName: String
    ) {
        // Collect all type variables from the annotation
        val annotationTypeVars = collectTypeVars(annotationType)

        // Check each type variable - if substitution maps it to a FULLY CONCRETE type, that's an error
        for (typeVar in annotationTypeVars) {
            val substituted = substitution.apply(FunkType.VarType(typeVar))
            if (substituted !is FunkType.VarType) {
                // Check if the substituted type is FULLY concrete (no type variables)
                val substitutedTypeVars = collectTypeVars(substituted)
                if (substitutedTypeVars.isEmpty()) {
                    throw com.funk.language.typechecker.TypeCheckError(
                        "Type annotation mismatch for '$varName': Annotation declares type variable '${typeVar.name}' " +
                        "but implementation forces it to concrete type '${com.funk.language.ast.prettyPrintType(substituted)}'. " +
                        "The implementation must work for all types, not just specific ones."
                    )
                }
            }
        }
    }

    /**
     * Collect all type variables from a type.
     */
    private fun collectTypeVars(type: FunkType): Set<TypeVar> {
        val result = mutableSetOf<TypeVar>()
        collectTypeVarsRecursive(type, result)
        return result
    }

    private fun collectTypeVarsRecursive(type: FunkType, result: MutableSet<TypeVar>) {
        when (type) {
            is FunkType.VarType -> result.add(type.variable)
            is FunkType.FunctionType -> {
                collectTypeVarsRecursive(type.paramType, result)
                collectTypeVarsRecursive(type.returnType, result)
            }
            is FunkType.RecordType -> {
                type.fields.values.forEach { collectTypeVarsRecursive(it, result) }
            }
            is FunkType.VariantType -> {
                type.constructors.values.filterNotNull().forEach { collectTypeVarsRecursive(it, result) }
            }
            is FunkType.TypeApplication -> {
                type.args.forEach { collectTypeVarsRecursive(it, result) }
            }
            is FunkType.ArrayType -> collectTypeVarsRecursive(type.elementType, result)
            is FunkType.ListType -> collectTypeVarsRecursive(type.elementType, result)
            is FunkType.SetType -> collectTypeVarsRecursive(type.elementType, result)
            is FunkType.MapType -> {
                collectTypeVarsRecursive(type.keyType, result)
                collectTypeVarsRecursive(type.valueType, result)
            }
            // Concrete types have no type variables
            is FunkType.IntType,
            is FunkType.FloatType,
            is FunkType.StringType,
            is FunkType.BooleanType,
            is FunkType.NatType,
            is FunkType.FracType,
            is FunkType.OrderingType -> { /* No type variables */ }
            // Effect and suspended types - check inner types
            is FunkType.EffectRow -> { /* Effect names, no type variables to collect */ }
            is FunkType.SuspendedType -> collectTypeVarsRecursive(type.valueType, result)
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
    private fun containsConcreteTypes(type: FunkType): Boolean {
        return when (type) {
            is FunkType.VarType -> false
            is FunkType.FunctionType ->
                containsConcreteTypes(type.paramType) || containsConcreteTypes(type.returnType)
            is FunkType.RecordType ->
                type.fields.values.any { containsConcreteTypes(it) }
            is FunkType.VariantType -> {
                // A variant type IS concrete even if constructors have no payloads
                // For example, |Red, Blue, Green| is a concrete type (not polymorphic)
                type.constructors.isNotEmpty() || type.constructors.values.any { it != null && containsConcreteTypes(it) }
            }
            is FunkType.TypeApplication ->
                type.args.any { containsConcreteTypes(it) }
            is FunkType.ArrayType ->
                containsConcreteTypes(type.elementType)
            is FunkType.ListType ->
                containsConcreteTypes(type.elementType)
            is FunkType.SetType ->
                containsConcreteTypes(type.elementType)
            is FunkType.MapType ->
                containsConcreteTypes(type.keyType) || containsConcreteTypes(type.valueType)
            // Concrete types: IntType, StringType, BoolType, FloatType, UnitType
            is FunkType.IntType,
            is FunkType.StringType,
            is FunkType.BooleanType,
            is FunkType.FloatType -> true
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
        annotationType: FunkType,
        inferredType: FunkType,
        varName: String,
        expression: Expression
    ) {
        if (annotationType is FunkType.FunctionType && inferredType is FunkType.FunctionType) {
            val annotatedEffects = annotationType.effects?.effects ?: emptySet()
            val inferredEffects = inferredType.effects?.effects ?: emptySet()

            // Check if implementation uses effects not declared in annotation
            val extraEffects = inferredEffects - annotatedEffects
            if (extraEffects.isNotEmpty()) {
                throw com.funk.language.typechecker.TypeCheckError(
                    "Type annotation mismatch for '$varName': Implementation uses effects ${extraEffects.joinToString(", ")} " +
                    "which are not declared in the annotation."
                )
            }

            // Check if annotation declares effects not used in implementation
            val missingEffects = annotatedEffects - inferredEffects
            if (missingEffects.isNotEmpty()) {
                throw com.funk.language.typechecker.TypeCheckError(
                    "Type annotation mismatch for '$varName': Annotation declares effects ${missingEffects.joinToString(", ")} " +
                    "which are not used in the implementation."
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
        annotationType: FunkType,
        inferredType: FunkType,
        varName: String
    ) {
        if (annotationType is FunkType.FunctionType && inferredType is FunkType.FunctionType) {
            val annotatedEffects = annotationType.effects?.effects ?: emptySet()
            val inferredEffects = inferredType.effects?.effects ?: emptySet()

            // Check if annotation declares effects not used in implementation (overpromising)
            val overpromisedEffects = annotatedEffects - inferredEffects
            if (overpromisedEffects.isNotEmpty()) {
                throw com.funk.language.typechecker.TypeCheckError(
                    "Type annotation mismatch for '$varName': Annotation declares effects ${overpromisedEffects.joinToString(", ")} " +
                    "which are not used in the implementation."
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
        annotationType: FunkType,
        inferredAbilityConstraints: List<Constraint.AbilityRequired>,
        varName: String,
        substitution: com.funk.language.typechecker.solving.Substitution
    ) {
        if (annotationType !is FunkType.FunctionType) return

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
            val resolved1 = substitution.apply(FunkType.VarType(tv1))
            val resolved2 = substitution.apply(FunkType.VarType(tv2))
            // They're unified if they resolve to the same thing
            return resolved1 == resolved2 ||
                   (resolved1 is FunkType.VarType && resolved1.variable.id == tv2.id) ||
                   (resolved2 is FunkType.VarType && resolved2.variable.id == tv1.id)
        }

        for (usage in actualUsageConstraints) {
            if (usage.abilityName == "Inspect") continue  // Skip implicit Inspect

            val matchingAnnotated = annotatedAbilities.find { it.abilityName == usage.abilityName }

            if (matchingAnnotated != null) {
                // Check type variables are unified through substitution
                for (annotatedTypeArg in matchingAnnotated.typeArgs) {
                    if (annotatedTypeArg is FunkType.VarType) {
                        // Check if usage.typeVar is unified with the annotation's type var
                        if (!areUnified(usage.typeVar, annotatedTypeArg.variable)) {
                            throw com.funk.language.typechecker.TypeCheckError(
                                "Type annotation mismatch for '$varName': Ability '${usage.abilityName}' is applied to wrong type variable. " +
                                "Annotation declares constraint on '${annotatedTypeArg.variable.name}' but implementation uses it on '${usage.typeVar.name}'."
                            )
                        }
                    }
                }
            } else {
                // Implementation uses ability not in annotation - error if on annotation's type var
                // Check if usage.typeVar is unified with any annotation type var
                val isOnAnnotationTypeVar = annotationTypeVars.any { annotationVar ->
                    areUnified(usage.typeVar, annotationVar)
                }
                if (isOnAnnotationTypeVar) {
                    throw com.funk.language.typechecker.TypeCheckError(
                        "Type annotation mismatch for '$varName': Implementation requires '${usage.abilityName}' on type variable '${usage.typeVar.name}', " +
                        "but the annotation does not declare this constraint."
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
        annotationType: FunkType,
        inferredType: FunkType,
        inferredAbilityConstraints: List<Constraint.AbilityRequired>,
        varName: String,
        expression: Expression
    ) {
        if (annotationType is FunkType.FunctionType && inferredType is FunkType.FunctionType) {
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
                if (usage.abilityName == "Inspect") continue

                // Find matching annotation constraint by ability name
                val matchingAnnotated = annotatedAbilities.find { it.abilityName == usage.abilityName }
                if (matchingAnnotated != null) {
                    // Check that the type variables match
                    // The annotation's type arg should reference the same type variable as the usage
                    for ((i, annotatedTypeArg) in matchingAnnotated.typeArgs.withIndex()) {
                        if (annotatedTypeArg is FunkType.VarType) {
                            // The usage's typeVar should match the annotation's type variable
                            if (usage.typeVar.name != annotatedTypeArg.variable.name) {
                                throw com.funk.language.typechecker.TypeCheckError(
                                    "Type annotation mismatch for '$varName': Ability '${usage.abilityName}' is applied to wrong type variable. " +
                                    "Annotation declares '${annotatedTypeArg.variable.name}' but implementation uses '${usage.typeVar.name}'."
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
                    if (annotated.abilityName == "Inspect") continue

                    // Skip abilities on concrete types - they don't need to be used
                    val hasConcreteTypes = annotated.typeArgs.any { it !is FunkType.VarType }
                    if (hasConcreteTypes) continue

                    val matchingInferred = actualUsageConstraints.find { it.abilityName == annotated.abilityName }
                    if (matchingInferred == null) {
                        throw com.funk.language.typechecker.TypeCheckError(
                            "Type annotation mismatch for '$varName': Annotation declares ability '${annotated.abilityName}' " +
                            "which is not used in the implementation."
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
    private fun containsFunctionCalls(expr: Expression): Boolean {
        return when (expr) {
            is FunctionCall -> true
            is LambdaExpression -> containsFunctionCalls(expr.body)
            is IfExpression -> {
                containsFunctionCalls(expr.condition) ||
                containsFunctionCalls(expr.thenBranch) ||
                containsFunctionCalls(expr.elseBranch)
            }
            is MatchExpression -> {
                containsFunctionCalls(expr.expression) ||
                expr.cases.any { containsFunctionCalls(it.expression) }
            }
            is ArrayLiteral -> expr.elements.any { containsFunctionCalls(it) }
            is RecordLiteral -> expr.entries.any { entry ->
                when (entry) {
                    is RecordFieldEntry -> entry.value?.let { containsFunctionCalls(it) } ?: false
                    is RecordSpreadEntry -> containsFunctionCalls(entry.expression)
                }
            }
            is MemberAccess -> containsFunctionCalls(expr.receiver)
            // String interpolation can contain function calls in its parts
            is StringLiteral -> {
                expr.parts?.any { part ->
                    part is ExpressionPart && containsFunctionCalls(part.expression)
                } ?: false
            }
            // For DoExpression and other complex expressions, assume they may contain function calls
            is DoExpression -> true
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
                abilityNames,
                effectNames,
                abilityDeclarations,
                effectDeclarations
            )
        }
    }

    private fun debugTypeCG(type: FunkType?, depth: Int = 0): String {
        if (type == null) return "null"
        if (depth > 3) return "..."
        return when (type) {
            is FunkType.FunctionType -> "FunctionType(param=${debugTypeCG(type.paramType, depth + 1)}, return=${debugTypeCG(type.returnType, depth + 1)})"
            is FunkType.RecordType -> "RecordType(fields={${type.fields.map { (k, v) -> "$k: ${debugTypeCG(v, depth + 1)}" }.joinToString(", ")}}, rowVar=${type.rowVar})"
            is FunkType.VariantType -> "VariantType(constructors=${type.constructors.keys}, rowVar=${type.rowVar})"
            is FunkType.VarType -> "VarType(${type.variable})"
            else -> type::class.simpleName ?: type.toString()
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
 * - Bindings introduced by the pattern
 * - Constraints on the scrutinee type
 */
data class PatternConstraintResult(
    val bindings: Map<String, FunkType>,
    val constraints: List<Constraint>
)
