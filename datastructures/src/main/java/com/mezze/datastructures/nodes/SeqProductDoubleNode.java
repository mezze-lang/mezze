package com.mezze.datastructures.nodes;

import com.mezze.datastructures.seq.*;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Truffle DSL node for product operations on DoubleSeqStorage.
 *
 * Uses SIMD-optimized VectorOps for large arrays.
 */
@NodeInfo(shortName = "seq.productDouble")
@GenerateUncached
public abstract class SeqProductDoubleNode extends Node {

    public abstract double execute(DoubleSeqStorage storage);

    /**
     * Fast path: Small size - scalar loop.
     */
    @Specialization(guards = "storage.size() <= 8")
    protected double productSmall(DoubleSeqStorage storage) {
        double[] data = storage.getArray();
        int size = storage.size();
        if (size == 0) return 1.0;
        double result = 1.0;
        for (int i = 0; i < size; i++) {
            result *= data[i];
        }
        return result;
    }

    /**
     * Fast path: Large size - SIMD product.
     */
    @Specialization(guards = "storage.size() > 8")
    protected double productLarge(DoubleSeqStorage storage) {
        return storage.product();
    }

    public static SeqProductDoubleNode create() {
        return SeqProductDoubleNodeGen.create();
    }

    public static SeqProductDoubleNode getUncached() {
        return SeqProductDoubleNodeGen.getUncached();
    }
}
