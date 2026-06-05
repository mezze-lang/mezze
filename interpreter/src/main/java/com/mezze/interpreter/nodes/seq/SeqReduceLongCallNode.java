package com.mezze.interpreter.nodes.seq;

import com.mezze.datastructures.seq.LongSeqStorage;
import com.mezze.datastructures.seq.MezzeSeq;
import com.mezze.datastructures.seq.SeqStorage;
import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.values.MezzeVariant;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Specialized reduce-over-{@code long[]} for {@code seq::min} and
 * {@code seq::max}. Mirrors {@link SeqSumCallNode}: plain Java
 * {@code for} over {@link LongSeqStorage#getArray()}, storage-type
 * profiled (size-1 PIC on Long vs Object), no folder dispatch.
 *
 * <p>Returns {@code MezzeVariant.some(Long)} for non-empty Long-backed
 * sequences; {@code MezzeVariant.none()} for empty. Falls back to a
 * boxed loop for non-Long storage (which uses generic {@code compare})
 * — slow path, not the hot bench target.
 *
 * <p>Pre-wiring, {@code seq::min} / {@code seq::max} fell through the
 * generic native-dispatch path which tried {@code SeqMinLongNode.
 * getUncached()} / {@code SeqMaxLongNode.getUncached()} — same SIMD
 * dependency that crashed {@code seq::sum} under the standalone
 * launcher (jdk.incubator.vector not on the module path).
 */
@NodeInfo(shortName = "seq-reduce-long")
public final class SeqReduceLongCallNode extends MezzeNode {

    public enum Op {
        MIN, MAX
    }

    @Child
    private MezzeNode seqNode;

    @CompilationFinal
    private final Op op;

    @CompilationFinal
    private Class<?> seenStorageType;

    @CompilationFinal
    private boolean seenMultipleTypes;

    public SeqReduceLongCallNode(MezzeNode seqNode, Op op) {
        this.seqNode = seqNode;
        this.op = op;
    }

    public static SeqReduceLongCallNode createMin(MezzeNode seqNode) {
        return new SeqReduceLongCallNode(seqNode, Op.MIN);
    }

    public static SeqReduceLongCallNode createMax(MezzeNode seqNode) {
        return new SeqReduceLongCallNode(seqNode, Op.MAX);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        MezzeSeq<?> seq = (MezzeSeq<?>) seqNode.execute(frame);
        int size = seq.size();
        if (size == 0) {
            return MezzeVariant.none();
        }

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

        if (!seenMultipleTypes && seenStorageType == LongSeqStorage.class) {
            long result = reduceLong((LongSeqStorage) storage, size);
            return MezzeVariant.some(result);
        }
        return MezzeVariant.some(reduceGeneric(seq, size));
    }

    private long reduceLong(LongSeqStorage storage, int size) {
        long[] data = storage.getArray();
        long acc = data[0];
        if (op == Op.MIN) {
            for (int i = 1; i < size; i++) {
                long v = data[i];
                if (v < acc) acc = v;
            }
        } else {
            for (int i = 1; i < size; i++) {
                long v = data[i];
                if (v > acc) acc = v;
            }
        }
        return acc;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object reduceGeneric(MezzeSeq seq, int size) {
        Object acc = seq.get(0);
        if (op == Op.MIN) {
            for (int i = 1; i < size; i++) {
                Object v = seq.get(i);
                if (compareBoxed(v, acc) < 0) acc = v;
            }
        } else {
            for (int i = 1; i < size; i++) {
                Object v = seq.get(i);
                if (compareBoxed(v, acc) > 0) acc = v;
            }
        }
        return acc;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareBoxed(Object a, Object b) {
        if (a instanceof Comparable c) {
            return c.compareTo(b);
        }
        // Defensive fallback — should not be reached for well-typed sequences.
        return 0;
    }
}
