package com.mezze.language.interpreter.nodes.control;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node.Children;
import com.oracle.truffle.api.nodes.NodeInfo;

import com.mezze.language.interpreter.MezzeNull;
import com.mezze.language.interpreter.MezzeValue;
import com.mezze.language.interpreter.nodes.MezzeNode;

/**
 * Sequence node for executing statements and returning the last result.
 * Written in Java to avoid Kotlin intrinsics in the JIT path.
 */
@NodeInfo(shortName = "sequence")
public final class SequenceNode extends MezzeNode {

    @Children
    private final MezzeNode[] nodes;

    public SequenceNode(MezzeNode[] nodes) {
        this.nodes = nodes;
    }

    @Override
    @ExplodeLoop
    public MezzeValue execute(VirtualFrame frame) {
        if (nodes.length == 0) {
            return MezzeNull.INSTANCE;
        }

        MezzeValue result = MezzeNull.INSTANCE;
        for (int i = 0; i < nodes.length; i++) {
            result = nodes[i].execute(frame);
        }

        return result;
    }
}
