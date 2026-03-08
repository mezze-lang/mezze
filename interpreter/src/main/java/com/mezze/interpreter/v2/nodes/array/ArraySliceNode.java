package com.mezze.interpreter.v2.nodes.array;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.values.MezzeList;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Shared;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.profiles.InlinedBranchProfile;

import java.util.Arrays;

/**
 * Array slice node for extracting a sub-array.
 * Used for rest patterns like `[x, ...rest]` where we need to slice from index to end.
 *
 * Optimized for JIT compilation:
 * - Specialized for Object[] and MezzeList
 * - Uses branch profiles for polymorphic cases
 */
@NodeInfo(shortName = "array-slice")
@NodeChild(value = "arrayNode", type = MezzeNode.class)
@NodeChild(value = "startNode", type = MezzeNode.class)
public abstract class ArraySliceNode extends MezzeNode {

    /**
     * Create a slice node that slices from start index to end.
     */
    public static ArraySliceNode create(MezzeNode arrayNode, MezzeNode startNode) {
        return ArraySliceNodeGen.create(arrayNode, startNode);
    }

    @Specialization
    protected Object sliceObjectArray(Object[] array, long start,
            @Cached @Shared("emptyProfile") InlinedBranchProfile emptyProfile) {
        int startIdx = (int) start;
        if (startIdx >= array.length) {
            emptyProfile.enter(this);
            return new Object[0];
        }
        return Arrays.copyOfRange(array, startIdx, array.length);
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
