package com.mezze.language.interpreter.nodes.literal;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;

import com.mezze.language.interpreter.MezzeBool;
import com.mezze.language.interpreter.MezzeValue;
import com.mezze.language.interpreter.nodes.MezzeNode;

/**
 * Boolean literal node.
 * Written in Java to avoid Kotlin intrinsics in the JIT path.
 */
@NodeInfo(shortName = "boolean-literal")
public final class BooleanLiteralNode extends MezzeNode {

    private final boolean value;

    public BooleanLiteralNode(boolean value) {
        this.value = value;
    }

    @Override
    public MezzeValue execute(VirtualFrame frame) {
        return new MezzeBool(value);
    }

    @Override
    public String toString() {
        return "BooleanLiteral(" + value + ")";
    }
}
