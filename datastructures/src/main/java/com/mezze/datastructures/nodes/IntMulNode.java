package com.mezze.datastructures.nodes;

import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Extremely optimized integer multiplication node.
 *
 * Bypasses record field lookup entirely - takes raw longs.
 * JIT can inline this to a single MUL instruction.
 */
@NodeInfo(shortName = "int.mul")
public abstract class IntMulNode extends Node {

    public abstract long execute(long left, long right);

    @Specialization(rewriteOn = ArithmeticException.class)
    protected long mulExact(long left, long right) {
        return Math.multiplyExact(left, right);
    }

    @Specialization(replaces = "mulExact")
    protected long mulOverflow(long left, long right) {
        // Fallback that doesn't throw - just wrap
        return left * right;
    }

    public static IntMulNode create() {
        return IntMulNodeGen.create();
    }

    public static IntMulNode getUncached() {
        return IntMulNodeGen.getUncached();
    }
}
