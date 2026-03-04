package com.mezze.interpreter.v2.nodes.control;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.nodes.continuation.MezzeContinuationSuspended;
import com.mezze.interpreter.v2.values.MezzeUnit;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.profiles.InlinedBranchProfile;

/**
 * Block node - executes a sequence of statements.
 *
 * Returns the value of the last statement, or Unit if empty.
 * If any statement returns MezzeContinuationSuspended, returns immediately
 * (for early return/exception effects).
 *
 * JIT Optimizations:
 * - @ExplodeLoop unrolls the loop at compile time
 * - InlinedBranchProfile marks early return as cold path
 * - Hot path (normal execution) has zero overhead
 */
@NodeInfo(shortName = "block")
public final class BlockNode extends MezzeNode {

    @Children private final MezzeNode[] statements;

    // Branch profile for early return - rarely taken, JIT optimizes for normal path
    private final InlinedBranchProfile earlyReturnProfile = InlinedBranchProfile.getUncached();

    public BlockNode(MezzeNode[] statements) {
        this.statements = statements;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        if (statements.length == 0) {
            return MezzeUnit.INSTANCE;
        }

        // Execute all but the last statement
        // Check for MezzeContinuationSuspended to support early return/exception effects
        for (int i = 0; i < statements.length - 1; i++) {
            Object result = statements[i].execute(frame);
            // Early return on suspended continuation (exception/return effects)
            // Branch profile marks this as cold path - JIT assumes rarely taken
            if (result instanceof MezzeContinuationSuspended) {
                earlyReturnProfile.enter(this);
                return result;
            }
        }

        // Return the value of the last statement
        return statements[statements.length - 1].execute(frame);
    }
}
