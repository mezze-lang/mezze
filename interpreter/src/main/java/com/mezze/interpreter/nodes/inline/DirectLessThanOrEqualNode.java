package com.mezze.interpreter.nodes.inline;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.values.MezzeRecord;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.bytecode.OperationProxy;

/**
 * Direct less-than-or-equal node that takes left and right operands directly.
 *
 * This bypasses the {l, r} record allocation used by BuiltinLeNode,
 * enabling much faster comparison when the typechecker has resolved
 * concrete types.
 */
@NodeChild(value = "left", type = MezzeNode.class)
@NodeChild(value = "right", type = MezzeNode.class)
@NodeInfo(shortName = "direct-le")
@OperationProxy.Proxyable(allowUncached = true)
public abstract class DirectLessThanOrEqualNode extends MezzeNode {

    @Specialization
    public static boolean leLongs(long left, long right) {
        return left <= right;
    }

    /**
     * Float comparison. Also handles mixed types due to implicit widening.
     */
    @Specialization(replaces = "leLongs")
    public static boolean leDoubles(double left, double right) {
        return left <= right;
    }

    @Specialization
    public static boolean leStrings(String left, String right) {
        return left.compareTo(right) <= 0;
    }

    @Specialization(guards = {"isFrac(left)", "isFrac(right)"})
    public static boolean leFractions(MezzeRecord left, MezzeRecord right) {
        return FractionHelper.compareFractions(left, right) <= 0;
    }

    public static boolean isFrac(Object value) {
        return FractionHelper.isFraction(value);
    }

    @Fallback
    @TruffleBoundary
    public static Object typeError(Object left, Object right) {
        throw new RuntimeException(
            "Cannot compare " + (left == null ? "null" : left.getClass().getSimpleName()) +
            " <= " + (right == null ? "null" : right.getClass().getSimpleName())
        );
    }

    public static DirectLessThanOrEqualNode create(MezzeNode left, MezzeNode right) {
        return DirectLessThanOrEqualNodeGen.create(left, right);
    }
}
