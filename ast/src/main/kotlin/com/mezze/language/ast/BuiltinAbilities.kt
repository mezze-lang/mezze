package com.mezze.language.ast

import kotlinx.serialization.Serializable

/**
 * Built-in ability system for the Mezze language.
 *
 * This file contains all built-in abilities and type utilities:
 * - BuiltinAbilities: All built-in abilities (Eq, Ord, NumAdd, etc.)
 * - BuiltinTypes: Built-in type constructor utilities
 *
 * Part of the refactored AST module.
 */

// ============================================================================
// BUILT-IN ABILITIES
// ============================================================================

/**
 * Built-in abilities that are always available in the language
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
    const val NUM_NEG = "NumNeg"

    // Logical abilities
    const val LOGICAL_AND = "LogicalAnd"
    const val LOGICAL_OR = "LogicalOr"
    const val LOGICAL_NOT = "LogicalNot"

    // Inspect ability
    const val INSPECT = "Inspect"

    // Serialization abilities (used by codec system)
    const val ENCODE = "Encode"
    const val DECODE = "Decode"
    const val STREAM_ENCODE = "StreamEncode"
    const val STREAM_DECODE = "StreamDecode"
    const val FORMAT = "Format"  // was ENCODE_FORMAT
    const val DECODE_FORMAT = "DecodeFormat"
    const val STREAM_FORMAT = "StreamFormat"  // was STREAM_ENCODE_FORMAT

    // Other stdlib abilities
    const val SHOW = "Show"
    const val CONVERT = "Convert"

    // All core built-in abilities (operators and basic operations)
    val ALL = setOf(EQ, ORD, NUM_ADD, NUM_SUB, NUM_MUL, NUM_DIV, NUM_MOD, NUM_NEG, LOGICAL_AND, LOGICAL_OR, LOGICAL_NOT, INSPECT)

    /**
     * Check if an ability name is a built-in ability
     */
    fun isBuiltin(abilityName: String): Boolean = abilityName in ALL

    /**
     * Automatic ability implementations for built-in types
     * Maps type to the set of abilities it automatically implements
     */
    private val automaticImplementations = mapOf(
        // Primitive types
        "Int" to setOf(EQ, ORD, INSPECT, NUM_ADD, NUM_SUB, NUM_MUL, NUM_DIV, NUM_MOD, NUM_NEG),
        "Float" to setOf(EQ, ORD, INSPECT, NUM_ADD, NUM_SUB, NUM_MUL, NUM_DIV, NUM_NEG),
        "Str" to setOf(EQ, ORD, INSPECT),
        "Bool" to setOf(EQ, ORD, INSPECT, LOGICAL_AND, LOGICAL_OR, LOGICAL_NOT),
        "Nat" to setOf(EQ, ORD, INSPECT, NUM_ADD, NUM_MUL, NUM_DIV, NUM_MOD),
        "Frac" to setOf(EQ, ORD, INSPECT, NUM_ADD, NUM_SUB, NUM_MUL, NUM_DIV, NUM_NEG),

        // Built-in core types (conditional implementations based on element types)
        "Option" to setOf(EQ, ORD, INSPECT), // if element type has these abilities
        "Result" to setOf(EQ, ORD, INSPECT), // if both error and value types have these abilities
        "List" to setOf(EQ, ORD, INSPECT),   // if element type has these abilities
        "Map" to setOf(EQ, INSPECT),         // if key and value types have these abilities
        "Set" to setOf(EQ, INSPECT),         // if element type has these abilities
        "Ordering" to setOf(EQ, ORD, INSPECT)
    )

    /**
     * Check if a built-in type automatically implements an ability
     */
    fun hasAutomaticImplementation(typeName: String, abilityName: String): Boolean {
        return automaticImplementations[typeName]?.contains(abilityName) == true
    }

    /**
     * Get all abilities that a built-in type automatically implements
     */
    fun getAutomaticImplementations(typeName: String): Set<String> {
        return automaticImplementations[typeName] ?: emptySet()
    }

    /**
     * Check if a MezzeType automatically implements an ability
     * This handles both primitive types and parameterized built-in types
     */
    fun typeHasAutomaticImplementation(type: MezzeType, abilityName: String): Boolean {
        return when (type) {
            is MezzeType.IntType -> hasAutomaticImplementation("Int", abilityName)
            is MezzeType.FloatType -> hasAutomaticImplementation("Float", abilityName)
            is MezzeType.StringType -> hasAutomaticImplementation("Str", abilityName)
            is MezzeType.BoolType -> hasAutomaticImplementation("Bool", abilityName)
            is MezzeType.NatType -> hasAutomaticImplementation("Nat", abilityName)
            is MezzeType.FracType -> hasAutomaticImplementation("Frac", abilityName)
            is MezzeType.OrderingType -> hasAutomaticImplementation("Ordering", abilityName)

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

// ============================================================================
// BUILT-IN TYPE UTILITIES
// ============================================================================

/**
 * Built-in type constructors and utilities
 */
object BuiltinTypes {

    // ========================================================================
    // TYPE NAME CONSTANTS
    // ========================================================================

    const val OPTION = "Option"
    const val RESULT = "Result"
    const val LIST = "List"
    const val MAP = "Map"
    const val SET = "Set"

    /**
     * Built-in type constructor names
     */
    val coreTypeNames = setOf(OPTION, RESULT, LIST, MAP, SET)
    val primitiveTypeNames = setOf("Int", "Float", "Str", "Bool", "Nat", "Frac", "Ordering")
    val allBuiltinTypeNames = coreTypeNames + primitiveTypeNames

    // ========================================================================
    // CONSTRUCTOR NAME CONSTANTS
    // ========================================================================

    // Option constructors
    const val SOME = "Some"
    const val NONE = "None"

    // Result constructors
    const val OK = "Ok"
    const val ERR = "Err"

    // Standard record field names for built-in types
    const val VAL_FIELD = "val"      // Used in Option.Some and Result.Ok
    const val ERROR_FIELD = "error"  // Used in Result.Err

    /**
     * Built-in constructor names for Option
     */
    val optionConstructors = setOf(SOME, NONE)

    /**
     * Built-in constructor names for Result
     */
    val resultConstructors = setOf(OK, ERR)

    /**
     * Built-in constructor names for List
     */
    val listConstructors = setOf("Nil", "Cons") // Internal representation

    /**
     * Built-in constructor names for Ordering
     */
    val orderingConstructors = setOf("LT", "EQ", "GT")

    /**
     * All built-in constructors
     */
    val allBuiltinConstructors = optionConstructors + resultConstructors +
                                listConstructors + orderingConstructors

    /**
     * Check if a type name is a built-in type
     */
    fun isBuiltinTypeName(typeName: String): Boolean {
        return typeName in allBuiltinTypeNames
    }

    /**
     * Check if a constructor name is a built-in constructor
     */
    fun isBuiltinConstructor(constructorName: String): Boolean {
        return constructorName in allBuiltinConstructors
    }

    /**
     * Get the type name for a built-in constructor
     */
    fun getTypeForConstructor(constructorName: String): String? {
        return when (constructorName) {
            in optionConstructors -> "Option"
            in resultConstructors -> "Result"
            in listConstructors -> "List"
            in orderingConstructors -> "Ordering"
            else -> null
        }
    }

    /**
     * Create built-in type instances
     */
    fun createOptionType(elementType: MezzeType): MezzeType.TypeApplication {
        return MezzeType.TypeApplication("Option", listOf(elementType))
    }

    fun createResultType(valueType: MezzeType, errorType: MezzeType): MezzeType.TypeApplication {
        return MezzeType.TypeApplication("Result", listOf(valueType, errorType))
    }

    fun createListType(elementType: MezzeType): MezzeType.ListType {
        return MezzeType.ListType(elementType)
    }

    fun createMapType(keyType: MezzeType, valueType: MezzeType): MezzeType.MapType {
        return MezzeType.MapType(keyType, valueType)
    }

    fun createSetType(elementType: MezzeType): MezzeType.SetType {
        return MezzeType.SetType(elementType)
    }

    /**
     * Check if a variant type is the Bool variant: | True, False |
     *
     * Bool is represented as a closed variant with exactly two constructors (True and False),
     * both with no payloads.
     */
    fun isBoolVariant(type: MezzeType): Boolean {
        return type is MezzeType.VariantType &&
               type.constructors.size == 2 &&
               type.constructors.containsKey("True") &&
               type.constructors.containsKey("False") &&
               type.constructors["True"] == null &&
               type.constructors["False"] == null
    }

    /**
     * Get the canonical Bool type.
     */
    fun getBoolType(): MezzeType.VariantType {
        return MezzeType.VariantType(
            constructors = mapOf("True" to null, "False" to null),
            rowVar = null
        )
    }

    /**
     * Check if a variant type represents the Option type: | Some { val }, None |
     *
     * Option is a closed variant with exactly two constructors:
     * - Some: with a payload (typically { val: T })
     * - None: with no payload
     */
    fun isOptionVariant(type: MezzeType): Boolean {
        return type is MezzeType.VariantType &&
               type.constructors.size == 2 &&
               type.constructors.containsKey("Some") &&
               type.constructors.containsKey("None") &&
               type.constructors["None"] == null &&
               !type.isOpen
    }

    /**
     * Extract the value type from an Option's Some constructor.
     *
     * For Option 'a, returns 'a (the type parameter).
     * Returns null if the type is not a valid Option variant.
     */
    fun getOptionValueType(type: MezzeType.VariantType): MezzeType? {
        if (!isOptionVariant(type)) return null
        val someType = type.constructors["Some"] ?: return null
        return when (someType) {
            is MezzeType.RecordType -> someType.fields["val"]
            else -> someType
        }
    }

    /**
     * Check if a variant type represents the Result type: | Ok { val }, Err { error } |
     *
     * Result is a closed variant with exactly two constructors:
     * - Ok: with a payload { val: T }
     * - Err: with a payload { error: E }
     */
    fun isResultVariant(type: MezzeType): Boolean {
        if (type !is MezzeType.VariantType) return false
        if (type.constructors.size != 2 || type.isOpen) return false

        val okType = type.constructors["Ok"] ?: return false
        val errType = type.constructors["Err"] ?: return false

        // Both Ok and Err must have record payloads with specific fields
        val okRecord = okType as? MezzeType.RecordType ?: return false
        val errRecord = errType as? MezzeType.RecordType ?: return false

        return okRecord.fields.containsKey("val") && errRecord.fields.containsKey("error")
    }

    /**
     * Extract the value and error types from a Result variant.
     *
     * For Result 'a 'e, returns Pair('a, 'e).
     * Returns null if the type is not a valid Result variant.
     */
    fun getResultTypes(type: MezzeType.VariantType): Pair<MezzeType, MezzeType>? {
        if (type.constructors.size != 2 || type.isOpen) return null

        val okType = type.constructors["Ok"] ?: return null
        val errType = type.constructors["Err"] ?: return null

        // Extract value type from Ok { val: T }
        val valueType = when (okType) {
            is MezzeType.RecordType -> okType.fields["val"]
            else -> null
        } ?: return null

        // Extract error type from Err { error: E }
        val errorType = when (errType) {
            is MezzeType.RecordType -> errType.fields["error"]
            else -> null
        } ?: return null

        return Pair(valueType, errorType)
    }
}
