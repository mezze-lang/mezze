package com.mezze.datastructures.nodes;

import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Optimized integer absolute value node.
 *
 * Math.abs is intrinsified to branchless code.
 */
@NodeInfo(shortName = "int.abs")
public abstract class IntAbsNode extends Node {

    public abstract long execute(long value);

    @Specialization
    protected long abs(long value) {
        return Math.abs(value);
    }

    public static IntAbsNode create() {
        return IntAbsNodeGen.create();
    }

    public static IntAbsNode getUncached() {
        return IntAbsNodeGen.getUncached();
    }
}
