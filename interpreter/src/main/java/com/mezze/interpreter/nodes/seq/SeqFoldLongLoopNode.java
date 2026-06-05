package com.mezze.interpreter.nodes.seq;

import com.mezze.interpreter.values.MezzeRecord;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.LoopNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.RepeatingNode;

/**
 * LoopNode-driven fold over a {@code long[]} backing array, monomorphic
 * on the folder {@link CallTarget}. Exists so the per-element folder
 * dispatch lives inside a Truffle {@link LoopNode} with the
 * {@link DirectCallNode} as a {@code @Child} of the repeating node —
 * which is what lets Truffle's inliner see and inline the folder body.
 *
 * <p>Pre-fix: {@code SeqFoldCallNode.foldLongLarge} was
 * {@code @TruffleBoundary} so the loop was opaque to PE — every iter
 * paid an interpreter→JIT boundary into the (separately compiled)
 * folder, and the per-iter {@link MezzeRecord} + {@code Object[]} +
 * Long-boxing allocs couldn't be escape-analyzed away.
 *
 * <p>Post-fix:
 * <ul>
 *   <li>LoopNode reports iteration count → OSR fires after ~100k iters
 *       even when the enclosing {@code seq::fold} CallTarget never
 *       compiles (1-shot bench).</li>
 *   <li>DirectCallNode is a {@code @Child} of {@link LongFoldRepeatingNode}
 *       → Truffle inlines the folder into the OSR'd body.</li>
 *   <li>Per-iter allocs are visible to the compiled body → escape
 *       analysis scalarizes them away.</li>
 * </ul>
 *
 * <h2>Re-entrancy / monomorphism</h2>
 *
 * The owning {@code SeqFoldCallNode} keeps one of these per distinct
 * folder {@link CallTarget} (size-1 PIC). If a recursive fold uses a
 * different folder, the {@code SeqFoldCallNode} creates a separate
 * instance for it; this node never gets reused across different
 * folders. So the cached {@link DirectCallNode} is stable.
 *
 * <h2>Loop state</h2>
 *
 * Each {@link #execute} call allocates a fresh {@link VirtualFrame}
 * for the loop. Slots:
 * <ul>
 *   <li>{@code ACC_SLOT}: current accumulator (Object).</li>
 *   <li>{@code INDEX_SLOT}: next-to-read index (long).</li>
 * </ul>
 * The args array carries the constants (data, size, closureValues,
 * closureLength). They don't change across iterations.
 */
@NodeInfo(shortName = "seq.fold.long.loop")
public final class SeqFoldLongLoopNode extends Node {

    private static final FrameDescriptor LOOP_FD;
    private static final int ACC_SLOT;
    private static final int INDEX_SLOT;
    static {
        FrameDescriptor.Builder b = FrameDescriptor.newBuilder(2);
        ACC_SLOT = b.addSlot(FrameSlotKind.Object, "acc", null);
        INDEX_SLOT = b.addSlot(FrameSlotKind.Long, "index", null);
        LOOP_FD = b.build();
    }

    /**
     * The folder's CallTarget — used as the identity key. {@code execute}
     * must only be invoked with this same target; the owner is
     * responsible for the dispatch.
     */
    @CompilationFinal
    private final CallTarget folderTarget;

    @Child
    private LoopNode loopNode;

    public SeqFoldLongLoopNode(CallTarget folderTarget) {
        this.folderTarget = folderTarget;
        this.loopNode = Truffle.getRuntime().createLoopNode(new LongFoldRepeatingNode(folderTarget));
    }

    public CallTarget getFolderTarget() {
        return folderTarget;
    }

    public Object execute(long[] data, Object init, int size,
                          Object[] closureValues, int closureLength) {
        VirtualFrame loopFrame = Truffle.getRuntime().createVirtualFrame(
            new Object[]{data, size, closureValues, closureLength},
            LOOP_FD);
        loopFrame.setObject(ACC_SLOT, init);
        loopFrame.setLong(INDEX_SLOT, 0);
        loopNode.execute(loopFrame);
        return loopFrame.getObject(ACC_SLOT);
    }

    static final class LongFoldRepeatingNode extends Node implements RepeatingNode {

        @Child
        private DirectCallNode callNode;

        LongFoldRepeatingNode(CallTarget folderTarget) {
            this.callNode = DirectCallNode.create(folderTarget);
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
            Object[] closureValues = (Object[]) args[2];
            int closureLength = (int) args[3];

            Object acc = frame.getObject(ACC_SLOT);
            Object next = callFolder(acc, data[(int) index], closureValues, closureLength);

            frame.setObject(ACC_SLOT, next);
            frame.setLong(INDEX_SLOT, index + 1);
            return true;
        }

        private Object callFolder(Object acc, long element,
                                  Object[] closureValues, int closureLength) {
            MezzeRecord arg = MezzeRecord.wrapAccX(acc, element);
            if (closureLength == 0) {
                return callNode.call(new Object[]{arg});
            }
            Object[] callArgs = new Object[closureLength + 1];
            for (int i = 0; i < closureLength; i++) {
                callArgs[i] = closureValues[i];
            }
            callArgs[closureLength] = arg;
            return callNode.call(callArgs);
        }
    }
}
