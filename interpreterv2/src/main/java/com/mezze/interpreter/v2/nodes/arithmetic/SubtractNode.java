package com.mezze.interpreter.v2.nodes.arithmetic;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Subtraction node with proper Truffle DSL specializations.
 */
@NodeChild("left")
@NodeChild("right")
@NodeInfo(shortName = "-")
public abstract class SubtractNode extends MezzeNode {

    /**
     * Fast path: Int - Int (unboxed longs).
     */
    @Specialization(rewriteOn = ArithmeticException.class)
    protected long subtractLongs(long left, long right) {
        return Math.subtractExact(left, right);
    }

    /**
     * Overflow fallback: Convert to double on overflow.
     */
    @Specialization(replaces = "subtractLongs")
    protected double subtractLongsOverflow(long left, long right) {
        return (double) left - (double) right;
    }

    /**
     * Fast path: Float - Float (unboxed doubles).
     */
    @Specialization
    protected double subtractDoubles(double left, double right) {
        return left - right;
    }

    /**
     * Fallback: Type error.
     */
    @Fallback
    protected Object typeError(Object left, Object right) {
        throw new MezzeArithmeticException(
            "Cannot subtract " + right.getClass().getSimpleName() +
            " from " + left.getClass().getSimpleName()
        );
    }
}
