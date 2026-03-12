package com.mezze.datastructures.nodes;

import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.CompilerDirectives;

/**
 * Extremely optimized integer addition node.
 *
 * Bypasses record field lookup entirely - takes raw longs.
 * JIT can inline this to a single ADD instruction.
 */
@NodeInfo(shortName = "int.add")
public abstract class IntAddNode extends Node {

    public abstract long execute(long left, long right);

    @Specialization(rewriteOn = ArithmeticException.class)
    protected long addExact(long left, long right) {
        return Math.addExact(left, right);
    }

    @Specialization(replaces = "addExact")
    protected long addOverflow(long left, long right) {
        // Fallback that doesn't throw - just wrap
        return left + right;
    }

    public static IntAddNode create() {
        return IntAddNodeGen.create();
    }

    public static IntAddNode getUncached() {
        return IntAddNodeGen.getUncached();
    }
}
