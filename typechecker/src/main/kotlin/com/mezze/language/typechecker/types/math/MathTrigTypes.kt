package com.mezze.language.typechecker.types.math

import com.mezze.language.ast.MezzeType

/**
 * Type signatures for trigonometric functions
 *
 * Corresponds to stdlib file: std/math/trig.mz
 *
 * Provides types for:
 * - Basic trigonometric functions (sin, cos, tan)
 * - Inverse trigonometric functions (asin, acos, atan, atan2)
 */
object MathTrigTypes {
    fun registerAll(context: MutableMap<String, MezzeType>) {
        registerBasicTrigFunctions(context)
        registerInverseTrigFunctions(context)
        registerModuleQualifiedFunctions(context)
    }

    private fun registerBasicTrigFunctions(context: MutableMap<String, MezzeType>) {
        // Sine function
        context["__native_float_sin"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.FloatType
        )

        // Cosine function
        context["__native_float_cos"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.FloatType
        )

        // Tangent function
        context["__native_float_tan"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.FloatType
        )
    }

    private fun registerInverseTrigFunctions(context: MutableMap<String, MezzeType>) {
        // Arcsine function
        context["__native_float_asin"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.FloatType
        )

        // Arccosine function
        context["__native_float_acos"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.FloatType
        )

        // Arctangent function
        context["__native_float_atan"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.FloatType
        )

        // Two-argument arctangent function
        context["__native_float_atan2"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "y" to MezzeType.FloatType,
                "x" to MezzeType.FloatType
            )),
            MezzeType.FloatType
        )
    }

    private fun registerModuleQualifiedFunctions(context: MutableMap<String, MezzeType>) {
        // Module-qualified functions for std::math::trig
        // When using: use std::math::trig
        // Functions accessible as: trig::sin, trig::cos, etc.

        // Mathematical constants (defined in trig.mz)
        context["trig::pi"] = MezzeType.FloatType
        context["trig::e"] = MezzeType.FloatType

        // Basic trigonometric functions
        context["trig::sin"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.FloatType
        )
        context["trig::cos"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.FloatType
        )
        context["trig::tan"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.FloatType
        )

        // Inverse trigonometric functions
        context["trig::asin"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.FloatType
        )
        context["trig::acos"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.FloatType
        )
        context["trig::atan"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.FloatType
        )
        context["trig::atan2"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("y" to MezzeType.FloatType, "x" to MezzeType.FloatType)),
            MezzeType.FloatType
        )

        // Utility functions
        context["trig::degrees_to_radians"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("degrees" to MezzeType.FloatType)),
            MezzeType.FloatType
        )
        context["trig::radians_to_degrees"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("radians" to MezzeType.FloatType)),
            MezzeType.FloatType
        )

        // Hyperbolic functions
        context["trig::sinh"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.FloatType
        )
        context["trig::cosh"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.FloatType
        )
        context["trig::tanh"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
            MezzeType.FloatType
        )
    }
}
