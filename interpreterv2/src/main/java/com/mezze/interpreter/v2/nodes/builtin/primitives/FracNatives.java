package com.mezze.interpreter.v2.nodes.builtin.primitives;

import com.mezze.interpreter.v2.values.*;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

/**
 * Native Frac (Fraction) operations - ULTRA PERFORMANT.
 *
 * Frac in V2 is represented as a MezzeRecord with {numerator: Int, denominator: Int}.
 * All operations maintain reduced form.
 */
public final class FracNatives {

    private FracNatives() {}

    // ==================== Factory Operations ====================

    @TruffleBoundary
    public static MezzeRecord make(long numerator, long denominator) {
        if (denominator == 0) {
            throw new ArithmeticException("Division by zero in fraction");
        }
        // Normalize sign
        if (denominator < 0) {
            numerator = -numerator;
            denominator = -denominator;
        }
        // Reduce
        long g = gcd(Math.abs(numerator), denominator);
        return MezzeRecord.fromFields(
            new String[]{"numerator", "denominator"},
            new Object[]{numerator / g, denominator / g}
        );
    }

    @TruffleBoundary
    public static MezzeRecord fromInt(long n) {
        return MezzeRecord.fromFields(
            new String[]{"numerator", "denominator"},
            new Object[]{n, 1L}
        );
    }

    @TruffleBoundary
    public static MezzeRecord fromNat(long n) {
        return MezzeRecord.fromFields(
            new String[]{"numerator", "denominator"},
            new Object[]{n, 1L}
        );
    }

    // ==================== Accessors ====================

    public static long numerator(MezzeRecord frac) {
        int idx = frac.getFieldIndex("numerator");
        return (Long) frac.getField(idx);
    }

    public static long denominator(MezzeRecord frac) {
        int idx = frac.getFieldIndex("denominator");
        return (Long) frac.getField(idx);
    }

    // ==================== Arithmetic ====================

    @TruffleBoundary
    public static MezzeRecord add(MezzeRecord l, MezzeRecord r) {
        long ln = numerator(l);
        long ld = denominator(l);
        long rn = numerator(r);
        long rd = denominator(r);
        return make(ln * rd + rn * ld, ld * rd);
    }

    @TruffleBoundary
    public static MezzeRecord subtract(MezzeRecord l, MezzeRecord r) {
        long ln = numerator(l);
        long ld = denominator(l);
        long rn = numerator(r);
        long rd = denominator(r);
        return make(ln * rd - rn * ld, ld * rd);
    }

    @TruffleBoundary
    public static MezzeRecord multiply(MezzeRecord l, MezzeRecord r) {
        long ln = numerator(l);
        long ld = denominator(l);
        long rn = numerator(r);
        long rd = denominator(r);
        return make(ln * rn, ld * rd);
    }

    @TruffleBoundary
    public static MezzeRecord divide(MezzeRecord l, MezzeRecord r) {
        long ln = numerator(l);
        long ld = denominator(l);
        long rn = numerator(r);
        long rd = denominator(r);
        if (rn == 0) {
            throw new ArithmeticException("Division by zero fraction");
        }
        return make(ln * rd, ld * rn);
    }

    @TruffleBoundary
    public static MezzeRecord negate(MezzeRecord frac) {
        return MezzeRecord.fromFields(
            new String[]{"numerator", "denominator"},
            new Object[]{-numerator(frac), denominator(frac)}
        );
    }

    @TruffleBoundary
    public static MezzeRecord abs(MezzeRecord frac) {
        return MezzeRecord.fromFields(
            new String[]{"numerator", "denominator"},
            new Object[]{Math.abs(numerator(frac)), denominator(frac)}
        );
    }

    @TruffleBoundary
    public static MezzeRecord reduce(MezzeRecord frac) {
        long n = numerator(frac);
        long d = denominator(frac);
        long g = gcd(Math.abs(n), Math.abs(d));
        return MezzeRecord.fromFields(
            new String[]{"numerator", "denominator"},
            new Object[]{n / g, d / g}
        );
    }

    // ==================== Comparison ====================

    @TruffleBoundary
    public static boolean equals(MezzeRecord l, MezzeRecord r) {
        long ln = numerator(l);
        long ld = denominator(l);
        long rn = numerator(r);
        long rd = denominator(r);
        return ln * rd == rn * ld;
    }

    @TruffleBoundary
    public static boolean lessThan(MezzeRecord l, MezzeRecord r) {
        long ln = numerator(l);
        long ld = denominator(l);
        long rn = numerator(r);
        long rd = denominator(r);
        return ln * rd < rn * ld;
    }

    @TruffleBoundary
    public static boolean greaterThan(MezzeRecord l, MezzeRecord r) {
        long ln = numerator(l);
        long ld = denominator(l);
        long rn = numerator(r);
        long rd = denominator(r);
        return ln * rd > rn * ld;
    }

    @TruffleBoundary
    public static long compare(MezzeRecord l, MezzeRecord r) {
        long ln = numerator(l);
        long ld = denominator(l);
        long rn = numerator(r);
        long rd = denominator(r);
        return Long.signum(ln * rd - rn * ld);
    }

    // ==================== Conversion ====================

    @TruffleBoundary
    public static double toFloat(MezzeRecord frac) {
        return (double) numerator(frac) / (double) denominator(frac);
    }

    @TruffleBoundary
    public static String fracToString(MezzeRecord frac) {
        long n = numerator(frac);
        long d = denominator(frac);
        if (d == 1) {
            return Long.toString(n);
        }
        return n + "/" + d;
    }

    // ==================== Helper ====================

    private static long gcd(long a, long b) {
        while (b != 0) {
            long t = b;
            b = a % b;
            a = t;
        }
        return a;
    }
}
