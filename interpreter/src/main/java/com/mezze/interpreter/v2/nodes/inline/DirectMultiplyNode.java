package com.mezze.interpreter.v2.nodes.inline;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;

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
public abstract class DirectMultiplyNode extends MezzeNode {

    @Specialization(rewriteOn = ArithmeticException.class)
    protected static long mulLongs(long left, long right) {
        return Math.multiplyExact(left, right);
    }

    @Specialization(replaces = "mulLongs")
    protected static double mulLongsOverflow(long left, long right) {
        return (double) left * (double) right;
    }

    /**
     * Float multiplication. Also handles mixed types due to implicit widening.
     */
    @Specialization(replaces = "mulLongsOverflow")
    protected static double mulDoubles(double left, double right) {
        return left * right;
    }

    @Fallback
    protected static Object typeError(Object left, Object right) {
        throw new RuntimeException(
            "Cannot multiply " + (left == null ? "null" : left.getClass().getSimpleName()) +
            " and " + (right == null ? "null" : right.getClass().getSimpleName())
        );
    }

    public static DirectMultiplyNode create(MezzeNode left, MezzeNode right) {
        return DirectMultiplyNodeGen.create(left, right);
    }
}
