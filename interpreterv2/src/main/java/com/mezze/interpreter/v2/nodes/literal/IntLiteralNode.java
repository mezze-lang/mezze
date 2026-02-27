package com.mezze.interpreter.v2.nodes.literal;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.UnexpectedResultException;

/**
 * Integer literal node.
 * Returns primitive long (unboxed) for maximum performance.
 */
@NodeInfo(shortName = "int")
public final class IntLiteralNode extends MezzeNode {

    private final long value;

    public IntLiteralNode(long value) {
        this.value = value;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return value;
    }

    @Override
    public long executeLong(VirtualFrame frame) {
        return value;
    }

    @Override
    public double executeDouble(VirtualFrame frame) {
        return (double) value;
    }
}
