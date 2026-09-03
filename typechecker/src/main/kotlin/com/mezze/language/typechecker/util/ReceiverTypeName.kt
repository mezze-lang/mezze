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
