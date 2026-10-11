package com.mezze.language.typechecker.codec

import com.mezze.language.ast.BuiltinTypes
import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.PrimitiveTypes

/**
 * The six Tier-2 codec shape families. A value's TOP-LEVEL shape selects
 * exactly one, which in turn names the Tier-2 ability a format must implement
 * (`"${prefix}${suffix}"`, e.g. `EncodeSequence`) and the family of
 * `config.<method>` calls the generator emits (`encode_sequence`, or the bulk
 * `encode_int_seq` fast path).
 */
enum class Tier2Shape(val abilitySuffix: String) {
    SCALAR("Scalar"),
    RECORD("Record"),
    VARIANT("Variant"),
    SEQUENCE("Sequence"),
    MAPPING("Mapping"),
    OPTION("Option"),
}

/**
 * Single source of truth for the shape → Tier-2 family decision.
 *
 * Both the capability CHECK (`AbilityChecker.walkTier2Capabilities`, which
 * decides which `Encode<Family>`/`Decode<Family>` ability a format must have)
 * and the EMIT (`CodecGenerator`, which picks the `config.encode_*`/`decode_*`
 * method) must agree on this classification, or a value encodes through one
 * shape and the check demands a different one. The two sites historically
 * drifted here — e.g. whether a `Set` is a `Sequence` — so the decision lives
 * in ONE place. The check consumes [of] directly; the emit is pinned against it
 * by `CodecCheckEmitMirrorTest`.
 *
 * Returns null for a type that has no Tier-2 call at this level: `VarType`,
 * `FunctionType`, `EffectRow`, a non-`Nat` `NewtypeType` (not auto-derivable),
 * or a user-`Declared` `TypeApplication` that must be expanded to its
 * underlying record/variant before it is classified.
 */
object Tier2Classifier {
    fun of(type: MezzeType): Tier2Shape? {
        // Bool spelled as the builtin `| True, False |` variant folds into the
        // scalar bool hook (encode_bool), NOT the variant hook.
        if (PrimitiveTypes.isBoolVariant(type)) return Tier2Shape.SCALAR
        // `Nat` is a builtin scalar spelled as a newtype — routed to
        // encode_nat/decode_nat (Scalar), not the newtype no-op.
        if (isNat(type)) return Tier2Shape.SCALAR
        // `Option` spelled structurally (`| Some { val }, None |`) uses the
        // Option hook, the same as its `TypeApplication` spelling — NOT Variant.
        if (BuiltinTypes.isOptionVariant(type)) return Tier2Shape.OPTION

        return when (type) {
            is MezzeType.IntType, is MezzeType.FloatType, is MezzeType.StringType,
            is MezzeType.FracType, is MezzeType.CharType,
            is MezzeType.BytesType -> Tier2Shape.SCALAR

            is MezzeType.RecordType -> Tier2Shape.RECORD
            is MezzeType.VariantType -> Tier2Shape.VARIANT

            is MezzeType.TypeApplication -> when (type.constructor) {
                BuiltinTypes.OPTION -> Tier2Shape.OPTION
                // All linear containers share the one Sequence wire shape; the
                // specific container is rebuilt by the walker / converted to a
                // seq by the emitter.
                BuiltinTypes.LIST, BuiltinTypes.SEQ,
                BuiltinTypes.SET, BuiltinTypes.SORTED_SET -> Tier2Shape.SEQUENCE
                // Both map flavours share the one Mapping wire shape.
                BuiltinTypes.MAP, BuiltinTypes.SORTED_MAP -> Tier2Shape.MAPPING
                else -> null
            }

            else -> null
        }
    }

    private fun isNat(type: MezzeType): Boolean =
        (type is MezzeType.NewtypeType && type.name == BuiltinTypes.NAT) ||
            (type is MezzeType.TypeApplication && type.constructor == BuiltinTypes.NAT)
}
