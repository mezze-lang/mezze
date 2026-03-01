package com.mezze.interpreter.v2.nodes.control;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.values.MezzeUnit;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.LoopNode;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.RepeatingNode;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import com.oracle.truffle.api.profiles.InlinedBranchProfile;

/**
 * While loop node with OSR (On-Stack Replacement) support.
 *
 * Uses Truffle's LoopNode to enable JIT compilation mid-loop.
 * This is critical for long-running loops that should be optimized
 * before the entire function becomes hot.
 *
 * Benefits of OSR:
 * 1. Loop can be JIT-compiled while running
 * 2. No need to wait for function to become hot
 * 3. Better performance for loops that iterate many times
 *
 * Example Mezze code this compiles:
 *   while condition do body
 */
@NodeInfo(shortName = "while")
public final class WhileLoopNode extends MezzeNode {

    @Child
    private LoopNode loopNode;

    public WhileLoopNode(MezzeNode conditionNode, MezzeNode bodyNode) {
        this.loopNode = Truffle.getRuntime().createLoopNode(
            new WhileRepeatingNode(conditionNode, bodyNode)
        );
    }

    @Override
    public Object execute(VirtualFrame frame) {
        loopNode.execute(frame);
        return MezzeUnit.INSTANCE;  // While loops return Unit
    }

    /**
     * Create a while loop node.
     */
    public static WhileLoopNode create(MezzeNode conditionNode, MezzeNode bodyNode) {
        return new WhileLoopNode(conditionNode, bodyNode);
    }

    /**
     * Inner repeating node for the LoopNode.
     */
    private static final class WhileRepeatingNode extends MezzeNode implements RepeatingNode {

        @Child
        private MezzeNode conditionNode;

        @Child
        private MezzeNode bodyNode;

        // Error branch profile - tell JIT this path is cold
        private final InlinedBranchProfile notBooleanProfile = InlinedBranchProfile.getUncached();

        WhileRepeatingNode(MezzeNode conditionNode, MezzeNode bodyNode) {
            this.conditionNode = conditionNode;
            this.bodyNode = bodyNode;
        }

        @Override
        public boolean executeRepeating(VirtualFrame frame) {
            // Evaluate condition
            boolean condition;
            try {
                condition = conditionNode.executeBoolean(frame);
            } catch (UnexpectedResultException e) {
                notBooleanProfile.enter(this);
                CompilerDirectives.transferToInterpreterAndInvalidate();
                throw new RuntimeException("While condition must be boolean, got: " + e.getResult());
            }

            if (!condition) {
                return false;  // Exit loop
            }

            // Execute body
            bodyNode.execute(frame);
            return true;  // Continue loop
        }

        @Override
        public Object execute(VirtualFrame frame) {
            throw new UnsupportedOperationException("Use executeRepeating");
        }
    }
}
