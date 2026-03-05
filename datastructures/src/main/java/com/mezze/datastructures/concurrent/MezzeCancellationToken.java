package com.mezze.datastructures.concurrent;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.TruffleObject;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Cancellation token for cooperative cancellation.
 *
 * Supports:
 * - Cancellation status checking
 * - Cancellation callbacks
 * - Child tokens (hierarchical cancellation)
 * - Cancellation reason tracking
 *
 * Thread-safe: Yes
 */
public final class MezzeCancellationToken implements TruffleObject {

    private static final AtomicLong NEXT_ID = new AtomicLong(0);

    private final long id;
    private final AtomicBoolean cancelled;
    private final AtomicReference<String> reason;
    private final ConcurrentHashMap<String, Consumer<MezzeCancellationToken>> callbacks;
    private final CopyOnWriteArrayList<MezzeCancellationToken> children;
    private final MezzeCancellationToken parent;

    /**
     * Creates a new cancellation token.
     */
    public MezzeCancellationToken() {
        this(null);
    }

    /**
     * Creates a child cancellation token.
     */
    private MezzeCancellationToken(MezzeCancellationToken parent) {
        this.id = NEXT_ID.getAndIncrement();
        this.cancelled = new AtomicBoolean(false);
        this.reason = new AtomicReference<>(null);
        this.callbacks = new ConcurrentHashMap<>();
        this.children = new CopyOnWriteArrayList<>();
        this.parent = parent;

        // If parent is already cancelled, start cancelled
        if (parent != null && parent.isCancelled()) {
            this.cancelled.set(true);
            this.reason.set(parent.getReason());
        }
    }

    /**
     * Returns the unique ID of this token.
     */
    public long getId() {
        return id;
    }

    /**
     * Creates a child token.
     * Child tokens are automatically cancelled when this token is cancelled.
     */
    @TruffleBoundary
    public MezzeCancellationToken createChild() {
        MezzeCancellationToken child = new MezzeCancellationToken(this);
        children.add(child);
        return child;
    }

    /**
     * Cancels this token and all children.
     */
    @TruffleBoundary
    public void cancel() {
        cancel(null);
    }

    /**
     * Cancels this token with a reason.
     */
    @TruffleBoundary
    public void cancel(String cancelReason) {
        if (cancelled.compareAndSet(false, true)) {
            reason.set(cancelReason);
            notifyCallbacks();
            cancelChildren();
        }
    }

    private void cancelChildren() {
        for (MezzeCancellationToken child : children) {
            child.cancel(reason.get());
        }
    }

    @TruffleBoundary
    private void notifyCallbacks() {
        for (Consumer<MezzeCancellationToken> callback : callbacks.values()) {
            try {
                callback.accept(this);
            } catch (Exception e) {
                // Log but don't propagate
                System.err.println("Cancellation callback error: " + e.getMessage());
            }
        }
    }

    /**
     * Returns true if this token is cancelled.
     */
    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * Returns the cancellation reason, or null if not cancelled or no reason given.
     */
    public String getReason() {
        return reason.get();
    }

    /**
     * Registers a callback to be invoked on cancellation.
     * If already cancelled, callback is invoked immediately.
     */
    @TruffleBoundary
    public void onCancelled(String key, Consumer<MezzeCancellationToken> callback) {
        callbacks.put(key, callback);
        if (isCancelled()) {
            try {
                callback.accept(this);
            } catch (Exception e) {
                // Ignore
            }
        }
    }

    /**
     * Removes a cancellation callback.
     */
    @TruffleBoundary
    public void removeCallback(String key) {
        callbacks.remove(key);
    }

    /**
     * Returns true if a callback with the given key exists.
     */
    public boolean hasCallback(String key) {
        return callbacks.containsKey(key);
    }

    /**
     * Returns the parent token, or null if this is a root token.
     */
    public MezzeCancellationToken getParent() {
        return parent;
    }

    /**
     * Returns the number of child tokens.
     */
    public int getChildCount() {
        return children.size();
    }

    /**
     * Throws CancelledException if this token is cancelled.
     */
    @TruffleBoundary
    public void throwIfCancelled() {
        if (isCancelled()) {
            throw new CancelledException(reason.get());
        }
    }

    @Override
    public String toString() {
        String status = cancelled.get() ? "cancelled" : "active";
        return "<cancellation-token:" + id + ":" + status + ">";
    }

    @Override
    public int hashCode() {
        return Long.hashCode(id);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof MezzeCancellationToken other)) return false;
        return this.id == other.id;
    }

    /**
     * Exception thrown when an operation is cancelled.
     */
    public static class CancelledException extends RuntimeException {
        public CancelledException(String reason) {
            super(reason != null ? reason : "Operation cancelled");
        }
    }
}
