package com.mezze.datastructures.nodes;

import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Optimized floating-point subtraction node.
 *
 * Single FSUB instruction in compiled code.
 */
@NodeInfo(shortName = "float.sub")
public abstract class FloatSubNode extends Node {

    public abstract double execute(double left, double right);

    @Specialization
    protected double sub(double left, double right) {
        return left - right;
    }

    public static FloatSubNode create() {
        return FloatSubNodeGen.create();
    }

    public static FloatSubNode getUncached() {
        return FloatSubNodeGen.getUncached();
    }
}
