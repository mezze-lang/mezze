package com.mezze.language.typechecker.mono

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar
import com.mezze.language.parsedast.Loc
import com.mezze.language.typedast.AbilityImplRef
import com.mezze.language.typedast.BindingRef
import com.mezze.language.typedast.Hash
import com.mezze.language.typedast.TypeKind
import com.mezze.language.typedast.TypeParam
import com.mezze.language.typedast.TypeScheme
import com.mezze.language.typedast.TypedAbilityImpl
import com.mezze.language.typedast.TypedBlock
import com.mezze.language.typedast.TypedCall
import com.mezze.language.typedast.TypedExpr
import com.mezze.language.typedast.TypedFieldAccess
import com.mezze.language.typedast.TypedIf
import com.mezze.language.typedast.TypedLambda
import com.mezze.language.typedast.TypedLetDecl
import com.mezze.language.typedast.TypedMatch
import com.mezze.language.typedast.TypedModule
import com.mezze.language.typedast.TypedPerform
import com.mezze.language.typedast.TypedPerformOp
import com.mezze.language.typedast.TypedPipe
import com.mezze.language.typedast.TypedRecord
import com.mezze.language.typedast.TypedRecordEntry
import com.mezze.language.typedast.TypedRecordUpdate
import com.mezze.language.typedast.TypedSeq
import com.mezze.language.typedast.TypedSeqElement
import com.mezze.language.typedast.TypedStatement
import com.mezze.language.typedast.TypedStringPart
import com.mezze.language.typedast.TypedStringLit
import com.mezze.language.typedast.TypedVar
import com.mezze.language.typedast.TypedVariantConstruct

/**
 * Phase 1 hybrid driver — gathers seed call sites from a TypedModule
 * (and its imported modules), runs the [Monomorphizer], and returns the
 * result for [com.mezze.language.typechecker.compiler.TypedASTCompiler].
 *
 * Gating is deliberately external: callers consult [enabled] before doing
 * any of this work. Default off.
 *
 * **Seed selection — two flavors**:
 *
 * 1. *Direct generic call*: `TypedCall(TypedVar(ByHash(genericHash)), arg)`
 *    where the callee's `typeParams` is non-empty. Type args derived by
 *    unifying `callee.scheme.type` against the call-site `fn.type`.
 *
 * 2. *Method call*: `TypedCall(TypedFieldAccess(receiver, methodName), arg)`
 *    where `(receiver.type, methodName)` resolves to a [TypedAbilityImpl]
 *    operation. The op's hash is `Hash.computeOpHash(impl.hash, methodName)`,
 *    its typeParams are `impl.forallVars`. Type args derived by unifying
 *    the op's lambda param type (a record `{it: T_self, …op-args}`) against
 *    the actual call-site argument record `{it: receiver.type, …arg-types}`.
 *
 * Calls inside generic decls' own bodies are skipped: those are still
 * polymorphic, and the unification would yield VarType bindings (not
 * usable for specialization). Their concrete callers will reach them
 * transitively through the Monomorphizer's worklist.
 */
object MonoDriver {

    /** Read once; subsequent reads cache the value. */
    val enabled: Boolean by lazy {
        val prop = System.getProperty("mezze.mono")
        val env = System.getenv("MEZZE_MONO")
        (prop?.lowercase() in setOf("1", "true", "yes")) ||
        (env?.lowercase() in setOf("1", "true", "yes"))
    }

    /**
     * @param entry the post-`wrapTopLevel` user module being compiled.
     * @param imported user-side imported modules. Their decls participate
     *   in seeding AND in resolution.
     * @param resolutionOnly modules whose decls are looked up but NOT
     *   seeded. Stdlib goes here.
     */
    /**
     * Augmented driver result. Wraps [Monomorphizer.Result] and adds a
     * per-call-site loc → specialized hash map so the compiler can rewrite
     * call sites by location, without re-deriving SpecRequest tuples.
     *
     * `bySiteLoc` is keyed by the `TypedCall`'s loc — same loc the compiler
     * sees when emitting IR for that call. If a call site appears in the
     * map, the compiler emits `CallDirectIR(specializedHash)` instead of
     * `CallDirectIR(genericHash)`.
     */
    data class Result(
        val mono: Monomorphizer.Result,
        val bySiteLoc: Map<Loc, Hash>,
    ) {
        val specializations get() = mono.specializations
        val fallbacks get() = mono.fallbacks
    }

    fun run(
        entry: TypedModule,
        imported: List<TypedModule> = emptyList(),
        resolutionOnly: List<TypedModule> = emptyList(),
    ): Result {
        // Index ordinary let decls.
        val declIndex = HashMap<Hash, TypedLetDecl>()
        for (m in (listOf(entry) + imported + resolutionOnly)) {
            for (d in m.decls) if (d is TypedLetDecl) declIndex[d.hash] = d
        }

        // Index ability impl operation bodies as synthetic generic decls.
        // Each `(implHash, opName)` pair becomes a TypedLetDecl whose
        // `typeParams` are `impl.forallVars`, scheme.type is the op body's
        // function type, and value is the op body. The Monomorphizer
        // resolves `Hash.computeOpHash(impl.hash, opName)` through the
        // same `declIndex` interface as ordinary decls.
        val abilityImpls = collectAbilityImpls(entry, imported, resolutionOnly)
        for (impl in abilityImpls) {
            for ((opName, opBody) in impl.operations) {
                val opHash = Hash.computeOpHash(impl.hash, opName)
                val opType = when (opBody) {
                    is TypedLambda -> opBody.type
                    else -> opBody.type
                }
                val synthetic = TypedLetDecl(
                    hash = opHash,
                    name = opName,
                    typeParams = impl.forallVars,
                    value = opBody,
                    scheme = TypeScheme(
                        typeParams = impl.forallVars,
                        abilityConstraints = emptyList(),
                        type = opType
                    ),
                    isPublic = true,
                    loc = impl.loc
                )
                declIndex[opHash] = synthetic
            }
        }

        // Build method index: methodName → list of impls (with their op hashes).
        val abilityOpIndex = HashMap<String, MutableList<AbilityOpEntry>>()
        for (impl in abilityImpls) {
            for ((opName, _) in impl.operations) {
                val opHash = Hash.computeOpHash(impl.hash, opName)
                abilityOpIndex.getOrPut(opName) { mutableListOf() }.add(
                    AbilityOpEntry(impl, opHash)
                )
            }
        }

        // Seed only from concrete (non-generic) decls. Track per-seed call
        // site loc in a parallel list so we can map back from each
        // SpecRequest to the originating TypedCall after mono runs.
        val seeds = ArrayList<Monomorphizer.SpecRequest>()
        val seedLocs = ArrayList<Loc>()
        val counters = Counters()
        for (m in listOf(entry) + imported) {
            for (d in m.decls) {
                if (d !is TypedLetDecl) continue
                if (d.typeParams.isNotEmpty()) continue
                collectSeedsFromExpr(d.value, declIndex, abilityOpIndex, abilityImpls, seeds, seedLocs, counters)
            }
        }

        // Index impls by their content hash so the constraint-aware
        // rewriter (Piece 2 of #267) can look up operation names from the
        // AbilityImplRefs in each SpecRequest.
        val implIndex = HashMap<Hash, TypedAbilityImpl>(abilityImpls.size)
        for (impl in abilityImpls) implIndex[impl.hash] = impl
        val result = Monomorphizer(
            resolveDecl = declIndex::get,
            implIndex = implIndex,
        ).run(seeds)

        // Build the per-call-site lookup the compiler consumes. For each
        // seed loc, look up the SpecRequest in the rewrite map; if mono
        // produced a specialization for that request, the compiler will
        // emit CallDirectIR(specializedHash) instead of the generic call.
        val bySiteLoc = HashMap<Loc, Hash>(seeds.size)
        for (i in seeds.indices) {
            val sh = result.callSiteRewrites[seeds[i]] ?: continue
            bySiteLoc[seedLocs[i]] = sh
        }
        if (System.getenv("MEZZE_MONO_STATS") != null) {
            val concreteDecls = (listOf(entry) + imported).sumOf {
                it.decls.count { d -> d is TypedLetDecl && d.typeParams.isEmpty() }
            }
            val genericDecls = (listOf(entry) + imported + resolutionOnly).sumOf {
                it.decls.count { d -> d is TypedLetDecl && d.typeParams.isNotEmpty() }
            }
            val genericImpls = abilityImpls.count { it.forallVars.isNotEmpty() }
            val genericSeeds = seeds.count { req ->
                declIndex[req.genericHash]?.typeParams?.isNotEmpty() == true
            }
            System.err.println(
                "[mono] entry=${entry.path} userMods=${1 + imported.size} stdlibMods=${resolutionOnly.size} " +
                "impls=${abilityImpls.size}(generic=$genericImpls) concreteDecls=$concreteDecls genericDecls=$genericDecls " +
                "seeds=${seeds.size}(generic=$genericSeeds) specs=${result.specializations.size} fallbacks=${result.fallbacks.size} " +
                "siteHits=${bySiteLoc.size}"
            )
            // Per-call-shape miss breakdown — surfaces opportunities to
            // tighten coverage. Only print when something interesting
            // happened (any visit > 0).
            if (counters.byHashGenericVisited + counters.methodGenericVisited > 0) {
                System.err.println(
                    "[mono]   byHash visited=${counters.byHashGenericVisited} seeded=${counters.byHashSeeded} " +
                    "(fnType=${counters.byHashFnTypeOk} argType=${counters.byHashArgTypeOk} fail=${counters.byHashUnifyFail})"
                )
                System.err.println(
                    "[mono]   method visited=${counters.methodGenericVisited} seeded=${counters.methodSeeded} " +
                    "(rcvVarType=${counters.methodReceiverNotConcrete} noImpl=${counters.methodNoMatchingImpl} unifyFail=${counters.methodUnifyFail})"
                )
            }
        }
        return Result(mono = result, bySiteLoc = bySiteLoc)
    }

    private data class AbilityOpEntry(val impl: TypedAbilityImpl, val opHash: Hash)

    /** Coverage counters accumulated by the seed walker. Reported via
     *  `MEZZE_MONO_STATS` so we can tell at a glance how much of the
     *  generic-call surface mono actually reaches. */
    private class Counters {
        // ByHash direct generic calls (TypedCall(TypedVar(ByHash, …))):
        var byHashGenericVisited = 0   // calls to a generic decl
        var byHashSeeded = 0           // produced a seed
        var byHashFnTypeOk = 0         // fn.type derivation worked
        var byHashArgTypeOk = 0        // had to fall back to arg-type derivation
        var byHashUnifyFail = 0        // both unification paths failed

        // Method-style calls (TypedCall(TypedFieldAccess, …)):
        var methodGenericVisited = 0   // resolved to a generic impl op
        var methodSeeded = 0           // produced a seed
        var methodReceiverNotConcrete = 0  // bailed on VarType receiver
        var methodNoMatchingImpl = 0   // multi-candidate, no targetType match
        var methodUnifyFail = 0        // unification produced VarType / null
    }

    /** Pull all ability impls from every visible module. */
    private fun collectAbilityImpls(
        entry: TypedModule,
        imported: List<TypedModule>,
        resolutionOnly: List<TypedModule>
    ): List<TypedAbilityImpl> {
        val all = ArrayList<TypedAbilityImpl>()
        for (m in listOf(entry) + imported + resolutionOnly) {
            all.addAll(m.abilityImpls)
        }
        return all
    }

    /** Walk a TypedExpr collecting concrete generic call sites. The
     *  `out` and `outLocs` lists are kept in lockstep — `outLocs[i]` is
     *  the loc of the call site that produced `out[i]`. */
    private fun collectSeedsFromExpr(
        expr: TypedExpr,
        declIndex: Map<Hash, TypedLetDecl>,
        abilityOpIndex: Map<String, List<AbilityOpEntry>>,
        allImpls: List<TypedAbilityImpl>,
        out: MutableList<Monomorphizer.SpecRequest>,
        outLocs: MutableList<Loc>,
        counters: Counters
    ) {
        if (expr is TypedCall) {
            val fn = expr.function

            // Direct generic call: callee is a TypedVar(ByHash(genericHash))
            if (fn is TypedVar && fn.binding is BindingRef.ByHash) {
                val calleeHash = (fn.binding as BindingRef.ByHash).hash
                val callee = declIndex[calleeHash]
                if (callee != null && callee.typeParams.isNotEmpty()) {
                    counters.byHashGenericVisited++
                    val fromFn = deriveTypeArgs(callee.scheme.type, callee.typeParams, fn.type)
                        ?.takeIf { it.all { t -> isConcrete(t) } }
                    val args = if (fromFn != null) {
                        counters.byHashFnTypeOk++
                        fromFn
                    } else {
                        val fromArg = deriveTypeArgsFromArg(callee, expr.arg.type)
                        if (fromArg != null && fromArg.all { isConcrete(it) }) {
                            counters.byHashArgTypeOk++
                            fromArg
                        } else null
                    }
                    if (args != null && args.all { isConcrete(it) }) {
                        // Resolve ability constraints on the callee's surface
                        // type now that we have concrete typeArgs. Each
                        // <Show 'a> on the function type resolves to an
                        // AbilityImplRef using the binding for 'a.
                        val resolvedImpls = resolveAbilityConstraints(
                            callee, args, abilityImpls = allImpls
                        )
                        out.add(Monomorphizer.SpecRequest(calleeHash, args, resolvedImpls))
                        outLocs.add(expr.loc)
                        counters.byHashSeeded++
                    } else {
                        counters.byHashUnifyFail++
                    }
                }
            }

            // Method-style call: fn is a TypedFieldAccess. Resolve the
            // (receiver.type, methodName) pair to an ability op and seed
            // the op for specialization.
            if (fn is TypedFieldAccess) {
                seedFromMethodCall(fn, expr.loc, expr.arg, abilityOpIndex, out, outLocs, counters)
            }

            collectSeedsFromExpr(fn, declIndex, abilityOpIndex, allImpls, out, outLocs, counters)
            collectSeedsFromExpr(expr.arg, declIndex, abilityOpIndex, allImpls, out, outLocs, counters)
            return
        }
        // Recurse through every other expression shape.
        when (expr) {
            is TypedLambda -> collectSeedsFromExpr(expr.body, declIndex, abilityOpIndex, allImpls, out, outLocs, counters)
            is TypedRecord -> for (e in expr.entries) when (e) {
                is TypedRecordEntry.Field -> collectSeedsFromExpr(e.value, declIndex, abilityOpIndex, allImpls, out, outLocs, counters)
                is TypedRecordEntry.Spread -> collectSeedsFromExpr(e.expr, declIndex, abilityOpIndex, allImpls, out, outLocs, counters)
            }
            is TypedRecordUpdate -> {
                collectSeedsFromExpr(expr.record, declIndex, abilityOpIndex, allImpls, out, outLocs, counters)
                for (e in expr.updates) when (e) {
                    is TypedRecordEntry.Field -> collectSeedsFromExpr(e.value, declIndex, abilityOpIndex, allImpls, out, outLocs, counters)
                    is TypedRecordEntry.Spread -> collectSeedsFromExpr(e.expr, declIndex, abilityOpIndex, allImpls, out, outLocs, counters)
                }
            }
            is TypedFieldAccess -> collectSeedsFromExpr(expr.record, declIndex, abilityOpIndex, allImpls, out, outLocs, counters)
            is TypedVariantConstruct -> expr.payload?.let { collectSeedsFromExpr(it, declIndex, abilityOpIndex, allImpls, out, outLocs, counters) }
            is TypedMatch -> {
                collectSeedsFromExpr(expr.scrutinee, declIndex, abilityOpIndex, allImpls, out, outLocs, counters)
                for (c in expr.cases) {
                    c.guard?.let { collectSeedsFromExpr(it, declIndex, abilityOpIndex, allImpls, out, outLocs, counters) }
                    collectSeedsFromExpr(c.body, declIndex, abilityOpIndex, allImpls, out, outLocs, counters)
                }
            }
            is TypedIf -> {
                collectSeedsFromExpr(expr.condition, declIndex, abilityOpIndex, allImpls, out, outLocs, counters)
                collectSeedsFromExpr(expr.thenBranch, declIndex, abilityOpIndex, allImpls, out, outLocs, counters)
                collectSeedsFromExpr(expr.elseBranch, declIndex, abilityOpIndex, allImpls, out, outLocs, counters)
            }
            is TypedBlock -> {
                for (s in expr.statements) when (s) {
                    is TypedStatement.Let -> collectSeedsFromExpr(s.value, declIndex, abilityOpIndex, allImpls, out, outLocs, counters)
                    is TypedStatement.Expr -> collectSeedsFromExpr(s.expr, declIndex, abilityOpIndex, allImpls, out, outLocs, counters)
                }
                collectSeedsFromExpr(expr.result, declIndex, abilityOpIndex, allImpls, out, outLocs, counters)
            }
            is TypedSeq -> for (el in expr.elements) when (el) {
                is TypedSeqElement.Regular -> collectSeedsFromExpr(el.expr, declIndex, abilityOpIndex, allImpls, out, outLocs, counters)
                is TypedSeqElement.Spread -> collectSeedsFromExpr(el.expr, declIndex, abilityOpIndex, allImpls, out, outLocs, counters)
            }
            is TypedPipe -> {
                collectSeedsFromExpr(expr.left, declIndex, abilityOpIndex, allImpls, out, outLocs, counters)
                collectSeedsFromExpr(expr.right, declIndex, abilityOpIndex, allImpls, out, outLocs, counters)
            }
            is TypedPerform -> collectSeedsFromExpr(expr.body, declIndex, abilityOpIndex, allImpls, out, outLocs, counters)
            is TypedPerformOp -> collectSeedsFromExpr(expr.arg, declIndex, abilityOpIndex, allImpls, out, outLocs, counters)
            is TypedStringLit -> for (part in expr.parts) {
                if (part is TypedStringPart.Interpolation) collectSeedsFromExpr(part.expr, declIndex, abilityOpIndex, allImpls, out, outLocs, counters)
            }
            else -> Unit
        }
    }

    /**
     * Resolve a method call `receiver.methodName { args }` to an ability
     * op and emit a seed if the type args come out concrete.
     *
     * The op body is a lambda whose param type is a record like
     * `{it: T_self, …op-fields}`. The call-site argument is a record
     * with the same fields (TypedAstBuilder injects `it = receiver`).
     * Unifying these recovers the type variable bindings.
     */
    private fun seedFromMethodCall(
        fa: TypedFieldAccess,
        callLoc: Loc,
        callArg: TypedExpr,
        abilityOpIndex: Map<String, List<AbilityOpEntry>>,
        out: MutableList<Monomorphizer.SpecRequest>,
        outLocs: MutableList<Loc>,
        counters: Counters
    ) {
        val candidates = abilityOpIndex[fa.field] ?: return
        val genericCandidates = candidates.filter { it.impl.forallVars.isNotEmpty() }
        if (genericCandidates.isEmpty()) return
        counters.methodGenericVisited++

        // Recover the concrete receiver type. Prefer `fa.record.type`
        // (the receiver expression's stamped type), but fall back to
        // extracting the receiver field from the call's arg record —
        // TypedAstBuilder injects the receiver as `it` (or whatever
        // `receiverFieldName` the impl uses) into the wrapped arg
        // record, and arg field types are stamped per-loc more
        // reliably than receiver expression types.
        val receiverType = if (isConcrete(fa.record.type)) {
            fa.record.type
        } else {
            extractReceiverFromArgRecord(callArg.type)
        }
        if (receiverType == null || !isConcrete(receiverType)) {
            counters.methodReceiverNotConcrete++
            return
        }

        val match = genericCandidates.firstOrNull { entry ->
            entry.impl.targetTypes.any { typeMatchesTarget(receiverType, it) }
        } ?: run {
            if (genericCandidates.size == 1) genericCandidates.first() else {
                counters.methodNoMatchingImpl++
                return
            }
        }

        val opBody = match.impl.operations[fa.field] ?: return
        if (opBody !is TypedLambda) {
            counters.methodUnifyFail++
            return
        }
        val opParamType = opBody.params.firstOrNull()?.type ?: run {
            counters.methodUnifyFail++
            return
        }

        val args = deriveTypeArgs(opParamType, match.impl.forallVars, callArg.type)
        if (args != null && args.all { isConcrete(it) }) {
            out.add(Monomorphizer.SpecRequest(match.opHash, args, emptyList()))
            outLocs.add(callLoc)
            counters.methodSeeded++
        } else {
            counters.methodUnifyFail++
        }
    }

    /** TypedAstBuilder wraps method-call args as a record with the
     *  receiver injected as a field (`it` for ability methods).
     *  When the receiver expression's own type is unresolved, the
     *  arg-record version is more often concrete because the
     *  typechecker stamps arg field types per-loc. */
    private fun extractReceiverFromArgRecord(argType: MezzeType): MezzeType? {
        if (argType !is MezzeType.RecordType) return null
        // Common receiver field names in order of likelihood.
        for (field in listOf("it", "self", "this")) {
            argType.fields[field]?.let { if (isConcrete(it)) return it }
        }
        return null
    }

    /** Derive concrete type args for `typeParams` by unifying the generic
     *  pattern against the call-site value type. Returns args in
     *  `typeParams` order, or null if some param remains unbound. */
    private fun deriveTypeArgs(
        genericType: MezzeType,
        typeParams: List<TypeParam>,
        siteType: MezzeType
    ): List<MezzeType>? {
        if (typeParams.isEmpty()) return emptyList()
        val nameToBinding = HashMap<String, MezzeType>()
        if (!unify(genericType, siteType, nameToBinding)) return null
        val args = ArrayList<MezzeType>(typeParams.size)
        for (tp in typeParams) {
            args.add(nameToBinding[tp.name] ?: return null)
        }
        return args
    }

    /** Fallback: derive type args by unifying the callee's declared param
     *  type against the call-site arg type. Useful when the function-
     *  reference's `fn.type` was instantiated with fresh VarTypes the
     *  typechecker didn't push back into the AST.
     *
     *  Only works when the callee's `scheme.type` is a FunctionType. */
    private fun deriveTypeArgsFromArg(
        callee: TypedLetDecl,
        argSiteType: MezzeType
    ): List<MezzeType>? {
        val genericFn = callee.scheme.type as? MezzeType.FunctionType ?: return null
        return deriveTypeArgs(genericFn.paramType, callee.typeParams, argSiteType)
    }

    /**
     * Resolve every `<Show 'a>` (etc.) ability constraint on the callee's
     * function type to a concrete `AbilityImplRef` using the type-arg
     * bindings we just derived.
     *
     * Mirrors the typechecker's `ConstraintSolver.findMatchingAbilityImpl`
     * logic, but operates on `TypedAbilityImpl` (the post-typecheck side)
     * instead of `TypingContext.AbilityImplInfo` (the pre-typecheck side).
     * The matching is the same shape — by ability hash + targetTypes.
     *
     * Returns one `AbilityImplRef` per resolved constraint. Constraints
     * we can't resolve are silently dropped — the worklist falls back
     * gracefully (the specialized body still has the constraint, mono
     * just can't pin it; dict-passing catches it at runtime).
     */
    private fun resolveAbilityConstraints(
        callee: TypedLetDecl,
        typeArgs: List<MezzeType>,
        abilityImpls: List<TypedAbilityImpl>
    ): List<AbilityImplRef> {
        val fnType = callee.scheme.type as? MezzeType.FunctionType ?: return emptyList()
        if (fnType.abilityConstraints.isEmpty()) return emptyList()

        // Build name → concrete-type map from the derived typeArgs.
        val nameToConcrete = HashMap<String, MezzeType>(callee.typeParams.size)
        for (i in callee.typeParams.indices) {
            nameToConcrete[callee.typeParams[i].name] = typeArgs[i]
        }

        val out = ArrayList<AbilityImplRef>(fnType.abilityConstraints.size)
        for (constraint in fnType.abilityConstraints) {
            val abilityHash = constraint.abilityHash ?: continue
            // Substitute type vars in the constraint's typeArgs to concrete.
            val concreteArgs = constraint.typeArgs.map { ta ->
                if (ta is MezzeType.VarType) nameToConcrete[ta.variable.name] ?: ta else ta
            }
            // Find an impl whose abilityHash and targetTypes match.
            val match = abilityImpls.firstOrNull { impl ->
                impl.abilityHash == abilityHash &&
                    impl.targetTypes.size == concreteArgs.size &&
                    impl.targetTypes.zip(concreteArgs).all { (target, concrete) ->
                        typeMatchesTarget(concrete, target)
                    }
            } ?: continue
            // The "primary" type for AbilityImplRef.targetType is the
            // first type arg — same convention used elsewhere.
            out.add(AbilityImplRef(
                abilityHash = abilityHash,
                implHash = match.hash,
                targetType = concreteArgs.firstOrNull() ?: MezzeType.IntType
            ))
        }
        return out
    }

}
