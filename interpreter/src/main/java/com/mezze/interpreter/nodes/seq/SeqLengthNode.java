package com.mezze.interpreter.nodes.seq;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.datastructures.seq.MezzeSeq;
import com.mezze.datastructures.list.MezzeList;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.UnexpectedResultException;

/**
 * Seq/List length node - returns primitive long.
 *
 * Type-specialized for MezzeSeq (Vavr Vector) and MezzeList (Vavr List).
 */
@NodeChild("seqNode")
@NodeInfo(shortName = "seq-len")
public abstract class SeqLengthNode extends MezzeNode {

    /**
     * MezzeSeq (Vavr Vector) length.
     */
    @Specialization
    protected long lengthSeq(MezzeSeq seq) {
        return seq.size();
    }

    /**
     * MezzeList (Vavr List) length.
     */
    @Specialization
    protected long lengthList(MezzeList list) {
        return list.size();
    }

    /**
     * Object array length (fallback).
     */
    @Specialization
    protected long lengthObjectArray(Object[] array) {
        return array.length;
    }

    @Override
    public long executeLong(VirtualFrame frame) throws UnexpectedResultException {
        Object result = execute(frame);
        if (result instanceof Long l) {
            return l;
        }
        throw new UnexpectedResultException(result);
    }

    /**
     * Create a seq length node.
     */
    public static SeqLengthNode create(MezzeNode seqNode) {
        return SeqLengthNodeGen.create(seqNode);
    }
}
