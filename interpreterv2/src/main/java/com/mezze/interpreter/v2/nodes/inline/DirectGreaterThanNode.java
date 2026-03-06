package com.mezze.interpreter.v2.nodes.inline;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Direct greater-than node that takes left and right operands directly.
 *
 * This bypasses the {l, r} record allocation used by BuiltinGtNode,
 * enabling much faster comparison when the typechecker has resolved
 * concrete types.
 */
@NodeChild(value = "left", type = MezzeNode.class)
@NodeChild(value = "right", type = MezzeNode.class)
@NodeInfo(shortName = "direct-gt")
public abstract class DirectGreaterThanNode extends MezzeNode {

    @Specialization
    protected static boolean gtLongs(long left, long right) {
        return left > right;
    }

    /**
     * Float comparison. Also handles mixed types due to implicit widening.
     */
    @Specialization(replaces = "gtLongs")
    protected static boolean gtDoubles(double left, double right) {
        return left > right;
    }

    @Specialization
    protected static boolean gtStrings(String left, String right) {
        return left.compareTo(right) > 0;
    }

    @Fallback
    protected static Object typeError(Object left, Object right) {
        throw new RuntimeException(
            "Cannot compare " + (left == null ? "null" : left.getClass().getSimpleName()) +
            " > " + (right == null ? "null" : right.getClass().getSimpleName())
        );
    }

    public static DirectGreaterThanNode create(MezzeNode left, MezzeNode right) {
        return DirectGreaterThanNodeGen.create(left, right);
    }
}
