package com.mezze.datastructures.nodes;

import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Optimized integer modulo node.
 *
 * Single IDIV instruction in compiled code.
 */
@NodeInfo(shortName = "int.mod")
public abstract class IntModNode extends Node {

    public abstract long execute(long left, long right);

    @Specialization
    protected long mod(long left, long right) {
        return left % right;
    }

    public static IntModNode create() {
        return IntModNodeGen.create();
    }

    public static IntModNode getUncached() {
        return IntModNodeGen.getUncached();
    }
}
