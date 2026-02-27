package com.mezze.interpreter.v2.nodes.literal;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Float literal node.
 * Returns primitive double (unboxed) for maximum performance.
 */
@NodeInfo(shortName = "float")
public final class FloatLiteralNode extends MezzeNode {

    private final double value;

    public FloatLiteralNode(double value) {
        this.value = value;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return value;
    }

    @Override
    public double executeDouble(VirtualFrame frame) {
        return value;
    }
}
