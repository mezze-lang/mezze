package com.mezze.interpreter.nodes.builtin.effects;

import com.mezze.datastructures.list.MezzeList;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.nodes.builtin.BuiltinFieldNames;
import com.mezze.interpreter.nodes.builtin.EffectHandlerDriver;
import com.mezze.interpreter.nodes.builtin.EffectHandlerDriver.TaskUnhandledOpException;
import com.mezze.interpreter.nodes.continuation.EffectFiber;
import com.mezze.interpreter.nodes.continuation.OpCall;
import com.mezze.interpreter.nodes.continuation.SpawnSupport;
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
import java.util.function.Supplier;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * High-performance native async operations.
 *
 * Two implementations:
 * 1. LoomAsync - Virtual threads (Project Loom) - millions of concurrent tasks
 * 2. OSThreadAsync - Platform threads - true CPU parallelism
 *
 * Cooperative scheduling lives in CoopScheduler / SchedulerNatives and is
 * wired into stdlib via the __native_scheduler_* family.
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

        // Set in the constructor or via setExecutingThread() once the
        // spawned virtual thread has been created. Single writer (the
        // spawn-site code), single reader (cancel()) which only fires
        // after the spawn site has returned and the body has had a chance
        // to run, so plain field access is safe.
        Thread executingThread; // For cancellation
        final Semaphore semaphore; // For concurrency limiting (may be null)

        /** Set once, after the Thread is created (spawn sites first build
         *  the promise, then the thread, then store it here). */
        public void setExecutingThread(Thread t) {
            this.executingThread = t;
        }

        // Set by Async.spawn when the spawned task body terminated because
        // an op-call propagated past all inherited handlers. Read by
        // Async.await, which re-raises the op-call on the parent fiber so
        // the parent's handler chain can dispatch it.
        private volatile OpCall unhandledOpCall;

        public MezzePromise(Thread executingThread, Semaphore semaphore) {
            this.executingThread = executingThread;
            this.semaphore = semaphore;
        }

        public void setUnhandledOpCall(OpCall op) {
            this.unhandledOpCall = op;
        }

        public OpCall getUnhandledOpCall() {
            return unhandledOpCall;
        }

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

        /** Mark Promise as Failed. `error` can be:
         *    - a Throwable (regular task-body-crashed path); receivers
         *      convert it to AsyncFailed { msg = e.getMessage() } when
         *      they need an AsyncError variant for the user.
         *    - a Mezze AsyncError variant (constructed via promiseFail);
         *      receivers pass it through unchanged.
         *  The Promise's result field is Object, so either flavor fits
         *  natively. */
        public boolean fail(Object error) {
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

        // Outcome codes for cancelOutcome() — mapped 1:1 to the Mezze
        // CancelOutcome ADT constructors. See AsyncNatives.promiseCancel.
        public static final int CANCEL_DID_CANCEL        = 0;
        public static final int CANCEL_ALREADY_COMPLETED = 1;
        public static final int CANCEL_ALREADY_FAILED    = 2;
        public static final int CANCEL_NOT_CANCELLABLE   = 3;

        /** Like cancel() but reports what actually happened. Returns one
         *  of the CANCEL_* codes. */
        public int cancelOutcome() {
            if (STATE.compareAndSet(this, PENDING, CANCELLED)) {
                if (executingThread != null) {
                    executingThread.interrupt();
                }
                unparkWaiter();
                return CANCEL_DID_CANCEL;
            }
            return switch (state) {
                case COMPLETED -> CANCEL_ALREADY_COMPLETED;
                case FAILED    -> CANCEL_ALREADY_FAILED;
                case CANCELLED -> CANCEL_DID_CANCEL;  // racing cancel saw us
                default        -> CANCEL_NOT_CANCELLABLE;
            };
        }

        /** Snapshot the state for inspection. Returns one of the
         *  state constants. Result field is read separately by caller
         *  AFTER seeing a non-PENDING state (volatile state acts as
         *  release fence). */
        public int snapshotState() { return state; }
        public Object snapshotResult() { return result; }

        // OPTIMIZATION: Use VarHandle for proper memory ordering on waiter access
        private void unparkWaiter() {
            // Use acquire semantics to ensure we see the waiter if it was set
            Thread w = (Thread) WAITER.getAcquire(this);
            if (w != null) {
                LockSupport.unpark(w);
            }
        }

        public boolean isDone() {
            return state != PENDING;
        }

        public boolean isCompleted() {
            return state == COMPLETED;
        }

        public boolean isFailed() {
            return state == FAILED;
        }

        public boolean isCancelled() {
            return state == CANCELLED;
        }

        public Object getResult() {
            return result;
        }

        /**
         * Blocking await with ADAPTIVE spin-then-park strategy for low latency.
         * OPTIMIZATION: Spin count adapts based on historical completion times.
         * OPTIMIZATION: Sampled nanoTime() calls to reduce overhead.
         * OPTIMIZATION: VarHandle for proper waiter memory ordering.
         */
        public Object await() throws InterruptedException {
            // OPTIMIZATION: Only measure time on sampled calls. Sampling
            // uses a per-thread counter (no atomic) — single read +
            // increment, no coherence traffic across awaiting threads.
            long startNanos = shouldSampleThisCall() ? System.nanoTime() : 0;

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
            if (state != PENDING) {
                return getResultOrThrow();
            }

            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            // Match await(): VarHandle setRelease so completion-side
            // getAcquire on the waiter sees a consistent thread reference.
            WAITER.setRelease(this, Thread.currentThread());
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
                WAITER.setRelease(this, null);
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

        // Capture the parent's effect-handler chain at spawn time so the
        // spawned task's OpCallNode (fiber-less dispatch path) can find
        // the same handlers that were in scope at the spawn site.
        MezzeRecord[] handlerChain = EffectFiber.snapshotHandlerChain();

        // Single promise: body completes it, parent awaits it. Thread
        // reference is patched in after construction (single writer:
        // the spawn site; reader: cancel() which only fires post-spawn).
        // Saves one MezzePromise allocation per spawn vs the old
        // inner+delegating-outer pattern.
        MezzePromise promise = new MezzePromise(null, semaphore);

        Thread vThread = env.newTruffleThreadBuilder(() -> runPromiseTask(handlerChain, promise, () -> {
            if (semaphore != null) {
                semaphore.acquireUninterruptibly();
            }
            try {
                return callNode.call(callTarget, closureValues);
            } finally {
                if (semaphore != null) {
                    semaphore.release();
                }
            }
        })).virtual(true).build();

        promise.setExecutingThread(vThread);
        vThread.start();

        // Track for cleanup
        if (trackerObj instanceof PromiseTracker tracker) {
            tracker.track(promise);
        }

        return promise;
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

        // Capture the parent's effect-handler chain at spawn time so the
        // spawned task's OpCallNode (fiber-less dispatch path) can find
        // the same handlers that were in scope at the spawn site.
        MezzeRecord[] handlerChain = EffectFiber.snapshotHandlerChain();

        MezzePromise promise = new MezzePromise(null, null);

        // Platform thread (not virtual)
        Thread thread = env.newTruffleThreadBuilder(
            () -> runPromiseTask(handlerChain, promise,
                () -> callNode.call(callTarget, fullArgs))
        ).build();

        promise.setExecutingThread(thread);
        thread.start();

        if (trackerObj instanceof PromiseTracker tracker) {
            tracker.track(promise);
        }

        return promise;
    }

    // Semaphore cache for concurrency limiting
    private static final ConcurrentHashMap<Integer, Semaphore> SEMAPHORE_CACHE = new ConcurrentHashMap<>();

    // ==================== Performance Constants ====================

    // Threshold below which we use sequential execution (thread overhead > benefit)
    private static final int PARALLEL_THRESHOLD = 4;

    // Batch size for chunked parallel operations - ALL parallel ops use batching now
    private static final int BATCH_SIZE = 64;

    // Pre-allocated field-name arrays live in BuiltinFieldNames so they
    // can be shared across natives without each file defining its own
    // (sometimes with a different name — ABC_FIELDS vs A_B_C_FIELDS).
    private static final String[] X_FIELD = BuiltinFieldNames.X;
    private static final String[] LEFT_RIGHT_FIELDS = BuiltinFieldNames.LEFT_RIGHT;
    private static final String[] ABC_FIELDS = BuiltinFieldNames.ABC;

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
    //
    // Adaptive spin tracks avg completion time so getAdaptiveSpinCount can
    // pick a sensible spin budget. Sampling is per-thread (no contention
    // on the hot path); the EMA itself is a plain volatile-via-VarHandle
    // (lossy under contention, fine for a perf heuristic).

    private static final int SAMPLE_RATE = 64;
    // Per-thread monotonic counter. Read on every await() to decide
    // whether to nanoTime() — replaces the previous AtomicLong.get()
    // which was a coherence-traffic hotspot under 1M+ parallel awaits.
    private static final ThreadLocal<long[]> SAMPLE_COUNTER =
        ThreadLocal.withInitial(() -> new long[1]);

    /** Returns true iff this thread should sample timing on this await. */
    private static boolean shouldSampleThisCall() {
        long[] counter = SAMPLE_COUNTER.get();
        long next = counter[0]++;
        return (next & (SAMPLE_RATE - 1)) == 0;
    }

    // Average completion time in nanoseconds. Plain long with relaxed
    // semantics — perf heuristic, lossy updates are acceptable.
    private static volatile long avgCompletionNanos = 1000L;
    // Count of total sampled completions across all threads — only used
    // to switch from initialization-EMA to long-run-EMA. Atomic so the
    // count progresses sanely; not on the hot path.
    private static final AtomicLong sampledCompletions = new AtomicLong(0);

    // Update completion time tracking — called only when shouldSampleThisCall
    // returned true (so already sampled, no need to gate again).
    private static void recordCompletionTime(long nanos) {
        long count = sampledCompletions.incrementAndGet();
        long oldAvg = avgCompletionNanos;
        long newAvg;
        if (count < 100) {
            newAvg = (oldAvg + nanos) / 2;
        } else {
            // Exponential moving average with alpha = 0.1.
            newAvg = (long)(oldAvg * 0.9 + nanos * 0.1);
        }
        avgCompletionNanos = newAvg;
    }

    // Calculate adaptive spin count based on historical completion times.
    // Bounded between 16 and 1024 iterations regardless of avgNanos.
    private static int getAdaptiveSpinCount() {
        long avgNanos = avgCompletionNanos;
        return (int) Math.min(1024L, Math.max(16L, avgNanos / 100));
    }

    // ==================== Loom Async Operations ====================

    /**
     * Simple spawn without concurrency limit - for backward compatibility.
     */
    @TruffleBoundary
    public static MezzePromise loomSpawn(MezzeRecord args, IndirectCallNode callNode, MezzeNode node) {
        MezzeFunction f = getFunction(args, "f");

        Object[] closureValues = f.getClosureValues();

        // Build argument array: closure values + empty record argument.
        Object[] fullArgs = new Object[closureValues.length + 1];
        System.arraycopy(closureValues, 0, fullArgs, 0, closureValues.length);
        fullArgs[closureValues.length] = MezzeRecord.empty();

        MezzeContext context = MezzeContext.get(node);
        Env env = context.getEnv();

        // Capture the parent's effect-handler chain at spawn time so the
        // spawned task dispatches op-calls through the same handlers that
        // were in scope here. Snapshot copy is safe to ship across threads.
        MezzeRecord[] handlerChain = EffectFiber.snapshotHandlerChain();

        // Single promise: body completes it, parent awaits it. Thread
        // reference is patched in after construction (single writer:
        // the spawn site; reader: cancel() which only fires post-spawn).
        MezzePromise promise = new MezzePromise(null, null);

        Thread vThread = env.newTruffleThreadBuilder(
            () -> runPromiseTask(handlerChain, promise,
                () -> callNode.call(f.getCallTarget(), fullArgs))
        ).virtual(true).build();

        promise.setExecutingThread(vThread);
        vThread.start();
        return promise;
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

        // Capture the parent's effect-handler chain at spawn time so each
        // spawned task's OpCallNode (fiber-less dispatch path) can find
        // the same handlers that were in scope at the spawn site.
        MezzeRecord[] handlerChain = EffectFiber.snapshotHandlerChain();

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

            Thread vThread = env.newTruffleThreadBuilder(
                () -> runPromiseTask(handlerChain, promise,
                    () -> callNode.call(callTarget, fullArgs))
            ).virtual(true).build();

            promise.setExecutingThread(vThread);
            vThread.start();
            promises[idx] = promise;
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

        // Capture the parent's effect-handler chain at spawn time so the
        // spawned task's OpCallNode (fiber-less dispatch path) can find
        // the same handlers that were in scope at the spawn site.
        MezzeRecord[] handlerChain = EffectFiber.snapshotHandlerChain();

        // Create promise first with placeholder thread
        MezzePromise promise = new MezzePromise(null, semaphore);

        // CRITICAL: Use TruffleThreadBuilder for proper context sharing across threads
        // This avoids "Invalid sharing of AST nodes detected" errors
        Thread vThread = env.newTruffleThreadBuilder(
            () -> runPromiseTask(handlerChain, promise, () -> {
                if (semaphore != null) {
                    semaphore.acquireUninterruptibly();
                }
                try {
                    return callNode.call(callTarget, closureValues);
                } finally {
                    if (semaphore != null) {
                        semaphore.release();
                    }
                }
            })
        ).virtual(true).build();

        promise.setExecutingThread(vThread);
        vThread.start();
        return promise;
    }

    @TruffleBoundary
    public static Object loomAwait(MezzeRecord args) {
        MezzePromise promise = getPromise(args, "p");
        return loomAwaitPromise(promise);
    }

    /**
     * Cancel every promise in {@code promises} that isn't the {@code winner}
     * and isn't already done. Used by race to clean up losing siblings
     * (#122) so they don't keep running in the background after a winner
     * commits.
     */
    private static void cancelOtherPromises(Object[] promises, MezzePromise winner) {
        for (Object p : promises) {
            MezzePromise promise = (MezzePromise) p;
            if (promise != winner && !promise.isDone()) {
                promise.cancel();
            }
        }
    }

    /**
     * Race: return the first promise to settle as a {@code Result 'a AsyncError}.
     * Auto-cancels losing siblings (#122) when a winner commits, so they
     * stop running in the background. Cancelled promises are skipped —
     * the race waits for a Completed or Failed promise. If every
     * promise is cancelled (or the race itself is interrupted), surfaces
     * AsyncCancelled.
     *
     * <p>OPTIMIZATION: direct polling with adaptive backoff instead of
     * spawning N CompletableFutures + N virtual threads.
     */
    @TruffleBoundary
    public static Object loomRace(MezzeRecord args) {
        Object[] promises = getArray(args, "ps");
        if (promises.length == 0) {
            return MezzeVariant.err(createAsyncFailed("race requires at least one promise"));
        }

        // Fast path: single promise — defer to the Result-typed await.
        if (promises.length == 1) {
            return promiseAwaitResult(args);
        }

        // Phase 1: Quick spin check (many promises may already be done)
        for (int spin = 0; spin < 64; spin++) {
            Object winner = pollForWinner(promises);
            if (winner != null) return winner;
            Thread.onSpinWait();
        }

        // Phase 2: Park with periodic wake-up to check all promises
        // Uses exponential backoff: 100us -> 200us -> 400us -> ... -> 10ms max
        long parkNanos = 100_000; // Start at 100 microseconds
        final long maxParkNanos = 10_000_000; // Cap at 10 milliseconds

        while (true) {
            Object winner = pollForWinner(promises);
            if (winner != null) return winner;

            // Interrupted on the race itself — surface as AsyncCancelled.
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                return MezzeVariant.err(createAsyncCancelled());
            }

            // Park with backoff
            LockSupport.parkNanos(parkNanos);
            parkNanos = Math.min(parkNanos * 2, maxParkNanos);
        }
    }

    /**
     * One race-poll pass. Returns a {@code Result 'a AsyncError} MezzeVariant
     * if a winner has settled (Completed → Ok, Failed → Err AsyncFailed) and
     * cancels the losing siblings as a side-effect. Returns null if no
     * non-cancelled promise has settled yet. When every promise is cancelled,
     * surfaces Err AsyncCancelled to the caller.
     */
    private static Object pollForWinner(Object[] promises) {
        boolean allCancelled = true;
        for (Object p : promises) {
            MezzePromise promise = (MezzePromise) p;
            if (!promise.isDone()) {
                allCancelled = false;
                continue;
            }
            if (promise.isCancelled()) {
                continue; // skip cancelled, keep looking
            }
            // Completed or Failed — we have a winner.
            cancelOtherPromises(promises, promise);
            if (promise.isCompleted()) {
                return MezzeVariant.ok(promise.getResult());
            }
            // Failed: pull AsyncError from the stored result.
            return MezzeVariant.err(asyncErrorFromResult(promise.result));
        }
        if (allCancelled) {
            return MezzeVariant.err(createAsyncCancelled());
        }
        return null;
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

    /**
     * Park the current task until {@code predicate} returns True.
     * Spin-park with exponential backoff up to a small cap; LockSupport
     * .parkNanos lets the scheduler hibernate the virtual thread. An
     * explicit waker would be cheaper for predicates that change
     * rarely; can land later if profile demands.
     */
    @TruffleBoundary
    public static MezzeUnit loomParkUntil(MezzeRecord args, IndirectCallNode callNode) {
        MezzeFunction predicate = getFunction(args, "predicate");
        long backoffNanos = 1_000L; // 1µs to start
        final long maxBackoffNanos = 1_000_000L; // 1ms cap
        while (true) {
            Object result = callThunkFunction(predicate, callNode);
            if (result instanceof Boolean b && b) return MezzeUnit.INSTANCE;
            LockSupport.parkNanos(backoffNanos);
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Park interrupted");
            }
            backoffNanos = Math.min(backoffNanos * 2, maxBackoffNanos);
        }
    }

    /**
     * Spawn one side of a join. Wraps the body's outcome in
     * {@code Result 'a AsyncError} and completes {@code resultFuture}.
     *
     * <p>Matches {@code futures::join!} semantics: each side runs
     * independently to completion. No sibling auto-cancel on failure —
     * a Mezze {@code try_join} (future #122f) will handle that.
     *
     * <p>{@code virtual} selects between Loom virtual threads
     * (true, for {@code __native_loom_join*}) and OS platform threads
     * (false, for {@code __native_os_join*}).
     */
    private static Thread spawnJoinSide(
            Env env,
            MezzeRecord[] chain,
            CompletableFuture<MezzeVariant> resultFuture,
            boolean virtual,
            Supplier<Object> body) {
        return env.newTruffleThreadBuilder(() -> {
            MezzeVariant result;
            boolean installed = false;
            try {
                if (chain.length > 0) {
                    EffectFiber.installHandlerChain(chain);
                    installed = true;
                }
                Object value = body.get();
                // If the body returned a MezzeSuspended, an effect op
                // (e.g. Exception.throw) propagated past the chain
                // without a handler — surface as Err AsyncFailed.
                if (value instanceof MezzeSuspended suspended) {
                    result = createAsyncFailed(
                        "join task raised unhandled effect: " + suspended);
                } else {
                    result = MezzeVariant.ok(value);
                }
            } catch (TaskUnhandledOpException tue) {
                result = createAsyncFailed(
                    "join task raised effect operation '" + tue.opCall.opName() + "'");
            } catch (Throwable e) {
                if (Thread.currentThread().isInterrupted()) {
                    result = createAsyncCancelled();
                } else {
                    String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                    result = createAsyncFailed(msg);
                }
            } finally {
                if (installed) EffectFiber.popHandlerChain(chain.length);
            }
            resultFuture.complete(result);
        }).virtual(virtual).build();
    }

    @TruffleBoundary
    public static MezzeRecord loomJoin(MezzeRecord args, IndirectCallNode callNode, MezzeNode node) {
        MezzeFunction left = getFunction(args, "l");
        MezzeFunction right = getFunction(args, "r");

        Object[] leftClosure = left.getClosureValues();
        Object[] rightClosure = right.getClosureValues();
        var leftTarget = left.getCallTarget();
        var rightTarget = right.getCallTarget();

        Object[] leftArgs = new Object[leftClosure.length + 1];
        System.arraycopy(leftClosure, 0, leftArgs, 0, leftClosure.length);
        leftArgs[leftClosure.length] = MezzeRecord.empty();

        Object[] rightArgs = new Object[rightClosure.length + 1];
        System.arraycopy(rightClosure, 0, rightArgs, 0, rightClosure.length);
        rightArgs[rightClosure.length] = MezzeRecord.empty();

        Env env = MezzeContext.get(node).getEnv();
        MezzeRecord[] handlerChain = EffectFiber.snapshotHandlerChain();

        CompletableFuture<MezzeVariant> leftFuture = new CompletableFuture<>();
        CompletableFuture<MezzeVariant> rightFuture = new CompletableFuture<>();
        Thread leftThread = spawnJoinSide(env, handlerChain, leftFuture, true,
            () -> callNode.call(leftTarget, leftArgs));
        Thread rightThread = spawnJoinSide(env, handlerChain, rightFuture, true,
            () -> callNode.call(rightTarget, rightArgs));

        leftThread.start();
        rightThread.start();

        try {
            MezzeVariant lr = leftFuture.get();
            MezzeVariant rr = rightFuture.get();
            return MezzeRecord.fromFields(LEFT_RIGHT_FIELDS, new Object[]{lr, rr});
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            leftThread.interrupt();
            rightThread.interrupt();
            // Surface as two Err AsyncCancelled — no host exception
            // escapes; caller pattern-matches the Result.
            MezzeVariant cancelled = createAsyncCancelled();
            return MezzeRecord.fromFields(LEFT_RIGHT_FIELDS, new Object[]{cancelled, cancelled});
        } catch (ExecutionException e) {
            // Defensive: futures should always complete with a Result.
            leftThread.interrupt();
            rightThread.interrupt();
            MezzeVariant failed = createAsyncFailed(
                "join: unexpected ExecutionException: " + e);
            return MezzeRecord.fromFields(LEFT_RIGHT_FIELDS, new Object[]{failed, failed});
        }
    }

    @TruffleBoundary
    public static MezzeRecord loomJoin3(MezzeRecord args, IndirectCallNode callNode, MezzeNode node) {
        MezzeFunction a = getFunction(args, "a");
        MezzeFunction b = getFunction(args, "b");
        MezzeFunction c = getFunction(args, "c");

        Env env = MezzeContext.get(node).getEnv();
        MezzeRecord[] handlerChain = EffectFiber.snapshotHandlerChain();

        CompletableFuture<MezzeVariant> aFuture = new CompletableFuture<>();
        CompletableFuture<MezzeVariant> bFuture = new CompletableFuture<>();
        CompletableFuture<MezzeVariant> cFuture = new CompletableFuture<>();
        Thread aThread = spawnJoinSide(env, handlerChain, aFuture, true,
            () -> callThunkFunction(a, callNode));
        Thread bThread = spawnJoinSide(env, handlerChain, bFuture, true,
            () -> callThunkFunction(b, callNode));
        Thread cThread = spawnJoinSide(env, handlerChain, cFuture, true,
            () -> callThunkFunction(c, callNode));

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
            MezzeVariant cancelled = createAsyncCancelled();
            return MezzeRecord.fromFields(
                ABC_FIELDS, new Object[]{cancelled, cancelled, cancelled});
        } catch (ExecutionException e) {
            // Defensive — futures complete with Result, never exceptionally.
            aThread.interrupt();
            bThread.interrupt();
            cThread.interrupt();
            MezzeVariant failed = createAsyncFailed(
                "join3: unexpected ExecutionException: " + e);
            return MezzeRecord.fromFields(
                ABC_FIELDS, new Object[]{failed, failed, failed});
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

        // Snapshot the parent's handler chain once for all batches.
        MezzeRecord[] handlerChain = EffectFiber.snapshotHandlerChain();
        java.util.concurrent.atomic.AtomicReference<TaskUnhandledOpException> opSink =
            new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> errSink =
            new java.util.concurrent.atomic.AtomicReference<>();

        // Use Truffle virtual threads for proper context handling
        Thread[] threads = new Thread[numBatches];
        final int finalBatchSize = batchSize;

        for (int b = 0; b < numBatches; b++) {
            final int batchStart = b * finalBatchSize;
            final int batchEnd = Math.min(batchStart + finalBatchSize, xs.length);

            threads[b] = env.newTruffleThreadBuilder(() -> SpawnSupport.runBatchTask(handlerChain, opSink, errSink, () -> {
                Object[] callArgs = getCallArgsBuffer(closureLen + 1);
                System.arraycopy(closure, 0, callArgs, 0, closureLen);
                for (int i = batchStart; i < batchEnd; i++) {
                    MezzeRecord arg = MezzeRecord.fromFields(X_FIELD, new Object[]{xs[i]});
                    callArgs[closureLen] = arg;
                    results[i] = callNode.call(callTarget, callArgs);
                }
            })).virtual(true).build();
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

        SpawnSupport.propagateBatchFailure(opSink, errSink, "parallel map");
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

        MezzeContext context = MezzeContext.get(node);
        Env env = context.getEnv();

        int numCores = Runtime.getRuntime().availableProcessors();
        int batchSize = Math.max(1, xs.length / (numCores * 2));
        batchSize = Math.min(batchSize, BATCH_SIZE);
        int numBatches = (xs.length + batchSize - 1) / batchSize;

        MezzeRecord[] handlerChain = EffectFiber.snapshotHandlerChain();
        java.util.concurrent.atomic.AtomicReference<TaskUnhandledOpException> opSink =
            new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> errSink =
            new java.util.concurrent.atomic.AtomicReference<>();

        Thread[] threads = new Thread[numBatches];
        final int finalBatchSize = batchSize;

        for (int b = 0; b < numBatches; b++) {
            final int batchStart = b * finalBatchSize;
            final int batchEnd = Math.min(batchStart + finalBatchSize, xs.length);

            threads[b] = env.newTruffleThreadBuilder(() -> SpawnSupport.runBatchTask(handlerChain, opSink, errSink, () -> {
                Object[] callArgs = getCallArgsBuffer(closureLen + 1);
                System.arraycopy(closure, 0, callArgs, 0, closureLen);
                for (int i = batchStart; i < batchEnd; i++) {
                    MezzeRecord arg = MezzeRecord.fromFields(X_FIELD, new Object[]{xs[i]});
                    callArgs[closureLen] = arg;
                    callNode.call(callTarget, callArgs);
                }
            })).virtual(true).build();
            threads[b].start();
        }

        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("ForEach interrupted", e);
            }
        }

        SpawnSupport.propagateBatchFailure(opSink, errSink, "parallel forEach");
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

        MezzeContext context = MezzeContext.get(node);
        Env env = context.getEnv();

        boolean[] passed = new boolean[xs.length];

        int numCores = Runtime.getRuntime().availableProcessors();
        int batchSize = Math.max(1, xs.length / (numCores * 2));
        batchSize = Math.min(batchSize, BATCH_SIZE);
        int numBatches = (xs.length + batchSize - 1) / batchSize;

        MezzeRecord[] handlerChain = EffectFiber.snapshotHandlerChain();
        java.util.concurrent.atomic.AtomicReference<TaskUnhandledOpException> opSink =
            new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> errSink =
            new java.util.concurrent.atomic.AtomicReference<>();

        Thread[] threads = new Thread[numBatches];
        final int finalBatchSize = batchSize;

        for (int b = 0; b < numBatches; b++) {
            final int batchStart = b * finalBatchSize;
            final int batchEnd = Math.min(batchStart + finalBatchSize, xs.length);

            threads[b] = env.newTruffleThreadBuilder(() -> SpawnSupport.runBatchTask(handlerChain, opSink, errSink, () -> {
                Object[] callArgs = getCallArgsBuffer(closureLen + 1);
                System.arraycopy(closure, 0, callArgs, 0, closureLen);
                for (int i = batchStart; i < batchEnd; i++) {
                    MezzeRecord arg = MezzeRecord.fromFields(X_FIELD, new Object[]{xs[i]});
                    callArgs[closureLen] = arg;
                    Object result = callNode.call(callTarget, callArgs);
                    passed[i] = result instanceof Boolean && (Boolean) result;
                }
            })).virtual(true).build();
            threads[b].start();
        }

        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Filter interrupted", e);
            }
        }

        SpawnSupport.propagateBatchFailure(opSink, errSink, "parallel filter");

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

        MezzeRecord[] handlerChain = EffectFiber.snapshotHandlerChain();

        MezzePromise promise = new MezzePromise(null, null);
        scope.addChild(promise);

        Thread vThread = env.newTruffleThreadBuilder(() -> {
            try {
                EffectFiber.runWithHandlerChain(handlerChain, () -> {
                    if (scope.isCancelled()) {
                        promise.cancel();
                        return;
                    }
                    Object result = callNode.call(callTarget, closure);
                    promise.complete(result);
                });
            } catch (TaskUnhandledOpException tue) {
                promise.setUnhandledOpCall(tue.opCall);
                promise.complete(null);
            } catch (Throwable e) {
                promise.fail(e);
            } finally {
                scope.childCompleted();
            }
        }).virtual(true).build();

        promise.setExecutingThread(vThread);
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

        MezzeRecord[] handlerChain = EffectFiber.snapshotHandlerChain();

        MezzePromise promise = new MezzePromise(null, null);

        // Use TruffleThreadBuilder for proper context handling
        // Note: .virtual(false) for OS threads (platform threads)
        Thread thread = env.newTruffleThreadBuilder(
            () -> runPromiseTask(handlerChain, promise,
                () -> callNode.call(callTarget, fullArgs))
        ).build();  // Platform thread (not virtual)

        promise.setExecutingThread(thread);
        thread.start();

        return promise;
    }

    // OS versions reuse the same await/race/all/etc. implementations
    // since they all work with MezzePromise

    // osAwait / osRace / osAll / osTimeout / osCancel / osSleep / osIsDone /
    // osIsCancelled / osCheck / osAny / osSettle were all pure passthroughs
    // to their loom counterparts (they operated on MezzePromise values which
    // carry their own thread reference). The stdlib OS impls now call
    // __native_loom_* directly. The remaining os* natives below (join, join3,
    // map, for_each, filter, scoped, spawn, init/finalize_scope) keep their
    // own implementations because they genuinely create platform threads
    // with .virtual(false).

    @TruffleBoundary
    public static MezzeRecord osJoin(MezzeRecord args, IndirectCallNode callNode, MezzeNode node) {
        MezzeFunction left = getFunction(args, "l");
        MezzeFunction right = getFunction(args, "r");

        Env env = MezzeContext.get(node).getEnv();
        MezzeRecord[] handlerChain = EffectFiber.snapshotHandlerChain();

        CompletableFuture<MezzeVariant> leftFuture = new CompletableFuture<>();
        CompletableFuture<MezzeVariant> rightFuture = new CompletableFuture<>();

        Thread leftThread = spawnJoinSide(env, handlerChain, leftFuture, false,
            () -> callThunkFunction(left, callNode));
        Thread rightThread = spawnJoinSide(env, handlerChain, rightFuture, false,
            () -> callThunkFunction(right, callNode));

        leftThread.start();
        rightThread.start();

        try {
            MezzeVariant lr = leftFuture.get();
            MezzeVariant rr = rightFuture.get();
            return MezzeRecord.fromFields(LEFT_RIGHT_FIELDS, new Object[]{lr, rr});
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            leftThread.interrupt();
            rightThread.interrupt();
            MezzeVariant cancelled = createAsyncCancelled();
            return MezzeRecord.fromFields(LEFT_RIGHT_FIELDS, new Object[]{cancelled, cancelled});
        } catch (ExecutionException e) {
            leftThread.interrupt();
            rightThread.interrupt();
            MezzeVariant failed = createAsyncFailed(
                "join: unexpected ExecutionException: " + e);
            return MezzeRecord.fromFields(LEFT_RIGHT_FIELDS, new Object[]{failed, failed});
        }
    }

    @TruffleBoundary
    public static MezzeRecord osJoin3(MezzeRecord args, IndirectCallNode callNode, MezzeNode node) {
        MezzeFunction a = getFunction(args, "a");
        MezzeFunction b = getFunction(args, "b");
        MezzeFunction c = getFunction(args, "c");

        Env env = MezzeContext.get(node).getEnv();
        MezzeRecord[] handlerChain = EffectFiber.snapshotHandlerChain();

        CompletableFuture<MezzeVariant> aFuture = new CompletableFuture<>();
        CompletableFuture<MezzeVariant> bFuture = new CompletableFuture<>();
        CompletableFuture<MezzeVariant> cFuture = new CompletableFuture<>();

        Thread aThread = spawnJoinSide(env, handlerChain, aFuture, false,
            () -> callThunkFunction(a, callNode));
        Thread bThread = spawnJoinSide(env, handlerChain, bFuture, false,
            () -> callThunkFunction(b, callNode));
        Thread cThread = spawnJoinSide(env, handlerChain, cFuture, false,
            () -> callThunkFunction(c, callNode));

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
            MezzeVariant cancelled = createAsyncCancelled();
            return MezzeRecord.fromFields(
                ABC_FIELDS, new Object[]{cancelled, cancelled, cancelled});
        } catch (ExecutionException e) {
            aThread.interrupt();
            bThread.interrupt();
            cThread.interrupt();
            MezzeVariant failed = createAsyncFailed(
                "join3: unexpected ExecutionException: " + e);
            return MezzeRecord.fromFields(
                ABC_FIELDS, new Object[]{failed, failed, failed});
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

    // ==================== AsyncError / PromiseState / CancelOutcome Helpers ====================

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

    private static MezzeVariant createAsyncTimedOut(long ms) {
        int tag = MezzeVariant.TagRegistry.getGlobal().getOrCreateTag("AsyncTimedOut");
        MezzeRecord errRec = MezzeRecord.fromFields(
            new String[]{"ms"}, new Object[]{ms});
        return MezzeVariant.singlePayload(MezzeVariant.TagRegistry.getGlobal(), tag, errRec);
    }

    // PromiseState constructors. Pending is allocation-free via singleton.
    private static volatile MezzeVariant PENDING_SINGLETON = null;
    private static MezzeVariant promiseStatePending() {
        MezzeVariant v = PENDING_SINGLETON;
        if (v != null) return v;
        int tag = MezzeVariant.TagRegistry.getGlobal().getOrCreateTag("Pending");
        v = MezzeVariant.noPayload(MezzeVariant.TagRegistry.getGlobal(), tag);
        PENDING_SINGLETON = v;
        return v;
    }
    private static MezzeVariant promiseStateCompleted(Object val) {
        int tag = MezzeVariant.TagRegistry.getGlobal().getOrCreateTag("Completed");
        MezzeRecord rec = MezzeRecord.fromFields(new String[]{"val"}, new Object[]{val});
        return MezzeVariant.singlePayload(MezzeVariant.TagRegistry.getGlobal(), tag, rec);
    }
    private static MezzeVariant promiseStateFailed(Object errVariant) {
        int tag = MezzeVariant.TagRegistry.getGlobal().getOrCreateTag("Failed");
        MezzeRecord rec = MezzeRecord.fromFields(new String[]{"err"}, new Object[]{errVariant});
        return MezzeVariant.singlePayload(MezzeVariant.TagRegistry.getGlobal(), tag, rec);
    }

    // CancelOutcome constructors. All cached singletons (no payload).
    private static volatile MezzeVariant CANCEL_DID_CANCEL_V       = null;
    private static volatile MezzeVariant CANCEL_ALREADY_COMPLETED_V = null;
    private static volatile MezzeVariant CANCEL_ALREADY_FAILED_V    = null;
    private static volatile MezzeVariant CANCEL_NOT_CANCELLABLE_V   = null;
    private static MezzeVariant cancelOutcomeFor(int outcome) {
        return switch (outcome) {
            case MezzePromise.CANCEL_DID_CANCEL -> {
                MezzeVariant v = CANCEL_DID_CANCEL_V;
                if (v == null) {
                    int tag = MezzeVariant.TagRegistry.getGlobal().getOrCreateTag("Cancelled");
                    v = MezzeVariant.noPayload(MezzeVariant.TagRegistry.getGlobal(), tag);
                    CANCEL_DID_CANCEL_V = v;
                }
                yield v;
            }
            case MezzePromise.CANCEL_ALREADY_COMPLETED -> {
                MezzeVariant v = CANCEL_ALREADY_COMPLETED_V;
                if (v == null) {
                    int tag = MezzeVariant.TagRegistry.getGlobal().getOrCreateTag("AlreadyCompleted");
                    v = MezzeVariant.noPayload(MezzeVariant.TagRegistry.getGlobal(), tag);
                    CANCEL_ALREADY_COMPLETED_V = v;
                }
                yield v;
            }
            case MezzePromise.CANCEL_ALREADY_FAILED -> {
                MezzeVariant v = CANCEL_ALREADY_FAILED_V;
                if (v == null) {
                    int tag = MezzeVariant.TagRegistry.getGlobal().getOrCreateTag("AlreadyFailed");
                    v = MezzeVariant.noPayload(MezzeVariant.TagRegistry.getGlobal(), tag);
                    CANCEL_ALREADY_FAILED_V = v;
                }
                yield v;
            }
            default -> {
                MezzeVariant v = CANCEL_NOT_CANCELLABLE_V;
                if (v == null) {
                    int tag = MezzeVariant.TagRegistry.getGlobal().getOrCreateTag("NotCancellable");
                    v = MezzeVariant.noPayload(MezzeVariant.TagRegistry.getGlobal(), tag);
                    CANCEL_NOT_CANCELLABLE_V = v;
                }
                yield v;
            }
        };
    }

    // ==================== Promise Natives (new API #117/#118/#123) ====================

    /** Non-blocking snapshot of a Promise's state, returns the
     *  PromiseState 'a ADT value. Used by `Promise.inspect`. */
    @TruffleBoundary
    public static MezzeVariant promiseInspect(MezzeRecord args) {
        MezzePromise p = getPromise(args, "p");
        int state = p.snapshotState();
        return switch (state) {
            case MezzePromise.PENDING   -> promiseStatePending();
            case MezzePromise.COMPLETED -> promiseStateCompleted(p.snapshotResult());
            case MezzePromise.FAILED    -> promiseStateFailed(asyncErrorFromResult(p.snapshotResult()));
            case MezzePromise.CANCELLED -> promiseStateFailed(createAsyncCancelled());
            default -> throw new RuntimeException("MezzePromise: unknown state " + state);
        };
    }

    /** Cancel the Promise and return a CancelOutcome variant. */
    @TruffleBoundary
    public static MezzeVariant promiseCancel(MezzeRecord args) {
        MezzePromise p = getPromise(args, "p");
        int outcome = p.cancelOutcome();
        return cancelOutcomeFor(outcome);
    }

    /** Construct an already-Completed Promise. */
    @TruffleBoundary
    public static MezzePromise promiseSucceed(MezzeRecord args) {
        Object val = getFieldValue(args, "val");
        MezzePromise p = new MezzePromise(null, null);
        p.complete(val);
        return p;
    }

    /** Construct an already-Failed Promise. err must be an AsyncError variant. */
    @TruffleBoundary
    public static MezzePromise promiseFail(MezzeRecord args) {
        Object err = getFieldValue(args, "err");
        MezzePromise p = new MezzePromise(null, null);
        p.fail(err);
        return p;
    }

    /** Block until the promise resolves; return a `Result 'a AsyncError`
     *  variant. Replaces the throw-on-failure behavior of loomAwait. */
    @TruffleBoundary
    public static Object promiseAwaitResult(MezzeRecord args) {
        MezzePromise p = getPromise(args, "p");
        Object value;
        try {
            value = p.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return MezzeVariant.err(createAsyncCancelled());
        } catch (RuntimeException e) {
            // p.await() throws on FAILED / CANCELLED via getResultOrThrow.
            // Classify by the promise's final state and return Result Err.
            int state = p.snapshotState();
            if (state == MezzePromise.CANCELLED) {
                return MezzeVariant.err(createAsyncCancelled());
            }
            if (state == MezzePromise.FAILED) {
                return MezzeVariant.err(asyncErrorFromResult(p.snapshotResult()));
            }
            // Some other RuntimeException — re-throw.
            throw e;
        }
        // Same propagation logic as loomAwaitPromise — MezzeSuspended forcing
        // and unhandled-op re-raise still happen, those are NOT failure modes
        // but the body's effect propagation.
        if (value instanceof MezzeSuspended suspended) {
            Object forced = suspended.force(IndirectCallNode.getUncached());
            return MezzeVariant.ok(forced);
        }
        OpCall pending = p.getUnhandledOpCall();
        if (pending != null) {
            EffectFiber parentFiber = EffectFiber.current();
            if (parentFiber != null) {
                Object forced = parentFiber.suspend(pending);
                return MezzeVariant.ok(forced);
            }
            if (EffectFiber.handlerChainDepth() > 0) {
                throw new EffectHandlerDriver.TaskUnhandledOpException(pending);
            }
            // No handler — surface as AsyncFailed so caller sees Result Err.
            return MezzeVariant.err(createAsyncFailed(
                "Spawned task raised effect operation '" + pending.opName()
                + "' with no handler available at await site."));
        }
        // Classify by final state.
        int state = p.snapshotState();
        return switch (state) {
            case MezzePromise.COMPLETED -> MezzeVariant.ok(value);
            case MezzePromise.FAILED    -> MezzeVariant.err(asyncErrorFromResult(value));
            case MezzePromise.CANCELLED -> MezzeVariant.err(createAsyncCancelled());
            default -> MezzeVariant.err(createAsyncFailed(
                "promise.await returned with unexpected state " + state));
        };
    }

    /** Convert a MezzePromise's stored result-on-failure into an AsyncError
     *  variant for the user-side Mezze API.
     *
     *  Two shapes the result field can take:
     *    - MezzeVariant (user constructed via promiseFail OR settle paths
     *      already stored a Mezze AsyncError there): pass through.
     *    - Throwable (regular spawned-body-crash path): wrap as
     *      AsyncFailed { msg = e.getMessage() }.
     *  Anything else falls through to a generic AsyncFailed for safety. */
    private static Object asyncErrorFromResult(Object r) {
        if (r instanceof MezzeVariant v) return v;
        if (r instanceof Throwable t)    return createAsyncFailed(t.getMessage());
        return createAsyncFailed(r == null ? "Unknown error" : r.toString());
    }

    // ==================== Helper Methods ====================

    @FunctionalInterface
    private interface TaskBody {
        Object run();
    }

    /**
     * Body for a promise-returning spawn. Installs {@code chain}, runs
     * {@code body}, completes {@code promise} with the result. If the body
     * raised an unhandled effect-op (handler in the chain called abort,
     * propagated past the chain), stash it on the promise so {@code await}
     * can re-issue it on the parent fiber. Any other throwable fails the
     * promise.
     */
    private static void runPromiseTask(MezzeRecord[] chain, MezzePromise promise, TaskBody body) {
        // Inlined EffectFiber.runWithHandlerChain so we don't allocate a
        // second lambda per spawn. The chain.length == 0 fast path matches
        // the common case (top-level Async.spawn outside any perform).
        boolean installed = false;
        try {
            if (chain.length > 0) {
                EffectFiber.installHandlerChain(chain);
                installed = true;
            }
            Object result = body.run();
            promise.complete(result);
        } catch (TaskUnhandledOpException tue) {
            promise.setUnhandledOpCall(tue.opCall);
            promise.complete(null);
        } catch (Throwable e) {
            promise.fail(e);
        } finally {
            if (installed) EffectFiber.clearHandlerChain();
        }
    }


    private static Object loomAwaitPromise(MezzePromise promise) {
        Object result;
        try {
            result = promise.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while awaiting", e);
        }
        // Effect propagation across the spawn boundary:
        //
        // 1. If the spawned task's body invoked an effect-op with no fiber
        //    current on its thread, OpCallNode wrapped the call as a
        //    MezzeSuspended thunk. When await retrieves it on the parent
        //    thread, the parent IS in a perform — force the thunk so the
        //    op-call dispatches on the parent fiber. This handles the
        //    common `perform ExceptionToResult in spawn (raise)` case.
        if (result instanceof MezzeSuspended suspended) {
            return suspended.force(IndirectCallNode.getUncached());
        }
        // 2. If the task ran via the captured handler chain and an op-call
        //    propagated past the entire chain, it was stashed via
        //    setUnhandledOpCall. Re-issue here. Three cases by location:
        //    a) Awaiter has a current fiber (the common case — parent is
        //       inside a `perform Handler in ...`): yield to the driver
        //       with the op-call so its handler chain dispatches.
        //    b) Awaiter has no fiber but a handler chain (this thread is
        //       itself a spawned task): re-throw TaskUnhandledOpException
        //       so the surrounding runPromiseTask / runBatchTask catches
        //       it and propagates one level further up.
        //    c) Neither: nowhere to send the op. Wrap as RuntimeException.
        OpCall pending = promise.getUnhandledOpCall();
        if (pending != null) {
            EffectFiber parentFiber = EffectFiber.current();
            if (parentFiber != null) {
                return parentFiber.suspend(pending);
            }
            if (EffectFiber.handlerChainDepth() > 0) {
                throw new EffectHandlerDriver.TaskUnhandledOpException(pending);
            }
            throw new RuntimeException(
                "Spawned task raised effect operation '" + pending.opName()
                + "' with no handler available at await site.");
        }
        return result;
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
        // Unwrap Promise newtype — variants from CoopAsync are
        // `Promise { val = nativePromise }`, where the payload is a
        // record. Loom passes the bare MezzePromise. Handle both.
        if (val instanceof MezzeVariant v) {
            val = v.getPayloadValue();
            if (val instanceof MezzeRecord innerRecord) {
                int valIdx = innerRecord.getFieldIndex("val");
                if (valIdx >= 0) {
                    val = innerRecord.getField(valIdx);
                }
            }
        }
        if (val instanceof MezzePromise p) return p;
        throw new RuntimeException("Expected Promise for '" + name + "'");
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

    // ========================================================================
    // Result<'a, AsyncError> helpers exposed for CoopScheduler (#159 / #122c).
    // Keep behavior identical to the private equivalents above so Loom and
    // Coop produce the same Result shape for the same outcome.
    // ========================================================================

    static MezzeVariant asyncOkResult(Object value) {
        return MezzeVariant.ok(value);
    }

    static MezzeVariant asyncCancelledResult() {
        return createAsyncCancelled();
    }

    static MezzeVariant asyncFailedResult(String msg) {
        return createAsyncFailed(msg);
    }

    static MezzeVariant asyncFailedResultFromPromise(MezzePromise promise) {
        return MezzeVariant.err(asyncErrorFromResult(promise.result));
    }

    /** Same as {@link #cancelOtherPromises} but operates on the Object[]
     *  shape coop uses (its array element type is Object, not MezzePromise). */
    static void cancelOtherCoopPromises(Object[] promises, MezzePromise winner) {
        cancelOtherPromises(promises, winner);
    }
}
