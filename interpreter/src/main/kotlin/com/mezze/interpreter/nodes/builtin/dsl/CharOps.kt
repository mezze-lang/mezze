package com.mezze.interpreter.nodes.builtin.dsl

import com.mezze.interpreter.nodes.builtin.primitives.CharNatives
import com.mezze.language.truffleir.SlotKind

/**
 * Char natives in DSL form.
 *
 * Mezze char is a single-char `String` at runtime — argument kind is
 * OBJECT (the underlying String reference), accessed via `args.string(i)`.
 *
 * Excluded:
 *  - `__native_char_to_mut` / `__native_char_to_immut` — Mut bridging.
 *  - `__native_char_from_string` — returns Option<Char>; cold path,
 *    kept on legacy with the rest of the parsing helpers.
 */
object CharOps {
    /** Forces class load when referenced; used by NativeBootstrap. */
    @JvmField val MARKER: Unit = Unit

    // ==================== Char -> Bool predicates ====================
    private val IS_LETTER = NativeOp("__native_char_is_letter",
        listOf(Param("c", SlotKind.OBJECT)), SlotKind.BOOLEAN
    ) { args -> CharNatives.isLetter(args.string(0)) }

    private val IS_DIGIT = NativeOp("__native_char_is_digit",
        listOf(Param("c", SlotKind.OBJECT)), SlotKind.BOOLEAN
    ) { args -> CharNatives.isDigit(args.string(0)) }

    private val IS_ALPHA_NUMERIC = NativeOp("__native_char_is_alpha_numeric",
        listOf(Param("c", SlotKind.OBJECT)), SlotKind.BOOLEAN
    ) { args -> CharNatives.isAlphaNumeric(args.string(0)) }

    private val IS_WHITESPACE = NativeOp("__native_char_is_whitespace",
        listOf(Param("c", SlotKind.OBJECT)), SlotKind.BOOLEAN
    ) { args -> CharNatives.isWhitespace(args.string(0)) }

    private val IS_UPPER = NativeOp("__native_char_is_upper",
        listOf(Param("c", SlotKind.OBJECT)), SlotKind.BOOLEAN
    ) { args -> CharNatives.isUpper(args.string(0)) }

    private val IS_LOWER = NativeOp("__native_char_is_lower",
        listOf(Param("c", SlotKind.OBJECT)), SlotKind.BOOLEAN
    ) { args -> CharNatives.isLower(args.string(0)) }

    // ==================== Char -> Char ====================
    private val TO_UPPER = NativeOp("__native_char_to_upper",
        listOf(Param("c", SlotKind.OBJECT)), SlotKind.OBJECT
    ) { args -> CharNatives.toUpper(args.string(0)) }

    private val TO_LOWER = NativeOp("__native_char_to_lower",
        listOf(Param("c", SlotKind.OBJECT)), SlotKind.OBJECT
    ) { args -> CharNatives.toLower(args.string(0)) }

    // ==================== conversions ====================
    private val FROM_INT = NativeOp("__native_char_from_int",
        listOf(Param("code", SlotKind.LONG)), SlotKind.OBJECT
    ) { args -> CharNatives.fromInt(args.long(0)) }

    private val TO_INT = NativeOp("__native_char_to_int",
        listOf(Param("c", SlotKind.OBJECT)), SlotKind.LONG
    ) { args -> CharNatives.toInt(args.string(0)) }

    // to_str is identity at runtime (Char IS a single-char String).
    private val TO_STR = NativeOp("__native_char_to_str",
        listOf(Param("c", SlotKind.OBJECT)), SlotKind.OBJECT
    ) { args -> args.string(0) }

    // ==================== Char, Char -> Bool / Int ====================
    private val EQ = NativeOp("__native_char_eq",
        listOf(Param("a", SlotKind.OBJECT), Param("b", SlotKind.OBJECT)), SlotKind.BOOLEAN
    ) { args -> CharNatives.eq(args.string(0), args.string(1)) }

    // CharNatives.compare returns Ordering (MezzeVariant); compareInt
    // returns a plain Int. Both are exposed.
    private val COMPARE = NativeOp("__native_char_compare",
        listOf(Param("a", SlotKind.OBJECT), Param("b", SlotKind.OBJECT)), SlotKind.OBJECT
    ) { args -> CharNatives.compare(args.string(0), args.string(1)) }

    private val COMPARE_INT = NativeOp("__native_char_compare_int",
        listOf(Param("a", SlotKind.OBJECT), Param("b", SlotKind.OBJECT)), SlotKind.LONG
    ) { args -> CharNatives.compareInt(args.string(0), args.string(1)) }

    // digit_to_int returns Option<Int>.
    private val DIGIT_TO_INT = NativeOp("__native_char_digit_to_int",
        listOf(Param("c", SlotKind.OBJECT), Param("radix", SlotKind.LONG)), SlotKind.OBJECT
    ) { args -> CharNatives.digitToInt(args.string(0), args.long(1)) }
}
