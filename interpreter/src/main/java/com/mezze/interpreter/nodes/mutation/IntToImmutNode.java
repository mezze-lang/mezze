package com.mezze.interpreter.nodes.mutation;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.values.MutableInt;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Convert MutableInt back to Int.
 *
 * This is a simple unwrap operation - no @TruffleBoundary needed.
 */
@NodeInfo(shortName = "int-to-immut")
public final class IntToImmutNode extends MezzeNode {

    @Child private MezzeNode valueNode;

    public IntToImmutNode(MezzeNode valueNode) {
        this.valueNode = valueNode;
    }

    public static IntToImmutNode create(MezzeNode valueNode) {
        return new IntToImmutNode(valueNode);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object value = valueNode.execute(frame);
        if (value instanceof MutableInt mutableInt) {
            return mutableInt.get();
        }
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new RuntimeException("int_to_immut expects MutableInt, got: " + value.getClass());
    }

    @Override
    public long executeLong(VirtualFrame frame) {
        Object value = valueNode.execute(frame);
        if (value instanceof MutableInt mutableInt) {
            return mutableInt.get();
        }
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new RuntimeException("int_to_immut expects MutableInt, got: " + value.getClass());
    }
}
