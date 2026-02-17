package com.mezze.language.interpreter.stdlib.basic

import com.mezze.language.interpreter.*

/**
 * Truffle-optimized implementations for Nat type operations.
 *
 * Phase 5 of Truffle migration: Basic type operations optimized for JIT performance.
 * Nat is the natural number type representing non-negative integers (0, 1, 2, ...).
 *
 * Optimizations:
 * - Inline Kotlin arithmetic (+, *, /, %) for JIT compilation
 * - Direct integer comparison with no overhead
 * - Zero-cost abstraction over Kotlin Long (with >= 0 constraint)
 * - Special case: Nat - Nat returns Int (can be negative)
 * - Proper division-by-zero and modulo-by-zero checks
 * - Safe conversions to Int, Float, and Frac types
 *
 * Note: __native_nat_subtract throws on negative results,
 *       subtract_nat returns Int to allow negative results
 *
 * Total: 23 natural number operations (arithmetic, comparison, conversion)
 */
object NatImplementations {

    /**
     * Register all Nat runtime implementations
     */
    fun registerAll(context: MutableMap<String, MezzeValue>) {
        // Arithmetic operations
        context["add_nat"] = NativeMezzeFunction("add_nat") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeNat ?: throw NativeFunctionException("add_nat", "expects 'l' as Nat")
            val r = record.fields["r"] as? MezzeNat ?: throw NativeFunctionException("add_nat", "expects 'r' as Nat")
            MezzeNat(l.value + r.value)
        }

        // Special case: Nat - Nat = Int (result might be negative)
        context["subtract_nat"] = NativeMezzeFunction("subtract_nat") { args ->
            val record = args[0] as MezzeRecord
            val left = record.fields["left"] as? MezzeNat ?: throw NativeFunctionException("subtract_nat", "expects 'left' as Nat")
            val right = record.fields["right"] as? MezzeNat ?: throw NativeFunctionException("subtract_nat", "expects 'right' as Nat")
            MezzeInt(left.value - right.value) // Returns Int, not Nat
        }

        context["multiply_nat"] = NativeMezzeFunction("multiply_nat") { args ->
            val record = args[0] as MezzeRecord
            val left = record.fields["left"] as? MezzeNat ?: throw NativeFunctionException("multiply_nat", "expects 'left' as Nat")
            val right = record.fields["right"] as? MezzeNat ?: throw NativeFunctionException("multiply_nat", "expects 'right' as Nat")
            MezzeNat(left.value * right.value)
        }

        context["divide_nat"] = NativeMezzeFunction("divide_nat") { args ->
            val record = args[0] as MezzeRecord
            val left = record.fields["left"] as? MezzeNat ?: throw NativeFunctionException("divide_nat", "expects 'left' as Nat")
            val right = record.fields["right"] as? MezzeNat ?: throw NativeFunctionException("divide_nat", "expects 'right' as Nat")
            if (right.value == 0L) throw MezzeException("Division by zero")
            MezzeNat(left.value / right.value)
        }

        context["modulo_nat"] = NativeMezzeFunction("modulo_nat") { args ->
            val record = args[0] as MezzeRecord
            val left = record.fields["left"] as? MezzeNat ?: throw NativeFunctionException("modulo_nat", "expects 'left' as Nat")
            val right = record.fields["right"] as? MezzeNat ?: throw NativeFunctionException("modulo_nat", "expects 'right' as Nat")
            if (right.value == 0L) throw MezzeException("Modulo by zero")
            MezzeNat(left.value % right.value)
        }

        // Comparison operations
        context["equal_nat"] = NativeMezzeFunction("equal_nat") { args ->
            val record = args[0] as MezzeRecord
            val left = record.fields["left"] as? MezzeNat ?: throw NativeFunctionException("equal_nat", "expects 'left' as Nat")
            val right = record.fields["right"] as? MezzeNat ?: throw NativeFunctionException("equal_nat", "expects 'right' as Nat")
            MezzeBool(left.value == right.value)
        }

        context["lessThan_nat"] = NativeMezzeFunction("lessThan_nat") { args ->
            val record = args[0] as MezzeRecord
            val left = record.fields["left"] as? MezzeNat ?: throw NativeFunctionException("lessThan_nat", "expects 'left' as Nat")
            val right = record.fields["right"] as? MezzeNat ?: throw NativeFunctionException("lessThan_nat", "expects 'right' as Nat")
            MezzeBool(left.value < right.value)
        }

        context["greaterThan_nat"] = NativeMezzeFunction("greaterThan_nat") { args ->
            val record = args[0] as MezzeRecord
            val left = record.fields["left"] as? MezzeNat ?: throw NativeFunctionException("greaterThan_nat", "expects 'left' as Nat")
            val right = record.fields["right"] as? MezzeNat ?: throw NativeFunctionException("greaterThan_nat", "expects 'right' as Nat")
            MezzeBool(left.value > right.value)
        }

        // Conversion operations
        context["natToInt"] = NativeMezzeFunction("natToInt") { args ->
            val record = args[0] as MezzeRecord
            val value = record.fields["val"] as? MezzeNat ?: throw NativeFunctionException("natToInt", "expects 'val' as Nat")
            MezzeInt(value.value)
        }

        context["natToFloat"] = NativeMezzeFunction("natToFloat") { args ->
            val record = args[0] as MezzeRecord
            val value = record.fields["val"] as? MezzeNat ?: throw NativeFunctionException("natToFloat", "expects 'val' as Nat")
            MezzeFloat(value.value.toDouble())
        }

        context["natToFrac"] = NativeMezzeFunction("natToFrac") { args ->
            val record = args[0] as MezzeRecord
            val value = record.fields["val"] as? MezzeNat ?: throw NativeFunctionException("natToFrac", "expects 'val' as Nat")
            MezzeFrac(value.value, 1L)
        }

        // Hidden native operations
        context["__native_nat_equals"] = NativeMezzeFunction("__native_nat_equals") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeNat ?: throw NativeFunctionException("__native_nat_equals", "expects 'l' as Nat")
            val r = record.fields["r"] as? MezzeNat ?: throw NativeFunctionException("__native_nat_equals", "expects 'r' as Nat")
            MezzeBool(l.value == r.value)
        }

        context["__native_nat_less_than"] = NativeMezzeFunction("__native_nat_less_than") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeNat ?: throw NativeFunctionException("__native_nat_less_than", "expects 'l' as Nat")
            val r = record.fields["r"] as? MezzeNat ?: throw NativeFunctionException("__native_nat_less_than", "expects 'r' as Nat")
            MezzeBool(l.value < r.value)
        }

        context["__native_nat_greater_than"] = NativeMezzeFunction("__native_nat_greater_than") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeNat ?: throw NativeFunctionException("__native_nat_greater_than", "expects 'l' as Nat")
            val r = record.fields["r"] as? MezzeNat ?: throw NativeFunctionException("__native_nat_greater_than", "expects 'r' as Nat")
            MezzeBool(l.value > r.value)
        }

        context["__native_nat_to_string"] = NativeMezzeFunction("__native_nat_to_string") { args ->
            val record = args[0] as MezzeRecord
            val value = record.fields["value"] as? MezzeNat ?: throw NativeFunctionException("__native_nat_to_string", "expects 'value' as Nat")
            MezzeString(value.value.toString())
        }

        context["__native_nat_subtract_to_int"] = NativeMezzeFunction("__native_nat_subtract_to_int") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeNat ?: throw NativeFunctionException("__native_nat_subtract_to_int", "expects 'l' as Nat")
            val r = record.fields["r"] as? MezzeNat ?: throw NativeFunctionException("__native_nat_subtract_to_int", "expects 'r' as Nat")
            MezzeInt(l.value - r.value) // Returns Int, not Nat
        }

        context["__native_nat_add"] = NativeMezzeFunction("__native_nat_add") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeNat ?: throw NativeFunctionException("__native_nat_add", "expects 'l' as Nat")
            val r = record.fields["r"] as? MezzeNat ?: throw NativeFunctionException("__native_nat_add", "expects 'r' as Nat")
            MezzeNat(l.value + r.value)
        }

        context["__native_nat_subtract"] = NativeMezzeFunction("__native_nat_subtract") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeNat ?: throw NativeFunctionException("__native_nat_subtract", "expects 'l' as Nat")
            val r = record.fields["r"] as? MezzeNat ?: throw NativeFunctionException("__native_nat_subtract", "expects 'r' as Nat")
            if (l.value < r.value) throw MezzeException("Nat subtraction would result in negative value")
            MezzeNat(l.value - r.value)
        }

        context["__native_nat_multiply"] = NativeMezzeFunction("__native_nat_multiply") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeNat ?: throw NativeFunctionException("__native_nat_multiply", "expects 'l' as Nat")
            val r = record.fields["r"] as? MezzeNat ?: throw NativeFunctionException("__native_nat_multiply", "expects 'r' as Nat")
            MezzeNat(l.value * r.value)
        }

        context["__native_nat_divide"] = NativeMezzeFunction("__native_nat_divide") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeNat ?: throw NativeFunctionException("__native_nat_divide", "expects 'l' as Nat")
            val r = record.fields["r"] as? MezzeNat ?: throw NativeFunctionException("__native_nat_divide", "expects 'r' as Nat")
            if (r.value == 0L) throw MezzeException("Division by zero")
            MezzeNat(l.value / r.value)
        }

        context["__native_nat_modulo"] = NativeMezzeFunction("__native_nat_modulo") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeNat ?: throw NativeFunctionException("__native_nat_modulo", "expects 'l' as Nat")
            val r = record.fields["r"] as? MezzeNat ?: throw NativeFunctionException("__native_nat_modulo", "expects 'r' as Nat")
            if (r.value == 0L) throw MezzeException("Modulo by zero")
            MezzeNat(l.value % r.value)
        }

        context["__native_nat_compare"] = NativeMezzeFunction("__native_nat_compare") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeNat ?: throw NativeFunctionException("__native_nat_compare", "expects 'l' as Nat")
            val r = record.fields["r"] as? MezzeNat ?: throw NativeFunctionException("__native_nat_compare", "expects 'r' as Nat")
            val result = when {
                l.value < r.value -> "Less"
                l.value > r.value -> "Greater"
                else -> "Equal"
            }
            // TODO: Return proper variant instead of string
            MezzeString(result)
        }

        context["__native_nat_to_frac"] = NativeMezzeFunction("__native_nat_to_frac") { args ->
            val record = args[0] as MezzeRecord
            val n = record.fields["n"] as? MezzeNat ?: throw NativeFunctionException("__native_nat_to_frac", "expects 'n' as Nat")
            MezzeFrac(n.value, 1L)
        }
    }
}
