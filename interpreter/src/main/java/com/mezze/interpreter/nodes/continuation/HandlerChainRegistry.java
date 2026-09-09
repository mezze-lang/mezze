package com.mezze.interpreter.nodes.continuation;

import com.mezze.interpreter.values.MezzeRecord;
import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.utilities.CyclicAssumption;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-thread state holder for the effect-handler runtime.
 *
 * <p>All per-thread effect state is consolidated in a single
 * {@link EffectState} struct accessed via one {@link ThreadLocal}. This
 * eliminates redundant ThreadLocal.get() calls when multiple pieces of
 * effect state are accessed in the same @TruffleBoundary method (e.g.
 * handler chain + D8 handle stack during push/pop). Each ThreadLocal.get()
 * costs ~200ns; merging 4 ThreadLocals into 1 saves ~800ns per perform
 * round-trip.
 *
 * <p>The struct holds:
 * <ul>
 *   <li>{@code handlerChain} — stack of (handler record, effect hash)
 *       pairs pushed by every {@code perform Handler in body}.</li>
 *   <li>{@code d8HandleStack} — BDSL Handle depth tracking, distinct
 *       from handlerChain so snapshot-install on spawned threads doesn't
 *       create phantom catch boundaries.</li>
 *   <li>{@code dispatching} — pointer to the dispatch-only registry
 *       whose op handler is currently running (for EffectBindingNode).</li>
 *   <li>{@code dispatchPool} — 1-slot pool of reusable dispatch
 *       registries.</li>
 * </ul>
 *
 * <p>Additionally, combined methods ({@link #pushHandlerAndEnterHandle},
 * {@link #popHandlerAndExitHandle}) do both chain + D8 stack operations
 * in a single ThreadLocal.get(), used by the BDSL PushHandlerChain /
 * PopHandlerChain @Operations.
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
    // ================================================================

    /** All per-thread effect state in a single struct. */
    static final class EffectState {
        final ArrayDeque<HandlerChainEntry> handlerChain = new ArrayDeque<>();
        final ArrayDeque<BdslHandleFrame> d8HandleStack = new ArrayDeque<>();
        HandlerChainRegistry dispatching;
        HandlerChainRegistry dispatchPool;
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
    // Handler-chain-stable assumption.
    // ----------------------------------------------------------------

    private static final CyclicAssumption HANDLER_CHAIN_STABLE =
        new CyclicAssumption("handler-chain-stable");

    @TruffleBoundary
    public static Assumption getHandlerChainStableAssumption() {
        return HANDLER_CHAIN_STABLE.getAssumption();
    }

    // ----------------------------------------------------------------
    // Per-thread handler chain — individual operations.
    // ----------------------------------------------------------------

    @TruffleBoundary
    public static void pushHandlerOnChain(
            com.mezze.interpreter.values.MezzeRecord handler,
            com.mezze.language.typedast.Hash effectHash) {
        STATE.get().handlerChain.push(new HandlerChainEntry(handler, effectHash));
        HANDLER_CHAIN_STABLE.invalidate();
    }

    @TruffleBoundary
    public static void popHandlerOffChain() {
        STATE.get().handlerChain.pop();
        HANDLER_CHAIN_STABLE.invalidate();
    }

    @TruffleBoundary
    public static int handlerChainDepth() {
        return STATE.get().handlerChain.size();
    }

    // ----------------------------------------------------------------
    // D8 BDSL Handle depth — individual operations.
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

    /** Sentinel pushed for handlers without scope hooks — avoids nullable
     *  ArrayDeque entries. Identity-compared, never dereferenced. */
    private static final BdslHandleFrame NO_SCOPE_FRAME = new BdslHandleFrame(null, null);

    @TruffleBoundary
    public static void enterBdslHandle() {
        STATE.get().d8HandleStack.push(NO_SCOPE_FRAME);
    }

    @TruffleBoundary
    public static void enterBdslHandleWithScope(Object scope, Object prevScope) {
        STATE.get().d8HandleStack.push(
            scope == null && prevScope == null ? NO_SCOPE_FRAME
                : new BdslHandleFrame(scope, prevScope));
    }

    @TruffleBoundary
    public static void exitBdslHandle() {
        STATE.get().d8HandleStack.pop();
    }

    @TruffleBoundary
    public static BdslHandleFrame peekBdslHandleScope() {
        var s = STATE.get().d8HandleStack;
        if (s.isEmpty()) return null;
        BdslHandleFrame top = s.peek();
        return top == NO_SCOPE_FRAME ? null : top;
    }

    @TruffleBoundary
    public static com.mezze.interpreter.values.MezzeRecord peekTopHandlerRecord() {
        var chain = STATE.get().handlerChain;
        if (chain.isEmpty()) return null;
        HandlerChainEntry entry = chain.peek();
        return entry == null ? null : entry.handler();
    }

    @TruffleBoundary
    public static boolean insideBdslHandle() {
        return !STATE.get().d8HandleStack.isEmpty();
    }

    // ----------------------------------------------------------------
    // Combined push/pop — single ThreadLocal.get() for both stacks.
    //
    // Called by BDSL PushHandlerChain / PopHandlerChain @Operations
    // which always touch both handler chain and D8 handle stack.
    // Saves one ThreadLocal.get() per push and ~3 per pop vs calling
    // the individual methods.
    // ----------------------------------------------------------------

    /**
     * Combined push: handler chain entry + D8 handle scope frame in one
     * ThreadLocal.get(). Used by BDSL PushHandlerChain @Operation.
     */
    @TruffleBoundary
    public static void pushHandlerAndEnterHandle(
            com.mezze.interpreter.values.MezzeRecord handler,
            com.mezze.language.typedast.Hash effectHash,
            Object scope, Object prevScope) {
        EffectState s = STATE.get();
        s.handlerChain.push(new HandlerChainEntry(handler, effectHash));
        s.d8HandleStack.push(
            scope == null && prevScope == null ? NO_SCOPE_FRAME
                : new BdslHandleFrame(scope, prevScope));
        HANDLER_CHAIN_STABLE.invalidate();
    }

    /**
     * Result of {@link #popHandlerAndExitHandle()} — carries the handler
     * record and scope frame that PopHandlerChain needs for finalize_scope.
     */
    public static final class PopResult {
        public final com.mezze.interpreter.values.MezzeRecord handler;
        public final BdslHandleFrame scopeFrame;
        PopResult(com.mezze.interpreter.values.MezzeRecord handler, BdslHandleFrame scopeFrame) {
            this.handler = handler;
            this.scopeFrame = scopeFrame;
        }
    }

    /**
     * Combined pop: peek both stacks (for finalize_scope), then pop both,
     * invalidate assumption — all in one ThreadLocal.get(). Used by BDSL
     * PopHandlerChain @Operation.
     *
     * @return handler + scope frame for finalize_scope invocation, or
     *         null handler / null frame when the stack is empty or has
     *         no scope hooks.
     */
    @TruffleBoundary
    public static PopResult popHandlerAndExitHandle() {
        EffectState s = STATE.get();
        // Peek before pop — need handler for finalize_scope.
        var chain = s.handlerChain;
        com.mezze.interpreter.values.MezzeRecord handler = null;
        if (!chain.isEmpty()) {
            HandlerChainEntry entry = chain.peek();
            if (entry != null) handler = entry.handler();
        }
        var d8 = s.d8HandleStack;
        BdslHandleFrame frame = null;
        if (!d8.isEmpty()) {
            BdslHandleFrame top = d8.peek();
            if (top != NO_SCOPE_FRAME) frame = top;
        }
        // Pop both.
        chain.pop();
        d8.pop();
        HANDLER_CHAIN_STABLE.invalidate();
        return new PopResult(handler, frame);
    }

    // ----------------------------------------------------------------
    // Chain truncation, snapshot, install.
    // ----------------------------------------------------------------

    @TruffleBoundary
    public static void truncateHandlerChainTo(int depth) {
        var chain = STATE.get().handlerChain;
        while (chain.size() > depth) chain.pop();
    }

    private static final HandlerChainEntry[] EMPTY_ENTRY_CHAIN = new HandlerChainEntry[0];

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public static HandlerChainEntry[] snapshotHandlerChainEntries() {
        ArrayDeque<HandlerChainEntry> chain = STATE.get().handlerChain;
        int size = chain.size();
        if (size == 0) return EMPTY_ENTRY_CHAIN;
        return chain.toArray(new HandlerChainEntry[size]);
    }

    /**
     * Allocation-free lookup of the innermost handler whose effect-hash
     * matches {@code effectHash}, or {@code null} if none in scope.
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public static com.mezze.interpreter.values.MezzeRecord peekHandlerForEffect(
            com.mezze.language.typedast.Hash effectHash) {
        ArrayDeque<HandlerChainEntry> chain = STATE.get().handlerChain;
        if (chain.isEmpty()) return null;
        HandlerChainEntry head = chain.peek();
        if (head != null) {
            com.mezze.language.typedast.Hash eh = head.effectHash();
            if (eh != null && eh.equals(effectHash)) {
                return head.handler();
            }
        }
        for (HandlerChainEntry entry : chain) {
            if (entry == head) continue;
            com.mezze.language.typedast.Hash eh = entry.effectHash();
            if (eh != null && eh.equals(effectHash)) {
                return entry.handler();
            }
        }
        return null;
    }

    @TruffleBoundary
    public static void installHandlerChainEntries(HandlerChainEntry[] chain) {
        ArrayDeque<HandlerChainEntry> stack = STATE.get().handlerChain;
        for (int i = chain.length - 1; i >= 0; i--) {
            stack.push(chain[i]);
        }
    }

    @TruffleBoundary
    public static void clearHandlerChain() {
        STATE.get().handlerChain.clear();
    }

    @TruffleBoundary
    public static void popHandlerChain(int count) {
        if (count <= 0) return;
        ArrayDeque<HandlerChainEntry> stack = STATE.get().handlerChain;
        for (int i = 0; i < count && !stack.isEmpty(); i++) {
            stack.pop();
        }
    }

    public static void runWithHandlerChain(HandlerChainEntry[] chain, Runnable body) {
        if (chain.length == 0) {
            body.run();
            return;
        }
        installHandlerChainEntries(chain);
        try {
            body.run();
        } finally {
            popHandlerChain(chain.length);
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
