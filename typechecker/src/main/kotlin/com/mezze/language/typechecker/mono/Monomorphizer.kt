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
import com.mezze.language.typedast.TypedAbilityImpl
import com.mezze.language.typedast.TypedCall
import com.mezze.language.typedast.TypedExpr
import com.mezze.language.typedast.TypedFieldAccess
import com.mezze.language.typedast.TypedLetDecl
import com.mezze.language.typedast.TypedVar
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
    /** Worklist depth limit. Default matches the spec's recommendation. */
    private val maxDepth: Int = 16,
) {

    /** A request to specialize `genericHash` for the given type/impl args. */
    data class SpecRequest(
        val genericHash: Hash,
        val typeArgs: List<MezzeType>,
        val abilityImpls: List<AbilityImplRef>,
    )

    /** Identity key for the `done` set — drops abilityImpls because they are
     *  derivable from typeArgs once the impl table is consulted. The full
     *  triple still goes into the specialization hash. */
    private data class DoneKey(val genericHash: Hash, val typeArgs: List<MezzeType>)

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
            val key = DoneKey(req.genericHash, req.typeArgs)

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

            val specHash = specializedHash(req.genericHash, req.typeArgs, req.abilityImpls)
            done[key] = specHash
            rewrites[req] = specHash
            specializations[specHash] = Specialization(
                specializedHash = specHash,
                genericHash = req.genericHash,
                typeArgs = req.typeArgs,
                abilityImpls = req.abilityImpls,
                specializedBody = newBody,
                specializedType = newType,
            )

            // Scan the substituted body for further generic call sites and
            // enqueue them. `collectByHashRefsWithSite` yields
            // `(ByHash, TypeInstantiation?, siteType?)` triples — the site
            // type is the substituted `fn.type` at each call, which lets us
            // recover concrete type-args via unification when the AST-
            // stamped instantiation is missing or wrong-arity.
            val refs = mutableListOf<Triple<BindingRef.ByHash, TypeInstantiation?, MezzeType?>>()
            TypeSubstitution.collectByHashRefsWithSite(newBody) { ref, inst, siteType ->
                refs.add(Triple(ref, inst, siteType))
            }
            if (System.getenv("MEZZE_MONO_TRACE") != null) {
                System.err.println("[mono-trace] transitive refs from ${decl.name}: total=${refs.size}")
                for ((ref, inst, siteType) in refs) {
                    val target = resolveDecl(ref.hash)
                    System.err.println("[mono-trace]   ref=${ref.hash} target=${target?.name ?: "<unresolved>"} typeParams=${target?.typeParams?.size ?: 0} inst=$inst siteType=$siteType")
                }
            }
            // #409: for each transitive ref, derive typeArgs by unifying
            // the callee's declared generic type against the substituted
            // call-site's fn type. This is more reliable than trusting
            // `TypedCall.instantiation`, which for ability/impl-wrapper
            // methods stamps the wrong shape (impl target-type instead of
            // each declared typeParam).
            for ((ref, inst, siteType) in refs) {
                val targetDecl = resolveDecl(ref.hash) ?: continue
                if (targetDecl.typeParams.isEmpty()) continue
                val derived = deriveTransitiveTypeArgs(targetDecl, inst, siteType)
                    ?: continue
                worklist.addLast(
                    SpecRequest(ref.hash, derived, inst?.abilityImpls ?: emptyList()) to (depth + 1)
                )
            }
        }

        return Result(specializations, rewrites, fallbacks)
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
        siteType: MezzeType?
    ): List<MezzeType>? {
        val typeParams = callee.typeParams
        if (typeParams.isEmpty()) return null

        if (inst != null && inst.typeArgs.size == typeParams.size &&
            inst.typeArgs.all { isConcreteEnough(it) }
        ) {
            return inst.typeArgs
        }

        if (siteType == null) return null
        val bindings = HashMap<String, MezzeType>()
        if (!unify(callee.scheme.type, siteType, bindings)) return null
        val args = ArrayList<MezzeType>(typeParams.size)
        for (tp in typeParams) {
            val b = bindings[tp.name]
            if (b != null) {
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
     * operations becomes an entry in `methodNameToOpHash`: the next time we
     * encounter a `TypedCall(TypedFieldAccess(_, op_name), arg, …)` in the
     * body, we rewrite it as if it were a top-level call to that op.
     *
     * The arg is left unchanged — the typechecker already desugared
     * `receiver.method {…}` into `method({it = receiver, …})`, so the arg
     * record already carries the receiver under field `it`.
     *
     * If `methodNameToOpHash` is empty (no constraints, or impls absent from
     * the index), the body is returned unchanged. Recursion mirrors the
     * shape of `TypeSubstitution.applyExpr`.
     */
    private fun rewriteConstrainedMethodCalls(
        expr: TypedExpr,
        abilityImpls: List<AbilityImplRef>,
    ): TypedExpr {
        if (abilityImpls.isEmpty()) return expr
        val methodNameToOpHash = HashMap<String, Hash>()
        for (ref in abilityImpls) {
            val impl = implIndex[ref.implHash] ?: continue
            for (opName in impl.operations.keys) {
                // computeOpHash matches what TypedASTCompiler uses when it
                // emits each impl operation as a standalone function — we're
                // pointing at the same compiled function.
                methodNameToOpHash[opName] = Hash.computeOpHash(impl.hash, opName)
            }
        }
        if (methodNameToOpHash.isEmpty()) return expr
        return rewriteCalls(expr, methodNameToOpHash)
    }

    private fun rewriteCalls(expr: TypedExpr, methodNameToOpHash: Map<String, Hash>): TypedExpr {
        return when (expr) {
            is TypedCall -> {
                val fn = expr.function
                val rewrittenArg = rewriteCalls(expr.arg, methodNameToOpHash)
                if (fn is TypedFieldAccess) {
                    val opHash = methodNameToOpHash[fn.field]
                    if (opHash != null) {
                        // Rewrite `receiver.method({it = receiver, …})` to a
                        // direct ByHash call. The function position becomes a
                        // TypedVar bound to the op-hash; the arg record is
                        // unchanged. fn.type is the (already-substituted)
                        // method function type, e.g. {it: Int} -> Str.
                        val direct = TypedVar(
                            name = fn.field,
                            binding = BindingRef.ByHash(opHash),
                            type = fn.type,
                            loc = fn.loc,
                        )
                        return expr.copy(
                            function = direct,
                            arg = rewrittenArg,
                        )
                    }
                    // Not a constraint-driven method — still recurse into the
                    // receiver (it might contain another method call).
                    return expr.copy(
                        function = fn.copy(record = rewriteCalls(fn.record, methodNameToOpHash)),
                        arg = rewrittenArg,
                    )
                }
                expr.copy(
                    function = rewriteCalls(fn, methodNameToOpHash),
                    arg = rewrittenArg,
                )
            }
            is com.mezze.language.typedast.TypedLambda -> expr.copy(
                body = rewriteCalls(expr.body, methodNameToOpHash),
            )
            is com.mezze.language.typedast.TypedRecord -> expr.copy(
                entries = expr.entries.map { rewriteRecordEntry(it, methodNameToOpHash) },
            )
            is com.mezze.language.typedast.TypedRecordUpdate -> expr.copy(
                record = rewriteCalls(expr.record, methodNameToOpHash),
                updates = expr.updates.map { rewriteRecordEntry(it, methodNameToOpHash) },
            )
            is TypedFieldAccess -> expr.copy(
                record = rewriteCalls(expr.record, methodNameToOpHash),
            )
            is com.mezze.language.typedast.TypedVariantConstruct -> {
                val payload = expr.payload
                if (payload == null) expr
                else expr.copy(payload = rewriteCalls(payload, methodNameToOpHash))
            }
            is com.mezze.language.typedast.TypedMatch -> expr.copy(
                scrutinee = rewriteCalls(expr.scrutinee, methodNameToOpHash),
                cases = expr.cases.map { c ->
                    c.copy(
                        guard = c.guard?.let { rewriteCalls(it, methodNameToOpHash) },
                        body = rewriteCalls(c.body, methodNameToOpHash),
                    )
                },
            )
            is com.mezze.language.typedast.TypedIf -> expr.copy(
                condition = rewriteCalls(expr.condition, methodNameToOpHash),
                thenBranch = rewriteCalls(expr.thenBranch, methodNameToOpHash),
                elseBranch = rewriteCalls(expr.elseBranch, methodNameToOpHash),
            )
            is com.mezze.language.typedast.TypedBlock -> expr.copy(
                statements = expr.statements.map { s ->
                    when (s) {
                        is com.mezze.language.typedast.TypedStatement.Let ->
                            s.copy(value = rewriteCalls(s.value, methodNameToOpHash))
                        is com.mezze.language.typedast.TypedStatement.Expr ->
                            s.copy(expr = rewriteCalls(s.expr, methodNameToOpHash))
                    }
                },
                result = rewriteCalls(expr.result, methodNameToOpHash),
            )
            is com.mezze.language.typedast.TypedSeq -> expr.copy(
                elements = expr.elements.map { el ->
                    when (el) {
                        is com.mezze.language.typedast.TypedSeqElement.Regular ->
                            el.copy(expr = rewriteCalls(el.expr, methodNameToOpHash))
                        is com.mezze.language.typedast.TypedSeqElement.Spread ->
                            el.copy(expr = rewriteCalls(el.expr, methodNameToOpHash))
                    }
                },
            )
            is com.mezze.language.typedast.TypedPipe -> expr.copy(
                left = rewriteCalls(expr.left, methodNameToOpHash),
                right = rewriteCalls(expr.right, methodNameToOpHash),
            )
            is com.mezze.language.typedast.TypedPerform -> expr.copy(
                body = rewriteCalls(expr.body, methodNameToOpHash),
            )
            is com.mezze.language.typedast.TypedPerformOp -> expr.copy(
                arg = rewriteCalls(expr.arg, methodNameToOpHash),
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
                                rewriteCalls(part.expr, methodNameToOpHash)
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
        methodNameToOpHash: Map<String, Hash>,
    ): com.mezze.language.typedast.TypedRecordEntry = when (entry) {
        is com.mezze.language.typedast.TypedRecordEntry.Field ->
            entry.copy(value = rewriteCalls(entry.value, methodNameToOpHash))
        is com.mezze.language.typedast.TypedRecordEntry.Spread ->
            entry.copy(expr = rewriteCalls(entry.expr, methodNameToOpHash))
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
    }
}
