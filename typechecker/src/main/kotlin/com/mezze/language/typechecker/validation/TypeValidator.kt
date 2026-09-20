package com.mezze.language.typechecker.validation

import com.mezze.language.ast.*
import com.mezze.language.parsedast.*
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.constraints.Constraint
import com.mezze.language.typechecker.constraints.ConstraintResult
import com.mezze.language.ast.solving.Substitution
import com.mezze.language.typechecker.solving.expandTypeApplicationToVariant
import com.mezze.language.typechecker.abilities.BuiltInAbilities
import com.mezze.language.typechecker.codec.CodecGenerator
import com.mezze.language.typechecker.hashing.ContentHashComputer
import com.mezze.language.typechecker.util.StringDistance
import com.mezze.language.typechecker.util.TypeAnnotationConverter
import com.mezze.language.typechecker.util.TypeAnnotationMatcher
import com.mezze.language.typechecker.WellKnownHashConstants
import com.mezze.language.typechecker.context.DeclarationRegistry
import com.mezze.language.typedast.Hash
import com.mezze.language.typedast.TypedModule
import com.mezze.language.typedast.TypedExpr
import com.mezze.language.typedast.TypedMatch
import com.mezze.language.typedast.forEachChild

class TypeValidator(
    private val context: TypingContext,
    private val registry: DeclarationRegistry = DeclarationRegistry.EMPTY,
    private val implDeclarations: Map<String, ParsedPerformerDecl> = emptyMap(),
    private val strictTopLevel: Boolean = false,
) {
    private val typeDeclarations: Map<String, ParsedTypeDecl> get() = registry.typeDeclarations
    private val effectDeclarations: Map<String, ParsedEffectDecl> get() = registry.effectDeclarations
    private val abilityResolver = AbilityResolver(context, registry)
    private val effectValidator = EffectValidator(context)
    private val matchValidator = MatchValidator(typeDeclarations, registry)
    private val performValidator = PerformValidator(typeDeclarations, implDeclarations)
    private val unknownTypeValidator = UnknownTypeValidator(registry)
    private val codecGenerator = CodecGenerator()

    private val typeNameCache = mutableMapOf<Int, String>()
    fun validate(
        module: ParsedModule,
        constraintResults: Map<String, ConstraintResult>,
        substitution: Substitution,
        typedModule: TypedModule? = null,
    ): ValidationResult {
        val errors = mutableListOf<ValidationError>()
        val warnings = mutableListOf<String>()

        // S5.1 (#364): exhaustiveness reads the DECIDED TypedMatch nodes off the
        // just-emitted TypedModule. Each TypedMatch carries its UNERASED
        // scrutineeSourceType (#376), keyed here by the match node's Loc (which
        // equals the ParsedMatch's Loc — emit stamps `loc = expr.loc`). The
        // MatchValidator looks the decided type up by Loc instead of re-inferring
        // it from the constraint-time side-table.
        val decidedScrutineeTypes: Map<Loc, MezzeType> =
            typedModule?.let { buildDecidedScrutineeTypes(it) } ?: emptyMap()

        errors.addAll(unknownTypeValidator.validate(module))

        for ((bindingName, result) in constraintResults) {
            val finalType = substitution.apply(result.type)

            val abilityValueErrors = validateBuiltInAbilityUsage(finalType, bindingName)
            errors.addAll(abilityValueErrors)

            val abilityErrors = validateAbilityConstraints(
                result.abilityConstraints,
                finalType,
                bindingName,
                substitution
            )
            errors.addAll(abilityErrors)
        }

        for (decl in module.decls) {
            when (decl) {
                is ParsedLetDecl -> {
                    errors.addAll(validateParsedAnnotation(decl, substitution, constraintResults))
                    errors.addAll(validateParsedTopLevelEffects(decl, substitution, constraintResults))
                    errors.addAll(validateHermeticInferredEffects(decl, substitution, constraintResults))
                    val scrutineeType = if (decl.isSimpleBinding) {
                        constraintResults[decl.variableName]?.type?.let { substitution.apply(it) }
                    } else null
                    if (scrutineeType != null) errors.addAll(validateLetPattern(decl.pattern, scrutineeType))
                    errors.addAll(validatePatternsInExpression(decl.value, substitution))
                }
                is ParsedTopLevelExpr -> {
                    // A bare expression at the top level parses (it's the REPL/eval
                    // affordance — a single expression IS the program), but in a
                    // real program file it has nothing to run it and silently never
                    // executes. In strict (program) mode, flag it. `strictTopLevel`
                    // is false for the eval/REPL/interpreter paths, so this only
                    // fires for `mez check`/`dev`/`build` and any frontend that opts
                    // into program mode.
                    if (strictTopLevel) {
                        errors.add(ValidationError.TopLevelExpression(decl.loc))
                    }
                    errors.addAll(validatePatternsInExpression(decl.expression, substitution))
                }
                is ParsedEffectDecl -> errors.addAll(effectValidator.validateEffectDeclaration(decl))
                is ParsedPerformerDecl -> {
                    val effectDecl = effectDeclarations[decl.effect.localName]
                    if (effectDecl != null) errors.addAll(effectValidator.validateEffectImpl(decl, effectDecl))
                }
                else -> {}
            }
        }

        val parsedFunctionDefs = buildMap {
            for (decl in module.decls) {
                if (decl is ParsedLetDecl && decl.isSimpleBinding) {
                    (decl.value as? ParsedLambda)?.let { put(decl.variableName, it) }
                }
            }
        }
        for (decl in module.decls) {
            val (expr, currentDeclName) = when (decl) {
                is ParsedLetDecl -> {
                    val name = if (decl.isSimpleBinding) decl.variableName else null
                    decl.value to name
                }
                is ParsedTopLevelExpr -> decl.expression to null
                else -> null to null
            }
            if (expr == null) continue
            errors.addAll(performValidator.validatePerformExpressionsInExpression(expr, implDeclarations, effectDeclarations, parsedFunctionDefs))
            errors.addAll(matchValidator.validateParsedMatchesInExpr(expr, decidedScrutineeTypes))
        }

        return if (errors.isEmpty()) {
            ValidationResult.Success(warnings)
        } else {
            ValidationResult.Failure(errors)
        }
    }

    /**
     * S5.1 (#364): collect every emitted TypedMatch's UNERASED scrutineeSourceType,
     * keyed by the match node's Loc, across all decl / ability-impl / performer
     * bodies. This is the exhaustiveness oracle the MatchValidator reads — the
     * decided node, not a constraint-time re-inference. A Bool match emits a
     * TypedIf (not a TypedMatch), so it's absent here; that's fine — the Bool /
     * constructor-pattern exhaustiveness path is handled separately from the
     * source AST.
     */
    private fun buildDecidedScrutineeTypes(typedModule: TypedModule): Map<Loc, MezzeType> {
        val out = mutableMapOf<Loc, MezzeType>()
        fun walk(expr: TypedExpr) {
            if (expr is TypedMatch) out[expr.loc] = expr.scrutineeSourceType
            expr.forEachChild(::walk)
        }
        for (decl in typedModule.decls) {
            if (decl is com.mezze.language.typedast.TypedLetDecl) walk(decl.value)
        }
        for (impl in typedModule.abilityImpls) impl.operations.values.forEach(::walk)
        for (performer in typedModule.performerDecls) performer.operations.values.forEach(::walk)
        return out
    }

    fun validateConstraints(
        constraints: List<Constraint>,
        substitution: Substitution
    ): ValidationResult {
        val errors = mutableListOf<ValidationError>()

        for (constraint in constraints) {
            when (constraint) {
                is Constraint.AbilityRequired -> {
                    if (BuiltInAbilities.isBuiltInAbility(constraint.abilityName)) continue
                    val type = substitution.apply(MezzeType.VarType(constraint.typeVar))
                    val resolvedTypeArgs = constraint.typeArgs.map { substitution.apply(it) }
                    val result = abilityResolver.checkAbility(type, constraint.abilityName, resolvedTypeArgs, constraint.abilityHash)
                    if (result is AbilityResolver.AbilityCheckResult.Unsatisfied) {
                        errors.add(ValidationError.AbilityNotSatisfied(
                            type = type, abilityName = constraint.abilityName,
                            requiredBy = constraint.context, loc = constraint.loc
                        ))
                    }
                }

                is Constraint.HasConstructor -> {
                    val appliedType = substitution.apply(constraint.variantType)
                    val variantType = when (appliedType) {
                        is MezzeType.VariantType -> appliedType
                        is MezzeType.TypeApplication -> {
                            val definition = typeDeclarations[appliedType.constructor]?.definition
                            if (definition is ParsedVariantDef) {
                                MezzeType.VariantType(
                                    constructors = definition.constructors.associate { it.name to it.dataType?.let { dt -> TypeAnnotationConverter.convert(dt, resolveTypeHash = { name -> registry.lookupType(name)?.let { d -> d.contentHash ?: ParsedDeclHasher.hashTypeDecl(d) } }) } },
                                    rowVar = if (definition.isOpen) RowVar.fresh() else null
                                )
                            } else null
                        }
                        else -> null
                    } ?: continue

                    if (!variantType.constructors.containsKey(constraint.constructorName) && variantType.rowVar == null) {
                        errors.add(ValidationError.ConstructorNotFound(
                            variantType = variantType, constructorName = constraint.constructorName,
                            availableConstructors = variantType.constructors.keys.sorted(), loc = constraint.loc
                        ))
                    }
                }

                is Constraint.HasField,
                is Constraint.Equality, is Constraint.Subsumes,
                is Constraint.MustBeOpen, is Constraint.RecordPatternExhaustive,
                is Constraint.AssociatedTypeResolution,
                // #160: fully resolved by the solver (solveInherentMethodResolve);
                // a mismatch there is already an error, so no re-validation here.
                is Constraint.InherentMethodResolve,
                // Best-effort defaulting handled in the solver; never a validation error.
                is Constraint.DefaultAssociatedType -> {}
            }
        }

        return if (errors.isEmpty()) ValidationResult.Success() else ValidationResult.Failure(errors)
    }

    private fun validateAbilityConstraints(
        constraints: List<Constraint.AbilityRequired>,
        type: MezzeType,
        bindingContext: String,
        substitution: Substitution
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()

        for (constraint in constraints) {
            val concreteType = substitution.apply(MezzeType.VarType(constraint.typeVar))
            if (concreteType is MezzeType.VarType) continue

            val typeName = getTypeNameForAbility(concreteType)
            if (typeName.startsWith("'") || typeName == "Unknown") continue

            val abilityName = constraint.abilityName
            val effectiveHash = constraint.abilityHash ?: registry.resolveAbilityHash(abilityName)

            if (BuiltInAbilities.isBuiltInAbility(abilityName)) continue
            if (canDeriveAbility(abilityName, concreteType, effectiveHash)) continue

            val implTypeParts = mutableListOf<String>()
            var hasPolymorphicTypeArg = false
            var hasConstrainedTypeInArgs = false
            for (typeArg in constraint.typeArgs) {
                val argTypeName = getTypeNameForAbility(substitution.apply(typeArg))
                if (argTypeName.startsWith("'") || argTypeName == "Unknown") { hasPolymorphicTypeArg = true; break }
                if (argTypeName == typeName) hasConstrainedTypeInArgs = true
                implTypeParts.add(argTypeName)
            }
            if (hasPolymorphicTypeArg) continue
            if (!hasConstrainedTypeInArgs) implTypeParts.add(0, typeName)

            val resolvedTypeArgs = constraint.typeArgs.map { substitution.apply(it) }
            // Determine if the primary typeVar ALSO appears among typeArgs by
            // variable identity (not by substituted value). Some constraints
            // (e.g. `with Sized 'a` — 1-param ability, typeArg is same tyvar
            // as receiver) duplicate the receiver in typeArgs and should
            // dedup. Real multi-param constraints (e.g. Okable's `[e]` +
            // primary `a` where 'e ≠ 'a) must NOT dedup even if both happen
            // to unify to the same concrete type — the impl stores separate
            // slots and we need to match each.
            val primaryAlreadyInTypeArgs = constraint.typeArgs.any { arg ->
                arg is MezzeType.VarType && arg.variable == constraint.typeVar
            }
            val concreteTypes = if (primaryAlreadyInTypeArgs) {
                resolvedTypeArgs
            } else {
                listOf(concreteType) + resolvedTypeArgs
            }

            val abilityImpls = context.getAbilityImpls(abilityName)

            if (hasStructuralMatch(abilityImpls, concreteTypes)) continue
            if (abilityImpls.any { it.targetTypes == implTypeParts }) continue
            if (hasAliasMatch(implTypeParts, concreteType, abilityImpls)) continue
            if (implTypeParts.size == 1 && constraint.typeArgs.isNotEmpty() &&
                abilityImpls.any { it.targetTypes.size >= 1 && it.targetTypes.take(1) == implTypeParts }) continue
            if (WellKnownHashConstants.isCodecAbility(effectiveHash) && codecGenerator.isSerializable(concreteType)) continue

            errors.add(ValidationError.MissingAbilityImplementation(
                abilityName = abilityName,
                typeName = implTypeParts.joinToString(", "),
                context = constraint.context ?: bindingContext,
                loc = constraint.loc,
            ))
        }

        return errors
    }

    private fun hasStructuralMatch(
        impls: List<TypingContext.AbilityImplInfo>,
        concreteTypes: List<MezzeType>
    ): Boolean = impls.any { implInfo ->
        val annotations = implInfo.targetTypeAnnotations ?: return@any false
        if (annotations.size != concreteTypes.size) return@any false
        val bindings = mutableMapOf<String, MezzeType>()
        concreteTypes.zip(annotations).all { (t, a) ->
            TypeAnnotationMatcher.typeMatchesAnnotation(t, a, typeDeclarations, implInfo.typeVars, bindings)
        }
    }

    private fun hasAliasMatch(
        implTypeParts: List<String>,
        concreteType: MezzeType,
        impls: List<TypingContext.AbilityImplInfo>
    ): Boolean {
        if (implTypeParts.size != 1) return false
        val aliases = when {
            concreteType is MezzeType.RecordType && concreteType.identity.displayNameOrNull() == null -> findMatchingRecordTypeAliases(concreteType)
            concreteType is MezzeType.VariantType && concreteType.identity.displayNameOrNull() == null -> findMatchingVariantTypeAliases(concreteType)
            else -> return false
        }
        return impls.any { it.targetTypes.size == 1 && it.targetTypes[0] in aliases }
    }

    private fun findMatchingRecordTypeAliases(recordType: MezzeType.RecordType): List<String> =
        typeDeclarations.mapNotNull { (aliasName, typeDecl) ->
            val def = typeDecl.definition as? ParsedRecordDef ?: return@mapNotNull null
            val aliasFields = def.fields.associate { it.name to it.type }
            if (aliasFields.size != recordType.fields.size || aliasFields.keys != recordType.fields.keys) return@mapNotNull null
            val compatible = aliasFields.all { (name, parsedType) ->
                val aliasFieldType = try { TypeAnnotationConverter.convert(parsedType, resolveTypeHash = { name -> registry.lookupType(name)?.let { d -> d.contentHash ?: ParsedDeclHasher.hashTypeDecl(d) } }) } catch (_: Exception) { null }
                val recordFieldType = recordType.fields[name]
                aliasFieldType != null && recordFieldType != null && alphaEquivalent(aliasFieldType, recordFieldType, mutableMapOf())
            }
            if (compatible) aliasName else null
        }

    private fun findMatchingVariantTypeAliases(variantType: MezzeType.VariantType): List<String> =
        buildList {
            for ((aliasName, typeDecl) in typeDeclarations) {
                val def = typeDecl.definition as? ParsedVariantDef ?: continue
                if (def.constructors.map { it.name }.toSet() != variantType.constructors.keys) continue
                if (typeDecl.typeParams.isNotEmpty()) {
                    val inferred = TypeAnnotationMatcher.inferTypeArgsFromVariant(variantType, typeDecl, def)
                    if (inferred != null && inferred.size == typeDecl.typeParams.size) {
                        add("${aliasName}_${inferred.joinToString("_") { getTypeNameForAbility(it) }}")
                    }
                }
                add(aliasName)
            }
        }

    // Task F: was a weaker copy of AbilityChecker's rule — same arms, but no cycle
    // guard, no record expansion, and crucially NO NEWTYPE RULE, so this believed a
    // newtype derives Eq while the checker that actually decides says it does not.
    // Both now call the one rule; only the component test stays local, because this
    // decides a component by matching target annotations in scope while
    // AbilityChecker consults registered impls.
    private fun canDeriveAbility(
        abilityName: String,
        type: MezzeType,
        abilityHash: Hash? = null,
        seen: MutableSet<Any> = mutableSetOf(),
    ): Boolean = com.mezze.language.typechecker.derive.canDeriveAbilityStructurally(
        abilityName, type, abilityHash, registry, seen,
    ) { name, componentType, hash, seenSoFar ->
        canDeriveAbility(name, componentType, hash, seenSoFar) || hasImplInContext(name, componentType)
    }

    private fun canDeriveOrHasImpl(abilityName: String, type: MezzeType, abilityHash: Hash? = null): Boolean =
        canDeriveAbility(abilityName, type, abilityHash) || hasImplInContext(abilityName, type)

    private fun hasImplInContext(abilityName: String, type: MezzeType): Boolean =
        context.getAbilityImpls(abilityName).any { implInfo ->
            val annotations = implInfo.targetTypeAnnotations ?: return@any false
            annotations.size == 1 && TypeAnnotationMatcher.typeMatchesAnnotation(type, annotations[0], typeDeclarations, implInfo.typeVars, mutableMapOf())
        }

    private fun getTypeNameForAbility(type: MezzeType): String {
        val key = System.identityHashCode(type)
        typeNameCache[key]?.let { return it }
        val result = when (type) {
            is MezzeType.VariantType -> type.identity.displayNameOrNull() ?: when {
                type.constructors.keys == setOf(BuiltinTypes.TRUE, BuiltinTypes.FALSE) -> BuiltinTypes.BOOL
                type.constructors.size == 1 -> type.constructors.keys.first()
                else -> ContentHashComputer.mezzeTypeToString(type)
            }
            is MezzeType.RecordType -> type.identity.displayNameOrNull() ?: ContentHashComputer.mezzeTypeToString(type)
            else -> ContentHashComputer.mezzeTypeToString(type)
        }
        typeNameCache[key] = result
        return result
    }

    private fun validateParsedAnnotation(
        decl: ParsedLetDecl,
        substitution: Substitution,
        constraintResults: Map<String, ConstraintResult>
    ): List<ValidationError> {
        val parsedAnnotation = decl.typeAnnotation ?: return emptyList()
        if (!decl.isSimpleBinding) return emptyList()
        val inferredType = constraintResults[decl.variableName]?.type ?: return emptyList()
        val annotatedType = try {
            TypeAnnotationConverter.convert(
                parsedAnnotation,
                resolveTypeHash = { name -> registry.lookupType(name)?.let { d -> d.contentHash ?: ParsedDeclHasher.hashTypeDecl(d) } },
                // THIS module's abilities, so a local `ability Ord` shadows the
                // prelude's as it does everywhere else. Without it the annotation
                // was stamped from the generated stdlib table and compared against
                // an inferred side carrying the local declaration.
                resolveAbilityHash = { name -> registry.resolveAbilityHash(name) },
                resolveTypeAlias = { name ->
                    registry.lookupType(name)?.let { d ->
                        com.mezze.language.typechecker.context.ContextBuilder
                            .aliasedAnnotationForTypeDecl(d)?.let { ann -> ann to d.typeParams }
                    }
                }
            )
        } catch (_: Exception) { return emptyList() }
        val finalType = substitution.apply(inferredType)
        if (!alphaEquivalent(annotatedType, finalType, mutableMapOf())) {
            return listOf(ValidationError.AnnotationMismatch(
                bindingName = decl.variableName, annotatedType = annotatedType,
                inferredType = finalType, loc = decl.loc,
            ))
        }
        return emptyList()
    }

    private fun validateParsedTopLevelEffects(
        decl: ParsedLetDecl,
        substitution: Substitution,
        constraintResults: Map<String, ConstraintResult>
    ): List<ValidationError> {
        if (!decl.isSimpleBinding) return emptyList()
        val result = constraintResults[decl.variableName] ?: return emptyList()
        val finalType = substitution.apply(result.type)
        // Effect-model migration (S4): value types are bare now — read the INCURRED
        // effects off the ConstraintResult. A FUNCTION binding's effects are LATENT on
        // its arrow (it performs nothing until called), so it is never a stranded
        // top-level effect; only non-function bindings are.
        if (finalType is MezzeType.FunctionType) return emptyList()

        // A top-level non-function `let` whose RHS incurs an effect is suspect: the RHS
        // is an effectful computation and the module top level runs no handler. Whether
        // to reject depends on mode:
        //  - strict (mez build / mez run — real entry paths): reject ANY incurred effect.
        //  - permissive (mez test, REPL): reject only the "would execute at module load"
        //    case — performed with no residual named effect — so tests/REPL can still
        //    introspect an intermediate suspended value with a residual row.
        val hasNamedEffect = result.effects.isNotEmpty() || result.effectInstances.isNotEmpty()
        val incursEffect = hasNamedEffect || result.performed
        if (!incursEffect) return emptyList()
        val performedAndEmpty = result.performed && !hasNamedEffect
        val shouldReject = if (strictTopLevel) true else performedAndEmpty
        if (shouldReject) {
            val row = MezzeType.EffectRow(
                effects = result.effects,
                effectInstances = result.effectInstances,
                rowVar = null,
                performed = result.performed
            )
            return listOf(ValidationError.TopLevelPerformedEffects(
                bindingName = decl.variableName, effectRow = row, loc = decl.loc
            ))
        }
        return emptyList()
    }


    /**
     * #202: enforce the hermetic rule (Mut must be the only effect in scope) on a
     * binding's INFERRED effect row. The annotated form is rejected during
     * annotation conversion (V0504 thrown as a TypeCheckError), but an unannotated
     * fn that performs Mut inside a spawned/joined callback gets an inferred
     * {Mut, Async}/{Mut, Parallel} row that never passes through the annotation
     * converter — so it slipped through. We run the same check here on the solved
     * type. A DISCHARGED Mut is already absent from this row, so a
     * `perform LocalMut in …` then another effect is NOT rejected.
     *
     * Reported as a ValidationError (not thrown) so it surfaces as a normal
     * diagnostic; the emit phase swallows thrown exceptions, so throwing here
     * would be lost.
     */
    private fun validateHermeticInferredEffects(
        decl: ParsedLetDecl,
        substitution: Substitution,
        constraintResults: Map<String, ConstraintResult>
    ): List<ValidationError> {
        if (!decl.isSimpleBinding) return emptyList()
        val finalType = substitution.apply(constraintResults[decl.variableName]?.type ?: return emptyList())
        val row = when (finalType) {
            is MezzeType.FunctionType -> finalType.effects
            else -> null
        } ?: return emptyList()

        fun resolveHash(name: String) = row.effectHashes[name] ?: registry.resolveEffectHash(name)
        val names = row.allEffectNames
        val hermetic = names.firstOrNull { WellKnownHashConstants.isMut(resolveHash(it)) } ?: return emptyList()
        if (names.size <= 1) return emptyList()
        val others = names.filter { !WellKnownHashConstants.isMut(resolveHash(it)) }
        return listOf(ValidationError.HermeticEffectConflict(
            bindingName = decl.variableName, hermeticEffect = hermetic, otherEffects = others.sorted(), loc = decl.loc
        ))
    }

    private fun alphaEquivalent(type1: MezzeType, type2: MezzeType, mapping: MutableMap<TypeVar, TypeVar>): Boolean = when {
        type1 == type2 -> true
        type1 is MezzeType.VarType && type2 is MezzeType.VarType -> {
            val mapped = mapping[type1.variable]
            if (mapped != null) mapped == type2.variable else { mapping[type1.variable] = type2.variable; true }
        }
        type1 is MezzeType.VarType || type2 is MezzeType.VarType -> true
        type1 is MezzeType.FunctionType && type2 is MezzeType.FunctionType ->
            alphaEquivalent(type1.paramType, type2.paramType, mapping) &&
            alphaEquivalent(type1.returnType, type2.returnType, mapping) &&
            effectsMatch(type1.effects, type2.effects) &&
            abilityNamesMatch(type1.abilityConstraints, type2.abilityConstraints)
        type1 is MezzeType.RecordType && type2 is MezzeType.RecordType ->
            type1.fields.keys == type2.fields.keys &&
            type1.fields.all { (k, v) -> alphaEquivalent(v, type2.fields[k]!!, mapping) }
        // Variant subtyping: the annotated variant (type1) may declare MORE
        // arms than the inferred body (type2) uses — the body just happens to
        // return a narrow subset. Every arm the body USES must exist in the
        // annotation with an alphaEquivalent payload. This is Mezze's Bool
        // model: `let x : Bool = True` — True (one arm) satisfies Bool (two).
        type1 is MezzeType.VariantType && type2 is MezzeType.VariantType ->
            type2.constructors.keys.all { k -> k in type1.constructors.keys } &&
            type2.constructors.all { (k, v) ->
                val v1 = type1.constructors[k]
                (v == null && v1 == null) || (v != null && v1 != null && alphaEquivalent(v1, v, mapping))
            }
        type1 is MezzeType.TypeApplication && type2 is MezzeType.TypeApplication ->
            type1.constructor == type2.constructor && type1.args.size == type2.args.size &&
            type1.args.zip(type2.args).all { (a, b) -> alphaEquivalent(a, b, mapping) }
        else -> false
    }

    private fun effectsMatch(e1: MezzeType.EffectRow?, e2: MezzeType.EffectRow?): Boolean = when {
        e1 == null && e2 == null -> true
        e1 != null && e2 != null -> {
            // #274-B: With `<...>` (row var) either row is open — an open
            // annotation is compatible with any superset of its declared
            // effects that the body actually performs.
            val bothClosed = e1.rowVar == null && e2.rowVar == null
            if (bothClosed) e1.effects == e2.effects
            else {
                // Open side must be a subset of the other's effects.
                val (open, other) = if (e1.rowVar != null) e1 to e2 else e2 to e1
                open.effects.all { it in other.effects }
            }
        }
        else -> false
    }

    /**
     * The two constraint lists demand the same abilities — by DECLARATION, not by
     * spelling.
     *
     * Keyed on the declaration hash when both sides carry one, on the name only
     * when either does not. Matching is PAIRWISE and not via a single key: a
     * constraint carrying a hash and its counterpart without one would produce
     * different keys (`hash` vs `name`) for the same ability.
     *
     * Hash-keying this failed for a long time, and the failure was real. Two
     * separate defects had to be fixed first:
     *   - BUG 24: identity was dropped at generalization and re-resolved by
     *     spelling at the call site;
     *   - the annotated side was converted with the GENERATED stdlib name->hash
     *     table, which cannot see a local declaration, so a module declaring its
     *     own `ability Ord` had its annotation stamped with the STDLIB's Ord while
     *     the inferred side carried the local one. `TypeValidator` now passes the
     *     module's registry as the converter's ability resolver.
     *
     * `Inspect` is excluded on both sides: it is derived for everything, so its
     * presence says nothing about whether the two lists demand the same abilities.
     */
    private fun abilityNamesMatch(
        c1: List<MezzeType.FunctionAbilityConstraint>,
        c2: List<MezzeType.FunctionAbilityConstraint>
    ): Boolean {
        fun same(a: MezzeType.FunctionAbilityConstraint, b: MezzeType.FunctionAbilityConstraint): Boolean {
            val ah = a.abilityHash
            val bh = b.abilityHash
            return if (ah != null && bh != null) ah == bh else a.abilityName == b.abilityName
        }
        val l1 = c1.filter { it.abilityName != "Inspect" }
        val l2 = c2.filter { it.abilityName != "Inspect" }
        return l1.all { a -> l2.any { b -> same(a, b) } } && l2.all { b -> l1.any { a -> same(a, b) } }
    }

    private fun validateBuiltInAbilityUsage(type: MezzeType, context: String): List<ValidationError> {
        if (type !is MezzeType.VariantType) return emptyList()
        return type.constructors.keys
            .filter { BuiltInAbilities.isBuiltInAbility(it) }
            .map { ValidationError.AbilityUsedAsValue(abilityName = it, context = context, loc = null) }
    }

    fun validateParsedExhaustiveness(
        matchExpr: ParsedMatch,
        scrutineeType: MezzeType
    ): ValidationResult = matchValidator.validateParsedExhaustiveness(matchExpr, scrutineeType)

    internal fun findSimilarNames(target: String, candidates: List<String>, maxDistance: Int = 3): List<String> =
        StringDistance.findSimilar(target, candidates, maxDistance, maxResults = 3)

    private fun validateLetPattern(pattern: ParsedPattern, scrutineeType: MezzeType): List<ValidationError> = when (pattern) {
        is ParsedRecordPattern -> validateRecordPattern(pattern, scrutineeType)
        is ParsedNewtypePattern -> validateLetPattern(pattern.inner, scrutineeType)
        else -> emptyList()
    }

    private fun validateRecordPattern(pattern: ParsedRecordPattern, scrutineeType: MezzeType): List<ValidationError> {
        if (pattern.isOpen) return emptyList()
        val recordType = scrutineeType as? MezzeType.RecordType ?: return emptyList()
        val missing = recordType.fields.keys - pattern.entries.map { it.name }.toSet()
        return if (missing.isNotEmpty()) listOf(ValidationError.NonExhaustiveRecordPattern(
            missingFields = missing.sorted(), pattern = pattern, recordType = recordType, loc = pattern.loc
        )) else emptyList()
    }

    private fun validatePatternsInExpression(expr: ParsedExpr, substitution: Substitution): List<ValidationError> = buildList {
        fun recurse(e: ParsedExpr) { addAll(validatePatternsInExpression(e, substitution)) }
        when (expr) {
            is ParsedDo -> {
                for (stmt in expr.statements) when (stmt) {
                    is ParsedStatement.Let -> recurse(stmt.value)
                    is ParsedStatement.Expr -> recurse(stmt.expr)
                }
                recurse(expr.result)
            }
            is ParsedLambda -> recurse(expr.body)
            is ParsedIf -> { recurse(expr.condition); recurse(expr.thenBranch); recurse(expr.elseBranch) }
            is ParsedFunctionCall -> { recurse(expr.function); recurse(expr.arguments) }
            is ParsedRecordLit -> expr.entries.forEach { entry ->
                when (entry) {
                    is ParsedRecordEntry.Field -> entry.value?.let { recurse(it) }
                    is ParsedRecordEntry.Spread -> recurse(entry.expr)
                }
            }
            is ParsedMatch -> {
                recurse(expr.scrutinee)
                matchValidator.inferParsedScrutineeTypeFromPatterns(expr)?.let { scrutineeType ->
                    val result = validateParsedExhaustiveness(expr, substitution.apply(scrutineeType))
                    if (result is ValidationResult.Failure) addAll(result.errors)
                }
                (matchValidator.validateParsedUnreachablePatterns(expr) as? ValidationResult.Failure)?.let { addAll(it.errors) }
                for (case in expr.cases) { recurse(case.body); case.guard?.let { recurse(it) } }
            }
            is ParsedVariantConstruct -> expr.value?.let { recurse(it) }
            is ParsedNewtypeConstruct -> recurse(expr.value)
            is ParsedMemberAccess -> recurse(expr.receiver)
            is ParsedSeqLit -> expr.elements.forEach { when (it) {
                is ParsedSeqElement.Regular -> recurse(it.expr)
                is ParsedSeqElement.Spread -> recurse(it.expr)
            }}
            is ParsedStringLit -> expr.parts.forEach { if (it is ParsedStringPart.Interpolation) recurse(it.expr) }
            is ParsedPerform -> {
                recurse(expr.body)
                expr.handlers.forEach { it.params?.let { p -> recurse(p) } }
            }
            is ParsedPipe -> { recurse(expr.left); recurse(expr.right) }
            is ParsedParens -> recurse(expr.expr)
            is ParsedBinaryOp -> { recurse(expr.left); recurse(expr.right) }
            is ParsedIdentifier, is ParsedIntLit, is ParsedNatLit,
            is ParsedFloatLit, is ParsedFracLit,
            is ParsedCharLit, is ParsedPipePlaceholder,
            is ParsedEffectCall -> {}
        }
    }
}
