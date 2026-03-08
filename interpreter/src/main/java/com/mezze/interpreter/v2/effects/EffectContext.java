package com.mezze.interpreter.v2.effects;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Unified, immutable effect context for handler resolution and execution state.
 * This is the V2/Truffle-optimized version of the V1 EffectContext.
 *
 * Optimized for Truffle/GraalVM JIT compilation:
 * - Uses @CompilationFinal for fields that are constant after construction
 * - Thread-safe for concurrent contexts via immutable design
 * - Minimizes allocations in hot paths
 * - Provides O(1) handler resolution with clear precedence rules
 *
 * Key concepts:
 * - Handler resolution with stack-based precedence (perform > active > global)
 * - Suspension points for abort/resume functionality
 * - Cancellation tokens for structured concurrency
 * - Execution context hierarchy for virtual thread management
 */
public final class EffectContext {

    // ========================
    // Core Handler State
    // ========================

    /**
     * Set of effect names declared in the current scope.
     */
    @CompilationFinal(dimensions = 1)
    private final String[] declaredEffects;

    /**
     * Active handlers in current scope (effect name -> handler record).
     */
    private final Map<String, Object> activeHandlers;

    /**
     * Handler stack for perform expressions - each entry is a map of handlers.
     * Innermost handlers (last in list) have highest priority.
     */
    private final List<Map<String, Object>> handlerStack;

    /**
     * Global/fallback handlers.
     */
    private final Map<String, Object> globalHandlers;

    // ========================
    // Local Variables
    // ========================

    /**
     * Local variables in the effect scope.
     */
    private final Map<String, Object> localVariables;

    // ========================
    // Suspension & Continuation
    // ========================

    /**
     * Suspension points for abort/resume (suspension ID -> SuspensionPoint).
     */
    private final Map<String, SuspensionPoint> suspensionPoints;

    /**
     * Virtual thread futures for async operations.
     */
    private final Map<String, Future<?>> virtualThreadFutures;

    // ========================
    // Concurrency Management
    // ========================

    /**
     * Structured concurrency scope (may be null).
     */
    @CompilationFinal
    private final ConcurrencyScope concurrencyScope;

    /**
     * Execution context for virtual thread management (may be null).
     */
    @CompilationFinal
    private final ExecutionContext executionContext;

    /**
     * Cancellation token for structured concurrency support.
     */
    @CompilationFinal
    private final CancellationToken cancellationToken;

    /**
     * Unique execution ID for this context.
     */
    @CompilationFinal
    private final String executionId;

    // ========================
    // Constructors
    // ========================

    /**
     * Private constructor - use factory methods or Builder.
     */
    private EffectContext(
            String[] declaredEffects,
            Map<String, Object> activeHandlers,
            List<Map<String, Object>> handlerStack,
            Map<String, Object> globalHandlers,
            Map<String, Object> localVariables,
            Map<String, SuspensionPoint> suspensionPoints,
            Map<String, Future<?>> virtualThreadFutures,
            ConcurrencyScope concurrencyScope,
            ExecutionContext executionContext,
            CancellationToken cancellationToken,
            String executionId
    ) {
        this.declaredEffects = declaredEffects;
        this.activeHandlers = activeHandlers;
        this.handlerStack = handlerStack;
        this.globalHandlers = globalHandlers;
        this.localVariables = localVariables;
        this.suspensionPoints = suspensionPoints;
        this.virtualThreadFutures = virtualThreadFutures;
        this.concurrencyScope = concurrencyScope;
        this.executionContext = executionContext;
        this.cancellationToken = cancellationToken;
        this.executionId = executionId;
    }

    // ========================
    // Factory Methods
    // ========================

    /**
     * Create an empty effect context.
     */
    public static EffectContext empty() {
        return new EffectContext(
                new String[0],
                Collections.emptyMap(),
                Collections.emptyList(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                null,
                null,
                new CancellationToken(),
                UUID.randomUUID().toString()
        );
    }

    /**
     * Create a context with basic setup.
     */
    public static EffectContext create(
            Set<String> declaredEffects,
            Map<String, Object> activeHandlers,
            Map<String, Object> globalHandlers
    ) {
        return new EffectContext(
                declaredEffects.toArray(new String[0]),
                new HashMap<>(activeHandlers),
                new ArrayList<>(),
                new HashMap<>(globalHandlers),
                new HashMap<>(),
                new ConcurrentHashMap<>(),
                new ConcurrentHashMap<>(),
                null,
                null,
                new CancellationToken(),
                UUID.randomUUID().toString()
        );
    }

    /**
     * Create a new Builder for constructing EffectContext.
     */
    public static Builder builder() {
        return new Builder();
    }

    // ========================
    // Handler Management
    // ========================

    /**
     * Add handlers to the stack (for perform expressions).
     * Returns a new immutable context.
     */
    @TruffleBoundary
    public EffectContext withHandlers(Map<String, Object> handlers) {
        List<Map<String, Object>> newStack = new ArrayList<>(this.handlerStack);
        newStack.add(new HashMap<>(handlers));
        return copyWith(b -> b.handlerStack(newStack));
    }

    /**
     * Resolve handler with clear precedence rules:
     * 1. Handler stack (perform expressions) - highest priority (innermost first)
     * 2. Active handlers (current scope)
     * 3. Global handlers (fallback)
     */
    @TruffleBoundary
    public Object resolveHandler(String effectName) {
        // Check handler stack from innermost to outermost
        for (int i = handlerStack.size() - 1; i >= 0; i--) {
            Object handler = handlerStack.get(i).get(effectName);
            if (handler != null) {
                return handler;
            }
        }
        // Check active handlers
        Object handler = activeHandlers.get(effectName);
        if (handler != null) {
            return handler;
        }
        // Check global handlers
        return globalHandlers.get(effectName);
    }

    /**
     * Check if a handler is available for an effect.
     */
    @TruffleBoundary
    public boolean hasHandler(String effectName) {
        return resolveHandler(effectName) != null;
    }

    /**
     * Check if an effect is declared.
     */
    public boolean isEffectDeclared(String effectName) {
        for (String effect : declaredEffects) {
            if (effect.equals(effectName)) {
                return true;
            }
        }
        return false;
    }

    // ========================
    // Variable Management
    // ========================

    /**
     * Add a local variable. Returns a new immutable context.
     */
    @TruffleBoundary
    public EffectContext withVariable(String name, Object value) {
        Map<String, Object> newVars = new HashMap<>(this.localVariables);
        newVars.put(name, value);
        return copyWith(b -> b.localVariables(newVars));
    }

    /**
     * Add multiple variables. Returns a new immutable context.
     */
    @TruffleBoundary
    public EffectContext withVariables(Map<String, Object> variables) {
        Map<String, Object> newVars = new HashMap<>(this.localVariables);
        newVars.putAll(variables);
        return copyWith(b -> b.localVariables(newVars));
    }

    /**
     * Get a local variable.
     */
    @TruffleBoundary
    public Object getVariable(String name) {
        return localVariables.get(name);
    }

    // ========================
    // Suspension Point Management
    // ========================

    /**
     * Add a suspension point. Returns a new immutable context.
     */
    @TruffleBoundary
    public EffectContext withSuspensionPoint(SuspensionPoint suspensionPoint) {
        Map<String, SuspensionPoint> newPoints = new ConcurrentHashMap<>(this.suspensionPoints);
        newPoints.put(suspensionPoint.getId(), suspensionPoint);
        return copyWith(b -> b.suspensionPoints(newPoints));
    }

    /**
     * Resume execution from a suspension point.
     */
    @TruffleBoundary
    public EffectContext resume(String suspensionId, Object value) {
        SuspensionPoint point = suspensionPoints.get(suspensionId);
        if (point == null) {
            throw new EffectExecutionException(
                    "Suspension point not found: " + suspensionId,
                    this
            );
        }
        return point.resume(value, this);
    }

    /**
     * Get a suspension point by ID.
     */
    public SuspensionPoint getSuspensionPoint(String id) {
        return suspensionPoints.get(id);
    }

    // ========================
    // Virtual Thread Futures
    // ========================

    /**
     * Add a virtual thread future. Returns a new immutable context.
     */
    @TruffleBoundary
    public EffectContext withVirtualThreadFuture(String id, Future<?> future) {
        Map<String, Future<?>> newFutures = new ConcurrentHashMap<>(this.virtualThreadFutures);
        newFutures.put(id, future);
        return copyWith(b -> b.virtualThreadFutures(newFutures));
    }

    /**
     * Get a virtual thread future by ID.
     */
    public Future<?> getVirtualThreadFuture(String id) {
        return virtualThreadFutures.get(id);
    }

    // ========================
    // Concurrency Management
    // ========================

    /**
     * Add a concurrency scope. Returns a new immutable context.
     */
    public EffectContext withConcurrencyScope(ConcurrencyScope scope) {
        return copyWith(b -> b.concurrencyScope(scope));
    }

    /**
     * Add execution context. Returns a new immutable context.
     */
    public EffectContext withExecutionContext(ExecutionContext context) {
        return copyWith(b -> b.executionContext(context));
    }

    /**
     * Create a context with a new cancellation token.
     */
    public EffectContext withCancellationToken(CancellationToken token) {
        return copyWith(b -> b.cancellationToken(token));
    }

    /**
     * Get the current concurrency scope.
     */
    public ConcurrencyScope getConcurrencyScope() {
        return concurrencyScope;
    }

    /**
     * Get the current execution context.
     */
    public ExecutionContext getExecutionContext() {
        return executionContext;
    }

    /**
     * Get the cancellation token.
     */
    public CancellationToken getCancellationToken() {
        return cancellationToken;
    }

    /**
     * Get the execution ID.
     */
    public String getExecutionId() {
        return executionId;
    }

    // ========================
    // Cancellation
    // ========================

    /**
     * Cancel all operations in this context.
     */
    @TruffleBoundary
    public void cancel(String reason) {
        cancellationToken.cancel(reason);

        if (concurrencyScope != null) {
            concurrencyScope.cancel();
        }

        // Cancel all virtual thread futures
        for (Future<?> future : virtualThreadFutures.values()) {
            future.cancel(true);
        }
    }

    /**
     * Check if this context has been cancelled.
     */
    public boolean isCancelled() {
        return cancellationToken.isCancelled();
    }

    /**
     * Throw if cancelled.
     */
    public void throwIfCancelled() {
        cancellationToken.throwIfCancelled();
    }

    // ========================
    // Child Context Creation
    // ========================

    /**
     * Create a child context for spawning virtual threads.
     */
    @TruffleBoundary
    public EffectContext createChildContext(String name, Map<String, Object> metadata) {
        ExecutionContext childExecContext;
        if (executionContext != null) {
            childExecContext = new ExecutionContext(
                    Thread.currentThread().threadId(),
                    name,
                    executionContext,
                    mergeMetadata(executionContext.getMetadata(), metadata)
            );
        } else {
            childExecContext = new ExecutionContext(
                    Thread.currentThread().threadId(),
                    name,
                    null,
                    metadata != null ? metadata : Collections.emptyMap()
            );
        }

        return copyWith(b -> b
                .executionContext(childExecContext)
                .executionId(UUID.randomUUID().toString())
        );
    }

    @TruffleBoundary
    private static Map<String, Object> mergeMetadata(Map<String, Object> base, Map<String, Object> additional) {
        if (additional == null || additional.isEmpty()) {
            return base;
        }
        Map<String, Object> merged = new HashMap<>(base);
        merged.putAll(additional);
        return merged;
    }

    // ========================
    // Result Management
    // ========================

    /**
     * Get the final result (for state machine completion).
     */
    public Object getResult() {
        return localVariables.get("final_result");
    }

    /**
     * Set the final result. Returns a new immutable context.
     */
    public EffectContext withResult(Object result) {
        return withVariable("final_result", result);
    }

    // ========================
    // Debug & Introspection
    // ========================

    /**
     * Get available effects for error reporting.
     */
    public Set<String> getAvailableEffects() {
        return new HashSet<>(Arrays.asList(declaredEffects));
    }

    /**
     * Get available handlers for error reporting.
     */
    @TruffleBoundary
    public Set<String> getAvailableHandlers() {
        Set<String> allHandlers = new HashSet<>();
        allHandlers.addAll(globalHandlers.keySet());
        allHandlers.addAll(activeHandlers.keySet());
        for (Map<String, Object> handlers : handlerStack) {
            allHandlers.addAll(handlers.keySet());
        }
        return allHandlers;
    }

    /**
     * Create a debug representation.
     */
    @TruffleBoundary
    public String toDebugString() {
        StringBuilder sb = new StringBuilder();
        sb.append("EffectContext(id=").append(executionId).append(")\n");
        sb.append("  Declared Effects: ").append(Arrays.toString(declaredEffects)).append("\n");
        sb.append("  Active Handlers: ").append(activeHandlers.keySet()).append("\n");
        sb.append("  Handler Stack Depth: ").append(handlerStack.size()).append("\n");
        sb.append("  Local Variables: ").append(localVariables.keySet()).append("\n");
        sb.append("  Suspension Points: ").append(suspensionPoints.keySet()).append("\n");
        sb.append("  Virtual Thread Futures: ").append(virtualThreadFutures.keySet()).append("\n");
        sb.append("  Concurrency Scope: ").append(concurrencyScope != null ? concurrencyScope.getName() : "null").append("\n");
        sb.append("  Execution Context: ").append(executionContext != null ? executionContext.getThreadName() : "null").append("\n");
        sb.append("  Cancelled: ").append(cancellationToken.isCancelled()).append("\n");
        return sb.toString();
    }

    @Override
    public String toString() {
        return "EffectContext{id=" + executionId +
                ", handlers=" + getAvailableHandlers().size() +
                ", cancelled=" + isCancelled() + "}";
    }

    // ========================
    // Immutable Copy Helper
    // ========================

    /**
     * Helper for immutable copy with modifications.
     */
    @TruffleBoundary
    private EffectContext copyWith(java.util.function.Consumer<Builder> modifier) {
        Builder builder = new Builder()
                .declaredEffects(new HashSet<>(Arrays.asList(declaredEffects)))
                .activeHandlers(new HashMap<>(activeHandlers))
                .handlerStack(new ArrayList<>(handlerStack))
                .globalHandlers(new HashMap<>(globalHandlers))
                .localVariables(new HashMap<>(localVariables))
                .suspensionPoints(new ConcurrentHashMap<>(suspensionPoints))
                .virtualThreadFutures(new ConcurrentHashMap<>(virtualThreadFutures))
                .concurrencyScope(concurrencyScope)
                .executionContext(executionContext)
                .cancellationToken(cancellationToken)
                .executionId(executionId);

        modifier.accept(builder);
        return builder.build();
    }

    // ========================
    // Builder
    // ========================

    /**
     * Builder for constructing EffectContext instances.
     */
    public static final class Builder {
        private Set<String> declaredEffects = Collections.emptySet();
        private Map<String, Object> activeHandlers = Collections.emptyMap();
        private List<Map<String, Object>> handlerStack = Collections.emptyList();
        private Map<String, Object> globalHandlers = Collections.emptyMap();
        private Map<String, Object> localVariables = Collections.emptyMap();
        private Map<String, SuspensionPoint> suspensionPoints = Collections.emptyMap();
        private Map<String, Future<?>> virtualThreadFutures = Collections.emptyMap();
        private ConcurrencyScope concurrencyScope;
        private ExecutionContext executionContext;
        private CancellationToken cancellationToken;
        private String executionId;

        public Builder declaredEffects(Set<String> effects) {
            this.declaredEffects = effects;
            return this;
        }

        public Builder activeHandlers(Map<String, Object> handlers) {
            this.activeHandlers = handlers;
            return this;
        }

        public Builder handlerStack(List<Map<String, Object>> stack) {
            this.handlerStack = stack;
            return this;
        }

        public Builder globalHandlers(Map<String, Object> handlers) {
            this.globalHandlers = handlers;
            return this;
        }

        public Builder localVariables(Map<String, Object> vars) {
            this.localVariables = vars;
            return this;
        }

        public Builder suspensionPoints(Map<String, SuspensionPoint> points) {
            this.suspensionPoints = points;
            return this;
        }

        public Builder virtualThreadFutures(Map<String, Future<?>> futures) {
            this.virtualThreadFutures = futures;
            return this;
        }

        public Builder concurrencyScope(ConcurrencyScope scope) {
            this.concurrencyScope = scope;
            return this;
        }

        public Builder executionContext(ExecutionContext ctx) {
            this.executionContext = ctx;
            return this;
        }

        public Builder cancellationToken(CancellationToken token) {
            this.cancellationToken = token;
            return this;
        }

        public Builder executionId(String id) {
            this.executionId = id;
            return this;
        }

        public EffectContext build() {
            return new EffectContext(
                    declaredEffects.toArray(new String[0]),
                    activeHandlers.isEmpty() ? Collections.emptyMap() : new HashMap<>(activeHandlers),
                    handlerStack.isEmpty() ? Collections.emptyList() : new ArrayList<>(handlerStack),
                    globalHandlers.isEmpty() ? Collections.emptyMap() : new HashMap<>(globalHandlers),
                    localVariables.isEmpty() ? Collections.emptyMap() : new HashMap<>(localVariables),
                    suspensionPoints.isEmpty() ? new ConcurrentHashMap<>() : new ConcurrentHashMap<>(suspensionPoints),
                    virtualThreadFutures.isEmpty() ? new ConcurrentHashMap<>() : new ConcurrentHashMap<>(virtualThreadFutures),
                    concurrencyScope,
                    executionContext,
                    cancellationToken != null ? cancellationToken : new CancellationToken(),
                    executionId != null ? executionId : UUID.randomUUID().toString()
            );
        }
    }
}
