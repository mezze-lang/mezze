package com.mezze.interpreter.v2.nodes.literal;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * String literal node.
 */
@NodeInfo(shortName = "string")
public final class StringLiteralNode extends MezzeNode {

    private final String value;

    public StringLiteralNode(String value) {
        this.value = value;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return value;
    }
}
