package com.mezze.language.typechecker.constraints

import com.mezze.language.ast.*
import com.mezze.language.parsedast.*
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.context.ContextBuilder
import com.mezze.language.ast.BuiltinTypes
import com.mezze.language.ast.solving.collectFreeTypeVars
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.typechecker.context.TypeScheme
import com.mezze.language.parsedast.diagnostics.ErrorCode

internal fun ExpressionConstraintGenerator.generateMatch(expr: ParsedMatch, context: ConstraintContext): ConstraintResult {

    val scrutineeResult = generate(expr.scrutinee, context.notInTailPosition())

    val collector = ConstraintCollector()
    collector.merge(scrutineeResult)
    val unusedVars = mutableListOf<ConstraintResult.UnusedVariable>()
    val patternWarnings = mutableListOf<String>()
    // #195 mono-fix #1: per-match-case binding types
    val matchCaseBindings = mutableMapOf<Loc, Map<String, MezzeType>>()
    matchCaseBindings.putAll(scrutineeResult.matchCaseBindings)

    val hasWildcard = expr.cases.any { matchCase ->
        matchCase.patterns.any { pattern ->
            pattern is ParsedWildcardPattern || pattern is ParsedVariablePattern
        }
    }
    if (hasWildcard) {
        collector.constraints.add(
            Constraint.MustBeOpen(
                type = scrutineeResult.type,
                bindingName = context.currentBindingName,
                context = "Match has wildcard pattern - scrutinee may have additional constructors",
                loc = expr.loc,
            )
        )
    }

    val patternGenerator = PatternConstraintGenerator()
    val caseResultTypes = mutableListOf<MezzeType>()

    for (matchCase in expr.cases) {

        val firstPattern = matchCase.patterns.first()
        val patternResult = patternGenerator.generate(firstPattern, scrutineeResult.type, context, expr.loc)
        collector.constraints.addAll(patternResult.constraints)
        patternWarnings.addAll(patternResult.warnings)

        val firstPatternBindings = patternResult.bindings.keys
        for (pattern in matchCase.patterns.drop(1)) {
            val orPatternResult = patternGenerator.generate(pattern, scrutineeResult.type, context, expr.loc)
            collector.constraints.addAll(orPatternResult.constraints)
            patternWarnings.addAll(orPatternResult.warnings)

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
                    code = ErrorCode.V0605_OR_PATTERN_BINDS_DIFFERENT_VARS,
                    hints = listOf("all alternatives in an or-pattern must bind the same variables"),
                )
            }

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

        var caseContext = context
        for ((varName, varType) in patternResult.bindings) {
            caseContext = caseContext.extend(varName, varType)
        }
        // #195 mono-fix #1: stash this case's bindings keyed by the case
        // Loc so TypedAstBuilder can recover the solver's TyVars (not
        // disconnected placeholders) when emitting PatternBinding + the
        // guard/body identifier context.
        matchCaseBindings[matchCase.loc] = patternResult.bindings

        val guard = matchCase.guard
        var guardUsedVariables: Set<String> = emptySet()
        if (guard != null) {
            val guardResult = generate(guard, caseContext.notInTailPosition())
            collector.merge(guardResult)
            guardUsedVariables = guardResult.usedVariables
            // #218: guard's methodCallResolutions and callReturnTypes were
            // being dropped on the floor — every `w.eq` inside a match guard
            // never reached TypedAstBuilder, which fell back to a plain
            // TypedFieldAccess emission with no `it` injection. The mono
            // path then crashed because the impl body's `it` slot stayed
            // null. Same propagation pattern as the case-body's path below.
            collector.methodCallResolutions.putAll(guardResult.methodCallResolutions)
            collector.callReturnTypes.putAll(guardResult.callReturnTypes)

            collector.constraints.add(
                Constraint.Equality(
                    left = guardResult.type,
                    right = MezzeType.VariantType(
                        constructors = mapOf(BuiltinTypes.TRUE to null, BuiltinTypes.FALSE to null),
                        rowVar = null
                    ),
                    context = "Match guard must be Bool",
                    loc = guard.loc,
                )
            )
        }

        for (ifLetBinding in matchCase.ifLetBindings) {
            val bindingExprResult = generate(ifLetBinding.expr, caseContext.notInTailPosition())
            collector.merge(bindingExprResult)

            val ifLetPatternResult = patternGenerator.generate(
                ifLetBinding.pattern,
                bindingExprResult.type,
                caseContext,
                ifLetBinding.loc
            )
            collector.constraints.addAll(ifLetPatternResult.constraints)
            patternWarnings.addAll(ifLetPatternResult.warnings)

            for ((varName, varType) in ifLetPatternResult.bindings) {
                caseContext = caseContext.extend(varName, varType)
            }
        }

        val caseBodyResult = generate(matchCase.body, caseContext)
        collector.merge(caseBodyResult)
        matchCaseBindings.putAll(caseBodyResult.matchCaseBindings)

        val usedVarsInBody = caseBodyResult.usedVariables + guardUsedVariables
        for ((varName, _) in patternResult.bindings) {
            if (!varName.startsWith("_") && varName !in usedVarsInBody) {
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

        caseResultTypes.add(caseBodyResult.type)
    }

    val resultType = MezzeType.VarType(TypeVar.fresh())

    for (caseType in caseResultTypes) {
        collector.constraints.add(
            Constraint.Equality(
                left = caseType,
                right = resultType,
                context = "Match case must have same type as other cases",
                isMatchCaseContext = true,
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

internal fun ExpressionConstraintGenerator.generateDo(expr: ParsedDo, context: ConstraintContext): ConstraintResult {

    var currentContext = context.clearExpectedType().notInTailPosition()
    val collector = ConstraintCollector()

    val patternGenerator = PatternConstraintGenerator()

    for (stmt in expr.statements) {
        when (stmt) {
            is ParsedStatement.Let -> {
                val annotatedType = stmt.typeAnnotation?.let { annotation ->
                    convertParsedTypeAnnotation(annotation)
                }

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

                val exprContext = if (annotatedType != null) {
                    preBindContext.withExpectedType(annotatedType)
                } else {
                    preBindContext
                }
                val exprResult = generate(stmt.value, exprContext)

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

                val variableNames = stmt.pattern.extractBindings()
                for (varName in variableNames) {
                    nestedBindingTypes[varName] = bindingType
                }

                val patternResult = patternGenerator.generate(
                    stmt.pattern,
                    bindingType,
                    currentContext,
                    stmt.value.loc
                )
                collector.constraints.addAll(patternResult.constraints)

                val typingContext = currentContext.typingContext

                val contextBindings = if (stmt.pattern is ParsedVariablePattern &&
                    stmt.typeAnnotation is ParsedFunctionType &&
                    (stmt.typeAnnotation as ParsedFunctionType).abilityConstraints.isNotEmpty()
                ) {
                    val annotation = stmt.typeAnnotation as ParsedFunctionType
                    val annotatedType = convertParsedTypeAnnotation(annotation)

                    val typeVars = collectFreeTypeVars(annotatedType)

                    val abilityConstraints = annotation.abilityConstraints.mapNotNull { constraint ->
                        val typeVarArg = constraint.typeArgs.find { arg ->
                            arg is ParsedNamedType &&
                            (arg.name.startsWith("'") || arg.name.first().isLowerCase())
                        }
                        val typeParamName = when (typeVarArg) {
                            is ParsedNamedType -> typeVarArg.name
                            else -> null
                        }
                        if (typeParamName != null) {
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

                    val scheme = TypeScheme(
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
                throw IllegalStateException(
                    "ParsedStatement.Bind reached constraint generation at ${stmt.loc} — " +
                    "DoBindDesugar must rewrite Bind statements to Let before this phase."
                )
            }
        }
    }

    val resultContext = if (context.expectedType != null) {
        currentContext.withExpectedType(context.expectedType).copy(inTailPosition = context.inTailPosition)
    } else {
        currentContext.copy(inTailPosition = context.inTailPosition)
    }
    val resultResult = generate(expr.result, resultContext)
    collector.merge(resultResult)

    val resultType = if (collector.effects.isNotEmpty() || collector.performed) {
        when (val innerType = resultResult.type) {
            is MezzeType.SuspendedType -> {
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

