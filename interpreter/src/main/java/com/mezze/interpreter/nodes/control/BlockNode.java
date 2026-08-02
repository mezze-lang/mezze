package com.mezze.interpreter.nodes.control;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.values.MezzeUnit;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Block node - executes a sequence of statements.
 *
 * Returns the value of the last statement, or Unit if empty.
 *
 * JIT Optimizations:
 * - @ExplodeLoop unrolls the loop at compile time
 * - Hot path (normal execution) has zero overhead
 *
 * <p>Historical note: earlier versions detected a MezzeSuspended returned
 * by an intermediate statement and composed the remaining statements onto
 * it (ComposedBlockThunk). That path was dead code — empirical trace
 * across the full :interpreter:test suite showed zero hits — because the
 * lazy-suspend model is being replaced by Option B (#341): the
 * typechecker refuses to let a Suspend&lt;T, r&gt; value flow into a plain-
 * consuming position, so intermediate-stmt leaks can't happen. Deleted
 * in Phase 3 of the effect-runtime cleanup.
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
        for (int i = 0; i < statements.length - 1; i++) {
            statements[i].execute(frame);
        }
        return statements[statements.length - 1].execute(frame);
    }
}
