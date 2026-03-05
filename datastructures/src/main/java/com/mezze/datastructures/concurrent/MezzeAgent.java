package com.mezze.datastructures.concurrent;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.TruffleObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Asynchronous actor-like state container.
 *
 * Agents provide async state management:
 * - State updates are queued and processed serially
 * - send() returns immediately (fire-and-forget)
 * - Reads (deref) are always synchronous
 * - Errors are captured and can be retrieved
 *
 * Similar to Clojure agents.
 *
 * Thread-safe: Yes
 */
public final class MezzeAgent<T> implements TruffleObject {

    private static final AtomicLong NEXT_ID = new AtomicLong(0);

    private final long id;
    private final AtomicReference<T> state;
    private final AtomicReference<Throwable> error;
    private final ExecutorService executor;
    private volatile Consumer<Throwable> errorHandler;
    private volatile boolean shutdown;

    /**
     * Creates an agent with the given initial state.
     */
    public MezzeAgent(T initialState) {
        this.id = NEXT_ID.getAndIncrement();
        this.state = new AtomicReference<>(initialState);
        this.error = new AtomicReference<>(null);
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "mezze-agent-" + id);
            t.setDaemon(true);
            return t;
        });
        this.shutdown = false;
    }

    /**
     * Returns the unique ID of this agent.
     */
    public long getId() {
        return id;
    }

    /**
     * Reads the current state.
     */
    public T deref() {
        return state.get();
    }

    /**
     * Sends an action to be applied asynchronously.
     * Returns immediately.
     */
    @TruffleBoundary
    public void send(Function<T, T> action) {
        if (shutdown) {
            throw new IllegalStateException("Agent is shut down");
        }
        if (error.get() != null && errorHandler == null) {
            throw new IllegalStateException("Agent is in error state: " + error.get().getMessage());
        }

        executor.submit(() -> {
            try {
                T current = state.get();
                T newValue = action.apply(current);
                state.set(newValue);
                error.set(null); // Clear error on success
            } catch (Throwable t) {
                error.set(t);
                Consumer<Throwable> handler = errorHandler;
                if (handler != null) {
                    try {
                        handler.accept(t);
                    } catch (Throwable ignored) {
                        // Ignore handler errors
                    }
                }
            }
        });
    }

    /**
     * Sends a simple state update (not a function).
     */
    @TruffleBoundary
    public void send(T newValue) {
        send(oldValue -> newValue);
    }

    /**
     * Waits for all pending actions to complete.
     * @return true if completed, false if timeout
     */
    @TruffleBoundary
    public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
        // Submit a marker task and wait for it
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        executor.submit(latch::countDown);
        return latch.await(timeout, unit);
    }

    /**
     * Waits for all pending actions to complete (indefinitely).
     */
    @TruffleBoundary
    public void await() throws InterruptedException {
        await(Long.MAX_VALUE, TimeUnit.DAYS);
    }

    /**
     * Returns the last error, or null if no error.
     */
    public Throwable getError() {
        return error.get();
    }

    /**
     * Clears the error state.
     */
    public void clearError() {
        error.set(null);
    }

    /**
     * Returns true if the agent is in an error state.
     */
    public boolean hasError() {
        return error.get() != null;
    }

    /**
     * Sets the error handler.
     */
    public void setErrorHandler(Consumer<Throwable> handler) {
        this.errorHandler = handler;
    }

    /**
     * Removes the error handler.
     */
    public void removeErrorHandler() {
        this.errorHandler = null;
    }

    /**
     * Restarts the agent with a new state, clearing errors.
     */
    @TruffleBoundary
    public void restart(T newState) {
        send(old -> newState);
        error.set(null);
    }

    /**
     * Shuts down the agent executor.
     * No more sends allowed after this.
     */
    @TruffleBoundary
    public void shutdown() {
        shutdown = true;
        executor.shutdown();
    }

    /**
     * Returns true if the agent is shut down.
     */
    public boolean isShutdown() {
        return shutdown;
    }

    @Override
    public String toString() {
        String status = shutdown ? "shutdown" : (error.get() != null ? "error" : "active");
        return "<agent:" + id + ":" + status + ">";
    }

    @Override
    public int hashCode() {
        return Long.hashCode(id);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof MezzeAgent<?> other)) return false;
        return this.id == other.id;
    }
}
