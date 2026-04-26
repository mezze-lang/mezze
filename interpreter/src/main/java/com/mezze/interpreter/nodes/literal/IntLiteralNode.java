package com.mezze.interpreter.nodes.literal;

import com.mezze.interpreter.nodes.MezzeNode;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.UnexpectedResultException;

/**
 * Integer literal node - ZERO OVERHEAD.
 *
 * The value is @CompilationFinal so the JIT will inline
 * this node as a constant load, eliminating all dispatch.
 * Returns primitive long (unboxed) for maximum performance.
 */
@NodeInfo(shortName = "int")
public final class IntLiteralNode extends MezzeNode {

    @CompilationFinal
    private final long value;

    public IntLiteralNode(long value) {
        this.value = value;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return value;
    }

    @Override
    public long executeLong(VirtualFrame frame) {
        return value;
    }

    @Override
    public double executeDouble(VirtualFrame frame) {
        return (double) value;
    }
}
