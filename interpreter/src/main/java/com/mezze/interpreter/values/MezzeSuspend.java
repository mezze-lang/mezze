package com.mezze.interpreter.values;

import com.mezze.language.typedast.Hash;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.nodes.IndirectCallNode;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Suspended computation - a deferred effect call waiting for handlers.
 *
 * This implements the lazy effects model where:
 * - Inside perform: effects execute immediately (hot path)
 * - Outside perform: effects return MezzeSuspend (cold path)
 *
 * The suspended computation can later be executed with perform,
 * which provides the missing handlers.
 *
 * Example:
 *   let result = myEffectfulFunc {}  // Returns MezzeSuspend with unhandled effects
 *   perform Console in result        // Executes with Console handler
 */
public final class MezzeSuspend implements TruffleObject {

    // ----- Op-defer shape (legacy). Discriminator: `computation != null`.
    // force() dispatches the deferred op-call on the current fiber (or
    // walks the handler chain if there is no fiber). Slated for deletion
    // under #341 once the remaining producers (OpCallNode,
    // EffectHandlerDriver, YieldDispatch) are rewired to a
    // continuation-capturing shape.
    @CompilationFinal
    private final MezzeThunk computation;

    // ----- Path E (task #336) shape — full body continuation.
    // Discriminator: `bodyNode != null` → new shape. force() runs bodyNode.
    // See docs/perf/effect-lazy-redesign-2026-07.md.
    @CompilationFinal
    private final com.mezze.interpreter.nodes.MezzeNode bodyNode;
    @CompilationFinal
    private final com.oracle.truffle.api.frame.MaterializedFrame bodyFrame;
    @CompilationFinal(dimensions = 1)
    private final com.mezze.interpreter.nodes.continuation.HandlerChainEntry[] chainSnapshot;

    @CompilationFinal(dimensions = 1)
    private final Hash[] unhandledEffects;

    // Cached set view for faster contains checks
    @CompilationFinal
    private Set<Hash> unhandledEffectsSet;

    public MezzeSuspend(MezzeThunk computation, Set<Hash> unhandledEffects) {
        this.computation = computation;
        this.bodyNode = null;
        this.bodyFrame = null;
        this.chainSnapshot = null;
        this.unhandledEffects = unhandledEffects.toArray(new Hash[0]);
    }

    /**
     * Path E constructor — capture a full body continuation instead of a
     * single deferred op-call. Body doesn't run; execution defers until
     * {@link #force(IndirectCallNode)} sees all effects in
     * {@code unhandledEffects} discharged by the current chain.
     *
     * <p>{@code chainSnapshot} = handlers already installed at capture time
     * (the enclosing BDSL Handle @Operation's PushHandlerChain). Force
     * merges these with the chain live at force time.
     */
    public static MezzeSuspend captureContinuation(
            com.mezze.interpreter.nodes.MezzeNode bodyNode,
            com.oracle.truffle.api.frame.MaterializedFrame bodyFrame,
            com.mezze.interpreter.nodes.continuation.HandlerChainEntry[] chainSnapshot,
            Set<Hash> unhandledEffects) {
        return new MezzeSuspend(bodyNode, bodyFrame, chainSnapshot, unhandledEffects);
    }

    private MezzeSuspend(
            com.mezze.interpreter.nodes.MezzeNode bodyNode,
            com.oracle.truffle.api.frame.MaterializedFrame bodyFrame,
            com.mezze.interpreter.nodes.continuation.HandlerChainEntry[] chainSnapshot,
            Set<Hash> unhandledEffects) {
        this.computation = null;
        this.bodyNode = bodyNode;
        this.bodyFrame = bodyFrame;
        this.chainSnapshot = chainSnapshot;
        this.unhandledEffects = unhandledEffects.toArray(new Hash[0]);
    }

    /** True when this value carries a Path E full-body continuation
     *  (as opposed to the legacy single-op MezzeThunk shape). */
    public boolean isPathE() {
        return bodyNode != null;
    }

    public com.mezze.interpreter.nodes.MezzeNode getBodyNode() {
        return bodyNode;
    }

    public com.oracle.truffle.api.frame.MaterializedFrame getBodyFrame() {
        return bodyFrame;
    }

    public com.mezze.interpreter.nodes.continuation.HandlerChainEntry[] getChainSnapshot() {
        return chainSnapshot;
    }

    public Set<Hash> getUnhandledEffects() {
        if (unhandledEffectsSet == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            unhandledEffectsSet = new HashSet<>();
            for (Hash effect : unhandledEffects) {
                unhandledEffectsSet.add(effect);
            }
            unhandledEffectsSet = Collections.unmodifiableSet(unhandledEffectsSet);
        }
        return unhandledEffectsSet;
    }

    public boolean hasUnhandledEffect(Hash effectHash) {
        for (Hash effect : unhandledEffects) {
            if (effect.equals(effectHash)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if all effects are handled (empty unhandled set).
     */
    public boolean isFullyHandled() {
        return unhandledEffects.length == 0;
    }

    /**
     * Force this suspended computation under the currently-active effect
     * handler chain, if any.
     *
     * <p>Effect-handler lookup happens via the per-thread handler chain
     * ({@link HandlerChainRegistry#peekHandlerForEffect}), not via a context-wide
     * handler map. Forcing a deferred effect call re-issues the op
     * through {@link com.mezze.interpreter.bytecode.YieldDispatch};
     * an enclosing BDSL Handle @Operation catches abort as usual.
     *
     * <p>If no fiber is current (we're still outside any {@code perform}),
     * returns {@code this} so the suspension persists.
     */
    @TruffleBoundary
    public Object force(IndirectCallNode callNode) {
        // Path E (#336): full-body continuation. Merge captured chain with
        // this thread's live chain, install temporarily, execute bodyNode
        // on the captured frame. If body completes → ground value. If body
        // aborts → return abort value. If body re-suspends via another
        // unhandled op → captureContinuation for the next suspend point
        // (handled naturally by the enclosing BDSL Handle @Operation when
        // the resumed body reaches another perform without a matching
        // handler).
        if (bodyNode != null) {
            return forcePathE(callNode);
        }
        // MezzeThunk force always goes through the chain-dispatch path.
        // If the thunk is a deferred effect call and a chain is installed
        // on this thread (e.g. an Async.spawn body inherited the parent's
        // chain), the dispatcher routes it there; otherwise `this`
        // bubbles back unforced.
        return forceWithoutFiber(callNode);
    }

    /**
     * Path E (#336) atomic force. Installs the captured chainSnapshot on
     * this thread's HandlerChain, executes bodyNode on the captured
     * materialized frame, restores chain on exit.
     *
     * <p>Body may:
     * <ul>
     *   <li>Complete normally → return ground value.</li>
     *   <li>Fire {@code abort {val = X}} in a matched op impl →
     *       {@link com.mezze.interpreter.nodes.builtin.EffectHandlerDriver.AbortPerformException}
     *       caught here, return the abort value.</li>
     *   <li>Perform an op with no matching handler → nested MezzeSuspend
     *       returned by an inner BDSL Handle @Operation (or MezzeSuspend
     *       propagated up from YieldDispatch's makeUnhandledSuspended).
     *       Force returns that, preserving lazy semantics for still-
     *       unhandled effects.</li>
     * </ul>
     *
     * <p>Same-thread only: bodyFrame carries slot references specific to
     * the thread it was materialized on. Cross-thread force is undefined
     * under this initial cut; Phase 3 will decide the cross-thread policy.
     */
    @TruffleBoundary
    private Object forcePathE(IndirectCallNode callNode) {
        int depthBefore =
            com.mezze.interpreter.nodes.continuation.HandlerChainRegistry.handlerChainDepth();
        // Push captured chain entries in reverse — chainSnapshot[0] was
        // the innermost at capture, so we want it pushed LAST so it ends
        // up on top of the deque.
        for (int i = chainSnapshot.length - 1; i >= 0; i--) {
            com.mezze.interpreter.nodes.continuation.HandlerChainEntry entry = chainSnapshot[i];
            com.mezze.interpreter.nodes.continuation.HandlerChainRegistry.pushHandlerOnChain(
                entry.handler(), entry.effectHash());
        }
        try {
            Object out = bodyNode.execute(bodyFrame);
            // Cascade: if body execution produced another lazy MezzeSuspend
            // (from an inner nested Handle further down that also deferred),
            // force it now under the same installed chain. Loop rather than
            // recurse to avoid stack growth for deeply-nested `perform` chains.
            while (out instanceof MezzeSuspend nested && nested.isPathE()) {
                out = nested.forcePathE(callNode);
            }
            return out;
        } catch (com.mezze.interpreter.nodes.builtin.EffectHandlerDriver.AbortPerformException abort) {
            return abort.value;
        } finally {
            com.mezze.interpreter.nodes.continuation.HandlerChainRegistry.truncateHandlerChainTo(depthBefore);
        }
    }

    @TruffleBoundary
    private Object forceWithoutFiber(IndirectCallNode callNode) {
        com.mezze.interpreter.nodes.continuation.HandlerChainEntry[] chain =
            com.mezze.interpreter.nodes.continuation.HandlerChainRegistry.snapshotHandlerChainEntries();
        if (chain.length == 0) {
            return this;
        }
        Object result = com.mezze.interpreter.nodes.effect.OpCallNode.dispatchOpOnChain(
            chain,
            computation.getOperationName(),
            computation.getArguments(),
            computation.getEffectContentHash(),
            callNode
        );
        if (result == com.mezze.interpreter.nodes.effect.OpCallNode.DISPATCH_NO_MATCH) {
            return this;
        }
        return result;
    }

    /**
     * Check if a value is a suspended computation.
     */
    public static boolean isSuspended(Object value) {
        return value instanceof MezzeSuspend;
    }

    @Override
    @TruffleBoundary
    public String toString() {
        StringBuilder sb = new StringBuilder("<suspended <");
        for (int i = 0; i < unhandledEffects.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(unhandledEffects[i].getHex());
        }
        sb.append(">>");
        return sb.toString();
    }
}
