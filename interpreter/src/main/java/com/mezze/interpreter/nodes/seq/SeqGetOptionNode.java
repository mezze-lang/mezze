package com.mezze.interpreter.nodes.seq;

import com.mezze.datastructures.seq.MezzeSeq;
import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.nodes.builtin.collections.SeqNatives;
import com.mezze.interpreter.values.MezzeVariant;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * DS-Wire fast path for {@code seq::get {xs, i}} → Option element.
 *
 * Skips the NativeDispatch HashMap-handler indirection and per-call
 * MezzeRecord allocation. Calls {@link SeqNatives#get(MezzeSeq, long)}
 * directly with positional args; returns the same Option-wrapped result.
 *
 * Falls back to the slower path when the seq runtime type isn't
 * MezzeSeq (rare today; future: support Object[] / MezzeList variants).
 */
@NodeInfo(shortName = "seq.get.option")
public final class SeqGetOptionNode extends MezzeNode {

    @Child private MezzeNode seqNode;
    @Child private MezzeNode indexNode;

    public SeqGetOptionNode(MezzeNode seqNode, MezzeNode indexNode) {
        this.seqNode = seqNode;
        this.indexNode = indexNode;
    }

    public static SeqGetOptionNode create(MezzeNode seqNode, MezzeNode indexNode) {
        return new SeqGetOptionNode(seqNode, indexNode);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object seqVal = seqNode.execute(frame);
        Object idxVal = indexNode.execute(frame);
        if (!(seqVal instanceof MezzeSeq seq) || !(idxVal instanceof Long idx)) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("seq::get expects (Seq, Int), got " +
                (seqVal == null ? "null" : seqVal.getClass().getSimpleName()) + " and " +
                (idxVal == null ? "null" : idxVal.getClass().getSimpleName()));
        }
        return SeqNatives.get(seq, idx);
    }
}
