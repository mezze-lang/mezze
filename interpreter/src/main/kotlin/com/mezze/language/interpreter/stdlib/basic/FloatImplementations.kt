package com.mezze.language.interpreter.stdlib.basic

import com.mezze.language.interpreter.*

/**
 * Truffle-optimized implementations for Float type operations.
 *
 * Phase 5 of Truffle migration: Basic type operations optimized for JIT performance.
 * Float is the 64-bit floating point type backed by Kotlin Double.
 *
 * Optimizations:
 * - Inline Kotlin arithmetic (+, -, *, /, %) for JIT compilation
 * - Direct floating point comparison with no overhead
 * - Zero-cost abstraction over Kotlin Double
 * - IEEE 754 floating point semantics
 * - Proper division-by-zero and modulo-by-zero checks
 * - Safe conversions to Int and Frac types
 *
 * Total: 21 float operations (arithmetic, comparison, conversion)
 */
object FloatImplementations {

    /**
     * Register all Float runtime implementations
     */
    fun registerAll(context: MutableMap<String, MezzeValue>) {
        // Arithmetic operations
        context["add_float"] = NativeMezzeFunction("add_float") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeFloat ?: throw NativeFunctionException("add_float", "expects 'l' as Float")
            val r = record.fields["r"] as? MezzeFloat ?: throw NativeFunctionException("add_float", "expects 'r' as Float")
            MezzeFloat(l.value + r.value)
        }

        context["subtract_float"] = NativeMezzeFunction("subtract_float") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeFloat ?: throw NativeFunctionException("subtract_float", "expects 'l' as Float")
            val r = record.fields["r"] as? MezzeFloat ?: throw NativeFunctionException("subtract_float", "expects 'r' as Float")
            MezzeFloat(l.value - r.value)
        }

        context["multiply_float"] = NativeMezzeFunction("multiply_float") { args ->
            val record = args[0] as MezzeRecord
            val left = record.fields["left"] as? MezzeFloat ?: throw NativeFunctionException("multiply_float", "expects 'left' as Float")
            val right = record.fields["right"] as? MezzeFloat ?: throw NativeFunctionException("multiply_float", "expects 'right' as Float")
            MezzeFloat(left.value * right.value)
        }

        context["divide_float"] = NativeMezzeFunction("divide_float") { args ->
            val record = args[0] as MezzeRecord
            val left = record.fields["left"] as? MezzeFloat ?: throw NativeFunctionException("divide_float", "expects 'left' as Float")
            val right = record.fields["right"] as? MezzeFloat ?: throw NativeFunctionException("divide_float", "expects 'right' as Float")
            if (right.value == 0.0) throw MezzeException("Division by zero")
            MezzeFloat(left.value / right.value)
        }

        context["modulo_float"] = NativeMezzeFunction("modulo_float") { args ->
            val record = args[0] as MezzeRecord
            val left = record.fields["left"] as? MezzeFloat ?: throw NativeFunctionException("modulo_float", "expects 'left' as Float")
            val right = record.fields["right"] as? MezzeFloat ?: throw NativeFunctionException("modulo_float", "expects 'right' as Float")
            if (right.value == 0.0) throw MezzeException("Modulo by zero")
            MezzeFloat(left.value % right.value)
        }

        // Comparison operations
        context["equal_float"] = NativeMezzeFunction("equal_float") { args ->
            val record = args[0] as MezzeRecord
            val left = record.fields["left"] as? MezzeFloat ?: throw NativeFunctionException("equal_float", "expects 'left' as Float")
            val right = record.fields["right"] as? MezzeFloat ?: throw NativeFunctionException("equal_float", "expects 'right' as Float")
            MezzeBool(left.value == right.value)
        }

        context["lessThan_float"] = NativeMezzeFunction("lessThan_float") { args ->
            val record = args[0] as MezzeRecord
            val left = record.fields["left"] as? MezzeFloat ?: throw NativeFunctionException("lessThan_float", "expects 'left' as Float")
            val right = record.fields["right"] as? MezzeFloat ?: throw NativeFunctionException("lessThan_float", "expects 'right' as Float")
            MezzeBool(left.value < right.value)
        }

        context["greaterThan_float"] = NativeMezzeFunction("greaterThan_float") { args ->
            val record = args[0] as MezzeRecord
            val left = record.fields["left"] as? MezzeFloat ?: throw NativeFunctionException("greaterThan_float", "expects 'left' as Float")
            val right = record.fields["right"] as? MezzeFloat ?: throw NativeFunctionException("greaterThan_float", "expects 'right' as Float")
            MezzeBool(left.value > right.value)
        }

        // Conversion operations
        context["floatToInt"] = NativeMezzeFunction("floatToInt") { args ->
            val record = args[0] as MezzeRecord
            val value = record.fields["val"] as? MezzeFloat ?: throw NativeFunctionException("floatToInt", "expects 'val' as Float")
            MezzeInt(value.value.toLong())
        }

        context["floatToFrac"] = NativeMezzeFunction("floatToFrac") { args ->
            val record = args[0] as MezzeRecord
            val value = record.fields["val"] as? MezzeFloat ?: throw NativeFunctionException("floatToFrac", "expects 'val' as Float")

            // Simple approximation - convert to decimal and then to fraction
            val denominator = 1000000L
            val numerator = (value.value * denominator).toLong()
            MezzeFrac(numerator, denominator).reduce()
        }

        // Hidden native operations
        context["__native_float_equals"] = NativeMezzeFunction("__native_float_equals") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeFloat ?: throw NativeFunctionException("__native_float_equals", "expects 'l' as Float")
            val r = record.fields["r"] as? MezzeFloat ?: throw NativeFunctionException("__native_float_equals", "expects 'r' as Float")
            MezzeBool(l.value == r.value)
        }

        context["__native_float_less_than"] = NativeMezzeFunction("__native_float_less_than") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeFloat ?: throw NativeFunctionException("__native_float_less_than", "expects 'l' as Float")
            val r = record.fields["r"] as? MezzeFloat ?: throw NativeFunctionException("__native_float_less_than", "expects 'r' as Float")
            MezzeBool(l.value < r.value)
        }

        context["__native_float_greater_than"] = NativeMezzeFunction("__native_float_greater_than") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeFloat ?: throw NativeFunctionException("__native_float_greater_than", "expects 'l' as Float")
            val r = record.fields["r"] as? MezzeFloat ?: throw NativeFunctionException("__native_float_greater_than", "expects 'r' as Float")
            MezzeBool(l.value > r.value)
        }

        context["__native_float_to_string"] = NativeMezzeFunction("__native_float_to_string") { args ->
            val record = args[0] as MezzeRecord
            val n = record.fields["n"] as? MezzeFloat ?: throw NativeFunctionException("__native_float_to_string", "expects 'n' as Float")
            MezzeString(n.value.toString())
        }

        context["__native_float_add"] = NativeMezzeFunction("__native_float_add") { args ->
            val record = args[0] as MezzeRecord
            val left = record.fields["l"] as? MezzeFloat ?: throw NativeFunctionException("__native_float_add", "expects 'l' as Float")
            val right = record.fields["r"] as? MezzeFloat ?: throw NativeFunctionException("__native_float_add", "expects 'r' as Float")
            MezzeFloat(left.value + right.value)
        }

        context["__native_float_subtract"] = NativeMezzeFunction("__native_float_subtract") { args ->
            val record = args[0] as MezzeRecord
            val left = record.fields["l"] as? MezzeFloat ?: throw NativeFunctionException("__native_float_subtract", "expects 'l' as Float")
            val right = record.fields["r"] as? MezzeFloat ?: throw NativeFunctionException("__native_float_subtract", "expects 'r' as Float")
            MezzeFloat(left.value - right.value)
        }

        context["__native_float_multiply"] = NativeMezzeFunction("__native_float_multiply") { args ->
            val record = args[0] as MezzeRecord
            val left = record.fields["l"] as? MezzeFloat ?: throw NativeFunctionException("__native_float_multiply", "expects 'l' as Float")
            val right = record.fields["r"] as? MezzeFloat ?: throw NativeFunctionException("__native_float_multiply", "expects 'r' as Float")
            MezzeFloat(left.value * right.value)
        }

        context["__native_float_divide"] = NativeMezzeFunction("__native_float_divide") { args ->
            val record = args[0] as MezzeRecord
            val left = record.fields["l"] as? MezzeFloat ?: throw NativeFunctionException("__native_float_divide", "expects 'l' as Float")
            val right = record.fields["r"] as? MezzeFloat ?: throw NativeFunctionException("__native_float_divide", "expects 'r' as Float")
            if (right.value == 0.0) throw MezzeException("Division by zero")
            MezzeFloat(left.value / right.value)
        }

        context["__native_float_modulo"] = NativeMezzeFunction("__native_float_modulo") { args ->
            val record = args[0] as MezzeRecord
            val left = record.fields["l"] as? MezzeFloat ?: throw NativeFunctionException("__native_float_modulo", "expects 'l' as Float")
            val right = record.fields["r"] as? MezzeFloat ?: throw NativeFunctionException("__native_float_modulo", "expects 'r' as Float")
            if (right.value == 0.0) throw MezzeException("Modulo by zero")
            MezzeFloat(left.value % right.value)
        }

        context["__native_float_compare"] = NativeMezzeFunction("__native_float_compare") { args ->
            val record = args[0] as MezzeRecord
            val left = record.fields["l"] as? MezzeFloat ?: throw NativeFunctionException("__native_float_compare", "expects 'l' as Float")
            val right = record.fields["r"] as? MezzeFloat ?: throw NativeFunctionException("__native_float_compare", "expects 'r' as Float")
            val result = when {
                left.value < right.value -> "Less"
                left.value > right.value -> "Greater"
                else -> "Equal"
            }
            // TODO: Return proper variant instead of string
            MezzeString(result)
        }

        context["__native_float_neg"] = NativeMezzeFunction("__native_float_neg") { args ->
            val record = args[0] as MezzeRecord
            val value = record.fields["n"] as? MezzeFloat ?: throw NativeFunctionException("__native_float_neg", "expects 'n' as Float")
            MezzeFloat(-value.value)
        }
    }
}
