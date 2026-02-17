package com.mezze.language.interpreter.stdlib

import com.mezze.language.interpreter.*

/**
 * Native implementations for Char operations.
 *
 * Char represents a single Unicode character (UTF-16 code unit).
 * Operations support character classification, case conversion,
 * and numeric conversions.
 */
object TruffleCharImplementations {

    fun registerAll(context: MutableMap<String, MezzeValue>) {
        // === Construction ===

        // __native_char_from_int: {code: Int} -> Option Char
        // Creates a character from a Unicode code point
        context["__native_char_from_int"] = NativeMezzeFunction("__native_char_from_int") { args ->
            val record = args[0] as MezzeRecord
            val code = (record.fields["code"] as? MezzeInt)?.value?.toInt()
                ?: throw NativeFunctionException("__native_char_from_int", "expects 'code' as Int")

            if (code in 0..0xFFFF) {
                MezzeVariant("Some", MezzeRecord(mapOf("val" to MezzeChar(code.toChar()))))
            } else {
                MezzeVariant("None", null)
            }
        }

        // __native_char_from_string: {s: String} -> Option Char
        // Gets the first character from a string
        context["__native_char_from_string"] = NativeMezzeFunction("__native_char_from_string") { args ->
            val record = args[0] as MezzeRecord
            val s = (record.fields["s"] as? MezzeString)?.value
                ?: throw NativeFunctionException("__native_char_from_string", "expects 's' as String")

            if (s.isNotEmpty()) {
                MezzeVariant("Some", MezzeRecord(mapOf("val" to MezzeChar(s[0]))))
            } else {
                MezzeVariant("None", null)
            }
        }

        // === Conversion ===

        // __native_char_to_int: {c: Char} -> Int
        // Gets the Unicode code point
        context["__native_char_to_int"] = NativeMezzeFunction("__native_char_to_int") { args ->
            val record = args[0] as MezzeRecord
            val c = (record.fields["c"] as? MezzeChar)?.value
                ?: throw NativeFunctionException("__native_char_to_int", "expects 'c' as Char")

            MezzeInt(c.code.toLong())
        }

        // __native_char_to_string: {c: Char} -> String
        // Converts character to a single-character string
        context["__native_char_to_string"] = NativeMezzeFunction("__native_char_to_string") { args ->
            val record = args[0] as MezzeRecord
            val c = (record.fields["c"] as? MezzeChar)?.value
                ?: throw NativeFunctionException("__native_char_to_string", "expects 'c' as Char")

            MezzeString(c.toString())
        }

        // === Case Conversion ===

        // __native_char_to_upper: {c: Char} -> Char
        context["__native_char_to_upper"] = NativeMezzeFunction("__native_char_to_upper") { args ->
            val record = args[0] as MezzeRecord
            val c = (record.fields["c"] as? MezzeChar)?.value
                ?: throw NativeFunctionException("__native_char_to_upper", "expects 'c' as Char")

            MezzeChar(c.uppercaseChar())
        }

        // __native_char_to_lower: {c: Char} -> Char
        context["__native_char_to_lower"] = NativeMezzeFunction("__native_char_to_lower") { args ->
            val record = args[0] as MezzeRecord
            val c = (record.fields["c"] as? MezzeChar)?.value
                ?: throw NativeFunctionException("__native_char_to_lower", "expects 'c' as Char")

            MezzeChar(c.lowercaseChar())
        }

        // === Character Classification ===

        // __native_char_is_digit: {c: Char} -> Bool
        context["__native_char_is_digit"] = NativeMezzeFunction("__native_char_is_digit") { args ->
            val record = args[0] as MezzeRecord
            val c = (record.fields["c"] as? MezzeChar)?.value
                ?: throw NativeFunctionException("__native_char_is_digit", "expects 'c' as Char")

            MezzeBool(c.isDigit())
        }

        // __native_char_is_letter: {c: Char} -> Bool
        context["__native_char_is_letter"] = NativeMezzeFunction("__native_char_is_letter") { args ->
            val record = args[0] as MezzeRecord
            val c = (record.fields["c"] as? MezzeChar)?.value
                ?: throw NativeFunctionException("__native_char_is_letter", "expects 'c' as Char")

            MezzeBool(c.isLetter())
        }

        // __native_char_is_alpha_numeric: {c: Char} -> Bool
        context["__native_char_is_alpha_numeric"] = NativeMezzeFunction("__native_char_is_alpha_numeric") { args ->
            val record = args[0] as MezzeRecord
            val c = (record.fields["c"] as? MezzeChar)?.value
                ?: throw NativeFunctionException("__native_char_is_alpha_numeric", "expects 'c' as Char")

            MezzeBool(c.isLetterOrDigit())
        }

        // __native_char_is_whitespace: {c: Char} -> Bool
        context["__native_char_is_whitespace"] = NativeMezzeFunction("__native_char_is_whitespace") { args ->
            val record = args[0] as MezzeRecord
            val c = (record.fields["c"] as? MezzeChar)?.value
                ?: throw NativeFunctionException("__native_char_is_whitespace", "expects 'c' as Char")

            MezzeBool(c.isWhitespace())
        }

        // __native_char_is_upper: {c: Char} -> Bool
        context["__native_char_is_upper"] = NativeMezzeFunction("__native_char_is_upper") { args ->
            val record = args[0] as MezzeRecord
            val c = (record.fields["c"] as? MezzeChar)?.value
                ?: throw NativeFunctionException("__native_char_is_upper", "expects 'c' as Char")

            MezzeBool(c.isUpperCase())
        }

        // __native_char_is_lower: {c: Char} -> Bool
        context["__native_char_is_lower"] = NativeMezzeFunction("__native_char_is_lower") { args ->
            val record = args[0] as MezzeRecord
            val c = (record.fields["c"] as? MezzeChar)?.value
                ?: throw NativeFunctionException("__native_char_is_lower", "expects 'c' as Char")

            MezzeBool(c.isLowerCase())
        }

        // === Comparison ===

        // __native_char_eq: {a: Char, b: Char} -> Bool
        context["__native_char_eq"] = NativeMezzeFunction("__native_char_eq") { args ->
            val record = args[0] as MezzeRecord
            val a = (record.fields["a"] as? MezzeChar)?.value
                ?: throw NativeFunctionException("__native_char_eq", "expects 'a' as Char")
            val b = (record.fields["b"] as? MezzeChar)?.value
                ?: throw NativeFunctionException("__native_char_eq", "expects 'b' as Char")

            MezzeBool(a == b)
        }

        // __native_char_compare: {a: Char, b: Char} -> Ordering
        context["__native_char_compare"] = NativeMezzeFunction("__native_char_compare") { args ->
            val record = args[0] as MezzeRecord
            val a = (record.fields["a"] as? MezzeChar)?.value
                ?: throw NativeFunctionException("__native_char_compare", "expects 'a' as Char")
            val b = (record.fields["b"] as? MezzeChar)?.value
                ?: throw NativeFunctionException("__native_char_compare", "expects 'b' as Char")

            when {
                a < b -> MezzeVariant("LT", null)
                a > b -> MezzeVariant("GT", null)
                else -> MezzeVariant("EQ", null)
            }
        }

        // === Digit Operations ===

        // __native_char_digit_to_int: {c: Char, radix: Int} -> Option Int
        // Converts a digit character to its numeric value in the given radix
        context["__native_char_digit_to_int"] = NativeMezzeFunction("__native_char_digit_to_int") { args ->
            val record = args[0] as MezzeRecord
            val c = (record.fields["c"] as? MezzeChar)?.value
                ?: throw NativeFunctionException("__native_char_digit_to_int", "expects 'c' as Char")
            val radix = (record.fields["radix"] as? MezzeInt)?.value?.toInt()
                ?: throw NativeFunctionException("__native_char_digit_to_int", "expects 'radix' as Int")

            val digitValue = c.digitToIntOrNull(radix)
            if (digitValue != null) {
                MezzeVariant("Some", MezzeRecord(mapOf("val" to MezzeInt(digitValue.toLong()))))
            } else {
                MezzeVariant("None", null)
            }
        }
    }
}
