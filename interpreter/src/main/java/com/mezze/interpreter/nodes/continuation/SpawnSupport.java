package com.mezze.interpreter.nodes.continuation;

import com.mezze.interpreter.nodes.builtin.EffectHandlerDriver.TaskUnhandledOpException;
import com.mezze.interpreter.values.MezzeRecord;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Shared task-body wrappers for native operations that spawn off the
 * current thread.
 *
 * <p>Every spawn site needs to do the same dance: snapshot the parent's
 * effect-handler chain, install it on the spawned thread before running
 * the body, route any failure (regular throwable or unhandled effect-op
 * propagated past the chain) somewhere the spawn-site's caller can act
 * on. Three patterns cover the surface area:
 *
 * <ul>
 *   <li><b>Promise-returning spawn</b> — body's result completes a
 *     promise; unhandled-op stashes on the promise so {@code await}
 *     re-issues it on the parent fiber. See callers in
 *     {@code AsyncNatives.loomSpawn} et al.</li>
 *   <li><b>Join-style spawn</b> — body's result completes a
 *     {@link java.util.concurrent.CompletableFuture}; unhandled-op
 *     surfaces as the future's failure cause. Used by {@code loomJoin}
 *     and {@code osJoin}.</li>
 *   <li><b>Batch spawn</b> — body writes directly into a shared array
 *     and the main thread joins as a barrier. Failures (op or
 *     throwable) accumulate in {@link AtomicReference} sinks; the main
 *     thread calls {@link #propagateBatchFailure} after the join to
 *     re-raise on its own fiber.</li>
 * </ul>
 *
 * <p>Promise- and future-shaped routing is so coupled to the specific
 * datatype (MezzePromise vs. CompletableFuture) that those helpers live
 * with their callers in {@code AsyncNatives}. Batch-style routing is
 * data-shape-independent and lives here so {@code ConcurrencyNatives}'
 * parallel primitives can reuse it without depending on
 * {@code AsyncNatives}.
 */
public final class SpawnSupport {

    private SpawnSupport() {}

    /**
     * Body for a join-style spawn: install {@code chain}, run
     * {@code body}, complete {@code future} with the result; any
     * throwable (including {@link TaskUnhandledOpException}) fails the
     * future. The awaiter on the main thread sees the failure via
     * {@code future.get()}'s {@code ExecutionException} cause.
     */
    public static void runJoinTask(MezzeRecord[] chain, CompletableFuture<Object> future, Supplier<Object> body) {
        try {
            EffectFiber.runWithHandlerChain(chain, () -> future.complete(body.get()));
        } catch (Throwable e) {
            future.completeExceptionally(e);
        }
    }

    /**
     * Unwrap an {@link ExecutionException} produced by
     * {@code CompletableFuture.get()}. If the cause is a
     * {@link TaskUnhandledOpException}, re-issue the op-call on the
     * current fiber (or rethrow if this thread is itself a spawned task
     * with chain-dispatch). Otherwise rethrow the cause wrapped.
     *
     * @param opLabel short name used in error messages
     */
    public static Object propagateJoinFailure(ExecutionException e, String opLabel) {
        Throwable cause = e.getCause();
        if (cause instanceof TaskUnhandledOpException tue) {
            EffectFiber parent = EffectFiber.current();
            if (parent != null) {
                return parent.suspend(tue.opCall);
            }
            if (EffectFiber.handlerChainDepth() > 0) {
                throw new TaskUnhandledOpException(tue.opCall);
            }
            throw new RuntimeException(
                opLabel + " task raised effect operation '"
                + tue.opCall.opName() + "' with no handler available.");
        }
        if (cause instanceof RuntimeException re) throw re;
        throw new RuntimeException(opLabel + " failed: " + (cause != null ? cause.getMessage() : "null"), cause);
    }

    /**
     * Body for a parallel-batch spawn. Installs {@code chain} on the
     * current thread, runs {@code body}, captures any failure into the
     * corresponding sink. {@link TaskUnhandledOpException} (handler in
     * the chain called {@code abort}, op propagated past the chain) is
     * preferred over a regular throwable when both happen — the op-call
     * carries information the parent driver can act on.
     */
    public static void runBatchTask(
        MezzeRecord[] chain,
        AtomicReference<TaskUnhandledOpException> opSink,
        AtomicReference<Throwable> errSink,
        Runnable body
    ) {
        try {
            EffectFiber.runWithHandlerChain(chain, body);
        } catch (TaskUnhandledOpException tue) {
            opSink.compareAndSet(null, tue);
        } catch (Throwable e) {
            errSink.compareAndSet(null, e);
        }
    }

    /**
     * After all batches join, raise the first failure (if any). Effect-
     * ops re-issue via {@code parent.suspend(opCall)} so an enclosing
     * {@code perform} can dispatch them; regular throwables rethrow
     * wrapped. Returns normally with {@code null} if both sinks are
     * empty. May yield the current fiber if a re-issued op-call suspends.
     *
     * @param opLabel short name of the parallel op, used in error
     *                messages (e.g. {@code "parallel map"})
     */
    public static Object propagateBatchFailure(
        AtomicReference<TaskUnhandledOpException> opSink,
        AtomicReference<Throwable> errSink,
        String opLabel
    ) {
        TaskUnhandledOpException tue = opSink.get();
        if (tue != null) {
            EffectFiber parent = EffectFiber.current();
            if (parent != null) {
                return parent.suspend(tue.opCall);
            }
            throw new RuntimeException(
                opLabel + " task raised effect operation '"
                + tue.opCall.opName() + "' with no handler available.");
        }
        Throwable err = errSink.get();
        if (err != null) {
            if (err instanceof RuntimeException re) throw re;
            throw new RuntimeException(opLabel + " task failed: " + err.getMessage(), err);
        }
        return null;
    }
}
