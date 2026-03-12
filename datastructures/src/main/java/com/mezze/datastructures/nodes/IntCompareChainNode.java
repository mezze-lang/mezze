package com.mezze.datastructures.nodes;

import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Optimized comparison chain nodes.
 *
 * These nodes optimize common patterns like range checks (a < x && x < b)
 * into branchless code.
 */
public final class IntCompareChainNode {

    /**
     * Tests if a < b < c (exclusive range check).
     */
    @NodeInfo(shortName = "int.inRange")
    public static abstract class InRangeExclusiveNode extends Node {
        public abstract boolean execute(long value, long min, long max);

        @Specialization
        protected boolean inRange(long value, long min, long max) {
            // Branchless range check
            return value > min && value < max;
        }

        public static InRangeExclusiveNode create() {
            return IntCompareChainNodeFactory.InRangeExclusiveNodeGen.create();
        }
    }

    /**
     * Tests if min <= value <= max (inclusive range check).
     */
    @NodeInfo(shortName = "int.inRangeInclusive")
    public static abstract class InRangeInclusiveNode extends Node {
        public abstract boolean execute(long value, long min, long max);

        @Specialization
        protected boolean inRange(long value, long min, long max) {
            return value >= min && value <= max;
        }

        public static InRangeInclusiveNode create() {
            return IntCompareChainNodeFactory.InRangeInclusiveNodeGen.create();
        }
    }

    /**
     * Tests if 0 <= value < max (array bounds check pattern).
     */
    @NodeInfo(shortName = "int.inBounds")
    public static abstract class InBoundsNode extends Node {
        public abstract boolean execute(long value, long max);

        @Specialization
        protected boolean inBounds(long value, long max) {
            // Single unsigned comparison: treats negative as large positive
            return Long.compareUnsigned(value, max) < 0;
        }

        public static InBoundsNode create() {
            return IntCompareChainNodeFactory.InBoundsNodeGen.create();
        }
    }

    /**
     * Three-way comparison (like <=> in Scala).
     * Returns -1, 0, or 1.
     */
    @NodeInfo(shortName = "int.compare3")
    public static abstract class Compare3Node extends Node {
        public abstract int execute(long left, long right);

        @Specialization
        protected int compare3(long left, long right) {
            return Long.compare(left, right);
        }

        public static Compare3Node create() {
            return IntCompareChainNodeFactory.Compare3NodeGen.create();
        }
    }

    /**
     * Equality check.
     */
    @NodeInfo(shortName = "int.eq")
    public static abstract class EqNode extends Node {
        public abstract boolean execute(long left, long right);

        @Specialization
        protected boolean eq(long left, long right) {
            return left == right;
        }

        public static EqNode create() {
            return IntCompareChainNodeFactory.EqNodeGen.create();
        }
    }

    /**
     * Not equal check.
     */
    @NodeInfo(shortName = "int.ne")
    public static abstract class NeNode extends Node {
        public abstract boolean execute(long left, long right);

        @Specialization
        protected boolean ne(long left, long right) {
            return left != right;
        }

        public static NeNode create() {
            return IntCompareChainNodeFactory.NeNodeGen.create();
        }
    }

    /**
     * Less than.
     */
    @NodeInfo(shortName = "int.lt")
    public static abstract class LtNode extends Node {
        public abstract boolean execute(long left, long right);

        @Specialization
        protected boolean lt(long left, long right) {
            return left < right;
        }

        public static LtNode create() {
            return IntCompareChainNodeFactory.LtNodeGen.create();
        }
    }

    /**
     * Less than or equal.
     */
    @NodeInfo(shortName = "int.le")
    public static abstract class LeNode extends Node {
        public abstract boolean execute(long left, long right);

        @Specialization
        protected boolean le(long left, long right) {
            return left <= right;
        }

        public static LeNode create() {
            return IntCompareChainNodeFactory.LeNodeGen.create();
        }
    }

    /**
     * Greater than.
     */
    @NodeInfo(shortName = "int.gt")
    public static abstract class GtNode extends Node {
        public abstract boolean execute(long left, long right);

        @Specialization
        protected boolean gt(long left, long right) {
            return left > right;
        }

        public static GtNode create() {
            return IntCompareChainNodeFactory.GtNodeGen.create();
        }
    }

    /**
     * Greater than or equal.
     */
    @NodeInfo(shortName = "int.ge")
    public static abstract class GeNode extends Node {
        public abstract boolean execute(long left, long right);

        @Specialization
        protected boolean ge(long left, long right) {
            return left >= right;
        }

        public static GeNode create() {
            return IntCompareChainNodeFactory.GeNodeGen.create();
        }
    }

    private IntCompareChainNode() {
        // Utility class
    }
}
