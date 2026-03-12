package com.mezze.datastructures.nodes;

import com.mezze.datastructures.seq.*;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Truffle DSL node for checking if DoubleSeqStorage contains a value.
 */
@NodeInfo(shortName = "seq.containsDouble")
@GenerateUncached
public abstract class SeqContainsDoubleNode extends Node {

    public abstract boolean execute(DoubleSeqStorage storage, double value);

    /**
     * Fast path: Empty sequence - always false.
     */
    @Specialization(guards = "storage.isEmpty()")
    protected boolean containsEmpty(DoubleSeqStorage storage, double value) {
        return false;
    }

    /**
     * Fast path: Small size - linear search with potential loop unrolling.
     */
    @Specialization(guards = {"!storage.isEmpty()", "storage.size() <= 8"})
    @ExplodeLoop(kind = ExplodeLoop.LoopExplosionKind.FULL_UNROLL_UNTIL_RETURN)
    protected boolean containsSmall(DoubleSeqStorage storage, double value) {
        return storage.indexOfSmall(value, storage.size()) >= 0;
    }

    /**
     * Fast path: Large size - linear search.
     */
    @Specialization(guards = {"!storage.isEmpty()", "storage.size() > 8"})
    protected boolean containsLarge(DoubleSeqStorage storage, double value) {
        return storage.contains(value);
    }

    public static SeqContainsDoubleNode create() {
        return SeqContainsDoubleNodeGen.create();
    }

    public static SeqContainsDoubleNode getUncached() {
        return SeqContainsDoubleNodeGen.getUncached();
    }
}
