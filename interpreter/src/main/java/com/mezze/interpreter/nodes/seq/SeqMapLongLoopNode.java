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
 * LoopNode-driven {@code seq::map} over a {@code long[]} backing array,
 * monomorphic on the mapper {@link CallTarget}. Mirror of
 * {@link SeqFoldLongLoopNode} — exists so the per-element mapper
 * dispatch lives inside a Truffle {@link LoopNode} with the
 * {@link DirectCallNode} as a {@code @Child} of the repeating node,
 * which is what lets Truffle's inliner see and inline the mapper.
 *
 * <p>Pre-fix: {@code SeqMapCallNode.mapLongLarge} was
 * {@code @TruffleBoundary} — opaque to PE; every iter paid an
 * interpreter→JIT boundary into the (separately compiled) mapper, and
 * the per-iter {@link MezzeRecord} + {@code Object[]} + Long-boxing
 * allocs couldn't be escape-analyzed away.
 *
 * <p>State: per call we allocate a fresh {@link VirtualFrame} carrying
 * {@code [data, results, size, closureValues, closureLength]} in args
 * and {@code INDEX_SLOT} for the loop counter. The output {@code Object[]}
 * is created by the caller, passed in via args[1].
 *
 * <p>Re-entrancy: one instance per distinct mapper CallTarget (size-1
 * PIC, managed by the owning {@link SeqMapCallNode}).
 */
@NodeInfo(shortName = "seq.map.long.loop")
public final class SeqMapLongLoopNode extends Node {

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

    public SeqMapLongLoopNode(CallTarget mapperTarget) {
        this.mapperTarget = mapperTarget;
        this.loopNode = Truffle.getRuntime().createLoopNode(new LongMapRepeatingNode(mapperTarget));
    }

    public CallTarget getMapperTarget() {
        return mapperTarget;
    }

    /**
     * Runs the loop, writing into {@code results} in place. Returns
     * results so the caller can chain into {@code MezzeSeq.fromArray}.
     */
    public Object[] execute(long[] data, Object[] results, int size,
                            Object[] closureValues, int closureLength) {
        VirtualFrame loopFrame = Truffle.getRuntime().createVirtualFrame(
            new Object[]{data, results, size, closureValues, closureLength},
            LOOP_FD);
        loopFrame.setLong(INDEX_SLOT, 0);
        loopNode.execute(loopFrame);
        return results;
    }

    static final class LongMapRepeatingNode extends Node implements RepeatingNode {

        @Child
        private DirectCallNode callNode;

        LongMapRepeatingNode(CallTarget mapperTarget) {
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
            long[] data = (long[]) args[0];
            Object[] results = (Object[]) args[1];
            Object[] closureValues = (Object[]) args[3];
            int closureLength = (int) args[4];

            results[(int) index] = callMapper(data[(int) index], closureValues, closureLength);
            frame.setLong(INDEX_SLOT, index + 1);
            return true;
        }

        private Object callMapper(long element, Object[] closureValues, int closureLength) {
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
