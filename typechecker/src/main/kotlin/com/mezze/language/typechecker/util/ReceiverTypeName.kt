package com.mezze.language.typechecker.util

import com.mezze.language.ast.MezzeType

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
    is MezzeType.NatType -> "Nat"
    is MezzeType.FracType -> "Frac"
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
