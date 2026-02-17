package com.mezze.language.typechecker.validation

import com.mezze.language.ast.*
import com.mezze.language.typechecker.abilities.BuiltInAbilities
import com.mezze.language.typechecker.codec.CodecGenerator
import com.mezze.language.typechecker.context.TypingContext

/**
 * Ability resolver - checks that ability constraints are satisfied.
 *
 * Abilities (type classes) require that types implement specific operations.
 * Example: NumAdd ability requires + operation
 *
 * Built-in abilities (from BuiltInAbilities.kt):
 * - NumAdd, NumSub, NumMul, NumDiv, NumMod (arithmetic)
 * - Eq (equality: eq, neq)
 * - Ord (ordering: lt, le, gt, ge)
 * - Inspect (to_str conversion)
 * - LogicalAnd, LogicalOr, LogicalNot (boolean operations)
 *
 * The resolver checks:
 * 1. Does the type have an impl for this ability?
 * 2. Are all ability dependencies satisfied?
 * 3. Are ability constraints from functions satisfied?
 */
class AbilityResolver(
    private val context: TypingContext
) {
    private val codecGenerator = CodecGenerator()
    /**
     * Result of ability resolution.
     */
    sealed class AbilityCheckResult {
        data class Satisfied(
            val implSource: String? = null
        ) : AbilityCheckResult()

        data class Unsatisfied(
            val reason: String,
            val suggestions: List<String> = emptyList()
        ) : AbilityCheckResult()
    }

    /**
     * Check if a type satisfies an ability.
     */
    fun checkAbility(
        type: MezzeType,
        abilityName: String,
        typeArgs: List<MezzeType> = emptyList()
    ): AbilityCheckResult {
        return when (type) {
            // Type variables can't be checked yet (deferred to instantiation)
            is MezzeType.VarType -> AbilityCheckResult.Satisfied(
                implSource = "Type variable (deferred)"
            )

            // Numeric types: Int, Float, Nat, Frac
            is MezzeType.IntType -> checkIntAbility(abilityName)
            is MezzeType.FloatType -> checkFloatAbility(abilityName)
            is MezzeType.NatType -> checkNatAbility(abilityName)
            is MezzeType.FracType -> checkFracAbility(abilityName)

            // Other primitive types
            is MezzeType.StringType -> checkStringAbility(abilityName)
            is MezzeType.BooleanType -> checkBooleanAbility(abilityName)
            is MezzeType.CharType -> checkCharAbility(abilityName)
            is MezzeType.OrderingType -> checkOrderingAbility(abilityName)
            is MezzeType.BytesType -> checkBytesAbility(abilityName)
            is MezzeType.ByteBufferType -> checkByteBufferAbility(abilityName)

            // Collection types
            is MezzeType.ListType -> checkListAbility(type, abilityName)
            is MezzeType.ArrayType -> checkArrayAbility(type, abilityName)
            is MezzeType.SetType -> checkSetAbility(type, abilityName)
            is MezzeType.MapType -> checkMapAbility(type, abilityName)
            is MezzeType.SortedSetType -> checkSortedSetAbility(type, abilityName)
            is MezzeType.SortedMapType -> checkSortedMapAbility(type, abilityName)

            // Composite types
            is MezzeType.RecordType -> checkRecordAbility(type, abilityName)
            is MezzeType.VariantType -> checkVariantAbility(type, abilityName)
            is MezzeType.TypeApplication -> checkTypeApplicationAbility(type, abilityName, typeArgs)

            // Function types don't have abilities
            is MezzeType.FunctionType -> AbilityCheckResult.Unsatisfied(
                reason = "Function types do not support abilities"
            )

            // Suspended types delegate to their wrapped type
            is MezzeType.SuspendedType -> checkAbility(type.valueType, abilityName, typeArgs)

            // Effect rows don't have abilities
            is MezzeType.EffectRow -> AbilityCheckResult.Unsatisfied(
                reason = "Effect rows do not support abilities"
            )
        }
    }

    // ========================================
    // Numeric Types
    // ========================================

    private fun checkIntAbility(abilityName: String): AbilityCheckResult {
        return when (abilityName) {
            "NumAdd", "NumSub", "NumMul", "NumDiv", "NumMod" ->
                AbilityCheckResult.Satisfied("Built-in Int arithmetic")
            "Eq" -> AbilityCheckResult.Satisfied("Built-in Int equality")
            "Ord" -> AbilityCheckResult.Satisfied("Built-in Int ordering")
            "Inspect" -> AbilityCheckResult.Satisfied("Built-in Int to_string")
            else -> checkUserDefinedAbilityImpl(MezzeType.IntType, abilityName)
        }
    }

    private fun checkFloatAbility(abilityName: String): AbilityCheckResult {
        return when (abilityName) {
            "NumAdd", "NumSub", "NumMul", "NumDiv" ->
                AbilityCheckResult.Satisfied("Built-in Float arithmetic")
            "Eq" -> AbilityCheckResult.Satisfied("Built-in Float equality")
            "Ord" -> AbilityCheckResult.Satisfied("Built-in Float ordering")
            "Inspect" -> AbilityCheckResult.Satisfied("Built-in Float to_string")
            else -> checkUserDefinedAbilityImpl(MezzeType.FloatType, abilityName)
        }
    }

    private fun checkNatAbility(abilityName: String): AbilityCheckResult {
        return when (abilityName) {
            "NumAdd", "NumMul", "NumDiv", "NumMod" ->
                AbilityCheckResult.Satisfied("Built-in Nat arithmetic")
            // Note: Nat doesn't support NumSub because Nat - Nat can be negative (returns Int, not Nat)
            "Eq" -> AbilityCheckResult.Satisfied("Built-in Nat equality")
            "Ord" -> AbilityCheckResult.Satisfied("Built-in Nat ordering")
            "Inspect" -> AbilityCheckResult.Satisfied("Built-in Nat to_string")
            else -> checkUserDefinedAbilityImpl(MezzeType.NatType, abilityName)
        }
    }

    private fun checkFracAbility(abilityName: String): AbilityCheckResult {
        return when (abilityName) {
            "NumAdd", "NumSub", "NumMul", "NumDiv" ->
                AbilityCheckResult.Satisfied("Built-in Frac arithmetic")
            "Eq" -> AbilityCheckResult.Satisfied("Built-in Frac equality")
            "Ord" -> AbilityCheckResult.Satisfied("Built-in Frac ordering")
            "Inspect" -> AbilityCheckResult.Satisfied("Built-in Frac to_string")
            else -> checkUserDefinedAbilityImpl(MezzeType.FracType, abilityName)
        }
    }

    // ========================================
    // Other Primitive Types
    // ========================================

    private fun checkStringAbility(abilityName: String): AbilityCheckResult {
        return when (abilityName) {
            "Eq" -> AbilityCheckResult.Satisfied("Built-in String equality")
            "Ord" -> AbilityCheckResult.Satisfied("Built-in String ordering")
            "Inspect" -> AbilityCheckResult.Satisfied("Built-in String to_string")
            else -> checkUserDefinedAbilityImpl(MezzeType.StringType, abilityName)
        }
    }

    private fun checkBooleanAbility(abilityName: String): AbilityCheckResult {
        return when (abilityName) {
            "Eq" -> AbilityCheckResult.Satisfied("Built-in Bool equality")
            "Ord" -> AbilityCheckResult.Satisfied("Built-in Bool ordering (False < True)")
            "Inspect" -> AbilityCheckResult.Satisfied("Built-in Bool to_string")
            "LogicalAnd", "LogicalOr", "LogicalNot" ->
                AbilityCheckResult.Satisfied("Built-in Bool logic")
            else -> checkUserDefinedAbilityImpl(MezzeType.BooleanType, abilityName)
        }
    }

    private fun checkCharAbility(abilityName: String): AbilityCheckResult {
        return when (abilityName) {
            "Eq" -> AbilityCheckResult.Satisfied("Built-in Char equality")
            "Ord" -> AbilityCheckResult.Satisfied("Built-in Char ordering")
            "Inspect" -> AbilityCheckResult.Satisfied("Built-in Char to_string")
            else -> checkUserDefinedAbilityImpl(MezzeType.CharType, abilityName)
        }
    }

    private fun checkOrderingAbility(abilityName: String): AbilityCheckResult {
        return when (abilityName) {
            "Eq" -> AbilityCheckResult.Satisfied("Built-in Ordering equality")
            "Ord" -> AbilityCheckResult.Satisfied("Built-in Ordering ordering (LT < EQ < GT)")
            "Inspect" -> AbilityCheckResult.Satisfied("Built-in Ordering to_string")
            else -> checkUserDefinedAbilityImpl(MezzeType.OrderingType, abilityName)
        }
    }

    private fun checkBytesAbility(abilityName: String): AbilityCheckResult {
        return when (abilityName) {
            "Eq" -> AbilityCheckResult.Satisfied("Built-in Bytes equality")
            "Ord" -> AbilityCheckResult.Satisfied("Built-in Bytes lexicographic ordering")
            "Inspect" -> AbilityCheckResult.Satisfied("Built-in Bytes to_string")
            else -> checkUserDefinedAbilityImpl(MezzeType.BytesType, abilityName)
        }
    }

    private fun checkByteBufferAbility(abilityName: String): AbilityCheckResult {
        // ByteBuffer is mutable, so it has very limited abilities
        return when (abilityName) {
            // ByteBuffer doesn't have Eq or Inspect implemented
            else -> checkUserDefinedAbilityImpl(MezzeType.ByteBufferType, abilityName)
        }
    }

    // ========================================
    // Collection Types
    // ========================================

    private fun checkListAbility(type: MezzeType.ListType, abilityName: String): AbilityCheckResult {
        return when (abilityName) {
            "Eq" -> {
                // List has equals if element type has Eq
                val elementCheck = checkAbility(type.elementType, "Eq")
                if (elementCheck is AbilityCheckResult.Satisfied) {
                    AbilityCheckResult.Satisfied("Built-in List equality (derived from element)")
                } else {
                    AbilityCheckResult.Unsatisfied(
                        reason = "List Eq requires element type to have Eq",
                        suggestions = listOf("Ensure ${formatType(type.elementType)} has Eq")
                    )
                }
            }
            "Ord" -> {
                // List has Ord (lexicographic) if element type has Ord
                val elementCheck = checkAbility(type.elementType, "Ord")
                if (elementCheck is AbilityCheckResult.Satisfied) {
                    AbilityCheckResult.Satisfied("Built-in List lexicographic ordering (derived from element)")
                } else {
                    AbilityCheckResult.Unsatisfied(
                        reason = "List Ord requires element type to have Ord",
                        suggestions = listOf("Ensure ${formatType(type.elementType)} has Ord")
                    )
                }
            }
            "Inspect" -> AbilityCheckResult.Satisfied("Built-in List to_str")
            else -> checkUserDefinedAbilityImpl(type, abilityName)
        }
    }

    private fun checkArrayAbility(type: MezzeType.ArrayType, abilityName: String): AbilityCheckResult {
        return when (abilityName) {
            "Eq" -> {
                // Array has equals if element type has Eq
                val elementCheck = checkAbility(type.elementType, "Eq")
                if (elementCheck is AbilityCheckResult.Satisfied) {
                    AbilityCheckResult.Satisfied("Built-in Array equality (derived from element)")
                } else {
                    AbilityCheckResult.Unsatisfied(
                        reason = "Array Eq requires element type to have Eq",
                        suggestions = listOf("Ensure ${formatType(type.elementType)} has Eq")
                    )
                }
            }
            "Ord" -> {
                // Array has Ord (lexicographic) if element type has Ord
                val elementCheck = checkAbility(type.elementType, "Ord")
                if (elementCheck is AbilityCheckResult.Satisfied) {
                    AbilityCheckResult.Satisfied("Built-in Array lexicographic ordering (derived from element)")
                } else {
                    AbilityCheckResult.Unsatisfied(
                        reason = "Array Ord requires element type to have Ord",
                        suggestions = listOf("Ensure ${formatType(type.elementType)} has Ord")
                    )
                }
            }
            "Inspect" -> AbilityCheckResult.Satisfied("Built-in Array to_str")
            else -> checkUserDefinedAbilityImpl(type, abilityName)
        }
    }

    private fun checkSetAbility(type: MezzeType.SetType, abilityName: String): AbilityCheckResult {
        return when (abilityName) {
            "Eq" -> {
                // Set has equals if element type has Eq
                val elementCheck = checkAbility(type.elementType, "Eq")
                if (elementCheck is AbilityCheckResult.Satisfied) {
                    AbilityCheckResult.Satisfied("Built-in Set equality (derived from element)")
                } else {
                    AbilityCheckResult.Unsatisfied(
                        reason = "Set Eq requires element type to have Eq",
                        suggestions = listOf("Ensure ${formatType(type.elementType)} has Eq")
                    )
                }
            }
            "Inspect" -> AbilityCheckResult.Satisfied("Built-in Set to_str")
            else -> checkUserDefinedAbilityImpl(type, abilityName)
        }
    }

    private fun checkMapAbility(type: MezzeType.MapType, abilityName: String): AbilityCheckResult {
        return when (abilityName) {
            "Eq" -> {
                // Map has equals if key and value types have Eq
                val keyCheck = checkAbility(type.keyType, "Eq")
                val valueCheck = checkAbility(type.valueType, "Eq")
                if (keyCheck is AbilityCheckResult.Satisfied && valueCheck is AbilityCheckResult.Satisfied) {
                    AbilityCheckResult.Satisfied("Built-in Map equality (derived from key and value)")
                } else {
                    AbilityCheckResult.Unsatisfied(
                        reason = "Map Eq requires both key and value types to have Eq"
                    )
                }
            }
            "Inspect" -> AbilityCheckResult.Satisfied("Built-in Map to_str")
            else -> checkUserDefinedAbilityImpl(type, abilityName)
        }
    }

    private fun checkSortedSetAbility(type: MezzeType.SortedSetType, abilityName: String): AbilityCheckResult {
        return when (abilityName) {
            "Eq" -> {
                // SortedSet has equals if element type has Eq
                val elementCheck = checkAbility(type.elementType, "Eq")
                if (elementCheck is AbilityCheckResult.Satisfied) {
                    AbilityCheckResult.Satisfied("Built-in SortedSet equality (derived from element)")
                } else {
                    AbilityCheckResult.Unsatisfied(
                        reason = "SortedSet Eq requires element type to have Eq",
                        suggestions = listOf("Ensure ${formatType(type.elementType)} has Eq")
                    )
                }
            }
            "Inspect" -> AbilityCheckResult.Satisfied("Built-in SortedSet to_str")
            else -> checkUserDefinedAbilityImpl(type, abilityName)
        }
    }

    private fun checkSortedMapAbility(type: MezzeType.SortedMapType, abilityName: String): AbilityCheckResult {
        return when (abilityName) {
            "Eq" -> {
                // SortedMap has equals if key and value types have Eq
                val keyCheck = checkAbility(type.keyType, "Eq")
                val valueCheck = checkAbility(type.valueType, "Eq")
                if (keyCheck is AbilityCheckResult.Satisfied && valueCheck is AbilityCheckResult.Satisfied) {
                    AbilityCheckResult.Satisfied("Built-in SortedMap equality (derived from key and value)")
                } else {
                    AbilityCheckResult.Unsatisfied(
                        reason = "SortedMap Eq requires both key and value types to have Eq"
                    )
                }
            }
            "Inspect" -> AbilityCheckResult.Satisfied("Built-in SortedMap to_str")
            else -> checkUserDefinedAbilityImpl(type, abilityName)
        }
    }

    // ========================================
    // Composite Types
    // ========================================

    private fun checkRecordAbility(type: MezzeType.RecordType, abilityName: String): AbilityCheckResult {
        return when (abilityName) {
            "Eq" -> {
                // Records can have Eq if all fields have Eq
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
            "Inspect" -> {
                // Records can have Inspect if all fields have Inspect
                for ((fieldName, fieldType) in type.fields) {
                    val fieldCheck = checkAbility(fieldType, "Inspect")
                    if (fieldCheck is AbilityCheckResult.Unsatisfied) {
                        return AbilityCheckResult.Unsatisfied(
                            reason = "Record Inspect requires all fields to have Inspect (field '$fieldName' does not)"
                        )
                    }
                }
                AbilityCheckResult.Satisfied("Built-in Record to_string (derived from all fields)")
            }
            else -> checkUserDefinedAbilityImpl(type, abilityName)
        }
    }

    private fun checkVariantAbility(type: MezzeType.VariantType, abilityName: String): AbilityCheckResult {
        return when (abilityName) {
            "Eq" -> {
                // Variants can have Eq if all constructors have Eq
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
            "Inspect" -> {
                // Variants can have Inspect if all constructor data types have Inspect
                for ((constructorName, dataType) in type.constructors) {
                    if (dataType != null) {
                        val dataCheck = checkAbility(dataType, "Inspect")
                        if (dataCheck is AbilityCheckResult.Unsatisfied) {
                            return AbilityCheckResult.Unsatisfied(
                                reason = "Variant Inspect requires all constructor data to have Inspect (constructor '$constructorName' does not)"
                            )
                        }
                    }
                }
                AbilityCheckResult.Satisfied("Built-in Variant to_string (derived from all constructors)")
            }
            else -> checkUserDefinedAbilityImpl(type, abilityName)
        }
    }

    private fun checkTypeApplicationAbility(
        type: MezzeType.TypeApplication,
        abilityName: String,
        typeArgs: List<MezzeType>
    ): AbilityCheckResult {
        // Handle well-known built-in types
        when (type.constructor) {
            "Option", "Result" -> {
                return when (abilityName) {
                    "Eq" -> {
                        // Option/Result have Eq if their type args have Eq
                        val allArgsHaveEq = type.args.all { arg ->
                            checkAbility(arg, "Eq") is AbilityCheckResult.Satisfied
                        }
                        if (allArgsHaveEq) {
                            AbilityCheckResult.Satisfied("Built-in ${type.constructor} equality")
                        } else {
                            AbilityCheckResult.Unsatisfied(
                                reason = "${type.constructor} Eq requires type arguments to have Eq"
                            )
                        }
                    }
                    "Inspect" -> {
                        // Option/Result have Inspect if their type args have Inspect
                        val allArgsHaveInspect = type.args.all { arg ->
                            checkAbility(arg, "Inspect") is AbilityCheckResult.Satisfied
                        }
                        if (allArgsHaveInspect) {
                            AbilityCheckResult.Satisfied("Built-in ${type.constructor} to_string")
                        } else {
                            AbilityCheckResult.Unsatisfied(
                                reason = "${type.constructor} Inspect requires type arguments to have Inspect"
                            )
                        }
                    }
                    else -> checkUserDefinedAbilityImpl(type, abilityName)
                }
            }
        }

        return checkUserDefinedAbilityImpl(type, abilityName)
    }

    // ========================================
    // Helper Functions
    // ========================================

    private fun getTypeName(type: MezzeType): String {
        return when (type) {
            is MezzeType.IntType -> "Int"
            is MezzeType.FloatType -> "Float"
            is MezzeType.StringType -> "String"
            is MezzeType.BooleanType -> "Bool"
            is MezzeType.NatType -> "Nat"
            is MezzeType.FracType -> "Frac"
            is MezzeType.OrderingType -> "Ordering"
            is MezzeType.BytesType -> "Bytes"
            is MezzeType.ByteBufferType -> "ByteBuffer"
            is MezzeType.CharType -> "Char"
            is MezzeType.VarType -> type.variable.name.removePrefix("'")
            is MezzeType.TypeApplication -> {
                if (type.args.isEmpty()) {
                    type.constructor
                } else {
                    val argNames = type.args.map { getTypeName(it) }
                    "${type.constructor}_${argNames.joinToString("_")}"
                }
            }
            is MezzeType.ArrayType -> "Array_${getTypeName(type.elementType)}"
            is MezzeType.ListType -> "List_${getTypeName(type.elementType)}"
            is MezzeType.SetType -> "Set_${getTypeName(type.elementType)}"
            is MezzeType.SortedMapType -> "SortedMap_${getTypeName(type.keyType)}_${getTypeName(type.valueType)}"
            is MezzeType.SortedSetType -> "SortedSet_${getTypeName(type.elementType)}"
            is MezzeType.MapType -> "Map_${getTypeName(type.keyType)}_${getTypeName(type.valueType)}"
            is MezzeType.FunctionType -> "Function"
            is MezzeType.RecordType -> "Record"
            is MezzeType.VariantType -> {
                if (BuiltInAbilities.isBoolVariant(type)) {
                    "Bool"
                } else {
                    "Variant_${type.constructors.keys.sorted().joinToString("_")}"
                }
            }
            is MezzeType.SuspendedType -> getTypeName(type.valueType)
            is MezzeType.EffectRow -> "EffectRow"
        }
    }

    private fun formatType(type: MezzeType): String {
        return when (type) {
            is MezzeType.VarType -> type.variable.toString()
            is MezzeType.IntType -> "Int"
            is MezzeType.FloatType -> "Float"
            is MezzeType.StringType -> "String"
            is MezzeType.BooleanType -> "Bool"
            is MezzeType.NatType -> "Nat"
            is MezzeType.FracType -> "Frac"
            is MezzeType.CharType -> "Char"
            is MezzeType.OrderingType -> "Ordering"
            is MezzeType.BytesType -> "Bytes"
            is MezzeType.ByteBufferType -> "ByteBuffer"
            else -> type.toString()
        }
    }

    private fun checkUserDefinedAbilityImpl(type: MezzeType, abilityName: String): AbilityCheckResult {
        val typeName = getTypeName(type)

        // Search for implementation by ability name and matching types
        // (ability impls are now keyed by content hash, so we search by ability name)
        val allImpls = context.getAbilityImpls(abilityName)
        val matchingImpl = allImpls.find { impl ->
            impl.targetTypes.isNotEmpty() && impl.targetTypes.first() == typeName
        }

        if (matchingImpl != null) {
            val implRef = matchingImpl.contentHash ?: "${matchingImpl.abilityName} ${matchingImpl.targetTypes}"
            return AbilityCheckResult.Satisfied("User-defined impl: $implRef")
        }

        // Check for auto-derivable Encode/Decode abilities
        // These are automatically generated by CodecGenerator for serializable types
        if ((abilityName == "Encode" || abilityName == "Decode") &&
            codecGenerator.isSerializable(type)) {
            // Check if there's an EncodeFormat/DecodeFormat impl in scope
            val formatAbility = if (abilityName == "Encode") "EncodeFormat" else "DecodeFormat"
            val formatImpls = context.getAbilityImpls(formatAbility)
            if (formatImpls.isNotEmpty()) {
                // This will be auto-derived by SubstitutionApplier/CodecGenerator
                return AbilityCheckResult.Satisfied("Auto-derived from $formatAbility impl")
            }
        }

        return AbilityCheckResult.Unsatisfied(
            reason = "No implementation of ability '$abilityName' for type '$typeName'",
            suggestions = listOf(
                "Add an implementation: impl $abilityName $typeName { ... }"
            )
        )
    }
}
