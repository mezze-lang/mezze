package com.mezze.interpreter.nodes.inline;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.values.MezzeRecord;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.bytecode.OperationProxy;
import com.oracle.truffle.api.nodes.UnexpectedResultException;

/**
 * Direct greater-than-or-equal node that takes left and right operands directly.
 *
 * This bypasses the {l, r} record allocation used by BuiltinGeNode,
 * enabling much faster comparison when the typechecker has resolved
 * concrete types.
 */
@NodeChild(value = "left", type = MezzeNode.class)
@NodeChild(value = "right", type = MezzeNode.class)
@NodeInfo(shortName = "direct-ge")
@OperationProxy.Proxyable(allowUncached = true)
public abstract class DirectGreaterThanOrEqualNode extends MezzeNode {

    /** Typed executeBoolean so `if`-conditions don't pay a Boolean box
     *  per iteration in hot loops. */
    @Override
    public abstract boolean executeBoolean(VirtualFrame frame) throws UnexpectedResultException;

    @Specialization
    public static boolean geLongs(long left, long right) {
        return left >= right;
    }

    /**
     * Float comparison. Also handles mixed types due to implicit widening.
     */
    @Specialization(replaces = "geLongs")
    public static boolean geDoubles(double left, double right) {
        return left >= right;
    }

    @Specialization
    public static boolean geStrings(String left, String right) {
        return left.compareTo(right) >= 0;
    }

    @Specialization(guards = {"isFrac(left)", "isFrac(right)"})
    public static boolean geFractions(MezzeRecord left, MezzeRecord right) {
        return FractionHelper.compareFractions(left, right) >= 0;
    }

    public static boolean isFrac(Object value) {
        return FractionHelper.isFraction(value);
    }

    @Fallback
    @TruffleBoundary
    public static Object typeError(Object left, Object right) {
        throw new RuntimeException(
            "Cannot compare " + (left == null ? "null" : left.getClass().getSimpleName()) +
            " >= " + (right == null ? "null" : right.getClass().getSimpleName())
        );
    }

    public static DirectGreaterThanOrEqualNode create(MezzeNode left, MezzeNode right) {
        return DirectGreaterThanOrEqualNodeGen.create(left, right);
    }
}
