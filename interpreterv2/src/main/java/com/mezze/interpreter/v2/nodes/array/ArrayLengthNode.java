package com.mezze.interpreter.v2.nodes.array;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.values.MezzeIntArray;
import com.mezze.interpreter.v2.values.MezzeFloatArray;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.UnexpectedResultException;

/**
 * Array length node - returns primitive long, NO BOXING.
 *
 * Type-specialized for MezzeIntArray and MezzeFloatArray.
 */
@NodeChild("arrayNode")
@NodeInfo(shortName = "array-len")
public abstract class ArrayLengthNode extends MezzeNode {

    /**
     * MezzeIntArray length.
     */
    @Specialization
    protected long lengthIntArray(MezzeIntArray array) {
        return array.length();
    }

    /**
     * MezzeFloatArray length.
     */
    @Specialization
    protected long lengthFloatArray(MezzeFloatArray array) {
        return array.length();
    }

    /**
     * Object array length.
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
     * Create an array length node.
     */
    public static ArrayLengthNode create(MezzeNode arrayNode) {
        return ArrayLengthNodeGen.create(arrayNode);
    }
}
