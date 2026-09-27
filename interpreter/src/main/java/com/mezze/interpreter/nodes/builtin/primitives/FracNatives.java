package com.mezze.interpreter.nodes.builtin.primitives;

import com.mezze.interpreter.values.*;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

/**
 * Native Frac (Fraction) operations - ULTRA PERFORMANT.
 *
 * Frac is represented as a MezzeRecord with {numerator: Int, denominator: Int}.
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
            throw com.mezze.interpreter.values.MezzeGuestException.dividingByZero("Fraction division");
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

    // ==================== Conversion ====================

    @TruffleBoundary
    public static double toFloat(MezzeRecord frac) {
        return (double) numerator(frac) / (double) denominator(frac);
    }

    /**
     * A Float as a reduced Frac — the inverse of {@link #toFloat}.
     *
     * Uses the continued-fraction convergents (the Stern–Brocot walk) to find the
     * best rational approximation whose denominator stays within a bound, so a
     * `Frac -> Float -> Frac` round trip recovers the "nice" fractions a codec
     * cares about (3/4, 1/3, 22/7) instead of the raw binary ratio of the double.
     * NaN / infinity have no rational value and raise, matching how the other
     * frac natives surface a domain error.
     */
    @TruffleBoundary
    public static MezzeRecord fromFloat(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new ArithmeticException("Cannot convert " + value + " to a fraction");
        }
        boolean negative = value < 0;
        double x = Math.abs(value);
        final long MAX_DEN = 1_000_000_000L;
        long h0 = 0, h1 = 1;  // numerator convergents (h1 = current)
        long k0 = 1, k1 = 0;  // denominator convergents (k1 = current)
        double frac = x;
        for (int i = 0; i < 64; i++) {
            long a = (long) Math.floor(frac);
            long h2 = a * h1 + h0;
            long k2 = a * k1 + k0;
            if (k2 > MAX_DEN || k2 < 0 || h2 < 0) break;
            h0 = h1; h1 = h2;
            k0 = k1; k1 = k2;
            double rem = frac - a;
            if (rem < 1e-12) break;
            frac = 1.0 / rem;
        }
        long num = negative ? -h1 : h1;
        long den = k1 == 0 ? 1 : k1;
        return make(num, den);
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
