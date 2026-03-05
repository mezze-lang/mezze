package com.mezze.datastructures.nodes;

import com.mezze.datastructures.seq.*;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Truffle DSL node for getting a long from LongSeqStorage.
 * Provides unboxed access with bounds checking.
 */
@NodeInfo(shortName = "seq.getLong")
@GenerateUncached
public abstract class SeqGetNode extends Node {

    public abstract long execute(LongSeqStorage storage, int index);

    /**
     * Fast path: Direct array access.
     */
    @Specialization
    protected long get(LongSeqStorage storage, int index) {
        return storage.getLong(index);
    }

    public static SeqGetNode create() {
        return SeqGetNodeGen.create();
    }

    public static SeqGetNode getUncached() {
        return SeqGetNodeGen.getUncached();
    }
}
