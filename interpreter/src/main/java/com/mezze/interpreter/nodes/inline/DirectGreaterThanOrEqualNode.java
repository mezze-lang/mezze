package com.mezze.interpreter.nodes.inline;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.values.MezzeRecord;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;

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
public abstract class DirectGreaterThanOrEqualNode extends MezzeNode {

    @Specialization
    protected static boolean geLongs(long left, long right) {
        return left >= right;
    }

    /**
     * Float comparison. Also handles mixed types due to implicit widening.
     */
    @Specialization(replaces = "geLongs")
    protected static boolean geDoubles(double left, double right) {
        return left >= right;
    }

    @Specialization
    protected static boolean geStrings(String left, String right) {
        return left.compareTo(right) >= 0;
    }

    @Specialization(guards = {"isFrac(left)", "isFrac(right)"})
    protected static boolean geFractions(MezzeRecord left, MezzeRecord right) {
        return FractionHelper.compareFractions(left, right) >= 0;
    }

    protected static boolean isFrac(Object value) {
        return FractionHelper.isFraction(value);
    }

    @Fallback
    protected static Object typeError(Object left, Object right) {
        throw new RuntimeException(
            "Cannot compare " + (left == null ? "null" : left.getClass().getSimpleName()) +
            " >= " + (right == null ? "null" : right.getClass().getSimpleName())
        );
    }

    public static DirectGreaterThanOrEqualNode create(MezzeNode left, MezzeNode right) {
        return DirectGreaterThanOrEqualNodeGen.create(left, right);
    }
}
