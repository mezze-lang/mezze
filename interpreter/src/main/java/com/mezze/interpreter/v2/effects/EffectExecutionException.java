package com.mezze.interpreter.v2.effects;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

/**
 * Exception thrown when an effect execution fails.
 *
 * This exception carries context information about the effect execution state,
 * making it easier to debug effect-related issues.
 *
 * Truffle optimization: Extends RuntimeException so no overhead from checked exceptions.
 */
public final class EffectExecutionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * The effect context at the time of the error.
     */
    private final transient EffectContext effectContext;

    public EffectExecutionException(String message, EffectContext context) {
        super(message);
        this.effectContext = context;
    }

    public EffectExecutionException(String message, EffectContext context, Throwable cause) {
        super(message, cause);
        this.effectContext = context;
    }

    /**
     * Get the effect context at the time of the error.
     *
     * @return The effect context, or null if not available
     */
    public EffectContext getEffectContext() {
        return effectContext;
    }

    /**
     * Get a detailed message including context information.
     */
    @TruffleBoundary
    @Override
    public String getMessage() {
        String baseMessage = super.getMessage();
        if (effectContext == null) {
            return baseMessage;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(baseMessage);
        sb.append("\n\nEffect Context:");
        sb.append("\n  Execution ID: ").append(effectContext.getExecutionId());
        sb.append("\n  Available Handlers: ").append(effectContext.getAvailableHandlers());
        sb.append("\n  Available Effects: ").append(effectContext.getAvailableEffects());
        sb.append("\n  Cancelled: ").append(effectContext.isCancelled());

        return sb.toString();
    }

    /**
     * Create an exception for when a handler is not found.
     */
    public static EffectExecutionException handlerNotFound(String effectName, EffectContext context) {
        return new EffectExecutionException(
                "No handler found for effect '" + effectName + "'. " +
                        "Available handlers: " + context.getAvailableHandlers(),
                context
        );
    }

    /**
     * Create an exception for when an operation is not found in a handler.
     */
    public static EffectExecutionException operationNotFound(
            String operationName,
            String effectName,
            EffectContext context
    ) {
        return new EffectExecutionException(
                "Operation '" + operationName + "' not found in effect handler '" + effectName + "'",
                context
        );
    }

    /**
     * Create an exception for suspension point not found.
     */
    public static EffectExecutionException suspensionPointNotFound(String suspensionId, EffectContext context) {
        return new EffectExecutionException(
                "Suspension point not found: " + suspensionId,
                context
        );
    }

    /**
     * Create an exception for effect already handled.
     */
    public static EffectExecutionException effectAlreadyHandled(String effectName, EffectContext context) {
        return new EffectExecutionException(
                "Effect '" + effectName + "' is already being handled in this scope",
                context
        );
    }

    /**
     * Create an exception for invalid effect operation.
     */
    public static EffectExecutionException invalidOperation(String message, EffectContext context) {
        return new EffectExecutionException(message, context);
    }
}
