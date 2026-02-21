package com.mezze.language.interpreter.stdlib.basic

import com.mezze.language.interpreter.*

/**
 * Truffle-optimized implementations for Float type operations.
 *
 * Note: Most float operations are handled elsewhere:
 * - Arithmetic operations: handled by Truffle nodes
 * - Comparison operations: handled by Truffle nodes
 * - Conversion operations: TruffleConversionImplementations.kt
 * - Math operations: MathCoreImplementations.kt and MathTrigImplementations.kt
 */
object FloatImplementations {

    /**
     * Register all Float runtime implementations
     */
    fun registerAll(context: MutableMap<String, MezzeValue>) {
        context["__native_float_to_string"] = NativeMezzeFunction("__native_float_to_string") { args ->
            val record = args[0] as MezzeRecord
            val n = record.fields["n"] as? MezzeFloat ?: throw NativeFunctionException("__native_float_to_string", "expects 'n' as Float")
            MezzeString(n.value.toString())
        }

        context["__native_float_abs"] = NativeMezzeFunction("__native_float_abs") { args ->
            val record = args[0] as MezzeRecord
            val n = record.fields["n"] as? MezzeFloat ?: throw NativeFunctionException("__native_float_abs", "expects 'n' as Float")
            MezzeFloat(kotlin.math.abs(n.value))
        }

        context["__native_float_min"] = NativeMezzeFunction("__native_float_min") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeFloat ?: throw NativeFunctionException("__native_float_min", "expects 'l' as Float")
            val r = record.fields["r"] as? MezzeFloat ?: throw NativeFunctionException("__native_float_min", "expects 'r' as Float")
            MezzeFloat(kotlin.math.min(l.value, r.value))
        }

        context["__native_float_max"] = NativeMezzeFunction("__native_float_max") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeFloat ?: throw NativeFunctionException("__native_float_max", "expects 'l' as Float")
            val r = record.fields["r"] as? MezzeFloat ?: throw NativeFunctionException("__native_float_max", "expects 'r' as Float")
            MezzeFloat(kotlin.math.max(l.value, r.value))
        }

        // Check if value is NaN
        context["__native_float_is_nan"] = NativeMezzeFunction("__native_float_is_nan") { args ->
            val record = args[0] as MezzeRecord
            val n = record.fields["n"] as? MezzeFloat ?: throw NativeFunctionException("__native_float_is_nan", "expects 'n' as Float")
            MezzeBool(n.value.isNaN())
        }

        // Check if value is infinite
        context["__native_float_is_infinite"] = NativeMezzeFunction("__native_float_is_infinite") { args ->
            val record = args[0] as MezzeRecord
            val n = record.fields["n"] as? MezzeFloat ?: throw NativeFunctionException("__native_float_is_infinite", "expects 'n' as Float")
            MezzeBool(n.value.isInfinite())
        }

        // Check if value is finite (not NaN and not infinite)
        context["__native_float_is_finite"] = NativeMezzeFunction("__native_float_is_finite") { args ->
            val record = args[0] as MezzeRecord
            val n = record.fields["n"] as? MezzeFloat ?: throw NativeFunctionException("__native_float_is_finite", "expects 'n' as Float")
            MezzeBool(n.value.isFinite())
        }

        // Get sign of float (-1.0, 0.0, or 1.0)
        context["__native_float_sign"] = NativeMezzeFunction("__native_float_sign") { args ->
            val record = args[0] as MezzeRecord
            val n = record.fields["n"] as? MezzeFloat ?: throw NativeFunctionException("__native_float_sign", "expects 'n' as Float")
            val result = when {
                n.value.isNaN() -> Double.NaN
                n.value > 0.0 -> 1.0
                n.value < 0.0 -> -1.0
                else -> 0.0
            }
            MezzeFloat(result)
        }

        // Clamp float to range [min, max]
        context["__native_float_clamp"] = NativeMezzeFunction("__native_float_clamp") { args ->
            val record = args[0] as MezzeRecord
            val n = record.fields["n"] as? MezzeFloat ?: throw NativeFunctionException("__native_float_clamp", "expects 'n' as Float")
            val min = record.fields["min"] as? MezzeFloat ?: throw NativeFunctionException("__native_float_clamp", "expects 'min' as Float")
            val max = record.fields["max"] as? MezzeFloat ?: throw NativeFunctionException("__native_float_clamp", "expects 'max' as Float")
            MezzeFloat(n.value.coerceIn(min.value, max.value))
        }

        // Convert float to int (truncate), returns None for NaN/Infinity
        context["__native_float_to_int"] = NativeMezzeFunction("__native_float_to_int") { args ->
            val record = args[0] as MezzeRecord
            val n = record.fields["n"] as? MezzeFloat ?: throw NativeFunctionException("__native_float_to_int", "expects 'n' as Float")
            if (n.value.isNaN() || n.value.isInfinite()) {
                mezzeOptionNone()
            } else {
                mezzeOptionSome(MezzeInt(n.value.toLong()))
            }
        }
    }
}
