package com.mezze.datastructures.nodes;

import com.mezze.datastructures.seq.*;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Truffle DSL node for summing a DoubleSeqStorage.
 *
 * Uses SIMD-optimized VectorOps for large arrays and loop unrolling for small arrays.
 */
@NodeInfo(shortName = "seq.sumDouble")
@GenerateUncached
public abstract class SeqSumDoubleNode extends Node {

    public abstract double execute(DoubleSeqStorage storage);

    /**
     * Fast path: Small size - loop unrolling.
     */
    @Specialization(guards = "storage.size() <= 8")
    protected double sumSmall(DoubleSeqStorage storage) {
        return storage.sumSmall(storage.size());
    }

    /**
     * Fast path: Larger size - SIMD.
     */
    @Specialization(guards = "storage.size() > 8")
    protected double sumLarge(DoubleSeqStorage storage) {
        return storage.sum();
    }

    public static SeqSumDoubleNode create() {
        return SeqSumDoubleNodeGen.create();
    }

    public static SeqSumDoubleNode getUncached() {
        return SeqSumDoubleNodeGen.getUncached();
    }
}
