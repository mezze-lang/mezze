package com.mezze.interpreter.v2.nodes.effect;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.runtime.MezzeContext;
import com.mezze.interpreter.v2.values.MezzeFunction;
import com.mezze.interpreter.v2.values.MezzeRecord;
import com.mezze.interpreter.v2.values.MezzeSuspended;
import com.mezze.interpreter.v2.values.MezzeThunk;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.NodeInfo;

import java.util.Collections;

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

        // Fast path: cached handler (only for monomorphic call sites with same handler)
        // Note: caching is NOT correct for lazy effects since handlers change dynamically!
        // The handler stack is context-dependent, so we can't cache handler lookups.
        // Always go through slow path for now.
        // TODO: Consider caching based on handler stack depth/identity for performance

        // Slow path: lookup handler
        return executeSlowPath(argument);
    }

    @TruffleBoundary
    private Object executeSlowPath(Object argument) {
        // Look up handler from context
        MezzeContext context = MezzeContext.get(this);

        // LAZY EFFECTS: ONLY check the handler stack
        // The handler stack is the source of truth for active handlers.
        // Handlers registered in locals/globals are NOT automatically active -
        // they must be explicitly installed via perform.
        Object handler = context.getHandlerFor(effectName);

        // NO HANDLER AVAILABLE - Create suspended computation (LAZY EFFECTS)
        if (handler == null) {
            return createSuspendedEffectCall(argument);
        }

        // Handler should be a record. If it's a function (parameterized impl), that's an error
        // because parameterized impls should be invoked via ParameterizedHandleNode first.
        // But for robustness, check if the DynamicHandleNode already invoked the function.
        if (!(handler instanceof MezzeRecord handlerRecord)) {
            // If handler is a function, it's likely a parameterized impl that wasn't invoked
            // This shouldn't normally happen if the perform expression was converted correctly
            throw new RuntimeException("Effect handler must be a record, got: " +
                (handler == null ? "null" : handler.getClass().getSimpleName()) +
                ". For parameterized impls, use 'perform Handler {params} in ...'");
        }

        // Get the operation function from the handler record
        Object operation = handlerRecord.getField(operationName);

        if (!(operation instanceof MezzeFunction opFunc)) {
            throw new RuntimeException("Operation '" + operationName + "' not found or not a function in handler " + effectName +
                ", got: " + (operation == null ? "null" : operation.getClass().getSimpleName()));
        }

        return callOperation(opFunc, argument);
    }

    /**
     * Create a suspended effect call when no handler is available.
     * This implements the lazy effects model.
     */
    @TruffleBoundary
    private MezzeSuspended createSuspendedEffectCall(Object argument) {
        // Create a thunk for the deferred effect operation
        MezzeThunk thunk = new MezzeThunk(effectName, operationName, argument);

        // Wrap in MezzeSuspended with this effect as unhandled
        return new MezzeSuspended(
            thunk,
            Collections.singleton(effectName)
        );
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
