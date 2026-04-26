package com.mezze.interpreter.nodes.mutation;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MutableSeq;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * In-place reverse for MutableSeq.
 */
@NodeInfo(shortName = "mut-seq-reverse")
public final class MutSeqReverseNode extends MezzeNode {

    @Child private MezzeNode seqNode;

    public MutSeqReverseNode(MezzeNode seqNode) {
        this.seqNode = seqNode;
    }

    public static MutSeqReverseNode create(MezzeNode seqNode) {
        return new MutSeqReverseNode(seqNode);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object seq = seqNode.execute(frame);
        if (seq instanceof MutableSeq mutableSeq) {
            mutableSeq.reverse();
            return MezzeRecord.empty();
        }
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new RuntimeException("reverse expects MutableSeq, got: " + seq.getClass());
    }
}
