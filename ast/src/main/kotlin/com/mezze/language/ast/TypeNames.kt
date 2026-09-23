package com.mezze.language.ast

/**
 * The name of a type's HEAD, asked once.
 *
 * Several passes needed "what is this type called?" and each answered it with its
 * own `when`, ending in `else -> type::class.simpleName`. That fallback is the
 * problem: it leaks Kotlin class names into places that are supposed to speak
 * Mezze. Two live examples, both fixed by this file —
 *
 *  - `AbilityChecker.typeHeadName` listed Int/Float/Str/Bool by hand and forgot
 *    Frac, Char and Bytes, so a diagnostic about a `Frac` said `FracType`.
 *  - the LSP's `typeConstructorName` used `simpleName.removeSuffix("Type")`,
 *    which renders `MezzeType.StringType` as `String` — a type name Mezze does
 *    not have, so the go-to-definition lookup it fed was searching for a
 *    declaration that cannot exist.
 *
 * Both `when`s here are exhaustive and neither has an `else`, so a new
 * [MezzeType] variant is a compile error in this file before it can be a wrong
 * name somewhere else.
 */
object TypeNames {

    /**
     * A display name for the type's head — always a name a Mezze programmer would
     * recognise, never null, never a Kotlin class name.
     *
     * Structural types get a bracketed placeholder rather than their contents:
     * this answers "what KIND of thing is this", and the full rendering is
     * [prettyPrintType]'s job.
     */
    fun head(type: MezzeType): String {
        PrimitiveTypes.toName(type)?.let { return it }
        return when (type) {
            is MezzeType.TypeApplication -> type.constructor
            is MezzeType.NewtypeType -> type.name
            is MezzeType.VariantType -> type.identity.displayNameOrNull() ?: "|variant|"
            is MezzeType.RecordType -> type.identity.displayNameOrNull() ?: "{record}"
            is MezzeType.FunctionType -> "{fn}"
            is MezzeType.VarType -> type.variable.name
            is MezzeType.EffectRow -> "<effects>"
            // Every primitive is answered by PrimitiveTypes.toName above; listing
            // them keeps the `when` exhaustive without an `else`, so adding a
            // MezzeType variant fails here rather than silently rendering as a
            // Kotlin class name.
            is MezzeType.IntType, is MezzeType.FloatType, is MezzeType.StringType,
            is MezzeType.BoolType, is MezzeType.FracType, is MezzeType.BytesType,
            is MezzeType.CharType -> PrimitiveTypes.toName(type) ?: "?"
        }
    }

    /**
     * The name of the DECLARATION this type refers to, or null when it refers to
     * none.
     *
     * Use this — not [head] — to look a declaration up. A record, a function and
     * a bare type variable have no declaration to find, and [head]'s placeholders
     * for them (`{record}`, `{fn}`) are display text that would never resolve.
     */
    fun nominal(type: MezzeType): String? = when (type) {
        is MezzeType.TypeApplication -> type.constructor
        is MezzeType.NewtypeType -> type.name
        is MezzeType.VariantType -> type.identity.displayNameOrNull()
        is MezzeType.RecordType -> type.identity.displayNameOrNull()
        is MezzeType.IntType, is MezzeType.FloatType, is MezzeType.StringType,
        is MezzeType.BoolType, is MezzeType.FracType, is MezzeType.BytesType,
        is MezzeType.CharType -> PrimitiveTypes.toName(type)
        is MezzeType.FunctionType, is MezzeType.VarType, is MezzeType.EffectRow -> null
    }
}
