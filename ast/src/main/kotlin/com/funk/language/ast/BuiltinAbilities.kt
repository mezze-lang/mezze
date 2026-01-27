package com.funk.language.ast

import kotlinx.serialization.Serializable

/**
 * Built-in ability system for the Funk language.
 *
 * This file contains all built-in abilities, operators, and type utilities:
 * - BuiltinAbilities: All built-in abilities (Eq, Ord, NumAdd, etc.)
 * - BuiltinOperators: Operator to ability mappings
 * - BuiltinAbilityDefinitions: Ability interface definitions
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

    // All built-in abilities
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
        "String" to setOf(EQ, ORD, INSPECT),
        "Boolean" to setOf(EQ, ORD, INSPECT, LOGICAL_AND, LOGICAL_OR, LOGICAL_NOT),
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
     * Check if a FunkType automatically implements an ability
     * This handles both primitive types and parameterized built-in types
     */
    fun typeHasAutomaticImplementation(type: FunkType, abilityName: String): Boolean {
        return when (type) {
            is FunkType.IntType -> hasAutomaticImplementation("Int", abilityName)
            is FunkType.FloatType -> hasAutomaticImplementation("Float", abilityName)
            is FunkType.StringType -> hasAutomaticImplementation("String", abilityName)
            is FunkType.BooleanType -> hasAutomaticImplementation("Boolean", abilityName)
            is FunkType.NatType -> hasAutomaticImplementation("Nat", abilityName)
            is FunkType.FracType -> hasAutomaticImplementation("Frac", abilityName)
            is FunkType.OrderingType -> hasAutomaticImplementation("Ordering", abilityName)

            // Parameterized types - check if element types support the ability
            is FunkType.TypeApplication -> {
                val typeName = type.constructor
                hasAutomaticImplementation(typeName, abilityName) &&
                type.args.all { typeHasAutomaticImplementation(it, abilityName) }
            }
            is FunkType.ListType -> {
                hasAutomaticImplementation("List", abilityName) &&
                typeHasAutomaticImplementation(type.elementType, abilityName)
            }
            is FunkType.MapType -> {
                hasAutomaticImplementation("Map", abilityName) &&
                typeHasAutomaticImplementation(type.keyType, abilityName) &&
                typeHasAutomaticImplementation(type.valueType, abilityName)
            }
            is FunkType.SetType -> {
                hasAutomaticImplementation("Set", abilityName) &&
                typeHasAutomaticImplementation(type.elementType, abilityName)
            }

            // Record types automatically derive Eq and Inspect if all fields have these abilities
            is FunkType.RecordType -> {
                when (abilityName) {
                    EQ, INSPECT -> type.fields.values.all { fieldType ->
                        typeHasAutomaticImplementation(fieldType, abilityName)
                    }
                    else -> false
                }
            }

            // Variant types automatically derive Eq and Inspect if all constructors have these abilities
            is FunkType.VariantType -> {
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

/**
 * Built-in ability interface definitions
 * These define the structure and operations for each built-in ability
 */
object BuiltinAbilityDefinitions {

    /**
     * Equality ability - provides equals operation
     */
    data class EqAbility(
        val equals: String = "equals" // { l: 'a, r: 'a } -> Boolean
    )

    /**
     * Ordering ability - provides comparison operations
     */
    data class OrdAbility(
        val compare: String = "compare",        // { l: 'a, r: 'a } -> Ordering
        val lessThan: String = "less_than",     // { l: 'a, r: 'a } -> Boolean
        val greaterThan: String = "greater_than" // { l: 'a, r: 'a } -> Boolean
    )

    /**
     * Numeric addition ability
     */
    data class NumAddAbility(
        val add: String = "add" // { l: 'a, r: 'a } -> 'a
    )

    /**
     * Numeric subtraction ability
     */
    data class NumSubAbility(
        val subtract: String = "subtract" // { l: 'a, r: 'a } -> 'a
    )

    /**
     * Numeric multiplication ability
     */
    data class NumMulAbility(
        val multiply: String = "multiply" // { l: 'a, r: 'a } -> 'a
    )

    /**
     * Numeric division ability
     */
    data class NumDivAbility(
        val divide: String = "divide" // { l: 'a, r: 'a } -> 'a
    )

    /**
     * Numeric modulo ability
     */
    data class NumModAbility(
        val modulo: String = "modulo" // { l: 'a, r: 'a } -> 'a
    )

    /**
     * Numeric negation ability
     */
    data class NumNegAbility(
        val neg: String = "neg" // { val: 'a } -> 'a
    )

    /**
     * Logical AND ability
     */
    data class LogicalAndAbility(
        val and: String = "and" // { l: Bool, r: Bool } -> Bool
    )

    /**
     * Logical OR ability
     */
    data class LogicalOrAbility(
        val or: String = "or" // { l: Bool, r: Bool } -> Bool
    )

    /**
     * Logical NOT ability
     */
    data class LogicalNotAbility(
        val not: String = "not" // { val: Bool } -> Bool
    )

    /**
     * Inspect ability - provides string representation
     */
    data class InspectAbility(
        val to_str: String = "to_str" // { val: 'a } -> String
    )

    /**
     * Get the operation names for a built-in ability
     */
    fun getAbilityOperations(abilityName: String): List<String> {
        return when (abilityName) {
            BuiltinAbilities.EQ -> listOf(EqAbility().equals)
            BuiltinAbilities.ORD -> listOf(
                OrdAbility().compare,
                OrdAbility().lessThan,
                OrdAbility().greaterThan
            )
            BuiltinAbilities.NUM_ADD -> listOf(NumAddAbility().add)
            BuiltinAbilities.NUM_SUB -> listOf(NumSubAbility().subtract)
            BuiltinAbilities.NUM_MUL -> listOf(NumMulAbility().multiply)
            BuiltinAbilities.NUM_DIV -> listOf(NumDivAbility().divide)
            BuiltinAbilities.NUM_MOD -> listOf(NumModAbility().modulo)
            BuiltinAbilities.NUM_NEG -> listOf(NumNegAbility().neg)
            BuiltinAbilities.LOGICAL_AND -> listOf(LogicalAndAbility().and)
            BuiltinAbilities.LOGICAL_OR -> listOf(LogicalOrAbility().or)
            BuiltinAbilities.LOGICAL_NOT -> listOf(LogicalNotAbility().not)
            BuiltinAbilities.INSPECT -> listOf(InspectAbility().to_str)
            else -> emptyList()
        }
    }

    /**
     * Check if an operation belongs to a built-in ability
     */
    fun isBuiltinAbilityOperation(abilityName: String, operationName: String): Boolean {
        return getAbilityOperations(abilityName).contains(operationName)
    }
}

// ============================================================================
// BUILT-IN TYPE UTILITIES
// ============================================================================

/**
 * Built-in type constructors and utilities
 */
object BuiltinTypes {

    /**
     * Built-in type constructor names
     */
    val coreTypeNames = setOf("Option", "Result", "List", "Map", "Set")
    val primitiveTypeNames = setOf("Int", "Float", "String", "Boolean", "Nat", "Frac", "Ordering")
    val allBuiltinTypeNames = coreTypeNames + primitiveTypeNames

    /**
     * Built-in constructor names for Option
     */
    val optionConstructors = setOf("Some", "None")

    /**
     * Built-in constructor names for Result
     */
    val resultConstructors = setOf("Ok", "Err")

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
    fun createOptionType(elementType: FunkType): FunkType.TypeApplication {
        return FunkType.TypeApplication("Option", listOf(elementType))
    }

    fun createResultType(valueType: FunkType, errorType: FunkType): FunkType.TypeApplication {
        return FunkType.TypeApplication("Result", listOf(valueType, errorType))
    }

    fun createListType(elementType: FunkType): FunkType.ListType {
        return FunkType.ListType(elementType)
    }

    fun createMapType(keyType: FunkType, valueType: FunkType): FunkType.MapType {
        return FunkType.MapType(keyType, valueType)
    }

    fun createSetType(elementType: FunkType): FunkType.SetType {
        return FunkType.SetType(elementType)
    }
}
