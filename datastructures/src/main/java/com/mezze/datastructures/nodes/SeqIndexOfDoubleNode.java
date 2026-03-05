package com.mezze.datastructures.nodes;

import com.mezze.datastructures.seq.*;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Truffle DSL node for indexOf in DoubleSeqStorage.
 *
 * Uses loop unrolling for small arrays.
 */
@NodeInfo(shortName = "seq.indexOfDouble")
@GenerateUncached
public abstract class SeqIndexOfDoubleNode extends Node {

    public abstract int execute(DoubleSeqStorage storage, double value);

    /**
     * Fast path: Small size - unrolled loop.
     */
    @Specialization(guards = "storage.size() <= 8")
    protected int indexOfSmall(DoubleSeqStorage storage, double value) {
        return storage.indexOfSmall(value, storage.size());
    }

    /**
     * Fast path: Large size - linear search.
     */
    @Specialization(guards = "storage.size() > 8")
    protected int indexOfLarge(DoubleSeqStorage storage, double value) {
        return storage.indexOf(value);
    }

    public static SeqIndexOfDoubleNode create() {
        return SeqIndexOfDoubleNodeGen.create();
    }

    public static SeqIndexOfDoubleNode getUncached() {
        return SeqIndexOfDoubleNodeGen.getUncached();
    }
}
