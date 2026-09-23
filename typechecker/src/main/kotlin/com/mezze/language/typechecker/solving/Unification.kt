package com.mezze.language.typechecker.solving

import com.mezze.language.ast.*
import com.mezze.language.ast.solving.Substitution
import com.mezze.language.ast.solving.occursCheck
import com.mezze.language.parsedast.diagnostics.ErrorCode
import com.mezze.language.typechecker.WellKnownHashConstants
import com.mezze.language.typechecker.abilities.BuiltInAbilities
import com.mezze.language.typechecker.context.DeclarationRegistry
import com.mezze.language.typechecker.debug.DebugLogger
import com.mezze.language.typechecker.debug.DebugLogger.Category
import com.mezze.language.typechecker.util.StringDistance
import com.mezze.language.typechecker.util.TypeAnnotationConverter
import com.mezze.language.parsedast.*
import com.mezze.language.typedast.*
import com.mezze.language.typechecker.ModuleResolver
import com.mezze.language.typechecker.context.ContextBuilder

private val unificationCache = ThreadLocal.withInitial {
    HashMap<Pair<MezzeType, MezzeType>, UnificationResult>()
}

// Shared ContextBuilder for the type-application expand functions below. These
// call only convertParsedTypeAnnotation, which resets typeVarCache/sharedRowVar
// on each call and never touches the build()-populated decl maps (all empty
// here), so a single instance is safe to reuse and avoids allocating a fresh
// ContextBuilder on every expansion. Thread-local because ContextBuilder is not
// thread-safe and unification runs concurrently.
private val expandContextBuilder = ThreadLocal.withInitial { ContextBuilder() }

/**
 * Describe a variant type for a user-facing error message. Gives both the
 * alias name (when the variant carries one — anchors the error to the decl
 * the user reads in their code) AND the arm set (concrete info to reason
 * about). Anonymous variants render as their arm set alone.
 */
internal fun describeVariant(v: MezzeType.VariantType): String {
    val arms = v.constructors.keys.sorted().joinToString(", ")
    val armsStr = if (v.rowVar != null) "| $arms, ... |" else "| $arms |"
    val name = v.identity.displayNameOrNull()
    return if (name != null) "$armsStr ($name)" else armsStr
}

/**
 * "Type mismatch: A is not compatible with B" — rendered with ONE shared
 * type-variable naming so a variable shared by both sides prints as the same
 * name, and two distinct variables never collide on one name (see
 * [prettyPrintTypePair]).
 */
private fun incompatibleMessage(actual: MezzeType, expected: MezzeType): String {
    val (a, e) = prettyPrintTypePair(actual, expected)
    return "Type mismatch: $a is not compatible with $e"
}

/**
 * "Type mismatch: expected E, but got A" — same shared-naming guarantee as
 * [incompatibleMessage]. The two sides are printed against one variable map so
 * "expected 'a but got 'a" always denotes the same variable.
 */
private fun expectedButGotMessage(expected: MezzeType, actual: MezzeType): String {
    val (e, a) = prettyPrintTypePair(expected, actual)
    return "Type mismatch: expected $e, but got $a"
}

/**
 * The verdict of comparing two nominal [TypeIdentity]s. Identity alone can give
 * one of three answers; what a caller does with [UNDETERMINED] depends on whether
 * the type is a TRANSPARENT alias (fall through to structural) or an OPAQUE wall
 * (reject the mismatch). See [identityVerdict].
 */
internal enum class IdentityVerdict { SAME, DISTINCT, UNDETERMINED }

/**
 * Compare two nominal identities. This is the single, exhaustive identity `when`
 * that replaces the scattered `if (hash != null && hash != null) … else name …`
 * guesses — no null ambiguity, and a name string NEVER decides here.
 *
 *  - both [TypeIdentity.Declared] → SAME iff hashes equal, else DISTINCT
 *  - both [TypeIdentity.Builtin]  → SAME iff names equal, else DISTINCT
 *  - both [TypeIdentity.Anonymous] → SAME (two anonymous shapes are decided purely
 *    structurally by the caller; identity imposes no veto)
 *  - any mixed pair (Declared/Builtin/Anonymous in any combination) → UNDETERMINED:
 *    identity can't prove sameness OR distinctness. The caller decides:
 *      · transparent types (records, non-walled variant aliases) → structural unify
 *      · opaque walls (newtype, ascriptionWall variants) → reject
 *
 * (Stage B derives `.identity` from the legacy name/hash, so this reproduces
 * exactly today's behaviour; Stage C/E make it authoritative.)
 */
internal fun identityVerdict(id1: TypeIdentity, id2: TypeIdentity): IdentityVerdict = when {
    id1 is TypeIdentity.Declared && id2 is TypeIdentity.Declared ->
        if (id1.hash == id2.hash) IdentityVerdict.SAME else IdentityVerdict.DISTINCT
    id1 is TypeIdentity.Builtin && id2 is TypeIdentity.Builtin -> {
        // ALLOWED: a Builtin's reserved name IS its identity (List/Map/Bool have no
        // content hash). Comparing the reserved kind name is the identity decision,
        // not a name-fallback. See project no_name_string_dispatch_ever.
        if (id1.name == id2.name) IdentityVerdict.SAME else IdentityVerdict.DISTINCT
    }
    id1 is TypeIdentity.Anonymous && id2 is TypeIdentity.Anonymous ->
        IdentityVerdict.SAME
    else -> IdentityVerdict.UNDETERMINED
}

/**
 * True iff [type] is an OPAQUE nominal wall: unification must REJECT on an identity
 * mismatch instead of falling through to structural comparison. This is the single
 * place that decides wall-ness for the whole unifier.
 *
 * Wall-ness is keyed on NODE-KIND + [MezzeType.VariantType.ascriptionWall] ONLY —
 * NEVER on `.identity` shape. A `Declared` record is still TRANSPARENT: it may unify
 * structurally with an `Anonymous` bare-record literal (that is what makes
 * `type Wrapper = {n:Int}; f({n=5})` type-check). Only two kinds are opaque:
 *
 *  - [MezzeType.NewtypeType] — nominal, module-opaque by node kind. A newtype never
 *    unifies with its bare underlying; it rejects on any declaration mismatch.
 *  - [MezzeType.VariantType] with `ascriptionWall == true` — an author-written /
 *    inferred hard ascription. Everything else (records, non-walled variant aliases)
 *    is transparent and falls through to structural unify on an UNDETERMINED verdict.
 *
 * `ascriptionWall` stays a SEPARATE field consulted here — it is not part of
 * [TypeIdentity]. The name-in-scope nominal barrier (#148) is a DISTINCT mechanism
 * layered on top by [isWalledVariant]; it is deliberately NOT folded in here, so this
 * predicate never touches the registry.
 */
internal fun isOpaqueWall(type: MezzeType): Boolean = when {
    type is MezzeType.NewtypeType -> true
    type is MezzeType.VariantType -> type.ascriptionWall
    else -> false
}

/**
 * True iff two identities are the SAME *concrete declaration* — both [TypeIdentity.Declared]
 * with equal hash, or both [TypeIdentity.Builtin] with equal name. Unlike [identityVerdict]'s
 * SAME verdict this does NOT include two [TypeIdentity.Anonymous] (two anonymous shapes are
 * NOT the same nominal declaration — their sameness is decided structurally, not by identity).
 * Used where identity is an ADDITIVE match (e.g. a rename-safe alias whose name differs but
 * declaration is the same); it must never turn two distinct-named anonymous applications into
 * a match.
 */
/**
 * One well-known type met in BOTH of its representations: a [TypeIdentity.Declared]
 * on one side and a [TypeIdentity.Builtin] on the other, under the same name.
 *
 * [identityVerdict] calls a mixed pair UNDETERMINED and refuses to decide, which is
 * right in general — a name is not an identity, and matching on one is the pattern
 * this codebase otherwise forbids. This is the one place that has to decide anyway,
 * and only because `Bool`, `Option` and `Result` genuinely exist twice: as a builtin
 * and as a stdlib declaration. Two walls that are the same type must unify even when
 * they arrive wearing different identity kinds.
 *
 * MEASURED across the typechecker suite: 2636 pairs reach this and answer SAME where
 * [sameDeclaredIdentity] alone would answer no — 1767 `Bool`, 603 `Result`, 266
 * `Option`, and nothing else. That is the whole population. So this is not a general
 * name-based identity rule that happens to be narrow; it is exactly the dual-
 * representation problem, and it disappears the day those three types have one
 * representation each. Whoever unifies them should expect this function to go with
 * them — and should expect 2636 unifications to route through the change.
 */
private fun sameBuiltinSeenBothWays(id1: TypeIdentity, id2: TypeIdentity): Boolean {
    // Same-kind pairs are [sameDeclaredIdentity]'s business, not this one's.
    if (id1 is TypeIdentity.Declared == id2 is TypeIdentity.Declared) return false
    val n1 = id1.displayNameOrNull() ?: return false
    return n1 == id2.displayNameOrNull()
}

internal fun sameDeclaredIdentity(id1: TypeIdentity, id2: TypeIdentity): Boolean = when {
    id1 is TypeIdentity.Declared && id2 is TypeIdentity.Declared -> id1.hash == id2.hash
    id1 is TypeIdentity.Builtin && id2 is TypeIdentity.Builtin -> id1.name == id2.name
    else -> false
}

fun clearUnificationCache() {
    unificationCache.get().clear()
}

sealed class UnificationResult {
    data class Success(val substitution: Substitution) : UnificationResult()
    data class Failure(
        val message: String,
        val type1: MezzeType? = null,
        val type2: MezzeType? = null,
        /**
         * WHICH failure this is. Defaults to a plain mismatch because that is what
         * unification failing means unless the site says otherwise; the occurs
         * check is the one place that says otherwise today.
         *
         * Callers used to recover this by testing the rendered message
         * (`msg.startsWith("Infinite type: ")`), so rewording that one diagnostic
         * would have silently downgraded it to T0201.
         */
        val code: ErrorCode = ErrorCode.T0201_TYPE_MISMATCH,
    ) : UnificationResult()

    inline fun getOrElse(onFailure: (Failure) -> Nothing): Substitution = when (this) {
        is Success -> substitution
        is Failure -> onFailure(this)
    }
}

fun unify(type1: MezzeType, type2: MezzeType, registry: DeclarationRegistry = DeclarationRegistry.EMPTY): UnificationResult {
    if (type1 == type2) return UnificationResult.Success(Substitution.empty)

    val cacheKey = Pair(type1, type2)
    unificationCache.get()[cacheKey]?.let { return it }

    val result = unifyImpl(type1, type2, registry)
    unificationCache.get()[cacheKey] = result
    return result
}

private fun unifyImpl(type1: MezzeType, type2: MezzeType, registry: DeclarationRegistry): UnificationResult {
    if (type1 is MezzeType.BoolType && type2 is MezzeType.VariantType) {
        if (BuiltInAbilities.isBoolVariant(type2)) {
            return UnificationResult.Success(Substitution.empty)
        }
    }
    if (type1 is MezzeType.VariantType && type2 is MezzeType.BoolType) {
        if (BuiltInAbilities.isBoolVariant(type1)) {
            return UnificationResult.Success(Substitution.empty)
        }
    }
    when {
        type1 is MezzeType.VariantType && type2 is MezzeType.TypeApplication -> {
            val expanded = expandTypeApplicationToVariant(type2, registry)
            return if (expanded != null) {
                unify(type1, expanded, registry)
            } else {
                UnificationResult.Failure(
                    message = incompatibleMessage(type1, type2),
                    type1 = type1,
                    type2 = type2
                )
            }
        }
        type1 is MezzeType.TypeApplication && type2 is MezzeType.VariantType -> {
            val expanded = expandTypeApplicationToVariant(type1, registry)
            return if (expanded != null) {
                unify(expanded, type2, registry)
            } else {
                UnificationResult.Failure(
                    message = incompatibleMessage(type1, type2),
                    type1 = type1,
                    type2 = type2
                )
            }
        }
        type1 is MezzeType.RecordType && type2 is MezzeType.TypeApplication -> {
            when (val expansion = expandTypeApplication(type2, registry)) {
                is TypeExpansionResult.Record -> {
                    return unify(type1, expansion.recordType, registry)
                }
                is TypeExpansionResult.Newtype -> {
                    if (expansion.payloadType != null && expansion.variantType.rowVar == null) {
                        return unify(type1, expansion.payloadType, registry)
                    }
                }
                is TypeExpansionResult.Variant, is TypeExpansionResult.NotFound -> {
                }
            }
            return UnificationResult.Failure(
                message = incompatibleMessage(type1, type2),
                type1 = type1,
                type2 = type2
            )
        }
        type1 is MezzeType.TypeApplication && type2 is MezzeType.RecordType -> {
            when (val expansion = expandTypeApplication(type1, registry)) {
                is TypeExpansionResult.Record -> {
                    return unify(expansion.recordType, type2, registry)
                }
                is TypeExpansionResult.Newtype -> {
                    if (expansion.payloadType != null && expansion.variantType.rowVar == null) {
                        return unify(expansion.payloadType, type2, registry)
                    }
                }
                is TypeExpansionResult.Variant, is TypeExpansionResult.NotFound -> {
                }
            }
            return UnificationResult.Failure(
                message = incompatibleMessage(type1, type2),
                type1 = type1,
                type2 = type2
            )
        }
        type1 is MezzeType.RecordType && type2 is MezzeType.VariantType -> {
            if (type2.constructors.size == 1 && type2.rowVar == null &&
                !isOpaqueWall(type2) && !isWalledVariant(type2, registry)) {
                val (_, payloadType) = type2.constructors.entries.first()
                if (payloadType != null) {
                    return unify(type1, payloadType, registry)
                }
            }
            return UnificationResult.Failure(
                message = expectedButGotMessage(type2, type1),
                type1 = type1,
                type2 = type2
            )
        }
        type1 is MezzeType.VariantType && type2 is MezzeType.RecordType -> {
            if (type1.constructors.size == 1 && type1.rowVar == null &&
                !isOpaqueWall(type1) && !isWalledVariant(type1, registry)) {
                val (_, payloadType) = type1.constructors.entries.first()
                if (payloadType != null) {
                    return unify(payloadType, type2, registry)
                }
            }
            return UnificationResult.Failure(
                message = expectedButGotMessage(type2, type1),
                type1 = type1,
                type2 = type2
            )
        }
    }

    return when {
        type1 is MezzeType.VarType -> unifyVar(type1.variable, type2)
        type2 is MezzeType.VarType -> unifyVar(type2.variable, type1)

        type1 is MezzeType.FunctionType && type2 is MezzeType.FunctionType ->
            unifyFunctions(type1, type2, registry)

        type1 is MezzeType.RecordType && type2 is MezzeType.RecordType ->
            unifyRecords(type1, type2, registry)

        type1 is MezzeType.VariantType && type2 is MezzeType.VariantType ->
            unifyVariants(type1, type2, registry)

        type1 is MezzeType.TypeApplication && type2 is MezzeType.TypeApplication ->
            unifyTypeApplications(type1, type2, registry)



        type1 is MezzeType.EffectRow && type2 is MezzeType.EffectRow ->
            unifyEffectRows(type1, type2, registry)

        type1 is MezzeType.IntType && type2 is MezzeType.IntType -> UnificationResult.Success(Substitution.empty)
        type1 is MezzeType.FloatType && type2 is MezzeType.FloatType -> UnificationResult.Success(Substitution.empty)
        type1 is MezzeType.StringType && type2 is MezzeType.StringType -> UnificationResult.Success(Substitution.empty)
        type1 is MezzeType.BoolType && type2 is MezzeType.BoolType -> UnificationResult.Success(Substitution.empty)
        type1 is MezzeType.FracType && type2 is MezzeType.FracType -> UnificationResult.Success(Substitution.empty)

        type1 is MezzeType.NewtypeType && type2 is MezzeType.NewtypeType -> {
            // #278/F0: newtypes are NOMINAL, module-opaque — same type only if same
            // DECLARATION. A newtype is an OPAQUE wall (isOpaqueWall(newtype) == true by
            // node kind): it rejects on any declaration mismatch and NEVER falls through
            // to structural, so this branch always ends in unify(wrapped) or Failure —
            // there is no transparent path.
            // #400: identity is AUTHORITATIVE. The verdict mirrors the
            // TypeApplication/VariantType hash-veto (identityVerdict):
            //  - DISTINCT (both Declared, hashes differ) → two modules' same-named
            //    newtypes, MUST reject even when the names match. This is the
            //    soundness fix — previously the code fell to bare `name ==` here and
            //    laundered one module's `Id` where another's was required.
            //  - SAME (equal Declared hash, or a rename-safe equal-hash alias) → the
            //    same declaration, unify the wrapped types.
            //  - UNDETERMINED (a hashless/legacy Anonymous side — builtins, pre-hash
            //    construction) → the name is the ONLY identity available, so fall back
            //    to bare name. This is NOT a name-fallback over an available hash
            //    decision (the DISTINCT/SAME arms already decided those); it only ever
            //    fires when at least one side has no declaration hash to key on.
            val verdict = identityVerdict(type1.identity, type2.identity)
            val sameDeclaration = when (verdict) {
                IdentityVerdict.DISTINCT -> false
                // SAME/UNDETERMINED never OVERRIDE a DISTINCT veto (handled above).
                // A NAME match unifies; the SAME concrete declaration (equal Declared
                // hash / Builtin name) ALSO unifies even on differing names (rename-safe
                // alias). Using sameDeclaredIdentity — NOT the bare SAME verdict — keeps
                // two Anonymous newtypes with DIFFERENT names distinct (name== is false),
                // exactly as before. Mirrors unifyTypeApplications' nameMatched||declMatched.
                IdentityVerdict.SAME, IdentityVerdict.UNDETERMINED ->
                    type1.name == type2.name || sameDeclaredIdentity(type1.identity, type2.identity)
            }
            if (sameDeclaration) {
                unify(type1.wrappedType, type2.wrappedType, registry)
            } else {
                val (n1, n2) = prettyPrintTypePair(type1, type2)
                UnificationResult.Failure(
                    message = "Type mismatch: $n1 is not the same type as $n2. " +
                        "These are distinct newtypes even if their wrapped types match — " +
                        "unwrap one or convert explicitly.",
                    type1 = type1,
                    type2 = type2
                )
            }
        }
        // TypeApplication vs NewtypeType: an annotation-side ParsedNamedType for a
        // newtype decl surfaces as a nominal TypeApplication (name+identity, no
        // payload), while a value-producing expression (a literal `0n`, a wrapping
        // call) surfaces as a NewtypeType carrying the wrapped payload. Same
        // declaration ⇒ same type — reconcile by nominal identity (hash first,
        // name as fallback). No payload re-unification: the decl's identity fixes
        // the payload and expanding the TypeApp side produces a synthetic `{val: T}`
        // record that isn't the raw wrapped type.
        type1 is MezzeType.TypeApplication && type2 is MezzeType.NewtypeType -> {
            // #400/L-21: decide by identityVerdict, NOT the bare `h1==h2 else name==`
            // guess. DISTINCT (both Declared, hashes differ) MUST reject — otherwise a
            // hashless TypeApp for one module's `Id` laundered into another module's
            // Declared newtype `Id` by name alone even though that side HAD a hash.
            // Name only decides when the verdict is UNDETERMINED (a hashless/Anonymous
            // side — the name is then the sole identity available). Mirrors the
            // Newtype/Newtype arm above and unifyTypeApplications.
            val verdict = identityVerdict(type1.identity, type2.identity)
            val sameDeclaration = when (verdict) {
                IdentityVerdict.DISTINCT -> false
                IdentityVerdict.SAME, IdentityVerdict.UNDETERMINED ->
                    type1.constructor == type2.name || sameDeclaredIdentity(type1.identity, type2.identity)
            }
            if (sameDeclaration) UnificationResult.Success(Substitution.empty)
            else UnificationResult.Failure(
                message = "Type mismatch: ${type1.constructor} is not the same type as ${type2.name}",
                type1 = type1,
                type2 = type2
            )
        }
        type1 is MezzeType.NewtypeType && type2 is MezzeType.TypeApplication -> {
            // Symmetric to the arm above (L-21): identityVerdict decides, DISTINCT
            // rejects the cross-module name launder, name only for UNDETERMINED.
            val verdict = identityVerdict(type1.identity, type2.identity)
            val sameDeclaration = when (verdict) {
                IdentityVerdict.DISTINCT -> false
                IdentityVerdict.SAME, IdentityVerdict.UNDETERMINED ->
                    type1.name == type2.constructor || sameDeclaredIdentity(type1.identity, type2.identity)
            }
            if (sameDeclaration) UnificationResult.Success(Substitution.empty)
            else UnificationResult.Failure(
                message = "Type mismatch: ${type1.name} is not the same type as ${type2.constructor}",
                type1 = type1,
                type2 = type2
            )
        }
        type1 is MezzeType.BytesType && type2 is MezzeType.BytesType -> UnificationResult.Success(Substitution.empty)
        type1 is MezzeType.CharType && type2 is MezzeType.CharType -> UnificationResult.Success(Substitution.empty)

        else -> {
            UnificationResult.Failure(
                message = expectedButGotMessage(type2, type1),
                type1 = type1,
                type2 = type2
            )
        }
    }
}

private fun unifyVar(typeVar: TypeVar, type: MezzeType): UnificationResult {
    if (type is MezzeType.VarType && type.variable == typeVar) {
        return UnificationResult.Success(Substitution.empty)
    }
    if (occursCheck(typeVar, type)) {
        return UnificationResult.Failure(
            message = "Infinite type: type variable ${prettyPrintType(MezzeType.VarType(typeVar))} occurs inside ${prettyPrintType(type)}. " +
                    "This would create an infinitely-nested type — add a type annotation to break the cycle (often a recursive let-binding).",
            type1 = MezzeType.VarType(typeVar),
            type2 = type,
            code = ErrorCode.T0207_INFINITE_TYPE,
        )
    }
    return UnificationResult.Success(Substitution.single(typeVar, type))
}

/**
 * #11 directional field requirement: every field the DEMAND record declares must
 * be present in the VALUE record. Extras on the VALUE are fine (row polymorphism);
 * a field the VALUE has but the DEMAND lacks is not our concern here. Recurses
 * into record-typed fields shared by both. Returns a Failure iff the value is a
 * CONCRETE record that lacks a field the demand requires; returns null (OK) when
 * the value is still a bare var (inference not finished — leave it to the solver)
 * or when all demanded fields are present. Only called from unifyFunctions, where
 * arg-vs-param direction is known — the symmetric unifyRecords is unchanged.
 */
private fun directionalParamFieldCheck(value: MezzeType, demand: MezzeType): UnificationResult.Failure? {
    if (demand !is MezzeType.RecordType || value !is MezzeType.RecordType) return null
    for ((fieldName, demandFieldType) in demand.fields) {
        val valueFieldType = value.fields[fieldName]
        if (valueFieldType == null) {
            // The demand requires `fieldName`; the value's concrete field set lacks
            // it.  Sound to accept ONLY if the value's row var could still supply it
            // — but a launder is exactly "the value is open yet genuinely has no
            // such field", which is indistinguishable from a not-yet-inferred open
            // value.  We reject when the value is a record with a rowVar that is NOT
            // a fresh inference placeholder for this field, i.e. always for a
            // concrete open record: an explicit `{ x, ... }` value declares its
            // fields and `...` admits only EXTRAS, it does not conjure `y`.
            return UnificationResult.Failure(
                message = "record fields don't match: missing '$fieldName' (required by the expected type)",
                type1 = value,
                type2 = demand,
            )
        }
        // Recurse into nested records (both concrete) so `{ r: { y, ... } }` vs
        // `{ r: { x, ... } }` is caught at the inner level too.
        directionalParamFieldCheck(value = valueFieldType, demand = demandFieldType)?.let { return it }
    }
    return null
}

private fun unifyFunctions(fn1: MezzeType.FunctionType, fn2: MezzeType.FunctionType, registry: DeclarationRegistry): UnificationResult {
    val paramSubst = unify(fn1.paramType, fn2.paramType, registry).getOrElse { return it }
    // #11 directional param check. By the call-argument convention documented
    // for the effect rows below — Equality(left = arg, right = param) →
    // unifyFunctions(fn1 = arg/value, fn2 = param/demand) — fn2.paramType is the
    // DEMAND and fn1.paramType is the VALUE. Symmetric unifyRecords (above) can't
    // reject a field the DEMAND requires but the VALUE lacks when both are open:
    // it absorbs it into the value's row var (the launder — an open value
    // `{ x, ... }` wrongly satisfies a demand `{ y, ... }`). Enforce it here with
    // the direction this site has: every concrete field of the demand must be
    // present in the value (recursively for nested records). The value's extra
    // fields remain fine (row polymorphism), and a field the value has that the
    // demand lacks is unaffected — only demand→value absence is a launder.
    val __dfcValue = paramSubst.apply(fn1.paramType)
    val __dfcDemand = paramSubst.apply(fn2.paramType)
    directionalParamFieldCheck(
        value = __dfcValue,
        demand = __dfcDemand,
    )?.let { return it }
    val return1 = paramSubst.apply(fn1.returnType)
    val return2 = paramSubst.apply(fn2.returnType)
    val returnSubst = unify(return1, return2, registry).getOrElse { return it }
    val effects1 = fn1.effects
    val effects2 = fn2.effects

    val isPure1 = effects1 == null || (effects1.effects.isEmpty() && effects1.effectInstances.isEmpty() && effects1.rowVar == null)
    val isPure2 = effects2 == null || (effects2.effects.isEmpty() && effects2.effectInstances.isEmpty() && effects2.rowVar == null)
    val isPolymorphic1 = effects1 != null && effects1.effects.isEmpty() && effects1.effectInstances.isEmpty() && effects1.rowVar != null
    val isPolymorphic2 = effects2 != null && effects2.effects.isEmpty() && effects2.effectInstances.isEmpty() && effects2.rowVar != null
    val hasConcrete1 = effects1 != null && (effects1.effects.isNotEmpty() || effects1.effectInstances.isNotEmpty())
    val hasConcrete2 = effects2 != null && (effects2.effects.isNotEmpty() || effects2.effectInstances.isNotEmpty())

    // A declared callback row (has caughtByAncestor) must always be checked
    // — even against a pure or polymorphic actual side — so the strict rule
    // "declared effects must be performed" can fire. Route those through
    // unifyEffectRows using an empty EffectRow for the pure side.
    val declaredCallbackPresent = (effects1?.caughtByAncestor?.isNotEmpty() == true) ||
        (effects2?.caughtByAncestor?.isNotEmpty() == true)

    val effectSubst = when {
        isPure1 && isPure2 -> Substitution.empty

        isPure1 && isPolymorphic2 -> Substitution.empty
        isPolymorphic1 && isPure2 -> Substitution.empty

        // Pure vs. concrete effects. Route through unifyEffectRows so the
        // directional extras logic applies (concrete extras on the ACTUAL
        // side, against a CLOSED formal row, are rejected; extras on the
        // FORMAL/declared side against a pure actual are contravariantly
        // accepted). For a call argument the convention is
        // Equality(left = arg, right = param) → unifyFunctions(fn1 = arg,
        // fn2 = param), so:
        //   isPure1 && hasConcrete2  = pure ACTUAL into an effectful FORMAL
        //     → widening; unifyEffectRows sees the extras as eff2Extra
        //       against a closed eff1 and accepts (contravariant). Sound.
        //   isPure2 && hasConcrete1  = effectful ACTUAL into a pure FORMAL
        //     → the launder (#215); unifyEffectRows sees the extras as
        //       eff1Extra against a closed eff2 and REJECTS. Previously this
        //       returned Substitution.empty, silently swallowing the effect
        //       (e.g. `apply_pure { f = leaky }` where leaky performs Log but
        //       apply_pure's callback param is pure) — the module then
        //       type-checked as pure while performing Log at runtime.
        isPure1 && hasConcrete2 ->
            unifyEffectRows(effects1 ?: MezzeType.EffectRow(emptySet()), effects2!!, registry).getOrElse { return it }
        isPure2 && hasConcrete1 ->
            unifyEffectRows(effects1!!, effects2 ?: MezzeType.EffectRow(emptySet()), registry).getOrElse { return it }

        // Both polymorphic-only or polymorphic-vs-concrete: keep the row
        // var open. Eagerly binding here (via unifyEffectRows) would
        // fix a fresh row var to the current call site's effects, which
        // then blocks other call sites with a different effect set.
        isPolymorphic1 && isPolymorphic2 -> Substitution.empty
        isPolymorphic1 && hasConcrete2 -> Substitution.empty
        hasConcrete1 && isPolymorphic2 -> Substitution.empty

        // #274-B: both sides have concrete effects — propagate through
        // row vars so `<Foo, ...>` can absorb the body's extra effects.
        else -> unifyEffectRows(effects1!!, effects2!!, registry).getOrElse { return it }
    }

    // Unify ability-constraint ASSOCIATED TYPES, mirroring the effect-row path
    // (unifyEffectRows). Without this, `with Producer 'a having 'out = Int` in an
    // annotation is never checked against the body's resolved 'out — the exact
    // asymmetry that let effect `having` be sound while ability `having` was not.
    // Only bindings on constraints shared by (ability name, hash) are unified,
    // key-by-key; a mismatch is a hard failure (naming the key).
    fun key(c: MezzeType.FunctionAbilityConstraint) = c.abilityName to c.abilityHash
    val byKey1 = fn1.abilityConstraints.associateBy { key(it) }
    val byKey2 = fn2.abilityConstraints.associateBy { key(it) }
    var abilitySubst = returnSubst.compose(paramSubst).compose(effectSubst)
    for (k in byKey1.keys.intersect(byKey2.keys)) {
        val c1 = byKey1[k]!!
        val c2 = byKey2[k]!!
        abilitySubst = unifyAssociatedTypeMaps(
            c1.associatedTypeBindings, c2.associatedTypeBindings,
            kind = "Ability", owner = c1.abilityName, acc = abilitySubst, registry = registry
        ).getOrElse { return it }
    }

    return UnificationResult.Success(abilitySubst)
}

/**
 * Unify two associated-type binding maps key-by-key, threading [acc]. Shared by
 * the effect-row path (`EffectInstance.associatedTypes`) and the ability-
 * constraint path (`FunctionAbilityConstraint.associatedTypeBindings`) so both
 * check associated types identically. Only keys present on BOTH sides are
 * unified; a mismatch is a Failure naming the [kind] ("Effect"/"Ability"), the
 * [owner] (effect/ability name), and the key. On success returns the updated
 * substitution; the caller decides how to surface a Failure.
 */
private fun unifyAssociatedTypeMaps(
    m1: Map<String, MezzeType>,
    m2: Map<String, MezzeType>,
    kind: String,
    owner: String,
    acc: Substitution,
    registry: DeclarationRegistry,
): UnificationResult {
    var subst = acc
    for (key in m1.keys + m2.keys) {
        val t1 = m1[key] ?: continue
        val t2 = m2[key] ?: continue
        when (val r = unify(subst.apply(t1), subst.apply(t2), registry)) {
            is UnificationResult.Success -> subst = r.substitution.compose(subst)
            is UnificationResult.Failure -> return UnificationResult.Failure(
                message = "$kind '$owner' has incompatible associated type " +
                    "'${key.removePrefix("'")}': ${r.message}",
                type1 = t1,
                type2 = t2,
            )
        }
    }
    return UnificationResult.Success(subst)
}

/**
 * #268 Stage 1 — the "unify shared member payloads, thread the composed
 * substitution" loop shared by record and variant unification (records unify
 * field types; variants unify constructor payloads). Extracted verbatim from the
 * four copies (unifyRecords both-open + both-closed; unifyVariants both-open,
 * one-open, both-closed) so the compose-ordering and short-circuit are stated
 * ONCE. This is NOT the tail-binding or the nominal/effect-specific branch
 * policies — those stay per-caller (variant nominal wall, effect caughtByAncestor,
 * record exact-set-equality). Effects don't call this: their "members" span three
 * fields and unify associated-types, not a single MezzeType payload.
 *
 * For each key in [keys]:
 *   - `arityConflict(name)` runs first; if it returns a Failure, that is returned.
 *   - When BOTH sides have a payload, the payloads are unified under the running
 *     substitution and composed (new-on-left, matching every original site).
 *   - A key present on only one side is skipped here (openness/missing-member
 *     policy is the caller's job, applied before/around this loop).
 */
private inline fun unifyMemberPayloads(
    keys: Set<String>,
    member1: (String) -> MezzeType?,
    member2: (String) -> MezzeType?,
    registry: DeclarationRegistry,
    outer1: MezzeType,
    outer2: MezzeType,
    arityConflict: (String) -> UnificationResult.Failure? = { null },
    mismatchMessage: (name: String, applied1: MezzeType, applied2: MezzeType, inner: String) -> String,
): UnificationResult {
    var substitution = Substitution.empty
    for (name in keys) {
        arityConflict(name)?.let { return it }

        val payload1 = member1(name)
        val payload2 = member2(name)
        if (payload1 != null && payload2 != null) {
            val applied1 = substitution.apply(payload1)
            val applied2 = substitution.apply(payload2)
            val memberSubst = when (val memberResult = unify(applied1, applied2, registry)) {
                is UnificationResult.Success -> memberResult.substitution
                is UnificationResult.Failure -> return UnificationResult.Failure(
                    message = mismatchMessage(name, applied1, applied2, memberResult.message),
                    type1 = outer1,
                    type2 = outer2,
                )
            }
            substitution = memberSubst.compose(substitution)
        }
    }
    return UnificationResult.Success(substitution)
}

private fun unifyRecords(rec1: MezzeType.RecordType, rec2: MezzeType.RecordType, registry: DeclarationRegistry): UnificationResult {
    // Records are TRANSPARENT nominal types: identity vetoes ONLY when it proves the
    // two are distinct declarations (both Declared, hashes differ) — that must not
    // unify structurally even with identical fields. When identity can't decide
    // (UNDETERMINED — one side is an anonymous `{…}` literal, i.e. a transparent
    // alias meeting a bare record), we KEEP structural unification. That is what
    // makes `type Wrapper = {n:Int}; f { x = {n=5} }` type-check. Mirrors the
    // TypeApplication/variant hash-veto; contrast newtype, which is an opaque wall.
    // (`isOpaqueWall(record) == false` by construction — records are never opaque, so
    // an UNDETERMINED verdict here always falls through to the structural loop below.)
    if (identityVerdict(rec1.identity, rec2.identity) == IdentityVerdict.DISTINCT) {
        return UnificationResult.Failure(
            message = "Cannot unify record types: distinct declarations (content hash differs)",
            type1 = rec1,
            type2 = rec2
        )
    }

    val fields1 = rec1.fields.keys
    val fields2 = rec2.fields.keys

    if (rec1.rowVar != null || rec2.rowVar != null) {
        val allFields = (fields1 + fields2).toSet()

        // A field declared on ONE record but absent from the OTHER is only sound
        // if the ABSENT side is OPEN — its row variable can absorb that field. If
        // the absent side is CLOSED, the field is genuinely missing: `{ a, b, ... }`
        // still REQUIRES `b`, so a closed `{ a }` value (no `b`) must fail — the
        // `...` only admits EXTRA fields beyond the declared ones, it doesn't make
        // them optional. (Openness policy — the caller's job, run before the shared
        // payload loop below.)
        //
        // KNOWN SOUNDNESS HOLE (#11, NOT fixed here — needs directional info this
        // symmetric unifier lacks): when BOTH sides are open, a field demanded by
        // one side but absent from the other is silently absorbed, so an open value
        // `{ x: Int, ... }` wrongly satisfies a demand `{ y: Int, ... }` and
        // null-derefs at runtime ("Field not found: y"). A symmetric strict rule
        // here CANNOT distinguish that launder from the LEGITIMATE case of a value
        // carrying extra fields beyond an open demand (`{ x, y, ... }` satisfying
        // `{ y, ... }`) — both look like "field on one open side, absent from the
        // other". The correct fix threads expected-vs-actual (subsumption) direction
        // into record unification or fixes it at the arg-check call site.
        for (fieldName in allFields) {
            val missingOnClosedRight = fieldName in fields1 && fieldName !in fields2 && rec2.rowVar == null
            val missingOnClosedLeft = fieldName in fields2 && fieldName !in fields1 && rec1.rowVar == null
            if (missingOnClosedRight || missingOnClosedLeft) {
                return UnificationResult.Failure(
                    message = "record fields don't match: missing '$fieldName'",
                    type1 = rec1,
                    type2 = rec2
                )
            }
        }

        return unifyMemberPayloads(
            keys = allFields,
            member1 = { rec1.fields[it] },
            member2 = { rec2.fields[it] },
            registry = registry,
            outer1 = rec1,
            outer2 = rec2,
            mismatchMessage = { name, a1, a2, inner -> formatFieldMismatch(name, a2, a1, inner) },
        )
    }

    if (fields1 != fields2) {
        val missing = fields2 - fields1   // expected has, got doesn't
        val extra   = fields1 - fields2   // got has, expected doesn't
        val parts = mutableListOf<String>()
        if (missing.isNotEmpty()) {
            parts.add("missing ${missing.joinToString(", ") { "'$it'" }}")
        }
        if (extra.isNotEmpty()) {
            parts.add("unexpected ${extra.joinToString(", ") { "'$it'" }}")
        }
        return UnificationResult.Failure(
            message = "record fields don't match: ${parts.joinToString("; ")}",
            type1 = rec1,
            type2 = rec2
        )
    }

    // Field sets are equal here; unify the shared field types pairwise.
    return unifyMemberPayloads(
        keys = fields1,
        member1 = { rec1.fields[it] },
        member2 = { rec2.fields[it] },
        registry = registry,
        outer1 = rec1,
        outer2 = rec2,
        mismatchMessage = { name, a1, a2, inner -> formatFieldMismatch(name, a2, a1, inner) },
    )
}

// A variant acts as a hard wall (foreign ctors reject) when either the user
// wrote an ascription-shaped construct (ascriptionWall) OR the alias name is
// in the caller's import set. Kept as `isWalledVariant` for its
// remaining callers; the returned bool is really "should this variant wall".
private fun isWalledVariant(
    v: MezzeType.VariantType,
    registry: DeclarationRegistry
): Boolean {
    // The opaque-wall portion (ascriptionWall) comes from the shared predicate; the
    // #148 name-in-scope barrier below is the DISTINCT, registry-driven layer on top.
    if (isOpaqueWall(v)) return true
    val name = v.identity.displayNameOrNull() ?: return false
    val override = inScopeAliasNamesOverride.get()
    return if (override != null) override.contains(name)
           else registry.typeNames.contains(name)
}

private val inScopeAliasNamesOverride: ThreadLocal<Set<String>?> = ThreadLocal.withInitial { null }

fun <T> withInScopeAliasNames(names: Set<String>, block: () -> T): T {
    val prev = inScopeAliasNamesOverride.get()
    inScopeAliasNamesOverride.set(names)
    try {
        return block()
    } finally {
        inScopeAliasNamesOverride.set(prev)
    }
}

fun inScopeAliasNamesForSolverOrNull(): Set<String>? = inScopeAliasNamesOverride.get()

internal fun unifyVariants(var1: MezzeType.VariantType, var2: MezzeType.VariantType, registry: DeclarationRegistry): UnificationResult {
    val constructors1 = var1.constructors.keys
    val constructors2 = var2.constructors.keys

    // #12 (soundness): two variants whose identities are provably DISTINCT (both
    // Declared, content hashes differ) are distinct declarations and MUST NOT
    // unify — even when one or both are OPEN. Without this the open branches below
    // absorb each side's unique ctors into the other's row var and vacuously
    // succeed, so an `ErrB` value silently satisfies an `ErrA` param (last-wins
    // coercion). The closed/nominal path already applies this veto further down;
    // hoisting it here covers the both-open and one-open branches too. Anonymous
    // open variants return UNDETERMINED (not DISTINCT), so genuine row polymorphism
    // is untouched.
    if (identityVerdict(var1.identity, var2.identity) == IdentityVerdict.DISTINCT) {
        return UnificationResult.Failure(
            message = "Cannot unify ${describeVariant(var1)} with ${describeVariant(var2)} (distinct declarations — content hash differs)",
            type1 = var1,
            type2 = var2
        )
    }

    if (var1.rowVar != null && var2.rowVar != null) {
        val allConstructors = (constructors1 + constructors2).toSet()
        // #258: a constructor present on BOTH open variants with a payload-arity
        // disagreement (nullary on one side, payload-bearing on the other) is a hard
        // conflict. A constructor unique to one side is fine (absorbed by the other's
        // row var), so only check shared constructors.
        return unifyMemberPayloads(
            keys = allConstructors,
            member1 = { var1.constructors[it] },
            member2 = { var2.constructors[it] },
            registry = registry,
            outer1 = var1,
            outer2 = var2,
            arityConflict = { name ->
                if (name in constructors1 && name in constructors2 &&
                    (var1.constructors[name] == null) != (var2.constructors[name] == null)
                ) {
                    UnificationResult.Failure("Constructor '$name' data mismatch", var1, var2)
                } else null
            },
            mismatchMessage = { name, _, _, inner -> "Constructor '$name' type mismatch: $inner" },
        )
    }

    if (var1.rowVar != null || var2.rowVar != null) {
        val (openVar, closedVar) = if (var1.rowVar != null) Pair(var1, var2) else Pair(var2, var1)
        val closedCtors = closedVar.constructors.keys
        val openCtors = openVar.constructors.keys

        // When the closed side is a wall (ascriptionWall OR an in-scope alias),
        // the open side's ctors must be a subset of the closed side's arm set —
        // otherwise the open side is trying to add a foreign ctor to a wall.
        // Prevents `Yellow` from unifying with `Color = | Red, Green, Blue |`
        // when Color is imported (or when the closed side is a hard ascription).
        if (isWalledVariant(closedVar, registry)) {
            val invalidConstructors = openCtors - closedCtors
            if (invalidConstructors.isNotEmpty()) {
                val invalid = invalidConstructors.sorted().joinToString(", ")
                return UnificationResult.Failure(
                    message = "Constructor(s) '$invalid' not found in ${describeVariant(closedVar)}.",
                    type1 = var1,
                    type2 = var2
                )
            }
        }

        return unifyMemberPayloads(
            keys = closedCtors,
            member1 = { var1.constructors[it] },
            member2 = { var2.constructors[it] },
            registry = registry,
            outer1 = var1,
            outer2 = var2,
            // #10 (soundness): a ctor present on BOTH sides with a payload-arity
            // disagreement (payloadless on one, payloaded on the other) is a hard
            // conflict — same guard the both-open and nominal branches use. The
            // one-open branch was missing it, so a payloadless `| Tag |` unified
            // with a demand for `| Tag { val: Int }, ... |` and null-deref'd at
            // runtime ("Expected record for field 'val', got: null").
            arityConflict = { name ->
                if (name in constructors1 && name in constructors2 &&
                    (var1.constructors[name] == null) != (var2.constructors[name] == null)
                ) {
                    UnificationResult.Failure("Constructor '$name' data mismatch", var1, var2)
                } else null
            },
            mismatchMessage = { name, _, _, inner -> "Constructor '$name' type mismatch: $inner" },
        )
    }

    // — but only if at least one of those names is in scope at the use site (#148). An alias
    // acts as a nominal wall only when its name is reachable by the caller (declared locally
    // or imported by name). When a value crosses a module boundary with an alias-typed shape
    // and the caller didn't import the alias, the name has no nominal force here.
    // A variant is "walled" if it carries a decl identity (name/hash) OR the
    // author-written / inferred ascriptionWall marker. Both are hard walls under
    // unification's disjoint-arms rule; only two walls with SAME arms unify.
    val walled1 = var1.identity != TypeIdentity.Anonymous || isOpaqueWall(var1)
    val walled2 = var2.identity != TypeIdentity.Anonymous || isOpaqueWall(var2)
    val bothWalled = walled1 && walled2
    val id1 = var1.identity
    val id2 = var2.identity
    // Two walls are the same wall when they are the same DECLARATION, or when
    // they are one well-known builtin met in both of its representations.
    val identityMatch = sameDeclaredIdentity(id1, id2) || sameBuiltinSeenBothWays(id1, id2)
    // Identity is authoritative: two variants whose identities are provably DISTINCT
    // (both Declared, content hashes differ) are distinct declarations and MUST NOT
    // unify — regardless of whether either name is in scope. Mirrors the
    // TypeApplication/NewtypeType veto (Stage 0 proved recursive self-reference
    // threads a stable equal hash, so this never over-rejects). UNDETERMINED (a
    // hashless/anonymous side) is left to the name-in-scope barrier below — variants
    // are transparent aliases unless walled.
    if (identityVerdict(var1.identity, var2.identity) == IdentityVerdict.DISTINCT) {
        return UnificationResult.Failure(
            message = "Cannot unify ${describeVariant(var1)} with ${describeVariant(var2)} (distinct declarations — content hash differs)",
            type1 = var1,
            type2 = var2
        )
    }
    if (bothWalled && !identityMatch) {
        // Hash-null (anonymous / builtin) walls fall back to the in-scope name
        // barrier: a name has nominal force only when in scope in the caller.
        val var1NameInScope = isWalledVariant(var1, registry)
        val var2NameInScope = isWalledVariant(var2, registry)
        if (var1NameInScope || var2NameInScope) {
            // #149: when both walls happen to constrain to the SAME arm set,
            // unify successfully (their nominal identities are interchangeable
            // for value-typing purposes). Concretely this covers the pattern
            val sameArms = var1.constructors.keys == var2.constructors.keys
            if (!sameArms) {
                return UnificationResult.Failure(
                    message = "Cannot unify ${describeVariant(var1)} with ${describeVariant(var2)}",
                    type1 = var1,
                    type2 = var2
                )
            }
        }
    }

    // Wall + anonymous: the wall's arm set is authoritative; anonymous side's
    // extras that aren't in the wall's arms are rejected (only when the wall
    // is actually enforced — via ascriptionWall OR its typeName being in scope).
    val (declaredVar, anonymousVar) = when {
        walled1 && !walled2 -> Pair(var1, var2)
        walled2 && !walled1 -> Pair(var2, var1)
        else -> Pair(null, null)
    }

    if (declaredVar != null && anonymousVar != null) {
        val invalidConstructors = anonymousVar.constructors.keys - declaredVar.constructors.keys
        if (invalidConstructors.isNotEmpty() && isWalledVariant(declaredVar, registry)) {
            val invalid = invalidConstructors.sorted().joinToString(", ")
            return UnificationResult.Failure(
                message = "Constructor(s) '$invalid' not found in ${describeVariant(declaredVar)}.",
                type1 = var1,
                type2 = var2
            )
        }
    }

    val allConstructors = (constructors1 + constructors2).toSet()
    // Same arity guard as the both-open branch, gated on shared constructors.
    return unifyMemberPayloads(
        keys = allConstructors,
        member1 = { var1.constructors[it] },
        member2 = { var2.constructors[it] },
        registry = registry,
        outer1 = var1,
        outer2 = var2,
        arityConflict = { name ->
            if (name in constructors1 && name in constructors2 &&
                (var1.constructors[name] == null) != (var2.constructors[name] == null)
            ) {
                UnificationResult.Failure("Constructor '$name' data mismatch", var1, var2)
            } else null
        },
        mismatchMessage = { name, _, _, inner -> "Constructor '$name' type mismatch: $inner" },
    )
}

private fun unifyTypeApplications(app1: MezzeType.TypeApplication, app2: MezzeType.TypeApplication, registry: DeclarationRegistry): UnificationResult {
    // Hash is the authoritative nominal identity. When BOTH sides carry a
    // declaration content hash and those hashes DIFFER, the two are distinct
    // declarations and MUST NOT unify — even when their names match (two modules'
    // `Widget`, a version skew). Mirrors the NewtypeType hash-veto (#278/F0).
    //
    // Formerly the veto was withheld out of a fear that `typeHash` is unstable
    // across a recursive self-reference (inner vs outer `Json` in
    // `type Json = | … JArray {items:[Json]} |`). The post-merge stamping chain
    // threads the SAME `contentHash` to every occurrence, and a suite-wide probe
    // (Stage 0) found ZERO same-name/both-hash/unequal pairs across the recursive
    // codec types — so the instability rationale is dead and the veto is safe.
    val verdict = identityVerdict(app1.identity, app2.identity)
    if (verdict == IdentityVerdict.DISTINCT) {
        return UnificationResult.Failure(
            message = "Type constructor mismatch: ${app1.constructor} is a different declaration than ${app2.constructor} (content hash differs)",
            type1 = app1,
            type2 = app2,
        )
    }
    // A NAME match unifies; the SAME concrete declaration on DIFFERING names ALSO
    // unifies (a rename-safe alias: equal Declared hash, or equal Builtin name).
    // Identity only ever ADDS matches here — the DISTINCT reject above already
    // handled the distinct-declaration case. NOTE: must use sameDeclaredIdentity,
    // NOT the SAME verdict — two Anonymous applications are NOT the same declaration
    // (else `Wrap Int` and `Seq Int`, both hashless→Anonymous, would wrongly unify).
    // The DISTINCT hash-veto above already rejected two Declared decls with unequal
    // hashes. A NAME match here only ever ADDS unification, and only DECIDES the
    // outcome when at least one side is hashless (Anonymous/Builtin) — where the
    // name is the only identity. When both sides are Declared with EQUAL hash, the
    // sameDeclaredIdentity clause carries it and the name is redundant.
    val nameMatched = app1.constructor == app2.constructor
    val declMatched = sameDeclaredIdentity(app1.identity, app2.identity)
    val constructorsMatch = nameMatched || declMatched
    if (!constructorsMatch) {
        val expanded1 = expandTypeApplicationToVariant(app1, registry)
        val expanded2 = expandTypeApplicationToVariant(app2, registry)

        if (expanded1 != null && expanded2 != null) {
            if (expanded1.rowVar != null || expanded2.rowVar != null) {
                return unifyVariants(expanded1, expanded2, registry)
            }

            // STRUCTURAL ALIAS HANDLING (#148): two distinct closed aliases. An
            // alias name acts as a nominal barrier ONLY when it's in scope in
            // the caller's module — declared locally or imported by name. When
            // NEITHER side is in scope, both names are reachable-but-unqualified
            // labels and the typechecker should treat the values structurally,
            // unifying their arm sets.
            val override = inScopeAliasNamesOverride.get()
            val app1InScope = if (override != null) override.contains(app1.constructor)
                              else registry.typeNames.contains(app1.constructor)
            val app2InScope = if (override != null) override.contains(app2.constructor)
                              else registry.typeNames.contains(app2.constructor)
            if (!app1InScope && !app2InScope) {
                return unifyVariants(expanded1, expanded2, registry)
            }
        }

        return UnificationResult.Failure(
            message = "Type constructor mismatch: ${app1.constructor} vs ${app2.constructor}",
            type1 = app1,
            type2 = app2
        )
    }

    if (app1.args.size != app2.args.size) {
        return UnificationResult.Failure(
            message = "Type argument count mismatch: ${app1.args.size} vs ${app2.args.size}",
            type1 = app1,
            type2 = app2
        )
    }

    var substitution = Substitution.empty
    for (i in app1.args.indices) {
        val arg1 = substitution.apply(app1.args[i])
        val arg2 = substitution.apply(app2.args[i])

        val argSubst = when (val argResult = unify(arg1, arg2, registry)) {
            is UnificationResult.Success -> argResult.substitution
            is UnificationResult.Failure -> return UnificationResult.Failure(
                message = "Type argument $i mismatch: ${argResult.message}",
                type1 = app1,
                type2 = app2
            )
        }
        substitution = argSubst.compose(substitution)
    }

    return UnificationResult.Success(substitution)
}



private fun unifyEffectRows(eff1: MezzeType.EffectRow, eff2: MezzeType.EffectRow, registry: DeclarationRegistry): UnificationResult {
    val allEffects1 = eff1.allEffectNames
    val allEffects2 = eff2.allEffectNames
    val combinedEffects = allEffects1 + allEffects2

    // Hermetic-Mut check reads each effect's carried content hash directly.
    // The row carries effectHashes (effectName → declHash); prefer that. The
    // registry lookup is only the parse-boundary seed for any effect that has
    // not yet had its hash stamped onto the row — never a name→identity compare.
    val combinedHashes = eff1.effectHashes + eff2.effectHashes
    fun effectHash(name: String): Hash? = combinedHashes[name] ?: registry.resolveEffectHash(name)

    val mutEffect = combinedEffects.firstOrNull { name ->
        WellKnownHashConstants.isMut(effectHash(name))
    }
    if (mutEffect != null && combinedEffects.size > 1) {
        val otherEffects = combinedEffects.filter { name ->
            !WellKnownHashConstants.isMut(effectHash(name))
        }.joinToString(", ")
        return UnificationResult.Failure(
            message = "$mutEffect effect is hermetic: cannot coexist with other effects ($otherEffects). " +
                "$mutEffect must be the only effect in scope to prevent data races. " +
                "Either handle the other effects before entering the mutation scope, " +
                "or restructure the code to separate mutation from other effectful operations.",
            type1 = eff1,
            type2 = eff2
        )
    }

    val instances1 = eff1.effectInstances.associateBy { it.name }
    val instances2 = eff2.effectInstances.associateBy { it.name }

    val commonEffects = instances1.keys.intersect(instances2.keys)

    var composedSubst = Substitution.empty
    for (effectName in commonEffects) {
        val inst1 = instances1[effectName]!!
        val inst2 = instances2[effectName]!!

        val allKeys = inst1.associatedTypes.keys + inst2.associatedTypes.keys
        for (key in allKeys) {
            val type1 = inst1.associatedTypes[key]
            val type2 = inst2.associatedTypes[key]

            if (type1 != null && type2 != null) {
                // L-31: thread the in-scope registry (was defaulting to
                // DeclarationRegistry.EMPTY) so a TypeApplication needing alias
                // expansion resolves here the SAME way it does in the sibling
                // ability-constraint path (unifyAssociatedTypeMaps threads registry).
                // Without it, the same associated-type question got two answers
                // depending on whether it flowed through an effect row or a constraint.
                val result = unify(composedSubst.apply(type1), composedSubst.apply(type2), registry)
                when (result) {
                    is UnificationResult.Success -> {
                        composedSubst = composedSubst.compose(result.substitution)
                    }
                    is UnificationResult.Failure -> {
                        val (expStr, actStr) = prettyPrintTypePair(type2, type1)
                        return UnificationResult.Failure(
                            message = "Effect '$effectName' has incompatible associated type '$key': " +
                                "expected $expStr but got $actStr",
                            type1 = eff1,
                            type2 = eff2
                        )
                    }
                }
            }
        }
    }

    // #274-B: propagate effects through row vars.
    // - Effects only in eff2 must be absorbed by eff1's row var (if any).
    // - Effects only in eff1 must be absorbed by eff2's row var (if any).
    // - If both sides are closed and their effect sets differ, that's a
    //   real mismatch (annotation lies about body's effects).
    //
    // Row subtraction via `caughtByAncestor`: effects listed on a callback
    // row that don't appear on the enclosing function's return row are
    // HANDLED by the enclosing function (e.g., `spawn` catches Exception).
    // Filter these out from the ACTUAL side's extras (eff2Extra): the
    // enclosing function catches them, so they should not bind the shared
    // row var and thus should not surface to the caller.
    //
    // DECLARED-only extras (eff1Extra) are NOT filtered by caught. If
    // spawn declares `<Exception, ρ>` on the callback but the actual
    // lambda doesn't perform Exception, that's a type error — the
    // callback promised to perform Exception and didn't. Row subtraction
    // is about "spawn catches what the CALLBACK actually performs," not
    // "spawn accepts pure callbacks that ignore the contract."
    val caught = eff1.caughtByAncestor + eff2.caughtByAncestor
    val eff2Extra = (allEffects2 - allEffects1) - caught
    val eff1Extra = allEffects1 - allEffects2

    // Strict declared-row check (position-symmetric):
    // A row with non-empty caughtByAncestor is a DECLARED CALLBACK row
    // (only such rows carry caughtByAncestor — set by stampCaughtOnCallbackRows).
    // Effects listed on a declared callback row MUST be performed by the
    // actual body. If declared has effects the actual doesn't perform,
    // that's an error regardless of which side of the unification the
    // declared row lands on.
    if (eff1.caughtByAncestor.isNotEmpty()) {
        // eff1 is declared, eff2 is actual.
        val missing = allEffects1 - allEffects2
        if (missing.isNotEmpty()) {
            return UnificationResult.Failure(
                message = "Effect row mismatch: callback declared row lists <${allEffects1.sorted().joinToString(", ")}> " +
                    "but the actual body performs only <${allEffects2.sorted().joinToString(", ")}>. " +
                    "Missing: <${missing.sorted().joinToString(", ")}>. " +
                    "Every effect on a declared callback row must be performed by the body.",
                type1 = eff1,
                type2 = eff2
            )
        }
    }
    if (eff2.caughtByAncestor.isNotEmpty()) {
        // eff2 is declared, eff1 is actual.
        val missing = allEffects2 - allEffects1
        if (missing.isNotEmpty()) {
            return UnificationResult.Failure(
                message = "Effect row mismatch: callback declared row lists <${allEffects2.sorted().joinToString(", ")}> " +
                    "but the actual body performs only <${allEffects1.sorted().joinToString(", ")}>. " +
                    "Missing: <${missing.sorted().joinToString(", ")}>. " +
                    "Every effect on a declared callback row must be performed by the body.",
                type1 = eff1,
                type2 = eff2
            )
        }
    }

    // Contravariant callback rows: if one side declares extras but the other
    // side's row is CLOSED (no row var), silently accept — the extras are
    // "handled by" whatever handler declared them. This is what makes
    // `spawn : { f: {} -> 'a <Exception, ...> }` accept a callback like
    // `{} -> 42` (which performs no Exception): the declared Exception is
    // simply not present, and spawn's runtime catches nothing.
    //
    // The strict "annotation doesn't match body" check for LET BINDINGS is
    // enforced separately by validateEffectsNotOverpromised (which runs
    // post-solve on annotated let decls). Unification is where argument
    // passing / call-site type matching happens; contravariance is correct
    // there.
    if (eff2Extra.isNotEmpty()) {
        val eff1Row = eff1.rowVar
        if (eff1Row != null) {
            val extraInstances = eff2.effectInstances.filter { it.name in eff2Extra }.toSet()
            val extraSimple = eff2Extra - extraInstances.map { it.name }.toSet()
            val extraHashes = eff2.effectHashes.filterKeys { it in eff2Extra }
            val binding = MezzeType.EffectRow(
                effects = extraSimple,
                effectInstances = extraInstances,
                effectHashes = extraHashes,
                rowVar = eff2.rowVar,
                performed = false
            )
            composedSubst = composedSubst.compose(Substitution.singleRow(eff1Row, binding))
        }
    }

    if (eff1Extra.isNotEmpty()) {
        val eff2Row = eff2.rowVar
        if (eff2Row != null) {
            val extraInstances = eff1.effectInstances.filter { it.name in eff1Extra }.toSet()
            val extraSimple = eff1Extra - extraInstances.map { it.name }.toSet()
            val extraHashes = eff1.effectHashes.filterKeys { it in eff1Extra }
            val binding = MezzeType.EffectRow(
                effects = extraSimple,
                effectInstances = extraInstances,
                effectHashes = extraHashes,
                rowVar = eff1.rowVar,
                performed = false
            )
            composedSubst = composedSubst.compose(Substitution.singleRow(eff2Row, binding))
        } else {
            // Declared side has effects the actual (closed) side doesn't
            // perform. This is a real mismatch — the declaration is a
            // promise, and the actual body isn't keeping it.
            return UnificationResult.Failure(
                message = "Effect row mismatch: declared effects <${allEffects1.sorted().joinToString(", ")}> " +
                    "but actual performs only <${allEffects2.sorted().joinToString(", ")}>. " +
                    "Missing: <${eff1Extra.sorted().joinToString(", ")}>. " +
                    "The callback must perform every effect declared on its row.",
                type1 = eff1,
                type2 = eff2
            )
        }
    }

    return UnificationResult.Success(composedSubst)
}

/**
 * Build a focused error message when a record field's type fails to unify
 * against an expected shape. Avoids the doubled "Field 'x' type mismatch:
 * Type mismatch:" phrasing — strips the inner generic prefix and, when the
 * expected field type is a closed record (the shape produced by issue #216's
 * method-call inference), foregrounds the shape so the user sees what they
 * needed to pass.
 */
private fun unifyKeyValue(
    key1: MezzeType, val1: MezzeType,
    key2: MezzeType, val2: MezzeType,
    registry: DeclarationRegistry
): UnificationResult {
    val keyResult = unify(key1, key2, registry)
    return when (keyResult) {
        is UnificationResult.Success -> {
            val valueResult = unify(
                keyResult.substitution.apply(val1),
                keyResult.substitution.apply(val2),
                registry
            )
            when (valueResult) {
                is UnificationResult.Success -> UnificationResult.Success(keyResult.substitution.compose(valueResult.substitution))
                is UnificationResult.Failure -> valueResult
            }
        }
        is UnificationResult.Failure -> keyResult
    }
}

private fun formatFieldMismatch(
    fieldName: String,
    expectedFieldType: MezzeType,
    actualFieldType: MezzeType,
    innerMessage: String
): String {
    val (expectedStr, actualStr) = prettyPrintTypePair(expectedFieldType, actualFieldType)
    val baseMessage = "Field '$fieldName' type mismatch: expected $expectedStr, got $actualStr"
    val cleanedInner = innerMessage.removePrefix("Type mismatch: ").trim()
    val isShallow = cleanedInner.contains(expectedStr) && cleanedInner.contains(actualStr)
    return if (isShallow) baseMessage else "$baseMessage ($cleanedInner)"
}

/**
 * THE type-parameter substitution (task F). There were two: this one, and a copy
 * in `ImplValidator` that differed in both directions — the copy rebuilt a
 * `TypeApplication` through [WellKnownTypeApp.of] so a well-known constructor
 * regains its identity hash when it has none, while this one tolerated the `'`
 * prefix on either side of the lookup. Each was right about the thing the other
 * missed, so the merged version does both and the copy is gone.
 *
 * PRECONDITION, unstated until now and not checked: every variable in [type]
 * that shares a name with a key of [subst] must BE that parameter. Replacement
 * is by NAME, so two distinct variables spelled the same are both rewritten.
 * This is reachable — a probe over both suites found four occurrences, all on
 * `out` (the `having 'out` associated type) with two distinct variables sharing
 * the name while `out` was in the substitution. No test changes result today,
 * so it is an unguarded assumption rather than a known wrong answer; see the
 * name-keyed-substitution task before relying on it.
 */
internal fun substituteTypeParamsByName(type: MezzeType, subst: Map<String, MezzeType>): MezzeType {
    // Only a type var is replaced (by name, tolerating the `'` prefix either
    // way); every other node just recurses via transformChildren, which
    // rebuilds it with copy() and so preserves typeName/typeHash/rowVar/effects.
    if (type is MezzeType.VarType) {
        val name = type.variable.name
        return subst[name] ?: subst["'$name"] ?: subst[name.removePrefix("'")] ?: type
    }
    // TypeApplication keeps its own case so a well-known constructor that arrived
    // WITHOUT an identity hash regains one — transformChildren would rebuild it
    // hashless, and a hashless application is the shape dispatch cannot key on.
    // An application that already carries a Declared hash keeps it: `of` only
    // fills in what is absent.
    if (type is MezzeType.TypeApplication) {
        return com.mezze.language.typechecker.util.WellKnownTypeApp.of(
            type.constructor,
            type.args.map { substituteTypeParamsByName(it, subst) },
            (type.identity as? TypeIdentity.Declared)?.hash,
        )
    }
    return type.transformChildren { substituteTypeParamsByName(it, subst) }
}

internal fun expandTypeApplicationToRecord(typeApp: MezzeType.TypeApplication, registry: DeclarationRegistry): MezzeType.RecordType? {
    // Registry lookup first (import-scoped + transitive-by-hash); fall back to the
    // module cache when a Declared TypeApplication references a type from a module
    // that wasn't directly imported by the current file. Without this fallback, an
    // annotation whose alias-inline leaves a nested `TypeApplication(SharedConfig)`
    // fails to unify against a fully-expanded RecordType(SharedConfig, …) reached
    // through a same-hash imported alias (e.g. `let c: JsonC = json_default`) —
    // `expandTypeApplication` returns NotFound and the RecordType-vs-TypeApp arm
    // reports "SharedConfig is not compatible with SharedConfig". Mirrors the
    // ModuleResolver fallback already used by expandTypeApplicationToVariant.
    val typeDecl = declarationFor(typeApp, registry)
        ?: return null

    val recordDef = typeDecl.definition as? ParsedRecordDef ?: return null

    val typeParamSubst = typeDecl.typeParams.zip(typeApp.args).associate { (paramName, argType) ->
        paramName to argType
    }

    val contextBuilder = expandContextBuilder.get()

    val fields = recordDef.fields.associate { field ->
        val convertedType = contextBuilder.convertParsedTypeAnnotation(field.type)
        val substitutedType = substituteTypeParamsByName(convertedType, typeParamSubst)
        field.name to substitutedType
    }

    return MezzeType.RecordType(
        fields = fields,
        // #260: expanding an OPEN record alias (`type Foo = { a, ... }`) at a use
        // site must produce a USER-open row (like the inline annotation path), so
        // solveHasField (ConstraintSolver.kt:1029) rejects reading an unlisted field
        // (`f.zzz`) instead of inventing it. A plain fresh row read as solver-inferred.
        rowVar = if (recordDef.isOpen) RowVar.userOpen() else null
    )
}

/**
 * The declaration a [MezzeType.TypeApplication] names, resolved by its IDENTITY.
 *
 * This file's registry answers first. `ModuleResolver`'s module cache is the wider
 * net for a type the registry never loaded — a nested application reached through
 * an alias-inline of an imported type, e.g. `JsonC` inlining to a record with a
 * `TypeApplication(SharedConfig)` field where `SharedConfig` was never imported
 * directly.
 *
 * That wider lookup is BY NAME, and for an application carrying a Declared identity
 * it has to agree with it. Letting it answer regardless substitutes a different
 * type that merely shares the name: a module declaring `type Option = | Yes, No |`
 * expanded the stdlib's `Option Int` with ITS constructors, the two unified, the
 * program type-checked, and the match died at runtime with "no variant tag matched
 * — typechecker exhaustiveness guarantee violated".
 *
 * Null means no declaration THIS application could be; each caller turns that into
 * its own not-found result, which unification reports as incompatible types.
 */
private fun declarationFor(
    typeApp: MezzeType.TypeApplication,
    registry: DeclarationRegistry,
): ParsedTypeDecl? {
    val declaredHash = (typeApp.identity as? TypeIdentity.Declared)?.hash
    return registry.lookupType(typeApp.constructor, declaredHash)
        ?: ModuleResolver.lookupTypeDeclaration(typeApp.constructor)?.takeIf { d ->
            declaredHash == null || (d.contentHash ?: ParsedDeclHasher.hashTypeDecl(d)) == declaredHash
        }
}

/**
 * `Option`/`Result` as variant shapes when the registry cannot supply them.
 *
 * Both are DECLARED in the stdlib, so every caller with a real registry keeps
 * using the declaration and nothing changes for them. A caller without one —
 * `unify(a, b)` at its default `DeclarationRegistry.EMPTY` — used to get null and
 * report `Option 'a is not compatible with Option 'b` for two spellings of one
 * type. Their constructors are reserved and not in doubt; this is the same
 * answer `List` already gets one line below.
 */
private fun builtinVariantShape(typeApp: MezzeType.TypeApplication): MezzeType.VariantType? = when (typeApp.constructor) {
    BuiltinTypes.OPTION -> typeApp.args.singleOrNull()?.let { BuiltinTypes.optionVariantShape(it) }
    BuiltinTypes.RESULT ->
        if (typeApp.args.size == 2) BuiltinTypes.resultVariantShape(typeApp.args[0], typeApp.args[1]) else null
    else -> null
}

internal fun expandTypeApplicationToVariant(typeApp: MezzeType.TypeApplication, registry: DeclarationRegistry): MezzeType.VariantType? {
    // `List` has no `ParsedTypeDecl` — it is a compiler builtin with a dedicated
    // `MezzeType.ListType` — so there was nothing to expand it against, and
    // `let f: { l: List Int } -> Int` with `match l is Nil -> …` failed as "Cannot
    // resolve type 'List' to check for constructor 'Nil'" while the same program in
    // `Option`/`Result` worked. Its constructors are not in doubt: `Cons`/`Nil` are
    // RESERVED to `List` (BuiltinTypes.reservedConstructorOwner), and matching them
    // already works at runtime.
    //
    // Synthesised HERE rather than registered as a declaration on purpose. A decl
    // would give `List 'a` a Declared identity, `canDeriveAbility("Eq", …)` would
    // answer true, and the coherence rule would reject `std/collections/list.mz`'s
    // hand-written `Eq`/`Hash` — which are load-bearing, because a List is NATIVE at
    // runtime rather than an actual Cons/Nil chain. This gives the type checker the
    // constructors without telling the derive machinery anything new. Deleting it is
    // part of making a container's derived Eq element-aware (#34/T1.4).
    BuiltinTypes.listElementType(typeApp)?.let { return BuiltinTypes.listVariantShape(it) }

    val typeDecl = declarationFor(typeApp, registry)
        ?: return builtinVariantShape(typeApp)

    val variantDef = when (val def = typeDecl.definition) {
        is ParsedVariantDef -> def
        is ParsedNewtypeDef -> {
            val payloadType = if (def.wrappedType is ParsedRecordType) {
                def.wrappedType
            } else {
                val synLoc = Loc("<expand>", 0, 0, 0, 0)
                ParsedRecordType(
                    entries = listOf(ParsedRecordTypeEntry("val", def.wrappedType, synLoc)),
                    loc = synLoc
                )
            }
            val synLoc = Loc("<expand>", 0, 0, 0, 0)
            ParsedVariantDef(listOf(ParsedVariantCtorDef(def.constructor, payloadType, loc = synLoc)), loc = synLoc)
        }
        else -> return null
    }

    DebugLogger.debugSection(Category.Unification, "expandTypeApplication") {
        listOf(
            "Expanding ${typeApp.constructor}",
            "Variant has ${variantDef.constructors.size} constructors: ${variantDef.constructors.map { it.name }}"
        )
    }

    val typeParamSubst = typeDecl.typeParams.zip(typeApp.args).associate { (paramName, argType) ->
        paramName to argType
    }

    // Convert the constructor payload annotations with a config that resolves
    // declared-type hashes from the registry — so a recursive self-reference (the
    // inner `Tree` in `Node { l: Tree 'a }`) carries real nominal identity
    // (Declared), never Named. The shared expandContextBuilder has an empty decl
    // map and would leave the inner reference hashless.
    val expandConfig = TypeAnnotationConverter.ConversionConfig(
        resolveTypeHash = { name -> registry.lookupType(name)?.let { d -> d.contentHash ?: ParsedDeclHasher.hashTypeDecl(d) } },
        resolveCollectionTypes = true,
    )
    val constructors = variantDef.constructors.associate { constructor ->
        val dataType = constructor.dataType?.let { annotation ->
            val convertedType = TypeAnnotationConverter.convertWith(annotation, expandConfig)
            substituteTypeParamsByName(convertedType, typeParamSubst)
        }
        constructor.name to dataType
    }

    DebugLogger.debugSection(Category.Unification, "expandTypeApplicationToVariant") {
        listOf(
            "Expanding ${typeApp.constructor}",
            "VariantDef has ${variantDef.constructors.size} constructors: ${variantDef.constructors.map { it.name }}",
            "Built ${constructors.size} constructors: ${constructors.keys}"
        )
    }

    return MezzeType.VariantType(
        constructors = constructors,
        rowVar = if (variantDef.isOpen) RowVar.fresh() else null,
        typeArgs = typeApp.args,   // #194: carry the application's args so the printer can render `Tree Int`
        identity = TypeIdentity.declared(
            typeDecl.name,
            typeDecl.contentHash ?: ParsedDeclHasher.hashTypeDecl(typeDecl)
        )
    )
}

sealed class TypeExpansionResult {
    data class Record(val recordType: MezzeType.RecordType) : TypeExpansionResult()

    data class Variant(val variantType: MezzeType.VariantType) : TypeExpansionResult()

    data class Newtype(
        val variantType: MezzeType.VariantType,
        val constructorName: String,
        val payloadType: MezzeType?
    ) : TypeExpansionResult()

    object NotFound : TypeExpansionResult()
}

internal fun expandTypeApplication(
    typeApp: MezzeType.TypeApplication,
    registry: DeclarationRegistry
): TypeExpansionResult {
    // Fall back to the module cache when the current file's registry doesn't
    // carry this type — a nested TypeApplication reached through an alias-inline
    // of an imported type (e.g. `JsonC` inlining to a record with a
    // `TypeApplication(SharedConfig)` field, where `SharedConfig` isn't directly
    // imported). Mirrors the fallback already used by expandTypeApplicationToVariant.
    val typeDecl = declarationFor(typeApp, registry)
        ?: return TypeExpansionResult.NotFound

    return when (val definition = typeDecl.definition) {
        is ParsedRecordDef -> {
            val expanded = expandTypeApplicationToRecord(typeApp, registry)
            if (expanded != null) {
                TypeExpansionResult.Record(expanded)
            } else {
                TypeExpansionResult.NotFound
            }
        }
        is ParsedVariantDef -> {
            val expanded = expandTypeApplicationToVariant(typeApp, registry)
            if (expanded != null) {
                TypeExpansionResult.Variant(expanded)
            } else {
                TypeExpansionResult.NotFound
            }
        }
        is ParsedNewtypeDef -> {
            val expanded = expandTypeApplicationToVariant(typeApp, registry)
            if (expanded != null && expanded.constructors.size == 1) {
                val (constructorName, payloadType) = expanded.constructors.entries.first()
                TypeExpansionResult.Newtype(expanded, constructorName, payloadType)
            } else {
                TypeExpansionResult.NotFound
            }
        }
        is ParsedTypeAliasDef -> {
            // Transparent aliases are inlined at the annotation converter, so
            // a TypeApplication of an alias name should never reach here in
            // normal flow. If one leaks through (e.g. via a code path that
            // hasn't been ported yet) treat it as NotFound rather than
            // hallucinating record/variant/newtype shape.
            TypeExpansionResult.NotFound
        }
    }
}

private fun isBuiltinCollectionApp(app: MezzeType.TypeApplication, expectedConstructor: String): Boolean =
    app.constructor == expectedConstructor
