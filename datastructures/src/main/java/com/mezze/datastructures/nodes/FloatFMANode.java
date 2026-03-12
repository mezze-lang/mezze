package com.mezze.datastructures.nodes;

import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Fused Multiply-Add node for floats.
 *
 * Computes a * b + c in a single operation with better precision
 * and performance (single rounding, uses FMA instruction).
 *
 * Critical for ML, graphics, and numerical computations.
 */
@NodeInfo(shortName = "float.fma")
public abstract class FloatFMANode extends Node {

    public abstract double execute(double a, double b, double c);

    @Specialization
    protected double fma(double a, double b, double c) {
        return Math.fma(a, b, c);
    }

    public static FloatFMANode create() {
        return FloatFMANodeGen.create();
    }

    public static FloatFMANode getUncached() {
        return FloatFMANodeGen.getUncached();
    }
}
