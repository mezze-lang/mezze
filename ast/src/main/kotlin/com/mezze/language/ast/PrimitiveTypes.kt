package com.mezze.language.ast

object PrimitiveTypes {

    // Canonical source-level names for the primitive types. Single source so callers
    // key on these constants instead of re-typing the literal (e.g. BuiltinAbilities).
    const val INT = "Int"
    const val FLOAT = "Float"
    const val BOOL = "Bool"
    const val STR = "Str"
    const val NAT = "Nat"
    const val FRAC = "Frac"
    const val CHAR = "Char"
    const val BYTES = "Bytes"
    const val BYTE_BUFFER = "ByteBuffer"

    private val nameToType: Map<String, MezzeType> = mapOf(
        INT to MezzeType.IntType,
        FLOAT to MezzeType.FloatType,
        BOOL to MezzeType.BoolType,
        STR to MezzeType.StringType,
        NAT to MezzeType.NatType,
        FRAC to MezzeType.FracType,
        CHAR to MezzeType.CharType,
        BYTES to MezzeType.BytesType,
        BYTE_BUFFER to MezzeType.ByteBufferType,
    )

    private val typeToName: Map<MezzeType, String> =
        nameToType.entries.associate { (name, type) -> type to name }

    fun fromName(name: String): MezzeType? = nameToType[name]
    fun toName(type: MezzeType): String? = typeToName[type]
    fun isPrimitiveName(name: String): Boolean = name in nameToType
    fun isPrimitiveType(type: MezzeType): Boolean = type in typeToName

    val allNames: Set<String> = nameToType.keys
    val allTypes: Set<MezzeType> = typeToName.keys

    private val numericTypes: Set<MezzeType> = setOf(
        MezzeType.IntType,
        MezzeType.FloatType,
        MezzeType.NatType,
        MezzeType.FracType,
    )

    fun isNumericType(type: MezzeType): Boolean = type in numericTypes

    fun matches(name: String, type: MezzeType): Boolean {
        val primitiveType = nameToType[name] ?: return false
        return primitiveType == type
    }

    // Returns null if not a built-in type, true/false if it is and matches/doesn't
    fun matchesBuiltinType(name: String, type: MezzeType): Boolean? {
        val builtinType = nameToType[name] ?: return null
        if (builtinType == type) return true
        if (builtinType is MezzeType.BoolType && isBoolVariant(type)) return true
        return false
    }

    fun isBoolVariant(type: MezzeType): Boolean {
        if (type !is MezzeType.VariantType) return false
        val constructors = type.constructors
        return constructors.size == 2 &&
               constructors.containsKey(BuiltinTypes.TRUE) && constructors[BuiltinTypes.TRUE] == null &&
               constructors.containsKey(BuiltinTypes.FALSE) && constructors[BuiltinTypes.FALSE] == null &&
               type.rowVar == null
    }

}
