package com.mezze.datastructures.nodes;

import com.mezze.datastructures.util.VectorOps;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Optimized sequence fill operations.
 *
 * Uses SIMD vectorized fill for primitives.
 */
public final class SeqFillNode {

    @NodeInfo(shortName = "seq.fillLong")
    public static abstract class FillLongNode extends Node {
        public abstract long[] execute(int count, long value);

        @Specialization(guards = "count <= 0")
        protected long[] fillEmpty(int count, long value) {
            return new long[0];
        }

        @Specialization(guards = "count > 0")
        @TruffleBoundary
        protected long[] fill(int count, long value) {
            long[] result = new long[count];
            VectorOps.fillLong(result, value);
            return result;
        }

        public static FillLongNode create() {
            return SeqFillNodeFactory.FillLongNodeGen.create();
        }
    }

    @NodeInfo(shortName = "seq.fillDouble")
    public static abstract class FillDoubleNode extends Node {
        public abstract double[] execute(int count, double value);

        @Specialization(guards = "count <= 0")
        protected double[] fillEmpty(int count, double value) {
            return new double[0];
        }

        @Specialization(guards = "count > 0")
        @TruffleBoundary
        protected double[] fill(int count, double value) {
            double[] result = new double[count];
            VectorOps.fillDouble(result, value);
            return result;
        }

        public static FillDoubleNode create() {
            return SeqFillNodeFactory.FillDoubleNodeGen.create();
        }
    }

    @NodeInfo(shortName = "seq.fillObject")
    public static abstract class FillObjectNode extends Node {
        public abstract Object[] execute(int count, Object value);

        @Specialization(guards = "count <= 0")
        protected Object[] fillEmpty(int count, Object value) {
            return new Object[0];
        }

        @Specialization(guards = "count > 0")
        @TruffleBoundary
        protected Object[] fill(int count, Object value) {
            Object[] result = new Object[count];
            java.util.Arrays.fill(result, value);
            return result;
        }

        public static FillObjectNode create() {
            return SeqFillNodeFactory.FillObjectNodeGen.create();
        }
    }

    private SeqFillNode() {
        // Utility class
    }
}
