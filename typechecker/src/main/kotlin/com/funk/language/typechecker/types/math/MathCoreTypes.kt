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
        registerModuleQualifiedFunctions(context)
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

    private fun registerModuleQualifiedFunctions(context: MutableMap<String, FunkType>) {
        // Module-qualified functions for std::math::core
        // When using: use std::math::core
        // Functions accessible as: core::pow, core::sqrt, etc.

        // Mathematical constants
        context["core::pi"] = FunkType.FloatType
        context["core::e"] = FunkType.FloatType

        // Note: min and max are polymorphic and will be handled by module system
        // For now, we don't register them here as they require constraint handling

        // Power functions
        context["core::pow"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("base" to FunkType.FloatType, "exp" to FunkType.FloatType)),
            FunkType.FloatType
        )
        context["core::pow_int"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("base" to FunkType.IntType, "exp" to FunkType.IntType)),
            FunkType.IntType
        )
        context["core::pow_nat"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("base" to FunkType.NatType, "exp" to FunkType.NatType)),
            FunkType.NatType
        )

        // Square root
        context["core::sqrt"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )

        // Logarithmic functions
        context["core::ln"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )
        context["core::log10"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )
        context["core::exp"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )

        // Rounding functions
        context["core::floor"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )
        context["core::ceil"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )
        context["core::round"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )

        // Float classification
        context["core::is_nan"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.BooleanType
        )
        context["core::is_infinite"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.BooleanType
        )
        context["core::is_finite"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.BooleanType
        )

        // Sign functions
        context["core::sign"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )
        context["core::sign_int"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.IntType)),
            FunkType.IntType
        )
    }
}
