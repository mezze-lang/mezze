package com.funk.language.typechecker.types

import com.funk.language.ast.FunkType

/**
 * Helper function to create Option type (as TypeApplication)
 */
private fun optionType(elementType: FunkType): FunkType {
    return FunkType.TypeApplication("Option", listOf(elementType))
}

/**
 * Type signatures for Char operations.
 *
 * Char represents a single Unicode character.
 * Operations include construction, conversion, classification, and comparison.
 */
object CharTypes {
    fun registerAll(context: MutableMap<String, FunkType>) {
        registerConstructionTypes(context)
        registerConversionTypes(context)
        registerCaseConversionTypes(context)
        registerClassificationTypes(context)
        registerComparisonTypes(context)
        registerDigitTypes(context)
    }

    private fun registerConstructionTypes(context: MutableMap<String, FunkType>) {
        // __native_char_from_int: {code: Int} -> Option Char
        context["__native_char_from_int"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("code" to FunkType.IntType)),
            optionType(FunkType.CharType)
        )

        // __native_char_from_string: {s: String} -> Option Char
        context["__native_char_from_string"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to FunkType.StringType)),
            optionType(FunkType.CharType)
        )
    }

    private fun registerConversionTypes(context: MutableMap<String, FunkType>) {
        // __native_char_to_int: {c: Char} -> Int
        context["__native_char_to_int"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("c" to FunkType.CharType)),
            FunkType.IntType
        )

        // __native_char_to_string: {c: Char} -> String
        context["__native_char_to_string"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("c" to FunkType.CharType)),
            FunkType.StringType
        )
    }

    private fun registerCaseConversionTypes(context: MutableMap<String, FunkType>) {
        // __native_char_to_upper: {c: Char} -> Char
        context["__native_char_to_upper"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("c" to FunkType.CharType)),
            FunkType.CharType
        )

        // __native_char_to_lower: {c: Char} -> Char
        context["__native_char_to_lower"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("c" to FunkType.CharType)),
            FunkType.CharType
        )
    }

    private fun registerClassificationTypes(context: MutableMap<String, FunkType>) {
        // __native_char_is_digit: {c: Char} -> Bool
        context["__native_char_is_digit"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("c" to FunkType.CharType)),
            FunkType.BooleanType
        )

        // __native_char_is_letter: {c: Char} -> Bool
        context["__native_char_is_letter"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("c" to FunkType.CharType)),
            FunkType.BooleanType
        )

        // __native_char_is_alpha_numeric: {c: Char} -> Bool
        context["__native_char_is_alpha_numeric"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("c" to FunkType.CharType)),
            FunkType.BooleanType
        )

        // __native_char_is_whitespace: {c: Char} -> Bool
        context["__native_char_is_whitespace"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("c" to FunkType.CharType)),
            FunkType.BooleanType
        )

        // __native_char_is_upper: {c: Char} -> Bool
        context["__native_char_is_upper"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("c" to FunkType.CharType)),
            FunkType.BooleanType
        )

        // __native_char_is_lower: {c: Char} -> Bool
        context["__native_char_is_lower"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("c" to FunkType.CharType)),
            FunkType.BooleanType
        )
    }

    private fun registerComparisonTypes(context: MutableMap<String, FunkType>) {
        // __native_char_eq: {a: Char, b: Char} -> Bool
        context["__native_char_eq"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("a" to FunkType.CharType, "b" to FunkType.CharType)),
            FunkType.BooleanType
        )

        // __native_char_compare: {a: Char, b: Char} -> Ordering
        context["__native_char_compare"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("a" to FunkType.CharType, "b" to FunkType.CharType)),
            FunkType.OrderingType
        )
    }

    private fun registerDigitTypes(context: MutableMap<String, FunkType>) {
        // __native_char_digit_to_int: {c: Char, radix: Int} -> Option Int
        context["__native_char_digit_to_int"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("c" to FunkType.CharType, "radix" to FunkType.IntType)),
            optionType(FunkType.IntType)
        )
    }
}
