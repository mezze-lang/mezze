package com.funk.language.typechecker.types.math

import com.funk.language.ast.FunkType

/**
 * Type signatures for trigonometric functions
 *
 * Corresponds to stdlib file: std/math/trig.funk
 *
 * Provides types for:
 * - Basic trigonometric functions (sin, cos, tan)
 * - Inverse trigonometric functions (asin, acos, atan, atan2)
 */
object MathTrigTypes {
    fun registerAll(context: MutableMap<String, FunkType>) {
        registerBasicTrigFunctions(context)
        registerInverseTrigFunctions(context)
        registerModuleQualifiedFunctions(context)
    }

    private fun registerBasicTrigFunctions(context: MutableMap<String, FunkType>) {
        // Sine function
        context["__native_float_sin"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )

        // Cosine function
        context["__native_float_cos"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )

        // Tangent function
        context["__native_float_tan"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )
    }

    private fun registerInverseTrigFunctions(context: MutableMap<String, FunkType>) {
        // Arcsine function
        context["__native_float_asin"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )

        // Arccosine function
        context["__native_float_acos"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )

        // Arctangent function
        context["__native_float_atan"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )

        // Two-argument arctangent function
        context["__native_float_atan2"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "y" to FunkType.FloatType,
                "x" to FunkType.FloatType
            )),
            FunkType.FloatType
        )
    }

    private fun registerModuleQualifiedFunctions(context: MutableMap<String, FunkType>) {
        // Module-qualified functions for std::math::trig
        // When using: use std::math::trig
        // Functions accessible as: trig::sin, trig::cos, etc.

        // Mathematical constants (defined in trig.funk)
        context["trig::pi"] = FunkType.FloatType
        context["trig::e"] = FunkType.FloatType

        // Basic trigonometric functions
        context["trig::sin"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )
        context["trig::cos"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )
        context["trig::tan"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )

        // Inverse trigonometric functions
        context["trig::asin"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )
        context["trig::acos"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )
        context["trig::atan"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )
        context["trig::atan2"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("y" to FunkType.FloatType, "x" to FunkType.FloatType)),
            FunkType.FloatType
        )

        // Utility functions
        context["trig::degrees_to_radians"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("degrees" to FunkType.FloatType)),
            FunkType.FloatType
        )
        context["trig::radians_to_degrees"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("radians" to FunkType.FloatType)),
            FunkType.FloatType
        )

        // Hyperbolic functions
        context["trig::sinh"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )
        context["trig::cosh"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )
        context["trig::tanh"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FloatType)),
            FunkType.FloatType
        )
    }
}
