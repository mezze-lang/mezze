package com.mezze.language.typechecker.constraints

import com.mezze.language.ast.*
import com.mezze.language.parsedast.*
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.context.ContextBuilder
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.parsedast.diagnostics.ErrorCode

/**
 * Match and do-block constraint generators.
 *
 * Extension functions on [ExpressionConstraintGenerator].
 */

// ========================================================================
// CONTROL FLOW
// ========================================================================

// NOTE: IfExpression is unified with MatchExpression at the type-checking level.
// The generateIf() function has been removed - IfExpression is now transformed to
// MatchExpression via toMatchExpression() and handled by generateMatch().
// This simplifies the typechecker while preserving:
// - IfExpression in AST for tooling (error messages, LSP, formatting)
// - IfNode in interpreter for optimal runtime performance (simple branch profile)

internal fun ExpressionConstraintGenerator.generateMatch(expr: ParsedMatch, context: ConstraintContext): ConstraintResult {
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

internal fun ExpressionConstraintGenerator.generateDo(expr: ParsedDo, context: ConstraintContext): ConstraintResult {
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
                    val typeVars = com.mezze.language.ast.solving.collectFreeTypeVars(annotatedType)

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

