package com.mezze.language.interpreter.nodes.control;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node.Child;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.profiles.ConditionProfile;

import com.mezze.language.interpreter.MezzeBool;
import com.mezze.language.interpreter.MezzeNull;
import com.mezze.language.interpreter.MezzeValue;
import com.mezze.language.interpreter.nodes.MezzeNode;

/**
 * If-then-else conditional node with branch profiling.
 * Written in Java to avoid Kotlin intrinsics in the JIT path.
 */
@NodeInfo(shortName = "if-then-else")
public final class IfNode extends MezzeNode {

    @Child
    private MezzeNode conditionNode;

    @Child
    private MezzeNode thenNode;

    @Child
    private MezzeNode elseNode;

    @SuppressWarnings("deprecation")
    private final ConditionProfile conditionProfile = ConditionProfile.createCountingProfile();

    public IfNode(MezzeNode conditionNode, MezzeNode thenNode, MezzeNode elseNode) {
        this.conditionNode = conditionNode;
        this.thenNode = thenNode;
        this.elseNode = elseNode;
    }

    @Override
    public MezzeValue execute(VirtualFrame frame) {
        MezzeValue condition = conditionNode.execute(frame);

        boolean isTrue;
        if (condition instanceof MezzeBool) {
            isTrue = ((MezzeBool) condition).getValue();
        } else {
            return throwNotBoolean(condition);
        }

        if (conditionProfile.profile(isTrue)) {
            return thenNode.execute(frame);
        } else {
            if (elseNode != null) {
                return elseNode.execute(frame);
            } else {
                return MezzeNull.INSTANCE;
            }
        }
    }

    @TruffleBoundary
    private MezzeValue throwNotBoolean(MezzeValue condition) {
        throw new RuntimeException("Condition must be boolean, got " + condition.getClass().getSimpleName());
    }
}
