package com.mezze.language.ast

/**
 * Central registry for primitive type name mappings.
 *
 * This is the single source of truth for mapping between:
 * - User-facing type names (e.g., "Str", "Int", "Bool")
 * - Internal MezzeType representations (e.g., MezzeType.StringType)
 *
 * All code that needs to convert between type names and MezzeType should use this registry.
 * This ensures consistency and makes renaming types trivial (change in one place).
 */
object PrimitiveTypes {

    /**
     * Maps user-facing primitive type names to their internal MezzeType representation.
     *
     * This is THE authoritative mapping. To rename a type (e.g., "String" -> "Str"),
     * just change it here and all code using this registry will pick up the change.
     */
    private val nameToType: Map<String, MezzeType> = mapOf(
        "Int" to MezzeType.IntType,
        "Float" to MezzeType.FloatType,
        "Bool" to MezzeType.BoolType,
        "Str" to MezzeType.StringType,
        "Nat" to MezzeType.NatType,
        "Frac" to MezzeType.FracType,
        "Char" to MezzeType.CharType,
        "Bytes" to MezzeType.BytesType,
        "ByteBuffer" to MezzeType.ByteBufferType,
        // Ordering is NOT a primitive — it's a variant type | LT | EQ | GT |
        // Kept in MezzeType as OrderingType for backward compat in when-branches
    )

    /**
     * Reverse mapping: internal MezzeType to user-facing name.
     */
    private val typeToName: Map<MezzeType, String> =
        nameToType.entries.associate { (name, type) -> type to name }

    /**
     * Convert a user-facing type name to its internal MezzeType.
     * Returns null if the name is not a primitive type.
     *
     * Example: fromName("Str") -> MezzeType.StringType
     */
    fun fromName(name: String): MezzeType? = nameToType[name]

    /**
     * Convert an internal MezzeType to its user-facing name.
     * Returns null if the type is not a primitive type.
     *
     * Example: toName(MezzeType.StringType) -> "Str"
     */
    fun toName(type: MezzeType): String? = typeToName[type]

    /**
     * Check if a name corresponds to a primitive type.
     */
    fun isPrimitiveName(name: String): Boolean = name in nameToType

    /**
     * Check if a type is a primitive type.
     */
    fun isPrimitiveType(type: MezzeType): Boolean = type in typeToName

    /**
     * All user-facing primitive type names.
     * Useful for error messages, suggestions, and validation.
     */
    val allNames: Set<String> = nameToType.keys

    /**
     * All primitive MezzeTypes.
     */
    val allTypes: Set<MezzeType> = typeToName.keys

    // toAnnotation removed -- IdentifierTypeAnnotation was in the deleted TypeAnnotation.kt

    /**
     * Numeric types that support arithmetic operations.
     */
    private val numericTypes: Set<MezzeType> = setOf(
        MezzeType.IntType,
        MezzeType.FloatType,
        MezzeType.NatType,
        MezzeType.FracType,
    )

    /**
     * Check if a type is a numeric type (Int, Float, Nat, Frac).
     */
    fun isNumericType(type: MezzeType): Boolean = type in numericTypes


    /**
     * Check if a type name matches a MezzeType.
     *
     * This is useful for checking if a type annotation name (e.g., "Int") corresponds
     * to a specific MezzeType (e.g., MezzeType.IntType).
     *
     * Example: matches("Str", MezzeType.StringType) -> true
     * Example: matches("Int", MezzeType.StringType) -> false
     * Example: matches("CustomType", MezzeType.IntType) -> false
     */
    fun matches(name: String, type: MezzeType): Boolean {
        val primitiveType = nameToType[name] ?: return false
        return primitiveType == type
    }

    /**
     * Extended name-to-type mapping that includes Ordering.
     * Ordering is not a true primitive but has a dedicated MezzeType representation.
     */
    private val extendedNameToType: Map<String, MezzeType> = nameToType + mapOf(
        "Ordering" to MezzeType.OrderingType
    )

    /**
     * Check if a type annotation name matches a built-in MezzeType, including:
     * - All primitive types (Int, Float, Str, Bool, Nat, Frac, Char, Bytes, ByteBuffer)
     * - Ordering (dedicated MezzeType but not a true primitive)
     * - Bool also matches VariantType with exactly {True, False} constructors (Bool variant pattern)
     *
     * This centralizes the pattern that was previously duplicated in 5+ files:
     *   "Int" -> type is MezzeType.IntType
     *   "Float" -> type is MezzeType.FloatType
     *   ...
     *   "Bool" -> type is MezzeType.BoolType || isBoolVariant(type)
     *   "Ordering" -> type is MezzeType.OrderingType
     *
     * Returns null if the name is not a built-in type (caller should handle non-primitive types).
     * Returns true/false if the name IS a built-in type and the match succeeded/failed.
     */
    fun matchesBuiltinType(name: String, type: MezzeType): Boolean? {
        val builtinType = extendedNameToType[name] ?: return null
        if (builtinType == type) return true
        // Special case: Bool also matches the {True, False} variant pattern
        if (builtinType is MezzeType.BoolType && isBoolVariant(type)) return true
        return false
    }

    /**
     * Check if a MezzeType is the Bool variant pattern: VariantType with exactly {True, False}
     * constructors, both with no payload, and closed (no row variable).
     */
    fun isBoolVariant(type: MezzeType): Boolean {
        if (type !is MezzeType.VariantType) return false
        val constructors = type.constructors
        return constructors.size == 2 &&
               constructors.containsKey("True") && constructors["True"] == null &&
               constructors.containsKey("False") && constructors["False"] == null &&
               type.rowVar == null
    }

}
