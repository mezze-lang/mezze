package com.mezze.interpreter.v2.effects;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Cancellation token for structured concurrency support.
 *
 * Provides cooperative cancellation across effect contexts and virtual threads.
 * When cancelled, all registered callbacks are invoked and child tokens
 * are automatically cancelled.
 *
 * Thread-safe: All operations are safe for concurrent use.
 *
 * Usage patterns:
 * 1. Check cancellation status: token.isCancelled()
 * 2. Throw if cancelled: token.throwIfCancelled()
 * 3. Register cleanup callbacks: token.onCancellation(id, callback)
 * 4. Create child tokens: token.createChild()
 */
public final class CancellationToken {

    /**
     * Atomic flag indicating if this token is cancelled.
     */
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /**
     * The reason for cancellation (set when cancel() is called).
     */
    private final AtomicReference<String> cancellationReason = new AtomicReference<>();

    /**
     * Callbacks to invoke when cancelled.
     * Key is callback ID, value is the callback runnable.
     */
    private final ConcurrentHashMap<String, Runnable> cancellationCallbacks = new ConcurrentHashMap<>();

    /**
     * Cancel this token with a reason.
     * All registered callbacks will be invoked.
     *
     * @param reason Human-readable reason for cancellation
     * @return true if this call caused the cancellation, false if already cancelled
     */
    @TruffleBoundary
    public boolean cancel(String reason) {
        if (cancelled.compareAndSet(false, true)) {
            cancellationReason.set(reason != null ? reason : "Operation cancelled");

            // Execute all cancellation callbacks
            for (Runnable callback : cancellationCallbacks.values()) {
                try {
                    callback.run();
                } catch (Exception e) {
                    // Log but don't propagate callback errors
                    System.err.println("Error in cancellation callback: " + e.getMessage());
                }
            }
            cancellationCallbacks.clear();
            return true;
        }
        return false;
    }

    /**
     * Cancel with default reason.
     */
    public boolean cancel() {
        return cancel("Operation cancelled");
    }

    /**
     * Check if this token is cancelled.
     * This is a fast, non-blocking check suitable for hot paths.
     */
    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * Get the cancellation reason.
     *
     * @return The reason string, or null if not cancelled
     */
    public String getCancellationReason() {
        return cancellationReason.get();
    }

    /**
     * Throw EffectCancellationException if this token is cancelled.
     * Use this at yield points to cooperatively check for cancellation.
     */
    public void throwIfCancelled() {
        if (isCancelled()) {
            throw new EffectCancellationException(
                    getCancellationReason() != null ? getCancellationReason() : "Operation cancelled"
            );
        }
    }

    /**
     * Register a callback to be executed when cancelled.
     * If already cancelled, the callback is executed immediately.
     *
     * @param id Unique identifier for this callback (for later removal)
     * @param callback The callback to execute on cancellation
     */
    @TruffleBoundary
    public void onCancellation(String id, Runnable callback) {
        if (isCancelled()) {
            // Already cancelled - execute immediately
            try {
                callback.run();
            } catch (Exception e) {
                System.err.println("Error in cancellation callback: " + e.getMessage());
            }
        } else {
            cancellationCallbacks.put(id, callback);
            // Double-check in case cancellation happened between check and put
            if (isCancelled()) {
                Runnable removed = cancellationCallbacks.remove(id);
                if (removed != null) {
                    try {
                        removed.run();
                    } catch (Exception e) {
                        System.err.println("Error in cancellation callback: " + e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * Remove a cancellation callback.
     *
     * @param id The callback identifier
     */
    @TruffleBoundary
    public void removeCancellationCallback(String id) {
        cancellationCallbacks.remove(id);
    }

    /**
     * Create a child token that is cancelled when this token is cancelled.
     * Useful for structured concurrency where child operations should be
     * cancelled when their parent is cancelled.
     *
     * @return A new child token linked to this parent
     */
    @TruffleBoundary
    public CancellationToken createChild() {
        CancellationToken child = new CancellationToken();

        if (isCancelled()) {
            // Parent already cancelled - cancel child immediately
            child.cancel(getCancellationReason() != null ? getCancellationReason() : "Parent cancelled");
        } else {
            // Register child to be cancelled when parent is cancelled
            String childId = "child-" + System.identityHashCode(child);
            onCancellation(childId, () -> {
                child.cancel(getCancellationReason() != null ? getCancellationReason() : "Parent cancelled");
            });
        }

        return child;
    }

    /**
     * Get the number of registered callbacks.
     * Useful for debugging/testing.
     */
    public int getCallbackCount() {
        return cancellationCallbacks.size();
    }

    @Override
    public String toString() {
        return "CancellationToken{" +
                "cancelled=" + cancelled.get() +
                ", reason='" + cancellationReason.get() + '\'' +
                ", callbacks=" + cancellationCallbacks.size() +
                '}';
    }
}
