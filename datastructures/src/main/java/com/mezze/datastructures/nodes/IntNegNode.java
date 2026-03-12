package com.mezze.datastructures.nodes;

import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Optimized integer negation node.
 *
 * Single NEG instruction in compiled code.
 */
@NodeInfo(shortName = "int.neg")
public abstract class IntNegNode extends Node {

    public abstract long execute(long value);

    @Specialization
    protected long neg(long value) {
        return -value;
    }

    public static IntNegNode create() {
        return IntNegNodeGen.create();
    }

    public static IntNegNode getUncached() {
        return IntNegNodeGen.getUncached();
    }
}
