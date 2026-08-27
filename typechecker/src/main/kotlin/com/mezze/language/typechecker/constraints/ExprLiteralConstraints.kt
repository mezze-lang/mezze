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
    val op = expr.op.resolved
        ?: throw TypeCheckError(
            "Unknown operator: ${expr.op.symbol}", expr.loc,
            code = ErrorCode.T0401_UNKNOWN_OPERATOR,
            hints = listOf("binary operators desugar to method calls; check that `${expr.op.symbol}` is a known operator"),
        )

    val desugared = ParsedFunctionCall(
        function = ParsedMemberAccess(expr.left, op.method, expr.loc),
        arguments = ParsedRecordLit(listOf(ParsedRecordEntry.Field("r", expr.right, expr.loc)), expr.loc),
        loc = expr.loc
    )

    SyntaxMetadata.markAsOperator(expr.loc, expr.op.symbol)

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

                    // When a class-param obligation's receiver was concrete at
                    // generalization (`NumAdd Int 'a`, the `Int` from a literal), pin
                    // the freshened receiver var to that concrete type so the
                    // AbilityRequired above sees the real receiver and can REJECT a
                    // bad one — e.g. `f {x = "s"}` must fail on the missing
                    // `NumAdd Int Str` impl, not launder past an unresolved var.
                    val concreteReceiverEqualities = instantiation.freshConstraints.mapNotNull { freshConstraint ->
                        freshConstraint.concreteReceiver?.let { receiver ->
                            Constraint.Equality(
                                left = MezzeType.VarType(freshConstraint.typeVar),
                                right = receiver,
                                context = "Concrete ability receiver for ${freshConstraint.abilityName}",
                                loc = expr.loc
                            )
                        }
                    }

                    // For each instantiated ability obligation carrying associated-type
                    // bindings (`having 'out = 'c`), emit an AssociatedTypeResolution
                    // over the freshened vars. This is what pins a called polymorphic
                    // `+`-fn's result: `my_func {x=5, y=8.6}` resolves `'out` to Float
                    // once the args make `'l`/`'r` concrete. AbilityRequired above still
                    // enforces rejection of unimplemented pairs; this only resolves.
                    val associatedTypeResolutions = instantiation.freshConstraints.mapNotNull { freshConstraint ->
                        if (freshConstraint.associatedTypeBindings.isEmpty()) return@mapNotNull null
                        val assocVars = freshConstraint.associatedTypeBindings.mapNotNull { (name, bound) ->
                            (bound as? MezzeType.VarType)?.let { name.removePrefix("'") to it.variable }
                        }.toMap()
                        if (assocVars.isEmpty()) return@mapNotNull null
                        val classArgVars = (listOf(MezzeType.VarType(freshConstraint.typeVar)) + freshConstraint.typeArgs)
                            .mapNotNull { (it as? MezzeType.VarType)?.variable }
                        Constraint.AssociatedTypeResolution(
                            abilityName = freshConstraint.abilityName,
                            primaryTypeVar = freshConstraint.typeVar,
                            associatedTypeVars = assocVars,
                            classArgTypeVars = classArgVars,
                            context = "Call-site associated type resolution for ${freshConstraint.abilityName}",
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
                        constraints = concreteReceiverEqualities,
                        effects = effects,
                        effectInstances = effectInstances,
                        abilityConstraints = abilityConstraints,
                        associatedTypeConstraints = associatedTypeResolutions,
                        usedVariables = setOf(lookupKeyString),
                        referencedBindings = setOf(referencedBinding)
                    )
                }
            }
        }
        is TypingContext.LookupResult.NotFound -> {

            // #109: `__native_*` intrinsics are stdlib-only. User code that
            // references one falls through to the normal "undefined variable"
            // error path, preventing the type-check bypass (fresh-var fast
            // path) that was silently accepting `__native_str_eq { l = u, r = u }`
            // where u: UserId — leaking nominal opacity through the native
            // boundary. `crash` remains available everywhere (universal panic).
            // #281: recognise the intrinsic names via MagicParams constants
            // (single-sourced) rather than raw string literals, but keep the
            // #109 stdlib-only gate on the native prefix.
            if (expr.localName == com.mezze.language.ast.MagicParams.CRASH ||
                (expr.localName.startsWith(com.mezze.language.ast.MagicParams.NATIVE_PREFIX) && isStdlibModule)) {
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
