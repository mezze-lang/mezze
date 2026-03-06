package com.mezze.interpreter.v2.nodes.inline;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Direct modulo node that takes left and right operands directly.
 *
 * This bypasses the {l, r} record allocation used by BuiltinModNode,
 * enabling much faster arithmetic when the typechecker has resolved
 * concrete types.
 */
@NodeChild(value = "left", type = MezzeNode.class)
@NodeChild(value = "right", type = MezzeNode.class)
@NodeInfo(shortName = "direct-mod")
public abstract class DirectModuloNode extends MezzeNode {

    @Specialization
    protected static long modLongs(long left, long right) {
        if (right == 0) {
            throw new ArithmeticException("Modulo by zero");
        }
        return left % right;
    }

    /**
     * Float modulo. Also handles mixed types due to implicit widening.
     */
    @Specialization(replaces = "modLongs")
    protected static double modDoubles(double left, double right) {
        return left % right;
    }

    @Fallback
    protected static Object typeError(Object left, Object right) {
        throw new RuntimeException(
            "Cannot modulo " + (left == null ? "null" : left.getClass().getSimpleName()) +
            " by " + (right == null ? "null" : right.getClass().getSimpleName())
        );
    }

    public static DirectModuloNode create(MezzeNode left, MezzeNode right) {
        return DirectModuloNodeGen.create(left, right);
    }
}
