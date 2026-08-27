package com.mezze.language.ast

import kotlinx.serialization.Serializable

/**
 * Built-in abilities that are always available in the language.
 */
object BuiltinAbilities {
    // Comparison abilities
    const val EQ = "Eq"
    const val ORD = "Ord"

    // Numeric abilities
    const val NUM_ADD = "NumAdd"
    const val NUM_SUB = "NumSub"
    const val NUM_MUL = "NumMul"
    const val NUM_DIV = "NumDiv"
    const val NUM_MOD = "NumMod"
    // Logical abilities
    const val LOGICAL_AND = "LogicalAnd"
    const val LOGICAL_OR = "LogicalOr"

    // Inspect ability
    const val INSPECT = "Inspect"

    // Default/Zero/One abilities
    const val DEFAULT = "Default"
    const val NUM_ZERO = "NumZero"
    const val NUM_ONE = "NumOne"

    // Conversion abilities
    const val INTO = "Into"
    const val TRY_INTO = "TryInto"

    // Serialization abilities (used by codec system)
    const val ENCODE = "Encode"
    const val ENCODE_REPR = "EncodeRepr"
    const val DECODE = "Decode"
    const val DECODE_REPR = "DecodeRepr"
    const val STREAM_ENCODE = "StreamEncode"
    const val STREAM_DECODE = "StreamDecode"
    const val FORMAT = "Format"
    const val DECODE_FORMAT = "DecodeFormat"
    const val STREAM_FORMAT = "StreamFormat"
    const val STREAM_DECODE_FORMAT = "StreamDecodeFormat"

    /** All codec-related ability names */
    val CODEC_ABILITIES = setOf(
        ENCODE, ENCODE_REPR, DECODE, DECODE_REPR,
        STREAM_ENCODE, STREAM_DECODE,
        FORMAT, DECODE_FORMAT, STREAM_FORMAT, STREAM_DECODE_FORMAT
    )

    /** Encode-side codec abilities (data → repr) */
    val ENCODE_ABILITIES = setOf(ENCODE, ENCODE_REPR)

    /** Decode-side codec abilities (repr → data) */
    val DECODE_ABILITIES = setOf(DECODE, DECODE_REPR)

    /** All user-facing codec abilities (auto-derived by CodecGenerator) */
    val AUTO_DERIVE_CODEC_ABILITIES = setOf(ENCODE, ENCODE_REPR, DECODE, DECODE_REPR)

    /**
     * Automatic ability implementations for built-in types
     * Maps type to the set of abilities it automatically implements
     */
    private val automaticImplementations = mapOf(
        // Primitive types
        PrimitiveTypes.INT to setOf(EQ, ORD, INSPECT, NUM_ADD, NUM_SUB, NUM_MUL, NUM_DIV, NUM_MOD),
        PrimitiveTypes.FLOAT to setOf(EQ, ORD, INSPECT, NUM_ADD, NUM_SUB, NUM_MUL, NUM_DIV),
        PrimitiveTypes.STR to setOf(EQ, ORD, INSPECT),
        PrimitiveTypes.BOOL to setOf(EQ, ORD, INSPECT, LOGICAL_AND, LOGICAL_OR),
        PrimitiveTypes.NAT to setOf(EQ, ORD, INSPECT, NUM_ADD, NUM_MUL, NUM_DIV, NUM_MOD),
        PrimitiveTypes.FRAC to setOf(EQ, ORD, INSPECT, NUM_ADD, NUM_SUB, NUM_MUL, NUM_DIV),

        // Built-in core types (conditional implementations based on element types)
        BuiltinTypes.OPTION to setOf(EQ, ORD, INSPECT), // if element type has these abilities
        BuiltinTypes.RESULT to setOf(EQ, ORD, INSPECT), // if both error and value types have these abilities
        BuiltinTypes.LIST to setOf(EQ, ORD, INSPECT),   // if element type has these abilities
        BuiltinTypes.MAP to setOf(EQ, INSPECT),         // if key and value types have these abilities
        BuiltinTypes.SET to setOf(EQ, INSPECT),         // if element type has these abilities
    )

    /**
     * Check if a built-in type automatically implements an ability
     */
    fun hasAutomaticImplementation(typeName: String, abilityName: String): Boolean {
        return automaticImplementations[typeName]?.contains(abilityName) == true
    }

    /**
     * Check if a MezzeType automatically implements an ability
     * This handles both primitive types and parameterized built-in types
     */
    fun typeHasAutomaticImplementation(type: MezzeType, abilityName: String): Boolean {
        return when (type) {
            is MezzeType.IntType -> hasAutomaticImplementation(PrimitiveTypes.INT, abilityName)
            is MezzeType.FloatType -> hasAutomaticImplementation(PrimitiveTypes.FLOAT, abilityName)
            is MezzeType.StringType -> hasAutomaticImplementation(PrimitiveTypes.STR, abilityName)
            is MezzeType.BoolType -> hasAutomaticImplementation(PrimitiveTypes.BOOL, abilityName)
            is MezzeType.NatType -> hasAutomaticImplementation(PrimitiveTypes.NAT, abilityName)
            is MezzeType.FracType -> hasAutomaticImplementation(PrimitiveTypes.FRAC, abilityName)

            // Parameterized types - check if element types support the ability
            is MezzeType.TypeApplication -> {
                val typeName = type.constructor
                hasAutomaticImplementation(typeName, abilityName) &&
                type.args.all { typeHasAutomaticImplementation(it, abilityName) }
            }
            is MezzeType.ListType -> {
                hasAutomaticImplementation("List", abilityName) &&
                typeHasAutomaticImplementation(type.elementType, abilityName)
            }
            is MezzeType.MapType -> {
                hasAutomaticImplementation("Map", abilityName) &&
                typeHasAutomaticImplementation(type.keyType, abilityName) &&
                typeHasAutomaticImplementation(type.valueType, abilityName)
            }
            is MezzeType.SetType -> {
                hasAutomaticImplementation("Set", abilityName) &&
                typeHasAutomaticImplementation(type.elementType, abilityName)
            }

            // Record types automatically derive Eq and Inspect if all fields have these abilities
            is MezzeType.RecordType -> {
                when (abilityName) {
                    EQ, INSPECT -> type.fields.values.all { fieldType ->
                        typeHasAutomaticImplementation(fieldType, abilityName)
                    }
                    else -> false
                }
            }

            // Variant types automatically derive Eq and Inspect if all constructors have these abilities
            is MezzeType.VariantType -> {
                when (abilityName) {
                    EQ, INSPECT -> type.constructors.values.all { constructorDataType ->
                        // Constructors without data (like None) always support Eq and Inspect
                        constructorDataType == null || typeHasAutomaticImplementation(constructorDataType, abilityName)
                    }
                    else -> false
                }
            }

            else -> false
        }
    }
}
