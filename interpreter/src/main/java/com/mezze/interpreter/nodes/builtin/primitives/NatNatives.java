package com.mezze.interpreter.nodes.builtin.primitives;

import com.mezze.interpreter.values.*;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

/**
 * Native Nat (Natural number) operations - ULTRA PERFORMANT.
 *
 * Nat is represented as a primitive long for optimal JIT performance.
 * The type distinction (Nat vs Int) is known statically from the typechecker.
 */
public final class NatNatives {

    private NatNatives() {}

    // ==================== Conversion ====================

    public static MezzeVariant fromInt(long n) {
        if (n >= 0) {
            return MezzeVariant.some(n);
        }
        return MezzeVariant.none();
    }

    @TruffleBoundary
    public static MezzeVariant fromString(String s) {
        try {
            long value = Long.parseLong(s.trim());
            if (value >= 0) {
                return MezzeVariant.some(value);
            }
            return MezzeVariant.none();
        } catch (NumberFormatException e) {
            return MezzeVariant.none();
        }
    }

    @TruffleBoundary
    public static MezzeVariant fromFloat(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d) || d < 0) {
            return MezzeVariant.none();
        }
        return MezzeVariant.some((long) d);
    }

    public static long toInt(long n) {
        return n;
    }

    @TruffleBoundary
    public static String toString(long n) {
        return Long.toString(n);
    }

    // ==================== Arithmetic ====================

    /**
     * Subtract returning Int (can be negative)
     */
    public static long subtractToInt(long l, long r) {
        return l - r;
    }

    /**
     * Integer power - fully JIT compilable
     */
    public static long pow(long base, long exp) {
        if (exp < 0) {
            return 0;
        }
        long b = base, e = exp, result = 1;
        try {
            while (e > 0) {
                if ((e & 1) == 1) {
                    result = Math.multiplyExact(result, b);
                }
                e >>= 1;
                // Square only while another bit needs it: squaring past the last bit
                // can overflow even though the answer itself fits.
                if (e > 0) b = Math.multiplyExact(b, b);
            }
        } catch (ArithmeticException overflow) {
            throw MezzeGuestException.natOverflow("Power", base, exp);
        }
        return result;
    }

    // ==================== Comparison ====================

    public static long min(long l, long r) {
        return Math.min(l, r);
    }

    public static long max(long l, long r) {
        return Math.max(l, r);
    }

    public static long clamp(long n, long low, long high) {
        return Math.max(low, Math.min(high, n));
    }
}
