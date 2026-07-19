package com.mezze.interpreter.nodes.builtin.concurrency;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.nodes.builtin.EffectHandlerDriver.TaskUnhandledOpException;
import com.mezze.interpreter.nodes.continuation.EffectFiber;
import com.mezze.interpreter.nodes.continuation.HandlerChainEntry;
import com.mezze.interpreter.nodes.continuation.SpawnSupport;
import com.mezze.interpreter.runtime.MezzeContext;
import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeUnit;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleContext;
import com.oracle.truffle.api.TruffleLanguage.Env;
import com.oracle.truffle.api.nodes.IndirectCallNode;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.RecursiveTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Native primitives for the Parallel effect. The ability declares
 * a single op — {@code join} — and every derived data-parallel
 * shape (map, reduce, filter, find, any, all, ...) lives as a
 * threshold-based recursive helper in the Mezze stdlib on top of it.
 *
 * <p>Two backends:
 * <ul>
 *   <li>{@code fjpool*} — {@link ForkJoinPool} + {@link RecursiveTask}
 *       for CPU-bound work with irregular subtask sizes. Same shape
 *       as Rust's Rayon: cheap fork/join (~500ns) plus work stealing.
 *   <li>{@code vthread*} — one virtual thread per side. For I/O-bound
 *       work where each subtask can park without blocking a carrier.
 *       Optional concurrency cap via a semaphore.
 * </ul>
 *
 * <p>Both backends respect the {@code initialize_scope}/{@code finalize_scope}
 * convention: the driver invokes them at the top and bottom of every
 * {@code perform ForkJoinParallel in ...} / {@code perform VirtualParallel in ...}
 * block, so per-scope state (the pool, the semaphore) has a clean lifecycle.
 */
public final class ParallelNatives {

    private ParallelNatives() {}

    // ==================== Per-scope state ====================
    //
    // Both backends stash their state in a ThreadLocal so join() can
    // find it without threading through Mezze source. This is the same
    // pattern Async uses for its promise tracker.

    /** Current ForkJoinPool for a `perform ForkJoinParallel` scope. */
    private static final ThreadLocal<ForkJoinPool> CURRENT_POOL = new ThreadLocal<>();

    /** Backpressure semaphore for VirtualParallelWith { max_concurrent }.
     *  Null means unbounded (VirtualParallel default). */
    private static final ThreadLocal<Semaphore> VTHREAD_SEMAPHORE = new ThreadLocal<>();

    // ==================== ForkJoinParallel ====================
    //
    // We build a private ForkJoinPool per scope whose workers subclass
    // ForkJoinWorkerThread with a Truffle-context enter/leave lifecycle.
    // On worker construction we snapshot the caller's TruffleContext;
    // onStart the worker enters that context; onTermination it leaves.
    // This lets pool.invoke(new RecursiveTask { compute { ... callNode.call ... } })
    // run safely on ANY worker — the AST sharing guard sees the correct
    // context for whichever worker picks up the task.
    //
    // We can't use ForkJoinPool.commonPool() this way (JDK singleton,
    // no custom factory), so ForkJoinParallel (no parallelism arg) sizes
    // the private pool to Runtime.availableProcessors(). That's the
    // same default Rayon uses.

    /**
     * ForkJoinWorkerThread subclass that enters a Truffle polyglot
     * context in onStart and leaves it in onTermination. This is how
     * FJP workers become callNode.call-safe — every task the pool
     * dispatches runs on a thread that's currently "inside" the context.
     */
    private static final class TruffleFJPWorker extends ForkJoinWorkerThread {
        private final TruffleContext context;
        private Object enterToken;

        TruffleFJPWorker(ForkJoinPool pool, TruffleContext context) {
            super(pool);
            this.context = context;
        }

        @Override
        protected void onStart() {
            super.onStart();
            enterToken = context.enter(null);
        }

        @Override
        protected void onTermination(Throwable exception) {
            try {
                if (enterToken != null) {
                    context.leave(null, enterToken);
                    enterToken = null;
                }
            } finally {
                super.onTermination(exception);
            }
        }
    }

    /**
     * Enter a ForkJoinParallel scope. Allocates a fresh per-scope
     * ForkJoinPool with Truffle-aware workers, so pool.invoke on nested
     * joins hits the real work-stealing fast path. Size: caller-provided
     * {@code parallelism}, or Runtime.availableProcessors() if 0/negative.
     *
     * <p>Per-scope pool ownership avoids the classic ForkJoinPool nested-
     * deadlock trap: if one shared pool were reused across many
     * concurrent scopes, an outer scope's blocking {@code pool.invoke}
     * could exhaust workers with nested tasks that recursively wait on
     * further tasks in the same pool. Fresh pools mean each scope's
     * invoke-and-wait budget is its own.
     */
    @TruffleBoundary
    public static MezzeUnit fjpoolInit(MezzeRecord args, MezzeNode node) {
        long parallelism = getLongOrZero(args, "parallelism");
        int size = parallelism > 0 ? (int) parallelism : Runtime.getRuntime().availableProcessors();
        Env env = MezzeContext.get(node).getEnv();
        TruffleContext ctx = env.getContext();
        ForkJoinPool pool = new ForkJoinPool(
            size,
            (ForkJoinPool p) -> new TruffleFJPWorker(p, ctx),
            /* uncaughtExceptionHandler */ null,
            /* asyncMode = */ false
        );
        CURRENT_POOL.set(pool);
        return MezzeUnit.INSTANCE;
    }

    /**
     * Exit a ForkJoinParallel scope. Shuts down the pool; worker threads
     * leave the polyglot context in onTermination. Always clears the
     * ThreadLocal to prevent leakage across scopes.
     */
    @TruffleBoundary
    public static MezzeUnit fjpoolFinalize(MezzeRecord args) {
        ForkJoinPool pool = CURRENT_POOL.get();
        if (pool != null) pool.shutdown();
        CURRENT_POOL.remove();
        return MezzeUnit.INSTANCE;
    }

    /**
     * The Parallel.join primitive on ForkJoinPool. Runs {@code l} inline
     * on the calling thread while forking {@code r} to the pool. This
     * is the classic Rayon shape — cheap fork (~200ns queue push), work
     * stealing balances irregular subtasks, and {@code join()} pops the
     * task locally if nobody stole it (so no idle waiting on light work).
     *
     * <p>The handler chain is snapshotted once and shared by both sides,
     * so effects declared outside the scope propagate correctly through
     * the parallel work.
     *
     * <p>If we're not currently on an FJP worker thread (first entry
     * into a Parallel scope), we submit the whole {@code join} as a
     * top-level task via {@code pool.invoke(...)}. Subsequent nested
     * {@code join}s within the same scope then run on worker threads
     * and use the fork/join fast path directly.
     */
    @TruffleBoundary
    public static MezzeRecord fjpoolJoin(MezzeRecord args, IndirectCallNode callNode) {
        MezzeFunction leftFn = getFunction(args, "l");
        MezzeFunction rightFn = getFunction(args, "r");

        HandlerChainEntry[] chain = EffectFiber.snapshotHandlerChainEntries();

        // Already on a pool worker → fast path (real work-stealing fork/join).
        if (Thread.currentThread() instanceof TruffleFJPWorker) {
            return fjpoolJoinInner(leftFn, rightFn, callNode, chain);
        }

        // First entry into a Parallel scope, or called from a non-FJP
        // thread (e.g. inside a spawn callback that itself started a
        // Parallel perform). Submit as a top-level RecursiveTask; the
        // pool's Truffle-aware workers will handle it. pool.invoke
        // blocks the calling thread until done — but the actual work
        // runs on the pool workers, so the AST sharing guard sees the
        // right context.
        ForkJoinPool pool = CURRENT_POOL.get();
        if (pool == null) {
            // Should not happen — initialize_scope always sets the pool.
            // Fall through to running on the calling thread as a safety net.
            return fjpoolJoinInner(leftFn, rightFn, callNode, chain);
        }
        return pool.invoke(new RecursiveTask<MezzeRecord>() {
            @Override
            protected MezzeRecord compute() {
                return fjpoolJoinInner(leftFn, rightFn, callNode, chain);
            }
        });
    }

    private static MezzeRecord fjpoolJoinInner(
        MezzeFunction leftFn,
        MezzeFunction rightFn,
        IndirectCallNode callNode,
        HandlerChainEntry[] chain
    ) {
        AtomicReference<TaskUnhandledOpException> opSink = new AtomicReference<>();
        AtomicReference<Throwable> errSink = new AtomicReference<>();

        // Right side: enqueued to the pool via fork().
        Object[] rightResultHolder = new Object[1];
        ForkJoinTask<Void> rightTask = new RecursiveTask<Void>() {
            @Override
            protected Void compute() {
                SpawnSupport.runBatchTask(chain, opSink, errSink, () ->
                    rightResultHolder[0] = callThunk(rightFn, callNode));
                return null;
            }
        };
        rightTask.fork();

        // Left side: run inline on this worker. Standard Rayon pattern.
        Object[] leftResultHolder = new Object[1];
        SpawnSupport.runBatchTask(chain, opSink, errSink, () ->
            leftResultHolder[0] = callThunk(leftFn, callNode));

        // Wait for right. FJP's join() will pop and run the task locally
        // if nobody stole it yet — no idle blocking on light work.
        rightTask.join();

        SpawnSupport.propagateBatchFailure(opSink, errSink, "parallel join");

        return MezzeRecord.fromFields(
            LR_FIELDS,
            new Object[]{leftResultHolder[0], rightResultHolder[0]}
        );
    }

    private static final String[] LR_FIELDS = new String[]{"l", "r"};

    // ==================== VirtualParallel ====================

    /**
     * Enter a VirtualParallel scope. If {@code max_concurrent} is
     * positive, installs a semaphore with that many permits — every
     * vthread {@code join} sub-task acquires one before running.
     * Zero means unbounded.
     */
    @TruffleBoundary
    public static MezzeUnit vthreadInit(MezzeRecord args) {
        long maxConcurrent = getLongOrZero(args, "max_concurrent");
        if (maxConcurrent > 0) {
            VTHREAD_SEMAPHORE.set(new Semaphore((int) maxConcurrent));
        } else {
            VTHREAD_SEMAPHORE.remove();
        }
        return MezzeUnit.INSTANCE;
    }

    /** Exit a VirtualParallel scope. Clears the semaphore. */
    @TruffleBoundary
    public static MezzeUnit vthreadFinalize(MezzeRecord args) {
        VTHREAD_SEMAPHORE.remove();
        return MezzeUnit.INSTANCE;
    }

    /**
     * The Parallel.join primitive on virtual threads. Spawns one
     * vthread for {@code r}, runs {@code l} on the current thread,
     * then joins. If a concurrency cap is installed, each side
     * acquires a permit before running — provides backpressure for
     * I/O-bound work that would otherwise spawn thousands of vthreads.
     */
    @TruffleBoundary
    public static MezzeRecord vthreadJoin(MezzeRecord args, IndirectCallNode callNode, MezzeNode node) {
        MezzeFunction leftFn = getFunction(args, "l");
        MezzeFunction rightFn = getFunction(args, "r");

        HandlerChainEntry[] chain = EffectFiber.snapshotHandlerChainEntries();
        Semaphore sem = VTHREAD_SEMAPHORE.get();

        AtomicReference<TaskUnhandledOpException> opSink = new AtomicReference<>();
        AtomicReference<Throwable> errSink = new AtomicReference<>();

        // Spawn the right side via Truffle's thread builder so the vthread
        // shares the polyglot context — direct Thread.ofVirtual() would
        // trigger "Invalid sharing of AST nodes" when the vthread calls
        // callNode.
        Env env = MezzeContext.get(node).getEnv();
        Object[] rightResultHolder = new Object[1];
        Thread rightThread = env.newTruffleThreadBuilder(() -> {
            if (sem != null) {
                try { sem.acquire(); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            try {
                SpawnSupport.runBatchTask(chain, opSink, errSink, () ->
                    rightResultHolder[0] = callThunk(rightFn, callNode));
            } finally {
                if (sem != null) sem.release();
            }
        }).virtual(true).build();
        rightThread.start();

        // Left side: run inline on current thread (still under permit if capped).
        Object[] leftResultHolder = new Object[1];
        if (sem != null) {
            try { sem.acquire(); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("VirtualParallel.join interrupted", e);
            }
        }
        try {
            SpawnSupport.runBatchTask(chain, opSink, errSink, () ->
                leftResultHolder[0] = callThunk(leftFn, callNode));
        } finally {
            if (sem != null) sem.release();
        }

        try {
            rightThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("VirtualParallel.join interrupted waiting for right side", e);
        }

        SpawnSupport.propagateBatchFailure(opSink, errSink, "vthread join");

        return MezzeRecord.fromFields(
            LR_FIELDS,
            new Object[]{leftResultHolder[0], rightResultHolder[0]}
        );
    }

    // ==================== Helpers ====================

    /**
     * Call a Mezze thunk ({} -> 'a). Just forwards the closure values;
     * the {} argument is the empty record.
     */
    private static Object callThunk(MezzeFunction f, IndirectCallNode callNode) {
        Object[] closure = f.getClosureValues();
        Object[] callArgs = new Object[closure.length + 1];
        System.arraycopy(closure, 0, callArgs, 0, closure.length);
        callArgs[closure.length] = MezzeRecord.empty();
        return callNode.call(f.getCallTarget(), callArgs);
    }

    private static MezzeFunction getFunction(MezzeRecord args, String fieldName) {
        Object value = args.getField(fieldName);
        if (value instanceof MezzeFunction f) return f;
        throw new RuntimeException("Expected function for field '" + fieldName + "'");
    }

    private static long getLongOrZero(MezzeRecord args, String fieldName) {
        Object value = args.getField(fieldName);
        if (value instanceof Long l) return l;
        if (value instanceof Integer i) return i.longValue();
        return 0L;
    }
}
