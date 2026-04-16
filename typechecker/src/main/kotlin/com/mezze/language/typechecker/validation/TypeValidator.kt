package com.mezze.language.typechecker.validation

import com.mezze.language.ast.*
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.constraints.Constraint
import com.mezze.language.typechecker.constraints.ConstraintResult
import com.mezze.language.typechecker.debug.DebugLogger
import com.mezze.language.typechecker.debug.DebugLogger.Category
import com.mezze.language.typechecker.solving.Substitution
import com.mezze.language.typechecker.solving.expandTypeApplicationToVariant
import com.mezze.language.typechecker.abilities.BuiltInAbilities
import com.mezze.language.typechecker.codec.CodecGenerator
import com.mezze.language.typechecker.hashing.ContentHashComputer
import com.mezze.language.typechecker.util.StringDistance
import com.mezze.language.typechecker.WellKnownHashes

/**
 * Type validator - validates types after constraint solving.
 *
 * This is PHASE 4 of the new architecture:
 * - PHASE 1: Build context (ContextBuilder)
 * - PHASE 2: Generate constraints (ConstraintGenerator)
 * - PHASE 3: Solve constraints (ConstraintSolver)
 * - PHASE 4: Emit TypedAST + V2 Pipeline (TypedAstBuilder, Monomorphizer, Resolver, Lowerer)
 * - PHASE 5: Validate types (TypeValidator, V2Validator) ← WE ARE HERE
 *
 * Validation checks:
 * - Ability constraints are satisfied
 * - Effects are properly handled
 * - Pattern matching is exhaustive
 * - Type applications are valid
 * - No circular dependencies
 *
 * Design:
 * - Pure functions (no mutation!)
 * - Returns sealed ValidationResult
 * - Exhaustive pattern matching on all constraint types
 * - Accumulates all errors (doesn't fail fast)
 */
class TypeValidator(
    private val context: TypingContext,
    private val typeDeclarations: Map<String, com.mezze.language.parsedast.ParsedTypeDecl> = emptyMap(),
    private val effectDeclarations: Map<String, com.mezze.language.parsedast.ParsedEffectDecl> = emptyMap(),
    private val implDeclarations: Map<String, com.mezze.language.parsedast.ParsedPerformerDecl> = emptyMap()
) {
    private val abilityResolver = AbilityResolver(context, typeDeclarations)
    private val effectValidator = EffectValidator(context)
    private val codecGenerator = CodecGenerator()

    // Cache for getTypeNameForAbility() - avoids repeated ContentHashComputer calls
    private val typeNameCache = mutableMapOf<Int, String>()

    /**
     * Validate a program after constraint solving.
     *
     * Returns Success with warnings, or Failure with errors.
     */
    /**
     * Validate using ParsedModule (v2 entry point).
     * Works directly on ParsedDecl types. Converts individual items to v1
     * only when deeper helpers still need v1 (pattern/expression validation).
     */
    fun validate(
        module: com.mezze.language.parsedast.ParsedModule,
        constraintResults: Map<String, ConstraintResult>,
        substitution: Substitution
    ): ValidationResult {
        val errors = mutableListOf<ValidationError>()
        val warnings = mutableListOf<String>()
        val parserBridge = com.mezze.language.parser.MezzeParser

        // Validate each binding from constraint results
        for ((bindingName, result) in constraintResults) {
            // Check for undefined variables
            if (result.undefinedVariables.isNotEmpty()) {
                for (undefinedVar in result.undefinedVariables) {
                    val allVariables = context.getAllBindings()
                    val candidateNames = allVariables.keys.map { key ->
                        if (key.qualifier != null) "${key.qualifier}.${key.localName}" else key.localName
                    }
                    val similarNames = findSimilarNames(undefinedVar.name, candidateNames)

                    errors.add(
                        ValidationError.VariableNotInScope(
                            variableName = undefinedVar.name,
                            similarNames = similarNames,
                            sourceLocation = undefinedVar.sourceLocation
                        )
                    )
                }
            }

            val finalType = substitution.apply(result.type)

            val abilityValueErrors = validateBuiltInAbilityUsage(finalType, bindingName)
            errors.addAll(abilityValueErrors)

            val abilityErrors = validateAbilityConstraints(
                result.abilityConstraints,
                finalType,
                bindingName,
                substitution
            )
            errors.addAll(abilityErrors)
        }

        val file = module.path

        // Validate type annotations, top-level effects, and patterns using v2 decls
        for (decl in module.decls) {
            when (decl) {
                is com.mezze.language.parsedast.ParsedLetDecl -> {
                    // Validate annotation (v2 path — uses ParsedTypeAnnotation directly)
                    val annotationErrors = validateParsedAnnotation(decl, substitution, constraintResults)
                    errors.addAll(annotationErrors)

                    // Validate top-level effect restrictions
                    val topLevelErrors = validateParsedTopLevelEffects(decl, substitution, constraintResults)
                    errors.addAll(topLevelErrors)

                    // Pattern and expression validation (v2 native)
                    val scrutineeType = if (decl.isSimpleBinding) {
                        constraintResults[decl.variableName]?.type?.let { substitution.apply(it) }
                    } else null
                    if (scrutineeType != null) {
                        errors.addAll(validateLetPattern(decl.pattern, scrutineeType))
                    }
                    errors.addAll(validatePatternsInExpression(decl.value, substitution))
                }
                is com.mezze.language.parsedast.ParsedTopLevelExpr -> {
                    errors.addAll(validatePatternsInExpression(decl.expression, substitution))
                }
                is com.mezze.language.parsedast.ParsedEffectDecl -> {
                    val declErrors = effectValidator.validateEffectDeclaration(decl)
                    errors.addAll(declErrors)
                }
                is com.mezze.language.parsedast.ParsedPerformerDecl -> {
                    val effectDecl = effectDeclarations[decl.effect.localName]
                    if (effectDecl != null) {
                        val implErrors = effectValidator.validateEffectImpl(decl, effectDecl)
                        errors.addAll(implErrors)
                    }
                }
                else -> { /* Other items don't need validation here */ }
            }
        }

        // Validate perform expressions (handler type compatibility) — v2 native
        val parsedFunctionDefs = mutableMapOf<String, com.mezze.language.parsedast.ParsedLambda>()
        for (decl in module.decls) {
            if (decl is com.mezze.language.parsedast.ParsedLetDecl && decl.isSimpleBinding) {
                val value = decl.value
                if (value is com.mezze.language.parsedast.ParsedLambda) parsedFunctionDefs[decl.variableName] = value
            }
        }
        for (decl in module.decls) {
            val performErrors: List<ValidationError> = when (decl) {
                is com.mezze.language.parsedast.ParsedLetDecl -> {
                    validatePerformExpressionsInExpression(decl.value, implDeclarations, effectDeclarations, parsedFunctionDefs)
                }
                is com.mezze.language.parsedast.ParsedTopLevelExpr -> {
                    validatePerformExpressionsInExpression(decl.expression, implDeclarations, effectDeclarations, parsedFunctionDefs)
                }
                else -> emptyList()
            }
            errors.addAll(performErrors)
        }

        // V2 match exhaustiveness pass — walks ParsedExpr directly, gets scrutinee type
        // from constraint results. Only checks matches that the v1 path can't handle:
        // matches where no constructor patterns exist (so v1 can't infer scrutinee type).
        for (decl in module.decls) {
            val expr = when (decl) {
                is com.mezze.language.parsedast.ParsedLetDecl -> decl.value
                is com.mezze.language.parsedast.ParsedTopLevelExpr -> decl.expression
                else -> null
            }
            if (expr != null) {
                val matchErrors = validateParsedMatchesInExpr(expr, substitution, constraintResults)
                errors.addAll(matchErrors)
            }
        }

        return if (errors.isEmpty()) {
            ValidationResult.Success(warnings)
        } else {
            ValidationResult.Failure(errors)
        }
    }

    /**
     * Walk a ParsedExpr tree and validate exhaustiveness for all ParsedMatch nodes.
     * Uses constraint results to infer scrutinee types (no v1 inferredType needed).
     */
    private fun validateParsedMatchesInExpr(
        expr: com.mezze.language.parsedast.ParsedExpr,
        substitution: Substitution,
        constraintResults: Map<String, ConstraintResult>
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        walkParsedExpr(expr) { node ->
            if (node is com.mezze.language.parsedast.ParsedMatch) {
                // Only check matches that have NO constructor patterns — the v1 path handles
                // matches with constructors fine. This catches wildcard/variable-only matches
                // where the v1 path can't infer the scrutinee type.
                val hasConstructorPattern = node.cases.any { case ->
                    case.patterns.any { it is com.mezze.language.parsedast.ParsedConstructorPattern }
                }
                if (!hasConstructorPattern) {
                    val scrutineeType = inferParsedScrutineeType(node, substitution, constraintResults)
                    if (scrutineeType != null) {
                        val result = validateParsedExhaustiveness(node, scrutineeType)
                        if (result is ValidationResult.Failure) {
                            errors.addAll(result.errors)
                        }
                    }
                }
            }
        }
        return errors
    }

    /** Infer the scrutinee type for a ParsedMatch from patterns or type declarations. */
    private fun inferParsedScrutineeType(
        matchExpr: com.mezze.language.parsedast.ParsedMatch,
        substitution: Substitution,
        constraintResults: Map<String, ConstraintResult>
    ): MezzeType? {
        // Try inferring from constructor patterns in the match cases
        val constructorNames = mutableSetOf<String>()
        for (case in matchExpr.cases) {
            for (pattern in case.patterns) {
                if (pattern is com.mezze.language.parsedast.ParsedConstructorPattern) {
                    constructorNames.add(pattern.constructor)
                }
            }
        }

        if (constructorNames.isNotEmpty()) {
            // Find a type declaration that contains these constructors
            for ((_, typeDecl) in typeDeclarations) {
                val definition = typeDecl.definition
                if (definition is com.mezze.language.parsedast.ParsedVariantDef) {
                    val variantConstructors = definition.constructors.map { it.name }.toSet()
                    if (constructorNames.all { it in variantConstructors }) {
                        return MezzeType.VariantType(
                            constructors = definition.constructors.associate { ctor ->
                                ctor.name to ctor.dataType?.let { convertParsedTypeAnnotation(it) }
                            },
                            rowVar = if (definition.isOpen) RowVar.fresh() else null
                        )
                    }
                }
            }
        }

        // Try inferring from scrutinee variable's type in enclosing function parameters
        val scrutinee = matchExpr.scrutinee
        if (scrutinee is com.mezze.language.parsedast.ParsedIdentifier) {
            val varName = scrutinee.localName
            // Search constraint results for a function type that has this parameter
            for ((_, result) in constraintResults) {
                val resolvedType = substitution.apply(result.type)
                val paramType = extractParamType(resolvedType, varName)
                if (paramType != null) {
                    val finalType = substitution.apply(paramType)
                    // Expand type applications to variant types
                    val expanded = when (finalType) {
                        is MezzeType.TypeApplication ->
                            expandTypeApplicationToVariant(finalType, typeDeclarations) ?: finalType
                        else -> finalType
                    }
                    if (expanded is MezzeType.VariantType) return expanded
                }
            }
        }

        return null
    }

    /** Extract a named parameter's type from a FunctionType's param record. */
    private fun extractParamType(type: MezzeType, paramName: String): MezzeType? {
        return when (type) {
            is MezzeType.FunctionType -> {
                val paramType = type.paramType
                if (paramType is MezzeType.RecordType) {
                    paramType.fields[paramName]
                } else null
            }
            else -> null
        }
    }

    /** Walk all sub-expressions of a ParsedExpr, calling visitor for each. */
    private fun walkParsedExpr(expr: com.mezze.language.parsedast.ParsedExpr, visitor: (com.mezze.language.parsedast.ParsedExpr) -> Unit) {
        visitor(expr)
        when (expr) {
            is com.mezze.language.parsedast.ParsedLambda -> walkParsedExpr(expr.body, visitor)
            is com.mezze.language.parsedast.ParsedFunctionCall -> {
                walkParsedExpr(expr.function, visitor)
                walkParsedExpr(expr.arguments, visitor)
            }
            is com.mezze.language.parsedast.ParsedMatch -> {
                walkParsedExpr(expr.scrutinee, visitor)
                for (case in expr.cases) {
                    case.guard?.let { walkParsedExpr(it, visitor) }
                    walkParsedExpr(case.body, visitor)
                }
            }
            is com.mezze.language.parsedast.ParsedIf -> {
                walkParsedExpr(expr.condition, visitor)
                walkParsedExpr(expr.thenBranch, visitor)
                walkParsedExpr(expr.elseBranch, visitor)
            }
            is com.mezze.language.parsedast.ParsedDo -> {
                for (stmt in expr.statements) {
                    when (stmt) {
                        is com.mezze.language.parsedast.ParsedStatement.Let -> walkParsedExpr(stmt.value, visitor)
                        is com.mezze.language.parsedast.ParsedStatement.Expr -> walkParsedExpr(stmt.expr, visitor)
                    }
                }
                walkParsedExpr(expr.result, visitor)
            }
            is com.mezze.language.parsedast.ParsedRecordLit -> {
                for (entry in expr.entries) {
                    when (entry) {
                        is com.mezze.language.parsedast.ParsedRecordEntry.Field -> entry.value?.let { walkParsedExpr(it, visitor) }
                        is com.mezze.language.parsedast.ParsedRecordEntry.Spread -> walkParsedExpr(entry.expr, visitor)
                    }
                }
            }
            is com.mezze.language.parsedast.ParsedVariantConstruct -> expr.value?.let { walkParsedExpr(it, visitor) }
            is com.mezze.language.parsedast.ParsedMemberAccess -> walkParsedExpr(expr.receiver, visitor)
            is com.mezze.language.parsedast.ParsedSeqLit -> {
                for (elem in expr.elements) {
                    when (elem) {
                        is com.mezze.language.parsedast.ParsedSeqElement.Regular -> walkParsedExpr(elem.expr, visitor)
                        is com.mezze.language.parsedast.ParsedSeqElement.Spread -> walkParsedExpr(elem.expr, visitor)
                    }
                }
            }
            is com.mezze.language.parsedast.ParsedPerform -> {
                walkParsedExpr(expr.body, visitor)
                // Handlers are just performer references — no sub-expressions to walk
            }
            is com.mezze.language.parsedast.ParsedPipe -> {
                walkParsedExpr(expr.left, visitor)
                walkParsedExpr(expr.right, visitor)
            }
            is com.mezze.language.parsedast.ParsedStringLit -> {
                for (part in expr.parts) {
                    if (part is com.mezze.language.parsedast.ParsedStringPart.Interpolation) {
                        walkParsedExpr(part.expr, visitor)
                    }
                }
            }
            is com.mezze.language.parsedast.ParsedParens -> walkParsedExpr(expr.expr, visitor)
            // Leaf nodes and other types — no children to walk
            else -> { }
        }
    }

    /**
     * Validate constraints from constraint generation.
     *
     * Some constraints need validation after solving.
     */
    fun validateConstraints(
        constraints: List<Constraint>,
        substitution: Substitution
    ): ValidationResult {
        val errors = mutableListOf<ValidationError>()

        for (constraint in constraints) {
            when (constraint) {
                is Constraint.AbilityRequired -> {
                    // Built-in abilities are validated by the ConstraintSolver (Phase 3).
                    // Skip re-validation here — the ConstraintSolver uses canDeriveAbility
                    // for Inspect/VarType/composite derivation and context-based lookup
                    // for primitive types via imported .mz impls.
                    if (BuiltinAbilities.isBuiltin(constraint.abilityName)) continue

                    // Check if ability is satisfied
                    val type = substitution.apply(MezzeType.VarType(constraint.typeVar))
                    // Apply substitution to typeArgs as well for multi-parameter abilities
                    val resolvedTypeArgs = constraint.typeArgs.map { substitution.apply(it) }
                    val result = abilityResolver.checkAbility(
                        type,
                        constraint.abilityName,
                        resolvedTypeArgs,
                        constraint.abilityHash
                    )

                    if (result is AbilityResolver.AbilityCheckResult.Unsatisfied) {
                        errors.add(
                            ValidationError.AbilityNotSatisfied(
                                type = type,
                                abilityName = constraint.abilityName,
                                requiredBy = constraint.context,
                                sourceLocation = constraint.sourceLocation
                            )
                        )
                    }
                }

                is Constraint.EffectUsed -> {
                    // Effect validation is done separately
                    // Here we just note that effects are used
                }

                is Constraint.HasField -> {
                    // Field access was validated during solving
                    // Check here if needed
                }

                is Constraint.HasConstructor -> {
                    // Validate that the constructor exists in the variant type
                    val appliedType = substitution.apply(constraint.variantType)

                    // If it's a TypeApplication, we need to expand it to VariantType
                    val variantType = when (appliedType) {
                        is MezzeType.VariantType -> appliedType
                        is MezzeType.TypeApplication -> {
                            // Expand TypeApplication to VariantType using type declarations
                            val typeDecl = typeDeclarations[appliedType.constructor]
                            val definition = typeDecl?.definition
                            if (definition is com.mezze.language.parsedast.ParsedVariantDef) {
                                // Build VariantType from definition
                                MezzeType.VariantType(
                                    constructors = definition.constructors.associate { ctor ->
                                        ctor.name to ctor.dataType?.let { convertParsedTypeAnnotation(it) }
                                    },
                                    rowVar = if (definition.isOpen) RowVar.fresh() else null
                                )
                            } else null
                        }
                        else -> null
                    }

                    if (variantType == null) continue  // Can't validate, skip

                    // Check if constructor exists in the variant type
                    if (!variantType.constructors.containsKey(constraint.constructorName)) {
                        // For CLOSED variants (rowVar == null), this is an error
                        if (variantType.rowVar == null) {
                            val validConstructors = variantType.constructors.keys.sorted()

                            errors.add(
                                ValidationError.ConstructorNotFound(
                                    variantType = variantType,
                                    constructorName = constraint.constructorName,
                                    availableConstructors = validConstructors,
                                    sourceLocation = constraint.sourceLocation
                                )
                            )
                        }
                        // For OPEN variants (rowVar != null), extra constructors are allowed
                    }
                }

                is Constraint.Equality,
                is Constraint.Subsumes -> {
                    // These were handled during solving
                }

                is Constraint.MustBeOpen -> {
                    // This was handled during solving and substitution application
                }

                is Constraint.RecordPatternExhaustive -> {
                    // This was handled during solving
                }

                is Constraint.AssociatedTypeResolution -> {
                    // Associated type resolution is handled during constraint solving
                    // Nothing to validate here
                }
            }
        }

        return if (errors.isEmpty()) {
            ValidationResult.Success()
        } else {
            ValidationResult.Failure(errors)
        }
    }

    /**
     * Validate ability constraints.
     *
     * After constraint solving, we check if concrete types have implementations
     * for the required abilities.
     *
     * Example: 42.show {} generates AbilityRequired(typeVar='a, abilityName=Show)
     * After substitution, 'a = Int, so we check if Show_Int implementation exists.
     */
    private fun validateAbilityConstraints(
        constraints: List<Constraint.AbilityRequired>,
        type: MezzeType,
        bindingContext: String,
        substitution: Substitution
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()

        // NOTE: We iterate through ALL constraints and check each one individually.
        // If a constraint's type variable is still polymorphic after substitution,
        // we skip it (it will be checked when instantiated with a concrete type).
        // This allows us to catch errors where CONCRETE types are used with abilities
        // that don't have implementations, even inside polymorphic functions.

        for (constraint in constraints) {
            // Apply substitution to get the concrete type
            val typeVarType = MezzeType.VarType(constraint.typeVar)
            val concreteType = substitution.apply(typeVarType)

            // If the type is still a type variable (polymorphic), skip validation
            // It will be checked when instantiated with a concrete type
            if (concreteType is MezzeType.VarType) {
                continue
            }

            // Get type name for implementation lookup
            val typeName = getTypeNameForAbility(concreteType)

            // Also skip if type name looks polymorphic (belt-and-suspenders)
            if (typeName.startsWith("'") || typeName == "Unknown") {
                continue
            }

            val abilityName = constraint.abilityName
            val effectiveHash = constraint.abilityHash

            // Built-in abilities are validated by the ConstraintSolver (Phase 3).
            // No need to re-validate here — if we reached Phase 5, they passed.
            if (BuiltinAbilities.isBuiltin(abilityName)) {
                continue
            }

            // Derivation: Inspect universality, VarType pass-through,
            // Eq/Ord composite derivation, Bool logical operators.
            if (canDeriveAbility(abilityName, concreteType, effectiveHash)) {
                continue
            }

            // Check if user-defined implementation exists
            // Handle multi-type abilities like Convert 'a 'b
            // IMPORTANT: constraint.typeArgs may already include the constrained type var
            // (when from type annotations like "with Show 'a" or "with Encode Json 'a").
            val implTypeParts = mutableListOf<String>()
            var hasPolymorphicTypeArg = false
            var hasConstrainedTypeInArgs = false
            for (typeArg in constraint.typeArgs) {
                val argType = substitution.apply(typeArg)
                val argTypeName = getTypeNameForAbility(argType)
                if (argTypeName.startsWith("'") || argTypeName == "Unknown") {
                    // Type argument is still polymorphic - can't validate yet
                    hasPolymorphicTypeArg = true
                    break
                }
                if (argTypeName == typeName) {
                    hasConstrainedTypeInArgs = true
                }
                implTypeParts.add(argTypeName)
            }

            // If any type argument is still polymorphic, skip validation for this constraint
            // (it will be checked when fully instantiated)
            if (hasPolymorphicTypeArg) {
                continue
            }

            // If typeArgs doesn't contain the constrained type, prepend it
            if (!hasConstrainedTypeInArgs) {
                implTypeParts.add(0, typeName)
            }

            // Build list of concrete types for structural matching
            // IMPORTANT: constraint.typeArgs may already include the constrained type var
            // (when from type annotations like "with Show 'a" or "with Encode Json 'a").
            // In that case, use resolved typeArgs directly to avoid duplicates.
            val resolvedTypeArgs = constraint.typeArgs.map { substitution.apply(it) }
            val concreteTypes = if (resolvedTypeArgs.any { it == concreteType }) {
                // TypeArgs already contains the constrained type, use as-is
                resolvedTypeArgs
            } else if (resolvedTypeArgs.isEmpty()) {
                listOf(concreteType)
            } else {
                // Prepend constrained type (from method call constraints)
                listOf(concreteType) + resolvedTypeArgs
            }

            // OPTIMIZATION: Cache ability impls lookup - called once per constraint instead of 4x
            val abilityImpls = context.getAbilityImpls(abilityName)

            // PRIMARY: Check for structural match using type annotations
            // This is the preferred method - avoids string serialization
            val hasStructuralMatch = abilityImpls.any { implInfo ->
                val annotations = implInfo.targetTypeAnnotations ?: return@any false
                if (annotations.size != concreteTypes.size) return@any false
                val typeVarBindings = mutableMapOf<String, MezzeType>()
                concreteTypes.zip(annotations).all { (type, annotation) ->
                    typeMatchesAnnotation(type, annotation, implInfo.typeVars, typeVarBindings)
                }
            }
            if (hasStructuralMatch) {
                continue
            }

            // FALLBACK: Check for exact string match (for backward compatibility)
            val hasExactMatch = abilityImpls.any { implInfo ->
                implInfo.targetTypes == implTypeParts
            }
            if (hasExactMatch) {
                continue
            }

            // Check for match via type alias - if the type is a record without typeName,
            // check if any type alias in scope matches its structure
            val hasRecordAliasMatch = if (implTypeParts.size == 1 && concreteType is MezzeType.RecordType && concreteType.typeName == null) {
                val matchingAliases = findMatchingRecordTypeAliases(concreteType)
                abilityImpls.any { implInfo ->
                    implInfo.targetTypes.size == 1 && matchingAliases.contains(implInfo.targetTypes[0])
                }
            } else {
                false
            }
            if (hasRecordAliasMatch) {
                continue
            }

            // Check for match via variant type alias - if the type is a variant without typeName,
            // check if any type alias in scope matches its structure and infer type arguments
            val hasVariantAliasMatch = if (implTypeParts.size == 1 && concreteType is MezzeType.VariantType && concreteType.typeName == null) {
                val matchingAliases = findMatchingVariantTypeAliases(concreteType)
                abilityImpls.any { implInfo ->
                    implInfo.targetTypes.size == 1 && matchingAliases.contains(implInfo.targetTypes[0])
                }
            } else {
                false
            }
            if (hasVariantAliasMatch) {
                continue
            }

            // For multi-parameter abilities, try to find a partial match
            // For example, if we're looking for Convert_Int and there's Convert_Int_String, that's a match
            val hasPartialMatch = if (implTypeParts.size == 1 && constraint.typeArgs.isNotEmpty()) {
                // We only have the first type arg - look for any impl that starts with this prefix
                // Inline lookup using cached abilityImpls
                abilityImpls.any { implInfo ->
                    implInfo.targetTypes.size >= implTypeParts.size &&
                    implInfo.targetTypes.take(implTypeParts.size) == implTypeParts
                }
            } else {
                false
            }

            if (hasPartialMatch) {
                continue
            }

            // Check if this is an auto-derivable Encode/Decode/EncodeRepr/DecodeRepr ability
            // These are automatically generated by CodecGenerator for serializable types
            if ((abilityName == "Encode" || abilityName == "Decode" ||
                 abilityName == "EncodeRepr" || abilityName == "DecodeRepr") &&
                codecGenerator.isSerializable(concreteType)) {
                // Check if there's a Format/DecodeFormat impl in scope
                val formatAbility = if (abilityName == "Encode" || abilityName == "EncodeRepr") "Format" else "DecodeFormat"
                val hasFormatImpl = context.getAllAbilityImpls().any { it.value.abilityName == formatAbility }
                if (hasFormatImpl) {
                    // This will be auto-derived by CodecGenerator
                    continue
                }
            }

            // Implementation not found - this is an error
            val typeDisplay = if (implTypeParts.size == 1) {
                implTypeParts[0]
            } else {
                implTypeParts.joinToString(", ")
            }

            errors.add(
                ValidationError.MissingAbilityImplementation(
                    abilityName = abilityName,
                    typeName = typeDisplay,
                    context = constraint.context ?: bindingContext
                )
            )
        }

        return errors
    }

    /**
     * Find type aliases that match the given record type structure.
     *
     * When a record literal `{ name = "Alice" }` is created without a type annotation,
     * it has no typeName. But if there's a type alias `Person = { name: String }` in scope,
     * we should be able to find impls for `Person`.
     */
    private fun findMatchingRecordTypeAliases(recordType: MezzeType.RecordType): List<String> {
        val matchingAliases = mutableListOf<String>()

        for ((aliasName, typeDecl) in typeDeclarations) {
            val definition = typeDecl.definition
            if (definition is com.mezze.language.parsedast.ParsedRecordDef) {
                // Check if fields match
                val aliasFields = definition.fields.associate { it.name to it.type }

                // Same number of fields?
                if (aliasFields.size != recordType.fields.size) continue

                // Same field names?
                if (aliasFields.keys != recordType.fields.keys) continue

                // For now, just check field names match (not full type compatibility)
                // Full type checking would require recursive unification
                matchingAliases.add(aliasName)
            }
        }

        return matchingAliases
    }

    /**
     * Find type aliases that match the given variant type structure.
     *
     * When a variant constructor `Just {val = 42}` is used without a type annotation,
     * it creates an anonymous variant with no typeName. But if there's a type declaration
     * `type Maybe 'a = | Nothing, Just {val: 'a} |` in scope, we should be able to find
     * impls for `Maybe Int`.
     *
     * Returns fully parameterized names like "Maybe_Int" by:
     * 1. Finding type declarations whose constructors match the variant's constructors
     * 2. Extracting type arguments by matching concrete payload types against type parameter placeholders
     * 3. Constructing the full parametric name using the naming convention "TypeName_Arg1_Arg2"
     */
    private fun findMatchingVariantTypeAliases(variantType: MezzeType.VariantType): List<String> {
        val matchingAliases = mutableListOf<String>()
        val variantConstructors = variantType.constructors

        for ((aliasName, typeDecl) in typeDeclarations) {
            val definition = typeDecl.definition
            if (definition is com.mezze.language.parsedast.ParsedVariantDef) {
                // Get constructors from the type declaration
                val aliasConstructors = definition.constructors.map { it.name }.toSet()

                // Check if ALL variant constructors are present in the type declaration
                if (aliasConstructors.containsAll(variantConstructors.keys)) {
                    // If the type has type parameters, we need to infer them from the payload types
                    if (typeDecl.typeParams.isNotEmpty()) {
                        val inferredTypeArgs = inferTypeArgsFromVariant(variantType, typeDecl, definition)
                        if (inferredTypeArgs != null && inferredTypeArgs.size == typeDecl.typeParams.size) {
                            // Construct the parametric name like "Maybe_Int"
                            val argNames = inferredTypeArgs.map { getTypeNameForAbility(it) }
                            val parametricName = "${aliasName}_${argNames.joinToString("_")}"
                            matchingAliases.add(parametricName)
                        }
                        // Also add the base name in case impl is for Maybe (no type args)
                        matchingAliases.add(aliasName)
                    } else {
                        // No type parameters, just add the alias name
                        matchingAliases.add(aliasName)
                    }
                }
            }
        }

        return matchingAliases
    }

    /**
     * Infer type arguments from a variant's concrete payload types by matching against
     * the type declaration's constructor patterns.
     */
    private fun inferTypeArgsFromVariant(
        variantType: MezzeType.VariantType,
        typeDecl: com.mezze.language.parsedast.ParsedTypeDecl,
        definition: com.mezze.language.parsedast.ParsedVariantDef
    ): List<MezzeType>? {
        // Map type parameter names to their inferred types
        val typeArgMap = mutableMapOf<String, MezzeType>()

        // For each constructor in the variant, try to match against the type declaration
        for ((constructorName, concretePayload) in variantType.constructors) {
            // Find the matching constructor in the type declaration
            val declConstructor = definition.constructors.find { it.name == constructorName } ?: continue

            // If the constructor has a data type annotation, try to extract type parameter mappings
            val dataTypeAnnotation = declConstructor.dataType ?: continue
            if (concretePayload == null) continue

            // Extract type parameter mappings by comparing the annotation with the concrete type
            extractTypeParamMappingsV2(dataTypeAnnotation, concretePayload, typeDecl.typeParams, typeArgMap)
        }

        // Return the type arguments in order
        return if (typeArgMap.size == typeDecl.typeParams.size) {
            typeDecl.typeParams.map { typeArgMap[it] ?: return null }
        } else {
            null
        }
    }

    /**
     * Extract type parameter mappings by comparing a type annotation with a concrete type.
     */
    private fun extractTypeParamMappingsV2(
        annotation: com.mezze.language.parsedast.ParsedTypeAnnotation,
        concreteType: MezzeType,
        typeParams: List<String>,
        mappings: MutableMap<String, MezzeType>
    ) {
        when (annotation) {
            is com.mezze.language.parsedast.ParsedNamedType -> {
                if (annotation.name in typeParams) mappings[annotation.name] = concreteType
            }
            is com.mezze.language.parsedast.ParsedRecordType -> {
                if (concreteType is MezzeType.RecordType) {
                    for (e in annotation.entries) {
                        val ft = concreteType.fields[e.name] ?: continue
                        extractTypeParamMappingsV2(e.type, ft, typeParams, mappings)
                    }
                }
            }
            is com.mezze.language.parsedast.ParsedTypeApp -> {
                if (concreteType is MezzeType.TypeApplication && concreteType.constructor == annotation.constructor) {
                    for ((a, c) in annotation.args.zip(concreteType.args)) extractTypeParamMappingsV2(a, c, typeParams, mappings)
                }
            }
            is com.mezze.language.parsedast.ParsedFunctionType -> {
                if (concreteType is MezzeType.FunctionType) {
                    extractTypeParamMappingsV2(annotation.paramType, concreteType.paramType, typeParams, mappings)
                    extractTypeParamMappingsV2(annotation.returnType, concreteType.returnType, typeParams, mappings)
                }
            }
            else -> {}
        }
    }

    private fun extractTypeParamMappings(
        annotation: TypeAnnotation,
        concreteType: MezzeType,
        typeParams: List<String>,
        mappings: MutableMap<String, MezzeType>
    ) {
        when (annotation) {
            is IdentifierTypeAnnotation -> {
                // If this is a type parameter reference like 'a
                if (annotation.name in typeParams) {
                    // Map this type parameter to the concrete type
                    mappings[annotation.name] = concreteType
                }
            }
            is RecordTypeAnnotation -> {
                // Match record fields
                if (concreteType is MezzeType.RecordType) {
                    for (entry in annotation.entries) {
                        val fieldType = concreteType.fields[entry.name] ?: continue
                        extractTypeParamMappings(entry.type, fieldType, typeParams, mappings)
                    }
                }
            }
            is TypeApplicationAnnotation -> {
                // Match type applications like List 'a
                if (concreteType is MezzeType.TypeApplication && concreteType.constructor == annotation.constructor) {
                    for ((annArg, concreteArg) in annotation.args.zip(concreteType.args)) {
                        extractTypeParamMappings(annArg, concreteArg, typeParams, mappings)
                    }
                } else if (concreteType is MezzeType.SeqType && annotation.constructor == BuiltinTypes.SEQ) {
                    // Handle Seq type specially
                    annotation.args.firstOrNull()?.let { elementAnnotation ->
                        extractTypeParamMappings(elementAnnotation, concreteType.elementType, typeParams, mappings)
                    }
                } else if (concreteType is MezzeType.ListType && annotation.constructor == BuiltinTypes.LIST) {
                    // Handle List type specially
                    annotation.args.firstOrNull()?.let { elementAnnotation ->
                        extractTypeParamMappings(elementAnnotation, concreteType.elementType, typeParams, mappings)
                    }
                }
            }
            is VariantTypeAnnotation -> {
                // Match variant constructors
                if (concreteType is MezzeType.VariantType) {
                    for (constructor in annotation.constructors) {
                        val concretePayload = concreteType.constructors[constructor.name] ?: continue
                        val annPayload = constructor.dataType ?: continue
                        extractTypeParamMappings(annPayload, concretePayload, typeParams, mappings)
                    }
                }
            }
            is FunctionTypeAnnotation -> {
                // Match function parameter and return types
                if (concreteType is MezzeType.FunctionType) {
                    extractTypeParamMappings(annotation.paramType, concreteType.paramType, typeParams, mappings)
                    extractTypeParamMappings(annotation.returnType, concreteType.returnType, typeParams, mappings)
                }
            }
            is SeqTypeAnnotation -> {
                // Match seq element types
                if (concreteType is MezzeType.SeqType) {
                    extractTypeParamMappings(annotation.elementType, concreteType.elementType, typeParams, mappings)
                }
            }
            // These annotation types don't contain type parameters we need to extract
            is QualifiedTypeAnnotation,
            is EffectRowAnnotation -> { /* no type parameters to extract */ }
        }
    }

    /**
     * Check if an ability can be derived without an explicit impl in context.
     * Only handles things that CAN'T come from .mz files:
     * - Inspect: universally available for ALL types
     * - VarType pass-through: polymorphic types are constrained at call site
     * - Eq/Ord derivation for composite types (records, variants, lists, seqs)
     * - Bool variant matching for logical operators
     */
    private fun canDeriveAbility(abilityName: String, type: MezzeType, abilityHash: String? = null): Boolean {
        var unwrappedType = if (type is MezzeType.SuspendedType) type.valueType else type

        if (unwrappedType is MezzeType.TypeApplication) {
            val expanded = expandTypeApplicationToVariant(unwrappedType, typeDeclarations)
            if (expanded != null) {
                unwrappedType = expanded
            }
        }

        return when {
            WellKnownHashes.isInspect(abilityHash) -> true

            WellKnownHashes.isEq(abilityHash) -> {
                unwrappedType is MezzeType.VarType ||
                isBoolVariant(unwrappedType) ||
                (unwrappedType is MezzeType.RecordType && unwrappedType.fields.values.all { canDeriveOrHasImpl("Eq", it, abilityHash) }) ||
                (unwrappedType is MezzeType.VariantType && unwrappedType.constructors.values.all { it == null || canDeriveOrHasImpl("Eq", it, abilityHash) }) ||
                (unwrappedType is MezzeType.ListType && canDeriveOrHasImpl("Eq", unwrappedType.elementType, abilityHash)) ||
                (unwrappedType is MezzeType.SeqType && canDeriveOrHasImpl("Eq", unwrappedType.elementType, abilityHash))
            }

            WellKnownHashes.isOrd(abilityHash) -> {
                unwrappedType is MezzeType.VarType ||
                (unwrappedType is MezzeType.RecordType && unwrappedType.fields.values.all { canDeriveOrHasImpl("Ord", it, abilityHash) }) ||
                (unwrappedType is MezzeType.VariantType && unwrappedType.constructors.values.all { it == null || canDeriveOrHasImpl("Ord", it, abilityHash) }) ||
                (unwrappedType is MezzeType.ListType && canDeriveOrHasImpl("Ord", unwrappedType.elementType, abilityHash)) ||
                (unwrappedType is MezzeType.SeqType && canDeriveOrHasImpl("Ord", unwrappedType.elementType, abilityHash))
            }

            abilityName == "LogicalAnd" || abilityName == "LogicalOr" || abilityName == "LogicalNot" -> {
                unwrappedType is MezzeType.BoolType || isBoolVariant(unwrappedType)
            }

            else -> false
        }
    }

    /**
     * Check if a type has an ability via derivation OR via an explicit impl in context.
     */
    private fun canDeriveOrHasImpl(abilityName: String, type: MezzeType, abilityHash: String? = null): Boolean {
        return canDeriveAbility(abilityName, type, abilityHash) || hasImplInContext(abilityName, type)
    }

    /**
     * Check if an ability impl for this type exists in the typing context.
     */
    private fun hasImplInContext(abilityName: String, type: MezzeType): Boolean {
        val impls = context.getAbilityImpls(abilityName)
        return impls.any { implInfo ->
            val annotations = implInfo.targetTypeAnnotations ?: return@any false
            if (annotations.size != 1) return@any false
            val typeVarBindings = mutableMapOf<String, MezzeType>()
            typeMatchesAnnotation(type, annotations[0], implInfo.typeVars, typeVarBindings)
        }
    }

    /**
     * Check if type is the Bool variant type.
     */
    private fun isBoolVariant(type: MezzeType): Boolean {
        return type is MezzeType.VariantType &&
               type.constructors.keys == setOf("True", "False")
    }

    /**
     * Get type name for ability implementation lookup.
     *
     * Uses special handling for named types (typeName), Bool variants, and single-constructor variants,
     * then falls back to the canonical ContentHashComputer.mezzeTypeToString.
     *
     * Results are cached by identity hash to avoid repeated computation.
     */
    private fun getTypeNameForAbility(type: MezzeType): String {
        val key = System.identityHashCode(type)
        typeNameCache[key]?.let { return it }

        val result = when (type) {
            is MezzeType.VariantType -> {
                // Use typeName if available, otherwise check for Bool or use constructor name
                type.typeName ?: if (type.constructors.keys == setOf("True", "False")) {
                    "Bool"
                } else if (type.constructors.size == 1) {
                    // Single constructor variant - likely a type alias
                    type.constructors.keys.first()
                } else {
                    // Fall back to canonical naming
                    ContentHashComputer.mezzeTypeToString(type)
                }
            }
            is MezzeType.RecordType -> {
                // Use typeName if available
                type.typeName ?: ContentHashComputer.mezzeTypeToString(type)
            }
            is MezzeType.SuspendedType -> getTypeNameForAbility(type.valueType)
            // For all other types, delegate to canonical implementation
            else -> ContentHashComputer.mezzeTypeToString(type)
        }

        typeNameCache[key] = result
        return result
    }

    /**
     * Validate effect usage.
     */
    private fun validateEffectUsage(
        usedEffects: Set<String>,
        type: MezzeType,
        context: String
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()

        // Get allowed effects from type
        val allowedEffects = when (type) {
            is MezzeType.FunctionType -> type.effects?.effects ?: emptySet()
            is MezzeType.SuspendedType -> type.effects.effects
            else -> emptySet()
        }

        // Check if all used effects are allowed
        for (effect in usedEffects) {
            if (effect !in allowedEffects) {
                val result = effectValidator.checkEffect(
                    effect,
                    allowedEffects,
                    allowedEffects
                )

                if (result is EffectValidator.EffectCheckResult.Unhandled) {
                    errors.add(
                        ValidationError.EffectNotHandled(
                            effectName = effect,
                            usedBy = null,
                            availableHandlers = allowedEffects.toList(),
                            sourceLocation = null
                        )
                    )
                }
            }
        }

        return errors
    }

    /**
     * Validate type annotation for a v2 ParsedLetDecl.
     * Uses ParsedTypeAnnotation directly.
     */
    private fun validateParsedAnnotation(
        decl: com.mezze.language.parsedast.ParsedLetDecl,
        substitution: Substitution,
        constraintResults: Map<String, ConstraintResult>
    ): List<ValidationError> {
        val parsedAnnotation = decl.typeAnnotation ?: return emptyList()
        val bindingKey = if (decl.isSimpleBinding) decl.variableName else return emptyList()
        val inferredType = constraintResults[bindingKey]?.type ?: return emptyList()

        val annotatedType = try {
            convertParsedTypeAnnotation(parsedAnnotation)
        } catch (_: Exception) {
            return emptyList()
        }

        val finalType = substitution.apply(inferredType)

        if (!typesCompatible(annotatedType, finalType)) {
            val bindingName = if (decl.isSimpleBinding) decl.variableName else "_pattern"
            return listOf(
                ValidationError.AnnotationMismatch(
                    bindingName = bindingName,
                    annotatedType = annotatedType,
                    inferredType = finalType,
                    sourceLocation = null
                )
            )
        }

        return emptyList()
    }

    /**
     * Validate top-level effect restrictions for a v2 ParsedLetDecl.
     */
    private fun validateParsedTopLevelEffects(
        decl: com.mezze.language.parsedast.ParsedLetDecl,
        substitution: Substitution,
        constraintResults: Map<String, ConstraintResult>
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()

        if (decl.isSimpleBinding && decl.variableName == "main") {
            return emptyList()
        }

        val bindingKey = if (decl.isSimpleBinding) decl.variableName else return emptyList()
        val inferredType = constraintResults[bindingKey]?.type ?: return emptyList()
        val finalType = substitution.apply(inferredType)

        when (finalType) {
            is MezzeType.FunctionType -> { /* Functions are definitions, <*> is fine */ }
            is MezzeType.SuspendedType -> {
                if (finalType.effects.performed) {
                    errors.add(
                        ValidationError.TopLevelPerformedEffects(
                            bindingName = decl.variableName,
                            effectRow = finalType.effects,
                            sourceLocation = SourceLocation(
                                file = decl.loc.file,
                                line = decl.loc.startLine,
                                column = decl.loc.startCol
                            )
                        )
                    )
                }
            }
            else -> { /* Other types don't need top-level effect validation */ }
        }

        return errors
    }

    // parsedEffectDeclToV1 deleted — v1 bridge no longer needed

    /**
     * Convert a v2 ParsedPerformerDecl to a v1 ImplDeclaration for validation.
     * Thin bridge for EffectValidator which still operates on v1 types.
     */
    // parsedPerformerDeclToV1 deleted — v1 bridge no longer needed

    /**
     * Validate a top-level expression for effect restrictions.
     *
     * Top-level expressions (bare function calls, etc.) cannot have performed effects (<*>)
     * because that would mean executing effects at module load time without assignment.
     *
     * Example of invalid code:
     *   myEffectfulFunc {}  // Can't call effectful function at top-level
     */
    private fun validateTopLevelExpressionEffects(
        topLevelExpr: TopLevelExpression,
        substitution: Substitution,
        constraintResults: Map<String, ConstraintResult> = emptyMap()
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()

        // TODO: TopLevelExpression types are keyed as "_toplevel" in constraintResults.
        // For now, skip validation if the type is not available — constraint solver catches most errors.
        val rawType = constraintResults["_toplevel"]?.type ?: return emptyList()
        val finalType = substitution.apply(rawType)

        // Check if this expression has performed effects
        when (finalType) {
            is MezzeType.FunctionType -> {
                // A function value at top-level is fine - it's just a value
                // (though unusual to have a bare function expression at top-level)
            }
            is MezzeType.SuspendedType -> {
                // SuspendedType wraps a value with its effects
                // If it has performed=true, that means effects were executed
                if (finalType.effects.performed) {
                    errors.add(
                        ValidationError.TopLevelExpressionPerformedEffects(
                            effectRow = finalType.effects,
                            sourceLocation = topLevelExpr.expression.sourceLocation
                        )
                    )
                }
            }
            else -> {
                // Other types are fine at top-level
            }
        }

        return errors
    }

    /**
     * Validate top-level expressions for execution.
     *
     * Call this AFTER type-checking when you want to run the program as an executable.
     * This validates that no top-level expressions have performed effects (<*>).
     *
     * This is NOT called during normal type-checking (checkProgram) because:
     * - Tests and REPL use top-level expressions to evaluate effectful code
     * - Only actual program execution (exec/build) needs this validation
     *
     * @param program The type-checked program
     * @param substitution The substitution from constraint solving
     * @return List of validation errors for any top-level expressions with <*>
     */
    fun validateTopLevelExpressionsForExecution(
        program: Program,
        substitution: Substitution,
        constraintResults: Map<String, ConstraintResult> = emptyMap()
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()

        for (item in program.items) {
            if (item is TopLevelExpression) {
                errors.addAll(validateTopLevelExpressionEffects(item, substitution, constraintResults))
            }
        }

        return errors
    }

    /**
     * Validate that main has the correct type: {} -> {} <*>
     */
    fun validateMainType(type: MezzeType, sourceLocation: SourceLocation? = null): ValidationError? {
        // main must be a function
        if (type !is MezzeType.FunctionType) {
            return ValidationError.MainFunctionTypeError(
                actualType = type,
                reason = "it is not a function (got ${prettyPrintType(type)})",
                sourceLocation = sourceLocation
            )
        }

        // Check parameter type is {} (empty record)
        val paramType = type.paramType
        if (paramType !is MezzeType.RecordType || paramType.fields.isNotEmpty()) {
            return ValidationError.MainFunctionTypeError(
                actualType = type,
                reason = "parameter type is ${prettyPrintType(paramType)}, expected {}",
                sourceLocation = sourceLocation
            )
        }

        // Check return type is {} (empty record)
        val returnType = type.returnType
        if (returnType !is MezzeType.RecordType || returnType.fields.isNotEmpty()) {
            return ValidationError.MainFunctionTypeError(
                actualType = type,
                reason = "return type is ${prettyPrintType(returnType)}, expected {}",
                sourceLocation = sourceLocation
            )
        }

        // Check effects are <*> (performed, no unhandled effects)
        val effectRow = type.effects
        if (effectRow == null) {
            return ValidationError.MainFunctionTypeError(
                actualType = type,
                reason = "it is pure (no effects). main should perform effects using 'perform'",
                sourceLocation = sourceLocation
            )
        }

        if (!effectRow.performed) {
            // Has unhandled effects but not performed
            val unhandledEffects = effectRow.effects.joinToString(", ")
            return ValidationError.MainFunctionTypeError(
                actualType = type,
                reason = "it has unhandled effects <$unhandledEffects>. Use 'perform' to handle all effects in main",
                sourceLocation = sourceLocation
            )
        }

        if (effectRow.effects.isNotEmpty()) {
            // Has performed but also has remaining unhandled effects
            val unhandledEffects = effectRow.effects.joinToString(", ")
            return ValidationError.MainFunctionTypeError(
                actualType = type,
                reason = "it has unhandled effects <$unhandledEffects, *>. All effects must be handled in main",
                sourceLocation = sourceLocation
            )
        }

        // All checks passed - main is {} -> {} <*>
        return null
    }

    /**
     * Extract effect row from a type if present.
     */
    private fun extractEffectRowFromType(type: MezzeType): MezzeType.EffectRow? {
        return when (type) {
            is MezzeType.FunctionType -> type.effects
            is MezzeType.SuspendedType -> type.effects
            else -> null
        }
    }

    /**
     * Check if two types are compatible for annotation checking.
     *
     * This is more lenient than strict equality:
     * - Ignores type variable names (alpha equivalence)
     * - Allows annotated types to be more specific than inferred
     */
    private fun typesCompatible(annotated: MezzeType, inferred: MezzeType): Boolean {
        // Use alpha equivalence check with empty mapping
        return alphaEquivalent(annotated, inferred, mutableMapOf())
    }

    /**
     * Check if two types are alpha-equivalent (same up to renaming of type variables).
     *
     * @param type1 First type to compare
     * @param type2 Second type to compare
     * @param mapping Mutable map tracking type variable correspondences
     */
    private fun alphaEquivalent(
        type1: MezzeType,
        type2: MezzeType,
        mapping: MutableMap<TypeVar, TypeVar>
    ): Boolean {
        return when {
            // Exact match
            type1 == type2 -> true

            // Both are type variables - check if they correspond
            type1 is MezzeType.VarType && type2 is MezzeType.VarType -> {
                val var1 = type1.variable
                val var2 = type2.variable

                // Check if we've seen var1 before
                val mapped = mapping[var1]
                if (mapped != null) {
                    // var1 should map to var2
                    mapped == var2
                } else {
                    // First time seeing var1 - establish mapping
                    mapping[var1] = var2
                    true
                }
            }

            // One is a type variable - polymorphic match
            type1 is MezzeType.VarType || type2 is MezzeType.VarType -> true

            // Both are function types - check structure
            type1 is MezzeType.FunctionType && type2 is MezzeType.FunctionType -> {
                functionTypesAlphaEquivalent(type1, type2, mapping)
            }

            // Both are record types - check fields
            type1 is MezzeType.RecordType && type2 is MezzeType.RecordType -> {
                recordTypesAlphaEquivalent(type1, type2, mapping)
            }

            // Both are variant types - check constructors
            type1 is MezzeType.VariantType && type2 is MezzeType.VariantType -> {
                variantTypesAlphaEquivalent(type1, type2, mapping)
            }

            // Both are type applications - check constructor and args
            type1 is MezzeType.TypeApplication && type2 is MezzeType.TypeApplication -> {
                type1.constructor == type2.constructor &&
                type1.args.size == type2.args.size &&
                type1.args.zip(type2.args).all { (a, b) -> alphaEquivalent(a, b, mapping) }
            }

            // Both are seq types
            type1 is MezzeType.SeqType && type2 is MezzeType.SeqType -> {
                alphaEquivalent(type1.elementType, type2.elementType, mapping)
            }

            // Both are list types
            type1 is MezzeType.ListType && type2 is MezzeType.ListType -> {
                alphaEquivalent(type1.elementType, type2.elementType, mapping)
            }

            // Both are set types
            type1 is MezzeType.SetType && type2 is MezzeType.SetType -> {
                alphaEquivalent(type1.elementType, type2.elementType, mapping)
            }

            // Both are sorted map types
            type1 is MezzeType.SortedMapType && type2 is MezzeType.SortedMapType -> {
                alphaEquivalent(type1.keyType, type2.keyType, mapping) &&
                alphaEquivalent(type1.valueType, type2.valueType, mapping)
            }

            // Both are sorted set types
            type1 is MezzeType.SortedSetType && type2 is MezzeType.SortedSetType -> {
                alphaEquivalent(type1.elementType, type2.elementType, mapping)
            }

            // Both are map types
            type1 is MezzeType.MapType && type2 is MezzeType.MapType -> {
                alphaEquivalent(type1.keyType, type2.keyType, mapping) &&
                alphaEquivalent(type1.valueType, type2.valueType, mapping)
            }

            // No match
            else -> false
        }
    }

    /**
     * Check if two function types are alpha-equivalent.
     */
    private fun functionTypesAlphaEquivalent(
        func1: MezzeType.FunctionType,
        func2: MezzeType.FunctionType,
        mapping: MutableMap<TypeVar, TypeVar>
    ): Boolean {
        // Check parameter types
        if (!alphaEquivalent(func1.paramType, func2.paramType, mapping)) {
            return false
        }

        // Check return types
        if (!alphaEquivalent(func1.returnType, func2.returnType, mapping)) {
            return false
        }

        // Check effects
        val effects1 = func1.effects
        val effects2 = func2.effects
        if (effects1 != null && effects2 != null) {
            if (effects1.effects != effects2.effects) {
                return false
            }
        } else if (effects1 != null || effects2 != null) {
            // One has effects, other doesn't
            return false
        }

        // Check ability constraints (simplified - just check names)
        // CRITICAL: Filter out Inspect - it's universally available and shouldn't affect type compatibility
        // See OldTypeChecker.kt:2651-2652 and ConstraintSolver.kt:396-402
        // TODO: FunctionAbilityConstraint should carry abilityHash so we can use hash-only check here.
        //       For now, use name comparison since FunctionAbilityConstraint lacks hash field.
        val abilities1 = func1.abilityConstraints
            .map { it.abilityName }
            .filter { it != "Inspect" }
            .toSet()
        val abilities2 = func2.abilityConstraints
            .map { it.abilityName }
            .filter { it != "Inspect" }
            .toSet()

        if (abilities1 != abilities2) {
            return false
        }

        return true
    }

    /**
     * Check if two record types are alpha-equivalent.
     */
    private fun recordTypesAlphaEquivalent(
        rec1: MezzeType.RecordType,
        rec2: MezzeType.RecordType,
        mapping: MutableMap<TypeVar, TypeVar>
    ): Boolean {
        // Check same field names
        if (rec1.fields.keys != rec2.fields.keys) {
            return false
        }

        // Check each field type
        for ((fieldName, fieldType1) in rec1.fields) {
            val fieldType2 = rec2.fields[fieldName] ?: return false
            if (!alphaEquivalent(fieldType1, fieldType2, mapping)) {
                return false
            }
        }

        return true
    }

    /**
     * Check if two variant types are alpha-equivalent.
     */
    private fun variantTypesAlphaEquivalent(
        var1: MezzeType.VariantType,
        var2: MezzeType.VariantType,
        mapping: MutableMap<TypeVar, TypeVar>
    ): Boolean {
        // Check same constructor names
        if (var1.constructors.keys != var2.constructors.keys) {
            return false
        }

        // Check each constructor payload
        for ((ctorName, payload1) in var1.constructors) {
            val payload2 = var2.constructors[ctorName]

            // Both have payload
            if (payload1 != null && payload2 != null) {
                if (!alphaEquivalent(payload1, payload2, mapping)) {
                    return false
                }
            }
            // One has payload, other doesn't
            else if (payload1 != null || payload2 != null) {
                return false
            }
        }

        return true
    }

    /**
     * Check if two function types are compatible.
     *
     * Note: MezzeType.FunctionType has paramType (which is a RecordType) not a list of params.
     */
    private fun functionTypesCompatible(annotated: MezzeType.FunctionType, inferred: MezzeType.FunctionType): Boolean {
        // Check parameter type (which is a RecordType)
        if (!typesCompatible(annotated.paramType, inferred.paramType)) {
            return false
        }

        // Check return type
        if (!typesCompatible(annotated.returnType, inferred.returnType)) {
            return false
        }

        // Check effects if annotated type has effects
        val annotatedEffects = annotated.effects
        if (annotatedEffects != null) {
            val inferredEffects = inferred.effects?.effects ?: emptySet()
            // Annotated effects must match inferred effects exactly
            if (annotatedEffects.effects != inferredEffects) {
                return false
            }
        }

        // Check ability constraints if annotated type has them
        if (annotated.abilityConstraints.isNotEmpty() || inferred.abilityConstraints.isNotEmpty()) {
            // Check that both have same number of constraints
            if (annotated.abilityConstraints.size != inferred.abilityConstraints.size) {
                return false
            }

            // Check that all annotated constraints exist in inferred constraints
            // This is a simplified check - we just match by ability name
            // A full check would need to match type arguments too
            val annotatedAbilities = annotated.abilityConstraints.map { it.abilityName }.toSet()
            val inferredAbilities = inferred.abilityConstraints.map { it.abilityName }.toSet()

            if (annotatedAbilities != inferredAbilities) {
                return false
            }
        }

        return true
    }

    /**
     * Check if two record types are compatible.
     */
    private fun recordTypesCompatible(annotated: MezzeType.RecordType, inferred: MezzeType.RecordType): Boolean {
        // Check that all annotated fields exist in inferred type
        for ((fieldName, annotatedFieldType) in annotated.fields) {
            val inferredFieldType = inferred.fields[fieldName] ?: return false
            if (!typesCompatible(annotatedFieldType, inferredFieldType)) {
                return false
            }
        }

        // Check that inferred type doesn't have extra fields (unless annotated is open)
        // isOpen is a property, not a method
        if (!annotated.isOpen) {
            if (annotated.fields.size != inferred.fields.size) {
                return false
            }
        }

        return true
    }

    /**
     * Check if two variant types are compatible.
     */
    private fun variantTypesCompatible(annotated: MezzeType.VariantType, inferred: MezzeType.VariantType): Boolean {
        // Check that all annotated constructors exist in inferred type
        for ((constructorName, annotatedPayload) in annotated.constructors) {
            // Key must exist in inferred type
            if (!inferred.constructors.containsKey(constructorName)) {
                return false
            }
            val inferredPayload = inferred.constructors[constructorName]

            // Both have payload - check compatibility
            if (annotatedPayload != null && inferredPayload != null) {
                if (!typesCompatible(annotatedPayload, inferredPayload)) {
                    return false
                }
            }
            // Annotated has payload but inferred doesn't - mismatch
            else if (annotatedPayload != null && inferredPayload == null) {
                return false
            }
            // Inferred has payload but annotated doesn't - mismatch
            else if (annotatedPayload == null && inferredPayload != null) {
                return false
            }
        }

        // Check that inferred type doesn't have extra constructors (unless annotated is open)
        if (annotated.rowVar == null) {  // closed variant
            if (annotated.constructors.size != inferred.constructors.size) {
                return false
            }
        }

        return true
    }

    /**
     * Convert TypeAnnotation to MezzeType.
     *
     * This is used to convert user-written type annotations to internal MezzeType
     * for comparison with inferred types.
     *
     * We use the existing convertTypeAnnotation function from the AST module.
     */
    /** Convert ParsedTypeAnnotation to MezzeType (native v2). */
    private fun convertParsedTypeAnnotation(annotation: com.mezze.language.parsedast.ParsedTypeAnnotation): MezzeType {
        return com.mezze.language.ast.convertParsedTypeAnnotation(annotation)
    }

    /**
     * Validate that built-in abilities are not used as values.
     *
     * Built-in abilities (NumAdd, Eq, Ord, etc.) should only be used for method access,
     * not as standalone values.
     *
     * Example:
     *   let x = NumAdd      // ERROR - ability used as value
     *   let y = NumAdd.add  // OK - ability method access
     */
    private fun validateBuiltInAbilityUsage(
        type: MezzeType,
        context: String
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()

        // Check if type is a variant with a constructor that matches a built-in ability
        if (type is MezzeType.VariantType) {
            for (constructorName in type.constructors.keys) {
                if (BuiltInAbilities.isBuiltInAbility(constructorName)) {
                    // Found a built-in ability being used as a variant constructor (value)
                    errors.add(
                        ValidationError.AbilityUsedAsValue(
                            abilityName = constructorName,
                            context = context,
                            sourceLocation = null
                        )
                    )
                }
            }
        }

        return errors
    }

    /**
     * Validate pattern matching exhaustiveness.
     *
     * Ensures all constructors are covered.
     */
    fun validateExhaustiveness(
        matchExpr: MatchExpression,
        scrutineeType: MezzeType
    ): ValidationResult {
        val errors = mutableListOf<ValidationError>()

        // CRITICAL FIX: If scrutineeType is a TypeApplication, expand it to VariantType
        // This matches OldTypeChecker behavior where type aliases are expanded
        // IMPORTANT: Use expandTypeApplicationToVariant to properly substitute type parameters!
        // e.g., Result Int String should expand to | Ok {val: Int}, Err {error: String} |
        val expandedType = when (scrutineeType) {
            is MezzeType.TypeApplication -> {
                // Use expandTypeApplicationToVariant to properly substitute type params
                // This handles lookup in both local declarations and cached modules
                expandTypeApplicationToVariant(scrutineeType, typeDeclarations) ?: scrutineeType
            }
            // BoolType is internally optimized but behaves like variant for pattern matching
            is MezzeType.BoolType -> MezzeType.VariantType(
                constructors = mapOf("True" to null, "False" to null),
                rowVar = null
            )
            else -> scrutineeType
        }

        // Only variant types need exhaustiveness checking
        if (expandedType !is MezzeType.VariantType) {
            return ValidationResult.Success()
        }

        // For OPEN variants, we need a wildcard to handle unknown constructors
        // For CLOSED variants, we need to cover all constructors OR have a wildcard
        val isOpenVariant = expandedType.rowVar != null

        // Check exhaustiveness
        // Matches OldTypeChecker.kt lines 8103-8141
        val allConstructors = expandedType.constructors.keys
        val coveredConstructors = mutableSetOf<String>()
        var hasWildcard = false

        for (case in matchExpr.cases) {
            // Check each pattern in the case
            for (pattern in case.patterns) {
                when (pattern) {
                    is ConstructorPattern -> {
                        // CRITICAL: Only count as covered if there's no guard condition
                        // Guards and if-let bindings make coverage conditional
                        // Matches OldTypeChecker.kt lines 8112-8118
                        if (case.guard == null && case.ifLetBindings.isEmpty()) {
                            coveredConstructors.add(pattern.constructor)
                        }

                        // BUG FIX: Validate that unit variants (no payload) don't have payload patterns
                        // If the constructor has no payload (null in constructors map) but the pattern
                        // has a recordPattern, that's an error
                        val constructorPayload = expandedType.constructors[pattern.constructor]
                        if (constructorPayload == null && pattern.recordPattern != null) {
                            errors.add(
                                ValidationError.UnitVariantPayloadMismatch(
                                    constructorName = pattern.constructor,
                                    variantType = expandedType,
                                    sourceLocation = case.expression.sourceLocation
                                )
                            )
                        }
                    }
                    is WildcardPattern -> {
                        // Only count as wildcard if there's no guard or if-let condition
                        // Matches OldTypeChecker.kt lines 8119-8123
                        if (case.guard == null && case.ifLetBindings.isEmpty()) {
                            hasWildcard = true
                        }
                    }
                    is VariablePattern -> {
                        // Variable patterns also act as wildcards if no guard or if-let
                        if (case.guard == null && case.ifLetBindings.isEmpty()) {
                            hasWildcard = true
                        }
                    }
                    is LiteralPattern -> {
                        // SPECIAL CASE: Boolean literal patterns cover Bool constructors
                        // Bool is defined as | True, False | but we use true/false literals
                        // So we need to map boolean literal patterns to Bool constructors
                        if (case.guard == null && case.ifLetBindings.isEmpty() && pattern.value is BooleanLiteralValue) {
                            val boolLit = pattern.value as BooleanLiteralValue
                            val constructorName = if (boolLit.value) "True" else "False"
                            coveredConstructors.add(constructorName)
                        }
                        // Other literal patterns don't cover constructors
                    }
                    is RecordPattern, is SeqPattern -> {
                        // Other patterns don't cover specific constructors
                    }
                    is StringInterpolationPattern -> {
                        // String interpolation patterns don't cover variant constructors
                        // They can only match String type values
                    }
                }
            }
        }

        val uncoveredConstructors = allConstructors - coveredConstructors

        if (isOpenVariant) {
            // OPEN variant: MUST have a wildcard to handle unknown constructors
            if (!hasWildcard) {
                errors.add(
                    ValidationError.NonExhaustiveMatch(
                        expression = matchExpr,
                        coveredConstructors = coveredConstructors.toList(),
                        missingConstructors = listOf("_ (wildcard required for open variant)"),
                        sourceLocation = matchExpr.sourceLocation
                    )
                )
            }
            // Open variants with wildcard are fine - no unreachable pattern check needed
        } else {
            // CLOSED variant: must cover all constructors OR have a wildcard
            if (uncoveredConstructors.isNotEmpty() && !hasWildcard) {
                errors.add(
                    ValidationError.NonExhaustiveMatch(
                        expression = matchExpr,
                        coveredConstructors = coveredConstructors.toList(),
                        missingConstructors = uncoveredConstructors.toList(),
                        sourceLocation = matchExpr.sourceLocation
                    )
                )
            }

            // Check for unreachable patterns: if all constructors of a CLOSED variant are covered
            // BEFORE a wildcard, the wildcard is unreachable and is an error.
            //
            // This applies to ALL closed variants (Bool, Option, Result, user-defined types like Color).
            // A wildcard after exhaustive constructor patterns is dead code that can hide type errors.
            if (hasWildcard && uncoveredConstructors.isEmpty()) {
                // Find the wildcard/variable pattern to report its location
                for (case in matchExpr.cases) {
                    for (pattern in case.patterns) {
                        if ((pattern is WildcardPattern || pattern is VariablePattern) && case.guard == null && case.ifLetBindings.isEmpty()) {
                            errors.add(
                                ValidationError.UnreachablePattern(
                                    pattern = pattern,
                                    reason = "All constructors of this closed variant are already matched: ${allConstructors.sorted().joinToString(", ")}. " +
                                        "This pattern will never execute.",
                                    sourceLocation = case.expression.sourceLocation ?: matchExpr.sourceLocation
                                )
                            )
                        }
                    }
                }
            }
        }

        return if (errors.isEmpty()) {
            ValidationResult.Success()
        } else {
            ValidationResult.Failure(errors)
        }
    }

    /**
     * V2 exhaustiveness check — operates on ParsedMatch directly (no v1 conversion).
     */
    fun validateParsedExhaustiveness(
        matchExpr: com.mezze.language.parsedast.ParsedMatch,
        scrutineeType: MezzeType
    ): ValidationResult {
        val errors = mutableListOf<ValidationError>()

        val expandedType = when (scrutineeType) {
            is MezzeType.TypeApplication ->
                expandTypeApplicationToVariant(scrutineeType, typeDeclarations) ?: scrutineeType
            is MezzeType.BoolType -> MezzeType.VariantType(
                constructors = mapOf("True" to null, "False" to null), rowVar = null
            )
            else -> scrutineeType
        }

        if (expandedType !is MezzeType.VariantType) return ValidationResult.Success()

        val isOpenVariant = expandedType.rowVar != null
        val allConstructors = expandedType.constructors.keys
        val coveredConstructors = mutableSetOf<String>()
        var hasWildcard = false

        for (case in matchExpr.cases) {
            for (pattern in case.patterns) {
                when (pattern) {
                    is com.mezze.language.parsedast.ParsedConstructorPattern -> {
                        if (case.guard == null && case.ifLetBindings.isEmpty()) {
                            coveredConstructors.add(pattern.constructor)
                        }
                        val constructorPayload = expandedType.constructors[pattern.constructor]
                        if (constructorPayload == null && pattern.payload != null) {
                            errors.add(ValidationError.UnitVariantPayloadMismatch(
                                constructorName = pattern.constructor,
                                variantType = expandedType,
                                sourceLocation = SourceLocation(case.body.loc.startLine, case.body.loc.startCol, case.body.loc.file)
                            ))
                        }
                    }
                    is com.mezze.language.parsedast.ParsedWildcardPattern -> {
                        if (case.guard == null && case.ifLetBindings.isEmpty()) hasWildcard = true
                    }
                    is com.mezze.language.parsedast.ParsedVariablePattern -> {
                        if (case.guard == null && case.ifLetBindings.isEmpty()) hasWildcard = true
                    }
                    is com.mezze.language.parsedast.ParsedLiteralPattern -> {
                        if (case.guard == null && case.ifLetBindings.isEmpty()) {
                            val lit = pattern.value
                            if (lit is com.mezze.language.parsedast.ParsedLiteralValue.BoolValue) {
                                coveredConstructors.add(if (lit.value) "True" else "False")
                            }
                        }
                    }
                    else -> { /* Other patterns don't cover specific constructors */ }
                }
            }
        }

        val uncoveredConstructors = allConstructors - coveredConstructors

        if (isOpenVariant) {
            if (!hasWildcard) {
                errors.add(ValidationError.NonExhaustiveMatch(
                    coveredConstructors = coveredConstructors.toList(),
                    missingConstructors = listOf("_ (wildcard required for open variant)"),
                    sourceLocation = SourceLocation(matchExpr.loc.startLine, matchExpr.loc.startCol, matchExpr.loc.file)
                ))
            }
        } else {
            if (uncoveredConstructors.isNotEmpty() && !hasWildcard) {
                errors.add(ValidationError.NonExhaustiveMatch(
                    coveredConstructors = coveredConstructors.toList(),
                    missingConstructors = uncoveredConstructors.toList(),
                    sourceLocation = SourceLocation(matchExpr.loc.startLine, matchExpr.loc.startCol, matchExpr.loc.file)
                ))
            }
            if (hasWildcard && uncoveredConstructors.isEmpty()) {
                for (case in matchExpr.cases) {
                    for (pattern in case.patterns) {
                        if ((pattern is com.mezze.language.parsedast.ParsedWildcardPattern ||
                             pattern is com.mezze.language.parsedast.ParsedVariablePattern) &&
                            case.guard == null && case.ifLetBindings.isEmpty()) {
                            errors.add(ValidationError.UnreachablePattern(
                                reason = "All constructors of this closed variant are already matched: " +
                                    "${allConstructors.sorted().joinToString(", ")}. This pattern will never execute.",
                                sourceLocation = SourceLocation(case.body.loc.startLine, case.body.loc.startCol, case.body.loc.file)
                            ))
                        }
                    }
                }
            }
        }

        return if (errors.isEmpty()) ValidationResult.Success()
        else ValidationResult.Failure(errors)
    }

    /**
     * Validate that there are no unreachable patterns in a match expression.
     *
     * A pattern is unreachable if a previous pattern (without a guard) already matches all cases.
     * This applies to:
     * - Wildcard patterns `_`
     * - Variable patterns `x`
     *
     * This check applies to ALL match expressions, not just variants.
     * Matches OldTypeChecker.kt lines 8147-8213.
     */
    fun validateUnreachablePatterns(
        matchExpr: MatchExpression
    ): ValidationResult {
        val errors = mutableListOf<ValidationError>()
        var isExhausted = false

        for ((index, case) in matchExpr.cases.withIndex()) {
            if (isExhausted) {
                // Previous pattern was exhaustive - this pattern is unreachable
                errors.add(
                    ValidationError.UnreachablePattern(
                        pattern = case.patterns.firstOrNull() ?: WildcardPattern(),
                        reason = "Previous pattern already matches all cases",
                        sourceLocation = case.expression.sourceLocation
                    )
                )
                // Continue checking to report all unreachable patterns
                continue
            }

            // Check if this case makes subsequent patterns unreachable
            // A case is only exhaustive if it has no guard AND no if-let bindings
            // (guards and if-let bindings can fail, so subsequent cases may still be reachable)
            if (case.guard == null && case.ifLetBindings.isEmpty()) {
                for (pattern in case.patterns) {
                    // Wildcard and variable patterns are exhaustive - they match everything
                    if (pattern is WildcardPattern || pattern is VariablePattern) {
                        isExhausted = true
                        break
                    }
                }
            }
        }

        return if (errors.isEmpty()) {
            ValidationResult.Success()
        } else {
            ValidationResult.Failure(errors)
        }
    }

    /**
     * Validate unreachable patterns in a v2 ParsedMatch expression.
     *
     * A pattern is unreachable if a previous wildcard/variable pattern (without guard) already matches all cases.
     */
    private fun validateParsedUnreachablePatterns(
        matchExpr: com.mezze.language.parsedast.ParsedMatch
    ): ValidationResult {
        val errors = mutableListOf<ValidationError>()
        var isExhausted = false

        for (case in matchExpr.cases) {
            if (isExhausted) {
                errors.add(
                    ValidationError.UnreachablePattern(
                        pattern = case.patterns.firstOrNull(),
                        reason = "Previous pattern already matches all cases",
                        sourceLocation = SourceLocation(case.loc.startLine, case.loc.startCol, case.loc.file)
                    )
                )
                continue
            }

            // A case is only exhaustive if it has no guard AND no if-let bindings
            if (case.guard == null && case.ifLetBindings.isEmpty()) {
                for (pattern in case.patterns) {
                    if (pattern is com.mezze.language.parsedast.ParsedWildcardPattern ||
                        pattern is com.mezze.language.parsedast.ParsedVariablePattern) {
                        isExhausted = true
                        break
                    }
                }
            }
        }

        return if (errors.isEmpty()) {
            ValidationResult.Success()
        } else {
            ValidationResult.Failure(errors)
        }
    }

    /**
     * Infer scrutinee type from constructor patterns in a v2 ParsedMatch expression.
     */
    private fun inferParsedScrutineeTypeFromPatterns(
        matchExpr: com.mezze.language.parsedast.ParsedMatch,
        typeDecls: Map<String, com.mezze.language.parsedast.ParsedTypeDecl>
    ): MezzeType? {
        val constructorNames = mutableSetOf<String>()
        for (case in matchExpr.cases) {
            for (pattern in case.patterns) {
                if (pattern is com.mezze.language.parsedast.ParsedConstructorPattern) {
                    constructorNames.add(pattern.constructor)
                }
            }
        }

        if (constructorNames.isEmpty()) {
            return null
        }

        for ((_, typeDecl) in typeDecls) {
            val definition = typeDecl.definition
            if (definition is com.mezze.language.parsedast.ParsedVariantDef) {
                val variantConstructors = definition.constructors.map { it.name }.toSet()
                if (constructorNames.all { it in variantConstructors }) {
                    return MezzeType.VariantType(
                        constructors = definition.constructors.associate { ctor ->
                            ctor.name to ctor.dataType?.let { convertParsedTypeAnnotation(it) }
                        },
                        rowVar = if (definition.isOpen) RowVar.fresh() else null
                    )
                }
            }
        }

        return null
    }

    /**
     * Validate type application.
     *
     * Ensures type constructor has correct arity.
     */
    fun validateTypeApplication(
        typeApp: MezzeType.TypeApplication,
        expectedArity: Int
    ): ValidationResult {
        if (typeApp.args.size != expectedArity) {
            return ValidationResult.Failure(
                listOf(
                    ValidationError.InvalidTypeApplication(
                        constructor = typeApp.constructor,
                        expectedArity = expectedArity,
                        actualArity = typeApp.args.size,
                        sourceLocation = null
                    )
                )
            )
        }

        return ValidationResult.Success()
    }

    /**
     * Validate type declaration.
     *
     * Checks for circular dependencies and invalid recursion.
     */
    fun validateTypeDeclaration(typeDecl: TypeDeclaration): ValidationResult {
        val errors = mutableListOf<ValidationError>()

        // Check for simple circular dependency (type A = A)
        when (val definition = typeDecl.definition) {
            is RecordDefinition -> {
                // Check if any field has the same type as the record itself
                for (field in definition.fields) {
                    // This would need proper recursive type checking
                    // For now, skip
                }
            }

            is VariantDefinition -> {
                // Variants can be recursive, that's fine
                // Example: type List 'a = Nil | Cons { head: 'a, tail: List 'a }
            }

            is NewtypeDefinition -> {
                // Newtypes can't be directly recursive
                // type T = T would be invalid
            }
        }

        return if (errors.isEmpty()) {
            ValidationResult.Success()
        } else {
            ValidationResult.Failure(errors)
        }
    }

    /**
     * Validate ability implementation.
     *
     * Checks for orphan instances and overlapping instances.
     */
    fun validateAbilityImpl(
        implDecl: com.mezze.language.parsedast.ParsedImplAbilityDecl,
        context: TypingContext
    ): ValidationResult {
        val errors = mutableListOf<ValidationError>()

        // Check for orphan instances (both ability and type are from different modules)
        // This would require module information, skip for now

        // Check for overlapping instances (same ability for same type multiple times)
        // This would require tracking all impls in context, skip for now

        return if (errors.isEmpty()) {
            ValidationResult.Success()
        } else {
            ValidationResult.Failure(errors)
        }
    }

    /**
     * Find similar names for suggestions using Levenshtein distance.
     *
     * Returns up to 3 most similar names.
     * Delegates to shared StringDistance utility.
     */
    internal fun findSimilarNames(target: String, candidates: List<String>, maxDistance: Int = 3): List<String> {
        return StringDistance.findSimilar(target, candidates, maxDistance, maxResults = 3)
    }

    /**
     * Validate a let pattern for exhaustiveness (v2 native — works on ParsedPattern).
     *
     * For record patterns without ellipsis (...), ensures all fields are destructured.
     */
    private fun validateLetPattern(
        pattern: com.mezze.language.parsedast.ParsedPattern,
        scrutineeType: MezzeType
    ): List<ValidationError> {
        return when (pattern) {
            is com.mezze.language.parsedast.ParsedRecordPattern -> {
                validateRecordPattern(pattern, scrutineeType)
            }
            is com.mezze.language.parsedast.ParsedConstructorPattern -> {
                // Constructor patterns in let bindings can fail to match
                // TODO: Add validation for irrefutable patterns in let bindings
                emptyList()
            }
            is com.mezze.language.parsedast.ParsedVariablePattern,
            is com.mezze.language.parsedast.ParsedWildcardPattern,
            is com.mezze.language.parsedast.ParsedLiteralPattern,
            is com.mezze.language.parsedast.ParsedSeqPattern,
            is com.mezze.language.parsedast.ParsedStringInterpPattern -> {
                // These patterns don't need exhaustiveness checking here
                // (StringInterpPattern is refutable, but that's checked elsewhere)
                emptyList()
            }
        }
    }

    /**
     * Validate record pattern exhaustiveness (v2 native — works on ParsedRecordPattern).
     *
     * Checks that all fields in the record type are present in the pattern
     * when the pattern is closed (no ... ellipsis).
     */
    private fun validateRecordPattern(
        pattern: com.mezze.language.parsedast.ParsedRecordPattern,
        scrutineeType: MezzeType
    ): List<ValidationError> {
        // If pattern is open (has ...), it's always exhaustive
        if (pattern.isOpen) {
            return emptyList()
        }

        // Only validate if scrutinee is a record type
        val recordType = scrutineeType as? MezzeType.RecordType
            ?: return emptyList()

        // Get all fields from pattern
        val patternFields = pattern.entries.map { it.name }.toSet()

        // Get all fields from type
        val typeFields = recordType.fields.keys

        // Check for missing fields
        val missingFields = typeFields - patternFields

        return if (missingFields.isNotEmpty()) {
            listOf(
                ValidationError.NonExhaustiveRecordPattern(
                    missingFields = missingFields.sorted(),
                    pattern = pattern,
                    recordType = recordType,
                    sourceLocation = SourceLocation(pattern.loc.startLine, pattern.loc.startCol, pattern.loc.file)
                )
            )
        } else {
            emptyList()
        }
    }

    /**
     * Recursively validate patterns in an expression tree (v2 native — works on ParsedExpr).
     *
     * This finds let statements in do-blocks, lambda bodies, etc.
     * and validates their patterns for exhaustiveness.
     */
    private fun validatePatternsInExpression(
        expr: com.mezze.language.parsedast.ParsedExpr,
        substitution: Substitution
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()

        when (expr) {
            is com.mezze.language.parsedast.ParsedDo -> {
                // Validate let statements in do-block
                for (stmt in expr.statements) {
                    when (stmt) {
                        is com.mezze.language.parsedast.ParsedStatement.Let -> {
                            // In v2 we don't have inferredType on expressions.
                            // We still validate the sub-expression patterns recursively.
                            // Let pattern exhaustiveness for nested lets is handled by
                            // the v2 match exhaustiveness pass (validateParsedMatchesInExpr).
                            val nestedErrors = validatePatternsInExpression(stmt.value, substitution)
                            errors.addAll(nestedErrors)
                        }
                        is com.mezze.language.parsedast.ParsedStatement.Expr -> {
                            val nestedErrors = validatePatternsInExpression(stmt.expr, substitution)
                            errors.addAll(nestedErrors)
                        }
                    }
                }

                // Validate result expression
                val resultErrors = validatePatternsInExpression(expr.result, substitution)
                errors.addAll(resultErrors)
            }

            is com.mezze.language.parsedast.ParsedLambda -> {
                // Validate lambda body
                val bodyErrors = validatePatternsInExpression(expr.body, substitution)
                errors.addAll(bodyErrors)
            }

            is com.mezze.language.parsedast.ParsedIf -> {
                errors.addAll(validatePatternsInExpression(expr.condition, substitution))
                errors.addAll(validatePatternsInExpression(expr.thenBranch, substitution))
                errors.addAll(validatePatternsInExpression(expr.elseBranch, substitution))
            }

            is com.mezze.language.parsedast.ParsedFunctionCall -> {
                errors.addAll(validatePatternsInExpression(expr.function, substitution))
                errors.addAll(validatePatternsInExpression(expr.arguments, substitution))
            }

            is com.mezze.language.parsedast.ParsedRecordLit -> {
                for (entry in expr.entries) {
                    when (entry) {
                        is com.mezze.language.parsedast.ParsedRecordEntry.Field -> {
                            entry.value?.let { value ->
                                errors.addAll(validatePatternsInExpression(value, substitution))
                            }
                        }
                        is com.mezze.language.parsedast.ParsedRecordEntry.Spread -> {
                            errors.addAll(validatePatternsInExpression(entry.expr, substitution))
                        }
                    }
                }
            }

            is com.mezze.language.parsedast.ParsedMatch -> {
                val scrutineeErrors = validatePatternsInExpression(expr.scrutinee, substitution)
                errors.addAll(scrutineeErrors)

                // Validate match exhaustiveness
                // In v2, we infer scrutinee type from constructor patterns in the match cases.
                val scrutineeType = inferParsedScrutineeTypeFromPatterns(expr, typeDeclarations)

                if (scrutineeType != null) {
                    val finalScrutineeType = substitution.apply(scrutineeType)
                    val exhaustivenessResult = validateParsedExhaustiveness(expr, finalScrutineeType)

                    if (exhaustivenessResult is ValidationResult.Failure) {
                        errors.addAll(exhaustivenessResult.errors)
                    }
                }

                // Check for unreachable patterns (applies to all match expressions, not just variants)
                val unreachableResult = validateParsedUnreachablePatterns(expr)
                if (unreachableResult is ValidationResult.Failure) {
                    errors.addAll(unreachableResult.errors)
                }

                for (case in expr.cases) {
                    errors.addAll(validatePatternsInExpression(case.body, substitution))
                    case.guard?.let { guard ->
                        errors.addAll(validatePatternsInExpression(guard, substitution))
                    }
                }
            }

            // Other expression types that may contain sub-expressions
            is com.mezze.language.parsedast.ParsedVariantConstruct -> {
                expr.value?.let { value ->
                    errors.addAll(validatePatternsInExpression(value, substitution))
                }
            }

            is com.mezze.language.parsedast.ParsedMemberAccess -> {
                errors.addAll(validatePatternsInExpression(expr.receiver, substitution))
            }

            is com.mezze.language.parsedast.ParsedSeqLit -> {
                for (element in expr.elements) {
                    val elementExpr = when (element) {
                        is com.mezze.language.parsedast.ParsedSeqElement.Regular -> element.expr
                        is com.mezze.language.parsedast.ParsedSeqElement.Spread -> element.expr
                    }
                    errors.addAll(validatePatternsInExpression(elementExpr, substitution))
                }
            }

            is com.mezze.language.parsedast.ParsedStringLit -> {
                for (part in expr.parts) {
                    if (part is com.mezze.language.parsedast.ParsedStringPart.Interpolation) {
                        errors.addAll(validatePatternsInExpression(part.expr, substitution))
                    }
                }
            }

            is com.mezze.language.parsedast.ParsedPerform -> {
                // Validate the body (handlers are just performer references)
                errors.addAll(validatePatternsInExpression(expr.body, substitution))
                // Also validate handler params if they exist
                for (handler in expr.handlers) {
                    handler.params?.let { params ->
                        errors.addAll(validatePatternsInExpression(params, substitution))
                    }
                }
            }

            is com.mezze.language.parsedast.ParsedPipe -> {
                errors.addAll(validatePatternsInExpression(expr.left, substitution))
                errors.addAll(validatePatternsInExpression(expr.right, substitution))
            }

            is com.mezze.language.parsedast.ParsedParens -> {
                errors.addAll(validatePatternsInExpression(expr.expr, substitution))
            }

            // Leaf nodes - no sub-expressions to validate
            is com.mezze.language.parsedast.ParsedIdentifier,
            is com.mezze.language.parsedast.ParsedIntLit,
            is com.mezze.language.parsedast.ParsedNatLit,
            is com.mezze.language.parsedast.ParsedFloatLit,
            is com.mezze.language.parsedast.ParsedFracLit,
            is com.mezze.language.parsedast.ParsedCharLit,
            is com.mezze.language.parsedast.ParsedPipePlaceholder,
            is com.mezze.language.parsedast.ParsedEffectCall -> {
                // No patterns to validate in these
            }
        }

        return errors
    }

    /**
     * Validate variant constructor usage.
     *
     * Checks that:
     * - For closed variants (rowVar == null), the constructor exists in the variant's constructors
     * - For open variants (rowVar != null), any constructor is allowed
     */
    private fun validateVariantConstructor(
        variantExpr: VariantConstruction,
        substitution: Substitution
    ): List<ValidationError> {
        // Get the inferred type for the expression.
        // inferredType may be null on nested expressions — skip when null.
        val inferredType = variantExpr.inferredType

        if (inferredType == null) {
            return emptyList()  // No type yet, skip validation
        }

        // Apply substitution to get final type
        val finalType = substitution.apply(inferredType)

        // Only validate if the final type is a VariantType
        val variantType = finalType as? MezzeType.VariantType
        if (variantType == null) {
            return emptyList()
        }

        // Get the constructor name
        val constructorName = variantExpr.constructor

        // Check if constructor exists in the variant type
        if (!variantType.constructors.containsKey(constructorName)) {
            // For CLOSED variants (rowVar == null), this is an error
            if (variantType.rowVar == null) {
                val validConstructors = variantType.constructors.keys.sorted()

                DebugLogger.debug(Category.TypeValidator) {
                    "validateVariantConstructor: ADDING ERROR for constructor $constructorName not in $validConstructors"
                }

                return listOf(
                    ValidationError.ConstructorNotFound(
                        variantType = variantType,
                        constructorName = constructorName,
                        availableConstructors = validConstructors,
                        sourceLocation = variantExpr.sourceLocation
                    )
                )
            }
            // For OPEN variants (rowVar != null), extra constructors are allowed
        }

        return emptyList()
    }

    /**
     * Infer scrutinee type from constructor patterns in match expression.
     *
     * Infers the scrutinee type from constructor patterns when inferredType is not
     * available on the expression. Looks at the constructor patterns and finds
     * the type declaration that contains those constructors.
     */
    private fun inferScrutineeTypeFromPatterns(
        matchExpr: MatchExpression,
        typeDecls: Map<String, com.mezze.language.parsedast.ParsedTypeDecl>
    ): MezzeType? {
        // Collect all constructor names from patterns
        val constructorNames = mutableSetOf<String>()
        for (case in matchExpr.cases) {
            for (pattern in case.patterns) {
                if (pattern is ConstructorPattern) {
                    constructorNames.add(pattern.constructor)
                }
            }
        }

        if (constructorNames.isEmpty()) {
            return null  // No constructor patterns to infer from
        }

        // Find a type declaration that contains these constructors
        for ((typeName, typeDecl) in typeDecls) {
            val definition = typeDecl.definition
            if (definition is com.mezze.language.parsedast.ParsedVariantDef) {
                // Check if this variant has the constructors we're looking for
                val variantConstructors = definition.constructors.map { it.name }.toSet()
                if (constructorNames.all { it in variantConstructors }) {
                    // Found it! Build the VariantType
                    return MezzeType.VariantType(
                        constructors = definition.constructors.associate { ctor ->
                            ctor.name to ctor.dataType?.let { convertParsedTypeAnnotation(it) }
                        },
                        rowVar = if (definition.isOpen) RowVar.fresh() else null
                    )
                }
            }
        }

        return null  // Couldn't infer type
    }

    // ========================================================================
    // PERFORM EXPRESSION VALIDATION (v2 native — works on ParsedExpr)
    // ========================================================================

    /**
     * Validate perform expressions in an expression tree (v2 native — works on ParsedExpr).
     *
     * This checks that handler associated types match what the expression throws.
     */
    private fun validatePerformExpressionsInExpression(
        expr: com.mezze.language.parsedast.ParsedExpr,
        implDeclarations: Map<String, com.mezze.language.parsedast.ParsedPerformerDecl>,
        effectDeclarations: Map<String, com.mezze.language.parsedast.ParsedEffectDecl>,
        functionDefinitions: Map<String, com.mezze.language.parsedast.ParsedLambda>
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()

        when (expr) {
            is com.mezze.language.parsedast.ParsedPerform -> {
                // Validate this perform expression
                val performErrors = validatePerformExpression(
                    expr,
                    implDeclarations,
                    effectDeclarations,
                    functionDefinitions
                )
                errors.addAll(performErrors)

                // Also validate the inner expression
                val innerErrors = validatePerformExpressionsInExpression(
                    expr.body,
                    implDeclarations,
                    effectDeclarations,
                    functionDefinitions
                )
                errors.addAll(innerErrors)
            }

            is com.mezze.language.parsedast.ParsedDo -> {
                for (stmt in expr.statements) {
                    when (stmt) {
                        is com.mezze.language.parsedast.ParsedStatement.Let -> {
                            errors.addAll(validatePerformExpressionsInExpression(stmt.value, implDeclarations, effectDeclarations, functionDefinitions))
                        }
                        is com.mezze.language.parsedast.ParsedStatement.Expr -> {
                            errors.addAll(validatePerformExpressionsInExpression(stmt.expr, implDeclarations, effectDeclarations, functionDefinitions))
                        }
                    }
                }
                errors.addAll(validatePerformExpressionsInExpression(expr.result, implDeclarations, effectDeclarations, functionDefinitions))
            }

            is com.mezze.language.parsedast.ParsedLambda -> {
                errors.addAll(validatePerformExpressionsInExpression(expr.body, implDeclarations, effectDeclarations, functionDefinitions))
            }

            is com.mezze.language.parsedast.ParsedIf -> {
                errors.addAll(validatePerformExpressionsInExpression(expr.condition, implDeclarations, effectDeclarations, functionDefinitions))
                errors.addAll(validatePerformExpressionsInExpression(expr.thenBranch, implDeclarations, effectDeclarations, functionDefinitions))
                errors.addAll(validatePerformExpressionsInExpression(expr.elseBranch, implDeclarations, effectDeclarations, functionDefinitions))
            }

            is com.mezze.language.parsedast.ParsedFunctionCall -> {
                errors.addAll(validatePerformExpressionsInExpression(expr.function, implDeclarations, effectDeclarations, functionDefinitions))
                errors.addAll(validatePerformExpressionsInExpression(expr.arguments, implDeclarations, effectDeclarations, functionDefinitions))
            }

            is com.mezze.language.parsedast.ParsedRecordLit -> {
                for (entry in expr.entries) {
                    when (entry) {
                        is com.mezze.language.parsedast.ParsedRecordEntry.Field -> {
                            entry.value?.let { value ->
                                errors.addAll(validatePerformExpressionsInExpression(value, implDeclarations, effectDeclarations, functionDefinitions))
                            }
                        }
                        is com.mezze.language.parsedast.ParsedRecordEntry.Spread -> {
                            errors.addAll(validatePerformExpressionsInExpression(entry.expr, implDeclarations, effectDeclarations, functionDefinitions))
                        }
                    }
                }
            }

            is com.mezze.language.parsedast.ParsedMatch -> {
                errors.addAll(validatePerformExpressionsInExpression(expr.scrutinee, implDeclarations, effectDeclarations, functionDefinitions))
                for (case in expr.cases) {
                    case.guard?.let { guard ->
                        errors.addAll(validatePerformExpressionsInExpression(guard, implDeclarations, effectDeclarations, functionDefinitions))
                    }
                    errors.addAll(validatePerformExpressionsInExpression(case.body, implDeclarations, effectDeclarations, functionDefinitions))
                }
            }

            is com.mezze.language.parsedast.ParsedVariantConstruct -> {
                expr.value?.let { value ->
                    errors.addAll(validatePerformExpressionsInExpression(value, implDeclarations, effectDeclarations, functionDefinitions))
                }
            }

            is com.mezze.language.parsedast.ParsedMemberAccess -> {
                errors.addAll(validatePerformExpressionsInExpression(expr.receiver, implDeclarations, effectDeclarations, functionDefinitions))
            }

            is com.mezze.language.parsedast.ParsedSeqLit -> {
                for (element in expr.elements) {
                    val elementExpr = when (element) {
                        is com.mezze.language.parsedast.ParsedSeqElement.Regular -> element.expr
                        is com.mezze.language.parsedast.ParsedSeqElement.Spread -> element.expr
                    }
                    errors.addAll(validatePerformExpressionsInExpression(elementExpr, implDeclarations, effectDeclarations, functionDefinitions))
                }
            }

            is com.mezze.language.parsedast.ParsedStringLit -> {
                for (part in expr.parts) {
                    if (part is com.mezze.language.parsedast.ParsedStringPart.Interpolation) {
                        errors.addAll(validatePerformExpressionsInExpression(part.expr, implDeclarations, effectDeclarations, functionDefinitions))
                    }
                }
            }

            is com.mezze.language.parsedast.ParsedPipe -> {
                errors.addAll(validatePerformExpressionsInExpression(expr.left, implDeclarations, effectDeclarations, functionDefinitions))
                errors.addAll(validatePerformExpressionsInExpression(expr.right, implDeclarations, effectDeclarations, functionDefinitions))
            }

            is com.mezze.language.parsedast.ParsedParens -> {
                errors.addAll(validatePerformExpressionsInExpression(expr.expr, implDeclarations, effectDeclarations, functionDefinitions))
            }

            // Leaf nodes
            is com.mezze.language.parsedast.ParsedIdentifier,
            is com.mezze.language.parsedast.ParsedIntLit,
            is com.mezze.language.parsedast.ParsedNatLit,
            is com.mezze.language.parsedast.ParsedFloatLit,
            is com.mezze.language.parsedast.ParsedFracLit,
            is com.mezze.language.parsedast.ParsedCharLit,
            is com.mezze.language.parsedast.ParsedPipePlaceholder,
            is com.mezze.language.parsedast.ParsedEffectCall -> {
                // No sub-expressions to validate
            }
        }

        return errors
    }

    /**
     * Validate a single perform expression (v2 native — works on ParsedPerform).
     *
     * Checks that each handler's associated type bindings are compatible
     * with what the expression actually throws.
     */
    private fun validatePerformExpression(
        expr: com.mezze.language.parsedast.ParsedPerform,
        implDeclarations: Map<String, com.mezze.language.parsedast.ParsedPerformerDecl>,
        effectDeclarations: Map<String, com.mezze.language.parsedast.ParsedEffectDecl>,
        functionDefinitions: Map<String, com.mezze.language.parsedast.ParsedLambda>
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()

        // Collect thrown error types from the inner expression
        val thrownTypes = collectThrownErrorTypes(expr.body, effectDeclarations, functionDefinitions)

        // Validate each handler
        for (handler in expr.handlers) {
            val handlerName = handler.performer.localName
            val implDecl = implDeclarations[handlerName]

            if (implDecl == null) {
                // Handler not found - this should be caught elsewhere
                continue
            }

            val effectName = implDecl.effect.localName

            // Check if handler has associated type bindings
            if (implDecl.associatedTypeBindings.isEmpty()) {
                continue
            }

            // Get thrown types for this effect
            val thrownForEffect = thrownTypes[effectName] ?: continue

            // Check each associated type binding
            for ((typeName, handlerTypeAnnotation) in implDecl.associatedTypeBindings) {
                val thrownType = thrownForEffect[typeName]

                if (thrownType == null) {
                    continue
                }

                // Convert handler's type annotation to MezzeType (expand type aliases)
                val handlerType = expandParsedTypeAnnotation(handlerTypeAnnotation)

                // Check compatibility
                val compatible = areAssociatedTypesCompatible(handlerType, thrownType)

                if (!compatible) {
                    errors.add(
                        ValidationError.HandlerTypeMismatch(
                            handlerName = handlerName,
                            effectName = effectName,
                            expectedType = handlerType,
                            actualType = thrownType,
                            sourceLocation = SourceLocation(expr.loc.startLine, expr.loc.startCol, expr.loc.file)
                        )
                    )
                }
            }
        }

        return errors
    }

    /**
     * Collect thrown error types from an expression (v2 native — works on ParsedExpr).
     *
     * Returns a map from effect name to a map of associated type name to thrown type.
     */
    private fun collectThrownErrorTypes(
        expr: com.mezze.language.parsedast.ParsedExpr,
        effectDeclarations: Map<String, com.mezze.language.parsedast.ParsedEffectDecl>,
        functionDefinitions: Map<String, com.mezze.language.parsedast.ParsedLambda>
    ): Map<String, Map<String, MezzeType>> {
        val result = mutableMapOf<String, MutableMap<String, MezzeType>>()
        val visitedFunctions = mutableSetOf<String>()

        fun traverse(e: com.mezze.language.parsedast.ParsedExpr) {
            when (e) {
                is com.mezze.language.parsedast.ParsedEffectCall -> {
                    val effectName = e.operation.qualifier?.toString() ?: ""
                    val operationName = e.operation.localName

                    val effectDecl = effectDeclarations[effectName]
                    if (effectDecl != null && effectDecl.associatedTypes.isNotEmpty()) {
                        extractAssociatedTypeValuesV2(effectDecl, operationName, e.arguments)?.let { typeMap ->
                            val effectMap = result.getOrPut(effectName) { mutableMapOf() }
                            for ((assocTypeName, extractedType) in typeMap) {
                                unifyThrownType(effectMap, assocTypeName, extractedType)
                            }
                        }
                    }
                }

                is com.mezze.language.parsedast.ParsedFunctionCall -> {
                    // Check if this is an effect call (Effect.operation {args})
                    if (e.function is com.mezze.language.parsedast.ParsedMemberAccess) {
                        val memberAccess = e.function as com.mezze.language.parsedast.ParsedMemberAccess
                        val operationName = memberAccess.member

                        val effectName = when (val receiver = memberAccess.receiver) {
                            is com.mezze.language.parsedast.ParsedIdentifier -> receiver.localName
                            is com.mezze.language.parsedast.ParsedVariantConstruct -> receiver.constructor
                            else -> null
                        }

                        if (effectName != null) {
                            val effectDecl = effectDeclarations[effectName]
                            if (effectDecl != null && effectDecl.associatedTypes.isNotEmpty()) {
                                extractAssociatedTypeValuesV2(effectDecl, operationName, e.arguments)?.let { typeMap ->
                                    val effectMap = result.getOrPut(effectName) { mutableMapOf() }
                                    for ((assocTypeName, extractedType) in typeMap) {
                                        unifyThrownType(effectMap, assocTypeName, extractedType)
                                    }
                                }
                            }
                        }
                    }

                    // If the function being called is an Identifier, look up its definition and traverse the body
                    if (e.function is com.mezze.language.parsedast.ParsedIdentifier) {
                        val funcName = (e.function as com.mezze.language.parsedast.ParsedIdentifier).localName
                        if (!visitedFunctions.contains(funcName)) {
                            val funcDef = functionDefinitions[funcName]
                            if (funcDef != null) {
                                visitedFunctions.add(funcName)
                                traverse(funcDef.body)
                            }
                        }
                    }

                    traverse(e.function)
                    traverse(e.arguments)
                }

                is com.mezze.language.parsedast.ParsedDo -> {
                    for (stmt in e.statements) {
                        when (stmt) {
                            is com.mezze.language.parsedast.ParsedStatement.Let -> traverse(stmt.value)
                            is com.mezze.language.parsedast.ParsedStatement.Expr -> traverse(stmt.expr)
                        }
                    }
                    traverse(e.result)
                }

                is com.mezze.language.parsedast.ParsedIf -> {
                    traverse(e.condition)
                    traverse(e.thenBranch)
                    traverse(e.elseBranch)
                }

                is com.mezze.language.parsedast.ParsedMatch -> {
                    traverse(e.scrutinee)
                    for (case in e.cases) {
                        traverse(case.body)
                    }
                }

                is com.mezze.language.parsedast.ParsedLambda -> traverse(e.body)

                is com.mezze.language.parsedast.ParsedRecordLit -> {
                    for (entry in e.entries) {
                        when (entry) {
                            is com.mezze.language.parsedast.ParsedRecordEntry.Field -> entry.value?.let { traverse(it) }
                            is com.mezze.language.parsedast.ParsedRecordEntry.Spread -> traverse(entry.expr)
                        }
                    }
                }

                is com.mezze.language.parsedast.ParsedVariantConstruct -> e.value?.let { traverse(it) }
                is com.mezze.language.parsedast.ParsedMemberAccess -> traverse(e.receiver)
                is com.mezze.language.parsedast.ParsedSeqLit -> e.elements.forEach { element ->
                    when (element) {
                        is com.mezze.language.parsedast.ParsedSeqElement.Regular -> traverse(element.expr)
                        is com.mezze.language.parsedast.ParsedSeqElement.Spread -> traverse(element.expr)
                    }
                }
                is com.mezze.language.parsedast.ParsedStringLit -> {
                    for (part in e.parts) {
                        if (part is com.mezze.language.parsedast.ParsedStringPart.Interpolation) traverse(part.expr)
                    }
                }
                is com.mezze.language.parsedast.ParsedPerform -> {
                    // CRITICAL: Do NOT traverse into nested perform expressions!
                    // The nested perform has its own handler that catches errors from its inner expression.
                    // We only care about errors that escape to the OUTER perform block.
                }

                is com.mezze.language.parsedast.ParsedPipe -> {
                    traverse(e.left)
                    traverse(e.right)
                }

                is com.mezze.language.parsedast.ParsedParens -> traverse(e.expr)

                // Leaf nodes
                is com.mezze.language.parsedast.ParsedIdentifier,
                is com.mezze.language.parsedast.ParsedIntLit,
                is com.mezze.language.parsedast.ParsedNatLit,
                is com.mezze.language.parsedast.ParsedFloatLit,
                is com.mezze.language.parsedast.ParsedFracLit,
                is com.mezze.language.parsedast.ParsedCharLit,
                is com.mezze.language.parsedast.ParsedPipePlaceholder,
                is com.mezze.language.parsedast.ParsedEffectCall -> { }
            }
        }

        traverse(expr)
        return result
    }

    /**
     * Extract associated type values from an effect operation call.
     *
     * For an effect like `effect Exception having 'error where { throw: {error: 'error} -> 'a }`,
     * when we see a call to `throw {error = SomeError}`, this extracts the mapping
     * from "'error" to the type of SomeError.
     *
     * This generalizes the previous hardcoded Exception/throw handling to work with
     * any effect that has associated types.
     *
     * @param effectDecl The effect declaration
     * @param operationName The name of the operation being called
     * @param args The arguments passed to the operation
     * @return A map from associated type names (e.g., "'error") to the extracted types
     */
    private fun extractAssociatedTypeValues(
        effectDecl: com.mezze.language.parsedast.ParsedEffectDecl,
        operationName: String,
        args: Expression
    ): Map<String, MezzeType>? {
        // Find the operation in the effect declaration
        val operation = effectDecl.operations.find { it.name == operationName } ?: return null

        // Get the parameter type annotation
        val paramTypeAnnotation = operation.paramType

        // We need to find which fields in the parameter type use associated types
        // Associated types are stored with the ' prefix already (e.g., "'error", "'state")
        val associatedTypeNames = effectDecl.associatedTypes.toSet()

        // Parse the parameter type annotation to find field -> associated type mappings
        val fieldToAssocType = findAssociatedTypeFieldsV2(paramTypeAnnotation, associatedTypeNames)

        if (fieldToAssocType.isEmpty()) return null

        // Extract the argument values for those fields
        if (args !is RecordLiteral) return null

        val result = mutableMapOf<String, MezzeType>()
        for ((fieldName, assocTypeName) in fieldToAssocType) {
            val fieldEntry = args.entries.filterIsInstance<RecordFieldEntry>()
                .find { it.name == fieldName }

            val fieldValue = fieldEntry?.value ?: continue

            // Get the type of the field value.
            // inferredType may be null — fall back to constructTypeFromExpression; skip if both null.
            val fieldType = fieldValue.inferredType ?: constructTypeFromExpression(fieldValue) ?: continue

            result[assocTypeName] = fieldType
        }

        return if (result.isNotEmpty()) result else null
    }

    /**
     * Find fields in a type annotation that reference associated types.
     *
     * For a type like `{error: 'error, value: Int}`, this returns {"error" -> "'error"}.
     */
    private fun findAssociatedTypeFieldsV2(
        typeAnnotation: com.mezze.language.parsedast.ParsedTypeAnnotation,
        associatedTypeNames: Set<String>
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()
        if (typeAnnotation is com.mezze.language.parsedast.ParsedRecordType) {
            for (entry in typeAnnotation.entries) {
                if (entry.type is com.mezze.language.parsedast.ParsedNamedType) {
                    val typeName = (entry.type as com.mezze.language.parsedast.ParsedNamedType).name
                    if (typeName in associatedTypeNames) result[entry.name] = typeName
                }
            }
        }
        return result
    }

    /**
     * Extract associated type values from an effect operation call (v2 native).
     *
     * Works on ParsedExpr arguments instead of v1 Expression.
     */
    private fun extractAssociatedTypeValuesV2(
        effectDecl: com.mezze.language.parsedast.ParsedEffectDecl,
        operationName: String,
        args: com.mezze.language.parsedast.ParsedExpr
    ): Map<String, MezzeType>? {
        val operation = effectDecl.operations.find { it.name == operationName } ?: return null
        val paramTypeAnnotation = operation.paramType
        val associatedTypeNames = effectDecl.associatedTypes.toSet()
        val fieldToAssocType = findAssociatedTypeFieldsV2(paramTypeAnnotation, associatedTypeNames)

        if (fieldToAssocType.isEmpty()) return null

        if (args !is com.mezze.language.parsedast.ParsedRecordLit) return null

        val result = mutableMapOf<String, MezzeType>()
        for ((fieldName, assocTypeName) in fieldToAssocType) {
            val fieldEntry = args.entries.filterIsInstance<com.mezze.language.parsedast.ParsedRecordEntry.Field>()
                .find { it.name == fieldName }

            val fieldValue = fieldEntry?.value ?: continue

            // In v2 there's no inferredType — reconstruct structurally from ParsedExpr
            val fieldType = constructTypeFromParsedExpression(fieldValue) ?: continue

            result[assocTypeName] = fieldType
        }

        return if (result.isNotEmpty()) result else null
    }

    /**
     * Construct a MezzeType from a ParsedExpr when no inferredType is available.
     *
     * This is the v2 equivalent of constructTypeFromExpression.
     */
    private fun constructTypeFromParsedExpression(expr: com.mezze.language.parsedast.ParsedExpr): MezzeType? {
        return when (expr) {
            is com.mezze.language.parsedast.ParsedRecordLit -> {
                val fields = mutableMapOf<String, MezzeType>()
                for (entry in expr.entries) {
                    if (entry is com.mezze.language.parsedast.ParsedRecordEntry.Field && entry.value != null) {
                        val fieldType = constructTypeFromParsedExpression(entry.value!!)
                        if (fieldType != null) {
                            fields[entry.name] = fieldType
                        }
                    }
                }
                MezzeType.RecordType(fields, null)
            }
            is com.mezze.language.parsedast.ParsedVariantConstruct -> {
                if (expr.constructor == "True" || expr.constructor == "False") {
                    MezzeType.BoolType
                } else {
                    val payloadType = expr.value?.let { constructTypeFromParsedExpression(it) }
                    MezzeType.VariantType(
                        constructors = mapOf(expr.constructor to payloadType),
                        rowVar = RowVar.fresh()
                    )
                }
            }
            is com.mezze.language.parsedast.ParsedStringLit -> MezzeType.StringType
            is com.mezze.language.parsedast.ParsedIntLit -> MezzeType.IntType
            is com.mezze.language.parsedast.ParsedFloatLit -> MezzeType.FloatType
            is com.mezze.language.parsedast.ParsedNatLit -> MezzeType.NatType
            is com.mezze.language.parsedast.ParsedCharLit -> MezzeType.CharType
            is com.mezze.language.parsedast.ParsedFracLit -> MezzeType.FracType
            is com.mezze.language.parsedast.ParsedIdentifier -> null  // Can't determine type from just an identifier
            else -> null
        }
    }

    private fun findAssociatedTypeFields(
        typeAnnotation: TypeAnnotation,
        associatedTypeNames: Set<String>
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()

        when (typeAnnotation) {
            is RecordTypeAnnotation -> {
                for (entry in typeAnnotation.entries) {
                    // Check if the field type is a type variable that matches an associated type
                    // Type variables in annotations are IdentifierTypeAnnotation with names like 'error
                    if (entry.type is IdentifierTypeAnnotation) {
                        val typeName = (entry.type as IdentifierTypeAnnotation).name
                        if (typeName in associatedTypeNames) {
                            result[entry.name] = typeName
                        }
                    }
                }
            }
            else -> {
                // Other type annotations (e.g., single type var) - not handled for now
            }
        }

        return result
    }

    /**
     * Extract the error type from a throw call's arguments.
     * @deprecated Use extractAssociatedTypeValues instead - this is kept for reference
     */
    private fun extractThrownErrorType(args: Expression): MezzeType? {
        if (args !is RecordLiteral) {
            return null
        }

        val errorEntry = args.entries.filterIsInstance<RecordFieldEntry>()
            .find { it.name == "error" }

        val errorValue = errorEntry?.value ?: return null

        // inferredType may be null — fall back to constructTypeFromExpression.
        if (errorValue.inferredType != null) {
            return errorValue.inferredType
        }

        // If inferredType is not set, try to determine type from the expression itself
        // For VariantConstruction, we can construct the type directly
        if (errorValue is VariantConstruction) {
            val constructorName = errorValue.constructor
            // Create a VariantType with just this constructor
            // Recursively construct the payload type from the value expression
            val payloadType = constructTypeFromExpression(errorValue.value)
            val variantType = MezzeType.VariantType(
                constructors = mapOf(constructorName to payloadType),
                rowVar = RowVar.fresh()  // Open variant - may be extended
            )
            return variantType
        }

        return null
    }

    /**
     * Construct a MezzeType from an expression when inferredType is not available.
     *
     * This is used for expressions where inferredType is not available.
     */
    private fun constructTypeFromExpression(expr: Expression?): MezzeType? {
        if (expr == null) return null

        // When inferredType is available, use it; otherwise reconstruct structurally.
        if (expr.inferredType != null) {
            return expr.inferredType
        }

        return when (expr) {
            is RecordLiteral -> {
                // Construct RecordType from the literal
                val fields = mutableMapOf<String, MezzeType>()
                for (entry in expr.entries) {
                    if (entry is RecordFieldEntry && entry.value != null) {
                        val fieldType = constructTypeFromExpression(entry.value)
                        if (fieldType != null) {
                            fields[entry.name] = fieldType
                        }
                    }
                }
                MezzeType.RecordType(fields, null)
            }
            is VariantConstruction -> {
                // True and False are Bool type (optimized in interpreter)
                if (expr.constructor == "True" || expr.constructor == "False") {
                    MezzeType.BoolType
                } else {
                    val payloadType = constructTypeFromExpression(expr.value)
                    MezzeType.VariantType(
                        constructors = mapOf(expr.constructor to payloadType),
                        rowVar = RowVar.fresh()
                    )
                }
            }
            is StringLiteral -> MezzeType.StringType
            is IntLiteral -> MezzeType.IntType
            is FloatLiteral -> MezzeType.FloatType
            is Identifier -> {
                // For identifiers without inferredType, we can't determine the type
                // Return null to skip extraction for this expression
                null
            }
            else -> null
        }
    }

    /**
     * Expand a type annotation using the type declarations.
     *
     * This converts user-defined types (like PartialError) to their full type definition
     * (like VariantType with the constructors from the type declaration).
     */
    private fun expandParsedTypeAnnotation(annotation: com.mezze.language.parsedast.ParsedTypeAnnotation): MezzeType {
        return when (annotation) {
            is com.mezze.language.parsedast.ParsedNamedType -> {
                PrimitiveTypes.fromName(annotation.name)?.let { return it }
                val typeDecl = typeDeclarations[annotation.name]
                if (typeDecl != null) when (val def = typeDecl.definition) {
                    is com.mezze.language.parsedast.ParsedVariantDef -> MezzeType.VariantType(
                        constructors = def.constructors.associate { it.name to it.dataType?.let { dt -> convertParsedTypeAnnotation(dt) } },
                        rowVar = if (def.isOpen) RowVar.fresh() else null)
                    is com.mezze.language.parsedast.ParsedRecordDef -> MezzeType.RecordType(fields = def.fields.associate { it.name to convertParsedTypeAnnotation(it.type) })
                    is com.mezze.language.parsedast.ParsedNewtypeDef -> convertParsedTypeAnnotation(def.wrappedType)
                } else MezzeType.VarType(TypeVar(annotation.name))
            }
            is com.mezze.language.parsedast.ParsedTypeApp -> {
                val typeDecl = typeDeclarations[annotation.constructor]
                if (typeDecl != null) expandParsedTypeAnnotation(com.mezze.language.parsedast.ParsedNamedType(annotation.constructor, annotation.loc))
                else convertParsedTypeAnnotation(annotation)
            }
            else -> convertParsedTypeAnnotation(annotation)
        }
    }


    /**
     * Unify a thrown type with existing types in the map.
     */
    private fun unifyThrownType(
        effectMap: MutableMap<String, MezzeType>,
        typeName: String,
        newType: MezzeType
    ) {
        val existing = effectMap[typeName]
        if (existing == null) {
            effectMap[typeName] = newType
        } else if (existing is MezzeType.VariantType && newType is MezzeType.VariantType) {
            // Merge variant constructors
            val mergedConstructors = existing.constructors + newType.constructors
            effectMap[typeName] = MezzeType.VariantType(mergedConstructors, null)
        }
        // Otherwise keep existing (first wins for non-variants)
    }

    /**
     * Check if handler's associated type is compatible with expression's inferred type.
     *
     * For variants, handler must accept all variants that expression throws.
     */
    private fun areAssociatedTypesCompatible(handlerType: MezzeType, exprType: MezzeType): Boolean {
        // If types are exactly equal, they're compatible
        if (handlerType == exprType) return true

        // If handler type is polymorphic (type variable), it accepts any concrete type
        if (handlerType is MezzeType.VarType) {
            return true
        }

        // BoolType is compatible with the Bool variant [True, False]
        if (handlerType is MezzeType.BoolType && exprType is MezzeType.VariantType) {
            return isBoolVariant(exprType)
        }
        if (exprType is MezzeType.BoolType && handlerType is MezzeType.VariantType) {
            return isBoolVariant(handlerType)
        }

        // For variant types, check if handler accepts all expr variants
        if (handlerType is MezzeType.VariantType && exprType is MezzeType.VariantType) {
            // Both are Bool variants
            if (isBoolVariant(handlerType) && isBoolVariant(exprType)) {
                return true
            }

            val handlerVariantNames = handlerType.constructors.keys
            val exprVariantNames = exprType.constructors.keys

            // Handler must accept ALL variants from expression
            return exprVariantNames.all { it in handlerVariantNames }
        }

        // For mixed types (one variant, one not), they are incompatible
        if ((handlerType is MezzeType.VariantType) != (exprType is MezzeType.VariantType)) {
            return false
        }

        // For other types, they must be exactly equal
        return handlerType == exprType
    }

    /**
     * Check if a variant type is the Bool variant: | True, False |
     */
    private fun isBoolVariant(type: MezzeType.VariantType): Boolean {
        return type.constructors.keys == setOf("True", "False")
    }

    /**
     * Check if a concrete MezzeType matches a TypeAnnotation structurally.
     * Used for ability implementation matching.
     */
    private fun typeMatchesAnnotation(
        type: MezzeType,
        annotation: com.mezze.language.parsedast.ParsedTypeAnnotation,
        typeVars: Set<String> = emptySet(),
        typeVarBindings: MutableMap<String, MezzeType> = mutableMapOf()
    ): Boolean {
        return when (annotation) {
            is com.mezze.language.parsedast.ParsedNamedType -> {
                if (annotation.name in typeVars) { val e = typeVarBindings[annotation.name]; if (e != null) e == type else { typeVarBindings[annotation.name] = type; true } }
                else { val b = PrimitiveTypes.matchesBuiltinType(annotation.name, type); if (b != null) b else when (type) { is MezzeType.RecordType -> type.typeHash?.let { it == typeDeclarations[annotation.name]?.contentHash } ?: (type.typeName == annotation.name); is MezzeType.VariantType -> { val dh = typeDeclarations[annotation.name]?.contentHash; if (type.typeHash != null && dh != null) type.typeHash == dh else if (type.typeName == annotation.name) true else type.typeName == null && type.constructors.containsKey(annotation.name) }; is MezzeType.TypeApplication -> type.typeHash?.let { it == typeDeclarations[annotation.name]?.contentHash } ?: (type.constructor == annotation.name); else -> false } }
            }
            is com.mezze.language.parsedast.ParsedTypeApp -> when { type is MezzeType.TypeApplication && type.constructor == annotation.constructor -> type.args.size == annotation.args.size && type.args.zip(annotation.args).all { (a, b) -> typeMatchesAnnotation(a, b, typeVars, typeVarBindings) }; type is MezzeType.SeqType && annotation.constructor == BuiltinTypes.SEQ -> annotation.args.size == 1 && typeMatchesAnnotation(type.elementType, annotation.args[0], typeVars, typeVarBindings); type is MezzeType.ListType && annotation.constructor == BuiltinTypes.LIST -> annotation.args.size == 1 && typeMatchesAnnotation(type.elementType, annotation.args[0], typeVars, typeVarBindings); else -> false }
            is com.mezze.language.parsedast.ParsedRecordType -> type is MezzeType.RecordType && type.fields.size == annotation.entries.size && annotation.entries.all { e -> type.fields[e.name]?.let { typeMatchesAnnotation(it, e.type, typeVars, typeVarBindings) } ?: false }
            is com.mezze.language.parsedast.ParsedVariantType -> type is MezzeType.VariantType && annotation.constructors.all { c -> val p = type.constructors[c.name]; if (c.dataType == null) p == null else if (p == null) false else typeMatchesAnnotation(p, c.dataType!!, typeVars, typeVarBindings) }
            is com.mezze.language.parsedast.ParsedSeqType -> type is MezzeType.SeqType && typeMatchesAnnotation(type.elementType, annotation.elementType, typeVars, typeVarBindings)
            else -> false
        }
    }

}
