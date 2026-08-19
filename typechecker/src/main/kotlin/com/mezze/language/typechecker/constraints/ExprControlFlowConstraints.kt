package com.mezze.language.typechecker.constraints

import com.mezze.language.ast.*
import com.mezze.language.parsedast.*
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.context.ContextBuilder
import com.mezze.language.ast.BuiltinTypes
import com.mezze.language.ast.solving.collectFreeTypeVars
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.typechecker.ErrorType
import com.mezze.language.typechecker.context.TypeScheme
import com.mezze.language.typechecker.solving.ConstraintSolver
import com.mezze.language.ast.solving.Substitution
import com.mezze.language.parsedast.diagnostics.ErrorCode

/**
 * Reject a non-linear pattern — one that binds the same variable name more than
 * once (`A { x = n, y = n }`, `[n, n]`). Without this, the two occurrences are
 * given DIFFERENT types with no unification between them (record-field binding is
 * last-wins), so `n` is typed at one field's type while the runtime may bind the
 * other — silent type confusion. (Or-pattern alternatives are a separate, allowed
 * case: they bind the SAME name across mutually-exclusive arms, and are unified
 * elsewhere. Here we only look WITHIN one alternative pattern.)
 */
private fun rejectDuplicateBinders(pattern: ParsedPattern, loc: Loc) {
    val names = pattern.extractBindings()
    val seen = HashSet<String>()
    val dupes = LinkedHashSet<String>()
    for (n in names) if (!seen.add(n)) dupes.add(n)
    if (dupes.isNotEmpty()) {
        throw TypeCheckError(
            "Pattern binds the same variable more than once: ${dupes.joinToString(", ")}. " +
                "Each binding in a pattern must use a distinct name (use `_` to ignore a value).",
            loc,
            code = ErrorCode.V0606_NON_LINEAR_PATTERN,
            hints = listOf("rename one of the duplicate bindings, or use `_` for the one you don't need"),
        )
    }
}

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
        rejectDuplicateBinders(firstPattern, expr.loc)
        val patternResult = patternGenerator.generate(firstPattern, scrutineeResult.type, context, expr.loc)
        collector.constraints.addAll(patternResult.constraints)
        patternWarnings.addAll(patternResult.warnings)

        val firstPatternBindings = patternResult.bindings.keys
        for (pattern in matchCase.patterns.drop(1)) {
            rejectDuplicateBinders(pattern, expr.loc)
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

    val resultVar = MezzeType.VarType(TypeVar.fresh())

    for (caseType in caseResultTypes) {
        collector.constraints.add(
            Constraint.Equality(
                left = caseType,
                right = resultVar,
                context = "Match case must have same type as other cases",
                isMatchCaseContext = true,
                loc = expr.loc,
            )
        )
    }

    // Effect soundness: when every arm's value is a concrete FUNCTION type,
    // surface a merged function type (UNIONing the arms' effect rows) as the
    // match result — not the bare `resultVar` VarType. Otherwise, CALLING the
    // match result (`(match … { True -> effectfulFn … }) {}`) sees an unresolved
    // VarType callee, so the call handler's `is FunctionType` effect-harvest is
    // skipped and the selected function's effects are LAUNDERED (a pure-typed
    // caller could perform them untracked). `if`/`else` desugars to `match`, so
    // this covers both. The Equality constraints above still unify every arm
    // with the result, so this only refines the reported type; it never loosens
    // it. Mirrors the concrete-receiver member-access fix in generateMemberAccess.
    val resultType = mergeArmFunctionEffects(caseResultTypes) ?: resultVar

    // Add this match's own unused case bindings to the collector's set (which
    // already holds any unused vars propagated up from nested sub-expressions).
    collector.unusedVars.addAll(unusedVars)
    return collector.toResult(resultType).copy(
        patternWarnings = patternWarnings,
        matchCaseBindings = matchCaseBindings,
    )
}

/**
 * If every arm's value type is a concrete [MezzeType.FunctionType] and at least
 * one carries an effect row, return a single function type whose effect row is
 * the UNION of all arms' effects (so calling the branch result performs any
 * effect any arm's function could). Returns null when the arms aren't all
 * concrete functions (fall back to the fresh result VarType) or none has
 * effects (nothing to preserve — keep inference unconstrained).
 */
private fun mergeArmFunctionEffects(armTypes: List<MezzeType>): MezzeType.FunctionType? {
    if (armTypes.isEmpty()) return null
    val fns = armTypes.map { it as? MezzeType.FunctionType ?: return null }

    val effects = mutableSetOf<String>()
    val effectInstances = mutableSetOf<MezzeType.EffectInstance>()
    var performed = false
    var anyRowVar: RowVar? = null
    for (fn in fns) {
        val row = fn.effects ?: continue
        effects.addAll(row.effects)
        effectInstances.addAll(row.effectInstances)
        performed = performed || row.performed
        if (row.rowVar != null) anyRowVar = row.rowVar
    }
    if (effects.isEmpty() && effectInstances.isEmpty() && !performed && anyRowVar == null) return null

    val mergedRow = MezzeType.EffectRow(
        effects = effects,
        effectInstances = effectInstances,
        rowVar = anyRowVar,
        performed = performed,
    )
    // Use the first arm as the structural template (param/return); the Equality
    // constraints guarantee all arms unify, so any is representative.
    return fns.first().copy(effects = mergedRow)
}

/**
 * #76 let-polymorphism for do-block bindings. Generalizes an un-annotated
 * do-block `let` the way a top-level binding does: solve the constraints
 * accumulated so far (so the binding type is resolved), then quantify the type
 * vars that are free in the solved binding type but NOT free in the outer
 * context. Quantifying the *solved* type is what keeps this sound — a body that
 * pins a param (`{x} -> x + 1`) resolves that var to a concrete type, so it is
 * no longer free and is not quantified, leaving the binding monomorphic. Returns
 * one binding per pattern var. Falls back to monomorphic bindings when no
 * registry is wired (bare construction) or solving fails.
 */
private fun ExpressionConstraintGenerator.generalizeDoBlockBinding(
    patternBindings: Map<String, MezzeType>,
    accumulatedConstraints: List<Constraint>,
    bindingAbilityConstraints: List<Constraint.AbilityRequired>,
    outerContext: ConstraintContext,
): Map<BindingKey, TypingContext.Binding> {
    val reg = this.registry
    val monomorphic = {
        patternBindings.mapValues { (_, type) -> type }
            .map { (name, type) -> BindingKey.unqualified(name) to TypingContext.Binding.Monomorphic(type = type) as TypingContext.Binding }
            .toMap()
    }
    if (reg == null) return monomorphic()

    val solveResult = ConstraintSolver(outerContext.typingContext, reg).solve(accumulatedConstraints.toList())
    // A failure here isn't ours to report — the global solve surfaces real errors
    // later. We just can't generalize without a substitution, so stay monomorphic.
    val substitution = when (solveResult) {
        is ConstraintSolver.SolveResult.Success -> solveResult.substitution
        is ConstraintSolver.SolveResult.Failure -> return monomorphic()
    }

    // Vars free in the outer context must never be quantified — they may be
    // shared with the enclosing scope (e.g. a captured `n: 't`).
    val contextVars = doBlockContextFreeVars(outerContext.typingContext)

    return patternBindings.map { (name, rawType) ->
        val solvedType = substitution.apply(rawType)
        val quantifiable = collectFreeTypeVars(solvedType) - contextVars
        val binding: TypingContext.Binding = if (quantifiable.isNotEmpty()) {
            // Carry any ability constraint whose (resolved) type var is one we're
            // quantifying — otherwise a generalized `{a, b} -> a + b` would lose its
            // NumAdd requirement and unify against non-numeric args unsoundly.
            // Mirrors ConstraintGenerator.buildBindingForContext.
            val quantifiableIds = quantifiable.map { it.id }.toSet()
            val carried = bindingAbilityConstraints.mapNotNull { c ->
                val resolved = substitution.apply(MezzeType.VarType(c.typeVar))
                val varId = if (resolved is MezzeType.VarType) resolved.variable.id else c.typeVar.id
                if (varId in quantifiableIds) {
                    val quantVar = quantifiable.find { it.id == varId }
                    AbilityConstraint(
                        abilityName = c.abilityName,
                        typeParam = quantVar?.name ?: c.typeVar.name,
                        typeArgs = c.typeArgs.map { substitution.apply(it) }
                    )
                } else null
            }
            TypingContext.Binding.Polymorphic(
                TypeScheme(quantifiedVars = quantifiable.toList(), constraints = carried, type = solvedType)
            )
        } else {
            TypingContext.Binding.Monomorphic(type = solvedType)
        }
        BindingKey.unqualified(name) to binding
    }.toMap()
}

/** True if [lambda] captures a term variable from the enclosing scope — i.e. it
 *  references a name (in [usedVariables]) that is not one of its own parameters
 *  but is bound in [outerContext]. Such closures are left monomorphic (#76): the
 *  captured value's monomorphization/lowering doesn't compose with generalizing
 *  the closure. */
private fun doBlockLambdaCaptures(
    lambda: ParsedLambda,
    usedVariables: Set<String>,
    outerContext: ConstraintContext,
): Boolean {
    val paramNames = when (val params = lambda.params) {
        is ParsedLambdaParams.Untyped -> params.names.toSet()
        is ParsedLambdaParams.Typed -> params.fields.map { it.name }.toSet()
    }
    return usedVariables.any { name ->
        name !in paramNames &&
            outerContext.typingContext.lookup(BindingKey.unqualified(name)) is TypingContext.LookupResult.Found
    }
}

/** Type vars free in the context's bindings (unquantified vars for polymorphic
 *  schemes). Mirrors ConstraintGenerator.getContextFreeVars, which is private. */
private fun doBlockContextFreeVars(context: TypingContext): Set<TypeVar> {
    val freeVars = mutableSetOf<TypeVar>()
    for ((_, binding) in context.getAllBindings()) {
        when (binding) {
            is TypingContext.Binding.Monomorphic -> freeVars.addAll(collectFreeTypeVars(binding.type))
            is TypingContext.Binding.Polymorphic ->
                freeVars.addAll(collectFreeTypeVars(binding.scheme.type) - binding.scheme.quantifiedVars.toSet())
        }
    }
    return freeVars
}

internal fun ExpressionConstraintGenerator.generateDo(expr: ParsedDo, context: ConstraintContext): ConstraintResult {

    var currentContext = context.clearExpectedType().notInTailPosition()
    val collector = ConstraintCollector()

    val patternGenerator = PatternConstraintGenerator()

    // #84 linter: name + location of every do-block `let` binding, checked for
    // use against the collector's accumulated used-set after all statements are
    // processed (a name may be referenced by a *later* statement, so the check
    // can only run once the whole block has been generated).
    val letBindings = mutableListOf<Pair<String, Loc?>>()

    // #86 case-A shadowing: names bound by THIS do-block's own `let`s. Kept
    // local (not in the context) so a NESTED do-block starts fresh — reusing an
    // outer block's let name across the block boundary is legal (case B).
    val blockLets = mutableSetOf<String>()

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

                // #86 case-A shadowing: a `let` that reuses a name already bound
                // in THIS function scope — the enclosing lambda's param, or an
                // earlier `let` in this same do-block — silently overwrites it.
                // Reject it (T0306), the same class of mistake as a duplicate
                // lambda param (#79). `_`-prefixed names opt out (a throwaway
                // may legitimately repeat). Shadowing an outer param/global name
                // is in neither set, so it stays legal (case B); a nested
                // do-block's own lets are tracked in a fresh `blockLets`.
                for (varName in variableNames) {
                    if (varName.startsWith("_")) continue
                    if (varName in currentContext.currentScopeBindings || varName in blockLets) {
                        throw TypeCheckError(
                            message = "'$varName' is already bound in this scope. This `let` " +
                                "silently overwrites the earlier binding (a parameter or an " +
                                "earlier `let` in the same block). Rename one of them.",
                            loc = stmt.pattern.loc,
                            errorType = ErrorType.TYPE_MISMATCH,
                            code = ErrorCode.T0306_SHADOWED_BINDING_IN_SCOPE,
                        )
                    }
                }
                blockLets.addAll(variableNames)

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
                            arg is ParsedNamedType && arg.name.isTypeVarName()
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
                } else if (stmt.pattern is ParsedVariablePattern && stmt.typeAnnotation == null &&
                           stmt.value is ParsedLambda &&
                           !doBlockLambdaCaptures(stmt.value as ParsedLambda, exprResult.usedVariables, currentContext)) {
                    // #76: generalize an un-annotated do-block lambda the same way a
                    // top-level binding does, so it can be used at several types
                    // within the block. Restricted to lambda bindings that DON'T
                    // capture an outer term variable — a closure that captures (e.g.
                    // `{x} -> {sum = x + base}` capturing `base`) is left monomorphic
                    // because generalizing it interacts badly with monomorphization
                    // and lowering of the captured value. A non-lambda (`let x = 5`)
                    // has a concrete type with nothing to quantify. We must solve
                    // first (the binding type is still full of unsolved vars during
                    // generation) to distinguish `{x} -> x` (quantify) from
                    // `{x} -> x + 1` (var pinned to Int — must NOT quantify). Falls
                    // back to monomorphic when no registry is wired or solving fails.
                    generalizeDoBlockBinding(
                        patternBindings = patternResult.bindings,
                        accumulatedConstraints = collector.constraints,
                        bindingAbilityConstraints = exprResult.abilityConstraints,
                        outerContext = currentContext,
                    )
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

                // #84 linter: remember this let's bound names to check for use
                // once the whole block is generated. Skip `_`-prefixed names
                // (intentionally unused) here so they never warn.
                for (varName in variableNames) {
                    if (!varName.startsWith("_")) {
                        letBindings.add(varName to (patternResult.bindingLocations[varName] ?: stmt.loc))
                    }
                }
            }
            is ParsedStatement.Expr -> {
                val exprResult = generate(stmt.expr, currentContext)
                collector.merge(exprResult)
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
            is MezzeType.SuspendType -> {
                val mergedEffects = innerType.effects.effects + collector.effects
                val mergedPerformed = innerType.effects.performed || collector.performed
                MezzeType.SuspendType(innerType.valueType, MezzeType.EffectRow.simple(mergedEffects, innerType.effects.rowVar, mergedPerformed))
            }
            else -> {
                MezzeType.SuspendType(resultResult.type, MezzeType.EffectRow.simple(collector.effects, null, collector.performed))
            }
        }
    } else {
        resultResult.type
    }

    // #84 linter: a do-block `let` whose name is never referenced by any later
    // statement, the block result, or its own value is unused. `collector.usedVars`
    // has accumulated every referenced name across the whole block by now.
    for ((varName, loc) in letBindings) {
        if (varName !in collector.usedVars) {
            collector.unusedVars.add(ConstraintResult.UnusedVariable(name = varName, loc = loc))
        }
    }

    return collector.toResult(resultType)
}

