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
    is MezzeType.BoolType -> "Bool"
    is MezzeType.FracType -> "Frac"
    is MezzeType.NewtypeType -> type.name
    is MezzeType.CharType -> "Char"
    is MezzeType.BytesType -> "Bytes"
    is MezzeType.SeqType -> "Seq"
    is MezzeType.ListType -> "List"
    is MezzeType.SetType -> "Set"
    is MezzeType.MapType -> "Map"
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
    is MezzeType.SeqType -> mangledArgName(arg.elementType)?.let { "Seq_$it" }
    is MezzeType.ListType -> mangledArgName(arg.elementType)?.let { "List_$it" }
    is MezzeType.SetType -> mangledArgName(arg.elementType)?.let { "Set_$it" }
    is MezzeType.MapType -> {
        val k = mangledArgName(arg.keyType); val v = mangledArgName(arg.valueType)
        if (k != null && v != null) "Map_${k}_$v" else null
    }
    is MezzeType.VarType -> null
    else -> receiverTypeName(arg)
}
