package com.mezze.interpreter.v2.nodes.mutation;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.values.MezzeRecord;
import com.mezze.interpreter.v2.values.MutableSeq;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * In-place sort for MutableSeq.
 *
 * Sorts elements in place. The sort operation itself uses @TruffleBoundary
 * internally (in MutableSeq.sort()) because Arrays.sort cannot be JIT compiled,
 * but the node dispatch is still optimized.
 */
@NodeInfo(shortName = "mut-seq-sort")
public final class MutSeqSortNode extends MezzeNode {

    @Child private MezzeNode seqNode;

    public MutSeqSortNode(MezzeNode seqNode) {
        this.seqNode = seqNode;
    }

    public static MutSeqSortNode create(MezzeNode seqNode) {
        return new MutSeqSortNode(seqNode);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object seq = seqNode.execute(frame);
        if (seq instanceof MutableSeq mutableSeq) {
            mutableSeq.sort();
            return MezzeRecord.empty();
        }
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new RuntimeException("sort expects MutableSeq, got: " + seq.getClass());
    }
}
