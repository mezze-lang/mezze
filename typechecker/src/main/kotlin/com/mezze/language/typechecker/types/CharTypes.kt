package com.mezze.language.typechecker.types

import com.mezze.language.ast.MezzeType

/**
 * Helper function to create Option type (as TypeApplication)
 */
private fun optionType(elementType: MezzeType): MezzeType {
    return MezzeType.TypeApplication("Option", listOf(elementType))
}

/**
 * Type signatures for Char operations.
 *
 * Char represents a single Unicode character.
 * Operations include construction, conversion, classification, and comparison.
 */
object CharTypes {
    fun registerAll(context: MutableMap<String, MezzeType>) {
        registerConstructionTypes(context)
        registerConversionTypes(context)
        registerCaseConversionTypes(context)
        registerClassificationTypes(context)
        registerComparisonTypes(context)
        registerDigitTypes(context)
    }

    private fun registerConstructionTypes(context: MutableMap<String, MezzeType>) {
        // __native_char_from_int: {code: Int} -> Option Char
        context["__native_char_from_int"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("code" to MezzeType.IntType)),
            optionType(MezzeType.CharType)
        )

        // __native_char_from_string: {s: String} -> Option Char
        context["__native_char_from_string"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.StringType)),
            optionType(MezzeType.CharType)
        )
    }

    private fun registerConversionTypes(context: MutableMap<String, MezzeType>) {
        // __native_char_to_int: {c: Char} -> Int
        context["__native_char_to_int"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("c" to MezzeType.CharType)),
            MezzeType.IntType
        )

        // __native_char_to_str: {c: Char} -> String
        context["__native_char_to_str"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("c" to MezzeType.CharType)),
            MezzeType.StringType
        )
    }

    private fun registerCaseConversionTypes(context: MutableMap<String, MezzeType>) {
        // __native_char_to_upper: {c: Char} -> Char
        context["__native_char_to_upper"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("c" to MezzeType.CharType)),
            MezzeType.CharType
        )

        // __native_char_to_lower: {c: Char} -> Char
        context["__native_char_to_lower"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("c" to MezzeType.CharType)),
            MezzeType.CharType
        )
    }

    private fun registerClassificationTypes(context: MutableMap<String, MezzeType>) {
        // __native_char_is_digit: {c: Char} -> Bool
        context["__native_char_is_digit"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("c" to MezzeType.CharType)),
            MezzeType.BoolType
        )

        // __native_char_is_letter: {c: Char} -> Bool
        context["__native_char_is_letter"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("c" to MezzeType.CharType)),
            MezzeType.BoolType
        )

        // __native_char_is_alpha_numeric: {c: Char} -> Bool
        context["__native_char_is_alpha_numeric"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("c" to MezzeType.CharType)),
            MezzeType.BoolType
        )

        // __native_char_is_whitespace: {c: Char} -> Bool
        context["__native_char_is_whitespace"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("c" to MezzeType.CharType)),
            MezzeType.BoolType
        )

        // __native_char_is_upper: {c: Char} -> Bool
        context["__native_char_is_upper"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("c" to MezzeType.CharType)),
            MezzeType.BoolType
        )

        // __native_char_is_lower: {c: Char} -> Bool
        context["__native_char_is_lower"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("c" to MezzeType.CharType)),
            MezzeType.BoolType
        )
    }

    private fun registerComparisonTypes(context: MutableMap<String, MezzeType>) {
        // __native_char_eq: {a: Char, b: Char} -> Bool
        context["__native_char_eq"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("a" to MezzeType.CharType, "b" to MezzeType.CharType)),
            MezzeType.BoolType
        )

        // __native_char_compare: {a: Char, b: Char} -> Ordering
        context["__native_char_compare"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("a" to MezzeType.CharType, "b" to MezzeType.CharType)),
            MezzeType.OrderingType
        )

        // __native_char_compare_int: {a: Char, b: Char} -> Int
        context["__native_char_compare_int"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("a" to MezzeType.CharType, "b" to MezzeType.CharType)),
            MezzeType.IntType
        )
    }

    private fun registerDigitTypes(context: MutableMap<String, MezzeType>) {
        // __native_char_digit_to_int: {c: Char, radix: Int} -> Option Int
        context["__native_char_digit_to_int"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("c" to MezzeType.CharType, "radix" to MezzeType.IntType)),
            optionType(MezzeType.IntType)
        )
    }
}
