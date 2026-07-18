package com.mezze.interpreter.nodes.inline;

import com.mezze.interpreter.nodes.MezzeNode;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.bytecode.OperationProxy;

/**
 * Direct logical NOT node that takes the operand directly.
 * Used for method-call syntax: x.not {} (desugared from !x).
 */
@NodeChild(value = "operand", type = MezzeNode.class)
@NodeInfo(shortName = "direct-not")
@OperationProxy.Proxyable(allowUncached = true)
public abstract class DirectNotNode extends MezzeNode {

    @Specialization
    public static boolean notBoolean(boolean value) {
        return !value;
    }

    @Fallback
    public static Object typeError(Object value) {
        throw new RuntimeException(
            "Cannot apply ! to " + (value == null ? "null" : value.getClass().getSimpleName())
        );
    }

    public static DirectNotNode create(MezzeNode operand) {
        return DirectNotNodeGen.create(operand);
    }
}
