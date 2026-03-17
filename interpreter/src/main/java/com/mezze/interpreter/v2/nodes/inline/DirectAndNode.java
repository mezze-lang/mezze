package com.mezze.interpreter.v2.nodes.inline;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Direct logical AND node that takes left and right operands directly.
 * Used for method-call syntax: x.and { r = y } (desugared from x && y).
 */
@NodeChild(value = "left", type = MezzeNode.class)
@NodeChild(value = "right", type = MezzeNode.class)
@NodeInfo(shortName = "direct-and")
public abstract class DirectAndNode extends MezzeNode {

    @Specialization
    protected static boolean andBooleans(boolean left, boolean right) {
        return left && right;
    }

    @Fallback
    protected static Object typeError(Object left, Object right) {
        throw new RuntimeException(
            "Cannot apply && to " + (left == null ? "null" : left.getClass().getSimpleName()) +
            " and " + (right == null ? "null" : right.getClass().getSimpleName())
        );
    }

    public static DirectAndNode create(MezzeNode left, MezzeNode right) {
        return DirectAndNodeGen.create(left, right);
    }
}
