package com.funk.language.interpreter.stdlib.logic

import com.funk.language.interpreter.*

/**
 * Truffle runtime implementations for logic abilities
 */
object TruffleLogicImplementations {
    
    /**
     * Register all logic runtime implementations
     */
    fun registerAll(context: MutableMap<String, FunkValue>) {
        registerBooleanOperations(context)
        registerComparisonOperations(context)
    }
    
    /**
     * Boolean logic operations
     */
    private fun registerBooleanOperations(context: MutableMap<String, FunkValue>) {
        // And operation
        context["and"] = NativeFunkFunction("and") { args ->
            val record = args[0] as FunkRecord
            val a = record.fields["a"] as? FunkBool ?: throw NativeFunctionException("and", "expects 'a' as Boolean")
            val b = record.fields["b"] as? FunkBool ?: throw NativeFunctionException("and", "expects 'b' as Boolean")
            FunkBool(a.value && b.value)
        }
        
        // Or operation
        context["or"] = NativeFunkFunction("or") { args ->
            val record = args[0] as FunkRecord
            val a = record.fields["a"] as? FunkBool ?: throw NativeFunctionException("or", "expects 'a' as Boolean")
            val b = record.fields["b"] as? FunkBool ?: throw NativeFunctionException("or", "expects 'b' as Boolean")
            FunkBool(a.value || b.value)
        }
        
        // Not operation
        context["not"] = NativeFunkFunction("not") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["val"] as? FunkBool ?: throw NativeFunctionException("not", "expects 'val' as Boolean")
            FunkBool(!value.value)
        }
        
        // Keep backward compatibility
        context["boolAnd"] = context["and"]!!
        context["boolOr"] = context["or"]!!
        context["boolNot"] = context["not"]!!
    }
    
    /**
     * Comparison operations for strings and additional types
     */
    private fun registerComparisonOperations(context: MutableMap<String, FunkValue>) {
        // String equality operations
        context["equal_string"] = NativeFunkFunction("equal_string") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["left"] as? FunkString ?: throw NativeFunctionException("equal_string", "expects 'left' as String")
            val right = record.fields["right"] as? FunkString ?: throw NativeFunctionException("equal_string", "expects 'right' as String")
            FunkBool(left.value == right.value)
        }

        context["notEqual_string"] = NativeFunkFunction("notEqual_string") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["left"] as? FunkString ?: throw NativeFunctionException("notEqual_string", "expects 'left' as String")
            val right = record.fields["right"] as? FunkString ?: throw NativeFunctionException("notEqual_string", "expects 'right' as String")
            FunkBool(left.value != right.value)
        }
        
        // Additional numeric comparison operations (complement the ones in numeric module)
        listOf("int", "float", "frac", "nat").forEach { type ->
            context["notEqual_$type"] = NativeFunkFunction("notEqual_$type") { args ->
                val record = args[0] as FunkRecord
                
                when (type) {
                    "int" -> {
                        val left = record.fields["left"] as? FunkInt ?: throw NativeFunctionException("notEqual_int", "expects 'left' as Int")
                        val right = record.fields["right"] as? FunkInt ?: throw NativeFunctionException("notEqual_int", "expects 'right' as Int")
                        FunkBool(left.value != right.value)
                    }
                    "float" -> {
                        val left = record.fields["left"] as? FunkFloat ?: throw NativeFunctionException("notEqual_float", "expects 'left' as Float")
                        val right = record.fields["right"] as? FunkFloat ?: throw NativeFunctionException("notEqual_float", "expects 'right' as Float")
                        FunkBool(left.value != right.value)
                    }
                    "frac" -> {
                        val left = record.fields["left"] as? FunkFrac ?: throw NativeFunctionException("notEqual_frac", "expects 'left' as Frac")
                        val right = record.fields["right"] as? FunkFrac ?: throw NativeFunctionException("notEqual_frac", "expects 'right' as Frac")
                        FunkBool(left.numerator * right.denominator != right.numerator * left.denominator)
                    }
                    "nat" -> {
                        val left = record.fields["left"] as? FunkNat ?: throw NativeFunctionException("notEqual_nat", "expects 'left' as Nat")
                        val right = record.fields["right"] as? FunkNat ?: throw NativeFunctionException("notEqual_nat", "expects 'right' as Nat")
                        FunkBool(left.value != right.value)
                    }
                    else -> throw NativeFunctionException("notEqual_$type", "Unknown type: $type")
                }
            }
            
            context["lessThanOrEqual_$type"] = NativeFunkFunction("lessThanOrEqual_$type") { args ->
                val record = args[0] as FunkRecord

                when (type) {
                    "int" -> {
                        val left = record.fields["left"] as? FunkInt ?: throw NativeFunctionException("lessThanOrEqual_int", "expects 'left' as Int")
                        val right = record.fields["right"] as? FunkInt ?: throw NativeFunctionException("lessThanOrEqual_int", "expects 'right' as Int")
                        FunkBool(left.value <= right.value)
                    }
                    "float" -> {
                        val left = record.fields["left"] as? FunkFloat ?: throw NativeFunctionException("lessThanOrEqual_float", "expects 'left' as Float")
                        val right = record.fields["right"] as? FunkFloat ?: throw NativeFunctionException("lessThanOrEqual_float", "expects 'right' as Float")
                        FunkBool(left.value <= right.value)
                    }
                    "frac" -> {
                        val left = record.fields["left"] as? FunkFrac ?: throw NativeFunctionException("lessThanOrEqual_frac", "expects 'left' as Frac")
                        val right = record.fields["right"] as? FunkFrac ?: throw NativeFunctionException("lessThanOrEqual_frac", "expects 'right' as Frac")
                        FunkBool(left.numerator * right.denominator <= right.numerator * left.denominator)
                    }
                    "nat" -> {
                        val left = record.fields["left"] as? FunkNat ?: throw NativeFunctionException("lessThanOrEqual_nat", "expects 'left' as Nat")
                        val right = record.fields["right"] as? FunkNat ?: throw NativeFunctionException("lessThanOrEqual_nat", "expects 'right' as Nat")
                        FunkBool(left.value <= right.value)
                    }
                    else -> throw NativeFunctionException("lessThanOrEqual_$type", "Unknown type: $type")
                }
            }
            
            context["greaterThanOrEqual_$type"] = NativeFunkFunction("greaterThanOrEqual_$type") { args ->
                val record = args[0] as FunkRecord

                when (type) {
                    "int" -> {
                        val left = record.fields["left"] as? FunkInt ?: throw NativeFunctionException("greaterThanOrEqual_int", "expects 'left' as Int")
                        val right = record.fields["right"] as? FunkInt ?: throw NativeFunctionException("greaterThanOrEqual_int", "expects 'right' as Int")
                        FunkBool(left.value >= right.value)
                    }
                    "float" -> {
                        val left = record.fields["left"] as? FunkFloat ?: throw NativeFunctionException("greaterThanOrEqual_float", "expects 'left' as Float")
                        val right = record.fields["right"] as? FunkFloat ?: throw NativeFunctionException("greaterThanOrEqual_float", "expects 'right' as Float")
                        FunkBool(left.value >= right.value)
                    }
                    "frac" -> {
                        val left = record.fields["left"] as? FunkFrac ?: throw NativeFunctionException("greaterThanOrEqual_frac", "expects 'left' as Frac")
                        val right = record.fields["right"] as? FunkFrac ?: throw NativeFunctionException("greaterThanOrEqual_frac", "expects 'right' as Frac")
                        FunkBool(left.numerator * right.denominator >= right.numerator * left.denominator)
                    }
                    "nat" -> {
                        val left = record.fields["left"] as? FunkNat ?: throw NativeFunctionException("greaterThanOrEqual_nat", "expects 'left' as Nat")
                        val right = record.fields["right"] as? FunkNat ?: throw NativeFunctionException("greaterThanOrEqual_nat", "expects 'right' as Nat")
                        FunkBool(left.value >= right.value)
                    }
                    else -> throw NativeFunctionException("greaterThanOrEqual_$type", "Unknown type: $type")
                }
            }
        }
    }
}
