package com.mezze.datastructures.nodes;

import com.mezze.datastructures.seq.*;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Truffle DSL node for appending double to DoubleSeqStorage.
 * Provides specialized fast paths for different array sizes.
 */
@NodeInfo(shortName = "seq.appendDouble")
@GenerateUncached
public abstract class SeqAppendDoubleNode extends Node {

    public abstract DoubleSeqStorage execute(DoubleSeqStorage storage, double value);

    /**
     * Fast path: Append to storage.
     */
    @Specialization
    protected DoubleSeqStorage append(DoubleSeqStorage storage, double value) {
        return storage.appendDouble(value);
    }

    public static SeqAppendDoubleNode create() {
        return SeqAppendDoubleNodeGen.create();
    }

    public static SeqAppendDoubleNode getUncached() {
        return SeqAppendDoubleNodeGen.getUncached();
    }
}
