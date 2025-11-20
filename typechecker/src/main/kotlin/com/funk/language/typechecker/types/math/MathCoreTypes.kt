package com.funk.language.typechecker.types.math

import com.funk.language.ast.FunkType

/**
 * Type signatures for core mathematical functions
 *
 * Corresponds to stdlib file: std/math/core.funk
 *
 * Provides types for:
 * - Power functions (pow, sqrt)
 * - Logarithmic functions (ln, log10, exp)
 * - Rounding functions (floor, ceil, round)
 * - Float classification (is_nan, is_infinite, is_finite)
 * - Sign functions
 */
object MathCoreTypes {
    fun registerAll(context: MutableMap<String, FunkType>) {
        registerPowerFunctions(context)
        registerLogarithmicFunctions(context)
        registerRoundingFunctions(context)
        registerClassificationFunctions(context)
        registerSignFunctions(context)
    }

    private fun registerPowerFunctions(context: MutableMap<String, FunkType>) {
        // Float power function
        context["__native_float_pow"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "base" to FunkType.FloatType,
                "exp" to FunkType.FloatType
            )),
            FunkType.FloatType
        )

        // Float square root
        context["__native_float_sqrt"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )

        // Int power function
        context["__native_int_pow"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "base" to FunkType.IntType,
                "exp" to FunkType.IntType
            )),
            FunkType.IntType
        )

        // Nat power function
        context["__native_nat_pow"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "base" to FunkType.NatType,
                "exp" to FunkType.NatType
            )),
            FunkType.NatType
        )
    }

    private fun registerLogarithmicFunctions(context: MutableMap<String, FunkType>) {
        // Natural logarithm
        context["__native_float_ln"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )

        // Base-10 logarithm
        context["__native_float_log10"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )

        // Exponential function (e^x)
        context["__native_float_exp"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )
    }

    private fun registerRoundingFunctions(context: MutableMap<String, FunkType>) {
        // Floor function
        context["__native_float_floor"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )

        // Ceiling function
        context["__native_float_ceil"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )

        // Round to nearest integer
        context["__native_float_round"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )
    }

    private fun registerClassificationFunctions(context: MutableMap<String, FunkType>) {
        // Check if float is NaN
        context["__native_float_is_nan"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.BooleanType
        )

        // Check if float is infinite
        context["__native_float_is_infinite"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.BooleanType
        )

        // Check if float is finite
        context["__native_float_is_finite"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.BooleanType
        )
    }

    private fun registerSignFunctions(context: MutableMap<String, FunkType>) {
        // Float sign function
        context["__native_float_sign"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )

        // Int sign function
        context["__native_int_sign"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.IntType)),
            FunkType.IntType
        )
    }
}
