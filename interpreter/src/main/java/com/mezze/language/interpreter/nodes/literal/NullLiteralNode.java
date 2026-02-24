package com.mezze.language.interpreter.nodes.literal;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;

import com.mezze.language.interpreter.MezzeNull;
import com.mezze.language.interpreter.MezzeValue;
import com.mezze.language.interpreter.nodes.MezzeNode;

/**
 * Null literal node.
 * Written in Java to avoid Kotlin intrinsics in the JIT path.
 */
@NodeInfo(shortName = "null-literal")
public final class NullLiteralNode extends MezzeNode {

    @Override
    public MezzeValue execute(VirtualFrame frame) {
        return MezzeNull.INSTANCE;
    }

    @Override
    public String toString() {
        return "NullLiteral";
    }
}
