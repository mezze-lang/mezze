package com.mezze.interpreter.v2.nodes.inline;

import com.mezze.interpreter.v2.values.MezzeRecord;

/**
 * Helper for fraction arithmetic in Direct* nodes.
 * Fractions are MezzeRecord with "numerator" and "denominator" fields.
 */
public final class FractionHelper {

    private static final String NUMERATOR = "numerator";
    private static final String DENOMINATOR = "denominator";
    private static final String[] FRAC_FIELDS = new String[]{NUMERATOR, DENOMINATOR};

    public static boolean isFraction(Object value) {
        if (!(value instanceof MezzeRecord record)) return false;
        return record.getFieldIndex(NUMERATOR) >= 0 && record.getFieldIndex(DENOMINATOR) >= 0;
    }

    public static long getNumerator(MezzeRecord frac) {
        return (Long) frac.getField(NUMERATOR);
    }

    public static long getDenominator(MezzeRecord frac) {
        return (Long) frac.getField(DENOMINATOR);
    }

    public static MezzeRecord makeFraction(long numerator, long denominator) {
        long g = gcd(Math.abs(numerator), Math.abs(denominator));
        if (g > 1) {
            numerator /= g;
            denominator /= g;
        }
        if (denominator < 0) {
            numerator = -numerator;
            denominator = -denominator;
        }
        return MezzeRecord.fromFields(FRAC_FIELDS, new Object[]{numerator, denominator});
    }

    private static long gcd(long a, long b) {
        while (b != 0) {
            long t = b;
            b = a % b;
            a = t;
        }
        return a;
    }

    // (a/b) + (c/d) = (a*d + c*b) / (b*d)
    public static MezzeRecord addFractions(MezzeRecord left, MezzeRecord right) {
        long ln = getNumerator(left), ld = getDenominator(left);
        long rn = getNumerator(right), rd = getDenominator(right);
        return makeFraction(ln * rd + rn * ld, ld * rd);
    }

    // (a/b) - (c/d) = (a*d - c*b) / (b*d)
    public static MezzeRecord subtractFractions(MezzeRecord left, MezzeRecord right) {
        long ln = getNumerator(left), ld = getDenominator(left);
        long rn = getNumerator(right), rd = getDenominator(right);
        return makeFraction(ln * rd - rn * ld, ld * rd);
    }

    // (a/b) * (c/d) = (a*c) / (b*d)
    public static MezzeRecord multiplyFractions(MezzeRecord left, MezzeRecord right) {
        long ln = getNumerator(left), ld = getDenominator(left);
        long rn = getNumerator(right), rd = getDenominator(right);
        return makeFraction(ln * rn, ld * rd);
    }

    // (a/b) / (c/d) = (a*d) / (b*c)
    public static MezzeRecord divideFractions(MezzeRecord left, MezzeRecord right) {
        long ln = getNumerator(left), ld = getDenominator(left);
        long rn = getNumerator(right), rd = getDenominator(right);
        if (rn == 0) throw new ArithmeticException("Division by zero fraction");
        return makeFraction(ln * rd, ld * rn);
    }

    // -(a/b) = (-a/b)
    public static MezzeRecord negateFraction(MezzeRecord frac) {
        return makeFraction(-getNumerator(frac), getDenominator(frac));
    }

    // Fraction equality: (a/b) == (c/d) iff a*d == b*c (after reduction)
    public static boolean equalFractions(MezzeRecord left, MezzeRecord right) {
        long ln = getNumerator(left), ld = getDenominator(left);
        long rn = getNumerator(right), rd = getDenominator(right);
        return ln * rd == rn * ld;
    }

    // Fraction comparison: (a/b) < (c/d) iff a*d < b*c (assuming positive denominators)
    public static int compareFractions(MezzeRecord left, MezzeRecord right) {
        long ln = getNumerator(left), ld = getDenominator(left);
        long rn = getNumerator(right), rd = getDenominator(right);
        return Long.compare(ln * rd, rn * ld);
    }
}
