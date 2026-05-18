package com.mezze.interpreter.nodes.builtin.effects;

import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.datastructures.seq.MezzeSeq;
import com.mezze.interpreter.values.MezzeUnit;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.IndirectCallNode;

import java.util.*;

/**
 * Cooperative scheduler for single-threaded async execution.
 *
 * Today's design: tasks run to completion on the Java stack;
 * interleaving happens only at spawn boundaries. {@link #parkUntil}
 * runs ready tasks while polling a predicate, which enables simple
 * park-based primitives (Mutex, Latch) to work under Coop without
 * contention.
 *
 * <p>Scheduler tasks complete a shared {@link AsyncNatives.MezzePromise}
 * — the same promise type Loom uses. That makes Promise value ops
 * (inspect, is_*, cancel) scheduler-agnostic at the user-facing layer.
 *
 * <p>Limitation: nested {@code parkUntil} (a parked task whose body
 * itself parks) deadlocks because the inner parker doesn't capture
 * its continuation. The proper fix is fiber-based suspension via
 * EffectFiber (tracked in #113). Until that lands, CoopAsync is a
 * good drop-in for spawn/await tests and uncontended primitives, but
 * not for producer/consumer-style nested-park scenarios — those
 * tests are {@code @Disabled}.
 */
public final class CoopScheduler {

    // Ready queue - tasks waiting to execute
    private final Deque<CoopTask> readyQueue = new ArrayDeque<>();

    // Scheduler ID for serialization
    private final UUID id = UUID.randomUUID();

    /**
     * A task in the scheduler - wraps function + the MezzePromise it
     * completes. Run order is determined by the ready queue.
     */
    static final class CoopTask {
        final MezzeFunction function;
        final AsyncNatives.MezzePromise promise;

        CoopTask(MezzeFunction function, AsyncNatives.MezzePromise promise) {
            this.function = function;
            this.promise = promise;
        }
    }

    // ========================================================================
    // Core Operations
    // ========================================================================

    /**
     * Spawn a new task - adds to ready queue, returns promise immediately.
     * Task is NOT executed yet.
     */
    @TruffleBoundary
    public AsyncNatives.MezzePromise spawn(MezzeFunction f) {
        AsyncNatives.MezzePromise promise = new AsyncNatives.MezzePromise(null, null);
        readyQueue.addLast(new CoopTask(f, promise));
        return promise;
    }

    /**
     * Await a promise - runs scheduler until the promise completes.
     *
     * @param promise The promise to await
     * @param callNode For calling Mezze functions
     * @return The promise result
     */
    @TruffleBoundary
    public Object await(AsyncNatives.MezzePromise promise, IndirectCallNode callNode) {
        // Fast path: already complete
        if (promise.isDone()) {
            return resultOrThrow(promise);
        }

        // Run scheduler until this promise completes
        runUntil(promise, callNode);

        // Now should be complete
        if (!promise.isDone()) {
            throw new RuntimeException("Deadlock: scheduler promise never completed "
                + "(ready queue empty: " + readyQueue.isEmpty() + ")");
        }

        return resultOrThrow(promise);
    }

    /**
     * Drain scheduler - run all tasks until ready queue is empty.
     */
    @TruffleBoundary
    public void drain(IndirectCallNode callNode) {
        while (!readyQueue.isEmpty()) {
            runOneTask(callNode);
        }
    }

    /**
     * Run tasks until a specific promise completes.
     */
    @TruffleBoundary
    public void runUntil(AsyncNatives.MezzePromise target, IndirectCallNode callNode) {
        while (!target.isDone() && !readyQueue.isEmpty()) {
            runOneTask(callNode);
        }
    }

    /**
     * Park: run ready tasks until {@code predicate} returns True. The
     * predicate is a Mezze closure of type {@code {} -> Bool}; it's
     * called between task runs. Deadlocks (throws) if the ready queue
     * empties and the predicate is still False — no way to make
     * progress.
     */
    @TruffleBoundary
    public void parkUntil(MezzeFunction predicate, IndirectCallNode callNode) {
        while (true) {
            if (callPredicate(predicate, callNode)) return;
            if (readyQueue.isEmpty()) {
                throw new RuntimeException(
                    "CoopAsync deadlock: parked on a predicate that never became True");
            }
            runOneTask(callNode);
        }
    }

    private boolean callPredicate(MezzeFunction predicate, IndirectCallNode callNode) {
        Object[] closure = predicate.getClosureValues();
        Object[] args = new Object[closure.length + 1];
        System.arraycopy(closure, 0, args, 0, closure.length);
        args[closure.length] = MezzeRecord.empty();
        Object result = callNode.call(predicate.getCallTarget(), args);
        return result instanceof Boolean b && b;
    }

    /**
     * Run one task from the ready queue.
     */
    private void runOneTask(IndirectCallNode callNode) {
        if (readyQueue.isEmpty()) {
            return;
        }

        CoopTask task = readyQueue.pollFirst();

        try {
            // Execute the task function
            Object result = executeTaskFunction(task.function, callNode);

            // Task completed successfully
            task.promise.complete(result);

        } catch (Throwable e) {
            // Task failed
            task.promise.fail(e);
        }
    }

    /**
     * Execute a task's function.
     */
    private Object executeTaskFunction(MezzeFunction f, IndirectCallNode callNode) {
        Object[] closure = f.getClosureValues();
        Object[] args = appendArg(closure, MezzeRecord.empty());
        return callNode.call(f.getCallTarget(), args);
    }

    // ========================================================================
    // Collection Operations
    // ========================================================================

    /**
     * Race - wait for first promise to complete.
     */
    @TruffleBoundary
    public Object race(Object[] promiseArray, IndirectCallNode callNode) {
        if (promiseArray.length == 0) {
            throw new RuntimeException("race requires at least one promise");
        }

        // Check if any already complete
        for (Object p : promiseArray) {
            AsyncNatives.MezzePromise promise = (AsyncNatives.MezzePromise) p;
            if (promise.isDone()) {
                return resultOrThrow(promise);
            }
        }

        // Run until any promise completes
        while (true) {
            // Check if any complete now
            for (Object p : promiseArray) {
                AsyncNatives.MezzePromise promise = (AsyncNatives.MezzePromise) p;
                if (promise.isDone()) {
                    return resultOrThrow(promise);
                }
            }

            // Run one task
            if (readyQueue.isEmpty()) {
                throw new RuntimeException("Deadlock: no promise completed and no tasks to run");
            }
            runOneTask(callNode);
        }
    }

    /**
     * All - wait for all promises to complete.
     */
    @TruffleBoundary
    public MezzeSeq all(Object[] promiseArray, IndirectCallNode callNode) {
        Object[] results = new Object[promiseArray.length];

        for (int i = 0; i < promiseArray.length; i++) {
            AsyncNatives.MezzePromise promise = (AsyncNatives.MezzePromise) promiseArray[i];
            results[i] = await(promise, callNode);
        }

        return MezzeSeq.fromArray(results);
    }

    /**
     * Sleep - yield to other tasks.
     * In coop mode, this just runs other ready tasks once then returns.
     */
    @TruffleBoundary
    public void sleep(long ms, IndirectCallNode callNode) {
        // In cooperative mode, sleep just yields to other ready tasks
        // Run one task if available, then return
        if (!readyQueue.isEmpty()) {
            runOneTask(callNode);
        }
        // Real time-based sleeping would require integration with event loop
    }

    // ========================================================================
    // Utility Methods
    // ========================================================================

    public boolean isDone(AsyncNatives.MezzePromise promise) {
        return promise.isDone();
    }

    public int pendingCount() {
        return readyQueue.size();
    }

    /**
     * Read the final value of a settled promise. On FAILED, re-throw so
     * the await caller observes the same behavior as before the
     * CoopPromise → MezzePromise switch.
     */
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
