package com.mezze.datastructures.nodes;

import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Optimized integer bitwise operations node.
 *
 * Provides AND, OR, XOR, NOT, shifts in single instruction form.
 */
public final class IntBitwiseNode {

    @NodeInfo(shortName = "int.and")
    @GenerateUncached
    public static abstract class AndNode extends Node {
        public abstract long execute(long left, long right);

        @Specialization
        protected long and(long left, long right) {
            return left & right;
        }

        public static AndNode create() {
            return IntBitwiseNodeFactory.AndNodeGen.create();
        }

        public static AndNode getUncached() {
            return IntBitwiseNodeFactory.AndNodeGen.getUncached();
        }
    }

    @NodeInfo(shortName = "int.or")
    @GenerateUncached
    public static abstract class OrNode extends Node {
        public abstract long execute(long left, long right);

        @Specialization
        protected long or(long left, long right) {
            return left | right;
        }

        public static OrNode create() {
            return IntBitwiseNodeFactory.OrNodeGen.create();
        }

        public static OrNode getUncached() {
            return IntBitwiseNodeFactory.OrNodeGen.getUncached();
        }
    }

    @NodeInfo(shortName = "int.xor")
    @GenerateUncached
    public static abstract class XorNode extends Node {
        public abstract long execute(long left, long right);

        @Specialization
        protected long xor(long left, long right) {
            return left ^ right;
        }

        public static XorNode create() {
            return IntBitwiseNodeFactory.XorNodeGen.create();
        }

        public static XorNode getUncached() {
            return IntBitwiseNodeFactory.XorNodeGen.getUncached();
        }
    }

    @NodeInfo(shortName = "int.not")
    @GenerateUncached
    public static abstract class NotNode extends Node {
        public abstract long execute(long value);

        @Specialization
        protected long not(long value) {
            return ~value;
        }

        public static NotNode create() {
            return IntBitwiseNodeFactory.NotNodeGen.create();
        }

        public static NotNode getUncached() {
            return IntBitwiseNodeFactory.NotNodeGen.getUncached();
        }
    }

    @NodeInfo(shortName = "int.shl")
    @GenerateUncached
    public static abstract class ShiftLeftNode extends Node {
        public abstract long execute(long value, long shift);

        @Specialization
        protected long shl(long value, long shift) {
            return value << shift;
        }

        public static ShiftLeftNode create() {
            return IntBitwiseNodeFactory.ShiftLeftNodeGen.create();
        }

        public static ShiftLeftNode getUncached() {
            return IntBitwiseNodeFactory.ShiftLeftNodeGen.getUncached();
        }
    }

    @NodeInfo(shortName = "int.shr")
    @GenerateUncached
    public static abstract class ShiftRightNode extends Node {
        public abstract long execute(long value, long shift);

        @Specialization
        protected long shr(long value, long shift) {
            return value >> shift;
        }

        public static ShiftRightNode create() {
            return IntBitwiseNodeFactory.ShiftRightNodeGen.create();
        }

        public static ShiftRightNode getUncached() {
            return IntBitwiseNodeFactory.ShiftRightNodeGen.getUncached();
        }
    }

    @NodeInfo(shortName = "int.ushr")
    @GenerateUncached
    public static abstract class UnsignedShiftRightNode extends Node {
        public abstract long execute(long value, long shift);

        @Specialization
        protected long ushr(long value, long shift) {
            return value >>> shift;
        }

        public static UnsignedShiftRightNode create() {
            return IntBitwiseNodeFactory.UnsignedShiftRightNodeGen.create();
        }

        public static UnsignedShiftRightNode getUncached() {
            return IntBitwiseNodeFactory.UnsignedShiftRightNodeGen.getUncached();
        }
    }

    @NodeInfo(shortName = "int.popcount")
    @GenerateUncached
    public static abstract class PopCountNode extends Node {
        public abstract long execute(long value);

        @Specialization
        protected long popcount(long value) {
            return Long.bitCount(value);
        }

        public static PopCountNode create() {
            return IntBitwiseNodeFactory.PopCountNodeGen.create();
        }

        public static PopCountNode getUncached() {
            return IntBitwiseNodeFactory.PopCountNodeGen.getUncached();
        }
    }

    @NodeInfo(shortName = "int.clz")
    @GenerateUncached
    public static abstract class CountLeadingZerosNode extends Node {
        public abstract long execute(long value);

        @Specialization
        protected long clz(long value) {
            return Long.numberOfLeadingZeros(value);
        }

        public static CountLeadingZerosNode create() {
            return IntBitwiseNodeFactory.CountLeadingZerosNodeGen.create();
        }

        public static CountLeadingZerosNode getUncached() {
            return IntBitwiseNodeFactory.CountLeadingZerosNodeGen.getUncached();
        }
    }

    @NodeInfo(shortName = "int.ctz")
    @GenerateUncached
    public static abstract class CountTrailingZerosNode extends Node {
        public abstract long execute(long value);

        @Specialization
        protected long ctz(long value) {
            return Long.numberOfTrailingZeros(value);
        }

        public static CountTrailingZerosNode create() {
            return IntBitwiseNodeFactory.CountTrailingZerosNodeGen.create();
        }

        public static CountTrailingZerosNode getUncached() {
            return IntBitwiseNodeFactory.CountTrailingZerosNodeGen.getUncached();
        }
    }

    private IntBitwiseNode() {
        // Utility class
    }
}
