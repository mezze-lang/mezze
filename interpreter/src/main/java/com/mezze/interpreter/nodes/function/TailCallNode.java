package com.mezze.interpreter.nodes.function;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.values.MezzeFunction;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Tail call node - AGGRESSIVELY OPTIMIZED.
 *
 * Key optimizations:
 * 1. ZERO-CLOSURE FAST PATH - no closure copying overhead
 * 2. Truffle DSL specializations for monomorphic tail calls
 * 3. Cached closure length for @ExplodeLoop unrolling
 * 4. Inline loops instead of System.arraycopy
 * 5. Pre-computed argument count
 *
 * When a function call is in tail position (determined by typechecker's
 * isTailCall flag), we throw TailCallException instead of making a
 * recursive call. This is caught by TailCallLoopNode which rebinds
 * arguments and continues without growing the stack.
 */
@NodeChild("functionNode")
@NodeInfo(shortName = "tail-call")
public abstract class TailCallNode extends MezzeNode {

    @Children
    private final MezzeNode[] argumentNodes;

    @CompilationFinal
    private final int argumentCount;

    protected TailCallNode(MezzeNode[] argumentNodes) {
        this.argumentNodes = argumentNodes;
        this.argumentCount = argumentNodes.length;
    }

    /**
     * FASTEST PATH: Zero-closure tail call.
     * No closure handling overhead - just evaluate arguments and throw.
     */
    @Specialization(guards = "!function.hasClosure()")
    @ExplodeLoop
    protected Object tailCallNoClosure(VirtualFrame frame, MezzeFunction function) {
        // Direct argument array - no closure values to prepend
        Object[] arguments = new Object[argumentCount];

        // Evaluate argument values - @ExplodeLoop unrolls this
        for (int i = 0; i < argumentCount; i++) {
            arguments[i] = argumentNodes[i].execute(frame);
        }

        throw new TailCallException(function.getCallTarget(), arguments);
    }

    /**
     * Monomorphic tail call with cached closure length.
     * JIT fully unrolls both loops.
     */
    @Specialization(
        guards = "function.getClosureValues().length == cachedClosureLength",
        limit = "3"
    )
    @ExplodeLoop
    protected Object tailCallCached(
            VirtualFrame frame,
            MezzeFunction function,
            @Cached("function.getClosureValues().length") int cachedClosureLength
    ) {
        // Pre-size array based on cached lengths
        Object[] arguments = new Object[cachedClosureLength + argumentCount];

        // Copy closure values with inline loop (JIT unrolls completely)
        Object[] closureValues = function.getClosureValues();
        for (int i = 0; i < cachedClosureLength; i++) {
            arguments[i] = closureValues[i];
        }

        // Evaluate and copy argument values
        for (int i = 0; i < argumentCount; i++) {
            arguments[cachedClosureLength + i] = argumentNodes[i].execute(frame);
        }

        throw new TailCallException(function.getCallTarget(), arguments);
    }

    /**
     * Generic tail call (varying closure length).
     */
    @Specialization(replaces = "tailCallCached")
    @ExplodeLoop
    protected Object tailCallGeneric(VirtualFrame frame, MezzeFunction function) {
        Object[] closureValues = function.getClosureValues();
        int closureLength = closureValues.length;
        Object[] arguments = new Object[closureLength + argumentCount];

        // Inline copy loop
        for (int i = 0; i < closureLength; i++) {
            arguments[i] = closureValues[i];
        }

        for (int i = 0; i < argumentCount; i++) {
            arguments[closureLength + i] = argumentNodes[i].execute(frame);
        }

        throw new TailCallException(function.getCallTarget(), arguments);
    }

    /**
     * Create a tail call node.
     */
    public static TailCallNode create(MezzeNode functionNode, MezzeNode[] argumentNodes) {
        return TailCallNodeGen.create(argumentNodes, functionNode);
    }
}
