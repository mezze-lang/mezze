package com.mezze.interpreter.v2.nodes.inline;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.values.MezzeRecord;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Direct negation node that takes the operand directly.
 *
 * This bypasses the {val} record allocation used by BuiltinNegNode,
 * enabling much faster arithmetic when the typechecker has resolved
 * concrete types.
 */
@NodeChild(value = "operand", type = MezzeNode.class)
@NodeInfo(shortName = "direct-neg")
public abstract class DirectNegateNode extends MezzeNode {

    @Specialization(rewriteOn = ArithmeticException.class)
    protected static long negateLong(long value) {
        return Math.negateExact(value);
    }

    @Specialization(replaces = "negateLong")
    protected static double negateLongOverflow(long value) {
        return -(double) value;
    }

    @Specialization
    protected static double negateDouble(double value) {
        return -value;
    }

    @Specialization(guards = "isFrac(value)")
    protected static MezzeRecord negateFraction(MezzeRecord value) {
        return FractionHelper.negateFraction(value);
    }

    protected static boolean isFrac(Object value) {
        return FractionHelper.isFraction(value);
    }

    @Fallback
    protected static Object typeError(Object value) {
        throw new RuntimeException(
            "Cannot negate " + (value == null ? "null" : value.getClass().getSimpleName())
        );
    }

    public static DirectNegateNode create(MezzeNode operand) {
        return DirectNegateNodeGen.create(operand);
    }
}
