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
    }
}
