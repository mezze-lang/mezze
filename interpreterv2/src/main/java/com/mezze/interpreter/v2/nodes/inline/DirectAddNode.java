package com.mezze.interpreter.v2.nodes.inline;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;

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
