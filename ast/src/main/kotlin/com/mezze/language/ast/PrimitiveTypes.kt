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

    /**
     * Convert an internal MezzeType to an IdentifierTypeAnnotation.
     * Returns null if the type is not a primitive type.
     *
     * This is a convenience method that combines toName() with IdentifierTypeAnnotation creation.
     * Example: toAnnotation(MezzeType.StringType) -> IdentifierTypeAnnotation("Str")
     */
    fun toAnnotation(type: MezzeType): IdentifierTypeAnnotation? =
        toName(type)?.let { IdentifierTypeAnnotation(it) }

    // ========================================================================
    // TYPE CATEGORIES
    // ========================================================================

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
     * All numeric type names.
     */
    val numericTypeNames: Set<String> = numericTypes.mapNotNull { toName(it) }.toSet()

    // ========================================================================
    // TYPE MATCHING
    // ========================================================================

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
}
