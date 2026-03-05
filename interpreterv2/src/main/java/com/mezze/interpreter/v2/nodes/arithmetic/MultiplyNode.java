package com.mezze.interpreter.v2.nodes.arithmetic;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Multiplication node with proper Truffle DSL specializations.
 * Nat is represented as primitive long for optimal JIT performance.
 */
@NodeChild("left")
@NodeChild("right")
@NodeInfo(shortName = "*")
public abstract class MultiplyNode extends MezzeNode {

    /**
     * Fast path: Int/Nat * Int/Nat (unboxed longs).
     */
    @Specialization(rewriteOn = ArithmeticException.class)
    protected long multiplyLongs(long left, long right) {
        return Math.multiplyExact(left, right);
    }

    /**
     * Overflow fallback: Convert to double on overflow.
     */
    @Specialization(replaces = "multiplyLongs")
    protected double multiplyLongsOverflow(long left, long right) {
        return (double) left * (double) right;
    }

    /**
     * Fast path: Float * Float (unboxed doubles).
     */
    @Specialization
    protected double multiplyDoubles(double left, double right) {
        return left * right;
    }

    /**
     * String repetition: "ab" * 3 = "ababab"
     */
    @Specialization
    protected String multiplyStringLong(String left, long right) {
        if (right <= 0) return "";
        if (right == 1) return left;
        return left.repeat((int) right);
    }

    /**
     * Fallback: Type error.
     */
    @Fallback
    protected Object typeError(Object left, Object right) {
        String leftDesc = left == null ? "null" : left.getClass().getSimpleName();
        String rightDesc = right == null ? "null" : right.getClass().getSimpleName();
        throw new MezzeArithmeticException(
            "Cannot multiply " + leftDesc + " and " + rightDesc
        );
    }
}
