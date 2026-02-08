package com.funk.language.interpreter.stdlib

import com.funk.language.interpreter.*

/**
 * Native implementations for Char operations.
 *
 * Char represents a single Unicode character (UTF-16 code unit).
 * Operations support character classification, case conversion,
 * and numeric conversions.
 */
object TruffleCharImplementations {

    fun registerAll(context: MutableMap<String, FunkValue>) {
        // === Construction ===

        // __native_char_from_int: {code: Int} -> Option Char
        // Creates a character from a Unicode code point
        context["__native_char_from_int"] = NativeFunkFunction("__native_char_from_int") { args ->
            val record = args[0] as FunkRecord
            val code = (record.fields["code"] as? FunkInt)?.value?.toInt()
                ?: throw NativeFunctionException("__native_char_from_int", "expects 'code' as Int")

            if (code in 0..0xFFFF) {
                FunkVariant("Some", FunkRecord(mapOf("val" to FunkChar(code.toChar()))))
            } else {
                FunkVariant("None", null)
            }
        }

        // __native_char_from_string: {s: String} -> Option Char
        // Gets the first character from a string
        context["__native_char_from_string"] = NativeFunkFunction("__native_char_from_string") { args ->
            val record = args[0] as FunkRecord
            val s = (record.fields["s"] as? FunkString)?.value
                ?: throw NativeFunctionException("__native_char_from_string", "expects 's' as String")

            if (s.isNotEmpty()) {
                FunkVariant("Some", FunkRecord(mapOf("val" to FunkChar(s[0]))))
            } else {
                FunkVariant("None", null)
            }
        }

        // === Conversion ===

        // __native_char_to_int: {c: Char} -> Int
        // Gets the Unicode code point
        context["__native_char_to_int"] = NativeFunkFunction("__native_char_to_int") { args ->
            val record = args[0] as FunkRecord
            val c = (record.fields["c"] as? FunkChar)?.value
                ?: throw NativeFunctionException("__native_char_to_int", "expects 'c' as Char")

            FunkInt(c.code.toLong())
        }

        // __native_char_to_string: {c: Char} -> String
        // Converts character to a single-character string
        context["__native_char_to_string"] = NativeFunkFunction("__native_char_to_string") { args ->
            val record = args[0] as FunkRecord
            val c = (record.fields["c"] as? FunkChar)?.value
                ?: throw NativeFunctionException("__native_char_to_string", "expects 'c' as Char")

            FunkString(c.toString())
        }

        // === Case Conversion ===

        // __native_char_to_upper: {c: Char} -> Char
        context["__native_char_to_upper"] = NativeFunkFunction("__native_char_to_upper") { args ->
            val record = args[0] as FunkRecord
            val c = (record.fields["c"] as? FunkChar)?.value
                ?: throw NativeFunctionException("__native_char_to_upper", "expects 'c' as Char")

            FunkChar(c.uppercaseChar())
        }

        // __native_char_to_lower: {c: Char} -> Char
        context["__native_char_to_lower"] = NativeFunkFunction("__native_char_to_lower") { args ->
            val record = args[0] as FunkRecord
            val c = (record.fields["c"] as? FunkChar)?.value
                ?: throw NativeFunctionException("__native_char_to_lower", "expects 'c' as Char")

            FunkChar(c.lowercaseChar())
        }

        // === Character Classification ===

        // __native_char_is_digit: {c: Char} -> Bool
        context["__native_char_is_digit"] = NativeFunkFunction("__native_char_is_digit") { args ->
            val record = args[0] as FunkRecord
            val c = (record.fields["c"] as? FunkChar)?.value
                ?: throw NativeFunctionException("__native_char_is_digit", "expects 'c' as Char")

            FunkBool(c.isDigit())
        }

        // __native_char_is_letter: {c: Char} -> Bool
        context["__native_char_is_letter"] = NativeFunkFunction("__native_char_is_letter") { args ->
            val record = args[0] as FunkRecord
            val c = (record.fields["c"] as? FunkChar)?.value
                ?: throw NativeFunctionException("__native_char_is_letter", "expects 'c' as Char")

            FunkBool(c.isLetter())
        }

        // __native_char_is_alpha_numeric: {c: Char} -> Bool
        context["__native_char_is_alpha_numeric"] = NativeFunkFunction("__native_char_is_alpha_numeric") { args ->
            val record = args[0] as FunkRecord
            val c = (record.fields["c"] as? FunkChar)?.value
                ?: throw NativeFunctionException("__native_char_is_alpha_numeric", "expects 'c' as Char")

            FunkBool(c.isLetterOrDigit())
        }

        // __native_char_is_whitespace: {c: Char} -> Bool
        context["__native_char_is_whitespace"] = NativeFunkFunction("__native_char_is_whitespace") { args ->
            val record = args[0] as FunkRecord
            val c = (record.fields["c"] as? FunkChar)?.value
                ?: throw NativeFunctionException("__native_char_is_whitespace", "expects 'c' as Char")

            FunkBool(c.isWhitespace())
        }

        // __native_char_is_upper: {c: Char} -> Bool
        context["__native_char_is_upper"] = NativeFunkFunction("__native_char_is_upper") { args ->
            val record = args[0] as FunkRecord
            val c = (record.fields["c"] as? FunkChar)?.value
                ?: throw NativeFunctionException("__native_char_is_upper", "expects 'c' as Char")

            FunkBool(c.isUpperCase())
        }

        // __native_char_is_lower: {c: Char} -> Bool
        context["__native_char_is_lower"] = NativeFunkFunction("__native_char_is_lower") { args ->
            val record = args[0] as FunkRecord
            val c = (record.fields["c"] as? FunkChar)?.value
                ?: throw NativeFunctionException("__native_char_is_lower", "expects 'c' as Char")

            FunkBool(c.isLowerCase())
        }

        // === Comparison ===

        // __native_char_eq: {a: Char, b: Char} -> Bool
        context["__native_char_eq"] = NativeFunkFunction("__native_char_eq") { args ->
            val record = args[0] as FunkRecord
            val a = (record.fields["a"] as? FunkChar)?.value
                ?: throw NativeFunctionException("__native_char_eq", "expects 'a' as Char")
            val b = (record.fields["b"] as? FunkChar)?.value
                ?: throw NativeFunctionException("__native_char_eq", "expects 'b' as Char")

            FunkBool(a == b)
        }

        // __native_char_compare: {a: Char, b: Char} -> Ordering
        context["__native_char_compare"] = NativeFunkFunction("__native_char_compare") { args ->
            val record = args[0] as FunkRecord
            val a = (record.fields["a"] as? FunkChar)?.value
                ?: throw NativeFunctionException("__native_char_compare", "expects 'a' as Char")
            val b = (record.fields["b"] as? FunkChar)?.value
                ?: throw NativeFunctionException("__native_char_compare", "expects 'b' as Char")

            when {
                a < b -> FunkVariant("LT", null)
                a > b -> FunkVariant("GT", null)
                else -> FunkVariant("EQ", null)
            }
        }

        // === Digit Operations ===

        // __native_char_digit_to_int: {c: Char, radix: Int} -> Option Int
        // Converts a digit character to its numeric value in the given radix
        context["__native_char_digit_to_int"] = NativeFunkFunction("__native_char_digit_to_int") { args ->
            val record = args[0] as FunkRecord
            val c = (record.fields["c"] as? FunkChar)?.value
                ?: throw NativeFunctionException("__native_char_digit_to_int", "expects 'c' as Char")
            val radix = (record.fields["radix"] as? FunkInt)?.value?.toInt()
                ?: throw NativeFunctionException("__native_char_digit_to_int", "expects 'radix' as Int")

            val digitValue = c.digitToIntOrNull(radix)
            if (digitValue != null) {
                FunkVariant("Some", FunkRecord(mapOf("val" to FunkInt(digitValue.toLong()))))
            } else {
                FunkVariant("None", null)
            }
        }
    }
}
