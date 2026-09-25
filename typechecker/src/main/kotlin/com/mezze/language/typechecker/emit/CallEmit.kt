package com.mezze.language.typechecker.emit

import com.mezze.language.typechecker.solving.expandTypeApplicationToRecord
import com.mezze.language.typechecker.solving.expandTypeApplicationToVariant
import com.mezze.language.typechecker.solving.substituteTypeParamsByName
import com.mezze.language.typechecker.solving.substituteTypeVars
import com.mezze.language.ast.*
import com.mezze.language.ast.hashing.MezzeHashBuilder
import com.mezze.language.parser.MezzeParser as ParserBridge
import com.mezze.language.parsedast.*
import com.mezze.language.parsedast.Visibility as ParsedVisibility
import com.mezze.language.typedast.*
import com.mezze.language.typedast.TypeScheme as V2TypeScheme
import com.mezze.language.typechecker.constraints.ConstraintResult
import com.mezze.language.typechecker.constraints.ConstraintResult.MethodCallInfo
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.module.ModuleImportContext
import com.mezze.language.ast.solving.Substitution
import com.mezze.language.typechecker.WellKnownHashConstants
import com.mezze.language.typechecker.context.DeclarationRegistry
import com.mezze.language.typechecker.util.WellKnownTypeApp
import com.mezze.language.typechecker.util.receiverTypeName
import com.mezze.language.ast.hashing.HashTags
import com.mezze.language.ast.solving.closeGeneratedRowVars

/*
 * Call emission, moved out of TypedAstBuilder.
 *
 * The three call shapes — an ordinary call, a static ability operation, and a
 * method call — are one subject: each decides a callee, a dispatch and an
 * argument context, and they share their rules by sitting next to each other.
 * They were ~640 lines in the middle of a 3,100-line file.
 *
 * Extension functions rather than a class, because they read and write the
 * builder's scope and substitution state; the same split
 * `ExpressionConstraintGenerator` uses across ExprFunctionConstraints and its
 * siblings, and now `TypedASTCompiler` across DictEvidence and
 * PatternCompilation.
 *
 * A pure move: no body was edited, only the indentation and the receiver.
 */

internal fun TypedAstBuilder.buildFunctionCall(expr: ParsedFunctionCall, file: String, contextType: MezzeType?): TypedExpr {
    val loc = expr.loc

    if (expr.function is ParsedMemberAccess) {
        val memberAccess = expr.function as ParsedMemberAccess
        val receiverName = when (val r = memberAccess.receiver) {
            is ParsedIdentifier -> r.localName
            is ParsedVariantConstruct -> r.constructor
            else -> null
        }
        if (receiverName != null) {
            val isEffect = registry.lookupEffect(receiverName) != null
                || importContext?.importedEffects?.containsKey(receiverName) == true
            if (isEffect) {
                val effectHash = resolveEffectHash(receiverName)
                val opName = memberAccess.member
                val argExpr = buildExpr(expr.arguments, file)
                val effectDecl = registry.lookupEffect(receiverName)
                    ?: importContext?.importedEffects?.get(receiverName)
                // The solver already unified this call's result var with the
                // op's instantiated return (e.g. `spawn`'s `Promise 'a` tied to
                // the callback's return). Prefer that per-call-site solved type —
                // it's the SAME source the ordinary-call path uses below (line
                // ~787). Falling back to re-converting the op's DECLARED return
                // annotation yields a fresh unbound `'a` (`Promise 'a`): fine at
                // the top level, where `contextType` is already solved, but wrong
                // for a do-block `let` whose `contextType` is still a bare var —
                // there the raw annotation leaks through and two spawns with
                // different callbacks both come out `Promise 'a`.
                val solvedCallReturn = callReturnTypes[loc]?.let { substitution.apply(it) }
                val resolvedReturnType = effectDecl?.operations
                    ?.firstOrNull { it.name == opName }
                    ?.let { convertParsedTypeAnnotation(it.returnType) }
                val applied = contextType?.let { substitution.apply(it) }
                val finalType = when {
                    solvedCallReturn != null && solvedCallReturn !is MezzeType.VarType -> solvedCallReturn
                    applied is MezzeType.VarType && resolvedReturnType != null -> resolvedReturnType
                    else -> applied
                } ?: noContextType("perform op with no solved return, no resolved return and no context")
                return TypedPerformOp(effectHash, opName, argExpr, finalType, loc)
            }
        }
    }

    val fnIsMemberAccess = expr.function is ParsedMemberAccess
    val memberName = if (fnIsMemberAccess) (expr.function as ParsedMemberAccess).member else null
    val methodInfo = if (memberName != null) methodCallResolutions[expr.loc to memberName] else null
    if (methodInfo != null && fnIsMemberAccess) {
        return buildMethodCall(expr, methodInfo, file, contextType)
    }

    val fn = buildExpr(expr.function, file)
    // #261: thread the callee's PARAM type into the argument so an untyped
    // callback param whose type is pushed from a declared fn type
    // (`apply: { f: { x: Int } -> Str }` ⇒ `apply { f = { x } -> … }`) is
    // stamped CONCRETE at emit. Without this, the arg record — and the lambda
    // inside it — is built context-free, the pushed `Int` never reaches the
    // param, and an ability-method call on it emits an uncached CallIndirect
    // that crashes at runtime (mono's methodReceiverNotConcrete bail).
    val fnParamType = (substitution.apply(fn.type) as? MezzeType.FunctionType)?.paramType
    // The callee's declared parameter record can still hold ITS OWN generic
    // variables at this site — `seq::find`'s `p: { x: 'a } -> Bool` keeps `'a`
    // when the solve did not pin it here. Pushing that down stamps a callback's
    // parameter as a variable, and everything downstream is then guessing on a
    // receiver the program had spelled out.
    //
    // The sibling arguments say what those variables are: `find { xs = rts, … }`
    // with `rts : [Rt]` binds `'a = Rt`. Harvest from them and substitute before
    // pushing. Non-lambda fields are built first because they are what carries
    // the information; the result is discarded, only their TYPES are used.
    val refinedParamType = run {
        val pattern = fnParamType as? MezzeType.RecordType ?: return@run fnParamType
        if (!pattern.holdsTypeVar()) return@run fnParamType
        val args = expr.arguments as? ParsedRecordLit ?: return@run fnParamType
        val bindings = mutableMapOf<TypeVar, MezzeType>()
        for (entry in args.entries) {
            if (entry !is ParsedRecordEntry.Field) continue
            if (entry.value is ParsedLambda) continue
            val declared = pattern.fields[entry.name] ?: continue
            if (!declared.holdsTypeVar()) continue
            val fieldValue = entry.value ?: continue
            val actual = substitution.apply(buildExpr(fieldValue, file).type)
            // A variable bound by the ENCLOSING declaration is a real type, not
            // a gap: inside `min_by : { xs: ['a], f: { x: 'a } -> 'b } …` the
            // argument `f` has type `{ x: 'n227 } -> 'v229`, and those two
            // variables are precisely what the callee's `'a` and `'b` are being
            // instantiated to here. Refusing every actual that holds a variable
            // refused all of them inside generic code — which is all of the
            // collections — so the callee's own `'b` stayed in the caller's body
            // and belonged to no signature there.
            if (actual.holdsUnknownPlaceholder()) continue
            harvestVarBindings(declared, actual, bindings)
        }
        if (bindings.isEmpty()) fnParamType
        else substituteTypeVars(pattern, bindings)
    }
    val argExpr = if (refinedParamType != null) buildExpr(expr.arguments, file, refinedParamType)
        else buildExpr(expr.arguments, file)

    val solvedReturn = callReturnTypes[expr.loc]?.let { substitution.apply(it) }
    val callType = solvedReturn
        ?: when (val ft = substitution.apply(fn.type)) {
            is MezzeType.FunctionType -> substitution.apply(ft.returnType)
            else -> contextType?.let { substitution.apply(it) }
        }
        ?: noContextType("call with no solved return, no callee arrow and no context")

    val isTailCall = isTailCallLocation(expr.loc)

    // A callee whose type is still undetermined gets it from the CALL, which
    // knows: the argument record was just built, and the return is the call's
    // own solved type. Nothing is invented — both halves are read off this node.
    //
    // This is what types `__native_*` references. A native is in no binding
    // table, so it resolved nowhere and emitted as an undetermined type; the
    // stdlib is mostly native wrappers, so that single gap accounted for the
    // large majority of undetermined types in the emitted tree.
    // The callee is not always a plain reference. `a < b` and every other
    // operator dispatch emits the ability op as a FIELD ACCESS off its
    // receiver, and that is the form 441 of the 451 remaining undetermined
    // types took — the rule below used to skip them for being the wrong node
    // class, which has nothing to do with whether the call knows the type.
    val calleeType: MezzeType? = when (fn) {
        is TypedVar -> fn.type
        is TypedFieldAccess -> fn.type
        else -> null
    }
    fun retypedCallee(to: MezzeType): TypedExpr = when (fn) {
        is TypedVar -> fn.copy(type = to)
        is TypedFieldAccess -> fn.copy(type = to)
        else -> fn
    }

    val typedFn = when {
        calleeType == null -> fn

        calleeType.holdsUnknownPlaceholder() &&
            !callType.holdsUnknownPlaceholder() &&
            !argExpr.type.holdsUnknownPlaceholder() ->
            retypedCallee(MezzeType.FunctionType(paramType = argExpr.type, returnType = callType))

        // A POLYMORPHIC callee is instantiated at its use site.
        //
        // A reference to `min_by_with_cmp : { xs: ['a], … } -> Option 'a` was
        // emitted carrying the callee's OWN variables — the very `'a` object
        // from its declaration, shared by every call site in the program. The
        // declaration `min_by` is generalized over the variables its own solve
        // produced, so its scheme said `'n227` while its body said `'a`: one
        // written type variable, two TypeVars, and nothing downstream that
        // matches BY NAME (mono's substitution, the per-(ability, type var)
        // dict slots) can line the two up.
        //
        // This call knows the instantiation: match the declared shape against
        // the argument that was just built and against the call's own solved
        // result. Only the VARIABLES move — the declared shape, its open/closed
        // records and its aliases, is what gets substituted into, so nothing is
        // widened or narrowed here.
        else -> {
            val declared = calleeType as? MezzeType.FunctionType
            if (declared == null || !declared.holdsTypeVar()) fn else {
                val bindings = mutableMapOf<TypeVar, MezzeType>()
                if (!argExpr.type.holdsUnknownPlaceholder()) {
                    harvestVarBindings(declared.paramType, argExpr.type, bindings)
                }
                // The return too — it is the only thing `empty {}`, `None` and
                // every other nullary constructor HAS, and without it their
                // element type stays open in the caller.
                //
                // Except for a variable a DICTIONARY is keyed on. `zero {}` and
                // `default {}` are chosen by the type they produce, and so is
                // every wrapper that forwards one (`{ … } with Default 'a -> 'a`)
                // — the evidence arrives in a slot named for that variable.
                // Renaming it leaves the lookup pointing at a variable that is no
                // longer there: "nothing here determines that type". Both forms
                // say so structurally — the ability op through its binding, the
                // wrapper through the constraint on its own arrow — so neither
                // needs to be recognised by name.
                val dictKeyed = HashSet<TypeVar>()
                if ((fn as? TypedVar)?.binding is BindingRef.AbilityOp) {
                    com.mezze.language.ast.collectTypeVariables(declared.returnType, dictKeyed)
                }
                for (constraint in declared.abilityConstraints) {
                    for (arg in constraint.typeArgs) {
                        com.mezze.language.ast.collectTypeVariables(arg, dictKeyed)
                    }
                }
                val paramVars = HashSet<TypeVar>()
                com.mezze.language.ast.collectTypeVariables(declared.paramType, paramVars)
                if (!callType.holdsUnknownPlaceholder()) {
                    val fromReturn = mutableMapOf<TypeVar, MezzeType>()
                    harvestVarBindings(declared.returnType, callType, fromReturn)
                    for ((tv, to) in fromReturn) {
                        if (tv !in dictKeyed || tv in paramVars) {
                            bindings.putIfAbsent(tv, to)
                        }
                    }
                }
                // A dict-keyed variable stays put even when an ARGUMENT would
                // have pinned it, for the same reason.
                bindings.keys.retainAll { it !in dictKeyed || it in paramVars }
                // A variable standing for itself is not an instantiation, and a
                // binding to something still undetermined would REPLACE a real
                // variable with a guess — strictly worse than leaving it open.
                val instantiation = bindings.filterNot { (tv, to) ->
                    to.holdsUnknownPlaceholder() || (to as? MezzeType.VarType)?.variable == tv
                }
                if (instantiation.isEmpty()) fn
                else retypedCallee(substituteTypeVars(declared, instantiation))
            }
        }
    }

    return TypedCall(
        function = typedFn,
        arg = argExpr,
        instantiation = null,
        isTailCall = isTailCall,
        type = callType,
        loc = loc
    )
}


/**
 * `Ability.op {…}` for an operation that declares NO RECEIVER.
 *
 * `default: {} -> 'a` gives the call nothing to dispatch on but the type it
 * produces, so the impl is chosen from the SOLVED RETURN. The ordinary method
 * path cannot do this: it builds a receiver from `memberAccess.receiver`, and
 * an ability name is not a value — `Default` is uppercase, so it built as a
 * variant constructor and the program reached the runtime doing a field read
 * on a variant ("Expected record for field 'default', got: MezzeVariant").
 *
 * Resolution is by ABILITY CONTENT HASH plus the produced type's name, the
 * same pairing the ambiguous-operation path above uses. No impl for that type
 * is an error here rather than a fallback: there is no other way to pick one,
 * and guessing produces exactly the silent wrong answer this replaces.
 */
internal fun TypedAstBuilder.buildStaticAbilityOpCall(
    expr: ParsedFunctionCall,
    methodInfo: ConstraintResult.MethodCallInfo,
    file: String,
    contextType: MezzeType?,
): TypedCall {
    val loc = expr.loc
    val memberAccess = expr.function as ParsedMemberAccess
    val solvedFnType = methodInfo.methodFunctionType?.let { substitution.apply(it) }
    val methodParamType = (solvedFnType as? MezzeType.FunctionType)?.paramType
    val argExpr = if (methodParamType != null) buildExpr(expr.arguments, file, methodParamType)
                  else buildExpr(expr.arguments, file)

    val solvedCallSite = callReturnTypes[loc]?.let { substitution.apply(it) }
    val produced = when {
        solvedCallSite != null && solvedCallSite !is MezzeType.VarType -> solvedCallSite
        solvedFnType is MezzeType.FunctionType -> substitution.apply(solvedFnType.returnType)
        else -> contextType?.let { substitution.apply(it) }
    } ?: unreachedPosition("static ability op with no solved, declared or context type")

    val abilityName = methodInfo.abilityName
    val abilityHash = abilityName?.let { name ->
        registry.lookupAbility(name)?.let { it.contentHash ?: ParsedDeclHasher.hashAbilityDecl(it) }
            ?: importContext?.importedAbilities?.get(name)
                ?.let { it.contentHash ?: ParsedDeclHasher.hashAbilityDecl(it) }
    }
    val producedName = com.mezze.language.typechecker.util.receiverTypeName(produced)
    val implEntry = if (abilityHash != null && producedName != null) {
        abilityImpls.entries.firstOrNull { (_, info) ->
            info.abilityDeclHash == abilityHash && producedName in info.targetTypes
        }
    } else null
    // The produced type is still a VARIABLE: the impl is not this call's to pick.
    // The enclosing function declared a constraint for that variable, so the
    // dictionary it carries holds the operation — reference it and let the caller
    // supply the evidence, exactly as a receiver-taking operation does.
    if (implEntry == null && abilityHash != null && produced is MezzeType.VarType) {
        val fn = TypedVar(
            name = methodInfo.methodName,
            binding = BindingRef.AbilityOp(
                abilityHash = abilityHash,
                opName = methodInfo.methodName,
                typeVarName = produced.variable.name,
            ),
            type = solvedFnType ?: contextType?.let { substitution.apply(it) }
                ?: noContextType("ability op reference with no solved signature"),
            loc = memberAccess.memberLoc,
        )
        return TypedCall(
            function = fn,
            arg = argExpr,
            instantiation = null,
            isTailCall = isTailCallLocation(loc),
            type = produced,
            loc = loc,
        )
    }
    if (implEntry == null) {
        throw com.mezze.language.typechecker.TypeCheckError(
            message = "'$abilityName.${methodInfo.methodName}' takes no receiver, so the type it " +
                "produces is what chooses the implementation" +
                if (producedName == null) {
                    " — and nothing at this call determines that type. Say what the call " +
                    "produces: `let x: <Type> = $abilityName.${methodInfo.methodName} {…}`. " +
                    "An enclosing `with $abilityName 'a` does not carry it: an operation " +
                    "with no receiver is resolved at the call, never forwarded."
                } else {
                    ", and here it produces $producedName. No `impl $abilityName for " +
                    "$producedName` is in scope — write one, or import it."
                },
            loc = loc,
            errorType = com.mezze.language.typechecker.ErrorType.MISSING_FIELD,
            code = com.mezze.language.parsedast.diagnostics.ErrorCode.V0408_NO_IMPLEMENTATION,
        )
    }
    val opHash = Hash.computeOpHash(implEntry.value.contentHash, methodInfo.methodName)
    val fn = TypedVar(
        name = methodInfo.methodName,
        binding = BindingRef.ByHash(opHash),
        type = solvedFnType ?: contextType?.let { substitution.apply(it) }
            ?: unreachedPosition("method reference with no solved signature"),
        loc = memberAccess.memberLoc,
    )
    return TypedCall(
        function = fn,
        arg = argExpr,
        instantiation = null,
        isTailCall = isTailCallLocation(loc),
        type = produced,
        loc = loc,
    )
}

internal fun TypedAstBuilder.buildMethodCall(
    expr: ParsedFunctionCall,
    methodInfo: ConstraintResult.MethodCallInfo,
    file: String,
    contextType: MezzeType?,
): TypedCall {
    // An operation that declares no receiver is dispatched by the type it
    // PRODUCES, and there is nothing here to build a receiver from — building
    // one from `memberAccess.receiver` is what turned the ability NAME into a
    // variant and sent it to the runtime.
    if (methodInfo.receiverFieldName == null) {
        return buildStaticAbilityOpCall(expr, methodInfo, file, contextType)
    }

    val loc = expr.loc
    val memberAccess = expr.function as ParsedMemberAccess
    val receiverExpr = buildExpr(memberAccess.receiver, file)
    val receiverFieldName = methodInfo.receiverFieldName

    val solvedFnType = methodInfo.methodFunctionType?.let { substitution.apply(it) }

    // Thread the SOLVED method parameter type into the argument as its
    // contextType — the same #261 fix `buildFunctionCall` applies on the
    // ordinary-call path. Lets the callback lambda's params be stamped with
    // the concrete types the solver pinned (hover: `x : Item` not `x : 'a`).
    val methodParamType = (solvedFnType as? MezzeType.FunctionType)?.paramType
    val argExpr = if (methodParamType != null) buildExpr(expr.arguments, file, methodParamType)
                  else buildExpr(expr.arguments, file)

    // S2.3 (#358): dispatch is decided ONCE, in DispatchResolver. When this
    // inherent call site has a Resolution, READ its opHash (the resolved impl
    // content hash) — emit performs NO impl selection of its own. The legacy
    // re-pick is kept only as the FALLBACK for sites the resolver deferred
    // (no Resolution: a receiver still open past solve, or an ability/builtin
    // collision the resolver leaves to the downstream ability path). Under
    // invariants we assert the two agree wherever both produce an answer, so
    // "emit never selects an impl differing from DispatchResolver" is enforced
    // by the suite.
    val resolution = dispatchResolutions[expr.loc]
    val resolvedImplHash: Hash? = run {
        val fromResolver = resolution
            ?.takeIf { it.kind == com.mezze.language.typechecker.constraints.ResolutionKind.Inherent }
            ?.opHash
        val legacy: Hash? = legacyInherentImplHash(methodInfo, receiverExpr)
        if (invariantsEnabled && fromResolver != null && legacy != null && fromResolver != legacy) {
            throw IllegalStateException(
                "[S2.3 emit-never-selects] inherent '${methodInfo.methodName}' at ${expr.loc}: " +
                    "DispatchResolver chose impl ${fromResolver.hex.take(12)} but emit's legacy " +
                    "re-pick chose ${legacy.hex.take(12)}. Dispatch must be decided once."
            )
        }
        fromResolver ?: legacy
    }

    // #226: the STATIC ability-call form `Ability.method { it = v, ... }` carries
    // the receiver in the args' `it` field; `memberAccess.receiver` is the
    // ability NAME (a nullary variant), not the receiver value. Recover the real
    // receiver from the args so both the `fn` field-access receiver (built-in
    // dispatch reads its TYPE — must be `Int`, not the ability-variant VarType,
    // else built-in Eq/Ord route to the wrong dispatch and return a wrong result)
    // and the wrapped arg use the actual value.
    val staticReceiverValue: TypedExpr? =
        if (argExpr is TypedRecord) {
            argExpr.entries.filterIsInstance<TypedRecordEntry.Field>()
                .firstOrNull { it.name == receiverFieldName }?.value
        } else null

    // BUG 9: when two abilities declare the SAME operation name and a type
    // implements both, the emitted call carried neither a hash nor the
    // ability, so the compiler re-resolved by name against an index keyed on
    // the name — and `Weigh.rank {…} + Price.rank {…}` ran Weigh twice,
    // returning 6 where 106 was required, silently.
    //
    // The typechecker already decided correctly (methodInfo.abilityName is
    // `Weigh` for one call and `Price` for the other). Resolve the impl here
    // and emit it by hash, so the decision survives to the compiler instead of
    // being re-derived from a name that cannot distinguish them. This is the
    // shape C.3 generalizes — emit the evidence, do not re-resolve.
    //
    // Deliberately NARROW: only when the operation name is declared by more
    // than one ability in scope. Where the name is unambiguous the existing
    // path already resolves correctly, and every ability method call is far
    // too much surface to change for a bug that needs the ambiguity.
    // The ability this call resolved to, but ONLY when the operation name is
    // ambiguous — more than one ability DECLARATION in scope has an operation
    // of that name. Where the name is unambiguous nothing downstream can get
    // it wrong, and tagging every ability call would be far more surface than
    // the bug needs. Read from the declarations, not the impls: that is where
    // "who declares this operation" actually lives.
    val ambiguousAbilityHash: Hash? = run {
        if (methodInfo.isInherentMethod) return@run null
        val abilityName = methodInfo.abilityName ?: return@run null
        val abilityHash = registry.lookupAbility(abilityName)
            ?.let { it.contentHash ?: ParsedDeclHasher.hashAbilityDecl(it) }
            ?: importContext?.importedAbilities?.get(abilityName)
                ?.let { it.contentHash ?: ParsedDeclHasher.hashAbilityDecl(it) }
            ?: return@run null
        val declaringAbilities = registry.abilityDeclarations.values
            .filter { ad -> ad.operations.any { it.name == methodInfo.methodName } }
            .mapNotNull { it.contentHash ?: ParsedDeclHasher.hashAbilityDecl(it) }
            .distinct()
        if (declaringAbilities.size < 2) null else abilityHash
    }

    val ambiguousAbilityOpHash: Hash? = run {
        if (methodInfo.methodHash != null || methodInfo.isInherentMethod) return@run null
        val abilityHash = ambiguousAbilityHash ?: return@run null

        val receiverType = staticReceiverValue?.let { substitution.apply(it.type) } ?: return@run null
        val receiverName = com.mezze.language.typechecker.util.receiverTypeName(receiverType)
            ?: return@run null
        // The ABILITY is matched by declaration hash — that is the part the
        // name cannot distinguish and the whole reason this exists. The impl's
        // target is only available here as a name (`AbilityImplInfo.targetTypes`
        // is List<String>), which is the accepted fallback for a shape with no
        // hash to compare; it only has to separate impls WITHIN one ability.
        val implEntry = abilityImpls.entries.firstOrNull { (_, info) ->
            info.abilityDeclHash == abilityHash && receiverName in info.targetTypes
        } ?: return@run null
        Hash.computeOpHash(implEntry.value.contentHash, methodInfo.methodName)
    }

    val fn = if (ambiguousAbilityOpHash != null) {
        TypedVar(
            name = methodInfo.methodName,
            binding = BindingRef.ByHash(ambiguousAbilityOpHash),
            type = solvedFnType ?: contextType?.let { substitution.apply(it) }
                ?: noContextType("ability op reference with no solved signature"),
            loc = memberAccess.memberLoc,
        )
    } else if (methodInfo.methodHash != null) {
        val hash = methodInfo.methodHash!!
        TypedVar(
            name = methodInfo.methodName,
            binding = BindingRef.ByHash(hash),
            type = solvedFnType ?: contextType?.let { substitution.apply(it) }
                ?: unreachedPosition("method reference with no solved signature"),
            // Narrow member-name loc, not the whole `receiver.method` span,
            // so hover/goto-def on a chained call resolve to THIS method
            // rather than the outermost one. Safe for tree descent: the
            // receiver lives in the call's ARG record ({it: receiver}), not
            // inside this function node, so narrowing it strands nothing.
            loc = memberAccess.memberLoc
        )
    } else if (methodInfo.isInherentMethod && resolvedImplHash != null) {
        val implHash = resolvedImplHash
        val opHash = Hash.computeOpHash(implHash, methodInfo.methodName)
        TypedVar(
            name = methodInfo.methodName,
            binding = BindingRef.ByHash(opHash),
            type = solvedFnType ?: contextType?.let { substitution.apply(it) }
                ?: unreachedPosition("inherent method reference with no solved signature"),
            loc = memberAccess.memberLoc
        )
    } else if (staticReceiverValue != null) {
        // Built-in (or otherwise unresolved) static ability call: rebuild the
        // field-access on the real receiver VALUE so the compiler's receiverType
        // (fn.record.type) is the element type, giving the correct dispatch.
        // Tagged with the ability when the operation name is ambiguous — for a
        // GENERIC receiver the impl is genuinely unknown here (it arrives through
        // the caller's dictionary), so the ability is the only thing that can be
        // carried, and it is exactly what the dict key needs to stop two
        // abilities sharing one slot.
        TypedFieldAccess(
            staticReceiverValue, methodInfo.methodName, -1,
            solvedFnType ?: contextType?.let { substitution.apply(it) }
                ?: noContextType("static ability op field access with no solved signature"),
            memberAccess.memberLoc,
            abilityHash = ambiguousAbilityHash,
        )
    } else {
        // The solved signature, same as every branch above. Without it this
        // one built the callee with NO context, so `x.add {y}` — a METHOD —
        // reached the member-access arm with nothing to consult, fell past
        // `declaredShape(Float)` (not a record, because `add` is not a field),
        // and minted a placeholder blaming a "field read". That mislabelled
        // position is the largest entry in the placeholder census.
        // `solvedFnType` is null exactly when nothing resolved the method —
        // the builtin arithmetic ops reach here (`x.add {y}` on Int/Float).
        // The call site still KNOWS the arrow: the argument record was just
        // built, and the solver recorded this call's return type. Composing
        // the two is not a guess about the method, it is this call's own
        // signature stated from solved facts.
        // `solvedFnType` is null exactly when nothing resolved the method —
        // the builtin arithmetic ops reach here (`x.add {y}` on Int/Float).
        // The call site still KNOWS the arrow: the argument record was just
        // built, and the solver recorded this call's return type. Composing
        // the two is not a guess about the method, it is this call's own
        // signature stated from solved facts.
        val calleeArrow = solvedFnType ?: callReturnTypes[loc]
            ?.let { substitution.apply(it) }
            ?.let { MezzeType.FunctionType(paramType = argExpr.type, returnType = it) }
        val built = buildExpr(expr.function, file, calleeArrow)
        if (ambiguousAbilityHash != null && built is TypedFieldAccess) {
            built.copy(abilityHash = ambiguousAbilityHash)
        } else built
    }

    // #226: the STATIC ability-call form `Ability.method { it = v, ... }`
    // (desugared to a method call by the typechecker) already carries the
    // receiver in the args' `it` field — the `memberAccess.receiver` here is
    // the ability NAME (a nullary variant), NOT the receiver value. Building
    // `receiverExpr` from it and prepending `{ it = <Ability-variant> }`
    // would shadow the real `it = v` (duplicate field), so the body would
    // see the ability variant instead of the argument (silent wrong result,
    // or a crash for arithmetic bodies). Detect this structurally: if the
    // arg record ALREADY binds `receiverFieldName`, that field IS the
    // receiver — don't synthesize another from the name.
    val argsAlreadyHaveReceiver = argExpr is TypedRecord &&
        argExpr.entries.any { it is TypedRecordEntry.Field && it.name == receiverFieldName }

    val wrappedArg = when {
        argsAlreadyHaveReceiver -> argExpr as TypedRecord
        argExpr is TypedRecord -> {
            val receiverField = TypedRecordEntry.Field(receiverFieldName, receiverExpr, memberAccess.receiver.loc)
            val allEntries = listOf(receiverField) + argExpr.entries
            val allFieldTypes = mutableMapOf(receiverFieldName to receiverExpr.type)
            for (entry in argExpr.entries) {
                if (entry is TypedRecordEntry.Field) {
                    allFieldTypes[entry.name] = entry.value.type
                }
            }
            TypedRecord(allEntries, MezzeType.RecordType(allFieldTypes), argExpr.loc)
        }
        else -> {
            val receiverField = TypedRecordEntry.Field(receiverFieldName, receiverExpr, memberAccess.receiver.loc)
            val entries = listOf(receiverField)
            TypedRecord(entries, MezzeType.RecordType(mapOf(receiverFieldName to receiverExpr.type)), loc)
        }
    }

    // Prefer the solver's per-call-site result (callReturnTypes[loc], set by
    // tryGenerate*MethodCall) when it's concrete. methodFunctionType carries the
    // method's DECLARED signature whose return var is not always the var the
    // solver pinned at this call — reading `fn.type`'s return alone then leaves a
    // generic result unbound: `top.get_or { def = "?" }` on `Result Str Str`
    // recorded `'a` instead of `Str`. Fall back to the fn-type return, then the
    // context type.
    val solvedCallSite = callReturnTypes[expr.loc]?.let { substitution.apply(it) }
    val callReturnType = when {
        solvedCallSite != null && solvedCallSite !is MezzeType.VarType -> solvedCallSite
        else -> when (val ft = substitution.apply(fn.type)) {
            is MezzeType.FunctionType -> substitution.apply(ft.returnType)
            else -> solvedCallSite ?: contextType?.let { substitution.apply(it) }
        }
    } ?: unreachedPosition("method call with no solved return, no method arrow and no context")

    // Correct the method-var's displayed type to the ACTUAL call site. For a
    // deferred inherent method (receiver pinned only after solving, e.g.
    // `…eq {}.to_str {}` where the receiver is the Bool result of `.eq`),
    // `methodFunctionType` was captured against the wrong candidate receiver
    // (often the first / stale one — `{ it: Int }`), so hover/goto-def showed
    // the wrong receiver even though dispatch (resolvedImplHash) and the arg
    // record are correct. Rebuild the fn type from the real param record
    // (wrappedArg.type carries `{ it: <solvedReceiver>, … }`) and return type.
    // Preserve the method's effect row + ability constraints from the
    // original (solved) fn type — rebuilding only param/return here would
    // otherwise DROP them (default null), so a hover on `.send` lost its
    // `<Http>` even though the method body performs Http.
    fun correctTo(prior: MezzeType): MezzeType = MezzeType.FunctionType(
        paramType = substitution.apply(wrappedArg.type),
        returnType = callReturnType,
        effects = (prior as? MezzeType.FunctionType)?.effects,
        abilityConstraints = (prior as? MezzeType.FunctionType)?.abilityConstraints ?: emptyList(),
    )

    val correctedFn = when {
        fn is TypedVar && fn.name == methodInfo.methodName ->
            fn.copy(type = correctTo(substitution.apply(fn.type)))

        // An OPERATOR dispatches through the same machinery, but emits the op
        // as a field access off its receiver rather than as a named var — and
        // that form was skipped here purely for being the wrong node class.
        // It was the single largest remaining source of "the compiler finished
        // without a type": 441 of 451, every `a < b` and `x == 0` in the
        // stdlib. The receiver's type and the call's result are both known at
        // this point; `wrappedArg` is the very record the op is applied to.
        fn is TypedFieldAccess && fn.field == methodInfo.methodName &&
            fn.type.holdsUnknownPlaceholder() ->
            fn.copy(type = correctTo(substitution.apply(fn.type)))

        else -> fn
    }

    val isTailCall = isTailCallLocation(expr.loc)

    val typeArgsResolved = methodInfo.typeArgs.map { substitution.apply(it) }
    val abilityImplsResolved = methodInfo.abilityImpls.map { ref ->
        AbilityImplRef(
            abilityHash = ref.abilityHash,
            implHash = ref.implHash,
            targetType = substitution.apply(ref.targetType),
        )
    }
    val instantiation = if (typeArgsResolved.isEmpty() && abilityImplsResolved.isEmpty()) {
        null
    } else {
        TypeInstantiation(
            typeArgs = typeArgsResolved,
            abilityImpls = abilityImplsResolved,
        )
    }

    return TypedCall(
        function = correctedFn,
        arg = wrappedArg,
        instantiation = instantiation,
        isTailCall = isTailCall,
        type = callReturnType,
        loc = loc
    )
}
