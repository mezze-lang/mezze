package com.mezze.datastructures.nodes;

import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Optimized boolean XOR node.
 *
 * JIT can inline this to a single XOR instruction.
 */
@NodeInfo(shortName = "bool.xor")
@GenerateUncached
public abstract class BoolXorNode extends Node {

    public abstract boolean execute(boolean left, boolean right);

    @Specialization
    protected boolean xor(boolean left, boolean right) {
        return left ^ right;
    }

    public static BoolXorNode create() {
        return BoolXorNodeGen.create();
    }

    public static BoolXorNode getUncached() {
        return BoolXorNodeGen.getUncached();
    }
}
