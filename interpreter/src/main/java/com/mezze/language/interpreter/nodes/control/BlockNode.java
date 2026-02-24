package com.mezze.language.interpreter.nodes.control;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node.Children;
import com.oracle.truffle.api.nodes.NodeInfo;

import com.mezze.language.interpreter.MezzeNull;
import com.mezze.language.interpreter.MezzeValue;
import com.mezze.language.interpreter.nodes.MezzeNode;

/**
 * Block node for executing multiple statements sequentially.
 * Written in Java to avoid Kotlin intrinsics in the JIT path.
 */
@NodeInfo(shortName = "block")
public final class BlockNode extends MezzeNode {

    @Children
    private final MezzeNode[] statements;

    public BlockNode(MezzeNode[] statements) {
        this.statements = statements;
    }

    @Override
    @ExplodeLoop
    public MezzeValue execute(VirtualFrame frame) {
        MezzeValue result = MezzeNull.INSTANCE;

        for (int i = 0; i < statements.length; i++) {
            result = statements[i].execute(frame);
        }

        return result;
    }
}
