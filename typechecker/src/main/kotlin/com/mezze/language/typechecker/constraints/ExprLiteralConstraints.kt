package com.mezze.language.typechecker.constraints

import com.mezze.language.ast.*
import com.mezze.language.parsedast.*
import com.mezze.language.typechecker.abilities.BuiltInAbilities
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.dependencies.DefinitionRef
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.ast.SyntaxMetadata
import com.mezze.language.parsedast.diagnostics.ErrorCode

internal fun ExpressionConstraintGenerator.generateBinaryOp(expr: ParsedBinaryOp, context: ConstraintContext): ConstraintResult {
    val op = OperatorMapping.getBinaryOp(expr.op)
        ?: throw TypeCheckError(
            "Unknown operator: ${expr.op}", expr.loc,
            code = ErrorCode.T0401_UNKNOWN_OPERATOR,
            hints = listOf("binary operators desugar to method calls; check that `${expr.op}` is a known operator"),
        )

    val desugared = ParsedFunctionCall(
        function = ParsedMemberAccess(expr.left, op.method, expr.loc),
        arguments = ParsedRecordLit(listOf(ParsedRecordEntry.Field("r", expr.right, expr.loc)), expr.loc),
        loc = expr.loc
    )

    SyntaxMetadata.markAsOperator(expr.loc, expr.op)

    return generate(desugared, context)
}

internal fun ExpressionConstraintGenerator.generateUnaryOp(expr: ParsedUnaryOp, context: ConstraintContext): ConstraintResult {
    val op = OperatorMapping.getUnaryOp(expr.op)
        ?: throw TypeCheckError(
            "Unknown operator: ${expr.op}", expr.loc,
            code = ErrorCode.T0401_UNKNOWN_OPERATOR,
            hints = listOf("unary operators desugar to method calls; check that `${expr.op}` is a known operator"),
        )

    val desugared = ParsedFunctionCall(
        function = ParsedMemberAccess(expr.operand, op.method, expr.loc),
        arguments = ParsedRecordLit(emptyList(), expr.loc),
        loc = expr.loc
    )

    SyntaxMetadata.markAsOperator(expr.loc, expr.op)

    return generate(desugared, context)
}

internal fun ExpressionConstraintGenerator.generateIntLiteral(expr: ParsedIntLit, context: ConstraintContext) = ConstraintResult.simple(MezzeType.IntType)
internal fun ExpressionConstraintGenerator.generateNatLiteral(expr: ParsedNatLit, context: ConstraintContext) = ConstraintResult.simple(MezzeType.NatType)
internal fun ExpressionConstraintGenerator.generateFloatLiteral(expr: ParsedFloatLit, context: ConstraintContext) = ConstraintResult.simple(MezzeType.FloatType)
internal fun ExpressionConstraintGenerator.generateFractionLiteral(expr: ParsedFracLit, context: ConstraintContext) = ConstraintResult.simple(MezzeType.FracType)

internal fun ExpressionConstraintGenerator.generateStringLiteral(expr: ParsedStringLit, context: ConstraintContext): ConstraintResult {
    val isInterpolated = expr.parts.any { it is ParsedStringPart.Interpolation }
    if (!isInterpolated) return ConstraintResult.simple(MezzeType.StringType)

    val collector = ConstraintCollector()

    var interpolationIndex = 0
    for (part in expr.parts) {
        when (part) {
            is ParsedStringPart.Literal -> {
            }
            is ParsedStringPart.Interpolation -> {
                val exprResult = generate(part.expr, context)
                collector.merge(exprResult)
                collector.constraints.add(
                    Constraint.Equality(
                        left = exprResult.type,
                        right = MezzeType.StringType,
                        context = "String interpolation requires String type",
                        // Point at the interpolated EXPRESSION, not the whole
                        // string literal — so the T0201 squiggle and its
                        // `.to_str {}` quick-fix target just `$( … )`'s inner expr.
                        loc = part.expr.loc,
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
        val elementType = MezzeType.VarType(TypeVar.fresh())
        return ConstraintResult.simple(MezzeType.SeqType(elementType))
    }

    val elementType = MezzeType.VarType(TypeVar.fresh())
    val collector = ConstraintCollector()

    var elementIndex = 0
    for (element in expr.elements) {
        when (element) {
            is ParsedSeqElement.Regular -> {
                val result = generate(element.expr, context)
                collector.merge(result)
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

internal fun ExpressionConstraintGenerator.generateIdentifier(expr: ParsedIdentifier, context: ConstraintContext): ConstraintResult {
    val lookupKey = BindingKey(qualifier = expr.qualifier?.segments?.lastOrNull(), localName = expr.localName)
    val lookupKeyString = lookupKey.toKeyString()

    return when (val result = context.typingContext.lookup(lookupKey)) {
        is TypingContext.LookupResult.Found -> {
            when (val binding = result.binding) {
                is TypingContext.Binding.Monomorphic -> {
                    val type = binding.type
                    val referencedBinding = DefinitionRef.ByName(binding.sourceModule, lookupKeyString)
                    when (type) {
                        is MezzeType.SuspendType -> ConstraintResult(
                            type = type,
                            constraints = emptyList(),
                            effects = type.effects.effects,
                            effectInstances = type.effects.effectInstances,
                            usedVariables = setOf(lookupKeyString),
                            referencedBindings = setOf(referencedBinding)
                        )
                        else -> ConstraintResult(
                            type = type,
                            constraints = emptyList(),
                            usedVariables = setOf(lookupKeyString),
                            referencedBindings = setOf(referencedBinding)
                        )
                    }
                }
                is TypingContext.Binding.Polymorphic -> {
                    val instantiation = binding.scheme.instantiate()

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

                    val type = instantiation.type
                    val effects = when (type) {
                        is MezzeType.SuspendType -> type.effects.effects
                        else -> emptySet()
                    }
                    val effectInstances = when (type) {
                        is MezzeType.SuspendType -> type.effects.effectInstances
                        else -> emptySet()
                    }

                    val referencedBinding = DefinitionRef.ByName(binding.sourceModule, lookupKeyString)

                    ConstraintResult(
                        type = type,
                        constraints = emptyList(),
                        effects = effects,
                        effectInstances = effectInstances,
                        abilityConstraints = abilityConstraints,
                        usedVariables = setOf(lookupKeyString),
                        referencedBindings = setOf(referencedBinding)
                    )
                }
            }
        }
        is TypingContext.LookupResult.NotFound -> {

            if (expr.localName.startsWith("__native_") || expr.localName == "crash") {
                val freshType = MezzeType.VarType(TypeVar.fresh())
                return ConstraintResult(
                    type = freshType,
                    constraints = emptyList(),
                    referencedBindings = setOf(DefinitionRef.ByName(null, lookupKeyString))
                )
            }

            val isBuiltInAbility = BuiltInAbilities.isBuiltInAbility(lookupKeyString)
            val isUserDefinedAbility = lookupKeyString in context.abilityNames
            val isEffect = lookupKeyString in context.effectNames

            if (isBuiltInAbility || isUserDefinedAbility || isEffect) {
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

            if (expr.localName.first().isUpperCase()) {
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
    throw TypeCheckError(
        "'@' can only be used inside a pipe expression (|>).\n\n" +
        "  hint: Use '@' to refer to the piped value:\n" +
        "        value |> func { arg = @ }",
        expr.loc,
        code = ErrorCode.T0402_PIPE_PLACEHOLDER_MISUSE,
        hints = listOf("`@` is only valid in pipe rhs: `x |> f { arg = @ }`"),
    )
}

internal fun ExpressionConstraintGenerator.generatePipeExpression(expr: ParsedPipe, context: ConstraintContext): ConstraintResult {
    throw TypeCheckError(
        "Internal error: PipeExpression was not desugared before type checking. " +
        "This is a compiler bug.",
        expr.loc,
        code = ErrorCode.I0001_UNKNOWN_TYPECHECK_FAILURE,
    )
}
