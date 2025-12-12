package com.funk.language.interpreter.stdlib

import com.funk.language.interpreter.*
import com.funk.language.interpreter.stdlib.basic.IntImplementations
import com.funk.language.interpreter.stdlib.basic.FloatImplementations
import com.funk.language.interpreter.stdlib.basic.NatImplementations
import com.funk.language.interpreter.stdlib.basic.BoolImplementations
import com.funk.language.interpreter.stdlib.basic.StringImplementations
import com.funk.language.interpreter.stdlib.basic.FracImplementations
import com.funk.language.interpreter.stdlib.math.MathCoreImplementations
import com.funk.language.interpreter.stdlib.math.MathTrigImplementations
import com.funk.language.interpreter.stdlib.math.MathRandomImplementations
import com.funk.language.interpreter.stdlib.collections.TruffleListImplementations
import com.funk.language.interpreter.stdlib.collections.TruffleNonEmptyListImplementations
import com.funk.language.interpreter.stdlib.collections.TruffleArrayListImplementations
import com.funk.language.interpreter.stdlib.collections.TruffleMapImplementations
import com.funk.language.interpreter.stdlib.collections.TruffleSetImplementations
import com.funk.language.interpreter.stdlib.effects.LogImplementations
import com.funk.language.interpreter.stdlib.effects.FilesystemImplementations
import com.funk.language.interpreter.stdlib.effects.TimeImplementations
import com.funk.language.interpreter.stdlib.effects.EnvironmentImplementations
import com.funk.language.interpreter.stdlib.effects.ContinuationImplementations
import com.funk.language.interpreter.stdlib.conversion.TruffleConversionImplementations
import com.funk.language.interpreter.stdlib.io.TruffleIOImplementations
import com.funk.language.interpreter.stdlib.system.TruffleSystemImplementations


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
        // Register basic type implementations (int, float, nat, bool, string, frac)
        IntImplementations.registerAll(context)
        FloatImplementations.registerAll(context)
        NatImplementations.registerAll(context)
        BoolImplementations.registerAll(context)
        StringImplementations.registerAll(context)
        FracImplementations.registerAll(context)

        // Register math implementations (core, trig, random)
        MathCoreImplementations.registerAll(context)
        MathTrigImplementations.registerAll(context)
        MathRandomImplementations.registerAll(context)

        // Register collection implementations (List, NonEmptyList, ArrayList, Map, Set)
        TruffleListImplementations.registerAll(context)
        TruffleNonEmptyListImplementations.registerAll(context)
        TruffleArrayListImplementations.registerAll(context)
        TruffleMapImplementations.registerAll(context)
        TruffleSetImplementations.registerAll(context)

        // Register effects implementations (log, filesystem, time, environment, continuation)
        LogImplementations.registerAll(context)
        FilesystemImplementations.registerAll(context)
        TimeImplementations.registerAll(context)
        EnvironmentImplementations.registerAll(context)
        ContinuationImplementations.registerAll(context, null)  // interpreter will be set up later

        // Register legacy implementations (for backward compatibility)
        TruffleConversionImplementations.registerAll(context)
        TruffleIOImplementations.registerAll(context)
        TruffleSystemImplementations.registerAll(context)

        // Register minimal crash function for testing
        context["__native_crash"] = NativeFunkFunction("__native_crash") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException("__native_crash", "expects FunkRecord")
            val msg = record.fields["msg"] as? FunkString ?: throw NativeFunctionException("__native_crash", "expects 'msg' as String")
            System.err.println("Program crashed: ${msg.value}")
            System.exit(1)
            // This line will never be reached, but needed for type checking
            throw FunkException("Program crashed: ${msg.value}")
        }

        // NOTE: Numeric operations (__native_int_*, __native_float_*, etc.) are now defined in
        // TruffleNumericImplementations.kt which is loaded above. The implementations there
        // provide the correct parameter names and better organization.

        // String utility functions
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

        context["__native_string_split"] = NativeFunkFunction("__native_string_split") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException("__native_string_split", "expects FunkRecord")
            val s = record.fields["s"] as? FunkString ?: throw NativeFunctionException("__native_string_split", "expects 's' as String")
            val delimiter = record.fields["delimiter"] as? FunkString ?: throw NativeFunctionException("__native_string_split", "expects 'delimiter' as String")

            val parts = if (delimiter.value.isEmpty()) {
                // Split into individual characters
                s.value.map { it.toString() }
            } else {
                s.value.split(delimiter.value)
            }

            FunkArrayList(parts.map { FunkString(it) })
        }

        context["__native_string_join"] = NativeFunkFunction("__native_string_join") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException("__native_string_join", "expects FunkRecord")
            val strings = record.fields["strings"] as? FunkArrayList ?: throw NativeFunctionException("__native_string_join", "expects 'strings' as List")
            val separator = record.fields["separator"] as? FunkString ?: throw NativeFunctionException("__native_string_join", "expects 'separator' as String")

            val stringValues = strings.elements.map { element ->
                when (element) {
                    is FunkString -> element.value
                    else -> throw NativeFunctionException("__native_string_join", "all list elements must be strings")
                }
            }

            FunkString(stringValues.joinToString(separator.value))
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
                    if (right.value == 0L) throw FunkException("Division by zero")
                    FunkInt(left.value / right.value)
                }
                left is FunkFloat && right is FunkFloat -> {
                    if (right.value == 0.0) throw FunkException("Division by zero")
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
                    if (right.value == 0L) throw FunkException("Modulo by zero")
                    FunkInt(left.value % right.value)
                }
                left is FunkFloat && right is FunkFloat -> {
                    if (right.value == 0.0) throw FunkException("Modulo by zero")
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
            throw FunkException("Program crashed: ${msg.value}")
        }
        
        // Validate that all functions from the registry are implemented
        validateImplementations(context)
    }
}