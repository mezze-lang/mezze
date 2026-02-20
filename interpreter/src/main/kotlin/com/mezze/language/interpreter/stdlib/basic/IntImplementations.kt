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
                mezzeOptionSome(MezzeInt(value))
            } catch (e: NumberFormatException) {
                // Return None
                mezzeOptionNone()
            }
        }

        context["__native_int_from_nat"] = NativeMezzeFunction("__native_int_from_nat") { args ->
            val record = args[0] as MezzeRecord
            val n = record.fields["n"] as? MezzeNat ?: throw NativeFunctionException("__native_int_from_nat", "expects 'n' as Nat")
            MezzeInt(n.value)
        }

        context["__native_int_to_float"] = NativeMezzeFunction("__native_int_to_float") { args ->
            val record = args[0] as MezzeRecord
            val n = record.fields["n"] as? MezzeInt ?: throw NativeFunctionException("__native_int_to_float", "expects 'n' as Int")
            MezzeFloat(n.value.toDouble())
        }

        context["__native_int_abs"] = NativeMezzeFunction("__native_int_abs") { args ->
            val record = args[0] as MezzeRecord
            val n = record.fields["n"] as? MezzeInt ?: throw NativeFunctionException("__native_int_abs", "expects 'n' as Int")
            MezzeInt(kotlin.math.abs(n.value))
        }

        context["__native_int_min"] = NativeMezzeFunction("__native_int_min") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeInt ?: throw NativeFunctionException("__native_int_min", "expects 'l' as Int")
            val r = record.fields["r"] as? MezzeInt ?: throw NativeFunctionException("__native_int_min", "expects 'r' as Int")
            MezzeInt(kotlin.math.min(l.value, r.value))
        }

        context["__native_int_max"] = NativeMezzeFunction("__native_int_max") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeInt ?: throw NativeFunctionException("__native_int_max", "expects 'l' as Int")
            val r = record.fields["r"] as? MezzeInt ?: throw NativeFunctionException("__native_int_max", "expects 'r' as Int")
            MezzeInt(kotlin.math.max(l.value, r.value))
        }
    }
}
