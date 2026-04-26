package com.mezze.interpreter.nodes.arithmetic;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.values.MezzeRecord;
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

    private static final String[] FRAC_FIELDS = new String[]{"numerator", "denominator"};

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
     * Negate fraction: -(a/b) = (-a)/b
     */
    @Specialization(guards = "isFraction(value)")
    protected static MezzeRecord negateFraction(MezzeRecord value) {
        long numerator = (Long) value.getField("numerator");
        long denominator = (Long) value.getField("denominator");
        return MezzeRecord.fromFields(FRAC_FIELDS, new Object[]{-numerator, denominator});
    }

    protected static boolean isFraction(MezzeRecord record) {
        return record.getFieldIndex("numerator") >= 0
            && record.getFieldIndex("denominator") >= 0
            && record.getFieldCount() == 2;
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
