package com.mezze.datastructures.nodes;

import com.mezze.datastructures.list.*;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Truffle DSL node for getting unboxed long head from MezzeLongCons.
 * Provides zero-boxing access to long list heads.
 */
@NodeInfo(shortName = "list.headLong")
@GenerateUncached
public abstract class ListHeadNode extends Node {

    public abstract long execute(MezzeLongCons list);

    /**
     * Fast path: Long cons - no boxing.
     */
    @Specialization
    protected long head(MezzeLongCons list) {
        return list.headLong();
    }

    public static ListHeadNode create() {
        return ListHeadNodeGen.create();
    }

    public static ListHeadNode getUncached() {
        return ListHeadNodeGen.getUncached();
    }
}
