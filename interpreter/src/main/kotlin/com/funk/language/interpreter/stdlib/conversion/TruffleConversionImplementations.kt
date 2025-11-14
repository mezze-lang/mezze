package com.funk.language.interpreter.stdlib.conversion

import com.funk.language.interpreter.*

/**
 * Truffle runtime implementations for conversion abilities
 */
object TruffleConversionImplementations {
    
    /**
     * Register all conversion runtime implementations
     */
    fun registerAll(context: MutableMap<String, FunkValue>) {
        registerStringConversions(context)
        registerBooleanConversions(context)
        registerFloatConversions(context)
        registerNatConversions(context)
    }
    
    /**
     * String conversion operations
     */
    private fun registerStringConversions(context: MutableMap<String, FunkValue>) {
        // To string conversions
        context["intToString"] = NativeFunkFunction("intToString") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["n"] as? FunkInt ?: throw NativeFunctionException("intToString", "expects 'n' as Int")
            FunkString(value.value.toString())
        }

        // Hidden native version for int module
        context["__native_int_to_string"] = context["intToString"]!!

        // Convert String to Int (returns Option)
        context["__native_int_from_string"] = NativeFunkFunction("__native_int_from_string") { args ->
            val record = args[0] as FunkRecord
            val s = record.fields["s"] as? FunkString ?: throw NativeFunctionException("__native_int_from_string", "expects 's' as String")

            try {
                val intValue = s.value.toLong()
                // Some { val = int_value }
                FunkVariant("Some", FunkRecord(mapOf("val" to FunkInt(intValue))))
            } catch (e: NumberFormatException) {
                // None
                FunkVariant("None", null)
            }
        }

        // Convert Nat to Int
        context["__native_int_from_nat"] = NativeFunkFunction("__native_int_from_nat") { args ->
            val record = args[0] as FunkRecord
            val n = record.fields["n"] as? FunkNat ?: throw NativeFunctionException("__native_int_from_nat", "expects 'n' as Nat")
            FunkInt(n.value.toLong())
        }

        context["floatToString"] = NativeFunkFunction("floatToString") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["n"] as? FunkFloat ?: throw NativeFunctionException("floatToString", "expects 'n' as Float")
            FunkString(value.value.toString())
        }

        // Hidden native version for float module
        context["__native_float_to_string"] = context["floatToString"]!!

        context["fracToString"] = NativeFunkFunction("fracToString") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["val"] as? FunkFrac ?: throw NativeFunctionException("fracToString", "expects 'val' as Frac")
            FunkString("${value.numerator}/${value.denominator}")
        }
        
        context["natToString"] = NativeFunkFunction("natToString") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["val"] as? FunkNat ?: throw NativeFunctionException("natToString", "expects 'val' as Nat")
            FunkString(value.value.toString())
        }
        
        context["boolToString"] = NativeFunkFunction("boolToString") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["val"] as? FunkBool ?: throw NativeFunctionException("boolToString", "expects 'val' as Boolean")
            FunkString(value.value.toString())
        }
        
        // From string conversions
        context["stringToInt"] = NativeFunkFunction("stringToInt") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["val"] as? FunkString ?: throw NativeFunctionException("stringToInt", "expects 'val' as String")
            try {
                FunkInt(value.value.toLong())
            } catch (e: NumberFormatException) {
                throw FunkException("Invalid integer: ${value.value}")
            }
        }
        
        context["stringToFloat"] = NativeFunkFunction("stringToFloat") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["val"] as? FunkString ?: throw NativeFunctionException("stringToFloat", "expects 'val' as String")
            try {
                FunkFloat(value.value.toDouble())
            } catch (e: NumberFormatException) {
                throw FunkException("Invalid float: ${value.value}")
            }
        }
        
        context["stringToFrac"] = NativeFunkFunction("stringToFrac") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["val"] as? FunkString ?: throw NativeFunctionException("stringToFrac", "expects 'val' as String")
            
            try {
                val parts = value.value.split("/")
                if (parts.size == 2) {
                    val numerator = parts[0].toLong()
                    val denominator = parts[1].toLong()
                    if (denominator == 0L) {
                        throw FunkException("Fraction denominator cannot be zero")
                    }
                    FunkFrac(numerator, denominator).reduce()
                } else {
                    // Try to parse as integer and convert to fraction
                    val intValue = value.value.toLong()
                    FunkFrac(intValue, 1L)
                }
            } catch (e: NumberFormatException) {
                throw FunkException("Invalid fraction: ${value.value}")
            }
        }
        
        context["stringToBool"] = NativeFunkFunction("stringToBool") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["val"] as? FunkString ?: throw NativeFunctionException("stringToBool", "expects 'val' as String")
            
            when (value.value.lowercase()) {
                "true" -> FunkBool(true)
                "false" -> FunkBool(false)
                else -> throw FunkException("Invalid boolean: ${value.value}")
            }
        }
    }
    
    /**
     * Boolean conversion operations
     */
    private fun registerBooleanConversions(context: MutableMap<String, FunkValue>) {
        // Boolean to integer conversion
        context["boolToInt"] = NativeFunkFunction("boolToInt") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["val"] as? FunkBool ?: throw NativeFunctionException("boolToInt", "expects 'val' as Boolean")
            FunkInt(if (value.value) 1L else 0L)
        }
    }

    /**
     * Natural number conversion operations
     */
    private fun registerFloatConversions(context: MutableMap<String, FunkValue>) {
        // Convert Int to Float
        context["__native_float_from_int"] = NativeFunkFunction("__native_float_from_int") { args ->
            val record = args[0] as FunkRecord
            val x = record.fields["n"] as? FunkInt ?: throw NativeFunctionException("__native_float_from_int", "expects 'n' as Int")
            FunkFloat(x.value.toDouble())
        }

        // Convert Nat to Float
        context["__native_float_from_nat"] = NativeFunkFunction("__native_float_from_nat") { args ->
            val record = args[0] as FunkRecord
            val x = record.fields["n"] as? FunkNat ?: throw NativeFunctionException("__native_float_from_nat", "expects 'n' as Nat")
            FunkFloat(x.value.toDouble())
        }

        // Convert String to Float (returns Option)
        context["__native_float_from_string"] = NativeFunkFunction("__native_float_from_string") { args ->
            val record = args[0] as FunkRecord
            val s = record.fields["s"] as? FunkString ?: throw NativeFunctionException("__native_float_from_string", "expects 's' as String")

            try {
                val floatValue = s.value.toDouble()
                // Some { val = float_value }
                FunkVariant("Some", FunkRecord(mapOf("val" to FunkFloat(floatValue))))
            } catch (e: NumberFormatException) {
                // None
                FunkVariant("None", null)
            }
        }
    }

    private fun registerNatConversions(context: MutableMap<String, FunkValue>) {
        // Nat subtraction that returns Int (can be negative)
        context["__native_nat_subtract_to_int"] = NativeFunkFunction("__native_nat_subtract_to_int") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkNat ?: throw NativeFunctionException("__native_nat_subtract_to_int", "expects 'l' as Nat")
            val r = record.fields["r"] as? FunkNat ?: throw NativeFunctionException("__native_nat_subtract_to_int", "expects 'r' as Nat")
            // Convert to Int to allow negative results
            FunkInt(l.value.toLong() - r.value.toLong())
        }

        // Convert Int to Nat (return Option since negative ints can't be Nat)
        context["__native_nat_from_int"] = NativeFunkFunction("__native_nat_from_int") { args ->
            val record = args[0] as FunkRecord
            val x = record.fields["n"] as? FunkInt ?: throw NativeFunctionException("__native_nat_from_int", "expects 'n' as Int")

            if (x.value >= 0) {
                // Some { val = nat_value }
                FunkVariant("Some", FunkRecord(mapOf("val" to FunkNat(x.value))))
            } else {
                // None
                FunkVariant("None", null)
            }
        }

        // Convert Float to Nat (return Option since floats can be negative or fractional)
        context["__native_nat_from_float"] = NativeFunkFunction("__native_nat_from_float") { args ->
            val record = args[0] as FunkRecord
            val x = record.fields["n"] as? FunkFloat ?: throw NativeFunctionException("__native_nat_from_float", "expects 'n' as Float")

            if (x.value >= 0.0 && x.value.isFinite()) {
                val natValue = x.value.toLong()
                // Some { val = nat_value }
                FunkVariant("Some", FunkRecord(mapOf("val" to FunkNat(natValue))))
            } else {
                // None
                FunkVariant("None", null)
            }
        }

        // Convert String to Nat (return Option since string may not be valid nat)
        context["__native_nat_from_string"] = NativeFunkFunction("__native_nat_from_string") { args ->
            val record = args[0] as FunkRecord
            val s = record.fields["s"] as? FunkString ?: throw NativeFunctionException("__native_nat_from_string", "expects 's' as String")

            try {
                val natValue = s.value.toLong()
                if (natValue < 0) {
                    FunkVariant("None", null)
                } else {
                    // Some { val = nat_value }
                    FunkVariant("Some", FunkRecord(mapOf("val" to FunkNat(natValue))))
                }
            } catch (e: NumberFormatException) {
                // None
                FunkVariant("None", null)
            }
        }
    }
}
