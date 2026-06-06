package com.mezze.language.typechecker.types

import com.mezze.language.ast.MezzeType

/**
 * Type signatures for basic numeric and string types:
 * int, float, nat, bool, string, frac
 *
 * Corresponds to stdlib files:
 * - std/int.mz
 * - std/float.mz
 * - std/nat.mz
 * - std/bool.mz
 * - std/str.mz
 * - std/frac.mz
 */
object BasicTypes {
    fun registerAll(context: MutableMap<String, MezzeType>) = natives(context) {
        // =====================================================================
        // INT TYPES
        // =====================================================================

        // Native arithmetic operations for int module
        fn("__native_int_add") { param("l" to int, "r" to int); returns(int) }
        fn("__native_int_subtract") { param("l" to int, "r" to int); returns(int) }
        fn("__native_int_multiply") { param("l" to int, "r" to int); returns(int) }
        fn("__native_int_divide") { param("l" to int, "r" to int); returns(int) }
        fn("__native_int_modulo") { param("l" to int, "r" to int); returns(int) }
        fn("__native_int_neg") { param("n" to int); returns(int) }
        fn("__native_int_to_str") { param("n" to int); returns(str) }
        fn("__native_int_from_string") { param("s" to str); returns(option(int)) }
        fn("__native_int_from_nat") { param("n" to nat); returns(int) }

        // Comparison native functions for Eq/Ord ability impls
        fn("__native_int_eq") { param("l" to int, "r" to int); returns(bool) }
        fn("__native_int_neq") { param("l" to int, "r" to int); returns(bool) }
        fn("__native_int_lt") { param("l" to int, "r" to int); returns(bool) }
        fn("__native_int_le") { param("l" to int, "r" to int); returns(bool) }
        fn("__native_int_gt") { param("l" to int, "r" to int); returns(bool) }
        fn("__native_int_ge") { param("l" to int, "r" to int); returns(bool) }

        // Polymorphic reference equality for opaque types (newtypes wrapping
        // native objects). Both operands must be the same type 'a.
        fn("__native_ref_eq") { val a = tvar("a"); param("l" to a, "r" to a); returns(bool) }
        fn("__native_ref_neq") { val a = tvar("a"); param("l" to a, "r" to a); returns(bool) }

        fn("__native_int_to_frac") { param("n" to int); returns(frac) }
        fn("__native_int_to_float") { param("n" to int); returns(float) }
        fn("__native_int_abs") { param("n" to int); returns(int) }
        fn("__native_int_min") { param("l" to int, "r" to int); returns(int) }
        fn("__native_int_max") { param("l" to int, "r" to int); returns(int) }
        fn("__native_int_clamp") { param("n" to int, "min" to int, "max" to int); returns(int) }
        fn("__native_int_to_nat") { param("n" to int); returns(option(nat)) }

        // Note: __native_int_pow and __native_int_sign are registered in MathCoreTypes.kt

        fn("__native_int_gcd") { param("l" to int, "r" to int); returns(int) }
        fn("__native_int_lcm") { param("l" to int, "r" to int); returns(int) }
        fn("__native_int_is_even") { param("n" to int); returns(bool) }
        fn("__native_int_is_odd") { param("n" to int); returns(bool) }

        // =====================================================================
        // FLOAT TYPES
        // =====================================================================

        // Note: Math functions (__native_float_pow, sqrt, floor, ceil, round) are in MathCoreTypes.kt

        // Float arithmetic for ability impls
        fn("__native_float_add") { param("l" to float, "r" to float); returns(float) }
        fn("__native_float_subtract") { param("l" to float, "r" to float); returns(float) }
        fn("__native_float_multiply") { param("l" to float, "r" to float); returns(float) }
        fn("__native_float_divide") { param("l" to float, "r" to float); returns(float) }
        fn("__native_float_mod") { param("l" to float, "r" to float); returns(float) }
        fn("__native_float_neg") { param("n" to float); returns(float) }

        // Float comparisons for ability impls
        fn("__native_float_eq") { param("l" to float, "r" to float); returns(bool) }
        fn("__native_float_neq") { param("l" to float, "r" to float); returns(bool) }
        fn("__native_float_lt") { param("l" to float, "r" to float); returns(bool) }
        fn("__native_float_le") { param("l" to float, "r" to float); returns(bool) }
        fn("__native_float_gt") { param("l" to float, "r" to float); returns(bool) }
        fn("__native_float_ge") { param("l" to float, "r" to float); returns(bool) }

        fn("__native_float_to_str") { param("n" to float); returns(str) }
        fn("__native_float_from_int") { param("n" to int); returns(float) }
        fn("__native_float_from_nat") { param("n" to nat); returns(float) }
        fn("__native_float_from_string") { param("s" to str); returns(option(float)) }
        fn("__native_float_abs") { param("n" to float); returns(float) }
        fn("__native_float_min") { param("l" to float, "r" to float); returns(float) }
        fn("__native_float_max") { param("l" to float, "r" to float); returns(float) }

        // Float classification
        fn("__native_float_is_nan") { param("n" to float); returns(bool) }
        fn("__native_float_is_infinite") { param("n" to float); returns(bool) }
        fn("__native_float_is_finite") { param("n" to float); returns(bool) }

        // Float sign
        fn("__native_float_sign") { param("n" to float); returns(float) }

        // Float clamp
        fn("__native_float_clamp") { param("n" to float, "min" to float, "max" to float); returns(float) }

        // Float to Int
        fn("__native_float_to_int") { param("n" to float); returns(option(int)) }

        // Note: Math functions (__native_float_ln, log10, exp, is_nan, is_infinite, is_finite, sign)
        // are registered in MathCoreTypes.kt
        // Note: Trig functions (__native_float_sin, cos, tan, asin, acos, atan, atan2)
        // are registered in MathTrigTypes.kt

        // =====================================================================
        // NAT TYPES
        // =====================================================================

        // Nat arithmetic for ability impls
        fn("__native_nat_add") { param("l" to nat, "r" to nat); returns(nat) }
        fn("__native_nat_multiply") { param("l" to nat, "r" to nat); returns(nat) }
        fn("__native_nat_divide") { param("l" to nat, "r" to nat); returns(nat) }
        fn("__native_nat_modulo") { param("l" to nat, "r" to nat); returns(nat) }

        // Nat comparisons for ability impls
        fn("__native_nat_eq") { param("l" to nat, "r" to nat); returns(bool) }
        fn("__native_nat_neq") { param("l" to nat, "r" to nat); returns(bool) }
        fn("__native_nat_lt") { param("l" to nat, "r" to nat); returns(bool) }
        fn("__native_nat_le") { param("l" to nat, "r" to nat); returns(bool) }
        fn("__native_nat_gt") { param("l" to nat, "r" to nat); returns(bool) }
        fn("__native_nat_ge") { param("l" to nat, "r" to nat); returns(bool) }

        // Native nat operations
        fn("__native_nat_subtract_to_int") { param("l" to nat, "r" to nat); returns(int) }
        fn("__native_nat_from_int") { param("n" to int); returns(option(nat)) }
        fn("__native_nat_from_float") { param("n" to float); returns(option(nat)) }
        fn("__native_nat_from_string") { param("s" to str); returns(option(nat)) }
        fn("__native_nat_to_frac") { param("n" to nat); returns(frac) }
        fn("__native_nat_min") { param("l" to nat, "r" to nat); returns(nat) }
        fn("__native_nat_max") { param("l" to nat, "r" to nat); returns(nat) }
        fn("__native_nat_to_int") { param("n" to nat); returns(int) }
        fn("__native_nat_to_str") { param("n" to nat); returns(str) }
        fn("__native_nat_clamp") { param("n" to nat, "min" to nat, "max" to nat); returns(nat) }

        // Note: __native_nat_pow is registered in MathCoreTypes.kt

        // =====================================================================
        // BOOL TYPES
        // =====================================================================

        // Bool comparisons for ability impls
        fn("__native_bool_eq") { param("l" to bool, "r" to bool); returns(bool) }
        fn("__native_bool_neq") { param("l" to bool, "r" to bool); returns(bool) }

        // Native bool operations for stdlib bool module
        fn("__native_bool_and") { param("l" to bool, "r" to bool); returns(bool) }
        fn("__native_bool_or") { param("l" to bool, "r" to bool); returns(bool) }
        fn("__native_bool_not") { param("b" to bool); returns(bool) }
        fn("__native_bool_xor") { param("l" to bool, "r" to bool); returns(bool) }
        fn("__native_bool_to_str") { param("b" to bool); returns(str) }

        // =====================================================================
        // STRING TYPES
        // =====================================================================

        // String comparisons for ability impls
        fn("__native_string_eq") { param("l" to str, "r" to str); returns(bool) }
        fn("__native_string_neq") { param("l" to str, "r" to str); returns(bool) }
        fn("__native_string_lt") { param("l" to str, "r" to str); returns(bool) }
        fn("__native_string_le") { param("l" to str, "r" to str); returns(bool) }
        fn("__native_string_gt") { param("l" to str, "r" to str); returns(bool) }
        fn("__native_string_ge") { param("l" to str, "r" to str); returns(bool) }

        fn("__native_string_to_str") { param("value" to str); returns(str) }

        // Native string operations
        fn("__native_string_concat") { param("l" to str, "r" to str); returns(str) }
        fn("__native_string_size") { param("s" to str); returns(int) }
        fn("__native_string_to_upper") { param("s" to str); returns(str) }
        fn("__native_string_to_lower") { param("s" to str); returns(str) }
        fn("__native_url_encode_component") { param("s" to str); returns(str) }
        fn("__native_url_decode_component") { param("s" to str); returns(str) }
        fn("__native_string_trim") { param("s" to str); returns(str) }
        fn("__native_string_reverse") { param("s" to str); returns(str) }
        fn("__native_string_starts_with") { param("s" to str, "prefix" to str); returns(bool) }
        fn("__native_string_ends_with") { param("s" to str, "suffix" to str); returns(bool) }
        fn("__native_string_contains") { param("s" to str, "substring" to str); returns(bool) }
        fn("__native_string_split") { param("s" to str, "delimiter" to str); returns(seq(str)) }
        fn("__native_string_join") { param("strings" to seq(str), "separator" to str); returns(str) }

        // Case conversion functions (for codec field transforms)
        fn("__native_string_to_snake_case") { param("s" to str); returns(str) }
        fn("__native_string_to_camel_case") { param("s" to str); returns(str) }
        fn("__native_string_to_kebab_case") { param("s" to str); returns(str) }
        fn("__native_string_to_screaming_snake_case") { param("s" to str); returns(str) }

        // Substring
        fn("__native_string_substring") { param("s" to str, "start" to int, "end" to int); returns(str) }

        // Replace
        fn("__native_string_replace") { param("s" to str, "old" to str, "new" to str); returns(str) }
        fn("__native_string_replace_all") { param("s" to str, "old" to str, "new" to str); returns(str) }

        // Character access
        fn("__native_string_char_at") { param("s" to str, "i" to int); returns(option(char)) }

        // Index search
        fn("__native_string_index_of") { param("s" to str, "sub" to str); returns(option(int)) }
        fn("__native_string_last_index_of") { param("s" to str, "sub" to str); returns(option(int)) }

        // Trimming
        fn("__native_string_trim_start") { param("s" to str); returns(str) }
        fn("__native_string_trim_end") { param("s" to str); returns(str) }

        // Splitting utilities
        fn("__native_string_lines") { param("s" to str); returns(seq(str)) }
        fn("__native_string_words") { param("s" to str); returns(seq(str)) }
        fn("__native_string_chars") { param("s" to str); returns(seq(char)) }

        // Padding
        fn("__native_string_pad_left") { param("s" to str, "len" to int, "c" to char); returns(str) }
        fn("__native_string_pad_right") { param("s" to str, "len" to int, "c" to char); returns(str) }

        // Glob pattern matching
        fn("__native_string_glob_match") { param("pattern" to str, "s" to str); returns(bool) }

        // =====================================================================
        // FRAC TYPES
        // =====================================================================

        fn("__native_frac_add") { param("l" to frac, "r" to frac); returns(frac) }
        fn("__native_frac_subtract") { param("l" to frac, "r" to frac); returns(frac) }
        fn("__native_frac_multiply") { param("l" to frac, "r" to frac); returns(frac) }
        fn("__native_frac_divide") { param("l" to frac, "r" to frac); returns(frac) }
        fn("__native_frac_equals") { param("l" to frac, "r" to frac); returns(bool) }
        fn("__native_frac_less_than") { param("l" to frac, "r" to frac); returns(bool) }
        fn("__native_frac_greater_than") { param("l" to frac, "r" to frac); returns(bool) }

        // Ordering comparison type
        fn("__native_frac_compare") {
            param("l" to frac, "r" to frac)
            returns(MezzeType.VariantType(mapOf(
                "LT" to null,
                "EQ" to null,
                "GT" to null
            )))
        }

        fn("__native_frac_to_float") { param("n" to frac); returns(float) }
        fn("__native_frac_to_str") { param("n" to frac); returns(str) }
        fn("__native_make_frac") { param("numerator" to int, "denominator" to int); returns(frac) }
        fn("__native_frac_numerator") { param("n" to frac); returns(int) }
        fn("__native_frac_denominator") { param("n" to frac); returns(int) }
        fn("__native_reduce_frac") { param("n" to frac); returns(frac) }
        fn("__native_abs_frac") { param("n" to frac); returns(frac) }
        fn("__native_negate_frac") { param("n" to frac); returns(frac) }
    }
}
