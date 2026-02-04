package com.funk.language.typechecker.validation

import com.funk.language.ast.*
import com.funk.language.typechecker.context.TypingContext
import com.funk.language.typechecker.constraints.Constraint
import com.funk.language.typechecker.constraints.ConstraintResult
import com.funk.language.typechecker.solving.Substitution
import com.funk.language.typechecker.abilities.BuiltInAbilities

/**
 * Type validator - validates types after constraint solving.
 *
 * This is PHASE 4 of the new architecture:
 * - PHASE 1: Build context (ContextBuilder)
 * - PHASE 2: Generate constraints (ConstraintGenerator)
 * - PHASE 3: Solve constraints (ConstraintSolver)
 * - PHASE 4: Validate types (TypeValidator) ← WE ARE HERE
 * - PHASE 5: Apply substitution (SubstitutionApplier)
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
    private val typeDeclarations: Map<String, TypeDeclaration> = emptyMap()
) {
    private val abilityResolver = AbilityResolver(context)
    private val effectValidator = EffectValidator(context)

    /**
     * Validate a program after constraint solving.
     *
     * Returns Success with warnings, or Failure with errors.
     */
    fun validate(
        program: Program,
        constraintResults: Map<String, ConstraintResult>,
        substitution: Substitution
    ): ValidationResult {

        val errors = mutableListOf<ValidationError>()
        val warnings = mutableListOf<String>()

        // Validate each binding
        for ((bindingName, result) in constraintResults) {
            // Check for undefined variables
            if (result.undefinedVariables.isNotEmpty()) {
                for (undefinedVar in result.undefinedVariables) {
                    // Get similar variable names from context for suggestions
                    val allVariables = context.getAllBindings()
                    val similarNames = findSimilarNames(undefinedVar.name, allVariables.keys.toList())

                    errors.add(
                        ValidationError.VariableNotInScope(
                            variableName = undefinedVar.name,
                            similarNames = similarNames,
                            sourceLocation = undefinedVar.sourceLocation
                        )
                    )
                }
            }

            // Apply substitution to get final type
            val finalType = substitution.apply(result.type)

            // Check if a built-in ability is being used as a value
            val abilityValueErrors = validateBuiltInAbilityUsage(finalType, bindingName)
            errors.addAll(abilityValueErrors)

            // Validate ability constraints
            val abilityErrors = validateAbilityConstraints(
                result.abilityConstraints,
                finalType,
                bindingName,
                substitution
            )
            errors.addAll(abilityErrors)

            // Validate effect usage
            // NOTE: Now that we have SuspendedType conversion, effects at top level
            // are properly wrapped in SuspendedType, so validation should pass.
            // However, we keep validation disabled for top-level let bindings because
            // effects are ALLOWED to be unhandled at top level - they become part
            // of the SuspendedType. The validation would only be useful to check
            // that function bodies don't use undeclared effects, which is a different concern.
            // TODO: Consider re-enabling for function body validation only
            // val effectErrors = validateEffectUsage(
            //     result.effects,
            //     finalType,
            //     bindingName
            // )
            // errors.addAll(effectErrors)
        }

        // Validate type annotations match inferred types
        for (item in program.items) {
            when (item) {
                is LetBinding -> {
                    val annotationErrors = validateAnnotation(item, substitution)
                    errors.addAll(annotationErrors)
                }
                else -> {
                    // Other items don't need annotation validation here
                }
            }
        }

        // Validate let binding patterns (both top-level and in expressions)
        for (item in program.items) {
            when (item) {
                is LetBinding -> {
                    // Validate top-level let binding pattern
                    // CRITICAL: Use the expression's inferred type (set by SubstitutionApplier)
                    // instead of looking up in constraintResults, because destructuring patterns
                    // have multiple variable names and can't be keyed consistently
                    val scrutineeType = item.expression.inferredType
                    if (scrutineeType != null) {
                        val patternErrors = validateLetPattern(item.pattern, scrutineeType)
                        errors.addAll(patternErrors)
                    }

                    // Also recursively validate patterns in the binding's expression
                    val exprPatternErrors = validatePatternsInExpression(item.expression, substitution)
                    errors.addAll(exprPatternErrors)
                }
                is TopLevelExpression -> {
                    // Validate patterns in top-level expressions
                    val exprPatternErrors = validatePatternsInExpression(item.expression, substitution)
                    errors.addAll(exprPatternErrors)
                }
                else -> {
                    // Other items handled below
                }
            }
        }

        // Validate effect declarations
        for (item in program.items) {
            when (item) {
                is EffectDeclaration -> {
                    val declErrors = effectValidator.validateEffectDeclaration(item)
                    errors.addAll(declErrors)
                }

                is ImplDeclaration -> {
                    // Find corresponding effect declaration
                    val effectDecl = program.items.filterIsInstance<EffectDeclaration>()
                        .find { it.name == item.effectName }

                    if (effectDecl != null) {
                        val implErrors = effectValidator.validateEffectImpl(item, effectDecl)
                        errors.addAll(implErrors)
                    }
                }

                else -> {
                    // Other items don't need validation here
                }
            }
        }

        // Return result
        return if (errors.isEmpty()) {
            ValidationResult.Success(warnings)
        } else {
            ValidationResult.Failure(errors)
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
                    // Check if ability is satisfied
                    val type = substitution.apply(FunkType.VarType(constraint.typeVar))
                    val result = abilityResolver.checkAbility(
                        type,
                        constraint.abilityName,
                        constraint.typeArgs
                    )

                    if (result is AbilityResolver.AbilityCheckResult.Unsatisfied) {
                        errors.add(
                            ValidationError.AbilityNotSatisfied(
                                type = type,
                                abilityName = constraint.abilityName,
                                requiredBy = constraint.context,
                                sourceLocation = null
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
                        is FunkType.VariantType -> appliedType
                        is FunkType.TypeApplication -> {
                            // Expand TypeApplication to VariantType using type declarations
                            val typeDecl = typeDeclarations[appliedType.constructor]
                            val definition = typeDecl?.definition
                            if (definition is VariantDefinition) {
                                // Build VariantType from definition
                                FunkType.VariantType(
                                    constructors = definition.constructors.associate { ctor ->
                                        ctor.name to ctor.dataType?.let { convertTypeAnnotation(it) }
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
                                    sourceLocation = null
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
     * Example: Show.show {val = 42} generates AbilityRequired(typeVar='a, abilityName=Show)
     * After substitution, 'a = Int, so we check if Show_Int implementation exists.
     */
    private fun validateAbilityConstraints(
        constraints: List<Constraint.AbilityRequired>,
        type: FunkType,
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
            val typeVarType = FunkType.VarType(constraint.typeVar)
            val concreteType = substitution.apply(typeVarType)

            // Get type name for implementation lookup
            val typeName = getTypeNameForAbility(concreteType)

            // If the type is still a type variable (polymorphic), skip validation
            // It will be checked when instantiated with a concrete type
            if (typeName.startsWith("'") || typeName == "Unknown") {
                continue
            }

            // Check if this is a built-in ability with built-in implementation
            val abilityName = constraint.abilityName

            if (isBuiltInAbilityImplementation(abilityName, concreteType)) {
                // Built-in ability for built-in type (e.g., Eq for Int)
                continue
            }

            // Check if user-defined implementation exists
            // Handle multi-type abilities like Convert 'a 'b
            val implTypeParts = mutableListOf(typeName)
            var hasPolymorphicTypeArg = false
            for (typeArg in constraint.typeArgs) {
                val argType = substitution.apply(typeArg)
                val argTypeName = getTypeNameForAbility(argType)
                if (argTypeName.startsWith("'") || argTypeName == "Unknown") {
                    // Type argument is still polymorphic - can't validate yet
                    hasPolymorphicTypeArg = true
                    break
                }
                implTypeParts.add(argTypeName)
            }

            // If any type argument is still polymorphic, skip validation for this constraint
            // (it will be checked when fully instantiated)
            if (hasPolymorphicTypeArg) {
                continue
            }

            val implKey = "${abilityName}_${implTypeParts.joinToString("_")}"

            // Check for exact match first
            if (context.hasAbilityImpl(implKey)) {
                continue
            }

            // For multi-parameter abilities, try to find a partial match
            // For example, if we're looking for Convert_Int and there's Convert_Int_String, that's a match
            val hasPartialMatch = if (implTypeParts.size == 1 && constraint.typeArgs.isNotEmpty()) {
                // We only have the first type arg - look for any impl that starts with this prefix
                findMatchingMultiParameterImpl(abilityName, implTypeParts)
            } else {
                false
            }

            if (hasPartialMatch) {
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
                    context = constraint.context ?: bindingContext
                )
            )
        }

        return errors
    }

    /**
     * Find a matching multi-parameter ability implementation.
     *
     * For multi-parameter abilities like Convert 'a 'b, when we only know the first type arg,
     * we look for any implementation that matches the prefix.
     *
     * Example: Looking for Convert_Int when impl Convert_Int_String exists -> returns true
     */
    private fun findMatchingMultiParameterImpl(
        abilityName: String,
        knownTypeParts: List<String>
    ): Boolean {
        // Build prefix to search for
        val prefix = "${abilityName}_${knownTypeParts.joinToString("_")}_"

        // Check if any impl key starts with this prefix
        return context.getAllAbilityImpls().keys.any { implKey ->
            implKey.startsWith(prefix) || implKey == "${abilityName}_${knownTypeParts.joinToString("_")}"
        }
    }

    /**
     * Check if this is a built-in ability with built-in implementation.
     */
    private fun isBuiltInAbilityImplementation(abilityName: String, type: FunkType): Boolean {
        // Unwrap SuspendedType to check the inner value type
        val unwrappedType = if (type is FunkType.SuspendedType) type.valueType else type

        // Built-in abilities have implicit implementations for certain types
        return when (abilityName) {
            // Numeric abilities - implemented for Int, Float, Nat, Frac
            "NumAdd", "NumSub", "NumMul", "NumDiv", "NumMod", "NumNeg" -> {
                unwrappedType is FunkType.IntType ||
                unwrappedType is FunkType.FloatType ||
                unwrappedType is FunkType.NatType ||
                unwrappedType is FunkType.FracType
            }

            // Equality - implemented for most primitive types
            "Eq" -> {
                unwrappedType is FunkType.IntType ||
                unwrappedType is FunkType.FloatType ||
                unwrappedType is FunkType.StringType ||
                unwrappedType is FunkType.BooleanType ||
                unwrappedType is FunkType.NatType ||
                unwrappedType is FunkType.FracType ||
                unwrappedType is FunkType.OrderingType ||
                isBoolVariant(unwrappedType)
            }

            // Ordering - implemented for comparable types
            "Ord" -> {
                unwrappedType is FunkType.IntType ||
                unwrappedType is FunkType.FloatType ||
                unwrappedType is FunkType.StringType ||
                unwrappedType is FunkType.NatType ||
                unwrappedType is FunkType.FracType
            }

            // Inspect - built-in ability for displaying values (to_str)
            // Note: "Show" is NOT a built-in ability - it's commonly used as a user-defined ability name
            "Inspect" -> {
                unwrappedType is FunkType.IntType ||
                unwrappedType is FunkType.FloatType ||
                unwrappedType is FunkType.StringType ||
                unwrappedType is FunkType.BooleanType ||
                unwrappedType is FunkType.NatType ||
                unwrappedType is FunkType.FracType ||
                unwrappedType is FunkType.OrderingType ||
                isBoolVariant(unwrappedType)
            }

            // Logical operations - only for Bool
            "LogicalAnd", "LogicalOr", "LogicalNot" -> {
                unwrappedType is FunkType.BooleanType || isBoolVariant(unwrappedType)
            }

            else -> false
        }
    }

    /**
     * Check if type is the Bool variant type.
     */
    private fun isBoolVariant(type: FunkType): Boolean {
        return type is FunkType.VariantType &&
               type.constructors.keys == setOf("True", "False")
    }

    /**
     * Get type name for ability implementation lookup.
     */
    private fun getTypeNameForAbility(type: FunkType): String {
        return when (type) {
            is FunkType.IntType -> "Int"
            is FunkType.FloatType -> "Float"
            is FunkType.StringType -> "String"
            is FunkType.BooleanType -> "Bool"
            is FunkType.NatType -> "Nat"
            is FunkType.FracType -> "Frac"
            is FunkType.OrderingType -> "Ordering"

            is FunkType.VarType -> type.variable.name  // Still polymorphic

            is FunkType.VariantType -> {
                // Special case: Bool variant
                if (type.constructors.keys == setOf("True", "False")) {
                    "Bool"
                } else {
                    "Variant"
                }
            }

            is FunkType.RecordType -> {
                // For record types, extract the field types (used for ability method arguments)
                type.fields.values.firstOrNull()?.let { getTypeNameForAbility(it) } ?: "Record"
            }

            is FunkType.TypeApplication -> {
                if (type.args.isEmpty()) {
                    type.constructor
                } else {
                    val argNames = type.args.map { getTypeNameForAbility(it) }
                    "${type.constructor}_${argNames.joinToString("_")}"
                }
            }

            is FunkType.ArrayType -> "Array_${getTypeNameForAbility(type.elementType)}"
            is FunkType.ListType -> "List_${getTypeNameForAbility(type.elementType)}"
            is FunkType.SetType -> "Set_${getTypeNameForAbility(type.elementType)}"
            is FunkType.MapType -> "Map_${getTypeNameForAbility(type.keyType)}_${getTypeNameForAbility(type.valueType)}"

            is FunkType.SuspendedType -> getTypeNameForAbility(type.valueType)
            is FunkType.FunctionType -> "Function"
            is FunkType.EffectRow -> "EffectRow"
        }
    }

    /**
     * Validate effect usage.
     */
    private fun validateEffectUsage(
        usedEffects: Set<String>,
        type: FunkType,
        context: String
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()

        // Get allowed effects from type
        val allowedEffects = when (type) {
            is FunkType.FunctionType -> type.effects?.effects ?: emptySet()
            is FunkType.SuspendedType -> type.effects.effects
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
     * Validate that a type annotation matches the inferred type.
     *
     * This checks that when a user writes:
     *   let x: Int = ...
     *
     * The inferred type actually matches Int.
     *
     * For function types, it also checks:
     * - Parameter count and types
     * - Return type
     * - Ability constraints
     * - Effect annotations
     */
    private fun validateAnnotation(
        binding: LetBinding,
        substitution: Substitution
    ): List<ValidationError> {
        val annotation = binding.typeAnnotation ?: return emptyList()
        val inferredType = binding.inferredType ?: return emptyList()

        // Try to convert annotation to FunkType
        // If conversion fails, skip validation (annotation might use unsupported syntax)
        val annotatedType = try {
            convertAnnotationToType(annotation)
        } catch (e: Exception) {
            // Skip validation if annotation can't be converted
            // This can happen with array annotations or other edge cases
            return emptyList()
        }

        // Apply substitution to inferred type
        val finalType = substitution.apply(inferredType)

        // Check if types are compatible
        if (!typesCompatible(annotatedType, finalType)) {
            val bindingName = if (binding.isSimpleBinding) binding.variableName else "_pattern"
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
     * Check if two types are compatible for annotation checking.
     *
     * This is more lenient than strict equality:
     * - Ignores type variable names (alpha equivalence)
     * - Allows annotated types to be more specific than inferred
     */
    private fun typesCompatible(annotated: FunkType, inferred: FunkType): Boolean {
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
        type1: FunkType,
        type2: FunkType,
        mapping: MutableMap<TypeVar, TypeVar>
    ): Boolean {
        return when {
            // Exact match
            type1 == type2 -> true

            // Both are type variables - check if they correspond
            type1 is FunkType.VarType && type2 is FunkType.VarType -> {
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
            type1 is FunkType.VarType || type2 is FunkType.VarType -> true

            // Both are function types - check structure
            type1 is FunkType.FunctionType && type2 is FunkType.FunctionType -> {
                functionTypesAlphaEquivalent(type1, type2, mapping)
            }

            // Both are record types - check fields
            type1 is FunkType.RecordType && type2 is FunkType.RecordType -> {
                recordTypesAlphaEquivalent(type1, type2, mapping)
            }

            // Both are variant types - check constructors
            type1 is FunkType.VariantType && type2 is FunkType.VariantType -> {
                variantTypesAlphaEquivalent(type1, type2, mapping)
            }

            // Both are type applications - check constructor and args
            type1 is FunkType.TypeApplication && type2 is FunkType.TypeApplication -> {
                type1.constructor == type2.constructor &&
                type1.args.size == type2.args.size &&
                type1.args.zip(type2.args).all { (a, b) -> alphaEquivalent(a, b, mapping) }
            }

            // Both are array types
            type1 is FunkType.ArrayType && type2 is FunkType.ArrayType -> {
                alphaEquivalent(type1.elementType, type2.elementType, mapping)
            }

            // Both are list types
            type1 is FunkType.ListType && type2 is FunkType.ListType -> {
                alphaEquivalent(type1.elementType, type2.elementType, mapping)
            }

            // Both are set types
            type1 is FunkType.SetType && type2 is FunkType.SetType -> {
                alphaEquivalent(type1.elementType, type2.elementType, mapping)
            }

            // Both are map types
            type1 is FunkType.MapType && type2 is FunkType.MapType -> {
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
        func1: FunkType.FunctionType,
        func2: FunkType.FunctionType,
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
        rec1: FunkType.RecordType,
        rec2: FunkType.RecordType,
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
        var1: FunkType.VariantType,
        var2: FunkType.VariantType,
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
     * Note: FunkType.FunctionType has paramType (which is a RecordType) not a list of params.
     */
    private fun functionTypesCompatible(annotated: FunkType.FunctionType, inferred: FunkType.FunctionType): Boolean {
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
    private fun recordTypesCompatible(annotated: FunkType.RecordType, inferred: FunkType.RecordType): Boolean {
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
    private fun variantTypesCompatible(annotated: FunkType.VariantType, inferred: FunkType.VariantType): Boolean {
        // Check that all annotated constructors exist in inferred type
        for ((constructorName, annotatedPayload) in annotated.constructors) {
            val inferredPayload = inferred.constructors[constructorName] ?: return false

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
     * Convert TypeAnnotation to FunkType.
     *
     * This is used to convert user-written type annotations to internal FunkType
     * for comparison with inferred types.
     *
     * We use the existing convertTypeAnnotation function from the AST module.
     */
    private fun convertAnnotationToType(annotation: TypeAnnotation): FunkType {
        return convertTypeAnnotation(annotation)
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
        type: FunkType,
        context: String
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()

        // Check if type is a variant with a constructor that matches a built-in ability
        if (type is FunkType.VariantType) {
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
        scrutineeType: FunkType
    ): ValidationResult {
        val errors = mutableListOf<ValidationError>()

        // CRITICAL FIX: If scrutineeType is a TypeApplication, expand it to VariantType
        // This matches OldTypeChecker behavior where type aliases are expanded
        val expandedType = when (scrutineeType) {
            is FunkType.TypeApplication -> {
                // Look up the type declaration and expand it
                val typeDecl = typeDeclarations[scrutineeType.constructor]
                val definition = typeDecl?.definition
                if (definition is VariantDefinition) {
                    // Build VariantType from definition
                    FunkType.VariantType(
                        constructors = definition.constructors.associate { ctor ->
                            ctor.name to ctor.dataType?.let { convertTypeAnnotation(it) }
                        },
                        rowVar = if (definition.isOpen) RowVar.fresh() else null
                    )
                } else {
                    scrutineeType  // Not a variant, keep as-is
                }
            }
            // BooleanType is internally optimized but behaves like variant for pattern matching
            is FunkType.BooleanType -> FunkType.VariantType(
                constructors = mapOf("True" to null, "False" to null),
                rowVar = null
            )
            else -> scrutineeType
        }

        // Only variant types need exhaustiveness checking
        if (expandedType !is FunkType.VariantType) {
            return ValidationResult.Success()
        }

        // Open variants don't require exhaustiveness checking
        if (expandedType.rowVar != null) {
            return ValidationResult.Success()
        }

        // This is a closed variant - check exhaustiveness
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
                        // Guards make coverage conditional
                        // Matches OldTypeChecker.kt lines 8112-8118
                        if (case.guard == null) {
                            coveredConstructors.add(pattern.constructor)
                        }
                    }
                    is WildcardPattern -> {
                        // Only count as wildcard if there's no guard condition
                        // Matches OldTypeChecker.kt lines 8119-8123
                        if (case.guard == null) {
                            hasWildcard = true
                        }
                    }
                    is VariablePattern -> {
                        // Variable patterns also act as wildcards if no guard
                        if (case.guard == null) {
                            hasWildcard = true
                        }
                    }
                    is LiteralPattern -> {
                        // SPECIAL CASE: Boolean literal patterns cover Bool constructors
                        // Bool is defined as | True, False | but we use true/false literals
                        // So we need to map boolean literal patterns to Bool constructors
                        if (case.guard == null && pattern.value is BooleanLiteralValue) {
                            val boolLit = pattern.value as BooleanLiteralValue
                            val constructorName = if (boolLit.value) "True" else "False"
                            coveredConstructors.add(constructorName)
                        }
                        // Other literal patterns don't cover constructors
                    }
                    is RecordPattern, is ArrayPattern -> {
                        // Other patterns don't cover specific constructors
                    }
                }
            }
        }

        val uncoveredConstructors = allConstructors - coveredConstructors

        if (uncoveredConstructors.isNotEmpty() && !hasWildcard) {
            // Closed variant with missing constructors and no wildcard = error
            // Matches OldTypeChecker.kt lines 8134-8137
            errors.add(
                ValidationError.NonExhaustiveMatch(
                    expression = matchExpr,
                    coveredConstructors = coveredConstructors.toList(),
                    missingConstructors = uncoveredConstructors.toList(),
                    sourceLocation = null
                )
            )
        }

        return if (errors.isEmpty()) {
            ValidationResult.Success()
        } else {
            ValidationResult.Failure(errors)
        }
    }

    /**
     * Validate type application.
     *
     * Ensures type constructor has correct arity.
     */
    fun validateTypeApplication(
        typeApp: FunkType.TypeApplication,
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
        implDecl: ImplAbilityDeclaration,
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
     */
    internal fun findSimilarNames(target: String, candidates: List<String>, maxDistance: Int = 3): List<String> {
        if (candidates.isEmpty()) return emptyList()

        // Calculate Levenshtein distance for each candidate
        val distances = candidates.map { candidate ->
            candidate to levenshteinDistance(target, candidate)
        }

        // Filter candidates within max distance and sort by distance
        return distances
            .filter { (_, distance) -> distance <= maxDistance }
            .sortedBy { (_, distance) -> distance }
            .take(3)
            .map { (name, _) -> name }
    }

    /**
     * Calculate Levenshtein distance between two strings.
     *
     * Measures minimum number of single-character edits (insertions, deletions, substitutions)
     * required to change one string into another.
     */
    private fun levenshteinDistance(s1: String, s2: String): Int {
        val len1 = s1.length
        val len2 = s2.length

        // Create matrix of distances
        val dp = Array(len1 + 1) { IntArray(len2 + 1) }

        // Initialize base cases
        for (i in 0..len1) dp[i][0] = i
        for (j in 0..len2) dp[0][j] = j

        // Calculate distances
        for (i in 1..len1) {
            for (j in 1..len2) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,      // deletion
                    dp[i][j - 1] + 1,      // insertion
                    dp[i - 1][j - 1] + cost // substitution
                )
            }
        }

        return dp[len1][len2]
    }

    /**
     * Get binding name from LetBinding for looking up constraint results.
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
     * Validate a let pattern for exhaustiveness.
     *
     * For record patterns without ellipsis (...), ensures all fields are destructured.
     */
    private fun validateLetPattern(
        pattern: Pattern,
        scrutineeType: FunkType
    ): List<ValidationError> {
        return when (pattern) {
            is RecordPattern -> {
                validateRecordPattern(pattern, scrutineeType)
            }
            is ConstructorPattern -> {
                // Constructor patterns in let bindings can fail to match
                // TODO: Add validation for irrefutable patterns in let bindings
                emptyList()
            }
            is VariablePattern,
            is WildcardPattern,
            is LiteralPattern,
            is ArrayPattern -> {
                // These patterns don't need exhaustiveness checking
                emptyList()
            }
        }
    }

    /**
     * Validate record pattern exhaustiveness.
     *
     * Checks that all fields in the record type are present in the pattern
     * when the pattern is closed (no ... ellipsis).
     */
    private fun validateRecordPattern(
        pattern: RecordPattern,
        scrutineeType: FunkType
    ): List<ValidationError> {
        // If pattern is open (has ...), it's always exhaustive
        if (pattern.isOpen) {
            return emptyList()
        }

        // Only validate if scrutinee is a record type
        val recordType = scrutineeType as? FunkType.RecordType
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
                    sourceLocation = null
                )
            )
        } else {
            emptyList()
        }
    }

    /**
     * Recursively validate patterns in an expression tree.
     *
     * This finds let statements in do-blocks, lambda bodies, etc.
     * and validates their patterns for exhaustiveness.
     */
    private fun validatePatternsInExpression(
        expr: Expression,
        substitution: Substitution
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()

        when (expr) {
            is DoExpression -> {
                // Validate let statements in do-block
                for (stmt in expr.statements) {
                    if (stmt is LetStatement) {
                        // Get the inferred type for the let statement expression
                        val exprType = stmt.expression.inferredType
                        if (exprType != null) {
                            val scrutineeType = substitution.apply(exprType)
                            val patternErrors = validateLetPattern(stmt.pattern, scrutineeType)
                            errors.addAll(patternErrors)
                        }

                        // Recursively validate the expression
                        val nestedErrors = validatePatternsInExpression(stmt.expression, substitution)
                        errors.addAll(nestedErrors)
                    } else if (stmt is ExpressionStatement) {
                        val nestedErrors = validatePatternsInExpression(stmt.expression, substitution)
                        errors.addAll(nestedErrors)
                    }
                }

                // Validate result expression
                val resultErrors = validatePatternsInExpression(expr.result, substitution)
                errors.addAll(resultErrors)
            }

            is LambdaExpression -> {
                // Validate lambda body
                val bodyErrors = validatePatternsInExpression(expr.body, substitution)
                errors.addAll(bodyErrors)
            }

            is IfExpression -> {
                val condErrors = validatePatternsInExpression(expr.condition, substitution)
                val thenErrors = validatePatternsInExpression(expr.thenBranch, substitution)
                val elseErrors = validatePatternsInExpression(expr.elseBranch, substitution)
                errors.addAll(condErrors)
                errors.addAll(thenErrors)
                errors.addAll(elseErrors)
            }

            is FunctionCall -> {
                val funcErrors = validatePatternsInExpression(expr.function, substitution)
                val argsErrors = validatePatternsInExpression(expr.arguments, substitution)
                errors.addAll(funcErrors)
                errors.addAll(argsErrors)
            }

            is RecordLiteral -> {
                for (entry in expr.entries) {
                    if (entry is RecordFieldEntry) {
                        val value = entry.value
                        if (value != null) {
                            val fieldErrors = validatePatternsInExpression(value, substitution)
                            errors.addAll(fieldErrors)
                        }
                    }
                }
            }

            is MatchExpression -> {
                val scrutineeErrors = validatePatternsInExpression(expr.expression, substitution)
                errors.addAll(scrutineeErrors)

                // CRITICAL: Validate match exhaustiveness
                // Get the scrutinee type and check if all constructors are covered
                // FIXED: SubstitutionApplier now maintains a context map (like OldTypeChecker) that tracks
                // identifier types as it traverses the AST. This allows identifiers in nested expressions
                // (like lambda parameters, do-block let bindings) to get their types set properly.
                val scrutineeType = expr.expression.inferredType

                if (scrutineeType != null) {
                    val finalScrutineeType = substitution.apply(scrutineeType)
                    val exhaustivenessResult = validateExhaustiveness(expr, finalScrutineeType)

                    if (exhaustivenessResult is ValidationResult.Failure) {
                        errors.addAll(exhaustivenessResult.errors)
                    }
                }

                for (case in expr.cases) {
                    val caseErrors = validatePatternsInExpression(case.expression, substitution)
                    errors.addAll(caseErrors)
                }
            }

            // Other expression types that may contain sub-expressions
            is VariantConstruction -> {
                // Validate the constructor value first
                val value = expr.value
                if (value != null) {
                    val valueErrors = validatePatternsInExpression(value, substitution)
                    errors.addAll(valueErrors)
                }

                // Validate the constructor itself against its expected variant type
                val constructorErrors = validateVariantConstructor(expr, substitution)
                errors.addAll(constructorErrors)
            }

            is MemberAccess -> {
                val receiverErrors = validatePatternsInExpression(expr.receiver, substitution)
                errors.addAll(receiverErrors)
            }

            is ArrayAccess -> {
                val arrayErrors = validatePatternsInExpression(expr.array, substitution)
                val indexErrors = validatePatternsInExpression(expr.index, substitution)
                errors.addAll(arrayErrors)
                errors.addAll(indexErrors)
            }

            is ArrayLiteral -> {
                for (element in expr.elements) {
                    val elemErrors = validatePatternsInExpression(element, substitution)
                    errors.addAll(elemErrors)
                }
            }

            is RecordUpdate -> {
                val baseErrors = validatePatternsInExpression(expr.record, substitution)
                errors.addAll(baseErrors)

                for (update in expr.updates) {
                    val value = update.value
                    if (value != null) {
                        val updateErrors = validatePatternsInExpression(value, substitution)
                        errors.addAll(updateErrors)
                    }
                }
            }

            is StringLiteral -> {
                if (expr.isInterpolated && expr.parts != null) {
                    for (part in expr.parts) {
                        if (part is ExpressionPart) {
                            val partErrors = validatePatternsInExpression(part.expression, substitution)
                            errors.addAll(partErrors)
                        }
                    }
                }
            }

            // Leaf nodes - no sub-expressions to validate
            is Identifier,
            is IntLiteral,
            is NatLiteral,
            is FloatLiteral,
            is FractionLiteral,
            is BooleanLiteral,
            is PlaceholderExpression,
            is RecordUpdateExpression,
            is PerformExpression,
            is EffectCallExpression,
            is StateMachineExpression -> {
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
        // Get the inferred type for the expression
        val inferredType = variantExpr.inferredType

        if (System.getProperty("funk.debug.validation") == "true") {
            println("[DEBUG validateVariantConstructor] constructor=${variantExpr.constructor}, inferredType=$inferredType")
        }

        if (inferredType == null) {
            return emptyList()  // No type yet, skip validation
        }

        // Apply substitution to get final type
        val finalType = substitution.apply(inferredType)

        if (System.getProperty("funk.debug.validation") == "true") {
            println("[DEBUG validateVariantConstructor] finalType=$finalType")
        }

        // Only validate if the final type is a VariantType
        val variantType = finalType as? FunkType.VariantType
        if (variantType == null) {
            if (System.getProperty("funk.debug.validation") == "true") {
                println("[DEBUG validateVariantConstructor] finalType is not VariantType, skipping")
            }
            return emptyList()
        }

        // Get the constructor name
        val constructorName = variantExpr.constructor

        if (System.getProperty("funk.debug.validation") == "true") {
            println("[DEBUG validateVariantConstructor] variantType.constructors=${variantType.constructors.keys}")
            println("[DEBUG validateVariantConstructor] variantType.rowVar=${variantType.rowVar}")
        }

        // Check if constructor exists in the variant type
        if (!variantType.constructors.containsKey(constructorName)) {
            // For CLOSED variants (rowVar == null), this is an error
            if (variantType.rowVar == null) {
                val validConstructors = variantType.constructors.keys.sorted()

                if (System.getProperty("funk.debug.validation") == "true") {
                    println("[DEBUG validateVariantConstructor] ADDING ERROR for constructor $constructorName not in $validConstructors")
                }

                return listOf(
                    ValidationError.ConstructorNotFound(
                        variantType = variantType,
                        constructorName = constructorName,
                        availableConstructors = validConstructors,
                        sourceLocation = null
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
     * This is a workaround for the fact that SubstitutionApplier doesn't set
     * inferredType on nested expressions. We look at the constructor patterns
     * and find the type declaration that contains those constructors.
     */
    private fun inferScrutineeTypeFromPatterns(
        matchExpr: MatchExpression,
        typeDecls: Map<String, TypeDeclaration>
    ): FunkType? {
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
            if (definition is VariantDefinition) {
                // Check if this variant has the constructors we're looking for
                val variantConstructors = definition.constructors.map { it.name }.toSet()
                if (constructorNames.all { it in variantConstructors }) {
                    // Found it! Build the VariantType
                    return FunkType.VariantType(
                        constructors = definition.constructors.associate { ctor ->
                            ctor.name to ctor.dataType?.let { convertTypeAnnotation(it) }
                        },
                        rowVar = if (definition.isOpen) RowVar.fresh() else null
                    )
                }
            }
        }

        return null  // Couldn't infer type
    }
}
