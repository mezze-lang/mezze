package com.mezze.language.interpreter.stdlib.conversion

import com.mezze.language.interpreter.*

/**
 * Truffle-optimized implementations for type conversion operations.
 *
 * Handles conversions between all basic types: Int, Float, Frac, Nat, Bool, String
 *
 * All functions use the __native_ prefix and are called from stdlib .mz files.
 */
object TruffleConversionImplementations {

    /**
     * Register all conversion runtime implementations
     */
    fun registerAll(context: MutableMap<String, MezzeValue>) {
        registerIntConversions(context)
        registerFloatConversions(context)
        registerNatConversions(context)
    }

    /**
     * Int conversion operations
     */
    private fun registerIntConversions(context: MutableMap<String, MezzeValue>) {
        // Convert Int to String
        context["__native_int_to_string"] = NativeMezzeFunction("__native_int_to_string") { args ->
            val record = args[0] as MezzeRecord
            val value = record.fields["n"] as? MezzeInt ?: throw NativeFunctionException("__native_int_to_string", "expects 'n' as Int")
            MezzeString(value.value.toString())
        }

        // Convert String to Int (returns Option)
        context["__native_int_from_string"] = NativeMezzeFunction("__native_int_from_string") { args ->
            val record = args[0] as MezzeRecord
            val s = record.fields["s"] as? MezzeString ?: throw NativeFunctionException("__native_int_from_string", "expects 's' as String")

            try {
                val intValue = s.value.toLong()
                mezzeOptionSome(MezzeInt(intValue))
            } catch (e: NumberFormatException) {
                mezzeOptionNone()
            }
        }

        // Convert Nat to Int
        context["__native_int_from_nat"] = NativeMezzeFunction("__native_int_from_nat") { args ->
            val record = args[0] as MezzeRecord
            val n = record.fields["n"] as? MezzeNat ?: throw NativeFunctionException("__native_int_from_nat", "expects 'n' as Nat")
            MezzeInt(n.value.toLong())
        }

        // Convert Int to Float
        context["__native_int_to_float"] = NativeMezzeFunction("__native_int_to_float") { args ->
            val record = args[0] as MezzeRecord
            val n = record.fields["n"] as? MezzeInt ?: throw NativeFunctionException("__native_int_to_float", "expects 'n' as Int")
            MezzeFloat(n.value.toDouble())
        }
    }

    /**
     * Float conversion operations
     */
    private fun registerFloatConversions(context: MutableMap<String, MezzeValue>) {
        // Convert Float to String
        context["__native_float_to_string"] = NativeMezzeFunction("__native_float_to_string") { args ->
            val record = args[0] as MezzeRecord
            val value = record.fields["n"] as? MezzeFloat ?: throw NativeFunctionException("__native_float_to_string", "expects 'n' as Float")
            MezzeString(value.value.toString())
        }

        // Convert Int to Float
        context["__native_float_from_int"] = NativeMezzeFunction("__native_float_from_int") { args ->
            val record = args[0] as MezzeRecord
            val x = record.fields["n"] as? MezzeInt ?: throw NativeFunctionException("__native_float_from_int", "expects 'n' as Int")
            MezzeFloat(x.value.toDouble())
        }

        // Convert Nat to Float
        context["__native_float_from_nat"] = NativeMezzeFunction("__native_float_from_nat") { args ->
            val record = args[0] as MezzeRecord
            val x = record.fields["n"] as? MezzeNat ?: throw NativeFunctionException("__native_float_from_nat", "expects 'n' as Nat")
            MezzeFloat(x.value.toDouble())
        }

        // Convert String to Float (returns Option)
        context["__native_float_from_string"] = NativeMezzeFunction("__native_float_from_string") { args ->
            val record = args[0] as MezzeRecord
            val s = record.fields["s"] as? MezzeString ?: throw NativeFunctionException("__native_float_from_string", "expects 's' as String")

            try {
                val floatValue = s.value.toDouble()
                mezzeOptionSome(MezzeFloat(floatValue))
            } catch (e: NumberFormatException) {
                mezzeOptionNone()
            }
        }
    }

    /**
     * Natural number conversion operations
     */
    private fun registerNatConversions(context: MutableMap<String, MezzeValue>) {
        // Convert Int to Nat (returns Option since negative ints can't be Nat)
        context["__native_nat_from_int"] = NativeMezzeFunction("__native_nat_from_int") { args ->
            val record = args[0] as MezzeRecord
            val x = record.fields["n"] as? MezzeInt ?: throw NativeFunctionException("__native_nat_from_int", "expects 'n' as Int")

            if (x.value >= 0) {
                mezzeOptionSome(MezzeNat(x.value))
            } else {
                mezzeOptionNone()
            }
        }

        // Convert Float to Nat (returns Option since floats can be negative or fractional)
        context["__native_nat_from_float"] = NativeMezzeFunction("__native_nat_from_float") { args ->
            val record = args[0] as MezzeRecord
            val x = record.fields["n"] as? MezzeFloat ?: throw NativeFunctionException("__native_nat_from_float", "expects 'n' as Float")

            if (x.value >= 0.0 && x.value.isFinite()) {
                val natValue = x.value.toLong()
                mezzeOptionSome(MezzeNat(natValue))
            } else {
                mezzeOptionNone()
            }
        }

        // Convert String to Nat (returns Option since string may not be valid nat)
        context["__native_nat_from_string"] = NativeMezzeFunction("__native_nat_from_string") { args ->
            val record = args[0] as MezzeRecord
            val s = record.fields["s"] as? MezzeString ?: throw NativeFunctionException("__native_nat_from_string", "expects 's' as String")

            try {
                val natValue = s.value.toLong()
                if (natValue < 0) {
                    mezzeOptionNone()
                } else {
                    mezzeOptionSome(MezzeNat(natValue))
                }
            } catch (e: NumberFormatException) {
                mezzeOptionNone()
            }
        }
    }
}
