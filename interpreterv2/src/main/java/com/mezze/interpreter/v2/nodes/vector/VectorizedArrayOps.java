package com.mezze.interpreter.v2.nodes.vector;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.values.MezzeIntArray;
import com.mezze.interpreter.v2.values.MezzeFloatArray;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * VECTORIZED ARRAY OPERATIONS for SIMD optimization.
 *
 * These nodes are designed to enable auto-vectorization by the JIT:
 * 1. Simple loop structures that the compiler can recognize
 * 2. No dependencies between iterations
 * 3. Contiguous memory access patterns
 * 4. Loop sizes that benefit from SIMD (> 8 elements)
 *
 * The JIT compiler (Graal) will attempt to vectorize these operations
 * using AVX/SSE instructions on x86 or NEON on ARM.
 */
public final class VectorizedArrayOps {

    // Prevent instantiation
    private VectorizedArrayOps() {}

    // ==================== VECTOR ADD ====================

    /**
     * Vector addition: result[i] = a[i] + b[i]
     * Optimized for SIMD auto-vectorization.
     */
    @NodeChild(value = "leftNode", type = MezzeNode.class)
    @NodeChild(value = "rightNode", type = MezzeNode.class)
    @NodeInfo(shortName = "vec-add")
    public abstract static class VectorAddNode extends MezzeNode {

        /**
         * Int array + Int array = Int array
         * Uses simple loop that JIT can vectorize.
         */
        @Specialization
        protected MezzeIntArray addIntArrays(MezzeIntArray left, MezzeIntArray right) {
            long[] a = left.getElements();
            long[] b = right.getElements();
            int len = Math.min(a.length, b.length);

            // VECTORIZATION HINT: Simple loop with no dependencies
            long[] result = new long[len];
            for (int i = 0; i < len; i++) {
                result[i] = a[i] + b[i];
            }

            return new MezzeIntArray(result);
        }

        /**
         * Float array + Float array = Float array
         */
        @Specialization
        protected MezzeFloatArray addFloatArrays(MezzeFloatArray left, MezzeFloatArray right) {
            double[] a = left.getElements();
            double[] b = right.getElements();
            int len = Math.min(a.length, b.length);

            // VECTORIZATION HINT: Simple loop with no dependencies
            double[] result = new double[len];
            for (int i = 0; i < len; i++) {
                result[i] = a[i] + b[i];
            }

            return new MezzeFloatArray(result);
        }

        public static VectorAddNode create(MezzeNode left, MezzeNode right) {
            return VectorizedArrayOpsFactory.VectorAddNodeGen.create(left, right);
        }
    }

    // ==================== VECTOR MULTIPLY ====================

    /**
     * Vector multiplication: result[i] = a[i] * b[i]
     */
    @NodeChild(value = "leftNode", type = MezzeNode.class)
    @NodeChild(value = "rightNode", type = MezzeNode.class)
    @NodeInfo(shortName = "vec-mul")
    public abstract static class VectorMulNode extends MezzeNode {

        @Specialization
        protected MezzeIntArray mulIntArrays(MezzeIntArray left, MezzeIntArray right) {
            long[] a = left.getElements();
            long[] b = right.getElements();
            int len = Math.min(a.length, b.length);

            long[] result = new long[len];
            for (int i = 0; i < len; i++) {
                result[i] = a[i] * b[i];
            }

            return new MezzeIntArray(result);
        }

        @Specialization
        protected MezzeFloatArray mulFloatArrays(MezzeFloatArray left, MezzeFloatArray right) {
            double[] a = left.getElements();
            double[] b = right.getElements();
            int len = Math.min(a.length, b.length);

            double[] result = new double[len];
            for (int i = 0; i < len; i++) {
                result[i] = a[i] * b[i];
            }

            return new MezzeFloatArray(result);
        }

        public static VectorMulNode create(MezzeNode left, MezzeNode right) {
            return VectorizedArrayOpsFactory.VectorMulNodeGen.create(left, right);
        }
    }

    // ==================== SCALAR-VECTOR OPERATIONS ====================

    /**
     * Scalar-vector multiplication: result[i] = scalar * a[i]
     */
    @NodeChild(value = "scalarNode", type = MezzeNode.class)
    @NodeChild(value = "arrayNode", type = MezzeNode.class)
    @NodeInfo(shortName = "scalar-mul")
    public abstract static class ScalarMulNode extends MezzeNode {

        @Specialization
        protected MezzeIntArray scalarMulInt(long scalar, MezzeIntArray array) {
            long[] a = array.getElements();
            int len = a.length;

            long[] result = new long[len];
            for (int i = 0; i < len; i++) {
                result[i] = scalar * a[i];
            }

            return new MezzeIntArray(result);
        }

        @Specialization
        protected MezzeFloatArray scalarMulFloat(double scalar, MezzeFloatArray array) {
            double[] a = array.getElements();
            int len = a.length;

            double[] result = new double[len];
            for (int i = 0; i < len; i++) {
                result[i] = scalar * a[i];
            }

            return new MezzeFloatArray(result);
        }

        public static ScalarMulNode create(MezzeNode scalar, MezzeNode array) {
            return VectorizedArrayOpsFactory.ScalarMulNodeGen.create(scalar, array);
        }
    }

    // ==================== REDUCTION OPERATIONS ====================

    /**
     * Sum reduction: result = sum(a[i])
     * Uses chunked reduction for better vectorization.
     */
    @NodeChild(value = "arrayNode", type = MezzeNode.class)
    @NodeInfo(shortName = "vec-sum")
    public abstract static class VectorSumNode extends MezzeNode {

        /**
         * Sum of int array.
         * Uses 4-way parallel reduction for vectorization.
         */
        @Specialization
        protected long sumIntArray(MezzeIntArray array) {
            long[] a = array.getElements();
            int len = a.length;

            if (len == 0) return 0L;
            if (len < 8) return sumSmall(a, len);

            // 4-WAY PARALLEL REDUCTION for SIMD
            // The JIT can vectorize this into 4 parallel sums
            long sum0 = 0, sum1 = 0, sum2 = 0, sum3 = 0;
            int i = 0;
            int limit = len - 3;

            while (i < limit) {
                sum0 += a[i];
                sum1 += a[i + 1];
                sum2 += a[i + 2];
                sum3 += a[i + 3];
                i += 4;
            }

            // Handle remaining elements
            long sum = sum0 + sum1 + sum2 + sum3;
            while (i < len) {
                sum += a[i++];
            }

            return sum;
        }

        private long sumSmall(long[] a, int len) {
            long sum = 0;
            for (int i = 0; i < len; i++) {
                sum += a[i];
            }
            return sum;
        }

        @Specialization
        protected double sumFloatArray(MezzeFloatArray array) {
            double[] a = array.getElements();
            int len = a.length;

            if (len == 0) return 0.0;
            if (len < 8) return sumSmallDouble(a, len);

            // 4-WAY PARALLEL REDUCTION
            double sum0 = 0, sum1 = 0, sum2 = 0, sum3 = 0;
            int i = 0;
            int limit = len - 3;

            while (i < limit) {
                sum0 += a[i];
                sum1 += a[i + 1];
                sum2 += a[i + 2];
                sum3 += a[i + 3];
                i += 4;
            }

            double sum = sum0 + sum1 + sum2 + sum3;
            while (i < len) {
                sum += a[i++];
            }

            return sum;
        }

        private double sumSmallDouble(double[] a, int len) {
            double sum = 0;
            for (int i = 0; i < len; i++) {
                sum += a[i];
            }
            return sum;
        }

        public static VectorSumNode create(MezzeNode array) {
            return VectorizedArrayOpsFactory.VectorSumNodeGen.create(array);
        }
    }

    // ==================== DOT PRODUCT ====================

    /**
     * Dot product: result = sum(a[i] * b[i])
     * Fused multiply-add for optimal SIMD.
     */
    @NodeChild(value = "leftNode", type = MezzeNode.class)
    @NodeChild(value = "rightNode", type = MezzeNode.class)
    @NodeInfo(shortName = "vec-dot")
    public abstract static class VectorDotNode extends MezzeNode {

        @Specialization
        protected long dotIntArrays(MezzeIntArray left, MezzeIntArray right) {
            long[] a = left.getElements();
            long[] b = right.getElements();
            int len = Math.min(a.length, b.length);

            // 4-WAY PARALLEL FMA (Fused Multiply-Add)
            long sum0 = 0, sum1 = 0, sum2 = 0, sum3 = 0;
            int i = 0;
            int limit = len - 3;

            while (i < limit) {
                sum0 += a[i] * b[i];
                sum1 += a[i + 1] * b[i + 1];
                sum2 += a[i + 2] * b[i + 2];
                sum3 += a[i + 3] * b[i + 3];
                i += 4;
            }

            long sum = sum0 + sum1 + sum2 + sum3;
            while (i < len) {
                sum += a[i] * b[i];
                i++;
            }

            return sum;
        }

        @Specialization
        protected double dotFloatArrays(MezzeFloatArray left, MezzeFloatArray right) {
            double[] a = left.getElements();
            double[] b = right.getElements();
            int len = Math.min(a.length, b.length);

            // 4-WAY PARALLEL FMA
            double sum0 = 0, sum1 = 0, sum2 = 0, sum3 = 0;
            int i = 0;
            int limit = len - 3;

            while (i < limit) {
                sum0 = Math.fma(a[i], b[i], sum0);
                sum1 = Math.fma(a[i + 1], b[i + 1], sum1);
                sum2 = Math.fma(a[i + 2], b[i + 2], sum2);
                sum3 = Math.fma(a[i + 3], b[i + 3], sum3);
                i += 4;
            }

            double sum = sum0 + sum1 + sum2 + sum3;
            while (i < len) {
                sum = Math.fma(a[i], b[i], sum);
                i++;
            }

            return sum;
        }

        public static VectorDotNode create(MezzeNode left, MezzeNode right) {
            return VectorizedArrayOpsFactory.VectorDotNodeGen.create(left, right);
        }
    }

    // ==================== MAP OPERATIONS ====================

    /**
     * Map a unary operation over an array.
     * Used for operations like negate, abs, sqrt, etc.
     */
    @NodeInfo(shortName = "vec-map")
    public abstract static class VectorMapNode extends MezzeNode {

        public enum UnaryOp {
            NEGATE, ABS, SQRT, SQUARE
        }

        @Child private MezzeNode arrayNode;
        @CompilationFinal private final UnaryOp op;

        protected VectorMapNode(MezzeNode arrayNode, UnaryOp op) {
            this.arrayNode = arrayNode;
            this.op = op;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object array = arrayNode.execute(frame);

            if (array instanceof MezzeIntArray intArray) {
                return mapIntArray(intArray);
            } else if (array instanceof MezzeFloatArray floatArray) {
                return mapFloatArray(floatArray);
            }

            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("Expected array, got: " + array.getClass().getSimpleName());
        }

        private MezzeIntArray mapIntArray(MezzeIntArray array) {
            long[] a = array.getElements();
            int len = a.length;
            long[] result = new long[len];

            for (int i = 0; i < len; i++) {
                result[i] = applyIntOp(a[i]);
            }

            return new MezzeIntArray(result);
        }

        private long applyIntOp(long x) {
            return switch (op) {
                case NEGATE -> -x;
                case ABS -> Math.abs(x);
                case SQRT -> (long) Math.sqrt(x);
                case SQUARE -> x * x;
            };
        }

        private MezzeFloatArray mapFloatArray(MezzeFloatArray array) {
            double[] a = array.getElements();
            int len = a.length;
            double[] result = new double[len];

            for (int i = 0; i < len; i++) {
                result[i] = applyDoubleOp(a[i]);
            }

            return new MezzeFloatArray(result);
        }

        private double applyDoubleOp(double x) {
            return switch (op) {
                case NEGATE -> -x;
                case ABS -> Math.abs(x);
                case SQRT -> Math.sqrt(x);
                case SQUARE -> x * x;
            };
        }

        public static VectorMapNode create(MezzeNode array, UnaryOp op) {
            return new VectorMapNode(array, op) {};
        }
    }
}
