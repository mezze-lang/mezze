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
        /** #424 Piece 5 — per SEED call-site loc, the set of arg-record
         *  field names to strip when the compiler routes the call to
         *  the specialized decl. Populated for HOF call sites whose
         *  callee's closure params were dead-code eliminated. */
        val stripBySiteLoc: Map<Loc, Set<String>> = emptyMap(),
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
                // Typechecker stamps op bodies with fresh VarTypes (`'t145`,
                // `'u145`, …) that don't correspond to `impl.forallVars`
                // (`'a`, `'b`). Using `impl.forallVars` as the synthetic
                // decl's typeParams makes seeding impossible: unify binds
                // the fresh names but `deriveTypeArgs` iterates
                // `impl.forallVars` and finds no bindings for them.
                //
                // Collect the actual VarType names appearing in the op's
                // typechecked type and use those as typeParams. Preserve
                // `impl.forallVars` order first so any impl-declared var
                // that also appears keeps its identity; then append any
                // extra typechecker-fresh names.
                val actualVarNames = collectVarTypeNames(opType)
                val typeParams = buildList {
                    val seen = HashSet<String>()
                    for (tp in impl.forallVars) {
                        if (tp.name in actualVarNames && seen.add(tp.name)) add(tp)
                    }
                    for (name in actualVarNames) {
                        if (seen.add(name)) {
                            add(TypeParam(name = name, kind = TypeKind.Star))
                        }
                    }
                }
                val synthetic = TypedLetDecl(
                    hash = opHash,
                    name = opName,
                    typeParams = typeParams,
                    value = opBody,
                    scheme = TypeScheme(
                        typeParams = typeParams,
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
        //
        // #441 Day 8 (Mono Gap B): env-gated stdlib seed walk. When
        // MEZZE_MONO_SEED_STDLIB=1, also walk stdlib CONCRETE decls
        // (typeParams.isEmpty()) for callsites — no seeding from generic
        // decls' bodies (still polymorphic; would produce VarType bindings).
        // Off by default. The gate exists so we can measure the coverage
        // lift without risking a default-on regression on the ~496 generic
        // decls in stdlib (bulk unification cost).
        val seeds = ArrayList<Monomorphizer.SpecRequest>()
        val seedLocs = ArrayList<Loc>()
        val counters = Counters()
        val seedStdlib = System.getenv("MEZZE_MONO_SEED_STDLIB") == "1"
        val seedFrom = if (seedStdlib) listOf(entry) + imported + resolutionOnly
                       else            listOf(entry) + imported
        for (m in seedFrom) {
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
        // #424 Piece 5 — mirror stripMap onto seed sites so the compiler
        // drops the same fields from user-code call sites.
        val stripBySiteLoc = HashMap<Loc, Set<String>>()
        for (i in seeds.indices) {
            val sh = result.callSiteRewrites[seeds[i]] ?: continue
            bySiteLoc[seedLocs[i]] = sh
            result.stripMap[sh]?.let { keys ->
                if (keys.isNotEmpty()) stripBySiteLoc[seedLocs[i]] = keys
            }
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
        return Result(mono = result, bySiteLoc = bySiteLoc, stripBySiteLoc = stripBySiteLoc)
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
                        // #424: if the flag is on, harvest closure args
                        // from this call site — mono keys the spec on
                        // (typeArgs, closureHashes) and Piece 3 splices
                        // the lambda bodies into the specialized body.
                        val closureArgs: Map<String, com.mezze.language.typedast.Hash>
                        val closureBodies: Map<String, com.mezze.language.typedast.TypedLambda>
                        if (Monomorphizer.closuresEnabled) {
                            closureArgs = Monomorphizer.closureArgsAt(expr)
                            closureBodies = Monomorphizer.closureBodiesAt(expr)
                        } else {
                            closureArgs = emptyMap()
                            closureBodies = emptyMap()
                        }
                        out.add(Monomorphizer.SpecRequest(
                            calleeHash, args, resolvedImpls, closureArgs, closureBodies
                        ))
                        outLocs.add(expr.loc)
                        counters.byHashSeeded++
                    } else {
                        counters.byHashUnifyFail++
                        if (System.getenv("MEZZE_MONO_TRACE") != null) {
                            System.err.println("[mono-trace] byHash-fail callee=${callee.name} " +
                                "typeParams=${callee.typeParams.map { it.name }} " +
                                "scheme=${detailed(callee.scheme.type)} " +
                                "fn.type=${detailed(fn.type)} " +
                                "arg.type=${detailed(expr.arg.type)}")
                        }
                    }
                }
            }

            // Method-style call: fn is a TypedFieldAccess. Resolve the
            // (receiver.type, methodName) pair to an ability op and seed
            // the op for specialization.
            if (fn is TypedFieldAccess) {
                seedFromMethodCall(fn, expr, abilityOpIndex, out, outLocs, counters)
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
        call: TypedCall,
        abilityOpIndex: Map<String, List<AbilityOpEntry>>,
        out: MutableList<Monomorphizer.SpecRequest>,
        outLocs: MutableList<Loc>,
        counters: Counters
    ) {
        val callLoc = call.loc
        val callArg = call.arg
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

        // Use the actual VarType names appearing in the op's body — this
        // is what MonoDriver stamped as the synthetic decl's typeParams
        // (see the Gap A fix at collectVarTypeNames above). Passing
        // `match.impl.forallVars` here would produce SpecRequest.typeArgs
        // sized to `impl.forallVars` but the synthetic decl's `typeParams`
        // is sized differently, triggering Monomorphizer's arity-mismatch
        // fallback for every method-style seed on this impl.
        val opTypeParams = buildList {
            val seen = HashSet<String>()
            val actualNames = collectVarTypeNames(opBody.type)
            for (tp in match.impl.forallVars) {
                if (tp.name in actualNames && seen.add(tp.name)) add(tp)
            }
            for (name in actualNames) {
                if (seen.add(name)) add(TypeParam(name = name, kind = TypeKind.Star))
            }
        }
        val args = deriveTypeArgs(opParamType, opTypeParams, callArg.type)
        if (args != null && args.all { isConcrete(it) }) {
            // #424: harvest closure args from the method-style call too.
            // For `xs.fold {init = 0, f = {acc,x} -> ...}`, `f` here is
            // a lambda literal — spec keyed on its hash gets a separate
            // specialization from `xs.fold {init = 0, f = {acc,x} -> ...*2}`.
            val closureArgs: Map<String, com.mezze.language.typedast.Hash>
            val closureBodies: Map<String, com.mezze.language.typedast.TypedLambda>
            if (Monomorphizer.closuresEnabled) {
                closureArgs = Monomorphizer.closureArgsAt(call)
                closureBodies = Monomorphizer.closureBodiesAt(call)
            } else {
                closureArgs = emptyMap()
                closureBodies = emptyMap()
            }
            out.add(Monomorphizer.SpecRequest(
                match.opHash, args, emptyList(), closureArgs, closureBodies
            ))
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
     *  `typeParams` order, or null if some param remains unbound.
     *
     *  [fullSchemeType], if provided, is the callee's ENTIRE scheme type
     *  (param+return). Used only for the phantom-typeParam check — a
     *  typeParam that doesn't appear anywhere in the scheme is truly
     *  phantom and safe to fill with a stable placeholder. When null,
     *  falls back to [genericType] for that check, which is fine when
     *  the caller passes the full scheme as [genericType]. */
    private fun deriveTypeArgs(
        genericType: MezzeType,
        typeParams: List<TypeParam>,
        siteType: MezzeType,
        fullSchemeType: MezzeType? = null,
    ): List<MezzeType>? {
        if (typeParams.isEmpty()) return emptyList()
        val phantomProbe = fullSchemeType ?: genericType
        val nameToBinding = HashMap<String, MezzeType>()
        if (!unify(genericType, siteType, nameToBinding)) return null
        val args = ArrayList<MezzeType>(typeParams.size)
        for (tp in typeParams) {
            val binding = nameToBinding[tp.name]
            if (binding != null) {
                args.add(binding)
            } else if (isEffectVarName(tp.name)) {
                // Effect-var type params (named 'eNNN by generalize) don't
                // appear in the value-type signature — they live only in
                // EffectRow.rowVar. When the caller doesn't add extra
                // effects, safely default to the empty effect row so mono
                // can still specialize. This unlocks specialization for
                // any user-defined helper that internally calls an
                // effect-polymorphic stdlib function (very common: `sum_to
                // = {n} -> range.fold {...}` inherits `<'e>` from fold).
                args.add(MezzeType.EffectRow.simple(emptySet()))
            } else if (!typeParamAppearsIn(tp.name, phantomProbe)) {
                // "Phantom" type param — declared in the scheme but never
                // referenced by the actual type. This happens for ability
                // impl op bodies where the impl's forallVars include a
                // param that the specific op's typechecked body has
                // already resolved to a concrete type (e.g.
                // `impl Seq 'a where { sum = {it} -> sum {xs = it} }` —
                // the top-level `sum` requires `[Int]`, so the op's `'a`
                // is already pinned to `Int` in the op body's type, but
                // `impl.forallVars` still lists `'a`).
                //
                // Substitution over an absent name is a no-op, so any
                // placeholder yields the same specialized body. Pick a
                // stable value so the spec-hash is deterministic.
                args.add(MezzeType.IntType)
            } else {
                return null
            }
        }
        return args
    }

    /** Public alias for `detailed` — external tracing (Monomorphizer) uses this. */
    fun detailedPublic(t: MezzeType?): String = detailed(t)

    /** Verbose type printer for `MEZZE_MONO_TRACE` — MezzeType.toString() is
     *  depth-truncated, which hides the exact TypeVar names / element types
     *  we need when diagnosing unify failures. */
    private fun detailed(t: MezzeType?): String {
        if (t == null) return "null"
        return when (t) {
            is MezzeType.VarType -> "'${t.variable.name}#${t.variable.id}"
            is MezzeType.FunctionType -> "(${detailed(t.paramType)} -> ${detailed(t.returnType)})"
            is MezzeType.RecordType -> t.fields.entries.joinToString(prefix = "{", postfix = "}") { "${it.key}: ${detailed(it.value)}" }
            is MezzeType.VariantType -> t.constructors.entries.joinToString(prefix = "V<${t.typeName ?: "?"}: ", postfix = ">") { "${it.key}(${detailed(it.value ?: MezzeType.RecordType(emptyMap()))})" }
            is MezzeType.ListType -> "List[${detailed(t.elementType)}]"
            is MezzeType.SeqType -> "Seq[${detailed(t.elementType)}]"
            is MezzeType.SetType -> "Set[${detailed(t.elementType)}]"
            is MezzeType.SortedSetType -> "SortedSet[${detailed(t.elementType)}]"
            is MezzeType.MapType -> "Map[${detailed(t.keyType)}, ${detailed(t.valueType)}]"
            is MezzeType.SortedMapType -> "SortedMap[${detailed(t.keyType)}, ${detailed(t.valueType)}]"
            is MezzeType.SuspendType -> "Susp[${detailed(t.valueType)}]"
            is MezzeType.NewtypeType -> "New(${t.name})[${detailed(t.wrappedType)}]"
            is MezzeType.TypeApplication -> "App(${t.constructor})[${t.args.joinToString { detailed(it) }}]"
            is MezzeType.IntType -> "Int"
            is MezzeType.FloatType -> "Float"
            is MezzeType.StringType -> "Str"
            is MezzeType.BoolType -> "Bool"
            is MezzeType.NatType -> "Nat"
            is MezzeType.FracType -> "Frac"
            is MezzeType.CharType -> "Char"
            is MezzeType.OrderingType -> "Ordering"
            is MezzeType.BytesType -> "Bytes"
            is MezzeType.ByteBufferType -> "ByteBuffer"
            is MezzeType.EffectRow -> "<eff>"
        }
    }

    /** Collect every VarType name reachable from [type], in first-encounter
     *  order. Deterministic ordering matters because these names become
     *  the synthetic ability-op decl's typeParams, and typeParam order
     *  drives the specialization hash. */
    private fun collectVarTypeNames(type: MezzeType): List<String> {
        val seen = LinkedHashSet<String>()
        fun go(t: MezzeType) {
            when (t) {
                is MezzeType.VarType -> seen.add(t.variable.name)
                is MezzeType.FunctionType -> { go(t.paramType); go(t.returnType) }
                is MezzeType.RecordType -> t.fields.values.forEach(::go)
                is MezzeType.VariantType -> t.constructors.values.forEach { it?.let(::go) }
                is MezzeType.ListType -> go(t.elementType)
                is MezzeType.SeqType -> go(t.elementType)
                is MezzeType.SetType -> go(t.elementType)
                is MezzeType.SortedSetType -> go(t.elementType)
                is MezzeType.MapType -> { go(t.keyType); go(t.valueType) }
                is MezzeType.SortedMapType -> { go(t.keyType); go(t.valueType) }
                is MezzeType.SuspendType -> go(t.valueType)
                is MezzeType.NewtypeType -> go(t.wrappedType)
                is MezzeType.TypeApplication -> t.args.forEach(::go)
                is MezzeType.IntType, is MezzeType.FloatType, is MezzeType.StringType,
                is MezzeType.BoolType, is MezzeType.NatType, is MezzeType.FracType,
                is MezzeType.OrderingType, is MezzeType.BytesType, is MezzeType.ByteBufferType,
                is MezzeType.CharType, is MezzeType.EffectRow -> Unit
            }
        }
        go(type)
        return seen.toList()
    }

    /** True if `typeParamName` appears anywhere in `type`. Used to
     *  distinguish "phantom" type params (declared but unreferenced)
     *  from real ones that must be bound by unification. */
    private fun typeParamAppearsIn(typeParamName: String, type: MezzeType): Boolean {
        return when (type) {
            is MezzeType.VarType -> type.variable.name == typeParamName
            is MezzeType.FunctionType -> typeParamAppearsIn(typeParamName, type.paramType) ||
                typeParamAppearsIn(typeParamName, type.returnType)
            is MezzeType.RecordType -> type.fields.values.any { typeParamAppearsIn(typeParamName, it) }
            is MezzeType.VariantType -> type.constructors.values.any {
                it != null && typeParamAppearsIn(typeParamName, it)
            }
            is MezzeType.ListType -> typeParamAppearsIn(typeParamName, type.elementType)
            is MezzeType.SeqType -> typeParamAppearsIn(typeParamName, type.elementType)
            is MezzeType.SetType -> typeParamAppearsIn(typeParamName, type.elementType)
            is MezzeType.SortedSetType -> typeParamAppearsIn(typeParamName, type.elementType)
            is MezzeType.MapType -> typeParamAppearsIn(typeParamName, type.keyType) ||
                typeParamAppearsIn(typeParamName, type.valueType)
            is MezzeType.SortedMapType -> typeParamAppearsIn(typeParamName, type.keyType) ||
                typeParamAppearsIn(typeParamName, type.valueType)
            is MezzeType.SuspendType -> typeParamAppearsIn(typeParamName, type.valueType)
            is MezzeType.NewtypeType -> typeParamAppearsIn(typeParamName, type.wrappedType)
            is MezzeType.TypeApplication -> type.args.any { typeParamAppearsIn(typeParamName, it) }
            is MezzeType.IntType, is MezzeType.FloatType, is MezzeType.StringType,
            is MezzeType.BoolType, is MezzeType.NatType, is MezzeType.FracType,
            is MezzeType.OrderingType, is MezzeType.BytesType, is MezzeType.ByteBufferType,
            is MezzeType.CharType, is MezzeType.EffectRow -> false
        }
    }

    /** Effect-var type params are named `'eNNN` by generalization —
     *  the leading `e` after the tick is the convention. Value-type
     *  params start with `'a`, `'b`, ..., `'x`, etc. */
    private fun isEffectVarName(name: String): Boolean {
        // Names arrive with the tick, e.g. "'e271".
        val stripped = name.removePrefix("'")
        return stripped.length >= 2 && stripped.startsWith("e") && stripped[1].isDigit()
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
        return deriveTypeArgs(
            genericFn.paramType, callee.typeParams, argSiteType,
            fullSchemeType = callee.scheme.type,
        )
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
