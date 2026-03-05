package com.mezze.datastructures.nodes;

import com.mezze.datastructures.seq.*;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Truffle DSL node for indexOf in LongSeqStorage.
 *
 * Uses SIMD-optimized VectorOps for large arrays and loop unrolling for small arrays.
 */
@NodeInfo(shortName = "seq.indexOfLong")
@GenerateUncached
public abstract class SeqIndexOfNode extends Node {

    public abstract int execute(LongSeqStorage storage, long value);

    /**
     * Fast path: Small size - unrolled loop.
     */
    @Specialization(guards = "storage.size() <= 8")
    protected int indexOfSmall(LongSeqStorage storage, long value) {
        return storage.indexOfSmall(value, storage.size());
    }

    /**
     * Fast path: Large size - SIMD search.
     */
    @Specialization(guards = "storage.size() > 8")
    protected int indexOfLarge(LongSeqStorage storage, long value) {
        return storage.indexOf(value);
    }

    public static SeqIndexOfNode create() {
        return SeqIndexOfNodeGen.create();
    }

    public static SeqIndexOfNode getUncached() {
        return SeqIndexOfNodeGen.getUncached();
    }
}
