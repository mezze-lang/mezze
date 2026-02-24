package com.mezze.language.interpreter.nodes.literal;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;

import com.mezze.language.interpreter.MezzeString;
import com.mezze.language.interpreter.MezzeValue;
import com.mezze.language.interpreter.nodes.MezzeNode;

/**
 * String literal node.
 * Written in Java to avoid Kotlin intrinsics in the JIT path.
 */
@NodeInfo(shortName = "string-literal")
public final class StringLiteralNode extends MezzeNode {

    private final String value;

    public StringLiteralNode(String value) {
        this.value = value;
    }

    @Override
    public MezzeValue execute(VirtualFrame frame) {
        return new MezzeString(value);
    }

    @Override
    public String toString() {
        return "StringLiteral(\"" + value + "\")";
    }
}
