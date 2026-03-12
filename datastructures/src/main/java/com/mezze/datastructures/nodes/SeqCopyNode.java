package com.mezze.datastructures.nodes;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

import java.util.Arrays;

/**
 * Optimized array copy operations.
 *
 * Uses System.arraycopy which is intrinsified for vectorized copy.
 */
public final class SeqCopyNode {

    @NodeInfo(shortName = "seq.copyLong")
    public static abstract class CopyLongNode extends Node {
        public abstract long[] execute(long[] src, int srcPos, int length);

        @Specialization(guards = "length == 0")
        protected long[] copyEmpty(long[] src, int srcPos, int length) {
            return new long[0];
        }

        @Specialization(guards = "length > 0")
        @TruffleBoundary
        protected long[] copy(long[] src, int srcPos, int length) {
            return Arrays.copyOfRange(src, srcPos, srcPos + length);
        }

        public static CopyLongNode create() {
            return SeqCopyNodeFactory.CopyLongNodeGen.create();
        }
    }

    @NodeInfo(shortName = "seq.copyDouble")
    public static abstract class CopyDoubleNode extends Node {
        public abstract double[] execute(double[] src, int srcPos, int length);

        @Specialization(guards = "length == 0")
        protected double[] copyEmpty(double[] src, int srcPos, int length) {
            return new double[0];
        }

        @Specialization(guards = "length > 0")
        @TruffleBoundary
        protected double[] copy(double[] src, int srcPos, int length) {
            return Arrays.copyOfRange(src, srcPos, srcPos + length);
        }

        public static CopyDoubleNode create() {
            return SeqCopyNodeFactory.CopyDoubleNodeGen.create();
        }
    }

    @NodeInfo(shortName = "seq.copyObject")
    public static abstract class CopyObjectNode extends Node {
        public abstract Object[] execute(Object[] src, int srcPos, int length);

        @Specialization(guards = "length == 0")
        protected Object[] copyEmpty(Object[] src, int srcPos, int length) {
            return new Object[0];
        }

        @Specialization(guards = "length > 0")
        @TruffleBoundary
        protected Object[] copy(Object[] src, int srcPos, int length) {
            return Arrays.copyOfRange(src, srcPos, srcPos + length);
        }

        public static CopyObjectNode create() {
            return SeqCopyNodeFactory.CopyObjectNodeGen.create();
        }
    }

    @NodeInfo(shortName = "seq.concatLong")
    public static abstract class ConcatLongNode extends Node {
        public abstract long[] execute(long[] a, long[] b);

        @Specialization(guards = "a.length == 0")
        protected long[] concatLeftEmpty(long[] a, long[] b) {
            return b;
        }

        @Specialization(guards = "b.length == 0")
        protected long[] concatRightEmpty(long[] a, long[] b) {
            return a;
        }

        @Specialization(guards = {"a.length > 0", "b.length > 0"})
        @TruffleBoundary
        protected long[] concat(long[] a, long[] b) {
            long[] result = new long[a.length + b.length];
            System.arraycopy(a, 0, result, 0, a.length);
            System.arraycopy(b, 0, result, a.length, b.length);
            return result;
        }

        public static ConcatLongNode create() {
            return SeqCopyNodeFactory.ConcatLongNodeGen.create();
        }
    }

    @NodeInfo(shortName = "seq.concatDouble")
    public static abstract class ConcatDoubleNode extends Node {
        public abstract double[] execute(double[] a, double[] b);

        @Specialization(guards = "a.length == 0")
        protected double[] concatLeftEmpty(double[] a, double[] b) {
            return b;
        }

        @Specialization(guards = "b.length == 0")
        protected double[] concatRightEmpty(double[] a, double[] b) {
            return a;
        }

        @Specialization(guards = {"a.length > 0", "b.length > 0"})
        @TruffleBoundary
        protected double[] concat(double[] a, double[] b) {
            double[] result = new double[a.length + b.length];
            System.arraycopy(a, 0, result, 0, a.length);
            System.arraycopy(b, 0, result, a.length, b.length);
            return result;
        }

        public static ConcatDoubleNode create() {
            return SeqCopyNodeFactory.ConcatDoubleNodeGen.create();
        }
    }

    @NodeInfo(shortName = "seq.concatObject")
    public static abstract class ConcatObjectNode extends Node {
        public abstract Object[] execute(Object[] a, Object[] b);

        @Specialization(guards = "a.length == 0")
        protected Object[] concatLeftEmpty(Object[] a, Object[] b) {
            return b;
        }

        @Specialization(guards = "b.length == 0")
        protected Object[] concatRightEmpty(Object[] a, Object[] b) {
            return a;
        }

        @Specialization(guards = {"a.length > 0", "b.length > 0"})
        @TruffleBoundary
        protected Object[] concat(Object[] a, Object[] b) {
            Object[] result = new Object[a.length + b.length];
            System.arraycopy(a, 0, result, 0, a.length);
            System.arraycopy(b, 0, result, a.length, b.length);
            return result;
        }

        public static ConcatObjectNode create() {
            return SeqCopyNodeFactory.ConcatObjectNodeGen.create();
        }
    }

    private SeqCopyNode() {
        // Utility class
    }
}
