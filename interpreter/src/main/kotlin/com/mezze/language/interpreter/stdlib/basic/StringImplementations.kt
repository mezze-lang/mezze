package com.mezze.language.interpreter.stdlib.basic

import com.mezze.language.interpreter.*

/**
 * Truffle runtime implementations for String type operations.
 *
 * Phase 2 of Truffle migration: Optimized string operations for better JIT performance.
 * These implementations use inline Kotlin string operations to be JIT-friendly.
 */
object StringImplementations {

    /**
     * Register all String runtime implementations
     */
    fun registerAll(context: MutableMap<String, MezzeValue>) {
        // === HOT PATH: Core String Operations ===

        // String concatenation (hot path - heavily used)
        context["__native_string_concat"] = NativeMezzeFunction("__native_string_concat") { args ->
            val record = args.firstOrNull() as? MezzeRecord ?: throw NativeFunctionException("__native_string_concat", "expects MezzeRecord")
            val l = record.fields["l"] as? MezzeString ?: throw NativeFunctionException("__native_string_concat", "expects 'l' as String")
            val r = record.fields["r"] as? MezzeString ?: throw NativeFunctionException("__native_string_concat", "expects 'r' as String")
            // Inline string concatenation for JIT optimization
            MezzeString(l.value + r.value)
        }

        // String length (hot path - frequently used)
        context["__native_string_length"] = NativeMezzeFunction("__native_string_length") { args ->
            val record = args.firstOrNull() as? MezzeRecord ?: throw NativeFunctionException("__native_string_length", "expects MezzeRecord")
            val s = record.fields["s"] as? MezzeString ?: throw NativeFunctionException("__native_string_length", "expects 's' as String")
            // Direct property access for JIT optimization
            MezzeInt(s.value.length.toLong())
        }

        // String equality (hot path - comparison operations)
        context["__native_string_equals"] = NativeMezzeFunction("__native_string_equals") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeString ?: throw NativeFunctionException("__native_string_equals", "expects 'l' as String")
            val r = record.fields["r"] as? MezzeString ?: throw NativeFunctionException("__native_string_equals", "expects 'r' as String")
            // Inline comparison for JIT optimization
            MezzeBool(l.value == r.value)
        }

        // String comparison operators
        context["__native_string_less_than"] = NativeMezzeFunction("__native_string_less_than") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeString ?: throw NativeFunctionException("__native_string_less_than", "expects 'l' as String")
            val r = record.fields["r"] as? MezzeString ?: throw NativeFunctionException("__native_string_less_than", "expects 'r' as String")
            MezzeBool(l.value < r.value)
        }

        context["__native_string_greater_than"] = NativeMezzeFunction("__native_string_greater_than") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeString ?: throw NativeFunctionException("__native_string_greater_than", "expects 'l' as String")
            val r = record.fields["r"] as? MezzeString ?: throw NativeFunctionException("__native_string_greater_than", "expects 'r' as String")
            MezzeBool(l.value > r.value)
        }

        context["__native_string_compare"] = NativeMezzeFunction("__native_string_compare") { args ->
            val record = args[0] as MezzeRecord
            val l = record.fields["l"] as? MezzeString ?: throw NativeFunctionException("__native_string_compare", "expects 'l' as String")
            val r = record.fields["r"] as? MezzeString ?: throw NativeFunctionException("__native_string_compare", "expects 'r' as String")
            // Inline comparison logic for JIT optimization
            val result = when {
                l.value < r.value -> "Less"
                l.value > r.value -> "Greater"
                else -> "Equal"
            }
            // TODO: Return proper variant instead of string
            MezzeString(result)
        }

        // === String Utility Functions ===

        // String conversion (identity function for String)
        context["__native_string_to_string"] = NativeMezzeFunction("__native_string_to_string") { args ->
            val record = args.firstOrNull() as? MezzeRecord ?: throw NativeFunctionException("__native_string_to_string", "expects MezzeRecord")
            val s = record.fields["s"] as? MezzeString ?: throw NativeFunctionException("__native_string_to_string", "expects 's' as String")
            s // Identity function - just return the string
        }

        // Case conversion
        context["__native_string_to_upper"] = NativeMezzeFunction("__native_string_to_upper") { args ->
            val record = args.firstOrNull() as? MezzeRecord ?: throw NativeFunctionException("__native_string_to_upper", "expects MezzeRecord")
            val s = record.fields["s"] as? MezzeString ?: throw NativeFunctionException("__native_string_to_upper", "expects 's' as String")
            MezzeString(s.value.uppercase())
        }

        context["__native_string_to_lower"] = NativeMezzeFunction("__native_string_to_lower") { args ->
            val record = args.firstOrNull() as? MezzeRecord ?: throw NativeFunctionException("__native_string_to_lower", "expects MezzeRecord")
            val s = record.fields["s"] as? MezzeString ?: throw NativeFunctionException("__native_string_to_lower", "expects 's' as String")
            MezzeString(s.value.lowercase())
        }

        // String manipulation
        context["__native_string_trim"] = NativeMezzeFunction("__native_string_trim") { args ->
            val record = args.firstOrNull() as? MezzeRecord ?: throw NativeFunctionException("__native_string_trim", "expects MezzeRecord")
            val s = record.fields["s"] as? MezzeString ?: throw NativeFunctionException("__native_string_trim", "expects 's' as String")
            MezzeString(s.value.trim())
        }

        context["__native_string_reverse"] = NativeMezzeFunction("__native_string_reverse") { args ->
            val record = args.firstOrNull() as? MezzeRecord ?: throw NativeFunctionException("__native_string_reverse", "expects MezzeRecord")
            val s = record.fields["s"] as? MezzeString ?: throw NativeFunctionException("__native_string_reverse", "expects 's' as String")
            MezzeString(s.value.reversed())
        }

        // String search
        context["__native_string_starts_with"] = NativeMezzeFunction("__native_string_starts_with") { args ->
            val record = args.firstOrNull() as? MezzeRecord ?: throw NativeFunctionException("__native_string_starts_with", "expects MezzeRecord")
            val s = record.fields["s"] as? MezzeString ?: throw NativeFunctionException("__native_string_starts_with", "expects 's' as String")
            val prefix = record.fields["prefix"] as? MezzeString ?: throw NativeFunctionException("__native_string_starts_with", "expects 'prefix' as String")
            MezzeBool(s.value.startsWith(prefix.value))
        }

        context["__native_string_ends_with"] = NativeMezzeFunction("__native_string_ends_with") { args ->
            val record = args.firstOrNull() as? MezzeRecord ?: throw NativeFunctionException("__native_string_ends_with", "expects MezzeRecord")
            val s = record.fields["s"] as? MezzeString ?: throw NativeFunctionException("__native_string_ends_with", "expects 's' as String")
            val suffix = record.fields["suffix"] as? MezzeString ?: throw NativeFunctionException("__native_string_ends_with", "expects 'suffix' as String")
            MezzeBool(s.value.endsWith(suffix.value))
        }

        context["__native_string_contains"] = NativeMezzeFunction("__native_string_contains") { args ->
            val record = args.firstOrNull() as? MezzeRecord ?: throw NativeFunctionException("__native_string_contains", "expects MezzeRecord")
            val s = record.fields["s"] as? MezzeString ?: throw NativeFunctionException("__native_string_contains", "expects 's' as String")
            val substring = record.fields["substring"] as? MezzeString ?: throw NativeFunctionException("__native_string_contains", "expects 'substring' as String")
            MezzeBool(s.value.contains(substring.value))
        }

        // String splitting and joining
        context["__native_string_split"] = NativeMezzeFunction("__native_string_split") { args ->
            val record = args.firstOrNull() as? MezzeRecord ?: throw NativeFunctionException("__native_string_split", "expects MezzeRecord")
            val s = record.fields["s"] as? MezzeString ?: throw NativeFunctionException("__native_string_split", "expects 's' as String")
            val delimiter = record.fields["delimiter"] as? MezzeString ?: throw NativeFunctionException("__native_string_split", "expects 'delimiter' as String")

            // Inline split logic for JIT optimization
            val parts = if (delimiter.value.isEmpty()) {
                // Split into individual characters
                s.value.map { it.toString() }
            } else {
                s.value.split(delimiter.value)
            }

            MezzeArrayList(parts.map { MezzeString(it) })
        }

        context["__native_string_join"] = NativeMezzeFunction("__native_string_join") { args ->
            val record = args.firstOrNull() as? MezzeRecord ?: throw NativeFunctionException("__native_string_join", "expects MezzeRecord")
            val strings = record.fields["strings"] as? MezzeArrayList ?: throw NativeFunctionException("__native_string_join", "expects 'strings' as List")
            val separator = record.fields["separator"] as? MezzeString ?: throw NativeFunctionException("__native_string_join", "expects 'separator' as String")

            // Inline join logic for JIT optimization
            val stringValues = strings.elements.map { element ->
                when (element) {
                    is MezzeString -> element.value
                    else -> throw NativeFunctionException("__native_string_join", "all list elements must be strings")
                }
            }

            MezzeString(stringValues.joinToString(separator.value))
        }

        // === Case Conversion Functions (for codec field transforms) ===

        // Convert to snake_case: "userName" -> "user_name", "HTTPResponse" -> "http_response"
        context["__native_string_to_snake_case"] = NativeMezzeFunction("__native_string_to_snake_case") { args ->
            val record = args.firstOrNull() as? MezzeRecord ?: throw NativeFunctionException("__native_string_to_snake_case", "expects MezzeRecord")
            val s = record.fields["s"] as? MezzeString ?: throw NativeFunctionException("__native_string_to_snake_case", "expects 's' as String")
            MezzeString(toSnakeCase(s.value))
        }

        // Convert to camelCase: "user_name" -> "userName", "http_response" -> "httpResponse"
        context["__native_string_to_camel_case"] = NativeMezzeFunction("__native_string_to_camel_case") { args ->
            val record = args.firstOrNull() as? MezzeRecord ?: throw NativeFunctionException("__native_string_to_camel_case", "expects MezzeRecord")
            val s = record.fields["s"] as? MezzeString ?: throw NativeFunctionException("__native_string_to_camel_case", "expects 's' as String")
            MezzeString(toCamelCase(s.value))
        }

        // Convert to kebab-case: "userName" -> "user-name"
        context["__native_string_to_kebab_case"] = NativeMezzeFunction("__native_string_to_kebab_case") { args ->
            val record = args.firstOrNull() as? MezzeRecord ?: throw NativeFunctionException("__native_string_to_kebab_case", "expects MezzeRecord")
            val s = record.fields["s"] as? MezzeString ?: throw NativeFunctionException("__native_string_to_kebab_case", "expects 's' as String")
            MezzeString(toKebabCase(s.value))
        }

        // Convert to SCREAMING_SNAKE_CASE: "userName" -> "USER_NAME"
        context["__native_string_to_screaming_snake_case"] = NativeMezzeFunction("__native_string_to_screaming_snake_case") { args ->
            val record = args.firstOrNull() as? MezzeRecord ?: throw NativeFunctionException("__native_string_to_screaming_snake_case", "expects MezzeRecord")
            val s = record.fields["s"] as? MezzeString ?: throw NativeFunctionException("__native_string_to_screaming_snake_case", "expects 's' as String")
            MezzeString(toSnakeCase(s.value).uppercase())
        }
    }

    // Helper: Convert camelCase/PascalCase to snake_case
    private fun toSnakeCase(s: String): String {
        if (s.isEmpty()) return s
        val result = StringBuilder()
        for ((i, c) in s.withIndex()) {
            if (c.isUpperCase()) {
                if (i > 0) result.append('_')
                result.append(c.lowercaseChar())
            } else {
                result.append(c)
            }
        }
        return result.toString()
    }

    // Helper: Convert snake_case/kebab-case to camelCase
    private fun toCamelCase(s: String): String {
        if (s.isEmpty()) return s
        val parts = s.split('_', '-')
        return parts.mapIndexed { i, part ->
            if (i == 0) part.lowercase()
            else part.replaceFirstChar { it.uppercase() }
        }.joinToString("")
    }

    // Helper: Convert camelCase/PascalCase to kebab-case
    private fun toKebabCase(s: String): String {
        if (s.isEmpty()) return s
        val result = StringBuilder()
        for ((i, c) in s.withIndex()) {
            if (c.isUpperCase()) {
                if (i > 0) result.append('-')
                result.append(c.lowercaseChar())
            } else {
                result.append(c)
            }
        }
        return result.toString()
    }
}
