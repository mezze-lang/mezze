package com.mezze.interpreter.nodes.effect;

import com.mezze.interpreter.nodes.MezzeNode;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * STATIC perform node - ZERO RUNTIME OVERHEAD.
 *
 * This is used when the effect handler is fully resolved at compile time.
 * Unlike PerformNode which looks up the handler from a frame slot,
 * this node has:
 *
 * 1. Pre-resolved CallTarget (no handler record lookup)
 * 2. Pre-created DirectCallNode (no lazy initialization)
 * 3. Pre-computed closure values (captured at node creation)
 * 4. NO transferToInterpreterAndInvalidate() calls
 *
 * After JIT: This compiles to a direct function call with inlined arguments.
 *
 * Usage: When the effect handler is statically known (e.g., in a `handle` block
 * where the handler expression is a known impl with known operations).
 */
@NodeInfo(shortName = "static-perform")
public final class StaticPerformNode extends MezzeNode {

    // Pre-created at AST conversion time - NO lazy initialization
    @Child
    private DirectCallNode callNode;

    @Children
    private final MezzeNode[] argumentNodes;

    @CompilationFinal
    private final int argumentCount;

    // Pre-captured closure values from the handler's operation
    @CompilationFinal(dimensions = 1)
    private final Object[] closureValues;

    @CompilationFinal
    private final int closureLength;

    /**
     * Create a static perform node with pre-resolved call target and closure.
     *
     * @param callTarget Pre-resolved CallTarget from the handler's operation
     * @param closureValues Pre-captured closure values (may be empty array)
     * @param argumentNodes Argument expression nodes
     */
    public StaticPerformNode(
            CallTarget callTarget,
            Object[] closureValues,
            MezzeNode[] argumentNodes
    ) {
        this.callNode = DirectCallNode.create(callTarget);
        this.closureValues = closureValues != null ? closureValues : EMPTY_CLOSURE;
        this.closureLength = this.closureValues.length;
        this.argumentNodes = argumentNodes;
        this.argumentCount = argumentNodes.length;
    }

    private static final Object[] EMPTY_CLOSURE = new Object[0];

    /**
     * Create with no closure (most common case).
     */
    public static StaticPerformNode create(
            CallTarget callTarget,
            MezzeNode[] argumentNodes
    ) {
        return new StaticPerformNode(callTarget, EMPTY_CLOSURE, argumentNodes);
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        // Build arguments array - closure values are compile-time constants
        Object[] arguments = new Object[closureLength + argumentCount];

        // Copy pre-captured closure values (compile-time constant after JIT)
        if (closureLength > 0) {
            for (int i = 0; i < closureLength; i++) {
                arguments[i] = closureValues[i];
            }
        }

        // Evaluate and copy argument values - @ExplodeLoop unrolls this
        for (int i = 0; i < argumentCount; i++) {
            arguments[closureLength + i] = argumentNodes[i].execute(frame);
        }

        // Direct call - NO lazy initialization, NO type checks
        return callNode.call(arguments);
    }
}
