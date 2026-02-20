package com.mezze.language.interpreter.stdlib.basic

import com.mezze.language.interpreter.*

/**
 * Native implementations for Bool type operations.
 *
 * While Bool operators (&&, ||) are handled by Truffle nodes for optimal performance,
 * the stdlib bool module functions use these native implementations for consistency
 * and to avoid if-then-else overhead.
 */
object BoolImplementations {

    /**
     * Register all Bool runtime implementations
     */
    fun registerAll(context: MutableMap<String, MezzeValue>) {
        context["__native_bool_and"] = NativeMezzeFunction("__native_bool_and") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeBool ?: throw NativeFunctionException("__native_bool_and", "expects 'l' as Bool")
            val r = record.fields["r"] as? MezzeBool ?: throw NativeFunctionException("__native_bool_and", "expects 'r' as Bool")
            MezzeBool(l.value && r.value)
        }

        context["__native_bool_or"] = NativeMezzeFunction("__native_bool_or") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeBool ?: throw NativeFunctionException("__native_bool_or", "expects 'l' as Bool")
            val r = record.fields["r"] as? MezzeBool ?: throw NativeFunctionException("__native_bool_or", "expects 'r' as Bool")
            MezzeBool(l.value || r.value)
        }

        context["__native_bool_not"] = NativeMezzeFunction("__native_bool_not") { args ->
            val record = args[0] as MezzeRecord
            val b = record.fields["b"] as? MezzeBool ?: throw NativeFunctionException("__native_bool_not", "expects 'b' as Bool")
            MezzeBool(!b.value)
        }

        context["__native_bool_xor"] = NativeMezzeFunction("__native_bool_xor") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeBool ?: throw NativeFunctionException("__native_bool_xor", "expects 'l' as Bool")
            val r = record.fields["r"] as? MezzeBool ?: throw NativeFunctionException("__native_bool_xor", "expects 'r' as Bool")
            MezzeBool(l.value xor r.value)
        }

        context["__native_bool_to_string"] = NativeMezzeFunction("__native_bool_to_string") { args ->
            val record = args[0] as MezzeRecord
            val b = record.fields["b"] as? MezzeBool ?: throw NativeFunctionException("__native_bool_to_string", "expects 'b' as Bool")
            MezzeString(if (b.value) "true" else "false")
        }
    }
}
