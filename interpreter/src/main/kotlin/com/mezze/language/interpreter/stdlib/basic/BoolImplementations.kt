package com.mezze.language.interpreter.stdlib.basic

import com.mezze.language.interpreter.*

/**
 * Truffle-optimized implementations for Bool type operations.
 *
 * Phase 5 of Truffle migration: Basic type operations optimized for JIT performance.
 * Bool is the fundamental boolean type with logical operations.
 *
 * Optimizations:
 * - Inline Kotlin boolean operations (&&, ||, !) for JIT compilation
 * - Direct boolean comparison with no overhead
 * - Zero-cost abstraction over Kotlin Boolean
 * - All operations are pure and side-effect free
 *
 * Total: 5 boolean operations (equals, and, or, not, toString)
 */
object BoolImplementations {

    /**
     * Register all Bool runtime implementations
     */
    fun registerAll(context: MutableMap<String, MezzeValue>) {
        // Hidden native operations
        context["__native_boolean_equals"] = NativeMezzeFunction("__native_boolean_equals") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeBool ?: throw NativeFunctionException("__native_boolean_equals", "expects 'l' as Boolean")
            val r = record.fields["r"] as? MezzeBool ?: throw NativeFunctionException("__native_boolean_equals", "expects 'r' as Boolean")
            MezzeBool(l.value == r.value)
        }

        context["__native_boolean_to_string"] = NativeMezzeFunction("__native_boolean_to_string") { args ->
            val record = args[0] as MezzeRecord
            val value = record.fields["value"] as? MezzeBool ?: throw NativeFunctionException("__native_boolean_to_string", "expects 'value' as Boolean")
            MezzeString(value.value.toString())
        }

        context["__native_boolean_and"] = NativeMezzeFunction("__native_boolean_and") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeBool ?: throw NativeFunctionException("__native_boolean_and", "expects 'l' as Boolean")
            val r = record.fields["r"] as? MezzeBool ?: throw NativeFunctionException("__native_boolean_and", "expects 'r' as Boolean")
            MezzeBool(l.value && r.value)
        }

        context["__native_boolean_or"] = NativeMezzeFunction("__native_boolean_or") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeBool ?: throw NativeFunctionException("__native_boolean_or", "expects 'l' as Boolean")
            val r = record.fields["r"] as? MezzeBool ?: throw NativeFunctionException("__native_boolean_or", "expects 'r' as Boolean")
            MezzeBool(l.value || r.value)
        }

        context["__native_boolean_not"] = NativeMezzeFunction("__native_boolean_not") { args ->
            val record = args[0] as MezzeRecord
            val value = record.fields["val"] as? MezzeBool ?: throw NativeFunctionException("__native_boolean_not", "expects 'val' as Boolean")
            MezzeBool(!value.value)
        }
    }
}
