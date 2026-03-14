package com.mezze.language.typechecker.validation

import com.mezze.language.ast.*
import com.mezze.language.typechecker.abilities.BuiltInAbilities
import com.mezze.language.typechecker.codec.CodecGenerator
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.hashing.ContentHashComputer

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
    private val context: TypingContext,
    private val typeDeclarations: Map<String, TypeDeclaration> = emptyMap()
) {
    private val codecGenerator = CodecGenerator()

    // Cache for getTypeName() - avoids repeated ContentHashComputer calls
    // Keyed by identity hash to avoid expensive MezzeType.equals()
    private val typeNameCache = mutableMapOf<Int, String>()

    // Track types currently being checked to handle recursive types.
    // Key is (typeName, abilityName) - if we encounter the same check recursively,
    // we assume the ability is satisfied (coinductive reasoning for recursive types).
    private val inProgressChecks = mutableSetOf<Pair<String, String>>()

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
        // Handle recursive type checking - if we're already checking this type for this ability,
        // assume it's satisfied to break the recursion (coinductive approach)
        val typeName = getTypeNameForRecursionCheck(type)
        if (typeName != null) {
            val checkKey = Pair(typeName, abilityName)
            if (checkKey in inProgressChecks) {
                return AbilityCheckResult.Satisfied("Recursive type (coinductive)")
            }
            inProgressChecks.add(checkKey)
            try {
                return checkAbilityImpl(type, abilityName, typeArgs)
            } finally {
                inProgressChecks.remove(checkKey)
            }
        }

        return checkAbilityImpl(type, abilityName, typeArgs)
    }

    /**
     * Get a stable name for a type for recursion checking.
     * Returns null for types that can't be recursive (primitives, etc.)
     */
    private fun getTypeNameForRecursionCheck(type: MezzeType): String? {
        return when (type) {
            is MezzeType.RecordType -> type.typeName
            is MezzeType.VariantType -> type.typeName
            is MezzeType.TypeApplication -> type.constructor
            else -> null
        }
    }

    /**
     * Internal implementation of ability checking.
     */
    private fun checkAbilityImpl(
        type: MezzeType,
        abilityName: String,
        typeArgs: List<MezzeType>
    ): AbilityCheckResult {
        return when (type) {
            // Type variables can't be checked yet (deferred to instantiation)
            is MezzeType.VarType -> AbilityCheckResult.Satisfied(
                implSource = "Type variable (deferred)"
            )

            // Numeric types: Int, Float, Nat, Frac
            is MezzeType.IntType -> checkIntAbility(abilityName, typeArgs)
            is MezzeType.FloatType -> checkFloatAbility(abilityName, typeArgs)
            is MezzeType.NatType -> checkNatAbility(abilityName, typeArgs)
            is MezzeType.FracType -> checkFracAbility(abilityName, typeArgs)

            // Other primitive types
            is MezzeType.StringType -> checkStringAbility(abilityName, typeArgs)
            is MezzeType.BoolType -> checkBooleanAbility(abilityName, typeArgs)
            is MezzeType.CharType -> checkCharAbility(abilityName, typeArgs)
            is MezzeType.OrderingType -> checkOrderingAbility(abilityName, typeArgs)
            is MezzeType.BytesType -> checkBytesAbility(abilityName, typeArgs)
            is MezzeType.ByteBufferType -> checkByteBufferAbility(abilityName, typeArgs)

            // Collection types
            is MezzeType.ListType -> checkListAbility(type, abilityName, typeArgs)
            is MezzeType.SeqType -> checkSeqAbility(type, abilityName, typeArgs)
            is MezzeType.SetType -> checkSetAbility(type, abilityName, typeArgs)
            is MezzeType.MapType -> checkMapAbility(type, abilityName, typeArgs)
            is MezzeType.SortedSetType -> checkSortedSetAbility(type, abilityName, typeArgs)
            is MezzeType.SortedMapType -> checkSortedMapAbility(type, abilityName, typeArgs)

            // Composite types
            is MezzeType.RecordType -> checkRecordAbility(type, abilityName, typeArgs)
            is MezzeType.VariantType -> checkVariantAbility(type, abilityName, typeArgs)
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

    private fun checkIntAbility(abilityName: String, typeArgs: List<MezzeType>): AbilityCheckResult {
        return when (abilityName) {
            "NumAdd", "NumSub", "NumMul", "NumDiv", "NumMod" ->
                AbilityCheckResult.Satisfied("Built-in Int arithmetic")
            "Eq" -> AbilityCheckResult.Satisfied("Built-in Int equality")
            "Ord" -> AbilityCheckResult.Satisfied("Built-in Int ordering")
            "Inspect" -> AbilityCheckResult.Satisfied("Built-in Int to_string")
            else -> checkUserDefinedAbilityImpl(MezzeType.IntType, abilityName, typeArgs)
        }
    }

    private fun checkFloatAbility(abilityName: String, typeArgs: List<MezzeType>): AbilityCheckResult {
        return when (abilityName) {
            "NumAdd", "NumSub", "NumMul", "NumDiv" ->
                AbilityCheckResult.Satisfied("Built-in Float arithmetic")
            "Eq" -> AbilityCheckResult.Satisfied("Built-in Float equality")
            "Ord" -> AbilityCheckResult.Satisfied("Built-in Float ordering")
            "Inspect" -> AbilityCheckResult.Satisfied("Built-in Float to_string")
            else -> checkUserDefinedAbilityImpl(MezzeType.FloatType, abilityName, typeArgs)
        }
    }

    private fun checkNatAbility(abilityName: String, typeArgs: List<MezzeType>): AbilityCheckResult {
        return when (abilityName) {
            "NumAdd", "NumMul", "NumDiv", "NumMod" ->
                AbilityCheckResult.Satisfied("Built-in Nat arithmetic")
            // Note: Nat doesn't support NumSub because Nat - Nat can be negative (returns Int, not Nat)
            "Eq" -> AbilityCheckResult.Satisfied("Built-in Nat equality")
            "Ord" -> AbilityCheckResult.Satisfied("Built-in Nat ordering")
            "Inspect" -> AbilityCheckResult.Satisfied("Built-in Nat to_string")
            else -> checkUserDefinedAbilityImpl(MezzeType.NatType, abilityName, typeArgs)
        }
    }

    private fun checkFracAbility(abilityName: String, typeArgs: List<MezzeType>): AbilityCheckResult {
        return when (abilityName) {
            "NumAdd", "NumSub", "NumMul", "NumDiv" ->
                AbilityCheckResult.Satisfied("Built-in Frac arithmetic")
            "Eq" -> AbilityCheckResult.Satisfied("Built-in Frac equality")
            "Ord" -> AbilityCheckResult.Satisfied("Built-in Frac ordering")
            "Inspect" -> AbilityCheckResult.Satisfied("Built-in Frac to_string")
            else -> checkUserDefinedAbilityImpl(MezzeType.FracType, abilityName, typeArgs)
        }
    }

    // ========================================
    // Other Primitive Types
    // ========================================

    private fun checkStringAbility(abilityName: String, typeArgs: List<MezzeType>): AbilityCheckResult {
        return when (abilityName) {
            "Eq" -> AbilityCheckResult.Satisfied("Built-in String equality")
            "Ord" -> AbilityCheckResult.Satisfied("Built-in String ordering")
            "Inspect" -> AbilityCheckResult.Satisfied("Built-in String to_string")
            else -> checkUserDefinedAbilityImpl(MezzeType.StringType, abilityName, typeArgs)
        }
    }

    private fun checkBooleanAbility(abilityName: String, typeArgs: List<MezzeType>): AbilityCheckResult {
        return when (abilityName) {
            "Eq" -> AbilityCheckResult.Satisfied("Built-in Bool equality")
            "Ord" -> AbilityCheckResult.Satisfied("Built-in Bool ordering (False < True)")
            "Inspect" -> AbilityCheckResult.Satisfied("Built-in Bool to_string")
            "LogicalAnd", "LogicalOr", "LogicalNot" ->
                AbilityCheckResult.Satisfied("Built-in Bool logic")
            else -> checkUserDefinedAbilityImpl(MezzeType.BoolType, abilityName, typeArgs)
        }
    }

    private fun checkCharAbility(abilityName: String, typeArgs: List<MezzeType>): AbilityCheckResult {
        return when (abilityName) {
            "Eq" -> AbilityCheckResult.Satisfied("Built-in Char equality")
            "Ord" -> AbilityCheckResult.Satisfied("Built-in Char ordering")
            "Inspect" -> AbilityCheckResult.Satisfied("Built-in Char to_string")
            else -> checkUserDefinedAbilityImpl(MezzeType.CharType, abilityName, typeArgs)
        }
    }

    private fun checkOrderingAbility(abilityName: String, typeArgs: List<MezzeType>): AbilityCheckResult {
        return when (abilityName) {
            "Eq" -> AbilityCheckResult.Satisfied("Built-in Ordering equality")
            "Ord" -> AbilityCheckResult.Satisfied("Built-in Ordering ordering (LT < EQ < GT)")
            "Inspect" -> AbilityCheckResult.Satisfied("Built-in Ordering to_string")
            else -> checkUserDefinedAbilityImpl(MezzeType.OrderingType, abilityName, typeArgs)
        }
    }

    private fun checkBytesAbility(abilityName: String, typeArgs: List<MezzeType>): AbilityCheckResult {
        return when (abilityName) {
            "Eq" -> AbilityCheckResult.Satisfied("Built-in Bytes equality")
            "Ord" -> AbilityCheckResult.Satisfied("Built-in Bytes lexicographic ordering")
            "Inspect" -> AbilityCheckResult.Satisfied("Built-in Bytes to_string")
            else -> checkUserDefinedAbilityImpl(MezzeType.BytesType, abilityName, typeArgs)
        }
    }

    private fun checkByteBufferAbility(abilityName: String, typeArgs: List<MezzeType>): AbilityCheckResult {
        // ByteBuffer is mutable, so it has very limited abilities
        return when (abilityName) {
            // ByteBuffer doesn't have Eq or Inspect implemented
            else -> checkUserDefinedAbilityImpl(MezzeType.ByteBufferType, abilityName, typeArgs)
        }
    }

    // ========================================
    // Collection Types
    // ========================================

    private fun checkListAbility(type: MezzeType.ListType, abilityName: String, typeArgs: List<MezzeType>): AbilityCheckResult {
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
            else -> checkUserDefinedAbilityImpl(type, abilityName, typeArgs)
        }
    }

    private fun checkSeqAbility(type: MezzeType.SeqType, abilityName: String, typeArgs: List<MezzeType>): AbilityCheckResult {
        return when (abilityName) {
            "Eq" -> {
                // Seq has equals if element type has Eq
                val elementCheck = checkAbility(type.elementType, "Eq")
                if (elementCheck is AbilityCheckResult.Satisfied) {
                    AbilityCheckResult.Satisfied("Built-in Seq equality (derived from element)")
                } else {
                    AbilityCheckResult.Unsatisfied(
                        reason = "Seq Eq requires element type to have Eq",
                        suggestions = listOf("Ensure ${formatType(type.elementType)} has Eq")
                    )
                }
            }
            "Ord" -> {
                // Seq has Ord (lexicographic) if element type has Ord
                val elementCheck = checkAbility(type.elementType, "Ord")
                if (elementCheck is AbilityCheckResult.Satisfied) {
                    AbilityCheckResult.Satisfied("Built-in Seq lexicographic ordering (derived from element)")
                } else {
                    AbilityCheckResult.Unsatisfied(
                        reason = "Seq Ord requires element type to have Ord",
                        suggestions = listOf("Ensure ${formatType(type.elementType)} has Ord")
                    )
                }
            }
            "Inspect" -> AbilityCheckResult.Satisfied("Built-in Seq to_str")
            else -> checkUserDefinedAbilityImpl(type, abilityName, typeArgs)
        }
    }

    private fun checkSetAbility(type: MezzeType.SetType, abilityName: String, typeArgs: List<MezzeType>): AbilityCheckResult {
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
            else -> checkUserDefinedAbilityImpl(type, abilityName, typeArgs)
        }
    }

    private fun checkMapAbility(type: MezzeType.MapType, abilityName: String, typeArgs: List<MezzeType>): AbilityCheckResult {
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
            else -> checkUserDefinedAbilityImpl(type, abilityName, typeArgs)
        }
    }

    private fun checkSortedSetAbility(type: MezzeType.SortedSetType, abilityName: String, typeArgs: List<MezzeType>): AbilityCheckResult {
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
            else -> checkUserDefinedAbilityImpl(type, abilityName, typeArgs)
        }
    }

    private fun checkSortedMapAbility(type: MezzeType.SortedMapType, abilityName: String, typeArgs: List<MezzeType>): AbilityCheckResult {
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
            else -> checkUserDefinedAbilityImpl(type, abilityName, typeArgs)
        }
    }

    // ========================================
    // Composite Types
    // ========================================

    private fun checkRecordAbility(type: MezzeType.RecordType, abilityName: String, typeArgs: List<MezzeType>): AbilityCheckResult {
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
            else -> checkUserDefinedAbilityImpl(type, abilityName, typeArgs)
        }
    }

    private fun checkVariantAbility(type: MezzeType.VariantType, abilityName: String, typeArgs: List<MezzeType>): AbilityCheckResult {
        // Check if this is a newtype by looking up the type declaration
        // A newtype is identified by its TypeDeclaration having a NewtypeDefinition, not by constructor count
        val typeName = type.typeName
        val typeDecl = if (typeName != null) typeDeclarations[typeName] else null
        val isNewtype = typeDecl?.definition is NewtypeDefinition

        if (isNewtype) {
            return checkNewtypeAbility(type, abilityName, typeArgs, typeDecl!!.definition as NewtypeDefinition)
        }

        // Regular variant handling
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
            else -> checkUserDefinedAbilityImpl(type, abilityName, typeArgs)
        }
    }

    /**
     * Check ability for newtype.
     *
     * Newtypes have special rules:
     * - Inspect: Always auto-derived (universal debugging)
     * - Eq, Ord, etc.: Only available if explicitly listed in `deriving` clause
     */
    private fun checkNewtypeAbility(
        type: MezzeType.VariantType,
        abilityName: String,
        typeArgs: List<MezzeType>,
        definition: NewtypeDefinition
    ): AbilityCheckResult {
        val typeName = type.typeName ?: return checkUserDefinedAbilityImpl(type, abilityName, typeArgs)

        // Get the wrapped type (payload of the single constructor)
        val wrappedType = type.constructors.values.firstOrNull()

        return when (abilityName) {
            "Inspect" -> {
                // Inspect is always auto-derived for newtypes (universal debugging)
                if (wrappedType != null) {
                    val wrappedCheck = checkAbility(wrappedType, "Inspect")
                    if (wrappedCheck is AbilityCheckResult.Unsatisfied) {
                        return AbilityCheckResult.Unsatisfied(
                            reason = "Newtype '$typeName' Inspect requires wrapped type to have Inspect"
                        )
                    }
                }
                AbilityCheckResult.Satisfied("Built-in Newtype to_string (auto-derived)")
            }
            "Eq", "Ord" -> {
                // Eq and Ord require explicit deriving
                val derivedAbilities = definition.derivedAbilities

                if (abilityName in derivedAbilities) {
                    // Check that wrapped type also has this ability
                    if (wrappedType != null) {
                        val wrappedCheck = checkAbility(wrappedType, abilityName)
                        if (wrappedCheck is AbilityCheckResult.Unsatisfied) {
                            return AbilityCheckResult.Unsatisfied(
                                reason = "Newtype '$typeName' cannot derive $abilityName because wrapped type doesn't have $abilityName"
                            )
                        }
                    }
                    AbilityCheckResult.Satisfied("Derived via 'deriving $abilityName'")
                } else {
                    AbilityCheckResult.Unsatisfied(
                        reason = "Newtype '$typeName' does not derive $abilityName. Add 'deriving $abilityName' to the newtype declaration.",
                        suggestions = listOf("newtype $typeName = ... deriving $abilityName")
                    )
                }
            }
            else -> {
                // Other abilities require explicit impl or deriving
                val derivedAbilities = definition.derivedAbilities

                if (abilityName in derivedAbilities) {
                    // Check that wrapped type also has this ability
                    if (wrappedType != null) {
                        val wrappedCheck = checkAbility(wrappedType, abilityName)
                        if (wrappedCheck is AbilityCheckResult.Unsatisfied) {
                            return AbilityCheckResult.Unsatisfied(
                                reason = "Newtype '$typeName' cannot derive $abilityName because wrapped type doesn't have $abilityName"
                            )
                        }
                    }
                    AbilityCheckResult.Satisfied("Derived via 'deriving $abilityName'")
                } else {
                    checkUserDefinedAbilityImpl(type, abilityName, typeArgs)
                }
            }
        }
    }

    /**
     * Check ability for newtype when accessed via TypeApplication.
     *
     * This is called when the type is represented as TypeApplication("UserId", [])
     * rather than VariantType (which happens during constraint generation).
     */
    private fun checkNewtypeAbilityForTypeApplication(
        type: MezzeType.TypeApplication,
        abilityName: String,
        typeArgs: List<MezzeType>,
        definition: NewtypeDefinition
    ): AbilityCheckResult {
        val typeName = type.constructor

        return when (abilityName) {
            "Inspect" -> {
                // Inspect is always auto-derived for newtypes (universal debugging)
                AbilityCheckResult.Satisfied("Built-in Newtype to_string (auto-derived)")
            }
            "Eq", "Ord" -> {
                // Eq and Ord require explicit deriving
                val derivedAbilities = definition.derivedAbilities

                if (abilityName in derivedAbilities) {
                    AbilityCheckResult.Satisfied("Derived via 'deriving $abilityName'")
                } else {
                    AbilityCheckResult.Unsatisfied(
                        reason = "Newtype '$typeName' does not derive $abilityName. Add 'deriving $abilityName' to the newtype declaration.",
                        suggestions = listOf("newtype $typeName = ... deriving $abilityName")
                    )
                }
            }
            else -> {
                // Other abilities require explicit impl or deriving
                val derivedAbilities = definition.derivedAbilities

                if (abilityName in derivedAbilities) {
                    AbilityCheckResult.Satisfied("Derived via 'deriving $abilityName'")
                } else {
                    checkUserDefinedAbilityImpl(type, abilityName, typeArgs)
                }
            }
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
                    else -> checkUserDefinedAbilityImpl(type, abilityName, typeArgs)
                }
            }
        }

        // For user-defined type applications, check if the underlying type declaration
        // supports the ability. This handles recursive types like Tree 'a.
        val typeDecl = typeDeclarations[type.constructor]
        if (typeDecl != null) {
            // Check if this is a newtype - newtypes have special deriving rules
            if (typeDecl.definition is NewtypeDefinition) {
                return checkNewtypeAbilityForTypeApplication(
                    type, abilityName, typeArgs, typeDecl.definition as NewtypeDefinition
                )
            }

            // For regular variants and records, check built-in abilities
            if (abilityName == "Eq" || abilityName == "Inspect") {
                // First check if type arguments support the ability
                val allArgsSupport = type.args.all { arg ->
                    checkAbility(arg, abilityName) is AbilityCheckResult.Satisfied
                }
                if (!allArgsSupport) {
                    return AbilityCheckResult.Unsatisfied(
                        reason = "${type.constructor} $abilityName requires type arguments to have $abilityName"
                    )
                }

                // Check the underlying type structure
                return when (val definition = typeDecl.definition) {
                    is VariantDefinition -> {
                        // For variant types, check all constructor data types
                        // But skip recursive references (handled by inProgressChecks)
                        for (constructor in definition.constructors) {
                            val dataTypeAnnotation = constructor.dataType ?: continue
                            // Check if this is a self-reference - if so, skip
                            if (isSelfReference(dataTypeAnnotation, type.constructor)) continue

                            // For non-self-referential parts, we need to check the concrete type
                            // This is already handled by the recursive check with inProgressChecks
                        }
                        AbilityCheckResult.Satisfied("Derived from type declaration ${type.constructor}")
                    }
                    is RecordDefinition -> {
                        AbilityCheckResult.Satisfied("Derived from type declaration ${type.constructor}")
                    }
                    is NewtypeDefinition -> {
                        // Already handled above, but needed for exhaustive when
                        checkNewtypeAbilityForTypeApplication(type, abilityName, typeArgs, definition)
                    }
                }
            }
        }

        return checkUserDefinedAbilityImpl(type, abilityName, typeArgs)
    }

    /**
     * Check if a type annotation references the given type name (self-reference).
     */
    private fun isSelfReference(annotation: TypeAnnotation, typeName: String): Boolean {
        return when (annotation) {
            is IdentifierTypeAnnotation -> annotation.name == typeName
            is TypeApplicationAnnotation -> annotation.constructor == typeName
            is RecordTypeAnnotation -> annotation.entries.any { isSelfReference(it.type, typeName) }
            else -> false
        }
    }

    // ========================================
    // Helper Functions
    // ========================================

    /**
     * Get the type name for ability implementation lookup.
     *
     * Uses special handling for named types (typeName) and Bool variants,
     * then falls back to the canonical ContentHashComputer.mezzeTypeToString.
     *
     * Results are cached by identity hash to avoid repeated computation.
     */
    private fun getTypeName(type: MezzeType): String {
        val key = System.identityHashCode(type)
        typeNameCache[key]?.let { return it }

        val result = when (type) {
            is MezzeType.RecordType -> {
                // Use typeName if available (e.g., "Person" for `type Person = { ... }`)
                type.typeName ?: ContentHashComputer.mezzeTypeToString(type)
            }
            is MezzeType.VariantType -> {
                // Use typeName if available (e.g., "Person" for `type Person = | ... |`)
                // Otherwise fall back to canonical naming
                type.typeName ?: if (BuiltInAbilities.isBoolVariant(type)) {
                    "Bool"
                } else {
                    ContentHashComputer.mezzeTypeToString(type)
                }
            }
            is MezzeType.SuspendedType -> getTypeName(type.valueType)
            // For all other types, delegate to canonical implementation
            else -> ContentHashComputer.mezzeTypeToString(type)
        }

        typeNameCache[key] = result
        return result
    }

    private fun formatType(type: MezzeType): String {
        // Use centralized registry for primitive types
        PrimitiveTypes.toName(type)?.let { return it }

        return when (type) {
            is MezzeType.VarType -> type.variable.toString()
            else -> type.toString()
        }
    }

    private fun checkUserDefinedAbilityImpl(
        type: MezzeType,
        abilityName: String,
        additionalTypeArgs: List<MezzeType> = emptyList()
    ): AbilityCheckResult {
        val typeName = getTypeName(type)

        // Search for implementation using structural type matching
        val allImpls = context.getAbilityImpls(abilityName)
        val matchingImpl = allImpls.find { impl ->
            // Use structural matching via targetTypeAnnotations
            val annotations = impl.targetTypeAnnotations ?: return@find false

            // Match primary type params against targetTypeAnnotations
            // Match associated types against associatedTypeBindings
            val associatedBindings = impl.associatedTypeBindings

            // IMPORTANT: additionalTypeArgs may contain the primary type as first element
            // when the constraint comes from a type annotation like "with Show 'a".
            // In that case, we should use additionalTypeArgs directly without prepending.
            // For "Show 'a" with 'a=Int: type=Int, additionalTypeArgs=[Int] -> allTypes=[Int]
            // For "Encode Json 'a" with 'a=Record: type=Record, additionalTypeArgs=[Json,Record] -> allTypes=[Json,Record]
            val allTypes = if (additionalTypeArgs.isNotEmpty() && additionalTypeArgs.any { it == type }) {
                // additionalTypeArgs already contains the primary type, use as-is
                additionalTypeArgs
            } else if (additionalTypeArgs.isEmpty()) {
                listOf(type)
            } else {
                // Prepend primary type for method call constraints
                listOf(type) + additionalTypeArgs
            }

            // Case 1: Simple matching with computed allTypes
            if (associatedBindings.isEmpty()) {
                annotations.size == allTypes.size &&
                allTypes.zip(annotations).all { (actualType, annotation) ->
                    typeMatchesAnnotation(actualType, annotation)
                }
            }
            // Case 2: Ability with associated types (e.g., Container 'c having 'item)
            else {
                // For abilities with associated types, only match the primary type params.
                // Associated types are determined by the impl itself, not by unification.
                // The additionalTypeArgs may contain unresolved VarTypes for associated types.
                annotations.size == 1 && typeMatchesAnnotation(type, annotations.first())
            }
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

    /**
     * Infer type arguments from a variant's concrete payload types by matching against
     * the type declaration's constructor patterns.
     */
    private fun inferTypeArgsFromVariant(
        variantType: MezzeType.VariantType,
        typeDecl: TypeDeclaration,
        definition: VariantDefinition
    ): List<MezzeType>? {
        // Map type parameter names to their inferred types
        val typeArgMap = mutableMapOf<String, MezzeType>()

        // For each constructor in the variant, try to match against the type declaration
        for ((constructorName, concretePayload) in variantType.constructors) {
            // Find the matching constructor in the type declaration
            val declConstructor = definition.constructors.find { it.name == constructorName } ?: continue

            // If the constructor has a data type annotation, try to extract type parameter mappings
            val dataTypeAnnotation = declConstructor.dataType ?: continue
            if (concretePayload == null) continue

            // Extract type parameter mappings by comparing the annotation with the concrete type
            extractTypeParamMappings(dataTypeAnnotation, concretePayload, typeDecl.typeParams, typeArgMap)
        }

        // Return the type arguments in order
        return if (typeArgMap.size == typeDecl.typeParams.size) {
            typeDecl.typeParams.map { typeArgMap[it] ?: return null }
        } else {
            null
        }
    }

    /**
     * Extract type parameter mappings by comparing a type annotation with a concrete type.
     */
    private fun extractTypeParamMappings(
        annotation: TypeAnnotation,
        concreteType: MezzeType,
        typeParams: List<String>,
        mappings: MutableMap<String, MezzeType>
    ) {
        when (annotation) {
            is IdentifierTypeAnnotation -> {
                // If this is a type parameter reference like 'a
                if (annotation.name in typeParams) {
                    // Map this type parameter to the concrete type
                    mappings[annotation.name] = concreteType
                }
            }
            is RecordTypeAnnotation -> {
                // Match record fields
                if (concreteType is MezzeType.RecordType) {
                    for (entry in annotation.entries) {
                        val fieldType = concreteType.fields[entry.name] ?: continue
                        extractTypeParamMappings(entry.type, fieldType, typeParams, mappings)
                    }
                }
            }
            is TypeApplicationAnnotation -> {
                // Match type applications like List 'a
                if (concreteType is MezzeType.TypeApplication && concreteType.constructor == annotation.constructor) {
                    for ((annArg, concreteArg) in annotation.args.zip(concreteType.args)) {
                        extractTypeParamMappings(annArg, concreteArg, typeParams, mappings)
                    }
                } else if (concreteType is MezzeType.SeqType && annotation.constructor == "Seq") {
                    // Handle Seq type specially
                    annotation.args.firstOrNull()?.let { elementAnnotation ->
                        extractTypeParamMappings(elementAnnotation, concreteType.elementType, typeParams, mappings)
                    }
                } else if (concreteType is MezzeType.ListType && annotation.constructor == "List") {
                    // Handle List type specially
                    annotation.args.firstOrNull()?.let { elementAnnotation ->
                        extractTypeParamMappings(elementAnnotation, concreteType.elementType, typeParams, mappings)
                    }
                }
            }
            is VariantTypeAnnotation -> {
                // Match variant constructors
                if (concreteType is MezzeType.VariantType) {
                    for (constructor in annotation.constructors) {
                        val concretePayload = concreteType.constructors[constructor.name] ?: continue
                        val annPayload = constructor.dataType ?: continue
                        extractTypeParamMappings(annPayload, concretePayload, typeParams, mappings)
                    }
                }
            }
            else -> {
                // Other annotation types don't contain type parameters we need to extract
            }
        }
    }

    /**
     * Check if a concrete MezzeType matches a TypeAnnotation.
     *
     * This enables structural matching for ability implementations.
     */
    private fun typeMatchesAnnotation(type: MezzeType, annotation: TypeAnnotation): Boolean {
        return when (annotation) {
            is IdentifierTypeAnnotation -> {
                when (annotation.name) {
                    "Int" -> type is MezzeType.IntType
                    "Float" -> type is MezzeType.FloatType
                    "Str" -> type is MezzeType.StringType
                    "Bool" -> type is MezzeType.BoolType || isBoolVariant(type)
                    "Nat" -> type is MezzeType.NatType
                    "Frac" -> type is MezzeType.FracType
                    "Char" -> type is MezzeType.CharType
                    "Bytes" -> type is MezzeType.BytesType
                    "ByteBuffer" -> type is MezzeType.ByteBufferType
                    "Ordering" -> type is MezzeType.OrderingType
                    else -> {
                        val typeDecl = typeDeclarations[annotation.name]
                        if (typeDecl != null) {
                            typeMatchesDeclaration(type, typeDecl)
                        } else {
                            when (type) {
                                is MezzeType.RecordType -> type.typeName == annotation.name
                                is MezzeType.VariantType -> type.typeName == annotation.name
                                is MezzeType.TypeApplication -> type.constructor == annotation.name && type.args.isEmpty()
                                else -> false
                            }
                        }
                    }
                }
            }
            is TypeApplicationAnnotation -> {
                when {
                    type is MezzeType.TypeApplication && type.constructor == annotation.constructor -> {
                        type.args.size == annotation.args.size &&
                        type.args.zip(annotation.args).all { (argType, argAnnotation) ->
                            typeMatchesAnnotation(argType, argAnnotation)
                        }
                    }
                    type is MezzeType.VariantType && type.typeName == null -> {
                        val typeDecl = typeDeclarations[annotation.constructor]
                        if (typeDecl != null && typeDecl.definition is VariantDefinition) {
                            val definition = typeDecl.definition as VariantDefinition
                            val declConstructors = definition.constructors.map { it.name }.toSet()
                            if (!declConstructors.containsAll(type.constructors.keys)) {
                                return false
                            }
                            val inferredArgs = inferTypeArgsFromVariant(type, typeDecl, definition)
                            if (inferredArgs != null && inferredArgs.size == annotation.args.size) {
                                inferredArgs.zip(annotation.args).all { (inferredType, argAnnotation) ->
                                    typeMatchesAnnotation(inferredType, argAnnotation)
                                }
                            } else {
                                false
                            }
                        } else {
                            false
                        }
                    }
                    type is MezzeType.SeqType && annotation.constructor == "Seq" -> {
                        annotation.args.size == 1 && typeMatchesAnnotation(type.elementType, annotation.args[0])
                    }
                    type is MezzeType.ListType && annotation.constructor == "List" -> {
                        annotation.args.size == 1 && typeMatchesAnnotation(type.elementType, annotation.args[0])
                    }
                    else -> false
                }
            }
            is RecordTypeAnnotation -> {
                if (type !is MezzeType.RecordType) return false
                if (type.fields.size != annotation.entries.size) return false
                annotation.entries.all { entry ->
                    val fieldType = type.fields[entry.name] ?: return false
                    typeMatchesAnnotation(fieldType, entry.type)
                }
            }
            is VariantTypeAnnotation -> {
                if (type !is MezzeType.VariantType) return false
                annotation.constructors.all { constructor ->
                    val concretePayload = type.constructors[constructor.name]
                    val dataType = constructor.dataType
                    if (dataType == null) {
                        concretePayload == null
                    } else if (concretePayload == null) {
                        false
                    } else {
                        typeMatchesAnnotation(concretePayload, dataType)
                    }
                }
            }
            is SeqTypeAnnotation -> {
                // Handle [T] syntax for seq types
                type is MezzeType.SeqType && typeMatchesAnnotation(type.elementType, annotation.elementType)
            }
            else -> false
        }
    }

    private fun typeMatchesDeclaration(type: MezzeType, typeDecl: TypeDeclaration): Boolean {
        if (type is MezzeType.TypeApplication) {
            return type.constructor == typeDecl.name && type.args.isEmpty()
        }
        return when (val definition = typeDecl.definition) {
            is RecordDefinition -> {
                if (type !is MezzeType.RecordType) return false
                type.typeName == typeDecl.name || type.fields.keys == definition.fields.map { it.name }.toSet()
            }
            is VariantDefinition -> {
                if (type !is MezzeType.VariantType) return false
                type.typeName == typeDecl.name ||
                definition.constructors.map { it.name }.toSet().containsAll(type.constructors.keys)
            }
            else -> false
        }
    }

    private fun isBoolVariant(type: MezzeType): Boolean {
        if (type !is MezzeType.VariantType) return false
        val constructors = type.constructors.keys
        return constructors.size == 2 && constructors.contains("True") && constructors.contains("False")
    }
}
