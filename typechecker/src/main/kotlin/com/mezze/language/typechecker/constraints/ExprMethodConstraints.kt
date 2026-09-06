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

    // S2.1 (#356): record a candidate BUCKET for this call site — the foundation
    // for Stage 2's single post-solve dispatch. This RECORDS the candidate set +
    // the (best-effort syntactic) receiver and SELECTS NOTHING; the still-live
    // ladder below is what actually decides dispatch until S2.3. Keyed on
    // identity/hash, never on a bare name for the dispatch decision.
    recordMethodResolveBucket(expr, memberAccess, methodName, matchingAbilities, builtInMatches, inherentMethods, context)

    if (matchingAbilities.isEmpty() && builtInMatches.isEmpty() && inherentMethods.isNotEmpty()) {
        // Multi-candidate dispatch by the GENERATED receiver type. The
        // syntactic `getReceiverConcreteType` walk (used by the deferral guard
        // below) can't see through a chained/match-bound receiver — e.g.
        // `val.text {}.get_or {…}` where `val` is bound by `match … is Ok { val }`
        // and its payload TyVar isn't solved yet. But the receiver's GENERATED
        // type already carries the concrete named identity (`.text {}` promotes
        // its result to the `Result Str Str` TypeApplication via the #334 path),
        // so generate it and filter candidates by that identity (name for
        // nominals/TypeApplications, structural-alias match for bare
        // records/variants — same `candidateTypeNames` the concrete-receiver
        // and chain-callee paths use). This resolves the call by identity
        // instead of falling through the `isReceiverUnresolved` bail to a
        // structural field access → T0205. Only fires when the filter lands on
        // a UNIQUE target's methods, so it never guesses among genuine
        // ambiguity. See project_result_option_two_representations / #334.
        if (inherentMethods.size > 1) {
            val genReceiverType = generate(memberAccess.receiver, context.notInTailPosition()).type
            val effectiveReceiverType = genReceiverType
            if (effectiveReceiverType !is MezzeType.VarType) {
                val names = candidateTypeNames(effectiveReceiverType, context)
                if (names.isNotEmpty()) {
                    val filtered = inherentMethods.filter { it.targetTypeName in names }
                    if (filtered.isNotEmpty() && filtered.map { it.targetTypeName }.distinct().size == 1) {
                        return generateInherentMethodCall(expr, memberAccess, filtered, context)
                    }
                }
            }
        }
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
        // #448 + Gap 2: filter inherent candidates by the receiver's type-decl
        // name(s). Nominal types match by name (Seq, Int, or a labeled Client);
        // bare records/variants produced by transparent-alias resolution match
        // structurally against in-scope aliases. Single unified path — same
        // helper the chain-callee return-type lookup uses.
        if (inherentMethods.size > 1) {
            val concreteReceiver = getReceiverConcreteType(memberAccess.receiver, context)
            val candidateNames = concreteReceiver?.let { candidateTypeNames(it, context) } ?: emptySet()
            if (candidateNames.isNotEmpty()) {
                val filtered = inherentMethods.filter { it.targetTypeName in candidateNames }
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
                concreteReceiver is MezzeType.RecordType && concreteReceiver.identity == TypeIdentity.Anonymous ->
                    findMatchingRecordTypeAliases(concreteReceiver, context).toSet()
                concreteReceiver is MezzeType.VariantType && concreteReceiver.identity == TypeIdentity.Anonymous ->
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
    // #86 sub-gap 2: fresh var for the ability's first typeParam, so
    // `TypeName.method{…}` can pin it below via Equality once the ability
    // resolves. Null when this branch isn't taken.
    var firstClassArgVar: TypeVar? = null
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

    // Receiver-driven ability disambiguation: when two abilities share an op
    // name (e.g. the pure `Sized.size` and the effectful `SizedMut.size`) the
    // pick above still leaves both, and the consumer below would take
    // `.first()` — i.e. dispatch by load order, then error because the
    // receiver's type only implements the OTHER ability. Instead, when the
    // receiver's concrete type name is known, keep only the abilities that
    // actually have an impl targeting that type. Structural — keys on the
    // impl registry's target types, never on the ability name. Only narrows
    // when the survivor set is non-empty; if none match (receiver type has no
    // impl for any candidate) we leave the set as-is so the downstream
    // AbilityRequired constraint produces the normal "does not implement"
    // error rather than silently vanishing.
    val implFilteredAbilities = if (filteredMatchingAbilities.size > 1) {
        val concreteReceiver = getReceiverConcreteType(memberAccess.receiver, context)
        val receiverTypeName = concreteReceiver?.let { resolveReceiverTypeName(it) }
        if (receiverTypeName != null) {
            // ALLOWED: NARROWS (never finalizes) a >1 ability candidate set by the
            // impl registry's name-labeled targetTypes, and only when the survivor
            // set is non-empty — a downstream AbilityRequired constraint then
            // validates. Not a terminal identity/dispatch decision by name.
            val implMatches = filteredMatchingAbilities.filter { (abilityName, _) ->
                context.typingContext.getAbilityImpls(abilityName)
                    .any { receiverTypeName in it.targetTypes }
            }
            if (implMatches.isNotEmpty()) implMatches else filteredMatchingAbilities
        } else {
            filteredMatchingAbilities
        }
    } else {
        filteredMatchingAbilities
    }

    if (builtInMatches.size == 1 && implFilteredAbilities.isEmpty()) {
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
    } else if (implFilteredAbilities.isNotEmpty()) {
        val (abilityName, rawMethodType) = implFilteredAbilities.first()
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
            // #86 sub-gap 2: record the ability's first class-arg TyVar; the
            // Type-prefixed dispatch pin (Equality below) unifies it with the
            // TypeApplication threaded through `context.pinFirstAbilityTypeArg`.
            firstClassArgVar = abilityTypeVars.first()

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

    // #334, extended to ability methods: when the method's declared return is a
    // NAMED TypeApplication (`Result 'a 'e`, `Option 'a`, …), make the call
    // result BE that named type — a fresh instantiation preserving the
    // constructor and Declared identity, args freshened — not a bare var pinned
    // to it only during solving.
    //
    // The inherent-method path already does this. The ability path did not, so
    // `body.from_json {}` (return `Result 'a DecodeError`) produced a bare
    // VarType, and a `.map` chained on it saw no `Result` identity to
    // disambiguate among its nine same-named overloads (Result/Seq/Bytes/…). It
    // fell through and mis-dispatched to Bytes — `expected Bytes, but got
    // Result`. A method dispatching on its RETURN (`from_json`) makes this the
    // common case, not a corner one.
    val abilityMethodReturn = strippedMethodType.returnType
    val resultType: MezzeType =
        if (abilityMethodReturn is MezzeType.TypeApplication)
            instantiateTypeWithFreshVars(abilityMethodReturn)
        else MezzeType.VarType(TypeVar.fresh())
    val constraints = mutableListOf<Constraint>()
    constraints.addAll(receiverResult.constraints)
    constraints.addAll(argumentResult.constraints)

    val receiverType: MezzeType = receiverResult.type
    val extraEffects: Set<String> = emptySet()
    val extraEffectInstances: Set<MezzeType.EffectInstance> = emptySet()

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

    // #86 sub-gap 2 (docs/codec/redesign-2026-09.md §Open questions Q1): pin the
    // ability's first class-param to the type-name prefix the caller wrote. This
    // is what makes `Person.from_json { it = raw }` dispatch to `FromJson`'s `'a`
    // slot even though `'a` only appears in the return type — a return-driver
    // pattern the receiver-driven ladder can't decide on its own. Consumed here
    // for the current call site only; caller (generateFunctionCall) stashes the
    // pin under a synthetic `it.method` rewrite so tryGenerateMethodCall's usual
    // machinery still runs.
    val pin = context.pinFirstAbilityTypeArg
    if (pin != null && firstClassArgVar != null) {
        constraints.add(Constraint.Equality(
            left = MezzeType.VarType(firstClassArgVar),
            right = pin,
            context = "Type-prefixed dispatch pin: ${methodName} class-arg",
            loc = expr.loc,
        ))
    }

    val (callType, methodDeclaredEffects, methodDeclaredEffectInstances) =
        wrapCallResultWithEffects(resultType, strippedMethodType.effects)

    // #458: harvest closure-callback row-var effects into the outer call's
    // effect set. See generateInherentMethodCall for the design note. Gate:
    // any declared CALLBACK PARAM has a row-var — closed rows must not
    // over-propagate. Ability methods usually have pure returns but their
    // callback rows are open (that's what makes them polymorphic in effects).
    val abilityCaughtByOp = collectCaughtByAncestor(strippedMethodType.paramType)
    val abilityHasCallbackRowVar = (strippedMethodType.paramType as? MezzeType.RecordType)?.fields?.values?.any { field ->
        (field as? MezzeType.FunctionType)?.effects?.rowVar != null
    } == true
    val abilityHarvestedEffects: Set<String> =
        if (abilityHasCallbackRowVar) {
            collectClosureArgEffects(argumentResult.type) - abilityCaughtByOp
        } else emptySet()

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
    // S9 (#374) — INTENTIONAL BOUNDARY, analyzed not overlooked. Unlike inherent
    // dispatch (unified post-solve into DispatchResolver in S2), ability dispatch
    // is DELIBERATELY generated here at gen-time. Two facts make relocating it
    // pointless AND unsafe:
    //   1. This branch does NOT pick a concrete impl. It emits the AbilityRequired
    //      + AssociatedTypeResolution constraints above; the actual ability→impl
    //      resolution is ALREADY post-solve — ConstraintSolver/AbilityChecker
    //      resolve them keyed by (abilityName, primaryTypeVar.id), and the
    //      interpreter dispatches the op by hash on the class-arg tuple at runtime.
    //      So the "decide dispatch once, post-solve" property S7.1 proves for
    //      inherent ALREADY holds for abilities — via a different, equally
    //      post-solve mechanism, not via MethodCallInfo.
    //   2. This MethodCallInfo carries NO dispatch decision for emit to consume:
    //      methodHash=null and isInherentMethod=false, so TypedAstBuilder's ability
    //      arm takes neither the ByHash nor the inherent-impl path — it builds an
    //      ordinary field access (buildExpr(expr.function)). Emit reads only
    //      receiverFieldName (always `it`) and typeArgs (the mono TyVar thread,
    //      #218). abilityName is never read by emit. There is no MethodCallInfo to
    //      "rebuild post-solve" — moving the PICK gains no deletion (the #358
    //      finding), and the fresh-tyvar / constraint-graph context that produces
    //      the AbilityRequired constraints exists ONLY at gen time.
    // Same-op ability collisions (e.g. Sized.size vs SizedMut.size) are decided by
    // the call's EFFECT signature / annotation, NOT by receiver type identity —
    // DispatchResolver.resolveBucket explicitly DEFERS them (records no Resolution)
    // for exactly this reason. Keep this boundary.
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
        effects = (receiverResult.effects + argumentResult.effects + extraEffects + methodDeclaredEffects + abilityHarvestedEffects) - abilityCaughtByOp,
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
        // A call `f {..}` or a parenthesized `(e)` produces a value whose type is
        // pinned by solving, so deferring inherent-method dispatch to emit time is
        // safe.
        is ParsedFunctionCall, is ParsedParens -> true
        // A field access `r.f` is solvable ONLY when its BASE is — recurse. A chain
        // rooted at a concrete value (`point.x.neg {}`, point a monomorphic local)
        // is solvable: getReceiverConcreteType resolves it and the caller never even
        // reaches this guard. But a chain rooted at an unpinnable LAMBDA PARAM
        // (`{ r } -> r.n.to_str {}`) is NOT: nothing pins `r`, so `n` stays a free
        // var with no runtime impl to dispatch to. Treating it as "solvable" made
        // the call take the homogeneous-deferral path, which types it from a
        // representative signature and DROPS the receiver-refinement — `n` was
        // accepted as ANY value (incl. a function) then crashed at runtime
        // (SOUNDNESS bug). Not-solvable falls through to structural field-access,
        // which emits the `{ to_str: {} -> _ }` refinement, matching the top-level
        // `{ x } -> x.to_str {}` reference behaviour.
        is ParsedMemberAccess -> receiverHasSolvableType(receiver.receiver, context)
        is ParsedIdentifier -> {
            if (receiver.qualifier != null) return false
            // A lambda param is monomorphic-but-unpinnable; not solvable.
            if (receiver.localName in context.currentScopeBindings) return false
            val lookup = context.typingContext.lookup(receiver.localName)
            val binding = (lookup as? TypingContext.LookupResult.Found)?.binding
            if (binding !is TypingContext.Binding.Monomorphic) return false
            // Reaching here, the receiver's static type is a VarType (a concrete
            // one was resolved by getReceiverConcreteType upstream and never hit
            // this guard). Two kinds of var-typed monomorphic binding exist, and
            // they need OPPOSITE treatment:
            //
            //  - SOLVABLE — a do-block `let x = <rhs>` whose RHS is a value whose
            //    type the GLOBAL solve pins even though it's a bare var now: a
            //    polymorphic call result (`let x = help {..}`, help's `having 'out`
            //    resolves only globally). Deferring the impl pick to emit is safe
            //    and REQUIRED — the inline `help {..}.to_str {}` works, so the
            //    let-bound form must too. These names are recorded in
            //    solvableLetBindings when the do-block let is generated (keyed off
            //    the same solvable-expression predicate, applied to the RHS).
            //
            //  - UNPINNABLE — a PATTERN/DESTRUCTURE binding of a field/payload
            //    extracted from a free-typed param (`match r is { n } -> …`,
            //    `Grams { n } -> …`, `do let { n } = r`), or a `let` aliasing a free
            //    param: nothing ever pins the var, so there is no runtime impl to
            //    defer to. Treating it as solvable would take the homogeneous-
            //    deferral path, which types the call from a representative signature
            //    and DROPS the receiver-refinement — accepting ANY value (incl. a
            //    function) then crashing at runtime (SOUNDNESS bug). These names are
            //    NOT in solvableLetBindings, so they fall through to structural
            //    field-access, which emits the `{ to_str: {} -> _ }` refinement,
            //    matching the top-level `{ x } -> x.to_str {}` reference behaviour.
            receiver.localName in context.solvableLetBindings
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

/**
 * S2.1 (#356): record ONE [MethodResolveBucket] for a method/ability/builtin
 * call site into the generator's [ExpressionConstraintGenerator.resolutionBuckets]
 * sink. Generalizes the deferred-dispatch idea of [Constraint.InherentMethodResolve]
 * to cover ability + builtin ops too: it captures every candidate (inherent,
 * ability, builtin) keyed on identity/hash and the best-effort syntactic receiver
 * type, and SELECTS NOTHING. Recording-only — it emits no [Constraint], mutates no
 * typing state, and does not perturb the still-live dispatch ladder. The S2.2
 * single pass consumes these buckets to produce a [Resolution]; the ladder remains
 * the live decider until S2.3.
 */
internal fun ExpressionConstraintGenerator.recordMethodResolveBucket(
    expr: ParsedFunctionCall,
    memberAccess: ParsedMemberAccess,
    methodName: String,
    matchingAbilities: List<Pair<String, MezzeType>>,
    builtInMatches: List<String>,
    inherentMethods: List<TypingContext.InherentMethodInfo>,
    context: ConstraintContext,
) {
    val candidates = mutableListOf<ResolutionCandidate>()

    for (mi in inherentMethods) {
        candidates.add(
            ResolutionCandidate(
                opHash = mi.implContentHash,
                kind = ResolutionKind.Inherent,
                targetTypeIdentity = mi.targetTypeHash?.let { TypeIdentity.declared(mi.targetTypeName, it) },
                displayName = mi.targetTypeName,
            )
        )
    }
    for ((abilityName, _) in matchingAbilities) {
        candidates.add(
            ResolutionCandidate(
                opHash = context.abilityDeclarations[abilityName]?.contentHash,
                kind = ResolutionKind.Ability,
                // The ability op's target identity is the SOLVED receiver's — not
                // known at gen time. The S2.2 pass fills it from the solution.
                targetTypeIdentity = null,
                displayName = abilityName,
            )
        )
    }
    for (abilityName in builtInMatches) {
        candidates.add(
            ResolutionCandidate(
                // Builtin operator abilities have no content hash — their reserved
                // name is their canonical identity.
                opHash = null,
                kind = ResolutionKind.Builtin,
                targetTypeIdentity = null,
                displayName = abilityName,
            )
        )
    }

    // Best-effort syntactic receiver type (pure read-only lookup — allocates only
    // fresh placeholder tyvars, emits no constraints). Null when unresolvable at
    // gen time; the S2.2 pass reads the real receiver from the solution.
    val receiverType = getReceiverConcreteType(memberAccess.receiver, context)
        ?: MezzeType.VarType(TypeVar.fresh())

    resolutionBuckets.add(
        MethodResolveBucket(
            loc = expr.loc,
            methodName = methodName,
            receiverType = receiverType,
            candidates = candidates,
        )
    )
}

internal fun ExpressionConstraintGenerator.getReceiverConcreteType(receiver: ParsedExpr, context: ConstraintContext): MezzeType? {
    when (receiver) {
        is ParsedIntLit -> return MezzeType.IntType
        is ParsedNatLit -> return natLiteralType(context)
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
            // Direct callee `f{...}` — look up f's declared return type.
            val ret = calleeReturnType(receiver, context)
            if (ret != null && ret !is MezzeType.VarType) {
                return ret
            }
            // #448: Method-chain callee `x.m{...}` — the direct-callee lookup
            // above fails because the callee is a ParsedMemberAccess, not a
            // ParsedIdentifier. Resolve the chain: find the inner receiver's
            // type, look up its `.m` inherent method, return `.m`'s return
            // type. This is the structural fix: EVERY "thing before `.`"
            // should return a value whose type is looked up. Previously only
            // identifier receivers worked; literal chains and method chains
            // fell through to null, forcing the outer dispatch to guess.
            val callee = receiver.function
            if (callee is ParsedMemberAccess) {
                val innerType = getReceiverConcreteType(callee.receiver, context) ?: return null
                val innerNames = candidateTypeNames(innerType, context)
                if (innerNames.isEmpty()) return null
                val allCandidates = context.typingContext.findAnonymousMethodsByName(callee.member)
                // Prefer the receiver's CONTENT HASH when it has one: two same-named
                // methods from different modules differ only by targetTypeHash, and
                // unlike the primary dispatch path this site picks via singleOrNull()
                // with no later hash veto — so a name-only filter could select the
                // wrong module's method. Fall back to the name-set for hashless
                // receivers (builtins / transparent aliases), exactly as the primary
                // path does.
                val innerHash = com.mezze.language.typechecker.util.declaredHashOf(innerType)
                val byHash = if (innerHash != null) allCandidates.filter { it.targetTypeHash == innerHash } else emptyList()
                val candidates = if (byHash.isNotEmpty()) byHash
                    else allCandidates.filter { it.targetTypeName in innerNames }
                val method = candidates.singleOrNull() ?: return null
                val ret2 = (method.methodType as? MezzeType.FunctionType)?.returnType ?: return null
                return ret2
            }
            return null
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
    // #392: a match-arm pattern binding is registered with its raw fresh pattern
    // VarType (kept a var for cross-arm unification), so the name-keyed lookup below
    // can't see its concrete type. generateMatch stashes the SOLVED binding type in
    // this dispatch-only side table; prefer it so a match-bound receiver dispatches
    // like a `let`/param one. Absent ⇒ genuinely unresolved (falls through).
    context.matchBindingConcreteTypes[receiver.localName]?.let { return it }
    val lookup = context.typingContext.lookup(receiver.localName)
    if (lookup !is TypingContext.LookupResult.Found) return null
    val type = when (val binding = lookup.binding) {
        is TypingContext.Binding.Monomorphic -> binding.type
        is TypingContext.Binding.Polymorphic -> binding.scheme.instantiate().type
    }
    // S1.3: a do-block `let x = <expr>` now runs a local incremental solve and
    // routes through the shared bindLet, so `x`'s binding TYPE is already the
    // concrete solved type here (e.g. `let kept = xs.filter{…}` → `[…]`). The old
    // letBoundExprs crutch — resolving THROUGH the source expr when the binding was
    // still a raw VarType — is gone; the binding type is authoritative. A still-var
    // type means genuinely-unresolved (no static answer), so we return null.
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

    val receiverType: MezzeType = receiverResult.type
    val extraEffects: Set<String> = emptySet()
    val extraEffectInstances: Set<MezzeType.EffectInstance> = emptySet()

    // #334: default to a fresh var; reassigned below to the method's INSTANTIATED
    // named return type when that is a TypeApplication, so the call result keeps
    // its `Result`/`Option`/… identity at generation time. Without this, a chained
    // `let r = a.await {}; r.get_or {…}` binds `r` to a bare var and the
    // multi-candidate `.get_or` (Result + Option) cannot be dispatched during
    // generation — it falls through to a structural field access and fails with
    // "Cannot access field 'get_or' on variant type …".
    var resultType: MezzeType = MezzeType.VarType(TypeVar.fresh())
    val constraints = mutableListOf<Constraint>()
    constraints.addAll(receiverResult.constraints)

    val receiverTypeName = resolveReceiverTypeName(receiverType)
        ?: lookupConstructorTypeName(memberAccess.receiver, context)
    // Prefer the resolved receiver's CONTENT HASH to disambiguate two same-named
    // inherent-method impls from different modules (name-only matching would pick
    // by load order → silent mis-dispatch). Falls back to name when the receiver
    // has no type hash (builtins). See project_classparam_runtime_dispatch_tuple.
    val receiverTypeHash = com.mezze.language.typechecker.util.declaredHashOf(receiverType)
    // Does a resolved-CONCRETE receiver actually match this candidate's target
    // type? An unresolved receiver (still a VarType) is genuinely polymorphic /
    // deferred — accept it (later constraints pin it). A concrete receiver whose
    // declared identity (hash first, then name) DIFFERS from the impl's target is
    // a call of the method on the WRONG type (BUG A: `(42).unwrap {}` against an
    // `impl Box`) — reject by resolving to null so the call falls through to a
    // structural field access, which fails cleanly instead of being accepted and
    // crashing at runtime.
    // Reject a single candidate ONLY for an unambiguous plain-nominal mismatch:
    // the receiver is a concrete user record/variant (or scalar) whose declared
    // identity differs from the candidate's target. Parameterized-container impls
    // register under mangled target names (`Seq_Promise`) that never equal the
    // receiver's bare name (`Seq`) and whose element type isn't known here — so we
    // must NOT reject them (they dispatch correctly at runtime). When in any doubt,
    // accept (preserve the prior permissive behavior); we only ADD rejections for
    // the clear `Int`/`Box` case.
    fun recvIsPlainNominal(t: MezzeType): Boolean = when (t) {
        is MezzeType.RecordType -> t.identity is TypeIdentity.Declared
        is MezzeType.VariantType -> t.identity is TypeIdentity.Declared
        is MezzeType.IntType, is MezzeType.FloatType, is MezzeType.StringType,
        is MezzeType.BoolType, is MezzeType.FracType,
        is MezzeType.CharType, is MezzeType.BytesType -> true
        else -> false
    }

    fun candidateMatchesReceiver(cand: TypingContext.InherentMethodInfo): Boolean {
        if (receiverType is MezzeType.VarType) return true        // polymorphic — defer
        // Only a plain-nominal / scalar receiver + a plain (non-mangled) target
        // are eligible for a mismatch reject; anything else stays permissive.
        val eligible = recvIsPlainNominal(receiverType) && !cand.targetTypeName.contains('_')
        if (!eligible) return true
        if (receiverTypeHash != null && cand.targetTypeHash != null)
            return receiverTypeHash == cand.targetTypeHash
        if (receiverTypeName != null) {
            // Name decides only when the hash pair is unavailable (receiver or
            // candidate is hashless — a builtin/scalar target). If the receiver DID
            // carry a hash but the candidate didn't (or vice versa), that is a
            // hashless-side fallback, not a name overriding an available hash match.
            return cand.targetTypeName == receiverTypeName
        }
        return true
    }

    val methodInfo = if (deferImplByReceiver) {
        // Homogeneous candidates — any is a valid signature representative; the
        // concrete impl is picked from candidateImpls at emit time.
        inherentMethods.first()
    } else if (inherentMethods.size == 1) {
        // Single candidate — accept only if the receiver actually is (or could
        // still resolve to) its target type; else unresolved (BUG A).
        inherentMethods.first().takeIf { candidateMatchesReceiver(it) }
    } else if (receiverTypeHash != null) {
        // The receiver carries a declaration hash: identity is AUTHORITATIVE.
        // Match strictly by hash. Do NOT fall through to a name match — a
        // differently-hashed impl that happens to share the receiver's NAME is a
        // DIFFERENT declaration (two modules' `Widget`), and rescuing it by name
        // is exactly the silent mis-dispatch the hash veto exists to prevent.
        // No hash-match ⇒ unresolved (null), never a name-based guess.
        inherentMethods.firstOrNull { it.targetTypeHash == receiverTypeHash }
    } else if (receiverTypeName != null) {
        // Hashless receiver (builtins / pre-hash): name is the only identity we
        // have, so a name match is the correct fallback here. This arm is guarded by
        // `receiverTypeHash == null` above — a hash-carrying receiver NEVER reaches
        // here (it resolves by hash and stops), so the name never overrides a hash.
        inherentMethods.firstOrNull { it.targetTypeName == receiverTypeName }
    } else {
        // No receiver identity at all — neither a hash nor a name. Picking the
        // first candidate would be a blind by-load-order guess (the comment above
        // flags exactly this), so resolve to UNRESOLVED and let the fresh result
        // VarType stay open rather than silently mis-dispatch.
        null
    }

    // BUG A: a CONCRETE receiver that matches no candidate's target is calling an
    // inherent method on the wrong type (`(42).unwrap {}` against `impl Box`).
    // Resolution above yields methodInfo=null, which otherwise types the call as an
    // open var and SILENTLY ACCEPTS it — then it crashes at runtime as a bad field
    // access. Emit a real diagnostic instead — but ONLY for the unambiguous case:
    //   - the receiver is concrete (not a VarType — a polymorphic receiver defers),
    //   - it is a PLAIN NOMINAL type (a user record/variant or a scalar), NOT a
    //     parameterized container (Seq/List/Map/…) whose impls are registered under
    //     mangled target names like `Seq_Promise` that never equal the receiver's
    //     bare name `Seq` — matching those needs the solved element type, which
    //     isn't available here, so we must not reject them, and
    //   - every candidate target is likewise a plain name (no `_` mangling).
    // This keeps the rejection to genuine type mismatches and never fires on the
    // legitimate container-impl dispatch (`ps.race {}` on a Promise seq).
    val candidatesArePlain = inherentMethods.all { !it.targetTypeName.contains('_') }
    if (methodInfo == null && !deferImplByReceiver &&
        recvIsPlainNominal(receiverType) && candidatesArePlain && inherentMethods.isNotEmpty()) {
        val recvName = receiverTypeName ?: prettyPrintType(receiverType)
        throw TypeCheckError(
            "No inherent method '$methodName' for type '$recvName'. " +
                "It exists for: ${inherentMethods.map { it.targetTypeName }.distinct().joinToString(", ")}.",
            expr.loc,
            code = ErrorCode.T0201_TYPE_MISMATCH,
        )
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

            // #334: when the method's return type is a NAMED TypeApplication
            // (`Result 'a 'e`, `Option 'a`, …) — make the call result BE that named
            // type (a fresh instantiation preserving the constructor + Declared
            // identity, and freshening the type args) rather than a bare var pinned to
            // it only during solving. This keeps the name visible at generation time so
            // a downstream multi-candidate inherent method on the result (e.g.
            // `.get_or`, which lives on both Result and Option) dispatches by identity
            // instead of falling through to a structural field access ("Cannot access
            // field … on variant type …"). Other return shapes keep the fresh var —
            // there is no name to preserve.
            val methodReturn = freshMethodType.returnType
            val namedReturn = methodReturn
            if (namedReturn is MezzeType.TypeApplication) {
                resultType = instantiateTypeWithFreshVars(methodReturn)
            }
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
                    ConstraintResult.InherentImplCandidate(it.targetTypeName, it.implContentHash, it.targetTypeHash)
                }
            } else emptyList(),
        )
    } else null

    val (callType, methodDeclaredEffects, methodDeclaredEffectInstances) =
        wrapCallResultWithEffects(resultType, (freshMethodType as? MezzeType.FunctionType)?.effects)

    // #458: harvest closure-callback row-var effects into the outer call's
    // effect set for inherent method dispatch (`.map{f=...}` on Seq/List/etc).
    // Similar in spirit to ExprFunctionConstraints.kt:757-764 (plain-call path)
    // but with a different gate: for method dispatch we gate on whether ANY
    // declared CALLBACK PARAM has a row-var (not the method's return row —
    // methods like Seq.map declare `{f: T -> T <ρ>} -> [T] <ρ>` and the ρ is
    // shared, but for methods with pure returns whose callbacks still carry
    // rowvars we still need to harvest).
    // Without this, `xs.map{f = {x} -> perform E.op{}; x}.fold{...}` drops
    // the E effect silently.
    val inherentFnType = freshMethodType as? MezzeType.FunctionType
    val inherentCaughtByOp = if (inherentFnType != null) collectCaughtByAncestor(inherentFnType.paramType) else emptySet()
    val inherentHasCallbackRowVar = inherentFnType?.paramType?.let { pt ->
        (pt as? MezzeType.RecordType)?.fields?.values?.any { field ->
            (field as? MezzeType.FunctionType)?.effects?.rowVar != null
        }
    } == true
    val inherentHarvestedEffects: Set<String> =
        if (inherentHasCallbackRowVar) {
            collectClosureArgEffects(argumentResult.type) - inherentCaughtByOp
        } else emptySet()

    return ConstraintResult(
        type = callType,
        constraints = constraints,
        effects = (receiverResult.effects + argumentResult.effects + extraEffects + methodDeclaredEffects + inherentHarvestedEffects) - inherentCaughtByOp,
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

    val receiverType: MezzeType = receiverResult.type
    val extraEffects: Set<String> = emptySet()
    val extraEffectInstances: Set<MezzeType.EffectInstance> = emptySet()

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
                    targetTypeHash = mi.targetTypeHash,
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

    // #458: harvest closure-callback row-var effects for the deferred inherent
    // path — same rationale as generateInherentMethodCall / generateAbilityMethodCall.
    // Gate: any candidate declares a callback param with a row-var. Their param
    // types agree modulo unresolved TyVars, so union caughtByAncestor across
    // candidates for the subtraction.
    val deferredAnyCallbackRowVar = resolvedCandidates.any { (_, fnType) ->
        val pt = (fnType as? MezzeType.FunctionType)?.paramType as? MezzeType.RecordType
        pt?.fields?.values?.any { field ->
            (field as? MezzeType.FunctionType)?.effects?.rowVar != null
        } == true
    }
    val deferredCaughtByOp: Set<String> = resolvedCandidates
        .mapNotNull { (_, fnType) -> (fnType as? MezzeType.FunctionType)?.paramType }
        .flatMap { collectCaughtByAncestor(it) }
        .toSet()
    val deferredHarvestedEffects: Set<String> =
        if (deferredAnyCallbackRowVar) collectClosureArgEffects(argumentResult.type) - deferredCaughtByOp
        else emptySet()

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
            ConstraintResult.InherentImplCandidate(mi.targetTypeName, mi.implContentHash, mi.targetTypeHash)
        },
    )

    return ConstraintResult(
        type = callType,
        constraints = constraints,
        effects = (receiverResult.effects + argumentResult.effects + extraEffects + methodDeclaredEffects + deferredHarvestedEffects) - deferredCaughtByOp,
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
        BuiltinTypes.reservedConstructorOwner[ctorName]?.let {
            if (it == BuiltinTypes.RESULT || it == BuiltinTypes.OPTION) return it
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
        return resolveReceiverTypeName(ret)
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
 * Type-decl names a receiver type might dispatch on. A nominal type
 * (Int, Client-with-name, TypeApplication) yields its single name; a
 * bare record/variant produced by transparent-alias resolution falls back
 * to structural alias matching (Gap 2 — same shape as method-dispatch's
 * ambiguity resolver). Empty set means the receiver isn't dispatchable
 * via inherent methods (bail out at the caller).
 */
internal fun ExpressionConstraintGenerator.candidateTypeNames(
    type: MezzeType,
    context: ConstraintContext
): Set<String> {
    resolveReceiverTypeName(type)?.let { return setOf(it) }
    return when (type) {
        is MezzeType.RecordType -> findMatchingRecordTypeAliases(type, context).toSet()
        is MezzeType.VariantType -> findMatchingVariantTypeAliases(type, context).toSet()
        else -> emptySet()
    }
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

