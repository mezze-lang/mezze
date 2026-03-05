package com.mezze.datastructures.nodes;

import com.mezze.datastructures.list.*;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Truffle DSL node for prepending a double to a MezzeDoubleCons list.
 * Stays unboxed throughout the operation.
 */
@NodeInfo(shortName = "list.consDouble")
@GenerateUncached
public abstract class ListConsDoubleNode extends Node {

    public abstract MezzeDoubleCons execute(double head, MezzeDoubleCons tail);

    /**
     * Fast path: Double cons to double list - stays unboxed.
     */
    @Specialization
    protected MezzeDoubleCons cons(double head, MezzeDoubleCons tail) {
        return new MezzeDoubleCons(head, tail);
    }

    public static ListConsDoubleNode create() {
        return ListConsDoubleNodeGen.create();
    }

    public static ListConsDoubleNode getUncached() {
        return ListConsDoubleNodeGen.getUncached();
    }
}
