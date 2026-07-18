package com.mezze.interpreter.nodes.builtin.effects;

import com.mezze.interpreter.nodes.continuation.EffectFiber;
import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.datastructures.seq.MezzeSeq;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.IndirectCallNode;

import java.util.*;

/**
 * Cooperative scheduler for single-threaded async execution.
 *
 * <p>Each spawn creates an {@link EffectFiber} (no virtual thread — just
 * a JDK delimited continuation). {@link #parkUntil} suspends the
 * current fiber via the same Continuation.yield primitive Loom uses;
 * the driver loop wakes parked fibers when their predicate flips true.
 *
 * <p>Because each fiber owns its own continuation/stack, nested park
 * works: a fiber that parks while another fiber is also parked simply
 * yields back to the driver, which schedules whatever's ready next.
 *
 * <p>Promise completion uses the shared {@link AsyncNatives.MezzePromise},
 * so Promise value ops (inspect, is_*, cancel) work identically under
 * Loom and Coop at the user-facing layer.
 */
public final class CoopScheduler {

    /** Sentinel suspend-payload telling the driver "fiber is parked". */
    private static final class ParkRequest {
        final MezzeFunction predicate;
        ParkRequest(MezzeFunction predicate) { this.predicate = predicate; }
    }

    /** Per-thread handle so a fiber body can find its scheduler when parking. */
    private static final ThreadLocal<CoopScheduler> CURRENT = new ThreadLocal<>();
    /** Per-thread handle for the coop fiber currently executing on this scheduler. */
    private static final ThreadLocal<EffectFiber> CURRENT_FIBER = new ThreadLocal<>();
    /** Per-thread handle for the result promise of the coop fiber currently
     *  executing on this scheduler. Used by isCancelledCurrent / checkCurrent
     *  so the running fiber body can observe its own cancellation. */
    private static final ThreadLocal<AsyncNatives.MezzePromise> CURRENT_PROMISE = new ThreadLocal<>();

    private final Deque<CoopFiber> readyQueue = new ArrayDeque<>();
    private final List<CoopFiber> parkedFibers = new ArrayList<>();
    private final UUID id = UUID.randomUUID();

    /** A fiber tracked by this scheduler — pairs the JDK continuation with its result promise. */
    static final class CoopFiber {
        final EffectFiber fiber;
        final AsyncNatives.MezzePromise promise;
        /** Set while parked; null while ready or running. */
        ParkRequest park;

        CoopFiber(EffectFiber fiber, AsyncNatives.MezzePromise promise) {
            this.fiber = fiber;
            this.promise = promise;
        }
    }

    // ========================================================================
    // Core Operations
    // ========================================================================

    /**
     * Spawn a new task — wraps it in an EffectFiber and adds to the ready
     * queue. Task is NOT executed yet.
     *
     * <p>The body captures the parent's handler chain (so dispatch from
     * inside the spawned body resolves to the same handlers — including
     * {@code CoopAsync} itself, which routes back into this scheduler).
     */
    @TruffleBoundary
    public AsyncNatives.MezzePromise spawn(MezzeFunction f, IndirectCallNode callNode) {
        AsyncNatives.MezzePromise promise = new AsyncNatives.MezzePromise(null, null);
        com.mezze.interpreter.nodes.continuation.HandlerChainEntry[] handlerChain =
            EffectFiber.snapshotHandlerChainEntries();

        Runnable body = () -> {
            EffectFiber.installHandlerChainEntries(handlerChain);
            try {
                Object result = callMezzeThunk(f, callNode);
                promise.complete(result);
            } catch (Throwable t) {
                promise.fail(t);
            } finally {
                EffectFiber.popHandlerChain(handlerChain.length);
            }
        };

        EffectFiber fiber = EffectFiber.create("coop-task-" + UUID.randomUUID(), body);
        readyQueue.addLast(new CoopFiber(fiber, promise));
        return promise;
    }

    /**
     * Await a promise — runs scheduler until the promise completes.
     */
    @TruffleBoundary
    public Object await(AsyncNatives.MezzePromise promise, IndirectCallNode callNode) {
        if (promise.isDone()) {
            return resultOrThrow(promise);
        }
        runUntil(promise, callNode);
        if (!promise.isDone()) {
            throw new RuntimeException("Deadlock: scheduler promise never completed "
                + "(ready queue empty: " + readyQueue.isEmpty()
                + ", parked: " + parkedFibers.size() + ")");
        }
        return resultOrThrow(promise);
    }

    /**
     * Await a promise and return its final state as a `Result 'a AsyncError`
     * variant — never throws on FAILED / CANCELLED. The counterpart to
     * Loom's {@code promiseAwaitResult}: keeps the Result contract honest
     * under CoopAsync.
     */
    @TruffleBoundary
    public Object awaitResult(AsyncNatives.MezzePromise promise, IndirectCallNode callNode) {
        if (!promise.isDone()) {
            runUntil(promise, callNode);
        }
        if (promise.isCompleted()) {
            return AsyncNatives.asyncOkResult(promise.snapshotResult());
        }
        if (promise.isFailed()) {
            return AsyncNatives.asyncFailedResultFromPromise(promise);
        }
        if (promise.isCancelled()) {
            return AsyncNatives.asyncCancelledResult();
        }
        return AsyncNatives.asyncFailedResult("scheduler await: promise never settled");
    }

    /** Drain — run all tasks until both ready and parked are empty (or stuck). */
    @TruffleBoundary
    public void drain(IndirectCallNode callNode) {
        while (!readyQueue.isEmpty() || !parkedFibers.isEmpty()) {
            if (!tick(callNode)) break;
        }
    }

    /** Run tasks until a specific promise completes. */
    @TruffleBoundary
    public void runUntil(AsyncNatives.MezzePromise target, IndirectCallNode callNode) {
        while (!target.isDone()) {
            if (!tick(callNode)) break;
        }
    }

    /**
     * Returns true if the currently-running coop fiber's result promise is
     * in the CANCELLED state. Called from inside a fiber body. If called
     * outside any coop fiber (no current promise), returns false.
     */
    @TruffleBoundary
    public boolean isCancelledCurrent() {
        AsyncNatives.MezzePromise self = CURRENT_PROMISE.get();
        return self != null && self.isCancelled();
    }

    /**
     * Cancellation checkpoint for cooperative scheduler. If the running
     * fiber's promise is CANCELLED, throws CancellationException so the
     * tick-loop catches it and propagates the cancel cleanly. If no fiber
     * is running, no-op (matches LoomAsync.check semantics outside spawn).
     */
    @TruffleBoundary
    public void checkCurrent() {
        AsyncNatives.MezzePromise self = CURRENT_PROMISE.get();
        if (self != null && self.isCancelled()) {
            throw new java.util.concurrent.CancellationException("Coop task cancelled");
        }
    }

    /**
     * Park the *current* fiber until {@code predicate} returns True.
     *
     * <p>Called from inside a fiber body. Suspends via Continuation.yield,
     * returning control to the driver loop. The driver re-polls the
     * predicate on each tick; when it flips, the fiber is moved back to
     * ready and resumed on the next tick.
     */
    @TruffleBoundary
    public void parkUntil(MezzeFunction predicate, IndirectCallNode callNode) {
        // Fast path: already true.
        if (callPredicate(predicate, callNode)) return;
        EffectFiber self = CURRENT_FIBER.get();
        if (self == null) {
            // Called from outside any coop fiber (e.g. the main perform body
            // before any spawn) — fall back to busy-poll on the ready queue.
            while (true) {
                if (callPredicate(predicate, callNode)) return;
                if (readyQueue.isEmpty() && parkedFibers.isEmpty()) {
                    throw new RuntimeException(
                        "CoopAsync deadlock: parked on a predicate that never became True");
                }
                if (!tick(callNode)) {
                    throw new RuntimeException(
                        "CoopAsync deadlock: parked on a predicate that never became True");
                }
            }
        }
        // Inside a coop fiber: yield to the driver with a park request.
        self.suspendUnscoped(new ParkRequest(predicate));
        // Re-entry: predicate became True; nothing more to do.
    }

    /**
     * Run one ready fiber to its next suspend/completion, then re-poll
     * parked predicates. Returns true if forward progress was made.
     */
    private boolean tick(IndirectCallNode callNode) {
        if (readyQueue.isEmpty()) {
            // Nothing ready — try to unpark first (handles cases where
            // a predicate became true via external mutation between ticks).
            if (!unparkReady(callNode)) {
                return false;
            }
        }
        CoopFiber task = readyQueue.pollFirst();
        if (task == null) return false;

        // Observe cancellation (#160): if the promise was cancelled while
        // this fiber sat in the ready queue, skip the body entirely. The
        // promise is already in CANCELLED state; just close the fiber so
        // its continuation can be GC'd.
        if (task.promise.isCancelled()) {
            try { task.fiber.close(); } catch (Throwable ignored) {}
            return true;
        }

        CoopScheduler prevSched = CURRENT.get();
        EffectFiber prevFiber = CURRENT_FIBER.get();
        AsyncNatives.MezzePromise prevPromise = CURRENT_PROMISE.get();
        CURRENT.set(this);
        CURRENT_FIBER.set(task.fiber);
        CURRENT_PROMISE.set(task.promise);
        try {
            task.fiber.runUnscoped();
        } catch (java.util.concurrent.CancellationException ce) {
            // Body called Async.check {} (or a coop-aware native) and observed
            // cancellation. Mark the promise CANCELLED instead of FAILED.
            task.promise.cancel();
            return true;
        } catch (Throwable t) {
            task.promise.fail(t);
            return true;
        } finally {
            CURRENT.set(prevSched);
            CURRENT_FIBER.set(prevFiber);
            CURRENT_PROMISE.set(prevPromise);
        }

        if (task.fiber.isDone()) {
            // Promise already completed by the body's invoke wrapper.
            // Defensive: if it isn't, that's a runtime bug.
            return true;
        }

        Object suspended = task.fiber.takeSuspendedValue();
        if (suspended instanceof ParkRequest pr) {
            task.park = pr;
            parkedFibers.add(task);
        } else {
            task.promise.fail(new RuntimeException(
                "CoopScheduler: fiber suspended with unexpected payload " + suspended));
        }
        unparkReady(callNode);
        return true;
    }

    /**
     * Re-poll every parked fiber's predicate. Any whose predicate flips
     * True is moved to the ready queue. Returns true if at least one was
     * unparked or the ready queue was non-empty.
     */
    private boolean unparkReady(IndirectCallNode callNode) {
        if (parkedFibers.isEmpty()) return !readyQueue.isEmpty();
        Iterator<CoopFiber> it = parkedFibers.iterator();
        boolean unparked = false;
        while (it.hasNext()) {
            CoopFiber pf = it.next();
            // Cancellation (#160): a parked fiber whose promise was
            // cancelled never wakes — drop it from the parked set so
            // the scheduler doesn't deadlock waiting on it.
            if (pf.promise.isCancelled()) {
                pf.park = null;
                try { pf.fiber.close(); } catch (Throwable ignored) {}
                it.remove();
                unparked = true;  // forward progress: one fewer fiber to track
                continue;
            }
            if (pf.park == null || callPredicate(pf.park.predicate, callNode)) {
                pf.park = null;
                it.remove();
                readyQueue.addLast(pf);
                unparked = true;
            }
        }
        return unparked || !readyQueue.isEmpty();
    }

    private boolean callPredicate(MezzeFunction predicate, IndirectCallNode callNode) {
        Object[] closure = predicate.getClosureValues();
        Object[] args = new Object[closure.length + 1];
        System.arraycopy(closure, 0, args, 0, closure.length);
        args[closure.length] = MezzeRecord.empty();
        Object result = callNode.call(predicate.getCallTarget(), args);
        return result instanceof Boolean b && b;
    }

    private Object callMezzeThunk(MezzeFunction f, IndirectCallNode callNode) {
        Object[] closure = f.getClosureValues();
        Object[] args = appendArg(closure, MezzeRecord.empty());
        return callNode.call(f.getCallTarget(), args);
    }

    // ========================================================================
    // Collection Operations
    // ========================================================================

    /**
     * Race — return the first promise to settle as {@code Result 'a AsyncError},
     * auto-cancelling losing siblings (#122). Cancelled promises are
     * skipped — race waits for a Completed or Failed outcome. If every
     * promise is cancelled or progress is impossible, surfaces Err
     * AsyncCancelled.
     */
    @TruffleBoundary
    public Object race(Object[] promiseArray, IndirectCallNode callNode) {
        if (promiseArray.length == 0) {
            return AsyncNatives.asyncFailedResult("race requires at least one promise");
        }
        while (true) {
            Object winner = pollForWinner(promiseArray);
            if (winner != null) return winner;
            if (!tick(callNode)) {
                // Driver stalled with no progress possible — treat as cancelled.
                return AsyncNatives.asyncCancelledResult();
            }
        }
    }

    /**
     * Same shape as AsyncNatives.pollForWinner — picks the first
     * Completed/Failed promise and cancels its losers. Returns null
     * when no settled non-cancelled promise exists yet.
     */
    private static Object pollForWinner(Object[] promiseArray) {
        boolean allCancelled = true;
        for (Object p : promiseArray) {
            AsyncNatives.MezzePromise promise = (AsyncNatives.MezzePromise) p;
            if (!promise.isDone()) {
                allCancelled = false;
                continue;
            }
            if (promise.isCancelled()) continue;
            AsyncNatives.cancelOtherCoopPromises(promiseArray, promise);
            if (promise.isCompleted()) {
                return AsyncNatives.asyncOkResult(promise.snapshotResult());
            }
            return AsyncNatives.asyncFailedResultFromPromise(promise);
        }
        if (allCancelled) return AsyncNatives.asyncCancelledResult();
        return null;
    }

    /** All - wait for all promises to complete. */
    @TruffleBoundary
    public MezzeSeq all(Object[] promiseArray, IndirectCallNode callNode) {
        Object[] results = new Object[promiseArray.length];
        for (int i = 0; i < promiseArray.length; i++) {
            results[i] = await((AsyncNatives.MezzePromise) promiseArray[i], callNode);
        }
        return MezzeSeq.fromArray(results);
    }

    /** Sleep: cooperative yield — run one ready fiber if available. */
    @TruffleBoundary
    public void sleep(long ms, IndirectCallNode callNode) {
        if (!readyQueue.isEmpty() || !parkedFibers.isEmpty()) {
            tick(callNode);
        }
    }

    /**
     * #254 — voluntarily yield to the scheduler for one tick. Under
     * coop, "yield" and "sleep 0" are the same shape: if any other
     * task is ready, run it. If nothing else is ready, return
     * immediately. Symmetric with LoomAsync.yield_now (Thread.yield).
     */
    @TruffleBoundary
    public void yieldNow(IndirectCallNode callNode) {
        if (!readyQueue.isEmpty() || !parkedFibers.isEmpty()) {
            tick(callNode);
        }
    }

    public boolean isDone(AsyncNatives.MezzePromise promise) {
        return promise.isDone();
    }

    public int pendingCount() {
        return readyQueue.size() + parkedFibers.size();
    }

    // ========================================================================
    // Utility Methods
    // ========================================================================

    private static Object resultOrThrow(AsyncNatives.MezzePromise promise) {
        if (promise.isCompleted()) {
            return promise.snapshotResult();
        }
        if (promise.isFailed()) {
            Object stored = promise.snapshotResult();
            if (stored instanceof Throwable t) {
                throw new RuntimeException("Task failed: " + t.getMessage(), t);
            }
            throw new RuntimeException("Task failed: " + stored);
        }
        if (promise.isCancelled()) {
            throw new RuntimeException("Task cancelled");
        }
        throw new RuntimeException("Promise not settled");
    }

    private static Object[] appendArg(Object[] closure, Object arg) {
        Object[] result = new Object[closure.length + 1];
        System.arraycopy(closure, 0, result, 0, closure.length);
        result[closure.length] = arg;
        return result;
    }
}
