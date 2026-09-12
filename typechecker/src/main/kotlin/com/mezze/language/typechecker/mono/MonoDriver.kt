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
        /** BUG B / #333: declared record-type field shapes, keyed by the type decl's
         *  content hash. Supplied by the caller because `entry` is post-`wrapTopLevel`,
         *  which strips `TypedTypeDecl`s — so shapes must come from the un-wrapped
         *  modules. Used to structurally recover the nominal identity of an
         *  identity-less record receiver at ability-impl resolution. */
        recordShapes: Map<Hash, Map<String, MezzeType>> = emptyMap(),
    ): Result {
        // Index ordinary let decls.
        val declIndex = HashMap<Hash, TypedLetDecl>()
        // Merge any record shapes still present on the passed modules (defensive; the
        // caller's map is the authoritative source since `entry` is wrapped).
        val recordShapeByHash = HashMap<Hash, Map<String, MezzeType>>(recordShapes)
        for (m in (listOf(entry) + imported + resolutionOnly)) {
            for (d in m.decls) {
                if (d is TypedLetDecl) declIndex[d.hash] = d
                if (d is com.mezze.language.typedast.TypedTypeDecl) {
                    val def = d.definition
                    if (def is com.mezze.language.typedast.TypedRecordDef) {
                        recordShapeByHash.putIfAbsent(d.hash, def.fields)
                    }
                }
            }
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
                // Include element-level forallVars (`'a`) that the laundered op
                // surface type omits, so the substitution reaches an inner comparator
                // closure's `a < b`. Must match the request-side typeParams in
                // seedFromMethodCall (both via opDeclTypeParamsWithForall).
                val (typeParams, _) = opDeclTypeParamsWithForall(opType, impl, null)
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
                collectSeedsFromExpr(d.value, declIndex, abilityOpIndex, abilityImpls, seeds, seedLocs, counters, recordShapeByHash)
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
        counters: Counters,
        recordShapes: Map<Hash, Map<String, MezzeType>>,
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
                        val fromArg = deriveTypeArgsFromArg(callee, expr.arg.type, allowPartial = true)
                        // A `having 'out` typeParam (e.g. `NumAdd Int 'a having 'out='b`,
                        // from a constrained fn propagated through a HOF) is not derivable
                        // from the argument alone — `'b` only becomes concrete once the
                        // NumAdd impl is resolved for the (now-concrete) receiver+class
                        // args. Fill those remaining out-vars from the ability's `having`
                        // bindings BEFORE the all-concrete gate, so mono can specialize a
                        // HOF-propagated constrained fn (`g = wrap {f = addTen}`; `g{x=5}`).
                        val filled = fromArg?.let { fillHavingOutTypeArgs(callee, it, allImpls) }
                        if (filled != null && filled.all { isConcrete(it) }) {
                            counters.byHashArgTypeOk++
                            filled
                        } else null
                    }
                    if (args != null && args.all { isConcrete(it) }) {
                        // Resolve ability constraints on the callee's surface
                        // type now that we have concrete typeArgs. Each
                        // <Show 'a> on the function type resolves to an
                        // AbilityImplRef using the binding for 'a.
                        val resolvedImpls = resolveAbilityConstraints(
                            callee, args, abilityImpls = allImpls, recordShapes = recordShapes
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
                seedFromMethodCall(fn, expr, abilityOpIndex, out, outLocs, counters, recordShapes)
            }

            collectSeedsFromExpr(fn, declIndex, abilityOpIndex, allImpls, out, outLocs, counters, recordShapes)
            collectSeedsFromExpr(expr.arg, declIndex, abilityOpIndex, allImpls, out, outLocs, counters, recordShapes)
            return
        }
        // Recurse through every other expression shape.
        when (expr) {
            is TypedLambda -> collectSeedsFromExpr(expr.body, declIndex, abilityOpIndex, allImpls, out, outLocs, counters, recordShapes)
            is TypedRecord -> for (e in expr.entries) when (e) {
                is TypedRecordEntry.Field -> collectSeedsFromExpr(e.value, declIndex, abilityOpIndex, allImpls, out, outLocs, counters, recordShapes)
                is TypedRecordEntry.Spread -> collectSeedsFromExpr(e.expr, declIndex, abilityOpIndex, allImpls, out, outLocs, counters, recordShapes)
            }
            is TypedRecordUpdate -> {
                collectSeedsFromExpr(expr.record, declIndex, abilityOpIndex, allImpls, out, outLocs, counters, recordShapes)
                for (e in expr.updates) when (e) {
                    is TypedRecordEntry.Field -> collectSeedsFromExpr(e.value, declIndex, abilityOpIndex, allImpls, out, outLocs, counters, recordShapes)
                    is TypedRecordEntry.Spread -> collectSeedsFromExpr(e.expr, declIndex, abilityOpIndex, allImpls, out, outLocs, counters, recordShapes)
                }
            }
            is TypedFieldAccess -> collectSeedsFromExpr(expr.record, declIndex, abilityOpIndex, allImpls, out, outLocs, counters, recordShapes)
            is TypedVariantConstruct -> expr.payload?.let { collectSeedsFromExpr(it, declIndex, abilityOpIndex, allImpls, out, outLocs, counters, recordShapes) }
            is TypedMatch -> {
                collectSeedsFromExpr(expr.scrutinee, declIndex, abilityOpIndex, allImpls, out, outLocs, counters, recordShapes)
                for (c in expr.cases) {
                    c.guard?.let { collectSeedsFromExpr(it, declIndex, abilityOpIndex, allImpls, out, outLocs, counters, recordShapes) }
                    collectSeedsFromExpr(c.body, declIndex, abilityOpIndex, allImpls, out, outLocs, counters, recordShapes)
                }
            }
            is TypedIf -> {
                collectSeedsFromExpr(expr.condition, declIndex, abilityOpIndex, allImpls, out, outLocs, counters, recordShapes)
                collectSeedsFromExpr(expr.thenBranch, declIndex, abilityOpIndex, allImpls, out, outLocs, counters, recordShapes)
                collectSeedsFromExpr(expr.elseBranch, declIndex, abilityOpIndex, allImpls, out, outLocs, counters, recordShapes)
            }
            is TypedBlock -> {
                for (s in expr.statements) when (s) {
                    is TypedStatement.Let -> collectSeedsFromExpr(s.value, declIndex, abilityOpIndex, allImpls, out, outLocs, counters, recordShapes)
                    is TypedStatement.Expr -> collectSeedsFromExpr(s.expr, declIndex, abilityOpIndex, allImpls, out, outLocs, counters, recordShapes)
                }
                collectSeedsFromExpr(expr.result, declIndex, abilityOpIndex, allImpls, out, outLocs, counters, recordShapes)
            }
            is TypedSeq -> for (el in expr.elements) when (el) {
                is TypedSeqElement.Regular -> collectSeedsFromExpr(el.expr, declIndex, abilityOpIndex, allImpls, out, outLocs, counters, recordShapes)
                is TypedSeqElement.Spread -> collectSeedsFromExpr(el.expr, declIndex, abilityOpIndex, allImpls, out, outLocs, counters, recordShapes)
            }
            is TypedPipe -> {
                collectSeedsFromExpr(expr.left, declIndex, abilityOpIndex, allImpls, out, outLocs, counters, recordShapes)
                collectSeedsFromExpr(expr.right, declIndex, abilityOpIndex, allImpls, out, outLocs, counters, recordShapes)
            }
            is TypedPerform -> collectSeedsFromExpr(expr.body, declIndex, abilityOpIndex, allImpls, out, outLocs, counters, recordShapes)
            is TypedPerformOp -> collectSeedsFromExpr(expr.arg, declIndex, abilityOpIndex, allImpls, out, outLocs, counters, recordShapes)
            is TypedStringLit -> for (part in expr.parts) {
                if (part is TypedStringPart.Interpolation) collectSeedsFromExpr(part.expr, declIndex, abilityOpIndex, allImpls, out, outLocs, counters, recordShapes)
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
        counters: Counters,
        recordShapes: Map<Hash, Map<String, MezzeType>>,
    ) {
        val callLoc = call.loc
        val callArg = call.arg
        val candidates = abilityOpIndex[fa.field] ?: return
        // Codec ENTRY-POINT ops (from_json / a user codec's entry op) dispatch
        // their inner decode on the TARGET type, which stays a bare var here —
        // mono can't resolve that inner op, so a specialized copy would bake it
        // as a failing field access. Leave them GENERIC: the compiler's
        // per-call-site decoder wiring handles them. Identified by the op's own
        // decode-flavored ability-constraint HASH, never by op name.
        if (candidates.any { entry ->
                (entry.impl.operations[fa.field]?.type as? MezzeType.FunctionType)
                    ?.abilityConstraints?.any {
                        com.mezze.language.typechecker.WellKnownHashConstants
                            .isDecodeFlavoredCodec(it.abilityHash)
                    } == true
            }) return
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
            if (genericCandidates.size == 1) genericCandidates.first()
            // BUG B / #333: an identity-less record receiver never matches a nominal
            // record target via typeMatchesTarget (which keys records on identity).
            // Recover it structurally, but ONLY when exactly one candidate impl's
            // declared record shape claims the receiver — never force an ambiguous choice.
            else genericCandidates.singleOrNull { entry ->
                entry.impl.targetTypes.any { anonymousRecordMatchesDeclaredRecord(receiverType, it, recordShapes) }
            } ?: run {
                counters.methodNoMatchingImpl++
                return
            }
        }

        val opBody = match.impl.operations[fa.field] ?: return
        if (opBody !is TypedLambda) {
            counters.methodUnifyFail++
            return
        }
        // Bug B (#86): unify against the arrow's paramType (the arg record),
        // not the FIRST destructured param's type. For a blanket-impl body
        // `{it: 'a} with … -> Json = { it } -> …`, `params[0].type` is a bare
        // 'a (or a fresh var when ctxType didn't carry structure); unifying
        // that against the call-site's arg record `{it: Point}` binds
        // 'a→{it: Point} — the wrong substitution that surfaces as an
        // ".encode field-not-found" runtime pattern in the spec body. Falls
        // back to the old path when the arrow shape isn't a FunctionType.
        val opParamType = (opBody.type as? MezzeType.FunctionType)?.paramType
            ?: opBody.params.firstOrNull()?.type
            ?: run {
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
        // Element-typevar recovery: the op's laundered surface type names its
        // params after RECORD FIELDS (`it`/`r`/`ret`), not the impl's real element
        // type var (`'a`). So `deriveTypeArgs` binds it/r/ret but leaves the body's
        // `'a` (used by an inner comparator closure's `a < b`) unbound — mono then
        // strips the dict AND leaves `'a` generic, so `a < b` falls to a primitive
        // node and crashes on user records ([P] < [P]). Recover `'a` by unifying the
        // impl TARGET (e.g. `['a]`) against the concrete receiver (`[P]`) and append
        // those forallVars to the synthetic decl's typeParams (same shape the decl
        // uses in MonoDriver.buildOpDecls). See project_impl_op_with_constraint_dropped.
        val (opTypeParams, extraForallArgs) = opDeclTypeParamsWithForall(
            opBody.type, match.impl, receiverType)
        val baseArgs = deriveTypeArgs(opParamType, opTypeParams.dropLast(extraForallArgs.size), callArg.type)
        val args = if (baseArgs != null) baseArgs + extraForallArgs else null
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
        // When true, a non-phantom typeParam that the site couldn't bind is left as
        // its own VarType (unresolved) instead of aborting with null — so a caller
        // can complete it afterwards (e.g. filling a `having 'out` var via the
        // resolved ability impl). The caller is responsible for the final
        // all-concrete check.
        allowPartial: Boolean = false,
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
            } else if (allowPartial) {
                // Leave this typeParam unresolved (a var carrying its param name); the
                // caller will try to complete it (e.g. a `having 'out` via ability
                // resolution), then run the final all-concrete check.
                args.add(MezzeType.VarType(com.mezze.language.ast.TypeVar.freshWithName(tp.name)))
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
            is MezzeType.VariantType -> t.constructors.entries.joinToString(prefix = "V<${t.identity.displayNameOrNull() ?: "?"}: ", postfix = ">") { "${it.key}(${detailed(it.value ?: MezzeType.RecordType(emptyMap()))})" }
            is MezzeType.ListType -> "List[${detailed(t.elementType)}]"
            is MezzeType.SeqType -> "Seq[${detailed(t.elementType)}]"
            is MezzeType.SetType -> "Set[${detailed(t.elementType)}]"
            is MezzeType.SortedSetType -> "SortedSet[${detailed(t.elementType)}]"
            is MezzeType.MapType -> "Map[${detailed(t.keyType)}, ${detailed(t.valueType)}]"
            is MezzeType.SortedMapType -> "SortedMap[${detailed(t.keyType)}, ${detailed(t.valueType)}]"
            is MezzeType.NewtypeType -> "New(${t.name})[${detailed(t.wrappedType)}]"
            is MezzeType.TypeApplication -> "App(${t.constructor})[${t.args.joinToString { detailed(it) }}]"
            is MezzeType.IntType -> "Int"
            is MezzeType.FloatType -> "Float"
            is MezzeType.StringType -> "Str"
            is MezzeType.BoolType -> "Bool"
            is MezzeType.FracType -> "Frac"
            is MezzeType.CharType -> "Char"
            is MezzeType.BytesType -> "Bytes"
            is MezzeType.EffectRow -> "<eff>"
        }
    }

    /** Collect every VarType name reachable from [type], in first-encounter
     *  order. Deterministic ordering matters because these names become
     *  the synthetic ability-op decl's typeParams, and typeParam order
     *  drives the specialization hash. */
    /**
     * The synthetic ability-op decl's typeParams, PLUS the impl's element-level
     * forallVars (`'a`) that the laundered op surface type omits. Returns the full
     * typeParam list (base field-named vars, then appended forallVars) and the
     * concrete args for those appended forallVars, recovered by unifying the impl's
     * TARGET type against the concrete receiver. Base and request derivation share
     * this so their typeParam arity stays aligned. When `receiverType` is null
     * (decl-build time — no call site), the appended forallVars carry a fresh var
     * placeholder and an empty extra-args list. See project_impl_op_with_constraint_dropped.
     */
    /** The element type-var NAME of a built-in container type whose element is a bare
     *  VarType (`[ 'a ]` → "a"), else null. Excludes `Option 'a` / records / nominal
     *  TypeApplications — only Seq/List/Set/SortedSet/Map/SortedMap containers. */
    private fun elementVarName(type: MezzeType): String? {
        val elem = when (type) {
            is MezzeType.SeqType -> type.elementType
            is MezzeType.ListType -> type.elementType
            is MezzeType.SetType -> type.elementType
            is MezzeType.SortedSetType -> type.elementType
            is MezzeType.MapType -> type.valueType
            is MezzeType.SortedMapType -> type.valueType
            else -> null
        }
        return (elem as? MezzeType.VarType)?.variable?.name
    }

    private fun opDeclTypeParamsWithForall(
        opType: MezzeType,
        impl: TypedAbilityImpl,
        receiverType: MezzeType?
    ): Pair<List<TypeParam>, List<MezzeType>> {
        val base = buildList {
            val seen = HashSet<String>()
            val actualNames = collectVarTypeNames(opType)
            for (tp in impl.forallVars) if (tp.name in actualNames && seen.add(tp.name)) add(tp)
            for (name in actualNames) if (seen.add(name)) add(TypeParam(name = name, kind = TypeKind.Star))
        }
        val baseNames = base.map { it.name }.toHashSet()
        // NARROW to the exact laundering case: a CONTAINER target (Seq/List/Set/…)
        // whose element type var is dropped from the op's laundered surface type. Other
        // impls (variant/record like `impl Eq for Option 'a`) keep their existing
        // typeParams untouched — appending a forallVar there changes the spec arity and
        // corrupts an already-working derived op. Only recover forallVars that (a) are
        // missing from the base AND (b) actually appear as the container's element var.
        val containerElementVarNames = impl.targetTypes
            .mapNotNull { elementVarName(it) }
            .toHashSet()
        val missing = impl.forallVars.filter { it.name !in baseNames && it.name in containerElementVarNames }
        if (missing.isEmpty()) return base to emptyList()
        // Recover each missing forallVar's concrete type by unifying the impl target
        // against the receiver (e.g. `['a]` vs `[P]` → `'a = P`).
        val bindings = HashMap<String, MezzeType>()
        if (receiverType != null) {
            for (target in impl.targetTypes) unify(target, receiverType, bindings)
        }
        val extraArgs = missing.map { tp ->
            bindings[tp.name] ?: MezzeType.VarType(TypeVar.freshWithName(tp.name))
        }
        return (base + missing) to extraArgs
    }

    private fun collectVarTypeNames(type: MezzeType): List<String> {
        val seen = LinkedHashSet<String>()
        fun go(t: MezzeType) {
            when (t) {
                is MezzeType.VarType -> seen.add(t.variable.name)
                is MezzeType.FunctionType -> { go(t.paramType); go(t.returnType) }
                is MezzeType.RecordType -> t.fields.values.forEach(::go)
                is MezzeType.VariantType -> t.constructors.values.forEach { it?.let(::go) }
                is MezzeType.ParametricBuiltin -> t.typeArgs.forEach(::go)
                is MezzeType.NewtypeType -> go(t.wrappedType)
                is MezzeType.TypeApplication -> t.args.forEach(::go)
                is MezzeType.IntType, is MezzeType.FloatType, is MezzeType.StringType,
                is MezzeType.BoolType, is MezzeType.FracType,
                is MezzeType.BytesType,
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
            is MezzeType.ParametricBuiltin -> type.typeArgs.any { typeParamAppearsIn(typeParamName, it) }
            is MezzeType.NewtypeType -> typeParamAppearsIn(typeParamName, type.wrappedType)
            is MezzeType.TypeApplication -> type.args.any { typeParamAppearsIn(typeParamName, it) }
            is MezzeType.IntType, is MezzeType.FloatType, is MezzeType.StringType,
            is MezzeType.BoolType, is MezzeType.FracType,
            is MezzeType.BytesType,
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
        argSiteType: MezzeType,
        allowPartial: Boolean = false,
    ): List<MezzeType>? {
        val genericFn = callee.scheme.type as? MezzeType.FunctionType ?: return null
        return deriveTypeArgs(
            genericFn.paramType, callee.typeParams, argSiteType,
            fullSchemeType = callee.scheme.type,
            allowPartial = allowPartial,
        )
    }

    /**
     * Fill any typeParam still left as a VarType by [deriveTypeArgsFromArg] using
     * the callee's `having 'out` ability bindings. A class-param ability like
     * `NumAdd 'r for 'l having 'out` has an `out` type var that the ARGUMENT can't
     * pin — it only becomes concrete once the impl is resolved for the (now-derived)
     * receiver + class args. Without this, a constrained fn propagated through a HOF
     * (`g = wrap {f = addTen}` : `{x:'a} with NumAdd Int 'a having 'out='b -> 'b`)
     * has `'b` unresolved at `g{x=5}`, the strict all-concrete gate rejects it, and
     * no `__dict_add` gets seeded → the dict fn lands in a value slot at runtime.
     *
     * For each ability constraint whose args are all concrete after derivation, we
     * resolve the matching impl and read its `associatedTypeBindings` (the impl's
     * concrete `out`). We then substitute the constraint's `out` binding var (which
     * points at the callee's `out` typeParam) with that concrete type in the derived
     * arg list. Returns [derived] unchanged if there is nothing to fill.
     */
    private fun fillHavingOutTypeArgs(
        callee: TypedLetDecl,
        derived: List<MezzeType>,
        abilityImpls: List<TypedAbilityImpl>,
    ): List<MezzeType> {
        // Nothing to do if everything is already concrete.
        if (derived.all { isConcrete(it) }) return derived
        // The MONO scheme's `type` is constraint-less; the ability constraints live
        // on `scheme.abilityConstraints` (abilityHash + typeArgs — the typedast
        // TypedAbilityConstraint has NO associated-type bindings, so we recover the
        // `out` type structurally from the resolved IMPL, not the constraint).
        val schemeConstraints = callee.scheme.abilityConstraints
        if (schemeConstraints.isEmpty()) return derived

        // name -> concrete, from what we've derived so far.
        val nameToConcrete = HashMap<String, MezzeType>(callee.typeParams.size)
        for (i in callee.typeParams.indices) nameToConcrete[callee.typeParams[i].name] = derived[i]

        // The set of typeParams still unresolved (candidates for an `out` fill).
        val unresolvedParamNames = callee.typeParams
            .filter { nameToConcrete[it.name]?.let { t -> t is MezzeType.VarType && !isConcrete(t) } == true }
            .map { it.name }
            .toMutableSet()
        if (unresolvedParamNames.isEmpty()) return derived

        // For each constraint whose class/receiver args are all concrete, resolve the
        // impl and read its associated `out`. Fill the unresolved return typeParam.
        val outFills = HashMap<String, MezzeType>()
        for (c in schemeConstraints) {
            val abilityHash = c.abilityHash
            val concreteArgs = c.typeArgs.map { ta ->
                if (ta is MezzeType.VarType) nameToConcrete[ta.variable.name] ?: ta else ta
            }
            if (concreteArgs.isEmpty() || concreteArgs.any { !isConcrete(it) }) continue
            val candidates = abilityImpls.filter {
                it.abilityHash == abilityHash && it.targetTypes.size == concreteArgs.size
            }
            val match = candidates.firstOrNull { impl ->
                impl.targetTypes.zip(concreteArgs).all { (target, concrete) -> typeMatchesTarget(concrete, target) }
            }
            val m = match ?: continue
            // The impl's associatedTypeBindings hold the concrete `out` for this
            // (receiver, class-args) instantiation. There is typically one `out`.
            val implOut = m.associatedTypeBindings["out"]
                ?: m.associatedTypeBindings["'out"]
                ?: m.associatedTypeBindings.values.firstOrNull()
                ?: continue
            if (!isConcrete(implOut)) continue
            // Assign it to the still-unresolved return typeParam. When exactly one param
            // is unresolved, it IS the `out` (the return); otherwise assign to any
            // unresolved param that does not appear among the constraint's own args (so
            // we never clobber a receiver/class-arg var).
            val argVarNames = c.typeArgs.filterIsInstance<MezzeType.VarType>().map { it.variable.name }.toSet()
            val target = unresolvedParamNames.firstOrNull { it !in argVarNames } ?: continue
            outFills[target] = implOut
            unresolvedParamNames.remove(target)
        }
        if (outFills.isEmpty()) return derived
        return callee.typeParams.indices.map { i ->
            outFills[callee.typeParams[i].name] ?: derived[i]
        }
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
        abilityImpls: List<TypedAbilityImpl>,
        recordShapes: Map<Hash, Map<String, MezzeType>>,
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
            val abilityImplsForHash = abilityImpls.filter {
                it.abilityHash == abilityHash && it.targetTypes.size == concreteArgs.size
            }
            val match = abilityImplsForHash.firstOrNull { impl ->
                impl.targetTypes.zip(concreteArgs).all { (target, concrete) ->
                    typeMatchesTarget(concrete, target)
                }
            }
            // BUG B / #333: an identity-less record receiver (a `{..}` literal that
            // flowed into a generic `x: 'a` param) carries `TypeIdentity.Anonymous`, so
            // `typeMatchesTarget` — which keys on nominal identity for record targets —
            // never matches it against a nominal `impl Ability for T` target, and the
            // impl ref is dropped (the `x.total{}` method call then falls back to a raw
            // field read → runtime "Field not found"). Recover the nominal impl by
            // structurally matching the anonymous receiver against the impls' declared
            // record targets, but ONLY when EXACTLY ONE impl of this ability claims the
            // receiver's shape — never force a choice when the shape is ambiguous.
            val resolvedMatch = match ?: run {
                val structural = abilityImplsForHash.filter { impl ->
                    impl.targetTypes.zip(concreteArgs).all { (target, concrete) ->
                        anonymousRecordMatchesDeclaredRecord(concrete, target, recordShapes)
                    }
                }
                structural.singleOrNull()
            } ?: continue
            // The "primary" type for AbilityImplRef.targetType is the
            // first type arg — same convention used elsewhere.
            out.add(AbilityImplRef(
                abilityHash = abilityHash,
                implHash = resolvedMatch.hash,
                targetType = concreteArgs.firstOrNull() ?: MezzeType.IntType
            ))
        }
        return out
    }

    /**
     * BUG B / #333: structural match of an IDENTITY-LESS record receiver against a
     * NOMINAL record impl target.
     *
     * [concrete] qualifies only when it is a [MezzeType.RecordType] carrying
     * `TypeIdentity.Anonymous` — a `{..}` literal that lost its nominal label flowing
     * into a generic `x: 'a` param. [target] is the impl's `for T` shape; when T is a
     * declared record it appears here as a `TypeApplication`/`RecordType` carrying a
     * `Declared` identity, so we look T's declared field shape up in [recordShapes]
     * (keyed by the Declared content hash) and accept when the receiver's fields are a
     * SUPERSET of T's declared fields with matching field types — the same "written
     * shape satisfies the declared shape" rule ability-impl matching uses everywhere.
     *
     * This deliberately re-introduces a STRUCTURAL match (mono normally dispatches
     * records by nominal identity). It is confined to the identity-less-receiver case
     * and, at both call sites, is gated on EXACTLY ONE impl of the ability claiming the
     * receiver — so it can never silently pick between two same-shaped nominal impls.
     */
    private fun anonymousRecordMatchesDeclaredRecord(
        concrete: MezzeType,
        target: MezzeType,
        recordShapes: Map<Hash, Map<String, MezzeType>>,
    ): Boolean {
        if (concrete !is MezzeType.RecordType) return false
        if (concrete.identity != com.mezze.language.ast.TypeIdentity.Anonymous) return false
        // The target must denote a DECLARED record type (a nominal `for T`). Its declared
        // field shape is looked up by the target's Declared content hash.
        val targetIdentity = when (target) {
            is MezzeType.TypeApplication -> target.identity
            is MezzeType.RecordType -> target.identity
            is MezzeType.VariantType -> target.identity
            else -> return false
        }
        val declaredHash = (targetIdentity as? com.mezze.language.ast.TypeIdentity.Declared)?.hash
            ?: return false
        val declaredFields = recordShapes[declaredHash] ?: return false
        // Superset match: every declared field is present in the receiver with a matching
        // field type. Extra receiver fields are permitted — they cannot make a declared
        // shape stop being satisfied.
        return declaredFields.all { (name, declaredFieldType) ->
            concrete.fields[name] == declaredFieldType
        }
    }

}
