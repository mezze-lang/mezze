package com.mezze.interpreter.nodes.inline;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeSuspend;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.bytecode.ForceQuickening;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.bytecode.OperationProxy;
import com.oracle.truffle.api.nodes.UnexpectedResultException;

/**
 * Direct multiplication node that takes left and right operands directly.
 *
 * This bypasses the {l, r} record allocation used by BuiltinMulNode,
 * enabling much faster arithmetic when the typechecker has resolved
 * concrete types.
 */
@NodeChild(value = "left", type = MezzeNode.class)
@NodeChild(value = "right", type = MezzeNode.class)
@NodeInfo(shortName = "direct-mul")
@OperationProxy.Proxyable(allowUncached = true)
public abstract class DirectMultiplyNode extends MezzeNode {

    /** Typed execute methods so callers in tail-call hot loops can read
     *  the result without boxing through execute(). */
    @Override
    public abstract long executeLong(VirtualFrame frame) throws UnexpectedResultException;

    @Override
    public abstract double executeDouble(VirtualFrame frame) throws UnexpectedResultException;

    @Specialization(rewriteOn = ArithmeticException.class)
    @ForceQuickening
    public static long mulLongs(long left, long right) {
        return Math.multiplyExact(left, right);
    }

    @Specialization(replaces = "mulLongs")
    public static double mulLongsOverflow(long left, long right) {
        return (double) left * (double) right;
    }

    /**
     * Float multiplication. Also handles mixed types due to implicit widening.
     */
    @Specialization(replaces = "mulLongsOverflow")
    public static double mulDoubles(double left, double right) {
        return left * right;
    }

    @Specialization(guards = {"isFrac(left)", "isFrac(right)"})
    public static MezzeRecord mulFractions(MezzeRecord left, MezzeRecord right) {
        return FractionHelper.multiplyFractions(left, right);
    }

    public static boolean isFrac(Object value) {
        return FractionHelper.isFraction(value);
    }

    /**
     * MezzeSuspend propagation: if either operand is suspended, propagate it.
     */
    @Specialization(guards = "isSuspended(left)")
    public static MezzeSuspend mulLeftSuspended(MezzeSuspend left, Object right) {
        return left;
    }

    @Specialization(guards = "isSuspended(right)")
    public static MezzeSuspend mulRightSuspended(Object left, MezzeSuspend right) {
        return right;
    }

    public static boolean isSuspended(Object value) {
        return value instanceof MezzeSuspend;
    }

    @Fallback
    @TruffleBoundary
    public static Object typeError(Object left, Object right) {
        throw new RuntimeException(
            "Cannot multiply " + (left == null ? "null" : left.getClass().getSimpleName()) +
            " and " + (right == null ? "null" : right.getClass().getSimpleName())
        );
    }

    public static DirectMultiplyNode create(MezzeNode left, MezzeNode right) {
        return DirectMultiplyNodeGen.create(left, right);
    }
}
