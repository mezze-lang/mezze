package com.mezze.datastructures.nodes;

import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Optimized integer subtraction node.
 *
 * Uses Math.subtractExact for overflow detection in fast path,
 * falls back to wrapping subtraction after first overflow.
 */
@NodeInfo(shortName = "int.sub")
public abstract class IntSubNode extends Node {

    public abstract long execute(long left, long right);

    @Specialization(rewriteOn = ArithmeticException.class)
    protected long subExact(long left, long right) {
        return Math.subtractExact(left, right);
    }

    @Specialization(replaces = "subExact")
    protected long subOverflow(long left, long right) {
        return left - right;
    }

    public static IntSubNode create() {
        return IntSubNodeGen.create();
    }

    public static IntSubNode getUncached() {
        return IntSubNodeGen.getUncached();
    }
}
