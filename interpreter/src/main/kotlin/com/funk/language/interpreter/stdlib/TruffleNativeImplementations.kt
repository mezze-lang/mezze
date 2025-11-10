package com.funk.language.interpreter.stdlib

import com.funk.language.interpreter.*
import com.funk.language.interpreter.stdlib.conversion.TruffleConversionImplementations
import com.funk.language.interpreter.stdlib.io.TruffleIOImplementations
import com.funk.language.interpreter.stdlib.numeric.TruffleNumericImplementations
import com.funk.language.interpreter.stdlib.system.TruffleSystemImplementations
import com.funk.language.interpreter.stdlib.math.TruffleMathImplementations
import com.funk.language.interpreter.stdlib.collections.TruffleListImplementations
import com.funk.language.interpreter.stdlib.collections.TruffleNonEmptyListImplementations
import com.funk.language.interpreter.stdlib.collections.TruffleArrayListImplementations
import com.funk.language.interpreter.stdlib.collections.TruffleMapImplementations
import com.funk.language.interpreter.stdlib.collections.TruffleSetImplementations


/**
 * Minimal native implementations for the Funk standard library.
 * 
 * This provides only essential native functions needed for basic operation.
 * More comprehensive implementations will be added once the build is stable.
 */
object TruffleNativeImplementations {
    
    /**
     * Validate that all native functions are implemented (simplified version)
     */
    private fun validateImplementations(context: Map<String, FunkValue>) {
        // Validation logic removed since NativeFunctionRegistry no longer exists
        // Native functions are now managed through the .funk module files
    }
    
    /**
     * Register minimal native implementations in the interpreter context
     */
    fun registerAll(context: MutableMap<String, FunkValue>) {
        // Register conversion functions (intToString, etc.)
        TruffleConversionImplementations.registerAll(context)

        // Register IO functions (print, native_print, etc.)
        TruffleIOImplementations.registerAll(context)

        // Register numeric functions (add_int, etc.)
        TruffleNumericImplementations.registerAll(context)

        // Register system functions (time, environment, etc.)
        TruffleSystemImplementations.registerAll(context)

        // Register math functions (pow, sqrt, sin, cos, random, etc.)
        TruffleMathImplementations.registerAll(context)

        // Register collection functions (List, NonEmptyList, ArrayList, Map, Set)
        TruffleListImplementations.registerAll(context)
        TruffleNonEmptyListImplementations.registerAll(context)
        TruffleArrayListImplementations.registerAll(context)
        TruffleMapImplementations.registerAll(context)
        TruffleSetImplementations.registerAll(context)

        // Register minimal crash function for testing
        context["__native_crash"] = NativeFunkFunction("__native_crash") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException("__native_crash", "expects FunkRecord")
            val msg = record.fields["msg"] as? FunkString ?: throw NativeFunctionException("__native_crash", "expects 'msg' as String")
            System.err.println("Program crashed: ${msg.value}")
            System.exit(1)
            // This line will never be reached, but needed for type checking
            throw RuntimeException("Program crashed: ${msg.value}")
        }
        
        // Internal native functions for ability implementations (hidden with __native_ prefix)
        context["__native_int_add"] = NativeFunkFunction("__native_int_add") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException("__native_int_add", "expects FunkRecord")
            val l = record.fields["l"] as? FunkInt ?: throw NativeFunctionException("__native_int_add", "expects 'l' as Int")
            val r = record.fields["r"] as? FunkInt ?: throw NativeFunctionException("__native_int_add", "expects 'r' as Int")
            FunkInt(l.value + r.value)
        }
        
        context["__native_int_subtract"] = NativeFunkFunction("__native_int_subtract") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException("__native_int_subtract", "expects FunkRecord")
            val l = record.fields["l"] as? FunkInt ?: throw NativeFunctionException("__native_int_subtract", "expects 'l' as Int")
            val r = record.fields["r"] as? FunkInt ?: throw NativeFunctionException("__native_int_subtract", "expects 'r' as Int")
            FunkInt(l.value - r.value)
        }
        
        // Float arithmetic operations
        context["__native_float_add"] = NativeFunkFunction("__native_float_add") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException("__native_float_add", "expects FunkRecord")
            val l = record.fields["l"] as? FunkFloat ?: throw NativeFunctionException("__native_float_add", "expects 'l' as Float")
            val r = record.fields["r"] as? FunkFloat ?: throw NativeFunctionException("__native_float_add", "expects 'r' as Float")
            FunkFloat(l.value + r.value)
        }
        
        context["__native_float_subtract"] = NativeFunkFunction("__native_float_subtract") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException("__native_float_subtract", "expects FunkRecord")
            val l = record.fields["l"] as? FunkFloat ?: throw NativeFunctionException("__native_float_subtract", "expects 'l' as Float")
            val r = record.fields["r"] as? FunkFloat ?: throw NativeFunctionException("__native_float_subtract", "expects 'r' as Float")
            FunkFloat(l.value - r.value)
        }
        
        context["__native_float_multiply"] = NativeFunkFunction("__native_float_multiply") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException("__native_float_multiply", "expects FunkRecord")
            val l = record.fields["l"] as? FunkFloat ?: throw NativeFunctionException("__native_float_multiply", "expects 'l' as Float")
            val r = record.fields["r"] as? FunkFloat ?: throw NativeFunctionException("__native_float_multiply", "expects 'r' as Float")
            FunkFloat(l.value * r.value)
        }
        
        context["__native_float_divide"] = NativeFunkFunction("__native_float_divide") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException("__native_float_divide", "expects FunkRecord")
            val l = record.fields["l"] as? FunkFloat ?: throw NativeFunctionException("__native_float_divide", "expects 'l' as Float")
            val r = record.fields["r"] as? FunkFloat ?: throw NativeFunctionException("__native_float_divide", "expects 'r' as Float")
            if (r.value == 0.0) throw NativeFunctionException("__native_float_divide", "division by zero")
            FunkFloat(l.value / r.value)
        }
        
        context["__native_int_multiply"] = NativeFunkFunction("__native_int_multiply") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException("__native_int_multiply", "expects FunkRecord")
            val l = record.fields["l"] as? FunkInt ?: throw NativeFunctionException("__native_int_multiply", "expects 'l' as Int")
            val r = record.fields["r"] as? FunkInt ?: throw NativeFunctionException("__native_int_multiply", "expects 'r' as Int")
            FunkInt(l.value * r.value)
        }
        
        context["__native_int_divide"] = NativeFunkFunction("__native_int_divide") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException("__native_int_divide", "expects FunkRecord")
            val l = record.fields["l"] as? FunkInt ?: throw NativeFunctionException("__native_int_divide", "expects 'l' as Int")
            val r = record.fields["r"] as? FunkInt ?: throw NativeFunctionException("__native_int_divide", "expects 'r' as Int")
            if (r.value == 0L) throw ArithmeticException("Division by zero")
            FunkInt(l.value / r.value)
        }
        
        context["__native_int_modulo"] = NativeFunkFunction("__native_int_modulo") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException("__native_int_modulo", "expects FunkRecord")
            val l = record.fields["l"] as? FunkInt ?: throw NativeFunctionException("__native_int_modulo", "expects 'l' as Int")
            val r = record.fields["r"] as? FunkInt ?: throw NativeFunctionException("__native_int_modulo", "expects 'r' as Int")
            if (r.value == 0L) throw ArithmeticException("Modulo by zero")
            FunkInt(l.value % r.value)
        }
        
        context["__native_int_equals"] = NativeFunkFunction("__native_int_equals") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException("__native_int_equals", "expects FunkRecord")
            val l = record.fields["l"] as? FunkInt ?: throw NativeFunctionException("__native_int_equals", "expects 'l' as Int")
            val r = record.fields["r"] as? FunkInt ?: throw NativeFunctionException("__native_int_equals", "expects 'r' as Int")
            FunkBool(l.value == r.value)
        }
        
        context["__native_int_less_than"] = NativeFunkFunction("__native_int_less_than") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException("__native_int_less_than", "expects FunkRecord")
            val l = record.fields["l"] as? FunkInt ?: throw NativeFunctionException("__native_int_less_than", "expects 'l' as Int")
            val r = record.fields["r"] as? FunkInt ?: throw NativeFunctionException("__native_int_less_than", "expects 'r' as Int")
            FunkBool(l.value < r.value)
        }
        
        context["__native_int_greater_than"] = NativeFunkFunction("__native_int_greater_than") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException("__native_int_greater_than", "expects FunkRecord")
            val l = record.fields["l"] as? FunkInt ?: throw NativeFunctionException("__native_int_greater_than", "expects 'l' as Int")
            val r = record.fields["r"] as? FunkInt ?: throw NativeFunctionException("__native_int_greater_than", "expects 'r' as Int")
            FunkBool(l.value > r.value)
        }
        
        context["__native_int_compare"] = NativeFunkFunction("__native_int_compare") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException("__native_int_compare", "expects FunkRecord")
            val l = record.fields["l"] as? FunkInt ?: throw NativeFunctionException("__native_int_compare", "expects 'l' as Int")
            val r = record.fields["r"] as? FunkInt ?: throw NativeFunctionException("__native_int_compare", "expects 'r' as Int")
            when {
                l.value < r.value -> FunkVariant("Less", null)
                l.value > r.value -> FunkVariant("Greater", null)
                else -> FunkVariant("Equal", null)
            }
        }
        
        context["__native_int_to_string"] = NativeFunkFunction("__native_int_to_string") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException("__native_int_to_string", "expects FunkRecord")
            val value = record.fields["n"] as? FunkInt ?: throw NativeFunctionException("__native_int_to_string", "expects 'n' as Int")
            FunkString(value.value.toString())
        }
        
        context["__native_boolean_to_string"] = NativeFunkFunction("__native_boolean_to_string") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException("__native_boolean_to_string", "expects FunkRecord")
            val value = record.fields["val"] as? FunkBool ?: throw NativeFunctionException("__native_boolean_to_string", "expects 'val' as Boolean")
            FunkString(value.value.toString())
        }
        
        context["__native_string_to_string"] = NativeFunkFunction("__native_string_to_string") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException("__native_string_to_string", "expects FunkRecord")
            val value = record.fields["val"] as? FunkString ?: throw NativeFunctionException("__native_string_to_string", "expects 'val' as String")
            FunkString(value.value)
        }
        
        context["__native_float_to_string"] = NativeFunkFunction("__native_float_to_string") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException("__native_float_to_string", "expects FunkRecord")
            val value = record.fields["n"] as? FunkFloat ?: throw NativeFunctionException("__native_float_to_string", "expects 'n' as Float")
            FunkString(value.value.toString())
        }
        
        // String native functions
        context["__native_string_equals"] = NativeFunkFunction("__native_string_equals") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException("__native_string_equals", "expects FunkRecord")
            val l = record.fields["l"] as? FunkString ?: throw NativeFunctionException("__native_string_equals", "expects 'l' as String")
            val r = record.fields["r"] as? FunkString ?: throw NativeFunctionException("__native_string_equals", "expects 'r' as String")
            FunkBool(l.value == r.value)
        }

        context["__native_string_compare"] = NativeFunkFunction("__native_string_compare") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException("__native_string_compare", "expects FunkRecord")
            val l = record.fields["l"] as? FunkString ?: throw NativeFunctionException("__native_string_compare", "expects 'l' as String")
            val r = record.fields["r"] as? FunkString ?: throw NativeFunctionException("__native_string_compare", "expects 'r' as String")
            when {
                l.value < r.value -> FunkVariant("Less", null)
                l.value > r.value -> FunkVariant("Greater", null)
                else -> FunkVariant("Equal", null)
            }
        }

        context["__native_string_less_than"] = NativeFunkFunction("__native_string_less_than") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException("__native_string_less_than", "expects FunkRecord")
            val l = record.fields["l"] as? FunkString ?: throw NativeFunctionException("__native_string_less_than", "expects 'l' as String")
            val r = record.fields["r"] as? FunkString ?: throw NativeFunctionException("__native_string_less_than", "expects 'r' as String")
            FunkBool(l.value < r.value)
        }

        context["__native_string_greater_than"] = NativeFunkFunction("__native_string_greater_than") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException("__native_string_greater_than", "expects FunkRecord")
            val l = record.fields["l"] as? FunkString ?: throw NativeFunctionException("__native_string_greater_than", "expects 'l' as String")
            val r = record.fields["r"] as? FunkString ?: throw NativeFunctionException("__native_string_greater_than", "expects 'r' as String")
            FunkBool(l.value > r.value)
        }
        
        context["__native_string_concat"] = NativeFunkFunction("__native_string_concat") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException("__native_string_concat", "expects FunkRecord")
            val l = record.fields["l"] as? FunkString ?: throw NativeFunctionException("__native_string_concat", "expects 'l' as String")
            val r = record.fields["r"] as? FunkString ?: throw NativeFunctionException("__native_string_concat", "expects 'r' as String")
            FunkString(l.value + r.value)
        }
        
        // Additional string utility functions
        context["__native_string_length"] = NativeFunkFunction("__native_string_length") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException("__native_string_length", "expects FunkRecord")
            val s = record.fields["s"] as? FunkString ?: throw NativeFunctionException("__native_string_length", "expects 's' as String")
            FunkInt(s.value.length.toLong())
        }
        
        context["__native_string_to_upper"] = NativeFunkFunction("__native_string_to_upper") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException("__native_string_to_upper", "expects FunkRecord")
            val s = record.fields["s"] as? FunkString ?: throw NativeFunctionException("__native_string_to_upper", "expects 's' as String")
            FunkString(s.value.uppercase())
        }
        
        context["__native_string_to_lower"] = NativeFunkFunction("__native_string_to_lower") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException("__native_string_to_lower", "expects FunkRecord")
            val s = record.fields["s"] as? FunkString ?: throw NativeFunctionException("__native_string_to_lower", "expects 's' as String")
            FunkString(s.value.lowercase())
        }
        
        context["__native_string_trim"] = NativeFunkFunction("__native_string_trim") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException("__native_string_trim", "expects FunkRecord")
            val s = record.fields["s"] as? FunkString ?: throw NativeFunctionException("__native_string_trim", "expects 's' as String")
            FunkString(s.value.trim())
        }
        
        context["__native_string_reverse"] = NativeFunkFunction("__native_string_reverse") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException("__native_string_reverse", "expects FunkRecord")
            val s = record.fields["s"] as? FunkString ?: throw NativeFunctionException("__native_string_reverse", "expects 's' as String")
            FunkString(s.value.reversed())
        }
        
        context["__native_string_starts_with"] = NativeFunkFunction("__native_string_starts_with") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException("__native_string_starts_with", "expects FunkRecord")
            val s = record.fields["s"] as? FunkString ?: throw NativeFunctionException("__native_string_starts_with", "expects 's' as String")
            val prefix = record.fields["prefix"] as? FunkString ?: throw NativeFunctionException("__native_string_starts_with", "expects 'prefix' as String")
            FunkBool(s.value.startsWith(prefix.value))
        }
        
        context["__native_string_ends_with"] = NativeFunkFunction("__native_string_ends_with") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException("__native_string_ends_with", "expects FunkRecord")
            val s = record.fields["s"] as? FunkString ?: throw NativeFunctionException("__native_string_ends_with", "expects 's' as String")
            val suffix = record.fields["suffix"] as? FunkString ?: throw NativeFunctionException("__native_string_ends_with", "expects 'suffix' as String")
            FunkBool(s.value.endsWith(suffix.value))
        }
        
        context["__native_string_contains"] = NativeFunkFunction("__native_string_contains") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException("__native_string_contains", "expects FunkRecord")
            val s = record.fields["s"] as? FunkString ?: throw NativeFunctionException("__native_string_contains", "expects 's' as String")
            val substring = record.fields["substring"] as? FunkString ?: throw NativeFunctionException("__native_string_contains", "expects 'substring' as String")
            FunkBool(s.value.contains(substring.value))
        }
        // Console native functions (required by interpreter)
        context["__native_console_print"] = NativeFunkFunction("__native_console_print") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException("__native_console_print", "expects FunkRecord")
            val msg = record.fields["msg"] as? FunkString ?: throw NativeFunctionException("__native_console_print", "expects 'msg' as String")
            println(msg.value)
            FunkNull
        }
        
        context["__native_console_debug"] = NativeFunkFunction("__native_console_debug") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException("__native_console_debug", "expects FunkRecord")
            val msg = record.fields["msg"] as? FunkString ?: throw NativeFunctionException("__native_console_debug", "expects 'msg' as String")
            System.err.println("[DEBUG] ${msg.value}")
            FunkNull
        }
        
        // === INLINED OPERATORS FROM STDLIB ===
        
        // Arithmetic operators (user-accessible)
        context["+"] = NativeFunkFunction("+") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException("+", "expects FunkRecord")
            val left = record.fields["left"] ?: throw NativeFunctionException("+", "expects 'left' field")
            val right = record.fields["right"] ?: throw NativeFunctionException("+", "expects 'right' field")
            
            when {
                left is FunkInt && right is FunkInt -> FunkInt(left.value + right.value)
                left is FunkFloat && right is FunkFloat -> FunkFloat(left.value + right.value)
                else -> throw NativeFunctionException("+", "unsupported types: ${left::class.simpleName} + ${right::class.simpleName}")
            }
        }
        
        context["-"] = NativeFunkFunction("-") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException("-", "expects FunkRecord")
            val left = record.fields["left"] ?: throw NativeFunctionException("-", "expects 'left' field")
            val right = record.fields["right"] ?: throw NativeFunctionException("-", "expects 'right' field")
            
            when {
                left is FunkInt && right is FunkInt -> FunkInt(left.value - right.value)
                left is FunkFloat && right is FunkFloat -> FunkFloat(left.value - right.value)
                else -> throw NativeFunctionException("-", "unsupported types: ${left::class.simpleName} - ${right::class.simpleName}")
            }
        }
        
        context["*"] = NativeFunkFunction("*") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException("*", "expects FunkRecord")
            val left = record.fields["left"] ?: throw NativeFunctionException("*", "expects 'left' field")
            val right = record.fields["right"] ?: throw NativeFunctionException("*", "expects 'right' field")
            
            when {
                left is FunkInt && right is FunkInt -> FunkInt(left.value * right.value)
                left is FunkFloat && right is FunkFloat -> FunkFloat(left.value * right.value)
                else -> throw NativeFunctionException("*", "unsupported types: ${left::class.simpleName} * ${right::class.simpleName}")
            }
        }
        
        context["/"] = NativeFunkFunction("/") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException("/", "expects FunkRecord")
            val left = record.fields["left"] ?: throw NativeFunctionException("/", "expects 'left' field")
            val right = record.fields["right"] ?: throw NativeFunctionException("/", "expects 'right' field")
            
            when {
                left is FunkInt && right is FunkInt -> {
                    if (right.value == 0L) throw ArithmeticException("Division by zero")
                    FunkInt(left.value / right.value)
                }
                left is FunkFloat && right is FunkFloat -> {
                    if (right.value == 0.0) throw ArithmeticException("Division by zero")
                    FunkFloat(left.value / right.value)
                }
                else -> throw NativeFunctionException("/", "unsupported types: ${left::class.simpleName} / ${right::class.simpleName}")
            }
        }
        
        context["%"] = NativeFunkFunction("%") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException("%", "expects FunkRecord")
            val left = record.fields["left"] ?: throw NativeFunctionException("%", "expects 'left' field")
            val right = record.fields["right"] ?: throw NativeFunctionException("%", "expects 'right' field")
            
            when {
                left is FunkInt && right is FunkInt -> {
                    if (right.value == 0L) throw ArithmeticException("Modulo by zero")
                    FunkInt(left.value % right.value)
                }
                left is FunkFloat && right is FunkFloat -> {
                    if (right.value == 0.0) throw ArithmeticException("Modulo by zero")
                    FunkFloat(left.value % right.value)
                }
                else -> throw NativeFunctionException("%", "unsupported types: ${left::class.simpleName} % ${right::class.simpleName}")
            }
        }
        
        // Comparison operators
        context["=="] = NativeFunkFunction("==") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException("==", "expects FunkRecord")
            val left = record.fields["left"] ?: throw NativeFunctionException("==", "expects 'left' field")
            val right = record.fields["right"] ?: throw NativeFunctionException("==", "expects 'right' field")
            
            when {
                left is FunkInt && right is FunkInt -> FunkBool(left.value == right.value)
                left is FunkFloat && right is FunkFloat -> FunkBool(left.value == right.value)
                left is FunkString && right is FunkString -> FunkBool(left.value == right.value)
                left is FunkBool && right is FunkBool -> FunkBool(left.value == right.value)
                else -> FunkBool(false) // Different types are not equal
            }
        }
        
        context["!="] = NativeFunkFunction("!=") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException("!=", "expects FunkRecord")
            val left = record.fields["left"] ?: throw NativeFunctionException("!=", "expects 'left' field")
            val right = record.fields["right"] ?: throw NativeFunctionException("!=", "expects 'right' field")
            
            when {
                left is FunkInt && right is FunkInt -> FunkBool(left.value != right.value)
                left is FunkFloat && right is FunkFloat -> FunkBool(left.value != right.value)
                left is FunkString && right is FunkString -> FunkBool(left.value != right.value)
                left is FunkBool && right is FunkBool -> FunkBool(left.value != right.value)
                else -> FunkBool(true) // Different types are not equal
            }
        }
        
        context["<"] = NativeFunkFunction("<") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException("<", "expects FunkRecord")
            val left = record.fields["left"] ?: throw NativeFunctionException("<", "expects 'left' field")
            val right = record.fields["right"] ?: throw NativeFunctionException("<", "expects 'right' field")
            
            when {
                left is FunkInt && right is FunkInt -> FunkBool(left.value < right.value)
                left is FunkFloat && right is FunkFloat -> FunkBool(left.value < right.value)
                left is FunkString && right is FunkString -> FunkBool(left.value < right.value)
                else -> throw NativeFunctionException("<", "unsupported types: ${left::class.simpleName} < ${right::class.simpleName}")
            }
        }
        
        context[">"] = NativeFunkFunction(">") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException(">", "expects FunkRecord")
            val left = record.fields["left"] ?: throw NativeFunctionException(">", "expects 'left' field")
            val right = record.fields["right"] ?: throw NativeFunctionException(">", "expects 'right' field")
            
            when {
                left is FunkInt && right is FunkInt -> FunkBool(left.value > right.value)
                left is FunkFloat && right is FunkFloat -> FunkBool(left.value > right.value)
                left is FunkString && right is FunkString -> FunkBool(left.value > right.value)
                else -> throw NativeFunctionException(">", "unsupported types: ${left::class.simpleName} > ${right::class.simpleName}")
            }
        }
        
        context["<="] = NativeFunkFunction("<=") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException("<=", "expects FunkRecord")
            val left = record.fields["left"] ?: throw NativeFunctionException("<=", "expects 'left' field")
            val right = record.fields["right"] ?: throw NativeFunctionException("<=", "expects 'right' field")
            
            when {
                left is FunkInt && right is FunkInt -> FunkBool(left.value <= right.value)
                left is FunkFloat && right is FunkFloat -> FunkBool(left.value <= right.value)
                left is FunkString && right is FunkString -> FunkBool(left.value <= right.value)
                else -> throw NativeFunctionException("<=", "unsupported types: ${left::class.simpleName} <= ${right::class.simpleName}")
            }
        }
        
        context[">="] = NativeFunkFunction(">=") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException(">=", "expects FunkRecord")
            val left = record.fields["left"] ?: throw NativeFunctionException(">=", "expects 'left' field")
            val right = record.fields["right"] ?: throw NativeFunctionException(">=", "expects 'right' field")
            
            when {
                left is FunkInt && right is FunkInt -> FunkBool(left.value >= right.value)
                left is FunkFloat && right is FunkFloat -> FunkBool(left.value >= right.value)
                left is FunkString && right is FunkString -> FunkBool(left.value >= right.value)
                else -> throw NativeFunctionException(">=", "unsupported types: ${left::class.simpleName} >= ${right::class.simpleName}")
            }
        }
        
        // Core stdlib functions
        context["not"] = NativeFunkFunction("not") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException("not", "expects FunkRecord")
            val value = record.fields["value"] as? FunkBool ?: throw NativeFunctionException("not", "expects 'value' as Boolean")
            FunkBool(!value.value)
        }

        // Note: concat is not a global function - use ++ operator for string concatenation
        // concat is defined per-collection in their respective modules (list, array, etc.)

        // Core panic function
        context["crash"] = NativeFunkFunction("crash") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException("crash", "expects FunkRecord")
            val msg = record.fields["msg"] as? FunkString ?: throw NativeFunctionException("crash", "expects 'msg' as String")
            System.err.println("Program crashed: ${msg.value}")
            System.exit(1)
            throw RuntimeException("Program crashed: ${msg.value}")
        }
        
        // Validate that all functions from the registry are implemented
        validateImplementations(context)
    }
}