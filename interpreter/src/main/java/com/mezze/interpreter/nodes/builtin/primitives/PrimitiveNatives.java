package com.mezze.interpreter.nodes.builtin.primitives;

import com.mezze.interpreter.values.MezzeGuestException;
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
 *
 * <h2>Everything here must be safe to call with any Mezze input</h2>
 *
 * This class is the SHARED implementation the native routes call, so a method
 * here has to carry its own guards — see {@link #intDivide}. It used to also
 * hold unguarded twins of operations that were guarded elsewhere
 * ({@code stringSubstring} raw next to {@code NativeDispatch}'s clamped
 * private copy of the same name, and {@code NativeDispatch} wildcard-imports
 * this package). Nothing called the twins, but deleting a private copy in
 * favour of "the shared helper" would have silently switched to the unguarded
 * one. The 54 uncalled methods are gone; keep it that way — add a method here
 * when a route needs it, not in advance.
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

    // Guarded here, not just in DirectDivideNode/DirectModuloNode: `a / b`
    // reaches the primitive by four routes (the inline node, the scalar-ABI
    // NativeOp, the typed LLL handler, and the record-ABI NativeDispatch
    // entry), and the same expression must fail the same way whichever route
    // the compiler picks for it.
    public static long intDivide(long l, long r) {
        if (r == 0) throw MezzeGuestException.dividingByZero("Division");
        return l / r;
    }

    public static long intModulo(long l, long r) {
        if (r == 0) throw MezzeGuestException.dividingByZero("Modulo");
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

    public static double floatSignum(double n) {
        return Math.signum(n);
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
    public static String stringReplace(String s, String target, String replacement) {
        return s.replace(target, replacement);
    }

    /**
     * Replace the FIRST occurrence of {@code target}, literally.
     *
     * Both sides are literal, exactly as in {@link #stringReplace}: the two
     * are the same operation differing only in how many occurrences they
     * take, so they must not disagree about what their arguments mean.
     *
     * This deliberately does not go through {@code String.replaceFirst}.
     * That takes a regex for the search AND a replacement TEMPLATE, in which
     * {@code $} and {@code \} are metacharacters — so quoting only the search
     * side (the previous implementation) left the replacement live: replacing
     * with a price like {@code "$5"} threw IndexOutOfBoundsException out of
     * the regex engine, {@code "c$"} threw IllegalArgumentException, and
     * {@code "$0"} silently substituted the matched text instead of itself.
     */
    @TruffleBoundary
    public static String stringReplaceFirst(String s, String target, String replacement) {
        int at = s.indexOf(target);
        if (at < 0) return s;
        return s.substring(0, at) + replacement + s.substring(at + target.length());
    }

    public static String stringConcat(String s, String other) {
        return s.concat(other);
    }

    // ==================== Conversion Operations ====================

    public static double intToFloat(long n) {
        return (double) n;
    }

    public static long floatToInt(double n) {
        return (long) n;
    }

    @TruffleBoundary
    public static String intToString(long n) {
        return Long.toString(n);
    }

    // ==================== Nat Operations ====================
    // Nat is represented as long, same as Int but non-negative

    public static long natAdd(long l, long r) {
        return l + r;
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

    /**
     * The STRUCTURAL hash of a value — what a derived {@code Hash} instance means.
     *
     * This is already what every hash container computes for its keys
     * ({@code HashSetStorage} calls {@code elem.hashCode()}), so a type whose
     * {@code Hash} is derived hashes exactly as it does today: adding the ability
     * changes no behaviour and costs no performance for the common case. A type
     * that writes its own {@code Hash} overrides this, which is the point — a
     * hand-written {@code Eq} and a structural hash disagree, and a hash container
     * that buckets by one and compares by the other silently holds both of two
     * equal values.
     *
     * {@code MezzeRecord} and the variant/seq values define {@code hashCode} over
     * their contents, so this is stable for compound values and not identity-based.
     */
    @TruffleBoundary
    public static long structuralHash(Object value) {
        return value == null ? 0L : (long) value.hashCode();
    }
}
