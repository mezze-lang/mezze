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
 * Specialized {@code seq::count} — counts elements matching a predicate.
 *
 * <p>Pre-fix the user would hit {@code SeqNatives.count}, which is
 * {@code @TruffleBoundary} and dispatches via {@code IndirectCallNode}
 * (no inlining). For N=1M with a {@code (x -> p x)} predicate this
 * was the same dispatch-tax as {@code seq::fold}.
 *
 * <p>This node mirrors the {@link SeqFilterLongLoopNode} pattern:
 * per-predicate PIC, a Truffle {@link LoopNode} with the predicate's
 * {@link DirectCallNode} as {@code @Child} of the repeating node, so
 * Truffle's inliner inlines the predicate into the OSR'd body. No
 * filter output to materialize — only a {@code long} count slot.
 *
 * <p>Wired into TruffleCompiler so {@code seq::count {xs, p}} lowers
 * directly to this node.
 */
@NodeInfo(shortName = "seq-count")
public final class SeqCountCallNode extends MezzeNode {

    @Child
    private MezzeNode seqNode;

    @Child
    private MezzeNode predicateNode;

    @Child
    private LongCountLoopNode longLoopNode;

    /**
     * Body-adopted (real AST inlining) fast path for the canonical
     * shape: 1-param predicate + 0 closures over long storage. The
     * predicate's body AST is cloned and runs in-place per iter — no
     * DirectCallNode boundary, no MezzeRecord alloc, no ABI dispatch.
     * Selected over {@link #longLoopNode} when
     * {@link SeqCountLongInlinedLoopNode#canHandle} is true.
     */
    @Child
    private SeqCountLongInlinedLoopNode longInlinedLoopNode;

    @Child
    private ObjectCountLoopNode objectLoopNode;

    @CompilationFinal
    private Class<?> seenStorageType;

    @CompilationFinal
    private boolean seenMultipleTypes;

    public SeqCountCallNode(MezzeNode seqNode, MezzeNode predicateNode) {
        this.seqNode = seqNode;
        this.predicateNode = predicateNode;
    }

    public static SeqCountCallNode create(MezzeNode seqNode, MezzeNode predicateNode) {
        return new SeqCountCallNode(seqNode, predicateNode);
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
        if (size == 0) return 0L;

        if (!seenMultipleTypes) {
            if (seenStorageType == LongSeqStorage.class) {
                // FAST PATH: body adoption when predicate has no closures
                // and matches the 1-param canonical shape.
                if (closureLength == 0) {
                    SeqCountLongInlinedLoopNode inlined = longInlinedLoopNode;
                    if (inlined == null || inlined.getPredicateTarget() != target) {
                        CompilerDirectives.transferToInterpreterAndInvalidate();
                        if (SeqCountLongInlinedLoopNode.canHandle(target)) {
                            com.mezze.interpreter.nodes.function.MezzeFunctionRootNode mrn =
                                (com.mezze.interpreter.nodes.function.MezzeFunctionRootNode)
                                    ((com.oracle.truffle.api.RootCallTarget) target).getRootNode();
                            inlined = new SeqCountLongInlinedLoopNode(target, mrn);
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
                LongCountLoopNode loop = longLoopNode;
                if (loop == null || loop.getPredicateTarget() != target) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    loop = new LongCountLoopNode(target);
                    longLoopNode = insert(loop);
                }
                return loop.execute(((LongSeqStorage) storage).getArray(), size,
                                    closureValues, closureLength);
            }
            if (seenStorageType == ObjectSeqStorage.class) {
                ObjectCountLoopNode loop = objectLoopNode;
                if (loop == null || loop.getPredicateTarget() != target) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    loop = new ObjectCountLoopNode(target);
                    objectLoopNode = insert(loop);
                }
                return loop.execute(((ObjectSeqStorage) storage).getArray(), size,
                                    closureValues, closureLength);
            }
        }
        return countGeneric(seq, size, predicate);
    }

    @Override
    public long executeLong(VirtualFrame frame) throws UnexpectedResultException {
        Object r = execute(frame);
        if (r instanceof Long l) return l;
        throw new UnexpectedResultException(r);
    }

    /** Generic cold path for megamorphic / non-array storage. */
    @CompilerDirectives.TruffleBoundary
    private long countGeneric(MezzeSeq<?> seq, int size, MezzeFunction predicate) {
        Object[] closureValues = predicate.getClosureValues();
        int closureLength = closureValues.length;
        CallTarget target = predicate.getCallTarget();
        com.oracle.truffle.api.nodes.IndirectCallNode indirect =
            com.oracle.truffle.api.nodes.IndirectCallNode.getUncached();
        long count = 0;
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
            if (r instanceof Boolean b && b) count++;
        }
        return count;
    }

    // ============================================================
    // Long-storage per-predicate LoopNode
    // ============================================================
    static final class LongCountLoopNode extends Node {
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

        LongCountLoopNode(CallTarget predicateTarget) {
            this.predicateTarget = predicateTarget;
            this.loopNode = Truffle.getRuntime().createLoopNode(new LongCountRepeating(predicateTarget));
        }

        CallTarget getPredicateTarget() { return predicateTarget; }

        long execute(long[] data, int size, Object[] closureValues, int closureLength) {
            VirtualFrame loopFrame = Truffle.getRuntime().createVirtualFrame(
                new Object[]{data, size, closureValues, closureLength}, LOOP_FD);
            loopFrame.setLong(INDEX_SLOT, 0);
            loopFrame.setLong(COUNT_SLOT, 0);
            loopNode.execute(loopFrame);
            return loopFrame.getLong(COUNT_SLOT);
        }

        static final class LongCountRepeating extends Node implements RepeatingNode {
            @Child private DirectCallNode callNode;
            LongCountRepeating(CallTarget target) { this.callNode = DirectCallNode.create(target); }

            @Override
            public boolean executeRepeating(VirtualFrame frame) {
                long index = frame.getLong(INDEX_SLOT);
                Object[] args = frame.getArguments();
                int size = (int) args[1];
                if (index >= size) return false;
                long[] data = (long[]) args[0];
                Object[] closureValues = (Object[]) args[2];
                int closureLength = (int) args[3];
                if (callPredicate(data[(int) index], closureValues, closureLength)) {
                    frame.setLong(COUNT_SLOT, frame.getLong(COUNT_SLOT) + 1);
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
    // Object-storage per-predicate LoopNode (Seq<Record>, Seq<Str>, etc.)
    // ============================================================
    static final class ObjectCountLoopNode extends Node {
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

        ObjectCountLoopNode(CallTarget predicateTarget) {
            this.predicateTarget = predicateTarget;
            this.loopNode = Truffle.getRuntime().createLoopNode(new ObjectCountRepeating(predicateTarget));
        }

        CallTarget getPredicateTarget() { return predicateTarget; }

        long execute(Object[] data, int size, Object[] closureValues, int closureLength) {
            VirtualFrame loopFrame = Truffle.getRuntime().createVirtualFrame(
                new Object[]{data, size, closureValues, closureLength}, LOOP_FD);
            loopFrame.setLong(INDEX_SLOT, 0);
            loopFrame.setLong(COUNT_SLOT, 0);
            loopNode.execute(loopFrame);
            return loopFrame.getLong(COUNT_SLOT);
        }

        static final class ObjectCountRepeating extends Node implements RepeatingNode {
            @Child private DirectCallNode callNode;
            ObjectCountRepeating(CallTarget target) { this.callNode = DirectCallNode.create(target); }

            @Override
            public boolean executeRepeating(VirtualFrame frame) {
                long index = frame.getLong(INDEX_SLOT);
                Object[] args = frame.getArguments();
                int size = (int) args[1];
                if (index >= size) return false;
                Object[] data = (Object[]) args[0];
                Object[] closureValues = (Object[]) args[2];
                int closureLength = (int) args[3];
                if (callPredicate(data[(int) index], closureValues, closureLength)) {
                    frame.setLong(COUNT_SLOT, frame.getLong(COUNT_SLOT) + 1);
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
