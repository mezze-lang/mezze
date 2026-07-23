package com.mezze.interpreter.values;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.nodes.control.BlockNode;
import com.mezze.interpreter.runtime.MezzeContext;
import com.mezze.language.typedast.Hash;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.nodes.IndirectCallNode;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Suspended computation - a deferred effect call waiting for handlers.
 *
 * This implements the lazy effects model where:
 * - Inside perform: effects execute immediately (hot path)
 * - Outside perform: effects return MezzeSuspended (cold path)
 *
 * The suspended computation can later be executed with perform,
 * which provides the missing handlers.
 *
 * Example:
 *   let result = myEffectfulFunc {}  // Returns MezzeSuspended with unhandled effects
 *   perform Console in result        // Executes with Console handler
 */
public final class MezzeSuspended implements TruffleObject {

    // ----- Legacy shape (pre-Path-E, still active when MEZZE_LAZY_EFFECTS is off).
    // Discriminator: `computation != null` → legacy shape. force() dispatches
    // via MezzeThunk (single-op deferred call or ComposedBlockThunk).

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

    // Handlers already captured (for partial handling — legacy shape only).
    private final Map<Hash, Object> capturedHandlers;

    public MezzeSuspended(MezzeThunk computation, Set<Hash> unhandledEffects) {
        this(computation, unhandledEffects, Collections.emptyMap());
    }

    public MezzeSuspended(MezzeThunk computation, Set<Hash> unhandledEffects, Map<Hash, Object> capturedHandlers) {
        this.computation = computation;
        this.bodyNode = null;
        this.bodyFrame = null;
        this.chainSnapshot = null;
        this.unhandledEffects = unhandledEffects.toArray(new Hash[0]);
        this.capturedHandlers = capturedHandlers;
    }

    /**
     * Path E constructor — capture a full body continuation instead of a
     * single deferred op-call. Body doesn't run; execution defers until
     * {@link #force(IndirectCallNode)} sees all effects in
     * {@code unhandledEffects} discharged by the current chain.
     *
     * <p>{@code chainSnapshot} = handlers already installed at capture time
     * (the enclosing HandleNode's push). Force merges these with the chain
     * live at force time.
     */
    public static MezzeSuspended captureContinuation(
            com.mezze.interpreter.nodes.MezzeNode bodyNode,
            com.oracle.truffle.api.frame.MaterializedFrame bodyFrame,
            com.mezze.interpreter.nodes.continuation.HandlerChainEntry[] chainSnapshot,
            Set<Hash> unhandledEffects) {
        return new MezzeSuspended(bodyNode, bodyFrame, chainSnapshot, unhandledEffects);
    }

    private MezzeSuspended(
            com.mezze.interpreter.nodes.MezzeNode bodyNode,
            com.oracle.truffle.api.frame.MaterializedFrame bodyFrame,
            com.mezze.interpreter.nodes.continuation.HandlerChainEntry[] chainSnapshot,
            Set<Hash> unhandledEffects) {
        this.computation = null;
        this.bodyNode = bodyNode;
        this.bodyFrame = bodyFrame;
        this.chainSnapshot = chainSnapshot;
        this.unhandledEffects = unhandledEffects.toArray(new Hash[0]);
        this.capturedHandlers = Collections.emptyMap();
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

    public MezzeThunk getComputation() {
        return computation;
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

    public Map<Hash, Object> getCapturedHandlers() {
        return capturedHandlers;
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
     * Get the effect name from the underlying thunk.
     */
    public String getEffectName() {
        return computation.getEffectName();
    }

    /**
     * Get the operation name from the underlying thunk.
     */
    public String getOperationName() {
        return computation.getOperationName();
    }

    /**
     * Force this suspended computation under the currently-active effect
     * handler chain, if any.
     *
     * <p>Under the new dispatch design (post-rewrite), effect-handler
     * lookup happens via the current fiber, not via a context-wide handler
     * map. So forcing a deferred effect call just re-issues the op on the
     * current fiber: {@code fiber.suspend(new OpCall(opName, args))}.
     * The surrounding {@code HandleNode}'s driver dispatches it like any
     * other op call.
     *
     * <p>If no fiber is current (we're still outside any {@code perform}),
     * returns {@code this} so the suspension persists.
     */
    @TruffleBoundary
    public Object force(IndirectCallNode callNode) {
        com.mezze.interpreter.nodes.continuation.EffectFiber fiber =
            com.mezze.interpreter.nodes.continuation.EffectFiber.current();
        if (fiber == null) {
            // No fiber, but a chain may be installed on this thread (e.g.
            // we're inside the body of an Async.spawn that inherited the
            // parent's handler chain, where chain-dispatch — not fiber
            // suspend — is the dispatch path). If the suspended thunk is
            // a deferred effect call, try chain-dispatching it here, the
            // same way OpCallNode does its no-fiber fallback. Without
            // this, force() returns `this` unforced (#285) and the
            // unevaluated MezzeSuspended leaks back to the caller.
            return forceWithoutFiber(callNode);
        }
        if (computation instanceof BlockNode.ComposedBlockThunk composedThunk) {
            return forceComposedBlock(composedThunk, fiber, callNode);
        }
        if (computation.isDeferredEffectCall()) {
            // Forward the effect content hash so the dispatcher can
            // disambiguate when two effects in scope declare an op with
            // the same simple name (#246). Dropping it here would silently
            // misroute on force, reintroducing the bug for any op that
            // was deferred outside a perform and forced inside one.
            return fiber.suspend(new com.mezze.interpreter.nodes.continuation.OpCall(
                computation.getOperationName(),
                computation.getArguments(),
                computation.getEffectContentHash()
            ));
        }
        // Regular function thunk — no effect to dispatch, just execute.
        return computation.execute(callNode);
    }

    @TruffleBoundary
    private Object forceWithoutFiber(IndirectCallNode callNode) {
        if (!computation.isDeferredEffectCall()) {
            return this;
        }
        com.mezze.interpreter.nodes.continuation.HandlerChainEntry[] chain =
            com.mezze.interpreter.nodes.continuation.EffectFiber.snapshotHandlerChainEntries();
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
     * Force a composed block thunk under the current fiber: execute the
     * original effect (which yields to the driver), then run the
     * remaining statements that were closed over the original
     * MaterializedFrame.
     */
    @TruffleBoundary
    private Object forceComposedBlock(
            BlockNode.ComposedBlockThunk composedThunk,
            com.mezze.interpreter.nodes.continuation.EffectFiber fiber,
            IndirectCallNode callNode) {
        MezzeThunk originalThunk = composedThunk.getOriginalThunk();
        MezzeNode[] remainingStatements = composedThunk.getRemainingStatements();
        MaterializedFrame frame = composedThunk.getMaterializedFrame();

        Object effectResult;
        if (originalThunk instanceof BlockNode.ComposedBlockThunk nested) {
            effectResult = forceComposedBlock(nested, fiber, callNode);
        } else if (originalThunk.isDeferredEffectCall()) {
            effectResult = fiber.suspend(new com.mezze.interpreter.nodes.continuation.OpCall(
                originalThunk.getOperationName(),
                originalThunk.getArguments(),
                originalThunk.getEffectContentHash()
            ));
        } else {
            effectResult = originalThunk.execute(callNode);
        }

        // If the effect result is still suspended (no fiber was current at
        // the recursive force), preserve the composition for later.
        if (effectResult instanceof MezzeSuspended ms) {
            return new MezzeSuspended(
                new BlockNode.ComposedBlockThunk(
                    ms.getComputation(),
                    remainingStatements,
                    frame
                ),
                ms.getUnhandledEffects()
            );
        }

        Object result = MezzeUnit.INSTANCE;
        for (MezzeNode stmt : remainingStatements) {
            result = stmt.execute(frame);
            if (result instanceof MezzeSuspended) {
                return result;
            }
        }
        return result;
    }

    /**
     * Check if a value is a suspended computation.
     */
    public static boolean isSuspended(Object value) {
        return value instanceof MezzeSuspended;
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
