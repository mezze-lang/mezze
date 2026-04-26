package com.mezze.interpreter.nodes.literal;

import com.mezze.interpreter.nodes.MezzeNode;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Boolean literal node - ZERO OVERHEAD.
 *
 * The value is @CompilationFinal so the JIT will inline
 * this node as a constant load, eliminating all dispatch.
 * Returns primitive boolean (unboxed) for maximum performance.
 */
@NodeInfo(shortName = "bool")
public final class BoolLiteralNode extends MezzeNode {

    @CompilationFinal
    private final boolean value;

    public BoolLiteralNode(boolean value) {
        this.value = value;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return value;
    }

    @Override
    public boolean executeBoolean(VirtualFrame frame) {
        return value;
    }
}
