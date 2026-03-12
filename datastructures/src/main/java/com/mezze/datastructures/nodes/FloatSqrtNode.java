package com.mezze.datastructures.nodes;

import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Optimized square root node.
 *
 * JIT can inline this to a single FSQRT instruction.
 */
@NodeInfo(shortName = "float.sqrt")
public abstract class FloatSqrtNode extends Node {

    public abstract double execute(double n);

    @Specialization
    protected double sqrt(double n) {
        return Math.sqrt(n);
    }

    public static FloatSqrtNode create() {
        return FloatSqrtNodeGen.create();
    }

    public static FloatSqrtNode getUncached() {
        return FloatSqrtNodeGen.getUncached();
    }
}
