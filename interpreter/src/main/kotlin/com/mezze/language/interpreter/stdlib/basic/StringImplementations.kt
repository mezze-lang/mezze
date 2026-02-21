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

    /**
     * Register additional String utility functions (Phase 2)
     */
    fun registerUtilities(context: MutableMap<String, MezzeValue>) {
        // === Substring and Slicing ===

        // Get substring from start (inclusive) to end (exclusive)
        // Returns empty string if indices are out of bounds or invalid
        context["__native_string_substring"] = NativeMezzeFunction("__native_string_substring") { args ->
            val record = args.firstOrNull() as? MezzeRecord ?: throw NativeFunctionException("__native_string_substring", "expects MezzeRecord")
            val s = record.fields["s"] as? MezzeString ?: throw NativeFunctionException("__native_string_substring", "expects 's' as String")
            val start = record.fields["start"] as? MezzeInt ?: throw NativeFunctionException("__native_string_substring", "expects 'start' as Int")
            val end = record.fields["end"] as? MezzeInt ?: throw NativeFunctionException("__native_string_substring", "expects 'end' as Int")

            val startIdx = start.value.toInt().coerceIn(0, s.value.length)
            val endIdx = end.value.toInt().coerceIn(startIdx, s.value.length)
            MezzeString(s.value.substring(startIdx, endIdx))
        }

        // === Replace Operations ===

        // Replace first occurrence of old with new
        context["__native_string_replace"] = NativeMezzeFunction("__native_string_replace") { args ->
            val record = args.firstOrNull() as? MezzeRecord ?: throw NativeFunctionException("__native_string_replace", "expects MezzeRecord")
            val s = record.fields["s"] as? MezzeString ?: throw NativeFunctionException("__native_string_replace", "expects 's' as String")
            val old = record.fields["old"] as? MezzeString ?: throw NativeFunctionException("__native_string_replace", "expects 'old' as String")
            val new = record.fields["new"] as? MezzeString ?: throw NativeFunctionException("__native_string_replace", "expects 'new' as String")
            MezzeString(s.value.replaceFirst(old.value, new.value))
        }

        // Replace all occurrences of old with new
        context["__native_string_replace_all"] = NativeMezzeFunction("__native_string_replace_all") { args ->
            val record = args.firstOrNull() as? MezzeRecord ?: throw NativeFunctionException("__native_string_replace_all", "expects MezzeRecord")
            val s = record.fields["s"] as? MezzeString ?: throw NativeFunctionException("__native_string_replace_all", "expects 's' as String")
            val old = record.fields["old"] as? MezzeString ?: throw NativeFunctionException("__native_string_replace_all", "expects 'old' as String")
            val new = record.fields["new"] as? MezzeString ?: throw NativeFunctionException("__native_string_replace_all", "expects 'new' as String")
            MezzeString(s.value.replace(old.value, new.value))
        }

        // === Character Access ===

        // Get character at index, returns Option Char
        context["__native_string_char_at"] = NativeMezzeFunction("__native_string_char_at") { args ->
            val record = args.firstOrNull() as? MezzeRecord ?: throw NativeFunctionException("__native_string_char_at", "expects MezzeRecord")
            val s = record.fields["s"] as? MezzeString ?: throw NativeFunctionException("__native_string_char_at", "expects 's' as String")
            val i = record.fields["i"] as? MezzeInt ?: throw NativeFunctionException("__native_string_char_at", "expects 'i' as Int")

            val idx = i.value.toInt()
            if (idx >= 0 && idx < s.value.length) {
                MezzeVariant("Some", MezzeRecord(mapOf("val" to MezzeChar(s.value[idx]))))
            } else {
                MezzeVariant("None", null)
            }
        }

        // === Index Search ===

        // Find first index of substring, returns Option Int
        context["__native_string_index_of"] = NativeMezzeFunction("__native_string_index_of") { args ->
            val record = args.firstOrNull() as? MezzeRecord ?: throw NativeFunctionException("__native_string_index_of", "expects MezzeRecord")
            val s = record.fields["s"] as? MezzeString ?: throw NativeFunctionException("__native_string_index_of", "expects 's' as String")
            val sub = record.fields["sub"] as? MezzeString ?: throw NativeFunctionException("__native_string_index_of", "expects 'sub' as String")

            val idx = s.value.indexOf(sub.value)
            if (idx >= 0) {
                MezzeVariant("Some", MezzeRecord(mapOf("val" to MezzeInt(idx.toLong()))))
            } else {
                MezzeVariant("None", null)
            }
        }

        // Find last index of substring, returns Option Int
        context["__native_string_last_index_of"] = NativeMezzeFunction("__native_string_last_index_of") { args ->
            val record = args.firstOrNull() as? MezzeRecord ?: throw NativeFunctionException("__native_string_last_index_of", "expects MezzeRecord")
            val s = record.fields["s"] as? MezzeString ?: throw NativeFunctionException("__native_string_last_index_of", "expects 's' as String")
            val sub = record.fields["sub"] as? MezzeString ?: throw NativeFunctionException("__native_string_last_index_of", "expects 'sub' as String")

            val idx = s.value.lastIndexOf(sub.value)
            if (idx >= 0) {
                MezzeVariant("Some", MezzeRecord(mapOf("val" to MezzeInt(idx.toLong()))))
            } else {
                MezzeVariant("None", null)
            }
        }

        // === Trimming ===

        // Trim leading whitespace
        context["__native_string_trim_start"] = NativeMezzeFunction("__native_string_trim_start") { args ->
            val record = args.firstOrNull() as? MezzeRecord ?: throw NativeFunctionException("__native_string_trim_start", "expects MezzeRecord")
            val s = record.fields["s"] as? MezzeString ?: throw NativeFunctionException("__native_string_trim_start", "expects 's' as String")
            MezzeString(s.value.trimStart())
        }

        // Trim trailing whitespace
        context["__native_string_trim_end"] = NativeMezzeFunction("__native_string_trim_end") { args ->
            val record = args.firstOrNull() as? MezzeRecord ?: throw NativeFunctionException("__native_string_trim_end", "expects MezzeRecord")
            val s = record.fields["s"] as? MezzeString ?: throw NativeFunctionException("__native_string_trim_end", "expects 's' as String")
            MezzeString(s.value.trimEnd())
        }

        // === Splitting Utilities ===

        // Split by newlines
        context["__native_string_lines"] = NativeMezzeFunction("__native_string_lines") { args ->
            val record = args.firstOrNull() as? MezzeRecord ?: throw NativeFunctionException("__native_string_lines", "expects MezzeRecord")
            val s = record.fields["s"] as? MezzeString ?: throw NativeFunctionException("__native_string_lines", "expects 's' as String")
            MezzeArrayList(s.value.lines().map { MezzeString(it) })
        }

        // Split by whitespace
        context["__native_string_words"] = NativeMezzeFunction("__native_string_words") { args ->
            val record = args.firstOrNull() as? MezzeRecord ?: throw NativeFunctionException("__native_string_words", "expects MezzeRecord")
            val s = record.fields["s"] as? MezzeString ?: throw NativeFunctionException("__native_string_words", "expects 's' as String")
            MezzeArrayList(s.value.split(Regex("\\s+")).filter { it.isNotEmpty() }.map { MezzeString(it) })
        }

        // Convert to list of characters
        context["__native_string_chars"] = NativeMezzeFunction("__native_string_chars") { args ->
            val record = args.firstOrNull() as? MezzeRecord ?: throw NativeFunctionException("__native_string_chars", "expects MezzeRecord")
            val s = record.fields["s"] as? MezzeString ?: throw NativeFunctionException("__native_string_chars", "expects 's' as String")
            MezzeArrayList(s.value.map { MezzeChar(it) })
        }

        // === Padding ===

        // Pad string on the left to reach target length
        context["__native_string_pad_left"] = NativeMezzeFunction("__native_string_pad_left") { args ->
            val record = args.firstOrNull() as? MezzeRecord ?: throw NativeFunctionException("__native_string_pad_left", "expects MezzeRecord")
            val s = record.fields["s"] as? MezzeString ?: throw NativeFunctionException("__native_string_pad_left", "expects 's' as String")
            val len = record.fields["len"] as? MezzeInt ?: throw NativeFunctionException("__native_string_pad_left", "expects 'len' as Int")
            val c = record.fields["c"] as? MezzeChar ?: throw NativeFunctionException("__native_string_pad_left", "expects 'c' as Char")

            val targetLen = len.value.toInt()
            if (s.value.length >= targetLen) {
                s
            } else {
                val padding = c.value.toString().repeat(targetLen - s.value.length)
                MezzeString(padding + s.value)
            }
        }

        // Pad string on the right to reach target length
        context["__native_string_pad_right"] = NativeMezzeFunction("__native_string_pad_right") { args ->
            val record = args.firstOrNull() as? MezzeRecord ?: throw NativeFunctionException("__native_string_pad_right", "expects MezzeRecord")
            val s = record.fields["s"] as? MezzeString ?: throw NativeFunctionException("__native_string_pad_right", "expects 's' as String")
            val len = record.fields["len"] as? MezzeInt ?: throw NativeFunctionException("__native_string_pad_right", "expects 'len' as Int")
            val c = record.fields["c"] as? MezzeChar ?: throw NativeFunctionException("__native_string_pad_right", "expects 'c' as Char")

            val targetLen = len.value.toInt()
            if (s.value.length >= targetLen) {
                s
            } else {
                val padding = c.value.toString().repeat(targetLen - s.value.length)
                MezzeString(s.value + padding)
            }
        }
    }
}
