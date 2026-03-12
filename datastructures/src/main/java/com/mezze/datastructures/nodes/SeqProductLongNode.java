package com.mezze.datastructures.nodes;

import com.mezze.datastructures.seq.*;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Truffle DSL node for product operations on LongSeqStorage.
 *
 * Uses SIMD-optimized VectorOps for large arrays.
 */
@NodeInfo(shortName = "seq.productLong")
@GenerateUncached
public abstract class SeqProductLongNode extends Node {

    public abstract long execute(LongSeqStorage storage);

    /**
     * Fast path: Small size - scalar loop.
     */
    @Specialization(guards = "storage.size() <= 8")
    protected long productSmall(LongSeqStorage storage) {
        long[] data = storage.getArray();
        int size = storage.size();
        if (size == 0) return 1L;
        long result = 1L;
        for (int i = 0; i < size; i++) {
            result *= data[i];
        }
        return result;
    }

    /**
     * Fast path: Large size - SIMD product.
     */
    @Specialization(guards = "storage.size() > 8")
    protected long productLarge(LongSeqStorage storage) {
        return storage.product();
    }

    public static SeqProductLongNode create() {
        return SeqProductLongNodeGen.create();
    }

    public static SeqProductLongNode getUncached() {
        return SeqProductLongNodeGen.getUncached();
    }
}
