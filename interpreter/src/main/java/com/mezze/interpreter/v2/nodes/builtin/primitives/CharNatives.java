package com.mezze.interpreter.v2.nodes.builtin.primitives;

import com.mezze.interpreter.v2.values.*;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

/**
 * Native Char operations - ULTRA PERFORMANT.
 *
 * Char in V2 is represented as a single-character String.
 * All operations are designed for JIT compilation.
 */
public final class CharNatives {

    private CharNatives() {}

    // ==================== Conversion ====================

    @TruffleBoundary
    public static MezzeVariant fromString(String s) {
        if (s == null || s.isEmpty()) {
            return MezzeVariant.none();
        }
        return MezzeVariant.some(s.substring(0, 1));
    }

    @TruffleBoundary
    public static MezzeVariant fromInt(long codePoint) {
        if (codePoint < 0 || codePoint > Character.MAX_CODE_POINT) {
            return MezzeVariant.none();
        }
        return MezzeVariant.some(String.valueOf((char) codePoint));
    }

    @TruffleBoundary
    public static long toInt(String c) {
        if (c == null || c.isEmpty()) {
            return 0;
        }
        return c.charAt(0);
    }


    // ==================== Character Classification ====================

    @TruffleBoundary
    public static boolean isLetter(String c) {
        if (c == null || c.isEmpty()) return false;
        return Character.isLetter(c.charAt(0));
    }

    @TruffleBoundary
    public static boolean isDigit(String c) {
        if (c == null || c.isEmpty()) return false;
        return Character.isDigit(c.charAt(0));
    }

    @TruffleBoundary
    public static boolean isAlphaNumeric(String c) {
        if (c == null || c.isEmpty()) return false;
        return Character.isLetterOrDigit(c.charAt(0));
    }

    @TruffleBoundary
    public static boolean isWhitespace(String c) {
        if (c == null || c.isEmpty()) return false;
        return Character.isWhitespace(c.charAt(0));
    }

    @TruffleBoundary
    public static boolean isUpper(String c) {
        if (c == null || c.isEmpty()) return false;
        return Character.isUpperCase(c.charAt(0));
    }

    @TruffleBoundary
    public static boolean isLower(String c) {
        if (c == null || c.isEmpty()) return false;
        return Character.isLowerCase(c.charAt(0));
    }

    // ==================== Case Conversion ====================

    @TruffleBoundary
    public static String toUpper(String c) {
        if (c == null || c.isEmpty()) return c;
        return c.toUpperCase();
    }

    @TruffleBoundary
    public static String toLower(String c) {
        if (c == null || c.isEmpty()) return c;
        return c.toLowerCase();
    }

    // ==================== Digit Conversion ====================

    /**
     * Convert a character to its digit value in the given radix.
     * Returns Some(value) if the character is a valid digit, None otherwise.
     */
    @TruffleBoundary
    public static MezzeVariant digitToInt(String c, long radix) {
        if (c == null || c.isEmpty()) {
            return MezzeVariant.none();
        }
        char ch = c.charAt(0);
        int digit = Character.digit(ch, (int) radix);
        if (digit >= 0) {
            return MezzeVariant.some((long) digit);
        }
        return MezzeVariant.none();
    }


    // ==================== Comparison ====================

    @TruffleBoundary
    public static boolean eq(String c1, String c2) {
        if (c1 == null || c2 == null) return c1 == c2;
        return c1.equals(c2);
    }

    /**
     * Compare two characters, returning an Ordering variant (LT, EQ, GT).
     * This matches the expected Mezze Ordering type.
     */
    @TruffleBoundary
    public static MezzeVariant compare(String c1, String c2) {
        if (c1 == null || c1.isEmpty()) {
            if (c2 == null || c2.isEmpty()) {
                return MezzeVariant.eq();
            }
            return MezzeVariant.lt();
        }
        if (c2 == null || c2.isEmpty()) {
            return MezzeVariant.gt();
        }
        int cmp = c1.charAt(0) - c2.charAt(0);
        if (cmp < 0) {
            return MezzeVariant.lt();
        } else if (cmp > 0) {
            return MezzeVariant.gt();
        } else {
            return MezzeVariant.eq();
        }
    }

    /**
     * Compare two characters, returning -1, 0, or 1 as a long.
     * Used by Ord ability impl which compares against integers.
     */
    @TruffleBoundary
    public static long compareInt(String c1, String c2) {
        if (c1 == null || c1.isEmpty()) {
            if (c2 == null || c2.isEmpty()) {
                return 0L;
            }
            return -1L;
        }
        if (c2 == null || c2.isEmpty()) {
            return 1L;
        }
        int cmp = c1.charAt(0) - c2.charAt(0);
        if (cmp < 0) {
            return -1L;
        } else if (cmp > 0) {
            return 1L;
        } else {
            return 0L;
        }
    }
}
