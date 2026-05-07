package com.mezze.language.typechecker.types

import com.mezze.language.ast.MezzeType
import com.mezze.language.typechecker.util.WellKnownTypeApp

/**
 * Helper function to create Option type (as TypeApplication).
 * Routes through WellKnownTypeApp so `typeHash` is populated when known (#309).
 */
private fun optionType(elementType: MezzeType): MezzeType =
    WellKnownTypeApp.of("Option", listOf(elementType))

/**
 * Helper function to create Result type (as TypeApplication).
 * Note: Result takes value type first, then error type.
 */
private fun resultType(valueType: MezzeType, errorType: MezzeType): MezzeType =
    WellKnownTypeApp.of("Result", listOf(valueType, errorType))

/**
 * Type signatures for basic numeric and string types:
 * int, float, nat, bool, string, frac
 *
 * Corresponds to stdlib files:
 * - std/int.mz
 * - std/float.mz
 * - std/nat.mz
 * - std/bool.mz
 * - std/str.mz
 * - std/frac.mz
 */
object BasicTypes {
    fun registerAll(context: MutableMap<String, MezzeType>) {
        registerIntTypes(context)
        registerFloatTypes(context)
        registerNatTypes(context)
        registerBoolTypes(context)
        registerStringTypes(context)
        registerFracTypes(context)
    }

    private fun registerIntTypes(context: MutableMap<String, MezzeType>) {
        // Native arithmetic operations for int module
        context["__native_int_add"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.IntType, "r" to MezzeType.IntType)),
            MezzeType.IntType
        )

        context["__native_int_subtract"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.IntType, "r" to MezzeType.IntType)),
            MezzeType.IntType
        )

        context["__native_int_multiply"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.IntType, "r" to MezzeType.IntType)),
            MezzeType.IntType
        )

        context["__native_int_divide"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.IntType, "r" to MezzeType.IntType)),
            MezzeType.IntType
        )

        context["__native_int_modulo"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.IntType, "r" to MezzeType.IntType)),
            MezzeType.IntType
        )

        context["__native_int_neg"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.IntType)),
            MezzeType.IntType
        )

        context["__native_int_to_str"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.IntType)),
            MezzeType.StringType
        )

        context["__native_int_from_string"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.StringType)),
            optionType(MezzeType.IntType)
        )

        context["__native_int_from_nat"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.NatType)),
            MezzeType.IntType
        )

        // Comparison native functions for Eq/Ord ability impls
        context["__native_int_eq"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.IntType, "r" to MezzeType.IntType)),
            MezzeType.BoolType
        )
        context["__native_int_neq"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.IntType, "r" to MezzeType.IntType)),
            MezzeType.BoolType
        )
        context["__native_int_lt"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.IntType, "r" to MezzeType.IntType)),
            MezzeType.BoolType
        )
        context["__native_int_le"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.IntType, "r" to MezzeType.IntType)),
            MezzeType.BoolType
        )
        context["__native_int_gt"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.IntType, "r" to MezzeType.IntType)),
            MezzeType.BoolType
        )
        context["__native_int_ge"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.IntType, "r" to MezzeType.IntType)),
            MezzeType.BoolType
        )

        context["__native_int_to_frac"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.IntType)),
            MezzeType.FracType
        )

        context["__native_int_to_float"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.IntType)),
            MezzeType.FloatType
        )

        context["__native_int_abs"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.IntType)),
            MezzeType.IntType
        )

        context["__native_int_min"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.IntType, "r" to MezzeType.IntType)),
            MezzeType.IntType
        )

        context["__native_int_max"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.IntType, "r" to MezzeType.IntType)),
            MezzeType.IntType
        )

        // Clamp
        context["__native_int_clamp"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.IntType, "min" to MezzeType.IntType, "max" to MezzeType.IntType)),
            MezzeType.IntType
        )

        // To Nat
        context["__native_int_to_nat"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.IntType)),
            optionType(MezzeType.NatType)
        )

        // Note: __native_int_pow and __native_int_sign are registered in MathCoreTypes.kt

        context["__native_int_gcd"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.IntType, "r" to MezzeType.IntType)),
            MezzeType.IntType
        )

        context["__native_int_lcm"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.IntType, "r" to MezzeType.IntType)),
            MezzeType.IntType
        )

        context["__native_int_is_even"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.IntType)),
            MezzeType.BoolType
        )

        context["__native_int_is_odd"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.IntType)),
            MezzeType.BoolType
        )
    }

    private fun registerFloatTypes(context: MutableMap<String, MezzeType>) {
        // Note: Math functions (__native_float_pow, sqrt, floor, ceil, round) are in MathCoreTypes.kt

        // Float arithmetic for ability impls
        context["__native_float_add"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.FloatType, "r" to MezzeType.FloatType)),
            MezzeType.FloatType
        )
        context["__native_float_subtract"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.FloatType, "r" to MezzeType.FloatType)),
            MezzeType.FloatType
        )
        context["__native_float_multiply"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.FloatType, "r" to MezzeType.FloatType)),
            MezzeType.FloatType
        )
        context["__native_float_divide"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.FloatType, "r" to MezzeType.FloatType)),
            MezzeType.FloatType
        )
        context["__native_float_mod"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.FloatType, "r" to MezzeType.FloatType)),
            MezzeType.FloatType
        )
        context["__native_float_neg"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.FloatType
        )

        // Float comparisons for ability impls
        context["__native_float_eq"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.FloatType, "r" to MezzeType.FloatType)),
            MezzeType.BoolType
        )
        context["__native_float_neq"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.FloatType, "r" to MezzeType.FloatType)),
            MezzeType.BoolType
        )
        context["__native_float_lt"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.FloatType, "r" to MezzeType.FloatType)),
            MezzeType.BoolType
        )
        context["__native_float_le"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.FloatType, "r" to MezzeType.FloatType)),
            MezzeType.BoolType
        )
        context["__native_float_gt"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.FloatType, "r" to MezzeType.FloatType)),
            MezzeType.BoolType
        )
        context["__native_float_ge"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.FloatType, "r" to MezzeType.FloatType)),
            MezzeType.BoolType
        )

        context["__native_float_to_str"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.StringType
        )

        context["__native_float_from_int"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.IntType)),
            MezzeType.FloatType
        )

        context["__native_float_from_nat"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.NatType)),
            MezzeType.FloatType
        )

        context["__native_float_from_string"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.StringType)),
            optionType(MezzeType.FloatType)
        )

        context["__native_float_abs"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.FloatType
        )

        context["__native_float_min"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.FloatType, "r" to MezzeType.FloatType)),
            MezzeType.FloatType
        )

        context["__native_float_max"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.FloatType, "r" to MezzeType.FloatType)),
            MezzeType.FloatType
        )

        // Float classification
        context["__native_float_is_nan"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.BoolType
        )

        context["__native_float_is_infinite"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.BoolType
        )

        context["__native_float_is_finite"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.BoolType
        )

        // Float sign
        context["__native_float_sign"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.FloatType
        )

        // Float clamp
        context["__native_float_clamp"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType, "min" to MezzeType.FloatType, "max" to MezzeType.FloatType)),
            MezzeType.FloatType
        )

        // Float to Int
        context["__native_float_to_int"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            optionType(MezzeType.IntType)
        )

        // Note: Math functions (__native_float_ln, log10, exp, is_nan, is_infinite, is_finite, sign)
        // are registered in MathCoreTypes.kt
        // Note: Trig functions (__native_float_sin, cos, tan, asin, acos, atan, atan2)
        // are registered in MathTrigTypes.kt
    }

    private fun registerNatTypes(context: MutableMap<String, MezzeType>) {
        // Nat arithmetic for ability impls
        context["__native_nat_add"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.NatType, "r" to MezzeType.NatType)),
            MezzeType.NatType
        )
        context["__native_nat_multiply"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.NatType, "r" to MezzeType.NatType)),
            MezzeType.NatType
        )
        context["__native_nat_divide"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.NatType, "r" to MezzeType.NatType)),
            MezzeType.NatType
        )
        context["__native_nat_modulo"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.NatType, "r" to MezzeType.NatType)),
            MezzeType.NatType
        )
        // Nat comparisons for ability impls
        context["__native_nat_eq"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.NatType, "r" to MezzeType.NatType)),
            MezzeType.BoolType
        )
        context["__native_nat_neq"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.NatType, "r" to MezzeType.NatType)),
            MezzeType.BoolType
        )
        context["__native_nat_lt"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.NatType, "r" to MezzeType.NatType)),
            MezzeType.BoolType
        )
        context["__native_nat_le"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.NatType, "r" to MezzeType.NatType)),
            MezzeType.BoolType
        )
        context["__native_nat_gt"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.NatType, "r" to MezzeType.NatType)),
            MezzeType.BoolType
        )
        context["__native_nat_ge"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.NatType, "r" to MezzeType.NatType)),
            MezzeType.BoolType
        )

        // Native nat operations
        context["__native_nat_subtract_to_int"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "l" to MezzeType.NatType,
                "r" to MezzeType.NatType
            )),
            MezzeType.IntType
        )

        context["__native_nat_from_int"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.IntType)),
            optionType(MezzeType.NatType)
        )

        context["__native_nat_from_float"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            optionType(MezzeType.NatType)
        )

        context["__native_nat_from_string"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.StringType)),
            optionType(MezzeType.NatType)
        )

        context["__native_nat_to_frac"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.NatType)),
            MezzeType.FracType
        )

        context["__native_nat_min"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.NatType, "r" to MezzeType.NatType)),
            MezzeType.NatType
        )

        context["__native_nat_max"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.NatType, "r" to MezzeType.NatType)),
            MezzeType.NatType
        )

        // To Int
        context["__native_nat_to_int"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.NatType)),
            MezzeType.IntType
        )

        // To String
        context["__native_nat_to_str"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.NatType)),
            MezzeType.StringType
        )

        // Clamp
        context["__native_nat_clamp"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.NatType, "min" to MezzeType.NatType, "max" to MezzeType.NatType)),
            MezzeType.NatType
        )

        // Note: __native_nat_pow is registered in MathCoreTypes.kt
    }

    private fun registerBoolTypes(context: MutableMap<String, MezzeType>) {
        // Bool comparisons for ability impls
        context["__native_bool_eq"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.BoolType, "r" to MezzeType.BoolType)),
            MezzeType.BoolType
        )
        context["__native_bool_neq"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.BoolType, "r" to MezzeType.BoolType)),
            MezzeType.BoolType
        )

        // Native bool operations for stdlib bool module
        context["__native_bool_and"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.BoolType, "r" to MezzeType.BoolType)),
            MezzeType.BoolType
        )

        context["__native_bool_or"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.BoolType, "r" to MezzeType.BoolType)),
            MezzeType.BoolType
        )

        context["__native_bool_not"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("b" to MezzeType.BoolType)),
            MezzeType.BoolType
        )

        context["__native_bool_xor"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.BoolType, "r" to MezzeType.BoolType)),
            MezzeType.BoolType
        )

        context["__native_bool_to_str"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("b" to MezzeType.BoolType)),
            MezzeType.StringType
        )

    }

    private fun registerStringTypes(context: MutableMap<String, MezzeType>) {
        // String comparisons for ability impls
        context["__native_string_eq"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.StringType, "r" to MezzeType.StringType)),
            MezzeType.BoolType
        )
        context["__native_string_neq"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.StringType, "r" to MezzeType.StringType)),
            MezzeType.BoolType
        )
        context["__native_string_lt"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.StringType, "r" to MezzeType.StringType)),
            MezzeType.BoolType
        )
        context["__native_string_le"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.StringType, "r" to MezzeType.StringType)),
            MezzeType.BoolType
        )
        context["__native_string_gt"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.StringType, "r" to MezzeType.StringType)),
            MezzeType.BoolType
        )
        context["__native_string_ge"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.StringType, "r" to MezzeType.StringType)),
            MezzeType.BoolType
        )

        context["__native_string_to_str"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("value" to MezzeType.StringType)),
            MezzeType.StringType
        )

        // Native string operations
        context["__native_string_concat"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.StringType, "r" to MezzeType.StringType)),
            MezzeType.StringType
        )

        context["__native_string_size"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.StringType)),
            MezzeType.IntType
        )

        context["__native_string_to_upper"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.StringType)),
            MezzeType.StringType
        )

        context["__native_string_to_lower"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.StringType)),
            MezzeType.StringType
        )

        context["__native_string_trim"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.StringType)),
            MezzeType.StringType
        )

        context["__native_string_reverse"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.StringType)),
            MezzeType.StringType
        )

        context["__native_string_starts_with"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.StringType, "prefix" to MezzeType.StringType)),
            MezzeType.BoolType
        )

        context["__native_string_ends_with"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.StringType, "suffix" to MezzeType.StringType)),
            MezzeType.BoolType
        )

        context["__native_string_contains"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.StringType, "substring" to MezzeType.StringType)),
            MezzeType.BoolType
        )

        context["__native_string_split"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.StringType, "delimiter" to MezzeType.StringType)),
            MezzeType.SeqType(MezzeType.StringType)
        )

        context["__native_string_join"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("strings" to MezzeType.SeqType(MezzeType.StringType), "separator" to MezzeType.StringType)),
            MezzeType.StringType
        )

        // Case conversion functions (for codec field transforms)
        context["__native_string_to_snake_case"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.StringType)),
            MezzeType.StringType
        )
        context["__native_string_to_camel_case"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.StringType)),
            MezzeType.StringType
        )
        context["__native_string_to_kebab_case"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.StringType)),
            MezzeType.StringType
        )
        context["__native_string_to_screaming_snake_case"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.StringType)),
            MezzeType.StringType
        )

        // === New String Utility Functions ===

        // Substring
        context["__native_string_substring"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.StringType, "start" to MezzeType.IntType, "end" to MezzeType.IntType)),
            MezzeType.StringType
        )

        // Replace
        context["__native_string_replace"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.StringType, "old" to MezzeType.StringType, "new" to MezzeType.StringType)),
            MezzeType.StringType
        )

        context["__native_string_replace_all"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.StringType, "old" to MezzeType.StringType, "new" to MezzeType.StringType)),
            MezzeType.StringType
        )

        // Character access
        context["__native_string_char_at"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.StringType, "i" to MezzeType.IntType)),
            optionType(MezzeType.CharType)
        )

        // Index search
        context["__native_string_index_of"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.StringType, "sub" to MezzeType.StringType)),
            optionType(MezzeType.IntType)
        )

        context["__native_string_last_index_of"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.StringType, "sub" to MezzeType.StringType)),
            optionType(MezzeType.IntType)
        )

        // Trimming
        context["__native_string_trim_start"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.StringType)),
            MezzeType.StringType
        )

        context["__native_string_trim_end"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.StringType)),
            MezzeType.StringType
        )

        // Splitting utilities
        context["__native_string_lines"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.StringType)),
            MezzeType.SeqType(MezzeType.StringType)
        )

        context["__native_string_words"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.StringType)),
            MezzeType.SeqType(MezzeType.StringType)
        )

        context["__native_string_chars"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.StringType)),
            MezzeType.SeqType(MezzeType.CharType)
        )

        // Padding
        context["__native_string_pad_left"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.StringType, "len" to MezzeType.IntType, "c" to MezzeType.CharType)),
            MezzeType.StringType
        )

        context["__native_string_pad_right"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.StringType, "len" to MezzeType.IntType, "c" to MezzeType.CharType)),
            MezzeType.StringType
        )

        // Glob pattern matching
        context["__native_string_glob_match"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("pattern" to MezzeType.StringType, "s" to MezzeType.StringType)),
            MezzeType.BoolType
        )
    }

    private fun registerFracTypes(context: MutableMap<String, MezzeType>) {
        // Native frac operations
        context["__native_frac_add"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "l" to MezzeType.FracType,
                "r" to MezzeType.FracType
            )),
            MezzeType.FracType
        )

        context["__native_frac_subtract"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "l" to MezzeType.FracType,
                "r" to MezzeType.FracType
            )),
            MezzeType.FracType
        )

        context["__native_frac_multiply"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "l" to MezzeType.FracType,
                "r" to MezzeType.FracType
            )),
            MezzeType.FracType
        )

        context["__native_frac_divide"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "l" to MezzeType.FracType,
                "r" to MezzeType.FracType
            )),
            MezzeType.FracType
        )

        context["__native_frac_equals"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "l" to MezzeType.FracType,
                "r" to MezzeType.FracType
            )),
            MezzeType.BoolType
        )

        context["__native_frac_less_than"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "l" to MezzeType.FracType,
                "r" to MezzeType.FracType
            )),
            MezzeType.BoolType
        )

        context["__native_frac_greater_than"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "l" to MezzeType.FracType,
                "r" to MezzeType.FracType
            )),
            MezzeType.BoolType
        )

        // Ordering comparison type
        val orderingType = MezzeType.VariantType(mapOf(
            "LT" to null,
            "EQ" to null,
            "GT" to null
        ))

        context["__native_frac_compare"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "l" to MezzeType.FracType,
                "r" to MezzeType.FracType
            )),
            orderingType
        )

        context["__native_frac_to_float"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FracType)),
            MezzeType.FloatType
        )

        context["__native_frac_to_str"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FracType)),
            MezzeType.StringType
        )

        context["__native_make_frac"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "numerator" to MezzeType.IntType,
                "denominator" to MezzeType.IntType
            )),
            MezzeType.FracType
        )

        context["__native_frac_numerator"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FracType)),
            MezzeType.IntType
        )

        context["__native_frac_denominator"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FracType)),
            MezzeType.IntType
        )

        context["__native_reduce_frac"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FracType)),
            MezzeType.FracType
        )

        context["__native_abs_frac"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FracType)),
            MezzeType.FracType
        )

        context["__native_negate_frac"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FracType)),
            MezzeType.FracType
        )

    }
}
