package com.mezze.interpreter.nodes.seq;

import com.mezze.datastructures.seq.LongSeqStorage;
import com.mezze.datastructures.seq.MezzeSeq;
import com.mezze.datastructures.seq.ObjectSeqStorage;
import com.mezze.datastructures.seq.SeqStorage;
import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.values.MezzeFunction;
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
import com.oracle.truffle.api.nodes.UnexpectedResultException;

/**
 * Specialized {@code seq::all} — returns {@code true} if every element
 * matches the predicate. Short-circuits on the first failure.
 *
 * <p>Mirrors {@link SeqAnyCallNode} but with inverted semantics: the
 * FAILED_SLOT stores whether we've seen a !p(x); we return that negated.
 */
@NodeInfo(shortName = "seq-all")
public final class SeqAllCallNode extends MezzeNode {

    @Child
    private MezzeNode seqNode;

    @Child
    private MezzeNode predicateNode;

    @Child
    private LongAllLoopNode longLoopNode;

    /**
     * Body-adopted fast path for the canonical 1-param + 0-closure
     * predicate shape over long storage.
     */
    @Child
    private SeqAllLongInlinedLoopNode longInlinedLoopNode;

    @Child
    private ObjectAllLoopNode objectLoopNode;

    @CompilationFinal
    private Class<?> seenStorageType;

    @CompilationFinal
    private boolean seenMultipleTypes;

    public SeqAllCallNode(MezzeNode seqNode, MezzeNode predicateNode) {
        this.seqNode = seqNode;
        this.predicateNode = predicateNode;
    }

    public static SeqAllCallNode create(MezzeNode seqNode, MezzeNode predicateNode) {
        return new SeqAllCallNode(seqNode, predicateNode);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        MezzeSeq<?> seq = (MezzeSeq<?>) seqNode.execute(frame);
        MezzeFunction predicate = (MezzeFunction) predicateNode.execute(frame);
        Object[] closureValues = predicate.getClosureValues();
        int closureLength = closureValues.length;
        CallTarget target = predicate.getCallTarget();

        SeqStorage storage = seq.getStorage();
        Class<?> storageType = storage.getClass();

        if (!seenMultipleTypes) {
            if (seenStorageType == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                seenStorageType = storageType;
            } else if (seenStorageType != storageType) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                seenMultipleTypes = true;
            }
        }

        int size = seq.size();
        if (size == 0) return Boolean.TRUE; // vacuous truth

        if (!seenMultipleTypes) {
            if (seenStorageType == LongSeqStorage.class) {
                // FAST PATH: body adoption when predicate has no closures
                // and matches the 1-param canonical shape.
                if (closureLength == 0) {
                    SeqAllLongInlinedLoopNode inlined = longInlinedLoopNode;
                    if (inlined == null || inlined.getPredicateTarget() != target) {
                        CompilerDirectives.transferToInterpreterAndInvalidate();
                        if (SeqAllLongInlinedLoopNode.canHandle(target)) {
                            com.mezze.interpreter.nodes.function.MezzeFunctionRootNode mrn =
                                (com.mezze.interpreter.nodes.function.MezzeFunctionRootNode)
                                    ((com.oracle.truffle.api.RootCallTarget) target).getRootNode();
                            inlined = new SeqAllLongInlinedLoopNode(target, mrn);
                            longInlinedLoopNode = insert(inlined);
                        } else {
                            inlined = null;
                        }
                    }
                    if (inlined != null) {
                        return inlined.execute(((LongSeqStorage) storage).getArray(), size);
                    }
                }
                // FALLBACK: per-predicate LoopNode + DirectCallNode.
                LongAllLoopNode loop = longLoopNode;
                if (loop == null || loop.getPredicateTarget() != target) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    loop = new LongAllLoopNode(target);
                    longLoopNode = insert(loop);
                }
                return loop.execute(((LongSeqStorage) storage).getArray(), size,
                                    closureValues, closureLength);
            }
            if (seenStorageType == ObjectSeqStorage.class) {
                ObjectAllLoopNode loop = objectLoopNode;
                if (loop == null || loop.getPredicateTarget() != target) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    loop = new ObjectAllLoopNode(target);
                    objectLoopNode = insert(loop);
                }
                return loop.execute(((ObjectSeqStorage) storage).getArray(), size,
                                    closureValues, closureLength);
            }
        }
        return allGeneric(seq, size, predicate);
    }

    @Override
    public boolean executeBoolean(VirtualFrame frame) throws UnexpectedResultException {
        Object r = execute(frame);
        if (r instanceof Boolean b) return b;
        throw new UnexpectedResultException(r);
    }

    @CompilerDirectives.TruffleBoundary
    private boolean allGeneric(MezzeSeq<?> seq, int size, MezzeFunction predicate) {
        Object[] closureValues = predicate.getClosureValues();
        int closureLength = closureValues.length;
        CallTarget target = predicate.getCallTarget();
        com.oracle.truffle.api.nodes.IndirectCallNode indirect =
            com.oracle.truffle.api.nodes.IndirectCallNode.getUncached();
        for (int i = 0; i < size; i++) {
            Object elem = seq.get(i);
            MezzeRecord arg = MezzeRecord.wrapX(elem);
            Object[] callArgs;
            if (closureLength == 0) {
                callArgs = new Object[]{arg};
            } else {
                callArgs = new Object[closureLength + 1];
                for (int k = 0; k < closureLength; k++) callArgs[k] = closureValues[k];
                callArgs[closureLength] = arg;
            }
            Object r = indirect.call(target, callArgs);
            if (!(r instanceof Boolean b) || !b) return false;
        }
        return true;
    }

    // ============================================================
    // Long-storage per-predicate LoopNode
    // ============================================================
    static final class LongAllLoopNode extends Node {
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

        @Child
        private LoopNode loopNode;

        LongAllLoopNode(CallTarget predicateTarget) {
            this.predicateTarget = predicateTarget;
            this.loopNode = Truffle.getRuntime().createLoopNode(new LongAllRepeating(predicateTarget));
        }

        CallTarget getPredicateTarget() { return predicateTarget; }

        Object execute(long[] data, int size, Object[] closureValues, int closureLength) {
            VirtualFrame loopFrame = Truffle.getRuntime().createVirtualFrame(
                new Object[]{data, size, closureValues, closureLength}, LOOP_FD);
            loopFrame.setLong(INDEX_SLOT, 0);
            loopFrame.setBoolean(FAILED_SLOT, false);
            loopNode.execute(loopFrame);
            return loopFrame.getBoolean(FAILED_SLOT) ? Boolean.FALSE : Boolean.TRUE;
        }

        static final class LongAllRepeating extends Node implements RepeatingNode {
            @Child private DirectCallNode callNode;
            LongAllRepeating(CallTarget target) { this.callNode = DirectCallNode.create(target); }

            @Override
            public boolean executeRepeating(VirtualFrame frame) {
                long index = frame.getLong(INDEX_SLOT);
                Object[] args = frame.getArguments();
                int size = (int) args[1];
                if (index >= size) return false;
                long[] data = (long[]) args[0];
                Object[] closureValues = (Object[]) args[2];
                int closureLength = (int) args[3];
                if (!callPredicate(data[(int) index], closureValues, closureLength)) {
                    frame.setBoolean(FAILED_SLOT, true);
                    return false;
                }
                frame.setLong(INDEX_SLOT, index + 1);
                return true;
            }

            private boolean callPredicate(long element, Object[] closureValues, int closureLength) {
                MezzeRecord arg = MezzeRecord.wrapX(element);
                Object result;
                if (closureLength == 0) {
                    result = callNode.call(new Object[]{arg});
                } else {
                    Object[] callArgs = new Object[closureLength + 1];
                    for (int i = 0; i < closureLength; i++) callArgs[i] = closureValues[i];
                    callArgs[closureLength] = arg;
                    result = callNode.call(callArgs);
                }
                return result instanceof Boolean b && b;
            }
        }
    }

    // ============================================================
    // Object-storage per-predicate LoopNode
    // ============================================================
    static final class ObjectAllLoopNode extends Node {
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

        @Child
        private LoopNode loopNode;

        ObjectAllLoopNode(CallTarget predicateTarget) {
            this.predicateTarget = predicateTarget;
            this.loopNode = Truffle.getRuntime().createLoopNode(new ObjectAllRepeating(predicateTarget));
        }

        CallTarget getPredicateTarget() { return predicateTarget; }

        Object execute(Object[] data, int size, Object[] closureValues, int closureLength) {
            VirtualFrame loopFrame = Truffle.getRuntime().createVirtualFrame(
                new Object[]{data, size, closureValues, closureLength}, LOOP_FD);
            loopFrame.setLong(INDEX_SLOT, 0);
            loopFrame.setBoolean(FAILED_SLOT, false);
            loopNode.execute(loopFrame);
            return loopFrame.getBoolean(FAILED_SLOT) ? Boolean.FALSE : Boolean.TRUE;
        }

        static final class ObjectAllRepeating extends Node implements RepeatingNode {
            @Child private DirectCallNode callNode;
            ObjectAllRepeating(CallTarget target) { this.callNode = DirectCallNode.create(target); }

            @Override
            public boolean executeRepeating(VirtualFrame frame) {
                long index = frame.getLong(INDEX_SLOT);
                Object[] args = frame.getArguments();
                int size = (int) args[1];
                if (index >= size) return false;
                Object[] data = (Object[]) args[0];
                Object[] closureValues = (Object[]) args[2];
                int closureLength = (int) args[3];
                if (!callPredicate(data[(int) index], closureValues, closureLength)) {
                    frame.setBoolean(FAILED_SLOT, true);
                    return false;
                }
                frame.setLong(INDEX_SLOT, index + 1);
                return true;
            }

            private boolean callPredicate(Object element, Object[] closureValues, int closureLength) {
                MezzeRecord arg = MezzeRecord.wrapX(element);
                Object result;
                if (closureLength == 0) {
                    result = callNode.call(new Object[]{arg});
                } else {
                    Object[] callArgs = new Object[closureLength + 1];
                    for (int i = 0; i < closureLength; i++) callArgs[i] = closureValues[i];
                    callArgs[closureLength] = arg;
                    result = callNode.call(callArgs);
                }
                return result instanceof Boolean b && b;
            }
        }
    }
}
