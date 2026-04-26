package com.mezze.interpreter.nodes.variable;

import com.mezze.interpreter.nodes.MezzeNode;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.UnexpectedResultException;

/**
 * Read an argument from the frame's arguments array - OPTIMIZED.
 *
 * Key optimizations:
 * 1. Index is @CompilationFinal for constant propagation
 * 2. No bounds check in hot path (deferred to interpreter)
 * 3. Direct array access for maximum performance
 *
 * Arguments are passed in frame.getArguments() and include:
 * - Closure values (indices 0 to closureCount-1)
 * - Function parameters (indices closureCount to closureCount+paramCount-1)
 */
@NodeInfo(shortName = "read-arg")
public final class ReadArgumentNode extends MezzeNode {

    @CompilationFinal
    private final int index;

    public ReadArgumentNode(int index) {
        this.index = index;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        // Direct array access - no bounds check in compiled code
        // The JIT trusts that index is valid (validated at compile time)
        return frame.getArguments()[index];
    }

    @Override
    public long executeLong(VirtualFrame frame) throws UnexpectedResultException {
        Object value = frame.getArguments()[index];
        if (value instanceof Long l) {
            return l;
        }
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new UnexpectedResultException(value);
    }

    @Override
    public double executeDouble(VirtualFrame frame) throws UnexpectedResultException {
        Object value = frame.getArguments()[index];
        if (value instanceof Double d) {
            return d;
        }
        if (value instanceof Long l) {
            return l.doubleValue();
        }
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new UnexpectedResultException(value);
    }

    @Override
    public boolean executeBoolean(VirtualFrame frame) throws UnexpectedResultException {
        Object value = frame.getArguments()[index];
        if (value instanceof Boolean b) {
            return b;
        }
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new UnexpectedResultException(value);
    }
}
