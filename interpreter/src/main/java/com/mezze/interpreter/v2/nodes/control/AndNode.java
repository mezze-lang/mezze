package com.mezze.interpreter.v2.nodes.control;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Exclusive;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import com.oracle.truffle.api.profiles.InlinedBranchProfile;
import com.oracle.truffle.api.profiles.InlinedConditionProfile;

/**
 * Short-circuit AND node with INLINED profiling.
 *
 * Evaluates left first, only evaluates right if left is true.
 * Uses InlinedConditionProfile for optimal short-circuit prediction.
 */
@NodeChild(value = "leftNode", type = MezzeNode.class)
@NodeInfo(shortName = "and")
public abstract class AndNode extends MezzeNode {

    @Child private MezzeNode rightNode;

    protected AndNode(MezzeNode rightNode) {
        this.rightNode = rightNode;
    }

    /**
     * Boolean AND with short-circuit evaluation.
     */
    @Specialization
    protected boolean doAnd(VirtualFrame frame, boolean left,
            @Cached @Exclusive InlinedConditionProfile shortCircuitProfile,
            @Cached @Exclusive InlinedBranchProfile errorProfile) {
        // Short-circuit: if left is false, don't evaluate right
        if (shortCircuitProfile.profile(this, !left)) {
            return false;
        }
        try {
            return rightNode.executeBoolean(frame);
        } catch (UnexpectedResultException e) {
            errorProfile.enter(this);
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("AND right operand must be boolean, got: " + e.getResult());
        }
    }

    @Override
    public boolean executeBoolean(VirtualFrame frame) throws UnexpectedResultException {
        Object result = execute(frame);
        if (result instanceof Boolean b) {
            return b;
        }
        throw new UnexpectedResultException(result);
    }

    /**
     * Create an AND node.
     */
    public static AndNode create(MezzeNode leftNode, MezzeNode rightNode) {
        return AndNodeGen.create(rightNode, leftNode);
    }
}
