package com.mezze.interpreter.v2.effects;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Execution context that travels with virtual threads via thread-locals.
 *
 * Tracks the execution hierarchy (parent-child relationships) and provides
 * metadata propagation across spawned threads.
 *
 * JIT Optimization:
 * - Immutable design for safe sharing across threads
 * - @CompilationFinal for fields that don't change after construction
 * - Minimal allocations in hot paths
 *
 * Usage:
 * - Created when spawning virtual threads
 * - Propagated via thread-local storage (will upgrade to ScopedValue when available)
 * - Used for debugging, tracing, and structured concurrency
 */
public final class ExecutionContext {

    /**
     * Thread ID of the executing thread.
     */
    @CompilationFinal
    private final long threadId;

    /**
     * Human-readable name for this execution context/thread.
     */
    @CompilationFinal
    private final String threadName;

    /**
     * Parent execution context (null for root contexts).
     */
    @CompilationFinal
    private final ExecutionContext parentContext;

    /**
     * Start time in nanoseconds (for elapsed time calculation).
     */
    @CompilationFinal
    private final long startTime;

    /**
     * Metadata attached to this context (e.g., trace IDs, request context).
     */
    private final Map<String, Object> metadata;

    /**
     * Thread-local for current execution context.
     * Will be upgraded to ScopedValue when Project Loom ScopedValue is stable.
     */
    private static final ThreadLocal<ExecutionContext> CURRENT_CONTEXT = new ThreadLocal<>();

    public ExecutionContext(
            long threadId,
            String threadName,
            ExecutionContext parentContext,
            Map<String, Object> metadata
    ) {
        this.threadId = threadId;
        this.threadName = threadName;
        this.parentContext = parentContext;
        this.startTime = System.nanoTime();
        this.metadata = metadata != null ?
                Collections.unmodifiableMap(new HashMap<>(metadata)) :
                Collections.emptyMap();
    }

    /**
     * Create a new execution context for the current thread.
     */
    public static ExecutionContext create(String name) {
        return new ExecutionContext(
                Thread.currentThread().threadId(),
                name,
                null,
                Collections.emptyMap()
        );
    }

    /**
     * Create a child execution context.
     */
    public ExecutionContext createChild(String childName) {
        return new ExecutionContext(
                Thread.currentThread().threadId(),
                childName,
                this,
                this.metadata
        );
    }

    /**
     * Create a child execution context with additional metadata.
     */
    @TruffleBoundary
    public ExecutionContext createChild(String childName, Map<String, Object> additionalMetadata) {
        Map<String, Object> mergedMetadata = new HashMap<>(this.metadata);
        if (additionalMetadata != null) {
            mergedMetadata.putAll(additionalMetadata);
        }
        return new ExecutionContext(
                Thread.currentThread().threadId(),
                childName,
                this,
                mergedMetadata
        );
    }

    /**
     * Create a new context with additional metadata.
     */
    @TruffleBoundary
    public ExecutionContext withMetadata(String key, Object value) {
        Map<String, Object> newMetadata = new HashMap<>(this.metadata);
        newMetadata.put(key, value);
        return new ExecutionContext(threadId, threadName, parentContext, newMetadata);
    }

    // ========================
    // Thread-Local Management
    // ========================

    /**
     * Get the current execution context for this thread.
     *
     * @return The current context, or null if none set
     */
    public static ExecutionContext current() {
        return CURRENT_CONTEXT.get();
    }

    /**
     * Set the current execution context for this thread.
     *
     * @param context The context to set (null to clear)
     */
    public static void setCurrent(ExecutionContext context) {
        if (context != null) {
            CURRENT_CONTEXT.set(context);
        } else {
            CURRENT_CONTEXT.remove();
        }
    }

    /**
     * Run a task with this context set as current.
     *
     * @param task The task to run
     * @param <T> Return type
     * @return The task result
     */
    @TruffleBoundary
    public <T> T runWith(java.util.concurrent.Callable<T> task) throws Exception {
        ExecutionContext previous = CURRENT_CONTEXT.get();
        CURRENT_CONTEXT.set(this);
        try {
            return task.call();
        } finally {
            if (previous != null) {
                CURRENT_CONTEXT.set(previous);
            } else {
                CURRENT_CONTEXT.remove();
            }
        }
    }

    /**
     * Run a task with this context set as current (no return value).
     */
    @TruffleBoundary
    public void runWith(Runnable task) {
        ExecutionContext previous = CURRENT_CONTEXT.get();
        CURRENT_CONTEXT.set(this);
        try {
            task.run();
        } finally {
            if (previous != null) {
                CURRENT_CONTEXT.set(previous);
            } else {
                CURRENT_CONTEXT.remove();
            }
        }
    }

    // ========================
    // Getters
    // ========================

    public long getThreadId() {
        return threadId;
    }

    public String getThreadName() {
        return threadName;
    }

    public ExecutionContext getParentContext() {
        return parentContext;
    }

    public long getStartTime() {
        return startTime;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /**
     * Get a specific metadata value.
     */
    public Object getMetadata(String key) {
        return metadata.get(key);
    }

    /**
     * Get the elapsed time since this context was created.
     */
    public Duration getElapsedTime() {
        return Duration.ofNanos(System.nanoTime() - startTime);
    }

    /**
     * Get the elapsed time in milliseconds.
     */
    public long getElapsedMillis() {
        return (System.nanoTime() - startTime) / 1_000_000;
    }

    /**
     * Get the depth of this context in the hierarchy.
     * Root contexts have depth 0.
     */
    public int getDepth() {
        int depth = 0;
        ExecutionContext ctx = parentContext;
        while (ctx != null) {
            depth++;
            ctx = ctx.parentContext;
        }
        return depth;
    }

    /**
     * Check if this context is a descendant of another.
     */
    public boolean isDescendantOf(ExecutionContext ancestor) {
        ExecutionContext ctx = parentContext;
        while (ctx != null) {
            if (ctx == ancestor) {
                return true;
            }
            ctx = ctx.parentContext;
        }
        return false;
    }

    /**
     * Get the root context (the topmost parent).
     */
    public ExecutionContext getRoot() {
        ExecutionContext ctx = this;
        while (ctx.parentContext != null) {
            ctx = ctx.parentContext;
        }
        return ctx;
    }

    @Override
    public String toString() {
        return "ExecutionContext{" +
                "threadId=" + threadId +
                ", name='" + threadName + '\'' +
                ", depth=" + getDepth() +
                ", elapsed=" + getElapsedMillis() + "ms" +
                '}';
    }

    /**
     * Get a detailed debug string showing the full context hierarchy.
     */
    @TruffleBoundary
    public String toDebugString() {
        StringBuilder sb = new StringBuilder();
        sb.append("ExecutionContext:\n");
        sb.append("  Thread ID: ").append(threadId).append("\n");
        sb.append("  Name: ").append(threadName).append("\n");
        sb.append("  Elapsed: ").append(getElapsedMillis()).append("ms\n");
        sb.append("  Depth: ").append(getDepth()).append("\n");
        sb.append("  Metadata: ").append(metadata).append("\n");

        if (parentContext != null) {
            sb.append("  Parent Chain:\n");
            ExecutionContext ctx = parentContext;
            int indent = 4;
            while (ctx != null) {
                sb.append(" ".repeat(indent)).append("-> ").append(ctx.threadName)
                        .append(" (").append(ctx.threadId).append(")\n");
                ctx = ctx.parentContext;
                indent += 2;
            }
        }

        return sb.toString();
    }
}
