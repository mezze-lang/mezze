package com.mezze.datastructures.nodes;

import com.mezze.datastructures.set.*;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Truffle DSL node for long set membership test.
 * Provides unboxed access for LongSetStorage.
 */
@NodeInfo(shortName = "set.containsLong")
@GenerateUncached
public abstract class SetContainsNode extends Node {

    public abstract boolean execute(LongSetStorage storage, long element);

    /**
     * Fast path: Long set lookup.
     */
    @Specialization
    protected boolean contains(LongSetStorage storage, long element) {
        return storage.contains(element);
    }

    public static SetContainsNode create() {
        return SetContainsNodeGen.create();
    }

    public static SetContainsNode getUncached() {
        return SetContainsNodeGen.getUncached();
    }
}
