package com.mezze.datastructures.nodes;

import com.mezze.datastructures.list.*;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Truffle DSL node for getting unboxed double head from MezzeDoubleCons.
 * Provides zero-boxing access to double list heads.
 */
@NodeInfo(shortName = "list.headDouble")
@GenerateUncached
public abstract class ListHeadDoubleNode extends Node {

    public abstract double execute(MezzeDoubleCons list);

    /**
     * Fast path: Double cons - no boxing.
     */
    @Specialization
    protected double head(MezzeDoubleCons list) {
        return list.headDouble();
    }

    public static ListHeadDoubleNode create() {
        return ListHeadDoubleNodeGen.create();
    }

    public static ListHeadDoubleNode getUncached() {
        return ListHeadDoubleNodeGen.getUncached();
    }
}
