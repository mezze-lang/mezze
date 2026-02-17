package com.mezze.language.typechecker.types

import com.mezze.language.ast.MezzeType

/**
 * Helper function to create Option type (as TypeApplication)
 */
private fun optionType(elementType: MezzeType): MezzeType {
    return MezzeType.TypeApplication("Option", listOf(elementType))
}

/**
 * Helper function to create Result type (as TypeApplication)
 * Note: Result takes value type first, then error type
 */
private fun resultType(valueType: MezzeType, errorType: MezzeType): MezzeType {
    return MezzeType.TypeApplication("Result", listOf(valueType, errorType))
}

/**
 * Type signatures for basic numeric and string types:
 * int, float, nat, bool, string, frac
 *
 * Corresponds to stdlib files:
 * - std/int.mz
 * - std/float.mz
 * - std/nat.mz
 * - std/bool.mz
 * - std/string.mz
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

        context["__native_int_to_string"] = MezzeType.FunctionType(
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

        context["__native_int_equals"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.IntType, "r" to MezzeType.IntType)),
            MezzeType.BooleanType
        )

        context["__native_int_less_than"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.IntType, "r" to MezzeType.IntType)),
            MezzeType.BooleanType
        )

        context["__native_int_greater_than"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.IntType, "r" to MezzeType.IntType)),
            MezzeType.BooleanType
        )

        // Module-qualified arithmetic functions for int
        context["int::add"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.IntType, "r" to MezzeType.IntType)),
            MezzeType.IntType
        )

        context["int::subtract"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.IntType, "r" to MezzeType.IntType)),
            MezzeType.IntType
        )

        context["int::multiply"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.IntType, "r" to MezzeType.IntType)),
            MezzeType.IntType
        )

        context["int::divide"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.IntType, "r" to MezzeType.IntType)),
            MezzeType.IntType
        )

        context["int::modulo"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.IntType, "r" to MezzeType.IntType)),
            MezzeType.IntType
        )

        context["int::to_string"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.IntType)),
            MezzeType.StringType
        )

        context["int::from_string"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.StringType)),
            optionType(MezzeType.IntType)
        )

        context["int::from_nat"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.NatType)),
            MezzeType.IntType
        )

        context["__native_int_to_frac"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.IntType)),
            MezzeType.FracType
        )

        context["__native_int_pow"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "base" to MezzeType.IntType,
                "exp" to MezzeType.IntType
            )),
            MezzeType.IntType
        )

        context["__native_int_sign"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.IntType)),
            MezzeType.IntType
        )
    }

    private fun registerFloatTypes(context: MutableMap<String, MezzeType>) {
        // Native float operations
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

        context["__native_float_pow"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("base" to MezzeType.FloatType, "exp" to MezzeType.FloatType)),
            MezzeType.FloatType
        )

        context["__native_float_sqrt"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.FloatType
        )

        context["__native_float_floor"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.FloatType
        )

        context["__native_float_ceil"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.FloatType
        )

        context["__native_float_round"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.FloatType
        )

        context["__native_float_to_string"] = MezzeType.FunctionType(
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

        // Module-qualified functions for float
        context["float::abs"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("x" to MezzeType.FloatType)),
            MezzeType.FloatType
        )

        context["float::min"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.FloatType, "r" to MezzeType.FloatType)),
            MezzeType.FloatType
        )

        context["float::max"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.FloatType, "r" to MezzeType.FloatType)),
            MezzeType.FloatType
        )

        context["float::pow"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("base" to MezzeType.FloatType, "exp" to MezzeType.FloatType)),
            MezzeType.FloatType
        )

        context["float::sqrt"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("x" to MezzeType.FloatType)),
            MezzeType.FloatType
        )

        context["float::floor"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("x" to MezzeType.FloatType)),
            MezzeType.FloatType
        )

        context["float::ceil"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("x" to MezzeType.FloatType)),
            MezzeType.FloatType
        )

        context["float::round"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("x" to MezzeType.FloatType)),
            MezzeType.FloatType
        )

        context["float::from_int"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.IntType)),
            MezzeType.FloatType
        )

        context["float::from_nat"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.NatType)),
            MezzeType.FloatType
        )

        context["float::from_string"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.StringType)),
            MezzeType.FloatType
        )

        context["__native_float_ln"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.FloatType
        )

        context["__native_float_log10"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.FloatType
        )

        context["__native_float_exp"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.FloatType
        )

        context["__native_float_is_nan"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.BooleanType
        )

        context["__native_float_is_infinite"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.BooleanType
        )

        context["__native_float_is_finite"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.BooleanType
        )

        context["__native_float_sign"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.FloatType
        )

        context["__native_float_sin"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.FloatType
        )

        context["__native_float_cos"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.FloatType
        )

        context["__native_float_tan"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.FloatType
        )

        context["__native_float_asin"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.FloatType
        )

        context["__native_float_acos"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.FloatType
        )

        context["__native_float_atan"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.FloatType
        )

        context["__native_float_atan2"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "y" to MezzeType.FloatType,
                "x" to MezzeType.FloatType
            )),
            MezzeType.FloatType
        )
    }

    private fun registerNatTypes(context: MutableMap<String, MezzeType>) {
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

        // Module-qualified functions for nat
        context["nat::subtract_to_int"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "l" to MezzeType.NatType,
                "r" to MezzeType.NatType
            )),
            MezzeType.IntType
        )

        context["nat::from_int"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.IntType)),
            optionType(MezzeType.NatType)
        )

        context["nat::from_float"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            optionType(MezzeType.NatType)
        )

        context["nat::from_string"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.StringType)),
            optionType(MezzeType.NatType)
        )

        context["nat::min"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "l" to MezzeType.NatType,
                "r" to MezzeType.NatType
            )),
            MezzeType.NatType
        )

        context["nat::max"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "l" to MezzeType.NatType,
                "r" to MezzeType.NatType
            )),
            MezzeType.NatType
        )

        context["nat::is_zero"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.NatType)),
            MezzeType.BooleanType
        )

        context["nat::is_even"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.NatType)),
            MezzeType.BooleanType
        )

        context["nat::is_odd"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.NatType)),
            MezzeType.BooleanType
        )

        context["__native_nat_to_frac"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.NatType)),
            MezzeType.FracType
        )

        context["__native_nat_pow"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "base" to MezzeType.NatType,
                "exp" to MezzeType.NatType
            )),
            MezzeType.NatType
        )
    }

    private fun registerBoolTypes(context: MutableMap<String, MezzeType>) {
        context["__native_boolean_to_string"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("value" to MezzeType.BooleanType)),
            MezzeType.StringType
        )

        // Module-qualified functions for boolean
        context["boolean::and"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.BooleanType, "r" to MezzeType.BooleanType)),
            MezzeType.BooleanType
        )

        context["boolean::or"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.BooleanType, "r" to MezzeType.BooleanType)),
            MezzeType.BooleanType
        )

        context["boolean::not"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("b" to MezzeType.BooleanType)),
            MezzeType.BooleanType
        )

        context["boolean::xor"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.BooleanType, "r" to MezzeType.BooleanType)),
            MezzeType.BooleanType
        )

        context["boolean::implies"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.BooleanType, "r" to MezzeType.BooleanType)),
            MezzeType.BooleanType
        )

        context["boolean::iff"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.BooleanType, "r" to MezzeType.BooleanType)),
            MezzeType.BooleanType
        )

        // Aliases for std::bool module (module name is bool, not boolean)
        context["bool::and"] = context["boolean::and"]!!
        context["bool::or"] = context["boolean::or"]!!
        context["bool::not"] = context["boolean::not"]!!
        context["bool::xor"] = context["boolean::xor"]!!
        context["bool::implies"] = context["boolean::implies"]!!
        context["bool::iff"] = context["boolean::iff"]!!
    }

    private fun registerStringTypes(context: MutableMap<String, MezzeType>) {
        context["__native_string_to_string"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("value" to MezzeType.StringType)),
            MezzeType.StringType
        )

        // Native string operations
        context["__native_string_concat"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.StringType, "r" to MezzeType.StringType)),
            MezzeType.StringType
        )

        context["__native_string_length"] = MezzeType.FunctionType(
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
            MezzeType.BooleanType
        )

        context["__native_string_ends_with"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.StringType, "suffix" to MezzeType.StringType)),
            MezzeType.BooleanType
        )

        context["__native_string_contains"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.StringType, "substring" to MezzeType.StringType)),
            MezzeType.BooleanType
        )

        context["__native_string_split"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.StringType, "delimiter" to MezzeType.StringType)),
            MezzeType.ArrayType(MezzeType.StringType)
        )

        context["__native_string_join"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("strings" to MezzeType.ArrayType(MezzeType.StringType), "separator" to MezzeType.StringType)),
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

        // Module-qualified functions for string
        context["string::concat"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("l" to MezzeType.StringType, "r" to MezzeType.StringType)),
            MezzeType.StringType
        )

        context["string::length"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.StringType)),
            MezzeType.IntType
        )

        context["string::is_empty"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.StringType)),
            MezzeType.BooleanType
        )

        context["string::to_upper"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.StringType)),
            MezzeType.StringType
        )

        context["string::to_lower"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.StringType)),
            MezzeType.StringType
        )

        context["string::trim"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.StringType)),
            MezzeType.StringType
        )

        context["string::reverse"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.StringType)),
            MezzeType.StringType
        )

        context["string::starts_with"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.StringType, "prefix" to MezzeType.StringType)),
            MezzeType.BooleanType
        )

        context["string::ends_with"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.StringType, "suffix" to MezzeType.StringType)),
            MezzeType.BooleanType
        )

        context["string::contains"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.StringType, "substring" to MezzeType.StringType)),
            MezzeType.BooleanType
        )

        context["string::repeat"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.StringType, "n" to MezzeType.IntType)),
            MezzeType.StringType
        )

        context["string::split"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.StringType, "delimiter" to MezzeType.StringType)),
            MezzeType.ArrayType(MezzeType.StringType)
        )

        context["string::join"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("strings" to MezzeType.ArrayType(MezzeType.StringType), "separator" to MezzeType.StringType)),
            MezzeType.StringType
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
            MezzeType.BooleanType
        )

        context["__native_frac_less_than"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "l" to MezzeType.FracType,
                "r" to MezzeType.FracType
            )),
            MezzeType.BooleanType
        )

        context["__native_frac_greater_than"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "l" to MezzeType.FracType,
                "r" to MezzeType.FracType
            )),
            MezzeType.BooleanType
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

        context["__native_frac_to_string"] = MezzeType.FunctionType(
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

        // Module-qualified functions for frac
        context["frac::add"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "l" to MezzeType.FracType,
                "r" to MezzeType.FracType
            )),
            MezzeType.FracType
        )

        context["frac::subtract"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "l" to MezzeType.FracType,
                "r" to MezzeType.FracType
            )),
            MezzeType.FracType
        )

        context["frac::multiply"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "l" to MezzeType.FracType,
                "r" to MezzeType.FracType
            )),
            MezzeType.FracType
        )

        context["frac::divide"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "l" to MezzeType.FracType,
                "r" to MezzeType.FracType
            )),
            MezzeType.FracType
        )

        context["frac::equals"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "l" to MezzeType.FracType,
                "r" to MezzeType.FracType
            )),
            MezzeType.BooleanType
        )

        context["frac::less_than"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "l" to MezzeType.FracType,
                "r" to MezzeType.FracType
            )),
            MezzeType.BooleanType
        )

        context["frac::greater_than"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "l" to MezzeType.FracType,
                "r" to MezzeType.FracType
            )),
            MezzeType.BooleanType
        )

        context["frac::to_float"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FracType)),
            MezzeType.FloatType
        )

        context["frac::to_string"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FracType)),
            MezzeType.StringType
        )

        context["frac::make"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "numerator" to MezzeType.IntType,
                "denominator" to MezzeType.IntType
            )),
            MezzeType.FracType
        )

        context["frac::from_int"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.IntType)),
            MezzeType.FracType
        )

        context["frac::from_nat"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.NatType)),
            MezzeType.FracType
        )

        context["frac::numerator"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FracType)),
            MezzeType.IntType
        )

        context["frac::denominator"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FracType)),
            MezzeType.IntType
        )

        context["frac::reduce"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FracType)),
            MezzeType.FracType
        )

        context["frac::abs"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FracType)),
            MezzeType.FracType
        )

        context["frac::negate"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FracType)),
            MezzeType.FracType
        )
    }
}
