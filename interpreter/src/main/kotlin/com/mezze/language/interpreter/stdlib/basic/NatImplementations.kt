package com.mezze.language.interpreter.stdlib.basic

import com.mezze.language.interpreter.*

/**
 * Truffle-optimized implementations for Nat type operations.
 *
 * Note: Most nat operations are handled elsewhere:
 * - Arithmetic operations: handled by Truffle nodes
 * - Comparison operations: handled by Truffle nodes
 * - Conversion operations: TruffleConversionImplementations.kt
 * - Math operations: MathCoreImplementations.kt (__native_nat_pow)
 */
object NatImplementations {

    /**
     * Register all Nat runtime implementations
     */
    fun registerAll(context: MutableMap<String, MezzeValue>) {
        // Special case: Nat - Nat = Int (result might be negative)
        context["__native_nat_subtract_to_int"] = NativeMezzeFunction("__native_nat_subtract_to_int") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeNat ?: throw NativeFunctionException("__native_nat_subtract_to_int", "expects 'l' as Nat")
            val r = record.fields["r"] as? MezzeNat ?: throw NativeFunctionException("__native_nat_subtract_to_int", "expects 'r' as Nat")
            MezzeInt(l.value - r.value) // Returns Int, not Nat
        }

        context["__native_nat_to_frac"] = NativeMezzeFunction("__native_nat_to_frac") { args ->
            val record = args[0] as MezzeRecord
            val n = record.fields["n"] as? MezzeNat ?: throw NativeFunctionException("__native_nat_to_frac", "expects 'n' as Nat")
            MezzeFrac(n.value, 1L)
        }

        context["__native_nat_min"] = NativeMezzeFunction("__native_nat_min") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeNat ?: throw NativeFunctionException("__native_nat_min", "expects 'l' as Nat")
            val r = record.fields["r"] as? MezzeNat ?: throw NativeFunctionException("__native_nat_min", "expects 'r' as Nat")
            MezzeNat(kotlin.math.min(l.value, r.value))
        }

        context["__native_nat_max"] = NativeMezzeFunction("__native_nat_max") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeNat ?: throw NativeFunctionException("__native_nat_max", "expects 'l' as Nat")
            val r = record.fields["r"] as? MezzeNat ?: throw NativeFunctionException("__native_nat_max", "expects 'r' as Nat")
            MezzeNat(kotlin.math.max(l.value, r.value))
        }
    }
}
