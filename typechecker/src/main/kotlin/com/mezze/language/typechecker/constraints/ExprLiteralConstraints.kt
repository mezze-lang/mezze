package com.mezze.language.typechecker.constraints

import com.mezze.language.ast.*
import com.mezze.language.parsedast.*
import com.mezze.language.typechecker.abilities.BuiltInAbilities
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.dependencies.DefinitionRef
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.parsedast.diagnostics.ErrorCode

/**
 * Literal, identifier, operator, and pipe constraint generators.
 *
 * Extension functions on [ExpressionConstraintGenerator] — split from the
 * original monolithic file for navigability.
 */

/**
 * Generate constraints for a binary operator by desugaring to method call.
 * `x + y` → `x.add {r = y}` — but with better error messages that reference the operator.
 */
internal fun ExpressionConstraintGenerator.generateBinaryOp(expr: ParsedBinaryOp, context: ConstraintContext): ConstraintResult {
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

    return generate(desugared, context)
}

/**
 * Generate constraints for a unary operator by desugaring to method call.
 * `-x` → `x.neg {}`, `!x` → `x.not {}`
 */
internal fun ExpressionConstraintGenerator.generateUnaryOp(expr: ParsedUnaryOp, context: ConstraintContext): ConstraintResult {
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

    return generate(desugared, context)
}

// ========================================================================
// LITERALS
// ========================================================================

internal fun ExpressionConstraintGenerator.generateIntLiteral(expr: ParsedIntLit, context: ConstraintContext): ConstraintResult {
    // Int literal has type Int, no constraints
    return ConstraintResult.simple(MezzeType.IntType)
}

internal fun ExpressionConstraintGenerator.generateNatLiteral(expr: ParsedNatLit, context: ConstraintContext): ConstraintResult {
    // Nat literal has type Nat, no constraints
    return ConstraintResult.simple(MezzeType.NatType)
}

internal fun ExpressionConstraintGenerator.generateFloatLiteral(expr: ParsedFloatLit, context: ConstraintContext): ConstraintResult {
    // Float literal has type Float, no constraints
    return ConstraintResult.simple(MezzeType.FloatType)
}

internal fun ExpressionConstraintGenerator.generateFractionLiteral(expr: ParsedFracLit, context: ConstraintContext): ConstraintResult {
    // Fraction literal has type Frac, no constraints
    return ConstraintResult.simple(MezzeType.FracType)
}

internal fun ExpressionConstraintGenerator.generateStringLiteral(expr: ParsedStringLit, context: ConstraintContext): ConstraintResult {
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

internal fun ExpressionConstraintGenerator.generateSeqLiteral(expr: ParsedSeqLit, context: ConstraintContext): ConstraintResult {
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

internal fun ExpressionConstraintGenerator.generateIdentifier(expr: ParsedIdentifier, context: ConstraintContext): ConstraintResult {
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

internal fun ExpressionConstraintGenerator.generatePipePlaceholder(expr: ParsedPipePlaceholder, context: ConstraintContext): ConstraintResult {
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

internal fun ExpressionConstraintGenerator.generatePipeExpression(expr: ParsedPipe, context: ConstraintContext): ConstraintResult {
    // PipeExpression should be desugared before constraint generation
    // If we reach here, there's a bug in the transformation phase
    throw com.mezze.language.typechecker.TypeCheckError(
        "Internal error: PipeExpression was not desugared before type checking. " +
        "This is a compiler bug.",
        expr.loc,
        code = ErrorCode.I0001_UNKNOWN_TYPECHECK_FAILURE,
    )
}
