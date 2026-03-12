package com.mezze.datastructures.nodes;

import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Optimized floating-point min/max operations.
 *
 * Math.min/max(double) handles NaN correctly and is intrinsified.
 */
public final class FloatMinMaxNode {

    @NodeInfo(shortName = "float.min")
    public static abstract class MinNode extends Node {
        public abstract double execute(double left, double right);

        @Specialization
        protected double min(double left, double right) {
            return Math.min(left, right);
        }

        public static MinNode create() {
            return FloatMinMaxNodeFactory.MinNodeGen.create();
        }
    }

    @NodeInfo(shortName = "float.max")
    public static abstract class MaxNode extends Node {
        public abstract double execute(double left, double right);

        @Specialization
        protected double max(double left, double right) {
            return Math.max(left, right);
        }

        public static MaxNode create() {
            return FloatMinMaxNodeFactory.MaxNodeGen.create();
        }
    }

    @NodeInfo(shortName = "float.clamp")
    public static abstract class ClampNode extends Node {
        public abstract double execute(double value, double min, double max);

        @Specialization
        protected double clamp(double value, double min, double max) {
            return Math.max(min, Math.min(max, value));
        }

        public static ClampNode create() {
            return FloatMinMaxNodeFactory.ClampNodeGen.create();
        }
    }

    private FloatMinMaxNode() {
        // Utility class
    }
}
