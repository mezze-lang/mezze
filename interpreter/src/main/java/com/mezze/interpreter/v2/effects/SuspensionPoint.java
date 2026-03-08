package com.mezze.interpreter.v2.effects;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.util.function.BiFunction;

/**
 * Suspension point for abort/resume functionality in effect handlers.
 *
 * When an effect operation suspends execution, it creates a SuspensionPoint
 * that captures:
 * - The state machine ID and current state
 * - The effect context at the point of suspension
 * - Callbacks for resuming or aborting execution
 *
 * This enables delimited continuations and algebraic effect patterns.
 *
 * JIT Optimization:
 * - Uses @CompilationFinal for immutable fields
 * - Callbacks are stored as BiFunction for efficient invocation
 */
public final class SuspensionPoint {

    /**
     * Unique identifier for this suspension point.
     */
    @CompilationFinal
    private final String id;

    /**
     * State machine ID (for correlating with state machine execution).
     */
    @CompilationFinal
    private final String stateMachineId;

    /**
     * Current state ID within the state machine.
     */
    @CompilationFinal
    private final String currentStateId;

    /**
     * The effect context at the point of suspension.
     */
    @CompilationFinal
    private final EffectContext context;

    /**
     * Callback to continue execution with a value.
     * Parameters: (resumeValue, currentContext) -> newContext
     */
    private final BiFunction<Object, EffectContext, EffectContext> continuationCallback;

    /**
     * Callback to abort execution with an error.
     * Parameters: (error, currentContext) -> newContext
     */
    private final BiFunction<Throwable, EffectContext, EffectContext> abortCallback;

    /**
     * Timestamp when this suspension point was created.
     */
    @CompilationFinal
    private final long timestamp;

    public SuspensionPoint(
            String id,
            String stateMachineId,
            String currentStateId,
            EffectContext context,
            BiFunction<Object, EffectContext, EffectContext> continuationCallback,
            BiFunction<Throwable, EffectContext, EffectContext> abortCallback
    ) {
        this.id = id;
        this.stateMachineId = stateMachineId;
        this.currentStateId = currentStateId;
        this.context = context;
        this.continuationCallback = continuationCallback;
        this.abortCallback = abortCallback;
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * Resume execution with a value.
     *
     * @param value The value to resume with
     * @param currentContext The current effect context
     * @return The new effect context after resumption
     */
    @TruffleBoundary
    public EffectContext resume(Object value, EffectContext currentContext) {
        if (continuationCallback == null) {
            throw new EffectExecutionException(
                    "Cannot resume suspension point " + id + ": no continuation callback",
                    currentContext
            );
        }
        return continuationCallback.apply(value, currentContext);
    }

    /**
     * Abort execution with an error.
     *
     * @param error The error to abort with
     * @param currentContext The current effect context
     * @return The new effect context after abort
     */
    @TruffleBoundary
    public EffectContext abort(Throwable error, EffectContext currentContext) {
        if (abortCallback == null) {
            throw new EffectExecutionException(
                    "Cannot abort suspension point " + id + ": no abort callback",
                    currentContext
            );
        }
        return abortCallback.apply(error, currentContext);
    }

    // ========================
    // Getters
    // ========================

    public String getId() {
        return id;
    }

    public String getStateMachineId() {
        return stateMachineId;
    }

    public String getCurrentStateId() {
        return currentStateId;
    }

    public EffectContext getContext() {
        return context;
    }

    public long getTimestamp() {
        return timestamp;
    }

    /**
     * Get the age of this suspension point in milliseconds.
     */
    public long getAge() {
        return System.currentTimeMillis() - timestamp;
    }

    @Override
    public String toString() {
        return "SuspensionPoint{" +
                "id='" + id + '\'' +
                ", stateMachine='" + stateMachineId + '\'' +
                ", state='" + currentStateId + '\'' +
                ", age=" + getAge() + "ms" +
                '}';
    }

    // ========================
    // Factory Methods
    // ========================

    /**
     * Create a suspension point with only a resume callback (no abort).
     */
    public static SuspensionPoint resumeOnly(
            String id,
            String stateMachineId,
            String currentStateId,
            EffectContext context,
            BiFunction<Object, EffectContext, EffectContext> continuationCallback
    ) {
        return new SuspensionPoint(
                id,
                stateMachineId,
                currentStateId,
                context,
                continuationCallback,
                null
        );
    }

    /**
     * Create a simple suspension point for basic effect handling.
     */
    public static SuspensionPoint simple(
            String id,
            EffectContext context,
            BiFunction<Object, EffectContext, EffectContext> continuationCallback
    ) {
        return new SuspensionPoint(
                id,
                "default",
                "suspended",
                context,
                continuationCallback,
                (error, ctx) -> {
                    throw new EffectExecutionException(
                            "Effect aborted: " + error.getMessage(),
                            ctx,
                            error
                    );
                }
        );
    }
}
