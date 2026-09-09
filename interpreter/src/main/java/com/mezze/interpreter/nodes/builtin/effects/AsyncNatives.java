package com.mezze.interpreter.nodes.builtin.effects;

import com.mezze.interpreter.runtime.NativeTypeMismatchException;

import com.mezze.datastructures.list.MezzeList;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.nodes.builtin.EffectHandlerDriver;
import com.mezze.interpreter.nodes.builtin.EffectHandlerDriver.TaskUnhandledOpException;
import com.mezze.interpreter.nodes.continuation.HandlerChainRegistry;
import com.mezze.interpreter.nodes.continuation.HandlerChainEntry;
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
import java.util.concurrent.*;
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
        public static final int PENDING = 0;
        public static final int COMPLETED = 1;
        public static final int FAILED = 2;
        public static final int CANCELLED = 3;

        // #79: one atomic reference holds state + payload together.
        // Every terminal transition is a single CAS installing an
        // Outcome instance; readers do a single acquire-load and pattern-
        // match. No possibility of a losing writer corrupting the
        // winner's payload because losers never touch the field.
        //
        // Old design (state + result as separate volatile fields, written
        // in a specific order) was correct only for single-writer paths;
        // any race between complete/fail/cancel could publish inconsistent
        // (state, result) pairs.

        /** Base class for terminal outcomes. Subclasses are final. */
        private static abstract class Outcome {
            final int kind;
            Outcome(int kind) { this.kind = kind; }
        }
        private static final class CompletedOutcome extends Outcome {
            final Object value;
            CompletedOutcome(Object value) { super(COMPLETED); this.value = value; }
        }
        private static final class FailedOutcome extends Outcome {
            final Object error;
            FailedOutcome(Object error) { super(FAILED); this.error = error; }
        }
        private static final class CancelledOutcome extends Outcome {
            final Object reason;
            CancelledOutcome(Object reason) { super(CANCELLED); this.reason = reason; }
        }

        /** Sentinel meaning "still pending, no outcome yet". Reference
         *  equality with this is how readers detect the pending state. */
        private static final Object PENDING_SENTINEL = new Object();

        private static final VarHandle OUTCOME;
        private static final VarHandle WAITER;

        static {
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                OUTCOME = l.findVarHandle(MezzePromise.class, "outcome", Object.class);
                WAITER  = l.findVarHandle(MezzePromise.class, "waiter",  Thread.class);
            } catch (Exception e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        // Cache line padding to prevent false sharing between promises
        @SuppressWarnings("unused")
        private long p0, p1, p2, p3, p4, p5, p6;

        /** Either {@link #PENDING_SENTINEL} or one of the three
         *  {@link Outcome} subclasses. Single-write via CAS by the
         *  terminal-transition winner; observers acquire-read once and
         *  dispatch on the concrete subclass. */
        private volatile Object outcome = PENDING_SENTINEL;

        /** Thread parked in await/awaitTimed. Written by waiters via
         *  {@code WAITER.setRelease}; read by terminal transitions via
         *  {@code WAITER.getAcquire} to know whom to unpark. */
        private volatile Thread waiter;

        @SuppressWarnings("unused")
        private long q0, q1, q2, q3, q4, q5, q6;

        // Set in the constructor or via setExecutingThread() once the
        // spawned virtual thread has been created. Single writer (the
        // spawn-site code), single reader (cancel()) which only fires
        // after the spawn site has returned and the body has had a chance
        // to run, so plain field access is safe.
        Thread executingThread;
        final Semaphore semaphore;

        public void setExecutingThread(Thread t) {
            this.executingThread = t;
        }

        // Set by Async.spawn when the spawned task body terminated because
        // an op-call propagated past all inherited handlers. Read by
        // Async.await, which re-raises the op-call on the parent fiber so
        // the parent's handler chain can dispatch it.
        private volatile OpCall unhandledOpCall;

        /**
         * Backend-supplied cancel hook. Set by user-space Promise
         * constructors (promise_from via its on_cancel capability) so a
         * cancel() call can propagate to a foreign scheduler (Kotlin
         * Job, CompletableFuture, etc.) with the cancel reason.
         *
         * The hook receives the reason (or null → ExternalInterrupt
         * fallback). Runs on the thread that won the cancel CAS.
         * Errors thrown by the hook are swallowed — cancel semantics
         * do not depend on hook success.
         *
         * Last-writer-wins if setCancelHook is called multiple times.
         *
         * Single-writer (the promise_from setup callback), single-reader
         * (cancel()). Volatile for visibility across threads.
         */
        private volatile java.util.function.Consumer<Object> cancelHook;

        public MezzePromise(Thread executingThread, Semaphore semaphore) {
            this.executingThread = executingThread;
            this.semaphore = semaphore;
        }

        public void setCancelHook(java.util.function.Consumer<Object> hook) {
            this.cancelHook = hook;
        }

        public void setUnhandledOpCall(OpCall op) {
            this.unhandledOpCall = op;
        }

        public OpCall getUnhandledOpCall() {
            return unhandledOpCall;
        }

        // ==================== Terminal transitions ====================
        //
        // Each of complete/fail/cancel/cancelOutcome does one CAS on
        // OUTCOME. Winner allocates its Outcome; losers see non-PENDING
        // and return false with no observable side effect. postCommit
        // runs cancel-only side effects (interrupt, hook) for cancel
        // and always unparks the waiter.

        public boolean complete(Object value) {
            if (OUTCOME.compareAndSet(this, PENDING_SENTINEL, new CompletedOutcome(value))) {
                unparkWaiter();
                return true;
            }
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
            if (OUTCOME.compareAndSet(this, PENDING_SENTINEL, new FailedOutcome(error))) {
                unparkWaiter();
                return true;
            }
            return false;
        }

        public boolean cancel() {
            return cancel(null);
        }

        /**
         * Cancel with a caller-supplied reason.
         *
         * `reason` is a Mezze CancelReason variant (opaque Object here —
         * this native code just stores and re-emits it). Null means the
         * caller didn't supply one; downstream construction of
         * AsyncCancelled substitutes ExternalInterrupt as the fallback.
         *
         * Under #79's design: the reason is embedded in the CancelledOutcome
         * installed by the CAS winner. Losing racers cannot overwrite it.
         */
        public boolean cancel(Object reason) {
            if (OUTCOME.compareAndSet(this, PENDING_SENTINEL, new CancelledOutcome(reason))) {
                if (executingThread != null) {
                    executingThread.interrupt();
                }
                java.util.function.Consumer<Object> hook = cancelHook;
                if (hook != null) {
                    try { hook.accept(reason); } catch (Throwable ignored) { /* hook errors don't unwind cancel */ }
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
            return cancelOutcome(null);
        }

        public int cancelOutcome(Object reason) {
            if (OUTCOME.compareAndSet(this, PENDING_SENTINEL, new CancelledOutcome(reason))) {
                if (executingThread != null) {
                    executingThread.interrupt();
                }
                java.util.function.Consumer<Object> hook = cancelHook;
                if (hook != null) {
                    try { hook.accept(reason); } catch (Throwable ignored) { /* hook errors don't unwind cancel */ }
                }
                unparkWaiter();
                return CANCEL_DID_CANCEL;
            }
            Object o = outcome;
            if (o instanceof CompletedOutcome) return CANCEL_ALREADY_COMPLETED;
            if (o instanceof FailedOutcome)    return CANCEL_ALREADY_FAILED;
            if (o instanceof CancelledOutcome) return CANCEL_DID_CANCEL;  // racing cancel saw us
            return CANCEL_NOT_CANCELLABLE;
        }

        /**
         * Snapshot the reason captured by whoever won the cancel CAS.
         * Null when the Promise wasn't cancelled or the winner passed
         * no reason. Callers converting to AsyncCancelled pass this
         * through {@link #createAsyncCancelled(Object)}, which
         * substitutes ExternalInterrupt when this is null.
         */
        public Object getCancelReason() {
            Object o = outcome;
            return (o instanceof CancelledOutcome c) ? c.reason : null;
        }

        /** Snapshot the state. Returns one of PENDING/COMPLETED/FAILED/CANCELLED. */
        public int snapshotState() {
            Object o = outcome;
            return (o instanceof Outcome oc) ? oc.kind : PENDING;
        }

        /**
         * Snapshot the terminal payload. Meaningful only after
         * {@link #snapshotState} returns non-PENDING; on PENDING this
         * returns null.
         *
         * For COMPLETED, returns the completion value (may itself be
         * null — Mezze's Unit path stores that explicitly).
         * For FAILED, returns the error object (Throwable or AsyncError variant).
         * For CANCELLED, returns the cancel reason (may be null).
         */
        public Object snapshotResult() {
            Object o = outcome;
            if (o instanceof CompletedOutcome c) return c.value;
            if (o instanceof FailedOutcome f)    return f.error;
            if (o instanceof CancelledOutcome x) return x.reason;
            return null;
        }

        /** `@TruffleBoundary` — LockSupport.unpark reaches Pthread.pthread_mutex_lock,
         *  a blocklisted native call under SVM Truffle runtime compilation. Called
         *  from Promise.complete on async completion — cold path. #402. */
        @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        private void unparkWaiter() {
            Thread w = (Thread) WAITER.getAcquire(this);
            if (w != null) {
                LockSupport.unpark(w);
            }
        }

        public boolean isDone() {
            return outcome != PENDING_SENTINEL;
        }

        public boolean isCompleted() {
            return outcome instanceof CompletedOutcome;
        }

        public boolean isFailed() {
            return outcome instanceof FailedOutcome;
        }

        public boolean isCancelled() {
            return outcome instanceof CancelledOutcome;
        }

        /** Terminal-state payload. Prefer {@link #snapshotResult()} —
         *  this is an alias kept for existing callers. */
        public Object getResult() {
            return snapshotResult();
        }

        /**
         * Blocking await with ADAPTIVE spin-then-park strategy for low latency.
         * Loops on isDone() — reads the single outcome field per iteration.
         */
        public Object await() throws InterruptedException {
            long startNanos = shouldSampleThisCall() ? System.nanoTime() : 0;

            // Fast path: already done
            Object o = outcome;
            if (o != PENDING_SENTINEL) {
                if (startNanos != 0) recordCompletionTime(System.nanoTime() - startNanos);
                return getResultOrThrow(o);
            }

            // Adaptive spinning based on historical completion times
            int spinCount = getAdaptiveSpinCount();
            for (int i = 0; i < spinCount; i++) {
                o = outcome;
                if (o != PENDING_SENTINEL) {
                    if (startNanos != 0) recordCompletionTime(System.nanoTime() - startNanos);
                    return getResultOrThrow(o);
                }
                Thread.onSpinWait();
            }

            // Park with waiter registration
            WAITER.setRelease(this, Thread.currentThread());
            try {
                while ((o = outcome) == PENDING_SENTINEL) {
                    LockSupport.park(this);
                    if (Thread.interrupted()) {
                        throw new InterruptedException();
                    }
                }
            } finally {
                WAITER.setRelease(this, null);
            }

            if (startNanos != 0) recordCompletionTime(System.nanoTime() - startNanos);
            return getResultOrThrow(o);
        }

        /**
         * Timed await.
         */
        public Object await(long timeoutMs) throws InterruptedException, TimeoutException {
            Object o = outcome;
            if (o != PENDING_SENTINEL) {
                return getResultOrThrow(o);
            }

            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            WAITER.setRelease(this, Thread.currentThread());
            try {
                while ((o = outcome) == PENDING_SENTINEL) {
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

            return getResultOrThrow(o);
        }

        /**
         * Legacy throwing accessor. `o` must be a non-pending Outcome.
         *
         * The live await path is `__native_promise_await_result`, which
         * returns a Mezze `Result 'a AsyncError` (see async.mz). These
         * throws are the legacy blocking accessor's failure propagation and
         * keep the original {@link Throwable} as the cause via the
         * cause-carrying {@link MezzeGuestException} constructor, so the real
         * error survives for debugging (see #387 / S8.1-E).
         */
        private Object getResultOrThrow(Object o) {
            if (o instanceof CompletedOutcome c) return c.value;
            if (o instanceof FailedOutcome f) {
                if (f.error instanceof Throwable t) {
                    throw new MezzeGuestException("Task failed: " + t.getMessage(), t);
                }
                throw new MezzeGuestException("Task failed with unknown error: " + f.error);
            }
            if (o instanceof CancelledOutcome) {
                throw new CancellationException("Task was cancelled");
            }
            throw new IllegalStateException("Promise in unexpected outcome: " + o);
        }
    }

    // ==================== Scope Implementation ====================

    /**
     * Structured concurrency scope - tracks child tasks and propagates cancellation.
     * Uses ConcurrentLinkedQueue instead of CopyOnWriteArrayList for O(1) addChild.
     *
     * Transitive cancellation (#252): each MezzeScope also tracks any nested
     * Scopes opened while one of its spawned tasks was running. `cancelAll()`
     * walks `childScopes` so cancelling a parent reaches grandchildren even
     * when the descendant task is uninterruptibly blocked (e.g., parked on a
     * non-Thread.interrupt-aware primitive). Parent linkage is established via
     * the CURRENT_SCOPE ThreadLocal in scoped.
     */
    public static final class MezzeScope {
        // OPTIMIZATION: ConcurrentLinkedQueue has O(1) add vs CopyOnWriteArrayList's O(n)
        private final ConcurrentLinkedQueue<MezzePromise> children = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<MezzeScope> childScopes = new ConcurrentLinkedQueue<>();
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicInteger pendingCount = new AtomicInteger(0);

        // Truffle environment for spawning child threads with proper context
        Env env;

        void addChild(MezzePromise promise) {
            children.add(promise);
            pendingCount.incrementAndGet();
        }

        void addChildScope(MezzeScope nested) {
            childScopes.add(nested);
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
                // Walk into nested scopes so cancellation reaches descendants
                // even when a child task's thread is blocked on something that
                // doesn't honour Thread.interrupt().
                for (MezzeScope nested : childScopes) {
                    nested.cancelAll();
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

    /**
     * Tracks the innermost MezzeScope currently executing on this thread (if
     * any). Used by scoped to wire up parent→child linkage for transitive
     * cancellation (#252). A scope opened by a task that is itself a child of
     * an outer scope adopts that outer scope as parent.
     */
    private static final ThreadLocal<MezzeScope> CURRENT_SCOPE = new ThreadLocal<>();

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
    public static PromiseTracker initScope(MezzeRecord args) {
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
    public static MezzeRecord finalizeScopeNative(MezzeRecord args) {
        Object trackerObj = getFieldValue(args, "tracker");
        if (trackerObj instanceof PromiseTracker tracker) {
            tracker.clear();
        }
        return MezzeRecord.empty();
    }

    /**
     * Spawn with tracking - registers promise with tracker for cleanup.
     */
    @TruffleBoundary
    public static MezzePromise spawnTracked(MezzeRecord args, IndirectCallNode callNode, MezzeNode node) {
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
        HandlerChainEntry[] handlerChain = HandlerChainRegistry.snapshotHandlerChainEntries();

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
     * Same semantics as finalizeScopeNative - unawaited tasks continue running.
     */
    @TruffleBoundary
    public static MezzeRecord osFinalizeScope(MezzeRecord args) {
        Object trackerObj = getFieldValue(args, "tracker");
        if (trackerObj instanceof PromiseTracker tracker) {
            tracker.clear();
        }
        return MezzeRecord.empty();
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
        HandlerChainEntry[] handlerChain = HandlerChainRegistry.snapshotHandlerChainEntries();

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
    public static MezzePromise spawn(MezzeRecord args, IndirectCallNode callNode, MezzeNode node) {
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
        HandlerChainEntry[] handlerChain = HandlerChainRegistry.snapshotHandlerChainEntries();

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

    @TruffleBoundary
    public static Object awaitOp(MezzeRecord args) {
        MezzePromise promise = getPromise(args, "p");
        return awaitPromise(promise);
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

    @TruffleBoundary
    public static MezzeRecord sleep(MezzeRecord args) {
        long ms = getLong(args, "ms");
        try {
            Thread.sleep(ms);
            return MezzeRecord.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Sleep cancelled");
        }
    }

    /**
     * #254 — voluntarily yield the current task's slot back to the
     * scheduler / OS. On virtual threads, Thread.yield() is a hint to
     * the JDK scheduler to reschedule; still cheap. Useful in
     * user-written tight loops that want to be cooperative under a
     * single-threaded scheduler and want to give cancel-delivery a
     * chance to land under Loom.
     */
    @TruffleBoundary
    public static MezzeRecord yieldNow(MezzeRecord args) {
        Thread.yield();
        return MezzeRecord.empty();
    }

    /**
     * Park the current task until {@code predicate} returns True.
     * Spin-park with exponential backoff up to a small cap; LockSupport
     * .parkNanos lets the scheduler hibernate the virtual thread. An
     * explicit waker would be cheaper for predicates that change
     * rarely; can land later if profile demands.
     */
    @TruffleBoundary
    public static MezzeRecord parkUntil(MezzeRecord args, IndirectCallNode callNode) {
        MezzeFunction predicate = getFunction(args, "predicate");
        long backoffNanos = 1_000L; // 1µs to start
        final long maxBackoffNanos = 1_000_000L; // 1ms cap
        while (true) {
            Object result = callThunkFunction(predicate, callNode);
            if (result instanceof Boolean b && b) return MezzeRecord.empty();
            LockSupport.parkNanos(backoffNanos);
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                throw new CancellationException("Park cancelled");
            }
            backoffNanos = Math.min(backoffNanos * 2, maxBackoffNanos);
        }
    }

    @TruffleBoundary
    public static Object scoped(MezzeRecord args, IndirectCallNode callNode, MezzeNode node) {
        MezzeFunction f = getFunction(args, "f");
        MezzeScope scope = new MezzeScope();

        // Store env for scope-spawned children
        Env env = MezzeContext.get(node).getEnv();
        scope.env = env;

        // Transitive cancellation linkage (#252): if this thread is already
        // running inside a parent Scope (because we were spawned by a parent
        // scope's body), register as its child so `cancelAll(parent)` reaches
        // us. Outermost scopes have no parent → CURRENT_SCOPE is null.
        MezzeScope prev = CURRENT_SCOPE.get();
        if (prev != null) {
            prev.addChildScope(scope);
        }

        // Install this scope as current for the duration of the body so any
        // nested `Async.scoped` calls in the body register us as their parent.
        CURRENT_SCOPE.set(scope);
        try {
            MezzeRecord scopeArg = MezzeRecord.fromFields(
                new String[]{"sc"},
                new Object[]{scope}
            );
            // Clear CURRENT fiber around the callback so op-calls inside
            // f route through chain dispatch instead of suspending the
            // outer perform's fiber (whose continuation is yielded
            // several layers deep on the driver stack). Without this,
            // nested op-calls in f — e.g. Async.spawn or a routed
            // Time.sleep — bubble as MezzeSuspend from the outer
            // driver instead of dispatching to the installed handler.
            // #274.
            Object result = HandlerChainRegistry.runDetached(() ->
                callNode.call(f.getCallTarget(), appendArg(f.getClosureValues(), scopeArg))
            );

            // Wait for all children
            scope.awaitAll();

            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scope.cancelAll();
            throw new CancellationException("Scoped execution cancelled");
        } catch (CancellationException ce) {
            scope.cancelAll();
            throw ce;
        } catch (RuntimeException e) {
            scope.cancelAll();
            throw e;
        } finally {
            if (prev == null) {
                CURRENT_SCOPE.remove();
            } else {
                CURRENT_SCOPE.set(prev);
            }
        }
    }

    @TruffleBoundary
    public static MezzePromise scopeSpawn(MezzeRecord args, IndirectCallNode callNode, MezzeNode node) {
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

        HandlerChainEntry[] handlerChain = HandlerChainRegistry.snapshotHandlerChainEntries();

        MezzePromise promise = new MezzePromise(null, null);
        scope.addChild(promise);

        Thread vThread = env.newTruffleThreadBuilder(() -> {
            // Pin this scope as current on the spawned thread so any nested
            // `Async.scoped` opened by the body registers itself as child of
            // `scope` (#252 transitive cancellation linkage).
            CURRENT_SCOPE.set(scope);
            try {
                HandlerChainRegistry.runWithHandlerChain(handlerChain, () -> {
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
            } catch (CancellationException ce) {
                // Cancel arrived while the body was blocked in a native
                // op (sleep, park, mutex, etc.); the op translated its
                // InterruptedException to CancellationException so we
                // could finalize here as CANCELLED (not FAILED). If the
                // parent already CAS'd to CANCELLED with a reason via
                // cancelOutcome, this cancel(null) is a no-op. Otherwise
                // (external interrupt), we finalize with null → the
                // ExternalInterrupt fallback inside AsyncCancelled.
                promise.cancel(null);
            } catch (Throwable e) {
                promise.fail(e);
            } finally {
                scope.childCompleted();
                CURRENT_SCOPE.remove();
            }
        }).virtual(true).build();

        promise.setExecutingThread(vThread);
        vThread.start();

        return promise;
    }

    @TruffleBoundary
    public static MezzeRecord scopeCancelAll(MezzeRecord args) {
        getScope(args, "sc").cancelAll();
        return MezzeRecord.empty();
    }

    @TruffleBoundary
    public static boolean scopeIsCancelled(MezzeRecord args) {
        return getScope(args, "sc").isCancelled();
    }

    @TruffleBoundary
    public static MezzeRecord scopeGetToken(MezzeRecord args) {
        MezzeScope scope = getScope(args, "sc");
        return MezzeRecord.fromFields(
            new String[]{"_scope_ref"},
            new Object[]{scope}
        );
    }

    // ==================== OS Thread Operations ====================
    // Only osSpawnTracked and osInitScope / osFinalizeScope (above) keep
    // their own platform-thread implementations. All other OS Async ops
    // are now pure-Mezze passthroughs to __native_async_* / __native_promise_* — see
    // stdlib/std/effects/async.mz (OSThreadAsync impl).

    // ==================== AsyncError / PromiseState / CancelOutcome Helpers ====================

    /**
     * Build an `AsyncCancelled { reason: CancelReason }` variant.
     *
     * `reason` is the raw CancelReason variant supplied by the caller,
     * or null when no reason was available at the cancel site (e.g. a
     * cancel driven by native Thread.interrupt from outside Mezze).
     * Null substitutes {@link #EXTERNAL_INTERRUPT_REASON} — the Mezze
     * ADT has no "no reason" case; every path names why.
     */
    static MezzeVariant createAsyncCancelled(Object reason) {
        int tag = MezzeVariant.TagRegistry.getGlobal().getOrCreateTag("AsyncCancelled");
        Object r = reason != null ? reason : externalInterruptReason();
        MezzeRecord errRec = MezzeRecord.fromFields(
            new String[]{"reason"},
            new Object[]{r}
        );
        return MezzeVariant.singlePayload(MezzeVariant.TagRegistry.getGlobal(), tag, errRec);
    }

    // Cached ExternalInterrupt singleton — no-payload variant, so we can
    // reuse it across every native path that needs a fallback reason.
    private static volatile MezzeVariant EXTERNAL_INTERRUPT_REASON = null;
    private static MezzeVariant externalInterruptReason() {
        MezzeVariant v = EXTERNAL_INTERRUPT_REASON;
        if (v != null) return v;
        int tag = MezzeVariant.TagRegistry.getGlobal().getOrCreateTag("ExternalInterrupt");
        v = MezzeVariant.noPayload(MezzeVariant.TagRegistry.getGlobal(), tag);
        EXTERNAL_INTERRUPT_REASON = v;
        return v;
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
            case MezzePromise.CANCELLED -> promiseStateFailed(createAsyncCancelled(p.getCancelReason()));
            // Internal invariant: `state` is one of exactly four snapshot
            // constants; any other value is a runtime bug, not a program input.
            default -> throw new IllegalStateException("[internal] MezzePromise: unknown snapshot state " + state);
        };
    }

    /** Cancel the Promise from the parent side (i.e. the caller of
     *  Async.cancel). CAS PENDING→CANCELLED, records the reason, and
     *  interrupts the child thread if any. The awaiter reads
     *  cancelReason and the CancellationException catch in the child's
     *  task loop finalizes with a no-op cancel(null). */
    @TruffleBoundary
    public static MezzeVariant promiseCancel(MezzeRecord args) {
        MezzePromise p = getPromise(args, "p");
        Object reason = getFieldValueOrNull(args, "reason");
        int outcome = p.cancelOutcome(reason);
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
     *  variant. Replaces the throw-on-failure behavior of awaitOp. */
    @TruffleBoundary
    public static Object promiseAwaitResult(MezzeRecord args) {
        MezzePromise p = getPromise(args, "p");
        Object value;
        try {
            value = p.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return MezzeVariant.err(createAsyncCancelled(p.getCancelReason()));
        } catch (RuntimeException e) {
            // p.await() throws on FAILED / CANCELLED via getResultOrThrow.
            // Classify by the promise's final state and return Result Err.
            int state = p.snapshotState();
            if (state == MezzePromise.CANCELLED) {
                return MezzeVariant.err(createAsyncCancelled(p.getCancelReason()));
            }
            if (state == MezzePromise.FAILED) {
                return MezzeVariant.err(asyncErrorFromResult(p.snapshotResult()));
            }
            // Some other RuntimeException — re-throw.
            throw e;
        }
        // Same propagation logic as awaitPromise — MezzeSuspend forcing
        // and unhandled-op re-raise still happen, those are NOT failure modes
        // but the body's effect propagation.
        if (value instanceof MezzeSuspend suspended) {
            Object forced = suspended.force(IndirectCallNode.getUncached());
            return MezzeVariant.ok(forced);
        }
        OpCall pending = p.getUnhandledOpCall();
        if (pending != null) {
            // Chain-dispatch is the only path from an unhandled spawn-side
            // op back to the outer perform on this thread.
            if (HandlerChainRegistry.handlerChainDepth() > 0) {
                Object dispatched = com.mezze.interpreter.nodes.effect.OpCallNode
                    .dispatchOpOnChain(
                        HandlerChainRegistry.snapshotHandlerChainEntries(),
                        pending.opName(),
                        pending.args(),
                        pending.effectHash(),
                        IndirectCallNode.getUncached());
                if (dispatched != com.mezze.interpreter.nodes.effect.OpCallNode.DISPATCH_NO_MATCH) {
                    return MezzeVariant.ok(dispatched);
                }
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
            case MezzePromise.CANCELLED -> MezzeVariant.err(createAsyncCancelled(p.getCancelReason()));
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
    private static void runPromiseTask(HandlerChainEntry[] chain, MezzePromise promise, TaskBody body) {
        // Inlined HandlerChainRegistry.runWithHandlerChain so we don't allocate a
        // second lambda per spawn. The chain.length == 0 fast path matches
        // the common case (top-level Async.spawn outside any perform).
        boolean installed = false;
        try {
            if (chain.length > 0) {
                HandlerChainRegistry.installHandlerChainEntries(chain);
                installed = true;
            }
            Object result = body.run();
            promise.complete(result);
        } catch (TaskUnhandledOpException tue) {
            promise.setUnhandledOpCall(tue.opCall);
            promise.complete(null);
        } catch (CancellationException ce) {
            // Native blocking ops translate InterruptedException to
            // CancellationException so cancel-during-blocking surfaces as
            // a typed AsyncCancelled result on await rather than a generic
            // task-failed runtime error. If the parent already CAS'd to
            // CANCELLED with a reason, this cancel(null) is a no-op;
            // otherwise we finalize with null → ExternalInterrupt.
            promise.cancel(null);
        } catch (Throwable e) {
            promise.fail(e);
        } finally {
            if (installed) HandlerChainRegistry.clearHandlerChain();
        }
    }


    private static Object awaitPromise(MezzePromise promise) {
        Object result;
        try {
            result = promise.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // The awaiter was cancelled while waiting. Surface as a typed
            // CancellationException so the surrounding runPromiseTask marks
            // the calling task CANCELLED (not FAILED).
            throw new CancellationException("Interrupted while awaiting");
        }
        // Effect propagation across the spawn boundary:
        //
        // 1. If the spawned task's body invoked an effect-op with no fiber
        //    current on its thread, OpCallNode wrapped the call as a
        //    MezzeSuspend thunk. When await retrieves it on the parent
        //    thread, the parent IS in a perform — force the thunk so the
        //    op-call dispatches on the parent fiber. This handles the
        //    common `perform ExceptionToResult in spawn (raise)` case.
        if (result instanceof MezzeSuspend suspended) {
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
            if (HandlerChainRegistry.handlerChainDepth() > 0) {
                // The outer BDSL Handle @Operation installed its handler
                // on the per-thread chain; dispatch directly through the
                // chain — under insideBdslHandle() this preserves
                // AbortPerformException up to the outer Handle catch.
                Object dispatched = com.mezze.interpreter.nodes.effect.OpCallNode
                    .dispatchOpOnChain(
                        HandlerChainRegistry.snapshotHandlerChainEntries(),
                        pending.opName(),
                        pending.args(),
                        pending.effectHash(),
                        IndirectCallNode.getUncached());
                if (dispatched != com.mezze.interpreter.nodes.effect.OpCallNode.DISPATCH_NO_MATCH) {
                    return dispatched;
                }
                throw new EffectHandlerDriver.TaskUnhandledOpException(pending);
            }
            // User-reachable: a spawned task performed an effect operation
            // that no handler at the await site can discharge. Guest-visible
            // fatal — an unhandled effect, consistent with the language's
            // unhandled-effect semantics.
            throw new MezzeGuestException(
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

    /**
     * Read a field by name, returning null if absent. Used for optional
     * native arguments — currently `reason` on __native_promise_cancel,
     * where the ADT itself has no "no reason" case but the *native shim
     * layer* allows the field to be omitted (older callers, test
     * harnesses that construct the record dynamically).
     */
    private static Object getFieldValueOrNull(MezzeRecord r, String name) {
        int idx = r.getFieldIndex(name);
        if (idx < 0) return null;
        return r.getField(idx);
    }

    private static Object getFieldValue(MezzeRecord r, String name) {
        int idx = r.getFieldIndex(name);
        if (idx < 0) throw new NativeTypeMismatchException("Missing field '" + name + "' in record: " + r);
        return r.getField(idx);
    }

    private static MezzeFunction getFunction(MezzeRecord r, String name) {
        int idx = r.getFieldIndex(name);
        if (idx < 0) throw new NativeTypeMismatchException("Missing field '" + name + "' in record: " + r);
        Object val = r.getField(idx);
        if (val instanceof MezzeFunction f) return f;
        throw new NativeTypeMismatchException("Expected function for '" + name + "', got: " +
            (val == null ? "null" : val.getClass().getName() + " = " + val) +
            ". Full record: " + r);
    }

    private static long getLong(MezzeRecord r, String name) {
        int idx = r.getFieldIndex(name);
        if (idx < 0) throw new NativeTypeMismatchException("Missing field '" + name + "'");
        Object val = r.getField(idx);
        if (val instanceof Long l) return l;
        throw new NativeTypeMismatchException("Expected Long for '" + name + "'");
    }

    private static Object[] getArray(MezzeRecord r, String name) {
        int idx = r.getFieldIndex(name);
        if (idx < 0) throw new NativeTypeMismatchException("Missing field '" + name + "'");
        Object val = r.getField(idx);
        if (val instanceof Object[] arr) return arr;
        if (val instanceof MezzeSeq seq) return seq.toArray();
        if (val instanceof MezzeList list) return list.toArray();
        throw new NativeTypeMismatchException("Expected array for '" + name + "'");
    }

    private static MezzeList getList(MezzeRecord r, String name) {
        int idx = r.getFieldIndex(name);
        if (idx < 0) throw new NativeTypeMismatchException("Missing field '" + name + "'");
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
        throw new NativeTypeMismatchException("Expected list for '" + name + "'");
    }

    private static MezzePromise getPromise(MezzeRecord r, String name) {
        int idx = r.getFieldIndex(name);
        if (idx < 0) throw new NativeTypeMismatchException("Missing field '" + name + "'");
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
        throw new NativeTypeMismatchException("Expected Promise for '" + name + "'");
    }

    private static MezzeScope getScope(MezzeRecord r, String name) {
        int idx = r.getFieldIndex(name);
        if (idx < 0) throw new NativeTypeMismatchException("Missing field '" + name + "'");
        Object val = r.getField(idx);
        if (val instanceof MezzeVariant v) {
            val = v.getPayloadValue();
        }
        if (val instanceof MezzeScope s) return s;
        throw new NativeTypeMismatchException("Expected Scope for '" + name + "'");
    }

    // ========================================================================
    // Result<'a, AsyncError> helpers.
    // ========================================================================

    static MezzeVariant asyncOkResult(Object value) {
        return MezzeVariant.ok(value);
    }

    /**
     * Build a `Result _ AsyncError = Err { error = AsyncCancelled { reason } }`
     * variant. Coop callers were returning the raw AsyncError variant —
     * wrong; users pattern-match `Err { error = AsyncCancelled { reason = _ } }`
     * and saw a tag mismatch. Matches Loom's promiseAwaitResult.
     *
     * `reason` is a CancelReason variant (from the cancelled promise's
     * stored reason). Null falls back to ExternalInterrupt inside
     * createAsyncCancelled.
     */
    static MezzeVariant asyncCancelledResult() {
        return asyncCancelledResult(null);
    }

    static MezzeVariant asyncCancelledResult(Object reason) {
        return MezzeVariant.err(createAsyncCancelled(reason));
    }

    static MezzeVariant asyncFailedResult(String msg) {
        return MezzeVariant.err(createAsyncFailed(msg));
    }

    static MezzeVariant asyncFailedResultFromPromise(MezzePromise promise) {
        return MezzeVariant.err(asyncErrorFromResult(promise.snapshotResult()));
    }

    /** Same as {@link #cancelOtherPromises} but operates on the Object[]
     *  shape coop uses (its array element type is Object, not MezzePromise). */
    static void cancelOtherCoopPromises(Object[] promises, MezzePromise winner) {
        cancelOtherPromises(promises, winner);
    }
}
