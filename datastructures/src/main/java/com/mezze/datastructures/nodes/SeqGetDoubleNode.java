package com.mezze.datastructures.nodes;

import com.mezze.datastructures.seq.*;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Truffle DSL node for getting a double from DoubleSeqStorage.
 * Provides unboxed access with bounds checking.
 */
@NodeInfo(shortName = "seq.getDouble")
@GenerateUncached
public abstract class SeqGetDoubleNode extends Node {

    public abstract double execute(DoubleSeqStorage storage, int index);

    /**
     * Fast path: Direct array access.
     */
    @Specialization
    protected double get(DoubleSeqStorage storage, int index) {
        return storage.getDouble(index);
    }

    public static SeqGetDoubleNode create() {
        return SeqGetDoubleNodeGen.create();
    }

    public static SeqGetDoubleNode getUncached() {
        return SeqGetDoubleNodeGen.getUncached();
    }
}
