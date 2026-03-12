package com.mezze.datastructures.nodes;

import com.mezze.datastructures.seq.*;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Truffle DSL node for min operations on DoubleSeqStorage.
 *
 * Uses SIMD-optimized VectorOps for large arrays.
 */
@NodeInfo(shortName = "seq.minDouble")
@GenerateUncached
public abstract class SeqMinDoubleNode extends Node {

    public abstract double execute(DoubleSeqStorage storage);

    /**
     * Fast path: Small size - scalar loop.
     */
    @Specialization(guards = "storage.size() <= 8")
    protected double minSmall(DoubleSeqStorage storage) {
        double[] data = storage.getArray();
        int size = storage.size();
        if (size == 0) throw new IllegalArgumentException("Empty sequence");
        double result = data[0];
        for (int i = 1; i < size; i++) {
            if (data[i] < result) result = data[i];
        }
        return result;
    }

    /**
     * Fast path: Large size - SIMD min.
     */
    @Specialization(guards = "storage.size() > 8")
    protected double minLarge(DoubleSeqStorage storage) {
        return storage.min();
    }

    public static SeqMinDoubleNode create() {
        return SeqMinDoubleNodeGen.create();
    }

    public static SeqMinDoubleNode getUncached() {
        return SeqMinDoubleNodeGen.getUncached();
    }
}
