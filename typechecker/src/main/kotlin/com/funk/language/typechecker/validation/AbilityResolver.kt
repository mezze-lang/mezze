package com.funk.language.typechecker.validation

import com.funk.language.ast.*
import com.funk.language.typechecker.abilities.BuiltInAbilities
import com.funk.language.typechecker.context.TypingContext

/**
 * Ability resolver - checks that ability constraints are satisfied.
 *
 * Abilities (type classes) require that types implement specific operations.
 * Example: NumAdd ability requires + operation
 *
 * The resolver checks:
 * 1. Does the type have an impl for this ability?
 * 2. Are all ability dependencies satisfied?
 * 3. Are ability constraints from functions satisfied?
 *
 * Design:
 * - Pure functions (no mutation!)
 * - Returns sealed AbilityCheckResult
 * - Exhaustive pattern matching
 * - Clear error messages
 */
class AbilityResolver(
    private val context: TypingContext
) {
    /**
     * Result of ability resolution.
     *
     * Sealed type - exhaustive pattern matching, no nulls!
     */
    sealed class AbilityCheckResult {
        /**
         * Ability constraint is satisfied.
         */
        data class Satisfied(
            val implSource: String? = null
        ) : AbilityCheckResult()

        /**
         * Ability constraint is not satisfied.
         */
        data class Unsatisfied(
            val reason: String,
            val suggestions: List<String> = emptyList()
        ) : AbilityCheckResult()
    }

    /**
     * Check if a type satisfies an ability.
     *
     * Example: Does Int satisfy NumAdd?
     */
    fun checkAbility(
        type: FunkType,
        abilityName: String,
        typeArgs: List<FunkType> = emptyList()
    ): AbilityCheckResult {
        return when (type) {
            // Type variables can't be checked yet (deferred to instantiation)
            is FunkType.VarType -> AbilityCheckResult.Satisfied(
                implSource = "Type variable (deferred)"
            )

            // Primitive types have built-in abilities
            is FunkType.IntType,
            is FunkType.FloatType,
            is FunkType.NatType,
            is FunkType.FracType -> checkNumericAbility(type, abilityName)

            is FunkType.BytesType -> checkBytesAbility(abilityName)

            is FunkType.StringType -> checkStringAbility(abilityName)
            is FunkType.BooleanType -> checkBooleanAbility(abilityName)
            is FunkType.OrderingType -> checkOrderingAbility(abilityName)

            // Collection types have specific abilities
            is FunkType.ArrayType -> checkCollectionAbility(type, abilityName)
            is FunkType.ListType -> checkCollectionAbility(type, abilityName)
            is FunkType.SetType -> checkCollectionAbility(type, abilityName)
            is FunkType.MapType -> checkMapAbility(type, abilityName)

            // Record types can derive some abilities
            is FunkType.RecordType -> checkRecordAbility(type, abilityName)

            // Variant types can derive some abilities
            is FunkType.VariantType -> checkVariantAbility(type, abilityName)

            // Type applications need to look up the ability impl
            is FunkType.TypeApplication -> checkTypeApplicationAbility(type, abilityName, typeArgs)

            // Function types don't have abilities (can't be compared, shown, etc.)
            is FunkType.FunctionType -> AbilityCheckResult.Unsatisfied(
                reason = "Function types do not support abilities",
                suggestions = emptyList()
            )

            // Suspended types delegate to their wrapped type
            is FunkType.SuspendedType -> checkAbility(type.valueType, abilityName, typeArgs)

            // Effect rows don't have abilities
            is FunkType.EffectRow -> AbilityCheckResult.Unsatisfied(
                reason = "Effect rows do not support abilities",
                suggestions = emptyList()
            )
        }
    }

    /**
     * Check numeric type abilities.
     */
    private fun checkNumericAbility(type: FunkType, abilityName: String): AbilityCheckResult {
        return when (abilityName) {
            // Arithmetic abilities
            "NumAdd", "NumSub", "NumMul", "NumDiv", "NumMod", "NumNeg" ->
                AbilityCheckResult.Satisfied("Built-in numeric")

            // Comparison abilities
            "Eq", "Ord" ->
                AbilityCheckResult.Satisfied("Built-in comparable")

            // Display abilities
            "Show", "Inspect" ->
                AbilityCheckResult.Satisfied("Built-in displayable")

            else -> checkUserDefinedAbilityImpl(type, abilityName)
        }
    }

    /**
     * Check string abilities.
     */
    private fun checkStringAbility(abilityName: String): AbilityCheckResult {
        return when (abilityName) {
            // String concatenation (could be modeled as Monoid)
            "Eq", "Ord" ->
                AbilityCheckResult.Satisfied("Built-in comparable")

            "Show", "Inspect" ->
                AbilityCheckResult.Satisfied("Built-in displayable")

            else -> checkUserDefinedAbilityImpl(FunkType.StringType, abilityName)
        }
    }

    /**
     * Check boolean abilities.
     */
    private fun checkBooleanAbility(abilityName: String): AbilityCheckResult {
        return when (abilityName) {
            "Eq" -> AbilityCheckResult.Satisfied("Built-in")
            "Show", "Inspect" -> AbilityCheckResult.Satisfied("Built-in displayable")

            else -> checkUserDefinedAbilityImpl(FunkType.BooleanType, abilityName)
        }
    }

    /**
     * Check ordering type abilities.
     */
    private fun checkOrderingAbility(abilityName: String): AbilityCheckResult {
        return when (abilityName) {
            "Eq" -> AbilityCheckResult.Satisfied("Built-in")
            "Show", "Inspect" -> AbilityCheckResult.Satisfied("Built-in displayable")

            else -> checkUserDefinedAbilityImpl(FunkType.OrderingType, abilityName)
        }
    }

    /**
     * Check bytes type abilities.
     */
    private fun checkBytesAbility(abilityName: String): AbilityCheckResult {
        return when (abilityName) {
            "Eq" -> AbilityCheckResult.Satisfied("Built-in")
            "Show", "Inspect" -> AbilityCheckResult.Satisfied("Built-in displayable")

            else -> checkUserDefinedAbilityImpl(FunkType.BytesType, abilityName)
        }
    }

    /**
     * Check collection type abilities.
     */
    private fun checkCollectionAbility(type: FunkType, abilityName: String): AbilityCheckResult {
        val elementType = when (type) {
            is FunkType.ArrayType -> type.elementType
            is FunkType.ListType -> type.elementType
            is FunkType.SetType -> type.elementType
            else -> return AbilityCheckResult.Unsatisfied("Not a collection type")
        }

        return when (abilityName) {
            // Equality if elements are comparable
            "Eq" -> {
                val elementCheck = checkAbility(elementType, "Eq")
                if (elementCheck is AbilityCheckResult.Satisfied) {
                    AbilityCheckResult.Satisfied("Derived from element type")
                } else {
                    AbilityCheckResult.Unsatisfied(
                        reason = "Collection Eq requires element type to have Eq",
                        suggestions = listOf("Ensure element type ${formatType(elementType)} has Eq ability")
                    )
                }
            }

            // Collections can be shown if elements can
            "Show", "Inspect" -> {
                val elementCheck = checkAbility(elementType, abilityName)
                if (elementCheck is AbilityCheckResult.Satisfied) {
                    AbilityCheckResult.Satisfied("Derived from element type")
                } else {
                    AbilityCheckResult.Unsatisfied(
                        reason = "Collection $abilityName requires element type to have $abilityName"
                    )
                }
            }

            // Collections are iterable
            "Iterable" -> AbilityCheckResult.Satisfied("Built-in")

            else -> checkUserDefinedAbilityImpl(type, abilityName)
        }
    }

    /**
     * Check map abilities.
     */
    private fun checkMapAbility(type: FunkType.MapType, abilityName: String): AbilityCheckResult {
        return when (abilityName) {
            "Eq" -> {
                // Both key and value must have Eq
                val keyCheck = checkAbility(type.keyType, "Eq")
                val valueCheck = checkAbility(type.valueType, "Eq")

                if (keyCheck is AbilityCheckResult.Satisfied && valueCheck is AbilityCheckResult.Satisfied) {
                    AbilityCheckResult.Satisfied("Derived from key and value types")
                } else {
                    AbilityCheckResult.Unsatisfied(
                        reason = "Map Eq requires both key and value types to have Eq"
                    )
                }
            }

            "Show", "Inspect" -> AbilityCheckResult.Satisfied("Built-in displayable")

            else -> checkUserDefinedAbilityImpl(type, abilityName)
        }
    }

    /**
     * Check record type abilities.
     */
    private fun checkRecordAbility(type: FunkType.RecordType, abilityName: String): AbilityCheckResult {
        return when (abilityName) {
            // Records can have Eq if all fields have Eq
            "Eq" -> {
                for ((fieldName, fieldType) in type.fields) {
                    val fieldCheck = checkAbility(fieldType, "Eq")
                    if (fieldCheck is AbilityCheckResult.Unsatisfied) {
                        return AbilityCheckResult.Unsatisfied(
                            reason = "Record Eq requires all fields to have Eq (field '$fieldName' does not)"
                        )
                    }
                }
                AbilityCheckResult.Satisfied("Derived from all fields")
            }

            // Records can be shown
            "Show", "Inspect" -> AbilityCheckResult.Satisfied("Records can always be displayed")

            else -> checkUserDefinedAbilityImpl(type, abilityName)
        }
    }

    /**
     * Check variant type abilities.
     */
    private fun checkVariantAbility(type: FunkType.VariantType, abilityName: String): AbilityCheckResult {
        return when (abilityName) {
            // Variants can have Eq if all constructors have Eq
            "Eq" -> {
                for ((constructorName, dataType) in type.constructors) {
                    if (dataType != null) {
                        val dataCheck = checkAbility(dataType, "Eq")
                        if (dataCheck is AbilityCheckResult.Unsatisfied) {
                            return AbilityCheckResult.Unsatisfied(
                                reason = "Variant Eq requires all constructor data to have Eq (constructor '$constructorName' does not)"
                            )
                        }
                    }
                }
                AbilityCheckResult.Satisfied("Derived from all constructors")
            }

            // Variants can be shown
            "Show", "Inspect" -> AbilityCheckResult.Satisfied("Variants can always be displayed")

            else -> checkUserDefinedAbilityImpl(type, abilityName)
        }
    }

    /**
     * Check type application abilities.
     *
     * This requires looking up the ability implementation in the context.
     */
    private fun checkTypeApplicationAbility(
        type: FunkType.TypeApplication,
        abilityName: String,
        typeArgs: List<FunkType>
    ): AbilityCheckResult {
        // Handle well-known built-in types first
        when (type.constructor) {
            "Option", "Result" -> {
                // These are variants, so they can have Eq, Show
                return when (abilityName) {
                    "Eq", "Show", "Inspect" -> AbilityCheckResult.Satisfied("Built-in variant type")
                    else -> AbilityCheckResult.Unsatisfied(
                        reason = "${type.constructor} does not support ability '$abilityName'"
                    )
                }
            }
        }

        // Look up user-defined ability implementation in context
        return checkUserDefinedAbilityImpl(type, abilityName)
    }

    /**
     * Get type name for ability impl lookup.
     *
     * This must match the format used in ContextBuilder when storing impls.
     *
     * Examples:
     * - String → "String"
     * - TypeApplication("Option", [Int]) → "Option_Int"
     * - TypeApplication("Result", [String, Int]) → "Result_String_Int"
     */
    private fun getTypeName(type: FunkType): String {
        return when (type) {
            is FunkType.IntType -> "Int"
            is FunkType.FloatType -> "Float"
            is FunkType.StringType -> "String"
            is FunkType.BooleanType -> "Bool"
            is FunkType.NatType -> "Nat"
            is FunkType.FracType -> "Frac"
            is FunkType.OrderingType -> "Ordering"
            is FunkType.BytesType -> "Bytes"

            is FunkType.VarType -> type.variable.name.removePrefix("'")

            is FunkType.TypeApplication -> {
                if (type.args.isEmpty()) {
                    type.constructor
                } else {
                    val argNames = type.args.map { getTypeName(it) }
                    "${type.constructor}_${argNames.joinToString("_")}"
                }
            }

            is FunkType.ArrayType -> "Array_${getTypeName(type.elementType)}"
            is FunkType.ListType -> "List_${getTypeName(type.elementType)}"
            is FunkType.SetType -> "Set_${getTypeName(type.elementType)}"
            is FunkType.MapType -> "Map_${getTypeName(type.keyType)}_${getTypeName(type.valueType)}"

            // For complex types, use a generic name (these are rare as ability targets)
            is FunkType.FunctionType -> "Function"
            is FunkType.RecordType -> "Record"
            is FunkType.VariantType -> {
                // Check if this is the Bool type (True | False)
                // Uses the centralized check in BuiltInAbilities to avoid duplicating logic
                if (BuiltInAbilities.isBoolVariant(type)) {
                    "Bool"
                } else {
                    // For other variant types, generate a name based on constructors
                    "Variant_${type.constructors.keys.sorted().joinToString("_")}"
                }
            }
            is FunkType.SuspendedType -> getTypeName(type.valueType)
            is FunkType.EffectRow -> "EffectRow"
        }
    }

    /**
     * Format type for error messages.
     */
    private fun formatType(type: FunkType): String {
        return when (type) {
            is FunkType.VarType -> type.variable.toString()
            is FunkType.IntType -> "Int"
            is FunkType.FloatType -> "Float"
            is FunkType.StringType -> "String"
            is FunkType.BooleanType -> "Bool"
            is FunkType.NatType -> "Nat"
            is FunkType.FracType -> "Frac"
            is FunkType.OrderingType -> "Ordering"
            is FunkType.BytesType -> "Bytes"
            else -> type.toString()
        }
    }

    /**
     * Check for user-defined ability implementation.
     *
     * This is called when built-in ability checks fail, to check if a user
     * has defined an implementation for this ability and type.
     *
     * For multi-parameter abilities like Convert 'a 'b, we look for implementations
     * with partial type information (e.g., Convert_Int_* matches Convert_Int_String).
     */
    private fun checkUserDefinedAbilityImpl(type: FunkType, abilityName: String): AbilityCheckResult {
        val typeName = getTypeName(type)

        // First check for exact match (single-parameter ability)
        val exactImplKey = "${abilityName}_$typeName"
        if (context.hasAbilityImpl(exactImplKey)) {
            return AbilityCheckResult.Satisfied("User-defined impl: $exactImplKey")
        }

        // Check for partial match (multi-parameter abilities)
        // Get all implementations for this ability and check if any have this type as first arg
        val allImpls = context.getAbilityImpls(abilityName)
        val matchingImpl = allImpls.find { impl ->
            impl.targetTypes.isNotEmpty() && impl.targetTypes.first() == typeName
        }

        return if (matchingImpl != null) {
            val implKey = "${matchingImpl.abilityName}_${matchingImpl.targetTypes.joinToString("_")}"
            AbilityCheckResult.Satisfied("User-defined impl: $implKey")
        } else {
            AbilityCheckResult.Unsatisfied(
                reason = "No implementation of ability '$abilityName' for type '$typeName'",
                suggestions = listOf(
                    "Add an implementation: impl $abilityName $typeName { ... }",
                    "Or import a module that provides this implementation"
                )
            )
        }
    }
}
