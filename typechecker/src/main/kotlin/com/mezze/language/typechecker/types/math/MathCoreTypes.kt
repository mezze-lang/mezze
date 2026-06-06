package com.mezze.language.typechecker.types.math

import com.mezze.language.ast.MezzeType
import com.mezze.language.typechecker.types.*

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
    fun registerAll(context: MutableMap<String, MezzeType>) = natives(context) {
        // Power functions
        fn("__native_float_pow") { param("base" to float, "exp" to float); returns(float) }
        fn("__native_float_sqrt") { param("n" to float); returns(float) }
        fn("__native_int_pow") { param("base" to int, "exp" to int); returns(int) }
        fn("__native_nat_pow") { param("base" to nat, "exp" to nat); returns(nat) }

        // Logarithmic functions
        fn("__native_float_ln") { param("n" to float); returns(float) }
        fn("__native_float_log10") { param("n" to float); returns(float) }
        fn("__native_float_exp") { param("n" to float); returns(float) }

        // Rounding functions
        fn("__native_float_floor") { param("n" to float); returns(float) }
        fn("__native_float_ceil") { param("n" to float); returns(float) }
        fn("__native_float_round") { param("n" to float); returns(float) }

        // Float classification
        fn("__native_float_is_nan") { param("n" to float); returns(bool) }
        fn("__native_float_is_infinite") { param("n" to float); returns(bool) }
        fn("__native_float_is_finite") { param("n" to float); returns(bool) }

        // Sign functions
        fn("__native_float_sign") { param("n" to float); returns(float) }
        fn("__native_int_sign") { param("n" to int); returns(int) }

        // Module-qualified functions for std::math::core
        // Mathematical constants
        const("core::pi", float)
        const("core::e", float)

        // Power functions
        fn("core::pow") { param("base" to float, "exp" to float); returns(float) }
        fn("core::pow_int") { param("base" to int, "exp" to int); returns(int) }
        fn("core::pow_nat") { param("base" to nat, "exp" to nat); returns(nat) }

        // Square root
        fn("core::sqrt") { param("n" to float); returns(float) }

        // Logarithmic functions
        fn("core::ln") { param("n" to float); returns(float) }
        fn("core::log10") { param("n" to float); returns(float) }
        fn("core::exp") { param("n" to float); returns(float) }

        // Rounding functions
        fn("core::floor") { param("n" to float); returns(float) }
        fn("core::ceil") { param("n" to float); returns(float) }
        fn("core::round") { param("n" to float); returns(float) }

        // Float classification
        fn("core::is_nan") { param("n" to float); returns(bool) }
        fn("core::is_infinite") { param("n" to float); returns(bool) }
        fn("core::is_finite") { param("n" to float); returns(bool) }

        // Sign functions
        fn("core::sign") { param("n" to float); returns(float) }
        fn("core::sign_int") { param("n" to int); returns(int) }
    }
}
