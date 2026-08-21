package com.mezze.language.typechecker.constraints

import com.mezze.language.ast.*
import com.mezze.language.parsedast.*
import com.mezze.language.typechecker.abilities.BuiltInAbilities
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.typechecker.ErrorType
import com.mezze.language.parsedast.diagnostics.ErrorCode
import com.mezze.language.typechecker.util.receiverTypeName as resolveReceiverTypeName

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
            // #160: the receiver's concrete type isn't known yet (e.g.
            // `help {a=5,b=6}.abs {}` where help is polymorphic and the args pin
            // it to Int only during solving). Two sub-cases:
            //
            // (a) HOMOGENEOUS family — every candidate has the SAME non-`it`
            //     params AND the SAME return type (e.g. `to_str`: all `{it} ->
            //     Str`). The call typechecks IDENTICALLY whichever impl is picked,
            //     so we DON'T need the receiver's concrete type to type it: use a
            //     representative signature now and defer only the IMPL pick to
            //     emit (by the solved receiver). This still typechecks even if the
            //     receiver never fully resolves at the value level.
            //
            // (b) NON-HOMOGENEOUS family — the return varies by receiver (e.g.
            //     `abs`: Int->Int, Float->Float, Frac->Frac). There is no
            //     representative return type, so we MUST know the receiver first.
            //     Emit a DEFERRED constraint (InherentMethodResolve): the solver
            //     waits until the receiver is pinned, then picks the candidate and
            //     unifies the call's args/return against that impl's signature.
            //
            // Both only fire when the receiver PRODUCES a value with a solvable
            // type — a call `f {}.m`, a parens `(e).m`, or a monomorphic local. A
            // bare GENERALIZED param (`{x} -> x.m {}`) is genuinely polymorphic
            // with no runtime impl to dispatch to; it falls through to null (a
            // structural field-access) — receiverHasSolvableType excludes it.
            if (receiverHasSolvableType(memberAccess.receiver, context) && concrete.size >= 2) {
                val homogeneous = homogeneousCandidateGroup(concrete)
                if (homogeneous.size >= 2) {
                    return generateInherentMethodCall(
                        expr, memberAccess, homogeneous, context, deferImplByReceiver = true
                    )
                }
                return generateDeferredInherentMethodCall(expr, memberAccess, concrete, context)
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
                resolveReceiverTypeName(concreteReceiver) != null ->
                    setOf(resolveReceiverTypeName(concreteReceiver)!!)
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

    // Generated bottom-up first because method/ability dispatch below resolves on
    // the argument's field names. Once strippedMethodType is known we re-generate
    // with its param type pushed down (bidirectional) — but only when it changes
    // anything (a lambda argument field). See pushParamTypeIntoArgs.
    var argumentResult = generate(expr.arguments, context.clearExpectedType().notInTailPosition())

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
                    // Drop the ability receiver param `it` before comparing: a call
                    // site `x.m {a=…}` desugars to args `{a}` (no `it`), while the
                    // ability op's param record still carries `it`. Without this,
                    // an ability whose real method type is seeded into the context
                    // (e.g. the built-in NumAdd, so `+` can reach the class-param
                    // dispatch path) is spuriously filtered out and falls back to
                    // the single-var flattened path. Mirrors the `- Keywords.IT`
                    // shape-match used for inherent-method preference below.
                    is MezzeType.RecordType -> pt.fields.keys - Keywords.IT
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
                        classArgTypeVars = abilityTypeVars,
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

    // Bidirectional: now that the method type is resolved, push its parameter
    // type into a lambda argument so its params/return infer top-down. No-op for
    // non-lambda args. The ability/associated-type constraints were already
    // collected from the first pass above, so re-generation only refines the
    // value constraints used below.
    argumentResult = pushParamTypeIntoArgs(expr.arguments, argumentResult, strippedMethodType.paramType, context)

    val resultType = MezzeType.VarType(TypeVar.fresh())
    val constraints = mutableListOf<Constraint>()
    constraints.addAll(receiverResult.constraints)
    constraints.addAll(argumentResult.constraints)

    val receiverType: MezzeType
    val extraEffects: Set<String>
    val extraEffectInstances: Set<MezzeType.EffectInstance>
    when (val rt = receiverResult.type) {
        is MezzeType.SuspendType -> {
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

/**
 * #160: true when a receiver whose static type is a tyvar will nonetheless be
 * pinned to a concrete type by SOLVING — so deferring inherent-method dispatch
 * to emit time is safe. That's the case for:
 *   - a call `f {..}` or a parenthesized `(e)` — produces a value whose type
 *     the surrounding constraints determine (e.g. a poly fn's literal args), and
 *   - a bare identifier bound MONOMORPHICALLY to a LOCAL (`let x = help {..}` —
 *     its fresh tyvar gets unified during solving).
 * It is NOT safe for a LAMBDA PARAM (a genuinely-polymorphic-in-body value like
 * `{x} -> x.abs {}`): `x` is a free type variable that NOTHING in the body pins,
 * so there is no runtime impl to dispatch to. Emitting a deferred constraint
 * there would silently vanish (the receiver never resolves) and wrongly accept
 * the definition; instead we fall through to structural field-access, which
 * keeps the call-site error (#216). Lambda params are exactly the names in the
 * enclosing scope's `currentScopeBindings` (seeded by withFreshScope), so we
 * exclude them here.
 */
internal fun ExpressionConstraintGenerator.receiverHasSolvableType(receiver: ParsedExpr, context: ConstraintContext): Boolean {
    return when (receiver) {
        // A call `f {..}`, a parenthesized `(e)`, or a field access `r.f` all
        // produce a value whose type is pinned by solving (the field access via
        // its record's field type), so deferring inherent-method dispatch to
        // emit time is safe. Without ParsedMemberAccess here, `point.x.neg {}`
        // fell through to a structural field-access constraint → T0205.
        is ParsedFunctionCall, is ParsedParens, is ParsedMemberAccess -> true
        is ParsedIdentifier -> {
            if (receiver.qualifier != null) return false
            // A lambda param is monomorphic-but-unpinnable; not solvable.
            if (receiver.localName in context.currentScopeBindings) return false
            val lookup = context.typingContext.lookup(receiver.localName)
            (lookup as? TypingContext.LookupResult.Found)?.binding is TypingContext.Binding.Monomorphic
        }
        else -> false
    }
}

/**
 * #160: the LARGEST subset of inherent-method candidates that differ ONLY in
 * their `it` (receiver) slot — same non-`it` params AND same return type. A call
 * over such a group typechecks IDENTICALLY whichever impl is picked, so it can be
 * typed now (from a representative) with only the impl pick deferred to emit —
 * WITHOUT needing the receiver's concrete type. That's what lets `to_str` work on
 * a receiver whose value type never fully resolves (e.g. a plain generalized
 * callee's result), where the receiver-dependent `abs` cannot.
 *
 * We take the largest homogeneous group rather than requiring ALL candidates to
 * match because real method families aren't perfectly uniform — e.g. `to_str` is
 * `{it} -> Str` for Int/Float/Bool/… but `{it} -> Result Str Str` for Bytes. A
 * receiver that solves to Bytes simply won't be in this group (it dispatches via
 * the deferred path instead). Returns emptyList when no group of ≥2 qualifies.
 */
internal fun ExpressionConstraintGenerator.homogeneousCandidateGroup(
    candidates: List<TypingContext.InherentMethodInfo>
): List<TypingContext.InherentMethodInfo> {
    fun sig(mi: TypingContext.InherentMethodInfo): Pair<Map<String, MezzeType>, MezzeType>? {
        val fn = mi.methodType as? MezzeType.FunctionType ?: return null
        val params = (fn.paramType as? MezzeType.RecordType)?.fields ?: return null
        if (!params.containsKey(Keywords.IT)) return null   // must be a receiver method
        return params.filterKeys { it != Keywords.IT } to fn.returnType
    }
    return candidates
        .mapNotNull { mi -> sig(mi)?.let { it to mi } }
        .groupBy({ it.first }, { it.second })
        .values
        .maxByOrNull { it.size }
        ?.takeIf { it.size >= 2 }
        ?: emptyList()
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
        // `(expr).method` — a parenthesized receiver is type-transparent, so
        // look through it. Without this `(5).to_str {}` and `(f {}).to_str {}`
        // reported the receiver as "unresolved" and fell through to a HasField
        // constraint on the concrete type → T0205 (#194 follow-up).
        is ParsedParens -> return getReceiverConcreteType(receiver.expr, context)
        // `f { … }.method` — the receiver is a call whose result type is
        // concrete even though the syntax isn't a literal/identifier. Resolve
        // the callee's return type so e.g. `help { a = 5, b = 6 }.to_str {}`
        // (help : … -> Int) dispatches on Int.
        is ParsedFunctionCall -> {
            val ret = calleeReturnType(receiver, context)
            return if (ret == null || ret is MezzeType.VarType) null else ret
        }
        // `r.f.method` — the receiver is a field access. When `r`'s concrete
        // type is a record with field `f`, the receiver's type is that field's
        // type. Resolves e.g. `point.x.neg {}` (point : {x: Int}) on Int rather
        // than deferring on an unpinnable var. Struct-typed receivers only; a
        // field whose type is itself a var stays unresolved (falls through).
        is ParsedMemberAccess -> {
            val recvType = getReceiverConcreteType(receiver.receiver, context)
            val fieldType = (recvType as? MezzeType.RecordType)?.fields?.get(receiver.member)
            return if (fieldType == null || fieldType is MezzeType.VarType) null else fieldType
        }
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

/**
 * Best-effort static lookup of a function call's return type — the callee's
 * FunctionType.returnType, when the callee is a simple in-scope identifier.
 * Returns null for indirect/computed callees (the receiver then stays
 * "unresolved", preserving the conservative default). Mirrors the callee
 * resolution in [lookupConstructorTypeName].
 */
private fun ExpressionConstraintGenerator.calleeReturnType(
    call: ParsedFunctionCall,
    context: ConstraintContext
): MezzeType? {
    val fn = call.function as? ParsedIdentifier ?: return null
    val key = fn.qualifier?.toString()?.let { BindingKey(it, fn.localName) }
        ?: BindingKey.unqualified(fn.localName)
    val lookup = context.typingContext.lookup(key)
    if (lookup !is TypingContext.LookupResult.Found) return null
    val fnType = when (val b = lookup.binding) {
        is TypingContext.Binding.Monomorphic -> b.type
        is TypingContext.Binding.Polymorphic -> b.scheme.instantiate().type
    }
    return (fnType as? MezzeType.FunctionType)?.returnType
}

internal fun ExpressionConstraintGenerator.generateInherentMethodCall(
    expr: ParsedFunctionCall,
    memberAccess: ParsedMemberAccess,
    inherentMethods: List<TypingContext.InherentMethodInfo>,
    context: ConstraintContext,
    // #160: HOMOGENEOUS-family deferral. When true, the candidates all share a
    // signature apart from their `it` slot, so any is a valid representative for
    // typing the call — but the receiver's concrete type isn't known yet, so
    // don't pin the receiver to the representative's `it`. Record every candidate
    // (candidateImpls) so emit picks the impl by the SOLVED receiver type.
    deferImplByReceiver: Boolean = false,
): ConstraintResult {
    val methodName = memberAccess.member

    val receiverResult = generate(memberAccess.receiver, context.notInTailPosition())
    // Generated bottom-up first; re-generated with the resolved param type pushed
    // down once the inherent method is picked (bidirectional, lambda args only).
    var argumentResult = generate(expr.arguments, context.clearExpectedType().notInTailPosition())

    val receiverType: MezzeType
    val extraEffects: Set<String>
    val extraEffectInstances: Set<MezzeType.EffectInstance>
    when (val rt = receiverResult.type) {
        is MezzeType.SuspendType -> {
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

    val receiverTypeName = resolveReceiverTypeName(receiverType)
        ?: lookupConstructorTypeName(memberAccess.receiver, context)
    // Prefer the resolved receiver's CONTENT HASH to disambiguate two same-named
    // inherent-method impls from different modules (name-only matching would pick
    // by load order → silent mis-dispatch). Falls back to name when the receiver
    // has no type hash (builtins). See project_classparam_runtime_dispatch_tuple.
    val receiverTypeHash = (receiverType as? MezzeType.VariantType)?.typeHash
        ?: (receiverType as? MezzeType.TypeApplication)?.typeHash
    val methodInfo = if (deferImplByReceiver) {
        // Homogeneous candidates — any is a valid signature representative; the
        // concrete impl is picked from candidateImpls at emit time.
        inherentMethods.first()
    } else if (inherentMethods.size == 1) {
        inherentMethods.first()
    } else if (receiverTypeHash != null && inherentMethods.any { it.targetTypeHash == receiverTypeHash }) {
        inherentMethods.first { it.targetTypeHash == receiverTypeHash }
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

            // Bidirectional push now that the param type is known (lambda args only).
            argumentResult = pushParamTypeIntoArgs(expr.arguments, argumentResult, strippedParamType, context)

            // When deferring impl selection (#160 homogeneous), do NOT pin the
            // receiver to the representative candidate's `it` type — that would
            // force e.g. Bool. The receiver's real type is fixed by OTHER
            // constraints, and emit picks the impl accordingly.
            if (methodInfo.isImported && !deferImplByReceiver) {
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

    // Added here (not before resolution) so it reflects any bidirectional
    // re-generation of argumentResult above.
    constraints.addAll(argumentResult.constraints)

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
            typeArgs = if (inherentItType != null) listOf(inherentItType) else emptyList(),
            // #160: when the impl couldn't be picked statically (homogeneous
            // deferral), carry every candidate so emit resolves by the solved
            // receiver type.
            candidateImpls = if (deferImplByReceiver) {
                inherentMethods.map {
                    ConstraintResult.InherentImplCandidate(it.targetTypeName, it.implContentHash)
                }
            } else emptyList(),
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

/**
 * #160: emit a DEFERRED inherent-method call. Used when the receiver's concrete
 * type is only known after solving AND the candidates DON'T share a signature
 * (so no representative return type exists — e.g. `abs`). We generate a fresh
 * `resultType` tyvar for the call and an [Constraint.InherentMethodResolve] that
 * the solver resolves once the receiver tyvar is pinned: it picks the candidate
 * whose target matches the solved receiver, then unifies the call's args against
 * that impl's non-`it` params and `resultType` against its return type.
 *
 * The impl hash (for emit) is carried in [ConstraintResult.MethodCallInfo.candidateImpls];
 * TypedAstBuilder re-picks it by the solved receiver type (same path as the
 * homogeneous case). `methodFunctionType` is a synthetic `args -> resultType`
 * function so the emitted method ref gets a proper (post-solve concrete)
 * FunctionType and the call's return type derives from `resultType`.
 */
internal fun ExpressionConstraintGenerator.generateDeferredInherentMethodCall(
    expr: ParsedFunctionCall,
    memberAccess: ParsedMemberAccess,
    candidates: List<TypingContext.InherentMethodInfo>,
    context: ConstraintContext,
): ConstraintResult {
    val methodName = memberAccess.member

    val receiverResult = generate(memberAccess.receiver, context.notInTailPosition())
    val argumentResult = generate(expr.arguments, context.clearExpectedType().notInTailPosition())

    val receiverType: MezzeType
    val extraEffects: Set<String>
    val extraEffectInstances: Set<MezzeType.EffectInstance>
    when (val rt = receiverResult.type) {
        is MezzeType.SuspendType -> {
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

    // Fresh-instantiate imported method types so their type params don't clash
    // across candidates; local ones are used as-is (mirrors generateInherentMethodCall).
    val resolvedCandidates = candidates.map { mi ->
        val fnType = if (mi.isImported) instantiateTypeWithFreshVars(mi.methodType) else mi.methodType
        mi to fnType
    }

    val resultType = MezzeType.VarType(TypeVar.fresh())

    val constraints = mutableListOf<Constraint>()
    constraints.addAll(receiverResult.constraints)
    constraints.addAll(argumentResult.constraints)

    constraints.add(
        Constraint.InherentMethodResolve(
            receiverType = receiverType,
            methodName = methodName,
            candidates = resolvedCandidates.map { (mi, fnType) ->
                Constraint.InherentMethodCandidate(
                    targetTypeName = mi.targetTypeName,
                    methodType = fnType,
                    implContentHash = mi.implContentHash,
                )
            },
            argsType = argumentResult.type,
            resultType = resultType,
            receiverLoc = memberAccess.receiver.loc,
            context = "Deferred inherent method: $methodName",
            loc = expr.loc,
        )
    )

    // Effects the call site must account for regardless of which impl is picked:
    // the union of every candidate's declared effects (empty for abs/to_str).
    val candidateEffects: Set<String> = resolvedCandidates
        .mapNotNull { (_, fnType) -> (fnType as? MezzeType.FunctionType)?.effects?.effects }
        .flatten()
        .toSet()
    val (callType, methodDeclaredEffects, methodDeclaredEffectInstances) =
        wrapCallResultWithEffects(
            resultType,
            if (candidateEffects.isEmpty()) null
            else MezzeType.EffectRow(effects = candidateEffects, effectInstances = emptySet(), rowVar = null)
        )

    // Synthetic FunctionType so the emitted method ref has a proper (post-solve
    // concrete) function type; return type derives from `resultType`.
    val syntheticFnType = MezzeType.FunctionType(
        paramType = argumentResult.type,
        returnType = resultType,
        effects = MezzeType.EffectRow(effects = candidateEffects, effectInstances = emptySet(), rowVar = null)
    )

    val methodCallInfo = ConstraintResult.MethodCallInfo(
        abilityName = null,
        methodName = methodName,
        methodHash = null,
        receiverFieldName = Keywords.IT,
        isInherentMethod = true,
        // fallback impl if the receiver somehow resolves to a non-candidate type
        // at emit (the solver would already have errored) — first candidate.
        implContentHash = resolvedCandidates.first().first.implContentHash,
        implStartLine = resolvedCandidates.first().first.implStartLine,
        implStartCol = resolvedCandidates.first().first.implStartCol,
        implSourceFile = resolvedCandidates.first().first.implSourceFile,
        methodFunctionType = syntheticFnType,
        candidateImpls = resolvedCandidates.map { (mi, _) ->
            ConstraintResult.InherentImplCandidate(mi.targetTypeName, mi.implContentHash)
        },
    )

    return ConstraintResult(
        type = callType,
        constraints = constraints,
        effects = receiverResult.effects + argumentResult.effects + extraEffects + methodDeclaredEffects,
        effectInstances = receiverResult.effectInstances + argumentResult.effectInstances + extraEffectInstances + methodDeclaredEffectInstances,
        abilityConstraints = receiverResult.abilityConstraints + argumentResult.abilityConstraints,
        associatedTypeConstraints = receiverResult.associatedTypeConstraints + argumentResult.associatedTypeConstraints,
        undefinedVariables = receiverResult.undefinedVariables + argumentResult.undefinedVariables,
        usedVariables = receiverResult.usedVariables + argumentResult.usedVariables,
        methodCallResolutions = receiverResult.methodCallResolutions + argumentResult.methodCallResolutions + mapOf((expr.loc to methodName) to methodCallInfo),
        callReturnTypes = receiverResult.callReturnTypes + argumentResult.callReturnTypes + (expr.loc to callType),
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
        // Suspended return (unhandled effects from callback params, e.g. Seq.map's `f`
        // may perform effects) is transparent for nominal dispatch — peel it.
        val peeled = if (ret is MezzeType.SuspendType) ret.valueType else ret
        return resolveReceiverTypeName(peeled)
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
    return resolveReceiverTypeName(resultType)
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

