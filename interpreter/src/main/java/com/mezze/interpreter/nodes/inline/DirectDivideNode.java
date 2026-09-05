package com.mezze.interpreter.nodes.inline;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.values.MezzeRecord;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.bytecode.ForceQuickening;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.bytecode.OperationProxy;

/**
 * Direct division node that takes left and right operands directly.
 *
 * This bypasses the {l, r} record allocation used by BuiltinDivNode,
 * enabling much faster arithmetic when the typechecker has resolved
 * concrete types.
 */
@NodeChild(value = "left", type = MezzeNode.class)
@NodeChild(value = "right", type = MezzeNode.class)
@NodeInfo(shortName = "direct-div")
@OperationProxy.Proxyable(allowUncached = true)
public abstract class DirectDivideNode extends MezzeNode {

    @Specialization
    @ForceQuickening
    public static long divLongs(long left, long right) {
        if (right == 0) {
            throw new com.mezze.interpreter.values.MezzeGuestException("Division by zero");
        }
        return left / right;
    }

    /**
     * Float division. Also handles mixed types due to implicit widening.
     */
    @Specialization(replaces = "divLongs")
    public static double divDoubles(double left, double right) {
        return left / right;
    }

    @Specialization(guards = {"isFrac(left)", "isFrac(right)"})
    public static MezzeRecord divFractions(MezzeRecord left, MezzeRecord right) {
        return FractionHelper.divideFractions(left, right);
    }

    public static boolean isFrac(Object value) {
        return FractionHelper.isFraction(value);
    }

    @Fallback
    @TruffleBoundary
    public static Object typeError(Object left, Object right) {
        throw new com.mezze.interpreter.values.MezzeGuestException(
            "Cannot divide " + (left == null ? "null" : left.getClass().getSimpleName()) +
            " by " + (right == null ? "null" : right.getClass().getSimpleName())
        );
    }

    public static DirectDivideNode create(MezzeNode left, MezzeNode right) {
        return DirectDivideNodeGen.create(left, right);
    }
}
