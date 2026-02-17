package com.mezze.language.typechecker.types.math

import com.mezze.language.ast.MezzeType

/**
 * Type signatures for core mathematical functions
 *
 * Corresponds to stdlib file: std/math/core.mz
 *
 * Provides types for:
 * - Power functions (pow, sqrt)
 * - Logarithmic functions (ln, log10, exp)
 * - Rounding functions (floor, ceil, round)
 * - Float classification (is_nan, is_infinite, is_finite)
 * - Sign functions
 */
object MathCoreTypes {
    fun registerAll(context: MutableMap<String, MezzeType>) {
        registerPowerFunctions(context)
        registerLogarithmicFunctions(context)
        registerRoundingFunctions(context)
        registerClassificationFunctions(context)
        registerSignFunctions(context)
        registerModuleQualifiedFunctions(context)
    }

    private fun registerPowerFunctions(context: MutableMap<String, MezzeType>) {
        // Float power function
        context["__native_float_pow"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "base" to MezzeType.FloatType,
                "exp" to MezzeType.FloatType
            )),
            MezzeType.FloatType
        )

        // Float square root
        context["__native_float_sqrt"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.FloatType
        )

        // Int power function
        context["__native_int_pow"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "base" to MezzeType.IntType,
                "exp" to MezzeType.IntType
            )),
            MezzeType.IntType
        )

        // Nat power function
        context["__native_nat_pow"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "base" to MezzeType.NatType,
                "exp" to MezzeType.NatType
            )),
            MezzeType.NatType
        )
    }

    private fun registerLogarithmicFunctions(context: MutableMap<String, MezzeType>) {
        // Natural logarithm
        context["__native_float_ln"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.FloatType
        )

        // Base-10 logarithm
        context["__native_float_log10"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.FloatType
        )

        // Exponential function (e^x)
        context["__native_float_exp"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.FloatType
        )
    }

    private fun registerRoundingFunctions(context: MutableMap<String, MezzeType>) {
        // Floor function
        context["__native_float_floor"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.FloatType
        )

        // Ceiling function
        context["__native_float_ceil"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.FloatType
        )

        // Round to nearest integer
        context["__native_float_round"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.FloatType
        )
    }

    private fun registerClassificationFunctions(context: MutableMap<String, MezzeType>) {
        // Check if float is NaN
        context["__native_float_is_nan"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.BooleanType
        )

        // Check if float is infinite
        context["__native_float_is_infinite"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.BooleanType
        )

        // Check if float is finite
        context["__native_float_is_finite"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.BooleanType
        )
    }

    private fun registerSignFunctions(context: MutableMap<String, MezzeType>) {
        // Float sign function
        context["__native_float_sign"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.FloatType
        )

        // Int sign function
        context["__native_int_sign"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.IntType)),
            MezzeType.IntType
        )
    }

    private fun registerModuleQualifiedFunctions(context: MutableMap<String, MezzeType>) {
        // Module-qualified functions for std::math::core
        // When using: use std::math::core
        // Functions accessible as: core::pow, core::sqrt, etc.

        // Mathematical constants
        context["core::pi"] = MezzeType.FloatType
        context["core::e"] = MezzeType.FloatType

        // Note: min and max are polymorphic and will be handled by module system
        // For now, we don't register them here as they require constraint handling

        // Power functions
        context["core::pow"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("base" to MezzeType.FloatType, "exp" to MezzeType.FloatType)),
            MezzeType.FloatType
        )
        context["core::pow_int"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("base" to MezzeType.IntType, "exp" to MezzeType.IntType)),
            MezzeType.IntType
        )
        context["core::pow_nat"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("base" to MezzeType.NatType, "exp" to MezzeType.NatType)),
            MezzeType.NatType
        )

        // Square root
        context["core::sqrt"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.FloatType
        )

        // Logarithmic functions
        context["core::ln"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.FloatType
        )
        context["core::log10"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.FloatType
        )
        context["core::exp"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.FloatType
        )

        // Rounding functions
        context["core::floor"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.FloatType
        )
        context["core::ceil"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.FloatType
        )
        context["core::round"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.FloatType
        )

        // Float classification
        context["core::is_nan"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.BooleanType
        )
        context["core::is_infinite"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.BooleanType
        )
        context["core::is_finite"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.BooleanType
        )

        // Sign functions
        context["core::sign"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.FloatType
        )
        context["core::sign_int"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.IntType)),
            MezzeType.IntType
        )
    }
}
