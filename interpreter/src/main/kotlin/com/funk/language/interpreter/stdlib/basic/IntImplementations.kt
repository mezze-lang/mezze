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
            try {
                FunkInt(Math.addExact(left.value, right.value))
            } catch (e: ArithmeticException) {
                throw FunkException("Integer overflow in addition: ${left.value} + ${right.value}")
            }
        }

        context["__native_int_subtract"] = NativeFunkFunction("__native_int_subtract") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["l"] as? FunkInt ?: throw NativeFunctionException("__native_int_subtract", "expects 'l' as Int")
            val right = record.fields["r"] as? FunkInt ?: throw NativeFunctionException("__native_int_subtract", "expects 'r' as Int")
            try {
                FunkInt(Math.subtractExact(left.value, right.value))
            } catch (e: ArithmeticException) {
                throw FunkException("Integer overflow in subtraction: ${left.value} - ${right.value}")
            }
        }

        context["__native_int_multiply"] = NativeFunkFunction("__native_int_multiply") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["l"] as? FunkInt ?: throw NativeFunctionException("__native_int_multiply", "expects 'l' as Int")
            val right = record.fields["r"] as? FunkInt ?: throw NativeFunctionException("__native_int_multiply", "expects 'r' as Int")
            try {
                FunkInt(Math.multiplyExact(left.value, right.value))
            } catch (e: ArithmeticException) {
                throw FunkException("Integer overflow in multiplication: ${left.value} * ${right.value}")
            }
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

        context["__native_int_neg"] = NativeFunkFunction("__native_int_neg") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["n"] as? FunkInt ?: throw NativeFunctionException("__native_int_neg", "expects 'n' as Int")
            try {
                FunkInt(Math.negateExact(value.value))
            } catch (e: ArithmeticException) {
                throw FunkException("Integer overflow in negation: ${value.value}")
            }
        }

        context["__native_int_pow"] = NativeFunkFunction("__native_int_pow") { args ->
            val record = args[0] as FunkRecord
            val base = record.fields["base"] as? FunkInt ?: throw NativeFunctionException("__native_int_pow", "expects 'base' as Int")
            val exp = record.fields["exp"] as? FunkInt ?: throw NativeFunctionException("__native_int_pow", "expects 'exp' as Int")
            if (exp.value < 0) throw FunkException("Negative exponent not supported for integer power")
            // Compute power using repeated multiplication with overflow checking
            var result = 1L
            var b = base.value
            var e = exp.value
            try {
                while (e > 0) {
                    if (e and 1 == 1L) {
                        result = Math.multiplyExact(result, b)
                    }
                    e = e shr 1
                    if (e > 0) {
                        b = Math.multiplyExact(b, b)
                    }
                }
                FunkInt(result)
            } catch (ex: ArithmeticException) {
                throw FunkException("Integer overflow in power: ${base.value} ^ ${exp.value}")
            }
        }

        context["__native_int_sign"] = NativeFunkFunction("__native_int_sign") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["n"] as? FunkInt ?: throw NativeFunctionException("__native_int_sign", "expects 'n' as Int")
            FunkInt(when {
                value.value < 0 -> -1L
                value.value > 0 -> 1L
                else -> 0L
            })
        }

        context["__native_int_from_string"] = NativeFunkFunction("__native_int_from_string") { args ->
            val record = args[0] as FunkRecord
            val s = record.fields["s"] as? FunkString ?: throw NativeFunctionException("__native_int_from_string", "expects 's' as String")
            try {
                val value = s.value.toLong()
                // Return Some {val = value}
                FunkVariant("Some", FunkRecord(mapOf("val" to FunkInt(value))))
            } catch (e: NumberFormatException) {
                // Return None
                FunkVariant("None", null)
            }
        }

        context["__native_int_from_nat"] = NativeFunkFunction("__native_int_from_nat") { args ->
            val record = args[0] as FunkRecord
            val n = record.fields["n"] as? FunkNat ?: throw NativeFunctionException("__native_int_from_nat", "expects 'n' as Nat")
            FunkInt(n.value)
        }
    }
}
