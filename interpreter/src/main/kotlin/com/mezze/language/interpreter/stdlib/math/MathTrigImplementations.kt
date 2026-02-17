package com.mezze.language.interpreter.stdlib.math

import com.mezze.language.interpreter.*

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
    fun registerAll(context: MutableMap<String, MezzeValue>) {
        registerTrigonometricOperations(context)
        registerHiddenTrigOperations(context)
    }

    /**
     * Trigonometric functions
     */
    private fun registerTrigonometricOperations(context: MutableMap<String, MezzeValue>) {
        context["sin"] = NativeMezzeFunction("sin") { args ->
            val record = args[0] as MezzeRecord
            val value = record.fields["val"] as? MezzeFloat ?: throw NativeFunctionException("sin", "expects 'val' as Float")
            MezzeFloat(kotlin.math.sin(value.value))
        }

        context["cos"] = NativeMezzeFunction("cos") { args ->
            val record = args[0] as MezzeRecord
            val value = record.fields["val"] as? MezzeFloat ?: throw NativeFunctionException("cos", "expects 'val' as Float")
            MezzeFloat(kotlin.math.cos(value.value))
        }

        context["tan"] = NativeMezzeFunction("tan") { args ->
            val record = args[0] as MezzeRecord
            val value = record.fields["val"] as? MezzeFloat ?: throw NativeFunctionException("tan", "expects 'val' as Float")
            MezzeFloat(kotlin.math.tan(value.value))
        }
    }

    /**
     * Register hidden native trigonometric operations (prefixed with __native_)
     * These are used by the stdlib modules and hidden from user code
     */
    private fun registerHiddenTrigOperations(context: MutableMap<String, MezzeValue>) {
        // Trigonometric functions
        context["__native_float_sin"] = NativeMezzeFunction("__native_float_sin") { args ->
            val record = args[0] as MezzeRecord
            val value = record.fields["n"] as? MezzeFloat ?: throw NativeFunctionException("__native_float_sin", "expects 'n' as Float")
            MezzeFloat(kotlin.math.sin(value.value))
        }

        context["__native_float_cos"] = NativeMezzeFunction("__native_float_cos") { args ->
            val record = args[0] as MezzeRecord
            val value = record.fields["n"] as? MezzeFloat ?: throw NativeFunctionException("__native_float_cos", "expects 'n' as Float")
            MezzeFloat(kotlin.math.cos(value.value))
        }

        context["__native_float_tan"] = NativeMezzeFunction("__native_float_tan") { args ->
            val record = args[0] as MezzeRecord
            val value = record.fields["n"] as? MezzeFloat ?: throw NativeFunctionException("__native_float_tan", "expects 'n' as Float")
            MezzeFloat(kotlin.math.tan(value.value))
        }

        context["__native_float_asin"] = NativeMezzeFunction("__native_float_asin") { args ->
            val record = args[0] as MezzeRecord
            val value = record.fields["n"] as? MezzeFloat ?: throw NativeFunctionException("__native_float_asin", "expects 'n' as Float")
            MezzeFloat(kotlin.math.asin(value.value))
        }

        context["__native_float_acos"] = NativeMezzeFunction("__native_float_acos") { args ->
            val record = args[0] as MezzeRecord
            val value = record.fields["n"] as? MezzeFloat ?: throw NativeFunctionException("__native_float_acos", "expects 'n' as Float")
            MezzeFloat(kotlin.math.acos(value.value))
        }

        context["__native_float_atan"] = NativeMezzeFunction("__native_float_atan") { args ->
            val record = args[0] as MezzeRecord
            val value = record.fields["n"] as? MezzeFloat ?: throw NativeFunctionException("__native_float_atan", "expects 'n' as Float")
            MezzeFloat(kotlin.math.atan(value.value))
        }

        context["__native_float_atan2"] = NativeMezzeFunction("__native_float_atan2") { args ->
            val record = args[0] as MezzeRecord
            val y = record.fields["y"] as? MezzeFloat ?: throw NativeFunctionException("__native_float_atan2", "expects 'y' as Float")
            val x = record.fields["x"] as? MezzeFloat ?: throw NativeFunctionException("__native_float_atan2", "expects 'x' as Float")
            MezzeFloat(kotlin.math.atan2(y.value, x.value))
        }
    }
}
