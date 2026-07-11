package com.mezze.language.typechecker.validation

import com.mezze.language.ast.*
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.constraints.Constraint
import com.mezze.language.typechecker.constraints.ConstraintResult
import com.mezze.language.typechecker.debug.DebugLogger
import com.mezze.language.typechecker.debug.DebugLogger.Category
import com.mezze.language.ast.solving.Substitution
import com.mezze.language.typechecker.solving.expandTypeApplicationToVariant
import com.mezze.language.typechecker.abilities.BuiltInAbilities
import com.mezze.language.typechecker.codec.CodecGenerator
import com.mezze.language.typechecker.hashing.ContentHashComputer
import com.mezze.language.typechecker.util.StringDistance
import com.mezze.language.typechecker.util.TypeAnnotationConverter
import com.mezze.language.typechecker.util.TypeAnnotationMatcher
import com.mezze.language.typechecker.WellKnownHashes
import com.mezze.language.typechecker.WellKnownHashConstants

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
    private val registry: com.mezze.language.typechecker.context.DeclarationRegistry = com.mezze.language.typechecker.context.DeclarationRegistry.EMPTY,
    private val implDeclarations: Map<String, com.mezze.language.parsedast.ParsedPerformerDecl> = emptyMap()
) {
    private val typeDeclarations: Map<String, com.mezze.language.parsedast.ParsedTypeDecl> get() = registry.typeDeclarations
    private val effectDeclarations: Map<String, com.mezze.language.parsedast.ParsedEffectDecl> get() = registry.effectDeclarations
    private val abilityResolver = AbilityResolver(context, registry)
    private val effectValidator = EffectValidator(context)
    private val matchValidator = MatchValidator(typeDeclarations, registry)
    private val performValidator = PerformValidator(typeDeclarations, implDeclarations)
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

        // Validate each binding from constraint results.
        //
        // Undefined-variable reporting (V0702) lives in TypeChecker.kt's
        // pre-validation pass (~line 454), which classifies the name
        // (ability / effect / prelude method / private item) and builds the
        // richer "Did you mean" / "available as a method on Int" hints.
        // We deliberately do NOT re-emit `VariableNotInScope` here — doing
        // so was the source of the duplicate V0702 messages with different
        // suggestion sets seen on undefined-identifier reproductions.
        for ((bindingName, result) in constraintResults) {
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
                    performValidator.validatePerformExpressionsInExpression(decl.value, implDeclarations, effectDeclarations, parsedFunctionDefs)
                }
                is com.mezze.language.parsedast.ParsedTopLevelExpr -> {
                    performValidator.validatePerformExpressionsInExpression(decl.expression, implDeclarations, effectDeclarations, parsedFunctionDefs)
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
        return matchValidator.validateParsedMatchesInExpr(expr, substitution, constraintResults)
    }

    /** Infer the scrutinee type for a ParsedMatch from patterns or type declarations. */
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
                                loc = constraint.loc
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
                                        ctor.name to ctor.dataType?.let { TypeAnnotationConverter.convert(it) }
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
                                    loc = constraint.loc
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
            // #231: an effectful method call (e.g. `xs.filter {p=...}.is_empty {}`,
            // where filter's result is `SuspendedType(SeqType(Int), <Async>)` per
            // the effect row on the impl op) feeds a Suspended into the next
            // ability-constraint slot. The impl table is keyed on the inner
            // value type — unwrap before structural matching, otherwise the
            // matcher trivially rejects `SuspendedType` vs `[Int]` and falls
            // through to the "no impl" error.
            val concreteType = when (val applied = substitution.apply(typeVarType)) {
                is MezzeType.SuspendedType -> applied.valueType
                else -> applied
            }

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
            val effectiveHash = constraint.abilityHash ?: WellKnownHashConstants.resolveAbilityHash(abilityName)

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
                    TypeAnnotationMatcher.typeMatchesAnnotation(type, annotation, typeDeclarations, implInfo.typeVars, typeVarBindings)
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

            // Check if this is an auto-derivable Encode/Decode/EncodeRepr/DecodeRepr ability.
            // These are automatically generated by CodecGenerator for serializable types.
            // The ConstraintSolver (Phase 3) already validated format impl availability,
            // so we trust it here — just check codec + serializable.
            if (WellKnownHashes.isCodecAbility(effectiveHash) && codecGenerator.isSerializable(concreteType)) {
                continue
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
                    context = constraint.context ?: bindingContext,
                    loc = constraint.loc,
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

                // Check field types match (convert parsed annotations to MezzeType for comparison)
                val fieldsCompatible = aliasFields.all { (name, parsedType) ->
                    val aliasFieldType = try { TypeAnnotationConverter.convert(parsedType) } catch (_: Exception) { null }
                    val recordFieldType = recordType.fields[name]
                    if (aliasFieldType == null || recordFieldType == null) false
                    else alphaEquivalent(aliasFieldType, recordFieldType, mutableMapOf())
                }
                if (!fieldsCompatible) continue

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

                // Check if the variant's constructors exactly match the declaration's constructors
                if (aliasConstructors == variantConstructors.keys) {
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
    ): List<MezzeType>? = TypeAnnotationMatcher.inferTypeArgsFromVariant(variantType, typeDecl, definition)

    /**
     * Check if an ability can be derived without an explicit impl in context.
     * Only handles things that CAN'T come from .mz files:
     * - Inspect: universally available for ALL types
     * - VarType pass-through: polymorphic types are constrained at call site
     * - Eq/Ord derivation for composite types (records, variants, lists, seqs)
     * - Bool variant matching for logical operators
     */
    private fun canDeriveAbility(abilityName: String, type: MezzeType, abilityHash: com.mezze.language.typedast.Hash? = null): Boolean {
        var unwrappedType = if (type is MezzeType.SuspendedType) type.valueType else type

        if (unwrappedType is MezzeType.TypeApplication) {
            val expanded = expandTypeApplicationToVariant(unwrappedType, registry)
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

            abilityHash == WellKnownHashConstants.ABILITY_LOGICALAND ||
            abilityHash == WellKnownHashConstants.ABILITY_LOGICALOR ||
            abilityHash == WellKnownHashConstants.ABILITY_LOGICALNOT -> {
                unwrappedType is MezzeType.BoolType || isBoolVariant(unwrappedType)
            }

            else -> false
        }
    }

    /**
     * Check if a type has an ability via derivation OR via an explicit impl in context.
     */
    private fun canDeriveOrHasImpl(abilityName: String, type: MezzeType, abilityHash: com.mezze.language.typedast.Hash? = null): Boolean {
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
            TypeAnnotationMatcher.typeMatchesAnnotation(type, annotations[0], typeDeclarations, implInfo.typeVars, typeVarBindings)
        }
    }

    private fun isBoolVariant(type: MezzeType): Boolean = PrimitiveTypes.isBoolVariant(type)

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
            TypeAnnotationConverter.convert(parsedAnnotation)
        } catch (_: Exception) {
            return emptyList()
        }

        val finalType = substitution.apply(inferredType)

        if (!alphaEquivalent(annotatedType, finalType, mutableMapOf())) {
            val bindingName = if (decl.isSimpleBinding) decl.variableName else "_pattern"
            return listOf(
                ValidationError.AnnotationMismatch(
                    bindingName = bindingName,
                    annotatedType = annotatedType,
                    inferredType = finalType,
                    loc = decl.loc,
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

        val bindingKey = if (decl.isSimpleBinding) decl.variableName else return emptyList()
        val inferredType = constraintResults[bindingKey]?.type ?: return emptyList()
        val finalType = substitution.apply(inferredType)

        when (finalType) {
            // Functions are definitions — their effect row is unperformed
            // until the function is called. Always fine at the top level.
            // The entry-point binding (whatever the user names it) takes
            // this shape: `{} -> ... <*>`. The CLI / runtime layer is
            // responsible for picking which function is the entry; the
            // typechecker just validates that any top-level value with
            // unhandled effects must be a function.
            is MezzeType.FunctionType -> { /* fine */ }
            is MezzeType.SuspendedType -> {
                if (finalType.effects.performed) {
                    errors.add(
                        ValidationError.TopLevelPerformedEffects(
                            bindingName = decl.variableName,
                            effectRow = finalType.effects,
                            loc = decl.loc
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
     * Check if two types are compatible for annotation checking.
     *
     * This is more lenient than strict equality:
     * - Ignores type variable names (alpha equivalence)
     * - Allows annotated types to be more specific than inferred
     */
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
                            loc = null
                        )
                    )
                }
            }
        }

        return errors
    }

    fun validateParsedExhaustiveness(
        matchExpr: com.mezze.language.parsedast.ParsedMatch,
        scrutineeType: MezzeType
    ): ValidationResult = matchValidator.validateParsedExhaustiveness(matchExpr, scrutineeType)

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
                    loc = pattern.loc
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
                        is com.mezze.language.parsedast.ParsedStatement.Bind -> {
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
                val scrutineeType = matchValidator.inferParsedScrutineeTypeFromPatterns(expr)

                if (scrutineeType != null) {
                    val finalScrutineeType = substitution.apply(scrutineeType)
                    val exhaustivenessResult = validateParsedExhaustiveness(expr, finalScrutineeType)

                    if (exhaustivenessResult is ValidationResult.Failure) {
                        errors.addAll(exhaustivenessResult.errors)
                    }
                }

                // Check for unreachable patterns (applies to all match expressions, not just variants)
                val unreachableResult = matchValidator.validateParsedUnreachablePatterns(expr)
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

            is com.mezze.language.parsedast.ParsedBinaryOp -> {
                errors.addAll(validatePatternsInExpression(expr.left, substitution))
                errors.addAll(validatePatternsInExpression(expr.right, substitution))
            }

            is com.mezze.language.parsedast.ParsedUnaryOp -> {
                errors.addAll(validatePatternsInExpression(expr.operand, substitution))
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

}
