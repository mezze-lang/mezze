package com.mezze.datastructures.nodes;

import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Extremely optimized float addition node.
 *
 * Bypasses record field lookup entirely - takes raw doubles.
 * JIT can inline this to a single FADD instruction.
 */
@NodeInfo(shortName = "float.add")
public abstract class FloatAddNode extends Node {

    public abstract double execute(double left, double right);

    @Specialization
    protected double add(double left, double right) {
        return left + right;
    }

    public static FloatAddNode create() {
        return FloatAddNodeGen.create();
    }

    public static FloatAddNode getUncached() {
        return FloatAddNodeGen.getUncached();
    }
}
