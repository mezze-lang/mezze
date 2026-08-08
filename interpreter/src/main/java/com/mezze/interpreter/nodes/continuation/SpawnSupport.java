package com.mezze.interpreter.nodes.continuation;

import com.mezze.interpreter.nodes.builtin.EffectHandlerDriver.TaskUnhandledOpException;

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
    public static void runJoinTask(HandlerChainEntry[] chain, CompletableFuture<Object> future, Supplier<Object> body) {
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
            // Chain-dispatch is the only path back to the outer perform.
            // If a chain is installed on this thread (e.g. we're inside a
            // spawned task that inherited the parent's chain), rethrow the
            // TUE so the outer runPromiseTask/runBatchTask stashes it one
            // level up.
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
        HandlerChainEntry[] chain,
        AtomicReference<TaskUnhandledOpException> opSink,
        AtomicReference<Throwable> errSink,
        Runnable body
    ) {
        runBatchTask(chain, opSink, errSink, body, false);
    }

    /**
     * Variant of {@link #runBatchTask(HandlerChainEntry[], AtomicReference,
     * AtomicReference, Runnable)} that skips the redundant chain install
     * when the caller guarantees the chain is already on this thread.
     *
     * <p>This is the case for {@code Parallel.join}'s left side: the
     * chain snapshot was taken on the calling thread and left-side body
     * runs on that same thread, so the chain we would install matches the
     * one already installed. Push+pop N times is pure overhead
     * (~200-400 ns per join). Right side must still install because it
     * runs on a fresh FJP worker with an empty chain. #62.
     *
     * @param sameThreadChain true iff the caller guarantees the current
     *   thread's chain already matches {@code chain}. Wrong value =
     *   silent misroute; use with care.
     */
    public static void runBatchTask(
        HandlerChainEntry[] chain,
        AtomicReference<TaskUnhandledOpException> opSink,
        AtomicReference<Throwable> errSink,
        Runnable body,
        boolean sameThreadChain
    ) {
        try {
            if (sameThreadChain) {
                body.run();
            } else {
                EffectFiber.runWithHandlerChain(chain, body);
            }
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
        return propagateBatchFailure(opSink, errSink, opLabel, null);
    }

    /**
     * Overload that also accepts an {@link com.oracle.truffle.api.nodes.IndirectCallNode}
     * for chain-based fallback dispatch when no active fiber is present.
     *
     * <p>This is the case that Parallel primitives hit on the main thread:
     * inside {@code perform ExceptionToResult in perform Parallel in do ...},
     * the main thread has handler chain entries but no active
     * {@link EffectFiber} (chain-dispatch is the fast path; the fiber only
     * exists during a real suspend cycle). When a batch task's body raises
     * an unhandled op, we need to route it to the chain — same fallback that
     * {@link com.mezze.interpreter.nodes.effect.OpCallNode} uses in its
     * "no fiber but chain exists" branch. Without this, the op leaks as a
     * {@code MezzeSuspend} to user code even though a handler for it is
     * clearly on the chain.
     *
     * <p>Note: {@link com.mezze.interpreter.nodes.builtin.EffectHandlerDriver.AbortPerformException}
     * from the chain handler propagates up unchanged. The outer {@code perform}
     * driver catches it and completes with the abort value — exactly what
     * we want when {@code Exception.throw} runs under {@code ExceptionToResult}.
     */
    public static Object propagateBatchFailure(
        AtomicReference<TaskUnhandledOpException> opSink,
        AtomicReference<Throwable> errSink,
        String opLabel,
        com.oracle.truffle.api.nodes.IndirectCallNode callNode
    ) {
        TaskUnhandledOpException tue = opSink.get();
        if (tue != null) {
            // Chain-dispatch is the only path back to the outer perform.
            // If a chain is installed on this thread (the classic case for
            // chain-dispatch inside nested performs), dispatch directly
            // through it; if the handler aborts, AbortPerformException
            // propagates up to the outer perform driver which completes
            // with the abort value.
            if (callNode != null) {
                HandlerChainEntry[] chain = EffectFiber.snapshotHandlerChainEntries();
                if (chain.length > 0) {
                    Object result = dispatchOpOnChainWithAbortPropagation(
                        chain,
                        tue.opCall.opName(),
                        tue.opCall.args(),
                        tue.opCall.effectHash(),
                        callNode
                    );
                    if (result != NO_MATCH) {
                        return result;
                    }
                }
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

    private static final Object NO_MATCH = new Object();

    /**
     * Chain-walk dispatch that lets
     * {@link com.mezze.interpreter.nodes.builtin.EffectHandlerDriver.AbortPerformException}
     * propagate. Similar to {@code OpCallNode.dispatchOpOnChain}, but
     * without the "wrap abort as TaskUnhandledOpException" step —
     * we're on the main thread inside the outer {@code perform}, so
     * the abort should unwind up to that perform directly.
     */
    private static Object dispatchOpOnChainWithAbortPropagation(
        HandlerChainEntry[] chain,
        String opName,
        Object args,
        com.mezze.language.typedast.Hash effectHash,
        com.oracle.truffle.api.nodes.IndirectCallNode callNode
    ) {
        for (int i = 0; i < chain.length; i++) {
            com.mezze.language.typedast.Hash chainEffect = chain[i].effectHash();
            // STRICT filter — null hash on either side is the #246/#55
            // silent-misroute setup, not a legitimate fallback. Skip and
            // warn once so the leak site can be identified.
            if (chainEffect == null || effectHash == null) {
                com.mezze.interpreter.nodes.builtin.EffectHandlerDriver.warnNullEffectHash(
                    chainEffect, effectHash, opName,
                    "SpawnSupport.dispatchOpOnChainWithAbortPropagation");
                continue;
            }
            if (!chainEffect.equals(effectHash)) {
                continue;
            }
            Object f = chain[i].handler().getField(opName);
            if (f instanceof com.mezze.interpreter.values.MezzeFunction op) {
                EffectFiber dispatchFiber = EffectFiber.acquireDispatchOnly();
                com.mezze.interpreter.values.MezzeFunction resumeFn =
                    com.mezze.interpreter.nodes.effect.OpCallNode.makeChainResumeFunction();
                com.mezze.interpreter.values.MezzeFunction abortFn =
                    com.mezze.interpreter.nodes.builtin.EffectHandlerDriver.makeAbortFunction();
                dispatchFiber.pushEffectHandles(resumeFn, abortFn);

                Object[] closure = op.getClosureValues();
                Object[] callArgs = new Object[closure.length + 1];
                System.arraycopy(closure, 0, callArgs, 0, closure.length);
                callArgs[closure.length] = args;

                EffectFiber prev = dispatchFiber.enterDispatch();
                try {
                    return callNode.call(op.getCallTarget(), callArgs);
                } catch (com.mezze.interpreter.nodes.builtin.EffectHandlerDriver.AbortPerformException abort) {
                    //   - Inside a BDSL Handle @Operation (registered on
                    //     D8_HANDLE_STACK): rethrow so the enclosing Handle
                    //     catches abort and finishes the perform with
                    //     abort.value.
                    //   - Otherwise: convert to TaskUnhandledOpException so
                    //     the surrounding batch/task machinery stashes it
                    //     for parent await re-issue.
                    // Diverging from BdslYieldDispatch here would let abort
                    // escape as TUE past a Handle boundary.
                    if (EffectFiber.insideBdslHandle()) {
                        throw abort;
                    }
                    throw new com.mezze.interpreter.nodes.builtin.EffectHandlerDriver.TaskUnhandledOpException(
                        new OpCall(opName, args, effectHash));
                } finally {
                    EffectFiber.exitDispatch(prev);
                    dispatchFiber.popEffectHandles();
                    EffectFiber.releaseDispatchOnly(dispatchFiber);
                }
            }
        }
        return NO_MATCH;
    }
}
