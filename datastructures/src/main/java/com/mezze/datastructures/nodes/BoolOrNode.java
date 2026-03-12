package com.mezze.datastructures.nodes;

import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Optimized boolean OR node.
 *
 * JIT can inline this to a single OR instruction.
 */
@NodeInfo(shortName = "bool.or")
@GenerateUncached
public abstract class BoolOrNode extends Node {

    public abstract boolean execute(boolean left, boolean right);

    @Specialization
    protected boolean or(boolean left, boolean right) {
        return left || right;
    }

    public static BoolOrNode create() {
        return BoolOrNodeGen.create();
    }

    public static BoolOrNode getUncached() {
        return BoolOrNodeGen.getUncached();
    }
}
