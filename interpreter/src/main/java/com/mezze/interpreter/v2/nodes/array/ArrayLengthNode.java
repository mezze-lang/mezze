package com.mezze.interpreter.v2.nodes.array;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.values.MezzeSeq;
import com.mezze.interpreter.v2.values.MezzeList;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.UnexpectedResultException;

/**
 * Array/List length node - returns primitive long.
 *
 * Type-specialized for MezzeSeq (Vavr Vector) and MezzeList (Vavr List).
 */
@NodeChild("arrayNode")
@NodeInfo(shortName = "array-len")
public abstract class ArrayLengthNode extends MezzeNode {

    /**
     * MezzeSeq (Vavr Vector) length.
     */
    @Specialization
    protected long lengthArray(MezzeSeq array) {
        return array.size();
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
     * Create an array length node.
     */
    public static ArrayLengthNode create(MezzeNode arrayNode) {
        return ArrayLengthNodeGen.create(arrayNode);
    }
}
