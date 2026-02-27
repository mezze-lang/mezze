package com.mezze.interpreter.v2.nodes.literal;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Boolean literal node.
 * Returns primitive boolean (unboxed) for maximum performance.
 */
@NodeInfo(shortName = "bool")
public final class BoolLiteralNode extends MezzeNode {

    private final boolean value;

    public BoolLiteralNode(boolean value) {
        this.value = value;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return value;
    }

    @Override
    public boolean executeBoolean(VirtualFrame frame) {
        return value;
    }
}
