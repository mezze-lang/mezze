package com.mezze.interpreter.nodes.builtin.effects;

import com.mezze.datastructures.list.MezzeList;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.runtime.MezzeContext;
import com.mezze.datastructures.seq.MezzeSeq;
import com.mezze.interpreter.values.*;
import com.mezze.language.typedast.Hash;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage.Env;
import com.oracle.truffle.api.nodes.IndirectCallNode;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * High-performance native async operations.
 *
 * Three implementations:
 * 1. LoomAsync - Virtual threads (Project Loom) - millions of concurrent tasks
 * 2. OSThreadAsync - Platform threads - true CPU parallelism
 * 3. CoopAsync - Cooperative scheduling - single-threaded, deterministic
 *
 * Performance optimizations:
 * - Lock-free promise state management via VarHandle
 * - Object pooling for MezzePromise to reduce GC pressure
 * - Work-stealing for OS thread pool
 * - Specialized fast paths for common cases (single promise await, small arrays)
 * - Avoiding unnecessary allocations in hot paths
 */
public final class AsyncNatives {

    // ==================== Helper Functions ====================

    /**
     * Call a MezzeFunction that takes an empty record argument.
     * Builds the proper argument array: [closure0, closure1, ..., emptyRecord]
     */
    private static Object callThunkFunction(MezzeFunction f, IndirectCallNode callNode) {
        Object[] closureValues = f.getClosureValues();
        Object[] fullArgs = new Object[closureValues.length + 1];
        System.arraycopy(closureValues, 0, fullArgs, 0, closureValues.length);
        fullArgs[closureValues.length] = MezzeRecord.empty();
        return callNode.call(f.getCallTarget(), fullArgs);
    }

    // ==================== Promise Implementation ====================

    /**
     * High-performance promise using VarHandle for lock-free state transitions.
     * States: PENDING(0) -> COMPLETED(1) or FAILED(2) or CANCELLED(3)
     *
     * AGGRESSIVE OPTIMIZATIONS:
     * 1. VarHandles for lock-free state transitions (no synchronized blocks)
     * 2. Cache line padding to prevent false sharing between promises
     * 3. Adaptive spin-then-park await strategy
     * 4. Sampled completion time tracking for adaptive spin count
     */
    public static class MezzePromise {
        private static final int PENDING = 0;
        private static final int COMPLETED = 1;
        private static final int FAILED = 2;
        private static final int CANCELLED = 3;

        // OPTIMIZATION: VarHandles for ALL volatile fields - proper memory ordering
        private static final VarHandle STATE;
        private static final VarHandle RESULT;
        private static final VarHandle WAITER;

        static {
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                STATE = l.findVarHandle(MezzePromise.class, "state", int.class);
                RESULT = l.findVarHandle(MezzePromise.class, "result", Object.class);
                WAITER = l.findVarHandle(MezzePromise.class, "waiter", Thread.class);
            } catch (Exception e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        // OPTIMIZATION: Cache line padding to prevent false sharing
        // Hot fields (state, result, waiter) are on separate cache lines from cold fields
        // x86 cache line is 64 bytes, so we pad between hot and cold fields
        @SuppressWarnings("unused")
        private long p0, p1, p2, p3, p4, p5, p6; // Padding before hot fields

        private volatile int state = PENDING;
        private volatile Object result; // Value on success, Throwable on failure
        private volatile Thread waiter; // Thread waiting on this promise

        @SuppressWarnings("unused")
        private long q0, q1, q2, q3, q4, q5, q6; // Padding after hot fields

        final Thread executingThread; // For cancellation
        final Semaphore semaphore; // For concurrency limiting (may be null)

        public MezzePromise(Thread executingThread, Semaphore semaphore) {
            this.executingThread = executingThread;
            this.semaphore = semaphore;
        }

        /**
         * Constructor that delegates to another promise's future state.
         * Used for returning a promise with thread reference after spawn.
         */
        public MezzePromise(Thread executingThread, Semaphore semaphore, MezzePromise delegate) {
            this.executingThread = executingThread;
            this.semaphore = semaphore;
            // Link to delegate - we'll poll its state
            this.delegate = delegate;
        }

        private volatile MezzePromise delegate;

        public boolean complete(Object value) {
            // Set result BEFORE state to ensure visibility
            // The state CAS acts as a release fence, making result visible
            RESULT.setVolatile(this, value);
            if (STATE.compareAndSet(this, PENDING, COMPLETED)) {
                unparkWaiter();
                return true;
            }
            // CAS failed, but we already set result - this is fine because
            // state didn't change, so no one will read the result
            return false;
        }

        public boolean fail(Throwable error) {
            // Set result BEFORE state to ensure visibility
            RESULT.setVolatile(this, error);
            if (STATE.compareAndSet(this, PENDING, FAILED)) {
                unparkWaiter();
                return true;
            }
            return false;
        }

        public boolean cancel() {
            if (STATE.compareAndSet(this, PENDING, CANCELLED)) {
                if (executingThread != null) {
                    executingThread.interrupt();
                }
                unparkWaiter();
                return true;
            }
            return false;
        }

        // OPTIMIZATION: Use VarHandle for proper memory ordering on waiter access
        private void unparkWaiter() {
            // Use acquire semantics to ensure we see the waiter if it was set
            Thread w = (Thread) WAITER.getAcquire(this);
            if (w != null) {
                LockSupport.unpark(w);
            }
        }

        public boolean isDone() {
            MezzePromise d = delegate;
            if (d != null) return d.isDone();
            return state != PENDING;
        }

        public boolean isCompleted() {
            MezzePromise d = delegate;
            if (d != null) return d.isCompleted();
            return state == COMPLETED;
        }

        public boolean isFailed() {
            MezzePromise d = delegate;
            if (d != null) return d.isFailed();
            return state == FAILED;
        }

        public boolean isCancelled() {
            MezzePromise d = delegate;
            if (d != null) return d.isCancelled();
            return state == CANCELLED;
        }

        public Object getResult() {
            MezzePromise d = delegate;
            if (d != null) return d.getResult();
            return result;
        }

        /**
         * Blocking await with ADAPTIVE spin-then-park strategy for low latency.
         * OPTIMIZATION: Spin count adapts based on historical completion times.
         * OPTIMIZATION: Sampled nanoTime() calls to reduce overhead.
         * OPTIMIZATION: VarHandle for proper waiter memory ordering.
         */
        public Object await() throws InterruptedException {
            // OPTIMIZATION: Only measure time on sampled calls
            long startNanos = ((completionCount.get() & (SAMPLE_RATE - 1)) == 0)
                ? System.nanoTime() : 0;

            // If delegating, wait on the delegate
            MezzePromise d = delegate;
            if (d != null) {
                return d.await();
            }

            // Fast path: already done
            if (state != PENDING) {
                if (startNanos != 0) recordCompletionTime(System.nanoTime() - startNanos);
                return getResultOrThrow();
            }

            // Adaptive spinning based on historical completion times
            int spinCount = getAdaptiveSpinCount();
            for (int i = 0; i < spinCount; i++) {
                if (state != PENDING) {
                    if (startNanos != 0) recordCompletionTime(System.nanoTime() - startNanos);
                    return getResultOrThrow();
                }
                Thread.onSpinWait();
            }

            // Park with waiter registration - use VarHandle for proper ordering
            WAITER.setRelease(this, Thread.currentThread());
            try {
                while (state == PENDING) {
                    LockSupport.park(this);
                    if (Thread.interrupted()) {
                        throw new InterruptedException();
                    }
                }
            } finally {
                WAITER.setRelease(this, null);
            }

            if (startNanos != 0) recordCompletionTime(System.nanoTime() - startNanos);
            return getResultOrThrow();
        }

        /**
         * Timed await.
         */
        public Object await(long timeoutMs) throws InterruptedException, TimeoutException {
            MezzePromise d = delegate;
            if (d != null) {
                return d.await(timeoutMs);
            }

            if (state != PENDING) {
                return getResultOrThrow();
            }

            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            waiter = Thread.currentThread();
            try {
                while (state == PENDING) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        throw new TimeoutException();
                    }
                    LockSupport.parkNanos(this, remaining);
                    if (Thread.interrupted()) {
                        throw new InterruptedException();
                    }
                }
            } finally {
                waiter = null;
            }

            return getResultOrThrow();
        }

        private Object getResultOrThrow() {
            return switch (state) {
                case COMPLETED -> result;
                case FAILED -> {
                    if (result instanceof Throwable t) {
                        throw new RuntimeException("Task failed: " + t.getMessage(), t);
                    } else {
                        throw new RuntimeException("Task failed with unknown error: " + result);
                    }
                }
                case CANCELLED -> throw new CancellationException("Task was cancelled");
                default -> throw new IllegalStateException("Promise in unexpected state: " + state);
            };
        }
    }

    // ==================== Scope Implementation ====================

    /**
     * Structured concurrency scope - tracks child tasks and propagates cancellation.
     * Uses ConcurrentLinkedQueue instead of CopyOnWriteArrayList for O(1) addChild.
     */
    public static final class MezzeScope {
        // OPTIMIZATION: ConcurrentLinkedQueue has O(1) add vs CopyOnWriteArrayList's O(n)
        private final ConcurrentLinkedQueue<MezzePromise> children = new ConcurrentLinkedQueue<>();
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicInteger pendingCount = new AtomicInteger(0);

        // Truffle environment for spawning child threads with proper context
        Env env;

        void addChild(MezzePromise promise) {
            children.add(promise);
            pendingCount.incrementAndGet();
        }

        void childCompleted() {
            pendingCount.decrementAndGet();
        }

        boolean isCancelled() {
            return cancelled.get();
        }

        void cancelAll() {
            if (cancelled.compareAndSet(false, true)) {
                for (MezzePromise child : children) {
                    child.cancel();
                }
            }
        }

        void awaitAll() throws InterruptedException {
            for (MezzePromise child : children) {
                try {
                    child.await();
                } catch (CancellationException e) {
                    // Expected if scope was cancelled
                } catch (RuntimeException e) {
                    // Child failed - cancel siblings and propagate
                    cancelAll();
                    throw e;
                }
            }
        }
    }

    // ==================== Promise Tracker (for scoped effects) ====================

    /**
     * Tracks all promises spawned within an effect handler scope.
     * Used by initialize_scope/finalize_scope to ensure cleanup.
     */
    public static final class PromiseTracker {
        private final ConcurrentLinkedQueue<MezzePromise> promises = new ConcurrentLinkedQueue<>();
        private final AtomicBoolean finalized = new AtomicBoolean(false);

        public void track(MezzePromise promise) {
            if (!finalized.get()) {
                promises.add(promise);
            }
        }

        /**
         * Clear the tracker. Called by finalize_scope.
         *
         * Like Go and Rust, we don't await unawaited promises - they continue
         * running but the result is lost. This is the correct behavior for
         * fire-and-forget tasks. If user wants to wait, they should explicitly
         * use await, all, or race.
         *
         * Tasks run to completion; the scope just doesn't wait for them.
         */
        public void clear() {
            finalized.set(true);
            promises.clear();
        }

        /**
         * Cancel all pending promises. Called on error/exception in scope.
         */
        public void cancelAll() {
            finalized.set(true);
            for (MezzePromise p : promises) {
                if (!p.isDone()) {
                    p.cancel();
                }
            }
            promises.clear();
        }
    }

    // ==================== Loom Scope Lifecycle (scoped effects) ====================

    /**
     * Initialize scope - creates a promise tracker for this handler.
     */
    @TruffleBoundary
    public static PromiseTracker loomInitScope(MezzeRecord args) {
        return new PromiseTracker();
    }

    /**
     * Finalize scope - clean up the tracker.
     *
     * Like Go and Rust, unawaited promises continue running but their
     * results are lost. This is correct for fire-and-forget patterns.
     * If user wants to wait for all tasks, they should use await/all/race.
     */
    @TruffleBoundary
    public static MezzeUnit loomFinalizeScope(MezzeRecord args) {
        Object trackerObj = getFieldValue(args, "tracker");
        if (trackerObj instanceof PromiseTracker tracker) {
            tracker.clear();
        }
        return MezzeUnit.INSTANCE;
    }

    /**
     * Spawn with tracking - registers promise with tracker for cleanup.
     */
    @TruffleBoundary
    public static MezzePromise loomSpawnTracked(MezzeRecord args, IndirectCallNode callNode, MezzeNode node) {
        MezzeFunction f = getFunction(args, "f");
        long maxConcurrent = getLong(args, "max_concurrent");
        Object trackerObj = getFieldValue(args, "tracker");

        Semaphore semaphore = maxConcurrent > 0
            ? SEMAPHORE_CACHE.computeIfAbsent((int) maxConcurrent, Semaphore::new)
            : null;

        Object[] closureValues = f.getClosureValues();
        var callTarget = f.getCallTarget();
        MezzeContext context = MezzeContext.get(node);
        Env env = context.getEnv();

        // LAZY EFFECTS: Capture parent's handlers to propagate to spawned thread
        java.util.Map<Hash, Object> parentHandlers = context.getAllCurrentHandlers();

        MezzePromise promise = new MezzePromise(null, semaphore);

        Thread vThread = env.newTruffleThreadBuilder(() -> {
            try {
                // LAZY EFFECTS: Push parent handlers onto this thread's stack
                MezzeContext threadContext = MezzeContext.get(node);
                if (parentHandlers != null && !parentHandlers.isEmpty()) {
                    threadContext.pushHandlers(parentHandlers);
                }

                try {
                    if (semaphore != null) {
                        semaphore.acquireUninterruptibly();
                    }
                    try {
                        Object result = callNode.call(callTarget, closureValues);
                        promise.complete(result);
                    } finally {
                        if (semaphore != null) {
                            semaphore.release();
                        }
                    }
                } finally {
                    // LAZY EFFECTS: Pop handlers when thread completes
                    if (parentHandlers != null && !parentHandlers.isEmpty()) {
                        threadContext.popHandlers();
                    }
                }
            } catch (Throwable e) {
                promise.fail(e);
            }
        }).virtual(true).build();

        vThread.start();

        MezzePromise tracked = new MezzePromise(vThread, semaphore, promise);

        // Track for cleanup
        if (trackerObj instanceof PromiseTracker tracker) {
            tracker.track(tracked);
        }

        return tracked;
    }

    // ==================== OS Thread Scope Lifecycle ====================

    @TruffleBoundary
    public static PromiseTracker osInitScope(MezzeRecord args) {
        return new PromiseTracker();
    }

    /**
     * Finalize OS thread scope - clean up the tracker.
     * Same semantics as loomFinalizeScope - unawaited tasks continue running.
     */
    @TruffleBoundary
    public static MezzeUnit osFinalizeScope(MezzeRecord args) {
        Object trackerObj = getFieldValue(args, "tracker");
        if (trackerObj instanceof PromiseTracker tracker) {
            tracker.clear();
        }
        return MezzeUnit.INSTANCE;
    }

    @TruffleBoundary
    public static MezzePromise osSpawnTracked(MezzeRecord args, IndirectCallNode callNode, MezzeNode node) {
        MezzeFunction f = getFunction(args, "f");
        Object trackerObj = getFieldValue(args, "tracker");

        Object[] closureValues = f.getClosureValues();
        var callTarget = f.getCallTarget();
        MezzeContext context = MezzeContext.get(node);
        Env env = context.getEnv();

        // Build argument array: closure values + empty record argument
        // Thunk functions expect {} as argument
        Object[] fullArgs = new Object[closureValues.length + 1];
        System.arraycopy(closureValues, 0, fullArgs, 0, closureValues.length);
        fullArgs[closureValues.length] = MezzeRecord.empty();

        // LAZY EFFECTS: Capture parent's handlers to propagate to spawned thread
        java.util.Map<Hash, Object> parentHandlers = context.getAllCurrentHandlers();

        MezzePromise promise = new MezzePromise(null, null);

        // Platform thread (not virtual)
        Thread thread = env.newTruffleThreadBuilder(() -> {
            try {
                // LAZY EFFECTS: Push parent handlers onto this thread's stack
                MezzeContext threadContext = MezzeContext.get(node);
                if (parentHandlers != null && !parentHandlers.isEmpty()) {
                    threadContext.pushHandlers(parentHandlers);
                }

                try {
                    Object result = callNode.call(callTarget, fullArgs);
                    promise.complete(result);
                } finally {
                    // LAZY EFFECTS: Pop handlers when thread completes
                    if (parentHandlers != null && !parentHandlers.isEmpty()) {
                        threadContext.popHandlers();
                    }
                }
            } catch (Throwable e) {
                promise.fail(e);
            }
        }).build();

        thread.start();

        MezzePromise tracked = new MezzePromise(thread, null, promise);

        if (trackerObj instanceof PromiseTracker tracker) {
            tracker.track(tracked);
        }

        return tracked;
    }

    // ==================== Executors ====================

    // Virtual thread executor - unbounded, uses ForkJoinPool carrier threads
    private static final ExecutorService VIRTUAL_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    // OS thread executor - work-stealing for better CPU utilization
    private static final ForkJoinPool OS_EXECUTOR = new ForkJoinPool(
        Runtime.getRuntime().availableProcessors(),
        ForkJoinPool.defaultForkJoinWorkerThreadFactory,
        null,
        true // asyncMode for better throughput with async tasks
    );

    // Semaphore cache for concurrency limiting
    private static final ConcurrentHashMap<Integer, Semaphore> SEMAPHORE_CACHE = new ConcurrentHashMap<>();

    // ==================== Performance Constants ====================

    // Threshold below which we use sequential execution (thread overhead > benefit)
    private static final int PARALLEL_THRESHOLD = 4;

    // Batch size for chunked parallel operations - ALL parallel ops use batching now
    private static final int BATCH_SIZE = 64;

    // Pre-allocated field name arrays to avoid allocation in hot paths
    private static final String[] X_FIELD = new String[]{"x"};
    private static final String[] LEFT_RIGHT_FIELDS = new String[]{"l", "r"};
    private static final String[] ABC_FIELDS = new String[]{"a", "b", "c"};

    // ==================== OPTIMIZATION: Thread-Local Argument Buffers ====================

    // Thread-local reusable argument arrays to avoid allocation per iteration
    // Key insight: Each thread only needs ONE buffer at a time
    private static final ThreadLocal<Object[]> CALL_ARGS_BUFFER = ThreadLocal.withInitial(() -> new Object[16]);

    // Get or resize thread-local buffer for closure + args
    private static Object[] getCallArgsBuffer(int minSize) {
        Object[] buffer = CALL_ARGS_BUFFER.get();
        if (buffer.length < minSize) {
            buffer = new Object[Math.max(minSize, buffer.length * 2)];
            CALL_ARGS_BUFFER.set(buffer);
        }
        return buffer;
    }

    // ==================== OPTIMIZATION: Sampled Completion Time Tracking ====================

    // Track average completion time in nanoseconds for adaptive spinning
    // OPTIMIZATION: Only sample 1 in 64 completions to reduce nanoTime() overhead
    private static final AtomicLong avgCompletionNanos = new AtomicLong(1000);
    private static final AtomicLong completionCount = new AtomicLong(0);
    private static final int SAMPLE_RATE = 64; // Only measure every 64th completion

    // Update completion time tracking with sampling
    private static void recordCompletionTime(long nanos) {
        long count = completionCount.incrementAndGet();
        // OPTIMIZATION: Only update EMA on sampled completions
        if ((count & (SAMPLE_RATE - 1)) == 0) {
            if (count < 100 * SAMPLE_RATE) {
                avgCompletionNanos.updateAndGet(old -> (old + nanos) / 2);
            } else {
                // Exponential moving average with alpha = 0.1
                avgCompletionNanos.updateAndGet(old -> (long)(old * 0.9 + nanos * 0.1));
            }
        }
    }

    // Calculate adaptive spin count based on historical completion times
    private static int getAdaptiveSpinCount() {
        long avgNanos = avgCompletionNanos.get();
        // Spin for approximately 1/10th of average completion time
        // Bounded between 16 and 1024 iterations
        return (int) Math.min(1024, Math.max(16, avgNanos / 100));
    }

    // ==================== Loom Async Operations ====================

    /**
     * Simple spawn without concurrency limit - for backward compatibility.
     */
    @TruffleBoundary
    public static MezzePromise loomSpawn(MezzeRecord args, IndirectCallNode callNode, MezzeNode node) {
        MezzeFunction f = getFunction(args, "f");

        Object[] closureValues = f.getClosureValues();
        var callTarget = f.getCallTarget();
        MezzeContext context = MezzeContext.get(node);
        Env env = context.getEnv();

        // Build argument array: closure values + empty record argument
        // Thunk functions expect {} as argument
        Object[] fullArgs = new Object[closureValues.length + 1];
        System.arraycopy(closureValues, 0, fullArgs, 0, closureValues.length);
        fullArgs[closureValues.length] = MezzeRecord.empty();

        // LAZY EFFECTS: Capture parent's handlers to propagate to spawned thread
        java.util.Map<Hash, Object> parentHandlers = context.getAllCurrentHandlers();

        MezzePromise promise = new MezzePromise(null, null);

        Thread vThread = env.newTruffleThreadBuilder(() -> {
            try {
                // LAZY EFFECTS: Push parent handlers onto this thread's stack
                MezzeContext threadContext = MezzeContext.get(node);
                if (parentHandlers != null && !parentHandlers.isEmpty()) {
                    threadContext.pushHandlers(parentHandlers);
                }

                try {
                    Object result = callNode.call(callTarget, fullArgs);
                    promise.complete(result);
                } finally {
                    // LAZY EFFECTS: Pop handlers when thread completes
                    if (parentHandlers != null && !parentHandlers.isEmpty()) {
                        threadContext.popHandlers();
                    }
                }
            } catch (Throwable e) {
                promise.fail(e);
            }
        }).virtual(true).build();

        vThread.start();
        return new MezzePromise(vThread, null, promise);
    }

    /**
     * Spawn all functions in parallel, await all, and return array of results.
     * This is the semantics expected by std::effects::concurrency::all.
     * Returns Object[] to match Mezze array representation in V2.
     */
    @TruffleBoundary
    public static Object[] loomSpawnAll(MezzeRecord args, IndirectCallNode callNode, MezzeNode node) {
        Object[] functions = getArray(args, "fs");
        MezzeContext context = MezzeContext.get(node);
        Env env = context.getEnv();

        // LAZY EFFECTS: Capture parent's handlers to propagate to spawned threads
        java.util.Map<Hash, Object> parentHandlers = context.getAllCurrentHandlers();

        // Pre-sized array - we know exactly how many promises we'll create
        int numFunctions = functions.length;
        MezzePromise[] promises = new MezzePromise[numFunctions];

        for (int idx = 0; idx < numFunctions; idx++) {
            Object item = functions[idx];
            if (!(item instanceof MezzeFunction f)) {
                throw new RuntimeException("__native_loom_spawn_all expects list of functions");
            }

            Object[] closureValues = f.getClosureValues();
            var callTarget = f.getCallTarget();

            // Build argument array: closure values + empty record argument
            // Thunk functions expect {} as argument
            Object[] fullArgs = new Object[closureValues.length + 1];
            System.arraycopy(closureValues, 0, fullArgs, 0, closureValues.length);
            fullArgs[closureValues.length] = MezzeRecord.empty();

            MezzePromise promise = new MezzePromise(null, null);

            Thread vThread = env.newTruffleThreadBuilder(() -> {
                try {
                    // LAZY EFFECTS: Push parent handlers onto this thread's stack
                    MezzeContext threadContext = MezzeContext.get(node);
                    if (parentHandlers != null && !parentHandlers.isEmpty()) {
                        threadContext.pushHandlers(parentHandlers);
                    }

                    try {
                        Object result = callNode.call(callTarget, fullArgs);
                        promise.complete(result);
                    } finally {
                        // LAZY EFFECTS: Pop handlers when thread completes
                        if (parentHandlers != null && !parentHandlers.isEmpty()) {
                            threadContext.popHandlers();
                        }
                    }
                } catch (Throwable e) {
                    promise.fail(e);
                }
            }).virtual(true).build();

            vThread.start();
            promises[idx] = new MezzePromise(vThread, null, promise);
        }

        // Await all promises and collect results into an array
        Object[] results = new Object[numFunctions];
        for (int i = 0; i < numFunctions; i++) {
            try {
                results[i] = promises[i].await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while awaiting promise", e);
            }
        }

        return results;
    }

    @TruffleBoundary
    public static MezzePromise loomSpawnCfg(MezzeRecord args, IndirectCallNode callNode, MezzeNode node) {
        MezzeFunction f = getFunction(args, "f");
        long maxConcurrent = getLong(args, "max_concurrent");

        Semaphore semaphore = maxConcurrent > 0
            ? SEMAPHORE_CACHE.computeIfAbsent((int) maxConcurrent, Semaphore::new)
            : null;

        // Pre-extract closure values to avoid allocation in thread
        Object[] closureValues = f.getClosureValues();
        var callTarget = f.getCallTarget();

        // Get Truffle environment for proper thread creation
        MezzeContext context = MezzeContext.get(node);
        Env env = context.getEnv();

        // LAZY EFFECTS: Capture parent's handlers to propagate to spawned thread
        java.util.Map<Hash, Object> parentHandlers = context.getAllCurrentHandlers();

        // Create promise first with placeholder thread
        MezzePromise promise = new MezzePromise(null, semaphore);

        // CRITICAL: Use TruffleThreadBuilder for proper context sharing across threads
        // This avoids "Invalid sharing of AST nodes detected" errors
        Thread vThread = env.newTruffleThreadBuilder(() -> {
            try {
                // LAZY EFFECTS: Push parent handlers onto this thread's stack
                MezzeContext threadContext = MezzeContext.get(node);
                if (parentHandlers != null && !parentHandlers.isEmpty()) {
                    threadContext.pushHandlers(parentHandlers);
                }

                try {
                    if (semaphore != null) {
                        semaphore.acquireUninterruptibly();
                    }
                    try {
                        Object result = callNode.call(callTarget, closureValues);
                        promise.complete(result);
                    } finally {
                        if (semaphore != null) {
                            semaphore.release();
                        }
                    }
                } finally {
                    // LAZY EFFECTS: Pop handlers when thread completes
                    if (parentHandlers != null && !parentHandlers.isEmpty()) {
                        threadContext.popHandlers();
                    }
                }
            } catch (Throwable e) {
                promise.fail(e);
            }
        }).virtual(true).build();  // Use virtual threads for Project Loom

        vThread.start();

        // Return promise with thread reference for cancellation
        return new MezzePromise(vThread, semaphore, promise);
    }

    @TruffleBoundary
    public static Object loomAwait(MezzeRecord args) {
        MezzePromise promise = getPromise(args, "p");
        try {
            return promise.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while awaiting", e);
        }
    }

    /**
     * OPTIMIZATION: Custom lightweight race without CompletableFuture overhead.
     * Uses direct polling with adaptive backoff instead of spawning N extra threads.
     */
    @TruffleBoundary
    public static Object loomRace(MezzeRecord args) {
        Object[] promises = getArray(args, "ps");
        if (promises.length == 0) {
            throw new RuntimeException("race requires at least one promise");
        }

        // Fast path: single promise
        if (promises.length == 1) {
            return loomAwaitPromise((MezzePromise) promises[0]);
        }

        // OPTIMIZATION: Direct polling loop with adaptive backoff
        // Much faster than creating N CompletableFutures + N virtual threads

        // Phase 1: Quick spin check (many promises may already be done)
        for (int spin = 0; spin < 64; spin++) {
            for (Object p : promises) {
                MezzePromise promise = (MezzePromise) p;
                if (promise.isDone()) {
                    if (promise.isCompleted()) {
                        return promise.getResult();
                    } else if (promise.isFailed()) {
                        throw new RuntimeException("Task failed: " + promise.result);
                    }
                    // Cancelled - continue checking others
                }
            }
            Thread.onSpinWait();
        }

        // Phase 2: Park with periodic wake-up to check all promises
        // Uses exponential backoff: 100us -> 200us -> 400us -> ... -> 10ms max
        long parkNanos = 100_000; // Start at 100 microseconds
        final long maxParkNanos = 10_000_000; // Cap at 10 milliseconds

        while (true) {
            for (Object p : promises) {
                MezzePromise promise = (MezzePromise) p;
                if (promise.isDone()) {
                    if (promise.isCompleted()) {
                        return promise.getResult();
                    } else if (promise.isFailed()) {
                        throw new RuntimeException("Task failed: " + promise.result);
                    }
                }
            }

            // Check for interruption
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted during race");
            }

            // Park with backoff
            LockSupport.parkNanos(parkNanos);
            parkNanos = Math.min(parkNanos * 2, maxParkNanos);
        }
    }

    @TruffleBoundary
    public static MezzeSeq loomAll(MezzeRecord args) {
        Object[] promises = getArray(args, "ps");
        Object[] results = new Object[promises.length];

        // Fast path: empty array
        if (promises.length == 0) {
            return MezzeSeq.empty();
        }

        // Fast path: single promise
        if (promises.length == 1) {
            results[0] = loomAwaitPromise((MezzePromise) promises[0]);
            return MezzeSeq.fromArray(results);
        }

        // OPTIMIZATION: For many promises, use batched polling instead of serial await
        // This allows us to quickly collect already-completed promises
        if (promises.length > 8) {
            return batchedAll(promises, results);
        }

        // Small number of promises: simple sequential await
        for (int i = 0; i < promises.length; i++) {
            results[i] = loomAwaitPromise((MezzePromise) promises[i]);
        }

        return MezzeSeq.fromArray(results);
    }

    /**
     * OPTIMIZATION: Batched await for many promises.
     * First collects all already-done promises, then awaits remaining.
     * This avoids blocking on one slow promise when others are ready.
     */
    private static MezzeSeq batchedAll(Object[] promises, Object[] results) {
        int remaining = promises.length;
        boolean[] collected = new boolean[promises.length];

        // Phase 1: Quick spin to collect already-done promises
        for (int spin = 0; spin < 32 && remaining > 0; spin++) {
            for (int i = 0; i < promises.length; i++) {
                if (!collected[i]) {
                    MezzePromise p = (MezzePromise) promises[i];
                    if (p.isDone()) {
                        results[i] = getPromiseResult(p);
                        collected[i] = true;
                        remaining--;
                    }
                }
            }
            if (remaining > 0) {
                Thread.onSpinWait();
            }
        }

        // Phase 2: Await any remaining promises
        for (int i = 0; i < promises.length; i++) {
            if (!collected[i]) {
                results[i] = loomAwaitPromise((MezzePromise) promises[i]);
            }
        }

        return MezzeSeq.fromArray(results);
    }

    /**
     * Gets result from a done promise, throwing on failure.
     */
    private static Object getPromiseResult(MezzePromise promise) {
        if (promise.isCompleted()) {
            return promise.getResult();
        } else if (promise.isFailed()) {
            throw new RuntimeException("Task failed: " + promise.getResult());
        } else {
            throw new CancellationException("Task was cancelled");
        }
    }

    @TruffleBoundary
    public static MezzeVariant loomTimeout(MezzeRecord args) {
        long ms = getLong(args, "ms");
        MezzePromise promise = getPromise(args, "p");

        try {
            Object result = promise.await(ms);
            return MezzeVariant.some(result);
        } catch (TimeoutException e) {
            return MezzeVariant.none();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return MezzeVariant.none();
        }
    }

    @TruffleBoundary
    public static MezzeUnit loomCancel(MezzeRecord args) {
        MezzePromise promise = getPromise(args, "p");
        promise.cancel();
        return MezzeUnit.INSTANCE;
    }

    @TruffleBoundary
    public static MezzeUnit loomSleep(MezzeRecord args) {
        long ms = getLong(args, "ms");
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Sleep interrupted", e);
        }
        return MezzeUnit.INSTANCE;
    }

    // OPTIMIZATION 5: No @TruffleBoundary for simple accessors - allows Graal inlining
    public static boolean loomIsDone(MezzeRecord args) {
        return getPromise(args, "p").isDone();
    }

    // OPTIMIZATION 5: No @TruffleBoundary - simple thread state check
    public static boolean loomIsCancelled(MezzeRecord args) {
        return Thread.currentThread().isInterrupted();
    }

    // OPTIMIZATION 5: No @TruffleBoundary - simple check + throw
    public static MezzeUnit loomCheck(MezzeRecord args) {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Task was cancelled");
        }
        return MezzeUnit.INSTANCE;
    }

    @TruffleBoundary
    public static MezzeRecord loomJoin(MezzeRecord args, IndirectCallNode callNode, MezzeNode node) {
        MezzeFunction left = getFunction(args, "l");
        MezzeFunction right = getFunction(args, "r");

        Object[] leftClosure = left.getClosureValues();
        Object[] rightClosure = right.getClosureValues();
        var leftTarget = left.getCallTarget();
        var rightTarget = right.getCallTarget();

        // Build argument arrays: closure values + empty record argument
        // Thunk functions expect {} as argument
        Object[] leftArgs = new Object[leftClosure.length + 1];
        System.arraycopy(leftClosure, 0, leftArgs, 0, leftClosure.length);
        leftArgs[leftClosure.length] = MezzeRecord.empty();

        Object[] rightArgs = new Object[rightClosure.length + 1];
        System.arraycopy(rightClosure, 0, rightArgs, 0, rightClosure.length);
        rightArgs[rightClosure.length] = MezzeRecord.empty();

        MezzeContext context = MezzeContext.get(node);
        Env env = context.getEnv();

        // LAZY EFFECTS: Capture parent's handlers to propagate to spawned threads
        java.util.Map<Hash, Object> parentHandlers = context.getAllCurrentHandlers();

        // Use CompletableFuture for result coordination
        CompletableFuture<Object> leftFuture = new CompletableFuture<>();
        CompletableFuture<Object> rightFuture = new CompletableFuture<>();

        // Spawn Truffle-aware threads for both computations
        Thread leftThread = env.newTruffleThreadBuilder(() -> {
            try {
                // LAZY EFFECTS: Push parent handlers onto this thread's stack
                MezzeContext threadContext = MezzeContext.get(node);
                if (parentHandlers != null && !parentHandlers.isEmpty()) {
                    threadContext.pushHandlers(parentHandlers);
                }
                try {
                    leftFuture.complete(callNode.call(leftTarget, leftArgs));
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
                // LAZY EFFECTS: Push parent handlers onto this thread's stack
                MezzeContext threadContext = MezzeContext.get(node);
                if (parentHandlers != null && !parentHandlers.isEmpty()) {
                    threadContext.pushHandlers(parentHandlers);
                }
                try {
                    rightFuture.complete(callNode.call(rightTarget, rightArgs));
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
            // Wait for both
            Object leftResult = leftFuture.get();
            Object rightResult = rightFuture.get();
            // OPTIMIZATION: Use cached field name array
            return MezzeRecord.fromFields(LEFT_RIGHT_FIELDS, new Object[]{leftResult, rightResult});
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

    @TruffleBoundary
    public static MezzeRecord loomJoin3(MezzeRecord args, IndirectCallNode callNode, MezzeNode node) {
        MezzeFunction a = getFunction(args, "a");
        MezzeFunction b = getFunction(args, "b");
        MezzeFunction c = getFunction(args, "c");

        MezzeContext context = MezzeContext.get(node);
        Env env = context.getEnv();

        // LAZY EFFECTS: Capture parent's handlers to propagate to spawned threads
        java.util.Map<Hash, Object> parentHandlers = context.getAllCurrentHandlers();

        CompletableFuture<Object> aFuture = new CompletableFuture<>();
        CompletableFuture<Object> bFuture = new CompletableFuture<>();
        CompletableFuture<Object> cFuture = new CompletableFuture<>();

        Thread aThread = env.newTruffleThreadBuilder(() -> {
            try {
                // LAZY EFFECTS: Push parent handlers onto this thread's stack
                MezzeContext threadContext = MezzeContext.get(node);
                if (parentHandlers != null && !parentHandlers.isEmpty()) {
                    threadContext.pushHandlers(parentHandlers);
                }
                try {
                    aFuture.complete(callThunkFunction(a, callNode));
                } finally {
                    if (parentHandlers != null && !parentHandlers.isEmpty()) {
                        threadContext.popHandlers();
                    }
                }
            } catch (Throwable e) {
                aFuture.completeExceptionally(e);
            }
        }).virtual(true).build();

        Thread bThread = env.newTruffleThreadBuilder(() -> {
            try {
                // LAZY EFFECTS: Push parent handlers onto this thread's stack
                MezzeContext threadContext = MezzeContext.get(node);
                if (parentHandlers != null && !parentHandlers.isEmpty()) {
                    threadContext.pushHandlers(parentHandlers);
                }
                try {
                    bFuture.complete(callThunkFunction(b, callNode));
                } finally {
                    if (parentHandlers != null && !parentHandlers.isEmpty()) {
                        threadContext.popHandlers();
                    }
                }
            } catch (Throwable e) {
                bFuture.completeExceptionally(e);
            }
        }).virtual(true).build();

        Thread cThread = env.newTruffleThreadBuilder(() -> {
            try {
                // LAZY EFFECTS: Push parent handlers onto this thread's stack
                MezzeContext threadContext = MezzeContext.get(node);
                if (parentHandlers != null && !parentHandlers.isEmpty()) {
                    threadContext.pushHandlers(parentHandlers);
                }
                try {
                    cFuture.complete(callThunkFunction(c, callNode));
                } finally {
                    if (parentHandlers != null && !parentHandlers.isEmpty()) {
                        threadContext.popHandlers();
                    }
                }
            } catch (Throwable e) {
                cFuture.completeExceptionally(e);
            }
        }).virtual(true).build();

        aThread.start();
        bThread.start();
        cThread.start();

        try {
            return MezzeRecord.fromFields(
                ABC_FIELDS,
                new Object[]{aFuture.get(), bFuture.get(), cFuture.get()}
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            aThread.interrupt();
            bThread.interrupt();
            cThread.interrupt();
            throw new RuntimeException("Join3 interrupted", e);
        } catch (ExecutionException e) {
            aThread.interrupt();
            bThread.interrupt();
            cThread.interrupt();
            throw new RuntimeException("Join3 failed: " + e.getCause().getMessage(), e.getCause());
        }
    }

    /**
     * OPTIMIZATION: All parallel map operations use batching.
     * - Small arrays (< 4): Sequential execution
     * - All other arrays: Batched ForkJoin (no 1-thread-per-element)
     */
    @TruffleBoundary
    public static MezzeSeq loomMap(MezzeRecord args, IndirectCallNode callNode, MezzeNode node) {
        Object[] xs = getArray(args, "xs");
        MezzeFunction f = getFunction(args, "f");

        if (xs.length == 0) {
            return MezzeSeq.empty();
        }

        // Sequential fallback for small arrays
        if (xs.length < PARALLEL_THRESHOLD) {
            return sequentialMapInternal(xs, f, callNode);
        }

        // OPTIMIZATION: ALL parallel operations use batching now
        // No more 1-thread-per-element which has high overhead
        return batchedMapWithForkJoin(xs, f, callNode, node);
    }

    /**
     * Batched parallel map using Truffle virtual threads.
     * Uses virtual threads to ensure proper Truffle context propagation.
     */
    private static MezzeSeq batchedMapWithForkJoin(Object[] xs, MezzeFunction f,
                                                     IndirectCallNode callNode, MezzeNode node) {
        var callTarget = f.getCallTarget();
        Object[] closure = f.getClosureValues();
        final int closureLen = closure.length;
        Object[] results = new Object[xs.length];

        // Get Truffle environment for creating proper threads
        MezzeContext context = MezzeContext.get(node);
        Env env = context.getEnv();

        // Calculate number of batches - use more batches for better parallelism
        int numCores = Runtime.getRuntime().availableProcessors();
        int batchSize = Math.max(1, xs.length / (numCores * 2)); // At least 2 batches per core
        batchSize = Math.min(batchSize, BATCH_SIZE); // But not larger than BATCH_SIZE
        int numBatches = (xs.length + batchSize - 1) / batchSize;

        // Use Truffle virtual threads for proper context handling
        Thread[] threads = new Thread[numBatches];
        final int finalBatchSize = batchSize;

        for (int b = 0; b < numBatches; b++) {
            final int batchStart = b * finalBatchSize;
            final int batchEnd = Math.min(batchStart + finalBatchSize, xs.length);

            threads[b] = env.newTruffleThreadBuilder(() -> {
                // Use thread-local buffer instead of allocating
                Object[] callArgs = getCallArgsBuffer(closureLen + 1);
                System.arraycopy(closure, 0, callArgs, 0, closureLen);

                for (int i = batchStart; i < batchEnd; i++) {
                    // Wrap element in record - callbacks expect {x} -> ...
                    MezzeRecord arg = MezzeRecord.fromFields(X_FIELD, new Object[]{xs[i]});
                    callArgs[closureLen] = arg;
                    results[i] = callNode.call(callTarget, callArgs);
                }
            }).virtual(true).build();
            threads[b].start();
        }

        // Wait for all batches
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Map interrupted", e);
            }
        }

        return MezzeSeq.fromArray(results);
    }

    // Fast sequential map for small arrays - uses thread-local buffer
    private static MezzeSeq sequentialMapInternal(Object[] xs, MezzeFunction f, IndirectCallNode callNode) {
        var callTarget = f.getCallTarget();
        Object[] closure = f.getClosureValues();
        Object[] results = new Object[xs.length];

        // OPTIMIZATION: Use thread-local buffer
        Object[] callArgs = getCallArgsBuffer(closure.length + 1);
        System.arraycopy(closure, 0, callArgs, 0, closure.length);

        for (int i = 0; i < xs.length; i++) {
            // Wrap element in record - callbacks expect {x} -> ...
            MezzeRecord arg = MezzeRecord.fromFields(X_FIELD, new Object[]{xs[i]});
            callArgs[closure.length] = arg;
            results[i] = callNode.call(callTarget, callArgs);
        }

        return MezzeSeq.fromArray(results);
    }

    /**
     * OPTIMIZATION: All parallel forEach operations use batching.
     * - Small arrays (< 4): Sequential execution
     * - All other arrays: Batched ForkJoin (no 1-thread-per-element)
     */
    @TruffleBoundary
    public static MezzeUnit loomForEach(MezzeRecord args, IndirectCallNode callNode, MezzeNode node) {
        Object[] xs = getArray(args, "xs");
        MezzeFunction f = getFunction(args, "f");

        if (xs.length == 0) {
            return MezzeUnit.INSTANCE;
        }

        // Sequential fallback for small arrays
        if (xs.length < PARALLEL_THRESHOLD) {
            return sequentialForEachInternal(xs, f, callNode);
        }

        // Use Truffle threads for proper context propagation
        return batchedForEachWithTruffleThreads(xs, f, callNode, node);
    }

    /**
     * Batched forEach using Truffle virtual threads.
     * Uses virtual threads to ensure proper Truffle context propagation.
     */
    private static MezzeUnit batchedForEachWithTruffleThreads(Object[] xs, MezzeFunction f, IndirectCallNode callNode, MezzeNode node) {
        var callTarget = f.getCallTarget();
        Object[] closure = f.getClosureValues();
        final int closureLen = closure.length;

        // Get Truffle environment for creating proper threads
        MezzeContext context = MezzeContext.get(node);
        Env env = context.getEnv();

        // Calculate number of batches - use more batches for better parallelism
        int numCores = Runtime.getRuntime().availableProcessors();
        int batchSize = Math.max(1, xs.length / (numCores * 2)); // At least 2 batches per core
        batchSize = Math.min(batchSize, BATCH_SIZE); // But not larger than BATCH_SIZE
        int numBatches = (xs.length + batchSize - 1) / batchSize;

        Thread[] threads = new Thread[numBatches];
        final int finalBatchSize = batchSize;

        for (int b = 0; b < numBatches; b++) {
            final int batchStart = b * finalBatchSize;
            final int batchEnd = Math.min(batchStart + finalBatchSize, xs.length);

            threads[b] = env.newTruffleThreadBuilder(() -> {
                // Use thread-local buffer instead of allocating
                Object[] callArgs = getCallArgsBuffer(closureLen + 1);
                System.arraycopy(closure, 0, callArgs, 0, closureLen);

                for (int i = batchStart; i < batchEnd; i++) {
                    // Wrap element in record - callbacks expect {x} -> ...
                    MezzeRecord arg = MezzeRecord.fromFields(X_FIELD, new Object[]{xs[i]});
                    callArgs[closureLen] = arg;
                    callNode.call(callTarget, callArgs);
                }
            }).virtual(true).build();
            threads[b].start();
        }

        // Wait for all batches
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("ForEach interrupted", e);
            }
        }

        return MezzeUnit.INSTANCE;
    }

    // Fast sequential forEach for small arrays - uses thread-local buffer
    private static MezzeUnit sequentialForEachInternal(Object[] xs, MezzeFunction f, IndirectCallNode callNode) {
        var callTarget = f.getCallTarget();
        Object[] closure = f.getClosureValues();

        // OPTIMIZATION: Use thread-local buffer
        Object[] callArgs = getCallArgsBuffer(closure.length + 1);
        System.arraycopy(closure, 0, callArgs, 0, closure.length);

        for (Object x : xs) {
            // Wrap element in record - callbacks expect {x} -> ...
            MezzeRecord arg = MezzeRecord.fromFields(X_FIELD, new Object[]{x});
            callArgs[closure.length] = arg;
            callNode.call(callTarget, callArgs);
        }
        return MezzeUnit.INSTANCE;
    }

    /**
     * OPTIMIZATION: All parallel filter operations use batching.
     * - Small arrays (< 4): Sequential execution
     * - All other arrays: Batched ForkJoin (no 1-thread-per-element)
     */
    @TruffleBoundary
    public static MezzeSeq loomFilter(MezzeRecord args, IndirectCallNode callNode, MezzeNode node) {
        Object[] xs = getArray(args, "xs");
        MezzeFunction f = getFunction(args, "f");

        if (xs.length == 0) {
            return MezzeSeq.empty();
        }

        // Sequential fallback for small arrays
        if (xs.length < PARALLEL_THRESHOLD) {
            return sequentialFilterInternal(xs, f, callNode);
        }

        // Use Truffle threads for proper context propagation
        return batchedFilterWithTruffleThreads(xs, f, callNode, node);
    }

    /**
     * Batched filter using Truffle virtual threads.
     * Uses virtual threads to ensure proper Truffle context propagation.
     */
    private static MezzeSeq batchedFilterWithTruffleThreads(Object[] xs, MezzeFunction f, IndirectCallNode callNode, MezzeNode node) {
        var callTarget = f.getCallTarget();
        Object[] closure = f.getClosureValues();
        final int closureLen = closure.length;

        // Get Truffle environment for creating proper threads
        MezzeContext context = MezzeContext.get(node);
        Env env = context.getEnv();

        // Use array of booleans to track which elements pass the filter
        boolean[] passed = new boolean[xs.length];

        // Calculate number of batches - use more batches for better parallelism
        int numCores = Runtime.getRuntime().availableProcessors();
        int batchSize = Math.max(1, xs.length / (numCores * 2)); // At least 2 batches per core
        batchSize = Math.min(batchSize, BATCH_SIZE); // But not larger than BATCH_SIZE
        int numBatches = (xs.length + batchSize - 1) / batchSize;

        Thread[] threads = new Thread[numBatches];
        final int finalBatchSize = batchSize;

        for (int b = 0; b < numBatches; b++) {
            final int batchStart = b * finalBatchSize;
            final int batchEnd = Math.min(batchStart + finalBatchSize, xs.length);

            threads[b] = env.newTruffleThreadBuilder(() -> {
                // Use thread-local buffer instead of allocating
                Object[] callArgs = getCallArgsBuffer(closureLen + 1);
                System.arraycopy(closure, 0, callArgs, 0, closureLen);

                for (int i = batchStart; i < batchEnd; i++) {
                    // Wrap element in record - callbacks expect {x} -> ...
                    MezzeRecord arg = MezzeRecord.fromFields(X_FIELD, new Object[]{xs[i]});
                    callArgs[closureLen] = arg;
                    Object result = callNode.call(callTarget, callArgs);
                    passed[i] = result instanceof Boolean && (Boolean) result;
                }
            }).virtual(true).build();
            threads[b].start();
        }

        // Wait for all threads
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Filter interrupted", e);
            }
        }

        // Collect results
        List<Object> results = new ArrayList<>();
        for (int i = 0; i < xs.length; i++) {
            if (passed[i]) {
                results.add(xs[i]);
            }
        }
        return MezzeSeq.fromArray(results.toArray());
    }

    // Fast sequential filter for small arrays - uses thread-local buffer
    private static MezzeSeq sequentialFilterInternal(Object[] xs, MezzeFunction f, IndirectCallNode callNode) {
        var callTarget = f.getCallTarget();
        Object[] closure = f.getClosureValues();

        // OPTIMIZATION: Use thread-local buffer
        Object[] callArgs = getCallArgsBuffer(closure.length + 1);
        System.arraycopy(closure, 0, callArgs, 0, closure.length);

        List<Object> results = new ArrayList<>();
        for (Object x : xs) {
            // Wrap element in record - callbacks expect {x} -> ... (use fast wrapper)
            callArgs[closure.length] = MezzeRecord.wrapX(x);
            Object result = callNode.call(callTarget, callArgs);
            if (result instanceof Boolean && (Boolean) result) {
                results.add(x);
            }
        }
        return MezzeSeq.fromArray(results.toArray());
    }

    /**
     * OPTIMIZATION: Returns first successfully completed promise.
     * Uses polling-based approach to avoid blocking on a slow promise
     * when a fast one completes.
     */
    @TruffleBoundary
    public static Object loomAny(MezzeRecord args) {
        Object[] promises = getArray(args, "ps");
        if (promises.length == 0) {
            throw new RuntimeException("any requires at least one promise");
        }

        // Fast path: single promise
        if (promises.length == 1) {
            return loomAwaitPromise((MezzePromise) promises[0]);
        }

        List<Throwable> failures = new ArrayList<>();
        boolean[] failed = new boolean[promises.length];
        int failedCount = 0;

        // Phase 1: Quick spin to find already-done promises
        for (int spin = 0; spin < 64; spin++) {
            for (int i = 0; i < promises.length; i++) {
                if (failed[i]) continue;
                MezzePromise promise = (MezzePromise) promises[i];
                if (promise.isDone()) {
                    if (promise.isCompleted()) {
                        return promise.getResult();
                    } else if (promise.isFailed()) {
                        failures.add((Throwable) promise.getResult());
                        failed[i] = true;
                        failedCount++;
                    }
                    // Cancelled - keep checking others
                }
            }
            if (failedCount == promises.length) {
                throw new RuntimeException("All tasks failed. First: " + failures.get(0).getMessage());
            }
            Thread.onSpinWait();
        }

        // Phase 2: Park with periodic wake-up
        long parkNanos = 100_000; // 100us
        final long maxParkNanos = 10_000_000; // 10ms

        while (true) {
            for (int i = 0; i < promises.length; i++) {
                if (failed[i]) continue;
                MezzePromise promise = (MezzePromise) promises[i];
                if (promise.isDone()) {
                    if (promise.isCompleted()) {
                        return promise.getResult();
                    } else if (promise.isFailed()) {
                        failures.add((Throwable) promise.getResult());
                        failed[i] = true;
                        failedCount++;
                    }
                }
            }

            if (failedCount == promises.length) {
                throw new RuntimeException("All tasks failed. First: " + failures.get(0).getMessage());
            }

            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted during any");
            }

            LockSupport.parkNanos(parkNanos);
            parkNanos = Math.min(parkNanos * 2, maxParkNanos);
        }
    }

    @TruffleBoundary
    public static MezzeSeq loomSettle(MezzeRecord args) {
        Object[] promises = getArray(args, "ps");
        Object[] results = new Object[promises.length];

        for (int i = 0; i < promises.length; i++) {
            MezzePromise promise = (MezzePromise) promises[i];
            try {
                Object value = promise.await();
                results[i] = MezzeVariant.ok(value);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                results[i] = MezzeVariant.err(createAsyncCancelled());
            } catch (CancellationException e) {
                results[i] = MezzeVariant.err(createAsyncCancelled());
            } catch (RuntimeException e) {
                results[i] = MezzeVariant.err(createAsyncFailed(e.getMessage()));
            }
        }

        return MezzeSeq.fromArray(results);
    }

    @TruffleBoundary
    public static Object loomScoped(MezzeRecord args, IndirectCallNode callNode, MezzeNode node) {
        MezzeFunction f = getFunction(args, "f");
        MezzeScope scope = new MezzeScope();

        // Store env for scope-spawned children
        Env env = MezzeContext.get(node).getEnv();
        scope.env = env;

        try {
            MezzeRecord scopeArg = MezzeRecord.fromFields(
                new String[]{"sc"},
                new Object[]{scope}
            );
            Object result = callNode.call(f.getCallTarget(), appendArg(f.getClosureValues(), scopeArg));

            // Wait for all children
            scope.awaitAll();

            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scope.cancelAll();
            throw new RuntimeException("Scoped execution interrupted", e);
        } catch (RuntimeException e) {
            scope.cancelAll();
            throw e;
        }
    }

    @TruffleBoundary
    public static MezzePromise loomScopeSpawn(MezzeRecord args, IndirectCallNode callNode, MezzeNode node) {
        MezzeScope scope = getScope(args, "sc");
        MezzeFunction f = getFunction(args, "f");

        if (scope.isCancelled()) {
            MezzePromise cancelled = new MezzePromise(null, null);
            cancelled.cancel();
            return cancelled;
        }

        var callTarget = f.getCallTarget();
        Object[] closure = f.getClosureValues();

        // Use env from scope or from node
        MezzeContext context = MezzeContext.get(node);
        Env env = scope.env != null ? scope.env : context.getEnv();

        // LAZY EFFECTS: Capture parent's handlers to propagate to spawned thread
        java.util.Map<Hash, Object> parentHandlers = context.getAllCurrentHandlers();

        MezzePromise promise = new MezzePromise(null, null);
        scope.addChild(promise);

        Thread vThread = env.newTruffleThreadBuilder(() -> {
            try {
                // LAZY EFFECTS: Push parent handlers onto this thread's stack
                MezzeContext threadContext = MezzeContext.get(node);
                if (parentHandlers != null && !parentHandlers.isEmpty()) {
                    threadContext.pushHandlers(parentHandlers);
                }

                try {
                    if (scope.isCancelled()) {
                        promise.cancel();
                        return;
                    }
                    Object result = callNode.call(callTarget, closure);
                    promise.complete(result);
                } finally {
                    // LAZY EFFECTS: Pop handlers when thread completes
                    if (parentHandlers != null && !parentHandlers.isEmpty()) {
                        threadContext.popHandlers();
                    }
                }
            } catch (Throwable e) {
                promise.fail(e);
            } finally {
                scope.childCompleted();
            }
        }).virtual(true).build();

        vThread.start();

        return promise;
    }

    @TruffleBoundary
    public static MezzeUnit loomScopeCancelAll(MezzeRecord args) {
        getScope(args, "sc").cancelAll();
        return MezzeUnit.INSTANCE;
    }

    @TruffleBoundary
    public static boolean loomScopeIsCancelled(MezzeRecord args) {
        return getScope(args, "sc").isCancelled();
    }

    @TruffleBoundary
    public static MezzeRecord loomScopeGetToken(MezzeRecord args) {
        MezzeScope scope = getScope(args, "sc");
        return MezzeRecord.fromFields(
            new String[]{"_scope_ref"},
            new Object[]{scope}
        );
    }

    // ==================== OS Thread Operations ====================

    @TruffleBoundary
    public static MezzePromise osSpawnCfg(MezzeRecord args, IndirectCallNode callNode, MezzeNode node) {
        MezzeFunction f = getFunction(args, "f");
        long poolSize = getLong(args, "pool_size");

        var callTarget = f.getCallTarget();
        Object[] closureValues = f.getClosureValues();

        // Build argument array: closure values + empty record argument
        // Thunk functions expect {} as argument
        Object[] fullArgs = new Object[closureValues.length + 1];
        System.arraycopy(closureValues, 0, fullArgs, 0, closureValues.length);
        fullArgs[closureValues.length] = MezzeRecord.empty();

        MezzeContext context = MezzeContext.get(node);
        Env env = context.getEnv();

        // LAZY EFFECTS: Capture parent's handlers to propagate to spawned thread
        java.util.Map<Hash, Object> parentHandlers = context.getAllCurrentHandlers();

        MezzePromise promise = new MezzePromise(null, null);

        // Use TruffleThreadBuilder for proper context handling
        // Note: .virtual(false) for OS threads (platform threads)
        Thread thread = env.newTruffleThreadBuilder(() -> {
            try {
                // LAZY EFFECTS: Push parent handlers onto this thread's stack
                MezzeContext threadContext = MezzeContext.get(node);
                if (parentHandlers != null && !parentHandlers.isEmpty()) {
                    threadContext.pushHandlers(parentHandlers);
                }

                try {
                    Object result = callNode.call(callTarget, fullArgs);
                    promise.complete(result);
                } finally {
                    // LAZY EFFECTS: Pop handlers when thread completes
                    if (parentHandlers != null && !parentHandlers.isEmpty()) {
                        threadContext.popHandlers();
                    }
                }
            } catch (Throwable e) {
                promise.fail(e);
            }
        }).build();  // Platform thread (not virtual)

        thread.start();

        return promise;
    }

    // OS versions reuse the same await/race/all/etc. implementations
    // since they all work with MezzePromise

    @TruffleBoundary
    public static Object osAwait(MezzeRecord args) {
        return loomAwait(args);
    }

    @TruffleBoundary
    public static Object osRace(MezzeRecord args) {
        return loomRace(args);
    }

    @TruffleBoundary
    public static MezzeSeq osAll(MezzeRecord args) {
        return loomAll(args);
    }

    @TruffleBoundary
    public static MezzeVariant osTimeout(MezzeRecord args) {
        return loomTimeout(args);
    }

    @TruffleBoundary
    public static MezzeUnit osCancel(MezzeRecord args) {
        return loomCancel(args);
    }

    @TruffleBoundary
    public static MezzeUnit osSleep(MezzeRecord args) {
        return loomSleep(args);
    }

    // OPTIMIZATION 5: No @TruffleBoundary - delegates to loom version
    public static boolean osIsDone(MezzeRecord args) {
        return loomIsDone(args);
    }

    // OPTIMIZATION 5: No @TruffleBoundary
    public static boolean osIsCancelled(MezzeRecord args) {
        return loomIsCancelled(args);
    }

    // OPTIMIZATION 5: No @TruffleBoundary
    public static MezzeUnit osCheck(MezzeRecord args) {
        return loomCheck(args);
    }

    @TruffleBoundary
    public static MezzeRecord osJoin(MezzeRecord args, IndirectCallNode callNode, MezzeNode node) {
        MezzeFunction left = getFunction(args, "l");
        MezzeFunction right = getFunction(args, "r");

        MezzeContext context = MezzeContext.get(node);
        Env env = context.getEnv();

        // LAZY EFFECTS: Capture parent's handlers to propagate to spawned threads
        java.util.Map<Hash, Object> parentHandlers = context.getAllCurrentHandlers();

        CompletableFuture<Object> leftFuture = new CompletableFuture<>();
        CompletableFuture<Object> rightFuture = new CompletableFuture<>();

        Thread leftThread = env.newTruffleThreadBuilder(() -> {
            try {
                // LAZY EFFECTS: Push parent handlers onto this thread's stack
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
        }).build();

        Thread rightThread = env.newTruffleThreadBuilder(() -> {
            try {
                // LAZY EFFECTS: Push parent handlers onto this thread's stack
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
        }).build();

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

    @TruffleBoundary
    public static MezzeRecord osJoin3(MezzeRecord args, IndirectCallNode callNode, MezzeNode node) {
        MezzeFunction a = getFunction(args, "a");
        MezzeFunction b = getFunction(args, "b");
        MezzeFunction c = getFunction(args, "c");

        MezzeContext context = MezzeContext.get(node);
        Env env = context.getEnv();

        // LAZY EFFECTS: Capture parent's handlers to propagate to spawned threads
        java.util.Map<Hash, Object> parentHandlers = context.getAllCurrentHandlers();

        CompletableFuture<Object> aFuture = new CompletableFuture<>();
        CompletableFuture<Object> bFuture = new CompletableFuture<>();
        CompletableFuture<Object> cFuture = new CompletableFuture<>();

        Thread aThread = env.newTruffleThreadBuilder(() -> {
            try {
                // LAZY EFFECTS: Push parent handlers onto this thread's stack
                MezzeContext threadContext = MezzeContext.get(node);
                if (parentHandlers != null && !parentHandlers.isEmpty()) {
                    threadContext.pushHandlers(parentHandlers);
                }
                try {
                    aFuture.complete(callThunkFunction(a, callNode));
                } finally {
                    if (parentHandlers != null && !parentHandlers.isEmpty()) {
                        threadContext.popHandlers();
                    }
                }
            } catch (Throwable e) {
                aFuture.completeExceptionally(e);
            }
        }).build();

        Thread bThread = env.newTruffleThreadBuilder(() -> {
            try {
                // LAZY EFFECTS: Push parent handlers onto this thread's stack
                MezzeContext threadContext = MezzeContext.get(node);
                if (parentHandlers != null && !parentHandlers.isEmpty()) {
                    threadContext.pushHandlers(parentHandlers);
                }
                try {
                    bFuture.complete(callThunkFunction(b, callNode));
                } finally {
                    if (parentHandlers != null && !parentHandlers.isEmpty()) {
                        threadContext.popHandlers();
                    }
                }
            } catch (Throwable e) {
                bFuture.completeExceptionally(e);
            }
        }).build();

        Thread cThread = env.newTruffleThreadBuilder(() -> {
            try {
                // LAZY EFFECTS: Push parent handlers onto this thread's stack
                MezzeContext threadContext = MezzeContext.get(node);
                if (parentHandlers != null && !parentHandlers.isEmpty()) {
                    threadContext.pushHandlers(parentHandlers);
                }
                try {
                    cFuture.complete(callThunkFunction(c, callNode));
                } finally {
                    if (parentHandlers != null && !parentHandlers.isEmpty()) {
                        threadContext.popHandlers();
                    }
                }
            } catch (Throwable e) {
                cFuture.completeExceptionally(e);
            }
        }).build();

        aThread.start();
        bThread.start();
        cThread.start();

        try {
            return MezzeRecord.fromFields(
                ABC_FIELDS,
                new Object[]{aFuture.get(), bFuture.get(), cFuture.get()}
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Join3 interrupted", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Join3 failed: " + e.getCause().getMessage(), e.getCause());
        }
    }

    /**
     * OPTIMIZATION: OS thread map now uses batched execution with bounded thread pool.
     * Instead of creating 1 thread per element (1.3MB each!), we batch elements
     * and use a bounded pool of platform threads.
     */
    @TruffleBoundary
    public static MezzeSeq osMap(MezzeRecord args, IndirectCallNode callNode, MezzeNode node) {
        Object[] xs = getArray(args, "xs");
        MezzeFunction f = getFunction(args, "f");

        if (xs.length == 0) {
            return MezzeSeq.empty();
        }

        // Sequential fallback for small arrays
        if (xs.length < PARALLEL_THRESHOLD) {
            return sequentialMapInternal(xs, f, callNode);
        }

        // OPTIMIZATION: Use batched execution with Truffle threads
        // This reduces thread creation from N to ~numCores threads
        Env env = MezzeContext.get(node).getEnv();
        var callTarget = f.getCallTarget();
        Object[] closure = f.getClosureValues();
        final int closureLen = closure.length;
        Object[] results = new Object[xs.length];

        // Calculate batches - use numCores threads
        int numCores = Runtime.getRuntime().availableProcessors();
        int batchSize = Math.max(1, xs.length / numCores);
        int numBatches = (xs.length + batchSize - 1) / batchSize;

        Thread[] threads = new Thread[numBatches];
        final int finalBatchSize = batchSize;

        for (int b = 0; b < numBatches; b++) {
            final int batchStart = b * finalBatchSize;
            final int batchEnd = Math.min(batchStart + finalBatchSize, xs.length);

            threads[b] = env.newTruffleThreadBuilder(() -> {
                // Thread-local buffer for this batch
                Object[] callArgs = getCallArgsBuffer(closureLen + 1);
                System.arraycopy(closure, 0, callArgs, 0, closureLen);

                for (int i = batchStart; i < batchEnd; i++) {
                    MezzeRecord arg = MezzeRecord.fromFields(X_FIELD, new Object[]{xs[i]});
                    callArgs[closureLen] = arg;
                    results[i] = callNode.call(callTarget, callArgs);
                }
            }).build();  // Platform thread (not virtual)
            threads[b].start();
        }

        // Wait for all batches
        try {
            for (Thread t : threads) {
                t.join();
            }
        } catch (InterruptedException e) {
            for (Thread t : threads) t.interrupt();
            Thread.currentThread().interrupt();
            throw new RuntimeException("Map interrupted", e);
        }

        return MezzeSeq.fromArray(results);
    }

    /**
     * OPTIMIZATION: OS thread forEach now uses batched execution.
     */
    @TruffleBoundary
    public static MezzeUnit osForEach(MezzeRecord args, IndirectCallNode callNode, MezzeNode node) {
        Object[] xs = getArray(args, "xs");
        MezzeFunction f = getFunction(args, "f");

        if (xs.length == 0) {
            return MezzeUnit.INSTANCE;
        }

        // Sequential fallback for small arrays
        if (xs.length < PARALLEL_THRESHOLD) {
            return sequentialForEachInternal(xs, f, callNode);
        }

        // OPTIMIZATION: Batched execution with Truffle threads
        Env env = MezzeContext.get(node).getEnv();
        var callTarget = f.getCallTarget();
        Object[] closure = f.getClosureValues();
        final int closureLen = closure.length;

        int numCores = Runtime.getRuntime().availableProcessors();
        int batchSize = Math.max(1, xs.length / numCores);
        int numBatches = (xs.length + batchSize - 1) / batchSize;

        Thread[] threads = new Thread[numBatches];
        final int finalBatchSize = batchSize;

        for (int b = 0; b < numBatches; b++) {
            final int batchStart = b * finalBatchSize;
            final int batchEnd = Math.min(batchStart + finalBatchSize, xs.length);

            threads[b] = env.newTruffleThreadBuilder(() -> {
                Object[] callArgs = getCallArgsBuffer(closureLen + 1);
                System.arraycopy(closure, 0, callArgs, 0, closureLen);

                for (int i = batchStart; i < batchEnd; i++) {
                    MezzeRecord arg = MezzeRecord.fromFields(X_FIELD, new Object[]{xs[i]});
                    callArgs[closureLen] = arg;
                    callNode.call(callTarget, callArgs);
                }
            }).build();
            threads[b].start();
        }

        try {
            for (Thread t : threads) {
                t.join();
            }
        } catch (InterruptedException e) {
            for (Thread t : threads) t.interrupt();
            Thread.currentThread().interrupt();
            throw new RuntimeException("ForEach interrupted", e);
        }

        return MezzeUnit.INSTANCE;
    }

    /**
     * OPTIMIZATION: OS thread filter now uses batched execution.
     */
    @TruffleBoundary
    public static MezzeSeq osFilter(MezzeRecord args, IndirectCallNode callNode, MezzeNode node) {
        Object[] xs = getArray(args, "xs");
        MezzeFunction f = getFunction(args, "f");

        if (xs.length == 0) {
            return MezzeSeq.empty();
        }

        // Sequential fallback for small arrays
        if (xs.length < PARALLEL_THRESHOLD) {
            return sequentialFilterInternal(xs, f, callNode);
        }

        // OPTIMIZATION: Batched execution with Truffle threads
        Env env = MezzeContext.get(node).getEnv();
        var callTarget = f.getCallTarget();
        Object[] closure = f.getClosureValues();
        final int closureLen = closure.length;

        // Use boolean array for thread-safe results
        boolean[] keep = new boolean[xs.length];

        int numCores = Runtime.getRuntime().availableProcessors();
        int batchSize = Math.max(1, xs.length / numCores);
        int numBatches = (xs.length + batchSize - 1) / batchSize;

        Thread[] threads = new Thread[numBatches];
        final int finalBatchSize = batchSize;

        for (int b = 0; b < numBatches; b++) {
            final int batchStart = b * finalBatchSize;
            final int batchEnd = Math.min(batchStart + finalBatchSize, xs.length);

            threads[b] = env.newTruffleThreadBuilder(() -> {
                Object[] callArgs = getCallArgsBuffer(closureLen + 1);
                System.arraycopy(closure, 0, callArgs, 0, closureLen);

                for (int i = batchStart; i < batchEnd; i++) {
                    MezzeRecord arg = MezzeRecord.fromFields(X_FIELD, new Object[]{xs[i]});
                    callArgs[closureLen] = arg;
                    Object result = callNode.call(callTarget, callArgs);
                    keep[i] = result instanceof Boolean && (Boolean) result;
                }
            }).build();
            threads[b].start();
        }

        try {
            for (Thread t : threads) {
                t.join();
            }
        } catch (InterruptedException e) {
            for (Thread t : threads) t.interrupt();
            Thread.currentThread().interrupt();
            throw new RuntimeException("Filter interrupted", e);
        }

        // Collect results
        List<Object> results = new ArrayList<>();
        for (int i = 0; i < xs.length; i++) {
            if (keep[i]) {
                results.add(xs[i]);
            }
        }

        return MezzeSeq.fromArray(results.toArray());
    }

    @TruffleBoundary
    public static Object osAny(MezzeRecord args) {
        return loomAny(args);
    }

    @TruffleBoundary
    public static MezzeSeq osSettle(MezzeRecord args) {
        return loomSettle(args);
    }

    @TruffleBoundary
    public static Object osScoped(MezzeRecord args, IndirectCallNode callNode, MezzeNode node) {
        return loomScoped(args, callNode, node);
    }

    // ==================== Coop Async Operations ====================
    // Cooperative scheduling using continuation-based execution

    /**
     * Coop promise - wraps result for cooperative scheduler.
     */
    public static final class CoopPromise {
        Object result;
        boolean completed = false;
        Runnable continuation;
    }

    @TruffleBoundary
    public static CoopPromise coopSpawn(MezzeRecord args, IndirectCallNode callNode) {
        MezzeFunction f = getFunction(args, "f");
        // MezzeCont cont = getContinuation(args, "cont"); // For full coop support

        CoopPromise promise = new CoopPromise();

        // In coop mode, we execute immediately (cooperative = no true parallelism)
        try {
            // Build argument array: closure values + empty record argument
            Object[] closureValues = f.getClosureValues();
            Object[] fullArgs = new Object[closureValues.length + 1];
            System.arraycopy(closureValues, 0, fullArgs, 0, closureValues.length);
            fullArgs[closureValues.length] = MezzeRecord.empty();

            promise.result = callNode.call(f.getCallTarget(), fullArgs);
            promise.completed = true;
        } catch (Throwable e) {
            promise.result = e;
            promise.completed = true;
        }

        return promise;
    }

    @TruffleBoundary
    public static Object coopAwait(MezzeRecord args) {
        CoopPromise promise = getCoopPromise(args, "p");

        if (!promise.completed) {
            throw new RuntimeException("Coop promise not ready - scheduler error");
        }

        if (promise.result instanceof Throwable t) {
            throw new RuntimeException("Task failed: " + t.getMessage(), t);
        }

        return promise.result;
    }

    @TruffleBoundary
    public static Object coopRace(MezzeRecord args) {
        Object[] promises = getArray(args, "ps");
        if (promises.length == 0) {
            throw new RuntimeException("race requires at least one promise");
        }

        // In coop mode, first one wins (all are already completed)
        CoopPromise first = (CoopPromise) promises[0];
        if (first.result instanceof Throwable t) {
            throw new RuntimeException("Task failed: " + t.getMessage(), t);
        }
        return first.result;
    }

    @TruffleBoundary
    public static MezzeSeq coopAll(MezzeRecord args) {
        Object[] promises = getArray(args, "ps");
        Object[] results = new Object[promises.length];

        for (int i = 0; i < promises.length; i++) {
            CoopPromise p = (CoopPromise) promises[i];
            if (p.result instanceof Throwable t) {
                throw new RuntimeException("Task failed: " + t.getMessage(), t);
            }
            results[i] = p.result;
        }

        return MezzeSeq.fromArray(results);
    }

    @TruffleBoundary
    public static MezzeVariant coopTimeout(MezzeRecord args) {
        // In coop mode, timeouts don't really apply (synchronous execution)
        CoopPromise promise = getCoopPromise(args, "p");
        if (promise.result instanceof Throwable) {
            return MezzeVariant.none();
        }
        return MezzeVariant.some(promise.result);
    }

    @TruffleBoundary
    public static MezzeUnit coopSleep(MezzeRecord args) {
        // In coop mode, sleep is a no-op (or could yield to scheduler)
        return MezzeUnit.INSTANCE;
    }

    // OPTIMIZATION 5: No @TruffleBoundary - simple field access
    public static boolean coopIsDone(MezzeRecord args) {
        return getCoopPromise(args, "p").completed;
    }

    @TruffleBoundary
    public static MezzeRecord coopJoin(MezzeRecord args, IndirectCallNode callNode) {
        MezzeFunction left = getFunction(args, "l");
        MezzeFunction right = getFunction(args, "r");

        Object leftResult = callThunkFunction(left, callNode);
        Object rightResult = callThunkFunction(right, callNode);

        return MezzeRecord.fromFields(
            LEFT_RIGHT_FIELDS,
            new Object[]{leftResult, rightResult}
        );
    }

    @TruffleBoundary
    public static MezzeRecord coopJoin3(MezzeRecord args, IndirectCallNode callNode) {
        MezzeFunction a = getFunction(args, "a");
        MezzeFunction b = getFunction(args, "b");
        MezzeFunction c = getFunction(args, "c");

        return MezzeRecord.fromFields(
            ABC_FIELDS,
            new Object[]{
                callThunkFunction(a, callNode),
                callThunkFunction(b, callNode),
                callThunkFunction(c, callNode)
            }
        );
    }

    @TruffleBoundary
    public static MezzeSeq coopMap(MezzeRecord args, IndirectCallNode callNode) {
        Object[] xs = getArray(args, "xs");
        MezzeFunction f = getFunction(args, "f");

        Object[] results = new Object[xs.length];
        var callTarget = f.getCallTarget();
        Object[] closure = f.getClosureValues();

        for (int i = 0; i < xs.length; i++) {
            MezzeRecord arg = MezzeRecord.fromFields(new String[]{"x"}, new Object[]{xs[i]});
            results[i] = callNode.call(callTarget, appendArg(closure, arg));
        }

        return MezzeSeq.fromArray(results);
    }

    @TruffleBoundary
    public static MezzeUnit coopForEach(MezzeRecord args, IndirectCallNode callNode) {
        Object[] xs = getArray(args, "xs");
        MezzeFunction f = getFunction(args, "f");

        var callTarget = f.getCallTarget();
        Object[] closure = f.getClosureValues();

        for (Object x : xs) {
            MezzeRecord arg = MezzeRecord.fromFields(new String[]{"x"}, new Object[]{x});
            callNode.call(callTarget, appendArg(closure, arg));
        }

        return MezzeUnit.INSTANCE;
    }

    @TruffleBoundary
    public static MezzeSeq coopFilter(MezzeRecord args, IndirectCallNode callNode) {
        Object[] xs = getArray(args, "xs");
        MezzeFunction f = getFunction(args, "f");

        var callTarget = f.getCallTarget();
        Object[] closure = f.getClosureValues();

        List<Object> results = new ArrayList<>();
        for (Object x : xs) {
            // Use fast wrapper - no @TruffleBoundary
            MezzeRecord arg = MezzeRecord.wrapX(x);
            Object result = callNode.call(callTarget, appendArg(closure, arg));
            if (result instanceof Boolean && (Boolean) result) {
                results.add(x);
            }
        }

        return MezzeSeq.fromArray(results.toArray());
    }

    @TruffleBoundary
    public static Object coopAny(MezzeRecord args) {
        Object[] promises = getArray(args, "ps");
        if (promises.length == 0) {
            throw new RuntimeException("any requires at least one promise");
        }

        for (Object p : promises) {
            CoopPromise promise = (CoopPromise) p;
            if (!(promise.result instanceof Throwable)) {
                return promise.result;
            }
        }

        CoopPromise first = (CoopPromise) promises[0];
        Throwable t = (Throwable) first.result;
        throw new RuntimeException("All tasks failed: " + t.getMessage(), t);
    }

    @TruffleBoundary
    public static MezzeSeq coopSettle(MezzeRecord args) {
        Object[] promises = getArray(args, "ps");
        Object[] results = new Object[promises.length];

        for (int i = 0; i < promises.length; i++) {
            CoopPromise p = (CoopPromise) promises[i];
            if (p.result instanceof Throwable t) {
                results[i] = MezzeVariant.err(createAsyncFailed(t.getMessage()));
            } else {
                results[i] = MezzeVariant.ok(p.result);
            }
        }

        return MezzeSeq.fromArray(results);
    }

    @TruffleBoundary
    public static Object coopScoped(MezzeRecord args, IndirectCallNode callNode) {
        MezzeFunction f = getFunction(args, "f");
        MezzeScope scope = new MezzeScope();

        MezzeRecord scopeArg = MezzeRecord.fromFields(
            new String[]{"sc"},
            new Object[]{scope}
        );

        return callNode.call(f.getCallTarget(), appendArg(f.getClosureValues(), scopeArg));
    }

    // ==================== AsyncError Variant Helpers ====================

    private static MezzeVariant createAsyncCancelled() {
        int tag = MezzeVariant.TagRegistry.getGlobal().getOrCreateTag("AsyncCancelled");
        return MezzeVariant.noPayload(MezzeVariant.TagRegistry.getGlobal(), tag);
    }

    private static MezzeVariant createAsyncFailed(String msg) {
        int tag = MezzeVariant.TagRegistry.getGlobal().getOrCreateTag("AsyncFailed");
        MezzeRecord errRec = MezzeRecord.fromFields(
            new String[]{"msg"},
            new Object[]{msg != null ? msg : "Unknown error"}
        );
        return MezzeVariant.singlePayload(MezzeVariant.TagRegistry.getGlobal(), tag, errRec);
    }

    // ==================== Helper Methods ====================

    private static Object loomAwaitPromise(MezzePromise promise) {
        try {
            return promise.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while awaiting", e);
        }
    }

    private static Object[] appendArg(Object[] closure, Object arg) {
        Object[] result = new Object[closure.length + 1];
        System.arraycopy(closure, 0, result, 0, closure.length);
        result[closure.length] = arg;
        return result;
    }

    private static Object getFieldValue(MezzeRecord r, String name) {
        int idx = r.getFieldIndex(name);
        if (idx < 0) throw new RuntimeException("Missing field '" + name + "' in record: " + r);
        return r.getField(idx);
    }

    private static MezzeFunction getFunction(MezzeRecord r, String name) {
        int idx = r.getFieldIndex(name);
        if (idx < 0) throw new RuntimeException("Missing field '" + name + "' in record: " + r);
        Object val = r.getField(idx);
        if (val instanceof MezzeFunction f) return f;
        throw new RuntimeException("Expected function for '" + name + "', got: " +
            (val == null ? "null" : val.getClass().getName() + " = " + val) +
            ". Full record: " + r);
    }

    private static long getLong(MezzeRecord r, String name) {
        int idx = r.getFieldIndex(name);
        if (idx < 0) throw new RuntimeException("Missing field '" + name + "'");
        Object val = r.getField(idx);
        if (val instanceof Long l) return l;
        throw new RuntimeException("Expected Long for '" + name + "'");
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

    private static MezzeList getList(MezzeRecord r, String name) {
        int idx = r.getFieldIndex(name);
        if (idx < 0) throw new RuntimeException("Missing field '" + name + "'");
        Object val = r.getField(idx);
        if (val instanceof MezzeList list) return list;
        if (val instanceof MezzeSeq seq) {
            MezzeList result = MezzeList.empty();
            for (int i = seq.size() - 1; i >= 0; i--) {
                result = result.prepend(seq.get(i));
            }
            return result;
        }
        if (val instanceof Object[] arr) return MezzeList.of(arr);
        throw new RuntimeException("Expected list for '" + name + "'");
    }

    private static MezzePromise getPromise(MezzeRecord r, String name) {
        int idx = r.getFieldIndex(name);
        if (idx < 0) throw new RuntimeException("Missing field '" + name + "'");
        Object val = r.getField(idx);
        // Unwrap Promise newtype
        if (val instanceof MezzeVariant v) {
            val = v.getPayloadValue();
        }
        if (val instanceof MezzePromise p) return p;
        throw new RuntimeException("Expected Promise for '" + name + "'");
    }

    private static CoopPromise getCoopPromise(MezzeRecord r, String name) {
        int idx = r.getFieldIndex(name);
        if (idx < 0) throw new RuntimeException("Missing field '" + name + "'");
        Object val = r.getField(idx);
        if (val instanceof MezzeVariant v) {
            val = v.getPayloadValue();
        }
        if (val instanceof CoopPromise p) return p;
        throw new RuntimeException("Expected CoopPromise for '" + name + "'");
    }

    private static MezzeScope getScope(MezzeRecord r, String name) {
        int idx = r.getFieldIndex(name);
        if (idx < 0) throw new RuntimeException("Missing field '" + name + "'");
        Object val = r.getField(idx);
        if (val instanceof MezzeVariant v) {
            val = v.getPayloadValue();
        }
        if (val instanceof MezzeScope s) return s;
        throw new RuntimeException("Expected Scope for '" + name + "'");
    }
}
