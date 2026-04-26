package com.mezze.interpreter.nodes.mutation;

import com.mezze.datastructures.seq.MezzeSeq;
import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.values.MutableSeq;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Convert MutableSeq back to immutable MezzeSeq.
 *
 * This "freezes" the mutable sequence into an immutable one.
 */
@NodeInfo(shortName = "seq-to-immut")
public final class SeqToImmutNode extends MezzeNode {

    @Child private MezzeNode seqNode;

    public SeqToImmutNode(MezzeNode seqNode) {
        this.seqNode = seqNode;
    }

    public static SeqToImmutNode create(MezzeNode seqNode) {
        return new SeqToImmutNode(seqNode);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object seq = seqNode.execute(frame);
        if (seq instanceof MutableSeq mutableSeq) {
            return convertToImmut(mutableSeq);
        }
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new RuntimeException("seq_to_immut expects MutableSeq, got: " + seq.getClass());
    }

    @TruffleBoundary
    private static MezzeSeq convertToImmut(MutableSeq seq) {
        return seq.toImmutable();
    }
}
