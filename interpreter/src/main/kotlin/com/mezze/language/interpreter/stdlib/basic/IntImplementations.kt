package com.mezze.language.interpreter.stdlib.basic

import com.mezze.language.interpreter.*

/**
 * Truffle runtime implementations for Int type operations
 */
object IntImplementations {

    /**
     * Register all Int runtime implementations
     */
    fun registerAll(context: MutableMap<String, MezzeValue>) {
        // Arithmetic operations
        context["add_int"] = NativeMezzeFunction("add_int") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeInt ?: throw NativeFunctionException("add_int", "expects 'l' as Int")
            val r = record.fields["r"] as? MezzeInt ?: throw NativeFunctionException("add_int", "expects 'r' as Int")
            MezzeInt(l.value + r.value)
        }

        context["subtract_int"] = NativeMezzeFunction("subtract_int") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeInt ?: throw NativeFunctionException("subtract_int", "expects 'l' as Int")
            val r = record.fields["r"] as? MezzeInt ?: throw NativeFunctionException("subtract_int", "expects 'r' as Int")
            MezzeInt(l.value - r.value)
        }

        context["multiply_int"] = NativeMezzeFunction("multiply_int") { args ->
            val record = args[0] as MezzeRecord
            val left = record.fields["left"] as? MezzeInt ?: throw NativeFunctionException("multiply_int", "expects 'left' as Int")
            val right = record.fields["right"] as? MezzeInt ?: throw NativeFunctionException("multiply_int", "expects 'right' as Int")
            MezzeInt(left.value * right.value)
        }

        context["divide_int"] = NativeMezzeFunction("divide_int") { args ->
            val record = args[0] as MezzeRecord
            val left = record.fields["left"] as? MezzeInt ?: throw NativeFunctionException("divide_int", "expects 'left' as Int")
            val right = record.fields["right"] as? MezzeInt ?: throw NativeFunctionException("divide_int", "expects 'right' as Int")
            if (right.value == 0L) throw MezzeException("Division by zero")
            MezzeInt(left.value / right.value)
        }

        context["modulo_int"] = NativeMezzeFunction("modulo_int") { args ->
            val record = args[0] as MezzeRecord
            val left = record.fields["left"] as? MezzeInt ?: throw NativeFunctionException("modulo_int", "expects 'left' as Int")
            val right = record.fields["right"] as? MezzeInt ?: throw NativeFunctionException("modulo_int", "expects 'right' as Int")
            if (right.value == 0L) throw MezzeException("Modulo by zero")
            MezzeInt(left.value % right.value)
        }

        // Comparison operations
        context["equal_int"] = NativeMezzeFunction("equal_int") { args ->
            val record = args[0] as MezzeRecord
            val left = record.fields["left"] as? MezzeInt ?: throw NativeFunctionException("equal_int", "expects 'left' as Int")
            val right = record.fields["right"] as? MezzeInt ?: throw NativeFunctionException("equal_int", "expects 'right' as Int")
            MezzeBool(left.value == right.value)
        }

        context["lessThan_int"] = NativeMezzeFunction("lessThan_int") { args ->
            val record = args[0] as MezzeRecord
            val left = record.fields["left"] as? MezzeInt ?: throw NativeFunctionException("lessThan_int", "expects 'left' as Int")
            val right = record.fields["right"] as? MezzeInt ?: throw NativeFunctionException("lessThan_int", "expects 'right' as Int")
            MezzeBool(left.value < right.value)
        }

        context["greaterThan_int"] = NativeMezzeFunction("greaterThan_int") { args ->
            val record = args[0] as MezzeRecord
            val left = record.fields["left"] as? MezzeInt ?: throw NativeFunctionException("greaterThan_int", "expects 'left' as Int")
            val right = record.fields["right"] as? MezzeInt ?: throw NativeFunctionException("greaterThan_int", "expects 'right' as Int")
            MezzeBool(left.value > right.value)
        }

        // Conversion operations
        context["intToFloat"] = NativeMezzeFunction("intToFloat") { args ->
            val record = args[0] as MezzeRecord
            val value = record.fields["val"] as? MezzeInt ?: throw NativeFunctionException("intToFloat", "expects 'val' as Int")
            MezzeFloat(value.value.toDouble())
        }

        context["intToFrac"] = NativeMezzeFunction("intToFrac") { args ->
            val record = args[0] as MezzeRecord
            val value = record.fields["val"] as? MezzeInt ?: throw NativeFunctionException("intToFrac", "expects 'val' as Int")
            MezzeFrac(value.value, 1L)
        }

        context["intToNat"] = NativeMezzeFunction("intToNat") { args ->
            val record = args[0] as MezzeRecord
            val value = record.fields["val"] as? MezzeInt ?: throw NativeFunctionException("intToNat", "expects 'val' as Int")
            if (value.value < 0) throw MezzeException("Cannot convert negative integer to Nat: ${value.value}")
            MezzeNat(value.value)
        }

        // Hidden native operations
        context["__native_int_equals"] = NativeMezzeFunction("__native_int_equals") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeInt ?: throw NativeFunctionException("__native_int_equals", "expects 'l' as Int")
            val r = record.fields["r"] as? MezzeInt ?: throw NativeFunctionException("__native_int_equals", "expects 'r' as Int")
            MezzeBool(l.value == r.value)
        }

        context["__native_int_less_than"] = NativeMezzeFunction("__native_int_less_than") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeInt ?: throw NativeFunctionException("__native_int_less_than", "expects 'l' as Int")
            val r = record.fields["r"] as? MezzeInt ?: throw NativeFunctionException("__native_int_less_than", "expects 'r' as Int")
            MezzeBool(l.value < r.value)
        }

        context["__native_int_greater_than"] = NativeMezzeFunction("__native_int_greater_than") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeInt ?: throw NativeFunctionException("__native_int_greater_than", "expects 'l' as Int")
            val r = record.fields["r"] as? MezzeInt ?: throw NativeFunctionException("__native_int_greater_than", "expects 'r' as Int")
            MezzeBool(l.value > r.value)
        }

        context["__native_int_compare"] = NativeMezzeFunction("__native_int_compare") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeInt ?: throw NativeFunctionException("__native_int_compare", "expects 'l' as Int")
            val r = record.fields["r"] as? MezzeInt ?: throw NativeFunctionException("__native_int_compare", "expects 'r' as Int")
            val result = when {
                l.value < r.value -> "Less"
                l.value > r.value -> "Greater"
                else -> "Equal"
            }
            // TODO: Return proper variant instead of string
            MezzeString(result)
        }

        context["__native_int_to_string"] = NativeMezzeFunction("__native_int_to_string") { args ->
            val record = args[0] as MezzeRecord
            val n = record.fields["n"] as? MezzeInt ?: throw NativeFunctionException("__native_int_to_string", "expects 'n' as Int")
            MezzeString(n.value.toString())
        }

        context["__native_int_add"] = NativeMezzeFunction("__native_int_add") { args ->
            val record = args[0] as MezzeRecord
            val left = record.fields["l"] as? MezzeInt ?: throw NativeFunctionException("__native_int_add", "expects 'l' as Int")
            val right = record.fields["r"] as? MezzeInt ?: throw NativeFunctionException("__native_int_add", "expects 'r' as Int")
            try {
                MezzeInt(Math.addExact(left.value, right.value))
            } catch (e: ArithmeticException) {
                throw MezzeException("Integer overflow in addition: ${left.value} + ${right.value}")
            }
        }

        context["__native_int_subtract"] = NativeMezzeFunction("__native_int_subtract") { args ->
            val record = args[0] as MezzeRecord
            val left = record.fields["l"] as? MezzeInt ?: throw NativeFunctionException("__native_int_subtract", "expects 'l' as Int")
            val right = record.fields["r"] as? MezzeInt ?: throw NativeFunctionException("__native_int_subtract", "expects 'r' as Int")
            try {
                MezzeInt(Math.subtractExact(left.value, right.value))
            } catch (e: ArithmeticException) {
                throw MezzeException("Integer overflow in subtraction: ${left.value} - ${right.value}")
            }
        }

        context["__native_int_multiply"] = NativeMezzeFunction("__native_int_multiply") { args ->
            val record = args[0] as MezzeRecord
            val left = record.fields["l"] as? MezzeInt ?: throw NativeFunctionException("__native_int_multiply", "expects 'l' as Int")
            val right = record.fields["r"] as? MezzeInt ?: throw NativeFunctionException("__native_int_multiply", "expects 'r' as Int")
            try {
                MezzeInt(Math.multiplyExact(left.value, right.value))
            } catch (e: ArithmeticException) {
                throw MezzeException("Integer overflow in multiplication: ${left.value} * ${right.value}")
            }
        }

        context["__native_int_divide"] = NativeMezzeFunction("__native_int_divide") { args ->
            val record = args[0] as MezzeRecord
            val left = record.fields["l"] as? MezzeInt ?: throw NativeFunctionException("__native_int_divide", "expects 'l' as Int")
            val right = record.fields["r"] as? MezzeInt ?: throw NativeFunctionException("__native_int_divide", "expects 'r' as Int")
            if (right.value == 0L) throw MezzeException("Division by zero")
            MezzeInt(left.value / right.value)
        }

        context["__native_int_modulo"] = NativeMezzeFunction("__native_int_modulo") { args ->
            val record = args[0] as MezzeRecord
            val left = record.fields["l"] as? MezzeInt ?: throw NativeFunctionException("__native_int_modulo", "expects 'l' as Int")
            val right = record.fields["r"] as? MezzeInt ?: throw NativeFunctionException("__native_int_modulo", "expects 'r' as Int")
            if (right.value == 0L) throw MezzeException("Modulo by zero")
            MezzeInt(left.value % right.value)
        }

        context["__native_int_to_frac"] = NativeMezzeFunction("__native_int_to_frac") { args ->
            val record = args[0] as MezzeRecord
            val n = record.fields["n"] as? MezzeInt ?: throw NativeFunctionException("__native_int_to_frac", "expects 'n' as Int")
            MezzeFrac(n.value, 1L)
        }

        context["__native_int_neg"] = NativeMezzeFunction("__native_int_neg") { args ->
            val record = args[0] as MezzeRecord
            val value = record.fields["n"] as? MezzeInt ?: throw NativeFunctionException("__native_int_neg", "expects 'n' as Int")
            try {
                MezzeInt(Math.negateExact(value.value))
            } catch (e: ArithmeticException) {
                throw MezzeException("Integer overflow in negation: ${value.value}")
            }
        }

        context["__native_int_pow"] = NativeMezzeFunction("__native_int_pow") { args ->
            val record = args[0] as MezzeRecord
            val base = record.fields["base"] as? MezzeInt ?: throw NativeFunctionException("__native_int_pow", "expects 'base' as Int")
            val exp = record.fields["exp"] as? MezzeInt ?: throw NativeFunctionException("__native_int_pow", "expects 'exp' as Int")
            if (exp.value < 0) throw MezzeException("Negative exponent not supported for integer power")
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
                MezzeInt(result)
            } catch (ex: ArithmeticException) {
                throw MezzeException("Integer overflow in power: ${base.value} ^ ${exp.value}")
            }
        }

        context["__native_int_sign"] = NativeMezzeFunction("__native_int_sign") { args ->
            val record = args[0] as MezzeRecord
            val value = record.fields["n"] as? MezzeInt ?: throw NativeFunctionException("__native_int_sign", "expects 'n' as Int")
            MezzeInt(when {
                value.value < 0 -> -1L
                value.value > 0 -> 1L
                else -> 0L
            })
        }

        context["__native_int_from_string"] = NativeMezzeFunction("__native_int_from_string") { args ->
            val record = args[0] as MezzeRecord
            val s = record.fields["s"] as? MezzeString ?: throw NativeFunctionException("__native_int_from_string", "expects 's' as String")
            try {
                val value = s.value.toLong()
                // Return Some {val = value}
                MezzeVariant("Some", MezzeRecord(mapOf("val" to MezzeInt(value))))
            } catch (e: NumberFormatException) {
                // Return None
                MezzeVariant("None", null)
            }
        }

        context["__native_int_from_nat"] = NativeMezzeFunction("__native_int_from_nat") { args ->
            val record = args[0] as MezzeRecord
            val n = record.fields["n"] as? MezzeNat ?: throw NativeFunctionException("__native_int_from_nat", "expects 'n' as Nat")
            MezzeInt(n.value)
        }
    }
}
