package com.mezze.language.typechecker.compiler

import com.mezze.language.ast.EffectBindingKind
import com.mezze.language.ast.Keywords
import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.holdsTypeVar
import com.mezze.language.ast.holdsUnknownPlaceholder
import com.mezze.language.ast.MezzeType.*
import com.mezze.language.ast.TypeIdentity
import com.mezze.language.typechecker.solving.IdentityVerdict
import com.mezze.language.typechecker.solving.identityVerdict
import com.mezze.language.typechecker.solving.sameDeclaredIdentity
import com.mezze.language.ast.TypeVar
import com.mezze.language.ast.VariantAliases
import com.mezze.language.parsedast.Loc
import com.mezze.language.truffleir.*
import com.mezze.language.ast.ScopedEffectConstants
import com.mezze.language.ast.PrimitiveTypes
import com.mezze.language.typedast.*

/*
 * The dictionary / evidence subsystem, moved out of TypedASTCompiler.
 *
 * Deciding which calls need an evidence dictionary, which slot an ability
 * operation occupies, and how a dict is threaded to a call is one subject — and
 * it was ~850 lines at the top of a 5,580-line file, in front of the compiler's
 * actual work. It is also where the elaboration plan's evidence phase lands, so
 * it is worth being able to find.
 *
 * Extension functions rather than a class, for the same reason as
 * PatternCompilation: these read and write the compiler's own scan state. The
 * split mirrors ExpressionConstraintGenerator's across ExprFunctionConstraints
 * and its siblings.
 *
 * A pure move: no body was edited, only the indentation and the receiver.
 */

/**
 * Pre-scan all declarations (including ability impl operation bodies) to find
 * functions whose bodies contain VarType method calls to ability methods.
 * These functions will receive hidden dictionary params at compile time.
 *
 * Also transitively propagates dict requirements: if function A calls function B
 * which needs dict params, then A also needs those same dict params (to forward them).
 */
/**
 * Expose the discovered dict method requirements so the stdlib pre-compilation
 * step can accumulate them and pass them to the user-code compiler.
 */

/**
 * The same question answered from the DECLARED constraints instead of from the
 * bodies: a function constrained `with Ord 'a`, where `'a` is still a variable,
 * needs a dict for each of `Ord`'s operations.
 *
 * This is where the answer actually lives for a well-typed program, and it is
 * the standing measurement of how far [dictMethodsForHash] is from being
 * derivable rather than scanned (C.4/C.5). `DictJustificationGateTest` holds
 * the scan to it: nothing may demand a dict that no declared constraint
 * justifies.
 *
 * It does not replace the scan outright. A dict param carries ONE operation, and
 * a constraint names an ability, not which of its operations a body uses — so
 * substituting this for the scan would hand every `Ord`-constrained function
 * four dict params where it uses one.
 */

/**
 * Which functions need a `__dict_*` parameter, transitively.
 *
 * This is load-bearing — bypassing it breaks `SeqUserTypeCompareTest`
 * (nestedSort, variantSort, nestedLtFalse, nested3Sort, variantLtFalse) —
 * and it survives C.1 because it answers a DIFFERENT question than evidence
 * does. Evidence says WHICH IMPL discharges a constraint; this says WHICH
 * FUNCTIONS need a dict at all. Nothing emitted so far carries that, so
 * retiring the pass is blocked on C.3/C.4 emitting per-site dict
 * requirements.
 *
 * Its remaining guess is that it DERIVES the requirement by scanning bodies
 * for var-typed method calls, rather than reading it off the declared
 * constraints — which is where the answer actually lives for a well-typed
 * program. The magic iteration cap is gone (see the fixpoint below); the
 * body scan is what C.3/C.4 should replace.
 */
internal fun TypedASTCompiler.prescanForDictMethods(modules: List<TypedModule>) {
    dictMethodsForHash.clear()
    declaredOpTypeByHash.clear()
    declaredDictMethodsForHash.clear()
    // Seed with pre-lowered stdlib dict methods so call sites in the user
    // module correctly inject dicts for stdlib functions whose modules are
    // NOT in `additionalModules` during user compilation.
    for ((h, methods) in preloweredDictMethods) {
        dictMethodsForHash[h] = methods
    }

    // Phase 1: find functions with direct VarType method calls
    val allDecls = mutableListOf<TypedLetDecl>()
    for (module in modules) {
        for (decl in module.decls) {
            if (decl is TypedLetDecl) {
                allDecls.add(decl)
                val value = decl.value
                if (value is TypedLambda) {
                    val constrained = arrowHasAbilityConstraint(value.type)
                    val methods = scanExprForVarTypeMethodCalls(value.body, constrained)
                    if (methods.isNotEmpty()) {
                        dictMethodsForHash[decl.hash] = methods
                    }
                    declaredDictMethods(value.type).let {
                        if (it.isNotEmpty()) declaredDictMethodsForHash[decl.hash] = it
                    }
                }
            }
        }
    }

    // Phase 1b: scan ability impl operation bodies for VarType method calls.
    // Auto-generated codec impls have bodies like `fmt.encode_int { ... }` where
    // `fmt` is a VarType param. These need dict params just like regular functions.
    //
    // Bug B (#86): blanket-impl bodies also gate off `with <Ability>` constraints
    // on the enclosing arrow. When the impl is compiled at stdlib-precompile
    // time, the abilityOpIndex has zero entries for the inner method (e.g. no
    // `impl Encode` in scope yet), so the receiver-only check would miss the
    // dispatch. Collect the constraint typevars from the op's own FunctionType
    // and thread them so `receiver.method` on any of those typevars is marked
    // regardless of impl-index size.
    // Impl operation bodies, paired with their hashes, so phase 2 below can
    // propagate through them as well. Keeping the list is the whole point: an
    // impl op that needs no dict of its own may still CALL something that does.
    val implOpBodies = mutableListOf<Pair<Hash, TypedExpr>>()
    for (impl in abilityImpls) {
        for ((opName, opBody) in impl.operations) {
            val opHash = TypedASTCompiler.computeOpHash(impl.hash, opName)
            val bodyExpr = if (opBody is TypedLambda) opBody.body else opBody
            implOpBodies.add(opHash to bodyExpr)
            declaredOpTypeByHash[opHash] = opBody.type
            val constrained = arrowHasAbilityConstraint(opBody.type)
            val methods = scanExprForVarTypeMethodCalls(bodyExpr, constrained)
            if (methods.isNotEmpty()) {
                dictMethodsForHash[opHash] = methods
            }
            declaredDictMethods(opBody.type).let {
                if (it.isNotEmpty()) declaredDictMethodsForHash[opHash] = it
            }
        }
    }

    // Phase 2: transitively propagate dict requirements.
    // If function A's body calls function B (by hash) and B needs dict
    // methods, A also needs those same dict methods (to forward them).
    //
    // Census shows ~9 distinct dict-path call sites total, so the
    // dependency graph between dict-needing functions is tiny. A real
    // fixpoint converges in 1-2 passes; we cap at MAX_PASSES as a
    // defensive guard against degenerate cases. If we ever hit the cap
    // it means there's a deep call chain that needs investigation.
    // A REAL fixpoint, run to convergence. This used to stop after three
    // passes and log a warning if it was still changing — a magic bound that
    // could silently leave a function short of a dict it needs, which is the
    // shape of BUG 7 (an inherent op that never learned it must forward
    // `__dict_lt`, so `x < acc` fell to the primitive comparison node).
    //
    // Termination is structural, not by counting: `dictMethodsForHash` only
    // ever GROWS, and it is bounded by (functions x method names), so the
    // loop cannot run forever. That is the anti-hang invariant satisfied for
    // the right reason.
    //
    // Measured: converges in 1-2 passes today (98 compiles at 1, 16 at 2), so
    // the cap was never actually reached and removing it changes nothing now.
    // It goes anyway — a bound that silently truncates is the exact shape of
    // the defect above, and "it has not bitten yet" is not a termination
    // argument.
    var changed = true
    while (changed) {
        changed = false
        fun propagate(hash: Hash, body: TypedExpr) {
            val calleeDictMethods = scanExprForCallsToDictFunctions(body)
            if (calleeDictMethods.isEmpty()) return
            val existing = dictMethodsForHash[hash] ?: emptySet()
            val merged = existing + calleeDictMethods
            if (merged.size > existing.size) {
                dictMethodsForHash[hash] = merged
                changed = true
            }
        }
        for (decl in allDecls) {
            val value = decl.value
            if (value is TypedLambda) propagate(decl.hash, value.body)
        }
        // IMPL OPERATIONS propagate too. Phase 1b only asked whether an op's
        // body uses an ability op DIRECTLY; it never asked whether the body
        // CALLS something that needs a dict. An inherent op is exactly that
        // shape — `impl Seq 'a { min = { it } -> min { xs = it } }` forwards to
        // the constrained `seq::min` and does nothing else — so it was never
        // told it must forward `__dict_lt`. The caller of `xs.min {}` then
        // passed no dict, `seq::min` found none to use, and `x < acc` inside it
        // fell through to the primitive comparison node: a crash on a record or
        // variant element, and a SILENTLY WRONG answer for a newtype whose
        // ordering differs from the type it wraps.
        for ((opHash, body) in implOpBodies) propagate(opHash, body)
    }

}

/**
 * Scan an expression for calls to functions that need dictionary params.
 * Returns the set of dict method names required by those callees.
 */
internal fun TypedASTCompiler.scanExprForCallsToDictFunctions(expr: TypedExpr): Set<String> {
    val result = mutableSetOf<String>()
    scanExprForDictFunctionCalls(expr, result)
    return result
}

internal fun TypedASTCompiler.scanExprForDictFunctionCalls(expr: TypedExpr, out: MutableSet<String>) {
    when (expr) {
        is TypedCall -> {
            val fn = expr.function
            // Check if the callee is a ByHash reference to a dict-needing function
            if (fn is TypedVar && fn.binding is BindingRef.ByHash) {
                val calleeHash = (fn.binding as BindingRef.ByHash).hash
                val calleeDictMethods = dictMethodsForHash[calleeHash]
                if (calleeDictMethods != null) {
                    // Check if arg types can resolve the dict methods.
                    // If not (VarType args), the caller needs to forward its own dict params.
                    val concreteTypes = mutableListOf<MezzeType>()
                    if (expr.arg is TypedRecord) {
                        for (entry in (expr.arg as TypedRecord).entries) {
                            if (entry is TypedRecordEntry.Field) {
                                val refreshed = substitution.apply(entry.value.type)
                                val recovered = resolveVariantReceiverType(entry.value, refreshed) ?: refreshed
                                // Extract concrete element types from seq/list literals
                                // so VarType-wrapped containers (e.g. `['a]`)
                                // can still resolve the element-level impl.
                                val elementType = extractConcreteElementTypeFromExpr(entry.value)
                                if (elementType != null) concreteTypes.add(elementType)
                                concreteTypes.add(recovered)
                                collectDeepConcreteTypes(entry.value, concreteTypes)
                            }
                        }
                    }
                    // Filter out types that still contain VarType before resolution.
                    // A `['a]` could match a container-level impl like
                    // `impl Ord for ['a]` when the dict is actually for the element
                    // type — which is unknown. Only fully-concrete types (or types
                    // where expression-based extraction recovered the element type)
                    // can be safely resolved.
                    val resolvableTypes = concreteTypes.filter { !containsVarType(it) }
                    // Try to resolve each dict method from the concrete types.
                    // Only forward to caller when resolution actually fails.
                    for (methodName in calleeDictMethods) {
                        // Callers forward PLAIN slots; a per-var split is decided by
                        // each function's own body, never inherited from a callee.
                        val calleeMethod = com.mezze.language.ast.MagicParams.methodOfKey(methodName)
                        val resolved = resolveOpHashFromConcreteTypes(calleeMethod, resolvableTypes)
                        if (resolved == null) {
                            out.add(calleeMethod)
                        }
                    }
                }
            }
            if (fn is TypedFieldAccess) {
                scanExprForDictFunctionCalls(fn.record, out)
            } else {
                scanExprForDictFunctionCalls(fn, out)
            }
            scanExprForDictFunctionCalls(expr.arg, out)
        }
        is TypedLambda -> {
            // #195 B2: descend into nested lambdas so transitive dict-passing
            // sees calls inside HOF callbacks (e.g. seq::fold {f = {x} -> ...}).
            scanExprForDictFunctionCalls(expr.body, out)
        }
        is TypedBlock -> {
            for (s in expr.statements) {
                when (s) {
                    is TypedStatement.Let -> scanExprForDictFunctionCalls(s.value, out)
                    is TypedStatement.Expr -> scanExprForDictFunctionCalls(s.expr, out)
                }
            }
            scanExprForDictFunctionCalls(expr.result, out)
        }
        is TypedIf -> {
            scanExprForDictFunctionCalls(expr.condition, out)
            scanExprForDictFunctionCalls(expr.thenBranch, out)
            scanExprForDictFunctionCalls(expr.elseBranch, out)
        }
        is TypedMatch -> {
            scanExprForDictFunctionCalls(expr.scrutinee, out)
            for (c in expr.cases) {
                c.guard?.let { scanExprForDictFunctionCalls(it, out) }
                scanExprForDictFunctionCalls(c.body, out)
            }
        }
        is TypedRecord -> {
            for (e in expr.entries) {
                when (e) {
                    is TypedRecordEntry.Field -> scanExprForDictFunctionCalls(e.value, out)
                    is TypedRecordEntry.Spread -> scanExprForDictFunctionCalls(e.expr, out)
                }
            }
        }
        is TypedFieldAccess -> scanExprForDictFunctionCalls(expr.record, out)
        is TypedVariantConstruct -> expr.payload?.let { scanExprForDictFunctionCalls(it, out) }
        is TypedRecordUpdate -> {
            scanExprForDictFunctionCalls(expr.record, out)
            for (e in expr.updates) {
                if (e is TypedRecordEntry.Field) scanExprForDictFunctionCalls(e.value, out)
            }
        }
        is TypedPerform -> {
            // Handler PARAMS are expressions too. Skipping them under-reports
            // what a function needs, and under-reporting a dict is BUG 7's
            // shape: the callee finds no dict and falls to the primitive op.
            for (h in expr.handlers) h.params?.let { scanExprForDictFunctionCalls(it, out) }
            scanExprForDictFunctionCalls(expr.body, out)
        }
        is TypedPerformOp -> scanExprForDictFunctionCalls(expr.arg, out)
        is TypedPipe -> {
            scanExprForDictFunctionCalls(expr.left, out)
            scanExprForDictFunctionCalls(expr.right, out)
        }
        is TypedSeq -> {
            for (e in expr.elements) {
                when (e) {
                    is TypedSeqElement.Regular -> scanExprForDictFunctionCalls(e.expr, out)
                    is TypedSeqElement.Spread -> scanExprForDictFunctionCalls(e.expr, out)
                }
            }
        }
        is TypedStringLit -> {
            for (p in expr.parts) {
                if (p is TypedStringPart.Interpolation) scanExprForDictFunctionCalls(p.expr, out)
            }
        }
        is TypedVar, is TypedIntLit, is TypedNatLit, is TypedFloatLit,
        is TypedFracLit, is TypedBoolLit, is TypedCharLit -> {}
    }
}

/**
 * Recursively scan a TypedExpr for VarType method calls (i.e., calls where
 * the receiver is VarType and the method is a known ability operation).
 * Returns the set of ability method names found.
 *
 * `activeConstraints` — the set of TypeVar ids that the enclosing function's
 * `with <Ability>` clause quantifies over. A receiver VarType whose id is in
 * this set is marked for dict-passing regardless of impl-index population, so
 * blanket-impl bodies (`with Encode JsonC 'a`) dispatch correctly even when
 * the impl-index has zero entries at stdlib-precompile time (Bug B / #86).
 */
internal fun TypedASTCompiler.scanExprForVarTypeMethodCalls(
    expr: TypedExpr,
    arrowIsConstrained: Boolean = false
): Set<String> {
    val result = mutableSetOf<String>()
    scanExprForVarTypeMethods(expr, result, arrowIsConstrained)
    return normalizeDictKeys(result)
}

/** Raw scan keys are `method@var`. A function needing a method for ONE type
 *  variable keeps the plain `method` slot (unchanged wire shape); only a
 *  function needing the same method at two or more variables gets a slot per
 *  variable — the case one shared slot cannot serve (`Result 'a 'e`).
 *
 *  BUG 9 / task C.2: this groups by METHOD NAME, which is exactly where two
 *  abilities declaring the same operation collapse into one slot. A function
 *  constrained `with Weigh 'a, Price 'a` raises `rank@a` from both calls, they
 *  group together, `vars.size == 1`, and the result is a single plain `rank`
 *  that serves both — so whichever fills it answers for the other, silently.
 *  The grouping key has to become (ability, method); getting the ability here
 *  needs it carried on the emitted node, because the op index this scan
 *  consults is keyed on the name and by construction cannot tell them apart.
 *  The CONCRETE-receiver half of the same bug is already fixed in emit. */
internal fun TypedASTCompiler.normalizeDictKeys(raw: Set<String>): Set<String> =
    // Grouped by (ABILITY, method). Grouping by method alone was BUG 9: a
    // function constrained `with Weigh 'a, Price 'a` raises `rank@a` from both
    // calls, they grouped under `rank`, vars.size was 1, and the result was ONE
    // plain `rank` slot serving both abilities — whichever filled it answered
    // for the other, silently. A key with no ability segment groups under null
    // exactly as before, so nothing unambiguous changes shape.
    raw.groupBy {
        com.mezze.language.ast.MagicParams.abilityOfKey(it) to
            com.mezze.language.ast.MagicParams.methodOfKey(it)
    }.flatMap { (group, keys) ->
        val (ability, method) = group
        val vars = keys.mapNotNull { com.mezze.language.ast.MagicParams.typeVarOfKey(it) }.toSet()
        when {
            vars.size >= 2 -> keys.filter { com.mezze.language.ast.MagicParams.typeVarOfKey(it) != null }
            // One variable but a DISAMBIGUATING ability: keep the ability, drop
            // the variable — the slot must stay distinct from the other
            // ability's, and the plain shape would collide again.
            ability != null -> listOf("$method${com.mezze.language.ast.MagicParams.ABILITY_SEP}$ability")
            else -> listOf(method)
        }
    }.toSet()

/** The dict slot NAME an enclosing function holds for [method] at type var
 *  [typeVar]: the per-var slot when it has one, else the plain slot, else any
 *  per-var slot of that method (a caller that only holds one). Null if none. */
internal fun TypedASTCompiler.enclosingDictSlotIn(
    params: List<String>,
    captures: Map<String, Int>,
    method: String,
    typeVar: String?,
    abilityHash: String? = null,
): String? {
    fun has(n: String) = params.indexOf(n) >= 0 || captures.containsKey(n)
    val mp = com.mezze.language.ast.MagicParams
    // BUG 9 / C.2: an ABILITY-qualified slot is looked up first and, when the
    // caller asked for one, ONLY. Falling back to the plain slot would hand
    // back the other ability's dictionary — which is the bug, not a fallback.
    if (abilityHash != null) {
        val ability = abilityHash.take(12)
        if (typeVar != null) {
            mp.dictParamName(mp.dictKey(method, typeVar, abilityHash)).let { if (has(it)) return it }
        }
        mp.dictParamName("$method${mp.ABILITY_SEP}$ability").let { if (has(it)) return it }
        return null
    }
    if (typeVar != null) mp.dictParamName(mp.dictKey(method, typeVar)).let { if (has(it)) return it }
    mp.dictParamName(method).let { if (has(it)) return it }
    val prefix = mp.dictParamName(method) + mp.VAR_SEP
    (params + captures.keys).firstOrNull { it.startsWith(prefix) }?.let { return it }
    // An ability-qualified slot still serves an UNqualified request: the
    // caller simply had no ambiguity to resolve at its own site.
    val abilityPrefix = mp.dictParamName(method) + mp.ABILITY_SEP
    return (params + captures.keys).firstOrNull { it.startsWith(abilityPrefix) }
}
/** Frame index of the dictionary parameter [name] — a param sits after the
 *  captures, a capture at its own index. Null when this function carries no
 *  such slot. */
internal fun TypedASTCompiler.dictSlotIndex(name: String): Int? {
    val paramIdx = currentParamNames.indexOf(name)
    if (paramIdx >= 0) return currentCaptureCount + paramIdx
    return currentCaptureNames[name]
}

/** The dictionary slot an [BindingRef.AbilityOp] reference resolves to, or a
 *  loud failure: the reference exists only because the enclosing function
 *  declared the constraint that supplies it, so a miss is a compiler defect,
 *  never something to paper over. */
internal fun TypedASTCompiler.abilityOpDictSlot(binding: BindingRef.AbilityOp): Int {
    val name = enclosingDictSlot(binding.opName, binding.typeVarName, binding.abilityHash.hex)
    val index = name?.let { dictSlotIndex(it) }
    return index ?: throw com.mezze.language.typechecker.TypeCheckError(
        message = "'${binding.opName}' takes no receiver, so the type it produces is what " +
            "chooses the implementation — and nothing here determines that type. Say what " +
            "the call produces (`let x: <Type> = … ${binding.opName} {…}`), or declare the " +
            "constraint on the enclosing function so the caller supplies it " +
            "(`{ … } with <Ability> '${binding.typeVarName.removePrefix("'")} -> …`).",
        errorType = com.mezze.language.typechecker.ErrorType.CONSTRAINT_VIOLATION,
        code = com.mezze.language.parsedast.diagnostics.ErrorCode.V0408_NO_IMPLEMENTATION,
    )
}

internal fun TypedASTCompiler.enclosingDictSlot(method: String, typeVar: String?, abilityHash: String? = null): String? =
    enclosingDictSlotIn(currentParamNames, currentCaptureNames, method, typeVar, abilityHash)

/** Bind an op's impl type variables from the instance head: the impl whose op
 *  is [opHash] has target `T 'a 'e…`; unify it against each of [types] (the
 *  receiver / arg types at the site) and collect `'a → …`. Empty when the op
 *  is not an impl op or nothing binds. */
/**
 * The (ability hash, concrete type) pairs a called op's own `with` obligations
 * resolve to AT THIS CALL SITE.
 *
 * An obligation names a type variable — `with Decode JsonC 'a` names `'a` —
 * and that variable also appears in the op's declared return type
 * (`Result 'a DecodeError`). The call's return type is concrete here, so
 * matching the declared return against it binds the variable to THIS call's
 * own value for it.
 *
 * That binding is the only thing at a decode site that distinguishes two calls
 * of the same op: `"…".from_json {}` twice has identical receiver and argument
 * types (both `Str`) and differs ONLY in what it decodes INTO. Resolving the
 * dict from the receiver therefore hands both calls the same decoder, and the
 * second one decodes against the first one's shape.
 *
 * Structural throughout — no ability, type or op name is matched. An obligation
 * whose variable does not appear in the return type simply yields no pair, and
 * the caller falls through to its existing receiver/argument resolution.
 */
internal fun TypedASTCompiler.obligationDispatchTypes(opHash: Hash, callType: MezzeType): List<Pair<Hash?, List<MezzeType>>> {
    val declared = declaredOpTypeByHash[opHash] as? FunctionType ?: return emptyList()
    if (declared.abilityConstraints.isEmpty()) return emptyList()
    val bindings = HashMap<String, MezzeType>()
    harvestVarBindings(declared.returnType, substitution.apply(callType), bindings)
    if (bindings.isEmpty()) return emptyList()
    return declared.abilityConstraints.mapNotNull { constraint ->
        val args = constraint.typeArgs.map { arg ->
            if (arg is VarType) {
                bindings[arg.variable.name] ?: bindings[arg.variable.name.removePrefix("'")] ?: arg
            } else arg
        }
        // Partially-bound obligations dispatch nothing: a tuple with a hole in
        // it matches more than one impl, which is the ambiguity this exists to
        // remove. Leave those to the caller's existing resolution.
        if (args.any { it is VarType || containsVarType(it) }) null
        else constraint.abilityHash to args
    }
}

/**
 * The op a resolved obligation designates: the impl of that ability whose
 * class-argument tuple agrees with the obligation's, POSITION BY POSITION.
 *
 * The ordinary receiver-based lookup matches only the tuple's LAST slot (the
 * `for` type), which for a codec ability is the WIRE type — `Str` for every
 * JSON decoder there is. Two `from_json` calls decoding different shapes have
 * identical receivers and therefore identical last slots; what separates them
 * sits at the slot the obligation's variable occupies. Matching the whole
 * tuple uses that slot instead of discarding it.
 */
internal fun TypedASTCompiler.resolveOpFromObligation(method: String, abilityHash: Hash?, args: List<MezzeType>): Hash? {
    if (abilityHash == null || args.isEmpty()) return null
    val candidates = abilityOpIndex[method]?.filter { it.abilityHash == abilityHash } ?: return null
    return candidates.firstOrNull { op ->
        args.size <= op.targetTypes.size &&
            args.indices.all { i -> typeMatchesTarget(args[i], op.targetTypes[i]) }
    }?.opHash
}

internal fun TypedASTCompiler.bindImplVarsForOp(opHash: Hash, types: List<MezzeType>): Map<String, MezzeType> {
    val op = abilityOpIndex.values.asSequence().flatten().firstOrNull { it.opHash == opHash } ?: return emptyMap()
    val pattern = op.targetTypes.lastOrNull() ?: return emptyMap()
    val bindings = HashMap<String, MezzeType>()
    for (t in types) harvestVarBindings(pattern, substitution.apply(t), bindings)
    return bindings
}
/** The concrete type a qualified dict key's variable is bound to, or null. */
internal fun TypedASTCompiler.boundTypeForKey(key: String, bindings: Map<String, MezzeType>): MezzeType? =
    com.mezze.language.ast.MagicParams.typeVarOfKey(key)?.let { v -> bindings[v] ?: bindings["'$v"] }
        ?.takeIf { it !is VarType && !containsVarType(it) }

/**
 * True iff the arrow carries at least one `with <Ability>` constraint
 * that is still genuinely polymorphic after applying the final
 * substitution. A constraint like `NumAdd(VarType('a), IntType)` is
 * polymorphic only if `'a` remains a VarType after substitution —
 * if it resolves to `Int`, the constraint is `NumAdd(Int, Int)`,
 * which is a concrete built-in operation that doesn't need
 * dict-passing.
 *
 * Bug B (#86): a `with`-constrained arrow lets its body call ability
 * ops on a still-generic receiver (`{it: 'a} with Encode JsonC 'a
 * -> …` allows `it.encode {…}`). Any VarType-receiver method call
 * in such an arrow must be marked for dict-passing so the resolved
 * impl reaches the call site.
 *
 * Bug #X (dict-leak): recursive functions like `sum_to = {n} -> n +
 * sum_to {n-1}` generate a `NumAdd(VarType, IntType)` constraint
 * during inference. This constraint can leak onto enclosing function
 * types (e.g. `main`). Under the old `isNotEmpty()` check, the
 * leaked constraint caused `main` to be marked as constrained →
 * lambdas inside main got spurious `__dict_mul` captures → crash
 * when the callback's closureValues didn't match the inner BDSL
 * root's capture count. Applying substitution filters out these
 * resolved constraints correctly.
 */
/**
 * The dict operations [type]'s own declared constraints call for: every
 * operation of every ability it is constrained by, for a constraint whose type
 * argument is still a VARIABLE.
 *
 * A constraint on a type that resolved to something concrete needs no dict — the
 * impl is picked at compile time — and inference leaves those behind on the
 * arrow (see [arrowHasAbilityConstraint]), so the variable test is what separates
 * a live constraint from a spent one.
 */
internal fun TypedASTCompiler.declaredDictMethods(type: MezzeType): Set<String> {
    val fn = type as? MezzeType.FunctionType ?: return emptySet()
    if (fn.abilityConstraints.isEmpty()) return emptySet()
    return fn.abilityConstraints
        .filter { c -> c.typeArgs.any { containsVarTypeDeep(substitution.apply(it)) } }
        .flatMapTo(HashSet()) { c -> c.abilityHash?.let { abilityOpNames[it] } ?: emptySet() }
}

internal fun TypedASTCompiler.arrowHasAbilityConstraint(type: MezzeType): Boolean {
    if (type !is MezzeType.FunctionType) return false
    if (type.abilityConstraints.isEmpty()) return false
    // A function with ability constraints only needs dict-passing
    // if its parameter type is genuinely polymorphic (contains VarType
    // after substitution). Functions like `fib: {n: Int} -> Int`
    // can carry stale constraints from type inference (the VarType
    // in the constraint was resolved but the constraint wasn't
    // cleaned up). These functions don't need dict-passing because
    // all their operations can be resolved from concrete types at
    // compile time.
    //
    // The generalized type variables in these stale constraints are
    // NOT in the module substitution (they were let-generalized),
    // so substitution.apply() on the constraint's typeArgs doesn't
    // help — they remain VarType. But the function's own paramType
    // IS fully resolved under the substitution.
    val resolvedParam = substitution.apply(type.paramType)
    return containsVarTypeDeep(resolvedParam)
}

/** Like [containsVarType] but also recurses into record fields and
 *  function types — needed to detect VarType in parameter types. */
/**
 * "Does this type still hold an unsolved variable?" — the guard in front of
 * every dispatch decision in this file.
 *
 * Delegates to [holdsTypeVar], which walks via [MezzeType.anyChild], the one
 * walker that knows all of a type's positions. This was a hand-written
 * recursion covering records, applications, variants and an arrow's
 * param/return, ending in `else -> false`. It therefore answered "concrete"
 * for a `NewtypeType` wrapping a variable, for a standalone effect row, and
 * for an arrow whose only variable lives in its effect row or its ability
 * constraints.
 *
 * MEASURED before replacing it: 14 disagreements across the typechecker
 * suite, every one an arrow the local version called concrete and the
 * canonical walker did not.
 */
internal fun TypedASTCompiler.containsVarTypeDeep(type: MezzeType): Boolean = type.holdsTypeVar()

/**
 * Whether a method call on a VarType receiver should be marked for
 * dict-passing (both when SCANNING for a callee's dict params and when
 * EMITTING the call). Single source of truth for the two mirror sites so
 * collection and consumption can never disagree.
 *
 * `to_str` / `to_dbg` with no real ability op are the InspectIR path
 * (auto-derived, receiver's shape baked at compile time) — never
 * dict-dispatched. There are many same-named `to_str` (one per type),
 * which would otherwise trip the multiple-candidates rule and demand a
 * `__dict_to_str` the caller never passes.
 *
 * #18: comparison / arithmetic ops (`lt`, `eq`, `add`, …) also need a dict
 * when the receiver is a still-generic `'a` under a `with Ord`/`with Eq`/
 * `with Num*` constraint — e.g. `x < acc` inside `list::min`'s body. The
 * old `methodName !in inlineableOps` guard excluded these entirely, so the
 * `<` fell through to the native comparator and crashed on a user variant
 * (`Cannot compare MezzeVariant`). The exclusion existed only so PRIMITIVE
 * operands inline (no dict) — but a primitive receiver is a concrete type,
 * not a VarType, so it never enters this branch; and a VarType receiver
 * that mono later specializes to a primitive gets its dict param stripped
 * during specialization. So for `inlineableOps` we require a REAL ability
 * op of that name to exist (`opCandidates` non-empty) in addition to the
 * constrained-arrow / multi-candidate rule — a bare `'a` with no `Ord`/`Eq`
 * impl anywhere in scope still falls through to the structural default
 * rather than demanding a dict nobody supplies.
 */
internal fun TypedASTCompiler.shouldDictDispatchVarTypeOp(
    methodName: String,
    arrowIsConstrained: Boolean
): Boolean {
    val isInspectSugar =
        methodName == Keywords.TO_STR || methodName == Keywords.TO_DBG
    if (isInspectSugar) return false
    val opCandidates = abilityOpIndex[methodName]
    val hasRealOp = opCandidates != null && opCandidates.isNotEmpty()
    // Inlineable ops (comparisons / arithmetic) only dict-dispatch when a
    // real ability op backs them — otherwise a VarType `'a` with no impl
    // takes the structural/native default (matching the old behavior of
    // never entering this branch for inlineable ops).
    if (methodName in inlineableOps && !hasRealOp) return false
    // #18 guard: in user compilation (!isStdlibMode), only flag inlineable
    // ops when the enclosing arrow explicitly carries an ability constraint
    // (`with Ord 'a`, `with NumMul 'a`, etc.). Without the constraint guard,
    // every generalized user function like `{x} -> x * 2` gets `__dict_mul`
    // in its frame — and CallIndirect callers (HOF callbacks, closures)
    // crash because they can't inject dicts.
    //
    // During stdlib compilation (isStdlibMode), we allow inlineable ops
    // unconditionally because stdlib functions (sort, min, max) are always
    // called via CallDirect, which can inject dicts at the call site.
    if (methodName in inlineableOps && !isStdlibMode && !arrowIsConstrained) return false
    return when {
        arrowIsConstrained -> true
        opCandidates != null && opCandidates.size > 1 -> true
        // For a non-inlineable op the historical rule required >1 candidate.
        // For an inlineable op backed by a real ability (Ord/Eq/Num), a
        // single candidate still needs the dict at a polymorphic call site,
        // since the receiver stays generic until mono specializes it.
        methodName in inlineableOps && hasRealOp -> true
        else -> false
    }
}

internal fun TypedASTCompiler.scanExprForVarTypeMethods(
    expr: TypedExpr,
    out: MutableSet<String>,
    arrowIsConstrained: Boolean = false,
    // Concrete types of lambda params in scope (from their ANNOTATIONS, e.g.
    // `{ x: Rt } -> …`). Used to recover an op receiver's concrete type when the
    // node's own type was laundered to VarType(unknown) — see the field-recovery
    // in the field-access op check below.
    paramEnv: Map<String, MezzeType> = emptyMap()
) {
    when (expr) {
        is TypedCall -> {
            val fn = expr.function
            // An operation with NO RECEIVER says which dictionary it wants —
            // there is no variable-typed receiver for the walk below to notice,
            // so the requirement would otherwise be invisible and the slot would
            // never be allocated.
            val staticOp = (fn as? TypedVar)?.binding as? BindingRef.AbilityOp
            if (staticOp != null) {
                out.add(
                    com.mezze.language.ast.MagicParams.dictKey(
                        staticOp.opName, staticOp.typeVarName, staticOp.abilityHash.hex,
                    ),
                )
            }
            if (fn is TypedFieldAccess) {
                val methodName = fn.field
                // Refresh against the final substitution so an inlineable op
                // whose receiver is a resolved primitive (e.g. `rank {..} <
                // rank {..}` where `rank` returns Int, but the sub-expr's
                // node type was still a var at build time) is NOT mistaken
                // for a generic-'a comparison and given a spurious dict param.
                // #18: without this, an `impl Ord for T` body's own internal
                // `<` on Int would demand a `__dict_lt` its caller never
                // supplies, misaligning the arg record at runtime.
                val receiverType = substitution.apply(fn.record.type)
                // Bug B (#86): a VarType receiver whose id is in the enclosing
                // arrow's `with`-constraint typevar set needs dict-passing even
                // when abilityOpIndex has zero entries at this compile step
                // (blanket-impl body precompiled before any user impl exists).
                // Otherwise fall back to the impl-index population check: only
                // flag as needing dict if there are multiple candidates
                // (ambiguous — can't resolve statically). Single candidate =
                // resolved in compileCall without needing a dict param.
                //
                // Frame-slot crash fix: a binary inlineable op (eq/neq/lt/… with
                // an `r` co-operand) is CONCRETELY resolvable when its CO-OPERAND
                // is a concrete type — the co-operand pins the impl (e.g. `x.m ==
                // Get` resolves `Eq M` from the concrete `Get`) even if the
                // receiver `x.m`'s node type is still an unresolved VarType (as
                // happens for a variant field read inside a closure returned from
                // a generic-`'it` inherent-method op — the field's concrete type
                // isn't stamped through `'it`). Requesting a dict there is wrong:
                // no dict exists (concrete impl = direct call), so the closure
                // frame is built without it and the emitted read runs off the end
                // (ArrayIndexOutOfBounds at the returned-closure call). Skip the
                // dict request when the co-operand resolves concrete.
                // Frame-slot crash recovery: the op receiver's node type may be
                // laundered to VarType(unknown) for a field read inside a closure
                // returned from a generic-`'it` inherent-method op (the field's
                // concrete type isn't stamped through `'it`). But when the op's
                // receiver is `param.field` and `param` has a concrete RecordType
                // ANNOTATION in scope (`{ x: Rt } -> x.m == …`), the real receiver
                // type is Rt.fields[field] = M — concrete → the op resolves to a
                // concrete impl (direct call, NO dict). Requesting a dict here
                // wrongly gives the enclosing op a spurious `__dict_eq` param whose
                // frame slot the inherent-method dispatch doesn't supply →
                // ArrayIndexOutOfBounds when the returned closure runs. So treat
                // the op as concretely resolvable when the annotation recovers a
                // concrete field type.
                // The op receiver is `fn.record`. For `x.m == Get` the desugared
                // op is `(x.m).eq(Get)`, so `fn.record` is the field access
                // `x.m` = TypedFieldAccess(record = TypedVar(x), field = "m").
                // Recover its type from x's concrete annotation: Rt.fields["m"].
                val recoveredConcrete = receiverType is VarType && run {
                    val opReceiver = fn.record
                    if (opReceiver !is TypedFieldAccess) return@run false
                    val base = opReceiver.record
                    if (base !is TypedVar) return@run false
                    // The op receiver is `base.field` where `base` is a lambda
                    // param annotated with a CONCRETE type (e.g. `{ x: Rt }`).
                    // `Rt` surfaces as a nominal TypeApplication or an expanded
                    // RecordType — either way it is NOT a type variable, so
                    // `base.field` is a field of a concrete type and the op
                    // resolves to a concrete impl (direct call, no dict). We don't
                    // need the field's exact type — concrete base ⇒ concrete
                    // receiver for dispatch purposes.
                    val baseAnn = paramEnv[base.name]?.let { substitution.apply(it) } ?: return@run false
                    // "Concrete" means no type variable anywhere in it: `['a]` or
                    // `Box 'a` is a shape, not a concrete type, and a field read off
                    // it can still be the element `'a` that needs the dict.
                    baseAnn !is VarType && !containsVarType(baseAnn)
                }
                if (receiverType is VarType && !recoveredConcrete &&
                    shouldDictDispatchVarTypeOp(methodName, arrowIsConstrained)) {
                    // BUG 9 / C.2: include the ABILITY the emitted node carries.
                    // Without it two abilities declaring this operation raise the
                    // SAME key for the same variable, normalizeDictKeys groups
                    // them, and one slot serves both. `fn.abilityHash` is set only
                    // where the name is ambiguous, so unambiguous calls keep the
                    // byte-identical key they always had.
                    out.add(
                        com.mezze.language.ast.MagicParams.dictKey(
                            methodName, receiverType.variable.name, fn.abilityHash?.hex,
                        ),
                    )
                }
                scanExprForVarTypeMethods(fn.record, out, arrowIsConstrained, paramEnv)
            } else {
                scanExprForVarTypeMethods(fn, out, arrowIsConstrained, paramEnv)
            }
            scanExprForVarTypeMethods(expr.arg, out, arrowIsConstrained, paramEnv)
        }
        is TypedLambda -> {
            // #195 B2: descend into nested lambdas. When the inner body calls
            // `x.method` on a VarType receiver (e.g. inside a `seq::fold`
            // callback), the OUTER function holds the constraint and must
            // request `__dict_<method>` so the inner lambda can capture it
            // from the outer's frame. Skipping here used to make the inner
            // call fall through to plain field access at runtime → crash.
            //
            // Bug B (#86): an inner arrow that carries its own `with`
            // constraint promotes to arrowIsConstrained; the outer scan
            // already runs constrained when the OUTER arrow was.
            val innerConstrained = arrowIsConstrained || arrowHasAbilityConstraint(expr.type)
            // Extend the param env with THIS lambda's params (their annotated
            // types), so a nested `x.field` op can recover x's concrete type.
            val innerParamEnv = paramEnv + expr.params.associate { it.name to it.type }
            scanExprForVarTypeMethods(expr.body, out, innerConstrained, innerParamEnv)
        }
        is TypedBlock -> {
            for (s in expr.statements) {
                when (s) {
                    is TypedStatement.Let -> scanExprForVarTypeMethods(s.value, out, arrowIsConstrained, paramEnv)
                    is TypedStatement.Expr -> scanExprForVarTypeMethods(s.expr, out, arrowIsConstrained, paramEnv)
                }
            }
            scanExprForVarTypeMethods(expr.result, out, arrowIsConstrained, paramEnv)
        }
        is TypedIf -> {
            scanExprForVarTypeMethods(expr.condition, out, arrowIsConstrained, paramEnv)
            scanExprForVarTypeMethods(expr.thenBranch, out, arrowIsConstrained, paramEnv)
            scanExprForVarTypeMethods(expr.elseBranch, out, arrowIsConstrained, paramEnv)
        }
        is TypedMatch -> {
            scanExprForVarTypeMethods(expr.scrutinee, out, arrowIsConstrained, paramEnv)
            for (c in expr.cases) {
                c.guard?.let { scanExprForVarTypeMethods(it, out, arrowIsConstrained, paramEnv) }
                scanExprForVarTypeMethods(c.body, out, arrowIsConstrained, paramEnv)
            }
        }
        is TypedRecord -> {
            for (e in expr.entries) {
                when (e) {
                    is TypedRecordEntry.Field -> scanExprForVarTypeMethods(e.value, out, arrowIsConstrained, paramEnv)
                    is TypedRecordEntry.Spread -> scanExprForVarTypeMethods(e.expr, out, arrowIsConstrained, paramEnv)
                }
            }
        }
        is TypedFieldAccess -> scanExprForVarTypeMethods(expr.record, out, arrowIsConstrained, paramEnv)
        is TypedVariantConstruct -> expr.payload?.let { scanExprForVarTypeMethods(it, out, arrowIsConstrained, paramEnv) }
        is TypedRecordUpdate -> {
            scanExprForVarTypeMethods(expr.record, out, arrowIsConstrained, paramEnv)
            for (e in expr.updates) {
                if (e is TypedRecordEntry.Field) scanExprForVarTypeMethods(e.value, out, arrowIsConstrained, paramEnv)
            }
        }
        is TypedPerform -> {
            for (h in expr.handlers) {
                h.params?.let { scanExprForVarTypeMethods(it, out, arrowIsConstrained, paramEnv) }
            }
            scanExprForVarTypeMethods(expr.body, out, arrowIsConstrained, paramEnv)
        }
        is TypedPerformOp -> scanExprForVarTypeMethods(expr.arg, out, arrowIsConstrained, paramEnv)
        is TypedPipe -> {
            scanExprForVarTypeMethods(expr.left, out, arrowIsConstrained, paramEnv)
            scanExprForVarTypeMethods(expr.right, out, arrowIsConstrained, paramEnv)
        }
        is TypedSeq -> {
            for (e in expr.elements) {
                when (e) {
                    is TypedSeqElement.Regular -> scanExprForVarTypeMethods(e.expr, out, arrowIsConstrained, paramEnv)
                    is TypedSeqElement.Spread -> scanExprForVarTypeMethods(e.expr, out, arrowIsConstrained, paramEnv)
                }
            }
        }
        is TypedStringLit -> {
            for (p in expr.parts) {
                if (p is TypedStringPart.Interpolation) scanExprForVarTypeMethods(p.expr, out, arrowIsConstrained, paramEnv)
            }
        }
        // Leaf nodes: no children
        is TypedVar, is TypedIntLit, is TypedNatLit, is TypedFloatLit,
        is TypedFracLit, is TypedBoolLit, is TypedCharLit -> {}
    }
}

data class CompileResult(
    val functions: List<FunctionIR>,
    val performerRecords: List<PerformerRecord>
)

data class PerformerRecord(
    val performerHash: String,
    val effectHash: String,
    val operationHashes: List<String>,
    val operationNames: List<String>
)

    /**
     * STRUCTURAL constraint binding for an ability-op callee — the
     * instance-head step (`instance Eq a => Eq [a]`: bind `a` from the
     * HEAD, then resolve the constraint on that binding). `fn.type` on a
     * mono-rewritten `Var(eq, ByHash)` is the field access's already
     * SUBSTITUTED arrow (`{it: [P], r: [P]}`), so the unify above has no
     * `'a` left to bind: the constraint arg stays generic, forwarding
     * finds nothing (a captureless comparator), and resolution falls to
     * the ARG types — where `[P]` matches the blanket `impl Eq for ['a]`
     * and the callee's `__dict_eq` becomes eq-for-`[P]` instead of `P`'s
     * eq. `[[P]] == [[P]]` then handed a P record to the seq op
     * ("Expected MezzeSeq for field 'l'"). NOTE the op's declared ARROW
     * is no use here either: it is LAUNDERED — its params are vars named
     * after the record fields (`{it: 'it, r: 'r}`), so unifying it binds
     * `it`/`r`, never `'a`. The structure survives only in the impl's
     * TARGET pattern (`['a]`): unify THAT against the receiver field's
     * actual type and take the impl's forall var from the bindings —
     * exactly what the field-access site does. The op is found by HASH,
     * never by name; non-op callees and failed bindings keep the
     * previous behaviour verbatim.
     *
     * Extracted from `compileCall` under the file-size ratchet rather than raising
     * its bound: it is a self-contained question — "what concrete type does this
     * callee's instance head bind to?" — and splitting it out is what the ratchet
     * is for.
     */
internal fun TypedASTCompiler.structuralConstraintTypesFor(calleeHash: Hash, expr: TypedCall): List<MezzeType> {
    // Locate the op and its impl by HASH.
    var found: Pair<TypedAbilityImpl, String>? = null
    for (impl in abilityImpls) {
        for (opName in impl.operations.keys) {
            if (TypedASTCompiler.computeOpHash(impl.hash, opName) == calleeHash) {
                found = impl to opName
                break
            }
        }
        if (found != null) break
    }
    val (impl, opName) = found ?: return emptyList()
    // EVERY type parameter, in order — `Result 'a 'e` as readily as
    // `['a]`. `singleOrNull` before left a two-parameter impl with NO
    // binding, so a derived `Arbitrary for Result` hit an unfilled
    // slot. `boundTypeForKey` below keys each slot on its own
    // variable, so more than one is not a crossing hazard.
    val varNames = impl.forallVars.map { it.name }
    if (varNames.isEmpty()) return emptyList()
    val pattern = impl.targetTypes.lastOrNull() ?: return emptyList()
    // The receiver field is the op's OWN first param, read off its
    // lambda — the same structural derivation TypedAstBuilder uses.
    //
    // S-3: an op with NO params has no receiver to read, and the type
    // it PRODUCES plays exactly the receiver's role in the instance
    // head. `arbitrary: {} with Arbitrary 'a -> ['a]` at `[Int]` binds
    // `'a = Int` from the return just as `eq: {it: ['a], …}` binds it
    // from `it`. Discriminated on the op having no parameters —
    // structural, not a name — and this block previously bailed here,
    // which is why every parameterised container reached a dictionary
    // slot nobody filled (`DictFallback[arbitrary]`).
    val opLambda = impl.operations[opName] as? TypedLambda
    val recvField = opLambda?.params?.firstOrNull()?.name
    val headActual = if (recvField == null) {
        if (opLambda == null) return emptyList()
        substitution.apply(expr.type)
    } else {
        (expr.arg as? TypedRecord)?.entries
            ?.filterIsInstance<TypedRecordEntry.Field>()
            ?.firstOrNull { it.name == recvField }
            ?.let { substitution.apply(it.value.type) }
            ?: return emptyList()
    }
    val bindings = mutableMapOf<String, MezzeType>()
    harvestVarBindings(pattern, headActual, bindings)
    // ALL or NOTHING: a short list hands unresolved slots whichever
    // type resolved first, and a wrong impl is worse than none.
    return varNames.map { name ->
        bindings[name]?.takeIf { it !is VarType && !containsVarType(it) }
            ?: return emptyList()
    }
}
