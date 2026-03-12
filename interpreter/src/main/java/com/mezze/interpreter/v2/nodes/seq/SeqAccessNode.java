package com.mezze.interpreter.v2.nodes.seq;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.datastructures.seq.MezzeSeq;
import com.mezze.datastructures.list.MezzeList;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Shared;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import com.oracle.truffle.api.profiles.InlinedBranchProfile;

/**
 * Sequence/List element access node.
 *
 * Type-specialized for MezzeSeq (Vavr Vector) and MezzeList (Vavr List).
 */
@NodeChild("seqNode")
@NodeChild("indexNode")
@NodeInfo(shortName = "seq-get")
public abstract class SeqAccessNode extends MezzeNode {

    /**
     * MezzeSeq (Vavr Vector) access - O(log32 n) ≈ O(1)
     */
    @Specialization
    protected Object accessSeq(
            VirtualFrame frame,
            MezzeSeq seq,
            long index,
            @Cached @Shared("boundsError") InlinedBranchProfile boundsErrorProfile
    ) {
        int idx = (int) index;
        if (idx < 0 || idx >= seq.size()) {
            boundsErrorProfile.enter(this);
            throw new ArrayIndexOutOfBoundsException("Index " + index + " out of bounds for sequence of length " + seq.size());
        }
        return seq.get(idx);
    }

    /**
     * MezzeList (Vavr List) access - O(n)
     */
    @Specialization
    protected Object accessList(
            VirtualFrame frame,
            MezzeList list,
            long index,
            @Cached @Shared("boundsError") InlinedBranchProfile boundsErrorProfile
    ) {
        int idx = (int) index;
        if (idx < 0 || idx >= list.size()) {
            boundsErrorProfile.enter(this);
            throw new ArrayIndexOutOfBoundsException("Index " + index + " out of bounds for list of length " + list.size());
        }
        return list.get(idx);
    }

    /**
     * Generic path: Object array.
     */
    @Specialization
    protected Object accessObjectArray(
            VirtualFrame frame,
            Object[] array,
            long index,
            @Cached @Shared("boundsError") InlinedBranchProfile boundsErrorProfile
    ) {
        int idx = (int) index;
        if (idx < 0 || idx >= array.length) {
            boundsErrorProfile.enter(this);
            throw new ArrayIndexOutOfBoundsException("Index " + index + " out of bounds for array of length " + array.length);
        }
        return array[idx];
    }

    @Override
    public long executeLong(VirtualFrame frame) throws UnexpectedResultException {
        Object result = execute(frame);
        if (result instanceof Long l) {
            return l;
        }
        throw new UnexpectedResultException(result);
    }

    @Override
    public double executeDouble(VirtualFrame frame) throws UnexpectedResultException {
        Object result = execute(frame);
        if (result instanceof Double d) {
            return d;
        }
        if (result instanceof Long l) {
            return l.doubleValue();
        }
        throw new UnexpectedResultException(result);
    }

    /**
     * Create a seq access node.
     */
    public static SeqAccessNode create(MezzeNode seqNode, MezzeNode indexNode) {
        return SeqAccessNodeGen.create(seqNode, indexNode);
    }
}
