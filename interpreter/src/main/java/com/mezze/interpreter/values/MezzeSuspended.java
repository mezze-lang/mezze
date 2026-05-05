package com.mezze.interpreter.values;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.nodes.control.BlockNode;
import com.mezze.interpreter.nodes.continuation.MezzeContinuationSuspended;
import com.mezze.interpreter.runtime.MezzeContext;
import com.mezze.language.typedast.Hash;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.nodes.IndirectCallNode;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Suspended computation - a deferred effect call waiting for handlers.
 *
 * This implements the lazy effects model where:
 * - Inside perform: effects execute immediately (hot path)
 * - Outside perform: effects return MezzeSuspended (cold path)
 *
 * The suspended computation can later be executed with perform,
 * which provides the missing handlers.
 *
 * Example:
 *   let result = myEffectfulFunc {}  // Returns MezzeSuspended with unhandled effects
 *   perform Console in result        // Executes with Console handler
 */
public final class MezzeSuspended implements TruffleObject {

    @CompilationFinal
    private final MezzeThunk computation;

    @CompilationFinal(dimensions = 1)
    private final Hash[] unhandledEffects;

    // Cached set view for faster contains checks
    @CompilationFinal
    private Set<Hash> unhandledEffectsSet;

    // Handlers already captured (for partial handling)
    private final Map<Hash, Object> capturedHandlers;

    public MezzeSuspended(MezzeThunk computation, Set<Hash> unhandledEffects) {
        this(computation, unhandledEffects, Collections.emptyMap());
    }

    public MezzeSuspended(MezzeThunk computation, Set<Hash> unhandledEffects, Map<Hash, Object> capturedHandlers) {
        this.computation = computation;
        this.unhandledEffects = unhandledEffects.toArray(new Hash[0]);
        this.capturedHandlers = capturedHandlers;
    }

    public MezzeThunk getComputation() {
        return computation;
    }

    public Set<Hash> getUnhandledEffects() {
        if (unhandledEffectsSet == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            unhandledEffectsSet = new HashSet<>();
            for (Hash effect : unhandledEffects) {
                unhandledEffectsSet.add(effect);
            }
            unhandledEffectsSet = Collections.unmodifiableSet(unhandledEffectsSet);
        }
        return unhandledEffectsSet;
    }

    public Map<Hash, Object> getCapturedHandlers() {
        return capturedHandlers;
    }

    public boolean hasUnhandledEffect(Hash effectHash) {
        for (Hash effect : unhandledEffects) {
            if (effect.equals(effectHash)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if all effects are handled (empty unhandled set).
     */
    public boolean isFullyHandled() {
        return unhandledEffects.length == 0;
    }

    /**
     * Get the effect name from the underlying thunk.
     */
    public String getEffectName() {
        return computation.getEffectName();
    }

    /**
     * Get the operation name from the underlying thunk.
     */
    public String getOperationName() {
        return computation.getOperationName();
    }

    /**
     * Execute the suspended computation with provided handlers.
     * This is called by perform when handlers become available.
     *
     * @param handlers Map of effect name to handler record
     * @param context The Mezze context
     * @param callNode Indirect call node for executing thunks
     * @return The result, or a new MezzeSuspended if effects remain unhandled
     */
    @TruffleBoundary
    public Object executeWith(Map<Hash, Object> handlers, MezzeContext context, IndirectCallNode callNode) {
        // Merge captured handlers with new handlers
        Map<Hash, Object> allHandlers = new HashMap<>(capturedHandlers);
        allHandlers.putAll(handlers);

        // Check if all required effects are now handled.
        // (performerHandlesEffect bails are skipped — handlers are keyed by hash now,
        //  not by performer name, so the old name-based fallback no longer applies.)
        Set<Hash> stillUnhandled = new HashSet<>();
        for (Hash effect : unhandledEffects) {
            if (!allHandlers.containsKey(effect)) {
                stillUnhandled.add(effect);
            }
        }

        if (!stillUnhandled.isEmpty()) {
            // Still missing handlers - return new MezzeSuspended with remaining effects
            return new MezzeSuspended(computation, stillUnhandled, allHandlers);
        }

        // All handlers available - execute the thunk!
        // Push handlers onto effect handler stack
        context.pushHandlers(allHandlers);

        try {
            // Check if this is a composed block thunk
            if (computation instanceof BlockNode.ComposedBlockThunk composedThunk) {
                return executeComposedBlockThunk(composedThunk, allHandlers, context, callNode);
            } else if (computation.isDeferredEffectCall()) {
                // Execute deferred effect call by looking up handler and calling operation
                return executeDeferredEffectCall(allHandlers, context, callNode);
            } else {
                // Execute regular function thunk
                return computation.execute(callNode);
            }
        } finally {
            context.popHandlers();
        }
    }

    /**
     * Execute a composed block thunk.
     * This executes the original effect, then the remaining statements.
     */
    @TruffleBoundary
    private Object executeComposedBlockThunk(
            BlockNode.ComposedBlockThunk composedThunk,
            Map<Hash, Object> handlers,
            MezzeContext context,
            IndirectCallNode callNode
    ) {
        MezzeThunk originalThunk = composedThunk.getOriginalThunk();
        MezzeNode[] remainingStatements = composedThunk.getRemainingStatements();
        MaterializedFrame materializedFrame = composedThunk.getMaterializedFrame();

        // Execute the original suspended effect
        Object effectResult;
        if (originalThunk instanceof BlockNode.ComposedBlockThunk nestedComposed) {
            // Nested composed thunk - recurse
            effectResult = executeComposedBlockThunk(nestedComposed, handlers, context, callNode);
        } else if (originalThunk.isDeferredEffectCall()) {
            // Deferred effect call - use content hash for O(1) lookup
            Hash effectContentHash = originalThunk.getEffectContentHash();
            String operationName = originalThunk.getOperationName();
            Object argument = originalThunk.getArguments();

            // Content hash lookup - no fallbacks
            if (effectContentHash == null) {
                throw new RuntimeException("No effect content hash available for composed block effect call. " +
                    "Operation: " + operationName);
            }

            Object handler = handlers.get(effectContentHash);
            if (handler == null) {
                throw new RuntimeException("No handler found for effect hash " + effectContentHash.getHex() + " in composed block");
            }

            if (!(handler instanceof MezzeRecord handlerRecord)) {
                throw new RuntimeException("Handler for effect hash " + effectContentHash.getHex() + " must be a record");
            }

            Object operation = handlerRecord.getField(operationName);
            if (!(operation instanceof MezzeFunction opFunc)) {
                throw new RuntimeException("Operation " + operationName + " not found in handler (hash: " + effectContentHash.getHex() + ")");
            }

            effectResult = callFunction(opFunc, argument, callNode);
        } else {
            // Regular function thunk
            effectResult = originalThunk.execute(callNode);
        }

        // If the effect result is still suspended, we need to compose again
        if (effectResult instanceof MezzeSuspended) {
            // The effect itself returned another suspension - compose with remaining
            return new MezzeSuspended(
                new BlockNode.ComposedBlockThunk(
                    ((MezzeSuspended) effectResult).getComputation(),
                    remainingStatements,
                    materializedFrame
                ),
                ((MezzeSuspended) effectResult).getUnhandledEffects(),
                handlers
            );
        }

        // Execute remaining statements
        Object result = MezzeUnit.INSTANCE;
        for (MezzeNode stmt : remainingStatements) {
            result = stmt.execute(materializedFrame);

            // Handle suspended results in remaining statements
            if (result instanceof MezzeSuspended) {
                return result;
            }
            if (result instanceof MezzeContinuationSuspended) {
                return result;
            }
        }

        return result;
    }

    /**
     * Call a function with closure values prepended.
     */
    private Object callFunction(MezzeFunction func, Object argument, IndirectCallNode callNode) {
        Object[] closureValues = func.getClosureValues();
        int closureLength = closureValues.length;

        Object[] fullArgs;
        if (closureLength == 0) {
            fullArgs = new Object[]{argument};
        } else {
            fullArgs = new Object[closureLength + 1];
            for (int i = 0; i < closureLength; i++) {
                fullArgs[i] = closureValues[i];
            }
            fullArgs[closureLength] = argument;
        }

        return callNode.call(func.getCallTarget(), fullArgs);
    }

    /**
     * Execute a deferred effect call by resolving the handler and calling the operation.
     * Uses content hash for O(1) collision-free lookup - no effect name fallbacks.
     */
    @TruffleBoundary
    private Object executeDeferredEffectCall(Map<Hash, Object> handlers, MezzeContext context, IndirectCallNode callNode) {
        Hash effectContentHash = computation.getEffectContentHash();
        String operationName = computation.getOperationName();
        Object argument = computation.getArguments();

        // Content hash lookup - no fallbacks
        if (effectContentHash == null) {
            throw new RuntimeException("No effect content hash available for deferred effect call. " +
                "Operation: " + operationName);
        }

        Object handler = handlers.get(effectContentHash);
        if (handler == null) {
            throw new RuntimeException("No handler found for effect hash " + effectContentHash.getHex() +
                " in deferred call. Operation: " + operationName);
        }

        if (!(handler instanceof MezzeRecord handlerRecord)) {
            throw new RuntimeException("Handler for effect hash " + effectContentHash.getHex() + " must be a record, got: " +
                handler.getClass().getSimpleName());
        }

        // Get the operation function
        Object operation = handlerRecord.getField(operationName);
        if (!(operation instanceof MezzeFunction opFunc)) {
            throw new RuntimeException("Operation " + operationName + " not found or not a function in handler (hash: " + effectContentHash.getHex() + ")");
        }

        // Call the operation with closure values prepended
        Object[] closureValues = opFunc.getClosureValues();
        int closureLength = closureValues.length;

        Object[] fullArgs;
        if (closureLength == 0) {
            fullArgs = new Object[]{argument};
        } else {
            fullArgs = new Object[closureLength + 1];
            for (int i = 0; i < closureLength; i++) {
                fullArgs[i] = closureValues[i];
            }
            fullArgs[closureLength] = argument;
        }

        return callNode.call(opFunc.getCallTarget(), fullArgs);
    }

    /**
     * Check if a value is a suspended computation.
     */
    public static boolean isSuspended(Object value) {
        return value instanceof MezzeSuspended;
    }

    @Override
    @TruffleBoundary
    public String toString() {
        StringBuilder sb = new StringBuilder("<suspended <");
        for (int i = 0; i < unhandledEffects.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(unhandledEffects[i].getHex());
        }
        sb.append(">>");
        return sb.toString();
    }
}
