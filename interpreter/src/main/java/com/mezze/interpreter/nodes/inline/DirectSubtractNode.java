package com.mezze.interpreter.nodes.inline;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.values.MezzeRecord;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.bytecode.OperationProxy;
import com.oracle.truffle.api.nodes.UnexpectedResultException;

/**
 * Direct subtraction node that takes left and right operands directly.
 *
 * This bypasses the {l, r} record allocation used by BuiltinSubNode,
 * enabling much faster arithmetic when the typechecker has resolved
 * concrete types.
 */
@NodeChild(value = "left", type = MezzeNode.class)
@NodeChild(value = "right", type = MezzeNode.class)
@NodeInfo(shortName = "direct-sub")
@OperationProxy.Proxyable(allowUncached = true)
public abstract class DirectSubtractNode extends MezzeNode {

    /** Typed execute methods so callers in tail-call hot loops can read
     *  the result without boxing through execute(). */
    @Override
    public abstract long executeLong(VirtualFrame frame) throws UnexpectedResultException;

    @Override
    public abstract double executeDouble(VirtualFrame frame) throws UnexpectedResultException;

    @Specialization(rewriteOn = ArithmeticException.class)
    public static long subLongs(long left, long right) {
        return Math.subtractExact(left, right);
    }

    @Specialization(replaces = "subLongs")
    public static double subLongsOverflow(long left, long right) {
        return (double) left - (double) right;
    }

    /**
     * Float subtraction. Also handles mixed types due to implicit widening.
     */
    @Specialization(replaces = "subLongsOverflow")
    public static double subDoubles(double left, double right) {
        return left - right;
    }

    @Specialization(guards = {"isFrac(left)", "isFrac(right)"})
    public static MezzeRecord subFractions(MezzeRecord left, MezzeRecord right) {
        return FractionHelper.subtractFractions(left, right);
    }

    public static boolean isFrac(Object value) {
        return FractionHelper.isFraction(value);
    }

    @Fallback
    public static Object typeError(Object left, Object right) {
        throw new RuntimeException(
            "Cannot subtract " + (left == null ? "null" : left.getClass().getSimpleName()) +
            " and " + (right == null ? "null" : right.getClass().getSimpleName())
        );
    }

    public static DirectSubtractNode create(MezzeNode left, MezzeNode right) {
        return DirectSubtractNodeGen.create(left, right);
    }
}
