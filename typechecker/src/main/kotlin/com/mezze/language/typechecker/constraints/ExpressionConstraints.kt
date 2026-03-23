package com.mezze.language.typechecker.constraints

import com.mezze.language.ast.*
import com.mezze.language.typechecker.abilities.BuiltInAbilities
import com.mezze.language.typechecker.context.ContextBuilder
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.dependencies.DefinitionRef
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.typechecker.SpreadSyntaxError
import com.mezze.language.typechecker.debug.DebugLogger
import com.mezze.language.typechecker.debug.DebugLogger.Category
import com.mezze.language.typechecker.solving.collectFreeTypeVars
import com.mezze.language.typechecker.ErrorType
import com.mezze.language.typechecker.util.StringDistance
import com.mezze.language.ast.Keywords

/**
 * Expression constraint generator - generates constraints for all expression types.
 *
 * This is the heart of constraint-based type inference.
 * For each expression type, we:
 * 1. Generate constraints for sub-expressions
 * 2. Create fresh type variables as needed
 * 3. Add equality/ability/effect constraints
 * 4. Return ConstraintResult with type + constraints
 *
 * Design:
 * - Pure functions (no mutation!)
 * - Exhaustive when on Expression sealed interface
 * - No catch-all else clauses
 *
 * Performance optimizations:
 * - TypeVar mapping cache for effect operations (avoids repeated structural traversal)
 * - Lazy effectInstance creation (only when in perform context with associated types)
 * - Short-circuit checks to avoid unnecessary work
 */
class ExpressionConstraintGenerator {

    // ========================================================================
    // CACHES FOR PERFORMANCE
    // ========================================================================

    /**
     * Cache for TypeVar mappings from original to instantiated types.
     * Key: Pair of (original type identity hashCode, instantiated type identity hashCode)
     * This avoids repeated structural traversal for the same type pairs.
     *
     * The cache is automatically cleared when it grows too large to prevent memory issues
     * in long-running sessions.
     */
    private val typeVarMappingCache = mutableMapOf<Pair<Int, Int>, Map<TypeVar, TypeVar>>()

    /** Maximum cache size before automatic cleanup */
    private val maxCacheSize = 1000

    /**
     * Clear all internal caches. Call this between type-checking different modules
     * to prevent stale data and memory growth.
     */
    fun clearCaches() {
        typeVarMappingCache.clear()
    }

    /**
     * Generate constraints for an expression.
     *
     * This is the main entry point - exhaustive when on all Expression types.
     */
    fun generate(expr: Expression, context: ConstraintContext): ConstraintResult {
        return when (expr) {
            // Literals - simple types, no constraints
            is IntLiteral -> generateIntLiteral(expr, context)
            is NatLiteral -> generateNatLiteral(expr, context)
            is FloatLiteral -> generateFloatLiteral(expr, context)
            is FractionLiteral -> generateFractionLiteral(expr, context)
            is StringLiteral -> generateStringLiteral(expr, context)
            is SeqLiteral -> generateSeqLiteral(expr, context)

            // Variables - lookup in context
            is Identifier -> generateIdentifier(expr, context)

            // Pipe placeholder - should not reach constraint generation (desugared earlier)
            is PipePlaceholder -> generatePipePlaceholder(expr, context)

            // Pipe expression - should not reach constraint generation (desugared earlier)
            is PipeExpression -> generatePipeExpression(expr, context)

            // Parenthesized expression - just delegate to inner
            is ParenthesizedExpression -> generate(expr.expression, context)

            // Records
            is RecordLiteral -> generateRecordLiteral(expr, context)
            is MemberAccess -> generateMemberAccess(expr, context)

            // Variants
            is VariantConstruction -> generateVariantConstruction(expr, context)

            // Functions
            is LambdaExpression -> generateLambda(expr, context)
            is FunctionCall -> generateFunctionCall(expr, context)

            // Control flow
            // IfExpression is unified with MatchExpression at the type-checking level.
            // This simplifies the typechecker (one code path for all conditionals) while
            // preserving IfExpression in the AST for tooling and IfNode in the interpreter
            // for optimal runtime performance.
            is IfExpression -> generateMatch(expr.toMatchExpression(), context)
            is MatchExpression -> generateMatch(expr, context)

            // Blocks
            is DoExpression -> generateDo(expr, context)

            // Effects
            is PerformExpression -> generatePerform(expr, context)
            is EffectCallExpression -> generateEffectCall(expr, context)

            // State machines (generated by compiler, not user code)
            is StateMachineExpression -> generateStateMachine(expr, context)
        }
    }

    // ========================================================================
    // LITERALS
    // ========================================================================

    private fun generateIntLiteral(expr: IntLiteral, context: ConstraintContext): ConstraintResult {
        // Int literal has type Int, no constraints
        return ConstraintResult.simple(MezzeType.IntType)
    }

    private fun generateNatLiteral(expr: NatLiteral, context: ConstraintContext): ConstraintResult {
        // Nat literal has type Nat, no constraints
        return ConstraintResult.simple(MezzeType.NatType)
    }

    private fun generateFloatLiteral(expr: FloatLiteral, context: ConstraintContext): ConstraintResult {
        // Float literal has type Float, no constraints
        return ConstraintResult.simple(MezzeType.FloatType)
    }

    private fun generateFractionLiteral(expr: FractionLiteral, context: ConstraintContext): ConstraintResult {
        // Fraction literal has type Frac, no constraints
        return ConstraintResult.simple(MezzeType.FracType)
    }

    private fun generateStringLiteral(expr: StringLiteral, context: ConstraintContext): ConstraintResult {
        if (!expr.isInterpolated) {
            // Simple string has type String, no constraints
            return ConstraintResult.simple(MezzeType.StringType)
        }

        // Interpolated string: each ExpressionPart must have type String
        val constraints = mutableListOf<Constraint>()
        val undefinedVars = mutableListOf<ConstraintResult.UndefinedVariable>()
        val effects = mutableSetOf<String>()
        val abilityConstraints = mutableListOf<Constraint.AbilityRequired>()
        val associatedTypeConstraints = mutableListOf<Constraint.AssociatedTypeResolution>()
        val usedVars = mutableSetOf<String>()
        val referencedBindings = mutableSetOf<DefinitionRef.ByName>()

        var interpolationIndex = 0
        for (part in expr.parts!!) {
            when (part) {
                is StringPart -> {
                    // String parts are fine, no constraints
                }
                is ExpressionPart -> {
                    // Expression must have type String
                    val exprResult = generate(part.expression, context)
                    constraints.addAll(exprResult.constraints)
                    undefinedVars.addAll(exprResult.undefinedVariables)
                    // Propagate effects and ability constraints from interpolated expressions
                    effects.addAll(exprResult.effects)
                    abilityConstraints.addAll(exprResult.abilityConstraints)
                    associatedTypeConstraints.addAll(exprResult.associatedTypeConstraints)
                    // Track used variables from interpolated expressions
                    usedVars.addAll(exprResult.usedVariables)
                    referencedBindings.addAll(exprResult.referencedBindings)
                    constraints.add(
                        Constraint.Equality(
                            left = exprResult.type,
                            right = MezzeType.StringType,
                            context = "String interpolation requires String type",
                            sourceLocation = part.expression.sourceLocation,
                            errorContext = ErrorContext.StringInterpolation(interpolationIndex)
                        )
                    )
                    interpolationIndex++
                }
            }
        }

        return ConstraintResult(
            type = MezzeType.StringType,
            constraints = constraints,
            effects = effects,
            abilityConstraints = abilityConstraints,
            associatedTypeConstraints = associatedTypeConstraints,
            undefinedVariables = undefinedVars,
            usedVariables = usedVars,
            referencedBindings = referencedBindings
        )
    }

    private fun generateSeqLiteral(expr: SeqLiteral, context: ConstraintContext): ConstraintResult {
        if (expr.elements.isEmpty()) {
            // Empty seq: [element] where element is fresh type variable
            val elementType = MezzeType.VarType(TypeVar.fresh())
            return ConstraintResult.simple(MezzeType.SeqType(elementType))
        }

        // Non-empty seq: all elements must have same type
        // Handle both regular elements and spread elements
        val elementType = MezzeType.VarType(TypeVar.fresh())
        val constraints = mutableListOf<Constraint>()
        val undefinedVars = mutableListOf<ConstraintResult.UndefinedVariable>()
        val abilityConstraints = mutableListOf<Constraint.AbilityRequired>()
        val associatedTypeConstraints = mutableListOf<Constraint.AssociatedTypeResolution>()
        val effects = mutableSetOf<String>()

        var elementIndex = 0
        for (element in expr.elements) {
            when (element) {
                is SeqRegularElement -> {
                    val result = generate(element.expression, context)
                    constraints.addAll(result.constraints)
                    undefinedVars.addAll(result.undefinedVariables)
                    abilityConstraints.addAll(result.abilityConstraints)
                    associatedTypeConstraints.addAll(result.associatedTypeConstraints)
                    effects.addAll(result.effects)
                    // Regular element must match element type
                    constraints.add(
                        Constraint.Equality(
                            left = result.type,
                            right = elementType,
                            context = "Seq elements must have same type",
                            sourceLocation = element.expression.sourceLocation,
                            errorContext = ErrorContext.SeqElement(elementIndex)
                        )
                    )
                    elementIndex++
                }
                is SeqSpreadElement -> {
                    val result = generate(element.expression, context)
                    constraints.addAll(result.constraints)
                    undefinedVars.addAll(result.undefinedVariables)
                    abilityConstraints.addAll(result.abilityConstraints)
                    associatedTypeConstraints.addAll(result.associatedTypeConstraints)
                    effects.addAll(result.effects)
                    // Spread element must be a seq with matching element type
                    constraints.add(
                        Constraint.Equality(
                            left = result.type,
                            right = MezzeType.SeqType(elementType),
                            context = "Spread element must be a seq",
                            sourceLocation = element.expression.sourceLocation,
                            errorContext = ErrorContext.SeqElement(elementIndex)
                        )
                    )
                    elementIndex++
                }
            }
        }

        return ConstraintResult(
            type = MezzeType.SeqType(elementType),
            constraints = constraints,
            effects = effects,
            abilityConstraints = abilityConstraints,
            associatedTypeConstraints = associatedTypeConstraints,
            undefinedVariables = undefinedVars
        )
    }

    // ========================================================================
    // VARIABLES
    // ========================================================================

    private fun generateIdentifier(expr: Identifier, context: ConstraintContext): ConstraintResult {
        val lookupKey = expr.toBindingKey()
        val lookupKeyString = lookupKey.toKeyString()  // For usedVariables, DefinitionRef, etc.
        if (expr.localName == "Show") {
            DebugLogger.debug(Category.ExpressionConstraints) { "generateIdentifier: Looking up '${lookupKeyString}'" }
        }

        // Look up variable in typing context
        return when (val result = context.typingContext.lookup(lookupKey)) {
            is TypingContext.LookupResult.Found -> {
                if (expr.localName == "Show") {
                    DebugLogger.debug(Category.ExpressionConstraints) { "generateIdentifier: Found '${lookupKeyString}' in context!" }
                }
                when (val binding = result.binding) {
                    is TypingContext.Binding.Monomorphic -> {
                        // Monomorphic variable - use type directly
                        // CRITICAL: If the type is SuspendedType, extract its effects
                        // This allows perform expressions to know what effects need handling
                        val type = binding.type
                        // Track reference for dependency graph (module-qualified if from another module)
                        val referencedBinding = DefinitionRef.ByName(binding.sourceModule, lookupKeyString)
                        when (type) {
                            is MezzeType.SuspendedType -> ConstraintResult(
                                type = type,
                                constraints = emptyList(),
                                effects = type.effects.effects,  // Propagate effects from SuspendedType
                                effectInstances = type.effects.effectInstances,  // Propagate effect instances for associated type bindings
                                usedVariables = setOf(lookupKeyString),  // Track variable usage
                                referencedBindings = setOf(referencedBinding)
                            )
                            else -> ConstraintResult(
                                type = type,
                                constraints = emptyList(),
                                usedVariables = setOf(lookupKeyString),  // Track variable usage
                                referencedBindings = setOf(referencedBinding)
                            )
                        }
                    }
                    is TypingContext.Binding.Polymorphic -> {
                        // Polymorphic variable - instantiate with fresh type variables
                        val instantiation = binding.scheme.instantiate()

                        // Convert FreshAbilityConstraints to typechecker Constraint.AbilityRequired
                        // The fresh constraints now contain the actual TypeVar (not just a name string)
                        // and typeArgs for multi-parameter abilities like Encode Json 'a
                        val abilityConstraints = instantiation.freshConstraints.map { freshConstraint ->
                            Constraint.AbilityRequired(
                                typeVar = freshConstraint.typeVar,
                                abilityName = freshConstraint.abilityName,
                                typeArgs = freshConstraint.typeArgs,
                                context = "Polymorphic instantiation of '${lookupKeyString}'",
                                sourceLocation = expr.sourceLocation
                            )
                        }

                        // CRITICAL: If the type is SuspendedType, extract its effects and effectInstances
                        val type = instantiation.type
                        val effects = when (type) {
                            is MezzeType.SuspendedType -> type.effects.effects
                            else -> emptySet()
                        }
                        val effectInstances = when (type) {
                            is MezzeType.SuspendedType -> type.effects.effectInstances
                            else -> emptySet()
                        }

                        // Track reference for dependency graph (module-qualified if from another module)
                        val referencedBinding = DefinitionRef.ByName(binding.sourceModule, lookupKeyString)

                        ConstraintResult(
                            type = type,
                            constraints = emptyList(),
                            effects = effects,
                            effectInstances = effectInstances,  // Propagate effect instances for associated type bindings
                            abilityConstraints = abilityConstraints,
                            usedVariables = setOf(lookupKeyString),  // Track variable usage
                            referencedBindings = setOf(referencedBinding)
                        )
                    }
                }
            }
            is TypingContext.LookupResult.NotFound -> {
                // Variable not found
                // (True and False should be found in context now, as they are registered as Bool variant constructors)

                // CRITICAL: Check if this is an ability or effect being used as a value
                // Abilities and effects start with uppercase but should NOT be treated as variant constructors
                // They should be marked as undefined variables so validation can catch them
                val isBuiltInAbility = BuiltInAbilities.isBuiltInAbility(lookupKeyString)
                val isUserDefinedAbility = lookupKeyString in context.abilityNames
                val isEffect = lookupKeyString in context.effectNames

                if (expr.localName == "Show") {
                    DebugLogger.debugSection(Category.ExpressionConstraints, "Checking '${lookupKeyString}'") {
                        listOf(
                            "isBuiltInAbility: $isBuiltInAbility",
                            "isUserDefinedAbility: $isUserDefinedAbility",
                            "context.abilityNames: ${context.abilityNames}",
                            "isEffect: $isEffect",
                            "context.effectNames: ${context.effectNames}"
                        )
                    }
                }

                if (isBuiltInAbility || isUserDefinedAbility || isEffect) {
                    // This is an ability or effect being used as a value - error!
                    // Mark as undefined variable so TypeChecker can provide a better error message
                    val freshType = MezzeType.VarType(TypeVar.fresh())
                    return ConstraintResult(
                        type = freshType,
                        constraints = emptyList(),
                        undefinedVariables = listOf(
                            ConstraintResult.UndefinedVariable(
                                name = lookupKeyString,
                                sourceLocation = expr.sourceLocation,
                                qualifier = expr.qualifier,
                                isQualified = expr.isQualified
                            )
                        )
                    )
                }

                // Check if it's a variant constructor (uppercase) or undefined variable (lowercase)
                if (expr.localName.first().isUpperCase()) {
                    // Uppercase names are treated as variant constructors
                    // CRITICAL: Don't create a concrete variant type here!
                    // Instead, create a fresh type variable with a HasConstructor constraint.
                    // This allows the type variable to unify with existing variant type aliases.
                    val resultType = MezzeType.VarType(TypeVar.fresh())
                    val constraints = listOf(
                        Constraint.HasConstructor(
                            variantType = resultType,
                            constructorName = expr.localName,
                            payloadType = null,
                            context = "Variant constructor ${expr.localName}"
                        )
                    )
                    ConstraintResult(
                        type = resultType,
                        constraints = constraints
                    )
                } else {
                    // Lowercase name - this is an undefined variable
                    // Track it for validation phase
                    val freshType = MezzeType.VarType(TypeVar.fresh())
                    ConstraintResult(
                        type = freshType,
                        constraints = emptyList(),
                        undefinedVariables = listOf(
                            ConstraintResult.UndefinedVariable(
                                name = lookupKeyString,
                                sourceLocation = expr.sourceLocation,
                                qualifier = expr.qualifier,
                                isQualified = expr.isQualified
                            )
                        )
                    )
                }
            }
        }
    }

    private fun generatePipePlaceholder(expr: PipePlaceholder, context: ConstraintContext): ConstraintResult {
        // PipePlaceholder should be desugared before constraint generation
        // If we reach here, it means @ was used outside of a pipe expression
        throw com.mezze.language.typechecker.TypeCheckError(
            "'@' can only be used inside a pipe expression (|>).\n\n" +
            "  hint: Use '@' to refer to the piped value:\n" +
            "        value |> func { arg = @ }",
            expr.sourceLocation
        )
    }

    private fun generatePipeExpression(expr: PipeExpression, context: ConstraintContext): ConstraintResult {
        // PipeExpression should be desugared before constraint generation
        // If we reach here, there's a bug in the transformation phase
        throw com.mezze.language.typechecker.TypeCheckError(
            "Internal error: PipeExpression was not desugared before type checking. " +
            "This is a compiler bug.",
            expr.sourceLocation
        )
    }

    // ========================================================================
    // RECORDS
    // ========================================================================

    private fun generateRecordLiteral(expr: RecordLiteral, context: ConstraintContext): ConstraintResult {
        // {field1 = expr1, field2 = expr2, ...}
        // or {...spread, field1 = expr1, ...}

        val fields = linkedMapOf<String, MezzeType>()  // Preserve insertion order
        val constraints = mutableListOf<Constraint>()
        val undefinedVars = mutableListOf<ConstraintResult.UndefinedVariable>()
        val abilityConstraints = mutableListOf<Constraint.AbilityRequired>()
        val associatedTypeConstraints = mutableListOf<Constraint.AssociatedTypeResolution>()
        val usedVars = mutableSetOf<String>()
        val effects = mutableSetOf<String>()  // Collect effects from field expressions
        val effectInstances = mutableSetOf<MezzeType.EffectInstance>()  // Collect effect instances

        // Pre-pass: collect ALL explicit field names upfront
        // This is needed because spread validation needs to know all field names,
        // even if they appear after the spread in source order
        val explicitFieldNames = expr.entries
            .filterIsInstance<RecordFieldEntry>()
            .map { it.name }
            .toSet()

        // First pass: collect explicit field types
        val explicitFieldTypes = mutableMapOf<String, MezzeType>()

        // IMPORTANT: Clear expected type when generating field values
        // The expected type is for the record as a whole, not individual field values.
        // Without this, a type annotation like `{} -> Continuation` would propagate
        // the `Continuation` expected type to all field value expressions (including lambdas).
        val fieldContext = context.clearExpectedType()

        for (entry in expr.entries) {
            when (entry) {
                is RecordFieldEntry -> {
                    val fieldValue = entry.value ?: Identifier(localName = entry.name)
                    val fieldResult = generate(fieldValue, fieldContext)

                    // If the field value is a SuspendedType (effectful value), unwrap it:
                    // - Use the inner valueType for the field type (effects are evaluated, not stored)
                    // - Propagate the effects upward to the record expression
                    // IMPORTANT: Propagate BOTH effects AND effectInstances from the SuspendedType
                    val fieldType = when (val t = fieldResult.type) {
                        is MezzeType.SuspendedType -> {
                            effects.addAll(t.effects.effects)  // Propagate simple effects from SuspendedType
                            effectInstances.addAll(t.effects.effectInstances)  // Propagate effect instances (with associated types)
                            t.valueType  // Use the unwrapped value type
                        }
                        else -> t
                    }

                    explicitFieldTypes[entry.name] = fieldType
                    constraints.addAll(fieldResult.constraints)
                    undefinedVars.addAll(fieldResult.undefinedVariables)
                    abilityConstraints.addAll(fieldResult.abilityConstraints)
                    associatedTypeConstraints.addAll(fieldResult.associatedTypeConstraints)
                    usedVars.addAll(fieldResult.usedVariables)
                    effects.addAll(fieldResult.effects)  // Propagate effects from field expressions
                    effectInstances.addAll(fieldResult.effectInstances)  // Propagate effect instances
                }
                is RecordSpreadEntry -> {
                    // Spread: {...baseRecord}
                    // Process the spread expression and extract its fields
                    val spreadResult = generate(entry.expression, fieldContext)
                    constraints.addAll(spreadResult.constraints)
                    undefinedVars.addAll(spreadResult.undefinedVariables)
                    abilityConstraints.addAll(spreadResult.abilityConstraints)
                    associatedTypeConstraints.addAll(spreadResult.associatedTypeConstraints)
                    usedVars.addAll(spreadResult.usedVariables)
                    effects.addAll(spreadResult.effects)  // Propagate effects from spread expressions
                    effectInstances.addAll(spreadResult.effectInstances)  // Propagate effect instances

                    // Extract fields from the spread type and merge into our record
                    // The spread expression must be a record type (or unify with one via constraints)
                    // Also handle type aliases (TypeApplication) by resolving to underlying RecordType
                    val resolvedSpreadType = when (val spreadType = spreadResult.type) {
                        is MezzeType.RecordType -> spreadType
                        is MezzeType.TypeApplication -> {
                            // Resolve type alias to underlying record type
                            val typeDecl = context.typeDeclarations[spreadType.constructor]
                            if (typeDecl != null && typeDecl.definition is RecordDefinition) {
                                val recordDef = typeDecl.definition as RecordDefinition
                                val resolvedFields = recordDef.fields.associate { field ->
                                    field.name to convertFieldType(field.type)
                                }
                                MezzeType.RecordType(
                                    fields = resolvedFields,
                                    rowVar = if (recordDef.isOpen) RowVar("rest") else null
                                )
                            } else null
                        }
                        is MezzeType.VarType -> null  // Type not yet known
                        else -> null
                    }

                    when {
                        resolvedSpreadType != null -> {
                            // VALIDATION: Check that all explicit fields exist in the spread record
                            // Mezze spread semantics: override only, no field addition
                            for (fieldName in explicitFieldNames) {
                                if (fieldName !in resolvedSpreadType.fields) {
                                    throw SpreadSyntaxError.FieldAdditionNotAllowed(
                                        fieldName,
                                        resolvedSpreadType.fields.keys.toList()
                                    )
                                }
                            }

                            // Add all fields from the spread record in their original order
                            // Explicit fields will override values but we use spread order as base
                            for ((fieldName, fieldType) in resolvedSpreadType.fields) {
                                // Use explicit type if available, otherwise spread type
                                fields[fieldName] = explicitFieldTypes[fieldName] ?: fieldType
                            }
                        }
                        spreadResult.type is MezzeType.VarType -> {
                            // Spread type is not yet known - add a constraint that it must be a record
                            // We'll use a fresh row variable to represent the unknown fields
                            // This enables: let x = { ...someVar } where someVar's type isn't known yet
                            // For now, we don't add fields (they'll be resolved during constraint solving)
                        }
                        else -> {
                            // Spread expression is not a record - throw an error
                            throw SpreadSyntaxError.NonRecordSpread(prettyPrintType(spreadResult.type))
                        }
                    }
                }
            }
        }

        // Second pass: add any explicit fields that weren't in a spread (new fields)
        // Note: if there was a spread, all fields should already be in 'fields' map
        // and we validated that explicit fields exist in the spread record
        for ((fieldName, fieldType) in explicitFieldTypes) {
            if (fieldName !in fields) {
                fields[fieldName] = fieldType
            }
        }

        // VALIDATION: If expected type is present AND is a type alias, validate closed record constraints
        // This handles type annotations like: let x: ClosedPerson = { ... }
        // where ClosedPerson is a type alias for a closed record
        // IMPORTANT: Only validate TypeApplications (user-defined type aliases), NOT plain RecordTypes
        // (plain RecordTypes come from nested records and should be validated by constraint solving instead)
        val expectedType = context.expectedType
        if (expectedType != null && expectedType is MezzeType.TypeApplication) {
            // Look up type declaration and resolve to RecordType
            val typeDecl = context.typeDeclarations[expectedType.constructor]
            if (typeDecl != null && typeDecl.definition is RecordDefinition) {
                val recordDef = typeDecl.definition as RecordDefinition
                // Convert RecordDefinition to RecordType (for validation purposes only)
                val fields = recordDef.fields.associate { field ->
                    field.name to convertFieldType(field.type)
                }
                val resolvedType = MezzeType.RecordType(
                    fields = fields,
                    rowVar = if (recordDef.isOpen) RowVar("rest") else null
                )

                // If resolved type is a closed record, validate fields
                if (resolvedType.rowVar == null) {
                    // Expected type is a closed record - validate fields match exactly
                    // Use `fields.keys` which includes both explicit AND spread fields
                    val allFieldNames = fields.keys
                    val missingFields = resolvedType.fields.keys - allFieldNames
                    if (missingFields.isNotEmpty()) {
                        val missingList = missingFields.joinToString(", ")
                        val example = missingFields.take(2).joinToString(", ") { "$it = ..." }
                        throw TypeCheckError(
                            message = "Record is missing ${if (missingFields.size == 1) "field" else "fields"}: $missingList. " +
                                    "Add: { ${allFieldNames.joinToString(", ") { "$it = ..." }}, $example }",
                            expression = expr,
                            errorType = ErrorType.MISSING_FIELD,
                            metadata = mapOf(
                                "missingFields" to missingFields.toList(),
                                "allFieldNames" to allFieldNames.toList()
                            )
                        )
                    }

                    val extraFields = allFieldNames - resolvedType.fields.keys
                    if (extraFields.isNotEmpty()) {
                        val extraList = extraFields.joinToString(", ")
                        val expectedList = resolvedType.fields.keys.sorted().joinToString(", ")
                        throw TypeCheckError(
                            message = "Record has extra ${if (extraFields.size == 1) "field" else "fields"}: $extraList. " +
                                    "Remove ${if (extraFields.size == 1) "it" else "them"}. Expected fields: $expectedList",
                            expression = expr,
                            errorType = ErrorType.TYPE_MISMATCH,
                            metadata = mapOf(
                                "extraFields" to extraFields.toList(),
                                "expectedFields" to resolvedType.fields.keys.toList()
                            )
                        )
                    }
                }
            }
        }

        val recordType = MezzeType.RecordType(
            fields = fields,
            // Use userOpen() to mark user-specified openness (won't be closed by closeGeneratedRowVars)
            rowVar = if (expr.isOpen) RowVar.userOpen() else null
        )

        return ConstraintResult(
            type = recordType,
            constraints = constraints,
            effects = effects,  // Include effects from field expressions
            effectInstances = effectInstances,  // Include effect instances
            undefinedVariables = undefinedVars,
            abilityConstraints = abilityConstraints,
            associatedTypeConstraints = associatedTypeConstraints,
            usedVariables = usedVars
        )
    }

    private fun generateMemberAccess(expr: MemberAccess, context: ConstraintContext): ConstraintResult {
        // receiver.member

        // Special case: Check if this is an ability method access
        // Example: Show.show, NumAdd.add, Eq.eq, etc.

        // Get receiver name (works for both Identifier and VariantConstruction)
        val receiverName = when (expr.receiver) {
            is Identifier -> (expr.receiver as Identifier).localName
            is VariantConstruction -> (expr.receiver as VariantConstruction).constructor
            else -> null
        }

        if (receiverName != null) {
            // Check if user has defined their own ability with this name (shadowing built-in)
            val userDefinedAbility = context.abilityDeclarations[receiverName]

            // Direct Ability.method access — return the FULL method type (with `it` param).
            // Example: Eq.eq returns {it: 'a, r: 'a} -> Bool
            if (userDefinedAbility == null && BuiltInAbilities.isBuiltInAbility(receiverName)) {
                val freshTypeVar = MezzeType.VarType(TypeVar.fresh())
                val methodType = BuiltInAbilities.getAbilityMethodType(
                    receiverName,
                    expr.member,
                    freshTypeVar
                )

                if (methodType != null) {
                    // Return the full method type for direct calls: Ability.method {it = x, r = y}
                    return ConstraintResult(
                        type = methodType,
                        constraints = emptyList(),
                        effects = emptySet(),
                        effectInstances = emptySet(),
                        abilityConstraints = listOf(Constraint.AbilityRequired(
                            typeVar = freshTypeVar.variable,
                            abilityName = receiverName,
                            typeArgs = emptyList(),
                            context = "Direct ability call: ${receiverName}.${expr.member}",
                            sourceLocation = expr.sourceLocation
                        )),
                        associatedTypeConstraints = emptyList(),
                        undefinedVariables = emptyList(),
                        usedVariables = emptySet()
                    )
                } else {
                    // Method not found on built-in ability - throw error with suggestion
                    val availableMethods = BuiltInAbilities.getAvailableMethods(receiverName)
                    val suggestion = findClosestMatch(expr.member, availableMethods.toSet())
                    val didYouMean = if (suggestion != null) " Did you mean '$suggestion'?" else ""
                    throw TypeCheckError(
                        message = "Method '${expr.member}' not found in ability '$receiverName'.$didYouMean Available methods: ${availableMethods.joinToString(", ")}",
                        expression = expr,
                        errorType = ErrorType.MISSING_FIELD
                    )
                }
            }

            // Check if this is a user-defined ability (using dedicated map)
            val abilityOperationType = context.typingContext.getAbilityOperationType(receiverName)
            if (abilityOperationType != null) {
                throw TypeCheckError(
                    message = "Direct ability access '${receiverName}.${expr.member}' is not allowed. " +
                        "Use 'value.${expr.member} { args }' or '${receiverName}.${expr.member} { it = value, ... }' instead.",
                    expression = expr,
                    errorType = ErrorType.MISSING_FIELD
                )
            }

            // Check if this is an effect (using dedicated map)
            val effectOperationType = context.typingContext.getEffectOperationType(receiverName)
            if (effectOperationType != null) {
                val binding = TypingContext.Binding.Monomorphic(effectOperationType)
                // Try effect operation first (if it produces suspended type)
                val effectResult = tryGenerateEffectOperationCall(expr, receiverName, binding, context)
                if (effectResult != null) {
                    return effectResult
                }
            }

            // Check if this is a performer method call (e.g., BasicLogger.info)
            // Performers implement effects, so we need to look up the effect and its operations
            val effectName = context.performerToEffect[receiverName]
            if (effectName != null) {
                // This is a performer - look up the effect operations using dedicated map
                val effectOpsType = context.typingContext.getEffectOperationType(effectName)
                if (effectOpsType != null) {
                    val effectBinding = TypingContext.Binding.Monomorphic(effectOpsType)
                    // Generate performer method call - similar to effect operation but bound to specific performer
                    val performerResult = tryGeneratePerformerMethodCall(expr, receiverName, effectName, effectBinding, context)
                    if (performerResult != null) {
                        return performerResult
                    }
                }
            }

            // CRITICAL FIX: If receiver name starts with uppercase but wasn't recognized as
            // a built-in ability, user-defined ability, effect, or performer, it's an error.
            // This catches undefined ability/effect usage like Show.show when Show is not defined.
            if (receiverName.first().isUpperCase()) {
                // Check if it's in the typing context (could be a type or variant constructor with methods)
                val contextLookup = context.typingContext.lookup(receiverName)
                if (contextLookup is TypingContext.LookupResult.NotFound) {
                    // Not found anywhere - this is likely an undefined ability or effect
                    // Check what it might be and give a helpful error
                    val similarAbility = findClosestMatch(receiverName, context.abilityNames)
                    val similarEffect = findClosestMatch(receiverName, context.effectNames)
                    val builtInAbilities = BuiltInAbilities.getAllAbilityNames()
                    val similarBuiltIn = findClosestMatch(receiverName, builtInAbilities)

                    val suggestion = when {
                        similarAbility != null -> " Did you mean ability '$similarAbility'?"
                        similarEffect != null -> " Did you mean effect '$similarEffect'?"
                        similarBuiltIn != null -> " Did you mean built-in ability '$similarBuiltIn'?"
                        else -> ""
                    }

                    throw TypeCheckError(
                        message = "'$receiverName' is not defined. Cannot access method '${expr.member}' on undefined ability or effect.$suggestion",
                        expression = expr,
                        errorType = ErrorType.UNDEFINED_VARIABLE
                    )
                }
            }
        }

        // Normal case: receiver must be record with 'member' field
        val receiverResult = generate(expr.receiver, context)
        val fieldType = MezzeType.VarType(TypeVar.fresh())

        // CRITICAL: Validate field access on record literals (matches OldTypeChecker.kt:6260-6310)
        // Check if field exists for:
        // 1. Identifiers bound to record literals (e.g., let x = {a = 1}; x.b)
        // 2. Nested field access where intermediate type is a known RecordType (e.g., data.point.z)

        // First, determine if we should validate field existence
        val shouldValidate = if (expr.receiver is Identifier) {
            // For identifiers, only validate if bound to a record literal
            val receiverName = (expr.receiver as Identifier).toBindingKey().toKeyString()
            receiverName in context.recordLiteralBindings
        } else if (expr.receiver is MemberAccess) {
            // For nested access, validate if the receiver type is a known RecordType
            // This handles cases like data.point.z where point has type {x: Int, y: Int}
            receiverResult.type is MezzeType.RecordType
        } else {
            false
        }

        if (shouldValidate) {
            // CRITICAL: Resolve TypeApplication to get the actual fields
            val (fieldNames, isOpen) = when (val t = receiverResult.type) {
                is MezzeType.RecordType -> t.fields.keys to (t.rowVar != null)
                is MezzeType.TypeApplication -> {
                    // Look up the type alias definition to get its fields
                    context.typeDeclarations[t.constructor]?.let { typeDecl ->
                        when (val def = typeDecl.definition) {
                            is RecordDefinition -> def.fields.map { it.name }.toSet() to def.isOpen
                            else -> null
                        }
                    } ?: (emptySet<String>() to false)
                }
                else -> emptySet<String>() to false
            }

            // Check if field exists
            if (fieldNames.isNotEmpty() && !fieldNames.contains(expr.member)) {
                // Field doesn't exist - check if record is closed or open
                val suggestion = findClosestMatch(expr.member, fieldNames)
                val didYouMean = if (suggestion != null) " Did you mean '$suggestion'?" else ""

                if (!isOpen) {
                    // Closed record - field must exist, throw error
                    throw TypeCheckError(
                        message = "Field '${expr.member}' not found in record.$didYouMean Available fields: ${fieldNames.joinToString()}",
                        expression = expr,
                        errorType = ErrorType.MISSING_FIELD,
                        metadata = mapOf(
                            "field" to expr.member,
                            "availableFields" to fieldNames.toList()
                        )
                    )
                } else {
                    // Open record with explicit literal - still fail
                    // OldTypeChecker validates this too (line 6285-6300)
                    throw TypeCheckError(
                        message = "Field '${expr.member}' not found in open record.$didYouMean Explicitly declared fields: ${fieldNames.joinToString()}",
                        expression = expr,
                        errorType = ErrorType.MISSING_FIELD,
                        metadata = mapOf(
                            "field" to expr.member,
                            "availableFields" to fieldNames.toList()
                        )
                    )
                }
            }
        }

        val constraints = mutableListOf<Constraint>()
        constraints.addAll(receiverResult.constraints)

        // Determine if this is a strict field check (record literal context)
        // For strict checks, field accumulation is disallowed - the field must exist exactly
        // This applies to:
        // 1. Direct access on a record literal binding: data.x where data = {...}
        // 2. Nested access on a record literal binding: data.point.z where data = {point = {...}, ...}
        val isStrictFieldCheck = isRecordLiteralAccess(expr.receiver, context)

        // Add HasField constraint
        constraints.add(
            Constraint.HasField(
                recordType = receiverResult.type,
                fieldName = expr.member,
                fieldType = fieldType,
                context = "Field access ${expr.member}",
                strictFieldCheck = isStrictFieldCheck
            )
        )

        return ConstraintResult(
            type = fieldType,
            constraints = constraints,
            undefinedVariables = receiverResult.undefinedVariables
        )
    }

    /**
     * Strip the receiver parameter from a method's function type.
     *
     * Convention: the first field of an ability method's param record is the receiver.
     * - `show: {val: 'a} -> Str` → `{} -> Str`
     * - `eq: {l: 'a, r: 'a} -> Bool` → `{r: 'a} -> Bool`
     * - `and: {l: Bool, r: Bool} -> Bool` → `{r: Bool} -> Bool`
     */
    private fun stripReceiverFromMethodType(methodType: MezzeType, receiverTypeVar: MezzeType.VarType): MezzeType {
        // Strip `it` from the method's param record for method-call syntax.
        // The receiver is passed separately via the member access (x.method {args}).
        if (methodType !is MezzeType.FunctionType) return methodType
        val params = (methodType.paramType as? MezzeType.RecordType)?.fields ?: return methodType
        val strippedParams = params.filterKeys { it != "it" }
        return MezzeType.FunctionType(
            paramType = MezzeType.RecordType(fields = strippedParams, rowVar = null),
            returnType = methodType.returnType,
            effects = methodType.effects
        )
    }

    /**
     * Try to generate constraints for method call syntax: `value.method { args }`.
     *
     * Desugars `42.show {}` to `Show.show { it = 42 }`.
     *
     * The method's receiver parameter is stripped (bound via `it`), and the explicit
     * arguments are matched against the remaining parameters.
     *
     * Returns null if no matching ability method is found (fall through to field access).
     */
    private fun tryGenerateMethodCall(
        expr: FunctionCall,
        memberAccess: MemberAccess,
        context: ConstraintContext
    ): ConstraintResult? {
        val methodName = memberAccess.member

        // FIELD ACCESS: If the receiver is known to have a field with this name,
        // it's plain field access — skip method call resolution.
        if (memberAccess.receiver is Identifier) {
            val receiverVarName = (memberAccess.receiver as Identifier).localName
            val lookupResult = context.typingContext.lookup(receiverVarName)
            if (lookupResult is TypingContext.LookupResult.Found) {
                val receiverType = when (val binding = lookupResult.binding) {
                    is TypingContext.Binding.Monomorphic -> binding.type
                    is TypingContext.Binding.Polymorphic -> binding.scheme.instantiate().type
                }
                if (receiverType is MezzeType.RecordType && receiverType.fields.containsKey(methodName)) {
                    return null // It's a field — let normal field access handle it
                }
            }
        }

        // Find abilities that have this method name
        val matchingAbilities = context.typingContext.findAbilitiesByMethodName(methodName)

        // Also check built-in abilities
        val builtInMatches = BuiltInAbilities.getAllAbilityNames()
            .filter { BuiltInAbilities.getAbilityMethodType(it, methodName, MezzeType.VarType(TypeVar.fresh())) != null }

        // Check inherent impl methods (impl Type where { ... })
        val inherentMethods = context.typingContext.findAnonymousMethodsByName(methodName)

        if (matchingAbilities.isEmpty() && builtInMatches.isEmpty() && inherentMethods.isEmpty()) {
            return null // No matching ability or inherent method — fall through to field access
        }

        // If ONLY inherent methods match (no ability methods), handle them directly
        if (matchingAbilities.isEmpty() && builtInMatches.isEmpty() && inherentMethods.isNotEmpty()) {
            return generateInherentMethodCall(expr, memberAccess, inherentMethods, context)
        }

        // Generate constraints for the receiver expression
        val receiverResult = generate(memberAccess.receiver, context.notInTailPosition())

        // Generate constraints for the arguments
        val argumentResult = generate(expr.arguments, context.clearExpectedType().notInTailPosition())

        // Resolve to the matching ability and get the receiver-stripped method type
        val strippedMethodType: MezzeType.FunctionType
        val receiverConstraintType: MezzeType  // The type the receiver must match (first field's type)
        // Collect ability constraints: from this method call AND from nested sub-expressions
        val abilityConstraints = mutableListOf<Constraint.AbilityRequired>()
        val associatedTypeConstraints = mutableListOf<Constraint.AssociatedTypeResolution>()
        abilityConstraints.addAll(receiverResult.abilityConstraints)
        abilityConstraints.addAll(argumentResult.abilityConstraints)
        associatedTypeConstraints.addAll(receiverResult.associatedTypeConstraints)
        associatedTypeConstraints.addAll(argumentResult.associatedTypeConstraints)

        // When both user-defined and built-in abilities match the same method name,
        // disambiguate by checking which ability's parameter fields match the call-site arguments.
        // e.g., `x + y` desugars to `x.add {r = y}` which should match NumAdd.add {r} not Math.add {x, y}.
        val callSiteFieldNames: Set<String>? = when (val args = expr.arguments) {
            is RecordLiteral -> args.entries.filterIsInstance<RecordFieldEntry>().map { entry -> entry.name }.toSet()
            else -> null
        }
        val filteredMatchingAbilities = if (callSiteFieldNames != null && matchingAbilities.isNotEmpty() && builtInMatches.isNotEmpty()) {
            matchingAbilities.filter { (_, rawMethodType) ->
                val methodParamFields = when (rawMethodType) {
                    is MezzeType.FunctionType -> when (val pt = rawMethodType.paramType) {
                        is MezzeType.RecordType -> pt.fields.keys
                        else -> null
                    }
                    else -> null
                }
                // Keep the ability if we can't determine fields (conservative) or if fields match
                methodParamFields == null || methodParamFields == callSiteFieldNames
            }
        } else {
            matchingAbilities
        }

        if (builtInMatches.size == 1 && filteredMatchingAbilities.isEmpty()) {
            // Built-in ability — method type from .mz files includes `it` param.
            // Strip `it` for method-call syntax (receiver is handled separately).
            val abilityName = builtInMatches.first()
            val freshTypeVar = MezzeType.VarType(TypeVar.fresh())
            val fullMethodType = BuiltInAbilities.getAbilityMethodType(abilityName, methodName, freshTypeVar)!!

            if (fullMethodType !is MezzeType.FunctionType) return null

            // Strip `it` from param record — call-site only has the other params
            val fullParams = (fullMethodType.paramType as? MezzeType.RecordType)?.fields ?: emptyMap()
            val strippedParams = fullParams.filterKeys { it != "it" }
            strippedMethodType = MezzeType.FunctionType(
                paramType = MezzeType.RecordType(fields = strippedParams, rowVar = null),
                returnType = fullMethodType.returnType,
                effects = fullMethodType.effects
            )

            receiverConstraintType = freshTypeVar

            abilityConstraints.add(Constraint.AbilityRequired(
                typeVar = freshTypeVar.variable,
                abilityName = abilityName,
                typeArgs = emptyList(),
                context = "Method call: $methodName",
                sourceLocation = expr.sourceLocation
            ))
        } else if (filteredMatchingAbilities.isNotEmpty()) {
            // User-defined ability
            val (abilityName, rawMethodType) = filteredMatchingAbilities.first()
            val abilityDecl = context.abilityDeclarations[abilityName]
            val associatedTypeNames = abilityDecl?.associatedTypes ?: emptyList()

            val (instantiatedMethodType, originalToFreshFromMethod) = instantiateTypeWithFreshVarsPreservingAssociatedWithMapping(
                rawMethodType, associatedTypeNames
            )

            // Ensure ALL ability type params have fresh vars, not just those in the method type.
            // The receiver type param (e.g. 'a in `ability Convert 'b for 'a`) may not appear
            // in the method signature (`convert : {} -> 'b`) but is needed for the ability constraint.
            val originalToFresh = originalToFreshFromMethod.toMutableMap()
            if (abilityDecl != null) {
                for (paramName in abilityDecl.typeParams) {
                    val normalizedName = paramName.removePrefix("'")
                    if (normalizedName !in originalToFresh) {
                        originalToFresh[normalizedName] = TypeVar.fresh()
                    }
                }
            }

            // Find the fresh var for the ability's primary type param
            val abilityTypeVars = abilityDecl?.typeParams?.mapNotNull { paramName ->
                originalToFresh[paramName.removePrefix("'")]
            } ?: emptyList()

            // The receiver type comes from the ability's `for` type param (receiverTypeParam).
            // In the new design, method signatures don't contain the receiver — it's implicit `it`.
            // Find the fresh var that corresponds to the receiverTypeParam.
            val receiverParamName = abilityDecl?.receiverTypeParam?.removePrefix("'")
            val receiverFreshVar = if (receiverParamName != null) {
                originalToFresh[receiverParamName]
            } else {
                // Fallback: use last type param (receiver is always last in typeParams)
                abilityTypeVars.lastOrNull()
            }

            receiverConstraintType = if (receiverFreshVar != null) {
                MezzeType.VarType(receiverFreshVar)
            } else if (abilityTypeVars.isNotEmpty()) {
                MezzeType.VarType(abilityTypeVars.last())
            } else {
                MezzeType.VarType(TypeVar.fresh())
            }

            val receiverTypeVar = if (receiverFreshVar != null) {
                MezzeType.VarType(receiverFreshVar)
            } else if (abilityTypeVars.isNotEmpty()) {
                MezzeType.VarType(abilityTypeVars.last())
            } else {
                MezzeType.VarType(TypeVar.fresh())
            }

            if (instantiatedMethodType !is MezzeType.FunctionType) return null
            val stripped = stripReceiverFromMethodType(instantiatedMethodType, receiverTypeVar)
            if (stripped !is MezzeType.FunctionType) return null
            strippedMethodType = stripped

            if (abilityTypeVars.isNotEmpty()) {
                val typeArgs = abilityTypeVars.drop(1).map { MezzeType.VarType(it) }
                abilityConstraints.add(Constraint.AbilityRequired(
                    typeVar = abilityTypeVars.first(),
                    abilityName = abilityName,
                    typeArgs = typeArgs,
                    context = "Method call: $methodName",
                    sourceLocation = expr.sourceLocation
                ))

                // Build associated type vars map and add AssociatedTypeResolution constraint
                if (associatedTypeNames.isNotEmpty()) {
                    val associatedTypeNamesNormalized = associatedTypeNames.map { it.removePrefix("'") }.toSet()
                    val associatedTypeVarsMap = mutableMapOf<String, TypeVar>()
                    for ((origName, freshVar) in originalToFresh) {
                        if (associatedTypeNamesNormalized.contains(origName)) {
                            associatedTypeVarsMap[origName] = freshVar
                        }
                    }
                    if (associatedTypeVarsMap.isNotEmpty()) {
                        associatedTypeConstraints.add(Constraint.AssociatedTypeResolution(
                            abilityName = abilityName,
                            primaryTypeVar = abilityTypeVars.first(),
                            associatedTypeVars = associatedTypeVarsMap,
                            context = "Associated type resolution for $abilityName.$methodName",
                            sourceLocation = expr.sourceLocation
                        ))
                    }
                }
            }

            // Handle method-level constraints
            if (instantiatedMethodType.abilityConstraints.isNotEmpty()) {
                for (constraint in instantiatedMethodType.abilityConstraints) {
                    val constrainedType = constraint.typeArgs.firstOrNull()
                    if (constrainedType is MezzeType.VarType) {
                        abilityConstraints.add(Constraint.AbilityRequired(
                            typeVar = constrainedType.variable,
                            abilityName = constraint.abilityName,
                            typeArgs = constraint.typeArgs.drop(1),
                            context = "Method-level constraint: ${constraint.abilityName} on $abilityName.$methodName",
                            sourceLocation = expr.sourceLocation
                        ))
                    }
                }
            }
        } else {
            return null
        }

        // Now we have:
        // - receiverConstraintType: the type the receiver must match (first field's type)
        // - strippedMethodType: method with receiver removed, e.g. {r: 'a} -> Bool or {} -> Str
        // - receiverResult: the receiver expression (e.g., 42)
        // - argumentResult: the explicit arguments (e.g., { r = 10 })

        val resultType = MezzeType.VarType(TypeVar.fresh())
        val constraints = mutableListOf<Constraint>()
        constraints.addAll(receiverResult.constraints)
        constraints.addAll(argumentResult.constraints)

        // Unwrap SuspendedType from receiver — effects are orthogonal to data types.
        // An effectful expression like `may_fail {x}` has type Suspended<Int, {Exception}>,
        // but for method dispatch we want the value type (Int) and propagate the effects.
        val receiverType: MezzeType
        val extraEffects: Set<String>
        val extraEffectInstances: Set<MezzeType.EffectInstance>
        when (val rt = receiverResult.type) {
            is MezzeType.SuspendedType -> {
                receiverType = rt.valueType
                extraEffects = rt.effects.effects
                extraEffectInstances = rt.effects.effectInstances
            }
            else -> {
                receiverType = rt
                extraEffects = emptySet()
                extraEffectInstances = emptySet()
            }
        }

        // Constrain: receiver type = first field's type (the receiver parameter)
        constraints.add(Constraint.Equality(
            left = receiverType,
            right = receiverConstraintType,
            context = "Method call receiver binds ${Keywords.IT}",
            sourceLocation = expr.sourceLocation
        ))

        // Constrain: explicit args = stripped method's param type
        constraints.add(Constraint.Equality(
            left = argumentResult.type,
            right = strippedMethodType.paramType,
            context = "Method call arguments for $methodName",
            sourceLocation = expr.sourceLocation
        ))

        // Constrain: result type = method's return type
        constraints.add(Constraint.Equality(
            left = resultType,
            right = strippedMethodType.returnType,
            context = "Method call return type",
            sourceLocation = expr.sourceLocation
        ))

        return ConstraintResult(
            type = resultType,
            constraints = constraints,
            effects = receiverResult.effects + argumentResult.effects + extraEffects,
            effectInstances = receiverResult.effectInstances + argumentResult.effectInstances + extraEffectInstances,
            abilityConstraints = abilityConstraints,
            associatedTypeConstraints = associatedTypeConstraints,
            undefinedVariables = receiverResult.undefinedVariables + argumentResult.undefinedVariables,
            usedVariables = receiverResult.usedVariables + argumentResult.usedVariables
        )
    }

    /**
     * Generate constraints for an inherent method call.
     *
     * Inherent methods come from `impl Type where { method = ... }` — no ability constraint needed.
     * The receiver type is unified with the target type from the inherent impl.
     */
    private fun generateInherentMethodCall(
        expr: FunctionCall,
        memberAccess: MemberAccess,
        inherentMethods: List<TypingContext.InherentMethodInfo>,
        context: ConstraintContext
    ): ConstraintResult {
        val methodName = memberAccess.member

        // Generate constraints for receiver and arguments
        val receiverResult = generate(memberAccess.receiver, context.notInTailPosition())
        val argumentResult = generate(expr.arguments, context.clearExpectedType().notInTailPosition())

        // Unwrap SuspendedType from receiver
        val receiverType: MezzeType
        val extraEffects: Set<String>
        val extraEffectInstances: Set<MezzeType.EffectInstance>
        when (val rt = receiverResult.type) {
            is MezzeType.SuspendedType -> {
                receiverType = rt.valueType
                extraEffects = rt.effects.effects
                extraEffectInstances = rt.effects.effectInstances
            }
            else -> {
                receiverType = rt
                extraEffects = emptySet()
                extraEffectInstances = emptySet()
            }
        }

        val resultType = MezzeType.VarType(TypeVar.fresh())
        val constraints = mutableListOf<Constraint>()
        constraints.addAll(receiverResult.constraints)
        constraints.addAll(argumentResult.constraints)

        // Disambiguate by receiver type name when multiple types share a method name.
        val receiverTypeName = getReceiverTypeName(receiverType)
        val methodInfo = if (inherentMethods.size == 1) {
            inherentMethods.first()
        } else if (receiverTypeName != null) {
            inherentMethods.firstOrNull { it.targetTypeName == receiverTypeName }
                ?: inherentMethods.firstOrNull()
        } else {
            inherentMethods.firstOrNull()
        }

        if (methodInfo != null) {
            // Re-instantiate the method type with fresh type variables.
            // The type stored in InherentMethodInfo may have stale vars from context-building time.
            val freshMethodType = instantiateTypeWithFreshVars(methodInfo.methodType)

            // No receiver type constraint needed — the inherent method was already matched
            // by type name during lookup. The receiver's actual type flows through naturally
            // from the receiver expression's constraint generation.

            if (freshMethodType is MezzeType.FunctionType) {
                // Strip `it` from param type — method syntax passes receiver separately
                val fullParams = (freshMethodType.paramType as? MezzeType.RecordType)?.fields ?: emptyMap()
                val strippedParams = fullParams.filterKeys { it != "it" }
                val strippedParamType = MezzeType.RecordType(fields = strippedParams, rowVar = null)

                // Constrain arguments (without it)
                constraints.add(Constraint.Equality(
                    left = argumentResult.type,
                    right = strippedParamType,
                    context = "Inherent method call arguments for $methodName",
                    sourceLocation = expr.sourceLocation
                ))

                // Constrain return type
                constraints.add(Constraint.Equality(
                    left = resultType,
                    right = freshMethodType.returnType,
                    context = "Inherent method call return type",
                    sourceLocation = expr.sourceLocation
                ))
            }
        }

        return ConstraintResult(
            type = resultType,
            constraints = constraints,
            effects = receiverResult.effects + argumentResult.effects + extraEffects,
            effectInstances = receiverResult.effectInstances + argumentResult.effectInstances + extraEffectInstances,
            abilityConstraints = receiverResult.abilityConstraints + argumentResult.abilityConstraints,
            associatedTypeConstraints = receiverResult.associatedTypeConstraints + argumentResult.associatedTypeConstraints,
            undefinedVariables = receiverResult.undefinedVariables + argumentResult.undefinedVariables,
            usedVariables = receiverResult.usedVariables + argumentResult.usedVariables
        )
    }

    /**
     * Try to generate constraints for effect operation calls.
     *
     * Example: Log.println {msg = "Hello"}
     *
     * Returns null if this is not an effect operation (so caller can try ability method instead).
     * Returns ConstraintResult if this is an effect operation.
     *
     * Effect operations are similar to ability methods, but they wrap the result in SuspendedType with the effect.
     */

    /** Extract type name from receiver type for inherent method disambiguation. */
    private fun getReceiverTypeName(type: MezzeType): String? = when (type) {
        is MezzeType.IntType -> "Int"
        is MezzeType.FloatType -> "Float"
        is MezzeType.StringType -> "Str"
        is MezzeType.BoolType -> "Bool"
        is MezzeType.NatType -> "Nat"
        is MezzeType.FracType -> "Frac"
        is MezzeType.CharType -> "Char"
        is MezzeType.BytesType -> "Bytes"
        is MezzeType.OrderingType -> "Ordering"
        is MezzeType.SeqType -> "Seq"
        is MezzeType.ListType -> "List"
        is MezzeType.SetType -> "Set"
        is MezzeType.MapType -> "Map"
        is MezzeType.TypeApplication -> type.constructor
        is MezzeType.RecordType -> type.typeName
        is MezzeType.VariantType -> type.typeName
        else -> null
    }

    private fun tryGenerateEffectOperationCall(
        expr: MemberAccess,
        effectName: String,
        effectBinding: TypingContext.Binding,
        context: ConstraintContext
    ): ConstraintResult? {
        // Check if this is actually an effect (not an ability)
        if (effectName !in context.effectNames) {
            return null  // Not an effect, caller will try ability method
        }

        // Get effect type from binding
        val effectType = when (effectBinding) {
            is TypingContext.Binding.Monomorphic -> effectBinding.type
            is TypingContext.Binding.Polymorphic -> {
                // Instantiate polymorphic effect type with fresh type variables
                effectBinding.scheme.instantiate().type
            }
        }

        // Effect should be stored as RecordType with operations as fields
        if (effectType !is MezzeType.RecordType) {
            // Shouldn't happen if ContextBuilder is working correctly
            throw IllegalStateException("Effect '$effectName' is not properly registered")
        }

        // Look up operation in effect
        val operationName = expr.member
        val operationType = effectType.fields[operationName]

        if (operationType == null) {
            // Operation not found - suggest similar operation names
            val availableOps = effectType.fields.keys
            val suggestion = findClosestMatch(operationName, availableOps)
            val didYouMean = if (suggestion != null) " Did you mean '$suggestion'?" else ""
            throw TypeCheckError(
                message = "Operation '$operationName' not found in effect '$effectName'.$didYouMean Available operations: ${availableOps.joinToString(", ")}",
                expression = expr,
                errorType = ErrorType.MISSING_FIELD
            )
        }

        // For effect operations, we DON'T want to instantiate with fresh vars yet
        // because we need to preserve associated type variable names for substitution
        // We'll instantiate AFTER substitution if needed
        var instantiatedOperationType = operationType

        // CRITICAL: If we're in a perform context and this effect has associated types,
        // substitute the associated type variables with the concrete types from the performer
        // This matches OldTypeChecker.kt lines 4810-4854
        val performContext = context.performContext
        if (performContext != null && instantiatedOperationType is MezzeType.FunctionType) {
            // Find which performer is handling this effect
            // The performer handlers are in performContext.handlers
            var performerName: String? = null

            // Try each handler in the perform context
            for (handler in performContext.handlers) {
                val handlerEffectName = context.performerToEffect[handler.performer.localName]
                if (handlerEffectName == effectName) {
                    performerName = handler.performer.localName
                    break
                }
            }

            if (performerName != null) {
                // Get the performer's associated type bindings using dedicated map
                val performerInfos = context.typingContext.getPerformerTypeInfosByName(performerName)
                if (performerInfos.isNotEmpty()) {
                    val performerInfo = performerInfos.first()
                    val performerType = performerInfo.associatedTypes

                    if (performerType is MezzeType.RecordType) {
                        // Substitute associated types in the operation type
                        // The performer's type has fields like: {env -> String}
                        instantiatedOperationType = substituteAssociatedTypesInType(
                            instantiatedOperationType,
                            performerType.fields
                        )

                        // CRITICAL: Preserve performer binding TypeVars during instantiation
                        // These TypeVars connect to the suspension type, so constraints
                        // from operation calls (e.g., 'error = String) must flow to the
                        // suspension type (e.g., Result 'val 'error -> Result 'val String)
                        val performerTypeVarNames = performerType.fields.values
                            .filterIsInstance<MezzeType.VarType>()
                            .map { it.variable.name }
                        instantiatedOperationType = instantiateTypeWithFreshVarsPreservingPerformerBindings(
                            instantiatedOperationType,
                            performerTypeVarNames
                        )
                    } else {
                        // No performer bindings to preserve
                        instantiatedOperationType = instantiateTypeWithFreshVars(instantiatedOperationType)
                    }
                } else {
                    // No performer info, use regular instantiation
                    instantiatedOperationType = instantiateTypeWithFreshVars(instantiatedOperationType)
                }
            } else {
                // No performer found, use regular instantiation
                instantiatedOperationType = instantiateTypeWithFreshVars(instantiatedOperationType)
            }
        } else {
            // Not in perform context, use regular instantiation
            instantiatedOperationType = instantiateTypeWithFreshVars(instantiatedOperationType)
        }

        // OPTIMIZATION: Only create effectInstance when the effect has associated types
        // This avoids expensive structural traversal for simple effects without associated types
        val effectDecl = context.effectDeclarations[effectName]
        val hasAssociatedTypes = effectDecl != null && effectDecl.associatedTypes.isNotEmpty()

        val effectInstances = if (hasAssociatedTypes) {
            // Create an EffectInstance with associated types from the INSTANTIATED operation type
            // This tracks the TypeVars that will be constrained by the call arguments
            // For example: Exception.throw {error = "boom"} creates Exception having 'error = String
            val effectInstance = createEffectInstanceFromInstantiated(
                effectName,
                operationType,
                instantiatedOperationType,
                context
            )
            setOf(effectInstance)
        } else {
            // No associated types - skip the expensive mapping
            emptySet()
        }

        // Return the plain function type with the effect tracked in ConstraintResult
        // When this operation is called, the effect will be collected and propagated
        // This matches how OldTypeChecker handled effect operations
        return ConstraintResult(
            type = instantiatedOperationType,
            constraints = emptyList(),
            effects = setOf(effectName),  // Track simple effect name for backward compatibility
            effectInstances = effectInstances,  // Track effect with associated types (only if present)
            undefinedVariables = emptyList()
        )
    }

    /**
     * Create an EffectInstance from an instantiated operation type.
     *
     * This function maps from the original operation type (with named TypeVars like 'error)
     * to the instantiated type (with fresh TypeVars like $tv123). It finds the corresponding
     * fresh TypeVars for each associated type by structural matching.
     *
     * Example: For Exception.throw
     *   - Original type: {error: 'error} -> 'a
     *   - Instantiated type: {error: $tv1} -> $tv2
     *   - Effect declaration has associatedTypes = ["error"]
     *   - We match 'error to $tv1 by finding it in the same structural position
     *   - Creates EffectInstance("Exception", {"error" -> VarType($tv1)})
     */
    private fun createEffectInstanceFromInstantiated(
        effectName: String,
        originalType: MezzeType,
        instantiatedType: MezzeType,
        context: ConstraintContext
    ): MezzeType.EffectInstance {
        // Get the effect declaration to find associated type names
        // Note: Caller already checked that effectDecl exists and has associated types
        val effectDecl = context.effectDeclarations[effectName]
            ?: return MezzeType.EffectInstance(effectName, emptyMap())

        if (effectDecl.associatedTypes.isEmpty()) {
            return MezzeType.EffectInstance(effectName, emptyMap())
        }

        // OPTIMIZATION: Build TypeVar mapping only once using cache
        val typeVarMapping = buildTypeVarMapping(originalType, instantiatedType)

        // For each associated type, find the original TypeVar and map it to the instantiated one
        // OPTIMIZATION: Use sized map to avoid rehashing
        val associatedTypeBindings = HashMap<String, MezzeType>(effectDecl.associatedTypes.size)

        if (originalType is MezzeType.FunctionType) {
            // OPTIMIZATION: Instead of collecting all TypeVars into a Set first,
            // search directly for each associated type name
            for (assocTypeName in effectDecl.associatedTypes) {
                val normalizedName = assocTypeName.removePrefix("'")

                // Find the TypeVar by name directly in the mapping keys
                val originalVar = typeVarMapping.keys.find { tv ->
                    tv.name == normalizedName || tv.name == "'$normalizedName"
                }

                if (originalVar != null) {
                    val instantiatedVar = typeVarMapping[originalVar]
                    if (instantiatedVar != null) {
                        associatedTypeBindings[normalizedName] = MezzeType.VarType(instantiatedVar)
                    } else {
                        associatedTypeBindings[normalizedName] = MezzeType.VarType(originalVar)
                    }
                }
            }
        }

        return MezzeType.EffectInstance(effectName, associatedTypeBindings)
    }

    /**
     * Build a mapping from TypeVars in the original type to TypeVars in the instantiated type.
     * This works by structural matching - we walk both types in parallel and collect the mappings.
     * Uses caching to avoid repeated traversal for the same type pairs.
     */
    private fun buildTypeVarMapping(original: MezzeType, instantiated: MezzeType): Map<TypeVar, TypeVar> {
        // Use identity hash codes for cache key (fast, works for same object references)
        val cacheKey = System.identityHashCode(original) to System.identityHashCode(instantiated)

        // Check cache first
        typeVarMappingCache[cacheKey]?.let { return it }

        // Cache miss - compute the mapping
        val mapping = mutableMapOf<TypeVar, TypeVar>()
        buildTypeVarMappingHelper(original, instantiated, mapping)

        // Store in cache with size management
        if (typeVarMappingCache.size >= maxCacheSize) {
            // Clear half the cache when full (simple LRU approximation)
            val keysToRemove = typeVarMappingCache.keys.take(maxCacheSize / 2)
            keysToRemove.forEach { typeVarMappingCache.remove(it) }
        }
        typeVarMappingCache[cacheKey] = mapping

        return mapping
    }

    private fun buildTypeVarMappingHelper(original: MezzeType, instantiated: MezzeType, mapping: MutableMap<TypeVar, TypeVar>) {
        when {
            original is MezzeType.VarType && instantiated is MezzeType.VarType -> {
                mapping[original.variable] = instantiated.variable
            }
            original is MezzeType.FunctionType && instantiated is MezzeType.FunctionType -> {
                buildTypeVarMappingHelper(original.paramType, instantiated.paramType, mapping)
                buildTypeVarMappingHelper(original.returnType, instantiated.returnType, mapping)
            }
            original is MezzeType.RecordType && instantiated is MezzeType.RecordType -> {
                for ((fieldName, fieldType) in original.fields) {
                    val instFieldType = instantiated.fields[fieldName]
                    if (instFieldType != null) {
                        buildTypeVarMappingHelper(fieldType, instFieldType, mapping)
                    }
                }
            }
            original is MezzeType.VariantType && instantiated is MezzeType.VariantType -> {
                for ((ctorName, ctorType) in original.constructors) {
                    val instCtorType = instantiated.constructors[ctorName]
                    if (ctorType != null && instCtorType != null) {
                        buildTypeVarMappingHelper(ctorType, instCtorType, mapping)
                    }
                }
            }
            original is MezzeType.TypeApplication && instantiated is MezzeType.TypeApplication -> {
                original.args.zip(instantiated.args).forEach { (orig, inst) ->
                    buildTypeVarMappingHelper(orig, inst, mapping)
                }
            }
            original is MezzeType.ListType && instantiated is MezzeType.ListType -> {
                buildTypeVarMappingHelper(original.elementType, instantiated.elementType, mapping)
            }
            original is MezzeType.SeqType && instantiated is MezzeType.SeqType -> {
                buildTypeVarMappingHelper(original.elementType, instantiated.elementType, mapping)
            }
            // Add more cases as needed...
        }
    }
    /**
     * Try to generate a performer method call (e.g., BasicLogger.info).
     *
     * Performers are concrete implementations of effects. When accessing a method
     * on a performer, we look up the corresponding effect's operation type.
     * The call still produces the effect (it's not handled by the performer itself).
     *
     * @return ConstraintResult if this is a valid performer method call, null otherwise
     */
    private fun tryGeneratePerformerMethodCall(
        expr: MemberAccess,
        performerName: String,
        effectName: String,
        effectBinding: TypingContext.Binding,
        context: ConstraintContext
    ): ConstraintResult? {
        // Get effect type from binding
        val effectType = when (effectBinding) {
            is TypingContext.Binding.Monomorphic -> effectBinding.type
            is TypingContext.Binding.Polymorphic -> {
                effectBinding.scheme.instantiate().type
            }
        }

        // Effect should be stored as RecordType with operations as fields
        if (effectType !is MezzeType.RecordType) {
            return null
        }

        // Look up operation in effect
        val operationName = expr.member
        val operationType = effectType.fields[operationName] ?: return null

        // Get the performer's associated type bindings (if any) using dedicated map
        var instantiatedOperationType = operationType
        val performerInfos = context.typingContext.getPerformerTypeInfosByName(performerName)
        if (performerInfos.isNotEmpty()) {
            val performerInfo = performerInfos.first()
            val performerType = performerInfo.associatedTypes

            if (performerType is MezzeType.RecordType) {
                // Substitute associated types in the operation type
                instantiatedOperationType = substituteAssociatedTypesInType(
                    instantiatedOperationType,
                    performerType.fields
                )
            }
        }

        // Instantiate with fresh vars
        instantiatedOperationType = instantiateTypeWithFreshVars(instantiatedOperationType)

        // Return the function type with the effect tracked
        // Performer method calls still produce effects - they need to be handled by perform
        return ConstraintResult(
            type = instantiatedOperationType,
            constraints = emptyList(),
            effects = setOf(effectName),
            undefinedVariables = emptyList()
        )
    }

    /**
     * Substitute associated type variables in a type with concrete types.
     * Used for effect operations with associated types.
     *
     * Example: If operation type is `{value: 'state} -> 'state <State>`
     *          and bindings is `{'state -> Int}`
     *          Result is `{value: Int} -> Int <State>`
     */
    private fun substituteAssociatedTypesInType(
        type: MezzeType,
        associatedTypeBindings: Map<String, MezzeType>
    ): MezzeType {
        return when (type) {
            is MezzeType.VarType -> {
                // CRITICAL: Associated type variables are represented as VarType with name like ''env'
                // The performer bindings map has keys like "env" (without ')
                // So we need to check both with and without the ' prefix
                val varName = type.variable.name
                // Try looking up with name as-is, then without ' prefix
                val keyWithoutQuote = varName.removePrefix("'")
                associatedTypeBindings[varName] ?: associatedTypeBindings[keyWithoutQuote] ?: type
            }
            is MezzeType.TypeApplication -> {
                // Check if this is an associated type reference (e.g., 'state)
                if (type.args.isEmpty() && type.constructor.startsWith("'")) {
                    // This is an associated type variable - substitute it
                    associatedTypeBindings[type.constructor] ?: type
                } else {
                    // Regular type application - recurse into args
                    MezzeType.TypeApplication(
                        constructor = type.constructor,
                        args = type.args.map { substituteAssociatedTypesInType(it, associatedTypeBindings) }
                    )
                }
            }
            is MezzeType.FunctionType -> MezzeType.FunctionType(
                paramType = substituteAssociatedTypesInType(type.paramType, associatedTypeBindings),
                returnType = substituteAssociatedTypesInType(type.returnType, associatedTypeBindings),
                effects = type.effects,
                abilityConstraints = type.abilityConstraints
            )
            is MezzeType.RecordType -> MezzeType.RecordType(
                fields = type.fields.mapValues { (_, fieldType) ->
                    substituteAssociatedTypesInType(fieldType, associatedTypeBindings)
                },
                rowVar = type.rowVar
            )
            is MezzeType.VariantType -> MezzeType.VariantType(
                constructors = type.constructors.mapValues { (_, dataType) ->
                    dataType?.let { substituteAssociatedTypesInType(it, associatedTypeBindings) }
                },
                rowVar = type.rowVar,
                typeName = type.typeName
            )
            is MezzeType.SeqType -> MezzeType.SeqType(
                elementType = substituteAssociatedTypesInType(type.elementType, associatedTypeBindings)
            )
            is MezzeType.ListType -> MezzeType.ListType(
                elementType = substituteAssociatedTypesInType(type.elementType, associatedTypeBindings)
            )
            is MezzeType.SetType -> MezzeType.SetType(
                elementType = substituteAssociatedTypesInType(type.elementType, associatedTypeBindings)
            )
            is MezzeType.SortedMapType -> MezzeType.SortedMapType(
                keyType = substituteAssociatedTypesInType(type.keyType, associatedTypeBindings),
                valueType = substituteAssociatedTypesInType(type.valueType, associatedTypeBindings)
            )
            is MezzeType.SortedSetType -> MezzeType.SortedSetType(
                elementType = substituteAssociatedTypesInType(type.elementType, associatedTypeBindings)
            )
            is MezzeType.MapType -> MezzeType.MapType(
                keyType = substituteAssociatedTypesInType(type.keyType, associatedTypeBindings),
                valueType = substituteAssociatedTypesInType(type.valueType, associatedTypeBindings)
            )
            is MezzeType.SuspendedType -> MezzeType.SuspendedType(
                valueType = substituteAssociatedTypesInType(type.valueType, associatedTypeBindings),
                effects = type.effects
            )
            is MezzeType.NewtypeType -> MezzeType.NewtypeType(
                type.name,
                type.constructor,
                substituteAssociatedTypesInType(type.wrappedType, associatedTypeBindings)
            )
            // Primitive types and VarType unchanged
            is MezzeType.VarType, is MezzeType.IntType, is MezzeType.FloatType, is MezzeType.StringType,
            is MezzeType.BoolType, is MezzeType.NatType, is MezzeType.FracType,
            is MezzeType.OrderingType, is MezzeType.BytesType, is MezzeType.ByteBufferType,
            is MezzeType.CharType, is MezzeType.EffectRow -> type
        }
    }

    /**
     * Instantiate a type with fresh type variables.
     * Replaces all type variables with fresh ones.
     * CRITICAL: Preserves variable names for associated types (names starting with ')
     */
    private fun instantiateTypeWithFreshVars(type: MezzeType): MezzeType {
        val freeVars = getFreeTypeVariables(type)
        if (freeVars.isEmpty()) {
            return type
        }

        // CRITICAL: Preserve names for associated type variables (those starting with ')
        // This allows them to be matched with performer bindings later
        val substitution = freeVars.associateWith { oldVar ->
            if (oldVar.name.startsWith("'")) {
                // Associated type variable - preserve the name but create new instance
                TypeVar(oldVar.name)
            } else {
                // Regular type variable - create fresh one
                TypeVar.fresh()
            }
        }
        return applyTypeVarSubstitution(type, substitution)
    }

    /**
     * Instantiate a type with fresh type variables, but preserve the names of associated types.
     * @param type The type to instantiate
     * @param associatedTypeNames List of associated type names (with or without ' prefix, e.g., ["'item"] or ["item"])
     */
    private fun instantiateTypeWithFreshVarsPreservingAssociated(
        type: MezzeType,
        associatedTypeNames: List<String>
    ): MezzeType {
        return instantiateTypeWithFreshVarsPreservingAssociatedWithMapping(type, associatedTypeNames).first
    }

    /**
     * Same as instantiateTypeWithFreshVarsPreservingAssociated but also returns the mapping
     * from original type var names (normalized, without ') to the fresh TypeVar instances.
     */
    private fun instantiateTypeWithFreshVarsPreservingAssociatedWithMapping(
        type: MezzeType,
        associatedTypeNames: List<String>
    ): Pair<MezzeType, Map<String, TypeVar>> {
        val freeVars = getFreeTypeVariables(type)
        if (freeVars.isEmpty()) {
            return type to emptyMap()
        }

        // Normalize associated type names (remove ' prefix if present)
        val normalizedAssociatedNames = associatedTypeNames.map { it.removePrefix("'") }.toSet()

        // Create substitution: fresh vars for type params, preserve names for associated types
        val substitution = freeVars.associateWith { oldVar ->
            val normalizedName = oldVar.name.removePrefix("'")
            if (normalizedAssociatedNames.contains(normalizedName)) {
                // Associated type variable - preserve the name but create new instance
                // Use the original name to ensure matching later
                TypeVar(oldVar.name)
            } else {
                // Regular type variable - create fresh one
                TypeVar.fresh()
            }
        }

        // Build the name mapping: original var name (normalized) -> fresh TypeVar
        val nameMapping = substitution.entries.associate { (oldVar, newVar) ->
            oldVar.name.removePrefix("'") to newVar
        }

        return applyTypeVarSubstitution(type, substitution) to nameMapping
    }

    /**
     * Instantiate type with fresh type variables, but preserve TypeVars that came from
     * performer bindings. These TypeVars are used to connect effect operation argument types
     * to the performer's associated type bindings, which then flow to Result type parameters.
     *
     * Unlike instantiateTypeWithFreshVarsPreservingAssociated which preserves based on
     * names starting with ', this function preserves based on an explicit list of names
     * (typically the TypeVars from performerType.fields.values).
     */
    private fun instantiateTypeWithFreshVarsPreservingPerformerBindings(
        type: MezzeType,
        performerTypeVarNames: List<String>
    ): MezzeType {
        val freeVars = getFreeTypeVariables(type)
        if (freeVars.isEmpty()) {
            return type
        }

        // Create a set of names to preserve (handle both with and without ' prefix)
        val namesToPreserve = performerTypeVarNames.flatMap { name ->
            listOf(name, name.removePrefix("'"), "'$name")
        }.toSet()

        // Create substitution: fresh vars for regular type params, preserve performer bindings
        val substitution = freeVars.associateWith { oldVar ->
            if (namesToPreserve.contains(oldVar.name) || namesToPreserve.contains(oldVar.name.removePrefix("'"))) {
                // Performer binding TypeVar - keep the SAME instance so constraints connect
                oldVar
            } else {
                // Regular type variable - create fresh one
                TypeVar.fresh()
            }
        }
        return applyTypeVarSubstitution(type, substitution)
    }

    /**
     * Get all free type variables in a type.
     */
    private fun getFreeTypeVariables(type: MezzeType): Set<TypeVar> {
        return when (type) {
            is MezzeType.VarType -> setOf(type.variable)
            is MezzeType.FunctionType -> {
                getFreeTypeVariables(type.paramType) + getFreeTypeVariables(type.returnType)
            }
            is MezzeType.RecordType -> {
                type.fields.values.flatMap { getFreeTypeVariables(it) }.toSet()
            }
            is MezzeType.VariantType -> {
                type.constructors.values.filterNotNull().flatMap { getFreeTypeVariables(it) }.toSet()
            }
            is MezzeType.SeqType -> getFreeTypeVariables(type.elementType)
            is MezzeType.ListType -> getFreeTypeVariables(type.elementType)
            is MezzeType.SetType -> getFreeTypeVariables(type.elementType)
            is MezzeType.SortedMapType -> getFreeTypeVariables(type.keyType) + getFreeTypeVariables(type.valueType)
            is MezzeType.SortedSetType -> getFreeTypeVariables(type.elementType)
            is MezzeType.MapType -> getFreeTypeVariables(type.keyType) + getFreeTypeVariables(type.valueType)
            is MezzeType.SuspendedType -> getFreeTypeVariables(type.valueType)
            is MezzeType.TypeApplication -> type.args.flatMap { getFreeTypeVariables(it) }.toSet()
            is MezzeType.NewtypeType -> getFreeTypeVariables(type.wrappedType)
            // Primitive types have no variables
            is MezzeType.IntType, is MezzeType.FloatType, is MezzeType.StringType,
            is MezzeType.BoolType, is MezzeType.NatType, is MezzeType.FracType,
            is MezzeType.OrderingType, is MezzeType.BytesType, is MezzeType.ByteBufferType,
            is MezzeType.CharType, is MezzeType.EffectRow -> emptySet()
        }
    }

    /**
     * Apply type variable substitution to a type.
     */
    private fun applyTypeVarSubstitution(type: MezzeType, substitution: Map<TypeVar, TypeVar>): MezzeType {
        return when (type) {
            is MezzeType.VarType -> {
                val newVar = substitution[type.variable]
                if (newVar != null) MezzeType.VarType(newVar) else type
            }
            is MezzeType.FunctionType -> MezzeType.FunctionType(
                paramType = applyTypeVarSubstitution(type.paramType, substitution),
                returnType = applyTypeVarSubstitution(type.returnType, substitution),
                effects = type.effects,
                // CRITICAL: Apply substitution to ability constraints too
                // This ensures that fresh type variables in the constraint are properly connected
                abilityConstraints = type.abilityConstraints.map { constraint ->
                    MezzeType.FunctionAbilityConstraint(
                        abilityName = constraint.abilityName,
                        typeArgs = constraint.typeArgs.map { applyTypeVarSubstitution(it, substitution) }
                    )
                }
            )
            is MezzeType.RecordType -> MezzeType.RecordType(
                fields = type.fields.mapValues { (_, fieldType) ->
                    applyTypeVarSubstitution(fieldType, substitution)
                },
                rowVar = type.rowVar
            )
            is MezzeType.VariantType -> MezzeType.VariantType(
                constructors = type.constructors.mapValues { (_, dataType) ->
                    dataType?.let { applyTypeVarSubstitution(it, substitution) }
                },
                rowVar = type.rowVar,
                typeName = type.typeName
            )
            is MezzeType.SeqType -> MezzeType.SeqType(
                elementType = applyTypeVarSubstitution(type.elementType, substitution)
            )
            is MezzeType.ListType -> MezzeType.ListType(
                elementType = applyTypeVarSubstitution(type.elementType, substitution)
            )
            is MezzeType.SetType -> MezzeType.SetType(
                elementType = applyTypeVarSubstitution(type.elementType, substitution)
            )
            is MezzeType.SortedMapType -> MezzeType.SortedMapType(
                keyType = applyTypeVarSubstitution(type.keyType, substitution),
                valueType = applyTypeVarSubstitution(type.valueType, substitution)
            )
            is MezzeType.SortedSetType -> MezzeType.SortedSetType(
                elementType = applyTypeVarSubstitution(type.elementType, substitution)
            )
            is MezzeType.MapType -> MezzeType.MapType(
                keyType = applyTypeVarSubstitution(type.keyType, substitution),
                valueType = applyTypeVarSubstitution(type.valueType, substitution)
            )
            is MezzeType.SuspendedType -> MezzeType.SuspendedType(
                valueType = applyTypeVarSubstitution(type.valueType, substitution),
                effects = type.effects
            )
            is MezzeType.TypeApplication -> MezzeType.TypeApplication(
                constructor = type.constructor,
                args = type.args.map { applyTypeVarSubstitution(it, substitution) }
            )
            is MezzeType.NewtypeType -> MezzeType.NewtypeType(
                type.name,
                type.constructor,
                applyTypeVarSubstitution(type.wrappedType, substitution)
            )
            // Primitive types unchanged
            is MezzeType.IntType, is MezzeType.FloatType, is MezzeType.StringType,
            is MezzeType.BoolType, is MezzeType.NatType, is MezzeType.FracType,
            is MezzeType.OrderingType, is MezzeType.BytesType, is MezzeType.ByteBufferType,
            is MezzeType.CharType, is MezzeType.EffectRow -> type
        }
    }

    /**
     * Extract type variables from a type in order.
     * Used for ability constraint generation.
     */
    private fun extractTypeVariables(type: MezzeType): List<TypeVar> {
        // For function types, we want type variables from parameters first
        return when (type) {
            is MezzeType.VarType -> listOf(type.variable)
            is MezzeType.FunctionType -> {
                extractTypeVariables(type.paramType) + extractTypeVariables(type.returnType)
            }
            is MezzeType.RecordType -> {
                type.fields.values.flatMap { extractTypeVariables(it) }
            }
            is MezzeType.VariantType -> {
                type.constructors.values.filterNotNull().flatMap { extractTypeVariables(it) }
            }
            is MezzeType.SeqType -> extractTypeVariables(type.elementType)
            is MezzeType.ListType -> extractTypeVariables(type.elementType)
            is MezzeType.SetType -> extractTypeVariables(type.elementType)
            is MezzeType.SortedMapType -> extractTypeVariables(type.keyType) + extractTypeVariables(type.valueType)
            is MezzeType.SortedSetType -> extractTypeVariables(type.elementType)
            is MezzeType.MapType -> extractTypeVariables(type.keyType) + extractTypeVariables(type.valueType)
            is MezzeType.SuspendedType -> extractTypeVariables(type.valueType)
            is MezzeType.TypeApplication -> type.args.flatMap { extractTypeVariables(it) }
            // Primitive types have no variables
            else -> emptyList()
        }
    }

    /**
     * Find an ability implementation that matches the given type arguments.
     *
     * This implements bidirectional matching from OldTypeChecker.kt lines 16246-16300:
     * - Type variables in `typeArgs` can match concrete types in implementation
     * - This enables inference: Convert Int 'a can match impl Convert for Int String
     *
     * Uses structural type matching via targetTypeAnnotations for correctness,
     * falling back to string-based matching only when annotations are not available.
     *
     * @param abilityName The ability name (e.g., "Convert")
     * @param typeArgs The type arguments from the constraint (may contain type variables)
     * @param context The constraint context (for looking up implementations)
     * @return The matched implementation's type list, or null if no match
     */
    private fun findAbilityImplementation(
        abilityName: String,
        typeArgs: List<MezzeType>,
        context: ConstraintContext
    ): List<String>? {
        // Get all implementations for this ability
        val impls = context.typingContext.getAbilityImpls(abilityName)

        if (impls.isEmpty()) {
            return null
        }

        // Find implementation that matches the type arguments using structural matching
        for (impl in impls) {
            val annotations = impl.targetTypeAnnotations

            // Prefer structural matching via targetTypeAnnotations
            if (annotations != null && annotations.size == typeArgs.size) {
                val allMatch = typeArgs.zip(annotations).all { (constraintType, annotation) ->
                    typeMatchesAnnotationBidirectional(constraintType, annotation)
                }
                if (allMatch) {
                    return impl.targetTypes
                }
            } else if (impl.targetTypes.size == typeArgs.size) {
                // Fallback to string matching only when annotations not available
                val allMatch = typeArgs.zip(impl.targetTypes).all { (constraintType, implTypeName) ->
                    typeMatchesStringBidirectional(constraintType, implTypeName)
                }
                if (allMatch) {
                    return impl.targetTypes
                }
            }
        }

        return null
    }

    /**
     * Check if a MezzeType matches a TypeAnnotation with bidirectional inference.
     * Type variables in constraintType can match any concrete type in the annotation.
     */
    private fun typeMatchesAnnotationBidirectional(constraintType: MezzeType, annotation: TypeAnnotation): Boolean {
        // Type variables can match any concrete type (bidirectional inference)
        if (constraintType is MezzeType.VarType) {
            return true
        }

        return when (annotation) {
            is IdentifierTypeAnnotation -> {
                when (annotation.name) {
                    "Int" -> constraintType is MezzeType.IntType
                    "Float" -> constraintType is MezzeType.FloatType
                    "Str" -> constraintType is MezzeType.StringType
                    "Bool" -> constraintType is MezzeType.BoolType || isBoolVariant(constraintType)
                    "Nat" -> constraintType is MezzeType.NatType
                    "Frac" -> constraintType is MezzeType.FracType
                    "Char" -> constraintType is MezzeType.CharType
                    "Bytes" -> constraintType is MezzeType.BytesType
                    "ByteBuffer" -> constraintType is MezzeType.ByteBufferType
                    "Ordering" -> constraintType is MezzeType.OrderingType
                    else -> {
                        // Type variable in annotation (e.g., 'a) - always matches
                        if (annotation.name.startsWith("'") || annotation.name.first().isLowerCase()) {
                            true
                        } else {
                            // Type constructor - match by name
                            when (constraintType) {
                                is MezzeType.TypeApplication -> constraintType.constructor == annotation.name && constraintType.args.isEmpty()
                                is MezzeType.RecordType -> constraintType.typeName == annotation.name
                                is MezzeType.VariantType -> constraintType.typeName == annotation.name
                                else -> false
                            }
                        }
                    }
                }
            }
            is TypeApplicationAnnotation -> {
                when (constraintType) {
                    is MezzeType.TypeApplication -> {
                        constraintType.constructor == annotation.constructor &&
                        constraintType.args.size == annotation.args.size &&
                        constraintType.args.zip(annotation.args).all { (argType, argAnnotation) ->
                            typeMatchesAnnotationBidirectional(argType, argAnnotation)
                        }
                    }
                    is MezzeType.SeqType -> {
                        annotation.constructor == "Seq" &&
                        annotation.args.size == 1 &&
                        typeMatchesAnnotationBidirectional(constraintType.elementType, annotation.args[0])
                    }
                    is MezzeType.ListType -> {
                        annotation.constructor == "List" &&
                        annotation.args.size == 1 &&
                        typeMatchesAnnotationBidirectional(constraintType.elementType, annotation.args[0])
                    }
                    is MezzeType.SetType -> {
                        annotation.constructor == "Set" &&
                        annotation.args.size == 1 &&
                        typeMatchesAnnotationBidirectional(constraintType.elementType, annotation.args[0])
                    }
                    is MezzeType.MapType -> {
                        annotation.constructor == "Map" &&
                        annotation.args.size == 2 &&
                        typeMatchesAnnotationBidirectional(constraintType.keyType, annotation.args[0]) &&
                        typeMatchesAnnotationBidirectional(constraintType.valueType, annotation.args[1])
                    }
                    else -> false
                }
            }
            is RecordTypeAnnotation -> {
                if (constraintType !is MezzeType.RecordType) return false
                annotation.entries.all { entry ->
                    val fieldType = constraintType.fields[entry.name]
                    fieldType != null && typeMatchesAnnotationBidirectional(fieldType, entry.type)
                }
            }
            is VariantTypeAnnotation -> {
                if (constraintType !is MezzeType.VariantType) return false
                annotation.constructors.all { ctor ->
                    val payload = constraintType.constructors[ctor.name]
                    val dataType = ctor.dataType
                    when {
                        dataType == null -> payload == null
                        payload == null -> false
                        else -> typeMatchesAnnotationBidirectional(payload, dataType)
                    }
                }
            }
            is SeqTypeAnnotation -> {
                constraintType is MezzeType.SeqType &&
                typeMatchesAnnotationBidirectional(constraintType.elementType, annotation.elementType)
            }
            is FunctionTypeAnnotation -> {
                constraintType is MezzeType.FunctionType &&
                typeMatchesAnnotationBidirectional(constraintType.paramType, annotation.paramType) &&
                typeMatchesAnnotationBidirectional(constraintType.returnType, annotation.returnType)
            }
            is QualifiedTypeAnnotation -> {
                when (constraintType) {
                    is MezzeType.TypeApplication -> {
                        constraintType.constructor == annotation.qualifiedName &&
                        constraintType.args.size == annotation.args.size &&
                        constraintType.args.zip(annotation.args).all { (argType, argAnnotation) ->
                            typeMatchesAnnotationBidirectional(argType, argAnnotation)
                        }
                    }
                    else -> false
                }
            }
            is EffectRowAnnotation -> false // Effect rows don't match concrete types
        }
    }

    /**
     * Check if a MezzeType matches a type name string with bidirectional inference.
     * Used as fallback when targetTypeAnnotations are not available.
     *
     * NOTE: This is a legacy fallback. Prefer using structured type matching
     * via MezzeType pattern matching when possible.
     */
    private fun typeMatchesStringBidirectional(constraintType: MezzeType, implTypeName: String): Boolean {
        // Type variable can match any concrete type
        if (constraintType is MezzeType.VarType) {
            return true
        }

        return when (constraintType) {
            // Primitive types: exact string match
            MezzeType.IntType -> implTypeName == "Int"
            MezzeType.FloatType -> implTypeName == "Float"
            MezzeType.StringType -> implTypeName == "Str"
            MezzeType.BoolType -> implTypeName == "Bool"
            MezzeType.NatType -> implTypeName == "Nat"
            MezzeType.FracType -> implTypeName == "Frac"
            MezzeType.CharType -> implTypeName == "Char"
            MezzeType.OrderingType -> implTypeName == "Ordering"
            MezzeType.BytesType -> implTypeName == "Bytes"
            MezzeType.ByteBufferType -> implTypeName == "ByteBuffer"

            // Type applications: match constructor or constructor_suffix pattern
            is MezzeType.TypeApplication ->
                implTypeName == constraintType.constructor ||
                implTypeName.startsWith("${constraintType.constructor}_")

            // Collection types: match "Type" or "Type_element" pattern
            // Using exact prefix + optional suffix to avoid false positives
            is MezzeType.SeqType -> matchesCollectionType(implTypeName, "Seq")
            is MezzeType.ListType -> matchesCollectionType(implTypeName, "List")
            is MezzeType.SetType -> matchesCollectionType(implTypeName, "Set")
            is MezzeType.SortedSetType -> matchesCollectionType(implTypeName, "SortedSet")
            is MezzeType.MapType -> matchesCollectionType(implTypeName, "Map")
            is MezzeType.SortedMapType -> matchesCollectionType(implTypeName, "SortedMap")

            // Structural types: exact name match
            is MezzeType.RecordType -> implTypeName == constraintType.typeName
            is MezzeType.VariantType -> implTypeName == constraintType.typeName

            else -> false
        }
    }

    /**
     * Match a collection type name with its canonical prefix.
     * Accepts "Seq", "Seq_Int", "Seq_String", etc.
     * Rejects "SeqIterator" or other types that happen to start with "Seq".
     */
    private fun matchesCollectionType(implTypeName: String, prefix: String): Boolean {
        return implTypeName == prefix ||                           // Exact match: "Seq"
               implTypeName.startsWith("${prefix}_") ||            // Element suffix: "Seq_Int"
               implTypeName.startsWith("${prefix}<")               // Generic syntax: "Seq<Int>"
    }

    /**
     * Check if a type is a Bool variant (True/False constructors).
     */
    private fun isBoolVariant(type: MezzeType): Boolean {
        if (type !is MezzeType.VariantType) return false
        val constructors = type.constructors.keys
        return constructors.size == 2 && "True" in constructors && "False" in constructors
    }

    // ========================================================================
    // VARIANTS
    // ========================================================================

    private fun generateVariantConstruction(expr: VariantConstruction, context: ConstraintContext): ConstraintResult {
        // Constructor {payload}

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
                        sourceLocation = expr.sourceLocation
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
                    context = "Variant construction ${expr.constructor}"
                )
            )

            return ConstraintResult(
                type = resultType,
                constraints = constraints
            )
        } else {
            // With payload
            val payloadResult = generate(valueExpr, context)

            // VALIDATION: Check variant constructor payload fields against type declaration
            // This validates: Red { x = 5, y = 10 } against type Color = | Red { x: Int } |
            if (valueExpr is RecordLiteral && context.typeDeclarations.isNotEmpty()) {
                // Find type declarations that have this constructor
                for ((typeName, typeDecl) in context.typeDeclarations) {
                    if (typeDecl.definition is VariantDefinition) {
                        val variantDef = typeDecl.definition as VariantDefinition
                        val constructor = variantDef.constructors.find { it.name == expr.constructor }

                        if (constructor != null && constructor.dataType is RecordTypeAnnotation) {
                            val expectedRecordType = constructor.dataType as RecordTypeAnnotation
                            val isOpen = expectedRecordType.isOpen

                            // Get fields from literal
                            val literalFields = valueExpr.entries.filterIsInstance<RecordFieldEntry>().map { it.name }.toSet()
                            val expectedFields = expectedRecordType.entries.map { it.name }.toSet()

                            // Check for closed record (no ...)
                            if (!isOpen) {
                                // Check for extra fields
                                val extraFields = literalFields - expectedFields
                                if (extraFields.isNotEmpty()) {
                                    throw TypeCheckError(
                                        message = "Variant constructor ${expr.constructor} expects exact fields ${expectedFields.sorted().joinToString(", ")} " +
                                                "but got extra field(s): ${extraFields.sorted().joinToString(", ")}",
                                        expression = expr,
                                        errorType = ErrorType.TYPE_MISMATCH
                                    )
                                }

                                // Check for missing fields
                                val missingFields = expectedFields - literalFields
                                if (missingFields.isNotEmpty()) {
                                    throw TypeCheckError(
                                        message = "Variant constructor ${expr.constructor} expects fields ${expectedFields.sorted().joinToString(", ")} " +
                                                "but missing field(s): ${missingFields.sorted().joinToString(", ")}",
                                        expression = expr,
                                        errorType = ErrorType.MISSING_FIELD
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
                    context = "Variant construction ${expr.constructor}"
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
                referencedBindings = payloadResult.referencedBindings
            )
        }
    }

    // ========================================================================
    // FUNCTIONS
    // ========================================================================

    private fun generateLambda(expr: LambdaExpression, context: ConstraintContext): ConstraintResult {
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

        when (val params = expr.parameters) {
            is UntypedParameters -> {
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
            is TypedParameters -> {
                // Typed parameters: use the annotated types
                // Check if the pattern is open (has ...)
                isOpenPattern = params.recordType.isOpen
                paramNames = params.recordType.entries.map { it.name }
                // CRITICAL: Convert annotated types using ContextBuilder instead of fresh vars!
                // This ensures parameter types like `opt: Option Int` actually use `Option Int`
                // instead of a fresh type variable.
                val contextBuilder = com.mezze.language.typechecker.context.ContextBuilder()
                paramFields = params.recordType.entries.associate { entry ->
                    entry.name to contextBuilder.convertTypeAnnotation(entry.type)
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
        val (mergedEffectInstances, effectMergeConstraints) = mergeEffectInstances(bodyResult.effectInstances)

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
                                    context = "Effect associated type '$assocName' in ${annotationInstance.name}"
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
        // The lambda's own constraints take precedence since they're explicitly specified
        val abilityConstraints = when {
            expr.abilityConstraints.isNotEmpty() -> {
                // Convert from AST FunctionAbilityConstraint to MezzeType.FunctionAbilityConstraint
                val contextBuilder = com.mezze.language.typechecker.context.ContextBuilder()
                expr.abilityConstraints.map { constraint ->
                    MezzeType.FunctionAbilityConstraint(
                        abilityName = constraint.abilityName,
                        typeArgs = constraint.typeArgs.map { typeAnn ->
                            contextBuilder.convertTypeAnnotation(typeAnn)
                        }
                    )
                }
            }
            expectedFunctionType?.abilityConstraints?.isNotEmpty() == true -> expectedFunctionType.abilityConstraints
            else -> emptyList()
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
                                    context = "Ability associated type '$assocName' in ${annotationConstraint.abilityName}"
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
            referencedBindings = bodyResult.referencedBindings
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
    private fun mergeEffectInstances(instances: Set<MezzeType.EffectInstance>): Pair<Set<MezzeType.EffectInstance>, List<Constraint>> {
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
                        val mergedType = mergeAssociatedTypes(typesForKey, constraints)
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
    private fun mergeAssociatedTypes(types: List<MezzeType>, constraints: MutableList<Constraint>): MezzeType {
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
                                context = "Merging variant constructor '$constructorName' payload"
                            )
                        )
                        constraints.add(
                            Constraint.Equality(
                                left = payloadType,
                                right = mergedPayloadVar,
                                context = "Merging variant constructor '$constructorName' payload"
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
                    isMatchCaseContext = true  // Enable variant merging
                )
            )
        }
        return mergedTypeVar
    }

    private fun generateFunctionCall(expr: FunctionCall, context: ConstraintContext): ConstraintResult {
        // function(arguments)
        // Supports:
        // 1. Full application: all parameters provided
        // 2. Partial application: subset of parameters provided (currying)
        // 3. Effectful functions: wrap result in SuspendedType

        // VALIDATION: Reject spread-only records in function calls (Bug #10 Extension)
        // Valid: func { x = 1, y = 2 } - explicit fields
        // Valid: func { ...k, x = 1 } - spread with explicit fields
        // Invalid: func { ...k } - spread-only
        if (expr.arguments is RecordLiteral) {
            val recordLiteral = expr.arguments as RecordLiteral
            val hasExplicitFields = recordLiteral.entries.any { it is RecordFieldEntry }
            val hasSpreadEntries = recordLiteral.entries.any { it is RecordSpreadEntry }

            // Reject if ONLY spread entries (no explicit fields)
            if (hasSpreadEntries && !hasExplicitFields) {
                // Get the function name for error message
                val functionName = when (expr.function) {
                    is Identifier -> (expr.function as Identifier).localName
                    else -> "function"
                }

                // Get the spread variable name for error message
                val spreadEntry = recordLiteral.entries.filterIsInstance<RecordSpreadEntry>().first()
                val spreadVarName = when (spreadEntry.expression) {
                    is Identifier -> (spreadEntry.expression as Identifier).localName
                    else -> "record"
                }

                throw TypeCheckError(
                    message = "cannot use spread-only records in function call to '$functionName'. " +
                            "Spread variable: '$spreadVarName'. " +
                            "Use explicit named arguments instead, e.g., { x = $spreadVarName.x, y = $spreadVarName.y }",
                    expression = expr,
                    errorType = ErrorType.TYPE_MISMATCH,
                    metadata = mapOf(
                        "functionName" to functionName,
                        "spreadVariable" to spreadVarName
                    )
                )
            }
        }

        // METHOD CALL RESOLUTION: Detect `value.method { args }` pattern
        // When the function is MemberAccess(receiver, methodName) and receiver is NOT an ability/effect,
        // check if methodName matches an ability operation. If so, transform into an ability method call
        // where the receiver is injected as the first argument.
        if (expr.function is MemberAccess) {
            val memberAccess = expr.function as MemberAccess
            val receiverName = when (memberAccess.receiver) {
                is Identifier -> (memberAccess.receiver as Identifier).localName
                is VariantConstruction -> (memberAccess.receiver as VariantConstruction).constructor
                else -> null
            }
            val isAbilityOrEffect = receiverName != null && receiverName.first().isUpperCase() &&
                (context.typingContext.getAbilityOperationType(receiverName) != null ||
                 context.typingContext.getEffectOperationType(receiverName) != null ||
                 context.performerToEffect.containsKey(receiverName) ||
                 BuiltInAbilities.isBuiltInAbility(receiverName))

            // If receiver is a VariantConstruction (uppercase) that's NOT a known ability/effect,
            // check if it's a known value (variant constructor like True, Some, etc.).
            // If it's not a known value either, it's likely a misspelled ability —
            // skip method resolution and let the normal error path handle it.
            val isUnknownUppercaseName = !isAbilityOrEffect &&
                memberAccess.receiver is VariantConstruction &&
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
            expr.function is Identifier &&
            (expr.function as Identifier).qualifier == null &&
            (expr.function as Identifier).localName == context.enclosingFunctionName

        // Track tail call location if detected
        val tailCallLocation = if (isSelfTailCall) expr.sourceLocation else null

        // PARTIAL APPLICATION DETECTION
        // If function type is known AND argument is a record, check for partial application
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
                        is Identifier -> (expr.function as Identifier).localName
                        else -> "function"
                    }
                    throw TypeCheckError(
                        message = "Extra field(s) '${extraFields.joinToString(", ")}' in call to '$functionName'. " +
                                "Function expects fields: ${expectedFields.sorted().joinToString(", ")}",
                        expression = expr,
                        errorType = ErrorType.TYPE_MISMATCH
                    )
                }

                // Check for partial application (some fields missing)
                if (missingFields.isNotEmpty()) {
                    // PARTIAL APPLICATION: Return function accepting remaining parameters
                    val constraints = mutableListOf<Constraint>()
                    constraints.addAll(functionResult.constraints)
                    constraints.addAll(argumentResult.constraints)

                    // Add constraints for provided fields (must match expected types)
                    for (fieldName in providedFields) {
                        val expectedType = funcParamType.fields[fieldName]
                        val providedType = argumentType.fields[fieldName]
                        if (expectedType != null && providedType != null) {
                            constraints.add(
                                Constraint.Equality(
                                    left = providedType,
                                    right = expectedType,
                                    context = "Partial application field $fieldName",
                                    sourceLocation = expr.sourceLocation
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
                    // Filter ability constraints to only keep those on type variables still present
                    // in the remaining parameters (not bound to concrete types via provided args)
                    val remainingTypeVars = collectFreeTypeVars(remainingParamType) + collectFreeTypeVars(functionType.returnType)
                    val filteredAbilityConstraints = functionType.abilityConstraints.filter { constraint ->
                        // Keep constraint if its type args contain a type variable that's still polymorphic
                        constraint.typeArgs.any { typeArg ->
                            when (typeArg) {
                                is MezzeType.VarType -> typeArg.variable in remainingTypeVars
                                else -> false
                            }
                        }
                    }

                    val partialResult = MezzeType.FunctionType(
                        paramType = remainingParamType,
                        returnType = functionType.returnType,
                        effects = functionType.effects,
                        abilityConstraints = filteredAbilityConstraints
                    )

                    return ConstraintResult(
                        type = partialResult,
                        constraints = constraints,
                        effects = functionResult.effects + argumentResult.effects,
                        effectInstances = functionResult.effectInstances + argumentResult.effectInstances,
                        performed = functionResult.performed || argumentResult.performed,
                        abilityConstraints = functionResult.abilityConstraints + argumentResult.abilityConstraints,
                        undefinedVariables = functionResult.undefinedVariables + argumentResult.undefinedVariables,
                        usedVariables = functionResult.usedVariables + argumentResult.usedVariables,
                        referencedBindings = functionResult.referencedBindings + argumentResult.referencedBindings
                    )
                }
            }
        }

        // FULL APPLICATION (or function type not yet known)
        // Check if the function type has effects (concrete or polymorphic)
        if (functionType is MezzeType.FunctionType) {
            val effectRow = functionType.effects
            // Handle effects if: has concrete effects OR effect instances OR has a row variable (polymorphic <...>) OR is performed
            if (effectRow != null && (effectRow.effects.isNotEmpty() || effectRow.effectInstances.isNotEmpty() || effectRow.rowVar != null || effectRow.performed)) {
                val constraints = mutableListOf<Constraint>()
                constraints.addAll(functionResult.constraints)
                constraints.addAll(argumentResult.constraints)

                // Argument must match parameter type
                // Extract function name for better error messages
                val functionName = when (expr.function) {
                    is Identifier -> (expr.function as Identifier).localName
                    else -> null
                }
                constraints.add(
                    Constraint.Equality(
                        left = argumentResult.type,
                        right = functionType.paramType,
                        context = "Function application argument",
                        sourceLocation = expr.sourceLocation,
                        errorContext = ErrorContext.FunctionArgument(
                            functionName = functionName,
                            argumentName = extractArgumentName(expr.arguments),
                            parameterIndex = 0
                        )
                    )
                )

                // CRITICAL FIX: Propagate effects from the function type to ConstraintResult.effects
                // This allows generatePerform to track which effects are used by the expression
                val propagatedEffects = effectRow.effects + functionResult.effects + argumentResult.effects
                // CRITICAL FIX: Also propagate effectInstances from the function type!
                // This is needed so that when a function calls another function with associated effect types
                // (like Exception with 'error), those effect instances are properly propagated and merged.
                val propagatedEffectInstances = effectRow.effectInstances + functionResult.effectInstances + argumentResult.effectInstances
                // Propagate performed flag from function type and sub-results
                val propagatedPerformed = effectRow.performed || functionResult.performed || argumentResult.performed

                // TCO: Include tail call if detected (note: effectful tail calls may need special handling)
                val baseTailCalls = functionResult.tailCalls + argumentResult.tailCalls
                val tailCalls = if (tailCallLocation != null) baseTailCalls + tailCallLocation else baseTailCalls

                // CRITICAL: Propagate ability constraints from function and arguments
                var effectfulAbilityConstraints = functionResult.abilityConstraints + argumentResult.abilityConstraints

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
                                typeArgs = constraint.typeArgs.drop(1),  // Additional type args after the constrained type
                                context = "Method-level constraint: ${constraint.abilityName} on ${constrainedType.variable.name}",
                                sourceLocation = expr.sourceLocation
                            )
                        } else null
                    }
                    effectfulAbilityConstraints = effectfulAbilityConstraints + methodLevelConstraints
                }

                // If the function has unhandled effects (suspended) or performed effects,
                // wrap in SuspendedType. This is important for top-level validation to detect <*>.
                val hasUnhandledEffects = effectRow.effects.isNotEmpty() || effectRow.effectInstances.isNotEmpty() || effectRow.rowVar != null
                val hasPerformedEffects = effectRow.performed
                val resultType = if (hasUnhandledEffects || hasPerformedEffects) {
                    // Calling an effectful function produces a SuspendedType
                    // The return value is suspended with the function's effects
                    MezzeType.SuspendedType(
                        valueType = functionType.returnType,
                        effects = effectRow
                    )
                } else {
                    // Pure function, no effects at all
                    functionType.returnType
                }

                return ConstraintResult(
                    type = resultType,
                    constraints = constraints,
                    effects = propagatedEffects,  // Propagate effects from function type
                    effectInstances = propagatedEffectInstances,  // Propagate effect instances from function type too!
                    performed = propagatedPerformed,  // Propagate performed flag
                    abilityConstraints = effectfulAbilityConstraints,  // Propagate ability constraints including method-level
                    associatedTypeConstraints = functionResult.associatedTypeConstraints + argumentResult.associatedTypeConstraints,
                    undefinedVariables = functionResult.undefinedVariables + argumentResult.undefinedVariables,
                    usedVariables = functionResult.usedVariables + argumentResult.usedVariables,
                    tailCalls = tailCalls,
                    referencedBindings = functionResult.referencedBindings + argumentResult.referencedBindings
                )
            }
        }

        // Normal function call (no effects in the function type itself)
        // BUT: the function may have tracked effects in ConstraintResult.effects
        // For example, effect operations like Log.println track effects this way
        val returnType = MezzeType.VarType(TypeVar.fresh())

        val constraints = mutableListOf<Constraint>()
        constraints.addAll(functionResult.constraints)
        constraints.addAll(argumentResult.constraints)

        // function must be a function type: argumentType -> returnType
        // Note: left = actual (what we got), right = expected (what we need)
        // The function's actual type must match a function that takes our argument type
        // Extract function name for better error messages
        val functionName = when (expr.function) {
            is Identifier -> (expr.function as Identifier).localName
            else -> null
        }
        constraints.add(
            Constraint.Equality(
                left = MezzeType.FunctionType(
                    paramType = argumentResult.type,
                    returnType = returnType
                ),
                right = functionResult.type,
                context = "Function application",
                sourceLocation = expr.sourceLocation,
                errorContext = ErrorContext.FunctionArgument(
                    functionName = functionName,
                    argumentName = extractArgumentName(expr.arguments),
                    parameterIndex = 0
                )
            )
        )

        // Collect effects from the function being called (e.g., effect operations)
        // Also collect effects from the arguments
        val collectedEffects = functionResult.effects + argumentResult.effects
        val collectedPerformed = functionResult.performed || argumentResult.performed

        // CRITICAL: Propagate ability constraints from function and arguments
        var collectedAbilityConstraints = functionResult.abilityConstraints + argumentResult.abilityConstraints
        // CRITICAL: Propagate associated type constraints from function and arguments
        val collectedAssociatedTypeConstraints = functionResult.associatedTypeConstraints + argumentResult.associatedTypeConstraints

        // CRITICAL: If the function type has ability constraints (like `with Show 'b`),
        // generate AbilityRequired constraints for them. These constraints will be unified
        // with the actual argument types through the equality constraint above.
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
                            typeArgs = constraint.typeArgs.drop(1),  // Additional type args after the constrained type
                            context = "Method-level constraint: ${constraint.abilityName} on ${constrainedType.variable.name}",
                            sourceLocation = expr.sourceLocation
                        )
                    } else null
                }
                collectedAbilityConstraints = collectedAbilityConstraints + methodLevelConstraints
            }
        }

        // TCO: Include tail call if detected
        val baseTailCalls = functionResult.tailCalls + argumentResult.tailCalls
        val tailCalls = if (tailCallLocation != null) baseTailCalls + tailCallLocation else baseTailCalls

        return ConstraintResult(
            type = returnType,
            constraints = constraints,
            effects = collectedEffects,  // Propagate effects from function and arguments
            effectInstances = functionResult.effectInstances + argumentResult.effectInstances,
            performed = collectedPerformed,  // Propagate performed flag
            abilityConstraints = collectedAbilityConstraints,  // Propagate ability constraints
            associatedTypeConstraints = collectedAssociatedTypeConstraints,  // Propagate associated type constraints
            undefinedVariables = functionResult.undefinedVariables + argumentResult.undefinedVariables,
            usedVariables = functionResult.usedVariables + argumentResult.usedVariables,
            tailCalls = tailCalls,
            referencedBindings = functionResult.referencedBindings + argumentResult.referencedBindings
        )
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

    private fun generateMatch(expr: MatchExpression, context: ConstraintContext): ConstraintResult {
        // match scrutinee { pattern1 -> expr1, pattern2 -> expr2 }

        // Step 1: Generate constraints for the scrutinee expression
        // TCO: Scrutinee is NOT in tail position
        val scrutineeResult = generate(expr.expression, context.notInTailPosition())

        val constraints = mutableListOf<Constraint>()
        constraints.addAll(scrutineeResult.constraints)
        val undefinedVars = mutableListOf<ConstraintResult.UndefinedVariable>()
        undefinedVars.addAll(scrutineeResult.undefinedVariables)
        val abilityConstraints = mutableListOf<Constraint.AbilityRequired>()
        abilityConstraints.addAll(scrutineeResult.abilityConstraints)
        val associatedTypeConstraints = mutableListOf<Constraint.AssociatedTypeResolution>()
        associatedTypeConstraints.addAll(scrutineeResult.associatedTypeConstraints)
        val effects = mutableSetOf<String>()
        effects.addAll(scrutineeResult.effects)
        val effectInstances = mutableSetOf<MezzeType.EffectInstance>()
        effectInstances.addAll(scrutineeResult.effectInstances)
        var performed = scrutineeResult.performed  // Track performed flag
        val duplicateBindings = mutableListOf<ConstraintResult.DuplicateBinding>()
        duplicateBindings.addAll(scrutineeResult.duplicateBindings)
        // TCO: Track tail calls from case bodies
        val tailCalls = mutableSetOf<SourceLocation>()
        tailCalls.addAll(scrutineeResult.tailCalls)
        // Track unused pattern bindings (warnings)
        val unusedVars = mutableListOf<ConstraintResult.UnusedVariable>()
        // Track pattern-related warnings (e.g., float literal patterns)
        val patternWarnings = mutableListOf<String>()
        // Track referenced bindings for dependency graph
        val referencedBindings = mutableSetOf<DefinitionRef.ByName>()
        referencedBindings.addAll(scrutineeResult.referencedBindings)

        // Step 2: Check for wildcards - if present, scrutinee must be open variant
        // IMPORTANT: Only check TOP-LEVEL patterns for wildcards/variables.
        // A VariablePattern inside a ConstructorPattern (like `Pair {a}`) binds a payload field,
        // not the whole variant, so it doesn't make the variant open.
        val hasWildcard = expr.cases.any { matchCase ->
            matchCase.patterns.any { pattern ->
                // Only WildcardPattern or VariablePattern at top level make the variant open
                pattern is WildcardPattern || pattern is VariablePattern
            }
        }
        if (hasWildcard) {
            // Mark the scrutinee type as must-be-open
            constraints.add(
                Constraint.MustBeOpen(
                    type = scrutineeResult.type,
                    bindingName = context.currentBindingName,
                    context = "Match has wildcard pattern - scrutinee may have additional constructors"
                )
            )
        }

        // Step 3: Process each match case
        val patternGenerator = PatternConstraintGenerator()
        val caseResultTypes = mutableListOf<MezzeType>()

        for (matchCase in expr.cases) {
            // For or-patterns (pattern1 | pattern2 | ...), all patterns must match the scrutinee type
            // We use the first pattern's bindings for the case body

            // Process first pattern to get bindings
            val firstPattern = matchCase.patterns.first()
            val patternResult = patternGenerator.generate(firstPattern, scrutineeResult.type, context, expr.sourceLocation)
            constraints.addAll(patternResult.constraints)
            patternWarnings.addAll(patternResult.warnings)

            // For additional patterns in or-pattern, verify they bind the same variables
            // and add constraints (bindings must match first pattern's)
            val firstPatternBindings = patternResult.bindings.keys
            for (pattern in matchCase.patterns.drop(1)) {
                val orPatternResult = patternGenerator.generate(pattern, scrutineeResult.type, context, expr.sourceLocation)
                constraints.addAll(orPatternResult.constraints)
                patternWarnings.addAll(orPatternResult.warnings)

                // Verify that or-pattern binds exactly the same variables
                val orPatternBindings = orPatternResult.bindings.keys
                if (orPatternBindings != firstPatternBindings) {
                    val missingInOr = firstPatternBindings - orPatternBindings
                    val extraInOr = orPatternBindings - firstPatternBindings
                    val errorParts = mutableListOf<String>()
                    if (missingInOr.isNotEmpty()) {
                        errorParts.add("missing bindings: ${missingInOr.joinToString()}")
                    }
                    if (extraInOr.isNotEmpty()) {
                        errorParts.add("extra bindings: ${extraInOr.joinToString()}")
                    }
                    throw TypeCheckError(
                        "Or-pattern alternatives must bind the same variables. ${errorParts.joinToString("; ")}",
                        expr.sourceLocation
                    )
                }

                // Also unify the binding types between patterns to ensure consistency
                for ((varName, varType) in orPatternResult.bindings) {
                    val firstType = patternResult.bindings[varName]!!
                    constraints.add(
                        Constraint.Equality(
                            left = varType,
                            right = firstType,
                            context = "Or-pattern binding '$varName' must have same type in all alternatives"
                        )
                    )
                }
            }

            // Extend context with pattern bindings for the case body
            var caseContext = context
            for ((varName, varType) in patternResult.bindings) {
                caseContext = caseContext.extend(varName, varType)
            }

            // If there's a guard, check it (must be Bool)
            // TCO: Guard is NOT in tail position
            val guard = matchCase.guard
            var guardUsedVariables: Set<String> = emptySet()  // Track for unused variable checking
            if (guard != null) {
                val guardResult = generate(guard, caseContext.notInTailPosition())
                constraints.addAll(guardResult.constraints)
                undefinedVars.addAll(guardResult.undefinedVariables)
                abilityConstraints.addAll(guardResult.abilityConstraints)
                associatedTypeConstraints.addAll(guardResult.associatedTypeConstraints)
                effects.addAll(guardResult.effects)
                effectInstances.addAll(guardResult.effectInstances)
                performed = performed || guardResult.performed  // Propagate performed
                duplicateBindings.addAll(guardResult.duplicateBindings)
                referencedBindings.addAll(guardResult.referencedBindings)
                guardUsedVariables = guardResult.usedVariables  // Save for later use

                // Guard must be Bool (variant type | True, False |)
                constraints.add(
                    Constraint.Equality(
                        left = guardResult.type,
                        right = MezzeType.VariantType(
                            constructors = mapOf("True" to null, "False" to null),
                            rowVar = null
                        ),
                        context = "Match guard must be Bool"
                    )
                )
            }

            // Process if-let bindings
            // if let Pattern = Expression extends the context and adds constraints
            for (ifLetBinding in matchCase.ifLetBindings) {
                // Generate constraints for the binding expression
                // TCO: If-let expression is NOT in tail position
                val bindingExprResult = generate(ifLetBinding.expression, caseContext.notInTailPosition())
                constraints.addAll(bindingExprResult.constraints)
                undefinedVars.addAll(bindingExprResult.undefinedVariables)
                abilityConstraints.addAll(bindingExprResult.abilityConstraints)
                associatedTypeConstraints.addAll(bindingExprResult.associatedTypeConstraints)
                effects.addAll(bindingExprResult.effects)
                effectInstances.addAll(bindingExprResult.effectInstances)  // Bug fix: was missing
                performed = performed || bindingExprResult.performed
                duplicateBindings.addAll(bindingExprResult.duplicateBindings)
                referencedBindings.addAll(bindingExprResult.referencedBindings)  // Bug fix: was missing

                // Generate pattern constraints for the if-let pattern against the expression type
                val ifLetPatternResult = patternGenerator.generate(
                    ifLetBinding.pattern,
                    bindingExprResult.type,
                    caseContext,
                    ifLetBinding.sourceLocation
                )
                constraints.addAll(ifLetPatternResult.constraints)
                patternWarnings.addAll(ifLetPatternResult.warnings)

                // Extend context with bindings from the if-let pattern
                for ((varName, varType) in ifLetPatternResult.bindings) {
                    caseContext = caseContext.extend(varName, varType)
                }
            }

            // Generate constraints for case body with extended context
            // TCO: Case body inherits tail position from context
            val caseBodyResult = generate(matchCase.expression, caseContext)
            constraints.addAll(caseBodyResult.constraints)
            undefinedVars.addAll(caseBodyResult.undefinedVariables)
            abilityConstraints.addAll(caseBodyResult.abilityConstraints)
            associatedTypeConstraints.addAll(caseBodyResult.associatedTypeConstraints)
            effects.addAll(caseBodyResult.effects)
            effectInstances.addAll(caseBodyResult.effectInstances)
            performed = performed || caseBodyResult.performed  // Propagate performed
            duplicateBindings.addAll(caseBodyResult.duplicateBindings)
            tailCalls.addAll(caseBodyResult.tailCalls)
            referencedBindings.addAll(caseBodyResult.referencedBindings)

            // Check for unused pattern bindings
            // Variables starting with _ are intentionally unused (convention)
            // Bug fix: Reuse guardUsedVariables instead of re-generating the guard
            val usedVarsInBody = caseBodyResult.usedVariables + guardUsedVariables
            for ((varName, _) in patternResult.bindings) {
                if (!varName.startsWith("_") && varName !in usedVarsInBody) {
                    // Use the binding's source location from the pattern
                    // Fall back to case body location if not available
                    val patternLocation = patternResult.bindingLocations[varName]
                        ?: matchCase.expression.sourceLocation
                        ?: expr.sourceLocation
                    unusedVars.add(
                        ConstraintResult.UnusedVariable(
                            name = varName,
                            sourceLocation = patternLocation
                        )
                    )
                }
            }

            caseResultTypes.add(caseBodyResult.type)
        }

        // Step 4: All case bodies must have the same type
        // Use a fresh type variable as the result type so variant constructors from different
        // arms can be merged through unification. Without this, the first arm's concrete type
        // would become the result type, and merging wouldn't work properly.
        val resultType = MezzeType.VarType(TypeVar.fresh())

        for (caseType in caseResultTypes) {
            constraints.add(
                Constraint.Equality(
                    left = caseType,
                    right = resultType,
                    context = "Match case must have same type as other cases",
                    isMatchCaseContext = true  // OPTIMIZATION: Use flag instead of string parsing
                )
            )
        }

        return ConstraintResult(
            type = resultType,
            constraints = constraints,
            effects = effects,
            effectInstances = effectInstances,
            performed = performed,  // Include performed flag
            abilityConstraints = abilityConstraints,
            associatedTypeConstraints = associatedTypeConstraints,
            undefinedVariables = undefinedVars,
            duplicateBindings = duplicateBindings,
            tailCalls = tailCalls,
            unusedVariables = unusedVars,
            patternWarnings = patternWarnings,
            referencedBindings = referencedBindings
        )
    }

    // ========================================================================
    // BLOCKS
    // ========================================================================

    private fun generateDo(expr: DoExpression, context: ConstraintContext): ConstraintResult {
        // do
        //     stmt1
        //     stmt2
        //     result

        // IMPORTANT: Clear expected type for intermediate statements in do block
        // The expected type should only apply to the final result expression, not
        // intermediate let bindings or expression statements. Otherwise, a type
        // annotation like `{} -> Continuation` would propagate to all sub-expressions.
        // TCO: Statements are NOT in tail position
        var currentContext = context.clearExpectedType().notInTailPosition()
        val constraints = mutableListOf<Constraint>()
        val undefinedVars = mutableListOf<ConstraintResult.UndefinedVariable>()
        val effects = mutableSetOf<String>()
        val effectInstances = mutableSetOf<MezzeType.EffectInstance>()
        var performed = false  // Track performed flag
        val abilityConstraints = mutableListOf<Constraint.AbilityRequired>()
        val associatedTypeConstraints = mutableListOf<Constraint.AssociatedTypeResolution>()
        // TCO: Track tail calls from result expression (statements are not in tail position)
        val tailCalls = mutableSetOf<SourceLocation>()
        // Track referenced bindings for dependency graph
        val referencedBindings = mutableSetOf<DefinitionRef.ByName>()
        // Track used variables for unused variable warnings
        val usedVars = mutableSetOf<String>()

        // Create pattern generator for handling destructuring in let statements
        val patternGenerator = PatternConstraintGenerator()

        // Process statements
        for (stmt in expr.statements) {
            when (stmt) {
                is LetStatement -> {
                    // BIDIRECTIONAL TYPING: If there's a type annotation, use it as expected type
                    // This is CRITICAL for polymorphic abilities like Decode to work correctly
                    // (e.g., `let user: Result {name: String} DecodeError = Decode.decode {...}`)
                    val annotatedType = stmt.typeAnnotation?.let { annotation ->
                        val contextBuilder = com.mezze.language.typechecker.context.ContextBuilder()
                        contextBuilder.convertTypeAnnotation(annotation)
                    }

                    // Generate constraints for the expression, with expected type if annotated
                    val exprContext = if (annotatedType != null) {
                        currentContext.withExpectedType(annotatedType)
                    } else {
                        currentContext
                    }
                    val exprResult = generate(stmt.expression, exprContext)
                    constraints.addAll(exprResult.constraints)
                    undefinedVars.addAll(exprResult.undefinedVariables)
                    effects.addAll(exprResult.effects)
                    effectInstances.addAll(exprResult.effectInstances)
                    performed = performed || exprResult.performed  // Propagate performed
                    abilityConstraints.addAll(exprResult.abilityConstraints)
                    associatedTypeConstraints.addAll(exprResult.associatedTypeConstraints)
                    referencedBindings.addAll(exprResult.referencedBindings)
                    usedVars.addAll(exprResult.usedVariables)

                    // CRITICAL: If there's a type annotation, add equality constraint to unify
                    // the expression type with the annotated type. This ensures type variables
                    // in the expression type get resolved.
                    if (annotatedType != null) {
                        constraints.add(
                            Constraint.Equality(
                                left = exprResult.type,
                                right = annotatedType,
                                context = "Type annotation on let binding in do block",
                                sourceLocation = stmt.expression.sourceLocation
                            )
                        )
                    }

                    // CRITICAL: Inside a do block, unwrap SuspendedType for let bindings.
                    // Data types (Result, Option, etc.) are orthogonal to effects.
                    // When we write `let res = effectfulFunc {}`, we want `res` to be the
                    // unwrapped value type, not Suspended<T>. The effects are tracked
                    // separately in the effects set and will be handled by perform.
                    val bindingType = when (val t = exprResult.type) {
                        is MezzeType.SuspendedType -> t.valueType
                        else -> t
                    }

                    // Generate pattern constraints
                    // The pattern must match the unwrapped expression type
                    val patternResult = patternGenerator.generate(
                        stmt.pattern,
                        bindingType,
                        currentContext,
                        stmt.expression.sourceLocation
                    )
                    constraints.addAll(patternResult.constraints)

                    // Extend context with all pattern bindings
                    // For simple variable bindings: extends with one binding
                    // For destructuring patterns: extends with multiple bindings
                    val typingContext = currentContext.typingContext

                    // LET-POLYMORPHISM: If the user explicitly declares a polymorphic type annotation
                    // with ability constraints, we should create a Polymorphic binding.
                    // Example: let encode_value: {x: 'a} -> String with Encoder 'a = ...
                    // The user is explicitly telling us this should be polymorphic.
                    val contextBindings = if (stmt.pattern is VariablePattern &&
                        stmt.typeAnnotation is FunctionTypeAnnotation &&
                        (stmt.typeAnnotation as FunctionTypeAnnotation).abilityConstraints.isNotEmpty()
                    ) {
                        // User explicitly declared ability constraints - create Polymorphic binding
                        val annotation = stmt.typeAnnotation as FunctionTypeAnnotation
                        val contextBuilder = com.mezze.language.typechecker.context.ContextBuilder()
                        val annotatedType = contextBuilder.convertTypeAnnotation(annotation)

                        // Collect type variables from the annotation
                        val typeVars = com.mezze.language.typechecker.context.collectFreeTypeVars(annotatedType)

                        // Convert annotation's ability constraints to AbilityConstraint
                        // FunctionAbilityConstraint has typeArgs - find the type variable(s) among them
                        // For Encode Json 'a: typeArgs = [Json, 'a], typeParam = 'a, allTypeArgs = [Json, 'a]
                        val abilityConstraints = annotation.abilityConstraints.mapNotNull { constraint ->
                            // Find the first type variable in the type args - that's the constrained parameter
                            // For multi-param abilities like Encode Json 'a, 'a is the type var
                            val typeVarArg = constraint.typeArgs.find { arg ->
                                arg is IdentifierTypeAnnotation &&
                                (arg.name.startsWith("'") || arg.name.first().isLowerCase())
                            }
                            val typeParamName = when (typeVarArg) {
                                is IdentifierTypeAnnotation -> typeVarArg.name
                                else -> null
                            }
                            if (typeParamName != null) {
                                // Convert ALL type args to MezzeTypes, preserving order
                                // For Encode Json 'a, this gives us [Json, 'a]
                                val allTypeArgs = constraint.typeArgs.map { typeArg ->
                                    contextBuilder.convertTypeAnnotation(typeArg)
                                }
                                AbilityConstraint(
                                    abilityName = constraint.abilityName,
                                    typeParam = typeParamName,
                                    typeArgs = allTypeArgs
                                )
                            } else null
                        }

                        val scheme = com.mezze.language.typechecker.context.TypeScheme(
                            quantifiedVars = typeVars.toList(),
                            constraints = abilityConstraints,
                            type = annotatedType
                        )

                        patternResult.bindings.map { (name, _) ->
                            BindingKey.unqualified(name) to TypingContext.Binding.Polymorphic(scheme)
                        }.toMap()
                    } else {
                        // Regular monomorphic binding
                        patternResult.bindings.map { (name, type) ->
                            BindingKey.unqualified(name) to TypingContext.Binding.Monomorphic(type)
                        }.toMap()
                    }

                    val extendedTypingContext = typingContext.extendAll(contextBindings)
                    currentContext = currentContext.copy(typingContext = extendedTypingContext)

                    // CRITICAL: Track record literal bindings (matches OldTypeChecker.kt:1428-1429)
                    // If this is a simple variable binding to a RecordLiteral, track it
                    // This enables field access validation like OldTypeChecker
                    if (stmt.pattern is VariablePattern && stmt.expression is RecordLiteral) {
                        val varName = (stmt.pattern as VariablePattern).name
                        currentContext = currentContext.withRecordLiteralBinding(varName)
                    }
                }
                is ExpressionStatement -> {
                    val exprResult = generate(stmt.expression, currentContext)
                    constraints.addAll(exprResult.constraints)
                    undefinedVars.addAll(exprResult.undefinedVariables)
                    effects.addAll(exprResult.effects)
                    effectInstances.addAll(exprResult.effectInstances)
                    performed = performed || exprResult.performed  // Propagate performed
                    abilityConstraints.addAll(exprResult.abilityConstraints)
                    associatedTypeConstraints.addAll(exprResult.associatedTypeConstraints)
                    referencedBindings.addAll(exprResult.referencedBindings)
                    usedVars.addAll(exprResult.usedVariables)
                }
            }
        }

        // Process result expression - restore expected type from the original context
        // The result expression is what should match the expected type (e.g., Continuation)
        // TCO: Result expression inherits tail position from the outer context
        val resultContext = if (context.expectedType != null) {
            currentContext.withExpectedType(context.expectedType).copy(inTailPosition = context.inTailPosition)
        } else {
            currentContext.copy(inTailPosition = context.inTailPosition)
        }
        val resultResult = generate(expr.result, resultContext)
        constraints.addAll(resultResult.constraints)
        undefinedVars.addAll(resultResult.undefinedVariables)
        effects.addAll(resultResult.effects)
        effectInstances.addAll(resultResult.effectInstances)
        performed = performed || resultResult.performed  // Propagate performed
        abilityConstraints.addAll(resultResult.abilityConstraints)
        associatedTypeConstraints.addAll(resultResult.associatedTypeConstraints)
        // TCO: Only the result expression can have tail calls
        tailCalls.addAll(resultResult.tailCalls)
        referencedBindings.addAll(resultResult.referencedBindings)
        usedVars.addAll(resultResult.usedVariables)

        // CRITICAL: If do block has effects or performed effects, wrap the result in SuspendedType
        // This allows perform expressions to properly detect and handle effects
        // BUT: Don't double-wrap if the result is already a SuspendedType!
        // Data types (like Result) are orthogonal to effects - we should not nest Suspended types.
        val resultType = if (effects.isNotEmpty() || performed) {
            when (val innerType = resultResult.type) {
                is MezzeType.SuspendedType -> {
                    // Result is already suspended - merge effects and performed flag
                    val mergedEffects = innerType.effects.effects + effects
                    val mergedPerformed = innerType.effects.performed || performed
                    MezzeType.SuspendedType(innerType.valueType, MezzeType.EffectRow.simple(mergedEffects, innerType.effects.rowVar, mergedPerformed))
                }
                else -> {
                    MezzeType.SuspendedType(resultResult.type, MezzeType.EffectRow.simple(effects, null, performed))
                }
            }
        } else {
            resultResult.type
        }

        return ConstraintResult(
            type = resultType,
            constraints = constraints,
            effects = effects,  // Still track effects for propagation
            effectInstances = effectInstances,
            performed = performed,  // Include performed flag
            abilityConstraints = abilityConstraints,
            associatedTypeConstraints = associatedTypeConstraints,
            undefinedVariables = undefinedVars,
            tailCalls = tailCalls,
            usedVariables = usedVars,
            referencedBindings = referencedBindings
        )
    }

    // ========================================================================
    // EFFECTS
    // ========================================================================

    private fun generatePerform(expr: PerformExpression, context: ConstraintContext): ConstraintResult {
        // perform Handler in expr
        // The handler handles a specific effect, removing it from the expression's effects

        // CRITICAL: Pass the perform context to child expressions
        // This allows effect operations to substitute associated types from the performer
        // Matches OldTypeChecker.kt lines 7353-7354
        val contextWithPerform = context.copy(performContext = expr)
        val exprResult = generate(expr.expression, contextWithPerform)

        // Map each handler name to the effect it handles
        // VALIDATION: Check that all handlers are defined performers
        // Note: Performers imported from modules might not be in performerToEffect,
        // so we check context as a fallback. If not found in either, throw an error.
        val handlerEffects = mutableListOf<Pair<String, String>>()
        for (handler in expr.handlers) {
            val handlerName = handler.performer.localName
            val effectName = context.performerToEffect[handlerName]
            if (effectName != null) {
                handlerEffects.add(handlerName to effectName)
            } else {
                // Handler not in performerToEffect - check if it exists in context
                // Performers can be imported from modules where they might not be tracked
                // Check using dedicated performer map
                val handlerLookup = context.typingContext.lookup(handlerName)
                val performerInfos = context.typingContext.getPerformerTypeInfosByName(handlerName)

                if (handlerLookup is TypingContext.LookupResult.Found || performerInfos.isNotEmpty()) {
                    // Performer exists in context (imported), allow it but we can't track effect
                    // Skip adding to handlerEffects - imported performers are permissive
                } else {
                    // Not in performerToEffect and not in context - truly unknown
                    throw TypeCheckError(
                        message = "Unknown performer '${handlerName}'. Performer has not been defined with 'impl Effect for ${handlerName}'.",
                        expression = expr,
                        errorType = ErrorType.UNDEFINED_VARIABLE
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
                    expression = expr,
                    errorType = ErrorType.CONSTRAINT_VIOLATION
                )
            }
        }

        // VALIDATION 2: Check that handlers are meaningful
        // Skip validation for nested performs in certain cases:
        // - Inner performs may have handlers that PRODUCE effects (e.g., FileLogger adds FileSystem)
        //   which need to be handled by outer performs. We can't track handler-added effects statically.
        // However, we CAN validate when effects are embedded in the result TYPE (e.g., SuspendedType)
        val isNestedPerform = expr.expression is PerformExpression

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
            // If the expression has no effects (neither tracked nor in type), providing handlers is meaningless
            if (handlerEffects.isNotEmpty() && allEffects.isEmpty()) {
                // Expression is pure but handlers provided
                val handlerNames = handlerEffects.map { it.first }.joinToString(", ")
                throw TypeCheckError(
                    message = "Expression is pure (has no effects), but handlers were provided: $handlerNames. " +
                        "Remove the perform block or use an expression that actually uses effects.",
                    expression = expr,
                    errorType = ErrorType.CONSTRAINT_VIOLATION
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
                        expression = expr,
                        errorType = ErrorType.CONSTRAINT_VIOLATION
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
                            expression = expr,
                            errorType = ErrorType.CONSTRAINT_VIOLATION
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
            val handlerParams = handler.parameters
            if (handlerParams != null) {
                val performerFields = performerInfo.performerFields
                if (performerFields is MezzeType.RecordType) {
                    val actualParamsResult = generate(handlerParams, context)
                    val actualParamsType = actualParamsResult.type

                    // Generate constraint: actual params = expected fields
                    continuationConstraints.add(
                        Constraint.Equality(
                            left = actualParamsType,
                            right = performerFields,
                            context = "Handler parameters must match performer field types",
                            sourceLocation = handlerParams.sourceLocation
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
                        sourceLocation = expr.sourceLocation
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
            undefinedVariables = exprResult.undefinedVariables,
            usedVariables = exprResult.usedVariables,
            referencedBindings = exprResult.referencedBindings
        )
    }

    /**
     * Result of extracting effects from a type.
     * Contains both concrete effect names and whether effects are polymorphic.
     */
    private data class ExtractedEffects(
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
    private fun extractEffectsFromType(type: MezzeType): ExtractedEffects {
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
    private fun hasPerformedMarkerInType(type: MezzeType): Boolean {
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
     * Extract Continuation type parameters from a type.
     *
     * Continuation 'suspend 'resume 'result is a type alias that expands to a record type.
     * This function detects if a type is a Continuation and extracts its type parameters.
     *
     * At the constraint generation phase, Continuation types appear as TypeApplication,
     * e.g., TypeApplication("Continuation", ['suspend, 'resume, 'result])
     *
     * Returns Triple(suspendType, resumeType, resultType) if the type is a Continuation,
     * null otherwise.
     */
    private fun extractContinuationType(type: MezzeType): Triple<MezzeType, MezzeType, MezzeType>? {
        // Handle TypeApplication case - this is how Continuation appears before expansion
        // TypeApplication("Continuation", [suspendType, resumeType, resultType])
        if (type is MezzeType.TypeApplication) {
            // Check if it's a Continuation type (handle both qualified and unqualified names)
            // Use structured path comparison instead of string endsWith
            val constructorPath = ModulePath.parse(type.constructor)
            val continuationPath = ModulePath(listOf("Continuation"))
            val isContinuation = constructorPath == continuationPath ||
                constructorPath.endsWith(continuationPath)

            if (isContinuation && type.args.size == 3) {
                // Args order: 'suspend, 'resume, 'result
                return Triple(type.args[0], type.args[1], type.args[2])
            }
            return null
        }

        // Handle expanded RecordType case - for cases where the type alias was already expanded
        if (type !is MezzeType.RecordType) return null

        // Look for suspend_with field - this is the key field that tells us about 'suspend
        // Continuation is defined as:
        // type Continuation 'suspend 'resume 'result = {
        //     suspend_with: { value: 'suspend } -> 'result,
        //     ...
        // }
        val suspendWithField = type.fields["suspend_with"]
        if (suspendWithField !is MezzeType.FunctionType) return null

        // suspend_with: { value: 'suspend } -> 'result
        val paramType = suspendWithField.paramType
        if (paramType !is MezzeType.RecordType) return null

        val valueField = paramType.fields["value"]
        if (valueField == null) return null

        // Extract 'suspend from the value field
        val suspendType = valueField

        // Extract 'result from the return type
        val resultType = suspendWithField.returnType

        // Try to extract 'resume from the suspend field: {} -> 'resume
        val suspendField = type.fields["suspend"]
        val resumeType = if (suspendField is MezzeType.FunctionType) {
            suspendField.returnType
        } else {
            MezzeType.VarType(TypeVar.fresh())  // Fallback if not found
        }

        return Triple(suspendType, resumeType, resultType)
    }

    /**
     * Remove specific effects from a type.
     *
     * For FunctionType with effects: Remove effects from effect row
     * For SuspendedType with effects: Remove effects from effect row, returning pure value if all removed
     */
    private fun removeEffectsFromType(type: MezzeType, effectsToRemove: Set<String>): MezzeType {
        return when (type) {
            is MezzeType.FunctionType -> {
                val effects = type.effects
                if (effects == null) {
                    // No effects to remove, but mark as performed since we're in a perform block
                    MezzeType.FunctionType(
                        paramType = type.paramType,
                        returnType = type.returnType,
                        effects = MezzeType.EffectRow.simple(emptySet(), null, true),  // Mark as performed
                        abilityConstraints = type.abilityConstraints
                    )
                } else {
                    // Remove the handled effects, mark as performed
                    val remainingEffects = effects.effects - effectsToRemove

                    // Always set performed = true since we're handling effects
                    MezzeType.FunctionType(
                        paramType = type.paramType,
                        returnType = type.returnType,
                        effects = MezzeType.EffectRow.simple(remainingEffects, effects.rowVar, true),
                        abilityConstraints = type.abilityConstraints
                    )
                }
            }
            is MezzeType.SuspendedType -> {
                // Remove effects from suspended type, mark as performed
                val remainingEffects = type.effects.effects - effectsToRemove

                // Keep SuspendedType with performed=true even when all effects are handled
                // This allows top-level validation to reject values with <*> marker
                MezzeType.SuspendedType(
                    valueType = type.valueType,
                    effects = MezzeType.EffectRow.simple(remainingEffects, type.effects.rowVar, true)
                )
            }
            else -> {
                // Other types don't have effects
                type
            }
        }
    }

    private fun generateEffectCall(expr: EffectCallExpression, context: ConstraintContext): ConstraintResult {
        // EffectCallExpression represents effect operation calls like Effect.operation(args)
        // The effectName and operationName are already parsed out

        val effectName = expr.operation.qualifier?.fullPath ?: ""
        val operationName = expr.operation.localName

        // Look up effect type from context using dedicated map
        val effectType = context.typingContext.getEffectOperationType(effectName)
        if (effectType == null) {
            // Effect not found - return error with fresh type var
            return ConstraintResult(
                type = MezzeType.VarType(TypeVar.fresh()),
                constraints = emptyList(),
                effects = setOf(effectName),
                undefinedVariables = listOf(ConstraintResult.UndefinedVariable(effectName, expr.sourceLocation))
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
                expression = expr,
                errorType = ErrorType.MISSING_FIELD
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
                sourceLocation = expr.sourceLocation
            )
            instantiatedOperationType.returnType to listOf(paramConstraint)
        } else {
            // Operation is not a function (unusual but possible for thunks)
            instantiatedOperationType to emptyList()
        }

        // Create EffectInstance with associated types bound from the argument
        // For Exception.throw { error = FakeError {...} }, we need to bind 'error to FakeError
        val effectDecl = context.effectDeclarations[effectName]
        // DEBUG:
        println("DEBUG generateEffectCall: effectName=$effectName, effectDecl=$effectDecl, keys=${context.effectDeclarations.keys}")
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

        val effectInstances = if (effectInstance != null) setOf(effectInstance) else emptySet<MezzeType.EffectInstance>()

        return ConstraintResult(
            type = resultType,
            constraints = argResult.constraints + additionalConstraints,
            effects = setOf(effectName),
            effectInstances = effectInstances + argResult.effectInstances,  // Include effect instances!
            undefinedVariables = argResult.undefinedVariables
        )
    }

    // ========================================================================
    // STATE MACHINES
    // ========================================================================

    private fun generateStateMachine(expr: StateMachineExpression, context: ConstraintContext): ConstraintResult {
        // State machines are compiler-generated continuation transformations.
        // The type of a state machine is determined by its CompleteState expressions.
        // We need to:
        // 1. Type-check all computations in ComputeState nodes
        // 2. Type-check all yield expressions in YieldState nodes
        // 3. Find all CompleteState nodes and unify their result types

        val allConstraints = mutableListOf<Constraint>()
        val allEffects = mutableSetOf<String>()
        val undefinedVars = mutableListOf<ConstraintResult.UndefinedVariable>()

        // Track result types from CompleteStates to unify them
        val resultTypes = mutableListOf<MezzeType>()

        for (state in expr.states) {
            when (state) {
                is ComputeState -> {
                    // Type check the computation expression
                    val computeResult = generate(state.computation, context)
                    allConstraints.addAll(computeResult.constraints)
                    allEffects.addAll(computeResult.effects)
                    undefinedVars.addAll(computeResult.undefinedVariables)
                }
                is YieldState -> {
                    // Type check the yield value expression
                    val yieldResult = generate(state.valueExpression, context)
                    allConstraints.addAll(yieldResult.constraints)
                    allEffects.addAll(yieldResult.effects)
                    undefinedVars.addAll(yieldResult.undefinedVariables)
                }
                is CompleteState -> {
                    // Type check the result expression and track its type
                    val completeResult = generate(state.resultExpression, context)
                    allConstraints.addAll(completeResult.constraints)
                    allEffects.addAll(completeResult.effects)
                    undefinedVars.addAll(completeResult.undefinedVariables)
                    resultTypes.add(completeResult.type)
                }
            }
        }

        // Determine the state machine's result type
        val resultType = if (resultTypes.isEmpty()) {
            // No complete states - use a fresh type variable (will be constrained by context)
            MezzeType.VarType(TypeVar.fresh())
        } else if (resultTypes.size == 1) {
            resultTypes.first()
        } else {
            // Multiple complete states - unify all result types
            val unifiedType = MezzeType.VarType(TypeVar.fresh())
            for (rt in resultTypes) {
                allConstraints.add(Constraint.Equality(
                    left = unifiedType,
                    right = rt,
                    context = "State machine branches must have same type",
                    sourceLocation = expr.sourceLocation
                ))
            }
            unifiedType
        }

        return ConstraintResult(
            type = resultType,
            constraints = allConstraints,
            effects = allEffects,
            undefinedVariables = undefinedVars
        )
    }

    // ========================================================================
    // HELPER FUNCTIONS
    // ========================================================================

    /**
     * Convert TypeAnnotation to MezzeType for validation purposes only.
     * This is a simple conversion used for type alias field validation.
     */
    private fun convertFieldType(annotation: TypeAnnotation): MezzeType {
        return when (annotation) {
            is IdentifierTypeAnnotation -> {
                // Check primitive types first (includes Bool -> MezzeType.BoolType)
                PrimitiveTypes.fromName(annotation.name)?.let { return it }

                // Type variable or type constructor
                if (annotation.name.first().isLowerCase() || annotation.name.startsWith("'")) {
                    MezzeType.VarType(TypeVar(annotation.name))
                } else {
                    MezzeType.TypeApplication(annotation.name, emptyList())
                }
            }
            is RecordTypeAnnotation -> {
                MezzeType.RecordType(
                    fields = annotation.entries.associate { entry ->
                        entry.name to convertFieldType(entry.type)
                    },
                    rowVar = if (annotation.isOpen) RowVar.userOpen() else null
                )
            }
            is FunctionTypeAnnotation -> {
                MezzeType.FunctionType(
                    paramType = convertFieldType(annotation.paramType),
                    returnType = convertFieldType(annotation.returnType),
                    effects = annotation.effects?.let {
                        // Validate hermetic effects (e.g., Mutation cannot coexist with other effects)
                        // Use allEffectNames to include both simple effects and effect annotations
                        ContextBuilder.validateHermeticEffects(it.allEffectNames)
                        MezzeType.EffectRow.simple(it.effects.toSet(), null, it.isPerformed)
                    }
                )
            }
            is TypeApplicationAnnotation -> {
                MezzeType.TypeApplication(
                    constructor = annotation.constructor,
                    args = annotation.args.map { convertFieldType(it) }
                )
            }
            is VariantTypeAnnotation -> {
                MezzeType.VariantType(
                    constructors = annotation.constructors.associate { ctor ->
                        ctor.name to ctor.dataType?.let { convertFieldType(it) }
                    },
                    rowVar = if (annotation.isOpen) RowVar.userOpen() else null,
                    // Use synthetic typeName for closed inline annotations to enable validation.
                    // This makes `let x: | Red, Blue | = Green` fail because Green is not in the annotation.
                    // Open annotations don't need this since they explicitly allow additional constructors.
                    typeName = if (!annotation.isOpen) "<inline>" else null
                )
            }
            is SeqTypeAnnotation -> {
                MezzeType.SeqType(
                    elementType = convertFieldType(annotation.elementType)
                )
            }
            is EffectRowAnnotation -> {
                MezzeType.EffectRow(annotation.effects.toSet())
            }
            is QualifiedTypeAnnotation -> {
                MezzeType.TypeApplication(
                    constructor = annotation.qualifiedName,
                    args = annotation.args.map { convertFieldType(it) }
                )
            }
        }
    }

    /**
     * Check if an expression is an access chain on a record literal binding.
     *
     * This recursively checks:
     * - Direct identifier: `data` where `data` is in recordLiteralBindings
     * - Nested access: `data.point` where `data` is in recordLiteralBindings
     * - Deep nested: `data.point.x` etc.
     *
     * Used to determine if field access should be strict (no accumulation).
     */
    private fun isRecordLiteralAccess(expr: Expression, context: ConstraintContext): Boolean {
        return when (expr) {
            is Identifier -> expr.toBindingKey().toKeyString() in context.recordLiteralBindings
            is MemberAccess -> isRecordLiteralAccess(expr.receiver, context)
            else -> false
        }
    }

    /**
     * Find the closest matching name from a set of candidates using Levenshtein distance.
     * Returns null if no close match is found (distance > threshold).
     * Delegates to shared StringDistance utility.
     */
    private fun findClosestMatch(name: String, candidates: Set<String>, threshold: Int = 3): String? {
        if (candidates.isEmpty()) return null
        val similar = StringDistance.findSimilar(name, candidates.toList(), threshold, maxResults = 1)
        return similar.firstOrNull()
    }

    /**
     * Extract a descriptive name for function arguments.
     *
     * For record literals, returns the first field name or a combined description.
     * For identifiers, returns the identifier name.
     * For other expressions, returns null.
     */
    private fun extractArgumentName(expr: Expression): String? {
        return when (expr) {
            is RecordLiteral -> {
                val fieldNames = expr.entries.mapNotNull { entry ->
                    when (entry) {
                        is RecordFieldEntry -> entry.name
                        else -> null  // RecordSpreadEntry doesn't have a name
                    }
                }
                when {
                    fieldNames.isEmpty() -> null
                    fieldNames.size == 1 -> fieldNames.first()
                    fieldNames.size <= 3 -> "{${fieldNames.joinToString(", ")}}"
                    else -> "{${fieldNames.take(3).joinToString(", ")}, ...}"
                }
            }
            is Identifier -> expr.localName
            else -> null
        }
    }
}
