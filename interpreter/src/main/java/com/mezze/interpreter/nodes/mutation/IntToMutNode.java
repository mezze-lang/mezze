package com.mezze.interpreter.nodes.mutation;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.values.MutableInt;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Convert Int to MutableInt.
 *
 * This is a simple wrapper operation - no @TruffleBoundary needed.
 */
@NodeInfo(shortName = "int-to-mut")
public final class IntToMutNode extends MezzeNode {

    @Child private MezzeNode valueNode;

    public IntToMutNode(MezzeNode valueNode) {
        this.valueNode = valueNode;
    }

    public static IntToMutNode create(MezzeNode valueNode) {
        return new IntToMutNode(valueNode);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object value = valueNode.execute(frame);
        if (value instanceof Long longValue) {
            return new MutableInt(longValue);
        }
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new RuntimeException("int_to_mut expects Int, got: " + value.getClass());
    }

    @Override
    public long executeLong(VirtualFrame frame) {
        // MutableInt is an Object, not a primitive
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new RuntimeException("int_to_mut returns MutableInt, not long");
    }
}
