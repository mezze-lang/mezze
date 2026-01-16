package com.funk.language.interpreter.stdlib.math

import com.funk.language.interpreter.*

/**
 * Truffle-optimized implementations for trigonometric operations.
 *
 * Phase 4 of Truffle migration: Trigonometric math optimized for JIT performance.
 * Includes: sin, cos, tan, asin, acos, atan, atan2
 *
 * Optimizations:
 * - Direct use of JIT-friendly Kotlin math functions (kotlin.math.*)
 * - No function callbacks - all pure trigonometric operations
 * - Inline operations for maximum performance
 * - Float-only operations (standard for trigonometry)
 *
 * Total: 10 trigonometric functions (3 public + 7 hidden natives)
 */
object MathTrigImplementations {

    /**
     * Register all trigonometric implementations
     */
    fun registerAll(context: MutableMap<String, FunkValue>) {
        registerTrigonometricOperations(context)
        registerHiddenTrigOperations(context)
    }

    /**
     * Trigonometric functions
     */
    private fun registerTrigonometricOperations(context: MutableMap<String, FunkValue>) {
        context["sin"] = NativeFunkFunction("sin") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["val"] as? FunkFloat ?: throw NativeFunctionException("sin", "expects 'val' as Float")
            FunkFloat(kotlin.math.sin(value.value))
        }

        context["cos"] = NativeFunkFunction("cos") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["val"] as? FunkFloat ?: throw NativeFunctionException("cos", "expects 'val' as Float")
            FunkFloat(kotlin.math.cos(value.value))
        }

        context["tan"] = NativeFunkFunction("tan") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["val"] as? FunkFloat ?: throw NativeFunctionException("tan", "expects 'val' as Float")
            FunkFloat(kotlin.math.tan(value.value))
        }
    }

    /**
     * Register hidden native trigonometric operations (prefixed with __native_)
     * These are used by the stdlib modules and hidden from user code
     */
    private fun registerHiddenTrigOperations(context: MutableMap<String, FunkValue>) {
        // Trigonometric functions
        context["__native_float_sin"] = NativeFunkFunction("__native_float_sin") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["n"] as? FunkFloat ?: throw NativeFunctionException("__native_float_sin", "expects 'n' as Float")
            FunkFloat(kotlin.math.sin(value.value))
        }

        context["__native_float_cos"] = NativeFunkFunction("__native_float_cos") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["n"] as? FunkFloat ?: throw NativeFunctionException("__native_float_cos", "expects 'n' as Float")
            FunkFloat(kotlin.math.cos(value.value))
        }

        context["__native_float_tan"] = NativeFunkFunction("__native_float_tan") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["n"] as? FunkFloat ?: throw NativeFunctionException("__native_float_tan", "expects 'n' as Float")
            FunkFloat(kotlin.math.tan(value.value))
        }

        context["__native_float_asin"] = NativeFunkFunction("__native_float_asin") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["n"] as? FunkFloat ?: throw NativeFunctionException("__native_float_asin", "expects 'n' as Float")
            FunkFloat(kotlin.math.asin(value.value))
        }

        context["__native_float_acos"] = NativeFunkFunction("__native_float_acos") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["n"] as? FunkFloat ?: throw NativeFunctionException("__native_float_acos", "expects 'n' as Float")
            FunkFloat(kotlin.math.acos(value.value))
        }

        context["__native_float_atan"] = NativeFunkFunction("__native_float_atan") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["n"] as? FunkFloat ?: throw NativeFunctionException("__native_float_atan", "expects 'n' as Float")
            FunkFloat(kotlin.math.atan(value.value))
        }

        context["__native_float_atan2"] = NativeFunkFunction("__native_float_atan2") { args ->
            val record = args[0] as FunkRecord
            val y = record.fields["y"] as? FunkFloat ?: throw NativeFunctionException("__native_float_atan2", "expects 'y' as Float")
            val x = record.fields["x"] as? FunkFloat ?: throw NativeFunctionException("__native_float_atan2", "expects 'x' as Float")
            FunkFloat(kotlin.math.atan2(y.value, x.value))
        }
    }
}
