package com.mezze.datastructures.nodes;

import com.mezze.datastructures.seq.*;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Truffle DSL node for checking if LongSeqStorage contains a value.
 *
 * Uses SIMD-optimized indexOf for large arrays.
 */
@NodeInfo(shortName = "seq.containsLong")
@GenerateUncached
public abstract class SeqContainsLongNode extends Node {

    public abstract boolean execute(LongSeqStorage storage, long value);

    /**
     * Fast path: Empty sequence - always false.
     */
    @Specialization(guards = "storage.isEmpty()")
    protected boolean containsEmpty(LongSeqStorage storage, long value) {
        return false;
    }

    /**
     * Fast path: Small size - linear search with potential loop unrolling.
     */
    @Specialization(guards = {"!storage.isEmpty()", "storage.size() <= 8"})
    @ExplodeLoop(kind = ExplodeLoop.LoopExplosionKind.FULL_UNROLL_UNTIL_RETURN)
    protected boolean containsSmall(LongSeqStorage storage, long value) {
        return storage.indexOfSmall(value, storage.size()) >= 0;
    }

    /**
     * Fast path: Large size - SIMD search.
     */
    @Specialization(guards = {"!storage.isEmpty()", "storage.size() > 8"})
    protected boolean containsLarge(LongSeqStorage storage, long value) {
        return storage.contains(value);
    }

    public static SeqContainsLongNode create() {
        return SeqContainsLongNodeGen.create();
    }

    public static SeqContainsLongNode getUncached() {
        return SeqContainsLongNodeGen.getUncached();
    }
}
