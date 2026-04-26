package com.mezze.interpreter.nodes.literal;

import com.mezze.interpreter.nodes.MezzeNode;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Float literal node - ZERO OVERHEAD.
 *
 * The value is @CompilationFinal so the JIT will inline
 * this node as a constant load, eliminating all dispatch.
 * Returns primitive double (unboxed) for maximum performance.
 */
@NodeInfo(shortName = "float")
public final class FloatLiteralNode extends MezzeNode {

    @CompilationFinal
    private final double value;

    public FloatLiteralNode(double value) {
        this.value = value;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return value;
    }

    @Override
    public double executeDouble(VirtualFrame frame) {
        return value;
    }
}
