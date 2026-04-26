package com.mezze.interpreter.nodes.function;

import com.mezze.interpreter.nodes.MezzeNode;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * STATIC function invocation node - ZERO RUNTIME OVERHEAD.
 *
 * This is used when calling a top-level function that:
 * 1. Is known at compile time (via content-hash)
 * 2. Has no closure variables (pure top-level function)
 *
 * Unlike InvokeNode which:
 * - Evaluates a function expression
 * - Checks if it's a MezzeFunction
 * - Handles polymorphic call targets
 * - Copies closure values
 *
 * StaticInvokeNode has:
 * 1. Pre-resolved CallTarget (compile-time constant)
 * 2. Pre-created DirectCallNode (no specialization)
 * 3. NO function evaluation
 * 4. NO closure handling
 * 5. NO type checks
 *
 * After JIT: This compiles to a direct function call, potentially inlined.
 */
@NodeInfo(shortName = "static-invoke")
public final class StaticInvokeNode extends MezzeNode {

    // Pre-created at AST conversion time
    @Child
    private DirectCallNode callNode;

    @Children
    private final MezzeNode[] argumentNodes;

    @CompilationFinal
    private final int argumentCount;

    /**
     * Create a static invoke node for a known top-level function.
     *
     * @param callTarget Pre-resolved CallTarget
     * @param argumentNodes Argument expression nodes
     */
    public StaticInvokeNode(CallTarget callTarget, MezzeNode[] argumentNodes) {
        this.callNode = DirectCallNode.create(callTarget);
        this.argumentNodes = argumentNodes;
        this.argumentCount = argumentNodes.length;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        // Allocate arguments array
        Object[] arguments = new Object[argumentCount];

        // Evaluate argument values - @ExplodeLoop unrolls this
        for (int i = 0; i < argumentCount; i++) {
            arguments[i] = argumentNodes[i].execute(frame);
        }

        // Direct call - maximum performance
        return callNode.call(arguments);
    }

    /**
     * Optimized version for zero-argument calls.
     */
    public static final class ZeroArg extends MezzeNode {
        @Child
        private DirectCallNode callNode;

        private static final Object[] EMPTY_ARGS = new Object[0];

        public ZeroArg(CallTarget callTarget) {
            this.callNode = DirectCallNode.create(callTarget);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return callNode.call(EMPTY_ARGS);
        }
    }

    /**
     * Optimized version for single-argument calls.
     */
    public static final class OneArg extends MezzeNode {
        @Child
        private DirectCallNode callNode;

        @Child
        private MezzeNode arg0;

        public OneArg(CallTarget callTarget, MezzeNode arg0) {
            this.callNode = DirectCallNode.create(callTarget);
            this.arg0 = arg0;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return callNode.call(new Object[] { arg0.execute(frame) });
        }
    }

    /**
     * Optimized version for two-argument calls.
     */
    public static final class TwoArg extends MezzeNode {
        @Child
        private DirectCallNode callNode;

        @Child
        private MezzeNode arg0;

        @Child
        private MezzeNode arg1;

        public TwoArg(CallTarget callTarget, MezzeNode arg0, MezzeNode arg1) {
            this.callNode = DirectCallNode.create(callTarget);
            this.arg0 = arg0;
            this.arg1 = arg1;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return callNode.call(new Object[] {
                arg0.execute(frame),
                arg1.execute(frame)
            });
        }
    }

    /**
     * Optimized version for three-argument calls.
     */
    public static final class ThreeArg extends MezzeNode {
        @Child
        private DirectCallNode callNode;

        @Child
        private MezzeNode arg0;

        @Child
        private MezzeNode arg1;

        @Child
        private MezzeNode arg2;

        public ThreeArg(CallTarget callTarget, MezzeNode arg0, MezzeNode arg1, MezzeNode arg2) {
            this.callNode = DirectCallNode.create(callTarget);
            this.arg0 = arg0;
            this.arg1 = arg1;
            this.arg2 = arg2;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return callNode.call(new Object[] {
                arg0.execute(frame),
                arg1.execute(frame),
                arg2.execute(frame)
            });
        }
    }

    /**
     * Factory method to create the optimal node for the argument count.
     */
    public static MezzeNode create(CallTarget callTarget, MezzeNode[] argumentNodes) {
        return switch (argumentNodes.length) {
            case 0 -> new ZeroArg(callTarget);
            case 1 -> new OneArg(callTarget, argumentNodes[0]);
            case 2 -> new TwoArg(callTarget, argumentNodes[0], argumentNodes[1]);
            case 3 -> new ThreeArg(callTarget, argumentNodes[0], argumentNodes[1], argumentNodes[2]);
            default -> new StaticInvokeNode(callTarget, argumentNodes);
        };
    }
}
