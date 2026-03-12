package com.mezze.datastructures.nodes;

import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Optimized floating-point absolute value node.
 *
 * Math.abs(double) is intrinsified to single AND instruction
 * (clears sign bit).
 */
@NodeInfo(shortName = "float.abs")
public abstract class FloatAbsNode extends Node {

    public abstract double execute(double value);

    @Specialization
    protected double abs(double value) {
        return Math.abs(value);
    }

    public static FloatAbsNode create() {
        return FloatAbsNodeGen.create();
    }

    public static FloatAbsNode getUncached() {
        return FloatAbsNodeGen.getUncached();
    }
}
