package com.mezze.interpreter.v2.nodes.builtin.effects;

import com.mezze.datastructures.list.MezzeList;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.runtime.MezzeContext;
import com.mezze.datastructures.seq.MezzeSeq;
import com.mezze.interpreter.v2.values.*;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.TruffleLanguage.Env;
import com.oracle.truffle.api.nodes.IndirectCallNode;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

/**
 * Adaptive Async Handler for V2 interpreter.
 *
 * Intelligently switches between Loom virtual threads and cooperative (coop)
 * execution based on runtime conditions. This provides optimal performance
 * across different workload types:
 *
 * - IO-bound workloads: Prefers Loom (virtual threads park efficiently)
 * - CPU-bound workloads: Prefers batched execution or coop
 * - Nested async: Detects virtual thread context and avoids overhead
 * - Lightweight tasks: Uses coop to avoid thread creation overhead
 *
 * Architecture:
 * - Uses pluggable AsyncStrategy for decision making
 * - Tracks execution statistics for adaptive decisions
 * - JIT-friendly with proper Truffle compilation boundaries
 * - Lock-free statistics using VarHandle and LongAdder
 *
 * Strategies:
 * - AUTO: Intelligent automatic selection (default)
 * - LOOM_ONLY: Always use virtual threads
 * - COOP_ONLY: Always use cooperative execution
 * - ADAPTIVE: Learns from runtime behavior
 * - THRESHOLD: Switches based on task count thresholds
 */
public final class AdaptiveAsyncHandler {

    // ==================== Strategy Interface ====================

    /**
     * Strategy for deciding between Loom and Coop execution.
     */
    public interface AsyncStrategy {
        /**
         * Decide whether to use Loom virtual threads for this operation.
         *
         * @param stats Current execution statistics
         * @param taskEstimate Estimated complexity of the task (0 = unknown)
         * @return true to use Loom, false to use Coop
         */
        boolean shouldUseLoom(AdaptiveStats stats, int taskEstimate);

        /**
         * Notify the strategy that an operation completed.
         * Allows strategies to learn from execution patterns.
         */
        default void onOperationComplete(boolean usedLoom, long durationNanos) {
            // Default: no-op
        }

        /**
         * Get the strategy name for debugging.
         */
        String getName();
    }

    // ==================== Built-in Strategies ====================

    /**
     * Always use Loom virtual threads.
     * Best for: IO-bound workloads, many concurrent tasks.
     */
    public static final AsyncStrategy LOOM_ONLY = new AsyncStrategy() {
        @Override
        public boolean shouldUseLoom(AdaptiveStats stats, int taskEstimate) {
            return true;
        }

        @Override
        public String getName() {
            return "LOOM_ONLY";
        }
    };

    /**
     * Always use cooperative execution.
     * Best for: Deterministic testing, single-threaded execution.
     */
    public static final AsyncStrategy COOP_ONLY = new AsyncStrategy() {
        @Override
        public boolean shouldUseLoom(AdaptiveStats stats, int taskEstimate) {
            return false;
        }

        @Override
        public String getName() {
            return "COOP_ONLY";
        }
    };

    /**
     * Automatic strategy selection based on context.
     *
     * Decision logic:
     * 1. If already in virtual thread with low nesting: use Coop (avoid overhead)
     * 2. If task estimate is very small: use Coop (thread creation overhead > benefit)
     * 3. Otherwise: use Loom (virtual threads are cheap)
     */
    public static final AsyncStrategy AUTO = new AsyncStrategy() {
        @Override
        public boolean shouldUseLoom(AdaptiveStats stats, int taskEstimate) {
            // If already deeply nested in virtual threads, use coop to avoid overhead
            Thread current = Thread.currentThread();
            if (current.isVirtual()) {
                // Check nesting depth via thread-local
                int depth = VIRTUAL_THREAD_DEPTH.get();
                if (depth > 3) {
                    return false; // Too deep, use coop
                }
            }

            // Very small tasks don't benefit from threading
            if (taskEstimate > 0 && taskEstimate < 10) {
                return false;
            }

            // Default to Loom for most cases
            return true;
        }

        @Override
        public String getName() {
            return "AUTO";
        }
    };

    /**
     * Adaptive strategy that learns from execution patterns.
     *
     * Tracks average completion times and adjusts thresholds dynamically.
     * Uses exponential moving average for smooth adaptation.
     */
    public static final class AdaptiveStrategy implements AsyncStrategy {
        // Moving average of Loom completion time (nanos)
        private volatile long avgLoomNanos = 100_000; // 100us initial
        // Moving average of Coop completion time (nanos)
        private volatile long avgCoopNanos = 10_000;  // 10us initial
        // Sample counter for EMA updates
        private final AtomicLong sampleCount = new AtomicLong(0);
        // Threshold ratio: use Loom if task takes > ratio * avgCoopNanos
        private volatile double loomThresholdRatio = 5.0;

        @Override
        public boolean shouldUseLoom(AdaptiveStats stats, int taskEstimate) {
            // If Coop is much faster on average, prefer it for small tasks
            if (avgLoomNanos > avgCoopNanos * loomThresholdRatio) {
                // Loom overhead is significant, only use for larger tasks
                if (taskEstimate > 0 && taskEstimate < 100) {
                    return false;
                }
            }

            // Check if we're already in a virtual thread
            if (Thread.currentThread().isVirtual()) {
                int depth = VIRTUAL_THREAD_DEPTH.get();
                if (depth > 2) {
                    return false;
                }
            }

            // Default to Loom
            return true;
        }

        @Override
        public void onOperationComplete(boolean usedLoom, long durationNanos) {
            long count = sampleCount.incrementAndGet();

            // Update moving average (alpha = 0.1 for slow adaptation)
            if (usedLoom) {
                avgLoomNanos = (long) (avgLoomNanos * 0.9 + durationNanos * 0.1);
            } else {
                avgCoopNanos = (long) (avgCoopNanos * 0.9 + durationNanos * 0.1);
            }

            // Periodically adjust threshold ratio
            if ((count & 0xFF) == 0) { // Every 256 operations
                adjustThreshold();
            }
        }

        private void adjustThreshold() {
            // If Loom is consistently slower, increase threshold
            if (avgLoomNanos > avgCoopNanos * 10) {
                loomThresholdRatio = Math.min(20.0, loomThresholdRatio * 1.1);
            }
            // If Loom and Coop are similar, decrease threshold
            else if (avgLoomNanos < avgCoopNanos * 3) {
                loomThresholdRatio = Math.max(2.0, loomThresholdRatio * 0.9);
            }
        }

        @Override
        public String getName() {
            return "ADAPTIVE";
        }

        /**
         * Get current average timings for debugging.
         */
        public String getTimingInfo() {
            return String.format("Loom avg: %d ns, Coop avg: %d ns, threshold: %.1fx",
                avgLoomNanos, avgCoopNanos, loomThresholdRatio);
        }
    }

    /**
     * Threshold-based strategy.
     * Uses Loom when task count exceeds a threshold.
     */
    public static final class ThresholdStrategy implements AsyncStrategy {
        private final int loomThreshold;

        public ThresholdStrategy(int loomThreshold) {
            this.loomThreshold = loomThreshold;
        }

        @Override
        public boolean shouldUseLoom(AdaptiveStats stats, int taskEstimate) {
            return taskEstimate >= loomThreshold;
        }

        @Override
        public String getName() {
            return "THRESHOLD(" + loomThreshold + ")";
        }
    }

    // ==================== Thread-local nesting depth ====================

    private static final ThreadLocal<Integer> VIRTUAL_THREAD_DEPTH =
        ThreadLocal.withInitial(() -> 0);

    // ==================== Statistics ====================

    /**
     * Lock-free statistics for adaptive decision making.
     * Uses LongAdder for high-throughput counters.
     */
    public static final class AdaptiveStats {
        private final LongAdder totalOperations = new LongAdder();
        private final LongAdder loomOperations = new LongAdder();
        private final LongAdder coopOperations = new LongAdder();
        private final LongAdder totalLoomNanos = new LongAdder();
        private final LongAdder totalCoopNanos = new LongAdder();

        void recordLoom(long durationNanos) {
            totalOperations.increment();
            loomOperations.increment();
            totalLoomNanos.add(durationNanos);
        }

        void recordCoop(long durationNanos) {
            totalOperations.increment();
            coopOperations.increment();
            totalCoopNanos.add(durationNanos);
        }

        public long getTotalOperations() { return totalOperations.sum(); }
        public long getLoomOperations() { return loomOperations.sum(); }
        public long getCoopOperations() { return coopOperations.sum(); }
        public long getTotalLoomNanos() { return totalLoomNanos.sum(); }
        public long getTotalCoopNanos() { return totalCoopNanos.sum(); }

        public double getLoomPercentage() {
            long total = getTotalOperations();
            return total > 0 ? (getLoomOperations() * 100.0 / total) : 0.0;
        }

        public long getAvgLoomNanos() {
            long count = getLoomOperations();
            return count > 0 ? getTotalLoomNanos() / count : 0;
        }

        public long getAvgCoopNanos() {
            long count = getCoopOperations();
            return count > 0 ? getTotalCoopNanos() / count : 0;
        }

        @Override
        public String toString() {
            return String.format(
                "AdaptiveStats[total=%d, loom=%d (%.1f%%, avg %d ns), coop=%d (%.1f%%, avg %d ns)]",
                getTotalOperations(),
                getLoomOperations(), getLoomPercentage(), getAvgLoomNanos(),
                getCoopOperations(), 100 - getLoomPercentage(), getAvgCoopNanos()
            );
        }
    }

    // ==================== Handler Instance ====================

    private final AsyncStrategy strategy;
    private final AdaptiveStats stats;

    // Promise tracker for scope cleanup
    private final AsyncNatives.PromiseTracker tracker;

    /**
     * Create an adaptive handler with the specified strategy.
     */
    public AdaptiveAsyncHandler(AsyncStrategy strategy) {
        this.strategy = strategy != null ? strategy : AUTO;
        this.stats = new AdaptiveStats();
        this.tracker = new AsyncNatives.PromiseTracker();
    }

    /**
     * Create an adaptive handler with AUTO strategy.
     */
    public AdaptiveAsyncHandler() {
        this(AUTO);
    }

    /**
     * Get the promise tracker for this handler.
     */
    public AsyncNatives.PromiseTracker getTracker() {
        return tracker;
    }

    /**
     * Get current statistics.
     */
    public AdaptiveStats getStats() {
        return stats;
    }

    /**
     * Get the active strategy.
     */
    public AsyncStrategy getStrategy() {
        return strategy;
    }

    // ==================== Spawn Operation ====================

    /**
     * Spawn a task adaptively - chooses between Loom and Coop.
     * Returns a unified AdaptivePromise that works with both.
     */
    @TruffleBoundary
    public AdaptivePromise spawn(MezzeRecord args, IndirectCallNode callNode, MezzeNode node) {
        MezzeFunction f = getFunction(args, "f");
        int taskEstimate = getTaskEstimate(args);

        boolean useLoom = strategy.shouldUseLoom(stats, taskEstimate);

        if (useLoom) {
            return spawnLoom(f, callNode, node);
        } else {
            return spawnCoop(f, callNode);
        }
    }

    /**
     * Spawn using Loom virtual thread.
     */
    private AdaptivePromise spawnLoom(MezzeFunction f, IndirectCallNode callNode, MezzeNode node) {
        Object[] closureValues = f.getClosureValues();
        var callTarget = f.getCallTarget();
        MezzeContext context = MezzeContext.get(node);
        Env env = context.getEnv();

        // Capture parent handlers for effect propagation
        Map<String, Object> parentHandlers = context.getAllCurrentHandlers();

        AdaptivePromise promise = new AdaptivePromise(true);
        long startNanos = System.nanoTime();

        Thread vThread = env.newTruffleThreadBuilder(() -> {
            // Track nesting depth
            int depth = VIRTUAL_THREAD_DEPTH.get();
            VIRTUAL_THREAD_DEPTH.set(depth + 1);

            try {
                // Push parent handlers
                MezzeContext threadContext = MezzeContext.get(node);
                if (parentHandlers != null && !parentHandlers.isEmpty()) {
                    threadContext.pushHandlers(parentHandlers);
                }

                try {
                    // Build args: closure + empty record
                    Object[] fullArgs = new Object[closureValues.length + 1];
                    System.arraycopy(closureValues, 0, fullArgs, 0, closureValues.length);
                    fullArgs[closureValues.length] = MezzeRecord.empty();

                    Object result = callNode.call(callTarget, fullArgs);

                    long durationNanos = System.nanoTime() - startNanos;
                    stats.recordLoom(durationNanos);
                    strategy.onOperationComplete(true, durationNanos);

                    promise.complete(result);
                } finally {
                    if (parentHandlers != null && !parentHandlers.isEmpty()) {
                        threadContext.popHandlers();
                    }
                }
            } catch (Throwable e) {
                promise.fail(e);
            } finally {
                VIRTUAL_THREAD_DEPTH.set(depth);
            }
        }).virtual(true).build();

        vThread.start();
        promise.setExecutingThread(vThread);
        tracker.track(promise.toLoomPromise());

        return promise;
    }

    /**
     * Spawn using cooperative (synchronous) execution.
     */
    private AdaptivePromise spawnCoop(MezzeFunction f, IndirectCallNode callNode) {
        AdaptivePromise promise = new AdaptivePromise(false);
        long startNanos = System.nanoTime();

        try {
            Object[] closureValues = f.getClosureValues();
            Object[] fullArgs = new Object[closureValues.length + 1];
            System.arraycopy(closureValues, 0, fullArgs, 0, closureValues.length);
            fullArgs[closureValues.length] = MezzeRecord.empty();

            Object result = callNode.call(f.getCallTarget(), fullArgs);

            long durationNanos = System.nanoTime() - startNanos;
            stats.recordCoop(durationNanos);
            strategy.onOperationComplete(false, durationNanos);

            promise.complete(result);
        } catch (Throwable e) {
            promise.fail(e);
        }

        return promise;
    }

    // ==================== Await Operation ====================

    /**
     * Await an adaptive promise.
     */
    @TruffleBoundary
    public Object await(MezzeRecord args) {
        AdaptivePromise promise = getAdaptivePromise(args, "p");
        return promise.await();
    }

    // ==================== Join Operations ====================

    /**
     * Join two tasks - runs them concurrently/sequentially based on strategy.
     */
    @TruffleBoundary
    public MezzeRecord join(MezzeRecord args, IndirectCallNode callNode, MezzeNode node) {
        MezzeFunction left = getFunction(args, "left");
        MezzeFunction right = getFunction(args, "right");

        boolean useLoom = strategy.shouldUseLoom(stats, 2);

        if (useLoom) {
            return joinLoom(left, right, callNode, node);
        } else {
            return joinCoop(left, right, callNode);
        }
    }

    private MezzeRecord joinLoom(MezzeFunction left, MezzeFunction right,
                                  IndirectCallNode callNode, MezzeNode node) {
        MezzeContext context = MezzeContext.get(node);
        Env env = context.getEnv();
        Map<String, Object> parentHandlers = context.getAllCurrentHandlers();

        CompletableFuture<Object> leftFuture = new CompletableFuture<>();
        CompletableFuture<Object> rightFuture = new CompletableFuture<>();

        Thread leftThread = env.newTruffleThreadBuilder(() -> {
            try {
                MezzeContext threadContext = MezzeContext.get(node);
                if (parentHandlers != null && !parentHandlers.isEmpty()) {
                    threadContext.pushHandlers(parentHandlers);
                }
                try {
                    leftFuture.complete(callThunkFunction(left, callNode));
                } finally {
                    if (parentHandlers != null && !parentHandlers.isEmpty()) {
                        threadContext.popHandlers();
                    }
                }
            } catch (Throwable e) {
                leftFuture.completeExceptionally(e);
            }
        }).virtual(true).build();

        Thread rightThread = env.newTruffleThreadBuilder(() -> {
            try {
                MezzeContext threadContext = MezzeContext.get(node);
                if (parentHandlers != null && !parentHandlers.isEmpty()) {
                    threadContext.pushHandlers(parentHandlers);
                }
                try {
                    rightFuture.complete(callThunkFunction(right, callNode));
                } finally {
                    if (parentHandlers != null && !parentHandlers.isEmpty()) {
                        threadContext.popHandlers();
                    }
                }
            } catch (Throwable e) {
                rightFuture.completeExceptionally(e);
            }
        }).virtual(true).build();

        leftThread.start();
        rightThread.start();

        try {
            return MezzeRecord.fromFields(
                LEFT_RIGHT_FIELDS,
                new Object[]{leftFuture.get(), rightFuture.get()}
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            leftThread.interrupt();
            rightThread.interrupt();
            throw new RuntimeException("Join interrupted", e);
        } catch (ExecutionException e) {
            leftThread.interrupt();
            rightThread.interrupt();
            throw new RuntimeException("Join failed: " + e.getCause().getMessage(), e.getCause());
        }
    }

    private MezzeRecord joinCoop(MezzeFunction left, MezzeFunction right, IndirectCallNode callNode) {
        Object leftResult = callThunkFunction(left, callNode);
        Object rightResult = callThunkFunction(right, callNode);
        return MezzeRecord.fromFields(LEFT_RIGHT_FIELDS, new Object[]{leftResult, rightResult});
    }

    // ==================== Map/ForEach Operations ====================

    /**
     * Parallel map with adaptive execution.
     */
    @TruffleBoundary
    public MezzeList map(MezzeRecord args, IndirectCallNode callNode, MezzeNode node) {
        Object[] xs = getArray(args, "xs");
        MezzeFunction f = getFunction(args, "f");

        if (xs.length == 0) {
            return MezzeList.empty();
        }

        // For small arrays, always use sequential
        if (xs.length < PARALLEL_THRESHOLD) {
            return sequentialMap(xs, f, callNode);
        }

        boolean useLoom = strategy.shouldUseLoom(stats, xs.length);

        if (useLoom) {
            return parallelMapLoom(xs, f, callNode, node);
        } else {
            return sequentialMap(xs, f, callNode);
        }
    }

    private MezzeList sequentialMap(Object[] xs, MezzeFunction f, IndirectCallNode callNode) {
        var callTarget = f.getCallTarget();
        Object[] closure = f.getClosureValues();
        Object[] results = new Object[xs.length];
        Object[] callArgs = new Object[closure.length + 1];
        System.arraycopy(closure, 0, callArgs, 0, closure.length);

        for (int i = 0; i < xs.length; i++) {
            MezzeRecord arg = MezzeRecord.fromFields(X_FIELD, new Object[]{xs[i]});
            callArgs[closure.length] = arg;
            results[i] = callNode.call(callTarget, callArgs);
        }

        return MezzeList.of(results);
    }

    private MezzeList parallelMapLoom(Object[] xs, MezzeFunction f,
                                       IndirectCallNode callNode, MezzeNode node) {
        var callTarget = f.getCallTarget();
        Object[] closure = f.getClosureValues();
        Object[] results = new Object[xs.length];

        MezzeContext context = MezzeContext.get(node);
        Env env = context.getEnv();

        int numCores = Runtime.getRuntime().availableProcessors();
        int batchSize = Math.max(1, xs.length / (numCores * 2));
        batchSize = Math.min(batchSize, BATCH_SIZE);
        int numBatches = (xs.length + batchSize - 1) / batchSize;

        Thread[] threads = new Thread[numBatches];
        final int finalBatchSize = batchSize;
        final int closureLen = closure.length;

        for (int b = 0; b < numBatches; b++) {
            final int batchStart = b * finalBatchSize;
            final int batchEnd = Math.min(batchStart + finalBatchSize, xs.length);

            threads[b] = env.newTruffleThreadBuilder(() -> {
                Object[] callArgs = new Object[closureLen + 1];
                System.arraycopy(closure, 0, callArgs, 0, closureLen);

                for (int i = batchStart; i < batchEnd; i++) {
                    MezzeRecord arg = MezzeRecord.fromFields(X_FIELD, new Object[]{xs[i]});
                    callArgs[closureLen] = arg;
                    results[i] = callNode.call(callTarget, callArgs);
                }
            }).virtual(true).build();
            threads[b].start();
        }

        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Map interrupted", e);
            }
        }

        return MezzeList.of(results);
    }

    // ==================== Scope Operations ====================

    /**
     * Initialize scope - returns the handler itself as tracker.
     */
    @TruffleBoundary
    public AdaptiveAsyncHandler initScope(MezzeRecord args) {
        return this;
    }

    /**
     * Finalize scope - clean up the tracker.
     * Unawaited promises continue running (like Go/Rust).
     */
    @TruffleBoundary
    public MezzeUnit finalizeScope(MezzeRecord args) {
        tracker.clear();
        return MezzeUnit.INSTANCE;
    }

    // ==================== Race/All Operations ====================

    /**
     * Race multiple promises.
     */
    @TruffleBoundary
    public Object race(MezzeRecord args) {
        Object[] promises = getArray(args, "ps");
        if (promises.length == 0) {
            throw new RuntimeException("race requires at least one promise");
        }

        // Fast path: single promise
        if (promises.length == 1) {
            return awaitPromise(promises[0]);
        }

        // Polling loop
        long parkNanos = 100_000;
        final long maxParkNanos = 10_000_000;

        while (true) {
            for (Object p : promises) {
                if (p instanceof AdaptivePromise ap) {
                    if (ap.isDone()) {
                        return ap.getResultOrThrow();
                    }
                } else if (p instanceof AsyncNatives.MezzePromise mp) {
                    if (mp.isDone()) {
                        if (mp.isCompleted()) return mp.getResult();
                        if (mp.isFailed()) throw new RuntimeException("Task failed");
                    }
                }
            }

            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted during race");
            }

            LockSupport.parkNanos(parkNanos);
            parkNanos = Math.min(parkNanos * 2, maxParkNanos);
        }
    }

    /**
     * Await all promises.
     */
    @TruffleBoundary
    public MezzeList all(MezzeRecord args) {
        Object[] promises = getArray(args, "ps");
        Object[] results = new Object[promises.length];

        for (int i = 0; i < promises.length; i++) {
            results[i] = awaitPromise(promises[i]);
        }

        return MezzeList.of(results);
    }

    private Object awaitPromise(Object p) {
        if (p instanceof AdaptivePromise ap) {
            return ap.await();
        } else if (p instanceof AsyncNatives.MezzePromise mp) {
            try {
                return mp.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted", e);
            }
        }
        throw new RuntimeException("Unknown promise type: " + p.getClass());
    }

    // ==================== Utility Operations ====================

    @TruffleBoundary
    public MezzeUnit sleep(MezzeRecord args) {
        long ms = getLong(args, "ms");
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Sleep interrupted", e);
        }
        return MezzeUnit.INSTANCE;
    }

    public boolean isDone(MezzeRecord args) {
        AdaptivePromise promise = getAdaptivePromise(args, "p");
        return promise.isDone();
    }

    // ==================== Unified Promise ====================

    /**
     * Unified promise that works with both Loom and Coop execution.
     */
    public static final class AdaptivePromise {
        private static final int PENDING = 0;
        private static final int COMPLETED = 1;
        private static final int FAILED = 2;
        private static final int CANCELLED = 3;

        private static final VarHandle STATE;
        private static final VarHandle RESULT;

        static {
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                STATE = l.findVarHandle(AdaptivePromise.class, "state", int.class);
                RESULT = l.findVarHandle(AdaptivePromise.class, "result", Object.class);
            } catch (Exception e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private volatile int state = PENDING;
        private volatile Object result;
        private volatile Thread executingThread;
        private final boolean usedLoom;

        AdaptivePromise(boolean usedLoom) {
            this.usedLoom = usedLoom;
        }

        void setExecutingThread(Thread thread) {
            this.executingThread = thread;
        }

        void complete(Object value) {
            RESULT.setVolatile(this, value);
            STATE.setVolatile(this, COMPLETED);
        }

        void fail(Throwable error) {
            RESULT.setVolatile(this, error);
            STATE.setVolatile(this, FAILED);
        }

        void cancel() {
            if (STATE.compareAndSet(this, PENDING, CANCELLED)) {
                Thread t = executingThread;
                if (t != null) {
                    t.interrupt();
                }
            }
        }

        public boolean isDone() {
            return state != PENDING;
        }

        public boolean usedLoom() {
            return usedLoom;
        }

        public Object await() {
            // Coop promises are already done
            if (!usedLoom) {
                return getResultOrThrow();
            }

            // Loom: wait for completion
            while (state == PENDING) {
                LockSupport.parkNanos(this, 1_000_000); // 1ms
                if (Thread.interrupted()) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while awaiting");
                }
            }

            return getResultOrThrow();
        }

        Object getResultOrThrow() {
            return switch (state) {
                case COMPLETED -> result;
                case FAILED -> {
                    if (result instanceof Throwable t) {
                        throw new RuntimeException("Task failed: " + t.getMessage(), t);
                    }
                    throw new RuntimeException("Task failed");
                }
                case CANCELLED -> throw new CancellationException("Task cancelled");
                default -> throw new IllegalStateException("Unexpected state: " + state);
            };
        }

        /**
         * Convert to MezzePromise for compatibility with PromiseTracker.
         */
        AsyncNatives.MezzePromise toLoomPromise() {
            // Create a proxy promise that delegates to this
            AsyncNatives.MezzePromise mp = new AsyncNatives.MezzePromise(executingThread, null);
            // Link completion
            if (isDone()) {
                if (state == COMPLETED) {
                    mp.complete(result);
                } else if (state == FAILED) {
                    mp.fail((Throwable) result);
                } else {
                    mp.cancel();
                }
            }
            return mp;
        }
    }

    // ==================== Constants ====================

    private static final int PARALLEL_THRESHOLD = 4;
    private static final int BATCH_SIZE = 64;
    private static final String[] X_FIELD = new String[]{"x"};
    private static final String[] LEFT_RIGHT_FIELDS = new String[]{"left", "right"};

    // ==================== Helper Methods ====================

    private static Object callThunkFunction(MezzeFunction f, IndirectCallNode callNode) {
        Object[] closureValues = f.getClosureValues();
        Object[] fullArgs = new Object[closureValues.length + 1];
        System.arraycopy(closureValues, 0, fullArgs, 0, closureValues.length);
        fullArgs[closureValues.length] = MezzeRecord.empty();
        return callNode.call(f.getCallTarget(), fullArgs);
    }

    private static MezzeFunction getFunction(MezzeRecord r, String name) {
        int idx = r.getFieldIndex(name);
        if (idx < 0) throw new RuntimeException("Missing field '" + name + "'");
        Object val = r.getField(idx);
        if (val instanceof MezzeFunction f) return f;
        throw new RuntimeException("Expected function for '" + name + "'");
    }

    private static Object[] getArray(MezzeRecord r, String name) {
        int idx = r.getFieldIndex(name);
        if (idx < 0) throw new RuntimeException("Missing field '" + name + "'");
        Object val = r.getField(idx);
        if (val instanceof Object[] arr) return arr;
        if (val instanceof MezzeSeq seq) return seq.toArray();
        if (val instanceof MezzeList list) return list.toArray();
        throw new RuntimeException("Expected array for '" + name + "'");
    }

    private static long getLong(MezzeRecord r, String name) {
        int idx = r.getFieldIndex(name);
        if (idx < 0) throw new RuntimeException("Missing field '" + name + "'");
        Object val = r.getField(idx);
        if (val instanceof Long l) return l;
        if (val instanceof Integer i) return i.longValue();
        throw new RuntimeException("Expected long for '" + name + "'");
    }

    private static int getTaskEstimate(MezzeRecord r) {
        int idx = r.getFieldIndex("task_estimate");
        if (idx < 0) return 0; // Unknown
        Object val = r.getField(idx);
        if (val instanceof Long l) return l.intValue();
        if (val instanceof Integer i) return i;
        return 0;
    }

    private static AdaptivePromise getAdaptivePromise(MezzeRecord r, String name) {
        int idx = r.getFieldIndex(name);
        if (idx < 0) throw new RuntimeException("Missing field '" + name + "'");
        Object val = r.getField(idx);
        if (val instanceof MezzeVariant v) {
            val = v.getPayloadValue();
        }
        if (val instanceof AdaptivePromise ap) return ap;
        throw new RuntimeException("Expected AdaptivePromise for '" + name + "'");
    }
}
