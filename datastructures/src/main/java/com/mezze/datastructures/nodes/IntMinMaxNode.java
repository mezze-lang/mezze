package com.mezze.datastructures.nodes;

import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Optimized integer min/max operations.
 *
 * Math.min/max are intrinsified to single cmov instruction.
 */
public final class IntMinMaxNode {

    @NodeInfo(shortName = "int.min")
    @GenerateUncached
    public static abstract class MinNode extends Node {
        public abstract long execute(long left, long right);

        @Specialization
        protected long min(long left, long right) {
            return Math.min(left, right);
        }

        public static MinNode create() {
            return IntMinMaxNodeFactory.MinNodeGen.create();
        }

        public static MinNode getUncached() {
            return IntMinMaxNodeFactory.MinNodeGen.getUncached();
        }
    }

    @NodeInfo(shortName = "int.max")
    @GenerateUncached
    public static abstract class MaxNode extends Node {
        public abstract long execute(long left, long right);

        @Specialization
        protected long max(long left, long right) {
            return Math.max(left, right);
        }

        public static MaxNode create() {
            return IntMinMaxNodeFactory.MaxNodeGen.create();
        }

        public static MaxNode getUncached() {
            return IntMinMaxNodeFactory.MaxNodeGen.getUncached();
        }
    }

    @NodeInfo(shortName = "int.clamp")
    @GenerateUncached
    public static abstract class ClampNode extends Node {
        public abstract long execute(long value, long min, long max);

        @Specialization
        protected long clamp(long value, long min, long max) {
            return Math.max(min, Math.min(max, value));
        }

        public static ClampNode create() {
            return IntMinMaxNodeFactory.ClampNodeGen.create();
        }

        public static ClampNode getUncached() {
            return IntMinMaxNodeFactory.ClampNodeGen.getUncached();
        }
    }

    private IntMinMaxNode() {
        // Utility class
    }
}
