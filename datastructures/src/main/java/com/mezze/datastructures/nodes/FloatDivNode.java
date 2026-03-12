package com.mezze.datastructures.nodes;

import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Optimized floating-point division node.
 *
 * Single FDIV instruction in compiled code.
 */
@NodeInfo(shortName = "float.div")
public abstract class FloatDivNode extends Node {

    public abstract double execute(double left, double right);

    @Specialization
    protected double div(double left, double right) {
        return left / right;
    }

    public static FloatDivNode create() {
        return FloatDivNodeGen.create();
    }

    public static FloatDivNode getUncached() {
        return FloatDivNodeGen.getUncached();
    }
}
