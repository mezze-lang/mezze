package com.mezze.interpreter.nodes.continuation;

import com.mezze.interpreter.values.MezzeRecord;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-thread state holder for the effect-handler runtime.
 *
 * <p>Historically wrapped a {@code jdk.internal.vm.Continuation} to
 * implement suspend/resume of an in-Mezze perform body across a driver
 * loop. That fiber-continuation surface (create/run/suspend/resume,
 * plus the Loom bridge in {@code LoomContinuationBackend}) was deleted
 * in #333 once BDSL Yield became the sole path for effect suspension.
 * What survives here is:
 *
 * <ul>
 *   <li>{@link #HANDLER_CHAIN} — per-thread stack of
 *       {@link HandlerChainEntry} pairs (handler record + effect hash),
 *       pushed by every {@code perform Handler in body} while body runs.
 *       Spawned tasks inherit the parent's chain at spawn time
 *       ({@link #snapshotHandlerChainEntries()} +
 *       {@link #installHandlerChainEntries(HandlerChainEntry[])}).</li>
 *   <li>{@link #D8_HANDLE_STACK} — BDSL Handle depth counter, distinct
 *       from HANDLER_CHAIN so a snapshot install on a spawned thread
 *       doesn't fool YieldDispatch into treating a chain push as a
 *       live catch boundary.</li>
 *   <li>Dispatch-only fibers ({@link #acquireDispatchOnly()}) — carry
 *       the per-op-invocation effect-handle stack so chain-dispatched
 *       op handlers can bind {@code resume}/{@code abort} via
 *       {@code EffectBindingNode}. No continuation involved.</li>
 * </ul>
 *
 * <p>Matching in chain dispatch is by (effect_hash, op_name) so two
 * in-scope effects with the same simple op name don't collide (#246).
 * Unhandled ops at the chain bottom surface as
 * {@code TaskUnhandledOpException} for the spawning code to re-dispatch
 * against the parent's chain at await time (see {@link SpawnSupport}).
 */
public final class HandlerChainRegistry {

    // Per-op-invocation effect-handler control-flow handles, populated by
    // chain dispatch right before it invokes an op handler. Read by the
    // EffectBindingIR (kind=RESUME/ABORT) lowering inside the op handler
    // body, which calls currentResume() / currentAbort() on the dispatch
    // fiber. Stacked across nested op calls so an inner op's invocation
    // doesn't clobber the outer op's handles.
    private Object[] effectHandleStack = new Object[8]; // [resume0, abort0, resume1, abort1, ...]
    private int effectHandleDepth = 0;

    private static final AtomicLong ID_GEN = new AtomicLong();
    @CompilationFinal final long id = ID_GEN.incrementAndGet();

    /**
     * Per-thread pointer to the fiber whose op handler is currently being
     * invoked by chain dispatch. The op handler body needs to find the
     * carrier fiber to read its resume/abort handles via
     * {@code EffectBindingNode}. Pinned by {@link #enterDispatch()} /
     * cleared by {@link #exitDispatch(HandlerChainRegistry)}.
     */
    private static final ThreadLocal<HandlerChainRegistry> DISPATCH = new ThreadLocal<>();

    /**
     * The fiber whose op handler is currently being dispatched on the
     * driver's stack, or {@code null}. Used by {@code EffectBindingNode} to
     * locate the fiber that owns the resume/abort handle stack.
     */
    public static HandlerChainRegistry dispatching() {
        return DISPATCH.get();
    }

    /**
     * Mark this fiber as the one being dispatched. Returns the previously
     * pinned fiber so the caller can restore it via {@link #exitDispatch}.
     */
    public HandlerChainRegistry enterDispatch() {
        HandlerChainRegistry prev = DISPATCH.get();
        DISPATCH.set(this);
        return prev;
    }

    /** Counterpart to {@link #enterDispatch()}. */
    public static void exitDispatch(HandlerChainRegistry previous) {
        DISPATCH.set(previous);
    }

    /**
     * Historical no-op preserved as a call-site hook. Before #333 this
     * cleared {@code CURRENT} around body so native handlers (e.g.
     * {@code __native_mutation_loop}) that re-entered Mezze code wouldn't
     * suspend an outer perform's Loom continuation. With Loom gone
     * CURRENT no longer exists, so this just runs body directly. Callers
     * remain untouched to avoid churn; if #333 follow-up wants to inline
     * every call, this method can be removed. Root cause of #255.
     */
    public static <T> T runDetached(java.util.function.Supplier<T> body) {
        return body.get();
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
    // Distinct from the general HANDLER_CHAIN depth so a snapshot install
    // on a spawned thread (which populates HANDLER_CHAIN without
    // establishing a catch site on this frame) doesn't fool YieldDispatch
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
     * popping until size == depth. Used by the BDSL Handle @Operation's
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
    /** `@TruffleBoundary` — same rationale as {@link #peekHandlerForEffect}:
     *  ThreadLocal.get closure reaches blocklisted methods under SVM. #402. */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
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
    // `@TruffleBoundary` — ThreadLocal.get() reachability closure in
    // native-image drags in every ThreadLocal supplier on the classpath
    // (including Helidon's ValueResolvingFilter → HashMap.<init>, which is
    // blocklisted for runtime compilation). The perform hot path calls
    // peekHandlerForEffect once per op-call; the boundary loses some inlining
    // but keeps SVM Truffle compilation valid. See #402.
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
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

    private HandlerChainRegistry() {}

    /**
     * Acquire a dispatch-only fiber — a carrier for the effect-handle
     * stack ({@link #pushEffectHandles}, {@link #currentResume},
     * {@link #currentAbort}) so chain-dispatched op handlers can bind
     * {@code resume}/{@code abort} via {@code EffectBindingNode}. No
     * continuation involved.
     *
     * <p>Acquires from a thread-local 1-slot pool when available — every
     * chain-dispatch entry allocates and discards one dispatch fiber,
     * so reusing a single per-thread slot saves an allocation per
     * op-call. The fiber is in clean state when reused
     * (popEffectHandles already clears its slots).
     *
     * <p>Callers MUST pair this with {@link #releaseDispatchOnly(HandlerChainRegistry)}
     * after use. The pool is best-effort: nested dispatch fibers allocate
     * fresh (no recursive pool).
     */
    public static HandlerChainRegistry acquireDispatchOnly() {
        HandlerChainRegistry cached = DISPATCH_POOL.get();
        if (cached != null) {
            DISPATCH_POOL.set(null);
            return cached;
        }
        return new HandlerChainRegistry();
    }

    /** Return a dispatch fiber to the per-thread cache (best-effort). */
    public static void releaseDispatchOnly(HandlerChainRegistry fiber) {
        if (fiber.effectHandleDepth != 0) {
            // Caller didn't pop their pushes — don't pool a dirty fiber.
            return;
        }
        // Most recent goes in cache; older one (if any) is dropped.
        DISPATCH_POOL.set(fiber);
    }

    /** Back-compat wrapper. New callers should use acquire/release. */
    @TruffleBoundary
    public static HandlerChainRegistry createDispatchOnly() {
        return acquireDispatchOnly();
    }

    private static final ThreadLocal<HandlerChainRegistry> DISPATCH_POOL =
        ThreadLocal.withInitial(() -> null);

    public long getId() { return id; }

    // ----------------------------------------------------------------
    // Per-op-invocation effect-handler control-flow handles.
    //
    // Chain dispatch pushes a frame {resume, abort} before invoking an op
    // handler's lambda and pops it after the lambda returns. Op handler
    // body code reads them via EffectBindingIR (kind=RESUME/ABORT), which
    // calls currentResume() / currentAbort() on the dispatch fiber.
    //
    // Nested op calls each push their own frame, so inner ops don't
    // clobber outer ops' handles.
    // ----------------------------------------------------------------

    public void pushEffectHandles(Object resume, Object abort) {
        int needed = (effectHandleDepth + 1) * 2;
        if (needed > effectHandleStack.length) {
            Object[] grown = new Object[effectHandleStack.length * 2];
            System.arraycopy(effectHandleStack, 0, grown, 0, effectHandleStack.length);
            effectHandleStack = grown;
        }
        effectHandleStack[effectHandleDepth * 2] = resume;
        effectHandleStack[effectHandleDepth * 2 + 1] = abort;
        effectHandleDepth++;
    }

    public void popEffectHandles() {
        if (effectHandleDepth == 0) {
            throw new IllegalStateException(
                "HandlerChainRegistry@" + id + ": effect-handle stack underflow");
        }
        effectHandleDepth--;
        effectHandleStack[effectHandleDepth * 2] = null;
        effectHandleStack[effectHandleDepth * 2 + 1] = null;
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
        return "HandlerChainRegistry@" + id + "[dispatch]";
    }
}
