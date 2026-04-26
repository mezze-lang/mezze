package com.mezze.interpreter.nodes.literal;

import com.mezze.interpreter.nodes.MezzeNode;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * String literal node - ZERO OVERHEAD.
 *
 * The value is @CompilationFinal so the JIT will inline
 * this node as a constant load, eliminating all dispatch.
 */
@NodeInfo(shortName = "string")
public final class StringLiteralNode extends MezzeNode {

    @CompilationFinal
    private final String value;

    public StringLiteralNode(String value) {
        this.value = value;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return value;
    }
}
