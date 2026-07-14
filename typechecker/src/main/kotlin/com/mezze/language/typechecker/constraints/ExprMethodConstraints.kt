package com.mezze.language.typechecker.constraints

import com.mezze.language.ast.*
import com.mezze.language.parsedast.*
import com.mezze.language.typechecker.abilities.BuiltInAbilities
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.typechecker.ErrorType
import com.mezze.language.parsedast.diagnostics.ErrorCode

internal fun ExpressionConstraintGenerator.stripReceiverFromMethodType(methodType: MezzeType, receiverTypeVar: MezzeType.VarType): MezzeType {
    if (methodType !is MezzeType.FunctionType) return methodType
    val params = (methodType.paramType as? MezzeType.RecordType)?.fields ?: return methodType
    val strippedParams = params.filterKeys { it != Keywords.IT }
    return MezzeType.FunctionType(
        paramType = MezzeType.RecordType(fields = strippedParams, rowVar = null),
        returnType = methodType.returnType,
        effects = methodType.effects
    )
}

internal fun ExpressionConstraintGenerator.tryGenerateMethodCall(
    expr: ParsedFunctionCall,
    memberAccess: ParsedMemberAccess,
    context: ConstraintContext
): ConstraintResult? {
    val methodName = memberAccess.member

    if (memberAccess.receiver is ParsedIdentifier) {
        val receiverVarName = (memberAccess.receiver as ParsedIdentifier).localName
        val lookupResult = context.typingContext.lookup(receiverVarName)
        if (lookupResult is TypingContext.LookupResult.Found) {
            val receiverType = when (val binding = lookupResult.binding) {
                is TypingContext.Binding.Monomorphic -> binding.type
                is TypingContext.Binding.Polymorphic -> binding.scheme.instantiate().type
            }
            if (receiverType is MezzeType.RecordType && receiverType.fields.containsKey(methodName)) {
                return null
            }
        }
    }

    val matchingAbilities = context.typingContext.findAbilitiesByMethodName(methodName)

    val builtInMatches = BuiltInAbilities.getAllAbilityNames()
        .filter { BuiltInAbilities.getAbilityMethodType(it, methodName, MezzeType.VarType(TypeVar.fresh())) != null }

    val inherentMethods = context.typingContext.findAnonymousMethodsByName(methodName)

    if (matchingAbilities.isEmpty() && builtInMatches.isEmpty() && inherentMethods.isEmpty()) {
        return null
    }

    if (matchingAbilities.isEmpty() && builtInMatches.isEmpty() && inherentMethods.isNotEmpty()) {
        // Closed-record method-call inference (issue #216): when there are
        // multiple candidate inherent methods AND the receiver's type is
        // still a free TypeVar, DON'T speculatively pick by load order
        // (the previous behaviour silently mis-dispatched, e.g. picking
        // Str.length over Seq.length for `{ xs } -> xs.length {}`).
        // Returning null here falls through to plain field-access
        // constraint generation, which infers `xs.length` as access into
        // a closed record `{ length: arg -> 'r }`.
        if (inherentMethods.size > 1 && isReceiverUnresolved(memberAccess.receiver, context)) {
            val syntacticTypeName = lookupConstructorTypeName(memberAccess.receiver, context)
            if (syntacticTypeName != null) {
                val filtered = inherentMethods.filter { it.targetTypeName == syntacticTypeName }
                if (filtered.isNotEmpty()) {
                    return generateInherentMethodCall(expr, memberAccess, filtered, context)
                }
            }
            // #184: when one candidate is concrete (its `it` slot has no
            // free TyVars) and the others are generic (e.g.
            // `impl Result 'a 'e where { is_ok }`), prefer the concrete
            // one. The user almost certainly meant the impl that targets
            // a specific type, not the polymorphic catch-all on the
            // outer variant. If the receiver later turns out NOT to be
            // that concrete type, unification will fail loudly — better
            // than silently dispatching to a generic impl that always
            // type-checks.
            val concrete = inherentMethods.filter { mi ->
                val fnType = mi.methodType as? MezzeType.FunctionType
                val itType = (fnType?.paramType as? MezzeType.RecordType)?.fields?.get(Keywords.IT)
                itType != null && getFreeTypeVariables(itType).isEmpty()
            }
            if (concrete.size == 1) {
                return generateInherentMethodCall(expr, memberAccess, concrete, context)
            }
            return null
        }
        // #188: when the receiver IS concrete but anonymous (a record literal
        // or unannotated let-binding to one), filter by structurally-matching
        // type aliases in scope. Without this, multiple inherent candidates
        // (e.g. Point.sum vs Seq.sum on an anonymous `{x:Int, y:Int}`) fall
        // through to the all-candidates dispatch below, which unifies
        // against the wrong impl's it slot ("[Int] vs {x:Int, y:Int}").
        if (inherentMethods.size > 1) {
            val concreteReceiver = getReceiverConcreteType(memberAccess.receiver, context)
            val candidateAliases: Set<String> = when {
                concreteReceiver is MezzeType.RecordType && concreteReceiver.typeName == null ->
                    findMatchingRecordTypeAliases(concreteReceiver, context).toSet()
                concreteReceiver is MezzeType.VariantType && concreteReceiver.typeName == null ->
                    findMatchingVariantTypeAliases(concreteReceiver, context).toSet()
                else -> emptySet()
            }
            if (candidateAliases.isNotEmpty()) {
                val filtered = inherentMethods.filter { it.targetTypeName in candidateAliases }
                if (filtered.isNotEmpty()) {
                    return generateInherentMethodCall(expr, memberAccess, filtered, context)
                }
            }
        }
        return generateInherentMethodCall(expr, memberAccess, inherentMethods, context)
    }

    // Receiver-driven preference (issue #229): the operator-desugar
    // built-in abilities (NumAdd, NumMul, …) share method names with
    // collection inherent impls (Seq.add). When the receiver's concrete
    // type has an inherent method matching the call shape, prefer it.
    // Without this, `nums.add {x=4}` (nums : [Int]) routes to NumAdd.add
    // expecting `{r}` and fires a cascading "[Int] does not implement
    // NumAdd" error.
    // operator abilities lived only in BuiltInAbilities. After #306
    // those abilities also reach the regular ability registry. The
    // guard now also fires on user-defined abilities — but only if
    // the inherent's parameter shape matches the call-site arguments.
    // Otherwise (e.g. `[1,2,3].set {i=0, v=42}` against a user ability
    // `Mutable.set` whose param is {it, i, v}), the user's intent is
    // the ability impl, not the unrelated Seq.set inherent.
    if (inherentMethods.isNotEmpty() &&
        (builtInMatches.isNotEmpty() || matchingAbilities.isNotEmpty())) {
        val concreteReceiver = getReceiverConcreteType(memberAccess.receiver, context)
        if (concreteReceiver != null) {
            // #188: when the receiver is an anonymous record/variant with
            // no typeName, fall back to a structural lookup against the
            // in-scope type aliases. Without this, `let p = { x = 3, y =
            // 4 }; p.method {}` can't disambiguate impl Point vs other
            // same-name inherent impls (Seq.sum, etc.) — receiverTypeName
            // is null, the matching filter drops to empty, and the
            // wrong impl wins.
            val receiverTypeNames: Set<String> = when {
                getReceiverTypeName(concreteReceiver) != null ->
                    setOf(getReceiverTypeName(concreteReceiver)!!)
                concreteReceiver is MezzeType.RecordType && concreteReceiver.typeName == null ->
                    findMatchingRecordTypeAliases(concreteReceiver, context).toSet()
                concreteReceiver is MezzeType.VariantType && concreteReceiver.typeName == null ->
                    findMatchingVariantTypeAliases(concreteReceiver, context).toSet()
                else -> emptySet()
            }
            val matchingInherent = inherentMethods.filter { it.targetTypeName in receiverTypeNames }
            val localCallSiteFields: Set<String>? = when (val args = expr.arguments) {
                is ParsedRecordLit -> args.entries.filterIsInstance<ParsedRecordEntry.Field>().map { it.name }.toSet()
                else -> null
            }
            val shapeMatched = if (localCallSiteFields != null) {
                matchingInherent.filter { info ->
                    val pf = (info.methodType as? MezzeType.FunctionType)
                        ?.paramType
                        ?.let { it as? MezzeType.RecordType }
                        ?.fields?.keys ?: return@filter true
                    pf == localCallSiteFields || (pf - Keywords.IT) == localCallSiteFields
                }
            } else matchingInherent
            if (shapeMatched.isNotEmpty()) {
                return generateInherentMethodCall(expr, memberAccess, shapeMatched, context)
            }
        }
    }

    val receiverResult = generate(memberAccess.receiver, context.notInTailPosition())

    val argumentResult = generate(expr.arguments, context.clearExpectedType().notInTailPosition())

    val strippedMethodType: MezzeType.FunctionType
    val receiverConstraintType: MezzeType
    var resolvedAbilityName: String? = null
    val abilityConstraints = mutableListOf<Constraint.AbilityRequired>()
    val associatedTypeConstraints = mutableListOf<Constraint.AssociatedTypeResolution>()
    abilityConstraints.addAll(receiverResult.abilityConstraints)
    abilityConstraints.addAll(argumentResult.abilityConstraints)
    associatedTypeConstraints.addAll(receiverResult.associatedTypeConstraints)
    associatedTypeConstraints.addAll(argumentResult.associatedTypeConstraints)

    val callSiteFieldNames: Set<String>? = when (val args = expr.arguments) {
        is ParsedRecordLit -> args.entries.filterIsInstance<ParsedRecordEntry.Field>().map { entry -> entry.name }.toSet()
        else -> null
    }
    val fieldFilteredAbilities = if (callSiteFieldNames != null && matchingAbilities.isNotEmpty() && builtInMatches.isNotEmpty()) {
        matchingAbilities.filter { (_, rawMethodType) ->
            val methodParamFields = when (rawMethodType) {
                is MezzeType.FunctionType -> when (val pt = rawMethodType.paramType) {
                    is MezzeType.RecordType -> pt.fields.keys
                    else -> null
                }
                else -> null
            }
            methodParamFields == null || methodParamFields == callSiteFieldNames
        }
    } else {
        matchingAbilities
    }

    // (e.g. user-defined Container.size vs prelude Sized.size). #304.
    val filteredMatchingAbilities = if (
        fieldFilteredAbilities.size > 1 &&
        context.annotationAbilityNames.isNotEmpty()
    ) {
        val annotationMatches = fieldFilteredAbilities.filter { (n, _) ->
            n in context.annotationAbilityNames
        }
        if (annotationMatches.isNotEmpty()) annotationMatches else fieldFilteredAbilities
    } else {
        fieldFilteredAbilities
    }

    if (builtInMatches.size == 1 && filteredMatchingAbilities.isEmpty()) {
        val abilityName = builtInMatches.first()
        resolvedAbilityName = abilityName
        val freshTypeVar = MezzeType.VarType(TypeVar.fresh())
        val fullMethodType = BuiltInAbilities.getAbilityMethodType(abilityName, methodName, freshTypeVar)!!

        if (fullMethodType !is MezzeType.FunctionType) return null

        val fullParams = (fullMethodType.paramType as? MezzeType.RecordType)?.fields ?: emptyMap()
        val strippedParams = fullParams.filterKeys { it != Keywords.IT }
        strippedMethodType = MezzeType.FunctionType(
            paramType = MezzeType.RecordType(fields = strippedParams, rowVar = null),
            returnType = fullMethodType.returnType,
            effects = fullMethodType.effects
        )

        receiverConstraintType = freshTypeVar

        abilityConstraints.add(Constraint.AbilityRequired(
            typeVar = freshTypeVar.variable,
            abilityName = abilityName,
            abilityHash = context.abilityDeclarations[abilityName]?.contentHash,
            typeArgs = emptyList(),
            context = "Method call: $methodName",
            loc = expr.loc
        ))
    } else if (filteredMatchingAbilities.isNotEmpty()) {
        val (abilityName, rawMethodType) = filteredMatchingAbilities.first()
        resolvedAbilityName = abilityName
        val abilityDecl = context.abilityDeclarations[abilityName]
        val associatedTypeNames = abilityDecl?.associatedTypes ?: emptyList()

        val (instantiatedMethodType, originalToFreshFromMethod) = instantiateTypeWithFreshVarsPreservingAssociatedWithMapping(
            rawMethodType, associatedTypeNames
        )

        val originalToFresh = originalToFreshFromMethod.toMutableMap()
        if (abilityDecl != null) {
            for (paramName in abilityDecl.typeParams) {
                val normalizedName = paramName.removePrefix("'")
                if (normalizedName !in originalToFresh) {
                    originalToFresh[normalizedName] = TypeVar.fresh()
                }
            }
        }

        val abilityTypeVars = abilityDecl?.typeParams?.mapNotNull { paramName ->
            originalToFresh[paramName.removePrefix("'")]
        } ?: emptyList()

        val receiverParamName = abilityDecl?.receiverTypeParam?.removePrefix("'")
        val receiverFreshVar = if (receiverParamName != null) {
            originalToFresh[receiverParamName]
        } else {
            abilityTypeVars.lastOrNull()
        }

        receiverConstraintType = if (receiverFreshVar != null) {
            MezzeType.VarType(receiverFreshVar)
        } else if (abilityTypeVars.isNotEmpty()) {
            MezzeType.VarType(abilityTypeVars.last())
        } else {
            MezzeType.VarType(TypeVar.fresh())
        }

        val receiverTypeVar = if (receiverFreshVar != null) {
            MezzeType.VarType(receiverFreshVar)
        } else if (abilityTypeVars.isNotEmpty()) {
            MezzeType.VarType(abilityTypeVars.last())
        } else {
            MezzeType.VarType(TypeVar.fresh())
        }

        if (instantiatedMethodType !is MezzeType.FunctionType) return null
        val stripped = stripReceiverFromMethodType(instantiatedMethodType, receiverTypeVar)
        if (stripped !is MezzeType.FunctionType) return null
        strippedMethodType = stripped

        if (abilityTypeVars.isNotEmpty()) {
            val typeArgs = abilityTypeVars.drop(1).map { MezzeType.VarType(it) }
            abilityConstraints.add(Constraint.AbilityRequired(
                typeVar = abilityTypeVars.first(),
                abilityName = abilityName,
                abilityHash = abilityDecl?.contentHash,
                typeArgs = typeArgs,
                context = "Method call: $methodName",
                loc = expr.loc
            ))

            if (associatedTypeNames.isNotEmpty()) {
                val associatedTypeNamesNormalized = associatedTypeNames.map { it.removePrefix("'") }.toSet()
                val associatedTypeVarsMap = mutableMapOf<String, TypeVar>()
                for ((origName, freshVar) in originalToFresh) {
                    if (origName in associatedTypeNamesNormalized) {
                        associatedTypeVarsMap[origName] = freshVar
                    }
                }
                if (associatedTypeVarsMap.isNotEmpty()) {
                    associatedTypeConstraints.add(Constraint.AssociatedTypeResolution(
                        abilityName = abilityName,
                        primaryTypeVar = abilityTypeVars.first(),
                        associatedTypeVars = associatedTypeVarsMap,
                        context = "Associated type resolution for $abilityName.$methodName",
                        loc = expr.loc
                    ))
                }
            }
        }

        if (instantiatedMethodType.abilityConstraints.isNotEmpty()) {
            for (constraint in instantiatedMethodType.abilityConstraints) {
                val constrainedType = constraint.typeArgs.firstOrNull()
                if (constrainedType is MezzeType.VarType) {
                    val normalizedName = constrainedType.variable.name.removePrefix("'")
                    val freshTypeVar = originalToFresh[normalizedName] ?: constrainedType.variable
                    abilityConstraints.add(Constraint.AbilityRequired(
                        typeVar = freshTypeVar,
                        abilityName = constraint.abilityName,
                        abilityHash = context.abilityDeclarations[constraint.abilityName]?.contentHash,
                        typeArgs = constraint.typeArgs.drop(1),
                        context = "Method-level constraint: ${constraint.abilityName} on $abilityName.$methodName",
                        loc = expr.loc
                    ))
                }
            }
        }
    } else {
        return null
    }

    val resultType = MezzeType.VarType(TypeVar.fresh())
    val constraints = mutableListOf<Constraint>()
    constraints.addAll(receiverResult.constraints)
    constraints.addAll(argumentResult.constraints)

    val receiverType: MezzeType
    val extraEffects: Set<String>
    val extraEffectInstances: Set<MezzeType.EffectInstance>
    when (val rt = receiverResult.type) {
        is MezzeType.SuspendedType -> {
            receiverType = rt.valueType
            extraEffects = rt.effects.effects
            extraEffectInstances = rt.effects.effectInstances
        }
        else -> {
            receiverType = rt
            extraEffects = emptySet()
            extraEffectInstances = emptySet()
        }
    }

    constraints.add(Constraint.Equality(
        left = receiverType,
        right = receiverConstraintType,
        context = "Method call receiver binds ${Keywords.IT}",
        loc = expr.loc
    ))

    constraints.add(Constraint.Equality(
        left = argumentResult.type,
        right = strippedMethodType.paramType,
        context = "Method call arguments for $methodName",
        loc = expr.loc
    ))

    constraints.add(Constraint.Equality(
        left = resultType,
        right = strippedMethodType.returnType,
        context = "Method call return type",
        loc = expr.loc
    ))

    val (callType, methodDeclaredEffects, methodDeclaredEffectInstances) =
        wrapCallResultWithEffects(resultType, strippedMethodType.effects)

    // #218: stash the ability's instantiation TyVars into typeArgs so the
    // monomorphizer can substitute them when specializing the enclosing
    // function. Without this, an in-isolation lambda body checks `w.eq`
    // with `'v` as the Eq instantiation; the call-site solver later binds
    // `'v := Int` but mono never sees that because the typeArgs were
    // empty. The receiverConstraintType IS the ability's primary TyVar
    // (built-in path) or the receiver TyVar (user-defined path). When
    // mono runs, it walks `TypedCall.instantiation.typeArgs` and
    // substitutes each TyVar via the specialization's substitution map —
    // the body must thread the same TyVar identity through.
    val methodCallTypeArgs: List<MezzeType> = listOf(receiverConstraintType)
    val methodCallInfo = ConstraintResult.MethodCallInfo(
        abilityName = resolvedAbilityName,
        methodName = methodName,
        methodHash = null,
        receiverFieldName = Keywords.IT,
        isInherentMethod = false,
        typeArgs = methodCallTypeArgs
    )

    return ConstraintResult(
        type = callType,
        constraints = constraints,
        effects = receiverResult.effects + argumentResult.effects + extraEffects + methodDeclaredEffects,
        effectInstances = receiverResult.effectInstances + argumentResult.effectInstances + extraEffectInstances + methodDeclaredEffectInstances,
        abilityConstraints = abilityConstraints,
        associatedTypeConstraints = associatedTypeConstraints,
        undefinedVariables = receiverResult.undefinedVariables + argumentResult.undefinedVariables,
        usedVariables = receiverResult.usedVariables + argumentResult.usedVariables,
        methodCallResolutions = receiverResult.methodCallResolutions + argumentResult.methodCallResolutions + mapOf((expr.loc to methodCallInfo.methodName) to methodCallInfo),
        callReturnTypes = receiverResult.callReturnTypes + argumentResult.callReturnTypes + (expr.loc to callType)
    )
}

/**
 * Peek at a receiver expression's bound type WITHOUT generating
 * constraints. Returns true when the receiver is a plain identifier
 * bound to a free type variable in scope — i.e. the call site can't
 * yet pick an inherent impl by receiver shape, and a speculative
 * choice by load order would silently mis-dispatch (issue #216).
 *
 * Conservative by design: returns false for anything we can't read
 * statically, so concrete receivers (literals, annotated params,
 * already-typed identifiers) keep their existing inherent-dispatch
 * behaviour.
 */
internal fun ExpressionConstraintGenerator.isReceiverUnresolved(receiver: ParsedExpr, context: ConstraintContext): Boolean {
    return getReceiverConcreteType(receiver, context) == null
}

internal fun ExpressionConstraintGenerator.getReceiverConcreteType(receiver: ParsedExpr, context: ConstraintContext): MezzeType? {
    when (receiver) {
        is ParsedIntLit -> return MezzeType.IntType
        is ParsedNatLit -> return MezzeType.NatType
        is ParsedFloatLit -> return MezzeType.FloatType
        is ParsedFracLit -> return MezzeType.FracType
        is ParsedStringLit -> return MezzeType.StringType
        is ParsedCharLit -> return MezzeType.CharType
        is ParsedSeqLit ->
            return MezzeType.SeqType(MezzeType.VarType(TypeVar.fresh()))
        else -> { /* fall through */ }
    }
    if (receiver !is ParsedIdentifier) return null
    if (receiver.qualifier != null) return null
    val lookup = context.typingContext.lookup(receiver.localName)
    if (lookup !is TypingContext.LookupResult.Found) return null
    val type = when (val binding = lookup.binding) {
        is TypingContext.Binding.Monomorphic -> binding.type
        is TypingContext.Binding.Polymorphic -> binding.scheme.instantiate().type
    }
    return if (type is MezzeType.VarType) null else type
}

internal fun ExpressionConstraintGenerator.generateInherentMethodCall(
    expr: ParsedFunctionCall,
    memberAccess: ParsedMemberAccess,
    inherentMethods: List<TypingContext.InherentMethodInfo>,
    context: ConstraintContext
): ConstraintResult {
    val methodName = memberAccess.member

    val receiverResult = generate(memberAccess.receiver, context.notInTailPosition())
    val argumentResult = generate(expr.arguments, context.clearExpectedType().notInTailPosition())

    val receiverType: MezzeType
    val extraEffects: Set<String>
    val extraEffectInstances: Set<MezzeType.EffectInstance>
    when (val rt = receiverResult.type) {
        is MezzeType.SuspendedType -> {
            receiverType = rt.valueType
            extraEffects = rt.effects.effects
            extraEffectInstances = rt.effects.effectInstances
        }
        else -> {
            receiverType = rt
            extraEffects = emptySet()
            extraEffectInstances = emptySet()
        }
    }

    val resultType = MezzeType.VarType(TypeVar.fresh())
    val constraints = mutableListOf<Constraint>()
    constraints.addAll(receiverResult.constraints)
    constraints.addAll(argumentResult.constraints)

    val receiverTypeName = getReceiverTypeName(receiverType)
        ?: lookupConstructorTypeName(memberAccess.receiver, context)
    val methodInfo = if (inherentMethods.size == 1) {
        inherentMethods.first()
    } else if (receiverTypeName != null) {
        inherentMethods.firstOrNull { it.targetTypeName == receiverTypeName }
    } else {
        inherentMethods.firstOrNull()
    }

    // (after issue #213 pinned non-generic returns to e.g. `Int`) survive.
    val freshMethodType: MezzeType? = if (methodInfo != null) {
        if (methodInfo.isImported) {
            instantiateTypeWithFreshVars(methodInfo.methodType)
        } else {
            methodInfo.methodType
        }
    } else null

    if (methodInfo != null && freshMethodType != null) {
        if (freshMethodType is MezzeType.FunctionType) {
            val fullParams = (freshMethodType.paramType as? MezzeType.RecordType)?.fields ?: emptyMap()
            val strippedParams = fullParams.filterKeys { it != Keywords.IT }
            val strippedParamType = MezzeType.RecordType(fields = strippedParams, rowVar = null)

            if (methodInfo.isImported) {
                val itType = fullParams[Keywords.IT]
                if (itType != null) {
                    constraints.add(Constraint.Equality(
                        left = receiverType,
                        right = itType,
                        context = "Inherent method receiver for $methodName",
                        loc = expr.loc
                    ))
                }
            }

            constraints.add(Constraint.Equality(
                left = argumentResult.type,
                right = strippedParamType,
                context = "Inherent method call arguments for $methodName",
                loc = expr.loc
            ))

            constraints.add(Constraint.Equality(
                left = resultType,
                right = freshMethodType.returnType,
                context = "Inherent method call return type",
                loc = expr.loc
            ))
        }
    }

    // #217: stash the impl's `it`-slot TyVar (post fresh-instantiation for
    // imports) into typeArgs so mono can substitute the concrete receiver
    // type when specializing the enclosing function. Mirror the #218
    // ability-method plumbing — without it, every inherent-method call
    // inside a generic body's TypedCall.instantiation ends up empty and
    // the specializer can't enqueue this call's spec.
    val inherentItType = (freshMethodType as? MezzeType.FunctionType)
        ?.let { (it.paramType as? MezzeType.RecordType)?.fields?.get(Keywords.IT) }
    val inherentMethodCallInfo = if (methodInfo != null) {
        ConstraintResult.MethodCallInfo(
            abilityName = null,
            methodName = methodName,
            methodHash = null,
            receiverFieldName = Keywords.IT,
            isInherentMethod = true,
            implContentHash = methodInfo.implContentHash,
            implStartLine = methodInfo.implStartLine,
            implStartCol = methodInfo.implStartCol,
            implSourceFile = methodInfo.implSourceFile,
            methodFunctionType = freshMethodType,
            typeArgs = if (inherentItType != null) listOf(inherentItType) else emptyList()
        )
    } else null

    val (callType, methodDeclaredEffects, methodDeclaredEffectInstances) =
        wrapCallResultWithEffects(resultType, (freshMethodType as? MezzeType.FunctionType)?.effects)

    return ConstraintResult(
        type = callType,
        constraints = constraints,
        effects = receiverResult.effects + argumentResult.effects + extraEffects + methodDeclaredEffects,
        effectInstances = receiverResult.effectInstances + argumentResult.effectInstances + extraEffectInstances + methodDeclaredEffectInstances,
        abilityConstraints = receiverResult.abilityConstraints + argumentResult.abilityConstraints,
        associatedTypeConstraints = receiverResult.associatedTypeConstraints + argumentResult.associatedTypeConstraints,
        undefinedVariables = receiverResult.undefinedVariables + argumentResult.undefinedVariables,
        usedVariables = receiverResult.usedVariables + argumentResult.usedVariables,
        methodCallResolutions = receiverResult.methodCallResolutions + argumentResult.methodCallResolutions + (if (inherentMethodCallInfo != null) mapOf((expr.loc to inherentMethodCallInfo.methodName) to inherentMethodCallInfo) else emptyMap()),
        callReturnTypes = receiverResult.callReturnTypes + argumentResult.callReturnTypes + (expr.loc to callType)
    )
}

internal fun ExpressionConstraintGenerator.lookupConstructorTypeName(receiver: ParsedExpr, context: ConstraintContext): String? =
    lookupConstructorTypeName(receiver, context, depth = 0)

/**
 * Bounded variant. `depth` guards against `let a = b; let b = c; …` chains;
 * we walk at most a handful of hops, which is enough to cover the realistic
 * "alias one call result" pattern (#194) without quadratic blowups on
 * pathological inputs.
 */
internal fun ExpressionConstraintGenerator.lookupConstructorTypeName(
    receiver: ParsedExpr,
    context: ConstraintContext,
    depth: Int,
): String? {
    if (depth > 4) return null

    val ctorName = when (receiver) {
        is ParsedVariantConstruct -> receiver.constructor
        is ParsedFunctionCall -> (receiver.function as? ParsedVariantConstruct)?.constructor
        else -> null
    }
    if (ctorName != null) {
        when (ctorName) {
            "Ok", "Err" -> return "Result"
            "Some", "None" -> return "Option"
        }
        for ((typeName, typeDecl) in context.typeDeclarations) {
            val def = typeDecl.definition
            if (def is ParsedVariantDef && def.constructors.any { it.name == ctorName }) {
                return typeName
            }
        }
    }
    if (receiver is ParsedFunctionCall && receiver.function is ParsedMemberAccess) {
        val memberAccess = receiver.function as ParsedMemberAccess
        val innerTypeName = lookupConstructorTypeName(memberAccess.receiver, context, depth + 1) ?: return null
        val candidates = context.typingContext.findAnonymousMethodsByName(memberAccess.member)
            .filter { it.targetTypeName == innerTypeName }
        val method = candidates.singleOrNull() ?: return null
        val ret = (method.methodType as? MezzeType.FunctionType)?.returnType ?: return null
        return getReceiverTypeName(ret)
    }
    val fnExpr: ParsedExpr? = when (receiver) {
        is ParsedFunctionCall -> receiver.function
        else -> receiver
    }
    val bindingType: MezzeType? = when (fnExpr) {
        is ParsedIdentifier -> {
            val qualifierStr = fnExpr.qualifier?.toString()
            val key = if (qualifierStr != null)
                BindingKey(qualifierStr, fnExpr.localName)
            else BindingKey.unqualified(fnExpr.localName)
            val lookup = context.typingContext.lookup(key)
            if (lookup is TypingContext.LookupResult.Found) {
                when (val b = lookup.binding) {
                    is TypingContext.Binding.Monomorphic -> b.type
                    is TypingContext.Binding.Polymorphic -> b.scheme.type
                }
            } else null
        }
        else -> null
    }
    val resultType = when {
        bindingType is MezzeType.FunctionType && receiver is ParsedFunctionCall -> bindingType.returnType
        bindingType != null && receiver !is ParsedFunctionCall -> bindingType
        else -> null
    } ?: return null
    return getReceiverTypeName(resultType)
}

/**
 * #188: anonymous record → in-scope type aliases that share the field set.
 * Mirror of `TypeValidator.findMatchingRecordTypeAliases` so dispatch
 * preference can promote unannotated record literals to their nominal
 * alias when picking impl candidates.
 */
internal fun ExpressionConstraintGenerator.findMatchingRecordTypeAliases(
    recordType: MezzeType.RecordType,
    context: ConstraintContext
): List<String> {
    val matches = mutableListOf<String>()
    for ((aliasName, typeDecl) in context.typeDeclarations) {
        val def = typeDecl.definition
        if (def is ParsedRecordDef) {
            val aliasFieldNames = def.fields.map { it.name }.toSet()
            if (aliasFieldNames == recordType.fields.keys) {
                matches.add(aliasName)
            }
        }
    }
    return matches
}

/**
 * #188: anonymous variant → in-scope variant aliases whose constructor
 * names overlap. Loosely matches the validator's variant-alias lookup.
 */
internal fun ExpressionConstraintGenerator.findMatchingVariantTypeAliases(
    variantType: MezzeType.VariantType,
    context: ConstraintContext
): List<String> {
    val matches = mutableListOf<String>()
    for ((aliasName, typeDecl) in context.typeDeclarations) {
        val def = typeDecl.definition
        if (def is ParsedVariantDef) {
            val aliasCtorNames = def.constructors.map { it.name }.toSet()
            if (aliasCtorNames.containsAll(variantType.constructors.keys)) {
                matches.add(aliasName)
            }
        }
    }
    return matches
}

internal fun ExpressionConstraintGenerator.getReceiverTypeName(type: MezzeType): String? = when (type) {
    is MezzeType.IntType -> "Int"
    is MezzeType.FloatType -> "Float"
    is MezzeType.StringType -> "Str"
    is MezzeType.BoolType -> "Bool"
    is MezzeType.NatType -> "Nat"
    is MezzeType.FracType -> "Frac"
    is MezzeType.CharType -> "Char"
    is MezzeType.BytesType -> "Bytes"
    is MezzeType.OrderingType -> "Ordering"
    is MezzeType.SeqType -> "Seq"
    is MezzeType.ListType -> "List"
    is MezzeType.SetType -> "Set"
    is MezzeType.MapType -> "Map"
    is MezzeType.TypeApplication -> type.constructor
    is MezzeType.RecordType -> type.typeName
    is MezzeType.VariantType -> type.typeName
        ?: if (type.constructors.size == 1) type.constructors.keys.first() else null
    else -> null
}

