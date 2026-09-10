package com.mezze.interpreter.nodes.builtin.concurrency;

import com.mezze.interpreter.runtime.NativeTypeMismatchException;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.nodes.builtin.EffectHandlerDriver.TaskUnhandledOpException;
import com.mezze.interpreter.nodes.continuation.HandlerChainRegistry;
import com.mezze.interpreter.nodes.continuation.HandlerChainEntry;
import com.mezze.interpreter.nodes.continuation.SpawnSupport;
import com.mezze.interpreter.runtime.MezzeContext;
import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.interpreter.values.MezzeGuestException;
import com.mezze.interpreter.values.MezzeRecord;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage.Env;
import com.oracle.truffle.api.nodes.IndirectCallNode;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.RecursiveTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Native primitives for the Parallel effect.
 *
 * <p>Two backends, honestly different substrates:
 * <ul>
 *   <li><b>LoomParallel</b> — {@code env.newTruffleThreadBuilder(...).virtual(true)}.
 *     Each fork is a Loom virtual Truffle thread, scheduled M:N onto
 *     the JDK's default carrier ForkJoinPool. Cheap spawn (~14µs),
 *     scales to millions of forks, ideal for I/O-bound work.
 *   <li><b>OSThreadParallel</b> — {@code env.newTruffleThreadBuilder(...).virtual(false)}.
 *     Each fork is a fresh platform Truffle thread, one OS thread per
 *     fork, no Loom mounting overhead. More expensive per spawn
 *     (~50µs) but avoids virtual-thread scheduler churn. Best for
 *     CPU-bound work where forks are coarse-grained and long-lived.
 * </ul>
 *
 * <p>Both backends share {@link #joinOnTruffleThread} — the only
 * difference is the {@code virtual} flag and (optionally) a
 * backpressure semaphore. Both use TruffleRuby's shape: one polyglot
 * thread per unit of work, no worker pool, no shared queue.
 *
 * <p>Prior attempts to build a shared pool of polyglot workers hit
 * oracle/graal#10923 (ThreadLocalHandshake barrier hang) and violated
 * the Truffle Javadoc's own guidance against
 * {@code ExecutorService.awaitTermination} on polyglot threads. Full
 * research in {@code scratchpad/truffle_deep_research.md}.
 *
 * <p>Pure-Java parallel kernels ({@link #parallelSumLong},
 * {@link #parallelProductLong}) still use {@link ForkJoinPool#commonPool()} —
 * those workers never enter the polyglot context, so they are safe under
 * the GraalPy AsyncHandler pattern (system-thread scheduler that never
 * runs guest code). Handler-neutral: used by both LoomParallel and
 * OSThreadParallel.
 */
public final class ParallelNatives {

    private ParallelNatives() {}

    // ==================== Per-scope state ====================
    //
    // Both scopes stash small hints in ThreadLocals so join() /
    // combinators can read them without threading through Mezze source.
    // No pool / worker state anymore — every join is a fresh virtual
    // thread.

    /** Parallelism hint for a Parallel scope (Loom or OSThread). Used
     *  by SIMD kernels to decide chunk count. Default: availableProcessors. */
    private static final ThreadLocal<Integer> PARALLELISM_HINT = new InheritableThreadLocal<>();

    /** Backpressure semaphore for LoomParallelWith { max_concurrent }.
     *  Null means unbounded (LoomParallel default). */
    private static final ThreadLocal<Semaphore> LOOM_SEMAPHORE = new ThreadLocal<>();

    /**
     * Minimum chunk size for derived combinators (map, filter, fold, ...).
     * When the input size is at or below this threshold, the combinator
     * stops splitting and runs the leaf inline instead of forking.
     * Default of 1 preserves the pre-hint behavior (fork until singletons).
     */
    private static final ThreadLocal<Long> MIN_CHUNK_HINT =
        new InheritableThreadLocal<Long>() {
            @Override protected Long initialValue() { return 1L; }
        };

    // ==================== LoomParallel ====================
    //
    // Both LoomParallel (Mezze API) and OSThreadParallel (phase 1
    // alias — see #464/#465) route through these init/finalize/join
    // natives. Phase 2 will split OSThreadParallel onto a real
    // platform-thread pool with per-task context enter/leave.

    /**
     * Enter a LoomParallel (or phase-1-aliased OSThreadParallel) scope.
     * Records the min_chunk + parallelism hints and optional backpressure
     * semaphore. No pool allocation — each {@code join} spawns its own
     * virtual thread.
     */
    @TruffleBoundary
    public static MezzeRecord loomParallelInit(MezzeRecord args, MezzeNode node) {
        // Accept both `max_concurrent` (LoomParallelWith) and `pool_size`
        // (OSThreadParallelWith) as the concurrency hint — during phase 1
        // they're the same knob. Phase 2 splits them.
        long parallelism = getLongOrZero(args, "pool_size");
        if (parallelism <= 0) parallelism = getLongOrZero(args, "max_concurrent");
        int size = parallelism > 0 ? (int) parallelism : Runtime.getRuntime().availableProcessors();
        PARALLELISM_HINT.set(size);

        long maxConcurrent = getLongOrZero(args, "max_concurrent");
        if (maxConcurrent > 0) {
            LOOM_SEMAPHORE.set(new Semaphore((int) maxConcurrent));
        } else {
            LOOM_SEMAPHORE.remove();
        }

        long minChunk = getLongOrZero(args, "min_chunk");
        MIN_CHUNK_HINT.set(minChunk > 0 ? minChunk : 1L);
        return MezzeRecord.empty();
    }

    /**
     * Exit a LoomParallel scope. Just clears the ThreadLocals — no pool
     * to shut down, no {@code awaitTermination} race with the Truffle
     * context close-lock.
     */
    @TruffleBoundary
    public static MezzeRecord loomParallelFinalize(MezzeRecord args) {
        PARALLELISM_HINT.remove();
        LOOM_SEMAPHORE.remove();
        MIN_CHUNK_HINT.remove();
        return MezzeRecord.empty();
    }

    /** Read the current scope's min_chunk hint. Returns 1 when no scope
     *  is active or the scope didn't set one. */
    @TruffleBoundary
    public static long minChunkHint(MezzeRecord args) {
        return MIN_CHUNK_HINT.get();
    }

    /**
     * The Parallel.join primitive on LoomParallel. Fresh virtual
     * Truffle thread for the right side, left runs inline (or as its
     * own vthread when caller is a platform thread — Loom pinning
     * interaction). Loom absorbs the per-fork cost; no shared queue
     * means no priority-inversion deadlock on recursive nested joins.
     */
    @TruffleBoundary
    public static MezzeRecord loomParallelJoin(MezzeRecord args, IndirectCallNode callNode, MezzeNode node) {
        return joinOnTruffleThread(args, callNode, node, LOOM_SEMAPHORE.get(), /* virtual = */ true);
    }

    // ==================== OSThreadParallel ====================
    //
    // Same init/finalize as LoomParallel (shared PARALLELISM_HINT +
    // MIN_CHUNK_HINT + LOOM_SEMAPHORE state; nothing OSThread-specific
    // to stash at the moment). Only the join primitive differs: it
    // requests virtual(false) so each fork is a fresh polyglot-attached
    // platform Truffle thread rather than a Loom virtual thread.

    /**
     * Enter an OSThreadParallel scope. Same knob semantics as
     * {@link #loomParallelInit} — accepts pool_size (worker count hint,
     * currently only feeds SIMD chunking) and min_chunk. No pool is
     * pre-allocated: each Parallel.join spawns its own platform Truffle
     * thread.
     */
    @TruffleBoundary
    public static MezzeRecord osthreadParallelInit(MezzeRecord args, MezzeNode node) {
        return loomParallelInit(args, node);
    }

    /** Exit an OSThreadParallel scope. Clears the hint ThreadLocals. */
    @TruffleBoundary
    public static MezzeRecord osthreadParallelFinalize(MezzeRecord args) {
        return loomParallelFinalize(args);
    }

    /**
     * The Parallel.join primitive on OSThreadParallel. Fresh platform
     * Truffle thread for the right side (one OS thread per fork), left
     * runs inline on caller (or spawns its own platform thread when
     * caller is virtual — mixing Loom callers with a platform-only
     * scope, pinning would wedge the carrier).
     *
     * <p>Best for coarse-grained CPU-bound joins where the ~50µs
     * platform-thread spawn cost is amortized. For fine-grained
     * parallelism prefer LoomParallel.
     */
    @TruffleBoundary
    public static MezzeRecord osthreadParallelJoin(MezzeRecord args, IndirectCallNode callNode, MezzeNode node) {
        return joinOnTruffleThread(args, callNode, node, LOOM_SEMAPHORE.get(), /* virtual = */ false);
    }

    // ==================== Parallel numeric fast paths ====================
    //
    // parallel::sum / product on LongSeqStorage. The SIMD kernel runs on
    // ForkJoinPool.commonPool() — pure Java, no guest code, so the
    // workers never enter the polyglot context. This matches the GraalPy
    // AsyncHandler shape (system-thread scheduler). Handler-neutral:
    // used by both LoomParallel and OSThreadParallel scopes.

    private static int chunkCountForParallelism(int arrayLen) {
        Integer hint = PARALLELISM_HINT.get();
        int p = hint != null ? hint : Runtime.getRuntime().availableProcessors();
        if (arrayLen < p) return Math.max(1, arrayLen);
        return p;
    }

    /**
     * Parallel SIMD sum over a Seq of Ints. Splits the underlying long[]
     * into {@code parallelism} chunks, sums each on
     * {@link ForkJoinPool#commonPool()}, and combines. Falls back to a
     * boxed-storage scalar loop when the seq isn't a LongSeqStorage.
     */
    @TruffleBoundary
    public static long parallelSumLong(MezzeRecord args) {
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

        int chunks = chunkCountForParallelism(n);
        if (chunks == 1) {
            return com.mezze.datastructures.util.VectorOps.sumLongRange(data, 0, n);
        }

        int chunkSize = (n + chunks - 1) / chunks;
        long[] partials = new long[chunks];
        ForkJoinPool pool = ForkJoinPool.commonPool();
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
            pool.execute(tasks[c]);
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
     *  {@link #parallelSumLong}. */
    @TruffleBoundary
    public static long parallelProductLong(MezzeRecord args) {
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

        int chunks = chunkCountForParallelism(n);
        if (chunks == 1) {
            return com.mezze.datastructures.util.VectorOps.productLongRange(data, 0, n);
        }

        int chunkSize = (n + chunks - 1) / chunks;
        long[] partials = new long[chunks];
        ForkJoinPool pool = ForkJoinPool.commonPool();
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
            pool.execute(tasks[c]);
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

    // ==================== Shared thread-per-fork join impl ====================

    /**
     * Thread-per-fork join. Called from loomParallelJoin (virtual=true)
     * and osthreadParallelJoin (virtual=false). Per-call variation:
     * optional backpressure semaphore + the virtual/platform choice.
     *
     * <p>Left side: run inline on caller only when the caller's thread
     * KIND matches what this scope wants — i.e. inline iff
     * {@code virtual == callerIsVirtual}. Otherwise spawn the left as
     * its own fresh thread of the requested kind. This avoids two
     * hazards: Loom pinning (a platform caller running Mezze code
     * inline while a peer vthread does the same wedges the carrier),
     * and kind divergence (an OSThreadParallel scope should not
     * silently execute half its work on a virtual thread because the
     * caller happened to be one).
     *
     * <p>Right side: always a fresh Truffle thread of the requested
     * kind, via {@code env.newTruffleThreadBuilder(...).virtual(flag).build()} —
     * the polyglot-context-safe primitive, same one TruffleRuby uses
     * for its Fibers.
     */
    private static MezzeRecord joinOnTruffleThread(
        MezzeRecord args,
        IndirectCallNode callNode,
        MezzeNode node,
        Semaphore sem,
        boolean virtual
    ) {
        MezzeFunction leftFn = getFunction(args, "l");
        MezzeFunction rightFn = getFunction(args, "r");

        HandlerChainEntry[] chain = HandlerChainRegistry.snapshotHandlerChainEntries();
        Object parentScope = HandlerChainRegistry.lookupScopeVar();
        boolean callerIsVirtual = Thread.currentThread().isVirtual();
        boolean leftInline = (virtual == callerIsVirtual);

        AtomicReference<TaskUnhandledOpException> opSink = new AtomicReference<>();
        AtomicReference<Throwable> errSink = new AtomicReference<>();

        Env env = MezzeContext.get(node).getEnv();

        // Right side: always a fresh Truffle thread of the requested kind.
        Object[] rightResultHolder = new Object[1];
        Thread rightThread = env.newTruffleThreadBuilder(() -> {
            if (sem != null) {
                try { sem.acquire(); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            try {
                SpawnSupport.runBatchTask(chain, parentScope, opSink, errSink, () ->
                    rightResultHolder[0] = callThunk(rightFn, callNode));
            } finally {
                if (sem != null) sem.release();
            }
        }).virtual(virtual).build();
        rightThread.start();

        Object[] leftResultHolder = new Object[1];
        Thread leftThread = null;
        if (!leftInline) {
            leftThread = env.newTruffleThreadBuilder(() -> {
                if (sem != null) {
                    try { sem.acquire(); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                try {
                    SpawnSupport.runBatchTask(chain, parentScope, opSink, errSink, () ->
                        leftResultHolder[0] = callThunk(leftFn, callNode));
                } finally {
                    if (sem != null) sem.release();
                }
            }).virtual(virtual).build();
            leftThread.start();
        } else {
            if (sem != null) {
                try { sem.acquire(); } catch (InterruptedException e) {
                    // Thread interrupted mid-join (cancellation). Re-set the
                    // interrupt flag and propagate the InterruptedException as
                    // the cause via the cause-carrying MezzeGuestException:
                    // routing to CancellationException here would change which
                    // handlers catch it, so we keep it a guest-fatal throw that
                    // preserves the cause (#387 / S8.1-E).
                    Thread.currentThread().interrupt();
                    throw new MezzeGuestException("Parallel.join interrupted", e);
                }
            }
            try {
                // Same-thread inline: skip the redundant chain reinstall.
                SpawnSupport.runBatchTask(
                    chain, parentScope, opSink, errSink,
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
            throw new MezzeGuestException("Parallel.join interrupted waiting for peer", e);
        }

        SpawnSupport.propagateBatchFailure(opSink, errSink, "parallel join", callNode);

        return MezzeRecord.fromFields(
            LR_FIELDS,
            new Object[]{leftResultHolder[0], rightResultHolder[0]}
        );
    }

    private static final String[] LR_FIELDS = new String[]{"l", "r"};

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
        throw new NativeTypeMismatchException("Expected function for field '" + fieldName + "'");
    }

    private static long getLongOrZero(MezzeRecord args, String fieldName) {
        Object value = args.getField(fieldName);
        if (value instanceof Long l) return l;
        if (value instanceof Integer i) return i.longValue();
        return 0L;
    }
}
