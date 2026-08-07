package com.mezze.interpreter.nodes.seq;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.datastructures.seq.MezzeSeq;
import com.mezze.datastructures.list.MezzeList;
import com.oracle.truffle.api.bytecode.OperationProxy;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Shared;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import com.oracle.truffle.api.profiles.InlinedBranchProfile;

/**
 * Sequence/List element access node.
 *
 * Type-specialized for MezzeSeq (persistent vector — typed-storage stack
 * with Long/Double/Object/Boolean specializations and COW ownership) and
 * MezzeList (persistent cons list).
 *
 * Specializations are static so this node can be used both as an AST node
 * (via {@link #create}) AND as a BDSL {@code @OperationProxy} target on
 * {@code MezzeBytecodeRootNode} (BDSL requires static specializations
 * with {@code @Bind Node} for receiver access).
 */
@NodeChild("seqNode")
@NodeChild("indexNode")
@NodeInfo(shortName = "seq-get")
@OperationProxy.Proxyable(allowUncached = true)
public abstract class SeqAccessNode extends MezzeNode {

    /**
     * MezzeSeq (persistent vector) access - O(log32 n) ≈ O(1)
     */
    @Specialization
    public static Object accessSeq(
            VirtualFrame frame,
            MezzeSeq seq,
            long index,
            @Bind Node node,
            @Cached @Shared("boundsError") InlinedBranchProfile boundsErrorProfile
    ) {
        int idx = (int) index;
        if (idx < 0 || idx >= seq.size()) {
            boundsErrorProfile.enter(node);
            throw new ArrayIndexOutOfBoundsException("Index " + index + " out of bounds for sequence of length " + seq.size());
        }
        return seq.get(idx);
    }

    /**
     * MezzeList (persistent cons list) access - O(n)
     */
    @Specialization
    public static Object accessList(
            VirtualFrame frame,
            MezzeList list,
            long index,
            @Bind Node node,
            @Cached @Shared("boundsError") InlinedBranchProfile boundsErrorProfile
    ) {
        int idx = (int) index;
        if (idx < 0 || idx >= list.size()) {
            boundsErrorProfile.enter(node);
            throw new ArrayIndexOutOfBoundsException("Index " + index + " out of bounds for list of length " + list.size());
        }
        return list.get(idx);
    }

    /**
     * Generic path: Object array.
     */
    @Specialization
    public static Object accessObjectArray(
            VirtualFrame frame,
            Object[] array,
            long index,
            @Bind Node node,
            @Cached @Shared("boundsError") InlinedBranchProfile boundsErrorProfile
    ) {
        int idx = (int) index;
        if (idx < 0 || idx >= array.length) {
            boundsErrorProfile.enter(node);
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
