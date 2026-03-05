package com.mezze.datastructures.nodes;

import com.mezze.datastructures.list.*;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Truffle DSL node for prepending a long to a MezzeLongCons list.
 * Stays unboxed throughout the operation.
 */
@NodeInfo(shortName = "list.consLong")
@GenerateUncached
public abstract class ListConsNode extends Node {

    public abstract MezzeLongCons execute(long head, MezzeLongCons tail);

    /**
     * Fast path: Long cons to long list - stays unboxed.
     */
    @Specialization
    protected MezzeLongCons cons(long head, MezzeLongCons tail) {
        return new MezzeLongCons(head, tail);
    }

    public static ListConsNode create() {
        return ListConsNodeGen.create();
    }

    public static ListConsNode getUncached() {
        return ListConsNodeGen.getUncached();
    }
}
