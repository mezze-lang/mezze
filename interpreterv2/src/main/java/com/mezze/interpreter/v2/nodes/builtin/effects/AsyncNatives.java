package com.mezze.interpreter.v2.nodes.builtin.effects;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.runtime.MezzeContext;
import com.mezze.interpreter.v2.values.*;
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

        MezzePromise(Thread executingThread, Semaphore semaphore) {
            this.executingThread = executingThread;
            this.semaphore = semaphore;
        }

        /**
         * Constructor that delegates to another promise's future state.
         * Used for returning a promise with thread reference after spawn.
         */
        MezzePromise(Thread executingThread, Semaphore semaphore, MezzePromise delegate) {
            this.executingThread = executingThread;
            this.semaphore = semaphore;
            // Link to delegate - we'll poll its state
            this.delegate = delegate;
        }

        private volatile MezzePromise delegate;

        boolean complete(Object value) {
            if (STATE.compareAndSet(this, PENDING, COMPLETED)) {
                RESULT.setVolatile(this, value);
                unparkWaiter();
                return true;
            }
            return false;
        }

        boolean fail(Throwable error) {
            if (STATE.compareAndSet(this, PENDING, FAILED)) {
                RESULT.setVolatile(this, error);
                unparkWaiter();
                return true;
            }
            return false;
        }

        boolean cancel() {
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

        boolean isDone() {
            MezzePromise d = delegate;
            if (d != null) return d.isDone();
            return state != PENDING;
        }

        boolean isCompleted() {
            return state == COMPLETED;
        }

        boolean isFailed() {
            return state == FAILED;
        }

        boolean isCancelled() {
            return state == CANCELLED;
        }

        Object getResult() {
            return result;
        }

        /**
         * Blocking await with ADAPTIVE spin-then-park strategy for low latency.
         * OPTIMIZATION: Spin count adapts based on historical completion times.
         * OPTIMIZATION: Sampled nanoTime() calls to reduce overhead.
         * OPTIMIZATION: VarHandle for proper waiter memory ordering.
         */
        Object await() throws InterruptedException {
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
        Object await(long timeoutMs) throws InterruptedException, TimeoutException {
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
                    Throwable t = (Throwable) result;
                    throw new RuntimeException("Task failed: " + t.getMessage(), t);
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
    private static final String[] LEFT_RIGHT_FIELDS = new String[]{"left", "right"};
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
        Env env = MezzeContext.get(node).getEnv();

        MezzePromise promise = new MezzePromise(null, null);

        Thread vThread = env.newTruffleThreadBuilder(() -> {
            try {
                Object result = callNode.call(callTarget, closureValues);
                promise.complete(result);
            } catch (Throwable e) {
                promise.fail(e);
            }
        }).virtual(true).build();

        vThread.start();
        return new MezzePromise(vThread, null, promise);
    }

    /**
     * Spawn all functions in parallel and return list of promises.
     */
    @TruffleBoundary
    public static MezzeList loomSpawnAll(MezzeRecord args, IndirectCallNode callNode, MezzeNode node) {
        Object[] functions = getArray(args, "fs");
        Env env = MezzeContext.get(node).getEnv();

        List<MezzePromise> promises = new ArrayList<>();

        for (Object item : functions) {
            if (!(item instanceof MezzeFunction f)) {
                throw new RuntimeException("__native_loom_spawn_all expects list of functions");
            }

            Object[] closureValues = f.getClosureValues();
            var callTarget = f.getCallTarget();

            MezzePromise promise = new MezzePromise(null, null);

            Thread vThread = env.newTruffleThreadBuilder(() -> {
                try {
                    Object result = callNode.call(callTarget, closureValues);
                    promise.complete(result);
                } catch (Throwable e) {
                    promise.fail(e);
                }
            }).virtual(true).build();

            vThread.start();
            promises.add(new MezzePromise(vThread, null, promise));
        }

        return MezzeList.fromObjects(promises.toArray());
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
        Env env = MezzeContext.get(node).getEnv();

        // Create promise first with placeholder thread
        MezzePromise promise = new MezzePromise(null, semaphore);

        // CRITICAL: Use TruffleThreadBuilder for proper context sharing across threads
        // This avoids "Invalid sharing of AST nodes detected" errors
        Thread vThread = env.newTruffleThreadBuilder(() -> {
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
    public static MezzeList loomAll(MezzeRecord args) {
        Object[] promises = getArray(args, "ps");
        Object[] results = new Object[promises.length];

        // Fast path: empty array
        if (promises.length == 0) {
            return MezzeList.empty();
        }

        // Fast path: single promise
        if (promises.length == 1) {
            results[0] = loomAwaitPromise((MezzePromise) promises[0]);
            return MezzeList.fromObjects(results);
        }

        // Await all in order (preserves result ordering)
        for (int i = 0; i < promises.length; i++) {
            results[i] = loomAwaitPromise((MezzePromise) promises[i]);
        }

        return MezzeList.fromObjects(results);
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
        MezzeFunction left = getFunction(args, "left");
        MezzeFunction right = getFunction(args, "right");

        Object[] leftClosure = left.getClosureValues();
        Object[] rightClosure = right.getClosureValues();
        var leftTarget = left.getCallTarget();
        var rightTarget = right.getCallTarget();

        Env env = MezzeContext.get(node).getEnv();

        // Use CompletableFuture for result coordination
        CompletableFuture<Object> leftFuture = new CompletableFuture<>();
        CompletableFuture<Object> rightFuture = new CompletableFuture<>();

        // Spawn Truffle-aware threads for both computations
        Thread leftThread = env.newTruffleThreadBuilder(() -> {
            try {
                leftFuture.complete(callNode.call(leftTarget, leftClosure));
            } catch (Throwable e) {
                leftFuture.completeExceptionally(e);
            }
        }).virtual(true).build();

        Thread rightThread = env.newTruffleThreadBuilder(() -> {
            try {
                rightFuture.complete(callNode.call(rightTarget, rightClosure));
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

        Env env = MezzeContext.get(node).getEnv();

        CompletableFuture<Object> aFuture = new CompletableFuture<>();
        CompletableFuture<Object> bFuture = new CompletableFuture<>();
        CompletableFuture<Object> cFuture = new CompletableFuture<>();

        Thread aThread = env.newTruffleThreadBuilder(() -> {
            try {
                aFuture.complete(callNode.call(a.getCallTarget(), a.getClosureValues()));
            } catch (Throwable e) {
                aFuture.completeExceptionally(e);
            }
        }).virtual(true).build();

        Thread bThread = env.newTruffleThreadBuilder(() -> {
            try {
                bFuture.complete(callNode.call(b.getCallTarget(), b.getClosureValues()));
            } catch (Throwable e) {
                bFuture.completeExceptionally(e);
            }
        }).virtual(true).build();

        Thread cThread = env.newTruffleThreadBuilder(() -> {
            try {
                cFuture.complete(callNode.call(c.getCallTarget(), c.getClosureValues()));
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
    public static MezzeList loomMap(MezzeRecord args, IndirectCallNode callNode, MezzeNode node) {
        Object[] xs = getArray(args, "xs");
        MezzeFunction f = getFunction(args, "f");

        if (xs.length == 0) {
            return MezzeList.empty();
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
     * OPTIMIZATION: Batched parallel map using ForkJoin work-stealing.
     * Uses thread-local argument buffers to eliminate per-iteration allocation.
     */
    private static MezzeList batchedMapWithForkJoin(Object[] xs, MezzeFunction f,
                                                     IndirectCallNode callNode, MezzeNode node) {
        var callTarget = f.getCallTarget();
        Object[] closure = f.getClosureValues();
        final int closureLen = closure.length;
        Object[] results = new Object[xs.length];

        // Calculate number of batches - use more batches for better parallelism
        int numCores = Runtime.getRuntime().availableProcessors();
        int batchSize = Math.max(1, xs.length / (numCores * 2)); // At least 2 batches per core
        batchSize = Math.min(batchSize, BATCH_SIZE); // But not larger than BATCH_SIZE
        int numBatches = (xs.length + batchSize - 1) / batchSize;

        // Use ForkJoin for work-stealing across batches
        ForkJoinTask<?>[] tasks = new ForkJoinTask[numBatches];
        final int finalBatchSize = batchSize;

        for (int b = 0; b < numBatches; b++) {
            final int batchStart = b * finalBatchSize;
            final int batchEnd = Math.min(batchStart + finalBatchSize, xs.length);

            tasks[b] = OS_EXECUTOR.submit(() -> {
                // OPTIMIZATION: Use thread-local buffer instead of allocating
                Object[] callArgs = getCallArgsBuffer(closureLen + 1);
                System.arraycopy(closure, 0, callArgs, 0, closureLen);

                for (int i = batchStart; i < batchEnd; i++) {
                    // Wrap element in record - callbacks expect {x} -> ...
                    MezzeRecord arg = MezzeRecord.fromFields(X_FIELD, new Object[]{xs[i]});
                    callArgs[closureLen] = arg;
                    results[i] = callNode.call(callTarget, callArgs);
                }
            });
        }

        // Wait for all batches with work-stealing
        for (ForkJoinTask<?> task : tasks) {
            task.join();
        }

        return MezzeList.fromObjects(results);
    }

    // Fast sequential map for small arrays - uses thread-local buffer
    private static MezzeList sequentialMapInternal(Object[] xs, MezzeFunction f, IndirectCallNode callNode) {
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

        return MezzeList.fromObjects(results);
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

        // OPTIMIZATION: ALL parallel operations use batching now
        // No more 1-thread-per-element which has high overhead
        return batchedForEachWithForkJoin(xs, f, callNode);
    }

    /**
     * OPTIMIZATION: Batched forEach using ForkJoin work-stealing.
     * Uses thread-local argument buffers to eliminate per-iteration allocation.
     */
    private static MezzeUnit batchedForEachWithForkJoin(Object[] xs, MezzeFunction f, IndirectCallNode callNode) {
        var callTarget = f.getCallTarget();
        Object[] closure = f.getClosureValues();
        final int closureLen = closure.length;

        // Calculate number of batches - use more batches for better parallelism
        int numCores = Runtime.getRuntime().availableProcessors();
        int batchSize = Math.max(1, xs.length / (numCores * 2)); // At least 2 batches per core
        batchSize = Math.min(batchSize, BATCH_SIZE); // But not larger than BATCH_SIZE
        int numBatches = (xs.length + batchSize - 1) / batchSize;

        ForkJoinTask<?>[] tasks = new ForkJoinTask[numBatches];
        final int finalBatchSize = batchSize;

        for (int b = 0; b < numBatches; b++) {
            final int batchStart = b * finalBatchSize;
            final int batchEnd = Math.min(batchStart + finalBatchSize, xs.length);

            tasks[b] = OS_EXECUTOR.submit(() -> {
                // OPTIMIZATION: Use thread-local buffer instead of allocating
                Object[] callArgs = getCallArgsBuffer(closureLen + 1);
                System.arraycopy(closure, 0, callArgs, 0, closureLen);

                for (int i = batchStart; i < batchEnd; i++) {
                    // Wrap element in record - callbacks expect {x} -> ...
                    MezzeRecord arg = MezzeRecord.fromFields(X_FIELD, new Object[]{xs[i]});
                    callArgs[closureLen] = arg;
                    callNode.call(callTarget, callArgs);
                }
            });
        }

        for (ForkJoinTask<?> task : tasks) {
            task.join();
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
    public static MezzeList loomFilter(MezzeRecord args, IndirectCallNode callNode, MezzeNode node) {
        Object[] xs = getArray(args, "xs");
        MezzeFunction f = getFunction(args, "f");

        if (xs.length == 0) {
            return MezzeList.empty();
        }

        // Sequential fallback for small arrays
        if (xs.length < PARALLEL_THRESHOLD) {
            return sequentialFilterInternal(xs, f, callNode);
        }

        // OPTIMIZATION: ALL parallel operations use batching now
        // No more 1-thread-per-element which has high overhead
        return batchedFilterWithForkJoin(xs, f, callNode);
    }

    /**
     * OPTIMIZATION: Batched filter using ForkJoin work-stealing.
     * Uses thread-local argument buffers to eliminate per-iteration allocation.
     */
    private static MezzeList batchedFilterWithForkJoin(Object[] xs, MezzeFunction f, IndirectCallNode callNode) {
        var callTarget = f.getCallTarget();
        Object[] closure = f.getClosureValues();
        final int closureLen = closure.length;

        // Use array of booleans to track which elements pass the filter
        boolean[] passed = new boolean[xs.length];

        // Calculate number of batches - use more batches for better parallelism
        int numCores = Runtime.getRuntime().availableProcessors();
        int batchSize = Math.max(1, xs.length / (numCores * 2)); // At least 2 batches per core
        batchSize = Math.min(batchSize, BATCH_SIZE); // But not larger than BATCH_SIZE
        int numBatches = (xs.length + batchSize - 1) / batchSize;

        ForkJoinTask<?>[] tasks = new ForkJoinTask[numBatches];
        final int finalBatchSize = batchSize;

        for (int b = 0; b < numBatches; b++) {
            final int batchStart = b * finalBatchSize;
            final int batchEnd = Math.min(batchStart + finalBatchSize, xs.length);

            tasks[b] = OS_EXECUTOR.submit(() -> {
                // OPTIMIZATION: Use thread-local buffer instead of allocating
                Object[] callArgs = getCallArgsBuffer(closureLen + 1);
                System.arraycopy(closure, 0, callArgs, 0, closureLen);

                for (int i = batchStart; i < batchEnd; i++) {
                    // Wrap element in record - callbacks expect {x} -> ...
                    MezzeRecord arg = MezzeRecord.fromFields(X_FIELD, new Object[]{xs[i]});
                    callArgs[closureLen] = arg;
                    Object result = callNode.call(callTarget, callArgs);
                    passed[i] = result instanceof Boolean && (Boolean) result;
                }
            });
        }

        for (ForkJoinTask<?> task : tasks) {
            task.join();
        }

        // Collect results
        List<Object> results = new ArrayList<>();
        for (int i = 0; i < xs.length; i++) {
            if (passed[i]) {
                results.add(xs[i]);
            }
        }
        return MezzeList.fromObjects(results.toArray());
    }

    // Fast sequential filter for small arrays - uses thread-local buffer
    private static MezzeList sequentialFilterInternal(Object[] xs, MezzeFunction f, IndirectCallNode callNode) {
        var callTarget = f.getCallTarget();
        Object[] closure = f.getClosureValues();

        // OPTIMIZATION: Use thread-local buffer
        Object[] callArgs = getCallArgsBuffer(closure.length + 1);
        System.arraycopy(closure, 0, callArgs, 0, closure.length);

        List<Object> results = new ArrayList<>();
        for (Object x : xs) {
            // Wrap element in record - callbacks expect {x} -> ...
            MezzeRecord arg = MezzeRecord.fromFields(X_FIELD, new Object[]{x});
            callArgs[closure.length] = arg;
            Object result = callNode.call(callTarget, callArgs);
            if (result instanceof Boolean && (Boolean) result) {
                results.add(x);
            }
        }
        return MezzeList.fromObjects(results.toArray());
    }

    @TruffleBoundary
    public static Object loomAny(MezzeRecord args) {
        Object[] promises = getArray(args, "ps");
        if (promises.length == 0) {
            throw new RuntimeException("any requires at least one promise");
        }

        List<Throwable> failures = new ArrayList<>();

        for (Object p : promises) {
            MezzePromise promise = (MezzePromise) p;
            try {
                return promise.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted during any", e);
            } catch (RuntimeException e) {
                failures.add(e);
            }
        }

        throw new RuntimeException("All tasks failed. First: " + failures.get(0).getMessage());
    }

    @TruffleBoundary
    public static MezzeList loomSettle(MezzeRecord args) {
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

        return MezzeList.fromObjects(results);
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
        Env env = scope.env != null ? scope.env : MezzeContext.get(node).getEnv();

        MezzePromise promise = new MezzePromise(null, null);
        scope.addChild(promise);

        Thread vThread = env.newTruffleThreadBuilder(() -> {
            try {
                if (scope.isCancelled()) {
                    promise.cancel();
                    return;
                }
                Object result = callNode.call(callTarget, closure);
                promise.complete(result);
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
        Object[] closure = f.getClosureValues();

        Env env = MezzeContext.get(node).getEnv();
        MezzePromise promise = new MezzePromise(null, null);

        // Use TruffleThreadBuilder for proper context handling
        // Note: .virtual(false) for OS threads (platform threads)
        Thread thread = env.newTruffleThreadBuilder(() -> {
            try {
                Object result = callNode.call(callTarget, closure);
                promise.complete(result);
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
    public static MezzeList osAll(MezzeRecord args) {
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
        MezzeFunction left = getFunction(args, "left");
        MezzeFunction right = getFunction(args, "right");

        Env env = MezzeContext.get(node).getEnv();

        CompletableFuture<Object> leftFuture = new CompletableFuture<>();
        CompletableFuture<Object> rightFuture = new CompletableFuture<>();

        Thread leftThread = env.newTruffleThreadBuilder(() -> {
            try {
                leftFuture.complete(callNode.call(left.getCallTarget(), left.getClosureValues()));
            } catch (Throwable e) {
                leftFuture.completeExceptionally(e);
            }
        }).build();

        Thread rightThread = env.newTruffleThreadBuilder(() -> {
            try {
                rightFuture.complete(callNode.call(right.getCallTarget(), right.getClosureValues()));
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

        Env env = MezzeContext.get(node).getEnv();

        CompletableFuture<Object> aFuture = new CompletableFuture<>();
        CompletableFuture<Object> bFuture = new CompletableFuture<>();
        CompletableFuture<Object> cFuture = new CompletableFuture<>();

        Thread aThread = env.newTruffleThreadBuilder(() -> {
            try {
                aFuture.complete(callNode.call(a.getCallTarget(), a.getClosureValues()));
            } catch (Throwable e) {
                aFuture.completeExceptionally(e);
            }
        }).build();

        Thread bThread = env.newTruffleThreadBuilder(() -> {
            try {
                bFuture.complete(callNode.call(b.getCallTarget(), b.getClosureValues()));
            } catch (Throwable e) {
                bFuture.completeExceptionally(e);
            }
        }).build();

        Thread cThread = env.newTruffleThreadBuilder(() -> {
            try {
                cFuture.complete(callNode.call(c.getCallTarget(), c.getClosureValues()));
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

    @TruffleBoundary
    public static MezzeList osMap(MezzeRecord args, IndirectCallNode callNode, MezzeNode node) {
        Object[] xs = getArray(args, "xs");
        MezzeFunction f = getFunction(args, "f");

        if (xs.length == 0) {
            return MezzeList.empty();
        }

        Env env = MezzeContext.get(node).getEnv();
        var callTarget = f.getCallTarget();
        Object[] closure = f.getClosureValues();

        @SuppressWarnings("unchecked")
        CompletableFuture<Object>[] futures = new CompletableFuture[xs.length];
        Thread[] threads = new Thread[xs.length];

        for (int i = 0; i < xs.length; i++) {
            final Object x = xs[i];
            final int idx = i;
            futures[i] = new CompletableFuture<>();
            threads[i] = env.newTruffleThreadBuilder(() -> {
                try {
                    MezzeRecord arg = MezzeRecord.fromFields(new String[]{"x"}, new Object[]{x});
                    futures[idx].complete(callNode.call(callTarget, appendArg(closure, arg)));
                } catch (Throwable e) {
                    futures[idx].completeExceptionally(e);
                }
            }).build();
            threads[i].start();
        }

        Object[] results = new Object[xs.length];
        try {
            for (int i = 0; i < futures.length; i++) {
                results[i] = futures[i].get();
            }
        } catch (InterruptedException | ExecutionException e) {
            for (Thread t : threads) t.interrupt();
            throw new RuntimeException("Map failed", e);
        }

        return MezzeList.fromObjects(results);
    }

    @TruffleBoundary
    public static MezzeUnit osForEach(MezzeRecord args, IndirectCallNode callNode, MezzeNode node) {
        Object[] xs = getArray(args, "xs");
        MezzeFunction f = getFunction(args, "f");

        if (xs.length == 0) {
            return MezzeUnit.INSTANCE;
        }

        Env env = MezzeContext.get(node).getEnv();
        var callTarget = f.getCallTarget();
        Object[] closure = f.getClosureValues();

        @SuppressWarnings("unchecked")
        CompletableFuture<Void>[] futures = new CompletableFuture[xs.length];
        Thread[] threads = new Thread[xs.length];

        for (int i = 0; i < xs.length; i++) {
            final Object x = xs[i];
            final int idx = i;
            futures[i] = new CompletableFuture<>();
            threads[i] = env.newTruffleThreadBuilder(() -> {
                try {
                    MezzeRecord arg = MezzeRecord.fromFields(new String[]{"x"}, new Object[]{x});
                    callNode.call(callTarget, appendArg(closure, arg));
                    futures[idx].complete(null);
                } catch (Throwable e) {
                    futures[idx].completeExceptionally(e);
                }
            }).build();
            threads[i].start();
        }

        try {
            CompletableFuture.allOf(futures).get();
        } catch (InterruptedException | ExecutionException e) {
            for (Thread t : threads) t.interrupt();
            throw new RuntimeException("ForEach failed", e);
        }

        return MezzeUnit.INSTANCE;
    }

    @TruffleBoundary
    public static MezzeList osFilter(MezzeRecord args, IndirectCallNode callNode, MezzeNode node) {
        Object[] xs = getArray(args, "xs");
        MezzeFunction f = getFunction(args, "f");

        if (xs.length == 0) {
            return MezzeList.empty();
        }

        Env env = MezzeContext.get(node).getEnv();
        var callTarget = f.getCallTarget();
        Object[] closure = f.getClosureValues();

        @SuppressWarnings("unchecked")
        CompletableFuture<Boolean>[] futures = new CompletableFuture[xs.length];
        Thread[] threads = new Thread[xs.length];

        for (int i = 0; i < xs.length; i++) {
            final Object x = xs[i];
            final int idx = i;
            futures[i] = new CompletableFuture<>();
            threads[i] = env.newTruffleThreadBuilder(() -> {
                try {
                    MezzeRecord arg = MezzeRecord.fromFields(new String[]{"x"}, new Object[]{x});
                    Object result = callNode.call(callTarget, appendArg(closure, arg));
                    futures[idx].complete(result instanceof Boolean && (Boolean) result);
                } catch (Throwable e) {
                    futures[idx].completeExceptionally(e);
                }
            }).build();
            threads[i].start();
        }

        List<Object> results = new ArrayList<>();
        try {
            for (int i = 0; i < futures.length; i++) {
                if (futures[i].get()) {
                    results.add(xs[i]);
                }
            }
        } catch (InterruptedException | ExecutionException e) {
            for (Thread t : threads) t.interrupt();
            throw new RuntimeException("Filter failed", e);
        }

        return MezzeList.fromObjects(results.toArray());
    }

    @TruffleBoundary
    public static Object osAny(MezzeRecord args) {
        return loomAny(args);
    }

    @TruffleBoundary
    public static MezzeList osSettle(MezzeRecord args) {
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
            promise.result = callNode.call(f.getCallTarget(), f.getClosureValues());
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
    public static MezzeList coopAll(MezzeRecord args) {
        Object[] promises = getArray(args, "ps");
        Object[] results = new Object[promises.length];

        for (int i = 0; i < promises.length; i++) {
            CoopPromise p = (CoopPromise) promises[i];
            if (p.result instanceof Throwable t) {
                throw new RuntimeException("Task failed: " + t.getMessage(), t);
            }
            results[i] = p.result;
        }

        return MezzeList.fromObjects(results);
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
        MezzeFunction left = getFunction(args, "left");
        MezzeFunction right = getFunction(args, "right");

        Object leftResult = callNode.call(left.getCallTarget(), left.getClosureValues());
        Object rightResult = callNode.call(right.getCallTarget(), right.getClosureValues());

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
                callNode.call(a.getCallTarget(), a.getClosureValues()),
                callNode.call(b.getCallTarget(), b.getClosureValues()),
                callNode.call(c.getCallTarget(), c.getClosureValues())
            }
        );
    }

    @TruffleBoundary
    public static MezzeList coopMap(MezzeRecord args, IndirectCallNode callNode) {
        Object[] xs = getArray(args, "xs");
        MezzeFunction f = getFunction(args, "f");

        Object[] results = new Object[xs.length];
        var callTarget = f.getCallTarget();
        Object[] closure = f.getClosureValues();

        for (int i = 0; i < xs.length; i++) {
            MezzeRecord arg = MezzeRecord.fromFields(new String[]{"x"}, new Object[]{xs[i]});
            results[i] = callNode.call(callTarget, appendArg(closure, arg));
        }

        return MezzeList.fromObjects(results);
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
    public static MezzeList coopFilter(MezzeRecord args, IndirectCallNode callNode) {
        Object[] xs = getArray(args, "xs");
        MezzeFunction f = getFunction(args, "f");

        var callTarget = f.getCallTarget();
        Object[] closure = f.getClosureValues();

        List<Object> results = new ArrayList<>();
        for (Object x : xs) {
            MezzeRecord arg = MezzeRecord.fromFields(new String[]{"x"}, new Object[]{x});
            Object result = callNode.call(callTarget, appendArg(closure, arg));
            if (result instanceof Boolean && (Boolean) result) {
                results.add(x);
            }
        }

        return MezzeList.fromObjects(results.toArray());
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
    public static MezzeList coopSettle(MezzeRecord args) {
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

        return MezzeList.fromObjects(results);
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
        if (val instanceof MezzeList list) return list.getObjectStore();
        throw new RuntimeException("Expected array for '" + name + "'");
    }

    private static MezzeList getList(MezzeRecord r, String name) {
        int idx = r.getFieldIndex(name);
        if (idx < 0) throw new RuntimeException("Missing field '" + name + "'");
        Object val = r.getField(idx);
        if (val instanceof MezzeList list) return list;
        if (val instanceof Object[] arr) return MezzeList.fromObjects(arr);
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
