package com.mezze.datastructures.nodes;

import com.mezze.datastructures.seq.*;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Truffle DSL node for max operations on LongSeqStorage.
 *
 * Uses SIMD-optimized VectorOps for large arrays.
 */
@NodeInfo(shortName = "seq.maxLong")
@GenerateUncached
public abstract class SeqMaxLongNode extends Node {

    public abstract long execute(LongSeqStorage storage);

    /**
     * Fast path: Small size - scalar loop.
     */
    @Specialization(guards = "storage.size() <= 8")
    protected long maxSmall(LongSeqStorage storage) {
        long[] data = storage.getArray();
        int size = storage.size();
        if (size == 0) throw new IllegalArgumentException("Empty sequence");
        long result = data[0];
        for (int i = 1; i < size; i++) {
            if (data[i] > result) result = data[i];
        }
        return result;
    }

    /**
     * Fast path: Large size - SIMD max.
     */
    @Specialization(guards = "storage.size() > 8")
    protected long maxLarge(LongSeqStorage storage) {
        return storage.max();
    }

    public static SeqMaxLongNode create() {
        return SeqMaxLongNodeGen.create();
    }

    public static SeqMaxLongNode getUncached() {
        return SeqMaxLongNodeGen.getUncached();
    }
}
