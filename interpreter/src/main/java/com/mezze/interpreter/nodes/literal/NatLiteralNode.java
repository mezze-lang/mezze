package com.mezze.interpreter.nodes.literal;

import com.mezze.interpreter.nodes.MezzeNode;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Natural number literal node - ZERO OVERHEAD.
 *
 * Returns primitive long for optimal JIT performance.
 * The type distinction (Nat vs Int) is known statically from the typechecker.
 * Test helper handles conversion to V1 MezzeNat based on expected types.
 */
@NodeInfo(shortName = "nat")
public final class NatLiteralNode extends MezzeNode {

    @CompilationFinal
    private final long value;

    public NatLiteralNode(long value) {
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
