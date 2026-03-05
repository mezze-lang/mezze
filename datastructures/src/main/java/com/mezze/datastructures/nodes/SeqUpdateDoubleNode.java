package com.mezze.datastructures.nodes;

import com.mezze.datastructures.seq.*;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Truffle DSL node for updating a double in DoubleSeqStorage.
 * Stays unboxed throughout.
 */
@NodeInfo(shortName = "seq.updateDouble")
@GenerateUncached
public abstract class SeqUpdateDoubleNode extends Node {

    public abstract DoubleSeqStorage execute(DoubleSeqStorage storage, int index, double value);

    /**
     * Fast path: Update double - stays unboxed.
     */
    @Specialization
    protected DoubleSeqStorage update(DoubleSeqStorage storage, int index, double value) {
        return storage.updateDouble(index, value);
    }

    public static SeqUpdateDoubleNode create() {
        return SeqUpdateDoubleNodeGen.create();
    }

    public static SeqUpdateDoubleNode getUncached() {
        return SeqUpdateDoubleNodeGen.getUncached();
    }
}
