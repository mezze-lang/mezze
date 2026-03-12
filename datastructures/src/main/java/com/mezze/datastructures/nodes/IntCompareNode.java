package com.mezze.datastructures.nodes;

import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Extremely optimized integer comparison node.
 *
 * Returns comparison result as int: -1, 0, or 1
 * JIT can inline this to CMP + conditional moves.
 */
@NodeInfo(shortName = "int.compare")
public abstract class IntCompareNode extends Node {

    public abstract int execute(long left, long right);

    @Specialization
    protected int compare(long left, long right) {
        return Long.compare(left, right);
    }

    public static IntCompareNode create() {
        return IntCompareNodeGen.create();
    }

    public static IntCompareNode getUncached() {
        return IntCompareNodeGen.getUncached();
    }
}
