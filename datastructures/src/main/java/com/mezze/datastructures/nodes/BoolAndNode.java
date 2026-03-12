package com.mezze.datastructures.nodes;

import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Optimized boolean AND node.
 *
 * JIT can inline this to a single AND instruction.
 */
@NodeInfo(shortName = "bool.and")
@GenerateUncached
public abstract class BoolAndNode extends Node {

    public abstract boolean execute(boolean left, boolean right);

    @Specialization
    protected boolean and(boolean left, boolean right) {
        return left && right;
    }

    public static BoolAndNode create() {
        return BoolAndNodeGen.create();
    }

    public static BoolAndNode getUncached() {
        return BoolAndNodeGen.getUncached();
    }
}
