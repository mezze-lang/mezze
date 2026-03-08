package com.mezze.interpreter.v2.effects;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Internal scope management for tracking and cleanup of concurrent tasks.
 *
 * A ConcurrencyScope tracks all tasks spawned within it and ensures proper
 * cleanup when the scope exits. It supports:
 * - Task registration and tracking
 * - Timeout-based expiration
 * - Cooperative cancellation
 * - Waiting for all tasks to complete
 * - Hierarchical scope management (parent/child relationships)
 *
 * Used by structured concurrency patterns in effect handlers.
 *
 * Thread-safe: All operations are safe for concurrent use from multiple threads.
 */
public final class ConcurrencyScope {

    /**
     * Unique name for this scope.
     */
    @CompilationFinal
    private final String name;

    /**
     * Parent scope (null for root scopes).
     */
    @CompilationFinal
    private final ConcurrencyScope parent;

    /**
     * Optional timeout for the scope.
     */
    @CompilationFinal
    private final Duration timeout;

    /**
     * Map of active tasks (Future -> timestamp when added).
     */
    private final ConcurrentHashMap<Future<?>, Long> tasks = new ConcurrentHashMap<>();

    /**
     * Atomic flag indicating if this scope has been cancelled.
     */
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /**
     * Start time for timeout calculation.
     */
    @CompilationFinal
    private final long startTime;

    /**
     * Cancellation token associated with this scope.
     */
    @CompilationFinal
    private final CancellationToken cancellationToken;

    public ConcurrencyScope(String name, Duration timeout) {
        this.name = name;
        this.parent = null;
        this.timeout = timeout;
        this.startTime = System.nanoTime();
        this.cancellationToken = new CancellationToken();
    }

    public ConcurrencyScope(String name) {
        this(name, (Duration) null);
    }

    /**
     * Create a child scope under a parent scope.
     */
    public ConcurrencyScope(String name, ConcurrencyScope parent) {
        this.name = name;
        this.parent = parent;
        this.timeout = null;
        this.startTime = System.nanoTime();
        // Child uses parent's token as basis for its own
        this.cancellationToken = parent != null ? parent.cancellationToken.createChild() : new CancellationToken();
    }

    /**
     * Create a scope with auto-generated name.
     */
    public static ConcurrencyScope create() {
        return new ConcurrencyScope("scope-" + System.nanoTime());
    }

    /**
     * Create a scope with timeout.
     */
    public static ConcurrencyScope withTimeout(Duration timeout) {
        return new ConcurrencyScope("scope-" + System.nanoTime(), timeout);
    }

    // ========================
    // Task Management
    // ========================

    /**
     * Add a task to this scope.
     * The task will be tracked and cancelled if the scope is cancelled.
     *
     * @param future The future representing the task
     */
    @TruffleBoundary
    public void addTask(Future<?> future) {
        if (!cancelled.get()) {
            tasks.put(future, System.nanoTime());

            // If scope was cancelled between check and put, cancel the task
            if (cancelled.get()) {
                future.cancel(true);
                tasks.remove(future);
            }
        } else {
            // Scope already cancelled - cancel immediately
            future.cancel(true);
        }
    }

    /**
     * Remove a completed task from tracking.
     *
     * @param future The future to remove
     */
    @TruffleBoundary
    public void removeTask(Future<?> future) {
        tasks.remove(future);
    }

    /**
     * Get the number of active tasks.
     */
    public int getActiveTasks() {
        return tasks.size();
    }

    /**
     * Get all currently tracked futures.
     */
    @TruffleBoundary
    public List<Future<?>> getAllFutures() {
        return List.copyOf(tasks.keySet());
    }

    // ========================
    // Cancellation
    // ========================

    /**
     * Cancel all tasks in this scope.
     * This is idempotent - multiple calls have no additional effect.
     */
    @TruffleBoundary
    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            cancellationToken.cancel("Scope cancelled: " + name);

            for (Future<?> future : tasks.keySet()) {
                future.cancel(true);
            }
            tasks.clear();
        }
    }

    /**
     * Cancel with a specific reason.
     */
    @TruffleBoundary
    public void cancel(String reason) {
        if (cancelled.compareAndSet(false, true)) {
            cancellationToken.cancel(reason);

            for (Future<?> future : tasks.keySet()) {
                future.cancel(true);
            }
            tasks.clear();
        }
    }

    /**
     * Check if this scope has been cancelled (directly or via parent).
     */
    public boolean isCancelled() {
        if (cancelled.get()) {
            return true;
        }
        if (parent != null) {
            return parent.isCancelled();
        }
        return false;
    }

    /**
     * Get the cancellation token for this scope.
     */
    public CancellationToken getCancellationToken() {
        return cancellationToken;
    }

    // ========================
    // Parent/Child
    // ========================

    /**
     * Get the scope name.
     */
    public String getName() {
        return name;
    }

    /**
     * Get the parent scope (may be null for root scopes).
     */
    public ConcurrencyScope getParent() {
        return parent;
    }

    /**
     * Create a child scope under this scope.
     */
    public ConcurrencyScope createChildScope(String childName) {
        return new ConcurrencyScope(childName, this);
    }

    // ========================
    // Timeout
    // ========================

    /**
     * Check if this scope has expired due to timeout.
     */
    public boolean isExpired() {
        if (timeout == null) {
            return false;
        }
        long elapsed = System.nanoTime() - startTime;
        return elapsed > timeout.toNanos();
    }

    /**
     * Get the remaining time before timeout.
     *
     * @return Duration remaining, or null if no timeout set
     */
    public Duration getRemainingTime() {
        if (timeout == null) {
            return null;
        }
        long elapsed = System.nanoTime() - startTime;
        long remaining = timeout.toNanos() - elapsed;
        return remaining > 0 ? Duration.ofNanos(remaining) : Duration.ZERO;
    }

    /**
     * Get the elapsed time since scope creation.
     */
    public Duration getElapsedTime() {
        return Duration.ofNanos(System.nanoTime() - startTime);
    }

    public Duration getTimeout() {
        return timeout;
    }

    // ========================
    // Waiting
    // ========================

    /**
     * Wait for all tasks to complete.
     *
     * @throws InterruptedException if the current thread is interrupted
     */
    @TruffleBoundary
    public void awaitAll() throws InterruptedException {
        for (Future<?> future : tasks.keySet()) {
            if (!future.isDone()) {
                try {
                    future.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    // Task failed - continue waiting for others
                } catch (java.util.concurrent.CancellationException e) {
                    // Task was cancelled - continue
                }
            }
        }
    }

    /**
     * Wait for all tasks with timeout.
     *
     * @param timeout Maximum time to wait
     * @throws InterruptedException if interrupted
     * @throws TimeoutException if timeout expires before all tasks complete
     */
    @TruffleBoundary
    public void awaitAll(Duration timeout) throws InterruptedException, TimeoutException {
        long deadline = System.nanoTime() + timeout.toNanos();

        for (Future<?> future : tasks.keySet()) {
            if (!future.isDone()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new TimeoutException("Scope timed out waiting for tasks");
                }
                try {
                    future.get(remaining, TimeUnit.NANOSECONDS);
                } catch (java.util.concurrent.ExecutionException e) {
                    // Task failed - continue
                } catch (java.util.concurrent.CancellationException e) {
                    // Task was cancelled - continue
                }
            }
        }
    }

    /**
     * Wait for any task to complete.
     *
     * @return The first completed future, or null if no tasks
     * @throws InterruptedException if interrupted
     */
    @TruffleBoundary
    public Future<?> awaitAny() throws InterruptedException {
        while (true) {
            for (Future<?> future : tasks.keySet()) {
                if (future.isDone()) {
                    return future;
                }
            }
            if (tasks.isEmpty()) {
                return null;
            }
            Thread.sleep(1); // Small yield
        }
    }

    // ========================
    // Cleanup
    // ========================

    /**
     * Clean up completed tasks from tracking.
     */
    @TruffleBoundary
    public void cleanup() {
        tasks.entrySet().removeIf(entry -> entry.getKey().isDone());
    }

    /**
     * Clean up and cancel all pending tasks.
     * Call this when the scope exits.
     */
    @TruffleBoundary
    public void close() {
        cancel();
    }

    @Override
    public String toString() {
        return "ConcurrencyScope{" +
                "name='" + name + '\'' +
                ", tasks=" + tasks.size() +
                ", cancelled=" + cancelled.get() +
                ", elapsed=" + getElapsedTime().toMillis() + "ms" +
                (timeout != null ? ", timeout=" + timeout.toMillis() + "ms" : "") +
                '}';
    }

    /**
     * Get detailed debug information.
     */
    @TruffleBoundary
    public String toDebugString() {
        StringBuilder sb = new StringBuilder();
        sb.append("ConcurrencyScope: ").append(name).append("\n");
        sb.append("  Tasks: ").append(tasks.size()).append("\n");
        sb.append("  Cancelled: ").append(cancelled.get()).append("\n");
        sb.append("  Elapsed: ").append(getElapsedTime().toMillis()).append("ms\n");
        if (timeout != null) {
            sb.append("  Timeout: ").append(timeout.toMillis()).append("ms\n");
            sb.append("  Expired: ").append(isExpired()).append("\n");
            Duration remaining = getRemainingTime();
            sb.append("  Remaining: ").append(remaining != null ? remaining.toMillis() + "ms" : "N/A").append("\n");
        }
        if (parent != null) {
            sb.append("  Parent: ").append(parent.getName()).append("\n");
        }
        sb.append("  Task States:\n");
        for (Future<?> future : tasks.keySet()) {
            sb.append("    - ").append(future.isDone() ? "DONE" : "RUNNING")
                    .append(future.isCancelled() ? " (CANCELLED)" : "").append("\n");
        }
        return sb.toString();
    }
}
