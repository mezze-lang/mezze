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
 * LoopNode-driven {@code seq::filter} over a {@code long[]} backing
 * array, monomorphic on the predicate {@link CallTarget}. Mirror of
 * {@link SeqMapLongLoopNode} / {@link SeqFoldLongLoopNode}.
 *
 * <p>Pre-fix: {@code SeqFilterCallNode.filterLongLarge} was
 * {@code @TruffleBoundary} — opaque to PE; every iter paid an
 * interpreter→JIT boundary into the (separately compiled) predicate,
 * and the per-iter {@link MezzeRecord} + {@code Object[]} + Long-
 * boxing allocs couldn't be escape-analyzed away.
 *
 * <p>State carried via the per-call {@link VirtualFrame}:
 * <ul>
 *   <li>args: {@code [data, results, size, closureValues, closureLength]}</li>
 *   <li>{@code INDEX_SLOT} (long): next-to-read index</li>
 *   <li>{@code COUNT_SLOT} (long): number of kept elements so far —
 *       returned to the caller via {@link #execute}.</li>
 * </ul>
 *
 * <p>Re-entrancy: one instance per distinct predicate CallTarget,
 * managed by the owning {@link SeqFilterCallNode}.
 */
@NodeInfo(shortName = "seq.filter.long.loop")
public final class SeqFilterLongLoopNode extends Node {

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

    @Child
    private LoopNode loopNode;

    public SeqFilterLongLoopNode(CallTarget predicateTarget) {
        this.predicateTarget = predicateTarget;
        this.loopNode = Truffle.getRuntime().createLoopNode(new LongFilterRepeatingNode(predicateTarget));
    }

    public CallTarget getPredicateTarget() {
        return predicateTarget;
    }

    /**
     * Runs the loop, writing kept elements densely into {@code results}.
     * Returns the count of kept elements; caller is responsible for
     * trimming {@code results} to that count.
     */
    public int execute(long[] data, long[] results, int size,
                       Object[] closureValues, int closureLength) {
        VirtualFrame loopFrame = Truffle.getRuntime().createVirtualFrame(
            new Object[]{data, results, size, closureValues, closureLength},
            LOOP_FD);
        loopFrame.setLong(INDEX_SLOT, 0);
        loopFrame.setLong(COUNT_SLOT, 0);
        loopNode.execute(loopFrame);
        return (int) loopFrame.getLong(COUNT_SLOT);
    }

    static final class LongFilterRepeatingNode extends Node implements RepeatingNode {

        @Child
        private DirectCallNode callNode;

        LongFilterRepeatingNode(CallTarget predicateTarget) {
            this.callNode = DirectCallNode.create(predicateTarget);
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
            Object[] closureValues = (Object[]) args[3];
            int closureLength = (int) args[4];

            long elem = data[(int) index];
            if (testPredicate(elem, closureValues, closureLength)) {
                long count = frame.getLong(COUNT_SLOT);
                results[(int) count] = elem;
                frame.setLong(COUNT_SLOT, count + 1);
            }
            frame.setLong(INDEX_SLOT, index + 1);
            return true;
        }

        private boolean testPredicate(long element, Object[] closureValues, int closureLength) {
            MezzeRecord arg = MezzeRecord.wrapX(element);
            Object result;
            if (closureLength == 0) {
                result = callNode.call(new Object[]{arg});
            } else {
                Object[] callArgs = new Object[closureLength + 1];
                for (int i = 0; i < closureLength; i++) {
                    callArgs[i] = closureValues[i];
                }
                callArgs[closureLength] = arg;
                result = callNode.call(callArgs);
            }
            return result instanceof Boolean b && b;
        }
    }
}
