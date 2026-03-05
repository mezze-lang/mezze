package com.mezze.datastructures.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;

/**
 * Tests for SIMD-optimized VectorOps.
 */
public class VectorOpsTest {

    private static final Random RANDOM = new Random(42);

    // ==================== LONG SUM ====================

    @Test
    void testSumLongEmpty() {
        assertEquals(0L, VectorOps.sumLong(new long[0]));
    }

    @Test
    void testSumLongSingle() {
        assertEquals(42L, VectorOps.sumLong(new long[]{42}));
    }

    @Test
    void testSumLongSmall() {
        assertEquals(15L, VectorOps.sumLong(new long[]{1, 2, 3, 4, 5}));
    }

    @Test
    void testSumLongLarge() {
        // Test with large array to trigger SIMD
        long[] data = new long[1000];
        long expected = 0;
        for (int i = 0; i < data.length; i++) {
            data[i] = i + 1;
            expected += data[i];
        }
        assertEquals(expected, VectorOps.sumLong(data));
    }

    @Test
    void testSumLongNegative() {
        assertEquals(-6L, VectorOps.sumLong(new long[]{-1, -2, -3}));
    }

    // ==================== LONG PRODUCT ====================

    @Test
    void testProductLongEmpty() {
        assertEquals(1L, VectorOps.productLong(new long[0]));
    }

    @Test
    void testProductLongSmall() {
        assertEquals(120L, VectorOps.productLong(new long[]{1, 2, 3, 4, 5}));
    }

    @Test
    void testProductLongLarge() {
        long[] data = new long[20];
        long expected = 1;
        for (int i = 0; i < data.length; i++) {
            data[i] = 2;
            expected *= 2;
        }
        assertEquals(expected, VectorOps.productLong(data));
    }

    // ==================== LONG MIN/MAX ====================

    @Test
    void testMinLongSmall() {
        assertEquals(1L, VectorOps.minLong(new long[]{5, 3, 1, 4, 2}));
    }

    @Test
    void testMinLongLarge() {
        long[] data = new long[1000];
        for (int i = 0; i < data.length; i++) {
            data[i] = i + 100;
        }
        data[500] = 1; // Put min in middle
        assertEquals(1L, VectorOps.minLong(data));
    }

    @Test
    void testMinLongNegative() {
        assertEquals(-10L, VectorOps.minLong(new long[]{5, -3, -10, 4, 2}));
    }

    @Test
    void testMaxLongSmall() {
        assertEquals(5L, VectorOps.maxLong(new long[]{5, 3, 1, 4, 2}));
    }

    @Test
    void testMaxLongLarge() {
        long[] data = new long[1000];
        for (int i = 0; i < data.length; i++) {
            data[i] = i;
        }
        data[500] = 10000; // Put max in middle
        assertEquals(10000L, VectorOps.maxLong(data));
    }

    // ==================== LONG INDEXOF ====================

    @Test
    void testIndexOfLongFound() {
        assertEquals(2, VectorOps.indexOfLong(new long[]{1, 2, 3, 4, 5}, 3));
    }

    @Test
    void testIndexOfLongNotFound() {
        assertEquals(-1, VectorOps.indexOfLong(new long[]{1, 2, 3, 4, 5}, 99));
    }

    @Test
    void testIndexOfLongLarge() {
        long[] data = new long[1000];
        for (int i = 0; i < data.length; i++) {
            data[i] = i * 2;
        }
        assertEquals(500, VectorOps.indexOfLong(data, 1000));
        assertEquals(-1, VectorOps.indexOfLong(data, 1001));
    }

    // ==================== LONG ADD/SCALE ====================

    @Test
    void testAddLong() {
        long[] a = {1, 2, 3, 4};
        long[] b = {10, 20, 30, 40};
        long[] result = VectorOps.addLong(a, b);
        assertArrayEquals(new long[]{11, 22, 33, 44}, result);
    }

    @Test
    void testScaleLong() {
        long[] data = {1, 2, 3, 4, 5};
        long[] result = VectorOps.scaleLong(data, 10);
        assertArrayEquals(new long[]{10, 20, 30, 40, 50}, result);
    }

    // ==================== DOUBLE SUM ====================

    @Test
    void testSumDoubleEmpty() {
        assertEquals(0.0, VectorOps.sumDouble(new double[0]), 1e-10);
    }

    @Test
    void testSumDoubleSmall() {
        assertEquals(15.0, VectorOps.sumDouble(new double[]{1, 2, 3, 4, 5}), 1e-10);
    }

    @Test
    void testSumDoubleLarge() {
        double[] data = new double[1000];
        double expected = 0;
        for (int i = 0; i < data.length; i++) {
            data[i] = i + 0.5;
            expected += data[i];
        }
        assertEquals(expected, VectorOps.sumDouble(data), 1e-6);
    }

    // ==================== DOUBLE MIN/MAX ====================

    @Test
    void testMinDoubleSmall() {
        assertEquals(1.1, VectorOps.minDouble(new double[]{5.5, 3.3, 1.1, 4.4, 2.2}), 1e-10);
    }

    @Test
    void testMaxDoubleSmall() {
        assertEquals(5.5, VectorOps.maxDouble(new double[]{5.5, 3.3, 1.1, 4.4, 2.2}), 1e-10);
    }

    // ==================== DOT PRODUCT ====================

    @Test
    void testDotProductSmall() {
        double[] a = {1, 2, 3};
        double[] b = {4, 5, 6};
        // 1*4 + 2*5 + 3*6 = 4 + 10 + 18 = 32
        assertEquals(32.0, VectorOps.dotProduct(a, b), 1e-10);
    }

    @Test
    void testDotProductLarge() {
        double[] a = new double[1000];
        double[] b = new double[1000];
        double expected = 0;
        for (int i = 0; i < 1000; i++) {
            a[i] = i + 1;
            b[i] = 2;
            expected += a[i] * b[i];
        }
        assertEquals(expected, VectorOps.dotProduct(a, b), 1e-6);
    }

    // ==================== DOUBLE ADD/SCALE ====================

    @Test
    void testAddDouble() {
        double[] a = {1.0, 2.0, 3.0, 4.0};
        double[] b = {0.1, 0.2, 0.3, 0.4};
        double[] result = VectorOps.addDouble(a, b);
        assertArrayEquals(new double[]{1.1, 2.2, 3.3, 4.4}, result, 1e-10);
    }

    @Test
    void testScaleDouble() {
        double[] data = {1.0, 2.0, 3.0, 4.0, 5.0};
        double[] result = VectorOps.scaleDouble(data, 2.5);
        assertArrayEquals(new double[]{2.5, 5.0, 7.5, 10.0, 12.5}, result, 1e-10);
    }

    // ==================== FMA ====================

    @Test
    void testFmaDouble() {
        double[] a = {1.0, 2.0, 3.0};
        double[] b = {2.0, 3.0, 4.0};
        double[] c = {10.0, 20.0, 30.0};
        double[] result = VectorOps.fmaDouble(a, b, c);
        // a*b + c
        assertArrayEquals(new double[]{12.0, 26.0, 42.0}, result, 1e-10);
    }

    // ==================== BITWISE ====================

    @Test
    void testOrLong() {
        long[] a = {0b1010, 0b1100};
        long[] b = {0b0110, 0b0011};
        long[] result = VectorOps.orLong(a, b);
        assertArrayEquals(new long[]{0b1110, 0b1111}, result);
    }

    @Test
    void testAndLong() {
        long[] a = {0b1010, 0b1100};
        long[] b = {0b0110, 0b0011};
        long[] result = VectorOps.andLong(a, b);
        assertArrayEquals(new long[]{0b0010, 0b0000}, result);
    }

    @Test
    void testXorLong() {
        long[] a = {0b1010, 0b1100};
        long[] b = {0b0110, 0b0011};
        long[] result = VectorOps.xorLong(a, b);
        assertArrayEquals(new long[]{0b1100, 0b1111}, result);
    }

    @Test
    void testAndNotLong() {
        long[] a = {0b1111, 0b1111};
        long[] b = {0b0101, 0b0011};
        long[] result = VectorOps.andNotLong(a, b);
        assertArrayEquals(new long[]{0b1010, 0b1100}, result);
    }

    @Test
    void testPopCount() {
        long[] data = {0b1010101010L, 0b11111111L};
        assertEquals(5 + 8, VectorOps.popCount(data));
    }

    // ==================== COMPARISON ====================

    @Test
    void testEqualsLong() {
        long[] a = {1, 2, 3, 4, 5};
        long[] b = {1, 2, 3, 4, 5};
        long[] c = {1, 2, 3, 4, 6};
        assertTrue(VectorOps.equalsLong(a, b));
        assertFalse(VectorOps.equalsLong(a, c));
    }

    // ==================== LANE COUNT ====================

    @Test
    void testLaneCounts() {
        // Just verify these return positive values
        assertTrue(VectorOps.longLaneCount() > 0);
        assertTrue(VectorOps.doubleLaneCount() > 0);
        assertTrue(VectorOps.intLaneCount() > 0);
    }

    // ==================== STRESS TEST ====================

    @Test
    void testSumLongStress() {
        // Test various sizes to exercise both scalar and SIMD paths
        for (int size : new int[]{0, 1, 2, 3, 7, 8, 15, 16, 31, 32, 63, 64, 127, 128, 1000}) {
            long[] data = new long[size];
            long expected = 0;
            for (int i = 0; i < size; i++) {
                data[i] = RANDOM.nextLong() % 1000;
                expected += data[i];
            }
            assertEquals(expected, VectorOps.sumLong(data),
                "Failed for size " + size);
        }
    }

    @Test
    void testSumDoubleStress() {
        for (int size : new int[]{0, 1, 2, 3, 7, 8, 15, 16, 31, 32, 63, 64, 127, 128, 1000}) {
            double[] data = new double[size];
            double expected = 0;
            for (int i = 0; i < size; i++) {
                data[i] = RANDOM.nextDouble() * 100;
                expected += data[i];
            }
            assertEquals(expected, VectorOps.sumDouble(data), 1e-6,
                "Failed for size " + size);
        }
    }

    // ==================== NEW OPERATIONS ====================

    @Test
    void testSubtractLong() {
        long[] a = {10, 20, 30, 40};
        long[] b = {1, 2, 3, 4};
        long[] result = VectorOps.subtractLong(a, b);
        assertArrayEquals(new long[]{9, 18, 27, 36}, result);
    }

    @Test
    void testSubtractDouble() {
        double[] a = {10.0, 20.0, 30.0, 40.0};
        double[] b = {1.0, 2.0, 3.0, 4.0};
        double[] result = VectorOps.subtractDouble(a, b);
        assertArrayEquals(new double[]{9.0, 18.0, 27.0, 36.0}, result, 1e-10);
    }

    @Test
    void testNegateLong() {
        long[] data = {1, -2, 3, -4, 5};
        long[] result = VectorOps.negateLong(data);
        assertArrayEquals(new long[]{-1, 2, -3, 4, -5}, result);
    }

    @Test
    void testNegateDouble() {
        double[] data = {1.0, -2.0, 3.0, -4.0};
        double[] result = VectorOps.negateDouble(data);
        assertArrayEquals(new double[]{-1.0, 2.0, -3.0, 4.0}, result, 1e-10);
    }

    @Test
    void testAbsLong() {
        long[] data = {-1, 2, -3, 4, -5};
        long[] result = VectorOps.absLong(data);
        assertArrayEquals(new long[]{1, 2, 3, 4, 5}, result);
    }

    @Test
    void testAbsDouble() {
        double[] data = {-1.0, 2.0, -3.0, 4.0};
        double[] result = VectorOps.absDouble(data);
        assertArrayEquals(new double[]{1.0, 2.0, 3.0, 4.0}, result, 1e-10);
    }

    @Test
    void testCountLong() {
        long[] data = {1, 2, 1, 3, 1, 4, 1};
        assertEquals(4, VectorOps.countLong(data, 1));
        assertEquals(1, VectorOps.countLong(data, 2));
        assertEquals(0, VectorOps.countLong(data, 99));
    }

    @Test
    void testCountLongLarge() {
        long[] data = new long[1000];
        for (int i = 0; i < 1000; i++) {
            data[i] = i % 10;
        }
        assertEquals(100, VectorOps.countLong(data, 0));
        assertEquals(100, VectorOps.countLong(data, 5));
    }

    @Test
    void testFillLong() {
        long[] data = new long[100];
        VectorOps.fillLong(data, 42L);
        for (int i = 0; i < 100; i++) {
            assertEquals(42L, data[i]);
        }
    }

    @Test
    void testFillDouble() {
        double[] data = new double[100];
        VectorOps.fillDouble(data, 3.14);
        for (int i = 0; i < 100; i++) {
            assertEquals(3.14, data[i], 1e-10);
        }
    }

    @Test
    void testEqualsDouble() {
        double[] a = {1.0, 2.0, 3.0, 4.0, 5.0};
        double[] b = {1.0, 2.0, 3.0, 4.0, 5.0};
        double[] c = {1.0, 2.0, 3.0, 4.0, 6.0};
        assertTrue(VectorOps.equalsDouble(a, b));
        assertFalse(VectorOps.equalsDouble(a, c));
    }
}
