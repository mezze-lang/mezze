package com.mezze.interpreter.nodes.seq;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.nodes.function.MezzeFunctionRootNode;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.LoopNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.api.nodes.RepeatingNode;

/**
 * Body-adoption (real AST inlining) variant of the long-storage
 * {@code seq::all} loop. Short-circuits on the first failing element.
 *
 * <p>Applicability gate matches the count/any variants. Falls back to
 * {@code LongAllLoopNode}'s DirectCallNode dispatch when the predicate
 * has closures or non-canonical param shape.
 */
@NodeInfo(shortName = "seq.all.long.inlined.loop")
public final class SeqAllLongInlinedLoopNode extends Node {

    private static final FrameDescriptor LOOP_FD;
    private static final int INDEX_SLOT;
    private static final int FAILED_SLOT;
    static {
        FrameDescriptor.Builder b = FrameDescriptor.newBuilder(2);
        INDEX_SLOT = b.addSlot(FrameSlotKind.Long, "index", null);
        FAILED_SLOT = b.addSlot(FrameSlotKind.Boolean, "failed", null);
        LOOP_FD = b.build();
    }

    @CompilationFinal
    private final CallTarget predicateTarget;

    @CompilationFinal
    private final FrameDescriptor predicateFD;

    @Child
    private LoopNode loopNode;

    public SeqAllLongInlinedLoopNode(CallTarget predicateTarget, MezzeFunctionRootNode predicateRoot) {
        this.predicateTarget = predicateTarget;
        this.predicateFD = predicateRoot.getFrameDescriptor();
        MezzeNode clonedBody = NodeUtil.cloneNode(predicateRoot.getBodyNode());
        this.loopNode = Truffle.getRuntime().createLoopNode(
            new InlinedRepeating(clonedBody, predicateFD));
    }

    public CallTarget getPredicateTarget() {
        return predicateTarget;
    }

    public static boolean canHandle(CallTarget predicateTarget) {
        if (!(predicateTarget instanceof RootCallTarget rct)) return false;
        if (!(rct.getRootNode() instanceof MezzeFunctionRootNode mrn)) return false;
        if (mrn.getParameterCount() != 1) return false;
        if (mrn.getClosureCount() != 0) return false;
        return mrn.getParamSlotKinds().length == 1;
    }

    public Object execute(long[] data, int size) {
        VirtualFrame loopFrame = Truffle.getRuntime().createVirtualFrame(
            new Object[]{data, size}, LOOP_FD);
        loopFrame.setLong(INDEX_SLOT, 0);
        loopFrame.setBoolean(FAILED_SLOT, false);
        loopNode.execute(loopFrame);
        return loopFrame.getBoolean(FAILED_SLOT) ? Boolean.FALSE : Boolean.TRUE;
    }

    static final class InlinedRepeating extends Node implements RepeatingNode {

        @Child
        private MezzeNode clonedBody;

        @CompilationFinal
        private final FrameDescriptor predicateFD;

        InlinedRepeating(MezzeNode clonedBody, FrameDescriptor predicateFD) {
            this.clonedBody = clonedBody;
            this.predicateFD = predicateFD;
        }

        @Override
        public boolean executeRepeating(VirtualFrame frame) {
            long index = frame.getLong(INDEX_SLOT);
            Object[] args = frame.getArguments();
            int size = (int) args[1];
            if (index >= size) {
                return false;
            }
            long[] data = (long[]) args[0];
            long element = data[(int) index];

            VirtualFrame innerFrame = Truffle.getRuntime().createVirtualFrame(
                new Object[0], predicateFD);
            innerFrame.setObject(0, element);

            Object result = clonedBody.execute(innerFrame);
            if (!(result instanceof Boolean b) || !b) {
                frame.setBoolean(FAILED_SLOT, true);
                return false; // short-circuit
            }
            frame.setLong(INDEX_SLOT, index + 1);
            return true;
        }
    }
}
