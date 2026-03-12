package com.mezze.datastructures.nodes;

import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Optimized floating-point comparison nodes.
 *
 * Handles NaN correctly according to IEEE 754.
 */
public final class FloatCompareNode {

    /**
     * Equality (NaN != NaN returns false).
     */
    @NodeInfo(shortName = "float.eq")
    public static abstract class EqNode extends Node {
        public abstract boolean execute(double left, double right);

        @Specialization
        protected boolean eq(double left, double right) {
            return left == right;
        }

        public static EqNode create() {
            return FloatCompareNodeFactory.EqNodeGen.create();
        }
    }

    /**
     * Not equal.
     */
    @NodeInfo(shortName = "float.ne")
    public static abstract class NeNode extends Node {
        public abstract boolean execute(double left, double right);

        @Specialization
        protected boolean ne(double left, double right) {
            return left != right;
        }

        public static NeNode create() {
            return FloatCompareNodeFactory.NeNodeGen.create();
        }
    }

    /**
     * Less than.
     */
    @NodeInfo(shortName = "float.lt")
    public static abstract class LtNode extends Node {
        public abstract boolean execute(double left, double right);

        @Specialization
        protected boolean lt(double left, double right) {
            return left < right;
        }

        public static LtNode create() {
            return FloatCompareNodeFactory.LtNodeGen.create();
        }
    }

    /**
     * Less than or equal.
     */
    @NodeInfo(shortName = "float.le")
    public static abstract class LeNode extends Node {
        public abstract boolean execute(double left, double right);

        @Specialization
        protected boolean le(double left, double right) {
            return left <= right;
        }

        public static LeNode create() {
            return FloatCompareNodeFactory.LeNodeGen.create();
        }
    }

    /**
     * Greater than.
     */
    @NodeInfo(shortName = "float.gt")
    public static abstract class GtNode extends Node {
        public abstract boolean execute(double left, double right);

        @Specialization
        protected boolean gt(double left, double right) {
            return left > right;
        }

        public static GtNode create() {
            return FloatCompareNodeFactory.GtNodeGen.create();
        }
    }

    /**
     * Greater than or equal.
     */
    @NodeInfo(shortName = "float.ge")
    public static abstract class GeNode extends Node {
        public abstract boolean execute(double left, double right);

        @Specialization
        protected boolean ge(double left, double right) {
            return left >= right;
        }

        public static GeNode create() {
            return FloatCompareNodeFactory.GeNodeGen.create();
        }
    }

    /**
     * Three-way comparison.
     */
    @NodeInfo(shortName = "float.compare3")
    public static abstract class Compare3Node extends Node {
        public abstract int execute(double left, double right);

        @Specialization
        protected int compare3(double left, double right) {
            return Double.compare(left, right);
        }

        public static Compare3Node create() {
            return FloatCompareNodeFactory.Compare3NodeGen.create();
        }
    }

    /**
     * Check if NaN.
     */
    @NodeInfo(shortName = "float.isNaN")
    public static abstract class IsNaNNode extends Node {
        public abstract boolean execute(double value);

        @Specialization
        protected boolean isNaN(double value) {
            return Double.isNaN(value);
        }

        public static IsNaNNode create() {
            return FloatCompareNodeFactory.IsNaNNodeGen.create();
        }
    }

    /**
     * Check if infinite.
     */
    @NodeInfo(shortName = "float.isInfinite")
    public static abstract class IsInfiniteNode extends Node {
        public abstract boolean execute(double value);

        @Specialization
        protected boolean isInfinite(double value) {
            return Double.isInfinite(value);
        }

        public static IsInfiniteNode create() {
            return FloatCompareNodeFactory.IsInfiniteNodeGen.create();
        }
    }

    /**
     * Check if finite (not NaN and not infinite).
     */
    @NodeInfo(shortName = "float.isFinite")
    public static abstract class IsFiniteNode extends Node {
        public abstract boolean execute(double value);

        @Specialization
        protected boolean isFinite(double value) {
            return Double.isFinite(value);
        }

        public static IsFiniteNode create() {
            return FloatCompareNodeFactory.IsFiniteNodeGen.create();
        }
    }

    /**
     * Inclusive range check.
     */
    @NodeInfo(shortName = "float.inRange")
    public static abstract class InRangeNode extends Node {
        public abstract boolean execute(double value, double min, double max);

        @Specialization
        protected boolean inRange(double value, double min, double max) {
            return value >= min && value <= max;
        }

        public static InRangeNode create() {
            return FloatCompareNodeFactory.InRangeNodeGen.create();
        }
    }

    private FloatCompareNode() {
        // Utility class
    }
}
