package com.mezze.datastructures.nodes;

import com.mezze.datastructures.seq.*;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Truffle DSL node for updating a long in LongSeqStorage.
 * Stays unboxed throughout.
 */
@NodeInfo(shortName = "seq.updateLong")
@GenerateUncached
public abstract class SeqUpdateNode extends Node {

    public abstract LongSeqStorage execute(LongSeqStorage storage, int index, long value);

    /**
     * Fast path: Update long - stays unboxed.
     */
    @Specialization
    protected LongSeqStorage update(LongSeqStorage storage, int index, long value) {
        return storage.updateLong(index, value);
    }

    public static SeqUpdateNode create() {
        return SeqUpdateNodeGen.create();
    }

    public static SeqUpdateNode getUncached() {
        return SeqUpdateNodeGen.getUncached();
    }
}
