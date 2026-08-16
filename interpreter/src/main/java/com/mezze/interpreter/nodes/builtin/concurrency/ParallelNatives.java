package com.mezze.interpreter.nodes.builtin.concurrency;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.nodes.builtin.EffectHandlerDriver.TaskUnhandledOpException;
import com.mezze.interpreter.nodes.continuation.HandlerChainRegistry;
import com.mezze.interpreter.nodes.continuation.HandlerChainEntry;
import com.mezze.interpreter.nodes.continuation.SpawnSupport;
import com.mezze.interpreter.runtime.MezzeContext;
import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeUnit;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage.Env;
import com.oracle.truffle.api.nodes.IndirectCallNode;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.RecursiveTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Native primitives for the Parallel effect. Both ForkJoinParallel and
 * VirtualParallel now share one implementation shape: {@code join}
 * spawns a fresh Truffle virtual thread for the right side, runs the
 * left inline on the caller, and joins. Derived combinators (map,
 * reduce, filter, ...) sit on top of this in the Mezze stdlib.
 *
 * <p>This shape matches TruffleRuby's {@code ThreadManager} — one
 * polyglot thread per unit of parallel work, virtual-thread backed for
 * cheap fork cost, no shared queue or worker pool. Prior attempts to
 * build a shared pool of polyglot workers hit oracle/graal#10923
 * (ThreadLocalHandshake barrier hang) and violated the Truffle
 * Javadoc's own guidance against {@code ExecutorService.awaitTermination}
 * on polyglot threads. Full research in
 * {@code scratchpad/truffle_deep_research.md}.
 *
 * <p>Pure-Java parallel kernels ({@link #fjpoolSumLong},
 * {@link #fjpoolProductLong}) still use {@link ForkJoinPool#commonPool()} —
 * those workers never enter the polyglot context, so they are safe under
 * the GraalPy AsyncHandler pattern (system-thread scheduler that never
 * runs guest code).
 */
public final class ParallelNatives {

    private ParallelNatives() {}

    // ==================== Per-scope state ====================
    //
    // Both scopes stash small hints in ThreadLocals so join() /
    // combinators can read them without threading through Mezze source.
    // No pool / worker state anymore — every join is a fresh virtual
    // thread.

    /** Parallelism hint for a ForkJoinParallel scope. Used by SIMD
     *  kernels to decide chunk count. Default: available processors. */
    private static final ThreadLocal<Integer> FJP_PARALLELISM = new InheritableThreadLocal<>();

    /** Backpressure semaphore for VirtualParallelWith { max_concurrent }.
     *  Null means unbounded (VirtualParallel default). */
    private static final ThreadLocal<Semaphore> VTHREAD_SEMAPHORE = new ThreadLocal<>();

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

    // ==================== ForkJoinParallel ====================

    /**
     * Enter a ForkJoinParallel scope. Records the parallelism hint (for
     * SIMD chunking) and the min_chunk hint. No pool allocation — each
     * {@code join} spawns its own virtual thread.
     */
    @TruffleBoundary
    public static MezzeUnit fjpoolInit(MezzeRecord args, MezzeNode node) {
        long parallelism = getLongOrZero(args, "parallelism");
        int size = parallelism > 0 ? (int) parallelism : Runtime.getRuntime().availableProcessors();
        FJP_PARALLELISM.set(size);
        long minChunk = getLongOrZero(args, "min_chunk");
        MIN_CHUNK_HINT.set(minChunk > 0 ? minChunk : 1L);
        return MezzeUnit.INSTANCE;
    }

    /**
     * Exit a ForkJoinParallel scope. Just clears the hint ThreadLocals —
     * no pool to shut down, no {@code awaitTermination} race with the
     * Truffle context close-lock.
     */
    @TruffleBoundary
    public static MezzeUnit fjpoolFinalize(MezzeRecord args) {
        FJP_PARALLELISM.remove();
        MIN_CHUNK_HINT.remove();
        return MezzeUnit.INSTANCE;
    }

    /** Read the current scope's min_chunk hint. Returns 1 when no scope
     *  is active or the scope didn't set one. */
    @TruffleBoundary
    public static long minChunkHint(MezzeRecord args) {
        return MIN_CHUNK_HINT.get();
    }

    /**
     * The Parallel.join primitive on ForkJoinParallel. Same shape as
     * {@link #vthreadJoin}: fresh virtual thread for the right side,
     * left runs inline. Loom absorbs the per-fork cost; no shared queue
     * means no priority-inversion deadlock on recursive nested joins.
     */
    @TruffleBoundary
    public static MezzeRecord fjpoolJoin(MezzeRecord args, IndirectCallNode callNode, MezzeNode node) {
        return joinOnVirtualThread(args, callNode, node, /* sem = */ null);
    }

    // ==================== ForkJoinParallel numeric fast paths ====================
    //
    // parallel::sum / product on LongSeqStorage. The SIMD kernel runs on
    // ForkJoinPool.commonPool() — pure Java, no guest code, so the
    // workers never enter the polyglot context. This matches the GraalPy
    // AsyncHandler shape (system-thread scheduler).

    private static int chunkCountForParallelism(int arrayLen) {
        Integer hint = FJP_PARALLELISM.get();
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
     *  {@link #fjpoolSumLong}. */
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
     * The Parallel.join primitive on VirtualParallel. Spawns one virtual
     * thread for {@code r}, runs {@code l} inline on caller (or as its
     * own thread when caller is a platform thread — Loom pinning
     * interaction), joins.
     */
    @TruffleBoundary
    public static MezzeRecord vthreadJoin(MezzeRecord args, IndirectCallNode callNode, MezzeNode node) {
        return joinOnVirtualThread(args, callNode, node, VTHREAD_SEMAPHORE.get());
    }

    // ==================== Shared join impl ====================

    /**
     * Shared virtual-thread-per-fork join. Both fjpoolJoin and
     * vthreadJoin land here — the only difference is whether a
     * backpressure semaphore is passed.
     *
     * <p>Left side: run inline on caller when caller is itself a virtual
     * thread (cheap — no second spawn). When caller is a platform
     * thread, spawn a fresh virtual thread for the left too — running
     * Mezze code inline while a peer vthread runs the same triggers a
     * Truffle safepoint / Loom pinning interaction that wedges the
     * platform thread.
     *
     * <p>Right side: always a fresh virtual thread via
     * {@code env.newTruffleThreadBuilder(...).virtual(true).build()} —
     * the polyglot-context-safe primitive, same one TruffleRuby uses
     * for its Fibers.
     */
    private static MezzeRecord joinOnVirtualThread(
        MezzeRecord args,
        IndirectCallNode callNode,
        MezzeNode node,
        Semaphore sem
    ) {
        MezzeFunction leftFn = getFunction(args, "l");
        MezzeFunction rightFn = getFunction(args, "r");

        HandlerChainEntry[] chain = HandlerChainRegistry.snapshotHandlerChainEntries();
        boolean callerIsPlatform = !Thread.currentThread().isVirtual();

        AtomicReference<TaskUnhandledOpException> opSink = new AtomicReference<>();
        AtomicReference<Throwable> errSink = new AtomicReference<>();

        Env env = MezzeContext.get(node).getEnv();

        // Right side: always a fresh virtual thread.
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
                    throw new RuntimeException("Parallel.join interrupted", e);
                }
            }
            try {
                // Same-thread inline: skip the redundant chain reinstall.
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
            throw new RuntimeException("Parallel.join interrupted waiting for peer", e);
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
        throw new RuntimeException("Expected function for field '" + fieldName + "'");
    }

    private static long getLongOrZero(MezzeRecord args, String fieldName) {
        Object value = args.getField(fieldName);
        if (value instanceof Long l) return l;
        if (value instanceof Integer i) return i.longValue();
        return 0L;
    }
}
