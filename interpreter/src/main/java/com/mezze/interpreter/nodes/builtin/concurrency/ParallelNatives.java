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

    /**
     * Minimum chunk size for derived combinators (map, filter, fold, ...).
     * When the input size is at or below this threshold, the combinator
     * stops splitting and runs the leaf inline instead of forking. Default
     * of 1 preserves the pre-#65 behavior (fork until singletons).
     *
     * <p>Set by the {@code With}-shaped impls (ForkJoinParallelWith,
     * VirtualParallelWith). Read by the derived combinators via the
     * {@code __native_parallel_min_chunk_hint} native.
     */
    private static final ThreadLocal<Long> MIN_CHUNK_HINT =
        ThreadLocal.withInitial(() -> 1L);

    // Chain-snapshot cache (#67) was attempted but reverted: the FJP
    // worker's chain state depends on runBatchTask install timing, and
    // reusing the caller's snapshot broke handler dispatch on the fresh
    // worker (MezzeSuspended leaks in "raise inside Parallel" tests).
    // The correct optimization needs finer-grained per-worker
    // installed-chain tracking, which the current runtime doesn't
    // expose cheaply. Filed as future work if perf ever demands it.

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
        // Optional min_chunk hint (#65). Missing field or non-positive
        // value means "always split down to singletons" (pre-#65 default).
        long minChunk = getLongOrZero(args, "min_chunk");
        MIN_CHUNK_HINT.set(minChunk > 0 ? minChunk : 1L);
        return MezzeUnit.INSTANCE;
    }

    /**
     * Exit a ForkJoinParallel scope. Shuts down the pool and waits
     * briefly for workers to terminate — this matters because workers
     * call context.leave() in onTermination, and letting them race
     * with the next scope's context.enter() can wedge under Truffle
     * safepoint handshakes. Bounded wait prevents user code from
     * hanging indefinitely if a worker is stuck.
     */
    @TruffleBoundary
    public static MezzeUnit fjpoolFinalize(MezzeRecord args) {
        ForkJoinPool pool = CURRENT_POOL.get();
        if (pool != null) {
            pool.shutdown();
            try {
                pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        CURRENT_POOL.remove();
        MIN_CHUNK_HINT.remove();
        return MezzeUnit.INSTANCE;
    }

    /**
     * Read the current scope's min_chunk hint. Returns 1 when no scope
     * is active or the scope didn't set a hint — meaning derived
     * combinators split down to singletons. Set by
     * {@code ForkJoinParallelWithChunk} and {@code VirtualParallelWithChunk}
     * impls at scope entry. #65.
     */
    @TruffleBoundary
    public static long minChunkHint(MezzeRecord args) {
        return MIN_CHUNK_HINT.get();
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

        // First entry into a Parallel scope. Submit as a top-level
        // RecursiveTask; a worker will run it under the polyglot context
        // (TruffleFJPWorker.onStart entered the context for us).
        ForkJoinPool pool = CURRENT_POOL.get();
        if (pool == null) {
            // Should not happen — initialize_scope always sets the pool.
            // Fall back to running on the calling thread; the caller's
            // chain is already correct.
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
        // Skip the redundant chain reinstall ONLY when the current
        // thread's chain already matches — the nested-join case, where
        // fjpoolJoinInner was reached from a caller that already
        // installed the chain. For the TOP-LEVEL entry (dispatched via
        // pool.invoke on a fresh FJP worker), the chain is empty and
        // we still need to install. #62.
        boolean chainAlreadyInstalled =
            EffectFiber.handlerChainDepth() == chain.length;
        Object[] leftResultHolder = new Object[1];
        SpawnSupport.runBatchTask(
            chain, opSink, errSink,
            () -> leftResultHolder[0] = callThunk(leftFn, callNode),
            /* sameThreadChain = */ chainAlreadyInstalled);

        // Wait for right. FJP's join() will pop and run the task locally
        // if nobody stole it yet — no idle blocking on light work.
        rightTask.join();

        SpawnSupport.propagateBatchFailure(opSink, errSink, "parallel join", callNode);

        return MezzeRecord.fromFields(
            LR_FIELDS,
            new Object[]{leftResultHolder[0], rightResultHolder[0]}
        );
    }

    private static final String[] LR_FIELDS = new String[]{"l", "r"};

    // ==================== ForkJoinParallel numeric fast paths (#69) ====================
    //
    // parallel::sum / product / count via `Parallel.reduce` walk log₂(N)
    // levels of Parallel.join, each with per-join overhead. Since the
    // underlying seq storage is a contiguous long[], we can instead:
    //  1. slice the array into `parallelism` fixed chunks
    //  2. fork each chunk to an FJP worker that runs a single SIMD reduce
    //  3. combine the partials — one flat join level, not log₂(N)
    //
    // These natives bypass the recursive-split path entirely. They're only
    // eligible for seqs backed by LongSeqStorage (integer sums/products);
    // other storage types fall back to the stdlib recursive impl by
    // returning null so the caller's match arm on None takes over.

    /** Number of chunks to split into. Uses the pool's parallelism level. */
    private static int chunkCountForPool(ForkJoinPool pool, int arrayLen) {
        int p = pool != null ? pool.getParallelism() : Runtime.getRuntime().availableProcessors();
        if (arrayLen < p) return Math.max(1, arrayLen);
        return p;
    }

    /**
     * Parallel SIMD sum over a Seq of Ints. Splits the underlying long[]
     * into {@code parallelism} chunks, sums each on an FJP worker via
     * {@link com.mezze.datastructures.util.VectorOps#sumLongRange}, and
     * combines. Falls back to a boxed-storage scalar loop when the seq
     * isn't a LongSeqStorage. #69.
     */
    @TruffleBoundary
    public static long fjpoolSumLong(MezzeRecord args) {
        Object xs = args.getField("xs");
        if (!(xs instanceof com.mezze.datastructures.seq.MezzeSeq seq)) return 0L;
        com.mezze.datastructures.seq.SeqStorage storage = seq.getStorage();
        if (!(storage instanceof com.mezze.datastructures.seq.LongSeqStorage lss)) {
            long total = 0L;
            for (int i = 0; i < seq.size(); i++) {
                Object elem = seq.get(i);
                if (elem instanceof Long l) total += l;
                else if (elem instanceof Integer n2) total += n2;
            }
            return total;
        }
        long[] data = lss.getArray();
        int n = data.length;
        if (n == 0) return 0L;

        ForkJoinPool pool = CURRENT_POOL.get();
        int chunks = chunkCountForPool(pool, n);
        if (chunks == 1) {
            return com.mezze.datastructures.util.VectorOps.sumLongRange(data, 0, n);
        }

        int chunkSize = (n + chunks - 1) / chunks;
        long[] partials = new long[chunks];
        @SuppressWarnings("unchecked")
        ForkJoinTask<Void>[] tasks = new ForkJoinTask[chunks - 1];
        for (int c = 0; c < chunks - 1; c++) {
            final int idx = c;
            final int from = c * chunkSize;
            final int to = Math.min(from + chunkSize, n);
            tasks[c] = new RecursiveTask<Void>() {
                @Override protected Void compute() {
                    partials[idx] = com.mezze.datastructures.util.VectorOps
                        .sumLongRange(data, from, to);
                    return null;
                }
            };
            if (pool != null && Thread.currentThread() instanceof TruffleFJPWorker) {
                tasks[c].fork();
            } else if (pool != null) {
                pool.execute(tasks[c]);
            } else {
                tasks[c].invoke();
            }
        }
        // Last chunk runs inline on this thread.
        {
            int from = (chunks - 1) * chunkSize;
            partials[chunks - 1] = com.mezze.datastructures.util.VectorOps
                .sumLongRange(data, from, n);
        }
        for (int c = 0; c < chunks - 1; c++) tasks[c].join();

        long total = 0L;
        for (long p : partials) total += p;
        return total;
    }

    /** Parallel SIMD product over a Seq of Ints. Same shape as
     *  {@link #fjpoolSumLong}. #69. */
    @TruffleBoundary
    public static long fjpoolProductLong(MezzeRecord args) {
        Object xs = args.getField("xs");
        if (!(xs instanceof com.mezze.datastructures.seq.MezzeSeq seq)) return 1L;
        com.mezze.datastructures.seq.SeqStorage storage = seq.getStorage();
        if (!(storage instanceof com.mezze.datastructures.seq.LongSeqStorage lss)) {
            long result = 1L;
            for (int i = 0; i < seq.size(); i++) {
                Object elem = seq.get(i);
                if (elem instanceof Long l) result *= l;
                else if (elem instanceof Integer n2) result *= n2;
            }
            return result;
        }
        long[] data = lss.getArray();
        int n = data.length;
        if (n == 0) return 1L;

        ForkJoinPool pool = CURRENT_POOL.get();
        int chunks = chunkCountForPool(pool, n);
        if (chunks == 1) {
            return com.mezze.datastructures.util.VectorOps.productLongRange(data, 0, n);
        }

        int chunkSize = (n + chunks - 1) / chunks;
        long[] partials = new long[chunks];
        @SuppressWarnings("unchecked")
        ForkJoinTask<Void>[] tasks = new ForkJoinTask[chunks - 1];
        for (int c = 0; c < chunks - 1; c++) {
            final int idx = c;
            final int from = c * chunkSize;
            final int to = Math.min(from + chunkSize, n);
            tasks[c] = new RecursiveTask<Void>() {
                @Override protected Void compute() {
                    partials[idx] = com.mezze.datastructures.util.VectorOps
                        .productLongRange(data, from, to);
                    return null;
                }
            };
            if (pool != null && Thread.currentThread() instanceof TruffleFJPWorker) {
                tasks[c].fork();
            } else if (pool != null) {
                pool.execute(tasks[c]);
            } else {
                tasks[c].invoke();
            }
        }
        {
            int from = (chunks - 1) * chunkSize;
            partials[chunks - 1] = com.mezze.datastructures.util.VectorOps
                .productLongRange(data, from, n);
        }
        for (int c = 0; c < chunks - 1; c++) tasks[c].join();

        long total = 1L;
        for (long p : partials) total *= p;
        return total;
    }

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
        // Optional min_chunk hint (#65).
        long minChunk = getLongOrZero(args, "min_chunk");
        MIN_CHUNK_HINT.set(minChunk > 0 ? minChunk : 1L);
        return MezzeUnit.INSTANCE;
    }

    /** Exit a VirtualParallel scope. Clears the semaphore. */
    @TruffleBoundary
    public static MezzeUnit vthreadFinalize(MezzeRecord args) {
        VTHREAD_SEMAPHORE.remove();
        MIN_CHUNK_HINT.remove();
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
        boolean callerIsPlatform = !Thread.currentThread().isVirtual();

        AtomicReference<TaskUnhandledOpException> opSink = new AtomicReference<>();
        AtomicReference<Throwable> errSink = new AtomicReference<>();

        Env env = MezzeContext.get(node).getEnv();

        // Right side: always on a fresh vthread.
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

        // Left side: if the caller is on a virtual thread, run inline
        // (the classic Rayon pattern — cheap because we don't spawn a
        // second vthread). If the caller is on the PLATFORM thread,
        // running Mezze code inline while a peer vthread is doing the
        // same triggers a Truffle safepoint / Loom pinning interaction
        // that wedges the platform thread mid-execution. In that case
        // spawn the left as its own vthread and let the platform thread
        // wait on both — slower per-join but doesn't deadlock.
        Object[] leftResultHolder = new Object[1];
        Thread leftThread = null;
        if (callerIsPlatform) {
            leftThread = env.newTruffleThreadBuilder(() -> {
                if (sem != null) {
                    try { sem.acquire(); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                try {
                    SpawnSupport.runBatchTask(chain, opSink, errSink, () ->
                        leftResultHolder[0] = callThunk(leftFn, callNode));
                } finally {
                    if (sem != null) sem.release();
                }
            }).virtual(true).build();
            leftThread.start();
        } else {
            if (sem != null) {
                try { sem.acquire(); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("VirtualParallel.join interrupted", e);
                }
            }
            try {
                // Same-thread inline: skip the redundant chain reinstall.
                // #62.
                SpawnSupport.runBatchTask(
                    chain, opSink, errSink,
                    () -> leftResultHolder[0] = callThunk(leftFn, callNode),
                    /* sameThreadChain = */ true);
            } finally {
                if (sem != null) sem.release();
            }
        }

        try {
            if (leftThread != null) leftThread.join();
            rightThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("VirtualParallel.join interrupted waiting for peer", e);
        }

        SpawnSupport.propagateBatchFailure(opSink, errSink, "vthread join", callNode);

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
