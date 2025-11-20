package com.funk.language.interpreter.stdlib.numeric

import com.funk.language.interpreter.*

/**
 * Truffle runtime implementations for numeric abilities
 * These are the actual functions that execute at runtime
 */
object TruffleNumericImplementations {
    
    /**
     * Register all numeric runtime implementations
     */
    fun registerAll(context: MutableMap<String, FunkValue>) {
        registerArithmeticOperations(context)
        registerComparisonOperations(context)
        registerNumericConversions(context)
        registerHiddenNativeOperations(context)
    }
    
    /**
     * Arithmetic operations: add, subtract, multiply, divide
     */
    private fun registerArithmeticOperations(context: MutableMap<String, FunkValue>) {
        // Addition operations
        context["add_int"] = NativeFunkFunction("add_int") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkInt ?: throw NativeFunctionException("add_int", "expects 'l' as Int")
            val r = record.fields["r"] as? FunkInt ?: throw NativeFunctionException("add_int", "expects 'r' as Int")
            FunkInt(l.value + r.value)
        }
        
        context["add_float"] = NativeFunkFunction("add_float") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkFloat ?: throw NativeFunctionException("add_float", "expects 'l' as Float")
            val r = record.fields["r"] as? FunkFloat ?: throw NativeFunctionException("add_float", "expects 'r' as Float")
            FunkFloat(l.value + r.value)
        }
        
        context["add_frac"] = NativeFunkFunction("add_frac") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkFrac ?: throw NativeFunctionException("add_frac", "expects 'left' as Frac")
            val r = record.fields["r"] as? FunkFrac ?: throw NativeFunctionException("add_frac", "expects 'right' as Frac")

            // a/b + c/d = (a*d + c*b) / (b*d)
            val numerator = l.numerator * r.denominator + r.numerator * l.denominator
            val denominator = l.denominator * r.denominator
            FunkFrac(numerator, denominator).reduce()
        }
        
        context["add_nat"] = NativeFunkFunction("add_nat") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkNat ?: throw NativeFunctionException("add_nat", "expects 'l' as Nat")
            val r = record.fields["r"] as? FunkNat ?: throw NativeFunctionException("add_nat", "expects 'r' as Nat")
            FunkNat(l.value + r.value)
        }
        
        // Subtraction operations
        context["subtract_int"] = NativeFunkFunction("subtract_int") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkInt ?: throw NativeFunctionException("subtract_int", "expects 'l' as Int")
            val r = record.fields["r"] as? FunkInt ?: throw NativeFunctionException("subtract_int", "expects 'r' as Int")
            FunkInt(l.value - r.value)
        }
        
        context["subtract_float"] = NativeFunkFunction("subtract_float") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkFloat ?: throw NativeFunctionException("subtract_float", "expects 'l' as Float")
            val r = record.fields["r"] as? FunkFloat ?: throw NativeFunctionException("subtract_float", "expects 'r' as Float")
            FunkFloat(l.value - r.value)
        }
        
        context["subtract_frac"] = NativeFunkFunction("subtract_frac") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkFrac ?: throw NativeFunctionException("subtract_frac", "expects 'left' as Frac")
            val r = record.fields["r"] as? FunkFrac ?: throw NativeFunctionException("subtract_frac", "expects 'right' as Frac")

            // a/b - c/d = (a*d - c*b) / (b*d)
            val numerator = l.numerator * r.denominator - r.numerator * l.denominator
            val denominator = l.denominator * r.denominator
            FunkFrac(numerator, denominator).reduce()
        }
        
        // Special case: Nat - Nat = Int (result might be negative)
        context["subtract_nat"] = NativeFunkFunction("subtract_nat") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["left"] as? FunkNat ?: throw NativeFunctionException("subtract_nat", "expects 'left' as Nat")
            val right = record.fields["right"] as? FunkNat ?: throw NativeFunctionException("subtract_nat", "expects 'right' as Nat")
            FunkInt(left.value - right.value) // Returns Int, not Nat
        }
        
        // Multiplication operations
        context["multiply_int"] = NativeFunkFunction("multiply_int") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["left"] as? FunkInt ?: throw NativeFunctionException("multiply_int", "expects 'left' as Int")
            val right = record.fields["right"] as? FunkInt ?: throw NativeFunctionException("multiply_int", "expects 'right' as Int")
            FunkInt(left.value * right.value)
        }
        
        context["multiply_float"] = NativeFunkFunction("multiply_float") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["left"] as? FunkFloat ?: throw NativeFunctionException("multiply_float", "expects 'left' as Float")
            val right = record.fields["right"] as? FunkFloat ?: throw NativeFunctionException("multiply_float", "expects 'right' as Float")
            FunkFloat(left.value * right.value)
        }
        
        context["multiply_frac"] = NativeFunkFunction("multiply_frac") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkFrac ?: throw NativeFunctionException("multiply_frac", "expects 'left' as Frac")
            val r = record.fields["r"] as? FunkFrac ?: throw NativeFunctionException("multiply_frac", "expects 'right' as Frac")
            
            // a/b * c/d = (a*c) / (b*d)
            val numerator = l.numerator * r.numerator
            val denominator = l.denominator * r.denominator
            FunkFrac(numerator, denominator).reduce()
        }
        
        context["multiply_nat"] = NativeFunkFunction("multiply_nat") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["left"] as? FunkNat ?: throw NativeFunctionException("multiply_nat", "expects 'left' as Nat")
            val right = record.fields["right"] as? FunkNat ?: throw NativeFunctionException("multiply_nat", "expects 'right' as Nat")
            FunkNat(left.value * right.value)
        }
        
        // Division operations
        context["divide_int"] = NativeFunkFunction("divide_int") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["left"] as? FunkInt ?: throw NativeFunctionException("divide_int", "expects 'left' as Int")
            val right = record.fields["right"] as? FunkInt ?: throw NativeFunctionException("divide_int", "expects 'right' as Int")
            if (right.value == 0L) throw FunkException("Division by zero")
            FunkInt(left.value / right.value)
        }
        
        context["divide_float"] = NativeFunkFunction("divide_float") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["left"] as? FunkFloat ?: throw NativeFunctionException("divide_float", "expects 'left' as Float")
            val right = record.fields["right"] as? FunkFloat ?: throw NativeFunctionException("divide_float", "expects 'right' as Float")
            if (right.value == 0.0) throw FunkException("Division by zero")
            FunkFloat(left.value / right.value)
        }
        
        context["divide_frac"] = NativeFunkFunction("divide_frac") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkFrac ?: throw NativeFunctionException("divide_frac", "expects 'left' as Frac")
            val r = record.fields["r"] as? FunkFrac ?: throw NativeFunctionException("divide_frac", "expects 'right' as Frac")
            
            if (r.numerator == 0L) throw FunkException("Division by zero")
            
            // a/b ÷ c/d = a/b * d/c = (a*d) / (b*c)
            val numerator = l.numerator * r.denominator
            val denominator = l.denominator * r.numerator
            FunkFrac(numerator, denominator).reduce()
        }
        
        context["divide_nat"] = NativeFunkFunction("divide_nat") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["left"] as? FunkNat ?: throw NativeFunctionException("divide_nat", "expects 'left' as Nat")
            val right = record.fields["right"] as? FunkNat ?: throw NativeFunctionException("divide_nat", "expects 'right' as Nat")
            if (right.value == 0L) throw FunkException("Division by zero")
            FunkNat(left.value / right.value)
        }
        
        // Modulo operations
        context["modulo_int"] = NativeFunkFunction("modulo_int") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["left"] as? FunkInt ?: throw NativeFunctionException("modulo_int", "expects 'left' as Int")
            val right = record.fields["right"] as? FunkInt ?: throw NativeFunctionException("modulo_int", "expects 'right' as Int")
            if (right.value == 0L) throw FunkException("Modulo by zero")
            FunkInt(left.value % right.value)
        }
        
        context["modulo_float"] = NativeFunkFunction("modulo_float") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["left"] as? FunkFloat ?: throw NativeFunctionException("modulo_float", "expects 'left' as Float")
            val right = record.fields["right"] as? FunkFloat ?: throw NativeFunctionException("modulo_float", "expects 'right' as Float")
            if (right.value == 0.0) throw FunkException("Modulo by zero")
            FunkFloat(left.value % right.value)
        }
        
        context["modulo_nat"] = NativeFunkFunction("modulo_nat") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["left"] as? FunkNat ?: throw NativeFunctionException("modulo_nat", "expects 'left' as Nat")
            val right = record.fields["right"] as? FunkNat ?: throw NativeFunctionException("modulo_nat", "expects 'right' as Nat")
            if (right.value == 0L) throw FunkException("Modulo by zero")
            FunkNat(left.value % right.value)
        }
    }
    
    /**
     * Comparison operations: equal, lessThan, greaterThan, etc.
     */
    private fun registerComparisonOperations(context: MutableMap<String, FunkValue>) {
        // Equality operations
        context["equal_int"] = NativeFunkFunction("equal_int") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["left"] as? FunkInt ?: throw NativeFunctionException("equal_int", "expects 'left' as Int")
            val right = record.fields["right"] as? FunkInt ?: throw NativeFunctionException("equal_int", "expects 'right' as Int")
            FunkBool(left.value == right.value)
        }
        
        context["equal_float"] = NativeFunkFunction("equal_float") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["left"] as? FunkFloat ?: throw NativeFunctionException("equal_float", "expects 'left' as Float")
            val right = record.fields["right"] as? FunkFloat ?: throw NativeFunctionException("equal_float", "expects 'right' as Float")
            FunkBool(left.value == right.value)
        }
        
        context["equal_frac"] = NativeFunkFunction("equal_frac") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkFrac ?: throw NativeFunctionException("equal_frac", "expects 'left' as Frac")
            val r = record.fields["r"] as? FunkFrac ?: throw NativeFunctionException("equal_frac", "expects 'right' as Frac")
            
            // Cross multiply: a/b == c/d iff a*d == b*c
            FunkBool(l.numerator * r.denominator == r.numerator * l.denominator)
        }
        
        context["equal_nat"] = NativeFunkFunction("equal_nat") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["left"] as? FunkNat ?: throw NativeFunctionException("equal_nat", "expects 'left' as Nat")
            val right = record.fields["right"] as? FunkNat ?: throw NativeFunctionException("equal_nat", "expects 'right' as Nat")
            FunkBool(left.value == right.value)
        }
        
        // Less than operations
        context["lessThan_int"] = NativeFunkFunction("lessThan_int") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["left"] as? FunkInt ?: throw NativeFunctionException("lessThan_int", "expects 'left' as Int")
            val right = record.fields["right"] as? FunkInt ?: throw NativeFunctionException("lessThan_int", "expects 'right' as Int")
            FunkBool(left.value < right.value)
        }
        
        context["lessThan_float"] = NativeFunkFunction("lessThan_float") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["left"] as? FunkFloat ?: throw NativeFunctionException("lessThan_float", "expects 'left' as Float")
            val right = record.fields["right"] as? FunkFloat ?: throw NativeFunctionException("lessThan_float", "expects 'right' as Float")
            FunkBool(left.value < right.value)
        }
        
        context["lessThan_frac"] = NativeFunkFunction("lessThan_frac") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkFrac ?: throw NativeFunctionException("lessThan_frac", "expects 'left' as Frac")
            val r = record.fields["r"] as? FunkFrac ?: throw NativeFunctionException("lessThan_frac", "expects 'right' as Frac")
            
            // Cross multiply: a/b < c/d iff a*d < c*b (assuming positive denominators)
            FunkBool(l.numerator * r.denominator < r.numerator * l.denominator)
        }
        
        context["lessThan_nat"] = NativeFunkFunction("lessThan_nat") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["left"] as? FunkNat ?: throw NativeFunctionException("lessThan_nat", "expects 'left' as Nat")
            val right = record.fields["right"] as? FunkNat ?: throw NativeFunctionException("lessThan_nat", "expects 'right' as Nat")
            FunkBool(left.value < right.value)
        }
        
        // Greater than operations
        context["greaterThan_int"] = NativeFunkFunction("greaterThan_int") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["left"] as? FunkInt ?: throw NativeFunctionException("greaterThan_int", "expects 'left' as Int")
            val right = record.fields["right"] as? FunkInt ?: throw NativeFunctionException("greaterThan_int", "expects 'right' as Int")
            FunkBool(left.value > right.value)
        }
        
        context["greaterThan_float"] = NativeFunkFunction("greaterThan_float") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["left"] as? FunkFloat ?: throw NativeFunctionException("greaterThan_float", "expects 'left' as Float")
            val right = record.fields["right"] as? FunkFloat ?: throw NativeFunctionException("greaterThan_float", "expects 'right' as Float")
            FunkBool(left.value > right.value)
        }
        
        context["greaterThan_frac"] = NativeFunkFunction("greaterThan_frac") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkFrac ?: throw NativeFunctionException("greaterThan_frac", "expects 'left' as Frac")
            val r = record.fields["r"] as? FunkFrac ?: throw NativeFunctionException("greaterThan_frac", "expects 'right' as Frac")
            
            // Cross multiply: a/b > c/d iff a*d > c*b (assuming positive denominators)
            FunkBool(l.numerator * r.denominator > r.numerator * l.denominator)
        }
        
        context["greaterThan_nat"] = NativeFunkFunction("greaterThan_nat") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["left"] as? FunkNat ?: throw NativeFunctionException("greaterThan_nat", "expects 'left' as Nat")
            val right = record.fields["right"] as? FunkNat ?: throw NativeFunctionException("greaterThan_nat", "expects 'right' as Nat")
            FunkBool(left.value > right.value)
        }
    }
    
    /**
     * Numeric conversion operations
     */
    private fun registerNumericConversions(context: MutableMap<String, FunkValue>) {
        // Int conversions
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
        
        // Float conversions
        context["floatToInt"] = NativeFunkFunction("floatToInt") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["val"] as? FunkFloat ?: throw NativeFunctionException("floatToInt", "expects 'val' as Float")
            FunkInt(value.value.toLong())
        }
        
        context["floatToFrac"] = NativeFunkFunction("floatToFrac") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["val"] as? FunkFloat ?: throw NativeFunctionException("floatToFrac", "expects 'val' as Float")
            
            // Simple approximation - convert to decimal and then to fraction
            val denominator = 1000000L
            val numerator = (value.value * denominator).toLong()
            FunkFrac(numerator, denominator).reduce()
        }
        
        // Frac conversions
        context["fracToInt"] = NativeFunkFunction("fracToInt") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["val"] as? FunkFrac ?: throw NativeFunctionException("fracToInt", "expects 'val' as Frac")
            FunkInt(value.numerator / value.denominator)
        }
        
        context["fracToFloat"] = NativeFunkFunction("fracToFloat") { args ->
            val record = args[0] as FunkRecord
            val n = record.fields["n"] as? FunkFrac ?: throw NativeFunctionException("fracToFloat", "expects 'frac' as Frac")
            FunkFloat(n.numerator.toDouble() / n.denominator.toDouble())
        }

        context["fracToString"] = NativeFunkFunction("fracToString") { args ->
            val record = args[0] as FunkRecord
            val n = record.fields["n"] as? FunkFrac ?: throw NativeFunctionException("fracToString", "expects 'frac' as Frac")
            FunkString("${n.numerator}/${n.denominator}")
        }

        // Nat conversions
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
    }
    
    /**
     * Hidden native operations (only accessible through abilities)
     */
    private fun registerHiddenNativeOperations(context: MutableMap<String, FunkValue>) {
        // Hidden Int operations
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
        
        // Hidden Float operations
        context["__native_float_equals"] = NativeFunkFunction("__native_float_equals") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkFloat ?: throw NativeFunctionException("__native_float_equals", "expects 'l' as Float")
            val r = record.fields["r"] as? FunkFloat ?: throw NativeFunctionException("__native_float_equals", "expects 'r' as Float")
            FunkBool(l.value == r.value)
        }
        
        context["__native_float_less_than"] = NativeFunkFunction("__native_float_less_than") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkFloat ?: throw NativeFunctionException("__native_float_less_than", "expects 'l' as Float")
            val r = record.fields["r"] as? FunkFloat ?: throw NativeFunctionException("__native_float_less_than", "expects 'r' as Float")
            FunkBool(l.value < r.value)
        }
        
        context["__native_float_greater_than"] = NativeFunkFunction("__native_float_greater_than") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkFloat ?: throw NativeFunctionException("__native_float_greater_than", "expects 'l' as Float")
            val r = record.fields["r"] as? FunkFloat ?: throw NativeFunctionException("__native_float_greater_than", "expects 'r' as Float")
            FunkBool(l.value > r.value)
        }
        
        context["__native_float_to_string"] = NativeFunkFunction("__native_float_to_string") { args ->
            val record = args[0] as FunkRecord
            val n = record.fields["n"] as? FunkFloat ?: throw NativeFunctionException("__native_float_to_string", "expects 'n' as Float")
            FunkString(n.value.toString())
        }
        
        // Hidden String operations
        context["__native_string_equals"] = NativeFunkFunction("__native_string_equals") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkString ?: throw NativeFunctionException("__native_string_equals", "expects 'l' as String")
            val r = record.fields["r"] as? FunkString ?: throw NativeFunctionException("__native_string_equals", "expects 'r' as String")
            FunkBool(l.value == r.value)
        }
        
        context["__native_string_less_than"] = NativeFunkFunction("__native_string_less_than") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkString ?: throw NativeFunctionException("__native_string_less_than", "expects 'l' as String")
            val r = record.fields["r"] as? FunkString ?: throw NativeFunctionException("__native_string_less_than", "expects 'r' as String")
            FunkBool(l.value < r.value)
        }
        
        context["__native_string_greater_than"] = NativeFunkFunction("__native_string_greater_than") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkString ?: throw NativeFunctionException("__native_string_greater_than", "expects 'l' as String")
            val r = record.fields["r"] as? FunkString ?: throw NativeFunctionException("__native_string_greater_than", "expects 'r' as String")
            FunkBool(l.value > r.value)
        }
        
        // Hidden Boolean operations
        context["__native_boolean_equals"] = NativeFunkFunction("__native_boolean_equals") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkBool ?: throw NativeFunctionException("__native_boolean_equals", "expects 'l' as Boolean")
            val r = record.fields["r"] as? FunkBool ?: throw NativeFunctionException("__native_boolean_equals", "expects 'r' as Boolean")
            FunkBool(l.value == r.value)
        }
        
        context["__native_boolean_to_string"] = NativeFunkFunction("__native_boolean_to_string") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["value"] as? FunkBool ?: throw NativeFunctionException("__native_boolean_to_string", "expects 'value' as Boolean")
            FunkString(value.value.toString())
        }
        
        // Hidden Nat operations
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
        
        // Additional hidden native functions for primitive implementations
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
        
        context["__native_float_add"] = NativeFunkFunction("__native_float_add") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["l"] as? FunkFloat ?: throw NativeFunctionException("__native_float_add", "expects 'l' as Float")
            val right = record.fields["r"] as? FunkFloat ?: throw NativeFunctionException("__native_float_add", "expects 'r' as Float")
            FunkFloat(left.value + right.value)
        }

        context["__native_float_subtract"] = NativeFunkFunction("__native_float_subtract") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["l"] as? FunkFloat ?: throw NativeFunctionException("__native_float_subtract", "expects 'l' as Float")
            val right = record.fields["r"] as? FunkFloat ?: throw NativeFunctionException("__native_float_subtract", "expects 'r' as Float")
            FunkFloat(left.value - right.value)
        }

        context["__native_float_multiply"] = NativeFunkFunction("__native_float_multiply") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["l"] as? FunkFloat ?: throw NativeFunctionException("__native_float_multiply", "expects 'l' as Float")
            val right = record.fields["r"] as? FunkFloat ?: throw NativeFunctionException("__native_float_multiply", "expects 'r' as Float")
            FunkFloat(left.value * right.value)
        }

        context["__native_float_divide"] = NativeFunkFunction("__native_float_divide") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["l"] as? FunkFloat ?: throw NativeFunctionException("__native_float_divide", "expects 'l' as Float")
            val right = record.fields["r"] as? FunkFloat ?: throw NativeFunctionException("__native_float_divide", "expects 'r' as Float")
            if (right.value == 0.0) throw FunkException("Division by zero")
            FunkFloat(left.value / right.value)
        }

        context["__native_float_modulo"] = NativeFunkFunction("__native_float_modulo") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["l"] as? FunkFloat ?: throw NativeFunctionException("__native_float_modulo", "expects 'l' as Float")
            val right = record.fields["r"] as? FunkFloat ?: throw NativeFunctionException("__native_float_modulo", "expects 'r' as Float")
            if (right.value == 0.0) throw FunkException("Modulo by zero")
            FunkFloat(left.value % right.value)
        }

        context["__native_float_compare"] = NativeFunkFunction("__native_float_compare") { args ->
            val record = args[0] as FunkRecord
            val left = record.fields["l"] as? FunkFloat ?: throw NativeFunctionException("__native_float_compare", "expects 'l' as Float")
            val right = record.fields["r"] as? FunkFloat ?: throw NativeFunctionException("__native_float_compare", "expects 'r' as Float")
            val result = when {
                left.value < right.value -> "Less"
                left.value > right.value -> "Greater"
                else -> "Equal"
            }
            // TODO: Return proper variant instead of string
            FunkString(result)
        }
        
        context["__native_string_compare"] = NativeFunkFunction("__native_string_compare") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkString ?: throw NativeFunctionException("__native_string_compare", "expects 'l' as String")
            val r = record.fields["r"] as? FunkString ?: throw NativeFunctionException("__native_string_compare", "expects 'r' as String")
            val result = when {
                l.value < r.value -> "Less"
                l.value > r.value -> "Greater"
                else -> "Equal"
            }
            // TODO: Return proper variant instead of string
            FunkString(result)
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

        // Hidden Frac operations - aliases for stdlib
        context["__native_frac_add"] = context["add_frac"]!!
        context["__native_frac_subtract"] = context["subtract_frac"]!!
        context["__native_frac_multiply"] = context["multiply_frac"]!!
        context["__native_frac_divide"] = context["divide_frac"]!!
        context["__native_frac_equals"] = context["equal_frac"]!!
        context["__native_frac_less_than"] = context["lessThan_frac"]!!
        context["__native_frac_greater_than"] = context["greaterThan_frac"]!!
        context["__native_frac_to_float"] = context["fracToFloat"]!!
        context["__native_frac_to_string"] = context["fracToString"]!!

        // Conversion functions
        context["__native_int_to_frac"] = NativeFunkFunction("__native_int_to_frac") { args ->
            val record = args[0] as FunkRecord
            val n = record.fields["n"] as? FunkInt ?: throw NativeFunctionException("__native_int_to_frac", "expects 'n' as Int")
            FunkFrac(n.value, 1L)
        }

        context["__native_nat_to_frac"] = NativeFunkFunction("__native_nat_to_frac") { args ->
            val record = args[0] as FunkRecord
            val n = record.fields["n"] as? FunkNat ?: throw NativeFunctionException("__native_nat_to_frac", "expects 'n' as Nat")
            FunkFrac(n.value, 1L)
        }

        // Frac utility operations (need implementation)
        context["__native_make_frac"] = NativeFunkFunction("__native_make_frac") { args ->
            val record = args[0] as FunkRecord
            val numerator = record.fields["numerator"] as? FunkInt ?: throw NativeFunctionException("__native_make_frac", "expects 'numerator' as Int")
            val denominator = record.fields["denominator"] as? FunkInt ?: throw NativeFunctionException("__native_make_frac", "expects 'denominator' as Int")
            if (denominator.value == 0L) throw FunkException("Fraction denominator cannot be zero")
            FunkFrac(numerator.value, denominator.value).reduce()
        }

        context["__native_frac_numerator"] = NativeFunkFunction("__native_frac_numerator") { args ->
            val record = args[0] as FunkRecord
            val n = record.fields["n"] as? FunkFrac ?: throw NativeFunctionException("__native_frac_numerator", "expects 'frac' as Frac")
            FunkInt(n.numerator)
        }

        context["__native_frac_denominator"] = NativeFunkFunction("__native_frac_denominator") { args ->
            val record = args[0] as FunkRecord
            val n = record.fields["n"] as? FunkFrac ?: throw NativeFunctionException("__native_frac_denominator", "expects 'frac' as Frac")
            FunkInt(n.denominator)
        }

        context["__native_reduce_frac"] = NativeFunkFunction("__native_reduce_frac") { args ->
            val record = args[0] as FunkRecord
            val n = record.fields["n"] as? FunkFrac ?: throw NativeFunctionException("__native_reduce_frac", "expects 'frac' as Frac")
            n.reduce()
        }

        context["__native_abs_frac"] = NativeFunkFunction("__native_abs_frac") { args ->
            val record = args[0] as FunkRecord
            val n = record.fields["n"] as? FunkFrac ?: throw NativeFunctionException("__native_abs_frac", "expects 'frac' as Frac")
            FunkFrac(kotlin.math.abs(n.numerator), n.denominator).reduce()
        }

        context["__native_negate_frac"] = NativeFunkFunction("__native_negate_frac") { args ->
            val record = args[0] as FunkRecord
            val n = record.fields["n"] as? FunkFrac ?: throw NativeFunctionException("__native_negate_frac", "expects 'frac' as Frac")
            FunkFrac(-n.numerator, n.denominator).reduce()
        }
    }
}
