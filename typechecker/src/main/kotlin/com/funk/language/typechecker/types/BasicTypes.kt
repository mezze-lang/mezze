package com.funk.language.typechecker.types

import com.funk.language.ast.FunkType

/**
 * Helper function to create Option type (as TypeApplication)
 */
private fun optionType(elementType: FunkType): FunkType {
    return FunkType.TypeApplication("Option", listOf(elementType))
}

/**
 * Helper function to create Result type (as TypeApplication)
 * Note: Result takes value type first, then error type
 */
private fun resultType(valueType: FunkType, errorType: FunkType): FunkType {
    return FunkType.TypeApplication("Result", listOf(valueType, errorType))
}

/**
 * Type signatures for basic numeric and string types:
 * int, float, nat, bool, string, frac
 *
 * Corresponds to stdlib files:
 * - std/int.funk
 * - std/float.funk
 * - std/nat.funk
 * - std/bool.funk
 * - std/string.funk
 * - std/frac.funk
 */
object BasicTypes {
    fun registerAll(context: MutableMap<String, FunkType>) {
        registerIntTypes(context)
        registerFloatTypes(context)
        registerNatTypes(context)
        registerBoolTypes(context)
        registerStringTypes(context)
        registerFracTypes(context)
    }

    private fun registerIntTypes(context: MutableMap<String, FunkType>) {
        // Native arithmetic operations for int module
        context["__native_int_add"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("l" to FunkType.IntType, "r" to FunkType.IntType)),
            FunkType.IntType
        )

        context["__native_int_subtract"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("l" to FunkType.IntType, "r" to FunkType.IntType)),
            FunkType.IntType
        )

        context["__native_int_multiply"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("l" to FunkType.IntType, "r" to FunkType.IntType)),
            FunkType.IntType
        )

        context["__native_int_divide"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("l" to FunkType.IntType, "r" to FunkType.IntType)),
            FunkType.IntType
        )

        context["__native_int_modulo"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("l" to FunkType.IntType, "r" to FunkType.IntType)),
            FunkType.IntType
        )

        context["__native_int_to_string"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.IntType)),
            FunkType.StringType
        )

        context["__native_int_from_string"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to FunkType.StringType)),
            optionType(FunkType.IntType)
        )

        context["__native_int_from_nat"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.NatType)),
            FunkType.IntType
        )

        context["__native_int_equals"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("l" to FunkType.IntType, "r" to FunkType.IntType)),
            FunkType.BooleanType
        )

        context["__native_int_less_than"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("l" to FunkType.IntType, "r" to FunkType.IntType)),
            FunkType.BooleanType
        )

        context["__native_int_greater_than"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("l" to FunkType.IntType, "r" to FunkType.IntType)),
            FunkType.BooleanType
        )

        // Module-qualified arithmetic functions for int
        context["int::add"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("l" to FunkType.IntType, "r" to FunkType.IntType)),
            FunkType.IntType
        )

        context["int::subtract"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("l" to FunkType.IntType, "r" to FunkType.IntType)),
            FunkType.IntType
        )

        context["int::multiply"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("l" to FunkType.IntType, "r" to FunkType.IntType)),
            FunkType.IntType
        )

        context["int::divide"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("l" to FunkType.IntType, "r" to FunkType.IntType)),
            FunkType.IntType
        )

        context["int::modulo"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("l" to FunkType.IntType, "r" to FunkType.IntType)),
            FunkType.IntType
        )

        context["int::to_string"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.IntType)),
            FunkType.StringType
        )

        context["int::from_string"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to FunkType.StringType)),
            optionType(FunkType.IntType)
        )

        context["int::from_nat"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.NatType)),
            FunkType.IntType
        )

        context["__native_int_to_frac"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.IntType)),
            FunkType.FracType
        )

        context["__native_int_pow"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "base" to FunkType.IntType,
                "exp" to FunkType.IntType
            )),
            FunkType.IntType
        )

        context["__native_int_sign"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.IntType)),
            FunkType.IntType
        )
    }

    private fun registerFloatTypes(context: MutableMap<String, FunkType>) {
        // Native float operations
        context["__native_float_add"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("l" to FunkType.FloatType, "r" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["__native_float_subtract"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("l" to FunkType.FloatType, "r" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["__native_float_multiply"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("l" to FunkType.FloatType, "r" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["__native_float_divide"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("l" to FunkType.FloatType, "r" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["__native_float_pow"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("base" to FunkType.FloatType, "exp" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["__native_float_sqrt"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["__native_float_floor"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["__native_float_ceil"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["__native_float_round"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["__native_float_to_string"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.StringType
        )

        context["__native_float_from_int"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.IntType)),
            FunkType.FloatType
        )

        context["__native_float_from_nat"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.NatType)),
            FunkType.FloatType
        )

        context["__native_float_from_string"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to FunkType.StringType)),
            optionType(FunkType.FloatType)
        )

        // Module-qualified functions for float
        context["float::abs"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("x" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["float::min"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("l" to FunkType.FloatType, "r" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["float::max"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("l" to FunkType.FloatType, "r" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["float::pow"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("base" to FunkType.FloatType, "exp" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["float::sqrt"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("x" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["float::floor"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("x" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["float::ceil"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("x" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["float::round"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("x" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["float::from_int"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.IntType)),
            FunkType.FloatType
        )

        context["float::from_nat"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.NatType)),
            FunkType.FloatType
        )

        context["float::from_string"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to FunkType.StringType)),
            FunkType.FloatType
        )

        context["__native_float_ln"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["__native_float_log10"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["__native_float_exp"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["__native_float_is_nan"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.BooleanType
        )

        context["__native_float_is_infinite"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.BooleanType
        )

        context["__native_float_is_finite"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.BooleanType
        )

        context["__native_float_sign"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["__native_float_sin"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["__native_float_cos"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["__native_float_tan"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["__native_float_asin"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["__native_float_acos"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["__native_float_atan"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )

        context["__native_float_atan2"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "y" to FunkType.FloatType,
                "x" to FunkType.FloatType
            )),
            FunkType.FloatType
        )
    }

    private fun registerNatTypes(context: MutableMap<String, FunkType>) {
        // Native nat operations
        context["__native_nat_subtract_to_int"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "l" to FunkType.NatType,
                "r" to FunkType.NatType
            )),
            FunkType.IntType
        )

        context["__native_nat_from_int"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.IntType)),
            optionType(FunkType.NatType)
        )

        context["__native_nat_from_float"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            optionType(FunkType.NatType)
        )

        context["__native_nat_from_string"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to FunkType.StringType)),
            optionType(FunkType.NatType)
        )

        // Module-qualified functions for nat
        context["nat::subtract_to_int"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "l" to FunkType.NatType,
                "r" to FunkType.NatType
            )),
            FunkType.IntType
        )

        context["nat::from_int"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.IntType)),
            optionType(FunkType.NatType)
        )

        context["nat::from_float"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            optionType(FunkType.NatType)
        )

        context["nat::from_string"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to FunkType.StringType)),
            optionType(FunkType.NatType)
        )

        context["nat::min"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "l" to FunkType.NatType,
                "r" to FunkType.NatType
            )),
            FunkType.NatType
        )

        context["nat::max"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "l" to FunkType.NatType,
                "r" to FunkType.NatType
            )),
            FunkType.NatType
        )

        context["nat::is_zero"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.NatType)),
            FunkType.BooleanType
        )

        context["nat::is_even"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.NatType)),
            FunkType.BooleanType
        )

        context["nat::is_odd"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.NatType)),
            FunkType.BooleanType
        )

        context["__native_nat_to_frac"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.NatType)),
            FunkType.FracType
        )

        context["__native_nat_pow"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "base" to FunkType.NatType,
                "exp" to FunkType.NatType
            )),
            FunkType.NatType
        )
    }

    private fun registerBoolTypes(context: MutableMap<String, FunkType>) {
        context["__native_boolean_to_string"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("value" to FunkType.BooleanType)),
            FunkType.StringType
        )

        // Module-qualified functions for boolean
        context["boolean::and"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("l" to FunkType.BooleanType, "r" to FunkType.BooleanType)),
            FunkType.BooleanType
        )

        context["boolean::or"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("l" to FunkType.BooleanType, "r" to FunkType.BooleanType)),
            FunkType.BooleanType
        )

        context["boolean::not"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("b" to FunkType.BooleanType)),
            FunkType.BooleanType
        )

        context["boolean::xor"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("l" to FunkType.BooleanType, "r" to FunkType.BooleanType)),
            FunkType.BooleanType
        )

        context["boolean::implies"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("l" to FunkType.BooleanType, "r" to FunkType.BooleanType)),
            FunkType.BooleanType
        )

        context["boolean::iff"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("l" to FunkType.BooleanType, "r" to FunkType.BooleanType)),
            FunkType.BooleanType
        )

        // Aliases for std::bool module (module name is bool, not boolean)
        context["bool::and"] = context["boolean::and"]!!
        context["bool::or"] = context["boolean::or"]!!
        context["bool::not"] = context["boolean::not"]!!
        context["bool::xor"] = context["boolean::xor"]!!
        context["bool::implies"] = context["boolean::implies"]!!
        context["bool::iff"] = context["boolean::iff"]!!
    }

    private fun registerStringTypes(context: MutableMap<String, FunkType>) {
        context["__native_string_to_string"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("value" to FunkType.StringType)),
            FunkType.StringType
        )

        // Native string operations
        context["__native_string_concat"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("l" to FunkType.StringType, "r" to FunkType.StringType)),
            FunkType.StringType
        )

        context["__native_string_length"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to FunkType.StringType)),
            FunkType.IntType
        )

        context["__native_string_to_upper"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to FunkType.StringType)),
            FunkType.StringType
        )

        context["__native_string_to_lower"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to FunkType.StringType)),
            FunkType.StringType
        )

        context["__native_string_trim"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to FunkType.StringType)),
            FunkType.StringType
        )

        context["__native_string_reverse"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to FunkType.StringType)),
            FunkType.StringType
        )

        context["__native_string_starts_with"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to FunkType.StringType, "prefix" to FunkType.StringType)),
            FunkType.BooleanType
        )

        context["__native_string_ends_with"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to FunkType.StringType, "suffix" to FunkType.StringType)),
            FunkType.BooleanType
        )

        context["__native_string_contains"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to FunkType.StringType, "substring" to FunkType.StringType)),
            FunkType.BooleanType
        )

        context["__native_string_split"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to FunkType.StringType, "delimiter" to FunkType.StringType)),
            FunkType.ArrayListType(FunkType.StringType)
        )

        context["__native_string_join"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("strings" to FunkType.ArrayListType(FunkType.StringType), "separator" to FunkType.StringType)),
            FunkType.StringType
        )

        // Module-qualified functions for string
        context["string::concat"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("l" to FunkType.StringType, "r" to FunkType.StringType)),
            FunkType.StringType
        )

        context["string::length"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to FunkType.StringType)),
            FunkType.IntType
        )

        context["string::is_empty"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to FunkType.StringType)),
            FunkType.BooleanType
        )

        context["string::to_upper"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to FunkType.StringType)),
            FunkType.StringType
        )

        context["string::to_lower"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to FunkType.StringType)),
            FunkType.StringType
        )

        context["string::trim"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to FunkType.StringType)),
            FunkType.StringType
        )

        context["string::reverse"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to FunkType.StringType)),
            FunkType.StringType
        )

        context["string::starts_with"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to FunkType.StringType, "prefix" to FunkType.StringType)),
            FunkType.BooleanType
        )

        context["string::ends_with"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to FunkType.StringType, "suffix" to FunkType.StringType)),
            FunkType.BooleanType
        )

        context["string::contains"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to FunkType.StringType, "substring" to FunkType.StringType)),
            FunkType.BooleanType
        )

        context["string::repeat"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to FunkType.StringType, "n" to FunkType.IntType)),
            FunkType.StringType
        )
    }

    private fun registerFracTypes(context: MutableMap<String, FunkType>) {
        // Native frac operations
        context["__native_frac_add"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "l" to FunkType.FracType,
                "r" to FunkType.FracType
            )),
            FunkType.FracType
        )

        context["__native_frac_subtract"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "l" to FunkType.FracType,
                "r" to FunkType.FracType
            )),
            FunkType.FracType
        )

        context["__native_frac_multiply"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "l" to FunkType.FracType,
                "r" to FunkType.FracType
            )),
            FunkType.FracType
        )

        context["__native_frac_divide"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "l" to FunkType.FracType,
                "r" to FunkType.FracType
            )),
            FunkType.FracType
        )

        context["__native_frac_equals"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "l" to FunkType.FracType,
                "r" to FunkType.FracType
            )),
            FunkType.BooleanType
        )

        context["__native_frac_less_than"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "l" to FunkType.FracType,
                "r" to FunkType.FracType
            )),
            FunkType.BooleanType
        )

        context["__native_frac_greater_than"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "l" to FunkType.FracType,
                "r" to FunkType.FracType
            )),
            FunkType.BooleanType
        )

        context["__native_frac_to_float"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FracType)),
            FunkType.FloatType
        )

        context["__native_frac_to_string"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FracType)),
            FunkType.StringType
        )

        context["__native_make_frac"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "numerator" to FunkType.IntType,
                "denominator" to FunkType.IntType
            )),
            FunkType.FracType
        )

        context["__native_frac_numerator"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FracType)),
            FunkType.IntType
        )

        context["__native_frac_denominator"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FracType)),
            FunkType.IntType
        )

        context["__native_reduce_frac"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FracType)),
            FunkType.FracType
        )

        context["__native_abs_frac"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FracType)),
            FunkType.FracType
        )

        context["__native_negate_frac"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FracType)),
            FunkType.FracType
        )

        // Module-qualified functions for frac
        context["frac::add"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "l" to FunkType.FracType,
                "r" to FunkType.FracType
            )),
            FunkType.FracType
        )

        context["frac::subtract"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "l" to FunkType.FracType,
                "r" to FunkType.FracType
            )),
            FunkType.FracType
        )

        context["frac::multiply"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "l" to FunkType.FracType,
                "r" to FunkType.FracType
            )),
            FunkType.FracType
        )

        context["frac::divide"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "l" to FunkType.FracType,
                "r" to FunkType.FracType
            )),
            FunkType.FracType
        )

        context["frac::equals"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "l" to FunkType.FracType,
                "r" to FunkType.FracType
            )),
            FunkType.BooleanType
        )

        context["frac::less_than"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "l" to FunkType.FracType,
                "r" to FunkType.FracType
            )),
            FunkType.BooleanType
        )

        context["frac::greater_than"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "l" to FunkType.FracType,
                "r" to FunkType.FracType
            )),
            FunkType.BooleanType
        )

        context["frac::to_float"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FracType)),
            FunkType.FloatType
        )

        context["frac::to_string"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FracType)),
            FunkType.StringType
        )

        context["frac::make"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "numerator" to FunkType.IntType,
                "denominator" to FunkType.IntType
            )),
            FunkType.FracType
        )

        context["frac::from_int"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.IntType)),
            FunkType.FracType
        )

        context["frac::from_nat"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.NatType)),
            FunkType.FracType
        )

        context["frac::numerator"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FracType)),
            FunkType.IntType
        )

        context["frac::denominator"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FracType)),
            FunkType.IntType
        )

        context["frac::reduce"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FracType)),
            FunkType.FracType
        )

        context["frac::abs"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FracType)),
            FunkType.FracType
        )

        context["frac::negate"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FracType)),
            FunkType.FracType
        )
    }
}
