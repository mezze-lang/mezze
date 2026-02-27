package com.mezze.interpreter.v2.nodes.control;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import com.oracle.truffle.api.profiles.CountingConditionProfile;

/**
 * Short-circuit AND node.
 *
 * Evaluates left first, only evaluates right if left is true.
 */
@NodeInfo(shortName = "and")
public final class AndNode extends MezzeNode {

    @Child private MezzeNode leftNode;
    @Child private MezzeNode rightNode;

    private final CountingConditionProfile profile = CountingConditionProfile.create();

    public AndNode(MezzeNode leftNode, MezzeNode rightNode) {
        this.leftNode = leftNode;
        this.rightNode = rightNode;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        boolean left;
        try {
            left = leftNode.executeBoolean(frame);
        } catch (UnexpectedResultException e) {
            throw new RuntimeException("AND left operand must be boolean, got: " + e.getResult());
        }

        // Short-circuit: if left is false, don't evaluate right
        if (profile.profile(!left)) {
            return false;
        }

        try {
            return rightNode.executeBoolean(frame);
        } catch (UnexpectedResultException e) {
            throw new RuntimeException("AND right operand must be boolean, got: " + e.getResult());
        }
    }

    @Override
    public boolean executeBoolean(VirtualFrame frame) throws UnexpectedResultException {
        boolean left = leftNode.executeBoolean(frame);
        if (profile.profile(!left)) {
            return false;
        }
        return rightNode.executeBoolean(frame);
    }
}
