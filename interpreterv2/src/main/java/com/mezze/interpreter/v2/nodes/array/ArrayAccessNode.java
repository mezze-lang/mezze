package com.mezze.interpreter.v2.nodes.array;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.values.MezzeIntArray;
import com.mezze.interpreter.v2.values.MezzeFloatArray;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Shared;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import com.oracle.truffle.api.profiles.InlinedBranchProfile;

/**
 * Array element access node - AGGRESSIVELY OPTIMIZED.
 *
 * Key optimizations:
 * 1. Type-specialized paths for MezzeIntArray and MezzeFloatArray (NO BOXING)
 * 2. InlinedBranchProfile for bounds check failure path
 * 3. Direct primitive return via executeLong/executeDouble
 *
 * After JIT: Direct array load, equivalent to Java array[index].
 */
@NodeChild("arrayNode")
@NodeChild("indexNode")
@NodeInfo(shortName = "array-get")
public abstract class ArrayAccessNode extends MezzeNode {

    /**
     * FASTEST PATH: MezzeIntArray[long] - returns primitive long, NO BOXING.
     */
    @Specialization
    protected long accessIntArray(
            VirtualFrame frame,
            MezzeIntArray array,
            long index,
            @Cached @Shared("boundsError") InlinedBranchProfile boundsErrorProfile
    ) {
        int idx = (int) index;
        if (idx < 0 || idx >= array.length()) {
            boundsErrorProfile.enter(this);
            throw new ArrayIndexOutOfBoundsException("Index " + index + " out of bounds for array of length " + array.length());
        }
        return array.getLong(idx);
    }

    /**
     * FAST PATH: MezzeFloatArray[long] - returns primitive double, NO BOXING.
     */
    @Specialization
    protected double accessFloatArray(
            VirtualFrame frame,
            MezzeFloatArray array,
            long index,
            @Cached @Shared("boundsError") InlinedBranchProfile boundsErrorProfile
    ) {
        int idx = (int) index;
        if (idx < 0 || idx >= array.length()) {
            boundsErrorProfile.enter(this);
            throw new ArrayIndexOutOfBoundsException("Index " + index + " out of bounds for array of length " + array.length());
        }
        return array.getDouble(idx);
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
     * Create an array access node.
     */
    public static ArrayAccessNode create(MezzeNode arrayNode, MezzeNode indexNode) {
        return ArrayAccessNodeGen.create(arrayNode, indexNode);
    }
}
