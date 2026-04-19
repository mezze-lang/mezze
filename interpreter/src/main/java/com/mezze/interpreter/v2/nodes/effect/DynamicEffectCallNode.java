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

    /** Content hash for effect lookup (preferred over effectName when available) */
    @CompilationFinal
    private final String effectContentHash;

    @Child
    private MezzeNode argumentNode;

    @Child
    private IndirectCallNode callNode;

    // OPTIMIZATION: Version-based handler caching
    // Cache the resolved handler and operation along with the stack version.
    // When stack version matches, we can skip @TruffleBoundary and use cached values.
    @CompilationFinal
    private long cachedStackVersion = -1;

    @CompilationFinal
    private MezzeRecord cachedHandlerRecord;

    @CompilationFinal
    private MezzeFunction cachedOperation;

    @CompilationFinal
    private CallTarget cachedCallTarget;

    @CompilationFinal
    private int cachedClosureLength = -1;

    @CompilationFinal(dimensions = 1)
    private Object[] cachedClosureValues;

    // Cached operation field index for faster repeated access
    @CompilationFinal
    private int cachedOperationIndex = -1;

    // DirectCallNode for JIT-inlined calls after cache hit
    @Child
    private DirectCallNode directCallNode;

    public DynamicEffectCallNode(String effectName, String operationName, MezzeNode argumentNode, String effectContentHash) {
        this.effectName = effectName;
        this.operationName = operationName;
        this.argumentNode = argumentNode;
        this.effectContentHash = effectContentHash;
        this.callNode = IndirectCallNode.create();
    }

    /** Constructor without content hash (backward compatibility) */
    public DynamicEffectCallNode(String effectName, String operationName, MezzeNode argumentNode) {
        this(effectName, operationName, argumentNode, null);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        // Evaluate argument first
        Object argument = argumentNode.execute(frame);

        // OPTIMIZATION: Version-based cache check
        // If handler stack version hasn't changed, we can reuse the cached handler/operation.
        // This avoids the @TruffleBoundary in getHandlerFor() on cache hits.
        MezzeContext context = MezzeContext.get(this);
        long currentVersion = context.getHandlerStackVersion();

        if (currentVersion == cachedStackVersion && directCallNode != null && cachedClosureLength >= 0) {
            // FAST PATH: Cache hit - direct call without @TruffleBoundary
            return callCachedFast(argument);
        }

        // SLOW PATH: Cache miss - lookup handler and update cache
        return executeSlowPath(argument, context, currentVersion);
    }

    /**
     * FAST PATH: Call cached operation directly.
     * No @TruffleBoundary - fully JIT-inlinable after warmup.
     */
    private Object callCachedFast(Object argument) {
        if (cachedClosureLength == 0) {
            return directCallNode.call(argument);
        } else if (cachedClosureLength == 1) {
            return directCallNode.call(cachedClosureValues[0], argument);
        } else if (cachedClosureLength == 2) {
            return directCallNode.call(cachedClosureValues[0], cachedClosureValues[1], argument);
        } else {
            // General case: build args array
            Object[] args = new Object[cachedClosureLength + 1];
            System.arraycopy(cachedClosureValues, 0, args, 0, cachedClosureLength);
            args[cachedClosureLength] = argument;
            return directCallNode.call(args);
        }
    }

    @TruffleBoundary
    private Object executeSlowPath(Object argument, MezzeContext context, long currentVersion) {
        // LAZY EFFECTS: ONLY check the handler stack
        // The handler stack is the source of truth for active handlers.
        // Handlers registered in locals/globals are NOT automatically active -
        // they must be explicitly installed via perform.

        // Content hash lookup (O(1) via collapsed map)
        Object handler = null;
        if (effectContentHash != null && !effectContentHash.isEmpty()) {
            handler = context.getHandlerFor(effectContentHash);
        }

        // FALLBACK: If hash lookup failed, scan all active handlers for one that has
        // the operation we need. This handles hash mismatches between user modules and
        // stdlib modules (e.g., user's effectHash differs from stdlib's effectHash for
        // the same effect due to different Hash.fromV1 inputs).
        if (handler == null) {
            handler = context.findHandlerWithOperation(operationName);
        }

        // NO HANDLER AVAILABLE - Create suspended computation (LAZY EFFECTS)
        if (handler == null) {
            return createSuspendedEffectCall(argument);
        }

        // Handler should be a record. If it's a MezzeFunction (closure factory not yet evaluated),
        // try evaluating it to get the performer record.
        MezzeRecord handlerRecord;
        if (handler instanceof MezzeRecord rec) {
            handlerRecord = rec;
        } else if (handler instanceof MezzeFunction fn) {
            // Lazy evaluation: the handler is a closure factory that wasn't evaluated yet
            // (e.g., because captures were missing during module loading)
            try {
                Object result = fn.getCallTarget().call(new Object[] { MezzeRecord.empty() });
                if (result instanceof MezzeFunction innerFn) {
                    result = innerFn.getCallTarget().call(new Object[] { MezzeRecord.empty() });
                }
                if (result instanceof MezzeRecord rec) {
                    handlerRecord = rec;
                    // Cache the resolved record back in context for future lookups
                    if (effectContentHash != null && !effectContentHash.isEmpty()) {
                        context.defineGlobal(effectContentHash, handlerRecord);
                    }
                } else {
                    throw new RuntimeException("Effect handler closure factory returned " +
                        (result == null ? "null" : result.getClass().getSimpleName()) +
                        " instead of a record for operation '" + operationName + "'");
                }
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("Failed to evaluate effect handler closure factory: " + e.getMessage(), e);
            }
        } else {
            throw new RuntimeException("Effect handler must be a record, got: " +
                (handler == null ? "null" : handler.getClass().getSimpleName()) +
                ". For parameterized impls, use 'perform Handler {params} in ...'");
        }

        // Get the operation function from the handler record using cached index
        int opIndex = cachedOperationIndex;
        if (opIndex < 0) {
            opIndex = handlerRecord.getFieldIndex(operationName);
            if (opIndex >= 0) {
                cachedOperationIndex = opIndex;
            }
        }

        Object operation = opIndex >= 0 ? handlerRecord.getField(opIndex) : handlerRecord.getField(operationName);

        if (!(operation instanceof MezzeFunction opFunc)) {
            throw new RuntimeException("Operation '" + operationName + "' not found or not a function in handler (hash: " +
                effectContentHash + "), got: " + (operation == null ? "null" : operation.getClass().getSimpleName()));
        }

        // OPTIMIZATION: Update cache for future fast-path calls
        updateCache(currentVersion, handlerRecord, opFunc);

        return callOperation(opFunc, argument);
    }

    /**
     * Update the version-based cache with resolved handler and operation.
     * After this, future calls with same stack version will hit fast path.
     */
    private void updateCache(long version, MezzeRecord handlerRecord, MezzeFunction operation) {
        CompilerDirectives.transferToInterpreterAndInvalidate();

        cachedStackVersion = version;
        cachedHandlerRecord = handlerRecord;
        cachedOperation = operation;
        cachedCallTarget = operation.getCallTarget();
        cachedClosureValues = operation.getClosureValues();
        cachedClosureLength = cachedClosureValues.length;

        // Create DirectCallNode for JIT inlining
        if (directCallNode == null) {
            directCallNode = insert(DirectCallNode.create(cachedCallTarget));
        } else if (directCallNode.getCallTarget() != cachedCallTarget) {
            // CallTarget changed - replace DirectCallNode
            directCallNode = insert(DirectCallNode.create(cachedCallTarget));
        }
    }

    /**
     * Create a suspended effect call when no handler is available.
     * This implements the lazy effects model.
     * Requires content hash for handler resolution - no effect name fallbacks.
     */
    @TruffleBoundary
    private MezzeSuspended createSuspendedEffectCall(Object argument) {
        // Content hash is required for proper handler resolution
        if (effectContentHash == null || effectContentHash.isEmpty()) {
            throw new RuntimeException("Effect content hash is required for suspended effect call. " +
                "Operation: " + operationName);
        }

        // Create a thunk for the deferred effect operation with content hash
        MezzeThunk thunk = new MezzeThunk(effectName, operationName, argument, effectContentHash);

        // Use content hash as effect identifier (no fallback to name)
        return new MezzeSuspended(
            thunk,
            Collections.singleton(effectContentHash)
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
     * Create a dynamic effect call node with content hash for O(1) lookup.
     *
     * @param effectName The effect name (e.g., "Log", "Random")
     * @param operationName The operation name (e.g., "log", "nextInt")
     * @param argumentNode The argument expression node
     * @param effectContentHash Content hash of the effect (preferred for lookup)
     */
    public static DynamicEffectCallNode create(String effectName, String operationName, MezzeNode argumentNode, String effectContentHash) {
        return new DynamicEffectCallNode(effectName, operationName, argumentNode, effectContentHash);
    }

    /**
     * Create a dynamic effect call node (backward compatible, no content hash).
     *
     * @param effectName The effect name (e.g., "Log", "Random")
     * @param operationName The operation name (e.g., "log", "nextInt")
     * @param argumentNode The argument expression node
     */
    public static DynamicEffectCallNode create(String effectName, String operationName, MezzeNode argumentNode) {
        return new DynamicEffectCallNode(effectName, operationName, argumentNode, null);
    }
}
