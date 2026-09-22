package com.mezze.language.ast

object PrimitiveTypes {

    // Canonical source-level names for the primitive types. Single source so callers
    // key on these constants instead of re-typing the literal (e.g. BuiltinAbilities).
    //
    // EVERY constant here round-trips through `nameToType` below. `Nat` deliberately
    // has no constant: it is NOT a primitive, it is a stdlib newtype over Int
    // (`pub newtype Nat = Int`, stdlib/std/nat.mz), materialised at literal sites by
    // `natLiteralType()` in ExprLiteralConstraints. A `NAT` constant used to sit in
    // this list with no map entry and no callers, so `fromName(NAT)` returned null
    // and `isPrimitiveName(NAT)` was false — a constant that read as authoritative
    // and answered nothing. If you need Nat's name, take it from its declaration.
    const val INT = "Int"
    const val FLOAT = "Float"
    const val BOOL = "Bool"
    const val STR = "Str"
    const val FRAC = "Frac"
    const val CHAR = "Char"
    const val BYTES = "Bytes"

    private val nameToType: Map<String, MezzeType> = mapOf(
        INT to MezzeType.IntType,
        FLOAT to MezzeType.FloatType,
        BOOL to MezzeType.BoolType,
        STR to MezzeType.StringType,
        FRAC to MezzeType.FracType,
        CHAR to MezzeType.CharType,
        BYTES to MezzeType.BytesType,
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
