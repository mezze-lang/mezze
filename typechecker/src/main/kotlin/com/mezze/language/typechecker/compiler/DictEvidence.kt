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
                    val methods = scanExprForVarTypeMethodCalls(value.body, constrained, value.type)
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
            // A DECLARED `with` IS A REQUIREMENT, whether or not the body happens to
            // call the op on the variable itself. The scan below asks what the body
            // USES; it finds `x.show {}` and misses an op that FORWARDS instead:
            //
            //   direct: { it: A, x: 'p } with Show 'p -> Int = { it, x } -> x.show {}
            //   fwd:    { it: A, x: 'p } with Show 'p -> Int = { it, x } -> it.direct { x = x }
            //
            // `fwd`'s receiver is concrete, so no VarType method call is there to
            // find, and `fwd` compiled with `params=[it, x]` — NO `__dict_show` to
            // forward. Phase 2 does not rescue it either: it matches `BindingRef.ByHash`
            // callees, and an ability-op method call is not one. So `a.fwd { x = b }`
            // resolved `show` from the only thing in reach — the RECEIVER — and
            // answered 1 (`Show A`) where 2 (`Show B`) is correct, on both the
            // interpreter and the monomorphised build.
            //
            // The declared set is already computed just below for
            // `declaredDictMethodsForHash`; this unions it into the set that decides
            // PARAMETERS. For an op whose body does call the constrained op directly
            // the two sets agree and nothing changes — it only adds a slot where the
            // scan was blind, which is exactly the forwarding shape.
            val methods = scanExprForVarTypeMethodCalls(bodyExpr, constrained, opBody.type) +
                declaredDictMethods(opBody.type)
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
                    // A lambda with its own dictionary PARAMETERS is supplied by its
                    // call sites; its needs are not the enclosing function's.
                    is TypedStatement.Let ->
                        if (ownDictMethodsOf(s.value).isEmpty()) scanExprForDictFunctionCalls(s.value, out)
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
        // The handler parameters are scanned where they were written, on the
        // enclosing `perform`.
        is TypedBuildTime -> scanExprForDictFunctionCalls(expr.expr, out)
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
    arrowIsConstrained: Boolean = false,
    /** The enclosing arrow. Needed because an ability can dispatch on a type
     *  argument that is NOT the receiver — see the `Decode` case below. */
    arrowType: MezzeType? = null,
): Set<String> {
    val result = mutableSetOf<String>()
    scanExprForVarTypeMethods(expr, result, arrowIsConstrained, arrowType)
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
/**
 * The dictionaries [expr] has to capture from the enclosing frame, as
 * (name, parent slot index).
 *
 * ONE computation, because TWO callers have to agree on it EXACTLY. A
 * recursive do-block `let` lambda registers the hash its self-calls will
 * target before its body compiles, and the emission computes the hash it is
 * actually stored under; `saltedLambdaHash` folds the dict-capture list into
 * both. While the registration predicted that list as EMPTY, any recursive
 * local lambda whose arithmetic dictionary comes from the ENCLOSING
 * function's dict parameter emitted under one hash and called itself at
 * another — a target resolvable nowhere, so BDSL rejected the lambda and the
 * program died with `CallDirectIR:target-not-compiled`, having type-checked
 * clean. `bench/cases/hofcapture` is the 20-line reproducer.
 *
 * Reads the CURRENT frame, so it is only valid while the parent's frame is
 * still installed — true at both call sites.
 */
internal fun TypedASTCompiler.dictCapturesFor(
expr: TypedLambda,
ownDicts: Set<String>,
): List<Pair<String, Int>> {
        // #195 B3: scan the lambda body for VarType ability method calls
        // (e.g. `x.encode { config = config }` inside a `seq::fold` callback). The
        // lambda needs its own dict params for each method whose receiver is
        // a TyVar. These dicts can't come through the indirect-call signature
        // (it's `f : {acc, x} -> 'b`, no room for extra args), so we capture
        // them from the PARENT's dict-param slots. The parent already gained
        // those slots via the B2 scanner-descent fix.
        val lambdaDictMethods = sortedSetOf<String>()
        // The lambda's own arrow might not carry `with <Ability>` constraints,
        // but the PARENT scope may (e.g. `{ a, b } with Ord 'a -> ({ x, y } -> x <= y)…`).
        // If the parent has dict params, the lambda body may reference VarType ops
        // that need those dicts captured. Derive arrowIsConstrained from both the
        // lambda's own type AND whether the parent scope carries dicts.
        val parentHasDicts = currentParamNames.any { it.startsWith("__dict_") }
        val lambdaConstrained = parentHasDicts || arrowHasAbilityConstraint(expr.type)
        // A lambda with its OWN dictionary parameters captures none: its callers
        // supply every dictionary it needs.
        if (ownDicts.isEmpty()) scanExprForVarTypeMethods(expr.body, lambdaDictMethods, lambdaConstrained)
        // Only keep dicts the parent actually has — otherwise we'd try to
        // capture a slot that doesn't exist.
        return lambdaDictMethods.mapNotNull { method ->
            // Raw `method@var` keys resolve to whichever slot the PARENT holds for
            // that (method, var) — per-var when it has one, else the plain one.
            // C.5: no unqualified-name fallback. This used to drop the type var
            // and retry under the bare `__dict_<method>` name when the per-var
            // lookup missed — a name-keyed guess of exactly the kind C.2
            // replaced. Measured dead: zero hits across a full forced rerun of
            // the interpreter suite (8m38s, no caching), because
            // `enclosingDictSlotIn` already matches an ability-qualified slot for
            // an unqualified request. A dict we cannot locate is not captured.
            val dictName = enclosingDictSlotIn(
                currentParamNames,
                currentCaptureNames,
                com.mezze.language.ast.MagicParams.methodOfKey(method),
                com.mezze.language.ast.MagicParams.typeVarOfKey(method),
                // S-11: without the ability segment this misses the exact per-variable
                // slot, and the PREFIX scan below answers with whichever comes first —
                // two keys from one lambda collapsing onto ONE captured dictionary.
                com.mezze.language.ast.MagicParams.abilityOfKey(method),
            ) ?: return@mapNotNull null
            val parentParamIdx = currentParamNames.indexOf(dictName)
            if (parentParamIdx >= 0) {
                dictName to (currentCaptureCount + parentParamIdx)
            } else {
                val parentCaptureIdx = currentCaptureNames[dictName]
                if (parentCaptureIdx != null) dictName to parentCaptureIdx else null
            }
        }.distinctBy { it.first }
}

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
        // No plain-slot fallback HERE, deliberately. A caller forwarding to a
        // constrained callee does hold the plain slot (see
        // [plainSlotForQualifiedRequest]), but bridging to it at this level makes
        // the forward-from-caller path in `injectDictFields` fire BEFORE concrete
        // resolution, which hijacks cases that resolve correctly on their own —
        // measured: it broke `impl Arbitrary for Result 'a 'e`. The bridge belongs
        // at the last resort, after resolution has been tried.
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
/** The frame slot holding this receiver-less op's dictionary, or null when the
 *  frame has none — which inside a mono SPECIALIZATION is the ordinary case, not
 *  a defect: the spec's types are concrete, so the op can be resolved outright
 *  instead of being handed over. See `receiverlessOpRef`. */
internal fun TypedASTCompiler.abilityOpDictSlotOrNull(binding: BindingRef.AbilityOp): Int? =
    enclosingDictSlot(binding.opName, binding.typeVarName, binding.abilityHash.hex)
        ?.let { dictSlotIndex(it) }

internal fun TypedASTCompiler.abilityOpDictSlot(binding: BindingRef.AbilityOp): Int {
    val index = abilityOpDictSlotOrNull(binding)
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

/**
 * The caller's PLAIN slot for [method], when a qualified request may safely read it.
 *
 * `scanExprForDictFunctionCalls` drops the ability qualifier when it propagates a
 * callee's requirement up to a caller, on purpose: a per-var split belongs to each
 * body and is never inherited. So a caller forwarding to a constrained callee
 * allocates `__dict_arbitrary` while the callee, whose key came from its own body
 * scan, expects `__dict_arbitrary#<ability>` — one dictionary under two names, and
 * the forward matches nothing (S-17).
 *
 * This is a LAST RESORT, consulted only after resolution from concrete types has
 * failed. Folding it into [enclosingDictSlotIn] instead was measured to break
 * `impl Arbitrary for Result 'a 'e`: that made the forward-from-caller path run
 * before concrete resolution and take over a case that was already correct.
 *
 * Two guards, both load-bearing:
 *  - NO per-var request. A callee wanting `arbitrary@a` and `arbitrary@e` must not
 *    be handed one plain slot for both — BUG 9's shape across variables.
 *  - NO other ability-qualified slot of this method in the frame. The plain slot is
 *    then the frame's only dictionary of that name, so there is no second ability it
 *    could belong to, which is what BUG 9 was about.
 */
internal fun TypedASTCompiler.plainSlotForQualifiedRequest(method: String, typeVar: String?): String? {
    if (typeVar != null) return null
    val mp = com.mezze.language.ast.MagicParams
    val qualifiedPrefix = mp.dictParamName(method) + mp.ABILITY_SEP
    val names = currentParamNames + currentCaptureNames.keys
    if (names.any { it.startsWith(qualifiedPrefix) }) return null
    val plain = mp.dictParamName(method)
    return if (names.contains(plain)) plain else null
}

internal fun TypedASTCompiler.enclosingDictSlot(method: String, typeVar: String?, abilityHash: String? = null): String? =
    enclosingDictSlotIn(currentParamNames, currentCaptureNames, method, typeVar, abilityHash)

/**
 * Does the enclosing function already hold the dictionary for a DECODE ENTRY op
 * (`from_json` and friends) called here on a receiver whose type never solved?
 *
 * If it does, the op's impl was NOT chosen here — the constraint
 * (`with FromJson 'a 'in`) was left to the caller, and the caller discharged it
 * where the receiver was concrete. That dictionary IS the choice.
 *
 * Which matters because the compiler has a structural recovery for exactly this
 * shape: a var-typed `from_json` receiver is recovered from the decode impl
 * `DecodeTargetResolver` pinned for the site, by reading that impl's `for 'in`.
 * The two inputs are NOT the same type. A decoder's input is always `Json`; the
 * ENTRY impl's receiver is `Str` for `"{…}".from_json {}`, which PARSES and then
 * decodes, and `Json` for `json.from_json {}`, which decodes directly. They
 * coincide only in the second case.
 *
 * MEASURED: with the receiver recovered as `Json`, `let dec = { s } -> s.from_json {}`
 * ran `impl FromJson 'a for Json` on a `Str` — no parse — so the raw JSON TEXT
 * reached `decode_record_field` and died in `json_type_name` with
 * "Non-exhaustive match: no variant tag matched". It had been invisible because
 * the stdlib wrote `with Decode JsonC 'a`, one argument short of `Decode`'s
 * three: the derived decoder was itself built `for Str`, so the recovery copied
 * that error back and the two cancelled. Completing the constraint to
 * `with Decode JsonC 'a Json` removed the first and exposed the second.
 *
 * So: when the dict is held, stand down. The receiver stays a var, and the
 * dict-param path dispatches through the caller's evidence instead of a shape —
 * the same principle as `viaEnclosingDecodeDict` at the concrete-receiver end
 * ("when the enclosing function holds this method's dict, that dict IS the
 * choice").
 *
 * ## The concrete-receiver end, which is the same question
 *
 * `viaEnclosingDecodeDict` stated that principle but tested it with
 * `decodeImplByOpHash`, which holds only the DERIVED decoders. An ENTRY op is
 * not one, so `from_query` failed the test however many dictionaries the frame
 * held: the op was resolved concretely at the site, and the compiler then had
 * to fill the CALLEE's own `decode` slot from a site where the decode target is
 * still `'a`. With no evidence it left the slot empty, and the dispatch arrived
 * at `__dict_fallback_decode` — "no dictionary was supplied for ability
 * operation `decode`" — from `std/codec/query.mz`'s `from_query`.
 *
 * MEASURED, inside `query_decoded`: `params=[url, __dict_from_query]`,
 * `fwdSlot=null`. The frame was holding the entry dictionary the whole time;
 * nothing asked it. So this predicate answers for BOTH ends — a var-typed
 * receiver (where it stops a shape overruling the dict) and a concrete one
 * (where it routes through the dict instead of resolving past it).
 *
 * Why it had gone unnoticed: the under-supplied `with Decode QueryC 'a`
 * generated the derived decoder as `impl Decode QueryC {page: Int} for Str` —
 * `Str` being the ENTRY impl's receiver, invented for the slot the constraint
 * left empty. `Str` is also the only concrete type at the call site, so the
 * shape pile matched the WIRE type against a TARGET position and resolved the
 * dictionary by accident. Completing the constraint makes the decoder correctly
 * `for Seq { k: Str, v: Str }`, which is what removed the accident.
 */
internal fun TypedASTCompiler.entryOpDictIsHeld(
    methodName: String,
    receiverType: MezzeType,
    abilityHash: String?,
): Boolean =
    methodName in decodeEntryOpNames &&
        enclosingDictSlot(methodName, (receiverType as? VarType)?.variable?.name, abilityHash) != null

/**
 * Does an ability call on a TYPED receiver go through the dictionary the enclosing
 * function already holds for [methodName]?
 *
 * For an ordinary ability the receiver chooses the impl, and a dictionary held for
 * some other type variable must not hijack the call. For a RETURN-DRIVEN one — a
 * decode-flavored codec ability, `Decode 'config 'a for 'in` — the impl is chosen
 * by the TARGET type, which no receiver can tell (every derived decoder has
 * receiver `Json`); when the frame holds that method's dictionary, the dictionary
 * IS the choice, and the call goes through it even though the receiver is typed.
 *
 * "Return-driven" is decided from the op's ABILITY, read off the declarations
 * ([TypedASTCompiler.abilityOpNames]) — never from whether a derived decoder
 * happens to sit in `abilityOpIndex`. Derived decoders exist only in a compile
 * that also decodes into a concrete target, so that witness is absent while
 * compiling the stdlib, a dependency on its own, or a library module with no call
 * site. MEASURED, with every stdlib module in the snapshot: `from_query` for `Str`
 * was lowered with `__dict_decode` delivered and a body that never read it —
 * `.decode` fell to the field-access path as `ReadField(<pairs>, "decode")`, and
 * the first use died with `Expected record for field 'decode', got: MezzeSeq`.
 * The node's `abilityHash` is null when only one ability declares the name, so
 * the declaration index is consulted for that single ability; two abilities
 * declaring it means the node carries which one. The derived-decoder witness
 * stays as a disjunct, and a decode ENTRY op (`from_json`, which
 * `decodeImplByOpHash` excludes) is [entryOpDictIsHeld]'s.
 */
internal fun TypedASTCompiler.heldDictRoutesConcreteReceiver(
    methodName: String,
    receiverType: MezzeType,
    nodeAbility: Hash?,
): Boolean {
    if (receiverType is VarType) return false
    val opAbility = nodeAbility
        ?: abilityOpNames.entries.singleOrNull { methodName in it.value }?.key
    val opIsReturnDriven =
        com.mezze.language.typechecker.WellKnownHashConstants.isDecodeFlavoredCodec(opAbility)
    val held = enclosingDictSlot(methodName, null) != null &&
        (opIsReturnDriven ||
            abilityOpIndex[methodName]?.any { decodeImplByOpHash.containsKey(it.opHash) } == true)
    return held || entryOpDictIsHeld(methodName, receiverType, nodeAbility?.hex)
}

/**
 * Would the wrapper `refToDictedOp` builds for [opHash] carry EVERY dictionary
 * the op itself needs, resolving against [types] at this site? False when some
 * needed slot has no answer here — typically because the op's own obligation is
 * on a type variable this site leaves generic (a decode TARGET inside a generic
 * wrapper).
 *
 * `injectDictFields` uses it to prefer FORWARDING the caller's held slot over a
 * freshly built, incomplete wrapper. MEASURED, with every stdlib module in the
 * snapshot: http's inherent `from_json` on `Result Response HttpError` — a
 * generic wrapper over `from_json_result`, holding `__dict_from_json` — resolved
 * the callee's `from_json` concretely from the constraint's `Str`, wrapped
 * `impl FromJson for Str`'s op, could not plan its `decode` slot (the target is
 * `'a` here) and passed that wrapper on. The dictionary it HELD was complete; the
 * one it BUILT was not, and `.send {}.from_json {}` died with "no dictionary was
 * supplied for ability operation `decode`".
 */
internal fun TypedASTCompiler.dictedRefIsComplete(opHash: Hash, types: List<MezzeType>): Boolean {
    val needed = dictMethodsForHash[opHash].orEmpty()
    if (needed.isEmpty()) return true
    return planWrappedDictSlots(opHash, needed, elementFirst(types), types).size == needed.size
}

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
internal fun TypedASTCompiler.obligationDispatchTypes(
    opHash: Hash,
    callType: MezzeType,
    argType: MezzeType? = null,
): List<Pair<Hash?, List<MezzeType>>> {
    val declared = declaredOpTypeByHash[opHash] as? FunctionType ?: return emptyList()
    if (declared.abilityConstraints.isEmpty()) return emptyList()
    val bindings = HashMap<String, MezzeType>()
    harvestVarBindings(declared.returnType, substitution.apply(callType), bindings)
    // …and from the ARGUMENTS, which is where an operation's OWN type parameter
    // binds (BUG 42). `wrap: { it: 'w, x: 'a } with Show 'a -> Int` returns `Int`,
    // so the return harvests nothing and the obligation used to dispatch nothing;
    // resolution then fell to the flattened receiver/argument pile, which starts
    // with the RECEIVER and took the first impl claiming it. MEASURED: with
    // `impl Show for Src` and `impl Show for Other` both in scope,
    // `s.wrap { x = s }` and `s.wrap { x = o }` BOTH ran Src's `show` — 101 and
    // 101, where the second must be 201. A silent wrong answer, which is why
    // lifting V0417 without this would have been worse than refusing the shape.
    if (argType != null) {
        harvestVarBindings(declared.paramType, substitution.apply(argType), bindings)
    }
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

/**
 * The lambda of the ability-impl operation with this hash, or null.
 *
 * `declIndex` does NOT hold impl operations — only top-level declarations — so
 * reading an op's declared arrow (and with it its `with` constraints) has to go
 * through the impl table, the same lookup `structuralConstraintTypesFor` does.
 */
internal fun TypedASTCompiler.implOpLambdaForHash(opHash: Hash): TypedLambda? {
    for (impl in abilityImpls) {
        for ((opName, body) in impl.operations) {
            if (TypedASTCompiler.computeOpHash(impl.hash, opName) == opHash) {
                return body as? TypedLambda
            }
        }
    }
    return null
}

internal fun TypedASTCompiler.bindImplVarsForOp(opHash: Hash, types: List<MezzeType>): Map<String, MezzeType> {
    val op = abilityOpIndex.values.asSequence().flatten().firstOrNull { it.opHash == opHash } ?: return emptyMap()
    val pattern = op.targetTypes.lastOrNull() ?: return emptyMap()
    val bindings = HashMap<String, MezzeType>()
    for (t in types) harvestVarBindings(pattern, substitution.apply(t), bindings)
    return bindings
}
/**
 * Operation name → the type ITS OWN constraint is about, for a callee whose
 * declared [constraints] these are and whose impl variables are bound by
 * [varBindings].
 *
 * Without this every dict slot is filled by resolving the operation NAME
 * against one flat list of the callee's constraint types, in order, and the
 * first impl claiming any of them wins. For
 *
 *     impl A for Either 'p 'q  with A 'p, B 'q
 *
 * at `Either Str Bool` the list is `[Str, Bool]`, so `fb` found `impl B for
 * Str` and the WRONG OPERATION RAN — "A-StrB-Str" where "A-StrB-Bool" was
 * meant. It surfaces as a crash only when the wrong impl's body rejects the
 * value, which is what `Result Str HttpError` does through http's
 * IntoResponse/IntoErrorResponse pair; a `Result Str Str` "works" because both
 * slots hold one type.
 *
 * Keyed on WHICH ABILITY declares the operation, not on the dict key's
 * variable: a key raised inside a match arm names a FRESH variable
 * (`fb@j654`) rather than the impl's `'q`, and the substitution does not
 * relate the two, so a name lookup misses exactly where it is needed.
 *
 * Declines rather than guesses when one ability would answer twice with
 * different types (`with Eq 'a, Eq 'b`), leaving the existing path to decide.
 *
 * BUG 40 is answered NEXT DOOR, by [ownTuplePerMethod], which carries EVERY type
 * arg in the ability's declaration order — because for a CLASS-PARAM ability no
 * single one of them identifies the impl. (The type-soundness branch had the
 * tuple folded into this function's return; the perf branch split it out. The
 * split shape won the merge because its call sites are the more numerous. The
 * reasoning below is why either exists.) `with Make 'b 'a` at `[Str, Src]` reduced to its first
 * resolvable arg — `Str` — which matches no `Make _ for Str`, so resolution
 * fell through to the flattened list, found `Src`, and took the first of the
 * two impls sharing that receiver. `via` typed `-> 'b` and called at `Str`
 * returned the Int impl's value. The tuple is the same correction BUG 38 made
 * to direct dispatch, one layer down.
 */
internal fun TypedASTCompiler.ownTypePerMethod(
    constraints: List<MezzeType.FunctionAbilityConstraint>,
    varBindings: Map<String, MezzeType>,
): Map<String, Pair<MezzeType, Hash?>> {
    if (constraints.isEmpty()) return emptyMap()
    val found = HashMap<String, Pair<MezzeType, Hash?>>()
    val declined = HashSet<String>()
    for (c in constraints) {
        val ops = c.abilityHash?.let { abilityOpNames[it] } ?: continue
        val bound = c.typeArgs.asSequence()
            .mapNotNull { arg ->
                (arg as? VarType)?.variable?.name?.let { v -> varBindings[v] ?: varBindings["'$v"] }
                    ?: substitution.apply(arg).takeIf { it !is VarType && !containsVarType(it) }
            }
            .firstOrNull() ?: continue
        for (op in ops) {
            val prev = found[op]
            if (prev != null && prev.first != bound) declined.add(op) else found[op] = bound to c.abilityHash
        }
    }
    declined.forEach { found.remove(it) }
    return found
}

/**
 * Operation name → the FULL ordered class-arg tuple its own constraint resolves
 * to here, for the callee whose declared [constraints] these are.
 *
 * [ownTypePerMethod] answers the same question with ONE type, taking the first
 * type arg that resolves. That is enough to pick between impls for different
 * receivers, and not enough to pick between impls that share one:
 * `NumAdd Float for Float` and `NumAdd Int for Float` differ ONLY in the
 * operand, so a single type picks whichever is indexed first and the dictionary
 * holds a DIFFERENT OPERATION. A `Float + Int` reached through a dictionary got
 * Float's homogeneous `add` and handed it a Long:
 *
 *     UnsupportedSpecializationException … CallNativeDDD
 *         [FloatOps$$Lambda, 0.5, 21]        <- Double, Double, Long
 *
 * Only FULLY concrete tuples are kept. A tuple with a hole matches more than
 * one impl, which is the ambiguity this exists to remove — the same rule
 * [obligationDispatchTypes] applies — and those fall through to the existing
 * per-type resolution unchanged.
 *
 * Declines on conflict for the reason [ownTypePerMethod] does: one ability
 * answering twice with different tuples says nothing about which slot is which.
 */
internal fun TypedASTCompiler.ownTuplePerMethod(
    constraints: List<MezzeType.FunctionAbilityConstraint>,
    varBindings: Map<String, MezzeType>,
): Map<String, Pair<List<MezzeType>, Hash?>> {
    if (constraints.isEmpty()) return emptyMap()
    val found = HashMap<String, Pair<List<MezzeType>, Hash?>>()
    val declined = HashSet<String>()
    for (c in constraints) {
        val ops = c.abilityHash?.let { abilityOpNames[it] } ?: continue
        val tuple = c.typeArgs.map { arg ->
            (arg as? VarType)?.variable?.name?.let { v -> varBindings[v] ?: varBindings["'$v"] }
                ?: substitution.apply(arg)
        }
        if (tuple.isEmpty() || tuple.any { it is VarType || containsVarType(it) }) continue
        for (op in ops) {
            val prev = found[op]
            if (prev != null && prev.first != tuple) declined.add(op) else found[op] = tuple to c.abilityHash
        }
    }
    declined.forEach { found.remove(it) }
    return found
}

/**
 * The operation to put in a dictionary slot for [dictMethod].
 *
 * Order matters and is the whole fix: the type THIS operation's own constraint
 * is about comes first. The fallbacks resolve against FLATTENED lists, tried in
 * order, taking the first impl of this operation name that claims ANY element —
 * which fills one constraint's slot from another's type whenever they differ.
 * See [ownTypePerMethod] for the measured failure.
 */
internal fun TypedASTCompiler.resolveDictOp(
    dictMethod: String,
    methodOwnType: Map<String, Pair<MezzeType, Hash?>>,
    keyAbilityHash: Hash?,
    constraintTypes: List<MezzeType>,
    concreteTypes: List<MezzeType>,
    methodOwnTuple: Map<String, Pair<List<MezzeType>, Hash?>> = emptyMap(),
): Hash? {
    // THIS operation's own constraint, matched POSITION BY POSITION, before
    // anything below gets to decide it from a single type. Only a fully
    // concrete tuple arrives here (see `ownTuplePerMethod`), and matching the
    // whole of it is the only way to separate two impls that share a receiver.
    methodOwnTuple[dictMethod]?.let { (tuple, ability) ->
        resolveOpFromObligation(dictMethod, keyAbilityHash ?: ability, tuple)
            ?.let { return it }
    }
    // THIS operation's own constraint first.
    methodOwnType[dictMethod]?.let { (own, ability) ->
        resolveOpHashFromConcreteTypes(dictMethod, listOf(own), keyAbilityHash ?: ability)
            ?.let { return it }
    }
    // Then the callee's constraint types, then the argument types — both
    // flattened, so these are the orderings that can cross. Kept because they
    // answer every shape where nothing says which constraint a slot is for.
    if (constraintTypes.isNotEmpty()) {
        resolveOpHashFromConcreteTypes(dictMethod, constraintTypes)?.let { return it }
    }
    return resolveOpHashFromConcreteTypes(dictMethod, concreteTypes)
}

/** One dictionary slot of a wrapped op, resolved: its param name and the op to put there. */
internal data class WrappedDictSlot(
    val dictParamName: String,
    val opHash: Hash,
    val subTypes: List<MezzeType>,
)

/**
 * The dictionary slots to give a wrapper around [opHash].
 *
 * Applies the per-constraint rule the call site uses ([resolveDictOp]) to the op
 * being WRAPPED. [deeper] is ONE list shared by every slot, so resolving an
 * operation name against it can hand a slot another constraint's impl — and here
 * that means the WRAPPED OP ITSELF. `impl Show for Result 'p 'q` whose `Ok` arm
 * calls `val.show {}` got the Result impl as its own `'p` dictionary, recursed,
 * and matched a Str against Ok/Err:
 *
 *     TAG-READ tag=… value=Ok({val = seven})   ← the Ok arm matched
 *     TAG-READ-NONVARIANT got=String value=seven
 *     Non-exhaustive match: no variant tag matched
 *
 * which reads like a broken match and is a misdirected dictionary.
 *
 * A slot that cannot be resolved is simply ABSENT, and the slots that did
 * resolve are kept. Returning the bare op instead threw away every dictionary
 * that DID resolve, so one undeterminable slot broke a sibling that was
 * perfectly determined:
 *
 *     impl A for Box 'p 'q  with A 'p, C 'q   at Box Str 'a
 *     needed=[fa, fc]  own={fa=Str}
 *
 * `fc` cannot be resolved — nothing says what `'q` is, and with it free no
 * Empty value can exist, so it can never be called. But its miss discarded
 * `fa`'s dictionary too, the op ran bare, `fv.fa {}` fell back to a name
 * lookup, found the Box impl again, recursed, and matched a Str against
 * Full/Empty — reported as a non-exhaustive match. DictConstraintCrossingTest
 * has that shape. An absent slot still fails loudly IF it is ever demanded,
 * which is the honest outcome for a type nothing determines.
 */
internal fun TypedASTCompiler.planWrappedDictSlots(
    opHash: Hash,
    needed: Set<String>,
    deeper: List<MezzeType>,
    concreteTypes: List<MezzeType>,
): List<WrappedDictSlot> {
    val arrow = implOpLambdaForHash(opHash)?.type as? MezzeType.FunctionType
    val implBindings = bindImplVarsForOp(opHash, concreteTypes)
    val constraints = arrow?.abilityConstraints.orEmpty()
    val ownTypes = ownTypePerMethod(constraints, implBindings)
    val ownTuples = ownTuplePerMethod(constraints, implBindings)
    return needed.sorted().mapNotNull { key ->
        val dictMethod = com.mezze.language.ast.MagicParams.methodOfKey(key)
        val own = ownTypes[dictMethod]
        val subTypes = own?.let { listOf(it.first) } ?: deeper
        // BUG 40: the whole class-arg tuple first, for the same reason as in
        // `resolveDictOp`. The slot records the TUPLE it was resolved from when
        // that is what answered, so what the slot says and what chose it agree.
        val byTuple = ownTuples[dictMethod]?.let { (tuple, ability) ->
            tryResolveAbilityOpByTuple(dictMethod, tuple, ability)
                ?.let { it to tuple }
        }
        val resolved = byTuple
            ?: resolveOpHashFromConcreteTypes(dictMethod, subTypes, own?.second)?.let { it to subTypes }
        resolved?.let { (subHash, from) ->
            WrappedDictSlot(com.mezze.language.ast.MagicParams.dictParamName(key), subHash, from)
        }
    }
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
    // An OPERATOR op: one whose candidates come from an ability an operator
    // desugars to (Eq, Ord, LogicalAnd/Or, Num*). Asked by ABILITY IDENTITY,
    // not by name: the guards below change dictionary behaviour, and keying
    // them on the spelling gave a user ability declaring its own `add` the
    // arithmetic intrinsics' treatment.
    val isOperatorOp = opCandidates.orEmpty().any {
        com.mezze.language.typechecker.WellKnownHashConstants.isOperatorAbility(it.abilityHash)
    }
    // No ability of this name exists at all, so nothing can answer a dict for
    // it and only the intrinsic can — asked by NAME here on purpose, since
    // with no declaration in play there is no ability to ask and no user
    // declaration to be confused with. Keeps a VarType `'a` with no impl on
    // the structural/native default.
    if (methodName in inlineableOps && !hasRealOp) return false
    // #18 guard: in user compilation (!isStdlibMode), only flag operator ops
    // when the enclosing arrow explicitly carries an ability constraint
    // (`with Ord 'a`, `with NumMul 'a`, etc.). Without the constraint guard,
    // every generalized user function like `{x} -> x * 2` gets `__dict_mul`
    // in its frame — and CallIndirect callers (HOF callbacks, closures)
    // crash because they can't inject dicts.
    //
    // During stdlib compilation (isStdlibMode), we allow operator ops
    // unconditionally because stdlib functions (sort, min, max) are always
    // called via CallDirect, which can inject dicts at the call site.
    if (isOperatorOp && !isStdlibMode && !arrowIsConstrained) return false
    return when {
        arrowIsConstrained -> true
        opCandidates != null && opCandidates.size > 1 -> true
        // For a non-operator op the historical rule required >1 candidate.
        // For an operator op backed by a real ability (Ord/Eq/Num), a single
        // candidate still needs the dict at a polymorphic call site, since the
        // receiver stays generic until mono specializes it.
        isOperatorOp -> true
        else -> false
    }
}

internal fun TypedASTCompiler.scanExprForVarTypeMethods(
    expr: TypedExpr,
    out: MutableSet<String>,
    arrowIsConstrained: Boolean = false,
    arrowType: MezzeType? = null,
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
                // The receiver being CONCRETE says nothing about whether the op
                // resolves statically. This scan asks "is the receiver a
                // variable?", which is right for an ability that dispatches on
                // its receiver and wrong for one that does not:
                //
                //     ability Decode 'config 'a for 'in
                //     impl FromJson 'a for Str where {
                //       from_json: { it: Str } with Decode JsonC 'a -> … = { it } ->
                //         match parse_str { s = it } is
                //           Ok { val } -> val.decode { config = json_default }
                //
                // `decode` dispatches on the decode TARGET `'a`; the receiver
                // `'in` is the concrete `Json` the parse just produced. So the op
                // needs a dictionary while the receiver needs nothing, and keying
                // the decision on the receiver misses it — `from_json` compiled
                // with NO dict param at all and its `.decode` degraded to a
                // name-based field read (CallIndirect on [MezzeRecord, Object[]]).
                //
                // Narrow on purpose. A spurious dict param is not harmless: the
                // comments above record closure frames built without a slot the
                // emitted read then runs off the end of. So this fires only when
                // the ENCLOSING ARROW is constrained by an ability that declares
                // this very operation AND that constraint still carries a type
                // variable after substitution. "Constrained at all" is too broad
                // to risk.
                val concreteReceiverStillNeedsDict = receiverType !is VarType &&
                    (arrowType as? MezzeType.FunctionType)?.abilityConstraints?.any { c ->
                        val ops = c.abilityHash?.let { abilityOpNames[it] }
                        ops != null && methodName in ops &&
                            c.typeArgs.any { containsVarType(substitution.apply(it)) }
                    } == true
                if (concreteReceiverStillNeedsDict &&
                    shouldDictDispatchVarTypeOp(methodName, arrowIsConstrained)) {
                    // No receiver variable to qualify with, and none is wanted:
                    // the two decode targets are told apart by the two GENERATED
                    // impls and by what each call site injects, not by the slot
                    // name. `normalizeDictKeys` collapses a single-variable key to
                    // this same plain shape, so the param name is byte-identical
                    // to the working receiver-variable path.
                    val ability = fn.abilityHash?.hex?.take(12)
                    out.add(
                        if (ability == null) methodName
                        else "$methodName${com.mezze.language.ast.MagicParams.ABILITY_SEP}$ability"
                    )
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
                scanExprForVarTypeMethods(fn.record, out, arrowIsConstrained, arrowType, paramEnv)
            } else {
                scanExprForVarTypeMethods(fn, out, arrowIsConstrained, arrowType, paramEnv)
            }
            scanExprForVarTypeMethods(expr.arg, out, arrowIsConstrained, arrowType, paramEnv)
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
            scanExprForVarTypeMethods(expr.body, out, innerConstrained, arrowType, innerParamEnv)
        }
        is TypedBlock -> {
            for (s in expr.statements) {
                when (s) {
                    // See scanExprForDictFunctionCalls: a lambda with its own
                    // dictionary parameters is supplied by its call sites.
                    is TypedStatement.Let ->
                        if (ownDictMethodsOf(s.value).isEmpty()) scanExprForVarTypeMethods(s.value, out, arrowIsConstrained, arrowType, paramEnv)
                    is TypedStatement.Expr -> scanExprForVarTypeMethods(s.expr, out, arrowIsConstrained, arrowType, paramEnv)
                }
            }
            scanExprForVarTypeMethods(expr.result, out, arrowIsConstrained, arrowType, paramEnv)
        }
        is TypedIf -> {
            scanExprForVarTypeMethods(expr.condition, out, arrowIsConstrained, arrowType, paramEnv)
            scanExprForVarTypeMethods(expr.thenBranch, out, arrowIsConstrained, arrowType, paramEnv)
            scanExprForVarTypeMethods(expr.elseBranch, out, arrowIsConstrained, arrowType, paramEnv)
        }
        is TypedMatch -> {
            scanExprForVarTypeMethods(expr.scrutinee, out, arrowIsConstrained, arrowType, paramEnv)
            for (c in expr.cases) {
                c.guard?.let { scanExprForVarTypeMethods(it, out, arrowIsConstrained, arrowType, paramEnv) }
                scanExprForVarTypeMethods(c.body, out, arrowIsConstrained, arrowType, paramEnv)
            }
        }
        is TypedRecord -> {
            for (e in expr.entries) {
                when (e) {
                    is TypedRecordEntry.Field -> scanExprForVarTypeMethods(e.value, out, arrowIsConstrained, arrowType, paramEnv)
                    is TypedRecordEntry.Spread -> scanExprForVarTypeMethods(e.expr, out, arrowIsConstrained, arrowType, paramEnv)
                }
            }
        }
        is TypedFieldAccess -> scanExprForVarTypeMethods(expr.record, out, arrowIsConstrained, arrowType, paramEnv)
        is TypedVariantConstruct -> expr.payload?.let { scanExprForVarTypeMethods(it, out, arrowIsConstrained, arrowType, paramEnv) }
        is TypedRecordUpdate -> {
            scanExprForVarTypeMethods(expr.record, out, arrowIsConstrained, arrowType, paramEnv)
            for (e in expr.updates) {
                if (e is TypedRecordEntry.Field) scanExprForVarTypeMethods(e.value, out, arrowIsConstrained, arrowType, paramEnv)
            }
        }
        is TypedPerform -> {
            for (h in expr.handlers) {
                h.params?.let { scanExprForVarTypeMethods(it, out, arrowIsConstrained, arrowType, paramEnv) }
            }
            scanExprForVarTypeMethods(expr.body, out, arrowIsConstrained, arrowType, paramEnv)
        }
        is TypedPerformOp -> scanExprForVarTypeMethods(expr.arg, out, arrowIsConstrained, arrowType, paramEnv)
        is TypedPipe -> {
            scanExprForVarTypeMethods(expr.left, out, arrowIsConstrained, arrowType, paramEnv)
            scanExprForVarTypeMethods(expr.right, out, arrowIsConstrained, arrowType, paramEnv)
        }
        is TypedBuildTime -> scanExprForVarTypeMethods(expr.expr, out, arrowIsConstrained, arrowType, paramEnv)
        is TypedSeq -> {
            for (e in expr.elements) {
                when (e) {
                    is TypedSeqElement.Regular -> scanExprForVarTypeMethods(e.expr, out, arrowIsConstrained, arrowType, paramEnv)
                    is TypedSeqElement.Spread -> scanExprForVarTypeMethods(e.expr, out, arrowIsConstrained, arrowType, paramEnv)
                }
            }
        }
        is TypedStringLit -> {
            for (p in expr.parts) {
                if (p is TypedStringPart.Interpolation) scanExprForVarTypeMethods(p.expr, out, arrowIsConstrained, arrowType, paramEnv)
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

/**
 * [argIR] with the dictionaries [calleeHash] needs injected, resolved for THIS
 * call: constraint variables bound through the arguments, then the return,
 * then structurally; forwarded from the caller when all of them are still the
 * caller's own variables. One chain for every kind of callee — a top-level
 * function called by hash and a constrained LOCAL lambda called through its
 * closure resolve their dictionaries identically.
 */
/**
 * Bind a pattern row's associated types from the actual row's, for
 * [TypedASTCompiler.harvestVarBindings].
 *
 * A constraint variable can live ONLY in an effect row. `http_server`'s
 * `catching_any` takes a handler typed
 * `{ m: Matched } -> 'r <Exception having 'error = 'e, ...>` and declares
 * `with IntoErrorResponse 'e` — about the THROWN type, which the row names and
 * the parameter and result never mention. Walking params and result alone left
 * `'e` unbound at the call site, so `calleeConstraintsAreGeneric` went true,
 * dict injection preferred forwarding from a caller that has nothing to
 * forward, and the slot reached `DictFallbackRootNode`: "no dictionary was
 * supplied for ability operation `into_error_response`" — for a program that
 * determines the type perfectly well, by throwing a `Str`.
 *
 * Matched by effect NAME, accepting a suffix either way, because one side may
 * carry the module path (`std::effects::exception::Exception`) and the other
 * the local spelling.
 *
 * This is necessary and not sufficient: an INLINE argument lambda is stamped
 * with the row from the pushed CONTEXT — the callee's own declared row, with
 * the callee's variable — so there is nothing concrete on the value side to
 * bind. A NAMED handler carries its own solved row and works. `HttpCatchingAnyTest`
 * pins both halves.
 */
internal fun TypedASTCompiler.harvestEffectRowBindings(
    pattern: MezzeType.EffectRow?,
    value: MezzeType.EffectRow?,
    out: MutableMap<String, MezzeType>,
) {
    if (pattern == null || value == null) return
    if (pattern.effectInstances.isEmpty() || value.effectInstances.isEmpty()) return
    fun sameEffect(a: String, b: String) =
        a == b || a.endsWith("::$b") || b.endsWith("::$a")
    for (pi in pattern.effectInstances) {
        if (pi.associatedTypes.isEmpty()) continue
        val vi = value.effectInstances.firstOrNull { sameEffect(pi.name, it.name) } ?: continue
        for ((assoc, pt) in pi.associatedTypes) {
            val vt = vi.associatedTypes[assoc]
                ?: vi.associatedTypes[assoc.removePrefix("'")]
                ?: vi.associatedTypes["'" + assoc.removePrefix("'")]
                ?: continue
            harvestVarBindings(pt, vt, out)
        }
    }
}

internal fun TypedASTCompiler.dictEnrichedArg(
    calleeHash: Hash,
    fn: TypedExpr,
    expr: TypedCall,
    argIR: NodeIR,
    calleeDictMethods: Set<String>,
    src: SourceInfo,
): NodeIR {
    val rawFnType = fn.type as? MezzeType.FunctionType
    val rawConstraintTypeArgs = (rawFnType?.abilityConstraints
        ?.flatMap { it.typeArgs }
        ?.map { substitution.apply(it) }
        ?: emptyList())
        // KEY-constrained callees (min_by's `with Ord 'b` where 'b is
        // f's RETURN type): the constraint arg stays a generalized var
        // the module substitution can't solve. Bind it by unifying the
        // callee's param type against the actual arg type ('b -> K),
        // otherwise resolution falls to the arg types where the xs
        // container falsely matches the blanket `impl Ord for ['a]`.
        .let { bindConstraintArgsViaArg(rawFnType, expr.arg, it) }
        // …and from the RETURN, for a constraint variable the
        // parameters never mention (`{} with Zero 'a -> 'a`).
        .let { bindConstraintArgsViaReturn(rawFnType, expr.type, it) }
    val structuralConstraintTypes = structuralConstraintTypesFor(calleeHash, expr)
    val constraintTypes =
        if (structuralConstraintTypes.isNotEmpty()) structuralConstraintTypes
        else rawConstraintTypeArgs.filter { it !is MezzeType.VarType }
    // When ALL constraint type args are still VarType after substitution,
    // we're inside a generic function body calling another generic function.
    // In this case, prefer forwarding dicts from the caller over resolving
    // from concrete types (which may match a wrong blanket container impl).
    // A structural binding above is a concrete answer, so it overrides this.
    val calleeConstraintsAreGeneric = structuralConstraintTypes.isEmpty() &&
        rawConstraintTypeArgs.isNotEmpty() && rawConstraintTypeArgs.all { it is MezzeType.VarType }
    // Every callee type var → its type here: an impl op binds through
    // its instance head against the arg record's fields, a generic let
    // through its declared arrow against the actual arg type.
    val calleeVarBindings: Map<String, MezzeType> = run {
        val argTypes = (expr.arg as? TypedRecord)?.entries
            ?.filterIsInstance<TypedRecordEntry.Field>()
            ?.map { substitution.apply(it.value.type) } ?: emptyList()
        // S-3: a receiver-less op's arg record is `{}`, so there are no
        // argument types to bind through. Its PRODUCED type is the only
        // thing the instance head can match, same as above.
        val headTypes = argTypes.ifEmpty { listOf(substitution.apply(expr.type)) }
        val fromImpl = bindImplVarsForOp(calleeHash, headTypes)
        if (fromImpl.isNotEmpty()) return@run fromImpl
        val fromArrow = HashMap<String, MezzeType>()
        rawFnType?.let { harvestVarBindings(it.paramType, substitution.apply(expr.arg.type), fromArrow) }
        // THE SITE'S OWN SOLVED PARAMETER TYPES, for the vars the line above
        // could not reach. It harvests the callee's SCHEME against the
        // ARGUMENT, and when the argument is a reference to another polymorphic
        // function the argument's recorded type is ITS scheme too — so a
        // callback's result meets a variable and binds nothing. That left a
        // `with` constraint's operand open, and the dictionary was then picked
        // from the only resolved position, the RECEIVER: right by luck for a
        // homogeneous add, and Float's `impl NumAdd Float for Float` handed a
        // Long for `Float + Int`.
        //
        // `callSiteArrows` keeps what the solver concluded about THIS site, and
        // only a fully resolved arrow is offered, so harvesting the scheme
        // against it binds those same vars to concrete types:
        //
        //   scheme   { n: Int, f: { x: Int } -> 'a, zero: 'b }
        //   per-site { n: Int, f: { x: Int } -> Int, zero: Float }
        //   gives    'a = Int, 'b = Float   -> the tuple [Int, Float] is whole
        //
        // ADDED, never overriding: a var the existing sources bound keeps that
        // binding. Replacing them was not tried because the failure here is
        // always a MISSING binding, never a wrong one.
        callSiteArrow(expr.loc)?.let { arrow ->
            val fromSite = HashMap<String, MezzeType>()
            rawFnType?.let { harvestVarBindings(it.paramType, arrow.paramType, fromSite) }
            for ((k, v) in fromSite) fromArrow.putIfAbsent(k, v)
        }
        fromArrow
    }
    // The callee's `with` constraints. `fn.type` carries them for an ordinary
    // function, but at an ABILITY-OP call site it arrives already instantiated
    // with none left — and an impl op is exactly where the per-constraint
    // question matters, so fall back to the op's DECLARED arrow from the impl
    // table (`declIndex` holds top-level declarations only).
    val calleeConstraints = rawFnType?.abilityConstraints?.takeIf { it.isNotEmpty() }
        ?: (implOpLambdaForHash(calleeHash)?.type as? MezzeType.FunctionType)?.abilityConstraints.orEmpty()
    // BUG 40: what the call PRODUCES, as bindings for the callee's variables.
    //
    // `calleeVarBindings` is harvested from the ARGUMENTS, so a constraint
    // variable the parameters never mention stays unbound — and for a
    // class-param ability in the return position that is the variable that picks
    // the impl. `via: { x: 'a } with Make 'b 'a -> 'b` binds `'a` from `{ x = s }`
    // and learns nothing about `'b`, so the tuple came back incomplete and
    // resolution fell to the receiver alone.
    //
    // The same evidence `bindConstraintArgsViaReturn` already uses above, in the
    // form the per-constraint path needs. Kept SEPARATE from `calleeVarBindings`
    // so only `ownTypePerMethod` sees it: the per-var dict slots are addressed by
    // the caller's own variable names, and widening what they resolve from is a
    // different question with its own failure modes.
    val returnBindings: Map<String, MezzeType> = run {
        val produced = substitution.apply(expr.type)
        val declaredReturn = rawFnType?.returnType ?: return@run emptyMap()
        if (produced is MezzeType.VarType || containsVarType(produced)) return@run emptyMap()
        val out = HashMap<String, MezzeType>()
        harvestVarBindings(substitution.apply(declaredReturn), produced, out)
        out
    }
    return injectDictFields(
        argIR, expr.arg, calleeDictMethods, constraintTypes, calleeConstraintsAreGeneric, src,
        calleeVarBindings,
        // `returnBindings` as well as the argument bindings, in BOTH: a class
        // param can be reachable from the RETURN and from no argument at all
        // (`Into 'b for 'a`), which is what BUG 39/40 were about. Without it the
        // tuple is short exactly where it matters, and a generic hop loses the
        // param. The perf branch passed only `calleeVarBindings`; this is the
        // type-soundness side's addition, kept across the merge.
        ownTypePerMethod(calleeConstraints, returnBindings + calleeVarBindings),
        ownTuplePerMethod(calleeConstraints, returnBindings + calleeVarBindings),
    )
}

/**
 * The dictionaries a do-block-bound lambda takes as its OWN parameters, or
 * empty when it takes none. Only a lambda whose arrow carries `with`
 * constraints qualifies: it is generalized over them, so each call
 * instantiates them anew — one slot captured from the enclosing function
 * could serve only one instantiation, and that function's callers know
 * nothing of it. Its needs are what its body asks for directly plus what
 * its callees need forwarded, the same two sources a top-level function's
 * come from.
 */
internal fun TypedASTCompiler.ownDictMethodsOf(value: TypedExpr): Set<String> {
    if (value !is TypedLambda) return emptySet()
    val arrow = value.type as? MezzeType.FunctionType ?: return emptySet()
    // A constraint still holding a free variable is live — the test
    // `declaredDictMethods` uses. `arrowHasAbilityConstraint` asks about the
    // PARAMETER alone and so misses `{} with Default 'a -> 'a`, whose instance
    // only the result decides. Matching the variable against the arrow's own
    // param/result would be wrong too: the constraint carries the annotation's
    // `'a` and the result the body's, two TypeVars the module substitution does
    // not link for a generalized local. Over-reporting cannot add parameters:
    // they come from what the body ASKS for, below.
    val live = arrow.abilityConstraints.any { c -> c.typeArgs.any { containsVarTypeDeep(substitution.apply(it)) } }
    if (!live) return emptySet()
    return scanExprForVarTypeMethodCalls(value.body, arrowIsConstrained = true) +
        scanExprForCallsToDictFunctions(value.body)
}

/**
 * A bare record literal's type, carrying its alias's identity.
 *
 * Called from `resolveOpHashFromConcreteTypes` before a type is matched against
 * an impl. A literal gets its alias's identity by ADOPTION, and adoption
 * compares field types exactly — so it cannot run during the solve, while the
 * fields are still variables. By the time a type reaches op resolution they are
 * solved and the identity is still missing: `{ at: [Str], message: Str,
 * expected: Str, found: Option Str }` as Anonymous. A nominal
 * `impl … for BadInput` cannot match that, while the same value written
 * `let bad: BadInput = …` dispatched fine — the annotation supplied the
 * identity the literal had to earn (BUG 26).
 *
 * Adopting at that call is the right place because it is the first point at
 * which the fields are known. It also stops a WRONG impl being found:
 * `resolveOpHashFromConcreteTypes`' later pass descends into record FIELD
 * types, so a record with a `message: Str` field reached
 * `impl IntoErrorResponse for Str` and failed at run time with
 * `Expected String for field 's'`.
 *
 * `printTypeDefs` is the pool, and it is already here: #394 built it so a
 * nominal receiver could be PRINTED, and `PrintTypeDefs.resolve` yields a
 * closed record as `RecordType(fields, rowVar = null,
 * identity = declared(name, hash))` with local declarations shadowing
 * imported ones — the same scoping adoption needs. No second source.
 */
internal fun TypedASTCompiler.adoptBareRecord(type: MezzeType): MezzeType {
    val record = type as? RecordType ?: return type
    if (record.identity != com.mezze.language.ast.TypeIdentity.Anonymous) return type
    if (record.rowVar != null || record.fields.isEmpty()) return type
    if (printTypeDefs.isEmpty()) return type
    val aliases = printTypeDefs.mapNotNull { (name, declared) ->
        val rec = declared as? RecordType ?: return@mapNotNull null
        if (rec.rowVar != null) return@mapNotNull null
        val hash = (rec.identity as? com.mezze.language.ast.TypeIdentity.Declared)?.hash
            ?: return@mapNotNull null
        Triple(name, hash, rec.fields)
    }.distinctBy { (_, hash, _) -> hash }
    return com.mezze.language.ast.adoptRecordAlias(record, aliases)
}

/**
 * The slot to forward for an operation's OWN type parameter, or null when
 * something at the call site genuinely determined the dictionary.
 *
 * **This has to be asked BEFORE the concrete pile**, and that ordering is the
 * whole fix. The pile (`concreteTypesForDict`) holds the receiver and the
 * arguments; it cannot see what the CALLER's type variable is bound to. So for an
 * operation carrying its own parameter it always resolves, and always to the
 * receiver's impl:
 *
 *     direct: { it: A, x: 'p } with Show 'p -> Int = { it, x } -> x.show {}
 *     fwd:    { it: A, x: 'p } with Show 'p -> Int = { it, x } -> it.direct { x = x }
 *
 *     a.direct { x = b }   ## 2  — correct, `Show B`
 *     a.fwd    { x = b }   ## 1  — WRONG, `Show A`: the receiver's
 *
 * Forwarding used to be the LAST resort, reached only when nothing resolved — so
 * it never ran here. It is not a fallback for this shape; it is the specific
 * answer, and the receiver-is-a-VarType path already applied the same rule
 * ("bound to one of the caller's own variables → forward the caller's slot").
 *
 * Returns null when [fromObligation] answered, when a per-site decode pins the
 * slot, or when [perVarBound] bound the variable to a concrete type here — in all
 * three something more specific than a forward is known. Also null when the
 * enclosing function holds no such slot, which is the ordinary case for an op
 * whose constraint belongs to its ability rather than to itself.
 */
internal fun TypedASTCompiler.ownParamDictForward(
    dictMethodName: String,
    dictMethod: String,
    dictParamName: String,
    src: SourceInfo,
    perVarBound: MezzeType?,
    fromObligation: Hash?,
    perSiteMatchesSlot: Boolean,
): FieldIR? {
    if (fromObligation != null || perSiteMatchesSlot || perVarBound != null) return null
    val slot = enclosingDictSlot(
        dictMethod, com.mezze.language.ast.MagicParams.typeVarOfKey(dictMethodName),
    ) ?: return null
    val idx = currentParamNames.indexOf(slot)
    if (idx >= 0) return FieldIR(dictParamName, ReadLocalIR(currentCaptureCount + idx, SlotKind.OBJECT, src))
    val cap = currentCaptureNames[slot] ?: return null
    return FieldIR(dictParamName, ReadCaptureIR(cap, SlotKind.OBJECT, src))
}
