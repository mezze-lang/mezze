package com.mezze.language.typechecker.solving

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeIdentity
import com.mezze.language.ast.prettyPrintType
import com.mezze.language.ast.solving.Substitution
import com.mezze.language.parsedast.Loc
import com.mezze.language.typechecker.constraints.ConstraintResult
import com.mezze.language.typechecker.util.TypeAnnotationMatcher
import com.mezze.language.typechecker.constraints.MethodResolveBucket
import com.mezze.language.typechecker.constraints.Resolution
import com.mezze.language.typechecker.constraints.ResolutionCandidate
import com.mezze.language.typechecker.constraints.ResolutionKind
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.util.declaredHashOf
import com.mezze.language.typechecker.util.receiverTypeName
import com.mezze.language.typechecker.util.receiverDispatchHashes
import com.mezze.language.typedast.Hash

/**
 * S2.2 (#357): the SINGLE post-solve dispatch resolution pass — the analytical
 * heart of Stage 2. For every [MethodResolveBucket] recorded during generation
 * it resolves the receiver's SOLVED type to its [TypeIdentity] and applies ONE
 * hash/identity-keyed rule to select the winning op, producing a [Resolution]
 * keyed by call-site [Loc]. Genuine same-priority ambiguity is a HARD error —
 * never a silent load-order `.first()` pick.
 *
 * BEHAVIOUR-ADDITIVE in S2.2: the produced [Resolution]s are populated but NOT
 * yet read (emit/mono/compile keep reading the gen-time ladder's recorded
 * [ConstraintResult.MethodCallInfo] until S2.3). To PROVE the rule matches the
 * live ladder (the only intended behaviour change being ambiguity-becomes-error),
 * [resolveDispatch] cross-checks each produced opHash against the ladder's
 * recorded choice under `-Dmezze.invariants=true` and throws on divergence — the
 * exhaustive test suite becomes the parity oracle.
 *
 * The rule, HASH/IDENTITY-KEYED, ZERO name fallback for the primary key:
 *   1. Resolve the receiver's solved type → its identity key: `Declared.hash`
 *      (nominal user type), `Builtin.name`/scalar name (builtin), or structural
 *      (anonymous). A still-`VarType` receiver is genuinely polymorphic/deferred.
 *   2. Among INHERENT candidates: exact-hash impl wins (`targetTypeIdentity.hash
 *      == receiver hash`); when the receiver is hashless (builtin/scalar) the
 *      reserved name is its identity, so name-match is the correct fallback THERE
 *      only. A hashless candidate is a builtin-/scalar-TARGETED impl (`Seq`,
 *      `Int`, …) identified by its name — never a blanket match-any.
 *   3. Ability / builtin candidates carry the op family; the chosen ability op's
 *      hash is its declaration content hash (null for builtin operator abilities,
 *      whose reserved name is their canonical identity).
 *   4. ≥2 genuinely same-priority winners → [DispatchAmbiguityException].
 *
 * S9 (#374) — INTENTIONAL BOUNDARY: this pass fully OWNS inherent dispatch, but
 * ability dispatch stays generated at gen-time (ExprMethodConstraints's ability
 * ladder) BY DESIGN, analyzed not overlooked. The ability ladder emits
 * AbilityRequired + AssociatedTypeResolution constraints that the solver
 * (ConstraintSolver/AbilityChecker) resolves post-solve and the interpreter
 * dispatches by hash at runtime — so ability dispatch is ALSO decided once,
 * post-solve, just through a different mechanism than a [Resolution]. There is no
 * ability MethodCallInfo dispatch decision for this pass to take over: the ability
 * MethodCallInfo carries methodHash=null / isInherentMethod=false and emit reads
 * no dispatch field off it. Moving the ability PICK here would delete nothing and
 * would require relocating the fresh-tyvar constraint-generation context that only
 * exists at gen time. See the boundary note at the ability ladder's MethodCallInfo
 * construction. Same-op ability collisions are DEFERRED below (recorded no
 * Resolution) because they are decided by effect-context, not receiver identity.
 */
object DispatchResolver {

    private val invariantsEnabled: Boolean
        get() = System.getProperty("mezze.invariants", "false") == "true"

    /** A genuine, unresolvable same-priority dispatch ambiguity (S2.2's one
     *  intended behaviour change). Carries a diagnostic message naming the
     *  method, the receiver, and the conflicting impls. */
    class DispatchAmbiguityException(
        val methodName: String,
        val receiverDisplay: String,
        val conflicting: List<String>,
        val loc: Loc?,
    ) : RuntimeException(
        "Ambiguous dispatch of '$methodName' on receiver '$receiverDisplay': " +
            "${conflicting.size} same-priority impls apply (${conflicting.joinToString(", ")}). " +
            "The receiver's type does not disambiguate which impl to use."
    )

    /** A parity divergence between the S2.2 pass and the live ladder. Thrown ONLY
     *  under `-Dmezze.invariants=true` so the suite surfaces it; never in a normal
     *  gate. Its existence going into S2.3 is a real finding (see task item 4). */
    class DispatchParityException(message: String) : RuntimeException(message)

    /**
     * Resolve every bucket to a [Resolution], keyed by call-site [Loc] (matching
     * how S2.3 emit will look it up). [substitution] is the FINAL post-solve
     * substitution; [methodCallResolutions] is the live ladder's recorded choice
     * per `(loc, methodName)` — used ONLY as the parity oracle, never as an input
     * to the rule.
     */
    fun resolveDispatch(
        buckets: List<MethodResolveBucket>,
        substitution: Substitution,
        methodCallResolutions: Map<Pair<Loc, String>, ConstraintResult.MethodCallInfo>,
        typingContext: TypingContext,
    ): Map<Loc, Resolution> {
        val resolutions = mutableMapOf<Loc, Resolution>()
        for (bucket in buckets) {
            val loc = bucket.loc ?: continue
            val solvedReceiver = substitution.apply(bucket.receiverType)
            val resolution = resolveBucket(bucket, solvedReceiver, typingContext) ?: continue
            resolutions[loc] = resolution

            if (invariantsEnabled) {
                crossCheckAgainstLadder(bucket, solvedReceiver, resolution, methodCallResolutions[loc to bucket.methodName])
            }
        }
        return resolutions
    }

    /** The identity KEY for a solved receiver: its declaration hash when nominal,
     *  else its reserved/builtin name, else null (still a tyvar / genuinely
     *  structural). */
    private fun receiverKey(solvedReceiver: MezzeType): ReceiverKey? {
        if (solvedReceiver is MezzeType.VarType) return null
        // BUG 1: a receiver has MORE than one dispatch hash — the applied hash of
        // `Result PyForeign IErr` and, less specifically, `Result`'s constructor
        // hash. Carrying only the constructor hash made two per-argument impls
        // indistinguishable here, which `pickInherent` would have reported as a
        // DispatchAmbiguityException had the name pre-filter in
        // ExprMethodConstraints not resolved them first.
        val hashes = receiverDispatchHashes(solvedReceiver)
        val name = receiverTypeName(solvedReceiver)
        return when {
            hashes.isNotEmpty() -> ReceiverKey.ByHash(hashes, name)
            name != null -> ReceiverKey.ByName(name)
            else -> null
        }
    }

    private sealed interface ReceiverKey {
        /** [hashes] in SPECIFICITY order, most-specific first. */
        data class ByHash(val hashes: List<Hash>, val name: String?) : ReceiverKey
        data class ByName(val name: String) : ReceiverKey
    }

    /**
     * Apply the hash-keyed rule to ONE bucket. Returns null when the receiver is
     * still polymorphic (a genuine deferral — the ladder would keep the call open
     * too) or when no candidate applies (the ladder falls through to a structural
     * field access; no Resolution to record). Throws [DispatchAmbiguityException]
     * on genuine same-priority ambiguity.
     */
    private fun resolveBucket(
        bucket: MethodResolveBucket,
        solvedReceiver: MezzeType,
        typingContext: TypingContext,
    ): Resolution? {
        val inherent = bucket.candidates.filter { it.kind == ResolutionKind.Inherent }
        val ability = bucket.candidates.filter { it.kind == ResolutionKind.Ability }
        val builtin = bucket.candidates.filter { it.kind == ResolutionKind.Builtin }

        // Family ordering MIRRORS the ladder: an inherent impl that targets the
        // receiver's concrete type is preferred over an ability/builtin op of the
        // same name; only when no inherent impl matches do ability/builtin win.
        val receiverIdentity = solvedReceiverIdentity(solvedReceiver)
        val key = receiverKey(solvedReceiver)

        if (inherent.isNotEmpty()) {
            val picked = pickInherent(inherent, key, bucket, solvedReceiver)
            if (picked != null) {
                val opHash = picked.opHash ?: return null
                return Resolution(
                    opHash = opHash,
                    receiverTypeIdentity = receiverIdentity ?: return null,
                    kind = ResolutionKind.Inherent,
                )
            }
            // No inherent impl matched the concrete receiver. If there is ALSO an
            // ability/builtin candidate, the ladder routes to it; otherwise the
            // ladder falls through (null).
        }

        // Ability / builtin: the winning op is decided by op family, not receiver
        // hash (the receiver hash keys the IMPL, resolved separately in the
        // ability-impl registry). A single ability op wins; a single builtin op
        // wins; genuine op-name ambiguity between two distinct ability
        // declarations is a hard error.
        if (ability.isNotEmpty()) {
            // A still-unresolved (VarType) receiver is a genuine DEFERRAL, not an
            // ambiguity: the ladder keeps the call open (it can't narrow by an
            // impl target it doesn't yet know, and picks via annotation/first at
            // gen time). Record no Resolution — S2.3 will resolve it the same way
            // the ladder does once the receiver is pinned. NEVER an ambiguity
            // error here.
            if (key == null) return null

            // Mirror the ladder's implFilteredAbilities: when >1 ability shares
            // this op name, keep only those with an impl actually targeting the
            // receiver's type (keyed on the impl registry's target types — the
            // receiver's NAME for builtins, matching the ladder's
            // resolveReceiverTypeName). If narrowing lands on a non-empty subset,
            // use it; if none match (receiver has no impl for any candidate) leave
            // the full set (the downstream AbilityRequired then errors normally).
            // Narrow the candidate abilities to those that actually have an impl
            // for THIS receiver, by matching the solved receiver type against each
            // impl's target ANNOTATION.
            //
            // This used to test `receiverName in it.targetTypes` — a membership
            // check over RENDERED names. The renderings do not agree: a `Seq 'a`
            // receiver names itself `Seq` while `impl Sized for Seq 'a` stores its
            // target as `Seq_a` (mezzeTypeToString joins the constructor and its
            // arguments with `_`). So the check failed for EVERY candidate, the
            // narrowing fell back to the unnarrowed set, and `Sized` and
            // `SizedMut` were reported as a same-op collision on a `Seq` — even
            // though `SizedMut` is implemented only by `MutSeq`, `MutString` and
            // `MutBytes`, and cannot apply to a `Seq` at all.
            //
            // MEASURED: this false collision accounted for 33 of the 35 deferrals
            // across both suites. The receiver's type does decide; the comparison
            // just could not see it.
            val narrowed = if (ability.size > 1) {
                val implMatches = ability.filter { cand ->
                    val abilityName = cand.displayName ?: return@filter false
                    typingContext.getAbilityImpls(abilityName).any { impl ->
                        val annotations = impl.targetTypeAnnotations ?: return@any false
                        val target = annotations.firstOrNull() ?: return@any false
                        // No type-declaration map: this is a NARROWING, so a
                        // match that cannot be proved simply leaves the candidate
                        // in and we fall back to the unnarrowed set — exactly the
                        // behaviour before this change. It can refine, never
                        // mis-decide.
                        TypeAnnotationMatcher.typeMatchesAnnotation(
                            solvedReceiver, target, emptyMap(), impl.typeVars, mutableMapOf()
                        )
                    }
                }
                if (implMatches.isNotEmpty()) implMatches else ability
            } else ability

            // The author named an ability at this call site (`Weigh.rank { it = x }`).
            // That is not a hint to weigh against the others — it is the answer, so
            // it is applied before ambiguity is even considered. Compared by HASH:
            // the written name was resolved to its declaration once, at generation,
            // where the scope to resolve it in was still known.
            val qualified = bucket.explicitAbilityHash
                ?.let { q -> narrowed.filter { it.opHash == q } }
                ?.takeIf { it.isNotEmpty() }
                ?: narrowed

            val distinctOps = qualified.map { it.opHash }.distinct()
            if (distinctOps.size > 1) {
                // ≥2 DISTINCT ability ops share this method name and BOTH have an
                // impl targeting the concrete receiver (e.g. the pure `Sized.size`
                // and the effectful `SizedMut.size` on Seq, or two user abilities
                // A/B with the same op). The live ladder does NOT error here: it
                // disambiguates by the call site's EFFECT signature / annotation
                // ability names / (ultimately) load order — a decision that is NOT
                // a pure function of the receiver's TYPE IDENTITY. S2.2's hash-keyed
                // rule therefore cannot pick a winner behaviour-safely here.
                //
                // This is the S2.2 SEMANTIC QUESTION (task item 4): making it a
                // hard ambiguity error would regress every `x.size {}` on a type
                // that is both Sized and SizedMut. So we DEFER — record no
                // Resolution — keeping behaviour byte-identical; S2.3 must decide
                // whether same-op ability collisions dispatch by effect-context
                // (needs threading the call's effect expectation into this pass) or
                // become a genuine error. Left UNRESOLVED here on purpose.
                //
                // MEASURED before and after the narrowing fix above.
                //
                // BEFORE: 35 deferrals across both suites, 33 of them
                // `receiver=Seq, abilities=[Sized, SizedMut]`. Those were FALSE —
                // `SizedMut` is implemented only by `MutSeq`, `MutString` and
                // `MutBytes` and can never apply to a `Seq`. The name-keyed
                // narrowing simply could not see it.
                //
                // AFTER: 2 deferrals, both `receiver=Item, abilities=[Price,
                // Weigh]` — two unrelated USER abilities declaring `rank`, from
                // DictKeyAbilityCollisionTest. The calls there are QUALIFIED
                // (`Weigh.rank { it = x }`), so nothing is ambiguous: the answer
                // is written at the call site and this pass is not reading it.
                //
                // What remains is NOT the semantic question either, but it is not
                // a one-line fix. `Weigh.rank { it = x }` names its ability, yet by
                // the time the bucket is recorded the qualifier is gone: the
                // receiver has been rewritten to the `it` argument (`x`), so
                // `recordMethodResolveBucket` sees an ordinary identifier and takes
                // its candidates from the method NAME, admitting every ability that
                // declares `rank`.
                //
                // MEASURED AGAIN after the qualifier was threaded: ZERO deferrals
                // across both suites, down from 2 and before that from 35. The two
                // that remained were `receiver=Item, abilities=[Price, Weigh]` from
                // DictKeyAbilityCollisionTest, and they were never ambiguous — the
                // calls are written `Weigh.rank { it = x }`. The qualifier was not
                // missing, it was being looked for in the wrong place: the receiver
                // is rewritten to the `it` argument, so it is `x` and never `Weigh`,
                // but the ability the author named survives on the context and is
                // now resolved to its hash and carried on the bucket.
                //
                // What can still reach here is the case that is genuinely ambiguous:
                // two DISTINCT ability ops sharing a method name, both implemented
                // for this receiver, at an UNQUALIFIED call. No occurrence of that
                // is known. It stays a deferral rather than an error because this
                // pass does not decide dispatch yet — the ladder does, and it
                // disambiguates by the call's effect signature, which is not a
                // function of the receiver's type identity and so is not something
                // a hash-keyed rule can reproduce. Making it an error is S2.3's
                // call, once the ladder is gone and there is nothing left to
                // disagree with.
                return null
            }
            val opHash = qualified.first().opHash ?: return null
            return Resolution(
                opHash = opHash,
                receiverTypeIdentity = receiverIdentity ?: solvedReceiverAnyIdentity(solvedReceiver),
                kind = ResolutionKind.Ability,
            )
        }
        if (builtin.isNotEmpty()) {
            // Builtin operator abilities have no content hash; their reserved name
            // is their canonical identity. No opHash to record → no Resolution
            // (S2.3 keys builtins by reserved name, carried separately).
            return null
        }
        return null
    }

    /**
     * Pick the inherent candidate whose target identity matches the solved
     * receiver, hash-first (declared receiver) then target-NAME (hashless
     * builtin/scalar receiver — its reserved name IS its identity). A null-hash
     * candidate is a builtin-/scalar-targeted impl identified by its name, NOT a
     * blanket/any impl. ≥2 matches with DISTINCT op hashes → a genuine hash-keyed
     * inherent ambiguity (hard error).
     */
    private fun pickInherent(
        inherent: List<ResolutionCandidate>,
        key: ReceiverKey?,
        bucket: MethodResolveBucket,
        solvedReceiver: MezzeType,
    ): ResolutionCandidate? {
        if (key == null) {
            // Receiver still polymorphic: a single candidate is a deferral the
            // ladder keeps open; ≥2 is not resolvable here.
            return null
        }
        // Mirror the ladder's generateInherentMethodCall selection EXACTLY:
        //   - receiver carries a decl hash → match strictly by target hash (a
        //     hash-carrying candidate whose hash equals the receiver's); NEVER a
        //     name fallback (a differently-hashed same-name impl is a different
        //     declaration — the hash veto).
        //   - receiver is hashless (builtin/scalar) → its reserved NAME is its
        //     only identity, so match by target NAME (displayName == receiver
        //     name). A null-hash candidate is a builtin-/scalar-targeted impl
        //     (e.g. `Seq`, `Int`) identified by that name — it is NOT a
        //     blanket/any impl.
        val matches: List<ResolutionCandidate> = when (key) {
            // BUG 1: take the FIRST hash that matches anything, not the union —
            // a specific `impl Result PyForeign IErr` and a generic
            // `impl Result 'a 'e` both match if the constructor hash is consulted
            // alongside the applied one, and the union would read as ambiguity.
            is ReceiverKey.ByHash -> key.hashes.firstNotNullOfOrNull { h ->
                inherent.filter { (it.targetTypeIdentity as? TypeIdentity.Declared)?.hash == h }
                    .takeIf { it.isNotEmpty() }
            } ?: emptyList()
            is ReceiverKey.ByName -> {
                // ALLOWED: ReceiverKey.ByName is produced by receiverKey() ONLY when
                // declaredHashOf(receiver) == null — a hashless builtin/scalar whose
                // reserved name IS its identity. A hash-carrying receiver becomes
                // ByHash and never reaches this arm, so the name never overrides a hash.
                inherent.filter { it.displayName == key.name }
            }
        }
        if (matches.isEmpty()) return null
        // Several impls of one constructor (`impl Box Int`, `impl Box Str`): the
        // receiver's structure decides, exactly as pickByReceiverHash does for the
        // other ladders — all of them must agree.
        val narrowed = if (matches.map { it.opHash }.distinct().size >= 2) {
            com.mezze.language.typechecker.util.pickByStructure(solvedReceiver, matches) { it.targetType }
        } else null
        if (narrowed != null) return narrowed
        val distinctOps = matches.map { it.opHash }.distinct()
        if (distinctOps.size >= 2) {
            throw DispatchAmbiguityException(
                bucket.methodName,
                solvedReceiverDisplay(solvedReceiver),
                matches.mapNotNull { it.displayName }.distinct(),
                bucket.loc,
            )
        }
        // One winning op (possibly recorded under several candidate rows): the
        // ladder picks the first — here it is unambiguous by op hash.
        return matches.first()
    }

    private fun solvedReceiverIdentity(solvedReceiver: MezzeType): TypeIdentity? = when (solvedReceiver) {
        is MezzeType.VariantType -> solvedReceiver.identity.takeIf { it != TypeIdentity.Anonymous }
        is MezzeType.RecordType -> solvedReceiver.identity.takeIf { it != TypeIdentity.Anonymous }
        is MezzeType.TypeApplication -> solvedReceiver.identity.takeIf { it != TypeIdentity.Anonymous }
        else -> receiverTypeName(solvedReceiver)?.let { TypeIdentity.Builtin(it) }
    }

    /** Any identity for the receiver, including [TypeIdentity.Anonymous] as a last
     *  resort — used for ability ops whose impl selection happens separately. */
    private fun solvedReceiverAnyIdentity(solvedReceiver: MezzeType): TypeIdentity =
        solvedReceiverIdentity(solvedReceiver) ?: TypeIdentity.Anonymous

    private fun solvedReceiverDisplay(solvedReceiver: MezzeType): String =
        receiverTypeName(solvedReceiver) ?: prettyPrintType(solvedReceiver)

    /**
     * PARITY ORACLE (invariants-only): compare the S2.2 [resolution] against the
     * live ladder's recorded [ladderChoice] for this call site. A mismatch in the
     * chosen opHash (the dispatch-defining quantity) is a real divergence and a
     * hard finding going into S2.3. Missing ladder info (the ladder fell through
     * to a structural field access, recording nothing) is NOT a divergence — S2.2
     * only records a Resolution when it too resolved a concrete op.
     */
    private fun crossCheckAgainstLadder(
        bucket: MethodResolveBucket,
        solvedReceiver: MezzeType,
        resolution: Resolution,
        ladderChoice: ConstraintResult.MethodCallInfo?,
    ) {
        if (ladderChoice == null) return
        val ladderOpHash: Hash? = when {
            ladderChoice.isInherentMethod -> resolveLadderInherentOpHash(ladderChoice, solvedReceiver)
            else -> null // ability/builtin: opHash comparison handled below via abilityName
        }
        if (resolution.kind == ResolutionKind.Inherent && ladderChoice.isInherentMethod) {
            if (ladderOpHash != null && ladderOpHash != resolution.opHash) {
                throw DispatchParityException(
                    "[S2.2 parity] '${bucket.methodName}' on '${solvedReceiverDisplay(solvedReceiver)}' " +
                        "at ${bucket.loc}: S2.2 chose inherent impl ${resolution.opHash.hex.take(12)} " +
                        "but ladder chose ${ladderOpHash.hex.take(12)}."
                )
            }
        }
        // ABILITY parity, which used to be skipped here on the grounds that the
        // ladder records a NAME while a Resolution records a hash. The two are
        // comparable after all: the winning candidate in the bucket carries both,
        // so the resolver's hash maps back to the name the ladder would have
        // written, and no registry lookup is needed to line them up.
        //
        // Asserted now because it was MEASURED first, unconditionally, across both
        // suites: 136,643 ability call sites, 136,643 agreements, ZERO
        // disagreements. That is what makes the ladder's pick safe to treat as
        // redundant rather than merely believed to be — and it is why this can be
        // an invariant instead of a hope. A divergence here means the two rules
        // have drifted apart and one of them is now deciding dispatch differently
        // from the other, which is exactly the state S2.2 exists to end.
        if (resolution.kind == ResolutionKind.Ability && !ladderChoice.isInherentMethod) {
            val resolverChoice = bucket.candidates
                .firstOrNull { it.kind == ResolutionKind.Ability && it.opHash == resolution.opHash }
                ?.displayName
            if (resolverChoice != null && ladderChoice.abilityName != null &&
                resolverChoice != ladderChoice.abilityName
            ) {
                throw DispatchParityException(
                    "[S2.2 parity] '${bucket.methodName}' on " +
                        "'${solvedReceiverDisplay(solvedReceiver)}' at ${bucket.loc}: S2.2 chose " +
                        "ability '$resolverChoice' but the ladder chose " +
                        "'${ladderChoice.abilityName}'."
                )
            }
        }
    }

    /** The ladder's chosen inherent op hash: the resolved impl content hash. For a
     *  deferred/homogeneous call it re-picks the candidate by the SOLVED receiver
     *  (mirroring solveInherentMethodResolve / TypedAstBuilder), else the directly
     *  recorded [ConstraintResult.MethodCallInfo.implContentHash]. */
    private fun resolveLadderInherentOpHash(
        info: ConstraintResult.MethodCallInfo,
        solvedReceiver: MezzeType,
    ): Hash? {
        if (info.candidateImpls.isNotEmpty()) {
            // BUG 1: applied hash before constructor hash, matching the other
            // three ladders — all four must agree or the S2.3 invariant fires.
            val picked = com.mezze.language.typechecker.util
                .pickByReceiverHash(solvedReceiver, info.candidateImpls, { it.targetType }) { it.targetTypeHash }
                ?: run {
                    // Names in SPECIFICITY order: `Result_Response_HttpError`
                    // wins over `Result` when the receiver's args are concrete.
                    // Same fallback [legacyInherentImplHash] uses; both must
                    // agree so the S2.3 invariant assertion passes.
                    for (name in com.mezze.language.typechecker.util.receiverTypeNames(solvedReceiver)) {
                        info.candidateImpls.firstOrNull { it.targetTypeName == name }
                            ?.let { return@run it }
                    }
                    null
                }
            return picked?.implContentHash ?: info.implContentHash
        }
        return info.implContentHash
    }
}
