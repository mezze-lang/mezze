package com.mezze.interpreter.v2.nodes.control;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import com.oracle.truffle.api.profiles.CountingConditionProfile;

/**
 * If-then-else node with branch profiling.
 *
 * Uses CountingConditionProfile to optimize based on which branch
 * is taken more frequently.
 */
@NodeInfo(shortName = "if")
public final class IfNode extends MezzeNode {

    @Child private MezzeNode conditionNode;
    @Child private MezzeNode thenNode;
    @Child private MezzeNode elseNode;

    private final CountingConditionProfile profile = CountingConditionProfile.create();

    public IfNode(MezzeNode conditionNode, MezzeNode thenNode, MezzeNode elseNode) {
        this.conditionNode = conditionNode;
        this.thenNode = thenNode;
        this.elseNode = elseNode;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        boolean condition;
        try {
            condition = conditionNode.executeBoolean(frame);
        } catch (UnexpectedResultException e) {
            throw new RuntimeException("If condition must be a boolean, got: " + e.getResult());
        }

        if (profile.profile(condition)) {
            return thenNode.execute(frame);
        } else {
            return elseNode.execute(frame);
        }
    }
}
