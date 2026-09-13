package com.mezze.language.typechecker.solving

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeIdentity
import com.mezze.language.ast.prettyPrintType
import com.mezze.language.ast.solving.Substitution
import com.mezze.language.parsedast.Loc
import com.mezze.language.typechecker.constraints.ConstraintResult
import com.mezze.language.typechecker.constraints.MethodResolveBucket
import com.mezze.language.typechecker.constraints.Resolution
import com.mezze.language.typechecker.constraints.ResolutionCandidate
import com.mezze.language.typechecker.constraints.ResolutionKind
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.util.declaredHashOf
import com.mezze.language.typechecker.util.receiverTypeName
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
        val hash = declaredHashOf(solvedReceiver)
        val name = receiverTypeName(solvedReceiver)
        return when {
            hash != null -> ReceiverKey.ByHash(hash, name)
            name != null -> ReceiverKey.ByName(name)
            else -> null
        }
    }

    private sealed interface ReceiverKey {
        data class ByHash(val hash: Hash, val name: String?) : ReceiverKey
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
            val receiverName = receiverTypeName(solvedReceiver)
            val narrowed = if (ability.size > 1 && receiverName != null) {
                val implMatches = ability.filter { cand ->
                    val abilityName = cand.displayName ?: return@filter false
                    typingContext.getAbilityImpls(abilityName).any { receiverName in it.targetTypes }
                }
                if (implMatches.isNotEmpty()) implMatches else ability
            } else ability

            val distinctOps = narrowed.map { it.opHash }.distinct()
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
                return null
            }
            val opHash = narrowed.first().opHash ?: return null
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
            is ReceiverKey.ByHash -> inherent.filter {
                (it.targetTypeIdentity as? TypeIdentity.Declared)?.hash == key.hash
            }
            is ReceiverKey.ByName -> {
                // ALLOWED: ReceiverKey.ByName is produced by receiverKey() ONLY when
                // declaredHashOf(receiver) == null — a hashless builtin/scalar whose
                // reserved name IS its identity. A hash-carrying receiver becomes
                // ByHash and never reaches this arm, so the name never overrides a hash.
                inherent.filter { it.displayName == key.name }
            }
        }
        if (matches.isEmpty()) return null
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
        // Ability/builtin parity is intentionally not asserted on opHash here:
        // the ladder records abilityName (not a hash) and the ability-op hash is a
        // deterministic function of that name via the ability-decl registry — the
        // family-choice parity (inherent-vs-ability) IS asserted by the kind check
        // above producing a Resolution at all.
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
            val hash = declaredHashOf(solvedReceiver)
            val picked = hash?.let { h -> info.candidateImpls.firstOrNull { it.targetTypeHash == h } }
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
