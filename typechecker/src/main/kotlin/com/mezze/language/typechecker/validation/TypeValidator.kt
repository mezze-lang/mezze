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
import com.mezze.language.typechecker.mono.unwrapSuspended
import com.mezze.language.typechecker.util.StringDistance
import com.mezze.language.typechecker.util.TypeAnnotationConverter
import com.mezze.language.typechecker.util.TypeAnnotationMatcher
import com.mezze.language.typechecker.WellKnownHashConstants
import com.mezze.language.typechecker.context.DeclarationRegistry
import com.mezze.language.typedast.Hash

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
    private val codecGenerator = CodecGenerator()

    private val typeNameCache = mutableMapOf<Int, String>()
    fun validate(
        module: ParsedModule,
        constraintResults: Map<String, ConstraintResult>,
        substitution: Substitution
    ): ValidationResult {
        val errors = mutableListOf<ValidationError>()
        val warnings = mutableListOf<String>()

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
                    val scrutineeType = if (decl.isSimpleBinding) {
                        constraintResults[decl.variableName]?.type?.let { substitution.apply(it) }
                    } else null
                    if (scrutineeType != null) errors.addAll(validateLetPattern(decl.pattern, scrutineeType))
                    errors.addAll(validatePatternsInExpression(decl.value, substitution))
                }
                is ParsedTopLevelExpr -> errors.addAll(validatePatternsInExpression(decl.expression, substitution))
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
            val expr = when (decl) {
                is ParsedLetDecl -> decl.value
                is ParsedTopLevelExpr -> decl.expression
                else -> null
            } ?: continue
            errors.addAll(performValidator.validatePerformExpressionsInExpression(expr, implDeclarations, effectDeclarations, parsedFunctionDefs))
            errors.addAll(matchValidator.validateParsedMatchesInExpr(expr, substitution, constraintResults))
        }

        return if (errors.isEmpty()) {
            ValidationResult.Success(warnings)
        } else {
            ValidationResult.Failure(errors)
        }
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
                                    constructors = definition.constructors.associate { it.name to it.dataType?.let { dt -> TypeAnnotationConverter.convert(dt) } },
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
                is Constraint.InherentMethodResolve -> {}
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
            val concreteType = when (val applied = substitution.apply(MezzeType.VarType(constraint.typeVar))) {
                is MezzeType.SuspendType -> applied.valueType
                else -> applied
            }
            if (concreteType is MezzeType.VarType) continue

            val typeName = getTypeNameForAbility(concreteType)
            if (typeName.startsWith("'") || typeName == "Unknown") continue

            val abilityName = constraint.abilityName
            val effectiveHash = constraint.abilityHash ?: WellKnownHashConstants.resolveAbilityHash(abilityName)

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
            val concreteTypes = when {
                resolvedTypeArgs.any { it == concreteType } -> resolvedTypeArgs
                resolvedTypeArgs.isEmpty() -> listOf(concreteType)
                else -> listOf(concreteType) + resolvedTypeArgs
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
            concreteType is MezzeType.RecordType && concreteType.typeName == null -> findMatchingRecordTypeAliases(concreteType)
            concreteType is MezzeType.VariantType && concreteType.typeName == null -> findMatchingVariantTypeAliases(concreteType)
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
                val aliasFieldType = try { TypeAnnotationConverter.convert(parsedType) } catch (_: Exception) { null }
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

    private fun canDeriveAbility(abilityName: String, type: MezzeType, abilityHash: Hash? = null): Boolean {
        var unwrappedType = unwrapSuspended(type)
        (unwrappedType as? MezzeType.TypeApplication)?.let { expandTypeApplicationToVariant(it, registry)?.let { e -> unwrappedType = e } }

        return when {
            WellKnownHashConstants.isInspect(abilityHash) -> true

            WellKnownHashConstants.isEq(abilityHash) -> {
                unwrappedType is MezzeType.VarType ||
                BuiltInAbilities.isBoolVariant(unwrappedType) ||
                (unwrappedType is MezzeType.RecordType && unwrappedType.fields.values.all { canDeriveOrHasImpl("Eq", it, abilityHash) }) ||
                (unwrappedType is MezzeType.VariantType && unwrappedType.constructors.values.all { it == null || canDeriveOrHasImpl("Eq", it, abilityHash) }) ||
                (unwrappedType is MezzeType.ListType && canDeriveOrHasImpl("Eq", unwrappedType.elementType, abilityHash)) ||
                (unwrappedType is MezzeType.SeqType && canDeriveOrHasImpl("Eq", unwrappedType.elementType, abilityHash))
            }

            WellKnownHashConstants.isOrd(abilityHash) -> {
                unwrappedType is MezzeType.VarType ||
                (unwrappedType is MezzeType.RecordType && unwrappedType.fields.values.all { canDeriveOrHasImpl("Ord", it, abilityHash) }) ||
                (unwrappedType is MezzeType.VariantType && unwrappedType.constructors.values.all { it == null || canDeriveOrHasImpl("Ord", it, abilityHash) }) ||
                (unwrappedType is MezzeType.ListType && canDeriveOrHasImpl("Ord", unwrappedType.elementType, abilityHash)) ||
                (unwrappedType is MezzeType.SeqType && canDeriveOrHasImpl("Ord", unwrappedType.elementType, abilityHash))
            }

            abilityHash == WellKnownHashConstants.ABILITY_LOGICALAND ||
            abilityHash == WellKnownHashConstants.ABILITY_LOGICALOR ||
            abilityHash == WellKnownHashConstants.ABILITY_LOGICALNOT -> {
                unwrappedType is MezzeType.BoolType || BuiltInAbilities.isBoolVariant(unwrappedType)
            }

            else -> false
        }
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
            is MezzeType.VariantType -> type.typeName ?: when {
                type.constructors.keys == setOf("True", "False") -> "Bool"
                type.constructors.size == 1 -> type.constructors.keys.first()
                else -> ContentHashComputer.mezzeTypeToString(type)
            }
            is MezzeType.RecordType -> type.typeName ?: ContentHashComputer.mezzeTypeToString(type)
            is MezzeType.SuspendType -> getTypeNameForAbility(type.valueType)
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
        val annotatedType = try { TypeAnnotationConverter.convert(parsedAnnotation) } catch (_: Exception) { return emptyList() }
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
        val finalType = substitution.apply(constraintResults[decl.variableName]?.type ?: return emptyList())
        if (finalType !is MezzeType.SuspendType) return emptyList()

        // A top-level `let` bound to a SuspendType is always suspect: the RHS
        // is a lazy computation with pending effects. The question is whether we
        // allow it as an inspectable declarative value or reject it as a program-
        // level mistake (an effectful call that will never fire without a handler).
        //
        // Under strict mode (mez build / mez run — real program entry paths), any
        // top-level SuspendType is rejected. Fully-discharged (performed + empty
        // row) would execute at module load. Partially-discharged (residual row)
        // is a stranded effectful expression the entry point can't use.
        //
        // Under permissive mode (mez test, REPL), we keep only the historical
        // "would execute at module load" guard so tests and REPL sessions can
        // introspect intermediate suspended values.
        val rowIsEmpty = finalType.effects.effects.isEmpty() &&
            finalType.effects.effectInstances.isEmpty() &&
            finalType.effects.rowVar == null
        val performedAndEmpty = finalType.effects.performed && rowIsEmpty
        val shouldReject = if (strictTopLevel) true else performedAndEmpty
        if (shouldReject) {
            return listOf(ValidationError.TopLevelPerformedEffects(
                bindingName = decl.variableName, effectRow = finalType.effects, loc = decl.loc
            ))
        }
        return emptyList()
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
        type1 is MezzeType.VariantType && type2 is MezzeType.VariantType ->
            type1.constructors.keys == type2.constructors.keys &&
            type1.constructors.all { (k, v) ->
                val v2 = type2.constructors[k]
                (v == null && v2 == null) || (v != null && v2 != null && alphaEquivalent(v, v2, mapping))
            }
        type1 is MezzeType.TypeApplication && type2 is MezzeType.TypeApplication ->
            type1.constructor == type2.constructor && type1.args.size == type2.args.size &&
            type1.args.zip(type2.args).all { (a, b) -> alphaEquivalent(a, b, mapping) }
        type1 is MezzeType.SeqType && type2 is MezzeType.SeqType -> alphaEquivalent(type1.elementType, type2.elementType, mapping)
        type1 is MezzeType.ListType && type2 is MezzeType.ListType -> alphaEquivalent(type1.elementType, type2.elementType, mapping)
        type1 is MezzeType.SetType && type2 is MezzeType.SetType -> alphaEquivalent(type1.elementType, type2.elementType, mapping)
        type1 is MezzeType.SortedMapType && type2 is MezzeType.SortedMapType ->
            alphaEquivalent(type1.keyType, type2.keyType, mapping) && alphaEquivalent(type1.valueType, type2.valueType, mapping)
        type1 is MezzeType.SortedSetType && type2 is MezzeType.SortedSetType -> alphaEquivalent(type1.elementType, type2.elementType, mapping)
        type1 is MezzeType.MapType && type2 is MezzeType.MapType ->
            alphaEquivalent(type1.keyType, type2.keyType, mapping) && alphaEquivalent(type1.valueType, type2.valueType, mapping)
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

    private fun abilityNamesMatch(
        c1: List<MezzeType.FunctionAbilityConstraint>,
        c2: List<MezzeType.FunctionAbilityConstraint>
    ): Boolean = c1.mapNotNullTo(mutableSetOf()) { it.abilityName.takeIf { n -> n != "Inspect" } } ==
                 c2.mapNotNullTo(mutableSetOf()) { it.abilityName.takeIf { n -> n != "Inspect" } }

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
            is ParsedUnaryOp -> recurse(expr.operand)
            is ParsedIdentifier, is ParsedIntLit, is ParsedNatLit,
            is ParsedFloatLit, is ParsedFracLit,
            is ParsedCharLit, is ParsedPipePlaceholder,
            is ParsedEffectCall -> {}
        }
    }
}
