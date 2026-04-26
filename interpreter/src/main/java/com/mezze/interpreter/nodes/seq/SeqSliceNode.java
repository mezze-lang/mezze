package com.mezze.interpreter.nodes.seq;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.datastructures.list.MezzeList;
import com.mezze.datastructures.seq.MezzeSeq;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Shared;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.profiles.InlinedBranchProfile;

import java.util.Arrays;

/**
 * Seq slice node for extracting a sub-sequence.
 * Used for rest patterns like `[x, ...rest]` where we need to slice from index to end.
 *
 * Optimized for JIT compilation:
 * - Specialized for MezzeSeq, Object[] and MezzeList
 * - Uses branch profiles for polymorphic cases
 */
@NodeInfo(shortName = "seq-slice")
@NodeChild(value = "seqNode", type = MezzeNode.class)
@NodeChild(value = "startNode", type = MezzeNode.class)
public abstract class SeqSliceNode extends MezzeNode {

    /**
     * Create a slice node that slices from start index to end.
     */
    public static SeqSliceNode create(MezzeNode seqNode, MezzeNode startNode) {
        return SeqSliceNodeGen.create(seqNode, startNode);
    }

    @Specialization
    protected MezzeSeq sliceMezzeSeq(MezzeSeq seq, long start,
            @Cached @Shared("emptyProfile") InlinedBranchProfile emptyProfile) {
        int startIdx = (int) start;
        if (startIdx >= seq.size()) {
            emptyProfile.enter(this);
            return MezzeSeq.empty();
        }
        return seq.slice(startIdx, seq.size());
    }

    @Specialization
    protected MezzeSeq sliceObjectArray(Object[] array, long start,
            @Cached @Shared("emptyProfile") InlinedBranchProfile emptyProfile) {
        int startIdx = (int) start;
        if (startIdx >= array.length) {
            emptyProfile.enter(this);
            return MezzeSeq.empty();
        }
        return MezzeSeq.fromArray(Arrays.copyOfRange(array, startIdx, array.length));
    }

    @Specialization
    protected Object sliceMezzeList(MezzeList list, long start,
            @Cached @Shared("emptyProfile") InlinedBranchProfile emptyProfile) {
        int startIdx = (int) start;
        if (startIdx >= list.size()) {
            emptyProfile.enter(this);
            return MezzeList.empty();
        }
        return list.slice(startIdx, list.size());
    }

    @Specialization(guards = "isIntArray(array)")
    protected long[] sliceIntArray(Object array, long start,
            @Cached @Shared("emptyProfile") InlinedBranchProfile emptyProfile) {
        long[] arr = (long[]) array;
        int startIdx = (int) start;
        if (startIdx >= arr.length) {
            emptyProfile.enter(this);
            return new long[0];
        }
        return Arrays.copyOfRange(arr, startIdx, arr.length);
    }

    @Specialization(guards = "isDoubleArray(array)")
    protected double[] sliceDoubleArray(Object array, long start,
            @Cached @Shared("emptyProfile") InlinedBranchProfile emptyProfile) {
        double[] arr = (double[]) array;
        int startIdx = (int) start;
        if (startIdx >= arr.length) {
            emptyProfile.enter(this);
            return new double[0];
        }
        return Arrays.copyOfRange(arr, startIdx, arr.length);
    }

    protected static boolean isIntArray(Object obj) {
        return obj instanceof long[];
    }

    protected static boolean isDoubleArray(Object obj) {
        return obj instanceof double[];
    }
}
