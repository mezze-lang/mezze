package com.mezze.language.typechecker.constraints

import com.mezze.language.ast.*
import com.mezze.language.parsedast.*
import com.mezze.language.typechecker.context.DeclarationRegistry
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.mono.unwrapSuspended
import com.mezze.language.typechecker.solving.ConstraintSolver
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.typechecker.WellKnownHashConstants
import com.mezze.language.typechecker.util.WellKnownTypeApp
import com.mezze.language.parsedast.diagnostics.ErrorCode
import com.mezze.language.typedast.Hash

internal class ImplValidator(
    private val expressionGenerator: ExpressionConstraintGenerator,
    private val registry: DeclarationRegistry,
    private val performerToEffect: Map<String, String>,
    private val performerAddedEffects: MutableMap<String, Set<String>>,
    private val abilityNames: Set<String>,
    private val effectNames: Set<String>,
    private val convertTypeAnnotation: (ParsedTypeAnnotation) -> MezzeType,
) {
    /**
     * asymmetry fix: ability preconditions inferred from impl-operation BODIES,
     * keyed by the impl's content hash. `validateAbilityImplementation` fills
     * this while checking each impl; the caller (via [drainInferredImplConstraints])
     * folds them into the frozen context's `AbilityImplInfo.operationConstraints`
     * before the final use-site solve. An impl op is just a function: its body's
     * residual ability obligations on the impl's type params become the impl's
     * preconditions, exactly as a top-level fn records `with Ord 'a` in its sig.
     */
    private val inferredImplConstraints = mutableMapOf<Hash, MutableList<ParsedFnAbilityConstraint>>()

    fun drainInferredImplConstraints(): Map<Hash, List<ParsedFnAbilityConstraint>> {
        val snapshot = inferredImplConstraints.mapValues { it.value.toList() }
        inferredImplConstraints.clear()
        return snapshot
    }

    fun validateAbilityImplementation(impl: ParsedImplAbilityDecl, context: TypingContext) {
        if (impl.isInherent) return

        val abilityRef = (impl.target as ParsedImplTarget.Named).ability
        val abilityName = abilityRef.localName
        val abilityQualifier = abilityRef.qualifier
        val qualifiedAbilityName = abilityQualifier?.let { "${it.segments.joinToString("::")}::$abilityName" } ?: abilityName
        val abilityDeclaration = registry.abilityDeclarations[abilityName]
            ?: registry.abilityDeclarations[qualifiedAbilityName]
            ?: throw TypeCheckError(
                "Ability '$qualifiedAbilityName' is not defined or not imported. " +
                "Did you forget to import it with 'use'?",
                impl.loc,
                code = ErrorCode.V0401_ABILITY_NOT_DEFINED,
            )

        val allTypeArgs: List<ParsedTypeAnnotation> = run {
            val concreteArgs = impl.abilityTypeArgs.filter {
                it !is ParsedNamedType || !it.name.startsWith("'")
            }
            concreteArgs + impl.targetTypes
        }

        val paramSubstitution = abilityDeclaration.typeParams.zip(allTypeArgs).associate { (param, targetType) ->
            param to convertTypeAnnotation(targetType)
        }

        // Associated-type bindings (`having 'out = Str`) must substitute into the op
        // signatures too — otherwise a declared return type of `'out` stays an
        // unconstrained var and the body's actual type unifies against it freely
        // (HOLE B: `produce = { it } -> it` returning Int under `having 'out = Str`
        // type-checked, then `(5).produce{}` typed Str yielded a Long →
        // ClassCastException). The effect-impl path already does this; mirror it.
        // Keys are stored under both the `'`-prefixed and bare name because
        // substituteTypeParams matches type vars by name.
        val associatedTypeSubstitution = impl.associatedTypeBindings.mapKeys { (key, _) ->
            if (key.startsWith("'")) key else "'$key"
        }.mapValues { (_, typeAnnotation) ->
            convertTypeAnnotation(typeAnnotation)
        }
        val typeParamSubstitution = paramSubstitution +
            associatedTypeSubstitution +
            associatedTypeSubstitution.mapKeys { (k, _) -> k.removePrefix("'") }

        // asymmetry fix: harvest body-inferred ability preconditions on the impl's
        // own type params. Only for a generic impl (has forallVars) with a content
        // hash to key the accumulation by.
        val implForallVars = impl.forallVars.toSet()
        val inferredSink: MutableList<ParsedFnAbilityConstraint>? =
            if (implForallVars.isNotEmpty() && impl.contentHash != null)
                inferredImplConstraints.getOrPut(impl.contentHash!!) { mutableListOf() }
            else null

        for (implOp in impl.operations) {
            val declOp = abilityDeclaration.operations.find { it.name == implOp.name }
                ?: continue

            // #91: the ability op's declared types come from the ABILITY's
            // module. A named type there (e.g. `config: Config`) may not be
            // imported by name in THIS impl module, so the impl converter can't
            // stamp its content hash — leaving a bare TypeApplication that a
            // later HasField constraint (`config.name`) can't resolve without
            // the import. Stamp hashes from the global type set so the type
            // resolves by hash, keeping it closed/nominal without forcing an
            // import the impl never textually needs.
            val expectedParamType = stampTypeHashes(substituteTypeParams(convertTypeAnnotation(declOp.paramType), typeParamSubstitution))
            val expectedReturnType = stampTypeHashes(substituteTypeParams(convertTypeAnnotation(declOp.returnType), typeParamSubstitution))

            if (implOp.implementation is ParsedLambda) {
                validateImplOperationBody(
                    lambda = implOp.implementation as ParsedLambda,
                    opName = implOp.name,
                    opLoc = implOp.loc,
                    expectedParamType = expectedParamType,
                    expectedReturnType = expectedReturnType,
                    baseContext = context,
                    implLabel = "ability '$abilityName'",
                    validationReason = "Ability body validation",
                    implForallVars = implForallVars,
                    inferredSink = inferredSink,
                    onSolveFailure = { solveResult ->
                        val allFieldAccessErrors = solveResult.errors.all {
                            it.message.contains("Cannot access field") && it.message.contains("non-record type")
                        }
                        if (!allFieldAccessErrors) {
                            throw TypeCheckError(
                                "Type error in ability '${abilityName}' operation '${implOp.name}' implementation: ${solveResult.errors.firstOrNull()?.message ?: "Unknown error"}",
                                implOp.loc,
                                code = ErrorCode.T0201_TYPE_MISMATCH,
                            )
                        }
                        false
                    },
                )
            }
        }
    }

    /**
     * Find, for each of the impl's declared type params, the solver [TypeVar] that
     * represents it inside the op's expected param type (e.g. the `'a` in
     * `{ it: Wrap 'a, r: Wrap 'a }`). Keyed by the param's surface name.
     */
    private fun collectParamVarsByName(
        paramType: MezzeType,
        forallVars: Set<String>
    ): Map<String, TypeVar> {
        val out = mutableMapOf<String, TypeVar>()
        fun walk(t: MezzeType) {
            if (t is MezzeType.VarType) {
                val n = t.variable.name.removePrefix("'")
                if (forallVars.any { it.removePrefix("'") == n } && n !in out) out[n] = t.variable
            }
            t.transformChildren { walk(it); it }
        }
        walk(paramType)
        return out
    }

    private fun validateImplOperationBody(
        lambda: ParsedLambda,
        opName: String,
        opLoc: Loc,
        expectedParamType: MezzeType,
        expectedReturnType: MezzeType,
        baseContext: TypingContext,
        implLabel: String,
        validationReason: String,
        recordLiteralBindings: Set<String> = emptySet(),
        prepareContext: ((TypingContext) -> TypingContext)? = null,
        onBodyResult: ((ConstraintResult, TypingContext) -> Unit)? = null,
        onUndefinedVars: ((ConstraintResult) -> Boolean)? = null,
        onSolveFailure: ((ConstraintSolver.SolveResult.Failure) -> Boolean)? = null,
        // asymmetry fix: the impl's declared type params (tick-prefixed). When set,
        // ability obligations the body places on one of these params are harvested
        // into `inferredSink` as `with <Ability> 'param` — an impl op is just a
        // function, so its body's residual constraints become the impl's
        // preconditions (like a top-level fn records `with Ord 'a` in its sig).
        implForallVars: Set<String> = emptySet(),
        inferredSink: MutableList<ParsedFnAbilityConstraint>? = null,
    ) {
        val paramNames = when (val params = lambda.params) {
            is ParsedLambdaParams.Untyped -> params.names
            is ParsedLambdaParams.Typed -> params.fields.map { it.name }
        }

        val paramTypes = if (expectedParamType is MezzeType.RecordType) {
            paramNames.associateWith { name ->
                expectedParamType.fields[name] ?: MezzeType.VarType(TypeVar.fresh())
            }
        } else {
            paramNames.associateWith { expectedParamType }
        }

        var bodyContext = baseContext
        for ((name, type) in paramTypes) {
            bodyContext = bodyContext.extendMono(name, type)
        }

        if (prepareContext != null) {
            bodyContext = prepareContext(bodyContext)
        }

        val constraintContext = ConstraintContext(
            bodyContext,
            expectedType = null,
            performerToEffect = performerToEffect,
            performerAddedEffects = performerAddedEffects,
            abilityNames = abilityNames,
            effectNames = effectNames,
            effectDeclarations = registry.effectDeclarations,
            abilityDeclarations = registry.abilityDeclarations,
            typeDeclarations = registry.typeDeclarations,
            recordLiteralBindings = recordLiteralBindings
        )

        val bodyResult = expressionGenerator.generate(lambda.body, constraintContext)

        onBodyResult?.invoke(bodyResult, bodyContext)

        if (onUndefinedVars != null) {
            if (onUndefinedVars(bodyResult)) return
        } else {
            val realUndefined = bodyResult.undefinedVariables.filter { !it.name.startsWith("__native_") }
            if (realUndefined.isNotEmpty()) {
                val undefinedVar = realUndefined.first()
                throw TypeCheckError(
                    "Type error in $implLabel operation '$opName' implementation: " +
                    "Undefined variable '${undefinedVar.name}'",
                    undefinedVar.loc,
                    code = ErrorCode.V0702_UNDEFINED_VARIABLE,
                )
            }
        }

        // deferExistingAbilityImpls: an operator body (`+`, `<`, …) types its
        // result as a fresh associated-type `'out` var, resolved via an
        // AssociatedTypeResolution against the operand's ability impl. Feeding the
        // body's ability + associated-type constraints into THIS isolated solve is
        // what pins `'out` to a concrete type before the return-type check —
        // otherwise `'out` stays a free var and unifies with the declared concrete
        // return vacuously, silently accepting a lying body (the soundness hole:
        // `impl Eq for Foo where { eq = {it,r} -> it.v + r.v }` — body Int, decl
        // Bool). The top-level binding path (collectGlobalConstraints) already folds
        // these in for the same reason; an impl op is just a function.
        //
        // The flag makes a merely-not-import-visible ability obligation defer to the
        // top-level solve rather than reject here (e.g. `(it.compare {r}) < 0` over
        // Int when the Int impl exists but isn't `use`d at this site). A
        // genuinely-absent impl (`NumAdd Str`) still fails, leaving `'out`
        // unresolved so the return check rejects — which is exactly the soundness
        // guarantee we want.
        val solver = ConstraintSolver(bodyContext, registry, deferExistingAbilityImpls = true)
        val solveResult = solver.solve(
            bodyResult.constraints + bodyResult.abilityConstraints + bodyResult.associatedTypeConstraints
        )

        when (solveResult) {
            is ConstraintSolver.SolveResult.Failure -> {
                if (onSolveFailure != null) {
                    if (onSolveFailure(solveResult)) return
                } else {
                    throw TypeCheckError(
                        "Type error in $implLabel operation '$opName' implementation: ${solveResult.errors.firstOrNull()?.message ?: "Unknown error"}",
                        opLoc,
                        code = ErrorCode.T0201_TYPE_MISMATCH,
                    )
                }
            }
            is ConstraintSolver.SolveResult.Success -> {
                val substitution = solveResult.substitution
                val rawBodyType = substitution.apply(bodyResult.type)
                val actualBodyType = unwrapSuspended(rawBodyType)

                val unificationSolver = ConstraintSolver(bodyContext, registry)
                val unificationResult = unificationSolver.solve(
                    listOf(Constraint.Equality(actualBodyType, expectedReturnType, validationReason, loc = opLoc))
                )

                if (unificationResult is ConstraintSolver.SolveResult.Failure) {
                    val actualTypeStr = prettyPrintType(actualBodyType)
                    val expectedTypeStr = prettyPrintType(expectedReturnType)
                    throw TypeCheckError(
                        "Operation '$opName' in $implLabel returns wrong type: " +
                        "expected $expectedTypeStr, but body returns $actualTypeStr.",
                        opLoc,
                        code = ErrorCode.T0210_ANNOTATION_VS_BODY_MISMATCH,
                        hints = listOf("either change the annotation to match the body, or change the body to produce the annotated type"),
                    )
                }

                // Harvest body-inferred ability preconditions on the impl's type
                // params (the asymmetry fix). The body's `it.v < r.v` produces an
                // `Ord <var>` obligation; if <var> resolves to one of the impl's
                // declared type params, record `with Ord 'param` so the impl is
                // only applicable when that param satisfies the ability.
                if (inferredSink != null && implForallVars.isNotEmpty()) {
                    // Map each of the impl's declared type params to its
                    // solver var AS IT APPEARS in the op's param type, then to that
                    // var's post-solve REPRESENTATIVE. A residual `Ord <var>` counts
                    // as `with Ord 'param` when <var>'s representative equals the
                    // param's representative — matching by unification-equivalence,
                    // not by name (the body's field-access introduces a fresh var
                    // that unifies with `'a` but keeps a solver-internal name).
                    val paramVarsByName = collectParamVarsByName(expectedParamType, implForallVars)
                    val repToParamName = paramVarsByName.mapNotNull { (paramName, v) ->
                        val rep = substitution.apply(MezzeType.VarType(v))
                        if (rep is MezzeType.VarType) rep.variable.id to paramName else null
                    }.toMap()
                    for (ab in bodyResult.abilityConstraints) {
                        val rep = substitution.apply(MezzeType.VarType(ab.typeVar))
                        val paramName = (rep as? MezzeType.VarType)?.let { repToParamName[it.variable.id] }
                            ?: continue
                        inferredSink.add(ParsedFnAbilityConstraint(
                            abilityName = ab.abilityName,
                            typeArgs = listOf(ParsedNamedType("'${paramName.removePrefix("'")}", ab.loc ?: opLoc)),
                            loc = ab.loc ?: opLoc
                        ))
                    }
                }
            }
        }
    }

    fun validateEffectImplementation(impl: ParsedPerformerDecl, context: TypingContext) {
        val effectRef = impl.effect
        val effectQualifier = effectRef.qualifier
        val effectLookupKey = if (effectQualifier != null) {
            "${effectQualifier.segments.last()}::${effectRef.localName}"
        } else {
            effectRef.localName
        }
        val effectDeclaration = registry.effectDeclarations[effectLookupKey]
            ?: registry.effectDeclarations[effectRef.localName]
            ?: throw TypeCheckError(
                "Effect '${effectLookupKey}' is not defined or not imported. " +
                "Did you forget to import it with 'use'?",
                impl.loc,
                code = ErrorCode.V0505_EFFECT_NOT_DEFINED,
                hints = listOf(
                    "declare `effect ${effectRef.localName} { ... }` or import it with `use module::${effectRef.localName}`"
                ),
            )

        val associatedTypeSubstitution = impl.associatedTypeBindings.mapKeys { (key, _) ->
            if (key.startsWith("'")) key else "'$key"
        }.mapValues { (_, typeAnnotation) ->
            convertTypeAnnotation(typeAnnotation)
        }

        val fullSubstitution = associatedTypeSubstitution + associatedTypeSubstitution.mapKeys { (k, _) ->
            k.removePrefix("'")
        }

        var baseContext = context
        for (param in impl.params) {
            val paramType = convertTypeAnnotation(param.type)
            baseContext = baseContext.extendMono(param.name, paramType)
        }

        var initializeScope: ParsedPerformerOp? = null
        var finalizeScope: ParsedPerformerOp? = null
        for (operation in impl.operations) {
            when (operation.name) {
                ScopedEffectConstants.INITIALIZE_SCOPE -> initializeScope = operation
                ScopedEffectConstants.FINALIZE_SCOPE -> finalizeScope = operation
            }
        }
        val isScopedEffect = initializeScope != null
        val hasFinalizer = finalizeScope != null

        if (isScopedEffect && !hasFinalizer) {
            throw TypeCheckError(
                "initialize_scope requires finalize_scope for resource safety. " +
                "Add 'finalize_scope = {} -> {}' to clean up resources when the perform block exits.",
                initializeScope!!.loc,
                code = ErrorCode.V0506_SCOPED_EFFECT_INIT_FINALIZE_REQUIRED,
                hints = listOf("scoped effects need both `initialize_scope` and `finalize_scope` operations"),
            )
        }
        if (hasFinalizer && !isScopedEffect) {
            throw TypeCheckError(
                "finalize_scope requires initialize_scope. " +
                "Add initialize_scope to create the scope that finalize_scope cleans up.",
                finalizeScope!!.loc,
                code = ErrorCode.V0506_SCOPED_EFFECT_INIT_FINALIZE_REQUIRED,
                hints = listOf("scoped effects need both `initialize_scope` and `finalize_scope` operations"),
            )
        }

        if (isScopedEffect) {
            initializeScope!!.returnType?.let { retType ->
                if (retType !is ParsedRecordType) {
                    throw TypeCheckError(
                        "initialize_scope must return a record type like { field1 = value1, ... }. " +
                        "The returned record becomes the 'scope' variable available in operations.",
                        initializeScope.loc,
                        code = ErrorCode.T0201_TYPE_MISMATCH,
                    )
                }
            }
            finalizeScope?.returnType?.let { retType ->
                if (retType !is ParsedRecordType || (retType as ParsedRecordType).entries.isNotEmpty()) {
                    throw TypeCheckError(
                        "finalize_scope must return {} (unit). " +
                        "It's for cleanup side effects only, not for returning values.",
                        finalizeScope.loc,
                        code = ErrorCode.T0201_TYPE_MISMATCH,
                    )
                }
            }
        }

        var inferredScopeType: MezzeType? = null

        initializeScope?.let { initOp ->
            if (initOp.implementation is ParsedLambda) {
                val lambda = initOp.implementation as ParsedLambda

                val initContext = baseContext

                val constraintContext = ConstraintContext(
                    initContext,
                    expectedType = null,
                    performerToEffect = performerToEffect,
                    performerAddedEffects = performerAddedEffects,
                    abilityNames = abilityNames,
                    effectNames = effectNames,
                    effectDeclarations = registry.effectDeclarations,
                    abilityDeclarations = registry.abilityDeclarations,
                    typeDeclarations = registry.typeDeclarations
                )

                val bodyResult = expressionGenerator.generate(lambda.body, constraintContext)

                val scopeUndefined = bodyResult.undefinedVariables.find { it.name == "scope" }
                if (scopeUndefined != null) {
                    throw TypeCheckError(
                        "The 'scope' variable is not available in initialize_scope. " +
                        "The scope is being created here and will only be available in operations and finalize_scope.",
                        scopeUndefined.loc,
                        code = ErrorCode.V0702_UNDEFINED_VARIABLE,
                    )
                }

                inferredScopeType = bodyResult.type

                val initEffects = bodyResult.effects - effectRef.localName
                if (initEffects.isNotEmpty()) {
                    val existingEffects = performerAddedEffects[impl.performerName] ?: emptySet()
                    performerAddedEffects[impl.performerName] = existingEffects + initEffects
                }
            }
        }

        val scopeType: MezzeType? = inferredScopeType ?: if (isScopedEffect) {
            initializeScope?.let { initOp ->
                val returnType = initOp.returnType
                if (returnType is ParsedRecordType) {
                    convertTypeAnnotation(returnType)
                } else {
                    null
                }
            }
        } else {
            null
        }

        val contextWithScope = if (scopeType != null) {
            baseContext.extendMono("scope", scopeType)
        } else {
            baseContext
        }

        finalizeScope?.let { finalOp ->
            if (finalOp.implementation is ParsedLambda) {
                val lambda = finalOp.implementation as ParsedLambda

                val finalizeContext = contextWithScope

                val scopeRecordBindings = if (scopeType != null) setOf("scope") else emptySet()

                val constraintContext = ConstraintContext(
                    finalizeContext,
                    expectedType = null,
                    performerToEffect = performerToEffect,
                    performerAddedEffects = performerAddedEffects,
                    abilityNames = abilityNames,
                    effectNames = effectNames,
                    effectDeclarations = registry.effectDeclarations,
                    abilityDeclarations = registry.abilityDeclarations,
                    typeDeclarations = registry.typeDeclarations,
                    recordLiteralBindings = scopeRecordBindings
                )

                val bodyResult = expressionGenerator.generate(lambda.body, constraintContext)

                val finalizeEffects = bodyResult.effects - effectRef.localName
                if (finalizeEffects.isNotEmpty()) {
                    val existingEffects = performerAddedEffects[impl.performerName] ?: emptySet()
                    performerAddedEffects[impl.performerName] = existingEffects + finalizeEffects
                }
            }
        }

        for (implOp in impl.operations) {
            if (implOp.name == ScopedEffectConstants.INITIALIZE_SCOPE || implOp.name == ScopedEffectConstants.FINALIZE_SCOPE) {
                continue
            }

            val declOp = effectDeclaration.operations.find { it.name == implOp.name }
                ?: continue

            val declParamType = convertTypeAnnotation(declOp.paramType)
            val declReturnType = convertTypeAnnotation(declOp.returnType)

            val expectedParamType = substituteTypeParams(declParamType, fullSubstitution)
            val expectedReturnType = substituteTypeParams(declReturnType, fullSubstitution)

            val implParamTypeAnnotation = implOp.paramType
            val isPlaceholderParamType = implParamTypeAnnotation is ParsedRecordType &&
                implParamTypeAnnotation.entries.isEmpty() && !implParamTypeAnnotation.isOpen

            if (implParamTypeAnnotation != null && !isPlaceholderParamType) {
                val implParamType = convertTypeAnnotation(implParamTypeAnnotation)
                val unificationSolver = ConstraintSolver(baseContext, registry)
                val unificationResult = unificationSolver.solve(
                    listOf(Constraint.Equality(implParamType, expectedParamType, "Effect param type validation", loc = implOp.loc))
                )
                if (unificationResult is ConstraintSolver.SolveResult.Failure) {
                    val implTypeStr = prettyPrintType(implParamType)
                    val expectedTypeStr = prettyPrintType(expectedParamType)
                    throw TypeCheckError(
                        "Effect impl '${impl.performerName}' operation '${implOp.name}' has wrong parameter type: " +
                        "expected $expectedTypeStr (from effect '${effectRef.localName}'), " +
                        "but impl declares $implTypeStr. " +
                        "Parameter names and types must match exactly.",
                        implOp.implementation.loc,
                        code = ErrorCode.T0201_TYPE_MISMATCH,
                    )
                }
            }

            val implReturnTypeAnnotation = implOp.returnType
            val isPlaceholderReturnType = implReturnTypeAnnotation is ParsedRecordType &&
                implReturnTypeAnnotation.entries.isEmpty() && !implReturnTypeAnnotation.isOpen

            if (implReturnTypeAnnotation != null && !isPlaceholderReturnType) {
                val implReturnType = convertTypeAnnotation(implReturnTypeAnnotation)
                val unificationSolver = ConstraintSolver(baseContext, registry)
                val unificationResult = unificationSolver.solve(
                    listOf(Constraint.Equality(implReturnType, expectedReturnType, "Effect return type validation", loc = implOp.loc))
                )
                if (unificationResult is ConstraintSolver.SolveResult.Failure) {
                    val implTypeStr = prettyPrintType(implReturnType)
                    val expectedTypeStr = prettyPrintType(expectedReturnType)
                    throw TypeCheckError(
                        "Effect impl '${impl.performerName}' operation '${implOp.name}' has wrong return type: " +
                        "expected $expectedTypeStr (from effect '${effectRef.localName}'), " +
                        "but impl declares $implTypeStr.",
                        implOp.implementation.loc,
                        code = ErrorCode.T0210_ANNOTATION_VS_BODY_MISMATCH,
                        hints = listOf("either change the annotation to match the body, or change the body to produce the annotated type"),
                    )
                }
            }

            val implEffects = implOp.effects
            if (implEffects != null && effectRef.localName in implEffects.effects) {
                throw TypeCheckError(
                    "Effect impl '${impl.performerName}' operation '${implOp.name}' cannot require " +
                    "the same effect '${effectRef.localName}' it is implementing. " +
                    "The impl should handle the effect, not propagate it. " +
                    "Either remove '${effectRef.localName}' from the effect annotation or transform it to a different effect.",
                    implOp.implementation.loc,
                    code = ErrorCode.V0504_HERMETIC_EFFECT_CONFLICT,
                )
            }

            if (implOp.implementation is ParsedLambda) {
                val operationBaseContext = if (implOp.name == ScopedEffectConstants.INITIALIZE_SCOPE) {
                    baseContext
                } else {
                    contextWithScope
                }

                val scopeRecordBindings = if (scopeType != null && implOp.name != ScopedEffectConstants.INITIALIZE_SCOPE) {
                    setOf("scope")
                } else {
                    emptySet()
                }

                validateImplOperationBody(
                    lambda = implOp.implementation as ParsedLambda,
                    opName = implOp.name,
                    opLoc = implOp.loc,
                    expectedParamType = expectedParamType,
                    expectedReturnType = expectedReturnType,
                    baseContext = operationBaseContext,
                    implLabel = "impl '${impl.performerName}'",
                    validationReason = "Effect body validation",
                    recordLiteralBindings = scopeRecordBindings,
                    prepareContext = { ctx ->
                        EffectHandlerBindings
                            .extendWithAbortAndResume(ctx, expectedReturnType)
                    },
                    onBodyResult = { bodyResult, _ ->
                        val bodyEffects = bodyResult.effects - effectRef.localName
                        if (bodyEffects.isNotEmpty()) {
                            val existingEffects = performerAddedEffects[impl.performerName] ?: emptySet()
                            performerAddedEffects[impl.performerName] = existingEffects + bodyEffects
                        }
                    },
                    onUndefinedVars = { bodyResult ->
                        if (bodyResult.undefinedVariables.isNotEmpty()) {
                            val scopeUndefined = bodyResult.undefinedVariables.find { it.name == "scope" }
                            if (scopeUndefined != null) {
                                val errorMessage = if (implOp.name == ScopedEffectConstants.INITIALIZE_SCOPE) {
                                    "The 'scope' variable is not available in initialize_scope. " +
                                    "The scope is being created here and will only be available in operations and finalize_scope."
                                } else if (!isScopedEffect) {
                                    "The 'scope' variable is not available because this impl does not define initialize_scope. " +
                                    "Add 'initialize_scope = {} -> { ... }' to create a scope."
                                } else {
                                    "Undefined variable 'scope' in operation '${implOp.name}'."
                                }
                                throw TypeCheckError(
                                    errorMessage,
                                    scopeUndefined.loc,
                                    code = ErrorCode.V0702_UNDEFINED_VARIABLE,
                                )
                            }
                            true
                        } else {
                            false
                        }
                    },
                    onSolveFailure = { solveResult ->
                        val firstError = solveResult.errors.firstOrNull()
                        if (firstError != null) {
                            val constraint = firstError.constraint

                            if (constraint is Constraint.Equality) {
                                val left = constraint.left
                                val right = constraint.right
                                val isCallingNonFunction =
                                    (left is MezzeType.FunctionType && !isFunctionLike(right)) ||
                                    (right is MezzeType.FunctionType && !isFunctionLike(left))

                                if (isCallingNonFunction) {
                                    val nonFunctionType = if (left is MezzeType.FunctionType) right else left
                                    throw TypeCheckError(
                                        "Cannot call non-function type as function in impl '${impl.performerName}' " +
                                        "operation '${implOp.name}': type '${prettyPrintType(nonFunctionType)}' " +
                                        "is not a function",
                                        firstError.constraint.loc ?: implOp.implementation.loc,
                                        code = ErrorCode.T0302_CANNOT_APPLY_NON_FUNCTION,
                                    )
                                }
                            }

                            val msg = firstError.message
                            val hasTypeVar = Regex("'[a-z]\\b").containsMatchIn(msg)
                            val hasEffects = msg.contains("effectful") || msg.contains("<") && msg.contains(">")
                            val hasCollections = msg.contains("List") || msg.contains("Seq") ||
                                msg.contains("[") || msg.contains("Set") || msg.contains("Map")
                            val isClearTypeMismatch = msg.contains("Type mismatch") &&
                                !hasTypeVar && !hasEffects && !hasCollections

                            if (isClearTypeMismatch) {
                                throw TypeCheckError(
                                    "Type error in impl '${impl.performerName}' operation '${implOp.name}': ${firstError.message}",
                                    firstError.constraint.loc ?: implOp.implementation.loc,
                                    code = ErrorCode.T0201_TYPE_MISMATCH,
                                )
                            }
                        }
                        true
                    },
                )
            }
        }
    }

    /**
     * #91: stamp a content hash onto every hash-less [MezzeType.TypeApplication]
     * whose constructor name resolves in the global type set. Applied only to
     * ability-op declared types flowing into an impl body, so a cross-module
     * named type (e.g. `Config`) resolves by hash without being imported by
     * name. A TypeApplication that already carries a hash, or whose name is a
     * type variable / not a known type, is left untouched.
     */
    private fun stampTypeHashes(type: MezzeType): MezzeType {
        val stamped = if (type is MezzeType.TypeApplication && type.typeHash == null) {
            registry.typeHashFor(type.constructor)?.let { type.copy(typeHash = it) } ?: type
        } else type
        return stamped.transformChildren { stampTypeHashes(it) }
    }

    private fun substituteTypeParams(type: MezzeType, substitution: Map<String, MezzeType>): MezzeType =
        when (type) {
            // A type var is replaced by its substitute (by name); everything else
            // just recurses into its children. transformChildren rebuilds each
            // node via copy(), preserving all non-child fields (typeName,
            // typeHash, rowVar, effects). TypeApplication keeps its own case so
            // WellKnownTypeApp.of still fills in a well-known hash when absent.
            is MezzeType.VarType -> substitution[type.variable.name] ?: type
            is MezzeType.TypeApplication -> WellKnownTypeApp.of(
                type.constructor, type.args.map { substituteTypeParams(it, substitution) }, type.typeHash)
            else -> type.transformChildren { substituteTypeParams(it, substitution) }
        }

    private fun isFunctionLike(type: MezzeType): Boolean {
        return when (type) {
            is MezzeType.FunctionType -> true
            is MezzeType.VarType -> true
            else -> false
        }
    }
}
