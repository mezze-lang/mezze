package com.mezze.interpreter.nodes.seq;

import com.mezze.interpreter.values.MezzeRecord;
import com.oracle.truffle.api.CallTarget;
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
 * LoopNode-driven {@code seq::map} over an {@code Object[]} backing array,
 * monomorphic on the mapper {@link CallTarget}. Sibling of
 * {@link SeqMapLongLoopNode} — see that node for the full rationale on
 * why the per-element mapper dispatch must live inside a Truffle
 * {@link LoopNode} with the {@link DirectCallNode} as a {@code @Child}
 * of the repeating node (Truffle inliner visibility).
 */
@NodeInfo(shortName = "seq.map.object.loop")
public final class SeqMapObjectLoopNode extends Node {

    private static final FrameDescriptor LOOP_FD;
    private static final int INDEX_SLOT;
    static {
        FrameDescriptor.Builder b = FrameDescriptor.newBuilder(1);
        INDEX_SLOT = b.addSlot(FrameSlotKind.Long, "index", null);
        LOOP_FD = b.build();
    }

    @CompilationFinal
    private final CallTarget mapperTarget;

    @Child
    private LoopNode loopNode;

    public SeqMapObjectLoopNode(CallTarget mapperTarget) {
        this.mapperTarget = mapperTarget;
        this.loopNode = Truffle.getRuntime().createLoopNode(new ObjectMapRepeatingNode(mapperTarget));
    }

    public CallTarget getMapperTarget() {
        return mapperTarget;
    }

    /**
     * Runs the loop, writing into {@code results} in place. Returns
     * results so the caller can chain into {@code MezzeSeq.fromArray}.
     */
    public Object[] execute(Object[] data, Object[] results, int size,
                            Object[] closureValues, int closureLength) {
        VirtualFrame loopFrame = Truffle.getRuntime().createVirtualFrame(
            new Object[]{data, results, size, closureValues, closureLength},
            LOOP_FD);
        loopFrame.setLong(INDEX_SLOT, 0);
        loopNode.execute(loopFrame);
        return results;
    }

    static final class ObjectMapRepeatingNode extends Node implements RepeatingNode {

        @Child
        private DirectCallNode callNode;

        ObjectMapRepeatingNode(CallTarget mapperTarget) {
            this.callNode = DirectCallNode.create(mapperTarget);
        }

        @Override
        public boolean executeRepeating(VirtualFrame frame) {
            long index = frame.getLong(INDEX_SLOT);
            Object[] args = frame.getArguments();
            int size = (int) args[2];
            if (index >= size) {
                return false;
            }
            Object[] data = (Object[]) args[0];
            Object[] results = (Object[]) args[1];
            Object[] closureValues = (Object[]) args[3];
            int closureLength = (int) args[4];

            results[(int) index] = callMapper(data[(int) index], closureValues, closureLength);
            frame.setLong(INDEX_SLOT, index + 1);
            return true;
        }

        private Object callMapper(Object element, Object[] closureValues, int closureLength) {
            MezzeRecord arg = MezzeRecord.wrapX(element);
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
