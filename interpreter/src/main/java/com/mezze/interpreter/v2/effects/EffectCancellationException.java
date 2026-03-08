package com.mezze.interpreter.v2.effects;

/**
 * Exception thrown when an effect operation is cancelled.
 *
 * This is used for cooperative cancellation in structured concurrency.
 * When a CancellationToken is cancelled and throwIfCancelled() is called,
 * this exception is thrown to unwind the stack.
 */
public final class EffectCancellationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Create a new cancellation exception with the given reason.
     */
    public EffectCancellationException(String reason) {
        super(reason);
    }

    /**
     * Create a new cancellation exception with a cause.
     */
    public EffectCancellationException(String reason, Throwable cause) {
        super(reason, cause);
    }

    /**
     * Override to disable stack trace filling for performance.
     * Cancellation is expected and shouldn't carry full stack traces.
     */
    @Override
    public synchronized Throwable fillInStackTrace() {
        // Don't fill stack trace for performance
        return this;
    }
}
