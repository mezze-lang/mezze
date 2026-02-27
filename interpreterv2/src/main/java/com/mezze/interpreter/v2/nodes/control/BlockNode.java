package com.mezze.interpreter.v2.nodes.control;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.values.MezzeUnit;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Block node - executes a sequence of statements.
 *
 * Returns the value of the last statement, or Unit if empty.
 */
@NodeInfo(shortName = "block")
public final class BlockNode extends MezzeNode {

    @Children private final MezzeNode[] statements;

    public BlockNode(MezzeNode[] statements) {
        this.statements = statements;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        if (statements.length == 0) {
            return MezzeUnit.INSTANCE;
        }

        // Execute all but the last statement, ignoring their return values
        for (int i = 0; i < statements.length - 1; i++) {
            statements[i].execute(frame);
        }

        // Return the value of the last statement
        return statements[statements.length - 1].execute(frame);
    }
}
