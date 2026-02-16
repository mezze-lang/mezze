package com.funk.language.interpreter.stdlib.basic

import com.funk.language.interpreter.*

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
    fun registerAll(context: MutableMap<String, FunkValue>) {
        // === HOT PATH: Core String Operations ===

        // String concatenation (hot path - heavily used)
        context["__native_string_concat"] = NativeFunkFunction("__native_string_concat") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException("__native_string_concat", "expects FunkRecord")
            val l = record.fields["l"] as? FunkString ?: throw NativeFunctionException("__native_string_concat", "expects 'l' as String")
            val r = record.fields["r"] as? FunkString ?: throw NativeFunctionException("__native_string_concat", "expects 'r' as String")
            // Inline string concatenation for JIT optimization
            FunkString(l.value + r.value)
        }

        // String length (hot path - frequently used)
        context["__native_string_length"] = NativeFunkFunction("__native_string_length") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException("__native_string_length", "expects FunkRecord")
            val s = record.fields["s"] as? FunkString ?: throw NativeFunctionException("__native_string_length", "expects 's' as String")
            // Direct property access for JIT optimization
            FunkInt(s.value.length.toLong())
        }

        // String equality (hot path - comparison operations)
        context["__native_string_equals"] = NativeFunkFunction("__native_string_equals") { args ->
            val record = args[0] as FunkRecord
            val l = record.fields["l"] as? FunkString ?: throw NativeFunctionException("__native_string_equals", "expects 'l' as String")
            val r = record.fields["r"] as? FunkString ?: throw NativeFunctionException("__native_string_equals", "expects 'r' as String")
            // Inline comparison for JIT optimization
            FunkBool(l.value == r.value)
        }

        // String comparison operators
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
            // Inline comparison logic for JIT optimization
            val result = when {
                l.value < r.value -> "Less"
                l.value > r.value -> "Greater"
                else -> "Equal"
            }
            // TODO: Return proper variant instead of string
            FunkString(result)
        }

        // === String Utility Functions ===

        // String conversion (identity function for String)
        context["__native_string_to_string"] = NativeFunkFunction("__native_string_to_string") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException("__native_string_to_string", "expects FunkRecord")
            val s = record.fields["s"] as? FunkString ?: throw NativeFunctionException("__native_string_to_string", "expects 's' as String")
            s // Identity function - just return the string
        }

        // Case conversion
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

        // String manipulation
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

        // String search
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

        // String splitting and joining
        context["__native_string_split"] = NativeFunkFunction("__native_string_split") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException("__native_string_split", "expects FunkRecord")
            val s = record.fields["s"] as? FunkString ?: throw NativeFunctionException("__native_string_split", "expects 's' as String")
            val delimiter = record.fields["delimiter"] as? FunkString ?: throw NativeFunctionException("__native_string_split", "expects 'delimiter' as String")

            // Inline split logic for JIT optimization
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

            // Inline join logic for JIT optimization
            val stringValues = strings.elements.map { element ->
                when (element) {
                    is FunkString -> element.value
                    else -> throw NativeFunctionException("__native_string_join", "all list elements must be strings")
                }
            }

            FunkString(stringValues.joinToString(separator.value))
        }

        // === Case Conversion Functions (for codec field transforms) ===

        // Convert to snake_case: "userName" -> "user_name", "HTTPResponse" -> "http_response"
        context["__native_string_to_snake_case"] = NativeFunkFunction("__native_string_to_snake_case") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException("__native_string_to_snake_case", "expects FunkRecord")
            val s = record.fields["s"] as? FunkString ?: throw NativeFunctionException("__native_string_to_snake_case", "expects 's' as String")
            FunkString(toSnakeCase(s.value))
        }

        // Convert to camelCase: "user_name" -> "userName", "http_response" -> "httpResponse"
        context["__native_string_to_camel_case"] = NativeFunkFunction("__native_string_to_camel_case") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException("__native_string_to_camel_case", "expects FunkRecord")
            val s = record.fields["s"] as? FunkString ?: throw NativeFunctionException("__native_string_to_camel_case", "expects 's' as String")
            FunkString(toCamelCase(s.value))
        }

        // Convert to kebab-case: "userName" -> "user-name"
        context["__native_string_to_kebab_case"] = NativeFunkFunction("__native_string_to_kebab_case") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException("__native_string_to_kebab_case", "expects FunkRecord")
            val s = record.fields["s"] as? FunkString ?: throw NativeFunctionException("__native_string_to_kebab_case", "expects 's' as String")
            FunkString(toKebabCase(s.value))
        }

        // Convert to SCREAMING_SNAKE_CASE: "userName" -> "USER_NAME"
        context["__native_string_to_screaming_snake_case"] = NativeFunkFunction("__native_string_to_screaming_snake_case") { args ->
            val record = args.firstOrNull() as? FunkRecord ?: throw NativeFunctionException("__native_string_to_screaming_snake_case", "expects FunkRecord")
            val s = record.fields["s"] as? FunkString ?: throw NativeFunctionException("__native_string_to_screaming_snake_case", "expects 's' as String")
            FunkString(toSnakeCase(s.value).uppercase())
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
