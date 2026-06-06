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
 * {@code seq::count} loop. Mirrors {@link SeqFoldLongInlinedLoopNode}
 * but for a 1-arg predicate returning Boolean.
 *
 * <p>Restricted to the canonical shape: 1-param predicate with 0
 * closures (the typical {@code (x) -> p x} form). Other shapes fall
 * back to the DirectCallNode-driven {@code LongCountLoopNode} inside
 * {@link SeqCountCallNode}.
 *
 * <p>The cloned body runs against the predicate's own
 * {@link FrameDescriptor}; slot 0 is the element {@code x}. We use
 * {@code setObject} because user lambdas' param slots are typically
 * OBJECT-typed — EA scalarizes the Long box inside the OSR'd body.
 */
@NodeInfo(shortName = "seq.count.long.inlined.loop")
public final class SeqCountLongInlinedLoopNode extends Node {

    private static final FrameDescriptor LOOP_FD;
    private static final int INDEX_SLOT;
    private static final int COUNT_SLOT;
    static {
        FrameDescriptor.Builder b = FrameDescriptor.newBuilder(2);
        INDEX_SLOT = b.addSlot(FrameSlotKind.Long, "index", null);
        COUNT_SLOT = b.addSlot(FrameSlotKind.Long, "count", null);
        LOOP_FD = b.build();
    }

    @CompilationFinal
    private final CallTarget predicateTarget;

    @CompilationFinal
    private final FrameDescriptor predicateFD;

    @Child
    private LoopNode loopNode;

    public SeqCountLongInlinedLoopNode(CallTarget predicateTarget, MezzeFunctionRootNode predicateRoot) {
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

    public long execute(long[] data, int size) {
        VirtualFrame loopFrame = Truffle.getRuntime().createVirtualFrame(
            new Object[]{data, size}, LOOP_FD);
        loopFrame.setLong(INDEX_SLOT, 0);
        loopFrame.setLong(COUNT_SLOT, 0);
        loopNode.execute(loopFrame);
        return loopFrame.getLong(COUNT_SLOT);
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
            if (result instanceof Boolean b && b) {
                frame.setLong(COUNT_SLOT, frame.getLong(COUNT_SLOT) + 1);
            }
            frame.setLong(INDEX_SLOT, index + 1);
            return true;
        }
    }
}
