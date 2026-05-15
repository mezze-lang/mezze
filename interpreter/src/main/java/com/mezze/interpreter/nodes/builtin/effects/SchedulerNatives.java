package com.mezze.interpreter.nodes.builtin.effects;

import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.datastructures.seq.MezzeSeq;
import com.mezze.interpreter.values.MezzeUnit;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.IndirectCallNode;

/**
 * Native functions for cooperative scheduler.
 *
 * These back the std/control/scheduler.mz module.
 */
public final class SchedulerNatives {

    private SchedulerNatives() {}

    /**
     * Create a new scheduler.
     */
    @TruffleBoundary
    public static CoopScheduler schedulerNew() {
        return new CoopScheduler();
    }

    /**
     * Spawn a task on the scheduler.
     */
    @TruffleBoundary
    public static CoopScheduler.CoopPromise schedulerSpawn(MezzeRecord args, IndirectCallNode callNode) {
        CoopScheduler scheduler = getScheduler(args, "sched");
        MezzeFunction f = getFunction(args, "f");
        return scheduler.spawn(f);
    }

    /**
     * Await a promise - runs scheduler until promise completes.
     */
    @TruffleBoundary
    public static Object schedulerAwait(MezzeRecord args, IndirectCallNode callNode) {
        CoopScheduler scheduler = getScheduler(args, "sched");
        CoopScheduler.CoopPromise promise = getPromise(args, "p");
        return scheduler.await(promise, callNode);
    }

    /**
     * Drain all pending tasks.
     */
    @TruffleBoundary
    public static Object schedulerDrain(MezzeRecord args, IndirectCallNode callNode) {
        CoopScheduler scheduler = getScheduler(args, "sched");
        scheduler.drain(callNode);
        return MezzeUnit.INSTANCE;
    }

    /**
     * Check if promise is done.
     */
    public static boolean schedulerIsDone(MezzeRecord args) {
        CoopScheduler.CoopPromise promise = getPromise(args, "p");
        return promise.isCompleted();
    }

    /**
     * Get pending task count.
     */
    public static long schedulerPendingCount(MezzeRecord args) {
        CoopScheduler scheduler = getScheduler(args, "sched");
        return scheduler.pendingCount();
    }

    /**
     * Race multiple promises.
     */
    @TruffleBoundary
    public static Object schedulerRace(MezzeRecord args, IndirectCallNode callNode) {
        CoopScheduler scheduler = getScheduler(args, "sched");
        Object[] promises = getArray(args, "ps");
        return scheduler.race(promises, callNode);
    }

    /**
     * Wait for all promises.
     */
    @TruffleBoundary
    public static MezzeSeq schedulerAll(MezzeRecord args, IndirectCallNode callNode) {
        CoopScheduler scheduler = getScheduler(args, "sched");
        Object[] promises = getArray(args, "ps");
        return scheduler.all(promises, callNode);
    }

    /**
     * Sleep (cooperative yield).
     */
    @TruffleBoundary
    public static Object schedulerSleep(MezzeRecord args, IndirectCallNode callNode) {
        CoopScheduler scheduler = getScheduler(args, "sched");
        long ms = getLong(args, "ms");
        scheduler.sleep(ms, callNode);
        return MezzeUnit.INSTANCE;
    }

    /**
     * Park: run ready tasks until predicate returns True. Deadlocks if
     * the ready queue empties before the predicate flips.
     */
    @TruffleBoundary
    public static MezzeUnit schedulerParkUntil(MezzeRecord args, IndirectCallNode callNode) {
        CoopScheduler scheduler = getScheduler(args, "sched");
        MezzeFunction predicate = getFunction(args, "predicate");
        scheduler.parkUntil(predicate, callNode);
        return MezzeUnit.INSTANCE;
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private static CoopScheduler getScheduler(MezzeRecord args, String field) {
        Object val = args.getField(field);
        if (val instanceof CoopScheduler s) {
            return s;
        }
        throw new RuntimeException("Expected CoopScheduler for field '" + field + "', got: " +
            (val == null ? "null" : val.getClass().getName()));
    }

    private static CoopScheduler.CoopPromise getPromise(MezzeRecord args, String field) {
        Object val = args.getField(field);
        if (val instanceof CoopScheduler.CoopPromise p) {
            return p;
        }
        throw new RuntimeException("Expected CoopPromise for field '" + field + "', got: " +
            (val == null ? "null" : val.getClass().getName()) + ". Args: " + args);
    }

    private static MezzeFunction getFunction(MezzeRecord args, String field) {
        Object val = args.getField(field);
        if (val instanceof MezzeFunction f) {
            return f;
        }
        throw new RuntimeException("Expected MezzeFunction for field '" + field + "', got: " +
            (val == null ? "null" : val.getClass().getName()));
    }

    private static Object[] getArray(MezzeRecord args, String field) {
        Object val = args.getField(field);
        if (val instanceof MezzeSeq seq) {
            return seq.toArray();
        }
        if (val instanceof Object[] arr) {
            return arr;
        }
        throw new RuntimeException("Expected array for field '" + field + "', got: " +
            (val == null ? "null" : val.getClass().getName()));
    }

    private static long getLong(MezzeRecord args, String field) {
        Object val = args.getField(field);
        if (val instanceof Number n) {
            return n.longValue();
        }
        throw new RuntimeException("Expected number for field '" + field + "', got: " +
            (val == null ? "null" : val.getClass().getName()));
    }
}
