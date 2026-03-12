package com.mezze.datastructures.nodes;

import com.mezze.datastructures.seq.*;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Truffle DSL node for max operations on DoubleSeqStorage.
 *
 * Uses SIMD-optimized VectorOps for large arrays.
 */
@NodeInfo(shortName = "seq.maxDouble")
@GenerateUncached
public abstract class SeqMaxDoubleNode extends Node {

    public abstract double execute(DoubleSeqStorage storage);

    /**
     * Fast path: Small size - scalar loop.
     */
    @Specialization(guards = "storage.size() <= 8")
    protected double maxSmall(DoubleSeqStorage storage) {
        double[] data = storage.getArray();
        int size = storage.size();
        if (size == 0) throw new IllegalArgumentException("Empty sequence");
        double result = data[0];
        for (int i = 1; i < size; i++) {
            if (data[i] > result) result = data[i];
        }
        return result;
    }

    /**
     * Fast path: Large size - SIMD max.
     */
    @Specialization(guards = "storage.size() > 8")
    protected double maxLarge(DoubleSeqStorage storage) {
        return storage.max();
    }

    public static SeqMaxDoubleNode create() {
        return SeqMaxDoubleNodeGen.create();
    }

    public static SeqMaxDoubleNode getUncached() {
        return SeqMaxDoubleNodeGen.getUncached();
    }
}
