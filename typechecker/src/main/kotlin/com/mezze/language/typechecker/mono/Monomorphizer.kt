package com.mezze.language.typechecker.mono

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar
import com.mezze.language.ast.collectTypeVarsInOrder
import com.mezze.language.ast.hashing.HashTags
import com.mezze.language.ast.hashing.MezzeHashBuilder
import com.mezze.language.typedast.AbilityImplRef
import com.mezze.language.typedast.BindingRef
import com.mezze.language.typedast.Hash
import com.mezze.language.typedast.TypeInstantiation
import com.mezze.language.typedast.TypeParam
import com.mezze.language.typedast.TypedAbilityImpl
import com.mezze.language.typedast.TypedCall
import com.mezze.language.typedast.TypedExpr
import com.mezze.language.typedast.TypedFieldAccess
import com.mezze.language.typedast.TypedLetDecl
import com.mezze.language.typedast.TypedVar
import com.mezze.language.typedast.forEachChild
import java.util.ArrayDeque

/**
 * Compile-time monomorphization driver — produces specialized copies of
 * generic functions, one per concrete type tuple a call site uses.
 *
 * **Phase 1 (hybrid)**: only resolved call sites get specialized. Sites that
 * can't be specialized — first-class polymorphic values, polymorphic
 * recursion past the depth limit — fall back to the existing
 * dictionary-passing path. The two flavors coexist; tests run under both.
 *
 * **Identity**: a specialization is content-addressed by a hash derived from
 * `(genericHash, typeArgs, abilityImpls)`. No name strings. Two projects
 * that specialize the same generic for the same types produce the same
 * specialized hash → blob can be shared via deps.sqlite the same way every
 * other content-hashed artifact is.
 *
 * **Termination**: the worklist has a depth limit (default 16). When a
 * specialization recursively requests its own (gh, args) tuple at increasing
 * depth — polymorphic recursion in a non-bottoming form — we drop the
 * call site to the dict-passing fallback set instead of looping forever.
 *
 * The monomorphizer does NOT touch the store, the typechecker, or
 * `TypedASTCompiler` — it's a pure transformation. Phase 1.3 (#256) wires
 * the output into `mono_specializations` + blobs. Phase 1.4 (#257) teaches
 * `TypedASTCompiler` to emit `CallDirectIR(specializedHash)` at sites the
 * monomorphizer resolved.
 */
class Monomorphizer(
    /**
     * Resolves a generic decl by its content hash. Returns null if the decl
     * isn't reachable (e.g., a `BindingRef.ByHash` to a native function or a
     * stdlib decl we don't have). Native/non-resolvable references fall back
     * to dict-passing automatically.
     */
    private val resolveDecl: (Hash) -> TypedLetDecl?,
    /** Index of every visible ability impl, keyed by `impl.hash`. Used by the
     *  constraint-aware rewriter (Piece 2 of #267) to look up the operation
     *  set of each `AbilityImplRef` in a `SpecRequest`. Default empty map keeps
     *  unit tests that don't care about constraint rewriting unchanged. */
    private val implIndex: Map<Hash, TypedAbilityImpl> = emptyMap(),
    /**
     * C.1 — resolves a CALLEE's own ability constraints against its concrete
     * type arguments, returning the impls that discharge them.
     *
     * A transitive specialization used to inherit whatever evidence its CALLER
     * carried, which is a different function's evidence and is empty whenever
     * the caller had no constraints of its own. `seq::min_by` is the case that
     * exposed it: specialized with fully concrete `typeArgs = [P, K]` and
     * `abilityImpls = []`, so the comparator inside it could neither read
     * evidence (there was none) nor guess from the type arguments (two of them,
     * and `Ord 'b` binds only the second) and fell to the primitive `<`.
     *
     * Supplied by [MonoDriver], which owns the impl set — the knowledge stays
     * where it already lived. Null in unit tests that do not exercise
     * constraints.
     */
    private val resolveEvidence: ((TypedLetDecl, List<MezzeType>) -> List<AbilityImplRef>)? = null,
    /** Worklist depth limit. Default matches the spec's recommendation. */
    private val maxDepth: Int = 16,
) {

    /** A request to specialize `genericHash` for the given type/impl args.
     *
     *  `closureArgs` (#424 Piece 2): fieldName → closureHash for each
     *  record field of the call site's arg that is a lambda literal.
     *  Two calls with the same lambda hash for the same fieldName
     *  dedupe to one specialization; different lambdas produce
     *  different specializations (Rust-style per-closure mono).
     *  Empty when the caller's arg is not a record literal, or none
     *  of its fields are lambda literals. */
    data class SpecRequest(
        val genericHash: Hash,
        val typeArgs: List<MezzeType>,
        val abilityImpls: List<AbilityImplRef>,
        val closureArgs: Map<String, Hash> = emptyMap(),
        /** Companion to [closureArgs]: fieldName → the actual lambda IR
         *  Piece 3 splices into the specialized body. Not part of
         *  identity — the hashes in [closureArgs] carry that. */
        val closureBodies: Map<String, com.mezze.language.typedast.TypedLambda> = emptyMap(),
    )

    /** Identity key for the `done` set — drops abilityImpls because they are
     *  derivable from typeArgs once the impl table is consulted. The full
     *  triple still goes into the specialization hash.
     *
     *  #424: closureArgs participate — two call sites with different
     *  closures for the same generic must produce distinct
     *  specializations (that's the point). Identical closures dedupe. */
    private data class DoneKey(
        val genericHash: Hash,
        val typeArgs: List<MezzeType>,
        val closureArgs: Map<String, Hash>,
    )

    /** Output of one mono run. */
    data class Result(
        /** New specialized decls keyed by their specialized hash. */
        val specializations: Map<Hash, Specialization>,
        /** Per resolved call site: `originalHash → specializedHash`. The
         *  `TypedASTCompiler` rewrites `BindingRef.ByHash(originalHash)` to
         *  `BindingRef.ByHash(specializedHash)` at these sites. */
        val callSiteRewrites: Map<SpecRequest, Hash>,
        /** Requests that exceeded `maxDepth` or hit a non-resolvable decl —
         *  these stay on the dict-passing path. */
        val fallbacks: List<SpecRequest>,
        /** #424 Piece 5 — for each specialization that had closure params
         *  spliced away, the set of param names that were dropped from
         *  the specialized signature. MonoDriver / the compiler use this
         *  to strip the corresponding fields from arg records at SEED
         *  call sites (user code). */
        val stripMap: Map<Hash, Set<String>> = emptyMap(),
    )

    /** A produced specialization — content + provenance. */
    data class Specialization(
        val specializedHash: Hash,
        val genericHash: Hash,
        val typeArgs: List<MezzeType>,
        val abilityImpls: List<AbilityImplRef>,
        /** The original `TypedLetDecl.value` with type substitution applied,
         *  ability constraints removed (now concrete), forall vars dropped. */
        val specializedBody: TypedExpr,
        /** The original decl's resolved return type — now concrete. */
        val specializedType: MezzeType,
        /** #424: fieldName → closureHash for closure-arg specializations. */
        val closureArgs: Map<String, Hash> = emptyMap(),
        /** #424: fieldName → the lambda IR that was spliced into the body,
         *  kept for provenance/debug. Empty when Piece 3 is gated off. */
        val closureBodies: Map<String, com.mezze.language.typedast.TypedLambda> = emptyMap(),
    )

    /** Drive the worklist from a list of seed requests. */
    fun run(seeds: List<SpecRequest>): Result {
        val done = HashMap<DoneKey, Hash>()
        val specializations = HashMap<Hash, Specialization>()
        val rewrites = HashMap<SpecRequest, Hash>()
        val fallbacks = ArrayList<SpecRequest>()

        // Worklist holds `(request, depth)` so we can bound polymorphic
        // recursion. Depth is the chain length from a seed, NOT the number
        // of pending entries.
        val worklist: ArrayDeque<Pair<SpecRequest, Int>> = ArrayDeque()
        for (seed in seeds) worklist.addLast(seed to 0)

        while (worklist.isNotEmpty()) {
            val (req, depth) = worklist.removeFirst()
            val key = DoneKey(req.genericHash, req.typeArgs, req.closureArgs)

            // Cycle / cache hit — already specialized.
            done[key]?.let { existing ->
                rewrites[req] = existing
                continue
            }

            if (depth > maxDepth) {
                fallbacks.add(req)
                continue
            }

            val decl = resolveDecl(req.genericHash)
            if (decl == null || decl.typeParams.isEmpty()) {
                // Non-resolvable (native, stdlib without source) or already
                // monomorphic — no specialization needed. Compiler should
                // emit a direct call to the original hash; we record no
                // rewrite for this site.
                continue
            }

            // Build the substitution: typeParams[i].name → typeArgs[i]. We
            // walk the decl's body to find every `TypeVar` whose name
            // matches a typeParam, then point all occurrences at the same
            // concrete type. This handles the case where ConstraintSolver
            // produced multiple `TypeVar` instances with the same name but
            // different ids (idiomatic — each binding-site gets a fresh id).
            if (decl.typeParams.size != req.typeArgs.size) {
                // Arity mismatch — almost certainly an upstream bug, but
                // don't crash the build. Drop to dict.
                fallbacks.add(req)
                continue
            }
            val substitution = buildSubstitution(decl, req.typeArgs)

            val substitutedBody = TypeSubstitution.applyExpr(decl.value, substitution)
            val newType = TypeSubstitution.applyType(decl.scheme.type, substitution)

            // Piece 2 of #267: rewrite constraint-driven method calls inside
            // the substituted body to direct ByHash references against the
            // resolved op-hash, so the compiler emits CallDirectIR instead of
            // routing through the dict-passing path.
            val newBody = rewriteConstrainedMethodCalls(substitutedBody, req.abilityImpls)

            val specHash = specializedHash(req.genericHash, req.typeArgs, req.abilityImpls, req.closureArgs)
            done[key] = specHash
            rewrites[req] = specHash
            // #424 Piece 3: splice closure lambdas into the specialized
            // body, gated on MEZZE_MONO_CLOSURES=1 for safety. The splice
            // rewrites `f {acc, x}` → the folder body (beta-reduction)
            // and `f = f` → `f = <literal folder>` in forwarded records
            // (so transitive fold_go picks up the same closure). Only
            // runs when the request has closureArgs AND the flag is on.
            val specializedNewBody = if (req.closureArgs.isNotEmpty()
                && req.closureBodies.isNotEmpty()
                && closuresEnabled
            ) {
                val spliced = LambdaSubstitution.substituteClosures(newBody, req.closureBodies)
                // The spliced-in closure body may itself call a constraint-driven
                // ability method on a now-concrete receiver (a fold folder
                // `{ acc, x } -> acc + x.total {}` where `x : Item`). Its call was
                // emitted while the receiver was still polymorphic, so it carries NO
                // impl ref and stays routed through the field-access/dict path — at
                // runtime that throws `Field not found: <op>`. Now that the closure
                // param is concrete, resolve each such call against the impl whose
                // target type matches the receiver and rewrite it to a direct
                // op-hash ByHash call. (`req.abilityImpls` covers the generic's OWN
                // body, rewritten above; this covers the grafted closure.)
                rewriteClosureMethodCallsByReceiver(spliced)
            } else newBody

            specializations[specHash] = Specialization(
                specializedHash = specHash,
                genericHash = req.genericHash,
                typeArgs = req.typeArgs,
                abilityImpls = req.abilityImpls,
                specializedBody = specializedNewBody,
                specializedType = newType,
                closureArgs = req.closureArgs,
                closureBodies = req.closureBodies,
            )
            if (System.getenv("MEZZE_MONO_TRACE") != null) {
                System.err.println("[mono-trace] emit spec name=${decl.name} specHash=${specHash.hex.take(8)} " +
                    "typeArgs=${req.typeArgs.map { MonoDriver.detailedPublic(it) }} " +
                    "closureArgs=${req.closureArgs.keys}")
            }

            // Scan the substituted body for further generic call sites and
            // enqueue them. `collectByHashRefsWithSite` yields
            // `(ByHash, TypeInstantiation?, siteType?, enclosingCall?)`
            // — the enclosingCall is the TypedCall we're inside (null for
            // non-call refs). Used by #424 to harvest closure-arg
            // literals at each transitive HOF call site.
            data class RefSite(
                val ref: BindingRef.ByHash,
                val inst: TypeInstantiation?,
                val siteType: MezzeType?,
                val enclosingCall: TypedCall?,
            )
            val refs = mutableListOf<RefSite>()
            TypeSubstitution.collectByHashRefsWithSite(specializedNewBody) { ref, inst, siteType, call ->
                refs.add(RefSite(ref, inst, siteType, call))
            }
            if (System.getenv("MEZZE_MONO_TRACE") != null) {
                System.err.println("[mono-trace] transitive refs from ${decl.name} (spec=${specHash.hex.take(8)}, closureArgs=${req.closureArgs.keys}): total=${refs.size}")
                for (r in refs) {
                    val target = resolveDecl(r.ref.hash)
                    val siteDetail = when (val s = r.siteType) {
                        is MezzeType.FunctionType -> "FunctionType(param=${MonoDriver.detailedPublic(s.paramType)}, ret=${MonoDriver.detailedPublic(s.returnType)})"
                        null -> "null"
                        else -> MonoDriver.detailedPublic(s)
                    }
                    System.err.println("[mono-trace]   ref=${r.ref.hash.hex.take(8)} target=${target?.name ?: "?"} " +
                        "typeParams=${target?.typeParams?.map { it.name } ?: emptyList<String>()} " +
                        "inst=${r.inst?.typeArgs?.map { MonoDriver.detailedPublic(it) }} siteType=$siteDetail")
                }
            }
            // #409: for each transitive ref, derive typeArgs by unifying
            // the callee's declared generic type against the substituted
            // call-site's fn type. This is more reliable than trusting
            // `TypedCall.instantiation`, which for ability/impl-wrapper
            // methods stamps the wrong shape (impl target-type instead of
            // each declared typeParam).
            for (r in refs) {
                val targetDecl = resolveDecl(r.ref.hash) ?: continue
                if (targetDecl.typeParams.isEmpty()) continue
                val derived = deriveTransitiveTypeArgs(targetDecl, r.inst, r.siteType, r.enclosingCall)
                    ?: continue
                // #424 Piece 2: harvest closure args at each transitive
                // HOF call site. After Piece 3 splices literals into the
                // body, forwarded `f = f` becomes `f = <lambda>`, so a
                // recursive helper like `fold_go` picks the closure up
                // via this scan and specializes the same way.
                val cArgs: Map<String, Hash>
                val cBodies: Map<String, com.mezze.language.typedast.TypedLambda>
                if (closuresEnabled && r.enclosingCall != null) {
                    cArgs = closureArgsAt(r.enclosingCall)
                    cBodies = closureBodiesAt(r.enclosingCall)
                } else {
                    cArgs = emptyMap()
                    cBodies = emptyMap()
                }
                // C.1 — evidence for the CALLEE, not inherited from the caller.
                // Strictly additive: when the site already carries evidence we
                // keep it untouched, because `abilityImpls` feeds
                // `specializedHash` and re-deciding it for sites that already
                // had an answer would renumber specializations wholesale. Only
                // the empty case — the one that left `min_by` with nothing to
                // consult — is filled in.
                val inherited = r.inst?.abilityImpls ?: emptyList()
                val evidence = if (inherited.isNotEmpty()) inherited
                    else resolveEvidence?.invoke(targetDecl, derived) ?: emptyList()
                worklist.addLast(
                    SpecRequest(
                        r.ref.hash,
                        derived,
                        evidence,
                        cArgs,
                        cBodies,
                    ) to (depth + 1)
                )
            }
        }

        // Post-pass — rewrite ByHash refs inside each specialization
        // body when the callee ALSO got specialized. Compiler only
        // routes seed call sites via bySiteLoc; transitive calls need
        // this rewrite so a specialized `fold` calls the specialized
        // `fold_go`, not the generic one.
        //
        // Only enabled with the #424 flag on. Without it, existing
        // pre-#424 mono behaviour is preserved (broken for HOFs but
        // green under gate=off).
        if (closuresEnabled) {
            val specHashMap = HashMap<DoneKey, Hash>(done)
            // #424 Piece 5: DEAD-CLOSURE-PARAM ELIMINATION.
            //
            // After Piece 3 splices lambda bodies into specialized decls,
            // the closure param (`f`) is dead — every `f{...}` call was
            // beta-reduced to the folder body, and every bare `f` read
            // was replaced with the lambda literal. But the specialized
            // decl's outer `TypedLambda` still declares `f` as a param,
            // so callers pass a fresh `MakeLambdaIR(<folder>)` value on
            // every call. For a tail-recursive helper like `fold_go`,
            // that's one allocation per iteration — the exact cost mono
            // was supposed to eliminate.
            //
            // Fix: strip closure-arg keys from each spec's outer lambda
            // params (and from the specialized paramType). At every call
            // to a stripped spec, also strip those keys from the arg
            // record. Bookkeeping map `stripMap: specHash → closure keys`
            // drives both the rewrite of transitive callsites here and
            // the rewrite of SEED sites (user call sites) via
            // `stripMapForSites` returned in Result — MonoDriver consumes
            // it to also strip fields at seed callers.
            // #424 Piece 5: strip dead closure params. Only params that
            // Piece 3 (LambdaSubstitution) actually spliced are dead —
            // params forwarded to natives (e.g. `p` in seq::filter →
            // __native_seq_filter) are still live and must NOT be stripped.
            // Gated behind MEZZE_MONO_STRIP=1 until we're confident the
            // liveness scan catches all edge cases.
            val stripEnabled = System.getenv("MEZZE_MONO_STRIP") == "1"
            val stripMap = HashMap<Hash, Set<String>>()
            if (stripEnabled) {
                for ((sh, spec) in specializations) {
                    if (spec.closureArgs.isNotEmpty()) {
                        val stillLive = referencedClosureParams(
                            spec.specializedBody, spec.closureArgs.keys,
                        )
                        val dead = spec.closureArgs.keys - stillLive
                        if (dead.isNotEmpty()) stripMap[sh] = dead
                    }
                }
            }
            val rewritten = HashMap<Hash, Specialization>(specializations.size)
            for ((sh, spec) in specializations) {
                var newBody = rewriteBodyToSpecializedCalls(
                    spec.specializedBody, specHashMap,
                )
                // Strip THIS spec's closure params from its outer lambda.
                val myStrip = stripMap[sh]
                var newType = spec.specializedType
                if (myStrip != null) {
                    newBody = stripLambdaParams(newBody, myStrip)
                    newType = stripFunctionParamFields(newType, myStrip)
                }
                // Strip closure fields at every call to a stripped spec.
                newBody = stripCallArgFields(newBody, stripMap)
                if (System.getenv("MEZZE_MONO_DUMP") != null) {
                    val name = resolveDecl(spec.genericHash)?.name ?: "?"
                    System.err.println("[mono-dump] ${name}#${sh.hex.take(8)} stripped=${myStrip ?: "<none>"}")
                }
                rewritten[sh] = spec.copy(
                    specializedBody = newBody,
                    specializedType = newType,
                )
            }
            return Result(rewritten, rewrites, fallbacks, stripMap)
        }

        return Result(specializations, rewrites, fallbacks, emptyMap())
    }

    /** If [body] is a `TypedLambda` whose params list contains any of
     *  [stripKeys], drop those params from the outer lambda. All refs to
     *  them in the body are already gone (LambdaSubstitution replaced
     *  them with the folder literal + beta-reduced any calls). */
    private fun stripLambdaParams(
        body: TypedExpr,
        stripKeys: Set<String>,
    ): TypedExpr {
        if (body !is com.mezze.language.typedast.TypedLambda) return body
        val kept = body.params.filter { it.name !in stripKeys }
        if (kept.size == body.params.size) return body
        // Rebuild the outer function type with the stripped param list.
        val newType = stripFunctionParamFields(body.type, stripKeys)
        return body.copy(params = kept, type = newType)
    }

    /** If [type] is a `FunctionType` whose paramType is a RecordType with
     *  any of [stripKeys] as fields, return a new FunctionType with those
     *  fields removed. Non-FunctionType or non-RecordType shapes pass
     *  through unchanged. */
    private fun stripFunctionParamFields(
        type: MezzeType,
        stripKeys: Set<String>,
    ): MezzeType {
        if (type !is MezzeType.FunctionType) return type
        val paramRec = type.paramType as? MezzeType.RecordType ?: return type
        if (stripKeys.none { it in paramRec.fields }) return type
        val keptFields = LinkedHashMap<String, MezzeType>()
        for ((k, v) in paramRec.fields) if (k !in stripKeys) keptFields[k] = v
        return type.copy(paramType = paramRec.copy(fields = keptFields))
    }

    /** Walk [expr] and, for every call whose target hash is in
     *  [stripMap], remove the corresponding field entries from the arg
     *  record. Recurses into sub-expressions. */
    private fun stripCallArgFields(
        expr: TypedExpr,
        stripMap: Map<Hash, Set<String>>,
    ): TypedExpr {
        fun go(e: TypedExpr): TypedExpr = when (e) {
            is TypedCall -> {
                val fn = e.function
                val fnRewritten = go(fn)
                val argRewritten = go(e.arg)
                if (fnRewritten is TypedVar && fnRewritten.binding is BindingRef.ByHash) {
                    val calleeHash = (fnRewritten.binding as BindingRef.ByHash).hash
                    val keys = stripMap[calleeHash]
                    if (keys != null && argRewritten is com.mezze.language.typedast.TypedRecord) {
                        val kept = argRewritten.entries.filter { entry ->
                            entry !is com.mezze.language.typedast.TypedRecordEntry.Field || entry.name !in keys
                        }
                        val keptFieldTypes = LinkedHashMap<String, MezzeType>()
                        for ((k, v) in (argRewritten.type as? MezzeType.RecordType)?.fields.orEmpty()) {
                            if (k !in keys) keptFieldTypes[k] = v
                        }
                        val newArg = argRewritten.copy(
                            entries = kept,
                            type = (argRewritten.type as? MezzeType.RecordType)?.copy(fields = keptFieldTypes) ?: argRewritten.type,
                        )
                        // Also strip the fnRewritten's stamp type so the
                        // compiler's slot layout for the arg record matches.
                        val newFn = fnRewritten.copy(type = stripFunctionParamFields(fnRewritten.type, keys))
                        e.copy(function = newFn, arg = newArg)
                    } else {
                        e.copy(function = fnRewritten, arg = argRewritten)
                    }
                } else {
                    e.copy(function = fnRewritten, arg = argRewritten)
                }
            }
            is com.mezze.language.typedast.TypedLambda -> e.copy(body = go(e.body))
            is com.mezze.language.typedast.TypedRecord -> e.copy(entries = e.entries.map { stripEntry(it, ::go) })
            is com.mezze.language.typedast.TypedRecordUpdate -> e.copy(
                record = go(e.record),
                updates = e.updates.map { stripEntry(it, ::go) },
            )
            is com.mezze.language.typedast.TypedFieldAccess -> e.copy(record = go(e.record))
            is com.mezze.language.typedast.TypedVariantConstruct -> {
                val p = e.payload
                if (p == null) e else e.copy(payload = go(p))
            }
            is com.mezze.language.typedast.TypedMatch -> e.copy(
                scrutinee = go(e.scrutinee),
                cases = e.cases.map { c ->
                    c.copy(
                        guard = c.guard?.let { go(it) },
                        body = go(c.body),
                    )
                },
            )
            is com.mezze.language.typedast.TypedIf -> e.copy(
                condition = go(e.condition),
                thenBranch = go(e.thenBranch),
                elseBranch = go(e.elseBranch),
            )
            is com.mezze.language.typedast.TypedBlock -> e.copy(
                statements = e.statements.map { s ->
                    when (s) {
                        is com.mezze.language.typedast.TypedStatement.Let -> s.copy(value = go(s.value))
                        is com.mezze.language.typedast.TypedStatement.Expr -> s.copy(expr = go(s.expr))
                    }
                },
                result = go(e.result),
            )
            is com.mezze.language.typedast.TypedSeq -> e.copy(
                elements = e.elements.map { el ->
                    when (el) {
                        is com.mezze.language.typedast.TypedSeqElement.Regular -> el.copy(expr = go(el.expr))
                        is com.mezze.language.typedast.TypedSeqElement.Spread -> el.copy(expr = go(el.expr))
                    }
                },
            )
            is com.mezze.language.typedast.TypedPipe -> e.copy(left = go(e.left), right = go(e.right))
            is com.mezze.language.typedast.TypedPerform -> e.copy(body = go(e.body))
            is com.mezze.language.typedast.TypedPerformOp -> e.copy(arg = go(e.arg))
            is com.mezze.language.typedast.TypedStringLit -> e.copy(parts = e.parts.map { part ->
                when (part) {
                    is com.mezze.language.typedast.TypedStringPart.Literal -> part
                    is com.mezze.language.typedast.TypedStringPart.Interpolation ->
                        com.mezze.language.typedast.TypedStringPart.Interpolation(go(part.expr))
                }
            })
            else -> e
        }
        return go(expr)
    }

    private fun stripEntry(
        entry: com.mezze.language.typedast.TypedRecordEntry,
        go: (TypedExpr) -> TypedExpr,
    ): com.mezze.language.typedast.TypedRecordEntry = when (entry) {
        is com.mezze.language.typedast.TypedRecordEntry.Field -> entry.copy(value = go(entry.value))
        is com.mezze.language.typedast.TypedRecordEntry.Spread -> entry.copy(expr = go(entry.expr))
    }

    /** Collect closure-param names that are still referenced in [body].
     *  A param is "still live" if any TypedVar with a Param/Local binding
     *  and a name in [candidates] exists anywhere in the body tree.
     *  This lets Piece 5 strip only the params that Piece 3 actually
     *  made dead (beta-reduced away), leaving native-delegated params
     *  (e.g. `p` in seq::filter → __native_seq_filter) untouched. */
    private fun referencedClosureParams(
        body: TypedExpr,
        candidates: Set<String>,
    ): Set<String> {
        val found = HashSet<String>()
        fun walk(e: TypedExpr) {
            if (found.size == candidates.size) return
            if (e is TypedVar
                && e.name in candidates
                && (e.binding is BindingRef.Param || e.binding is BindingRef.Local)
            ) {
                found.add(e.name)
                return
            }
            e.forEachChild { walk(it) }
        }
        if (body is com.mezze.language.typedast.TypedLambda) {
            walk(body.body)
        } else {
            walk(body)
        }
        return found
    }

    /** Post-pass: rewrite `TypedVar(BindingRef.ByHash(h), …)` inside a
     *  specialization body to point at the specialized-callee hash when
     *  the callee's `(h, derivedTypeArgs, closureArgs)` is in
     *  [specHashMap]. Left alone otherwise — the compiler emits a
     *  direct call to the generic (which is the correct fallback). */
    private fun rewriteBodyToSpecializedCalls(
        expr: TypedExpr,
        specHashMap: Map<DoneKey, Hash>,
    ): TypedExpr {
        fun rewrite(e: TypedExpr): TypedExpr = when (e) {
            is TypedCall -> {
                val fn = e.function
                if (fn is TypedVar && fn.binding is BindingRef.ByHash) {
                    val callee = resolveDecl((fn.binding as BindingRef.ByHash).hash)
                    val newFn = if (callee != null && callee.typeParams.isNotEmpty()) {
                        val derived = deriveTransitiveTypeArgs(callee, e.instantiation, fn.type, e)
                        if (derived != null) {
                            val cArgs = closureArgsAt(e)
                            val key = DoneKey((fn.binding as BindingRef.ByHash).hash, derived, cArgs)
                            val specHash = specHashMap[key]
                            if (specHash != null) {
                                fn.copy(binding = BindingRef.ByHash(specHash))
                            } else fn
                        } else fn
                    } else fn
                    e.copy(function = newFn, arg = rewrite(e.arg))
                } else {
                    e.copy(function = rewrite(fn), arg = rewrite(e.arg))
                }
            }
            is com.mezze.language.typedast.TypedLambda -> e.copy(body = rewrite(e.body))
            is com.mezze.language.typedast.TypedRecord -> e.copy(entries = e.entries.map { rewriteEntry(it, ::rewrite) })
            is com.mezze.language.typedast.TypedRecordUpdate -> e.copy(
                record = rewrite(e.record),
                updates = e.updates.map { rewriteEntry(it, ::rewrite) },
            )
            is com.mezze.language.typedast.TypedFieldAccess -> e.copy(record = rewrite(e.record))
            is com.mezze.language.typedast.TypedVariantConstruct -> {
                val p = e.payload
                if (p == null) e else e.copy(payload = rewrite(p))
            }
            is com.mezze.language.typedast.TypedMatch -> e.copy(
                scrutinee = rewrite(e.scrutinee),
                cases = e.cases.map { c ->
                    c.copy(
                        guard = c.guard?.let { rewrite(it) },
                        body = rewrite(c.body),
                    )
                },
            )
            is com.mezze.language.typedast.TypedIf -> e.copy(
                condition = rewrite(e.condition),
                thenBranch = rewrite(e.thenBranch),
                elseBranch = rewrite(e.elseBranch),
            )
            is com.mezze.language.typedast.TypedBlock -> e.copy(
                statements = e.statements.map { s ->
                    when (s) {
                        is com.mezze.language.typedast.TypedStatement.Let -> s.copy(value = rewrite(s.value))
                        is com.mezze.language.typedast.TypedStatement.Expr -> s.copy(expr = rewrite(s.expr))
                    }
                },
                result = rewrite(e.result),
            )
            is com.mezze.language.typedast.TypedSeq -> e.copy(
                elements = e.elements.map { el ->
                    when (el) {
                        is com.mezze.language.typedast.TypedSeqElement.Regular -> el.copy(expr = rewrite(el.expr))
                        is com.mezze.language.typedast.TypedSeqElement.Spread -> el.copy(expr = rewrite(el.expr))
                    }
                },
            )
            is com.mezze.language.typedast.TypedPipe -> e.copy(left = rewrite(e.left), right = rewrite(e.right))
            is com.mezze.language.typedast.TypedPerform -> e.copy(body = rewrite(e.body))
            is com.mezze.language.typedast.TypedPerformOp -> e.copy(arg = rewrite(e.arg))
            else -> e
        }
        return rewrite(expr)
    }

    private fun rewriteEntry(
        entry: com.mezze.language.typedast.TypedRecordEntry,
        rewrite: (TypedExpr) -> TypedExpr,
    ): com.mezze.language.typedast.TypedRecordEntry = when (entry) {
        is com.mezze.language.typedast.TypedRecordEntry.Field -> entry.copy(value = rewrite(entry.value))
        is com.mezze.language.typedast.TypedRecordEntry.Spread -> entry.copy(expr = rewrite(entry.expr))
    }

    /**
     * Derive concrete type-args for a transitive callee reference.
     *
     * The AST-stamped `TypeInstantiation.typeArgs` is trustworthy for plain
     * generic calls but wrong-arity for ability/inherent-method impls:
     * `impl Seq 'a where { fold = ... }` produces a wrapper `fold` whose
     * typeParams include the underlying fold's `'b` accumulator, but the
     * typechecker stamps only `[Seq Int]` (the whole receiver type). We
     * ignore the stamped instantiation when its arity mismatches and fall
     * back to unifying the callee's declared function type against the
     * substituted call-site fn type — after the caller's own substitution
     * ran, that site type is concrete enough to bind every typeParam.
     *
     * Returns args in callee-typeParam order, or null if we can't
     * confidently derive a full set (upstream falls back to dict-passing).
     */
    private fun deriveTransitiveTypeArgs(
        callee: TypedLetDecl,
        inst: TypeInstantiation?,
        siteType: MezzeType?,
        enclosingCall: com.mezze.language.typedast.TypedCall? = null,
    ): List<MezzeType>? {
        val typeParams = callee.typeParams
        if (typeParams.isEmpty()) return null

        if (inst != null && inst.typeArgs.size == typeParams.size &&
            inst.typeArgs.all { isConcreteEnough(it) }
        ) {
            return inst.typeArgs
        }

        // Primary attempt: unify against the substituted site's fn type.
        // Works when the caller's substitution reached through to the
        // inner call's TypedVar stamp.
        if (siteType != null) {
            val bindings = HashMap<String, MezzeType>()
            if (unify(callee.scheme.type, siteType, bindings)) {
                val args = buildArgsFromBindings(typeParams, bindings)
                if (args != null) return args
            }
        }

        // Fallback: unify the callee's declared paramType against the
        // ENCLOSING call's arg record type. This is the shape mono needs
        // for cross-decl chained HOFs — impl-op bodies stamp the inner
        // call's TypedVar with the STDLIB's original typeParam names
        // (e.g. `'c136`, `'d136` for `seq::fold`), not with the impl-op's
        // own vars (`'t145`, `'u145`). The caller's substitution can't
        // rewrite those because the names don't match, so `siteType`
        // stays generic. But the enclosing call's arg record — actual
        // args like `{xs = it, init = init, f = f}` — DID get substituted,
        // so it carries concrete types. Unifying against `callee.scheme
        // .type.paramType` recovers `'c136 → Int`, `'d136 → Int`.
        if (enclosingCall != null) {
            val fnType = callee.scheme.type as? MezzeType.FunctionType
            if (fnType != null) {
                val bindings = HashMap<String, MezzeType>()
                if (unify(fnType.paramType, enclosingCall.arg.type, bindings)) {
                    val args = buildArgsFromBindings(typeParams, bindings)
                    if (args != null) return args
                }
            }
        }

        return null
    }

    private fun buildArgsFromBindings(
        typeParams: List<TypeParam>,
        bindings: Map<String, MezzeType>,
    ): List<MezzeType>? {
        val args = ArrayList<MezzeType>(typeParams.size)
        for (tp in typeParams) {
            val b = bindings[tp.name]
            if (b != null && isConcreteEnough(b)) {
                args.add(b)
            } else if (isEffectVarNameLocal(tp.name)) {
                args.add(MezzeType.EffectRow.simple(emptySet()))
            } else {
                return null
            }
        }
        return args
    }

    /** True when a type has no free VarType — safe to feed into buildSubstitution. */
    private fun isConcreteEnough(t: MezzeType): Boolean {
        var ok = true
        for (tv in collectTypeVarsInOrder(t, sortFields = false)) { ok = false; break }
        return ok
    }

    private fun isEffectVarNameLocal(name: String): Boolean {
        val stripped = name.removePrefix("'")
        return stripped.length >= 2 && stripped.startsWith("e") && stripped[1].isDigit()
    }

    /**
     * Build a `TypeVar → MezzeType` map covering every type variable bound
     * by the decl's typeParams.
     *
     * The decl's typeParams are name-only (`List<TypeParam>`). The TypeVars
     * actually referenced in the body (and in `decl.scheme.type`) carry the
     * ids the typechecker assigned. We collect every TypeVar whose `name`
     * matches a typeParam and point them all at the corresponding typeArg.
     *
     * Free TypeVars whose names don't appear in `typeParams` are left alone
     * — they belong to an outer scope (currently impossible at top-level
     * decls, but explicit is better than implicit).
     */
    private fun buildSubstitution(decl: TypedLetDecl, typeArgs: List<MezzeType>): Map<TypeVar, MezzeType> {
        val nameToArg: Map<String, MezzeType> =
            decl.typeParams.zip(typeArgs).associate { (param, arg) -> param.name to arg }

        // Collect every TypeVar reachable from the decl's surface type plus
        // the body's own type fields. We only need the surface type — the
        // body's TypeVars are a subset (every reference originates at a
        // binding declared by the type, ConstraintGenerator-side).
        // But to be safe in the presence of ability-constraint-only vars,
        // also walk the body via `collectBodyTypeVars`.
        val seen = HashMap<TypeVar, MezzeType>()
        for (tv in collectTypeVarsInOrder(decl.scheme.type, sortFields = false)) {
            val arg = nameToArg[tv.name] ?: continue
            seen[tv] = arg
        }
        collectBodyTypeVars(decl.value) { tv ->
            val arg = nameToArg[tv.name] ?: return@collectBodyTypeVars
            seen[tv] = arg
        }
        return seen
    }

    /** Walk every `TypedExpr.type` in the body and feed each `TypeVar` it
     *  contains to the visitor. Read-only; no allocation per node beyond
     *  what `collectTypeVarsInOrder` produces. */
    private fun collectBodyTypeVars(expr: TypedExpr, visit: (TypeVar) -> Unit) {
        // Reuse collectTypeVarsInOrder on each node's `type:` field. The
        // walker mirrors `TypeSubstitution.applyExpr` shape but only
        // traverses children — it does not allocate a new tree.
        for (tv in collectTypeVarsInOrder(expr.type, sortFields = false)) visit(tv)
        // Recurse via the same exhaustive case shape as TypeSubstitution.
        when (expr) {
            is com.mezze.language.typedast.TypedLambda -> {
                for (p in expr.params) for (tv in collectTypeVarsInOrder(p.type, sortFields = false)) visit(tv)
                collectBodyTypeVars(expr.body, visit)
            }
            is com.mezze.language.typedast.TypedCall -> {
                collectBodyTypeVars(expr.function, visit); collectBodyTypeVars(expr.arg, visit)
                expr.instantiation?.typeArgs?.forEach { for (tv in collectTypeVarsInOrder(it, sortFields = false)) visit(tv) }
            }
            is com.mezze.language.typedast.TypedRecord -> for (e in expr.entries) when (e) {
                is com.mezze.language.typedast.TypedRecordEntry.Field -> collectBodyTypeVars(e.value, visit)
                is com.mezze.language.typedast.TypedRecordEntry.Spread -> collectBodyTypeVars(e.expr, visit)
            }
            is com.mezze.language.typedast.TypedRecordUpdate -> {
                collectBodyTypeVars(expr.record, visit)
                for (e in expr.updates) when (e) {
                    is com.mezze.language.typedast.TypedRecordEntry.Field -> collectBodyTypeVars(e.value, visit)
                    is com.mezze.language.typedast.TypedRecordEntry.Spread -> collectBodyTypeVars(e.expr, visit)
                }
            }
            is com.mezze.language.typedast.TypedFieldAccess -> collectBodyTypeVars(expr.record, visit)
            is com.mezze.language.typedast.TypedVariantConstruct -> expr.payload?.let { collectBodyTypeVars(it, visit) }
            is com.mezze.language.typedast.TypedMatch -> {
                collectBodyTypeVars(expr.scrutinee, visit)
                for (c in expr.cases) {
                    c.guard?.let { collectBodyTypeVars(it, visit) }
                    collectBodyTypeVars(c.body, visit)
                }
            }
            is com.mezze.language.typedast.TypedIf -> {
                collectBodyTypeVars(expr.condition, visit)
                collectBodyTypeVars(expr.thenBranch, visit)
                collectBodyTypeVars(expr.elseBranch, visit)
            }
            is com.mezze.language.typedast.TypedBlock -> {
                for (s in expr.statements) when (s) {
                    is com.mezze.language.typedast.TypedStatement.Let -> collectBodyTypeVars(s.value, visit)
                    is com.mezze.language.typedast.TypedStatement.Expr -> collectBodyTypeVars(s.expr, visit)
                }
                collectBodyTypeVars(expr.result, visit)
            }
            is com.mezze.language.typedast.TypedSeq -> for (el in expr.elements) when (el) {
                is com.mezze.language.typedast.TypedSeqElement.Regular -> collectBodyTypeVars(el.expr, visit)
                is com.mezze.language.typedast.TypedSeqElement.Spread -> collectBodyTypeVars(el.expr, visit)
            }
            is com.mezze.language.typedast.TypedPipe -> {
                collectBodyTypeVars(expr.left, visit); collectBodyTypeVars(expr.right, visit)
            }
            is com.mezze.language.typedast.TypedPerform -> collectBodyTypeVars(expr.body, visit)
            is com.mezze.language.typedast.TypedPerformOp -> collectBodyTypeVars(expr.arg, visit)
            // #225 companion: string-interpolation subexprs may carry TypeVars that
            // share a surface param name — traverse them so buildSubstitution maps
            // them. (Doesn't fix the #225 program itself — that var is name-mismatched —
            // but closes the identical latent gap; the rewriteCalls case is the real fix.)
            is com.mezze.language.typedast.TypedStringLit -> for (part in expr.parts) {
                if (part is com.mezze.language.typedast.TypedStringPart.Interpolation) {
                    collectBodyTypeVars(part.expr, visit)
                }
            }
            // Leaves — already visited via `expr.type` above.
            else -> Unit
        }
    }

    /**
     * Rewrite constraint-driven method calls in the substituted body to
     * direct ByHash calls against the resolved op-hash.
     *
     * For each `AbilityImplRef` in the spec request, we look the impl up in
     * `implIndex` and enumerate its operations. Every `op_name` in those
     * operations becomes an entry in `opsByName`: the next time we
     * encounter a `TypedCall(TypedFieldAccess(_, op_name), arg, …)` in the
     * body, we rewrite it as if it were a top-level call to that op.
     *
     * Multiple impls may share an op name (e.g. `Shape for Square` +
     * `Shape for Rect` both provide `area`). At each call site, [selectOpHash]
     * disambiguates by matching the receiver's substituted type against the
     * impl's receiver-position target. Without this per-site match, the
     * last-writer-wins map would collapse both `a.area{}` and `b.area{}` in
     * `let both = { a, b } -> a.area{} + b.area{}` onto the same op.
     *
     * The arg is left unchanged — the typechecker already desugared
     * `receiver.method {…}` into `method({it = receiver, …})`, so the arg
     * record already carries the receiver under field `it`.
     *
     * If `opsByName` is empty (no constraints, or impls absent from the
     * index), the body is returned unchanged. Recursion mirrors the shape
     * of `TypeSubstitution.applyExpr`.
     */
    /**
     * A spliced-in closure body (e.g. a fold folder `{ acc, x } -> acc + x.total {}`)
     * carries ability-method calls that were emitted while the receiver was still
     * polymorphic, so they hold no impl ref and default to a field-access/dict call
     * that throws `Field not found: <op>` at runtime. Now that the closure param is
     * concrete, resolve each such call by matching the receiver's type against the
     * ability impls in `implIndex` and rewrite it to a direct op-hash ByHash call.
     * Runs the SAME per-method rewrite as [rewriteConstrainedMethodCalls] but keys
     * on the RECEIVER's type rather than a pre-resolved AbilityImplRef list.
     */
    private fun rewriteClosureMethodCallsByReceiver(expr: TypedExpr): TypedExpr {
        if (implIndex.isEmpty()) return expr
        // op name → all (targetTypes, opHash) an impl provides.
        // `receiverField` is the op's OWN first param name, read off the impl's
        // lambda — the field the callee expects its receiver under. Needed
        // because the rewrite below erases the field access, after which the
        // receiver can only travel inside the arg record.
        data class OpTarget(val targets: List<MezzeType>, val opHash: Hash, val receiverField: String?)
        val opTargets = HashMap<String, MutableList<OpTarget>>()
        for (impl in implIndex.values) {
            for (opName in impl.operations.keys) {
                opTargets.getOrPut(opName) { mutableListOf() }
                    .add(
                        OpTarget(
                            impl.targetTypes,
                            Hash.computeOpHash(impl.hash, opName),
                            (impl.operations[opName] as? com.mezze.language.typedast.TypedLambda)
                                ?.params?.firstOrNull()?.name,
                        )
                    )
            }
        }
        if (opTargets.isEmpty()) return expr

        // A target type is "user-declared" (nominal, carries a content hash) vs
        // a builtin primitive (Int/Float/…) or type-variable. Numeric operator
        // abilities (NumAdd/NumMul/…) target ONLY builtins and always have many
        // impls, so restricting to a single-impl op whose sole target is a
        // user-declared type both (a) excludes every numeric operator — those are
        // resolved by primitive inlining, never here — and (b) is exactly the
        // shape of a user ability like `Priced for Item`.
        fun isUserDeclaredTarget(t: MezzeType): Boolean =
            t is MezzeType.TypeApplication && t.identity is com.mezze.language.ast.TypeIdentity.Declared

        fun resolveOpTarget(fieldName: String, receiverType: MezzeType): OpTarget? {
            val cands = opTargets[fieldName] ?: return null
            // Concrete receiver: dispatch precisely by matching its type to an
            // impl target (exactly one match, else leave untouched — a no/ambiguous
            // match means this wasn't a deferred call on this concrete type).
            if (receiverType !is MezzeType.VarType) {
                val matches = cands.filter { c -> c.targets.any { typeMatchesTarget(receiverType, it) } }
                matches.singleOrNull()?.let { return it }
                // No structural match — but the receiver may be the EXPANDED form of
                // the impl's nominal target (e.g. `Item` inlined to its RecordType
                // `{name,price,qty}`, which typeMatchesTarget won't equate with
                // `TypeApplication(Item)`). Fall through to the single-user-declared
                // impl case below, which is the deferred user-ability call regardless
                // of whether the receiver surfaced as nominal or structural.
            }
            // Resolve the unambiguous deferred user-ability call: a single impl for
            // this op whose target is a user-declared type. Covers both the still-
            // polymorphic fold-folder receiver (`VarType`) and the case above where
            // the concrete receiver is a structural record that won't nominally match
            // its own impl target. Never a numeric operator (those are multi-impl,
            // builtin-target — excluded — and resolved by primitive inlining).
            val sole = cands.singleOrNull() ?: return null
            return if (sole.targets.all { isUserDeclaredTarget(it) }) sole else null
        }

        fun rw(e: TypedExpr): TypedExpr = when (e) {
            is TypedCall -> {
                val fn = e.function
                val newArg = rw(e.arg)
                if (fn is TypedFieldAccess) {
                    val target = resolveOpTarget(fn.field, fn.record.type)
                    if (target != null) {
                        // Erasing the field access strands the receiver unless it
                        // rides in the arg record. Method syntax already folded it
                        // there (TypedAstBuilder), but operator desugaring — `a == b`
                        // becomes FieldAccess(record=a, field=eq) with arg `{r = b}` —
                        // leaves it only in fn.record, so the callee saw a record
                        // missing its first param and returned a partially-applied
                        // function instead of a Bool (`[T] == [T]` was always false).
                        e.copy(
                            function = TypedVar(fn.field, BindingRef.ByHash(target.opHash), fn.type, fn.loc),
                            arg = injectReceiver(newArg, rw(fn.record), target.receiverField),
                        )
                    } else e.copy(function = fn.copy(record = rw(fn.record)), arg = newArg)
                } else e.copy(function = rw(fn), arg = newArg)
            }
            is TypedFieldAccess -> e.copy(record = rw(e.record))
            is com.mezze.language.typedast.TypedLambda -> e.copy(body = rw(e.body))
            is com.mezze.language.typedast.TypedRecord -> e.copy(entries = e.entries.map {
                when (it) {
                    is com.mezze.language.typedast.TypedRecordEntry.Field -> it.copy(value = rw(it.value))
                    is com.mezze.language.typedast.TypedRecordEntry.Spread -> it.copy(expr = rw(it.expr))
                }
            })
            is com.mezze.language.typedast.TypedRecordUpdate -> e.copy(
                record = rw(e.record),
                updates = e.updates.map {
                    when (it) {
                        is com.mezze.language.typedast.TypedRecordEntry.Field -> it.copy(value = rw(it.value))
                        is com.mezze.language.typedast.TypedRecordEntry.Spread -> it.copy(expr = rw(it.expr))
                    }
                })
            is com.mezze.language.typedast.TypedVariantConstruct -> e.copy(payload = e.payload?.let { rw(it) })
            is com.mezze.language.typedast.TypedMatch -> e.copy(
                scrutinee = rw(e.scrutinee),
                cases = e.cases.map { c -> c.copy(guard = c.guard?.let { rw(it) }, body = rw(c.body)) })
            is com.mezze.language.typedast.TypedIf -> e.copy(condition = rw(e.condition), thenBranch = rw(e.thenBranch), elseBranch = rw(e.elseBranch))
            is com.mezze.language.typedast.TypedBlock -> e.copy(
                statements = e.statements.map { s ->
                    when (s) {
                        is com.mezze.language.typedast.TypedStatement.Let -> s.copy(value = rw(s.value))
                        is com.mezze.language.typedast.TypedStatement.Expr -> s.copy(expr = rw(s.expr))
                    }
                },
                result = rw(e.result))
            is com.mezze.language.typedast.TypedSeq -> e.copy(elements = e.elements.map { el ->
                when (el) {
                    is com.mezze.language.typedast.TypedSeqElement.Regular -> el.copy(expr = rw(el.expr))
                    is com.mezze.language.typedast.TypedSeqElement.Spread -> el.copy(expr = rw(el.expr))
                }
            })
            is com.mezze.language.typedast.TypedPipe -> e.copy(left = rw(e.left), right = rw(e.right))
            is com.mezze.language.typedast.TypedPerform -> e.copy(body = rw(e.body))
            is com.mezze.language.typedast.TypedPerformOp -> e.copy(arg = rw(e.arg))
            is com.mezze.language.typedast.TypedStringLit -> e.copy(parts = e.parts.map { part ->
                when (part) {
                    is com.mezze.language.typedast.TypedStringPart.Literal -> part
                    is com.mezze.language.typedast.TypedStringPart.Interpolation ->
                        com.mezze.language.typedast.TypedStringPart.Interpolation(rw(part.expr))
                }
            })
            else -> e
        }
        return rw(expr)
    }

    /** Per-method dispatch table used by [rewriteCalls]. Multiple impls may
     *  provide the same op name (e.g. `Shape for Square` + `Shape for Rect`
     *  both define `area`). At each call site we resolve by matching the
     *  receiver's substituted type against the impl's `targetTypes` receiver
     *  position — same shape as [tryResolveAbilityOp] in the compiler. */
    private data class OpEntry(val impl: TypedAbilityImpl, val opHash: Hash)

    private fun rewriteConstrainedMethodCalls(
        expr: TypedExpr,
        abilityImpls: List<AbilityImplRef>,
    ): TypedExpr {
        if (abilityImpls.isEmpty()) return expr
        val opsByName = HashMap<String, MutableList<OpEntry>>()
        for (ref in abilityImpls) {
            val impl = implIndex[ref.implHash] ?: continue
            for (opName in impl.operations.keys) {
                // computeOpHash matches what TypedASTCompiler uses when it
                // emits each impl operation as a standalone function — we're
                // pointing at the same compiled function.
                opsByName.getOrPut(opName) { mutableListOf() }
                    .add(OpEntry(impl, Hash.computeOpHash(impl.hash, opName)))
            }
        }
        if (opsByName.isEmpty()) return expr
        return rewriteCalls(expr, opsByName)
    }

    /** Pick the impl whose receiver target matches [receiverType]. The receiver
     *  slot is the LAST entry in `targetTypes` (per the class-arg tuple with
     *  self last, see project_classparam_runtime_dispatch_tuple). Returns null
     *  on no match or genuine ambiguity — the caller falls through to the
     *  unmodified TypedFieldAccess call, which the compiler resolves separately. */
    private fun selectOpEntry(entries: List<OpEntry>, receiverType: MezzeType): OpEntry? {
        if (entries.size == 1) return entries.first()
        val matches = entries.filter { e ->
            val recvTarget = e.impl.targetTypes.lastOrNull() ?: return@filter false
            typeMatchesTarget(receiverType, recvTarget)
        }
        val unique = matches.distinctBy { it.opHash }
        return if (unique.size == 1) unique.first() else null
    }

    private fun selectOpHash(entries: List<OpEntry>, receiverType: MezzeType): Hash? =
        selectOpEntry(entries, receiverType)?.opHash

    /** Prepend `fieldName = receiver` to [arg] unless it already binds that
     *  field. Mirrors the receiver-folding in TypedAstBuilder's member-call
     *  lowering, so a rewritten op call carries its receiver whether the call
     *  came from method syntax (already folded) or operator desugaring (not).
     *  Returns [arg] untouched when the receiver name is unknown or the arg
     *  isn't a record — the shapes the old code already handled correctly. */
    private fun injectReceiver(
        arg: TypedExpr,
        receiver: TypedExpr,
        fieldName: String?,
    ): TypedExpr {
        if (fieldName == null) return arg
        if (arg !is com.mezze.language.typedast.TypedRecord) return arg
        val alreadyBound = arg.entries.any {
            it is com.mezze.language.typedast.TypedRecordEntry.Field && it.name == fieldName
        }
        if (alreadyBound) return arg
        val receiverEntry = com.mezze.language.typedast.TypedRecordEntry.Field(
            fieldName,
            receiver,
            receiver.loc,
        )
        val fieldTypes = LinkedHashMap<String, MezzeType>()
        fieldTypes[fieldName] = receiver.type
        for (e in arg.entries) {
            if (e is com.mezze.language.typedast.TypedRecordEntry.Field) {
                fieldTypes[e.name] = e.value.type
            }
        }
        return com.mezze.language.typedast.TypedRecord(
            entries = listOf(receiverEntry) + arg.entries,
            type = MezzeType.RecordType(fieldTypes),
            loc = arg.loc,
        )
    }

    private fun rewriteCalls(expr: TypedExpr, opsByName: Map<String, List<OpEntry>>): TypedExpr {
        return when (expr) {
            is TypedCall -> {
                val fn = expr.function
                val rewrittenArg = rewriteCalls(expr.arg, opsByName)
                if (fn is TypedFieldAccess) {
                    val entries = opsByName[fn.field]
                    val entry = entries?.let { selectOpEntry(it, fn.record.type) }
                    if (entry != null) {
                        // Rewrite `receiver.method({it = receiver, …})` to a
                        // direct ByHash call. The function position becomes a
                        // TypedVar bound to the op-hash. fn.type is the
                        // (already-substituted) method function type, e.g.
                        // {it: Int} -> Str.
                        val direct = TypedVar(
                            name = fn.field,
                            binding = BindingRef.ByHash(entry.opHash),
                            type = fn.type,
                            loc = fn.loc,
                        )
                        // Once the field access is gone the receiver can only
                        // reach the callee through the arg record. Method-call
                        // syntax already folded it in (TypedAstBuilder), but
                        // operator desugaring (`a == b`) leaves the receiver in
                        // fn.record and passes only `{r = b}` — dropping it
                        // there calls `eq` with a missing first param, which
                        // then returns a partially-applied function instead of
                        // a Bool. Re-add it under the op's OWN first-param
                        // name, read off the impl, never a hardcoded "it".
                        val receiverField =
                            (entry.impl.operations[fn.field] as? com.mezze.language.typedast.TypedLambda)
                                ?.params?.firstOrNull()?.name
                        return expr.copy(
                            function = direct,
                            arg = injectReceiver(
                                arg = rewrittenArg,
                                receiver = rewriteCalls(fn.record, opsByName),
                                fieldName = receiverField,
                            ),
                        )
                    }
                    // Not a constraint-driven method — still recurse into the
                    // receiver (it might contain another method call).
                    return expr.copy(
                        function = fn.copy(record = rewriteCalls(fn.record, opsByName)),
                        arg = rewrittenArg,
                    )
                }
                expr.copy(
                    function = rewriteCalls(fn, opsByName),
                    arg = rewrittenArg,
                )
            }
            is com.mezze.language.typedast.TypedLambda -> expr.copy(
                body = rewriteCalls(expr.body, opsByName),
            )
            is com.mezze.language.typedast.TypedRecord -> expr.copy(
                entries = expr.entries.map { rewriteRecordEntry(it, opsByName) },
            )
            is com.mezze.language.typedast.TypedRecordUpdate -> expr.copy(
                record = rewriteCalls(expr.record, opsByName),
                updates = expr.updates.map { rewriteRecordEntry(it, opsByName) },
            )
            is TypedFieldAccess -> expr.copy(
                record = rewriteCalls(expr.record, opsByName),
            )
            is com.mezze.language.typedast.TypedVariantConstruct -> {
                val payload = expr.payload
                if (payload == null) expr
                else expr.copy(payload = rewriteCalls(payload, opsByName))
            }
            is com.mezze.language.typedast.TypedMatch -> expr.copy(
                scrutinee = rewriteCalls(expr.scrutinee, opsByName),
                cases = expr.cases.map { c ->
                    c.copy(
                        guard = c.guard?.let { rewriteCalls(it, opsByName) },
                        body = rewriteCalls(c.body, opsByName),
                    )
                },
            )
            is com.mezze.language.typedast.TypedIf -> expr.copy(
                condition = rewriteCalls(expr.condition, opsByName),
                thenBranch = rewriteCalls(expr.thenBranch, opsByName),
                elseBranch = rewriteCalls(expr.elseBranch, opsByName),
            )
            is com.mezze.language.typedast.TypedBlock -> expr.copy(
                statements = expr.statements.map { s ->
                    when (s) {
                        is com.mezze.language.typedast.TypedStatement.Let ->
                            s.copy(value = rewriteCalls(s.value, opsByName))
                        is com.mezze.language.typedast.TypedStatement.Expr ->
                            s.copy(expr = rewriteCalls(s.expr, opsByName))
                    }
                },
                result = rewriteCalls(expr.result, opsByName),
            )
            is com.mezze.language.typedast.TypedSeq -> expr.copy(
                elements = expr.elements.map { el ->
                    when (el) {
                        is com.mezze.language.typedast.TypedSeqElement.Regular ->
                            el.copy(expr = rewriteCalls(el.expr, opsByName))
                        is com.mezze.language.typedast.TypedSeqElement.Spread ->
                            el.copy(expr = rewriteCalls(el.expr, opsByName))
                    }
                },
            )
            is com.mezze.language.typedast.TypedPipe -> expr.copy(
                left = rewriteCalls(expr.left, opsByName),
                right = rewriteCalls(expr.right, opsByName),
            )
            is com.mezze.language.typedast.TypedPerform -> expr.copy(
                body = rewriteCalls(expr.body, opsByName),
            )
            is com.mezze.language.typedast.TypedPerformOp -> expr.copy(
                arg = rewriteCalls(expr.arg, opsByName),
            )
            // #225: a string interpolation `"$(… x.tag {} …)"` embeds exprs that
            // may contain constraint-driven method calls. Without recursing here,
            // an ability-method call inside an interpolation inside a mono-
            // specialized generic was NEVER rewritten to a direct op-hash call —
            // its receiver stayed a VarType, forcing dict-passing, and a hash
            // collision with the dict-passing lambda then collapsed the two into
            // one FunctionIR with mismatched capture arity → ArrayIndexOOB at
            // runtime. Recursing fixes dispatch AND (because interpolation exprs
            // are hashed) dissolves the collision. Mirror TypeSubstitution.applyStringPart.
            is com.mezze.language.typedast.TypedStringLit -> expr.copy(
                parts = expr.parts.map { part ->
                    when (part) {
                        is com.mezze.language.typedast.TypedStringPart.Literal -> part
                        is com.mezze.language.typedast.TypedStringPart.Interpolation ->
                            com.mezze.language.typedast.TypedStringPart.Interpolation(
                                rewriteCalls(part.expr, opsByName)
                            )
                    }
                },
            )
            // Leaves — no recursive children with TypedCalls.
            else -> expr
        }
    }

    private fun rewriteRecordEntry(
        entry: com.mezze.language.typedast.TypedRecordEntry,
        opsByName: Map<String, List<OpEntry>>,
    ): com.mezze.language.typedast.TypedRecordEntry = when (entry) {
        is com.mezze.language.typedast.TypedRecordEntry.Field ->
            entry.copy(value = rewriteCalls(entry.value, opsByName))
        is com.mezze.language.typedast.TypedRecordEntry.Spread ->
            entry.copy(expr = rewriteCalls(entry.expr, opsByName))
    }

    companion object {
        /**
         * Tag byte for specialization hashes. Distinct from any HashTag in
         * `HashTags` so there's no collision between a generic decl and its
         * own specialization.
         */
        private const val MONO_SPEC_TAG: Byte = 0x70

        /**
         * Compute the content hash for a specialization.
         *
         *   H( MONO_SPEC_TAG
         *      || lengthPrefixed(genericHash hex)
         *      || varint(typeArgs.size)
         *      || for each type arg: appendType(arg)
         *      || varint(abilityImpls.size)
         *      || for each impl: appendImplBinding(impl) )
         *
         * Bytes-only inputs, byte-tagged, length-prefixed — no string concat
         * tricks. Two projects that specialize the same generic for the
         * same types produce the same specialized hash.
         */
        fun specializedHash(
            genericHash: Hash,
            typeArgs: List<MezzeType>,
            abilityImpls: List<AbilityImplRef>,
        ): Hash = specializedHash(genericHash, typeArgs, abilityImpls, emptyMap())

        /**
         * Overload of [specializedHash] that folds closure-arg hashes into
         * the identity — two call sites of the same generic with the same
         * lambda literal for a given field dedupe to one spec; different
         * lambdas produce different specs. See docs/perf/mono-closure-
         * specialization.md.
         */
        fun specializedHash(
            genericHash: Hash,
            typeArgs: List<MezzeType>,
            abilityImpls: List<AbilityImplRef>,
            closureArgs: Map<String, Hash>,
        ): Hash {
            val b = MezzeHashBuilder()
            b.append(byteArrayOf(MONO_SPEC_TAG))
            b.append(genericHash.hex)
            // Bundle every type input into a single synthetic record so
            // [normalizeTypeVariables] walks all of them under one
            // typevar numbering. Without this, two type args that share
            // an unsubstituted TypeVar would get normalized to '`a` in
            // each leaf — but their identity link would be lost, making
            // `spec<T, T>` and `spec<T, U>` hash identically.
            // Zero-pad indices so lexical field-key sort matches numeric
            // order — `appendTypeInScope` walks RecordType fields in
            // sorted order, so a0..a10 must sort as 0..10, not 0..1,10,11..
            fun key(prefix: String, i: Int) = "$prefix${i.toString().padStart(6, '0')}"
            val bundleFields = LinkedHashMap<String, MezzeType>()
            for ((i, t) in typeArgs.withIndex()) bundleFields[key("a", i)] = t
            for ((i, impl) in abilityImpls.withIndex()) bundleFields[key("i", i)] = impl.targetType
            val bundle = MezzeType.RecordType(bundleFields, rowVar = null)
            val canonical = com.mezze.language.ast.normalizeTypeVariables(bundle) as MezzeType.RecordType

            b.appendInt(typeArgs.size)
            for (i in typeArgs.indices) {
                com.mezze.language.ast.hashing.appendTypeInScope(b, canonical.fields[key("a", i)]!!)
            }
            b.appendInt(abilityImpls.size)
            for (i in abilityImpls.indices) {
                appendImplBindingInScope(b, abilityImpls[i], canonical.fields[key("i", i)]!!)
            }
            // #424: fold closure-arg hashes in. Sorted by field name so
            // ordering in the LinkedHashMap can't change identity.
            b.appendInt(closureArgs.size)
            val sortedNames = closureArgs.keys.sorted()
            for (name in sortedNames) {
                b.append(name.toByteArray(Charsets.UTF_8))
                b.append(closureArgs.getValue(name).hex)
            }
            return b.buildHash()
        }

        /** Hash an ability-impl binding. Three resolved hashes + the
         *  impl's target type. The targetType has already been
         *  normalized in the enclosing scope by [specializedHash]. */
        private fun appendImplBindingInScope(
            b: MezzeHashBuilder,
            impl: AbilityImplRef,
            normalizedTargetType: MezzeType,
        ) {
            b.append(byteArrayOf(HashTags.IMPL_ABILITY_DECLARATION, 0x01))
            b.append(impl.abilityHash.hex)
            b.append(impl.implHash.hex)
            com.mezze.language.ast.hashing.appendTypeInScope(b, normalizedTargetType)
        }

        // ────────────────────────────────────────────────────────────
        // #424 closure identity: give every lambda literal a content
        // hash — its "closure type." Two structurally identical lambdas
        // hash the same; different ones hash differently. Foundation
        // for #416 mono lambda-value specialization: an HOF call site
        // that passes a lambda literal gets a specialization keyed on
        // (typeArgs + closureHashes), and the specialized body has
        // the lambda's IR spliced in place of every read of the
        // closure parameter — same as Rust monomorphizing on the
        // closure's anonymous struct type.
        //
        // This piece (Piece 1) exposes the hash surface only. It has
        // no effect on emitted code — Piece 2 wires it into
        // SpecRequest, Piece 3 wires the splice at emit time.
        // See docs/perf/mono-closure-specialization.md.
        // ────────────────────────────────────────────────────────────

        /**
         * Compute the closure identity hash of a lambda literal.
         *
         * Two lambdas are the same closure iff they hash the same.
         * The hash IGNORES the outer type (mono handles that
         * separately) but INCLUDES the body structure — so
         * `{a,x} -> a+x` and `{a,x} -> a+x+0` hash differently,
         * `{a,x} -> a+x` and `{b,y} -> b+y` hash the same
         * (alpha-equivalent — bound-var names are de Bruijn'd
         * inside TypedASTHasher).
         *
         * Delegates to [com.mezze.language.typedast.TypedASTHasher]
         * — that hasher is already content-based over TypedLambda's
         * params + body + captures, which is exactly the identity
         * we want.
         */
        fun hashClosure(lambda: com.mezze.language.typedast.TypedLambda): Hash =
            com.mezze.language.typedast.TypedASTHasher.hashExpr(lambda)

        /**
         * Extract closure-arg hashes from an HOF call site.
         *
         * Returns a map `fieldName → closureHash` for every record
         * field of the call's arg that is a lambda literal. Fields
         * whose value is NOT a lambda literal (variable binding,
         * captured value, function-application result) are omitted
         * — those can't be specialized without escape analysis
         * (out of scope for Piece 1 per Q1 "strict" decision in
         * the design doc).
         *
         * Callers: mono worklist scan. When a `TypedCall` targets a
         * generic decl whose signature has functional record-field
         * parameters (fold's `f`, filter's `p`, etc.), this returns
         * which of those fields the caller passed a lambda literal
         * for. The mono driver then keys the specialization on
         * `(typeArgs + closureHashes)`.
         *
         * Returns an empty map if the call's arg is not a record
         * literal, or contains no lambda-literal fields.
         */
        fun closureArgsAt(call: TypedCall): Map<String, Hash> {
            val arg = call.arg
            if (arg !is com.mezze.language.typedast.TypedRecord) return emptyMap()
            val out = LinkedHashMap<String, Hash>()
            for (entry in arg.entries) {
                if (entry !is com.mezze.language.typedast.TypedRecordEntry.Field) continue
                val value = entry.value
                if (value !is com.mezze.language.typedast.TypedLambda) continue
                out[entry.name] = hashClosure(value)
            }
            return out
        }

        /** Companion to [closureArgsAt]: for the same fields, return the
         *  actual `TypedLambda` IR — Piece 3 splices these into the
         *  specialized body. */
        fun closureBodiesAt(call: TypedCall): Map<String, com.mezze.language.typedast.TypedLambda> {
            val arg = call.arg
            if (arg !is com.mezze.language.typedast.TypedRecord) return emptyMap()
            val out = LinkedHashMap<String, com.mezze.language.typedast.TypedLambda>()
            for (entry in arg.entries) {
                if (entry !is com.mezze.language.typedast.TypedRecordEntry.Field) continue
                val value = entry.value
                if (value !is com.mezze.language.typedast.TypedLambda) continue
                out[entry.name] = value
            }
            return out
        }

        /** #424 feature-gate. Piece 3 splice + Piece 2 closure keying were
         *  behind MEZZE_MONO_CLOSURES=1 for the initial land. Default
         *  flipped to on after both env-var settings ran green on the
         *  full :interpreter:test suite (5325 passed, 0 failed).
         *  MEZZE_MONO_CLOSURES=0 remains a runtime kill-switch. */
        val closuresEnabled: Boolean by lazy {
            System.getenv("MEZZE_MONO_CLOSURES") != "0"
        }
    }
}
