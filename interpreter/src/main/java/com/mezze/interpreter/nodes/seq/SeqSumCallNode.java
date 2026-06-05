package com.mezze.interpreter.nodes.seq;

import com.mezze.datastructures.seq.LongSeqStorage;
import com.mezze.datastructures.seq.MezzeSeq;
import com.mezze.datastructures.seq.SeqStorage;
import com.mezze.interpreter.nodes.MezzeNode;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.UnexpectedResultException;

/**
 * Specialized {@code seq::sum} for Long-storage sequences — a plain
 * Java {@code for} loop over the backing {@code long[]}, no folder
 * dispatch, no record wrapper, no boxing.
 *
 * <p>The general {@code seq::fold} path goes through
 * {@link SeqFoldCallNode}, which dispatches a user lambda per element
 * — even when JIT inlines that, there's a record alloc, an Object[]
 * wrapper, an Object-typed acc, etc. For the common {@code sum}
 * pattern (acc = 0, f = {acc, x} -> acc + x), this node skips all of
 * that and gives the JIT a plain reducer the HotSpot loop optimizer
 * can vectorize directly.
 *
 * <p>Wired into TruffleCompiler so {@code seq::sum} on a Long-backed
 * sequence compiles to this node. Falls back to a slow Object loop
 * for non-Long storage.
 */
@NodeInfo(shortName = "seq-sum")
public final class SeqSumCallNode extends MezzeNode {

    @Child
    private MezzeNode seqNode;

    @CompilationFinal
    private Class<?> seenStorageType;

    @CompilationFinal
    private boolean seenMultipleTypes;

    public SeqSumCallNode(MezzeNode seqNode) {
        this.seqNode = seqNode;
    }

    public static SeqSumCallNode create(MezzeNode seqNode) {
        return new SeqSumCallNode(seqNode);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        MezzeSeq<?> seq = (MezzeSeq<?>) seqNode.execute(frame);
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
            return sumLong((LongSeqStorage) storage);
        }
        return sumGeneric(seq);
    }

    @Override
    public long executeLong(VirtualFrame frame) throws UnexpectedResultException {
        Object r = execute(frame);
        if (r instanceof Long l) return l;
        throw new UnexpectedResultException(r);
    }

    /**
     * Hot path: plain reduce over a {@code long[]}. PE / HotSpot vectorize
     * this when reached from a compiled caller; even uncompiled it's a
     * single-instruction-per-element tight loop, no allocation, no
     * indirection. Equivalent in shape to the Java for-loop baseline.
     */
    private static long sumLong(LongSeqStorage storage) {
        long[] data = storage.getArray();
        int size = storage.size();
        long total = 0;
        for (int i = 0; i < size; i++) {
            total += data[i];
        }
        return total;
    }

    /**
     * Cold path: non-Long storage. Boxed elements; relies on Long /
     * Integer instanceof checks per element. Not the bench target.
     */
    private static long sumGeneric(MezzeSeq<?> seq) {
        long total = 0;
        int size = seq.size();
        for (int i = 0; i < size; i++) {
            Object e = seq.get(i);
            if (e instanceof Long l) total += l;
            else if (e instanceof Integer n) total += n;
        }
        return total;
    }
}
