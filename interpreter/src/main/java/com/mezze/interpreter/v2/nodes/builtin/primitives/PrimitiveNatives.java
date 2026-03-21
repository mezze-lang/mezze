package com.mezze.interpreter.v2.nodes.builtin.primitives;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

/**
 * Native primitive operations using direct Java implementations.
 *
 * For interpreter dispatch (via NativeDispatch), direct implementations
 * are optimal because:
 * 1. Single-instruction operations (add, mul, etc.) compile identically
 * 2. Truffle nodes with @Specialization(rewriteOn=...) require AST embedding
 * 3. Nodes with @Cached profiles don't work in uncached mode without @GenerateUncached
 *
 * The specialized Truffle nodes in datastructures module are for AST-embedded
 * use cases where node rewriting and profiling provide benefits.
 */
public final class PrimitiveNatives {

    private PrimitiveNatives() {}

    // ==================== Int Operations ====================

    // Direct implementations for interpreter dispatch.
    // Note: The specialized Truffle nodes (IntAddNode, etc.) with rewriteOn
    // are optimized for AST-embedded use, not uncached execution.
    // For native dispatch, direct implementations are equally efficient.
    public static long intAdd(long l, long r) {
        return l + r;
    }

    public static long intSubtract(long l, long r) {
        return l - r;
    }

    public static long intMultiply(long l, long r) {
        return l * r;
    }

    public static long intDivide(long l, long r) {
        return l / r;
    }

    public static long intModulo(long l, long r) {
        return l % r;
    }

    public static long intNegate(long n) {
        return -n;
    }

    public static long intAbs(long n) {
        return Math.abs(n);
    }

    // Direct implementations for simple intrinsic operations
    public static long intMin(long l, long r) {
        return Math.min(l, r);
    }

    public static long intMax(long l, long r) {
        return Math.max(l, r);
    }

    public static long intClamp(long n, long min, long max) {
        return Math.max(min, Math.min(max, n));
    }

    public static long intSign(long n) {
        return Long.signum(n);
    }

    // Bitwise operations - direct implementations
    public static long intBitwiseAnd(long l, long r) {
        return l & r;
    }

    public static long intBitwiseOr(long l, long r) {
        return l | r;
    }

    public static long intBitwiseXor(long l, long r) {
        return l ^ r;
    }

    public static long intBitwiseNot(long n) {
        return ~n;
    }

    public static long intShiftLeft(long n, long bits) {
        return n << bits;
    }

    public static long intShiftRight(long n, long bits) {
        return n >> bits;
    }

    public static long intUnsignedShiftRight(long n, long bits) {
        return n >>> bits;
    }

    public static long intPopCount(long n) {
        return Long.bitCount(n);
    }

    public static long intCountLeadingZeros(long n) {
        return Long.numberOfLeadingZeros(n);
    }

    public static long intCountTrailingZeros(long n) {
        return Long.numberOfTrailingZeros(n);
    }

    // Comparisons - direct implementations
    public static boolean intLt(long l, long r) {
        return l < r;
    }

    public static boolean intLte(long l, long r) {
        return l <= r;
    }

    public static boolean intGt(long l, long r) {
        return l > r;
    }

    public static boolean intGte(long l, long r) {
        return l >= r;
    }

    public static boolean intEq(long l, long r) {
        return l == r;
    }

    public static boolean intNeq(long l, long r) {
        return l != r;
    }

    public static long intCompare(long l, long r) {
        return Long.compare(l, r);
    }

    // ==================== Float Operations ====================

    // Direct implementations for interpreter dispatch.
    public static double floatAdd(double l, double r) {
        return l + r;
    }

    public static double floatSubtract(double l, double r) {
        return l - r;
    }

    public static double floatMultiply(double l, double r) {
        return l * r;
    }

    public static double floatDivide(double l, double r) {
        return l / r;
    }

    public static double floatNegate(double n) {
        return -n;
    }

    public static double floatAbs(double n) {
        return Math.abs(n);
    }

    public static double floatMin(double l, double r) {
        return Math.min(l, r);
    }

    public static double floatMax(double l, double r) {
        return Math.max(l, r);
    }

    public static double floatClamp(double n, double min, double max) {
        return Math.max(min, Math.min(max, n));
    }

    public static double floatSqrt(double n) {
        return Math.sqrt(n);
    }

    public static double floatFma(double a, double b, double c) {
        return Math.fma(a, b, c);
    }

    // Math functions - direct intrinsic calls
    public static double floatFloor(double n) {
        return Math.floor(n);
    }

    public static double floatCeil(double n) {
        return Math.ceil(n);
    }

    public static long floatRound(double n) {
        return Math.round(n);
    }

    public static double floatTrunc(double n) {
        return n >= 0 ? Math.floor(n) : Math.ceil(n);
    }

    public static double floatSin(double n) {
        return Math.sin(n);
    }

    public static double floatCos(double n) {
        return Math.cos(n);
    }

    public static double floatTan(double n) {
        return Math.tan(n);
    }

    public static double floatAsin(double n) {
        return Math.asin(n);
    }

    public static double floatAcos(double n) {
        return Math.acos(n);
    }

    public static double floatAtan(double n) {
        return Math.atan(n);
    }

    public static double floatAtan2(double y, double x) {
        return Math.atan2(y, x);
    }

    public static double floatExp(double n) {
        return Math.exp(n);
    }

    public static double floatLog(double n) {
        return Math.log(n);
    }

    public static double floatLog10(double n) {
        return Math.log10(n);
    }

    public static double floatPow(double base, double exp) {
        return Math.pow(base, exp);
    }

    public static double floatHypot(double x, double y) {
        return Math.hypot(x, y);
    }

    public static double floatCbrt(double n) {
        return Math.cbrt(n);
    }

    public static double floatSinh(double n) {
        return Math.sinh(n);
    }

    public static double floatCosh(double n) {
        return Math.cosh(n);
    }

    public static double floatTanh(double n) {
        return Math.tanh(n);
    }

    public static double floatExpm1(double n) {
        return Math.expm1(n);
    }

    public static double floatLog1p(double n) {
        return Math.log1p(n);
    }

    public static double floatSignum(double n) {
        return Math.signum(n);
    }

    public static double floatCopySign(double magnitude, double sign) {
        return Math.copySign(magnitude, sign);
    }

    // Comparisons - direct implementations
    public static boolean floatLt(double l, double r) {
        return l < r;
    }

    public static boolean floatLte(double l, double r) {
        return l <= r;
    }

    public static boolean floatGt(double l, double r) {
        return l > r;
    }

    public static boolean floatGte(double l, double r) {
        return l >= r;
    }

    public static boolean floatEq(double l, double r) {
        return l == r;
    }

    public static boolean floatNeq(double l, double r) {
        return l != r;
    }

    public static boolean floatIsNaN(double n) {
        return Double.isNaN(n);
    }

    public static boolean floatIsInfinite(double n) {
        return Double.isInfinite(n);
    }

    public static boolean floatIsFinite(double n) {
        return Double.isFinite(n);
    }

    // ==================== Bool Operations ====================

    // Bool comparisons
    public static boolean boolEq(boolean l, boolean r) {
        return l == r;
    }

    public static boolean boolNeq(boolean l, boolean r) {
        return l != r;
    }

    // Direct implementations - boolean operations are single instructions
    public static boolean boolAnd(boolean l, boolean r) {
        return l && r;
    }

    public static boolean boolOr(boolean l, boolean r) {
        return l || r;
    }

    public static boolean boolNot(boolean v) {
        return !v;
    }

    public static boolean boolXor(boolean l, boolean r) {
        return l ^ r;
    }

    // ==================== String Operations ====================

    // String comparisons
    public static boolean strEq(String l, String r) {
        return l.equals(r);
    }

    public static boolean strNeq(String l, String r) {
        return !l.equals(r);
    }

    public static boolean strLt(String l, String r) {
        return l.compareTo(r) < 0;
    }

    public static boolean strLte(String l, String r) {
        return l.compareTo(r) <= 0;
    }

    public static boolean strGt(String l, String r) {
        return l.compareTo(r) > 0;
    }

    public static boolean strGte(String l, String r) {
        return l.compareTo(r) >= 0;
    }

    public static int stringLength(String s) {
        return s.length();
    }

    public static boolean stringIsEmpty(String s) {
        return s.isEmpty();
    }

    public static char stringCharAt(String s, int index) {
        return s.charAt(index);
    }

    public static int stringCodePointAt(String s, int index) {
        return s.codePointAt(index);
    }

    @TruffleBoundary
    public static String stringSubstring(String s, int start, int end) {
        return s.substring(start, end);
    }

    @TruffleBoundary
    public static int stringIndexOf(String s, String target) {
        return s.indexOf(target);
    }

    @TruffleBoundary
    public static int stringLastIndexOf(String s, String target) {
        return s.lastIndexOf(target);
    }

    @TruffleBoundary
    public static boolean stringStartsWith(String s, String prefix) {
        return s.startsWith(prefix);
    }

    @TruffleBoundary
    public static boolean stringEndsWith(String s, String suffix) {
        return s.endsWith(suffix);
    }

    @TruffleBoundary
    public static boolean stringContains(String s, String target) {
        return s.contains(target);
    }

    public static boolean stringEquals(String s, String other) {
        return s.equals(other);
    }

    @TruffleBoundary
    public static boolean stringEqualsIgnoreCase(String s, String other) {
        return s.equalsIgnoreCase(other);
    }

    @TruffleBoundary
    public static int stringCompareTo(String s, String other) {
        return s.compareTo(other);
    }

    public static int stringHashCode(String s) {
        return s.hashCode();
    }

    @TruffleBoundary
    public static String stringToLowerCase(String s) {
        return s.toLowerCase();
    }

    @TruffleBoundary
    public static String stringToUpperCase(String s) {
        return s.toUpperCase();
    }

    @TruffleBoundary
    public static String stringTrim(String s) {
        return s.trim();
    }

    @TruffleBoundary
    public static String stringStrip(String s) {
        return s.strip();
    }

    @TruffleBoundary
    public static String stringReplace(String s, String target, String replacement) {
        return s.replace(target, replacement);
    }

    public static String stringConcat(String s, String other) {
        return s.concat(other);
    }

    @TruffleBoundary
    public static String stringRepeat(String s, int count) {
        return s.repeat(count);
    }

    @TruffleBoundary
    public static boolean stringIsBlank(String s) {
        return s.isBlank();
    }

    // ==================== Conversion Operations ====================

    public static double intToFloat(long n) {
        return (double) n;
    }

    public static long floatToInt(double n) {
        return (long) n;
    }

    public static long floatToIntTruncate(double n) {
        return (long) n;
    }

    public static long floatToIntRound(double n) {
        return Math.round(n);
    }

    public static long floatToIntFloor(double n) {
        return (long) Math.floor(n);
    }

    public static long floatToIntCeil(double n) {
        return (long) Math.ceil(n);
    }

    @TruffleBoundary
    public static String intToString(long n) {
        return Long.toString(n);
    }

    @TruffleBoundary
    public static String floatToString(double n) {
        return Double.toString(n);
    }

    // ==================== Char Operations ====================

    public static long charToInt(char c) {
        return c;
    }

    public static char intToChar(long n) {
        return (char) n;
    }

    public static boolean charIsDigit(char c) {
        return Character.isDigit(c);
    }

    public static boolean charIsLetter(char c) {
        return Character.isLetter(c);
    }

    public static boolean charIsWhitespace(char c) {
        return Character.isWhitespace(c);
    }

    public static boolean charIsUpperCase(char c) {
        return Character.isUpperCase(c);
    }

    public static boolean charIsLowerCase(char c) {
        return Character.isLowerCase(c);
    }

    public static char charToUpperCase(char c) {
        return Character.toUpperCase(c);
    }

    public static char charToLowerCase(char c) {
        return Character.toLowerCase(c);
    }

    // ==================== Nat Operations ====================
    // Nat is represented as long, same as Int but non-negative

    public static long natAdd(long l, long r) {
        return l + r;
    }

    public static long natSubtract(long l, long r) {
        return l - r;  // Caller should check for underflow
    }

    public static long natMultiply(long l, long r) {
        return l * r;
    }

    public static long natDivide(long l, long r) {
        return l / r;
    }

    public static long natModulo(long l, long r) {
        return l % r;
    }

    public static long natMin(long l, long r) {
        return Math.min(l, r);
    }

    public static long natMax(long l, long r) {
        return Math.max(l, r);
    }

    // Nat comparisons (same as int since both are long)
    public static boolean natEq(long l, long r) {
        return l == r;
    }

    public static boolean natNeq(long l, long r) {
        return l != r;
    }

    public static boolean natLt(long l, long r) {
        return l < r;
    }

    public static boolean natLte(long l, long r) {
        return l <= r;
    }

    public static boolean natGt(long l, long r) {
        return l > r;
    }

    public static boolean natGte(long l, long r) {
        return l >= r;
    }
}
