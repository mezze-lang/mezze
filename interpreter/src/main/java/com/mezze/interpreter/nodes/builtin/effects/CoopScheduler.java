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
 * This is a SIMPLE cooperative scheduler that:
 * - Queues tasks instead of executing immediately
 * - Runs tasks when await is called on incomplete promises
 * - Provides deterministic, interleaved execution
 *
 * Key design: No continuation capture within scheduler.
 * Tasks run to completion or until they call await.
 * When await is called, scheduler runs other tasks until the awaited promise completes.
 *
 * This is simpler than full continuation-based scheduling but provides:
 * - Task interleaving at await points
 * - Deterministic execution order
 * - Foundation for workflow serialization (tasks are explicit)
 */
public final class CoopScheduler {

    // Ready queue - tasks waiting to execute
    private final Deque<CoopTask> readyQueue = new ArrayDeque<>();

    // All promises by ID
    private final Map<Long, CoopPromise> promises = new HashMap<>();

    // Promise ID counter
    private long nextPromiseId = 0;

    // Scheduler ID for serialization
    private final UUID id = UUID.randomUUID();

    // Track if we're currently running a task (to detect re-entry)
    private boolean isRunning = false;

    /**
     * A task in the scheduler - wraps function + promise.
     */
    public static final class CoopTask {
        final long promiseId;
        final MezzeFunction function;
        final CoopPromise promise;

        CoopTask(long promiseId, MezzeFunction function, CoopPromise promise) {
            this.promiseId = promiseId;
            this.function = function;
            this.promise = promise;
        }
    }

    /**
     * A promise in the scheduler - tracks completion.
     */
    public static final class CoopPromise {
        final long id;
        Object result;
        boolean completed = false;
        Throwable error;

        CoopPromise(long id) {
            this.id = id;
        }

        public boolean isCompleted() {
            return completed;
        }

        public Object getResult() {
            if (error != null) {
                throw new RuntimeException("Task failed: " + error.getMessage(), error);
            }
            return result;
        }

        void complete(Object value) {
            this.result = value;
            this.completed = true;
        }

        void fail(Throwable t) {
            this.error = t;
            this.completed = true;
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
    public CoopPromise spawn(MezzeFunction f) {
        long promiseId = nextPromiseId++;
        CoopPromise promise = new CoopPromise(promiseId);
        CoopTask task = new CoopTask(promiseId, f, promise);

        promises.put(promiseId, promise);
        readyQueue.addLast(task);

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
    public Object await(CoopPromise promise, IndirectCallNode callNode) {
        // Fast path: already complete
        if (promise.isCompleted()) {
            return promise.getResult();
        }

        // Run scheduler until this promise completes
        runUntil(promise, callNode);

        // Now should be complete
        if (!promise.isCompleted()) {
            throw new RuntimeException("Deadlock: promise " + promise.id +
                " never completed (ready queue empty: " + readyQueue.isEmpty() + ")");
        }

        return promise.getResult();
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
    public void runUntil(CoopPromise target, IndirectCallNode callNode) {
        while (!target.isCompleted() && !readyQueue.isEmpty()) {
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
            CoopPromise promise = (CoopPromise) p;
            if (promise.isCompleted()) {
                return promise.getResult();
            }
        }

        // Run until any promise completes
        while (true) {
            // Check if any complete now
            for (Object p : promiseArray) {
                CoopPromise promise = (CoopPromise) p;
                if (promise.isCompleted()) {
                    return promise.getResult();
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
            CoopPromise promise = (CoopPromise) promiseArray[i];
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

    public boolean isDone(CoopPromise promise) {
        return promise.isCompleted();
    }

    public int pendingCount() {
        return readyQueue.size();
    }

    private static Object[] appendArg(Object[] closure, Object arg) {
        Object[] result = new Object[closure.length + 1];
        System.arraycopy(closure, 0, result, 0, closure.length);
        result[closure.length] = arg;
        return result;
    }

    // ========================================================================
    // Serialization (stub - full implementation in Task #6)
    // ========================================================================

    @TruffleBoundary
    public byte[] serialize() {
        // TODO: Implement full serialization
        return new byte[0];
    }

    @TruffleBoundary
    public static CoopScheduler deserialize(byte[] data) {
        // TODO: Implement full deserialization
        return new CoopScheduler();
    }
}
