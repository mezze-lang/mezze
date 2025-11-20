package com.funk.language.interpreter.stdlib.basic

import com.funk.language.interpreter.*

/**
 * Truffle runtime implementations for Bool type operations
 */
object BoolImplementations {

    /**
     * Register all Bool runtime implementations
     */
    fun registerAll(context: MutableMap<String, FunkValue>) {
        // Hidden native operations
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
    }
}
