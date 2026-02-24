package com.mezze.language.interpreter.nodes.literal;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;

import com.mezze.language.interpreter.MezzeInt;
import com.mezze.language.interpreter.MezzeValue;
import com.mezze.language.interpreter.nodes.MezzeNode;

/**
 * Integer literal node.
 * Written in Java to avoid Kotlin intrinsics in the JIT path.
 */
@NodeInfo(shortName = "int-literal")
public final class IntLiteralNode extends MezzeNode {

    private final long value;

    public IntLiteralNode(long value) {
        this.value = value;
    }

    @Override
    public MezzeValue execute(VirtualFrame frame) {
        return new MezzeInt(value);
    }

    @Override
    public String toString() {
        return "IntLiteral(" + value + ")";
    }
}
