package com.mezze.language.typechecker.types

import com.mezze.language.ast.MezzeType

object CharTypes {
    fun registerAll(context: MutableMap<String, MezzeType>) = natives(context) {
        // Construction
        fn("__native_char_from_int") { param("code" to int); returns(option(char)) }
        fn("__native_char_from_string") { param("s" to str); returns(option(char)) }

        // Conversion
        fn("__native_char_to_int") { param("c" to char); returns(int) }
        fn("__native_char_to_str") { param("c" to char); returns(str) }

        // Case conversion
        fn("__native_char_to_upper") { param("c" to char); returns(char) }
        fn("__native_char_to_lower") { param("c" to char); returns(char) }

        // Classification
        fn("__native_char_is_digit") { param("c" to char); returns(bool) }
        fn("__native_char_is_letter") { param("c" to char); returns(bool) }
        fn("__native_char_is_alpha_numeric") { param("c" to char); returns(bool) }
        fn("__native_char_is_whitespace") { param("c" to char); returns(bool) }
        fn("__native_char_is_upper") { param("c" to char); returns(bool) }
        fn("__native_char_is_lower") { param("c" to char); returns(bool) }

        // Comparison
        fn("__native_char_eq") { param("a" to char, "b" to char); returns(bool) }
        fn("__native_char_compare") { param("a" to char, "b" to char); returns(ordering) }
        fn("__native_char_compare_int") { param("a" to char, "b" to char); returns(int) }

        // Digit
        fn("__native_char_digit_to_int") { param("c" to char, "radix" to int); returns(option(int)) }
    }
}
