package com.mezze.language.interpreter.nodes.variable;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node.Child;
import com.oracle.truffle.api.nodes.NodeInfo;

import com.mezze.language.interpreter.MezzeValue;
import com.mezze.language.interpreter.nodes.MezzeNode;

/**
 * Node for writing variables to the frame.
 * Written in Java to avoid Kotlin intrinsics in the JIT path.
 */
@NodeInfo(shortName = "write-var")
public final class WriteVariableNode extends MezzeNode {

    private final int slot;

    @Child
    private MezzeNode valueNode;

    public WriteVariableNode(int slot, MezzeNode valueNode) {
        this.slot = slot;
        this.valueNode = valueNode;
    }

    @Override
    public MezzeValue execute(VirtualFrame frame) {
        MezzeValue value = valueNode.execute(frame);
        frame.setObject(slot, value);
        return value;
    }
}
