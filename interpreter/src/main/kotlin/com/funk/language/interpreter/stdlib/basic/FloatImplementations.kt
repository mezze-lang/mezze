package com.funk.language.interpreter.stdlib.basic

import com.funk.language.interpreter.*

/**
 * Truffle runtime implementations for Float type operations
 */
object FloatImplementations {

    /**
     * Register all Float runtime implementations
     */
    fun registerAll(context: MutableMap<String, FunkValue>) {
        // Arithmetic operations
        context["add_float"] = NativeFunkFunction("add_float") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkFloat ?: throw NativeFunctionException("add_float", "expects 'l' as Float")
            val r = record.fields["r"] as? FunkFloat ?: throw NativeFunctionException("add_float", "expects 'r' as Float")
            FunkFloat(l.value + r.value)
        }

        context["subtract_float"] = NativeFunkFunction("subtract_float") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkFloat ?: throw NativeFunctionException("subtract_float", "expects 'l' as Float")
            val r = record.fields["r"] as? FunkFloat ?: throw NativeFunctionException("subtract_float", "expects 'r' as Float")
            FunkFloat(l.value - r.value)
        }

        context["multiply_float"] = NativeFunkFunction("multiply_float") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["left"] as? FunkFloat ?: throw NativeFunctionException("multiply_float", "expects 'left' as Float")
            val right = record.fields["right"] as? FunkFloat ?: throw NativeFunctionException("multiply_float", "expects 'right' as Float")
            FunkFloat(left.value * right.value)
        }

        context["divide_float"] = NativeFunkFunction("divide_float") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["left"] as? FunkFloat ?: throw NativeFunctionException("divide_float", "expects 'left' as Float")
            val right = record.fields["right"] as? FunkFloat ?: throw NativeFunctionException("divide_float", "expects 'right' as Float")
            if (right.value == 0.0) throw FunkException("Division by zero")
            FunkFloat(left.value / right.value)
        }

        context["modulo_float"] = NativeFunkFunction("modulo_float") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["left"] as? FunkFloat ?: throw NativeFunctionException("modulo_float", "expects 'left' as Float")
            val right = record.fields["right"] as? FunkFloat ?: throw NativeFunctionException("modulo_float", "expects 'right' as Float")
            if (right.value == 0.0) throw FunkException("Modulo by zero")
            FunkFloat(left.value % right.value)
        }

        // Comparison operations
        context["equal_float"] = NativeFunkFunction("equal_float") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["left"] as? FunkFloat ?: throw NativeFunctionException("equal_float", "expects 'left' as Float")
            val right = record.fields["right"] as? FunkFloat ?: throw NativeFunctionException("equal_float", "expects 'right' as Float")
            FunkBool(left.value == right.value)
        }

        context["lessThan_float"] = NativeFunkFunction("lessThan_float") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["left"] as? FunkFloat ?: throw NativeFunctionException("lessThan_float", "expects 'left' as Float")
            val right = record.fields["right"] as? FunkFloat ?: throw NativeFunctionException("lessThan_float", "expects 'right' as Float")
            FunkBool(left.value < right.value)
        }

        context["greaterThan_float"] = NativeFunkFunction("greaterThan_float") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["left"] as? FunkFloat ?: throw NativeFunctionException("greaterThan_float", "expects 'left' as Float")
            val right = record.fields["right"] as? FunkFloat ?: throw NativeFunctionException("greaterThan_float", "expects 'right' as Float")
            FunkBool(left.value > right.value)
        }

        // Conversion operations
        context["floatToInt"] = NativeFunkFunction("floatToInt") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["val"] as? FunkFloat ?: throw NativeFunctionException("floatToInt", "expects 'val' as Float")
            FunkInt(value.value.toLong())
        }

        context["floatToFrac"] = NativeFunkFunction("floatToFrac") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["val"] as? FunkFloat ?: throw NativeFunctionException("floatToFrac", "expects 'val' as Float")

            // Simple approximation - convert to decimal and then to fraction
            val denominator = 1000000L
            val numerator = (value.value * denominator).toLong()
            FunkFrac(numerator, denominator).reduce()
        }

        // Hidden native operations
        context["__native_float_equals"] = NativeFunkFunction("__native_float_equals") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkFloat ?: throw NativeFunctionException("__native_float_equals", "expects 'l' as Float")
            val r = record.fields["r"] as? FunkFloat ?: throw NativeFunctionException("__native_float_equals", "expects 'r' as Float")
            FunkBool(l.value == r.value)
        }

        context["__native_float_less_than"] = NativeFunkFunction("__native_float_less_than") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkFloat ?: throw NativeFunctionException("__native_float_less_than", "expects 'l' as Float")
            val r = record.fields["r"] as? FunkFloat ?: throw NativeFunctionException("__native_float_less_than", "expects 'r' as Float")
            FunkBool(l.value < r.value)
        }

        context["__native_float_greater_than"] = NativeFunkFunction("__native_float_greater_than") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkFloat ?: throw NativeFunctionException("__native_float_greater_than", "expects 'l' as Float")
            val r = record.fields["r"] as? FunkFloat ?: throw NativeFunctionException("__native_float_greater_than", "expects 'r' as Float")
            FunkBool(l.value > r.value)
        }

        context["__native_float_to_string"] = NativeFunkFunction("__native_float_to_string") { args ->
            val record = args[0] as FunkRecord
            val n = record.fields["n"] as? FunkFloat ?: throw NativeFunctionException("__native_float_to_string", "expects 'n' as Float")
            FunkString(n.value.toString())
        }

        context["__native_float_add"] = NativeFunkFunction("__native_float_add") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["l"] as? FunkFloat ?: throw NativeFunctionException("__native_float_add", "expects 'l' as Float")
            val right = record.fields["r"] as? FunkFloat ?: throw NativeFunctionException("__native_float_add", "expects 'r' as Float")
            FunkFloat(left.value + right.value)
        }

        context["__native_float_subtract"] = NativeFunkFunction("__native_float_subtract") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["l"] as? FunkFloat ?: throw NativeFunctionException("__native_float_subtract", "expects 'l' as Float")
            val right = record.fields["r"] as? FunkFloat ?: throw NativeFunctionException("__native_float_subtract", "expects 'r' as Float")
            FunkFloat(left.value - right.value)
        }

        context["__native_float_multiply"] = NativeFunkFunction("__native_float_multiply") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["l"] as? FunkFloat ?: throw NativeFunctionException("__native_float_multiply", "expects 'l' as Float")
            val right = record.fields["r"] as? FunkFloat ?: throw NativeFunctionException("__native_float_multiply", "expects 'r' as Float")
            FunkFloat(left.value * right.value)
        }

        context["__native_float_divide"] = NativeFunkFunction("__native_float_divide") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["l"] as? FunkFloat ?: throw NativeFunctionException("__native_float_divide", "expects 'l' as Float")
            val right = record.fields["r"] as? FunkFloat ?: throw NativeFunctionException("__native_float_divide", "expects 'r' as Float")
            if (right.value == 0.0) throw FunkException("Division by zero")
            FunkFloat(left.value / right.value)
        }

        context["__native_float_modulo"] = NativeFunkFunction("__native_float_modulo") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["l"] as? FunkFloat ?: throw NativeFunctionException("__native_float_modulo", "expects 'l' as Float")
            val right = record.fields["r"] as? FunkFloat ?: throw NativeFunctionException("__native_float_modulo", "expects 'r' as Float")
            if (right.value == 0.0) throw FunkException("Modulo by zero")
            FunkFloat(left.value % right.value)
        }

        context["__native_float_compare"] = NativeFunkFunction("__native_float_compare") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["l"] as? FunkFloat ?: throw NativeFunctionException("__native_float_compare", "expects 'l' as Float")
            val right = record.fields["r"] as? FunkFloat ?: throw NativeFunctionException("__native_float_compare", "expects 'r' as Float")
            val result = when {
                left.value < right.value -> "Less"
                left.value > right.value -> "Greater"
                else -> "Equal"
            }
            // TODO: Return proper variant instead of string
            FunkString(result)
        }

        context["__native_float_neg"] = NativeFunkFunction("__native_float_neg") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["n"] as? FunkFloat ?: throw NativeFunctionException("__native_float_neg", "expects 'n' as Float")
            FunkFloat(-value.value)
        }
    }
}
