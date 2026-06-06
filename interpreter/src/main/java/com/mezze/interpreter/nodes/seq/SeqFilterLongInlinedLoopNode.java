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
 * Body-adoption (real AST inlining) variant of {@link SeqFilterLongLoopNode}.
 *
 * <p>Same idea as {@link SeqFoldLongInlinedLoopNode} but for
 * {@code seq::filter}: instead of dispatching the predicate through a
 * {@link com.oracle.truffle.api.nodes.DirectCallNode} per iter, we
 * <strong>clone the predicate's body AST</strong> and run it directly
 * inside the loop's repeating node, with the element pre-written into
 * the cloned body's frame slot 0.
 *
 * <h2>Applicability</h2>
 *
 * Restricted to the common-case shape:
 * <ul>
 *   <li>Predicate is a {@link MezzeFunctionRootNode}</li>
 *   <li>Exactly 1 param (canonical {@code {x} -> bool} predicate)</li>
 *   <li>0 closure captures</li>
 * </ul>
 *
 * Other shapes fall back to {@link SeqFilterLongLoopNode}'s
 * DirectCallNode dispatch path.
 *
 * <h2>Frame layout</h2>
 *
 * Outer-loop frame tracks {@code index} and {@code count} (kept-so-far)
 * via {@link FrameSlotKind#Long} slots. Per-iter inner frame uses the
 * predicate's own {@link FrameDescriptor}, with slot 0 holding the
 * boxed-Long element. We write via {@code setObject} because user
 * lambdas typically have OBJECT param slots — EA scalarizes the box
 * inside the OSR'd body.
 *
 * <h2>State + re-entrancy</h2>
 *
 * One instance per distinct predicate {@link CallTarget} (size-1 PIC
 * managed by {@link SeqFilterCallNode}). The cloned body is a permanent
 * {@code @Child} — no per-call mutation. Re-entrant filters with
 * different predicates get separate instances and so don't conflict.
 */
@NodeInfo(shortName = "seq.filter.long.inlined.loop")
public final class SeqFilterLongInlinedLoopNode extends Node {

    /** Outer-loop frame: index + kept-so-far count. */
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

    /** Predicate's FrameDescriptor — used to allocate the inner frame per
     *  iter so the cloned body's slot indices stay valid. */
    @CompilationFinal
    private final FrameDescriptor predicateFD;

    @Child
    private LoopNode loopNode;

    public SeqFilterLongInlinedLoopNode(CallTarget predicateTarget, MezzeFunctionRootNode predicateRoot) {
        this.predicateTarget = predicateTarget;
        this.predicateFD = predicateRoot.getFrameDescriptor();
        MezzeNode clonedBody = NodeUtil.cloneNode(predicateRoot.getBodyNode());
        this.loopNode = Truffle.getRuntime().createLoopNode(
            new InlinedRepeating(clonedBody, predicateFD));
    }

    public CallTarget getPredicateTarget() {
        return predicateTarget;
    }

    /**
     * Returns true iff body-adoption is applicable for {@code predicateTarget}.
     * If false, caller must fall back to the {@link SeqFilterLongLoopNode}
     * dispatch path.
     */
    public static boolean canHandle(CallTarget predicateTarget) {
        if (!(predicateTarget instanceof RootCallTarget rct)) return false;
        if (!(rct.getRootNode() instanceof MezzeFunctionRootNode mrn)) return false;
        if (mrn.getParameterCount() != 1) return false;
        if (mrn.getClosureCount() != 0) return false;
        return mrn.getParamSlotKinds().length == 1;
    }

    /**
     * Runs the filter loop. Kept elements are written densely into
     * {@code results}; returns the count of kept elements. Caller is
     * responsible for trimming {@code results} to that count.
     */
    public int execute(long[] data, long[] results, int size) {
        VirtualFrame loopFrame = Truffle.getRuntime().createVirtualFrame(
            new Object[]{data, results, size},
            LOOP_FD);
        loopFrame.setLong(INDEX_SLOT, 0);
        loopFrame.setLong(COUNT_SLOT, 0);
        loopNode.execute(loopFrame);
        return (int) loopFrame.getLong(COUNT_SLOT);
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
            int size = (int) args[2];
            if (index >= size) {
                return false;
            }
            long[] data = (long[]) args[0];
            long[] results = (long[]) args[1];
            long elem = data[(int) index];

            // Inner frame for the cloned body. Predicate's FD has slot 0
            // = x. User lambdas typically have OBJECT param slots, so we
            // box via setObject; EA scalarizes the Long box inside the
            // OSR'd body since it doesn't escape.
            VirtualFrame innerFrame = Truffle.getRuntime().createVirtualFrame(
                new Object[0], predicateFD);
            innerFrame.setObject(0, elem);

            Object result = clonedBody.execute(innerFrame);
            if ((Boolean) result) {
                long count = frame.getLong(COUNT_SLOT);
                results[(int) count] = elem;
                frame.setLong(COUNT_SLOT, count + 1);
            }
            frame.setLong(INDEX_SLOT, index + 1);
            return true;
        }
    }
}
