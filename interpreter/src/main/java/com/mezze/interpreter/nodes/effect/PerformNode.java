package com.mezze.interpreter.nodes.effect;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.interpreter.values.MezzeRecord;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Perform effect operation node - AGGRESSIVELY OPTIMIZED.
 *
 * Key optimizations:
 * 1. Pre-computed operation index for O(1) field access
 * 2. ZERO ALLOCATION paths for 0-arg and 1-arg no-closure calls
 * 3. DirectCallNode for inlined calls
 * 4. Cached closure length for monomorphic handlers
 * 5. @ExplodeLoop for argument evaluation - fully unrolled by JIT
 * 6. No @TruffleBoundary anywhere
 *
 * Flow:
 * 1. handlerNode.execute() returns the handler record
 * 2. Get operation function from handler (cached after first call)
 * 3. Call via DirectCallNode (inlined by JIT)
 */
@NodeInfo(shortName = "perform")
public final class PerformNode extends MezzeNode {

    // Singleton empty array - shared across all zero-arg calls
    private static final Object[] EMPTY_ARGS = new Object[0];

    @CompilationFinal
    private final String operationName;

    @CompilationFinal
    private final int operationIndex;  // Pre-computed field index

    @Child
    private MezzeNode handlerNode;

    @Children
    private final MezzeNode[] argumentNodes;

    @CompilationFinal
    private final int argumentCount;

    // Inline caching for monomorphic call sites
    @Child
    private DirectCallNode cachedCallNode;

    @CompilationFinal
    private CallTarget cachedTarget;

    // Cache closure length for monomorphic handlers
    @CompilationFinal
    private int cachedClosureLength = -1;

    // Fallback for polymorphic call sites
    @Child
    private IndirectCallNode indirectCallNode;

    public PerformNode(
            String operationName,
            int operationIndex,
            MezzeNode handlerNode,
            MezzeNode[] argumentNodes
    ) {
        this.operationName = operationName;
        this.operationIndex = operationIndex;
        this.handlerNode = handlerNode;
        this.argumentNodes = argumentNodes;
        this.argumentCount = argumentNodes.length;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        // Get the handler record
        Object handlerValue = handlerNode.execute(frame);

        if (!(handlerValue instanceof MezzeRecord handler)) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("Handler must be a record");
        }

        // Get operation function - use pre-computed index for O(1) access
        Object opValue = handler.getField(operationIndex);

        if (!(opValue instanceof MezzeFunction operation)) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("Operation " + operationName + " is not a function");
        }

        CallTarget target = operation.getCallTarget();

        // FASTEST PATH: Monomorphic with cached zero-closure
        if (cachedTarget == target && cachedCallNode != null && cachedClosureLength == 0) {
            return callNoClosureFast(frame);
        }

        // FAST PATH: Monomorphic with cached closure length
        if (cachedTarget == target && cachedCallNode != null && cachedClosureLength >= 0) {
            return callWithCachedClosureFast(frame, operation.getClosureValues());
        }

        // First call or polymorphic - setup caching
        return callSlow(frame, operation, target);
    }

    /**
     * ZERO-CLOSURE fast path - no closure handling overhead.
     * JIT compiles this to minimal code.
     */
    @ExplodeLoop
    private Object callNoClosureFast(VirtualFrame frame) {
        // ZERO ALLOCATION for 0 args
        if (argumentCount == 0) {
            return cachedCallNode.call(EMPTY_ARGS);
        }

        // ZERO ALLOCATION for 1 arg (JIT scalar-replaces single-element array)
        if (argumentCount == 1) {
            return cachedCallNode.call(argumentNodes[0].execute(frame));
        }

        // General case with @ExplodeLoop
        Object[] arguments = new Object[argumentCount];
        for (int i = 0; i < argumentCount; i++) {
            arguments[i] = argumentNodes[i].execute(frame);
        }
        return cachedCallNode.call(arguments);
    }

    /**
     * WITH-CLOSURE fast path - closure length is cached/constant.
     * JIT unrolls both loops since lengths are compilation constants.
     */
    @ExplodeLoop
    private Object callWithCachedClosureFast(VirtualFrame frame, Object[] closureValues) {
        int closureLen = cachedClosureLength;  // Cached - constant after JIT

        Object[] arguments = new Object[closureLen + argumentCount];

        // Copy closure - JIT unrolls based on cached length
        for (int i = 0; i < closureLen; i++) {
            arguments[i] = closureValues[i];
        }

        // Copy args - JIT unrolls based on argumentCount
        for (int i = 0; i < argumentCount; i++) {
            arguments[closureLen + i] = argumentNodes[i].execute(frame);
        }

        return cachedCallNode.call(arguments);
    }

    /**
     * Slow path for first call and polymorphic sites.
     * Sets up caching for future fast path execution.
     */
    private Object callSlow(VirtualFrame frame, MezzeFunction operation, CallTarget target) {
        Object[] closureValues = operation.getClosureValues();
        int closureLength = closureValues.length;

        // Build arguments array
        Object[] arguments;
        if (closureLength == 0) {
            if (argumentCount == 0) {
                arguments = EMPTY_ARGS;
            } else {
                arguments = new Object[argumentCount];
                for (int i = 0; i < argumentCount; i++) {
                    arguments[i] = argumentNodes[i].execute(frame);
                }
            }
        } else {
            arguments = new Object[closureLength + argumentCount];
            for (int i = 0; i < closureLength; i++) {
                arguments[i] = closureValues[i];
            }
            for (int i = 0; i < argumentCount; i++) {
                arguments[closureLength + i] = argumentNodes[i].execute(frame);
            }
        }

        // First call - cache the target and closure length
        if (cachedTarget == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            cachedTarget = target;
            cachedClosureLength = closureLength;
            cachedCallNode = insert(DirectCallNode.create(target));
            return cachedCallNode.call(arguments);
        }

        // Polymorphic: different target than cached, use indirect call
        if (indirectCallNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            indirectCallNode = insert(IndirectCallNode.create());
        }
        return indirectCallNode.call(target, arguments);
    }
}
