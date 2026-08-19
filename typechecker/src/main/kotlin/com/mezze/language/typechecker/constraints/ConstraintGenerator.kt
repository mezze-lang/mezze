package com.mezze.language.typechecker.constraints

import com.mezze.language.ast.*
import com.mezze.language.parsedast.*
import com.mezze.language.typechecker.abilities.BuiltInAbilities
import com.mezze.language.typechecker.context.ContextBuilder
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.context.TypeScheme
import com.mezze.language.typechecker.debug.DebugLogger
import com.mezze.language.typechecker.debug.DebugLogger.Category
import com.mezze.language.ast.Keywords
import com.mezze.language.ast.solving.Substitution
import com.mezze.language.ast.solving.collectFreeTypeVars
import com.mezze.language.parsedast.diagnostics.ErrorCode
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.typechecker.WellKnownHashConstants
import com.mezze.language.typechecker.context.DeclarationRegistry
import com.mezze.language.typechecker.solving.ConstraintSolver
import com.mezze.language.typechecker.util.TypeAnnotationConverter
import com.mezze.language.typechecker.util.WellKnownTypeApp
import com.mezze.language.typedast.Hash


data class ProgramConstraintResult(
    val constraintResults: Map<String, ConstraintResult>,
    val perBindingMustBeOpenRowVars: Set<RowVar>,
    val perBindingSolveErrors: List<ConstraintSolver.ConstraintError> = emptyList(),
    val perBindingSubstitution: Substitution = Substitution.empty,
    val collectedTypeCheckErrors: List<TypeCheckError> = emptyList()
) {
}

class ConstraintGenerator(
    val expressionGenerator: ExpressionConstraintGenerator,
    private val patternGenerator: PatternConstraintGenerator,
    private val registry: DeclarationRegistry = DeclarationRegistry.EMPTY,
    private val performerToEffect: Map<String, String> = emptyMap(),
    initialPerformerAddedEffects: Map<String, Set<String>> = emptyMap(),
    private val abilityNames: Set<String> = emptySet(),
    private val effectNames: Set<String> = emptySet(),
) {
    private val performerAddedEffects: MutableMap<String, Set<String>> = initialPerformerAddedEffects.toMutableMap()
    private val typeVarCache = mutableMapOf<String, TypeVar>()
    private val sharedRowVar = TypeAnnotationConverter.SharedRowVar()

    private val constraintConversionConfig = TypeAnnotationConverter.ConversionConfig(
        resolveTypeHash = { registry.typeDeclarations[it]?.contentHash },
        resolveAbilityHash = { registry.abilityDeclarations[it]?.contentHash },
        validateEffects = { effectRow ->
            TypeAnnotationConverter.validateUnknownEffects(
                effectRow, registry.effectDeclarations.keys
            )
        },
        resolveEffectHashes = { names ->
            names.mapNotNull { name ->
                registry.effectDeclarations[name]?.contentHash?.let { name to it }
            }.toMap()
        },
        resolveCollectionTypes = false,
        honorOpenRowVars = true,
        inlineVariantNaming = true,
        convertEffectAnnotations = true,
    )

    private val implValidator = ImplValidator(
        expressionGenerator, registry, performerToEffect, performerAddedEffects,
        abilityNames, effectNames, ::convertTypeAnnotation
    )
    private val annotationValidator = AnnotationValidator(registry)

    fun generateProgram(
        module: ParsedModule,
        context: TypingContext
    ): ProgramConstraintResult {
        DebugLogger.debugSection(Category.ConstraintGenerator, "Abilities and Effects") {
            listOf(
                "abilityNames = $abilityNames",
                "effectNames = $effectNames"
            )
        }

        val results = mutableMapOf<String, ConstraintResult>()
        var currentContext = context

        val recordLiteralBindings = mutableSetOf<String>()

        val perBindingSolveErrors = mutableListOf<ConstraintSolver.ConstraintError>()
        val collectedTypeCheckErrors = mutableListOf<TypeCheckError>()

        var perBindingSubstitution = Substitution.empty

        val solver = ConstraintSolver(context, registry)

        val letBindings = module.decls.filterIsInstance<ParsedLetDecl>()
        val placeholderNames = mutableSetOf<String>()
        val annotationTypes = mutableMapOf<ParsedLetDecl, MezzeType>()
        for (binding in letBindings) {
            binding.typeAnnotation?.let { annotation ->
                if (annotation is ParsedFunctionType) {
                    for (constraint in annotation.abilityConstraints) {
                        val name = constraint.abilityName
                        val isKnown = registry.abilityDeclarations.containsKey(name) ||
                            name in BuiltInAbilities.getAllAbilityNames()
                        if (!isKnown) {
                            throw TypeCheckError(
                                "Ability '$name' referenced in 'with' constraint is not defined or not imported. " +
                                "Did you forget to import it with 'use'?",
                                constraint.loc,
                                code = ErrorCode.V0401_ABILITY_NOT_DEFINED,
                            )
                        }
                    }
                }
                val convertedType = convertTypeAnnotation(annotation)
                annotationTypes[binding] = convertedType
            }

            if (binding.isSimpleBinding && binding.value is ParsedLambda) {
                val placeholderType = annotationTypes[binding] ?: MezzeType.VarType(TypeVar.fresh())

                currentContext = currentContext.extendMono(binding.variableName, placeholderType)
                placeholderNames.add(binding.variableName)
            }
        }

        // Process decls so a callee is inferred before its caller whenever
        // that ordering is unambiguous (acyclic). Without this, a call to a
        // source-order-LATER top-level `let f = { ... }` sees only f's fresh
        // monomorphic placeholder var (registered above), so the call
        // constraint `FunctionType({args}) ~ 'placeholder` binds the var whole
        // and never decomposes args against params — a polymorphic return then
        // never pins to the concrete arg type and the caller's binding leaks
        // `'a` (#126). Inferring f first registers its real Polymorphic scheme,
        // so the call instantiates the true function type (the working
        // "declared-before-use" path). Cycles (mutual recursion) keep source
        // order — placeholder behaviour, no worse than before.
        val orderedDecls = dependencyOrderedDecls(module.decls)
        for (item in orderedDecls) {
            when (item) {
                is ParsedLetDecl -> try {
                    val preConvertedAnnotationType = annotationTypes[item]
                    val result = generateLetBinding(item, currentContext, recordLiteralBindings, preConvertedAnnotationType)

                    if (item.pattern is ParsedVariablePattern && item.value is ParsedRecordLit) {
                        recordLiteralBindings.add((item.pattern as ParsedVariablePattern).name)
                    }

                    val constraintContext = ConstraintContext(currentContext, performerToEffect = performerToEffect, performerAddedEffects = performerAddedEffects, abilityNames = abilityNames, effectNames = effectNames, effectDeclarations = registry.effectDeclarations, abilityDeclarations = registry.abilityDeclarations, typeDeclarations = registry.typeDeclarations)
                    val patternResult = patternGenerator.generate(
                        item.pattern,
                        result.type,
                        constraintContext,
                        item.value.loc
                    )

                    val duplicateBindings = patternResult.bindings.keys
                        .filter { name -> currentContext.hasLocal(name) && name !in placeholderNames }
                        .map { name ->
                            ConstraintResult.DuplicateBinding(
                                name = name,
                                loc = patternResult.bindingLocations[name]
                            )
                        }

                    val resultWithDuplicates = result.copy(
                        duplicateBindings = duplicateBindings
                    )


                    results[getBindingName(item)] = resultWithDuplicates

                    val allBindingConstraints = result.constraints + result.abilityConstraints + result.associatedTypeConstraints + patternResult.constraints
                    val solveResult = solver.solve(allBindingConstraints)
                    when (solveResult) {
                        is ConstraintSolver.SolveResult.Failure -> {
                            perBindingSolveErrors.addAll(solveResult.errors)

                            results[getBindingName(item)] = resultWithDuplicates

                            val bindingHash: Hash? = null
                            currentContext = currentContext.extendAll(
                                patternResult.bindings.map { (name, type) ->
                                    BindingKey.unqualified(name) to TypingContext.Binding.Monomorphic(type, contentHash = bindingHash)
                                }.toMap()
                            )
                            continue
                        }
                        is ConstraintSolver.SolveResult.Success -> {
                            val substitution = solveResult.substitution

                            val authoritativeType = if (item.pattern is ParsedVariablePattern && preConvertedAnnotationType != null) {
                                if (preConvertedAnnotationType is MezzeType.TypeApplication) {
                                    val isUserDefinedAlias = registry.typeDeclarations.containsKey(preConvertedAnnotationType.constructor)

                                    if (isUserDefinedAlias) {
                                        WellKnownTypeApp.of(
                                            constructor = preConvertedAnnotationType.constructor,
                                            args = preConvertedAnnotationType.args.map { substitution.apply(it) },
                                            typeHash = preConvertedAnnotationType.typeHash
                                        )
                                    } else {
                                        substitution.apply(preConvertedAnnotationType)
                                    }
                                } else {
                                    substitution.apply(preConvertedAnnotationType)
                                }
                            } else {
                                substitution.apply(result.type)
                            }

                            if (item.pattern is ParsedVariablePattern && preConvertedAnnotationType != null) {
                                validateLetAnnotation(item, preConvertedAnnotationType, result, substitution, solveResult.resolvedAssociatedBindings, context)
                            }

                            val solvedBindings = if (item.pattern is ParsedVariablePattern && authoritativeType != substitution.apply(result.type)) {
                                mapOf(item.variableName to authoritativeType)
                            } else {
                                patternResult.bindings.mapValues { (_, type) ->
                                    substitution.apply(type)
                                }
                            }

                            val solvedBindingsWithAbilities = solvedBindings.mapValues { (name, solvedType) ->
                                addAbilityConstraintsToFunctionType(solvedType, result.abilityConstraints, substitution, solveResult.resolvedAssociatedBindings)
                            }

                            val hasEffectsOrPerformed = result.effects.isNotEmpty() || result.effectInstances.isNotEmpty() || result.performed
                            val solvedBindingsWithEffects = if (hasEffectsOrPerformed) {
                                solvedBindingsWithAbilities.mapValues { (_, solvedType) ->
                                    when (solvedType) {
                                        is MezzeType.SuspendType -> {
                                            if (result.performed && !solvedType.effects.performed) {
                                                MezzeType.SuspendType(
                                                    valueType = solvedType.valueType,
                                                    effects = MezzeType.EffectRow(
                                                        effects = solvedType.effects.effects,
                                                        effectInstances = solvedType.effects.effectInstances,
                                                        rowVar = solvedType.effects.rowVar,
                                                        performed = true
                                                    )
                                                )
                                            } else {
                                                solvedType
                                            }
                                        }
                                        is MezzeType.FunctionType -> solvedType
                                        else -> MezzeType.SuspendType(
                                            valueType = solvedType,
                                            effects = MezzeType.EffectRow(
                                                effects = result.effects,
                                                effectInstances = result.effectInstances,
                                                rowVar = null,
                                                performed = result.performed
                                            )
                                        )
                                    }
                                }
                            } else {
                                solvedBindingsWithAbilities
                            }

                            val contextBindings = solvedBindingsWithEffects.map { (name, solvedType) ->
                                val bindingKey = BindingKey.unqualified(name)
                                bindingKey to buildBindingForContext(
                                    solvedType, bindingKey, currentContext, item, result,
                                    substitution, authoritativeType
                                )
                            }.toMap()

                            currentContext = currentContext.extendAll(contextBindings)

                            for ((bindingName, solvedType) in solvedBindingsWithEffects) {
                                if (results.containsKey(bindingName)) {
                                    val existingResult = results[bindingName]!!
                                    results[bindingName] = existingResult.copy(type = solvedType)
                                }
                            }
                        }
                    }
                } catch (e: TypeCheckError) {
                    collectedTypeCheckErrors.add(if (e.loc == null) TypeCheckError(e.message ?: "Type error", item.loc, e.errorType, e.metadata, code = e.code, hints = e.hints, fixHints = e.fixHints) else e)
                    if (item.isSimpleBinding) {
                        currentContext = currentContext.extendMono(item.variableName, MezzeType.VarType(TypeVar.fresh()))
                    }
                }
                is ParsedTypeDecl,
                is ParsedAbilityDecl,
                is ParsedEffectDecl -> {
                }
                is ParsedPerformerDecl -> try {
                    implValidator.validateEffectImplementation(item, currentContext)
                } catch (e: TypeCheckError) {
                    collectedTypeCheckErrors.add(if (e.loc == null) TypeCheckError(e.message ?: "Type error", item.loc, e.errorType, e.metadata, code = e.code, hints = e.hints, fixHints = e.fixHints) else e)
                }
                is ParsedImplAbilityDecl -> try {
                    implValidator.validateAbilityImplementation(item, currentContext)
                    if (item.isInherent) {
                        currentContext = processInherentImpl(item, currentContext, results)
                    }
                } catch (e: TypeCheckError) {
                    collectedTypeCheckErrors.add(if (e.loc == null) TypeCheckError(e.message ?: "Type error", item.loc, e.errorType, e.metadata, code = e.code, hints = e.hints, fixHints = e.fixHints) else e)
                }
                is ParsedTopLevelExpr -> {
                    val constraintContext = ConstraintContext(currentContext, performerToEffect = performerToEffect, performerAddedEffects = performerAddedEffects, abilityNames = abilityNames, effectNames = effectNames, effectDeclarations = registry.effectDeclarations, abilityDeclarations = registry.abilityDeclarations, typeDeclarations = registry.typeDeclarations)
                    val result = expressionGenerator.generate(
                        item.expression,
                        constraintContext
                    )
                    results["_toplevel"] = result

                    val structuralConstraints = result.constraints.filter { constraint ->
                        constraint is Constraint.HasField || constraint is Constraint.HasConstructor
                    }
                    if (structuralConstraints.isNotEmpty()) {
                        val solveResult = solver.solve(structuralConstraints)
                        if (solveResult is ConstraintSolver.SolveResult.Success) {
                            perBindingSubstitution = solveResult.substitution.compose(perBindingSubstitution)
                        }
                    }

                    // A bare trailing expression must still discharge its ABILITY
                    // obligations. The global solve strips HasConstructor/HasField
                    // before its ability check (collectGlobalConstraints), so the var
                    // an `AbilityRequired` rides on is never pinned to the concrete
                    // argument type and the obligation is silently dropped — an
                    // ability-constrained call used as the module result (e.g.
                    // `sorted_set::singleton { x = Red }` where Color has no `Ord`)
                    // type-checked and then crashed at runtime, though the identical
                    // call was correctly rejected once `let`-bound. Re-solve the FULL
                    // constraint set here (so the arg equalities + HasConstructor pin
                    // the ability var to the concrete type, e.g. Red → Color) but
                    // report ONLY the ability failures — equality / method-resolution
                    // mismatches must stay deferred to the global solver, which owns
                    // their inference ordering, so we must not surface those here.
                    val abilityObligations = result.abilityConstraints + result.associatedTypeConstraints
                    if (abilityObligations.isNotEmpty()) {
                        val solveResult = solver.solve(result.constraints + abilityObligations)
                        if (solveResult is ConstraintSolver.SolveResult.Failure) {
                            // Keep only genuine ability failures — both the directly
                            // solved obligation and the deferred validateAbilityConstraints
                            // re-check stamp ErrorCode.V0402_ABILITY_NOT_SATISFIED, so a
                            // single typed code discriminates them (no magic-string or
                            // constraint-shape matching). Equality / method-resolution
                            // failures are dropped here; the global solver owns those.
                            perBindingSolveErrors.addAll(
                                solveResult.errors.filter { it.code == ErrorCode.V0402_ABILITY_NOT_SATISFIED }
                            )
                        }
                    }
                }
                is ParsedUseDecl,
                is ParsedPubUseDecl -> {
                }
            }
        }

        return ProgramConstraintResult(
            constraintResults = results,
            perBindingMustBeOpenRowVars = solver.getMustBeOpenRowVars(),
            perBindingSolveErrors = perBindingSolveErrors,
            perBindingSubstitution = perBindingSubstitution,
            collectedTypeCheckErrors = collectedTypeCheckErrors
        )
    }

    /**
     * For each ability named in the annotation, the set of CONCRETE types its
     * impls declare for each associated type (assocName(bare) → set of types).
     * Used by R2 on a generic receiver: an annotated `having 'x = T` where no
     * impl of that ability declares `'x = T` is a mismatch. Empty when there is
     * no context (e.g. cache paths) — R2 then only fires for concrete receivers.
     */
    private fun implDeclaredHaving(
        context: TypingContext?,
        annotationConstraints: List<ParsedFnAbilityConstraint>,
    ): Map<String, Map<String, Set<MezzeType>>> {
        if (context == null) return emptyMap()
        return annotationConstraints.map { it.abilityName }.distinct().associateWith { abilityName ->
            val byName = mutableMapOf<String, MutableSet<MezzeType>>()
            for (impl in context.getAbilityImpls(abilityName)) {
                for ((rawName, ann) in impl.associatedTypeBindings) {
                    val t = runCatching { annotationValidator.publicAnnToMezze(ann) }.getOrNull() ?: continue
                    if (t is MezzeType.VarType) continue
                    byName.getOrPut(rawName.removePrefix("'")) { mutableSetOf() }.add(t)
                }
            }
            byName.mapValues { it.value.toSet() }
        }.filterValues { it.isNotEmpty() }
    }

    private fun validateLetAnnotation(
        item: ParsedLetDecl,
        preConvertedAnnotationType: MezzeType,
        result: ConstraintResult,
        substitution: Substitution,
        resolvedAssociatedBindings: Map<Pair<String, Int>, Map<String, MezzeType>> = emptyMap(),
        context: TypingContext? = null,
    ) {
        val annotation = item.typeAnnotation
        val varName = (item.pattern as ParsedVariablePattern).name

        val inferredTypeRaw = substitution.apply(result.type)
        val inferredTypeWithAbilities = addAbilityConstraintsToFunctionType(
            inferredTypeRaw, result.abilityConstraints, substitution, resolvedAssociatedBindings
        )

        if (annotation is ParsedFunctionType) {
            val abilityConstrainedVars = result.abilityConstraints.map { it.typeVar }.toSet()
            // Associated-type vars bound via `having 'out = T` in the annotation are
            // EXPECTED to be forced to a concrete type by the impl — that's what the
            // binding means. Exempt them from the "annotation over-generalizes" check;
            // their correctness is enforced by unification (unifyFunctions unifies
            // ability associated-type bindings, mirroring the effect-row path).
            val havingVarNames = (preConvertedAnnotationType as? MezzeType.FunctionType)
                ?.abilityConstraints.orEmpty()
                .flatMap { it.associatedTypeBindings.keys }
                .map { it.removePrefix("'") }
                .toSet()
            val havingVars = com.mezze.language.ast.collectTypeVarsInOrder(preConvertedAnnotationType)
                .filter { it.name.removePrefix("'") in havingVarNames }
                .toSet()
            annotationValidator.validatePolymorphismPreserved(
                preConvertedAnnotationType, substitution, varName,
                item.value.loc, abilityConstrainedVars + havingVars
            )
        }

        // #274-B: run even when annotation.effects is null (annotation
        // implicitly promises pure). Missing rows = "pure", still a
        // constraint the body must satisfy.
        if (annotation is ParsedFunctionType && !bodyContainsNativeCall(item.value)) {
            annotationValidator.validateEffectsNotOverpromised(
                preConvertedAnnotationType, inferredTypeWithAbilities,
                varName, item.value.loc
            )
        }

        if (annotation is ParsedFunctionType && annotation.abilityConstraints.isNotEmpty()) {
            val resolvedAbilityConstraints = result.abilityConstraints.map { constraint ->
                val resolvedTypeVar = substitution.apply(MezzeType.VarType(constraint.typeVar))
                if (resolvedTypeVar is MezzeType.VarType) {
                    constraint.copy(typeVar = resolvedTypeVar.variable)
                } else {
                    constraint
                }
            }
            annotationValidator.validateAbilityOnCorrectTypeVar(
                preConvertedAnnotationType, resolvedAbilityConstraints,
                varName, substitution, item.value.loc
            )

            // Validate the annotation's `having 'x = T` bindings: reject an
            // undeclared associated-type name (R5), and a wrong concrete value
            // vs the impls' declared bindings (R2) — both hold even on a generic
            // fn. On a generic receiver the resolved var isn't concrete, so R2
            // compares against the concrete `having` values the ability's impls
            // declare, keyed assocName → set of declared types across impls.
            annotationValidator.validateHavingAnnotations(
                preConvertedAnnotationType,
                annotation.abilityConstraints,
                result.abilityConstraints,
                resolvedAssociatedBindings,
                implDeclaredHaving(context, annotation.abilityConstraints),
                varName, item.value.loc
            )
        }
    }

    private fun buildBindingForContext(
        solvedType: MezzeType,
        bindingKey: BindingKey,
        currentContext: TypingContext,
        item: ParsedLetDecl,
        result: ConstraintResult,
        substitution: Substitution,
        authoritativeType: MezzeType,
    ): TypingContext.Binding {
        val freeVars = collectFreeTypeVars(solvedType)
        val contextVars = getContextFreeVars(currentContext, excludeBindings = setOf(bindingKey))
        val quantifiableVars = freeVars - contextVars

        val hasExplicitAbilityConstraints = when (val annotation = item.typeAnnotation) {
            is ParsedFunctionType -> annotation.abilityConstraints.isNotEmpty()
            else -> false
        }

        val isLambdaBinding = item.value is ParsedLambda

        val hasUserDefinedAbilityConstraints = if (hasExplicitAbilityConstraints || isLambdaBinding) {
            false
        } else {
            result.abilityConstraints.any { constraint ->
                val isBuiltInAbility = BuiltInAbilities.isBuiltInAbility(constraint.abilityName)
                if (isBuiltInAbility) {
                    false
                } else {
                    val resolvedType = substitution.apply(MezzeType.VarType(constraint.typeVar))
                    val resolvedTypeVar = if (resolvedType is MezzeType.VarType) resolvedType.variable else null
                    val mainVarIsQuantifiable = resolvedTypeVar != null && resolvedTypeVar in quantifiableVars

                    val typeArgsHaveQuantifiableVars = constraint.typeArgs.any { typeArg ->
                        collectFreeTypeVars(typeArg).any { freeVar ->
                            val resolved = substitution.apply(MezzeType.VarType(freeVar))
                            resolved is MezzeType.VarType && resolved.variable in quantifiableVars
                        }
                    }

                    mainVarIsQuantifiable || typeArgsHaveQuantifiableVars
                }
            }
        }

        if (quantifiableVars.isNotEmpty() && !hasUserDefinedAbilityConstraints) {
            val abilityConstraints = if (hasExplicitAbilityConstraints && authoritativeType is MezzeType.FunctionType) {
                authoritativeType.abilityConstraints.mapNotNull { constraint ->
                    val typeVarArg = constraint.typeArgs.filterIsInstance<MezzeType.VarType>().firstOrNull()
                    if (typeVarArg != null) {
                        AbilityConstraint(
                            abilityName = constraint.abilityName,
                            typeParam = typeVarArg.variable.name,
                            typeArgs = constraint.typeArgs
                        )
                    } else null
                }
            } else {
                val quantifiableVarIds = quantifiableVars.map { it.id }.toSet()
                result.abilityConstraints.mapNotNull { constraint ->
                    val resolvedConstraintVar = substitution.apply(MezzeType.VarType(constraint.typeVar))
                    val constraintVarId = if (resolvedConstraintVar is MezzeType.VarType) {
                        resolvedConstraintVar.variable.id
                    } else {
                        constraint.typeVar.id
                    }
                    if (constraintVarId in quantifiableVarIds) {
                        val quantVar = quantifiableVars.find { it.id == constraintVarId }
                        val resolvedTypeArgs = constraint.typeArgs.map { substitution.apply(it) }
                        AbilityConstraint(
                            abilityName = constraint.abilityName,
                            typeParam = quantVar?.name ?: constraint.typeVar.name,
                            typeArgs = resolvedTypeArgs
                        )
                    } else null
                }
            }

            val scheme = TypeScheme(
                quantifiedVars = quantifiableVars.toList(),
                constraints = abilityConstraints,
                type = solvedType
            )
            return TypingContext.Binding.Polymorphic(scheme, contentHash = null)
        } else {
            return TypingContext.Binding.Monomorphic(solvedType, contentHash = null)
        }
    }

    private fun processInherentImpl(
        item: ParsedImplAbilityDecl,
        context: TypingContext,
        results: MutableMap<String, ConstraintResult>,
    ): TypingContext {
        var currentContext = context
        val targetTypeName = item.targetTypes.firstOrNull()?.let { ann ->
            when (ann) {
                is ParsedNamedType -> ann.name
                is ParsedTypeApp -> ann.constructor
                else -> null
            }
        }
        val targetTypeHash = targetTypeName?.let { registry.typeDeclarations[it]?.contentHash }

        for (op in item.operations) {
            val targetType = if (item.targetTypes.isNotEmpty()) {
                convertTypeAnnotation(item.targetTypes.first())
            } else {
                MezzeType.VarType(TypeVar.fresh())
            }

            if (op.implementation !is ParsedLambda) continue
            val lambda = op.implementation as ParsedLambda

            val methodInfo = if (targetTypeHash != null) {
                currentContext.findAnonymousMethodByHash(targetTypeHash, op.name)
                    ?: targetTypeName?.let { currentContext.findAnonymousMethod(it, op.name) }
            } else if (targetTypeName != null) {
                currentContext.findAnonymousMethod(targetTypeName, op.name)
            } else null

            val paramNames = when (val params = lambda.params) {
                is ParsedLambdaParams.Untyped -> params.names
                is ParsedLambdaParams.Typed -> params.fields.map { it.name }
            }

            val paramTypes = if (op.paramType != null) {
                val expectedParam = convertTypeAnnotation(op.paramType!!)
                if (expectedParam is MezzeType.RecordType) {
                    paramNames.associateWith { name ->
                        expectedParam.fields[name] ?: MezzeType.VarType(TypeVar.fresh())
                    }
                } else {
                    paramNames.associateWith { expectedParam }
                }
            } else {
                when (val params = lambda.params) {
                    is ParsedLambdaParams.Typed -> params.fields.associate { entry ->
                        entry.name to convertTypeAnnotation(entry.type)
                    }
                    else -> paramNames.associateWith { name ->
                        if (name == Keywords.IT) targetType
                        else MezzeType.VarType(TypeVar.fresh())
                    }
                }
            }

            var bodyContext = currentContext
            for ((name, type) in paramTypes) {
                bodyContext = bodyContext.extendMono(name, type)
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
                typeDeclarations = registry.typeDeclarations
            )

            val bodyResult = expressionGenerator.generate(lambda.body, constraintContext)

            val constraints = bodyResult.constraints.toMutableList()

            val registeredMethodType = methodInfo?.methodType
            if (registeredMethodType is MezzeType.FunctionType) {
                constraints.add(Constraint.Equality(
                    left = registeredMethodType.returnType,
                    right = bodyResult.type,
                    context = "Inherent impl return type for ${op.name}",
                    loc = op.loc,
                ))
                val paramRecordType = MezzeType.RecordType(fields = paramTypes, rowVar = null)
                constraints.add(Constraint.Equality(
                    left = registeredMethodType.paramType,
                    right = paramRecordType,
                    context = "Inherent impl param type for ${op.name}",
                    loc = op.loc,
                ))
            }

            val opKey = "_inherent_${item.loc}_${op.name}"
            val implParamRecord = MezzeType.RecordType(fields = paramTypes, rowVar = null)
            val bodyEffectRow: MezzeType.EffectRow? =
                if (bodyResult.effects.isEmpty() && bodyResult.effectInstances.isEmpty()) {
                    null
                } else {
                    MezzeType.EffectRow.simple(
                        effects = bodyResult.effects,
                        rowVar = null,
                        performed = false
                    ).copy(effectInstances = bodyResult.effectInstances)
                }
            val implFunctionType = MezzeType.FunctionType(
                paramType = implParamRecord,
                returnType = bodyResult.type,
                effects = bodyEffectRow
            )
            // #180: refresh the registered InherentMethodInfo with the body-derived FunctionType
            if (methodInfo != null && bodyEffectRow != null) {
                val refreshedInfo = methodInfo.copy(methodType = implFunctionType)
                currentContext = currentContext.extendWithAnonymousMethod(refreshedInfo)
            }
            results[opKey] = ConstraintResult(
                type = implFunctionType,
                constraints = constraints,
                effects = bodyResult.effects,
                effectInstances = bodyResult.effectInstances,
                abilityConstraints = bodyResult.abilityConstraints,
                associatedTypeConstraints = bodyResult.associatedTypeConstraints,
                undefinedVariables = emptyList(),
                usedVariables = bodyResult.usedVariables,
                callReturnTypes = bodyResult.callReturnTypes,
                methodCallResolutions = bodyResult.methodCallResolutions
            )
        }
        return currentContext
    }

    private fun generateLetBinding(
        binding: ParsedLetDecl,
        context: TypingContext,
        recordLiteralBindings: Set<String>,
        preConvertedAnnotationType: MezzeType? = null
    ): ConstraintResult {
        DebugLogger.debug(Category.ConstraintGenerator) {
            val exprInfo = if (binding.value is ParsedIdentifier) {
                " Identifier='${(binding.value as ParsedIdentifier).localName}'"
            } else ""
            "generateLetBinding: binding=${binding.variableName} expr=${binding.value.javaClass.simpleName}$exprInfo"
        }

        val expectedType = preConvertedAnnotationType ?: binding.typeAnnotation?.let { convertTypeAnnotation(it) }

        val bindingName = getBindingName(binding)
        val enclosingFunctionName = if (binding.value is ParsedLambda) bindingName else null

        // abilities declare the same method (#304) — the user's explicit
        // declaration wins over a coincidentally-named prelude ability.
        val annotationAbilityNames = (binding.typeAnnotation as? ParsedFunctionType)
            ?.abilityConstraints
            ?.map { it.abilityName }
            ?.toSet()
            .orEmpty()

        val constraintContext = ConstraintContext(
            context,
            expectedType = expectedType,
            performerToEffect = performerToEffect,
            performerAddedEffects = performerAddedEffects,
            abilityNames = abilityNames,
            effectNames = effectNames,
            effectDeclarations = registry.effectDeclarations,
            abilityDeclarations = registry.abilityDeclarations,
            recordLiteralBindings = recordLiteralBindings,
            typeDeclarations = registry.typeDeclarations,
            currentBindingName = bindingName,
            enclosingFunctionName = enclosingFunctionName,
            annotationAbilityNames = annotationAbilityNames
        )

        val exprResult = expressionGenerator.generate(
            binding.value,
            constraintContext
        )

        val bindingType = when (val t = exprResult.type) {
            is MezzeType.SuspendType -> t.valueType
            else -> t
        }

        val patternResult = patternGenerator.generate(
            binding.pattern,
            bindingType,
            constraintContext,
            binding.value.loc
        )

        val allConstraints = exprResult.constraints + patternResult.constraints

        val typeAnnotationValue = binding.typeAnnotation
        val (finalConstraints, abilityConstraints) = if (typeAnnotationValue != null && expectedType != null) {
            val annotatedType = expectedType

            annotationValidator.validateAnnotationAgainstExpression(binding, annotatedType, typeAnnotationValue, binding.value, exprResult.type)

            val annotationAbilityConstraints = if (typeAnnotationValue is ParsedFunctionType) {
                // Prefer the already-converted annotation FunctionType (typeArgs are
                // MezzeType); fall back to converting the parsed annotation's args.
                // Either way, each constraint's first type-VAR is the primary; skip
                // constraints with no variable (nothing to require).
                val preConvertedFnType = annotatedType as? MezzeType.FunctionType
                val abilityTypeArgs: List<Pair<String, List<MezzeType>>> =
                    if (preConvertedFnType != null) {
                        preConvertedFnType.abilityConstraints.map { it.abilityName to it.typeArgs }
                    } else {
                        typeAnnotationValue.abilityConstraints.map { c ->
                            c.abilityName to c.typeArgs.map { convertTypeAnnotation(it) }
                        }
                    }
                abilityTypeArgs.mapNotNull { (abilityName, typeArgs) ->
                    val typeVarArg = typeArgs.filterIsInstance<MezzeType.VarType>().firstOrNull()
                        ?: return@mapNotNull null
                    Constraint.AbilityRequired(
                        typeVar = typeVarArg.variable,
                        abilityName = abilityName,
                        abilityHash = registry.abilityDeclarations[abilityName]?.contentHash,
                        typeArgs = typeArgs,
                        context = "Type annotation on ${getBindingName(binding)}",
                        loc = binding.loc,
                    )
                }
            } else {
                emptyList()
            }

            val bindingName = getBindingName(binding)
            val constraintsWithAnnotation = allConstraints + Constraint.Equality(
                left = exprResult.type,
                right = annotatedType,
                context = "Type annotation on $bindingName",
                loc = binding.value.loc,
                errorContext = ErrorContext.TypeAnnotation(bindingName = bindingName)
            )

            Pair(constraintsWithAnnotation, exprResult.abilityConstraints + annotationAbilityConstraints)
        } else {
            Pair(allConstraints, exprResult.abilityConstraints)
        }

        val expectedHints: Map<Loc, MezzeType> =
            if (typeAnnotationValue != null && expectedType != null) {
                val fnAnnotation = expectedType as? MezzeType.FunctionType
                val lambdaBody = (binding.value as? ParsedLambda)?.body
                if (fnAnnotation != null && lambdaBody != null) {
                    mapOf(lambdaBody.loc to fnAnnotation.returnType)
                } else {
                    mapOf(binding.value.loc to expectedType)
                }
            } else emptyMap()

        return exprResult.copy(
            constraints = finalConstraints,
            abilityConstraints = abilityConstraints,
            expectedTypeAt = exprResult.expectedTypeAt + expectedHints,
        )
    }

    private fun getContextFreeVars(context: TypingContext, excludeBindings: Set<BindingKey> = emptySet()): Set<TypeVar> {
        val freeVars = mutableSetOf<TypeVar>()
        for ((key, binding) in context.getAllBindings()) {
            if (key in excludeBindings) continue

            when (binding) {
                is TypingContext.Binding.Monomorphic -> {
                    freeVars.addAll(collectFreeTypeVars(binding.type))
                }
                is TypingContext.Binding.Polymorphic -> {
                    val typeFreeVars = collectFreeTypeVars(binding.scheme.type)
                    val unquantified = typeFreeVars - binding.scheme.quantifiedVars.toSet()
                    freeVars.addAll(unquantified)
                }
            }
        }
        return freeVars
    }

    internal fun addAbilityConstraintsToFunctionType(
        type: MezzeType,
        abilityConstraints: List<Constraint.AbilityRequired>,
        substitution: Substitution,
        resolvedAssociatedBindings: Map<Pair<String, Int>, Map<String, MezzeType>> = emptyMap()
    ): MezzeType {
        DebugLogger.debugSection(Category.ConstraintGenerator, "addAbilityConstraintsToFunctionType") {
            val lines = mutableListOf("Type: $type")
            if (type is MezzeType.FunctionType) {
                lines.add("Param type: ${type.paramType}")
                if (type.paramType is MezzeType.RecordType) {
                    val record = type.paramType as MezzeType.RecordType
                    lines.add("  Record fields:")
                    record.fields.forEach { (name, fieldType) ->
                        lines.add("    * $name: $fieldType")
                    }
                }
                lines.add("Return type: ${type.returnType}")
            }
            lines.add("Ability constraints received: ${abilityConstraints.size} constraints")
            abilityConstraints.forEach { lines.add("  * $it") }
            lines
        }

        if (type !is MezzeType.FunctionType) {
            return type
        }

        val typeVars = mutableSetOf<TypeVar>()
        collectTypeVariables(type.paramType, typeVars)
        collectTypeVariables(type.returnType, typeVars)

        DebugLogger.debugSection(Category.ConstraintGenerator, "Collected type vars") {
            typeVars.map { "* ${it.name} (id=${it.id})" }
        }

        val functionAbilityConstraints = abilityConstraints.mapNotNull { constraint ->
            val solvedTypeVar = substitution.apply(MezzeType.VarType(constraint.typeVar))

            if (solvedTypeVar is MezzeType.VarType) {
                val matchingTypeVar = typeVars.find { it.name == solvedTypeVar.variable.name }

                val constraintResolvedHash = constraint.abilityHash ?: WellKnownHashConstants.resolveHash(constraint.abilityName)
                if (matchingTypeVar != null && !WellKnownHashConstants.isInspect(constraintResolvedHash)) {
                    // Carry the FULL type-arg tuple: primary (the matched var) plus
                    // the constraint's extra ability args, substituted. Hardcoding a
                    // single arg dropped the extras for a multi-param ability, which
                    // both malformed the constraint and produced a duplicate against
                    // the correct multi-arg one from the call-site path.
                    //
                    // Populate `having` bindings resolved during solving for this
                    // ability on this primary var (keyed by original primary id).
                    // Concrete or var-valued — mirrors how effects carry
                    // associatedTypes, so the with-clause renders + unifies.
                    val bindings = resolvedAssociatedBindings[constraint.abilityName to constraint.typeVar.id]
                        ?: emptyMap()
                    MezzeType.FunctionAbilityConstraint(
                        abilityName = constraint.abilityName,
                        typeArgs = listOf(MezzeType.VarType(matchingTypeVar)) +
                            constraint.typeArgs.map { substitution.apply(it) },
                        associatedTypeBindings = bindings,
                        abilityHash = constraint.abilityHash
                    )
                } else null
            } else null
        }

        // Dedup on (abilityName, DISTINCT typeArg identities). Using the distinct
        // set rather than the positional list collapses a malformed self-repeat
        // like `Producer 'a 'a` (single-param receiver accidentally prepended
        // onto a typeArgs that already held it) against the correct `Producer 'a`
        // — while keeping a genuine multi-param `Scale 'by 'a` (two DISTINCT args)
        // separate from `Scale 'a`. When duplicates collapse, prefer the one that
        // carries associated-type bindings (the populated one) over an empty
        // sibling from another pass.
        fun argKey(arg: MezzeType) = when (arg) {
            is MezzeType.VarType -> "v:" + arg.variable.name
            else -> "t:" + arg.toString()
        }
        val allConstraints = (type.abilityConstraints + functionAbilityConstraints)
            .sortedByDescending { it.associatedTypeBindings.size }
            .distinctBy { c -> c.abilityName to c.typeArgs.map(::argKey).distinct() }

        DebugLogger.debugSection(Category.ConstraintGenerator, "addAbilityConstraintsToFunctionType result") {
            val lines = mutableListOf("All constraints: ${allConstraints.size} constraints")
            allConstraints.forEach { lines.add("  * $it") }
            lines.add("Function ability constraints added: ${functionAbilityConstraints.size}")
            functionAbilityConstraints.forEach { lines.add("  * $it") }
            lines
        }

        return MezzeType.FunctionType(
            paramType = type.paramType,
            returnType = type.returnType,
            effects = type.effects,
            abilityConstraints = allConstraints
        )
    }

    private fun getBindingName(binding: ParsedLetDecl): String =
        binding.pattern.extractBindings().firstOrNull() ?: when (binding.pattern) {
            is ParsedWildcardPattern -> "_wildcard"
            is ParsedLiteralPattern -> "_literal"
            else -> "_pattern"
        }

    /**
     * Reorder decls so a top-level `let` callee is inferred before a caller
     * that references it, when the dependency is acyclic. Only reorders `let`
     * bindings relative to each other; every non-`let` decl keeps its original
     * index, and lets are placed by a STABLE topological sort so unrelated
     * bindings and cycles preserve source order (the previous behaviour). This
     * makes a forward reference to a polymorphic fn resolve to its real scheme
     * instead of a monomorphic placeholder var (#126).
     *
     * Edges: `let a` depends on `let b` iff a's body references the name `b`
     * (b bound by another top-level `let`). Self-references and cycles are
     * broken by falling back to source order — safe because that reproduces
     * today's placeholder path, which is already correct for direct/mutual
     * recursion (the callee is in scope via its own placeholder).
     */
    private fun dependencyOrderedDecls(decls: List<ParsedDecl>): List<ParsedDecl> {
        val lets = decls.filterIsInstance<ParsedLetDecl>()
        if (lets.size < 2) return decls  // nothing to reorder

        val letByName: Map<String, ParsedLetDecl> = lets
            .filter { it.isSimpleBinding }
            .associateBy { it.variableName }
        if (letByName.isEmpty()) return decls

        // Dependencies: name -> set of top-level let names its body references.
        val deps: Map<ParsedLetDecl, Set<ParsedLetDecl>> = lets.associateWith { d ->
            val referenced = mutableSetOf<String>()
            collectReferencedNames(d.value, referenced)
            referenced.mapNotNull { name ->
                letByName[name]?.takeIf { it !== d }  // ignore self-reference
            }.toSet()
        }

        // Stable DFS post-order → callee-before-caller. Cycles: a node already
        // on the stack is skipped (edge dropped), so the cycle keeps the order
        // its members are first visited in, i.e. source order.
        val sortedLets = ArrayList<ParsedLetDecl>(lets.size)
        val visited = HashSet<ParsedLetDecl>()
        val onStack = HashSet<ParsedLetDecl>()
        fun visit(d: ParsedLetDecl) {
            if (d in visited) return
            onStack.add(d)
            for (dep in deps[d].orEmpty()) {
                if (dep !in onStack) visit(dep)
            }
            onStack.remove(d)
            visited.add(d)
            sortedLets.add(d)
        }
        for (d in lets) visit(d)  // source order drives the outer walk → stable

        // If the topo order equals source order (no forward references to
        // reorder — the overwhelmingly common case), return the original list
        // unchanged so this pass is a guaranteed no-op. Compare by identity so
        // destructuring lets (record patterns, no `variableName`) are safe.
        if (sortedLets.zip(lets).all { (a, b) -> a === b }) return decls

        // Weave the sorted lets back into the original decl stream, keeping
        // every non-let decl at its original position.
        val letQueue = ArrayDeque(sortedLets)
        return decls.map { decl ->
            if (decl is ParsedLetDecl) letQueue.removeFirst() else decl
        }
    }

    /**
     * Collect FREE identifier names referenced inside an expr — names that
     * resolve to an enclosing/top-level binding, not to a local one. Must be
     * scope-aware: a lambda param / do-let / match-binding named `x` shadows a
     * top-level `x`, so a reference under that binder is NOT a dependency on the
     * top-level `x`. Ignoring this created false edges (and false cycles, e.g.
     * `let f = {x} -> x` "depending on" a top-level `x`) that mis-reordered
     * unrelated bindings. `bound` is the set of names shadowed at this point.
     */
    private fun collectReferencedNames(
        expr: ParsedExpr,
        out: MutableSet<String>,
        bound: Set<String> = emptySet(),
    ) {
        when (expr) {
            is ParsedIdentifier ->
                if (expr.qualifier == null && expr.localName !in bound) out.add(expr.localName)
            is ParsedLambda -> {
                val params = when (val p = expr.params) {
                    is ParsedLambdaParams.Untyped -> p.names
                    is ParsedLambdaParams.Typed -> p.fields.map { it.name }
                }
                collectReferencedNames(expr.body, out, bound + params)
            }
            is ParsedDo -> {
                // A do-block's `let`/`bind` names scope over later statements
                // and the result; accumulate them left-to-right.
                var scope = bound
                for (stmt in expr.statements) {
                    when (stmt) {
                        is ParsedStatement.Let -> {
                            collectReferencedNames(stmt.value, out, scope)
                            scope = scope + stmt.pattern.extractBindings()
                        }
                        is ParsedStatement.Expr -> collectReferencedNames(stmt.expr, out, scope)
                    }
                }
                collectReferencedNames(expr.result, out, scope)
            }
            is ParsedMatch -> {
                collectReferencedNames(expr.scrutinee, out, bound)
                for (case in expr.cases) {
                    val caseBound = bound + case.patterns.flatMap { it.extractBindings() } +
                        case.ifLetBindings.flatMap { it.pattern.extractBindings() }
                    case.guard?.let { collectReferencedNames(it, out, caseBound) }
                    case.ifLetBindings.forEach { collectReferencedNames(it.expr, out, caseBound) }
                    collectReferencedNames(case.body, out, caseBound)
                }
            }
            else -> for (child in expr.children()) collectReferencedNames(child, out, bound)
        }
    }

    private fun convertTypeAnnotation(annotation: ParsedTypeAnnotation): MezzeType {
        typeVarCache.clear()
        sharedRowVar.reset()
        return TypeAnnotationConverter.convertWith(
            annotation, constraintConversionConfig, typeVarCache, sharedRowVar
        )
    }

    companion object {
        fun create(
            registry: DeclarationRegistry = DeclarationRegistry.EMPTY,
            performerToEffect: Map<String, String> = emptyMap(),
            performerAddedEffects: Map<String, Set<String>> = emptyMap(),
            abilityNames: Set<String> = emptySet(),
            effectNames: Set<String> = emptySet(),
        ): ConstraintGenerator {
            val expressionGen = ExpressionConstraintGenerator()
            expressionGen.registry = registry
            val patternGen = PatternConstraintGenerator()

            return ConstraintGenerator(
                expressionGen,
                patternGen,
                registry,
                performerToEffect,
                performerAddedEffects,
                abilityNames,
                effectNames,
            )
        }
    }

}


internal fun bodyContainsNativeCall(expr: ParsedExpr): Boolean = when (expr) {
    is ParsedIdentifier -> expr.localName.startsWith("__native_")
    is ParsedLambda -> bodyContainsNativeCall(expr.body)
    is ParsedFunctionCall -> bodyContainsNativeCall(expr.function)
    is ParsedDo -> bodyContainsNativeCall(expr.result) || expr.statements.any { stmt ->
        when (stmt) {
            is ParsedStatement.Let -> bodyContainsNativeCall(stmt.value)
            is ParsedStatement.Expr -> bodyContainsNativeCall(stmt.expr)
            else -> false
        }
    }
    else -> false
}

data class PatternConstraintResult(
    val bindings: Map<String, MezzeType>,
    val constraints: List<Constraint>,
    val warnings: List<String> = emptyList(),
    val bindingLocations: Map<String, Loc> = emptyMap()
)
