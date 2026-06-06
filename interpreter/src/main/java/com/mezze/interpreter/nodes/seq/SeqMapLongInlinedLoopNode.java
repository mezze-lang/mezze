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
 * Body-adoption (real AST inlining) variant of {@link SeqMapLongLoopNode}.
 *
 * <p>Instead of dispatching the mapper through a {@link com.oracle.truffle.api.nodes.DirectCallNode}
 * — which costs frame allocation, ABI dispatch, record unpack, and
 * runBodyLoop overhead per iteration even when inlined — this node
 * <strong>clones the mapper's body AST</strong> and runs it directly
 * in-place per iteration, with {@code x} pre-written into the cloned
 * body's expected frame slot.
 *
 * <p>This is the canonical functional-language hot-loop optimization:
 * the map's "function call boundary" disappears entirely. PE sees a
 * straight-line graph of {@code x * 2} (or whatever the body computes)
 * inside the loop body.
 *
 * <h2>Applicability</h2>
 *
 * Restricted to the common-case shape that closes most of the gap:
 * <ul>
 *   <li>Mapper is a {@link MezzeFunctionRootNode} (not a wrapped
 *       lambda or polyglot Value)</li>
 *   <li>Exactly 1 param (the canonical {@code {x}} mapper)</li>
 *   <li>0 closure captures (so no closure-prefix slot binding)</li>
 * </ul>
 *
 * Other shapes fall back to {@link SeqMapLongLoopNode}'s DirectCallNode
 * dispatch path.
 *
 * <h2>Frame layout</h2>
 *
 * We allocate a per-call {@link VirtualFrame} with the mapper's
 * original {@link FrameDescriptor} — the cloned body reads slots by
 * integer index, and those indices remain valid. Slot 0 = x (since
 * closureCount == 0). The body itself writes to its own local slots
 * and returns via the normal MezzeNode.execute path.
 *
 * <h2>State + re-entrancy</h2>
 *
 * One instance per distinct mapper {@link CallTarget} (size-1 PIC
 * managed by {@link SeqMapCallNode}). The cloned body is a permanent
 * {@code @Child} — no per-call mutation. Re-entrant maps with
 * different mappers get separate {@link SeqMapLongInlinedLoopNode}
 * instances and so don't conflict.
 *
 * <h2>Loop driver</h2>
 *
 * The outer loop uses a Truffle {@link LoopNode} so OSR fires after
 * ~100k iterations (matching the pattern in
 * {@link SeqMapLongLoopNode}). Outer-loop slot tracks {@code index};
 * the body's frame is rebuilt per iter (frame alloc is escape-analyzed
 * away when the body compiles).
 */
@NodeInfo(shortName = "seq.map.long.inlined.loop")
public final class SeqMapLongInlinedLoopNode extends Node {

    /** Outer loop's frame layout (just index for the driver's
     *  bookkeeping). The cloned body uses the mapper's own
     *  FrameDescriptor for its inner frame. */
    private static final FrameDescriptor LOOP_FD;
    private static final int INDEX_SLOT;
    static {
        FrameDescriptor.Builder b = FrameDescriptor.newBuilder(1);
        INDEX_SLOT = b.addSlot(FrameSlotKind.Long, "index", null);
        LOOP_FD = b.build();
    }

    @CompilationFinal
    private final CallTarget mapperTarget;

    /** Mapper's FrameDescriptor — used to allocate the inner frame per
     *  iter so the cloned body's slot indices stay valid. */
    @CompilationFinal
    private final FrameDescriptor mapperFD;

    @Child
    private LoopNode loopNode;

    public SeqMapLongInlinedLoopNode(CallTarget mapperTarget, MezzeFunctionRootNode mapperRoot) {
        this.mapperTarget = mapperTarget;
        this.mapperFD = mapperRoot.getFrameDescriptor();
        // Clone the body once. The clone is @Child of the InlinedRepeating
        // node so PE adopts it into our tree; the original body still lives
        // under the mapper's root for the cached-DirectCallNode path used
        // by other call sites.
        MezzeNode clonedBody = NodeUtil.cloneNode(mapperRoot.getBodyNode());
        this.loopNode = Truffle.getRuntime().createLoopNode(
            new InlinedRepeating(clonedBody, mapperFD));
    }

    public CallTarget getMapperTarget() {
        return mapperTarget;
    }

    /**
     * Returns true iff body-adoption is applicable for {@code mapperTarget}.
     * If false, caller must fall back to the {@link SeqMapLongLoopNode}
     * dispatch path.
     */
    public static boolean canHandle(CallTarget mapperTarget) {
        if (!(mapperTarget instanceof RootCallTarget rct)) return false;
        if (!(rct.getRootNode() instanceof MezzeFunctionRootNode mrn)) return false;
        if (mrn.getParameterCount() != 1) return false;
        if (mrn.getClosureCount() != 0) return false;
        // Slot kinds can be LONG (0) or OBJECT (3) — for OBJECT we box at
        // the boundary, but the call dispatch overhead still goes away.
        // Even with boxing, EA can scalarize the Long boxes inside the
        // OSR'd body since they don't escape past the cloned body's
        // execute().
        return mrn.getParamSlotKinds().length == 1;
    }

    /**
     * Runs the map loop, writing into {@code results} in place. Caller
     * has already allocated the results array.
     */
    public void execute(long[] data, Object[] results, int size) {
        VirtualFrame loopFrame = Truffle.getRuntime().createVirtualFrame(
            new Object[]{data, results, size},
            LOOP_FD);
        loopFrame.setLong(INDEX_SLOT, 0);
        loopNode.execute(loopFrame);
    }

    static final class InlinedRepeating extends Node implements RepeatingNode {

        @Child
        private MezzeNode clonedBody;

        @CompilationFinal
        private final FrameDescriptor mapperFD;

        InlinedRepeating(MezzeNode clonedBody, FrameDescriptor mapperFD) {
            this.clonedBody = clonedBody;
            this.mapperFD = mapperFD;
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
            Object[] results = (Object[]) args[1];
            long element = data[(int) index];

            // Inner frame for the cloned body. Mapper's FD has slot 0 =
            // x. Slot kinds are usually OBJECT (since user-typed lambdas
            // are typically generic), so we write the Long-boxed value
            // via setObject. EA scalarizes the box inside the OSR'd body
            // since it doesn't escape.
            VirtualFrame innerFrame = Truffle.getRuntime().createVirtualFrame(
                new Object[0], mapperFD);
            innerFrame.setObject(0, element);

            Object result = clonedBody.execute(innerFrame);
            results[(int) index] = result;

            frame.setLong(INDEX_SLOT, index + 1);
            return true;
        }
    }
}
