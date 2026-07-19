package com.mezze.interpreter.nodes.seq;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.nodes.function.MezzeFunctionRootNode;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ControlFlowException;
import com.oracle.truffle.api.nodes.LoopNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.api.nodes.RepeatingNode;

/**
 * Body-adoption (real AST inlining) variant of {@link SeqFoldLongLoopNode}.
 *
 * <p>Instead of dispatching the folder through a {@link com.oracle.truffle.api.nodes.DirectCallNode}
 * — which costs frame allocation, ABI dispatch, record unpack, and
 * runBodyLoop overhead per iteration even when inlined — this node
 * <strong>clones the folder's body AST</strong> and runs it directly
 * in-place per iteration, with {@code acc} and {@code x} pre-written
 * into the cloned body's expected frame slots.
 *
 * <p>This is the canonical functional-language hot-loop optimization:
 * the fold's "function call boundary" disappears entirely. PE sees a
 * straight-line graph of {@code acc + x} (or whatever the body
 * computes) inside the loop body.
 *
 * <h2>Applicability</h2>
 *
 * Restricted to the common-case shape that closes most of the gap:
 * <ul>
 *   <li>Folder is a {@link MezzeFunctionRootNode} (not a wrapped
 *       lambda or polyglot Value)</li>
 *   <li>Exactly 2 LONG-kind params (the canonical {@code {acc, x}}
 *       arithmetic fold)</li>
 *   <li>0 closure captures (so no closure-prefix slot binding)</li>
 * </ul>
 *
 * Other shapes fall back to {@link SeqFoldLongLoopNode}'s DirectCallNode
 * dispatch path.
 *
 * <h2>Frame layout</h2>
 *
 * We allocate a per-call {@link VirtualFrame} with the folder's
 * original {@link FrameDescriptor} — the cloned body reads slots by
 * integer index, and those indices remain valid. Slot 0 = acc,
 * slot 1 = x (since closureCount == 0). The body itself writes to its
 * own local slots and returns via the normal MezzeNode.execute path.
 *
 * <h2>State + re-entrancy</h2>
 *
 * One instance per distinct folder {@link CallTarget} (size-1 PIC
 * managed by {@link SeqFoldCallNode}). The cloned body is a permanent
 * {@code @Child} — no per-call mutation. Re-entrant folds with
 * different folders get separate {@link SeqFoldLongInlinedLoopNode}
 * instances and so don't conflict.
 *
 * <h2>Loop driver</h2>
 *
 * The outer loop uses a Truffle {@link LoopNode} so OSR fires after
 * ~100k iterations (matching the pattern in
 * {@link SeqFoldLongLoopNode}). Outer-loop slots track {@code index}
 * and {@code accLong}; the body's frame is rebuilt per iter from
 * those values (frame alloc is escape-analyzed away when the body
 * compiles).
 */
@NodeInfo(shortName = "seq.fold.long.inlined.loop")
public final class SeqFoldLongInlinedLoopNode extends Node {

    /** Outer loop's frame layout (index + boxed-Object acc, for the
     *  driver's bookkeeping). The cloned body uses the folder's own
     *  FrameDescriptor for its inner frame. */
    private static final FrameDescriptor LOOP_FD;
    private static final int ACC_SLOT;
    private static final int INDEX_SLOT;
    static {
        FrameDescriptor.Builder b = FrameDescriptor.newBuilder(2);
        ACC_SLOT = b.addSlot(FrameSlotKind.Long, "acc", null);
        INDEX_SLOT = b.addSlot(FrameSlotKind.Long, "index", null);
        LOOP_FD = b.build();
    }

    @CompilationFinal
    private final CallTarget folderTarget;

    /** Folder's FrameDescriptor — used to allocate the inner frame per
     *  iter so the cloned body's slot indices stay valid. */
    @CompilationFinal
    private final FrameDescriptor folderFD;

    @Child
    private LoopNode loopNode;

    public SeqFoldLongInlinedLoopNode(CallTarget folderTarget, MezzeFunctionRootNode folderRoot) {
        this.folderTarget = folderTarget;
        this.folderFD = folderRoot.getFrameDescriptor();
        // Clone the body once. The clone is @Child of the InlinedRepeating
        // node so PE adopts it into our tree; the original body still lives
        // under the folder's root for the cached-DirectCallNode path used
        // by other call sites.
        MezzeNode clonedBody = NodeUtil.cloneNode(folderRoot.getBodyNode());
        this.loopNode = Truffle.getRuntime().createLoopNode(
            new InlinedRepeating(clonedBody, folderFD));
    }

    public CallTarget getFolderTarget() {
        return folderTarget;
    }

    /**
     * Returns true iff body-adoption is applicable for {@code folderTarget}.
     * If false, caller must fall back to the {@link SeqFoldLongLoopNode}
     * dispatch path.
     */
    public static boolean canHandle(CallTarget folderTarget) {
        if (!(folderTarget instanceof RootCallTarget rct)) return false;
        if (!(rct.getRootNode() instanceof MezzeFunctionRootNode mrn)) return false;
        if (mrn.getParameterCount() != 2) return false;
        if (mrn.getClosureCount() != 0) return false;
        // Slot kinds can be LONG (0) or OBJECT (3) — for OBJECT we box at
        // the boundary, but the call dispatch overhead still goes away.
        // Even with boxing, EA can scalarize the Long boxes inside the
        // OSR'd body since they don't escape past the cloned body's
        // execute().
        return mrn.getParamSlotKinds().length == 2;
    }

    /**
     * Runs the fold loop. {@code init} must be a {@link Long} (we
     * require it for the typed-LONG accumulator path); caller is
     * responsible for that guard.
     *
     * <p>Returns {@code null} if the folder produced a non-{@code Long}
     * result mid-fold (accumulator promoted to {@code Double} via
     * overflow, or the folder is non-arithmetic despite matching
     * {@link #canHandle}). Caller should then fall back to the generic
     * {@link SeqFoldLongLoopNode} or Object-typed path. See
     * {@link NonLongResultException} for why we don't just throw
     * {@link ClassCastException}: {@link com.oracle.truffle.api.nodes.ControlFlowException}
     * is trusted by PE and doesn't materialize a Java stack trace on
     * the hot path. #59.
     */
    public Long execute(long[] data, long init, int size) {
        VirtualFrame loopFrame = Truffle.getRuntime().createVirtualFrame(
            new Object[]{data, size},
            LOOP_FD);
        loopFrame.setLong(ACC_SLOT, init);
        loopFrame.setLong(INDEX_SLOT, 0);
        try {
            loopNode.execute(loopFrame);
        } catch (NonLongResultException e) {
            // Body produced a non-Long. Signal fallback to the caller.
            // The @CompilationFinal fallback flag on caller ensures
            // future iterations skip this node's fast path entirely.
            CompilerDirectives.transferToInterpreterAndInvalidate();
            return null;
        }
        return loopFrame.getLong(ACC_SLOT);
    }

    /**
     * Thrown by {@link InlinedRepeating} when the folder body returns a
     * non-{@link Long}. Extends {@link ControlFlowException} so PE
     * treats it as expected control flow (no stack trace, no cost when
     * not thrown).
     */
    static final class NonLongResultException extends ControlFlowException {
        private static final long serialVersionUID = 1L;
        static final NonLongResultException INSTANCE = new NonLongResultException();
    }

    static final class InlinedRepeating extends Node implements RepeatingNode {

        @Child
        private MezzeNode clonedBody;

        @CompilationFinal
        private final FrameDescriptor folderFD;

        InlinedRepeating(MezzeNode clonedBody, FrameDescriptor folderFD) {
            this.clonedBody = clonedBody;
            this.folderFD = folderFD;
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
            long acc = frame.getLong(ACC_SLOT);
            long element = data[(int) index];

            // Inner frame for the cloned body. Folder's FD has slot 0 =
            // acc, slot 1 = x. Slot kinds are usually OBJECT (since
            // user-typed lambdas are typically generic), so we write
            // Long-boxed values via setObject. EA scalarizes the boxes
            // inside the OSR'd body since they don't escape.
            VirtualFrame innerFrame = Truffle.getRuntime().createVirtualFrame(
                new Object[0], folderFD);
            innerFrame.setObject(0, acc);
            innerFrame.setObject(1, element);

            Object result = clonedBody.execute(innerFrame);
            // Body returns a Long for {acc, x} -> acc + x. If the
            // folder body arithmetic overflows Long and Mezze promotes
            // the result to Double, the raw cast would blow up here.
            // Signal a graceful fallback via ControlFlowException so
            // execute() can restart the fold on the generic path.
            // #59.
            if (!(result instanceof Long)) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                throw NonLongResultException.INSTANCE;
            }
            long next = (Long) result;

            frame.setLong(ACC_SLOT, next);
            frame.setLong(INDEX_SLOT, index + 1);
            return true;
        }
    }
}
