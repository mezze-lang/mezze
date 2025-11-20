package com.funk.language.interpreter.stdlib.basic

import com.funk.language.interpreter.*

/**
 * Truffle runtime implementations for Int type operations
 */
object IntImplementations {

    /**
     * Register all Int runtime implementations
     */
    fun registerAll(context: MutableMap<String, FunkValue>) {
        // Arithmetic operations
        context["add_int"] = NativeFunkFunction("add_int") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkInt ?: throw NativeFunctionException("add_int", "expects 'l' as Int")
            val r = record.fields["r"] as? FunkInt ?: throw NativeFunctionException("add_int", "expects 'r' as Int")
            FunkInt(l.value + r.value)
        }

        context["subtract_int"] = NativeFunkFunction("subtract_int") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkInt ?: throw NativeFunctionException("subtract_int", "expects 'l' as Int")
            val r = record.fields["r"] as? FunkInt ?: throw NativeFunctionException("subtract_int", "expects 'r' as Int")
            FunkInt(l.value - r.value)
        }

        context["multiply_int"] = NativeFunkFunction("multiply_int") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["left"] as? FunkInt ?: throw NativeFunctionException("multiply_int", "expects 'left' as Int")
            val right = record.fields["right"] as? FunkInt ?: throw NativeFunctionException("multiply_int", "expects 'right' as Int")
            FunkInt(left.value * right.value)
        }

        context["divide_int"] = NativeFunkFunction("divide_int") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["left"] as? FunkInt ?: throw NativeFunctionException("divide_int", "expects 'left' as Int")
            val right = record.fields["right"] as? FunkInt ?: throw NativeFunctionException("divide_int", "expects 'right' as Int")
            if (right.value == 0L) throw FunkException("Division by zero")
            FunkInt(left.value / right.value)
        }

        context["modulo_int"] = NativeFunkFunction("modulo_int") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["left"] as? FunkInt ?: throw NativeFunctionException("modulo_int", "expects 'left' as Int")
            val right = record.fields["right"] as? FunkInt ?: throw NativeFunctionException("modulo_int", "expects 'right' as Int")
            if (right.value == 0L) throw FunkException("Modulo by zero")
            FunkInt(left.value % right.value)
        }

        // Comparison operations
        context["equal_int"] = NativeFunkFunction("equal_int") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["left"] as? FunkInt ?: throw NativeFunctionException("equal_int", "expects 'left' as Int")
            val right = record.fields["right"] as? FunkInt ?: throw NativeFunctionException("equal_int", "expects 'right' as Int")
            FunkBool(left.value == right.value)
        }

        context["lessThan_int"] = NativeFunkFunction("lessThan_int") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["left"] as? FunkInt ?: throw NativeFunctionException("lessThan_int", "expects 'left' as Int")
            val right = record.fields["right"] as? FunkInt ?: throw NativeFunctionException("lessThan_int", "expects 'right' as Int")
            FunkBool(left.value < right.value)
        }

        context["greaterThan_int"] = NativeFunkFunction("greaterThan_int") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["left"] as? FunkInt ?: throw NativeFunctionException("greaterThan_int", "expects 'left' as Int")
            val right = record.fields["right"] as? FunkInt ?: throw NativeFunctionException("greaterThan_int", "expects 'right' as Int")
            FunkBool(left.value > right.value)
        }

        // Conversion operations
        context["intToFloat"] = NativeFunkFunction("intToFloat") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["val"] as? FunkInt ?: throw NativeFunctionException("intToFloat", "expects 'val' as Int")
            FunkFloat(value.value.toDouble())
        }

        context["intToFrac"] = NativeFunkFunction("intToFrac") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["val"] as? FunkInt ?: throw NativeFunctionException("intToFrac", "expects 'val' as Int")
            FunkFrac(value.value, 1L)
        }

        context["intToNat"] = NativeFunkFunction("intToNat") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["val"] as? FunkInt ?: throw NativeFunctionException("intToNat", "expects 'val' as Int")
            if (value.value < 0) throw FunkException("Cannot convert negative integer to Nat: ${value.value}")
            FunkNat(value.value)
        }

        // Hidden native operations
        context["__native_int_equals"] = NativeFunkFunction("__native_int_equals") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkInt ?: throw NativeFunctionException("__native_int_equals", "expects 'l' as Int")
            val r = record.fields["r"] as? FunkInt ?: throw NativeFunctionException("__native_int_equals", "expects 'r' as Int")
            FunkBool(l.value == r.value)
        }

        context["__native_int_less_than"] = NativeFunkFunction("__native_int_less_than") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkInt ?: throw NativeFunctionException("__native_int_less_than", "expects 'l' as Int")
            val r = record.fields["r"] as? FunkInt ?: throw NativeFunctionException("__native_int_less_than", "expects 'r' as Int")
            FunkBool(l.value < r.value)
        }

        context["__native_int_greater_than"] = NativeFunkFunction("__native_int_greater_than") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkInt ?: throw NativeFunctionException("__native_int_greater_than", "expects 'l' as Int")
            val r = record.fields["r"] as? FunkInt ?: throw NativeFunctionException("__native_int_greater_than", "expects 'r' as Int")
            FunkBool(l.value > r.value)
        }

        context["__native_int_compare"] = NativeFunkFunction("__native_int_compare") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkInt ?: throw NativeFunctionException("__native_int_compare", "expects 'l' as Int")
            val r = record.fields["r"] as? FunkInt ?: throw NativeFunctionException("__native_int_compare", "expects 'r' as Int")
            val result = when {
                l.value < r.value -> "Less"
                l.value > r.value -> "Greater"
                else -> "Equal"
            }
            // TODO: Return proper variant instead of string
            FunkString(result)
        }

        context["__native_int_to_string"] = NativeFunkFunction("__native_int_to_string") { args ->
            val record = args[0] as FunkRecord
            val n = record.fields["n"] as? FunkInt ?: throw NativeFunctionException("__native_int_to_string", "expects 'n' as Int")
            FunkString(n.value.toString())
        }

        context["__native_int_add"] = NativeFunkFunction("__native_int_add") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["l"] as? FunkInt ?: throw NativeFunctionException("__native_int_add", "expects 'l' as Int")
            val right = record.fields["r"] as? FunkInt ?: throw NativeFunctionException("__native_int_add", "expects 'r' as Int")
            FunkInt(left.value + right.value)
        }

        context["__native_int_subtract"] = NativeFunkFunction("__native_int_subtract") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["l"] as? FunkInt ?: throw NativeFunctionException("__native_int_subtract", "expects 'l' as Int")
            val right = record.fields["r"] as? FunkInt ?: throw NativeFunctionException("__native_int_subtract", "expects 'r' as Int")
            FunkInt(left.value - right.value)
        }

        context["__native_int_multiply"] = NativeFunkFunction("__native_int_multiply") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["l"] as? FunkInt ?: throw NativeFunctionException("__native_int_multiply", "expects 'l' as Int")
            val right = record.fields["r"] as? FunkInt ?: throw NativeFunctionException("__native_int_multiply", "expects 'r' as Int")
            FunkInt(left.value * right.value)
        }

        context["__native_int_divide"] = NativeFunkFunction("__native_int_divide") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["l"] as? FunkInt ?: throw NativeFunctionException("__native_int_divide", "expects 'l' as Int")
            val right = record.fields["r"] as? FunkInt ?: throw NativeFunctionException("__native_int_divide", "expects 'r' as Int")
            if (right.value == 0L) throw FunkException("Division by zero")
            FunkInt(left.value / right.value)
        }

        context["__native_int_modulo"] = NativeFunkFunction("__native_int_modulo") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["l"] as? FunkInt ?: throw NativeFunctionException("__native_int_modulo", "expects 'l' as Int")
            val right = record.fields["r"] as? FunkInt ?: throw NativeFunctionException("__native_int_modulo", "expects 'r' as Int")
            if (right.value == 0L) throw FunkException("Modulo by zero")
            FunkInt(left.value % right.value)
        }

        context["__native_int_to_frac"] = NativeFunkFunction("__native_int_to_frac") { args ->
            val record = args[0] as FunkRecord
            val n = record.fields["n"] as? FunkInt ?: throw NativeFunctionException("__native_int_to_frac", "expects 'n' as Int")
            FunkFrac(n.value, 1L)
        }
    }
}
