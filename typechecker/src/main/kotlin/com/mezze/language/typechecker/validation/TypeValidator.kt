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
    private val typeDeclarations: Map<String, TypeDeclaration> = emptyMap(),
    private val effectDeclarations: Map<String, EffectDeclaration> = emptyMap(),
    private val implDeclarations: Map<String, ImplDeclaration> = emptyMap()
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

        // Validate top-level effect restrictions:
        // 1. Top-level VALUES cannot have <*> (performed effects)
        // 2. Top-level FUNCTIONS can return <*> (they're just definitions)
        // 3. 'main' must have type {} -> {} <*>
        // Note: TopLevelExpression items are NOT validated for <*> because:
        //   - They're primarily used in tests/REPL to evaluate expressions
        //   - Real programs shouldn't have bare expressions at module level
        //   - The critical case (let result = ...) is already caught by LetBinding validation
        for (item in program.items) {
            when (item) {
                is LetBinding -> {
                    val topLevelErrors = validateTopLevelEffects(item, substitution, constraintResults)
                    errors.addAll(topLevelErrors)
                }
                else -> {
                    // TopLevelExpression and other items don't need effect restrictions
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
                    // Find corresponding effect declaration using O(1) map lookup
                    val effectDecl = effectDeclarations[item.effect.localName]

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

        // Validate perform expressions (handler type compatibility)
        // Use the pre-built maps passed to constructor for O(1) lookups
        // Build map of function definitions for looking up function bodies
        val functionDefinitions = buildFunctionDefinitions(program)
        for (item in program.items) {
            val performErrors = when (item) {
                is LetBinding -> validatePerformExpressionsInExpression(
                    item.expression,
                    implDeclarations,
                    effectDeclarations,
                    functionDefinitions
                )
                is TopLevelExpression -> validatePerformExpressionsInExpression(
                    item.expression,
                    implDeclarations,
                    effectDeclarations,
                    functionDefinitions
                )
                else -> emptyList()
            }
            errors.addAll(performErrors)
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
                    val type = substitution.apply(MezzeType.VarType(constraint.typeVar))
                    // Apply substitution to typeArgs as well for multi-parameter abilities
                    val resolvedTypeArgs = constraint.typeArgs.map { substitution.apply(it) }
                    val result = abilityResolver.checkAbility(
                        type,
                        constraint.abilityName,
                        resolvedTypeArgs
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
                            if (definition is VariantDefinition) {
                                // Build VariantType from definition
                                MezzeType.VariantType(
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
     * Example: Show.show {val = 42} generates AbilityRequired(typeVar='a, abilityName=Show)
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
                concreteTypes.zip(annotations).all { (type, annotation) ->
                    typeMatchesAnnotation(type, annotation)
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

            // Check if this is an auto-derivable Encode/Decode ability
            // These are automatically generated by CodecGenerator for serializable types
            if ((abilityName == "Encode" || abilityName == "Decode") &&
                codecGenerator.isSerializable(concreteType)) {
                // Check if there's an EncodeFormat/DecodeFormat impl in scope
                val formatAbility = if (abilityName == "Encode") "EncodeFormat" else "DecodeFormat"
                val hasFormatImpl = context.getAllAbilityImpls().any { it.value.abilityName == formatAbility }
                if (hasFormatImpl) {
                    // This will be auto-derived by SubstitutionApplier/CodecGenerator
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
            if (definition is RecordDefinition) {
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
            if (definition is VariantDefinition) {
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
        typeDecl: TypeDeclaration,
        definition: VariantDefinition
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
            extractTypeParamMappings(dataTypeAnnotation, concretePayload, typeDecl.typeParams, typeArgMap)
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
                } else if (concreteType is MezzeType.SeqType && annotation.constructor == "Seq") {
                    // Handle Seq type specially
                    annotation.args.firstOrNull()?.let { elementAnnotation ->
                        extractTypeParamMappings(elementAnnotation, concreteType.elementType, typeParams, mappings)
                    }
                } else if (concreteType is MezzeType.ListType && annotation.constructor == "List") {
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
     * Check if this is a built-in ability with built-in implementation.
     */
    private fun isBuiltInAbilityImplementation(abilityName: String, type: MezzeType): Boolean {
        // Unwrap SuspendedType to check the inner value type
        var unwrappedType = if (type is MezzeType.SuspendedType) type.valueType else type

        // Expand TypeApplication to VariantType if possible
        // This allows deriving Eq/Ord for named variant types like Option, Result, etc.
        if (unwrappedType is MezzeType.TypeApplication) {
            val expanded = expandTypeApplicationToVariant(unwrappedType, typeDeclarations)
            if (expanded != null) {
                unwrappedType = expanded
            }
        }

        // Built-in abilities have implicit implementations for certain types
        return when (abilityName) {
            // Numeric abilities - implemented for Int, Float, Nat, Frac
            "NumAdd", "NumSub", "NumMul", "NumDiv", "NumMod", "NumNeg" -> {
                unwrappedType is MezzeType.IntType ||
                unwrappedType is MezzeType.FloatType ||
                unwrappedType is MezzeType.NatType ||
                unwrappedType is MezzeType.FracType
            }

            // Equality - implemented for most primitive types and derivable for records/variants/lists
            "Eq" -> {
                unwrappedType is MezzeType.IntType ||
                unwrappedType is MezzeType.FloatType ||
                unwrappedType is MezzeType.StringType ||
                unwrappedType is MezzeType.BoolType ||
                unwrappedType is MezzeType.NatType ||
                unwrappedType is MezzeType.FracType ||
                unwrappedType is MezzeType.OrderingType ||
                unwrappedType is MezzeType.BytesType ||
                unwrappedType is MezzeType.ByteBufferType ||
                // Type variables are assumed to have Eq - they're constrained at the call site
                unwrappedType is MezzeType.VarType ||
                isBoolVariant(unwrappedType) ||
                // Derive Eq for records if all fields have Eq
                (unwrappedType is MezzeType.RecordType && unwrappedType.fields.values.all { isBuiltInAbilityImplementation("Eq", it) }) ||
                // Derive Eq for variants if all constructor payloads have Eq (or no payload)
                (unwrappedType is MezzeType.VariantType && unwrappedType.constructors.values.all { it == null || isBuiltInAbilityImplementation("Eq", it) }) ||
                // Derive Eq for lists if element type has Eq
                (unwrappedType is MezzeType.ListType && isBuiltInAbilityImplementation("Eq", unwrappedType.elementType)) ||
                // Derive Eq for seqs if element type has Eq
                (unwrappedType is MezzeType.SeqType && isBuiltInAbilityImplementation("Eq", unwrappedType.elementType))
            }

            // Ordering - implemented for comparable types and derivable for records/variants/lists
            "Ord" -> {
                unwrappedType is MezzeType.IntType ||
                unwrappedType is MezzeType.FloatType ||
                unwrappedType is MezzeType.StringType ||
                unwrappedType is MezzeType.NatType ||
                unwrappedType is MezzeType.FracType ||
                // Type variables are assumed to have Ord - they're constrained at the call site
                unwrappedType is MezzeType.VarType ||
                // Derive Ord for records if all fields have Ord
                (unwrappedType is MezzeType.RecordType && unwrappedType.fields.values.all { isBuiltInAbilityImplementation("Ord", it) }) ||
                // Derive Ord for variants if all constructor payloads have Ord (or no payload)
                (unwrappedType is MezzeType.VariantType && unwrappedType.constructors.values.all { it == null || isBuiltInAbilityImplementation("Ord", it) }) ||
                // Derive Ord for lists if element type has Ord
                (unwrappedType is MezzeType.ListType && isBuiltInAbilityImplementation("Ord", unwrappedType.elementType)) ||
                // Derive Ord for seqs if element type has Ord
                (unwrappedType is MezzeType.SeqType && isBuiltInAbilityImplementation("Ord", unwrappedType.elementType))
            }

            // Inspect - built-in ability for displaying values (to_str)
            // CRITICAL: Inspect is universally available for ALL types (per OldTypeChecker line 2806-2807)
            // This includes newtypes, records, variants, functions, etc.
            // Note: "Show" is NOT a built-in ability - it's commonly used as a user-defined ability name
            "Inspect" -> true

            // Logical operations - only for Bool
            "LogicalAnd", "LogicalOr", "LogicalNot" -> {
                unwrappedType is MezzeType.BoolType || isBoolVariant(unwrappedType)
            }

            else -> false
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

        // Try to convert annotation to MezzeType
        // If conversion fails, skip validation (annotation might use unsupported syntax)
        val annotatedType = try {
            convertAnnotationToType(annotation)
        } catch (e: Exception) {
            // Skip validation if annotation can't be converted
            // This can happen with seq annotations or other edge cases
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
     * Validate top-level effect restrictions.
     *
     * Note: We intentionally do NOT restrict:
     * 1. Top-level values from having <*> - useful for tests and scripts
     * 2. The 'main' function type - validation happens at runtime when executing
     *
     * The runtime/CLI should validate main type before execution.
     * This keeps type-checking working for tests and library code that use
     * 'main' as a regular binding name.
     */
    private fun validateTopLevelEffects(
        binding: LetBinding,
        substitution: Substitution,
        constraintResults: Map<String, ConstraintResult>
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()

        // Skip 'main' - it has separate validation via validateMainForExecution
        if (binding.isSimpleBinding && binding.variableName == "main") {
            return emptyList()
        }

        val inferredType = binding.inferredType ?: return emptyList()
        val finalType = substitution.apply(inferredType)

        // Check if this is a value (not a function) with performed effects
        // Functions are allowed to have <*> because they're just definitions
        // Values with <*> would mean effects executed at module load time
        when (finalType) {
            is MezzeType.FunctionType -> {
                // Functions can have <*> in their return type - that's fine
                // They're just definitions, execution happens when called
            }
            is MezzeType.SuspendedType -> {
                // SuspendedType wraps a value with its effects
                // If it has performed=true, that means effects were executed
                if (finalType.effects.performed) {
                    errors.add(
                        ValidationError.TopLevelPerformedEffects(
                            bindingName = binding.variableName,
                            effectRow = finalType.effects,
                            sourceLocation = binding.expression.sourceLocation
                        )
                    )
                }
            }
            else -> {
                // For non-SuspendedType, non-FunctionType values, we DON'T validate
                // based on ConstraintResult.performed because:
                // 1. Direct `perform` expressions at top-level are a common test pattern
                // 2. The perform is explicit and visible in the code
                // 3. The truly problematic pattern (calling functions with <*>) is caught
                //    by the SuspendedType check above
                //
                // The key patterns we want to catch are:
                // - let result = myFunc {} where myFunc returns T <*>
                // - let result = ({} -> perform H in e) {}
                // Both produce SuspendedType and are caught above.
            }
        }

        return errors
    }

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
        substitution: Substitution
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()

        val inferredType = topLevelExpr.expression.inferredType ?: return emptyList()
        val finalType = substitution.apply(inferredType)

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
        substitution: Substitution
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()

        for (item in program.items) {
            if (item is TopLevelExpression) {
                errors.addAll(validateTopLevelExpressionEffects(item, substitution))
            }
        }

        return errors
    }

    /**
     * Validate the main function for execution.
     *
     * Call this AFTER type-checking when you want to run the program as an executable.
     * This validates that main exists and has type {} -> {} <*>.
     *
     * @param program The type-checked program
     * @param substitution The substitution from constraint solving
     * @return ValidationError if main is invalid, null if valid
     */
    fun validateMainForExecution(
        program: Program,
        substitution: Substitution,
        letBindings: Map<String, LetBinding>? = null
    ): ValidationError? {
        // Find the main binding using O(1) map lookup if available, else scan
        val mainBinding = letBindings?.get("main")
            ?: program.items.filterIsInstance<LetBinding>()
                .find { binding ->
                    binding.isSimpleBinding && binding.variableName == "main"
                }

        if (mainBinding == null) {
            return ValidationError.MainFunctionTypeError(
                actualType = MezzeType.RecordType(emptyMap()),  // placeholder
                reason = "no 'main' function found. Define main = {} -> perform Handler in ...",
                sourceLocation = null
            )
        }

        val inferredType = mainBinding.inferredType ?: return null
        val finalType = substitution.apply(inferredType)

        return validateMainType(finalType, mainBinding.expression.sourceLocation)
    }

    /**
     * Validate that main has the correct type: {} -> {} <*>
     */
    private fun validateMainType(type: MezzeType, sourceLocation: SourceLocation?): ValidationError? {
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
    private fun convertAnnotationToType(annotation: TypeAnnotation): MezzeType {
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
     * Delegates to shared StringDistance utility.
     */
    internal fun findSimilarNames(target: String, candidates: List<String>, maxDistance: Int = 3): List<String> {
        return StringDistance.findSimilar(target, candidates, maxDistance, maxResults = 3)
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
        scrutineeType: MezzeType
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
            is SeqPattern,
            is StringInterpolationPattern -> {
                // These patterns don't need exhaustiveness checking here
                // (StringInterpolationPattern is refutable, but that's checked elsewhere)
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

                // Validate match exhaustiveness
                // Get the scrutinee type and check if all constructors are covered
                // SubstitutionApplier maintains a context map that tracks identifier types as it
                // traverses the AST, allowing identifiers in nested expressions to get their types set.
                val scrutineeType = expr.expression.inferredType

                if (scrutineeType != null) {
                    val finalScrutineeType = substitution.apply(scrutineeType)
                    val exhaustivenessResult = validateExhaustiveness(expr, finalScrutineeType)

                    if (exhaustivenessResult is ValidationResult.Failure) {
                        errors.addAll(exhaustivenessResult.errors)
                    }
                }

                // Check for unreachable patterns (applies to all match expressions, not just variants)
                val unreachableResult = validateUnreachablePatterns(expr)
                if (unreachableResult is ValidationResult.Failure) {
                    errors.addAll(unreachableResult.errors)
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

            is SeqLiteral -> {
                for (element in expr.elements) {
                    val elementExpr = when (element) {
                        is SeqRegularElement -> element.expression
                        is SeqSpreadElement -> element.expression
                    }
                    val elemErrors = validatePatternsInExpression(elementExpr, substitution)
                    errors.addAll(elemErrors)
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
            is PerformExpression,
            is EffectCallExpression,
            is StateMachineExpression -> {
                // No patterns to validate in these
            }

            // Parenthesized - recurse into inner
            is ParenthesizedExpression -> {
                errors.addAll(validatePatternsInExpression(expr.expression, substitution))
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
     * This is a workaround for the fact that SubstitutionApplier doesn't set
     * inferredType on nested expressions. We look at the constructor patterns
     * and find the type declaration that contains those constructors.
     */
    private fun inferScrutineeTypeFromPatterns(
        matchExpr: MatchExpression,
        typeDecls: Map<String, TypeDeclaration>
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
            if (definition is VariantDefinition) {
                // Check if this variant has the constructors we're looking for
                val variantConstructors = definition.constructors.map { it.name }.toSet()
                if (constructorNames.all { it in variantConstructors }) {
                    // Found it! Build the VariantType
                    return MezzeType.VariantType(
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

    // ========================================================================
    // PERFORM EXPRESSION VALIDATION
    // ========================================================================

    /**
     * Build a map of function definitions from the program.
     * This maps function names to their lambda bodies.
     */
    private fun buildFunctionDefinitions(program: Program): Map<String, LambdaExpression> {
        val result = mutableMapOf<String, LambdaExpression>()
        for (item in program.items) {
            if (item is LetBinding && item.isSimpleBinding && item.expression is LambdaExpression) {
                result[item.variableName] = item.expression as LambdaExpression
            }
        }
        return result
    }

    /**
     * Validate perform expressions in an expression tree.
     *
     * This checks that handler associated types match what the expression throws.
     */
    private fun validatePerformExpressionsInExpression(
        expr: Expression,
        implDeclarations: Map<String, ImplDeclaration>,
        effectDeclarations: Map<String, EffectDeclaration>,
        functionDefinitions: Map<String, LambdaExpression>
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()

        when (expr) {
            is PerformExpression -> {
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
                    expr.expression,
                    implDeclarations,
                    effectDeclarations,
                    functionDefinitions
                )
                errors.addAll(innerErrors)
            }

            is DoExpression -> {
                for (stmt in expr.statements) {
                    when (stmt) {
                        is LetStatement -> {
                            val stmtErrors = validatePerformExpressionsInExpression(
                                stmt.expression,
                                implDeclarations,
                                effectDeclarations,
                                functionDefinitions
                            )
                            errors.addAll(stmtErrors)
                        }
                        is ExpressionStatement -> {
                            val stmtErrors = validatePerformExpressionsInExpression(
                                stmt.expression,
                                implDeclarations,
                                effectDeclarations,
                                functionDefinitions
                            )
                            errors.addAll(stmtErrors)
                        }
                    }
                }
                val resultErrors = validatePerformExpressionsInExpression(
                    expr.result,
                    implDeclarations,
                    effectDeclarations,
                    functionDefinitions
                )
                errors.addAll(resultErrors)
            }

            is LambdaExpression -> {
                val bodyErrors = validatePerformExpressionsInExpression(
                    expr.body,
                    implDeclarations,
                    effectDeclarations,
                    functionDefinitions
                )
                errors.addAll(bodyErrors)
            }

            is IfExpression -> {
                errors.addAll(validatePerformExpressionsInExpression(expr.condition, implDeclarations, effectDeclarations, functionDefinitions))
                errors.addAll(validatePerformExpressionsInExpression(expr.thenBranch, implDeclarations, effectDeclarations, functionDefinitions))
                errors.addAll(validatePerformExpressionsInExpression(expr.elseBranch, implDeclarations, effectDeclarations, functionDefinitions))
            }

            is FunctionCall -> {
                errors.addAll(validatePerformExpressionsInExpression(expr.function, implDeclarations, effectDeclarations, functionDefinitions))
                errors.addAll(validatePerformExpressionsInExpression(expr.arguments, implDeclarations, effectDeclarations, functionDefinitions))
            }

            is RecordLiteral -> {
                for (entry in expr.entries) {
                    when (entry) {
                        is RecordFieldEntry -> {
                            entry.value?.let { value ->
                                errors.addAll(validatePerformExpressionsInExpression(value, implDeclarations, effectDeclarations, functionDefinitions))
                            }
                        }
                        is RecordSpreadEntry -> {
                            errors.addAll(validatePerformExpressionsInExpression(entry.expression, implDeclarations, effectDeclarations, functionDefinitions))
                        }
                    }
                }
            }

            is MatchExpression -> {
                errors.addAll(validatePerformExpressionsInExpression(expr.expression, implDeclarations, effectDeclarations, functionDefinitions))
                for (case in expr.cases) {
                    case.guard?.let { guard ->
                        errors.addAll(validatePerformExpressionsInExpression(guard, implDeclarations, effectDeclarations, functionDefinitions))
                    }
                    errors.addAll(validatePerformExpressionsInExpression(case.expression, implDeclarations, effectDeclarations, functionDefinitions))
                }
            }

            is VariantConstruction -> {
                expr.value?.let { value ->
                    errors.addAll(validatePerformExpressionsInExpression(value, implDeclarations, effectDeclarations, functionDefinitions))
                }
            }

            is MemberAccess -> {
                errors.addAll(validatePerformExpressionsInExpression(expr.receiver, implDeclarations, effectDeclarations, functionDefinitions))
            }

            is SeqLiteral -> {
                for (element in expr.elements) {
                    val elementExpr = when (element) {
                        is SeqRegularElement -> element.expression
                        is SeqSpreadElement -> element.expression
                    }
                    errors.addAll(validatePerformExpressionsInExpression(elementExpr, implDeclarations, effectDeclarations, functionDefinitions))
                }
            }

            is StringLiteral -> {
                expr.parts?.forEach { part ->
                    if (part is ExpressionPart) {
                        errors.addAll(validatePerformExpressionsInExpression(part.expression, implDeclarations, effectDeclarations, functionDefinitions))
                    }
                }
            }

            // Leaf nodes
            is Identifier,
            is IntLiteral,
            is NatLiteral,
            is FloatLiteral,
            is FractionLiteral,
            is BooleanLiteral,
            is PlaceholderExpression,
            is EffectCallExpression,
            is StateMachineExpression -> {
                // No sub-expressions to validate
            }

            // Parenthesized - recurse into inner
            is ParenthesizedExpression -> {
                errors.addAll(validatePerformExpressionsInExpression(expr.expression, implDeclarations, effectDeclarations, functionDefinitions))
            }
        }

        return errors
    }

    /**
     * Validate a single perform expression.
     *
     * Checks that each handler's associated type bindings are compatible
     * with what the expression actually throws.
     */
    private fun validatePerformExpression(
        expr: PerformExpression,
        implDeclarations: Map<String, ImplDeclaration>,
        effectDeclarations: Map<String, EffectDeclaration>,
        functionDefinitions: Map<String, LambdaExpression>
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()

        // Collect thrown error types from the inner expression
        val thrownTypes = collectThrownErrorTypes(expr.expression, effectDeclarations, functionDefinitions)

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
                // Handler has no associated type bindings - nothing to check
                continue
            }

            // Get thrown types for this effect
            val thrownForEffect = thrownTypes[effectName] ?: continue

            // Check each associated type binding
            for ((typeName, handlerTypeAnnotation) in implDecl.associatedTypeBindings) {
                val thrownType = thrownForEffect[typeName]

                if (thrownType == null) {
                    // No thrown type inferred for this binding - skip
                    continue
                }

                // Convert handler's type annotation to MezzeType
                // If it's a user-defined type, expand it using the type declarations
                val handlerType = expandTypeAnnotation(handlerTypeAnnotation)

                // Check compatibility
                val compatible = areAssociatedTypesCompatible(handlerType, thrownType)

                if (!compatible) {
                    errors.add(
                        ValidationError.HandlerTypeMismatch(
                            handlerName = handlerName,
                            effectName = effectName,
                            expectedType = handlerType,
                            actualType = thrownType,
                            sourceLocation = expr.sourceLocation
                        )
                    )
                }
            }
        }

        return errors
    }

    /**
     * Collect thrown error types from an expression.
     *
     * Returns a map from effect name to a map of associated type name to thrown type.
     */
    private fun collectThrownErrorTypes(
        expr: Expression,
        effectDeclarations: Map<String, EffectDeclaration>,
        functionDefinitions: Map<String, LambdaExpression>
    ): Map<String, Map<String, MezzeType>> {
        val result = mutableMapOf<String, MutableMap<String, MezzeType>>()
        // Track visited functions to avoid infinite recursion
        val visitedFunctions = mutableSetOf<String>()

        fun traverse(e: Expression) {
            when (e) {
                is EffectCallExpression -> {
                    val effectName = e.operation.qualifier?.fullPath ?: ""
                    val operationName = e.operation.localName

                    // Check if this effect has associated types
                    val effectDecl = effectDeclarations[effectName]
                    if (effectDecl != null && effectDecl.associatedTypes.isNotEmpty()) {
                        // For Exception effect, look for 'throw' operation
                        if (effectName == "Exception" && operationName == "throw") {
                            extractThrownErrorType(e.arguments)?.let { errorType ->
                                val effectMap = result.getOrPut(effectName) { mutableMapOf() }
                                unifyThrownType(effectMap, "'error", errorType)
                            }
                        }
                    }
                }

                is FunctionCall -> {
                    // Check if this is an effect call (Effect.operation {args})
                    if (e.function is MemberAccess) {
                        val memberAccess = e.function as MemberAccess
                        val operationName = memberAccess.member

                        val effectName = when (val receiver = memberAccess.receiver) {
                            is Identifier -> receiver.localName
                            is VariantConstruction -> receiver.constructor
                            else -> null
                        }


                        if (effectName != null) {
                            val effectDecl = effectDeclarations[effectName]
                            if (effectDecl != null && effectDecl.associatedTypes.isNotEmpty()) {
                                if (effectName == "Exception" && operationName == "throw") {
                                    val errorType = extractThrownErrorType(e.arguments)
                                    errorType?.let {
                                        val effectMap = result.getOrPut(effectName) { mutableMapOf() }
                                        unifyThrownType(effectMap, "'error", it)
                                    }
                                }
                            }
                        }
                    }

                    // If the function being called is an Identifier, look up its definition and traverse the body
                    if (e.function is Identifier) {
                        val funcName = (e.function as Identifier).localName
                        if (!visitedFunctions.contains(funcName)) {
                            val funcDef = functionDefinitions[funcName]
                            if (funcDef != null) {
                                visitedFunctions.add(funcName)
                                traverse(funcDef.body)
                            }
                        }
                    }

                    // Recurse into function and arguments
                    traverse(e.function)
                    traverse(e.arguments)
                }

                is DoExpression -> {
                    for (stmt in e.statements) {
                        when (stmt) {
                            is LetStatement -> traverse(stmt.expression)
                            is ExpressionStatement -> traverse(stmt.expression)
                        }
                    }
                    traverse(e.result)
                }

                is IfExpression -> {
                    traverse(e.condition)
                    traverse(e.thenBranch)
                    traverse(e.elseBranch)
                }

                is MatchExpression -> {
                    traverse(e.expression)
                    for (case in e.cases) {
                        traverse(case.expression)
                    }
                }

                is LambdaExpression -> traverse(e.body)

                is RecordLiteral -> {
                    for (entry in e.entries) {
                        when (entry) {
                            is RecordFieldEntry -> entry.value?.let { traverse(it) }
                            is RecordSpreadEntry -> traverse(entry.expression)
                        }
                    }
                }

                is VariantConstruction -> e.value?.let { traverse(it) }
                is MemberAccess -> traverse(e.receiver)
                is SeqLiteral -> e.elements.forEach { element ->
                    when (element) {
                        is SeqRegularElement -> traverse(element.expression)
                        is SeqSpreadElement -> traverse(element.expression)
                    }
                }
                is StringLiteral -> {
                    e.parts?.forEach { part ->
                        if (part is ExpressionPart) traverse(part.expression)
                    }
                }
                is PerformExpression -> {
                    // CRITICAL: Do NOT traverse into nested perform expressions!
                    // The nested perform has its own handler that catches errors from its inner expression.
                    // We only care about errors that escape to the OUTER perform block.
                    // So we just skip the entire PerformExpression - any errors inside are handled.
                }

                // Leaf nodes
                is Identifier,
                is IntLiteral,
                is NatLiteral,
                is FloatLiteral,
                is FractionLiteral,
                is BooleanLiteral,
                is PlaceholderExpression,
                is EffectCallExpression,
                is StateMachineExpression -> { }

                // Parenthesized - recurse into inner
                is ParenthesizedExpression -> traverse(e.expression)
            }
        }

        traverse(expr)
        return result
    }

    /**
     * Extract the error type from a throw call's arguments.
     */
    private fun extractThrownErrorType(args: Expression): MezzeType? {
        if (args !is RecordLiteral) {
            return null
        }

        val errorEntry = args.entries.filterIsInstance<RecordFieldEntry>()
            .find { it.name == "error" }

        val errorValue = errorEntry?.value ?: return null

        // If inferredType is already set, use it
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
     * This is used for expressions inside function definitions that haven't been
     * processed by SubstitutionApplier yet.
     */
    private fun constructTypeFromExpression(expr: Expression?): MezzeType? {
        if (expr == null) return null

        // If inferredType is available, use it
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
                val payloadType = constructTypeFromExpression(expr.value)
                MezzeType.VariantType(
                    constructors = mapOf(expr.constructor to payloadType),
                    rowVar = RowVar.fresh()
                )
            }
            is StringLiteral -> MezzeType.StringType
            is IntLiteral -> MezzeType.IntType
            is BooleanLiteral -> MezzeType.BoolType
            is FloatLiteral -> MezzeType.FloatType
            is Identifier -> {
                // For identifiers, we don't know the type without context
                // Return a fresh type variable
                MezzeType.VarType(TypeVar.fresh())
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
    private fun expandTypeAnnotation(annotation: TypeAnnotation): MezzeType {
        when (annotation) {
            is IdentifierTypeAnnotation -> {
                val name = annotation.name
                // Check if it's a primitive type
                when (name) {
                    "Int" -> return MezzeType.IntType
                    "Float" -> return MezzeType.FloatType
                    "String" -> return MezzeType.StringType
                    "Boolean", "Bool" -> return MezzeType.BoolType
                    "Nat" -> return MezzeType.NatType
                    "Frac" -> return MezzeType.FracType
                    "Ordering" -> return MezzeType.OrderingType
                }

                // Check if it's a user-defined type
                val typeDecl = typeDeclarations[name]
                if (typeDecl != null) {
                    val definition = typeDecl.definition
                    return when (definition) {
                        is VariantDefinition -> {
                            // Build VariantType from definition
                            MezzeType.VariantType(
                                constructors = definition.constructors.associate { ctor ->
                                    ctor.name to ctor.dataType?.let { convertTypeAnnotation(it) }
                                },
                                rowVar = if (definition.isOpen) RowVar.fresh() else null
                            )
                        }
                        is RecordDefinition -> {
                            // Build RecordType from definition
                            MezzeType.RecordType(
                                fields = definition.fields.associate { field ->
                                    field.name to convertTypeAnnotation(field.type)
                                },
                                rowVar = null
                            )
                        }
                        is NewtypeDefinition -> {
                            // Expand newtypes to their underlying type
                            convertTypeAnnotation(definition.wrappedType)
                        }
                    }
                }

                // Fall back to type variable if not found
                return MezzeType.VarType(TypeVar(name))
            }
            is TypeApplicationAnnotation -> {
                // For type applications like `List Int`, expand the constructor
                val constructorName = annotation.constructor
                val typeDecl = typeDeclarations[constructorName]
                if (typeDecl != null) {
                    // TODO: handle type arguments properly
                    return expandTypeAnnotation(IdentifierTypeAnnotation(constructorName))
                }
                return convertTypeAnnotation(annotation)
            }
            else -> {
                return convertTypeAnnotation(annotation)
            }
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

        // For variant types, check if handler accepts all expr variants
        if (handlerType is MezzeType.VariantType && exprType is MezzeType.VariantType) {
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
     * Check if a concrete MezzeType matches a TypeAnnotation structurally.
     * Used for ability implementation matching.
     */
    private fun typeMatchesAnnotation(type: MezzeType, annotation: TypeAnnotation): Boolean {
        return when (annotation) {
            is IdentifierTypeAnnotation -> {
                when (annotation.name) {
                    "Int" -> type is MezzeType.IntType
                    "Float" -> type is MezzeType.FloatType
                    "String" -> type is MezzeType.StringType
                    "Bool" -> type is MezzeType.BoolType
                    "Nat" -> type is MezzeType.NatType
                    "Frac" -> type is MezzeType.FracType
                    "Char" -> type is MezzeType.CharType
                    "Bytes" -> type is MezzeType.BytesType
                    "ByteBuffer" -> type is MezzeType.ByteBufferType
                    "Ordering" -> type is MezzeType.OrderingType
                    else -> {
                        // Check by type name
                        when (type) {
                            is MezzeType.RecordType -> type.typeName == annotation.name
                            is MezzeType.VariantType -> type.typeName == annotation.name
                            is MezzeType.TypeApplication -> type.constructor == annotation.name
                            else -> false
                        }
                    }
                }
            }
            is SeqTypeAnnotation -> {
                type is MezzeType.SeqType && typeMatchesAnnotation(type.elementType, annotation.elementType)
            }
            is TypeApplicationAnnotation -> {
                when {
                    type is MezzeType.TypeApplication && type.constructor == annotation.constructor -> {
                        type.args.size == annotation.args.size &&
                        type.args.zip(annotation.args).all { (argType, argAnnotation) ->
                            typeMatchesAnnotation(argType, argAnnotation)
                        }
                    }
                    type is MezzeType.SeqType && annotation.constructor == "Seq" -> {
                        annotation.args.size == 1 && typeMatchesAnnotation(type.elementType, annotation.args[0])
                    }
                    type is MezzeType.ListType && annotation.constructor == "List" -> {
                        annotation.args.size == 1 && typeMatchesAnnotation(type.elementType, annotation.args[0])
                    }
                    type is MezzeType.SetType && annotation.constructor == "Set" -> {
                        annotation.args.size == 1 && typeMatchesAnnotation(type.elementType, annotation.args[0])
                    }
                    type is MezzeType.MapType && annotation.constructor == "Map" -> {
                        annotation.args.size == 2 &&
                        typeMatchesAnnotation(type.keyType, annotation.args[0]) &&
                        typeMatchesAnnotation(type.valueType, annotation.args[1])
                    }
                    else -> false
                }
            }
            is RecordTypeAnnotation -> {
                if (type !is MezzeType.RecordType) return false
                if (type.fields.size != annotation.entries.size) return false
                annotation.entries.all { entry ->
                    val fieldType = type.fields[entry.name] ?: return false
                    typeMatchesAnnotation(fieldType, entry.type)
                }
            }
            is VariantTypeAnnotation -> {
                if (type !is MezzeType.VariantType) return false
                annotation.constructors.all { constructor ->
                    val concretePayload = type.constructors[constructor.name]
                    val dataType = constructor.dataType
                    if (dataType == null) {
                        concretePayload == null
                    } else if (concretePayload == null) {
                        false
                    } else {
                        typeMatchesAnnotation(concretePayload, dataType)
                    }
                }
            }
            else -> false
        }
    }
}
