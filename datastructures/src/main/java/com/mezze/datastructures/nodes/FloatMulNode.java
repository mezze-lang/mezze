package com.mezze.datastructures.nodes;

import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Extremely optimized float multiplication node.
 *
 * Bypasses record field lookup entirely - takes raw doubles.
 * JIT can inline this to a single FMUL instruction.
 */
@NodeInfo(shortName = "float.mul")
public abstract class FloatMulNode extends Node {

    public abstract double execute(double left, double right);

    @Specialization
    protected double mul(double left, double right) {
        return left * right;
    }

    public static FloatMulNode create() {
        return FloatMulNodeGen.create();
    }

    public static FloatMulNode getUncached() {
        return FloatMulNodeGen.getUncached();
    }
}
