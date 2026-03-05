package com.mezze.datastructures.nodes;

import com.mezze.datastructures.map.*;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Truffle DSL node for long-keyed map lookup.
 * Provides unboxed access for LongKeyMapStorage.
 */
@NodeInfo(shortName = "map.getLong")
@GenerateUncached
public abstract class MapGetNode extends Node {

    public abstract Object execute(LongKeyMapStorage storage, long key);

    /**
     * Fast path: Long-keyed map lookup.
     */
    @Specialization
    protected Object get(LongKeyMapStorage storage, long key) {
        return storage.get(key);
    }

    public static MapGetNode create() {
        return MapGetNodeGen.create();
    }

    public static MapGetNode getUncached() {
        return MapGetNodeGen.getUncached();
    }
}
