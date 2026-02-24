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
 * While loop node with loop profiling.
 * Written in Java to avoid Kotlin intrinsics in the JIT path.
 */
@NodeInfo(shortName = "while-loop")
public final class WhileNode extends MezzeNode {

    @Child
    private MezzeNode conditionNode;

    @Child
    private MezzeNode bodyNode;

    @SuppressWarnings("deprecation")
    private final ConditionProfile conditionProfile = ConditionProfile.createCountingProfile();

    public WhileNode(MezzeNode conditionNode, MezzeNode bodyNode) {
        this.conditionNode = conditionNode;
        this.bodyNode = bodyNode;
    }

    @Override
    public MezzeValue execute(VirtualFrame frame) {
        MezzeValue result = MezzeNull.INSTANCE;

        while (true) {
            MezzeValue condition = conditionNode.execute(frame);

            boolean shouldContinue;
            if (condition instanceof MezzeBool) {
                shouldContinue = ((MezzeBool) condition).getValue();
            } else {
                return throwNotBooleanCondition(condition);
            }

            if (!conditionProfile.profile(shouldContinue)) {
                break;
            }

            result = bodyNode.execute(frame);
        }

        return result;
    }

    @TruffleBoundary
    private MezzeValue throwNotBooleanCondition(MezzeValue condition) {
        throw new RuntimeException("Loop condition must be boolean, got " + condition.getClass().getSimpleName());
    }
}
