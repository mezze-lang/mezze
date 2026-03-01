package com.mezze.interpreter.v2.nodes.builtin.primitives;

import com.mezze.interpreter.v2.values.*;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

/**
 * Native Nat (Natural number) operations - ULTRA PERFORMANT.
 *
 * Nat in V2 is represented as Long with non-negative values.
 * All operations return Option when result could be invalid.
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
     * Integer power
     */
    public static long pow(long base, long exp) {
        if (exp < 0) {
            return 0;
        }
        long result = 1;
        while (exp > 0) {
            if ((exp & 1) == 1) {
                result = Math.multiplyExact(result, base);
            }
            base = Math.multiplyExact(base, base);
            exp >>= 1;
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
