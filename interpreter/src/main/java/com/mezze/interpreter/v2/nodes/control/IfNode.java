package com.mezze.interpreter.v2.nodes.control;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.profiles.InlinedConditionProfile;

/**
 * If-then-else node with INLINED branch profiling.
 *
 * Uses InlinedConditionProfile for:
 * 1. Better memory layout (profile data inline with node)
 * 2. Faster interpreter performance
 * 3. Better JIT compilation
 */
@NodeChild(value = "conditionNode", type = MezzeNode.class)
@NodeInfo(shortName = "if")
public abstract class IfNode extends MezzeNode {

    @Child private MezzeNode thenNode;
    @Child private MezzeNode elseNode;

    protected IfNode(MezzeNode thenNode, MezzeNode elseNode) {
        this.thenNode = thenNode;
        this.elseNode = elseNode;
    }

    /**
     * Boolean condition - most common case.
     * Uses InlinedConditionProfile for optimal branch prediction.
     */
    @Specialization
    protected Object doBoolean(VirtualFrame frame, boolean condition,
            @Cached InlinedConditionProfile profile) {
        if (profile.profile(this, condition)) {
            return thenNode.execute(frame);
        } else {
            return elseNode.execute(frame);
        }
    }

    /**
     * Create an if node.
     */
    public static IfNode create(MezzeNode conditionNode, MezzeNode thenNode, MezzeNode elseNode) {
        return IfNodeGen.create(thenNode, elseNode, conditionNode);
    }
}
