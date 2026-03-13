package com.mezze.interpreter.v2.nodes.mutation;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.values.MezzeFunction;
import com.mezze.interpreter.v2.values.MezzeRecord;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.LoopNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.RepeatingNode;
import com.oracle.truffle.api.profiles.LoopConditionProfile;

/**
 * Optimized while loop for mutation blocks.
 *
 * Uses Truffle's LoopNode for OSR (on-stack replacement) optimization.
 *
 * Usage: while {cond = {} -> ..., body = {} -> ...}
 */
@NodeInfo(shortName = "while-mut")
public final class WhileMutNode extends MezzeNode {

    @Child private MezzeNode condFnNode;
    @Child private MezzeNode bodyFnNode;
    @Child private LoopNode loopNode;

    private final int condSlot;
    private final int bodySlot;

    public WhileMutNode(MezzeNode condFnNode, MezzeNode bodyFnNode,
                        FrameDescriptor.Builder builder) {
        this.condFnNode = condFnNode;
        this.bodyFnNode = bodyFnNode;

        this.condSlot = builder.addSlot(FrameSlotKind.Object, "while_cond", null);
        this.bodySlot = builder.addSlot(FrameSlotKind.Object, "while_body", null);
    }

    public WhileMutNode(MezzeNode condFnNode, MezzeNode bodyFnNode,
                        int condSlot, int bodySlot) {
        this.condFnNode = condFnNode;
        this.bodyFnNode = bodyFnNode;
        this.condSlot = condSlot;
        this.bodySlot = bodySlot;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object condFn = condFnNode.execute(frame);
        Object bodyFn = bodyFnNode.execute(frame);

        if (!(condFn instanceof MezzeFunction cond)) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("while cond must be a function");
        }
        if (!(bodyFn instanceof MezzeFunction body)) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("while body must be a function");
        }

        frame.setObject(condSlot, cond);
        frame.setObject(bodySlot, body);

        if (loopNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            loopNode = insert(Truffle.getRuntime().createLoopNode(
                new WhileRepeatingNode(condSlot, bodySlot)
            ));
        }

        loopNode.execute(frame);
        return MezzeRecord.empty();
    }

    private static final class WhileRepeatingNode extends Node implements RepeatingNode {

        private final int condSlot;
        private final int bodySlot;

        @Child private DirectCallNode condCallNode;
        @Child private DirectCallNode bodyCallNode;

        private final LoopConditionProfile loopProfile = LoopConditionProfile.createCountingProfile();

        @CompilerDirectives.CompilationFinal(dimensions = 1)
        private Object[] condCallArgs;

        @CompilerDirectives.CompilationFinal(dimensions = 1)
        private Object[] bodyCallArgs;

        @CompilerDirectives.CompilationFinal
        private boolean initialized = false;

        WhileRepeatingNode(int condSlot, int bodySlot) {
            this.condSlot = condSlot;
            this.bodySlot = bodySlot;
        }

        @Override
        public boolean executeRepeating(VirtualFrame frame) {
            MezzeFunction cond = (MezzeFunction) frame.getObject(condSlot);
            MezzeFunction body = (MezzeFunction) frame.getObject(bodySlot);

            // Initialize on first iteration
            if (!initialized) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                condCallNode = insert(DirectCallNode.create(cond.getCallTarget()));
                bodyCallNode = insert(DirectCallNode.create(body.getCallTarget()));

                Object[] condClosure = cond.getClosureValues();
                condCallArgs = new Object[condClosure.length + 1];
                System.arraycopy(condClosure, 0, condCallArgs, 0, condClosure.length);
                condCallArgs[condClosure.length] = MezzeRecord.empty();

                Object[] bodyClosure = body.getClosureValues();
                bodyCallArgs = new Object[bodyClosure.length + 1];
                System.arraycopy(bodyClosure, 0, bodyCallArgs, 0, bodyClosure.length);
                bodyCallArgs[bodyClosure.length] = MezzeRecord.empty();

                initialized = true;
            }

            // Evaluate condition
            Object condResult = condCallNode.call(condCallArgs);

            if (loopProfile.profile(condResult instanceof Boolean b && b)) {
                // Execute body
                bodyCallNode.call(bodyCallArgs);
                return true; // Continue loop
            }

            return false; // Exit loop
        }
    }
}
