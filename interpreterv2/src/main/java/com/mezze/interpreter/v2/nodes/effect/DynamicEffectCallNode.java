package com.mezze.interpreter.v2.nodes.effect;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.runtime.MezzeContext;
import com.mezze.interpreter.v2.values.MezzeFunction;
import com.mezze.interpreter.v2.values.MezzeRecord;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Dynamic effect/ability call node - handles effect operations at runtime.
 *
 * This is used when the effect receiver (e.g., Log in Log.log) isn't known
 * statically. It looks up the handler from the context and calls the operation.
 *
 * Key optimizations:
 * 1. Caches the handler lookup after first successful resolution
 * 2. Uses IndirectCallNode for polymorphic dispatch (different handlers)
 * 3. @TruffleBoundary only on initial handler lookup
 * 4. After JIT warmup: direct call to cached handler's operation
 *
 * For monomorphic call sites (same handler every time), this compiles
 * to a direct call after warmup.
 */
@NodeInfo(shortName = "dynamic-effect-call")
public final class DynamicEffectCallNode extends MezzeNode {

    @CompilationFinal
    private final String effectName;  // e.g., "Log"

    @CompilationFinal
    private final String operationName;  // e.g., "log"

    @Child
    private MezzeNode argumentNode;

    @Child
    private IndirectCallNode callNode;

    // Cache for monomorphic call sites
    @CompilationFinal
    private Object cachedHandler;

    @CompilationFinal
    private MezzeFunction cachedOperation;

    @CompilationFinal
    private boolean cacheValid = false;

    public DynamicEffectCallNode(String effectName, String operationName, MezzeNode argumentNode) {
        this.effectName = effectName;
        this.operationName = operationName;
        this.argumentNode = argumentNode;
        this.callNode = IndirectCallNode.create();
    }

    @Override
    public Object execute(VirtualFrame frame) {
        // Evaluate argument first
        Object argument = argumentNode.execute(frame);

        // Fast path: cached handler
        if (cacheValid && cachedOperation != null) {
            return callOperation(cachedOperation, argument);
        }

        // Slow path: lookup handler
        return executeSlowPath(argument);
    }

    @TruffleBoundary
    private Object executeSlowPath(Object argument) {
        // Look up handler from context
        MezzeContext context = MezzeContext.get(this);

        // Try multiple lookup strategies:
        // 1. Look up by effect name directly (e.g., "Log")
        // 2. Look up by handler name (e.g., "Console")
        Object handler = context.lookupLocal(effectName);
        if (handler == null) {
            handler = context.lookupGlobal(effectName);
        }

        if (handler == null) {
            throw new RuntimeException("Effect handler not found for: " + effectName);
        }

        if (!(handler instanceof MezzeRecord handlerRecord)) {
            throw new RuntimeException("Effect handler must be a record, got: " +
                (handler == null ? "null" : handler.getClass().getSimpleName()));
        }

        // Get the operation function from the handler record
        Object operation = handlerRecord.getField(operationName);

        if (!(operation instanceof MezzeFunction opFunc)) {
            throw new RuntimeException("Operation '" + operationName + "' not found or not a function in handler " + effectName +
                ", got: " + (operation == null ? "null" : operation.getClass().getSimpleName()));
        }

        // Cache for next time
        CompilerDirectives.transferToInterpreterAndInvalidate();
        cachedHandler = handler;
        cachedOperation = opFunc;
        cacheValid = true;

        return callOperation(opFunc, argument);
    }

    private Object callOperation(MezzeFunction operation, Object argument) {
        Object[] closureValues = operation.getClosureValues();
        int closureLength = closureValues.length;

        // Build arguments array
        Object[] arguments;
        if (closureLength == 0) {
            // Fast path: no closure
            arguments = new Object[]{argument};
        } else {
            // Include closure values
            arguments = new Object[closureLength + 1];
            for (int i = 0; i < closureLength; i++) {
                arguments[i] = closureValues[i];
            }
            arguments[closureLength] = argument;
        }

        return callNode.call(operation.getCallTarget(), arguments);
    }

    /**
     * Create a dynamic effect call node.
     *
     * @param effectName The effect name (e.g., "Log", "Random")
     * @param operationName The operation name (e.g., "log", "nextInt")
     * @param argumentNode The argument expression node
     */
    public static DynamicEffectCallNode create(String effectName, String operationName, MezzeNode argumentNode) {
        return new DynamicEffectCallNode(effectName, operationName, argumentNode);
    }
}
