package com.mezze.interpreter.nodes.continuation;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleContext;
import com.oracle.truffle.api.TruffleLanguage;
import com.mezze.interpreter.runtime.MezzeContext;
import com.mezze.interpreter.runtime.MezzeLanguage;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Pure-Java {@link ContinuationBackend} that suspends the running body
 * by parking its thread on a lock/condition pair instead of yielding a
 * {@code jdk.internal.vm.Continuation}. Introduced by Path C Phase 2.1
 * so effect-carrying fibers can execute without depending on Loom.
 *
 * <h2>Scope and known limitations</h2>
 *
 * <p><b>This is a scaffolding backend, not a drop-in Loom replacement.</b>
 * The body runs on a dedicated worker thread; the current design ferries
 * the polyglot context and the effect-handler chain across the run/yield
 * boundary, but does not (yet) mirror {@code EffectFiber.CURRENT} and
 * {@code DISPATCH} correctly for the driver's own dispatch loop.
 *
 * <p>Consequence: with {@code MEZZE_CPS=1}, unit-level tests here pass
 * (see {@code CpsContinuationBackendTest}, {@code EffectFiberCpsTest}),
 * but the full effect-integration suite (LazyEffectsTest et al.) still
 * fails — the driver's handler-dispatch logic assumes both fiber and
 * handler live on the same thread. Making it work end-to-end needs the
 * real CPS IR transform (Phases 1.5-1.6) so the driver can stop needing
 * a fiber at all. Committing this backend keeps the seam wired so that
 * transform's PR isn't blocked on backend plumbing.
 *
 * <h2>Semantic contract</h2>
 *
 * <p>Same one-shot behaviour as {@link LoomContinuationBackend}: the
 * body's {@link Runnable#run()} may block at most once via
 * {@link #yield()}. When yield returns to the body, the body has been
 * "resumed" — free to run to completion or block again.
 *
 * <h2>Threading model</h2>
 *
 * <p>Unlike Loom's stack-switching continuation, this backend runs the
 * body on a dedicated worker thread. {@link #run()} unparks the worker
 * and blocks the caller until the worker either completes or yields;
 * {@link #yield()} parks the worker and returns to whoever called
 * {@link #run()}. Wall-clock cost per suspend is one lock hand-off in
 * each direction, plus condition signalling — a couple hundred nanos
 * warm, dominated by the fixed lock cost.
 *
 * <p>The worker thread lives for the lifetime of the fiber, hence
 * {@link #close()} must be called to release it. The Path C Phase 3
 * plan replaces this MVP with a lock-free scheduler that runs the body
 * inline on the caller's stack; that removes the extra thread and the
 * lock cost.
 *
 * <p>Not thread-safe. A single instance is owned by one fiber; the
 * driver never concurrently calls {@link #run()} and {@link #yield()}.
 */
public final class CpsContinuationBackend implements ContinuationBackend {

    private final Thread worker;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition workerCanRun = lock.newCondition();
    private final Condition callerCanReturn = lock.newCondition();

    /**
     * States describing which side owns execution.
     * <ul>
     *   <li>{@code IDLE}     — worker parked, caller not blocked. Initial
     *       state; also the state after each yield or completion.</li>
     *   <li>{@code RUNNING}  — worker running the body, caller blocked
     *       inside {@link #run()}.</li>
     *   <li>{@code DONE}     — body has run to completion. Further
     *       {@link #run()} calls throw.</li>
     * </ul>
     */
    private enum State { IDLE, RUNNING, DONE }
    private volatile State state = State.IDLE;

    /**
     * Set to true by {@link #run()} to signal the worker to enter the
     * body; cleared by the worker after entering. Prevents spurious
     * wakeups from misinterpreting a stale signal.
     */
    private boolean runSignal = false;
    /**
     * Set to true by {@link #yield()} or by the worker at completion to
     * signal the caller inside {@link #run()} that ownership has flipped
     * back; cleared by the caller.
     */
    private boolean returnSignal = false;

    /**
     * If the body throws, we stash the throwable and re-throw it on the
     * caller thread from the next {@link #run()} return. Otherwise the
     * exception is lost inside the worker.
     */
    private Throwable bodyError;

    /**
     * Thread-local snapshot ferried across the run/yield boundary.
     * Set by the caller thread before signalling the worker, applied by
     * the worker before entering the body; on yield the worker snapshots
     * back into these fields so the caller can restore anything the body
     * mutated. The set is the same one EffectFiber consults on the hot
     * suspend/resume path — HANDLER_CHAIN, CURRENT, DISPATCH — plus the
     * dispatch-only fiber pool used by chain-dispatch.
     */
    private HandlerChainEntry[] chainSnapshot = EffectFiber.snapshotHandlerChainEntries();
    private EffectFiber currentSnapshot;
    private EffectFiber dispatchSnapshot;

    private void captureCallerThreadLocals() {
        chainSnapshot = EffectFiber.snapshotHandlerChainEntries();
        currentSnapshot = EffectFiber.current();
        dispatchSnapshot = EffectFiber.dispatching();
    }

    private void applyOnWorker() {
        EffectFiber.clearHandlerChain();
        if (chainSnapshot.length > 0) {
            EffectFiber.installHandlerChainEntries(chainSnapshot);
        }
    }

    private void snapshotFromWorker() {
        chainSnapshot = EffectFiber.snapshotHandlerChainEntries();
    }

    private void restoreOnCaller(HandlerChainEntry[] priorCallerChain) {
        EffectFiber.clearHandlerChain();
        if (priorCallerChain.length > 0) {
            EffectFiber.installHandlerChainEntries(priorCallerChain);
        }
    }

    /**
     * Captured on construction (on the caller thread that IS inside the
     * polyglot context). The worker thread enters and leaves this context
     * so Truffle nodes it drives — which read language/context via
     * {@code TruffleLanguage.ContextReference.get(node)} — see a valid
     * context and don't blow up with "no polyglot context entered".
     */
    private final TruffleContext truffleContext;

    @TruffleBoundary
    public CpsContinuationBackend(String name, Runnable body) {
        this.truffleContext = captureCurrentContext();
        this.worker = new Thread(() -> workerLoop(body), "cps-fiber-" + name);
        this.worker.setDaemon(true);
        this.worker.start();
    }

    /**
     * Grab the current polyglot context so the worker thread can enter
     * it. Called on the constructor thread, which by construction has
     * entered the context (otherwise it couldn't be calling into
     * language-level code that instantiates an EffectFiber). Returns
     * null if we can't find one — the worker will just skip enter/leave
     * and any real Truffle work will still crash, but the bookkeeping-
     * only unit tests keep working.
     */
    @TruffleBoundary
    private static TruffleContext captureCurrentContext() {
        try {
            MezzeContext ctx = MezzeContext.get(null);
            if (ctx == null) return null;
            TruffleLanguage.Env env = ctx.getEnv();
            return env == null ? null : env.getContext();
        } catch (Throwable ignore) {
            return null;
        }
    }

    private void workerLoop(Runnable body) {
        Object enterToken = null;
        try {
            if (truffleContext != null) enterToken = truffleContext.enter(null);
            try {
                waitUntilRunSignal();
                applyOnWorker();
                try {
                    body.run();
                } catch (Throwable t) {
                    bodyError = t;
                } finally {
                    snapshotFromWorker();
                    signalCallerReturnAndMarkDone();
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        } finally {
            if (enterToken != null) {
                try { truffleContext.leave(null, enterToken); } catch (Throwable ignore) {}
            }
        }
    }

    private void waitUntilRunSignal() throws InterruptedException {
        lock.lock();
        try {
            while (!runSignal) workerCanRun.await();
            runSignal = false;
            state = State.RUNNING;
        } finally {
            lock.unlock();
        }
    }

    private void signalCallerReturnAndMarkDone() {
        lock.lock();
        try {
            state = State.DONE;
            returnSignal = true;
            callerCanReturn.signalAll();
        } finally {
            lock.unlock();
        }
    }

    @Override
    @TruffleBoundary
    public void run() {
        if (state == State.DONE) {
            throw new IllegalStateException("CpsContinuationBackend already done");
        }
        // Snapshot the caller's handler chain BEFORE the worker starts —
        // it may have mutated between the last yield and this run. The
        // worker's local chain becomes the union, so anything the body
        // pushed is preserved across the yield.
        HandlerChainEntry[] priorCallerChain = EffectFiber.snapshotHandlerChainEntries();
        chainSnapshot = priorCallerChain;
        lock.lock();
        try {
            runSignal = true;
            workerCanRun.signalAll();
            while (!returnSignal) {
                try { callerCanReturn.await(); }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(ie);
                }
            }
            returnSignal = false;
            if (state != State.DONE) state = State.IDLE;
        } finally {
            lock.unlock();
        }
        // The worker may have mutated its chain (pushed/popped handlers
        // inside the body). Return the caller's chain to the same state
        // it was in when we snapshotted — the driver takes care of any
        // reconciliation via truncateHandlerChainTo.
        restoreOnCaller(priorCallerChain);
        if (bodyError != null) {
            Throwable t = bodyError;
            bodyError = null;
            if (t instanceof RuntimeException re) throw re;
            if (t instanceof Error e) throw e;
            throw new RuntimeException(t);
        }
    }

    @Override
    @TruffleBoundary
    public boolean yield() {
        // Called from the worker thread inside body.run(). Hand control
        // back to whoever called run().
        if (Thread.currentThread() != worker) {
            throw new IllegalStateException(
                "CpsContinuationBackend.yield() must be called from the worker thread");
        }
        // Snapshot the worker's chain BEFORE handing back so the caller
        // sees any handlers pushed by the body.
        snapshotFromWorker();
        lock.lock();
        try {
            state = State.IDLE;
            returnSignal = true;
            callerCanReturn.signalAll();
            while (!runSignal) {
                try { workerCanRun.await(); }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            runSignal = false;
            state = State.RUNNING;
        } finally {
            lock.unlock();
        }
        // On resume, adopt the caller's (possibly updated) chain — the
        // driver may have installed a fresh handler in the interim.
        applyOnWorker();
        return true;
    }

    @Override
    public boolean isDone() {
        return state == State.DONE;
    }

    @Override
    @TruffleBoundary
    public void close() {
        // If the worker is still alive but the driver has abandoned this
        // fiber, interrupt it so the thread exits.
        if (worker.isAlive() && state != State.DONE) {
            worker.interrupt();
        }
    }
}
