package com.mezze.datastructures.nodes;

import com.mezze.datastructures.seq.*;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Truffle DSL node for min operations on LongSeqStorage.
 *
 * Uses SIMD-optimized VectorOps for large arrays.
 */
@NodeInfo(shortName = "seq.minLong")
@GenerateUncached
public abstract class SeqMinLongNode extends Node {

    public abstract long execute(LongSeqStorage storage);

    /**
     * Fast path: Small size - scalar loop.
     */
    @Specialization(guards = "storage.size() <= 8")
    protected long minSmall(LongSeqStorage storage) {
        long[] data = storage.getArray();
        int size = storage.size();
        if (size == 0) throw new IllegalArgumentException("Empty sequence");
        long result = data[0];
        for (int i = 1; i < size; i++) {
            if (data[i] < result) result = data[i];
        }
        return result;
    }

    /**
     * Fast path: Large size - SIMD min.
     */
    @Specialization(guards = "storage.size() > 8")
    protected long minLarge(LongSeqStorage storage) {
        return storage.min();
    }

    public static SeqMinLongNode create() {
        return SeqMinLongNodeGen.create();
    }

    public static SeqMinLongNode getUncached() {
        return SeqMinLongNodeGen.getUncached();
    }
}
