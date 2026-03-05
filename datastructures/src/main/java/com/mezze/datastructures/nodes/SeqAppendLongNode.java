package com.mezze.datastructures.nodes;

import com.mezze.datastructures.seq.*;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Truffle DSL node for appending long to LongSeqStorage.
 * Provides specialized fast paths for different array sizes.
 */
@NodeInfo(shortName = "seq.appendLong")
@GenerateUncached
public abstract class SeqAppendLongNode extends Node {

    public abstract LongSeqStorage execute(LongSeqStorage storage, long value);

    /**
     * Fast path: Append to non-empty storage.
     */
    @Specialization
    protected LongSeqStorage append(LongSeqStorage storage, long value) {
        return storage.appendLong(value);
    }

    public static SeqAppendLongNode create() {
        return SeqAppendLongNodeGen.create();
    }

    public static SeqAppendLongNode getUncached() {
        return SeqAppendLongNodeGen.getUncached();
    }
}
