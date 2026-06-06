package com.mezze.language.typechecker.constraints

import com.mezze.language.ast.*
import com.mezze.language.parsedast.*
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
import com.mezze.language.parsedast.diagnostics.ErrorCode


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
     * Types of let bindings inside do blocks, collected during constraint generation.
     * Keyed by binding name, value is the type variable (before substitution).
     * Used to resolve types of nested bindings
     * that don't have inferredType set on their expressions (e.g., effect calls).
     */
    val nestedBindingTypes = mutableMapOf<String, MezzeType>()

    /**
     * Generate constraints for an expression.
     *
     * Guarantees: the returned ConstraintResult.type is NEVER a SuspendedType.
     * Any effects wrapped in a SuspendedType are extracted into the result's
     * effects/effectInstances/performed fields. Downstream code (constraint
     * solver, unifier) never sees SuspendedType.
     */
    fun generate(expr: ParsedExpr, context: ConstraintContext): ConstraintResult {
        val raw = generateImpl(expr, context)
        return when (val t = raw.type) {
            is MezzeType.SuspendedType -> raw.copy(
                type = t.valueType,
                effects = raw.effects + t.effects.effects,
                effectInstances = raw.effectInstances + t.effects.effectInstances,
                performed = raw.performed || t.effects.performed
            )
            else -> raw
        }
    }

    private fun generateImpl(expr: ParsedExpr, context: ConstraintContext): ConstraintResult {
        return when (expr) {
            // Literals - simple types, no constraints
            is ParsedIntLit -> generateIntLiteral(expr, context)
            is ParsedNatLit -> generateNatLiteral(expr, context)
            is ParsedFloatLit -> generateFloatLiteral(expr, context)
            is ParsedFracLit -> generateFractionLiteral(expr, context)
            is ParsedStringLit -> generateStringLiteral(expr, context)
            is ParsedSeqLit -> generateSeqLiteral(expr, context)
            is ParsedCharLit -> ConstraintResult.simple(MezzeType.CharType)

            // Variables - lookup in context
            is ParsedIdentifier -> generateIdentifier(expr, context)

            // Pipe placeholder - should not reach constraint generation (desugared earlier)
            is ParsedPipePlaceholder -> generatePipePlaceholder(expr, context)

            // Pipe expression - should not reach constraint generation (desugared earlier)
            is ParsedPipe -> generatePipeExpression(expr, context)

            // Parenthesized expression - just delegate to inner
            is ParsedParens -> generate(expr.expr, context)

            // Records
            is ParsedRecordLit -> generateRecordLiteral(expr, context)
            is ParsedMemberAccess -> generateMemberAccess(expr, context)

            // Variants
            is ParsedVariantConstruct -> generateVariantConstruction(expr, context)

            // Functions
            is ParsedLambda -> generateLambda(expr, context)
            is ParsedFunctionCall -> generateFunctionCall(expr, context)

            // Control flow
            // ParsedIf is unified with ParsedMatch at the type-checking level.
            // This simplifies the typechecker (one code path for all conditionals) while
            // preserving ParsedIf in the AST for tooling and IfNode in the interpreter
            // for optimal runtime performance.
            is ParsedIf -> generateMatch(expr.toMatchExpr(), context)
            is ParsedMatch -> generateMatch(expr, context)

            // Blocks
            is ParsedDo -> generateDo(expr, context)

            // Operators — desugar to method calls for type checking
            is ParsedBinaryOp -> generateBinaryOp(expr, context)
            is ParsedUnaryOp -> generateUnaryOp(expr, context)

            // Effects
            is ParsedPerform -> generatePerform(expr, context)
            is ParsedEffectCall -> generateEffectCall(expr, context)
        }
    }

    // ========================================================================
    // CONSTRAINT COLLECTOR — eliminates repeated merge boilerplate
    // ========================================================================

    /**
     * Mutable accumulator for sub-expression constraint results.
     *
     * Many generator functions create 8-11 mutable collections, then call
     * `addAll`/`putAll` on each one for every sub-expression result. This
     * class centralises that pattern: call [merge] for each sub-result,
     * then [toResult] to build the final [ConstraintResult].
     */
    private inner class ConstraintCollector {
        val constraints = mutableListOf<Constraint>()
        val undefinedVars = mutableListOf<ConstraintResult.UndefinedVariable>()
        val effects = mutableSetOf<String>()
        val effectInstances = mutableSetOf<MezzeType.EffectInstance>()
        var performed = false
        val abilityConstraints = mutableListOf<Constraint.AbilityRequired>()
        val associatedTypeConstraints = mutableListOf<Constraint.AssociatedTypeResolution>()
        val usedVars = mutableSetOf<String>()
        val referencedBindings = mutableSetOf<DefinitionRef.ByName>()
        val methodCallResolutions = mutableMapOf<Pair<Loc, String>, ConstraintResult.MethodCallInfo>()
        val callReturnTypes = mutableMapOf<Loc, MezzeType>()
        val duplicateBindings = mutableListOf<ConstraintResult.DuplicateBinding>()
        val tailCalls = mutableSetOf<Loc>()

        fun merge(result: ConstraintResult) {
            constraints.addAll(result.constraints)
            undefinedVars.addAll(result.undefinedVariables)
            effects.addAll(result.effects)
            effectInstances.addAll(result.effectInstances)
            performed = performed || result.performed
            abilityConstraints.addAll(result.abilityConstraints)
            associatedTypeConstraints.addAll(result.associatedTypeConstraints)
            usedVars.addAll(result.usedVariables)
            referencedBindings.addAll(result.referencedBindings)
            methodCallResolutions.putAll(result.methodCallResolutions)
            callReturnTypes.putAll(result.callReturnTypes)
            duplicateBindings.addAll(result.duplicateBindings)
            tailCalls.addAll(result.tailCalls)
        }

        fun toResult(type: MezzeType): ConstraintResult = ConstraintResult(
            type = type,
            constraints = constraints,
            effects = effects,
            effectInstances = effectInstances,
            performed = performed,
            abilityConstraints = abilityConstraints,
            associatedTypeConstraints = associatedTypeConstraints,
            undefinedVariables = undefinedVars,
            usedVariables = usedVars,
            referencedBindings = referencedBindings,
            methodCallResolutions = methodCallResolutions,
            callReturnTypes = callReturnTypes,
            duplicateBindings = duplicateBindings,
            tailCalls = tailCalls,
        )
    }

    /**
     * Generate constraints for a binary operator by desugaring to method call.
     * `x + y` → `x.add {r = y}` — but with better error messages that reference the operator.
     */
    private fun generateBinaryOp(expr: ParsedBinaryOp, context: ConstraintContext): ConstraintResult {
        val op = com.mezze.language.parser.OperatorMapping.getBinaryOp(expr.op)
            ?: throw com.mezze.language.typechecker.TypeCheckError(
                "Unknown operator: ${expr.op}", expr.loc,
                code = com.mezze.language.parsedast.diagnostics.ErrorCode.T0401_UNKNOWN_OPERATOR,
                hints = listOf("binary operators desugar to method calls; check that `${expr.op}` is a known operator"),
            )

        // Desugar to method call: x + y → x.add {r = y}
        val desugared = ParsedFunctionCall(
            function = ParsedMemberAccess(expr.left, op.method, expr.loc),
            arguments = ParsedRecordLit(listOf(ParsedRecordEntry.Field("r", expr.right, expr.loc)), expr.loc),
            loc = expr.loc
        )

        // Mark as operator for error message enrichment
        com.mezze.language.ast.SyntaxMetadata.markAsOperator(expr.loc, expr.op)

        return generateFunctionCall(desugared, context)
    }

    /**
     * Generate constraints for a unary operator by desugaring to method call.
     * `-x` → `x.neg {}`, `!x` → `x.not {}`
     */
    private fun generateUnaryOp(expr: ParsedUnaryOp, context: ConstraintContext): ConstraintResult {
        val op = com.mezze.language.parser.OperatorMapping.getUnaryOp(expr.op)
            ?: throw com.mezze.language.typechecker.TypeCheckError(
                "Unknown operator: ${expr.op}", expr.loc,
                code = com.mezze.language.parsedast.diagnostics.ErrorCode.T0401_UNKNOWN_OPERATOR,
                hints = listOf("unary operators desugar to method calls; check that `${expr.op}` is a known operator"),
            )

        // Desugar to method call: -x → x.neg {}
        val desugared = ParsedFunctionCall(
            function = ParsedMemberAccess(expr.operand, op.method, expr.loc),
            arguments = ParsedRecordLit(emptyList(), expr.loc),
            loc = expr.loc
        )

        com.mezze.language.ast.SyntaxMetadata.markAsOperator(expr.loc, expr.op)

        return generateFunctionCall(desugared, context)
    }

    /** Convert ParsedIf to ParsedMatch for unified type-checking. */
    private fun ParsedIf.toMatchExpr(): ParsedMatch = ParsedMatch(
        scrutinee = condition,
        cases = listOf(
            ParsedMatchCase(
                patterns = listOf(ParsedConstructorPattern("True", null, loc)),
                body = thenBranch,
                loc = loc
            ),
            ParsedMatchCase(
                patterns = listOf(ParsedConstructorPattern("False", null, loc)),
                body = elseBranch,
                loc = loc
            )
        ),
        loc = loc
    )

    // ========================================================================
    // LITERALS
    // ========================================================================

    private fun generateIntLiteral(expr: ParsedIntLit, context: ConstraintContext): ConstraintResult {
        // Int literal has type Int, no constraints
        return ConstraintResult.simple(MezzeType.IntType)
    }

    private fun generateNatLiteral(expr: ParsedNatLit, context: ConstraintContext): ConstraintResult {
        // Nat literal has type Nat, no constraints
        return ConstraintResult.simple(MezzeType.NatType)
    }

    private fun generateFloatLiteral(expr: ParsedFloatLit, context: ConstraintContext): ConstraintResult {
        // Float literal has type Float, no constraints
        return ConstraintResult.simple(MezzeType.FloatType)
    }

    private fun generateFractionLiteral(expr: ParsedFracLit, context: ConstraintContext): ConstraintResult {
        // Fraction literal has type Frac, no constraints
        return ConstraintResult.simple(MezzeType.FracType)
    }

    private fun generateStringLiteral(expr: ParsedStringLit, context: ConstraintContext): ConstraintResult {
        // Check if this is a simple (non-interpolated) string
        val isInterpolated = expr.parts.any { it is ParsedStringPart.Interpolation }
        if (!isInterpolated) {
            // Simple string has type String, no constraints
            return ConstraintResult.simple(MezzeType.StringType)
        }

        // Interpolated string: each Interpolation part's expression must have type String
        val collector = ConstraintCollector()

        var interpolationIndex = 0
        for (part in expr.parts) {
            when (part) {
                is ParsedStringPart.Literal -> {
                    // String parts are fine, no constraints
                }
                is ParsedStringPart.Interpolation -> {
                    // Expression must have type String
                    val exprResult = generate(part.expr, context)
                    collector.merge(exprResult)
                    collector.constraints.add(
                        Constraint.Equality(
                            left = exprResult.type,
                            right = MezzeType.StringType,
                            context = "String interpolation requires String type",
                            loc = expr.loc,
                            errorContext = ErrorContext.StringInterpolation(interpolationIndex)
                        )
                    )
                    interpolationIndex++
                }
            }
        }

        return collector.toResult(MezzeType.StringType)
    }

    private fun generateSeqLiteral(expr: ParsedSeqLit, context: ConstraintContext): ConstraintResult {
        if (expr.elements.isEmpty()) {
            // Empty seq: [element] where element is fresh type variable
            val elementType = MezzeType.VarType(TypeVar.fresh())
            return ConstraintResult.simple(MezzeType.SeqType(elementType))
        }

        // Non-empty seq: all elements must have same type
        // Handle both regular elements and spread elements
        val elementType = MezzeType.VarType(TypeVar.fresh())
        val collector = ConstraintCollector()

        var elementIndex = 0
        for (element in expr.elements) {
            when (element) {
                is ParsedSeqElement.Regular -> {
                    val result = generate(element.expr, context)
                    collector.merge(result)
                    // generate() guarantees no SuspendedType — effects are in result.effects
                    collector.constraints.add(
                        Constraint.Equality(
                            left = result.type,
                            right = elementType,
                            context = "Seq elements must have same type",
                            loc = element.expr.loc,
                            errorContext = ErrorContext.SeqElement(elementIndex)
                        )
                    )
                    elementIndex++
                }
                is ParsedSeqElement.Spread -> {
                    val result = generate(element.expr, context)
                    collector.merge(result)
                    collector.constraints.add(
                        Constraint.Equality(
                            left = result.type,
                            right = MezzeType.SeqType(elementType),
                            context = "Spread element must be a seq",
                            loc = element.expr.loc,
                            errorContext = ErrorContext.SeqElement(elementIndex)
                        )
                    )
                    elementIndex++
                }
            }
        }

        return collector.toResult(MezzeType.SeqType(elementType))
    }

    // ========================================================================
    // VARIABLES
    // ========================================================================

    private fun generateIdentifier(expr: ParsedIdentifier, context: ConstraintContext): ConstraintResult {
        val lookupKey = BindingKey(qualifier = expr.qualifier?.segments?.lastOrNull(), localName = expr.localName)
        val lookupKeyString = lookupKey.toKeyString()  // For usedVariables, DefinitionRef, etc.

        // Look up variable in typing context
        return when (val result = context.typingContext.lookup(lookupKey)) {
            is TypingContext.LookupResult.Found -> {
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
                            val abilityHash = context.abilityDeclarations[freshConstraint.abilityName]?.contentHash
                            Constraint.AbilityRequired(
                                typeVar = freshConstraint.typeVar,
                                abilityName = freshConstraint.abilityName,
                                abilityHash = abilityHash,
                                typeArgs = freshConstraint.typeArgs,
                                context = "Polymorphic instantiation of '${lookupKeyString}'",
                                loc = expr.loc
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
                                loc = expr.loc,
                                qualifier = expr.qualifier?.let { ModulePath(it.segments) },
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
                            context = "Variant constructor ${expr.localName}",
                            loc = expr.loc,
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
                                loc = expr.loc,
                                qualifier = expr.qualifier?.let { ModulePath(it.segments) },
                                isQualified = expr.isQualified
                            )
                        )
                    )
                }
            }
        }
    }

    private fun generatePipePlaceholder(expr: ParsedPipePlaceholder, context: ConstraintContext): ConstraintResult {
        // PipePlaceholder should be desugared before constraint generation
        // If we reach here, it means @ was used outside of a pipe expression
        throw com.mezze.language.typechecker.TypeCheckError(
            "'@' can only be used inside a pipe expression (|>).\n\n" +
            "  hint: Use '@' to refer to the piped value:\n" +
            "        value |> func { arg = @ }",
            expr.loc,
            code = com.mezze.language.parsedast.diagnostics.ErrorCode.T0402_PIPE_PLACEHOLDER_MISUSE,
            hints = listOf("`@` is only valid in pipe rhs: `x |> f { arg = @ }`"),
        )
    }

    private fun generatePipeExpression(expr: ParsedPipe, context: ConstraintContext): ConstraintResult {
        // PipeExpression should be desugared before constraint generation
        // If we reach here, there's a bug in the transformation phase
        throw com.mezze.language.typechecker.TypeCheckError(
            "Internal error: PipeExpression was not desugared before type checking. " +
            "This is a compiler bug.",
            expr.loc,
            code = ErrorCode.I0001_UNKNOWN_TYPECHECK_FAILURE,
        )
    }

    // ========================================================================
    // RECORDS
    // ========================================================================

    private fun generateRecordLiteral(expr: ParsedRecordLit, context: ConstraintContext): ConstraintResult {
        // {field1 = expr1, field2 = expr2, ...}
        // or {...spread, field1 = expr1, ...}

        val fields = linkedMapOf<String, MezzeType>()  // Preserve insertion order
        val collector = ConstraintCollector()

        // Pre-pass: collect ALL explicit field names upfront
        // This is needed because spread validation needs to know all field names,
        // even if they appear after the spread in source order
        val explicitFieldNames = expr.entries
            .filterIsInstance<ParsedRecordEntry.Field>()
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
                is ParsedRecordEntry.Field -> {
                    val fieldValue = entry.value ?: ParsedIdentifier(localName = entry.name, loc = entry.loc)
                    val fieldResult = generate(fieldValue, fieldContext)

                    // If the field value is a SuspendedType (effectful value), unwrap it:
                    // - Use the inner valueType for the field type (effects are evaluated, not stored)
                    // - Propagate the effects upward to the record expression
                    // IMPORTANT: Propagate BOTH effects AND effectInstances from the SuspendedType
                    val fieldType = when (val t = fieldResult.type) {
                        is MezzeType.SuspendedType -> {
                            collector.effects.addAll(t.effects.effects)  // Propagate simple effects from SuspendedType
                            collector.effectInstances.addAll(t.effects.effectInstances)  // Propagate effect instances (with associated types)
                            t.valueType  // Use the unwrapped value type
                        }
                        else -> t
                    }

                    explicitFieldTypes[entry.name] = fieldType
                    collector.merge(fieldResult)
                }
                is ParsedRecordEntry.Spread -> {
                    // Spread: {...baseRecord}
                    // Process the spread expression and extract its fields
                    val spreadResult = generate(entry.expr, fieldContext)
                    collector.merge(spreadResult)

                    // Extract fields from the spread type and merge into our record
                    // The spread expression must be a record type (or unify with one via constraints)
                    // Also handle type aliases (TypeApplication) by resolving to underlying RecordType
                    val resolvedSpreadType = when (val spreadType = spreadResult.type) {
                        is MezzeType.RecordType -> spreadType
                        is MezzeType.TypeApplication -> {
                            // Resolve type alias to underlying record type
                            val typeDecl = context.typeDeclarations[spreadType.constructor]
                            if (typeDecl != null && typeDecl.definition is ParsedRecordDef) {
                                val recordDef = typeDecl.definition as ParsedRecordDef
                                val resolvedFields = recordDef.fields.associate { field ->
                                    field.name to convertParsedFieldType(field.type)
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
                            // Spread type is not yet known — constrain it to be a record (open)
                            val spreadRecordType = MezzeType.RecordType(
                                fields = emptyMap(),
                                rowVar = RowVar.fresh()
                            )
                            collector.constraints.add(
                                Constraint.Equality(
                                    left = spreadResult.type,
                                    right = spreadRecordType,
                                    context = "Spread expression must be a record type",
                                    loc = entry.expr.loc
                                )
                            )
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
            if (typeDecl != null && typeDecl.definition is ParsedRecordDef) {
                val recordDef = typeDecl.definition as ParsedRecordDef
                // Convert ParsedRecordDef to RecordType (for validation purposes only)
                val fields = recordDef.fields.associate { field ->
                    field.name to convertParsedFieldType(field.type)
                }
                val resolvedType = MezzeType.RecordType(
                    fields = fields,
                    rowVar = if (recordDef.isOpen) RowVar("rest") else null
                )

                // Validate record literal fields against the type declaration
                val allFieldNames = fields.keys

                // Check for missing declared fields (required for BOTH open and closed records)
                val missingFields = resolvedType.fields.keys - allFieldNames
                if (missingFields.isNotEmpty()) {
                    val missingList = missingFields.joinToString(", ")
                    throw TypeCheckError(
                        message = "Record is missing ${if (missingFields.size == 1) "field" else "fields"}: $missingList. " +
                                "Expected fields: ${resolvedType.fields.keys.sorted().joinToString(", ")}",
                        loc = expr.loc,
                        errorType = ErrorType.MISSING_FIELD,
                        metadata = mapOf(
                            "missingFields" to missingFields.toList(),
                            "allFieldNames" to allFieldNames.toList()
                        ),
                        code = ErrorCode.T0202_RECORD_MISSING_FIELDS,
                    )
                }

                // Check for extra fields (only for CLOSED records — open records allow extras)
                if (resolvedType.rowVar == null) {
                    val extraFields = allFieldNames - resolvedType.fields.keys
                    if (extraFields.isNotEmpty()) {
                        val extraList = extraFields.joinToString(", ")
                        val expectedList = resolvedType.fields.keys.sorted().joinToString(", ")
                        throw TypeCheckError(
                            message = "Record has extra ${if (extraFields.size == 1) "field" else "fields"}: $extraList. " +
                                    "Remove ${if (extraFields.size == 1) "it" else "them"}. Expected fields: $expectedList",
                            loc = expr.loc,
                            errorType = ErrorType.TYPE_MISMATCH,
                            metadata = mapOf(
                                "extraFields" to extraFields.toList(),
                                "expectedFields" to resolvedType.fields.keys.toList()
                            ),
                            code = ErrorCode.T0203_RECORD_EXTRA_FIELDS,
                        )
                    }
                }
            }
        }

        val recordType = MezzeType.RecordType(
            fields = fields,
            // ParsedRecordLit has no isOpen field — open record syntax not captured at parse level
            rowVar = null
        )

        return collector.toResult(recordType)
    }

    /**
     * Compute the Loc pointing only at the `.member` portion of a
     * member-access expression, so a Rename FixHint can replace just the
     * field name. `expr.loc` covers the whole `receiver.member` span,
     * which would replace the receiver too. We derive the member span
     * by walking back `member.length` columns from the expression's end.
     */
    private fun memberAccessFieldLoc(expr: ParsedMemberAccess): Loc {
        val len = expr.member.length
        val startCol = (expr.loc.endCol - len + 1).coerceAtLeast(1)
        return Loc(
            file = expr.loc.file,
            startLine = expr.loc.endLine,
            startCol = startCol,
            endLine = expr.loc.endLine,
            endCol = expr.loc.endCol,
        )
    }

    private fun generateMemberAccess(expr: ParsedMemberAccess, context: ConstraintContext): ConstraintResult {
        // receiver.member

        // Special case: Check if this is an ability method access
        // Example: Show.show, NumAdd.add, Eq.eq, etc.

        // Get receiver name (works for both ParsedIdentifier and ParsedVariantConstruct)
        val receiverName = when (expr.receiver) {
            is ParsedIdentifier -> (expr.receiver as ParsedIdentifier).localName
            is ParsedVariantConstruct -> (expr.receiver as ParsedVariantConstruct).constructor
            else -> null
        }

        if (receiverName != null) {
            // Direct Ability.method access — return the FULL method type (with `it` param).
            // Example: Eq.eq returns {it: 'a, r: 'a} -> Bool
            // Check both: built-in abilities AND user-defined/stdlib abilities (which may
            // shadow the built-in due to transitive collection from stdlib modules).
            if (BuiltInAbilities.isBuiltInAbility(receiverName)) {
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
                            abilityHash = context.abilityDeclarations[receiverName]?.contentHash,
                            typeArgs = emptyList(),
                            context = "Direct ability call: ${receiverName}.${expr.member}",
                            loc = expr.loc
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
                        loc = expr.loc,
                        errorType = ErrorType.MISSING_FIELD,
                        code = ErrorCode.T0206_METHOD_NOT_FOUND,
                    )
                }
            }

            // Check if this is a user-defined ability (using dedicated map)
            val abilityOperationType = context.typingContext.getAbilityOperationType(receiverName)
            if (abilityOperationType != null) {
                throw TypeCheckError(
                    message = "Direct ability access '${receiverName}.${expr.member}' is not allowed. " +
                        "Use 'value.${expr.member} { args }' or '${receiverName}.${expr.member} { it = value, ... }' instead.",
                    loc = expr.loc,
                    errorType = ErrorType.MISSING_FIELD,
                    code = ErrorCode.T0209_ABILITY_AS_VALUE,
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
                        loc = expr.loc,
                        errorType = ErrorType.UNDEFINED_VARIABLE,
                        code = ErrorCode.V0702_UNDEFINED_VARIABLE,
                    )
                }
            }
        }

        // Normal case: receiver must be record with 'member' field
        val receiverResult = generate(expr.receiver, context)
        val fieldType = MezzeType.VarType(TypeVar.fresh())

        // Unwrap SuspendedType from receiver — effects are tracked
        // separately and propagated to the outer ConstraintResult below.
        // Without this, the HasField constraint binds against the
        // SuspendedType wrapper rather than the inner record, and the
        // field type drifts to a wrong shape.
        val receiverValueType = when (val t = receiverResult.type) {
            is MezzeType.SuspendedType -> t.valueType
            else -> t
        }

        // CRITICAL: Validate field access on record literals (matches OldTypeChecker.kt:6260-6310)
        // Check if field exists for:
        // 1. Identifiers bound to record literals (e.g., let x = {a = 1}; x.b)
        // 2. Nested field access where intermediate type is a known RecordType (e.g., data.point.z)

        // First, determine if we should validate field existence
        val shouldValidate = if (expr.receiver is ParsedIdentifier) {
            // For identifiers, only validate if bound to a record literal
            val rcvr = expr.receiver as ParsedIdentifier
            val rcvrKey = BindingKey(qualifier = rcvr.qualifier?.segments?.lastOrNull(), localName = rcvr.localName).toKeyString()
            rcvrKey in context.recordLiteralBindings
        } else if (expr.receiver is ParsedMemberAccess) {
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
                            is ParsedRecordDef -> def.fields.map { it.name }.toSet() to def.isOpen
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
                // Build a Rename FixHint pointing at the misspelled field's
                // loc so the LSP can offer a one-click "Replace with `x`"
                // quick fix in addition to the "Did you mean" hint in the
                // message text. Pin to the member name only (expr.loc covers
                // the whole `receiver.member` span which is too wide).
                val memberLoc = memberAccessFieldLoc(expr)
                val renameHints: List<com.mezze.language.parsedast.diagnostics.FixHint> =
                    if (suggestion != null) listOf(
                        com.mezze.language.parsedast.diagnostics.FixHint.Rename(memberLoc, listOf(suggestion))
                    ) else emptyList()

                if (!isOpen) {
                    // Closed record - field must exist, throw error
                    throw TypeCheckError(
                        message = "Field '${expr.member}' not found in record.$didYouMean Available fields: ${fieldNames.joinToString()}",
                        loc = expr.loc,
                        errorType = ErrorType.MISSING_FIELD,
                        metadata = mapOf(
                            "field" to expr.member,
                            "availableFields" to fieldNames.toList()
                        ),
                        code = ErrorCode.T0205_FIELD_NOT_FOUND,
                        fixHints = renameHints,
                    )
                } else {
                    // Open record with explicit literal - still fail
                    // OldTypeChecker validates this too (line 6285-6300)
                    throw TypeCheckError(
                        message = "Field '${expr.member}' not found in open record.$didYouMean Explicitly declared fields: ${fieldNames.joinToString()}",
                        loc = expr.loc,
                        errorType = ErrorType.MISSING_FIELD,
                        metadata = mapOf(
                            "field" to expr.member,
                            "availableFields" to fieldNames.toList()
                        ),
                        code = ErrorCode.T0303_OPEN_RECORD_FIELD_MISSING,
                        fixHints = renameHints,
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

        // Add HasField constraint — use the unwrapped value type so the
        // solver matches the field against the underlying record, not the
        // SuspendedType wrapper.
        constraints.add(
            Constraint.HasField(
                recordType = receiverValueType,
                fieldName = expr.member,
                fieldType = fieldType,
                context = "Field access ${expr.member}",
                loc = expr.loc,
                strictFieldCheck = isStrictFieldCheck,
            )
        )

        // Propagate the receiver's effects to the outer ConstraintResult.
        // Without this, `(Foo.run {}).a` loses the <Foo> effect entirely
        // (the receiver's ConstraintResult carries it, but the member
        // access used to drop it on the floor).
        return ConstraintResult(
            type = fieldType,
            constraints = constraints + receiverResult.constraints,
            effects = receiverResult.effects,
            effectInstances = receiverResult.effectInstances,
            performed = receiverResult.performed,
            abilityConstraints = receiverResult.abilityConstraints,
            associatedTypeConstraints = receiverResult.associatedTypeConstraints,
            undefinedVariables = receiverResult.undefinedVariables,
            usedVariables = receiverResult.usedVariables,
            referencedBindings = receiverResult.referencedBindings,
            methodCallResolutions = receiverResult.methodCallResolutions,
            callReturnTypes = receiverResult.callReturnTypes
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
        val strippedParams = params.filterKeys { it != Keywords.IT }
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
        expr: ParsedFunctionCall,
        memberAccess: ParsedMemberAccess,
        context: ConstraintContext
    ): ConstraintResult? {
        val methodName = memberAccess.member

        // FIELD ACCESS: If the receiver is known to have a field with this name,
        // it's plain field access — skip method call resolution.
        if (memberAccess.receiver is ParsedIdentifier) {
            val receiverVarName = (memberAccess.receiver as ParsedIdentifier).localName
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

        // If ONLY inherent methods match (no ability methods), handle them directly.
        if (matchingAbilities.isEmpty() && builtInMatches.isEmpty() && inherentMethods.isNotEmpty()) {
            // Closed-record method-call inference (issue #216): when there are
            // multiple candidate inherent methods AND the receiver's type is
            // still a free TypeVar, DON'T speculatively pick by load order
            // (the previous behaviour silently mis-dispatched, e.g. picking
            // Str.length over Seq.length for `{ xs } -> xs.length {}`).
            // Returning null here falls through to plain field-access
            // constraint generation, which infers `xs.length` as access into
            // a closed record `{ length: arg -> 'r }`.
            //
            // When there's exactly ONE candidate, there's no ambiguity even
            // with an unresolved receiver — keep dispatching.
            if (inherentMethods.size > 1 && isReceiverUnresolved(memberAccess.receiver, context)) {
                // When the receiver's type isn't yet inferred, try a syntactic
                // shortcut: if it's a variant constructor like `Some {val=42}`
                // or `Ok {val=42}`, look up which type that constructor belongs
                // to and filter candidates by that. Fixes the case where the
                // ambiguity is statically resolvable from the surface syntax.
                val syntacticTypeName = lookupConstructorTypeName(memberAccess.receiver, context)
                if (syntacticTypeName != null) {
                    val filtered = inherentMethods.filter { it.targetTypeName == syntacticTypeName }
                    if (filtered.isNotEmpty()) {
                        return generateInherentMethodCall(expr, memberAccess, filtered, context)
                    }
                }
                // #184: when one candidate is concrete (its `it` slot has no
                // free TyVars) and the others are generic (e.g.
                // `impl Result 'a 'e where { is_ok }`), prefer the concrete
                // one. The user almost certainly meant the impl that targets
                // a specific type, not the polymorphic catch-all on the
                // outer variant. If the receiver later turns out NOT to be
                // that concrete type, unification will fail loudly — better
                // than silently dispatching to a generic impl that always
                // type-checks.
                val concrete = inherentMethods.filter { mi ->
                    val fnType = mi.methodType as? MezzeType.FunctionType
                    val itType = (fnType?.paramType as? MezzeType.RecordType)?.fields?.get(Keywords.IT)
                    itType != null && getFreeTypeVariables(itType).isEmpty()
                }
                if (concrete.size == 1) {
                    return generateInherentMethodCall(expr, memberAccess, concrete, context)
                }
                return null
            }
            // #188: when the receiver IS concrete but anonymous (a record literal
            // or unannotated let-binding to one), filter by structurally-matching
            // type aliases in scope. Without this, multiple inherent candidates
            // (e.g. Point.sum vs Seq.sum on an anonymous `{x:Int, y:Int}`) fall
            // through to the all-candidates dispatch below, which unifies
            // against the wrong impl's it slot ("[Int] vs {x:Int, y:Int}").
            if (inherentMethods.size > 1) {
                val concreteReceiver = getReceiverConcreteType(memberAccess.receiver, context)
                val candidateAliases: Set<String> = when {
                    concreteReceiver is MezzeType.RecordType && concreteReceiver.typeName == null ->
                        findMatchingRecordTypeAliases(concreteReceiver, context).toSet()
                    concreteReceiver is MezzeType.VariantType && concreteReceiver.typeName == null ->
                        findMatchingVariantTypeAliases(concreteReceiver, context).toSet()
                    else -> emptySet()
                }
                if (candidateAliases.isNotEmpty()) {
                    val filtered = inherentMethods.filter { it.targetTypeName in candidateAliases }
                    if (filtered.isNotEmpty()) {
                        return generateInherentMethodCall(expr, memberAccess, filtered, context)
                    }
                }
            }
            return generateInherentMethodCall(expr, memberAccess, inherentMethods, context)
        }

        // Receiver-driven preference (issue #229): the operator-desugar
        // built-in abilities (NumAdd, NumMul, …) share method names with
        // collection inherent impls (Seq.add). When the receiver's concrete
        // type has an inherent method matching the call shape, prefer it.
        // Without this, `nums.add {x=4}` (nums : [Int]) routes to NumAdd.add
        // expecting `{r}` and fires a cascading "[Int] does not implement
        // NumAdd" error.
        //
        // Originally guarded on `matchingAbilities.isEmpty()` — fine when
        // operator abilities lived only in BuiltInAbilities. After #306
        // those abilities also reach the regular ability registry. The
        // guard now also fires on user-defined abilities — but only if
        // the inherent's parameter shape matches the call-site arguments.
        // Otherwise (e.g. `[1,2,3].set {i=0, v=42}` against a user ability
        // `Mutable.set` whose param is {it, i, v}), the user's intent is
        // the ability impl, not the unrelated Seq.set inherent.
        if (inherentMethods.isNotEmpty() &&
            (builtInMatches.isNotEmpty() || matchingAbilities.isNotEmpty())) {
            val concreteReceiver = getReceiverConcreteType(memberAccess.receiver, context)
            if (concreteReceiver != null) {
                // #188: when the receiver is an anonymous record/variant with
                // no typeName, fall back to a structural lookup against the
                // in-scope type aliases. Without this, `let p = { x = 3, y =
                // 4 }; p.method {}` can't disambiguate impl Point vs other
                // same-name inherent impls (Seq.sum, etc.) — receiverTypeName
                // is null, the matching filter drops to empty, and the
                // wrong impl wins.
                val receiverTypeNames: Set<String> = when {
                    getReceiverTypeName(concreteReceiver) != null ->
                        setOf(getReceiverTypeName(concreteReceiver)!!)
                    concreteReceiver is MezzeType.RecordType && concreteReceiver.typeName == null ->
                        findMatchingRecordTypeAliases(concreteReceiver, context).toSet()
                    concreteReceiver is MezzeType.VariantType && concreteReceiver.typeName == null ->
                        findMatchingVariantTypeAliases(concreteReceiver, context).toSet()
                    else -> emptySet()
                }
                val matchingInherent = inherentMethods.filter { it.targetTypeName in receiverTypeNames }
                // Compute call-site field names locally (the cached value is
                // declared further down). Field-shape filter: keep only
                // inherents whose param record matches the call-site argument
                // record. Without this, an unrelated inherent with the same
                // name but different signature shadows a user ability
                // designed for that exact concrete type.
                val localCallSiteFields: Set<String>? = when (val args = expr.arguments) {
                    is ParsedRecordLit -> args.entries.filterIsInstance<ParsedRecordEntry.Field>().map { it.name }.toSet()
                    else -> null
                }
                val shapeMatched = if (localCallSiteFields != null) {
                    matchingInherent.filter { info ->
                        // InherentMethodInfo's `methodType` storage shape
                        // varies: some paths register the param record WITH
                        // an `it` receiver field; others (when the impl op
                        // declares its param annotation explicitly) leave it
                        // out. Compare both shapes.
                        val pf = (info.methodType as? MezzeType.FunctionType)
                            ?.paramType
                            ?.let { it as? MezzeType.RecordType }
                            ?.fields?.keys ?: return@filter true
                        pf == localCallSiteFields || (pf - Keywords.IT) == localCallSiteFields
                    }
                } else matchingInherent
                if (shapeMatched.isNotEmpty()) {
                    return generateInherentMethodCall(expr, memberAccess, shapeMatched, context)
                }
            }
        }

        // Generate constraints for the receiver expression
        val receiverResult = generate(memberAccess.receiver, context.notInTailPosition())

        // Generate constraints for the arguments
        val argumentResult = generate(expr.arguments, context.clearExpectedType().notInTailPosition())

        // Resolve to the matching ability and get the receiver-stripped method type
        val strippedMethodType: MezzeType.FunctionType
        val receiverConstraintType: MezzeType  // The type the receiver must match (first field's type)
        var resolvedAbilityName: String? = null  // Tracks which ability was resolved for MethodCallInfo
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
            is ParsedRecordLit -> args.entries.filterIsInstance<ParsedRecordEntry.Field>().map { entry -> entry.name }.toSet()
            else -> null
        }
        val fieldFilteredAbilities = if (callSiteFieldNames != null && matchingAbilities.isNotEmpty() && builtInMatches.isNotEmpty()) {
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

        // When the enclosing function annotation declared specific ability
        // constraints, prefer those abilities over coincidentally-matching ones
        // (e.g. user-defined Container.size vs prelude Sized.size). #304.
        val filteredMatchingAbilities = if (
            fieldFilteredAbilities.size > 1 &&
            context.annotationAbilityNames.isNotEmpty()
        ) {
            val annotationMatches = fieldFilteredAbilities.filter { (n, _) ->
                n in context.annotationAbilityNames
            }
            if (annotationMatches.isNotEmpty()) annotationMatches else fieldFilteredAbilities
        } else {
            fieldFilteredAbilities
        }

        if (builtInMatches.size == 1 && filteredMatchingAbilities.isEmpty()) {
            // Built-in ability — method type from .mz files includes `it` param.
            // Strip `it` for method-call syntax (receiver is handled separately).
            val abilityName = builtInMatches.first()
            resolvedAbilityName = abilityName
            val freshTypeVar = MezzeType.VarType(TypeVar.fresh())
            val fullMethodType = BuiltInAbilities.getAbilityMethodType(abilityName, methodName, freshTypeVar)!!

            if (fullMethodType !is MezzeType.FunctionType) return null

            // Strip receiver param from param record — call-site only has the other params
            val fullParams = (fullMethodType.paramType as? MezzeType.RecordType)?.fields ?: emptyMap()
            val strippedParams = fullParams.filterKeys { it != Keywords.IT }
            strippedMethodType = MezzeType.FunctionType(
                paramType = MezzeType.RecordType(fields = strippedParams, rowVar = null),
                returnType = fullMethodType.returnType,
                effects = fullMethodType.effects
            )

            receiverConstraintType = freshTypeVar

            abilityConstraints.add(Constraint.AbilityRequired(
                typeVar = freshTypeVar.variable,
                abilityName = abilityName,
                abilityHash = context.abilityDeclarations[abilityName]?.contentHash,
                typeArgs = emptyList(),
                context = "Method call: $methodName",
                loc = expr.loc
            ))
        } else if (filteredMatchingAbilities.isNotEmpty()) {
            // User-defined ability
            val (abilityName, rawMethodType) = filteredMatchingAbilities.first()
            resolvedAbilityName = abilityName
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
                    abilityHash = abilityDecl?.contentHash,
                    typeArgs = typeArgs,
                    context = "Method call: $methodName",
                    loc = expr.loc
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
                            loc = expr.loc
                        ))
                    }
                }
            }

            // Handle method-level constraints
            if (instantiatedMethodType.abilityConstraints.isNotEmpty()) {
                for (constraint in instantiatedMethodType.abilityConstraints) {
                    val constrainedType = constraint.typeArgs.firstOrNull()
                    if (constrainedType is MezzeType.VarType) {
                        // The constrainedType.variable may be an un-instantiated TypeVar from the
                        // ability method's stored type (e.g., TypeVar("a", id=0) from ContextBuilder).
                        // If so, use originalToFresh to get the proper fresh TypeVar that is connected
                        // to the receiver/annotation TypeVars through the constraint solver.
                        // This handles the case where the ability type param only appears in
                        // abilityConstraints (not in param/return types), so instantiation missed it.
                        val normalizedName = constrainedType.variable.name.removePrefix("'")
                        val freshTypeVar = originalToFresh[normalizedName] ?: constrainedType.variable
                        abilityConstraints.add(Constraint.AbilityRequired(
                            typeVar = freshTypeVar,
                            abilityName = constraint.abilityName,
                            abilityHash = context.abilityDeclarations[constraint.abilityName]?.contentHash,
                            typeArgs = constraint.typeArgs.drop(1),
                            context = "Method-level constraint: ${constraint.abilityName} on $abilityName.$methodName",
                            loc = expr.loc
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
            loc = expr.loc
        ))

        // Constrain: explicit args = stripped method's param type
        constraints.add(Constraint.Equality(
            left = argumentResult.type,
            right = strippedMethodType.paramType,
            context = "Method call arguments for $methodName",
            loc = expr.loc
        ))

        // Constrain: result type = method's return type
        constraints.add(Constraint.Equality(
            left = resultType,
            right = strippedMethodType.returnType,
            context = "Method call return type",
            loc = expr.loc
        ))

        // Propagate effects declared by the ability method's arrow type
        // (e.g. `try: {it: 'm} -> 'inner <Exception>`) into the call result.
        val (callType, methodDeclaredEffects, methodDeclaredEffectInstances) =
            wrapCallResultWithEffects(resultType, strippedMethodType.effects)

        // Store method call resolution for the TypedAstBuilder.
        //
        // #218: stash the ability's instantiation TyVars into typeArgs so the
        // monomorphizer can substitute them when specializing the enclosing
        // function. Without this, an in-isolation lambda body checks `w.eq`
        // with `'v` as the Eq instantiation; the call-site solver later binds
        // `'v := Int` but mono never sees that because the typeArgs were
        // empty. The receiverConstraintType IS the ability's primary TyVar
        // (built-in path) or the receiver TyVar (user-defined path). When
        // mono runs, it walks `TypedCall.instantiation.typeArgs` and
        // substitutes each TyVar via the specialization's substitution map —
        // the body must thread the same TyVar identity through.
        val methodCallTypeArgs: List<MezzeType> = listOf(receiverConstraintType)
        val methodCallInfo = ConstraintResult.MethodCallInfo(
            abilityName = resolvedAbilityName,
            methodName = methodName,
            methodHash = null,  // Resolved at TypedAstBuilder time via ability impl index
            receiverFieldName = Keywords.IT,  // Language convention: receiver param is always `it`
            isInherentMethod = false,
            typeArgs = methodCallTypeArgs
        )

        return ConstraintResult(
            type = callType,
            constraints = constraints,
            effects = receiverResult.effects + argumentResult.effects + extraEffects + methodDeclaredEffects,
            effectInstances = receiverResult.effectInstances + argumentResult.effectInstances + extraEffectInstances + methodDeclaredEffectInstances,
            abilityConstraints = abilityConstraints,
            associatedTypeConstraints = associatedTypeConstraints,
            undefinedVariables = receiverResult.undefinedVariables + argumentResult.undefinedVariables,
            usedVariables = receiverResult.usedVariables + argumentResult.usedVariables,
            methodCallResolutions = receiverResult.methodCallResolutions + argumentResult.methodCallResolutions + mapOf((expr.loc to methodCallInfo.methodName) to methodCallInfo),
            callReturnTypes = receiverResult.callReturnTypes + argumentResult.callReturnTypes + (expr.loc to callType)
        )
    }

    /**
     * Peek at a receiver expression's bound type WITHOUT generating
     * constraints. Returns true when the receiver is a plain identifier
     * bound to a free type variable in scope — i.e. the call site can't
     * yet pick an inherent impl by receiver shape, and a speculative
     * choice by load order would silently mis-dispatch (issue #216).
     *
     * Conservative by design: returns false for anything we can't read
     * statically, so concrete receivers (literals, annotated params,
     * already-typed identifiers) keep their existing inherent-dispatch
     * behaviour.
     */
    private fun isReceiverUnresolved(receiver: ParsedExpr, context: ConstraintContext): Boolean {
        return getReceiverConcreteType(receiver, context) == null
    }

    /**
     * If the receiver has a known concrete type — either a literal (whose type
     * is determined syntactically) or an identifier bound to a non-VarType —
     * return it. Used to bias method-call dispatch toward inherent impls when
     * the receiver is concrete. Returns null when the receiver type is still
     * a free TypeVar so callers fall through to closed-record inference.
     */
    private fun getReceiverConcreteType(receiver: ParsedExpr, context: ConstraintContext): MezzeType? {
        // Literals have a known concrete type directly from syntax.
        when (receiver) {
            is com.mezze.language.parsedast.ParsedIntLit -> return MezzeType.IntType
            is com.mezze.language.parsedast.ParsedNatLit -> return MezzeType.NatType
            is com.mezze.language.parsedast.ParsedFloatLit -> return MezzeType.FloatType
            is com.mezze.language.parsedast.ParsedFracLit -> return MezzeType.FracType
            is com.mezze.language.parsedast.ParsedStringLit -> return MezzeType.StringType
            is com.mezze.language.parsedast.ParsedCharLit -> return MezzeType.CharType
            is com.mezze.language.parsedast.ParsedSeqLit ->
                return MezzeType.SeqType(MezzeType.VarType(TypeVar.fresh()))
            else -> { /* fall through */ }
        }
        if (receiver !is ParsedIdentifier) return null
        if (receiver.qualifier != null) return null
        val lookup = context.typingContext.lookup(receiver.localName)
        if (lookup !is TypingContext.LookupResult.Found) return null
        val type = when (val binding = lookup.binding) {
            is TypingContext.Binding.Monomorphic -> binding.type
            is TypingContext.Binding.Polymorphic -> binding.scheme.instantiate().type
        }
        return if (type is MezzeType.VarType) null else type
    }

    /**
     * Generate constraints for an inherent method call.
     *
     * Inherent methods come from `impl Type where { method = ... }` — no ability constraint needed.
     * The receiver type is unified with the target type from the inherent impl.
     */
    private fun generateInherentMethodCall(
        expr: ParsedFunctionCall,
        memberAccess: ParsedMemberAccess,
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
        // When the inferred receiverType is still a fresh VarType (e.g., the
        // receiver is a `Some {val=42}` whose type the solver hasn't pinned yet),
        // fall back to a syntactic lookup: find the type-decl that declares the
        // constructor and match impls against that type-decl name.
        val receiverTypeName = getReceiverTypeName(receiverType)
            ?: lookupConstructorTypeName(memberAccess.receiver, context)
        val methodInfo = if (inherentMethods.size == 1) {
            inherentMethods.first()
        } else if (receiverTypeName != null) {
            inherentMethods.firstOrNull { it.targetTypeName == receiverTypeName }
        } else {
            inherentMethods.firstOrNull()
        }

        // Imported (cross-module) impls: fresh-instantiate the method type so
        // per-call unification of generic impls (e.g. `impl Seq 'a where {
        // first }`) doesn't leak between call sites — `nums.first {}` on
        // `[Int]` should pin `'a := Int` for that site only. Concrete bits
        // (after issue #213 pinned non-generic returns to e.g. `Int`) survive.
        //
        // Same-module impls: use methodType directly so the registered
        // method type's TypeVars stay shared with the constraint
        // ConstraintGenerator added between method-return and body type
        // (`Equality(methodType.returnType, body.type)`). Severing that
        // link would leave call sites unable to recover the body's
        // inferred return type.
        val freshMethodType: MezzeType? = if (methodInfo != null) {
            if (methodInfo.isImported) {
                instantiateTypeWithFreshVars(methodInfo.methodType)
            } else {
                methodInfo.methodType
            }
        } else null

        if (methodInfo != null && freshMethodType != null) {
            if (freshMethodType is MezzeType.FunctionType) {
                // Strip receiver param from param type — method syntax passes receiver separately
                val fullParams = (freshMethodType.paramType as? MezzeType.RecordType)?.fields ?: emptyMap()
                val strippedParams = fullParams.filterKeys { it != Keywords.IT }
                val strippedParamType = MezzeType.RecordType(fields = strippedParams, rowVar = null)

                // For imported (cross-module) impls only: unify the receiver's
                // actual type with the impl's `it` slot. For generic imports
                // (e.g. `impl Seq 'a where { first }`) this is what lets `'a`
                // get bound to the concrete element type at the call site —
                // without it, `nums.first {}` on `[Int]` reports `Option 'a`
                // instead of `Option Int`. Same-module impls don't need this:
                // ConstraintGenerator already binds the impl's it slot via the
                // body-context constraint pass.
                if (methodInfo.isImported) {
                    val itType = fullParams[Keywords.IT]
                    if (itType != null) {
                        constraints.add(Constraint.Equality(
                            left = receiverType,
                            right = itType,
                            context = "Inherent method receiver for $methodName",
                            loc = expr.loc
                        ))
                    }
                }

                // Constrain arguments (without it)
                constraints.add(Constraint.Equality(
                    left = argumentResult.type,
                    right = strippedParamType,
                    context = "Inherent method call arguments for $methodName",
                    loc = expr.loc
                ))

                // Constrain return type
                constraints.add(Constraint.Equality(
                    left = resultType,
                    right = freshMethodType.returnType,
                    context = "Inherent method call return type",
                    loc = expr.loc
                ))
            }
        }

        // Store inherent method call resolution for the TypedAstBuilder.
        //
        // #217: stash the impl's `it`-slot TyVar (post fresh-instantiation for
        // imports) into typeArgs so mono can substitute the concrete receiver
        // type when specializing the enclosing function. Mirror the #218
        // ability-method plumbing — without it, every inherent-method call
        // inside a generic body's TypedCall.instantiation ends up empty and
        // the specializer can't enqueue this call's spec.
        val inherentItType = (freshMethodType as? MezzeType.FunctionType)
            ?.let { (it.paramType as? MezzeType.RecordType)?.fields?.get(Keywords.IT) }
        val inherentMethodCallInfo = if (methodInfo != null) {
            ConstraintResult.MethodCallInfo(
                abilityName = null,  // Inherent methods have no ability
                methodName = methodName,
                methodHash = null,   // Resolved at TypedAstBuilder time via impl loc
                receiverFieldName = Keywords.IT,  // Language convention: receiver param is always `it`
                isInherentMethod = true,
                implContentHash = methodInfo.implContentHash,
                implStartLine = methodInfo.implStartLine,
                implStartCol = methodInfo.implStartCol,
                implSourceFile = methodInfo.implSourceFile,
                methodFunctionType = freshMethodType,
                typeArgs = if (inherentItType != null) listOf(inherentItType) else emptyList()
            )
        } else null

        // Propagate effects declared by the inherent method's arrow type
        // into the call result. Same logic as ability methods and function calls.
        val (callType, methodDeclaredEffects, methodDeclaredEffectInstances) =
            wrapCallResultWithEffects(resultType, (freshMethodType as? MezzeType.FunctionType)?.effects)

        return ConstraintResult(
            type = callType,
            constraints = constraints,
            effects = receiverResult.effects + argumentResult.effects + extraEffects + methodDeclaredEffects,
            effectInstances = receiverResult.effectInstances + argumentResult.effectInstances + extraEffectInstances + methodDeclaredEffectInstances,
            abilityConstraints = receiverResult.abilityConstraints + argumentResult.abilityConstraints,
            associatedTypeConstraints = receiverResult.associatedTypeConstraints + argumentResult.associatedTypeConstraints,
            undefinedVariables = receiverResult.undefinedVariables + argumentResult.undefinedVariables,
            usedVariables = receiverResult.usedVariables + argumentResult.usedVariables,
            methodCallResolutions = receiverResult.methodCallResolutions + argumentResult.methodCallResolutions + (if (inherentMethodCallInfo != null) mapOf((expr.loc to inherentMethodCallInfo.methodName) to inherentMethodCallInfo) else emptyMap()),
            callReturnTypes = receiverResult.callReturnTypes + argumentResult.callReturnTypes + (expr.loc to callType)
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
    /**
     * When the inferred receiver type is still an unresolved VarType, try to
     * derive a type name from the receiver expression itself. For variant
     * constructors like `Some {val=42}`, this finds the type-decl whose
     * variant defines `Some` and returns its name (`"Option"`). Fixes inherent
     * method dispatch when constraint solving hasn't yet pinned the receiver
     * type.
     */
    private fun lookupConstructorTypeName(receiver: ParsedExpr, context: ConstraintContext): String? =
        lookupConstructorTypeName(receiver, context, depth = 0)

    /**
     * Bounded variant. `depth` guards against `let a = b; let b = c; …` chains;
     * we walk at most a handful of hops, which is enough to cover the realistic
     * "alias one call result" pattern (#194) without quadratic blowups on
     * pathological inputs.
     */
    private fun lookupConstructorTypeName(
        receiver: ParsedExpr,
        context: ConstraintContext,
        depth: Int,
    ): String? {
        if (depth > 4) return null

        // Variant constructor receiver: `Some {val=42}`, `Ok {val=42}`.
        val ctorName = when (receiver) {
            is ParsedVariantConstruct -> receiver.constructor
            is ParsedFunctionCall -> (receiver.function as? ParsedVariantConstruct)?.constructor
            else -> null
        }
        if (ctorName != null) {
            // Built-in variant constructors don't appear in typeDeclarations —
            // they're hardcoded as TypeApplication("Result", ...) etc. in
            // BasicTypes.kt and friends. Map the well-known ones explicitly.
            when (ctorName) {
                "Ok", "Err" -> return "Result"
                "Some", "None" -> return "Option"
            }
            for ((typeName, typeDecl) in context.typeDeclarations) {
                val def = typeDecl.definition
                if (def is ParsedVariantDef && def.constructors.any { it.name == ctorName }) {
                    return typeName
                }
            }
        }
        // Method-chain receiver: `s.insert {x=1}.insert {x=2}` — the outer
        // call's receiver is itself a ParsedFunctionCall whose function is a
        // ParsedMemberAccess. Recurse on the inner receiver to find its type
        // name, then look up the inherent method's return type.
        if (receiver is ParsedFunctionCall && receiver.function is ParsedMemberAccess) {
            val memberAccess = receiver.function as ParsedMemberAccess
            val innerTypeName = lookupConstructorTypeName(memberAccess.receiver, context, depth + 1) ?: return null
            val candidates = context.typingContext.findAnonymousMethodsByName(memberAccess.member)
                .filter { it.targetTypeName == innerTypeName }
            val method = candidates.singleOrNull() ?: return null
            val ret = (method.methodType as? MezzeType.FunctionType)?.returnType ?: return null
            return getReceiverTypeName(ret)
        }
        // Function-call receiver: `set::empty {}` returns `Set 'a`. Look up the
        // function's binding type and extract the return-type's constructor.
        // Also handles bare identifiers like `s` whose binding may already have
        // a non-VarType (rare here, but covered).
        val fnExpr: ParsedExpr? = when (receiver) {
            is ParsedFunctionCall -> receiver.function
            else -> receiver
        }
        val bindingType: MezzeType? = when (fnExpr) {
            is ParsedIdentifier -> {
                val qualifierStr = fnExpr.qualifier?.toString()
                val key = if (qualifierStr != null)
                    BindingKey(qualifierStr, fnExpr.localName)
                else BindingKey.unqualified(fnExpr.localName)
                val lookup = context.typingContext.lookup(key)
                if (lookup is TypingContext.LookupResult.Found) {
                    when (val b = lookup.binding) {
                        is TypingContext.Binding.Monomorphic -> b.type
                        is TypingContext.Binding.Polymorphic -> b.scheme.type
                    }
                } else null
            }
            else -> null
        }
        // For function calls, extract the return type. For bare identifiers,
        // use the binding type directly.
        val resultType = when {
            bindingType is MezzeType.FunctionType && receiver is ParsedFunctionCall -> bindingType.returnType
            bindingType != null && receiver !is ParsedFunctionCall -> bindingType
            else -> null
        } ?: return null
        return getReceiverTypeName(resultType)
    }

    /**
     * #188: anonymous record → in-scope type aliases that share the field set.
     * Mirror of `TypeValidator.findMatchingRecordTypeAliases` so dispatch
     * preference can promote unannotated record literals to their nominal
     * alias when picking impl candidates.
     */
    private fun findMatchingRecordTypeAliases(
        recordType: MezzeType.RecordType,
        context: ConstraintContext
    ): List<String> {
        val matches = mutableListOf<String>()
        for ((aliasName, typeDecl) in context.typeDeclarations) {
            val def = typeDecl.definition
            if (def is com.mezze.language.parsedast.ParsedRecordDef) {
                val aliasFieldNames = def.fields.map { it.name }.toSet()
                if (aliasFieldNames == recordType.fields.keys) {
                    matches.add(aliasName)
                }
            }
        }
        return matches
    }

    /**
     * #188: anonymous variant → in-scope variant aliases whose constructor
     * names overlap. Loosely matches the validator's variant-alias lookup.
     */
    private fun findMatchingVariantTypeAliases(
        variantType: MezzeType.VariantType,
        context: ConstraintContext
    ): List<String> {
        val matches = mutableListOf<String>()
        for ((aliasName, typeDecl) in context.typeDeclarations) {
            val def = typeDecl.definition
            if (def is com.mezze.language.parsedast.ParsedVariantDef) {
                val aliasCtorNames = def.constructors.map { it.name }.toSet()
                if (aliasCtorNames.containsAll(variantType.constructors.keys)) {
                    matches.add(aliasName)
                }
            }
        }
        return matches
    }

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
            ?: if (type.constructors.size == 1) type.constructors.keys.first() else null
        else -> null
    }

    private fun tryGenerateEffectOperationCall(
        expr: ParsedMemberAccess,
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
                loc = expr.loc,
                errorType = ErrorType.MISSING_FIELD,
                code = ErrorCode.T0206_METHOD_NOT_FOUND,
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

        // Return the plain function type with the effects tracked in ConstraintResult.
        //
        // #312: when the operation's own outer effect row mentions the parent effect
        // explicitly (e.g. `query: ... -> Str <Database, Log>` inside `effect Database`),
        // we trust the row as exhaustive and surface ALL of its effects. When the row
        // names a different effect entirely (e.g. `succeed: ... <Result>` inside
        // `effect Outcome`), we treat that as a free-form "tag" annotation and fall
        // back to just the parent effect — preserving long-standing behavior of
        // EffectAssociatedTypesInterpreterTest. (Empty op row → parent only.)
        val opOwnEffects = effectDecl?.operations
            ?.firstOrNull { it.name == operationName }
            ?.effects?.toSet().orEmpty()
        val operationEffects = if (effectName in opOwnEffects) opOwnEffects else setOf(effectName)
        return ConstraintResult(
            type = instantiatedOperationType,
            constraints = emptyList(),
            effects = operationEffects,
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
        expr: ParsedMemberAccess,
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
                    com.mezze.language.typechecker.util.WellKnownTypeApp.of(
                        constructor = type.constructor,
                        args = type.args.map { substituteAssociatedTypesInType(it, associatedTypeBindings) },
                        typeHash = type.typeHash
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
                rowVar = type.rowVar,
                typeName = type.typeName,
                typeHash = type.typeHash
            )
            is MezzeType.VariantType -> MezzeType.VariantType(
                constructors = type.constructors.mapValues { (_, dataType) ->
                    dataType?.let { substituteAssociatedTypesInType(it, associatedTypeBindings) }
                },
                rowVar = type.rowVar,
                typeName = type.typeName,
                typeArgs = type.typeArgs.map { substituteAssociatedTypesInType(it, associatedTypeBindings) },
                typeHash = type.typeHash,
                nominalWall = type.nominalWall
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
        // This allows them to be matched with performer bindings later.
        //
        // #183: Use `freshWithName` (not bare `TypeVar(name)`) so each
        // fresh-instantiation gets a unique id. Plain `TypeVar(name)`
        // uses the default `id = 0`, and `data class` equality then
        // collapses every `'it` across every imported impl onto the
        // SAME instance — chains like `c.get { p }.with_json { v }.send {}`
        // end up unifying the receivers of two different impls together
        // ("Type constructor mismatch: Req vs Client").
        val substitution = freeVars.associateWith { oldVar ->
            if (oldVar.name.startsWith("'")) {
                // Associated type variable - preserve the name but create new instance
                TypeVar.freshWithName(oldVar.name)
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
                type.constructors.values.filterNotNull().flatMap { getFreeTypeVariables(it) }.toSet() +
                type.typeArgs.flatMap { getFreeTypeVariables(it) }.toSet()
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
                        typeArgs = constraint.typeArgs.map { applyTypeVarSubstitution(it, substitution) },
                        abilityHash = constraint.abilityHash
                    )
                }
            )
            is MezzeType.RecordType -> MezzeType.RecordType(
                fields = type.fields.mapValues { (_, fieldType) ->
                    applyTypeVarSubstitution(fieldType, substitution)
                },
                rowVar = type.rowVar,
                typeName = type.typeName,
                typeHash = type.typeHash
            )
            is MezzeType.VariantType -> MezzeType.VariantType(
                constructors = type.constructors.mapValues { (_, dataType) ->
                    dataType?.let { applyTypeVarSubstitution(it, substitution) }
                },
                rowVar = type.rowVar,
                typeName = type.typeName,
                typeArgs = type.typeArgs.map { applyTypeVarSubstitution(it, substitution) },
                typeHash = type.typeHash,
                nominalWall = type.nominalWall
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
            is MezzeType.TypeApplication -> com.mezze.language.typechecker.util.WellKnownTypeApp.of(
                constructor = type.constructor,
                args = type.args.map { applyTypeVarSubstitution(it, substitution) },
                typeHash = type.typeHash
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
                type.constructors.values.filterNotNull().flatMap { extractTypeVariables(it) } +
                type.typeArgs.flatMap { extractTypeVariables(it) }
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
    private fun typeMatchesAnnotationBidirectional(constraintType: MezzeType, annotation: com.mezze.language.parsedast.ParsedTypeAnnotation): Boolean {
        if (constraintType is MezzeType.VarType) return true
        return when (annotation) {
            is com.mezze.language.parsedast.ParsedNamedType -> {
                val b = PrimitiveTypes.matchesBuiltinType(annotation.name, constraintType); if (b != null) b
                else if (annotation.name.startsWith("'") || annotation.name.first().isLowerCase()) true
                else when (constraintType) { is MezzeType.TypeApplication -> constraintType.constructor == annotation.name && constraintType.args.isEmpty(); is MezzeType.RecordType -> constraintType.typeName == annotation.name; is MezzeType.VariantType -> constraintType.typeName == annotation.name; else -> false }
            }
            is com.mezze.language.parsedast.ParsedTypeApp -> when (constraintType) {
                is MezzeType.TypeApplication -> constraintType.constructor == annotation.constructor && constraintType.args.size == annotation.args.size && constraintType.args.zip(annotation.args).all { (a, b) -> typeMatchesAnnotationBidirectional(a, b) }
                is MezzeType.SeqType -> annotation.constructor == BuiltinTypes.SEQ && annotation.args.size == 1 && typeMatchesAnnotationBidirectional(constraintType.elementType, annotation.args[0])
                is MezzeType.ListType -> annotation.constructor == BuiltinTypes.LIST && annotation.args.size == 1 && typeMatchesAnnotationBidirectional(constraintType.elementType, annotation.args[0])
                is MezzeType.SetType -> annotation.constructor == BuiltinTypes.SET && annotation.args.size == 1 && typeMatchesAnnotationBidirectional(constraintType.elementType, annotation.args[0])
                is MezzeType.MapType -> annotation.constructor == BuiltinTypes.MAP && annotation.args.size == 2 && typeMatchesAnnotationBidirectional(constraintType.keyType, annotation.args[0]) && typeMatchesAnnotationBidirectional(constraintType.valueType, annotation.args[1])
                else -> false
            }
            is com.mezze.language.parsedast.ParsedRecordType -> constraintType is MezzeType.RecordType && annotation.entries.all { e -> constraintType.fields[e.name]?.let { typeMatchesAnnotationBidirectional(it, e.type) } ?: false }
            is com.mezze.language.parsedast.ParsedVariantType -> constraintType is MezzeType.VariantType && annotation.constructors.all { c -> val p = constraintType.constructors[c.name]; if (c.dataType == null) p == null else if (p == null) false else typeMatchesAnnotationBidirectional(p, c.dataType!!) }
            is com.mezze.language.parsedast.ParsedSeqType -> constraintType is MezzeType.SeqType && typeMatchesAnnotationBidirectional(constraintType.elementType, annotation.elementType)
            is com.mezze.language.parsedast.ParsedFunctionType -> constraintType is MezzeType.FunctionType && typeMatchesAnnotationBidirectional(constraintType.paramType, annotation.paramType) && typeMatchesAnnotationBidirectional(constraintType.returnType, annotation.returnType)
            is com.mezze.language.parsedast.ParsedQualifiedType -> constraintType is MezzeType.TypeApplication && constraintType.constructor == (annotation.modulePath + annotation.typeName).joinToString("::") && constraintType.args.size == annotation.args.size && constraintType.args.zip(annotation.args).all { (a, b) -> typeMatchesAnnotationBidirectional(a, b) }
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

    // ========================================================================
    // VARIANTS
    // ========================================================================

    private fun generateVariantConstruction(expr: ParsedVariantConstruct, context: ConstraintContext): ConstraintResult {
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

    private fun generateLambda(expr: ParsedLambda, context: ConstraintContext): ConstraintResult {
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
    private fun mergeEffectInstances(
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
    private fun mergeAssociatedTypes(
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

    private fun generateFunctionCall(expr: ParsedFunctionCall, context: ConstraintContext): ConstraintResult {
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

                    return ConstraintResult(
                        type = partialResult,
                        constraints = constraints,
                        effects = functionResult.effects + argumentResult.effects,
                        effectInstances = functionResult.effectInstances + argumentResult.effectInstances,
                        performed = functionResult.performed || argumentResult.performed,
                        abilityConstraints = functionResult.abilityConstraints + argumentResult.abilityConstraints + concreteAbilityChecks,
                        undefinedVariables = functionResult.undefinedVariables + argumentResult.undefinedVariables,
                        usedVariables = functionResult.usedVariables + argumentResult.usedVariables,
                        referencedBindings = functionResult.referencedBindings + argumentResult.referencedBindings,
                        methodCallResolutions = functionResult.methodCallResolutions + argumentResult.methodCallResolutions,
                        callReturnTypes = functionResult.callReturnTypes + argumentResult.callReturnTypes + (expr.loc to partialResult)
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
                    is ParsedIdentifier -> (expr.function as ParsedIdentifier).localName
                    else -> null
                }
                constraints.add(
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
                val propagatedEffects = effectRow.effects + functionResult.effects + argumentResult.effects + rowVarEffects
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
                                abilityHash = context.abilityDeclarations[constraint.abilityName]?.contentHash,
                                typeArgs = constraint.typeArgs.drop(1),  // Additional type args after the constrained type
                                context = "Method-level constraint: ${constraint.abilityName} on ${constrainedType.variable.name}",
                                loc = expr.loc
                            )
                        } else null
                    }
                    effectfulAbilityConstraints = effectfulAbilityConstraints + methodLevelConstraints
                }

                // Wrap the call's value type in SuspendedType when the callee
                // has effects (unhandled or performed). Important so top-level
                // validation can detect `<*>`.
                val (resultType, _, _) = wrapCallResultWithEffects(functionType.returnType, effectRow)

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
                    referencedBindings = functionResult.referencedBindings + argumentResult.referencedBindings,
                    methodCallResolutions = functionResult.methodCallResolutions + argumentResult.methodCallResolutions,
                    callReturnTypes = functionResult.callReturnTypes + argumentResult.callReturnTypes + (expr.loc to resultType)
                )
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
                val freeVars = com.mezze.language.typechecker.context.collectFreeTypeVars(ft.returnType)
                if (freeVars.isEmpty()) ft.returnType
                else MezzeType.VarType(TypeVar.fresh())
            } else MezzeType.VarType(TypeVar.fresh())
        }

        val constraints = mutableListOf<Constraint>()
        constraints.addAll(functionResult.constraints)
        constraints.addAll(argumentResult.constraints)

        // function must be a function type: argumentType -> returnType
        // Note: left = actual (what we got), right = expected (what we need)
        // The function's actual type must match a function that takes our argument type
        // Extract function name for better error messages
        val functionName = when (expr.function) {
            is ParsedIdentifier -> (expr.function as ParsedIdentifier).localName
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
        val funcTypeEffects: Set<String>
        val funcTypePerformed: Boolean
        if (functionResult.type is MezzeType.FunctionType) {
            val fnType = functionResult.type as MezzeType.FunctionType
            funcTypeEffects = fnType.effects?.effects ?: emptySet()
            funcTypePerformed = fnType.effects?.performed ?: false
        } else {
            funcTypeEffects = emptySet()
            funcTypePerformed = false
        }
        val collectedEffects = functionResult.effects + argumentResult.effects + rowVarEffects + funcTypeEffects
        val collectedPerformed = functionResult.performed || argumentResult.performed || funcTypePerformed

        // CRITICAL: Propagate ability constraints from function and arguments
        var collectedAbilityConstraints = functionResult.abilityConstraints + argumentResult.abilityConstraints
        // CRITICAL: Propagate associated type constraints from function and arguments
        val collectedAssociatedTypeConstraints = functionResult.associatedTypeConstraints + argumentResult.associatedTypeConstraints

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
            referencedBindings = functionResult.referencedBindings + argumentResult.referencedBindings,
            methodCallResolutions = functionResult.methodCallResolutions + argumentResult.methodCallResolutions,
            callReturnTypes = functionResult.callReturnTypes + argumentResult.callReturnTypes + (expr.loc to returnType)
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

    private fun generateMatch(expr: ParsedMatch, context: ConstraintContext): ConstraintResult {
        // match scrutinee { pattern1 -> expr1, pattern2 -> expr2 }

        // Step 1: Generate constraints for the scrutinee expression
        // TCO: Scrutinee is NOT in tail position
        val scrutineeResult = generate(expr.scrutinee, context.notInTailPosition())

        val collector = ConstraintCollector()
        collector.merge(scrutineeResult)
        // Track unused pattern bindings (warnings)
        val unusedVars = mutableListOf<ConstraintResult.UnusedVariable>()
        // Track pattern-related warnings (e.g., float literal patterns)
        val patternWarnings = mutableListOf<String>()
        // #195 mono-fix #1: per-match-case binding types
        val matchCaseBindings = mutableMapOf<com.mezze.language.parsedast.Loc, Map<String, MezzeType>>()
        matchCaseBindings.putAll(scrutineeResult.matchCaseBindings)

        // Step 2: Check for wildcards - if present, scrutinee must be open variant
        // IMPORTANT: Only check TOP-LEVEL patterns for wildcards/variables.
        // A VariablePattern inside a ConstructorPattern (like `Pair {a}`) binds a payload field,
        // not the whole variant, so it doesn't make the variant open.
        val hasWildcard = expr.cases.any { matchCase ->
            matchCase.patterns.any { pattern ->
                // Only ParsedWildcardPattern or ParsedVariablePattern at top level make the variant open
                pattern is ParsedWildcardPattern || pattern is ParsedVariablePattern
            }
        }
        if (hasWildcard) {
            // Mark the scrutinee type as must-be-open
            collector.constraints.add(
                Constraint.MustBeOpen(
                    type = scrutineeResult.type,
                    bindingName = context.currentBindingName,
                    context = "Match has wildcard pattern - scrutinee may have additional constructors",
                    loc = expr.loc,
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
            val patternResult = patternGenerator.generate(firstPattern, scrutineeResult.type, context, expr.loc)
            collector.constraints.addAll(patternResult.constraints)
            patternWarnings.addAll(patternResult.warnings)

            // For additional patterns in or-pattern, verify they bind the same variables
            // and add constraints (bindings must match first pattern's)
            val firstPatternBindings = patternResult.bindings.keys
            for (pattern in matchCase.patterns.drop(1)) {
                val orPatternResult = patternGenerator.generate(pattern, scrutineeResult.type, context, expr.loc)
                collector.constraints.addAll(orPatternResult.constraints)
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
                        expr.loc,
                        code = com.mezze.language.parsedast.diagnostics.ErrorCode.V0605_OR_PATTERN_BINDS_DIFFERENT_VARS,
                        hints = listOf("all alternatives in an or-pattern must bind the same variables"),
                    )
                }

                // Also unify the binding types between patterns to ensure consistency
                for ((varName, varType) in orPatternResult.bindings) {
                    val firstType = patternResult.bindings[varName]!!
                    collector.constraints.add(
                        Constraint.Equality(
                            left = varType,
                            right = firstType,
                            context = "Or-pattern binding '$varName' must have same type in all alternatives",
                            loc = expr.loc,
                        )
                    )
                }
            }

            // Extend context with pattern bindings for the case body
            var caseContext = context
            for ((varName, varType) in patternResult.bindings) {
                caseContext = caseContext.extend(varName, varType)
            }
            // #195 mono-fix #1: stash this case's bindings keyed by the case
            // Loc so TypedAstBuilder can recover the solver's TyVars (not
            // disconnected placeholders) when emitting PatternBinding + the
            // guard/body identifier context.
            matchCaseBindings[matchCase.loc] = patternResult.bindings

            // If there's a guard, check it (must be Bool)
            // TCO: Guard is NOT in tail position
            val guard = matchCase.guard
            var guardUsedVariables: Set<String> = emptySet()  // Track for unused variable checking
            if (guard != null) {
                val guardResult = generate(guard, caseContext.notInTailPosition())
                collector.merge(guardResult)
                guardUsedVariables = guardResult.usedVariables  // Save for later use
                // #218: guard's methodCallResolutions and callReturnTypes were
                // being dropped on the floor — every `w.eq` inside a match guard
                // never reached TypedAstBuilder, which fell back to a plain
                // TypedFieldAccess emission with no `it` injection. The mono
                // path then crashed because the impl body's `it` slot stayed
                // null. Same propagation pattern as the case-body's path below.
                collector.methodCallResolutions.putAll(guardResult.methodCallResolutions)
                collector.callReturnTypes.putAll(guardResult.callReturnTypes)

                // Guard must be Bool (variant type | True, False |)
                collector.constraints.add(
                    Constraint.Equality(
                        left = guardResult.type,
                        right = MezzeType.VariantType(
                            constructors = mapOf(com.mezze.language.ast.BuiltinTypes.TRUE to null, com.mezze.language.ast.BuiltinTypes.FALSE to null),
                            rowVar = null
                        ),
                        context = "Match guard must be Bool",
                        loc = guard.loc,
                    )
                )
            }

            // Process if-let bindings
            // if let Pattern = Expression extends the context and adds constraints
            for (ifLetBinding in matchCase.ifLetBindings) {
                // Generate constraints for the binding expression
                // TCO: If-let expression is NOT in tail position
                val bindingExprResult = generate(ifLetBinding.expr, caseContext.notInTailPosition())
                collector.merge(bindingExprResult)

                // Generate pattern constraints for the if-let pattern against the expression type
                val ifLetPatternResult = patternGenerator.generate(
                    ifLetBinding.pattern,
                    bindingExprResult.type,
                    caseContext,
                    ifLetBinding.loc
                )
                collector.constraints.addAll(ifLetPatternResult.constraints)
                patternWarnings.addAll(ifLetPatternResult.warnings)

                // Extend context with bindings from the if-let pattern
                for ((varName, varType) in ifLetPatternResult.bindings) {
                    caseContext = caseContext.extend(varName, varType)
                }
            }

            // Generate constraints for case body with extended context
            // TCO: Case body inherits tail position from context
            val caseBodyResult = generate(matchCase.body, caseContext)
            collector.merge(caseBodyResult)
            matchCaseBindings.putAll(caseBodyResult.matchCaseBindings)

            // Check for unused pattern bindings
            // Variables starting with _ are intentionally unused (convention)
            // Bug fix: Reuse guardUsedVariables instead of re-generating the guard
            val usedVarsInBody = caseBodyResult.usedVariables + guardUsedVariables
            for ((varName, _) in patternResult.bindings) {
                if (!varName.startsWith("_") && varName !in usedVarsInBody) {
                    // Use the binding's source location from the pattern
                    // Fall back to case body location if not available
                    val patternLocation = patternResult.bindingLocations[varName]
                        ?: matchCase.body.loc
                        ?: expr.loc
                    unusedVars.add(
                        ConstraintResult.UnusedVariable(
                            name = varName,
                            loc = patternLocation
                        )
                    )
                }
            }

            // Unwrap SuspendedType from the case body's result. Effects from
            // generate() guarantees no SuspendedType — effects are in caseBodyResult.effects
            caseResultTypes.add(caseBodyResult.type)
        }

        // Step 4: All case bodies must have the same type
        // Use a fresh type variable as the result type so variant constructors from different
        // arms can be merged through unification. Without this, the first arm's concrete type
        // would become the result type, and merging wouldn't work properly.
        val resultType = MezzeType.VarType(TypeVar.fresh())

        for (caseType in caseResultTypes) {
            collector.constraints.add(
                Constraint.Equality(
                    left = caseType,
                    right = resultType,
                    context = "Match case must have same type as other cases",
                    isMatchCaseContext = true,  // OPTIMIZATION: Use flag instead of string parsing
                    loc = expr.loc,
                )
            )
        }

        return collector.toResult(resultType).copy(
            unusedVariables = unusedVars,
            patternWarnings = patternWarnings,
            matchCaseBindings = matchCaseBindings,
        )
    }

    // ========================================================================
    // BLOCKS
    // ========================================================================

    private fun generateDo(expr: ParsedDo, context: ConstraintContext): ConstraintResult {
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
        val collector = ConstraintCollector()

        // Create pattern generator for handling destructuring in let statements
        val patternGenerator = PatternConstraintGenerator()

        // Process statements
        for (stmt in expr.statements) {
            when (stmt) {
                is ParsedStatement.Let -> {
                    // BIDIRECTIONAL TYPING: If there's a type annotation, use it as expected type
                    // This is CRITICAL for polymorphic abilities like Decode to work correctly
                    // (e.g., `let user: Result {name: String} DecodeError = Decode.decode {...}`)
                    val annotatedType = stmt.typeAnnotation?.let { annotation ->
                        convertParsedTypeAnnotation(annotation)
                    }

                    // RECURSIVE LET: For `let name = lambda { ... }` in do blocks, pre-bind
                    // the variable name so the lambda body can reference itself recursively.
                    val isRecursiveLetCandidate = stmt.value is ParsedLambda &&
                                                   stmt.pattern is ParsedVariablePattern
                    val preBindType: MezzeType? = if (isRecursiveLetCandidate) {
                        annotatedType ?: MezzeType.VarType(TypeVar.fresh())
                    } else null

                    val preBindContext = if (preBindType != null) {
                        val varName = (stmt.pattern as ParsedVariablePattern).name
                        val preBinding = mapOf(
                            BindingKey.unqualified(varName) to TypingContext.Binding.Monomorphic(preBindType)
                        )
                        currentContext.copy(
                            typingContext = currentContext.typingContext.extendAll(preBinding)
                        )
                    } else {
                        currentContext
                    }

                    // Generate constraints for the expression, with expected type if annotated
                    val exprContext = if (annotatedType != null) {
                        preBindContext.withExpectedType(annotatedType)
                    } else {
                        preBindContext
                    }
                    val exprResult = generate(stmt.value, exprContext)

                    // RECURSIVE LET: unify pre-bind type with inferred lambda type
                    if (preBindType != null && annotatedType == null) {
                        collector.constraints.add(
                            Constraint.Equality(
                                left = preBindType,
                                right = exprResult.type,
                                context = "Recursive let binding in do block",
                                loc = stmt.value.loc
                            )
                        )
                    }

                    collector.merge(exprResult)

                    // generate() already unwrapped SuspendedType — use type directly
                    val bindingType = exprResult.type

                    if (annotatedType != null) {
                        collector.constraints.add(
                            Constraint.Equality(
                                left = bindingType,
                                right = annotatedType,
                                context = "Type annotation on let binding in do block",
                                loc = stmt.value.loc
                            )
                        )
                    }

                    // Record nested binding types for type resolution
                    val variableNames = stmt.pattern.extractBindings()
                    for (varName in variableNames) {
                        nestedBindingTypes[varName] = bindingType
                    }

                    // Generate pattern constraints
                    // The pattern must match the unwrapped expression type
                    val patternResult = patternGenerator.generate(
                        stmt.pattern,
                        bindingType,
                        currentContext,
                        stmt.value.loc
                    )
                    collector.constraints.addAll(patternResult.constraints)

                    // Extend context with all pattern bindings
                    // For simple variable bindings: extends with one binding
                    // For destructuring patterns: extends with multiple bindings
                    val typingContext = currentContext.typingContext

                    // LET-POLYMORPHISM: If the user explicitly declares a polymorphic type annotation
                    // with ability constraints, we should create a Polymorphic binding.
                    // Example: let encode_value: {x: 'a} with Encoder 'a -> String = ...
                    // The user is explicitly telling us this should be polymorphic.
                    val contextBindings = if (stmt.pattern is ParsedVariablePattern &&
                        stmt.typeAnnotation is ParsedFunctionType &&
                        (stmt.typeAnnotation as ParsedFunctionType).abilityConstraints.isNotEmpty()
                    ) {
                        // User explicitly declared ability constraints - create Polymorphic binding
                        val annotation = stmt.typeAnnotation as ParsedFunctionType
                        val annotatedType = convertParsedTypeAnnotation(annotation)

                        // Collect type variables from the annotation
                        val typeVars = com.mezze.language.typechecker.context.collectFreeTypeVars(annotatedType)

                        // Convert annotation's ability constraints to AbilityConstraint
                        // ParsedFnAbilityConstraint has typeArgs - find the type variable(s) among them
                        // For Encode Json 'a: typeArgs = [Json, 'a], typeParam = 'a, allTypeArgs = [Json, 'a]
                        val abilityConstraints = annotation.abilityConstraints.mapNotNull { constraint ->
                            // Find the first type variable in the type args - that's the constrained parameter
                            val typeVarArg = constraint.typeArgs.find { arg ->
                                arg is ParsedNamedType &&
                                (arg.name.startsWith("'") || arg.name.first().isLowerCase())
                            }
                            val typeParamName = when (typeVarArg) {
                                is ParsedNamedType -> typeVarArg.name
                                else -> null
                            }
                            if (typeParamName != null) {
                                // Convert ALL type args to MezzeTypes, preserving order
                                val allTypeArgs = constraint.typeArgs.map { typeArg ->
                                    convertParsedTypeAnnotation(typeArg)
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
                        patternResult.bindings.map { (name, type) ->
                            BindingKey.unqualified(name) to TypingContext.Binding.Monomorphic(type = type)
                        }.toMap()
                    }

                    val extendedTypingContext = typingContext.extendAll(contextBindings)
                    currentContext = currentContext.copy(typingContext = extendedTypingContext)

                    // CRITICAL: Track record literal bindings (matches OldTypeChecker.kt:1428-1429)
                    // If this is a simple variable binding to a ParsedRecordLit, track it
                    // This enables field access validation like OldTypeChecker
                    if (stmt.pattern is ParsedVariablePattern && stmt.value is ParsedRecordLit) {
                        val varName = (stmt.pattern as ParsedVariablePattern).name
                        currentContext = currentContext.withRecordLiteralBinding(varName)
                    }
                }
                is ParsedStatement.Expr -> {
                    val exprResult = generate(stmt.expr, currentContext)
                    collector.merge(exprResult)
                }
                is ParsedStatement.Bind -> {
                    // ParsedStatement.Bind must be eliminated by DoBindDesugar before
                    // constraint generation. Reaching here means a traversal site in
                    // the desugar missed this position.
                    throw IllegalStateException(
                        "ParsedStatement.Bind reached constraint generation at ${stmt.loc} — " +
                        "DoBindDesugar must rewrite Bind statements to Let before this phase."
                    )
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
        collector.merge(resultResult)

        // CRITICAL: If do block has effects or performed effects, wrap the result in SuspendedType
        // This allows perform expressions to properly detect and handle effects
        // BUT: Don't double-wrap if the result is already a SuspendedType!
        // Data types (like Result) are orthogonal to effects - we should not nest Suspended types.
        val resultType = if (collector.effects.isNotEmpty() || collector.performed) {
            when (val innerType = resultResult.type) {
                is MezzeType.SuspendedType -> {
                    // Result is already suspended - merge effects and performed flag
                    val mergedEffects = innerType.effects.effects + collector.effects
                    val mergedPerformed = innerType.effects.performed || collector.performed
                    MezzeType.SuspendedType(innerType.valueType, MezzeType.EffectRow.simple(mergedEffects, innerType.effects.rowVar, mergedPerformed))
                }
                else -> {
                    MezzeType.SuspendedType(resultResult.type, MezzeType.EffectRow.simple(collector.effects, null, collector.performed))
                }
            }
        } else {
            resultResult.type
        }

        return collector.toResult(resultType)
    }

    // ========================================================================
    // EFFECTS
    // ========================================================================

    private fun generatePerform(expr: ParsedPerform, context: ConstraintContext): ConstraintResult {
        // perform Handler in expr
        // The handler handles a specific effect, removing it from the expression's effects

        // CRITICAL: Pass the perform context to child expressions
        // This allows effect operations to substitute associated types from the performer
        // Matches OldTypeChecker.kt lines 7353-7354
        val contextWithPerform = context.copy(performContext = expr)
        val exprResult = generate(expr.body, contextWithPerform)

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
            // If the expression has no effects (neither tracked nor in type), providing handlers is meaningless
            if (handlerEffects.isNotEmpty() && allEffects.isEmpty()) {
                // Expression is pure but handlers provided
                val handlerNames = handlerEffects.map { it.first }.joinToString(", ")
                throw TypeCheckError(
                    message = "Expression is pure (has no effects), but handlers were provided: $handlerNames. " +
                        "Remove the perform block or use an expression that actually uses effects.",
                    loc = expr.loc,
                    errorType = ErrorType.CONSTRAINT_VIOLATION,
                    code = com.mezze.language.parsedast.diagnostics.ErrorCode.V0508_HANDLER_ON_PURE_EXPR,
                    hints = listOf("drop the `perform` wrapper — there are no effects to handle"),
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
     * Wrap a call's value type in `SuspendedType` if the callee's effect row
     * is non-empty / performed. Returns `(callType, declaredEffects,
     * declaredEffectInstances)`.
     *
     * Shared by ability method calls, inherent method calls, and regular
     * function calls — same logic in three places previously.
     */
    private fun wrapCallResultWithEffects(
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
    internal fun removeEffectsFromType(
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

    private fun generateEffectCall(expr: ParsedEffectCall, context: ConstraintContext): ConstraintResult {
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

        val effectInstances = if (effectInstance != null) setOf(effectInstance) else emptySet<MezzeType.EffectInstance>()

        return ConstraintResult(
            type = resultType,
            constraints = argResult.constraints + additionalConstraints,
            effects = setOf(effectName) + argResult.effects,
            effectInstances = effectInstances + argResult.effectInstances,
            performed = argResult.performed,
            abilityConstraints = argResult.abilityConstraints,
            associatedTypeConstraints = argResult.associatedTypeConstraints,
            undefinedVariables = argResult.undefinedVariables,
            usedVariables = argResult.usedVariables,
            referencedBindings = argResult.referencedBindings,
            methodCallResolutions = argResult.methodCallResolutions,
            callReturnTypes = argResult.callReturnTypes
        )
    }

    // ========================================================================
    // HELPER FUNCTIONS
    // ========================================================================

    /** Convert ParsedTypeAnnotation to MezzeType for field type resolution. */
    private fun convertParsedFieldType(annotation: ParsedTypeAnnotation): MezzeType {
        return when (annotation) {
            is com.mezze.language.parsedast.ParsedNamedType -> {
                PrimitiveTypes.fromName(annotation.name)?.let { return it }
                if (annotation.name.first().isLowerCase() || annotation.name.startsWith("'")) MezzeType.VarType(TypeVar(annotation.name))
                else com.mezze.language.typechecker.util.WellKnownTypeApp.of(annotation.name, emptyList())
            }
            is com.mezze.language.parsedast.ParsedRecordType -> MezzeType.RecordType(
                fields = annotation.entries.associate { it.name to convertParsedFieldType(it.type) },
                rowVar = if (annotation.isOpen) RowVar.userOpen() else null
            )
            is com.mezze.language.parsedast.ParsedFunctionType -> MezzeType.FunctionType(
                paramType = convertParsedFieldType(annotation.paramType),
                returnType = convertParsedFieldType(annotation.returnType),
                effects = annotation.effects?.let {
                    ContextBuilder.validateHermeticEffects(it.allEffectNames)
                    MezzeType.EffectRow.simple(it.effects.toSet(), null, it.isPerformed)
                }
            )
            is com.mezze.language.parsedast.ParsedTypeApp -> com.mezze.language.typechecker.util.WellKnownTypeApp.of(annotation.constructor, annotation.args.map { convertParsedFieldType(it) })
            is com.mezze.language.parsedast.ParsedVariantType -> MezzeType.VariantType(
                constructors = annotation.constructors.associate { it.name to it.dataType?.let { dt -> convertParsedFieldType(dt) } },
                rowVar = if (annotation.isOpen) RowVar.userOpen() else null,
                typeName = if (!annotation.isOpen) "<inline>" else null,
                nominalWall = !annotation.isOpen // #148: closed inline annotations are always walls
            )
            is com.mezze.language.parsedast.ParsedSeqType -> MezzeType.SeqType(convertParsedFieldType(annotation.elementType))
            is com.mezze.language.parsedast.ParsedQualifiedType -> com.mezze.language.typechecker.util.WellKnownTypeApp.of(
                (annotation.modulePath + annotation.typeName).joinToString("::"),
                annotation.args.map { convertParsedFieldType(it) }
            )
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
    private fun isRecordLiteralAccess(expr: ParsedExpr, context: ConstraintContext): Boolean {
        return when (expr) {
            is ParsedIdentifier -> {
                val key = BindingKey(qualifier = expr.qualifier?.segments?.lastOrNull(), localName = expr.localName).toKeyString()
                key in context.recordLiteralBindings
            }
            is ParsedMemberAccess -> isRecordLiteralAccess(expr.receiver, context)
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
     * #312: harvest effects from any closure-typed fields in an argument record.
     *
     * When a function is called whose declared effect row is open (`<Async, ...>`),
     * the row var stands for "whatever effects the closure arguments have". This
     * walks the argument's record type and pulls effect names out of any
     * `FunctionType` field's effect row. The results are added directly to the
     * call's `propagatedEffects` — bypassing the substitution machinery (which
     * leaks badly when row var bindings interact with type var unification).
     *
     * Conservative by design: only walks one level of record fields and only
     * inspects FunctionType's own `.effects.effects` set. Effect propagation
     * through deeper structures or transitive HOFs is intentionally out of scope.
     */
    private fun collectClosureArgEffects(argType: MezzeType): Set<String> {
        if (argType !is MezzeType.RecordType) return emptySet()
        val effects = mutableSetOf<String>()
        for ((_, fieldType) in argType.fields) {
            val fnType = fieldType as? MezzeType.FunctionType ?: continue
            val effRow = fnType.effects ?: continue
            effects.addAll(effRow.effects)
            for (instance in effRow.effectInstances) effects.add(instance.name)
        }
        return effects
    }

     /**
     * For other expressions, returns null.
     */
    private fun extractArgumentName(expr: ParsedExpr): String? {
        return when (expr) {
            is ParsedRecordLit -> {
                val fieldNames = expr.entries.mapNotNull { entry ->
                    when (entry) {
                        is ParsedRecordEntry.Field -> entry.name
                        else -> null  // ParsedRecordEntry.Spread doesn't have a name
                    }
                }
                when {
                    fieldNames.isEmpty() -> null
                    fieldNames.size == 1 -> fieldNames.first()
                    fieldNames.size <= 3 -> "{${fieldNames.joinToString(", ")}}"
                    else -> "{${fieldNames.take(3).joinToString(", ")}, ...}"
                }
            }
            is ParsedIdentifier -> expr.localName
            else -> null
        }
    }

    // Inline type annotation conversion (avoids dependency on util/TypeAnnotationConverter). This version does
    // import resolution, hermetic effect validation via ContextBuilder.validateHermeticEffects,
    // open row vars on records/variants, typeName on inline variants, and collection type
    // sugar (Seq/List/Set/Map) that require constraint context not available to the shared utility.
    /**
     * Convert a ParsedTypeAnnotation to a MezzeType.
     *
     * This is the ParsedAST-equivalent of ContextBuilder.convertTypeAnnotation.
     * Used for type annotations on let bindings and lambda parameters.
     */
    private fun convertParsedTypeAnnotation(annotation: ParsedTypeAnnotation): MezzeType {
        return when (annotation) {
            is ParsedNamedType -> {
                // Check primitive types first
                PrimitiveTypes.fromName(annotation.name)?.let { return it }
                // Type variable or type constructor
                if (annotation.name.first().isLowerCase() || annotation.name.startsWith("'")) {
                    MezzeType.VarType(TypeVar(annotation.name))
                } else {
                    com.mezze.language.typechecker.util.WellKnownTypeApp.of(annotation.name, emptyList())
                }
            }
            is ParsedQualifiedType -> {
                val qualifiedName = (annotation.modulePath + annotation.typeName).joinToString("::")
                com.mezze.language.typechecker.util.WellKnownTypeApp.of(
                    constructor = qualifiedName,
                    args = annotation.args.map { convertParsedTypeAnnotation(it) }
                )
            }
            is ParsedTypeApp -> {
                // Check for built-in collection types
                when (annotation.constructor) {
                    "Seq" -> if (annotation.args.size == 1) MezzeType.SeqType(convertParsedTypeAnnotation(annotation.args[0]))
                             else com.mezze.language.typechecker.util.WellKnownTypeApp.of(annotation.constructor, annotation.args.map { convertParsedTypeAnnotation(it) })
                    "List" -> if (annotation.args.size == 1) MezzeType.ListType(convertParsedTypeAnnotation(annotation.args[0]))
                              else com.mezze.language.typechecker.util.WellKnownTypeApp.of(annotation.constructor, annotation.args.map { convertParsedTypeAnnotation(it) })
                    "Set" -> if (annotation.args.size == 1) MezzeType.SetType(convertParsedTypeAnnotation(annotation.args[0]))
                             else com.mezze.language.typechecker.util.WellKnownTypeApp.of(annotation.constructor, annotation.args.map { convertParsedTypeAnnotation(it) })
                    "Map" -> if (annotation.args.size == 2) MezzeType.MapType(
                                 convertParsedTypeAnnotation(annotation.args[0]),
                                 convertParsedTypeAnnotation(annotation.args[1])
                             ) else com.mezze.language.typechecker.util.WellKnownTypeApp.of(annotation.constructor, annotation.args.map { convertParsedTypeAnnotation(it) })
                    else -> com.mezze.language.typechecker.util.WellKnownTypeApp.of(
                        constructor = annotation.constructor,
                        args = annotation.args.map { convertParsedTypeAnnotation(it) }
                    )
                }
            }
            is ParsedRecordType -> {
                MezzeType.RecordType(
                    fields = annotation.entries.associate { entry ->
                        entry.name to convertParsedTypeAnnotation(entry.type)
                    },
                    rowVar = if (annotation.isOpen) RowVar.userOpen() else null
                )
            }
            is ParsedVariantType -> {
                MezzeType.VariantType(
                    constructors = annotation.constructors.associate { ctor ->
                        ctor.name to ctor.dataType?.let { convertParsedTypeAnnotation(it) }
                    },
                    rowVar = if (annotation.isOpen) RowVar.userOpen() else null,
                    typeName = if (!annotation.isOpen) "<inline>" else null,
                    nominalWall = !annotation.isOpen // #148
                )
            }
            is ParsedFunctionType -> {
                MezzeType.FunctionType(
                    paramType = convertParsedTypeAnnotation(annotation.paramType),
                    returnType = convertParsedTypeAnnotation(annotation.returnType),
                    effects = annotation.effects?.let { effectRow ->
                        ContextBuilder.validateHermeticEffects(effectRow.allEffectNames)
                        MezzeType.EffectRow.simple(effectRow.effects.toSet(), null, effectRow.isPerformed)
                    },
                    abilityConstraints = annotation.abilityConstraints.map { constraint ->
                        MezzeType.FunctionAbilityConstraint(
                            abilityName = constraint.abilityName,
                            typeArgs = constraint.typeArgs.map { convertParsedTypeAnnotation(it) },
                            abilityHash = com.mezze.language.typechecker.WellKnownHashConstants.resolveAbilityHash(constraint.abilityName)
                        )
                    }
                )
            }
            is ParsedSeqType -> {
                MezzeType.SeqType(convertParsedTypeAnnotation(annotation.elementType))
            }
        }
    }
}
