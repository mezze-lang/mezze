package com.mezze.interpreter.v2.nodes.control;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import com.oracle.truffle.api.profiles.CountingConditionProfile;

/**
 * Short-circuit OR node.
 *
 * Evaluates left first, only evaluates right if left is false.
 */
@NodeInfo(shortName = "or")
public final class OrNode extends MezzeNode {

    @Child private MezzeNode leftNode;
    @Child private MezzeNode rightNode;

    private final CountingConditionProfile profile = CountingConditionProfile.create();

    public OrNode(MezzeNode leftNode, MezzeNode rightNode) {
        this.leftNode = leftNode;
        this.rightNode = rightNode;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        boolean left;
        try {
            left = leftNode.executeBoolean(frame);
        } catch (UnexpectedResultException e) {
            throw new RuntimeException("OR left operand must be boolean, got: " + e.getResult());
        }

        // Short-circuit: if left is true, don't evaluate right
        if (profile.profile(left)) {
            return true;
        }

        try {
            return rightNode.executeBoolean(frame);
        } catch (UnexpectedResultException e) {
            throw new RuntimeException("OR right operand must be boolean, got: " + e.getResult());
        }
    }

    @Override
    public boolean executeBoolean(VirtualFrame frame) throws UnexpectedResultException {
        boolean left = leftNode.executeBoolean(frame);
        if (profile.profile(left)) {
            return true;
        }
        return rightNode.executeBoolean(frame);
    }
}
