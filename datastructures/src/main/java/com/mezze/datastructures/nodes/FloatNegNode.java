package com.mezze.datastructures.nodes;

import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Optimized floating-point negation node.
 *
 * Single FNEG instruction in compiled code.
 */
@NodeInfo(shortName = "float.neg")
public abstract class FloatNegNode extends Node {

    public abstract double execute(double value);

    @Specialization
    protected double neg(double value) {
        return -value;
    }

    public static FloatNegNode create() {
        return FloatNegNodeGen.create();
    }

    public static FloatNegNode getUncached() {
        return FloatNegNodeGen.getUncached();
    }
}
