package com.mezze.interpreter.nodes.inline;

import com.mezze.interpreter.nodes.MezzeNode;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.bytecode.OperationProxy;

/**
 * Direct logical AND node that takes left and right operands directly.
 * Used for method-call syntax: x.and { r = y } (desugared from x && y).
 */
@NodeChild(value = "left", type = MezzeNode.class)
@NodeChild(value = "right", type = MezzeNode.class)
@NodeInfo(shortName = "direct-and")
@OperationProxy.Proxyable(allowUncached = true)
public abstract class DirectAndNode extends MezzeNode {

    @Specialization
    public static boolean andBooleans(boolean left, boolean right) {
        return left && right;
    }

    @Fallback
    @TruffleBoundary
    public static Object typeError(Object left, Object right) {
        throw new RuntimeException(
            "Cannot apply && to " + (left == null ? "null" : left.getClass().getSimpleName()) +
            " and " + (right == null ? "null" : right.getClass().getSimpleName())
        );
    }

    public static DirectAndNode create(MezzeNode left, MezzeNode right) {
        return DirectAndNodeGen.create(left, right);
    }
}
