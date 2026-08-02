package com.mezze.interpreter.nodes.continuation;

import com.mezze.interpreter.values.MezzeRecord;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Loom-backed fiber used by the effect-handler runtime.
 *
 * <p>Wraps a body Runnable in a {@code jdk.internal.vm.Continuation}.
 * The body runs inside {@link #run()}; when the body wants to suspend
 * (e.g. an effect operation has been called and the handler decided to
 * escape or stash), it calls {@link #suspend(Object)}, which freezes the
 * stack and returns to the {@link #run()} call site. The runner reads
 * what was suspended via {@link #takeSuspendedValue()}, decides what to
 * do (stash, drop, eventually call run again), etc.
 *
 * <h2>Lifecycle invariants</h2>
 *
 * <p><b>One-shot.</b> HotSpot's {@code Continuation} is one-shot: once
 * the body returns naturally, the fiber is closed and any further
 * {@link #run()} call throws {@link IllegalStateException}. The driver
 * marks completion by stashing a {@code BodyResult} via
 * {@link #setSuspendedValue(Object)}; subsequent {@code run()} would
 * crash, so the driver checks {@link #isDone()} or the suspend payload
 * type before recursing.
 *
 * <p><b>Thread-locals.</b> Two distinct ThreadLocals track fiber state.
 * {@link #CURRENT} points to the fiber whose continuation is on top of
 * the current thread's stack — the one {@link #suspend(Object)} would
 * yield from. {@link #DISPATCH} points to the fiber whose op handler
 * the driver is invoking on its own stack — set during
 * {@link #enterDispatch()}/{@link #exitDispatch(EffectFiber)} so the op
 * handler body can read this fiber's resume/abort handles via
 * {@code EffectBindingNode}, while NOT pretending its continuation is
 * suspendable. They are always disjoint: a fiber is either running or
 * being dispatched-into, never both at the same time on the same thread.
 *
 * <p><b>Cross-scope yields.</b> When an inner perform's body performs an
 * op that propagates past the inner handler, the driver's
 * {@link #suspend(Object)} on the inner fiber bypasses the outer
 * fiber's continuation frame on the same thread. The outer fiber's
 * Java-side try/finally blocks DO NOT fire during this yield — control
 * goes straight back to whoever last called {@link #run()} on the outer
 * fiber. {@code EffectHandlerDriver.run} works around this with
 * {@link #handlerChainDepth()} + {@link #truncateHandlerChainTo(int)} in
 * an outer finally that fires only when control finally returns.
 *
 * <p><b>Handler chain.</b> Independent of fiber state, every thread has
 * a {@link #HANDLER_CHAIN} stack of {@link HandlerChainEntry} pairs
 * (handler record + the content hash of the effect it implements) that
 * spawned tasks inherit at spawn time
 * ({@link #snapshotHandlerChainEntries()} +
 * {@link #installHandlerChainEntries(HandlerChainEntry[])}). The chain
 * participates in {@link com.mezze.interpreter.nodes.effect.OpCallNode}'s
 * fiber-less dispatch path: when there's no current fiber but a chain
 * is installed, the op-call walks the chain to find a matching handler
 * and invokes it directly. Matching is by (effect_hash, op_name) so two
 * in-scope effects with the same op simple name don't collide (#246).
 * Unhandled ops at the chain bottom are surfaced as
 * {@code TaskUnhandledOpException} for the spawning code to re-issue on
 * the parent fiber at await time. See
 * {@link com.mezze.interpreter.nodes.continuation.SpawnSupport}.
 *
 * <p><b>Late resume.</b> A {@code resume} reference captured inside an
 * abort payload (the OCaml-5 style "captured continuation" pattern,
 * e.g. Generator's Stream consumer) outlives the original op-handler
 * body. Re-entering the cached resume CallTarget calls back into
 * {@link com.mezze.interpreter.nodes.builtin.EffectHandlerDriver#drive
 * drive}, which catches {@link
 * com.mezze.interpreter.nodes.builtin.EffectHandlerDriver.AbortPerformException
 * AbortPerformException} so later aborts during the deferred resume
 * surface as resume's return value rather than escaping past the
 * resume call site.
 *
 * <p><b>Dispatch-only fibers.</b>
 * {@link #createDispatchOnly()} produces a fiber with no continuation —
 * useful when {@code OpCallNode}'s chain-dispatch path wants to invoke
 * an inherited op handler that calls {@code abort}/{@code resume}. The
 * handler body needs a dispatch fiber to bind those handles to, but
 * there's no surrounding {@code perform} to suspend back into. The
 * dispatch-only fiber's {@code resume {val = X}} just returns X.
 */
public final class EffectFiber {

    // Continuation state — routed through a swappable backend since Phase 0
    // of Path C (docs/perf/bdsl-effects-path-c-2026-07.md). Null on
    // dispatch-only fibers, which never suspend or resume.
    @CompilationFinal final ContinuationBackend backend;

    private volatile Object suspendedValue;     // set by suspend(), read by runner
    private volatile Object resumeValue;        // set by runner, returned by suspend()

    // Per-op-invocation effect-handler control-flow handles, populated by
    // the driver right before it dispatches an op handler. Read by
    // ReadEffectHandleNode inside the op handler body. Stacked across
    // nested op calls so an inner op's invocation doesn't clobber the
    // outer op's handles.
    private Object[] effectHandleStack = new Object[8]; // [resume0, abort0, resume1, abort1, ...]
    private int effectHandleDepth = 0;

    private volatile boolean closed;

    private static final AtomicLong ID_GEN = new AtomicLong();
    @CompilationFinal final long id = ID_GEN.incrementAndGet();

    /**
     * Per-thread pointer to the fiber whose body is <b>actually running</b>
     * inside its continuation on this thread. Set by {@link #run()} before
     * entering the continuation; cleared when the continuation yields or
     * completes. Read by {@code OpCallNode} to decide whether to
     * {@link #suspend(Object) suspend} (yield the running continuation)
     * versus fall through to chain dispatch.
     *
     * <p>Critically this is NOT set while the driver is dispatching an op
     * handler on its own stack — during dispatch, no continuation is on the
     * current thread's stack, so {@code suspend} would fail. See
     * {@link #DISPATCH} for the dispatch-pinned slot.
     */
    private static final ThreadLocal<EffectFiber> CURRENT = new ThreadLocal<>();

    /**
     * Per-thread pointer to the fiber whose op handler is currently being
     * invoked by the driver. The op handler body needs to find the fiber to
     * read its resume/abort handles via {@code EffectBindingNode}, but ops
     * performed inside that body must NOT try to suspend this fiber (its
     * continuation is yielded — nothing to yield from). Pinned by
     * {@link #enterDispatch()} / cleared by {@link #exitDispatch(EffectFiber)}.
     */
    private static final ThreadLocal<EffectFiber> DISPATCH = new ThreadLocal<>();

    /**
     * The fiber whose body is currently running on the thread inside its
     * continuation, or {@code null} when no body is running. This is the
     * fiber that {@link #suspend(Object)} would yield from.
     */
    public static EffectFiber current() {
        return CURRENT.get();
    }

    /**
     * The fiber whose op handler is currently being dispatched on the
     * driver's stack, or {@code null}. Used by {@code EffectBindingNode} to
     * locate the fiber that owns the resume/abort handle stack.
     */
    public static EffectFiber dispatching() {
        return DISPATCH.get();
    }

    /**
     * Mark this fiber as the one being dispatched. Returns the previously
     * pinned fiber so the caller can restore it via {@link #exitDispatch}.
     * Does NOT touch {@link #CURRENT}: the fiber's continuation is not
     * actually running while its op handler executes on the driver's stack,
     * and nested op-calls inside that handler must fall through to chain
     * dispatch rather than attempt to yield.
     *
     * <p>Caveat: under nested performs (outer then inner), CURRENT during
     * an Inner-op dispatch still points at the Outer fiber. Native handlers
     * that <i>call back into Mezze code</i> (e.g. {@code __native_mutation_loop}
     * invoking {@code cond}/{@code body}) must clear CURRENT around the
     * callback themselves so the callback's op-calls route through chain
     * dispatch instead of suspending the wrong fiber. See
     * {@link #runDetached(java.util.function.Supplier)}.
     */
    public EffectFiber enterDispatch() {
        EffectFiber prev = DISPATCH.get();
        DISPATCH.set(this);
        return prev;
    }

    /** Counterpart to {@link #enterDispatch()}. */
    public static void exitDispatch(EffectFiber previous) {
        DISPATCH.set(previous);
    }

    /**
     * Run {@code body} with {@link #CURRENT} cleared on this thread and
     * restored on return. Used by native handlers (e.g. the
     * {@code __native_mutation_loop} while loop) that re-enter Mezze code
     * which may perform effect ops: clearing CURRENT routes those ops
     * through chain dispatch instead of suspending whichever fiber happens
     * to be the thread's CURRENT at the time (often an outer perform's
     * fiber, whose continuation is yielded several layers deep — suspending
     * it strands the rest of the body and surfaces as a stray
     * {@code MezzeSuspend} from the outer perform's driver).
     * Root cause of #255.
     */
    public static <T> T runDetached(java.util.function.Supplier<T> body) {
        EffectFiber prev = CURRENT.get();
        CURRENT.set(null);
        try {
            return body.get();
        } finally {
            CURRENT.set(prev);
        }
    }

    // ----------------------------------------------------------------
    // Per-thread handler chain.
    //
    // Each `perform Handler in body` pushes its handler record onto this
    // thread-local stack while body runs, then pops on exit. The chain
    // captures the dynamic set of handlers in scope on this thread, in
    // outermost→innermost order (top = innermost).
    //
    // Used by `Async.spawn` to capture the parent's handlers and re-
    // install them on the spawned virtual thread so op-calls inside the
    // task dispatch through the same handler chain.
    // ----------------------------------------------------------------

    private static final ThreadLocal<java.util.ArrayDeque<HandlerChainEntry>>
        HANDLER_CHAIN = ThreadLocal.withInitial(java.util.ArrayDeque::new);

    /**
     * Push a handler with the content hash of the effect it implements.
     * The effect hash is required for correct dispatch when two effects in
     * scope declare ops with the same simple name (#246).
     */
    public static void pushHandlerOnChain(
            com.mezze.interpreter.values.MezzeRecord handler,
            com.mezze.language.typedast.Hash effectHash) {
        HANDLER_CHAIN.get().push(new HandlerChainEntry(handler, effectHash));
    }

    public static void popHandlerOffChain() {
        HANDLER_CHAIN.get().pop();
    }

    /** Current depth of the per-thread handler chain. */
    public static int handlerChainDepth() {
        return HANDLER_CHAIN.get().size();
    }

    // ----------------------------------------------------------------
    // D8 BDSL Handle depth counter — only touched by
    // MezzeBytecodeRootNode.PushHandlerChain / PopHandlerChain.
    // Distinct from the general HANDLER_CHAIN depth so a Path A snapshot-
    // install on a spawned thread (which populates HANDLER_CHAIN without
    // establishing a catch site on this frame) doesn't fool BdslYieldDispatch
    // into thinking a BDSL Handle boundary is live. See project_d8_handleir_design.
    // ----------------------------------------------------------------

    /** Per-Handle scope-hook frame. Non-null only when the handler has an
     *  initialize_scope hook. */
    public static final class BdslHandleFrame {
        public final Object scope;
        public final Object prevScope;
        public BdslHandleFrame(Object scope, Object prevScope) {
            this.scope = scope;
            this.prevScope = prevScope;
        }
    }

    private static final ThreadLocal<java.util.ArrayDeque<BdslHandleFrame>>
        D8_HANDLE_STACK = ThreadLocal.withInitial(java.util.ArrayDeque::new);

    /** Sentinel pushed for handlers without scope hooks — avoids nullable
     *  ArrayDeque entries. Identity-compared, never dereferenced. */
    private static final BdslHandleFrame NO_SCOPE_FRAME = new BdslHandleFrame(null, null);

    public static void enterBdslHandle() {
        D8_HANDLE_STACK.get().push(NO_SCOPE_FRAME);
    }

    public static void enterBdslHandleWithScope(Object scope, Object prevScope) {
        D8_HANDLE_STACK.get().push(
            scope == null && prevScope == null ? NO_SCOPE_FRAME
                : new BdslHandleFrame(scope, prevScope));
    }

    public static void exitBdslHandle() {
        D8_HANDLE_STACK.get().pop();
    }

    /** Peek the scope frame at the innermost live BDSL Handle, or null if
     *  that Handle has no scope hooks (common case). Called by
     *  PopHandlerChain before it pops the stack. */
    public static BdslHandleFrame peekBdslHandleScope() {
        var s = D8_HANDLE_STACK.get();
        if (s.isEmpty()) return null;
        BdslHandleFrame top = s.peek();
        return top == NO_SCOPE_FRAME ? null : top;
    }

    /** Peek the handler record on the top of the per-thread chain — used
     *  by PopHandlerChain to reach finalize_scope before popping. */
    public static com.mezze.interpreter.values.MezzeRecord peekTopHandlerRecord() {
        var chain = HANDLER_CHAIN.get();
        if (chain.isEmpty()) return null;
        HandlerChainEntry entry = chain.peek();
        return entry == null ? null : entry.handler();
    }

    /** True when at least one BDSL Handle catch boundary is live on this
     *  thread, i.e. a PushHandlerChain has fired without a matching
     *  PopHandlerChain (in either the otherwise or catch branch). */
    public static boolean insideBdslHandle() {
        return !D8_HANDLE_STACK.get().isEmpty();
    }

    /**
     * Force the per-thread handler chain back to the given depth by
     * popping until size == depth. Used by {@code EffectHandlerDriver.run}'s
     * outer {@code finally} to recover from cross-scope continuation yields
     * (e.g. outer perform aborts while an inner perform's chain push is
     * still on the stack): inner's {@code popHandlerOffChain} never fires
     * because the yield bypasses inner's Java {@code finally}, leaving the
     * chain polluted for the next test. Truncating to a depth snapshot
     * makes the cleanup unconditional.
     */
    public static void truncateHandlerChainTo(int depth) {
        var chain = HANDLER_CHAIN.get();
        while (chain.size() > depth) chain.pop();
    }

    /** Singleton empty array — handed back instead of allocating per call.
     *  Most spawn sites snapshot a chain with no handlers above them
     *  (top-level Async.spawn outside any perform installs zero handlers
     *  on this thread), so this is the overwhelmingly common case. */
    private static final HandlerChainEntry[] EMPTY_ENTRY_CHAIN = new HandlerChainEntry[0];

    /**
     * Snapshot of the handler chain on this thread, innermost-first
     * (matching the iteration order callers want for "innermost matches
     * first" dispatch). Returns an array of (handler, effect_hash) entries
     * so chain-dispatch can match by (effect_hash, op_name) — required to
     * disambiguate when two effects in scope declare an op with the same
     * simple name (#246).
     */
    public static HandlerChainEntry[] snapshotHandlerChainEntries() {
        ArrayDeque<HandlerChainEntry> chain = HANDLER_CHAIN.get();
        int size = chain.size();
        if (size == 0) return EMPTY_ENTRY_CHAIN;
        return chain.toArray(new HandlerChainEntry[size]);
    }

    /**
     * Allocation-free lookup of the innermost handler whose effect-hash
     * matches {@code effectHash}, or {@code null} if none in scope. Used by
     * the marker-effect fast path in {@code OpCallNode}: hot Mut op-calls
     * need to locate the surrounding LocalMut handler without allocating
     * a fresh chain array per call. ArrayDeque.peekFirst is O(1); the
     * iterator path (when we need to skip past non-matching entries) is
     * still allocation-free because ArrayDeque's Iterator is an inner
     * non-static class reused via cursor state. #257.
     */
    public static com.mezze.interpreter.values.MezzeRecord peekHandlerForEffect(
            com.mezze.language.typedast.Hash effectHash) {
        ArrayDeque<HandlerChainEntry> chain = HANDLER_CHAIN.get();
        if (chain.isEmpty()) return null;
        // Fast path: the innermost entry usually matches (perform Mut.x
        // inside `handle Mut { ... }`). Check it without setting up the
        // iterator — saves Iterator alloc + a virtual call per perform.
        HandlerChainEntry head = chain.peek();
        if (head != null) {
            com.mezze.language.typedast.Hash eh = head.effectHash();
            if (eh != null && eh.equals(effectHash)) {
                return head.handler();
            }
        }
        // Slow path: walk the rest of the chain looking for an outer handler.
        for (HandlerChainEntry entry : chain) {
            if (entry == head) continue;  // already checked above
            com.mezze.language.typedast.Hash eh = entry.effectHash();
            if (eh != null && eh.equals(effectHash)) {
                return entry.handler();
            }
        }
        return null;
    }

    /**
     * Install a captured (handler, effect-hash) chain on the current
     * thread. Caller is responsible for {@link #clearHandlerChain()} on
     * the same thread when done (typically in a finally block). For
     * one-shot scope use, {@link #runWithHandlerChain} is safer.
     */
    public static void installHandlerChainEntries(HandlerChainEntry[] chain) {
        ArrayDeque<HandlerChainEntry> stack = HANDLER_CHAIN.get();
        // Push in reverse so the innermost (chain[0]) ends up on top.
        for (int i = chain.length - 1; i >= 0; i--) {
            stack.push(chain[i]);
        }
    }

    public static void clearHandlerChain() {
        HANDLER_CHAIN.get().clear();
    }

    /**
     * Pop {@code count} handlers from the top of this thread's chain.
     * Companion to {@link #installHandlerChainEntries} when the caller
     * must preserve any handlers underneath the chunk it installed (e.g.
     * the coop scheduler runs spawned bodies on its driver thread, which
     * itself sits inside an outer perform).
     */
    public static void popHandlerChain(int count) {
        if (count <= 0) return;
        ArrayDeque<HandlerChainEntry> stack = HANDLER_CHAIN.get();
        for (int i = 0; i < count && !stack.isEmpty(); i++) {
            stack.pop();
        }
    }

    /**
     * Install {@code chain} on the current thread, run {@code body}, then
     * clear. Use this in spawned-task bodies that need to inherit the
     * parent's handler chain. Empty chain is a no-op fast path.
     * Effect-hash-preserving: required so cross-thread spawn paths retain
     * effect identity and the inherited chain's op-name lookup can
     * disambiguate when two in-scope effects declare an op with the same
     * simple name (#246).
     */
    public static void runWithHandlerChain(HandlerChainEntry[] chain, Runnable body) {
        if (chain.length == 0) {
            body.run();
            return;
        }
        installHandlerChainEntries(chain);
        try {
            body.run();
        } finally {
            // Pop only what we pushed. clearHandlerChain() would wipe
            // handlers that were installed by an outer perform on the
            // same thread — which happens when runWithHandlerChain is
            // called inline from a thread that already has a chain
            // (e.g. Parallel.join's left-side inline execution).
            popHandlerChain(chain.length);
        }
    }

    private EffectFiber(ContinuationBackend backend) {
        this.backend = backend;
    }

    /**
     * Build a fiber whose body is the given Runnable. The body does not
     * execute until {@link #run()} is called.
     *
     * <p>Backend today is always the Loom-based one. Path C Phase 3.1
     * will flip this to select {@link CpsContinuationBackend} when
     * {@link com.mezze.interpreter.runtime.MezzeLanguage#CPS_ENABLED} is
     * on, once the CPS backend can transparently replace Loom (i.e.
     * after the real IR transform lands in Phases 1.5-1.6).
     */
    @TruffleBoundary
    public static EffectFiber create(String name, Runnable body) {
        return new EffectFiber(new LoomContinuationBackend(name, body));
    }

    /**
     * Build a fiber explicitly using the CPS backend. Currently only
     * exercised by tests probing the CPS backend end-to-end; production
     * callers should stick with {@link #create}. See
     * {@link CpsContinuationBackend} for its scope caveats.
     */
    @TruffleBoundary
    public static EffectFiber createCps(String name, Runnable body) {
        return new EffectFiber(new CpsContinuationBackend(name, body));
    }

    /**
     * Build a "dispatch-only" fiber with no Loom continuation. Cannot be
     * {@link #run()} or {@link #suspend(Object) suspended}; serves only as
     * a carrier for the effect-handle stack ({@link #pushEffectHandles},
     * {@link #currentResume}, {@link #currentAbort}) so chain-dispatch on
     * spawned threads can give op handlers a valid dispatch context for
     * reading {@code resume}/{@code abort} via {@code EffectBindingNode}.
     *
     * <p>Acquires from a thread-local 1-slot pool when available — every
     * chain-dispatch entry allocates and discards one dispatch fiber,
     * so reusing a single per-thread slot saves an allocation per
     * op-call on the chain-dispatch path. The fiber is in clean state
     * when reused (popEffectHandles already clears its slots).
     *
     * <p>Callers MUST pair this with {@link #releaseDispatchOnly(EffectFiber)}
     * after use. The pool is best-effort: nested dispatch fibers allocate
     * fresh (no recursive pool).
     */
    public static EffectFiber acquireDispatchOnly() {
        EffectFiber cached = DISPATCH_POOL.get();
        if (cached != null) {
            DISPATCH_POOL.set(null);
            return cached;
        }
        return new EffectFiber(/* backend */ null);
    }

    /** Return a dispatch fiber to the per-thread cache (best-effort). */
    public static void releaseDispatchOnly(EffectFiber fiber) {
        if (fiber.effectHandleDepth != 0) {
            // Caller didn't pop their pushes — don't pool a dirty fiber.
            return;
        }
        // Most recent goes in cache; older one (if any) is dropped.
        DISPATCH_POOL.set(fiber);
    }

    /** Back-compat wrapper. New callers should use acquire/release. */
    @TruffleBoundary
    public static EffectFiber createDispatchOnly() {
        return acquireDispatchOnly();
    }

    private static final ThreadLocal<EffectFiber> DISPATCH_POOL =
        ThreadLocal.withInitial(() -> null);

    /**
     * Run/resume the body. Returns when body either suspends or completes.
     * After this returns, check {@link #isDone()} to distinguish.
     */
    @TruffleBoundary
    public void run() {
        if (closed) {
            throw new IllegalStateException(
                "EffectFiber@" + id + " is closed; cannot run");
        }
        EffectFiber previous = CURRENT.get();
        CURRENT.set(this);
        try {
            backend.run();
        } finally {
            CURRENT.set(previous);
        }
    }

    /**
     * Run the body without setting {@link #CURRENT}. Used by the coop
     * scheduler: the body should dispatch effect ops via the snapshot
     * handler chain (chain-dispatch path in {@code OpCallNode}), not by
     * suspending the fiber for every op-call. The fiber is still
     * suspendable — call {@link #suspendUnscoped(Object)} from inside
     * the body when you do want to yield (e.g. for park).
     */
    @TruffleBoundary
    public void runUnscoped() {
        if (closed) {
            throw new IllegalStateException(
                "EffectFiber@" + id + " is closed; cannot run");
        }
        EffectFiber previous = CURRENT.get();
        CURRENT.set(null);
        try {
            backend.run();
        } finally {
            CURRENT.set(previous);
        }
    }

    /**
     * Suspend this fiber regardless of whether it is the current fiber.
     * Companion to {@link #runUnscoped()} for the coop scheduler.
     */
    @TruffleBoundary
    public Object suspendUnscoped(Object value) {
        if (closed) {
            throw new IllegalStateException(
                "EffectFiber@" + id + " is closed; cannot suspend");
        }
        this.suspendedValue = value;
        boolean ok = backend.yield();
        if (!ok) {
            throw new IllegalStateException(
                "EffectFiber yield failed (likely native frames pinning)");
        }
        Object resumed = this.resumeValue;
        this.resumeValue = null;
        return resumed;
    }

    public boolean isDone() {
        return backend.isDone();
    }

    /**
     * Called from inside the body to suspend with a value. The runner
     * reads it via {@link #takeSuspendedValue()}.
     *
     * <p>When {@link #run()} is called again later, this method returns
     * whatever the runner placed via {@link #setResumeValue(Object)}.
     */
    @TruffleBoundary
    public Object suspend(Object value) {
        if (closed) {
            throw new IllegalStateException(
                "EffectFiber@" + id + " is closed; cannot suspend");
        }
        this.suspendedValue = value;
        boolean ok = backend.yield();
        if (!ok) {
            throw new IllegalStateException(
                "EffectFiber yield failed (likely native frames pinning)");
        }
        Object resumed = this.resumeValue;
        this.resumeValue = null;
        return resumed;
    }

    /** Read what the body most recently suspended with, then clear the slot. */
    public Object takeSuspendedValue() {
        Object v = suspendedValue;
        suspendedValue = null;
        return v;
    }

    /** Set the value the next {@link #suspend(Object)} call will return. */
    public void setResumeValue(Object value) {
        this.resumeValue = value;
    }

    /**
     * Stash a value as the "suspended value" without actually suspending.
     * Used by the runtime to expose body's return value when body completes
     * without calling suspend(): the runner can read it via
     * {@link #takeSuspendedValue()} the same way it reads explicit suspends.
     */
    public void setSuspendedValue(Object value) {
        this.suspendedValue = value;
    }

    /** Mark the fiber's region closed. Subsequent run/suspend throw. */
    public void close() {
        this.closed = true;
    }

    public boolean isClosed() {
        return closed;
    }

    public long getId() { return id; }

    // ----------------------------------------------------------------
    // Per-op-invocation effect-handler control-flow handles.
    //
    // The driver pushes a frame {resume, abort} before invoking an op
    // handler's lambda and pops it after the lambda returns. Op handler
    // body code reads them via ReadEffectHandleNode, which calls
    // currentResume() / currentAbort() on whichever fiber is current.
    //
    // Nested op calls each push their own frame, so inner ops don't
    // clobber outer ops' handles.
    // ----------------------------------------------------------------

    // Parallel stack of "did this op invocation call resume?" booleans,
    // one per frame on the effect-handle stack. The driver checks the top
    // of this stack after the op handler returns to distinguish:
    //   - fall-through (no resume call) → drive with return value
    //   - explicit resume during op body → return value is perform's result
    private boolean[] resumedStack = new boolean[8];

    public void pushEffectHandles(Object resume, Object abort) {
        int needed = (effectHandleDepth + 1) * 2;
        if (needed > effectHandleStack.length) {
            Object[] grown = new Object[effectHandleStack.length * 2];
            System.arraycopy(effectHandleStack, 0, grown, 0, effectHandleStack.length);
            effectHandleStack = grown;
        }
        if (effectHandleDepth + 1 > resumedStack.length) {
            boolean[] grown = new boolean[resumedStack.length * 2];
            System.arraycopy(resumedStack, 0, grown, 0, resumedStack.length);
            resumedStack = grown;
        }
        effectHandleStack[effectHandleDepth * 2] = resume;
        effectHandleStack[effectHandleDepth * 2 + 1] = abort;
        resumedStack[effectHandleDepth] = false;
        effectHandleDepth++;
    }

    public void popEffectHandles() {
        if (effectHandleDepth == 0) {
            throw new IllegalStateException(
                "EffectFiber@" + id + ": effect-handle stack underflow");
        }
        effectHandleDepth--;
        effectHandleStack[effectHandleDepth * 2] = null;
        effectHandleStack[effectHandleDepth * 2 + 1] = null;
        resumedStack[effectHandleDepth] = false;
    }

    /**
     * Mark that {@code resume} was invoked at the current op-handler
     * frame. Called by the driver's resume closure before recursing.
     */
    public void markResumedAtCurrentFrame() {
        if (effectHandleDepth > 0) {
            resumedStack[effectHandleDepth - 1] = true;
        }
    }

    /**
     * Whether {@code resume} was invoked at the current op-handler frame.
     * Read by the driver after the op handler returns to decide fall-through
     * vs explicit-resume semantics.
     */
    public boolean wasResumedAtCurrentFrame() {
        return effectHandleDepth > 0 && resumedStack[effectHandleDepth - 1];
    }

    public Object currentResume() {
        if (effectHandleDepth == 0) return null;
        return effectHandleStack[(effectHandleDepth - 1) * 2];
    }

    public Object currentAbort() {
        if (effectHandleDepth == 0) return null;
        return effectHandleStack[(effectHandleDepth - 1) * 2 + 1];
    }

    @Override
    public String toString() {
        return "EffectFiber@" + id
            + (closed ? "[closed]" : isDone() ? "[done]" : "[live]");
    }
}
