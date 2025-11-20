package com.funk.language.interpreter.stdlib.basic

import com.funk.language.interpreter.*

/**
 * Truffle runtime implementations for Nat type operations
 */
object NatImplementations {

    /**
     * Register all Nat runtime implementations
     */
    fun registerAll(context: MutableMap<String, FunkValue>) {
        // Arithmetic operations
        context["add_nat"] = NativeFunkFunction("add_nat") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkNat ?: throw NativeFunctionException("add_nat", "expects 'l' as Nat")
            val r = record.fields["r"] as? FunkNat ?: throw NativeFunctionException("add_nat", "expects 'r' as Nat")
            FunkNat(l.value + r.value)
        }

        // Special case: Nat - Nat = Int (result might be negative)
        context["subtract_nat"] = NativeFunkFunction("subtract_nat") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["left"] as? FunkNat ?: throw NativeFunctionException("subtract_nat", "expects 'left' as Nat")
            val right = record.fields["right"] as? FunkNat ?: throw NativeFunctionException("subtract_nat", "expects 'right' as Nat")
            FunkInt(left.value - right.value) // Returns Int, not Nat
        }

        context["multiply_nat"] = NativeFunkFunction("multiply_nat") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["left"] as? FunkNat ?: throw NativeFunctionException("multiply_nat", "expects 'left' as Nat")
            val right = record.fields["right"] as? FunkNat ?: throw NativeFunctionException("multiply_nat", "expects 'right' as Nat")
            FunkNat(left.value * right.value)
        }

        context["divide_nat"] = NativeFunkFunction("divide_nat") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["left"] as? FunkNat ?: throw NativeFunctionException("divide_nat", "expects 'left' as Nat")
            val right = record.fields["right"] as? FunkNat ?: throw NativeFunctionException("divide_nat", "expects 'right' as Nat")
            if (right.value == 0L) throw FunkException("Division by zero")
            FunkNat(left.value / right.value)
        }

        context["modulo_nat"] = NativeFunkFunction("modulo_nat") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["left"] as? FunkNat ?: throw NativeFunctionException("modulo_nat", "expects 'left' as Nat")
            val right = record.fields["right"] as? FunkNat ?: throw NativeFunctionException("modulo_nat", "expects 'right' as Nat")
            if (right.value == 0L) throw FunkException("Modulo by zero")
            FunkNat(left.value % right.value)
        }

        // Comparison operations
        context["equal_nat"] = NativeFunkFunction("equal_nat") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["left"] as? FunkNat ?: throw NativeFunctionException("equal_nat", "expects 'left' as Nat")
            val right = record.fields["right"] as? FunkNat ?: throw NativeFunctionException("equal_nat", "expects 'right' as Nat")
            FunkBool(left.value == right.value)
        }

        context["lessThan_nat"] = NativeFunkFunction("lessThan_nat") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["left"] as? FunkNat ?: throw NativeFunctionException("lessThan_nat", "expects 'left' as Nat")
            val right = record.fields["right"] as? FunkNat ?: throw NativeFunctionException("lessThan_nat", "expects 'right' as Nat")
            FunkBool(left.value < right.value)
        }

        context["greaterThan_nat"] = NativeFunkFunction("greaterThan_nat") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["left"] as? FunkNat ?: throw NativeFunctionException("greaterThan_nat", "expects 'left' as Nat")
            val right = record.fields["right"] as? FunkNat ?: throw NativeFunctionException("greaterThan_nat", "expects 'right' as Nat")
            FunkBool(left.value > right.value)
        }

        // Conversion operations
        context["natToInt"] = NativeFunkFunction("natToInt") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["val"] as? FunkNat ?: throw NativeFunctionException("natToInt", "expects 'val' as Nat")
            FunkInt(value.value)
        }

        context["natToFloat"] = NativeFunkFunction("natToFloat") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["val"] as? FunkNat ?: throw NativeFunctionException("natToFloat", "expects 'val' as Nat")
            FunkFloat(value.value.toDouble())
        }

        context["natToFrac"] = NativeFunkFunction("natToFrac") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["val"] as? FunkNat ?: throw NativeFunctionException("natToFrac", "expects 'val' as Nat")
            FunkFrac(value.value, 1L)
        }

        // Hidden native operations
        context["__native_nat_equals"] = NativeFunkFunction("__native_nat_equals") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkNat ?: throw NativeFunctionException("__native_nat_equals", "expects 'l' as Nat")
            val r = record.fields["r"] as? FunkNat ?: throw NativeFunctionException("__native_nat_equals", "expects 'r' as Nat")
            FunkBool(l.value == r.value)
        }

        context["__native_nat_less_than"] = NativeFunkFunction("__native_nat_less_than") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkNat ?: throw NativeFunctionException("__native_nat_less_than", "expects 'l' as Nat")
            val r = record.fields["r"] as? FunkNat ?: throw NativeFunctionException("__native_nat_less_than", "expects 'r' as Nat")
            FunkBool(l.value < r.value)
        }

        context["__native_nat_greater_than"] = NativeFunkFunction("__native_nat_greater_than") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkNat ?: throw NativeFunctionException("__native_nat_greater_than", "expects 'l' as Nat")
            val r = record.fields["r"] as? FunkNat ?: throw NativeFunctionException("__native_nat_greater_than", "expects 'r' as Nat")
            FunkBool(l.value > r.value)
        }

        context["__native_nat_to_string"] = NativeFunkFunction("__native_nat_to_string") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["value"] as? FunkNat ?: throw NativeFunctionException("__native_nat_to_string", "expects 'value' as Nat")
            FunkString(value.value.toString())
        }

        context["__native_nat_subtract_to_int"] = NativeFunkFunction("__native_nat_subtract_to_int") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkNat ?: throw NativeFunctionException("__native_nat_subtract_to_int", "expects 'l' as Nat")
            val r = record.fields["r"] as? FunkNat ?: throw NativeFunctionException("__native_nat_subtract_to_int", "expects 'r' as Nat")
            FunkInt(l.value - r.value) // Returns Int, not Nat
        }

        context["__native_nat_add"] = NativeFunkFunction("__native_nat_add") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkNat ?: throw NativeFunctionException("__native_nat_add", "expects 'l' as Nat")
            val r = record.fields["r"] as? FunkNat ?: throw NativeFunctionException("__native_nat_add", "expects 'r' as Nat")
            FunkNat(l.value + r.value)
        }

        context["__native_nat_subtract"] = NativeFunkFunction("__native_nat_subtract") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkNat ?: throw NativeFunctionException("__native_nat_subtract", "expects 'l' as Nat")
            val r = record.fields["r"] as? FunkNat ?: throw NativeFunctionException("__native_nat_subtract", "expects 'r' as Nat")
            if (l.value < r.value) throw FunkException("Nat subtraction would result in negative value")
            FunkNat(l.value - r.value)
        }

        context["__native_nat_multiply"] = NativeFunkFunction("__native_nat_multiply") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkNat ?: throw NativeFunctionException("__native_nat_multiply", "expects 'l' as Nat")
            val r = record.fields["r"] as? FunkNat ?: throw NativeFunctionException("__native_nat_multiply", "expects 'r' as Nat")
            FunkNat(l.value * r.value)
        }

        context["__native_nat_divide"] = NativeFunkFunction("__native_nat_divide") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkNat ?: throw NativeFunctionException("__native_nat_divide", "expects 'l' as Nat")
            val r = record.fields["r"] as? FunkNat ?: throw NativeFunctionException("__native_nat_divide", "expects 'r' as Nat")
            if (r.value == 0L) throw FunkException("Division by zero")
            FunkNat(l.value / r.value)
        }

        context["__native_nat_modulo"] = NativeFunkFunction("__native_nat_modulo") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkNat ?: throw NativeFunctionException("__native_nat_modulo", "expects 'l' as Nat")
            val r = record.fields["r"] as? FunkNat ?: throw NativeFunctionException("__native_nat_modulo", "expects 'r' as Nat")
            if (r.value == 0L) throw FunkException("Modulo by zero")
            FunkNat(l.value % r.value)
        }

        context["__native_nat_compare"] = NativeFunkFunction("__native_nat_compare") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkNat ?: throw NativeFunctionException("__native_nat_compare", "expects 'l' as Nat")
            val r = record.fields["r"] as? FunkNat ?: throw NativeFunctionException("__native_nat_compare", "expects 'r' as Nat")
            val result = when {
                l.value < r.value -> "Less"
                l.value > r.value -> "Greater"
                else -> "Equal"
            }
            // TODO: Return proper variant instead of string
            FunkString(result)
        }

        context["__native_nat_to_frac"] = NativeFunkFunction("__native_nat_to_frac") { args ->
            val record = args[0] as FunkRecord
            val n = record.fields["n"] as? FunkNat ?: throw NativeFunctionException("__native_nat_to_frac", "expects 'n' as Nat")
            FunkFrac(n.value, 1L)
        }
    }
}
