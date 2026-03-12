package com.mezze.datastructures.nodes;

import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Optimized boolean NOT node.
 *
 * JIT can inline this to a single XOR instruction.
 */
@NodeInfo(shortName = "bool.not")
@GenerateUncached
public abstract class BoolNotNode extends Node {

    public abstract boolean execute(boolean value);

    @Specialization
    protected boolean not(boolean value) {
        return !value;
    }

    public static BoolNotNode create() {
        return BoolNotNodeGen.create();
    }

    public static BoolNotNode getUncached() {
        return BoolNotNodeGen.getUncached();
    }
}
