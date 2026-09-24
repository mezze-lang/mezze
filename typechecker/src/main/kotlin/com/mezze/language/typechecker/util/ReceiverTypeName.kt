package com.mezze.language.typechecker.util

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeIdentity
import com.mezze.language.typedast.Hash

/**
 * The content hash of a solved receiver's DECLARED nominal identity, or null
 * when the receiver has no declared identity (builtins, structural/anonymous
 * records, type variables). This is the AUTHORITATIVE, rename-safe, module-
 * distinct key for selecting a method/ability impl — dispatch must key on it,
 * never on the type NAME (two modules' `Widget` share a name but not a hash).
 *
 * Single source of truth for "what is this receiver's declaration hash?" — the
 * three method/ability resolution+emit sites (ExprMethodConstraints main and
 * inner-receiver paths, TypedAstBuilder) previously each read only VariantType
 * and TypeApplication, silently OMITTING RecordType. A `type X = {..}` receiver
 * is a RecordType carrying TypeIdentity.Declared, so omitting it dropped the
 * hash → those sites fell back to name-match-by-load-order (BUG E: two same-
 * named record types in different modules cross-dispatched). Covering all three
 * nominal shapes here fixes every consumer at once.
 */
fun declaredHashOf(type: MezzeType): Hash? = when (type) {
    is MezzeType.VariantType -> (type.identity as? TypeIdentity.Declared)?.hash
    is MezzeType.TypeApplication -> (type.identity as? TypeIdentity.Declared)?.hash
    is MezzeType.RecordType -> (type.identity as? TypeIdentity.Declared)?.hash
    else -> null
}

/**
 * BUG 1 — the identity of an APPLIED type, distinguishing two impls that share a
 * constructor but differ in their arguments (`Result PyForeign InteropError` vs
 * `Result JsForeign InteropError`). [declaredHashOf] returns the CONSTRUCTOR's
 * hash for both, so anything keyed on it alone collides and one impl silently
 * overwrites the other; the mangled string `Result_PyForeign_InteropError` was
 * the only thing keeping them apart, in violation of "key on structure, never on
 * a name".
 *
 * Returns null unless this is a type application with at least one CONCRETE
 * argument. A generic impl (`impl Result 'a 'e`) has only TypeVar args and keeps
 * its bare constructor hash — so the applied hash is a strictly MORE-SPECIFIC
 * key layered over [declaredHashOf], never a replacement for it. Callers try the
 * applied hash first and fall back; see [receiverDispatchHashes].
 *
 * Both sides of dispatch call this — ContextBuilder when filing an impl, the
 * resolvers when looking one up — so the two mint identical bytes by
 * construction, rather than by two parallel implementations staying in
 * agreement.
 */
fun appliedHashOf(type: MezzeType): Hash? {
    if (type !is MezzeType.TypeApplication) return null
    if (type.args.isEmpty() || type.args.all { it is MezzeType.VarType }) return null
    val constructorHash = declaredHashOf(type) ?: return null
    val tokens = type.args.map { argIdentityToken(it) ?: return null }
    return Hash.computeAppliedHash(constructorHash, tokens)
}

/**
 * The identity token for one type ARGUMENT: its declaration hash when it has
 * one, else its reserved builtin name.
 *
 * The name fallback is the same accepted rule as [receiverTypeName] — `Int`,
 * `Str` and `Seq` are declared nowhere, so their reserved name IS their identity
 * and there is no hash to prefer. A declared type always contributes its hash,
 * so two modules' same-named `Widget` yield different applied hashes. Returns
 * null for anything with neither (a type variable, a structural record), which
 * makes [appliedHashOf] decline rather than mint a key that could compare equal
 * to an unrelated type's.
 */
private fun argIdentityToken(arg: MezzeType): String? =
    declaredHashOf(arg)?.hex
        ?: when (arg) {
            is MezzeType.VarType -> null
            is MezzeType.RecordType -> null
            is MezzeType.TypeApplication -> appliedHashOf(arg)?.hex
            else -> receiverTypeName(arg)
        }

/**
 * The hashes dispatch can match a receiver against, in SPECIFICITY order —
 * most-specific FIRST, exactly parallel to [receiverTypeNames] but structural.
 * Callers take the first hit.
 *
 * For `Result PyForeign InteropError` that is [applied, constructor]: the
 * per-provider impl wins, and an `impl Result 'a 'e` filed under the bare
 * constructor hash stays reachable when no specific impl matches.
 */
fun receiverDispatchHashes(type: MezzeType): List<Hash> =
    listOfNotNull(appliedHashOf(type), declaredHashOf(type)).distinct()

/** True when [candidateHash] identifies a declaration this receiver dispatches to. */
fun receiverMatchesHash(receiver: MezzeType, candidateHash: Hash): Boolean =
    candidateHash in receiverDispatchHashes(receiver)

/**
 * The candidate whose target hash identifies [receiver]'s declaration, preferring
 * a specific `impl Result PyForeign IErr` over a generic `impl Result 'a 'e`.
 *
 * Every hash-keyed dispatch site goes through here so the specificity order is
 * decided once. Returns null when no candidate matches — an unresolved call, not
 * a licence to fall back to a name (a differently-hashed same-name impl is a
 * different declaration; that veto is the whole point of hashing).
 */
fun <T> pickByReceiverHash(receiver: MezzeType, candidates: List<T>, hashOf: (T) -> Hash?): T? {
    for (hash in receiverDispatchHashes(receiver)) {
        candidates.firstOrNull { hashOf(it) == hash }?.let { return it }
    }
    return null
}

/**
 * Nominal type name of a solved receiver — used to pick a deferred
 * inherent-impl / method-impl candidate by its `targetTypeName`.
 * Returns null for unresolved / structural types.
 *
 * Shared by ConstraintSolver, ExprMethodConstraints, and TypedAstBuilder;
 * previously three verbatim copies (see #160).
 */
fun receiverTypeName(type: MezzeType): String? = when (type) {
    is MezzeType.IntType -> "Int"
    is MezzeType.FloatType -> "Float"
    is MezzeType.StringType -> "Str"
    is MezzeType.FracType -> "Frac"
    is MezzeType.NewtypeType -> type.name
    is MezzeType.CharType -> "Char"
    is MezzeType.BytesType -> "Bytes"
    is MezzeType.TypeApplication -> type.constructor
    is MezzeType.RecordType -> type.identity.displayNameOrNull()
    is MezzeType.VariantType -> type.identity.displayNameOrNull()
        // Fallback for a hashless single-constructor variant: this is how a
        // record type (`type Pair = {..}`) reaches dispatch — a one-constructor
        // variant `| Pair {..} |` whose sole CONSTRUCTOR name IS the type name.
        // So the fallback is not a wrong "type-from-constructor" guess; for a
        // one-constructor type the two names coincide by construction. Same
        // accepted "hashless receiver → name is the only identity" rule as the
        // primitive/builtin arms above (name is a fallback, never a hash veto).
        ?: if (type.constructors.size == 1) type.constructors.keys.first() else null
    else -> null
}

/**
 * The candidate names dispatch can match this receiver against, in
 * SPECIFICITY order — most-specific FIRST. Callers pick the first hit.
 *
 * For a `TypeApplication` with concrete args (`Result Response HttpError`) we
 * ALSO produce the mangled form `Result_Response_HttpError` — the SAME shape
 * [AbilityImplFactory.typeNameOf] mints as the `targetTypeName` for
 * `impl Result Response HttpError`. So the specific impl wins over a same-
 * constructor generic impl (`impl Result 'a 'e`, registered as just
 * `"Result"`) when the receiver's args are known: dispatch sees the
 * concrete args and picks the concrete impl. When args are TypeVars (the
 * generic-impl receiver at that impl's own body), only the plain constructor
 * name is produced — matching `impl Result 'a 'e`'s registration.
 *
 * Same accepted "no hash → name is the identity fallback" rule as
 * [receiverTypeName]. Every non-TypeApplication kind produces exactly the
 * single name [receiverTypeName] returns (or an empty list when that is
 * null) — so this is a strict superset that never removes a hit.
 */
fun receiverTypeNames(type: MezzeType): List<String> = when (type) {
    is MezzeType.TypeApplication -> {
        val hasConcreteArg = type.args.any { it !is MezzeType.VarType }
        if (hasConcreteArg) listOf(mangledTypeAppName(type), type.constructor)
        else listOf(type.constructor)
    }
    else -> receiverTypeName(type)?.let { listOf(it) } ?: emptyList()
}

/**
 * Mangled name matching [com.mezze.language.typechecker.util.AbilityImplFactory.typeNameOf]
 * for a `TypeApplication`: constructor + args joined by `_`, recursing into
 * nested applications. Only produces a name for concrete-arg positions —
 * VarType args stay bare, so a partially-instantiated application never
 * spuriously matches a fully-mangled impl target. Container types
 * (`Seq/List/Set/Map`) match the `Seq_<Elem>` shape [AbilityImplFactory]
 * mints for them.
 */
private fun mangledTypeAppName(type: MezzeType.TypeApplication): String {
    val args = type.args.map { arg -> mangledArgName(arg) ?: "'?" }
    return if (args.isEmpty()) type.constructor
    else "${type.constructor}_${args.joinToString("_")}"
}

private fun mangledArgName(arg: MezzeType): String? = when (arg) {
    is MezzeType.TypeApplication -> mangledTypeAppName(arg)
    is MezzeType.VarType -> null
    else -> receiverTypeName(arg)
}
