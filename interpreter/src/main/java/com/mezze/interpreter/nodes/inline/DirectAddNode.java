package com.mezze.interpreter.nodes.inline;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeSuspended;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.UnexpectedResultException;

/**
 * Direct addition node that takes left and right operands directly.
 *
 * This bypasses the {l, r} record allocation used by BuiltinAddNode,
 * enabling much faster arithmetic when the typechecker has resolved
 * concrete types.
 *
 * Use this when ResolvedAbility.typeArgs confirms the operand types.
 */
@NodeChild(value = "left", type = MezzeNode.class)
@NodeChild(value = "right", type = MezzeNode.class)
@NodeInfo(shortName = "direct-add")
public abstract class DirectAddNode extends MezzeNode {

    /** Typed execute methods so callers in tail-call hot loops can read
     *  the result directly without Long/Double boxing through execute(). */
    @Override
    public abstract long executeLong(VirtualFrame frame) throws UnexpectedResultException;

    @Override
    public abstract double executeDouble(VirtualFrame frame) throws UnexpectedResultException;

    /**
     * Fast path: Int + Int (unboxed longs).
     */
    @Specialization(rewriteOn = ArithmeticException.class)
    protected static long addLongs(long left, long right) {
        return Math.addExact(left, right);
    }

    /**
     * Overflow fallback.
     */
    @Specialization(replaces = "addLongs")
    protected static double addLongsOverflow(long left, long right) {
        return (double) left + (double) right;
    }

    /**
     * Fast path: Float + Float (unboxed doubles).
     * Also handles mixed long+double due to implicit widening.
     */
    @Specialization(replaces = "addLongsOverflow")
    protected static double addDoubles(double left, double right) {
        return left + right;
    }

    /**
     * String concatenation.
     */
    @Specialization
    protected static String addStrings(String left, String right) {
        return left + right;
    }

    /**
     * Fraction addition: (a/b) + (c/d) = (a*d + c*b) / (b*d)
     */
    @Specialization(guards = {"isFrac(left)", "isFrac(right)"})
    protected static MezzeRecord addFractions(MezzeRecord left, MezzeRecord right) {
        return FractionHelper.addFractions(left, right);
    }

    protected static boolean isFrac(Object value) {
        return FractionHelper.isFraction(value);
    }

    /**
     * MezzeSuspended propagation: if either operand is suspended, propagate it.
     * This handles the case where an unhandled effect returns MezzeSuspended
     * and the result flows into arithmetic.
     */
    @Specialization(guards = "isSuspended(left)")
    protected static MezzeSuspended addLeftSuspended(MezzeSuspended left, Object right) {
        return left;
    }

    @Specialization(guards = "isSuspended(right)")
    protected static MezzeSuspended addRightSuspended(Object left, MezzeSuspended right) {
        return right;
    }

    protected static boolean isSuspended(Object value) {
        return value instanceof MezzeSuspended;
    }

    /**
     * Fallback: Type error.
     */
    @Fallback
    protected static Object typeError(Object left, Object right) {
        throw new RuntimeException(
            "Cannot add " + (left == null ? "null" : left.getClass().getSimpleName()) +
            " and " + (right == null ? "null" : right.getClass().getSimpleName())
        );
    }

    public static DirectAddNode create(MezzeNode left, MezzeNode right) {
        return DirectAddNodeGen.create(left, right);
    }
}
