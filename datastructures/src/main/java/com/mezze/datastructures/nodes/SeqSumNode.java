package com.mezze.datastructures.nodes;

import com.mezze.datastructures.seq.*;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Truffle DSL node for summing a LongSeqStorage.
 *
 * Uses SIMD-optimized VectorOps for large arrays and loop unrolling for small arrays.
 */
@NodeInfo(shortName = "seq.sumLong")
@GenerateUncached
public abstract class SeqSumNode extends Node {

    public abstract long execute(LongSeqStorage storage);

    /**
     * Fast path: Small size - loop unrolling.
     */
    @Specialization(guards = "storage.size() <= 8")
    protected long sumSmall(LongSeqStorage storage) {
        return storage.sumSmall(storage.size());
    }

    /**
     * Fast path: Larger size - SIMD.
     */
    @Specialization(guards = "storage.size() > 8")
    protected long sumLarge(LongSeqStorage storage) {
        return storage.sum();
    }

    public static SeqSumNode create() {
        return SeqSumNodeGen.create();
    }

    public static SeqSumNode getUncached() {
        return SeqSumNodeGen.getUncached();
    }
}
