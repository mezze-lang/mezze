package com.mezze.interpreter.nodes.builtin.dsl

import com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
import com.mezze.interpreter.values.MezzeVariant
import com.mezze.language.truffleir.SlotKind

/**
 * String natives in DSL form.
 *
 * Excluded (kept on the legacy record path):
 *  - Ops with complex return shapes or cross-cutting helpers
 *    (split, join, pad_left/right, lines, words, chars,
 *    to_snake_case, to_camel_case, glob_match, with_capacity)
 *    — duplicating those here risks silent drift.
 *  - `__native_string_to_mut` — Mut bridging.
 */
object StringOps {
    /** Forces class load when referenced; used by NativeBootstrap. */
    @JvmField val MARKER: Unit = Unit

    // ==================== String -> Int / Bool ====================
    private val SIZE = NativeOp("__native_string_size",
        listOf(Param("s", SlotKind.OBJECT)), SlotKind.LONG, pure = true,
    ) { args -> PrimitiveNatives.stringLength(args.string(0)).toLong() }

    // ==================== String -> String ====================
    private val TO_UPPER = NativeOp("__native_string_to_upper",
        listOf(Param("s", SlotKind.OBJECT)), SlotKind.OBJECT, pure = true,
    ) { args -> PrimitiveNatives.stringToUpperCase(args.string(0)) }

    private val TO_LOWER = NativeOp("__native_string_to_lower",
        listOf(Param("s", SlotKind.OBJECT)), SlotKind.OBJECT, pure = true,
    ) { args -> PrimitiveNatives.stringToLowerCase(args.string(0)) }

    private val TRIM = NativeOp("__native_string_trim",
        listOf(Param("s", SlotKind.OBJECT)), SlotKind.OBJECT, pure = true,
    ) { args -> PrimitiveNatives.stringTrim(args.string(0)) }

    private val TRIM_START = NativeOp("__native_string_trim_start",
        listOf(Param("s", SlotKind.OBJECT)), SlotKind.OBJECT, pure = true,
    ) { args -> args.string(0).trimStart() }

    private val TRIM_END = NativeOp("__native_string_trim_end",
        listOf(Param("s", SlotKind.OBJECT)), SlotKind.OBJECT, pure = true,
    ) { args -> args.string(0).trimEnd() }

    private val REVERSE = NativeOp("__native_string_reverse",
        listOf(Param("s", SlotKind.OBJECT)), SlotKind.OBJECT, pure = true,
    ) { args -> StringBuilder(args.string(0)).reverse().toString() }

    // ==================== String, String -> String ====================
    private val CONCAT = NativeOp("__native_string_concat",
        listOf(Param("l", SlotKind.OBJECT), Param("r", SlotKind.OBJECT)), SlotKind.OBJECT, pure = true,
    ) { args -> PrimitiveNatives.stringConcat(args.string(0), args.string(1)) }

    // ==================== String, String -> String (3-arg replace_all) ====================
    private val REPLACE_ALL = NativeOp("__native_string_replace_all",
        listOf(Param("s", SlotKind.OBJECT), Param("old", SlotKind.OBJECT), Param("new", SlotKind.OBJECT)),
        SlotKind.OBJECT, pure = true,
    ) { args -> args.string(0).replace(args.string(1), args.string(2)) }

    // ==================== String, Int -> String ====================
    private val REPEAT = NativeOp("__native_string_repeat",
        listOf(Param("s", SlotKind.OBJECT), Param("n", SlotKind.LONG)), SlotKind.OBJECT, pure = true,
    ) { args -> args.string(0).repeat(args.long(1).toInt()) }

    // ==================== String, String -> Bool ====================
    private val EQ = NativeOp("__native_string_eq",
        listOf(Param("l", SlotKind.OBJECT), Param("r", SlotKind.OBJECT)), SlotKind.BOOLEAN, pure = true,
    ) { args -> PrimitiveNatives.strEq(args.string(0), args.string(1)) }

    private val NEQ = NativeOp("__native_string_neq",
        listOf(Param("l", SlotKind.OBJECT), Param("r", SlotKind.OBJECT)), SlotKind.BOOLEAN, pure = true,
    ) { args -> PrimitiveNatives.strNeq(args.string(0), args.string(1)) }

    private val LT = NativeOp("__native_string_lt",
        listOf(Param("l", SlotKind.OBJECT), Param("r", SlotKind.OBJECT)), SlotKind.BOOLEAN, pure = true,
    ) { args -> PrimitiveNatives.strLt(args.string(0), args.string(1)) }

    private val LE = NativeOp("__native_string_le",
        listOf(Param("l", SlotKind.OBJECT), Param("r", SlotKind.OBJECT)), SlotKind.BOOLEAN, pure = true,
    ) { args -> PrimitiveNatives.strLte(args.string(0), args.string(1)) }

    private val GT = NativeOp("__native_string_gt",
        listOf(Param("l", SlotKind.OBJECT), Param("r", SlotKind.OBJECT)), SlotKind.BOOLEAN, pure = true,
    ) { args -> PrimitiveNatives.strGt(args.string(0), args.string(1)) }

    private val GE = NativeOp("__native_string_ge",
        listOf(Param("l", SlotKind.OBJECT), Param("r", SlotKind.OBJECT)), SlotKind.BOOLEAN, pure = true,
    ) { args -> PrimitiveNatives.strGte(args.string(0), args.string(1)) }

    private val CONTAINS = NativeOp("__native_string_contains",
        listOf(Param("s", SlotKind.OBJECT), Param("substring", SlotKind.OBJECT)), SlotKind.BOOLEAN, pure = true,
    ) { args -> args.string(0).contains(args.string(1)) }

    private val STARTS_WITH = NativeOp("__native_string_starts_with",
        listOf(Param("s", SlotKind.OBJECT), Param("prefix", SlotKind.OBJECT)), SlotKind.BOOLEAN, pure = true,
    ) { args -> args.string(0).startsWith(args.string(1)) }

    private val ENDS_WITH = NativeOp("__native_string_ends_with",
        listOf(Param("s", SlotKind.OBJECT), Param("suffix", SlotKind.OBJECT)), SlotKind.BOOLEAN, pure = true,
    ) { args -> args.string(0).endsWith(args.string(1)) }

    // ==================== String, Int, Int -> String ====================
    private val SUBSTRING = NativeOp("__native_string_substring",
        listOf(Param("s", SlotKind.OBJECT), Param("start", SlotKind.LONG), Param("end", SlotKind.LONG)),
        SlotKind.OBJECT, pure = true,
    ) { args ->
        val s = args.string(0)
        val start = args.long(1)
        val end = args.long(2)
        val len = s.length
        val startIdx = Math.max(0, Math.min(len, start.toInt()))
        val endIdx = Math.max(0, Math.min(len, end.toInt()))
        if (startIdx >= endIdx) "" else s.substring(startIdx, endIdx)
    }

    // ==================== String, Long -> Option Char ====================
    private val CHAR_AT = NativeOp("__native_string_char_at",
        listOf(Param("s", SlotKind.OBJECT), Param("i", SlotKind.LONG)),
        SlotKind.OBJECT, pure = true,
    ) { args ->
        val s = args.string(0)
        val i = args.long(1)
        if (i < 0 || i >= s.length) MezzeVariant.none()
        else MezzeVariant.some(s[i.toInt()].toString())
    }

    // ==================== String, String -> Option Int ====================
    private val INDEX_OF = NativeOp("__native_string_index_of",
        listOf(Param("s", SlotKind.OBJECT), Param("sub", SlotKind.OBJECT)),
        SlotKind.OBJECT, pure = true,
    ) { args ->
        val idx = args.string(0).indexOf(args.string(1))
        if (idx >= 0) MezzeVariant.some(idx.toLong()) else MezzeVariant.none()
    }

    private val LAST_INDEX_OF = NativeOp("__native_string_last_index_of",
        listOf(Param("s", SlotKind.OBJECT), Param("sub", SlotKind.OBJECT)),
        SlotKind.OBJECT, pure = true,
    ) { args ->
        val idx = args.string(0).lastIndexOf(args.string(1))
        if (idx >= 0) MezzeVariant.some(idx.toLong()) else MezzeVariant.none()
    }

    // ==================== String, String, String -> String ====================
    private val REPLACE = NativeOp("__native_string_replace",
        listOf(Param("s", SlotKind.OBJECT), Param("old", SlotKind.OBJECT), Param("new", SlotKind.OBJECT)),
        SlotKind.OBJECT, pure = true,
    ) { args -> args.string(0).replaceFirst(java.util.regex.Pattern.quote(args.string(1)), args.string(2)) }

}
