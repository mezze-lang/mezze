package com.mezze.interpreter.nodes.mutation;

import com.mezze.datastructures.seq.MezzeSeq;
import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.values.MutableSeq;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Convert immutable MezzeSeq to MutableSeq.
 *
 * This creates a mutable copy that can be sorted, reversed, etc. in place.
 * The conversion itself is @TruffleBoundary because it involves allocation
 * and copying, but subsequent mutation operations can be optimized.
 */
@NodeInfo(shortName = "seq-to-mut")
public final class SeqToMutNode extends MezzeNode {

    @Child private MezzeNode seqNode;

    public SeqToMutNode(MezzeNode seqNode) {
        this.seqNode = seqNode;
    }

    public static SeqToMutNode create(MezzeNode seqNode) {
        return new SeqToMutNode(seqNode);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object seq = seqNode.execute(frame);
        if (seq instanceof MezzeSeq mezzeSeq) {
            return convertToMut(mezzeSeq);
        }
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new RuntimeException("seq_to_mut expects Seq, got: " + seq.getClass());
    }

    @TruffleBoundary
    private static MutableSeq convertToMut(MezzeSeq seq) {
        return MutableSeq.fromImmutable(seq);
    }
}
