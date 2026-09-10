package com.mezze.interpreter.nodes.continuation;

import com.mezze.interpreter.values.MezzeRecord;
import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.utilities.CyclicAssumption;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-thread state holder for the effect-handler runtime.
 *
 * <p>All per-thread effect state is consolidated in a single
 * {@link EffectState} struct accessed via one {@link ThreadLocal}. The
 * handler chain and D8 handle stack are merged into a single flat array
 * stack — push/pop touch one structure, not two. Scope hooks
 * (initialize_scope / finalize_scope) are separated into dedicated
 * push/pop methods so the common non-scoped path never probes for
 * scope fields.
 *
 * <p>The struct holds:
 * <ul>
 *   <li>Merged handler+D8 stack — flat arrays indexed by depth.</li>
 *   <li>{@code dispatching} — pointer to the dispatch-only registry
 *       whose op handler is currently running (for EffectBindingNode).</li>
 *   <li>{@code dispatchPool} — 1-slot pool of reusable dispatch
 *       registries.</li>
 * </ul>
 *
 * <p>Matching in chain dispatch is by (effect_hash, op_name) so two
 * in-scope effects with the same simple op name don't collide (#246).
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

    // ================================================================
    // Consolidated per-thread effect state — ONE ThreadLocal for all.
    //
    // Handler chain and D8 handle stack are MERGED into flat arrays.
    // Each slot at index i holds: handler record, effect hash, scope
    // value, previous scope, and a BDSL-handle flag.
    //
    // Push = write fields at depth, depth++. Pop = depth--, null fields.
    // Zero heap allocation per push/pop.
    // ================================================================

    /** Initial capacity — effect chains rarely exceed 8. */
    private static final int INITIAL_CAPACITY = 16;

    /** All per-thread effect state in a single struct. */
    static final class EffectState {
        // Merged handler chain + D8 handle stack — parallel flat arrays.
        MezzeRecord[] handlers = new MezzeRecord[INITIAL_CAPACITY];
        com.mezze.language.typedast.Hash[] effectHashes = new com.mezze.language.typedast.Hash[INITIAL_CAPACITY];
        // Scope hook data — only non-null for scoped effects.
        Object[] scopes = new Object[INITIAL_CAPACITY];
        Object[] prevScopes = new Object[INITIAL_CAPACITY];
        int depth = 0;
        // Separate BDSL Handle depth — counts only pushes from BDSL
        // PushHandlerChain* @Operations (not from installHandlerChainEntries
        // on spawned tasks). Used by insideBdslHandle() to distinguish
        // "inside a BDSL Handle catch boundary" from "chain entries installed
        // for chain dispatch on a spawned task."
        int bdslHandleDepth = 0;

        // Current active scope value — the scope returned by the innermost
        // scoped handler's initialize_scope. Read by EffectBindingNode(SCOPE)
        // during op dispatch. Per-thread, so no cross-thread contention.
        Object currentScope;

        HandlerChainRegistry dispatching;
        HandlerChainRegistry dispatchPool;

        /** Grow all parallel arrays when depth reaches capacity. */
        void grow() {
            int newCap = handlers.length * 2;
            MezzeRecord[] h = new MezzeRecord[newCap];
            com.mezze.language.typedast.Hash[] e = new com.mezze.language.typedast.Hash[newCap];
            Object[] s = new Object[newCap];
            Object[] ps = new Object[newCap];
            System.arraycopy(handlers, 0, h, 0, handlers.length);
            System.arraycopy(effectHashes, 0, e, 0, effectHashes.length);
            System.arraycopy(scopes, 0, s, 0, scopes.length);
            System.arraycopy(prevScopes, 0, ps, 0, prevScopes.length);
            handlers = h;
            effectHashes = e;
            scopes = s;
            prevScopes = ps;
        }
    }

    private static final ThreadLocal<EffectState> STATE =
        ThreadLocal.withInitial(EffectState::new);

    /**
     * The fiber whose op handler is currently being dispatched on the
     * driver's stack, or {@code null}. Used by {@code EffectBindingNode} to
     * locate the fiber that owns the resume/abort handle stack.
     */
    @TruffleBoundary
    public static HandlerChainRegistry dispatching() {
        return STATE.get().dispatching;
    }

    /**
     * Mark this fiber as the one being dispatched. Returns the previously
     * pinned fiber so the caller can restore it via {@link #exitDispatch}.
     */
    @TruffleBoundary
    public HandlerChainRegistry enterDispatch() {
        EffectState s = STATE.get();
        HandlerChainRegistry prev = s.dispatching;
        s.dispatching = this;
        return prev;
    }

    /** Counterpart to {@link #enterDispatch()}. */
    @TruffleBoundary
    public static void exitDispatch(HandlerChainRegistry previous) {
        STATE.get().dispatching = previous;
    }

    /**
     * Historical no-op preserved as a call-site hook. See #255.
     */
    public static <T> T runDetached(java.util.function.Supplier<T> body) {
        return body.get();
    }

    // ----------------------------------------------------------------
    // Handler-chain-stable assumptions.
    //
    // Two levels:
    //   1. Per-effect CyclicAssumption — keyed by effect hash. Invalidated
    //      only when a handler for THAT specific effect is pushed/popped.
    //      Used by PerformD and PerformMutMutateAct so that pushing a
    //      Console handler doesn't invalidate cached Mut specializations.
    //   2. Global CyclicAssumption — invalidated on ANY handler push/pop.
    //      Used by EffectBinding (resume/abort singletons depend on the
    //      entire chain being empty, not just one effect's handler).
    // ----------------------------------------------------------------

    private static final CyclicAssumption HANDLER_CHAIN_STABLE =
        new CyclicAssumption("handler-chain-stable");

    private static final java.util.concurrent.ConcurrentHashMap<
            com.mezze.language.typedast.Hash, CyclicAssumption> EFFECT_ASSUMPTIONS =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Global assumption: valid as long as NO handler push/pop has happened.
     * Used by EffectBinding where resume/abort singletons depend on the
     * entire chain state, not a single effect.
     */
    @TruffleBoundary
    public static Assumption getHandlerChainStableAssumption() {
        return HANDLER_CHAIN_STABLE.getAssumption();
    }

    /**
     * Per-effect assumption: valid as long as no handler for THIS specific
     * effect has been pushed or popped. Used by PerformD and
     * PerformMutMutateAct so that unrelated handler changes (e.g. Console)
     * don't invalidate cached Mut or Async dispatch specializations.
     */
    @TruffleBoundary
    public static Assumption getEffectStableAssumption(com.mezze.language.typedast.Hash effectHash) {
        return getOrCreateEffectAssumption(effectHash).getAssumption();
    }

    private static CyclicAssumption getOrCreateEffectAssumption(
            com.mezze.language.typedast.Hash effectHash) {
        CyclicAssumption existing = EFFECT_ASSUMPTIONS.get(effectHash);
        if (existing != null) return existing;
        return EFFECT_ASSUMPTIONS.computeIfAbsent(effectHash,
            h -> new CyclicAssumption("effect-stable-" + h.getHex().substring(0, 8)));
    }

    /** Invalidate both per-effect and global assumptions. */
    private static void invalidateAssumptions(com.mezze.language.typedast.Hash effectHash) {
        HANDLER_CHAIN_STABLE.invalidate();
        if (effectHash != null) {
            CyclicAssumption ea = EFFECT_ASSUMPTIONS.get(effectHash);
            if (ea != null) ea.invalidate();
        }
    }

    // ----------------------------------------------------------------
    // Merged push/pop — handler chain + D8 handle in one flat array.
    //
    // Two variants: simple (no scope hooks — the common case for 13 of
    // 19 stdlib effects) and scoped (with initialize_scope/finalize_scope).
    // The simple path does NO scope work — zero overhead from scope hooks.
    // ----------------------------------------------------------------

    /**
     * Simple push (non-scoped effects): handler + effectHash only.
     * No scope hook check, no scope allocation. Used by BDSL
     * PushHandlerChainSimple @Operation.
     */
    @TruffleBoundary
    public static void pushSimple(
            MezzeRecord handler,
            com.mezze.language.typedast.Hash effectHash) {
        EffectState s = STATE.get();
        int d = s.depth;
        if (d >= s.handlers.length) s.grow();
        s.handlers[d] = handler;
        s.effectHashes[d] = effectHash;
        // scopes[d] and prevScopes[d] stay null — no scope hooks.
        s.depth = d + 1;
        s.bdslHandleDepth++;
        invalidateAssumptions(effectHash);
    }

    /**
     * Simple pop (non-scoped effects): just decrement depth and null
     * the slot. No scope cleanup, no return value. Used by BDSL
     * PopHandlerChainSimple @Operation.
     */
    @TruffleBoundary
    public static void popSimple() {
        EffectState s = STATE.get();
        int d = s.depth - 1;
        com.mezze.language.typedast.Hash effectHash = s.effectHashes[d];
        s.handlers[d] = null;
        s.effectHashes[d] = null;
        s.depth = d;
        s.bdslHandleDepth--;
        invalidateAssumptions(effectHash);
    }

    /**
     * Scoped push: handler + effectHash + scope hooks. Stores scope
     * and prevScope for the paired popScoped() to run finalize_scope.
     * Used by BDSL PushHandlerChainScoped @Operation.
     */
    @TruffleBoundary
    public static void pushScoped(
            MezzeRecord handler,
            com.mezze.language.typedast.Hash effectHash,
            Object scope, Object prevScope) {
        EffectState s = STATE.get();
        int d = s.depth;
        if (d >= s.handlers.length) s.grow();
        s.handlers[d] = handler;
        s.effectHashes[d] = effectHash;
        s.scopes[d] = scope;
        s.prevScopes[d] = prevScope;
        s.depth = d + 1;
        s.bdslHandleDepth++;
        invalidateAssumptions(effectHash);
    }

    /**
     * Result of {@link #popScoped()} — carries the handler record, scope
     * value, and previous scope that PopHandlerChainScoped needs.
     * Only allocated for scoped effects (6 of 19 stdlib effects).
     */
    public static final class ScopedPopResult {
        public final MezzeRecord handler;
        public final Object scope;
        public final Object prevScope;
        ScopedPopResult(MezzeRecord handler, Object scope, Object prevScope) {
            this.handler = handler;
            this.scope = scope;
            this.prevScope = prevScope;
        }
    }

    /**
     * Scoped pop: returns handler + scope data for finalize_scope
     * invocation. Used by BDSL PopHandlerChainScoped @Operation.
     */
    @TruffleBoundary
    public static ScopedPopResult popScoped() {
        EffectState s = STATE.get();
        int d = s.depth - 1;
        MezzeRecord handler = s.handlers[d];
        com.mezze.language.typedast.Hash effectHash = s.effectHashes[d];
        Object scope = s.scopes[d];
        Object prevScope = s.prevScopes[d];
        s.handlers[d] = null;
        s.effectHashes[d] = null;
        s.scopes[d] = null;
        s.prevScopes[d] = null;
        s.depth = d;
        s.bdslHandleDepth--;
        invalidateAssumptions(effectHash);
        return new ScopedPopResult(handler, scope, prevScope);
    }

    // ----------------------------------------------------------------
    // Chain-only push/pop — for non-BDSL callers (MezzeSuspend.force,
    // installHandlerChainEntries, tests, YieldDispatchTest).
    // Does NOT increment bdslHandleDepth because there is no BDSL
    // Handle catch boundary on these call paths.
    // ----------------------------------------------------------------

    @TruffleBoundary
    public static void pushHandlerOnChain(
            MezzeRecord handler,
            com.mezze.language.typedast.Hash effectHash) {
        pushChainOnly(handler, effectHash);
    }

    @TruffleBoundary
    public static void popHandlerOffChain() {
        popChainOnly();
    }

    /** Push handler on chain WITHOUT marking a BDSL Handle boundary. */
    private static void pushChainOnly(
            MezzeRecord handler,
            com.mezze.language.typedast.Hash effectHash) {
        EffectState s = STATE.get();
        int d = s.depth;
        if (d >= s.handlers.length) s.grow();
        s.handlers[d] = handler;
        s.effectHashes[d] = effectHash;
        s.depth = d + 1;
        invalidateAssumptions(effectHash);
    }

    /** Pop handler from chain WITHOUT decrementing bdslHandleDepth. */
    private static void popChainOnly() {
        EffectState s = STATE.get();
        int d = s.depth - 1;
        com.mezze.language.typedast.Hash effectHash = s.effectHashes[d];
        s.handlers[d] = null;
        s.effectHashes[d] = null;
        s.depth = d;
        invalidateAssumptions(effectHash);
    }

    @TruffleBoundary
    public static int handlerChainDepth() {
        return STATE.get().depth;
    }

    // ----------------------------------------------------------------
    // Combined push/pop — backwards compat for any callers that used
    // the old pushHandlerAndEnterHandle / popHandlerAndExitHandle API.
    // Now simply delegates to pushScoped/popScoped (or pushSimple for
    // the non-scoped case).
    // ----------------------------------------------------------------

    /**
     * Combined push (legacy API — used by legacy PushHandlerChain).
     * Delegates to pushScoped or pushSimple. Both increment bdslHandleDepth.
     */
    @TruffleBoundary
    public static void pushHandlerAndEnterHandle(
            MezzeRecord handler,
            com.mezze.language.typedast.Hash effectHash,
            Object scope, Object prevScope) {
        if (scope != null || prevScope != null) {
            pushScoped(handler, effectHash, scope, prevScope);
        } else {
            pushSimple(handler, effectHash);
        }
    }

    /**
     * Legacy PopResult — kept for any callers using the old API.
     */
    public static final class PopResult {
        public final MezzeRecord handler;
        public final BdslHandleFrame scopeFrame;
        PopResult(MezzeRecord handler, BdslHandleFrame scopeFrame) {
            this.handler = handler;
            this.scopeFrame = scopeFrame;
        }
    }

    /** Per-Handle scope-hook frame. Kept as legacy type for PopResult. */
    public static final class BdslHandleFrame {
        public final Object scope;
        public final Object prevScope;
        public BdslHandleFrame(Object scope, Object prevScope) {
            this.scope = scope;
            this.prevScope = prevScope;
        }
    }

    /**
     * Combined pop (legacy API). Translates to new stack format.
     */
    @TruffleBoundary
    public static PopResult popHandlerAndExitHandle() {
        EffectState s = STATE.get();
        int d = s.depth - 1;
        MezzeRecord handler = s.handlers[d];
        com.mezze.language.typedast.Hash effectHash = s.effectHashes[d];
        Object scope = s.scopes[d];
        Object prevScope = s.prevScopes[d];
        s.handlers[d] = null;
        s.effectHashes[d] = null;
        s.scopes[d] = null;
        s.prevScopes[d] = null;
        s.depth = d;
        s.bdslHandleDepth--;
        invalidateAssumptions(effectHash);
        BdslHandleFrame frame = (scope != null || prevScope != null)
            ? new BdslHandleFrame(scope, prevScope) : null;
        return new PopResult(handler, frame);
    }

    /**
     * Whether the current thread is inside a BDSL Handle boundary
     * (i.e. a TryCatchOtherwise catch site exists on the call stack
     * that will catch AbortPerformException). Distinguished from chain
     * entries installed via installHandlerChainEntries on spawned tasks
     * which have no catch site.
     */
    @TruffleBoundary
    public static boolean insideBdslHandle() {
        return STATE.get().bdslHandleDepth > 0;
    }

    // ----------------------------------------------------------------
    // Scope variable — direct field on EffectState (replaces the old
    // ConcurrentHashMap<String,Object> on MezzeContext). Only one key
    // ("scope") was ever used; a plain field is zero-cost.
    // ----------------------------------------------------------------

    /**
     * Install a new scope value, returning the previous one so the
     * caller can restore it on pop. Called by PushHandlerChainScoped
     * and EffectHandlerDriver.installScope.
     */
    @TruffleBoundary
    public static Object installScopeVar(Object scope) {
        if (scope == null) return null;
        EffectState s = STATE.get();
        Object prev = s.currentScope;
        s.currentScope = scope;
        return prev;
    }

    /**
     * Restore a previous scope value. Called by PopHandlerChainScoped
     * and EffectHandlerDriver.restoreScope.
     */
    @TruffleBoundary
    public static void restoreScopeVar(Object prev) {
        STATE.get().currentScope = prev;
    }

    /**
     * Read the current scope value. Called by EffectBindingNode(SCOPE)
     * during op dispatch.
     */
    @TruffleBoundary
    public static Object lookupScopeVar() {
        return STATE.get().currentScope;
    }

    // ----------------------------------------------------------------
    // Chain lookup, truncation, snapshot, install.
    // ----------------------------------------------------------------

    /**
     * Allocation-free lookup of the innermost handler whose effect-hash
     * matches {@code effectHash}, or {@code null} if none in scope.
     */
    @TruffleBoundary
    public static MezzeRecord peekHandlerForEffect(
            com.mezze.language.typedast.Hash effectHash) {
        EffectState s = STATE.get();
        // Walk from top of stack (innermost handler) outward.
        for (int i = s.depth - 1; i >= 0; i--) {
            com.mezze.language.typedast.Hash eh = s.effectHashes[i];
            if (eh != null && eh.equals(effectHash)) {
                return s.handlers[i];
            }
        }
        return null;
    }

    @TruffleBoundary
    public static MezzeRecord peekTopHandlerRecord() {
        EffectState s = STATE.get();
        if (s.depth == 0) return null;
        return s.handlers[s.depth - 1];
    }

    @TruffleBoundary
    public static void truncateHandlerChainTo(int depth) {
        EffectState s = STATE.get();
        while (s.depth > depth) {
            int d = s.depth - 1;
            s.handlers[d] = null;
            s.effectHashes[d] = null;
            s.scopes[d] = null;
            s.prevScopes[d] = null;
            s.depth = d;
        }
    }

    private static final HandlerChainEntry[] EMPTY_ENTRY_CHAIN = new HandlerChainEntry[0];

    @TruffleBoundary
    public static HandlerChainEntry[] snapshotHandlerChainEntries() {
        EffectState s = STATE.get();
        int size = s.depth;
        if (size == 0) return EMPTY_ENTRY_CHAIN;
        // Snapshot in innermost-first order (matches old ArrayDeque
        // convention): snapshot[0] = innermost (top of stack),
        // snapshot[size-1] = outermost (bottom of stack).
        HandlerChainEntry[] snapshot = new HandlerChainEntry[size];
        for (int i = 0; i < size; i++) {
            snapshot[i] = new HandlerChainEntry(
                s.handlers[size - 1 - i], s.effectHashes[size - 1 - i]);
        }
        return snapshot;
    }

    @TruffleBoundary
    public static void installHandlerChainEntries(HandlerChainEntry[] chain) {
        // chain is innermost-first. Install in reverse so outermost
        // goes at lower depth index, innermost at higher (top).
        // Uses pushChainOnly — no bdslHandleDepth increment, since
        // these are chain entries from a parent thread, not BDSL
        // Handle catch boundaries on THIS thread.
        for (int i = chain.length - 1; i >= 0; i--) {
            pushChainOnly(chain[i].handler(), chain[i].effectHash());
        }
    }

    @TruffleBoundary
    public static void clearHandlerChain() {
        EffectState s = STATE.get();
        for (int i = 0; i < s.depth; i++) {
            s.handlers[i] = null;
            s.effectHashes[i] = null;
            s.scopes[i] = null;
            s.prevScopes[i] = null;
        }
        s.depth = 0;
        s.bdslHandleDepth = 0;
        s.currentScope = null;
    }

    @TruffleBoundary
    public static void popHandlerChain(int count) {
        if (count <= 0) return;
        EffectState s = STATE.get();
        for (int i = 0; i < count && s.depth > 0; i++) {
            int d = s.depth - 1;
            s.handlers[d] = null;
            s.effectHashes[d] = null;
            s.scopes[d] = null;
            s.prevScopes[d] = null;
            s.depth = d;
        }
    }

    public static void runWithHandlerChain(HandlerChainEntry[] chain, Runnable body) {
        runWithHandlerChain(chain, null, body);
    }

    /**
     * Install handler chain and optional scope for the duration of {@code body}.
     * The scope is set before the body runs and restored after.
     */
    public static void runWithHandlerChain(HandlerChainEntry[] chain, Object scope, Runnable body) {
        if (chain.length == 0 && scope == null) {
            body.run();
            return;
        }
        EffectState s = STATE.get();
        Object prevScope = s.currentScope;
        if (scope != null) s.currentScope = scope;
        if (chain.length > 0) installHandlerChainEntries(chain);
        try {
            body.run();
        } finally {
            if (chain.length > 0) popHandlerChain(chain.length);
            s.currentScope = prevScope;
        }
    }

    private HandlerChainRegistry() {}

    // ----------------------------------------------------------------
    // Dispatch-only fiber pool.
    // ----------------------------------------------------------------

    @TruffleBoundary
    public static HandlerChainRegistry acquireDispatchOnly() {
        EffectState s = STATE.get();
        HandlerChainRegistry cached = s.dispatchPool;
        if (cached != null) {
            s.dispatchPool = null;
            return cached;
        }
        return new HandlerChainRegistry();
    }

    @TruffleBoundary
    public static void releaseDispatchOnly(HandlerChainRegistry fiber) {
        if (fiber.effectHandleDepth != 0) {
            return;
        }
        STATE.get().dispatchPool = fiber;
    }

    @TruffleBoundary
    public static HandlerChainRegistry createDispatchOnly() {
        return acquireDispatchOnly();
    }

    public long getId() { return id; }

    // ----------------------------------------------------------------
    // Per-op-invocation effect-handler control-flow handles.
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
