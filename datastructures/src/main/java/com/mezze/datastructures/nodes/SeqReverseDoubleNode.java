package com.mezze.datastructures.nodes;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.ExplodeLoop;

/**
 * Optimized sequence reverse for doubles.
 *
 * Uses loop unrolling for small sequences.
 */
@NodeInfo(shortName = "seq.reverseDouble")
public abstract class SeqReverseDoubleNode extends Node {

    public abstract double[] execute(double[] data);

    @Specialization(guards = "data.length == 0")
    protected double[] reverseEmpty(double[] data) {
        return data;
    }

    @Specialization(guards = "data.length == 1")
    protected double[] reverseSingle(double[] data) {
        return data;
    }

    @Specialization(guards = "data.length == 2")
    protected double[] reverseTwo(double[] data) {
        return new double[] { data[1], data[0] };
    }

    @Specialization(guards = {"data.length > 2", "data.length <= 8"})
    @ExplodeLoop
    protected double[] reverseSmall(double[] data) {
        int len = data.length;
        double[] result = new double[len];
        for (int i = 0; i < len; i++) {
            result[i] = data[len - 1 - i];
        }
        return result;
    }

    @Specialization(guards = "data.length > 8", replaces = {"reverseSmall"})
    @TruffleBoundary
    protected double[] reverseLarge(double[] data) {
        int len = data.length;
        double[] result = new double[len];
        for (int i = 0; i < len; i++) {
            result[i] = data[len - 1 - i];
        }
        return result;
    }

    public static SeqReverseDoubleNode create() {
        return SeqReverseDoubleNodeGen.create();
    }

    public static SeqReverseDoubleNode getUncached() {
        return SeqReverseDoubleNodeGen.getUncached();
    }
}
