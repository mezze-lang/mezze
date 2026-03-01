package com.mezze.interpreter.v2.nodes.arithmetic;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Unary negation node.
 *
 * Static methods are used for specializations to enable
 * better inlining by the JIT compiler.
 */
@NodeChild("operand")
@NodeInfo(shortName = "-")
public abstract class NegateNode extends MezzeNode {

    /**
     * Negate Int (unboxed long).
     */
    @Specialization(rewriteOn = ArithmeticException.class)
    protected static long negateLong(long value) {
        return Math.negateExact(value);
    }

    /**
     * Overflow fallback for Long.MIN_VALUE.
     */
    @Specialization(replaces = "negateLong")
    protected static double negateLongOverflow(long value) {
        return -(double) value;
    }

    /**
     * Negate Float (unboxed double).
     */
    @Specialization
    protected static double negateDouble(double value) {
        return -value;
    }

    /**
     * Fallback: Type error.
     */
    @Fallback
    protected static Object typeError(Object value) {
        throw new MezzeArithmeticException(
            "Cannot negate " + value.getClass().getSimpleName()
        );
    }
}
