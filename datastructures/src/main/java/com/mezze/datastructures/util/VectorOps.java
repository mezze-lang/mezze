package com.mezze.datastructures.util;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import jdk.incubator.vector.*;

/**
 * SIMD-optimized operations using Java Vector API.
 *
 * Provides vectorized implementations for common array operations:
 * - sum, product, min, max
 * - dot product, scale, add arrays
 * - find, contains
 * - copy, fill
 *
 * Falls back to scalar loops for small arrays or when SIMD isn't beneficial.
 */
public final class VectorOps {

    // Vector species for different types - use preferred (largest available)
    // @CompilationFinal allows the JIT to treat these as compile-time constants
    @CompilationFinal
    private static final VectorSpecies<Long> LONG_SPECIES = LongVector.SPECIES_PREFERRED;
    @CompilationFinal
    private static final VectorSpecies<Double> DOUBLE_SPECIES = DoubleVector.SPECIES_PREFERRED;
    @CompilationFinal
    private static final VectorSpecies<Integer> INT_SPECIES = IntVector.SPECIES_PREFERRED;

    // Threshold below which scalar is faster (cache/setup overhead)
    @CompilationFinal
    private static final int SIMD_THRESHOLD_LONG = LONG_SPECIES.length() * 2;
    @CompilationFinal
    private static final int SIMD_THRESHOLD_DOUBLE = DOUBLE_SPECIES.length() * 2;

    private VectorOps() {} // Utility class

    // ==================== LONG OPERATIONS ====================

    /**
     * Sum all elements using SIMD.
     */
    @TruffleBoundary
    public static long sumLong(long[] data) {
        int length = data.length;
        if (length == 0) return 0L;
        if (length < SIMD_THRESHOLD_LONG) return sumLongScalar(data);

        int i = 0;
        int bound = LONG_SPECIES.loopBound(length);
        LongVector acc = LongVector.zero(LONG_SPECIES);

        // Main SIMD loop
        for (; i < bound; i += LONG_SPECIES.length()) {
            LongVector v = LongVector.fromArray(LONG_SPECIES, data, i);
            acc = acc.add(v);
        }

        // Reduce vector to scalar
        long result = acc.reduceLanes(VectorOperators.ADD);

        // Handle tail
        for (; i < length; i++) {
            result += data[i];
        }

        return result;
    }

    private static long sumLongScalar(long[] data) {
        long result = 0;
        for (long v : data) {
            result += v;
        }
        return result;
    }

    /**
     * Sum {@code data[from..to)} using SIMD. Same shape as
     * {@link #sumLong(long[])} but bounded to a half-open range —
     * lets a caller chunk the array across FJP workers without
     * copying slices. #69.
     */
    @TruffleBoundary
    public static long sumLongRange(long[] data, int from, int to) {
        int length = to - from;
        if (length <= 0) return 0L;
        if (length < SIMD_THRESHOLD_LONG) {
            long r = 0;
            for (int j = from; j < to; j++) r += data[j];
            return r;
        }
        int i = from;
        int bound = from + LONG_SPECIES.loopBound(length);
        LongVector acc = LongVector.zero(LONG_SPECIES);
        for (; i < bound; i += LONG_SPECIES.length()) {
            LongVector v = LongVector.fromArray(LONG_SPECIES, data, i);
            acc = acc.add(v);
        }
        long result = acc.reduceLanes(VectorOperators.ADD);
        for (; i < to; i++) result += data[i];
        return result;
    }

    /**
     * Product of all elements using SIMD.
     */
    @TruffleBoundary
    public static long productLong(long[] data) {
        int length = data.length;
        if (length == 0) return 1L;
        if (length < SIMD_THRESHOLD_LONG) return productLongScalar(data);

        int i = 0;
        int bound = LONG_SPECIES.loopBound(length);
        LongVector acc = LongVector.broadcast(LONG_SPECIES, 1L);

        for (; i < bound; i += LONG_SPECIES.length()) {
            LongVector v = LongVector.fromArray(LONG_SPECIES, data, i);
            acc = acc.mul(v);
        }

        long result = acc.reduceLanes(VectorOperators.MUL);

        for (; i < length; i++) {
            result *= data[i];
        }

        return result;
    }

    private static long productLongScalar(long[] data) {
        long result = 1;
        for (long v : data) {
            result *= v;
        }
        return result;
    }

    /**
     * Product of {@code data[from..to)} using SIMD. Companion to
     * {@link #sumLongRange}. #69.
     */
    @TruffleBoundary
    public static long productLongRange(long[] data, int from, int to) {
        int length = to - from;
        if (length <= 0) return 1L;
        if (length < SIMD_THRESHOLD_LONG) {
            long r = 1;
            for (int j = from; j < to; j++) r *= data[j];
            return r;
        }
        int i = from;
        int bound = from + LONG_SPECIES.loopBound(length);
        LongVector acc = LongVector.broadcast(LONG_SPECIES, 1L);
        for (; i < bound; i += LONG_SPECIES.length()) {
            LongVector v = LongVector.fromArray(LONG_SPECIES, data, i);
            acc = acc.mul(v);
        }
        long result = acc.reduceLanes(VectorOperators.MUL);
        for (; i < to; i++) result *= data[i];
        return result;
    }

    /**
     * Minimum element using SIMD.
     */
    @TruffleBoundary
    public static long minLong(long[] data) {
        int length = data.length;
        if (length == 0) throw new IllegalArgumentException("Empty array");
        if (length < SIMD_THRESHOLD_LONG) return minLongScalar(data);

        int i = 0;
        int bound = LONG_SPECIES.loopBound(length);
        LongVector acc = LongVector.broadcast(LONG_SPECIES, Long.MAX_VALUE);

        for (; i < bound; i += LONG_SPECIES.length()) {
            LongVector v = LongVector.fromArray(LONG_SPECIES, data, i);
            acc = acc.min(v);
        }

        long result = acc.reduceLanes(VectorOperators.MIN);

        for (; i < length; i++) {
            if (data[i] < result) result = data[i];
        }

        return result;
    }

    private static long minLongScalar(long[] data) {
        long result = data[0];
        for (int i = 1; i < data.length; i++) {
            if (data[i] < result) result = data[i];
        }
        return result;
    }

    /**
     * Maximum element using SIMD.
     */
    @TruffleBoundary
    public static long maxLong(long[] data) {
        int length = data.length;
        if (length == 0) throw new IllegalArgumentException("Empty array");
        if (length < SIMD_THRESHOLD_LONG) return maxLongScalar(data);

        int i = 0;
        int bound = LONG_SPECIES.loopBound(length);
        LongVector acc = LongVector.broadcast(LONG_SPECIES, Long.MIN_VALUE);

        for (; i < bound; i += LONG_SPECIES.length()) {
            LongVector v = LongVector.fromArray(LONG_SPECIES, data, i);
            acc = acc.max(v);
        }

        long result = acc.reduceLanes(VectorOperators.MAX);

        for (; i < length; i++) {
            if (data[i] > result) result = data[i];
        }

        return result;
    }

    private static long maxLongScalar(long[] data) {
        long result = data[0];
        for (int i = 1; i < data.length; i++) {
            if (data[i] > result) result = data[i];
        }
        return result;
    }

    /**
     * Find index of value using SIMD (-1 if not found).
     */
    @TruffleBoundary
    public static int indexOfLong(long[] data, long value) {
        int length = data.length;
        if (length < SIMD_THRESHOLD_LONG) return indexOfLongScalar(data, value);

        int i = 0;
        int bound = LONG_SPECIES.loopBound(length);
        LongVector target = LongVector.broadcast(LONG_SPECIES, value);

        for (; i < bound; i += LONG_SPECIES.length()) {
            LongVector v = LongVector.fromArray(LONG_SPECIES, data, i);
            VectorMask<Long> mask = v.eq(target);
            if (mask.anyTrue()) {
                // Found in this chunk - find exact index
                for (int j = 0; j < LONG_SPECIES.length(); j++) {
                    if (data[i + j] == value) return i + j;
                }
            }
        }

        // Check tail
        for (; i < length; i++) {
            if (data[i] == value) return i;
        }

        return -1;
    }

    private static int indexOfLongScalar(long[] data, long value) {
        for (int i = 0; i < data.length; i++) {
            if (data[i] == value) return i;
        }
        return -1;
    }

    /**
     * Add two arrays element-wise using SIMD.
     */
    @TruffleBoundary
    public static long[] addLong(long[] a, long[] b) {
        int length = Math.min(a.length, b.length);
        long[] result = new long[length];

        if (length < SIMD_THRESHOLD_LONG) {
            for (int i = 0; i < length; i++) {
                result[i] = a[i] + b[i];
            }
            return result;
        }

        int i = 0;
        int bound = LONG_SPECIES.loopBound(length);

        for (; i < bound; i += LONG_SPECIES.length()) {
            LongVector va = LongVector.fromArray(LONG_SPECIES, a, i);
            LongVector vb = LongVector.fromArray(LONG_SPECIES, b, i);
            va.add(vb).intoArray(result, i);
        }

        for (; i < length; i++) {
            result[i] = a[i] + b[i];
        }

        return result;
    }

    /**
     * Scale array by constant using SIMD.
     */
    @TruffleBoundary
    public static long[] scaleLong(long[] data, long factor) {
        int length = data.length;
        long[] result = new long[length];

        if (length < SIMD_THRESHOLD_LONG) {
            for (int i = 0; i < length; i++) {
                result[i] = data[i] * factor;
            }
            return result;
        }

        int i = 0;
        int bound = LONG_SPECIES.loopBound(length);
        LongVector vfactor = LongVector.broadcast(LONG_SPECIES, factor);

        for (; i < bound; i += LONG_SPECIES.length()) {
            LongVector v = LongVector.fromArray(LONG_SPECIES, data, i);
            v.mul(vfactor).intoArray(result, i);
        }

        for (; i < length; i++) {
            result[i] = data[i] * factor;
        }

        return result;
    }

    // ==================== DOUBLE OPERATIONS ====================

    /**
     * Sum all elements using SIMD.
     */
    @TruffleBoundary
    public static double sumDouble(double[] data) {
        int length = data.length;
        if (length == 0) return 0.0;
        if (length < SIMD_THRESHOLD_DOUBLE) return sumDoubleScalar(data);

        int i = 0;
        int bound = DOUBLE_SPECIES.loopBound(length);
        DoubleVector acc = DoubleVector.zero(DOUBLE_SPECIES);

        for (; i < bound; i += DOUBLE_SPECIES.length()) {
            DoubleVector v = DoubleVector.fromArray(DOUBLE_SPECIES, data, i);
            acc = acc.add(v);
        }

        double result = acc.reduceLanes(VectorOperators.ADD);

        for (; i < length; i++) {
            result += data[i];
        }

        return result;
    }

    private static double sumDoubleScalar(double[] data) {
        double result = 0.0;
        for (double v : data) {
            result += v;
        }
        return result;
    }

    /**
     * Product of all elements using SIMD.
     */
    @TruffleBoundary
    public static double productDouble(double[] data) {
        int length = data.length;
        if (length == 0) return 1.0;
        if (length < SIMD_THRESHOLD_DOUBLE) return productDoubleScalar(data);

        int i = 0;
        int bound = DOUBLE_SPECIES.loopBound(length);
        DoubleVector acc = DoubleVector.broadcast(DOUBLE_SPECIES, 1.0);

        for (; i < bound; i += DOUBLE_SPECIES.length()) {
            DoubleVector v = DoubleVector.fromArray(DOUBLE_SPECIES, data, i);
            acc = acc.mul(v);
        }

        double result = acc.reduceLanes(VectorOperators.MUL);

        for (; i < length; i++) {
            result *= data[i];
        }

        return result;
    }

    private static double productDoubleScalar(double[] data) {
        double result = 1.0;
        for (double v : data) {
            result *= v;
        }
        return result;
    }

    /**
     * Minimum element using SIMD.
     */
    @TruffleBoundary
    public static double minDouble(double[] data) {
        int length = data.length;
        if (length == 0) throw new IllegalArgumentException("Empty array");
        if (length < SIMD_THRESHOLD_DOUBLE) return minDoubleScalar(data);

        int i = 0;
        int bound = DOUBLE_SPECIES.loopBound(length);
        DoubleVector acc = DoubleVector.broadcast(DOUBLE_SPECIES, Double.POSITIVE_INFINITY);

        for (; i < bound; i += DOUBLE_SPECIES.length()) {
            DoubleVector v = DoubleVector.fromArray(DOUBLE_SPECIES, data, i);
            acc = acc.min(v);
        }

        double result = acc.reduceLanes(VectorOperators.MIN);

        for (; i < length; i++) {
            if (data[i] < result) result = data[i];
        }

        return result;
    }

    private static double minDoubleScalar(double[] data) {
        double result = data[0];
        for (int i = 1; i < data.length; i++) {
            if (data[i] < result) result = data[i];
        }
        return result;
    }

    /**
     * Maximum element using SIMD.
     */
    @TruffleBoundary
    public static double maxDouble(double[] data) {
        int length = data.length;
        if (length == 0) throw new IllegalArgumentException("Empty array");
        if (length < SIMD_THRESHOLD_DOUBLE) return maxDoubleScalar(data);

        int i = 0;
        int bound = DOUBLE_SPECIES.loopBound(length);
        DoubleVector acc = DoubleVector.broadcast(DOUBLE_SPECIES, Double.NEGATIVE_INFINITY);

        for (; i < bound; i += DOUBLE_SPECIES.length()) {
            DoubleVector v = DoubleVector.fromArray(DOUBLE_SPECIES, data, i);
            acc = acc.max(v);
        }

        double result = acc.reduceLanes(VectorOperators.MAX);

        for (; i < length; i++) {
            if (data[i] > result) result = data[i];
        }

        return result;
    }

    private static double maxDoubleScalar(double[] data) {
        double result = data[0];
        for (int i = 1; i < data.length; i++) {
            if (data[i] > result) result = data[i];
        }
        return result;
    }

    /**
     * Dot product using SIMD.
     */
    @TruffleBoundary
    public static double dotProduct(double[] a, double[] b) {
        int length = Math.min(a.length, b.length);
        if (length == 0) return 0.0;
        if (length < SIMD_THRESHOLD_DOUBLE) return dotProductScalar(a, b, length);

        int i = 0;
        int bound = DOUBLE_SPECIES.loopBound(length);
        DoubleVector acc = DoubleVector.zero(DOUBLE_SPECIES);

        for (; i < bound; i += DOUBLE_SPECIES.length()) {
            DoubleVector va = DoubleVector.fromArray(DOUBLE_SPECIES, a, i);
            DoubleVector vb = DoubleVector.fromArray(DOUBLE_SPECIES, b, i);
            acc = acc.add(va.mul(vb));
        }

        double result = acc.reduceLanes(VectorOperators.ADD);

        for (; i < length; i++) {
            result += a[i] * b[i];
        }

        return result;
    }

    private static double dotProductScalar(double[] a, double[] b, int length) {
        double result = 0.0;
        for (int i = 0; i < length; i++) {
            result += a[i] * b[i];
        }
        return result;
    }

    /**
     * Add two arrays element-wise using SIMD.
     */
    @TruffleBoundary
    public static double[] addDouble(double[] a, double[] b) {
        int length = Math.min(a.length, b.length);
        double[] result = new double[length];

        if (length < SIMD_THRESHOLD_DOUBLE) {
            for (int i = 0; i < length; i++) {
                result[i] = a[i] + b[i];
            }
            return result;
        }

        int i = 0;
        int bound = DOUBLE_SPECIES.loopBound(length);

        for (; i < bound; i += DOUBLE_SPECIES.length()) {
            DoubleVector va = DoubleVector.fromArray(DOUBLE_SPECIES, a, i);
            DoubleVector vb = DoubleVector.fromArray(DOUBLE_SPECIES, b, i);
            va.add(vb).intoArray(result, i);
        }

        for (; i < length; i++) {
            result[i] = a[i] + b[i];
        }

        return result;
    }

    /**
     * Scale array by constant using SIMD.
     */
    @TruffleBoundary
    public static double[] scaleDouble(double[] data, double factor) {
        int length = data.length;
        double[] result = new double[length];

        if (length < SIMD_THRESHOLD_DOUBLE) {
            for (int i = 0; i < length; i++) {
                result[i] = data[i] * factor;
            }
            return result;
        }

        int i = 0;
        int bound = DOUBLE_SPECIES.loopBound(length);
        DoubleVector vfactor = DoubleVector.broadcast(DOUBLE_SPECIES, factor);

        for (; i < bound; i += DOUBLE_SPECIES.length()) {
            DoubleVector v = DoubleVector.fromArray(DOUBLE_SPECIES, data, i);
            v.mul(vfactor).intoArray(result, i);
        }

        for (; i < length; i++) {
            result[i] = data[i] * factor;
        }

        return result;
    }

    /**
     * Fused multiply-add: result[i] = a[i] * b[i] + c[i]
     */
    @TruffleBoundary
    public static double[] fmaDouble(double[] a, double[] b, double[] c) {
        int length = Math.min(Math.min(a.length, b.length), c.length);
        double[] result = new double[length];

        if (length < SIMD_THRESHOLD_DOUBLE) {
            for (int i = 0; i < length; i++) {
                result[i] = Math.fma(a[i], b[i], c[i]);
            }
            return result;
        }

        int i = 0;
        int bound = DOUBLE_SPECIES.loopBound(length);

        for (; i < bound; i += DOUBLE_SPECIES.length()) {
            DoubleVector va = DoubleVector.fromArray(DOUBLE_SPECIES, a, i);
            DoubleVector vb = DoubleVector.fromArray(DOUBLE_SPECIES, b, i);
            DoubleVector vc = DoubleVector.fromArray(DOUBLE_SPECIES, c, i);
            va.fma(vb, vc).intoArray(result, i);
        }

        for (; i < length; i++) {
            result[i] = Math.fma(a[i], b[i], c[i]);
        }

        return result;
    }

    // ==================== INT OPERATIONS ====================

    /**
     * Sum all elements using SIMD.
     */
    @TruffleBoundary
    public static int sumInt(int[] data) {
        int length = data.length;
        if (length == 0) return 0;
        if (length < INT_SPECIES.length() * 2) return sumIntScalar(data);

        int i = 0;
        int bound = INT_SPECIES.loopBound(length);
        IntVector acc = IntVector.zero(INT_SPECIES);

        for (; i < bound; i += INT_SPECIES.length()) {
            IntVector v = IntVector.fromArray(INT_SPECIES, data, i);
            acc = acc.add(v);
        }

        int result = acc.reduceLanes(VectorOperators.ADD);

        for (; i < length; i++) {
            result += data[i];
        }

        return result;
    }

    private static int sumIntScalar(int[] data) {
        int result = 0;
        for (int v : data) {
            result += v;
        }
        return result;
    }

    // ==================== BITWISE OPERATIONS ====================

    /**
     * Bitwise OR of two long arrays using SIMD.
     */
    @TruffleBoundary
    public static long[] orLong(long[] a, long[] b) {
        int length = Math.max(a.length, b.length);
        long[] result = new long[length];
        int minLength = Math.min(a.length, b.length);

        if (minLength < SIMD_THRESHOLD_LONG) {
            for (int i = 0; i < minLength; i++) {
                result[i] = a[i] | b[i];
            }
        } else {
            int i = 0;
            int bound = LONG_SPECIES.loopBound(minLength);

            for (; i < bound; i += LONG_SPECIES.length()) {
                LongVector va = LongVector.fromArray(LONG_SPECIES, a, i);
                LongVector vb = LongVector.fromArray(LONG_SPECIES, b, i);
                va.or(vb).intoArray(result, i);
            }

            for (; i < minLength; i++) {
                result[i] = a[i] | b[i];
            }
        }

        // Copy remaining from longer array
        if (a.length > minLength) {
            System.arraycopy(a, minLength, result, minLength, a.length - minLength);
        } else if (b.length > minLength) {
            System.arraycopy(b, minLength, result, minLength, b.length - minLength);
        }

        return result;
    }

    /**
     * Bitwise AND of two long arrays using SIMD.
     */
    @TruffleBoundary
    public static long[] andLong(long[] a, long[] b) {
        int minLength = Math.min(a.length, b.length);
        long[] result = new long[minLength];

        if (minLength < SIMD_THRESHOLD_LONG) {
            for (int i = 0; i < minLength; i++) {
                result[i] = a[i] & b[i];
            }
            return result;
        }

        int i = 0;
        int bound = LONG_SPECIES.loopBound(minLength);

        for (; i < bound; i += LONG_SPECIES.length()) {
            LongVector va = LongVector.fromArray(LONG_SPECIES, a, i);
            LongVector vb = LongVector.fromArray(LONG_SPECIES, b, i);
            va.and(vb).intoArray(result, i);
        }

        for (; i < minLength; i++) {
            result[i] = a[i] & b[i];
        }

        return result;
    }

    /**
     * Bitwise XOR of two long arrays using SIMD.
     */
    @TruffleBoundary
    public static long[] xorLong(long[] a, long[] b) {
        int length = Math.max(a.length, b.length);
        long[] result = new long[length];
        int minLength = Math.min(a.length, b.length);

        if (minLength < SIMD_THRESHOLD_LONG) {
            for (int i = 0; i < minLength; i++) {
                result[i] = a[i] ^ b[i];
            }
        } else {
            int i = 0;
            int bound = LONG_SPECIES.loopBound(minLength);

            for (; i < bound; i += LONG_SPECIES.length()) {
                LongVector va = LongVector.fromArray(LONG_SPECIES, a, i);
                LongVector vb = LongVector.fromArray(LONG_SPECIES, b, i);
                va.lanewise(VectorOperators.XOR, vb).intoArray(result, i);
            }

            for (; i < minLength; i++) {
                result[i] = a[i] ^ b[i];
            }
        }

        // Copy remaining from longer array (XOR with 0 = identity)
        if (a.length > minLength) {
            System.arraycopy(a, minLength, result, minLength, a.length - minLength);
        } else if (b.length > minLength) {
            System.arraycopy(b, minLength, result, minLength, b.length - minLength);
        }

        return result;
    }

    /**
     * Bitwise AND NOT (a & ~b) using SIMD.
     */
    @TruffleBoundary
    public static long[] andNotLong(long[] a, long[] b) {
        long[] result = new long[a.length];
        int minLength = Math.min(a.length, b.length);

        if (minLength < SIMD_THRESHOLD_LONG) {
            for (int i = 0; i < minLength; i++) {
                result[i] = a[i] & ~b[i];
            }
        } else {
            int i = 0;
            int bound = LONG_SPECIES.loopBound(minLength);

            for (; i < bound; i += LONG_SPECIES.length()) {
                LongVector va = LongVector.fromArray(LONG_SPECIES, a, i);
                LongVector vb = LongVector.fromArray(LONG_SPECIES, b, i);
                va.and(vb.not()).intoArray(result, i);
            }

            for (; i < minLength; i++) {
                result[i] = a[i] & ~b[i];
            }
        }

        // Copy remaining from a (AND with ~0 = identity)
        if (a.length > minLength) {
            System.arraycopy(a, minLength, result, minLength, a.length - minLength);
        }

        return result;
    }

    /**
     * Population count (number of set bits) using SIMD.
     */
    @TruffleBoundary
    public static int popCount(long[] data) {
        int count = 0;
        // Long.bitCount uses hardware POPCNT instruction
        for (long word : data) {
            count += Long.bitCount(word);
        }
        return count;
    }

    // ==================== COMPARISON ====================

    /**
     * Check if all elements in a are <= corresponding elements in b.
     */
    @TruffleBoundary
    public static boolean allLessOrEqualLong(long[] a, long[] b) {
        int length = Math.min(a.length, b.length);

        if (length < SIMD_THRESHOLD_LONG) {
            for (int i = 0; i < length; i++) {
                if (a[i] > b[i]) return false;
            }
            return true;
        }

        int i = 0;
        int bound = LONG_SPECIES.loopBound(length);

        for (; i < bound; i += LONG_SPECIES.length()) {
            LongVector va = LongVector.fromArray(LONG_SPECIES, a, i);
            LongVector vb = LongVector.fromArray(LONG_SPECIES, b, i);
            VectorMask<Long> mask = va.compare(VectorOperators.GT, vb);
            if (mask.anyTrue()) return false;
        }

        for (; i < length; i++) {
            if (a[i] > b[i]) return false;
        }

        return true;
    }

    /**
     * Check if arrays are equal.
     * Uses Arrays.equals which is intrinsified by the JVM for optimal SIMD.
     */
    @TruffleBoundary
    public static boolean equalsLong(long[] a, long[] b) {
        // Arrays.equals is intrinsified by Hotspot and uses optimal SIMD
        return java.util.Arrays.equals(a, b);
    }

    /**
     * Check if arrays are equal.
     * Uses Arrays.equals which is intrinsified by the JVM for optimal SIMD.
     */
    @TruffleBoundary
    public static boolean equalsDouble(double[] a, double[] b) {
        // Arrays.equals is intrinsified by Hotspot and uses optimal SIMD
        return java.util.Arrays.equals(a, b);
    }

    // ==================== FILL / COPY ====================

    /**
     * Fill array with value using SIMD.
     */
    @TruffleBoundary
    public static void fillLong(long[] data, long value) {
        int length = data.length;

        if (length < SIMD_THRESHOLD_LONG) {
            java.util.Arrays.fill(data, value);
            return;
        }

        int i = 0;
        int bound = LONG_SPECIES.loopBound(length);
        LongVector vfill = LongVector.broadcast(LONG_SPECIES, value);

        for (; i < bound; i += LONG_SPECIES.length()) {
            vfill.intoArray(data, i);
        }

        for (; i < length; i++) {
            data[i] = value;
        }
    }

    /**
     * Fill array with value using SIMD.
     */
    @TruffleBoundary
    public static void fillDouble(double[] data, double value) {
        int length = data.length;

        if (length < SIMD_THRESHOLD_DOUBLE) {
            java.util.Arrays.fill(data, value);
            return;
        }

        int i = 0;
        int bound = DOUBLE_SPECIES.loopBound(length);
        DoubleVector vfill = DoubleVector.broadcast(DOUBLE_SPECIES, value);

        for (; i < bound; i += DOUBLE_SPECIES.length()) {
            vfill.intoArray(data, i);
        }

        for (; i < length; i++) {
            data[i] = value;
        }
    }

    /**
     * Subtract arrays element-wise: result[i] = a[i] - b[i]
     */
    @TruffleBoundary
    public static long[] subtractLong(long[] a, long[] b) {
        int length = Math.min(a.length, b.length);
        long[] result = new long[length];

        if (length < SIMD_THRESHOLD_LONG) {
            for (int i = 0; i < length; i++) {
                result[i] = a[i] - b[i];
            }
            return result;
        }

        int i = 0;
        int bound = LONG_SPECIES.loopBound(length);

        for (; i < bound; i += LONG_SPECIES.length()) {
            LongVector va = LongVector.fromArray(LONG_SPECIES, a, i);
            LongVector vb = LongVector.fromArray(LONG_SPECIES, b, i);
            va.sub(vb).intoArray(result, i);
        }

        for (; i < length; i++) {
            result[i] = a[i] - b[i];
        }

        return result;
    }

    /**
     * Subtract arrays element-wise: result[i] = a[i] - b[i]
     */
    @TruffleBoundary
    public static double[] subtractDouble(double[] a, double[] b) {
        int length = Math.min(a.length, b.length);
        double[] result = new double[length];

        if (length < SIMD_THRESHOLD_DOUBLE) {
            for (int i = 0; i < length; i++) {
                result[i] = a[i] - b[i];
            }
            return result;
        }

        int i = 0;
        int bound = DOUBLE_SPECIES.loopBound(length);

        for (; i < bound; i += DOUBLE_SPECIES.length()) {
            DoubleVector va = DoubleVector.fromArray(DOUBLE_SPECIES, a, i);
            DoubleVector vb = DoubleVector.fromArray(DOUBLE_SPECIES, b, i);
            va.sub(vb).intoArray(result, i);
        }

        for (; i < length; i++) {
            result[i] = a[i] - b[i];
        }

        return result;
    }

    /**
     * Negate all elements: result[i] = -data[i]
     */
    @TruffleBoundary
    public static long[] negateLong(long[] data) {
        int length = data.length;
        long[] result = new long[length];

        if (length < SIMD_THRESHOLD_LONG) {
            for (int i = 0; i < length; i++) {
                result[i] = -data[i];
            }
            return result;
        }

        int i = 0;
        int bound = LONG_SPECIES.loopBound(length);

        for (; i < bound; i += LONG_SPECIES.length()) {
            LongVector v = LongVector.fromArray(LONG_SPECIES, data, i);
            v.neg().intoArray(result, i);
        }

        for (; i < length; i++) {
            result[i] = -data[i];
        }

        return result;
    }

    /**
     * Negate all elements: result[i] = -data[i]
     */
    @TruffleBoundary
    public static double[] negateDouble(double[] data) {
        int length = data.length;
        double[] result = new double[length];

        if (length < SIMD_THRESHOLD_DOUBLE) {
            for (int i = 0; i < length; i++) {
                result[i] = -data[i];
            }
            return result;
        }

        int i = 0;
        int bound = DOUBLE_SPECIES.loopBound(length);

        for (; i < bound; i += DOUBLE_SPECIES.length()) {
            DoubleVector v = DoubleVector.fromArray(DOUBLE_SPECIES, data, i);
            v.neg().intoArray(result, i);
        }

        for (; i < length; i++) {
            result[i] = -data[i];
        }

        return result;
    }

    /**
     * Absolute value: result[i] = |data[i]|
     */
    @TruffleBoundary
    public static long[] absLong(long[] data) {
        int length = data.length;
        long[] result = new long[length];

        if (length < SIMD_THRESHOLD_LONG) {
            for (int i = 0; i < length; i++) {
                result[i] = Math.abs(data[i]);
            }
            return result;
        }

        int i = 0;
        int bound = LONG_SPECIES.loopBound(length);

        for (; i < bound; i += LONG_SPECIES.length()) {
            LongVector v = LongVector.fromArray(LONG_SPECIES, data, i);
            v.abs().intoArray(result, i);
        }

        for (; i < length; i++) {
            result[i] = Math.abs(data[i]);
        }

        return result;
    }

    /**
     * Absolute value: result[i] = |data[i]|
     */
    @TruffleBoundary
    public static double[] absDouble(double[] data) {
        int length = data.length;
        double[] result = new double[length];

        if (length < SIMD_THRESHOLD_DOUBLE) {
            for (int i = 0; i < length; i++) {
                result[i] = Math.abs(data[i]);
            }
            return result;
        }

        int i = 0;
        int bound = DOUBLE_SPECIES.loopBound(length);

        for (; i < bound; i += DOUBLE_SPECIES.length()) {
            DoubleVector v = DoubleVector.fromArray(DOUBLE_SPECIES, data, i);
            v.abs().intoArray(result, i);
        }

        for (; i < length; i++) {
            result[i] = Math.abs(data[i]);
        }

        return result;
    }

    /**
     * Count elements matching value.
     */
    @TruffleBoundary
    public static int countLong(long[] data, long value) {
        int length = data.length;
        int count = 0;

        if (length < SIMD_THRESHOLD_LONG) {
            for (int i = 0; i < length; i++) {
                if (data[i] == value) count++;
            }
            return count;
        }

        int i = 0;
        int bound = LONG_SPECIES.loopBound(length);
        LongVector target = LongVector.broadcast(LONG_SPECIES, value);

        for (; i < bound; i += LONG_SPECIES.length()) {
            LongVector v = LongVector.fromArray(LONG_SPECIES, data, i);
            VectorMask<Long> mask = v.eq(target);
            count += mask.trueCount();
        }

        for (; i < length; i++) {
            if (data[i] == value) count++;
        }

        return count;
    }

    // ==================== DIVISION / MODULO ====================

    /**
     * Element-wise division: result[i] = a[i] / b[i]
     * Note: Integer division (truncates toward zero).
     */
    @TruffleBoundary
    public static long[] divideLong(long[] a, long[] b) {
        int length = Math.min(a.length, b.length);
        long[] result = new long[length];

        if (length < SIMD_THRESHOLD_LONG) {
            for (int i = 0; i < length; i++) {
                result[i] = a[i] / b[i];
            }
            return result;
        }

        int i = 0;
        int bound = LONG_SPECIES.loopBound(length);

        for (; i < bound; i += LONG_SPECIES.length()) {
            LongVector va = LongVector.fromArray(LONG_SPECIES, a, i);
            LongVector vb = LongVector.fromArray(LONG_SPECIES, b, i);
            va.div(vb).intoArray(result, i);
        }

        for (; i < length; i++) {
            result[i] = a[i] / b[i];
        }

        return result;
    }

    /**
     * Element-wise division: result[i] = a[i] / b[i]
     */
    @TruffleBoundary
    public static double[] divideDouble(double[] a, double[] b) {
        int length = Math.min(a.length, b.length);
        double[] result = new double[length];

        if (length < SIMD_THRESHOLD_DOUBLE) {
            for (int i = 0; i < length; i++) {
                result[i] = a[i] / b[i];
            }
            return result;
        }

        int i = 0;
        int bound = DOUBLE_SPECIES.loopBound(length);

        for (; i < bound; i += DOUBLE_SPECIES.length()) {
            DoubleVector va = DoubleVector.fromArray(DOUBLE_SPECIES, a, i);
            DoubleVector vb = DoubleVector.fromArray(DOUBLE_SPECIES, b, i);
            va.div(vb).intoArray(result, i);
        }

        for (; i < length; i++) {
            result[i] = a[i] / b[i];
        }

        return result;
    }

    /**
     * Element-wise modulo: result[i] = a[i] % b[i]
     * SIMD modulo via: a - (a / b) * b
     */
    @TruffleBoundary
    public static long[] moduloLong(long[] a, long[] b) {
        int length = Math.min(a.length, b.length);
        long[] result = new long[length];

        if (length < SIMD_THRESHOLD_LONG) {
            for (int i = 0; i < length; i++) {
                result[i] = a[i] % b[i];
            }
            return result;
        }

        int i = 0;
        int bound = LONG_SPECIES.loopBound(length);

        for (; i < bound; i += LONG_SPECIES.length()) {
            LongVector va = LongVector.fromArray(LONG_SPECIES, a, i);
            LongVector vb = LongVector.fromArray(LONG_SPECIES, b, i);
            // a % b = a - (a / b) * b
            LongVector quotient = va.div(vb);
            va.sub(quotient.mul(vb)).intoArray(result, i);
        }

        for (; i < length; i++) {
            result[i] = a[i] % b[i];
        }

        return result;
    }

    /**
     * Element-wise modulo: result[i] = a[i] % b[i]
     * SIMD modulo via: a - floor(a / b) * b
     */
    @TruffleBoundary
    public static double[] moduloDouble(double[] a, double[] b) {
        int length = Math.min(a.length, b.length);
        double[] result = new double[length];

        // Modulo for doubles is complex due to floor vs truncation
        // Use scalar for correctness
        for (int i = 0; i < length; i++) {
            result[i] = a[i] % b[i];
        }

        return result;
    }

    // ==================== SHIFT OPERATIONS ====================

    /**
     * Logical left shift: result[i] = a[i] << shift
     */
    @TruffleBoundary
    public static long[] shiftLeftLong(long[] data, int shift) {
        int length = data.length;
        long[] result = new long[length];

        if (length < SIMD_THRESHOLD_LONG) {
            for (int i = 0; i < length; i++) {
                result[i] = data[i] << shift;
            }
            return result;
        }

        int i = 0;
        int bound = LONG_SPECIES.loopBound(length);

        for (; i < bound; i += LONG_SPECIES.length()) {
            LongVector v = LongVector.fromArray(LONG_SPECIES, data, i);
            v.lanewise(VectorOperators.LSHL, shift).intoArray(result, i);
        }

        for (; i < length; i++) {
            result[i] = data[i] << shift;
        }

        return result;
    }

    /**
     * Logical right shift (unsigned): result[i] = a[i] >>> shift
     */
    @TruffleBoundary
    public static long[] shiftRightLogicalLong(long[] data, int shift) {
        int length = data.length;
        long[] result = new long[length];

        if (length < SIMD_THRESHOLD_LONG) {
            for (int i = 0; i < length; i++) {
                result[i] = data[i] >>> shift;
            }
            return result;
        }

        int i = 0;
        int bound = LONG_SPECIES.loopBound(length);

        for (; i < bound; i += LONG_SPECIES.length()) {
            LongVector v = LongVector.fromArray(LONG_SPECIES, data, i);
            v.lanewise(VectorOperators.LSHR, shift).intoArray(result, i);
        }

        for (; i < length; i++) {
            result[i] = data[i] >>> shift;
        }

        return result;
    }

    /**
     * Arithmetic right shift (signed): result[i] = a[i] >> shift
     */
    @TruffleBoundary
    public static long[] shiftRightArithmeticLong(long[] data, int shift) {
        int length = data.length;
        long[] result = new long[length];

        if (length < SIMD_THRESHOLD_LONG) {
            for (int i = 0; i < length; i++) {
                result[i] = data[i] >> shift;
            }
            return result;
        }

        int i = 0;
        int bound = LONG_SPECIES.loopBound(length);

        for (; i < bound; i += LONG_SPECIES.length()) {
            LongVector v = LongVector.fromArray(LONG_SPECIES, data, i);
            v.lanewise(VectorOperators.ASHR, shift).intoArray(result, i);
        }

        for (; i < length; i++) {
            result[i] = data[i] >> shift;
        }

        return result;
    }

    /**
     * Bitwise NOT: result[i] = ~data[i]
     */
    @TruffleBoundary
    public static long[] notLong(long[] data) {
        int length = data.length;
        long[] result = new long[length];

        if (length < SIMD_THRESHOLD_LONG) {
            for (int i = 0; i < length; i++) {
                result[i] = ~data[i];
            }
            return result;
        }

        int i = 0;
        int bound = LONG_SPECIES.loopBound(length);

        for (; i < bound; i += LONG_SPECIES.length()) {
            LongVector v = LongVector.fromArray(LONG_SPECIES, data, i);
            v.not().intoArray(result, i);
        }

        for (; i < length; i++) {
            result[i] = ~data[i];
        }

        return result;
    }

    /**
     * Rotate bits left: result[i] = Long.rotateLeft(data[i], distance)
     */
    @TruffleBoundary
    public static long[] rotateLeftLong(long[] data, int distance) {
        int length = data.length;
        long[] result = new long[length];

        if (length < SIMD_THRESHOLD_LONG) {
            for (int i = 0; i < length; i++) {
                result[i] = Long.rotateLeft(data[i], distance);
            }
            return result;
        }

        int i = 0;
        int bound = LONG_SPECIES.loopBound(length);

        for (; i < bound; i += LONG_SPECIES.length()) {
            LongVector v = LongVector.fromArray(LONG_SPECIES, data, i);
            v.lanewise(VectorOperators.ROL, distance).intoArray(result, i);
        }

        for (; i < length; i++) {
            result[i] = Long.rotateLeft(data[i], distance);
        }

        return result;
    }

    /**
     * Rotate bits right: result[i] = Long.rotateRight(data[i], distance)
     */
    @TruffleBoundary
    public static long[] rotateRightLong(long[] data, int distance) {
        int length = data.length;
        long[] result = new long[length];

        if (length < SIMD_THRESHOLD_LONG) {
            for (int i = 0; i < length; i++) {
                result[i] = Long.rotateRight(data[i], distance);
            }
            return result;
        }

        int i = 0;
        int bound = LONG_SPECIES.loopBound(length);

        for (; i < bound; i += LONG_SPECIES.length()) {
            LongVector v = LongVector.fromArray(LONG_SPECIES, data, i);
            v.lanewise(VectorOperators.ROR, distance).intoArray(result, i);
        }

        for (; i < length; i++) {
            result[i] = Long.rotateRight(data[i], distance);
        }

        return result;
    }

    // ==================== UTILITY ====================

    /**
     * Returns the SIMD lane width for longs.
     */
    public static int longLaneCount() {
        return LONG_SPECIES.length();
    }

    /**
     * Returns the SIMD lane width for doubles.
     */
    public static int doubleLaneCount() {
        return DOUBLE_SPECIES.length();
    }

    /**
     * Returns the SIMD lane width for ints.
     */
    public static int intLaneCount() {
        return INT_SPECIES.length();
    }
}
