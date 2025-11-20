package com.funk.language.interpreter.stdlib.basic

import com.funk.language.interpreter.*

/**
 * Truffle runtime implementations for String type operations
 */
object StringImplementations {

    /**
     * Register all String runtime implementations
     */
    fun registerAll(context: MutableMap<String, FunkValue>) {
        // Hidden native operations
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
    }
}
